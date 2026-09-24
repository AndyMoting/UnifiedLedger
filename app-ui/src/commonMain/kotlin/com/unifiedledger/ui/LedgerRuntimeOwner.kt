@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.unifiedledger.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt

/*
 * P7-06 06.1 (D-176; spec `docs/specs/2026-09-24-p7-06-stable-storage-runtime-owner-design.md`
 * section 4): the single runtime owner of the active ledger graph. It holds at most one graph
 * (generation), hands out operation leases to every piece of business work, supports
 * `quiesce -> close -> reopen`, and fails closed instead of ever silently creating an empty
 * database.
 *
 * Concurrency (spec section 4.2, P2-5): an owner-internal [Mutex] guards the state transitions
 * and the in-flight lease accounting, and atomic fields carry the lock-free snapshot reads
 * ([state], [activeGeneration], the lease count). `kotlinx.coroutines.sync` rides the coroutines
 * core artifact already on the compile classpath (transitively via compose.runtime /
 * sqldelight); this adds no dependency and no version catalog entry.
 *
 * Non-blocking acquisition (spec section 4.2): [acquireLease] is non-suspend and never waits —
 * it takes the mutex with `tryLock`, so a caller on the UI thread receives a typed
 * [LeaseAcquireResult.RuntimeNotReady] instead of blocking when a transition is in progress.
 */

/** The process-internal, monotonically increasing generation (spec section 4.4). */
typealias Generation = Int

/** The owner's internal state (spec section 5.2, the minimal 06.1 chain plus the 06.D skeletons). */
enum class LedgerRuntimeState {
    /** Resolving the stable-storage host location and plan (section 5.1 step 1-2). */
    ResolvingStorage,

    /** Selecting the active generation from the pointer (section 5.1 step 3). */
    SelectingGeneration,

    /** Opening the selected generation and authoritative-reading it back (step 4). */
    Opening,

    /** The graph is open and leases may be acquired (step 5). */
    Ready,

    /** Rejecting new leases and waiting for in-flight leases to drain (section 4.2). */
    Quiescing,

    /** Closing the active graph (section 4.2). */
    Closing,

    /** Reopening after a close (section 4.2). */
    Reopening,

    /** No active graph after an explicit close; a [LedgerRuntimeOwner.reopen] can follow. */
    Closed,

    /** Fail-closed: no graph is exposed and no lease can be acquired. */
    StartupError,
}

/** The generation a [LedgerRuntimeOwner.reopen] should target (spec section 4.2). */
sealed interface GenerationSelection {
    /** Reopen the generation named by the on-disk active pointer (the default). */
    data object ActivePointer : GenerationSelection

    /** Reopen an explicitly named on-disk generation (06.D switch skeleton). */
    data class Explicit(
        val generation: Int,
    ) : GenerationSelection
}

/** A successful startup. */
sealed interface LedgerStartupResult {
    data class Started(
        val generation: Generation,
    ) : LedgerStartupResult

    data class Failed(
        val cause: Throwable,
    ) : LedgerStartupResult
}

/** Section 4.2: `acquireLease` outcome. */
sealed interface LeaseAcquireResult {
    data class Acquired(
        val lease: LedgerLease,
    ) : LeaseAcquireResult

    /** Typed `RuntimeNotReady`: the owner is not Ready (quiesce/switch/closed/error). */
    data object RuntimeNotReady : LeaseAcquireResult
}

/** Section 4.2: `quiesce` outcome. */
sealed interface QuiesceResult {
    /** Zero in-flight leases: the caller may now close/reopen. */
    data object Quiesced : QuiesceResult

    /** Typed `QuiesceBlocked`: leases did not drain within the timeout. */
    data class QuiesceBlocked(
        val inFlightLeases: Int,
    ) : QuiesceResult
}

/** Section 4.2: `closeActiveGraph` outcome. */
sealed interface CloseResult {
    data object Closed : CloseResult

    /** Typed rejection: leases are still in flight, so the graph is NOT closed. */
    data class QuiesceBlocked(
        val inFlightLeases: Int,
    ) : CloseResult
}

/** Section 4.2: `reopen` outcome. */
sealed interface ReopenResult {
    data class Reopened(
        val generation: Generation,
    ) : ReopenResult

    /** Typed rejection: leases are still in flight, so nothing was closed or reopened. */
    data class QuiesceBlocked(
        val inFlightLeases: Int,
    ) : ReopenResult

    /** Fail-closed: the open/authoritative read-back failed; no half-open graph is exposed. */
    data class Failed(
        val cause: Throwable,
    ) : ReopenResult
}

/**
 * An operation lease (spec section 4.2). It carries the generation captured at acquisition
 * (section 4.3: landing hops must discard a result whose captured generation is no longer the
 * active one) and releases exactly once on [close].
 */
class LedgerLease internal constructor(
    /** The generation captured at acquisition time. */
    val generation: Generation,
    private val release: (Generation) -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /** Idempotent release. */
    override fun close() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) {
            release(generation)
        }
    }
}

/** Default quiesce timeout: bounded so a caller can never wait forever (spec section 4.2 P3-3). */
const val LEDGER_QUIESCE_TIMEOUT_MILLIS: Long = 5_000L

/**
 * The single runtime owner (spec section 4.1). Generic over the platform graph type so the
 * shared module stays free of platform types; the composition root supplies the open/close
 * actions and the facade projection.
 *
 * @param openActiveGeneration opens the active generation from stable storage (performing any
 *   legacy upgrade and the authoritative read-back) and returns the built graph; it throws on any
 *   failure, which the owner maps to fail-closed.
 * @param closeGraph releases the platform connection; idempotent.
 * @param facadeOf projects the business facade out of a graph.
 */

class LedgerRuntimeOwner<G : Any>(
    /**
     * Opens the requested generation and performs the authoritative read-back, returning the built
     * graph. Throwing fails the startup/reopen closed. 06.1 always passes
     * [GenerationSelection.ActivePointer] (the on-disk pointer is the only selection); the 06.D
     * switch supplies a real [GenerationSelection.Explicit] implementation.
     */
    private val openGeneration: (GenerationSelection) -> G,
    private val closeGraph: (G) -> Unit,
    private val facadeOf: (G) -> P503LedgerFacade,
    private val quiesceTimeoutMillis: Long = LEDGER_QUIESCE_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()

    /** Lock-free snapshot of the runtime state (spec section 4.2). */
    @Volatile
    var state: LedgerRuntimeState = LedgerRuntimeState.ResolvingStorage
        private set

    /** Lock-free snapshot of the active generation; null when not Ready (spec section 4.2). */
    @Volatile
    var activeGeneration: Generation? = null
        private set

    private val inFlightLeases = AtomicInt(0)

    /** The graph currently held; only touched under [mutex]. */
    private var activeGraph: G? = null

    /**
     * The monotonic generation counter (section 4.4). It only ever advances on a successful
     * open/reopen, so closing the graph (which clears [activeGeneration]) can never reset the
     * next generation back to 1.
     */
    private var generationCounter: Generation = 0

    /**
     * Completed when the in-flight lease count reaches zero during a quiesce. Published under
     * [mutex] before the post-publish count re-check, so a concurrent final release either sees
     * this signal or has not yet decremented past the re-check (see [quiesce]).
     */
    @Volatile
    private var zeroLeaseSignal: CompletableDeferred<Unit>? = null

    /** The current graph's facade, or null when no graph is open. */
    val facade: P503LedgerFacade?
        get() = activeGraph?.let(facadeOf)

    /** The number of in-flight leases (diagnostics/tests). */
    val inFlightLeaseCount: Int
        get() = inFlightLeases.load()

    /**
     * Section 5.1 startup order steps 1-5: open the active generation and enter Ready. Non-suspend
     * because both composition roots call it from a synchronous startup path.
     */
    fun startup(): LedgerStartupResult {
        if (!mutex.tryLock()) return LedgerStartupResult.Failed(IllegalStateException("startup raced with another transition"))
        try {
            // A restart must never leak a previously held connection (the existing "close before
            // open" resource-safety discipline of both controllers, now under the owner).
            closeHeldGraphLocked()
            state = LedgerRuntimeState.ResolvingStorage
            state = LedgerRuntimeState.SelectingGeneration
            state = LedgerRuntimeState.Opening
            val graph =
                try {
                    openGeneration(GenerationSelection.ActivePointer)
                } catch (failure: Throwable) {
                    state = LedgerRuntimeState.StartupError
                    activeGeneration = null
                    return LedgerStartupResult.Failed(failure)
                }
            activeGraph = graph
            val generation = generationCounter + 1
            generationCounter = generation
            activeGeneration = generation
            state = LedgerRuntimeState.Ready
            return LedgerStartupResult.Started(generation)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Section 4.2: acquire an operation lease. Only succeeds while Ready; never blocks (the mutex
     * is taken with `tryLock`), so a UI-thread caller receives [LeaseAcquireResult.RuntimeNotReady]
     * instead of waiting.
     */
    fun acquireLease(): LeaseAcquireResult {
        if (!mutex.tryLock()) return LeaseAcquireResult.RuntimeNotReady
        try {
            if (state != LedgerRuntimeState.Ready) return LeaseAcquireResult.RuntimeNotReady
            val generation = activeGeneration ?: return LeaseAcquireResult.RuntimeNotReady
            inFlightLeases.fetchAndAdd(1)
            return LeaseAcquireResult.Acquired(LedgerLease(generation, ::releaseLease))
        } finally {
            mutex.unlock()
        }
    }

    private fun releaseLease(generation: Generation) {
        // Lock-free decrement: a lease may be released from any thread and must never wait on a
        // transition (section 4.2: the release happens after the business call returns, in a
        // finally, and must not deadlock a quiesce that is holding the mutex).
        if (inFlightLeases.fetchAndAdd(-1) == 1) {
            // The last lease drained. The signal is published under the mutex before quiesce's
            // post-publish re-check, so either this read sees it or the re-check already saw 0.
            zeroLeaseSignal?.complete(Unit)
        }
    }

    /**
     * Section 4.2: atomically reject further acquisitions and wait for every in-flight lease to
     * release. Bounded by [quiesceTimeoutMillis]: a caller that itself holds a lease (the P3-3
     * self-deadlock shape) or a stuck lease yields the typed [QuiesceResult.QuiesceBlocked]
     * instead of waiting forever. The caller contract is that the quiesce-initiating context must
     * not hold a lease.
     */
    suspend fun quiesce(): QuiesceResult {
        val signal = CompletableDeferred<Unit>()
        val blocked: QuiesceResult
        mutex.withLock {
            when (state) {
                LedgerRuntimeState.Ready -> {
                    state = LedgerRuntimeState.Quiescing
                    zeroLeaseSignal = signal
                }
                LedgerRuntimeState.Quiescing -> {
                    // A second concurrent quiesce joins the same drain rather than starting a new
                    // one; its own signal is unused but harmless.
                    zeroLeaseSignal = zeroLeaseSignal ?: signal
                }
                else -> {
                    // Already quiesced/closed/errored: nothing is in flight, so report success
                    // without changing the state.
                    blocked = QuiesceResult.Quiesced
                    return@withLock
                }
            }
            // Close the publish/decrement race: if the last release ran before the signal above
            // became visible, it could not have completed it.
            if (inFlightLeases.load() == 0) signal.complete(Unit)
        }
        val drained = withTimeoutOrNull(quiesceTimeoutMillis) { signal.await() }
        if (drained == null) {
            return QuiesceResult.QuiesceBlocked(inFlightLeases.load())
        }
        return QuiesceResult.Quiesced
    }

    /**
     * Section 4.2: close the active graph and release its driver. Idempotent. Precondition: zero
     * in-flight leases — a non-zero count yields the typed [CloseResult.QuiesceBlocked] and the
     * graph is NOT closed (the plan's "never release a still-running SQLite call early").
     *
     * Kotlin `internal` cannot span Gradle modules, so this is documented rather than enforced:
     * it is the owner-internal action behind [quiesce] and [reopen], not a freely callable public
     * close (spec section 4.2).
     */
    fun closeActiveGraph(): CloseResult {
        if (!mutex.tryLock()) return CloseResult.QuiesceBlocked(inFlightLeases.load())
        try {
            val inFlight = inFlightLeases.load()
            if (inFlight > 0) return CloseResult.QuiesceBlocked(inFlight)
            state = LedgerRuntimeState.Closing
            closeHeldGraphLocked()
            state = LedgerRuntimeState.Closed
            return CloseResult.Closed
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Section 4.2: open the target generation and rebuild the graph, incrementing
     * [activeGeneration] on success. Precondition: zero in-flight leases — otherwise the typed
     * [ReopenResult.QuiesceBlocked] is returned and nothing is closed or reopened. A failure
     * fails closed ([ReopenResult.Failed]) and never exposes a half-open graph.
     */
    fun reopen(target: GenerationSelection = GenerationSelection.ActivePointer): ReopenResult {
        if (!mutex.tryLock()) return ReopenResult.QuiesceBlocked(inFlightLeases.load())
        try {
            val inFlight = inFlightLeases.load()
            if (inFlight > 0) return ReopenResult.QuiesceBlocked(inFlight)
            // Single active graph: the held graph is always released before a new one is opened.
            state = LedgerRuntimeState.Closing
            closeHeldGraphLocked()
            state = LedgerRuntimeState.Reopening
            val graph =
                try {
                    openGeneration(target)
                } catch (failure: Throwable) {
                    state = LedgerRuntimeState.StartupError
                    activeGeneration = null
                    return ReopenResult.Failed(failure)
                }
            activeGraph = graph
            val generation = generationCounter + 1
            generationCounter = generation
            activeGeneration = generation
            state = LedgerRuntimeState.Ready
            return ReopenResult.Reopened(generation)
        } finally {
            mutex.unlock()
        }
    }

    /** Closes and forgets the held graph; callers must hold [mutex]. */
    private fun closeHeldGraphLocked() {
        val graph = activeGraph ?: return
        activeGraph = null
        activeGeneration = null
        runCatching { closeGraph(graph) }
    }
}

/**
 * Thrown by [openStableStorageLedger] when the stable-storage resolution fails closed (section
 * 3.2 rule 4 / section 4.5). The composition roots let it propagate so the owner maps it to
 * StartupError; it is never a silent fallback to a fresh install.
 */
class LedgerStorageRejectedException(
    val failure: LedgerStorageFailure,
) : RuntimeException("stable storage rejected: $failure")

/**
 * P7-06 06.1 (D-176; spec section 4.3): the lease-scoped facade accessor — the choke point every
 * `facade.*` call is meant to pass through. It acquires an operation lease for the duration of
 * [withFacade], exposes the generation captured at acquisition to the block (so a landing hop can
 * discard a result whose generation is no longer active), and always releases the lease.
 *
 * The accessor is deliberately the smallest possible surface: a caller cannot reach the facade
 * except inside [withFacade], so "no `facade.*` call escapes the lease" is a structural property
 * of this type rather than a per-call-site convention.
 *
 * INTEGRATION NOTE (deliberate, reported): `P503App` currently reads the facade directly at ~30
 * host entry points. Retrofitting all of them in this slice is a large mechanical edit with
 * regression risk on a frozen UI surface, so this slice ships the accessor and its tests and
 * leaves the call-site conversion as the follow-up (the spec section 7 A07 row already assigns
 * the end-to-end "reject new leases and wait for in-flight work" to 06.4). Until that conversion
 * lands, `P503App` is NOT lease-gated; the owner's single-active-graph and fail-closed guarantees
 * still hold because every graph is built and closed by the owner.
 */
class LedgerLeaseScope<G : Any>(
    private val owner: LedgerRuntimeOwner<G>,
) {
    /** Whether a lease can be acquired right now (Ready). */
    fun isReady(): Boolean = owner.state == LedgerRuntimeState.Ready

    /**
     * Runs [block] while holding an operation lease over the facade. Returns null (and runs
     * nothing) when the owner is not Ready — the typed `RuntimeNotReady` path, never a blocked
     * UI thread.
     */
    fun <T> withFacade(block: (P503LedgerFacade, Generation) -> T): T? {
        val facade = owner.facade ?: return null
        return when (val acquired = owner.acquireLease()) {
            is LeaseAcquireResult.Acquired -> {
                try {
                    block(facade, acquired.lease.generation)
                } finally {
                    acquired.lease.close()
                }
            }
            LeaseAcquireResult.RuntimeNotReady -> null
        }
    }
}

/**
 * The target of one graph open (section 3.2 / 4.5): [allowCreateOnOpen] is true ONLY for a
 * genuine fresh install, so no non-fresh path can let the create-on-open factory build an empty
 * ledger.
 */
data class LedgerOpenTarget(
    val generation: Int,
    val mainFile: String,
    val allowCreateOnOpen: Boolean,
)

/**
 * Section 5.1 steps 1-4 plus the rule 1 legacy upgrade, shared by both composition roots: resolve
 * the stable storage, perform the non-destructive upgrade when a legacy database exists, open the
 * selected generation through [openGraph] and publish the atomic pointer. Throws
 * [LedgerStorageRejectedException] (fail-closed) or propagates an open failure.
 */
fun <G> openStableStorageLedger(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    legacyMainFile: String?,
    openGraph: (LedgerOpenTarget) -> G,
): G {
    val plan =
        when (val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile)) {
            is LedgerStorageResolution.Planned -> resolution.plan
            is LedgerStorageResolution.Rejected -> throw LedgerStorageRejectedException(resolution.failure)
        }
    return when (plan) {
        is LedgerStoragePlan.OpenGeneration ->
            openGraph(
                LedgerOpenTarget(plan.generation, plan.mainFile, allowCreateOnOpen = false),
            )
        is LedgerStoragePlan.FreshInstall -> {
            // Rule 3: the ONLY path allowed to let the factory create the database. The pointer is
            // published only after a successful open + authoritative read-back, so a later start
            // selects this generation through the pointer.
            fileSystem.createDirectories(plan.generationDirectory)
            val graph = openGraph(LedgerOpenTarget(1, plan.mainFile, allowCreateOnOpen = true))
            publishActivePointer(fileSystem, layout, 1)
            graph
        }
        is LedgerStoragePlan.UpgradeLegacy -> {
            // Rule 1, the frozen non-destructive order: (a) copy, (b) fsync the new set, (c) open
            // and authoritative-read-back, (d) publish the atomic pointer (+ dir fsync), and only
            // then (e) remove the legacy files. Any throw leaves the legacy set untouched.
            stageLegacyUpgrade(fileSystem, plan.legacyMainFile, plan.generationDirectory, plan.mainFile)
            if (!isUsableSqliteMainFile(fileSystem, plan.mainFile)) {
                // A legacy file that is not a usable SQLite database must never reach the
                // create-on-open factory (section 4.5).
                throw LedgerStorageRejectedException(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
            }
            val graph = openGraph(LedgerOpenTarget(1, plan.mainFile, allowCreateOnOpen = false))
            publishActivePointer(fileSystem, layout, 1)
            removeLegacyFiles(fileSystem, plan.legacyMainFile)
            graph
        }
    }
}
