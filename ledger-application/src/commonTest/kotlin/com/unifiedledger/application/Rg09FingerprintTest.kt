package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.FormalTransaction
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.Posting
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSet
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.Transaction
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionKind
import com.unifiedledger.domain.TransactionTimes
import com.unifiedledger.domain.TransactionVersion
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class Rg09FingerprintTest {
    @Test
    fun canonicalProjectionUsesJcsObjectOrderAndSha256() {
        val projection =
            Rg09LedgerFingerprintProjection(
                listOf(
                    Rg09FingerprintPosting(
                        transactionId = "transaction-1",
                        currentVersionId = "version-1",
                        effectiveAt = "2026-01-01T00:00:00+08:00",
                        postingId = "posting-1",
                        accountId = "asset-a",
                        currency = "CNY",
                        amount = "1.00",
                    ),
                ),
            )

        assertEquals(
            "{\"postings\":[{\"account_id\":\"asset-a\",\"amount\":\"1.00\",\"currency\":\"CNY\",\"current_version_id\":\"version-1\",\"effective_at\":\"2026-01-01T00:00:00+08:00\",\"posting_id\":\"posting-1\",\"transaction_id\":\"transaction-1\"}]}",
            projection.canonicalJson(),
        )
        assertEquals(
            "sha256:72c5a33f14227ba4c80d0730ea4a65b721fc28f18699bfd8aef24387cb78756e",
            Rg09LedgerFingerprint.digest(projection),
        )
    }

    @Test
    fun projectionSortsTheFrozenTupleAndExcludesLaterEffectiveRows() {
        val target = Instant.parse("2026-01-31T15:59:59Z")
        val records =
            listOf(
                record(
                    transactionId = "transaction-z",
                    versionId = "version-z",
                    postingSetId = "set-z",
                    postingIds = listOf("posting-z"),
                    accountIds = listOf("asset-z"),
                    amounts = listOf(100L),
                    effectiveAt = Instant.parse("2026-01-20T00:00:00Z"),
                ),
                record(
                    transactionId = "transaction-a",
                    versionId = "version-a",
                    postingSetId = "set-a",
                    postingIds = listOf("posting-a"),
                    accountIds = listOf("asset-a"),
                    amounts = listOf(-100L),
                    effectiveAt = Instant.parse("2026-01-20T00:00:00Z"),
                ),
                record(
                    transactionId = "transaction-future",
                    versionId = "version-future",
                    postingSetId = "set-future",
                    postingIds = listOf("posting-future"),
                    accountIds = listOf("asset-a"),
                    amounts = listOf(999L),
                    effectiveAt = Instant.parse("2026-02-01T00:00:00Z"),
                ),
            )

        val projection = Rg09LedgerFingerprint.project(records, target)

        val assetPostings = projection.postings.filter { it.accountId.startsWith("asset-") }
        assertEquals(listOf("transaction-a", "transaction-z"), assetPostings.map { it.transactionId })
        assertEquals(listOf("asset-a", "asset-z"), assetPostings.map { it.accountId })
        assertEquals(4, projection.postings.size)
    }

    @Test
    fun metadataAndSourceLineageDoNotChangeFingerprintButEconomicFactsDo() {
        val target = Instant.parse("2026-01-31T15:59:59Z")
        val original =
            record(
                transactionId = "transaction-1",
                versionId = "version-1",
                postingSetId = "set-1",
                postingIds = listOf("posting-1"),
                accountIds = listOf("asset-a"),
                amounts = listOf(100L),
                effectiveAt = Instant.parse("2026-01-20T00:00:00Z"),
                createdAt = Instant.parse("2026-02-01T00:00:00Z"),
                sourceRecordId = Rg09SourceRecordId("source-a"),
            )
        val metadataOnly =
            original.copy(
                createdAt = Instant.parse("2026-03-01T00:00:00Z"),
                sourceRecordId = Rg09SourceRecordId("source-b"),
            )
        val economicChange =
            record(
                transactionId = "transaction-1",
                versionId = "version-1",
                postingSetId = "set-1",
                postingIds = listOf("posting-1"),
                accountIds = listOf("asset-a"),
                amounts = listOf(101L),
                effectiveAt = Instant.parse("2026-01-20T00:00:00Z"),
            )

        assertEquals(
            Rg09LedgerFingerprint.digest(listOf(original), target),
            Rg09LedgerFingerprint.digest(listOf(metadataOnly), target),
        )
        assertNotEquals(
            Rg09LedgerFingerprint.digest(listOf(original), target),
            Rg09LedgerFingerprint.digest(listOf(economicChange), target),
        )
    }

    @Test
    fun jcsEscapesControlCharactersAndQuotesWithoutUnicodeNormalization() {
        val projection =
            Rg09LedgerFingerprintProjection(
                listOf(
                    Rg09FingerprintPosting(
                        transactionId = "tx\"\\\n",
                        currentVersionId = "v",
                        effectiveAt = "time",
                        postingId = "p",
                        accountId = "账户",
                        currency = "CNY",
                        amount = "0.00",
                    ),
                ),
            )

        assertEquals(
            "{\"postings\":[{\"account_id\":\"账户\",\"amount\":\"0.00\",\"currency\":\"CNY\",\"current_version_id\":\"v\",\"effective_at\":\"time\",\"posting_id\":\"p\",\"transaction_id\":\"tx\\\"\\\\\\n\"}]}",
            projection.canonicalJson(),
        )
    }

    private fun record(
        transactionId: String,
        versionId: String,
        postingSetId: String,
        postingIds: List<String>,
        accountIds: List<String>,
        amounts: List<Long>,
        effectiveAt: Instant,
        createdAt: Instant = Instant.parse("2026-02-01T00:00:00Z"),
        sourceRecordId: Rg09SourceRecordId? = null,
    ): Rg09FormalTransactionRecord {
        val currency = CurrencyUnit("CNY", 2)
        val postings =
            postingIds.indices.map { index ->
                Posting(
                    id = PostingId(postingIds[index]),
                    accountId = AccountId(accountIds[index]),
                    amount = Money.ofMinor(amounts[index], currency),
                )
            } +
                Posting(
                    id = PostingId("$postingSetId-balance"),
                    accountId = AccountId("equity-opening"),
                    amount = Money.ofMinor(-amounts.sum(), currency),
                )
        val postingSet =
            assertIs<DomainResult.Success<PostingSet>>(
                PostingSet.create(PostingSetId(postingSetId), postings),
            ).value
        val transaction =
            Transaction(
                id = TransactionId(transactionId),
                ledgerId = LedgerId("ledger-rg09"),
                kind = TransactionKind.ACCOUNT_TRANSFER,
                currentVersionId = TransactionVersionId(versionId),
            )
        val formal =
            assertIs<DomainResult.Success<FormalTransaction>>(
                FormalTransaction.create(
                    transaction,
                    listOf(
                        TransactionVersion(
                            id = TransactionVersionId(versionId),
                            transactionId = transaction.id,
                            versionNumber = 1,
                            postingSetId = postingSet.id,
                            times = TransactionTimes.collapsed(effectiveAt),
                        ),
                    ),
                    listOf(postingSet),
                ),
            ).value
        return Rg09FormalTransactionRecord(formal, createdAt, sourceRecordId)
    }
}
