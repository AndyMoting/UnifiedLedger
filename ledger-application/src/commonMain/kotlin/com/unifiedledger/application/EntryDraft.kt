package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.EntryFoundationViolation
import kotlin.time.Instant

/**
 * P7-02.A S-2: shared entry type set. `TRANSFER`/`LEND`/`COLLECT` are declared so the type
 * set and the request/event vocabulary are frozen, but this batch (P7-02.A) implements only the
 * `EXPENSE` and `INCOME` product flows; the rest are added by later batches.
 */
enum class EntryType {
    EXPENSE,
    INCOME,
    TRANSFER,
    LEND,
    COLLECT,
}

/**
 * P7-02.A S-1/P1-4: one sealed typed draft carried by every draft-holding UI state. The
 * `EXPENSE` subclass is a value-compatible superset of the former `ManualExpenseDraft`
 * (same field names/order plus the new `note`), so existing constructor and copy sites keep
 * compiling and comparing equal. The UI consumes only this application type (S-1).
 *
 * The frozen cross-state invariant (P1-4) is that a state transition never changes the draft
 * subtype: Confirm/Cancel/Back/AbandonConflict/Retry/failure round-trips all carry the same
 * `TypedEntryDraft` subtype.
 */
sealed interface TypedEntryDraft {
    val entryType: EntryType

    val amountText: String

    val occurredAt: Instant?

    /** P7-02.A S-4 optional note; empty is allowed. */
    val note: String

    /** Both implemented types carry a secondary category; null until chosen. */
    val categoryId: CategoryId?

    /**
     * The type-specific account this draft debits/credits: the expense payment account or the
     * income receiving account. Used for display fallbacks and shared navigation.
     */
    val primaryAccountId: AccountId?

    /** Cross-type field writer that preserves the concrete draft subtype (P1-4). */
    fun withAmountText(text: String): TypedEntryDraft

    /** Cross-type field writer that preserves the concrete draft subtype (P1-4). */
    fun withOccurredAt(instant: Instant): TypedEntryDraft

    /** Cross-type field writer that preserves the concrete draft subtype (P1-4). */
    fun withNote(text: String): TypedEntryDraft
}

data class ExpenseDraft(
    val paymentAccountId: AccountId?,
    override val categoryId: CategoryId?,
    override val amountText: String,
    override val occurredAt: Instant?,
    override val note: String = "",
) : TypedEntryDraft {
    override val entryType: EntryType
        get() = EntryType.EXPENSE

    override val primaryAccountId: AccountId?
        get() = paymentAccountId

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
    override val entryType: EntryType
        get() = EntryType.INCOME

    override val primaryAccountId: AccountId?
        get() = receivingAccountId

    override fun withAmountText(text: String): TypedEntryDraft = copy(amountText = text)

    override fun withOccurredAt(instant: Instant): TypedEntryDraft = copy(occurredAt = instant)

    override fun withNote(text: String): TypedEntryDraft = copy(note = text)
}

/**
 * P7-02.A S-2/E-1 single implementation of the frozen type-switch retention matrix. The UI
 * must not re-derive it (E-5).
 *
 * Cross-type retained: amount raw text, `occurredAt`, `note`. Type-specific and cleared on a
 * switch away: the expense payment account, the income receiving account, and both types'
 * categories (categories are never retained across types). A `null` result means the target
 * type has no draft implementation in this batch (TRANSFER/LEND/COLLECT) and the switch is a
 * no-op at the state-machine boundary.
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
                        ExpenseDraft(
                            paymentAccountId = null,
                            categoryId = null,
                            amountText = current.amountText,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                }

            EntryType.INCOME ->
                when (current) {
                    is IncomeDraft -> current
                    is ExpenseDraft ->
                        IncomeDraft(
                            receivingAccountId = null,
                            categoryId = null,
                            amountText = current.amountText,
                            occurredAt = current.occurredAt,
                            note = current.note,
                        )
                }

            EntryType.TRANSFER,
            EntryType.LEND,
            EntryType.COLLECT,
            -> null
        }
}

/**
 * P7-02.A S-2/§5.4: defensive entry-type gate. Only the two implemented types are supported by
 * this batch; the remaining frozen enum values map to the stable `EntryTypeNotSupported` code.
 * The product UI cannot construct an unsupported draft (sealed type), so this is a defensive
 * boundary kept for later batches and direct callers.
 */
fun validateEntryTypeSupported(type: EntryType): DomainViolation? =
    when (type) {
        EntryType.EXPENSE,
        EntryType.INCOME,
        -> null

        EntryType.TRANSFER,
        EntryType.LEND,
        EntryType.COLLECT,
        -> EntryFoundationViolation.EntryTypeNotSupported
    }
