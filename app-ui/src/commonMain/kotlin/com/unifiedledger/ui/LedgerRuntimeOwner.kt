@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.unifiedledger.ui

import com.unifiedledger.application.ImportIntakeSessionIdentity
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
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

    /**
     * Section 4.2 hard precondition: no close/switch may run while leases are in flight (or while
     * another transition holds the owner). [startup] performs the same "close before open" it
     * always did, so it must apply the same precondition rather than closing a graph out from
     * under running business work. The graph is NOT closed and the state is unchanged.
     */
    data class Blocked(
        val inFlightLeases: Int,
    ) : LedgerStartupResult

    /**
     * P7-06 06.1 fix (review P3-6): typed rejection for mere transition contention (the owner's
     * mutex was held by another transition). Distinct from [Blocked] so a zero in-flight count is
     * never misreported as a lease-blocked startup (the [CloseResult.TransitionInProgress]
     * precedent): the graph was not touched and the caller may retry.
     */
    data object TransitionInProgress : LedgerStartupResult
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

    /**
     * Typed rejection for mere transition contention (the owner's mutex was held by another
     * transition). Distinct from [QuiesceBlocked] so a zero in-flight count is never misreported
     * as a lease-blocked close: the graph was not touched and the caller may retry.
     */
    data object TransitionInProgress : CloseResult
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

    /**
     * Typed rejection for mere transition contention (another transition holds the owner). The
     * graph was not touched and the caller may retry; never reported as a lease block.
     */
    data object TransitionInProgress : ReopenResult

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

    /**
     * The graph currently held; only *mutated* under [mutex]. Marked `@Volatile` because the
     * public facade projection and [LedgerLeaseScope] read it from arbitrary threads (the
     * `state`/`activeGeneration` snapshots are read lock-free the same way); the mutex still
     * serializes every mutation.
     */
    @Volatile
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

    /**
     * The current graph's facade, or null when no graph is open.
     *
     * P7-06 06.1 fix (review REJECT): this raw accessor is deliberately NOT public. Handing it to
     * the product would let a `facade.*` call bypass the operation lease and make the in-flight
     * count vacuous (spec section 4.3's conversion obligation is "every `facade.*` entry point").
     * The product consumes the facade only through [LedgerLeaseScope], which acquires a lease
     * around every call; the composition roots therefore never reach the raw projection. `internal`
     * because [LedgerLeaseScope] lives in this module and the composition roots must not see it.
     */
    internal val facade: P503LedgerFacade?
        get() = activeGraph?.let(facadeOf)

    /** The number of in-flight leases (diagnostics/tests). */
    val inFlightLeaseCount: Int
        get() = inFlightLeases.load()

    /**
     * Section 5.1 startup order steps 1-5: open the active generation and enter Ready. Non-suspend
     * because both composition roots call it from a synchronous startup path.
     *
     * Section 4.2 precondition (review fix): [startup] performs the "close before open" resource
     * safety of both controllers, so it must NOT run while leases are in flight — otherwise a
     * retry could close a graph under a running business call. A non-zero in-flight count returns
     * the typed [LedgerStartupResult.Blocked] and a concurrent transition holding the owner
     * returns the distinct [LedgerStartupResult.TransitionInProgress]; neither touches the graph.
     */
    fun startup(): LedgerStartupResult {
        if (!mutex.tryLock()) return LedgerStartupResult.TransitionInProgress
        try {
            val inFlight = inFlightLeases.load()
            if (inFlight > 0) return LedgerStartupResult.Blocked(inFlight)
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
                    return QuiesceResult.Quiesced
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
     * graph is NOT closed (the plan's "never release a still-running SQLite call early"). Mere
     * transition contention yields the distinct [CloseResult.TransitionInProgress] so a zero
     * in-flight count is never misreported as a lease block.
     *
     * Kotlin `internal` cannot span Gradle modules, so this is documented rather than enforced:
     * it is the owner-internal action behind [quiesce] and [reopen], not a freely callable public
     * close (spec section 4.2).
     */
    fun closeActiveGraph(): CloseResult {
        if (!mutex.tryLock()) return CloseResult.TransitionInProgress
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
     * [ReopenResult.QuiesceBlocked] is returned and nothing is closed or reopened; mere transition
     * contention returns the distinct [ReopenResult.TransitionInProgress]. A failure fails closed
     * ([ReopenResult.Failed]) and never exposes a half-open graph.
     */
    fun reopen(target: GenerationSelection = GenerationSelection.ActivePointer): ReopenResult {
        if (!mutex.tryLock()) return ReopenResult.TransitionInProgress
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
 * Section 4.3/4.4 landing rule (review Fix 2): a result captured under [capturedGeneration] may
 * land only while that generation is still the active one; otherwise it must be discarded. A late
 * callback from a superseded graph must never pollute the new graph (P706-A07). Pure so the
 * discard decision is directly testable.
 */
internal fun shouldDiscardLandingResult(
    capturedGeneration: Generation?,
    activeGeneration: Generation?,
): Boolean = capturedGeneration == null || capturedGeneration != activeGeneration

/**
 * P7-06 06.1 (D-176; spec section 4.3): the lease-scoped facade accessor — the choke point every
 * `facade.*` call must pass through. It acquires an operation lease for the duration of
 * [withFacade]/[probe], exposes the generation captured at acquisition to the block (so a landing
 * hop can discard a result whose generation is no longer active via [isCurrentGeneration]), and
 * always releases the lease.
 *
 * STRUCTURAL GUARANTEE (why this shape, per the review's Fix 1): [LedgerRuntimeOwner.facade] is
 * `internal`, so the composition roots — which live in other Gradle modules — cannot obtain the
 * raw facade at all. [P503App] takes THIS type instead of a facade, and the only facade-typed
 * members it exposes directly are the lease-free pure construction constants and stateless
 * projections ([ledgerId], [currency], [ledgerClock], [parseAmount], [parseOccurredAt],
 * [summarizeActivity], [importPlatformKind]) — none of which touch the ledger. Every actual
 * business read/write is reachable only inside [withFacade]/[probe], which by construction hold a
 * lease. Therefore no `facade.*` business call can escape a lease.
 *
 * The alternative the review suggested — a delegating wrapper implementing "the same facade
 * interface" — is not expressible here: [P503LedgerFacade] is a concrete final class exposing
 * dozens of concrete final collaborator types (`QueryLedgerCurrentState`, `ExecuteCatalogCommand`,
 * the resolvers, ...) with no interface seam, so a wrapper would have to re-declare and re-wrap
 * every collaborator type. The scoped accessor is the shape that covers all entry points with a
 * single structural guarantee, so it is the one implemented.
 */
class LedgerLeaseScope(
    private val owner: LedgerRuntimeOwner<*>,
) {
    /**
     * The lease-free pure construction constants of the current facade. These never touch the
     * ledger, so they need no operation lease. Non-null whenever the composition renders
     * [P503App] (the product is composed only in the Ready state).
     */
    val ledgerId: LedgerId
        get() = requiredFacade().ledgerId

    val currency: CurrencyUnit
        get() = requiredFacade().currency

    val ledgerClock: LedgerClock
        get() = requiredFacade().ledgerClock

    val parseAmount: ParseManualExpenseAmount
        get() = requiredFacade().parseAmount

    val parseOccurredAt: ParseManualExpenseOccurredAt
        get() = requiredFacade().parseOccurredAt

    /**
     * The stateless activity-summary projection (pure over the state it is handed; never touches
     * the ledger). Lease-free for the same reason as the constants above.
     */
    val summarizeActivity: SummarizeLedgerActivity
        get() = requiredFacade().summarizeActivity

    /** The platform kind of the import surface (a construction constant), or null when unwired. */
    val importPlatformKind: ImportPlatformKind?
        get() = owner.facade?.importPlatformKind

    /**
     * Lease-free pure surfaces: the id mints, the parsers and the platform pick port/channel. None
     * of these touch the ledger (they mint UUIDs, parse text, or launch a platform picker), so
     * they need no operation lease; the ledger-touching work of the import pipeline still runs
     * inside [withFacade].
     */
    val requestIdSource: ManualExpenseRequestIdSource
        get() = requiredFacade().requestIdSource

    val importConfirmRequestIdSource: (() -> String)?
        get() = owner.facade?.importConfirmRequestIdSource

    val importPickResultChannel: ImportFilePickResultChannel?
        get() = owner.facade?.importPickResultChannel

    val importFilePickPort: ImportFilePickPort?
        get() = owner.facade?.importFilePickPort

    fun importDuplicateReviewIds(): ImportDuplicateReviewIds? = owner.facade?.importDuplicateReviewIds?.invoke()

    fun importIntakeSessionFactory(): ImportIntakeSessionIdentity? = owner.facade?.importIntakeSessionFactory?.invoke()

    /**
     * P7-06 06.B (D-177; spec section 3): the shared backup-export use case the composition root
     * bound to this scope, or null when the surface is absent. Lease-free: the use case acquires and
     * releases its OWN operation lease for the whole export (spec section 3.1), so exposing it here
     * adds no second lease.
     */
    var backupExport: BackupExportUseCase? = null

    /**
     * P7-06 06.B (D-177; spec sections 3.1/3.2): the launch for the CURRENT active generation —
     * the export request plus the generation it was resolved under. Null when the export surface
     * is absent or the owner has no active generation (not Ready).
     *
     * The generation is returned so the host's landing hop can apply the SAME discard rule every
     * other generation-bound call uses ([isCurrentGeneration]): [BackupExportResult] itself carries
     * no generation (it is a plain success/failure/cancelled union), so the export cannot rely on
     * its own payload for the check. The export's own lease (acquired inside the use case) protects
     * the snapshot from a mid-export close/reopen; this capture is what makes a result that LANDS
     * after a reopen (a new generation) discarded rather than presented as the new graph's result.
     */
    fun backupExportLaunch(password: String): BackupExportLaunch? {
        val useCase = backupExport ?: return null
        val generation = owner.activeGeneration ?: return null
        return BackupExportLaunch(useCase.requestFor(password, generation), generation)
    }

    /**
     * Which optional surfaces the current facade has wired (pure null checks on the composition
     * wiring; never touches the ledger). The host uses these to render no dead affordances.
     */
    val surfaces: LedgerSurfaces
        get() {
            val facade = owner.facade ?: return LedgerSurfaces()
            return LedgerSurfaces(
                ledgerView = facade.queryMonthlyActivity != null || facade.queryLedgerEntryRows != null,
                recycleBin = facade.queryRecycleBin != null,
                catalogCommands = facade.executeCatalogCommand != null,
                counterpartyCommands = facade.counterpartyCommands != null,
                correction = facade.correctTransactionVersion != null,
                voidTransaction = facade.voidTransaction != null,
                restore = facade.restoreTransaction != null,
                importDuplicateReview = facade.importDuplicateReview != null,
                // P7-06 06.1 fix: the REAL batch-confirm wiring probe — a lease-free null check
                // of the use-case FACTORY itself (a pure field read, never an invocation). The
                // factory's invocation reads the catalog, so it must never run here (outside a
                // lease); [P503App.authorizeImportBatch] checks this wiring and the dispatch run
                // invokes the factory under a lease.
                importBatchConfirm = facade.importConfirmUseCases != null,
                // P7-06 06.1 fix (review P3-4): the import-review list and the optional
                // snapshot-aware commit-status resolvers are pure facade null checks, so the host
                // restores base's unwired early-return instead of falling through to a typed
                // Unavailable failure banner on a surface the composition never wired.
                importReviewRows = facade.queryImportReviewRows != null,
                incomeCommitStatus = facade.resolveIncomeCommitStatus != null,
                transferCommitStatus = facade.resolveTransferCommitStatus != null,
                lendingCommitStatus = facade.resolveLendingCommitStatus != null,
                // P7-06 06.B (D-177): the export use case is bound to this scope by the
                // composition root; a pure field read, never an invocation.
                backupExport = backupExport != null,
            )
        }

    private fun requiredFacade(): P503LedgerFacade {
        // P7-06 06.1 fix (review P3-5): the lease-free pure constants must DEGRADE, never throw.
        // 06.D's reopen runs Ready -> Closing -> Reopening, and a recomposition can still read
        // `ledger.ledgerId` (and the other construction constants) during that window, when
        // `owner.facade` is already null. These members are construction constants of the ledger,
        // so the last graph observed while Ready is still the correct source; caching it keeps a
        // transient transition from crashing the composition. The error below is now reachable
        // only if P503App is composed before the owner has ever reached Ready, which the product
        // composition roots never do.
        val current = owner.facade
        if (current != null) {
            lastFacade = current
            return current
        }
        return lastFacade
            ?: error("the ledger facade is unavailable before the runtime has ever reached Ready")
    }

    /**
     * The last facade observed while the owner was Ready. Only the lease-free pure constants read
     * it (see [requiredFacade]); every ledger-touching call still goes through [withFacade]/
     * [leased], which refuse to run when the owner is not Ready.
     */
    @Volatile
    private var lastFacade: P503LedgerFacade? = null

    /**
     * Section 4.3/4.4: whether a result captured under [generation] still belongs to the active
     * graph. A landing hop must call this and DISCARD the result when it returns false.
     */
    fun isCurrentGeneration(generation: Generation?): Boolean = !shouldDiscardLandingResult(generation, owner.activeGeneration)

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

    /** [withFacade] without the generation capture, for call sites that need no landing discard. */
    fun <T> probe(block: (P503LedgerFacade) -> T): T? = withFacade { facade, _ -> block(facade) }

    /**
     * Section 4.3/4.4: like [withFacade], but reports whether a lease was acquired so a landing
     * hop can still distinguish "the runtime refused the work" from "the work produced a null
     * result", and carries the captured generation for the discard check.
     */
    fun <T> leased(block: (P503LedgerFacade, Generation) -> T): LeaseOutcome<T> {
        val facade = owner.facade ?: return LeaseOutcome.NotReady
        return when (val acquired = owner.acquireLease()) {
            is LeaseAcquireResult.Acquired -> {
                try {
                    LeaseOutcome.Completed(acquired.lease.generation, block(facade, acquired.lease.generation))
                } finally {
                    acquired.lease.close()
                }
            }
            LeaseAcquireResult.RuntimeNotReady -> LeaseOutcome.NotReady
        }
    }
}

/** The outcome of [LedgerLeaseScope.leased] (spec section 4.3/4.4). */
sealed interface LeaseOutcome<out T> {
    /** The block ran under a lease; [generation] is the captured acquire-time generation. */
    data class Completed<T>(
        val generation: Generation,
        val value: T,
    ) : LeaseOutcome<T>

    /** The runtime is not Ready; the block did not run (typed `RuntimeNotReady`). */
    data object NotReady : LeaseOutcome<Nothing>
}

/**
 * Which optional business surfaces the current facade has wired. Every field is a pure
 * composition-wiring probe (never a ledger read), so the host can decide whether to render an
 * affordance without holding a lease.
 */
data class LedgerSurfaces(
    val ledgerView: Boolean = false,
    val recycleBin: Boolean = false,
    val catalogCommands: Boolean = false,
    val counterpartyCommands: Boolean = false,
    val correction: Boolean = false,
    val voidTransaction: Boolean = false,
    val restore: Boolean = false,
    val importDuplicateReview: Boolean = false,
    val importBatchConfirm: Boolean = false,
    val importReviewRows: Boolean = false,
    val incomeCommitStatus: Boolean = false,
    val transferCommitStatus: Boolean = false,
    val lendingCommitStatus: Boolean = false,
    // P7-06 06.B (D-177; spec section 3): the composition root bound the backup-export use case.
    // A pure field probe, never an invocation, so the host renders the export entry only when the
    // surface exists (the "no dead affordance" convention).
    val backupExport: Boolean = false,
)

/**
 * P7-06 06.B (D-177; spec sections 3.1/3.2): one export launch — the request resolved for the
 * active generation plus the generation it was resolved under. The generation lets the host's
 * landing hop discard a result captured under a superseded graph ([LedgerLeaseScope.isCurrentGeneration]),
 * because [BackupExportResult] itself carries none.
 */
class BackupExportLaunch(
    val request: BackupExportRequest,
    val generation: Generation,
)

/**
 * The target of one graph open (section 3.2 / 4.5): [allowCreateOnOpen] is true ONLY for a
 * genuine fresh install, so no non-fresh path can let the create-on-open factory build an empty
 * ledger.
 */
data class LedgerOpenTarget(
    val mainFile: String,
    val allowCreateOnOpen: Boolean,
)

/**
 * Section 5.1 steps 1-4 plus the rule 1 legacy upgrade, shared by both composition roots: resolve
 * the stable storage, perform the non-destructive upgrade when a legacy database exists, open the
 * selected generation through [openGraph] and publish the atomic pointer. Throws
 * [LedgerStorageRejectedException] (fail-closed) or propagates an open failure.
 *
 * Post-open failure cleanup (review Fix 3): the graph is opened BEFORE the pointer publish (the
 * frozen rule 1 (c) -> (d) order), so a failure in the remaining fallible steps
 * ([publishActivePointer] / [removeLegacyFiles]) must close the graph before rethrowing —
 * otherwise the platform driver leaks. [closeGraph] is the composition root's own close action.
 *
 * KNOWN RESIDUAL (registered, not implemented in 06.1; assigned to 06.D): a crash AFTER the fresh
 * install creates the generation directory and the database but BEFORE the pointer publish leaves
 * a generation directory with no pointer. Section 3.2 rule 4 makes that a permanent
 * `POINTER_MISSING` fail-closed (never a silent empty database), and 06.1 has no recovery for it.
 * The spec section 3.2 rule-2 cleanup (and its 06.D counterpart) is deliberately out of 06.1
 * scope; this is the registered residual the review asked to record.
 */
fun <G> openStableStorageLedger(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    legacyMainFile: String?,
    closeGraph: (G) -> Unit,
    openGraph: (LedgerOpenTarget) -> G,
): G {
    val plan =
        when (val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile)) {
            is LedgerStorageResolution.Planned -> resolution.plan
            is LedgerStorageResolution.Rejected -> throw LedgerStorageRejectedException(resolution.failure)
        }
    // P7-06 06.B (D-177; spec section 6): sweep the private backup staging on every start. A
    // process killed mid-export leaves its snapshot/container behind; this is the spec's
    // "next start" cleanup fallback. It runs before any export can begin and is best effort
    // (never fails startup), and it only removes the frozen staging prefixes.
    sweepBackupStaging(fileSystem, layout)
    return when (plan) {
        is LedgerStoragePlan.OpenGeneration ->
            openGraph(
                LedgerOpenTarget(plan.mainFile, allowCreateOnOpen = false),
            )
        is LedgerStoragePlan.FreshInstall -> {
            // Rule 3: the ONLY path allowed to let the factory create the database. The pointer is
            // published only after a successful open + authoritative read-back, so a later start
            // selects this generation through the pointer.
            fileSystem.createDirectories(plan.generationDirectory)
            val graph = openGraph(LedgerOpenTarget(plan.mainFile, allowCreateOnOpen = true))
            try {
                publishActivePointer(fileSystem, layout, 1)
            } catch (failure: Throwable) {
                runCatching { closeGraph(graph) }
                throw failure
            }
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
            val graph = openGraph(LedgerOpenTarget(plan.mainFile, allowCreateOnOpen = false))
            try {
                publishActivePointer(fileSystem, layout, 1)
                removeLegacyFiles(fileSystem, plan.legacyMainFile)
            } catch (failure: Throwable) {
                // The open already succeeded, so the driver exists: close it before rethrowing so
                // a post-open failure never leaks the graph/driver.
                runCatching { closeGraph(graph) }
                throw failure
            }
            graph
        }
    }
}
