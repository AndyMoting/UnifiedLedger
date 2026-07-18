package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrdinaryExpenseTest {
    private val fixture = Rg01Fixture()

    @Test
    fun createsTheAcceptedAssetPaidExpense() {
        val formal = fixture.acceptedExpense()

        assertEquals(TransactionKind.EXPENSE, formal.transaction.kind)
        assertEquals(fixture.ledgerId, formal.transaction.ledgerId)
        assertEquals(fixture.expenseIds.transactionId, formal.transaction.id)
        assertEquals(fixture.expenseIds.versionId, formal.transaction.currentVersionId)

        val version = formal.versions.single()
        assertEquals(formal.transaction.currentVersionId, version.id)
        assertEquals(formal.transaction.id, version.transactionId)
        assertEquals(1, version.versionNumber)
        assertEquals(fixture.expenseIds.postingSetId, version.postingSetId)
        assertEquals("", version.note)
        assertNull(fixture.openingBalance().versions.single().note)

        val postingSet = formal.postingSets.single()
        assertEquals(version.postingSetId, postingSet.id)
        val postings = postingSet.postings
        assertEquals(2, postings.size)
        val postingsById = postings.associateBy { it.id }
        val mappedExpenseAccountId = fixture.catalog.categories
            .single { it.id == fixture.command.categoryId }
            .postingAccountId

        assertEquals(
            mappedExpenseAccountId to fixture.amount,
            postingsById.getValue(fixture.expenseIds.expensePostingId).let {
                it.accountId to it.amount
            },
        )
        assertEquals(
            fixture.paymentAccountId to money(-3_580, fixture.cny),
            postingsById.getValue(fixture.expenseIds.paymentPostingId).let {
                it.accountId to it.amount
            },
        )
    }
}
