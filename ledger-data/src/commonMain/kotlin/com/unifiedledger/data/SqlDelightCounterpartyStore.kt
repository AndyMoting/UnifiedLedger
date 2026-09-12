package com.unifiedledger.data

import app.cash.sqldelight.db.SqlDriver
import com.unifiedledger.application.CounterpartyCommandResult
import com.unifiedledger.application.CounterpartyDirectoryCommitPort
import com.unifiedledger.application.CounterpartyDirectoryReader
import com.unifiedledger.application.LendingPositionHistoryView
import com.unifiedledger.application.LendingPositionReadPort
import com.unifiedledger.application.LendingPositionReader
import com.unifiedledger.application.LendingPositionView
import com.unifiedledger.application.ManualLendingBehavior
import com.unifiedledger.data.db.LedgerDatabase
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CatalogViolation
import com.unifiedledger.domain.Counterparty
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CounterpartyNameVersion
import com.unifiedledger.domain.CounterpartyViolation
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingBehaviorCode
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.LendingPositionHistoryEntry
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.counterpartyReceivableAccount
import com.unifiedledger.domain.createCounterparty
import com.unifiedledger.domain.createLendingPosition
import com.unifiedledger.domain.renameCounterparty
import kotlin.time.Instant

/**
 * P7-02.C L-1 counterparty directory store plus the L-2/L-4 per-object position reads.
 *
 * The directory is ledger-scoped and append-only in its name history. Creating a counterparty
 * also creates its hidden receivable account (`counterpartyReceivableAccount`) in the same
 * transaction; the account never satisfies the A-2 manageable predicate and is never wired to the
 * RG-08 silo (L-1/L-5). The position rebuild replays the frozen `(occurred_at, entry_id)` order
 * through [createLendingPosition], the sole rebuild validator (L-4).
 */
class SqlDelightCounterpartyStore private constructor(
    private val database: LedgerDatabase,
) : CounterpartyDirectoryReader,
    CounterpartyDirectoryCommitPort,
    LendingPositionReadPort,
    LendingPositionReader {
    constructor(database: LedgerDatabase, driver: SqlDriver) : this(database) {
        configureSqliteConnection(driver)
    }

    override fun find(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): Counterparty? =
        database.ledgerQueries
            .selectCounterparty(ledgerId.value, counterpartyId.value)
            .executeAsOneOrNull()
            ?.let { row ->
                Counterparty(
                    id = CounterpartyId(row.counterparty_id),
                    ledgerId = ledgerId,
                    name = row.current_name,
                    receivableAccountId = AccountId(row.receivable_account_id),
                    active = row.active == 1L,
                    nameHistory = loadNameHistory(ledgerId, counterpartyId),
                )
            }

    override fun list(ledgerId: LedgerId): List<Counterparty> =
        database.ledgerQueries
            .selectCounterparties(ledgerId.value)
            .executeAsList()
            .map { row ->
                val id = CounterpartyId(row.counterparty_id)
                Counterparty(
                    id = id,
                    ledgerId = ledgerId,
                    name = row.current_name,
                    receivableAccountId = AccountId(row.receivable_account_id),
                    active = row.active == 1L,
                    nameHistory = loadNameHistory(ledgerId, id),
                )
            }

    private fun loadNameHistory(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): List<CounterpartyNameVersion> =
        database.ledgerQueries
            .selectCounterpartyNameHistory(ledgerId.value, counterpartyId.value)
            .executeAsList()
            .map {
                CounterpartyNameVersion(
                    versionNumber = it.version_number.toInt(),
                    name = it.name,
                    current = it.status == "CURRENT",
                )
            }

    override fun create(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        name: String,
        receivableAccountId: AccountId,
        currency: CurrencyUnit,
    ): CounterpartyCommandResult {
        val currencyValue = currency
        return database.transactionWithResult {
            // Natural-key idempotency: an existing id converges to NO_CHANGE on a matching
            // payload and a conflict on a differing one (the name is the mutable payload part).
            val existing = find(ledgerId, counterpartyId)
            if (existing != null) {
                return@transactionWithResult if (existing.name == name) {
                    CounterpartyCommandResult.NoChange(existing)
                } else {
                    CounterpartyCommandResult.Rejected(CatalogViolation.CatalogNameConflict)
                }
            }
            val created =
                when (
                    val domain =
                        createCounterparty(
                            id = counterpartyId,
                            ledgerId = ledgerId,
                            name = name,
                            receivableAccountId = receivableAccountId,
                        )
                ) {
                    is DomainResult.Failure -> return@transactionWithResult CounterpartyCommandResult.Rejected(domain.violation)
                    is DomainResult.Success -> domain.value
                }
            val account =
                counterpartyReceivableAccount(
                    id = receivableAccountId,
                    ledgerId = ledgerId,
                    currency = currencyValue,
                    name = created.name,
                )
            database.ledgerQueries.insertCatalogAccount(
                ledger_id = ledgerId.value,
                account_id = account.id.value,
                name = account.name,
                kind = account.kind.name,
                currency_code = account.currency.code,
                currency_precision = account.currency.precision.toLong(),
                owned_by_user = 0L,
                real_account = 0L,
                system_role = null,
                hidden = 1L,
                active = 1L,
            )
            database.ledgerQueries.insertCatalogNameHistory(
                ledger_id = ledgerId.value,
                owner_kind = "account",
                owner_id = account.id.value,
                version_number = 1L,
                name = account.name,
                status = "CURRENT",
            )
            database.ledgerQueries.insertCounterparty(
                ledger_id = ledgerId.value,
                counterparty_id = created.id.value,
                current_name = created.name,
                receivable_account_id = created.receivableAccountId.value,
                active = 1L,
            )
            created.nameHistory.forEach { version ->
                database.ledgerQueries.insertCounterpartyNameHistory(
                    ledger_id = ledgerId.value,
                    counterparty_id = created.id.value,
                    version_number = version.versionNumber.toLong(),
                    name = version.name,
                    status = if (version.current) "CURRENT" else "SUPERSEDED",
                )
            }
            CounterpartyCommandResult.Created(created)
        }
    }

    override fun rename(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        newName: String,
    ): CounterpartyCommandResult {
        val current = find(ledgerId, counterpartyId) ?: return CounterpartyCommandResult.Rejected(CounterpartyViolation.CounterpartyNotFound)
        val renamed =
            when (val domain = renameCounterparty(current, newName)) {
                is DomainResult.Failure -> return CounterpartyCommandResult.Rejected(domain.violation)
                is DomainResult.Success -> domain.value
            }
        if (renamed.name == current.name) return CounterpartyCommandResult.NoChange(current)
        return database.transactionWithResult {
            database.ledgerQueries.supersedeCounterpartyCurrentName(ledgerId.value, counterpartyId.value)
            val nextVersion = renamed.nameHistory.maxOf { it.versionNumber }
            database.ledgerQueries.insertCounterpartyNameHistory(
                ledger_id = ledgerId.value,
                counterparty_id = counterpartyId.value,
                version_number = nextVersion.toLong(),
                name = renamed.name,
                status = "CURRENT",
            )
            database.ledgerQueries.updateCounterpartyName(
                current_name = renamed.name,
                ledger_id = ledgerId.value,
                counterparty_id = counterpartyId.value,
            )
            CounterpartyCommandResult.Renamed(renamed)
        }
    }

    override fun setActive(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        active: Boolean,
    ): CounterpartyCommandResult {
        val current = find(ledgerId, counterpartyId) ?: return CounterpartyCommandResult.Rejected(CounterpartyViolation.CounterpartyNotFound)
        if (current.active == active) return CounterpartyCommandResult.NoChange(current)
        database.ledgerQueries.updateCounterpartyActive(
            active = if (active) 1L else 0L,
            ledger_id = ledgerId.value,
            counterparty_id = counterpartyId.value,
        )
        return CounterpartyCommandResult.ActiveChanged(current.copy(active = active))
    }

    override fun load(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        receivableAccountId: AccountId,
        currency: CurrencyUnit,
    ): LendingPosition {
        val history =
            loadHistory(ledgerId, counterpartyId).map { view ->
                LendingPositionHistoryEntry(
                    id = view.entryId,
                    behaviorCode =
                        if (view.behavior == ManualLendingBehavior.LEND) {
                            LendingBehaviorCode.LEND
                        } else {
                            LendingBehaviorCode.COLLECT
                        },
                    amountMinor = if (view.behavior == ManualLendingBehavior.LEND) view.amountMinor else -view.amountMinor,
                    principalBalanceAfterMinor = view.principalBalanceAfterMinor,
                    transactionId = view.transactionId,
                    occurredAt = view.occurredAt,
                )
            }
        val balance = history.lastOrNull()?.principalBalanceAfterMinor ?: 0L
        return when (
            val rebuilt =
                createLendingPosition(
                    id = positionId(ledgerId, counterpartyId),
                    counterpartyId = counterpartyId.value,
                    receivableAccountId = receivableAccountId,
                    currency = currency,
                    principalBalanceMinor = balance,
                    history = history,
                )
        ) {
            is DomainResult.Success -> rebuilt.value
            is DomainResult.Failure -> error("persisted lending position failed the frozen rebuild validator: ${rebuilt.violation}")
        }
    }

    override fun findPosition(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): LendingPositionView? {
        val position =
            database.ledgerQueries
                .selectLendingPosition(ledgerId.value, counterpartyId.value)
                .executeAsOneOrNull()
                ?: return null
        val name = find(ledgerId, counterpartyId)?.name ?: counterpartyId.value
        return LendingPositionView(
            counterpartyId = counterpartyId,
            name = name,
            currency = CurrencyUnit(position.currency_code, position.currency_precision.toInt()),
            principalBalanceMinor = position.principal_balance_minor,
            history = loadHistory(ledgerId, counterpartyId),
        )
    }

    override fun listPositions(ledgerId: LedgerId): List<LendingPositionView> =
        database.ledgerQueries
            .selectLendingPositions(ledgerId.value)
            .executeAsList()
            .map { row ->
                val id = CounterpartyId(row.counterparty_id)
                LendingPositionView(
                    counterpartyId = id,
                    name = find(ledgerId, id)?.name ?: id.value,
                    currency = CurrencyUnit(row.currency_code, row.currency_precision.toInt()),
                    principalBalanceMinor = row.principal_balance_minor,
                    history = loadHistory(ledgerId, id),
                )
            }

    private fun loadHistory(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): List<LendingPositionHistoryView> =
        database.ledgerQueries
            .selectLendingPositionHistory(ledgerId.value, counterpartyId.value) {
                entryId,
                behaviorCode,
                amountMinor,
                principalBalanceAfterMinor,
                transactionId,
                occurredAt,
                ->
                LendingPositionHistoryView(
                    occurredAt = Instant.parse(occurredAt),
                    entryId = entryId,
                    behavior =
                        checkNotNull(ManualLendingBehavior.fromCode(behaviorCode)) {
                            "unknown persisted lending behavior code"
                        },
                    amountMinor = amountMinor,
                    principalBalanceAfterMinor = principalBalanceAfterMinor,
                    transactionId = TransactionId(transactionId),
                )
            }.executeAsList()

    private fun positionId(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): String = "position-${ledgerId.value}-${counterpartyId.value}"

    companion object {
        internal fun forPlatformConfiguredDatabase(
            database: LedgerDatabase,
        ): SqlDelightCounterpartyStore = SqlDelightCounterpartyStore(database)
    }
}
