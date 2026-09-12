package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P7-02.C L-1..L-6 manual-lending product chain, structurally mirroring the P7-02.B transfer
 * chain: a canonical snapshot as the only equivalent-replay basis, a claim-first commit port, a
 * four-state result, save-input gates and a snapshot-aware resolver.
 *
 * The product never reuses [Rg08Operations]: a confirmed LEND/COLLECT is a formal transaction
 * plus an append-only position/history append. No `BANK_DEBIT` source row, no fabricated hash and
 * no `MATCHED` evidence link are produced (L-5), so the funding leg stays unreconciled.
 */
enum class ManualLendingBehavior(
    val code: String,
) {
    LEND("LEND"),
    COLLECT("COLLECT"),
    ;

    companion object {
        fun fromCode(code: String): ManualLendingBehavior? = entries.firstOrNull { it.code == code }
    }
}

data class ManualLendingRequestIdentity(
    val ledgerId: LedgerId,
    val requestId: RequestId,
)

/**
 * The canonical replay snapshot. It mirrors the `manual_lending_request` columns exactly:
 * [amount] is the LEND principal or the COLLECT principal component, [principalAccountId] is the
 * LEND funding account or the COLLECT destination account, and [totalReceived]/[interestCategoryId]
 * are only meaningful (and non-null) for COLLECT.
 */
data class ManualLendingRequestSnapshot(
    val ledgerId: LedgerId,
    val behavior: ManualLendingBehavior,
    val counterpartyId: CounterpartyId,
    val principalAccountId: AccountId,
    val amount: Money,
    val interest: Money,
    val fee: Money,
    val totalReceived: Money?,
    val interestCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
)

data class ConfirmedLendingReceipt(
    val confirmationId: ConfirmationId,
    val transactionId: TransactionId,
)

/** Per-intent ids: one confirmation id, one stable history entry id and three posting ids. */
data class ManualLendingTransactionIds(
    val transactionId: TransactionId,
    val versionId: TransactionVersionId,
    val postingSetId: PostingSetId,
    val counterpartyPostingId: PostingId,
    val primaryAccountPostingId: PostingId,
    val interestPostingId: PostingId,
)

data class ConfirmedManualLendingCommitIds(
    val confirmationId: ConfirmationId,
    val entryId: String,
    val lendingIds: ManualLendingTransactionIds,
)

fun interface ConfirmedManualLendingIdSource {
    fun next(): ConfirmedManualLendingCommitIds
}

/**
 * The rebuilt position plus the formal transaction. The commit port persists the transaction and
 * appends the position's newest history entry atomically.
 */
data class ConfirmedManualLendingCommit(
    val confirmationId: ConfirmationId,
    val transaction: FormalTransaction,
    val position: LendingPosition,
)

/**
 * Product factory that turns one confirmed snapshot into a lending commit. Implementations
 * re-admit the account/counterparty references against the current authoritative state, reuse
 * [com.unifiedledger.domain.createManualLend]/[com.unifiedledger.domain.createManualCollect] and
 * map any residual domain rejection onto the stable product token family.
 */
fun interface ConfirmedLendingTransactionFactory {
    fun create(
        request: ManualLendingRequestSnapshot,
        ids: ConfirmedManualLendingCommitIds,
    ): DomainResult<ConfirmedManualLendingCommit>
}

sealed interface ConfirmedManualLendingResult {
    data class Created(
        val receipt: ConfirmedLendingReceipt,
    ) : ConfirmedManualLendingResult

    data class NoChange(
        val receipt: ConfirmedLendingReceipt,
    ) : ConfirmedManualLendingResult

    data class RequestIdentityConflict(
        val identity: ManualLendingRequestIdentity,
    ) : ConfirmedManualLendingResult

    data class Rejected(
        val violation: DomainViolation,
    ) : ConfirmedManualLendingResult
}

/**
 * Claim-first atomic boundary for one confirmed manual lending event. The same all-or-nothing and
 * equivalent-replay rules as [ConfirmedManualTransferCommitPort] apply: an equivalent replay
 * returns the original receipt with zero writes, the same identity with a different snapshot is a
 * request identity conflict with zero writes, and a first-request domain failure rolls the claim
 * back so the identity stays retryable.
 */
fun interface ConfirmedManualLendingCommitPort {
    fun commitOnce(
        identity: ManualLendingRequestIdentity,
        requestSnapshot: ManualLendingRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualLendingCommit>,
    ): ConfirmedManualLendingResult
}

class ExecuteConfirmedManualLending(
    private val commitPort: ConfirmedManualLendingCommitPort,
    private val idSource: ConfirmedManualLendingIdSource,
    private val createFormalTransaction: ConfirmedLendingTransactionFactory,
) {
    fun execute(request: ExplicitlyConfirmedManualLending): ConfirmedManualLendingResult {
        val identity = ManualLendingRequestIdentity(request.ledgerId, request.requestId)
        val snapshot = request.toSnapshot()
        return commitPort.commitOnce(identity, snapshot) {
            createFormalTransaction.create(snapshot, idSource.next())
        }
    }
}

data class ExplicitlyConfirmedManualLending(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val behavior: ManualLendingBehavior,
    val counterpartyId: CounterpartyId,
    val principalAccountId: AccountId,
    val amount: Money,
    val interest: Money,
    val fee: Money,
    val totalReceived: Money?,
    val interestCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
) {
    fun toSnapshot(): ManualLendingRequestSnapshot =
        ManualLendingRequestSnapshot(
            ledgerId = ledgerId,
            behavior = behavior,
            counterpartyId = counterpartyId,
            principalAccountId = principalAccountId,
            amount = amount,
            interest = interest,
            fee = fee,
            totalReceived = totalReceived,
            interestCategoryId = interestCategoryId,
            occurredAt = occurredAt,
            note = note,
        )
}

enum class ManualLendInputField {
    COUNTERPARTY,
    FUNDING_ACCOUNT,
    AMOUNT,
}

enum class ManualCollectInputField {
    COUNTERPARTY,
    DESTINATION_ACCOUNT,
    TOTAL_RECEIVED,
    PRINCIPAL,
    INTEREST,
    INTEREST_CATEGORY,
}

data class ManualLendSaveInput(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val counterpartyId: CounterpartyId?,
    val fundingAccountId: AccountId?,
    val amount: Money?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

data class ManualCollectSaveInput(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val counterpartyId: CounterpartyId?,
    val destinationAccountId: AccountId?,
    val totalReceived: Money?,
    val principal: Money?,
    val interest: Money?,
    val interestCategoryId: CategoryId?,
    val occurredAt: Instant,
    val note: String,
    val confirmation: ExplicitManualSave,
)

sealed interface ManualLendSaveResult {
    data class InvalidInput(
        val fields: Set<ManualLendInputField>,
    ) : ManualLendSaveResult

    data class Executed(
        val result: ConfirmedManualLendingResult,
    ) : ManualLendSaveResult
}

sealed interface ManualCollectSaveResult {
    data class InvalidInput(
        val fields: Set<ManualCollectInputField>,
    ) : ManualCollectSaveResult

    data class Executed(
        val result: ConfirmedManualLendingResult,
    ) : ManualCollectSaveResult
}

class ExecuteManualLendingSave(
    private val executeConfirmed: ExecuteConfirmedManualLending,
) {
    fun saveLend(input: ManualLendSaveInput): ManualLendSaveResult {
        val missing =
            buildSet {
                if (input.counterpartyId == null) add(ManualLendInputField.COUNTERPARTY)
                if (input.fundingAccountId == null) add(ManualLendInputField.FUNDING_ACCOUNT)
                if (input.amount == null) add(ManualLendInputField.AMOUNT)
            }
        if (missing.isNotEmpty()) return ManualLendSaveResult.InvalidInput(missing)
        val currency = checkNotNull(input.amount).currency
        return ManualLendSaveResult.Executed(
            executeConfirmed.execute(
                ExplicitlyConfirmedManualLending(
                    ledgerId = input.ledgerId,
                    requestId = input.requestId,
                    behavior = ManualLendingBehavior.LEND,
                    counterpartyId = checkNotNull(input.counterpartyId),
                    principalAccountId = checkNotNull(input.fundingAccountId),
                    amount = checkNotNull(input.amount),
                    interest = Money.ofMinor(0L, currency),
                    fee = Money.ofMinor(0L, currency),
                    totalReceived = null,
                    interestCategoryId = null,
                    occurredAt = input.occurredAt,
                    note = input.note,
                    confirmation = input.confirmation,
                ),
            ),
        )
    }

    fun saveCollect(input: ManualCollectSaveInput): ManualCollectSaveResult {
        val missing =
            buildSet {
                if (input.counterpartyId == null) add(ManualCollectInputField.COUNTERPARTY)
                if (input.destinationAccountId == null) add(ManualCollectInputField.DESTINATION_ACCOUNT)
                if (input.totalReceived == null) add(ManualCollectInputField.TOTAL_RECEIVED)
                if (input.principal == null) add(ManualCollectInputField.PRINCIPAL)
                if (input.interest == null) add(ManualCollectInputField.INTEREST)
                if (input.interestCategoryId == null) add(ManualCollectInputField.INTEREST_CATEGORY)
            }
        if (missing.isNotEmpty()) return ManualCollectSaveResult.InvalidInput(missing)
        val currency = checkNotNull(input.totalReceived).currency
        return ManualCollectSaveResult.Executed(
            executeConfirmed.execute(
                ExplicitlyConfirmedManualLending(
                    ledgerId = input.ledgerId,
                    requestId = input.requestId,
                    behavior = ManualLendingBehavior.COLLECT,
                    counterpartyId = checkNotNull(input.counterpartyId),
                    principalAccountId = checkNotNull(input.destinationAccountId),
                    amount = checkNotNull(input.principal),
                    interest = checkNotNull(input.interest),
                    fee = Money.ofMinor(0L, currency),
                    totalReceived = checkNotNull(input.totalReceived),
                    interestCategoryId = checkNotNull(input.interestCategoryId),
                    occurredAt = input.occurredAt,
                    note = input.note,
                    confirmation = input.confirmation,
                ),
            ),
        )
    }
}

/**
 * Rebuilds the outstanding position for one object inside the write transaction. Returning an
 * empty-history, zero-balance position for a brand-new object keeps the domain rebuild validator
 * as the sole ordering authority (L-4).
 */
fun interface LendingPositionReader {
    fun load(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
        receivableAccountId: AccountId,
        currency: CurrencyUnit,
    ): LendingPosition
}
