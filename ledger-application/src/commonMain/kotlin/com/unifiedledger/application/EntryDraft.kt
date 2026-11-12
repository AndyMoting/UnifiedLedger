package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CounterpartyId
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.MerchantId
import com.unifiedledger.domain.TagId
import com.unifiedledger.domain.TransactionId
import kotlin.time.Instant

/**
 * P7-02 S-2: shared entry type set. `TRANSFER`/`LEND`/`COLLECT` are declared so the type set
 * and the request/event vocabulary are frozen; batches implement them incrementally (A: expense
 * and income; B: transfer; C: lend and collect).
 *
 * P8-03 i2b (D-312; spec section 2.2/D-I): the frozen five-type set is EXPLICITLY EXPANDED by
 * `REFUND_RECEIPT` (「退款到账」) — the one entry type this design gate opens; the five unnamed
 * types (balance adjustment, mixed payment, instalment, image evidence, credit-card billing)
 * stay undelivered. The expansion is registered as an expansion, not a silent widening.
 */
enum class EntryType {
    EXPENSE,
    INCOME,
    TRANSFER,
    LEND,
    COLLECT,
    REFUND_RECEIPT,
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

    /**
     * P7-08 08.B-1 (D-221; spec section 4.1): optional tag/merchant associations carried by every
     * draft. They are kind-independent (stored on the transaction root) and never mandatory, so
     * they add no required field to normal entry. Both default to "no association".
     */
    val tagIds: Set<TagId>

    val merchantId: MerchantId?

    fun withAmountText(text: String): TypedEntryDraft

    fun withOccurredAt(instant: Instant): TypedEntryDraft

    fun withNote(text: String): TypedEntryDraft

    /**
     * P7-08 08.B-2 (spec section 4.1): replaces the optional tag association (multi-select). The
     * whole set is written; an empty set is the "no tags" state. A pure draft write — nothing is
     * committed until the explicit confirm, and the commit re-validates the ids.
     */
    fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft

    /** P7-08 08.B-2 (spec section 4.1): sets the optional merchant association (0..1); null clears. */
    fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft
}

data class ExpenseDraft(
    val paymentAccountId: AccountId?,
    override val categoryId: CategoryId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.EXPENSE

    override val primaryAccountId: AccountId? get() = paymentAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
}

data class IncomeDraft(
    val receivingAccountId: AccountId?,
    override val categoryId: CategoryId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.INCOME

    override val primaryAccountId: AccountId? get() = receivingAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
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
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.TRANSFER

    override val amountText: String get() = destinationCredit

    override val categoryId: CategoryId? get() = feeCategoryId

    override val primaryAccountId: AccountId? get() = sourceAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(destinationCredit = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
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
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.LEND

    override val amountText: String get() = amount

    override val categoryId: CategoryId? get() = null

    override val primaryAccountId: AccountId? get() = fundingAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amount = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
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
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.COLLECT

    override val amountText: String get() = totalReceived

    override val categoryId: CategoryId? get() = interestCategoryId

    override val primaryAccountId: AccountId? get() = destinationAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(totalReceived = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
}

/**
 * P8-03 i2b (D-312; spec sections 2.2/2.4). The manual refund-receipt draft. The user picks the
 * original expense transaction ([originalTransactionId]) and the destination asset account
 * ([destinationAccountId]); the amount and the arrival instant are the shared amount/occurred-at
 * fields.
 *
 * The category is READ-ONLY: [categoryId] is always null because the server re-resolves the
 * inherited secondary expense category from the original transaction at submit time (spec
 * section 2.4) and never trusts the UI. [primaryAccountId] is the destination account, so the
 * generic account write and the currency resolution treat the refund's arrival account as the
 * draft's primary account.
 */
data class RefundReceiptDraft(
    val originalTransactionId: TransactionId?,
    val destinationAccountId: AccountId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
    override val tagIds: Set<TagId> = emptySet(),
    override val merchantId: MerchantId? = null,
) : TypedEntryDraft {
    override val entryType: EntryType get() = EntryType.REFUND_RECEIPT

    override val categoryId: CategoryId? get() = null

    override val primaryAccountId: AccountId? get() = destinationAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)

    override fun withTagIds(tagIds: Set<TagId>): TypedEntryDraft = copy(tagIds = tagIds)

    override fun withMerchantId(merchantId: MerchantId?): TypedEntryDraft = copy(merchantId = merchantId)
}

/**
 * P7-02 E-1 single implementation of the frozen type-switch retention matrix, extended by P7-08
 * 08.B-1 (D-221; spec section 4.2) with the kind-independent optional associations. The UI must
 * not re-derive it (E-5).
 *
 * Cross-type retained: amount raw text (migrated to each target's main amount field), `occurredAt`,
 * `note` and — because the annotation lives on the transaction root and is kind-independent — the
 * optional `tagIds`/`merchantId`. The asset-account class (expense payment / transfer source / lend
 * funding account) is mutually retained. Type-specific fields are cleared on a switch away and
 * never restored: the transfer destination, the income receiving account, the collect destination,
 * the lending counterparty and every category.
 *
 * P8-03 i2b (D-312; spec section 2.2 保留规则) — the FROZEN EXCEPTION of `REFUND_RECEIPT`: no
 * account migrates in either direction, which is the opposite of the asset-account-class rule
 * above. A refund's arrival account is not the expense's payment account (an expense payment
 * account is the money's source, the refund's destination is where it came back), so migrating it
 * would silently assert an economic relation the user never typed; the original transaction
 * reference is likewise type-specific and is never retained. The refund keeps only the shared
 * fields: amount text, occurred-at, note, tags and merchant. Switching INTO `REFUND_RECEIPT` and
 * OUT of it therefore always yields a destination account of `null`.
 */
object EntryFieldRetention {
    fun switchType(
        current: TypedEntryDraft,
        target: EntryType,
    ): TypedEntryDraft? {
        val tags = current.tagIds
        val merchant = current.merchantId
        return when (target) {
            EntryType.EXPENSE ->
                when (current) {
                    is ExpenseDraft -> current
                    is IncomeDraft ->
                        ExpenseDraft(null, null, current.amountText, current.occurredAt, current.note, tags, merchant)
                    is TransferDraft ->
                        ExpenseDraft(current.sourceAccountId, null, current.destinationCredit, current.occurredAt, current.note, tags, merchant)
                    is LendDraft ->
                        ExpenseDraft(current.fundingAccountId, null, current.amount, current.occurredAt, current.note, tags, merchant)
                    is CollectDraft ->
                        ExpenseDraft(current.destinationAccountId, null, current.totalReceived, current.occurredAt, current.note, tags, merchant)
                    // D-312 frozen exception: a refund account is never an asset-account-class peer.
                    is RefundReceiptDraft ->
                        ExpenseDraft(null, null, current.amountText, current.occurredAt, current.note, tags, merchant)
                }

            EntryType.INCOME ->
                when (current) {
                    is IncomeDraft -> current
                    is ExpenseDraft ->
                        IncomeDraft(null, null, current.amountText, current.occurredAt, current.note, tags, merchant)
                    is TransferDraft ->
                        IncomeDraft(null, null, current.destinationCredit, current.occurredAt, current.note, tags, merchant)
                    is LendDraft ->
                        IncomeDraft(null, null, current.amount, current.occurredAt, current.note, tags, merchant)
                    is CollectDraft ->
                        IncomeDraft(null, null, current.totalReceived, current.occurredAt, current.note, tags, merchant)
                    // D-312 frozen exception: no account migration from a refund draft.
                    is RefundReceiptDraft ->
                        IncomeDraft(null, null, current.amountText, current.occurredAt, current.note, tags, merchant)
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
                            tagIds = tags,
                            merchantId = merchant,
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
                            tagIds = tags,
                            merchantId = merchant,
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
                            tagIds = tags,
                            merchantId = merchant,
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
                            tagIds = tags,
                            merchantId = merchant,
                        )
                    // D-312 frozen exception: no source account is migrated from a refund draft.
                    is RefundReceiptDraft ->
                        TransferDraft(
                            sourceAccountId = null,
                            destinationAccountId = null,
                            destinationCredit = current.amountText,
                            fee = DEFAULT_TRANSFER_FEE_TEXT,
                            feeCategoryId = null,
                            occurredAt = current.occurredAt,
                            note = current.note,
                            tagIds = tags,
                            merchantId = merchant,
                        )
                }

            EntryType.LEND ->
                when (current) {
                    is LendDraft -> current
                    is ExpenseDraft ->
                        LendDraft(counterpartyId = null, amount = current.amountText, fundingAccountId = current.paymentAccountId, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is IncomeDraft ->
                        LendDraft(counterpartyId = null, amount = current.amountText, fundingAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is TransferDraft ->
                        LendDraft(counterpartyId = null, amount = current.destinationCredit, fundingAccountId = current.sourceAccountId, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is CollectDraft ->
                        LendDraft(counterpartyId = null, amount = current.totalReceived, fundingAccountId = current.destinationAccountId, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    // D-312 frozen exception: no funding account is migrated from a refund draft.
                    is RefundReceiptDraft ->
                        LendDraft(counterpartyId = null, amount = current.amountText, fundingAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                }

            EntryType.COLLECT ->
                when (current) {
                    is CollectDraft -> current
                    is ExpenseDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amountText, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is IncomeDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amountText, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is TransferDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.destinationCredit, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is LendDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amount, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    // D-312 frozen exception: no account migration from a refund draft.
                    is RefundReceiptDraft ->
                        CollectDraft(counterpartyId = null, totalReceived = current.amountText, principal = "", interest = "", destinationAccountId = null, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                }

            EntryType.REFUND_RECEIPT ->
                when (current) {
                    is RefundReceiptDraft -> current
                    is ExpenseDraft ->
                        RefundReceiptDraft(originalTransactionId = null, destinationAccountId = null, amountText = current.amountText, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is IncomeDraft ->
                        RefundReceiptDraft(originalTransactionId = null, destinationAccountId = null, amountText = current.amountText, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is TransferDraft ->
                        RefundReceiptDraft(originalTransactionId = null, destinationAccountId = null, amountText = current.destinationCredit, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is LendDraft ->
                        RefundReceiptDraft(originalTransactionId = null, destinationAccountId = null, amountText = current.amount, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                    is CollectDraft ->
                        RefundReceiptDraft(originalTransactionId = null, destinationAccountId = null, amountText = current.totalReceived, occurredAt = current.occurredAt, note = current.note, tagIds = tags, merchantId = merchant)
                }
        }
    }
}

const val DEFAULT_TRANSFER_FEE_TEXT: String = "0.00"

const val DEFAULT_COLLECT_FEE_TEXT: String = "0.00"

/**
 * P7-02/§5.4: defensive entry-type gate. Only the types implemented so far are supported; the
 * remaining frozen enum values map to the stable `EntryTypeNotSupported` code. The product UI
 * cannot construct an unsupported draft (sealed type).
 *
 * P8-03 i2b (D-312; spec section 2.2): `REFUND_RECEIPT` is delivered, so this gate accepts it —
 * and the reducer's Continue branch is this function's SINGLE production caller (spec residual 4:
 * the gate no longer has zero production callers).
 */
fun validateEntryTypeSupported(type: EntryType): DomainViolation? =
    when (type) {
        EntryType.EXPENSE,
        EntryType.INCOME,
        EntryType.TRANSFER,
        EntryType.LEND,
        EntryType.COLLECT,
        EntryType.REFUND_RECEIPT,
        -> null
    }
