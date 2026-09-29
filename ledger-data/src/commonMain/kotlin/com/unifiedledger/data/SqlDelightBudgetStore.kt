package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.BudgetAuthority
import com.unifiedledger.application.BudgetAuthorityReader
import com.unifiedledger.application.BudgetCommandPayload
import com.unifiedledger.application.BudgetCommandReceipt
import com.unifiedledger.application.BudgetCommandRequest
import com.unifiedledger.application.BudgetCommandResult
import com.unifiedledger.application.BudgetConfigurationCommitPort
import com.unifiedledger.application.BudgetFailureCode
import com.unifiedledger.application.BudgetMonthConfigRow
import com.unifiedledger.application.BudgetReceiptOutcome
import com.unifiedledger.application.BudgetRequestId
import com.unifiedledger.application.BudgetSettingsVersion
import com.unifiedledger.application.BudgetTarget
import com.unifiedledger.application.budgetScopeFromStored
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.BudgetId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId

/**
 * P7-07 07.B budget configuration store (D-184 item 3; spec section 3.4).
 *
 * Claim-first atomic configuration boundary mirroring [SqlDelightCatalogStore]:
 * the `(ledgerId, requestId)` claim, the identity/version work, the immutable settings-history
 * append and the receipt are written in ONE transaction. An equivalent `requestSnapshot`
 * replay returns the original receipt with zero writes; a same-id different-snapshot request
 * returns [BudgetFailureCode.REQUEST_IDENTITY_CONFLICT]; a stale `expectedRevision` returns
 * [BudgetFailureCode.BUDGET_REVISION_CONFLICT] with zero writes. Product rows never
 * participate in the frozen `rgXX_` silos or golden replay.
 */
class SqlDelightBudgetStore private constructor(
    private val database: LedgerDatabase,
) : BudgetConfigurationCommitPort,
    BudgetAuthorityReader {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun load(target: BudgetTarget): BudgetAuthority? = loadAuthority(target)

    /**
     * P7-07 07.D-1 (spec section 5.1): every configured budget scope of one
     * `(ledgerId, month)` with its CURRENT settings, so the month list / TOTAL + category
     * enumeration does not need one single-target lookup per scope. Read-only.
     *
     * The month arrives as the canonical `YYYY-MM` key ([com.unifiedledger.application.budgetMonthConfigKey]
     * at the call site) rather than a `YearMonth`: ledger-data deliberately keeps
     * kotlinx-datetime a TEST-only dependency, and the [BudgetMonthConfigReader] lambda
     * adapter lives with the composition roots that already own the reporting types.
     */
    fun configsForMonth(
        ledgerId: LedgerId,
        monthKey: String,
    ): List<BudgetMonthConfigRow> =
        database.ledgerQueries
            .selectBudgetConfigsForMonth(ledgerId.value, monthKey) {
                budgetId,
                _,
                _,
                _,
                _,
                scopeKind,
                scopeCategoryId,
                currentRevision,
                ->
                val limit = currentLimit(ledgerId.value, budgetId, currentRevision)
                BudgetMonthConfigRow(
                    budgetId = BudgetId(budgetId),
                    scope = budgetScopeFromStored(scopeKind, scopeCategoryId),
                    revision = currentRevision,
                    // A monitored budget always stores a non-null limit (zero is a real 0);
                    // a null current limit at revision > 0 is a CLOSED history head.
                    closed = currentRevision > 0L && limit == null,
                    limitMinorUnits = limit,
                )
            }.executeAsList()

    /** Full immutable settings history for a budget, oldest revision first (spec section 3.4). */
    fun settingsHistory(
        ledgerId: LedgerId,
        budgetId: BudgetId,
    ): List<BudgetSettingsVersion> =
        database.ledgerQueries
            .selectBudgetSettingsHistory(ledgerId.value, budgetId.value) {
                revisionNumber,
                status,
                limitMinor,
                _,
                _,
                _,
                _,
                requestId,
                createdAt,
                ->
                BudgetSettingsVersion(
                    revisionNumber = revisionNumber,
                    closed = status == "CLOSED",
                    limitMinorUnits = limitMinor,
                    requestId = BudgetRequestId(requestId),
                    createdAt = createdAt,
                )
            }.executeAsList()

    override fun commitOnce(
        request: BudgetCommandRequest,
        mintBudgetId: () -> BudgetId,
    ): BudgetCommandResult {
        val ledger = request.target.ledgerId.value
        return try {
            database.transactionWithResult {
                database.ledgerQueries.claimBudgetCommandRequest(
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                    command = request.command.commandName,
                    budget_id = "",
                    request_snapshot = request.requestSnapshot,
                    input_fingerprint = request.inputFingerprint,
                    outcome = "ACCEPTED",
                    expected_revision = request.expectedRevision,
                )
                if (database.ledgerQueries.lastStatementChangedRowCount().executeAsOne() != 1L) {
                    return@transactionWithResult resolveExisting(request)
                }

                // Resolve the stable identity for this target; a fresh identity is inserted at
                // revision 0 (no history yet) and then receives its first history row below.
                val existing = loadAuthority(request.target)
                val authority =
                    existing ?: BudgetAuthority(
                        budgetId = mintBudgetId(),
                        target = request.target,
                        revision = 0L,
                        limitMinorUnits = null,
                    )
                if (existing == null) {
                    database.ledgerQueries.insertBudgetConfig(
                        ledger_id = ledger,
                        budget_id = authority.budgetId.value,
                        month_key = request.target.monthKey,
                        currency_code = request.target.currency.code,
                        currency_precision =
                            request.target.currency.precision
                                .toLong(),
                        scope_key = request.target.scopeKey,
                        scope_kind = if (request.target.scopeCategoryId == null) "TOTAL" else "CATEGORY",
                        scope_category_id = request.target.scopeCategoryId?.value,
                        current_revision = 0L,
                    )
                }

                if (authority.revision != request.expectedRevision) {
                    abortBudget(BudgetFailureCode.BUDGET_REVISION_CONFLICT, conflict = true)
                }

                // Bind the claim to the resolved identity so a replay can find the stable id.
                database.ledgerQueries.updateBudgetCommandRequestBudgetId(
                    budget_id = authority.budgetId.value,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )

                val newRevision = authority.revision + 1L
                val closed = request.command is BudgetCommandPayload.Close
                val limitMinor = (request.command as? BudgetCommandPayload.SetLimit)?.limitMinorUnits
                database.ledgerQueries.insertBudgetSettingsHistory(
                    ledger_id = ledger,
                    budget_id = authority.budgetId.value,
                    revision_number = newRevision,
                    status = if (closed) "CLOSED" else "MONITORED",
                    limit_minor = if (closed) null else limitMinor,
                    currency_code = request.target.currency.code,
                    currency_precision =
                        request.target.currency.precision
                            .toLong(),
                    scope_kind = if (request.target.scopeCategoryId == null) "TOTAL" else "CATEGORY",
                    scope_category_id = request.target.scopeCategoryId?.value,
                    request_id = request.requestId.value,
                    created_at = request.createdAt.toString(),
                )
                database.ledgerQueries.advanceBudgetRevision(ledger, authority.budgetId.value)

                database.ledgerQueries.updateBudgetCommandRequestOutcome(
                    outcome = "ACCEPTED",
                    result_revision = newRevision,
                    ledger_id = ledger,
                    request_id = request.requestId.value,
                )
                val receipt =
                    BudgetCommandReceipt(
                        requestId = request.requestId,
                        outcome = BudgetReceiptOutcome.ACCEPTED,
                        budgetId = authority.budgetId,
                        newRevision = newRevision,
                    )
                database.ledgerQueries.insertBudgetCommandReceipt(
                    ledger_id = ledger,
                    request_id = receipt.requestId.value,
                    outcome = "ACCEPTED",
                    budget_id = receipt.budgetId.value,
                    new_revision = receipt.newRevision,
                )
                BudgetCommandResult.Accepted(receipt)
            }
        } catch (rejected: BudgetTypedRollback) {
            rejected.result
        } catch (failure: Exception) {
            // Trigger/unique/FK fallback (spec section 3.4): the whole command transaction
            // already rolled back above, so the identity stays retryable and no terminal row
            // was written.
            BudgetCommandResult.Rejected(BudgetFailureCode.BUDGET_CONSTRAINT_VIOLATION)
        }
    }

    private fun resolveExisting(request: BudgetCommandRequest): BudgetCommandResult {
        val ledger = request.target.ledgerId.value
        val requestId = request.requestId.value
        val stored =
            database.ledgerQueries
                .selectBudgetCommandRequest(ledger, requestId) { _, _, snapshot, _, _, _, _ -> snapshot }
                .executeAsOneOrNull() ?: abortBudget(BudgetFailureCode.BUDGET_CONSTRAINT_VIOLATION)
        if (stored != request.requestSnapshot) {
            return BudgetCommandResult.Conflict(BudgetFailureCode.REQUEST_IDENTITY_CONFLICT)
        }
        val receipt =
            database.ledgerQueries
                .selectBudgetCommandReceipt(ledger, requestId) { receiptOutcome, budgetId, newRevision ->
                    BudgetCommandReceipt(
                        requestId = request.requestId,
                        outcome =
                            if (receiptOutcome == "ACCEPTED") {
                                BudgetReceiptOutcome.ACCEPTED
                            } else {
                                BudgetReceiptOutcome.NO_CHANGE
                            },
                        budgetId = BudgetId(budgetId),
                        newRevision = newRevision,
                    )
                }.executeAsOneOrNull() ?: abortBudget(BudgetFailureCode.BUDGET_CONSTRAINT_VIOLATION)
        return BudgetCommandResult.NoChange(receipt)
    }

    private fun loadAuthority(target: BudgetTarget): BudgetAuthority? {
        val row =
            database.ledgerQueries
                .selectBudgetConfigByScope(
                    ledger_id = target.ledgerId.value,
                    month_key = target.monthKey,
                    currency_code = target.currency.code,
                    scope_key = target.scopeKey,
                ) {
                    budgetId,
                    monthKey,
                    currencyCode,
                    currencyPrecision,
                    scopeKey,
                    _,
                    scopeCategoryId,
                    currentRevision,
                    ->
                    BudgetAuthority(
                        budgetId = BudgetId(budgetId),
                        target =
                            BudgetTarget(
                                ledgerId = target.ledgerId,
                                monthKey = monthKey,
                                currency = CurrencyUnit(currencyCode, currencyPrecision.toInt()),
                                scopeKey = scopeKey,
                                scopeCategoryId = scopeCategoryId?.let(::CategoryId),
                            ),
                        revision = currentRevision,
                        limitMinorUnits = currentLimit(target.ledgerId.value, budgetId, currentRevision),
                    )
                }.executeAsOneOrNull()
        return row
    }

    /** The current limit is the newest history row's limit (`null` when closed). */
    private fun currentLimit(
        ledger: String,
        budgetId: String,
        revision: Long,
    ): Long? {
        if (revision == 0L) return null
        return database.ledgerQueries
            .selectBudgetSettingsHistory(ledger, budgetId) { revisionNumber, _, limitMinor, _, _, _, _, _, _ ->
                revisionNumber to limitMinor
            }.executeAsList()
            .lastOrNull { it.first == revision }
            ?.second
    }

    companion object {
        /**
         * Android/foreign-key-configured handle variant (mirrors the other product stores): the
         * platform driver already configured foreign keys and its busy timeout, so this never
         * issues the JDBC-only `PRAGMA busy_timeout` through the driver.
         */
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightBudgetStore = SqlDelightBudgetStore(database)
    }
}

private class BudgetTypedRollback(
    val result: BudgetCommandResult,
) : RuntimeException()

private fun abortBudget(
    code: BudgetFailureCode,
    conflict: Boolean = false,
): Nothing =
    throw BudgetTypedRollback(
        if (conflict) {
            BudgetCommandResult.Conflict(code)
        } else {
            BudgetCommandResult.Rejected(code)
        },
    )
