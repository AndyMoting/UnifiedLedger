from copy import deepcopy
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID, uuid5
from zoneinfo import ZoneInfo

from jsonschema import Draft202012Validator
import golden_cases.v2 as golden_v2

from golden_cases import (
    GoldenCaseError,
    deterministic_v2_id,
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    load_golden_case,
    load_golden_case_v2,
    migration_semantic_key,
    validate_golden_case_v2,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
REQUIREMENTS_PATH = REPOSITORY_ROOT / "requirements-dev.txt"
SCHEMA_PATH = REPOSITORY_ROOT / "schemas" / "golden-case-v2.schema.json"
RG01_V2_PATH = REPOSITORY_ROOT / "docs" / "examples" / "golden-schema-v2" / "rg-01.json"
RG09_V2_PATH = REPOSITORY_ROOT / "docs" / "examples" / "golden-schema-v2" / "rg-09.json"
RG01_V1_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-01.json"
RG04_V1_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-04.json"
RG10_V1_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-10.json"
RG01_PATH_MAP = REPOSITORY_ROOT / "docs" / "migrations" / "golden-v2" / "rg-01-path-map.json"


def load_rg01() -> dict:
    return load_golden_case_v2(RG01_V2_PATH)


def load_rg09() -> dict:
    return load_golden_case_v2(RG09_V2_PATH)


def mixed_payment_relation_fixture() -> tuple[dict, dict]:
    case = load_rg01()
    state = deepcopy(case["states"][-1])
    liability = {
        "id": "liability-card-rg04",
        "name": "Card account",
        "kind": "liability",
        "currency": "CNY",
        "owned_by_user": True,
        "real_account": True,
        "reconciliation_eligible": True,
    }
    state["catalog"]["accounts"].append(liability)
    asset = next(item for item in state["postings"] if item["id"] == "posting-bank-rg01")
    credit = next(item for item in state["postings"] if item["id"] == "posting-expense-rg01")
    asset.update(amount="-70.00", role="mixed_expense_asset_funding")
    credit.update(account_id=liability["id"], amount="-50.00", role="mixed_expense_credit_funding")
    state["relations"] = [{
        "id": "relation-rg04-semantic",
        "type": "mixed_payment",
        "member_refs": [
            {"kind": "transaction", "id": "tx-expense-rg01"},
            {"kind": "posting", "id": asset["id"]},
            {"kind": "posting", "id": credit["id"]},
        ],
        "payload": {
            "system_managed": True,
            "display_name": "Mixed payment",
            "generic_order_lifecycle": False,
            "payment_composition_total": "120.00",
            "funding_components": [
                {"account_id": asset["account_id"], "funding_amount": "70.00", "currency": "CNY", "posting_id": asset["id"]},
                {"account_id": credit["account_id"], "funding_amount": "50.00", "currency": "CNY", "posting_id": credit["id"]},
            ],
        },
    }]
    indexes = golden_v2._state_indexes(state, "$.states[0]")
    current = {"tx-expense-rg01": (
        indexes["transactions"]["tx-expense-rg01"],
        indexes["transaction_versions"]["version-expense-rg01-v2"],
        [indexes["postings"][asset["id"]], indexes["postings"][credit["id"]]],
    )}
    return state, {"indexes": indexes, "current": current, "precisions": {"CNY": 2, "USD": 2}}


def rg04_posting_semantics_fixture(transaction_type: str) -> tuple[dict, dict]:
    case = load_rg01()
    state = deepcopy(case["states"][-1])
    liability = {
        "id": "liability-card-rg04-semantics",
        "name": "Card account",
        "kind": "liability",
        "currency": "CNY",
        "owned_by_user": True,
        "real_account": True,
        "reconciliation_eligible": True,
    }
    state["catalog"]["accounts"].append(liability)
    transaction = next(item for item in state["transactions"] if item["id"] == "tx-expense-rg01")
    transaction["type"] = transaction_type
    posting_set = next(item for item in state["posting_sets"] if item["id"] == "posting-set-expense-rg01")
    asset = next(item for item in state["postings"] if item["id"] == "posting-bank-rg01")
    other = next(item for item in state["postings"] if item["id"] == "posting-expense-rg01")
    state["relations"] = []
    if transaction_type == "expense":
        asset.update(amount="-70.00", role="mixed_expense_asset_funding", reconciliation_eligible=True)
        other.update(amount="120.00", category_id="expense-category-breakfast", role="expense", reconciliation_eligible=False)
        credit = {
            "id": "posting-credit-rg04-semantics",
            "posting_set_id": posting_set["id"],
            "account_id": liability["id"],
            "amount": "-50.00",
            "currency": "CNY",
            "role": "mixed_expense_credit_funding",
            "reconciliation_eligible": True,
        }
        state["postings"].append(credit)
        posting_set["posting_ids"].append(credit["id"])
    else:
        asset.update(amount="-50.00", role="credit_repayment_asset_outflow", reconciliation_eligible=True)
        other.update(
            account_id=liability["id"],
            amount="50.00",
            role="credit_repayment_liability_principal",
            reconciliation_eligible=True,
        )
    indexes = golden_v2._state_indexes(state, "$.states[0]")
    current = {
        transaction["id"]: (
            indexes["transactions"][transaction["id"]],
            indexes["transaction_versions"]["version-expense-rg01-v2"],
            [indexes["postings"][posting_id] for posting_id in posting_set["posting_ids"]],
        )
    }
    return state, {"indexes": indexes, "current": current, "precisions": {"CNY": 2, "USD": 2}}


def rg04_creation_case(action: str) -> tuple[dict, dict, dict]:
    case = load_rg01()
    case["case"]["id"] = "RG-04"
    case["case"]["currencies"] = [{"code": "CNY", "precision": 2}]
    case["roots"] = [{"id": "root-rg04-test", "purpose": "synthetic RG04 action", "initial_state_id": "state-rg04-opening", "operation_ids": ["operation-rg04-test"]}]
    baseline = deepcopy(case["states"][0])
    baseline["id"] = "state-rg04-opening"
    baseline["root_id"] = "root-rg04-test"
    baseline["as_of_operation_id"] = None
    baseline["catalog"]["accounts"].append({
        "id": "liability-credit-b", "name": "Credit", "kind": "liability", "currency": "CNY",
        "owned_by_user": True, "real_account": True, "reconciliation_eligible": True,
    })
    baseline["catalog"]["categories"][0]["id"] = "expense-category-living"
    baseline["catalog"]["categories"][1].update(id="expense-category-daily", parent_id="expense-category-living")
    baseline["catalog"]["accounts"][2]["id"] = "expense-account-daily"
    baseline["catalog"]["categories"][1]["posting_account_id"] = "expense-account-daily"
    next(item for item in baseline["balances"] if item["account_id"] == "expense-account-breakfast")["account_id"] = "expense-account-daily"
    baseline["balances"].append({"account_id": "liability-credit-b", "currency": "CNY", "amount": "0.00"})

    operation_id = "operation-rg04-test"
    occurred_at = "2026-01-20T10:00:00+08:00"
    if action == "manual_mixed_expense":
        input_value = {
            "request_id": "request-rg04-test-expense", "asset_account_id": "asset-bank-a",
            "liability_account_id": "liability-credit-b", "asset_funding_amount": "70.00",
            "liability_funding_amount": "50.00", "total_amount": "120.00", "currency": "CNY",
            "category_id": "expense-category-daily", "occurred_at": occurred_at,
            "settlement_explanation": {"original_amount": "135.00", "discount_amount": "15.00", "settled_amount": "120.00"},
            "explicit_confirmation": True,
        }
        transaction_id, version_id, posting_set_id = "tx-rg04-expense", "version-rg04-expense-v1", "posting-set-rg04-expense"
        postings = [
            {"id": "posting-rg04-expense", "posting_set_id": posting_set_id, "account_id": "expense-account-daily", "category_id": "expense-category-daily", "amount": "120.00", "currency": "CNY", "role": "expense", "reconciliation_eligible": False},
            {"id": "posting-rg04-asset", "posting_set_id": posting_set_id, "account_id": "asset-bank-a", "amount": "-70.00", "currency": "CNY", "role": "mixed_expense_asset_funding", "reconciliation_eligible": True},
            {"id": "posting-rg04-credit", "posting_set_id": posting_set_id, "account_id": "liability-credit-b", "amount": "-50.00", "currency": "CNY", "role": "mixed_expense_credit_funding", "reconciliation_eligible": True},
        ]
        balances = {"asset-bank-a": "930.00", "expense-account-daily": "120.00", "liability-credit-b": "-50.00"}
        report_amounts = {"cash_outflow": "70.00", "consumption": "120.00", "income": "0.00", "net_worth_change": "-120.00"}
        tx_type = "expense"
    else:
        input_value = {
            "request_id": "request-rg04-test-repayment", "asset_account_id": "asset-bank-a",
            "liability_account_id": "liability-credit-b", "principal_amount": "50.00", "currency": "CNY",
            "occurred_at": occurred_at, "explicit_confirmation": True,
        }
        transaction_id, version_id, posting_set_id = "tx-rg04-repayment", "version-rg04-repayment-v1", "posting-set-rg04-repayment"
        postings = [
            {"id": "posting-rg04-repayment-asset", "posting_set_id": posting_set_id, "account_id": "asset-bank-a", "amount": "-50.00", "currency": "CNY", "role": "credit_repayment_asset_outflow", "reconciliation_eligible": True},
            {"id": "posting-rg04-repayment-credit", "posting_set_id": posting_set_id, "account_id": "liability-credit-b", "amount": "50.00", "currency": "CNY", "role": "credit_repayment_liability_principal", "reconciliation_eligible": True},
        ]
        balances = {"asset-bank-a": "950.00", "liability-credit-b": "50.00"}
        report_amounts = {"cash_outflow": "50.00", "consumption": "0.00", "income": "0.00", "net_worth_change": "0.00"}
        tx_type = "credit_repayment"

    result = deepcopy(baseline)
    result.update(id="state-rg04-result", root_id="root-rg04-test", as_of_operation_id=operation_id)
    result["transactions"].append({"id": transaction_id, "type": tx_type, "current_version_id": version_id})
    result["transaction_versions"].append({
        "id": version_id, "transaction_id": transaction_id, "version_number": 1,
        "posting_set_id": posting_set_id, "occurred_at": occurred_at, "statistics_at": occurred_at,
        "effective_at": occurred_at, "confirmation_id": "confirmation-rg04-test",
    })
    result["posting_sets"].append({"id": posting_set_id, "posting_ids": [item["id"] for item in postings]})
    result["postings"].extend(postings)
    result["confirmations"].append({"id": "confirmation-rg04-test", "type": "explicit_manual_save", "operation_id": operation_id, "subject": {"kind": "operation", "id": operation_id}, "payload": {}})
    result["posting_reconciliations"].extend([
        {"id": f"reconciliation-rg04-{index}", "posting_id": item["id"], "status": "pending"}
        for index, item in enumerate(postings, 1) if item["reconciliation_eligible"]
    ])
    for balance in result["balances"]:
        if balance["account_id"] in balances:
            balance["amount"] = balances[balance["account_id"]]
    result["reports"][0]["period"] = "2026-01"
    for metric in result["reports"][0]["metrics"]:
        if metric["metric"] in report_amounts:
            metric["amount"] = report_amounts[metric["metric"]]
    result["derived_statuses"].append({"id": "derived-rg04-test", "target_kind": "transaction", "target_id": transaction_id, "status_name": "reconciliation_summary", "value": "pending"})
    operation = deepcopy(case["operations"][0])
    operation.update(id=operation_id, root_id="root-rg04-test", baseline_state_id=baseline["id"], result_state_id=result["id"], action_type=action, input=input_value)
    operation["status_changes"] = [{"target_kind": "transaction", "target_id": transaction_id, "status_name": "reconciliation_summary", "before": None, "after": "pending"}]
    operation["deltas"]["entity_changes"] = golden_v2._expected_entity_changes(baseline, result)
    operation["deltas"]["value_changes"] = {"balances": [], "reports": [], "derived_statuses": [{"key": {"kind": "transaction", "target_id": transaction_id, "status_name": "reconciliation_summary"}, "before": None, "after": "pending"}]}
    for before in baseline["balances"]:
        after = next(item for item in result["balances"] if item["account_id"] == before["account_id"] and item["currency"] == before["currency"])
        if before["amount"] != after["amount"]:
            operation["deltas"]["value_changes"]["balances"].append({"key": {"account_id": before["account_id"], "currency": before["currency"]}, "before": before["amount"], "after": after["amount"]})
    for metric, amount in report_amounts.items():
        if amount != "0.00":
            operation["deltas"]["value_changes"]["reports"].append({"key": {"period_type": "month", "period": "2026-01", "metric": metric, "currency": "CNY"}, "before": {"applicability": "applicable", "currency": "CNY", "amount": "0.00"}, "after": {"applicability": "applicable", "currency": "CNY", "amount": amount}})
    operation["returned_ids"] = [{"kind": "confirmation", "id": "confirmation-rg04-test"}, {"kind": "transaction", "id": transaction_id}]
    case["states"] = [baseline, result]
    case["operations"] = [operation]
    return case, baseline, result


def _rg04_value_changes(before: dict, after: dict) -> dict:
    def changes_for(before_map, after_map, key_builder):
        changes = []
        for key, (old, new) in sorted(golden_v2._changes(before_map, after_map).items()):
            changes.append({"key": key_builder(key), "before": old, "after": new})
        return changes

    return {
        "balances": changes_for(
            golden_v2._balance_map(before),
            golden_v2._balance_map(after),
            lambda key: {"account_id": key[0], "currency": key[1]},
        ),
        "reports": changes_for(
            golden_v2._report_map(before),
            golden_v2._report_map(after),
            lambda key: {
                "period_type": key[0],
                "period": key[1],
                "metric": key[2],
                **({"currency": key[3]} if key[3] is not None else {}),
            },
        ),
        "derived_statuses": changes_for(
            golden_v2._status_map(before),
            golden_v2._status_map(after),
            lambda key: {"kind": key[0], "target_id": key[1], "status_name": key[2]},
        ),
    }


def _rg04_action(case: dict, baseline: dict, result: dict, action: str, input_value: dict, operation_id: str, outcome: dict | None = None) -> dict:
    operation = deepcopy(case["operations"][0])
    value_changes = _rg04_value_changes(baseline, result)
    operation.update(
        id=operation_id,
        sequence=1,
        root_id=baseline["root_id"],
        action_type=action,
        operation_class="reconciliation" if action == "merge_mixed_payment_mirror_evidence" else "creation",
        baseline_state_id=baseline["id"],
        result_state_id=result["id"],
        input=input_value,
        outcome=outcome or {"status": "accepted"},
        status_changes=[
            {
                "target_kind": change["key"]["kind"],
                "target_id": change["key"]["target_id"],
                "status_name": change["key"]["status_name"],
                "before": change["before"],
                "after": change["after"],
            }
            for change in value_changes["derived_statuses"]
        ],
        deltas={
            "entity_changes": golden_v2._expected_entity_changes(baseline, result),
            "value_changes": value_changes,
        },
    )
    return operation


def _rg04_import_baseline() -> tuple[dict, dict]:
    case, baseline, _ = rg04_creation_case("manual_mixed_expense")
    required_metrics = {
        "balance_adjustment_net_worth_change", "budget", "cash_inflow", "cash_outflow",
        "consumption", "income", "internal_transfer_amount", "net_worth_change",
        "ordinary_expense", "ordinary_income",
    }
    for report in baseline["reports"]:
        by_name = {item["metric"]: item for item in report["metrics"]}
        for metric in required_metrics:
            if metric not in by_name:
                report["metrics"].append({"metric": metric, "applicability": "applicable", "currency": "CNY", "amount": "0.00"})
        budget = next(item for item in report["metrics"] if item["metric"] == "budget")
        budget.update(applicability="applicable", currency="CNY", amount="0.00")
    return case, baseline


def _rg04_complete_source(source_id: str = "source-record-rg04-complete", evidence_id: str = "evidence-rg04-asset-debit") -> dict:
    return {
        "id": source_id,
        "evidence_id": evidence_id,
        "observed_at": "2026-02-11T12:00:00+08:00",
        "total_amount": "120.00",
        "currency": "CNY",
        "suggested_category_id": "expense-category-daily",
        "funding_components": [
            {"account_id": "asset-bank-a", "funding_amount": "70.00", "currency": "CNY", "evidence_available": True},
            {"account_id": "liability-credit-b", "funding_amount": "50.00", "currency": "CNY", "evidence_available": False},
        ],
        "completeness": "complete",
    }


def _rg04_missing_source() -> dict:
    return {
        "id": "source-record-rg04-missing-leg",
        "evidence_id": "evidence-rg04-known-asset-debit",
        "observed_at": "2026-02-12T12:00:00+08:00",
        "total_amount": "120.00",
        "known_asset_funding_amount": "70.00",
        "currency": "CNY",
        "completeness": "missing_funding_leg",
    }


def _rg04_mirror_source() -> dict:
    return {
        "id": "source-rg04-credit-mirror",
        "evidence_id": "evidence-rg04-credit-mirror",
        "observed_at": "2026-02-11T12:00:00+08:00",
        "account_id": "liability-credit-b",
        "amount": "50.00",
        "currency": "CNY",
    }


def _rg04_ingested_state(baseline: dict, source_record: dict, candidate_id: str, confidence: str, payload: dict) -> dict:
    result = deepcopy(baseline)
    result["id"] = f"state-{candidate_id}"
    result["as_of_operation_id"] = f"operation-{candidate_id}-ingest"
    source = {"id": source_record["id"], "type": "mixed_payment", "payload": {key: value for key, value in source_record.items() if key != "id"}}
    evidence = {"id": source_record["evidence_id"], "type": "asset_funding_debit", "source_ids": [source["id"]], "payload": {"observed_at": source_record["observed_at"]}}
    candidate = {
        "id": candidate_id,
        "type": "mixed_payment",
        "source_ids": [source["id"]],
        "confidence": confidence,
        "payload": payload,
        "status_history": [{"id": f"status-{candidate_id}-pending", "sequence": 1, "status": "pending_confirmation"}],
    }
    result["sources"].append(source)
    result["evidence"].append(evidence)
    result["candidates"].append(candidate)
    result["derived_statuses"].append({
        "id": f"derived-{candidate_id}-confirmation",
        "target_kind": "candidate",
        "target_id": candidate_id,
        "status_name": "confirmation_status",
        "value": "pending_confirmation",
    })
    return result


def _rg04_complete_candidate_payload(source: dict) -> dict:
    return {
        "total_amount": source["total_amount"],
        "currency": source["currency"],
        "suggested_category_id": source["suggested_category_id"],
        "known_funding_components": source["funding_components"],
        "provenance": {"rule": "complete_mixed_payment_source", "rule_version": 1},
        "evidence_refs": [source["evidence_id"]],
        "requires_confirmation": ["category_id", "funding_components", "formal_transaction_creation"],
    }


def _rg04_missing_candidate_payload(source: dict) -> dict:
    return {
        "total_amount": source["total_amount"],
        "currency": source["currency"],
        "known_funding_amount": source["known_asset_funding_amount"],
        "missing_funding_amount": "50.00",
        "provenance": {"rule": "incomplete_mixed_payment_source", "rule_version": 1},
        "evidence_refs": [source["evidence_id"]],
        "requires_confirmation": ["funding_account_id", "missing_funding_amount", "category_id", "formal_transaction_creation"],
    }


def _rg04_confirmed_state(baseline: dict) -> dict:
    result = deepcopy(baseline)
    result["id"] = "state-rg04-confirmed"
    result["as_of_operation_id"] = "operation-rg04-confirm"
    candidate = next(item for item in result["candidates"] if item["id"] == "candidate-purchase-rg04")
    candidate["status_history"].append({"id": "status-candidate-purchase-rg04-confirmed", "sequence": 2, "status": "confirmed"})
    candidate["payload"]["transaction_id"] = "tx-purchase-rg04-imported"
    next(
        item
        for item in result["derived_statuses"]
        if item["target_kind"] == "candidate"
        and item["target_id"] == candidate["id"]
        and item["status_name"] == "confirmation_status"
    )["value"] = "confirmed"
    result["transactions"].append({"id": "tx-purchase-rg04-imported", "type": "expense", "current_version_id": "version-purchase-rg04-imported-v1"})
    source_id = candidate["source_ids"][0]
    observed_at = next(item for item in result["sources"] if item["id"] == source_id)["payload"]["observed_at"]
    result["transaction_versions"].append({"id": "version-purchase-rg04-imported-v1", "transaction_id": "tx-purchase-rg04-imported", "version_number": 1, "posting_set_id": "posting-set-purchase-rg04-imported", "occurred_at": observed_at, "statistics_at": observed_at, "effective_at": observed_at, "confirmation_id": "confirmation-rg04-confirm"})
    posting_ids = ["posting-expense-rg04-imported", "posting-asset-rg04-imported", "posting-liability-rg04-imported"]
    result["posting_sets"].append({"id": "posting-set-purchase-rg04-imported", "posting_ids": posting_ids})
    result["postings"].extend([
        {"id": posting_ids[0], "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "expense-account-daily", "category_id": "expense-category-daily", "amount": "120.00", "currency": "CNY", "role": "expense", "reconciliation_eligible": False},
        {"id": posting_ids[1], "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "asset-bank-a", "amount": "-70.00", "currency": "CNY", "role": "mixed_expense_asset_funding", "reconciliation_eligible": True},
        {"id": posting_ids[2], "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "liability-credit-b", "amount": "-50.00", "currency": "CNY", "role": "mixed_expense_credit_funding", "reconciliation_eligible": True},
    ])
    result["confirmations"].append({"id": "confirmation-rg04-confirm", "type": "candidate_confirmation", "operation_id": "operation-rg04-confirm", "subject": {"kind": "candidate", "id": "candidate-purchase-rg04"}, "payload": {}})
    result["relations"].append({"id": "relation-rg04-imported", "type": "mixed_payment", "member_refs": [{"kind": "transaction", "id": "tx-purchase-rg04-imported"}, {"kind": "posting", "id": posting_ids[1]}, {"kind": "posting", "id": posting_ids[2]}], "payload": {"system_managed": True, "display_name": "Mixed payment", "generic_order_lifecycle": False, "payment_composition_total": "120.00", "funding_components": [{"account_id": "asset-bank-a", "funding_amount": "70.00", "currency": "CNY", "posting_id": posting_ids[1]}, {"account_id": "liability-credit-b", "funding_amount": "50.00", "currency": "CNY", "posting_id": posting_ids[2]}]}})
    result["posting_reconciliations"].extend([{"id": "reconciliation-rg04-imported-asset", "posting_id": posting_ids[1], "status": "pending"}, {"id": "reconciliation-rg04-imported-liability", "posting_id": posting_ids[2], "status": "pending"}])
    balance_amounts = {"asset-bank-a": "930.00", "expense-account-daily": "120.00", "liability-credit-b": "-50.00"}
    for balance in result["balances"]:
        if balance["account_id"] in balance_amounts:
            balance["amount"] = balance_amounts[balance["account_id"]]
    period = observed_at[:7]
    report = next((item for item in result["reports"] if item["period_type"] == "month" and item["period"] == period), None)
    if report is None:
        report = deepcopy(result["reports"][0])
        report["period"] = period
        result["reports"].append(report)
    report_amounts = {"cash_outflow": "70.00", "consumption": "120.00", "income": "0.00", "net_worth_change": "-120.00", "ordinary_expense": "120.00"}
    for metric in report["metrics"]:
        if metric["metric"] in report_amounts:
            metric["amount"] = report_amounts[metric["metric"]]
    result["derived_statuses"].append({"id": "derived-rg04-imported", "target_kind": "transaction", "target_id": "tx-purchase-rg04-imported", "status_name": "reconciliation_summary", "value": "pending"})
    return result


def _rg04_confirmation_case() -> tuple[dict, dict, dict, dict, dict]:
    case, baseline = _rg04_import_baseline()
    source = _rg04_complete_source()
    baseline = _rg04_ingested_state(
        baseline,
        source,
        "candidate-purchase-rg04",
        "1.00",
        _rg04_complete_candidate_payload(source),
    )
    result = _rg04_confirmed_state(baseline)
    input_value = {
        "request_id": "request-rg04-confirm",
        "candidate_id": "candidate-purchase-rg04",
        "category_id": "expense-category-daily",
        "confirmed_funding_components": [
            {"account_id": "asset-bank-a", "funding_amount": "70.00", "currency": "CNY"},
            {"account_id": "liability-credit-b", "funding_amount": "50.00", "currency": "CNY"},
        ],
        "explicit_confirmation": True,
    }
    operation = _rg04_action(
        case,
        baseline,
        result,
        "confirm_mixed_payment_candidate",
        input_value,
        "operation-rg04-confirm",
    )
    operation["returned_ids"] = [
        {"kind": "confirmation", "id": "confirmation-rg04-confirm"},
        {"kind": "transaction", "id": "tx-purchase-rg04-imported"},
    ]
    return case, baseline, result, input_value, operation


def _rg04_public_ingest_case(source: dict, candidate_id: str, confidence: str, payload: dict) -> dict:
    case, initial = _rg04_import_baseline()
    result = _rg04_ingested_state(initial, source, candidate_id, confidence, payload)
    operation_id = result["as_of_operation_id"]
    operation = _rg04_action(
        case,
        initial,
        result,
        "ingest_mixed_payment_source",
        {"request_id": f"request-{candidate_id}", "source_record": source},
        operation_id,
    )
    operation["returned_ids"] = [
        {"kind": "source", "id": source["id"]},
        {"kind": "evidence", "id": source["evidence_id"]},
        {"kind": "candidate", "id": candidate_id},
    ]
    case["roots"] = [{"id": initial["root_id"], "purpose": "RG04 public ingest", "initial_state_id": initial["id"], "operation_ids": [operation_id]}]
    case["states"] = [initial, result]
    case["operations"] = [operation]
    return case


def _rg04_public_confirmation_case(*, include_replay: bool = False) -> dict:
    case, initial = _rg04_import_baseline()
    source = _rg04_complete_source()
    ingested = _rg04_ingested_state(initial, source, "candidate-purchase-rg04", "1.00", _rg04_complete_candidate_payload(source))
    ingest_operation = _rg04_action(
        case,
        initial,
        ingested,
        "ingest_mixed_payment_source",
        {"request_id": "request-candidate-purchase-rg04", "source_record": source},
        ingested["as_of_operation_id"],
    )
    ingest_operation["returned_ids"] = [
        {"kind": "source", "id": source["id"]},
        {"kind": "evidence", "id": source["evidence_id"]},
        {"kind": "candidate", "id": "candidate-purchase-rg04"},
    ]
    confirmed = _rg04_confirmed_state(ingested)
    input_value = {
        "request_id": "request-rg04-confirm",
        "candidate_id": "candidate-purchase-rg04",
        "category_id": "expense-category-daily",
        "confirmed_funding_components": [
            {"account_id": "asset-bank-a", "funding_amount": "70.00", "currency": "CNY"},
            {"account_id": "liability-credit-b", "funding_amount": "50.00", "currency": "CNY"},
        ],
        "explicit_confirmation": True,
    }
    confirm_operation = _rg04_action(case, ingested, confirmed, "confirm_mixed_payment_candidate", input_value, "operation-rg04-confirm")
    confirm_operation["sequence"] = 2
    confirm_operation["returned_ids"] = [
        {"kind": "confirmation", "id": "confirmation-rg04-confirm"},
        {"kind": "transaction", "id": "tx-purchase-rg04-imported"},
    ]
    operations = [ingest_operation, confirm_operation]
    states = [initial, ingested, confirmed]
    if include_replay:
        replay_result = deepcopy(confirmed)
        replay_result["id"] = "state-rg04-confirm-replay"
        replay_result["as_of_operation_id"] = "operation-rg04-confirm-replay"
        replay_operation = _rg04_action(
            case,
            confirmed,
            replay_result,
            "confirm_mixed_payment_candidate",
            deepcopy(input_value),
            "operation-rg04-confirm-replay",
            {"status": "no_change", "reason_code": "idempotent_replay"},
        )
        replay_operation["sequence"] = 3
        replay_operation["returned_ids"] = deepcopy(confirm_operation["returned_ids"])
        operations.append(replay_operation)
        states.append(replay_result)
    case["roots"] = [{
        "id": initial["root_id"],
        "purpose": "RG04 public confirmation",
        "initial_state_id": initial["id"],
        "operation_ids": [item["id"] for item in operations],
    }]
    case["states"] = states
    case["operations"] = operations
    return case


def _rg04_public_mirror_case() -> dict:
    case = _rg04_public_confirmation_case()
    baseline = case["states"][-1]
    result = deepcopy(baseline)
    result["id"] = "state-rg04-mirror"
    result["as_of_operation_id"] = "operation-rg04-mirror"
    mirror_source = _rg04_mirror_source()
    result["sources"].append({
        "id": mirror_source["id"],
        "type": "mixed_payment",
        "payload": {key: value for key, value in mirror_source.items() if key != "id"},
    })
    result["evidence"].append({
        "id": mirror_source["evidence_id"],
        "type": "credit_liability_mirror",
        "source_ids": [mirror_source["id"]],
        "payload": {"observed_at": mirror_source["observed_at"]},
    })
    result["evidence_links"].append({
        "id": "link-rg04-credit-mirror",
        "evidence_id": mirror_source["evidence_id"],
        "target_kind": "posting",
        "target_id": "posting-liability-rg04-imported",
        "role": "real_account_posting",
    })
    next(
        item
        for item in result["posting_reconciliations"]
        if item["posting_id"] == "posting-liability-rg04-imported"
    )["status"] = "matched"
    next(
        item
        for item in result["derived_statuses"]
        if item["target_kind"] == "transaction"
        and item["target_id"] == "tx-purchase-rg04-imported"
        and item["status_name"] == "reconciliation_summary"
    )["value"] = "partial"
    input_value = {
        "request_id": "request-rg04-credit-mirror",
        "source_record_id": mirror_source["id"],
        "evidence_id": mirror_source["evidence_id"],
        "transaction_id": "tx-purchase-rg04-imported",
        "candidate_id": "candidate-purchase-rg04",
        "account_id": mirror_source["account_id"],
        "amount": mirror_source["amount"],
        "currency": mirror_source["currency"],
        "observed_at": mirror_source["observed_at"],
    }
    operation = _rg04_action(
        case,
        baseline,
        result,
        "merge_mixed_payment_mirror_evidence",
        input_value,
        "operation-rg04-mirror",
    )
    operation["sequence"] = 3
    operation["returned_ids"] = [
        {"kind": "source", "id": mirror_source["id"]},
        {"kind": "evidence", "id": mirror_source["evidence_id"]},
        {"kind": "evidence_link", "id": "link-rg04-credit-mirror"},
    ]
    case["states"].append(result)
    case["operations"].append(operation)
    case["roots"][0]["operation_ids"].append(operation["id"])
    return case


def rg03_full_manual_case() -> dict:
    case = deepcopy(load_rg09())
    case["case"]["id"] = "RG-03"
    case["roots"][0]["operation_ids"] = ["operation-rg03-manual"]
    initial = deepcopy(case["states"][0])
    initial["id"] = "state-rg03-opening"
    initial["as_of_operation_id"] = None
    fee_account = {
        "id": "expense-account-transfer-fee-rg03",
        "name": "Transfer fee",
        "kind": "expense",
        "currency": "CNY",
        "owned_by_user": False,
        "real_account": False,
        "reconciliation_eligible": False,
    }
    fee_group = {"id": "expense-category-financial-rg03", "name": "Financial", "parent_id": None, "posting_account_id": None, "active": True}
    fee_category = {"id": "expense-category-transfer-fee-rg03", "name": "Transfer fee", "parent_id": fee_group["id"], "posting_account_id": fee_account["id"], "active": True}
    for item in (initial,):
        item["catalog"]["accounts"].append(fee_account)
        item["catalog"]["categories"].extend([fee_group, fee_category])
        item["balances"].append({"account_id": fee_account["id"], "currency": "CNY", "amount": "0.00"})

    result = deepcopy(initial)
    result["id"] = "state-rg03-manual"
    result["as_of_operation_id"] = "operation-rg03-manual"
    result["transactions"].append({"id": "transaction-rg03-manual", "type": "account_transfer", "current_version_id": "version-rg03-manual-v1"})
    result["transaction_versions"].append({
        "id": "version-rg03-manual-v1", "transaction_id": "transaction-rg03-manual", "version_number": 1,
        "posting_set_id": "posting-set-rg03-manual", "occurred_at": "2026-01-20T10:00:00+08:00",
        "statistics_at": "2026-01-20T10:00:00+08:00", "effective_at": "2026-01-20T10:00:00+08:00",
        "confirmation_id": "confirmation-rg03-manual",
    })
    result["posting_sets"].append({"id": "posting-set-rg03-manual", "posting_ids": ["posting-source-rg03-manual", "posting-destination-rg03-manual", "posting-fee-rg03-manual"]})
    result["postings"].extend([
        {"id": "posting-source-rg03-manual", "posting_set_id": "posting-set-rg03-manual", "account_id": "asset-a", "amount": "-60.00", "currency": "CNY", "role": "transfer_principal_out", "reconciliation_eligible": True},
        {"id": "posting-destination-rg03-manual", "posting_set_id": "posting-set-rg03-manual", "account_id": "asset-b", "amount": "59.00", "currency": "CNY", "role": "transfer_principal_in", "reconciliation_eligible": True},
        {"id": "posting-fee-rg03-manual", "posting_set_id": "posting-set-rg03-manual", "account_id": fee_account["id"], "amount": "1.00", "currency": "CNY", "role": "transfer_fee", "reconciliation_eligible": False},
    ])
    result["confirmations"].append({"id": "confirmation-rg03-manual", "type": "explicit_manual_save", "operation_id": "operation-rg03-manual", "subject": {"kind": "operation", "id": "operation-rg03-manual"}, "payload": {}})
    result["posting_reconciliations"].extend([
        {"id": "reconciliation-source-rg03-manual", "posting_id": "posting-source-rg03-manual", "status": "pending"},
        {"id": "reconciliation-destination-rg03-manual", "posting_id": "posting-destination-rg03-manual", "status": "pending"},
    ])
    for balance in result["balances"]:
        if balance["account_id"] == "asset-a":
            balance["amount"] = "40.00"
        elif balance["account_id"] == "asset-b":
            balance["amount"] = "109.00"
        elif balance["account_id"] == fee_account["id"]:
            balance["amount"] = "1.00"
    metrics = {item["metric"]: item for item in result["reports"][0]["metrics"]}
    metrics["cash_outflow"]["amount"] = "1.00"
    metrics["consumption"]["amount"] = "1.00"
    metrics["internal_transfer_amount"]["amount"] = "59.00"
    metrics["net_worth_change"]["amount"] = "-1.00"
    metrics["ordinary_expense"]["amount"] = "1.00"
    result["derived_statuses"] = [{"id": "derived-rg03-manual", "target_kind": "transaction", "target_id": "transaction-rg03-manual", "status_name": "reconciliation_summary", "value": "pending"}]

    def id_changes(before, after):
        return golden_v2._expected_entity_changes(before, after)

    def value_changes(before, after):
        balance_changes = []
        for key, (old, new) in sorted(golden_v2._changes(golden_v2._balance_map(before), golden_v2._balance_map(after)).items()):
            balance_changes.append({"key": {"account_id": key[0], "currency": key[1]}, "before": old, "after": new})
        report_changes = []
        for key, (old, new) in sorted(golden_v2._changes(golden_v2._report_map(before), golden_v2._report_map(after)).items()):
            report_key = {"period_type": key[0], "period": key[1], "metric": key[2]}
            if key[3] is not None:
                report_key["currency"] = key[3]
            report_changes.append({"key": report_key, "before": old, "after": new})
        status_changes = []
        for key, (old, new) in sorted(golden_v2._changes(golden_v2._status_map(before), golden_v2._status_map(after)).items()):
            status_changes.append({"key": {"kind": key[0], "target_id": key[1], "status_name": key[2]}, "before": old, "after": new})
        return {"balances": balance_changes, "reports": report_changes, "derived_statuses": status_changes}, status_changes

    entity_changes = id_changes(initial, result)
    value_change, status_changes = value_changes(initial, result)
    operation = {
        "id": "operation-rg03-manual", "root_id": "root-rg09-main", "sequence": 1,
        "operation_class": "creation", "action_type": "manual_account_transfer",
        "baseline_state_id": initial["id"], "result_state_id": result["id"],
        "input": {"request_id": "request-rg03-manual", "source_account_id": "asset-a", "destination_account_id": "asset-b", "source_debit_amount": "60.00", "destination_credit_amount": "59.00", "fee_amount": "1.00", "currency": "CNY", "fee_category_id": fee_category["id"], "occurred_at": "2026-01-20T10:00:00+08:00", "explicit_confirmation": True},
        "outcome": {"status": "accepted"}, "status_changes": [{"target_kind": item["key"]["kind"], "target_id": item["key"]["target_id"], "status_name": item["key"]["status_name"], "before": item["before"], "after": item["after"]} for item in status_changes],
        "deltas": {"entity_changes": entity_changes, "value_changes": value_change},
        "returned_ids": [{"kind": "confirmation", "id": "confirmation-rg03-manual"}, {"kind": "transaction", "id": "transaction-rg03-manual"}],
    }
    case["case"]["id"] = "RG-03"
    case["roots"] = [{"id": "root-rg09-main", "purpose": "representative_main_path", "initial_state_id": initial["id"], "operation_ids": [operation["id"]]}]
    case["states"] = [initial, result]
    case["operations"] = [operation]
    return case


def rg03_import_source_record_case() -> dict:
    case = rg03_full_manual_case()
    baseline = deepcopy(case["states"][-1])
    result = deepcopy(baseline)
    result["id"] = "state-rg03-imported-source"
    result["as_of_operation_id"] = "operation-rg03-import-source"
    source = {
        "id": "source-rg03-imported-transfer",
        "type": "account_transfer",
        "payload": {
            "source_account_id": "asset-a",
            "destination_account_id": "asset-b",
            "source_debit_amount": "60.00",
            "destination_credit_amount": "59.00",
            "fee_amount": "1.00",
            "currency": "CNY",
            "completeness": "complete",
            "observed_at": "2026-01-21T10:00:00+08:00",
            "evidence_id": "evidence-rg03-imported-transfer",
        },
    }
    evidence = {
        "id": "evidence-rg03-imported-transfer",
        "type": "transfer_record",
        "source_ids": [source["id"]],
        "payload": {"observed_at": source["payload"]["observed_at"]},
    }
    candidate = {
        "id": "candidate-rg03-imported-transfer",
        "type": "account_transfer",
        "source_ids": [source["id"]],
        "confidence": "1.00",
        "payload": {
            "source_account_id": "asset-a",
            "destination_account_id": "asset-b",
            "source_debit_amount": "60.00",
            "destination_credit_amount": "59.00",
            "fee_amount": "1.00",
            "currency": "CNY",
            "evidence_refs": [evidence["id"]],
            "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
            "requires_confirmation": ["formal_transaction_creation"],
        },
        "status_history": [
            {"id": "candidate-rg03-imported-transfer-pending", "sequence": 1, "status": "pending_confirmation"}
        ],
    }
    result["sources"].append(source)
    result["evidence"].append(evidence)
    result["candidates"].append(candidate)
    result["derived_statuses"].append({
        "id": "derived-candidate-rg03-imported-transfer",
        "target_kind": "candidate",
        "target_id": candidate["id"],
        "status_name": "confirmation_status",
        "value": "pending_confirmation",
    })
    operation = {
        "id": "operation-rg03-import-source",
        "root_id": "root-rg09-main",
        "sequence": 2,
        "operation_class": "creation",
        "action_type": "import_source_record",
        "baseline_state_id": baseline["id"],
        "result_state_id": result["id"],
        "input": {
            "request_id": "request-rg03-import-source",
            "source_id": source["id"],
            "evidence_id": evidence["id"],
            "source_account_id": "asset-a",
            "destination_account_id": "asset-b",
            "source_debit_amount": "60.00",
            "destination_credit_amount": "59.00",
            "fee_amount": "1.00",
            "currency": "CNY",
            "observed_at": source["payload"]["observed_at"],
        },
        "outcome": {"status": "accepted"},
        "status_changes": [{
            "target_kind": "candidate",
            "target_id": candidate["id"],
            "status_name": "confirmation_status",
            "before": None,
            "after": "pending_confirmation",
        }],
        "deltas": {
            "entity_changes": golden_v2._expected_entity_changes(baseline, result),
            "value_changes": {
                "balances": [],
                "reports": [],
                "derived_statuses": [{
                    "key": {
                        "kind": "candidate",
                        "target_id": candidate["id"],
                        "status_name": "confirmation_status",
                    },
                    "before": None,
                    "after": "pending_confirmation",
                }],
            },
        },
        "returned_ids": [
            {"kind": "source", "id": source["id"]},
            {"kind": "evidence", "id": evidence["id"]},
            {"kind": "candidate", "id": candidate["id"]},
        ],
    }
    case["states"].append(result)
    case["operations"].append(operation)
    case["roots"][0]["operation_ids"].append(operation["id"])
    return case


def _operation_deltas(baseline: dict, result: dict) -> tuple[dict, list[dict]]:
    balance_changes = [
        {
            "key": {"account_id": key[0], "currency": key[1]},
            "before": old,
            "after": new,
        }
        for key, (old, new) in sorted(
            golden_v2._changes(
                golden_v2._balance_map(baseline), golden_v2._balance_map(result)
            ).items()
        )
    ]
    report_changes = []
    for key, (old, new) in sorted(
        golden_v2._changes(
            golden_v2._report_map(baseline), golden_v2._report_map(result)
        ).items()
    ):
        report_key = {"period_type": key[0], "period": key[1], "metric": key[2]}
        if key[3] is not None:
            report_key["currency"] = key[3]
        report_changes.append({"key": report_key, "before": old, "after": new})
    status_changes = [
        {
            "key": {
                "kind": key[0],
                "target_id": key[1],
                "status_name": key[2],
            },
            "before": old,
            "after": new,
        }
        for key, (old, new) in sorted(
            golden_v2._changes(
                golden_v2._status_map(baseline), golden_v2._status_map(result)
            ).items()
        )
    ]
    return {
        "entity_changes": golden_v2._expected_entity_changes(baseline, result),
        "value_changes": {
            "balances": balance_changes,
            "reports": report_changes,
            "derived_statuses": status_changes,
        },
    }, [
        {
            "target_kind": item["key"]["kind"],
            "target_id": item["key"]["target_id"],
            "status_name": item["key"]["status_name"],
            "before": item["before"],
            "after": item["after"],
        }
        for item in status_changes
    ]


def rg03_confirm_account_transfer_candidate_case() -> dict:
    case = rg03_import_source_record_case()
    baseline = deepcopy(case["states"][-1])
    result = deepcopy(baseline)
    result["id"] = "state-rg03-imported-transfer-confirmed"
    result["as_of_operation_id"] = "operation-rg03-confirm-imported-transfer"
    candidate = result["candidates"][-1]
    candidate["payload"]["transaction_id"] = "transaction-rg03-confirmed-transfer"
    candidate["status_history"].append(
        {
            "id": "candidate-rg03-imported-transfer-confirmed",
            "sequence": 2,
            "status": "confirmed",
        }
    )
    result["transactions"].append(
        {
            "id": "transaction-rg03-confirmed-transfer",
            "type": "account_transfer",
            "current_version_id": "version-rg03-confirmed-transfer-v1",
        }
    )
    result["transaction_versions"].append(
        {
            "id": "version-rg03-confirmed-transfer-v1",
            "transaction_id": "transaction-rg03-confirmed-transfer",
            "version_number": 1,
            "posting_set_id": "posting-set-rg03-confirmed-transfer",
            "occurred_at": "2026-01-21T10:00:00+08:00",
            "statistics_at": "2026-01-21T10:00:00+08:00",
            "effective_at": "2026-01-21T10:00:00+08:00",
            "confirmation_id": "confirmation-rg03-imported-transfer",
        }
    )
    result["posting_sets"].append(
        {
            "id": "posting-set-rg03-confirmed-transfer",
            "posting_ids": [
                "posting-source-rg03-confirmed-transfer",
                "posting-destination-rg03-confirmed-transfer",
                "posting-fee-rg03-confirmed-transfer",
            ],
        }
    )
    result["postings"].extend(
        [
            {
                "id": "posting-source-rg03-confirmed-transfer",
                "posting_set_id": "posting-set-rg03-confirmed-transfer",
                "account_id": "asset-a",
                "amount": "-60.00",
                "currency": "CNY",
                "role": "transfer_principal_out",
                "reconciliation_eligible": True,
            },
            {
                "id": "posting-destination-rg03-confirmed-transfer",
                "posting_set_id": "posting-set-rg03-confirmed-transfer",
                "account_id": "asset-b",
                "amount": "59.00",
                "currency": "CNY",
                "role": "transfer_principal_in",
                "reconciliation_eligible": True,
            },
            {
                "id": "posting-fee-rg03-confirmed-transfer",
                "posting_set_id": "posting-set-rg03-confirmed-transfer",
                "account_id": "expense-account-transfer-fee-rg03",
                "amount": "1.00",
                "currency": "CNY",
                "role": "transfer_fee",
                "reconciliation_eligible": False,
            },
        ]
    )
    result["confirmations"].append(
        {
            "id": "confirmation-rg03-imported-transfer",
            "type": "candidate_confirmation",
            "operation_id": "operation-rg03-confirm-imported-transfer",
            "subject": {
                "kind": "candidate",
                "id": "candidate-rg03-imported-transfer",
            },
            "payload": {},
        }
    )
    result["evidence_links"].append(
        {
            "id": "evidence-link-rg03-confirmed-transfer",
            "evidence_id": "evidence-rg03-imported-transfer",
            "target_kind": "posting",
            "target_id": "posting-source-rg03-confirmed-transfer",
            "role": "real_account_posting",
        }
    )
    result["posting_reconciliations"].extend(
        [
            {
                "id": "reconciliation-source-rg03-confirmed-transfer",
                "posting_id": "posting-source-rg03-confirmed-transfer",
                "status": "matched",
            },
            {
                "id": "reconciliation-destination-rg03-confirmed-transfer",
                "posting_id": "posting-destination-rg03-confirmed-transfer",
                "status": "pending",
            },
        ]
    )
    for balance in result["balances"]:
        if balance["account_id"] == "asset-a":
            balance["amount"] = "-20.00"
        elif balance["account_id"] == "asset-b":
            balance["amount"] = "168.00"
        elif balance["account_id"] == "expense-account-transfer-fee-rg03":
            balance["amount"] = "2.00"
    for metric in result["reports"][0]["metrics"]:
        if metric["metric"] in {"cash_outflow", "consumption", "ordinary_expense"}:
            metric["amount"] = "2.00"
        elif metric["metric"] == "internal_transfer_amount":
            metric["amount"] = "118.00"
        elif metric["metric"] == "net_worth_change":
            metric["amount"] = "-2.00"
    result["derived_statuses"] = [
        {
            **item,
            "value": "confirmed",
        }
        if item["target_id"] == candidate["id"]
        else item
        for item in result["derived_statuses"]
    ]
    result["derived_statuses"].append(
        {
            "id": "derived-rg03-confirmed-transfer",
            "target_kind": "transaction",
            "target_id": "transaction-rg03-confirmed-transfer",
            "status_name": "reconciliation_summary",
            "value": "partial",
        }
    )
    deltas, status_changes = _operation_deltas(baseline, result)
    operation = {
        "id": "operation-rg03-confirm-imported-transfer",
        "root_id": "root-rg09-main",
        "sequence": 3,
        "operation_class": "creation",
        "action_type": "confirm_account_transfer_candidate",
        "baseline_state_id": baseline["id"],
        "result_state_id": result["id"],
        "input": {
            "request_id": "request-rg03-confirm-imported-transfer",
            "candidate_id": candidate["id"],
            "source_account_id": "asset-a",
            "destination_account_id": "asset-b",
            "source_debit_amount": "60.00",
            "destination_credit_amount": "59.00",
            "fee_amount": "1.00",
            "currency": "CNY",
            "fee_category_id": "expense-category-transfer-fee-rg03",
            "occurred_at": "2026-01-21T10:00:00+08:00",
            "explicit_confirmation": True,
        },
        "outcome": {"status": "accepted"},
        "status_changes": status_changes,
        "deltas": deltas,
        "returned_ids": [
            {"kind": "confirmation", "id": "confirmation-rg03-imported-transfer"},
            {"kind": "transaction", "id": "transaction-rg03-confirmed-transfer"},
        ],
    }
    case["states"].append(result)
    case["operations"].append(operation)
    case["roots"][0]["operation_ids"].append(operation["id"])
    retry = deepcopy(result)
    retry["id"] = "state-rg03-confirmed-transfer-retry"
    retry["as_of_operation_id"] = "operation-rg03-confirm-imported-transfer-retry"
    retry_deltas, retry_status_changes = _operation_deltas(result, retry)
    retry_operation = {
        **operation,
        "id": "operation-rg03-confirm-imported-transfer-retry",
        "sequence": 4,
        "baseline_state_id": result["id"],
        "result_state_id": retry["id"],
        "outcome": {"status": "no_change", "reason_code": "idempotent_replay"},
        "status_changes": retry_status_changes,
        "deltas": retry_deltas,
    }
    case["states"].append(retry)
    case["operations"].append(retry_operation)
    case["roots"][0]["operation_ids"].append(retry_operation["id"])
    return case


def add_second_rg03_pending_candidate(case: dict) -> dict:
    case = deepcopy(case)
    source = {
        "id": "source-rg03-secondary-transfer",
        "type": "account_transfer",
        "payload": {
            "source_account_id": "asset-b",
            "destination_account_id": "asset-a",
            "source_debit_amount": "20.00",
            "destination_credit_amount": "20.00",
            "fee_amount": "0.00",
            "currency": "CNY",
            "completeness": "complete",
            "observed_at": "2026-01-21T11:00:00+08:00",
            "evidence_id": "evidence-rg03-secondary-transfer",
        },
    }
    evidence = {
        "id": "evidence-rg03-secondary-transfer",
        "type": "transfer_record",
        "source_ids": [source["id"]],
        "payload": {"observed_at": source["payload"]["observed_at"]},
    }
    candidate = {
        "id": "candidate-rg03-secondary-transfer",
        "type": "account_transfer",
        "source_ids": [source["id"]],
        "confidence": "1.00",
        "payload": {
            "source_account_id": "asset-b",
            "destination_account_id": "asset-a",
            "source_debit_amount": "20.00",
            "destination_credit_amount": "20.00",
            "fee_amount": "0.00",
            "currency": "CNY",
            "evidence_refs": [evidence["id"]],
            "provenance": {
                "rule": "complete_transfer_source",
                "rule_version": 1,
            },
            "requires_confirmation": ["formal_transaction_creation"],
        },
        "status_history": [
            {
                "id": "candidate-rg03-secondary-transfer-pending",
                "sequence": 1,
                "status": "pending_confirmation",
            }
        ],
    }
    derived_status = {
        "id": "derived-candidate-rg03-secondary-transfer",
        "target_kind": "candidate",
        "target_id": candidate["id"],
        "status_name": "confirmation_status",
        "value": "pending_confirmation",
    }
    for state in case["states"]:
        state["sources"].append(deepcopy(source))
        state["evidence"].append(deepcopy(evidence))
        state["candidates"].append(deepcopy(candidate))
        state["derived_statuses"].append(deepcopy(derived_status))
    return case


def schema_errors(case: dict) -> list:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return list(Draft202012Validator(schema).iter_errors(case))


def rg03_transfer_provenance_case(complete: bool = True, confirmed: bool = False) -> dict:
    case = deepcopy(load_rg01())
    state = case["states"][1]
    observed_at = "2026-01-21T11:00:00+08:00"
    state["catalog"]["accounts"].append(
        {
            "id": "asset-wallet-b",
            "name": "Wallet B",
            "kind": "asset",
            "currency": "CNY",
            "owned_by_user": True,
            "real_account": True,
            "reconciliation_eligible": True,
        }
    )
    state["balances"].append(
        {"account_id": "asset-wallet-b", "currency": "CNY", "amount": "0.00"}
    )
    source = {
        "id": "source-transfer-rg03",
        "type": "account_transfer",
        "payload": {
            "source_account_id": "asset-bank-a",
            "currency": "CNY",
            "completeness": "complete" if complete else "missing_destination",
            "observed_at": observed_at,
            "evidence_id": "evidence-transfer-rg03",
        },
    }
    candidate_payload = {
        "currency": "CNY",
        "evidence_refs": ["evidence-transfer-rg03"],
        "provenance": {"rule": "complete_transfer_source", "rule_version": 1},
        "requires_confirmation": ["formal_transaction_creation"],
    }
    if complete:
        source["payload"].update(
            {
                "destination_account_id": "asset-wallet-b",
                "source_debit_amount": "60.00",
                "destination_credit_amount": "59.00",
                "fee_amount": "1.00",
            }
        )
        candidate_payload.update(
            {
                "source_account_id": "asset-bank-a",
                "destination_account_id": "asset-wallet-b",
                "source_debit_amount": "60.00",
                "destination_credit_amount": "59.00",
                "fee_amount": "1.00",
            }
        )
    else:
        source["payload"]["debit_amount"] = "40.00"
        candidate_payload.update(
            {
                "source_account_id": "asset-bank-a",
                "debit_amount": "40.00",
                "requires_confirmation": [
                    "destination_account_id",
                    "formal_transaction_creation",
                ],
            }
        )
    state["sources"].append(source)
    state["evidence"].append(
        {
            "id": "evidence-transfer-rg03",
            "type": "transfer_record",
            "source_ids": [source["id"]],
            "payload": {"observed_at": observed_at},
        }
    )
    history = [{"id": "candidate-transfer-pending", "sequence": 1, "status": "pending_confirmation"}]
    if confirmed:
        history.append({"id": "candidate-transfer-confirmed", "sequence": 2, "status": "confirmed"})
    state["candidates"].append(
        {
            "id": "candidate-transfer-rg03",
            "type": "account_transfer",
            "source_ids": [source["id"]],
            "confidence": "1.00",
            "payload": candidate_payload,
            "status_history": history,
        }
    )
    if confirmed:
        state["transactions"].append(
            {
                "id": "tx-transfer-rg03-confirmed",
                "type": "account_transfer",
                "current_version_id": "version-transfer-rg03-confirmed-v1",
            }
        )
        state["transaction_versions"].append(
            {
                "id": "version-transfer-rg03-confirmed-v1",
                "transaction_id": "tx-transfer-rg03-confirmed",
                "version_number": 1,
                "posting_set_id": "posting-set-transfer-rg03-confirmed",
                "occurred_at": "2026-01-21T12:00:00+08:00",
                "statistics_at": "2026-01-21T12:00:00+08:00",
                "effective_at": "2026-01-21T12:00:00+08:00",
            }
        )
        state["posting_sets"].append(
            {"id": "posting-set-transfer-rg03-confirmed", "posting_ids": ["posting-source-rg03-confirmed", "posting-destination-rg03-confirmed"]}
        )
        state["postings"].extend(
            [
                {"id": "posting-source-rg03-confirmed", "posting_set_id": "posting-set-transfer-rg03-confirmed", "account_id": "asset-bank-a", "amount": "-1.00", "currency": "CNY", "role": "transfer_principal_out", "reconciliation_eligible": True},
                {"id": "posting-destination-rg03-confirmed", "posting_set_id": "posting-set-transfer-rg03-confirmed", "account_id": "asset-wallet-b", "amount": "1.00", "currency": "CNY", "role": "transfer_principal_in", "reconciliation_eligible": True},
            ]
        )
        state["posting_reconciliations"].extend(
            [
                {"id": "reconciliation-source-rg03-confirmed", "posting_id": "posting-source-rg03-confirmed", "status": "pending"},
                {"id": "reconciliation-destination-rg03-confirmed", "posting_id": "posting-destination-rg03-confirmed", "status": "pending"},
            ]
        )
        for balance in state["balances"]:
            if balance["account_id"] == "asset-bank-a":
                balance["amount"] = "963.20"
            elif balance["account_id"] == "asset-wallet-b":
                balance["amount"] = "1.00"
        state["derived_statuses"].append(
            {"id": "derived-transaction-rg03-confirmed", "target_kind": "transaction", "target_id": "tx-transfer-rg03-confirmed", "status_name": "reconciliation_summary", "value": "pending"}
        )
        state["candidates"][-1]["payload"]["transaction_id"] = "tx-transfer-rg03-confirmed"
        state["confirmations"].append(
            {
                "id": "confirmation-transfer-rg03",
                "type": "candidate_confirmation",
                "operation_id": "operation-rg01-create",
                "subject": {"kind": "candidate", "id": "candidate-transfer-rg03"},
                "payload": {},
            }
        )
        state["transaction_versions"][-1]["confirmation_id"] = "confirmation-transfer-rg03"
    return case


def add_rg05_contract_objects(case: dict | None = None) -> dict:
    case = deepcopy(case) if case is not None else load_rg01()
    state = deepcopy(case["states"][1])
    state["id"] = "state-cross-rg-contract"
    state["as_of_operation_id"] = None
    state["confirmations"] = []
    for version in state["transaction_versions"]:
        version.pop("confirmation_id", None)
    case["states"] = [state]
    case["operations"] = []
    case["roots"][0]["initial_state_id"] = state["id"]
    case["roots"][0]["operation_ids"] = []
    expense_posting = next(
        item for item in state["postings"] if item["account_id"] == "expense-account-breakfast"
    )
    source = {
        "id": "source-cross-rg-contract",
        "type": "explicit_balance_observation",
        "payload": {
            "account_id": "asset-bank-a",
            "target_amount": "964.20",
            "currency": "CNY",
            "target_observed_at": "2026-01-15T08:30:00+08:00",
        },
    }
    state["sources"].append(source)
    state["domain_entities"].extend(
        [
            {
                "id": "consumption-cross-rg",
                "type": "consumption_record",
                "payload": {
                    "expense_posting_id": expense_posting["id"],
                    "category_id": "expense-category-breakfast",
                    "amount": "35.80",
                    "currency": "CNY",
                    "statistics_at": "2026-01-15T08:30:00+08:00",
                },
            },
            {
                "id": "allocation-cross-rg",
                "type": "item_allocation",
                "payload": {
                    "consumption_record_id": "consumption-cross-rg",
                    "expense_posting_id": expense_posting["id"],
                    "category_id": "expense-category-breakfast",
                    "amount": "35.80",
                    "currency": "CNY",
                },
            },
        ]
    )
    state["evidence"].append(
        {
            "id": "evidence-item-cross-rg",
            "type": "item_receipt",
            "source_ids": [source["id"]],
            "payload": {"observed_at": "2026-01-15T08:30:00+08:00"},
        }
    )
    state["evidence_links"].append(
        {
            "id": "link-item-allocation-cross-rg",
            "evidence_id": "evidence-item-cross-rg",
            "target_kind": "domain_entity",
            "target_id": "allocation-cross-rg",
            "role": "item_allocation_fact",
        }
    )

    return case


def rg10_contract_case() -> dict:
    occurred_at = "2026-01-15T08:30:00+08:00"
    state = {
        "id": "state-rg10-contract",
        "root_id": "root-rg10-contract",
        "as_of_operation_id": None,
        "catalog": {
            "accounts": [
                {
                    "id": "asset-stored-value",
                    "name": "Stored value",
                    "kind": "asset",
                    "currency": "CNY",
                    "owned_by_user": True,
                    "real_account": True,
                    "reconciliation_eligible": True,
                    "stored_value": {
                        "enabled": True,
                        "merchant_restricted": True,
                        "merchant_id": "merchant-contract",
                    },
                },
                {
                    "id": "asset-bank",
                    "name": "Bank",
                    "kind": "asset",
                    "currency": "CNY",
                    "owned_by_user": True,
                    "real_account": True,
                    "reconciliation_eligible": True,
                },
            ],
            "categories": [],
        },
        "transactions": [
            {
                "id": "transaction-recharge",
                "type": "stored_value_recharge",
                "current_version_id": "version-recharge-v1",
            }
        ],
        "transaction_versions": [
            {
                "id": "version-recharge-v1",
                "transaction_id": "transaction-recharge",
                "version_number": 1,
                "posting_set_id": "posting-set-recharge",
                "occurred_at": occurred_at,
                "statistics_at": occurred_at,
                "effective_at": occurred_at,
            }
        ],
        "posting_sets": [
            {
                "id": "posting-set-recharge",
                "posting_ids": ["posting-stored-value", "posting-bank"],
            }
        ],
        "postings": [
            {
                "id": "posting-stored-value",
                "posting_set_id": "posting-set-recharge",
                "account_id": "asset-stored-value",
                "amount": "35.80",
                "currency": "CNY",
                "role": "stored_value_asset",
                "reconciliation_eligible": True,
            },
            {
                "id": "posting-bank",
                "posting_set_id": "posting-set-recharge",
                "account_id": "asset-bank",
                "amount": "-35.80",
                "currency": "CNY",
                "role": "bank_payment",
                "reconciliation_eligible": True,
            },
        ],
        "sources": [
            {
                "id": "source-merchant-credit",
                "type": "explicit_balance_observation",
                "payload": {
                    "account_id": "asset-stored-value",
                    "target_amount": "35.80",
                    "currency": "CNY",
                    "target_observed_at": occurred_at,
                },
            }
        ],
        "candidates": [],
        "confirmations": [],
        "evidence": [
            {
                "id": "evidence-merchant-credit",
                "type": "merchant_stored_value_credit",
                "source_ids": ["source-merchant-credit"],
                "payload": {"observed_at": "2026-01-15T08:31:00+08:00"},
            }
        ],
        "evidence_links": [
            {
                "id": "link-merchant-posting",
                "evidence_id": "evidence-merchant-credit",
                "target_kind": "posting",
                "target_id": "posting-stored-value",
                "role": "stored_value_asset_posting",
            },
            {
                "id": "link-merchant-lot",
                "evidence_id": "evidence-merchant-credit",
                "target_kind": "domain_entity",
                "target_id": "lot-recharge",
                "role": "stored_value_lot_fact",
            },
        ],
        "relations": [],
        "domain_entities": [
            {
                "id": "lot-recharge",
                "type": "stored_value_lot",
                "payload": {
                    "recharge_transaction_id": "transaction-recharge",
                    "loaded_at": occurred_at,
                    "face_value": "35.80",
                    "currency": "CNY",
                },
            }
        ],
        "audit_links": [],
        "posting_reconciliations": [],
        "balances": [],
        "reports": [],
        "derived_statuses": [],
    }
    return {
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "case": {
            "id": "RG-10-CONTRACT",
            "level": "core_required",
            "rule_version": 1,
            "approval_status": "draft_for_review",
            "ledger_id": "ledger-rg10-contract",
            "timezone": "Asia/Shanghai",
            "currencies": [{"code": "CNY", "precision": 2}],
        },
        "roots": [
            {
                "id": "root-rg10-contract",
                "purpose": "contract",
                "initial_state_id": "state-rg10-contract",
                "operation_ids": [],
            }
        ],
        "states": [state],
        "operations": [],
    }


def add_second_rg10_recharge(case: dict) -> None:
    state = case["states"][0]
    occurred_at = "2026-01-16T09:00:00+08:00"
    state["transactions"].append(
        {
            "id": "transaction-recharge-other",
            "type": "stored_value_recharge",
            "current_version_id": "version-recharge-other-v1",
        }
    )
    state["transaction_versions"].append(
        {
            "id": "version-recharge-other-v1",
            "transaction_id": "transaction-recharge-other",
            "version_number": 1,
            "posting_set_id": "posting-set-recharge-other",
            "occurred_at": occurred_at,
            "statistics_at": occurred_at,
            "effective_at": occurred_at,
        }
    )
    state["posting_sets"].append(
        {
            "id": "posting-set-recharge-other",
            "posting_ids": ["posting-stored-value-other", "posting-bank-other"],
        }
    )
    state["postings"].extend(
        [
            {
                "id": "posting-stored-value-other",
                "posting_set_id": "posting-set-recharge-other",
                "account_id": "asset-stored-value",
                "amount": "20.00",
                "currency": "CNY",
                "role": "stored_value_asset",
                "reconciliation_eligible": True,
            },
            {
                "id": "posting-bank-other",
                "posting_set_id": "posting-set-recharge-other",
                "account_id": "asset-bank",
                "amount": "-20.00",
                "currency": "CNY",
                "role": "bank_payment",
                "reconciliation_eligible": True,
            },
        ]
    )
    state["domain_entities"].append(
        {
            "id": "lot-recharge-other",
            "type": "stored_value_lot",
            "payload": {
                "recharge_transaction_id": "transaction-recharge-other",
                "loaded_at": occurred_at,
                "face_value": "20.00",
                "currency": "CNY",
            },
        }
    )


def provenance_contract_case(confirmed: bool = True) -> dict:
    case = rg10_contract_case()
    state = case["states"][0]
    state["catalog"]["accounts"].extend(
        [
            {
                "id": "income-stored-value-bonus",
                "name": "Stored-value bonus",
                "kind": "income",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": False,
                "reconciliation_eligible": False,
                "system_role": "stored_value_bonus_right_income",
            },
            {
                "id": "expense-stored-value-expiry",
                "name": "Stored-value expiry",
                "kind": "expense",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": False,
                "reconciliation_eligible": False,
                "system_role": "stored_value_expiry_loss",
            },
        ]
    )
    stored_value_posting = next(
        item for item in state["postings"] if item["id"] == "posting-stored-value"
    )
    stored_value_posting["amount"] = "45.80"
    state["posting_sets"][0]["posting_ids"].append("posting-bonus-income")
    state["postings"].append(
        {
            "id": "posting-bonus-income",
            "posting_set_id": "posting-set-recharge",
            "account_id": "income-stored-value-bonus",
            "amount": "-10.00",
            "currency": "CNY",
            "role": "stored_value_bonus_income",
            "reconciliation_eligible": False,
        }
    )
    state["sources"][0]["payload"]["target_amount"] = "45.80"
    state["domain_entities"][0]["payload"]["face_value"] = "45.80"
    state["domain_entities"].extend(
        [
            {
                "id": "bonus-component-recharge",
                "type": "stored_value_bonus_component",
                "payload": {
                    "lot_id": "lot-recharge",
                    "recharge_transaction_id": "transaction-recharge",
                    "amount": "10.00",
                    "currency": "CNY",
                },
            },
            {
                "id": "expiry-event-recharge",
                "type": "stored_value_expiry_event",
                "payload": {
                    "lot_id": "lot-recharge",
                    "amount": "5.00",
                    "currency": "CNY",
                    "status_history": [
                        {
                            "id": "expiry-event-history-reminder",
                            "sequence": 1,
                            "status": "reminder",
                            "recorded_at": "2026-01-16T09:00:00+08:00",
                        }
                    ],
                },
            },
        ]
    )
    state["evidence_links"].append(
        {
            "id": "link-merchant-bonus",
            "evidence_id": "evidence-merchant-credit",
            "target_kind": "domain_entity",
            "target_id": "bonus-component-recharge",
            "role": "stored_value_bonus_component",
        }
    )
    if not confirmed:
        return case

    confirmed_at = "2026-01-17T09:00:00+08:00"
    state["transactions"].append(
        {
            "id": "transaction-expiry-loss",
            "type": "stored_value_expiry_loss",
            "current_version_id": "version-expiry-loss-v1",
        }
    )
    state["transaction_versions"].append(
        {
            "id": "version-expiry-loss-v1",
            "transaction_id": "transaction-expiry-loss",
            "version_number": 1,
            "posting_set_id": "posting-set-expiry-loss",
            "occurred_at": confirmed_at,
            "statistics_at": confirmed_at,
            "effective_at": confirmed_at,
        }
    )
    state["posting_sets"].append(
        {
            "id": "posting-set-expiry-loss",
            "posting_ids": ["posting-expiry-loss", "posting-stored-value-expiry"],
        }
    )
    state["postings"].extend(
        [
            {
                "id": "posting-expiry-loss",
                "posting_set_id": "posting-set-expiry-loss",
                "account_id": "expense-stored-value-expiry",
                "amount": "5.00",
                "currency": "CNY",
                "role": "stored_value_expiry_loss",
                "reconciliation_eligible": False,
            },
            {
                "id": "posting-stored-value-expiry",
                "posting_set_id": "posting-set-expiry-loss",
                "account_id": "asset-stored-value",
                "amount": "-5.00",
                "currency": "CNY",
                "role": "stored_value_asset",
                "reconciliation_eligible": True,
            },
        ]
    )
    state["domain_entities"][-1]["payload"]["status_history"].append(
        {
            "id": "expiry-event-history-confirmed",
            "sequence": 2,
            "status": "confirmed",
            "recorded_at": confirmed_at,
            "loss_transaction_id": "transaction-expiry-loss",
        }
    )
    state["sources"].append(
        {
            "id": "source-expiry-confirmation",
            "type": "explicit_balance_observation",
            "payload": {
                "account_id": "asset-stored-value",
                "target_amount": "40.80",
                "currency": "CNY",
                "target_observed_at": confirmed_at,
            },
        }
    )
    state["evidence"].append(
        {
            "id": "evidence-expiry-confirmation",
            "type": "confirmed_actual_expiry",
            "source_ids": ["source-expiry-confirmation"],
            "payload": {"observed_at": confirmed_at},
        }
    )
    state["evidence_links"].append(
        {
            "id": "link-expiry-confirmation",
            "evidence_id": "evidence-expiry-confirmation",
            "target_kind": "domain_entity",
            "target_id": "expiry-event-recharge",
            "role": "stored_value_expiry_confirmation",
        }
    )
    return case


def reconstruction_contract_case() -> dict:
    case = rg10_contract_case()
    state = case["states"][0]
    occurred_at = "2026-01-14T08:30:00+08:00"
    state["catalog"]["accounts"].append(
        {
            "id": "equity-pre-activation-adjustment",
            "name": "Pre-activation adjustment",
            "kind": "equity",
            "currency": "CNY",
            "owned_by_user": False,
            "real_account": False,
            "reconciliation_eligible": False,
            "system_role": "balance_adjustments",
        }
    )
    state["transactions"].append(
        {
            "id": "transaction-activation-adjustment",
            "type": "stored_value_pre_activation_balance_adjustment",
            "current_version_id": "version-activation-adjustment-v1",
        }
    )
    state["transaction_versions"].append(
        {
            "id": "version-activation-adjustment-v1",
            "transaction_id": "transaction-activation-adjustment",
            "version_number": 1,
            "posting_set_id": "posting-set-activation-adjustment",
            "occurred_at": occurred_at,
            "statistics_at": occurred_at,
            "effective_at": occurred_at,
        }
    )
    state["posting_sets"].append(
        {
            "id": "posting-set-activation-adjustment",
            "posting_ids": [
                "posting-activation-stored-value",
                "posting-activation-counterpart",
            ],
        }
    )
    state["postings"].extend(
        [
            {
                "id": "posting-activation-stored-value",
                "posting_set_id": "posting-set-activation-adjustment",
                "account_id": "asset-stored-value",
                "amount": "35.80",
                "currency": "CNY",
                "role": "stored_value_asset",
                "reconciliation_eligible": False,
            },
            {
                "id": "posting-activation-counterpart",
                "posting_set_id": "posting-set-activation-adjustment",
                "account_id": "equity-pre-activation-adjustment",
                "amount": "-35.80",
                "currency": "CNY",
                "role": "balance_adjustment_counterpart",
                "reconciliation_eligible": False,
            },
        ]
    )
    state["domain_entities"].extend(
        [
            {
                "id": "activation-adjustment-contract",
                "type": "activation_adjustment",
                "payload": {
                    "transaction_id": "transaction-activation-adjustment",
                },
            },
            {
                "id": "reconstruction-group-contract",
                "type": "stored_value_reconstruction",
                "payload": {
                    "adjustment_id": "activation-adjustment-contract",
                    "reconstructed_transaction_ids": ["transaction-recharge"],
                    "active_mode": "adjustment",
                    "history": [
                        {
                            "id": "reconstruction-history-1",
                            "sequence": 1,
                            "active_mode": "adjustment",
                            "confirmed_at": occurred_at,
                        }
                    ],
                },
            },
        ]
    )
    state["audit_links"].extend(
        [
            {
                "id": "audit-reconstruction-adjustment",
                "type": "reconstruction_adjustment",
                "from": {
                    "kind": "domain_entity",
                    "id": "reconstruction-group-contract",
                },
                "to": {
                    "kind": "domain_entity",
                    "id": "activation-adjustment-contract",
                },
                "payload": {},
            },
            {
                "id": "audit-reconstruction-transaction",
                "type": "reconstruction_transaction",
                "from": {
                    "kind": "domain_entity",
                    "id": "reconstruction-group-contract",
                },
                "to": {"kind": "transaction", "id": "transaction-recharge"},
                "payload": {},
            },
        ]
    )
    return case


def cross_group_adjustment_endpoint_case(activation_before_reconstruction: bool) -> dict:
    case = reconstruction_contract_case()
    add_second_rg10_recharge(case)
    state = case["states"][0]
    transaction = next(
        item
        for item in state["transactions"]
        if item["id"] == "transaction-recharge-other"
    )
    transaction["type"] = "stored_value_pre_activation_balance_adjustment"
    state["domain_entities"] = [
        item for item in state["domain_entities"] if item["id"] != "lot-recharge-other"
    ]

    reconstruction = next(
        item
        for item in state["domain_entities"]
        if item["id"] == "reconstruction-group-contract"
    )
    reconstruction["payload"]["reconstructed_transaction_ids"] = [
        "transaction-recharge-other"
    ]
    next(
        item
        for item in state["audit_links"]
        if item["type"] == "reconstruction_transaction"
    )["to"]["id"] = "transaction-recharge-other"

    other_adjustment = {
        "id": "activation-adjustment-other",
        "type": "activation_adjustment",
        "payload": {"transaction_id": "transaction-recharge-other"},
    }
    other_reconstruction = {
        "id": "reconstruction-group-other",
        "type": "stored_value_reconstruction",
        "payload": {
            "adjustment_id": "activation-adjustment-other",
            "reconstructed_transaction_ids": [],
            "active_mode": "adjustment",
            "history": [
                {
                    "id": "reconstruction-other-history-1",
                    "sequence": 1,
                    "active_mode": "adjustment",
                    "confirmed_at": "2026-01-16T09:00:00+08:00",
                }
            ],
        },
    }
    reconstruction_index = state["domain_entities"].index(reconstruction)
    insert_at = reconstruction_index if activation_before_reconstruction else len(
        state["domain_entities"]
    )
    state["domain_entities"][insert_at:insert_at] = [
        other_adjustment,
        other_reconstruction,
    ]
    state["audit_links"].append(
        {
            "id": "audit-reconstruction-other-adjustment",
            "type": "reconstruction_adjustment",
            "from": {"kind": "domain_entity", "id": "reconstruction-group-other"},
            "to": {"kind": "domain_entity", "id": "activation-adjustment-other"},
            "payload": {},
        }
    )
    return case


def validate_contract_state(case: dict, state_index: int = 0) -> None:
    errors = schema_errors(case)
    if errors:
        raise AssertionError(errors[0].message)
    state = case["states"][state_index]
    state_path = f"$.states[{state_index}]"
    timezone = ZoneInfo(case["case"]["timezone"])
    indexes = golden_v2._state_indexes(state, state_path)
    golden_v2._validate_catalog(state, state_path, indexes, {"CNY": 2})
    golden_v2._validate_formal_ledger(
        state, state_path, indexes, {"CNY": 2}, timezone
    )
    golden_v2._validate_references(
        state,
        state_path,
        indexes,
        {operation["id"]: operation for operation in case["operations"]},
        {"CNY": 2},
        timezone,
    )


def rg10_operation_inputs() -> dict[str, dict]:
    recharge = {
        "request_id": "request-recharge",
        "model": "stored_value_asset",
        "payment_account_id": "asset-bank",
        "stored_value_account_id": "asset-stored-value",
        "paid_amount": "35.80",
        "credited_amount": "45.80",
        "bonus_amount": "10.00",
        "currency": "CNY",
        "occurred_at": "2026-01-15T08:30:00+08:00",
        "created_at": "2026-01-15T08:31:00+08:00",
        "explicit_confirmation": True,
        "confirms_model": True,
        "confirms_payment_account": True,
        "confirms_stored_value_account": True,
        "confirms_paid_amount": True,
        "confirms_credited_amount": True,
        "confirms_bonus_amount": True,
        "confirms_actual_time": True,
        "confirms_lot_facts": True,
    }
    spend = {
        "request_id": "request-spend",
        "model": "stored_value_asset",
        "behavior": "stored_value_spend",
        "stored_value_account_id": "asset-stored-value",
        "category_id": "expense-category-meal",
        "amount": "5.00",
        "currency": "CNY",
        "occurred_at": "2026-01-16T08:30:00+08:00",
        "created_at": "2026-01-16T08:31:00+08:00",
        "explicit_confirmation": True,
        "confirms_model": True,
        "confirms_behavior": True,
        "confirms_stored_value_account": True,
        "confirms_amount": True,
        "confirms_actual_time": True,
        "confirms_category": True,
        "merchant_allocation_provided": False,
        "confirms_lot_allocation": True,
    }
    ingest_recharge = {
        key: value
        for key, value in recharge.items()
        if key
        in {
            "request_id", "model", "payment_account_id", "stored_value_account_id",
            "paid_amount", "credited_amount", "bonus_amount", "currency", "occurred_at",
        }
    }
    ingest_recharge.update(
        {"lot_id": "lot-recharge", "all_facts_complete": True, "explicit_confirmation": False}
    )
    ingest_spend = {
        key: value
        for key, value in spend.items()
        if key
        in {
            "request_id", "model", "behavior", "stored_value_account_id", "category_id",
            "amount", "currency", "occurred_at",
        }
    }
    ingest_spend.update(
        {
            "lot_allocations": [{"lot_id": "lot-recharge", "amount": "5.00"}],
            "all_facts_complete": True,
            "explicit_confirmation": False,
        }
    )
    return {
        "confirm_stored_value_recharge": recharge,
        "confirm_stored_value_spend": spend,
        "ingest_stored_value_recharge_candidate": ingest_recharge,
        "ingest_stored_value_spend_candidate": ingest_spend,
        "record_expiry_reminder": {
            "request_id": "request-reminder",
            "lot_id": "lot-recharge",
            "reminder_status": "expired_date_reached",
            "explicit_confirmation": False,
        },
        "confirm_stored_value_expiry_loss": {
            "request_id": "request-expiry",
            "lot_id": "lot-recharge",
            "amount": "5.00",
            "currency": "CNY",
            "occurred_at": "2026-01-17T08:30:00+08:00",
            "explicit_confirmation": True,
            "confirms_actual_expiry": True,
            "confirms_lot": True,
            "confirms_amount": True,
        },
        "reconcile_merchant_credit": {
            "source_id": "source-merchant-credit",
            "evidence_id": "evidence-merchant-credit",
            "role": "stored_value_credit_lot",
            "target_posting_id": "posting-stored-value",
            "explicit_confirmation": True,
        },
        "reconcile_bank_payment": {
            "source_id": "source-bank-payment",
            "evidence_id": "evidence-bank-payment",
            "role": "bank_payment_posting",
            "target_posting_id": "posting-bank",
            "explicit_confirmation": True,
        },
        "apply_merchant_lot_allocation": {
            "request_id": "request-allocation",
            "amount": "5.00",
            "merchant_allocation_provided": True,
            "merchant_evidence_id": "evidence-merchant-credit",
            "allocations": [{"lot_id": "lot-recharge", "amount": "5.00"}],
            "explicit_confirmation": True,
        },
        "confirm_stored_value_activation_balance": {
            "request_id": "request-activation",
            "stored_value_account_id": "asset-stored-value",
            "existing_balance": "45.80",
            "currency": "CNY",
            "activation_at": "2026-01-15T08:30:00+08:00",
            "created_at": "2026-01-15T08:31:00+08:00",
            "explicit_confirmation": True,
            "composition_confirmed": False,
        },
        "rename_stored_value_labels": {
            "account_id": "asset-stored-value",
            "new_account_name": "Stored value renamed",
            "lot_id": "lot-recharge",
            "new_lot_label": "Lot renamed",
        },
    }


def rg10_operation_shell(
    action: str, operation_class: str, status: str, payload: dict
) -> dict:
    operation = deepcopy(load_rg01()["operations"][0])
    operation["action_type"] = action
    operation["operation_class"] = operation_class
    if status == "rejected":
        operation.pop("input", None)
        operation["attempted_input"] = payload
        operation["outcome"] = {
            "status": "rejected",
            "reason_code": "invalid_attempt",
            "field_path": f"$.attempted_input.{next(iter(payload))}",
        }
    else:
        operation.pop("attempted_input", None)
        operation["input"] = payload
        operation["outcome"] = (
            {"status": "accepted"}
            if status == "accepted"
            else {"status": "no_change", "reason_code": "idempotent_replay"}
        )
    return operation


def rg10_rejection_baseline() -> dict:
    baseline = rg10_contract_case()["states"][0]
    baseline["catalog"]["accounts"].extend(
        [
            {
                "id": "asset-stored-value-disabled",
                "name": "Disabled stored value",
                "kind": "asset",
                "currency": "CNY",
                "owned_by_user": True,
                "real_account": True,
                "reconciliation_eligible": True,
                "stored_value": {
                    "enabled": False,
                    "merchant_restricted": True,
                    "merchant_id": "merchant-disabled",
                },
            },
            {
                "id": "asset-bank-external",
                "name": "External bank",
                "kind": "asset",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": True,
                "reconciliation_eligible": False,
            },
            {
                "id": "expense-stored-value",
                "name": "Stored-value expense",
                "kind": "expense",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": False,
                "reconciliation_eligible": False,
            },
        ]
    )
    baseline["catalog"]["categories"] = [
        {
            "id": "expense-category-parent",
            "name": "Parent",
            "parent_id": None,
            "posting_account_id": None,
            "active": True,
        },
        {
            "id": "expense-category-meal",
            "name": "Meal",
            "parent_id": "expense-category-parent",
            "posting_account_id": "expense-stored-value",
            "active": True,
        },
    ]
    baseline["balances"] = [
        {
            "account_id": "asset-stored-value",
            "currency": "CNY",
            "amount": "35.80",
        }
    ]
    return baseline


def assert_rg10_rejection_predicate(
    test: unittest.TestCase,
    action: str,
    attempted_input: dict,
    reason_code: str,
    field: str,
) -> None:
    operation = {
        "action_type": action,
        "outcome": {
            "status": "rejected",
            "reason_code": reason_code,
            "field_path": f"$.attempted_input.{field}",
        },
        "attempted_input": deepcopy(attempted_input),
    }
    golden_v2._validate_action_input(
        operation,
        "$.operation",
        rg10_rejection_baseline(),
        {"CNY": 2},
        ZoneInfo("Asia/Shanghai"),
    )


def rg10_full_rejection_case(
    action: str,
    attempted_input: dict,
    reason_code: str,
    field: str,
) -> dict:
    case = load_rg01()
    extra_accounts = [
        {
            "id": "asset-stored-value-x",
            "name": "Stored value X",
            "kind": "asset",
            "currency": "CNY",
            "owned_by_user": True,
            "real_account": True,
            "reconciliation_eligible": True,
            "stored_value": {
                "enabled": True,
                "merchant_restricted": True,
                "merchant_id": "merchant-x",
            },
        },
        {
            "id": "asset-stored-value-disabled",
            "name": "Disabled stored value",
            "kind": "asset",
            "currency": "CNY",
            "owned_by_user": True,
            "real_account": True,
            "reconciliation_eligible": True,
            "stored_value": {
                "enabled": False,
                "merchant_restricted": True,
                "merchant_id": "merchant-disabled",
            },
        },
        {
            "id": "asset-bank-external",
            "name": "External bank",
            "kind": "asset",
            "currency": "CNY",
            "owned_by_user": False,
            "real_account": True,
            "reconciliation_eligible": False,
        },
        {
            "id": "expense-consumption-rg10",
            "name": "Stored-value expense",
            "kind": "expense",
            "currency": "CNY",
            "owned_by_user": False,
            "real_account": False,
            "reconciliation_eligible": False,
        },
    ]
    for state in case["states"]:
        state["catalog"]["accounts"].extend(deepcopy(extra_accounts))
        state["balances"].extend(
            {
                "account_id": account["id"],
                "currency": account["currency"],
                "amount": "0.00",
            }
            for account in extra_accounts
        )
    case = add_rg01_rejected_attempt(attempted_input, field, reason_code, case)
    case["operations"][-1]["action_type"] = action
    return case


def reconciliation_contract_case(statuses: list[str] | None) -> dict:
    case = load_rg01()
    state = deepcopy(case["states"][0])
    state["id"] = "state-reconciliation-contract"
    case["states"] = [state]
    case["operations"] = []
    case["roots"][0]["initial_state_id"] = state["id"]
    case["roots"][0]["operation_ids"] = []

    second_account = next(
        item for item in state["catalog"]["accounts"] if item["id"] == "equity-opening-a"
    )
    second_account.update(
        {
            "kind": "asset",
            "owned_by_user": True,
            "real_account": True,
            "reconciliation_eligible": True,
        }
    )
    for posting in state["postings"]:
        posting["reconciliation_eligible"] = True

    state["posting_reconciliations"] = []
    state["derived_statuses"] = []
    if statuses is not None:
        for posting, status in zip(state["postings"], statuses, strict=True):
            state["posting_reconciliations"].append(
                {
                    "id": f"reconciliation-{posting['id']}",
                    "posting_id": posting["id"],
                    "status": status,
                }
            )
        summary = "matched" if all(item == "matched" for item in statuses) else "partial"
        state["derived_statuses"].append(
            {
                "id": "derived-reconciliation-contract",
                "target_kind": "transaction",
                "target_id": "tx-opening-a",
                "status_name": "reconciliation_summary",
                "value": summary,
            }
        )
    return case


def assert_invalid(test: unittest.TestCase, case: dict, path: str) -> None:
    with test.assertRaisesRegex(GoldenCaseError, path):
        validate_golden_case_v2(case)


def replace_timestamp_offset(value, old: str, new: str):
    if isinstance(value, dict):
        return {key: replace_timestamp_offset(item, old, new) for key, item in value.items()}
    if isinstance(value, list):
        return [replace_timestamp_offset(item, old, new) for item in value]
    if isinstance(value, str) and value.endswith(old):
        return value[: -len(old)] + new
    return value


def add_rg01_no_change_retry(case: dict | None = None) -> dict:
    case = deepcopy(case) if case is not None else load_rg01()
    baseline = case["states"][-1]
    result = deepcopy(baseline)
    result["id"] = "state-rg01-retry"
    result["as_of_operation_id"] = "operation-rg01-retry"
    case["states"].append(result)

    retry = deepcopy(case["operations"][0])
    retry["id"] = "operation-rg01-retry"
    retry["sequence"] = 3
    retry["baseline_state_id"] = baseline["id"]
    retry["result_state_id"] = result["id"]
    retry["outcome"] = {"status": "no_change", "reason_code": "idempotent_replay"}
    retry["status_changes"] = []
    for changes in retry["deltas"]["entity_changes"].values():
        changes["added_ids"] = []
        changes["changed_ids"] = []
        changes["removed_ids"] = []
    retry["deltas"]["value_changes"] = {
        "balances": [],
        "reports": [],
        "derived_statuses": [],
    }
    case["operations"].append(retry)
    case["roots"][0]["operation_ids"].append(retry["id"])
    return case


def add_rg01_rejected_attempt(
    attempted_input: dict,
    field: str,
    reason_code: str,
    case: dict | None = None,
) -> dict:
    case = deepcopy(case) if case is not None else load_rg01()
    baseline = case["states"][-1]
    result = deepcopy(baseline)
    result["id"] = "state-rg01-rejected"
    result["as_of_operation_id"] = "operation-rg01-rejected"
    case["states"].append(result)

    operation = deepcopy(case["operations"][0])
    operation["id"] = "operation-rg01-rejected"
    operation["sequence"] = 3
    operation["operation_class"] = "rejection"
    operation["baseline_state_id"] = baseline["id"]
    operation["result_state_id"] = result["id"]
    operation.pop("input")
    operation["attempted_input"] = deepcopy(attempted_input)
    operation["outcome"] = {
        "status": "rejected",
        "reason_code": reason_code,
        "field_path": f"$.attempted_input.{field}",
    }
    operation["status_changes"] = []
    for changes in operation["deltas"]["entity_changes"].values():
        changes["added_ids"] = []
        changes["changed_ids"] = []
        changes["removed_ids"] = []
    operation["deltas"]["value_changes"] = {
        "balances": [],
        "reports": [],
        "derived_statuses": [],
    }
    operation["returned_ids"] = []
    case["operations"].append(operation)
    case["roots"][0]["operation_ids"].append(operation["id"])
    return case


def add_rg09_note_update_with_side_effects() -> dict:
    case = load_rg09()
    baseline = case["states"][-1]
    result = deepcopy(baseline)
    result["id"] = "state-rg09-note-updated"
    result["as_of_operation_id"] = "operation-rg09-note-update"

    transaction = next(
        item
        for item in result["transactions"]
        if item["id"] == "transaction-transfer-rg09"
    )
    old_version = next(
        item
        for item in result["transaction_versions"]
        if item["id"] == transaction["current_version_id"]
    )
    new_version = deepcopy(old_version)
    new_version["id"] = "version-transfer-rg09-v2"
    new_version["version_number"] = 2
    new_version["note"] = "Reviewed"
    new_version["confirmation_id"] = "confirmation-rg09-note-update"
    result["transaction_versions"].append(new_version)
    transaction["current_version_id"] = new_version["id"]

    confirmation = {
        "id": "confirmation-rg09-note-update",
        "type": "explicit_manual_save",
        "operation_id": "operation-rg09-note-update",
        "subject": {"kind": "operation", "id": "operation-rg09-note-update"},
        "payload": {},
    }
    source = {
        "id": "source-rg09-note-side-effect",
        "type": "explicit_balance_observation",
        "payload": {
            "account_id": "asset-a",
            "target_amount": "130.00",
            "currency": "CNY",
            "target_observed_at": "2026-01-31T23:59:59+08:00",
        },
    }
    evidence = {
        "id": "evidence-rg09-note-side-effect",
        "type": "user_balance_observation",
        "source_ids": [source["id"]],
        "payload": {"observed_at": "2026-01-31T23:59:59+08:00"},
    }
    observation = {
        "id": "observation-rg09-note-side-effect",
        "type": "target_balance_observation",
        "payload": {
            "account_id": "asset-a",
            "target_amount": "130.00",
            "currency": "CNY",
            "observed_at": "2026-01-31T23:59:59+08:00",
            "source_id": source["id"],
        },
    }
    evidence_link = {
        "id": "evidence-link-rg09-note-side-effect",
        "evidence_id": evidence["id"],
        "target_kind": "observation",
        "target_id": observation["id"],
        "role": "target_balance_observation",
    }
    audit_link = {
        "id": "audit-rg09-note-side-effect",
        "type": "adjustment_transaction",
        "from": {"kind": "domain_entity", "id": "adjustment-rg09"},
        "to": {"kind": "transaction", "id": "transaction-adjustment-rg09"},
        "payload": {},
    }
    result["confirmations"].append(confirmation)
    result["sources"].append(source)
    result["evidence"].append(evidence)
    result["domain_entities"].append(observation)
    result["evidence_links"].append(evidence_link)
    result["audit_links"].append(audit_link)
    case["states"].append(result)

    operation = deepcopy(case["operations"][-1])
    operation.update(
        {
            "id": "operation-rg09-note-update",
            "sequence": 5,
            "operation_class": "update",
            "action_type": "transaction_note_update",
            "baseline_state_id": baseline["id"],
            "result_state_id": result["id"],
            "input": {
                "request_id": "request-rg09-note-update",
                "transaction_id": "transaction-transfer-rg09",
                "note": "Reviewed",
                "explicit_confirmation": True,
            },
            "outcome": {"status": "accepted"},
            "status_changes": [],
            "returned_ids": [
                {"kind": "transaction", "id": "transaction-transfer-rg09"},
                {"kind": "transaction_version", "id": new_version["id"]},
            ],
        }
    )
    for changes in operation["deltas"]["entity_changes"].values():
        changes["added_ids"] = []
        changes["changed_ids"] = []
        changes["removed_ids"] = []
    operation["deltas"]["value_changes"] = {
        "balances": [],
        "reports": [],
        "derived_statuses": [],
    }
    entity_changes = operation["deltas"]["entity_changes"]
    entity_changes["transactions"]["changed_ids"] = [transaction["id"]]
    entity_changes["transaction_versions"]["added_ids"] = [new_version["id"]]
    entity_changes["confirmations"]["added_ids"] = [confirmation["id"]]
    entity_changes["sources"]["added_ids"] = [source["id"]]
    entity_changes["evidence"]["added_ids"] = [evidence["id"]]
    entity_changes["domain_entities"]["added_ids"] = [observation["id"]]
    entity_changes["evidence_links"]["added_ids"] = [evidence_link["id"]]
    entity_changes["audit_links"]["added_ids"] = [audit_link["id"]]
    case["operations"].append(operation)
    case["roots"][0]["operation_ids"].append(operation["id"])
    return case


class GoldenV2SchemaTests(unittest.TestCase):
    def test_requirements_include_windows_tzdata_fallback(self):
        requirements = REQUIREMENTS_PATH.read_text(encoding="utf-8").splitlines()
        self.assertIn("jsonschema>=4.23,<5", requirements)
        self.assertTrue(
            any(
                line.startswith("tzdata>=2026.2,<2027")
                and 'platform_system == "Windows"' in line
                for line in requirements
            ),
            requirements,
        )

    def test_schema_is_valid_draft_2020_12(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
        Draft202012Validator.check_schema(schema)

    def test_representative_examples_validate(self):
        for path in (RG01_V2_PATH, RG09_V2_PATH):
            with self.subTest(path=path.name):
                validate_golden_case_v2(load_golden_case_v2(path))

    def test_rg01_path_map_records_resolved_contract_gap_counts(self):
        path_map = json.loads(RG01_PATH_MAP.read_text(encoding="utf-8"))
        self.assertEqual(path_map["contract_gap_count"], 0)
        self.assertEqual(path_map["resolved_contract_gap_count"], 4)
        self.assertEqual(len(path_map["resolved_contract_gaps"]), 4)

        entries = {item["source_path"]: item for item in path_map["entries"]}
        expected_count_targets = {
            "$.distinct_reentry.expected.effective_transaction_count": [
                "$.states[*].transactions",
                "$.states[*].transactions[*].current_version_id",
            ],
            "$.distinct_reentry.expected.new_transaction_count": [
                "$.operations[*].deltas.entity_changes.transactions.added_ids"
            ],
            "$.idempotency.expected.funding_effect_count": [
                "$.states[*].postings",
                "$.operations[*].deltas.entity_changes.postings.added_ids",
            ],
            "$.idempotency.expected.new_posting_set_count": [
                "$.operations[*].deltas.entity_changes.posting_sets.added_ids"
            ],
            "$.idempotency.expected.new_transaction_count": [
                "$.operations[*].deltas.entity_changes.transactions.added_ids"
            ],
            "$.idempotency.expected.new_version_count": [
                "$.operations[*].deltas.entity_changes.transaction_versions.added_ids"
            ],
            "$.invalid_inputs[*].expected.new_posting_count": [
                "$.operations[*].deltas.entity_changes.postings.added_ids"
            ],
            "$.invalid_inputs[*].expected.new_transaction_count": [
                "$.operations[*].deltas.entity_changes.transactions.added_ids"
            ],
            "$.invalid_inputs[*].expected.state_changes.new_version_count": [
                "$.operations[*].deltas.entity_changes.transaction_versions.added_ids"
            ],
            "$.note_update.expected.effective_transaction_count": [
                "$.states[*].transactions",
                "$.states[*].transactions[*].current_version_id",
            ],
            "$.note_update.expected.funding_effect_count": [
                "$.states[*].postings",
                "$.operations[*].deltas.entity_changes.postings.added_ids",
            ],
        }
        for source_path, target_paths in expected_count_targets.items():
            with self.subTest(source_path=source_path):
                self.assertEqual(entries[source_path]["target_paths"], target_paths)

    def test_approval_status_allows_only_review_draft_or_explicit_approval(self):
        approved = load_rg01()
        approved["case"]["approval_status"] = "approved"
        validate_golden_case_v2(approved)

        invalid = load_rg01()
        invalid["case"]["approval_status"] = "frozen"
        assert_invalid(self, invalid, r"\$\.case\.approval_status")

    def test_operation_input_shape_is_outcome_conditional(self):
        accepted_sparse = load_rg01()
        del accepted_sparse["operations"][0]["input"]["currency"]
        assert_invalid(self, accepted_sparse, r"\$\.operations\[0\].*input")

        accepted_with_attempt = load_rg01()
        accepted_with_attempt["operations"][0]["attempted_input"] = {
            "request_id": "unexpected-attempt"
        }
        assert_invalid(
            self, accepted_with_attempt, r"\$\.operations\[0\].*attempted_input"
        )

        rejected = add_rg01_rejected_attempt(
            {
                "request_id": "request-rg01-rejected",
                "amount": None,
                "category_id": "expense-category-breakfast",
                "payment_account_id": "asset-bank-a",
            },
            "amount",
            "missing_required_field",
        )
        validate_golden_case_v2(rejected)

        with_strict_input = deepcopy(rejected)
        with_strict_input["operations"][-1]["input"] = deepcopy(
            load_rg01()["operations"][0]["input"]
        )
        assert_invalid(self, with_strict_input, r"\$\.operations\[2\].*input")

        unknown_attempted_field = deepcopy(rejected)
        unknown_attempted_field["operations"][-1]["attempted_input"]["unexpected"] = True
        assert_invalid(
            self,
            unknown_attempted_field,
            r"\$\.operations\[2\].*attempted_input.*unexpected",
        )

    def test_rg10_operation_registry_closes_action_class_outcome_and_payload_dispatch(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$ref": "#/$defs/operation", "$defs": schema["$defs"]}
        )
        inputs = rg10_operation_inputs()
        classes = {
            "confirm_stored_value_recharge": "creation",
            "confirm_stored_value_spend": "creation",
            "ingest_stored_value_recharge_candidate": "creation",
            "ingest_stored_value_spend_candidate": "creation",
            "record_expiry_reminder": "status_transition",
            "confirm_stored_value_expiry_loss": "creation",
            "reconcile_merchant_credit": "reconciliation",
            "reconcile_bank_payment": "reconciliation",
            "apply_merchant_lot_allocation": "update",
            "confirm_stored_value_activation_balance": "adjustment",
            "rename_stored_value_labels": "update",
        }
        for action, operation_class in classes.items():
            for status in ("accepted", "no_change"):
                operation = rg10_operation_shell(
                    action, operation_class, status, inputs[action]
                )
                with self.subTest(action=action, status=status):
                    self.assertEqual(list(validator.iter_errors(operation)), [])

        rejected = {
            "confirm_stored_value_recharge": ({"paid_amount": "0.00"}, "must_be_positive", "paid_amount"),
            "confirm_stored_value_spend": (
                {"paid_bonus_composition": "paid_first"},
                "paid_bonus_composition_must_be_evidenced",
                "paid_bonus_composition",
            ),
            "confirm_imported_stored_value_recharge": (
                {"bank_payment_confirmed": False},
                "bank_payment_model_and_all_recharge_facts_required",
                "bank_payment_confirmed",
            ),
            "confirm_imported_stored_value_spend": (
                {"category_confirmed": False},
                "spend_category_and_behavior_confirmation_required",
                "category_confirmed",
            ),
            "confirm_stored_value_expiry_loss": (
                {"explicit_confirmation": False},
                "actual_expiry_requires_explicit_confirmation",
                "explicit_confirmation",
            ),
            "apply_merchant_lot_allocation": (
                {"amount": "901.00"},
                "lot_allocation_exceeds_remaining_face_value",
                "amount",
            ),
        }
        for action, (attempted_input, reason_code, field) in rejected.items():
            operation = rg10_operation_shell(
                action, "rejection", "rejected", attempted_input
            )
            operation["outcome"] = {
                "status": "rejected",
                "reason_code": reason_code,
                "field_path": f"$.attempted_input.{field}",
            }
            with self.subTest(action=action, status="rejected"):
                self.assertEqual(list(validator.iter_errors(operation)), [])

        wrong_class = rg10_operation_shell(
            "record_expiry_reminder",
            "creation",
            "accepted",
            inputs["record_expiry_reminder"],
        )
        self.assertTrue(list(validator.iter_errors(wrong_class)))

        wrong_outcome = rg10_operation_shell(
            "confirm_stored_value_recharge",
            "creation",
            "accepted",
            inputs["confirm_stored_value_recharge"],
        )
        wrong_outcome["outcome"] = {
            "status": "rejected",
            "reason_code": "invalid_attempt",
            "field_path": "$.attempted_input.paid_amount",
        }
        self.assertTrue(list(validator.iter_errors(wrong_outcome)))

        for field in ("unexpected", "kind"):
            unknown = rg10_operation_shell(
                "confirm_stored_value_spend",
                "creation",
                "accepted",
                inputs["confirm_stored_value_spend"],
            )
            unknown["input"][field] = "stored_value_spend"
            with self.subTest(field=field):
                self.assertTrue(list(validator.iter_errors(unknown)))

        mixed = rg10_operation_shell(
            "confirm_stored_value_recharge",
            "creation",
            "accepted",
            inputs["confirm_stored_value_recharge"],
        )
        mixed["attempted_input"] = {"paid_amount": "0.00"}
        self.assertTrue(list(validator.iter_errors(mixed)))

        unknown_attempt = rg10_operation_shell(
            "confirm_stored_value_recharge",
            "rejection",
            "rejected",
            {"paid_amount": "0.00"},
        )
        unknown_attempt["attempted_input"]["unexpected"] = True
        self.assertTrue(list(validator.iter_errors(unknown_attempt)))

        generic_retry = rg10_operation_shell(
            "retry", "creation", "no_change", inputs["confirm_stored_value_recharge"]
        )
        self.assertTrue(list(validator.iter_errors(generic_retry)))

    def test_rg10_rejection_schema_registers_exact_reason_and_field_pairs(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$ref": "#/$defs/operation", "$defs": schema["$defs"]}
        )
        cases = [
            (
                "confirm_stored_value_recharge",
                {"paid_amount": 1000.0},
                "exact_decimal_string_required",
                "paid_amount",
            ),
            (
                "confirm_stored_value_spend",
                {"paid_bonus_composition": "paid_first"},
                "paid_bonus_composition_must_be_evidenced",
                "paid_bonus_composition",
            ),
            (
                "confirm_imported_stored_value_recharge",
                {"bank_payment_confirmed": False},
                "bank_payment_model_and_all_recharge_facts_required",
                "bank_payment_confirmed",
            ),
            (
                "confirm_imported_stored_value_spend",
                {"category_confirmed": False},
                "spend_category_and_behavior_confirmation_required",
                "category_confirmed",
            ),
            (
                "confirm_stored_value_expiry_loss",
                {"explicit_confirmation": False},
                "actual_expiry_requires_explicit_confirmation",
                "explicit_confirmation",
            ),
            (
                "apply_merchant_lot_allocation",
                {"amount": "901.00"},
                "lot_allocation_exceeds_remaining_face_value",
                "amount",
            ),
        ]
        for action, attempted_input, reason_code, field in cases:
            operation = rg10_operation_shell(
                action, "rejection", "rejected", attempted_input
            )
            operation["outcome"] = {
                "status": "rejected",
                "reason_code": reason_code,
                "field_path": f"$.attempted_input.{field}",
            }
            with self.subTest(action=action, reason_code=reason_code):
                self.assertEqual(list(validator.iter_errors(operation)), [])

            wrong_reason = deepcopy(operation)
            wrong_reason["outcome"]["reason_code"] = "invalid_attempt"
            self.assertTrue(list(validator.iter_errors(wrong_reason)))

            wrong_field = deepcopy(operation)
            wrong_field["outcome"]["field_path"] = "$.attempted_input.request_id"
            self.assertTrue(list(validator.iter_errors(wrong_field)))

    def test_rg10_numeric_attempts_are_only_representable_for_frozen_recharge_failures(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$ref": "#/$defs/operation", "$defs": schema["$defs"]}
        )
        cases = [
            (
                "confirm_stored_value_spend",
                {"amount": 36.0},
                "insufficient_effective_stored_balance",
                "amount",
            ),
            (
                "confirm_imported_stored_value_recharge",
                {"paid_amount": 1000.0, "bank_payment_confirmed": False},
                "bank_payment_model_and_all_recharge_facts_required",
                "bank_payment_confirmed",
            ),
            (
                "confirm_imported_stored_value_spend",
                {"amount": 5.0, "category_confirmed": False},
                "spend_category_and_behavior_confirmation_required",
                "category_confirmed",
            ),
            (
                "confirm_stored_value_expiry_loss",
                {"amount": 5.0, "explicit_confirmation": False},
                "actual_expiry_requires_explicit_confirmation",
                "explicit_confirmation",
            ),
            (
                "apply_merchant_lot_allocation",
                {"amount": 36.0},
                "lot_allocation_exceeds_remaining_face_value",
                "amount",
            ),
        ]
        for action, attempted_input, reason_code, field in cases:
            operation = rg10_operation_shell(
                action, "rejection", "rejected", attempted_input
            )
            operation["outcome"] = {
                "status": "rejected",
                "reason_code": reason_code,
                "field_path": f"$.attempted_input.{field}",
            }
            with self.subTest(action=action):
                self.assertTrue(list(validator.iter_errors(operation)))

        lexical_before_category = rg10_operation_shell(
            "confirm_stored_value_spend",
            "rejection",
            "rejected",
            {"amount": "1.0", "category_id": "category-unknown"},
        )
        lexical_before_category["outcome"] = {
            "status": "rejected",
            "reason_code": "active_secondary_category_required",
            "field_path": "$.attempted_input.category_id",
        }
        self.assertTrue(list(validator.iter_errors(lexical_before_category)))

    def test_rejected_operation_requires_rejection_class_and_located_reason(self):
        base = add_rg01_rejected_attempt(
            {"request_id": "request-rg01-rejected", "amount": None},
            "amount",
            "missing_required_field",
        )

        wrong_class = deepcopy(base)
        wrong_class["operations"][-1]["operation_class"] = "creation"
        assert_invalid(self, wrong_class, r"\$\.operations\[2\]")

        missing_field_path = deepcopy(base)
        del missing_field_path["operations"][-1]["outcome"]["field_path"]
        assert_invalid(self, missing_field_path, r"\$\.operations\[2\].*outcome")

        bad_field_path = deepcopy(base)
        bad_field_path["operations"][-1]["outcome"]["field_path"] = "$.input.amount"
        assert_invalid(self, bad_field_path, r"\$\.operations\[2\].*outcome.*field_path")

    def test_loader_rejects_duplicate_object_keys(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text(
                '{"contract":"unifiedledger.golden-case",'
                '"contract":"unifiedledger.golden-case",'
                '"contract_version":"2.0.0"}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(GoldenCaseError, r"\$\.contract.*duplicate"):
                load_golden_case_v2(path)

    def test_v2_loader_rejects_v1_and_v1_loader_is_unchanged(self):
        v1 = load_golden_case(RG01_V1_PATH)
        self.assertEqual(v1["schema_version"], 1)
        with self.assertRaisesRegex(GoldenCaseError, r"\$\.contract"):
            load_golden_case_v2(RG01_V1_PATH)

    def test_schema_rejects_unknown_field_type_action_and_action_input(self):
        cases = []

        unknown_field = load_rg01()
        unknown_field["states"][0]["unexpected"] = True
        cases.append((unknown_field, r"\$\.states\[0\].*unexpected"))

        unknown_type = load_rg01()
        unknown_type["states"][1]["transactions"][0]["type"] = "unregistered"
        cases.append((unknown_type, r"\$\.states\[1\]\.transactions\[0\]\.type"))

        unknown_action = load_rg01()
        unknown_action["operations"][0]["action_type"] = "unregistered"
        cases.append((unknown_action, r"\$\.operations\[0\]"))

        unknown_input = load_rg01()
        unknown_input["operations"][0]["input"]["unexpected"] = True
        cases.append((unknown_input, r"\$\.operations\[0\]\.input.*unexpected"))

        for case, path in cases:
            with self.subTest(path=path):
                assert_invalid(self, case, path)

    def test_semantic_prototype_rejects_registered_but_unimplemented_case_types(self):
        case = load_rg01()
        case["states"][1]["transactions"][0]["type"] = "income"
        assert_invalid(self, case, r"\$\.states\[1\]\.transactions\[0\]\.type")

    def test_schema_binds_derived_status_name_target_and_value(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema)

        invalid_value = load_rg09()
        invalid_value["states"][-1]["derived_statuses"][0]["value"] = "pending"
        self.assertTrue(list(validator.iter_errors(invalid_value)))

        invalid_target = load_rg09()
        invalid_target["states"][-1]["derived_statuses"][0]["target_kind"] = "transaction"
        self.assertTrue(list(validator.iter_errors(invalid_target)))

    def test_schema_binds_evidence_roles_to_target_kinds(self):
        case = load_rg09()
        for state in case["states"]:
            for link in state["evidence_links"]:
                link["role"] = "refund_relationship"
        assert_invalid(self, case, r"\$\.states\[1\]\.evidence_links\[0\]")

    def test_rg04_posting_roles_are_closed_and_registered(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$defs": schema["$defs"], "$ref": "#/$defs/posting"}
        )
        roles = (
            "mixed_expense_asset_funding",
            "mixed_expense_credit_funding",
            "credit_repayment_asset_outflow",
            "credit_repayment_liability_principal",
        )
        posting = {
            "id": "posting-rg04",
            "posting_set_id": "posting-set-rg04",
            "account_id": "account-rg04",
            "amount": "1.00",
            "currency": "CNY",
            "reconciliation_eligible": True,
        }
        for role in roles:
            with self.subTest(role=role):
                accepted = deepcopy(posting)
                accepted["role"] = role
                self.assertTrue(validator.is_valid(accepted))
                rejected = deepcopy(accepted)
                rejected["role"] = role[:-1]
                self.assertFalse(validator.is_valid(rejected))

    def test_rg04_financial_evidence_variants_are_closed(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$defs": schema["$defs"], "$ref": "#/$defs/evidence"}
        )
        for evidence_type in ("asset_funding_debit", "credit_liability_mirror"):
            evidence = {
                "id": f"evidence-{evidence_type}",
                "type": evidence_type,
                "source_ids": ["source-rg04"],
                "payload": {"observed_at": "2026-01-20T10:00:00+08:00"},
            }
            with self.subTest(evidence_type=evidence_type):
                self.assertTrue(validator.is_valid(evidence))
                for source_ids in ([], ["source-rg04", "source-rg04"]):
                    invalid = deepcopy(evidence)
                    invalid["source_ids"] = source_ids
                    self.assertFalse(validator.is_valid(invalid))
                missing = deepcopy(evidence)
                del missing["payload"]["observed_at"]
                self.assertFalse(validator.is_valid(missing))
                extra = deepcopy(evidence)
                extra["payload"]["unexpected"] = True
                self.assertFalse(validator.is_valid(extra))

    def test_rg04_mixed_payment_relation_is_closed(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(
            {"$defs": schema["$defs"], "$ref": "#/$defs/relation"}
        )
        relation = {
            "id": "relation-rg04",
            "type": "mixed_payment",
            "member_refs": [
                {"kind": "transaction", "id": "transaction-rg04"},
                {"kind": "posting", "id": "posting-asset-rg04"},
                {"kind": "posting", "id": "posting-credit-rg04"},
            ],
            "payload": {
                "system_managed": True,
                "display_name": "Mixed payment",
                "generic_order_lifecycle": False,
                "payment_composition_total": "120.00",
                "funding_components": [
                    {"account_id": "asset-rg04", "funding_amount": "70.00", "currency": "CNY", "posting_id": "posting-asset-rg04"},
                    {"account_id": "credit-rg04", "funding_amount": "50.00", "currency": "CNY", "posting_id": "posting-credit-rg04"},
                ],
            },
        }
        self.assertTrue(validator.is_valid(relation))
        invalid = deepcopy(relation)
        invalid["extra"] = True
        self.assertFalse(validator.is_valid(invalid))
        invalid = deepcopy(relation)
        invalid["payload"]["funding_components"][0]["id"] = "component-rg04"
        self.assertFalse(validator.is_valid(invalid))
        for field, value in (("type", "mixed-payment"), ("system_managed", False), ("generic_order_lifecycle", True)):
            invalid = deepcopy(relation)
            target = invalid if field == "type" else invalid["payload"]
            target[field] = value
            self.assertFalse(validator.is_valid(invalid), field)
        duplicate_member_refs = deepcopy(relation["member_refs"])
        duplicate_member_refs[2] = deepcopy(duplicate_member_refs[1])
        three_postings = [
            {"kind": "posting", "id": "posting-asset-rg04"},
            {"kind": "posting", "id": "posting-credit-rg04"},
            {"kind": "posting", "id": "posting-extra-rg04"},
        ]
        two_transactions = [
            {"kind": "transaction", "id": "transaction-rg04"},
            {"kind": "transaction", "id": "transaction-extra-rg04"},
            {"kind": "posting", "id": "posting-asset-rg04"},
        ]
        for members in (
            relation["member_refs"][:2],
            duplicate_member_refs,
            relation["member_refs"] + [deepcopy(relation["member_refs"][0])],
            three_postings,
            two_transactions,
        ):
            invalid = deepcopy(relation)
            invalid["member_refs"] = members
            self.assertFalse(validator.is_valid(invalid))
        for components in (relation["payload"]["funding_components"][:1], relation["payload"]["funding_components"] * 2):
            invalid = deepcopy(relation)
            invalid["payload"]["funding_components"] = components
            self.assertFalse(validator.is_valid(invalid))
        for amount in ("0.00", "-1.00"):
            invalid = deepcopy(relation)
            invalid["payload"]["funding_components"][0]["funding_amount"] = amount
            self.assertFalse(validator.is_valid(invalid))

    def test_rg04_mixed_payment_relation_semantics(self):
        state, context = mixed_payment_relation_fixture()
        golden_v2._validate_relations(
            state, "$.states[0]", context["indexes"], context["current"], context["precisions"]
        )

        mutations = []
        invalid = deepcopy(state)
        invalid["relations"][0]["member_refs"][2] = deepcopy(invalid["relations"][0]["member_refs"][1])
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["member_refs"][1]["id"] = "posting-missing"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["member_refs"] = invalid["relations"][0]["member_refs"][:2]
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["role"] = "expense"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["transactions"][0]["type"] = "account_transfer"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["catalog"]["accounts"][0]["owned_by_user"] = False
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["catalog"]["accounts"][0]["kind"] = "liability"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["catalog"]["accounts"][-1]["kind"] = "asset"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][0]["amount"] = "70.00"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["currency"] = "USD"
        invalid["catalog"]["accounts"][-1]["currency"] = "USD"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["payment_composition_total"] = "119.99"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["system_managed"] = False
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["generic_order_lifecycle"] = True
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["funding_components"] = invalid["relations"][0]["payload"]["funding_components"][:1]
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["funding_components"][0]["account_id"] = "liability-card-rg04"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["funding_components"][0]["funding_amount"] = "69.00"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["relations"][0]["payload"]["funding_components"][1]["posting_id"] = invalid["relations"][0]["payload"]["funding_components"][0]["posting_id"]
        mutations.append(invalid)

        for invalid in mutations:
            with self.subTest(mutation=invalid):
                indexes = golden_v2._state_indexes(invalid, "$.states[0]")
                current = {"tx-expense-rg01": (
                    indexes["transactions"]["tx-expense-rg01"],
                    indexes["transaction_versions"]["version-expense-rg01-v2"],
                    [indexes["postings"]["posting-bank-rg01"], indexes["postings"]["posting-expense-rg01"]],
                )}
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_relations(
                        invalid, "$.states[0]", indexes, current, {"CNY": 2, "USD": 2}
                    )

    def test_rg04_mixed_expense_posting_semantics(self):
        state, context = rg04_posting_semantics_fixture("expense")
        replay, current = golden_v2._validate_formal_ledger(
            state,
            "$.states[0]",
            context["indexes"],
            context["precisions"],
            ZoneInfo("Asia/Shanghai"),
        )
        postings = current["tx-expense-rg01"][2]
        self.assertEqual(
            sum(Decimal(posting["amount"]) for posting in postings if posting["account_id"] == "asset-bank-a"),
            Decimal("-70.00"),
        )
        report = {"period_type": "month", "period": "2026-01"}
        values = golden_v2._report_values(
            current, context["indexes"]["catalog_accounts"], report, "CNY"
        )
        self.assertEqual(values["consumption"], 120)
        self.assertEqual(values["ordinary_expense"], 120)
        self.assertEqual(values["expense"], 120)
        self.assertEqual(values["cash_outflow"], 70)
        self.assertEqual(values["internal_transfer_amount"], 0)
        self.assertEqual(values["net_worth_change"], -120)
        self.assertEqual(
            {posting["role"] for posting in postings},
            {
                "expense",
                "mixed_expense_asset_funding",
                "mixed_expense_credit_funding",
            },
        )

        mutations = []
        invalid = deepcopy(state)
        invalid["postings"][0]["role"] = "mixed_expense_credit_funding"
        mutations.append(invalid)
        invalid = deepcopy(state)
        next(item for item in invalid["postings"] if item["id"] == "posting-credit-rg04-semantics")["account_id"] = "expense-account-breakfast"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["reconciliation_eligible"] = True
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"] = [
            item for item in invalid["postings"] if item["id"] != "posting-credit-rg04-semantics"
        ]
        invalid["posting_sets"][0]["posting_ids"].remove("posting-credit-rg04-semantics")
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["amount"] = "110.00"
        mutations.append(invalid)
        invalid = deepcopy(state)
        next(item for item in invalid["postings"] if item["role"] == "expense").pop("category_id")
        mutations.append(invalid)
        invalid = deepcopy(state)
        next(item for item in invalid["postings"] if item["role"] == "expense").update(category_id="expense-category-living")
        mutations.append(invalid)
        invalid = deepcopy(state)
        next(item for item in invalid["postings"] if item["role"] == "mixed_expense_asset_funding").update(category_id="expense-category-breakfast")
        mutations.append(invalid)
        for invalid in mutations:
            with self.subTest(mutation=invalid):
                indexes = golden_v2._state_indexes(invalid, "$.states[0]")
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_formal_ledger(
                        invalid,
                        "$.states[0]",
                        indexes,
                        context["precisions"],
                        ZoneInfo("Asia/Shanghai"),
                    )

        for posting_id, account_id, expected_path in (
            ("posting-expense-rg01", "account-missing-expense", "expense.account_id"),
            ("posting-credit-rg04-semantics", "account-missing-funding", "mixed_expense_credit_funding.account_id"),
        ):
            invalid = deepcopy(state)
            next(item for item in invalid["postings"] if item["id"] == posting_id)["account_id"] = account_id
            indexes = golden_v2._state_indexes(invalid, "$.states[0]")
            transaction = indexes["transactions"]["tx-expense-rg01"]
            postings = [
                indexes["postings"][posting_id]
                for posting_id in indexes["posting_sets"]["posting-set-expense-rg01"]["posting_ids"]
            ]
            with self.subTest(account_id=account_id):
                with self.assertRaisesRegex(GoldenCaseError, rf"\$\.states\[0\]\.transactions\[0\]\.posting_set\.{expected_path}"):
                    golden_v2._validate_transaction_posting_semantics(
                        transaction,
                        postings,
                        indexes["catalog_accounts"],
                        "$.states[0].transactions[0]",
                        context["precisions"],
                    )

    def test_rg04_credit_repayment_posting_semantics(self):
        state, context = rg04_posting_semantics_fixture("credit_repayment")
        _, current = golden_v2._validate_formal_ledger(
            state,
            "$.states[0]",
            context["indexes"],
            context["precisions"],
            ZoneInfo("Asia/Shanghai"),
        )
        values = golden_v2._report_values(
            current,
            context["indexes"]["catalog_accounts"],
            {"period_type": "month", "period": "2026-01"},
            "CNY",
        )
        self.assertEqual(values["cash_outflow"], 50)
        self.assertEqual(values["consumption"], 0)
        self.assertEqual(values["ordinary_expense"], 0)
        self.assertEqual(values["net_worth_change"], 0)

        mutations = []
        invalid = deepcopy(state)
        invalid["postings"][0]["role"] = "credit_repayment_liability_principal"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["account_id"] = "expense-account-breakfast"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["amount"] = "-50.00"
        mutations.append(invalid)
        invalid = deepcopy(state)
        invalid["postings"][1]["reconciliation_eligible"] = False
        mutations.append(invalid)
        for invalid in mutations:
            with self.subTest(mutation=invalid):
                indexes = golden_v2._state_indexes(invalid, "$.states[0]")
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_formal_ledger(
                        invalid,
                        "$.states[0]",
                        indexes,
                        context["precisions"],
                        ZoneInfo("Asia/Shanghai"),
                    )

    def test_all_confirmation_subtypes_allow_an_unknown_confirmation_time(self):
        case = load_rg09()
        case["states"][-1]["confirmations"].append(
            {
                "id": "confirmation-manual-without-time",
                "type": "explicit_manual_save",
                "operation_id": "operation-rg09-confirm-explanation",
                "subject": {
                    "kind": "operation",
                    "id": "operation-rg09-confirm-explanation",
                },
                "payload": {},
            }
        )
        seen = set()
        for state in case["states"]:
            for confirmation in state["confirmations"]:
                seen.add(confirmation["type"])
                confirmation.pop("confirmed_at", None)
        self.assertEqual(
            seen,
            {
                "explicit_manual_save",
                "candidate_confirmation",
                "explicit_operation_confirmation",
            },
        )
        self.assertEqual(schema_errors(case), [])

    def test_present_confirmation_time_remains_strict(self):
        case = load_rg09()
        samples = {
            item["type"]: item for item in case["states"][-1]["confirmations"]
        }
        samples["explicit_manual_save"] = {
            "id": "confirmation-manual-with-time",
            "type": "explicit_manual_save",
            "operation_id": "operation-rg09-confirm-explanation",
            "subject": {
                "kind": "operation",
                "id": "operation-rg09-confirm-explanation",
            },
            "payload": {},
        }
        for confirmation_type, sample in samples.items():
            with self.subTest(confirmation_type=confirmation_type):
                invalid = load_rg09()
                confirmation = deepcopy(sample)
                confirmation["confirmed_at"] = "2026-01-03T12:00:00"
                invalid["states"][-1]["confirmations"].append(confirmation)
                self.assertTrue(schema_errors(invalid))

    def test_cross_rg_domain_and_evidence_payloads_are_closed(self):
        case = add_rg05_contract_objects()
        merchant = rg10_contract_case()
        self.assertEqual(schema_errors(case), [])
        self.assertEqual(schema_errors(merchant), [])

        invalid = deepcopy(case)
        invalid["states"][0]["domain_entities"][-1]["payload"]["unexpected"] = True
        self.assertTrue(schema_errors(invalid))

        invalid = deepcopy(merchant)
        invalid["states"][0]["evidence"][-1]["payload"]["merchant_id"] = "merchant-x"
        self.assertTrue(schema_errors(invalid))

        invalid = deepcopy(merchant)
        invalid["states"][0]["evidence"][-1]["payload"]["observed_at"] = "2026-01-15T08:31:00"
        self.assertTrue(schema_errors(invalid))

    def test_rg03_transfer_provenance_payloads_are_closed(self):
        for complete, confirmed in ((True, False), (True, True), (False, False)):
            with self.subTest(complete=complete, confirmed=confirmed):
                case = rg03_transfer_provenance_case(complete, confirmed)
                self.assertEqual(schema_errors(case), [])
                validate_contract_state(case, state_index=1)

        unknown_destination = rg03_transfer_provenance_case(False)
        self.assertNotIn(
            "destination_account_id",
            unknown_destination["states"][1]["sources"][-1]["payload"],
        )
        self.assertNotIn(
            "destination_account_id",
            unknown_destination["states"][1]["candidates"][-1]["payload"],
        )

        cases = []
        source_extra = rg03_transfer_provenance_case()
        source_extra["states"][1]["sources"][-1]["payload"]["target_id"] = "posting-x"
        cases.append(source_extra)

        candidate_extra = rg03_transfer_provenance_case()
        candidate_extra["states"][1]["candidates"][-1]["payload"]["reconciliation_status"] = "matched"
        cases.append(candidate_extra)

        evidence_extra = rg03_transfer_provenance_case()
        evidence_extra["states"][1]["evidence"][-1]["payload"]["target_id"] = "posting-x"
        cases.append(evidence_extra)

        guessed_destination = rg03_transfer_provenance_case(False)
        guessed_destination["states"][1]["sources"][-1]["payload"]["destination_account_id"] = (
            "asset-wallet-b"
        )
        cases.append(guessed_destination)

        for case in cases:
            with self.subTest(case=case):
                self.assertTrue(schema_errors(case))

    def test_rg03_transfer_provenance_references_and_status_history_are_semantic(self):
        valid = rg03_transfer_provenance_case(True, True)
        validate_contract_state(valid, state_index=1)

        cases = []
        wrong_evidence_ref = rg03_transfer_provenance_case()
        wrong_evidence_ref["states"][1]["candidates"][-1]["payload"]["evidence_refs"] = ["missing-evidence"]
        cases.append((wrong_evidence_ref, r"evidence_refs"))

        mismatched_source_evidence = rg03_transfer_provenance_case()
        mismatched_source_evidence["states"][1]["evidence"][-1]["source_ids"] = []
        cases.append((mismatched_source_evidence, r"sources\[0\].payload.evidence_id"))

        dangling_source_evidence = rg03_transfer_provenance_case()
        dangling_source_evidence["states"][1]["sources"][-1]["payload"]["evidence_id"] = (
            "missing-evidence"
        )
        cases.append((dangling_source_evidence, r"sources\[0\].payload.evidence_id"))

        mismatched_evidence_time = rg03_transfer_provenance_case()
        mismatched_evidence_time["states"][1]["evidence"][-1]["payload"]["observed_at"] = (
            "2026-01-21T11:01:00+08:00"
        )
        cases.append((mismatched_evidence_time, r"observed_at"))

        candidate_source_completeness_mismatch = rg03_transfer_provenance_case()
        candidate_source_completeness_mismatch["states"][1]["candidates"][-1]["payload"] = (
            rg03_transfer_provenance_case(False)["states"][1]["candidates"][-1]["payload"]
        )
        cases.append((candidate_source_completeness_mismatch, r"candidates\[0\].payload"))

        invalid_history = rg03_transfer_provenance_case(True, True)
        invalid_history["states"][1]["candidates"][-1]["status_history"][0]["status"] = "confirmed"
        cases.append((invalid_history, r"status_history"))

        repeated_pending = rg03_transfer_provenance_case()
        repeated_pending["states"][1]["candidates"][-1]["status_history"].append(
            {"id": "candidate-transfer-pending-again", "sequence": 2, "status": "pending_confirmation"}
        )
        cases.append((repeated_pending, r"status_history"))

        repeated_pending_then_confirmed = rg03_transfer_provenance_case(True, True)
        repeated_pending_then_confirmed["states"][1]["candidates"][-1]["status_history"].insert(
            1,
            {"id": "candidate-transfer-pending-again", "sequence": 2, "status": "pending_confirmation"},
        )
        repeated_pending_then_confirmed["states"][1]["candidates"][-1]["status_history"][2][
            "sequence"
        ] = 3
        cases.append((repeated_pending_then_confirmed, r"status_history"))

        repeated_confirmation = rg03_transfer_provenance_case(True, True)
        repeated_confirmation["states"][1]["candidates"][-1]["status_history"].append(
            {"id": "candidate-transfer-confirmed-again", "sequence": 3, "status": "confirmed"}
        )
        cases.append((repeated_confirmation, r"status_history"))

        pending_with_confirmation = rg03_transfer_provenance_case()
        pending_with_confirmation["states"][1]["confirmations"].append(
            deepcopy(
                rg03_transfer_provenance_case(True, True)["states"][1]["confirmations"][-1]
            )
        )
        cases.append((pending_with_confirmation, r"candidate_confirmation"))

        confirmed_without_confirmation = rg03_transfer_provenance_case(True, True)
        confirmed_without_confirmation["states"][1]["confirmations"] = [
            confirmation
            for confirmation in confirmed_without_confirmation["states"][1]["confirmations"]
            if confirmation["id"] != "confirmation-transfer-rg03"
        ]
        cases.append((confirmed_without_confirmation, r"candidate_confirmation|transaction_versions.*confirmation_id"))

        confirmed_with_two_confirmations = rg03_transfer_provenance_case(True, True)
        duplicate_confirmation = deepcopy(
            confirmed_with_two_confirmations["states"][1]["confirmations"][-1]
        )
        duplicate_confirmation["id"] = "confirmation-transfer-rg03-duplicate"
        confirmed_with_two_confirmations["states"][1]["confirmations"].append(
            duplicate_confirmation
        )
        cases.append((confirmed_with_two_confirmations, r"candidate_confirmation"))

        for case, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(case, state_index=1)

    def test_rg03_helper_executes_transfer_semantics_in_second_state(self):
        case = rg03_transfer_provenance_case()
        case["states"][1]["sources"][-1]["payload"]["source_debit_amount"] = "61.00"
        self.assertEqual(schema_errors(case), [])
        with self.assertRaisesRegex(GoldenCaseError, r"source_debit_amount"):
            validate_contract_state(case, state_index=1)

    def test_rg03_account_credit_observation_source_is_closed_and_not_a_transfer_source(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        source_validator = Draft202012Validator({"$defs": schema["$defs"], "$ref": "#/$defs/accountCreditObservationSource"})
        source = {
            "id": "source-rg03-mirror",
            "type": "account_credit_observation",
            "payload": {
                "account_id": "asset-wallet-b",
                "credit_amount": "59.00",
                "currency": "CNY",
                "observed_at": "2026-01-21T11:01:00+08:00",
                "evidence_id": "evidence-rg03-mirror",
            },
        }
        self.assertTrue(source_validator.is_valid(source))
        for forbidden in (
            "source_account_id", "destination_account_id", "debit_amount",
            "source_debit_amount", "fee_amount", "completeness", "transaction_id",
            "posting_id", "balance", "report",
        ):
            invalid = deepcopy(source)
            invalid["payload"][forbidden] = "forbidden"
            self.assertFalse(source_validator.is_valid(invalid), forbidden)

        case = rg03_transfer_provenance_case()
        state = case["states"][1]
        state["sources"].append(deepcopy(source))
        state["evidence"].append({
            "id": "evidence-rg03-mirror",
            "type": "transfer_record",
            "source_ids": ["source-rg03-mirror"],
            "payload": {"observed_at": "2026-01-21T11:01:00+08:00"},
        })
        validate_contract_state(case, state_index=1)

        invalid = deepcopy(case)
        invalid["states"][1]["sources"][-1]["type"] = "account_transfer"
        self.assertTrue(schema_errors(invalid))
        invalid = deepcopy(case)
        invalid["states"][1]["sources"][-1]["payload"]["currency"] = "USD"
        with self.assertRaisesRegex(GoldenCaseError, r"currency"):
            validate_contract_state(invalid, state_index=1)

    def test_rg03_rejected_manual_transfer_predicates_cover_all_ten_v1_failures(self):
        case = rg03_transfer_provenance_case()
        baseline = case["states"][1]
        baseline["catalog"]["accounts"].append({
            "id": "asset-external-x", "name": "External asset", "kind": "asset",
            "currency": "CNY", "owned_by_user": False, "real_account": True,
            "reconciliation_eligible": False,
        })
        operation_template = {
            "id": "operation-rg03-rejected",
            "action_type": "manual_account_transfer",
            "operation_class": "rejection",
            "attempted_input": {},
            "outcome": {"status": "rejected", "reason_code": "", "field_path": ""},
        }
        base = {
            "request_id": "request-rg03-invalid",
            "source_account_id": "asset-bank-a",
            "destination_account_id": "asset-wallet-b",
            "source_debit_amount": "60.00",
            "destination_credit_amount": "59.00",
            "fee_amount": "1.00",
            "currency": "CNY",
        }
        cases = [
            ({"source_account_id": None, **{k: v for k, v in base.items() if k != "source_account_id"}}, "source_account_id", "required"),
            ({**base, "destination_account_id": None}, "destination_account_id", "required"),
            ({**base, "destination_account_id": "asset-bank-a"}, "destination_account_id", "distinct_own_real_financial_accounts_required"),
            ({**base, "source_account_id": "missing-account"}, "source_account_id", "known_account_required"),
            ({**base, "destination_account_id": "asset-external-x"}, "destination_account_id", "own_account_required"),
            ({**base, "source_account_id": "expense-account-breakfast"}, "source_account_id", "real_financial_account_required"),
            ({**base, "destination_credit_amount": "0.00", "source_debit_amount": "1.00"}, "destination_credit_amount", "must_be_positive"),
            ({**base, "destination_credit_amount": "-0.01", "source_debit_amount": "0.99"}, "destination_credit_amount", "must_be_positive"),
            ({**base, "fee_amount": "0.99"}, "fee_amount", "amounts_must_balance"),
            ({**base, "destination_currency": "USD", "source_currency": "CNY"}, "destination_currency", "same_currency_required"),
        ]
        for index, (attempted, field, reason) in enumerate(cases):
            with self.subTest(index=index, field=field):
                attempted["request_id"] = f"request-rg03-invalid-{index}"
                operation = deepcopy(operation_template)
                operation["attempted_input"] = attempted
                operation["outcome"] = {
                    "status": "rejected",
                    "reason_code": reason,
                    "field_path": f"$.attempted_input.{field}",
                }
                golden_v2._validate_rejected_manual_account_transfer_attempt(
                    operation, "$.operations[0]", baseline, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai")
                )

    def test_rg03_incomplete_source_skips_destination_requirement_but_validates_source(self):
        baseline = rg03_transfer_provenance_case()["states"][1]
        operation = {
            "action_type": "import_incomplete_source",
            "input": {
                "request_id": "request-rg03-incomplete",
                "source_id": "source-rg03-incomplete",
                "source_account_id": "asset-bank-a",
                "debit_amount": "40.00",
                "currency": "CNY",
                "observed_at": "2026-01-21T11:00:00+08:00",
            },
            "outcome": {"status": "accepted"},
        }
        golden_v2._validate_action_input(
            operation,
            "$.operations[0]",
            baseline,
            {"CNY": 2},
            ZoneInfo("Asia/Shanghai"),
        )

        invalid = deepcopy(operation)
        invalid["input"]["source_account_id"] = "expense-account-breakfast"
        with self.assertRaisesRegex(GoldenCaseError, r"source_account_id"):
            golden_v2._validate_action_input(
                invalid,
                "$.operations[0]",
                baseline,
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

    def test_rg03_rejected_transfer_prechecks_only_declared_currency(self):
        baseline = rg03_transfer_provenance_case()["states"][1]
        operation = {
            "action_type": "manual_account_transfer",
            "attempted_input": {
                "request_id": "request-rg03-cross-currency",
                "source_account_id": "asset-bank-a",
                "destination_account_id": "asset-wallet-b",
                "source_debit_amount": "60.00",
                "destination_credit_amount": "59.00",
                "fee_amount": "1.00",
                "currency": "CNY",
                "source_currency": "CNY",
                "destination_currency": "USD",
            },
            "outcome": {
                "status": "rejected",
                "reason_code": "same_currency_required",
                "field_path": "$.attempted_input.destination_currency",
            },
        }
        golden_v2._validate_rejected_manual_account_transfer_attempt(
            operation,
            "$.operations[0]",
            baseline,
            {"CNY": 2},
            ZoneInfo("Asia/Shanghai"),
        )

        undeclared_primary = deepcopy(operation)
        undeclared_primary["attempted_input"]["currency"] = "USD"
        with self.assertRaisesRegex(
            GoldenCaseError,
            r"\$\.operations\[0\]\.attempted_input\.currency.*undeclared currency",
        ):
            golden_v2._validate_rejected_manual_account_transfer_attempt(
                undeclared_primary,
                "$.operations[0]",
                baseline,
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

    def test_rg03_positive_case_uses_complete_validator_and_returned_ids_are_closed(self):
        case = rg03_full_manual_case()
        validate_golden_case_v2(case)

        baseline = deepcopy(case["states"][1])
        candidate_source = {
            "id": "source-rg03-confirmed-debit",
            "type": "account_transfer",
            "payload": {
                "source_account_id": "asset-a", "destination_account_id": "asset-b",
                "source_debit_amount": "60.00", "destination_credit_amount": "59.00",
                "fee_amount": "1.00", "currency": "CNY", "completeness": "complete",
                "observed_at": "2026-01-20T10:00:00+08:00", "evidence_id": "evidence-rg03-confirmed-debit",
            },
        }
        candidate_evidence = {"id": "evidence-rg03-confirmed-debit", "type": "transfer_record", "source_ids": [candidate_source["id"]], "payload": {"observed_at": "2026-01-20T10:00:00+08:00"}}
        baseline["sources"].append(candidate_source)
        baseline["evidence"].append(candidate_evidence)
        baseline["candidates"].append({
            "id": "candidate-rg03-confirmed", "type": "account_transfer", "source_ids": [candidate_source["id"]], "confidence": "1.00",
            "payload": {"source_account_id": "asset-a", "destination_account_id": "asset-b", "source_debit_amount": "60.00", "destination_credit_amount": "59.00", "fee_amount": "1.00", "currency": "CNY", "transaction_id": "transaction-rg03-manual", "evidence_refs": [candidate_evidence["id"]], "provenance": {"rule": "complete_transfer_source", "rule_version": 1}, "requires_confirmation": ["formal_transaction_creation"]},
            "status_history": [{"id": "candidate-rg03-confirmed-status", "sequence": 1, "status": "confirmed"}],
        })
        result = deepcopy(baseline)
        result["sources"].append({"id": "source-rg03-mirror", "type": "account_credit_observation", "payload": {"account_id": "asset-b", "credit_amount": "59.00", "currency": "CNY", "observed_at": "2026-01-21T11:01:00+08:00", "evidence_id": "evidence-rg03-mirror"}})
        result["evidence"].append({"id": "evidence-rg03-mirror", "type": "transfer_record", "source_ids": ["source-rg03-mirror"], "payload": {"observed_at": "2026-01-21T11:01:00+08:00"}})
        result["evidence_links"].append({"id": "link-rg03-mirror", "evidence_id": "evidence-rg03-mirror", "target_kind": "posting", "target_id": "posting-destination-rg03-manual", "role": "destination_asset_posting"})
        result["posting_reconciliations"][1]["status"] = "matched"
        mirror_operation = {"id": "operation-rg03-mirror", "action_type": "import_mirror_record", "operation_class": "reconciliation", "input": {"request_id": "request-rg03-mirror", "source_id": "source-rg03-mirror", "evidence_id": "evidence-rg03-mirror", "transaction_id": "transaction-rg03-manual", "candidate_id": "candidate-rg03-confirmed", "account_id": "asset-b", "credit_amount": "59.00", "currency": "CNY", "observed_at": "2026-01-21T11:01:00+08:00"}, "outcome": {"status": "accepted"}, "returned_ids": [{"kind": "source", "id": "source-rg03-mirror"}, {"kind": "evidence", "id": "evidence-rg03-mirror"}, {"kind": "evidence_link", "id": "link-rg03-mirror"}]}
        expected_entities = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_action_semantics(mirror_operation, "$.operations[1]", baseline, result, expected_entities)

        invalid_mirror = deepcopy(result)
        invalid_mirror["evidence_links"][-1]["target_id"] = "posting-opening-b-rg09"
        with self.assertRaisesRegex(GoldenCaseError, r"unique destination posting|destination posting"):
            golden_v2._validate_action_semantics(mirror_operation, "$.operations[1]", baseline, invalid_mirror, golden_v2._expected_entity_changes(baseline, invalid_mirror))

        invalid = deepcopy(case)
        invalid["operations"][0]["returned_ids"] = [{"kind": "transaction", "id": "transaction-rg03-manual"}]
        with self.assertRaisesRegex(GoldenCaseError, r"returned_ids"):
            validate_golden_case_v2(invalid)

        invalid = deepcopy(case)
        invalid["operations"][0]["root_id"] = "root-other"
        with self.assertRaisesRegex(GoldenCaseError, r"root_id|root"):
            validate_golden_case_v2(invalid)

    def test_rg03_mirror_evidence_role_allows_only_destination_posting_roles(self):
        case = rg03_confirm_account_transfer_candidate_case()
        baseline = deepcopy(case["states"][-1])
        result = deepcopy(baseline)
        result["id"] = "state-rg03-mirror"
        result["as_of_operation_id"] = "operation-rg03-mirror"
        result["sources"].append({"id": "source-rg03-mirror", "type": "account_credit_observation", "payload": {"account_id": "asset-b", "credit_amount": "59.00", "currency": "CNY", "observed_at": "2026-01-21T11:01:00+08:00", "evidence_id": "evidence-rg03-mirror"}})
        result["evidence"].append({"id": "evidence-rg03-mirror", "type": "transfer_record", "source_ids": ["source-rg03-mirror"], "payload": {"observed_at": "2026-01-21T11:01:00+08:00"}})
        result["evidence_links"].append({"id": "link-rg03-mirror", "evidence_id": "evidence-rg03-mirror", "target_kind": "posting", "target_id": "posting-destination-rg03-confirmed-transfer", "role": "destination_asset_posting"})
        next(item for item in result["posting_reconciliations"] if item["posting_id"] == "posting-destination-rg03-confirmed-transfer")["status"] = "matched"
        next(item for item in result["derived_statuses"] if item["target_id"] == "transaction-rg03-confirmed-transfer")["value"] = "matched"
        deltas, status_changes = _operation_deltas(baseline, result)
        operation = {
            "id": "operation-rg03-mirror", "root_id": case["roots"][0]["id"], "sequence": 5,
            "operation_class": "reconciliation", "action_type": "import_mirror_record",
            "baseline_state_id": baseline["id"], "result_state_id": result["id"],
            "input": {"request_id": "request-rg03-mirror", "source_id": "source-rg03-mirror", "evidence_id": "evidence-rg03-mirror", "transaction_id": "transaction-rg03-confirmed-transfer", "candidate_id": "candidate-rg03-imported-transfer", "account_id": "asset-b", "credit_amount": "59.00", "currency": "CNY", "observed_at": "2026-01-21T11:01:00+08:00"},
            "outcome": {"status": "accepted"}, "status_changes": status_changes, "deltas": deltas,
            "returned_ids": [{"kind": "source", "id": "source-rg03-mirror"}, {"kind": "evidence", "id": "evidence-rg03-mirror"}, {"kind": "evidence_link", "id": "link-rg03-mirror"}],
        }
        case["roots"][0]["operation_ids"].append(operation["id"])
        case["states"].append(result)
        case["operations"].append(operation)
        validate_golden_case_v2(case)

        contract_case = rg03_full_manual_case()
        contract_state = contract_case["states"][-1]
        contract_state["sources"].append({"id": "source-destination-role", "type": "account_credit_observation", "payload": {"account_id": "asset-b", "credit_amount": "59.00", "currency": "CNY", "observed_at": "2026-01-21T11:01:00+08:00", "evidence_id": "evidence-destination-role"}})
        contract_state["evidence"].append({"id": "evidence-destination-role", "type": "transfer_record", "source_ids": ["source-destination-role"], "payload": {"observed_at": "2026-01-21T11:01:00+08:00"}})
        link = {"id": "link-destination-role", "evidence_id": "evidence-destination-role", "target_kind": "posting", "target_id": "posting-destination-rg03-manual", "role": "destination_asset_posting"}
        contract_state["evidence_links"].append(link)
        target = next(item for item in contract_state["postings"] if item["id"] == link["target_id"])
        target["role"] = "destination_asset"
        validate_contract_state(contract_case, 1)

        for role in ("transfer_principal_out", "transfer_fee", "payment_asset"):
            with self.subTest(role=role):
                invalid = deepcopy(contract_case)
                target = next(item for item in invalid["states"][-1]["postings"] if item["id"] == "posting-destination-rg03-manual")
                target["role"] = role
                with self.assertRaisesRegex(GoldenCaseError, r"target_id"):
                    validate_contract_state(invalid, 1)

        unrelated = deepcopy(contract_case)
        unrelated["states"][-1]["evidence_links"][-1]["target_id"] = "posting-opening-b-rg09"
        with self.assertRaisesRegex(GoldenCaseError, r"target_id"):
            validate_contract_state(unrelated, 1)

    def test_rg03_import_source_record_is_source_only_and_exact(self):
        case = rg03_import_source_record_case()
        validate_golden_case_v2(case)

        invalid = deepcopy(case)
        result = invalid["states"][-1]
        result["transactions"].append({
            "id": "transaction-rg03-illicit-import",
            "type": "account_transfer",
            "current_version_id": "version-rg03-illicit-import-v1",
        })
        invalid["operations"][-1]["deltas"]["entity_changes"] = golden_v2._expected_entity_changes(
            invalid["states"][-2], result
        )
        with self.assertRaisesRegex(GoldenCaseError, r"transactions"):
            validate_golden_case_v2(invalid)

        invalid = deepcopy(case)
        invalid["operations"][-1]["returned_ids"][-1] = {
            "kind": "transaction", "id": "transaction-rg03-manual"
        }
        with self.assertRaisesRegex(GoldenCaseError, r"returned_ids"):
            validate_golden_case_v2(invalid)

        invalid = deepcopy(case)
        invalid["states"][-1]["sources"][-1]["payload"]["fee_amount"] = "2.00"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid)

        invalid = deepcopy(case)
        invalid["states"][-1]["evidence"][-1]["payload"]["observed_at"] = "2026-01-21T10:01:00+08:00"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid)

        invalid = deepcopy(case)
        invalid["states"][-1]["candidates"][-1]["payload"]["fee_amount"] = "2.00"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid)

    def test_rg03_candidate_confirmation_allows_only_its_transaction_binding_transition(self):
        case = rg03_confirm_account_transfer_candidate_case()
        validate_golden_case_v2(case)

        pending_prebound = deepcopy(case)
        for state in pending_prebound["states"][:-1]:
            candidates = [
                item
                for item in state["candidates"]
                if item["id"] == "candidate-rg03-imported-transfer"
            ]
            if candidates:
                candidates[0]["payload"]["transaction_id"] = "transaction-rg03-manual"
        with self.subTest("pending candidate cannot prebind a transaction"):
            with self.assertRaisesRegex(GoldenCaseError, r"pending transfer candidate"):
                validate_golden_case_v2(pending_prebound)

        changed_payload = deepcopy(case)
        changed_payload["states"][-1]["candidates"][-1]["payload"]["fee_amount"] = "2.00"
        with self.subTest("confirmation cannot rewrite original candidate payload"):
            with self.assertRaisesRegex(GoldenCaseError, r"candidates.*immutable|candidates.*payload"):
                validate_golden_case_v2(changed_payload)

        changed_transaction = deepcopy(case)
        for state in changed_transaction["states"][-2:]:
            state["candidates"][-1]["payload"]["transaction_id"] = (
                "transaction-rg03-manual"
            )
        with self.subTest("confirmation must bind its unique newly created transfer"):
            with self.assertRaisesRegex(GoldenCaseError, r"confirmed candidate must bind"):
                validate_golden_case_v2(changed_transaction)

        nonconfirmation_binding = rg03_import_source_record_case()
        nonconfirmation_binding["states"][-1]["candidates"][-1]["payload"]["transaction_id"] = (
            "transaction-rg03-manual"
        )
        with self.subTest("nonconfirmation action cannot bind a pending candidate"):
            with self.assertRaisesRegex(GoldenCaseError, r"pending transfer candidate"):
                validate_golden_case_v2(nonconfirmation_binding)

        confirmed_rewrite = deepcopy(case)
        confirmed_rewrite["states"][-1]["candidates"][-1]["confidence"] = "0.90"
        with self.subTest("confirmed candidates remain append-only after confirmation"):
            with self.assertRaisesRegex(GoldenCaseError, r"candidates.*(?:immutable|non-history)"):
                validate_golden_case_v2(confirmed_rewrite)

    def test_rg03_candidate_confirmation_special_transition_is_scoped_to_input_candidate(self):
        case = add_second_rg03_pending_candidate(
            rg03_confirm_account_transfer_candidate_case()
        )
        validate_golden_case_v2(case)

        invalid = deepcopy(case)
        for state in invalid["states"][-2:]:
            secondary = next(
                item
                for item in state["candidates"]
                if item["id"] == "candidate-rg03-secondary-transfer"
            )
            secondary["confidence"] = "0.90"
        with self.assertRaisesRegex(
            GoldenCaseError,
            r"candidates\[candidate-rg03-secondary-transfer\].*(?:immutable|non-history)",
        ):
            validate_golden_case_v2(invalid)

    def test_cross_rg_roles_bind_their_canonical_target_kinds(self):
        cases = []
        wrong_item = add_rg05_contract_objects()
        wrong_item["states"][0]["evidence_links"][-1]["target_kind"] = "posting"
        cases.append(wrong_item)

        wrong_posting = rg10_contract_case()
        wrong_posting["states"][0]["evidence_links"][0]["target_kind"] = "domain_entity"
        cases.append(wrong_posting)

        wrong_lot = rg10_contract_case()
        wrong_lot["states"][0]["evidence_links"][-1]["target_kind"] = "posting"
        cases.append(wrong_lot)

        for case in cases:
            with self.subTest(role=case["states"][0]["evidence_links"][-1]["role"]):
                self.assertTrue(schema_errors(case))

    def test_schema_stable_ids_reject_control_characters(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema)
        case = load_rg01()
        case["roots"][0]["id"] = "root-rg01\nmain"
        self.assertTrue(list(validator.iter_errors(case)))

    def test_schema_rejects_missing_required_and_invalid_scalar_shapes(self):
        cases = []

        missing = load_rg01()
        del missing["states"][0]["balances"]
        cases.append((missing, r"\$\.states\[0\].*balances"))

        bad_amount = load_rg01()
        bad_amount["states"][1]["postings"][0]["amount"] = 35.8
        cases.append((bad_amount, r"\$\.states\[1\]\.postings\[0\]\.amount"))

        bad_time = load_rg01()
        bad_time["states"][1]["transaction_versions"][0]["occurred_at"] = "2026-01-15T08:30:00"
        cases.append((bad_time, r"\$\.states\[1\]\.transaction_versions\[0\]\.occurred_at"))

        bad_id = load_rg01()
        bad_id["roots"][0]["id"] = ""
        cases.append((bad_id, r"\$\.roots\[0\]\.id"))

        for case, path in cases:
            with self.subTest(path=path):
                assert_invalid(self, case, path)

    def test_rfc3339_accepts_positive_zero_offset_and_rejects_wrong_zone_offset(self):
        utc_case = replace_timestamp_offset(load_rg01(), "+08:00", "+00:00")
        utc_case["case"]["timezone"] = "UTC"
        validate_golden_case_v2(utc_case)

        wrong_offset = load_rg01()
        wrong_offset["states"][1]["transaction_versions"][0]["occurred_at"] = (
            "2026-01-15T08:30:00+07:00"
        )
        assert_invalid(
            self,
            wrong_offset,
            r"\$\.states\[1\]\.transaction_versions\[0\]\.occurred_at",
        )

    def test_stored_value_catalog_and_fingerprint_foundation_are_closed(self):
        case = rg10_contract_case()
        self.assertEqual(schema_errors(case), [])

        for field in ("enabled", "merchant_restricted", "merchant_id"):
            bad_case = deepcopy(case)
            del bad_case["states"][0]["catalog"]["accounts"][0]["stored_value"][field]
            assert_invalid(self, bad_case, rf"stored_value.*{field}")

        unknown = deepcopy(case)
        unknown["states"][0]["catalog"]["accounts"][0]["stored_value"]["capable"] = True
        assert_invalid(self, unknown, r"stored_value")

        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            set(schema["$defs"]["staleReplayDiagnostics"]["properties"]),
            {
                "preview_ledger_fingerprint",
                "current_ledger_fingerprint",
                "recomputed_replay_amount",
                "recomputed_delta",
            },
        )
        self.assertEqual(
            set(schema["$defs"]["account"]["properties"]["system_role"]["enum"])
            - {"opening_balance", "balance_adjustments"},
            {
                "stored_value_bonus_right_income",
                "stored_value_expiry_loss",
                "stored_value_pre_activation_adjustment",
            },
        )

        fingerprint_validator = Draft202012Validator(
            {"$ref": "#/$defs/sha256Fingerprint", "$defs": schema["$defs"]}
        )
        self.assertEqual(
            list(fingerprint_validator.iter_errors("sha256:" + "0" * 64)), []
        )
        self.assertTrue(
            list(fingerprint_validator.iter_errors("sha256:not-a-digest"))
        )

        projection_validator = Draft202012Validator(
            {
                "$ref": "#/$defs/ledgerFingerprintProjection",
                "$defs": schema["$defs"],
            }
        )
        projection = {
            "postings": [
                {
                    "transaction_id": "transaction-a",
                    "current_version_id": "version-a-v1",
                    "effective_at": "2026-01-01T00:00:00+08:00",
                    "posting_id": "posting-a",
                    "account_id": "account-a",
                    "currency": "CNY",
                    "amount": "1.00",
                }
            ]
        }
        self.assertEqual(list(projection_validator.iter_errors(projection)), [])
        projection["postings"][0]["created_at"] = "2026-01-01T01:00:00+08:00"
        self.assertTrue(list(projection_validator.iter_errors(projection)))

        diagnostics_validator = Draft202012Validator(
            {"$ref": "#/$defs/staleReplayDiagnostics", "$defs": schema["$defs"]}
        )
        diagnostics = {
            "preview_ledger_fingerprint": "sha256:" + "0" * 64,
            "current_ledger_fingerprint": "sha256:" + "1" * 64,
            "recomputed_replay_amount": "105.00",
            "recomputed_delta": "25.00",
        }
        self.assertEqual(list(diagnostics_validator.iter_errors(diagnostics)), [])
        diagnostics["original_preview_delta"] = "30.00"
        self.assertTrue(list(diagnostics_validator.iter_errors(diagnostics)))

        fingerprint_case = load_rg09()
        payload = fingerprint_case["states"][1]["candidates"][0]["payload"]
        payload["ledger_fingerprint"] = "sha256:" + "0" * 64
        self.assertTrue(
            any(
                "ledger_fingerprint" in error.message
                for error in schema_errors(fingerprint_case)
            )
        )

        confirmation_case = load_rg09()
        confirmation = next(
            item
            for item in confirmation_case["operations"]
            if item["action_type"] == "confirm_balance_adjustment"
        )
        confirmation["input"]["ledger_fingerprint"] = "sha256:" + "0" * 64
        self.assertTrue(
            any(
                "ledger_fingerprint" in error.message
                for error in schema_errors(confirmation_case)
            )
        )


class GoldenV2LedgerSemanticTests(unittest.TestCase):
    def test_rejects_root_operation_order_and_cross_root_state_reference(self):
        wrong_order = load_rg01()
        wrong_order["roots"][0]["operation_ids"].reverse()
        assert_invalid(self, wrong_order, r"\$\.roots\[0\]\.operation_ids")

        cross_root = load_rg01()
        cross_root["roots"].append(
            {
                "id": "root-other",
                "purpose": "independent",
                "initial_state_id": "state-rg01-created",
                "operation_ids": [],
            }
        )
        cross_root["states"][1]["root_id"] = "root-other"
        assert_invalid(
            self,
            cross_root,
            r"\$\.(?:states\[1\]\.as_of_operation_id|operations\[0\]\.result_state_id)",
        )

    def test_rejects_duplicate_collection_ids_and_dangling_formal_chain(self):
        duplicate = load_rg01()
        duplicate["states"][1]["postings"][1]["id"] = duplicate["states"][1]["postings"][0]["id"]
        assert_invalid(self, duplicate, r"\$\.states\[1\]\.postings\[1\]\.id")

        dangling_version = load_rg01()
        dangling_version["states"][1]["transactions"][0]["current_version_id"] = "missing-version"
        assert_invalid(self, dangling_version, r"\$\.states\[1\]\.transactions\[0\]\.current_version_id")

        dangling_posting = load_rg01()
        dangling_posting["states"][1]["posting_sets"][0]["posting_ids"][0] = "missing-posting"
        assert_invalid(self, dangling_posting, r"\$\.states\[1\]\.posting_sets\[0\]\.posting_ids\[0\]")

    def test_rejects_unbalanced_posting_set_and_currency_precision_mismatch(self):
        unbalanced = load_rg01()
        unbalanced["states"][1]["postings"][0]["amount"] = "-35.79"
        assert_invalid(self, unbalanced, r"\$\.states\[1\]\.posting_sets\[0\].*balanced")

        precision = load_rg01()
        precision["states"][1]["postings"][0]["amount"] = "-35.800"
        assert_invalid(self, precision, r"\$\.states\[1\]\.postings\[0\]\.amount")

    def test_rejects_incomplete_extra_and_replay_mismatched_balances(self):
        incomplete = load_rg01()
        incomplete["states"][1]["balances"].pop()
        assert_invalid(self, incomplete, r"\$\.states\[1\]\.balances")

        extra = load_rg01()
        extra["states"][1]["balances"].append(
            {"account_id": "unknown-account", "currency": "CNY", "amount": "0.00"}
        )
        assert_invalid(self, extra, r"\$\.states\[1\]\.balances\[3\]\.account_id")

        mismatch = load_rg01()
        mismatch["states"][1]["balances"][0]["amount"] = "964.21"
        assert_invalid(self, mismatch, r"\$\.states\[1\]\.balances\[0\]\.amount")

    def test_rejects_catalog_and_reconciliation_invariants(self):
        bad_category = load_rg01()
        bad_category["states"][0]["catalog"]["categories"][1]["posting_account_id"] = "missing-account"
        assert_invalid(self, bad_category, r"\$\.states\[0\]\.catalog\.categories\[1\]\.posting_account_id")

        missing_posting_owner = load_rg01()
        missing_posting_owner["states"][0]["catalog"]["categories"][1][
            "posting_account_id"
        ] = None
        assert_invalid(
            self,
            missing_posting_owner,
            r"\$\.states\[0\]\.catalog\.categories\[1\]\.posting_account_id",
        )

        wrong_posting_kind = load_rg01()
        wrong_posting_kind["states"][0]["catalog"]["categories"][1][
            "posting_account_id"
        ] = "asset-bank-a"
        assert_invalid(
            self,
            wrong_posting_kind,
            r"\$\.states\[0\]\.catalog\.categories\[1\]\.posting_account_id",
        )

        ineligible = load_rg01()
        ineligible["states"][1]["posting_reconciliations"][0]["posting_id"] = "posting-expense-rg01"
        assert_invalid(self, ineligible, r"\$\.states\[1\]\.posting_reconciliations\[0\]\.posting_id")

    def test_catalog_parent_chain_and_posting_roles_have_semantic_owners(self):
        case = load_rg01()
        state = case["states"][0]
        state["catalog"]["categories"].append(
            {
                "id": "expense-category-third-level",
                "name": "Third level",
                "parent_id": "expense-category-breakfast",
                "posting_account_id": "expense-account-breakfast",
                "active": True,
            }
        )
        indexes = golden_v2._state_indexes(state, "$.states[0]")
        with self.assertRaisesRegex(GoldenCaseError, r"parent_id.*two levels"):
            golden_v2._validate_catalog(
                state, "$.states[0]", indexes, {"CNY": 2}
            )

        stored_value = rg10_contract_case()
        validate_contract_state(stored_value)

        wrong_asset_role = deepcopy(stored_value)
        wrong_asset_role["states"][0]["postings"][0]["account_id"] = "asset-bank"
        with self.assertRaisesRegex(GoldenCaseError, r"stored_value_asset"):
            validate_contract_state(wrong_asset_role)

        wrong_system_role = deepcopy(stored_value)
        wrong_system_role["states"][0]["catalog"]["accounts"][0]["system_role"] = (
            "stored_value_bonus_right_income"
        )
        with self.assertRaisesRegex(GoldenCaseError, r"system_role"):
            validate_contract_state(wrong_system_role)

    def test_target_time_fingerprint_uses_only_current_effective_postings(self):
        state = deepcopy(load_rg09()["states"][0])
        effective_at = "2026-01-31T23:59:59+08:00"
        projection = golden_v2._replay_fingerprint_projection(state, effective_at)

        self.assertEqual(set(projection), {"postings"})
        self.assertEqual(
            list(projection["postings"][0]),
            [
                "transaction_id",
                "current_version_id",
                "effective_at",
                "posting_id",
                "account_id",
                "currency",
                "amount",
            ],
        )
        self.assertEqual(
            [item["posting_id"] for item in projection["postings"]],
            [
                "posting-opening-a-rg09",
                "posting-opening-b-rg09",
                "posting-opening-equity-rg09",
            ],
        )
        self.assertEqual(
            golden_v2._compute_replay_fingerprint(state, effective_at),
            "sha256:4d87a55085cfa954c8eedf4209f134ad7d6d8aa6ae9ab3bf8ed13c0e346c78aa",
        )

        excluded_only = deepcopy(state)
        excluded_only["transaction_versions"][0]["created_at"] = (
            "2026-02-01T09:00:00+08:00"
        )
        excluded_only["evidence"] = [{"ignored": True}]
        excluded_only["posting_reconciliations"] = [{"ignored": True}]
        excluded_only["reports"] = [{"ignored": True}]
        excluded_only["derived_statuses"] = [{"ignored": True}]
        self.assertEqual(
            golden_v2._compute_replay_fingerprint(excluded_only, effective_at),
            golden_v2._compute_replay_fingerprint(state, effective_at),
        )

        changed_economic_fact = deepcopy(state)
        changed_economic_fact["postings"][0]["amount"] = "100.01"
        self.assertNotEqual(
            golden_v2._compute_replay_fingerprint(changed_economic_fact, effective_at),
            golden_v2._compute_replay_fingerprint(state, effective_at),
        )

        after_target = deepcopy(state)
        after_target["transaction_versions"][0]["effective_at"] = (
            "2026-02-01T00:00:00+08:00"
        )
        self.assertEqual(
            golden_v2._replay_fingerprint_projection(after_target, effective_at),
            {"postings": []},
        )

        utf16_order = deepcopy(state)
        utf16_order["posting_sets"][0]["posting_ids"] = [
            "posting-\ue000",
            "posting-\U00010000",
        ]
        utf16_order["postings"] = [
            {
                **utf16_order["postings"][0],
                "id": "posting-\ue000",
            },
            {
                **utf16_order["postings"][1],
                "id": "posting-\U00010000",
            },
        ]
        self.assertEqual(
            [
                item["posting_id"]
                for item in golden_v2._replay_fingerprint_projection(
                    utf16_order, effective_at
                )["postings"]
            ],
            ["posting-\U00010000", "posting-\ue000"],
        )

    def test_rejects_dangling_source_evidence_and_audit_references(self):
        source = load_rg09()
        source["states"][-1]["candidates"][0]["source_ids"][0] = "missing-source"
        assert_invalid(self, source, r"\$\.states\[4\]\.candidates\[0\]\.source_ids\[0\]")

        evidence = load_rg09()
        evidence["states"][-1]["evidence_links"][0]["target_id"] = "missing-observation"
        assert_invalid(self, evidence, r"\$\.states\[4\]\.evidence_links\[0\]\.target_id")

        audit = load_rg09()
        audit["states"][-1]["audit_links"][0]["to"]["id"] = "missing-transaction"
        assert_invalid(self, audit, r"\$\.states\[4\]\.audit_links\[0\]\.to\.id")

    def test_evidence_role_requires_the_exact_named_target(self):
        case = load_rg09()
        link = case["states"][-1]["evidence_links"][0]
        link["target_kind"] = "posting"
        link["target_id"] = "posting-opening-a-rg09"
        link["role"] = "real_account_posting"
        assert_invalid(self, case, r"\$\.states\[4\]\.evidence_links\[0\]\.target_id")

    def test_cross_rg_roles_require_exact_subtypes_and_eligible_posting(self):
        validate_golden_case_v2(add_rg05_contract_objects())

        wrong_allocation = add_rg05_contract_objects()
        wrong_allocation["states"][0]["evidence_links"][-1]["target_id"] = "consumption-cross-rg"
        assert_invalid(self, wrong_allocation, r"\$\.states\[0\]\.evidence_links\[0\]\.target_id")

        wrong_posting_role = rg10_contract_case()
        target_id = wrong_posting_role["states"][0]["evidence_links"][0]["target_id"]
        next(
            item for item in wrong_posting_role["states"][0]["postings"] if item["id"] == target_id
        )["role"] = "payment_asset"
        with self.assertRaisesRegex(GoldenCaseError, r"evidence_links\[0\]\.target_id"):
            validate_contract_state(wrong_posting_role)

        ineligible = rg10_contract_case()
        target_id = ineligible["states"][0]["evidence_links"][0]["target_id"]
        next(item for item in ineligible["states"][0]["postings"] if item["id"] == target_id)[
            "reconciliation_eligible"
        ] = False
        with self.assertRaisesRegex(GoldenCaseError, r"evidence_links\[0\]\.target_id"):
            validate_contract_state(ineligible)

        wrong_lot = rg10_contract_case()
        wrong_lot["states"][0]["evidence_links"][-1]["target_id"] = "allocation-cross-rg"
        with self.assertRaisesRegex(GoldenCaseError, r"evidence_links\[1\]\.target_id"):
            validate_contract_state(wrong_lot)

    def test_cross_rg_domain_references_amounts_currencies_and_times_are_validated(self):
        cases = []
        dangling = add_rg05_contract_objects()
        dangling["states"][0]["domain_entities"][-2]["payload"]["expense_posting_id"] = "missing-posting"
        cases.append((dangling, r"expense_posting_id"))

        mismatch = add_rg05_contract_objects()
        mismatch["states"][0]["domain_entities"][-2]["payload"]["amount"] = "35.79"
        cases.append((mismatch, r"amount"))

        for case, path in cases:
            with self.subTest(path=path):
                assert_invalid(self, case, path)

    def test_bonus_and_expiry_domain_payloads_and_roles_are_closed(self):
        for case in (provenance_contract_case(False), provenance_contract_case()):
            with self.subTest(confirmed=len(case["states"][0]["transactions"]) > 1):
                self.assertEqual(schema_errors(case), [])
                validate_contract_state(case)

        unknown = provenance_contract_case(False)
        unknown["states"][0]["domain_entities"][-2]["payload"]["merchant_id"] = (
            "merchant-not-owned-here"
        )
        self.assertTrue(schema_errors(unknown))

        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        roles = set(schema["$defs"]["evidenceLink"]["properties"]["role"]["enum"])
        self.assertTrue(
            {
                "stored_value_bonus_component",
                "stored_value_expiry_confirmation",
            }.issubset(roles)
        )
        self.assertNotIn("stored_value_expiry_event", roles)

    def test_bonus_component_binds_exact_lot_recharge_amount_and_currency(self):
        valid = provenance_contract_case(False)
        validate_contract_state(valid)

        cases = []
        dangling_lot = provenance_contract_case(False)
        dangling_lot["states"][0]["domain_entities"][-2]["payload"]["lot_id"] = (
            "missing-lot"
        )
        cases.append((dangling_lot, r"lot_id"))

        wrong_recharge = provenance_contract_case(False)
        wrong_recharge["states"][0]["domain_entities"][-2]["payload"][
            "recharge_transaction_id"
        ] = "missing-recharge"
        cases.append((wrong_recharge, r"recharge_transaction_id"))

        negative = provenance_contract_case(False)
        negative["states"][0]["domain_entities"][-2]["payload"]["amount"] = "-1.00"
        cases.append((negative, r"amount"))

        mismatched = provenance_contract_case(False)
        mismatched["states"][0]["domain_entities"][-2]["payload"]["amount"] = "9.99"
        cases.append((mismatched, r"amount"))

        wrong_currency = provenance_contract_case(False)
        wrong_currency["states"][0]["domain_entities"][-2]["payload"]["currency"] = (
            "USD"
        )
        cases.append((wrong_currency, r"currency"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

        missing_fact = provenance_contract_case(False)
        state = missing_fact["states"][0]
        state["domain_entities"] = [
            item
            for item in state["domain_entities"]
            if item["type"] != "stored_value_bonus_component"
        ]
        state["evidence_links"] = [
            item
            for item in state["evidence_links"]
            if item["role"] != "stored_value_bonus_component"
        ]
        with self.assertRaisesRegex(GoldenCaseError, r"bonus.*component|bonus.*posting"):
            validate_contract_state(missing_fact)

        zero = rg10_contract_case()
        zero_state = zero["states"][0]
        zero_state["domain_entities"].append(
            {
                "id": "bonus-component-zero",
                "type": "stored_value_bonus_component",
                "payload": {
                    "lot_id": "lot-recharge",
                    "recharge_transaction_id": "transaction-recharge",
                    "amount": "0.00",
                    "currency": "CNY",
                },
            }
        )
        zero_state["evidence_links"].append(
            {
                "id": "link-merchant-bonus-zero",
                "evidence_id": "evidence-merchant-credit",
                "target_kind": "domain_entity",
                "target_id": "bonus-component-zero",
                "role": "stored_value_bonus_component",
            }
        )
        validate_contract_state(zero)

    def test_expiry_lifecycle_is_contiguous_monotonic_and_confirmation_owned(self):
        reminder = provenance_contract_case(False)
        validate_contract_state(reminder)
        self.assertFalse(
            any(
                item["type"] == "stored_value_expiry_loss"
                for item in reminder["states"][0]["transactions"]
            )
        )

        confirmed = provenance_contract_case()
        validate_contract_state(confirmed)
        without_evidence = deepcopy(confirmed)
        state = without_evidence["states"][0]
        state["evidence_links"] = [
            item
            for item in state["evidence_links"]
            if item["role"] != "stored_value_expiry_confirmation"
        ]
        state["evidence"] = [
            item for item in state["evidence"] if item["id"] != "evidence-expiry-confirmation"
        ]
        validate_contract_state(without_evidence)

        cases = []
        bad_sequence = provenance_contract_case()
        bad_sequence["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ][-1]["sequence"] = 3
        cases.append((bad_sequence, r"status_history"))

        non_increasing = provenance_contract_case()
        non_increasing["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ][-1]["recorded_at"] = "2026-01-16T09:00:00+08:00"
        cases.append((non_increasing, r"recorded_at.*strictly later"))

        direct_confirmed = provenance_contract_case()
        history = direct_confirmed["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ]
        history.pop(0)
        history[0]["sequence"] = 1
        cases.append((direct_confirmed, r"status_history"))

        repeated_reminder = provenance_contract_case(False)
        repeated_reminder["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ].append(
            {
                "id": "expiry-event-history-reminder-2",
                "sequence": 2,
                "status": "reminder",
                "recorded_at": "2026-01-17T09:00:00+08:00",
            }
        )
        cases.append((repeated_reminder, r"status_history"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

        reminder_with_loss = provenance_contract_case(False)
        reminder_with_loss["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ][0]["loss_transaction_id"] = "transaction-recharge"
        self.assertTrue(schema_errors(reminder_with_loss))

        confirmed_without_loss = provenance_contract_case()
        del confirmed_without_loss["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ][-1]["loss_transaction_id"]
        self.assertTrue(schema_errors(confirmed_without_loss))

        reused_history_id = provenance_contract_case(False)
        duplicate_event = deepcopy(reused_history_id["states"][0]["domain_entities"][-1])
        duplicate_event["id"] = "expiry-event-other"
        reused_history_id["states"][0]["domain_entities"].append(duplicate_event)
        with self.assertRaisesRegex(GoldenCaseError, r"status_history.*more than one expiry event"):
            validate_contract_state(reused_history_id)

    def test_confirmed_expiry_binds_exact_loss_transaction_amount_and_currency(self):
        cases = []
        dangling = provenance_contract_case()
        dangling["states"][0]["domain_entities"][-1]["payload"]["status_history"][
            -1
        ]["loss_transaction_id"] = "missing-loss"
        cases.append((dangling, r"loss_transaction_id"))

        wrong_type = provenance_contract_case()
        wrong_type["states"][0]["transactions"][-1]["type"] = "expense"
        cases.append((wrong_type, r"loss_transaction_id"))

        wrong_amount = provenance_contract_case()
        wrong_amount["states"][0]["domain_entities"][-1]["payload"]["amount"] = (
            "4.99"
        )
        cases.append((wrong_amount, r"amount"))

        wrong_currency = provenance_contract_case()
        wrong_currency["states"][0]["domain_entities"][-1]["payload"]["currency"] = (
            "USD"
        )
        cases.append((wrong_currency, r"currency"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

        split_legs = provenance_contract_case()
        state = split_legs["states"][0]
        state["postings"][-2]["amount"] = "4.00"
        state["postings"][-1]["amount"] = "-4.00"
        state["posting_sets"][-1]["posting_ids"].extend(
            ["posting-expiry-loss-split", "posting-stored-value-expiry-split"]
        )
        state["postings"].extend(
            [
                {
                    **state["postings"][-2],
                    "id": "posting-expiry-loss-split",
                    "amount": "1.00",
                },
                {
                    **state["postings"][-1],
                    "id": "posting-stored-value-expiry-split",
                    "amount": "-1.00",
                },
            ]
        )
        with self.assertRaisesRegex(GoldenCaseError, r"exactly two postings"):
            validate_contract_state(split_legs)

        extra_roles = provenance_contract_case()
        state = extra_roles["states"][0]
        state["posting_sets"][-1]["posting_ids"].extend(
            ["posting-expiry-extra-in", "posting-expiry-extra-out"]
        )
        state["postings"].extend(
            [
                {
                    "id": "posting-expiry-extra-in",
                    "posting_set_id": "posting-set-expiry-loss",
                    "account_id": "expense-stored-value-expiry",
                    "amount": "1.00",
                    "currency": "CNY",
                    "role": "transfer_fee",
                    "reconciliation_eligible": False,
                },
                {
                    "id": "posting-expiry-extra-out",
                    "posting_set_id": "posting-set-expiry-loss",
                    "account_id": "asset-bank",
                    "amount": "-1.00",
                    "currency": "CNY",
                    "role": "transfer_principal_out",
                    "reconciliation_eligible": False,
                },
            ]
        )
        with self.assertRaisesRegex(GoldenCaseError, r"exactly two postings"):
            validate_contract_state(extra_roles)

        over_face_value = provenance_contract_case()
        state = over_face_value["states"][0]
        state["domain_entities"][-1]["payload"]["amount"] = "50.00"
        state["postings"][-2]["amount"] = "50.00"
        state["postings"][-1]["amount"] = "-50.00"
        with self.assertRaisesRegex(GoldenCaseError, r"face_value"):
            validate_contract_state(over_face_value)

    def test_expiry_loss_ownership_is_reverse_closed_and_order_independent(self):
        for event_first in (True, False):
            valid = provenance_contract_case()
            entities = valid["states"][0]["domain_entities"]
            event = entities.pop()
            entities.insert(0 if event_first else len(entities), event)
            with self.subTest(event_first=event_first):
                validate_contract_state(valid)

        reminder_only = provenance_contract_case()
        state = reminder_only["states"][0]
        state["domain_entities"][-1]["payload"]["status_history"].pop()
        state["evidence_links"].pop()
        state["evidence"].pop()
        with self.assertRaisesRegex(GoldenCaseError, r"expiry_loss.*confirmed|expiry loss.*owned"):
            validate_contract_state(reminder_only)

        no_event = provenance_contract_case()
        state = no_event["states"][0]
        state["domain_entities"].pop()
        state["evidence_links"].pop()
        state["evidence"].pop()
        with self.assertRaisesRegex(GoldenCaseError, r"expiry_loss.*confirmed|expiry loss.*owned"):
            validate_contract_state(no_event)

        for reverse_entities in (True, False):
            duplicate_owner = provenance_contract_case()
            state = duplicate_owner["states"][0]
            duplicate = deepcopy(state["domain_entities"][-1])
            duplicate["id"] = "expiry-event-duplicate-owner"
            duplicate["payload"]["status_history"][0]["id"] = "expiry-duplicate-reminder"
            duplicate["payload"]["status_history"][1]["id"] = "expiry-duplicate-confirmed"
            state["domain_entities"].append(duplicate)
            if reverse_entities:
                state["domain_entities"].reverse()
            with self.subTest(reverse_entities=reverse_entities):
                with self.assertRaisesRegex(GoldenCaseError, r"already owned"):
                    validate_contract_state(duplicate_owner)

    def test_expiry_confirmation_time_matches_loss_transaction_instants(self):
        equivalent_offsets = replace_timestamp_offset(
            provenance_contract_case(), "+08:00", "+00:00"
        )
        equivalent_offsets["case"]["timezone"] = "UTC"
        equivalent_offsets["states"][0]["domain_entities"][-1]["payload"][
            "status_history"
        ][-1]["recorded_at"] = "2026-01-17T09:00:00Z"
        validate_contract_state(equivalent_offsets)

        for field in ("occurred_at", "statistics_at", "effective_at"):
            invalid = provenance_contract_case()
            invalid["states"][0]["transaction_versions"][-1][field] = (
                "2026-01-17T09:00:01+08:00"
            )
            with self.subTest(field=field):
                with self.assertRaisesRegex(GoldenCaseError, rf"{field}.*recorded_at"):
                    validate_contract_state(invalid)

    def test_bonus_and_expiry_evidence_links_are_typed_unique_and_non_financial(self):
        case = provenance_contract_case()
        validate_contract_state(case)
        state = case["states"][0]
        self.assertEqual(state["posting_reconciliations"], [])
        self.assertEqual(
            {
                (item["role"], item["target_kind"], item["target_id"])
                for item in state["evidence_links"]
                if item["role"].startswith("stored_value_")
            },
            {
                ("stored_value_asset_posting", "posting", "posting-stored-value"),
                ("stored_value_lot_fact", "domain_entity", "lot-recharge"),
                (
                    "stored_value_bonus_component",
                    "domain_entity",
                    "bonus-component-recharge",
                ),
                (
                    "stored_value_expiry_confirmation",
                    "domain_entity",
                    "expiry-event-recharge",
                ),
            },
        )

        wrong_bonus = provenance_contract_case()
        wrong_bonus["states"][0]["evidence_links"][-2]["target_id"] = "lot-recharge"
        with self.assertRaisesRegex(GoldenCaseError, r"target_id"):
            validate_contract_state(wrong_bonus)

        wrong_expiry = provenance_contract_case()
        wrong_expiry["states"][0]["evidence_links"][-1]["target_id"] = (
            "bonus-component-recharge"
        )
        with self.assertRaisesRegex(GoldenCaseError, r"target_id"):
            validate_contract_state(wrong_expiry)

        duplicate = provenance_contract_case()
        duplicate_link = deepcopy(duplicate["states"][0]["evidence_links"][-1])
        duplicate_link["id"] = "link-expiry-confirmation-duplicate"
        duplicate["states"][0]["evidence_links"].append(duplicate_link)
        with self.assertRaisesRegex(GoldenCaseError, r"duplicate evidence link"):
            validate_contract_state(duplicate)

        posting_alias = provenance_contract_case()
        posting_alias["states"][0]["evidence_links"][-1].update(
            {"target_kind": "posting", "target_id": "posting-stored-value-expiry"}
        )
        self.assertTrue(schema_errors(posting_alias))

        event_as_role = provenance_contract_case()
        event_as_role["states"][0]["evidence_links"][-1]["role"] = (
            "stored_value_expiry_event"
        )
        self.assertTrue(schema_errors(event_as_role))

    def test_bonus_is_immutable_and_expiry_history_is_append_only(self):
        baseline = provenance_contract_case(False)["states"][0]
        result = provenance_contract_case()["states"][0]
        golden_v2._validate_append_only_transition(baseline, result, "$.operation")

        rewritten_bonus = deepcopy(result)
        rewritten_bonus["domain_entities"][-2]["payload"]["amount"] = "9.99"
        with self.assertRaisesRegex(GoldenCaseError, r"domain_entities.*immutable"):
            golden_v2._validate_append_only_transition(
                baseline, rewritten_bonus, "$.operation"
            )

        rewritten_history = deepcopy(result)
        rewritten_history["domain_entities"][-1]["payload"]["status_history"][0][
            "recorded_at"
        ] = "2026-01-16T09:01:00+08:00"
        with self.assertRaisesRegex(GoldenCaseError, r"status_history.*prefix"):
            golden_v2._validate_append_only_transition(
                baseline, rewritten_history, "$.operation"
            )

    def test_reconstruction_domain_and_typed_audit_topology_are_closed(self):
        case = reconstruction_contract_case()
        self.assertEqual(schema_errors(case), [])
        validate_contract_state(case)

        relation = deepcopy(case)
        relation["states"][0]["relations"] = [
            {
                "id": "relation-reconstruction",
                "type": "stored_value_reconstruction",
                "payload": {"active_mode": "adjustment"},
            }
        ]
        assert_invalid(self, relation, r"relations")

        stateful_link = deepcopy(case)
        stateful_link["states"][0]["audit_links"][-1]["payload"] = {
            "active_mode": "adjustment"
        }
        assert_invalid(self, stateful_link, r"audit_links")

    def test_reconstruction_active_mode_and_history_are_consistent(self):
        reconstructed = reconstruction_contract_case()
        payload = reconstructed["states"][0]["domain_entities"][-1]["payload"]
        payload["active_mode"] = "reconstructed"
        payload["history"].append(
            {
                "id": "reconstruction-history-2",
                "sequence": 2,
                "active_mode": "reconstructed",
                "confirmed_at": "2026-01-15T09:00:00+08:00",
            }
        )
        validate_contract_state(reconstructed)

        cases = []
        empty = reconstruction_contract_case()
        empty_payload = empty["states"][0]["domain_entities"][-1]["payload"]
        empty_payload["active_mode"] = "reconstructed"
        empty_payload["reconstructed_transaction_ids"] = []
        empty_payload["history"][0]["active_mode"] = "reconstructed"
        cases.append((empty, r"reconstructed_transaction_ids"))

        wrong_tail = reconstruction_contract_case()
        wrong_tail["states"][0]["domain_entities"][-1]["payload"]["active_mode"] = (
            "reconstructed"
        )
        cases.append((wrong_tail, r"active_mode"))

        bad_sequence = reconstruction_contract_case()
        bad_sequence["states"][0]["domain_entities"][-1]["payload"]["history"][0][
            "sequence"
        ] = 2
        cases.append((bad_sequence, r"history"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

    def test_reconstruction_rejects_dangling_duplicate_and_cross_group_endpoints(self):
        duplicate = reconstruction_contract_case()
        duplicate["states"][0]["domain_entities"][-1]["payload"][
            "reconstructed_transaction_ids"
        ].append("transaction-recharge")
        self.assertTrue(schema_errors(duplicate))

        cases = []
        dangling = reconstruction_contract_case()
        dangling["states"][0]["domain_entities"][-1]["payload"][
            "reconstructed_transaction_ids"
        ][0] = "missing-transaction"
        dangling["states"][0]["audit_links"][-1]["to"]["id"] = "missing-transaction"
        cases.append((dangling, r"reconstructed_transaction_ids"))

        cross_group = reconstruction_contract_case()
        state = cross_group["states"][0]
        other = deepcopy(state["domain_entities"][-1])
        other["id"] = "reconstruction-group-other"
        other["payload"]["history"][0]["id"] = "reconstruction-other-history-1"
        state["domain_entities"].append(other)
        cases.append((cross_group, r"reconstruction group|reconstructed_transaction_ids"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

        for activation_before_reconstruction in (True, False):
            invalid = cross_group_adjustment_endpoint_case(
                activation_before_reconstruction
            )
            with self.subTest(
                activation_before_reconstruction=activation_before_reconstruction
            ):
                with self.assertRaisesRegex(
                    GoldenCaseError,
                    r"activation adjustment transaction|reconstructed_transaction_ids",
                ):
                    validate_contract_state(invalid)

    def test_reconstruction_history_instants_are_strictly_increasing(self):
        cases = []
        same_instant = replace_timestamp_offset(
            reconstruction_contract_case(), "+08:00", "+00:00"
        )
        same_instant["case"]["timezone"] = "UTC"
        payload = same_instant["states"][0]["domain_entities"][-1]["payload"]
        payload["active_mode"] = "reconstructed"
        payload["history"].append(
            {
                "id": "reconstruction-history-2",
                "sequence": 2,
                "active_mode": "reconstructed",
                "confirmed_at": "2026-01-14T00:30:00Z",
            }
        )
        cases.append(same_instant)

        reversed_instant = deepcopy(same_instant)
        reversed_instant["states"][0]["domain_entities"][-1]["payload"][
            "history"
        ][-1]["confirmed_at"] = "2026-01-14T00:29:59Z"
        cases.append(reversed_instant)

        for invalid in cases:
            with self.subTest(
                confirmed_at=invalid["states"][0]["domain_entities"][-1][
                    "payload"
                ]["history"][-1]["confirmed_at"]
            ):
                with self.assertRaisesRegex(
                    GoldenCaseError,
                    r"confirmed_at.*strictly later",
                ):
                    validate_contract_state(invalid)

    def test_reconstruction_audit_links_exactly_cover_typed_endpoints(self):
        cases = []
        missing = reconstruction_contract_case()
        missing["states"][0]["audit_links"].pop()
        cases.append((missing, r"audit_links"))

        wrong_adjustment_kind = reconstruction_contract_case()
        wrong_adjustment_kind["states"][0]["audit_links"][0]["to"]["kind"] = (
            "transaction"
        )
        wrong_adjustment_kind["states"][0]["audit_links"][0]["to"]["id"] = (
            "transaction-activation-adjustment"
        )
        cases.append((wrong_adjustment_kind, r"audit_links\[0\]\.to"))

        cross_endpoint = reconstruction_contract_case()
        add_second_rg10_recharge(cross_endpoint)
        cross_endpoint["states"][0]["audit_links"][-1]["to"]["id"] = (
            "transaction-recharge-other"
        )
        cases.append((cross_endpoint, r"audit target|audit_links"))

        for invalid, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(invalid)

    def test_reconstruction_history_is_append_only_and_drives_mode_changes(self):
        baseline = reconstruction_contract_case()["states"][0]
        result = deepcopy(baseline)
        payload = result["domain_entities"][-1]["payload"]
        payload["active_mode"] = "reconstructed"
        payload["history"].append(
            {
                "id": "reconstruction-history-2",
                "sequence": 2,
                "active_mode": "reconstructed",
                "confirmed_at": "2026-01-15T09:00:00+08:00",
            }
        )
        golden_v2._validate_append_only_transition(baseline, result, "$.operation")

        rewritten = deepcopy(result)
        rewritten["domain_entities"][-1]["payload"]["history"][0][
            "confirmed_at"
        ] = "2026-01-14T09:00:00+08:00"
        with self.assertRaisesRegex(GoldenCaseError, r"history.*prefix"):
            golden_v2._validate_append_only_transition(
                baseline, rewritten, "$.operation"
            )

        mode_without_history = deepcopy(baseline)
        mode_without_history["domain_entities"][-1]["payload"]["active_mode"] = (
            "reconstructed"
        )
        with self.assertRaisesRegex(GoldenCaseError, r"history|active_mode"):
            golden_v2._validate_append_only_transition(
                baseline, mode_without_history, "$.operation"
            )

    def test_merchant_credit_uses_two_independent_links(self):
        case = rg10_contract_case()
        links = [
            item
            for item in case["states"][0]["evidence_links"]
            if item["evidence_id"] == "evidence-merchant-credit"
        ]
        self.assertEqual(len(links), 2)
        self.assertEqual(len({item["id"] for item in links}), 2)
        self.assertEqual(
            {(item["role"], item["target_kind"], item["target_id"]) for item in links},
            {
                ("stored_value_asset_posting", "posting", "posting-stored-value"),
                ("stored_value_lot_fact", "domain_entity", "lot-recharge"),
            },
        )
        validate_contract_state(case)

        missing_posting_link = deepcopy(case)
        missing_posting_link["states"][0]["evidence_links"].pop(-2)
        with self.assertRaisesRegex(GoldenCaseError, r"evidence-merchant-credit"):
            validate_contract_state(missing_posting_link)

        reused_target = deepcopy(case)
        reused_target["states"][0]["evidence_links"][-1]["target_kind"] = "posting"
        reused_target["states"][0]["evidence_links"][-1]["target_id"] = "posting-stored-value"
        reused_target["states"][0]["evidence_links"][-1]["role"] = "stored_value_asset_posting"
        with self.assertRaisesRegex(GoldenCaseError, r"evidence-merchant-credit"):
            validate_contract_state(reused_target)

    def test_merchant_credit_links_bind_one_current_recharge_transaction(self):
        wrong_type = rg10_contract_case()
        wrong_type["states"][0]["transactions"][0]["type"] = "expense"
        cases = [(wrong_type, r"recharge_transaction_id")]

        cross_recharge = rg10_contract_case()
        add_second_rg10_recharge(cross_recharge)
        cross_recharge["states"][0]["evidence_links"][0]["target_id"] = "posting-stored-value-other"
        cases.append((cross_recharge, r"same recharge transaction"))

        wrong_currency = rg10_contract_case()
        wrong_currency["states"][0]["domain_entities"][0]["payload"]["currency"] = "USD"
        cases.append((wrong_currency, r"currency"))

        wrong_amount = rg10_contract_case()
        wrong_amount["states"][0]["domain_entities"][0]["payload"]["face_value"] = "35.79"
        cases.append((wrong_amount, r"face_value"))

        wrong_time = rg10_contract_case()
        wrong_time["states"][0]["domain_entities"][0]["payload"]["loaded_at"] = (
            "2026-01-15T08:29:00+08:00"
        )
        cases.append((wrong_time, r"loaded_at"))

        for case, path in cases:
            with self.subTest(path=path):
                with self.assertRaisesRegex(GoldenCaseError, path):
                    validate_contract_state(case)

    def test_item_receipt_requires_exactly_one_link_even_when_unbound(self):
        case = add_rg05_contract_objects()
        case["states"][0]["evidence_links"] = []
        assert_invalid(self, case, r"item receipt evidence")

    def test_reconciliation_absence_and_canonical_summaries(self):
        absent = reconciliation_contract_case(None)
        assert_invalid(self, absent, r"posting_reconciliations")

        partial = reconciliation_contract_case(["matched", "pending"])
        validate_golden_case_v2(partial)
        self.assertEqual(partial["states"][0]["derived_statuses"][0]["value"], "partial")

        matched = reconciliation_contract_case(["matched", "matched"])
        validate_golden_case_v2(matched)
        self.assertEqual(matched["states"][0]["derived_statuses"][0]["value"], "matched")

        complete = deepcopy(matched)
        complete["states"][0]["derived_statuses"][0]["value"] = "complete"
        assert_invalid(self, complete, r"reconciliation_summary|derived_statuses")

        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        stored_statuses = schema["$defs"]["postingReconciliation"]["properties"]["status"]["enum"]
        summary_tokens = schema["$defs"]["derivedStatus"]["oneOf"][2]["properties"]["value"]["enum"]
        self.assertNotIn("not_present", stored_statuses)
        self.assertNotIn("complete", summary_tokens)
        self.assertEqual(
            [] if "not_present" == "not_present" else [{"status": "not_present"}],
            [],
        )

    def test_rejects_duplicate_set_like_source_references(self):
        candidate = load_rg09()
        candidate["states"][-1]["candidates"][0]["source_ids"].append(
            "source-target-observation-rg09"
        )
        assert_invalid(self, candidate, r"\$\.states\[4\]\.candidates\[0\]\.source_ids")

        evidence = load_rg09()
        evidence["states"][-1]["evidence"][0]["source_ids"].append(
            "source-target-observation-rg09"
        )
        assert_invalid(self, evidence, r"\$\.states\[4\]\.evidence\[0\]\.source_ids")

    def test_append_only_rejects_in_place_version_and_posting_rewrites(self):
        version = load_rg01()
        version["states"][2]["transaction_versions"][0]["note"] = "rewritten history"
        assert_invalid(self, version, r"\$\.operations\[1\].*transaction_versions.*immutable")

        posting = load_rg01()
        final_state = posting["states"][2]
        expense_posting = next(
            item for item in final_state["postings"] if item["id"] == "posting-expense-rg01"
        )
        expense_posting["role"] = "transfer_fee"
        metrics = final_state["reports"][0]["metrics"]
        next(item for item in metrics if item["metric"] == "consumption")["amount"] = "0.00"
        next(item for item in metrics if item["metric"] == "net_worth_change")["amount"] = "0.00"
        assert_invalid(self, posting, r"\$\.operations\[1\].*postings.*immutable")

    def test_append_only_rejects_rewriting_existing_candidate_history(self):
        case = load_rg09()
        for state in case["states"][3:]:
            state["candidates"][0]["status_history"][0]["status"] = "rejected"
        assert_invalid(self, case, r"\$\.operations\[2\].*candidates.*prefix")

    def test_rejects_confirmation_operation_and_subject_mismatch(self):
        operation = load_rg09()
        operation["states"][2]["confirmations"][0]["operation_id"] = "operation-rg09-preview"
        assert_invalid(
            self,
            operation,
            r"(?:\$\.states\[2\]\.confirmations\[0\]\.operation_id|\$\.operations\[1\].*confirmations)",
        )

        subject = load_rg09()
        subject["states"][2]["confirmations"][0]["subject"]["id"] = "missing-candidate"
        assert_invalid(self, subject, r"\$\.states\[2\]\.confirmations\[0\]\.subject\.id")


class GoldenV2OperationTests(unittest.TestCase):
    def test_rg10_owned_frozen_rejections_execute_end_to_end_atomically(self):
        frozen = load_golden_case(RG10_V1_PATH)
        dispatch = {
            "float-amount": ("confirm_stored_value_recharge", "paid_amount"),
            "numeric-credited-amount": ("confirm_stored_value_recharge", "credited_amount"),
            "numeric-bonus-amount": ("confirm_stored_value_recharge", "bonus_amount"),
            "nonpositive-amount": ("confirm_stored_value_recharge", "paid_amount"),
            "nonpositive-credited-amount": ("confirm_stored_value_recharge", "credited_amount"),
            "negative-bonus-amount": ("confirm_stored_value_recharge", "bonus_amount"),
            "credited-less-than-paid": ("confirm_stored_value_recharge", "credited_amount"),
            "component-mismatch": ("confirm_stored_value_recharge", "bonus_amount"),
            "disabled-stored-account": ("confirm_stored_value_recharge", "stored_value_account_id"),
            "model-overlap": ("confirm_stored_value_recharge", "model"),
            "unconfirmed-expiry": ("confirm_stored_value_expiry_loss", "explicit_confirmation"),
            "unknown-category": ("confirm_stored_value_spend", "category_id"),
            "unknown-payment-account": ("confirm_stored_value_recharge", "payment_account_id"),
            "unowned-payment-account": ("confirm_stored_value_recharge", "payment_account_id"),
            "wrong-payment-account-kind": ("confirm_stored_value_recharge", "payment_account_id"),
            "wrong-stored-account-kind": ("confirm_stored_value_spend", "stored_value_account_id"),
            "wrong-currency": ("confirm_stored_value_recharge", "currency"),
        }
        frozen_by_id = {item["id"]: item for item in frozen["invalid_inputs"]}
        self.assertEqual(set(dispatch), set(frozen_by_id) - {
            "spend-over-balance", "invalid-lot-allocation", "guessed-composition"
        })
        for item_id, (action, field) in dispatch.items():
            source = frozen_by_id[item_id]
            case = rg10_full_rejection_case(
                action,
                deepcopy(source["input"]),
                source["expected"]["reason"],
                field,
            )
            operation = case["operations"][-1]
            with self.subTest(item_id=item_id, action=action):
                self.assertEqual(operation["attempted_input"], source["input"])
                self.assertEqual(operation["status_changes"], [])
                self.assertEqual(operation["returned_ids"], [])
                self.assertTrue(
                    all(
                        not changes[change_type]
                        for changes in operation["deltas"]["entity_changes"].values()
                        for change_type in ("added_ids", "changed_ids", "removed_ids")
                    )
                )
                self.assertEqual(
                    operation["deltas"]["value_changes"],
                    {"balances": [], "reports": [], "derived_statuses": []},
                )
                validate_golden_case_v2(case)

    def test_rg10_owned_rejection_full_validation_rejects_non_atomic_claims(self):
        source = next(
            item
            for item in load_golden_case(RG10_V1_PATH)["invalid_inputs"]
            if item["id"] == "nonpositive-amount"
        )

        changed_result = rg10_full_rejection_case(
            "confirm_stored_value_recharge",
            deepcopy(source["input"]),
            source["expected"]["reason"],
            "paid_amount",
        )
        changed_result["states"][-1]["catalog"]["accounts"][0]["name"] = "Changed"
        assert_invalid(self, changed_result, r"operations\[2\].*(?:immutable|contract-equivalent)")

        nonzero_delta = rg10_full_rejection_case(
            "confirm_stored_value_recharge",
            deepcopy(source["input"]),
            source["expected"]["reason"],
            "paid_amount",
        )
        nonzero_delta["operations"][-1]["deltas"]["entity_changes"]["transactions"][
            "added_ids"
        ] = ["tx-expense-rg01"]
        assert_invalid(self, nonzero_delta, r"deltas\.entity_changes\.transactions")

        status_change = rg10_full_rejection_case(
            "confirm_stored_value_recharge",
            deepcopy(source["input"]),
            source["expected"]["reason"],
            "paid_amount",
        )
        status_change["operations"][-1]["status_changes"] = [
            {
                "target_kind": "transaction",
                "target_id": "tx-expense-rg01",
                "status_name": "reconciliation_summary",
                "before": "pending",
                "after": "confirmed",
            }
        ]
        assert_invalid(self, status_change, r"status_changes")

        returned = rg10_full_rejection_case(
            "confirm_stored_value_recharge",
            deepcopy(source["input"]),
            source["expected"]["reason"],
            "paid_amount",
        )
        returned["operations"][-1]["returned_ids"] = [
            {"kind": "transaction", "id": "tx-expense-rg01"}
        ]
        assert_invalid(self, returned, r"returned_ids")

    def test_rg10_unowned_frozen_rejections_fail_closed_end_to_end(self):
        frozen = load_golden_case(RG10_V1_PATH)
        invalid = {item["id"]: item for item in frozen["invalid_inputs"]}
        cases = [
            (
                "confirm_stored_value_spend",
                invalid["spend-over-balance"],
                "amount",
                "effective stored-value replay owner",
            ),
            (
                "apply_merchant_lot_allocation",
                invalid["invalid-lot-allocation"],
                "amount",
                "remaining-face-value effect owner",
            ),
            (
                "confirm_stored_value_spend",
                invalid["guessed-composition"],
                "paid_bonus_composition",
                "composition provenance owner",
            ),
        ]
        incomplete = frozen["import_path"]["incomplete_confirmations"]
        cases.extend(
            [
                (
                    "confirm_imported_stored_value_recharge",
                    incomplete[0],
                    "bank_payment_confirmed",
                    "import candidate/fact owner",
                ),
                (
                    "confirm_imported_stored_value_spend",
                    incomplete[1],
                    "category_confirmed",
                    "import candidate/fact owner",
                ),
            ]
        )
        for action, source, field, owner in cases:
            case = rg10_full_rejection_case(
                action,
                deepcopy(source["input"]),
                source["expected"]["reason"],
                field,
            )
            with self.subTest(source_id=source["id"], action=action):
                self.assertEqual(case["operations"][-1]["attempted_input"], source["input"])
                with self.assertRaisesRegex(GoldenCaseError, owner):
                    validate_golden_case_v2(case)

    def test_rg10_owned_rejection_predicates_match_frozen_reasons(self):
        cases = [
            ("confirm_stored_value_recharge", {"paid_amount": 1000.0}, "exact_decimal_string_required", "paid_amount"),
            ("confirm_stored_value_recharge", {"credited_amount": 1200.0}, "exact_decimal_string_required", "credited_amount"),
            ("confirm_stored_value_recharge", {"bonus_amount": 200.0}, "exact_decimal_string_required", "bonus_amount"),
            ("confirm_stored_value_recharge", {"paid_amount": "0.00"}, "must_be_positive", "paid_amount"),
            ("confirm_stored_value_recharge", {"credited_amount": "0.00"}, "credited_amount_must_be_positive", "credited_amount"),
            (
                "confirm_stored_value_recharge",
                {"paid_amount": "1000.00", "credited_amount": "999.00", "bonus_amount": "-1.00"},
                "bonus_amount_must_be_zero_or_positive",
                "bonus_amount",
            ),
            (
                "confirm_stored_value_recharge",
                {"paid_amount": "1000.00", "credited_amount": "900.00", "bonus_amount": "0.00"},
                "credited_must_equal_paid_plus_bonus",
                "credited_amount",
            ),
            (
                "confirm_stored_value_recharge",
                {"paid_amount": "1000.00", "credited_amount": "1200.00", "bonus_amount": "100.00"},
                "component_sum_mismatch",
                "bonus_amount",
            ),
            (
                "confirm_stored_value_recharge",
                {"stored_value_account_id": "asset-stored-value-disabled"},
                "stored_value_account_not_enabled",
                "stored_value_account_id",
            ),
            (
                "confirm_stored_value_recharge",
                {"model": "immediate_expense", "stored_value_account_id": "asset-stored-value"},
                "stored_value_models_must_not_overlap",
                "model",
            ),
            (
                "confirm_stored_value_expiry_loss",
                {"lot_id": "lot-recharge", "amount": "5.00", "explicit_confirmation": False},
                "actual_expiry_requires_explicit_confirmation",
                "explicit_confirmation",
            ),
            (
                "confirm_stored_value_spend",
                {"category_id": "category-unknown", "amount": "10.00"},
                "active_secondary_category_required",
                "category_id",
            ),
            (
                "confirm_stored_value_recharge",
                {"payment_account_id": "asset-unknown"},
                "unknown_payment_account",
                "payment_account_id",
            ),
            (
                "confirm_stored_value_recharge",
                {"payment_account_id": "asset-bank-external"},
                "owned_payment_asset_required",
                "payment_account_id",
            ),
            (
                "confirm_stored_value_recharge",
                {"payment_account_id": "expense-stored-value"},
                "owned_payment_asset_required",
                "payment_account_id",
            ),
            (
                "confirm_stored_value_spend",
                {"stored_value_account_id": "asset-bank"},
                "enabled_restricted_stored_value_asset_required",
                "stored_value_account_id",
            ),
            (
                "confirm_stored_value_recharge",
                {"currency": "USD", "paid_amount": "1000.00"},
                "same_cny_currency_required",
                "currency",
            ),
        ]
        for action, attempted_input, reason_code, field in cases:
            with self.subTest(action=action, reason_code=reason_code, field=field):
                assert_rg10_rejection_predicate(
                    self, action, attempted_input, reason_code, field
                )

    def test_rg10_rejection_reason_field_and_priority_must_match_failure(self):
        operation = {
            "action_type": "confirm_stored_value_recharge",
            "attempted_input": {"paid_amount": "0.00", "currency": "USD"},
            "outcome": {
                "status": "rejected",
                "reason_code": "same_cny_currency_required",
                "field_path": "$.attempted_input.currency",
            },
        }
        with self.assertRaisesRegex(GoldenCaseError, r"outcome\.field_path"):
            golden_v2._validate_action_input(
                operation,
                "$.operation",
                rg10_rejection_baseline(),
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        wrong_reason = deepcopy(operation)
        wrong_reason["attempted_input"] = {"paid_amount": "0.00"}
        wrong_reason["outcome"] = {
            "status": "rejected",
            "reason_code": "exact_decimal_string_required",
            "field_path": "$.attempted_input.paid_amount",
        }
        with self.assertRaisesRegex(GoldenCaseError, r"outcome\.reason_code"):
            golden_v2._validate_action_input(
                wrong_reason,
                "$.operation",
                rg10_rejection_baseline(),
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        legal = deepcopy(wrong_reason)
        legal["attempted_input"]["paid_amount"] = "1.00"
        legal["outcome"]["reason_code"] = "must_be_positive"
        with self.assertRaisesRegex(GoldenCaseError, r"does not match an executable"):
            golden_v2._validate_action_input(
                legal,
                "$.operation",
                rg10_rejection_baseline(),
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

    def test_rg10_unowned_rejection_predicates_remain_fail_closed(self):
        cases = [
            (
                "confirm_stored_value_spend",
                {"amount": "36.00"},
                "insufficient_effective_stored_balance",
                "amount",
                "effective stored-value replay owner",
            ),
            (
                "confirm_stored_value_spend",
                {"paid_bonus_composition": "paid_first"},
                "paid_bonus_composition_must_be_evidenced",
                "paid_bonus_composition",
                "composition provenance owner",
            ),
            (
                "confirm_imported_stored_value_recharge",
                {"bank_payment_confirmed": False},
                "bank_payment_model_and_all_recharge_facts_required",
                "bank_payment_confirmed",
                "import candidate/fact owner",
            ),
            (
                "confirm_imported_stored_value_spend",
                {"category_confirmed": False},
                "spend_category_and_behavior_confirmation_required",
                "category_confirmed",
                "import candidate/fact owner",
            ),
            (
                "apply_merchant_lot_allocation",
                {"amount": "901.00"},
                "lot_allocation_exceeds_remaining_face_value",
                "amount",
                "remaining-face-value effect owner",
            ),
        ]
        for action, attempted_input, reason_code, field, owner in cases:
            operation = {
                "action_type": action,
                "attempted_input": attempted_input,
                "outcome": {
                    "status": "rejected",
                    "reason_code": reason_code,
                    "field_path": f"$.attempted_input.{field}",
                },
            }
            with self.subTest(action=action, reason_code=reason_code):
                with self.assertRaisesRegex(GoldenCaseError, owner):
                    golden_v2._validate_action_input(
                        operation,
                        "$.operation",
                        rg10_rejection_baseline(),
                        {"CNY": 2},
                        ZoneInfo("Asia/Shanghai"),
                    )

    def test_rg10_structural_actions_fail_closed_in_full_effect_validation(self):
        inputs = rg10_operation_inputs()
        classes = {
            "confirm_stored_value_recharge": "creation",
            "confirm_stored_value_spend": "creation",
            "ingest_stored_value_recharge_candidate": "creation",
            "ingest_stored_value_spend_candidate": "creation",
            "record_expiry_reminder": "status_transition",
            "confirm_stored_value_expiry_loss": "creation",
            "reconcile_merchant_credit": "reconciliation",
            "reconcile_bank_payment": "reconciliation",
            "apply_merchant_lot_allocation": "update",
            "confirm_stored_value_activation_balance": "adjustment",
            "rename_stored_value_labels": "update",
        }
        for action, operation_class in classes.items():
            for status in ("accepted", "no_change"):
                case = load_rg01()
                case["operations"][0] = rg10_operation_shell(
                    action, operation_class, status, inputs[action]
                )
                with self.subTest(
                    action=action, operation_class=operation_class, status=status
                ):
                    with self.assertRaisesRegex(
                        GoldenCaseError,
                        r"structurally registered but economic effects are not implemented",
                    ):
                        validate_golden_case_v2(case)
    def test_rg10_structural_inputs_validate_scalars_and_reference_kinds(self):
        case = provenance_contract_case()
        baseline = case["states"][0]
        inputs = rg10_operation_inputs()
        selected = {
            "confirm_stored_value_recharge",
            "record_expiry_reminder",
            "confirm_stored_value_expiry_loss",
            "reconcile_merchant_credit",
            "apply_merchant_lot_allocation",
            "confirm_stored_value_activation_balance",
            "rename_stored_value_labels",
        }
        for action in selected:
            operation = {
                "action_type": action,
                "outcome": {"status": "accepted"},
                "input": deepcopy(inputs[action]),
            }
            with self.subTest(action=action):
                golden_v2._validate_action_input(
                    operation,
                    "$.operation",
                    baseline,
                    {"CNY": 2},
                    ZoneInfo("Asia/Shanghai"),
                )

        dangling = {
            "action_type": "record_expiry_reminder",
            "outcome": {"status": "accepted"},
            "input": deepcopy(inputs["record_expiry_reminder"]),
        }
        dangling["input"]["lot_id"] = "missing-lot"
        with self.assertRaisesRegex(GoldenCaseError, r"lot_id"):
            golden_v2._validate_action_input(
                dangling,
                "$.operation",
                baseline,
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        bad_time = {
            "action_type": "confirm_stored_value_expiry_loss",
            "outcome": {"status": "accepted"},
            "input": deepcopy(inputs["confirm_stored_value_expiry_loss"]),
        }
        bad_time["input"]["occurred_at"] = "2026-01-17T08:30:00"
        with self.assertRaisesRegex(GoldenCaseError, r"occurred_at"):
            golden_v2._validate_action_input(
                bad_time,
                "$.operation",
                baseline,
                {"CNY": 2},
                ZoneInfo("Asia/Shanghai"),
            )

    def test_rg10_owned_rejected_action_is_semantically_executable(self):
        case = add_rg01_rejected_attempt(
            {"request_id": "request-rg10-rejected", "amount": None},
            "amount",
            "missing_required_field",
        )
        operation = case["operations"][-1]
        operation["action_type"] = "confirm_stored_value_expiry_loss"
        operation["attempted_input"] = {"explicit_confirmation": False}
        operation["outcome"] = {
            "status": "rejected",
            "reason_code": "actual_expiry_requires_explicit_confirmation",
            "field_path": "$.attempted_input.explicit_confirmation",
        }
        validate_golden_case_v2(case)

    def test_rejects_inexact_entity_and_value_deltas(self):
        entity_delta = load_rg01()
        entity_delta["operations"][0]["deltas"]["entity_changes"]["transactions"]["added_ids"] = []
        assert_invalid(self, entity_delta, r"\$\.operations\[0\]\.deltas\.entity_changes\.transactions")

        value_delta = load_rg01()
        value_delta["operations"][0]["deltas"]["value_changes"]["balances"][0]["after"] = "964.21"
        assert_invalid(self, value_delta, r"\$\.operations\[0\]\.deltas\.value_changes\.balances")

    def test_rejects_status_change_and_derived_delta_mismatch(self):
        case = load_rg09()
        case["operations"][1]["status_changes"][0]["after"] = "fully_explained"
        assert_invalid(self, case, r"\$\.operations\[1\]\.status_changes")

    def test_rejects_undeclared_removal_even_when_delta_lists_it(self):
        case = load_rg01()
        final_state = case["states"][2]
        final_state["transaction_versions"] = [
            version
            for version in final_state["transaction_versions"]
            if version["id"] != "version-expense-rg01-v1"
        ]
        case["operations"][1]["deltas"]["entity_changes"]["transaction_versions"]["removed_ids"] = [
            "version-expense-rg01-v1"
        ]
        assert_invalid(
            self,
            case,
            r"(?:\$\.states\[2\]\.transactions\[0\].*continuous|\$\.operations\[1\]\.append_only\.transaction_versions)",
        )

    def test_rejected_and_no_change_operations_are_atomic(self):
        rejected = add_rg01_rejected_attempt(
            {"request_id": "request-rg01-rejected", "amount": None},
            "amount",
            "missing_required_field",
        )
        rejected_source = {
            "id": "source-rejected-side-effect",
            "type": "explicit_balance_observation",
            "payload": {
                "account_id": "asset-bank-a",
                "target_amount": "964.20",
                "currency": "CNY",
                "target_observed_at": "2026-01-31T23:59:59+08:00",
            },
        }
        rejected["states"][-1]["sources"].append(rejected_source)
        rejected["operations"][-1]["deltas"]["entity_changes"]["sources"][
            "added_ids"
        ] = [rejected_source["id"]]
        assert_invalid(self, rejected, r"\$\.operations\[2\].*contract-equivalent")

        returned = add_rg01_rejected_attempt(
            {"request_id": "request-rg01-returned", "amount": None},
            "amount",
            "missing_required_field",
        )
        returned["operations"][-1]["returned_ids"] = [
            {"kind": "transaction", "id": "tx-expense-rg01"}
        ]
        assert_invalid(self, returned, r"\$\.operations\[2\]\.returned_ids")

        no_change = add_rg01_no_change_retry()
        no_change_source = deepcopy(rejected_source)
        no_change_source["id"] = "source-no-change-side-effect"
        no_change["states"][-1]["sources"].append(no_change_source)
        no_change["operations"][-1]["deltas"]["entity_changes"]["sources"][
            "added_ids"
        ] = [no_change_source["id"]]
        assert_invalid(self, no_change, r"\$\.operations\[2\].*contract-equivalent")

    def test_returned_ids_must_resolve_in_result_state_or_prior_operation(self):
        case = load_rg01()
        case["operations"][0]["returned_ids"][0]["id"] = "missing-confirmation"
        assert_invalid(self, case, r"\$\.operations\[0\]\.returned_ids\[0\]\.id")

    def test_action_reference_failures_remain_golden_case_errors(self):
        case = load_rg01()
        case["operations"][1]["input"]["transaction_id"] = "missing-transaction"
        assert_invalid(self, case, r"\$\.operations\[1\]\.input\.transaction_id")

        category = load_rg01()
        category["operations"][0]["input"]["category_id"] = "missing-category"
        assert_invalid(self, category, r"\$\.operations\[0\]\.input\.category_id")

        candidate = load_rg09()
        candidate["operations"][1]["input"]["candidate_id"] = "missing-candidate"
        assert_invalid(self, candidate, r"\$\.operations\[1\]\.input\.candidate_id")

    def test_accepted_operation_requires_a_declared_effect(self):
        case = load_rg01()
        baseline = case["states"][1]
        result = deepcopy(baseline)
        result["id"] = "state-rg01-note-updated"
        result["as_of_operation_id"] = "operation-rg01-note-update"
        case["states"][2] = result
        operation = case["operations"][1]
        operation["input"]["note"] = ""
        for changes in operation["deltas"]["entity_changes"].values():
            changes["added_ids"] = []
            changes["changed_ids"] = []
            changes["removed_ids"] = []
        operation["returned_ids"] = [{"kind": "transaction", "id": "tx-expense-rg01"}]
        assert_invalid(
            self,
            case,
            r"\$\.operations\[1\].*(?:outcome|transactions|transaction_versions)",
        )

    def test_note_update_requires_a_new_immutable_version(self):
        case = load_rg01()
        baseline = case["states"][1]
        result = deepcopy(baseline)
        result["id"] = "state-rg01-note-updated"
        result["as_of_operation_id"] = "operation-rg01-note-update"
        note_confirmation = next(
            item
            for item in case["states"][2]["confirmations"]
            if item["id"] == "confirmation-rg01-note-update"
        )
        result["confirmations"].append(note_confirmation)
        case["states"][2] = result

        operation = case["operations"][1]
        for changes in operation["deltas"]["entity_changes"].values():
            changes["added_ids"] = []
            changes["changed_ids"] = []
            changes["removed_ids"] = []
        operation["deltas"]["entity_changes"]["confirmations"]["added_ids"] = [
            "confirmation-rg01-note-update"
        ]
        operation["returned_ids"] = [
            {"kind": "confirmation", "id": "confirmation-rg01-note-update"},
            {"kind": "transaction", "id": "tx-expense-rg01"},
        ]
        assert_invalid(
            self,
            case,
            r"\$\.operations\[1\].*(?:transactions|transaction_versions)",
        )

    def test_valid_no_change_retry_returns_the_prior_result(self):
        validate_golden_case_v2(add_rg01_no_change_retry())

        reordered = add_rg01_no_change_retry()
        reordered["operations"][-1]["returned_ids"].reverse()
        validate_golden_case_v2(reordered)

    def test_no_change_requires_prior_equivalent_accepted_request(self):
        missing = add_rg01_no_change_retry()
        missing["operations"][-1]["input"]["request_id"] = "request-not-seen"
        assert_invalid(self, missing, r"\$\.operations\[2\].*no prior accepted")

        conflict = add_rg01_no_change_retry()
        conflict["operations"][-1]["input"]["amount"] = "35.81"
        assert_invalid(self, conflict, r"\$\.operations\[2\].*contract-equivalent input")

        empty = add_rg01_no_change_retry()
        empty["operations"][-1]["returned_ids"] = []
        assert_invalid(self, empty, r"\$\.operations\[2\]\.returned_ids.*non-empty")

        incomplete = add_rg01_no_change_retry()
        incomplete["operations"][-1]["returned_ids"] = [
            {"kind": "transaction", "id": "tx-expense-rg01"}
        ]
        assert_invalid(self, incomplete, r"\$\.operations\[2\]\.returned_ids.*exact")

    def test_non_accepted_operations_still_validate_action_inputs(self):
        rejected = add_rg01_rejected_attempt(
            {
                "request_id": "request-rg01-rejected",
                "amount": None,
                "category_id": "missing-category",
            },
            "amount",
            "missing_required_field",
        )
        assert_invalid(
            self,
            rejected,
            r"\$\.operations\[2\]\.attempted_input\.category_id",
        )

        no_change = add_rg01_no_change_retry()
        no_change["operations"][-1]["input"]["payment_account_id"] = "missing-account"
        assert_invalid(self, no_change, r"\$\.operations\[2\]\.input\.payment_account_id")

    def test_rejected_manual_expense_represents_all_v1_invalid_attempts(self):
        v1 = load_golden_case(RG01_V1_PATH)
        invalid_inputs = v1["invalid_inputs"]
        for invalid in invalid_inputs:
            attempted = {"request_id": f"request-{invalid['id']}"}
            attempted.update(invalid["input"])
            field = invalid["expected"]["field"]
            reason = invalid["expected"].get("reason", "missing_required_field")
            case = load_rg01()
            category_ids = {
                item["id"] for item in case["states"][0]["catalog"]["categories"]
            }
            for category in v1["catalog"]["categories"]:
                if category["id"] not in category_ids:
                    category = deepcopy(category)
                    if (
                        category["parent_id"] is not None
                        and category["posting_account_id"] is None
                    ):
                        category["posting_account_id"] = "expense-account-breakfast"
                    for state in case["states"]:
                        state["catalog"]["categories"].append(deepcopy(category))
            with self.subTest(invalid=invalid["id"]):
                validate_golden_case_v2(
                    add_rg01_rejected_attempt(attempted, field, reason, case)
                )

    def test_rejected_manual_expense_distinguishes_omitted_from_explicit_absence(self):
        attempts = [
            ({"request_id": "missing-amount"}, "amount"),
            (
                {"request_id": "missing-account", "amount": "35.80"},
                "payment_account_id",
            ),
            (
                {
                    "request_id": "missing-category",
                    "amount": "35.80",
                    "payment_account_id": "asset-bank-a",
                },
                "category_id",
            ),
        ]
        for attempted, field in attempts:
            with self.subTest(field=field):
                validate_golden_case_v2(
                    add_rg01_rejected_attempt(
                        attempted, field, "missing_required_field"
                    )
                )

    def test_rejected_manual_expense_uses_stable_failure_precedence(self):
        cases = [
            (
                {
                    "request_id": "all-missing",
                    "amount": None,
                    "payment_account_id": None,
                    "category_id": None,
                },
                "amount",
            ),
            (
                {
                    "request_id": "account-and-category-missing",
                    "amount": "35.80",
                    "payment_account_id": None,
                    "category_id": None,
                },
                "payment_account_id",
            ),
        ]
        for attempted, field in cases:
            with self.subTest(field=field):
                validate_golden_case_v2(
                    add_rg01_rejected_attempt(
                        attempted, field, "missing_required_field"
                    )
                )

    def test_rejected_manual_expense_reason_and_field_must_match_failure(self):
        base = add_rg01_rejected_attempt(
            {
                "request_id": "request-zero",
                "amount": "0.00",
                "payment_account_id": "asset-bank-a",
                "category_id": "expense-category-breakfast",
            },
            "amount",
            "must_be_positive",
        )

        wrong_field = deepcopy(base)
        wrong_field["operations"][-1]["outcome"]["field_path"] = (
            "$.attempted_input.category_id"
        )
        assert_invalid(self, wrong_field, r"\$\.operations\[2\]\.outcome\.field_path")

        wrong_reason = deepcopy(base)
        wrong_reason["operations"][-1]["outcome"]["reason_code"] = (
            "missing_required_field"
        )
        assert_invalid(self, wrong_reason, r"\$\.operations\[2\]\.outcome\.reason_code")

    def test_rejected_manual_expense_validates_present_optional_facts(self):
        base_attempt = {
            "request_id": "request-invalid-optional",
            "amount": None,
            "category_id": "expense-category-breakfast",
            "payment_account_id": "asset-bank-a",
        }
        cases = []

        bad_currency = deepcopy(base_attempt)
        bad_currency["currency"] = "USD"
        cases.append((bad_currency, r"attempted_input\.currency"))

        bad_time = deepcopy(base_attempt)
        bad_time["occurred_at"] = "2026-01-15T08:30:00+07:00"
        cases.append((bad_time, r"attempted_input\.occurred_at"))

        bad_category = deepcopy(base_attempt)
        bad_category["category_id"] = "missing-category"
        cases.append((bad_category, r"attempted_input\.category_id"))

        bad_account = deepcopy(base_attempt)
        bad_account["payment_account_id"] = "missing-account"
        cases.append((bad_account, r"attempted_input\.payment_account_id"))

        for attempted, path in cases:
            with self.subTest(path=path):
                assert_invalid(
                    self,
                    add_rg01_rejected_attempt(
                        attempted, "amount", "missing_required_field"
                    ),
                    path,
                )

    def test_action_registry_validates_all_input_scalars(self):
        expense = load_rg01()
        expense["operations"][0]["input"]["currency"] = "USD"
        assert_invalid(self, expense, r"\$\.operations\[0\]\.input\.currency")

        mismatched_account_currency = load_rg01()
        mismatched_account_currency["case"]["currencies"].append(
            {"code": "USD", "precision": 2}
        )
        mismatched_account_currency["operations"][0]["input"]["currency"] = "USD"
        assert_invalid(
            self,
            mismatched_account_currency,
            r"\$\.operations\[0\]\.input\.currency",
        )

        transfer = load_rg09()
        transfer["operations"][2]["input"]["discovered_at"] = "2026-02-10T17:30:00+07:00"
        assert_invalid(self, transfer, r"\$\.operations\[2\]\.input\.discovered_at")

        explanation = load_rg09()
        explanation["operations"][3]["input"]["explanation_amount"] = "20.0"
        assert_invalid(self, explanation, r"\$\.operations\[3\]\.input\.explanation_amount")

    def test_registered_actions_reject_catalog_changes_even_with_exact_deltas(self):
        account = load_rg01()
        account["states"][2]["catalog"]["accounts"][0]["name"] = "Renamed account"
        account["operations"][1]["deltas"]["entity_changes"]["catalog_accounts"][
            "changed_ids"
        ] = ["asset-bank-a"]
        assert_invalid(self, account, r"\$\.operations\[1\].*catalog_accounts")

        category = load_rg01()
        category["states"][2]["catalog"]["categories"][0]["name"] = "Renamed category"
        category["operations"][1]["deltas"]["entity_changes"]["catalog_categories"][
            "changed_ids"
        ] = ["expense-category-food"]
        assert_invalid(self, category, r"\$\.operations\[1\].*catalog_categories")

    def test_versions_must_be_contiguous_current_and_owned_by_the_action_effect(self):
        ghost = load_rg01()
        ghost_version = deepcopy(ghost["states"][0]["transaction_versions"][0])
        ghost_version["id"] = "version-opening-a-v2-ghost"
        ghost_version["version_number"] = 2
        for state in ghost["states"]:
            state["transaction_versions"].append(deepcopy(ghost_version))
        assert_invalid(
            self,
            ghost,
            r"\$\.states\[0\]\.transactions\[0\]\.current_version_id.*highest",
        )

        gap = load_rg01()
        gap_version = deepcopy(gap["states"][0]["transaction_versions"][0])
        gap_version["id"] = "version-opening-a-v3"
        gap_version["version_number"] = 3
        for state in gap["states"]:
            state["transaction_versions"].append(deepcopy(gap_version))
            next(
                item for item in state["transactions"] if item["id"] == "tx-opening-a"
            )["current_version_id"] = gap_version["id"]
        assert_invalid(
            self,
            gap,
            r"\$\.states\[0\]\.transactions\[0\].*continuous",
        )

        read = load_rg09()
        opening_v2 = deepcopy(read["states"][0]["transaction_versions"][0])
        opening_v2["id"] = "version-opening-rg09-v2"
        opening_v2["version_number"] = 2
        for state in read["states"][1:]:
            state["transaction_versions"].append(deepcopy(opening_v2))
            next(
                item
                for item in state["transactions"]
                if item["id"] == "transaction-opening-rg09"
            )["current_version_id"] = opening_v2["id"]
        preview = read["operations"][0]
        preview["deltas"]["entity_changes"]["transactions"]["changed_ids"] = [
            "transaction-opening-rg09"
        ]
        preview["deltas"]["entity_changes"]["transaction_versions"]["added_ids"] = [
            opening_v2["id"]
        ]
        assert_invalid(
            self,
            read,
            r"\$\.operations\[0\].*(?:transactions|transaction_versions)",
        )

    def test_note_update_rejects_unregistered_entity_side_effects(self):
        case = add_rg09_note_update_with_side_effects()
        assert_invalid(
            self,
            case,
            r"\$\.operations\[4\].*(?:sources|evidence|domain_entities|audit_links)",
        )

    def test_registered_action_effect_entities_must_belong_to_the_action(self):
        cases = []

        manual = load_rg01()
        for state in manual["states"][1:]:
            next(
                item
                for item in state["confirmations"]
                if item["id"] == "confirmation-rg01-create"
            )["subject"]["id"] = "operation-rg01-note-update"
        cases.append(
            ("manual_expense", manual, r"\$\.operations\[0\].*confirmations")
        )

        note = load_rg01()
        next(
            item
            for item in note["states"][2]["transaction_versions"]
            if item["id"] == "version-expense-rg01-v2"
        )["confirmation_id"] = "confirmation-rg01-create"
        cases.append(
            (
                "transaction_note_update",
                note,
                r"\$\.operations\[1\].*confirmations",
            )
        )

        preview = load_rg09()
        for state in preview["states"][1:]:
            next(
                item
                for item in state["evidence"]
                if item["id"] == "evidence-target-observation-rg09"
            )["payload"]["observed_at"] = "2026-01-31T23:59:58+08:00"
        cases.append(
            ("preview_target_balance", preview, r"\$\.operations\[0\].*evidence")
        )

        adjustment = load_rg09()
        for state in adjustment["states"][2:]:
            next(
                item
                for item in state["confirmations"]
                if item["id"] == "confirmation-adjustment-rg09"
            )["confirmed_at"] = "2026-02-01T09:06:00+08:00"
        cases.append(
            (
                "confirm_balance_adjustment",
                adjustment,
                r"\$\.operations\[1\].*confirmations",
            )
        )

        transfer = load_rg09()
        for state in transfer["states"][3:]:
            next(
                item
                for item in state["confirmations"]
                if item["id"] == "confirmation-transfer-rg09"
            )["subject"]["id"] = "operation-rg09-confirm-adjustment"
        cases.append(
            ("confirm_real_transfer", transfer, r"\$\.operations\[2\].*confirmations")
        )

        explanation = load_rg09()
        next(
            item
            for item in explanation["states"][4]["confirmations"]
            if item["id"] == "confirmation-explanation-rg09"
        )["subject"]["id"] = "operation-rg09-confirm-transfer"
        cases.append(
            (
                "confirm_explanation_allocation",
                explanation,
                r"\$\.operations\[3\].*confirmations",
            )
        )

        for action, case, path in cases:
            with self.subTest(action=action):
                assert_invalid(self, case, path)

    def test_registered_action_samples_have_exact_entity_effect_signatures(self):
        cases = [load_rg01(), load_rg09()]
        expected = {
            "manual_expense": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "confirmations": (1, 0, 0),
                "posting_reconciliations": (1, 0, 0),
            },
            "transaction_note_update": {
                "transactions": (0, 1, 0),
                "transaction_versions": (1, 0, 0),
                "confirmations": (1, 0, 0),
            },
            "preview_target_balance": {
                "sources": (1, 0, 0),
                "candidates": (1, 0, 0),
                "evidence": (1, 0, 0),
                "evidence_links": (1, 0, 0),
                "domain_entities": (1, 0, 0),
            },
            "confirm_balance_adjustment": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "candidates": (0, 1, 0),
                "confirmations": (1, 0, 0),
                "domain_entities": (1, 0, 0),
                "audit_links": (1, 0, 0),
            },
            "confirm_real_transfer": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "confirmations": (1, 0, 0),
                "posting_reconciliations": (2, 0, 0),
            },
            "confirm_explanation_allocation": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "confirmations": (1, 0, 0),
                "domain_entities": (1, 0, 0),
                "audit_links": (2, 0, 0),
            },
        }
        observed = {}
        for case in cases:
            for operation in case["operations"]:
                signature = {}
                for collection, changes in operation["deltas"]["entity_changes"].items():
                    counts = (
                        len(changes["added_ids"]),
                        len(changes["changed_ids"]),
                        len(changes["removed_ids"]),
                    )
                    if counts != (0, 0, 0):
                        signature[collection] = counts
                observed[operation["action_type"]] = signature
        self.assertEqual(observed, expected)


class GoldenV2ProjectionTests(unittest.TestCase):
    def test_rejects_rg01_report_and_reconciliation_summary_mutation(self):
        report = load_rg01()
        report["states"][1]["reports"][0]["metrics"][0]["amount"] = "35.81"
        assert_invalid(self, report, r"\$\.states\[1\]\.reports")

        status = load_rg01()
        status["states"][1]["derived_statuses"][0]["value"] = "matched"
        assert_invalid(self, status, r"\$\.states\[1\]\.derived_statuses")

    def test_rejects_rg09_report_mutation_that_misclassifies_internal_principal(self):
        case = load_rg09()
        metrics = case["states"][3]["reports"][0]["metrics"]
        next(metric for metric in metrics if metric["metric"] == "cash_inflow")["amount"] = "20.00"
        assert_invalid(self, case, r"\$\.states\[3\]\.reports")

    def test_rejects_rg09_candidate_adjustment_and_observation_status_mutations(self):
        candidate = load_rg09()
        candidate["states"][1]["derived_statuses"][0]["value"] = "confirmed"
        assert_invalid(self, candidate, r"\$\.states\[1\]\.derived_statuses")

        adjustment = load_rg09()
        adjustment["states"][4]["derived_statuses"][0]["value"] = "fully_explained"
        assert_invalid(self, adjustment, r"\$\.states\[4\]\.derived_statuses")

        observation = load_rg09()
        observation["states"][3]["derived_statuses"][2]["value"] = "fully_reconciled"
        assert_invalid(self, observation, r"\$\.states\[3\]\.derived_statuses")


class GoldenV2IdTests(unittest.TestCase):
    def test_deterministic_id_uses_the_frozen_uuid5_name(self):
        expected = str(
            uuid5(
                UUID("cfad3f84-edb1-5838-ae53-aae49684cf1a"),
                "RG-01\nroot-rg01-main\ntransaction\nmanual-expense",
            )
        )
        actual = deterministic_v2_id(
            "RG-01", "root-rg01-main", "transaction", "manual-expense"
        )
        self.assertEqual(actual, expected)
        self.assertEqual(
            actual,
            deterministic_v2_id(
                "RG-01", "root-rg01-main", "transaction", "manual-expense"
            ),
        )

    def test_deterministic_id_distinguishes_each_identity_component(self):
        baseline = deterministic_v2_id("RG-01", "root-a", "transaction", "semantic")
        variants = {
            deterministic_v2_id("RG-02", "root-a", "transaction", "semantic"),
            deterministic_v2_id("RG-01", "root-b", "transaction", "semantic"),
            deterministic_v2_id("RG-01", "root-a", "posting", "semantic"),
            deterministic_v2_id("RG-01", "root-a", "transaction", "other"),
        }
        self.assertNotIn(baseline, variants)
        self.assertEqual(len(variants), 4)

    def test_deterministic_id_rejects_empty_inputs(self):
        inputs = [
            ("", "root", "transaction", "key"),
            ("RG-01", "", "transaction", "key"),
            ("RG-01", "root", "", "key"),
            ("RG-01", "root", "transaction", ""),
        ]
        for values in inputs:
            with self.subTest(values=values):
                with self.assertRaisesRegex(GoldenCaseError, "must be non-empty"):
                    deterministic_v2_id(*values)

    def test_deterministic_id_rejects_delimiter_and_control_characters(self):
        invalid_values = [
            ("RG-01\nroot", "root", "transaction", "key"),
            ("RG-01", "root\rnext", "transaction", "key"),
            ("RG-01", "root", "trans\x00action", "key"),
            ("RG-01", "root", "transaction", "semantic\nkey"),
        ]
        for values in invalid_values:
            with self.subTest(values=values):
                with self.assertRaisesRegex(GoldenCaseError, "control characters"):
                    deterministic_v2_id(*values)

    def test_migration_ids_use_normalized_locator_and_stable_occurrence(self):
        locator = "$.invalid_inputs[*].input.amount"
        discriminator = "invalid-id=zero-amount"
        semantic_key = locator + "\noccurrence=" + discriminator
        expected_root = str(
            uuid5(
                UUID("cfad3f84-edb1-5838-ae53-aae49684cf1a"),
                "RG-01\n@root\nroot\n" + semantic_key,
            )
        )
        expected_transaction = str(
            uuid5(
                UUID("cfad3f84-edb1-5838-ae53-aae49684cf1a"),
                "RG-01\n" + expected_root + "\ntransaction\n" + semantic_key,
            )
        )

        self.assertEqual(
            migration_semantic_key(locator, discriminator), semantic_key
        )
        self.assertEqual(
            deterministic_v2_root_id("RG-01", locator, discriminator),
            expected_root,
        )
        self.assertEqual(
            deterministic_v2_migration_id(
                "RG-01",
                expected_root,
                "transaction",
                locator,
                discriminator,
            ),
            expected_transaction,
        )

    def test_migration_locator_is_reorder_insensitive_without_array_indexes(self):
        locator = "$.invalid_inputs[*].input.category_id"
        first = deterministic_v2_root_id(
            "RG-01", locator, "invalid-id=primary-category"
        )
        reordered = deterministic_v2_root_id(
            "RG-01", locator, "invalid-id=primary-category"
        )
        other_occurrence = deterministic_v2_root_id(
            "RG-01", locator, "invalid-id=inactive-secondary-category"
        )
        self.assertEqual(first, reordered)
        self.assertNotEqual(first, other_occurrence)

    def test_migration_locator_and_occurrence_reject_unstable_forms(self):
        invalid_locators = [
            "case/invalid_inputs/0",
            "/invalid_inputs/0/input",
            "$.invalid_inputs[0].input",
            "$.invalid_inputs[].input",
            "$..input",
            "$.invalid_inputs[*]/input",
            "$.invalid_inputs[*].in\nput",
        ]
        for locator in invalid_locators:
            with self.subTest(locator=locator):
                with self.assertRaisesRegex(GoldenCaseError, "source locator"):
                    migration_semantic_key(locator, "stable")

        for discriminator in ("", "row\n2", "row\x002"):
            with self.subTest(discriminator=discriminator):
                with self.assertRaisesRegex(GoldenCaseError, "occurrence discriminator"):
                    migration_semantic_key("$.create", discriminator)

    def test_root_bootstrap_is_non_circular(self):
        root_id = deterministic_v2_root_id(
            "RG-01", "$.create", "request-id=request-rg01-create"
        )
        descendant_id = deterministic_v2_migration_id(
            "RG-01",
            root_id,
            "operation",
            "$.create",
            "request-id=request-rg01-create",
        )
        self.assertNotEqual(root_id, descendant_id)
        self.assertEqual(
            root_id,
            deterministic_v2_root_id(
                "RG-01", "$.create", "request-id=request-rg01-create"
            ),
        )


class GoldenV2DependencyIsolationTests(unittest.TestCase):
    def run_isolated(self, source: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PYTHONPATH"] = str(REPOSITORY_ROOT / "tools" / "python")
        return subprocess.run(
            [sys.executable, "-c", source],
            cwd=REPOSITORY_ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_v1_import_does_not_import_jsonschema(self):
        completed = self.run_isolated(
            "from golden_cases import load_golden_case\n"
            "import sys\n"
            "assert 'jsonschema' not in sys.modules\n"
            "assert callable(load_golden_case)\n"
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_v1_import_survives_missing_jsonschema_and_v2_reports_dependency(self):
        completed = self.run_isolated(
            "import builtins\n"
            "real_import = builtins.__import__\n"
            "def blocked(name, *args, **kwargs):\n"
            "    if name == 'jsonschema' or name.startswith('jsonschema.'):\n"
            "        raise ModuleNotFoundError('jsonschema is blocked')\n"
            "    return real_import(name, *args, **kwargs)\n"
            "builtins.__import__ = blocked\n"
            "from golden_cases import load_golden_case\n"
            "assert callable(load_golden_case)\n"
            "try:\n"
            "    from golden_cases import load_golden_case_v2\n"
            "except ImportError as error:\n"
            "    assert 'jsonschema' in str(error)\n"
            "else:\n"
            "    raise AssertionError('v2 import unexpectedly succeeded')\n"
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_v2_lazy_exports_are_normally_available(self):
        import golden_cases

        self.assertTrue(callable(golden_cases.load_golden_case_v2))
        self.assertTrue(callable(golden_cases.validate_golden_case_v2))
        self.assertTrue(callable(golden_cases.deterministic_v2_id))
        self.assertTrue(callable(golden_cases.migration_semantic_key))
        self.assertTrue(callable(golden_cases.deterministic_v2_root_id))
        self.assertTrue(callable(golden_cases.deterministic_v2_migration_id))

class GoldenV2RG04GapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.schema_defs = {"$defs": schema["$defs"]}

    def test_rg04_closed_input_registry_and_strict_confirmation(self):
        samples = {
            "manualMixedExpenseInput": {
                "request_id": "req", "asset_account_id": "asset", "liability_account_id": "liability",
                "asset_funding_amount": "70.00", "liability_funding_amount": "50.00", "total_amount": "120.00",
                "currency": "CNY", "category_id": "category", "occurred_at": "2026-01-20T10:00:00+08:00",
                "settlement_explanation": {"original_amount": "130.00", "discount_amount": "10.00", "settled_amount": "120.00"},
                "explicit_confirmation": True,
            },
            "manualMixedExpenseAttemptedInput": {
                "request_id": "req", "total_amount": "120.00", "currency": "CNY", "category_id": "category",
                "funding_components": [{"account_id": "asset", "funding_amount": "70.00", "currency": "CNY"}, {"account_id": "liability", "funding_amount": "50.00", "currency": "CNY"}],
            },
            "creditPrincipalRepaymentInput": {"request_id": "req", "asset_account_id": "asset", "liability_account_id": "liability", "principal_amount": "50.00", "currency": "CNY", "occurred_at": "2026-01-20T10:00:00+08:00", "explicit_confirmation": True},
            "confirmMixedPaymentCandidateInput": {"request_id": "req", "candidate_id": "candidate", "category_id": "category", "confirmed_funding_components": [{"account_id": "asset", "funding_amount": "70.00", "currency": "CNY"}, {"account_id": "liability", "funding_amount": "50.00", "currency": "CNY"}], "explicit_confirmation": True},
        }
        for definition, sample in samples.items():
            with self.subTest(definition=definition):
                validator = Draft202012Validator({**self.schema_defs, "$ref": f"#/$defs/{definition}"})
                self.assertTrue(validator.is_valid(sample))
                invalid = deepcopy(sample)
                invalid["unexpected"] = True
                self.assertFalse(validator.is_valid(invalid))
                if definition == "confirmMixedPaymentCandidateInput":
                    missing_category = deepcopy(sample)
                    del missing_category["category_id"]
                    self.assertFalse(validator.is_valid(missing_category))

    def test_rg04_source_variants_require_complete_or_single_known_leg(self):
        validator = Draft202012Validator({**self.schema_defs, "$ref": "#/$defs/source"})
        base = {"id": "source", "type": "mixed_payment", "payload": {"evidence_id": "evidence", "observed_at": "2026-01-20T10:00:00+08:00", "total_amount": "120.00", "currency": "CNY", "suggested_category_id": "category", "completeness": "complete", "funding_components": [{"account_id": "asset", "funding_amount": "70.00", "currency": "CNY", "evidence_available": True}, {"account_id": "liability", "funding_amount": "50.00", "currency": "CNY", "evidence_available": True}]}}
        self.assertEqual(base["id"], "source")
        self.assertNotIn("id", base["payload"])
        self.assertTrue(validator.is_valid(base))
        incomplete = deepcopy(base)
        incomplete["payload"]["completeness"] = "missing_funding_leg"
        incomplete["payload"].pop("suggested_category_id")
        incomplete["payload"].pop("funding_components")
        incomplete["payload"]["known_asset_funding_amount"] = "70.00"
        self.assertTrue(validator.is_valid(incomplete))
        forbidden = deepcopy(incomplete)
        forbidden["payload"]["known_asset_funding_amount"] = None
        self.assertFalse(validator.is_valid(forbidden))

    def test_rg04_public_ingest_and_confirmation_validate_end_to_end(self):
        complete = _rg04_complete_source()
        missing = _rg04_missing_source()
        for case in (
            _rg04_public_ingest_case(complete, "candidate-rg04-complete", "1.00", _rg04_complete_candidate_payload(complete)),
            _rg04_public_ingest_case(missing, "candidate-rg04-missing", "0.58", _rg04_missing_candidate_payload(missing)),
            _rg04_public_confirmation_case(),
        ):
            with self.subTest(operations=[item["action_type"] for item in case["operations"]]):
                validate_golden_case_v2(case)

    def test_rg04_public_ingest_confirm_and_mirror_merge_validate_end_to_end(self):
        case = _rg04_public_mirror_case()
        validate_golden_case_v2(case)
        confirmed = case["states"][-2]
        mirrored = case["states"][-1]
        self.assertEqual(len(mirrored["candidates"]), len(confirmed["candidates"]))
        self.assertEqual(mirrored["transactions"], confirmed["transactions"])
        self.assertEqual(mirrored["transaction_versions"], confirmed["transaction_versions"])
        self.assertEqual(mirrored["postings"], confirmed["postings"])

        invalid_evidence = deepcopy(case)
        next(
            item
            for item in invalid_evidence["states"][-1]["evidence"]
            if item["id"] == "evidence-rg04-credit-mirror"
        )["type"] = "asset_funding_debit"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid_evidence)

        invalid_payload = deepcopy(case)
        next(
            item
            for item in invalid_payload["states"][-1]["sources"]
            if item["id"] == "source-rg04-credit-mirror"
        )["payload"]["amount"] = "49.00"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid_payload)

        malformed_payload = deepcopy(case)
        next(
            item
            for item in malformed_payload["states"][-1]["sources"]
            if item["id"] == "source-rg04-credit-mirror"
        )["payload"]["amount"] = []
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(malformed_payload)

        mirror_candidate = deepcopy(case)
        next(
            item
            for item in mirror_candidate["states"][-1]["candidates"]
            if item["id"] == "candidate-purchase-rg04"
        )["source_ids"] = ["source-rg04-credit-mirror"]
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(mirror_candidate)

    def test_rg04_public_ingest_rejects_candidate_contract_drift(self):
        complete = _rg04_complete_source()
        missing = _rg04_missing_source()
        cases = (
            (_rg04_public_ingest_case(complete, "candidate-rg04-complete", "1.00", _rg04_complete_candidate_payload(complete)), "candidate-rg04-complete"),
            (_rg04_public_ingest_case(missing, "candidate-rg04-missing", "0.58", _rg04_missing_candidate_payload(missing)), "candidate-rg04-missing"),
        )
        mutations = (
            ("amount", lambda candidate: candidate["payload"].update(total_amount="121.00")),
            ("provenance", lambda candidate: candidate["payload"]["provenance"].update(rule_version=2)),
            ("evidence", lambda candidate: candidate["payload"].update(evidence_refs=["evidence-missing"])),
            ("confirmation_array", lambda candidate: candidate["payload"].update(requires_confirmation=["formal_transaction_creation"])),
            ("confidence", lambda candidate: candidate.update(confidence="0.75")),
        )
        for original, candidate_id in cases:
            for name, mutation in mutations:
                invalid = deepcopy(original)
                candidate = next(item for item in invalid["states"][-1]["candidates"] if item["id"] == candidate_id)
                mutation(candidate)
                with self.subTest(candidate_id=candidate_id, mutation=name):
                    with self.assertRaises(GoldenCaseError):
                        validate_golden_case_v2(invalid)

        category_drift = _rg04_public_ingest_case(complete, "candidate-rg04-complete", "1.00", _rg04_complete_candidate_payload(complete))
        next(item for item in category_drift["states"][-1]["candidates"] if item["id"] == "candidate-rg04-complete")["payload"]["suggested_category_id"] = "expense-category-living"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(category_drift)

        dangling_category = deepcopy(_rg04_complete_source())
        dangling_category["suggested_category_id"] = "category-missing"
        dangling_case = _rg04_public_ingest_case(
            dangling_category,
            "candidate-rg04-dangling-category",
            "1.00",
            _rg04_complete_candidate_payload(dangling_category),
        )
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(dangling_case)

    def test_rg04_confirmation_uses_source_observed_at_for_all_formal_time_roles(self):
        case = _rg04_public_confirmation_case()
        validate_golden_case_v2(case)
        for field in ("occurred_at", "statistics_at", "effective_at"):
            invalid = deepcopy(case)
            version = next(item for item in invalid["states"][-1]["transaction_versions"] if item["id"] == "version-purchase-rg04-imported-v1")
            version[field] = "2026-02-13T12:00:00+08:00"
            with self.subTest(field=field):
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(invalid)

    def test_rg04_source_time_binding_survives_later_current_replacement_version(self):
        _, _, state, _, confirmation_operation = _rg04_confirmation_case()
        transaction = next(
            item for item in state["transactions"] if item["id"] == "tx-purchase-rg04-imported"
        )
        original_version = next(
            item
            for item in state["transaction_versions"]
            if item["id"] == "version-purchase-rg04-imported-v1"
        )
        replacement_version = deepcopy(original_version)
        replacement_version.update(
            id="version-purchase-rg04-imported-v2",
            version_number=2,
            occurred_at="2026-02-13T12:00:00+08:00",
            statistics_at="2026-02-13T12:00:00+08:00",
            effective_at="2026-02-13T12:00:00+08:00",
            created_at="2026-02-13T12:01:00+08:00",
            confirmation_id="confirmation-rg04-correction",
        )
        state["transaction_versions"].append(replacement_version)
        transaction["current_version_id"] = replacement_version["id"]
        state["confirmations"].append({
            "id": "confirmation-rg04-correction",
            "type": "explicit_manual_save",
            "operation_id": "operation-rg04-correction",
            "subject": {"kind": "transaction", "id": transaction["id"]},
            "payload": {},
        })
        operations = {
            confirmation_operation["id"]: confirmation_operation,
            "operation-rg04-correction": {
                "id": "operation-rg04-correction",
                "root_id": state["root_id"],
            },
        }
        indexes = golden_v2._state_indexes(state, "$.states[0]")
        golden_v2._validate_formal_ledger(
            state,
            "$.states[0]",
            indexes,
            {"CNY": 2, "USD": 2},
            ZoneInfo("Asia/Shanghai"),
        )
        golden_v2._validate_references(
            state,
            "$.states[0]",
            indexes,
            operations,
            {"CNY": 2, "USD": 2},
            ZoneInfo("Asia/Shanghai"),
        )

        invalid = deepcopy(state)
        next(
            item
            for item in invalid["transaction_versions"]
            if item["id"] == original_version["id"]
        )["statistics_at"] = "2026-02-12T12:00:00+08:00"
        invalid_indexes = golden_v2._state_indexes(invalid, "$.states[0]")
        with self.assertRaisesRegex(GoldenCaseError, "candidate-confirmed version times"):
            golden_v2._validate_references(
                invalid,
                "$.states[0]",
                invalid_indexes,
                operations,
                {"CNY": 2, "USD": 2},
                ZoneInfo("Asia/Shanghai"),
            )

    def test_rg04_malformed_mixed_payment_shapes_raise_golden_case_error(self):
        for payload in (None, [], {"currency": "CNY", "total_amount": []}):
            with self.subTest(payload=payload):
                with self.assertRaises(GoldenCaseError):
                    golden_v2._mixed_payment_candidate_contract(payload, "$.source")

    def test_rg04_confirm_no_change_replay_accepts_confirmed_bound_candidate(self):
        validate_golden_case_v2(_rg04_public_confirmation_case(include_replay=True))

    def test_rg04_confirm_replay_allows_inactive_category_but_preserves_category_contract(self):
        _, fresh_baseline, confirmed, _, operation = _rg04_confirmation_case()
        category_id = operation["input"]["category_id"]
        next(
            item
            for item in fresh_baseline["catalog"]["categories"]
            if item["id"] == category_id
        )["active"] = False
        with self.assertRaisesRegex(GoldenCaseError, "fresh confirmation requires active"):
            golden_v2._validate_action_input(
                operation,
                "$.operations[0]",
                fresh_baseline,
                {"CNY": 2, "USD": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        replay = deepcopy(operation)
        replay["outcome"] = {"status": "no_change", "reason_code": "idempotent_replay"}
        next(
            item
            for item in confirmed["catalog"]["categories"]
            if item["id"] == category_id
        )["active"] = False
        golden_v2._validate_action_input(
            replay,
            "$.operations[1]",
            confirmed,
            {"CNY": 2, "USD": 2},
            ZoneInfo("Asia/Shanghai"),
        )

        invalid_replays = []
        invalid = deepcopy((confirmed, replay))
        invalid[1]["input"]["category_id"] = "expense-category-living"
        invalid_replays.append(invalid)
        invalid = deepcopy((confirmed, replay))
        next(
            item
            for item in invalid[0]["catalog"]["categories"]
            if item["id"] == category_id
        )["posting_account_id"] = "asset-bank-a"
        invalid_replays.append(invalid)
        invalid = deepcopy((confirmed, replay))
        next(
            item
            for item in invalid[0]["catalog"]["accounts"]
            if item["id"] == "expense-account-daily"
        )["currency"] = "USD"
        invalid_replays.append(invalid)
        for baseline, invalid_replay in invalid_replays:
            with self.subTest(input=invalid_replay["input"]):
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_action_input(
                        invalid_replay,
                        "$.operations[1]",
                        baseline,
                        {"CNY": 2, "USD": 2},
                        ZoneInfo("Asia/Shanghai"),
                    )

    def test_rg04_historical_inactive_category_and_global_posting_category_ownership(self):
        state, context = rg04_posting_semantics_fixture("expense")
        next(item for item in state["catalog"]["categories"] if item["id"] == "expense-category-breakfast")["active"] = False
        indexes = golden_v2._state_indexes(state, "$.states[0]")
        golden_v2._validate_formal_ledger(state, "$.states[0]", indexes, context["precisions"], ZoneInfo("Asia/Shanghai"))

        normal = deepcopy(load_rg01()["states"][-1])
        expense = next(item for item in normal["postings"] if item["id"] == "posting-expense-rg01")
        expense["category_id"] = "expense-category-breakfast"
        indexes = golden_v2._state_indexes(normal, "$.states[0]")
        golden_v2._validate_formal_ledger(normal, "$.states[0]", indexes, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))
        for category_id in ("category-missing", "expense-category-food"):
            invalid = deepcopy(normal)
            next(item for item in invalid["postings"] if item["id"] == "posting-expense-rg01")["category_id"] = category_id
            indexes = golden_v2._state_indexes(invalid, "$.states[0]")
            with self.subTest(category_id=category_id):
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_formal_ledger(invalid, "$.states[0]", indexes, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))

        repayment, repayment_context = rg04_posting_semantics_fixture("credit_repayment")
        next(item for item in repayment["postings"] if item.get("role") == "credit_repayment_asset_outflow")["category_id"] = "expense-category-breakfast"
        indexes = golden_v2._state_indexes(repayment, "$.states[0]")
        with self.assertRaises(GoldenCaseError):
            golden_v2._validate_formal_ledger(repayment, "$.states[0]", indexes, repayment_context["precisions"], ZoneInfo("Asia/Shanghai"))

    def test_rg04_operation_registry_has_no_generic_kind_escape(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        actions = schema["$defs"]["operationBase"]["properties"]["action_type"]["enum"]
        self.assertIn("manual_mixed_expense", actions)
        self.assertIn("credit_principal_repayment", actions)
        self.assertIn("ingest_mixed_payment_source", actions)
        self.assertIn("confirm_mixed_payment_candidate", actions)
        self.assertIn("merge_mixed_payment_mirror_evidence", actions)
        self.assertNotIn("kind", schema["$defs"]["operationBase"]["properties"])

    def test_rg04_manual_mixed_expense_creation_has_exact_effects(self):
        case, baseline, result = rg04_creation_case("manual_mixed_expense")
        operation = case["operations"][0]
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_action_input(operation, "$.operations[0]", baseline, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))
        golden_v2._validate_registered_action_effects(operation, "$.operations[0]", result, expected)
        golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, result, expected)
        self.assertEqual(operation["returned_ids"][1], {"kind": "transaction", "id": "tx-rg04-expense"})
        self.assertTrue(operation["input"]["explicit_confirmation"])
        self.assertEqual({item["role"] for item in result["postings"][-3:]}, {"expense", "mixed_expense_asset_funding", "mixed_expense_credit_funding"})
        self.assertEqual({item["account_id"]: item["amount"] for item in result["balances"] if item["account_id"] in {"asset-bank-a", "liability-credit-b"}}, {"asset-bank-a": "930.00", "liability-credit-b": "-50.00"})
        self.assertEqual({item["key"]["metric"]: item["after"]["amount"] for item in operation["deltas"]["value_changes"]["reports"]}, {"cash_outflow": "70.00", "consumption": "120.00", "net_worth_change": "-120.00"})

    def test_rg04_credit_principal_repayment_creation_has_no_consumption(self):
        case, baseline, result = rg04_creation_case("credit_principal_repayment")
        operation = case["operations"][0]
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_action_input(operation, "$.operations[0]", baseline, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))
        golden_v2._validate_registered_action_effects(operation, "$.operations[0]", result, expected)
        golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, result, expected)
        self.assertEqual(operation["returned_ids"][1], {"kind": "transaction", "id": "tx-rg04-repayment"})
        self.assertEqual({item["role"] for item in result["postings"][-2:]}, {"credit_repayment_asset_outflow", "credit_repayment_liability_principal"})
        self.assertEqual({item["key"]["metric"]: item["after"]["amount"] for item in operation["deltas"]["value_changes"]["reports"]}, {"cash_outflow": "50.00"})

    def test_rg04_creation_rejects_missing_confirmation_and_wrong_accounts_or_roles(self):
        case, baseline, result = rg04_creation_case("manual_mixed_expense")
        missing_confirmation = deepcopy(case)
        missing_confirmation["operations"][0]["input"]["explicit_confirmation"] = False
        with self.assertRaisesRegex(GoldenCaseError, "explicit_confirmation"):
            validate_golden_case_v2(missing_confirmation)

        operation = case["operations"][0]
        with self.assertRaisesRegex(GoldenCaseError, "matching owned real account"):
            invalid = deepcopy(operation)
            invalid["input"]["liability_account_id"] = "asset-bank-a"
            golden_v2._validate_action_input(invalid, "$.operations[0]", baseline, {"CNY": 2}, ZoneInfo("Asia/Shanghai"))

        expected = golden_v2._expected_entity_changes(baseline, result)
        invalid_result = deepcopy(result)
        invalid_result["postings"][-1]["role"] = "expense"
        with self.assertRaisesRegex(GoldenCaseError, "exact expense and two funding roles"):
            golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, invalid_result, expected)

        for mutation, message in (
            (lambda state: next(item for item in state["postings"] if item.get("role") == "expense").pop("category_id"), "confirmed category"),
            (lambda state: next(item for item in state["postings"] if item.get("role") == "expense").update(category_id="expense-category-living"), "confirmed category"),
            (lambda state: next(item for item in state["postings"] if item.get("role") == "mixed_expense_asset_funding").update(category_id="expense-category-daily"), "funding postings"),
        ):
            invalid_result = deepcopy(result)
            mutation(invalid_result)
            invalid_expected = golden_v2._expected_entity_changes(baseline, invalid_result)
            with self.assertRaisesRegex(GoldenCaseError, message):
                golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, invalid_result, invalid_expected)

    def test_rg04_ingest_action_input_validates_complete_and_missing_leg_variants(self):
        _, baseline = _rg04_import_baseline()
        complete = {
            "request_id": "request-rg04-complete-input",
            "source_record": _rg04_complete_source(),
        }
        missing = {
            "request_id": "request-rg04-missing-input",
            "source_record": _rg04_missing_source(),
        }

        def validate(source_input):
            golden_v2._validate_action_input(
                {"action_type": "ingest_mixed_payment_source", "input": source_input, "outcome": {"status": "accepted"}},
                "$.operations[0]",
                baseline,
                {"CNY": 2, "USD": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        validate(complete)
        validate(missing)

        invalid_complete = []
        mutation = deepcopy(complete)
        mutation["source_record"]["funding_components"][0]["account_id"] = "unknown-account"
        invalid_complete.append(mutation)
        mutation = deepcopy(complete)
        mutation["source_record"]["funding_components"][0]["account_id"] = "expense-account-breakfast"
        invalid_complete.append(mutation)
        mutation = deepcopy(complete)
        mutation["source_record"]["funding_components"][1]["account_id"] = "asset-bank-a"
        invalid_complete.append(mutation)
        mutation = deepcopy(complete)
        mutation["source_record"]["funding_components"][1]["currency"] = "USD"
        invalid_complete.append(mutation)
        for invalid in invalid_complete:
            with self.subTest(mutation=invalid):
                with self.assertRaises(GoldenCaseError):
                    validate(invalid)

        for amount in ("0.00", "120.00", "130.00"):
            invalid = deepcopy(missing)
            invalid["source_record"]["known_asset_funding_amount"] = amount
            with self.subTest(known_asset_funding_amount=amount):
                with self.assertRaises(GoldenCaseError):
                    validate(invalid)

    def test_rg04_ingest_complete_and_missing_inputs_create_pending_candidates_only(self):
        case, baseline = _rg04_import_baseline()
        cases = (
            (_rg04_complete_source(), "candidate-rg04-complete", "1.00", _rg04_complete_candidate_payload),
            (_rg04_missing_source(), "candidate-rg04-missing", "0.58", _rg04_missing_candidate_payload),
        )
        for source, candidate_id, confidence, payload_builder in cases:
            with self.subTest(source_id=source["id"]):
                result = _rg04_ingested_state(
                    baseline,
                    source,
                    candidate_id,
                    confidence,
                    payload_builder(source),
                )
                operation = _rg04_action(
                    case,
                    baseline,
                    result,
                    "ingest_mixed_payment_source",
                    {"request_id": f"request-{candidate_id}", "source_record": source},
                    f"operation-{candidate_id}-ingest",
                )
                operation["returned_ids"] = [
                    {"kind": "source", "id": source["id"]},
                    {"kind": "evidence", "id": source["evidence_id"]},
                    {"kind": "candidate", "id": candidate_id},
                ]
                expected = golden_v2._expected_entity_changes(baseline, result)
                golden_v2._validate_action_input(operation, "$.operations[0]", baseline, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai"))
                golden_v2._validate_registered_action_effects(operation, "$.operations[0]", result, expected)
                golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, result, expected)
                self.assertEqual(result["candidates"][-1]["status_history"][-1]["status"], "pending_confirmation")
                self.assertEqual(result["candidates"][-1]["confidence"], confidence)
                self.assertEqual(result["transactions"], baseline["transactions"])
                self.assertEqual(golden_v2._balance_map(result), golden_v2._balance_map(baseline))
                self.assertEqual(golden_v2._report_map(result), golden_v2._report_map(baseline))

    def test_rg04_confirm_candidate_creates_expense_roles_and_mixed_payment_relation(self):
        _, baseline, result, input_value, operation = _rg04_confirmation_case()
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_action_input(operation, "$.operations[0]", baseline, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai"))
        golden_v2._validate_registered_action_effects(operation, "$.operations[0]", result, expected)
        golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, result, expected)
        self.assertEqual(
            {posting["role"] for posting in result["postings"][-3:]},
            {"expense", "mixed_expense_asset_funding", "mixed_expense_credit_funding"},
        )
        self.assertEqual(result["relations"][-1]["type"], "mixed_payment")
        self.assertEqual(
            {item["kind"] for item in result["relations"][-1]["member_refs"]},
            {"transaction", "posting"},
        )
        expense = next(posting for posting in result["postings"] if posting.get("role") == "expense" and posting["posting_set_id"] == "posting-set-purchase-rg04-imported")
        self.assertEqual(expense["category_id"], input_value["category_id"])

    def test_rg04_confirm_candidate_rejects_invalid_candidate_category_and_components(self):
        def validate(baseline, operation):
            golden_v2._validate_action_input(
                operation,
                "$.operations[0]",
                baseline,
                {"CNY": 2, "USD": 2},
                ZoneInfo("Asia/Shanghai"),
            )

        invalid_cases = []

        _, baseline, _, _, operation = _rg04_confirmation_case()
        operation["input"]["candidate_id"] = "candidate-missing"
        invalid_cases.append(("missing_candidate", baseline, operation))

        _, baseline, _, _, operation = _rg04_confirmation_case()
        operation["input"]["candidate_id"] = []
        invalid_cases.append(("malformed_candidate_id", baseline, operation))

        for name, mutation in (
            ("candidate_status", lambda candidate: candidate["status_history"].append({"id": "status-already-confirmed", "sequence": 2, "status": "confirmed"})),
            ("empty_candidate_status", lambda candidate: candidate.update(status_history=[])),
            ("candidate_kind", lambda candidate: candidate.update(type="account_transfer")),
            ("candidate_transaction", lambda candidate: candidate["payload"].update(transaction_id="tx-already-created")),
        ):
            _, baseline, _, _, operation = _rg04_confirmation_case()
            candidate = next(item for item in baseline["candidates"] if item["id"] == operation["input"]["candidate_id"])
            mutation(candidate)
            invalid_cases.append((name, baseline, operation))

        for name, category_mutation in (
            ("missing_category", lambda baseline, operation: operation["input"].update(category_id="category-missing")),
            ("first_level_category", lambda baseline, operation: operation["input"].update(category_id="expense-category-living")),
            ("inactive_category", lambda baseline, operation: next(item for item in baseline["catalog"]["categories"] if item["id"] == operation["input"]["category_id"]).update(active=False)),
            ("dangling_posting_account", lambda baseline, operation: next(item for item in baseline["catalog"]["categories"] if item["id"] == operation["input"]["category_id"]).update(posting_account_id="account-missing")),
            ("non_expense_posting_account", lambda baseline, operation: next(item for item in baseline["catalog"]["categories"] if item["id"] == operation["input"]["category_id"]).update(posting_account_id="asset-bank-a")),
        ):
            _, baseline, _, _, operation = _rg04_confirmation_case()
            category_mutation(baseline, operation)
            invalid_cases.append((name, baseline, operation))

        _, baseline, _, _, operation = _rg04_confirmation_case()
        operation["input"]["category_id"] = []
        invalid_cases.append(("malformed_category_id", baseline, operation))

        component_mutations = (
            ("one_component", lambda baseline, components: components.pop()),
            ("missing_component_currency", lambda baseline, components: components[0].pop("currency")),
            ("malformed_component_account", lambda baseline, components: components[0].update(account_id=[])),
            ("duplicate_account", lambda baseline, components: components[1].update(account_id=components[0]["account_id"])),
            ("wrong_kind", lambda baseline, components: next(item for item in baseline["catalog"]["accounts"] if item["id"] == components[1]["account_id"]).update(kind="asset")),
            ("not_owned", lambda baseline, components: next(item for item in baseline["catalog"]["accounts"] if item["id"] == components[0]["account_id"]).update(owned_by_user=False)),
            ("not_real", lambda baseline, components: next(item for item in baseline["catalog"]["accounts"] if item["id"] == components[1]["account_id"]).update(real_account=False)),
            ("component_currency", lambda baseline, components: components[0].update(currency="USD")),
            ("account_currency", lambda baseline, components: next(item for item in baseline["catalog"]["accounts"] if item["id"] == components[0]["account_id"]).update(currency="USD")),
            ("zero_amount", lambda baseline, components: components[0].update(funding_amount="0.00")),
            ("wrong_sum", lambda baseline, components: components[1].update(funding_amount="49.00")),
        )
        for name, component_mutation in component_mutations:
            _, baseline, _, _, operation = _rg04_confirmation_case()
            component_mutation(baseline, operation["input"]["confirmed_funding_components"])
            invalid_cases.append((name, baseline, operation))

        _, baseline, _, _, operation = _rg04_confirmation_case()
        candidate = next(item for item in baseline["candidates"] if item["id"] == operation["input"]["candidate_id"])
        candidate["payload"]["currency"] = "USD"
        invalid_cases.append(("candidate_currency", baseline, operation))

        _, baseline, _, _, operation = _rg04_confirmation_case()
        operation["input"]["explicit_confirmation"] = False
        invalid_cases.append(("explicit_confirmation", baseline, operation))

        for name, baseline, operation in invalid_cases:
            with self.subTest(name=name):
                with self.assertRaises(GoldenCaseError):
                    validate(baseline, operation)

    def test_rg04_confirm_candidate_rejects_result_posting_candidate_binding_and_relation_mutations(self):
        def validate(result, baseline, operation):
            expected = golden_v2._expected_entity_changes(baseline, result)
            golden_v2._validate_registered_action_effects(
                operation, "$.operations[0]", result, expected
            )
            golden_v2._validate_action_semantics(
                operation, "$.operations[0]", baseline, result, expected
            )

        mutations = (
            ("expense_category", lambda result: next(item for item in result["postings"] if item.get("role") == "expense").update(category_id="expense-category-other")),
            ("expense_account", lambda result: next(item for item in result["postings"] if item.get("role") == "expense").update(account_id="asset-bank-a")),
            ("asset_funding_account", lambda result: next(item for item in result["postings"] if item.get("role") == "mixed_expense_asset_funding").update(account_id="liability-credit-b")),
            ("asset_funding_amount", lambda result: next(item for item in result["postings"] if item.get("role") == "mixed_expense_asset_funding").update(amount="-69.00")),
            ("liability_funding_currency", lambda result: next(item for item in result["postings"] if item.get("role") == "mixed_expense_credit_funding").update(currency="USD")),
            ("candidate_confidence", lambda result: next(item for item in result["candidates"] if item["id"] == "candidate-purchase-rg04").update(confidence="0.50")),
            ("candidate_payload", lambda result: next(item for item in result["candidates"] if item["id"] == "candidate-purchase-rg04")["payload"].update(currency="USD")),
            ("candidate_binding", lambda result: next(item for item in result["candidates"] if item["id"] == "candidate-purchase-rg04")["payload"].update(transaction_id="tx-wrong")),
            ("candidate_history", lambda result: next(item for item in result["candidates"] if item["id"] == "candidate-purchase-rg04")["status_history"][0].update(status="confirmed")),
            ("transaction_binding", lambda result: next(item for item in result["transactions"] if item["id"] == "tx-purchase-rg04-imported").update(type="account_transfer")),
            ("relation_transaction", lambda result: result["relations"][-1]["member_refs"][0].update(id="tx-wrong")),
            ("relation_funding_member", lambda result: result["relations"][-1]["member_refs"][1].update(id="posting-expense-rg04-imported")),
            ("relation_extra_member", lambda result: result["relations"][-1]["member_refs"].append({"kind": "posting", "id": "posting-expense-rg04-imported"})),
        )
        for name, mutation in mutations:
            _, baseline, result, _, operation = _rg04_confirmation_case()
            mutation(result)
            with self.subTest(name=name):
                with self.assertRaises(GoldenCaseError):
                    validate(result, baseline, operation)

    def test_rg04_mirror_adds_credit_liability_evidence_and_matches_one_reconciliation(self):
        case, baseline = _rg04_import_baseline()
        source = _rg04_complete_source()
        baseline = _rg04_ingested_state(
            baseline,
            source,
            "candidate-purchase-rg04",
            "1.00",
            _rg04_complete_candidate_payload(source),
        )
        baseline = _rg04_confirmed_state(baseline)
        result = deepcopy(baseline)
        result["id"] = "state-rg04-mirror"
        result["as_of_operation_id"] = "operation-rg04-mirror"
        mirror_source = _rg04_mirror_source()
        result["sources"].append({"id": mirror_source["id"], "type": "mixed_payment", "payload": {key: value for key, value in mirror_source.items() if key != "id"}})
        result["evidence"].append({"id": mirror_source["evidence_id"], "type": "credit_liability_mirror", "source_ids": [mirror_source["id"]], "payload": {"observed_at": mirror_source["observed_at"]}})
        result["evidence_links"].append({"id": "link-rg04-credit-mirror", "evidence_id": mirror_source["evidence_id"], "target_kind": "posting", "target_id": "posting-liability-rg04-imported", "role": "real_account_posting"})
        next(item for item in result["posting_reconciliations"] if item["posting_id"] == "posting-liability-rg04-imported")["status"] = "matched"
        operation = _rg04_action(case, baseline, result, "merge_mixed_payment_mirror_evidence", {
            "request_id": "request-rg04-credit-mirror", "source_record_id": mirror_source["id"], "evidence_id": mirror_source["evidence_id"],
            "transaction_id": "tx-purchase-rg04-imported", "candidate_id": "candidate-purchase-rg04", "account_id": "liability-credit-b",
            "amount": "50.00", "currency": "CNY", "observed_at": mirror_source["observed_at"],
        }, "operation-rg04-mirror")
        operation["returned_ids"] = [
            {"kind": "source", "id": mirror_source["id"]},
            {"kind": "evidence", "id": mirror_source["evidence_id"]},
            {"kind": "evidence_link", "id": "link-rg04-credit-mirror"},
        ]
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_action_input(operation, "$.operations[0]", baseline, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai"))
        golden_v2._validate_registered_action_effects(operation, "$.operations[0]", result, expected)
        golden_v2._validate_action_semantics(operation, "$.operations[0]", baseline, result, expected)
        self.assertEqual(result["transactions"], baseline["transactions"])
        self.assertEqual(result["postings"], baseline["postings"])
        self.assertEqual(result["relations"], baseline["relations"])
        self.assertEqual(expected["posting_reconciliations"]["changed_ids"], ["reconciliation-rg04-imported-liability"])
        self.assertEqual(
            next(item for item in result["posting_reconciliations"] if item["id"] == "reconciliation-rg04-imported-liability")["status"],
            "matched",
        )

        for mutation, message in (("status", "matched"), ("target", "posting-asset-rg04-imported")):
            invalid_result = deepcopy(result)
            reconciliation = next(
                item for item in invalid_result["posting_reconciliations"]
                if item["id"] == "reconciliation-rg04-imported-liability"
            )
            if mutation == "status":
                reconciliation["status"] = "pending"
            else:
                reconciliation["posting_id"] = message
            invalid_expected = golden_v2._expected_entity_changes(baseline, invalid_result)
            with self.subTest(mutation=mutation):
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_registered_action_effects(
                        operation, "$.operations[0]", invalid_result, invalid_expected
                    )

    def test_rg04_mirror_requires_confirmed_bound_candidate_and_exact_liability_funding(self):
        _, baseline = _rg04_import_baseline()
        source = _rg04_complete_source()
        baseline = _rg04_ingested_state(
            baseline,
            source,
            "candidate-purchase-rg04",
            "1.00",
            _rg04_complete_candidate_payload(source),
        )
        baseline = _rg04_confirmed_state(baseline)
        valid = {
            "request_id": "request-rg04-mirror-validation",
            "source_record_id": source["id"],
            "evidence_id": source["evidence_id"],
            "transaction_id": "tx-purchase-rg04-imported",
            "candidate_id": "candidate-purchase-rg04",
            "account_id": "liability-credit-b",
            "amount": "50.00",
            "currency": "CNY",
            "observed_at": source["observed_at"],
        }
        mutations = []
        mutation = deepcopy(baseline)
        mutation["candidates"][0]["status_history"][-1]["status"] = "pending_confirmation"
        mutations.append((mutation, valid, "confirmed mixed payment candidate"))
        mutation = deepcopy(baseline)
        mutation["candidates"][0]["payload"]["transaction_id"] = "unknown-transaction"
        mutations.append((mutation, valid, "confirmed candidate transaction"))
        invalid = deepcopy(valid)
        invalid["account_id"] = "asset-bank-a"
        invalid["amount"] = "70.00"
        mutations.append((baseline, invalid, "liability funding account"))
        invalid = deepcopy(valid)
        invalid["amount"] = "49.99"
        mutations.append((baseline, invalid, "liability funding posting"))
        invalid = deepcopy(valid)
        invalid["currency"] = "USD"
        mutations.append((baseline, invalid, "liability funding posting"))
        for state, input_value, message in mutations:
            with self.subTest(input=input_value, message=message):
                operation = {"action_type": "merge_mixed_payment_mirror_evidence", "input": input_value, "outcome": {"status": "accepted"}}
                with self.assertRaisesRegex(GoldenCaseError, message):
                    golden_v2._validate_action_input(operation, "$.operations[0]", state, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai"))

    def test_rg04_no_change_retry_preserves_the_accepted_ingest_result_exactly(self):
        case, baseline = _rg04_import_baseline()
        source = _rg04_complete_source()
        accepted_result = _rg04_ingested_state(
            baseline,
            source,
            "candidate-rg04-retry",
            "1.00",
            _rg04_complete_candidate_payload(source),
        )
        input_value = {"request_id": "request-rg04-retry", "source_record": source}
        accepted = _rg04_action(case, baseline, accepted_result, "ingest_mixed_payment_source", input_value, "operation-rg04-accepted")
        accepted["returned_ids"] = [{"kind": "source", "id": source["id"]}, {"kind": "evidence", "id": source["evidence_id"]}, {"kind": "candidate", "id": "candidate-rg04-retry"}]
        retry_result = deepcopy(accepted_result)
        retry_result["id"] = "state-rg04-retry"
        retry_result["as_of_operation_id"] = "operation-rg04-retry"
        retry = _rg04_action(case, accepted_result, retry_result, "ingest_mixed_payment_source", input_value, "operation-rg04-retry", {"status": "no_change", "reason_code": "idempotent_replay"})
        retry["sequence"] = 2
        retry["returned_ids"] = deepcopy(accepted["returned_ids"])
        expected = golden_v2._expected_entity_changes(accepted_result, retry_result)
        golden_v2._validate_action_input(retry, "$.operations[1]", accepted_result, {"CNY": 2, "USD": 2}, ZoneInfo("Asia/Shanghai"))
        golden_v2._validate_registered_action_effects(retry, "$.operations[1]", retry_result, expected)
        golden_v2._validate_action_semantics(retry, "$.operations[1]", accepted_result, retry_result, expected)
        golden_v2._validate_no_change_retry(retry, "$.operations[1]", [accepted])
        self.assertEqual(retry_result["transactions"], accepted_result["transactions"])
        self.assertEqual(golden_v2._balance_map(retry_result), golden_v2._balance_map(accepted_result))
        self.assertEqual(golden_v2._report_map(retry_result), golden_v2._report_map(accepted_result))

    def test_rg04_invalid_manual_inputs_are_atomic_and_match_first_failure(self):
        case, initial, _ = rg04_creation_case("manual_mixed_expense")
        initial = deepcopy(initial)
        initial["id"] = "state-rg04-invalid-initial"
        initial["root_id"] = "root-rg04-invalid"
        initial["as_of_operation_id"] = None
        initial["catalog"]["accounts"].extend([
            {
                "id": "asset-external-x", "name": "External asset", "kind": "asset",
                "currency": "CNY", "owned_by_user": False, "real_account": True,
                "reconciliation_eligible": False,
            },
            {
                "id": "expense-account-inactive", "name": "Inactive expense", "kind": "expense",
                "currency": "CNY", "owned_by_user": False, "real_account": False,
                "reconciliation_eligible": False,
            },
            {
                "id": "income-account", "name": "Income", "kind": "income",
                "currency": "CNY", "owned_by_user": False, "real_account": False,
                "reconciliation_eligible": False,
            },
        ])
        initial["catalog"]["categories"].extend([
            {
                "id": "expense-category-inactive", "name": "Inactive expense",
                "parent_id": "expense-category-living", "posting_account_id": "expense-account-inactive",
                "active": False,
            },
            {
                "id": "income-category-general", "name": "Income", "parent_id": None,
                "posting_account_id": None, "active": True,
            },
            {
                "id": "income-category-other", "name": "Other income",
                "parent_id": "income-category-general", "posting_account_id": "income-account",
                "active": True,
            },
        ])
        initial["balances"].extend([
            {"account_id": "asset-external-x", "currency": "CNY", "amount": "0.00"},
            {"account_id": "expense-account-inactive", "currency": "CNY", "amount": "0.00"},
            {"account_id": "income-account", "currency": "CNY", "amount": "0.00"},
        ])
        funding_mismatch_operation = None
        for invalid in json.loads((REPOSITORY_ROOT / "golden" / "rules" / "rg-04.json").read_text(encoding="utf-8"))["invalid_manual_inputs"]:
            attempted = {"request_id": f"request-rg04-invalid-{invalid['id']}"}
            attempted.update(invalid["input"])
            operation_id = f"operation-rg04-invalid-{invalid['id']}"
            result = deepcopy(initial)
            result["id"] = f"state-rg04-invalid-{invalid['id']}"
            result["root_id"] = "root-rg04-invalid"
            result["as_of_operation_id"] = operation_id
            operation = {
                "id": operation_id, "root_id": "root-rg04-invalid", "sequence": 1,
                "operation_class": "rejection", "action_type": "manual_mixed_expense",
                "baseline_state_id": initial["id"], "result_state_id": result["id"],
                "attempted_input": attempted,
                "outcome": {"status": "rejected", "reason_code": invalid["expected"]["reason"], "field_path": f"$.attempted_input.{invalid['expected']['field']}"},
                "status_changes": [], "deltas": {"entity_changes": {name: {"added_ids": [], "changed_ids": [], "removed_ids": []} for name in ("catalog_accounts", "catalog_categories", "transactions", "transaction_versions", "posting_sets", "postings", "sources", "candidates", "confirmations", "evidence", "evidence_links", "relations", "domain_entities", "audit_links", "posting_reconciliations")}, "value_changes": {"balances": [], "reports": [], "derived_statuses": []}},
                "returned_ids": [],
            }
            with self.subTest(invalid=invalid["id"]):
                golden_v2._validate_rejected_manual_mixed_expense_attempt(
                    operation, "$.operations[0]", initial
                )
                self.assertEqual(operation["attempted_input"], attempted)
                self.assertEqual(operation["outcome"]["field_path"], f"$.attempted_input.{invalid['expected']['field']}")
                self.assertEqual(operation["outcome"]["reason_code"], invalid["expected"]["reason"])
                before_payload = deepcopy(initial)
                after_payload = deepcopy(result)
                before_payload.pop("id")
                before_payload.pop("as_of_operation_id")
                after_payload.pop("id")
                after_payload.pop("as_of_operation_id")
                self.assertEqual(after_payload, before_payload)
                self.assertEqual(operation["returned_ids"], [])
                for changes in operation["deltas"]["entity_changes"].values():
                    self.assertEqual(changes, {"added_ids": [], "changed_ids": [], "removed_ids": []})
                self.assertEqual(operation["deltas"]["value_changes"], {"balances": [], "reports": [], "derived_statuses": []})
                if invalid["id"] == "funding-total-mismatch":
                    funding_mismatch_operation = deepcopy(operation)

        full_case = load_rg01()
        full_root_id = "root-rg04-full-invalid"
        full_operation_id = "operation-rg04-full-invalid-missing-secondary-category"
        full_initial = deepcopy(full_case["states"][0])
        full_initial["id"] = "state-rg04-full-invalid-initial"
        full_initial["root_id"] = full_root_id
        full_initial["as_of_operation_id"] = None
        full_result = deepcopy(full_initial)
        full_result["id"] = "state-rg04-full-invalid-result"
        full_result["as_of_operation_id"] = full_operation_id
        frozen_invalid = next(
            item
            for item in json.loads(RG04_V1_PATH.read_text(encoding="utf-8"))["invalid_manual_inputs"]
            if item["id"] == "missing-secondary-category"
        )
        full_operation = {
            "id": full_operation_id,
            "root_id": full_root_id,
            "sequence": 1,
            "operation_class": "rejection",
            "action_type": "manual_mixed_expense",
            "baseline_state_id": full_initial["id"],
            "result_state_id": full_result["id"],
            "attempted_input": {
                "request_id": "request-rg04-full-invalid-missing-secondary-category",
                **frozen_invalid["input"],
            },
            "outcome": {
                "status": "rejected",
                "reason_code": "secondary_category_required",
                "field_path": "$.attempted_input.category_id",
            },
            "status_changes": [],
            "deltas": {
                "entity_changes": {
                    name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
                    for name in (
                        "catalog_accounts", "catalog_categories", "transactions",
                        "transaction_versions", "posting_sets", "postings", "sources",
                        "candidates", "confirmations", "evidence", "evidence_links",
                        "relations", "domain_entities", "audit_links",
                        "posting_reconciliations",
                    )
                },
                "value_changes": {"balances": [], "reports": [], "derived_statuses": []},
            },
            "returned_ids": [],
        }
        full_case["roots"] = [{
            "id": full_root_id,
            "purpose": "full rejected manual input",
            "initial_state_id": full_initial["id"],
            "operation_ids": [full_operation_id],
        }]
        full_case["states"] = [full_initial, full_result]
        full_case["operations"] = [full_operation]
        validate_golden_case_v2(full_case)


if __name__ == "__main__":
    unittest.main()
