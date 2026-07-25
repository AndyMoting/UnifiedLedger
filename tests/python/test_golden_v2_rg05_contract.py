from copy import deepcopy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

from golden_cases import load_golden_case_v2


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas" / "golden-case-v2.schema.json").read_text(encoding="utf-8"))
RG01 = ROOT / "docs" / "examples" / "golden-schema-v2" / "rg-01.json"


def validator_for(definition: str) -> Draft202012Validator:
    return Draft202012Validator({
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$defs": SCHEMA["$defs"],
        "$ref": f"#/$defs/{definition}",
    })


def valid_source_objects() -> tuple[dict, dict]:
    bank = {
        "id": "source-bank-rg05",
        "type": "merged_payment_bank_fact",
        "payload": {
            "evidence_id": "evidence-bank-rg05",
            "observed_at": "2026-04-10T18:30:00+08:00",
            "details": "Synthetic bank debit",
            "amount": "-100.00",
            "currency": "CNY",
            "completeness": "complete",
        },
    }
    item = {
        "id": "source-item-a-rg05",
        "type": "merged_payment_item_fact",
        "payload": {
            "item_id": "item-a-rg05",
            "evidence_id": "evidence-item-a-rg05",
            "evidence_kind": "item_receipt",
            "observed_at": "2026-04-08T10:00:00+08:00",
            "details": "Synthetic item A",
            "amount": "40.00",
            "currency": "CNY",
            "suggested_category_id": "expense-category-daily",
            "completeness": "complete",
        },
    }
    return bank, item


class Rg05ClosedSchemaTests(unittest.TestCase):
    def assert_valid(self, definition: str, value: dict) -> None:
        errors = list(validator_for(definition).iter_errors(value))
        self.assertEqual([], errors, [error.message for error in errors])

    def assert_invalid(self, definition: str, value: dict) -> None:
        self.assertTrue(list(validator_for(definition).iter_errors(value)))

    def test_bank_and_item_sources_are_distinct_closed_subtypes(self):
        bank, item = valid_source_objects()
        self.assert_valid("source", bank)
        self.assert_valid("source", item)

        extra = deepcopy(item)
        extra["payload"]["transaction_id"] = "tx-forbidden"
        self.assert_invalid("source", extra)

        wrong_kind = deepcopy(item)
        wrong_kind["payload"]["evidence_kind"] = "bank_statement"
        self.assert_invalid("source", wrong_kind)

    def test_candidate_has_exact_three_sources_and_closed_provenance(self):
        candidate = {
            "id": "candidate-rg05",
            "type": "merged_payment",
            "source_ids": ["source-bank-rg05", "source-item-a-rg05", "source-item-b-rg05"],
            "confidence": "1.00",
            "payload": {
                "payment_total": "100.00",
                "currency": "CNY",
                "bank_source_id": "source-bank-rg05",
                "item_source_ids": ["source-item-a-rg05", "source-item-b-rg05"],
                "item_proposals": [
                    {"item_id": "item-a", "amount": "40.00", "currency": "CNY", "suggested_category_id": "category-a", "source_id": "source-item-a-rg05", "evidence_id": "evidence-a"},
                    {"item_id": "item-b", "amount": "60.00", "currency": "CNY", "suggested_category_id": "category-b", "source_id": "source-item-b-rg05", "evidence_id": "evidence-b"},
                ],
                "evidence_refs": ["evidence-bank", "evidence-a", "evidence-b"],
                "provenance": {"rule": "merged_payment_facts", "rule_version": 1},
                "requires_confirmation": ["funding_account_id", "secondary_categories", "allocation_closure", "formal_transaction_creation"],
            },
            "status_history": [{"id": "candidate-status-rg05-1", "sequence": 1, "status": "pending_confirmation"}],
        }
        self.assert_valid("candidate", candidate)

        missing_item_source = deepcopy(candidate)
        missing_item_source["source_ids"].pop()
        self.assert_invalid("candidate", missing_item_source)

        persistent_conflict = deepcopy(candidate)
        persistent_conflict["status_history"][0]["status"] = "conflict"
        self.assert_invalid("candidate", persistent_conflict)

    def test_merged_relation_has_one_transaction_one_posting_and_two_allocations(self):
        relation = {
            "id": "relation-rg05",
            "type": "merged_payment",
            "member_refs": [
                {"kind": "transaction", "id": "tx-rg05"},
                {"kind": "posting", "id": "posting-payment-rg05"},
                {"kind": "domain_entity", "id": "allocation-a-rg05"},
                {"kind": "domain_entity", "id": "allocation-b-rg05"},
            ],
            "payload": {
                "system_managed": True,
                "display_name": "合并付款",
                "generic_order_lifecycle": False,
                "payment_total": "100.00",
                "currency": "CNY",
            },
        }
        self.assert_valid("relation", relation)

        mutable_completeness = deepcopy(relation)
        mutable_completeness["payload"]["item_evidence_completeness"] = "partial"
        self.assert_invalid("relation", mutable_completeness)

        three_members = deepcopy(relation)
        three_members["member_refs"].pop()
        self.assert_invalid("relation", three_members)

        mixed_relation = deepcopy(load_golden_case_v2(RG01)["states"][-1])
        mixed_relation["relations"] = []
        self.assert_valid("relation", {
            "id": "relation-rg04",
            "type": "mixed_payment",
            "member_refs": [
                {"kind": "transaction", "id": "tx-rg04"},
                {"kind": "posting", "id": "asset-rg04"},
                {"kind": "posting", "id": "credit-rg04"},
            ],
            "payload": {
                "system_managed": True,
                "display_name": "Mixed payment",
                "generic_order_lifecycle": False,
                "payment_composition_total": "100.00",
                "funding_components": [
                    {"account_id": "asset", "funding_amount": "70.00", "currency": "CNY", "posting_id": "asset-rg04"},
                    {"account_id": "credit", "funding_amount": "30.00", "currency": "CNY", "posting_id": "credit-rg04"},
                ],
            },
        })

    def test_evidence_subtypes_and_allocation_lifecycle_fields_are_closed(self):
        bank = {
            "id": "evidence-bank", "type": "bank_payment", "source_ids": ["source-bank"],
            "payload": {"observed_at": "2026-04-10T18:30:00+08:00"},
        }
        summary = {
            "id": "evidence-summary", "type": "item_summary", "source_ids": ["source-item"],
            "payload": {"observed_at": "2026-04-09T15:00:00+08:00"},
        }
        self.assert_valid("evidence", bank)
        self.assert_valid("evidence", summary)

        two_sources = deepcopy(bank)
        two_sources["source_ids"].append("source-other")
        self.assert_invalid("evidence", two_sources)

        consumption = {
            "id": "consumption-rg05", "type": "consumption_record",
            "payload": {
                "expense_posting_id": "posting-expense", "category_id": "category", "amount": "40.00",
                "currency": "CNY", "statistics_at": "2026-04-10T18:30:00+08:00",
                "details": "Synthetic item", "source_observed_at": "2026-04-08T10:00:00+08:00",
                "source_item_id": "item-a", "source_id": "source-a", "evidence_id": "evidence-a",
            },
        }
        self.assert_valid("domainEntity", consumption)

    def test_operation_registry_accepts_strict_input_and_rejected_conflict(self):
        accepted = deepcopy(load_golden_case_v2(RG01)["operations"][0])
        accepted.update(action_type="manual_merged_payment", operation_class="creation")
        accepted["input"] = {
            "request_id": "request-rg05", "payment_at": "2026-04-10T18:30:00+08:00",
            "total_amount": "100.00", "currency": "CNY", "funding_account_id": "asset-bank-a",
            "items": [
                {"item_id": "item-a", "amount": "40.00", "currency": "CNY", "category_id": "category-a", "details": "Synthetic A", "source_observed_at": "2026-04-08T10:00:00+08:00"},
                {"item_id": "item-b", "amount": "60.00", "currency": "CNY", "category_id": "category-b", "details": "Synthetic B", "source_observed_at": "2026-04-09T15:00:00+08:00"},
            ],
            "explicit_confirmation": True,
        }
        self.assert_valid("operation", accepted)

        accepted["input"]["unknown"] = True
        self.assert_invalid("operation", accepted)

        rejected = deepcopy(load_golden_case_v2(RG01)["operations"][0])
        rejected.update(action_type="confirm_merged_payment_candidate", operation_class="rejection")
        rejected.pop("input")
        rejected["attempted_input"] = {
            "request_id": "request-rg05-allocation-conflict",
            "candidate_id": "candidate-rg05",
            "payment_total": "100.00",
            "allocation_total": "110.00",
            "currency": "CNY",
            "explicit_confirmation": True,
        }
        rejected["outcome"] = {
            "status": "rejected", "reason_code": "allocation_conflict",
            "field_path": "$.attempted_input.allocation_total",
        }
        self.assert_valid("operation", rejected)

        for field in (
            "request_id", "candidate_id", "payment_total", "allocation_total",
            "currency", "explicit_confirmation",
        ):
            with self.subTest(missing=field):
                missing = deepcopy(rejected)
                missing["attempted_input"].pop(field)
                self.assert_invalid("operation", missing)

        false_confirmation = deepcopy(rejected)
        false_confirmation["attempted_input"]["explicit_confirmation"] = False
        self.assert_invalid("operation", false_confirmation)

    def test_rg05_rejected_inputs_are_closed_for_all_four_actions(self):
        seed = deepcopy(load_golden_case_v2(RG01)["operations"][0])

        manual = deepcopy(seed)
        manual.update(action_type="manual_merged_payment", operation_class="rejection")
        manual.pop("input")
        manual["attempted_input"] = {
            "request_id": "request-rg05-manual",
            "total_amount": "100.00",
            "currency": "CNY",
            "funding_account_id": "asset-bank-a",
            "items": [
                {"id": "item-a", "amount": "40.00", "currency": "CNY", "category_id": None},
                {"item_id": "item-b", "amount": "60.00", "currency": "CNY", "category_id": "category-b"},
            ],
        }
        manual["outcome"] = {
            "status": "rejected", "reason_code": "secondary_category_required",
            "field_path": "$.attempted_input.items",
        }
        self.assert_valid("operation", manual)
        manual["attempted_input"]["items"][0]["unexpected"] = True
        self.assert_invalid("operation", manual)

        ingest = deepcopy(seed)
        ingest.update(action_type="ingest_merged_payment_facts", operation_class="rejection")
        ingest["attempted_input"] = {
            "request_id": "request-rg05-ingest",
            "bank_fact": {
                "source_id": "source-bank", "evidence_id": "evidence-bank",
                "observed_at": "2026-04-10T18:30:00+08:00", "details": "Bank",
                "amount": "-100.00", "currency": "CNY",
            },
            "item_facts": [
                {
                    "item_id": "item-a", "source_id": "source-a", "evidence_id": "evidence-a",
                    "evidence_kind": "item_receipt", "observed_at": "2026-04-08T10:00:00+08:00",
                    "details": "A", "amount": "40.00", "currency": "CNY",
                    "suggested_category_id": "category-a",
                },
                {
                    "item_id": "item-b", "source_id": "source-b", "evidence_id": "evidence-b",
                    "evidence_kind": "item_summary", "observed_at": "2026-04-09T15:00:00+08:00",
                    "details": "B", "amount": "60.00", "currency": "CNY",
                    "suggested_category_id": "category-b",
                },
            ],
        }
        ingest.pop("input")
        ingest["outcome"] = {
            "status": "rejected", "reason_code": "identity_conflict",
            "field_path": "$.attempted_input.request_id",
        }
        self.assert_valid("operation", ingest)

        receipt = deepcopy(seed)
        receipt.update(action_type="merge_item_receipt_evidence", operation_class="rejection")
        receipt["attempted_input"] = {
            "request_id": "request-rg05-receipt", "source_id": "source-receipt",
            "evidence_id": "evidence-receipt", "item_allocation_id": "allocation-b",
            "observed_at": "2026-04-11T09:00:00+08:00", "details": "Receipt",
            "amount": "60.00", "currency": "CNY",
        }
        receipt.pop("input")
        receipt["outcome"] = {
            "status": "rejected", "reason_code": "identity_conflict",
            "field_path": "$.attempted_input.request_id",
        }
        self.assert_valid("operation", receipt)


if __name__ == "__main__":
    unittest.main()
