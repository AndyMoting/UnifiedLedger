package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.DomainViolation
import kotlin.time.Instant

/**
 * P7-02 S-2: shared entry type set. `TRANSFER`/`LEND`/`COLLECT` are declared so the type set
 * and the request/event vocabulary are frozen; batches implement them incrementally (A: expense
 * and income; B: transfer; C: lend and collect).
 */
enum class EntryType {
    EXPENSE,
    INCOME,
    TRANSFER,
    LEND,
    COLLECT,
}

/**
 * P7-02 S-1/P1-4: one sealed typed draft carried by every draft-holding UI state. The `EXPENSE`
 * subclass is a value-compatible superset of the former `ManualExpenseDraft`. The UI consumes
 * only this application type (S-1); a state transition never changes the draft subtype (P1-4).
 */
sealed interface TypedEntryDraft {
    val entryType: EntryType

    val amountText: String

    val occurredAt: Instant?

    /** Optional note; empty is allowed. */
    val note: String

    /** Both implemented types carry a secondary category; null until chosen. */
    val categoryId: CategoryId?

    /**
     * The type-specific account this draft debits/credits: the expense payment account, the
     * income receiving account or the transfer source account.
     */
    val primaryAccountId: AccountId?

    fun withAmountText(text: String): TypedEntryDraft

    fun withOccurredAt(instant: Instant): TypedEntryDraft

    fun withNote(text: String): TypedEntryDraft
}

data class ExpenseDraft(
    val paymentAccountId: AccountId?,
    override val categoryId: CategoryId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.EXPENSE

    override val primaryAccountId: AccountId? get() = paymentAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

data class IncomeDraft(
    val receivingAccountId: AccountId?,
    override val categoryId: CategoryId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.INCOME

    override val primaryAccountId: AccountId? get() = receivingAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

/**
 * P7-02.B TransferDraft. `destinationCredit` and `fee` are raw UI text (amount main field is
 * the destination credit per E-1); `sourceDebit` is never stored because it derives as
 * `destinationCredit + fee` (P1-2). `feeCategoryId` is only meaningful when `fee > 0`.
 */
data class TransferDraft(
    val sourceAccountId: AccountId?,
    val destinationAccountId: AccountId?,
    val destinationCredit: String,
    val fee: String = "0.00",
    val feeCategoryId: CategoryId? = null,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.TRANSFER

    override val amountText: String get() = destinationCredit

    override val categoryId: CategoryId? get() = feeCategoryId

    override val primaryAccountId: AccountId? get() = sourceAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(destinationCredit = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

/**
 * P7-02.C LendDraft. `amount` is the raw UI text of the lend principal; `counterpartyId` and
 * `fundingAccountId` are the type-specific object/funding references (E-1: the counterparty is
 * cleared on a switch away, the funding account belongs to the shared asset-account class).
 */
data class LendDraft(
    val counterpartyId: CounterpartyId?,
    val amount: String,
    val fundingAccountId: AccountId?,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.LEND

    override val amountText: String get() = amount

    override val categoryId: CategoryId? get() = null

    override val primaryAccountId: AccountId? get() = fundingAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amount = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

/**
 * P7-02.C CollectDraft. `totalReceived` is the collect main amount (E-1), while
 * `principal`/`interest` are never auto-filled; `fee` is fixed to `0.00`. `interestCategoryId`
 * is the exact active leaf INCOME category (required even for zero interest).
 */
data class CollectDraft(
    val counterpartyId: CounterpartyId?,
    val totalReceived: String,
    val principal: String,
    val interest: String,
    val fee: String = DEFAULT_COLLECT_FEE_TEXT,
    val interestCategoryId: CategoryId? = null,
    val destinationAccountId: AccountId?,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.COLLECT

    override val amountText: String get() = totalReceived

    override val categoryId: CategoryId? get() = interestCategoryId

    override val primaryAccountId: AccountId? get() = destinationAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(totalReceived = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

/**
 * P7-02 E-1 single implementation of the frozen type-switch retention matrix. The UI must not
 * re-derive it (E-5).
 *
 * Cross-type retained: amount raw text (migrated to each target's main amount field), `occurredAt`
 * and `note`. The asset-account class (expense payment / transfer source / lend funding account)
 * is mutually retained. Type-specific fields are cleared on a switch away and never restored: the
 * transfer destination, the income receiving account, the collect destination, the lending
 * counterparty and every category.
 */
object EntryFieldRetention {
    fun switchType(
        current: TypedEntryDraft,
        target: EntryType,
    ): TypedEntryDraft? =
        when (target) {
            EntryType.EXPENSE ->
                when (current) {
                    is ExpenseDraft -> current
                    is IncomeDraft ->
                        ExpenseDraft(null, null, current.amountText, current.occurredAt, current.note)
                    is TransferDraft ->
                        ExpenseDraft(current.sourceAccountId, null, current.destinationCredit, current.occurredAt, current.note)
                    is LendDraft ->
                        ExpenseDraft(current.fundingAccountId, null, current.amount, current.occurredAt, current.note)
                    is CollectDraft ->
                        ExpenseDraft(current.destinationAccountId, null, current.totalReceived, current.occurredAt, current.note)
                }

            EntryType.INCOME ->
                when (current) {
                    is IncomeDraft -> current
                    is ExpenseDraft ->
                        IncomeDraft(null, null, current.amountText, current.occurredAt, current.note)
                    is TransferDraft ->
                        IncomeDraft(null, null, current.destinationCredit, current.occurredAt, current.note)
                    is LendDraft ->
                        IncomeDraft(null, null, current.amount, current.occurredAt, current.note)
                    is CollectDraft ->
                        IncomeDraft(null, null, current.totalReceived, current.occurredAt, current.note)
                }

            EntryType.TRANSFER ->
                when (current) {
                    is TransferDraft -> current
                    is ExpenseDraft ->
                        TransferDraft(
                            sourceAccountId = current.paymentAccountId,
                            destinationAccountId = null,
                            destinationCredit = current.amountText,
                            fee = DEFAULT_TRANSFER_FEE_TEXT,
                            feeCategoryId = null,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                    is IncomeDraft ->
                        TransferDraft(
                            sourceAccountId = null,
                            destinationAccountId = null,
                            destinationCredit = current.amountText,
                            fee = DEFAULT_TRANSFER_FEE_TEXT,
                            feeCategoryId = null,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                    is LendDraft ->
                        TransferDraft(
                            sourceAccountId = current.fundingAccountId,
                            destinationAccountId = null,
                            destinationCredit = current.amount,
                            fee = DEFAULT_TRANSFER_FEE_TEXT,
                            feeCategoryId = null,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                    is CollectDraft ->
                        TransferDraft(
                            sourceAccountId = null,
                            destinationAccountId = null,
                            destinationCredit = current.totalReceived,
                            fee = DEFAULT_TRANSFER_FEE_TEXT,
                            feeCategoryId = null,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                }

            EntryType.LEND ->
                when (current) {
                    is LendDraft -> current
                    is ExpenseDraft ->
                        LendDraft(counterpartyId = null, amount = current.amountText, fundingAccountId = current.paymentAccountId, occurredAt = current.occurredAt, note = current.note)
                    is IncomeDraft ->
                        LendDraft(counterpartyId = null, amount = current.amountText, fundingAccountId = null, occurredAt = current.occurredAt, note = current.note)
                    is TransferDraft ->
                        LendDraft(counterpartyId = null, amount = current.destinationCredit, fundingAccountId = current.sourceAccountId, occurredAt = current.occurredAt, note = current.note)
                    is CollectDraft ->
                        LendDraft(counterpartyId = null, amount = current.totalReceived, fundingAccountId = current.destinationAccountId, occurredAt = current.occurredAt, note = current.note)
                }

            EntryType.COLLECT ->
                when (current) {
                    is CollectDraft -> current
                    is ExpenseDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amountText, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note)
                    is IncomeDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amountText, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note)
                    is TransferDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.destinationCredit, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note)
                    is LendDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amount, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note)
                }
        }
}

const val DEFAULT_TRANSFER_FEE_TEXT: String = "0.00"

const val DEFAULT_COLLECT_FEE_TEXT: String = "0.00"

/**
 * P7-02/§5.4: defensive entry-type gate. Only the types implemented so far are supported; the
 * remaining frozen enum values map to the stable `EntryTypeNotSupported` code. The product UI
 * cannot construct an unsupported draft (sealed type).
 */
fun validateEntryTypeSupported(type: EntryType): DomainViolation? =
    when (type) {
        EntryType.EXPENSE,
        EntryType.INCOME,
        EntryType.TRANSFER,
        EntryType.LEND,
        EntryType.COLLECT,
        -> null
    }
