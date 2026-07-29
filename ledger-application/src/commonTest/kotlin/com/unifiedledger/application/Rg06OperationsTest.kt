package com.unifiedledger.application

import com.unifiedledger.domain.Account
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.AccountKind
import com.unifiedledger.domain.AssetPaidOrdinaryExpenseIds
import com.unifiedledger.domain.Category
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CategoryKind
import com.unifiedledger.domain.CreateStagedPaymentCommand
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.DomainResult
import com.unifiedledger.domain.InstallmentPayment
import com.unifiedledger.domain.InstallmentPaymentId
import com.unifiedledger.domain.LedgerCatalog
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.PostingSetId
import com.unifiedledger.domain.RecordStagedPaymentInstallmentCommand
import com.unifiedledger.domain.StagedPayment
import com.unifiedledger.domain.StagedPaymentCreationIds
import com.unifiedledger.domain.StagedPaymentFulfillment
import com.unifiedledger.domain.StagedPaymentHistoryId
import com.unifiedledger.domain.StagedPaymentInstallmentIds
import com.unifiedledger.domain.StagedPaymentLifecycleId
import com.unifiedledger.domain.StagedPaymentRelationId
import com.unifiedledger.domain.StagedPaymentRole
import com.unifiedledger.domain.StagedPaymentResult
import com.unifiedledger.domain.TransactionId
import com.unifiedledger.domain.TransactionVersionId
import com.unifiedledger.domain.createStagedPayment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class Rg06OperationsTest {
    private val referenceModel = Rg06ApplicationReferenceConformanceModel(portFactory = { it.port() })

    @Test fun allEightActionsContract() = referenceModel.allEightActionsReturnOnlyFrozenIdsAndReplayDefensiveOriginalSnapshots()
    @Test fun canonicalIdentityContract() = referenceModel.ingestCanonicalIdentityUsesOnlyRawFactIncludingByteExactTimeText()
    @Test fun importedTimeContract() = referenceModel.importedSourceTimeUsesInjectedOffsetPolicyBeforeCommitAndRejectedIdentityIsReusable()
    @Test fun signedMirrorContract() = referenceModel.signedFactsUseCheckedMagnitudeAndMirrorsSupportBothDirectionsWithoutNewLinks()
    @Test fun candidateSetOnceContract() = referenceModel.candidateAndEvidenceTransitionsAreClosedRoleCheckedSetOnceAndSourceImmutable()
    @Test fun manualProvenanceContract() = referenceModel.manualEvidenceResolvesImmutableIntakeObservationWithoutFormalPaymentSynthesis()
    @Test fun manualRejectionContract() = referenceModel.manualEvidenceMissingAmountCurrencyAndPostingMismatchRejectAtomicallyAndReserveNothing()
    @Test fun typedFactoryContract() = referenceModel.typedFactoriesCloseTimeCandidateConfidenceMagnitudeAndMirrorLineageStates()
    @Test fun duplicateCandidateStatusContract() = referenceModel.candidateConfirmationRejectsDuplicateStatusIdentityWithoutChangingCandidate()
    @Test fun frozenRejectionContract() = referenceModel.frozenEighteenRejectionsUseExactReasonPathPrecedenceZeroWritesAndReusableIdentity()
    @Test fun ownershipAndCollisionContract() = referenceModel.generatedIdentityCollisionsAndCrossLedgerOwnershipRejectAtomically()
    @Test fun allGeneratedIdentityKindsContract() = referenceModel.everyGeneratedIdentityCategoryCollisionIsAtomic()
    @Test fun semanticPrecedenceContract() = referenceModel.frozenSemanticFailurePrecedesOccupiedProposalIdentityAndCorrectionReusesIdentity()
    @Test fun prePortGuardContract() = referenceModel.explicitGuardsRemainSeparateFromFrozenRejectionsAndNeverCallPort()
}

/** Inspection surface used only by the application reference model. */
private interface Rg06ApplicationReferenceProbe : Rg06CommitPort {
    fun snapshot(): Any
    fun source(id: Rg06SourceId): Rg06StagedPaymentBankSource
    fun evidence(id: Rg06EvidenceId): Rg06StagedPaymentEvidence
    fun candidate(id: Rg06CandidateId): Rg06StagedPaymentCandidate
    fun payment(id: InstallmentPaymentId): InstallmentPayment
    fun linkCount(): Int
    fun reconciliationState(): Any
    fun occupyGeneratedIdentity(kind: String, value: String)
    fun stageManualObservation(key: Rg06ManualObservationKey, observation: Rg06ManualBankObservation)
}

/** Application-internal conformance model for the in-memory reference port only. */
private class Rg06ApplicationReferenceConformanceModel(
    val portFactory: (Rg06Fixture) -> Rg06ApplicationReferenceProbe,
) {

    fun allEightActionsReturnOnlyFrozenIdsAndReplayDefensiveOriginalSnapshots() {
        val cases = exactActionCases()
        assertEquals(Rg06Action.entries, cases.map { it.operation.action })

        cases.forEach { case ->
            val fixture = Rg06Fixture()
            val port = portFactory(fixture)
            val executor = ExecuteRg06Operation(port)
            case.prepare(fixture, executor)
            val operation = case.operationFor(fixture)
            val accepted = assertIs<Rg06ExecutionResult.Accepted>(executor.execute(operation), case.name)
            assertEquals(case.expectedIds(fixture), accepted.returnedIds, case.name)
            val state = port.snapshot()

            val replay = case.changedProposal(operation)
            assertEquals(Rg06ExecutionResult.NoChange(case.expectedIds(fixture)), executor.execute(replay), case.name)
            assertEquals(state, port.snapshot(), case.name)
        }

        val mutable = mutableListOf<Rg06ReturnedId>(Rg06ReturnedId.Source(Rg06SourceId("source-a")))
        val accepted = Rg06ExecutionResult.Accepted(mutable)
        mutable += Rg06ReturnedId.Evidence(Rg06EvidenceId("evidence-mutated"))
        assertEquals(listOf(Rg06ReturnedId.Source(Rg06SourceId("source-a"))), accepted.returnedIds)
        val leaked = accepted.returnedIds as MutableList<Rg06ReturnedId>
        leaked.clear()
        assertEquals(listOf(Rg06ReturnedId.Source(Rg06SourceId("source-a"))), accepted.returnedIds)
        val noChange = Rg06ExecutionResult.NoChange(mutable)
        mutable.clear()
        assertEquals(2, noChange.returnedIds.size)
        val leakedNoChange = noChange.returnedIds as MutableList<Rg06ReturnedId>
        leakedNoChange.clear()
        assertEquals(2, noChange.returnedIds.size)
    }

    fun ingestCanonicalIdentityUsesOnlyRawFactIncludingByteExactTimeText() {
        val fixture = Rg06Fixture()
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        val ingest = fixture.ingestDeposit()
        val accepted = assertIs<Rg06ExecutionResult.Accepted>(executor.execute(ingest))
        val originalState = port.snapshot()

        assertEquals(
            Rg06ExecutionResult.NoChange(accepted.returnedIds),
            executor.execute(
                ingest.copy(
                    ids = Rg06IngestCommitIds(
                        Rg06CandidateId("candidate-proposal-other"),
                        Rg06CandidateStatusId("candidate-status-proposal-other"),
                    ),
                ),
            ),
        )
        assertEquals(originalState, port.snapshot())

        val sameInstantDifferentText = ingest.copy(
            input = ingest.input.copy(sourcePaymentAtText = "2026-04-28T10:00:00.0+08:00"),
        )
        assertEquals(Rg06ExecutionResult.RequestIdentityConflict, executor.execute(sameInstantDifferentText))
        assertEquals(
            Rg06ExecutionResult.RequestIdentityConflict,
            executor.execute(ingest.copy(input = ingest.input.copy(amount = Money.ofMinor(-8_001, fixture.cny)))),
        )
        val changedAction = fixture.linkDepositEvidence().copy(
            input = fixture.linkDepositEvidence().input.copy(sourceId = ingest.input.sourceId),
        )
        assertEquals(Rg06ExecutionResult.RequestIdentityConflict, executor.execute(changedAction))
        assertEquals(originalState, port.snapshot())
    }

    fun importedSourceTimeUsesInjectedOffsetPolicyBeforeCommitAndRejectedIdentityIsReusable() {
        val fixture = Rg06Fixture("offset-policy")
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        val valid = fixture.ingestDeposit()
        val invalid = valid.copy(
            input = valid.input.copy(sourcePaymentAtText = "2026-04-28T02:00:00Z"),
        )
        val before = port.snapshot()

        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.INVALID_SOURCE_TIME,
                Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT,
            ),
            executor.execute(invalid),
        )
        assertEquals(before, port.snapshot())
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(valid))
        assertEquals(
            "2026-04-28T10:00:00+08:00",
            port.source(valid.input.sourceId).payload.observedTime.text,
        )
    }

    fun signedFactsUseCheckedMagnitudeAndMirrorsSupportBothDirectionsWithoutNewLinks() {
        listOf(8_000L to -8_000L, -8_000L to 8_000L).forEachIndexed { index, (original, mirror) ->
            val fixture = Rg06Fixture("direction-$index")
            val port = portFactory(fixture)
            val executor = ExecuteRg06Operation(port)
            assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.create()))
            val ingest = fixture.ingestDeposit(amountMinor = original)
            assertIs<Rg06ExecutionResult.Accepted>(executor.execute(ingest))
            assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.confirmDepositCandidate()))
            val payment = port.payment(fixture.depositPaymentId)
            assertEquals(8_000L, payment.amount.minorUnits)
            assertEquals(original, port.source(ingest.input.sourceId).payload.amount.minorUnits)
            val linkCount = port.linkCount()
            val reconciliationBefore = port.reconciliationState()
            val mirrorOperation = fixture.mergeDepositMirror(amountMinor = mirror)
            assertEquals(
                listOf(
                    Rg06ReturnedId.Source(mirrorOperation.input.sourceId),
                    Rg06ReturnedId.Evidence(mirrorOperation.input.evidenceId),
                ),
                assertIs<Rg06ExecutionResult.Accepted>(executor.execute(mirrorOperation)).returnedIds,
            )
            assertEquals(linkCount, port.linkCount())
            assertEquals(reconciliationBefore, port.reconciliationState())
            val mirrorEvidence = assertIs<Rg06BoundStagedPaymentEvidence>(
                port.evidence(mirrorOperation.input.evidenceId),
            )
            assertEquals(fixture.depositPaymentId, mirrorEvidence.paymentId)
            assertEquals(ingest.input.evidenceId, mirrorEvidence.mirrorOfEvidenceId)
            assertEquals(fixture.importDepositLinkId, mirrorEvidence.mergedIntoEvidenceLinkId)
            assertEquals(ingest.input.sourceId, port.source(mirrorOperation.input.sourceId).mirrorOfSourceId)
        }

        listOf(0L, Long.MIN_VALUE).forEach { invalidMinor ->
            val fixture = Rg06Fixture("invalid-$invalidMinor")
            val port = portFactory(fixture)
            val executor = ExecuteRg06Operation(port)
            val invalid = fixture.ingestDeposit(amountMinor = invalidMinor)
            val before = port.snapshot()
            assertEquals(
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.MUST_BE_POSITIVE,
                    Rg06FieldPath.INPUT_AMOUNT,
                ),
                executor.execute(invalid),
            )
            assertEquals(before, port.snapshot())
            assertIs<Rg06ExecutionResult.Accepted>(
                executor.execute(fixture.ingestDeposit(amountMinor = -8_000L)),
            )
        }
    }

    fun candidateAndEvidenceTransitionsAreClosedRoleCheckedSetOnceAndSourceImmutable() {
        val fixture = Rg06Fixture()
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.create()))
        val ingest = fixture.ingestDeposit(amountMinor = -8_000L)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(ingest))

        val sourceBefore = port.source(ingest.input.sourceId)
        val candidateBefore = port.candidate(fixture.depositCandidateId)
        val pendingEvidence = assertIs<Rg06PendingStagedPaymentEvidence>(
            port.evidence(ingest.input.evidenceId),
        )
        assertEquals(Rg06CandidateRoleFact.Known(StagedPaymentRole.DEPOSIT), candidateBefore.payload.roleFact)
        assertEquals(Rg06CandidateConfidence.CERTAIN, candidateBefore.confidence)
        assertEquals(1, candidateBefore.payload.ruleVersion)
        assertEquals(RG06_CONFIRMATION_REQUIREMENTS, candidateBefore.payload.confirmationRequirements)
        assertEquals(listOf(Rg06CandidateStatus.PENDING_CONFIRMATION), candidateBefore.statusHistory.map { it.status })
        assertEquals(ingest.input.sourcePaymentAtText, pendingEvidence.observedTime.text)

        val mismatched = fixture.confirmDepositCandidate().copy(
            input = fixture.confirmDepositCandidate().input.copy(paymentRole = StagedPaymentRole.FINAL),
        )
        val beforeMismatch = port.snapshot()
        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.CANDIDATE_ROLE_MISMATCH,
                Rg06FieldPath.INPUT_PAYMENT_ROLE,
            ),
            executor.execute(mismatched),
        )
        assertEquals(beforeMismatch, port.snapshot())

        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.confirmDepositCandidate()))
        assertEquals(sourceBefore, port.source(ingest.input.sourceId))
        val candidateAfter = port.candidate(fixture.depositCandidateId)
        assertEquals(candidateBefore.payload, candidateAfter.payload)
        assertEquals(
            listOf(Rg06CandidateStatus.PENDING_CONFIRMATION, Rg06CandidateStatus.CONFIRMED),
            candidateAfter.statusHistory.map { it.status },
        )
        val bound = assertIs<Rg06BoundStagedPaymentEvidence>(port.evidence(ingest.input.evidenceId))
        assertEquals(pendingEvidence.observedTime, bound.observedTime)
        assertEquals(fixture.depositPaymentId, bound.paymentId)
        val payment = port.payment(fixture.depositPaymentId)
        assertEquals(ingest.input.sourcePaymentAt, payment.actualPaymentAt)
        assertEquals(ingest.input.sourcePaymentAt, payment.statisticsAt)
        assertEquals(ingest.input.sourcePaymentAt, payment.sourcePaymentAt)
        assertEquals(ingest.input.sourcePaymentAtText, payment.sourcePaymentAtText)

        val secondRequest = RequestId("request-confirm-set-once")
        val beforeRebind = port.snapshot()
        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.CANDIDATE_NOT_PENDING,
                Rg06FieldPath.INPUT_CANDIDATE_ID,
            ),
            executor.execute(
                fixture.confirmDepositCandidate().copy(
                    input = fixture.confirmDepositCandidate().input.copy(requestId = secondRequest),
                    ids = fixture.confirmIds("rebind", InstallmentPaymentId("payment-rebind")),
                ),
            ),
        )
        assertEquals(beforeRebind, port.snapshot())

        val ambiguous = fixture.ingestFinal(suggestedRole = null)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(ambiguous))
        assertEquals(Rg06CandidateRoleFact.ExplicitAmbiguous, port.candidate(fixture.finalCandidateId).payload.roleFact)
        assertEquals(Rg06CandidateConfidence.AMBIGUOUS, port.candidate(fixture.finalCandidateId).confidence)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.confirmFinalCandidate(secondRequest)))
    }

    fun manualEvidenceResolvesImmutableIntakeObservationWithoutFormalPaymentSynthesis() {
        val fixture = Rg06Fixture("manual-provenance")
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        executor.execute(fixture.create()).accepted()
        executor.execute(fixture.recordDeposit()).accepted()
        val formalPayment = port.payment(fixture.depositPaymentId)

        executor.execute(fixture.linkDepositEvidence()).accepted()

        val source = port.source(fixture.manualSourceId)
        val observedAt = assertIs<Rg06ObservedAt>(source.payload.observedTime)
        assertEquals(-8_000L, source.payload.amount.minorUnits)
        assertEquals("CNY", source.payload.amount.currency.code)
        assertEquals("2026-04-27T18:15:00+08:00", observedAt.text)
        assertNotEquals(formalPayment.actualPaymentAt, observedAt.instant)
        val bound = assertIs<Rg06BoundStagedPaymentEvidence>(port.evidence(fixture.manualEvidenceId))
        assertEquals(observedAt, assertIs<Rg06ObservedAt>(bound.observedTime))
        assertEquals(fixture.depositPaymentId, bound.paymentId)
        assertNull(bound.mirrorOfEvidenceId)
        assertNull(bound.mergedIntoEvidenceLinkId)
    }

    fun manualEvidenceMissingAmountCurrencyAndPostingMismatchRejectAtomicallyAndReserveNothing() {
        data class ManualCase(
            val name: String,
            val observation: (Rg06Fixture) -> Rg06ManualBankObservation?,
            val operation: (Rg06Fixture) -> Rg06Operation.LinkStagedPaymentEvidence,
            val expected: Rg06ExecutionResult.Rejected,
        )
        val cases = listOf(
            ManualCase(
                "missing",
                { null },
                { it.linkDepositEvidence() },
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH,
                    Rg06FieldPath.INPUT_EVIDENCE_ID,
                ),
            ),
            ManualCase(
                "amount",
                { it.manualObservation(amount = Money.ofMinor(-8_001L, it.cny)) },
                { it.linkDepositEvidence() },
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH,
                    Rg06FieldPath.INPUT_PAYMENT_ID,
                ),
            ),
            ManualCase(
                "currency",
                { it.manualObservation(amount = Money.ofMinor(-8_000L, it.usd)) },
                { it.linkDepositEvidence() },
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH,
                    Rg06FieldPath.INPUT_PAYMENT_ID,
                ),
            ),
            ManualCase(
                "posting",
                { it.manualObservation() },
                { f -> f.linkDepositEvidence().copy(input = f.linkDepositEvidence().input.copy(postingId = PostingId("wrong-posting"))) },
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH,
                    Rg06FieldPath.INPUT_POSTING_ID,
                ),
            ),
        )

        cases.forEach { case ->
            val fixture = Rg06Fixture("manual-${case.name}")
            val key = Rg06ManualObservationKey(fixture.manualSourceId, fixture.manualEvidenceId)
            val observations = mutableMapOf<Rg06ManualObservationKey, Rg06ManualBankObservation>()
            case.observation(fixture)?.let { observations[key] = it }
            val port = fixture.port(observations)
            val executor = ExecuteRg06Operation(port)
            executor.execute(fixture.create()).accepted()
            executor.execute(fixture.recordDeposit()).accepted()
            val before = port.snapshot()
            assertEquals(case.expected, executor.execute(case.operation(fixture)), case.name)
            assertEquals(before, port.snapshot(), case.name)

            port.stageManualObservation(key, fixture.manualObservation())
            assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.linkDepositEvidence()), case.name)
        }
    }

    fun typedFactoriesCloseTimeCandidateConfidenceMagnitudeAndMirrorLineageStates() {
        val fixture = Rg06Fixture("typed-factories")
        val sourceTime = assertIs<Rg06TypedValueResult.Success<Rg06SourcePaymentAt>>(
            Rg06SourcePaymentAt.create(
                Instant.parse("2026-04-28T02:00:00Z"),
                "2026-04-28T10:00:00+08:00",
                fixture.expectedOffsetText,
            ),
        ).value
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME),
            Rg06SourcePaymentAt.create(
                sourceTime.instant,
                "2026-04-28T02:00:00Z",
                fixture.expectedOffsetText,
            ),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME),
            Rg06ObservedAt.create(
                sourceTime.instant,
                sourceTime.text,
                "invalid-policy",
            ),
        )

        val known = assertIs<Rg06TypedValueResult.Success<Rg06StagedPaymentCandidatePayload>>(
            Rg06StagedPaymentCandidatePayload.known(
                StagedPaymentRole.DEPOSIT,
                Money.ofMinor(-8_000L, fixture.cny),
                sourceTime,
                fixture.importDepositEvidenceId,
            ),
        ).value
        assertEquals(Rg06CandidateRoleFact.Known(StagedPaymentRole.DEPOSIT), known.roleFact)
        assertEquals(Rg06CandidateConfidence.CERTAIN, known.confidence)
        assertEquals(8_000L, known.amount.minorUnits)
        assertEquals(1, known.ruleVersion)
        assertEquals(RG06_CONFIRMATION_REQUIREMENTS, known.confirmationRequirements)
        val ambiguous = assertIs<Rg06TypedValueResult.Success<Rg06StagedPaymentCandidatePayload>>(
            Rg06StagedPaymentCandidatePayload.ambiguous(
                Money.ofMinor(8_000L, fixture.cny),
                sourceTime,
                fixture.importDepositEvidenceId,
            ),
        ).value
        assertEquals(Rg06CandidateConfidence.AMBIGUOUS, ambiguous.confidence)
        listOf(0L, Long.MIN_VALUE).forEach { invalid ->
            assertEquals(
                Rg06TypedValueResult.Failure(Rg06TypedValueFailure.AMOUNT_HAS_NO_POSITIVE_MAGNITUDE),
                Rg06StagedPaymentCandidatePayload.ambiguous(
                    Money.ofMinor(invalid, fixture.cny),
                    sourceTime,
                    fixture.importDepositEvidenceId,
                ),
            )
        }
        val candidate = Rg06StagedPaymentCandidate.pending(
            fixture.ledgerId,
            fixture.depositCandidateId,
            fixture.importDepositSourceId,
            known,
            Rg06CandidateStatusId("pending"),
        )
        assertEquals(known.confidence, candidate.confidence)

        val ordinary = Rg06BoundStagedPaymentEvidence.imported(
            fixture.ledgerId,
            fixture.importDepositEvidenceId,
            fixture.importDepositSourceId,
            sourceTime,
            fixture.depositPaymentId,
        )
        assertNull(ordinary.mirrorOfEvidenceId)
        assertNull(ordinary.mergedIntoEvidenceLinkId)
        val mirror = assertIs<Rg06TypedValueResult.Success<Rg06BoundStagedPaymentEvidence>>(
            Rg06BoundStagedPaymentEvidence.mirror(
                fixture.ledgerId,
                fixture.mirrorEvidenceId,
                fixture.mirrorSourceId,
                sourceTime,
                fixture.depositPaymentId,
                fixture.importDepositEvidenceId,
                fixture.importDepositLinkId,
            ),
        ).value
        assertEquals(fixture.importDepositEvidenceId, mirror.mirrorOfEvidenceId)
        assertEquals(fixture.importDepositLinkId, mirror.mergedIntoEvidenceLinkId)
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_MIRROR_EVIDENCE_LINEAGE),
            Rg06BoundStagedPaymentEvidence.mirror(
                fixture.ledgerId,
                fixture.mirrorEvidenceId,
                fixture.mirrorSourceId,
                sourceTime,
                fixture.depositPaymentId,
                fixture.mirrorEvidenceId,
                fixture.importDepositLinkId,
            ),
        )
        fun mirrorEvidence(
            ledgerId: LedgerId = fixture.ledgerId,
            id: Rg06EvidenceId = fixture.mirrorEvidenceId,
            sourceId: Rg06SourceId = fixture.mirrorSourceId,
            paymentId: InstallmentPaymentId = fixture.depositPaymentId,
            mirrorOfEvidenceId: Rg06EvidenceId = fixture.importDepositEvidenceId,
            linkId: Rg06EvidenceLinkId = fixture.importDepositLinkId,
        ) = Rg06BoundStagedPaymentEvidence.mirror(
            ledgerId, id, sourceId, sourceTime, paymentId, mirrorOfEvidenceId, linkId,
        )
        listOf(
            mirrorEvidence(ledgerId = LedgerId("")),
            mirrorEvidence(id = Rg06EvidenceId("")),
            mirrorEvidence(sourceId = Rg06SourceId("")),
            mirrorEvidence(paymentId = InstallmentPaymentId("")),
            mirrorEvidence(mirrorOfEvidenceId = Rg06EvidenceId("")),
            mirrorEvidence(linkId = Rg06EvidenceLinkId("")),
        ).forEach { result ->
            assertEquals(
                Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_MIRROR_EVIDENCE_LINEAGE),
                result,
            )
        }

        val observedAt = fixture.manualObservation().observedAt
        val manualSource = assertIs<Rg06TypedValueResult.Success<Rg06StagedPaymentBankSource>>(
            Rg06StagedPaymentBankSource.manual(
                fixture.ledgerId,
                fixture.manualSourceId,
                Money.ofMinor(-8_000L, fixture.cny),
                observedAt,
            ),
        ).value
        assertNull(manualSource.mirrorOfSourceId)
        val importedSource = assertIs<Rg06TypedValueResult.Success<Rg06StagedPaymentBankSource>>(
            Rg06StagedPaymentBankSource.importedOriginal(
                fixture.ledgerId,
                fixture.importDepositSourceId,
                Money.ofMinor(-8_000L, fixture.cny),
                sourceTime,
            ),
        ).value
        assertNull(importedSource.mirrorOfSourceId)
        val typedMirrorSource = assertIs<Rg06TypedValueResult.Success<Rg06StagedPaymentBankSource>>(
            Rg06StagedPaymentBankSource.mirror(
                fixture.ledgerId,
                fixture.mirrorSourceId,
                Money.ofMinor(8_000L, fixture.cny),
                sourceTime,
                fixture.importDepositSourceId,
            ),
        ).value
        assertEquals(fixture.importDepositSourceId, typedMirrorSource.mirrorOfSourceId)
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT),
            Rg06StagedPaymentBankSource.manual(
                fixture.ledgerId,
                fixture.manualSourceId,
                Money.ofMinor(-8_000L, fixture.cny),
                sourceTime,
            ),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT),
            Rg06ImmutableBankFactPayload.manual(Money.ofMinor(-8_000L, fixture.cny), sourceTime),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT),
            Rg06ImmutableBankFactPayload.imported(Money.ofMinor(-8_000L, fixture.cny), observedAt),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT),
            Rg06StagedPaymentBankSource.importedOriginal(
                fixture.ledgerId,
                fixture.importDepositSourceId,
                Money.ofMinor(-8_000L, fixture.cny),
                observedAt,
            ),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_TIME_VARIANT),
            Rg06StagedPaymentBankSource.mirror(
                fixture.ledgerId,
                fixture.mirrorSourceId,
                Money.ofMinor(8_000L, fixture.cny),
                observedAt,
                fixture.importDepositSourceId,
            ),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_MIRROR_SOURCE_ID),
            Rg06StagedPaymentBankSource.mirror(
                fixture.ledgerId,
                fixture.mirrorSourceId,
                Money.ofMinor(8_000L, fixture.cny),
                sourceTime,
                fixture.mirrorSourceId,
            ),
        )
        assertEquals(
            Rg06TypedValueResult.Failure(Rg06TypedValueFailure.INVALID_MIRROR_SOURCE_ID),
            Rg06StagedPaymentBankSource.mirror(
                fixture.ledgerId,
                fixture.mirrorSourceId,
                Money.ofMinor(8_000L, fixture.cny),
                sourceTime,
                Rg06SourceId(""),
            ),
        )
    }

    fun candidateConfirmationRejectsDuplicateStatusIdentityWithoutChangingCandidate() {
        val fixture = Rg06Fixture("duplicate-candidate-status")
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        executor.execute(fixture.create()).accepted()
        val ingest = fixture.ingestDeposit()
        executor.execute(ingest).accepted()
        val before = port.snapshot()
        val candidateBefore = port.candidate(fixture.depositCandidateId)
        assertEquals(
            Rg06TypedValueResult.Failure(
                Rg06TypedValueFailure.CANDIDATE_STATUS_IDENTITY_COLLISION,
            ),
            candidateBefore.confirm(ingest.ids.pendingStatusId),
        )
        val duplicate = fixture.confirmDepositCandidate().copy(
            ids = fixture.confirmDepositCandidate().ids.copy(
                confirmedStatusId = ingest.ids.pendingStatusId,
            ),
        )
        val invalidFundingAndDuplicateStatus = duplicate.copy(
            input = duplicate.input.copy(fundingAccountId = fixture.unknownAccountId),
        )

        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.UNKNOWN_REAL_ACCOUNT,
                Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID,
            ),
            executor.execute(invalidFundingAndDuplicateStatus),
        )
        assertEquals(before, port.snapshot())
        assertEquals(candidateBefore, port.candidate(fixture.depositCandidateId))
        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.IDENTITY_COLLISION,
                Rg06FieldPath.GENERATED_IDENTITY,
            ),
            executor.execute(duplicate),
        )
        assertEquals(before, port.snapshot())
        assertEquals(candidateBefore, port.candidate(fixture.depositCandidateId))
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.confirmDepositCandidate()))
    }

    fun frozenEighteenRejectionsUseExactReasonPathPrecedenceZeroWritesAndReusableIdentity() {
        val cases = frozenRejectionCases()
        assertEquals(18, cases.size)
        cases.forEach { case ->
            val fixture = Rg06Fixture(case.name)
            val port = portFactory(fixture)
            val executor = ExecuteRg06Operation(port)
            case.prepare(fixture, executor)
            val operation = case.operation(fixture)
            val before = port.snapshot()
            assertEquals(
                Rg06ExecutionResult.Rejected(case.reason, case.path),
                executor.execute(operation),
                case.name,
            )
            assertEquals(before, port.snapshot(), case.name)
            case.correctAndReuse(fixture, executor, operation)
        }

        val fixture = Rg06Fixture("precedence")
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.create()))
        val combined = listOf(
            fixture.recordDeposit().copy(
                input = fixture.recordDeposit().input.copy(
                    paymentAmount = Money.ofMinor(0, fixture.cny),
                    fundingAccountId = fixture.unknownAccountId,
                ),
            ) to Rg06ExecutionResult.Rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT),
            fixture.recordDeposit().copy(
                input = fixture.recordDeposit().input.copy(
                    paymentAmount = Money.ofMinor(30_000, fixture.cny),
                    fundingAccountId = fixture.unknownAccountId,
                ),
            ) to Rg06ExecutionResult.Rejected(Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT),
            fixture.recordDeposit().copy(
                input = fixture.recordDeposit().input.copy(
                    paymentAmount = Money.ofMinor(8_000, fixture.usd),
                    fundingAccountId = fixture.unknownAccountId,
                ),
            ) to Rg06ExecutionResult.Rejected(Rg06RejectionReason.SINGLE_CURRENCY_REQUIRED, Rg06FieldPath.ATTEMPTED_CURRENCY),
        )
        combined.forEachIndexed { index, (operation, expected) ->
            val distinct = operation.copy(input = operation.input.copy(requestId = RequestId("combined-$index")))
            val before = port.snapshot()
            assertEquals(expected, executor.execute(distinct))
            assertEquals(before, port.snapshot())
        }
    }

    fun generatedIdentityCollisionsAndCrossLedgerOwnershipRejectAtomically() {
        val fixture = Rg06Fixture()
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.create()))
        val before = port.snapshot()
        assertEquals(
            Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY),
            executor.execute(fixture.create().copy(input = fixture.create().input.copy(requestId = RequestId("other-create")))),
        )
        assertEquals(before, port.snapshot())

        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(fixture.ingestDeposit()))
        val beforeCandidateCollision = port.snapshot()
        val candidateCollision = fixture.ingestFinal().copy(
            ids = fixture.ingestDeposit().ids,
        )
        assertEquals(
            Rg06ExecutionResult.Rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY),
            executor.execute(candidateCollision),
        )
        assertEquals(beforeCandidateCollision, port.snapshot())

        val otherLedger = LedgerId("ledger-other")
        val crossLedger = fixture.recordDeposit().copy(ledgerId = otherLedger)
        assertEquals(
            Rg06ExecutionResult.Rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID),
            executor.execute(crossLedger),
        )
        assertEquals(beforeCandidateCollision, port.snapshot())
    }

    fun everyGeneratedIdentityCategoryCollisionIsAtomic() {
        data class Occurrence(val field: String, val key: Pair<String, String>)
        data class Branch(
            val action: Rg06Action,
            val operation: (Rg06Fixture) -> Rg06Operation,
            val prepare: (Rg06Fixture, ExecuteRg06Operation) -> Unit,
            val occurrences: (Rg06Operation) -> List<Occurrence>,
            val corrected: (Rg06Operation, String) -> Rg06Operation,
        )
        val none: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { _, _ -> }
        val created: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> e.execute(f.create()).accepted() }
        val recorded: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e ->
            created(f, e)
            e.execute(f.recordDeposit()).accepted()
        }
        val completed: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e ->
            recorded(f, e)
            e.execute(f.recordFinal()).accepted()
        }
        val ingested: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e ->
            created(f, e)
            e.execute(f.ingestDeposit()).accepted()
        }
        val confirmed: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e ->
            ingested(f, e)
            e.execute(f.confirmDepositCandidate()).accepted()
        }
        fun paymentOccurrences(ids: StagedPaymentInstallmentIds) = listOf(
            Occurrence("payment", "payment" to ids.paymentId.value),
            Occurrence("history", "history" to ids.historyId.value),
            Occurrence("transaction", "transaction" to ids.expenseIds.transactionId.value),
            Occurrence("version", "version" to ids.expenseIds.versionId.value),
            Occurrence("posting_set", "posting_set" to ids.expenseIds.postingSetId.value),
            Occurrence("expense_posting", "posting" to ids.expenseIds.expensePostingId.value),
            Occurrence("asset_posting", "posting" to ids.expenseIds.paymentPostingId.value),
        )
        fun correctedPayment(ids: StagedPaymentInstallmentIds, field: String): StagedPaymentInstallmentIds = when (field) {
            "payment" -> ids.copy(paymentId = InstallmentPaymentId("${ids.paymentId.value}-corrected"))
            "history" -> ids.copy(historyId = StagedPaymentHistoryId("${ids.historyId.value}-corrected"))
            "transaction" -> ids.copy(expenseIds = ids.expenseIds.copy(transactionId = TransactionId("${ids.expenseIds.transactionId.value}-corrected")))
            "version" -> ids.copy(expenseIds = ids.expenseIds.copy(versionId = TransactionVersionId("${ids.expenseIds.versionId.value}-corrected")))
            "posting_set" -> ids.copy(expenseIds = ids.expenseIds.copy(postingSetId = PostingSetId("${ids.expenseIds.postingSetId.value}-corrected")))
            "expense_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(expensePostingId = PostingId("${ids.expenseIds.expensePostingId.value}-corrected")))
            "asset_posting" -> ids.copy(expenseIds = ids.expenseIds.copy(paymentPostingId = PostingId("${ids.expenseIds.paymentPostingId.value}-corrected")))
            else -> ids
        }
        val branches = listOf(
            Branch(Rg06Action.CREATE_STAGED_PAYMENT, { it.create() }, none, { operation ->
                val op = operation as Rg06Operation.CreateStagedPayment
                listOf(
                    Occurrence("relation", "relation" to op.ids.relationId.value),
                    Occurrence("lifecycle", "lifecycle" to op.ids.lifecycleId.value),
                    Occurrence("history", "history" to op.ids.historyId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.CreateStagedPayment
                op.copy(ids = when (field) {
                    "relation" -> op.ids.copy(relationId = StagedPaymentRelationId("${op.ids.relationId.value}-corrected"))
                    "lifecycle" -> op.ids.copy(lifecycleId = StagedPaymentLifecycleId("${op.ids.lifecycleId.value}-corrected"))
                    else -> op.ids.copy(historyId = StagedPaymentHistoryId("${op.ids.historyId.value}-corrected"))
                })
            }),
            Branch(Rg06Action.RECORD_STAGED_PAYMENT_INSTALLMENT, { it.recordDeposit() }, created, { operation ->
                val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                listOf(
                    Occurrence("confirmation", "confirmation" to op.ids.confirmationId.value),
                    Occurrence("reconciliation", "reconciliation" to op.ids.reconciliationId.value),
                ) + paymentOccurrences(op.ids.paymentIds)
            }, { operation, field ->
                val op = operation as Rg06Operation.RecordStagedPaymentInstallment
                op.copy(ids = when (field) {
                    "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                    "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                    else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                })
            }),
            Branch(Rg06Action.CHANGE_STAGED_PAYMENT_FULFILLMENT, { it.fulfill() }, recorded, { operation ->
                val op = operation as Rg06Operation.ChangeStagedPaymentFulfillment
                listOf(Occurrence("history", "history" to op.historyId.value))
            }, { operation, _ ->
                val op = operation as Rg06Operation.ChangeStagedPaymentFulfillment
                op.copy(historyId = StagedPaymentHistoryId("${op.historyId.value}-corrected"))
            }),
            Branch(Rg06Action.CONFIRM_STAGED_PAYMENT_COMPLETION, { it.complete() }, completed, { operation ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCompletion
                listOf(Occurrence("history", "history" to op.historyId.value))
            }, { operation, _ ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCompletion
                op.copy(historyId = StagedPaymentHistoryId("${op.historyId.value}-corrected"))
            }),
            Branch(Rg06Action.LINK_STAGED_PAYMENT_EVIDENCE, { it.linkDepositEvidence() }, recorded, { operation ->
                val op = operation as Rg06Operation.LinkStagedPaymentEvidence
                listOf(
                    Occurrence("source", "source" to op.input.sourceId.value),
                    Occurrence("evidence", "evidence" to op.input.evidenceId.value),
                    Occurrence("link", "link" to op.evidenceLinkId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.LinkStagedPaymentEvidence
                when (field) {
                    "source" -> op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                    "evidence" -> op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                    else -> op.copy(evidenceLinkId = Rg06EvidenceLinkId("${op.evidenceLinkId.value}-corrected"))
                }
            }),
            Branch(Rg06Action.INGEST_STAGED_PAYMENT_BANK_FACT, { it.ingestDeposit() }, none, { operation ->
                val op = operation as Rg06Operation.IngestStagedPaymentBankFact
                listOf(
                    Occurrence("source", "source" to op.input.sourceId.value),
                    Occurrence("evidence", "evidence" to op.input.evidenceId.value),
                    Occurrence("candidate", "candidate" to op.ids.candidateId.value),
                    Occurrence("candidate_status", "candidate_status" to op.ids.pendingStatusId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.IngestStagedPaymentBankFact
                when (field) {
                    "source" -> op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                    "evidence" -> op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                    "candidate" -> op.copy(ids = op.ids.copy(candidateId = Rg06CandidateId("${op.ids.candidateId.value}-corrected")))
                    else -> op.copy(ids = op.ids.copy(pendingStatusId = Rg06CandidateStatusId("${op.ids.pendingStatusId.value}-corrected")))
                }
            }),
            Branch(Rg06Action.CONFIRM_STAGED_PAYMENT_CANDIDATE, { it.confirmDepositCandidate() }, ingested, { operation ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCandidate
                listOf(
                    Occurrence("confirmation", "confirmation" to op.ids.confirmationId.value),
                    Occurrence("link", "link" to op.ids.evidenceLinkId.value),
                    Occurrence("candidate_status", "candidate_status" to op.ids.confirmedStatusId.value),
                    Occurrence("reconciliation", "reconciliation" to op.ids.reconciliationId.value),
                ) + paymentOccurrences(op.ids.paymentIds)
            }, { operation, field ->
                val op = operation as Rg06Operation.ConfirmStagedPaymentCandidate
                op.copy(ids = when (field) {
                    "confirmation" -> op.ids.copy(confirmationId = Rg06ConfirmationId("${op.ids.confirmationId.value}-corrected"))
                    "link" -> op.ids.copy(evidenceLinkId = Rg06EvidenceLinkId("${op.ids.evidenceLinkId.value}-corrected"))
                    "candidate_status" -> op.ids.copy(confirmedStatusId = Rg06CandidateStatusId("${op.ids.confirmedStatusId.value}-corrected"))
                    "reconciliation" -> op.ids.copy(reconciliationId = Rg06ReconciliationId("${op.ids.reconciliationId.value}-corrected"))
                    else -> op.ids.copy(paymentIds = correctedPayment(op.ids.paymentIds, field))
                })
            }),
            Branch(Rg06Action.MERGE_STAGED_PAYMENT_MIRROR_EVIDENCE, { it.mergeDepositMirror() }, confirmed, { operation ->
                val op = operation as Rg06Operation.MergeStagedPaymentMirrorEvidence
                listOf(
                    Occurrence("source", "source" to op.input.sourceId.value),
                    Occurrence("evidence", "evidence" to op.input.evidenceId.value),
                )
            }, { operation, field ->
                val op = operation as Rg06Operation.MergeStagedPaymentMirrorEvidence
                if (field == "source") {
                    op.copy(input = op.input.copy(sourceId = Rg06SourceId("${op.input.sourceId.value}-corrected")))
                } else {
                    op.copy(input = op.input.copy(evidenceId = Rg06EvidenceId("${op.input.evidenceId.value}-corrected")))
                }
            }),
        )
        assertEquals(Rg06Action.entries, branches.map { it.action })
        val cases = branches.flatMap { branch ->
            branch.occurrences(branch.operation(Rg06Fixture("registry-${branch.action.code}"))).map { branch to it }
        }
        assertEquals(34, cases.size)
        assertEquals(
            listOf(3, 9, 1, 1, 3, 4, 11, 2),
            branches.mapIndexed { index, branch ->
                branch.occurrences(branch.operation(Rg06Fixture("count-$index"))).size
            },
        )

        cases.forEach { (branch, occurrence) ->
            val fixture = Rg06Fixture("collision-${branch.action.code}-${occurrence.field}")
            val port = portFactory(fixture)
            val executor = ExecuteRg06Operation(port)
            branch.prepare(fixture, executor)
            val operation = branch.operation(fixture)
            val actualOccurrence = branch.occurrences(operation).single { it.field == occurrence.field }
            val (kind, value) = actualOccurrence.key
            port.occupyGeneratedIdentity(kind, value)
            val before = port.snapshot()
            assertEquals(
                Rg06ExecutionResult.Rejected(
                    Rg06RejectionReason.IDENTITY_COLLISION,
                    Rg06FieldPath.GENERATED_IDENTITY,
                ),
                executor.execute(operation),
                "${branch.action.code}.${occurrence.field}",
            )
            assertEquals(before, port.snapshot(), "${branch.action.code}.${occurrence.field}")

            val corrected = branch.corrected(operation, occurrence.field)
            assertNotEquals(operation, corrected)
            if (corrected is Rg06Operation.LinkStagedPaymentEvidence) {
                port.stageManualObservation(
                    Rg06ManualObservationKey(corrected.input.sourceId, corrected.input.evidenceId),
                    fixture.manualObservation(),
                )
            }
            assertIs<Rg06ExecutionResult.Accepted>(
                executor.execute(corrected),
                "${branch.action.code}.${occurrence.field}.corrected",
            )
        }
    }

    fun frozenSemanticFailurePrecedesOccupiedProposalIdentityAndCorrectionReusesIdentity() {
        val fixture = Rg06Fixture("semantic-before-generated")
        val port = portFactory(fixture)
        val executor = ExecuteRg06Operation(port)
        executor.execute(fixture.create()).accepted()
        val requestId = RequestId("request-correctable")
        val invalidWithOccupiedIds = fixture.create().copy(
            input = fixture.create().input.copy(
                requestId = requestId,
                totalAmount = Money.ofMinor(0L, fixture.cny),
            ),
        )
        val before = port.snapshot()

        assertEquals(
            Rg06ExecutionResult.Rejected(
                Rg06RejectionReason.MUST_BE_POSITIVE,
                Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT,
            ),
            executor.execute(invalidWithOccupiedIds),
        )
        assertEquals(before, port.snapshot())

        val corrected = invalidWithOccupiedIds.copy(
            input = invalidWithOccupiedIds.input.copy(totalAmount = Money.ofMinor(30_000L, fixture.cny)),
            ids = StagedPaymentCreationIds(
                StagedPaymentRelationId("relation-corrected"),
                StagedPaymentLifecycleId("lifecycle-corrected"),
                StagedPaymentHistoryId("history-corrected"),
            ),
        )
        assertIs<Rg06ExecutionResult.Accepted>(executor.execute(corrected))
    }

    fun explicitGuardsRemainSeparateFromFrozenRejectionsAndNeverCallPort() {
        var calls = 0
        val fixture = Rg06Fixture()
        val executor = ExecuteRg06Operation {
            calls += 1
            Rg06ExecutionResult.Accepted(emptyList())
        }
        assertEquals(
            Rg06ExecutionResult.Rejected(Rg06RejectionReason.EXPLICIT_CONFIRMATION_REQUIRED, Rg06FieldPath.INPUT_CONFIRMED),
            executor.execute(fixture.complete().copy(input = fixture.complete().input.copy(confirmed = false))),
        )
        assertEquals(
            Rg06ExecutionResult.Rejected(Rg06RejectionReason.EXACT_BINDING_CONFIRMATION_REQUIRED, Rg06FieldPath.INPUT_EXACT_BINDING_CONFIRMED),
            executor.execute(
                fixture.confirmDepositCandidate().copy(
                    input = fixture.confirmDepositCandidate().input.copy(exactBindingConfirmed = false),
                ),
            ),
        )
        assertEquals(0, calls)
    }
}

private data class ExactActionCase(
    val name: String,
    val operationFor: (Rg06Fixture) -> Rg06Operation,
    val prepare: (Rg06Fixture, ExecuteRg06Operation) -> Unit,
    val expectedIds: (Rg06Fixture) -> List<Rg06ReturnedId>,
    val changedProposal: (Rg06Operation) -> Rg06Operation,
) {
    val operation: Rg06Operation get() = operationFor(Rg06Fixture("registry-$name"))
}

private fun exactActionCases(): List<ExactActionCase> {
    val none: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { _, _ -> }
    val create: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> e.execute(f.create()).accepted() }
    val deposit: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> create(f, e); e.execute(f.recordDeposit()).accepted() }
    val final: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> deposit(f, e); e.execute(f.recordFinal()).accepted() }
    val ingest: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> create(f, e); e.execute(f.ingestDeposit()).accepted() }
    val confirm: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> ingest(f, e); e.execute(f.confirmDepositCandidate()).accepted() }
    return listOf(
        ExactActionCase("create", { it.create() }, none, { listOf(Rg06ReturnedId.Relation(it.relationId), Rg06ReturnedId.Lifecycle(it.lifecycleId)) }, {
            (it as Rg06Operation.CreateStagedPayment).copy(ids = it.ids.copy(historyId = StagedPaymentHistoryId("proposal-history")))
        }),
        ExactActionCase("record", { it.recordDeposit() }, create, { listOf(Rg06ReturnedId.Confirmation(it.manualDepositConfirmationId), Rg06ReturnedId.Transaction(it.depositTransactionId), Rg06ReturnedId.Payment(it.depositPaymentId)) }, {
            (it as Rg06Operation.RecordStagedPaymentInstallment).copy(ids = it.ids.copy(confirmationId = Rg06ConfirmationId("proposal-confirmation")))
        }),
        ExactActionCase("fulfillment", { it.fulfill() }, deposit, { listOf(Rg06ReturnedId.Lifecycle(it.lifecycleId)) }, {
            (it as Rg06Operation.ChangeStagedPaymentFulfillment).copy(historyId = StagedPaymentHistoryId("proposal-history"))
        }),
        ExactActionCase("completion", { it.complete() }, final, { listOf(Rg06ReturnedId.Lifecycle(it.lifecycleId)) }, {
            (it as Rg06Operation.ConfirmStagedPaymentCompletion).copy(historyId = StagedPaymentHistoryId("proposal-history"))
        }),
        ExactActionCase("link", { it.linkDepositEvidence() }, deposit, { listOf(Rg06ReturnedId.Source(it.manualSourceId), Rg06ReturnedId.Evidence(it.manualEvidenceId), Rg06ReturnedId.EvidenceLink(it.manualLinkId)) }, {
            (it as Rg06Operation.LinkStagedPaymentEvidence).copy(evidenceLinkId = Rg06EvidenceLinkId("proposal-link"))
        }),
        ExactActionCase("ingest", { it.ingestDeposit() }, none, { listOf(Rg06ReturnedId.Source(it.importDepositSourceId), Rg06ReturnedId.Evidence(it.importDepositEvidenceId), Rg06ReturnedId.Candidate(it.depositCandidateId)) }, {
            (it as Rg06Operation.IngestStagedPaymentBankFact).copy(ids = Rg06IngestCommitIds(Rg06CandidateId("proposal-candidate"), Rg06CandidateStatusId("proposal-status")))
        }),
        ExactActionCase("confirm", { it.confirmDepositCandidate() }, ingest, { listOf(Rg06ReturnedId.Confirmation(it.importDepositConfirmationId), Rg06ReturnedId.Transaction(it.depositTransactionId), Rg06ReturnedId.Payment(it.depositPaymentId), Rg06ReturnedId.EvidenceLink(it.importDepositLinkId)) }, {
            (it as Rg06Operation.ConfirmStagedPaymentCandidate).copy(ids = it.ids.copy(confirmedStatusId = Rg06CandidateStatusId("proposal-status")))
        }),
        ExactActionCase("mirror", { it.mergeDepositMirror() }, confirm, { listOf(Rg06ReturnedId.Source(it.mirrorSourceId), Rg06ReturnedId.Evidence(it.mirrorEvidenceId)) }, { it }),
    )
}

private data class FrozenCase(
    val name: String,
    val reason: Rg06RejectionReason,
    val path: Rg06FieldPath,
    val prepare: (Rg06Fixture, ExecuteRg06Operation) -> Unit,
    val operation: (Rg06Fixture) -> Rg06Operation,
    val correctAndReuse: (Rg06Fixture, ExecuteRg06Operation, Rg06Operation) -> Unit,
)

private fun frozenRejectionCases(): List<FrozenCase> {
    val none: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { _, _ -> }
    val create: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> e.execute(f.create()).accepted() }
    val deposited: (Rg06Fixture, ExecuteRg06Operation) -> Unit = { f, e -> create(f, e); e.execute(f.recordDeposit()).accepted() }
    fun createCase(name: String, total: Long = 30_000, category: (Rg06Fixture) -> CategoryId?, reason: Rg06RejectionReason) =
        FrozenCase(name, reason, if (reason == Rg06RejectionReason.MUST_BE_POSITIVE) Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT else Rg06FieldPath.ATTEMPTED_CATEGORY_ID, none, { f ->
            f.create().copy(input = f.create().input.copy(requestId = RequestId("request-$name"), totalAmount = Money.ofMinor(total, f.cny), categoryId = category(f)))
        }, { f, e, op -> e.execute(f.create().copy(input = f.create().input.copy(requestId = op.identity.requestId()))).accepted() })
    fun depositCase(name: String, amount: (Rg06Fixture) -> Money, account: (Rg06Fixture) -> AccountId = { it.fundingAccountId }, reason: Rg06RejectionReason, path: Rg06FieldPath = Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT) =
        FrozenCase(name, reason, path, create, { f -> f.recordDeposit().copy(input = f.recordDeposit().input.copy(requestId = RequestId("request-$name"), paymentAmount = amount(f), fundingAccountId = account(f))) }, { f, e, op -> e.execute(f.recordDeposit().copy(input = f.recordDeposit().input.copy(requestId = op.identity.requestId()))).accepted() })
    fun finalCase(name: String, amount: Long, reason: Rg06RejectionReason) =
        FrozenCase(name, reason, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT, deposited, { f -> f.recordFinal().copy(input = f.recordFinal().input.copy(requestId = RequestId("request-$name"), paymentAmount = Money.ofMinor(amount, f.cny))) }, { f, e, op -> e.execute(f.recordFinal().copy(input = f.recordFinal().input.copy(requestId = op.identity.requestId()))).accepted() })
    return listOf(
        createCase("zero-total", 0, { it.categoryId }, Rg06RejectionReason.MUST_BE_POSITIVE),
        createCase("negative-total", -100, { it.categoryId }, Rg06RejectionReason.MUST_BE_POSITIVE),
        createCase("null-category", category = { null }, reason = Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED),
        createCase("primary-category", category = { it.primaryExpenseCategoryId }, reason = Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED),
        createCase("inactive-category", category = { it.inactiveCategoryId }, reason = Rg06RejectionReason.CATEGORY_INACTIVE),
        createCase("income-category", category = { it.incomeCategoryId }, reason = Rg06RejectionReason.EXPENSE_CATEGORY_REQUIRED),
        depositCase("zero-payment", { Money.ofMinor(0, it.cny) }, reason = Rg06RejectionReason.MUST_BE_POSITIVE),
        depositCase("negative-payment", { Money.ofMinor(-100, it.cny) }, reason = Rg06RejectionReason.MUST_BE_POSITIVE),
        depositCase("deposit-equals", { Money.ofMinor(30_000, it.cny) }, reason = Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL),
        depositCase("deposit-exceeds", { Money.ofMinor(30_100, it.cny) }, reason = Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL),
        finalCase("final-exceeds", 22_100, Rg06RejectionReason.PAYMENT_EXCEEDS_DUE),
        finalCase("final-below", 21_900, Rg06RejectionReason.FINAL_MUST_EQUAL_REMAINING_DUE),
        depositCase("currency", { Money.ofMinor(8_000, it.usd) }, reason = Rg06RejectionReason.SINGLE_CURRENCY_REQUIRED, path = Rg06FieldPath.ATTEMPTED_CURRENCY),
        depositCase("unknown", { Money.ofMinor(8_000, it.cny) }, { it.unknownAccountId }, Rg06RejectionReason.UNKNOWN_REAL_ACCOUNT, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
        depositCase("nonfinancial", { Money.ofMinor(8_000, it.cny) }, { it.nonFinancialAccountId }, Rg06RejectionReason.REAL_FINANCIAL_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
        depositCase("external", { Money.ofMinor(8_000, it.cny) }, { it.externalAccountId }, Rg06RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
        depositCase("liability", { Money.ofMinor(8_000, it.cny) }, { it.liabilityAccountId }, Rg06RejectionReason.ASSET_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID),
        FrozenCase("due", Rg06RejectionReason.DUE_MUST_BE_ZERO, Rg06FieldPath.ATTEMPTED_PAYMENT_PROGRESS, create, { f -> f.complete().copy(input = f.complete().input.copy(requestId = RequestId("request-due"))) }, { f, e, op ->
            e.execute(f.recordDeposit()).accepted(); e.execute(f.recordFinal()).accepted(); e.execute(f.complete().copy(input = f.complete().input.copy(requestId = op.identity.requestId()))).accepted()
        }),
    )
}

private class Rg06ApplicationReferencePort(
    private val catalog: LedgerCatalog,
    private val expectedSourceOffsetText: String,
    manualObservations: Map<Rg06ManualObservationKey, Rg06ManualBankObservation>,
) : Rg06ApplicationReferenceProbe {
    private data class Receipt(val action: Rg06Action, val input: Any, val ids: List<Rg06ReturnedId>)
    private data class Link(val id: Rg06EvidenceLinkId, val evidenceId: Rg06EvidenceId, val paymentId: InstallmentPaymentId, val postingId: PostingId)
    private data class Reconciliation(val postingId: PostingId, val status: String)
    data class Snapshot(
        val receiptCount: Int,
        val aggregates: Map<StagedPaymentRelationId, List<InstallmentPayment>>,
        val sources: Map<Rg06SourceId, Rg06StagedPaymentBankSource>,
        val evidence: Map<Rg06EvidenceId, Rg06StagedPaymentEvidence>,
        val candidates: Map<Rg06CandidateId, Rg06StagedPaymentCandidate>,
        val links: Map<Rg06EvidenceLinkId, String>,
        val reconciliations: Map<Rg06ReconciliationId, Pair<PostingId, String>>,
        val identities: Set<Pair<String, String>>,
    )

    private val receipts = mutableMapOf<Rg06OperationIdentity, Receipt>()
    private val aggregates = mutableMapOf<StagedPaymentRelationId, StagedPayment>()
    private val sources = mutableMapOf<Rg06SourceId, Rg06StagedPaymentBankSource>()
    private val evidence = mutableMapOf<Rg06EvidenceId, Rg06StagedPaymentEvidence>()
    private val candidates = mutableMapOf<Rg06CandidateId, Rg06StagedPaymentCandidate>()
    private val links = mutableMapOf<Rg06EvidenceLinkId, Link>()
    private val reconciliations = mutableMapOf<Rg06ReconciliationId, Reconciliation>()
    private val identities = mutableMapOf<Pair<String, String>, LedgerId>()
    private val manualObservations = manualObservations.toMutableMap()

    override fun commit(operation: Rg06Operation): Rg06ExecutionResult {
        receipts[operation.identity]?.let { receipt ->
            return if (receipt.action == operation.action && receipt.input == canonicalInput(operation)) {
                Rg06ExecutionResult.NoChange(receipt.ids)
            } else Rg06ExecutionResult.RequestIdentityConflict
        }
        val aggregatesBefore = aggregates.toMap()
        val sourcesBefore = sources.toMap()
        val evidenceBefore = evidence.toMap()
        val candidatesBefore = candidates.toMap()
        val linksBefore = links.toMap()
        val reconciliationsBefore = reconciliations.toMap()
        fun restoreAttemptState() {
            aggregates.replaceWith(aggregatesBefore)
            sources.replaceWith(sourcesBefore)
            evidence.replaceWith(evidenceBefore)
            candidates.replaceWith(candidatesBefore)
            links.replaceWith(linksBefore)
            reconciliations.replaceWith(reconciliationsBefore)
        }
        val result = when (operation) {
            is Rg06Operation.CreateStagedPayment -> create(operation)
            is Rg06Operation.RecordStagedPaymentInstallment -> record(operation)
            is Rg06Operation.ChangeStagedPaymentFulfillment -> fulfill(operation)
            is Rg06Operation.ConfirmStagedPaymentCompletion -> complete(operation)
            is Rg06Operation.LinkStagedPaymentEvidence -> link(operation)
            is Rg06Operation.IngestStagedPaymentBankFact -> ingest(operation)
            is Rg06Operation.ConfirmStagedPaymentCandidate -> confirm(operation)
            is Rg06Operation.MergeStagedPaymentMirrorEvidence -> mirror(operation)
        }
        if (result is Rg06ExecutionResult.Accepted) {
            val collision = generatedKeys(operation).firstOrNull { identities.containsKey(it) }
            if (collision != null) {
                restoreAttemptState()
                return rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY)
            }
            receipts[operation.identity] = Receipt(operation.action, canonicalInput(operation), result.returnedIds)
            generatedKeys(operation).forEach { identities[it] = operation.ledgerId }
        } else {
            restoreAttemptState()
        }
        return result
    }

    override fun snapshot(): Any = Snapshot(
        receipts.size,
        aggregates.mapValues { it.value.installments },
        sources.toMap(), evidence.toMap(), candidates.toMap(),
        links.mapValues { it.value.evidenceId.value },
        reconciliations.mapValues { it.value.postingId to it.value.status },
        identities.keys.toSet(),
    )
    override fun source(id: Rg06SourceId) = checkNotNull(sources[id])
    override fun evidence(id: Rg06EvidenceId) = checkNotNull(evidence[id])
    override fun candidate(id: Rg06CandidateId) = checkNotNull(candidates[id])
    override fun payment(id: InstallmentPaymentId) = aggregates.values.flatMap { it.installments }.single { it.id == id }
    override fun linkCount() = links.size
    override fun reconciliationState(): Any = reconciliations.mapValues { it.value.postingId to it.value.status }
    override fun occupyGeneratedIdentity(kind: String, value: String) {
        identities[kind to value] = LedgerId("occupied")
    }
    override fun stageManualObservation(
        key: Rg06ManualObservationKey,
        observation: Rg06ManualBankObservation,
    ) {
        manualObservations[key] = observation
    }

    private fun create(op: Rg06Operation.CreateStagedPayment): Rg06ExecutionResult {
        val categoryRejection = validateCreate(op)
        if (categoryRejection != null) return categoryRejection
        val result = createStagedPayment(catalog, CreateStagedPaymentCommand(op.ledgerId, op.input.totalAmount, checkNotNull(op.input.categoryId), op.input.createdAt), op.ids)
        val aggregate = (result as? StagedPaymentResult.Success)?.value ?: return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        aggregates[aggregate.relation.id] = aggregate
        return accepted(op)
    }

    private fun record(op: Rg06Operation.RecordStagedPaymentInstallment): Rg06ExecutionResult {
        val aggregate = aggregates[op.input.relationId]
            ?: return rejected(Rg06RejectionReason.RELATION_NOT_FOUND, Rg06FieldPath.INPUT_RELATION_ID)
        if (aggregate.ledgerId != op.ledgerId) return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID)
        validateInstallment(aggregate, op.input)?.let { return it }
        val result = aggregate.recordInstallment(catalog, RecordStagedPaymentInstallmentCommand(op.input.paymentRole, op.input.paymentAmount, op.input.fundingAccountId, op.input.actualPaymentAt), op.ids.paymentIds)
        val updated = (result as? StagedPaymentResult.Success)?.value ?: return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        aggregates[op.input.relationId] = updated
        reconciliations[op.ids.reconciliationId] = Reconciliation(
            op.ids.paymentIds.expenseIds.paymentPostingId,
            "pending",
        )
        return accepted(op)
    }

    private fun fulfill(op: Rg06Operation.ChangeStagedPaymentFulfillment): Rg06ExecutionResult {
        val aggregate = ownedAggregate(op.ledgerId, op.input.relationId) ?: return crossOrMissing(op.ledgerId, op.input.relationId)
        val result = aggregate.changeFulfillment(op.historyId, op.input.fulfillmentStatus, op.input.occurredAt)
        val updated = (result as? StagedPaymentResult.Success)?.value ?: return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.INPUT_RELATION_ID)
        aggregates[op.input.relationId] = updated
        return accepted(op)
    }

    private fun complete(op: Rg06Operation.ConfirmStagedPaymentCompletion): Rg06ExecutionResult {
        val aggregate = ownedAggregate(op.ledgerId, op.input.relationId) ?: return crossOrMissing(op.ledgerId, op.input.relationId)
        if (aggregate.lifecycle.dueAmount.minorUnits != 0L) return rejected(Rg06RejectionReason.DUE_MUST_BE_ZERO, Rg06FieldPath.ATTEMPTED_PAYMENT_PROGRESS)
        val result = aggregate.confirmCompletion(op.historyId, op.input.occurredAt)
        val updated = (result as? StagedPaymentResult.Success)?.value ?: return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.INPUT_RELATION_ID)
        aggregates[op.input.relationId] = updated
        return accepted(op)
    }

    private fun link(op: Rg06Operation.LinkStagedPaymentEvidence): Rg06ExecutionResult {
        val payment = findPayment(op.ledgerId, op.input.paymentId) ?: return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.assetPostingId != op.input.postingId) return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_POSTING_ID)
        val observation = manualObservations[Rg06ManualObservationKey(op.input.sourceId, op.input.evidenceId)]
            ?: return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_EVIDENCE_ID)
        val magnitude = positiveMagnitude(observation.amount)
            ?: return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        if (magnitude != payment.amount) {
            return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_PAYMENT_ID)
        }
        val source = (Rg06StagedPaymentBankSource.manual(
            op.ledgerId, op.input.sourceId, observation.amount, observation.observedAt,
        ) as? Rg06TypedValueResult.Success)?.value
            ?: return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_SOURCE_ID)
        sources[op.input.sourceId] = source
        evidence[op.input.evidenceId] = Rg06BoundStagedPaymentEvidence.manual(
            op.ledgerId, op.input.evidenceId, op.input.sourceId, observation.observedAt, payment.id,
        )
        links[op.evidenceLinkId] = Link(op.evidenceLinkId, op.input.evidenceId, payment.id, payment.assetPostingId)
        val target = reconciliations.entries.singleOrNull { it.value.postingId == payment.assetPostingId }
        if (target != null) reconciliations[target.key] = target.value.copy(status = "matched")
        return accepted(op)
    }

    private fun ingest(op: Rg06Operation.IngestStagedPaymentBankFact): Rg06ExecutionResult {
        val time = sourcePaymentAt(op.input.sourcePaymentAt, op.input.sourcePaymentAtText)
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        val payloadResult = op.input.suggestedPaymentRole?.let {
            Rg06StagedPaymentCandidatePayload.known(it, op.input.amount, time, op.input.evidenceId)
        } ?: Rg06StagedPaymentCandidatePayload.ambiguous(op.input.amount, time, op.input.evidenceId)
        val payload = (payloadResult as? Rg06TypedValueResult.Success)?.value
            ?: return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        val source = (Rg06StagedPaymentBankSource.importedOriginal(
            op.ledgerId, op.input.sourceId, op.input.amount, time,
        ) as? Rg06TypedValueResult.Success)?.value
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        val pendingEvidence = Rg06PendingStagedPaymentEvidence(op.ledgerId, op.input.evidenceId, op.input.sourceId, time)
        val candidate = Rg06StagedPaymentCandidate.pending(
            op.ledgerId, op.ids.candidateId, op.input.sourceId,
            payload,
            op.ids.pendingStatusId,
        )
        sources[source.id] = source; evidence[pendingEvidence.id] = pendingEvidence; candidates[candidate.id] = candidate
        return accepted(op)
    }

    private fun confirm(op: Rg06Operation.ConfirmStagedPaymentCandidate): Rg06ExecutionResult {
        val candidate = candidates[op.input.candidateId] ?: return rejected(Rg06RejectionReason.CANDIDATE_NOT_FOUND, Rg06FieldPath.INPUT_CANDIDATE_ID)
        if (candidate.ledgerId != op.ledgerId) return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_CANDIDATE_ID)
        if (candidate.statusHistory.last().status != Rg06CandidateStatus.PENDING_CONFIRMATION) return rejected(Rg06RejectionReason.CANDIDATE_NOT_PENDING, Rg06FieldPath.INPUT_CANDIDATE_ID)
        val known = candidate.payload.roleFact as? Rg06CandidateRoleFact.Known
        if (known != null && known.role != op.input.paymentRole) return rejected(Rg06RejectionReason.CANDIDATE_ROLE_MISMATCH, Rg06FieldPath.INPUT_PAYMENT_ROLE)
        val aggregate = ownedAggregate(op.ledgerId, op.input.relationId) ?: return crossOrMissing(op.ledgerId, op.input.relationId)
        if (aggregate.lifecycle.categoryId != op.input.categoryId) return rejected(Rg06RejectionReason.CANDIDATE_TARGET_MISMATCH, Rg06FieldPath.INPUT_CATEGORY_ID)
        val pending = evidence[candidate.payload.evidenceId] as? Rg06PendingStagedPaymentEvidence
            ?: return rejected(Rg06RejectionReason.EVIDENCE_ALREADY_BOUND, Rg06FieldPath.INPUT_CANDIDATE_ID)
        val input = Rg06RecordStagedPaymentInstallmentInput(op.input.requestId, op.input.relationId, op.input.paymentRole, candidate.payload.amount, op.input.fundingAccountId, candidate.payload.sourcePaymentAt.instant)
        validateInstallment(aggregate, input)?.let { return it }
        val result = aggregate.recordInstallment(
            catalog,
            RecordStagedPaymentInstallmentCommand(
                op.input.paymentRole,
                candidate.payload.amount,
                op.input.fundingAccountId,
                candidate.payload.sourcePaymentAt.instant,
                candidate.payload.sourcePaymentAt.value,
            ),
            op.ids.paymentIds,
        )
        val updated = (result as? StagedPaymentResult.Success)?.value ?: return rejected(Rg06RejectionReason.DOMAIN_REJECTED, Rg06FieldPath.GENERATED_IDENTITY)
        val confirmedCandidate = when (val transition = candidate.confirm(op.ids.confirmedStatusId)) {
            is Rg06TypedValueResult.Success -> transition.value
            is Rg06TypedValueResult.Failure -> return rejected(
                if (transition.reason == Rg06TypedValueFailure.CANDIDATE_STATUS_IDENTITY_COLLISION) {
                    Rg06RejectionReason.IDENTITY_COLLISION
                } else {
                    Rg06RejectionReason.CANDIDATE_NOT_PENDING
                },
                if (transition.reason == Rg06TypedValueFailure.CANDIDATE_STATUS_IDENTITY_COLLISION) {
                    Rg06FieldPath.GENERATED_IDENTITY
                } else {
                    Rg06FieldPath.INPUT_CANDIDATE_ID
                },
            )
        }
        if (generatedKeys(op).any { identities.containsKey(it) }) {
            return rejected(Rg06RejectionReason.IDENTITY_COLLISION, Rg06FieldPath.GENERATED_IDENTITY)
        }
        val payment = updated.installments.single { it.id == op.ids.paymentIds.paymentId }
        aggregates[op.input.relationId] = updated
        candidates[candidate.id] = confirmedCandidate
        evidence[pending.id] = pending.bind(payment.id)
        links[op.ids.evidenceLinkId] = Link(op.ids.evidenceLinkId, pending.id, payment.id, payment.assetPostingId)
        reconciliations[op.ids.reconciliationId] = Reconciliation(payment.assetPostingId, "pending")
        return accepted(op)
    }

    private fun mirror(op: Rg06Operation.MergeStagedPaymentMirrorEvidence): Rg06ExecutionResult {
        val time = sourcePaymentAt(op.input.sourcePaymentAt, op.input.sourcePaymentAtText)
            ?: return rejected(Rg06RejectionReason.INVALID_SOURCE_TIME, Rg06FieldPath.INPUT_SOURCE_PAYMENT_AT)
        if (op.input.amount.minorUnits == 0L || op.input.amount.minorUnits == Long.MIN_VALUE) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.INPUT_AMOUNT)
        val payment = findPayment(op.ledgerId, op.input.paymentId) ?: return rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_PAYMENT_ID)
        if (payment.assetPostingId != op.input.postingId) return rejected(Rg06RejectionReason.EVIDENCE_TARGET_MISMATCH, Rg06FieldPath.INPUT_POSTING_ID)
        val originalLink = links.values.singleOrNull { it.paymentId == payment.id && it.postingId == payment.assetPostingId }
            ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        val originalEvidence = evidence[originalLink.evidenceId] ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        val originalSource = sources[originalEvidence.sourceId] ?: return rejected(Rg06RejectionReason.MIRROR_TARGET_NOT_FOUND, Rg06FieldPath.INPUT_POSTING_ID)
        val originalMinor = originalSource.payload.amount.minorUnits
        if (originalMinor == Long.MIN_VALUE || op.input.amount.currency != originalSource.payload.amount.currency || op.input.amount.minorUnits != -originalMinor) {
            return rejected(Rg06RejectionReason.MIRROR_SOURCE_MISMATCH, Rg06FieldPath.INPUT_AMOUNT)
        }
        val mirrorSource = (Rg06StagedPaymentBankSource.mirror(
            op.ledgerId, op.input.sourceId, op.input.amount, time, originalSource.id,
        ) as? Rg06TypedValueResult.Success)?.value
            ?: return rejected(Rg06RejectionReason.MIRROR_SOURCE_MISMATCH, Rg06FieldPath.INPUT_SOURCE_ID)
        sources[op.input.sourceId] = mirrorSource
        val mirrorEvidence = when (
            val result = Rg06BoundStagedPaymentEvidence.mirror(
                op.ledgerId, op.input.evidenceId, op.input.sourceId, time, payment.id,
                originalEvidence.id, originalLink.id,
            )
        ) {
            is Rg06TypedValueResult.Success -> result.value
            is Rg06TypedValueResult.Failure -> return rejected(
                Rg06RejectionReason.IDENTITY_COLLISION,
                Rg06FieldPath.GENERATED_IDENTITY,
            )
        }
        evidence[op.input.evidenceId] = mirrorEvidence
        return accepted(op)
    }

    private fun validateCreate(op: Rg06Operation.CreateStagedPayment): Rg06ExecutionResult.Rejected? {
        if (op.input.totalAmount.minorUnits <= 0) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_TOTAL_AMOUNT)
        val id = op.input.categoryId ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        val category = catalog.categories.singleOrNull { it.id == id }
            ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        val parent = category.parentId?.let { parentId -> catalog.categories.singleOrNull { it.id == parentId } }
            ?: return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        if (parent.parentId != null || category.ledgerId != op.ledgerId || parent.ledgerId != op.ledgerId) return rejected(Rg06RejectionReason.SECONDARY_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        if (!category.active) return rejected(Rg06RejectionReason.CATEGORY_INACTIVE, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        if (category.kind != CategoryKind.EXPENSE || parent.kind != CategoryKind.EXPENSE) return rejected(Rg06RejectionReason.EXPENSE_CATEGORY_REQUIRED, Rg06FieldPath.ATTEMPTED_CATEGORY_ID)
        return null
    }

    private fun validateInstallment(aggregate: StagedPayment, input: Rg06RecordStagedPaymentInstallmentInput): Rg06ExecutionResult.Rejected? {
        val amount = input.paymentAmount.minorUnits
        if (amount <= 0) return rejected(Rg06RejectionReason.MUST_BE_POSITIVE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.DEPOSIT && amount >= aggregate.lifecycle.totalAmount.minorUnits) return rejected(Rg06RejectionReason.DEPOSIT_MUST_BE_LESS_THAN_TOTAL, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.FINAL && amount > aggregate.lifecycle.dueAmount.minorUnits) return rejected(Rg06RejectionReason.PAYMENT_EXCEEDS_DUE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentRole == StagedPaymentRole.FINAL && amount != aggregate.lifecycle.dueAmount.minorUnits) return rejected(Rg06RejectionReason.FINAL_MUST_EQUAL_REMAINING_DUE, Rg06FieldPath.ATTEMPTED_PAYMENT_AMOUNT)
        if (input.paymentAmount.currency != aggregate.lifecycle.currency) return rejected(Rg06RejectionReason.SINGLE_CURRENCY_REQUIRED, Rg06FieldPath.ATTEMPTED_CURRENCY)
        val account = catalog.accounts.singleOrNull { it.id == input.fundingAccountId }
            ?: return rejected(Rg06RejectionReason.UNKNOWN_REAL_ACCOUNT, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (!account.realAccount) return rejected(Rg06RejectionReason.REAL_FINANCIAL_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (!account.ownedByUser || account.ledgerId != aggregate.ledgerId) return rejected(Rg06RejectionReason.OWNED_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        if (account.kind != AccountKind.ASSET) return rejected(Rg06RejectionReason.ASSET_ACCOUNT_REQUIRED, Rg06FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID)
        return null
    }

    private fun accepted(op: Rg06Operation): Rg06ExecutionResult.Accepted {
        val ids = when (op) {
            is Rg06Operation.ChangeStagedPaymentFulfillment ->
                listOf(Rg06ReturnedId.Lifecycle(checkNotNull(aggregates[op.input.relationId]).lifecycle.id))
            is Rg06Operation.ConfirmStagedPaymentCompletion ->
                listOf(Rg06ReturnedId.Lifecycle(checkNotNull(aggregates[op.input.relationId]).lifecycle.id))
            else -> returnedIds(op)
        }
        return Rg06ExecutionResult.Accepted(ids)
    }
    private fun rejected(reason: Rg06RejectionReason, path: Rg06FieldPath) = Rg06ExecutionResult.Rejected(reason, path)
    private fun ownedAggregate(ledger: LedgerId, id: StagedPaymentRelationId) = aggregates[id]?.takeIf { it.ledgerId == ledger }
    private fun crossOrMissing(ledger: LedgerId, id: StagedPaymentRelationId) = if (aggregates[id] == null) rejected(Rg06RejectionReason.RELATION_NOT_FOUND, Rg06FieldPath.INPUT_RELATION_ID) else rejected(Rg06RejectionReason.CROSS_LEDGER_REFERENCE, Rg06FieldPath.INPUT_RELATION_ID)
    private fun findPayment(ledger: LedgerId, id: InstallmentPaymentId) = aggregates.values.filter { it.ledgerId == ledger }.flatMap { it.installments }.singleOrNull { it.id == id }
    private fun sourcePaymentAt(instant: Instant, text: String): Rg06SourcePaymentAt? =
        (Rg06SourcePaymentAt.create(instant, text, expectedSourceOffsetText) as? Rg06TypedValueResult.Success)?.value
    private fun positiveMagnitude(money: Money): Money? = when (money.minorUnits) { 0L, Long.MIN_VALUE -> null; else -> Money.ofMinor(if (money.minorUnits < 0) -money.minorUnits else money.minorUnits, money.currency) }

    private fun canonicalInput(op: Rg06Operation): Any = when (op) {
        is Rg06Operation.CreateStagedPayment -> op.input; is Rg06Operation.RecordStagedPaymentInstallment -> op.input
        is Rg06Operation.ChangeStagedPaymentFulfillment -> op.input; is Rg06Operation.ConfirmStagedPaymentCompletion -> op.input
        is Rg06Operation.LinkStagedPaymentEvidence -> op.input; is Rg06Operation.IngestStagedPaymentBankFact -> op.input
        is Rg06Operation.ConfirmStagedPaymentCandidate -> op.input; is Rg06Operation.MergeStagedPaymentMirrorEvidence -> op.input
    }

    private fun generatedKeys(op: Rg06Operation): Set<Pair<String, String>> {
        fun key(kind: String, value: String) = kind to value
        fun payment(ids: StagedPaymentInstallmentIds) = setOf(
            key("payment", ids.paymentId.value), key("history", ids.historyId.value), key("transaction", ids.expenseIds.transactionId.value),
            key("version", ids.expenseIds.versionId.value), key("posting_set", ids.expenseIds.postingSetId.value),
            key("posting", ids.expenseIds.expensePostingId.value), key("posting", ids.expenseIds.paymentPostingId.value),
        )
        return when (op) {
            is Rg06Operation.CreateStagedPayment -> setOf(key("relation", op.ids.relationId.value), key("lifecycle", op.ids.lifecycleId.value), key("history", op.ids.historyId.value))
            is Rg06Operation.RecordStagedPaymentInstallment -> payment(op.ids.paymentIds) + key("confirmation", op.ids.confirmationId.value) + key("reconciliation", op.ids.reconciliationId.value)
            is Rg06Operation.ChangeStagedPaymentFulfillment -> setOf(key("history", op.historyId.value))
            is Rg06Operation.ConfirmStagedPaymentCompletion -> setOf(key("history", op.historyId.value))
            is Rg06Operation.LinkStagedPaymentEvidence -> setOf(key("source", op.input.sourceId.value), key("evidence", op.input.evidenceId.value), key("link", op.evidenceLinkId.value))
            is Rg06Operation.IngestStagedPaymentBankFact -> setOf(key("source", op.input.sourceId.value), key("evidence", op.input.evidenceId.value), key("candidate", op.ids.candidateId.value), key("candidate_status", op.ids.pendingStatusId.value))
            is Rg06Operation.ConfirmStagedPaymentCandidate -> payment(op.ids.paymentIds) + setOf(key("confirmation", op.ids.confirmationId.value), key("link", op.ids.evidenceLinkId.value), key("candidate_status", op.ids.confirmedStatusId.value), key("reconciliation", op.ids.reconciliationId.value))
            is Rg06Operation.MergeStagedPaymentMirrorEvidence -> setOf(key("source", op.input.sourceId.value), key("evidence", op.input.evidenceId.value))
        }
    }

    private fun <K, V> MutableMap<K, V>.replaceWith(snapshot: Map<K, V>) {
        clear()
        putAll(snapshot)
    }
}

private class Rg06Fixture(private val suffix: String = "base") {
    val expectedOffsetText = "+08:00"
    val ledgerId = LedgerId("ledger-$suffix")
    val cny = CurrencyUnit("CNY", 2); val usd = CurrencyUnit("USD", 2)
    val fundingAccountId = AccountId("asset-bank-$suffix"); val unknownAccountId = AccountId("asset-missing-$suffix")
    val nonFinancialAccountId = AccountId("asset-nonfinancial-$suffix"); val externalAccountId = AccountId("asset-external-$suffix"); val liabilityAccountId = AccountId("liability-$suffix")
    val primaryExpenseCategoryId = CategoryId("expense-root-$suffix"); val categoryId = CategoryId("expense-service-$suffix")
    val inactiveCategoryId = CategoryId("expense-inactive-$suffix"); val incomeCategoryId = CategoryId("income-child-$suffix")
    val relationId = StagedPaymentRelationId("relation-$suffix"); val lifecycleId = StagedPaymentLifecycleId("lifecycle-$suffix")
    val depositPaymentId = InstallmentPaymentId("payment-deposit-$suffix"); val finalPaymentId = InstallmentPaymentId("payment-final-$suffix")
    val depositTransactionId = TransactionId("transaction-deposit-$suffix")
    val manualDepositConfirmationId = Rg06ConfirmationId("confirmation-manual-deposit-$suffix")
    val importDepositConfirmationId = Rg06ConfirmationId("confirmation-import-deposit-$suffix")
    val importDepositSourceId = Rg06SourceId("source-import-deposit-$suffix"); val importDepositEvidenceId = Rg06EvidenceId("evidence-import-deposit-$suffix")
    val depositCandidateId = Rg06CandidateId("candidate-deposit-$suffix"); val finalCandidateId = Rg06CandidateId("candidate-final-$suffix")
    val importDepositLinkId = Rg06EvidenceLinkId("link-import-deposit-$suffix")
    val manualSourceId = Rg06SourceId("source-manual-$suffix"); val manualEvidenceId = Rg06EvidenceId("evidence-manual-$suffix"); val manualLinkId = Rg06EvidenceLinkId("link-manual-$suffix")
    val mirrorSourceId = Rg06SourceId("source-mirror-$suffix"); val mirrorEvidenceId = Rg06EvidenceId("evidence-mirror-$suffix")
    private val createdAt = Instant.parse("2026-04-20T09:00:00+08:00"); private val depositAt = Instant.parse("2026-04-28T10:00:00+08:00"); private val finalAt = Instant.parse("2026-05-03T16:30:00+08:00")

    val catalog = assertIs<DomainResult.Success<LedgerCatalog>>(LedgerCatalog.create(
        listOf(
            Account(fundingAccountId, ledgerId, AccountKind.ASSET, cny, true, true),
            Account(nonFinancialAccountId, ledgerId, AccountKind.ASSET, cny, true, false),
            Account(externalAccountId, ledgerId, AccountKind.ASSET, cny, false, true),
            Account(liabilityAccountId, ledgerId, AccountKind.LIABILITY, cny, true, true),
            Account(AccountId("expense-account-$suffix"), ledgerId, AccountKind.EXPENSE, cny, false, false),
            Account(AccountId("income-account-$suffix"), ledgerId, AccountKind.INCOME, cny, false, false),
        ),
        listOf(
            Category(primaryExpenseCategoryId, ledgerId, null, null, true),
            Category(categoryId, ledgerId, primaryExpenseCategoryId, AccountId("expense-account-$suffix"), true),
            Category(inactiveCategoryId, ledgerId, primaryExpenseCategoryId, AccountId("expense-account-$suffix"), false),
            Category(CategoryId("income-root-$suffix"), ledgerId, null, null, true, CategoryKind.INCOME),
            Category(incomeCategoryId, ledgerId, CategoryId("income-root-$suffix"), AccountId("income-account-$suffix"), true, CategoryKind.INCOME),
        ),
    )).value

    fun manualObservation(
        amount: Money = Money.ofMinor(-8_000L, cny),
        instant: Instant = Instant.parse("2026-04-27T10:15:00Z"),
        text: String = "2026-04-27T18:15:00+08:00",
    ): Rg06ManualBankObservation = Rg06ManualBankObservation(
        amount,
        assertIs<Rg06TypedValueResult.Success<Rg06ObservedAt>>(
            Rg06ObservedAt.create(instant, text, expectedOffsetText),
        ).value,
    )

    fun observationMap(
        observation: Rg06ManualBankObservation = manualObservation(),
    ): Map<Rg06ManualObservationKey, Rg06ManualBankObservation> = mapOf(
        Rg06ManualObservationKey(manualSourceId, manualEvidenceId) to observation,
    )

    fun port(
        observations: Map<Rg06ManualObservationKey, Rg06ManualBankObservation> = observationMap(),
    ) = Rg06ApplicationReferencePort(catalog, expectedOffsetText, observations)

    fun create() = Rg06Operation.CreateStagedPayment(ledgerId, Rg06CreateStagedPaymentInput(RequestId("request-create-$suffix"), Money.ofMinor(30_000, cny), categoryId, createdAt), StagedPaymentCreationIds(relationId, lifecycleId, StagedPaymentHistoryId("history-create-$suffix")))
    fun recordDeposit() = Rg06Operation.RecordStagedPaymentInstallment(ledgerId, Rg06RecordStagedPaymentInstallmentInput(RequestId("request-record-deposit-$suffix"), relationId, StagedPaymentRole.DEPOSIT, Money.ofMinor(8_000, cny), fundingAccountId, depositAt), manualIds("deposit", depositPaymentId, manualDepositConfirmationId))
    fun recordFinal() = Rg06Operation.RecordStagedPaymentInstallment(ledgerId, Rg06RecordStagedPaymentInstallmentInput(RequestId("request-record-final-$suffix"), relationId, StagedPaymentRole.FINAL, Money.ofMinor(22_000, cny), fundingAccountId, finalAt), manualIds("final", finalPaymentId, Rg06ConfirmationId("confirmation-manual-final-$suffix")))
    fun fulfill() = Rg06Operation.ChangeStagedPaymentFulfillment(ledgerId, Rg06ChangeStagedPaymentFulfillmentInput(RequestId("request-fulfill-$suffix"), relationId, StagedPaymentFulfillment.FULFILLED, Instant.parse("2026-05-01T12:00:00+08:00")), StagedPaymentHistoryId("history-fulfill-$suffix"))
    fun complete() = Rg06Operation.ConfirmStagedPaymentCompletion(ledgerId, Rg06ConfirmStagedPaymentCompletionInput(RequestId("request-complete-$suffix"), relationId, true, Instant.parse("2026-05-04T09:00:00+08:00")), StagedPaymentHistoryId("history-complete-$suffix"))
    fun linkDepositEvidence() = Rg06Operation.LinkStagedPaymentEvidence(ledgerId, Rg06LinkStagedPaymentEvidenceInput(manualSourceId, manualEvidenceId, depositPaymentId, PostingId("posting-deposit-asset-$suffix")), manualLinkId)
    fun ingestDeposit(amountMinor: Long = -8_000L) = Rg06Operation.IngestStagedPaymentBankFact(ledgerId, Rg06IngestStagedPaymentBankFactInput(importDepositSourceId, importDepositEvidenceId, depositAt, "2026-04-28T10:00:00+08:00", Money.ofMinor(amountMinor, cny), StagedPaymentRole.DEPOSIT), Rg06IngestCommitIds(depositCandidateId, Rg06CandidateStatusId("status-deposit-pending-$suffix")))
    fun ingestFinal(suggestedRole: StagedPaymentRole? = StagedPaymentRole.FINAL) = Rg06Operation.IngestStagedPaymentBankFact(ledgerId, Rg06IngestStagedPaymentBankFactInput(Rg06SourceId("source-import-final-$suffix"), Rg06EvidenceId("evidence-import-final-$suffix"), finalAt, "2026-05-03T16:30:00+08:00", Money.ofMinor(-22_000, cny), suggestedRole), Rg06IngestCommitIds(finalCandidateId, Rg06CandidateStatusId("status-final-pending-$suffix")))
    fun confirmDepositCandidate() = Rg06Operation.ConfirmStagedPaymentCandidate(ledgerId, Rg06ConfirmStagedPaymentCandidateInput(RequestId("request-confirm-deposit-$suffix"), depositCandidateId, relationId, StagedPaymentRole.DEPOSIT, categoryId, fundingAccountId, true), confirmIds("deposit", depositPaymentId))
    fun confirmFinalCandidate(requestId: RequestId = RequestId("request-confirm-final-$suffix")) = Rg06Operation.ConfirmStagedPaymentCandidate(ledgerId, Rg06ConfirmStagedPaymentCandidateInput(requestId, finalCandidateId, relationId, StagedPaymentRole.FINAL, categoryId, fundingAccountId, true), confirmIds("final", finalPaymentId))
    fun mergeDepositMirror(amountMinor: Long = 8_000L) = Rg06Operation.MergeStagedPaymentMirrorEvidence(ledgerId, Rg06MergeStagedPaymentMirrorEvidenceInput(mirrorSourceId, mirrorEvidenceId, depositPaymentId, PostingId("posting-deposit-asset-$suffix"), Money.ofMinor(amountMinor, cny), depositAt, "2026-04-28T10:00:00+08:00"))
    fun manualIds(label: String, paymentId: InstallmentPaymentId, confirmation: Rg06ConfirmationId) = Rg06ManualInstallmentCommitIds(confirmation, paymentIds(label, paymentId), Rg06ReconciliationId("reconciliation-$label-$suffix"))
    fun confirmIds(label: String, paymentId: InstallmentPaymentId) = Rg06CandidateConfirmationCommitIds(if (label == "deposit") importDepositConfirmationId else Rg06ConfirmationId("confirmation-import-$label-$suffix"), paymentIds(label, paymentId), Rg06EvidenceLinkId("link-import-$label-$suffix"), Rg06CandidateStatusId("status-$label-confirmed-$suffix"), Rg06ReconciliationId("reconciliation-import-$label-$suffix"))
    private fun paymentIds(label: String, paymentId: InstallmentPaymentId) = StagedPaymentInstallmentIds(paymentId, StagedPaymentHistoryId("history-$label-$suffix"), AssetPaidOrdinaryExpenseIds(TransactionId("transaction-$label-$suffix"), TransactionVersionId("version-$label-$suffix"), PostingSetId("posting-set-$label-$suffix"), PostingId("posting-$label-expense-$suffix"), PostingId("posting-$label-asset-$suffix")))
}

private fun returnedIds(op: Rg06Operation): List<Rg06ReturnedId> = when (op) {
    is Rg06Operation.CreateStagedPayment -> listOf(Rg06ReturnedId.Relation(op.ids.relationId), Rg06ReturnedId.Lifecycle(op.ids.lifecycleId))
    is Rg06Operation.RecordStagedPaymentInstallment -> listOf(Rg06ReturnedId.Confirmation(op.ids.confirmationId), Rg06ReturnedId.Transaction(op.ids.paymentIds.expenseIds.transactionId), Rg06ReturnedId.Payment(op.ids.paymentIds.paymentId))
    is Rg06Operation.ChangeStagedPaymentFulfillment,
    is Rg06Operation.ConfirmStagedPaymentCompletion -> error("status lifecycle identity is resolved by the port")
    is Rg06Operation.LinkStagedPaymentEvidence -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId), Rg06ReturnedId.EvidenceLink(op.evidenceLinkId))
    is Rg06Operation.IngestStagedPaymentBankFact -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId), Rg06ReturnedId.Candidate(op.ids.candidateId))
    is Rg06Operation.ConfirmStagedPaymentCandidate -> listOf(Rg06ReturnedId.Confirmation(op.ids.confirmationId), Rg06ReturnedId.Transaction(op.ids.paymentIds.expenseIds.transactionId), Rg06ReturnedId.Payment(op.ids.paymentIds.paymentId), Rg06ReturnedId.EvidenceLink(op.ids.evidenceLinkId))
    is Rg06Operation.MergeStagedPaymentMirrorEvidence -> listOf(Rg06ReturnedId.Source(op.input.sourceId), Rg06ReturnedId.Evidence(op.input.evidenceId))
}

private fun Rg06ExecutionResult.accepted() = assertIs<Rg06ExecutionResult.Accepted>(this)
private fun Rg06OperationIdentity.requestId() = RequestId(value)
