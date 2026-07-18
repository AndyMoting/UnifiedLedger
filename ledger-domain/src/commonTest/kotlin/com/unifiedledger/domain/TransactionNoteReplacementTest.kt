package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionNoteReplacementTest {
    private val fixture = Rg01Fixture()
    private val replacementVersionId = TransactionVersionId("version-expense-rg01-v2")

    @Test
    fun replacesOnlyTheNoteByAppendingVersionTwo() {
        val original = fixture.acceptedExpense()

        val replaced: FormalTransaction = success(
            original.replaceNote(
                command = TransactionNoteUpdateCommand(note = "早餐"),
                ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
            ),
        )

        assertEquals(original.transaction.id, replaced.transaction.id)
        assertEquals(original.transaction.ledgerId, replaced.transaction.ledgerId)
        assertEquals(original.transaction.kind, replaced.transaction.kind)
        assertEquals(replacementVersionId, replaced.transaction.currentVersionId)
        assertEquals(2, replaced.versions.size)

        val originalVersion = original.versions.single()
        assertEquals(originalVersion, replaced.versions.single { it.versionNumber == 1 })

        val replacement = replaced.versions.single { it.versionNumber == 2 }
        assertEquals(replacementVersionId, replacement.id)
        assertEquals(original.transaction.id, replacement.transactionId)
        assertEquals("早餐", replacement.note)
    }

    @Test
    fun reusesThePostingSetAndExactEconomicTimesWithoutAddingFinancialEntities() {
        val original = fixture.acceptedExpense()

        val replaced: FormalTransaction = success(
            original.replaceNote(
                command = TransactionNoteUpdateCommand(note = "早餐"),
                ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
            ),
        )

        val versionOne = replaced.versions.single { it.versionNumber == 1 }
        val versionTwo = replaced.versions.single { it.versionNumber == 2 }
        assertEquals(versionOne.postingSetId, versionTwo.postingSetId)
        assertEquals(versionOne.times, versionTwo.times)
        assertEquals(original.postingSets.size, replaced.postingSets.size)
        assertEquals(1, replaced.postingSets.size)
        assertEquals(
            original.postingSets.map { it.id },
            replaced.postingSets.map { it.id },
        )
        assertEquals(
            original.postingSets.map { it.id }.toSet(),
            replaced.postingSets.map { it.id }.toSet(),
        )
        assertEquals(original.postingContent(), replaced.postingContent())
        assertEquals(
            original.postingSets.flatMap { it.postings }.map { it.id }.toSet(),
            replaced.postingSets.flatMap { it.postings }.map { it.id }.toSet(),
        )
    }

    @Test
    fun leavesTheOriginalAggregateUnchanged() {
        val original = fixture.acceptedExpense()
        val transactionBefore = original.transaction
        val versionsBefore = original.versions.toList()
        val postingContentBefore = original.postingContent()

        val replaced: FormalTransaction = success(
            original.replaceNote(
                command = TransactionNoteUpdateCommand(note = "早餐"),
                ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
            ),
        )

        assertEquals(transactionBefore, original.transaction)
        assertEquals(versionsBefore, original.versions)
        assertEquals(postingContentBefore, original.postingContent())
        assertEquals(fixture.expenseIds.versionId, original.transaction.currentVersionId)
        assertEquals(replacementVersionId, replaced.transaction.currentVersionId)
    }

    @Test
    fun replaysTheSameSingleFundingEffectBeforeAndAfterReplacement() {
        val opening = fixture.openingBalance()
        val original = fixture.acceptedExpense()
        val replaced: FormalTransaction = success(
            original.replaceNote(
                command = TransactionNoteUpdateCommand(note = "早餐"),
                ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
            ),
        )

        val before = success(
            replayBalances(
                catalog = fixture.catalog,
                transactions = listOf(opening, original),
            ),
        )
        val after = success(
            replayBalances(
                catalog = fixture.catalog,
                transactions = listOf(opening, replaced),
            ),
        )
        val expectedBalances = mapOf(
            fixture.paymentAccountId to money(96_420, fixture.cny),
            fixture.expenseAccountId to money(3_580, fixture.cny),
            fixture.equityAccountId to money(-100_000, fixture.cny),
        )

        assertEquals(before.balances, after.balances)
        assertEquals(expectedBalances, after.balances)
    }

    @Test
    fun rejectsANoncontiguousFormalVersionChain() {
        val original = fixture.acceptedExpense()
        val versionOne = original.versions.single()
        val versionThreeId = TransactionVersionId("version-expense-rg01-v3")
        val versionThree = versionOne.copy(
            id = versionThreeId,
            versionNumber = 3,
        )

        val result = FormalTransaction.create(
            transaction = original.transaction.copy(currentVersionId = versionThreeId),
            versions = listOf(versionOne, versionThree),
            postingSets = original.postingSets,
        )

        assertEquals(DomainViolation.InvalidFormalTransaction, failure(result))
    }

    @Test
    fun rejectsACurrentPointerThatDoesNotTargetTheHighestVersion() {
        val original = fixture.acceptedExpense()
        val versionOne = original.versions.single()
        val versionTwo = versionOne.copy(
            id = replacementVersionId,
            versionNumber = 2,
        )

        val result = FormalTransaction.create(
            transaction = original.transaction,
            versions = listOf(versionOne, versionTwo),
            postingSets = original.postingSets,
        )

        assertEquals(DomainViolation.InvalidFormalTransaction, failure(result))
    }

    @Test
    fun acceptsAContiguousVersionChainRegardlessOfInputOrder() {
        val original = fixture.acceptedExpense()
        val versionOne = original.versions.single()
        val versionTwo = versionOne.copy(
            id = replacementVersionId,
            versionNumber = 2,
            note = "早餐",
        )

        val formal = success(
            FormalTransaction.create(
                transaction = original.transaction.copy(currentVersionId = replacementVersionId),
                versions = listOf(versionTwo, versionOne),
                postingSets = original.postingSets,
            ),
        )

        assertEquals(replacementVersionId, formal.transaction.currentVersionId)
        assertEquals(2, formal.versions.single { it.id == replacementVersionId }.versionNumber)
    }

    @Test
    fun preservesTheFullChainAndFinancialEntitiesAcrossRepeatedNoteReplacement() {
        val original = fixture.acceptedExpense()
        val originalTransactionBefore = original.transaction
        val originalVersionsBefore = original.versions.toList()
        val originalPostingContentBefore = original.postingContent()
        val versionTwo = success(
            original.replaceNote(
                command = TransactionNoteUpdateCommand(note = "早餐"),
                ids = TransactionNoteUpdateIds(versionId = replacementVersionId),
            ),
        )
        val versionTwoTransactionBefore = versionTwo.transaction
        val versionTwoVersionsBefore = versionTwo.versions.toList()
        val versionTwoPostingContentBefore = versionTwo.postingContent()
        val versionThreeId = TransactionVersionId("version-expense-rg01-v3")
        val versionThreeNote = "晚餐 🌙 e\u0301"

        val versionThree = success(
            versionTwo.replaceNote(
                command = TransactionNoteUpdateCommand(note = versionThreeNote),
                ids = TransactionNoteUpdateIds(versionId = versionThreeId),
            ),
        )

        assertEquals(versionThreeId, versionThree.transaction.currentVersionId)
        assertEquals(3, versionThree.versions.size)
        assertEquals(listOf(1, 2, 3), versionThree.versions.map { it.versionNumber }.sorted())
        assertEquals(
            mapOf(
                1 to "",
                2 to "早餐",
                3 to versionThreeNote,
            ),
            versionThree.versions.associate { it.versionNumber to it.note },
        )

        val originalVersion = original.versions.single()
        versionThree.versions.forEach { version ->
            assertEquals(originalVersion.postingSetId, version.postingSetId)
            assertEquals(originalVersion.times, version.times)
        }
        val originalPostingSetIds = original.postingSets.map { it.id }
        val originalPostingIds = original.postingSets.flatMap { it.postings }.map { it.id }
        assertEquals(originalPostingSetIds.size, versionThree.postingSets.size)
        assertEquals(originalPostingSetIds.toSet(), versionThree.postingSets.map { it.id }.toSet())
        assertEquals(
            originalPostingIds.size,
            versionThree.postingSets.sumOf { it.postings.size },
        )
        assertEquals(
            originalPostingIds.toSet(),
            versionThree.postingSets.flatMap { it.postings }.map { it.id }.toSet(),
        )
        assertEquals(originalPostingContentBefore, versionThree.postingContent())

        assertEquals(originalTransactionBefore, original.transaction)
        assertEquals(originalVersionsBefore, original.versions)
        assertEquals(originalPostingContentBefore, original.postingContent())
        assertEquals(versionTwoTransactionBefore, versionTwo.transaction)
        assertEquals(versionTwoVersionsBefore, versionTwo.versions)
        assertEquals(versionTwoPostingContentBefore, versionTwo.postingContent())
    }

    @Test
    fun rejectsAnExistingReplacementVersionIdWithoutChangingTheOriginal() {
        val original = fixture.acceptedExpense()
        val transactionBefore = original.transaction
        val versionsBefore = original.versions.toList()
        val postingContentBefore = original.postingContent()

        val result = original.replaceNote(
            command = TransactionNoteUpdateCommand(note = "早餐"),
            ids = TransactionNoteUpdateIds(versionId = fixture.expenseIds.versionId),
        )

        assertEquals(DomainViolation.InvalidFormalTransaction, failure(result))
        assertEquals(transactionBefore, original.transaction)
        assertEquals(versionsBefore, original.versions)
        assertEquals(postingContentBefore, original.postingContent())
    }
}

private fun FormalTransaction.postingContent(): Map<
    PostingSetId,
    Set<Triple<PostingId, AccountId, Money>>,
> = postingSets.associate { postingSet ->
    postingSet.id to postingSet.postings
        .map { posting -> Triple(posting.id, posting.accountId, posting.amount) }
        .toSet()
}
