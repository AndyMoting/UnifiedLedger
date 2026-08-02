from __future__ import annotations

from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

import golden_cases.v2 as golden_v2
from golden_cases import (
    GoldenCaseError,
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    validate_golden_case_v2,
)


ROOT = Path(__file__).resolve().parents[2]
V1_PATH = ROOT / "golden" / "rules" / "rg-07.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-07-expected.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
METRICS = ("cash_inflow", "cash_outflow", "consumption", "income", "net_worth_change")


def mid(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id("RG-07", root_id, kind, locator, occurrence)


def catalog(v1: dict) -> dict:
    return {
        "accounts": [
            {
                "id": item["id"], "name": item["id"], "kind": item["type"],
                "currency": item["currency"], "owned_by_user": item["owned_by_user"],
                "real_account": item["real_account"],
                "reconciliation_eligible": bool(
                    item["owned_by_user"] and item["real_account"]
                    and item["type"] in {"asset", "liability"}
                    and item["destination_kind"] != "store_credit"
                ),
            }
            for item in v1["catalog"]["accounts"]
        ],
        "categories": [
            {
                "id": item["id"], "name": item["id"], "parent_id": item["parent_id"],
                "posting_account_id": item["account_id"], "active": item["active"],
            }
            for item in v1["catalog"]["categories"]
        ],
    }


def report(period_type: str, period: str) -> dict:
    return {
        "period_type": period_type, "period": period,
        "metrics": [
            {"metric": metric, "applicability": "applicable", "currency": "CNY", "amount": "0.00"}
            for metric in METRICS
        ],
    }


def current_parts(state: dict) -> list[tuple[dict, dict, list[dict]]]:
    versions = {item["id"]: item for item in state["transaction_versions"]}
    sets = {item["id"]: item for item in state["posting_sets"]}
    postings = {item["id"]: item for item in state["postings"]}
    return [
        (transaction, versions[transaction["current_version_id"]], [
            postings[item_id] for item_id in sets[versions[transaction["current_version_id"]]["posting_set_id"]]["posting_ids"]
        ])
        for transaction in state["transactions"]
    ]


def refresh(state: dict) -> None:
    amounts = {item["id"]: Decimal("0") for item in state["catalog"]["accounts"]}
    for _, _, postings in current_parts(state):
        for posting in postings:
            amounts[posting["account_id"]] += Decimal(posting["amount"])
    state["balances"] = [
        {"account_id": account_id, "currency": "CNY", "amount": f"{amount:.2f}"}
        for account_id, amount in amounts.items()
    ]
    accounts = {item["id"]: item for item in state["catalog"]["accounts"]}
    current = {transaction["id"]: part for part in current_parts(state) for transaction in [part[0]]}
    for item in state["reports"]:
        values = golden_v2._report_values(current, accounts, item, "CNY")
        for metric in item["metrics"]:
            if metric["applicability"] == "applicable":
                metric["amount"] = f"{values[metric['metric']]:.2f}"


def state_id(root_id: str, locator: str, occurrence: str) -> str:
    return mid(root_id, "state", locator, occurrence)


def empty_state(v1: dict, root_id: str, locator: str, occurrence: str, as_of: str | None) -> dict:
    opening = v1["opening"]["transactions"][0]
    version_id = mid(root_id, "transaction_version", "$.opening.transactions[*]", opening["id"])
    set_id = mid(root_id, "posting_set", "$.opening.transactions[*]", opening["id"])
    postings = [
        {"id": item["id"], "posting_set_id": set_id, "account_id": item["account_id"],
         "amount": item["amount"], "currency": item["currency"], "reconciliation_eligible": False}
        for item in opening["postings"]
    ]
    result = {
        "id": state_id(root_id, locator, occurrence), "root_id": root_id,
        "as_of_operation_id": as_of, "catalog": catalog(v1),
        "transactions": [{"id": opening["id"], "type": "opening_balance", "current_version_id": version_id}],
        "transaction_versions": [{"id": version_id, "transaction_id": opening["id"], "version_number": 1,
            "posting_set_id": set_id, "occurred_at": opening["occurred_at"],
            "statistics_at": opening["occurred_at"], "effective_at": opening["occurred_at"]}],
        "posting_sets": [{"id": set_id, "posting_ids": [item["id"] for item in postings]}],
        "postings": postings, "sources": [], "candidates": [], "confirmations": [], "evidence": [],
        "evidence_links": [], "relations": [], "domain_entities": [], "audit_links": [],
        "posting_reconciliations": [], "balances": [],
        "reports": [report(*item) for item in (("day", "2026-01-10"), ("day", "2026-02-02"),
            ("day", "2026-02-10"), ("month", "2026-01"), ("month", "2026-02"),
            ("cumulative", "lifecycle"))], "derived_statuses": [],
    }
    refresh(result)
    return result


def clone(state: dict, operation_id: str, locator: str, occurrence: str) -> dict:
    result = deepcopy(state)
    result["id"] = state_id(state["root_id"], locator, occurrence)
    result["as_of_operation_id"] = operation_id
    return result


def add_original(v1: dict, state: dict, operation_id: str, root_id: str, *, confirmation: bool = True) -> str | None:
    original = v1["original"]["transaction"]
    state["transactions"].append({"id": original["id"], "type": "expense", "current_version_id": original["current_version_id"]})
    state["transaction_versions"].append({"id": original["current_version_id"], "transaction_id": original["id"], "version_number": 1,
        "posting_set_id": original["posting_set_id"], "occurred_at": original["occurred_at"],
        "statistics_at": original["statistics_at"], "effective_at": original["occurred_at"],
        "created_at": v1["original"]["confirmation"]["confirmed_at"], "note": ""})
    state["posting_sets"].append({"id": original["posting_set_id"], "posting_ids": [item["id"] for item in original["postings"]]})
    state["postings"].extend([
        {"id": item["id"], "posting_set_id": original["posting_set_id"], "account_id": item["account_id"],
         **({"category_id": v1["original"]["category_id"]} if item["account_id"] == "expense-account-daily" else {}),
         "amount": item["amount"], "currency": item["currency"],
         "role": "expense" if item["amount"] > "0" else "payment_asset",
         "reconciliation_eligible": item["reconciliation_eligible"]}
        for item in original["postings"]
    ])
    confirmation_id = None
    if confirmation:
        confirmation_id = mid(root_id, "confirmation", "$.original.confirmation", original["id"])
        state["transaction_versions"][-1]["confirmation_id"] = confirmation_id
        state["confirmations"].append({"id": confirmation_id, "type": "explicit_manual_save", "operation_id": operation_id,
            "subject": {"kind": "operation", "id": operation_id}, "confirmed_at": v1["original"]["confirmation"]["confirmed_at"], "payload": {}})
    reconciliation_id = mid(root_id, "posting_reconciliation", "$.original.reconciliation", "posting-original-asset-rg07")
    state["posting_reconciliations"].append({"id": reconciliation_id, "posting_id": "posting-original-asset-rg07", "status": "pending" if confirmation else "matched"})
    refresh(state); refresh_statuses(state)
    return confirmation_id


def add_original_evidence(v1: dict, state: dict) -> None:
    source = v1["original"]["provenance"]["source_records"][0]
    evidence = v1["original"]["provenance"]["evidence"][0]
    link = v1["original"]["provenance"]["evidence_links"][0]
    state["sources"].append({"id": source["id"], "type": "bank_debit", "payload": {
        **source, "kind": "bank_debit", "evidence_id": source["evidence_id"]}})
    state["sources"][-1]["payload"].pop("id")
    state["evidence"].append({"id": evidence["id"], "type": "asset_debit", "source_ids": [source["id"]], "payload": {"observed_at": evidence["observed_at"]}})
    state["evidence_links"].append({"id": link["id"], "evidence_id": link["evidence_id"], "target_kind": "posting", "target_id": link["target_id"], "role": link["role"]})
    reconciliation = next(item for item in state["posting_reconciliations"] if item["posting_id"] == link["target_id"])
    reconciliation["status"] = "matched"
    refresh_statuses(state)


def changes(before: dict, after: dict) -> dict:
    return {
        "entity_changes": golden_v2._expected_entity_changes(before, after),
        "value_changes": {
            "balances": [{"key": {"account_id": key[0], "currency": key[1]}, "before": old, "after": new}
                for key, (old, new) in sorted(golden_v2._changes(golden_v2._balance_map(before), golden_v2._balance_map(after)).items())],
            "reports": [{"key": {"period_type": key[0], "period": key[1], "metric": key[2], **({"currency": key[3]} if key[3] else {})}, "before": old, "after": new}
                for key, (old, new) in sorted(golden_v2._changes(golden_v2._report_map(before), golden_v2._report_map(after)).items())],
            "derived_statuses": [{"key": {"kind": key[0], "target_id": key[1], "status_name": key[2]}, "before": old, "after": new}
                for key, (old, new) in sorted(golden_v2._changes(golden_v2._status_map(before), golden_v2._status_map(after)).items())],
        },
    }


def op(root_id: str, sequence: int, operation_id: str, action: str, cls: str, before: dict, after: dict, *, input_value=None, attempted=None, outcome=None, returned=None) -> dict:
    value = {"id": operation_id, "root_id": root_id, "sequence": sequence, "action_type": action,
        "operation_class": cls, "baseline_state_id": before["id"], "result_state_id": after["id"],
        "outcome": outcome or {"status": "accepted"}, "status_changes": [], "deltas": changes(before, after), "returned_ids": returned or []}
    for status in value["deltas"]["value_changes"]["derived_statuses"]:
        value["status_changes"].append({"target_kind": status["key"]["kind"], "target_id": status["key"]["target_id"],
            "status_name": status["key"]["status_name"], "before": status["before"], "after": status["after"]})
    if input_value is not None: value["input"] = input_value
    if attempted is not None: value["attempted_input"] = attempted
    return value


def source(state: dict, source_id: str, evidence_id: str, source_type: str, payload: dict) -> None:
    state["sources"].append({"id": source_id, "type": source_type, "payload": {"source_record_id": source_id, "evidence_id": evidence_id,
        "immutable_payload_hash": f"sha256:{source_id}", "kind": source_type, **payload}})
    types = {"merchant_refund_notice": "refund_notice", "wallet_credit": "asset_credit", "combined_refund_statement": "combined_refund_statement", "wallet_credit_mirror": "asset_credit_mirror"}
    state["evidence"].append({"id": evidence_id, "type": types[source_type], "source_ids": [source_id], "payload": {"observed_at": payload["observed_at"]}})


def relationship(root_id: str, locator: str, relation_id: str, original_id: str, *, receipt_id=None, category_id="expense-category-daily", requested="30.00", received="0.00", destination=None, times=None, history=None) -> tuple[dict, dict]:
    relation = {"id": relation_id, "type": "refund", "member_refs": [{"kind": "transaction", "id": original_id}] + ([] if receipt_id is None else [{"kind": "transaction", "id": receipt_id}]), "payload": {}}
    entity = {"id": mid(root_id, "domain_entity", "$.entity_registry.relations[*]", relation_id), "type": "refund_relationship", "payload": {
        "relation_id": relation_id, "original_transaction_id": original_id, "refund_transaction_id": receipt_id,
        "category_id": category_id, "requested_amount": requested, "received_amount": received, "currency": "CNY",
        "destination_account_id": destination,
        "times": {key: value for key, value in (times or {}).items() if value is not None},
        "state_history": history or []}}
    return relation, entity


def add_receipt(state: dict, operation_id: str, transaction_id: str, version_id: str, posting_set_id: str, asset_posting: str, expense_posting: str, arrived: str, confirmed: str, relation_id: str, confirmation_id: str, reconciliation_id: str, *, candidate_id=None) -> str:
    state["transactions"].append({"id": transaction_id, "type": "refund_receipt", "current_version_id": version_id})
    state["transaction_versions"].append({"id": version_id, "transaction_id": transaction_id, "version_number": 1, "posting_set_id": posting_set_id,
        "occurred_at": arrived, "statistics_at": arrived, "effective_at": arrived, "created_at": confirmed, "confirmation_id": confirmation_id})
    state["posting_sets"].append({"id": posting_set_id, "posting_ids": [asset_posting, expense_posting]})
    state["postings"].extend([
        {"id": asset_posting, "posting_set_id": posting_set_id, "account_id": "asset-wallet-b", "amount": "30.00", "currency": "CNY", "role": "destination_asset", "reconciliation_eligible": True},
        {"id": expense_posting, "posting_set_id": posting_set_id, "account_id": "expense-account-daily", "category_id": "expense-category-daily", "amount": "-30.00", "currency": "CNY", "role": "expense", "reconciliation_eligible": False},
    ])
    state["confirmations"].append({"id": confirmation_id, "type": "refund_relationship_confirmation", "operation_id": operation_id, "subject": {"kind": "relation", "id": relation_id}, "confirmed_at": confirmed, "payload": {"original_transaction_id": "transaction-original-rg07"}})
    state["posting_reconciliations"].append({"id": reconciliation_id, "posting_id": asset_posting, "status": "pending"})
    return confirmation_id


def add_status(state: dict, root_id: str, locator: str, relationship_id: str, value: str) -> None:
    state["derived_statuses"] = [item for item in state["derived_statuses"] if not (item["target_kind"] == "domain_entity" and item["target_id"] == relationship_id and item["status_name"] == "refund_status")]
    state["derived_statuses"].append({"id": mid(root_id, "derived_status", "$.derived.refund_status", relationship_id), "target_kind": "domain_entity", "target_id": relationship_id, "status_name": "refund_status", "value": value})


def refresh_statuses(state: dict) -> None:
    state["derived_statuses"] = [item for item in state["derived_statuses"] if item["status_name"] not in {"reconciliation_summary", "confirmation_status"}]
    reconciliations = {item["posting_id"]: item["status"] for item in state["posting_reconciliations"]}
    for transaction, _, postings in current_parts(state):
        eligible = [item for item in postings if item["reconciliation_eligible"]]
        if eligible:
            statuses = [reconciliations[item["id"]] for item in eligible]
            value = "matched" if all(item == "matched" for item in statuses) else "pending" if all(item == "pending" for item in statuses) else "partial"
            state["derived_statuses"].append({"id": mid(state["root_id"], "derived_status", "$.derived.reconciliation_summary", transaction["id"]), "target_kind": "transaction", "target_id": transaction["id"], "status_name": "reconciliation_summary", "value": value})
    for candidate in state["candidates"]:
        state["derived_statuses"].append({"id": mid(state["root_id"], "derived_status", "$.derived.confirmation_status", candidate["id"]), "target_kind": "candidate", "target_id": candidate["id"], "status_name": "confirmation_status", "value": candidate["status_history"][-1]["status"]})


def build_rg07_expected() -> dict:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    roots, states, operations = [], [], []

    def root(purpose: str, locator: str, discriminator: str):
        root_id = deterministic_v2_root_id("RG-07", locator, discriminator)
        initial = empty_state(v1, root_id, locator, "initial", None)
        item = {"id": root_id, "purpose": purpose, "initial_state_id": initial["id"], "operation_ids": []}
        roots.append(item); states.append(initial)
        return item, initial

    def root_with_original(purpose: str, locator: str, discriminator: str):
        item, initial = root(purpose, locator, discriminator)
        operation_id = mid(item["id"], "operation", "$.original", v1["original"]["request_id"])
        add_original(v1, initial, operation_id, item["id"], confirmation=False)
        return item, initial

    def replay(root_item: dict, current: dict, sequence: int, accepted: dict, locator: str, occurrence: str) -> dict:
        operation_id = mid(root_item["id"], "operation", locator, occurrence)
        result = clone(current, operation_id, locator, occurrence)
        operation = op(
            root_item["id"], sequence, operation_id, accepted["action_type"],
            accepted["operation_class"], current, result,
            input_value=deepcopy(accepted["input"]),
            outcome={"status": "no_change", "reason_code": "idempotent_replay"},
            returned=deepcopy(accepted["returned_ids"]),
        )
        operations.append(operation); root_item["operation_ids"].append(operation_id); states.append(result)
        return result

    # Original expense and its independently owned payment evidence, followed by exact replays.
    original_root, current = root("rg07_original_expense_lifecycle", "$.original", "original")
    oid = mid(original_root["id"], "operation", "$.original", v1["original"]["request_id"])
    result = clone(current, oid, "$.original", v1["original"]["request_id"])
    confirmation_id = add_original(v1, result, oid, original_root["id"])
    original_input = {
        "request_id": v1["original"]["request_id"], "amount": "120.00", "currency": "CNY",
        "category_id": v1["original"]["category_id"], "payment_account_id": v1["original"]["payment_account_id"],
        "occurred_at": v1["original"]["transaction"]["occurred_at"], "note": "", "explicit_confirmation": True,
    }
    original_operation = op(original_root["id"], 1, oid, "manual_expense", "creation", current, result,
        input_value=original_input, returned=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "transaction-original-rg07"}])
    operations.append(original_operation); original_root["operation_ids"].append(oid); states.append(result); current = result
    source_record = v1["original"]["provenance"]["source_records"][0]
    evidence_record = v1["original"]["provenance"]["evidence"][0]
    oid = mid(original_root["id"], "operation", "$.original.provenance", source_record["id"])
    result = clone(current, oid, "$.original.provenance", source_record["id"]); add_original_evidence(v1, result)
    evidence_input = {
        "source_id": source_record["id"], "evidence_id": evidence_record["id"],
        "payment_asset_posting_id": "posting-original-asset-rg07", "amount": source_record["amount"],
        "currency": source_record["currency"], "observed_at": source_record["observed_at"],
        "booking_at": source_record["booking_at"], "value_at": source_record["value_at"],
        "immutable_payload_hash": source_record["immutable_payload_hash"],
    }
    evidence_operation = op(original_root["id"], 2, oid, "attach_original_payment_evidence", "reconciliation", current, result,
        input_value=evidence_input, returned=[{"kind": "source", "id": source_record["id"]}, {"kind": "evidence", "id": evidence_record["id"]}, {"kind": "evidence_link", "id": "evidence-link-rg07-original-debit"}])
    operations.append(evidence_operation); original_root["operation_ids"].append(oid); states.append(result); current = result
    current = replay(original_root, current, 3, original_operation, "$.idempotency.retried_inputs[*]", v1["original"]["request_id"])
    replay(original_root, current, 4, evidence_operation, "$.idempotency.retried_inputs[*]", source_record["id"])

    # Manual path: status, merchant evidence, explicit arrival, posting evidence, and dual-role evidence.
    manual_root, current = root_with_original("rg07_manual_refund_lifecycle", "$.manual", "manual")
    request = v1["refund_request"]["input"]
    oid = mid(manual_root["id"], "operation", "$.refund_request", request["request_id"])
    result = clone(current, oid, "$.refund_request", request["request_id"])
    relation_id = v1["refund_request"]["expected"]["relation"]["id"]
    rel, ent = relationship(manual_root["id"], "$.refund_request.expected.relation", relation_id, "transaction-original-rg07", times=v1["refund_request"]["expected"]["relation"]["times"], history=[{**item, "sequence": index} for index, item in enumerate(v1["refund_request"]["expected"]["relation"]["state_history"], 1)])
    result["relations"].append(rel); result["domain_entities"].append(ent); add_status(result, manual_root["id"], "$.refund_request.expected.relation.state", ent["id"], "processing")
    operations.append(op(manual_root["id"], 1, oid, "record_refund_request_status", "status_transition", current, result, input_value=request, returned=[{"kind": "relation", "id": relation_id}, {"kind": "domain_entity", "id": ent["id"]}]))
    manual_root["operation_ids"].append(oid); states.append(result); current = result
    notice = v1["merchant_notice"]["source"]
    oid = mid(manual_root["id"], "operation", "$.merchant_notice", notice["id"]); result = clone(current, oid, "$.merchant_notice", notice["id"])
    source(result, notice["id"], "evidence-rg07-merchant-notice", "merchant_refund_notice", {**notice, "kind": "merchant_refund_notice"})
    result["sources"][-1]["payload"].pop("id")
    result["evidence_links"].append({"id": "evidence-link-rg07-merchant-notice", "evidence_id": "evidence-rg07-merchant-notice", "target_kind": "relation", "target_id": relation_id, "role": "refund_relationship"})
    notice_input = {"source_id": notice["id"], "refund_relation_id": relation_id, "observed_at": notice["observed_at"], "reported_state": notice["reported_state"], "proves_arrival": notice["proves_arrival"]}
    operations.append(op(manual_root["id"], 2, oid, "ingest_refund_status_source", "status_transition", current, result, input_value=notice_input, returned=[{"kind": "source", "id": notice["id"]}, {"kind": "evidence", "id": "evidence-rg07-merchant-notice"}, {"kind": "evidence_link", "id": "evidence-link-rg07-merchant-notice"}]))
    manual_root["operation_ids"].append(oid); states.append(result); current = result
    manual = v1["manual_receipt"]["request"]; expected = v1["manual_receipt"]["expected"]
    oid = mid(manual_root["id"], "operation", "$.manual_receipt", manual["request_id"]); result = clone(current, oid, "$.manual_receipt", manual["request_id"])
    confirmation_id = "confirmation-link-rg07-manual-relationship"
    receipt_reconciliation_id = mid(manual_root["id"], "posting_reconciliation", "$.manual_receipt.expected.reconciliation", "posting-refund-asset-rg07-manual")
    add_receipt(result, oid, "transaction-refund-rg07-manual", "version-refund-rg07-manual-v1", "posting-set-refund-rg07-manual", "posting-refund-asset-rg07-manual", "posting-refund-expense-rg07-manual", manual["arrived_at"], manual["confirmed_at"], relation_id, confirmation_id, receipt_reconciliation_id)
    rel, ent = relationship(manual_root["id"], "$.manual_receipt.expected.relation", relation_id, "transaction-original-rg07", receipt_id="transaction-refund-rg07-manual", received="30.00", destination="asset-wallet-b", times=expected["relation"]["times"], history=[{**item, "sequence": index} for index, item in enumerate(expected["relation"]["state_history"], 1)])
    result["relations"][-1] = rel; result["domain_entities"][-1] = ent; add_status(result, manual_root["id"], "$.manual_receipt.expected.relation.state", ent["id"], "received"); refresh(result); refresh_statuses(result)
    operations.append(op(manual_root["id"], 3, oid, "confirm_manual_refund_receipt", "creation", current, result, input_value=manual, returned=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "transaction-refund-rg07-manual"}]))
    manual_root["operation_ids"].append(oid); states.append(result); current = result
    credit = {
        **v1["bank_credit_evidence"]["input"],
        "refund_relation_id": relation_id,
        "destination_asset_posting_id": "posting-refund-asset-rg07-manual",
    }
    oid = mid(manual_root["id"], "operation", "$.bank_credit_evidence", credit["source_id"]); result = clone(current, oid, "$.bank_credit_evidence", credit["source_id"])
    source(result, credit["source_id"], credit["evidence_id"], "wallet_credit", {"observed_at": credit["booking_at"], "account_id": credit["account_id"], "amount": credit["amount"], "currency": credit["currency"], "booking_at": credit["booking_at"], "value_at": credit["value_at"], "original_source_payload_hash": "sha256:rg07-bank-credit"})
    result["evidence_links"].append({"id": "evidence-link-rg07-bank-credit", "evidence_id": credit["evidence_id"], "target_kind": "posting", "target_id": "posting-refund-asset-rg07-manual", "role": "destination_asset_posting"})
    result["posting_reconciliations"][-1]["status"] = "matched"; refresh_statuses(result)
    operations.append(op(manual_root["id"], 4, oid, "attach_refund_destination_evidence", "reconciliation", current, result, input_value=credit, returned=[{"kind": "source", "id": credit["source_id"]}, {"kind": "evidence", "id": credit["evidence_id"]}, {"kind": "evidence_link", "id": "evidence-link-rg07-bank-credit"}]))
    manual_root["operation_ids"].append(oid); states.append(result); current = result
    dual = {
        **v1["dual_role_source"]["input"],
        "refund_relation_id": relation_id,
        "destination_asset_posting_id": "posting-refund-asset-rg07-manual",
        "observed_at": manual["confirmed_at"],
    }
    oid = mid(manual_root["id"], "operation", "$.dual_role_source", dual["source_id"]); result = clone(current, oid, "$.dual_role_source", dual["source_id"])
    source(result, dual["source_id"], dual["evidence_id"], "combined_refund_statement", {"observed_at": manual["confirmed_at"]})
    result["evidence_links"].extend([
        {"id": "evidence-link-rg07-dual-relation", "evidence_id": dual["evidence_id"], "target_kind": "relation", "target_id": relation_id, "role": "refund_relationship"},
        {"id": "evidence-link-rg07-dual-posting", "evidence_id": dual["evidence_id"], "target_kind": "posting", "target_id": "posting-refund-asset-rg07-manual", "role": "destination_asset_posting"},
    ])
    operations.append(op(manual_root["id"], 5, oid, "attach_refund_dual_role_evidence", "reconciliation", current, result, input_value=dual, returned=[{"kind": "source", "id": dual["source_id"]}, {"kind": "evidence", "id": dual["evidence_id"]}, {"kind": "evidence_link", "id": "evidence-link-rg07-dual-relation"}, {"kind": "evidence_link", "id": "evidence-link-rg07-dual-posting"}]))
    manual_root["operation_ids"].append(oid); states.append(result); current = result
    manual_accepts = [operation for operation in operations if operation["root_id"] == manual_root["id"]]
    retry_inputs = [
        v1["refund_request"]["input"]["request_id"],
        v1["merchant_notice"]["source"]["id"],
        v1["manual_receipt"]["request"]["request_id"],
        v1["bank_credit_evidence"]["input"]["source_id"],
        v1["dual_role_source"]["input"]["source_id"],
    ]
    for index, (accepted, retry_id) in enumerate(zip(manual_accepts, retry_inputs, strict=True), 6):
        current = replay(manual_root, current, index, accepted, "$.idempotency.retried_inputs[*]", retry_id)

    # Import root: candidate intake, complete confirmation, and mirror lineage.
    import_root, current = root_with_original("rg07_import_refund_lifecycle", "$.import_path", "import")
    candidate_source = v1["import_path"]["candidate"]["source"]
    oid = mid(import_root["id"], "operation", "$.import_path.candidate", candidate_source["id"]); result = clone(current, oid, "$.import_path.candidate", candidate_source["id"])
    source(result, candidate_source["id"], "evidence-rg07-import-credit", "wallet_credit", {"observed_at": candidate_source["source_observed_at"], **candidate_source})
    result["sources"][-1]["payload"].pop("id")
    candidate = v1["import_path"]["candidate"]["expected"]["candidate"]
    result["candidates"].append({"id": candidate["id"], "type": "refund_credit", "source_ids": candidate["source_ids"], "confidence": candidate["confidence"], "payload": {key: candidate[key] for key in ("proposed_amount", "currency", "proposed_original_transaction_id", "proposed_category_id", "proposed_destination_account_id", "proposed_arrived_at", "requires_confirmation", "rule_version") } | {"original_source_payload_hash": candidate_source["original_source_payload_hash"]}, "status_history": [{**item, "sequence": index} for index, item in enumerate(candidate["status_history"], 1)]})
    refresh_statuses(result)
    candidate_input = {"source_id": candidate_source["id"], "source_record_id": candidate_source["source_record_id"], "account_id": candidate_source["account_id"], "amount": candidate_source["amount"], "currency": candidate_source["currency"], "processor_reported_at": candidate_source["processor_reported_at"], "source_observed_at": candidate_source["source_observed_at"], "booking_at": candidate_source["booking_at"], "value_at": candidate_source["value_at"], "original_source_payload_hash": candidate_source["original_source_payload_hash"]}
    operations.append(op(import_root["id"], 1, oid, "ingest_refund_credit_source", "creation", current, result, input_value=candidate_input, returned=[{"kind": "source", "id": candidate_source["id"]}, {"kind": "evidence", "id": "evidence-rg07-import-credit"}, {"kind": "candidate", "id": candidate["id"]}]))
    import_root["operation_ids"].append(oid); states.append(result); current = result
    request = v1["import_path"]["confirmation"]["request"]
    oid = mid(import_root["id"], "operation", "$.import_path.confirmation", request["request_id"]); result = clone(current, oid, "$.import_path.confirmation", request["request_id"])
    confirmation_id = "confirmation-link-rg07-import-relationship"
    receipt_reconciliation_id = mid(import_root["id"], "posting_reconciliation", "$.import_path.confirmation.expected.reconciliation", "posting-refund-asset-rg07-import")
    add_receipt(result, oid, "transaction-refund-rg07-import", "version-refund-rg07-import-v1", "posting-set-refund-rg07-import", "posting-refund-asset-rg07-import", "posting-refund-expense-rg07-import", request["arrived_at"], request["confirmed_at"], "refund-relation-rg07-import", confirmation_id, receipt_reconciliation_id)
    result["candidates"][0]["status_history"].append({"id": "history-rg07-import-confirmed", "sequence": 2, "status": "confirmed", "occurred_at": request["confirmed_at"], "formal_effect_count": 1})
    rel, ent = relationship(import_root["id"], "$.import_path.confirmation.expected.relation", "refund-relation-rg07-import", "transaction-original-rg07", receipt_id="transaction-refund-rg07-import", received="30.00", destination="asset-wallet-b", times=v1["import_path"]["confirmation"]["expected"]["relation"]["times"], history=[{"id": "history-rg07-import-relation-received", "sequence": 1, "state": "received", "occurred_at": request["confirmed_at"], "transaction_id": "transaction-refund-rg07-import", "formal_effect_count": 1}])
    result["relations"].append(rel); result["domain_entities"].append(ent); result["evidence_links"].append({"id": "evidence-link-rg07-import-posting", "evidence_id": "evidence-rg07-import-credit", "target_kind": "posting", "target_id": "posting-refund-asset-rg07-import", "role": "destination_asset_posting"}); result["posting_reconciliations"][-1]["status"] = "matched"; add_status(result, import_root["id"], "$.import_path.confirmation.expected.relation.state", ent["id"], "received"); refresh(result); refresh_statuses(result)
    operations.append(op(import_root["id"], 2, oid, "confirm_imported_refund", "creation", current, result, input_value=request, returned=[{"kind": "candidate", "id": candidate["id"]}, {"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "transaction-refund-rg07-import"}, {"kind": "relation", "id": "refund-relation-rg07-import"}, {"kind": "evidence_link", "id": "evidence-link-rg07-import-posting"}]))
    import_root["operation_ids"].append(oid); states.append(result); current = result
    mirror = v1["import_path"]["mirror_evidence"]["input"]
    oid = mid(import_root["id"], "operation", "$.import_path.mirror_evidence", mirror["request_id"]); result = clone(current, oid, "$.import_path.mirror_evidence", mirror["request_id"])
    source(result, mirror["source_id"], mirror["evidence_id"], "wallet_credit_mirror", {"observed_at": mirror["observed_at"], "account_id": "asset-wallet-b", "amount": mirror["amount"], "currency": mirror["currency"], "booking_at": mirror["observed_at"], "value_at": mirror["observed_at"], "original_source_payload_hash": "sha256:rg07-synthetic-credit", "mirror_of_source_id": candidate_source["id"]})
    result["evidence"][-1]["payload"].update({"mirror_of_evidence_id": "evidence-rg07-import-credit", "merged_into_evidence_link_id": "evidence-link-rg07-import-posting"})
    result["evidence_links"].append({"id": "evidence-link-rg07-import-mirror", "evidence_id": mirror["evidence_id"], "target_kind": "posting", "target_id": "posting-refund-asset-rg07-import", "role": "destination_asset_posting"})
    operations.append(op(import_root["id"], 3, oid, "merge_refund_mirror_evidence", "reconciliation", current, result, input_value=mirror, returned=[{"kind": "source", "id": mirror["source_id"]}, {"kind": "evidence", "id": mirror["evidence_id"]}, {"kind": "evidence_link", "id": "evidence-link-rg07-import-mirror"}]))
    import_root["operation_ids"].append(oid); states.append(result); current = result
    import_accepts = [operation for operation in operations if operation["root_id"] == import_root["id"]]
    import_retry_ids = [candidate_source["id"], request["request_id"], mirror["request_id"]]
    for index, (accepted, retry_id) in enumerate(zip(import_accepts, import_retry_ids, strict=True), 4):
        current = replay(import_root, current, index, accepted, "$.idempotency.retried_inputs[*]", retry_id)

    # Cap root confirms 30 then exactly the remaining 90; the over-cap attempt is atomic.
    cap_root, current = root_with_original("rg07_refund_cap", "$.refund_cap", "cap")
    existing = v1["refund_cap"]["existing_refund"]
    oid = existing["operation_context"]["operation_id"]; result = clone(current, oid, "$.refund_cap.existing_refund", oid)
    first = existing["transaction"]
    confirmation_id = "confirmation-link-rg07-cap-first-relationship"
    reconciliation_id = mid(cap_root["id"], "posting_reconciliation", "$.refund_cap.existing_refund", first["postings"][0]["id"])
    add_receipt(result, oid, first["id"], first["current_version_id"], first["posting_set_id"], first["postings"][0]["id"], first["postings"][1]["id"], first["occurred_at"], "2026-02-02T18:00:00+08:00", "refund-relation-rg07-cap-first", confirmation_id, reconciliation_id)
    rel, ent = relationship(cap_root["id"], "$.refund_cap.existing_refund.relation", "refund-relation-rg07-cap-first", "transaction-original-rg07", receipt_id=first["id"], received="30.00", destination="asset-wallet-b", times=existing["relation"]["times"], history=[{**item, "sequence": index} for index, item in enumerate(existing["relation"]["state_history"], 1)])
    result["relations"].append(rel); result["domain_entities"].append(ent); add_status(result, cap_root["id"], "$.refund_cap.existing_refund.relation.state", ent["id"], "received"); refresh(result); refresh_statuses(result)
    first_input = {"request_id": oid, "original_transaction_id": "transaction-original-rg07", "amount": "30.00", "currency": "CNY", "category_id": "expense-category-daily", "destination_account_id": "asset-wallet-b", "arrived_at": first["occurred_at"], "confirmed_at": "2026-02-02T18:00:00+08:00", "arrival_confirmed": True}
    operations.append(op(cap_root["id"], 1, oid, "confirm_refund_receipt", "creation", current, result, input_value=first_input, returned=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": first["id"]}, {"kind": "relation", "id": rel["id"]}]))
    cap_root["operation_ids"].append(oid); states.append(result); current = result
    maximum = v1["refund_cap"]["maximum_valid_allocation"]
    oid = maximum["operation_context"]["operation_id"]; result = clone(current, oid, "$.refund_cap.maximum_valid_allocation", oid)
    tx = maximum["expected"]["transaction"]
    confirmation_id = "confirmation-link-rg07-cap-maximum-relationship"
    reconciliation_id = mid(cap_root["id"], "posting_reconciliation", "$.refund_cap.maximum_valid_allocation", tx["postings"][0]["id"])
    add_receipt(result, oid, tx["id"], tx["current_version_id"], tx["posting_set_id"], tx["postings"][0]["id"], tx["postings"][1]["id"], tx["occurred_at"], "2026-02-10T10:05:00+08:00", "refund-relation-rg07-cap-maximum", confirmation_id, reconciliation_id)
    rel, ent = relationship(cap_root["id"], "$.refund_cap.maximum_valid_allocation.expected.relation", "refund-relation-rg07-cap-maximum", "transaction-original-rg07", receipt_id=tx["id"], requested="90.00", received="90.00", destination="asset-wallet-b", times=maximum["expected"]["relation"]["times"], history=[{**item, "sequence": index} for index, item in enumerate(maximum["expected"]["relation"]["state_history"], 1)])
    result["relations"].append(rel); result["domain_entities"].append(ent); add_status(result, cap_root["id"], "$.refund_cap.maximum_valid_allocation.expected.relation.state", ent["id"], "received")
    result["postings"][-2]["amount"] = "90.00"; result["postings"][-1]["amount"] = "-90.00"; refresh(result); refresh_statuses(result)
    maximum_input = {"request_id": oid, "original_transaction_id": "transaction-original-rg07", "amount": "90.00", "currency": "CNY", "category_id": "expense-category-daily", "destination_account_id": "asset-wallet-b", "arrived_at": tx["occurred_at"], "confirmed_at": "2026-02-10T10:05:00+08:00", "arrival_confirmed": True}
    operations.append(op(cap_root["id"], 2, oid, "confirm_refund_receipt", "creation", current, result, input_value=maximum_input, returned=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": tx["id"]}, {"kind": "relation", "id": rel["id"]}]))
    cap_root["operation_ids"].append(oid); states.append(result); current = result
    over = v1["refund_cap"]["over_cap_attempt"]
    oid = over["operation_context"]["operation_id"]; result = clone(current, oid, "$.refund_cap.over_cap_attempt", oid)
    attempted = {"candidate_id": "candidate-over-cap-rg07", "requested_allocation": "100.00", "available_allocation": "90.00"}
    operations.append(op(cap_root["id"], 3, oid, "allocate_refund_receipt", "rejection", current, result, attempted=attempted, outcome={"status": "rejected", "reason_code": "refund_amount_exceeds_remaining_refundable", "field_path": "$.attempted_input.requested_allocation"}))
    cap_root["operation_ids"].append(oid); states.append(result); current = result
    cap_accepts = [operation for operation in operations if operation["root_id"] == cap_root["id"] and operation["outcome"]["status"] == "accepted"]
    current = replay(cap_root, current, 4, cap_accepts[0], "$.idempotency.retried_inputs[*]", v1["refund_cap"]["existing_refund"]["operation_context"]["operation_id"])
    replay(cap_root, current, 5, cap_accepts[1], "$.idempotency.retried_inputs[*]", v1["refund_cap"]["maximum_valid_allocation"]["operation_context"]["operation_id"])

    # The fourteen frozen validation failures and one unconfirmed manual arrival are isolated roots.
    rejections = [("manual_unconfirmed_arrival", v1["manual_unconfirmed_arrival"], "confirm_manual_refund_receipt", "arrival_confirmation_required", "arrival_confirmed")]
    rejections += [(item["id"], item, "validate_refund_receipt", item["expected"]["reason"], item["expected"]["field"]) for item in v1["invalid_inputs"]]
    for name, item, action, reason, field in rejections:
        rejection_root, initial = root_with_original(f"rg07_rejected_{name.replace('-', '_')}", "$.rejections[*]", name)
        operation_id = item["operation_context"]["operation_id"]
        result = clone(initial, operation_id, "$.rejections[*]", name)
        if action == "confirm_manual_refund_receipt":
            attempted = item["input"]
        else:
            original = item["input"]
            attempted = {"attempt_id": operation_id, "amount": original.get("amount", "30.00"), "currency": original.get("currency", "CNY"), "original_transaction_id": original.get("original_transaction_id"), "category_id": original.get("category_id"), "destination_account_id": original.get("destination_account_id", "asset-wallet-b"), "remaining_refundable": "120.00", "destination_confirmed": True}
            if name == "missing-original-link": attempted["original_transaction_id"] = None
            if name == "missing-destination-confirmation": attempted["destination_confirmed"] = False
            if name == "over-remaining-refundable": attempted["remaining_refundable"] = "90.00"
        operations.append(op(rejection_root["id"], 1, operation_id, action, "rejection", initial, result, attempted=attempted, outcome={"status": "rejected", "reason_code": reason, "field_path": f"$.attempted_input.{field}"}))
        rejection_root["operation_ids"].append(operation_id); states.append(result)
        if name == "manual_unconfirmed_arrival":
            retry_id = mid(rejection_root["id"], "operation", "$.idempotency.retried_inputs[*]", item["input"]["request_id"])
            retry_result = clone(result, retry_id, "$.idempotency.retried_inputs[*]", item["input"]["request_id"])
            operations.append(op(rejection_root["id"], 2, retry_id, action, "rejection", result, retry_result,
                attempted=deepcopy(attempted), outcome={"status": "rejected", "reason_code": reason, "field_path": f"$.attempted_input.{field}"}))
            rejection_root["operation_ids"].append(retry_id); states.append(retry_result)

    # Imported confirmation omissions are separate atomic branches from an accepted pending intake.
    import_reason_fields = {
        "original_transaction_id": ("original_transaction_confirmation_required", "original_transaction_id"),
        "category_id_and_allocation": ("category_allocation_confirmation_required", "category_id"),
        "destination_account_id": ("destination_confirmation_required", "destination_account_id"),
        "arrival": ("arrival_confirmation_required", "arrival_confirmed"),
    }
    complete_request = v1["import_path"]["confirmation"]["request"]
    for item in v1["import_path"]["incomplete_confirmations"]:
        rejection_root, initial = root_with_original(
            f"rg07_rejected_{item['id'].replace('-', '_')}",
            "$.import_path.incomplete_confirmations[*]", item["id"],
        )
        intake_id = mid(rejection_root["id"], "operation", "$.import_path.candidate", candidate_source["id"])
        pending = clone(initial, intake_id, "$.import_path.candidate", candidate_source["id"])
        source(pending, candidate_source["id"], "evidence-rg07-import-credit", "wallet_credit", {"observed_at": candidate_source["source_observed_at"], **candidate_source})
        pending["sources"][-1]["payload"].pop("id")
        pending["candidates"].append({"id": candidate["id"], "type": "refund_credit", "source_ids": candidate["source_ids"], "confidence": candidate["confidence"], "payload": {key: candidate[key] for key in ("proposed_amount", "currency", "proposed_original_transaction_id", "proposed_category_id", "proposed_destination_account_id", "proposed_arrived_at", "requires_confirmation", "rule_version")} | {"original_source_payload_hash": candidate_source["original_source_payload_hash"]}, "status_history": [{**history, "sequence": index} for index, history in enumerate(candidate["status_history"], 1)]})
        refresh_statuses(pending)
        intake_operation = op(rejection_root["id"], 1, intake_id, "ingest_refund_credit_source", "creation", initial, pending,
            input_value=candidate_input, returned=[{"kind": "source", "id": candidate_source["id"]}, {"kind": "evidence", "id": "evidence-rg07-import-credit"}, {"kind": "candidate", "id": candidate["id"]}])
        operations.append(intake_operation); rejection_root["operation_ids"].append(intake_id); states.append(pending)
        operation_id = item["operation_context"]["operation_id"]
        result = clone(pending, operation_id, "$.import_path.incomplete_confirmations[*]", item["id"])
        attempted = {"attempt_id": operation_id, **complete_request}
        attempted.pop("request_id")
        missing = item["missing_field"]
        if missing == "category_id_and_allocation":
            attempted.pop("category_id"); attempted.pop("allocated_amount")
        elif missing == "arrival":
            attempted.pop("arrived_at"); attempted.pop("confirmed_at"); attempted["arrival_confirmed"] = False
        else:
            attempted.pop(missing)
        reason, field = import_reason_fields[missing]
        operations.append(op(rejection_root["id"], 2, operation_id, "confirm_imported_refund", "rejection", pending, result,
            attempted=attempted, outcome={"status": "rejected", "reason_code": reason, "field_path": f"$.attempted_input.{field}"}))
        rejection_root["operation_ids"].append(operation_id); states.append(result)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: item["id"])
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {"contract": "unifiedledger.golden-case", "contract_version": "2.0.0", "case": {"id": "RG-07", "level": "core_required", "rule_version": 1, "approval_status": "approved", "ledger_id": v1["case"]["ledger_id"], "timezone": v1["case"]["timezone"], "currencies": [{"code": "CNY", "precision": 2}]}, "roots": roots, "states": states, "operations": operations}


def write_rg07_expected() -> None:
    EXPECTED_PATH.write_text(json.dumps(build_rg07_expected(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class RG07GoldenV2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.expected = build_rg07_expected()

    def test_schema_and_semantic_validation(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validate_golden_case_v2(self.expected)

    def test_checked_in_draft_matches_deterministic_generator(self):
        self.assertEqual(json.loads(EXPECTED_PATH.read_text(encoding="utf-8")), self.expected)

    def test_exact_root_and_operation_inventory(self):
        self.assertEqual(self.expected["case"]["approval_status"], "approved")
        self.assertEqual(len(self.expected["roots"]), 23)
        self.assertEqual(len(self.expected["states"]), 72)
        self.assertEqual(len(self.expected["operations"]), 49)
        self.assertEqual(sum(item["operation_class"] == "rejection" for item in self.expected["operations"]), 21)
        self.assertEqual(sum(item["outcome"]["status"] == "no_change" for item in self.expected["operations"]), 12)

    def test_replays_cover_all_frozen_input_identities(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        replay_inputs = [
            operation["input"].get("request_id", operation["input"].get("source_id"))
            for operation in self.expected["operations"]
            if operation["outcome"]["status"] == "no_change"
        ]
        frozen = set(v1["idempotency"]["retried_inputs"])
        mirror_replay = next(
            operation
            for operation in self.expected["operations"]
            if operation.get("input", {}).get("request_id") == "request-rg07-merge-import-mirror"
            and operation["outcome"]["status"] == "no_change"
        )
        mirror_returned_ids = {
            item["id"]
            for item in mirror_replay["returned_ids"]
            if item["kind"] == "source"
        }
        rejected_replays = [
            operation for operation in self.expected["operations"]
            if operation["outcome"]["status"] == "rejected"
            and operation["attempted_input"].get("request_id")
            == "request-rg07-manual-unconfirmed-arrival"
        ]
        proven_inputs = set(replay_inputs) | mirror_returned_ids | {
            operation["attempted_input"]["request_id"]
            for operation in rejected_replays
        }
        self.assertEqual(
            proven_inputs,
            frozen,
        )
        self.assertEqual(len(rejected_replays), 2)
        self.assertEqual(mirror_returned_ids, {"source-rg07-import-mirror"})

    def test_preserved_and_generated_id_ownership(self):
        preserved_confirmations = {
            item["id"] for item in json.loads(V1_PATH.read_text(encoding="utf-8"))["entity_registry"]["confirmation_links"]
        }
        actual_confirmations = {
            item["id"] for state in self.expected["states"] for item in state["confirmations"]
            if item["type"] == "refund_relationship_confirmation"
        }
        self.assertEqual(actual_confirmations, preserved_confirmations)
        generated = [
            item["id"] for state in self.expected["states"]
            for collection in ("domain_entities", "posting_reconciliations", "derived_statuses")
            for item in state[collection]
            if item["id"] not in preserved_confirmations
        ]
        self.assertTrue(all(len(value) == 36 and value.count("-") == 4 for value in generated))

    def test_rejections_are_complete_atomic_states(self):
        states = {item["id"]: item for item in self.expected["states"]}
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] == "rejected":
                baseline = {
                    key: value for key, value in states[operation["baseline_state_id"]].items()
                    if key not in {"id", "as_of_operation_id"}
                }
                result = {
                    key: value for key, value in states[operation["result_state_id"]].items()
                    if key not in {"id", "as_of_operation_id"}
                }
                self.assertEqual(baseline, result)
                self.assertTrue(all(not values for changes in operation["deltas"]["entity_changes"].values() for values in changes.values()))
                self.assertEqual(operation["deltas"]["value_changes"], {"balances": [], "reports": [], "derived_statuses": []})

    def test_relation_history_rejects_illegal_order(self):
        mutated = deepcopy(self.expected)
        for state in mutated["states"]:
            for entity in state["domain_entities"]:
                history = entity.get("payload", {}).get("state_history", [])
                if len(history) == 4:
                    history[3]["state"] = "processing"
                    with self.assertRaises(GoldenCaseError):
                        validate_golden_case_v2(mutated)
                    return
        self.fail("expected a complete RG-07 refund relationship history")


if __name__ == "__main__":
    unittest.main()
