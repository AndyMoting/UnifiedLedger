package com.unifiedledger.application

import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CategoryId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LedgerId
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.PostingId
import com.unifiedledger.domain.TransactionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class Rg03OperationBoundaryTest {
    private val context = Rg03AdapterContext(LedgerId("ledger-a"), CurrencyUnit("CNY", 2), "Asia/Shanghai", "+08:00")

    @Test
    fun `adapter preserves exact facts for every approved action without expected-state input`() {
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value
        val commands =
            decoded.operations
                .filter { it.replayOf == null && it.expected !is Rg03ExpectedOutcome.Rejected }
                .map { assertIs<Rg03AdaptResult.Success>(adaptRg03Operation(context, it)).command }

        assertIs<Rg03Command.ManualTransfer>(commands[0]).also {
            assertEquals(6_000L, it.snapshot.sourceDebit.minorUnits)
            assertEquals(5_900L, it.snapshot.destinationCredit.minorUnits)
            assertEquals(100L, it.snapshot.fee.minorUnits)
            assertEquals(Instant.parse("2026-01-20T02:00:00Z"), it.snapshot.occurredAt)
        }
        assertIs<Rg03Command.ImportSource>(commands[1]).also { assertEquals(SourceCompleteness.COMPLETE, it.snapshot.completeness) }
        assertIs<Rg03Command.ConfirmCandidate>(commands[2])
        assertIs<Rg03Command.ImportMirror>(commands[3])
        assertIs<Rg03Command.ImportSource>(commands[4]).also {
            assertEquals(SourceCompleteness.MISSING_DESTINATION, it.snapshot.completeness)
            assertEquals(null, it.snapshot.destinationAccountId)
            assertEquals(null, it.snapshot.destinationCredit)
            assertEquals(null, it.snapshot.fee)
        }
        assertEquals(Rg03JsonField.Null, decoded.operations[4].input.destinationAccountId)
        assertEquals(Rg03JsonField.Omitted, decoded.operations[4].input.destinationCreditAmount)
        assertEquals(Rg03JsonField.Omitted, decoded.operations[4].input.feeAmount)
    }

    @Test
    fun `adapter rejects invalid decimals timestamps ids currency and implicit confirmation`() {
        val manual =
            assertIs<Rg03Command.ManualTransfer>(
                assertIs<Rg03AdaptResult.Success>(
                    adaptRg03Operation(
                        context,
                        assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value.operations.first(),
                    ),
                ).command,
            )
        val input = manual.rawInput
        val cases =
            listOf(
                input.copy(sourceDebitAmount = Rg03JsonField.Value("1.001")) to Rg03ContractErrorReason.INVALID_DECIMAL,
                input.copy(sourceDebitAmount = Rg03JsonField.Value("92233720368547758.08")) to Rg03ContractErrorReason.INVALID_DECIMAL,
                input.copy(feeAmount = Rg03JsonField.Value("-1.00")) to Rg03ContractErrorReason.NEGATIVE_FEE,
                input.copy(occurredAt = Rg03JsonField.Value("2026-01-20T10:00:00")) to Rg03ContractErrorReason.INVALID_TIMESTAMP,
                input.copy(occurredAt = Rg03JsonField.Value("2026-01-20T10:00:00+07:00")) to Rg03ContractErrorReason.TIMEZONE_OFFSET_MISMATCH,
                input.copy(requestId = Rg03JsonField.Value("")) to Rg03ContractErrorReason.INVALID_ID,
                input.copy(currency = Rg03JsonField.Value("USD")) to Rg03ContractErrorReason.CURRENCY_MISMATCH,
                input.copy(explicitConfirmation = Rg03JsonField.Value(false)) to Rg03ContractErrorReason.EXPLICIT_CONFIRMATION_REQUIRED,
            )
        cases.forEach { (candidate, reason) ->
            val operation = Rg03DecodedOperation(Rg03ActionType.MANUAL_ACCOUNT_TRANSFER, candidate, Rg03ExpectedOutcome.Accepted, null)
            assertEquals(reason, assertIs<Rg03AdaptResult.Invalid>(adaptRg03Operation(context, operation)).error.reason)
        }
    }

    @Test
    fun `confirmation recovers persisted candidate and mirror uses operation scoped unique binding`() {
        val callerLedger = LedgerId("ledger-non-default")
        val candidate = persistedCandidate().copy(ledgerId = callerLedger)
        val target =
            Rg03MirrorTarget(
                candidateId = candidate.candidateId,
                transactionId = TransactionId("tx-imported"),
                destinationPostingId = PostingId("posting-destination"),
                destinationAccountId = AccountId("asset-wallet-b"),
                destinationCredit = Money.ofMinor(5_900L, context.currency),
            )
        val committed = mutableListOf<Rg03PreparedOperation>()
        var recoveredLedger: LedgerId? = null
        var recoveredCandidateId: CandidateId? = null
        val executor =
            ExecuteRg03Operation(
                candidateRecovery =
                    Rg03CandidateRecoveryPort { ledgerId, candidateId ->
                        recoveredLedger = ledgerId
                        recoveredCandidateId = candidateId
                        candidate
                    },
                mirrorBinding =
                    Rg03MirrorBindingPort { ledgerId, scope ->
                        assertEquals(callerLedger, ledgerId)
                        assertEquals(Rg03MirrorScope(candidate.candidateId), scope)
                        Rg03MirrorBindingResult.Unique(target)
                    },
                commitPort =
                    Rg03PreparedOperationCommitPort { prepared ->
                        committed += prepared
                        Rg03ExecutionResult.Accepted(emptyList())
                    },
            )

        assertIs<Rg03ExecutionResult.Accepted>(
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("confirm"),
                    CandidateId("candidate-id"),
                    true,
                    callerLedger,
                ),
            ),
        )
        assertEquals(callerLedger, recoveredLedger)
        assertEquals(CandidateId("candidate-id"), recoveredCandidateId)
        assertIs<Rg03PreparedOperation.ConfirmCandidate>(committed.single()).also {
            assertEquals(candidate, it.candidate)
        }
        committed.clear()
        assertIs<Rg03ExecutionResult.Accepted>(
            executor.execute(Rg03Command.ImportMirror(mirrorSnapshot().copy(ledgerId = callerLedger))),
        )
        assertIs<Rg03PreparedOperation.MergeMirror>(committed.single()).also { assertEquals(target, it.target) }
    }

    @Test
    fun `confirmed candidate and ambiguous mirror reach commit boundary for replay resolution`() {
        var commits = 0
        val executor =
            ExecuteRg03Operation(
                candidateRecovery = Rg03CandidateRecoveryPort { _, _ -> persistedCandidate().copy(status = CandidateStatus.CONFIRMED) },
                mirrorBinding = Rg03MirrorBindingPort { _, _ -> Rg03MirrorBindingResult.Ambiguous },
                commitPort =
                    Rg03PreparedOperationCommitPort { prepared ->
                        commits++
                        when (prepared) {
                            is Rg03PreparedOperation.ConfirmCandidate ->
                                Rg03ExecutionResult.NoChange(
                                    listOf(ReturnedId(ReturnedIdKind.TRANSACTION, "existing")),
                                )
                            is Rg03PreparedOperation.MergeMirror -> {
                                assertNull(prepared.target)
                                Rg03ExecutionResult.Rejected(Rg03ExecutionError.AMBIGUOUS_MIRROR_TARGET)
                            }
                            else -> error("unexpected prepared operation")
                        }
                    },
            )

        assertEquals(
            Rg03ExecutionResult.NoChange(listOf(ReturnedId(ReturnedIdKind.TRANSACTION, "existing"))),
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("confirm"),
                    CandidateId("candidate-id"),
                    true,
                    context.ledgerId,
                ),
            ),
        )
        assertEquals(Rg03ExecutionResult.Rejected(Rg03ExecutionError.AMBIGUOUS_MIRROR_TARGET), executor.execute(Rg03Command.ImportMirror(mirrorSnapshot())))
        assertEquals(2, commits)
    }

    @Test
    fun `source result replaces stale lifecycle only from exactly one typed candidate id`() {
        val candidateA = persistedCandidate()
        val candidateB = CandidateId("candidate-b")
        val targetA =
            Rg03MirrorTarget(
                candidateA.candidateId,
                TransactionId("transaction-a"),
                PostingId("posting-a"),
                checkNotNull(candidateA.destinationAccountId),
                checkNotNull(candidateA.destinationCredit),
            )
        val cases =
            listOf(
                Rg03ExecutionResult.Accepted(listOf(ReturnedId(ReturnedIdKind.CANDIDATE, candidateB.value))) to candidateB,
                Rg03ExecutionResult.NoChange(
                    listOf(
                        ReturnedId(ReturnedIdKind.SOURCE, "candidate-looking-source"),
                        ReturnedId(ReturnedIdKind.CANDIDATE, candidateB.value),
                    ),
                ) to candidateB,
                Rg03ExecutionResult.Accepted(emptyList()) to null,
                Rg03ExecutionResult.Accepted(
                    listOf(
                        ReturnedId(ReturnedIdKind.CANDIDATE, candidateB.value),
                        ReturnedId(ReturnedIdKind.CANDIDATE, "candidate-c"),
                    ),
                ) to null,
            )

        cases.forEach { (sourceResult, expectedCandidateId) ->
            val resolvedScopes = mutableListOf<Rg03MirrorScope>()
            var mergedTarget: Rg03MirrorTarget? = targetA
            val executor =
                ExecuteRg03Operation(
                    candidateRecovery = Rg03CandidateRecoveryPort { _, _ -> candidateA },
                    mirrorBinding =
                        Rg03MirrorBindingPort { _, scope ->
                            resolvedScopes += scope
                            if (scope.candidateId == candidateA.candidateId) {
                                Rg03MirrorBindingResult.Unique(targetA)
                            } else {
                                Rg03MirrorBindingResult.Missing
                            }
                        },
                    commitPort =
                        Rg03PreparedOperationCommitPort { prepared ->
                            when (prepared) {
                                is Rg03PreparedOperation.ConfirmCandidate -> Rg03ExecutionResult.Accepted(emptyList())
                                is Rg03PreparedOperation.StoreSource -> sourceResult
                                is Rg03PreparedOperation.MergeMirror -> {
                                    mergedTarget = prepared.target
                                    Rg03ExecutionResult.Accepted(emptyList())
                                }
                                else -> error("unexpected prepared operation")
                            }
                        },
                )

            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(
                    Rg03Command.ConfirmCandidate(
                        RequestId("confirm-a"),
                        candidateA.candidateId,
                        true,
                        context.ledgerId,
                    ),
                ),
            )
            assertEquals(sourceResult, executor.execute(Rg03Command.ImportSource(sourceSnapshot())))
            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(Rg03Command.ImportMirror(mirrorSnapshot())))

            assertEquals(
                expectedCandidateId?.let { listOf(Rg03MirrorScope(it)) } ?: emptyList(),
                resolvedScopes,
            )
            assertNull(mergedTarget)
        }
    }

    @Test
    fun `every rejected root start clears a previously confirmed lifecycle scope`() {
        fun assertCleared(
            start: Rg03Command,
            expected: Rg03ExecutionResult,
        ) {
            val candidate = persistedCandidate()
            var mirrorBindings = 0
            val executor =
                ExecuteRg03Operation(
                    candidateRecovery = Rg03CandidateRecoveryPort { _, _ -> candidate },
                    mirrorBinding =
                        Rg03MirrorBindingPort { _, _ ->
                            mirrorBindings++
                            Rg03MirrorBindingResult.Ambiguous
                        },
                    commitPort =
                        Rg03PreparedOperationCommitPort { prepared ->
                            when (prepared) {
                                is Rg03PreparedOperation.ConfirmCandidate -> Rg03ExecutionResult.Accepted(emptyList())
                                is Rg03PreparedOperation.CreateManual -> expected
                                is Rg03PreparedOperation.StoreSource -> expected
                                is Rg03PreparedOperation.MergeMirror -> Rg03ExecutionResult.Accepted(emptyList())
                            }
                        },
                )
            assertIs<Rg03ExecutionResult.Accepted>(
                executor.execute(
                    Rg03Command.ConfirmCandidate(
                        RequestId("confirm-a"),
                        candidate.candidateId,
                        true,
                        context.ledgerId,
                    ),
                ),
            )

            assertEquals(expected, executor.execute(start))
            assertIs<Rg03ExecutionResult.Accepted>(executor.execute(Rg03Command.ImportMirror(mirrorSnapshot())))
            assertEquals(0, mirrorBindings)
        }

        assertCleared(
            manualCommand(),
            Rg03ExecutionResult.Rejected(Rg03ExecutionError.DOMAIN_VALIDATION_FAILED),
        )
        assertCleared(
            Rg03Command.ImportSource(sourceSnapshot()),
            Rg03ExecutionResult.RequestIdentityConflict,
        )
        assertCleared(
            Rg03Command.ConfirmCandidate(
                RequestId("confirm-rejected"),
                CandidateId("candidate-b"),
                false,
                context.ledgerId,
            ),
            Rg03ExecutionResult.Rejected(Rg03ExecutionError.EXPLICIT_CONFIRMATION_REQUIRED),
        )
    }

    @Test
    fun `recovery candidate id mismatch rejects without commit and clears stale scope`() {
        val candidateA = persistedCandidate()
        val candidateB = CandidateId("candidate-b")
        val committed = mutableListOf<Rg03PreparedOperation>()
        var mirrorBindings = 0
        val executor =
            ExecuteRg03Operation(
                candidateRecovery = Rg03CandidateRecoveryPort { _, _ -> candidateA },
                mirrorBinding =
                    Rg03MirrorBindingPort { _, _ ->
                        mirrorBindings++
                        Rg03MirrorBindingResult.Ambiguous
                    },
                commitPort =
                    Rg03PreparedOperationCommitPort { prepared ->
                        committed += prepared
                        Rg03ExecutionResult.Accepted(emptyList())
                    },
            )
        assertIs<Rg03ExecutionResult.Accepted>(
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("confirm-a"),
                    candidateA.candidateId,
                    true,
                    context.ledgerId,
                ),
            ),
        )
        committed.clear()

        assertEquals(
            Rg03ExecutionResult.Rejected(Rg03ExecutionError.CANDIDATE_NOT_FOUND),
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("confirm-b"),
                    candidateB,
                    true,
                    context.ledgerId,
                ),
            ),
        )
        assertEquals(emptyList(), committed)
        assertIs<Rg03ExecutionResult.Accepted>(executor.execute(Rg03Command.ImportMirror(mirrorSnapshot())))
        assertEquals(0, mirrorBindings)
        assertIs<Rg03PreparedOperation.MergeMirror>(committed.single()).also { assertNull(it.target) }
    }

    @Test
    fun `mirror in another ledger cannot reuse the same candidate id scope`() {
        val candidate = persistedCandidate()
        val otherLedger = LedgerId("ledger-other")
        var mirrorBindings = 0
        val committed = mutableListOf<Rg03PreparedOperation>()
        val executor =
            ExecuteRg03Operation(
                candidateRecovery = Rg03CandidateRecoveryPort { _, _ -> candidate },
                mirrorBinding =
                    Rg03MirrorBindingPort { _, _ ->
                        mirrorBindings++
                        Rg03MirrorBindingResult.Ambiguous
                    },
                commitPort =
                    Rg03PreparedOperationCommitPort { prepared ->
                        committed += prepared
                        Rg03ExecutionResult.Accepted(emptyList())
                    },
            )
        assertIs<Rg03ExecutionResult.Accepted>(
            executor.execute(
                Rg03Command.ConfirmCandidate(
                    RequestId("confirm-a"),
                    candidate.candidateId,
                    true,
                    context.ledgerId,
                ),
            ),
        )
        committed.clear()

        assertIs<Rg03ExecutionResult.Accepted>(
            executor.execute(
                Rg03Command.ImportMirror(mirrorSnapshot().copy(ledgerId = otherLedger)),
            ),
        )
        assertEquals(0, mirrorBindings)
        assertIs<Rg03PreparedOperation.MergeMirror>(committed.single()).also { assertNull(it.target) }
    }

    private fun persistedCandidate() =
        Rg03PersistedTransferCandidate(
            ledgerId = context.ledgerId,
            candidateId = CandidateId("candidate-id"),
            status = CandidateStatus.PENDING_CONFIRMATION,
            sourceId = SourceRecordId("source-id"),
            evidenceId = EvidenceId("evidence-id"),
            sourceAccountId = AccountId("asset-bank-a"),
            destinationAccountId = AccountId("asset-wallet-b"),
            sourceDebit = Money.ofMinor(6_000L, context.currency),
            destinationCredit = Money.ofMinor(5_900L, context.currency),
            fee = Money.ofMinor(100L, context.currency),
            feeCategoryId = CategoryId("expense-category-transfer-fee"),
            observedAt = Instant.parse("2026-01-21T03:00:00Z"),
        )

    private fun sourceSnapshot() =
        Rg03SourceSnapshot(
            ledgerId = context.ledgerId,
            requestId = RequestId("source-b"),
            sourceId = SourceRecordId("source-b"),
            evidenceId = EvidenceId("evidence-b"),
            observedAt = Instant.parse("2026-01-21T03:02:00Z"),
            sourceAccountId = AccountId("asset-bank-a"),
            destinationAccountId = AccountId("asset-wallet-b"),
            sourceDebit = Money.ofMinor(6_000L, context.currency),
            destinationCredit = Money.ofMinor(5_900L, context.currency),
            fee = Money.ofMinor(100L, context.currency),
            feeCategoryId = CategoryId("expense-category-transfer-fee"),
            completeness = SourceCompleteness.COMPLETE,
        )

    private fun manualCommand(): Rg03Command.ManualTransfer {
        val decoded = assertIs<Rg03RawJsonDecodeResult.Success>(decodeRg03RawJson(validRg03Raw())).value
        return assertIs<Rg03Command.ManualTransfer>(
            assertIs<Rg03AdaptResult.Success>(adaptRg03Operation(context, decoded.operations.first())).command,
        )
    }

    private fun mirrorSnapshot() =
        Rg03MirrorSnapshot(
            ledgerId = context.ledgerId,
            requestId = RequestId("mirror"),
            sourceId = SourceRecordId("mirror-source"),
            evidenceId = EvidenceId("mirror-evidence"),
            observedAt = Instant.parse("2026-01-21T03:01:00Z"),
            accountId = AccountId("asset-wallet-b"),
            credit = Money.ofMinor(5_900L, context.currency),
        )
}
