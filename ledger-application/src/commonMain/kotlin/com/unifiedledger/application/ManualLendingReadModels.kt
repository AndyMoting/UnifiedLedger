package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.Counterparty
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.LendingPosition
import com.unifiedledger.domain.ManualLendingViolation
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.time.Instant

/**
 * P7-02.C stable lending failure codes (spec section 5.3). `code` is the frozen literal; messages
 * are never compared. `LendingBehaviorNotSupported` is defensive dead code (the two use cases fix
 * the behavior, L-6).
 */
enum class ManualLendingFailureCode(
    val code: String,
) {
    LENDING_COUNTERPARTY_NOT_FOUND("LendingCounterpartyNotFound"),
    LENDING_ACCOUNT_NOT_ELIGIBLE("LendingAccountNotEligible"),
    LENDING_PRINCIPAL_EXCEEDS_BALANCE("LendingPrincipalExceedsBalance"),
    LENDING_COMPONENTS_MISMATCH("LendingComponentsMismatch"),
    LENDING_FEE_MUST_BE_ZERO("LendingFeeMustBeZero"),
    LENDING_INTEREST_CATEGORY_REQUIRED("LendingInterestCategoryRequired"),
    LENDING_AMOUNT_MUST_BE_POSITIVE("LendingAmountMustBePositive"),
    LENDING_TOTAL_MUST_BE_POSITIVE("LendingTotalMustBePositive"),
    LENDING_BACKDATED_NOT_ALLOWED("LendingBackdatedNotAllowed"),
    LENDING_BEHAVIOR_NOT_SUPPORTED("LendingBehaviorNotSupported"),
    ;

    companion object {
        fun of(violation: DomainViolation): ManualLendingFailureCode? =
            when (violation) {
                ManualLendingViolation.LendingCounterpartyNotFound -> LENDING_COUNTERPARTY_NOT_FOUND
                ManualLendingViolation.LendingAccountNotEligible -> LENDING_ACCOUNT_NOT_ELIGIBLE
                ManualLendingViolation.LendingPrincipalExceedsBalance -> LENDING_PRINCIPAL_EXCEEDS_BALANCE
                ManualLendingViolation.LendingComponentsMismatch -> LENDING_COMPONENTS_MISMATCH
                ManualLendingViolation.LendingFeeMustBeZero -> LENDING_FEE_MUST_BE_ZERO
                ManualLendingViolation.LendingInterestCategoryRequired -> LENDING_INTEREST_CATEGORY_REQUIRED
                ManualLendingViolation.LendingAmountMustBePositive -> LENDING_AMOUNT_MUST_BE_POSITIVE
                ManualLendingViolation.LendingTotalMustBePositive -> LENDING_TOTAL_MUST_BE_POSITIVE
                ManualLendingViolation.LendingBackdatedNotAllowed -> LENDING_BACKDATED_NOT_ALLOWED
                ManualLendingViolation.LendingBehaviorNotSupported -> LENDING_BEHAVIOR_NOT_SUPPORTED
                else -> null
            }
    }
}

/** P7-02.C read model for one persisted manual lending event, mirroring [ManualTransferCommitRecord]. */
data class ManualLendingCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualLendingRequestSnapshot,
    val receipt: ConfirmedLendingReceipt,
    val currentVersionId: TransactionVersionId,
)

/**
 * P7-02.C snapshot-aware unknown-commit resolution. Compares the persisted snapshot field by
 * field; only [ManualLendingCommitResolution.MatchingReceipt] recovers success.
 */
sealed interface ManualLendingCommitResolution {
    data class MatchingReceipt(
        val receipt: ConfirmedLendingReceipt,
    ) : ManualLendingCommitResolution

    data object SnapshotConflict : ManualLendingCommitResolution

    data object Absent : ManualLendingCommitResolution

    data object Unavailable : ManualLendingCommitResolution
}

class ResolveManualLendingCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: ManualLendingRequestSnapshot,
    ): ManualLendingCommitResolution {
        val record =
            try {
                readPort.findManualLendingByRequest(ledgerId, requestId)
            } catch (failure: Exception) {
                return ManualLendingCommitResolution.Unavailable
            }
        return when {
            record == null -> ManualLendingCommitResolution.Absent
            record.snapshot == attempted -> ManualLendingCommitResolution.MatchingReceipt(record.receipt)
            else -> ManualLendingCommitResolution.SnapshotConflict
        }
    }
}

/** P7-02.C independent lending request id source (one UUIDv7 per new intent). */
fun interface ManualLendingRequestIdSource {
    fun next(): RequestId
}

class UuidV7ManualLendingRequestIdSource(
    private val generator: UuidV7Generator,
) : ManualLendingRequestIdSource {
    override fun next(): RequestId = RequestId(generator.next())
}

/**
 * P7-02.C lending/interest option projection. The funding/destination drawers reuse the owned
 * real ASSET [PaymentAccountOption] set; the interest categories are the active leaf INCOME
 * options from the same authoritative catalog.
 */
data class ManualLendingOptions(
    val ownedAssetAccounts: List<PaymentAccountOption>,
    val interestCategories: List<IncomeCategoryOption>,
    /** L-1: the active counterparties of this ledger; empty when the directory is unavailable. */
    val counterparties: List<CounterpartyOption> = emptyList(),
)

fun interface ManualLendingOptionsProvider {
    fun queryOptions(): ManualLendingOptions
}

/**
 * P7-02.C lending option projection over the authoritative catalog plus the ledger-scoped
 * counterparty directory. Only `active` counterparties become selectable options; an inactive
 * object stays listed for history but is never offered for a new intent.
 */
class QueryManualLendingOptions(
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
    private val counterpartyReader: CounterpartyDirectoryReader? = null,
) : ManualLendingOptionsProvider {
    override fun queryOptions(): ManualLendingOptions {
        val expenseOptions = QueryManualExpenseOptions(ledgerId, catalog).queryOptions()
        val incomeOptions = QueryManualIncomeOptions(ledgerId, catalog).queryOptions()
        val counterparties =
            counterpartyReader?.list(ledgerId)?.map {
                CounterpartyOption(counterpartyId = it.id, name = it.name, active = it.active)
            } ?: emptyList()
        return ManualLendingOptions(
            ownedAssetAccounts = expenseOptions.paymentAccounts,
            interestCategories = incomeOptions.incomeCategories,
            counterparties = counterparties,
        )
    }
}

class QueryAuthoritativeManualLendingOptions(
    private val reader: CatalogAuthorityReader,
    private val ledgerId: LedgerId,
    private val counterpartyReader: CounterpartyDirectoryReader? = null,
) : ManualLendingOptionsProvider {
    override fun queryOptions(): ManualLendingOptions {
        val catalog = reader.load(ledgerId)?.catalog ?: return ManualLendingOptions(emptyList(), emptyList())
        return QueryManualLendingOptions(ledgerId, catalog, counterpartyReader).queryOptions()
    }
}

/**
 * P7-02.C L-1/L-3: resolves one counterparty and its receivable account from the authoritative
 * directory inside the write transaction. A missing, cross-ledger or `active=false` counterparty
 * is a typed [ManualLendingViolation.LendingCounterpartyNotFound] (zero writes).
 */
interface ResolveCounterpartyForLending {
    fun resolve(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): DomainViolation?
}

class CounterpartyDirectoryForLending(
    private val reader: CounterpartyDirectoryReader,
) : ResolveCounterpartyForLending {
    override fun resolve(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): DomainViolation? {
        val counterparty: Counterparty = reader.find(ledgerId, counterpartyId) ?: return ManualLendingViolation.LendingCounterpartyNotFound
        if (counterparty.ledgerId != ledgerId || !counterparty.active) {
            return ManualLendingViolation.LendingCounterpartyNotFound
        }
        return null
    }
}

/** One append-only history row of an object's principal ledger, ordered `(occurred_at, entry_id)`. */
data class LendingPositionHistoryView(
    val occurredAt: Instant,
    val entryId: String,
    val behavior: ManualLendingBehavior,
    val amountMinor: Long,
    val principalBalanceAfterMinor: Long,
    val transactionId: TransactionId,
)

data class LendingPositionView(
    val counterpartyId: CounterpartyId,
    val name: String,
    val currency: CurrencyUnit,
    val principalBalanceMinor: Long,
    val history: List<LendingPositionHistoryView>,
)

/**
 * P7-02.C L-1/L-2 object principal query. It returns the per-object current principal balance and
 * the full append-only history in the frozen `(occurred_at, entry_id)` order, plus a whole-ledger
 * listing; history is never re-sorted by the caller.
 */
interface LendingPositionReadPort {
    fun findPosition(
        ledgerId: LedgerId,
        counterpartyId: CounterpartyId,
    ): LendingPositionView?

    fun listPositions(ledgerId: LedgerId): List<LendingPositionView>
}

/**
 * Product-side position reconstruction used by the transaction factory when a formal transaction
 * already carries a position. The store persists positions row by row, so the application layer
 * exposes only the rebuild seam.
 */
fun emptyLendingPosition(
    id: String,
    counterpartyId: CounterpartyId,
    receivableAccountId: AccountId,
    currency: CurrencyUnit,
): LendingPosition =
    LendingPosition(
        id = id,
        counterpartyId = counterpartyId.value,
        receivableAccountId = receivableAccountId,
        currency = currency,
        principalBalanceMinor = 0L,
        allocationScope = com.unifiedledger.domain.LendingAllocationScope.PERSON_LEVEL_NET_POSITION,
        contractAllocationEnabled = false,
        history = emptyList(),
    )
