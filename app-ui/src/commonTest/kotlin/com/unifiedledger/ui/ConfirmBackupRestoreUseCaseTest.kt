package com.unifiedledger.ui

import com.unifiedledger.application.backup.BACKUP_DISK_HEADROOM_BYTES
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.BackupGcmDecryptor
import com.unifiedledger.application.backup.BackupGcmEncryptor
import com.unifiedledger.application.backup.BackupSha256Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P7-06 06.D (D-182; spec `docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`
 * sections 3, 4 and 7): the confirm & switch WIRING matrix. Every test maps to one failure
 * branch of the frozen nine-step sequence or one JVM matrix row of spec section 7, with the
 * pointer bytes as the assertion anchor:
 *
 * - the four D-179 section 7.5 revalidations each reject STALE with zero switch (spec 3.1);
 * - the confirm-time peak disk precheck rejects a known shortfall AND fails closed on an
 *   unknown usable-space value (spec 3.2, the D-179 section 10 item 9 split);
 * - a quiesce timeout, a generation advance after the quiesce and a blocked close each postpone
 *   with zero switch and restore the runtime through the frozen restoration program (spec
 *   3.3/3.4/3.5/3.10): the owner ends Ready on the old graph;
 * - a staging failure, a leftover-directory deletion failure and a prepared-journal write
 *   failure each abort BEFORE the publish, discard the half-built directory and restore the
 *   runtime (spec 3.6/3.10), and the leftover-directory retry proves delete-then-stage;
 * - the happy path publishes gen-(n+1) in the stageLegacyUpgrade fsync order with
 *   journal=prepared before the pointer and journal=switched after it, then commits with the
 *   journal removed (spec 3.6/3.7/3.9);
 * - a post-publish reopen failure rolls back to a byte-identical old pointer with the staged
 *   directory (including sidecars) deleted and the old graph reopened, while a rollback that
 *   also fails lands in the fail-closed RecoveryRequired without any retry loop (spec 3.8).
 *
 * The owner is the same lambda harness as the 06.1 owner tests; the process generation and the
 * disk generation are deliberately independent layers (spec section 5.2). The gate fake parks
 * the confirm coroutine inside an injected file operation so a concurrent transition can be
 * interleaved deterministically at the only real suspension windows.
 */
class ConfirmBackupRestoreUseCaseTest {
    private val hostDirectory = "/host"
    private val stagingDirectory = "/host/backup-staging"
    private val snapshotFile = "$stagingDirectory/restore-snapshot-tok"
    private val migratedFile = "$stagingDirectory/restore-migrated-tok"
    private val pointerFile = "/host/active-generation"
    private val journalFile = "/host/switch-journal"
    private val gen1Directory = "/host/ledger-generations/gen-1"
    private val gen1Main = "$gen1Directory/ledger.db"
    private val gen2Directory = "/host/ledger-generations/gen-2"
    private val gen2Main = "$gen2Directory/ledger.db"
    private val targetLedgerId = "ledger-local-test"

    // The staging artifacts are SQLite-like bytes: the confirm flow runs the pre-open
    // consistency gate on the staged main file, so a non-SQLite artifact must be rejected —
    // the happy path needs a gate-passing one.
    private val payload = sqliteLikeBytes()
    private val payloadDigest = confirmDigestOf(payload)

    /** The streaming-crypto fake: only the SHA-256 primitive is real enough for the recheck. */
    private class ConfirmFakeCrypto : BackupCryptoPrimitives {
        override fun randomBytes(count: Int): ByteArray = ByteArray(count)

        override fun deriveKey(
            password: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray = ByteArray(keyLengthBits / 8)

        override fun sha256Digest(): BackupSha256Digest =
            object : BackupSha256Digest {
                var buffer = ByteArray(0)

                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    buffer += bytes.copyOfRange(offset, offset + length)
                }

                override fun digest(): ByteArray = confirmDigestOf(buffer)
            }

        override fun gcmEncryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmEncryptor =
            object : BackupGcmEncryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray = ByteArray(0)

                override fun doFinal(): ByteArray = ByteArray(0)
            }

        override fun gcmDecryptor(
            key: ByteArray,
            iv: ByteArray,
            aad: ByteArray,
        ): BackupGcmDecryptor =
            object : BackupGcmDecryptor {
                override fun update(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): ByteArray = ByteArray(0)

                override fun doFinal(): ByteArray = ByteArray(0)
            }
    }

    /**
     * The owner open/close harness. It can inject a bounded number of failing opens (the
     * switch reopen vs the rollback reopen), park ONE open inside itself (a concurrent
     * transition holding the owner mutex) and count every attempt (the no-loop assertion).
     */
    private class OwnerHandle {
        var failedOpensRemaining = 0
        var parkTimeoutMillis = 100L
        var attempts = 0

        private var parkRequested = false
        private val parkedSignal = CompletableDeferred<Unit>()
        private val neverCompletes = CompletableDeferred<Unit>()

        /** Completes when the parked open has ENTERED the park (the mutex is held). */
        val parked: CompletableDeferred<Unit> get() = parkedSignal

        fun failNextOpens(count: Int) {
            failedOpensRemaining = count
        }

        fun parkNextOpen() {
            parkRequested = true
        }

        fun open(): Any {
            attempts += 1
            if (failedOpensRemaining > 0) {
                failedOpensRemaining -= 1
                throw IllegalStateException("injected open failure")
            }
            if (parkRequested) {
                parkRequested = false
                parkedSignal.complete(Unit)
                runBlocking { withTimeoutOrNull(parkTimeoutMillis) { neverCompletes.await() } }
            }
            return Any()
        }
    }

    private class ConfirmFixture(
        val fileSystem: LedgerFileSystemFake,
        val owner: LedgerRuntimeOwner<Any>,
        val handle: OwnerHandle,
    )

    /**
     * Delegates to the shared fake and adds the two deterministic interleave hooks: a
     * usable-space gate (the confirm precheck park) and a read-bytes hook (the post-quiesce
     * pointer-read park), a usable-space value queue for the post-staging recheck, and the
     * failure injections the typed fakes cannot express: a THROWING usable-space read (P3-1),
     * a fail-once atomic write (P3-3's second journal write), a fail-once delete (P3-4's
     * transient step-9 journal removal) and ERROR-tier (non-Runtime-Exception) failures at an
     * atomic write or a file fsync (P2-1's escape paths).
     */
    private class GatedFake(
        private val inner: LedgerFileSystemFake,
    ) : LedgerFileSystem by inner {
        private val usableSpaceValues = ArrayDeque<Long?>()
        private val writeAtomicCalls = mutableMapOf<String, Int>()
        var onUsableSpace: (() -> Unit)? = null
        var onReadBytes: ((String) -> Unit)? = null
        var throwOnUsableSpaceRead = false

        /** path -> the 1-based call index that must fail. */
        var failWriteAtomicAtCall: Pair<String, Int>? = null

        /** When true, the failing atomic write throws an [InternalError] instead of a RuntimeException. */
        var failWriteAtomicWithError = false

        /** path -> the 1-based call index that must fail. */
        var failDeleteAtCall: Pair<String, Int>? = null
        private val deleteCalls = mutableMapOf<String, Int>()

        /** The fsync path whose call throws an [InternalError] (unconditional). */
        var throwOnFsyncFile: String? = null

        /**
         * F-7: the path whose `exists` probe throws a RuntimeException, on the given 1-based call
         * for that path. `readPointerGeneration` calls `exists` OUTSIDE its own try, so an escape
         * at the step-4 (post-quiesce) read genuinely escapes the guarded region; the confirm's
         * last-resort catch must repair the runtime before rethrowing. The fail-loud
         * [LedgerFileSystem] contract permits such a throw.
         */
        var throwOnExistsAtCall: Pair<String, Int>? = null
        private val existsCalls = mutableMapOf<String, Int>()

        fun enqueueUsableSpace(value: Long?) {
            usableSpaceValues.addLast(value)
        }

        override fun exists(path: String): Boolean {
            val spec = throwOnExistsAtCall
            if (spec != null && spec.first == path) {
                val call = (existsCalls[path] ?: 0) + 1
                existsCalls[path] = call
                if (call == spec.second) throw InjectedFileSystemFailure("exists:$path#$call")
            }
            return inner.exists(path)
        }

        override fun usableSpace(path: String): Long? {
            if (throwOnUsableSpaceRead) throw IllegalStateException("injected usable-space read failure")
            onUsableSpace?.invoke()
            return if (usableSpaceValues.isEmpty()) inner.usableSpace(path) else usableSpaceValues.removeFirst()
        }

        override fun readBytes(path: String): ByteArray {
            onReadBytes?.invoke(path)
            return inner.readBytes(path)
        }

        override fun writeAtomic(
            path: String,
            bytes: ByteArray,
        ) {
            val spec = failWriteAtomicAtCall
            if (spec != null && spec.first == path) {
                val call = (writeAtomicCalls[path] ?: 0) + 1
                writeAtomicCalls[path] = call
                if (call == spec.second) {
                    if (failWriteAtomicWithError) throw InternalError("injected error at writeAtomic:$path")
                    throw InjectedFileSystemFailure("writeAtomic:$path#$call")
                }
            }
            inner.writeAtomic(path, bytes)
        }

        override fun fsyncFile(path: String) {
            val target = throwOnFsyncFile
            if (target == path) throw InternalError("injected error at fsyncFile:$path")
            inner.fsyncFile(path)
        }

        override fun delete(path: String) {
            val spec = failDeleteAtCall
            if (spec != null && spec.first == path) {
                val call = (deleteCalls[path] ?: 0) + 1
                deleteCalls[path] = call
                if (call == spec.second) throw InjectedFileSystemFailure("delete:$path#$call")
            }
            inner.delete(path)
        }
    }

    private fun fixture(
        quiesceTimeoutMillis: Long = 5_000L,
        withMigration: Boolean = true,
        seededGen2Leftover: Boolean = false,
        migratedSidecar: Boolean = false,
    ): ConfirmFixture {
        val fileSystem = LedgerFileSystemFake()
        fileSystem.putFile(gen1Main, sqliteLikeBytes())
        fileSystem.putFile(pointerFile, "gen-1")
        fileSystem.putFile(snapshotFile, payload)
        if (withMigration) {
            fileSystem.putFile(migratedFile, payload)
            if (migratedSidecar) {
                fileSystem.putFile("$migratedFile-wal", ByteArray(64) { 2 })
                fileSystem.putFile("$migratedFile-shm", ByteArray(32) { 5 })
            }
        }
        if (seededGen2Leftover) {
            fileSystem.putFile(gen2Main, "stale-leftover-bytes".encodeToByteArray())
            fileSystem.putFile("$gen2Main-wal", ByteArray(16) { 3 })
            fileSystem.putFile("$gen2Main-shm", ByteArray(16) { 4 })
        }
        fileSystem.usableSpaceBytes = 1L shl 40
        val handle = OwnerHandle()
        val owner =
            LedgerRuntimeOwner<Any>(
                openGeneration = { handle.open() },
                closeGraph = {},
                facadeOf = { throw AssertionError("no facade in confirm tests") },
                quiesceTimeoutMillis = quiesceTimeoutMillis,
            )
        owner.startup()
        return ConfirmFixture(fileSystem, owner, handle)
    }

    private fun useCase(
        fileSystem: LedgerFileSystem,
        owner: LedgerRuntimeOwner<Any>,
        reDrainAttempts: Int = 2,
        reopenRetryPauseMillis: Long = 100L,
    ): ConfirmBackupRestoreUseCase =
        ConfirmBackupRestoreUseCase(
            owner = owner,
            fileSystem = fileSystem,
            layout = ledgerStorageLayout(fileSystem, hostDirectory),
            crypto = ConfirmFakeCrypto(),
            targetLedgerId = targetLedgerId,
            reDrainAttempts = reDrainAttempts,
            reopenRetryPauseMillis = reopenRetryPauseMillis,
        )

    /**
     * P1 regression harness (06.D): an owner whose `openGeneration` is PRODUCTION-SHAPED — the
     * ActivePointer route runs the REAL [resolveLedgerStorage], so the 06.D switch-journal gate is
     * LIVE, exactly as in both composition roots (`openStableStorageLedger`). The bare-lambda
     * harness in [fixture] never consults the journal, which is why the original suite missed the
     * defect where step 8's own reopen consumed the journal the flow had just written.
     *
     * The Explicit route opens the named generation through [openExplicitGeneration] (no
     * resolution, no gate), mirroring the production closures. Every opened generation is recorded
     * so a test can prove WHICH route step 8 took.
     *
     * The sweep is deliberately not run here: it is orthogonal to the gate and would delete the
     * token's own staging artifacts mid-flow, obscuring the journal-gate behaviour under test.
     */
    private fun productionShapedOwner(
        fileSystem: LedgerFileSystemFake,
        openedGenerations: MutableList<Int> = mutableListOf(),
    ): LedgerRuntimeOwner<Any> {
        val layout = ledgerStorageLayout(fileSystem, hostDirectory)
        return LedgerRuntimeOwner(
            openGeneration = { selection ->
                openGenerationForSelection(
                    selection = selection,
                    openActivePointer = {
                        val resolution = resolveLedgerStorage(fileSystem, layout, legacyMainFile = null)
                        when (resolution) {
                            is LedgerStorageResolution.Rejected -> throw LedgerStorageRejectedException(resolution.failure)
                            is LedgerStorageResolution.Planned -> {
                                val plan = resolution.plan
                                val generation =
                                    when (plan) {
                                        is LedgerStoragePlan.OpenGeneration -> plan.generation
                                        else -> error("the production-shaped fixture only opens an existing generation")
                                    }
                                openedGenerations += generation
                                Any()
                            }
                        }
                    },
                    openExplicit = { generation ->
                        openExplicitGeneration(fileSystem, layout, generation) {
                            openedGenerations += generation
                            Any()
                        }
                    },
                )
            },
            closeGraph = {},
            facadeOf = { throw AssertionError("no facade in confirm tests") },
            quiesceTimeoutMillis = 5_000L,
        )
    }

    /**
     * Seeds the production-shaped host: gen-1 active, pointer gen-1, and the token's own staging
     * artifacts (snapshot + migrated copy) exactly as the 06.C preflight leaves them.
     */
    private fun seedProductionShapedHost(
        fileSystem: LedgerFileSystemFake,
        withMigration: Boolean = true,
    ) {
        // The generations directory itself must exist (the fake only materializes immediate
        // parents), or resolveLedgerStorage would see no generation directory at all and resolve a
        // fresh install instead of the pointer-selected generation.
        fileSystem.putDirectory("/host/ledger-generations")
        fileSystem.putFile(gen1Main, sqliteLikeBytes())
        fileSystem.putFile(pointerFile, "gen-1")
        fileSystem.putFile(snapshotFile, payload)
        if (withMigration) {
            fileSystem.putFile(migratedFile, payload)
        }
        fileSystem.usableSpaceBytes = 1L shl 40
    }

    private fun token(
        owner: LedgerRuntimeOwner<Any>,
        withMigration: Boolean = true,
        generation: Generation = owner.activeGeneration ?: 1,
        target: String = targetLedgerId,
        snapshotDigest: ByteArray = payloadDigest,
        migratedDigest: ByteArray? = if (withMigration) payloadDigest else null,
    ): RestorePreflightToken =
        RestorePreflightToken(
            handle = "tok",
            generation = generation,
            targetLedgerId = target,
            authenticatedArtifactSha256 = snapshotDigest,
            migratedArtifactSha256 = migratedDigest,
        )

    private fun assertPointerUnchanged(fixture: ConfirmFixture) {
        assertEquals("gen-1", fixture.fileSystem.fileBytes(pointerFile)?.decodeToString())
    }

    private fun CoroutineScope.confirmInBackground(
        fixture: ConfirmFixture,
        fileSystem: LedgerFileSystem,
        reDrainAttempts: Int = 2,
        reopenRetryPauseMillis: Long = 100L,
    ): CompletableDeferred<BackupRestoreSwitchResult> {
        val result = CompletableDeferred<BackupRestoreSwitchResult>()
        launch(Dispatchers.Default) {
            result.complete(useCase(fileSystem, fixture.owner, reDrainAttempts, reopenRetryPauseMillis).confirm(token(fixture.owner)))
        }
        return result
    }

    // ---------------------------------------------------------------- happy path (spec 3.6/3.7/3.9)

    @Test
    fun theHappyPathPublishesGenPlusOneWithTheFrozenOrderingAndCommits() =
        runBlocking {
            val f = fixture(migratedSidecar = true)
            val result = useCase(f.fileSystem, f.owner).confirm(token(f.owner))

            val committed = assertIs<BackupRestoreSwitchResult.Committed>(result)
            // The landing guard binds the POST-reopen runtime generation (startup = 1, the
            // switch reopen = 2), not the pre-confirm generation the token captured.
            assertEquals(f.owner.activeGeneration, committed.runtimeGeneration)
            assertEquals(2, committed.runtimeGeneration)
            // The pointer names the next DISK generation; the old generation is retained.
            assertEquals("gen-2", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertTrue(f.fileSystem.hasDirectory(gen1Directory))
            assertTrue(payload.contentEquals(f.fileSystem.fileBytes(gen2Main)))
            assertTrue(ByteArray(64) { 2 }.contentEquals(f.fileSystem.fileBytes("$gen2Main-wal")))
            // The journal is gone (committed) and the owner is Ready on the new graph.
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
            // The frozen ordering: copy main, copy sidecar, fsync each, fsync the directory,
            // journal=prepared, THEN the pointer publish, THEN journal=switched.
            val ops = f.fileSystem.operationsSnapshot()
            val copyMain = ops.indexOf("copy:$migratedFile->$gen2Main")
            val copySidecar = ops.indexOf("copy:$migratedFile-wal->$gen2Main-wal")
            val fsyncMain = ops.indexOf("fsyncFile:$gen2Main")
            val fsyncSidecar = ops.indexOf("fsyncFile:$gen2Main-wal")
            val fsyncDirectory = ops.indexOf("fsyncDirectory:$gen2Directory")
            val preparedWrite = ops.indexOf("writeAtomic:$journalFile")
            val pointerWrite = ops.indexOf("writeAtomic:$pointerFile")
            val switchedWrite = ops.lastIndexOf("writeAtomic:$journalFile")
            assertTrue(copyMain >= 0 && copySidecar > copyMain)
            assertTrue(fsyncMain > copySidecar && fsyncSidecar > fsyncMain && fsyncDirectory > fsyncSidecar)
            assertTrue(preparedWrite > fsyncDirectory && pointerWrite > preparedWrite && switchedWrite > pointerWrite)
            // P3-5: the committed journal removal itself is fsynced (a durable deletion).
            val journalRemove = ops.indexOf("delete:$journalFile")
            assertTrue(journalRemove > switchedWrite)
            assertTrue(ops.drop(journalRemove + 1).contains("fsyncDirectory:$hostDirectory"))
        }

    // ---------------------------------------------------------------- step 1: the four revalidations (spec 3.1)

    @Test
    fun aMissingSnapshotArtifactIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.delete(snapshotFile)

            val result = useCase(f.fileSystem, f.owner).confirm(token(f.owner))

            val stale = assertIs<BackupRestoreSwitchResult.Stale>(result)
            assertEquals(BackupRestoreStaleReason.StagingArtifactMissing, stale.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, stale.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aMissingMigratedCopyIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.delete(migratedFile)

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreStaleReason.StagingArtifactMissing, stale.reason)
            assertPointerUnchanged(f)
        }

    @Test
    fun aSnapshotDigestMismatchIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.putFile(snapshotFile, payload.copyOfRange(1, payload.size))

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreStaleReason.DigestMismatch, stale.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, stale.runtime)
            assertPointerUnchanged(f)
        }

    @Test
    fun aMigratedDigestMismatchIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.putFile(migratedFile, payload.copyOfRange(1, payload.size))

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreStaleReason.DigestMismatch, stale.reason)
            assertPointerUnchanged(f)
        }

    @Test
    fun anUnreadableStagingArtifactIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.failOn = "openRead:$snapshotFile"

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreStaleReason.ArtifactUnreadable, stale.reason)
            assertPointerUnchanged(f)
        }

    @Test
    fun anUnreadableMigratedCopyIsStaleWithZeroSwitch() =
        runBlocking {
            // P3-4: the migrated variant of the unreadable-artifact rejection — the revalidation
            // that fails is the migrated copy's digest read, still stale with zero switch.
            val f = fixture()
            f.fileSystem.failOn = "openRead:$migratedFile"

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreStaleReason.ArtifactUnreadable, stale.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, stale.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aSupersededTokenGenerationIsStaleWithZeroSwitchBeforeAnyQuiesce() =
        runBlocking {
            val f = fixture()
            val staleToken = token(f.owner, generation = (f.owner.activeGeneration ?: 1) + 5)

            val stale = assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(staleToken))

            assertEquals(BackupRestoreStaleReason.GenerationSuperseded, stale.reason)
            assertPointerUnchanged(f)
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aForeignTargetLedgerTokenIsStaleWithZeroSwitch() =
        runBlocking {
            val f = fixture()

            val stale =
                assertIs<BackupRestoreSwitchResult.Stale>(useCase(f.fileSystem, f.owner).confirm(token(f.owner, target = "another-ledger")))

            assertEquals(BackupRestoreStaleReason.TargetLedgerMismatch, stale.reason)
            assertPointerUnchanged(f)
        }

    // ---------------------------------------------------------------- step 2: the confirm-time disk precheck (spec 3.2)

    @Test
    fun anInsufficientConfirmTimeDiskPrecheckIsATypedRejectionWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.usableSpaceBytes = 1L

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.InsufficientDiskSpace, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            // The precheck runs BEFORE the quiesce, so the owner never left Ready.
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aDiskSatisfyingOnlyTheSmallerFormulaIsRejectedByTheConfirmPrecheck() =
        runBlocking {
            // F-2 (spec section 3.2): the frozen formula includes new_generation_db_size, lower
            // bounded by the migrated copy's length. A disk that satisfies ONLY the smaller
            // formula (snapshot + migrated + retained old + 64 MiB, WITHOUT the new generation)
            // must still be rejected — otherwise the staging write would run out mid-switch.
            val f = fixture()
            val snapshotSize = f.fileSystem.length(snapshotFile)
            val migratedSize = f.fileSystem.length(migratedFile)
            val oldGenerationSize = f.fileSystem.length(gen1Main)
            // The exact smaller formula, so the new-generation term is the sole difference.
            val smallerFormula = snapshotSize + migratedSize + oldGenerationSize + BACKUP_DISK_HEADROOM_BYTES
            f.fileSystem.usableSpaceBytes = smallerFormula

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.InsufficientDiskSpace, aborted.reason)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)

            // One byte more than the full formula (smaller + the new-generation lower bound) is
            // accepted, proving the new term is exactly the migrated copy's length.
            f.fileSystem.usableSpaceBytes = smallerFormula + migratedSize + 1
            assertIs<BackupRestoreSwitchResult.Committed>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))
            Unit
        }

    @Test
    fun anUnknownUsableSpaceFailsClosedAtTheConfirmPrecheck() =
        runBlocking {
            val f = fixture()
            f.fileSystem.usableSpaceBytes = null

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            // The section 3.2 ruling: the confirm-time peak fails CLOSED on unknown space.
            assertEquals(BackupRestoreAbortReason.UnknownDiskSpace, aborted.reason)
            assertPointerUnchanged(f)
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aThrowingUsableSpaceReadFailsClosedAsUnknownAtThePrecheck() =
        runBlocking {
            val f = fixture()
            val gated = GatedFake(f.fileSystem)
            gated.throwOnUsableSpaceRead = true

            val aborted = assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(gated, f.owner).confirm(token(f.owner)))

            // P3-1: "cannot verify" is one outcome whether the value is absent or the read
            // throws — both fail closed as UnknownDiskSpace with zero switch.
            assertEquals(BackupRestoreAbortReason.UnknownDiskSpace, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aPointerThatVanishesAfterTheQuiesceAbortsAndRestoresTheRuntime() =
        runBlocking {
            val f = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(f.fileSystem)
            gated.onUsableSpace = {
                // The step-2 pointer read already passed; make the pointer vanish while the
                // confirm is parked, so the post-quiesce read (step 4) finds nothing.
                gateEntered.complete(Unit)
                runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
                f.fileSystem.delete(pointerFile)
            }
            val result = confirmInBackground(f, gated)
            gateEntered.await()
            releaseGate.complete(Unit)

            val aborted = assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(result.await())

            assertEquals(BackupRestoreAbortReason.ActivePointerUnreadable, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            // The restoration program ran: the owner is Ready again on the reopened old graph.
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aStagedMainFileFailingTheSqliteGateAbortsBeforePublishAndCleans() =
        runBlocking {
            val f = fixture()
            // Bind the token to a real digest of a NON-SQLite artifact: every revalidation
            // passes, and the assembly-time consistency gate is what rejects it.
            val badArtifact = ByteArray(2048) { 1 }
            f.fileSystem.putFile(snapshotFile, badArtifact)
            f.fileSystem.putFile(migratedFile, badArtifact)
            val badToken =
                token(
                    f.owner,
                    snapshotDigest = confirmDigestOf(badArtifact),
                    migratedDigest = confirmDigestOf(badArtifact),
                )

            val aborted = assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(badToken))

            assertEquals(BackupRestoreAbortReason.StagingFailed, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aStuckLeaseHonestlyReportsTheRuntimeAsNotRestored() =
        runBlocking {
            val f = fixture(quiesceTimeoutMillis = 50L)
            val lease = assertIs<LeaseAcquireResult.Acquired>(f.owner.acquireLease()).lease
            // The lease NEVER releases: both re-drain attempts time out, so the owner honestly
            // stays Quiescing — the one sanctioned exception of the restoration program.
            val result = confirmInBackground(f, f.fileSystem)

            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(result.await())

            assertEquals(BackupRestorePostponeReason.QuiesceBlocked, postponed.reason)
            assertEquals(BackupRestoreRuntimeOutcome.NotRestored, postponed.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Quiescing, f.owner.state)
            lease.close()
        }

    @Test
    fun anUnreadableActivePointerAbortsBeforePublishWithZeroSwitch() =
        runBlocking {
            val f = fixture()
            f.fileSystem.delete(pointerFile)

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.ActivePointerUnreadable, aborted.reason)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    // ---------------------------------------------------------------- step 3/5: postponed + restoration (spec 3.3/3.5/3.10)

    @Test
    fun aQuiesceTimeoutPostponesTheSwitchAndRestoresTheRuntime() =
        runBlocking {
            val f = fixture(quiesceTimeoutMillis = 50L)
            val lease = assertIs<LeaseAcquireResult.Acquired>(f.owner.acquireLease()).lease
            // The holder releases inside the restoration re-drain window: the first quiesce
            // times out at 50ms and the re-drain (4 bounded attempts) converges at the release.
            val result = confirmInBackground(f, f.fileSystem, reDrainAttempts = 4)
            delay(150)
            lease.close()

            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(result.await())

            assertEquals(BackupRestorePostponeReason.QuiesceBlocked, postponed.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, postponed.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
            assertEquals(0, f.owner.inFlightLeaseCount)
        }

    @Test
    fun aGenerationAdvanceAfterQuiesceIsStaleAndRestoresTheRuntime() =
        runBlocking {
            val f = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(f.fileSystem)
            gated.onUsableSpace = {
                // Park the confirm inside the step-2 precheck and advance the RUNTIME
                // generation while it waits — the exact race the post-quiesce recheck guards.
                gateEntered.complete(Unit)
                runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
                assertIs<ReopenResult.Reopened>(f.owner.reopen(GenerationSelection.ActivePointer))
            }
            val result = confirmInBackground(f, gated)
            gateEntered.await()
            releaseGate.complete(Unit)

            val stale = assertIs<BackupRestoreSwitchResult.Stale>(result.await())

            assertEquals(BackupRestoreStaleReason.GenerationSuperseded, stale.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, stale.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            // The restoration program reopened the old graph: Ready again, one generation on.
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
            assertEquals(3, f.owner.activeGeneration)
            // F-1: the abort carries the POST-restoration generation (3), not the pre-confirm
            // capture (1). The host's landing guard must accept it; the old capture would discard
            // it and wedge the surface forever.
            assertEquals(3, stale.runtimeGeneration)
            assertEquals(
                f.owner.activeGeneration,
                stale.runtimeGeneration,
                "the abort result must bind the owner's post-restoration generation",
            )
        }

    @Test
    fun everyPostQuiesceAbortBindsThePostRestorationGenerationSoTheLandingGuardAcceptsIt() =
        runBlocking {
            // F-1 (both abort families): a blocked close postpones and a staging failure aborts,
            // and each runs the section 3.10 restoration whose reopen advances the generation.
            // Every returned abort must therefore carry the owner's CURRENT generation; the
            // pre-confirm capture would be stale and the host's guard would discard the result.
            val blocked = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(blocked.fileSystem)
            var pointerReads = 0
            gated.onReadBytes = { path ->
                if (path == pointerFile) {
                    pointerReads += 1
                    if (pointerReads == 2) {
                        gateEntered.complete(Unit)
                        runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
                    }
                }
            }
            blocked.handle.parkTimeoutMillis = 100L
            blocked.handle.parkNextOpen()
            val blockedResult = confirmInBackground(blocked, gated, reopenRetryPauseMillis = 300L)
            gateEntered.await()
            launch(Dispatchers.Default) { blocked.owner.reopen(GenerationSelection.ActivePointer) }
            blocked.handle.parked.await()
            releaseGate.complete(Unit)
            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(blockedResult.await())
            assertEquals(blocked.owner.activeGeneration, postponed.runtimeGeneration)

            val staging = fixture()
            staging.fileSystem.failOn = "copy:$migratedFile->$gen2Main"
            val aborted = assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(staging.fileSystem, staging.owner).confirm(token(staging.owner)))
            assertEquals(staging.owner.activeGeneration, aborted.runtimeGeneration)
        }

    @Test
    fun aBlockedClosePostponesTheSwitchAndRestoresTheRuntime() =
        runBlocking {
            val f = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(f.fileSystem)
            var pointerReads = 0
            gated.onReadBytes = { path ->
                if (path == pointerFile) {
                    pointerReads += 1
                    if (pointerReads == 2) {
                        // The SECOND pointer read is the post-quiesce one; park AFTER the
                        // generation recheck passed so the close below hits the contention.
                        gateEntered.complete(Unit)
                        runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
                    }
                }
            }
            // A concurrent reopen parks inside its open and holds the owner mutex; the
            // confirm's closeActiveGraph then fails tryLock with TransitionInProgress.
            f.handle.parkTimeoutMillis = 100L
            f.handle.parkNextOpen()
            val result = confirmInBackground(f, gated, reopenRetryPauseMillis = 300L)
            gateEntered.await()
            launch(Dispatchers.Default) { f.owner.reopen(GenerationSelection.ActivePointer) }
            f.handle.parked.await()
            releaseGate.complete(Unit)

            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(result.await())

            assertEquals(BackupRestorePostponeReason.CloseBlocked, postponed.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, postponed.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            // The restoration program reopened the old graph once the contention cleared.
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    // ---------------------------------------------------------------- step 6: aborts before the publish (spec 3.6/3.10)

    @Test
    fun aStagingFailureAbortsBeforePublishCleansTheHalfBuiltDirectoryAndRestores() =
        runBlocking {
            val f = fixture()
            f.fileSystem.failOn = "copy:$migratedFile->$gen2Main"

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.StagingFailed, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aLeftoverNewGenerationDirectoryIsDeletedThenStaged() =
        runBlocking {
            val f = fixture(seededGen2Leftover = true)

            val committed = assertIs<BackupRestoreSwitchResult.Committed>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(f.owner.activeGeneration, committed.runtimeGeneration)
            // The staged content replaced the leftover, and the stale sidecars are gone (the
            // source has none) — a stale `-wal` must never be picked up by the new graph.
            assertTrue(payload.contentEquals(f.fileSystem.fileBytes(gen2Main)))
            assertFalse(f.fileSystem.hasFile("$gen2Main-wal"))
            assertFalse(f.fileSystem.hasFile("$gen2Main-shm"))
            val ops = f.fileSystem.operationsSnapshot()
            val leftoverDelete = ops.indexOf("delete:$gen2Main")
            val copyMain = ops.indexOf("copy:$migratedFile->$gen2Main")
            assertTrue(leftoverDelete >= 0 && copyMain > leftoverDelete)
        }

    @Test
    fun aLeftoverDirectoryThatCannotBeDeletedAbortsBeforePublish() =
        runBlocking {
            val f = fixture(seededGen2Leftover = true)
            f.fileSystem.failOn = "delete:$gen2Main"

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.StagingFailed, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            // The leftover could not be deleted, so it is honestly still there; the pointer is
            // untouched and the runtime is restored.
            assertTrue(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aPreparedJournalWriteFailureAbortsBeforePublishAndCleans() =
        runBlocking {
            val f = fixture()
            f.fileSystem.failOn = "writeAtomic:$journalFile"

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.JournalWriteFailed, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            // The atomic write threw before publishing anything, and the half-built staged
            // directory was discarded.
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aDiskShortfallFoundAfterStagingAbortsAndCleansTheHalfBuiltDirectory() =
        runBlocking {
            val f = fixture()
            val gated = GatedFake(f.fileSystem)
            // The first usable-space read (the step-2 precheck) passes; the post-staging
            // actual-size recheck (the second read) finds the space gone.
            gated.enqueueUsableSpace(1L shl 40)
            gated.enqueueUsableSpace(1L)

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(gated, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.InsufficientDiskSpace, aborted.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, aborted.runtime)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun anUnknownUsableSpaceAfterStagingFailsClosedAndCleans() =
        runBlocking {
            val f = fixture()
            val gated = GatedFake(f.fileSystem)
            gated.enqueueUsableSpace(1L shl 40)
            gated.enqueueUsableSpace(null)

            val aborted =
                assertIs<BackupRestoreSwitchResult.AbortedBeforePublish>(useCase(gated, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreAbortReason.UnknownDiskSpace, aborted.reason)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    // ---------------------------------------------------------------- steps 7-9: rollback and recovery (spec 3.8)

    @Test
    fun theHappyPathCommitsWhenTheReopenRunsTheRealStartupJournalGate() =
        runBlocking {
            // P1 REGRESSION (06.D): the owner's ActivePointer route runs the REAL
            // resolveLedgerStorage, so the switch-journal gate is LIVE exactly as in production.
            // The confirm writes journal=switched in step 7 and reopens in step 8; if that reopen
            // went through the pointer route, the gate would consume the flow's own journal,
            // republish gen-1 and delete gen-2 — the switch would roll itself back while still
            // reporting Committed. Step 8 must therefore open gen-2 EXPLICITLY.
            val fileSystem = LedgerFileSystemFake()
            seedProductionShapedHost(fileSystem)
            val openedGenerations = mutableListOf<Int>()
            val owner = productionShapedOwner(fileSystem, openedGenerations)
            assertIs<LedgerStartupResult.Started>(owner.startup())

            val result = useCase(fileSystem, owner).confirm(token(owner))

            val committed = assertIs<BackupRestoreSwitchResult.Committed>(result)
            // The switch REALLY committed on disk: the pointer is the NEW generation, the new
            // generation directory still exists, and the journal is gone.
            assertEquals("gen-2", fileSystem.fileBytes(pointerFile)?.decodeToString(), "the pointer must still name the new generation")
            assertTrue(fileSystem.hasDirectory(gen2Directory), "the new generation directory must survive the reopen")
            assertTrue(fileSystem.hasFile(gen2Main), "the new generation main file must survive the reopen")
            assertFalse(fileSystem.hasFile(journalFile), "the journal must be removed (committed)")
            assertEquals(LedgerRuntimeState.Ready, owner.state)
            // Step 8 reopened the EXPLICIT generation (2), not through the pointer.
            assertEquals(2, openedGenerations.last(), "step 8 must open the just-published generation explicitly")
        }

    @Test
    fun aCrashedSwitchedJournalStillRollsBackThroughTheProductionShapedStartupGate() =
        runBlocking {
            // The frozen section 4.1 restart semantics must NOT be weakened by the explicit step-8
            // reopen: a session that crashed in the switched window (pointer = new gen, journal =
            // switched) still rolls back at STARTUP through the pointer route's journal gate.
            val fileSystem = LedgerFileSystemFake()
            fileSystem.putDirectory("/host/ledger-generations")
            fileSystem.putFile(gen1Main, sqliteLikeBytes())
            fileSystem.putFile(gen2Main, sqliteLikeBytes(filler = 1))
            fileSystem.putFile(pointerFile, "gen-2")
            fileSystem.putFile(journalFile, ledgerSwitchJournalBytes(LedgerSwitchJournal(LedgerSwitchJournalStage.Switched, 1, 2)))
            val openedGenerations = mutableListOf<Int>()
            val owner = productionShapedOwner(fileSystem, openedGenerations)

            assertIs<LedgerStartupResult.Started>(owner.startup())

            // The startup gate republished the OLD pointer, removed the journal and discarded the
            // new generation — then opened the old generation.
            assertEquals("gen-1", fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(fileSystem.hasFile(journalFile))
            assertFalse(fileSystem.hasDirectory(gen2Directory))
            assertEquals(listOf(1), openedGenerations, "startup must have rolled back to gen-1")
        }

    @Test
    fun aPostPublishReopenFailureRollsBackToTheByteIdenticalOldPointer() =
        runBlocking {
            val f = fixture(migratedSidecar = true)
            val pointerBefore = f.fileSystem.fileBytes(pointerFile)?.copyOf()
            f.handle.failNextOpens(1)

            val rolledBack = assertIs<BackupRestoreSwitchResult.RolledBack>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(f.owner.activeGeneration, rolledBack.runtimeGeneration)
            // The old pointer bytes are restored exactly, the journal is gone, the staged new
            // generation (main AND sidecar) is deleted, and the old graph is open again.
            assertTrue(pointerBefore?.contentEquals(f.fileSystem.fileBytes(pointerFile)) == true)
            assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            // F-6: the in-process rollback must delete the staged SIDECARS too, not just the
            // directory entry. The fake keeps files and directories in separate maps, so removing
            // the directory alone would leave `gen2Main-wal` behind — a stale `-wal` the next
            // confirm's delete-then-stage could otherwise pick up. Assert the files themselves.
            assertFalse(f.fileSystem.hasFile(gen2Main), "the staged main file must be gone")
            assertFalse(f.fileSystem.hasFile("$gen2Main-wal"), "the staged -wal sidecar must be gone")
            assertFalse(f.fileSystem.hasFile("$gen2Main-shm"), "the staged -shm sidecar must be gone")
            assertTrue(f.fileSystem.hasDirectory(gen1Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aRollbackReopenFailureIsTheFailClosedRecoveryRequiredWithoutLooping() =
        runBlocking {
            val f = fixture()
            // Both the switch reopen and the rollback reopen fail: exactly two open attempts
            // after the startup open — never a retry loop.
            f.handle.failNextOpens(2)
            val attemptsBefore = f.handle.attempts

            val recovery = assertIs<BackupRestoreSwitchResult.RecoveryRequired>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreRecoveryCause.RollbackReopenFailed, recovery.cause)
            // The pointer and journal WERE restored before the failing reopen; the owner is
            // fail-closed (StartupError), never silently retried (the D-176 prohibition).
            assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.StartupError, f.owner.state)
            assertEquals(attemptsBefore + 2, f.handle.attempts)
        }

    @Test
    fun aRollbackPointerRepublishFailureKeepsTheFailClosedRecoveryState() =
        runBlocking {
            val f = fixture()
            f.fileSystem.failOn = "writeAtomic:$pointerFile"

            val recovery = assertIs<BackupRestoreSwitchResult.RecoveryRequired>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreRecoveryCause.RollbackPublishFailed, recovery.cause)
            // Both publish attempts threw before mutating anything: the pointer and the
            // prepared journal remain exactly as the abort left them (the fail-closed shape
            // the startup journal recovery resolves on the next start, spec section 4.3).
            assertPointerUnchanged(f)
            assertTrue(f.fileSystem.hasFile(journalFile))
            assertTrue(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Closed, f.owner.state)
        }

    @Test
    fun aRollbackJournalRemovalFailureKeepsTheFailClosedRecoveryState() =
        runBlocking {
            val f = fixture()
            f.fileSystem.failOn = "delete:$journalFile"

            val recovery = assertIs<BackupRestoreSwitchResult.RecoveryRequired>(useCase(f.fileSystem, f.owner).confirm(token(f.owner)))

            assertEquals(BackupRestoreRecoveryCause.RollbackJournalRemoveFailed, recovery.cause)
            // The old pointer WAS restored; the journal and both generation directories remain,
            // so the next start rolls back deterministically from the journal (spec section 4.3).
            assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertTrue(f.fileSystem.hasFile(journalFile))
            assertTrue(f.fileSystem.hasDirectory(gen2Directory))
            assertTrue(f.fileSystem.hasDirectory(gen1Directory))
        }

    @Test
    fun aSwitchedJournalWriteFailureEntersTheRollback() =
        runBlocking {
            val f = fixture()
            val gated = GatedFake(f.fileSystem)
            // The FIRST atomic journal write (prepared) succeeds; the SECOND (switched, after
            // the pointer publish) fails — the sticky failOn cannot express this, so the
            // counting injection fails exactly call 2.
            gated.failWriteAtomicAtCall = journalFile to 2

            val rolledBack = assertIs<BackupRestoreSwitchResult.RolledBack>(useCase(gated, f.owner).confirm(token(f.owner)))

            assertEquals(f.owner.activeGeneration, rolledBack.runtimeGeneration)
            // The pointer had already moved to gen-2; the rollback restored the old one,
            // removed the journal and discarded the staged directory.
            assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aTransientJournalRemovalFailureAtCommitStillRollsBackSuccessfully() =
        runBlocking {
            val f = fixture()
            val gated = GatedFake(f.fileSystem)
            // P3-4: the step-9 committed journal removal fails ONCE (transient); the rollback's
            // own removal succeeds — a surviving journal means the switch did not commit, so the
            // rollback must run and SUCCEED, not escalate to RecoveryRequired.
            gated.failDeleteAtCall = journalFile to 1

            val rolledBack = assertIs<BackupRestoreSwitchResult.RolledBack>(useCase(gated, f.owner).confirm(token(f.owner)))

            assertEquals(f.owner.activeGeneration, rolledBack.runtimeGeneration)
            // The rollback completed: the old pointer is back, the journal is gone, the staged
            // directory is discarded and the old graph is open.
            assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    // ---------------------------------------------------------------- escape safety (P2-1)

    @Test
    fun aCancelledCallerDuringTheInitialQuiesceWaitRestoresTheRuntimeBeforeRethrowing() =
        runBlocking {
            val f = fixture(quiesceTimeoutMillis = 50L)
            val lease = assertIs<LeaseAcquireResult.Acquired>(f.owner.acquireLease()).lease
            val outcome = CompletableDeferred<Throwable?>()
            val job =
                launch(Dispatchers.Default) {
                    try {
                        useCase(f.fileSystem, f.owner).confirm(token(f.owner))
                        outcome.complete(null)
                    } catch (failure: Throwable) {
                        outcome.complete(failure)
                    }
                }
            // Cancel while the confirm is suspended inside the initial quiesce wait (the holder
            // lease keeps the drain open, so the quiesce cannot have completed yet).
            delay(20)
            assertEquals(1, f.owner.inFlightLeaseCount)
            job.cancel()
            lease.close()

            val failure = assertNotNull(outcome.await())
            assertIs<CancellationException>(failure)
            // The escape repair ran the section 3.10 restoration under NonCancellable: the
            // owner is Ready again on the old graph — never wedged in Quiescing.
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
            assertPointerUnchanged(f)
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertFalse(f.fileSystem.hasDirectory(gen2Directory))
            assertEquals(0, f.owner.inFlightLeaseCount)
        }

    @Test
    fun anEscapedRuntimeExceptionAfterTheQuiesceRestoresTheRuntimeBeforePropagating() {
        // F-7: the fail-loud LedgerFileSystem contract permits a RuntimeException. The step-4
        // post-quiesce pointer read calls `exists` OUTSIDE its own try, so a throw there escapes
        // the guarded region with the owner already in Quiescing and the graph closed. The
        // confirm's last-resort catch must run the section 3.10 restoration before rethrowing, or
        // the owner is wedged in Quiescing forever.
        val f = fixture()
        val gated = GatedFake(f.fileSystem)
        // The FIRST activePointerFile exists probe is the step-2 precheck (before the quiesce);
        // the SECOND is the step-4 post-quiesce read. Fail exactly there.
        gated.throwOnExistsAtCall = pointerFile to 2

        assertFailsWith<InjectedFileSystemFailure> { runBlocking { useCase(gated, f.owner).confirm(token(f.owner)) } }

        // The escape repair restored the owner to Ready on the old graph; the pointer and journal
        // are untouched.
        assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        assertPointerUnchanged(f)
        assertFalse(f.fileSystem.hasFile(journalFile))
        assertFalse(f.fileSystem.hasDirectory(gen2Directory))
    }

    @Test
    fun anErrorAfterCloseRestoresTheRuntimeBeforePropagating() {
        val f = fixture()
        val gated = GatedFake(f.fileSystem)
        // An Error from the fail-loud port inside the step-6 staging fsync: after the graph
        // was closed, before the pointer publish.
        gated.throwOnFsyncFile = gen2Main

        assertFailsWith<InternalError> { runBlocking { useCase(gated, f.owner).confirm(token(f.owner)) } }

        // The escape repair restored the owner to Ready on the old graph; the pointer and the
        // journal are untouched. The half-built staged directory stays inert by contract.
        assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        assertPointerUnchanged(f)
        assertFalse(f.fileSystem.hasFile(journalFile))
    }

    @Test
    fun anErrorInTheSwitchedWindowRunsTheRollbackBeforePropagating() {
        val f = fixture()
        val gated = GatedFake(f.fileSystem)
        // An Error from the step-7 pointer publish: the switched window was being entered.
        gated.failWriteAtomicAtCall = pointerFile to 1
        gated.failWriteAtomicWithError = true

        assertFailsWith<InternalError> { runBlocking { useCase(gated, f.owner).confirm(token(f.owner)) } }

        // The escape repair ran the frozen ROLLBACK disk actions (republish, journal removal,
        // staged-directory discard) and reopened the old graph before rethrowing.
        assertEquals("gen-1", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
        assertFalse(f.fileSystem.hasFile(journalFile))
        assertFalse(f.fileSystem.hasDirectory(gen2Directory))
        assertEquals(LedgerRuntimeState.Ready, f.owner.state)
    }

    // ---------------------------------------------------------------- single flight (P2-2)

    @Test
    fun aRealPostQuiesceAbortLandsThroughTheHostGuardAfterTheRestorationAdvancedTheGeneration() =
        runBlocking {
            // F-1 END-TO-END: the real use case, the real owner (whose restoration reopen ADVANCES
            // the process generation) and the real host landing pipeline. A blocked close postpones
            // after the section 3.10 restoration reopened the old graph at generation 3; the
            // pre-confirm capture is 1. Reverting the landing guard to the capture makes the
            // postponed result get discarded here and the `landed` deferred never completes.
            val f = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(f.fileSystem)
            var pointerReads = 0
            gated.onReadBytes = { path ->
                if (path == pointerFile) {
                    pointerReads += 1
                    if (pointerReads == 2) {
                        gateEntered.complete(Unit)
                        runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
                    }
                }
            }
            f.handle.parkTimeoutMillis = 100L
            f.handle.parkNextOpen()
            val useCase = useCase(gated, f.owner, reopenRetryPauseMillis = 300L)
            val landed = CompletableDeferred<BackupRestoreSwitchResult>()
            val capture = f.owner.activeGeneration ?: 1

            runRestoreConfirm(
                scope = this,
                token = token(f.owner),
                confirm = { useCase.confirm(it) },
                isCurrentGeneration = { f.owner.activeGeneration == it },
                land = { landed.complete(it) },
            )
            gateEntered.await()
            launch(Dispatchers.Default) { f.owner.reopen(GenerationSelection.ActivePointer) }
            f.handle.parked.await()
            releaseGate.complete(Unit)

            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(withTimeoutOrNull(30_000) { landed.await() })
            assertEquals(BackupRestorePostponeReason.CloseBlocked, postponed.reason)
            // The restoration reopened the old graph, advancing the owner past the capture.
            assertEquals(3, f.owner.activeGeneration)
            assertEquals(3, postponed.runtimeGeneration)
            assertTrue(f.owner.activeGeneration != capture, "the restoration must have advanced the generation")
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }

    @Test
    fun aSecondConcurrentConfirmIsTypedRejectedWithZeroSideEffects() =
        runBlocking {
            val f = fixture()
            val gateEntered = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val gated = GatedFake(f.fileSystem)
            gated.onUsableSpace = {
                // Park the FIRST confirm inside its precheck so the second one arrives while
                // the single-flight guard is held.
                gateEntered.complete(Unit)
                runBlocking { withTimeoutOrNull(5_000) { releaseGate.await() } }
            }
            val running = useCase(gated, f.owner)
            val first = CompletableDeferred<BackupRestoreSwitchResult>()
            launch(Dispatchers.Default) { first.complete(running.confirm(token(f.owner))) }
            gateEntered.await()

            // A second confirm — even bound to a DIFFERENT token — is rejected before any
            // effect: no staging, no quiesce, no close.
            val secondToken =
                RestorePreflightToken(
                    handle = "other",
                    generation = f.owner.activeGeneration ?: 1,
                    targetLedgerId = targetLedgerId,
                    authenticatedArtifactSha256 = payloadDigest,
                    migratedArtifactSha256 = null,
                )
            val second = running.confirm(secondToken)

            releaseGate.complete(Unit)
            val postponed = assertIs<BackupRestoreSwitchResult.Postponed>(second)
            assertEquals(BackupRestorePostponeReason.ConfirmAlreadyRunning, postponed.reason)
            assertEquals(BackupRestoreRuntimeOutcome.RestoredReady, postponed.runtime)
            val committed = assertIs<BackupRestoreSwitchResult.Committed>(first.await())
            assertEquals(f.owner.activeGeneration, committed.runtimeGeneration)
            // Exactly one switch happened and the staging state is sane.
            assertEquals("gen-2", f.fileSystem.fileBytes(pointerFile)?.decodeToString())
            assertFalse(f.fileSystem.hasFile(journalFile))
            assertEquals(LedgerRuntimeState.Ready, f.owner.state)
        }
}

/**
 * A deterministic 32-byte stand-in digest for the confirm tests (the same shape as the preflight
 * tests): the streaming fake crypto must reproduce exactly this value for the seeded artifacts.
 */
private fun confirmDigestOf(bytes: ByteArray): ByteArray {
    val out = ByteArray(32)
    for (index in bytes.indices) {
        out[index % 32] = (out[index % 32].toInt() xor bytes[index].toInt()).toByte()
    }
    out[31] = (out[31].toInt() xor bytes.size).toByte()
    return out
}
