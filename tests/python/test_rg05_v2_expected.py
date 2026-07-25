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
    load_golden_case_v2,
    validate_golden_case_v2,
)


ROOT = Path(__file__).resolve().parents[2]
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-05-expected.json"
V1_PATH = ROOT / "golden" / "rules" / "rg-05.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
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


def state_payload(state: dict) -> dict:
    return {
        key: value
        for key, value in state.items()
        if key not in {"id", "as_of_operation_id"}
    }


def empty_deltas(operation: dict) -> bool:
    return (
        operation["status_changes"] == []
        and all(
            not ids
            for changes in operation["deltas"]["entity_changes"].values()
            for ids in changes.values()
        )
        and all(
            not values
            for values in operation["deltas"]["value_changes"].values()
        )
    )


def migration_id(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id("RG-05", root_id, kind, locator, occurrence)


def catalog(v1: dict) -> dict:
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


def report(period_type: str, period: str) -> dict:
    return {
        "period_type": period_type,
        "period": period,
        "metrics": [
            (
                {"metric": metric, "applicability": "not_applicable"}
                if metric == "budget"
                else {
                    "metric": metric,
                    "applicability": "applicable",
                    "currency": "CNY",
                    "amount": "0.00",
                }
            )
            for metric in REPORT_METRICS
        ],
    }


def current_parts(state: dict) -> list[tuple[dict, dict, list[dict]]]:
    versions = {item["id"]: item for item in state["transaction_versions"]}
    posting_sets = {item["id"]: item for item in state["posting_sets"]}
    postings = {item["id"]: item for item in state["postings"]}
    result = []
    for transaction in state["transactions"]:
        version = versions[transaction["current_version_id"]]
        selected = [
            postings[posting_id]
            for posting_id in posting_sets[version["posting_set_id"]]["posting_ids"]
        ]
        result.append((transaction, version, selected))
    return result


def refresh_projections(state: dict) -> None:
    account_ids = [item["id"] for item in state["catalog"]["accounts"]]
    balances = {account_id: Decimal("0") for account_id in account_ids}
    parts = current_parts(state)
    for _, _, postings in parts:
        for posting in postings:
            balances[posting["account_id"]] += Decimal(posting["amount"])
    state["balances"] = [
        {"account_id": account_id, "currency": "CNY", "amount": f"{balances[account_id]:.2f}"}
        for account_id in account_ids
    ]
    current = {transaction["id"]: part for part in parts for transaction in [part[0]]}
    accounts = {item["id"]: item for item in state["catalog"]["accounts"]}
    for item in state["reports"]:
        values = golden_v2._report_values(current, accounts, item, "CNY")
        for metric in item["metrics"]:
            if metric["applicability"] == "applicable":
                metric["amount"] = f"{values[metric['metric']]:.2f}"


def opening_state(v1: dict, root_id: str, periods: tuple[tuple[str, str], ...]) -> dict:
    opening = v1["opening"]["transactions"][0]
    version_id = migration_id(root_id, "transaction_version", "$.opening.transactions[*]", opening["id"])
    posting_set_id = migration_id(root_id, "posting_set", "$.opening.transactions[*]", opening["id"])
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
        "id": migration_id(root_id, "state", "$.opening.transactions[*]", opening["id"]),
        "root_id": root_id,
        "as_of_operation_id": None,
        "catalog": catalog(v1),
        "transactions": [{"id": opening["id"], "type": "opening_balance", "current_version_id": version_id}],
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
        "sources": [], "candidates": [], "confirmations": [], "evidence": [],
        "evidence_links": [], "relations": [], "domain_entities": [], "audit_links": [],
        "posting_reconciliations": [],
        "balances": [],
        "reports": [report(period_type, period) for period_type, period in periods],
        "derived_statuses": [],
    }
    refresh_projections(state)
    return state


def value_changes(before: dict, after: dict) -> dict:
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


def operation(
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
    values = value_changes(baseline, result)
    result_value = {
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
        result_value["input"] = input_value
    if attempted_input is not None:
        result_value["attempted_input"] = attempted_input
    return result_value


def result_state(baseline: dict, operation_id: str, locator: str, occurrence: str) -> dict:
    result = deepcopy(baseline)
    result["id"] = migration_id(baseline["root_id"], "state", locator, occurrence)
    result["as_of_operation_id"] = operation_id
    return result


def replay_operation(
    root_id: str,
    sequence: int,
    baseline: dict,
    accepted: dict,
    occurrence: str,
) -> tuple[dict, dict]:
    locator = "$.idempotency.retried_inputs[*]"
    operation_id = migration_id(root_id, "operation", locator, occurrence)
    result = result_state(baseline, operation_id, locator, occurrence)
    return result, operation(
        root_id,
        sequence,
        operation_id,
        accepted["action_type"],
        accepted["operation_class"],
        baseline,
        result,
        input_value=deepcopy(accepted["input"]),
        outcome={"status": "no_change", "reason_code": "idempotent_replay"},
        returned_ids=deepcopy(accepted["returned_ids"]),
    )


def add_transaction(
    state: dict,
    transaction_id: str,
    version_id: str,
    posting_set_id: str,
    occurred_at: str,
    confirmation_id: str,
    postings: list[dict],
) -> None:
    state["transactions"].append({
        "id": transaction_id,
        "type": "expense",
        "current_version_id": version_id,
    })
    state["transaction_versions"].append({
        "id": version_id,
        "transaction_id": transaction_id,
        "version_number": 1,
        "posting_set_id": posting_set_id,
        "occurred_at": occurred_at,
        "statistics_at": occurred_at,
        "effective_at": occurred_at,
        "confirmation_id": confirmation_id,
    })
    state["posting_sets"].append({
        "id": posting_set_id,
        "posting_ids": [item["id"] for item in postings],
    })
    state["postings"].extend(postings)


def domain_entities(expected: dict, *, imported: bool) -> list[dict]:
    consumptions = []
    allocations = []
    allocation_by_consumption = {
        item["consumption_record_id"]: item
        for item in expected["association_group"]["item_allocations"]
    }
    for item in expected["consumption_records"]:
        payload = {
            "expense_posting_id": item["expense_posting_id"],
            "category_id": item["category_id"],
            "amount": item["amount"],
            "currency": item["currency"],
            "statistics_at": item["statistics_at"],
            "details": item["details"],
            "source_observed_at": item["source_observed_at"],
        }
        if imported:
            payload.update({
                "source_item_id": item["source_item_id"],
                "source_id": item["source_id"],
                "evidence_id": item["evidence_id"],
            })
        consumptions.append({"id": item["id"], "type": "consumption_record", "payload": payload})
        allocation = allocation_by_consumption[item["id"]]
        allocation_payload = {
            "consumption_record_id": allocation["consumption_record_id"],
            "expense_posting_id": allocation["expense_posting_id"],
            "category_id": allocation["category_id"],
            "amount": allocation["amount"],
            "currency": allocation["currency"],
        }
        if imported:
            allocation_payload.update({
                "source_item_id": allocation["source_item_id"],
                "source_id": allocation["source_id"],
                "evidence_id": allocation["evidence_id"],
            })
        allocations.append({"id": allocation["id"], "type": "item_allocation", "payload": allocation_payload})
    return [*consumptions, *allocations]


def relation(expected: dict) -> dict:
    group = expected["association_group"]
    return {
        "id": group["id"],
        "type": "merged_payment",
        "member_refs": [
            {"kind": "transaction", "id": group["formal_transaction_id"]},
            {"kind": "posting", "id": group["asset_posting_id"]},
            *[
                {"kind": "domain_entity", "id": item["id"]}
                for item in group["item_allocations"]
            ],
        ],
        "payload": {
            "system_managed": True,
            "display_name": "合并付款",
            "generic_order_lifecycle": False,
            "payment_total": group["payment_total"],
            "currency": group["currency"],
        },
    }


def build_rg05_expected() -> dict:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    roots: list[dict] = []
    states: list[dict] = []
    operations: list[dict] = []

    def add_root(purpose: str, locator: str, discriminator: str, periods):
        root_id = deterministic_v2_root_id("RG-05", locator, discriminator)
        initial = opening_state(v1, root_id, periods)
        root = {
            "id": root_id,
            "purpose": purpose,
            "initial_state_id": initial["id"],
            "operation_ids": [],
        }
        roots.append(root)
        states.append(initial)
        return root, initial

    periods = (("day", "2026-04-10"), ("month", "2026-04"), ("cumulative", "lifecycle"))
    manual_v1 = v1["manual_path"]
    manual_input_v1 = manual_v1["input"]
    manual_expected = manual_v1["expected"]
    manual_request = manual_input_v1["request_id"]
    manual_root, current = add_root(
        "rg05_manual_merged_payment", "$.manual_path", manual_request, periods
    )
    locator = "$.manual_path"
    operation_id = migration_id(manual_root["id"], "operation", locator, manual_request)
    created = result_state(current, operation_id, locator, manual_request)
    confirmation_id = migration_id(
        manual_root["id"], "confirmation", "$.manual_path.confirmation", manual_request
    )
    transaction = manual_expected["transaction"]
    postings = [
        {
            "id": item["id"],
            "posting_set_id": transaction["posting_set_id"],
            "account_id": item["account_id"],
            **({"category_id": record["category_id"]} if item["amount"] != "-100.00" else {}),
            "amount": item["amount"],
            "currency": item["currency"],
            "role": "payment_asset" if item["amount"] == "-100.00" else "expense",
            "reconciliation_eligible": item["amount"] == "-100.00",
        }
        for item, record in zip(
            transaction["postings"],
            [*manual_expected["consumption_records"], {}],
            strict=True,
        )
    ]
    add_transaction(
        created,
        transaction["id"],
        transaction["current_version_id"],
        transaction["posting_set_id"],
        transaction["occurred_at"],
        confirmation_id,
        postings,
    )
    created["confirmations"].append({
        "id": confirmation_id,
        "type": "explicit_manual_save",
        "operation_id": operation_id,
        "subject": {"kind": "operation", "id": operation_id},
        "payload": {},
    })
    created["domain_entities"].extend(domain_entities(manual_expected, imported=False))
    created["relations"].append(relation(manual_expected))
    reconciliation_id = migration_id(
        manual_root["id"], "posting_reconciliation",
        "$.manual_path.expected.reconciliation", "posting-asset-rg05-manual",
    )
    created["posting_reconciliations"].append({
        "id": reconciliation_id,
        "posting_id": "posting-asset-rg05-manual",
        "status": "pending",
    })
    created["derived_statuses"].extend([
        {
            "id": migration_id(manual_root["id"], "derived_status", "$.manual_path.expected.reconciliation", transaction["id"]),
            "target_kind": "transaction", "target_id": transaction["id"],
            "status_name": "reconciliation_summary", "value": "pending",
        },
        {
            "id": migration_id(manual_root["id"], "derived_status", "$.manual_path.expected.item_evidence_completeness", manual_expected["association_group"]["id"]),
            "target_kind": "relation", "target_id": manual_expected["association_group"]["id"],
            "status_name": "item_evidence_completeness", "value": "none",
        },
    ])
    refresh_projections(created)
    manual_input = {
        "request_id": manual_request,
        "payment_at": manual_input_v1["payment_at"],
        "total_amount": manual_input_v1["total_amount"],
        "currency": manual_input_v1["currency"],
        "funding_account_id": manual_input_v1["funding_account_id"],
        "items": [
            {
                "item_id": item["id"], "amount": item["amount"],
                "currency": item["currency"], "category_id": item["category_id"],
                "details": item["details"], "source_observed_at": item["source_observed_at"],
            }
            for item in manual_input_v1["items"]
        ],
        "settlement_explanation": {
            key: manual_expected["settlement_explanation"][key]
            for key in ("original_amount", "discount_amount", "settled_amount")
        },
        "explicit_confirmation": True,
    }
    manual_returned = [
        {"kind": "confirmation", "id": confirmation_id},
        {"kind": "transaction", "id": transaction["id"]},
        *[{"kind": "domain_entity", "id": item["id"]} for item in created["domain_entities"]],
        {"kind": "relation", "id": manual_expected["association_group"]["id"]},
    ]
    accepted_manual = operation(
        manual_root["id"], 1, operation_id, "manual_merged_payment", "creation",
        current, created, input_value=manual_input, returned_ids=manual_returned,
    )
    manual_root["operation_ids"].append(operation_id)
    states.append(created)
    operations.append(accepted_manual)
    current, replay = replay_operation(
        manual_root["id"], 2, created, accepted_manual, manual_request
    )
    manual_root["operation_ids"].append(replay["id"])
    states.append(current)
    operations.append(replay)

    import_operations_v1 = v1["import_path"]["ordered_operations"]
    ingest_v1, confirm_v1, receipt_v1 = import_operations_v1
    bank_fact = ingest_v1["input"]["bank_fact"]
    item_facts = ingest_v1["input"]["item_facts"]
    import_root, current = add_root(
        "rg05_import_lifecycle", "$.import_path", bank_fact["source_id"], periods
    )
    locator = "$.import_path.ordered_operations[*]"
    operation_id = migration_id(import_root["id"], "operation", locator, ingest_v1["id"])
    ingested = result_state(current, operation_id, locator, ingest_v1["id"])
    ingested["sources"].append({
        "id": bank_fact["source_id"], "type": "merged_payment_bank_fact",
        "payload": {
            **{key: value for key, value in bank_fact.items() if key != "source_id"},
            "completeness": "complete",
        },
    })
    for item in item_facts:
        ingested["sources"].append({
            "id": item["source_id"], "type": "merged_payment_item_fact",
            "payload": {
                **{key: value for key, value in item.items() if key != "source_id"},
                "completeness": "complete" if item["evidence_kind"] == "item_receipt" else "summary_only",
            },
        })
    for source in ingested["sources"]:
        evidence_type = (
            "bank_payment" if source["type"] == "merged_payment_bank_fact"
            else source["payload"]["evidence_kind"]
        )
        ingested["evidence"].append({
            "id": source["payload"]["evidence_id"],
            "type": evidence_type,
            "source_ids": [source["id"]],
            "payload": {"observed_at": source["payload"]["observed_at"]},
        })
    candidate_id = ingest_v1["expected"]["candidate"]["id"]
    pending_status_id = migration_id(
        import_root["id"], "candidate_status",
        "$.import_path.ordered_operations[*].expected.candidate.status", candidate_id,
    )
    candidate = {
        "id": candidate_id,
        "type": "merged_payment",
        "source_ids": [bank_fact["source_id"], *[item["source_id"] for item in item_facts]],
        "confidence": "1.00",
        "payload": {
            "payment_total": str(-Decimal(bank_fact["amount"])),
            "currency": bank_fact["currency"],
            "bank_source_id": bank_fact["source_id"],
            "item_source_ids": [item["source_id"] for item in item_facts],
            "item_proposals": [
                {
                    "item_id": item["item_id"], "amount": item["amount"],
                    "currency": item["currency"],
                    "suggested_category_id": item["suggested_category_id"],
                    "source_id": item["source_id"], "evidence_id": item["evidence_id"],
                }
                for item in item_facts
            ],
            "evidence_refs": [bank_fact["evidence_id"], *[item["evidence_id"] for item in item_facts]],
            "provenance": {"rule": "merged_payment_facts", "rule_version": 1},
            "requires_confirmation": [
                "funding_account_id", "secondary_categories", "allocation_closure",
                "formal_transaction_creation",
            ],
        },
        "status_history": [{
            "id": pending_status_id, "sequence": 1, "status": "pending_confirmation",
        }],
    }
    ingested["candidates"].append(candidate)
    candidate_derived_id = migration_id(
        import_root["id"], "derived_status",
        "$.import_path.ordered_operations[*].expected.candidate.status", candidate_id,
    )
    ingested["derived_statuses"].append({
        "id": candidate_derived_id, "target_kind": "candidate", "target_id": candidate_id,
        "status_name": "confirmation_status", "value": "pending_confirmation",
    })
    ingest_input = {
        "request_id": bank_fact["source_id"],
        "bank_fact": bank_fact,
        "item_facts": item_facts,
    }
    ingest_returned = [
        *[{"kind": "source", "id": item["id"]} for item in ingested["sources"]],
        *[{"kind": "evidence", "id": item["id"]} for item in ingested["evidence"]],
        {"kind": "candidate", "id": candidate_id},
    ]
    accepted_ingest = operation(
        import_root["id"], 1, operation_id, "ingest_merged_payment_facts", "creation",
        current, ingested, input_value=ingest_input, returned_ids=ingest_returned,
    )
    import_root["operation_ids"].append(operation_id)
    states.append(ingested)
    operations.append(accepted_ingest)
    current, replay = replay_operation(
        import_root["id"], 2, ingested, accepted_ingest, bank_fact["source_id"]
    )
    import_root["operation_ids"].append(replay["id"])
    states.append(current)
    operations.append(replay)

    for sequence, failure in enumerate(v1["allocation_failures"], start=3):
        failure_locator = "$.allocation_failures[*]"
        operation_id = migration_id(import_root["id"], "operation", failure_locator, failure["id"])
        rejected_state = result_state(current, operation_id, failure_locator, failure["id"])
        reason = "allocation_incomplete" if failure["id"] == "incomplete-allocation" else "allocation_conflict"
        rejected = operation(
            import_root["id"], sequence, operation_id,
            "confirm_merged_payment_candidate", "rejection", current, rejected_state,
            attempted_input={
                "request_id": migration_id(
                    import_root["id"], "request", failure_locator, failure["id"]
                ),
                "candidate_id": candidate_id,
                **failure["input"],
                "explicit_confirmation": True,
            },
            outcome={
                "status": "rejected", "reason_code": reason,
                "field_path": "$.attempted_input.allocation_total",
            },
        )
        import_root["operation_ids"].append(operation_id)
        states.append(rejected_state)
        operations.append(rejected)
        current = rejected_state

    confirm_input_v1 = confirm_v1["input"]
    confirm_expected = confirm_v1["expected"]
    confirm_request = confirm_input_v1["request_id"]
    operation_id = migration_id(import_root["id"], "operation", locator, confirm_v1["id"])
    confirmed = result_state(current, operation_id, locator, confirm_v1["id"])
    candidate = next(item for item in confirmed["candidates"] if item["id"] == candidate_id)
    candidate["payload"]["transaction_id"] = confirm_expected["transaction"]["id"]
    candidate["status_history"].append({
        "id": migration_id(
            import_root["id"], "candidate_status",
            "$.import_path.ordered_operations[*].expected.candidate_status", confirm_request,
        ),
        "sequence": 2,
        "status": "confirmed",
    })
    next(
        item for item in confirmed["derived_statuses"] if item["id"] == candidate_derived_id
    )["value"] = "confirmed"
    confirmation_id = migration_id(
        import_root["id"], "confirmation",
        "$.import_path.ordered_operations[*].expected.candidate_status", confirm_request,
    )
    transaction = confirm_expected["transaction"]
    postings = [
        {
            "id": item["id"],
            "posting_set_id": transaction["posting_set_id"],
            "account_id": item["account_id"],
            **({"category_id": record["category_id"]} if item["amount"] != "-100.00" else {}),
            "amount": item["amount"],
            "currency": item["currency"],
            "role": "payment_asset" if item["amount"] == "-100.00" else "expense",
            "reconciliation_eligible": item["amount"] == "-100.00",
        }
        for item, record in zip(
            transaction["postings"],
            [*confirm_expected["consumption_records"], {}],
            strict=True,
        )
    ]
    add_transaction(
        confirmed,
        transaction["id"],
        transaction["current_version_id"],
        transaction["posting_set_id"],
        transaction["occurred_at"],
        confirmation_id,
        postings,
    )
    confirmed["confirmations"].append({
        "id": confirmation_id,
        "type": "candidate_confirmation",
        "operation_id": operation_id,
        "subject": {"kind": "candidate", "id": candidate_id},
        "payload": {},
    })
    confirmed["domain_entities"].extend(domain_entities(confirm_expected, imported=True))
    confirmed["relations"].append(relation(confirm_expected))
    bank_link_v1 = confirm_expected["financial_evidence_links"][0]
    item_link_v1 = confirm_expected["item_evidence_links"][0]
    confirmed["evidence_links"].extend([
        {
            "id": bank_link_v1["id"], "evidence_id": bank_link_v1["evidence_id"],
            "target_kind": "posting", "target_id": bank_link_v1["posting_id"],
            "role": "payment_asset_posting",
        },
        {
            "id": item_link_v1["id"], "evidence_id": item_link_v1["evidence_id"],
            "target_kind": "domain_entity", "target_id": item_link_v1["item_allocation_id"],
            "role": "item_allocation_fact",
        },
    ])
    reconciliation_id = migration_id(
        import_root["id"], "posting_reconciliation",
        "$.import_path.ordered_operations[*].expected.reconciliation",
        "posting-asset-rg05-imported",
    )
    confirmed["posting_reconciliations"].append({
        "id": reconciliation_id,
        "posting_id": "posting-asset-rg05-imported",
        "status": "matched",
    })
    transaction_derived_id = migration_id(
        import_root["id"], "derived_status",
        "$.import_path.ordered_operations[*].expected.reconciliation", transaction["id"],
    )
    relation_derived_id = migration_id(
        import_root["id"], "derived_status",
        "$.import_path.ordered_operations[*].expected.item_evidence_completeness",
        confirm_expected["association_group"]["id"],
    )
    confirmed["derived_statuses"].extend([
        {
            "id": transaction_derived_id, "target_kind": "transaction",
            "target_id": transaction["id"], "status_name": "reconciliation_summary",
            "value": "matched",
        },
        {
            "id": relation_derived_id, "target_kind": "relation",
            "target_id": confirm_expected["association_group"]["id"],
            "status_name": "item_evidence_completeness", "value": "partial",
        },
    ])
    refresh_projections(confirmed)
    confirm_input = {
        "request_id": confirm_request,
        "candidate_id": candidate_id,
        "funding_account_id": confirm_input_v1["funding_account_id"],
        "payment_at": confirm_input_v1["payment_at"],
        "common_statistics_at": confirm_input_v1["common_statistics_at"],
        "items": confirm_input_v1["items"],
        "explicit_confirmation": True,
    }
    confirm_returned = [
        {"kind": "candidate", "id": candidate_id},
        {"kind": "confirmation", "id": confirmation_id},
        {"kind": "transaction", "id": transaction["id"]},
        *[{"kind": "domain_entity", "id": item["id"]} for item in confirmed["domain_entities"]],
        {"kind": "relation", "id": confirm_expected["association_group"]["id"]},
        *[{"kind": "evidence_link", "id": item["id"]} for item in confirmed["evidence_links"]],
    ]
    accepted_confirm = operation(
        import_root["id"], 5, operation_id,
        "confirm_merged_payment_candidate", "creation", current, confirmed,
        input_value=confirm_input, returned_ids=confirm_returned,
    )
    import_root["operation_ids"].append(operation_id)
    states.append(confirmed)
    operations.append(accepted_confirm)
    current, replay = replay_operation(
        import_root["id"], 6, confirmed, accepted_confirm, confirm_request
    )
    import_root["operation_ids"].append(replay["id"])
    states.append(current)
    operations.append(replay)

    receipt_input_v1 = receipt_v1["input"]
    operation_id = migration_id(import_root["id"], "operation", locator, receipt_v1["id"])
    completed = result_state(current, operation_id, locator, receipt_v1["id"])
    allocation = next(
        item for item in completed["domain_entities"]
        if item["id"] == receipt_input_v1["item_allocation_id"]
    )
    completed["sources"].append({
        "id": receipt_input_v1["source_id"],
        "type": "merged_payment_item_fact",
        "payload": {
            "item_id": allocation["payload"]["source_item_id"],
            "evidence_id": receipt_input_v1["evidence_id"],
            "evidence_kind": "item_receipt",
            "observed_at": receipt_input_v1["observed_at"],
            "details": receipt_input_v1["details"],
            "amount": receipt_input_v1["amount"],
            "currency": receipt_input_v1["currency"],
            "suggested_category_id": allocation["payload"]["category_id"],
            "completeness": "complete",
        },
    })
    completed["evidence"].append({
        "id": receipt_input_v1["evidence_id"],
        "type": "item_receipt",
        "source_ids": [receipt_input_v1["source_id"]],
        "payload": {"observed_at": receipt_input_v1["observed_at"]},
    })
    completed["evidence_links"].append({
        "id": "match-item-b-rg05",
        "evidence_id": receipt_input_v1["evidence_id"],
        "target_kind": "domain_entity",
        "target_id": receipt_input_v1["item_allocation_id"],
        "role": "item_allocation_fact",
    })
    next(
        item for item in completed["derived_statuses"] if item["id"] == relation_derived_id
    )["value"] = "complete"
    refresh_projections(completed)
    receipt_input = {"request_id": receipt_input_v1["evidence_id"], **receipt_input_v1}
    receipt_returned = [
        {"kind": "source", "id": receipt_input_v1["source_id"]},
        {"kind": "evidence", "id": receipt_input_v1["evidence_id"]},
        {"kind": "evidence_link", "id": "match-item-b-rg05"},
    ]
    accepted_receipt = operation(
        import_root["id"], 7, operation_id, "merge_item_receipt_evidence",
        "reconciliation", current, completed,
        input_value=receipt_input, returned_ids=receipt_returned,
    )
    import_root["operation_ids"].append(operation_id)
    states.append(completed)
    operations.append(accepted_receipt)
    current, replay = replay_operation(
        import_root["id"], 8, completed, accepted_receipt, receipt_input_v1["evidence_id"]
    )
    import_root["operation_ids"].append(replay["id"])
    states.append(current)
    operations.append(replay)

    for invalid in v1["invalid_manual_inputs"]:
        invalid_id = invalid["id"]
        invalid_root, initial = add_root(
            "rg05_invalid_" + invalid_id.replace("-", "_"),
            "$.invalid_manual_inputs[*]", invalid_id,
            (("cumulative", "lifecycle"),),
        )
        operation_id = migration_id(
            invalid_root["id"], "operation", "$.invalid_manual_inputs[*]", invalid_id
        )
        result = result_state(
            initial, operation_id, "$.invalid_manual_inputs[*].expected", invalid_id
        )
        request_id = migration_id(
            invalid_root["id"], "request", "$.invalid_manual_inputs[*].id", invalid_id
        )
        rejected = operation(
            invalid_root["id"], 1, operation_id, "manual_merged_payment", "rejection",
            initial, result,
            attempted_input={"request_id": request_id, **invalid["input"]},
            outcome={
                "status": "rejected",
                "reason_code": invalid["expected"]["reason"],
                "field_path": "$.attempted_input." + invalid["expected"]["field"],
            },
        )
        invalid_root["operation_ids"].append(operation_id)
        states.append(result)
        operations.append(rejected)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: (item["root_id"], item["as_of_operation_id"] is not None, item["id"]))
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "case": {
            "id": "RG-05", "level": "core_required", "rule_version": 1,
            "approval_status": "draft_for_review", "ledger_id": "ledger-a",
            "timezone": "Asia/Shanghai",
            "currencies": [{"code": "CNY", "precision": 2}],
        },
        "roots": roots,
        "states": states,
        "operations": operations,
    }


def write_rg05_expected() -> None:
    EXPECTED_PATH.write_text(
        json.dumps(build_rg05_expected(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


class RG05GoldenV2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not EXPECTED_PATH.exists():
            raise AssertionError("RG-05 expected output is not generated")
        cls.case = load_golden_case_v2(EXPECTED_PATH)
        cls.v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.roots_by_purpose = {item["purpose"]: item for item in cls.case["roots"]}
        cls.states = {item["id"]: item for item in cls.case["states"]}
        cls.operations = {item["id"]: item for item in cls.case["operations"]}

    def root_operations(self, purpose: str) -> list[dict]:
        return [
            self.operations[operation_id]
            for operation_id in self.roots_by_purpose[purpose]["operation_ids"]
        ]

    def state(self, operation: dict) -> dict:
        return self.states[operation["result_state_id"]]

    def applicable_metrics(self, state: dict, period_type: str) -> dict[str, str]:
        item = next(report for report in state["reports"] if report["period_type"] == period_type)
        return {
            metric["metric"]: metric["amount"]
            for metric in item["metrics"]
            if metric["applicability"] == "applicable"
        }

    def assert_replay(self, accepted: dict, replay: dict) -> None:
        self.assertEqual(
            replay["outcome"],
            {"status": "no_change", "reason_code": "idempotent_replay"},
        )
        self.assertEqual(replay["action_type"], accepted["action_type"])
        self.assertEqual(replay["operation_class"], accepted["operation_class"])
        self.assertEqual(replay["input"], accepted["input"])
        self.assertEqual(replay["returned_ids"], accepted["returned_ids"])
        self.assertTrue(empty_deltas(replay))
        self.assertEqual(
            state_payload(self.states[replay["baseline_state_id"]]),
            state_payload(self.states[replay["result_state_id"]]),
        )

    def test_schema_and_complete_semantic_validator(self):
        Draft202012Validator.check_schema(self.schema)
        Draft202012Validator(self.schema).validate(self.case)
        validate_golden_case_v2(self.case)
        self.assertEqual(self.case["case"]["id"], "RG-05")
        self.assertEqual(self.case["case"]["approval_status"], "draft_for_review")

    def test_checked_in_expected_matches_generator(self):
        self.assertEqual(self.case, build_rg05_expected())

    def test_exact_topology_and_outcome_inventory(self):
        self.assertEqual(
            (len(self.case["roots"]), len(self.case["operations"]), len(self.case["states"])),
            (17, 25, 42),
        )
        statuses = [item["outcome"]["status"] for item in self.case["operations"]]
        self.assertEqual(statuses.count("accepted"), 4)
        self.assertEqual(statuses.count("no_change"), 4)
        self.assertEqual(statuses.count("rejected"), 17)
        self.assertEqual(len(self.root_operations("rg05_manual_merged_payment")), 2)
        self.assertEqual(len(self.root_operations("rg05_import_lifecycle")), 8)

    def test_four_accepted_actions_have_complete_replays(self):
        manual, manual_replay = self.root_operations("rg05_manual_merged_payment")
        self.assert_replay(manual, manual_replay)
        operations = self.root_operations("rg05_import_lifecycle")
        for accepted_index, replay_index in ((0, 1), (4, 5), (6, 7)):
            self.assert_replay(operations[accepted_index], operations[replay_index])

    def test_all_rejections_are_atomic_and_preserve_complete_state(self):
        rejected = [
            item for item in self.case["operations"]
            if item["outcome"]["status"] == "rejected"
        ]
        self.assertEqual(len(rejected), 17)
        for operation in rejected:
            with self.subTest(operation_id=operation["id"]):
                self.assertEqual(operation["operation_class"], "rejection")
                self.assertEqual(operation["returned_ids"], [])
                self.assertTrue(empty_deltas(operation))
                self.assertEqual(
                    state_payload(self.states[operation["baseline_state_id"]]),
                    state_payload(self.states[operation["result_state_id"]]),
                )

    def test_manual_result_freezes_one_payment_two_consumptions_and_reports(self):
        accepted, _ = self.root_operations("rg05_manual_merged_payment")
        state = self.state(accepted)
        transaction = next(item for item in state["transactions"] if item["type"] == "expense")
        version = next(item for item in state["transaction_versions"] if item["id"] == transaction["current_version_id"])
        posting_ids = next(item for item in state["posting_sets"] if item["id"] == version["posting_set_id"])["posting_ids"]
        postings = [item for item in state["postings"] if item["id"] in posting_ids]
        self.assertEqual(
            {(item["role"], item["amount"]) for item in postings},
            {("expense", "40.00"), ("expense", "60.00"), ("payment_asset", "-100.00")},
        )
        self.assertEqual(sum(Decimal(item["amount"]) for item in postings), Decimal("0.00"))
        self.assertEqual(sum(item["role"] == "payment_asset" for item in postings), 1)
        self.assertEqual(
            {kind: sum(item["type"] == kind for item in state["domain_entities"])
             for kind in ("consumption_record", "item_allocation")},
            {"consumption_record": 2, "item_allocation": 2},
        )
        relation_value = state["relations"][0]
        self.assertEqual(relation_value["type"], "merged_payment")
        self.assertEqual(len(relation_value["member_refs"]), 4)
        self.assertFalse(relation_value["payload"]["generic_order_lifecycle"])
        self.assertEqual(
            self.applicable_metrics(state, "day"),
            {
                "balance_adjustment_net_worth_change": "0.00", "cash_inflow": "0.00",
                "cash_outflow": "100.00", "consumption": "100.00", "income": "0.00",
                "internal_transfer_amount": "0.00", "net_worth_change": "-100.00",
                "ordinary_expense": "100.00", "ordinary_income": "0.00",
            },
        )
        self.assertIn("settlement_explanation", accepted["input"])
        serialized = json.dumps(state, ensure_ascii=False)
        self.assertNotIn("112.00", serialized)
        self.assertNotIn("12.00", serialized)
        self.assertFalse(any("clearing" in item["id"] or "discount" in item["id"] for item in state["postings"]))

    def test_ingest_is_pending_intake_with_zero_formal_effect(self):
        ingest = self.root_operations("rg05_import_lifecycle")[0]
        baseline = self.states[ingest["baseline_state_id"]]
        state = self.state(ingest)
        self.assertEqual((len(state["sources"]), len(state["evidence"]), len(state["candidates"])), (3, 3, 1))
        self.assertEqual(state["candidates"][0]["status_history"][-1]["status"], "pending_confirmation")
        for collection in (
            "transactions", "transaction_versions", "posting_sets", "postings", "confirmations",
            "evidence_links", "relations", "domain_entities", "posting_reconciliations",
            "balances", "reports",
        ):
            self.assertEqual(state[collection], baseline[collection])
        self.assertEqual(
            {key: value for key, value in ingest["deltas"]["entity_changes"].items() if any(value.values())},
            {
                "sources": {"added_ids": sorted(item["id"] for item in state["sources"]), "changed_ids": [], "removed_ids": []},
                "candidates": {"added_ids": ["candidate-rg05-imported"], "changed_ids": [], "removed_ids": []},
                "evidence": {"added_ids": sorted(item["id"] for item in state["evidence"]), "changed_ids": [], "removed_ids": []},
            },
        )

    def test_confirmation_creates_exact_entities_and_separates_evidence_statuses(self):
        confirmation = self.root_operations("rg05_import_lifecycle")[4]
        state = self.state(confirmation)
        candidate = state["candidates"][0]
        self.assertEqual([item["status"] for item in candidate["status_history"]], ["pending_confirmation", "confirmed"])
        self.assertNotIn("conflict", [item["status"] for item in candidate["status_history"]])
        transaction = next(item for item in state["transactions"] if item["id"] == "tx-merged-rg05-imported")
        version = next(item for item in state["transaction_versions"] if item["id"] == transaction["current_version_id"])
        posting_ids = next(item for item in state["posting_sets"] if item["id"] == version["posting_set_id"])["posting_ids"]
        postings = [item for item in state["postings"] if item["id"] in posting_ids]
        self.assertEqual({(item["role"], item["amount"]) for item in postings}, {("expense", "40.00"), ("expense", "60.00"), ("payment_asset", "-100.00")})
        self.assertEqual({item["type"] for item in state["domain_entities"]}, {"consumption_record", "item_allocation"})
        self.assertEqual(len(state["domain_entities"]), 4)
        self.assertEqual(len(state["relations"]), 1)
        links = {item["role"]: item for item in state["evidence_links"]}
        self.assertEqual(links["payment_asset_posting"]["target_id"], "posting-asset-rg05-imported")
        self.assertEqual(links["payment_asset_posting"]["target_kind"], "posting")
        self.assertEqual(links["item_allocation_fact"]["target_id"], "allocation-rg05-imported-a")
        self.assertEqual(links["item_allocation_fact"]["target_kind"], "domain_entity")
        self.assertEqual({item["posting_id"]: item["status"] for item in state["posting_reconciliations"]}, {"posting-asset-rg05-imported": "matched"})
        statuses = {(item["target_kind"], item["status_name"]): item["value"] for item in state["derived_statuses"]}
        self.assertEqual(statuses[("transaction", "reconciliation_summary")], "matched")
        self.assertEqual(statuses[("relation", "item_evidence_completeness")], "partial")

    def test_receipt_merge_only_adds_source_evidence_link_and_completeness(self):
        receipt = self.root_operations("rg05_import_lifecycle")[6]
        baseline = self.states[receipt["baseline_state_id"]]
        state = self.state(receipt)
        for collection in (
            "transactions", "transaction_versions", "posting_sets", "postings", "candidates",
            "confirmations", "relations", "domain_entities", "posting_reconciliations",
            "balances", "reports",
        ):
            self.assertEqual(state[collection], baseline[collection])
        nonempty = {key: value for key, value in receipt["deltas"]["entity_changes"].items() if any(value.values())}
        self.assertEqual(set(nonempty), {"sources", "evidence", "evidence_links"})
        self.assertEqual(nonempty["sources"]["added_ids"], ["source-item-b-receipt-rg05"])
        self.assertEqual(nonempty["evidence"]["added_ids"], ["evidence-item-b-receipt-rg05"])
        self.assertEqual(nonempty["evidence_links"]["added_ids"], ["match-item-b-rg05"])
        before_status = next(item for item in baseline["derived_statuses"] if item["status_name"] == "item_evidence_completeness")
        after_status = next(item for item in state["derived_statuses"] if item["status_name"] == "item_evidence_completeness")
        self.assertEqual((before_status["value"], after_status["value"]), ("partial", "complete"))
        self.assertEqual(receipt["deltas"]["value_changes"]["balances"], [])
        self.assertEqual(receipt["deltas"]["value_changes"]["reports"], [])

    def test_invalid_reasons_and_fields_match_all_fifteen_v1_cases(self):
        self.assertEqual(len(self.v1["invalid_manual_inputs"]), 15)
        for invalid in self.v1["invalid_manual_inputs"]:
            operation_value = self.root_operations("rg05_invalid_" + invalid["id"].replace("-", "_"))[0]
            self.assertEqual(operation_value["outcome"], {
                "status": "rejected", "reason_code": invalid["expected"]["reason"],
                "field_path": "$.attempted_input." + invalid["expected"]["field"],
            })

    def test_allocation_failures_keep_candidate_pending_without_conflict_state(self):
        incomplete, conflict = self.root_operations("rg05_import_lifecycle")[2:4]
        self.assertEqual(incomplete["outcome"], {"status": "rejected", "reason_code": "allocation_incomplete", "field_path": "$.attempted_input.allocation_total"})
        self.assertEqual(conflict["outcome"], {"status": "rejected", "reason_code": "allocation_conflict", "field_path": "$.attempted_input.allocation_total"})
        for item in (incomplete, conflict):
            candidate = self.state(item)["candidates"][0]
            self.assertEqual([event["status"] for event in candidate["status_history"]], ["pending_confirmation"])

    def test_returned_ids_are_exact_and_stable_by_action(self):
        manual = self.root_operations("rg05_manual_merged_payment")[0]
        ingest, _, _, _, confirm, _, receipt, _ = self.root_operations("rg05_import_lifecycle")
        manual_root = self.roots_by_purpose["rg05_manual_merged_payment"]["id"]
        import_root = self.roots_by_purpose["rg05_import_lifecycle"]["id"]
        self.assertEqual(manual["returned_ids"], [
            {"kind": "confirmation", "id": migration_id(manual_root, "confirmation", "$.manual_path.confirmation", "request-rg05-manual")},
            {"kind": "transaction", "id": "tx-merged-rg05-manual"},
            {"kind": "domain_entity", "id": "consumption-rg05-manual-a"},
            {"kind": "domain_entity", "id": "consumption-rg05-manual-b"},
            {"kind": "domain_entity", "id": "allocation-rg05-manual-a"},
            {"kind": "domain_entity", "id": "allocation-rg05-manual-b"},
            {"kind": "relation", "id": "association-group-rg05-manual"},
        ])
        self.assertEqual(ingest["returned_ids"], [
            {"kind": "source", "id": "source-bank-debit-rg05"},
            {"kind": "source", "id": "source-item-a-rg05"},
            {"kind": "source", "id": "source-item-b-rg05"},
            {"kind": "evidence", "id": "evidence-bank-debit-rg05"},
            {"kind": "evidence", "id": "evidence-item-a-rg05"},
            {"kind": "evidence", "id": "evidence-item-b-summary-rg05"},
            {"kind": "candidate", "id": "candidate-rg05-imported"},
        ])
        self.assertEqual(confirm["returned_ids"], [
            {"kind": "candidate", "id": "candidate-rg05-imported"},
            {"kind": "confirmation", "id": migration_id(import_root, "confirmation", "$.import_path.ordered_operations[*].expected.candidate_status", "request-rg05-confirm-candidate")},
            {"kind": "transaction", "id": "tx-merged-rg05-imported"},
            {"kind": "domain_entity", "id": "consumption-rg05-imported-a"},
            {"kind": "domain_entity", "id": "consumption-rg05-imported-b"},
            {"kind": "domain_entity", "id": "allocation-rg05-imported-a"},
            {"kind": "domain_entity", "id": "allocation-rg05-imported-b"},
            {"kind": "relation", "id": "association-group-rg05-imported"},
            {"kind": "evidence_link", "id": "match-bank-rg05"},
            {"kind": "evidence_link", "id": "match-item-a-rg05"},
        ])
        self.assertEqual(receipt["returned_ids"], [
            {"kind": "source", "id": "source-item-b-receipt-rg05"},
            {"kind": "evidence", "id": "evidence-item-b-receipt-rg05"},
            {"kind": "evidence_link", "id": "match-item-b-rg05"},
        ])

    def test_same_request_changed_input_is_atomic_identity_conflict_for_all_actions(self):
        fixtures = []
        manual, manual_replay = self.root_operations("rg05_manual_merged_payment")
        fixtures.append((manual, manual_replay, lambda value: value["items"][0].__setitem__("details", "Changed manual details")))
        import_ops = self.root_operations("rg05_import_lifecycle")
        fixtures.extend([
            (import_ops[0], import_ops[1], lambda value: value["bank_fact"].__setitem__("details", "Changed bank details")),
            (import_ops[4], import_ops[5], lambda value: value["items"][0].__setitem__("category_id", "expense-category-service")),
            (import_ops[6], import_ops[7], lambda value: value.__setitem__("details", "Changed receipt details")),
        ])
        for accepted, replay, mutate in fixtures:
            with self.subTest(action=accepted["action_type"]):
                case = deepcopy(self.case)
                operation_value = next(item for item in case["operations"] if item["id"] == replay["id"])
                attempted = operation_value.pop("input")
                mutate(attempted)
                operation_value["attempted_input"] = attempted
                operation_value["operation_class"] = "rejection"
                operation_value["outcome"] = {
                    "status": "rejected", "reason_code": "identity_conflict",
                    "field_path": "$.attempted_input.request_id",
                }
                operation_value["returned_ids"] = []
                validate_golden_case_v2(case)

                attempted["request_id"] += "-different"
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(case)

    def test_accepted_action_effects_are_owned_by_closed_inputs(self):
        manual = self.root_operations("rg05_manual_merged_payment")[0]
        import_ops = self.root_operations("rg05_import_lifecycle")
        mutations = [
            (manual, lambda state: next(
                item for item in state["domain_entities"]
                if item["id"] == "consumption-rg05-manual-a"
            )["payload"].__setitem__("details", "Wrong manual details")),
            (manual, lambda state: next(
                item for item in state["domain_entities"]
                if item["id"] == "consumption-rg05-manual-a"
            )["payload"].__setitem__("category_id", "expense-category-service")),
            (import_ops[0], lambda state: next(
                item for item in state["sources"] if item["id"] == "source-bank-debit-rg05"
            )["payload"].__setitem__("details", "Wrong ingest details")),
            (import_ops[4], lambda state: next(
                item for item in state["domain_entities"]
                if item["id"] == "allocation-rg05-imported-a"
            )["payload"].__setitem__("category_id", "expense-category-service")),
            (import_ops[6], lambda state: next(
                item for item in state["sources"] if item["id"] == "source-item-b-receipt-rg05"
            )["payload"].__setitem__("details", "Wrong receipt details")),
        ]
        for operation_value, mutate in mutations:
            with self.subTest(action=operation_value["action_type"]):
                case = deepcopy(self.case)
                result = next(item for item in case["states"] if item["id"] == operation_value["result_state_id"])
                mutate(result)
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(case)

        for operation_value in (manual, import_ops[4]):
            with self.subTest(returned_ids=operation_value["action_type"]):
                case = deepcopy(self.case)
                changed = next(item for item in case["operations"] if item["id"] == operation_value["id"])
                changed["returned_ids"] = changed["returned_ids"][:-1]
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(case)

    def test_rg05_opening_and_confirmation_times_do_not_synthesize_audit_time(self):
        case = deepcopy(self.case)
        opening = next(
            version
            for state in case["states"]
            for version in state["transaction_versions"]
            if version["transaction_id"] == "tx-opening-rg05"
        )
        opening["created_at"] = opening["occurred_at"]
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(case)

        case = deepcopy(self.case)
        confirm = self.root_operations("rg05_import_lifecycle")[4]
        result = next(item for item in case["states"] if item["id"] == confirm["result_state_id"])
        result["confirmations"][0]["confirmed_at"] = "2026-04-10T18:30:00+08:00"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(case)

    def test_allocation_rejection_must_match_baseline_pending_candidate(self):
        incomplete = self.root_operations("rg05_import_lifecycle")[2]
        for field, value in (
            ("candidate_id", "candidate-unknown"),
            ("payment_total", "99.00"),
            ("currency", "USD"),
        ):
            with self.subTest(field=field):
                case = deepcopy(self.case)
                operation_value = next(
                    item for item in case["operations"] if item["id"] == incomplete["id"]
                )
                operation_value["attempted_input"][field] = value
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(case)

if __name__ == "__main__":
    unittest.main()
