package com.unifiedledger.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * D-085 RG-12 domain slice tests: the four new entity types, the posting_facts validation
 * (frozen first-failure order of `_posting_facts_correction_failure`) and the shared
 * `appendVersion` Postings form reserved by RG-11 for this slice.
 */
class ReconciliationCorrectionTest {
    private val fixture = Rg12CorrectionFixture()

    private fun attempt(
        transaction: FormalTransaction? = fixture.formalTransaction,
        replacements: List<ReplacementPostingInput> = fixture.validReplacements(),
        explicitConfirmation: Boolean = true,
        historyMutation: HistoryMutationInput? = null,
    ) = PostingFactsCorrectionAttempt(
        transaction = transaction,
        replacementPostings = replacements,
        explicitConfirmation = explicitConfirmation,
        historyMutation = historyMutation,
    )

    private fun validate(
        attempt: PostingFactsCorrectionAttempt,
        rejectChangedMatchedAsset: Boolean = true,
    ): DomainResult<Unit> = validatePostingFactsCorrection(
        attempt = attempt,
        accounts = fixture.accountsById,
        oldFactsByPosting = fixture.oldFactsByPosting,
        reconciliationsByPosting = fixture.reconciliationsByPosting,
        rejectChangedMatchedAsset = rejectChangedMatchedAsset,
    )

    private fun replaced(vararg replacements: ReplacementPostingInput): PostingFactsCorrectionAttempt =
        attempt(replacements = replacements.toList())

    /** A complete three-leg replacement set, mirroring the frozen root-correction input shape. */
    private fun triple(
        expense: ReplacementPostingInput = fixture.expenseReplacement(),
        asset: ReplacementPostingInput = fixture.assetReplacement(),
        liability: ReplacementPostingInput = fixture.liabilityReplacement(),
    ): PostingFactsCorrectionAttempt = attempt(replacements = listOf(expense, asset, liability))

    // ------------------------------------------------------------- frozen failures

    @Test
    fun rejectsUnknownTransactionAsIncompleteReplacement() {
        val result = validate(attempt(transaction = null))
        assertEquals(
            CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired(),
            failure(result),
        )
        assertEquals(
            "$.attempted_input.replacement_postings",
            (failure(result) as CorrectTransactionVersionViolation).fieldPath,
        )
    }

    @Test
    fun rejectsReplacementCountNotMatchingTheOldPostingCount() {
        val result = validate(replaced(fixture.expenseReplacement(), fixture.assetReplacement()))
        assertEquals(
            CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired(),
            failure(result),
        )
    }

    @Test
    fun rejectsUnbalancedReplacementSet() {
        val result = validate(triple(expense = fixture.expenseReplacement(amount = "109.00")))
        assertEquals(
            CorrectTransactionVersionViolation.ReplacementPostingsMustBalance(),
            failure(result),
        )
    }

    @Test
    fun reportsBalanceBeforeDuplicatesInTheFrozenOrder() {
        // Unbalanced AND duplicate source ids: the validator computes balance first.
        val result = validate(
            triple(
                expense = fixture.expenseReplacement(amount = "109.00"),
                liability = ReplacementPostingInput(
                    sourcePostingId = fixture.assetPostingId,
                    facts = fixture.liabilityFacts(amount = "-40.00"),
                ),
            ),
        )
        assertEquals(
            CorrectTransactionVersionViolation.ReplacementPostingsMustBalance(),
            failure(result),
        )
    }

    @Test
    fun rejectsDuplicateSourcePostingIdAtTheSecondOccurrence() {
        val result = validate(
            triple(
                liability = ReplacementPostingInput(
                    sourcePostingId = fixture.assetPostingId,
                    facts = fixture.liabilityFacts(amount = "-40.00"),
                ),
            ),
        )
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.DuplicateSourcePostingId(2), violation)
        assertEquals(
            "$.attempted_input.replacement_postings[2].source_posting_id",
            violation.fieldPath,
        )
    }

    @Test
    fun rejectsSourceSetNotCoveringTheOldCurrentPostings() {
        val foreign = PostingId("root-correction-foreign-v1")
        val result = validate(
            triple(
                liability = ReplacementPostingInput(
                    sourcePostingId = foreign,
                    facts = fixture.liabilityFacts(amount = "-40.00"),
                ),
            ),
        )
        assertEquals(
            CorrectTransactionVersionViolation.CompleteReplacementPostingsRequired(),
            failure(result),
        )
    }

    @Test
    fun rejectsUnknownAccount() {
        val result = validate(
            triple(asset = fixture.assetReplacement(accountId = AccountId("unknown-account"))),
        )
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.KnownAccountRequired(1), violation)
        assertEquals(
            "$.attempted_input.replacement_postings[1].account_id",
            violation.fieldPath,
        )
    }

    @Test
    fun rejectsNonOwnedNonExpenseAccount() {
        val result = validate(
            triple(asset = fixture.assetReplacement(accountId = fixture.externalAccountId)),
        )
        assertEquals(
            CorrectTransactionVersionViolation.OwnedAccountRequired(1),
            failure(result),
        )
    }

    @Test
    fun rejectsAccountCurrencyMismatch() {
        val result = validate(
            triple(asset = fixture.assetReplacement(accountId = fixture.usdAccountId)),
        )
        assertEquals(
            CorrectTransactionVersionViolation.AccountCurrencyMismatch(1),
            failure(result),
        )
    }

    @Test
    fun rejectsChangedMatchedAssetPostingByDefault() {
        val result = validate(
            triple(
                expense = fixture.expenseReplacement(amount = "100.00"),
                asset = fixture.assetReplacement(amount = "-60.00"),
            ),
        )
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.MatchedUnaffectedPostingMustBePreserved(1), violation)
        assertEquals(
            "$.attempted_input.replacement_postings[1]",
            violation.fieldPath,
        )
    }

    @Test
    fun rejectsMissingExplicitConfirmation() {
        val result = validate(attempt(explicitConfirmation = false))
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.ExplicitConfirmationRequired, violation)
        assertEquals(
            "$.attempted_input.explicit_confirmation",
            violation.fieldPath,
        )
    }

    @Test
    fun rejectsNonExactDecimalAmountAtTheFirstFailingIndex() {
        // A JSON number 110.0 arrives as the text "110.0": balance passes, exact format fails.
        val result = validate(triple(expense = fixture.expenseReplacement(amount = "110.0")))
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.ExactDecimalStringRequired(0), violation)
        assertEquals(
            "$.attempted_input.replacement_postings[0].amount",
            violation.fieldPath,
        )
    }

    @Test
    fun rejectsHistoryMutationAsImmutableFacts() {
        val result = validate(
            attempt(
                historyMutation = HistoryMutationInput(
                    matchId = "root-correction-match-liability-v1",
                    statusHistory = emptyList(),
                ),
            ),
        )
        val violation = failure(result) as CorrectTransactionVersionViolation
        assertEquals(CorrectTransactionVersionViolation.HistoricalFactsImmutable, violation)
        assertEquals(
            "$.attempted_input.history_mutation",
            violation.fieldPath,
        )
    }

    @Test
    fun frozenRejectionOrderPinsEarlierChecksBeforeLaterOnes() {
        // Account checks precede the confirmation gate; a missing confirmation must not mask
        // an unknown account at index 1.
        val result = validate(
            attempt(
                replacements = listOf(
                    fixture.expenseReplacement(),
                    fixture.assetReplacement(accountId = AccountId("unknown-account")),
                    fixture.liabilityReplacement(),
                ),
                explicitConfirmation = false,
            ),
        )
        assertEquals(
            CorrectTransactionVersionViolation.KnownAccountRequired(1),
            failure(result),
        )
        // The exact-decimal gate precedes the history-mutation gate.
        val result2 = validate(
            attempt(
                replacements = listOf(
                    fixture.expenseReplacement(amount = "110.0"),
                    fixture.assetReplacement(),
                    fixture.liabilityReplacement(),
                ),
                historyMutation = HistoryMutationInput(
                    matchId = "root-correction-match-liability-v1",
                    statusHistory = emptyList(),
                ),
            ),
        )
        assertEquals(
            CorrectTransactionVersionViolation.ExactDecimalStringRequired(0),
            failure(result2),
        )
    }

    @Test
    fun allTenFrozenReasonCodesAreExposed() {
        val reasons = setOf(
            (failure(validate(attempt(transaction = null))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(expense = fixture.expenseReplacement(amount = "109.00")))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(liability = ReplacementPostingInput(fixture.assetPostingId, fixture.liabilityFacts(amount = "-40.00"))))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(asset = fixture.assetReplacement(accountId = AccountId("unknown-account"))))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(asset = fixture.assetReplacement(accountId = fixture.externalAccountId)))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(asset = fixture.assetReplacement(accountId = fixture.usdAccountId)))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(expense = fixture.expenseReplacement(amount = "100.00"), asset = fixture.assetReplacement(amount = "-60.00")))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(attempt(explicitConfirmation = false))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(triple(expense = fixture.expenseReplacement(amount = "110.0")))) as CorrectTransactionVersionViolation).reasonCode,
            (failure(validate(attempt(historyMutation = HistoryMutationInput("root-correction-match-liability-v1", emptyList())))) as CorrectTransactionVersionViolation).reasonCode,
        )
        assertEquals(
            setOf(
                "complete_replacement_postings_required",
                "replacement_postings_must_balance",
                "duplicate_source_posting_id",
                "known_account_required",
                "owned_account_required",
                "account_currency_mismatch",
                "matched_unaffected_posting_must_be_preserved",
                "explicit_confirmation_required",
                "exact_decimal_string_required",
                "historical_facts_immutable",
            ),
            reasons,
        )
    }

    // ------------------------------------------------------------- accepted path

    @Test
    fun acceptsTheFrozenMainCorrection() {
        // liability -50.00 -> -40.00, expense 120.00 -> 110.00, asset leg unchanged and
        // explicitly present (rg-12.json root-correction-correct).
        val result = validate(
            replaced(
                fixture.expenseReplacement(amount = "110.00"),
                fixture.assetReplacement(),
                fixture.liabilityReplacement(amount = "-40.00"),
            ),
        )
        assertIs<DomainResult.Success<Unit>>(result)
        // The accepted path also passes with the symmetric lineage option.
        assertIs<DomainResult.Success<Unit>>(
            validate(
                replaced(
                    fixture.expenseReplacement(amount = "110.00"),
                    fixture.assetReplacement(),
                    fixture.liabilityReplacement(amount = "-40.00"),
                ),
                rejectChangedMatchedAsset = false,
            ),
        )
    }

    @Test
    fun acceptsChangedMatchedAssetWithSymmetricLineage() {
        // changed_asset_case of test_rg12_golden_v2.py: expense 100.00 / asset -60.00 with the
        // asset leg invalidated and rematched; only the accepted-path option allows it.
        val result = validate(
            triple(
                expense = fixture.expenseReplacement(amount = "100.00"),
                asset = fixture.assetReplacement(amount = "-60.00"),
            ),
            rejectChangedMatchedAsset = false,
        )
        assertIs<DomainResult.Success<Unit>>(result)
        // The frozen rejection path (default) still rejects the same input.
        assertEquals(
            CorrectTransactionVersionViolation.MatchedUnaffectedPostingMustBePreserved(1),
            failure(
                validate(
                    triple(
                        expense = fixture.expenseReplacement(amount = "100.00"),
                        asset = fixture.assetReplacement(amount = "-60.00"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun acceptsChangedMatchedLiabilityByDefault() {
        // The liability leg is matched too but is not an asset: the rejection-path guard only
        // protects asset legs (validator `reject_changed_asset=True`), so the frozen main
        // correction (liability -50.00 -> -40.00) passes with the default option.
        val result = validate(replaced(fixture.expenseReplacement(amount = "110.00"), fixture.assetReplacement(), fixture.liabilityReplacement(amount = "-40.00")))
        assertIs<DomainResult.Success<Unit>>(result)
    }

    @Test
    fun acceptsExactDecimalAmountsOfTheCurrencyPrecision() {
        // Zero amounts are valid exact decimals; the matched asset leg stays unchanged.
        assertIs<DomainResult.Success<Unit>>(
            validate(
                replaced(
                    fixture.expenseReplacement(amount = "0.00"),
                    fixture.assetReplacement(),
                    fixture.liabilityReplacement(amount = "70.00"),
                ),
            ),
        )
    }

    // ------------------------------------------------------------- reconciliation match

    @Test
    fun reconciliationMatchAppendsOnlyMatchedThenInvalidatedHistory() {
        val entry = fixture.matchedEntry()
        val match = success(
            createReconciliationMatch(
                id = "root-correction-match-asset-v1",
                postingId = fixture.assetPostingId,
                evidenceId = "root-correction-evidence-asset",
                statusHistory = listOf(entry),
            ),
        )
        assertEquals(ReconciliationMatchStatus.MATCHED, match.currentStatus)
        assertEquals(listOf(1), match.statusHistory.map { it.sequence })

        val invalidated = success(
            invalidateReconciliationMatch(
                match = match,
                entryId = "root-correction-match-asset-v1-history-2",
                at = Instant.parse("2026-04-20T10:00:00+08:00"),
            ),
        )
        assertEquals(
            listOf(ReconciliationMatchStatus.MATCHED, ReconciliationMatchStatus.INVALIDATED),
            invalidated.statusHistory.map { it.status },
        )
        assertEquals(listOf(1, 2), invalidated.statusHistory.map { it.sequence })
        assertEquals(
            listOf(ReconciliationMatchReason.EXACT_EVIDENCE, ReconciliationMatchReason.POSTING_REPLACED),
            invalidated.statusHistory.map { it.reason },
        )
        assertEquals(ReconciliationMatchStatus.INVALIDATED, invalidated.currentStatus)
        // The original match object keeps its untouched history.
        assertEquals(listOf(1), match.statusHistory.map { it.sequence })
    }

    @Test
    fun rejectsInvalidReconciliationMatchHistories() {
        val valid = ReconciliationMatchStatusEntry(
            id = "h-1",
            sequence = 1,
            status = ReconciliationMatchStatus.MATCHED,
            at = Instant.parse("2026-04-11T09:00:00+08:00"),
            reason = ReconciliationMatchReason.EXACT_EVIDENCE,
        )
        assertEquals(
            ReconciliationMatchViolation.IdentityRequired,
            failure(createReconciliationMatch("", fixture.assetPostingId, "ev", listOf(valid))),
        )
        assertEquals(
            ReconciliationMatchViolation.HistoryRequired,
            failure(createReconciliationMatch("m", fixture.assetPostingId, "ev", emptyList())),
        )
        assertEquals(
            ReconciliationMatchViolation.InvalidHistorySequence,
            failure(
                createReconciliationMatch(
                    "m",
                    fixture.assetPostingId,
                    "ev",
                    listOf(valid.copy(sequence = 2)),
                ),
            ),
        )
        assertEquals(
            ReconciliationMatchViolation.InvalidInitialStatus,
            failure(
                createReconciliationMatch(
                    "m",
                    fixture.assetPostingId,
                    "ev",
                    listOf(valid.copy(status = ReconciliationMatchStatus.INVALIDATED)),
                ),
            ),
        )
        assertEquals(
            ReconciliationMatchViolation.InvalidInitialStatus,
            failure(
                createReconciliationMatch(
                    "m",
                    fixture.assetPostingId,
                    "ev",
                    listOf(valid.copy(reason = ReconciliationMatchReason.POSTING_REPLACED)),
                ),
            ),
        )
        assertEquals(
            ReconciliationMatchViolation.InvalidStatusTransition,
            failure(
                createReconciliationMatch(
                    "m",
                    fixture.assetPostingId,
                    "ev",
                    listOf(
                        valid,
                        valid.copy(
                            id = "h-2",
                            sequence = 2,
                            status = ReconciliationMatchStatus.MATCHED,
                            reason = ReconciliationMatchReason.EXACT_EVIDENCE,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun rejectsInvalidatingAnAlreadyInvalidatedMatch() {
        val match = success(
            createReconciliationMatch(
                id = "m",
                postingId = fixture.assetPostingId,
                evidenceId = "ev",
                statusHistory = listOf(fixture.matchedEntry()),
            ),
        )
        val invalidated = success(
            invalidateReconciliationMatch(match, "h-2", Instant.parse("2026-04-20T10:00:00+08:00")),
        )
        assertEquals(
            ReconciliationMatchViolation.InvalidStatusTransition,
            failure(invalidateReconciliationMatch(invalidated, "h-3", Instant.parse("2026-04-21T10:00:00+08:00"))),
        )
    }

    // ------------------------------------------------------------- posting reconciliation

    @Test
    fun postingReconciliationCreationAndStatusDerivation() {
        val matched = success(
            createPostingReconciliation(
                id = "root-correction-reconciliation-asset-v1",
                postingId = fixture.assetPostingId,
                status = PostingReconciliationStatus.MATCHED,
            ),
        )
        assertEquals(PostingReconciliationStatus.MATCHED, matched.status)
        assertEquals(
            PostingReconciliationViolation.IdentityRequired,
            failure(createPostingReconciliation("", fixture.assetPostingId, PostingReconciliationStatus.PENDING)),
        )

        assertEquals(
            PostingReconciliationStatus.MATCHED,
            replacementPostingReconciliationStatus(ReconciliationEffect.PRESERVED),
        )
        assertEquals(
            PostingReconciliationStatus.PENDING,
            replacementPostingReconciliationStatus(ReconciliationEffect.INVALIDATED),
        )
        assertNull(replacementPostingReconciliationStatus(ReconciliationEffect.NOT_APPLICABLE))
    }

    @Test
    fun derivesTheTransactionReconciliationSummary() {
        assertEquals(
            ReconciliationSummary.MATCHED,
            deriveReconciliationSummary(listOf(PostingReconciliationStatus.MATCHED, PostingReconciliationStatus.MATCHED)),
        )
        assertEquals(
            ReconciliationSummary.PENDING,
            deriveReconciliationSummary(listOf(PostingReconciliationStatus.PENDING, PostingReconciliationStatus.PENDING)),
        )
        assertEquals(
            ReconciliationSummary.PARTIAL,
            deriveReconciliationSummary(listOf(PostingReconciliationStatus.MATCHED, PostingReconciliationStatus.PENDING)),
        )
        // The corrected transaction (asset matched, liability pending) is partial.
        assertEquals(
            ReconciliationSummary.PARTIAL,
            deriveReconciliationSummary(
                listOf(
                    PostingReconciliationStatus.MATCHED,
                    PostingReconciliationStatus.PENDING,
                ),
            ),
        )
    }

    // ------------------------------------------------------------- posting replacement

    @Test
    fun derivesReplacementEffectsByRealnessAndFacts() {
        val expenseAccount = fixture.accountsById.getValue(fixture.expenseAccountId)
        assertEquals(
            ReconciliationEffect.NOT_APPLICABLE,
            derivePostingReplacementEffect(fixture.expenseFacts(), fixture.expenseFacts(), expenseAccount),
        )
        assertEquals(
            ReconciliationEffect.NOT_APPLICABLE,
            derivePostingReplacementEffect(
                fixture.expenseFacts(amount = "120.00"),
                fixture.expenseFacts(amount = "110.00"),
                expenseAccount,
            ),
        )
        val assetAccount = fixture.accountsById.getValue(fixture.assetAccountId)
        assertEquals(
            ReconciliationEffect.PRESERVED,
            derivePostingReplacementEffect(fixture.assetFacts(), fixture.assetFacts(), assetAccount),
        )
        assertEquals(
            ReconciliationEffect.INVALIDATED,
            derivePostingReplacementEffect(
                fixture.assetFacts(),
                fixture.assetFacts(amount = "-60.00"),
                assetAccount,
            ),
        )
    }

    @Test
    fun postingReplacementValidatesTheGoldenAuditRules() {
        val assetMatch = success(
            createReconciliationMatch(
                id = "root-correction-match-asset-v1",
                postingId = fixture.assetPostingId,
                evidenceId = "root-correction-evidence-asset",
                statusHistory = listOf(fixture.matchedEntry()),
            ),
        )
        val assetV2 = success(
            createReconciliationMatch(
                id = "root-correction-match-asset-v2",
                postingId = fixture.assetPostingIdV2,
                evidenceId = "root-correction-evidence-asset",
                statusHistory = listOf(fixture.matchedEntry(id = "h-a2")),
            ),
        )
        val activeMatches = mapOf(
            fixture.assetPostingId to assetMatch,
            fixture.assetPostingIdV2 to assetV2,
        )
        val assetAccount = fixture.accountsById.getValue(fixture.assetAccountId)
        val versionTwo = fixture.versionTwo()
        val liabilityV2Facts = fixture.liabilityFacts(amount = "-40.00")

        // preserved with unchanged facts and inherited same-evidence matches.
        assertEquals(
            ReconciliationEffect.PRESERVED,
            success(
                createPostingReplacement(
                    id = "root-correction-replacement-asset",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ).reconciliationEffect,
        )

        // not_applicable is limited to non-real postings.
        assertEquals(
            PostingReplacementViolation.NotApplicableOnRealPosting,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.NOT_APPLICABLE,
                ),
            ),
        )

        // preserved requires unchanged facts.
        assertEquals(
            PostingReplacementViolation.PreservedRequiresUnchangedFacts,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(amount = "-60.00"),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ),
        )

        // preserved requires an active predecessor and an inherited successor match for the
        // same evidence: missing successor, then mismatched evidence.
        assertEquals(
            PostingReplacementViolation.PreservedRequiresInheritedEvidenceMatch,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = mapOf(fixture.assetPostingId to assetMatch),
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ),
        )
        assertEquals(
            PostingReplacementViolation.PreservedRequiresInheritedEvidenceMatch,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = mapOf(
                        fixture.assetPostingId to assetMatch,
                        fixture.assetPostingIdV2 to assetV2.copy(evidenceId = "root-correction-evidence-liability"),
                    ),
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ),
        )

        // invalidated requires a changed reconciliation-relevant real posting.
        assertEquals(
            PostingReplacementViolation.InvalidatedRequiresChangedRealFacts,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.INVALIDATED,
                ),
            ),
        )
        val expenseAccount = fixture.accountsById.getValue(fixture.expenseAccountId)
        assertEquals(
            PostingReplacementViolation.InvalidatedRequiresChangedRealFacts,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.expensePostingId,
                    toPostingId = fixture.expensePostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.expenseFacts(),
                    toFacts = fixture.expenseFacts(amount = "110.00"),
                    fromAccount = expenseAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.INVALIDATED,
                ),
            ),
        )

        // the changed liability leg is a valid invalidated replacement.
        assertEquals(
            ReconciliationEffect.INVALIDATED,
            success(
                createPostingReplacement(
                    id = "root-correction-replacement-liability",
                    fromPostingId = fixture.liabilityPostingId,
                    toPostingId = fixture.liabilityPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = versionTwo,
                    fromFacts = fixture.liabilityFacts(),
                    toFacts = liabilityV2Facts,
                    fromAccount = fixture.accountsById.getValue(fixture.liabilityAccountId),
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.INVALIDATED,
                ),
            ).reconciliationEffect,
        )

        // non-consecutive versions are rejected.
        assertEquals(
            PostingReplacementViolation.NonConsecutiveVersions,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = versionTwo,
                    toVersion = versionTwo,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ),
        )
        assertEquals(
            PostingReplacementViolation.NonConsecutiveVersions,
            failure(
                createPostingReplacement(
                    id = "r",
                    fromPostingId = fixture.assetPostingId,
                    toPostingId = fixture.assetPostingIdV2,
                    fromVersion = fixture.versionOne,
                    toVersion = fixture.versionOne,
                    fromFacts = fixture.assetFacts(),
                    toFacts = fixture.assetFacts(),
                    fromAccount = assetAccount,
                    activeMatchesByPosting = activeMatches,
                    reconciliationEffect = ReconciliationEffect.PRESERVED,
                ),
            ),
        )
    }

    // ------------------------------------------------------------- shared appendVersion kernel

    @Test
    fun appendVersionPostingsFormCreatesAFreshValidatedPostingSet() {
        val versionTwoId = TransactionVersionId("root-correction-transaction-v2")
        val postingSetTwoId = PostingSetId("root-correction-set-v2")
        val appended = success(
            fixture.formalTransaction.appendVersion(
                change = TransactionVersionChange.Postings(
                    postings = listOf(
                        Posting(fixture.expensePostingIdV2, fixture.expenseAccountId, money(11_000, fixture.cny)),
                        Posting(fixture.assetPostingIdV2, fixture.assetAccountId, money(-7_000, fixture.cny)),
                        Posting(fixture.liabilityPostingIdV2, fixture.liabilityAccountId, money(-4_000, fixture.cny)),
                    ),
                ),
                ids = TransactionVersionAppendIds(versionId = versionTwoId),
                newPostingSetId = postingSetTwoId,
            ),
        )

        assertEquals(versionTwoId, appended.transaction.currentVersionId)
        assertEquals(2, appended.versions.size)
        val versionTwo = appended.versions.single { it.versionNumber == 2 }
        assertEquals(postingSetTwoId, versionTwo.postingSetId)
        assertEquals(fixture.versionOne.times, versionTwo.times)
        assertEquals(fixture.versionOne.note, versionTwo.note)
        assertEquals(listOf(fixture.postingSetOneId, postingSetTwoId), appended.postingSets.map { it.id })
        assertEquals(
            listOf(fixture.expensePostingId, fixture.assetPostingId, fixture.liabilityPostingId),
            appended.postingSets.first().postings.map { it.id },
        )
        // The original aggregate is untouched.
        assertEquals(fixture.versionOneId, fixture.formalTransaction.transaction.currentVersionId)
        assertEquals(1, fixture.formalTransaction.versions.size)
    }

    @Test
    fun appendVersionRejectsMispairedChangeFormsAndNewPostingSetIds() {
        val versionTwoId = TransactionVersionId("root-correction-transaction-v2")
        val postingSetTwoId = PostingSetId("root-correction-set-v2")
        // The Postings form requires the fresh posting set id.
        assertEquals(
            DomainViolation.InvalidFormalTransaction,
            failure(
                fixture.formalTransaction.appendVersion(
                    change = TransactionVersionChange.Postings(
                        postings = listOf(
                            Posting(fixture.expensePostingIdV2, fixture.expenseAccountId, money(11_000, fixture.cny)),
                            Posting(fixture.assetPostingIdV2, fixture.assetAccountId, money(-7_000, fixture.cny)),
                            Posting(fixture.liabilityPostingIdV2, fixture.liabilityAccountId, money(-4_000, fixture.cny)),
                        ),
                    ),
                    ids = TransactionVersionAppendIds(versionId = versionTwoId),
                    newPostingSetId = null,
                ),
            ),
        )
        // The other forms reject a fresh posting set id (only Postings may bind one).
        assertEquals(
            DomainViolation.InvalidFormalTransaction,
            failure(
                fixture.formalTransaction.appendVersion(
                    change = TransactionVersionChange.StatisticsAt(
                        Instant.parse("2026-04-20T10:00:00+08:00"),
                    ),
                    ids = TransactionVersionAppendIds(versionId = versionTwoId),
                    newPostingSetId = postingSetTwoId,
                ),
            ),
        )
        // An unbalanced fresh set is rejected by the shared PostingSet validation.
        assertEquals(
            DomainViolation.UnbalancedPostingSet,
            failure(
                fixture.formalTransaction.appendVersion(
                    change = TransactionVersionChange.Postings(
                        postings = listOf(
                            Posting(fixture.expensePostingIdV2, fixture.expenseAccountId, money(11_000, fixture.cny)),
                            Posting(fixture.assetPostingIdV2, fixture.assetAccountId, money(-6_000, fixture.cny)),
                            Posting(fixture.liabilityPostingIdV2, fixture.liabilityAccountId, money(-4_000, fixture.cny)),
                        ),
                    ),
                    ids = TransactionVersionAppendIds(versionId = versionTwoId),
                    newPostingSetId = postingSetTwoId,
                ),
            ),
        )
    }
}

private class Rg12CorrectionFixture {
    val ledgerId = LedgerId("ledger-rg-12")
    val cny = CurrencyUnit("CNY", 2)
    val usd = CurrencyUnit("USD", 2)

    val expenseAccountId = AccountId("root-correction-expense")
    val assetAccountId = AccountId("root-correction-asset")
    val liabilityAccountId = AccountId("root-correction-liability")
    val externalAccountId = AccountId("root-rejections-external")
    val usdAccountId = AccountId("root-rejections-usd")
    val categoryId = CategoryId("root-correction-category")

    val accounts = listOf(
        Account(expenseAccountId, ledgerId, AccountKind.EXPENSE, cny, ownedByUser = false, realAccount = false),
        Account(assetAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = true, realAccount = true),
        Account(liabilityAccountId, ledgerId, AccountKind.LIABILITY, cny, ownedByUser = true, realAccount = true),
        Account(externalAccountId, ledgerId, AccountKind.ASSET, cny, ownedByUser = false, realAccount = true),
        Account(usdAccountId, ledgerId, AccountKind.ASSET, usd, ownedByUser = true, realAccount = true),
    )
    val accountsById: Map<AccountId, Account> = accounts.associateBy { it.id }

    val transactionId = TransactionId("root-correction-transaction")
    val versionOneId = TransactionVersionId("root-correction-transaction-v1")
    val postingSetOneId = PostingSetId("root-correction-set-v1")
    val expensePostingId = PostingId("root-correction-expense-v1")
    val assetPostingId = PostingId("root-correction-asset-v1")
    val liabilityPostingId = PostingId("root-correction-liability-v1")
    val expensePostingIdV2 = PostingId("root-correction-expense-v2")
    val assetPostingIdV2 = PostingId("root-correction-asset-v2")
    val liabilityPostingIdV2 = PostingId("root-correction-liability-v2")

    val times = TransactionTimes(
        occurredAt = Instant.parse("2026-04-10T09:30:00+08:00"),
        statisticsAt = Instant.parse("2026-04-10T09:30:00+08:00"),
        effectiveAt = Instant.parse("2026-04-10T09:30:00+08:00"),
    )

    val postingSet = success(
        PostingSet.create(
            id = postingSetOneId,
            postings = listOf(
                Posting(expensePostingId, expenseAccountId, money(12_000, cny)),
                Posting(assetPostingId, assetAccountId, money(-7_000, cny)),
                Posting(liabilityPostingId, liabilityAccountId, money(-5_000, cny)),
            ),
        ),
    )
    val versionOne = TransactionVersion(
        id = versionOneId,
        transactionId = transactionId,
        versionNumber = 1,
        postingSetId = postingSetOneId,
        times = times,
        note = "mixed expense",
    )
    val formalTransaction = success(
        FormalTransaction.create(
            transaction = Transaction(transactionId, ledgerId, TransactionKind.EXPENSE, versionOneId),
            versions = listOf(versionOne),
            postingSets = listOf(postingSet),
        ),
    )

    fun versionTwo(): TransactionVersion =
        TransactionVersion(
            id = TransactionVersionId("root-correction-transaction-v2"),
            transactionId = transactionId,
            versionNumber = 2,
            postingSetId = PostingSetId("root-correction-set-v2"),
            times = times,
            note = "mixed expense",
        )

    fun expenseFacts(amount: String = "120.00") =
        PostingFacts(expenseAccountId, amount, cny, "expense", categoryId)

    fun assetFacts(amount: String = "-70.00") =
        PostingFacts(assetAccountId, amount, cny, "mixed_expense_asset_funding", null)

    fun liabilityFacts(amount: String = "-50.00") =
        PostingFacts(liabilityAccountId, amount, cny, "mixed_expense_credit_funding", null)

    val oldFactsByPosting: Map<PostingId, PostingFacts> = mapOf(
        expensePostingId to expenseFacts(),
        assetPostingId to assetFacts(),
        liabilityPostingId to liabilityFacts(),
    )

    val reconciliationsByPosting: Map<PostingId, PostingReconciliationStatus> = mapOf(
        assetPostingId to PostingReconciliationStatus.MATCHED,
        liabilityPostingId to PostingReconciliationStatus.MATCHED,
    )

    fun expenseReplacement(amount: String = "110.00") =
        ReplacementPostingInput(expensePostingId, expenseFacts(amount))

    fun assetReplacement(amount: String = "-70.00", accountId: AccountId = assetAccountId) =
        ReplacementPostingInput(assetPostingId, assetFacts(amount).copy(accountId = accountId))

    fun liabilityReplacement(amount: String = "-40.00") =
        ReplacementPostingInput(liabilityPostingId, liabilityFacts(amount))

    fun validReplacements(): List<ReplacementPostingInput> =
        listOf(
            expenseReplacement(),
            assetReplacement(),
            liabilityReplacement(),
        )

    fun matchedEntry(id: String = "root-correction-match-asset-v1-history-1") =
        ReconciliationMatchStatusEntry(
            id = id,
            sequence = 1,
            status = ReconciliationMatchStatus.MATCHED,
            at = Instant.parse("2026-04-11T09:00:00+08:00"),
            reason = ReconciliationMatchReason.EXACT_EVIDENCE,
        )
}
