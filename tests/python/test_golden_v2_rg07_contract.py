from __future__ import annotations

import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

from golden_cases import v2 as golden_v2
from golden_cases.loader import GoldenCaseError


ROOT = Path(__file__).resolve().parents[2]


class GoldenV2Rg07ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(
            (ROOT / "schemas" / "golden-case-v2.schema.json").read_text(
                encoding="utf-8"
            )
        )
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(cls.schema)

    def validate_def(self, name: str, value: object) -> None:
        self.validator.evolve(schema=self.schema["$defs"][name]).validate(value)

    def test_d078_and_normative_amendment_are_present(self) -> None:
        decisions = (ROOT / "docs" / "DECISIONS.md").read_text(encoding="utf-8")
        contract = (ROOT / "docs" / "GOLDEN_SCHEMA.md").read_text(encoding="utf-8")
        self.assertIn("## D-078 RG-07 Golden Schema v2 契约修订", decisions)
        self.assertIn("confirmation `refund_relationship_confirmation`", contract)
        self.assertIn("`refund_cash_inflow` 映射到 `cash_inflow`", decisions)

    def test_rg07_actions_have_exact_effect_count_registration(self) -> None:
        expected = {
            "record_refund_request_status": {"relations": (1, 0, 0), "domain_entities": (1, 0, 0)},
            "ingest_refund_status_source": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0)},
            "confirm_manual_refund_receipt": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (2, 0, 0), "confirmations": (1, 0, 0), "relations": (0, 1, 0), "domain_entities": (0, 1, 0), "posting_reconciliations": (1, 0, 0)},
            "attach_original_payment_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0), "posting_reconciliations": (0, 1, 0)},
            "attach_refund_destination_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0), "posting_reconciliations": (0, 1, 0)},
            "attach_refund_dual_role_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (2, 0, 0)},
            "confirm_refund_receipt": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (2, 0, 0), "confirmations": (1, 0, 0), "relations": (1, 0, 0), "domain_entities": (1, 0, 0), "posting_reconciliations": (1, 0, 0)},
            "ingest_refund_credit_source": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "candidates": (1, 0, 0)},
            "confirm_imported_refund": {"transactions": (1, 0, 0), "transaction_versions": (1, 0, 0), "posting_sets": (1, 0, 0), "postings": (2, 0, 0), "candidates": (0, 1, 0), "confirmations": (1, 0, 0), "evidence_links": (1, 0, 0), "relations": (1, 0, 0), "domain_entities": (1, 0, 0), "posting_reconciliations": (1, 0, 0)},
            "merge_refund_mirror_evidence": {"sources": (1, 0, 0), "evidence": (1, 0, 0), "evidence_links": (1, 0, 0)},
        }
        self.assertEqual(
            {key: golden_v2._ACCEPTED_ACTION_ENTITY_COUNTS[key] for key in expected},
            expected,
        )

    def test_refund_relation_members_are_order_independent(self) -> None:
        state = {
            "relations": [
                {
                    "id": "relation-1",
                    "type": "refund",
                    "member_refs": [
                        {"kind": "transaction", "id": "transaction-refund"},
                        {"kind": "transaction", "id": "transaction-original"},
                    ],
                    "payload": {},
                }
            ]
        }
        indexes = {
            "catalog_accounts": {},
            "transactions": {
                "transaction-original": {"type": "expense"},
                "transaction-refund": {"type": "refund_receipt"},
            },
            "postings": {},
        }
        golden_v2._validate_relations(state, "$", indexes, {}, {})

    def test_generic_evidence_link_rejects_rg07_only_fields(self) -> None:
        link = {
            "id": "link-1",
            "evidence_id": "evidence-1",
            "target_kind": "posting",
            "target_id": "posting-1",
            "role": "destination_asset_posting",
        }
        self.validate_def("evidenceLink", link)
        for field in (
            "source_id",
            "status",
            "mirror_of_evidence_id",
            "merged_into_evidence_link_id",
        ):
            with self.subTest(field=field), self.assertRaises(ValidationError):
                self.validate_def("evidenceLink", {**link, field: "forbidden"})

    def test_refund_confirmation_uses_relation_subject_and_exact_payload(self) -> None:
        confirmation = {
            "id": "confirmation-1",
            "type": "refund_relationship_confirmation",
            "operation_id": "operation-1",
            "subject": {"kind": "relation", "id": "relation-1"},
            "confirmed_at": "2026-02-02T18:00:00+08:00",
            "payload": {"original_transaction_id": "transaction-original"},
        }
        self.validate_def("rg07RefundConfirmation", confirmation)
        with self.assertRaises(ValidationError):
            self.validate_def(
                "rg07RefundConfirmation",
                {**confirmation, "type": "refund_receipt_confirmation"},
            )
        with self.assertRaises(ValidationError):
            self.validate_def(
                "rg07RefundConfirmation",
                {**confirmation, "subject": {"kind": "operation", "id": "operation-1"}},
            )

    def test_rg07_history_and_action_inputs_are_closed(self) -> None:
        history = {
            "id": "history-1",
            "sequence": 1,
            "state": "received",
            "occurred_at": "2026-02-02T18:00:00+08:00",
            "transaction_id": "transaction-refund",
            "formal_effect_count": 1,
        }
        self.validate_def("refundRelationshipHistoryEvent", history)
        with self.assertRaises(ValidationError):
            self.validate_def(
                "refundRelationshipHistoryEvent",
                {key: value for key, value in history.items() if key != "sequence"},
            )
        request = {
            "source_id": "source-1",
            "refund_relation_id": "relation-refund-1",
            "observed_at": "2026-02-02T18:00:00+08:00",
            "reported_state": "processing",
            "proves_arrival": False,
        }
        self.validate_def("rg07StatusSourceInput", request)
        with self.assertRaises(ValidationError):
            self.validate_def("rg07StatusSourceInput", {**request, "request_id": "extra"})
        with self.assertRaises(ValidationError):
            self.validate_def("rg07StatusSourceInput", {**request, "reported_state": "received"})
        with self.assertRaises(ValidationError):
            self.validate_def("rg07StatusSourceInput", {**request, "proves_arrival": True})
        original_evidence = {
            "source_id": "source-original",
            "evidence_id": "evidence-original",
            "payment_asset_posting_id": "posting-original-asset",
            "amount": "-120.00",
            "currency": "CNY",
            "observed_at": "2026-01-10T12:02:00+08:00",
            "booking_at": "2026-01-10T12:00:00+08:00",
            "value_at": "2026-01-10T12:00:00+08:00",
            "immutable_payload_hash": "sha256:synthetic-original",
        }
        self.validate_def("rg07OriginalPaymentEvidenceInput", original_evidence)
        with self.assertRaises(ValidationError):
            self.validate_def(
                "rg07OriginalPaymentEvidenceInput",
                {**original_evidence, "request_id": "forbidden"},
            )

    def test_rg07_dual_role_input_binds_exact_targets_and_unique_roles(self) -> None:
        value = {
            "source_id": "source-dual",
            "evidence_id": "evidence-dual",
            "refund_relation_id": "relation-refund",
            "destination_asset_posting_id": "posting-refund-asset",
            "observed_at": "2026-02-02T18:00:00+08:00",
            "roles": ["refund_relationship", "destination_asset_posting"],
        }
        self.validate_def("rg07DualRoleEvidenceInput", value)
        self.validate_def("rg07DualRoleEvidenceInput", {**value, "roles": list(reversed(value["roles"]))})
        with self.assertRaises(ValidationError):
            self.validate_def("rg07DualRoleEvidenceInput", {**value, "roles": ["refund_relationship", "refund_relationship"]})
        for field in ("refund_relation_id", "destination_asset_posting_id"):
            with self.assertRaises(ValidationError):
                self.validate_def("rg07DualRoleEvidenceInput", {key: item for key, item in value.items() if key != field})

    def test_refund_reports_use_only_canonical_metrics(self) -> None:
        accounts = {
            "asset": {"id": "asset", "kind": "asset", "real_account": True},
            "expense": {"id": "expense", "kind": "expense", "real_account": False},
        }
        current = {
            "refund": (
                {"id": "refund", "type": "refund_receipt"},
                {"statistics_at": "2026-02-02T15:20:00+08:00"},
                [
                    {"account_id": "asset", "amount": "30.00", "currency": "CNY", "role": "destination_asset"},
                    {"account_id": "expense", "amount": "-30.00", "currency": "CNY", "role": "expense"},
                ],
            )
        }
        values = golden_v2._report_values(
            current,
            accounts,
            {"period_type": "month", "period": "2026-02"},
            "CNY",
        )
        self.assertEqual(values["cash_inflow"], 30)
        self.assertEqual(values["consumption"], -30)
        self.assertEqual(values["income"], 0)
        self.assertNotIn("refund_cash_inflow", values)

    def test_rg07_action_effect_validator_rejects_wrong_owned_subtype(self) -> None:
        result = {"catalog": {"accounts": [], "categories": []}}
        for collection, parts in golden_v2._ENTITY_COLLECTIONS.items():
            if parts[0] == "catalog":
                continue
            result[parts[0]] = []
        result["relations"] = [
            {"id": "relation-1", "type": "refund", "member_refs": [], "payload": {}}
        ]
        result["domain_entities"] = [
            {"id": "entity-1", "type": "refund_relationship", "payload": {}}
        ]
        changes = {
            name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
            for name in golden_v2._ENTITY_COLLECTIONS
        }
        changes["relations"]["added_ids"] = ["relation-1"]
        changes["domain_entities"]["added_ids"] = ["entity-1"]
        operation = {
            "id": "operation-1",
            "action_type": "record_refund_request_status",
        }
        golden_v2._validate_rg07_action_effects(operation, "$.operations[0]", result, changes)
        result["domain_entities"][0]["type"] = "counterparty"
        with self.assertRaises(GoldenCaseError):
            golden_v2._validate_rg07_action_effects(
                operation, "$.operations[0]", result, changes
            )

    def test_rg07_expected_output_is_approved_after_explicit_approval(self) -> None:
        path_map = json.loads(
            (ROOT / "docs" / "migrations" / "golden-v2" / "rg-07-path-map.json").read_text(encoding="utf-8")
        )
        mapping = (ROOT / "docs" / "migrations" / "golden-v2" / "rg-07-mapping.md").read_text(encoding="utf-8")
        self.assertEqual(path_map["status"], "approved")
        self.assertEqual(path_map["expected_output_gate"], "approved")
        self.assertIn('"approval_status": "approved"', (ROOT / "docs" / "migrations" / "golden-v2" / "rg-07-expected.json").read_text(encoding="utf-8"))
        self.assertIn("expected output gate: approved", mapping)



if __name__ == "__main__":
    unittest.main()
