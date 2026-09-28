package com.unifiedledger.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogAdmissionExpenseTransactionFactory
import com.unifiedledger.application.CatalogAdmissionIncomeTransactionFactory
import com.unifiedledger.application.CatalogAdmissionTransferTransactionFactory
import com.unifiedledger.application.CatalogBootstrapFailedException
import com.unifiedledger.application.CatalogConsumerSession
import com.unifiedledger.application.CommitOnceInvocationTracker
import com.unifiedledger.application.CommitOnceInvocationTrackerIncome
import com.unifiedledger.application.CommitOnceInvocationTrackerLending
import com.unifiedledger.application.CommitOnceInvocationTrackerTransfer
import com.unifiedledger.application.ConfirmImportCandidate
import com.unifiedledger.application.ConfirmedExpenseTransactionFactory
import com.unifiedledger.application.ConfirmedIncomeTransactionFactory
import com.unifiedledger.application.ConfirmedLendingTransactionFactory
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualIncomeCommit
import com.unifiedledger.application.ConfirmedTransferTransactionFactory
import com.unifiedledger.application.CounterpartyCommands
import com.unifiedledger.application.CreditFlowFormalFactory
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteConfirmedManualExpense
import com.unifiedledger.application.ExecuteConfirmedManualIncome
import com.unifiedledger.application.ExecuteConfirmedManualLending
import com.unifiedledger.application.ExecuteConfirmedManualTransfer
import com.unifiedledger.application.ExecuteCorrectTransactionVersion
import com.unifiedledger.application.ExecuteCreateCounterparty
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.ExecuteLendingSubmission
import com.unifiedledger.application.ExecuteManualEntrySubmission
import com.unifiedledger.application.ExecuteManualExpenseSave
import com.unifiedledger.application.ExecuteManualExpenseSubmission
import com.unifiedledger.application.ExecuteManualIncomeSave
import com.unifiedledger.application.ExecuteManualIncomeSubmission
import com.unifiedledger.application.ExecuteManualLendingSave
import com.unifiedledger.application.ExecuteManualLendingSubmission
import com.unifiedledger.application.ExecuteManualTransferSave
import com.unifiedledger.application.ExecuteManualTransferSubmission
import com.unifiedledger.application.ExecuteRenameCounterparty
import com.unifiedledger.application.ExecuteRestoreTransaction
import com.unifiedledger.application.ExecuteSetCounterpartyActive
import com.unifiedledger.application.ExecuteVoidTransaction
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportDuplicateReviewId
import com.unifiedledger.application.ImportIntakeSessionIdentity
import com.unifiedledger.application.ImportPlatformKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.LedgerClock
import com.unifiedledger.application.ManualLendingTransactionFactory
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.MixedPaymentFlowFormalFactory
import com.unifiedledger.application.OrdinaryFlowFormalFactory
import com.unifiedledger.application.ParseManualExpenseAmount
import com.unifiedledger.application.ParseManualExpenseOccurredAt
import com.unifiedledger.application.QueryCatalogSnapshot
import com.unifiedledger.application.QueryImportCandidateDetail
import com.unifiedledger.application.QueryImportDuplicateReviews
import com.unifiedledger.application.QueryImportDuplicateReviewsForSession
import com.unifiedledger.application.QueryImportReviewRows
import com.unifiedledger.application.ResolveManualExpenseCommitStatus
import com.unifiedledger.application.ResolveManualIncomeCommitStatus
import com.unifiedledger.application.ResolveManualLendingCommitStatus
import com.unifiedledger.application.ResolveManualTransferCommitStatus
import com.unifiedledger.application.ResolveTransactionCorrectionCommitStatus
import com.unifiedledger.application.ResolveTransactionVoidCommitStatus
import com.unifiedledger.application.ReviewImportDuplicateCandidate
import com.unifiedledger.application.TransferFlowFormalFactory
import com.unifiedledger.application.UuidV7CatalogEntityIdSource
import com.unifiedledger.application.UuidV7CatalogManagementRequestIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualExpenseIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualIncomeIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualLendingIdSource
import com.unifiedledger.application.UuidV7ConfirmedManualTransferIdSource
import com.unifiedledger.application.UuidV7CounterpartyIdSource
import com.unifiedledger.application.UuidV7Generator
import com.unifiedledger.application.UuidV7ImportIntakeIdSource
import com.unifiedledger.application.UuidV7ManualExpenseRequestIdSource
import com.unifiedledger.application.UuidV7ManualIncomeRequestIdSource
import com.unifiedledger.application.UuidV7ManualLendingRequestIdSource
import com.unifiedledger.application.UuidV7ManualTransferRequestIdSource
import com.unifiedledger.application.UuidV7TransactionCorrectionIdSource
import com.unifiedledger.application.UuidV7TransactionVoidFactIdSource
import com.unifiedledger.application.backup.BackupCryptoPrimitives
import com.unifiedledger.application.backup.JvmBackupCryptoPrimitives
import com.unifiedledger.application.import.JvmImportFileIntake
import com.unifiedledger.data.CatalogBootstrapResult
import com.unifiedledger.data.SqlDelightCatalogStore
import com.unifiedledger.data.SqlDelightConfirmedManualExpenseCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualIncomeCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualLendingCommitPort
import com.unifiedledger.data.SqlDelightConfirmedManualTransferCommitPort
import com.unifiedledger.data.SqlDelightCounterpartyStore
import com.unifiedledger.data.SqlDelightEntryPreferenceStore
import com.unifiedledger.data.SqlDelightImportReviewReadAdapter
import com.unifiedledger.data.SqlDelightImportSpineStore
import com.unifiedledger.data.SqlDelightLedgerCurrentStateReadAdapter
import com.unifiedledger.data.SqlDelightTransactionCorrectionCommitPort
import com.unifiedledger.data.SqlDelightTransactionVoidCommitPort
import com.unifiedledger.data.currentSupportedSchemaVersion
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.data.defaultCatalogSeed
import com.unifiedledger.data.runFullAnalyzeOn
import com.unifiedledger.data.runQueryStatisticsOptimizeOn
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetReceivedOrdinaryIncomeCommand
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.domain.createAssetReceivedOrdinaryIncome
import com.unifiedledger.ui.BackupExportUseCase
import com.unifiedledger.ui.BackupSnapshotPort
import com.unifiedledger.ui.BackupSourcePort
import com.unifiedledger.ui.BackupTargetPort
import com.unifiedledger.ui.ConfirmBackupRestoreUseCase
import com.unifiedledger.ui.GenerationSelection
import com.unifiedledger.ui.ImportConfirmUseCaseSet
import com.unifiedledger.ui.ImportDuplicateReviewIds
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerFileSystem
import com.unifiedledger.ui.LedgerLeaseScope
import com.unifiedledger.ui.LedgerOpenTarget
import com.unifiedledger.ui.LedgerRuntimeOwner
import com.unifiedledger.ui.LedgerStartupResult
import com.unifiedledger.ui.LedgerStorageFailure
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.LedgerStorageRejectedException
import com.unifiedledger.ui.P503App
import com.unifiedledger.ui.P503LedgerFacade
import com.unifiedledger.ui.P503StartupScreen
import com.unifiedledger.ui.P503StartupState
import com.unifiedledger.ui.PointerMissingRecoveryProbe
import com.unifiedledger.ui.PointerMissingRecoveryUseCase
import com.unifiedledger.ui.PointerRecoveryAdoptionResult
import com.unifiedledger.ui.PointerRecoveryDiscardResult
import com.unifiedledger.ui.RestoreHostWiring
import com.unifiedledger.ui.RestoreIsolatedDatabasePort
import com.unifiedledger.ui.RestorePreflightUseCase
import com.unifiedledger.ui.UuidV7ImportCommitIdSource
import com.unifiedledger.ui.importCreditRefundOriginalExpenseProvider
import com.unifiedledger.ui.isUsableSqliteMainFile
import com.unifiedledger.ui.ledgerStorageLayout
import com.unifiedledger.ui.openExplicitGeneration
import com.unifiedledger.ui.openGenerationForSelection
import com.unifiedledger.ui.openStableStorageLedger
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.FileInputStream
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.time.Clock

private const val LOCAL_TEST_LEDGER_FILE_NAME = "ledger-local-test.db"

/**
 * P7-06 06.B (D-177; spec section 3): the composition-root backup-export wiring handed to
 * [DesktopStartupController]. Kept as one value so the controller's constructor stays backward
 * compatible (the existing startup tests pass only the graph builder).
 */
internal class DesktopBackupWiring(
    val fileSystem: LedgerFileSystem,
    val layout: LedgerStorageLayout,
    val targetPort: BackupTargetPort = DesktopBackupTargetPort(::showSwingSaveFileChooser),
    val crypto: BackupCryptoPrimitives = JvmBackupCryptoPrimitives(),
    val newToken: () -> String = { randomUuidText() },
)

/**
 * P7-06 06.D (D-182; spec section 6, P2-7): the composition-root restore wiring handed to
 * [DesktopStartupController]. `legacyMainFile` is deliberately NULL (06.1 spec section 3.3: the
 * desktop has no migratable legacy product location), so the section 5.3 discard-and-re-upgrade
 * branch is typed-unavailable here and the adoption branch is the only recovery exit.
 */
internal class DesktopRestoreWiring(
    val fileSystem: LedgerFileSystem,
    val layout: LedgerStorageLayout,
    val sourcePort: BackupSourcePort = DesktopBackupSourcePort(),
    val isolatedDatabase: RestoreIsolatedDatabasePort = DesktopRestoreIsolatedDatabasePort(),
    val crypto: BackupCryptoPrimitives = JvmBackupCryptoPrimitives(),
    val newToken: () -> String = { randomUuidText() },
    val nowMillis: () -> Long = { System.currentTimeMillis() },
    val targetLedgerId: String = RESTORE_TARGET_LEDGER_ID,
    val legacyMainFile: String? = null,
)

/** The controller-built restore use-case set (exactly one instance set per runtime). */
internal class DesktopRestoreUseCases(
    val preflight: RestorePreflightUseCase,
    val confirm: ConfirmBackupRestoreUseCase,
    val recovery: PointerMissingRecoveryUseCase,
)

/**
 * P7-06 06.D (D-182; spec section 5.4): the wiring whitelist injected into the preflight request —
 * v1 is a CONDITIONALLY admitted old version (device-evidenced strict migration 1 -> current plus
 * validation — `AndroidFrameworkSqlDriverInstrumentedTest.aSupportedV1FixtureStrictMigratesOnDeviceThroughTheAdapter`;
 * its A04 round-trip-equivalence leg is outstanding and a falsification removes it), 31 is an
 * admitted OLD version — the direct pass-through entry of the frozen section 5.4 set (the ruling
 * recorded 31 as the then-current schema, i.e. the no-migration pass-through at freeze time; it now
 * migrates to current through the P7-07 07.T `31.sqm` edge) — and
 * 32 is the immediately preceding version whose strict migration is the P7-07 07.B `32.sqm` budget
 * edge. v32 carries the SAME conditional-admission status as v1: its strict migration 32 -> 33 plus
 * validation is proven (the `P707BudgetConfigMigrationV32ToV33Test` strict-migration leg) but its
 * A04 round-trip-equivalence leg is likewise not yet proven, so a falsification removes it exactly
 * as it would remove v1. The current schema version (33 since the 07.B budget-configuration edge)
 * passes through WITHOUT consulting this set (`needsMigration == false`), so this set names only old
 * versions that must be migrated.
 *
 * D-182 section 5.4 froze the literal `{1, 31}` when 31 was the current schema; D-185 supersedes that
 * literal for the WIRING set (the approved 06.D spec section 5.4 ruling text itself is unchanged and
 * remains the record for v1's conditional admission). The 07.T slice left it unchanged to avoid
 * stopping v31 restores. The 07.B slice extends it to `{1, 31, 32}` (D-185) so
 * the immediately preceding v32 backup keeps restoring through its `32.sqm` migration, mirroring the
 * v31 -> 32 pass-through preservation the 07.T slice relied on.
 */
internal val RESTORE_SUPPORTED_SOURCE_VERSIONS: Set<Long> = setOf(1L, 31L, 32L)

/**
 * The product's fixed single-ledger identity. F-8: ONE constant, used both by [buildLedgerGraph]
 * to bootstrap the catalog and by the restore wiring as the confirm target identity, so the
 * confirm's target-identity check (spec section 3.1 item 4) cannot drift from the graph's actual
 * ledger id.
 */
internal const val PRODUCT_LEDGER_ID: String = "ledger-local-test"

/** The restore target ledger identity — the same fixed product identity the graph bootstraps. */
internal const val RESTORE_TARGET_LEDGER_ID: String = PRODUCT_LEDGER_ID

/** P7-06 06.B: one random token text (kept out of the data-class default so ktlint's chain rule is satisfied). */
private fun randomUuidText(): String {
    val uuid = java.util.UUID.randomUUID()
    return uuid.toString()
}

/**
 * Desktop composition root (P5-03 spec sections 8/10.1). Assembles the full object graph
 * (driver, current-schema database, commit tracker, fixed catalog, product ID/clock sources, read
 * adapter and the shared facade) and runs the shared [P503App]. Startup is fail-closed:
 * driver/schema/create/open failures expose only Retry and Exit.
 *
 * P7-06 06.1 (D-176; spec section 3.3): the product entry no longer creates a fresh temp directory
 * per start. It resolves the per-OS user data directory at runtime and opens the fixed product
 * location through the shared stable-storage sequence, so the same ledger survives restarts. The
 * temp-directory entry is retained only as the injectable demo/test path below.
 */
fun main() {
    val fileSystem = DesktopLedgerFileSystem()
    val layout = ledgerStorageLayout(fileSystem, resolveDesktopHostDirectory())
    application {
        DesktopRoot(
            openDatabase = {
                openStableStorageDesktopLedger(fileSystem, layout)
            },
            onExit = ::exitApplication,
            backupWiring = DesktopBackupWiring(fileSystem, layout),
            restoreWiring = DesktopRestoreWiring(fileSystem, layout),
            // P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the confirm switch's step-8 reopen opens
            // the generation it just published BY NAME, bypassing the startup journal gate that
            // would otherwise consume its own live `switched` journal.
            openExplicitGeneration = { generation ->
                openExplicitDesktopLedger(fileSystem, layout, generation)
            },
        )
    }
}

/**
 * The demo/test temp-directory database URL (retained for tests and the demo path; no longer the
 * product entry, spec section 3.3). The desktop side has no migratable legacy product location —
 * its old product path was itself a per-start temp directory — so the upgrade here is simply
 * "stop treating the temp directory as the product path". `internal` so the composition-root tests
 * keep driving the isolated, injectable demo path.
 */
internal fun createDemoDatabaseUrl(): String {
    val directory = Files.createTempDirectory("unifiedledger-demo-")
    return "jdbc:sqlite:${directory.resolve(LOCAL_TEST_LEDGER_FILE_NAME).absolutePathString()}"
}

@Composable
internal fun DesktopRoot(
    openDatabase: () -> CloseableLedgerGraph,
    onExit: () -> Unit,
    backupWiring: DesktopBackupWiring? = null,
    restoreWiring: DesktopRestoreWiring? = null,
    // P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the confirm switch's step-8 explicit reopen.
    openExplicitGeneration: ((Int) -> CloseableLedgerGraph)? = null,
) {
    val controller = remember { DesktopStartupController(openDatabase, backupWiring, restoreWiring, openExplicitGeneration).also { it.start() } }
    Window(onCloseRequest = onExit, title = "UnifiedLedger Desktop") {
        val ledger = controller.ledger
        when {
            controller.state == P503StartupState.Ready && ledger != null ->
                P503App(
                    ledger,
                    onExit = onExit,
                    backHandler = { enabled, onBack ->
                        DesktopEscBackHandler(enabled, onBack)
                    },
                )
            else ->
                P503StartupScreen(
                    state = controller.state,
                    onRetry = controller::start,
                    onExit = onExit,
                    // P7-06 06.D (spec section 5.3): the user-confirmed recovery actions; the
                    // face decides which affordances exist (a verified candidate, the legacy
                    // guard - on desktop there is no legacy location, so the discard branch is
                    // typed-unavailable and renders no affordance).
                    onAdoptPointer = controller::adoptPointerRecovery,
                    onDiscardAndReUpgrade = controller::discardPointerRecoveryAndReUpgrade,
                )
        }
    }
}

/**
 * P5-04.3 desktop back equivalence: consumes Escape while [enabled] and forwards to
 * [onBack] (the shared P503App back channel; Submitting already swallows there). Non-Escape
 * events pass through untouched and are never read or recorded. The dispatcher callback
 * runs on the AWT event-dispatch thread, which is also the Compose Desktop UI thread, so
 * [onBack] may touch Compose state directly.
 *
 * D-137: [enabled] is now the sole gate of the back channel. P503App disables it while a
 * picker dialog is open, so Escape reaches only the dialog layer and the edit page stays
 * open. The former AWT window-yield heuristic (yield Escape while any AWT Dialog of this
 * process is showing) is removed: gate evidence E-1 (G07/G08 + gate procedure note) shows
 * that compose picker dialogs do not map to a visible AWT Dialog HWND on the current
 * Compose Multiplatform stack, so the probe could not fire and only masked the defect.
 * Escape dismissal while focus is inside the dialog content is handled by
 * P503EditScreen's `dismissOnEscape`; the composition-root [onBack] channel only receives
 * Escape when the editing flow's back is Back-legal. Window closing is unchanged.
 */
@Composable
internal fun DesktopEscBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val dispatcher =
            KeyEventDispatcher { event ->
                if (enabled && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
                    latestOnBack()
                    true
                } else {
                    false
                }
            }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher) }
    }
}

/**
 * Testable composition-root startup state (spec section 8) with P5-04.4 fail-closed retry
 * resource-safety (spec sections 4/5). Exposes the shared [P503StartupState] transitions and
 * never exposes a business graph or database handle after a failed start. The ledger
 * connection is carried as a [CloseableLedgerGraph] so a retry can close the previous driver
 * and a mid-failure open is closed too.
 */
internal class DesktopStartupController(
    private val openDatabase: () -> CloseableLedgerGraph,
    // P7-06 06.B (D-177): the optional backup-export wiring; null keeps the surface absent so the
    // existing startup tests construct the controller with the graph builder only.
    backupWiring: DesktopBackupWiring? = null,
    // P7-06 06.D (D-182; spec section 6, P2-7): the optional restore wiring; present, it binds
    // exactly one set of use-case instances (the confirm single-flight guard assumes it).
    restoreWiring: DesktopRestoreWiring? = null,
    // P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the explicit-generation opener the confirm
    // switch's step-8 reopen needs. Null (the existing startup tests) keeps the owner on the
    // pointer path only; a production root wires it so the two selections are honoured.
    private val openExplicitGeneration: ((Int) -> CloseableLedgerGraph)? = null,
) {
    var state by mutableStateOf<P503StartupState>(P503StartupState.Starting)
        private set

    /**
     * P7-06 06.1 (D-176; spec section 4): the single runtime owner of the active graph, exactly
     * like the Android controller. [openDatabase] is the injected graph builder the tests use.
     *
     * P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): both [GenerationSelection]s are honoured.
     * ActivePointer keeps the full startup sequence (pointer + journal gate, so a crashed
     * session's journal still rolls back at startup); Explicit opens the named generation directly
     * with the SAME non-fresh usability guard, which is what the confirm switch's step-8 reopen
     * needs while its own `switched` journal is still on disk. An Explicit request with no wired
     * opener fails LOUDLY rather than silently falling back to the pointer path (the fallback is
     * exactly the defect that rolled the switch back).
     */
    private val owner =
        LedgerRuntimeOwner(
            openGeneration = { selection ->
                openGenerationForSelection(
                    selection = selection,
                    openActivePointer = { openDatabase().also { graph -> lastOpenedGraph = graph } },
                    openExplicit = wiredExplicitOpener,
                )
            },
            closeGraph = { graph -> graph.close() },
            facadeOf = { graph -> graph.facade },
        )

    /**
     * The owner's Explicit route, tracking the opened graph like the pointer route. Null stays
     * null so [openGenerationForSelection] owns the single fail-loud check (never a silent
     * fallback to the pointer path, which is the defect that rolled the switch back).
     */
    private val wiredExplicitOpener: ((Int) -> CloseableLedgerGraph)? =
        openExplicitGeneration?.let { opener ->
            { generation: Int -> opener(generation).also { graph -> lastOpenedGraph = graph } }
        }

    /**
     * P7-06 06.B (D-177): the most recently opened graph, captured so the export use case resolves
     * the CURRENT graph's controlled snapshot surface (a pure field read).
     */
    private var lastOpenedGraph: CloseableLedgerGraph? = null

    /**
     * P7-06 06.B (D-177; spec section 3): the shared backup-export use case, built over this
     * controller's owner and the injected wiring.
     */
    private val backupExportUseCase: BackupExportUseCase? =
        backupWiring?.let { wiring ->
            BackupExportUseCase(
                owner = owner,
                fileSystem = wiring.fileSystem,
                layout = wiring.layout,
                snapshotProvider = { lastOpenedGraph?.snapshotPort },
                target = wiring.targetPort,
                crypto = wiring.crypto,
                newToken = wiring.newToken,
            )
        }

    /**
     * P7-06 06.D (D-182; spec sections 5.3/6): the restore use cases and the POINTER_MISSING
     * recovery use case, built over this controller's owner and the injected wiring. The recovery
     * use case never touches the owner (a pointerless start never reached Ready); it is invoked
     * only by the explicit user confirmations on the recovery face.
     */
    private val restoreUseCases: DesktopRestoreUseCases? =
        restoreWiring?.let { wiring ->
            val preflight =
                RestorePreflightUseCase(
                    owner = owner,
                    fileSystem = wiring.fileSystem,
                    layout = wiring.layout,
                    source = wiring.sourcePort,
                    isolatedDatabase = wiring.isolatedDatabase,
                    crypto = wiring.crypto,
                    newToken = wiring.newToken,
                    nowMillis = wiring.nowMillis,
                )
            val confirm =
                ConfirmBackupRestoreUseCase(
                    owner = owner,
                    fileSystem = wiring.fileSystem,
                    layout = wiring.layout,
                    crypto = wiring.crypto,
                    targetLedgerId = wiring.targetLedgerId,
                )
            val recovery =
                PointerMissingRecoveryUseCase(
                    fileSystem = wiring.fileSystem,
                    layout = wiring.layout,
                    isolatedDatabase = wiring.isolatedDatabase,
                    currentSchemaVersion = currentSupportedSchemaVersion(),
                    legacyMainFile = wiring.legacyMainFile,
                )
            DesktopRestoreUseCases(preflight, confirm, recovery)
        }

    /**
     * P7-06 06.1 fix (review REJECT): the product surface handed to [P503App] is the LEASE-SCOPED
     * accessor, never the raw facade (which is module-private to app-ui). Every `facade.*`
     * business call in P503App therefore passes through [LedgerLeaseScope], so the operation
     * lease is non-vacuous by construction.
     */
    val ledger: LedgerLeaseScope?
        get() = if (state == P503StartupState.Ready) leaseScope else null

    private val leaseScope =
        LedgerLeaseScope(owner).also { scope ->
            scope.backupExport = backupExportUseCase
            // P7-06 06.D (D-182; spec section 6): the section 5.4 ruling injects the wiring
            // whitelist {1, 31, 32} and the current schema version from ledger-data.
            scope.restoreWiring =
                restoreUseCases?.let { cases ->
                    RestoreHostWiring(
                        preflight = cases.preflight,
                        confirm = cases.confirm,
                        supportedSourceVersions = RESTORE_SUPPORTED_SOURCE_VERSIONS,
                        currentSchemaVersion = currentSupportedSchemaVersion(),
                    )
                }
        }

    /**
     * True once [start] has been invoked. The state already starts as [P503StartupState.Starting]
     * so the guard needs this flag to distinguish the initial call (which must proceed) from a
     * reentrant call while an open is in flight (which is dropped).
     */
    private var startedOnce = false

    fun start() {
        // P5-04.4 reentrancy guard: a double "Retry" / Esc tap while already starting is
        // ignored so it neither rebuilds the graph nor double-closes a connection.
        if (startedOnce && state == P503StartupState.Starting) return
        startedOnce = true
        state = P503StartupState.Starting
        // P7-06 06.1 fix: the owner applies the spec section 4.2 in-flight precondition, so a
        // retry racing in-flight business work is refused (typed Blocked) rather than closing the
        // graph under a running call.
        when (val result = owner.startup()) {
            is LedgerStartupResult.Started -> {
                state = P503StartupState.Ready
            }
            is LedgerStartupResult.Failed -> {
                System.err.println("UnifiedLedger startup failed: " + result.cause)
                result.cause.printStackTrace()
                // P7-06 06.D (D-182; spec section 5.3): a pointerless start with the recovery
                // wiring probes the shape and offers the recovery face; every other failure keeps
                // the plain StartupError. The probe is read-only (nothing is adopted or deleted
                // until the user confirms an action on the face); the desktop startup already
                // runs on the caller thread, so the probe follows that convention.
                state = probePointerRecoveryFace(result) ?: P503StartupState.StartupError
            }
            is LedgerStartupResult.Blocked -> {
                System.err.println("UnifiedLedger startup blocked by ${result.inFlightLeases} in-flight lease(s)")
                state = P503StartupState.StartupError
            }
            LedgerStartupResult.TransitionInProgress -> {
                // P7-06 06.1 fix (review P3-6): another transition held the owner (zero leases).
                System.err.println("UnifiedLedger startup rejected: a runtime transition is in progress")
                state = P503StartupState.StartupError
            }
        }
    }

    /**
     * P7-06 06.D (D-182; spec section 5.3): the read-only recovery probe after a pointerless start.
     * The recovery face exists ONLY for the `POINTER_MISSING` shape (a journal-failed or other
     * start stays the plain StartupError); null when the shape does not match or no wiring exists.
     */
    private fun probePointerRecoveryFace(result: LedgerStartupResult.Failed): P503StartupState? {
        val cause = result.cause as? LedgerStorageRejectedException ?: return null
        if (cause.failure != LedgerStorageFailure.POINTER_MISSING) return null
        val recovery = restoreUseCases?.recovery ?: return null
        return when (val probe = recovery.probe()) {
            is PointerMissingRecoveryProbe.Recoverable -> P503StartupState.PointerRecovery(probe.state)
            PointerMissingRecoveryProbe.NotPointerMissing -> null
        }
    }

    /**
     * P7-06 06.D (spec section 5.3, first branch): the user-confirmed ADOPTION of a verified
     * candidate. Calling this IS the confirmation. On success the normal startup re-runs and opens
     * the adopted generation; any refusal republishes the recovery face from a FRESH probe so the
     * user always sees the current disk truth.
     */
    fun adoptPointerRecovery() {
        val recovery = restoreUseCases?.recovery ?: return
        if (state !is P503StartupState.PointerRecovery) return
        when (val outcome = recovery.adopt()) {
            is PointerRecoveryAdoptionResult.Adopted -> start()
            else -> {
                System.err.println("UnifiedLedger pointer recovery adoption refused: $outcome")
                republishRecoveryFace()
            }
        }
    }

    /**
     * P7-06 06.D (spec section 5.3, second branch): the user-confirmed DISCARD-AND-RE-UPGRADE.
     * The desktop has no legacy product location, so this normally returns the typed
     * `LegacyOriginalMissing` refusal (the face renders no discard affordance); a success re-runs
     * the normal startup, which resolves the frozen `UpgradeLegacy` path.
     */
    fun discardPointerRecoveryAndReUpgrade() {
        val recovery = restoreUseCases?.recovery ?: return
        if (state !is P503StartupState.PointerRecovery) return
        when (val outcome = recovery.discardUnvalidatableAndReUpgrade()) {
            PointerRecoveryDiscardResult.DiscardedAwaitingUpgrade -> start()
            else -> {
                System.err.println("UnifiedLedger pointer recovery discard refused: $outcome")
                republishRecoveryFace()
            }
        }
    }

    /** Re-probes after a refused recovery action and republishes the face (or the plain error). */
    private fun republishRecoveryFace() {
        val recovery = restoreUseCases?.recovery
        if (recovery == null) {
            state = P503StartupState.StartupError
            return
        }
        state =
            when (val probe = recovery.probe()) {
                is PointerMissingRecoveryProbe.Recoverable -> P503StartupState.PointerRecovery(probe.state)
                PointerMissingRecoveryProbe.NotPointerMissing -> P503StartupState.StartupError
            }
    }
}

/**
 * P5-04.4 S3: a freshly-built ledger graph wrapped with its close action so the composition
 * root can release the underlying driver connection on retry or mid-failure without leaking
 * it. `close` is idempotent for the underlying JdbcSqliteDriver.
 *
 * P7-01.C (D-143): [catalogSession] and [catalogCommands] are the host-layer entry P7-01.D
 * consumes: run a management command through [catalogCommands], then [CatalogConsumerSession.refresh]
 * to reload the authoritative catalog and rebuild the option/read/summary models.
 */
internal data class CloseableLedgerGraph(
    val facade: P503LedgerFacade,
    val close: () -> Unit,
    val catalogSession: CatalogConsumerSession? = null,
    val catalogCommands: ExecuteCatalogCommand? = null,
    // A-PERF (P7-04 read-governance batch, spec section 0 item 3): the controlled
    // statistics-refresh entry this wrapper previously had no way to reach (CloseableLedgerGraph
    // exposed no driver-facing member). The composition root fills it from the freshly built
    // [DesktopLedgerGraph]; tests may keep it unset (the default runs nothing).
    val runQueryStatisticsOptimize: () -> Unit = {},
    // A-PERF rework 3: the intake-completion trigger's entry (explicit full-schema ANALYZE; the
    // device-evidenced strong guarantee). Filled from the freshly built graph like the
    // bootstrap entry above.
    val runFullAnalyze: () -> Unit = {},
    // P7-06 06.B (D-177; spec sections 3.3/3.4): the controlled snapshot surface over this
    // graph's private JDBC driver. Null when the composition root wired none.
    val snapshotPort: BackupSnapshotPort? = null,
)

internal data class DesktopLedgerGraph(
    val database: LedgerDatabase,
    val ledgerId: LedgerId,
    val categoryId: CategoryId,
    val paymentAccountId: AccountId,
    val useCase: ExecuteConfirmedManualExpense,
    val ledgerClock: LedgerClock,
    val facade: P503LedgerFacade,
    val catalogSession: CatalogConsumerSession,
    val catalogCommands: ExecuteCatalogCommand,
    // A-PERF (P7-04 read-governance batch, spec section 2.1): the desktop-side controlled
    // statistics-refresh entry over this graph's driver. CloseableLedgerGraph previously
    // exposed no driver-reaching member (the same interface gap the spec names); the graph
    // method keeps the driver private to this composition root while the shared P503App
    // intake-completion trigger can reach it through the facade wiring below.
    val runQueryStatisticsOptimize: () -> Unit,
    // A-PERF rework 3: the intake-completion trigger's controlled entry — the explicit
    // full-schema ANALYZE (the device-evidenced strong guarantee; PRAGMA optimize does not
    // grant first-time analysis to tables without a stat1 planning history).
    val runFullAnalyze: () -> Unit,
    // P7-06 06.B (D-177; spec sections 3.3/3.4): the controlled snapshot surface over this
    // graph's private JDBC driver. Null when the composition root wired none.
    val snapshotPort: BackupSnapshotPort? = null,
)

/**
 * Desktop composition-root graph (IMP-10 wiring, P5-03 spec 10.1; P7-01.C catalog consumption).
 * The catalog, options provider, current-state query and activity summary all come from one
 * [CatalogConsumerSession] over the persisted product catalog. The manual-expense write path is
 * wrapped by [CatalogAdmissionExpenseTransactionFactory] so it revalidates its account/category
 * references against the current catalog inside the write transaction (V-2). The `database`
 * handle is exposed only for test read-back; the UI and the write path never write through it
 * directly.
 */
internal fun buildLedgerGraph(
    driver: SqlDriver,
    createSchema: Boolean = true,
): DesktopLedgerGraph {
    if (createSchema) {
        LedgerDatabase.Schema.create(driver)
    }
    val database = LedgerDatabase(driver)

    val ledgerId = LedgerId(PRODUCT_LEDGER_ID)
    val currency = CATALOG_MANAGED_CURRENCY
    val paymentAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)
    val categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID)

    val store = SqlDelightCatalogStore(database, driver)
    val authority = bootstrapAuthority(store, ledgerId)
    // A-PERF (P7-04 read-governance batch, spec section 0 D-D / 2.1): the desktop open-time
    // statistics refresh, immediately after bootstrap and on the graph's own driver — the
    // SQLite-recommended long-connection pattern (run PRAGMA optimize when first opened). This
    // is deliberately NOT folded into configureSqliteConnection: connection configuration and
    // statistics maintenance stay separate concerns, and this trigger point is the bootstrap
    // completion the spec pins (Main.kt bootstrapAuthority call site). Zero DDL.
    runQueryStatisticsOptimizeOn(driver)
    val readAdapter = SqlDelightLedgerCurrentStateReadAdapter(database)
    val counterpartyStore = SqlDelightCounterpartyStore(database, driver)
    val entryPreferenceStore = SqlDelightEntryPreferenceStore(database)
    // P7-03.C/D: the reporting clock is injected into the session so the unified monthly
    // projection resolves 本月 (R-Q06-2) on the same authoritative catalog version.
    val ledgerClock = LedgerClock { Clock.System.now() }
    val session =
        CatalogConsumerSession(
            reader = store,
            ledgerId = ledgerId,
            initialAuthority = authority,
            readPort = readAdapter,
            counterpartyReader = counterpartyStore,
            clock = ledgerClock,
        )

    val port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)
    val tracker = CommitOnceInvocationTracker(port)
    val delegate =
        ConfirmedExpenseTransactionFactory { request, ids ->
            // The delegate and the admission wrapper both read the current catalog inside the
            // same write transaction, so the transaction built here uses the version that was
            // revalidated.
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedExpenseTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            when (
                val result =
                    createAssetPaidOrdinaryExpense(
                        catalog = catalog,
                        command =
                            AssetPaidOrdinaryExpenseCommand(
                                ledgerId = request.ledgerId,
                                amount = request.amount,
                                categoryId = request.categoryId,
                                paymentAccountId = request.paymentAccountId,
                                // P7-02.A S-4 (P702IMPL-01): the draft note must reach the formal
                                // version exactly like the income delegate does.
                                note = request.note,
                                times = TransactionTimes.collapsed(request.occurredAt),
                            ),
                        ids = ids.expenseIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualExpenseCommit(
                            confirmationId = ids.confirmationId,
                            transaction = result.value,
                        ),
                    )
                is DomainResult.Failure -> result
            }
        }
    // V-2 (spec 3.3/6.2): the production commit factory revalidates account/category admission
    // against the persisted catalog before any formal write.
    val factory = CatalogAdmissionExpenseTransactionFactory(admissionReader = store, delegate = delegate)
    val idSource = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(::secureRandomBytes))
    val requestIdSource = UuidV7ManualExpenseRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmed = ExecuteConfirmedManualExpense(tracker, idSource, factory)
    val executeSave = ExecuteManualExpenseSave(executeConfirmed)
    val queryCurrentState = session.queryCurrentState
    val resolver = ResolveManualExpenseCommitStatus(readAdapter)
    val submission = ExecuteManualExpenseSubmission(executeSave, tracker, resolver)

    // P7-02.A income product chain, symmetric to the expense chain: its own port/tracker/id
    // sources, its own V-2 admission wrapper and snapshot-aware resolver.
    val incomePort = SqlDelightConfirmedManualIncomeCommitPort(database, driver)
    val incomeTracker = CommitOnceInvocationTrackerIncome(incomePort)
    val incomeDelegate =
        ConfirmedIncomeTransactionFactory { request, ids ->
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedIncomeTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            when (
                val result =
                    createAssetReceivedOrdinaryIncome(
                        catalog = catalog,
                        command =
                            AssetReceivedOrdinaryIncomeCommand(
                                ledgerId = request.ledgerId,
                                amount = request.amount,
                                categoryId = request.categoryId,
                                receivingAccountId = request.receivingAccountId,
                                times = TransactionTimes.collapsed(request.occurredAt),
                                note = request.note,
                            ),
                        ids = ids.incomeIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualIncomeCommit(
                            confirmationId = ids.confirmationId,
                            transaction = result.value,
                        ),
                    )
                is DomainResult.Failure -> result
            }
        }
    val incomeFactory = CatalogAdmissionIncomeTransactionFactory(admissionReader = store, delegate = incomeDelegate)
    val incomeIdSource = UuidV7ConfirmedManualIncomeIdSource(UuidV7Generator(::secureRandomBytes))
    val incomeRequestIdSource = UuidV7ManualIncomeRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedIncome = ExecuteConfirmedManualIncome(incomeTracker, incomeIdSource, incomeFactory)
    val executeIncomeSave = ExecuteManualIncomeSave(executeConfirmedIncome)
    val incomeResolver = ResolveManualIncomeCommitStatus(readAdapter)
    val incomeSubmission = ExecuteManualIncomeSubmission(executeIncomeSave, incomeTracker, incomeResolver)

    // P7-02.B transfer product chain: its own port/tracker/id sources, its own V-2 admission
    // wrapper (which resolves the delegate against the current catalog inside the write
    // transaction) and snapshot-aware resolver.
    val transferPort = SqlDelightConfirmedManualTransferCommitPort(database, driver)
    val transferTracker = CommitOnceInvocationTrackerTransfer(transferPort)
    val transferDelegate =
        ConfirmedTransferTransactionFactory { request, ids ->
            val catalog =
                store.loadCurrent(request.ledgerId)
                    ?: return@ConfirmedTransferTransactionFactory DomainResult.Failure(DomainViolation.InvalidCatalog)
            ManualTransferTransactionFactory(catalog).create(request, ids)
        }
    val transferFactory = CatalogAdmissionTransferTransactionFactory(admissionReader = store, delegate = transferDelegate)
    val transferIdSource = UuidV7ConfirmedManualTransferIdSource(UuidV7Generator(::secureRandomBytes))
    val transferRequestIdSource = UuidV7ManualTransferRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedTransfer = ExecuteConfirmedManualTransfer(transferTracker, transferIdSource, transferFactory)
    val executeTransferSave = ExecuteManualTransferSave(executeConfirmedTransfer)
    val transferResolver = ResolveManualTransferCommitStatus(readAdapter)
    val transferSubmission = ExecuteManualTransferSubmission(executeTransferSave, transferTracker, transferResolver)
    // P7-02.C manual lending product chain: its own port/tracker/id sources, its own V-2 +
    // counterparty + position admission wrapper and snapshot-aware resolver. The store is also
    // the counterparty directory and the per-object position read model.
    val lendingPort = SqlDelightConfirmedManualLendingCommitPort(database, driver)
    val lendingTracker = CommitOnceInvocationTrackerLending(lendingPort)
    val lendingDelegate =
        ConfirmedLendingTransactionFactory { request, ids ->
            ManualLendingTransactionFactory(
                admissionReader = store,
                counterpartyReader = counterpartyStore,
                positionReader = counterpartyStore,
            ).create(request, ids)
        }
    val lendingIdSource = UuidV7ConfirmedManualLendingIdSource(UuidV7Generator(::secureRandomBytes))
    val lendingRequestIdSource = UuidV7ManualLendingRequestIdSource(UuidV7Generator(::secureRandomBytes))
    val executeConfirmedLending = ExecuteConfirmedManualLending(lendingTracker, lendingIdSource, lendingDelegate)
    val executeLendingSave = ExecuteManualLendingSave(executeConfirmedLending)
    val lendingResolver = ResolveManualLendingCommitStatus(readAdapter)
    val lendingSubmission = ExecuteManualLendingSubmission(executeLendingSave, lendingTracker, lendingResolver)
    val counterpartyCommands =
        CounterpartyCommands(
            create = ExecuteCreateCounterparty(counterpartyStore, UuidV7CounterpartyIdSource(UuidV7Generator(::secureRandomBytes)), currency),
            rename = ExecuteRenameCounterparty(counterpartyStore),
            setActive = ExecuteSetCounterpartyActive(counterpartyStore),
            reader = counterpartyStore,
        )
    val entrySubmission = ExecuteManualEntrySubmission(submission, incomeSubmission, transferSubmission, ExecuteLendingSubmission(lendingSubmission))
    val catalogCommands =
        ExecuteCatalogCommand(
            commitPort = store,
            requestIdSource = UuidV7CatalogManagementRequestIdSource(UuidV7Generator(::secureRandomBytes)),
            entityIdSource = UuidV7CatalogEntityIdSource(UuidV7Generator(::secureRandomBytes)),
            categoryReferenceProbe = store,
        )
    val snapshotQuery = QueryCatalogSnapshot(store)

    // P7-04.A/B (D-146): the import spine store on the desktop's own JDBC driver (the driver
    // is in hand here, so the public constructor applies the JDBC connection configuration);
    // the intake id source is the production UUIDv7 source (R-Q09-2: ids are minted only
    // inside the store's winning claim transaction); the candidate audit-time source is the
    // injected LedgerClock (processing times only, never source times). The session factory
    // mints one fresh opaque UUIDv7 handle per file pick (R-Q09-1) — a new session per pick,
    // never shared across concurrent dispatches.
    val importSpineStore = SqlDelightImportSpineStore(database, driver)
    val importIntakeGenerator = UuidV7Generator(::secureRandomBytes)
    val executeImportIntake =
        ExecuteImportIntake(
            commitPort = importSpineStore,
            idSource = UuidV7ImportIntakeIdSource(UuidV7Generator(::secureRandomBytes)),
            fingerprint = ImportContentFingerprint(),
        )
    val importFileIntake =
        JvmImportFileIntake(
            ledgerId = ledgerId,
            executeIntake = executeImportIntake,
            candidateGeneratedAt = { ledgerClock.now().toString() },
        )
    // The desktop pick port: the real Swing chooser dialog plus FileInputStream. P7-04.C wires the
    // results into the shared channel; the modal chooser blocks the calling (UI event handler)
    // thread until closed, so onResult delivers on the UI thread (the frozen disclosure).
    val importPickChannel = ImportFilePickResultChannel()
    val importFilePickPort =
        DesktopImportFilePickPort(
            onResult = importPickChannel::deliver,
            showOpenFileChooser = ::showSwingOpenFileChooser,
            openInputStream = { file -> FileInputStream(file) },
        )
    // P7-04.C (D-146; spec sections 4.5/6.1): the import review read surface over the same
    // database — the adapter, the three read use cases, and the core duplicate-review use case
    // (commit port = the spine store) with a per-intent UUIDv7 id mint: a fresh
    // requestId/reviewId/historyId triple for every review intent (R-Q09-2; claim-gated,
    // replay/conflict paths never consume ids).
    val importReviewReadAdapter = SqlDelightImportReviewReadAdapter(database)
    val importReviewIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importDuplicateReview = ReviewImportDuplicateCandidate(commitPort = importSpineStore)
    // P7-04.D (D-146; spec section 9 P7-04.D row): the per-kind ConfirmImportCandidate wiring
    // over the same spine store — commitPort = the spine store, an ImportCommitIds mint with the
    // kind's frozen posting count (the shape-gated 3/2 split), the existing per-kind formal
    // factories and the catalog. The set is built FRESH on every dispatch run (the facade calls
    // this factory per run) so the frozen use case's construction-time catalog parameter is
    // always the CURRENT catalog — the manual-flow V-2 admission precedent (fresh admission
    // data per write attempt). The credit kinds share one CreditFlowFormalFactory (the
    // direct/refund/repayment variants dispatch on the decision-fields type, exactly like the
    // core's confirm kind gate); its refund original-expense reader resolves through the P7-03
    // read model. The transfer direction gate observes the ledger's seed real asset account
    // (the demo composition's single payment account; the wallet/bank factory variants share
    // the identical predicate and differ only in the observed account identity).
    val importCommitIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importConfirmRequestIdGenerator = UuidV7Generator(::secureRandomBytes)
    val importConfirmUseCasesFactory: () -> ImportConfirmUseCaseSet = {
        val currentCatalog = store.loadCurrent(ledgerId) ?: authority.catalog
        val creditFormalFactory =
            CreditFlowFormalFactory(currentCatalog, importCreditRefundOriginalExpenseProvider(session.queryTransactionDetail))
        val twoPostingIds = UuidV7ImportCommitIdSource(importCommitIdGenerator, postingCount = 2)
        val threePostingIds = UuidV7ImportCommitIdSource(importCommitIdGenerator, postingCount = 3)
        ImportConfirmUseCaseSet(
            ordinaryFlow = ConfirmImportCandidate(importSpineStore, twoPostingIds, OrdinaryFlowFormalFactory(currentCatalog), currentCatalog),
            transferFlow =
                ConfirmImportCandidate(
                    importSpineStore,
                    twoPostingIds,
                    TransferFlowFormalFactory(currentCatalog, AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID)),
                    currentCatalog,
                ),
            creditExpense = ConfirmImportCandidate(importSpineStore, twoPostingIds, creditFormalFactory, currentCatalog),
            creditRepayment = ConfirmImportCandidate(importSpineStore, twoPostingIds, creditFormalFactory, currentCatalog),
            mixedPayment = ConfirmImportCandidate(importSpineStore, threePostingIds, MixedPaymentFlowFormalFactory(currentCatalog), currentCatalog),
        )
    }
    // P7-05.B/C (D-156/D-158 slice 1b): the correction and void/restore product surface over the
    // graph's driver (the public port constructors configure the JDBC connection, exactly like
    // the manual commit ports above). The id sources are the production UUIDv7 mints
    // (claim-gated: replays and identity conflicts consume no ids, though the correction/restore
    // path may discard ids minted before a lost CAS or an in-plan rejection) and the catalog
    // admission reader is the same store the manual flows revalidate against. The two
    // snapshot-aware resolvers read the same read adapter as the P7-03/P7-04 read surface.
    val correctionCommitPort = SqlDelightTransactionCorrectionCommitPort(database, driver)
    val voidCommitPort = SqlDelightTransactionVoidCommitPort(database, driver)
    val executeCorrectTransactionVersion =
        ExecuteCorrectTransactionVersion(
            commitPort = correctionCommitPort,
            idSource = UuidV7TransactionCorrectionIdSource(UuidV7Generator(::secureRandomBytes)),
            admissionReader = store,
        )
    val voidFactIdSource = UuidV7TransactionVoidFactIdSource(UuidV7Generator(::secureRandomBytes))
    val executeVoidTransaction =
        ExecuteVoidTransaction(
            commitPort = voidCommitPort,
            idSource = voidFactIdSource,
            clock = ledgerClock,
        )
    val executeRestoreTransaction =
        ExecuteRestoreTransaction(
            commitPort = voidCommitPort,
            idSource = voidFactIdSource,
            clock = ledgerClock,
            admissionReader = store,
        )
    val facade =
        P503LedgerFacade(
            ledgerId = ledgerId,
            currency = currency,
            catalog = authority.catalog,
            parseAmount = ParseManualExpenseAmount(),
            parseOccurredAt = ParseManualExpenseOccurredAt(),
            baseOptionsProvider = session.optionsProvider,
            baseQueryCurrentState = queryCurrentState,
            resolveCommitStatus = resolver,
            submitExpense = submission,
            requestIdSource = requestIdSource,
            ledgerClock = ledgerClock,
            baseSummarizeActivity = session.summarizeActivity,
            // P7-02.A typed entry surface.
            submitEntry = entrySubmission,
            submitIncome = incomeSubmission,
            resolveIncomeCommitStatus = incomeResolver,
            incomeRequestIdSource = incomeRequestIdSource,
            baseIncomeOptionsProvider = session.incomeOptionsProvider,
            resolveTransferCommitStatus = transferResolver,
            transferRequestIdSource = transferRequestIdSource,
            baseTransferOptionsProvider = session.transferOptionsProvider,
            resolveLendingCommitStatus = lendingResolver,
            lendingRequestIdSource = lendingRequestIdSource,
            baseLendingOptionsProvider = session.lendingOptionsProvider,
            lendingPositions = counterpartyStore,
            counterpartyCommands = counterpartyCommands,
            // P7-02.D: the manual pin preference surface.
            entryPreferences = entryPreferenceStore,
            // P7-01.D: the management surface reads the same authoritative session and refreshes
            // it after every command, so options/reads/summaries stay on one catalog version.
            catalogSnapshot = { snapshotQuery.query(ledgerId) },
            executeCatalogCommand = catalogCommands,
            refreshCatalog = { session.refresh() },
            // P7-03.C/D: the ledger-view read surface on the same authoritative session.
            baseQueryLedgerEntryRows = session.queryLedgerEntryRows,
            baseQueryMonthlyActivity = session.queryMonthlyActivity,
            baseQueryTransactionDetail = session.queryTransactionDetail,
            catalogSession = session,
            // P7-04.A/B (D-146): the import surface — the Swing pick port and the jvmMain
            // intake orchestration on the same ledger; the P7-04.C host consumes both
            // through the facade to build the typed intake input.
            importFilePickPort = importFilePickPort,
            importFileIntake = importFileIntake,
            importPlatformKind = ImportPlatformKind.DESKTOP,
            importIntakeSessionFactory = { ImportIntakeSessionIdentity.forFilePick(importIntakeGenerator) },
            // P7-04.C: the review read surface + the duplicate-review use case + its id mint +
            // the shared pick-result channel (the Swing onResult above delivers into it).
            baseQueryImportReviewRows = QueryImportReviewRows(importReviewReadAdapter),
            baseQueryImportCandidateDetail = QueryImportCandidateDetail(importReviewReadAdapter),
            baseQueryImportDuplicateReviews = QueryImportDuplicateReviews(importReviewReadAdapter),
            // P7-05: the session-level batch read behind the 整组确认页 enumeration.
            baseQueryImportDuplicateReviewsForSession = QueryImportDuplicateReviewsForSession(importReviewReadAdapter),
            importDuplicateReview = importDuplicateReview,
            importDuplicateReviewIds = {
                ImportDuplicateReviewIds(
                    ImportRequestId(importReviewIdGenerator.next()),
                    ImportDuplicateReviewId(importReviewIdGenerator.next()),
                    ImportStatusHistoryId(importReviewIdGenerator.next()),
                )
            },
            importPickResultChannel = importPickChannel,
            // P7-04.D: the batch confirmation surface — the per-kind confirm use case factory
            // (fresh catalog per dispatch run) and the per-item requestId mint (a fresh UUIDv7
            // per item, minted once per authorization intent by the host).
            importConfirmUseCases = importConfirmUseCasesFactory,
            importConfirmRequestIdSource = { importConfirmRequestIdGenerator.next() },
            // A-PERF: the shared intake-completion statistics refresh (spec section 2.1) — the
            // composition root hands the graph's controlled driver entry to the shared host
            // pipeline, which runs it off the UI thread right after the intake transaction.
            importIntakeStatisticsRefresh = { runFullAnalyzeOn(driver) },
            // P7-05.B/C (D-156/D-158 slice 1b): the correction/void/restore surface. The
            // recycle-bin read follows the catalog session (it is rebuilt by refreshCatalog like
            // queryTransactionDetail), so restore revalidation and dependency names stay on the
            // current authoritative version.
            correctTransactionVersion = executeCorrectTransactionVersion,
            voidTransaction = executeVoidTransaction,
            restoreTransaction = executeRestoreTransaction,
            resolveCorrectionCommitStatus = ResolveTransactionCorrectionCommitStatus(readAdapter),
            resolveVoidCommitStatus = ResolveTransactionVoidCommitStatus(readAdapter),
            baseQueryRecycleBin = session.queryRecycleBin,
        )

    return DesktopLedgerGraph(
        database = database,
        ledgerId = ledgerId,
        categoryId = categoryId,
        paymentAccountId = paymentAccountId,
        useCase = executeConfirmed,
        ledgerClock = ledgerClock,
        facade = facade,
        catalogSession = session,
        catalogCommands = catalogCommands,
        // A-PERF: the same controlled entry exposed on the graph (the CloseableLedgerGraph
        // driver-visibility gap the spec names); the bootstrap run above already happened, this
        // member serves the intake trigger and any future maintenance surface.
        runQueryStatisticsOptimize = { runQueryStatisticsOptimizeOn(driver) },
        // A-PERF rework 3: the intake trigger's entry (full-schema ANALYZE).
        runFullAnalyze = { runFullAnalyzeOn(driver) },
        // P7-06 06.B (D-177): the controlled snapshot surface over this graph's driver.
        snapshotPort = DesktopBackupSnapshotPort(driver),
    )
}

/**
 * Idempotent bootstrap (spec 5.3): seed the default catalog only for an empty ledger, load the
 * current authority otherwise. An unresolved existing reference fails closed and is surfaced to
 * the startup controller as [P503StartupState.StartupError] (Retry/Exit) - never a static
 * catalog fallback.
 */
private fun bootstrapAuthority(
    store: SqlDelightCatalogStore,
    ledgerId: LedgerId,
) = when (val bootstrapped = store.bootstrap(ledgerId, defaultCatalogSeed())) {
    is CatalogBootstrapResult.Seeded -> bootstrapped.authority
    is CatalogBootstrapResult.AlreadyInitialized -> bootstrapped.authority
    CatalogBootstrapResult.UnknownReference -> throw CatalogBootstrapFailedException(ledgerId)
}

/**
 * Opens the desktop ledger with version detection and conditional migration (P7-01 spec
 * section 5.4, D-143). The existing file is never deleted or overwritten:
 *
 * 1. read `PRAGMA user_version` as `from` (an empty/new file reports 0);
 * 2. empty/new file -> `Schema.create`;
 * 3. `from == current` -> open directly;
 * 4. `from < current` -> `Schema.migrate(driver, from, to)` inside one outer transaction;
 * 5. `from > current` -> fail closed (rejected, never downgraded).
 *
 * Any step failure propagates so [DesktopStartupController] maps it to
 * [P503StartupState.StartupError] with only Retry/Exit.
 */
internal fun openDesktopLedger(databaseUrl: String): CloseableLedgerGraph {
    val driver = JdbcSqliteDriver(databaseUrl)
    return try {
        migrateToCurrentSchema(driver)
        val graph = buildLedgerGraph(driver, createSchema = false)
        CloseableLedgerGraph(
            graph.facade,
            { driver.close() },
            graph.catalogSession,
            graph.catalogCommands,
            graph.runQueryStatisticsOptimize,
            graph.runFullAnalyze,
            snapshotPort = graph.snapshotPort,
        )
    } catch (failure: Exception) {
        // A failure mid-open (schema create/open/migrate/bootstrap) must not leak the
        // half-opened driver; the startup controller maps it to StartupError.
        driver.close()
        throw failure
    }
}

/**
 * P7-06 06.1 (D-176; spec sections 3.3/4.5): the desktop product open through the shared stable
 * storage. The desktop side has no migratable legacy product location, so [openStableStorageLedger]
 * is called with a null legacy path; the sequence resolves the plan, performs the pre-open guard
 * on non-fresh paths and publishes the atomic pointer.
 *
 * Section 4.5 create-on-open boundary: the JDBC driver creates the database file on open, so a
 * non-fresh target is re-guarded here (non-empty, valid SQLite header) before the driver is
 * constructed. Only the fresh-install plan may create.
 */
internal fun openStableStorageDesktopLedger(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
): CloseableLedgerGraph =
    openStableStorageLedger(
        fileSystem = fileSystem,
        layout = layout,
        legacyMainFile = null,
        closeGraph = { graph -> graph.close() },
    ) { target ->
        requireUsableDesktopTarget(fileSystem, target)
        openDesktopLedger("jdbc:sqlite:${target.mainFile}")
    }

/**
 * P7-06 06.D (D-182; spec section 3 step 8 (with 3.8)): the desktop confirm switch's step-8 reopen — the
 * named generation is opened DIRECTLY, without the startup sequence's pointer resolution and
 * WITHOUT its switch-journal gate. The gate would consume this flow's own live `switched` journal
 * and roll the switch back (see [openExplicitGeneration]); the explicit route is safe because
 * step 6 already staged/fsynced/gated the generation and step 7 published the pointer to it.
 *
 * The same non-fresh usability guard as the startup open is applied (the JDBC driver creates on
 * open, so a missing/invalid main file must fail closed rather than become an empty ledger).
 */
internal fun openExplicitDesktopLedger(
    fileSystem: LedgerFileSystem,
    layout: LedgerStorageLayout,
    generation: Int,
): CloseableLedgerGraph =
    openExplicitGeneration(fileSystem, layout, generation) { target ->
        requireUsableDesktopTarget(fileSystem, target)
        openDesktopLedger("jdbc:sqlite:${target.mainFile}")
    }

/**
 * P7-06 06.1 (D-176; spec section 4.5): the desktop non-fresh create-on-open guard, shared by the
 * startup open and the 06.D explicit reopen so the two routes cannot drift.
 */
private fun requireUsableDesktopTarget(
    fileSystem: LedgerFileSystem,
    target: LedgerOpenTarget,
) {
    if (!target.allowCreateOnOpen && !isUsableSqliteMainFile(fileSystem, target.mainFile)) {
        throw LedgerStorageRejectedException(LedgerStorageFailure.ACTIVE_GENERATION_UNUSABLE)
    }
}

/**
 * Reads the SQLite user_version and conditionally creates, opens or migrates the schema.
 * Kept separate from [openDesktopLedger] so the fail-closed version path is directly testable.
 *
 * SQLDelight's generated `Schema.create`/`Schema.migrate` perform no `user_version` bookkeeping,
 * so this function stamps the version it just materialized. Without that stamp every subsequent
 * open would re-read 0 and re-run `Schema.create` on an already-populated file. The stamp after a
 * migration runs inside the same outer transaction as `Schema.migrate`, so a failed migration
 * rolls back both the schema changes and the version bump.
 */
internal fun migrateToCurrentSchema(driver: SqlDriver) {
    val currentVersion = LedgerDatabase.Schema.version.toLong()
    val from = driver.readUserVersion()
    when {
        // A3 (review): `user_version == 0` alone does not mean "new file". A populated ledger
        // written before the version stamp existed must never be handed to `Schema.create`
        // (that fails with "table ... already exists" and becomes a permanent StartupError).
        // Probe the actual schema instead and only create when the file is genuinely empty.
        from == 0L && driver.hasTable("catalog_version") -> {
            // Already the current product schema, only the version stamp was missing.
            driver.writeUserVersion(currentVersion)
        }
        from == 0L && driver.hasTable("ledger_transaction") && driver.hasV27StructuralSentinel() -> {
            // Legacy pre-catalog ledger without a version stamp, verified to actually carry the
            // v27 surface (R2: the sentinel guards the 26.sqm rebuild objects, so a real v26 or
            // older file cannot reach here and silently skip 26 -> 27). Treat it as v27 (the last
            // version before the additive catalog tables) and run the structure-only migration in
            // one transaction. A failure rolls back and surfaces as StartupError; the file is
            // never deleted or overwritten.
            val database = LedgerDatabase(driver)
            database.transaction {
                LedgerDatabase.Schema.migrate(driver, LEGACY_UNTAGGED_VERSION, currentVersion)
                driver.writeUserVersion(currentVersion)
            }
        }
        from == 0L && !driver.hasAnyUserTable() -> {
            LedgerDatabase.Schema.create(driver)
            driver.writeUserVersion(currentVersion)
        }
        from == 0L -> {
            // Unknown populated schema without a version stamp (R2: populated but not verifiably
            // v27, e.g. a real v26 file): never create over it and never guess a migration; stamp
            // the current version so opening is deterministic. Downstream catalog bootstrap fails
            // closed visibly if the schema does not match, instead of a partial schema silently
            // acquired by skipping a structural migration.
            driver.writeUserVersion(currentVersion)
        }
        from == currentVersion -> Unit
        from < currentVersion -> {
            val database = LedgerDatabase(driver)
            database.transaction {
                LedgerDatabase.Schema.migrate(driver, from, currentVersion)
                driver.writeUserVersion(currentVersion)
            }
        }
        else -> throw IllegalStateException(
            "Library schema version $from is newer than the supported version $currentVersion; refusing to open",
        )
    }
}

/** Last schema version before the additive P7-01 `catalog_*` tables (spec 5.2). */
private const val LEGACY_UNTAGGED_VERSION = 27L

/**
 * R2: structural sentinels used to confirm an untagged populated file really is the v27 surface
 * before running the v27 -> v28 migration. `evidence_projection_guard_update` and
 * `evidence_projection_guard_delete` were created by `25.sqm` (v25 -> v26); the
 * `evidence_projection_current_by_evidence` unique index and `reconciliation_correction_snapshot`
 * were created by `26.sqm` step 2/3 (v26 -> v27). Because the conjunction (`.all`) requires the
 * v27-only pair, a file at exactly v26 (guards present, index/snapshot absent) and any older file
 * both fail the sentinel and fall through to the stamp-only fail-closed branch, never a guessed
 * migrate; only a real v27+ surface passes.
 */
private val V27_STRUCTURAL_SENTINELS =
    listOf(
        "evidence_projection_current_by_evidence",
        "evidence_projection_guard_update",
        "evidence_projection_guard_delete",
        "reconciliation_correction_snapshot",
    )

private fun SqlDriver.hasV27StructuralSentinel(): Boolean = V27_STRUCTURAL_SENTINELS.all { hasSchemaObject(it) }

private fun SqlDriver.hasSchemaObject(name: String): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE name = ?",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        1,
        { bindString(0, name) },
    )
    return found
}

private fun SqlDriver.hasTable(name: String): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        1,
        { bindString(0, name) },
    )
    return found
}

private fun SqlDriver.hasAnyUserTable(): Boolean {
    var found = false
    executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        { cursor ->
            if (cursor.next().value) found = (cursor.getLong(0) ?: 0L) > 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        0,
    )
    return found
}

private fun SqlDriver.writeUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version", 0)
}

private fun SqlDriver.readUserVersion(): Long {
    var version = 0L
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            if (cursor.next().value) version = cursor.getLong(0) ?: 0L
            app.cash.sqldelight.db.QueryResult.Unit
        },
        0,
    )
    return version
}

private val secureRandom = SecureRandom()

private fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)
