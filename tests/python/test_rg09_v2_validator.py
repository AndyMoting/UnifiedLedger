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
V1_PATH = ROOT / "golden" / "rules" / "rg-09.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
RG09_METRICS = (
    "balance_adjustment_net_worth_change",
    "budget",
    "cash_inflow",
    "cash_outflow",
    "consumption",
    "internal_transfer_amount",
    "net_worth_change",
    "ordinary_expense",
    "ordinary_income",
)

# The 50 frozen v1 operations of golden/rules/rg-09.json, in v1 file order:
# (v1 source path, v1 operation id, v2 action_type, operation_class).  The ids
# are carried over id for id into the v2 case; the paths are the v1 locations.
V1_OPERATION_PLAN: list[tuple[str, str, str, str]] = [
    ("main_path/preview", "preview-rg09", "preview_target_balance", "read"),
    ("main_path/confirmation", "confirm-adjustment-rg09", "confirm_balance_adjustment", "adjustment"),
    ("main_path/transfer_confirmation", "transfer-confirmation-rg09", "confirm_real_transfer", "creation"),
    ("main_path/explanation_confirmation", "explanation-confirmation-rg09", "confirm_explanation_allocation", "reversal"),
    ("main_path/second_transfer_confirmation", "second-transfer-confirmation-rg09", "confirm_second_real_transfer", "creation"),
    ("main_path/second_explanation_confirmation", "second-explanation-confirmation-rg09", "confirm_second_explanation_allocation", "reversal"),
    ("stale_preview", "stale-preview-rg09", "reject_stale_preview", "rejection"),
    ("zero_delta", "zero-delta-rg09", "save_zero_delta_observation", "creation"),
    ("import_path/pending", "pending-import-rg09", "receive_import_candidate", "creation"),
    ("import_path/incomplete_confirmations[*]", "missing-transaction", "reject_incomplete_import_confirmation", "rejection"),
    ("import_path/incomplete_confirmations[*]", "missing-account", "reject_incomplete_import_confirmation", "rejection"),
    ("import_path/incomplete_confirmations[*]", "missing-actual-time", "reject_incomplete_import_confirmation", "rejection"),
    ("import_path/incomplete_confirmations[*]", "missing-currency", "reject_incomplete_import_confirmation", "rejection"),
    ("import_path/incomplete_confirmations[*]", "missing-allocation", "reject_incomplete_import_confirmation", "rejection"),
    ("import_path/transfer_confirmation", "import-transfer-confirmation-rg09", "confirm_imported_real_transfer", "creation"),
    ("import_path/explanation_confirmation", "import-explanation-confirmation-rg09", "confirm_imported_explanation_allocation", "reversal"),
] + [
    ("invalid_inputs[*]", op_id, "reject_invalid_rg09_input", "rejection")
    for op_id in (
        "invalid-target-decimal", "invalid-target-time", "wrong-target-timezone",
        "unknown-target-account", "unowned-target-account", "nonasset-target-account",
        "wrong-target-currency", "wrong-adjustment-equity", "wrong-explanation-direction",
        "wrong-explanation-account", "wrong-explanation-currency", "explanation-after-target",
        "over-remaining-allocation", "guessed-link", "duplicate-conflicting-key",
    )
] + [
    ("idempotency/retries[*]", retry_id, action, cls)
    for (retry_id, action, cls) in (
        ("retry-preview-rg09", "preview_target_balance", "read"),
        ("retry-confirm-adjustment-rg09", "confirm_balance_adjustment", "adjustment"),
        ("retry-target-source-rg09", "preview_target_balance", "read"),
        ("retry-transfer-rg09", "confirm_real_transfer", "creation"),
        ("retry-allocation-rg09", "confirm_explanation_allocation", "reversal"),
        ("retry-import-transfer-confirm-rg09", "confirm_imported_real_transfer", "creation"),
        ("retry-import-allocation-confirm-rg09", "confirm_imported_explanation_allocation", "reversal"),
        ("retry-import-source-rg09", "receive_import_candidate", "creation"),
        ("retry-second-transfer-rg09", "confirm_second_real_transfer", "creation"),
        ("retry-second-allocation-rg09", "confirm_second_explanation_allocation", "reversal"),
        ("retry-transfer-a-source-rg09", "link_real_posting_evidence", "reconciliation"),
        ("retry-transfer-b-source-rg09", "link_real_posting_evidence", "reconciliation"),
        ("retry-transfer-a-remaining-source-rg09", "link_real_posting_evidence", "reconciliation"),
        ("retry-transfer-b-remaining-source-rg09", "link_real_posting_evidence", "reconciliation"),
        ("retry-zero-delta-rg09", "save_zero_delta_observation", "creation"),
    )
] + [
    ("evidence_path/" + section, op_id, "link_real_posting_evidence", "reconciliation")
    for (section, op_id) in (
        ("first_transfer_asset_a", "link-first_transfer_asset_a-rg09"),
        ("first_transfer_asset_b", "link-first_transfer_asset_b-rg09"),
        ("second_transfer_asset_a", "link-second_transfer_asset_a-rg09"),
        ("second_transfer_asset_b", "link-second_transfer_asset_b-rg09"),
    )
]

# v1 intake/formal delta fields projected onto v2 entity collections.  The
# expectation table below is verified directly against golden/rules/rg-09.json
# per operation and asserted per accepted operation in the 50-op case.
EXPECTED_ACCEPTED_COUNTS: dict[str, dict[str, tuple[int, int, int]]] = {
    "preview_target_balance": {
        "sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0), "domain_entities": (1, 0, 0),
    },
    "confirm_balance_adjustment": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "candidates": (0, 1, 0), "confirmations": (1, 0, 0),
        "domain_entities": (1, 0, 0), "audit_links": (1, 0, 0),
    },
    "confirm_real_transfer": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "posting_reconciliations": (2, 0, 0),
    },
    "confirm_explanation_allocation": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "domain_entities": (1, 0, 0),
        "audit_links": (2, 0, 0),
    },
    # v1 second-transfer-confirmation-rg09: transactions/versions/sets/postings
    # (1/1/1/2), confirmations (1), sources (1), NO posting_reconciliations.
    "confirm_second_real_transfer": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "sources": (1, 0, 0),
    },
    # v1 second-explanation-confirmation-rg09: audit_links (3, 0, 0),
    # allocations (1), reversal (1), NO posting_reconciliations.
    "confirm_second_explanation_allocation": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "domain_entities": (1, 0, 0),
        "audit_links": (3, 0, 0),
    },
    "save_zero_delta_observation": {
        "sources": (1, 0, 0), "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0), "domain_entities": (1, 0, 0),
    },
    "receive_import_candidate": {
        "sources": (1, 0, 0), "candidates": (1, 0, 0), "evidence": (1, 0, 0),
    },
    "confirm_imported_real_transfer": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0),
    },
    "confirm_imported_explanation_allocation": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "domain_entities": (1, 0, 0),
        "audit_links": (3, 0, 0),
    },
    "link_real_posting_evidence": {
        "sources": (1, 0, 0), "evidence": (1, 0, 0),
        "evidence_links": (1, 0, 0), "posting_reconciliations": (0, 1, 0),
    },
}


def mid(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id("RG-09", root_id, kind, locator, occurrence)


def state_id(root_id: str, locator: str, occurrence: str) -> str:
    return mid(root_id, "state", locator, occurrence)


def catalog() -> dict:
    return {
        "accounts": [
            {"id": "asset-a", "name": "Asset A", "kind": "asset", "currency": "CNY",
             "owned_by_user": True, "real_account": True, "reconciliation_eligible": True},
            {"id": "asset-b", "name": "Asset B", "kind": "asset", "currency": "CNY",
             "owned_by_user": True, "real_account": True, "reconciliation_eligible": True},
            {"id": "equity-balance-adjustments", "name": "Balance adjustments", "kind": "equity",
             "currency": "CNY", "owned_by_user": False, "real_account": False,
             "reconciliation_eligible": False, "system_role": "balance_adjustments",
             "system_managed": True, "hidden": True},
            {"id": "equity-opening", "name": "Opening equity", "kind": "equity", "currency": "CNY",
             "owned_by_user": False, "real_account": False, "reconciliation_eligible": False,
             "system_role": "opening_balance", "system_managed": True, "hidden": True},
            {"id": "asset-external", "name": "External asset", "kind": "asset", "currency": "CNY",
             "owned_by_user": False, "real_account": True, "reconciliation_eligible": False},
            {"id": "expense-validation", "name": "Validation expense", "kind": "expense",
             "currency": "CNY", "owned_by_user": False, "real_account": False,
             "reconciliation_eligible": False},
        ],
        "categories": [],
    }


def report() -> dict:
    return {
        "period_type": "month", "period": "2026-01",
        "metrics": [
            {"metric": metric, "applicability": "applicable", "currency": "CNY", "amount": "0.00"}
            for metric in RG09_METRICS
        ],
    }


def current_parts(state: dict) -> list[tuple[dict, dict, list[dict]]]:
    versions = {item["id"]: item for item in state["transaction_versions"]}
    sets = {item["id"]: item for item in state["posting_sets"]}
    postings = {item["id"]: item for item in state["postings"]}
    return [
        (transaction, versions[transaction["current_version_id"]], [
            postings[item_id]
            for item_id in sets[versions[transaction["current_version_id"]]["posting_set_id"]]["posting_ids"]
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
        for account_id, amount in sorted(amounts.items())
    ]
    accounts = {item["id"]: item for item in state["catalog"]["accounts"]}
    current = {transaction["id"]: part for part in current_parts(state) for transaction in [part[0]]}
    for item in state["reports"]:
        values = golden_v2._report_values(current, accounts, item, "CNY")
        for metric in item["metrics"]:
            if metric["applicability"] == "applicable":
                metric["amount"] = f"{values[metric['metric']]:.2f}"


def refresh_statuses(state: dict) -> None:
    indexes = golden_v2._state_indexes(state, "$")
    current = {
        transaction["id"]: part
        for part in current_parts(state)
        for transaction in [part[0]]
    }
    by_posting = {
        item["posting_id"]: item["status"]
        for item in state["posting_reconciliations"]
    }
    expected = golden_v2._expected_derived_statuses(
        state, indexes, current, by_posting
    )
    state["derived_statuses"] = [
        {
            "id": mid(state["root_id"], "derived_status", "$",
                      f"{kind}|{target_id}|{status_name}"),
            "target_kind": kind, "target_id": target_id,
            "status_name": status_name, "value": value,
        }
        for (kind, target_id, status_name), value in sorted(expected.items())
    ]


def empty_state(root_id: str, as_of: str | None) -> dict:
    version_id = mid(root_id, "transaction_version", "$.opening", "opening")
    set_id = mid(root_id, "posting_set", "$.opening", "opening")
    postings = [
        {"id": "posting-opening-a-rg09", "posting_set_id": set_id, "account_id": "asset-a",
         "amount": "100.00", "currency": "CNY", "reconciliation_eligible": False},
        {"id": "posting-opening-b-rg09", "posting_set_id": set_id, "account_id": "asset-b",
         "amount": "50.00", "currency": "CNY", "reconciliation_eligible": False},
        {"id": "posting-opening-equity-rg09", "posting_set_id": set_id,
         "account_id": "equity-opening", "amount": "-150.00", "currency": "CNY",
         "reconciliation_eligible": False},
    ]
    result = {
        "id": state_id(root_id, "$.opening", "initial"), "root_id": root_id,
        "as_of_operation_id": as_of, "catalog": catalog(),
        "transactions": [{"id": "transaction-opening-rg09", "type": "opening_balance",
                          "current_version_id": version_id}],
        "transaction_versions": [{"id": version_id, "transaction_id": "transaction-opening-rg09",
            "version_number": 1, "posting_set_id": set_id,
            "occurred_at": "2026-01-01T00:00:00+08:00",
            "statistics_at": "2026-01-01T00:00:00+08:00",
            "effective_at": "2026-01-01T00:00:00+08:00"}],
        "posting_sets": [{"id": set_id, "posting_ids": [item["id"] for item in postings]}],
        "postings": postings, "sources": [], "candidates": [], "confirmations": [],
        "evidence": [], "evidence_links": [], "relations": [], "domain_entities": [],
        "audit_links": [], "posting_reconciliations": [], "balances": [],
        "reports": [report()], "derived_statuses": [],
    }
    refresh(result)
    refresh_statuses(result)
    return result


def clone(state: dict, operation_id: str, locator: str, occurrence: str) -> dict:
    result = deepcopy(state)
    result["id"] = state_id(state["root_id"], locator, occurrence)
    result["as_of_operation_id"] = operation_id
    return result


def changes(before: dict, after: dict) -> dict:
    return {
        "entity_changes": golden_v2._expected_entity_changes(before, after),
        "value_changes": {
            "balances": [
                {"key": {"account_id": key[0], "currency": key[1]}, "before": old, "after": new}
                for key, (old, new) in sorted(
                    golden_v2._changes(golden_v2._balance_map(before), golden_v2._balance_map(after)).items()
                )
            ],
            "reports": [
                {"key": {"period_type": key[0], "period": key[1], "metric": key[2],
                         **({"currency": key[3]} if key[3] else {})},
                 "before": old, "after": new}
                for key, (old, new) in sorted(
                    golden_v2._changes(golden_v2._report_map(before), golden_v2._report_map(after)).items()
                )
            ],
            "derived_statuses": [
                {"key": {"kind": key[0], "target_id": key[1], "status_name": key[2]},
                 "before": old, "after": new}
                for key, (old, new) in sorted(
                    golden_v2._changes(golden_v2._status_map(before), golden_v2._status_map(after)).items()
                )
            ],
        },
    }


def op(root_id: str, sequence: int, operation_id: str, action: str, cls: str,
       before: dict, after: dict, *, input_value=None, attempted=None,
       outcome=None, returned=None) -> dict:
    value = {
        "id": operation_id, "root_id": root_id, "sequence": sequence,
        "action_type": action, "operation_class": cls,
        "baseline_state_id": before["id"], "result_state_id": after["id"],
        "outcome": outcome or {"status": "accepted"},
        "status_changes": [], "deltas": changes(before, after),
        "returned_ids": returned or [],
    }
    for status in value["deltas"]["value_changes"]["derived_statuses"]:
        value["status_changes"].append({
            "target_kind": status["key"]["kind"],
            "target_id": status["key"]["target_id"],
            "status_name": status["key"]["status_name"],
            "before": status["before"], "after": status["after"],
        })
    if input_value is not None:
        value["input"] = input_value
    if attempted is not None:
        value["attempted_input"] = attempted
    return value


def rejected(before: dict, after: dict, root_id: str, sequence: int, operation_id: str,
             action: str, attempted: dict, reason: str, field: str) -> dict:
    return op(root_id, sequence, operation_id, action, "rejection", before, after,
              attempted=attempted,
              outcome={"status": "rejected", "reason_code": reason,
                       "field_path": f"$.attempted_input.{field}"})


def replay(current: dict, sequence: int, accepted: dict, operation_id: str,
           locator: str, occurrence: str) -> dict:
    result = clone(current, operation_id, locator, occurrence)
    operation = op(
        accepted["root_id"], sequence, operation_id, accepted["action_type"],
        accepted["operation_class"], current, result,
        input_value=deepcopy(accepted["input"]),
        outcome={"status": "no_change", "reason_code": "idempotent_replay"},
        returned=deepcopy(accepted["returned_ids"]),
    )
    return result, operation


def add_confirmation(state: dict, confirmation_id: str, operation_id: str,
                     confirmation_type: str, subject: dict, confirmed_at: str) -> None:
    state["confirmations"].append({
        "id": confirmation_id, "type": confirmation_type,
        "operation_id": operation_id, "subject": subject,
        "confirmed_at": confirmed_at, "payload": {},
    })


def add_transaction(state: dict, transaction_id: str, transaction_type: str,
                    version_id: str, set_id: str, occurred_at: str, created_at: str,
                    confirmation_id: str | None, postings: list[dict]) -> None:
    state["transactions"].append({
        "id": transaction_id, "type": transaction_type, "current_version_id": version_id,
    })
    version = {
        "id": version_id, "transaction_id": transaction_id, "version_number": 1,
        "posting_set_id": set_id, "occurred_at": occurred_at,
        "statistics_at": occurred_at, "effective_at": occurred_at,
        "created_at": created_at,
    }
    if confirmation_id is not None:
        version["confirmation_id"] = confirmation_id
    state["transaction_versions"].append(version)
    state["posting_sets"].append({"id": set_id, "posting_ids": [item["id"] for item in postings]})
    state["postings"].extend(postings)


def snapshot_confirmations(state: dict, first_operation_id: str) -> None:
    """Snapshot initial states carry the frozen v1 baseline confirmations.

    The v2 case records exactly the 50 v1 operations, so the pre-chain
    confirmation entities of the v1 baselines (adjustment/transfer/allocation
    confirmations) have no creating operation inside the case.  The v2 schema
    requires every confirmation to reference a same-root operation, so these
    snapshot confirmations reference the root's first recorded operation.
    """
    for confirmation in state["confirmations"]:
        confirmation["operation_id"] = first_operation_id
        if confirmation["subject"].get("kind") == "operation":
            confirmation["subject"]["id"] = first_operation_id


def preview_state(state: dict, root_id: str) -> None:
    source_id = "source-target-observation-rg09"
    candidate_id = "candidate-adjustment-rg09"
    evidence_id = "evidence-target-observation-rg09"
    link_id = "evidence-link-target-observation-rg09"
    observation_id = "observation-target-rg09"
    state["sources"].append({
        "id": source_id, "type": "explicit_balance_observation",
        "payload": {"account_id": "asset-a", "target_amount": "130.00",
                    "currency": "CNY", "target_observed_at": "2026-01-31T23:59:59+08:00"},
    })
    state["candidates"].append({
        "id": candidate_id, "type": "balance_adjustment", "source_ids": [source_id],
        "confidence": "1.00",
        "payload": {"account_id": "asset-a", "replayed_amount": "100.00",
                    "target_amount": "130.00", "delta": "30.00", "currency": "CNY",
                    "effective_at": "2026-01-31T23:59:59+08:00"},
        "status_history": [{"id": mid(root_id, "candidate_status", "$.preview", "1"),
                            "sequence": 1, "status": "pending_confirmation"}],
    })
    state["evidence"].append({
        "id": evidence_id, "type": "user_balance_observation", "source_ids": [source_id],
        "payload": {"observed_at": "2026-01-31T23:59:59+08:00"},
    })
    state["domain_entities"].append({
        "id": observation_id, "type": "target_balance_observation",
        "payload": {"account_id": "asset-a", "target_amount": "130.00",
                    "currency": "CNY", "observed_at": "2026-01-31T23:59:59+08:00",
                    "source_id": source_id},
    })
    state["evidence_links"].append({
        "id": link_id, "evidence_id": evidence_id, "target_kind": "observation",
        "target_id": observation_id, "role": "target_balance_observation",
    })


def confirm_adjustment_state(state: dict, root_id: str) -> None:
    add_confirmation(
        state, "confirmation-adjustment-rg09", state["as_of_operation_id"],
        "candidate_confirmation", {"kind": "candidate", "id": "candidate-adjustment-rg09"},
        "2026-02-01T09:05:00+08:00",
    )
    candidate = next(item for item in state["candidates"] if item["id"] == "candidate-adjustment-rg09")
    candidate["status_history"].append({
        "id": mid(root_id, "candidate_status", "$.confirmation", "2"),
        "sequence": 2, "status": "confirmed",
    })
    add_transaction(
        state, "transaction-adjustment-rg09", "balance_adjustment",
        "version-adjustment-rg09-v1", "posting-set-adjustment-rg09",
        "2026-01-31T23:59:59+08:00", "2026-02-01T09:05:00+08:00",
        "confirmation-adjustment-rg09",
        [
            {"id": "posting-adjustment-a-rg09", "posting_set_id": "posting-set-adjustment-rg09",
             "account_id": "asset-a", "amount": "30.00", "currency": "CNY",
             "role": "balance_adjustment_target", "reconciliation_eligible": False},
            {"id": "posting-adjustment-equity-rg09", "posting_set_id": "posting-set-adjustment-rg09",
             "account_id": "equity-balance-adjustments", "amount": "-30.00", "currency": "CNY",
             "role": "balance_adjustment_counterpart", "reconciliation_eligible": False},
        ],
    )
    state["domain_entities"].append({
        "id": "adjustment-rg09", "type": "balance_adjustment",
        "payload": {"observation_id": "observation-target-rg09", "original_delta": "30.00",
                    "currency": "CNY", "transaction_id": "transaction-adjustment-rg09"},
    })
    state["audit_links"].append({
        "id": "audit-adjustment-transaction-rg09", "type": "adjustment_transaction",
        "from": {"kind": "domain_entity", "id": "adjustment-rg09"},
        "to": {"kind": "transaction", "id": "transaction-adjustment-rg09"}, "payload": {},
    })


def confirm_transfer_state(state: dict, root_id: str, *, second: bool = False,
                           import_path: bool = False,
                           reconciliation_eligible: bool | None = None) -> None:
    suffix = "-remaining" if second else ""
    import_suffix = "-import" if import_path else ""
    confirmation_id = f"confirmation-transfer-rg09{suffix}{import_suffix}"
    if import_path:
        confirmed_at = "2026-02-12T10:00:00+08:00"
    else:
        confirmed_at = "2026-02-11T18:00:00+08:00" if second else "2026-02-10T18:00:00+08:00"
    add_confirmation(
        state, confirmation_id, state["as_of_operation_id"],
        "explicit_operation_confirmation",
        {"kind": "operation", "id": state["as_of_operation_id"]},
        confirmed_at,
    )
    add_transaction(
        state, f"transaction-transfer-rg09{suffix}{import_suffix}", "account_transfer",
        f"version-transfer-rg09{suffix}{import_suffix}-v1",
        f"posting-set-transfer-rg09{suffix}{import_suffix}",
        "2026-01-22T10:00:00+08:00" if second else "2026-01-20T12:00:00+08:00",
        confirmed_at,
        confirmation_id,
        [
            {"id": f"posting-transfer-a-rg09{suffix}{import_suffix}",
             "posting_set_id": f"posting-set-transfer-rg09{suffix}{import_suffix}",
             "account_id": "asset-a", "amount": "20.00" if not second else "10.00",
             "currency": "CNY", "role": "transfer_principal_in",
             "reconciliation_eligible": not import_path
             if reconciliation_eligible is None else reconciliation_eligible},
            {"id": f"posting-transfer-b-rg09{suffix}{import_suffix}",
             "posting_set_id": f"posting-set-transfer-rg09{suffix}{import_suffix}",
             "account_id": "asset-b", "amount": "-20.00" if not second else "-10.00",
             "currency": "CNY", "role": "transfer_principal_out",
             "reconciliation_eligible": not import_path
             if reconciliation_eligible is None else reconciliation_eligible},
        ],
    )
    # The frozen v2 projection creates pending reconciliations only for the
    # first real transfer (v1 second-transfer-confirmation-rg09 declares
    # reconciliation_change_count 0 and no new reconciliation entries).
    if not import_path and not second:
        state["posting_reconciliations"].extend([
            {"id": f"reconciliation-transfer-a-rg09{suffix}",
             "posting_id": f"posting-transfer-a-rg09{suffix}", "status": "pending"},
            {"id": f"reconciliation-transfer-b-rg09{suffix}",
             "posting_id": f"posting-transfer-b-rg09{suffix}", "status": "pending"},
        ])
    # v1 second-transfer-confirmation-rg09 intake creates one source record:
    # source-real-transfer-confirmation-rg09-remaining.
    if second and not import_path:
        state["sources"].append({
            "id": "source-real-transfer-confirmation-rg09-remaining",
            "type": "manual_transaction_confirmation",
            "payload": {"observed_at": "2026-02-11T17:30:00+08:00",
                        "actual_at": "2026-01-22T10:00:00+08:00",
                        "account_id": "asset-a", "counter_account_id": "asset-b",
                        "amount": "10.00", "currency": "CNY",
                        "immutable_payload_digest": "sha256:rg09-transfer-remaining"},
        })


def confirm_explanation_state(state: dict, root_id: str, *, second: bool = False,
                              import_path: bool = False) -> None:
    suffix = "-remaining" if second else ""
    import_suffix = "-import" if import_path else ""
    amount = "10.00" if second else "20.00"
    confirmation_id = f"confirmation-explanation-rg09{suffix}{import_suffix}"
    if import_path:
        confirmed_at = "2026-02-12T10:05:00+08:00"
    else:
        confirmed_at = "2026-02-11T18:05:00+08:00" if second else "2026-02-10T18:05:00+08:00"
    add_confirmation(
        state, confirmation_id, state["as_of_operation_id"],
        "explicit_operation_confirmation",
        {"kind": "operation", "id": state["as_of_operation_id"]},
        confirmed_at,
    )
    add_transaction(
        state, f"transaction-reversal-rg09{suffix}{import_suffix}",
        "balance_adjustment_reversal",
        f"version-reversal-rg09{suffix}{import_suffix}-v1",
        f"posting-set-reversal-rg09{suffix}{import_suffix}",
        "2026-01-31T23:59:59+08:00", confirmed_at,
        confirmation_id,
        [
            {"id": f"posting-reversal-a-rg09{suffix}{import_suffix}",
             "posting_set_id": f"posting-set-reversal-rg09{suffix}{import_suffix}",
             "account_id": "asset-a", "amount": f"-{amount}", "currency": "CNY",
             "role": "balance_adjustment_reversal_target", "reconciliation_eligible": False},
            {"id": f"posting-reversal-equity-rg09{suffix}{import_suffix}",
             "posting_set_id": f"posting-set-reversal-rg09{suffix}{import_suffix}",
             "account_id": "equity-balance-adjustments", "amount": amount, "currency": "CNY",
             "role": "balance_adjustment_reversal_counterpart", "reconciliation_eligible": False},
        ],
    )
    state["domain_entities"].append({
        "id": f"allocation-rg09{suffix}{import_suffix}", "type": "explanation_allocation",
        "payload": {"adjustment_id": "adjustment-rg09",
                    "explanation_transaction_id": f"transaction-transfer-rg09{suffix}{import_suffix}",
                    "reversal_transaction_id": f"transaction-reversal-rg09{suffix}{import_suffix}",
                    "amount": amount, "currency": "CNY",
                    "confirmed_at": confirmed_at},
    })
    allocation_id = f"allocation-rg09{suffix}{import_suffix}"
    state["audit_links"].append({
        "id": f"audit-explanation-transaction-rg09{suffix}", "type": "explanation_transaction",
        "from": {"kind": "domain_entity", "id": allocation_id},
        "to": {"kind": "transaction", "id": f"transaction-transfer-rg09{suffix}{import_suffix}"},
        "payload": {},
    })
    state["audit_links"].append({
        "id": f"audit-allocation-reversal-rg09{suffix}", "type": "allocation_reversal",
        "from": {"kind": "domain_entity", "id": allocation_id},
        "to": {"kind": "transaction", "id": f"transaction-reversal-rg09{suffix}{import_suffix}"},
        "payload": {},
    })
    # v1 adds the adjustment_transaction audit link with the explanation
    # confirmation on the import path and on the second explanation.
    if import_path or second:
        state["audit_links"].append({
            "id": f"audit-adjustment-transaction-rg09{suffix}{import_suffix}",
            "type": "adjustment_transaction",
            "from": {"kind": "domain_entity", "id": "adjustment-rg09"},
            "to": {"kind": "transaction", "id": "transaction-adjustment-rg09"},
            "payload": {},
        })


def receive_import_state(state: dict, root_id: str) -> None:
    source_id = "source-import-transfer-rg09"
    evidence_id = "evidence-import-transfer-rg09"
    candidate_id = "candidate-import-transfer-rg09"
    state["sources"].append({
        "id": source_id, "type": "imported_transfer_candidate",
        "payload": {"observed_at": "2026-02-08T10:00:00+08:00",
                    "actual_at": "2026-01-20T12:00:00+08:00",
                    "account_id": "asset-a", "counter_account_id": "asset-b",
                    "amount": "20.00", "currency": "CNY",
                    "immutable_payload_digest": "sha256:rg09-import"},
    })
    state["evidence"].append({
        "id": evidence_id, "type": "imported_real_transaction_candidate",
        "source_ids": [source_id], "payload": {"observed_at": "2026-02-08T10:00:00+08:00"},
    })
    state["candidates"].append({
        "id": candidate_id, "type": "omitted_real_transaction_and_adjustment_explanation",
        "source_ids": [source_id], "confidence": "0.98",
        "payload": {"proposed_transaction_id": None,
                    "proposed_target_account_id": "asset-a",
                    "proposed_counter_account_id": "asset-b",
                    "proposed_actual_at": "2026-01-20T12:00:00+08:00",
                    "proposed_currency": "CNY", "proposed_allocation_amount": "20.00",
                    "requires_confirmation": ["transaction_id", "target_account_id",
                                              "actual_time", "currency",
                                              "explanation_allocation"]},
        "status_history": [{"id": mid(root_id, "candidate_status", "$.import_pending", "1"),
                            "sequence": 1, "status": "pending_confirmation"}],
    })


def link_evidence_state(state: dict, root_id: str, target_posting_id: str,
                        source_id: str, evidence_id: str, link_id: str,
                        amount: str, observed_at: str) -> None:
    state["sources"].append({
        "id": source_id, "type": "account_statement",
        "payload": {"observed_at": observed_at, "booking_at": "2026-01-20T12:00:00+08:00",
                    "account_id": "asset-a", "amount": amount, "currency": "CNY",
                    "immutable_payload_digest": f"sha256:{source_id}"},
    })
    state["evidence"].append({
        "id": evidence_id, "type": "real_account_posting",
        "source_ids": [source_id], "payload": {"observed_at": observed_at},
    })
    state["evidence_links"].append({
        "id": link_id, "evidence_id": evidence_id, "target_kind": "posting",
        "target_id": target_posting_id, "role": "real_account_posting",
    })
    reconciliation = next(
        item for item in state["posting_reconciliations"]
        if item["posting_id"] == target_posting_id
    )
    reconciliation["status"] = "matched"


def zero_delta_state(state: dict, root_id: str) -> None:
    source_id = "source-zero-target-rg09"
    evidence_id = "evidence-zero-target-rg09"
    link_id = "evidence-link-zero-target-rg09"
    observation_id = "observation-zero-target-rg09"
    state["sources"].append({
        "id": source_id, "type": "explicit_balance_observation",
        "payload": {"account_id": "asset-a", "target_amount": "100.00",
                    "currency": "CNY", "target_observed_at": "2026-01-31T23:59:59+08:00"},
    })
    state["evidence"].append({
        "id": evidence_id, "type": "user_balance_observation", "source_ids": [source_id],
        "payload": {"observed_at": "2026-01-31T23:59:59+08:00"},
    })
    state["domain_entities"].append({
        "id": observation_id, "type": "target_balance_observation",
        "payload": {"account_id": "asset-a", "target_amount": "100.00",
                    "currency": "CNY", "observed_at": "2026-01-31T23:59:59+08:00",
                    "source_id": source_id},
    })
    state["evidence_links"].append({
        "id": link_id, "evidence_id": evidence_id, "target_kind": "observation",
        "target_id": observation_id, "role": "target_balance_observation",
    })


def snapshot_chain_initial(root_id: str, *builders) -> dict:
    """Construct a root initial state that embeds a v1 baseline snapshot."""
    state = empty_state(root_id, None)
    for builder in builders:
        builder(state, root_id)
    refresh(state)
    refresh_statuses(state)
    return state


def preview_input() -> dict:
    return {
        "request_id": "request-preview-rg09", "account_id": "asset-a",
        "target_amount": "130.00", "currency": "CNY",
        "target_observed_at": "2026-01-31T23:59:59+08:00",
        "explicit_confirmation": False,
    }


def confirm_adjustment_input() -> dict:
    return {
        "request_id": "request-confirm-adjustment-rg09",
        "candidate_id": "candidate-adjustment-rg09", "account_id": "asset-a",
        "target_amount": "130.00", "replayed_amount": "100.00", "delta": "30.00",
        "currency": "CNY", "effective_at": "2026-01-31T23:59:59+08:00",
        "explicit_confirmation": True, "confirmed_at": "2026-02-01T09:05:00+08:00",
    }


def confirm_transfer_input() -> dict:
    return {
        "request_id": "request-transfer-rg09",
        "target_account_id": "asset-a", "counter_account_id": "asset-b",
        "amount": "20.00", "currency": "CNY",
        "actual_occurred_at": "2026-01-20T12:00:00+08:00",
        "discovered_at": "2026-02-10T17:30:00+08:00",
        "explicit_confirmation": True,
        "confirmed_at": "2026-02-10T18:00:00+08:00",
    }


def confirm_explanation_input() -> dict:
    return {
        "request_id": "request-confirm-allocation-rg09",
        "adjustment_id": "adjustment-rg09",
        "transaction_id": "transaction-transfer-rg09",
        "target_account_id": "asset-a",
        "actual_occurred_at": "2026-01-20T12:00:00+08:00",
        "real_transaction_amount": "20.00", "currency": "CNY",
        "target_observed_at": "2026-01-31T23:59:59+08:00",
        "allocation_direction": "same_as_original_adjustment",
        "explanation_amount": "20.00",
        "confirms_target_account": True, "confirms_actual_occurred_at": True,
        "confirms_real_transaction_amount": True, "confirms_currency": True,
        "confirms_target_observed_at": True, "confirms_allocation_direction": True,
        "confirms_explanation_amount": True,
        "explicit_confirmation": True,
        "confirmed_at": "2026-02-10T18:05:00+08:00",
    }


def v1_input(v1: dict, *path_parts: str) -> dict:
    node = v1
    for part in path_parts:
        node = node[part]
    return deepcopy(node["input"])


def returned_ids(operation_id: str) -> list[dict]:
    # v1 idempotency.retries[*].expected.returned_stable_ids, projected onto v2
    # reference kinds; retries must return exactly the accepted operation IDs.
    stable: dict[str, list[tuple[str, str]]] = {
        "preview-rg09": [
            ("candidate", "candidate-adjustment-rg09"),
            ("observation", "observation-target-rg09"),
            ("source", "source-target-observation-rg09"),
            ("evidence", "evidence-target-observation-rg09"),
            ("evidence_link", "evidence-link-target-observation-rg09"),
        ],
        "confirm-adjustment-rg09": [
            ("transaction", "transaction-adjustment-rg09"),
            ("transaction_version", "version-adjustment-rg09-v1"),
            ("domain_entity", "adjustment-rg09"),
            ("confirmation", "confirmation-adjustment-rg09"),
        ],
        "transfer-confirmation-rg09": [
            ("transaction", "transaction-transfer-rg09"),
            ("transaction_version", "version-transfer-rg09-v1"),
            ("confirmation", "confirmation-transfer-rg09"),
        ],
        "explanation-confirmation-rg09": [
            ("domain_entity", "allocation-rg09"),
            ("transaction", "transaction-reversal-rg09"),
            ("transaction_version", "version-reversal-rg09-v1"),
            ("confirmation", "confirmation-explanation-rg09"),
        ],
        "second-transfer-confirmation-rg09": [
            ("transaction", "transaction-transfer-rg09-remaining"),
            ("transaction_version", "version-transfer-rg09-remaining-v1"),
            ("confirmation", "confirmation-transfer-rg09-remaining"),
        ],
        "second-explanation-confirmation-rg09": [
            ("domain_entity", "allocation-rg09-remaining"),
            ("transaction", "transaction-reversal-rg09-remaining"),
            ("transaction_version", "version-reversal-rg09-remaining-v1"),
            ("confirmation", "confirmation-explanation-rg09-remaining"),
        ],
        "zero-delta-rg09": [
            ("observation", "observation-zero-target-rg09"),
            ("source", "source-zero-target-rg09"),
            ("evidence", "evidence-zero-target-rg09"),
            ("evidence_link", "evidence-link-zero-target-rg09"),
        ],
        "pending-import-rg09": [
            ("source", "source-import-transfer-rg09"),
            ("evidence", "evidence-import-transfer-rg09"),
            ("candidate", "candidate-import-transfer-rg09"),
        ],
        "import-transfer-confirmation-rg09": [
            ("transaction", "transaction-transfer-rg09-import"),
            ("transaction_version", "version-transfer-rg09-import-v1"),
            ("confirmation", "confirmation-transfer-rg09-import"),
        ],
        "import-explanation-confirmation-rg09": [
            ("domain_entity", "allocation-rg09-import"),
            ("transaction", "transaction-reversal-rg09-import"),
            ("transaction_version", "version-reversal-rg09-import-v1"),
            ("confirmation", "confirmation-explanation-rg09-import"),
        ],
        "link-first_transfer_asset_a-rg09": [
            ("source", "source-transfer-a-rg09"),
            ("evidence", "evidence-transfer-a-rg09"),
            ("evidence_link", "evidence-link-transfer-a-rg09"),
        ],
        "link-first_transfer_asset_b-rg09": [
            ("source", "source-transfer-b-rg09"),
            ("evidence", "evidence-transfer-b-rg09"),
            ("evidence_link", "evidence-link-transfer-b-rg09"),
        ],
        "link-second_transfer_asset_a-rg09": [
            ("source", "source-transfer-a-rg09-remaining"),
            ("evidence", "evidence-transfer-a-rg09-remaining"),
            ("evidence_link", "evidence-link-transfer-a-rg09-remaining"),
        ],
        "link-second_transfer_asset_b-rg09": [
            ("source", "source-transfer-b-rg09-remaining"),
            ("evidence", "evidence-transfer-b-rg09-remaining"),
            ("evidence_link", "evidence-link-transfer-b-rg09-remaining"),
        ],
    }
    return [{"kind": kind, "id": item_id} for kind, item_id in stable[operation_id]]


def build_case() -> dict:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    roots, states, operations = [], [], []

    def root(purpose: str, locator: str, discriminator: str,
             initial: dict) -> tuple[dict, dict]:
        root_id = deterministic_v2_root_id("RG-09", locator, discriminator)
        item = {"id": root_id, "purpose": purpose,
                "initial_state_id": initial["id"], "operation_ids": []}
        roots.append(item)
        states.append(initial)
        return item, initial

    def emit(root_item: dict, current: dict, sequence: int, operation: dict,
             result: dict) -> dict:
        operation["sequence"] = sequence
        operation["root_id"] = root_item["id"]
        operation["baseline_state_id"] = current["id"]
        operation["result_state_id"] = result["id"]
        result["as_of_operation_id"] = operation["id"]
        operations.append(operation)
        root_item["operation_ids"].append(operation["id"])
        states.append(result)
        return result

    def emit_retry(root_item: dict, current: dict, sequence: int,
                   accepted: dict, retry_id: str) -> dict:
        result, retry_operation = replay(current, sequence, accepted, retry_id,
                                         "$.idempotency.retries[*]", retry_id)
        operations.append(retry_operation)
        root_item["operation_ids"].append(retry_operation["id"])
        states.append(result)
        return result

    # ---- Main path root: the six frozen accepted operations and seven retries.
    main_root, current = root("rg09_main_path", "$.main_path", "main",
                              empty_state(deterministic_v2_root_id(
                                  "RG-09", "$.main_path", "main"), None))
    sequence = 0

    def accepted(operation: dict, result: dict) -> dict:
        nonlocal current, sequence
        sequence += 1
        current = emit(main_root, current, sequence, operation, result)
        return operation

    previewed = clone(current, "preview-rg09", "$.main_path.preview", "preview")
    previewed["as_of_operation_id"] = "preview-rg09"
    preview_state(previewed, main_root["id"])
    refresh(previewed)
    refresh_statuses(previewed)
    accepted(op(main_root["id"], 0, "preview-rg09", "preview_target_balance",
                "read", current, previewed, input_value=preview_input(),
                returned=returned_ids("preview-rg09")), previewed)

    adjusted = clone(current, "confirm-adjustment-rg09",
                     "$.main_path.confirmation", "adjustment")
    adjusted["as_of_operation_id"] = "confirm-adjustment-rg09"
    confirm_adjustment_state(adjusted, main_root["id"])
    refresh(adjusted)
    refresh_statuses(adjusted)
    accepted(op(main_root["id"], 0, "confirm-adjustment-rg09",
                "confirm_balance_adjustment", "adjustment", current, adjusted,
                input_value=confirm_adjustment_input(),
                returned=returned_ids("confirm-adjustment-rg09")), adjusted)

    transfer_confirmed = clone(current, "transfer-confirmation-rg09",
                               "$.main_path.transfer_confirmation", "transfer")
    transfer_confirmed["as_of_operation_id"] = "transfer-confirmation-rg09"
    confirm_transfer_state(transfer_confirmed, main_root["id"])
    refresh(transfer_confirmed)
    refresh_statuses(transfer_confirmed)
    accepted(op(main_root["id"], 0, "transfer-confirmation-rg09",
                "confirm_real_transfer", "creation", current, transfer_confirmed,
                input_value=confirm_transfer_input(),
                returned=returned_ids("transfer-confirmation-rg09")),
             transfer_confirmed)

    explained = clone(current, "explanation-confirmation-rg09",
                      "$.main_path.explanation_confirmation", "explanation")
    explained["as_of_operation_id"] = "explanation-confirmation-rg09"
    confirm_explanation_state(explained, main_root["id"])
    refresh(explained)
    refresh_statuses(explained)
    accepted(op(main_root["id"], 0, "explanation-confirmation-rg09",
                "confirm_explanation_allocation", "reversal", current, explained,
                input_value=confirm_explanation_input(),
                returned=returned_ids("explanation-confirmation-rg09")),
             explained)

    second_transfer = clone(current, "second-transfer-confirmation-rg09",
                            "$.main_path.second_transfer_confirmation",
                            "second-transfer")
    second_transfer["as_of_operation_id"] = "second-transfer-confirmation-rg09"
    confirm_transfer_state(second_transfer, main_root["id"], second=True,
                           reconciliation_eligible=False)
    refresh(second_transfer)
    refresh_statuses(second_transfer)
    accepted(op(main_root["id"], 0, "second-transfer-confirmation-rg09",
                "confirm_second_real_transfer", "creation", current,
                second_transfer,
                input_value=v1_input(v1, "main_path", "second_transfer_confirmation"),
                returned=returned_ids("second-transfer-confirmation-rg09")),
             second_transfer)

    second_explained = clone(current, "second-explanation-confirmation-rg09",
                             "$.main_path.second_explanation_confirmation",
                             "second-explanation")
    second_explained["as_of_operation_id"] = "second-explanation-confirmation-rg09"
    confirm_explanation_state(second_explained, main_root["id"], second=True)
    refresh(second_explained)
    refresh_statuses(second_explained)
    accepted(op(main_root["id"], 0, "second-explanation-confirmation-rg09",
                "confirm_second_explanation_allocation", "reversal", current,
                second_explained,
                input_value=v1_input(v1, "main_path", "second_explanation_confirmation"),
                returned=returned_ids("second-explanation-confirmation-rg09")),
             second_explained)

    main_retry_pairs = [
        ("retry-preview-rg09", "preview-rg09"),
        ("retry-confirm-adjustment-rg09", "confirm-adjustment-rg09"),
        ("retry-target-source-rg09", "preview-rg09"),
        ("retry-transfer-rg09", "transfer-confirmation-rg09"),
        ("retry-allocation-rg09", "explanation-confirmation-rg09"),
        ("retry-second-transfer-rg09", "second-transfer-confirmation-rg09"),
        ("retry-second-allocation-rg09", "second-explanation-confirmation-rg09"),
    ]
    accepted_by_id = {operation["id"]: operation for operation in operations}
    for retry_id, accepted_id in main_retry_pairs:
        sequence += 1
        current = emit_retry(main_root, current, sequence,
                             accepted_by_id[accepted_id], retry_id)

    # ---- Zero-delta root: one accepted observation and its retry.
    zero_root, current = root("rg09_zero_delta", "$.zero_delta", "zero",
                              empty_state(deterministic_v2_root_id(
                                  "RG-09", "$.zero_delta", "zero"), None))
    zero_state = clone(current, "zero-delta-rg09", "$.zero_delta", "zero")
    zero_state["as_of_operation_id"] = "zero-delta-rg09"
    zero_delta_state(zero_state, zero_root["id"])
    refresh(zero_state)
    refresh_statuses(zero_state)
    zero_op = op(zero_root["id"], 1, "zero-delta-rg09",
                 "save_zero_delta_observation", "creation", current, zero_state,
                 input_value=v1_input(v1, "zero_delta"),
                 returned=returned_ids("zero-delta-rg09"))
    current = emit(zero_root, current, 1, zero_op, zero_state)
    current = emit_retry(zero_root, current, 2, zero_op, "retry-zero-delta-rg09")

    # ---- Import path root: the v1 baseline is the adjustment-confirmed state.
    import_root_id = deterministic_v2_root_id("RG-09", "$.import_path", "import")
    import_initial = snapshot_chain_initial(import_root_id, preview_state,
                                            confirm_adjustment_state)
    import_root, current = root("rg09_import_path", "$.import_path", "import",
                                import_initial)
    first_import_operation_id = "pending-import-rg09"
    snapshot_confirmations(import_initial, first_import_operation_id)

    pending = clone(current, "pending-import-rg09",
                    "$.import_path.pending", "pending")
    pending["as_of_operation_id"] = "pending-import-rg09"
    receive_import_state(pending, import_root["id"])
    refresh(pending)
    refresh_statuses(pending)
    pending_op = op(import_root["id"], 1, "pending-import-rg09",
                    "receive_import_candidate", "creation", current, pending,
                    input_value=v1_input(v1, "import_path", "pending"),
                    returned=returned_ids("pending-import-rg09"))
    current = emit(import_root, current, 1, pending_op, pending)

    incomplete = [
        ("missing-transaction", 0),
        ("missing-account", 1),
        ("missing-actual-time", 2),
        ("missing-currency", 3),
        ("missing-allocation", 4),
    ]
    for index, (name, v1_index) in enumerate(incomplete, 2):
        node = v1["import_path"]["incomplete_confirmations"][v1_index]
        attempted = deepcopy(node["input"])
        reason = node["expected"]["reason"]
        field = next(
            key for key in attempted
            if key in {"transaction_id", "target_account_id", "actual_at",
                       "currency", "allocation_amount"}
        )
        result = clone(current, name, "$.import_path.incomplete_confirmations[*]", name)
        operation = rejected(current, result, import_root["id"], index, name,
                             "reject_incomplete_import_confirmation",
                             attempted, reason, field)
        current = emit(import_root, current, index, operation, result)

    import_transfer = clone(current, "import-transfer-confirmation-rg09",
                            "$.import_path.transfer_confirmation", "transfer")
    import_transfer["as_of_operation_id"] = "import-transfer-confirmation-rg09"
    confirm_transfer_state(import_transfer, import_root["id"], import_path=True)
    refresh(import_transfer)
    refresh_statuses(import_transfer)
    import_transfer_op = op(import_root["id"], 7, "import-transfer-confirmation-rg09",
                            "confirm_imported_real_transfer", "creation", current,
                            import_transfer,
                            input_value=v1_input(v1, "import_path", "transfer_confirmation"),
                            returned=returned_ids("import-transfer-confirmation-rg09"))
    current = emit(import_root, current, 7, import_transfer_op, import_transfer)

    import_explained = clone(current, "import-explanation-confirmation-rg09",
                             "$.import_path.explanation_confirmation", "explanation")
    import_explained["as_of_operation_id"] = "import-explanation-confirmation-rg09"
    confirm_explanation_state(import_explained, import_root["id"], import_path=True)
    refresh(import_explained)
    refresh_statuses(import_explained)
    import_explanation_op = op(import_root["id"], 8, "import-explanation-confirmation-rg09",
                               "confirm_imported_explanation_allocation", "reversal",
                               current, import_explained,
                               input_value=v1_input(v1, "import_path", "explanation_confirmation"),
                               returned=returned_ids("import-explanation-confirmation-rg09"))
    current = emit(import_root, current, 8, import_explanation_op, import_explained)

    import_retry_pairs = [
        ("retry-import-transfer-confirm-rg09", "import-transfer-confirmation-rg09"),
        ("retry-import-allocation-confirm-rg09", "import-explanation-confirmation-rg09"),
        ("retry-import-source-rg09", "pending-import-rg09"),
    ]
    accepted_by_id = {operation["id"]: operation for operation in operations}
    for index, (retry_id, accepted_id) in enumerate(import_retry_pairs, 9):
        current = emit_retry(import_root, current, index,
                             accepted_by_id[accepted_id], retry_id)

    # ---- Evidence path root: v1 baseline is the fully explained state with all
    # four transfer postings pending reconciliation, then four links and their
    # retries.  The second-transfer reconciliation entries exist only in this
    # snapshot baseline (v1 evidence_path changes them to matched); no RG-09
    # operation creates them under the frozen v2 contract.
    evidence_root_id = deterministic_v2_root_id("RG-09", "$.evidence_path", "evidence")
    evidence_initial = empty_state(evidence_root_id, None)
    preview_state(evidence_initial, evidence_root_id)
    confirm_adjustment_state(evidence_initial, evidence_root_id)
    confirm_transfer_state(evidence_initial, evidence_root_id)
    confirm_explanation_state(evidence_initial, evidence_root_id)
    confirm_transfer_state(evidence_initial, evidence_root_id, second=True,
                           reconciliation_eligible=True)
    confirm_explanation_state(evidence_initial, evidence_root_id, second=True)
    evidence_initial["posting_reconciliations"].extend([
        {"id": "reconciliation-transfer-a-rg09-remaining",
         "posting_id": "posting-transfer-a-rg09-remaining", "status": "pending"},
        {"id": "reconciliation-transfer-b-rg09-remaining",
         "posting_id": "posting-transfer-b-rg09-remaining", "status": "pending"},
    ])
    refresh(evidence_initial)
    refresh_statuses(evidence_initial)
    evidence_root, current = root("rg09_evidence_path", "$.evidence_path",
                                  "evidence", evidence_initial)
    first_evidence_operation_id = "link-first_transfer_asset_a-rg09"
    snapshot_confirmations(evidence_initial, first_evidence_operation_id)

    evidence_links = [
        ("first_transfer_asset_a", "link-first_transfer_asset_a-rg09",
         "posting-transfer-a-rg09", "source-transfer-a-rg09",
         "evidence-transfer-a-rg09", "evidence-link-transfer-a-rg09",
         "20.00", "2026-02-11T09:00:00+08:00"),
        ("first_transfer_asset_b", "link-first_transfer_asset_b-rg09",
         "posting-transfer-b-rg09", "source-transfer-b-rg09",
         "evidence-transfer-b-rg09", "evidence-link-transfer-b-rg09",
         "-20.00", "2026-02-11T09:01:00+08:00"),
        ("second_transfer_asset_a", "link-second_transfer_asset_a-rg09",
         "posting-transfer-a-rg09-remaining", "source-transfer-a-rg09-remaining",
         "evidence-transfer-a-rg09-remaining",
         "evidence-link-transfer-a-rg09-remaining",
         "10.00", "2026-02-12T09:00:00+08:00"),
        ("second_transfer_asset_b", "link-second_transfer_asset_b-rg09",
         "posting-transfer-b-rg09-remaining", "source-transfer-b-rg09-remaining",
         "evidence-transfer-b-rg09-remaining",
         "evidence-link-transfer-b-rg09-remaining",
         "-10.00", "2026-02-12T09:01:00+08:00"),
    ]
    for index, (section, operation_id, posting_id, source_id, evidence_id,
                link_id, amount, observed_at) in enumerate(evidence_links, 1):
        result = clone(current, operation_id, "$.evidence_path." + section, section)
        link_evidence_state(result, evidence_root["id"], posting_id, source_id,
                            evidence_id, link_id, amount, observed_at)
        refresh(result)
        refresh_statuses(result)
        input_value = {
            "source_id": source_id, "evidence_id": evidence_id,
            "target_posting_id": posting_id, "account_id": (
                "asset-a" if section.endswith("_asset_a") else "asset-b"),
            "currency": "CNY", "amount": amount,
            "posting_side": "increase" if amount.startswith("-") is False else "decrease",
            "observed_at": observed_at, "explicit_confirmation": True,
        }
        link_op = op(evidence_root["id"], index, operation_id,
                     "link_real_posting_evidence", "reconciliation", current, result,
                     input_value=input_value,
                     returned=returned_ids(operation_id))
        current = emit(evidence_root, current, index, link_op, result)

    evidence_retry_pairs = [
        ("retry-transfer-a-source-rg09", "link-first_transfer_asset_a-rg09"),
        ("retry-transfer-b-source-rg09", "link-first_transfer_asset_b-rg09"),
        ("retry-transfer-a-remaining-source-rg09", "link-second_transfer_asset_a-rg09"),
        ("retry-transfer-b-remaining-source-rg09", "link-second_transfer_asset_b-rg09"),
    ]
    accepted_by_id = {operation["id"]: operation for operation in operations}
    for index, (retry_id, accepted_id) in enumerate(evidence_retry_pairs, 5):
        current = emit_retry(evidence_root, current, index,
                             accepted_by_id[accepted_id], retry_id)

    # ---- Invalid-input roots: v1 baselines are opening/previewed/adjusted/
    # explained snapshots; the 15 probes are the only operations.
    opening_initial = empty_state(
        deterministic_v2_root_id("RG-09", "$.invalid_inputs[*]", "opening"), None)
    invalid_opening_root, current = root(
        "rg09_invalid_opening", "$.invalid_inputs[*]", "opening", opening_initial)
    opening_probes = [
        ("invalid-target-decimal", 0),
        ("invalid-target-time", 1),
        ("wrong-target-timezone", 2),
        ("unknown-target-account", 3),
        ("unowned-target-account", 4),
        ("nonasset-target-account", 5),
        ("wrong-target-currency", 6),
    ]
    for index, (name, v1_index) in enumerate(opening_probes, 1):
        node = v1["invalid_inputs"][v1_index]
        attempted = deepcopy(node["input"])
        reason = node["expected"]["reason"]
        field = next(key for key in attempted if key in {
            "target_amount", "target_observed_at", "account_id", "currency"})
        result = clone(current, name, "$.invalid_inputs[*]", name)
        operation = rejected(current, result, invalid_opening_root["id"], index,
                             name, "reject_invalid_rg09_input", attempted,
                             reason, field)
        current = emit(invalid_opening_root, current, index, operation, result)

    previewed_root_id = deterministic_v2_root_id("RG-09", "$.invalid_inputs[*]", "previewed")
    previewed_initial = snapshot_chain_initial(previewed_root_id, preview_state)
    invalid_previewed_root, current = root(
        "rg09_invalid_previewed", "$.invalid_inputs[*]", "previewed",
        previewed_initial)
    snapshot_confirmations(previewed_initial, "wrong-adjustment-equity")
    node = v1["invalid_inputs"][7]
    result = clone(current, "wrong-adjustment-equity", "$.invalid_inputs[*]",
                   "wrong-adjustment-equity")
    operation = rejected(current, result, invalid_previewed_root["id"], 1,
                         "wrong-adjustment-equity", "reject_invalid_rg09_input",
                         deepcopy(node["input"]), node["expected"]["reason"],
                         "equity_account_id")
    current = emit(invalid_previewed_root, current, 1, operation, result)

    adjusted_root_id = deterministic_v2_root_id("RG-09", "$.invalid_inputs[*]", "adjusted")
    adjusted_initial = snapshot_chain_initial(adjusted_root_id, preview_state,
                                              confirm_adjustment_state)
    invalid_adjusted_root, current = root(
        "rg09_invalid_adjusted", "$.invalid_inputs[*]", "adjusted",
        adjusted_initial)
    snapshot_confirmations(adjusted_initial, "guessed-link")
    adjusted_probes = [("guessed-link", 13), ("duplicate-conflicting-key", 14)]
    for index, (name, v1_index) in enumerate(adjusted_probes, 1):
        node = v1["invalid_inputs"][v1_index]
        attempted = deepcopy(node["input"])
        reason = node["expected"]["reason"]
        field = "explicit_confirmation" if name == "guessed-link" else "request_id"
        result = clone(current, name, "$.invalid_inputs[*]", name)
        operation = rejected(current, result, invalid_adjusted_root["id"], index,
                             name, "reject_invalid_rg09_input", attempted,
                             reason, field)
        current = emit(invalid_adjusted_root, current, index, operation, result)

    explained_root_id = deterministic_v2_root_id("RG-09", "$.invalid_inputs[*]", "explained")
    explained_initial = snapshot_chain_initial(
        explained_root_id, preview_state, confirm_adjustment_state,
        confirm_transfer_state, confirm_explanation_state)
    invalid_explained_root, current = root(
        "rg09_invalid_explained", "$.invalid_inputs[*]", "explained",
        explained_initial)
    snapshot_confirmations(explained_initial, "wrong-explanation-direction")
    explained_probes = [
        ("wrong-explanation-direction", 8),
        ("wrong-explanation-account", 9),
        ("wrong-explanation-currency", 10),
        ("explanation-after-target", 11),
        ("over-remaining-allocation", 12),
    ]
    for index, (name, v1_index) in enumerate(explained_probes, 1):
        node = v1["invalid_inputs"][v1_index]
        attempted = deepcopy(node["input"])
        reason = node["expected"]["reason"]
        field = next(key for key in attempted if key in {
            "direction", "account_id", "currency", "actual_at", "requested_amount"})
        result = clone(current, name, "$.invalid_inputs[*]", name)
        operation = rejected(current, result, invalid_explained_root["id"], index,
                             name, "reject_invalid_rg09_input", attempted,
                             reason, field)
        current = emit(invalid_explained_root, current, index, operation, result)

    # ---- Stale preview root: v1 baseline is the previewed snapshot.  The v1
    # fingerprints are not schema-representable (sha256Fingerprint requires
    # 64 hex digits), so the attempted input carries synthesized digests.
    stale_root_id = deterministic_v2_root_id("RG-09", "$.stale_preview", "stale")
    stale_initial = snapshot_chain_initial(stale_root_id, preview_state)
    stale_root, current = root("rg09_stale_preview", "$.stale_preview", "stale",
                               stale_initial)
    stale_attempted = {
        "preview_id": "candidate-adjustment-rg09",
        "preview_ledger_fingerprint": "sha256:" + "a" * 64,
        "current_ledger_fingerprint": "sha256:" + "b" * 64,
        "explicit_confirmation": True,
        "preview_changed_at": "2026-02-02T11:00:00+08:00",
    }
    result = clone(current, "stale-preview-rg09", "$.stale_preview", "reject")
    operation = rejected(current, result, stale_root["id"], 1,
                         "stale-preview-rg09", "reject_stale_preview",
                         stale_attempted, "ledger_changed_since_preview",
                         "current_ledger_fingerprint")
    current = emit(stale_root, current, 1, operation, result)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: item["id"])
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {
        "contract": "unifiedledger.golden-case", "contract_version": "2.0.0",
        "case": {"id": "RG-09", "level": "core_required", "rule_version": 1,
                 "approval_status": "approved", "ledger_id": "ledger-a",
                 "timezone": "Asia/Shanghai",
                 "currencies": [{"code": "CNY", "precision": 2}]},
        "roots": roots, "states": states, "operations": operations,
    }


def counts(operation: dict, collection: str) -> tuple[int, int, int]:
    changes_by_id = operation["deltas"]["entity_changes"][collection]
    return (len(changes_by_id["added_ids"]), len(changes_by_id["changed_ids"]),
            len(changes_by_id["removed_ids"]))


def v1_operation_ids() -> list[str]:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    found = []

    def walk(node) -> None:
        if isinstance(node, dict):
            if "id" in node and "operation_context" in node:
                found.append(node["id"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(v1)
    return found


class RG09FiftyOperationTests(unittest.TestCase):
    """The v2 case is exactly the 50 frozen v1 operations, id for id."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.case = build_case()

    def test_plan_is_exactly_the_fifty_frozen_v1_operations(self):
        self.assertEqual(len(V1_OPERATION_PLAN), 50)
        self.assertEqual([entry[1] for entry in V1_OPERATION_PLAN],
                         v1_operation_ids())
        self.assertEqual(len(self.case["operations"]), 50)
        # The v2 case orders operations by (root_id, sequence); the plan keeps
        # the v1 file order.  Ids must match exactly as a set (id for id).
        self.assertEqual({operation["id"] for operation in self.case["operations"]},
                         {entry[1] for entry in V1_OPERATION_PLAN})

    def test_each_v1_operation_keeps_action_type_and_class(self):
        by_id = {operation["id"]: operation for operation in self.case["operations"]}
        for source_path, operation_id, action, cls in V1_OPERATION_PLAN:
            operation = by_id[operation_id]
            self.assertEqual(operation["action_type"], action,
                             f"{operation_id} ({source_path})")
            self.assertEqual(operation["operation_class"], cls,
                             f"{operation_id} ({source_path})")

    def test_schema_and_full_validation(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validate_golden_case_v2(self.case)

    def test_every_accepted_operation_matches_v1_entity_deltas(self):
        for operation in self.case["operations"]:
            if operation["outcome"]["status"] != "accepted":
                continue
            expected = EXPECTED_ACCEPTED_COUNTS[operation["action_type"]]
            for collection, required in expected.items():
                self.assertEqual(counts(operation, collection), required,
                                 f"{operation['id']} {collection}")
            for collection in ("transactions", "transaction_versions",
                               "posting_sets", "postings", "confirmations",
                               "sources", "candidates", "evidence",
                               "evidence_links", "audit_links",
                               "domain_entities", "posting_reconciliations"):
                if collection not in expected:
                    self.assertEqual(counts(operation, collection), (0, 0, 0),
                                     f"{operation['id']} {collection}")

    def test_second_transfer_requires_source_and_no_reconciliations(self):
        operation = next(
            item for item in self.case["operations"]
            if item["id"] == "second-transfer-confirmation-rg09"
        )
        self.assertEqual(counts(operation, "sources"), (1, 0, 0))
        self.assertEqual(counts(operation, "posting_reconciliations"), (0, 0, 0))
        self.assertEqual(counts(operation, "transactions"), (1, 0, 0))
        self.assertEqual(counts(operation, "postings"), (2, 0, 0))
        self.assertEqual(counts(operation, "confirmations"), (1, 0, 0))

    def test_second_explanation_requires_three_audit_links_no_reconciliations(self):
        operation = next(
            item for item in self.case["operations"]
            if item["id"] == "second-explanation-confirmation-rg09"
        )
        self.assertEqual(counts(operation, "audit_links"), (3, 0, 0))
        self.assertEqual(counts(operation, "posting_reconciliations"), (0, 0, 0))
        self.assertEqual(counts(operation, "domain_entities"), (1, 0, 0))
        self.assertEqual(counts(operation, "transactions"), (1, 0, 0))
        link_types = {
            item["type"]
            for state in self.case["states"]
            if state["id"] == operation["result_state_id"]
            for item in state["audit_links"]
            if item["id"] in operation["deltas"]["entity_changes"]["audit_links"]["added_ids"]
        }
        self.assertEqual(link_types, {"adjustment_transaction",
                                      "explanation_transaction",
                                      "allocation_reversal"})

    def test_imported_confirmations_use_exact_v1_input_fields(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.case["operations"]}
        for operation_id, v1_path in (
            ("pending-import-rg09", ("import_path", "pending")),
            ("import-transfer-confirmation-rg09", ("import_path", "transfer_confirmation")),
            ("import-explanation-confirmation-rg09", ("import_path", "explanation_confirmation")),
            ("zero-delta-rg09", ("zero_delta",)),
            ("second-transfer-confirmation-rg09", ("main_path", "second_transfer_confirmation")),
            ("second-explanation-confirmation-rg09", ("main_path", "second_explanation_confirmation")),
            ("link-first_transfer_asset_a-rg09", ("evidence_path", "first_transfer_asset_a")),
            ("link-first_transfer_asset_b-rg09", ("evidence_path", "first_transfer_asset_b")),
            ("link-second_transfer_asset_a-rg09", ("evidence_path", "second_transfer_asset_a")),
            ("link-second_transfer_asset_b-rg09", ("evidence_path", "second_transfer_asset_b")),
        ):
            node = v1
            for part in v1_path:
                node = node[part]
            self.assertEqual(by_id[operation_id]["input"], node["input"],
                             f"{operation_id} must carry the exact v1 input fields")

    def test_imported_candidate_stays_pending_confirmation(self):
        states = {item["id"]: item for item in self.case["states"]}
        explanation_operation = next(
            operation for operation in self.case["operations"]
            if operation["action_type"] == "confirm_imported_explanation_allocation"
            and operation["outcome"]["status"] == "accepted"
        )
        final_import_state = states[explanation_operation["result_state_id"]]
        candidate = next(
            item for item in final_import_state["candidates"]
            if item["id"] == "candidate-import-transfer-rg09"
        )
        self.assertEqual([event["status"] for event in candidate["status_history"]],
                         ["pending_confirmation"])
        for operation in self.case["operations"]:
            if operation["action_type"] in {
                "confirm_imported_real_transfer",
                "confirm_imported_explanation_allocation",
            }:
                self.assertEqual(counts(operation, "candidates"), (0, 0, 0))

    def test_rejections_are_atomic_zero_delta(self):
        states = {item["id"]: item for item in self.case["states"]}
        for operation in self.case["operations"]:
            if operation["outcome"]["status"] != "rejected":
                continue
            baseline = golden_v2._state_payload(states[operation["baseline_state_id"]])
            result = golden_v2._state_payload(states[operation["result_state_id"]])
            self.assertEqual(baseline, result)
            self.assertEqual(operation["returned_ids"], [])
            self.assertTrue(all(
                not values
                for changes_by_id in operation["deltas"]["entity_changes"].values()
                for values in changes_by_id.values()
            ))
            self.assertEqual(
                operation["deltas"]["value_changes"],
                {"balances": [], "reports": [], "derived_statuses": []},
            )

    def test_frozen_retried_input_identities_are_replayed(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        frozen = set(v1["idempotency"]["retried_inputs"])
        no_change_ops = [
            operation for operation in self.case["operations"]
            if operation["outcome"]["status"] == "no_change"
        ]
        self.assertEqual(len(no_change_ops), 15)
        identities = {
            operation["input"].get("request_id", operation["input"].get("source_id"))
            for operation in no_change_ops
        }
        # The v1 preview retry is additionally keyed by the source it created;
        # the v2 contract keys no_change retries by request identity, so that
        # created source is asserted against the retry's result state instead.
        self.assertEqual(identities, frozen - {"source-target-observation-rg09"})
        preview_retry = next(
            operation for operation in no_change_ops
            if operation["input"].get("request_id") == "request-preview-rg09"
        )
        result_state = next(
            item for item in self.case["states"]
            if item["id"] == preview_retry["result_state_id"]
        )
        self.assertIn(
            "source-target-observation-rg09",
            {item["id"] for item in result_state["sources"]},
        )

    def test_link_real_posting_evidence_changes_exactly_one_reconciliation(self):
        for operation in self.case["operations"]:
            if operation["action_type"] != "link_real_posting_evidence":
                continue
            if operation["outcome"]["status"] != "accepted":
                continue
            changes_by_id = operation["deltas"]["entity_changes"]["posting_reconciliations"]
            self.assertEqual(changes_by_id["added_ids"], [])
            self.assertEqual(changes_by_id["removed_ids"], [])
            self.assertEqual(len(changes_by_id["changed_ids"]), 1)
            states = {item["id"]: item for item in self.case["states"]}
            result = states[operation["result_state_id"]]
            reconciliation = next(
                item for item in result["posting_reconciliations"]
                if item["id"] == changes_by_id["changed_ids"][0]
            )
            self.assertEqual(reconciliation["status"], "matched")


class RG09SourceBindingTests(unittest.TestCase):
    """Finding 3: imported confirmations must bind the exact intake source."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.case = build_case()

    def test_correct_imported_sources_are_accepted(self):
        validate_golden_case_v2(self.case)
        imported = next(
            operation for operation in self.case["operations"]
            if operation["id"] == "import-transfer-confirmation-rg09"
        )
        self.assertEqual(imported["input"]["source_id"], "source-import-transfer-rg09")
        states = {item["id"]: item for item in self.case["states"]}
        baseline = states[imported["baseline_state_id"]]
        candidate = next(
            item for item in baseline["candidates"]
            if item["type"] == "omitted_real_transaction_and_adjustment_explanation"
        )
        self.assertEqual(candidate["source_ids"], [imported["input"]["source_id"]])

    def test_mismatched_source_is_rejected_for_imported_transfer(self):
        case = deepcopy(self.case)
        operation = next(
            item for item in case["operations"]
            if item["id"] == "import-transfer-confirmation-rg09"
        )
        operation["input"]["source_id"] = "source-target-observation-rg09"
        with self.assertRaises(GoldenCaseError) as context:
            validate_golden_case_v2(case)
        self.assertIn(".input.source_id", str(context.exception))

    def test_mismatched_source_is_rejected_for_imported_explanation(self):
        case = deepcopy(self.case)
        operation = next(
            item for item in case["operations"]
            if item["id"] == "import-explanation-confirmation-rg09"
        )
        operation["input"]["source_id"] = "source-target-observation-rg09"
        with self.assertRaises(GoldenCaseError) as context:
            validate_golden_case_v2(case)
        self.assertIn(".input.source_id", str(context.exception))

    def test_source_bound_to_a_different_candidate_is_rejected(self):
        # A second import sharing the same intake pattern must be rejected: the
        # confirmation cannot pick one import over another, so the strict
        # binding requires exactly one pending imported candidate.
        root_id = deterministic_v2_root_id("RG-09", "$.import_path.reuse", "reuse")
        initial = snapshot_chain_initial(root_id, preview_state,
                                         confirm_adjustment_state)
        receive_import_state(initial, root_id)
        initial["sources"].append({
            "id": "source-import-other-rg09", "type": "imported_transfer_candidate",
            "payload": {"observed_at": "2026-02-08T11:00:00+08:00",
                        "actual_at": "2026-01-20T12:00:00+08:00",
                        "account_id": "asset-a", "counter_account_id": "asset-b",
                        "amount": "20.00", "currency": "CNY",
                        "immutable_payload_digest": "sha256:rg09-import-other"},
        })
        initial["candidates"].append({
            "id": "candidate-import-other-rg09",
            "type": "omitted_real_transaction_and_adjustment_explanation",
            "source_ids": ["source-import-other-rg09"], "confidence": "0.98",
            "payload": {"proposed_transaction_id": None,
                        "proposed_target_account_id": "asset-a",
                        "proposed_counter_account_id": "asset-b",
                        "proposed_actual_at": "2026-01-20T12:00:00+08:00",
                        "proposed_currency": "CNY", "proposed_allocation_amount": "20.00",
                        "requires_confirmation": ["transaction_id", "target_account_id",
                                                  "actual_time", "currency",
                                                  "explanation_allocation"]},
            "status_history": [{"id": mid(root_id, "candidate_status", "$.import_pending", "1"),
                                "sequence": 1, "status": "pending_confirmation"}],
        })
        refresh(initial)
        refresh_statuses(initial)
        snapshot_confirmations(initial, "import-transfer-confirmation-rg09")
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        transfer_input = v1_input(v1, "import_path", "transfer_confirmation")
        result = clone(initial, "import-transfer-confirmation-rg09",
                       "$.import_path.reuse.transfer", "transfer")
        confirm_transfer_state(result, root_id, import_path=True)
        refresh(result)
        refresh_statuses(result)
        reuse_root = {"id": root_id, "purpose": "rg09_import_source_reuse",
                      "initial_state_id": initial["id"],
                      "operation_ids": ["import-transfer-confirmation-rg09"]}
        operation = op(root_id, 1, "import-transfer-confirmation-rg09",
                       "confirm_imported_real_transfer", "creation", initial, result,
                       input_value=transfer_input,
                       returned=returned_ids("import-transfer-confirmation-rg09"))
        case = {
            "contract": "unifiedledger.golden-case", "contract_version": "2.0.0",
            "case": {"id": "RG-09", "level": "core_required", "rule_version": 1,
                     "approval_status": "approved", "ledger_id": "ledger-a",
                     "timezone": "Asia/Shanghai",
                     "currencies": [{"code": "CNY", "precision": 2}]},
            "roots": [reuse_root], "states": [initial, result],
            "operations": [operation],
        }
        with self.assertRaises(GoldenCaseError) as context:
            validate_golden_case_v2(case)
        self.assertIn(".input.source_id", str(context.exception))

    def test_second_and_evidence_families_do_not_touch_candidates(self):
        for operation in self.case["operations"]:
            if operation["action_type"] in {
                "confirm_second_real_transfer",
                "confirm_second_explanation_allocation",
                "link_real_posting_evidence",
            }:
                self.assertEqual(counts(operation, "candidates"), (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
