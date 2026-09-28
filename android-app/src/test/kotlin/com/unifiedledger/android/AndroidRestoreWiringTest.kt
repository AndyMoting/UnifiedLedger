package com.unifiedledger.android

import com.unifiedledger.application.ConfirmedExpenseReceipt
import com.unifiedledger.application.ConfirmedIncomeReceipt
import com.unifiedledger.application.ConfirmedManualExpenseCommitPort
import com.unifiedledger.application.ConfirmedManualExpenseIdSource
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedTransferReceipt
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.LedgerCurrentStateReadPort
import com.unifiedledger.application.ManualExpenseCommitRecord
import com.unifiedledger.application.ManualExpenseRequestIdSource
import com.unifiedledger.application.ManualIncomeCommitRecord
import com.unifiedledger.application.ManualTransferCommitRecord
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.QueryLedgerCurrentState
import com.unifiedledger.application.QueryManualExpenseOptions
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.SummarizeLedgerActivity
import com.unifiedledger.application.backup.BackupContainerSink
import com.unifiedledger.application.backup.BackupPlaintextReader
import com.unifiedledger.application.backup.BackupPlaintextSource
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.application.backup.writeBackupContainer
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.ui.BackupRestoreSwitchResult
import com.unifiedledger.ui.BackupSourceOpenResult
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.BackupSourceReader
import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.RestoreIsolatedDatabasePort
import com.unifiedledger.ui.RestoreMigrationOutcome
import com.unifiedledger.ui.RestoreOwnerCounts
import com.unifiedledger.ui.RestorePreflightResult
import com.unifiedledger.ui.RestoreValidationFacts
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openExplicitGeneration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The deterministic preflight token the explicit-opener wiring test binds its staging artifacts to. */
private const val RESTORE_WIRING_TEST_TOKEN = "restore-wiring-test-token"

/**
 * P7-06 06.D (D-182; spec sections 5.3/5.4/6): the ANDROID composition-root wiring evidence for the
 * restore surface, JVM-exercisable because the controller's open, log channel and dispatchers are
 * injected (the AndroidStartupControllerTest harness) and the filesystem is the real-filesystem
 * test adapter (DesktopStyleTestFileSystem, the 06.1 precedent). Pins the section 6 construction
 * decisions (the section 5.4 whitelist `{1, 31}` and `currentSupportedSchemaVersion()` injected,
 * one use-case instance set bound to the scope) and the section 5.3 recovery flow: a pointerless
 * start surfaces the recovery face, the user-confirmed adoption publishes the pointer through the
 * frozen primitive and the retried startup reaches Ready, and a DECLINE mutates nothing.
 *
 * It also pins the RC-2 wiring that the confirm switch's step-8 reopen depends on: the
 * composition-root owner satisfies `GenerationSelection.Explicit` (the confirm flow COMMITS instead
 * of rolling back), plus a source-level guard that the root still passes the opener argument.
 *
 * The restore SOURCE port's SAF launch and ruling F's `sizeOf` ContentResolver query are android.jar
 * surfaces; the port shape is pinned by [AndroidBackupSourcePortTest] and the size query belongs to
 * the instrumented suite (the 06.C device-gate pattern), so they are not re-faked here.
 */
class AndroidRestoreWiringTest {
    private class FakeRestoreIsolatedDatabase : RestoreIsolatedDatabasePort {
        val integrityByPath = mutableMapOf<String, Boolean>()
        val versionByPath = mutableMapOf<String, Long>()

        /**
         * P7-06 06.D (D-182): the PREFLIGHT facts. The recovery tests never read them (recovery uses
         * only [integrityCheckOk] and the authoritative version), so they stay unseeded there; the
         * explicit-opener wiring test seeds them so a real decrypted snapshot can pass the 06.C
         * preflight without a real framework SQLite open (the recovery's existing division).
         */
        var observedLedgerIdentities: List<String>? = null
        var validationFacts: RestoreValidationFacts? = null
        var ownerCounts: RestoreOwnerCounts? = null

        override fun readAuthoritativeUserVersion(snapshotPath: String): Long = requireNotNull(versionByPath[snapshotPath]) { "no version seeded" }

        override fun readObservedLedgerIdentities(snapshotPath: String): List<String> = requireNotNull(observedLedgerIdentities) { "no preflight identities seeded" }

        override fun migrateStrictly(
            snapshotPath: String,
            fromVersion: Long,
            supportedVersions: Set<Long>,
        ): RestoreMigrationOutcome = error("not used by the recovery or the current-schema preflight")

        override fun validate(snapshotPath: String): RestoreValidationFacts = requireNotNull(validationFacts) { "no preflight validation seeded" }

        override fun integrityCheckOk(snapshotPath: String): Boolean = integrityByPath[snapshotPath] ?: false

        override fun readOwnerCounts(snapshotPath: String): RestoreOwnerCounts = requireNotNull(ownerCounts) { "no preflight owner counts seeded" }
    }

    private val isolated = FakeRestoreIsolatedDatabase()

    private fun controller(
        host: Path,
        pointerGated: Boolean,
        fileSystem: LedgerFileSystem = DesktopStyleTestFileSystem(),
        sourcePort: BackupSourcePort =
            AndroidBackupSourcePort<String>(
                postToMainThread = { it() },
                launchOpenDocument = {},
                openInputStream = { null },
            ),
        openExplicitOpener: ((Int) -> CloseableLedgerGraph)? = null,
        newToken: () -> String = { RESTORE_WIRING_TEST_TOKEN },
    ): AndroidStartupController {
        val layout = ledgerStorageLayout(fileSystem, host.toString())
        return AndroidStartupController(
            openDatabase = {
                if (pointerGated && !fileSystem.exists(layout.activePointerFile)) {
                    // Exactly the product startup's fail-closed shape for a pointerless host.
                    throw LedgerStorageRejectedException(LedgerStorageFailure.POINTER_MISSING)
                }
                CloseableLedgerGraph(fakeFacade(), close = {})
            },
            logFailure = {},
            startScope = CoroutineScope(Dispatchers.Unconfined),
            backgroundDispatcher = Dispatchers.Unconfined,
            // The production root's argument (App.kt `app()`), passed straight through so the test
            // exercises the REAL controller owner wiring rather than a re-declared one.
            openExplicitGeneration = openExplicitOpener,
            restoreWiring =
                AndroidRestoreWiring(
                    fileSystem = fileSystem,
                    layout = layout,
                    sourcePort = sourcePort,
                    legacyMainFile = java.io.File(host.toFile(), "legacy.db").absolutePath,
                    isolatedDatabase = isolated,
                    newToken = newToken,
                ),
        )
    }

    /** Seeds a real pointerless host: one candidate generation whose database verifies current. */
    private fun seedPointerlessCurrentCandidate(host: Path): String {
        val fileSystem = DesktopStyleTestFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.toString())
        val candidateDirectory = layout.generationDirectory(1)
        Files.createDirectories(Path.of(candidateDirectory))
        val candidateMain = layout.mainFile(candidateDirectory)
        // The candidate file needs only the SQLite magic header for the pre-open usable gate; the
        // integrity/version verdicts come from the injected fake isolated port (a real SQLite open
        // is the desktop wiring test's evidence, where the real adapter runs).
        java.io.File(candidateMain).writeBytes(sqliteTestCandidateBytes)
        isolated.integrityByPath[candidateMain] = true
        isolated.versionByPath[candidateMain] = currentSupportedSchemaVersion()
        // The realistic Android shape: the legacy product database is still present (so the
        // section 5.3 discard-and-re-upgrade guard is available).
        java.io.File(host.toFile(), "legacy.db").writeBytes(sqliteTestCandidateBytes)
        return candidateMain
    }

    /** The 16-byte SQLite magic plus a filler: passes [isUsableSqliteMainFile], nothing more. */
    private val sqliteTestCandidateBytes: ByteArray =
        "SQLite format 3\u0000".encodeToByteArray() + ByteArray(256) { 7 }

    // ---------------------------------------------------------------- the wiring decisions (spec section 6)

    @Test
    fun theRestoreWiringBindsTheRuledWhitelistAndSingleUseCaseInstances() {
        val controller = controller(Files.createTempDirectory("p706d-android-wiring-"), pointerGated = false)
        controller.start()

        assertEquals(P503StartupState.Ready, controller.state)
        val ledger = assertNotNull(controller.ledger)
        assertTrue(ledger.surfaces.backupRestore, "the wired composition renders the restore surface")
        val wiring = assertNotNull(ledger.restoreWiring)
        // The section 5.4 whitelist, extended by the 07.B slice (D-185): {1, 31, 32}. v1/v31 remain
        // and v32 — the immediately preceding version — is now migratable rather than typed-rejected.
        assertEquals(setOf(1L, 31L, 32L), wiring.supportedSourceVersions)
        assertTrue(32L in wiring.supportedSourceVersions, "v32 must stay restorable after the v33 bump")
        assertTrue(31L in wiring.supportedSourceVersions, "v31 remains an admitted old version")
        assertTrue(1L in wiring.supportedSourceVersions, "v1 remains the conditionally admitted old version")
        assertEquals(currentSupportedSchemaVersion(), wiring.currentSchemaVersion)
        // Exactly one instance set: the confirm flow's single-flight guard assumes it. Reading the
        // wiring twice must yield the SAME RestoreHostWiring and the same use-case instances (a
        // second construction would defeat the single-flight guard), and the two use cases must be
        // distinct objects.
        val first = assertNotNull(ledger.restoreWiring)
        val second = assertNotNull(ledger.restoreWiring)
        assertSame(first, second, "the composition must expose one stable restore wiring")
        assertSame(first.preflight, second.preflight, "one preflight instance")
        assertSame(first.confirm, second.confirm, "one confirm instance")
        assertTrue((first.preflight as Any) !== (first.confirm as Any), "preflight and confirm are distinct use cases")
        val launch = assertNotNull(ledger.restorePreflightLaunch("password123"))
        assertEquals(RESTORE_TARGET_LEDGER_ID, launch.request.targetLedgerId)
        assertEquals(1, launch.generation)
    }

    // ---------------------------------------------------------------- the explicit opener wiring (RC-2)

    /**
     * RC-2 (P7-06 06.D review): the composition root's controller passes an explicit-generation
     * opener (Android `App.kt` `openExplicitGeneration = { ... }`; desktop `Main.kt` likewise), and
     * the confirm switch's step 8 reopens the generation it just published through
     * [GenerationSelection.Explicit]. Nothing pinned that wiring: a refactor dropping it would make
     * `openGenerationForSelection` fail loudly at step 8 (mid-switch) and roll the switch back.
     *
     * This drives a WHOLE real preflight → confirm through the controller's OWN owner — the
     * production `openGenerationForSelection(..., openExplicit = wiredExplicitOpener)` wiring — and
     * asserts it COMMITS, opening gen-2 through the explicit route. If the explicit wiring is
     * removed, step 8's `reopen(Explicit(2))` fails (`ReopenResult.Failed`) and the flow ROLLS BACK,
     * so the `Committed` assertion goes RED (verified by ablation).
     *
     * SCOPE (what this does and does not prove): the controller constructor is the exact seam the
     * root uses (the root builds `AndroidStartupController(openExplicitGeneration = { ... })`), and
     * the opener runs the shared [openExplicitGeneration] the production Android opener delegates to
     * (`openAndroidExplicitGenerationWith` → `openExplicitGeneration`). It does NOT execute the
     * `app()` composable (a real `Context` is unavailable on a JVM unit test), so it does not read
     * `App.kt`'s argument literal nor the Context-bound path resolution — those residuals are
     * disclosed, not overclaimed.
     */
    @Test
    fun theCompositionRootOwnerReopensThePublishedGenerationExplicitlyAndCommits() {
        val host = Files.createTempDirectory("p706d-android-explicit-wiring-")
        val fileSystem = DesktopStyleTestFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.toString())
        // The production pointer shape: gen-1 is the active disk generation, so the confirm's
        // gen-(current + 1) staging targets gen-2.
        Files.createDirectories(Path.of(layout.generationDirectory(1)))
        java.io.File(layout.mainFile(layout.generationDirectory(1))).writeBytes(sqliteTestCandidateBytes)
        java.io.File(layout.activePointerFile).writeText("gen-1")
        // The preflight facts for the deterministic token the controller is wired with: the decrypted
        // snapshot verifies current and belongs to the fixed target identity (the fake port supplies
        // the verdicts; no real framework SQLite open, matching the recovery tests' division).
        val snapshotFile = layout.restoreSnapshotFile(RESTORE_WIRING_TEST_TOKEN)
        isolated.versionByPath[snapshotFile] = currentSupportedSchemaVersion()
        isolated.observedLedgerIdentities = listOf(RESTORE_TARGET_LEDGER_ID)
        isolated.validationFacts =
            RestoreValidationFacts(
                integrityOk = true,
                foreignKeyOk = true,
                domainOk = true,
                ledgerIdentityCount = 1,
                formalTableCount = 0,
                postingImbalanceCount = 0,
            )
        isolated.ownerCounts = RestoreOwnerCounts(accountsCount = 0, categoriesCount = 0, transactionsCount = 0)
        val sourcePort =
            BackupSourcePort {
                BackupSourceOpenResult.Opened(InMemoryBackupSourceReader(backupContainerOf(sqliteTestCandidateBytes, "password123")))
            }
        val explicitOpens = mutableListOf<Int>()
        val controller =
            controller(
                host = host,
                pointerGated = false,
                sourcePort = sourcePort,
                openExplicitOpener = { generation ->
                    explicitOpens += generation
                    // The shared function the production Android explicit opener delegates to
                    // (openAndroidExplicitGenerationWith -> openExplicitGeneration), over the same
                    // real filesystem adapter and layout.
                    openExplicitGeneration(fileSystem, layout, generation) {
                        CloseableLedgerGraph(fakeFacade(), close = {})
                    }
                },
            )
        controller.start()
        assertEquals(P503StartupState.Ready, controller.state)
        val ledger = assertNotNull(controller.ledger)

        // A REAL preflight over a REAL container produces the token bound to the active generation.
        val launch = assertNotNull(ledger.restorePreflightLaunch("password123"))
        val wiring = assertNotNull(ledger.restoreWiring)
        val preview =
            assertIs<RestorePreflightResult.PreviewReady>(
                wiring.preflight.preflight(launch.request),
            )

        // Step 8 reopens the just-published generation EXPLICITLY and the switch commits.
        val committed = runBlocking { wiring.confirm.confirm(preview.token) }

        assertIs<BackupRestoreSwitchResult.Committed>(
            committed,
            "the wired controller must honour Explicit and commit; a dropped explicit opener rolls back instead: $committed",
        )
        assertEquals(listOf(2), explicitOpens, "step 8 must reopen the published generation through the explicit route")
        assertEquals("gen-2", fileSystem.readBytes(layout.activePointerFile).decodeToString())
        assertTrue(fileSystem.exists(layout.mainFile(layout.generationDirectory(2))))
        assertFalse(fileSystem.exists(layout.switchJournalFile), "a committed switch removes the journal")
    }

    /**
     * RC-2 (P7-06 06.D review): the ROOT call site. The Android root's explicit opener is the
     * `openExplicitGeneration = { generation -> openAndroidExplicitGeneration(...) }` argument that
     * `app()` passes to `AndroidStartupController` (App.kt). `app()` is a Compose composable that
     * reads `LocalContext`, so it is genuinely unreachable from a JVM unit test (no real `Context`).
     *
     * This is a SOURCE-LEVEL guard: it asserts the production root file still contains that argument
     * call site (and the production explicit-opener function it delegates to), so a refactor that
     * drops the argument — the exact regression RC-2 is about — turns this test RED. It proves the
     * call site EXISTS; it does NOT execute it, and it does not prove the argument's runtime
     * behaviour (the behavioural test above pins the controller-side wiring on the real seam).
     */
    @Test
    fun theAndroidRootStillWiresTheExplicitGenerationOpener() {
        val root = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/App.kt").readText()
        assertTrue(
            root.contains("openExplicitGeneration = { generation ->"),
            "the Android root must pass an explicit-generation opener to AndroidStartupController (RC-2)",
        )
        assertTrue(
            root.contains("openAndroidExplicitGeneration(context, importFilePickPort, importPickChannel, generation)"),
            "the Android root's explicit opener must delegate to the production openAndroidExplicitGeneration (RC-2)",
        )
    }

    /**
     * Defect 1 (P7-06 06.D device gate, D-183): the isolated-database port must open every
     * INSPECTION leg read-only, and only the migration leg read-write. The port is an Android-only
     * class whose open takes a concrete `SQLiteDatabase`, so it cannot be JVM-exercised; the
     * behaviour is device-pinned by
     * `AndroidRestoreRecoveryInstrumentedTest.aStructurallyCorruptCandidateIsNeverAdopted` (a corrupt
     * candidate survives the probe byte-identically). This source-level guard pins the wiring choice
     * itself so a regression that routes an inspection back through the read-write opener — the
     * shape that deleted the candidate on device — turns this test RED.
     */
    @Test
    fun theIsolatedPortOpensEveryInspectionReadOnlyAndOnlyTheMigrationReadWrite() {
        val port = repositoryFile("android-app/src/main/kotlin/com/unifiedledger/android/AndroidRestorePreflightPorts.kt").readText()
        // The five INSPECTION legs — readAuthoritativeUserVersion, readObservedLedgerIdentities,
        // validate, integrityCheckOk, readOwnerCounts — must ALL ride the read-only route. Counted
        // by call site so routing even one back through the read-write opener (the shape that
        // deleted the candidate on device) drops the count and turns this RED.
        val readOnlyCalls = Regex(Regex.escape("withReadOnlyDriver(snapshotPath)")).findAll(port).count()
        assertEquals(
            5,
            readOnlyCalls,
            "all five inspection legs must open read-only (defect 1, D-183); a revert to the read-write opener drops this count",
        )
        assertEquals(
            1,
            Regex(Regex.escape("withReadWriteDriver(snapshotPath)")).findAll(port).count(),
            "only the migration leg may open read-write (defect 1, D-183)",
        )
    }

    /**
     * Resolves a path relative to the repository root by walking up from the test working directory
     * to the settings file (the `LedgerDatabaseMigrationTest` precedent, ledger-data jvmTest).
     */
    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    /**
     * Writes one real authenticated container around [plaintext] with the product JCE primitives, so
     * the preflight exercises its real format/size/decryption steps. The plaintext is the SQLite-magic
     * candidate: enough for the pre-open usability gate the confirm's step-6 stage and step-8 explicit
     * reopen apply.
     */
    private fun backupContainerOf(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupContainer(
            plaintext =
                BackupPlaintextSource {
                    var position = 0
                    object : BackupPlaintextReader {
                        override fun read(buffer: ByteArray): Int {
                            if (position >= plaintext.size) return -1
                            val count = minOf(buffer.size, plaintext.size - position)
                            plaintext.copyInto(buffer, 0, position, position + count)
                            position += count
                            return count
                        }

                        override fun close() {}
                    }
                },
            sink = BackupContainerSink { bytes, offset, length -> out.write(bytes, offset, length) },
            password = password,
            schemaVersion = currentSupportedSchemaVersion(),
            plaintextLength = plaintext.size.toLong(),
            crypto = JvmBackupCryptoPrimitives(),
        )
        return out.toByteArray()
    }

    /** An in-memory bounded reader over the written container bytes (the preflight source port). */
    private class InMemoryBackupSourceReader(
        private val bytes: ByteArray,
    ) : BackupSourceReader {
        private var position = 0

        override fun read(buffer: ByteArray): Int {
            if (position >= bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - position)
            bytes.copyInto(buffer, 0, position, position + count)
            position += count
            return count
        }

        override val reportedSize: Long get() = bytes.size.toLong()

        override fun close() {}
    }

    // ---------------------------------------------------------------- the recovery flow (spec section 5.3)

    @Test
    fun aPointerlessStartOffersTheRecoveryFaceAndTheConfirmedAdoptionReachesReady() {
        val host = Files.createTempDirectory("p706d-android-recovery-")
        val fileSystem = DesktopStyleTestFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.toString())
        val candidateMain = seedPointerlessCurrentCandidate(host)
        val controller = controller(host, pointerGated = true)

        controller.start()

        val recovery = assertIs<P503StartupState.PointerRecovery>(controller.state, "the pointerless start surfaces the recovery face")
        assertEquals(1, recovery.state.adoptableGeneration)
        assertTrue(recovery.state.legacyUpgradeAvailable, "Android's legacy database exists, so the discard branch is available")
        assertNull(controller.ledger, "no business surface before the runtime is Ready")

        // The user CONFIRMS the adoption: the frozen primitive publishes the pointer and the
        // normal startup re-runs and opens the adopted generation.
        controller.adoptPointerRecovery()

        assertEquals(P503StartupState.Ready, controller.state)
        assertEquals("gen-1", fileSystem.readBytes(layout.activePointerFile).decodeToString())
        assertTrue(fileSystem.exists(candidateMain))
        assertNotNull(controller.ledger)
    }

    @Test
    fun aUserDeclineMutatesNothingWhileTheStateStaysRecoverable() {
        val host = Files.createTempDirectory("p706d-android-decline-")
        val fileSystem = DesktopStyleTestFileSystem()
        val layout = ledgerStorageLayout(fileSystem, host.toString())
        val candidateMain = seedPointerlessCurrentCandidate(host)
        val controller = controller(host, pointerGated = true)
        controller.start()
        assertIs<P503StartupState.PointerRecovery>(controller.state)

        // A user who confirms NOTHING: the state is unchanged, the disk is unchanged (the probe
        // and the face are read-only; the confirmed actions are the only mutation paths), and the
        // fail-closed runtime stays unusable (no facade exposed, D-176).
        val recovery = assertIs<P503StartupState.PointerRecovery>(controller.state)
        assertEquals(1, recovery.state.adoptableGeneration)
        assertTrue(fileSystem.exists(candidateMain))
        assertFalse(fileSystem.exists(layout.activePointerFile))
        assertNull(controller.ledger)
    }

    /**
     * A minimal but valid [P503LedgerFacade] (the AndroidStartupControllerTest precedent): the
     * controller only stores/returns the facade, none of its methods are exercised here.
     */
    private fun fakeFacade(): P503LedgerFacade {
        val ledgerId = LedgerId(RESTORE_TARGET_LEDGER_ID)
        val currency = CurrencyUnit("CNY", 2)
        val catalog =
            (LedgerCatalog.create(accounts = emptyList(), categories = emptyList()) as DomainResult.Success)
                .value
        val readPort =
            object : LedgerCurrentStateReadPort {
                override fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow> = emptyList()

                override fun findManualExpenseByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualExpenseCommitRecord? = null

                override fun findManualExpenseByReceipt(
                    ledgerId: LedgerId,
                    receipt: ConfirmedExpenseReceipt,
                ): ManualExpenseCommitRecord? = null

                override fun findManualIncomeByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualIncomeCommitRecord? = null

                override fun findManualIncomeByReceipt(
                    ledgerId: LedgerId,
                    receipt: ConfirmedIncomeReceipt,
                ): ManualIncomeCommitRecord? = null

                override fun findManualTransferByRequest(
                    ledgerId: LedgerId,
                    requestId: RequestId,
                ): ManualTransferCommitRecord? = null

                override fun findManualTransferByReceipt(
                    ledgerId: LedgerId,
                    receipt: ConfirmedTransferReceipt,
                ): ManualTransferCommitRecord? = null
            }
        val resolver = ResolveManualExpenseCommitStatus(readPort)
        val commitPort =
            ConfirmedManualExpenseCommitPort { _, _, _ ->
                ConfirmedManualExpenseResult.Rejected(DomainViolation.InvalidOrdinaryExpense)
            }
        val tracker = com.unifiedledger.application.CommitOnceInvocationTracker(commitPort)
        val idSource =
            ConfirmedManualExpenseIdSource {
                error("commit id source must not be used in wiring tests")
            }
        val transactionFactory =
            com.unifiedledger.application.ConfirmedExpenseTransactionFactory { _, _ ->
                error("transaction factory must not be used in wiring tests")
            }
        val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, transactionFactory)
        val executeSave = ExecuteManualExpenseSave(executeConfirmed)
        val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

        return P503LedgerFacade(
            ledgerId = ledgerId,
            currency = currency,
            catalog = catalog,
            parseAmount = ParseManualExpenseAmount(),
            baseOptionsProvider = QueryManualExpenseOptions(ledgerId, catalog),
            baseQueryCurrentState = QueryLedgerCurrentState(readPort, ledgerId, catalog),
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = ManualExpenseRequestIdSource { RequestId("request-restore-wiring-test") },
            ledgerClock =
                LedgerClock {
                    kotlin.time.Clock.System
                        .now()
                },
            baseSummarizeActivity = SummarizeLedgerActivity(catalog),
        )
    }
}
