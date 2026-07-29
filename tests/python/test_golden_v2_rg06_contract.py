from copy import deepcopy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads(
    (ROOT / "schemas" / "golden-case-v2.schema.json").read_text(encoding="utf-8")
)

ENTITY_COLLECTIONS = (
    "catalog_accounts",
    "catalog_categories",
    "transactions",
    "transaction_versions",
    "posting_sets",
    "postings",
    "sources",
    "candidates",
    "confirmations",
    "evidence",
    "evidence_links",
    "relations",
    "domain_entities",
    "audit_links",
    "posting_reconciliations",
)


def validator_for(definition: str) -> Draft202012Validator:
    return Draft202012Validator(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$defs": SCHEMA["$defs"],
            "$ref": f"#/$defs/{definition}",
        }
    )


def empty_deltas() -> dict:
    return {
        "entity_changes": {
            name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
            for name in ENTITY_COLLECTIONS
        },
        "value_changes": {"balances": [], "reports": [], "derived_statuses": []},
    }


def operation(
    action_type: str,
    operation_class: str,
    operation_input: dict,
    status: str = "accepted",
) -> dict:
    value = {
        "id": f"operation-{action_type}",
        "root_id": "root-rg06",
        "sequence": 1,
        "operation_class": operation_class,
        "action_type": action_type,
        "baseline_state_id": "state-before",
        "result_state_id": "state-after",
        "outcome": {"status": status},
        "status_changes": [],
        "deltas": empty_deltas(),
        "returned_ids": [],
    }
    if status == "no_change":
        value["outcome"]["reason_code"] = "idempotent_replay"
    value["input" if status != "rejected" else "attempted_input"] = operation_input
    return value


def staged_source(*, imported: bool, mirror: bool = False) -> dict:
    time_field = "source_payment_at" if imported else "observed_at"
    payload = {
        "amount": "-220.00",
        "currency": "CNY",
        time_field: "2026-05-03T16:30:00+08:00",
    }
    if mirror:
        payload["mirror_of_source_id"] = "source-staged-original"
    return {
        "id": "source-staged-payment",
        "type": "staged_payment_bank_fact",
        "payload": payload,
    }


def staged_evidence(*, imported: bool, mirror: bool = False) -> dict:
    time_field = "source_payment_at" if imported else "observed_at"
    payload = {
        "payment_id": "installment-final",
        time_field: "2026-05-03T16:30:00+08:00",
    }
    if mirror:
        payload.update(
            {
                "mirror_of_evidence_id": "evidence-staged-original",
                "merged_into_evidence_link_id": "link-staged-original",
            }
        )
    return {
        "id": "evidence-staged-payment",
        "type": "staged_payment_bank_payment",
        "source_ids": ["source-staged-payment"],
        "payload": payload,
    }


def candidate_status(sequence: int, status: str) -> dict:
    return {"id": f"candidate-status-{sequence}", "sequence": sequence, "status": status}


def staged_candidate(*, ambiguous: bool = False, confirmed: bool = False) -> dict:
    payload = {
        "payment_role": None if ambiguous else "deposit",
        "amount": "80.00",
        "currency": "CNY",
        "source_payment_at": "2026-04-28T10:00:00+08:00",
        "evidence_ref": "evidence-staged-payment",
        "provenance": {"rule": "staged_payment_bank_fact", "rule_version": 1},
        "requires_confirmation": [
            "relation_id",
            "payment_role",
            "category_id",
            "funding_account_id",
        ],
    }
    if ambiguous:
        payload["guessed_payment_role"] = None
    history = [candidate_status(1, "pending_confirmation")]
    if confirmed:
        history.append(candidate_status(2, "confirmed"))
    return {
        "id": "candidate-staged-payment",
        "type": "staged_payment",
        "source_ids": ["source-staged-payment"],
        "confidence": "0.50" if ambiguous else "1.00",
        "payload": payload,
        "status_history": history,
    }


def lifecycle_history(event: str) -> dict:
    return {
        "id": f"history-{event}",
        "sequence": 1,
        "event": event,
        "occurred_at": "2026-04-20T09:00:00+08:00",
        "total_amount": "300.00",
        "paid_amount": "80.00" if event == "payment_confirmed" else "0.00",
        "due_amount": "220.00" if event == "payment_confirmed" else "300.00",
        "payment_id": "installment-deposit" if event == "payment_confirmed" else None,
        "payment_progress": "partially_paid" if event == "payment_confirmed" else "unpaid",
        "fulfillment_status": "fulfilled" if event == "fulfillment_changed" else "in_progress",
        "state_transition_effect_count": 0,
    }


class Rg06ClosedSchemaTests(unittest.TestCase):
    def assert_valid(self, definition: str, value: dict) -> None:
        errors = list(validator_for(definition).iter_errors(value))
        self.assertEqual([], errors, [error.message for error in errors])

    def assert_invalid(self, definition: str, value: dict) -> None:
        self.assertTrue(list(validator_for(definition).iter_errors(value)))

    def test_rg06_direct_definitions_are_registered(self):
        required = {
            "stagedPaymentBankFactSource",
            "stagedPaymentBankPaymentEvidence",
            "stagedPaymentCandidate",
            "stagedPaymentRelation",
            "stagedPaymentLifecycle",
            "stagedPaymentLifecycleHistoryEvent",
            "installmentPayment",
            "stagedPaymentRejectedOutcome",
        }
        self.assertEqual(set(), required - set(SCHEMA["$defs"]))

    def test_staged_payment_source_time_variants_are_closed_and_exclusive(self):
        for imported in (False, True):
            with self.subTest(imported=imported):
                source = staged_source(imported=imported)
                self.assert_valid("stagedPaymentBankFactSource", source)
                self.assert_valid("source", source)

                wrong_time = deepcopy(source)
                wrong_time["payload"][
                    "observed_at" if imported else "source_payment_at"
                ] = "2026-05-03T16:30:00+08:00"
                self.assert_invalid("stagedPaymentBankFactSource", wrong_time)

                for forbidden in ("created_at", "confirmed_at"):
                    extra = deepcopy(source)
                    extra["payload"][forbidden] = "2026-05-03T16:30:00+08:00"
                    self.assert_invalid("stagedPaymentBankFactSource", extra)

                missing = deepcopy(source)
                missing["payload"].pop(
                    "source_payment_at" if imported else "observed_at"
                )
                self.assert_invalid("stagedPaymentBankFactSource", missing)

                null_time = deepcopy(source)
                null_time["payload"][
                    "source_payment_at" if imported else "observed_at"
                ] = None
                self.assert_invalid("stagedPaymentBankFactSource", null_time)

        imported_mirror = staged_source(imported=True, mirror=True)
        self.assert_valid("stagedPaymentBankFactSource", imported_mirror)
        manual_mirror = staged_source(imported=False, mirror=True)
        self.assert_invalid("stagedPaymentBankFactSource", manual_mirror)

    def test_staged_payment_evidence_time_and_mirror_lineage_are_closed(self):
        for imported in (False, True):
            with self.subTest(imported=imported):
                evidence = staged_evidence(imported=imported)
                self.assert_valid("stagedPaymentBankPaymentEvidence", evidence)
                self.assert_valid("evidence", evidence)

                doubled = deepcopy(evidence)
                doubled["payload"][
                    "observed_at" if imported else "source_payment_at"
                ] = "2026-05-03T16:30:00+08:00"
                self.assert_invalid("stagedPaymentBankPaymentEvidence", doubled)

                for forbidden in ("created_at", "confirmed_at"):
                    extra = deepcopy(evidence)
                    extra["payload"][forbidden] = "2026-05-03T16:30:00+08:00"
                    self.assert_invalid("stagedPaymentBankPaymentEvidence", extra)

                null_time = deepcopy(evidence)
                null_time["payload"][
                    "source_payment_at" if imported else "observed_at"
                ] = None
                self.assert_invalid("stagedPaymentBankPaymentEvidence", null_time)

        mirror = staged_evidence(imported=True, mirror=True)
        self.assert_valid("stagedPaymentBankPaymentEvidence", mirror)
        for lineage_field in (
            "mirror_of_evidence_id",
            "merged_into_evidence_link_id",
        ):
            incomplete = deepcopy(mirror)
            incomplete["payload"].pop(lineage_field)
            self.assert_invalid("stagedPaymentBankPaymentEvidence", incomplete)

    def test_staged_payment_candidates_freeze_known_and_explicit_null_variants(self):
        for candidate in (
            staged_candidate(),
            staged_candidate(confirmed=True),
            staged_candidate(ambiguous=True),
        ):
            with self.subTest(candidate=candidate):
                self.assert_valid("stagedPaymentCandidate", candidate)
                self.assert_valid("candidate", candidate)

        known_with_guess = staged_candidate()
        known_with_guess["payload"]["guessed_payment_role"] = None
        self.assert_invalid("stagedPaymentCandidate", known_with_guess)

        ambiguous_without_null = staged_candidate(ambiguous=True)
        ambiguous_without_null["payload"].pop("guessed_payment_role")
        self.assert_invalid("stagedPaymentCandidate", ambiguous_without_null)

        guessed_role = staged_candidate(ambiguous=True)
        guessed_role["payload"]["guessed_payment_role"] = "final"
        self.assert_invalid("stagedPaymentCandidate", guessed_role)

        wrong_confidence = staged_candidate()
        wrong_confidence["confidence"] = "0.50"
        self.assert_invalid("stagedPaymentCandidate", wrong_confidence)

        duplicate_source = staged_candidate()
        duplicate_source["source_ids"].append("source-staged-payment")
        self.assert_invalid("stagedPaymentCandidate", duplicate_source)

        wrong_requirements = staged_candidate()
        wrong_requirements["payload"]["requires_confirmation"][-1] = "account_id"
        self.assert_invalid("stagedPaymentCandidate", wrong_requirements)

        extra = staged_candidate()
        extra["payload"]["transaction_id"] = "transaction-not-owned-here"
        self.assert_invalid("stagedPaymentCandidate", extra)

        for missing_field in (
            "payment_role",
            "amount",
            "currency",
            "source_payment_at",
            "evidence_ref",
            "provenance",
            "requires_confirmation",
        ):
            with self.subTest(missing_field=missing_field):
                missing = staged_candidate()
                missing["payload"].pop(missing_field)
                self.assert_invalid("stagedPaymentCandidate", missing)

        missing_common = staged_candidate()
        missing_common.pop("confidence")
        self.assert_invalid("stagedPaymentCandidate", missing_common)

    def test_staged_payment_candidate_status_histories_are_bounded(self):
        confirmed_only = staged_candidate()
        confirmed_only["status_history"] = [candidate_status(1, "confirmed")]
        self.assert_invalid("stagedPaymentCandidate", confirmed_only)

        duplicate_confirmed = staged_candidate(confirmed=True)
        duplicate_confirmed["status_history"].append(candidate_status(3, "confirmed"))
        self.assert_invalid("stagedPaymentCandidate", duplicate_confirmed)

        rejected = staged_candidate()
        rejected["status_history"][0]["status"] = "rejected"
        self.assert_invalid("stagedPaymentCandidate", rejected)

    def test_lifecycle_history_event_registry_and_payment_id_variants_are_closed(self):
        events = (
            "group_created",
            "payment_confirmed",
            "fulfillment_changed",
            "completion_confirmed",
        )
        for event in events:
            with self.subTest(event=event):
                self.assert_valid(
                    "stagedPaymentLifecycleHistoryEvent", lifecycle_history(event)
                )

        wrong_payment = lifecycle_history("group_created")
        wrong_payment["payment_id"] = "installment-deposit"
        self.assert_invalid("stagedPaymentLifecycleHistoryEvent", wrong_payment)

        missing_payment = lifecycle_history("payment_confirmed")
        missing_payment["payment_id"] = None
        self.assert_invalid("stagedPaymentLifecycleHistoryEvent", missing_payment)

        unknown_event = lifecycle_history("group_created")
        unknown_event["event"] = "payment_completed"
        self.assert_invalid("stagedPaymentLifecycleHistoryEvent", unknown_event)

        nonzero_effect = lifecycle_history("group_created")
        nonzero_effect["state_transition_effect_count"] = 1
        self.assert_invalid("stagedPaymentLifecycleHistoryEvent", nonzero_effect)

        extra = lifecycle_history("group_created")
        extra["created_at"] = "2026-04-20T09:00:00+08:00"
        self.assert_invalid("stagedPaymentLifecycleHistoryEvent", extra)

    def test_lifecycle_installment_relation_and_status_shapes_are_closed(self):
        lifecycle = {
            "id": "lifecycle-staged-payment",
            "type": "staged_payment_lifecycle",
            "payload": {
                "total_amount": "300.00",
                "paid_amount": "0.00",
                "due_amount": "300.00",
                "currency": "CNY",
                "category_id": "category-service",
                "display_name": "Staged payment",
                "system_managed": True,
                "generic_order_lifecycle": False,
                "state_history": [lifecycle_history("group_created")],
            },
        }
        installment = {
            "id": "installment-deposit",
            "type": "installment_payment",
            "payload": {
                "role": "deposit",
                "amount": "80.00",
                "currency": "CNY",
                "funding_account_id": "asset-bank",
                "transaction_id": "transaction-deposit",
                "expense_posting_id": "posting-expense",
                "asset_posting_id": "posting-asset",
                "actual_payment_at": "2026-04-28T10:00:00+08:00",
                "statistics_at": "2026-04-28T10:00:00+08:00",
            },
        }
        relation = {
            "id": "relation-staged-payment",
            "type": "staged_payment",
            "member_refs": [
                {"kind": "domain_entity", "id": lifecycle["id"]},
                {"kind": "domain_entity", "id": installment["id"]},
            ],
            "payload": {},
        }
        self.assert_valid("stagedPaymentLifecycle", lifecycle)
        self.assert_valid("installmentPayment", installment)
        self.assert_valid("stagedPaymentRelation", relation)
        self.assert_valid("domainEntity", lifecycle)
        self.assert_valid("domainEntity", installment)
        self.assert_valid("relation", relation)

        duplicate_member = deepcopy(relation)
        duplicate_member["member_refs"].append(deepcopy(relation["member_refs"][1]))
        self.assert_invalid("stagedPaymentRelation", duplicate_member)

        business_payload = deepcopy(relation)
        business_payload["payload"]["total_amount"] = "300.00"
        self.assert_invalid("stagedPaymentRelation", business_payload)

        for status_name, values in {
            "payment_progress": ("unpaid", "partially_paid", "paid_in_full"),
            "fulfillment_status": ("in_progress", "fulfilled"),
            "reconciliation": ("pending", "partial", "complete"),
        }.items():
            for value in values:
                with self.subTest(status_name=status_name, value=value):
                    self.assert_valid(
                        "derivedStatus",
                        {
                            "id": f"status-{status_name}-{value}",
                            "target_kind": "domain_entity",
                            "target_id": lifecycle["id"],
                            "status_name": status_name,
                            "value": value,
                        },
                    )

    def test_staged_payment_evidence_link_uses_only_canonical_posting_fields(self):
        link = {
            "id": "link-staged-payment",
            "evidence_id": "evidence-staged-payment",
            "target_kind": "posting",
            "target_id": "posting-asset",
            "role": "payment_asset_posting",
        }
        self.assert_valid("evidenceLink", link)

        wrong_target = deepcopy(link)
        wrong_target["target_kind"] = "domain_entity"
        self.assert_invalid("evidenceLink", wrong_target)

        for legacy_field in (
            "payment_id",
            "source_id",
            "status",
            "mirror_of_evidence_id",
            "merged_into_evidence_link_id",
        ):
            with self.subTest(legacy_field=legacy_field):
                legacy = deepcopy(link)
                legacy[legacy_field] = "legacy-value"
                self.assert_invalid("evidenceLink", legacy)

    def test_eight_rg06_actions_have_strict_accepted_and_no_change_branches(self):
        actions = {
            "create_staged_payment": (
                "creation",
                {
                    "request_id": "request-create",
                    "kind": "staged_payment",
                    "total_amount": "300.00",
                    "currency": "CNY",
                    "category_id": "category-service",
                    "created_at": "2026-04-20T09:00:00+08:00",
                },
            ),
            "record_staged_payment_installment": (
                "creation",
                {
                    "request_id": "request-deposit",
                    "relation_id": "relation-staged-payment",
                    "payment_role": "deposit",
                    "payment_amount": "80.00",
                    "currency": "CNY",
                    "funding_account_id": "asset-bank",
                    "actual_payment_at": "2026-04-28T10:00:00+08:00",
                },
            ),
            "change_staged_payment_fulfillment": (
                "status_transition",
                {
                    "request_id": "request-fulfillment",
                    "relation_id": "relation-staged-payment",
                    "fulfillment_status": "fulfilled",
                    "occurred_at": "2026-05-01T12:00:00+08:00",
                },
            ),
            "confirm_staged_payment_completion": (
                "status_transition",
                {
                    "request_id": "request-completion",
                    "relation_id": "relation-staged-payment",
                    "confirmed": True,
                    "occurred_at": "2026-05-04T09:00:00+08:00",
                },
            ),
            "link_staged_payment_evidence": (
                "reconciliation",
                {
                    "source_id": "source-manual-bank",
                    "evidence_id": "evidence-manual-bank",
                    "payment_id": "installment-deposit",
                    "posting_id": "posting-asset",
                },
            ),
            "ingest_staged_payment_bank_fact": (
                "creation",
                {
                    "source_id": "source-import",
                    "evidence_id": "evidence-import",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                    "amount": "80.00",
                    "currency": "CNY",
                    "suggested_payment_role": "deposit",
                },
            ),
            "confirm_staged_payment_candidate": (
                "creation",
                {
                    "request_id": "request-confirm",
                    "candidate_id": "candidate-import",
                    "relation_id": "relation-staged-payment",
                    "payment_role": "deposit",
                    "category_id": "category-service",
                    "funding_account_id": "asset-bank",
                    "exact_binding_confirmed": True,
                },
            ),
            "merge_staged_payment_mirror_evidence": (
                "reconciliation",
                {
                    "source_id": "source-mirror",
                    "evidence_id": "evidence-mirror",
                    "payment_id": "installment-final",
                    "posting_id": "posting-asset-final",
                    "amount": "-220.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-05-03T16:30:00+08:00",
                },
            ),
        }
        self.assertEqual(8, len(actions))
        for action_type, (operation_class, action_input) in actions.items():
            for status in ("accepted", "no_change"):
                with self.subTest(action_type=action_type, status=status):
                    self.assert_valid(
                        "operation",
                        operation(action_type, operation_class, action_input, status),
                    )

            wrong_class = operation(action_type, "update", action_input)
            self.assert_invalid("operation", wrong_class)

            extra_input = operation(action_type, operation_class, action_input)
            extra_input["input"]["association_group_id"] = "legacy-group-id"
            self.assert_invalid("operation", extra_input)

            missing_input = operation(action_type, operation_class, action_input)
            missing_input["input"].pop(next(iter(action_input)))
            self.assert_invalid("operation", missing_input)

    def test_rg06_rejection_reason_and_path_registry_is_closed(self):
        reason_paths = (
            ("must_be_positive", "total_amount"),
            ("must_be_positive", "payment_amount"),
            ("deposit_must_be_less_than_total", "payment_amount"),
            ("final_must_equal_remaining_due", "payment_amount"),
            ("payment_exceeds_due", "payment_amount"),
            ("single_currency_required", "currency"),
            ("secondary_category_required", "category_id"),
            ("category_inactive", "category_id"),
            ("expense_category_required", "category_id"),
            ("unknown_real_account", "funding_account_id"),
            ("real_financial_account_required", "funding_account_id"),
            ("owned_account_required", "funding_account_id"),
            ("asset_account_required", "funding_account_id"),
            ("due_must_be_zero", "payment_progress"),
        )
        for reason, field in reason_paths:
            with self.subTest(reason=reason, field=field):
                self.assert_valid(
                    "stagedPaymentRejectedOutcome",
                    {
                        "status": "rejected",
                        "reason_code": reason,
                        "field_path": f"$.attempted_input.{field}",
                    },
                )

        invalid_pair = {
            "status": "rejected",
            "reason_code": "due_must_be_zero",
            "field_path": "$.attempted_input.total_amount",
        }
        self.assert_invalid("stagedPaymentRejectedOutcome", invalid_pair)

    def test_rg06_rejected_operation_branches_are_closed(self):
        rejected_operations = (
            (
                "create_staged_payment",
                {"total_amount": "0.00"},
                "must_be_positive",
                "total_amount",
            ),
            (
                "record_staged_payment_installment",
                {"payment_role": "final", "payment_amount": "219.99"},
                "final_must_equal_remaining_due",
                "payment_amount",
            ),
            (
                "confirm_staged_payment_completion",
                {"payment_progress": "paid_in_full"},
                "due_must_be_zero",
                "payment_progress",
            ),
        )
        for action_type, attempted_input, reason, field in rejected_operations:
            with self.subTest(action_type=action_type):
                rejected = operation(
                    action_type, "rejection", attempted_input, status="rejected"
                )
                rejected["outcome"].update(
                    {
                        "reason_code": reason,
                        "field_path": f"$.attempted_input.{field}",
                    }
                )
                self.assert_valid("operation", rejected)

                with_strict_input = deepcopy(rejected)
                with_strict_input["input"] = {}
                self.assert_invalid("operation", with_strict_input)

                unknown_attempted_field = deepcopy(rejected)
                unknown_attempted_field["attempted_input"]["unknown"] = True
                self.assert_invalid("operation", unknown_attempted_field)

                wrong_outcome_pair = deepcopy(rejected)
                wrong_outcome_pair["outcome"]["field_path"] = (
                    "$.attempted_input.unknown"
                )
                self.assert_invalid("operation", wrong_outcome_pair)

    def test_rg06_rejection_reason_matches_the_attempted_shape(self):
        mismatches = (
            (
                "create_staged_payment",
                {"total_amount": "0.00"},
                "secondary_category_required",
                "category_id",
            ),
            (
                "record_staged_payment_installment",
                {"funding_account_id": "asset-unknown"},
                "must_be_positive",
                "payment_amount",
            ),
            (
                "record_staged_payment_installment",
                {"payment_role": "deposit", "payment_amount": "300.00"},
                "final_must_equal_remaining_due",
                "payment_amount",
            ),
            (
                "record_staged_payment_installment",
                {"payment_role": "final", "payment_amount": "220.01"},
                "deposit_must_be_less_than_total",
                "payment_amount",
            ),
        )
        for action_type, attempted_input, reason, field in mismatches:
            with self.subTest(action_type=action_type, reason=reason):
                rejected = operation(
                    action_type, "rejection", attempted_input, status="rejected"
                )
                rejected["outcome"].update(
                    {
                        "reason_code": reason,
                        "field_path": f"$.attempted_input.{field}",
                    }
                )
                self.assert_invalid("operation", rejected)

    def test_rg05_discriminators_remain_isolated_and_compatible(self):
        rg05_source = {
            "id": "source-rg05-bank",
            "type": "merged_payment_bank_fact",
            "payload": {
                "evidence_id": "evidence-rg05-bank",
                "observed_at": "2026-04-01T09:00:00+08:00",
                "details": "Synthetic bank fact",
                "amount": "-100.00",
                "currency": "CNY",
                "completeness": "complete",
            },
        }
        rg05_evidence = {
            "id": "evidence-rg05-bank",
            "type": "bank_payment",
            "source_ids": [rg05_source["id"]],
            "payload": {"observed_at": "2026-04-01T09:00:00+08:00"},
        }
        self.assert_valid("source", rg05_source)
        self.assert_valid("evidence", rg05_evidence)

        staged_type_with_rg05_source_payload = deepcopy(rg05_source)
        staged_type_with_rg05_source_payload["type"] = "staged_payment_bank_fact"
        self.assert_invalid("source", staged_type_with_rg05_source_payload)

        staged_type_with_rg05_evidence_payload = deepcopy(rg05_evidence)
        staged_type_with_rg05_evidence_payload["type"] = "staged_payment_bank_payment"
        self.assert_invalid("evidence", staged_type_with_rg05_evidence_payload)


if __name__ == "__main__":
    unittest.main()
