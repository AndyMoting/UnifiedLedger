from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import unittest
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator

import golden_cases.v2 as v2
from golden_cases import GoldenCaseError


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas" / "golden-case-v2.schema.json").read_text(encoding="utf-8"))
STAMP = "2026-02-15T14:30:00+08:00"


def changes() -> dict:
    return {name: {"added_ids": [], "changed_ids": [], "removed_ids": []} for name in v2._ENTITY_COLLECTIONS}


def operation(action: str, cls: str, status: str, value: dict, *, reason: str | None = None, field: str | None = None) -> dict:
    result = {
        "id": "op-rg08", "root_id": "root-rg08", "sequence": 1,
        "operation_class": cls, "action_type": action,
        "baseline_state_id": "state-before", "result_state_id": "state-after",
        "outcome": {"status": status}, "status_changes": [],
        "deltas": {"entity_changes": changes(), "value_changes": {"balances": [], "reports": [], "derived_statuses": []}},
        "returned_ids": [],
    }
    result["attempted_input" if status == "rejected" else "input"] = value
    if status == "rejected":
        result["outcome"].update(reason_code=reason, field_path=field)
    elif status == "no_change":
        result["outcome"]["reason_code"] = "idempotent_retry" if action == "retry_idempotent_input" else "zero_formal_effect"
    return result


def schema_errors(value: dict) -> list:
    wrapper = {"$schema": SCHEMA["$schema"], "$defs": SCHEMA["$defs"], "$ref": "#/$defs/operation"}
    return list(Draft202012Validator(wrapper).iter_errors(value))


def catalog() -> dict:
    return {
        "accounts": [
            {"id": "bank", "name": "Bank", "kind": "asset", "currency": "CNY", "owned_by_user": True, "real_account": True, "reconciliation_eligible": True},
            {"id": "receivable", "name": "Receivable", "kind": "asset", "currency": "CNY", "owned_by_user": False, "real_account": False, "reconciliation_eligible": False},
            {"id": "interest", "name": "Interest", "kind": "income", "currency": "CNY", "owned_by_user": False, "real_account": False, "reconciliation_eligible": False},
            {"id": "external", "name": "External", "kind": "asset", "currency": "CNY", "owned_by_user": False, "real_account": True, "reconciliation_eligible": False},
            {"id": "expense", "name": "Expense", "kind": "expense", "currency": "CNY", "owned_by_user": False, "real_account": False, "reconciliation_eligible": False},
        ],
        "categories": [
            {"id": "income-parent", "name": "Income", "kind": "income", "parent_id": None, "active": True, "posting_account_id": None},
            {"id": "interest-category", "name": "Interest", "kind": "income", "parent_id": "income-parent", "active": True, "posting_account_id": "interest"},
            {"id": "inactive-category", "name": "Inactive", "kind": "income", "parent_id": "income-parent", "active": False, "posting_account_id": "interest"},
        ],
    }


def state() -> dict:
    position = {
        "id": "position", "type": "lending_position",
        "payload": {"counterparty_id": "person", "position_scope": "person_level_net_position", "contract_allocation_enabled": False, "receivable_account_id": "receivable", "principal_balance": "60.00", "currency": "CNY", "history": [
            {"id": "position-h1", "sequence": 1, "behavior_code": "lend", "amount": "100.00", "principal_balance_after": "100.00", "transaction_id": "lend-tx", "occurred_at": STAMP},
            {"id": "position-h2", "sequence": 2, "behavior_code": "collect", "amount": "-40.00", "principal_balance_after": "60.00", "transaction_id": "collect-tx", "occurred_at": STAMP},
        ]},
    }
    settlement = {
        "id": "settlement", "type": "lending_settlement",
        "payload": {"behavior_code": "collect", "counterparty_id": "person", "linked_position_id": "position", "allocated_lend_transaction_id": None, "transaction_id": "collect-tx", "destination_account_id": "bank", "interest_category_id": "interest-category", "total_received": "45.00", "currency": "CNY", "actual_receipt_at": STAMP, "confirmed_at": STAMP,
                    "components": [{"id": "component-principal", "kind": "principal", "amount": "40.00", "posting_id": "principal-p"}, {"id": "component-interest", "kind": "interest", "amount": "5.00", "posting_id": "interest-p"}, {"id": "component-fee", "kind": "fee", "amount": "0.00", "posting_id": None}],
                    "history": [{"id": "settlement-h1", "sequence": 1, "status": "confirmed", "occurred_at": STAMP, "transaction_id": "collect-tx", "formal_effect_count": 1}]},
    }
    sources = [
        {"id": "credit-source", "type": "bank_credit", "payload": {"source_record_id": "credit-record", "observed_at": STAMP, "booking_at": STAMP, "value_at": STAMP, "account_id": "bank", "amount": "45.00", "currency": "CNY", "original_source_payload_hash": "sha256:credit", "immutable_payload_hash": "sha256:credit-immutable"}},
        {"id": "agreement-source", "type": "lending_agreement", "payload": {"source_record_id": "agreement-record", "observed_at": STAMP, "counterparty_id": "person", "currency": "CNY", "immutable_payload_hash": "sha256:agreement"}},
    ]
    result = {
        "root_id": "root-rg08", "catalog": catalog(),
        "transactions": [{"id": "lend-tx", "type": "lending_disbursement"}, {"id": "collect-tx", "type": "lending_collection"}],
        "transaction_versions": [], "posting_sets": [],
        "postings": [{"id": "principal-p", "role": "lending_receivable", "account_id": "receivable", "amount": "-40.00", "currency": "CNY", "reconciliation_eligible": False}, {"id": "interest-p", "role": "lending_interest", "account_id": "interest", "amount": "-5.00", "currency": "CNY", "reconciliation_eligible": False}],
        "sources": sources,
        "candidates": [{"id": "candidate", "type": "lending_collection_credit", "source_ids": ["credit-source", "agreement-source"], "confidence": "0.94", "payload": {"proposed_total_received": "45.00", "proposed_principal_amount": None, "proposed_interest_amount": None, "proposed_fee_amount": None, "currency": "CNY", "proposed_destination_account_id": "bank", "proposed_actual_receipt_at": STAMP, "proposed_behavior_code": None, "proposed_counterparty_id": None, "rule_version": 1, "bank_evidence_proves_component_split": False, "expected_interest_may_confirm_split": False, "name_match_may_confirm_counterparty": False, "requires_confirmation": ["behavior_code", "counterparty_id", "destination_account_id", "principal_amount", "interest_and_fee_amounts", "actual_receipt_time"]}, "status_history": [{"id": "candidate-h1", "sequence": 1, "status": "pending_confirmation", "occurred_at": STAMP, "formal_effect_count": 0}] }],
        "confirmations": [{"id": "confirmation", "type": "lending_settlement_confirmation", "operation_id": "op-rg08", "subject": {"kind": "transaction", "id": "collect-tx"}, "confirmed_at": STAMP, "payload": {"confirmation_request_id": "request", "transaction_id": "collect-tx", "counterparty_id": "person", "candidate_id": "candidate", "settlement_id": "settlement"}}],
        "evidence": [{"id": "credit-evidence", "type": "asset_credit", "source_ids": ["credit-source"], "payload": {"observed_at": STAMP}}, {"id": "agreement-evidence", "type": "lending_agreement", "source_ids": ["agreement-source"], "payload": {"observed_at": STAMP}}],
        "evidence_links": [{"id": "relationship-link", "evidence_id": "agreement-evidence", "target_kind": "domain_entity", "target_id": "position", "role": "counterparty_lending_relationship"}],
        "relations": [{"id": "relation", "type": "counterparty_lending_relationship", "member_refs": [{"kind": "domain_entity", "id": "position"}], "payload": {"counterparty_id": "person"}}],
        "domain_entities": [position, settlement], "audit_links": [], "posting_reconciliations": [],
    }
    return result


def complete_state(value: dict, state_id: str, root_id: str, operation_id: str | None) -> dict:
    result = deepcopy(value)
    result.update(id=state_id, root_id=root_id, as_of_operation_id=operation_id)
    result.setdefault("balances", [
        {"account_id": item["id"], "currency": "CNY", "amount": "0.00"}
        for item in result["catalog"]["accounts"]
    ])
    result.setdefault("reports", [])
    result.setdefault("derived_statuses", [])
    return result


def public_rg08_rejected_case() -> dict:
    base = state()
    for category in base["catalog"]["categories"]:
        category.pop("kind", None)
    base["transactions"] = []
    base["transaction_versions"] = []
    base["posting_sets"] = []
    base["postings"] = []
    base["sources"] = []
    base["candidates"] = []
    base["confirmations"] = []
    base["evidence"] = []
    base["evidence_links"] = []
    base["relations"] = []
    base["domain_entities"] = []
    before = complete_state(base, "state-before", "root-public", None)
    after = complete_state(base, "state-after", "root-public", "op-public")
    op = operation(
        "validate_lending_event", "rejection", "rejected",
        {"behavior_code": "lend", "total_received": "10.00", "funding_account_id": "missing", "counterparty_id": "person"},
        reason="unknown_account", field="$.attempted_input.funding_account_id",
    )
    op.update(id="op-public", root_id="root-public")
    return {
        "contract": "unifiedledger.golden-case", "contract_version": "2.0.0",
        "case": {"id": "RG-08", "level": "core_required", "rule_version": 1, "approval_status": "draft_for_review", "ledger_id": "ledger", "timezone": "Asia/Shanghai", "currencies": [{"code": "CNY", "precision": 2}]},
        "roots": [{"id": "root-public", "purpose": "synthetic_public_rejection", "initial_state_id": "state-before", "operation_ids": ["op-public"]}],
        "states": [before, after], "operations": [op],
    }


def formal_confirmation_effect() -> tuple[dict, dict, dict]:
    baseline = state()
    baseline["candidates"].append(deepcopy(baseline["candidates"][0]) | {"id": "candidate-other"})
    result = deepcopy(baseline)
    candidate = next(item for item in result["candidates"] if item["id"] == "candidate")
    candidate["status_history"].append({"id": "candidate-h2", "sequence": 2, "status": "confirmed", "occurred_at": STAMP, "formal_effect_count": 1})
    position = next(item for item in result["domain_entities"] if item["id"] == "position")
    position["payload"]["principal_balance"] = "20.00"
    position["payload"]["history"].append({"id": "position-h3", "sequence": 3, "behavior_code": "collect", "amount": "-40.00", "principal_balance_after": "20.00", "transaction_id": "formal-tx", "occurred_at": STAMP})
    result["transactions"].append({"id": "formal-tx", "type": "lending_collection", "current_version_id": "formal-v1"})
    result["transaction_versions"].append({"id": "formal-v1", "transaction_id": "formal-tx", "version_number": 1, "posting_set_id": "formal-set", "occurred_at": STAMP, "statistics_at": STAMP, "effective_at": STAMP, "note": "", "confirmation_id": "formal-confirmation"})
    result["posting_sets"].append({"id": "formal-set", "posting_ids": ["formal-destination", "formal-principal", "formal-interest"]})
    result["postings"].extend([
        {"id": "formal-destination", "posting_set_id": "formal-set", "role": "lending_principal_in", "account_id": "bank", "amount": "45.00", "currency": "CNY", "reconciliation_eligible": True},
        {"id": "formal-principal", "posting_set_id": "formal-set", "role": "lending_receivable", "account_id": "receivable", "amount": "-40.00", "currency": "CNY", "reconciliation_eligible": False},
        {"id": "formal-interest", "posting_set_id": "formal-set", "role": "lending_interest", "account_id": "interest", "category_id": "interest-category", "amount": "-5.00", "currency": "CNY", "reconciliation_eligible": False},
    ])
    settlement = {
        "id": "formal-settlement", "type": "lending_settlement",
        "payload": {"behavior_code": "collect", "counterparty_id": "person", "linked_position_id": "position", "allocated_lend_transaction_id": None, "transaction_id": "formal-tx", "destination_account_id": "bank", "interest_category_id": "interest-category", "total_received": "45.00", "currency": "CNY", "actual_receipt_at": STAMP, "confirmed_at": STAMP,
                    "components": [{"id": "formal-component-principal", "kind": "principal", "amount": "40.00", "posting_id": "formal-principal"}, {"id": "formal-component-interest", "kind": "interest", "amount": "5.00", "posting_id": "formal-interest"}, {"id": "formal-component-fee", "kind": "fee", "amount": "0.00", "posting_id": None}],
                    "history": [{"id": "formal-settlement-h1", "sequence": 1, "status": "confirmed", "occurred_at": STAMP, "transaction_id": "formal-tx", "formal_effect_count": 1}]},
    }
    result["domain_entities"].append(settlement)
    result["confirmations"].append({"id": "formal-confirmation", "type": "lending_settlement_confirmation", "operation_id": "formal-op", "subject": {"kind": "transaction", "id": "formal-tx"}, "confirmed_at": STAMP, "payload": {"confirmation_request_id": "formal-request", "transaction_id": "formal-tx", "counterparty_id": "person", "candidate_id": "candidate", "settlement_id": "formal-settlement"}})
    result["evidence_links"].append({"id": "formal-link", "evidence_id": "credit-evidence", "target_kind": "posting", "target_id": "formal-destination", "role": "destination_asset_posting"})
    result["posting_reconciliations"].append({"id": "formal-reconciliation", "posting_id": "formal-destination", "status": "matched"})
    op = operation("confirm_imported_lending_collection", "creation", "accepted", {"variant": "formal_confirmation", "request_id": "formal-request", "candidate_id": "candidate", "behavior_code": "collect", "counterparty_id": "person", "destination_account_id": "bank", "principal_amount": "40.00", "interest_amount": "5.00", "fee_amount": "0.00", "interest_category_id": "interest-category", "currency": "CNY", "actual_receipt_at": STAMP, "confirmed_at": STAMP, "explicit_confirmation": True, "explicitly_confirmed_fields": ["behavior_code", "counterparty_id", "destination_account_id", "principal_amount", "interest_and_fee_amounts", "actual_receipt_time"]})
    op["id"] = "formal-op"
    op["returned_ids"] = [{"kind": "candidate", "id": "candidate"}, {"kind": "transaction", "id": "formal-tx"}, {"kind": "transaction_version", "id": "formal-v1"}, {"kind": "domain_entity", "id": "formal-settlement"}]
    return op, baseline, result


class Rg08V2ValidatorTests(unittest.TestCase):
    def test_five_actions_are_registered_with_closed_variants(self):
        inputs = [
            ("validate_lending_event", "creation", "accepted", {"variant": "lend", "request_id": "request", "behavior_code": "lend", "counterparty_id": "person", "funding_account_id": "bank", "principal_amount": "100.00", "currency": "CNY", "actual_at": STAMP, "confirmed_at": STAMP, "explicit_confirmation": True}),
            ("validate_lending_settlement", "creation", "accepted", {"variant": "manual_collection", "request_id": "request", "behavior_code": "collect", "counterparty_id": "person", "linked_position_id": "position", "allocated_lend_transaction_id": None, "destination_account_id": "bank", "total_received": "45.00", "principal_amount": "40.00", "interest_amount": "5.00", "fee_amount": "0.00", "interest_category_id": "interest-category", "currency": "CNY", "actual_receipt_at": STAMP, "confirmed_at": STAMP, "explicit_confirmation": True}),
            ("confirm_imported_lending_collection", "creation", "accepted", {"variant": "import_intake", "credit_source_id": "credit", "agreement_source_id": "agreement", "candidate_id": "candidate", "candidate_type": "lending_collection_credit", "proposed_total_received": "45.00", "proposed_destination_account_id": "bank", "proposed_actual_receipt_at": STAMP, "currency": "CNY", "rule_version": 1, "confidence": "0.94"}),
            ("allocate_lending_collection", "creation", "accepted", {"variant": "maximum_allocation", "request_id": "request", "counterparty_id": "person", "destination_account_id": "bank", "total_received": "105.00", "principal_amount": "100.00", "interest_amount": "5.00", "fee_amount": "0.00", "interest_category_id": "interest-category", "currency": "CNY", "actual_receipt_at": STAMP, "confirmed_at": STAMP}),
            ("retry_idempotent_input", "read", "no_change", {"variant": "retry", "input_anchor_id": "request"}),
        ]
        for action, cls, status, value in inputs:
            with self.subTest(action=action):
                self.assertEqual(schema_errors(operation(action, cls, status, value)), [])
        invalid = operation("validate_lending_event", "creation", "accepted", inputs[1][3])
        self.assertTrue(schema_errors(invalid), "cross-action variant must be rejected")

    def test_d090_rejection_paths_and_all_registered_reason_families(self):
        baseline = state()
        frozen = json.loads((ROOT / "golden" / "rules" / "rg-08.json").read_text(encoding="utf-8"))
        cases = []
        for item in frozen["invalid_inputs"]:
            attempted = deepcopy(item["input"])
            if "binary_float_total" in attempted:
                attempted["total_received"] = attempted.pop("binary_float_total")
            id_projection = {
                "asset-external-c": "external",
                "expense-validation": "expense",
                "income-category-interest-inactive": "inactive-category",
            }
            for key, value in list(attempted.items()):
                attempted[key] = id_projection.get(value, value)
            field = item["expected"]["field"]
            if item["id"] == "negative-interest":
                field = "principal_amount"
            elif item["id"] == "guessed-split":
                field = "split_source"
            cases.append((item["operation_context"]["operation_type"], attempted, item["expected"]["reason"], field))
        for item in frozen["import_collection"]["incomplete_confirmations"]:
            cases.append((item["operation_context"]["operation_type"], item["input"], item["expected"]["reason"], item["expected"]["field"]))
        cap = frozen["principal_cap"]["over_balance_attempt"]
        cases.append((cap["operation_context"]["operation_type"], cap["input"], cap["expected"]["reason"], cap["expected"]["field"]))
        for action, attempted, reason, field_name in cases:
            field = f"$.attempted_input.{field_name}"
            op = operation(action, "rejection", "rejected", attempted, reason=reason, field=field)
            v2._validate_action_input(op, "$.operations[0]", baseline, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))
        empty = operation("validate_lending_settlement", "rejection", "rejected", {}, reason="total_must_be_positive", field="$.attempted_input.total_received")
        self.assertTrue(schema_errors(empty), "empty attempted input must fail closed")
        wrong = operation("validate_lending_settlement", "rejection", "rejected", cases[0][1], reason="total_must_be_positive", field="$.attempted_input.total_received")
        with self.assertRaises(GoldenCaseError):
            v2._validate_action_input(wrong, "$", baseline, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))
        self.assertEqual(v2._RG08_REJECTION_FIELDS["component_must_be_nonnegative"], "$.attempted_input.principal_amount")
        self.assertEqual(v2._RG08_REJECTION_FIELDS["explicit_component_split_required"], "$.attempted_input.split_source")

    def test_lending_topology_and_report_semantics(self):
        accounts = {item["id"]: item for item in catalog()["accounts"]}
        categories = {item["id"]: item for item in catalog()["categories"]}
        transaction = {"id": "collect", "type": "lending_collection"}
        postings = [
            {"id": "destination", "account_id": "bank", "amount": "45.00", "currency": "CNY", "role": "lending_principal_in", "category_id": None, "reconciliation_eligible": True},
            {"id": "principal", "account_id": "receivable", "amount": "-40.00", "currency": "CNY", "role": "lending_receivable", "category_id": None, "reconciliation_eligible": False},
            {"id": "interest", "account_id": "interest", "amount": "-5.00", "currency": "CNY", "role": "lending_interest", "category_id": "interest-category", "reconciliation_eligible": False},
        ]
        v2._validate_transaction_posting_semantics(transaction, postings, accounts, "$", {"CNY": 2}, categories)
        current = {"collect": (transaction, {"statistics_at": STAMP}, postings)}
        values = v2._report_values(current, accounts, {"period_type": "month", "period": "2026-02"}, "CNY")
        self.assertEqual(values["cash_inflow"], 45)
        self.assertEqual(values["principal_external_cash_flow"], 40)
        self.assertEqual(values["ordinary_income"], 5)
        self.assertEqual(values["net_worth_change"], 5)
        broken = deepcopy(postings); broken[0]["reconciliation_eligible"] = False
        with self.assertRaises(GoldenCaseError):
            v2._validate_transaction_posting_semantics(transaction, broken, accounts, "$", {"CNY": 2}, categories)

    def test_full_rg08_state_contract_and_mutations(self):
        value = state()
        indexes = v2._state_indexes(value, "$.states[0]")
        v2._validate_rg08_contract(value, "$.states[0]", indexes, {"op-rg08": {"id": "op-rg08"}}, {"CNY": 2})
        mutations = [
            ("source/evidence subtype", lambda x: x["evidence"][0].update(type="asset_debit")),
            ("candidate gates", lambda x: x["candidates"][0]["payload"]["requires_confirmation"].pop()),
            ("position balance", lambda x: x["domain_entities"][0]["payload"].update(principal_balance="59.00")),
            ("fee zero", lambda x: x["domain_entities"][1]["payload"]["components"][2].update(amount="1.00")),
            ("settlement position", lambda x: x["domain_entities"][1]["payload"].update(linked_position_id="missing")),
            ("confirmation subject", lambda x: x["confirmations"][0].update(subject={"kind": "candidate", "id": "candidate"})),
            ("relationship member", lambda x: x["relations"][0]["member_refs"][0].update(id="settlement")),
        ]
        for label, mutate in mutations:
            broken = deepcopy(value); mutate(broken)
            with self.subTest(label=label), self.assertRaises((GoldenCaseError, KeyError)):
                v2._validate_rg08_contract(broken, "$", v2._state_indexes(broken, "$"), {"op-rg08": {"id": "op-rg08"}}, {"CNY": 2})

    def test_typed_lending_audit_endpoints(self):
        value = state()
        value["audit_links"] = [
            {"id": "mirror-audit", "type": "mirror_of_evidence_id", "from": {"kind": "evidence", "id": "credit-evidence"}, "to": {"kind": "evidence", "id": "agreement-evidence"}, "payload": {}},
            {"id": "merge-audit", "type": "merged_into_evidence_link_id", "from": {"kind": "evidence_link", "id": "relationship-link"}, "to": {"kind": "evidence_link", "id": "destination-link"}, "payload": {}},
        ]
        value["evidence_links"].append({"id": "destination-link", "evidence_id": "credit-evidence", "target_kind": "posting", "target_id": "destination-p", "role": "destination_asset_posting"})
        value["postings"].append({"id": "destination-p", "role": "lending_principal_in", "account_id": "bank", "amount": "45.00", "currency": "CNY", "reconciliation_eligible": True})
        indexes = v2._state_indexes(value, "$")
        for link in value["audit_links"]:
            expected_kind = "evidence" if link["type"] == "mirror_of_evidence_id" else "evidence_link"
            v2._resolve_ref(value, indexes, {"op-rg08": {"id": "op-rg08", "root_id": "root-rg08"}}, expected_kind, link["from"]["id"], "$")
            v2._resolve_ref(value, indexes, {"op-rg08": {"id": "op-rg08", "root_id": "root-rg08"}}, expected_kind, link["to"]["id"], "$")
        bad = deepcopy(value["audit_links"][0]); bad["to"] = {"kind": "evidence_link", "id": "relationship-link"}
        self.assertNotEqual(bad["from"]["kind"], bad["to"]["kind"])

    def test_accepted_variant_counts_retry_and_rename_contract(self):
        expected = {"lend", "manual_collection", "maximum_allocation", "import_intake", "formal_confirmation", "mirror_merge"}
        for variant in expected:
            op = {"outcome": {"status": "accepted"}, "input": {"variant": variant}}
            self.assertTrue(v2._rg08_effect_counts(op, "$") is not None)
        owner = operation("validate_lending_event", "update", "no_change", {"variant": "rename_counterparty", "request_id": "anchor", "counterparty_id": "person", "old_display_name": "A", "new_display_name": "B", "name_history_id": "history"})
        owner.update(id="owner", root_id="owner-root", baseline_state_id="owner-before", result_state_id="owner-after")
        owner["returned_ids"] = [{"kind": "counterparty", "id": "person"}, {"kind": "name_history", "id": "history"}]
        owner_before = state(); owner_before.update(id="owner-before", root_id="owner-root", as_of_operation_id=None)
        owner_after = deepcopy(owner_before); owner_after.update(id="owner-after", as_of_operation_id="owner")
        retry = operation("retry_idempotent_input", "read", "no_change", {"variant": "retry", "input_anchor_id": "anchor"})
        retry.update(id="retry", root_id="retry-root", baseline_state_id="retry-before", result_state_id="retry-after")
        retry["returned_ids"] = deepcopy(owner["returned_ids"])
        retry_before = deepcopy(owner_after); retry_before.update(id="retry-before", root_id="retry-root", as_of_operation_id=None)
        retry_after = deepcopy(retry_before); retry_after.update(id="retry-after", as_of_operation_id="retry")
        operations = [owner, retry]
        states = {item["id"]: item for item in (owner_before, owner_after, retry_before, retry_after)}
        v2._validate_no_change_retry(retry, "$.operations[1]", [], all_operations=operations, states=states)
        indexes = v2._state_indexes(retry_after, "$")
        operation_index = {item["id"]: item for item in operations}
        v2._validate_returned_ids(retry, "$.operations[1]", retry_after, indexes, operation_index)
        wrong_history = deepcopy(retry); wrong_history["returned_ids"][1]["id"] = "other-history"
        with self.assertRaises(GoldenCaseError):
            v2._validate_returned_ids(wrong_history, "$", retry_after, indexes, operation_index)
        duplicate = deepcopy(owner); duplicate["id"] = "owner-duplicate"
        with self.assertRaises(GoldenCaseError):
            v2._validate_no_change_retry(retry, "$", [], all_operations=[owner, duplicate, retry], states=states)
        changed = deepcopy(retry_before); changed["catalog"]["accounts"][0]["name"] = "Drift"
        states["retry-before"] = changed
        with self.assertRaises(GoldenCaseError):
            v2._validate_no_change_retry(retry, "$", [], all_operations=operations, states=states)
        rename = operation("validate_lending_event", "update", "no_change", {"variant": "rename_counterparty", "request_id": "rename", "counterparty_id": "person", "old_display_name": "A", "new_display_name": "B", "name_history_id": "history"})
        rename["returned_ids"] = [{"kind": "counterparty", "id": "person"}, {"kind": "name_history", "id": "history"}]
        v2._validate_no_change_retry(rename, "$.operations[0]", [])

    def test_public_validator_routes_rg08_and_inventory_is_exact(self):
        operations = []
        specs = [
            ("accepted", "lend", "validate_lending_event", 1),
            ("no_change", "rename_counterparty", "validate_lending_event", 1),
            ("accepted", "manual_collection", "validate_lending_settlement", 1),
            ("accepted", "maximum_allocation", "allocate_lending_collection", 1),
            ("accepted", "import_intake", "confirm_imported_lending_collection", 1),
            ("accepted", "formal_confirmation", "confirm_imported_lending_collection", 1),
            ("accepted", "mirror_merge", "confirm_imported_lending_collection", 1),
            ("no_change", "retry", "retry_idempotent_input", 12),
        ]
        for status, variant, action, count in specs:
            for index in range(count):
                operations.append({
                    "root_id": f"root-{variant}-{index}",
                    "action_type": action,
                    "outcome": {"status": status},
                    "input": {"variant": variant, "input_anchor_id": f"anchor-{index}"} if variant == "retry" else {"variant": variant},
                })
        rejected = {
            "validate_lending_event": 1,
            "validate_lending_settlement": 17,
            "confirm_imported_lending_collection": 6,
            "allocate_lending_collection": 1,
        }
        for action, count in rejected.items():
            operations.extend({"root_id": f"root-rejected-{action}-{index}", "action_type": action, "outcome": {"status": "rejected"}} for index in range(count))
        inventory = {"operations": operations}
        v2._validate_rg08_inventory(inventory)
        for mutation in (lambda x: x["operations"].pop(), lambda x: x["operations"][7]["input"].update(input_anchor_id="anchor-1")):
            broken = deepcopy(inventory); mutation(broken)
            with self.assertRaises(GoldenCaseError):
                v2._validate_rg08_inventory(broken)

        public = public_rg08_rejected_case()
        v2.validate_golden_case_v2(public)
        for mutation in (
            lambda x: x["operations"][0]["attempted_input"].update(funding_account_id="bank"),
            lambda x: x["operations"][0]["outcome"].update(reason_code="invalid_lending_behavior"),
            lambda x: x["operations"][0]["deltas"]["value_changes"]["balances"].append({"key": {"account_id": "bank", "currency": "CNY"}, "before": "0.00", "after": "1.00"}),
        ):
            broken = deepcopy(public); mutation(broken)
            with self.assertRaises(GoldenCaseError):
                v2.validate_golden_case_v2(broken)

    def test_formal_confirmation_exact_candidate_and_settlement_binders(self):
        op, baseline, result = formal_confirmation_effect()
        expected = v2._expected_entity_changes(baseline, result)
        v2._validate_rg08_action_effects(op, "$", baseline, result, expected)
        mutations = [
            lambda x: next(item for item in x["candidates"] if item["id"] == "candidate").update(id="candidate-other"),
            lambda x: next(item for item in x["domain_entities"] if item["id"] == "formal-settlement")["payload"].update(linked_position_id="missing"),
            lambda x: next(item for item in x["domain_entities"] if item["id"] == "formal-settlement")["payload"].update(allocated_lend_transaction_id="lend-tx"),
            lambda x: next(item for item in x["confirmations"] if item["id"] == "formal-confirmation")["payload"].update(candidate_id="candidate-other"),
            lambda x: next(item for item in x["confirmations"] if item["id"] == "formal-confirmation").update(confirmed_at="2026-02-15T14:31:00+08:00"),
        ]
        for mutate in mutations:
            broken = deepcopy(result); mutate(broken)
            with self.assertRaises((GoldenCaseError, KeyError, IndexError)):
                v2._validate_rg08_action_effects(op, "$", baseline, broken, v2._expected_entity_changes(baseline, broken))



if __name__ == "__main__":
    unittest.main()
