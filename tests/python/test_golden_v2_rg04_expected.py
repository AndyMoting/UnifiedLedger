from __future__ import annotations

from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import unittest

import golden_cases.v2 as golden_v2
from golden_cases import (
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    load_golden_case_v2,
    validate_golden_case_v2,
)


ROOT = Path(__file__).resolve().parents[2]
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-04-expected.json"

ENTITY_COLLECTIONS = (
    "catalog_accounts", "catalog_categories", "transactions", "transaction_versions",
    "posting_sets", "postings", "sources", "candidates", "confirmations", "evidence",
    "evidence_links", "relations", "domain_entities", "audit_links",
    "posting_reconciliations",
)
REPORT_METRICS = (
    "balance_adjustment_net_worth_change", "budget", "cash_inflow", "cash_outflow",
    "consumption", "income", "internal_transfer_amount", "net_worth_change",
    "ordinary_expense", "ordinary_income",
)


def _migration_id(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id("RG-04", root_id, kind, locator, occurrence)


def _empty_entity_changes() -> dict:
    return {
        name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
        for name in ENTITY_COLLECTIONS
    }


def _catalog(v1: dict) -> dict:
    return {
        "accounts": [
            {
                "id": item["id"],
                "name": item["name"],
                "kind": item["kind"],
                "currency": "CNY",
                "owned_by_user": item["owned_by_user"],
                "real_account": item["real_account"],
                "reconciliation_eligible": bool(
                    item["owned_by_user"]
                    and item["real_account"]
                    and item["kind"] in {"asset", "liability"}
                ),
            }
            for item in v1["catalog"]["accounts"]
        ],
        "categories": [
            {
                "id": item["id"],
                "name": item["name"],
                "parent_id": item["parent_id"],
                "posting_account_id": item["posting_account_id"],
                "active": item["active"],
            }
            for item in v1["catalog"]["categories"]
        ],
    }


def _report(period_type: str, period: str) -> dict:
    return {
        "period_type": period_type,
        "period": period,
        "metrics": [
            {
                "metric": metric,
                "applicability": "applicable",
                "currency": "CNY",
                "amount": "0.00",
            }
            for metric in REPORT_METRICS
        ],
    }


def _opening_state(v1: dict, root_id: str, periods: tuple[tuple[str, str], ...]) -> dict:
    opening = v1["opening"]["transactions"][0]
    version_id = _migration_id(
        root_id, "transaction_version", "$.opening.transactions[*]", opening["id"]
    )
    posting_set_id = _migration_id(
        root_id, "posting_set", "$.opening.transactions[*]", opening["id"]
    )
    state_id = _migration_id(
        root_id, "state", "$.opening.transactions[*]", opening["id"]
    )
    postings = [
        {
            "id": item["id"],
            "posting_set_id": posting_set_id,
            "account_id": item["account_id"],
            "amount": item["amount"],
            "currency": item["currency"],
            "reconciliation_eligible": False,
        }
        for item in opening["postings"]
    ]
    state = {
        "id": state_id,
        "root_id": root_id,
        "as_of_operation_id": None,
        "catalog": _catalog(v1),
        "transactions": [{
            "id": opening["id"],
            "type": "opening_balance",
            "current_version_id": version_id,
        }],
        "transaction_versions": [{
            "id": version_id,
            "transaction_id": opening["id"],
            "version_number": 1,
            "posting_set_id": posting_set_id,
            "occurred_at": opening["occurred_at"],
            "statistics_at": opening["occurred_at"],
            "effective_at": opening["occurred_at"],
        }],
        "posting_sets": [{"id": posting_set_id, "posting_ids": [item["id"] for item in postings]}],
        "postings": postings,
        "sources": [],
        "candidates": [],
        "confirmations": [],
        "evidence": [],
        "evidence_links": [],
        "relations": [],
        "domain_entities": [],
        "audit_links": [],
        "posting_reconciliations": [],
        "balances": [],
        "reports": [_report(period_type, period) for period_type, period in periods],
        "derived_statuses": [],
    }
    _refresh_projections(state)
    return state


def _in_period(version: dict, report: dict) -> bool:
    if report["period_type"] == "day":
        return version["statistics_at"][:10] == report["period"]
    if report["period_type"] == "month":
        return version["statistics_at"][:7] == report["period"]
    return True


def _refresh_projections(state: dict) -> None:
    account_ids = [item["id"] for item in state["catalog"]["accounts"]]
    balances = {account_id: Decimal("0") for account_id in account_ids}
    transactions = {item["id"]: item for item in state["transactions"]}
    versions = {item["id"]: item for item in state["transaction_versions"]}
    posting_sets = {item["id"]: item for item in state["posting_sets"]}
    postings = {item["id"]: item for item in state["postings"]}
    current_parts = []
    for transaction in transactions.values():
        version = versions[transaction["current_version_id"]]
        current_postings = [
            postings[posting_id]
            for posting_id in posting_sets[version["posting_set_id"]]["posting_ids"]
        ]
        current_parts.append((transaction, version, current_postings))
        for posting in current_postings:
            balances[posting["account_id"]] += Decimal(posting["amount"])
    state["balances"] = [
        {"account_id": account_id, "currency": "CNY", "amount": f"{balances[account_id]:.2f}"}
        for account_id in account_ids
    ]
    for report in state["reports"]:
        values = {metric: Decimal("0") for metric in REPORT_METRICS}
        for transaction, version, current_postings in current_parts:
            if transaction["type"] == "opening_balance" or not _in_period(version, report):
                continue
            by_role = {item.get("role"): item for item in current_postings}
            if transaction["type"] == "expense":
                expense = Decimal(by_role["expense"]["amount"])
                asset_funding = -Decimal(by_role["mixed_expense_asset_funding"]["amount"])
                values["consumption"] += expense
                values["ordinary_expense"] += expense
                values["cash_outflow"] += asset_funding
                values["net_worth_change"] -= expense
            elif transaction["type"] == "credit_repayment":
                values["cash_outflow"] += -Decimal(
                    by_role["credit_repayment_asset_outflow"]["amount"]
                )
        for metric in report["metrics"]:
            metric["amount"] = f"{values[metric['metric']]:.2f}"


def _value_changes(before: dict, after: dict) -> dict:
    def items(changes, key_builder):
        return [
            {"key": key_builder(key), "before": old, "after": new}
            for key, (old, new) in sorted(changes.items())
        ]

    return {
        "balances": items(
            golden_v2._changes(golden_v2._balance_map(before), golden_v2._balance_map(after)),
            lambda key: {"account_id": key[0], "currency": key[1]},
        ),
        "reports": items(
            golden_v2._changes(golden_v2._report_map(before), golden_v2._report_map(after)),
            lambda key: {
                "period_type": key[0], "period": key[1], "metric": key[2],
                **({"currency": key[3]} if key[3] is not None else {}),
            },
        ),
        "derived_statuses": items(
            golden_v2._changes(golden_v2._status_map(before), golden_v2._status_map(after)),
            lambda key: {"kind": key[0], "target_id": key[1], "status_name": key[2]},
        ),
    }


def _operation(
    root_id: str,
    sequence: int,
    operation_id: str,
    action_type: str,
    operation_class: str,
    baseline: dict,
    result: dict,
    *,
    input_value: dict | None = None,
    attempted_input: dict | None = None,
    outcome: dict | None = None,
    returned_ids: list[dict] | None = None,
) -> dict:
    values = _value_changes(baseline, result)
    operation = {
        "id": operation_id,
        "root_id": root_id,
        "sequence": sequence,
        "operation_class": operation_class,
        "action_type": action_type,
        "baseline_state_id": baseline["id"],
        "result_state_id": result["id"],
        "outcome": outcome or {"status": "accepted"},
        "status_changes": [
            {
                "target_kind": item["key"]["kind"],
                "target_id": item["key"]["target_id"],
                "status_name": item["key"]["status_name"],
                "before": item["before"],
                "after": item["after"],
            }
            for item in values["derived_statuses"]
        ],
        "deltas": {
            "entity_changes": golden_v2._expected_entity_changes(baseline, result),
            "value_changes": values,
        },
        "returned_ids": returned_ids or [],
    }
    if input_value is not None:
        operation["input"] = input_value
    if attempted_input is not None:
        operation["attempted_input"] = attempted_input
    return operation


def _result_state(baseline: dict, operation_id: str, locator: str, occurrence: str) -> dict:
    result = deepcopy(baseline)
    result["id"] = _migration_id(baseline["root_id"], "state", locator, occurrence)
    result["as_of_operation_id"] = operation_id
    return result


def _no_change_operation(
    root_id: str,
    sequence: int,
    baseline: dict,
    accepted: dict,
    locator: str,
    occurrence: str,
) -> tuple[dict, dict]:
    operation_id = _migration_id(root_id, "operation", locator, occurrence)
    result = _result_state(baseline, operation_id, locator, occurrence)
    operation = _operation(
        root_id, sequence, operation_id, accepted["action_type"], accepted["operation_class"],
        baseline, result, input_value=deepcopy(accepted["input"]),
        outcome={"status": "no_change", "reason_code": "idempotent_replay"},
        returned_ids=deepcopy(accepted["returned_ids"]),
    )
    return result, operation


def _add_transaction(
    state: dict,
    transaction: dict,
    version: dict,
    posting_set_id: str,
    postings: list[dict],
) -> None:
    state["transactions"].append(transaction)
    state["transaction_versions"].append(version)
    state["posting_sets"].append({
        "id": posting_set_id,
        "posting_ids": [item["id"] for item in postings],
    })
    state["postings"].extend(postings)


def _relation(relation_id: str, transaction_id: str, asset_posting_id: str, liability_posting_id: str) -> dict:
    return {
        "id": relation_id,
        "type": "mixed_payment",
        "member_refs": [
            {"kind": "transaction", "id": transaction_id},
            {"kind": "posting", "id": asset_posting_id},
            {"kind": "posting", "id": liability_posting_id},
        ],
        "payload": {
            "system_managed": True,
            "display_name": "混合支付",
            "generic_order_lifecycle": False,
            "payment_composition_total": "120.00",
            "funding_components": [
                {"account_id": "asset-bank-a", "funding_amount": "70.00", "currency": "CNY", "posting_id": asset_posting_id},
                {"account_id": "liability-credit-b", "funding_amount": "50.00", "currency": "CNY", "posting_id": liability_posting_id},
            ],
        },
    }


def _build_rg04_expected() -> dict:
    v1 = json.loads((ROOT / "golden" / "rules" / "rg-04.json").read_text(encoding="utf-8"))
    roots = []
    states = []
    operations = []

    def add_root(purpose, locator, discriminator, periods):
        root_id = deterministic_v2_root_id("RG-04", locator, discriminator)
        initial = _opening_state(v1, root_id, periods)
        root = {"id": root_id, "purpose": purpose, "initial_state_id": initial["id"], "operation_ids": []}
        roots.append(root)
        states.append(initial)
        return root, initial

    manual_root, current = add_root(
        "rg04_manual_lifecycle", "$.manual_lifecycle", "request-rg04-manual-purchase",
        (("day", "2026-02-10"), ("month", "2026-02"), ("day", "2026-03-05"), ("month", "2026-03"), ("cumulative", "lifecycle")),
    )
    purchase_v1 = v1["manual_lifecycle"]["ordered_operations"][0]
    purchase_input = purchase_v1["input"]
    purchase_request = purchase_input["request_id"]
    locator = "$.manual_lifecycle.ordered_operations[*]"
    operation_id = _migration_id(manual_root["id"], "operation", locator, purchase_request)
    purchase = _result_state(current, operation_id, locator, purchase_request)
    confirmation_id = _migration_id(manual_root["id"], "confirmation", locator + ".confirmation", purchase_request)
    purchase_postings = [
        {"id": "posting-expense-rg04-manual", "posting_set_id": "posting-set-purchase-rg04-manual", "account_id": "expense-account-daily", "category_id": "expense-category-daily", "amount": "120.00", "currency": "CNY", "role": "expense", "reconciliation_eligible": False},
        {"id": "posting-asset-rg04-manual", "posting_set_id": "posting-set-purchase-rg04-manual", "account_id": "asset-bank-a", "amount": "-70.00", "currency": "CNY", "role": "mixed_expense_asset_funding", "reconciliation_eligible": True},
        {"id": "posting-liability-rg04-manual", "posting_set_id": "posting-set-purchase-rg04-manual", "account_id": "liability-credit-b", "amount": "-50.00", "currency": "CNY", "role": "mixed_expense_credit_funding", "reconciliation_eligible": True},
    ]
    _add_transaction(
        purchase,
        {"id": "tx-purchase-rg04-manual", "type": "expense", "current_version_id": "version-purchase-rg04-manual-v1"},
        {"id": "version-purchase-rg04-manual-v1", "transaction_id": "tx-purchase-rg04-manual", "version_number": 1, "posting_set_id": "posting-set-purchase-rg04-manual", "occurred_at": purchase_input["occurred_at"], "statistics_at": purchase_input["occurred_at"], "effective_at": purchase_input["occurred_at"], "confirmation_id": confirmation_id},
        "posting-set-purchase-rg04-manual", purchase_postings,
    )
    purchase["confirmations"].append({"id": confirmation_id, "type": "explicit_manual_save", "operation_id": operation_id, "subject": {"kind": "operation", "id": operation_id}, "payload": {}})
    purchase["relations"].append(_relation("association-group-rg04-manual", "tx-purchase-rg04-manual", "posting-asset-rg04-manual", "posting-liability-rg04-manual"))
    for posting_id in ("posting-asset-rg04-manual", "posting-liability-rg04-manual"):
        purchase["posting_reconciliations"].append({"id": _migration_id(manual_root["id"], "posting_reconciliation", locator + ".expected.reconciliation", posting_id), "posting_id": posting_id, "status": "pending"})
    purchase["derived_statuses"].append({"id": _migration_id(manual_root["id"], "derived_status", locator + ".expected.reconciliation", "tx-purchase-rg04-manual"), "target_kind": "transaction", "target_id": "tx-purchase-rg04-manual", "status_name": "reconciliation_summary", "value": "pending"})
    _refresh_projections(purchase)
    accepted_purchase = _operation(
        manual_root["id"], 1, operation_id, "manual_mixed_expense", "creation", current, purchase,
        input_value={key: value for key, value in purchase_input.items() if key not in {"kind", "funding_components"}} | {"asset_account_id": "asset-bank-a", "liability_account_id": "liability-credit-b", "asset_funding_amount": "70.00", "liability_funding_amount": "50.00", "explicit_confirmation": True},
        returned_ids=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "tx-purchase-rg04-manual"}],
    )
    manual_root["operation_ids"].append(operation_id); states.append(purchase); operations.append(accepted_purchase)
    current, retry = _no_change_operation(manual_root["id"], 2, purchase, accepted_purchase, "$.idempotency.retried_inputs[*]", purchase_request)
    manual_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    repayment_v1 = v1["manual_lifecycle"]["ordered_operations"][1]
    repayment_input = repayment_v1["input"]
    repayment_request = repayment_input["request_id"]
    operation_id = _migration_id(manual_root["id"], "operation", locator, repayment_request)
    repayment = _result_state(current, operation_id, locator, repayment_request)
    confirmation_id = _migration_id(manual_root["id"], "confirmation", locator + ".confirmation", repayment_request)
    repayment_postings = [
        {"id": "posting-repayment-asset-rg04", "posting_set_id": "posting-set-repayment-rg04", "account_id": "asset-bank-a", "amount": "-50.00", "currency": "CNY", "role": "credit_repayment_asset_outflow", "reconciliation_eligible": True},
        {"id": "posting-repayment-liability-rg04", "posting_set_id": "posting-set-repayment-rg04", "account_id": "liability-credit-b", "amount": "50.00", "currency": "CNY", "role": "credit_repayment_liability_principal", "reconciliation_eligible": True},
    ]
    _add_transaction(
        repayment,
        {"id": "tx-repayment-rg04", "type": "credit_repayment", "current_version_id": "version-repayment-rg04-v1"},
        {"id": "version-repayment-rg04-v1", "transaction_id": "tx-repayment-rg04", "version_number": 1, "posting_set_id": "posting-set-repayment-rg04", "occurred_at": repayment_input["occurred_at"], "statistics_at": repayment_input["occurred_at"], "effective_at": repayment_input["occurred_at"], "confirmation_id": confirmation_id},
        "posting-set-repayment-rg04", repayment_postings,
    )
    repayment["confirmations"].append({"id": confirmation_id, "type": "explicit_manual_save", "operation_id": operation_id, "subject": {"kind": "operation", "id": operation_id}, "payload": {}})
    for posting_id in ("posting-repayment-asset-rg04", "posting-repayment-liability-rg04"):
        repayment["posting_reconciliations"].append({"id": _migration_id(manual_root["id"], "posting_reconciliation", locator + ".expected.reconciliation", posting_id), "posting_id": posting_id, "status": "pending"})
    repayment["derived_statuses"].append({"id": _migration_id(manual_root["id"], "derived_status", locator + ".expected.reconciliation", "tx-repayment-rg04"), "target_kind": "transaction", "target_id": "tx-repayment-rg04", "status_name": "reconciliation_summary", "value": "pending"})
    _refresh_projections(repayment)
    accepted_repayment = _operation(
        manual_root["id"], 3, operation_id, "credit_principal_repayment", "creation", current, repayment,
        input_value={key: value for key, value in repayment_input.items() if key != "kind"} | {"explicit_confirmation": True},
        returned_ids=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "tx-repayment-rg04"}],
    )
    manual_root["operation_ids"].append(operation_id); states.append(repayment); operations.append(accepted_repayment)
    current, retry = _no_change_operation(manual_root["id"], 4, repayment, accepted_repayment, "$.idempotency.retried_inputs[*]", repayment_request)
    manual_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    import_root, current = add_root(
        "rg04_import_lifecycle", "$.import_lifecycle", "source-record-rg04-complete",
        (("day", "2026-02-11"), ("month", "2026-02"), ("cumulative", "lifecycle")),
    )
    source_v1, confirm_v1, mirror_v1 = v1["import_lifecycle"]["ordered_operations"]
    source_record = source_v1["input"]["source_record"]
    operation_id = _migration_id(import_root["id"], "operation", "$.import_lifecycle.ordered_operations[*]", source_v1["id"])
    ingested = _result_state(current, operation_id, "$.import_lifecycle.ordered_operations[*]", source_v1["id"])
    ingested["sources"].append({"id": source_record["id"], "type": "mixed_payment", "payload": {key: value for key, value in source_record.items() if key != "id"}})
    ingested["evidence"].append({"id": source_record["evidence_id"], "type": "asset_funding_debit", "source_ids": [source_record["id"]], "payload": {"observed_at": source_record["observed_at"]}})
    candidate_status_id = _migration_id(import_root["id"], "candidate_status", "$.import_lifecycle.ordered_operations[*].expected.candidate.status", "candidate-purchase-rg04")
    ingested["candidates"].append({
        "id": "candidate-purchase-rg04", "type": "mixed_payment", "source_ids": [source_record["id"]], "confidence": "1.00",
        "payload": {"total_amount": "120.00", "currency": "CNY", "suggested_category_id": "expense-category-daily", "known_funding_components": source_record["funding_components"], "provenance": {"rule": "complete_mixed_payment_source", "rule_version": 1}, "evidence_refs": [source_record["evidence_id"]], "requires_confirmation": ["category_id", "funding_components", "formal_transaction_creation"]},
        "status_history": [{"id": candidate_status_id, "sequence": 1, "status": "pending_confirmation"}],
    })
    ingested["derived_statuses"].append({"id": _migration_id(import_root["id"], "derived_status", "$.import_lifecycle.ordered_operations[*].expected.candidate.status", "candidate-purchase-rg04"), "target_kind": "candidate", "target_id": "candidate-purchase-rg04", "status_name": "confirmation_status", "value": "pending_confirmation"})
    accepted_ingest = _operation(
        import_root["id"], 1, operation_id, "ingest_mixed_payment_source", "creation", current, ingested,
        input_value={"request_id": source_v1["input"]["request_id"], "source_record": source_record},
        returned_ids=[{"kind": "source", "id": source_record["id"]}, {"kind": "evidence", "id": source_record["evidence_id"]}, {"kind": "candidate", "id": "candidate-purchase-rg04"}],
    )
    import_root["operation_ids"].append(operation_id); states.append(ingested); operations.append(accepted_ingest)
    current, retry = _no_change_operation(import_root["id"], 2, ingested, accepted_ingest, "$.idempotency.retried_inputs[*]", source_record["id"])
    import_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    confirm_request = confirm_v1["input"]["request_id"]
    operation_id = _migration_id(import_root["id"], "operation", "$.import_lifecycle.ordered_operations[*]", confirm_v1["id"])
    confirmed = _result_state(current, operation_id, "$.import_lifecycle.ordered_operations[*]", confirm_v1["id"])
    candidate = next(item for item in confirmed["candidates"] if item["id"] == "candidate-purchase-rg04")
    candidate["status_history"].append({"id": _migration_id(import_root["id"], "candidate_status", "$.import_lifecycle.ordered_operations[*].expected.candidate_status", confirm_request), "sequence": 2, "status": "confirmed"})
    candidate["payload"]["transaction_id"] = "tx-purchase-rg04-imported"
    next(item for item in confirmed["derived_statuses"] if item["target_id"] == candidate["id"])["value"] = "confirmed"
    confirmation_id = _migration_id(import_root["id"], "confirmation", "$.import_lifecycle.ordered_operations[*].expected.candidate_status", confirm_request)
    imported_postings = [
        {"id": "posting-expense-rg04-imported", "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "expense-account-daily", "category_id": "expense-category-daily", "amount": "120.00", "currency": "CNY", "role": "expense", "reconciliation_eligible": False},
        {"id": "posting-asset-rg04-imported", "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "asset-bank-a", "amount": "-70.00", "currency": "CNY", "role": "mixed_expense_asset_funding", "reconciliation_eligible": True},
        {"id": "posting-liability-rg04-imported", "posting_set_id": "posting-set-purchase-rg04-imported", "account_id": "liability-credit-b", "amount": "-50.00", "currency": "CNY", "role": "mixed_expense_credit_funding", "reconciliation_eligible": True},
    ]
    _add_transaction(
        confirmed,
        {"id": "tx-purchase-rg04-imported", "type": "expense", "current_version_id": "version-purchase-rg04-imported-v1"},
        {"id": "version-purchase-rg04-imported-v1", "transaction_id": "tx-purchase-rg04-imported", "version_number": 1, "posting_set_id": "posting-set-purchase-rg04-imported", "occurred_at": source_record["observed_at"], "statistics_at": source_record["observed_at"], "effective_at": source_record["observed_at"], "confirmation_id": confirmation_id},
        "posting-set-purchase-rg04-imported", imported_postings,
    )
    confirmed["confirmations"].append({"id": confirmation_id, "type": "candidate_confirmation", "operation_id": operation_id, "subject": {"kind": "candidate", "id": candidate["id"]}, "payload": {}})
    confirmed["relations"].append(_relation("association-group-rg04-imported", "tx-purchase-rg04-imported", "posting-asset-rg04-imported", "posting-liability-rg04-imported"))
    confirmed["evidence_links"].append({"id": "match-rg04-asset-imported", "evidence_id": "evidence-rg04-asset-debit", "target_kind": "posting", "target_id": "posting-asset-rg04-imported", "role": "real_account_posting"})
    for posting_id, status in (("posting-asset-rg04-imported", "matched"), ("posting-liability-rg04-imported", "pending")):
        confirmed["posting_reconciliations"].append({"id": _migration_id(import_root["id"], "posting_reconciliation", "$.import_lifecycle.ordered_operations[*].expected.reconciliation", posting_id), "posting_id": posting_id, "status": status})
    confirmed["derived_statuses"].append({"id": _migration_id(import_root["id"], "derived_status", "$.import_lifecycle.ordered_operations[*].expected.reconciliation", "tx-purchase-rg04-imported"), "target_kind": "transaction", "target_id": "tx-purchase-rg04-imported", "status_name": "reconciliation_summary", "value": "partial"})
    _refresh_projections(confirmed)
    confirm_input = {"request_id": confirm_request, "candidate_id": "candidate-purchase-rg04", "category_id": "expense-category-daily", "confirmed_funding_components": confirm_v1["input"]["confirmed_funding_components"], "explicit_confirmation": True}
    accepted_confirm = _operation(
        import_root["id"], 3, operation_id, "confirm_mixed_payment_candidate", "creation", current, confirmed,
        input_value=confirm_input,
        returned_ids=[{"kind": "confirmation", "id": confirmation_id}, {"kind": "transaction", "id": "tx-purchase-rg04-imported"}],
    )
    import_root["operation_ids"].append(operation_id); states.append(confirmed); operations.append(accepted_confirm)
    current, retry = _no_change_operation(import_root["id"], 4, confirmed, accepted_confirm, "$.idempotency.retried_inputs[*]", confirm_request)
    import_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    mirror_request = mirror_v1["input"]["request_id"]
    operation_id = _migration_id(import_root["id"], "operation", "$.import_lifecycle.ordered_operations[*]", mirror_v1["id"])
    mirrored = _result_state(current, operation_id, "$.import_lifecycle.ordered_operations[*]", mirror_v1["id"])
    mirrored["sources"].append({"id": mirror_v1["input"]["source_record_id"], "type": "mixed_payment", "payload": {"evidence_id": mirror_v1["input"]["evidence_id"], "observed_at": mirror_v1["input"]["observed_at"], "account_id": mirror_v1["input"]["account_id"], "amount": "50.00", "currency": mirror_v1["input"]["currency"]}})
    mirrored["evidence"].append({"id": mirror_v1["input"]["evidence_id"], "type": "credit_liability_mirror", "source_ids": [mirror_v1["input"]["source_record_id"]], "payload": {"observed_at": mirror_v1["input"]["observed_at"]}})
    mirrored["evidence_links"].append({"id": "match-rg04-liability-mirror", "evidence_id": mirror_v1["input"]["evidence_id"], "target_kind": "posting", "target_id": "posting-liability-rg04-imported", "role": "real_account_posting"})
    next(item for item in mirrored["posting_reconciliations"] if item["posting_id"] == "posting-liability-rg04-imported")["status"] = "matched"
    next(item for item in mirrored["derived_statuses"] if item["target_id"] == "tx-purchase-rg04-imported")["value"] = "matched"
    mirror_input = {"request_id": mirror_request, "source_record_id": mirror_v1["input"]["source_record_id"], "evidence_id": mirror_v1["input"]["evidence_id"], "transaction_id": "tx-purchase-rg04-imported", "candidate_id": "candidate-purchase-rg04", "account_id": mirror_v1["input"]["account_id"], "amount": "50.00", "currency": mirror_v1["input"]["currency"], "observed_at": mirror_v1["input"]["observed_at"]}
    accepted_mirror = _operation(
        import_root["id"], 5, operation_id, "merge_mixed_payment_mirror_evidence", "reconciliation", current, mirrored,
        input_value=mirror_input,
        returned_ids=[{"kind": "source", "id": mirror_v1["input"]["source_record_id"]}, {"kind": "evidence", "id": mirror_v1["input"]["evidence_id"]}, {"kind": "evidence_link", "id": "match-rg04-liability-mirror"}],
    )
    import_root["operation_ids"].append(operation_id); states.append(mirrored); operations.append(accepted_mirror)
    current, retry = _no_change_operation(import_root["id"], 6, mirrored, accepted_mirror, "$.idempotency.retried_inputs[*]", mirror_v1["input"]["evidence_id"])
    import_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    missing_root, current = add_root(
        "rg04_missing_funding_leg", "$.missing_funding_leg", "source-record-rg04-missing-leg",
        (("cumulative", "lifecycle"),),
    )
    missing_v1 = v1["missing_funding_leg"]
    source_record = missing_v1["input"]["source_record"]
    operation_id = _migration_id(missing_root["id"], "operation", "$.missing_funding_leg", source_record["id"])
    missing = _result_state(current, operation_id, "$.missing_funding_leg", source_record["id"])
    missing["sources"].append({"id": source_record["id"], "type": "mixed_payment", "payload": {key: value for key, value in source_record.items() if key != "id"}})
    missing["evidence"].append({"id": source_record["evidence_id"], "type": "asset_funding_debit", "source_ids": [source_record["id"]], "payload": {"observed_at": source_record["observed_at"]}})
    status_id = _migration_id(missing_root["id"], "candidate_status", "$.missing_funding_leg.expected.candidate.status", "candidate-purchase-rg04-missing-leg")
    missing["candidates"].append({"id": "candidate-purchase-rg04-missing-leg", "type": "mixed_payment", "source_ids": [source_record["id"]], "confidence": "0.58", "payload": {"total_amount": "120.00", "currency": "CNY", "known_funding_amount": "70.00", "missing_funding_amount": "50.00", "provenance": {"rule": "incomplete_mixed_payment_source", "rule_version": 1}, "evidence_refs": [source_record["evidence_id"]], "requires_confirmation": ["funding_account_id", "missing_funding_amount", "category_id", "formal_transaction_creation"]}, "status_history": [{"id": status_id, "sequence": 1, "status": "pending_confirmation"}]})
    missing["derived_statuses"].append({"id": _migration_id(missing_root["id"], "derived_status", "$.missing_funding_leg.expected.candidate.status", "candidate-purchase-rg04-missing-leg"), "target_kind": "candidate", "target_id": "candidate-purchase-rg04-missing-leg", "status_name": "confirmation_status", "value": "pending_confirmation"})
    accepted_missing = _operation(
        missing_root["id"], 1, operation_id, "ingest_mixed_payment_source", "creation", current, missing,
        input_value={"request_id": missing_v1["input"]["request_id"], "source_record": source_record},
        returned_ids=[{"kind": "source", "id": source_record["id"]}, {"kind": "evidence", "id": source_record["evidence_id"]}, {"kind": "candidate", "id": "candidate-purchase-rg04-missing-leg"}],
    )
    missing_root["operation_ids"].append(operation_id); states.append(missing); operations.append(accepted_missing)
    current, retry = _no_change_operation(missing_root["id"], 2, missing, accepted_missing, "$.missing_funding_leg.retry", source_record["id"])
    missing_root["operation_ids"].append(retry["id"]); states.append(current); operations.append(retry)

    for invalid in v1["invalid_manual_inputs"]:
        invalid_id = invalid["id"]
        purpose = "rg04_invalid_" + invalid_id.replace("-", "_")
        root, initial = add_root(purpose, "$.invalid_manual_inputs[*]", invalid_id, (("cumulative", "lifecycle"),))
        operation_id = _migration_id(root["id"], "operation", "$.invalid_manual_inputs[*]", invalid_id)
        result = _result_state(initial, operation_id, "$.invalid_manual_inputs[*]", invalid_id)
        request_id = _migration_id(root["id"], "request", "$.invalid_manual_inputs[*].id", invalid_id)
        operation = _operation(
            root["id"], 1, operation_id, "manual_mixed_expense", "rejection", initial, result,
            attempted_input={"request_id": request_id, **invalid["input"]},
            outcome={"status": "rejected", "reason_code": invalid["expected"]["reason"], "field_path": "$.attempted_input." + invalid["expected"]["field"]},
        )
        root["operation_ids"].append(operation_id); states.append(result); operations.append(operation)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: (item["root_id"], item["as_of_operation_id"] is not None, item["id"]))
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "case": {"id": "RG-04", "level": "core_required", "rule_version": 1, "approval_status": "approved", "ledger_id": "ledger-a", "timezone": "Asia/Shanghai", "currencies": [{"code": "CNY", "precision": 2}]},
        "roots": roots,
        "states": states,
        "operations": operations,
    }


def write_rg04_expected() -> None:
    EXPECTED_PATH.write_text(
        json.dumps(_build_rg04_expected(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


class RG04GoldenV2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not EXPECTED_PATH.exists():
            raise AssertionError("RG-04 expected output is not generated")
        cls.case = load_golden_case_v2(EXPECTED_PATH)
        cls.v1 = json.loads(
            (ROOT / "golden" / "rules" / "rg-04.json").read_text(encoding="utf-8")
        )
        cls.states = {item["id"]: item for item in cls.case["states"]}
        cls.operations = {item["id"]: item for item in cls.case["operations"]}
        cls.roots_by_purpose = {item["purpose"]: item for item in cls.case["roots"]}

    def root_operations(self, purpose: str) -> list[dict]:
        return [
            self.operations[operation_id]
            for operation_id in self.roots_by_purpose[purpose]["operation_ids"]
        ]

    def state(self, operation: dict) -> dict:
        return self.states[operation["result_state_id"]]

    def assert_no_change(self, accepted: dict, retry: dict) -> None:
        self.assertEqual(retry["outcome"], {"status": "no_change", "reason_code": "idempotent_replay"})
        self.assertEqual(retry["input"], accepted["input"])
        self.assertEqual(retry["returned_ids"], accepted["returned_ids"])
        self.assertEqual(retry["status_changes"], [])
        self.assertTrue(
            all(
                not ids
                for changes in retry["deltas"]["entity_changes"].values()
                for ids in changes.values()
            )
        )
        self.assertTrue(
            all(not values for values in retry["deltas"]["value_changes"].values())
        )
        baseline = self.states[retry["baseline_state_id"]]
        result = self.states[retry["result_state_id"]]
        self.assertEqual(
            {key: value for key, value in baseline.items() if key not in {"id", "as_of_operation_id"}},
            {key: value for key, value in result.items() if key not in {"id", "as_of_operation_id"}},
        )

    def test_expected_output_gate_is_completed_and_approved(self):
        validate_golden_case_v2(self.case)
        self.assertEqual(self.case["case"]["id"], "RG-04")
        self.assertEqual(self.case["case"]["approval_status"], "approved")

    def test_complete_v1_behavior_families_are_present(self):
        purposes = {item["purpose"] for item in self.case["roots"]}
        self.assertIn("rg04_manual_lifecycle", purposes)
        self.assertIn("rg04_import_lifecycle", purposes)
        self.assertIn("rg04_missing_funding_leg", purposes)
        invalid_ids = {
            item["id"]
            for item in json.loads(
                (ROOT / "golden" / "rules" / "rg-04.json").read_text(encoding="utf-8")
            )["invalid_manual_inputs"]
        }
        self.assertEqual(
            {purpose.removeprefix("rg04_invalid_").replace("_", "-") for purpose in purposes if purpose.startswith("rg04_invalid_")},
            invalid_ids,
        )

    def test_exact_topology_and_deterministic_root_ids(self):
        self.assertEqual(
            (len(self.case["roots"]), len(self.case["operations"]), len(self.case["states"])),
            (17, 26, 43),
        )
        root_specs = {
            "rg04_manual_lifecycle": ("$.manual_lifecycle", "request-rg04-manual-purchase"),
            "rg04_import_lifecycle": ("$.import_lifecycle", "source-record-rg04-complete"),
            "rg04_missing_funding_leg": ("$.missing_funding_leg", "source-record-rg04-missing-leg"),
        }
        for invalid in self.v1["invalid_manual_inputs"]:
            root_specs["rg04_invalid_" + invalid["id"].replace("-", "_")] = (
                "$.invalid_manual_inputs[*]",
                invalid["id"],
            )
        self.assertEqual(set(self.roots_by_purpose), set(root_specs))
        for purpose, (locator, discriminator) in root_specs.items():
            self.assertEqual(
                self.roots_by_purpose[purpose]["id"],
                deterministic_v2_root_id("RG-04", locator, discriminator),
            )

    def test_manual_purchase_repayment_and_retries_preserve_v1_business_ids(self):
        purchase, purchase_retry, repayment, repayment_retry = self.root_operations(
            "rg04_manual_lifecycle"
        )
        self.assertEqual(
            [item["outcome"]["status"] for item in (purchase, purchase_retry, repayment, repayment_retry)],
            ["accepted", "no_change", "accepted", "no_change"],
        )
        purchase_state = self.state(purchase)
        purchase_transaction = next(
            item for item in purchase_state["transactions"]
            if item["id"] == "tx-purchase-rg04-manual"
        )
        self.assertEqual(purchase_transaction["current_version_id"], "version-purchase-rg04-manual-v1")
        purchase_postings = {
            item["role"]: item
            for item in purchase_state["postings"]
            if item.get("posting_set_id") == "posting-set-purchase-rg04-manual"
        }
        self.assertEqual(
            {role: (item["account_id"], item["amount"]) for role, item in purchase_postings.items()},
            {
                "expense": ("expense-account-daily", "120.00"),
                "mixed_expense_asset_funding": ("asset-bank-a", "-70.00"),
                "mixed_expense_credit_funding": ("liability-credit-b", "-50.00"),
            },
        )
        self.assertEqual(purchase_postings["expense"]["category_id"], "expense-category-daily")
        self.assert_no_change(purchase, purchase_retry)

        repayment_state = self.state(repayment)
        repayment_transaction = next(
            item for item in repayment_state["transactions"]
            if item["id"] == "tx-repayment-rg04"
        )
        self.assertEqual(repayment_transaction["current_version_id"], "version-repayment-rg04-v1")
        repayment_postings = {
            item["role"]: (item["account_id"], item["amount"])
            for item in repayment_state["postings"]
            if item.get("posting_set_id") == "posting-set-repayment-rg04"
        }
        self.assertEqual(
            repayment_postings,
            {
                "credit_repayment_asset_outflow": ("asset-bank-a", "-50.00"),
                "credit_repayment_liability_principal": ("liability-credit-b", "50.00"),
            },
        )
        balances = {item["account_id"]: item["amount"] for item in repayment_state["balances"]}
        self.assertEqual(
            {key: balances[key] for key in ("asset-bank-a", "liability-credit-b")},
            {"asset-bank-a": "880.00", "liability-credit-b": "0.00"},
        )
        cumulative = next(
            item for item in repayment_state["reports"]
            if item["period_type"] == "cumulative"
        )
        metrics = {item["metric"]: item["amount"] for item in cumulative["metrics"]}
        self.assertEqual(metrics["consumption"], "120.00")
        self.assertEqual(metrics["cash_outflow"], "120.00")
        self.assertEqual(metrics["net_worth_change"], "-120.00")
        self.assert_no_change(repayment, repayment_retry)

    def test_import_intake_confirmation_mirror_and_retries_are_complete(self):
        intake, intake_retry, confirmation, confirmation_retry, mirror, mirror_retry = self.root_operations(
            "rg04_import_lifecycle"
        )
        pending = self.state(intake)
        baseline = self.states[intake["baseline_state_id"]]
        self.assertEqual(pending["transactions"], baseline["transactions"])
        self.assertEqual(pending["balances"], baseline["balances"])
        candidate = next(item for item in pending["candidates"] if item["id"] == "candidate-purchase-rg04")
        self.assertEqual(candidate["status_history"][-1]["status"], "pending_confirmation")
        self.assert_no_change(intake, intake_retry)

        confirmed = self.state(confirmation)
        candidate = next(item for item in confirmed["candidates"] if item["id"] == "candidate-purchase-rg04")
        self.assertEqual([item["status"] for item in candidate["status_history"]], ["pending_confirmation", "confirmed"])
        self.assertEqual(candidate["payload"]["transaction_id"], "tx-purchase-rg04-imported")
        self.assertEqual(
            next(item for item in confirmed["evidence_links"] if item["id"] == "match-rg04-asset-imported"),
            {
                "id": "match-rg04-asset-imported",
                "evidence_id": "evidence-rg04-asset-debit",
                "target_kind": "posting",
                "target_id": "posting-asset-rg04-imported",
                "role": "real_account_posting",
            },
        )
        self.assertEqual(
            {
                item["posting_id"]: item["status"]
                for item in confirmed["posting_reconciliations"]
                if "rg04-imported" in item["posting_id"]
            },
            {
                "posting-asset-rg04-imported": "matched",
                "posting-liability-rg04-imported": "pending",
            },
        )
        self.assertEqual(
            next(item["value"] for item in confirmed["derived_statuses"] if item["target_id"] == "tx-purchase-rg04-imported"),
            "partial",
        )
        self.assert_no_change(confirmation, confirmation_retry)

        mirrored = self.state(mirror)
        mirror_baseline = self.states[mirror["baseline_state_id"]]
        for collection in (
            "transactions", "transaction_versions", "posting_sets", "postings",
            "candidates", "confirmations", "relations", "balances", "reports",
        ):
            self.assertEqual(mirrored[collection], mirror_baseline[collection])
        self.assertEqual(
            next(item for item in mirrored["evidence_links"] if item["id"] == "match-rg04-liability-mirror"),
            {
                "id": "match-rg04-liability-mirror",
                "evidence_id": "evidence-rg04-liability-mirror",
                "target_kind": "posting",
                "target_id": "posting-liability-rg04-imported",
                "role": "real_account_posting",
            },
        )
        self.assertEqual(
            next(item["value"] for item in mirrored["derived_statuses"] if item["target_id"] == "tx-purchase-rg04-imported"),
            "matched",
        )
        self.assert_no_change(mirror, mirror_retry)

    def test_missing_funding_leg_stays_pending_without_guessed_account(self):
        intake, retry = self.root_operations("rg04_missing_funding_leg")
        baseline = self.states[intake["baseline_state_id"]]
        result = self.state(intake)
        candidate = next(
            item for item in result["candidates"]
            if item["id"] == "candidate-purchase-rg04-missing-leg"
        )
        self.assertEqual(candidate["status_history"][-1]["status"], "pending_confirmation")
        self.assertEqual(candidate["payload"]["known_funding_amount"], "70.00")
        self.assertEqual(candidate["payload"]["missing_funding_amount"], "50.00")
        self.assertNotIn("funding_account_id", candidate["payload"])
        for collection in (
            "transactions", "transaction_versions", "posting_sets", "postings",
            "confirmations", "evidence_links", "relations", "posting_reconciliations",
            "balances", "reports",
        ):
            self.assertEqual(result[collection], baseline[collection])
        self.assert_no_change(intake, retry)

    def test_all_frozen_invalid_inputs_are_independent_atomic_rejections(self):
        for invalid in self.v1["invalid_manual_inputs"]:
            purpose = "rg04_invalid_" + invalid["id"].replace("-", "_")
            operation = self.root_operations(purpose)[0]
            baseline = self.states[operation["baseline_state_id"]]
            result = self.state(operation)
            self.assertEqual(operation["operation_class"], "rejection")
            self.assertEqual(operation["action_type"], "manual_mixed_expense")
            self.assertEqual(
                {key: value for key, value in operation["attempted_input"].items() if key != "request_id"},
                invalid["input"],
            )
            self.assertEqual(operation["outcome"]["status"], "rejected")
            self.assertEqual(operation["outcome"]["reason_code"], invalid["expected"]["reason"])
            self.assertEqual(
                operation["outcome"]["field_path"],
                "$.attempted_input." + invalid["expected"]["field"],
            )
            self.assertEqual(operation["returned_ids"], [])
            self.assertEqual(operation["status_changes"], [])
            self.assertTrue(
                all(
                    not ids
                    for changes in operation["deltas"]["entity_changes"].values()
                    for ids in changes.values()
                )
            )
            self.assertTrue(
                all(not values for values in operation["deltas"]["value_changes"].values())
            )
            self.assertEqual(
                {key: value for key, value in baseline.items() if key not in {"id", "as_of_operation_id"}},
                {key: value for key, value in result.items() if key not in {"id", "as_of_operation_id"}},
            )

    def test_manual_and_imported_expenses_each_have_one_frozen_relation(self):
        expense_transactions = {
            item["id"]
            for state in self.case["states"]
            for item in state["transactions"]
            if item["id"] in {"tx-purchase-rg04-manual", "tx-purchase-rg04-imported"}
        }
        self.assertEqual(
            expense_transactions,
            {"tx-purchase-rg04-manual", "tx-purchase-rg04-imported"},
        )
        for transaction_id in expense_transactions:
            relations = {
                relation["id"]: relation
                for state in self.case["states"]
                for relation in state["relations"]
                if {"kind": "transaction", "id": transaction_id} in relation["member_refs"]
            }
            self.assertEqual(len(relations), 1)
            relation = next(iter(relations.values()))
            self.assertEqual(relation["type"], "mixed_payment")
            self.assertEqual(relation["payload"]["display_name"], "混合支付")
            self.assertEqual(
                [item["kind"] for item in relation["member_refs"]].count("posting"),
                2,
            )


if __name__ == "__main__":
    unittest.main()
