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
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import com.unifiedledger.application.import.selfentry.SelfEntrySourceTokens
import com.unifiedledger.application.import.selfentry.SelfEntryTemplateGenerator
import com.unifiedledger.data.AndroidLedgerDatabaseHandle
import com.unifiedledger.data.createAndroidLedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AccountTransferIds
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseCommand
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
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

    /** The shared seed's pending import candidate amount; the confirmation page must never carry it. */
    const val SEED_CANDIDATE_AMOUNT_MINOR = TRANSFER_MINOR
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

    /** The detail-only selection-row copy; the checkbox is the single 勾选候选 node in its Row. */
    const val CANDIDATE_SELECTION_HINT = "勾选进入批量确认（须先补齐决策）"

    /** The imported self-entry row's structured kind (the seed candidate is transfer_flow). */
    const val CANDIDATE_IMPORTED_META_PREFIX = "类型 ordinary_flow"

    /**
     * The self-entry row names 一级分类 餐饮 + 二级分类 早餐, so the D-18/D-19 basis carries the full
     * path as its source text and resolves it through D-024's path disambiguation: the rendered
     * category line is `分类：餐饮/早餐 → 早餐（路径消歧）`.
     */
    const val CANDIDATE_MATCH_BASIS_CATEGORY = "分类：餐饮/早餐 → 早餐（路径消歧）"
    const val CANDIDATE_MATCH_BASIS_ACCOUNT = "资金账户：默认支付账户 → 默认支付账户"
    const val CANDIDATE_BATCH_ENTRY = "进入批量确认"
    const val BATCH_CONFIRM_TITLE = "批量确认"
    const val BATCH_BACK_BUTTON = "返回"
    const val REVIEW_SURFACE_MARKER = "最近导入："
    const val BATCH_AUTHORIZE_BUTTON = "确认入账（1 项）"
    const val BATCH_RESULT_PREFIX = "最近批量结果（授权时间 "
    const val RESOLVED_GROUP_ONE = "已确认 / 已拒绝（1）"
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
                TransactionDetailResult.Unavailable -> error("transaction ${row.transactionId.value} read as Unavailable")
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
        TransactionDetailResult.Unavailable -> error("the added transaction read as Unavailable")
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
                // 币种 carries the frozen CNY token, NOT blank. A blank cell raises
                // REQUIRED_FACT_UNRESOLVED(currency) (SelfEntryXlsxParser), which demotes the record
                // to VALID_INCOMPLETE; the spine then persists the candidate status as "incomplete"
                // (pending_confirmation requires VALID_COMPLETE + funding SETTLED), the review
                // surfaces 来源事实不完整——不可确认, and the row is never selectable. The
                // generator's own EXAMPLE_ROWS leave this cell blank, so the token is taken from
                // SelfEntrySourceTokens rather than from the examples.
                SelfEntrySourceTokens.CURRENCY_CNY,
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
    private val instrumentation: Instrumentation,
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
        try {
            ui.await(timeoutMillis) { ui.has(textPrefix, prefix = true) }
        } catch (failure: IllegalStateException) {
            val dump = dumpUiSnapshot("awaitAppText:$textPrefix")
            throw IllegalStateException(
                "awaitAppText('$textPrefix') missed its ${timeoutMillis}ms deadline; ${summarizeDump(dump)}",
                failure,
            )
        }
    }

    fun hasAppText(textPrefix: String): Boolean = ui.has(textPrefix, prefix = true)

    /**
     * Leaves the batch confirmation surface back to the import review surface. The authorize does
     * not reliably bounce the client back on its own (the run that failed here still showed the
     * authorization page's 返回 + 确认入账（1 项） after the ledger had already committed), so the
     * return is explicit and bounded: a visible 返回 is clicked, a bare 批量确认 title falls back to
     * the system back action, and the review surface's own record marker is awaited after each
     * attempt (either landing — the review top or the list — renders 最近导入).
     */
    fun returnToReviewSurface(timeoutMillis: Long = NODE_TIMEOUT_MILLIS): Boolean {
        if (ui.has(P807Q2Flow1Facts.REVIEW_SURFACE_MARKER, prefix = true)) return true
        repeat(2) { attempt ->
            val backButton = appNodes().firstOrNull { textOf(it) == P807Q2Flow1Facts.BATCH_BACK_BUTTON }
            if (backButton != null) {
                log("${P807Q2Flow1Facts.PREFIX} batch page ${P807Q2Flow1Facts.BATCH_BACK_BUTTON} attempt=$attempt ${describeNode(backButton)}")
                tap(backButton)
            } else if (ui.has(P807Q2Flow1Facts.BATCH_CONFIRM_TITLE, prefix = true)) {
                log("${P807Q2Flow1Facts.PREFIX} batch page without a ${P807Q2Flow1Facts.BATCH_BACK_BUTTON} node, system back attempt=$attempt")
                back()
            }
            if (runCatching { ui.await(timeoutMillis) { ui.has(P807Q2Flow1Facts.REVIEW_SURFACE_MARKER, prefix = true) } }.isSuccess) {
                log("${P807Q2Flow1Facts.PREFIX} review surface reached attempt=$attempt")
                return true
            }
            settle()
        }
        log("${P807Q2Flow1Facts.PREFIX} review surface never reached; surface=${surfaceTitle()}")
        dumpUiSnapshot("returnToReviewSurface")
        return false
    }

    /** The visible lead texts of the current surface (the surface title's evidence on a miss). */
    fun surfaceTitle(): String =
        appNodes()
            .map { textOf(it) }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString(" ¦ ")

    fun clickApp(text: String) {
        ui.click(text)
    }

    fun clickAppDescription(description: String) {
        ui.click(description)
    }

    /**
     * Establishes the selection of THIS detail's candidate. The click target is NOT the window's
     * first 勾选候选 node: the review list can stay in the tree behind the detail, and its first
     * selectable row is the seed's 12.34 transfer_flow candidate — clicking that by description is
     * how an earlier round confirmed the wrong candidate (run 23:35: dump: 12.34 moved to
     * 已确认 / 已拒绝, the imported 7.77 still 待确认). The checkbox is instead anchored
     * STRUCTURALLY on the detail-only hint copy 「勾选进入批量确认（须先补齐决策）」: both sit in
     * the same Row, so the checkbox is the single 勾选候选 node inside the hint's nearest ancestor
     * that contains exactly one. The postcondition is the detail's own entry (exact text
     * 进入批量确认), not the checkbox's read-back (D-338).
     */
    fun selectImportedCandidateForBatch(): Boolean {
        if (findDetailBatchEntry() != null) {
            log("${P807Q2Flow1Facts.PREFIX} selection already established (the detail's batch entry is present)")
            return true
        }
        repeat(SELECTION_CLICK_ATTEMPTS) { attempt ->
            val hint = appNodes().firstOrNull { textOf(it) == P807Q2Flow1Facts.CANDIDATE_SELECTION_HINT }
            val checkbox = hint?.let(::selectionCheckboxAnchoredOn)
            if (hint == null || checkbox == null) {
                log("${P807Q2Flow1Facts.PREFIX} detail selection row absent attempt=$attempt hint=${hint != null}")
                settle()
                return@repeat
            }
            log("${P807Q2Flow1Facts.PREFIX} detail checkbox attempt=$attempt ${describeNode(checkbox)} hint=${bounds(hint)}")
            tap(checkbox)
            if (runCatching { ui.await(PICKER_RETURN_TIMEOUT_MILLIS) { findDetailBatchEntry() != null } }.isSuccess) {
                log("${P807Q2Flow1Facts.PREFIX} detail selection established attempt=$attempt")
                return true
            }
        }
        log("${P807Q2Flow1Facts.PREFIX} detail selection never established")
        dumpUiSnapshot("selectImportedCandidateForBatch")
        return false
    }

    /**
     * The checkbox of the detail's selection Row: structurally the single 勾选候选 node inside the
     * [anchor]'s nearest ancestor that holds any, else the one in that ancestor sitting on the
     * anchor's own band and to its left (the Row's checkbox-then-copy order). The detail-only
     * anchor keeps the list's checkboxes out of the choice.
     */
    private fun selectionCheckboxAnchoredOn(anchor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val band = bounds(anchor)
        var current: AccessibilityNodeInfo? = anchor.parent
        repeat(5) {
            val container = current ?: return null
            val boxes = collectNodes(container).filter { descriptionOf(it) == P807Q2Flow1Facts.CANDIDATE_CHECKBOX_DESCRIPTION }
            if (boxes.size == 1) return boxes.single()
            if (boxes.isNotEmpty()) {
                return boxes
                    .filter { box ->
                        val boxBounds = bounds(box)
                        boxBounds.centerY() in band.top..band.bottom && boxBounds.right <= band.left
                    }.minByOrNull { box -> kotlin.math.abs(bounds(box).centerY() - band.centerY()) }
            }
            current = container.parent
        }
        return null
    }

    /** The detail's own entry: its visible text is exactly the token (the list entry appends a count). */
    private fun findDetailBatchEntry(): AccessibilityNodeInfo? = appNodes().firstOrNull { node -> textOf(node) == P807Q2Flow1Facts.CANDIDATE_BATCH_ENTRY }

    /**
     * Clicks the detail's batch entry by its EXACT text — never a prefix, and not the list-side
     * entry (whose text carries 「（N 项）」 while its contentDescription shares the token). The
     * node is read back and logged before the click, then the batch page is awaited.
     */
    fun enterBatchConfirmation(timeoutMillis: Long = NODE_TIMEOUT_MILLIS): Boolean {
        val appeared = runCatching { ui.await(timeoutMillis) { findDetailBatchEntry() != null } }.isSuccess
        val entry = findDetailBatchEntry()
        if (!appeared || entry == null) {
            log("${P807Q2Flow1Facts.PREFIX} detail batch entry absent windows=${describePickerWindows()}")
            dumpUiSnapshot("enterBatchConfirmation:absent")
            return false
        }
        log("${P807Q2Flow1Facts.PREFIX} detail batch entry ${describeNode(entry)}")
        tap(entry)
        val opened = runCatching { ui.await(timeoutMillis) { ui.has(P807Q2Flow1Facts.BATCH_CONFIRM_TITLE, prefix = true) } }.isSuccess
        if (!opened) {
            log("${P807Q2Flow1Facts.PREFIX} batch page never opened windows=${describePickerWindows()}")
            dumpUiSnapshot("enterBatchConfirmation:page")
        }
        return opened
    }

    /**
     * Reaches a target that may sit below the fold, find-or-scroll: `ui.seek` itself looks before
     * it scrolls, so the list path is unchanged. Only when that fails do we classify the miss — a
     * surface with a scrollable container gets one final loud seek; a short surface (the candidate
     * detail) gets one bounded render wait and then fails with the window dump. The old shape
     * scrolled unconditionally and died with "scroll container absent" before the target was ever
     * looked for; probing the container up front instead would let a stale tree report a false
     * "no container" on a scrollable review list.
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
        if (runCatching { ui.scrollable() }.isSuccess) {
            withUiDump("seekApp:$textPrefix") { ui.seek(textPrefix, forward = true, maxScrolls = maxScrolls) }
            return
        }
        if (runCatching { ui.await(SEEK_SHORT_SURFACE_TIMEOUT_MILLIS) { ui.has(textPrefix, prefix = true) } }.isSuccess) return
        withUiDump("seekApp:$textPrefix") {
            error("seekApp('$textPrefix'): the target is absent and the current surface has no scrollable container")
        }
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
        withUiDump("backToImportTop") { ui.seekBackToTop("刷新清单", maxSteps = 12) }
    }

    /** Scrolls the current surface back until [marker] is visible again (a top-anchored affordance). */
    fun scrollBackTo(marker: String) {
        repeat(SEEK_ATTEMPTS) {
            if (runCatching { ui.seekBackToTop(marker, maxSteps = 24) }.isSuccess) return
            SystemClock.sleep(1_000)
        }
        withUiDump("scrollBackTo:$marker") { ui.seekBackToTop(marker, maxSteps = 24) }
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

    /** Clicks the 选择文件 button of the self-entry format row and waits for the picker window. */
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
                // The click only staged the platform pick; the REQUIRED effect is the picker
                // window taking the foreground. Waiting for it turns a refused click into a
                // bounded false instead of a 60s downstream text timeout with no cause.
                val pickerShown = awaitPickerWindow(PICKER_WINDOW_TIMEOUT_MILLIS)
                log("${P807Q2Flow1Facts.PREFIX} formatPick name=$formatName pickerShown=$pickerShown windows=${describePickerWindows()}")
                return pickerShown
            }
            settle()
        }
        return false
    }

    /**
     * Drives the REAL system SAF picker to the given fixture file and waits until the pick has
     * actually RETURNED (the picker released the foreground to the app).
     *
     * THE CLICK TARGET MATTERS (run 22:27:27 failure). The row is matched by EXACT text, as
     * `AndroidScaleUi.selectSafFixtureOnce` does. A substring/contentDescription match could land
     * on the document's thumbnail, and a tap inside DocumentsUI's preview region runs
     * `QuickViewIntentBuilder` and starts an ACTION_VIEW: the picker stays foreground, no result is
     * delivered, the app never resumes, and the next text await dies 60s later with no cause. The
     * exact-text title node is outside that preview region, so the click is a regular pick.
     */
    fun pickImportFile(
        fileName: String,
        timeoutMillis: Long = PICKER_TIMEOUT_MILLIS,
    ): Boolean {
        if (!awaitPickerWindow(PICKER_WINDOW_TIMEOUT_MILLIS)) {
            log("${P807Q2Flow1Facts.PREFIX} picker window absent file=$fileName windows=${describePickerWindows()}")
            dumpUiSnapshot("pickImportFile:window:$fileName")
            return false
        }
        repeat(PICKER_CLICK_ATTEMPTS) { attempt ->
            val row = awaitPickerRow(fileName, timeoutMillis)
            if (row == null) {
                log("${P807Q2Flow1Facts.PREFIX} picker row absent file=$fileName attempt=$attempt windows=${describePickerWindows()}")
                dumpUiSnapshot("pickImportFile:row:$fileName")
                return false
            }
            log("${P807Q2Flow1Facts.PREFIX} picker row file=$fileName attempt=$attempt ${describeNode(row)}")
            tap(row)
            if (awaitPickerReturned(PICKER_RETURN_TIMEOUT_MILLIS)) {
                log("${P807Q2Flow1Facts.PREFIX} picker returned file=$fileName attempt=$attempt")
                return true
            }
            log(
                "${P807Q2Flow1Facts.PREFIX} picker did not return file=$fileName attempt=$attempt " +
                    "pickerForeground=${pickerForeground()} appForeground=${appForeground()} " +
                    "windows=${describePickerWindows()}",
            )
            // A viewer/quick-view can take the foreground instead of the pick: back out to the
            // picker (bounded) before the retry, so the retry clicks the picker it can see.
            if (!pickerForeground()) {
                runCatching { automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                awaitPickerForeground(PICKER_WINDOW_TIMEOUT_MILLIS)
            }
        }
        dumpUiSnapshot("pickImportFile:return:$fileName")
        return false
    }

    /**
     * Confirms the readable export's CreateDocument picker: clicks the platform Save action in the
     * documentsui window and waits until the picker hands the foreground back. This is the one
     * system-dialog step with no in-repo precedent (see the file note); the waits are bounded and a
     * failure dumps the whole window picture instead of only a label list.
     */
    fun saveExportFile(timeoutMillis: Long = PICKER_TIMEOUT_MILLIS): Boolean {
        if (!awaitPickerWindow(PICKER_WINDOW_TIMEOUT_MILLIS)) {
            log("${P807Q2Flow1Facts.PREFIX} export picker window absent windows=${describePickerWindows()}")
            dumpUiSnapshot("saveExportFile:window")
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var navigationSteps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val nodes = pickerRoots().flatMap(::collectNodes).filter { it.isVisibleToUser }
            val save = nodes.firstOrNull { textOf(it) in SAVE_LABELS || descriptionOf(it) in SAVE_LABELS }
            if (save != null) {
                log(
                    "${P807Q2Flow1Facts.PREFIX} export picker save ${describeNode(save)} " +
                        "labels=${nodes.map { textOf(it) }.filter { it.isNotBlank() }.take(20)}",
                )
                tap(save)
                if (awaitPickerReturned(PICKER_RETURN_TIMEOUT_MILLIS)) {
                    log("${P807Q2Flow1Facts.PREFIX} export picker returned")
                    return true
                }
                log("${P807Q2Flow1Facts.PREFIX} export picker still foreground windows=${describePickerWindows()}")
                dumpUiSnapshot("saveExportFile:return")
                return false
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
        dumpUiSnapshot("saveExportFile:save")
        return false
    }

    // ---- text fields (the search condition area)

    /** How many editable fields the current screen exposes (the search form's keyword/start/end). */
    fun editableFieldCount(): Int = editableNodes().size

    /**
     * Sets one editable field's text and VERIFIES the value landed. On these Compose fields
     * ACTION_SET_TEXT can be accepted without writing anything (observed accepted=true /
     * landed=false), so the path escalates inside the same attempt: focus + SetText, then
     * tap-to-focus, clear and inject the characters as key events; only a read-back of the value
     * counts. Every attempt logs the field's real node shape. [fromBottom] counts from the LAST
     * field upward (1 = the last field), which addresses the two custom-window fields without
     * depending on the keyword field's position.
     */
    fun setFieldText(
        fromBottom: Int,
        text: String,
    ): Boolean {
        repeat(FIELD_INPUT_ATTEMPTS) { attempt ->
            val fields = editableNodes()
            val index = fields.size - fromBottom
            if (index < 0 || index >= fields.size) {
                log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom fields=${fields.size} index=$index attempt=$attempt absent")
                return false
            }
            val node = fields[index]
            log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom index=$index fields=${fields.size} attempt=$attempt ${describeField(node)}")
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (awaitFieldValue(text, FIELD_SETTLE_TIMEOUT_MILLIS)) {
                log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom accepted=$accepted landed=true via=action attempt=$attempt value=$text")
                return true
            }
            // The Compose field can accept ACTION_SET_TEXT without writing the value (observed:
            // accepted=true, landed=false), so the retry drives the editor the way a user does:
            // tap for focus, clear what is there, then inject the characters as key events.
            log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom accepted=$accepted landed=false via=action attempt=$attempt; typing into the field")
            tap(node)
            SystemClock.sleep(POLL_MILLIS)
            clearFocusedField(textOf(node).length)
            val typed = typeTextEvents(text)
            log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom typed=$typed attempt=$attempt value=$text")
            if (awaitFieldValue(text, FIELD_SETTLE_TIMEOUT_MILLIS)) {
                log("${P807Q2Flow1Facts.PREFIX} setField fromBottom=$fromBottom landed=true via=keys attempt=$attempt value=$text")
                return true
            }
        }
        dumpUiSnapshot("setField:$text")
        return false
    }

    /** The three facts the input path needs: what the node is, what it shows, and what it accepts. */
    private fun describeField(node: AccessibilityNodeInfo): String =
        "class=${node.className} editable=${node.isEditable} focusable=${node.isFocusable} focused=${node.isFocused} " +
            "clickable=${node.isClickable} text=[${textOf(node)}] hint=[${node.hintText}] bounds=${bounds(node)} " +
            "actions=${node.actionList.map { action -> action.id }}"

    /** Empties the focused editor: move the caret to the end, then delete [length] characters. */
    private fun clearFocusedField(length: Int) {
        if (length <= 0) return
        runCatching { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END) }
        repeat(length) { runCatching { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) } }
    }

    /** Injects [text] as hardware key events (the platform's virtual keyboard map); no IME needed. */
    private fun typeTextEvents(text: String): Boolean =
        runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(text.toCharArray())
                .forEach { event -> instrumentation.sendKeySync(event) }
            true
        }.getOrDefault(false)

    /** Hides the soft keyboard when it owns the active window (the platform BACK hide gesture). */
    fun hideKeyboardIfShown() {
        val imeShown =
            runCatching {
                automation.windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.isActive }
            }.getOrDefault(false)
        if (!imeShown) return
        runCatching { automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        SystemClock.sleep(POLL_MILLIS)
        log("${P807Q2Flow1Facts.PREFIX} keyboard hidden")
    }

    /** Waits until ANY editable field carries [expected] (the action's observable effect). */
    private fun awaitFieldValue(
        expected: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (editableNodes().any { textOf(it) == expected }) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun editableNodes(): List<AccessibilityNodeInfo> =
        appNodes()
            .filter { it.isEditable || it.className?.toString()?.contains("EditText") == true }
            .sortedBy { bounds(it).top }

    // ---- picker windows

    /** Waits until a documentsui window exists OR the app lost the foreground (the picker took over). */
    private fun awaitPickerWindow(timeoutMillis: Long): Boolean = runCatching { ui.await(timeoutMillis) { pickerRoots().isNotEmpty() || !appForeground() } }.isSuccess

    /** Waits until the picker released the foreground AND the app owns the active window again. */
    private fun awaitPickerReturned(timeoutMillis: Long): Boolean = runCatching { ui.await(timeoutMillis) { !pickerForeground() && appForeground() } }.isSuccess

    /** Waits until a documentsui window owns the active window again (after backing out of a viewer). */
    private fun awaitPickerForeground(timeoutMillis: Long): Boolean = runCatching { ui.await(timeoutMillis) { pickerForeground() } }.isSuccess

    /**
     * Waits for the picker's fixture row (EXACT text, the `selectSafFixtureOnce` precedent) and
     * navigates to the Download root when the picker opened on a collection without it.
     */
    private fun awaitPickerRow(
        fileName: String,
        timeoutMillis: Long,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var navigationSteps = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val nodes = pickerRoots().flatMap(::collectNodes).filter { it.isVisibleToUser }
            nodes.firstOrNull { textOf(it) == fileName }?.let { return it }
            nodes.firstOrNull { descriptionOf(it) == fileName }?.let { return it }
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
        return null
    }

    private fun pickerForeground(): Boolean = foregroundPackage()?.contains("documentsui") == true

    private fun appForeground(): Boolean = foregroundPackage()?.contains("com.unifiedledger.android") == true

    /** The active window's package (the ImportScaleTraversal `activeWindowPackage` read, plus a window fallback). */
    private fun foregroundPackage(): String? {
        runCatching { automation.rootInActiveWindow }
            .getOrNull()
            ?.packageName
            ?.toString()
            ?.let { return it }
        return runCatching { automation.windows }
            .getOrNull()
            .orEmpty()
            .firstOrNull { window -> window.isActive || window.isFocused }
            ?.let { window -> runCatching { window.root?.packageName?.toString() }.getOrNull() }
    }

    /**
     * Writes ONE failure-time picture: every window's type/active/focused flags, its package, and
     * the visible text/contentDescription it exposes (the app's surface AND the picker's, so a
     * picker that never returned is visible at a glance). The text is chunked for logcat's
     * per-message bound and returned whole so the caller folds a compact copy into the exception.
     */
    fun dumpUiSnapshot(label: String): String {
        val windows = runCatching { automation.windows }.getOrNull().orEmpty()
        val parts = mutableListOf("windows=${windows.size}")
        windows.forEachIndexed { index, window ->
            val root = runCatching { window.root }.getOrNull()
            val packageName = root?.packageName?.toString().orEmpty()
            val texts =
                if (root == null) {
                    listOf("root=null")
                } else {
                    val collected = mutableListOf<String>()
                    collectNodes(root).filter { it.isVisibleToUser }.forEach { node ->
                        val text = textOf(node)
                        val description = descriptionOf(node)
                        val label =
                            when {
                                text.isNotBlank() -> "T:$text"
                                description.isNotBlank() -> "D:$description"
                                else -> null
                            }
                        if (label != null && label !in collected) collected += label
                    }
                    collected.take(MAX_DUMP_TEXTS)
                }
            parts +=
                "window[$index]{type=${window.type},active=${window.isActive},focused=${window.isFocused},pkg=$packageName," +
                "texts=[${texts.joinToString(" ¦ ")}]}"
        }
        val dump = "${P807Q2Flow1Facts.PREFIX} DUMP $label ${parts.joinToString(" ")}"
        var offset = 0
        while (offset < dump.length) {
            val end = minOf(dump.length, offset + LOGCAT_CHUNK)
            log(dump.substring(offset, end))
            offset = end
        }
        return dump
    }

    private fun summarizeDump(dump: String): String = dump.take(DUMP_SUMMARY_CHARS)

    /** Runs one UI step and, when it fails loudly, writes the window picture before rethrowing. */
    private fun <T> withUiDump(
        label: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: IllegalStateException) {
            val dump = dumpUiSnapshot(label)
            throw IllegalStateException("$label failed; ${summarizeDump(dump)}", failure)
        }

    private fun describeNode(node: AccessibilityNodeInfo): String =
        "class=${node.className} clickable=${node.isClickable} bounds=${bounds(node)} " +
            "text=[${textOf(node)}] desc=[${descriptionOf(node)}]"

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
        const val PICKER_WINDOW_TIMEOUT_MILLIS = 30_000L
        const val PICKER_RETURN_TIMEOUT_MILLIS = 20_000L
        const val FIELD_SETTLE_TIMEOUT_MILLIS = 10_000L
        const val FIELD_INPUT_ATTEMPTS = 3
        const val SEEK_SHORT_SURFACE_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 500L
        const val MAX_TREE_NODES = 2_000
        const val MAX_DUMP_TEXTS = 80
        const val LOGCAT_CHUNK = 1_000
        const val DUMP_SUMMARY_CHARS = 700
        const val PICKER_NAVIGATION_STEPS = 6
        const val PICKER_CLICK_ATTEMPTS = 2
        const val SELECTION_CLICK_ATTEMPTS = 3
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
