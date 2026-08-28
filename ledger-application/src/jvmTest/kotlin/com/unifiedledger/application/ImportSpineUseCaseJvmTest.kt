package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.DomainViolation
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * T-26 application layer: use-case assembly, SPINE_INTAKE_INVALID before any port call,
 * confirm/reject wiring, and lazy ID consumption (application side).
 */
class ImportSpineUseCaseJvmTest {
    private val ledgerId = LedgerId("ledger-p402")
    private val fingerprint = ImportContentFingerprint()
    private val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(emptyList(), emptyList())).value

    private val intakeIds =
        ImportIntakeIds(
            sourceId = ImportSourceId("source-a"),
            evidenceId = ImportEvidenceId("evidence-a"),
            candidateId = ImportCandidateId("candidate-a"),
            statusHistoryId = ImportStatusHistoryId("status-a-1"),
        )
    private val commitIds =
        ImportCommitIds(
            confirmationId = ImportConfirmationId("confirmation-a"),
            statusHistoryId = ImportStatusHistoryId("status-a-2"),
            formalIds =
                ImportFormalIds(
                    transactionId = TransactionId("tx-a"),
                    versionId = TransactionVersionId("version-a-v1"),
                    postingSetId = PostingSetId("posting-set-a"),
                    postingIds = listOf(PostingId("posting-expense-a"), PostingId("posting-asset-a")),
                ),
        )

    private val validRequest =
        ImportIntakeRequest(
            identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-a-intake")),
            inputRef = "batch-p402-a",
            recordOrdinal = 0,
            recordKind = ImportRecordKind.ORDINARY_FLOW_SOURCE,
            facts = ImportSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled", ImportFundingState.SETTLED, IMPORT_FUNDING_RULE_LEGACY_SETTLED, 1),
            completeness = ImportCompleteness.VALID_COMPLETE,
            candidateGeneratedAt = "legacy-intake-v1",
        )

    private class RecordingIntakePort(
        private val result: (ImportRequestIdentity, ImportIntakeSnapshot) -> ImportIntakeResult,
    ) : ImportIntakeCommitPort {
        var commits = 0
        var allocateInvocations = 0
        var lastIdentity: ImportRequestIdentity? = null
        var lastSnapshot: ImportIntakeSnapshot? = null

        override fun commitIntake(
            identity: ImportRequestIdentity,
            snapshot: ImportIntakeSnapshot,
            allocateIds: () -> ImportIntakeIds,
        ): ImportIntakeResult {
            commits++
            lastIdentity = identity
            lastSnapshot = snapshot
            allocateIds()
            allocateInvocations++
            return result(identity, snapshot)
        }
    }

    private class CountingIntakeIdSource : ImportIntakeIdSource {
        var calls = 0

        override fun next(): ImportIntakeIds {
            calls++
            return ImportIntakeIds(ImportSourceId("s"), ImportEvidenceId("e"), ImportCandidateId("c"), ImportStatusHistoryId("h"))
        }
    }

    @Test
    fun `T-26 intake assembles identity and snapshot and allocates ids only in the port callback`() {
        val idSource = CountingIntakeIdSource()
        var observed: ImportIntakeResult? = null
        val port =
            RecordingIntakePort { identity, snapshot ->
                observed =
                    ImportIntakeResult.Accepted(
                        ImportReceipt(identity.requestId, ImportSourceId("s"), ImportEvidenceId("e"), ImportCandidateId("c"), null, null),
                        emptyList(),
                    )
                ImportIntakeResult.Accepted(
                    ImportReceipt(identity.requestId, ImportSourceId("s"), ImportEvidenceId("e"), ImportCandidateId("c"), null, null),
                    emptyList(),
                )
            }
        val result = ExecuteImportIntake(port, idSource, fingerprint).execute(validRequest)
        assertIs<ImportIntakeResult.Accepted>(result)
        assertEquals(1, port.commits)
        assertEquals(1, port.allocateInvocations)
        assertEquals(1, idSource.calls)
        assertEquals(ImportRequestIdentity(ledgerId, ImportRequestId("req-a-intake")), port.lastIdentity)
        assertEquals(ImportRequestIdentity(ledgerId, ImportRequestId("req-a-intake")), port.lastSnapshot?.identity)
        assertEquals(ImportCompleteness.VALID_COMPLETE, port.lastSnapshot?.completeness)
    }

    @Test
    fun `T-26 structural intake violations reject before any port call`() {
        val cases =
            listOf(
                validRequest.copy(inputRef = ""),
                validRequest.copy(inputRef = "a".repeat(257)),
                validRequest.copy(inputRef = "bad\u0001ref"),
                validRequest.copy(recordOrdinal = -1),
                validRequest.copy(facts = validRequest.facts.copy(currencyPrecision = -1)),
                validRequest.copy(facts = validRequest.facts.copy(currencyCode = "")),
                validRequest.copy(facts = validRequest.facts.copy(occurredAt = "")),
                validRequest.copy(facts = validRequest.facts.copy(directionToken = "")),
                validRequest.copy(facts = validRequest.facts.copy(statusToken = "")),
                validRequest.copy(completeness = ImportCompleteness.VALID_COMPLETE, facts = validRequest.facts.copy(statusToken = null)),
            )
        cases.forEach { request ->
            val port = RecordingIntakePort { _, _ -> error("port must not be called") }
            val result = ExecuteImportIntake(port, CountingIntakeIdSource(), fingerprint).execute(request)
            val rejected = assertIs<ImportIntakeResult.Rejected>(result)
            assertEquals("SPINE_INTAKE_INVALID", rejected.diagnostic.code)
            assertEquals("invalid", rejected.diagnostic.severity)
            assertEquals("record", rejected.diagnostic.scope)
            assertEquals(0, port.commits)
            assertEquals(0, port.allocateInvocations)
        }
    }

    @Test
    fun `T-26 incomplete intake with at least one present fact is accepted by the use case`() {
        val incomplete =
            validRequest.copy(
                completeness = ImportCompleteness.VALID_INCOMPLETE,
                facts = validRequest.facts.copy(statusToken = null),
            )
        val port =
            RecordingIntakePort { identity, _ ->
                ImportIntakeResult.Accepted(
                    ImportReceipt(identity.requestId, ImportSourceId("s"), ImportEvidenceId("e"), ImportCandidateId("c"), null, null),
                    emptyList(),
                )
            }
        assertIs<ImportIntakeResult.Accepted>(
            ExecuteImportIntake(port, CountingIntakeIdSource(), fingerprint).execute(incomplete),
        )
    }

    @Test
    fun `T-26 confirm wires resolved facts through the factory with lazy id allocation`() {
        val idSourceCalls = intArrayOf(0)
        val factoryCalls = mutableListOf<Pair<ImportCandidateFormalizationInput, ImportCommitIds>>()
        val factory =
            ImportCandidateFormalFactory { input, ids ->
                factoryCalls.add(input to ids)
                DomainResult.Failure(DomainViolation.InvalidOrdinaryExpense)
            }
        var portSawDecision: ImportCandidateDecision? = null
        val port =
            object : ImportCandidateCommitPort {
                override fun commitOnce(
                    identity: ImportRequestIdentity,
                    snapshot: ImportCandidateDecisionSnapshot,
                    allocateIds: () -> ImportCommitIds,
                    catalog: LedgerCatalog,
                    createFormalTransaction: (ImportCandidateFormalizationInput, ImportCommitIds) -> DomainResult<ImportFormalCommit>,
                ): ImportCandidateDecisionResult {
                    portSawDecision = snapshot.decision
                    val resolved = ImportResolvedSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled")
                    val input = ImportCandidateFormalizationInput(identity.ledgerId, resolved, snapshot.confirmDecisionFields!!)
                    val created = createFormalTransaction(input, allocateIds())
                    assertIs<DomainResult.Failure>(created)
                    return ImportCandidateDecisionResult.Rejected(SpineDiagnostics.domainValidationFailed(snapshot.candidateId))
                }

                override fun commitRejectOnce(
                    identity: ImportRequestIdentity,
                    snapshot: ImportCandidateDecisionSnapshot,
                    allocateStatusId: () -> ImportStatusHistoryId,
                ): ImportCandidateDecisionResult = error("reject must not be called")
            }
        val useCase =
            ConfirmImportCandidate(
                port,
                ImportIdSource {
                    idSourceCalls[0]++
                    commitIds
                },
                factory,
                catalog,
            )
        val result =
            useCase.execute(
                ImportCandidateConfirmRequest(
                    identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-a-confirm")),
                    candidateId = ImportCandidateId("candidate-a"),
                    expectedContentHash = "sha256:afd8167ab6353423ef5632ae2a458f79bc4788f833f304c66b2fc8cf552a07e2",
                    explicitConfirmedAt = "2026-08-07T10:00:00+08:00",
                    decisionFields =
                        ImportConfirmDecisionFields.OrdinaryFlow(
                            categoryId = CategoryId("category-food"),
                            fundingAccountId = AccountId("account-asset-a"),
                        ),
                ),
            )
        assertEquals(ImportCandidateDecision.CONFIRM, portSawDecision)
        assertEquals(1, factoryCalls.size)
        assertEquals(
            ImportResolvedSourceFacts(12850, "CNY", 2, "2026-08-01T12:30:00+08:00", "out", "settled"),
            factoryCalls.single().first.resolved,
        )
        assertEquals(commitIds, factoryCalls.single().second)
        assertEquals(1, idSourceCalls[0])
        assertIs<ImportCandidateDecisionResult.Rejected>(result)
        assertEquals("SPINE_DOMAIN_VALIDATION_FAILED", (result as ImportCandidateDecisionResult.Rejected).diagnostic.code)
    }

    @Test
    fun `T-26 reject wires the status id source and never touches the formal factory`() {
        var statusCalls = 0
        var portSawDecision: ImportCandidateDecision? = null
        var portSawFields: ImportConfirmDecisionFields? = null
        val port =
            object : ImportCandidateCommitPort {
                override fun commitOnce(
                    identity: ImportRequestIdentity,
                    snapshot: ImportCandidateDecisionSnapshot,
                    allocateIds: () -> ImportCommitIds,
                    catalog: LedgerCatalog,
                    createFormalTransaction: (ImportCandidateFormalizationInput, ImportCommitIds) -> DomainResult<ImportFormalCommit>,
                ): ImportCandidateDecisionResult = error("confirm must not be called")

                override fun commitRejectOnce(
                    identity: ImportRequestIdentity,
                    snapshot: ImportCandidateDecisionSnapshot,
                    allocateStatusId: () -> ImportStatusHistoryId,
                ): ImportCandidateDecisionResult {
                    portSawDecision = snapshot.decision
                    portSawFields = snapshot.confirmDecisionFields
                    allocateStatusId()
                    return ImportCandidateDecisionResult.Accepted(
                        ImportReceipt(identity.requestId, null, null, snapshot.candidateId, null, null),
                        listOf(ImportReturnedId(ImportReturnedIdKind.CANDIDATE, snapshot.candidateId.value)),
                    )
                }
            }
        val result =
            RejectImportCandidate(
                port,
                ImportStatusIdSource {
                    statusCalls++
                    ImportStatusHistoryId("status-b-2")
                },
            ).execute(
                ImportCandidateRejectRequest(
                    identity = ImportRequestIdentity(ledgerId, ImportRequestId("req-b-reject")),
                    candidateId = ImportCandidateId("candidate-b"),
                    expectedContentHash = "sha256:5a5860ec8dd13eaa03b45627e5403c4ce62cd051c57e3a5a9d5c40f871245c89",
                ),
            )
        assertEquals(ImportCandidateDecision.REJECT, portSawDecision)
        assertNull(portSawFields)
        assertEquals(1, statusCalls)
        assertIs<ImportCandidateDecisionResult.Accepted>(result)
        assertEquals("candidate-b", (result as ImportCandidateDecisionResult.Accepted).returnedIds.single().id)
    }
}
