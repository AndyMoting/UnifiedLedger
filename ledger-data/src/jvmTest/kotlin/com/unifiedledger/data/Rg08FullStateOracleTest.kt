package com.unifiedledger.data

import com.unifiedledger.application.Rg08ConfirmationId
import com.unifiedledger.application.Rg08ExecutionResult
import com.unifiedledger.application.Rg08FieldPath
import com.unifiedledger.application.Rg08FixtureCase
import com.unifiedledger.application.Rg08FixtureInputs
import com.unifiedledger.application.Rg08FixtureOperation
import com.unifiedledger.application.Rg08FormalTransactionRecord
import com.unifiedledger.application.Rg08InvalidPredicate
import com.unifiedledger.application.Rg08LendingCatalog
import com.unifiedledger.application.Rg08Operation
import com.unifiedledger.application.Rg08RejectionReason
import com.unifiedledger.application.Rg08Report
import com.unifiedledger.application.Rg08ReturnedId
import com.unifiedledger.application.Rg08Runtime
import com.unifiedledger.application.Rg08Snapshot
import com.unifiedledger.application.adaptRg08Fixture
import com.unifiedledger.application.parseRg08FixtureInputs
import com.unifiedledger.domain.AccountId
import com.unifiedledger.domain.CurrencyUnit
import com.unifiedledger.domain.LendingAuditLinkKind
import com.unifiedledger.domain.LendingConfirmationGateField
import com.unifiedledger.domain.LendingSourceKind
import com.unifiedledger.domain.Money
import com.unifiedledger.domain.TransactionKind
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RG-08 D-084 acceptance oracle: every frozen operation-shaped case in golden/rules/rg-08.json
 * (44 operations: accepted 6 / rejected 25 / no_change 13) is independently replayed against the
 * typed runtime and compared field by field with the frozen expected blocks: outcome, returned
 * IDs, complete canonical state, formal/intake deltas, report deltas, status changes,
 * rejected/no-change baseline equality and retry equality (D-084 oracle contract). Structured as
 * a 1:1 mirror of the RG-10 oracle (Rg10FullStateOracleTest) with the RG-08 projections
 * registered below. The runtime is driven purely through [Rg08Runtime] (no store), matching the
 * RG-10 precedent: the anchor-style retries resolve inside a runtime session.
 *
 * Registered projection rules:
 *
 * 1. The runtime stores absolute instants; the frozen v1 shapes render them in the case
 *    timezone (Asia/Shanghai, fixed +08:00 offset), so every instant is projected through the
 *    fixed-offset formatter.
 *
 * 2. Mirror/merge references are typed audit links in the runtime (RG08-GAP-03: they are never
 *    evidence-link fields); the frozen v1 shape carries `mirror_of_evidence_id` /
 *    `merged_into_evidence_link_id` on the merged evidence link, and the projection translates
 *    the runtime audit links back onto the link.
 *
 * 3. Frozen v1 states publish balances only for the lending-story accounts; zero-balance
 *    accounts outside the published set (asset-external-c, expense-validation) are omitted the
 *    same way by the projection.
 *
 * 4. Rejected field paths are asserted from an oracle-side mirror of the runtime rejection
 *    table (RG-10 precedent). Two frozen v1 `field` names do not project 1:1 onto the runtime
 *    normalized paths and are documented at [expectedFieldPath]: `negative-interest` (v1
 *    `interest_amount`, runtime normalizes nonnegativity to the principal field) and
 *    `guessed-split` (v1 `components`, runtime reports the split-source field).
 *
 * 5. The frozen `candidate_status` markers, `guessed_*`/`fallback_event_type` nulls and the
 *    v1 `expected_interest_metadata` interest/accrual numbers are v1-era declarations without a
 *    runtime owner (accrual interest is out of scope); zero formal effect is asserted through
 *    the effect counts and the before/after state equality instead.
 *
 * Registered deviations awaiting parent ruling (no mapping authority exists for them):
 *
 * - RG08-DEV-01: the runtime derives the manual bank-credit source's account anchor from the
 *   destination account (Rg08Operations.bookCollection); the frozen v1 manual credit source
 *   carries no account anchor. Asserted explicitly in [assertExpectedSpecifics]; the state
 *   projection renders only the v1 published fields.
 *
 * - RG08-DEV-02 (fixed in runtime): `bookCollection`'s destination-evidence branch now fires
 *   only on the imported path (where the credit branch did not run); the manual path books the
 *   single `evidence-link-rg08-manual-credit` destination link once, matching the frozen
 *   manual state's evidence_links.
 *
 * - RG08-DEV-03 (fixed in runtime): `AllocateLendingCollection` returns the frozen stable ids
 *   {transaction_id, version_id, settlement_id, position_id} (the
 *   `retry-rg08-request-cap-maximum` returned_stable_ids contract); expectedReturnedIds below
 *   mirrors that contract.
 */
class Rg08FullStateOracleTest {
    @Test
    fun `raw operation registry preserves all outcome families`() {
        val oracle = loadOracle()
        assertEquals(44, oracle.documents.size)
        assertEquals(6, oracle.fixture.allOperations.count { it.expectedStatus == "accepted" })
        assertEquals(25, oracle.fixture.allOperations.count { it.expectedStatus == "rejected" })
        assertEquals(13, oracle.fixture.allOperations.count { it.expectedStatus == "no_change" })
        assertEquals(oracle.documents.values.map { it.resolvedId }, oracle.fixture.allOperations.map { it.id })
    }

    @Test
    fun `canonical states duplicate operation baselines byte for byte`() {
        // RG08-SPEC-006: `canonical_states.lend_confirmed`/`import_pending` duplicate
        // `operation_baselines.baseline-rg08-lend-confirmed`/`baseline-rg08-import-pending`
        // (the frozen state id is the same in both documents, so the oracle states map keys
        // both copies under one id). Assert the two documents agree byte for byte so the
        // keyed-by-id merge can never hide a drift between the copies.
        val raw = Files.readString(repositoryFile("golden/rules/rg-08.json"))
        val fixtureJson = Json.parseToJsonElement(raw).jsonObject
        val canonicalStates = fixtureJson.getValue("canonical_states").jsonObject
        val operationBaselines = fixtureJson.getValue("operation_baselines").jsonObject
        val duplicated = listOf(
            "lend_confirmed" to "baseline-rg08-lend-confirmed",
            "import_pending" to "baseline-rg08-import-pending",
        )
        duplicated.forEach { (canonicalKey, baselineKey) ->
            val canonical = canonicalStates.getValue(canonicalKey).jsonObject
            val baseline = operationBaselines.getValue(baselineKey).jsonObject
            assertEquals(canonical, baseline, "$canonicalKey equals $baselineKey")
            assertEquals(
                canonical.toString(),
                baseline.toString(),
                "$canonicalKey serializes identically to $baselineKey",
            )
        }
    }

    @Test
    fun `runtime transaction times preserve RG08 fallback premise`() {
        val oracle = loadOracle()
        val snapshots = buildList {
            add(
                Rg08Runtime(
                    oracle.fixture.catalog,
                    oracle.fixture.lendingCatalog,
                    oracle.fixture.openingTransactions,
                ).snapshot(),
            )
            oracle.fixture.allOperations
                .filter { it.retryOf == null }
                .forEach { operation ->
                    val runtime = baselineRuntime(oracle, operation)
                    add(runtime.snapshot())
                    runtime.commit(operation.operation)
                    add(runtime.snapshot())
                }
        }
        val records = snapshots.flatMap { it.formalTransactions }
        val uniqueRecords = records.distinctBy { it.formalTransaction.transaction.id }

        assertEquals(5, uniqueRecords.size)
        records.forEach { record ->
            val formal = record.formalTransaction
            val transaction = formal.transaction
            assertEquals(1, formal.versions.size, "${transaction.id.value}: one runtime version")
            val currentVersion = formal.versions.single { it.id == transaction.currentVersionId }
            assertEquals(
                currentVersion.times.occurredAt,
                currentVersion.times.statisticsAt,
                "${transaction.id.value}: occurred/statistics time",
            )
            assertEquals(
                currentVersion.times.occurredAt,
                currentVersion.times.effectiveAt,
                "${transaction.id.value}: occurred/effective time",
            )
            when (transaction.kind) {
                TransactionKind.OPENING_BALANCE ->
                    assertEquals(null, record.statisticsAtText, "${transaction.id.value}: opening fallback")
                TransactionKind.LEND, TransactionKind.COLLECT ->
                    assertTrue(record.statisticsAtText != null, "${transaction.id.value}: explicit report time")
                else -> error("unexpected RG-08 formal transaction kind ${transaction.kind}")
            }
        }
        assertEquals(
            1,
            uniqueRecords.count { it.formalTransaction.transaction.kind == TransactionKind.OPENING_BALANCE },
        )
        assertEquals(
            uniqueRecords.size - 1,
            uniqueRecords.count {
                it.formalTransaction.transaction.kind == TransactionKind.LEND ||
                    it.formalTransaction.transaction.kind == TransactionKind.COLLECT
            },
        )
    }

    @Test
    fun `main path operations replay against full canonical states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { MAIN_PATH.contains(it.sourcePath) }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `principal cap rejection stays atomic with zero effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath == "$.principal_cap.over_balance_attempt" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `import path operations replay against pending and confirmed states`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.import_collection.") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `counterparty rename replays with zero formal effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath == "$.counterparty_identity.rename" }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `invalid inputs reject with exact reason and zero effect`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.sourcePath.startsWith("$.invalid_inputs") }
            .forEach { assertOperation(oracle, it) }
    }

    @Test
    fun `idempotency retries return no change with first-time ids`() {
        val oracle = loadOracle()
        oracle.fixture.allOperations
            .filter { it.retryOf != null }
            .forEach { assertOperation(oracle, it) }
    }

    private fun assertOperation(oracle: OracleFixture, operation: Rg08FixtureOperation) {
        val document = oracle.documents.getValue(operation.id).json
        val expected = document.getValue("expected").jsonObject
        val original = operation.retryOf?.let { inputId ->
            oracle.fixture.allOperations.firstOrNull { candidate ->
                candidate.retryOf == null && matchesInputId(candidate.operation, inputId)
            } ?: error("missing retry source $inputId for ${operation.id}")
        }
        val runtime = baselineRuntime(oracle, operation)
        val before = runtime.snapshot()
        val baselineStateId = operation.baselineStateId
            ?: error("${operation.id} has no baseline state id")
        if (baselineStateId == "state-rg08-opening") {
            assertOpeningBalances(oracle, before, operation.id)
        } else {
            val baselineDoc = oracle.states.getValue(baselineStateId)
            assertState(oracle, baselineDoc, before, baselineStateId, "${operation.id} baseline")
        }

        val result = runtime.commit(operation.operation)
        assertOutcome(oracle, document, operation, original, result, operation.id)
        val after = runtime.snapshot()

        when (operation.expectedStatus) {
            "accepted" -> {
                val resultStateId = operation.resultStateId
                if (resultStateId != null) {
                    val resultDoc = oracle.states.getValue(resultStateId)
                    assertState(oracle, resultDoc, after, resultStateId, "${operation.id} result")
                }
            }
            "rejected", "no_change" -> {
                val baselineDoc = oracle.states.getValue(baselineStateId)
                assertEquals(
                    projectState(before, baselineDoc),
                    projectState(after, baselineDoc),
                    "${operation.id}: non-mutating outcome changed state",
                )
            }
            else -> error("unsupported RG-08 expected status ${operation.expectedStatus}")
        }
        assertCounts(expected, before, after, operation.id)
        assertExpectedSpecifics(expected, before, after, operation, operation.id)
    }

    private fun assertOutcome(
        oracle: OracleFixture,
        document: JsonObject,
        operation: Rg08FixtureOperation,
        original: Rg08FixtureOperation?,
        result: Rg08ExecutionResult,
        label: String,
    ) {
        val expected = document.getValue("expected").jsonObject
        when (operation.expectedStatus) {
            "accepted" -> {
                val accepted = assertIs<Rg08ExecutionResult.Accepted>(result, label)
                assertEquals(
                    expectedReturnedIds(operation.operation, oracle.inputs),
                    accepted.returnedIds,
                    "$label: accepted IDs",
                )
            }
            "no_change" -> {
                if (operation.retryOf != null) {
                    // The frozen v1 retry block lists the original stable ids; the runtime maps
                    // an identical replay to NoChange carrying the first-time ids (D-084 retry).
                    // Anchor retries (source ids) replay the ids the anchor registered first.
                    val noChange = assertIs<Rg08ExecutionResult.NoChange>(result, label)
                    val firstIds = firstTimeIds(
                        original?.operation ?: error("$label retry has no original operation"),
                        operation.retryOf!!,
                        oracle.inputs,
                    )
                    assertEquals(firstIds, noChange.returnedIds, "$label: no-change IDs equal first-time IDs")
                    assertRetryStableIds(expected, noChange.returnedIds, label)
                } else {
                    // rename: zero effect on the frozen shape, but the runtime accepts and
                    // returns the counterparty/name-history ids (D-084 no_change count).
                    val accepted = assertIs<Rg08ExecutionResult.Accepted>(result, label)
                    assertEquals(
                        expectedReturnedIds(operation.operation, oracle.inputs),
                        accepted.returnedIds,
                        "$label: rename IDs",
                    )
                }
            }
            "rejected" -> {
                val rejected = assertIs<Rg08ExecutionResult.Rejected>(result, label)
                val reason = Rg08RejectionReason.entries.single { it.code == expected.string("reason") }
                assertEquals(reason, rejected.reason, "$label: rejection reason")
                assertEquals(expectedFieldPath(operation.operation, reason), rejected.fieldPath, "$label: field path")
            }
            else -> error("unsupported RG-08 expected status ${operation.expectedStatus}")
        }
    }

    /**
     * Mirrors the runtime rejection table (Rg08Operations.rejectInvalidInput plus the gate and
     * principal-cap rejectors). RG08-SPEC: rejected field paths get an authoritative oracle-side
     * expectation. Registered divergences from the frozen v1 `field` names: the runtime
     * normalizes nonnegativity to the principal field and explicit-split to the split-source
     * field (projection rule 4).
     */
    private fun expectedFieldPath(operation: Rg08Operation, reason: Rg08RejectionReason): Rg08FieldPath = when (operation) {
        is Rg08Operation.InvalidInput -> when (operation.input.predicate) {
            Rg08InvalidPredicate.EXACT_DECIMAL_TOTAL -> Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED
            Rg08InvalidPredicate.TOTAL_POSITIVE -> Rg08FieldPath.ATTEMPTED_TOTAL_RECEIVED
            Rg08InvalidPredicate.COMPONENTS_EQUAL_TOTAL -> Rg08FieldPath.ATTEMPTED_COMPONENTS
            Rg08InvalidPredicate.COMPONENT_NONNEGATIVE -> Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT
            Rg08InvalidPredicate.FEE_ZERO -> Rg08FieldPath.ATTEMPTED_FEE_AMOUNT
            Rg08InvalidPredicate.FEE_OUT_OF_SCOPE -> Rg08FieldPath.ATTEMPTED_FEE_AMOUNT
            Rg08InvalidPredicate.PRINCIPAL_EXCEEDS_OUTSTANDING -> Rg08FieldPath.ATTEMPTED_PRINCIPAL_AMOUNT
            Rg08InvalidPredicate.UNKNOWN_DESTINATION -> Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
            Rg08InvalidPredicate.UNOWNED_DESTINATION -> Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
            Rg08InvalidPredicate.NONFINANCIAL_DESTINATION -> Rg08FieldPath.ATTEMPTED_DESTINATION_ACCOUNT_ID
            Rg08InvalidPredicate.UNKNOWN_FUNDING_ACCOUNT -> Rg08FieldPath.ATTEMPTED_FUNDING_ACCOUNT_ID
            Rg08InvalidPredicate.UNKNOWN_COUNTERPARTY -> Rg08FieldPath.ATTEMPTED_COUNTERPARTY_ID
            Rg08InvalidPredicate.INVALID_BEHAVIOR -> Rg08FieldPath.ATTEMPTED_BEHAVIOR_CODE
            Rg08InvalidPredicate.EXPLICIT_COMPONENT_SPLIT -> Rg08FieldPath.ATTEMPTED_SPLIT_SOURCE
            Rg08InvalidPredicate.SAME_CURRENCY -> Rg08FieldPath.ATTEMPTED_CURRENCY
            Rg08InvalidPredicate.ACTIVE_EXACT_INTEREST_CATEGORY -> Rg08FieldPath.ATTEMPTED_INTEREST_CATEGORY_ID
        }
        is Rg08Operation.AllocateLendingCollection -> Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT
        is Rg08Operation.RejectIncompleteImportedConfirmation -> when (operation.input.missingField) {
            LendingConfirmationGateField.BEHAVIOR_CODE -> Rg08FieldPath.INPUT_BEHAVIOR_CODE
            LendingConfirmationGateField.COUNTERPARTY_ID -> Rg08FieldPath.INPUT_COUNTERPARTY_ID
            LendingConfirmationGateField.DESTINATION_ACCOUNT_ID -> Rg08FieldPath.INPUT_DESTINATION_ACCOUNT_ID
            LendingConfirmationGateField.PRINCIPAL_AMOUNT -> Rg08FieldPath.INPUT_PRINCIPAL_AMOUNT
            LendingConfirmationGateField.INTEREST_AND_FEE_AMOUNTS -> Rg08FieldPath.INPUT_INTEREST_AND_FEE_AMOUNTS
            LendingConfirmationGateField.ACTUAL_RECEIPT_TIME -> Rg08FieldPath.INPUT_ACTUAL_RECEIPT_TIME
        }
        else -> error("no field path expectation for ${operation.action.code}")
    }

    private fun assertState(
        oracle: OracleFixture,
        expected: JsonObject,
        actual: Rg08Snapshot,
        stateId: String,
        label: String,
    ) {
        assertTrue(expected.containsKey("id"), "$label: state must retain its frozen state ID")
        val expectedPayload = JsonObject(expected.filterKeys { it != "id" })
        val projected = projectState(actual, expectedPayload)
        assertEquals(expectedPayload.keys, projected.keys, "$label: complete state fields")
        expectedPayload.keys.forEach { key ->
            assertJsonEquals(expectedPayload[key], projected[key], "$label: complete state field $key")
        }
    }

    /**
     * Entity arrays are compared per entity by id, and only over the fields the frozen v1
     * entity publishes: runtime-only extra fields are not part of the v1 publication (RG-10
     * rule-3 precedent, applied per entity here). The entity count is still exact, so a
     * runtime entity the frozen state does not have (or vice versa) fails loudly.
     */
    private fun assertJsonEquals(expected: JsonElement?, projected: JsonElement?, label: String) {
        if (expected is JsonArray && projected is JsonArray && expected.all { it is JsonObject }) {
            assertEquals(expected.size, projected.size, "$label: entity count")
            val projectedById = projected.map { it.jsonObject }.associateBy { it.string("id") }
            expected.forEach { element ->
                val expectedEntity = element.jsonObject
                val id = expectedEntity.string("id")
                val projectedEntity = projectedById[id]
                    ?: error("$label: missing projected entity $id")
                expectedEntity.keys.forEach { field ->
                    assertEquals(expectedEntity[field], projectedEntity[field], "$label: entity $id field $field")
                }
            }
        } else {
            assertEquals(expected, projected, label)
        }
    }

    private fun assertOpeningBalances(oracle: OracleFixture, before: Rg08Snapshot, label: String) {
        val expected = oracle.openingExpectedBalances
        val projectedMap = linkedMapOf<String, JsonElement>()
        expected.keys.forEach { key ->
            before.balances[AccountId(key)]?.let { projectedMap[key] = json(moneyText(it)) }
        }
        assertEquals(expected, JsonObject(projectedMap), "$label: opening balances")
    }

    private fun assertCounts(expected: JsonObject, before: Rg08Snapshot, after: Rg08Snapshot, label: String) {
        val counts = allCounts(before, after)
        fun assertBlock(block: JsonObject?, message: String) {
            block?.forEach { (key, value) ->
                assertEquals(
                    value,
                    json(counts[key] ?: error("$label: unknown $message key $key")),
                    "$label: $message $key",
                )
            }
        }
        assertBlock(expected["effect_counts"]?.jsonObject, "effect count")
        assertBlock(expected["formal_effect_counts"]?.jsonObject, "formal effect count")
        assertBlock(expected["duplicate_counts"]?.jsonObject, "duplicate count")
        assertBlock(expected["intake_entity_deltas"]?.jsonObject, "intake delta")
        // mirror_evidence publishes flat top-level counts (some without the new_ prefix).
        expected.forEach { (key, value) ->
            if (key in counts) {
                assertEquals(value, json(counts.getValue(key)), "$label: flat count $key")
            }
        }
    }

    private fun assertExpectedSpecifics(
        expected: JsonObject,
        before: Rg08Snapshot,
        after: Rg08Snapshot,
        operation: Rg08FixtureOperation,
        label: String,
    ) {
        // confirm-import: candidate lifecycle and the frozen provenance projection. The
        // candidate_status markers on rejected paths are v1-era declarations (rule 5).
        if (operation.expectedStatus == "accepted") {
            expected["candidate_status"]?.let { value ->
                assertEquals(value, json(after.candidates.single().status.name.lowercase()), "$label: candidate status")
            }
        }
        expected["provenance"]?.jsonObject?.let { block ->
            val candidate = after.candidates.single()
            val creditSource = after.sourceRecords.single {
                it.id in candidate.sourceIds && it.kind == LendingSourceKind.BANK_CREDIT
            }
            val collectRecord = after.formalTransactions.single {
                it.formalTransaction.transaction.kind == TransactionKind.COLLECT
            }
            val provenance = after.confirmations.single {
                it.transactionId.value == collectRecord.formalTransaction.transaction.id.value
            }
            block["source_ids"]?.let { value ->
                assertEquals(value, JsonArray(listOf(json(creditSource.id))), "$label: provenance source ids")
            }
            block["candidate_id"]?.let { value ->
                assertEquals(value, json(candidate.id), "$label: provenance candidate id")
            }
            block["confirmation_request_id"]?.let { value ->
                assertEquals(value, json(provenance.confirmationRequestId), "$label: provenance confirmation request id")
            }
            block["rule_version"]?.let { value ->
                assertEquals(value, json(candidate.ruleVersion), "$label: provenance rule version")
            }
            block["confidence"]?.let { value ->
                assertEquals(value, json(candidate.confidence), "$label: provenance confidence")
            }
            block["original_source_payload_hash"]?.let { value ->
                assertEquals(value, json(creditSource.originalSourcePayloadHash!!), "$label: provenance original hash")
            }
        }
        if (operation.operation is Rg08Operation.MergeImportedEvidence) {
            val mirror = operation.operation as Rg08Operation.MergeImportedEvidence
            val targetRecord = after.formalTransactions.single { record ->
                record.formalTransaction.currentPostings().any { it.id == mirror.input.targetPostingId }
            }
            expected["merged_into_transaction_id"]?.let { value ->
                assertEquals(
                    value,
                    json(targetRecord.formalTransaction.transaction.id.value),
                    "$label: merged into transaction",
                )
            }
            expected["current_version_id"]?.let { value ->
                assertEquals(
                    value,
                    json(targetRecord.formalTransaction.transaction.currentVersionId.value),
                    "$label: mirror current version",
                )
            }
            // RG08-GAP-03 typed audit links for the mirror/merge references.
            assertEquals(
                setOf("evidence-rg08-import-credit"),
                after.auditLinks.filter { it.kind == LendingAuditLinkKind.MIRROR_OF_EVIDENCE }
                    .map { it.toId }.toSet(),
                "$label: mirror-of-evidence audit links",
            )
            assertEquals(
                setOf("evidence-link-rg08-import-posting"),
                after.auditLinks.filter { it.kind == LendingAuditLinkKind.MERGED_INTO_EVIDENCE_LINK }
                    .map { it.toId }.toSet(),
                "$label: merged-into-evidence-link audit links",
            )
        }
        if (operation.operation is Rg08Operation.RenameCounterparty) {
            val rename = operation.operation as Rg08Operation.RenameCounterparty
            assertEquals(
                rename.input.newDisplayName,
                after.counterpartyNames.getValue(rename.input.counterpartyId),
                "$label: renamed display name",
            )
        }
        // Registered deviation RG08-DEV-01: the runtime derives the manual bank-credit source's
        // account anchor from the destination account (Rg08Operations.bookCollection); the
        // frozen v1 manual credit source carries no account anchor (the import credit keeps
        // its explicit one). The state projection renders only the v1 published fields, so the
        // derivation is asserted here explicitly for visibility; pending parent ruling.
        val manual = operation.operation as? Rg08Operation.ValidateLendingSettlement
        if (manual != null && manual.ids.creditSourceId != null) {
            val creditSource = after.sourceRecords.single { it.id == manual.ids.creditSourceId!!.value }
            assertEquals(
                manual.input.destinationAccountId,
                creditSource.accountId,
                "$label: manual credit source account derivation (RG08-DEV-01)",
            )
        }
        // lend: the v1 expected-interest metadata projects income/net-worth through the report
        // delta; expected_interest/accrued_interest are v1 informational (projection rule 5).
        expected["expected_interest_metadata"]?.jsonObject?.let { block ->
            val delta = reportDelta(before, after)
            block["income"]?.let { value ->
                assertEquals(value, delta.getValue("ordinary_income"), "$label: interest metadata income")
            }
            block["net_worth_change"]?.let { value ->
                assertEquals(value, delta.getValue("net_worth_change"), "$label: interest metadata net worth")
            }
        }
        // RG08-QA-04: accepted operations publish the frozen transaction/settlement/position
        // entities directly in the expected block (lend, manual-collection, cap-maximum,
        // confirm-import); assert them against the runtime projection instead of relying on
        // the resulting_state comparison alone.
        expected["transaction"]?.jsonObject?.let { block ->
            val record = after.formalTransactions.single {
                it.formalTransaction.transaction.id.value == block.string("id")
            }
            assertJsonEquals(block, projectTransaction(record, after), "$label: expected transaction")
        }
        expected["settlement"]?.jsonObject?.let { block ->
            val settlement = after.settlements.single { it.id == block.string("id") }
            assertJsonEquals(block, projectSettlement(settlement), "$label: expected settlement")
        }
        expected["position"]?.jsonObject?.let { block ->
            val position = after.positions.single { it.id == block.string("id") }
            assertJsonEquals(block, projectPosition(position), "$label: expected position")
        }
        // RG08-QA-06 + RG08-SPEC-005: the frozen over-balance expected block publishes the
        // D-062 atomic-cap projection (allocated_principal / pending_total / auto_capped).
        // Assert from the unchanged position balance and the attempted input: nothing was
        // allocated, the full attempted total stays pending, and no auto cap fired.
        if (
            expected.containsKey("allocated_principal") ||
            expected.containsKey("pending_total") ||
            expected.containsKey("auto_capped")
        ) {
            val allocate = operation.operation as Rg08Operation.AllocateLendingCollection
            val positionId = before.positions.single { it.counterpartyId == allocate.input.counterpartyId }.id
            val allocated = before.positions.single { it.id == positionId }.principalBalanceMinor -
                after.positions.single { it.id == positionId }.principalBalanceMinor
            expected["allocated_principal"]?.let { value ->
                assertEquals(value, json(moneyText(allocated)), "$label: allocated principal (atomic cap)")
            }
            expected["pending_total"]?.let { value ->
                assertEquals(value, json(moneyText(allocate.input.totalReceived)), "$label: pending total")
            }
            expected["auto_capped"]?.let { value ->
                assertEquals(value, json(false), "$label: auto capped")
            }
        }
    }

    private fun assertRetryStableIds(expected: JsonObject, returnedIds: List<Rg08ReturnedId>, label: String) {
        val expectedIds = expected["returned_stable_ids"]?.jsonObject ?: return
        assertEquals(expectedIds, projectStableIds(returnedIds), "$label: returned stable IDs")
    }

    private fun projectStableIds(returnedIds: List<Rg08ReturnedId>): JsonObject {
        val components = mutableListOf<String>()
        val map = linkedMapOf<String, JsonElement>()
        returnedIds.forEach { id ->
            when (id) {
                is Rg08ReturnedId.Transaction -> map["transaction_id"] = json(id.id.value)
                is Rg08ReturnedId.Version -> map["version_id"] = json(id.id.value)
                is Rg08ReturnedId.Position -> map["position_id"] = json(id.id)
                is Rg08ReturnedId.Settlement -> map["settlement_id"] = json(id.id)
                is Rg08ReturnedId.Component -> components += id.id
                is Rg08ReturnedId.Candidate -> map["candidate_id"] = json(id.id.value)
                is Rg08ReturnedId.SourceRecord -> map["source_record_id"] = json(id.id.value)
                is Rg08ReturnedId.Evidence -> map["evidence_id"] = json(id.id.value)
                is Rg08ReturnedId.EvidenceLink -> map["evidence_link_id"] = json(id.id.value)
                is Rg08ReturnedId.TargetPosting -> map["target_posting_id"] = json(id.id.value)
                is Rg08ReturnedId.Counterparty -> map["counterparty_id"] = json(id.id)
                is Rg08ReturnedId.NameHistory -> map["name_history_id"] = json(id.id)
                is Rg08ReturnedId.Request -> map["request_id"] = json(id.id)
            }
        }
        if (components.isNotEmpty()) {
            map["component_ids"] = JsonArray(components.map(::json))
        }
        return JsonObject(map)
    }

    /**
     * First-time returned ids for a retry anchor: request anchors return the operation's own
     * ids; source anchors return the ids the runtime registered for that anchor
     * (Rg08Operations.registerAnchorReceipts mirror).
     */
    private fun firstTimeIds(operation: Rg08Operation, inputId: String, inputs: Rg08FixtureInputs): List<Rg08ReturnedId> =
        when (operation) {
            is Rg08Operation.ValidateLendingEvent -> if (inputId == operation.ids.sourceId.value) {
                listOf(
                    Rg08ReturnedId.SourceRecord(operation.ids.sourceId),
                    Rg08ReturnedId.Evidence(operation.ids.evidenceId),
                    Rg08ReturnedId.EvidenceLink(operation.ids.evidenceLinkId),
                    Rg08ReturnedId.TargetPosting(operation.ids.fundingPostingId),
                )
            } else {
                expectedReturnedIds(operation, inputs)
            }
            is Rg08Operation.ValidateLendingSettlement -> when (inputId) {
                operation.ids.confirmationSourceId?.value -> listOf(
                    Rg08ReturnedId.SourceRecord(operation.ids.confirmationSourceId!!),
                    Rg08ReturnedId.Transaction(operation.ids.transactionId),
                    Rg08ReturnedId.Settlement(operation.ids.settlementId),
                )
                operation.ids.creditSourceId?.value -> listOf(
                    Rg08ReturnedId.SourceRecord(operation.ids.creditSourceId!!),
                    Rg08ReturnedId.Evidence(operation.ids.creditEvidenceId!!),
                    Rg08ReturnedId.EvidenceLink(operation.ids.creditEvidenceLinkId!!),
                    Rg08ReturnedId.TargetPosting(operation.ids.destinationPostingId),
                )
                else -> expectedReturnedIds(operation, inputs)
            }
            is Rg08Operation.IngestImportedCollectionCandidate -> if (inputId == operation.input.agreementSourceId.value) {
                val positionId = inputs.ids.getValue("request-rg08-lend").getValue("position_id")
                    ?: error("missing lending position id anchor")
                listOf(
                    Rg08ReturnedId.SourceRecord(operation.input.agreementSourceId),
                    Rg08ReturnedId.Evidence(operation.ids.agreementEvidenceId),
                    Rg08ReturnedId.EvidenceLink(operation.ids.agreementEvidenceLinkId),
                    Rg08ReturnedId.Position(positionId),
                )
            } else {
                expectedReturnedIds(operation, inputs)
            }
            is Rg08Operation.MergeImportedEvidence -> listOf(
                Rg08ReturnedId.SourceRecord(operation.input.sourceId),
                Rg08ReturnedId.Evidence(operation.input.evidenceId),
                Rg08ReturnedId.EvidenceLink(operation.input.evidenceLinkId),
                Rg08ReturnedId.TargetPosting(operation.input.targetPostingId),
            )
            else -> expectedReturnedIds(operation, inputs)
        }

    private fun expectedReturnedIds(operation: Rg08Operation, inputs: Rg08FixtureInputs): List<Rg08ReturnedId> =
        when (operation) {
            is Rg08Operation.ValidateLendingEvent -> listOf(
                Rg08ReturnedId.Transaction(operation.ids.transactionId),
                Rg08ReturnedId.Version(operation.ids.versionId),
                Rg08ReturnedId.Position(operation.ids.positionId),
            )
            is Rg08Operation.ValidateLendingSettlement -> listOf(
                Rg08ReturnedId.Transaction(operation.ids.transactionId),
                Rg08ReturnedId.Version(operation.ids.versionId),
                Rg08ReturnedId.Settlement(operation.ids.settlementId),
                Rg08ReturnedId.Component(operation.ids.principalComponentId),
                Rg08ReturnedId.Component(operation.ids.interestComponentId),
                Rg08ReturnedId.Component(operation.ids.feeComponentId),
            )
            is Rg08Operation.IngestImportedCollectionCandidate -> listOf(
                Rg08ReturnedId.SourceRecord(operation.input.creditSourceId),
                Rg08ReturnedId.Candidate(operation.input.candidateId),
            )
            is Rg08Operation.ConfirmImportedCollection -> listOf(
                Rg08ReturnedId.Candidate(operation.input.candidateId),
                Rg08ReturnedId.Transaction(operation.ids.transactionId),
                Rg08ReturnedId.Version(operation.ids.versionId),
                Rg08ReturnedId.Settlement(operation.ids.settlementId),
            )
            is Rg08Operation.MergeImportedEvidence -> listOf(
                Rg08ReturnedId.SourceRecord(operation.input.sourceId),
                Rg08ReturnedId.Evidence(operation.input.evidenceId),
                Rg08ReturnedId.EvidenceLink(operation.input.evidenceLinkId),
                Rg08ReturnedId.TargetPosting(operation.input.targetPostingId),
            )
            is Rg08Operation.AllocateLendingCollection -> {
                // Frozen retry-rg08-request-cap-maximum returned_stable_ids contract
                // (RG08-DEV-03): {transaction_id, version_id, settlement_id, position_id}.
                // The runtime returns the resolved (lend) position id, anchored in the
                // runtime input like the import-candidate anchor above.
                val positionId = inputs.ids.getValue("request-rg08-lend").getValue("position_id")
                    ?: error("missing lending position id anchor")
                listOf(
                    Rg08ReturnedId.Transaction(operation.input.ids.transactionId),
                    Rg08ReturnedId.Version(operation.input.ids.versionId),
                    Rg08ReturnedId.Settlement(operation.input.ids.settlementId),
                    Rg08ReturnedId.Position(positionId),
                )
            }
            is Rg08Operation.RenameCounterparty -> listOf(
                Rg08ReturnedId.Counterparty(operation.input.counterpartyId),
                Rg08ReturnedId.NameHistory(operation.input.nameHistoryId),
            )
            is Rg08Operation.RetryIdempotentInput,
            is Rg08Operation.InvalidInput,
            is Rg08Operation.RejectIncompleteImportedConfirmation,
            -> emptyList()
        }

    private fun baselineRuntime(oracle: OracleFixture, operation: Rg08FixtureOperation): Rg08Runtime =
        buildStateRuntime(oracle, operation.baselineStateId ?: error("${operation.id} has no baseline state id"))

    /**
     * Multi-root state graph: every frozen state is reached by replaying its producer chain
     * from the opening (lend-confirmed serves the rejection/rename baselines, import-pending the
     * gate baselines, and the 12 retry baselines are the original operations' result states).
     */
    private fun buildStateRuntime(oracle: OracleFixture, stateId: String): Rg08Runtime {
        if (stateId == "state-rg08-opening") {
            return Rg08Runtime(
                oracle.fixture.catalog,
                oracle.fixture.lendingCatalog,
                oracle.fixture.openingTransactions,
            )
        }
        val producer = producerOf(oracle.fixture, stateId)
        if (producer != null) {
            val runtime = buildStateRuntime(oracle, producer.baselineStateId!!)
            val result = runtime.commit(producer.operation)
            check(result is Rg08ExecutionResult.Accepted) {
                "${producer.id} baseline producer did not accept: $result"
            }
            return runtime
        }
        // Retry baselines equal the original's result state (frozen 1:1 content); the original
        // is replayed first on its own baseline so the runtime carries the receipt that the
        // generic retry replays (retry equality, D-084).
        val retryOp = oracle.fixture.allOperations.firstOrNull {
            it.retryOf != null && it.baselineStateId == stateId
        } ?: error("no producer for canonical state $stateId")
        val original = oracle.fixture.allOperations.firstOrNull { candidate ->
            candidate.retryOf == null && matchesInputId(candidate.operation, retryOp.retryOf!!)
        } ?: error("missing retry source ${retryOp.retryOf} for $stateId")
        val runtime = buildStateRuntime(oracle, original.baselineStateId!!)
        val result = runtime.commit(original.operation)
        check(result is Rg08ExecutionResult.Accepted) {
            "${original.id} retry source did not accept: $result"
        }
        return runtime
    }

    private fun producerOf(fixture: Rg08FixtureCase, stateId: String): Rg08FixtureOperation? =
        fixture.allOperations.firstOrNull { candidate ->
            candidate.retryOf == null &&
                candidate.resultStateId == stateId &&
                candidate.baselineStateId != stateId
        }

    private fun matchesInputId(operation: Rg08Operation, inputId: String): Boolean =
        operation.identity.value == inputId ||
            (operation is Rg08Operation.ValidateLendingSettlement &&
                (operation.ids.confirmationSourceId?.value == inputId || operation.ids.creditSourceId?.value == inputId)) ||
            (operation is Rg08Operation.ValidateLendingEvent && operation.ids.sourceId.value == inputId) ||
            (operation is Rg08Operation.IngestImportedCollectionCandidate &&
                operation.input.agreementSourceId.value == inputId) ||
            (operation is Rg08Operation.MergeImportedEvidence && operation.input.requestId.value == inputId)

    // ------------------------------------------------------------------ projection

    private fun projectState(snapshot: Rg08Snapshot, expected: JsonObject): JsonObject {
        val nonOpening = nonOpeningTransactions(snapshot)
            .sortedBy { it.formalTransaction.transaction.id.value }
        return jsonObjectOf(
            "transactions" to JsonArray(nonOpening.map { projectTransaction(it, snapshot) }),
            "versions" to JsonArray(nonOpening.map(::projectVersion)),
            "positions" to JsonArray(snapshot.positions.sortedBy { it.id }.map(::projectPosition)),
            "settlements" to JsonArray(snapshot.settlements.sortedBy { it.id }.map(::projectSettlement)),
            "candidates" to JsonArray(snapshot.candidates.sortedBy { it.id }.map(::projectCandidate)),
            "confirmation_provenance" to JsonArray(snapshot.confirmations.sortedBy { it.id }.map(::projectConfirmation)),
            "source_records" to JsonArray(snapshot.sourceRecords.sortedBy { it.id }.map(::projectSourceRecord)),
            "evidence" to JsonArray(snapshot.evidence.sortedBy { it.id }.map(::projectEvidence)),
            "evidence_links" to JsonArray(
                snapshot.evidenceLinks.sortedBy { it.id }.map { projectEvidenceLink(it, snapshot.auditLinks) },
            ),
            "balances" to projectBalances(snapshot, expected),
            "reports" to projectReports(snapshot),
            "reconciliation" to JsonObject(snapshot.reconciliation.mapValues { (_, value) -> json(value) }),
        )
    }

    private fun projectTransaction(record: Rg08FormalTransactionRecord, snapshot: Rg08Snapshot): JsonObject {
        val formal = record.formalTransaction
        val transaction = formal.transaction
        val version = formal.versions.single { it.id == transaction.currentVersionId }
        val postings = formal.currentPostings()
        val semantics = snapshot.postingSemantics
        val kind = transaction.kind
        val fields = mutableListOf<Pair<String, JsonElement?>>()
        fields += "id" to json(transaction.id.value)
        fields += "current_version_id" to json(version.id.value)
        fields += "posting_set_id" to json(version.postingSetId.value)
        fields += "type" to json(if (kind == TransactionKind.LEND || kind == TransactionKind.COLLECT) "lending" else kind.name.lowercase())
        if (kind == TransactionKind.LEND || kind == TransactionKind.COLLECT) {
            fields += "behavior_code" to json(kind.name.lowercase())
            val settlement = snapshot.settlements.firstOrNull { it.transactionId == transaction.id }
            val counterpartyId = settlement?.counterpartyId ?: snapshot.positions
                .firstOrNull { position -> position.history.any { it.transactionId == transaction.id } }
                ?.counterpartyId
            fields += "counterparty_id" to counterpartyId?.let(::json)
            if (settlement != null) {
                fields += "settlement_id" to json(settlement.id)
            }
        }
        fields += "occurred_at" to json(instantText(version.times.occurredAt))
        fields += "statistics_at" to json(record.statisticsAtText ?: instantText(version.times.statisticsAt))
        fields += "effective" to json(true)
        fields += "postings" to JsonArray(postings.map { posting ->
            jsonObjectOf(
                "id" to json(posting.id.value),
                "account_id" to json(posting.accountId.value),
                "amount" to json(moneyText(posting.amount)),
                "currency" to json(posting.amount.currency.code),
                "reconciliation_eligible" to json(semantics[posting.id.value]?.reconciliationEligible ?: false),
            )
        })
        return jsonObjectOf(*fields.toTypedArray())
    }

    private fun projectVersion(record: Rg08FormalTransactionRecord): JsonObject {
        val formal = record.formalTransaction
        val version = formal.versions.single { it.id == formal.transaction.currentVersionId }
        return jsonObjectOf(
            "id" to json(version.id.value),
            "transaction_id" to json(version.transactionId.value),
            "posting_set_id" to json(version.postingSetId.value),
            "status" to json("current"),
            "created_at" to json(record.createdAtText ?: instantText(record.createdAt)),
        )
    }

    private fun projectPosition(position: com.unifiedledger.domain.LendingPosition): JsonObject = jsonObjectOf(
        "id" to json(position.id),
        "counterparty_id" to json(position.counterpartyId),
        "receivable_account_id" to json(position.receivableAccountId.value),
        "currency" to json(position.currency.code),
        "principal_balance" to json(moneyText(position.principalBalanceMinor)),
        "allocation_scope" to json(position.allocationScope.name.lowercase()),
        "contract_allocation_enabled" to json(position.contractAllocationEnabled),
        "history" to JsonArray(position.history.map { entry ->
            jsonObjectOf(
                "id" to json(entry.id),
                "behavior_code" to json(entry.behaviorCode.name.lowercase()),
                "amount" to json(moneyText(entry.amountMinor)),
                "principal_balance_after" to json(moneyText(entry.principalBalanceAfterMinor)),
                "transaction_id" to json(entry.transactionId.value),
                "occurred_at" to json(instantText(entry.occurredAt)),
            )
        }),
    )

    private fun projectSettlement(settlement: com.unifiedledger.domain.LendingSettlement): JsonObject = jsonObjectOf(
        "id" to json(settlement.id),
        "behavior_code" to json(settlement.behaviorCode.name.lowercase()),
        "counterparty_id" to json(settlement.counterpartyId),
        "linked_position_id" to json(settlement.linkedPositionId),
        "allocated_lend_transaction_id" to (settlement.allocatedLendTransactionId?.let { json(it.value) } ?: JsonNull),
        "transaction_id" to json(settlement.transactionId.value),
        "destination_account_id" to json(settlement.destinationAccountId.value),
        "interest_category_id" to json(settlement.interestCategoryId.value),
        "total_received" to json(moneyText(settlement.totalReceivedMinor)),
        "currency" to json(settlement.currency.code),
        "actual_receipt_at" to json(instantText(settlement.actualReceiptAt)),
        "confirmed_at" to json(instantText(settlement.confirmedAt)),
        "components" to JsonArray(settlement.components.map { component ->
            jsonObjectOf(
                "id" to json(component.id),
                "kind" to json(component.kind.name.lowercase()),
                "amount" to json(moneyText(component.amountMinor)),
                "posting_id" to (component.postingId?.let { json(it.value) } ?: JsonNull),
            )
        }),
        "history" to JsonArray(settlement.history.map { entry ->
            jsonObjectOf(
                "id" to json(entry.id),
                "status" to json(entry.status.name.lowercase()),
                "occurred_at" to json(instantText(entry.occurredAt)),
                "transaction_id" to json(entry.transactionId.value),
                "formal_effect_count" to json(entry.formalEffectCount),
            )
        }),
    )

    private fun projectCandidate(candidate: com.unifiedledger.domain.LendingCandidate): JsonObject = jsonObjectOf(
        "id" to json(candidate.id),
        "type" to json(candidate.type),
        "status" to json(candidate.status.name.lowercase()),
        "proposed_total_received" to json(moneyText(candidate.proposedTotalReceivedMinor)),
        "proposed_principal_amount" to (candidate.proposedPrincipalAmountMinor?.let { json(moneyText(it)) } ?: JsonNull),
        "proposed_interest_amount" to (candidate.proposedInterestAmountMinor?.let { json(moneyText(it)) } ?: JsonNull),
        "proposed_fee_amount" to (candidate.proposedFeeAmountMinor?.let { json(moneyText(it)) } ?: JsonNull),
        "currency" to json(candidate.currency.code),
        "proposed_destination_account_id" to (candidate.proposedDestinationAccountId?.let { json(it.value) } ?: JsonNull),
        "proposed_actual_receipt_at" to (candidate.proposedActualReceiptAt?.let { json(instantText(it)) } ?: JsonNull),
        "proposed_behavior_code" to (candidate.proposedBehaviorCode?.let { json(it.name.lowercase()) } ?: JsonNull),
        "proposed_counterparty_id" to (candidate.proposedCounterpartyId?.let(::json) ?: JsonNull),
        "bank_evidence_proves_component_split" to json(candidate.bankEvidenceProvesComponentSplit),
        "expected_interest_may_confirm_split" to json(candidate.expectedInterestMayConfirmSplit),
        "name_match_may_confirm_counterparty" to json(candidate.nameMatchMayConfirmCounterparty),
        "requires_confirmation" to JsonArray(candidate.requiresConfirmation.map { json(it.name.lowercase()) }),
        "source_ids" to JsonArray(candidate.sourceIds.map(::json)),
        "rule_version" to json(candidate.ruleVersion),
        "confidence" to json(candidate.confidence),
        "status_history" to JsonArray(candidate.statusHistory.map { entry ->
            jsonObjectOf(
                "id" to json(entry.id),
                "status" to json(entry.status.name.lowercase()),
                "occurred_at" to json(instantText(entry.occurredAt)),
                "formal_effect_count" to json(entry.formalEffectCount),
            )
        }),
    )

    private fun projectConfirmation(confirmation: com.unifiedledger.domain.LendingConfirmationProvenance): JsonObject {
        val fields = mutableListOf<Pair<String, JsonElement?>>()
        fields += "id" to json(confirmation.id)
        fields += "confirmation_request_id" to json(confirmation.confirmationRequestId)
        fields += "role" to json(confirmation.role.name.lowercase())
        fields += "transaction_id" to json(confirmation.transactionId.value)
        fields += "counterparty_id" to json(confirmation.counterpartyId)
        fields += "confirmed_at" to json(instantText(confirmation.confirmedAt))
        fields += "candidate_id" to confirmation.candidateId?.let(::json)
        fields += "settlement_id" to confirmation.settlementId?.let(::json)
        return jsonObjectOf(*fields.toTypedArray())
    }

    private fun projectSourceRecord(source: com.unifiedledger.domain.LendingSourceRecord): JsonObject {
        val fields = mutableListOf<Pair<String, JsonElement?>>()
        fields += "id" to json(source.id)
        fields += "source_record_id" to json(source.sourceRecordId)
        fields += "kind" to json(source.kind.code)
        fields += "observed_at" to json(instantText(source.observedAt))
        fields += "booking_at" to source.bookingAt?.let { json(instantText(it)) }
        fields += "value_at" to source.valueAt?.let { json(instantText(it)) }
        fields += "account_id" to source.accountId?.let { json(it.value) }
        fields += "counterparty_id" to source.counterpartyId?.let(::json)
        fields += "amount" to source.amountMinor?.let { json(moneyText(it)) }
        fields += "currency" to source.currency?.let { json(it.code) }
        fields += "original_source_payload_hash" to source.originalSourcePayloadHash?.let(::json)
        fields += "immutable_payload_hash" to json(source.immutablePayloadHash)
        fields += "mirror_of_source_id" to source.mirrorOfSourceId?.let(::json)
        return jsonObjectOf(*fields.toTypedArray())
    }

    private fun projectEvidence(evidence: com.unifiedledger.domain.LendingEvidence): JsonObject = jsonObjectOf(
        "id" to json(evidence.id),
        "source_id" to json(evidence.sourceId),
        "type" to json(evidence.type.name.lowercase()),
        "observed_at" to json(instantText(evidence.observedAt)),
    )

    /**
     * Registered evidence-link projection (rule 2): mirror/merge references are typed audit
     * links in the runtime and are rendered back onto the merged link for the frozen shape.
     */
    private fun projectEvidenceLink(
        link: com.unifiedledger.domain.LendingEvidenceLink,
        auditLinks: List<com.unifiedledger.domain.LendingAuditLink>,
    ): JsonObject {
        val fields = mutableListOf<Pair<String, JsonElement?>>()
        fields += "id" to json(link.id)
        fields += "source_id" to json(link.sourceId)
        fields += "evidence_id" to json(link.evidenceId)
        fields += "role" to json(link.role.name.lowercase())
        fields += "target_id" to json(link.targetId)
        fields += "status" to json(link.status.name.lowercase())
        fields += "mirror_of_evidence_id" to auditLinks.firstOrNull {
            it.kind == LendingAuditLinkKind.MIRROR_OF_EVIDENCE && it.fromId == link.evidenceId
        }?.let { json(it.toId) }
        fields += "merged_into_evidence_link_id" to auditLinks.firstOrNull {
            it.kind == LendingAuditLinkKind.MERGED_INTO_EVIDENCE_LINK && it.fromId == link.id
        }?.let { json(it.toId) }
        return jsonObjectOf(*fields.toTypedArray())
    }

    private fun projectBalances(snapshot: Rg08Snapshot, expected: JsonObject): JsonObject {
        // Frozen v1 states publish only the lending-story accounts (rule 3).
        val expectedKeys = expected["balances"]?.jsonObject?.keys ?: emptySet()
        val projectedMap = linkedMapOf<String, JsonElement>()
        snapshot.balances
            .filterKeys { it.value in expectedKeys }
            .forEach { (accountId, amount) ->
                projectedMap[accountId.value] = json(moneyText(amount))
            }
        return JsonObject(projectedMap)
    }

    private fun projectReports(snapshot: Rg08Snapshot): JsonObject = JsonObject(
        snapshot.reports.mapValues { (_, report) ->
            jsonObjectOf(
                "consumption" to json(moneyText(report.consumptionMinor)),
                "expense" to json(moneyText(report.expenseMinor)),
                "lending_principal_cash_outflow" to json(moneyText(report.lendingPrincipalCashOutflowMinor)),
                "cash_outflow" to json(moneyText(report.cashOutflowMinor)),
                "lending_principal_cash_inflow" to json(moneyText(report.lendingPrincipalCashInflowMinor)),
                "interest_cash_inflow" to json(moneyText(report.interestCashInflowMinor)),
                "total_cash_inflow" to json(moneyText(report.totalCashInflowMinor)),
                "ordinary_interest_income" to json(moneyText(report.ordinaryInterestIncomeMinor)),
                "ordinary_income" to json(moneyText(report.ordinaryIncomeMinor)),
                "net_worth_change" to json(moneyText(report.netWorthChangeMinor)),
            )
        },
    )

    // ------------------------------------------------------------------ counts

    private fun allCounts(before: Rg08Snapshot, after: Rg08Snapshot): Map<String, Int> = mapOf(
        "new_candidate_count" to after.candidates.size - before.candidates.size,
        "new_candidate_history_count" to historyEntryCount(after.candidates) - historyEntryCount(before.candidates),
        "new_position_count" to after.positions.size - before.positions.size,
        "new_position_event_count" to positionHistoryCount(after) - positionHistoryCount(before),
        "new_settlement_count" to after.settlements.size - before.settlements.size,
        "new_component_count" to componentCount(after) - componentCount(before),
        "new_transaction_count" to nonOpeningTransactions(after).size - nonOpeningTransactions(before).size,
        "new_posting_count" to postingCount(after) - postingCount(before),
        "new_version_count" to nonOpeningTransactions(after).size - nonOpeningTransactions(before).size,
        "new_history_count" to allHistoryCount(after) - allHistoryCount(before),
        "new_source_record_count" to after.sourceRecords.size - before.sourceRecords.size,
        "new_evidence_count" to after.evidence.size - before.evidence.size,
        "new_evidence_link_count" to after.evidenceLinks.size - before.evidenceLinks.size,
        "new_audit_link_count" to after.auditLinks.size - before.auditLinks.size,
        "balance_change_count" to balanceChangeCount(before, after),
        "report_change_count" to if (before.reports != after.reports) 1 else 0,
        "reconciliation_change_count" to reconciliationChangeCount(before, after),
        "consumption_effect_count" to if (cumulative(before, Rg08Report::consumptionMinor) != cumulative(after, Rg08Report::consumptionMinor)) 1 else 0,
        "cash_flow_effect_count" to if (cumulativeCashFlow(before) != cumulativeCashFlow(after)) 1 else 0,
        "income_effect_count" to if (cumulative(before, Rg08Report::ordinaryIncomeMinor) != cumulative(after, Rg08Report::ordinaryIncomeMinor)) 1 else 0,
    )

    private fun historyEntryCount(candidates: List<com.unifiedledger.domain.LendingCandidate>): Int =
        candidates.sumOf { it.statusHistory.size }

    private fun positionHistoryCount(snapshot: Rg08Snapshot): Int =
        snapshot.positions.sumOf { it.history.size }

    private fun componentCount(snapshot: Rg08Snapshot): Int =
        snapshot.settlements.sumOf { it.components.size }

    private fun allHistoryCount(snapshot: Rg08Snapshot): Int =
        positionHistoryCount(snapshot) +
            snapshot.settlements.sumOf { it.history.size } +
            historyEntryCount(snapshot.candidates)

    private fun balanceChangeCount(before: Rg08Snapshot, after: Rg08Snapshot): Int =
        (before.balances.keys + after.balances.keys).count { key ->
            before.balances[key]?.minorUnits != after.balances[key]?.minorUnits
        }

    private fun reconciliationChangeCount(before: Rg08Snapshot, after: Rg08Snapshot): Int =
        (before.reconciliation.keys + after.reconciliation.keys).count { key ->
            before.reconciliation[key] != after.reconciliation[key]
        }

    private fun cumulative(snapshot: Rg08Snapshot, metric: (Rg08Report) -> Long): Long =
        snapshot.reports["cumulative"]?.let(metric) ?: 0L

    private fun cumulativeCashFlow(snapshot: Rg08Snapshot): Long {
        val report = snapshot.reports["cumulative"] ?: return 0L
        return report.totalCashInflowMinor + report.cashOutflowMinor
    }

    private fun reportDelta(before: Rg08Snapshot, after: Rg08Snapshot): JsonObject {
        val previous = before.reports["cumulative"]
        val current = after.reports["cumulative"]
        fun delta(metric: (Rg08Report) -> Long): String =
            moneyText((current?.let(metric) ?: 0L) - (previous?.let(metric) ?: 0L))
        return jsonObjectOf(
            "ordinary_income" to json(delta { it.ordinaryIncomeMinor }),
            "net_worth_change" to json(delta { it.netWorthChangeMinor }),
        )
    }

    private fun nonOpeningTransactions(snapshot: Rg08Snapshot): List<Rg08FormalTransactionRecord> =
        snapshot.formalTransactions.filter {
            it.formalTransaction.transaction.kind != TransactionKind.OPENING_BALANCE
        }

    private fun postingCount(snapshot: Rg08Snapshot): Int =
        nonOpeningTransactions(snapshot).sumOf { it.formalTransaction.currentPostings().size }

    // ------------------------------------------------------------------ loading

    private fun rawOperationDocuments(fixture: JsonObject): List<RawOperationDocument> = buildList {
        add(RawOperationDocument("$.lend", fixture.getValue("lend").jsonObject, "lend"))
        add(RawOperationDocument("$.manual_collection", fixture.getValue("manual_collection").jsonObject, "manual-collection"))
        add(
            RawOperationDocument(
                "$.principal_cap.maximum_valid_collection",
                fixture.getValue("principal_cap").jsonObject.getValue("maximum_valid_collection").jsonObject,
                "cap-maximum",
            ),
        )
        add(
            RawOperationDocument(
                "$.principal_cap.over_balance_attempt",
                fixture.getValue("principal_cap").jsonObject.getValue("over_balance_attempt").jsonObject,
            ),
        )
        val imports = fixture.getValue("import_collection").jsonObject
        add(RawOperationDocument("$.import_collection.candidate", imports.getValue("candidate").jsonObject, "import-candidate"))
        imports.getValue("incomplete_confirmations").jsonArray.forEachIndexed { index, element ->
            add(RawOperationDocument("$.import_collection.incomplete_confirmations[$index]", element.jsonObject))
        }
        add(
            RawOperationDocument(
                "$.import_collection.confirmation",
                imports.getValue("confirmation").jsonObject,
                "confirm-import",
            ),
        )
        add(
            RawOperationDocument(
                "$.import_collection.mirror_evidence",
                imports.getValue("mirror_evidence").jsonObject,
                "mirror-evidence",
            ),
        )
        add(
            RawOperationDocument(
                "$.counterparty_identity.rename",
                fixture.getValue("counterparty_identity").jsonObject.getValue("rename").jsonObject,
                "rename-counterparty",
            ),
        )
        fixture.getValue("invalid_inputs").jsonArray.forEach { element ->
            add(RawOperationDocument("$.invalid_inputs[${element.jsonObject.string("id")}]", element.jsonObject))
        }
        fixture.getValue("idempotency").jsonObject.getValue("retries").jsonArray.forEachIndexed { index, element ->
            add(RawOperationDocument("$.idempotency.retries[$index]", element.jsonObject))
        }
    }

    private fun loadOracle(): OracleFixture {
        val raw = Files.readString(repositoryFile("golden/rules/rg-08.json"))
        val fixtureJson = Json.parseToJsonElement(raw).jsonObject
        val inputs = parseRg08FixtureInputs(Files.readString(repositoryFile("tests/fixtures/rg08-runtime-input.json")))
        val fixture = adaptRg08Fixture(raw, inputs)
        val documents = rawOperationDocuments(fixtureJson).associateBy { it.resolvedId }
        val states = buildMap {
            fixtureJson.getValue("canonical_states").jsonObject.forEach { (_, element) ->
                put(element.jsonObject.string("id"), element.jsonObject)
            }
            putAll(fixture.baselineStates)
        }
        return OracleFixture(
            fixture = fixture,
            inputs = inputs,
            documents = documents,
            states = states,
            openingExpectedBalances = fixtureJson.getValue("opening").jsonObject
                .getValue("expected_balances").jsonObject,
        )
    }

    private fun repositoryFile(relative: String): Path {
        var candidate = Path.of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }

    private data class OracleFixture(
        val fixture: Rg08FixtureCase,
        val inputs: Rg08FixtureInputs,
        val documents: Map<String, RawOperationDocument>,
        val states: Map<String, JsonObject>,
        val openingExpectedBalances: JsonObject,
    )

    private data class RawOperationDocument(val sourcePath: String, val json: JsonObject, val id: String? = null) {
        val resolvedId: String get() = id ?: json.string("id")
    }

    private companion object {
        val MAIN_PATH = setOf(
            "$.lend",
            "$.manual_collection",
            "$.principal_cap.maximum_valid_collection",
        )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private val SHANGHAI_INSTANT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

private fun instantText(instant: Instant): String =
    SHANGHAI_INSTANT_FORMAT.format(
        OffsetDateTime.ofInstant(java.time.Instant.parse(instant.toString()), ZoneOffset.ofHours(8)),
    )

private fun moneyText(amount: Money): String = moneyText(amount.minorUnits, amount.currency.precision)

private fun moneyText(minor: Long, precision: Int = 2): String =
    BigDecimal.valueOf(minor, precision).setScale(precision).toPlainString()

private fun json(value: String): JsonPrimitive = JsonPrimitive(value)
private fun json(value: Boolean): JsonPrimitive = JsonPrimitive(value)
private fun json(value: Int): JsonPrimitive = JsonPrimitive(value)

private fun jsonObjectOf(vararg fields: Pair<String, JsonElement?>): JsonObject =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private fun repositoryFile(relative: String): Path {
    var candidate = Path.of(System.getProperty("user.dir"))
    repeat(8) {
        if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate.resolve(relative)
        candidate = candidate.parent ?: error("repository root not found")
    }
    error("repository root not found")
}
