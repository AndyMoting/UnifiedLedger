package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.Counterparty
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerId

/**
 * P7-02.C L-1 counterparty directory application surface.
 *
 * The frozen v29 product schema provides the `counterparty` / `counterparty_name_history` tables
 * but no counterparty request/receipt pair, so the directory command is a natural-key
 * idempotent commit (create with an existing id is `NoChange` only when the payload matches,
 * otherwise a request-style conflict; rename appends a new current name version) rather than the
 * claim-first requestId protocol used by the four formal entry types. This is a disclosed
 * spec-vs-schema gap (see batch report); no migration was added without confirmation.
 */
data class CounterpartyOption(
    val counterpartyId: CounterpartyId,
    val name: String,
    val active: Boolean,
)

data class NewCounterpartyIds(
    val counterpartyId: CounterpartyId,
    val receivableAccountId: AccountId,
)

fun interface CounterpartyIdSource {
    fun next(): NewCounterpartyIds
}

/** Ledger-scoped directory reads; a foreign-ledger counterparty is never returned. */
interface CounterpartyDirectoryReader {
    fun find(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): Counterparty?

    fun list(ledgerId: LedgerId): List<Counterparty>
}

sealed interface CounterpartyCommandResult {
    data class Created(
        val counterparty: Counterparty,
    ) : CounterpartyCommandResult

    data class Renamed(
        val counterparty: Counterparty,
    ) : CounterpartyCommandResult

    data class ActiveChanged(
        val counterparty: Counterparty,
    ) : CounterpartyCommandResult

    data class NoChange(
        val counterparty: Counterparty,
    ) : CounterpartyCommandResult

    data class Rejected(
        val violation: DomainViolation,
    ) : CounterpartyCommandResult
}

/**
 * Directory commit boundary: one all-or-nothing transaction per command. The receivable account
 * is minted by the caller and persisted with the counterparty in the same transaction.
 */
interface CounterpartyDirectoryCommitPort {
    fun create(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        name: String,
        receivableAccountId: AccountId,
        currency: CurrencyUnit,
    ): CounterpartyCommandResult

    fun rename(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        newName: String,
    ): CounterpartyCommandResult

    fun setActive(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        active: Boolean,
    ): CounterpartyCommandResult
}

class ExecuteCreateCounterparty(
    private val commitPort: CounterpartyDirectoryCommitPort,
    private val idSource: CounterpartyIdSource,
    private val currency: CurrencyUnit,
) {
    fun execute(
        ledgerId: LedgerId,
        name: String,
    ): CounterpartyCommandResult {
        val ids = idSource.next()
        return commitPort.create(ledgerId, ids.counterpartyId, name, ids.receivableAccountId, currency)
    }
}

class ExecuteRenameCounterparty(
    private val commitPort: CounterpartyDirectoryCommitPort,
) {
    fun execute(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        newName: String,
    ): CounterpartyCommandResult = commitPort.rename(ledgerId, counterpartyId, newName)
}

class ExecuteSetCounterpartyActive(
    private val commitPort: CounterpartyDirectoryCommitPort,
) {
    fun execute(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        active: Boolean,
    ): CounterpartyCommandResult = commitPort.setActive(ledgerId, counterpartyId, active)
}

/**
 * P7-02.C host-facing directory surface: the three commands plus ledger-scoped reads, so a host
 * can list/create/rename/toggle counterparties without holding the store directly.
 */
class CounterpartyCommands(
    val create: ExecuteCreateCounterparty,
    val rename: ExecuteRenameCounterparty,
    val setActive: ExecuteSetCounterpartyActive,
    private val reader: CounterpartyDirectoryReader,
) {
    fun list(ledgerId: LedgerId): List<Counterparty> = reader.list(ledgerId)

    fun find(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): Counterparty? = reader.find(ledgerId, counterpartyId)
}
