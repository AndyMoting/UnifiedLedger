package com.unifiedledger.android

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import com.unifiedledger.application.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.application.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.application.CATALOG_MANAGED_CURRENCY
import com.unifiedledger.application.CatalogCommandResult
import com.unifiedledger.application.ConfirmationId
import com.unifiedledger.application.ConfirmedManualExpenseCommit
import com.unifiedledger.application.ConfirmedManualExpenseCommitIds
import com.unifiedledger.application.ConfirmedManualExpenseResult
import com.unifiedledger.application.ConfirmedManualTransferCommitIds
import com.unifiedledger.application.ConfirmedManualTransferResult
import com.unifiedledger.application.CurrentVersionRow
import com.unifiedledger.application.DEFAULT_EXPENSE_LEAF_ID
import com.unifiedledger.application.DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID
import com.unifiedledger.application.DEFAULT_MANAGEABLE_ACCOUNT_ID
import com.unifiedledger.application.ExecuteCatalogCommand
import com.unifiedledger.application.ExecuteImportIntake
import com.unifiedledger.application.IMPORT_FUNDING_RULE_LEGACY_SETTLED
import com.unifiedledger.application.ImportCandidateId
import com.unifiedledger.application.ImportCompleteness
import com.unifiedledger.application.ImportContentFingerprint
import com.unifiedledger.application.ImportEvidenceId
import com.unifiedledger.application.ImportFormatCapabilities
import com.unifiedledger.application.ImportFundingState
import com.unifiedledger.application.ImportIntakeIdSource
import com.unifiedledger.application.ImportIntakeIds
import com.unifiedledger.application.ImportIntakeRequest
import com.unifiedledger.application.ImportIntakeResult
import com.unifiedledger.application.ImportRecordKind
import com.unifiedledger.application.ImportRequestId
import com.unifiedledger.application.ImportRequestIdentity
import com.unifiedledger.application.ImportReviewRowsPageResult
import com.unifiedledger.application.ImportSourceFacts
import com.unifiedledger.application.ImportSourceId
import com.unifiedledger.application.ImportStatusHistoryId
import com.unifiedledger.application.LedgerCurrentStateResult
import com.unifiedledger.application.ManualExpenseRequestIdentity
import com.unifiedledger.application.ManualExpenseRequestSnapshot
import com.unifiedledger.application.ManualTransferRequestIdentity
import com.unifiedledger.application.ManualTransferRequestSnapshot
import com.unifiedledger.application.ManualTransferTransactionFactory
import com.unifiedledger.application.RequestId
import com.unifiedledger.application.TransactionDetail
import com.unifiedledger.application.TransactionDetailResult
import com.unifiedledger.application.export.readable.ReadableExportLegFacts
import com.unifiedledger.application.export.readable.readableExportAccountName
import com.unifiedledger.application.export.readable.readableExportAmountText
import com.unifiedledger.application.export.readable.readableExportCategoryPath
import com.unifiedledger.application.export.readable.readableExportKindLabel
import com.unifiedledger.application.export.readable.readableExportWallClockText
import com.unifiedledger.application.export.readable.resolveReadableExportPresentation
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createAssetPaidOrdinaryExpense
import com.unifiedledger.ui.ImportFilePickResultChannel
import com.unifiedledger.ui.LedgerStorageLayout
import com.unifiedledger.ui.P503LedgerFacade
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

/**
 * P8-07 Q2 flow 1 device support (design section 2, first row): the shared seed's device
 * equivalent, the two self-entry workbooks, the live-ledger read access and the system-picker
 * plumbing.
 *
 * WHY THIS FILE CARRIES NO TEST ANNOTATION: `tools/python/android_scale/inventory.py` enumerates
 * exactly one class per file that contains a test method, so this helper never enters the
 * inventory.
 *
 * THE SEED IS THE JVM SHARED SEED'S DEVICE EQUIVALENT. `P807SharedAcceptanceSeedWriterTest`
 * (`ledger-data/src/jvmTest`) writes a host-side SQLite file and is not on the androidTest
 * classpath. This file builds the SAME initial state on the device through the SAME production
 * entry points, over the app's own stable storage:
 * - the real default catalog seed plus two owned ASSET accounts created through the real catalog
 *   command (对账到账账户 = the transfer's destination, 退款到账账户 = the refundable expense's
 *   payment account);
 * - one confirmed `ACCOUNT_TRANSFER` of exactly 12.34 CNY (two legs, fee 0) at the shared seed's
 *   pinned instant 2026-10-14T04:00:00Z;
 * - one confirmed manual EXPENSE of exactly 21.00 CNY from 退款到账账户 into the default 早餐 leaf
 *   at 2026-10-15T02:00:00Z;
 * - one resolved import intake (source + evidence + pending candidate) for the transfer's
 *   destination leg as `transfer_flow_source`, written in one transaction.
 * The two transactions / four postings / one pending candidate counts, the exact balances and the
 * candidate status are ASSERTED before the app is launched, never assumed.
 *
 * THE STORAGE IS THE APP'S OWN, ON PURPOSE. A Q2 flow must drive the launched app's UI, so the
 * fixture seeds the storage `MainActivity` itself opens (`openAndroidStableStorageLedger` over the
 * target context) and then closes its graph. The case therefore has to be the FIRST case of its
 * shard process (no earlier app launch, no earlier ledger handle), which is why it is registered
 * first in `tools/ci/android-instrumented-inventory.txt`. The previous stable-storage state is
 * reset first (generations directory, active pointer, switch journal, legacy files), so a re-run
 * on an emulator that already holds this app's data still starts from the shared seed and never
 * from a prior run's writes. Only the disposable CI app's own storage is written.
 *
 * READ-BACK GOES THROUGH THE PRODUCT'S OWN RUNTIME ACCESSOR. After the launch the instrumentation
 * lives in the app's process and reads the ONE active graph through
 * `ledgerProcessHolderFor(context)?.ledger?.withFacade { ... }` — the same lease-scoped accessor
 * every business read/write uses. The formal snapshot is built from the production read use cases
 * (`queryCurrentState`, `queryTransactionDetail`, `queryImportReviewRows`), never from SQL.
 *
 * THE EXPORT ARTIFACT IS THE ONE NON-UI READ. The readable export's target is the system SAF
 * `CreateDocument` picker; after the user (here: UiAutomation) confirms the save, the bytes live
 * in the platform's Documents-provider storage and the app intentionally keeps no handle on them.
 * This file reads the committed file through the instrumentation's shell identity (`ls -t` /
 * `cat` under the platform Download/Documents roots) and parses it with the PRODUCTION
 * `BoundedXlsxReader`. The export's own UI steps stay real; only the artifact read crosses the
 * system-dialog boundary.
 */
internal object P807Q2Flow1Facts {
    /** The one grep-able device-evidence prefix of this flow. */
    const val PREFIX = "P807-Q2FLOW1"

    // The JVM shared seed's created ASSET accounts.
    const val RECONCILE_ACCOUNT_NAME = "对账到账账户"
    const val REFUND_ACCOUNT_NAME = "退款到账账户"

    // The JVM shared seed's transaction facts.
    const val TRANSFER_NOTE = "P807 对账样本转账"
    const val TRANSFER_MINOR = 1_234L
    const val REFUNDABLE_EXPENSE_NOTE = "P807 可退样本支出"
    const val REFUNDABLE_EXPENSE_MINOR = 2_100L

    val TRANSFER_INSTANT: Instant = Instant.parse("2026-10-14T04:00:00Z")
    val TRANSFER_CREATED_AT: Instant = Instant.parse("2026-10-14T04:05:00Z")
    val REFUNDABLE_EXPENSE_INSTANT: Instant = Instant.parse("2026-10-15T02:00:00Z")
    val REFUNDABLE_EXPENSE_CREATED_AT: Instant = Instant.parse("2026-10-15T02:05:00Z")
    val IMPORT_GENERATED_AT: Instant = Instant.parse("2026-10-14T04:01:00Z")

    // The JVM shared seed's fixed ids (a fresh device storage holds no collision).
    const val TRANSFER_TRANSACTION_ID = "p807-seed-transfer"
    const val TRANSFER_SOURCE_POSTING_ID = "p807-seed-transfer-out"
    const val TRANSFER_DESTINATION_POSTING_ID = "p807-seed-transfer-in"
    const val REFUNDABLE_EXPENSE_TRANSACTION_ID = "p807-seed-refundable-expense"
    const val SEEDED_CANDIDATE_ID = "p807-seed-import-candidate"
    const val SEEDED_SOURCE_ID = "p807-seed-import-source"
    const val SEEDED_EVIDENCE_ID = "p807-seed-import-evidence"
    const val SEEDED_STATUS_ID = "p807-seed-import-status"
    const val SEEDED_INPUT_REF = "p807-shared-seed-statement"

    // The flow's own import row: one parsed-and-matched self-entry row, confirmed below.
    const val WRONG_HEADER_FILE = "p807-q2-wrong-header.xlsx"
    const val CORRECT_FILE = "p807-q2-correct.xlsx"
    const val IMPORTED_AMOUNT_TEXT = "7.77"
    const val IMPORTED_AMOUNT_MINOR = 777L
    const val IMPORTED_OCCURRED_AT_TEXT = "2026-11-02 10:00:00"
    const val IMPORTED_OCCURRED_AT_INSTANT = "2026-11-02T02:00:00Z"

    /** The confirmed category/date filter: a custom window only the imported row falls in. */
    const val SEARCH_WINDOW_START = "2026-11-01T00:00:00Z"
    const val SEARCH_WINDOW_END = "2026-11-03T00:00:00Z"

    /** The self-entry format row and its picker MIME filter (the product matrix is the source). */
    val SELF_ENTRY_FORMAT = ImportFormatCapabilities.SELF_ENTRY_XLSX
    val SELF_ENTRY_MIME: String = ImportFormatCapabilities.SELF_ENTRY_XLSX.mimeFilters.first()

    // UI copy mirrored as literals: these strings are internal to app-ui, which androidTest does
    // not see. A divergent copy fails the assertion instead of passing silently (the P805 mirror
    // discipline).
    const val HOME_SEARCH_CAPSULE = "搜索账目"
    const val DRAWER_OPEN_DESCRIPTION = "打开菜单"
    const val DRAWER_IMPORT_ROW = "导入账单"
    const val DRAWER_EXPORT_ROW = "导出账目"
    const val IMPORT_SCREEN_MARKER = "选择账单文件"
    const val PICK_FILE_BUTTON = "选择文件"
    const val INTAKE_RECENT_PREFIX = "最近导入："
    const val INTAKE_BATCH_REJECTED = "文件解析失败（诊断码 STRUCTURE_MISMATCH），本次导入已中止。"
    const val INTAKE_ACCEPTED_LINE = "接治完成：新增 1，等价重放 0，解析拒绝 0，接治拒绝 0。"
    const val CANDIDATE_DETAIL_TITLE = "候选详情"
    const val CANDIDATE_DECISION_COMPLETE = "决策已补全。"
    const val CANDIDATE_CHECKBOX_DESCRIPTION = "勾选候选"

    /**
     * The self-entry row names 一级分类 餐饮 + 二级分类 早餐, so the D-18/D-19 basis carries the full
     * path as its source text and resolves it through D-024's path disambiguation: the rendered
     * category line is `分类：餐饮/早餐 → 早餐（路径消歧）`.
     */
    const val CANDIDATE_MATCH_BASIS_CATEGORY = "分类：餐饮/早餐 → 早餐（路径消歧）"
    const val CANDIDATE_MATCH_BASIS_ACCOUNT = "资金账户：默认支付账户 → 默认支付账户"
    const val CANDIDATE_BATCH_ENTRY = "进入批量确认"
    const val BATCH_CONFIRM_TITLE = "批量确认"
    const val BATCH_AUTHORIZE_BUTTON = "确认入账（1 项）"
    const val BATCH_RESULT_PREFIX = "最近批量结果（授权时间 "
    const val BATCH_CONFIRMED_ONE = "已入账 1 项"
    const val SEARCH_WINDOW_CUSTOM = "自定义起止"
    const val SEARCH_CUSTOM_START_LABEL = "开始（含）"
    const val SEARCH_CUSTOM_END_LABEL = "结束（不含）"
    const val SEARCH_KEYWORD_LABEL = "关键词（备注 / 商家名 / 分类名）"
    const val SEARCH_SUBMIT_DESCRIPTION = "提交搜索"
    const val SEARCH_RESULTS_ONE = "搜索结果 · 共 1 笔"
    const val TRANSACTION_DETAIL_TITLE = "交易详情"
    const val EXPORT_START_BUTTON = "开始导出"
    const val EXPORT_SUCCESS_PREFIX_3 = "已导出 3 笔"
    const val BACK_LABEL = "返回"

    /** The self-entry template's frozen header is the generator's own constant (never a copy). */
    val SELF_ENTRY_HEADERS: List<String> = SelfEntryTemplateGenerator.HEADERS.toList()
}

// ------------------------------------------------------------------ the formal-ledger snapshot

/**
 * One read of the formal ledger through the production read use cases. `transactions`, `legs`,
 * `balances` and `candidates` are comparable strings, so a before/after equality is a whole
 * ledger-face equality rather than a row-count approximation.
 */
internal data class P807FormalSnapshot(
    val transactionTotal: Int,
    val transactions: List<String>,
    val legs: List<String>,
    val balances: List<String>,
    val candidates: List<String>,
) {
    fun logLine(): String =
        "tx=$transactionTotal postings=${legs.size} candidates=${candidates.size} " +
            "balances[${balances.joinToString(";")}] candidates[${candidates.joinToString(";")}]"

    fun pendingCandidateCount(): Int = candidates.count { it.contains("|pending_confirmation|") }

    fun candidateStatuses(): List<String> = candidates.map { it.split("|")[1] }.sorted()
}

/** Reads one whole formal snapshot through [facade]'s production read use cases. */
internal fun p807Q2Flow1ReadSnapshot(facade: P503LedgerFacade): P807FormalSnapshot {
    val state =
        when (val read = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> read.state
            LedgerCurrentStateResult.InvalidState -> error("the seeded ledger read as InvalidState")
            LedgerCurrentStateResult.Unavailable -> error("the seeded ledger read as Unavailable")
        }
    val detailQuery = checkNotNull(facade.queryTransactionDetail) { "the production graph must wire the transaction detail read" }
    val transactions = mutableListOf<String>()
    val legs = mutableListOf<String>()
    state.transactions.sortedBy { it.transactionId.value }.forEach { row ->
        val detail =
            when (val read = detailQuery.query(row.transactionId)) {
                is TransactionDetailResult.Success -> read.detail
                TransactionDetailResult.NotFound -> error("transaction ${row.transactionId.value} is not in the current set")
                TransactionDetailResult.InvalidState -> error("transaction ${row.transactionId.value} read as InvalidState")
            }
        transactions +=
            "${row.transactionId.value}|${row.kind}|${detail.occurredAt}|${detail.statisticsAt}|" +
            "${detail.note.orEmpty()}|legs=${row.postings.size}"
        detail.legs.forEach { leg ->
            legs +=
                "${row.transactionId.value}|${leg.postingId.value}|${leg.accountId.value}|${leg.accountName}|" +
                "${leg.amount.minorUnits}|${leg.amount.currency.code}|${leg.amount.currency.precision}|${leg.categoryName.orEmpty()}"
        }
    }
    val reviewRows = checkNotNull(facade.queryImportReviewRows) { "the production graph must wire the import review read" }
    val page =
        when (val read = reviewRows.queryPage(facade.ledgerId, 50L, null)) {
            is ImportReviewRowsPageResult.Page -> read
            ImportReviewRowsPageResult.Unavailable -> error("the import review read answered Unavailable")
        }
    val candidates =
        page.rows
            .map { row ->
                "${row.candidateId.value}|${row.candidateStatus}|${row.duplicateStatus?.name.orEmpty()}|" +
                    "${row.amountMinor}|${row.currencyCode.orEmpty()}"
            }.sorted()
    val balances =
        state.balances
            .map { balance ->
                "${balance.accountId.value}|${balance.currency.code}|${balance.currency.precision}|${balance.displayMinorUnits}"
            }.sorted()
    return P807FormalSnapshot(
        transactionTotal = state.transactionTotal,
        transactions = transactions,
        legs = legs,
        balances = balances,
        candidates = candidates,
    )
}

/** The imported transaction's detail, resolved as the single id the confirmation added. */
internal fun p807Q2Flow1AddedTransaction(
    facade: P503LedgerFacade,
    knownTransactionIds: Set<String>,
): TransactionDetail {
    val state =
        when (val read = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> read.state
            LedgerCurrentStateResult.InvalidState -> error("the ledger read as InvalidState after the confirmation")
            LedgerCurrentStateResult.Unavailable -> error("the ledger read as Unavailable after the confirmation")
        }
    val addedIds = state.transactions.map { it.transactionId.value }.filterNot { it in knownTransactionIds }
    check(addedIds.size == 1) {
        "the confirmation must add exactly one transaction, added=${addedIds.sorted()} known=${knownTransactionIds.sorted()}"
    }
    val detailQuery = checkNotNull(facade.queryTransactionDetail) { "the production graph must wire the transaction detail read" }
    return when (val read = detailQuery.query(TransactionId(addedIds.single()))) {
        is TransactionDetailResult.Success -> read.detail
        TransactionDetailResult.NotFound -> error("the added transaction is not in the current set")
        TransactionDetailResult.InvalidState -> error("the added transaction read as InvalidState")
    }
}

/** The added transaction's formal posting row, used to derive the expected export cells. */
internal fun p807Q2Flow1AddedPostingRow(
    facade: P503LedgerFacade,
    transactionId: String,
): CurrentVersionRow {
    val state =
        when (val read = facade.queryCurrentState.query()) {
            is LedgerCurrentStateResult.Success -> read.state
            LedgerCurrentStateResult.InvalidState -> error("the ledger read as InvalidState for the export row")
            LedgerCurrentStateResult.Unavailable -> error("the ledger read as Unavailable for the export row")
        }
    return checkNotNull(state.transactions.firstOrNull { it.transactionId.value == transactionId }) {
        "the added transaction must still be a current version row"
    }
}

/**
 * The EXACT readable-export row the production exporter must stream for [detail]: every cell is
 * derived through the exporter's own public contract functions and the live catalog, so the test
 * never hand-copies a presentation rule.
 */
internal data class P807ExpectedExportRow(
    val occurredAtText: String,
    val statisticsAtText: String,
    val kindLabel: String,
    val amountText: String,
    val currencyCode: String,
    val account1Name: String,
    val account2Name: String,
    val categoryPathText: String,
) {
    fun cells(): List<String> =
        listOf(
            occurredAtText,
            statisticsAtText,
            kindLabel,
            amountText,
            currencyCode,
            account1Name,
            account2Name,
            categoryPathText,
        )

    fun logLine(): String = "exportRow[${cells().joinToString("|")}]"
}

internal fun p807Q2Flow1ExpectedExportRow(
    facade: P503LedgerFacade,
    detail: TransactionDetail,
): P807ExpectedExportRow {
    val catalog = facade.catalog
    val row = p807Q2Flow1AddedPostingRow(facade, detail.transactionId.value)
    val legFacts =
        row.postings.map { posting ->
            ReadableExportLegFacts(
                posting = posting,
                accountKind = catalog.accounts.firstOrNull { it.id == posting.accountId }?.kind,
                categoryId = catalog.categories.firstOrNull { it.postingAccountId == posting.accountId }?.id,
            )
        }
    val presentation = resolveReadableExportPresentation(row.kind, legFacts)
    check(presentation.resolved) { "the imported transaction's export presentation must resolve" }
    val currency = checkNotNull(presentation.currency) { "a resolved presentation must name its currency" }
    val minor = checkNotNull(presentation.amountMinor) { "a resolved presentation must carry its amount" }
    return P807ExpectedExportRow(
        occurredAtText = readableExportWallClockText(detail.occurredAt),
        statisticsAtText = readableExportWallClockText(detail.statisticsAt),
        kindLabel = readableExportKindLabel(row.kind),
        amountText = readableExportAmountText(minor, currency.precision),
        currencyCode = currency.code,
        account1Name = readableExportAccountName(catalog, presentation.account1).orEmpty(),
        account2Name = readableExportAccountName(catalog, presentation.account2).orEmpty(),
        categoryPathText = readableExportCategoryPath(catalog, presentation.categoryAnchorAccountId).orEmpty(),
    )
}

// ------------------------------------------------------------------ the seed

/** The sealed starting state: the snapshot plus the ids the later legs compare against. */
internal data class P807SeededLedger(
    val initial: P807FormalSnapshot,
    val reconcileAccountId: AccountId,
    val refundAccountId: AccountId,
    val knownTransactionIds: Set<String>,
)

internal object P807Q2Flow1Seed {
    /**
     * Resets the app's ledger storage, seeds the shared initial state through the production
     * graph, asserts it and closes the graph so the launched app can open it. Call only in a
     * process that has not opened the ledger yet.
     */
    fun prepare(
        context: Context,
        log: (String) -> Unit,
    ): P807SeededLedger {
        val layout = scaleFixtureLayout(context)
        p807Q2Flow1RequireNoOpenLedgerHandle(layout.hostDirectory, log)
        p807Q2Flow1ResetLedgerStorage(context, layout, log)
        var handle: AndroidLedgerDatabaseHandle? = null
        val graph =
            openAndroidStableStorageLedger(
                context = context,
                importFilePickPort =
                    AndroidImportFilePickPort<Uri>(
                        launchOpenDocument = {},
                        resolveMetadata = { PickedSafFileMetadata(displayName = "", sizeBytes = null) },
                        openInputStream = { null },
                        onResult = {},
                    ),
                importPickChannel = ImportFilePickResultChannel(),
                openDriver = { name -> createAndroidLedgerDatabase(context, name).also { handle = it } },
            )
        try {
            val facade = graph.facade
            val ledgerId = facade.ledgerId
            val commands = checkNotNull(graph.catalogCommands) { "the production graph must wire the catalog commands" }
            val reconcileAccountId = p807Q2Flow1CreateAccount(commands, ledgerId, P807Q2Flow1Facts.RECONCILE_ACCOUNT_NAME, 1L)
            val refundAccountId = p807Q2Flow1CreateAccount(commands, ledgerId, P807Q2Flow1Facts.REFUND_ACCOUNT_NAME, 2L)
            // The catalog commands moved the authoritative catalog (two new ASSET accounts); the
            // session's read models rebuild from the reloaded authority, so the seed's read-back
            // below validates the transfer's and expense's legs against the CURRENT catalog instead
            // of a stale build-time snapshot (the composition roots' own post-command rule).
            checkNotNull(graph.catalogSession) { "the production graph must wire the catalog session" }.refresh()
            val databaseHandle = checkNotNull(handle) { "the graph open must capture its database handle" }
            val catalog =
                checkNotNull(databaseHandle.catalogStore.loadCurrent(ledgerId)) {
                    "the seeded ledger must carry its current catalog"
                }
            p807Q2Flow1CommitTransfer(databaseHandle, ledgerId, catalog, reconcileAccountId)
            p807Q2Flow1CommitRefundableExpense(databaseHandle, ledgerId, catalog, refundAccountId)
            p807Q2Flow1IntakeTransferEvidence(databaseHandle, ledgerId)

            val initial = p807Q2Flow1ReadSnapshot(facade)
            // The shared seed's exact starting facts: two transactions, four postings, one pending
            // candidate, and the exact balances the two writes moved.
            check(initial.transactionTotal == 2) {
                "the shared seed must start with 2 transactions, got ${initial.transactionTotal}: ${initial.logLine()}"
            }
            check(initial.legs.size == 4) {
                "the shared seed must start with 4 postings, got ${initial.legs.size}: ${initial.logLine()}"
            }
            check(initial.pendingCandidateCount() == 1) {
                "the shared seed must start with one pending import candidate: ${initial.logLine()}"
            }
            val expectedBalances =
                p807Q2Flow1Balances(
                    DEFAULT_MANAGEABLE_ACCOUNT_ID to -P807Q2Flow1Facts.TRANSFER_MINOR,
                    reconcileAccountId.value to P807Q2Flow1Facts.TRANSFER_MINOR,
                    refundAccountId.value to -P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
                    DEFAULT_HIDDEN_EXPENSE_ACCOUNT_ID to P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR,
                )
            check(initial.balances == expectedBalances) {
                "the shared seed's balances are not the exact seeded amounts: expected=$expectedBalances got=${initial.balances}"
            }
            log(
                "${P807Q2Flow1Facts.PREFIX} seed transactions=${initial.transactionTotal} postings=${initial.legs.size} " +
                    "candidates=${initial.candidates.size} balances=${initial.balances.joinToString(";")}",
            )
            return P807SeededLedger(
                initial = initial,
                reconcileAccountId = reconcileAccountId,
                refundAccountId = refundAccountId,
                knownTransactionIds =
                    setOf(
                        P807Q2Flow1Facts.TRANSFER_TRANSACTION_ID,
                        P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID,
                    ),
            )
        } finally {
            graph.close()
        }
    }
}

/** The exact balance snapshot text of one set of account/minor-unit pairs. */
internal fun p807Q2Flow1Balances(vararg expected: Pair<String, Long>): List<String> =
    expected
        .map { (accountId, minor) -> "$accountId|${CATALOG_MANAGED_CURRENCY.code}|${CATALOG_MANAGED_CURRENCY.precision}|$minor" }
        .sorted()

private fun p807Q2Flow1CreateAccount(
    commands: ExecuteCatalogCommand,
    ledgerId: LedgerId,
    name: String,
    expectedCatalogVersion: Long,
): AccountId =
    when (
        val result =
            commands.createAccount(
                ledgerId = ledgerId,
                name = name,
                kind = AccountKind.ASSET,
                expectedCatalogVersion = expectedCatalogVersion,
            )
    ) {
        is CatalogCommandResult.Accepted ->
            checkNotNull(result.receipt.createdManageableAccountId) { "an accepted CreateAccount must name the account it created" }
        else -> error("the shared seed's account [$name] must be created, got $result")
    }

private fun p807Q2Flow1CommitTransfer(
    handle: AndroidLedgerDatabaseHandle,
    ledgerId: LedgerId,
    catalog: LedgerCatalog,
    destinationAccountId: AccountId,
) {
    val snapshot =
        ManualTransferRequestSnapshot(
            ledgerId = ledgerId,
            sourceAccountId = AccountId(DEFAULT_MANAGEABLE_ACCOUNT_ID),
            destinationAccountId = destinationAccountId,
            destinationCredit = Money.ofMinor(P807Q2Flow1Facts.TRANSFER_MINOR, CATALOG_MANAGED_CURRENCY),
            fee = Money.ofMinor(0L, CATALOG_MANAGED_CURRENCY),
            feeCategoryId = null,
            occurredAt = P807Q2Flow1Facts.TRANSFER_INSTANT,
            note = P807Q2Flow1Facts.TRANSFER_NOTE,
        )
    val ids =
        AccountTransferIds(
            transactionId = TransactionId(P807Q2Flow1Facts.TRANSFER_TRANSACTION_ID),
            versionId = TransactionVersionId("${P807Q2Flow1Facts.TRANSFER_TRANSACTION_ID}-version"),
            postingSetId = PostingSetId("${P807Q2Flow1Facts.TRANSFER_TRANSACTION_ID}-posting-set"),
            sourcePostingId = PostingId(P807Q2Flow1Facts.TRANSFER_SOURCE_POSTING_ID),
            destinationPostingId = PostingId(P807Q2Flow1Facts.TRANSFER_DESTINATION_POSTING_ID),
            feePostingId = PostingId("${P807Q2Flow1Facts.TRANSFER_TRANSACTION_ID}-fee"),
        )
    val result =
        handle.transferCommitPort.commitOnce(
            identity = ManualTransferRequestIdentity(ledgerId, RequestId("p807-device-transfer-request")),
            requestSnapshot = snapshot,
            createdAt = P807Q2Flow1Facts.TRANSFER_CREATED_AT,
        ) {
            ManualTransferTransactionFactory(catalog).create(
                snapshot,
                ConfirmedManualTransferCommitIds(
                    confirmationId = ConfirmationId("p807-device-transfer-confirmation"),
                    transferIds = ids,
                ),
            )
        }
    check(result is ConfirmedManualTransferResult.Created) { "the shared seed's transfer must be created, got $result" }
}

private fun p807Q2Flow1CommitRefundableExpense(
    handle: AndroidLedgerDatabaseHandle,
    ledgerId: LedgerId,
    catalog: LedgerCatalog,
    paymentAccountId: AccountId,
) {
    val amount = Money.ofMinor(P807Q2Flow1Facts.REFUNDABLE_EXPENSE_MINOR, CATALOG_MANAGED_CURRENCY)
    val snapshot =
        ManualExpenseRequestSnapshot(
            ledgerId = ledgerId,
            amount = amount,
            categoryId = CategoryId(DEFAULT_EXPENSE_LEAF_ID),
            paymentAccountId = paymentAccountId,
            occurredAt = P807Q2Flow1Facts.REFUNDABLE_EXPENSE_INSTANT,
            note = P807Q2Flow1Facts.REFUNDABLE_EXPENSE_NOTE,
        )
    val ids =
        ConfirmedManualExpenseCommitIds(
            confirmationId = ConfirmationId("p807-device-refundable-expense-confirmation"),
            expenseIds =
                AssetPaidOrdinaryExpenseIds(
                    transactionId = TransactionId(P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID),
                    versionId = TransactionVersionId("${P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID}-version"),
                    postingSetId = PostingSetId("${P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID}-posting-set"),
                    expensePostingId = PostingId("${P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID}-expense"),
                    paymentPostingId = PostingId("${P807Q2Flow1Facts.REFUNDABLE_EXPENSE_TRANSACTION_ID}-payment"),
                ),
        )
    val result =
        handle.commitPort.commitOnce(
            identity = ManualExpenseRequestIdentity(ledgerId, RequestId("p807-device-refundable-expense-request")),
            requestSnapshot = snapshot,
            createdAt = P807Q2Flow1Facts.REFUNDABLE_EXPENSE_CREATED_AT,
        ) {
            when (
                val created =
                    createAssetPaidOrdinaryExpense(
                        catalog = catalog,
                        command =
                            AssetPaidOrdinaryExpenseCommand(
                                ledgerId = ledgerId,
                                amount = amount,
                                categoryId = snapshot.categoryId,
                                paymentAccountId = paymentAccountId,
                                times = TransactionTimes.collapsed(snapshot.occurredAt),
                                note = snapshot.note,
                            ),
                        ids = ids.expenseIds,
                    )
            ) {
                is DomainResult.Success ->
                    DomainResult.Success(
                        ConfirmedManualExpenseCommit(
                            confirmationId = ids.confirmationId,
                            transaction = created.value,
                        ),
                    )
                is DomainResult.Failure -> created
            }
        }
    check(result is ConfirmedManualExpenseResult.Created) { "the shared seed's refundable expense must be created, got $result" }
}

private fun p807Q2Flow1IntakeTransferEvidence(
    handle: AndroidLedgerDatabaseHandle,
    ledgerId: LedgerId,
) {
    val intake =
        ExecuteImportIntake(
            commitPort = handle.importSpineStore,
            idSource =
                object : ImportIntakeIdSource {
                    override fun next(requiredDuplicateIds: Int): ImportIntakeIds {
                        require(requiredDuplicateIds == 0) { "the shared seed has no prior import sources to duplicate" }
                        return ImportIntakeIds(
                            sourceId = ImportSourceId(P807Q2Flow1Facts.SEEDED_SOURCE_ID),
                            evidenceId = ImportEvidenceId(P807Q2Flow1Facts.SEEDED_EVIDENCE_ID),
                            candidateId = ImportCandidateId(P807Q2Flow1Facts.SEEDED_CANDIDATE_ID),
                            statusHistoryId = ImportStatusHistoryId(P807Q2Flow1Facts.SEEDED_STATUS_ID),
                        )
                    }
                },
            fingerprint = ImportContentFingerprint(),
        )
    val result =
        intake.execute(
            ImportIntakeRequest(
                identity = ImportRequestIdentity(ledgerId, ImportRequestId("p807-device-import-request")),
                inputRef = P807Q2Flow1Facts.SEEDED_INPUT_REF,
                recordOrdinal = 0,
                recordKind = ImportRecordKind.TRANSFER_FLOW_SOURCE,
                facts =
                    ImportSourceFacts(
                        amountMinor = P807Q2Flow1Facts.TRANSFER_MINOR,
                        currencyCode = CATALOG_MANAGED_CURRENCY.code,
                        currencyPrecision = CATALOG_MANAGED_CURRENCY.precision,
                        occurredAt = P807Q2Flow1Facts.TRANSFER_INSTANT.toString(),
                        directionToken = "in",
                        statusToken = "settled",
                        fundingState = ImportFundingState.SETTLED,
                        fundingRuleId = IMPORT_FUNDING_RULE_LEGACY_SETTLED,
                        fundingRuleVersion = 1,
                    ),
                completeness = ImportCompleteness.VALID_COMPLETE,
                candidateGeneratedAt = P807Q2Flow1Facts.IMPORT_GENERATED_AT.toString(),
            ),
        )
    check(result is ImportIntakeResult.Accepted) { "the shared seed's evidence intake must be accepted, got $result" }
}

// ------------------------------------------------------------------ live-ledger read access

/** Reads through the launched app's ONE runtime owner (the lease-scoped production accessor). */
internal class P807Q2Flow1Ledger(
    private val context: Context,
) {
    fun <T> read(block: (P503LedgerFacade) -> T): T {
        val holder =
            checkNotNull(ledgerProcessHolderFor(context)) {
                "the launched app must expose the process-scoped runtime holder"
            }
        val scope = checkNotNull(holder.ledger) { "the launched runtime must have reached Ready" }
        return checkNotNull(scope.withFacade { facade, _ -> block(facade) }) {
            "the runtime lease could not be acquired"
        }
    }

    fun snapshot(): P807FormalSnapshot = read(::p807Q2Flow1ReadSnapshot)
}

// ------------------------------------------------------------------ self-entry workbooks

/**
 * One minimal six-part OOXML workbook shaped like the production template generator (the
 * `SelfEntryAndroidInstrumentedTest` synthetic builder's layout). The header row is always the
 * generator's own frozen [P807Q2Flow1Facts.SELF_ENTRY_HEADERS]; [rows] are the data rows, the
 * amount column carrying exact decimal text in `<v>` and every other cell a shared string (the
 * D-16.3 contract).
 */
internal fun p807Q2Flow1SelfEntryWorkbook(rows: List<List<String>>): ByteArray =
    p807Q2Flow1Xlsx(
        headerCells = P807Q2Flow1Facts.SELF_ENTRY_HEADERS,
        rows = rows,
    )

/** The wrong-header workbook: row 1 carries only eight of the frozen nine header cells. */
internal fun p807Q2Flow1WrongHeaderWorkbook(): ByteArray =
    p807Q2Flow1Xlsx(
        headerCells = P807Q2Flow1Facts.SELF_ENTRY_HEADERS.dropLast(1),
        rows = listOf(listOf("支出")),
    )

/** The correct one-row workbook the flow confirms. */
internal fun p807Q2Flow1CorrectWorkbook(): ByteArray =
    p807Q2Flow1SelfEntryWorkbook(
        listOf(
            listOf(
                "支出",
                P807Q2Flow1Facts.IMPORTED_OCCURRED_AT_TEXT,
                P807Q2Flow1Facts.IMPORTED_AMOUNT_TEXT,
                "",
                "餐饮",
                "早餐",
                "默认支付账户",
                "",
                "",
            ),
        ),
    )

private fun p807Q2Flow1Xlsx(
    headerCells: List<String>,
    rows: List<List<String>>,
): ByteArray {
    val sharedStrings = LinkedHashSet<String>()
    sharedStrings.addAll(headerCells)
    rows.forEach { sharedStrings.addAll(it) }
    val index = sharedStrings.withIndex().associate { (position, value) -> value to position }

    fun sharedCell(value: String): String = "<c t=\"s\"><v>${index.getValue(value)}</v></c>"

    val sheet = StringBuilder(XML_DECLARATION)
    sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
    sheet.append("<row r=\"1\">")
    headerCells.forEach { header -> sheet.append(sharedCell(header)) }
    sheet.append("</row>")
    rows.forEachIndexed { rowIndex, cells ->
        sheet.append("<row r=\"").append(rowIndex + 2).append("\">")
        cells.forEachIndexed { columnIndex, value ->
            if (columnIndex == AMOUNT_COLUMN) {
                sheet.append("<c><v>").append(value).append("</v></c>")
            } else {
                sheet.append(sharedCell(value))
            }
        }
        sheet.append("</row>")
    }
    sheet.append("</sheetData></worksheet>")

    val sharedStringsXml =
        StringBuilder(XML_DECLARATION)
            .append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
            .append(sharedStrings.size)
            .append("\" uniqueCount=\"")
            .append(sharedStrings.size)
            .append("\">")
            .apply { sharedStrings.forEach { item -> append("<si><t>").append(item).append("</t></si>") } }
            .append("</sst>")

    val parts =
        linkedMapOf(
            CONTENT_TYPES_PART to CONTENT_TYPES_XML,
            ROOT_RELS_PART to ROOT_RELS_XML,
            WORKBOOK_PART to WORKBOOK_XML,
            WORKBOOK_RELS_PART to WORKBOOK_RELS_XML,
            SHARED_STRINGS_PART to sharedStringsXml.toString(),
            SHEET_PART to sheet.toString(),
        )
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        parts.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

// ------------------------------------------------------------------ Downloads fixtures

/** Inserts fixture files into the platform Download collection so the system picker can open them. */
internal class P807Q2Flow1Downloads(
    private val resolver: ContentResolver,
) {
    private val inserted = mutableListOf<Uri>()

    fun insert(
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "the fixture file [$name] must be insertable into the Download collection"
            }
        try {
            val stream: OutputStream =
                checkNotNull(resolver.openOutputStream(uri, "w")) { "the fixture file [$name] must open for write" }
            stream.use { it.write(bytes) }
            val published = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) { "the fixture file [$name] must be published" }
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        inserted += uri
        return uri
    }

    fun deleteAll() {
        inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        inserted.clear()
    }
}

// ------------------------------------------------------------------ shell + exported-file read

/** One shell command through the instrumentation's shell identity (`UiAutomation.executeShellCommand`). */
internal fun p807Q2Flow1Shell(
    automation: UiAutomation,
    command: String,
): ByteArray {
    val descriptor = automation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}

/**
 * The newest readable export file the platform wrote into the Download/Documents roots, read as
 * bytes. The suggested name is the product's `unifiedledger-export-<yyyyMMdd>.xlsx`; a repeated
 * run appends a platform counter, so the pattern accepts both.
 */
internal fun p807Q2Flow1NewestExportFile(
    automation: UiAutomation,
    log: (String) -> Unit,
): ByteArray {
    val directories = listOf("/sdcard/Download", "/sdcard/Documents")
    val listing =
        directories
            .map { directory -> String(p807Q2Flow1Shell(automation, "ls -t $directory 2>/dev/null"), Charsets.UTF_8) }
            .joinToString("\n")
    val names = listing.lines().map { it.trim() }.filter { EXPORT_FILE_NAME.matches(it) }
    check(names.isNotEmpty()) {
        "no readable-export file was found under $directories; listing=[$listing]"
    }
    for (name in names) {
        for (directory in directories) {
            val quoted = "'$directory/$name'"
            val probe = String(p807Q2Flow1Shell(automation, "ls $quoted 2>/dev/null"), Charsets.UTF_8).trim()
            if (probe == "$directory/$name") {
                val bytes = p807Q2Flow1Shell(automation, "cat $quoted")
                log("${P807Q2Flow1Facts.PREFIX} exportFile path=$directory/$name bytes=${bytes.size}")
                check(bytes.isNotEmpty()) { "the exported file [$directory/$name] read back empty" }
                return bytes
            }
        }
    }
    error("no readable exported file was found; listing=[$listing]")
}

// ------------------------------------------------------------------ the UI driver

/**
 * The flow's UI driver: the real app through [AndroidScaleUi] (the launch/drawer/scroll/click
 * discipline the scale suite already uses) plus the platform SAF picker windows. Every step is an
 * accessibility action or an injected gesture on a real node; no application service stands in for
 * a user action.
 */
internal class P807Q2Flow1Ui(
    instrumentation: Instrumentation,
    private val log: (String) -> Unit,
) {
    val ui = AndroidScaleUi(instrumentation) {}

    private val automation: UiAutomation = instrumentation.uiAutomation

    // ---- app surface

    fun launch() {
        ui.launch()
    }

    fun awaitHomeCapsule(timeoutMillis: Long = HOME_TIMEOUT_MILLIS): Boolean = runCatching { ui.await(timeoutMillis) { ui.has(P807Q2Flow1Facts.HOME_SEARCH_CAPSULE) } }.isSuccess

    fun openImport() {
        ui.openImportViaDrawer()
        awaitAppText(P807Q2Flow1Facts.IMPORT_SCREEN_MARKER)
    }

    fun openDrawerRow(rowText: String) {
        ui.click(P807Q2Flow1Facts.DRAWER_OPEN_DESCRIPTION)
        ui.click(rowText)
    }

    fun awaitAppText(
        textPrefix: String,
        timeoutMillis: Long = NODE_TIMEOUT_MILLIS,
    ) {
        ui.await(timeoutMillis) { ui.has(textPrefix, prefix = true) }
    }

    fun hasAppText(textPrefix: String): Boolean = ui.has(textPrefix, prefix = true)

    fun clickApp(text: String) {
        ui.click(text)
    }

    fun clickAppDescription(description: String) {
        ui.click(description)
    }

    /**
     * Scrolls the current surface forward until [textPrefix] is visible. A transition between two
     * screens can momentarily expose no scrollable container (`seek`'s own loud failure); the
     * bounded retry keeps that transient state from reading as a missing product affordance, and a
     * target that never appears still fails loudly on the final attempt.
     */
    fun seekApp(
        textPrefix: String,
        maxScrolls: Int = 40,
    ) {
        if (ui.has(textPrefix, prefix = true)) return
        repeat(SEEK_ATTEMPTS) {
            if (runCatching { ui.seek(textPrefix, forward = true, maxScrolls = maxScrolls) }.isSuccess) return
            SystemClock.sleep(1_000)
        }
        ui.seek(textPrefix, forward = true, maxScrolls = maxScrolls)
    }

    fun settle() {
        ui.settle()
    }

    /** Scrolls the import surface back to its title bar (the format row lives directly below it). */
    fun backToImportTop() {
        repeat(SEEK_ATTEMPTS) {
            if (runCatching { ui.seekBackToTop("刷新清单", maxSteps = 12) }.isSuccess) return
            SystemClock.sleep(1_000)
        }
        ui.seekBackToTop("刷新清单", maxSteps = 12)
    }

    /** Scrolls the current surface back until [marker] is visible again (a top-anchored affordance). */
    fun scrollBackTo(marker: String) {
        repeat(SEEK_ATTEMPTS) {
            if (runCatching { ui.seekBackToTop(marker, maxSteps = 24) }.isSuccess) return
            SystemClock.sleep(1_000)
        }
        ui.seekBackToTop(marker, maxSteps = 24)
    }

    /** Leaves the current in-app surface through the system back channel (the drawer surfaces' exit). */
    fun backToHome() {
        back()
    }

    fun back() {
        check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            "the system back action was refused"
        }
        SystemClock.sleep(800)
    }

    fun appNodes(): List<AccessibilityNodeInfo> = ui.nodes(ui.root()).filter { it.isVisibleToUser }

    fun findAppNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = appNodes().firstOrNull(predicate)

    fun tap(node: AccessibilityNodeInfo) {
        ui.clickNode(node)
    }

    fun textOf(node: AccessibilityNodeInfo): String = node.text?.toString().orEmpty()

    fun descriptionOf(node: AccessibilityNodeInfo): String = node.contentDescription?.toString().orEmpty()

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    // ---- import format row + picker

    /** Clicks the 选择文件 button of the self-entry format row (the row is located by its product label). */
    fun pickSelfEntryFormat(): Boolean {
        val formatName = P807Q2Flow1Facts.SELF_ENTRY_FORMAT.displayName
        repeat(2) {
            runCatching { seekApp(formatName) }
            val format = findAppNode { textOf(it) == formatName }
            val choices = appNodes().filter { textOf(it) == P807Q2Flow1Facts.PICK_FILE_BUTTON }
            val choice =
                if (format != null) {
                    choices.minByOrNull { kotlin.math.abs(bounds(it).centerY() - bounds(format).centerY()) }
                } else {
                    choices.firstOrNull()
                }
            if (choice != null) {
                tap(choice)
                return true
            }
            settle()
        }
        return false
    }

    /**
     * Drives the REAL system SAF picker to the given fixture name: waits for a documentsui window,
     * clicks the row whose text contains the file name, and — when the picker opens on a collection
     * that does not show it — navigates to the Download root first (the AndroidScaleUi precedent,
     * with both the Chinese and English platform labels).
     */
    fun pickImportFile(
        fileName: String,
        timeoutMillis: Long = PICKER_TIMEOUT_MILLIS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var navigationSteps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val roots = pickerRoots()
            val nodes = roots.flatMap(::collectNodes).filter { it.isVisibleToUser }
            val file = nodes.firstOrNull { textOf(it).contains(fileName) || descriptionOf(it).contains(fileName) }
            if (file != null) {
                tap(file)
                log("${P807Q2Flow1Facts.PREFIX} picker file=$fileName windows=${roots.size}")
                return true
            }
            if (navigationSteps < PICKER_NAVIGATION_STEPS) {
                val downloads = nodes.firstOrNull { textOf(it) in DOWNLOAD_ROOT_LABELS || descriptionOf(it) in DOWNLOAD_ROOT_LABELS }
                val rootsButton = nodes.firstOrNull { descriptionOf(it) in ROOTS_LABELS }
                when {
                    downloads != null -> {
                        tap(downloads)
                        navigationSteps++
                        SystemClock.sleep(800)
                    }
                    rootsButton != null -> {
                        tap(rootsButton)
                        navigationSteps++
                        SystemClock.sleep(800)
                    }
                    else -> SystemClock.sleep(POLL_MILLIS)
                }
            } else {
                SystemClock.sleep(POLL_MILLIS)
            }
        }
        log("${P807Q2Flow1Facts.PREFIX} picker file=$fileName found=false windows=${describePickerWindows()}")
        return false
    }

    /**
     * Confirms the readable export's CreateDocument picker: clicks the platform Save action in the
     * documentsui window, navigating to the Download root when the picker opens elsewhere. This is
     * the one system-dialog step with no in-repo precedent (see the file note); the await is
     * bounded and the failure text names the observed picker windows.
     */
    fun saveExportFile(timeoutMillis: Long = PICKER_TIMEOUT_MILLIS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var navigationSteps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val roots = pickerRoots()
            val nodes = roots.flatMap(::collectNodes).filter { it.isVisibleToUser }
            val save = nodes.firstOrNull { textOf(it) in SAVE_LABELS || descriptionOf(it) in SAVE_LABELS }
            if (save != null) {
                tap(save)
                log("${P807Q2Flow1Facts.PREFIX} export picker save windows=${roots.size} labels=${nodes.map { textOf(it) }.filter { it.isNotBlank() }.take(20)}")
                return true
            }
            if (navigationSteps < PICKER_NAVIGATION_STEPS) {
                val downloads = nodes.firstOrNull { textOf(it) in DOWNLOAD_ROOT_LABELS || descriptionOf(it) in DOWNLOAD_ROOT_LABELS }
                val rootsButton = nodes.firstOrNull { descriptionOf(it) in ROOTS_LABELS }
                when {
                    downloads != null -> {
                        tap(downloads)
                        navigationSteps++
                        SystemClock.sleep(800)
                    }
                    rootsButton != null -> {
                        tap(rootsButton)
                        navigationSteps++
                        SystemClock.sleep(800)
                    }
                    else -> SystemClock.sleep(POLL_MILLIS)
                }
            } else {
                SystemClock.sleep(POLL_MILLIS)
            }
        }
        log("${P807Q2Flow1Facts.PREFIX} export picker save found=false windows=${describePickerWindows()}")
        return false
    }

    // ---- text fields (the search condition area)

    /** How many editable fields the current screen exposes (the search form's keyword/start/end). */
    fun editableFieldCount(): Int = editableNodes().size

    /**
     * Sets one editable field's text through the accessibility SetText action. [fromBottom] counts
     * from the LAST field upward (1 = the last field), which addresses the two custom-window fields
     * without depending on the keyword field's position.
     */
    fun setFieldText(
        fromBottom: Int,
        text: String,
    ): Boolean {
        val fields = editableNodes()
        val index = fields.size - fromBottom
        if (index < 0 || index >= fields.size) {
            log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom fields=${fields.size} accepted=false")
            return false
        }
        val node = fields[index]
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom fields=${fields.size} accepted=$accepted")
        SystemClock.sleep(400)
        return accepted
    }

    private fun editableNodes(): List<AccessibilityNodeInfo> =
        appNodes()
            .filter { it.isEditable || it.className?.toString()?.contains("EditText") == true }
            .sortedBy { bounds(it).top }

    // ---- picker windows

    private fun pickerRoots(): List<AccessibilityNodeInfo> =
        runCatching { automation.windows }
            .getOrNull()
            .orEmpty()
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
            .filter { root -> root.packageName?.toString()?.contains("documentsui") == true }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result
    }

    private fun describePickerWindows(): String =
        runCatching { automation.windows }
            .getOrNull()
            .orEmpty()
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
            .joinToString(" | ") { root -> "pkg=${root.packageName}" }

    companion object {
        const val NODE_TIMEOUT_MILLIS = 60_000L
        const val HOME_TIMEOUT_MILLIS = 120_000L
        const val PICKER_TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 500L
        const val MAX_TREE_NODES = 2_000
        const val PICKER_NAVIGATION_STEPS = 6
        const val SEEK_ATTEMPTS = 2

        val DOWNLOAD_ROOT_LABELS = listOf("下载", "Downloads")
        val ROOTS_LABELS = listOf("显示根目录", "Show roots", "打开导航抽屉", "Open navigation drawer")
        val SAVE_LABELS = listOf("保存", "Save", "SAVE")
    }
}

// ------------------------------------------------------------------ process + storage guards

/**
 * The process must hold no handle on the app's ledger host directory: the seed replaces that
 * storage, and a `MainActivity` from an earlier case in the same process would leave the product
 * writing to the old inode (the ImportReviewPaging guard, mirrored).
 */
internal fun p807Q2Flow1RequireNoOpenLedgerHandle(
    hostDirectory: String,
    log: (String) -> Unit,
) {
    val hostPrefix = File(hostDirectory).canonicalPath + File.separator
    val open =
        File("/proc/self/fd")
            .listFiles()
            .orEmpty()
            .mapNotNull { descriptor -> runCatching { descriptor.canonicalPath }.getOrNull() }
            .filter { path -> path.startsWith(hostPrefix) && (path.contains("ledger.db") || path.contains("ledger-generations")) }
    check(open.isEmpty()) {
        "this process already holds the ledger database open ($open); the P8-07 Q2 flow must be the first case in its " +
            "shard process, because it seeds the app's own stable storage before launching the app"
    }
    log("${P807Q2Flow1Facts.PREFIX} guard openLedgerHandles=0")
}

/** Removes the app's own stable-storage state so this run starts from the shared seed. */
internal fun p807Q2Flow1ResetLedgerStorage(
    context: Context,
    layout: LedgerStorageLayout,
    log: (String) -> Unit,
) {
    File(layout.generationsDirectory).deleteRecursively()
    File(layout.activePointerFile).delete()
    File(layout.switchJournalFile).delete()
    val legacy = context.getDatabasePath("ledger.db")
    listOf(legacy, File(legacy.path + "-wal"), File(legacy.path + "-shm"), File(legacy.path + "-journal")).forEach { file ->
        if (file.exists()) file.delete()
    }
    log(
        "${P807Q2Flow1Facts.PREFIX} reset host=${layout.hostDirectory} " +
            "pointerPresent=${File(layout.activePointerFile).exists()} generationsPresent=${File(layout.generationsDirectory).exists()}",
    )
}

// ------------------------------------------------------------------ workbooks: frozen OOXML parts

private const val AMOUNT_COLUMN = 2
private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val CONTENT_TYPES_PART = "[Content_Types].xml"
private const val ROOT_RELS_PART = "_rels/.rels"
private const val WORKBOOK_PART = "xl/workbook.xml"
private const val WORKBOOK_RELS_PART = "xl/_rels/workbook.xml.rels"
private const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"
private const val SHEET_PART = "xl/worksheets/sheet1.xml"

private const val CONTENT_TYPES_XML =
    XML_DECLARATION +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
        "</Types>"

private const val ROOT_RELS_XML =
    XML_DECLARATION +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

private const val WORKBOOK_XML =
    XML_DECLARATION +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

private const val WORKBOOK_RELS_XML =
    XML_DECLARATION +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
        "</Relationships>"

/** The product's suggested export name shape, accepting the platform's duplicate counter. */
private val EXPORT_FILE_NAME = Regex("unifiedledger-export-[0-9]{8}( \\([0-9]+\\))?\\.xlsx")
