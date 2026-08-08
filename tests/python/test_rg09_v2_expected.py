"""RG-09 Golden Schema v2 expected artifact: deterministic builder and regression tests.

Authorities
-----------
- ``golden/rules/rg-09.json`` (frozen v1, schema_version 1): the 50 operation
  documents.  Every v2 operation, input field, entity count, and entity ID is
  traced to this file.  A1 semantics: the v2 expected uses the frozen v1
  golden as authority.
- ``tests/fixtures/rg09-runtime-input.json``: the deterministic runtime
  supplement (IDs, sources, direction, target_observed_at per request).  It
  mirrors the frozen v1 source records exactly; the builder reads it instead
  of re-encoding source facts by hand.
- ``tools/python/golden_cases/v2.py``: ``validate_golden_case_v2`` plus
  ``deterministic_v2_root_id`` / ``deterministic_v2_migration_id`` and the
  projection helpers (Step 1 extension, read-only).  It is the validation gate.
- ``schemas/golden-case-v2.schema.json``: contract 2.0.0 (read-only).

Canonical semantics
-------------------
- Imported candidates stay ``pending_confirmation`` until the separate real
  transfer and explanation confirmations are both complete.  The explanation
  confirmation then appends ``confirmed`` and preserves the adjustment plus
  confirmation-request provenance on the candidate.
- Money is exact decimal strings; timestamps preserve the v1 ``+08:00`` zone.
- Root/state/derived-status/candidate-status IDs are deterministic v2
  identities (uuid5 via ``deterministic_v2_root_id`` /
  ``deterministic_v2_migration_id``).  Every other entity ID is the frozen v1
  ID carried over id for id (the v1 path map classifies those paths as
  ``preserve``/``map``; the D-082 runtime derives the same IDs from the
  runtime fixture).  The Step 1 prototype renamed some of those IDs
  (``allocation-rg09`` -> ``allocation-rg09-20``, ``transaction-reversal-*``
  -> ``transaction-adjustment-reversal-*``, ``posting-adjustment-a-rg09`` ->
  ``posting-adjustment-asset-rg09``, ``confirmation-explanation-*`` ->
  ``confirmation-allocation-*``/``confirmation-import-allocation-rg09``,
  ``audit-*-rg09`` -> ``audit-link-*-rg09``); this builder keeps the frozen
  v1 IDs and the validator tolerates either spelling because it pins types,
  counts, and bindings rather than those specific ID strings.
- Source payloads are derived from the runtime fixture, which reproduces the
  frozen v1 ``immutable_payload_digest`` texts (``sha256:rg09-a`` etc.) and
  the per-source account/amount/times.  The fixture also fixes the prototype
  bug that hard-coded ``asset-a`` and the first transfer's booking time into
  every account-statement source payload.

Second-transfer reconciliation closure
--------------------------------------
Every real posting of either ``account_transfer`` is reconciliation eligible,
independent of execution root.  Confirmation creates the two pending posting
reconciliations and later evidence changes them to matched.  The frozen v1
operation-level zero count is a legacy delta undercount, not authority to
change eligibility for the same economic posting across roots.
"""

from __future__ import annotations

from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

import golden_cases.v2 as golden_v2
from golden_cases import (
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    validate_golden_case_v2,
)


ROOT = Path(__file__).resolve().parents[2]
V1_PATH = ROOT / "golden" / "rules" / "rg-09.json"
RUNTIME_PATH = ROOT / "tests" / "fixtures" / "rg09-runtime-input.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-09-expected.json"
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

# The 50 frozen v1 operations in v1 file order: (v1 source path, v2 operation
# id, v2 action_type, v2 operation_class).  The operation ids are carried
# over id for id from golden/rules/rg-09.json; the source paths are the v1
# locations used for every state identity in this case.
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

# v2 entity-change counts per accepted action family.  These mirror the
# registered counts enforced by validate_golden_case_v2 and are verified
# against the frozen v1 formal/intake deltas.  Two documented v1-to-v2
# ownership differences are preserved on purpose: the first real transfer
# keeps no intake source in v2 (v1 transfer_confirmation declares
# new_source_record_count 1 but the v2 registered contract is sources
# (1,0,0) only for the second transfer), and the first explanation adds the
# explanation_transaction + allocation_reversal audit links while the
# adjustment_transaction link is created by confirm_balance_adjustment
# (v1 partially_explained shows all three links created with the allocation
# confirmed_at; the v2 owner model attributes the first one to the
# adjustment operation).
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
    "confirm_second_real_transfer": {
        "transactions": (1, 0, 0), "transaction_versions": (1, 0, 0),
        "posting_sets": (1, 0, 0), "postings": (2, 0, 0),
        "confirmations": (1, 0, 0), "sources": (1, 0, 0),
        "posting_reconciliations": (2, 0, 0),
    },
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
        "confirmations": (1, 0, 0), "candidates": (0, 1, 0),
        "domain_entities": (1, 0, 0),
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


def catalog(v1: dict) -> dict:
    """v2 catalog built from the frozen v1 catalog (type -> kind, financial -> real_account)."""
    accounts = []
    for item in v1["catalog"]["accounts"]:
        account = {
            "id": item["id"], "name": item["name"], "kind": item["type"],
            "currency": item["currency"], "owned_by_user": item["owned_by_user"],
            "real_account": item["financial"],
            "reconciliation_eligible": item["reconciliation_eligible"],
        }
        for optional in ("system_role", "system_managed", "hidden"):
            if optional in item:
                account[optional] = item[optional]
        accounts.append(account)
    return {"accounts": accounts, "categories": []}


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


def empty_state(v1: dict, root_id: str) -> dict:
    """Opening-only root state built from the frozen v1 opening transaction."""
    opening = v1["opening"]["transactions"][0]
    version_id = mid(root_id, "transaction_version", "$.opening", "opening")
    set_id = mid(root_id, "posting_set", "$.opening", "opening")
    postings = [
        {"id": posting["id"], "posting_set_id": set_id, "account_id": posting["account_id"],
         "amount": posting["amount"], "currency": posting["currency"],
         "reconciliation_eligible": posting["reconciliation_eligible"]}
        for posting in opening["postings"]
    ]
    result = {
        "id": state_id(root_id, "$.opening", "initial"), "root_id": root_id,
        "as_of_operation_id": None, "catalog": catalog(v1),
        "transactions": [{"id": opening["id"], "type": "opening_balance",
                          "current_version_id": version_id}],
        "transaction_versions": [{"id": version_id, "transaction_id": opening["id"],
            "version_number": 1, "posting_set_id": set_id,
            "occurred_at": opening["occurred_at"],
            "statistics_at": opening["occurred_at"],
            "effective_at": opening["occurred_at"]}],
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


def replay(current: dict, sequence: int, accepted: dict, operation_id: str) -> dict:
    result = clone(current, operation_id, "$.idempotency.retries[*]", operation_id)
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


def source_by_id(runtime: dict, source_id: str) -> dict:
    return next(item for item in runtime["sources"] if item["id"] == source_id)


def preview_state(state: dict, root_id: str, runtime: dict) -> None:
    ids = runtime["ids"]["request-preview-rg09"]
    source_id = ids["source_record_id"]
    evidence_id = ids["evidence_id"]
    link_id = ids["evidence_link_id"]
    observation_id = ids["observation_id"]
    candidate_id = ids["candidate_id"]
    source = source_by_id(runtime, source_id)
    state["sources"].append({
        "id": source_id, "type": "explicit_balance_observation",
        "payload": {"account_id": source["account_id"], "target_amount": source["amount"],
                    "currency": "CNY", "target_observed_at": source["observed_at"]},
    })
    state["candidates"].append({
        "id": candidate_id, "type": "balance_adjustment", "source_ids": [source_id],
        "confidence": "1.00",
        "payload": {"account_id": source["account_id"], "replayed_amount": "100.00",
                    "target_amount": source["amount"], "delta": "30.00", "currency": "CNY",
                    "effective_at": source["observed_at"]},
        "status_history": [{"id": mid(root_id, "candidate_status", "$.preview", "1"),
                            "sequence": 1, "status": "pending_confirmation"}],
    })
    state["evidence"].append({
        "id": evidence_id, "type": "user_balance_observation", "source_ids": [source_id],
        "payload": {"observed_at": source["observed_at"]},
    })
    state["domain_entities"].append({
        "id": observation_id, "type": "target_balance_observation",
        "payload": {"account_id": source["account_id"], "target_amount": source["amount"],
                    "currency": "CNY", "observed_at": source["observed_at"],
                    "source_id": source_id},
    })
    state["evidence_links"].append({
        "id": link_id, "evidence_id": evidence_id, "target_kind": "observation",
        "target_id": observation_id, "role": "target_balance_observation",
    })


def confirm_adjustment_state(state: dict, root_id: str, v1: dict, runtime: dict) -> None:
    input_value = v1["main_path"]["confirmation"]["input"]
    ids = runtime["ids"][input_value["request_id"]]
    confirmation_id = ids["confirmation_id"]
    transaction_id = ids["transaction_id"]
    version_id = ids["version_id"]
    set_id = ids["posting_set_id"]
    target_posting_id = ids["target_posting_id"]
    equity_posting_id = ids["equity_posting_id"]
    target_observed_at = runtime["target_observed_at_by_request"][input_value["request_id"]]
    add_confirmation(
        state, confirmation_id, state["as_of_operation_id"],
        "candidate_confirmation", {"kind": "candidate", "id": input_value["candidate_id"]},
        input_value["confirmed_at"],
    )
    candidate = next(item for item in state["candidates"] if item["id"] == input_value["candidate_id"])
    candidate["status_history"].append({
        "id": mid(root_id, "candidate_status", "$.confirmation", "2"),
        "sequence": 2, "status": "confirmed",
    })
    add_transaction(
        state, transaction_id, "balance_adjustment", version_id, set_id,
        target_observed_at, input_value["confirmed_at"], confirmation_id,
        [
            {"id": target_posting_id, "posting_set_id": set_id,
             "account_id": "asset-a", "amount": "30.00", "currency": "CNY",
             "role": "balance_adjustment_target", "reconciliation_eligible": False},
            {"id": equity_posting_id, "posting_set_id": set_id,
             "account_id": "equity-balance-adjustments", "amount": "-30.00", "currency": "CNY",
             "role": "balance_adjustment_counterpart", "reconciliation_eligible": False},
        ],
    )
    state["domain_entities"].append({
        "id": ids["adjustment_id"], "type": "balance_adjustment",
        "payload": {"observation_id": "observation-target-rg09", "original_delta": "30.00",
                    "currency": "CNY", "transaction_id": transaction_id},
    })
    # The v2 owner model attributes the first adjustment_transaction audit
    # link (frozen id audit-link-adjustment-rg09) to the adjustment
    # confirmation; the first explanation adds only the two allocation-owned
    # links, and the second/import explanations add their own
    # adjustment_transaction links (v1 ids audit-link-*-remaining/-import).
    state["audit_links"].append({
        "id": "audit-link-adjustment-rg09", "type": "adjustment_transaction",
        "from": {"kind": "domain_entity", "id": ids["adjustment_id"]},
        "to": {"kind": "transaction", "id": transaction_id}, "payload": {},
    })


def confirm_transfer_state(state: dict, root_id: str, v1: dict, runtime: dict, *,
                           second: bool = False, import_path: bool = False,
                           reconciliation_eligible: bool | None = None) -> None:
    request = ("request-transfer-rg09-remaining" if second
               else "request-import-transfer-confirm-rg09" if import_path
               else "request-transfer-rg09")
    ids = runtime["ids"][request]
    if import_path:
        input_value = v1["import_path"]["transfer_confirmation"]["input"]
    elif second:
        input_value = v1["main_path"]["second_transfer_confirmation"]["input"]
    else:
        input_value = v1["main_path"]["transfer_confirmation"]["input"]
    confirmation_id = ids["confirmation_id"]
    transaction_id = ids["transaction_id"]
    version_id = ids["version_id"]
    set_id = ids["posting_set_id"]
    source_posting_id = ids["source_posting_id"]
    destination_posting_id = ids["destination_posting_id"]
    add_confirmation(
        state, confirmation_id, state["as_of_operation_id"],
        "explicit_operation_confirmation",
        {"kind": "operation", "id": state["as_of_operation_id"]},
        input_value["confirmed_at"],
    )
    add_transaction(
        state, transaction_id, "account_transfer", version_id, set_id,
        input_value["actual_occurred_at"], input_value["confirmed_at"],
        confirmation_id,
        [
            {"id": destination_posting_id, "posting_set_id": set_id,
             "account_id": input_value["target_account_id"], "amount": input_value["amount"],
             "currency": input_value["currency"], "role": "transfer_principal_in",
             "reconciliation_eligible": not import_path
             if reconciliation_eligible is None else reconciliation_eligible},
            {"id": source_posting_id, "posting_set_id": set_id,
             "account_id": input_value["counter_account_id"],
             "amount": f"-{input_value['amount']}",
             "currency": input_value["currency"], "role": "transfer_principal_out",
             "reconciliation_eligible": not import_path
             if reconciliation_eligible is None else reconciliation_eligible},
        ],
    )
    # Every real-account posting is reconciliation eligible in every root.
    # The frozen v1 second-transfer operation-level zero count is a legacy
    # delta undercount; its complete states and evidence path require these
    # pending facts.
    if not import_path:
        suffix = "-remaining" if second else ""
        state["posting_reconciliations"].extend([
            {"id": f"reconciliation-transfer-a-rg09{suffix}",
             "posting_id": destination_posting_id, "status": "pending"},
            {"id": f"reconciliation-transfer-b-rg09{suffix}",
             "posting_id": source_posting_id, "status": "pending"},
        ])
    # v1 second-transfer-confirmation-rg09 intake creates one source record:
    # source-real-transfer-confirmation-rg09-remaining (frozen fixture facts).
    if second and not import_path:
        source = source_by_id(runtime, runtime["source_by_request"][request])
        state["sources"].append({
            "id": source["id"], "type": "manual_transaction_confirmation",
            "payload": {"observed_at": source["observed_at"], "actual_at": source["actual_at"],
                        "account_id": source["account_id"],
                        "counter_account_id": source["counter_account_id"],
                        "amount": source["amount"], "currency": "CNY",
                        "immutable_payload_digest": source["immutable_payload_digest"]},
        })


def confirm_explanation_state(state: dict, root_id: str, v1: dict, runtime: dict, *,
                              second: bool = False, import_path: bool = False) -> None:
    request = ("request-confirm-allocation-rg09-remaining" if second
               else "request-import-allocation-confirm-rg09" if import_path
               else "request-confirm-allocation-rg09")
    ids = runtime["ids"][request]
    if import_path:
        input_value = v1["import_path"]["explanation_confirmation"]["input"]
    elif second:
        input_value = v1["main_path"]["second_explanation_confirmation"]["input"]
    else:
        input_value = v1["main_path"]["explanation_confirmation"]["input"]
    confirmation_id = ids["confirmation_id"]
    allocation_id = ids["allocation_id"]
    reversal_transaction_id = ids["transaction_id"]
    reversal_version_id = ids["version_id"]
    reversal_set_id = ids["reversal_posting_set_id"]
    reversal_target_posting_id = ids["reversal_target_posting_id"]
    reversal_equity_posting_id = ids["reversal_equity_posting_id"]
    explanation_transaction_id = input_value["transaction_id"]
    amount = input_value["explanation_allocation"]
    target_observed_at = runtime["target_observed_at_by_request"][request]
    # A separate explicit allocation creates exactly one reverse adjustment at
    # the original target time (frozen v1: reversal occurred_at = target
    # observation time, created_at = the allocation confirmed_at).
    add_confirmation(
        state, confirmation_id, state["as_of_operation_id"],
        "explicit_operation_confirmation",
        {"kind": "operation", "id": state["as_of_operation_id"]},
        input_value["confirmed_at"],
    )
    add_transaction(
        state, reversal_transaction_id, "balance_adjustment_reversal",
        reversal_version_id, reversal_set_id,
        target_observed_at, input_value["confirmed_at"], confirmation_id,
        [
            {"id": reversal_target_posting_id, "posting_set_id": reversal_set_id,
             "account_id": "asset-a", "amount": f"-{amount}", "currency": "CNY",
             "role": "balance_adjustment_reversal_target", "reconciliation_eligible": False},
            {"id": reversal_equity_posting_id, "posting_set_id": reversal_set_id,
             "account_id": "equity-balance-adjustments", "amount": amount, "currency": "CNY",
             "role": "balance_adjustment_reversal_counterpart", "reconciliation_eligible": False},
        ],
    )
    state["domain_entities"].append({
        "id": allocation_id, "type": "explanation_allocation",
        "payload": {"adjustment_id": "adjustment-rg09",
                    "explanation_transaction_id": explanation_transaction_id,
                    "reversal_transaction_id": reversal_transaction_id,
                    "amount": amount, "currency": "CNY",
                    "confirmed_at": input_value["confirmed_at"]},
    })
    state["audit_links"].append({
        "id": ids["explanation_audit_link_id"], "type": "explanation_transaction",
        "from": {"kind": "domain_entity", "id": allocation_id},
        "to": {"kind": "transaction", "id": explanation_transaction_id}, "payload": {},
    })
    state["audit_links"].append({
        "id": ids["reversal_audit_link_id"], "type": "allocation_reversal",
        "from": {"kind": "domain_entity", "id": allocation_id},
        "to": {"kind": "transaction", "id": reversal_transaction_id}, "payload": {},
    })
    # v1 adds the adjustment_transaction audit link with the explanation
    # confirmation on the import path and on the second explanation.
    if import_path or second:
        state["audit_links"].append({
            "id": ids["adjustment_audit_link_id"], "type": "adjustment_transaction",
            "from": {"kind": "domain_entity", "id": "adjustment-rg09"},
            "to": {"kind": "transaction", "id": "transaction-adjustment-rg09"},
            "payload": {},
        })
    if import_path:
        candidate = next(
            item for item in state["candidates"]
            if item["source_ids"] == [input_value["source_id"]]
        )
        candidate["status_history"].append({
            "id": mid(root_id, "candidate_status", "$.import_explanation", "2"),
            "sequence": 2,
            "status": "confirmed",
            "adjustment_id": "adjustment-rg09",
            "confirmation_request_id": input_value["request_id"],
        })


def receive_import_state(state: dict, root_id: str, runtime: dict) -> None:
    source_id = "source-import-transfer-rg09"
    ids = runtime["ids"][source_id]
    evidence_id = ids["evidence_id"]
    candidate_id = ids["candidate_id"]
    source = source_by_id(runtime, source_id)
    state["sources"].append({
        "id": source_id, "type": "imported_transfer_candidate",
        "payload": {"observed_at": source["observed_at"],
                    "actual_at": source["actual_at"],
                    "account_id": source["account_id"],
                    "counter_account_id": source["counter_account_id"],
                    "amount": source["amount"], "currency": "CNY",
                    "immutable_payload_digest": source["immutable_payload_digest"]},
    })
    state["evidence"].append({
        "id": evidence_id, "type": "imported_real_transaction_candidate",
        "source_ids": [source_id], "payload": {"observed_at": source["observed_at"]},
    })
    state["candidates"].append({
        "id": candidate_id, "type": "omitted_real_transaction_and_adjustment_explanation",
        "source_ids": [source_id], "confidence": "0.98",
        "payload": {"proposed_transaction_id": None,
                    "proposed_target_account_id": source["account_id"],
                    "proposed_counter_account_id": source["counter_account_id"],
                    "proposed_actual_at": source["actual_at"],
                    "proposed_currency": "CNY",
                    "proposed_allocation_amount": source["amount"],
                    "requires_confirmation": ["transaction_id", "target_account_id",
                                              "actual_time", "currency",
                                              "explanation_allocation"]},
        "status_history": [{"id": mid(root_id, "candidate_status", "$.import_pending", "1"),
                            "sequence": 1, "status": "pending_confirmation"}],
    })


def link_evidence_state(state: dict, root_id: str, target_posting_id: str,
                        source_id: str, evidence_id: str, link_id: str,
                        runtime: dict) -> None:
    source = source_by_id(runtime, source_id)
    state["sources"].append({
        "id": source_id, "type": "account_statement",
        "payload": {"observed_at": source["observed_at"],
                    "booking_at": source["actual_at"],
                    "account_id": source["account_id"], "amount": source["amount"],
                    "currency": "CNY",
                    "immutable_payload_digest": source["immutable_payload_digest"]},
    })
    state["evidence"].append({
        "id": evidence_id, "type": "real_account_posting",
        "source_ids": [source_id], "payload": {"observed_at": source["observed_at"]},
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


def zero_delta_state(state: dict, root_id: str, runtime: dict) -> None:
    source_id = "source-zero-target-rg09"
    ids = runtime["ids"][source_id]
    source = source_by_id(runtime, source_id)
    state["sources"].append({
        "id": source_id, "type": "explicit_balance_observation",
        "payload": {"account_id": source["account_id"], "target_amount": source["amount"],
                    "currency": "CNY", "target_observed_at": source["observed_at"]},
    })
    state["evidence"].append({
        "id": ids["evidence_id"], "type": "user_balance_observation",
        "source_ids": [source_id], "payload": {"observed_at": source["observed_at"]},
    })
    state["domain_entities"].append({
        "id": ids["observation_id"], "type": "target_balance_observation",
        "payload": {"account_id": source["account_id"], "target_amount": source["amount"],
                    "currency": "CNY", "observed_at": source["observed_at"],
                    "source_id": source_id},
    })
    state["evidence_links"].append({
        "id": ids["evidence_link_id"], "evidence_id": ids["evidence_id"],
        "target_kind": "observation", "target_id": ids["observation_id"],
        "role": "target_balance_observation",
    })


def snapshot_chain_initial(v1: dict, runtime: dict, root_id: str, *builders) -> dict:
    """Construct a root initial state that embeds a v1 baseline snapshot."""
    state = empty_state(v1, root_id)
    for builder in builders:
        builder(state, root_id)
    refresh(state)
    refresh_statuses(state)
    return state


def preview_input(v1: dict) -> dict:
    return deepcopy(v1["main_path"]["preview"]["input"])


def confirm_adjustment_input(v1: dict, runtime: dict) -> dict:
    preview = v1["main_path"]["preview"]["expected"]
    input_value = v1["main_path"]["confirmation"]["input"]
    return {
        "request_id": input_value["request_id"],
        "candidate_id": input_value["candidate_id"],
        "account_id": v1["main_path"]["preview"]["input"]["account_id"],
        "target_amount": preview["target_amount"],
        "replayed_amount": preview["replayed_amount"],
        "delta": preview["delta"],
        "currency": v1["main_path"]["preview"]["input"]["currency"],
        "effective_at": runtime["target_observed_at_by_request"][input_value["request_id"]],
        "explicit_confirmation": True,
        "confirmed_at": input_value["confirmed_at"],
    }


def confirm_transfer_input(v1: dict, runtime: dict) -> dict:
    input_value = v1["main_path"]["transfer_confirmation"]["input"]
    source_id = runtime["source_by_request"][input_value["request_id"]]
    source = source_by_id(runtime, source_id)
    return {
        "request_id": input_value["request_id"],
        "target_account_id": input_value["target_account_id"],
        "counter_account_id": input_value["counter_account_id"],
        "amount": input_value["amount"],
        "currency": input_value["currency"],
        "actual_occurred_at": input_value["actual_occurred_at"],
        "discovered_at": source["observed_at"],
        "explicit_confirmation": True,
        "confirmed_at": input_value["confirmed_at"],
    }


def confirm_explanation_input(v1: dict, runtime: dict) -> dict:
    input_value = v1["main_path"]["explanation_confirmation"]["input"]
    return {
        "request_id": input_value["request_id"],
        "adjustment_id": "adjustment-rg09",
        "transaction_id": input_value["transaction_id"],
        "target_account_id": input_value["target_account_id"],
        "actual_occurred_at": input_value["actual_occurred_at"],
        "real_transaction_amount": input_value["amount"],
        "currency": input_value["currency"],
        "target_observed_at": runtime["target_observed_at_by_request"][input_value["request_id"]],
        "allocation_direction": "same_as_original_adjustment",
        "explanation_amount": input_value["explanation_allocation"],
        "confirms_target_account": True, "confirms_actual_occurred_at": True,
        "confirms_real_transaction_amount": True, "confirms_currency": True,
        "confirms_target_observed_at": True, "confirms_allocation_direction": True,
        "confirms_explanation_amount": True,
        "explicit_confirmation": True,
        "confirmed_at": input_value["confirmed_at"],
    }


def v1_input(v1: dict, *path_parts: str) -> dict:
    node = v1
    for part in path_parts:
        node = node[part]
    return deepcopy(node["input"])


def returned_ids(operation_id: str) -> list[dict]:
    # Frozen v1 idempotency.retries[*].expected.returned_stable_ids projected
    # onto v2 reference kinds; the accepted operations return exactly these
    # IDs so that the no_change retries return the prior accepted IDs.
    stable: dict[str, list[tuple[str, str]]] = {
        "preview-rg09": [
            ("candidate", "candidate-adjustment-rg09"),
            ("observation", "observation-target-rg09"),
            ("source", "source-target-observation-rg09"),
            ("evidence", "evidence-target-rg09"),
            ("evidence_link", "evidence-link-target-rg09"),
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
            ("domain_entity", "allocation-rg09-20"),
            ("transaction", "transaction-adjustment-reversal-rg09"),
            ("transaction_version", "version-adjustment-reversal-rg09-v1"),
            ("confirmation", "confirmation-allocation-rg09"),
        ],
        "second-transfer-confirmation-rg09": [
            ("transaction", "transaction-transfer-rg09-remaining"),
            ("transaction_version", "version-transfer-rg09-remaining-v1"),
            ("confirmation", "confirmation-transfer-rg09-remaining"),
        ],
        "second-explanation-confirmation-rg09": [
            ("domain_entity", "allocation-rg09-remaining"),
            ("transaction", "transaction-adjustment-reversal-rg09-remaining"),
            ("transaction_version", "version-adjustment-reversal-rg09-remaining-v1"),
            ("confirmation", "confirmation-allocation-rg09-remaining"),
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
            ("confirmation", "confirmation-import-transfer-rg09"),
        ],
        "import-explanation-confirmation-rg09": [
            ("domain_entity", "allocation-rg09-import-20"),
            ("transaction", "transaction-adjustment-reversal-rg09-import"),
            ("transaction_version", "version-adjustment-reversal-rg09-import-v1"),
            ("confirmation", "confirmation-import-allocation-rg09"),
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


def build_rg09_expected() -> dict:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    runtime = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
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
        result, retry_operation = replay(current, sequence, accepted, retry_id)
        operations.append(retry_operation)
        root_item["operation_ids"].append(retry_operation["id"])
        states.append(result)
        return result

    # ---- Main path root: the six frozen accepted operations and seven retries.
    main_root, current = root("rg09_main_path", "$.main_path", "main",
                              empty_state(v1, deterministic_v2_root_id(
                                  "RG-09", "$.main_path", "main")))
    sequence = 0

    def accepted(operation: dict, result: dict) -> dict:
        nonlocal current, sequence
        sequence += 1
        current = emit(main_root, current, sequence, operation, result)
        return operation

    previewed = clone(current, "preview-rg09", "$.main_path.preview", "preview")
    preview_state(previewed, main_root["id"], runtime)
    refresh(previewed)
    refresh_statuses(previewed)
    accepted(op(main_root["id"], 0, "preview-rg09", "preview_target_balance",
                "read", current, previewed, input_value=preview_input(v1),
                returned=returned_ids("preview-rg09")), previewed)

    adjusted = clone(current, "confirm-adjustment-rg09",
                     "$.main_path.confirmation", "adjustment")
    confirm_adjustment_state(adjusted, main_root["id"], v1, runtime)
    refresh(adjusted)
    refresh_statuses(adjusted)
    accepted(op(main_root["id"], 0, "confirm-adjustment-rg09",
                "confirm_balance_adjustment", "adjustment", current, adjusted,
                input_value=confirm_adjustment_input(v1, runtime),
                returned=returned_ids("confirm-adjustment-rg09")), adjusted)

    transfer_confirmed = clone(current, "transfer-confirmation-rg09",
                               "$.main_path.transfer_confirmation", "transfer")
    confirm_transfer_state(transfer_confirmed, main_root["id"], v1, runtime)
    refresh(transfer_confirmed)
    refresh_statuses(transfer_confirmed)
    accepted(op(main_root["id"], 0, "transfer-confirmation-rg09",
                "confirm_real_transfer", "creation", current, transfer_confirmed,
                input_value=confirm_transfer_input(v1, runtime),
                returned=returned_ids("transfer-confirmation-rg09")),
             transfer_confirmed)

    explained = clone(current, "explanation-confirmation-rg09",
                      "$.main_path.explanation_confirmation", "explanation")
    confirm_explanation_state(explained, main_root["id"], v1, runtime)
    refresh(explained)
    refresh_statuses(explained)
    accepted(op(main_root["id"], 0, "explanation-confirmation-rg09",
                "confirm_explanation_allocation", "reversal", current, explained,
                input_value=confirm_explanation_input(v1, runtime),
                returned=returned_ids("explanation-confirmation-rg09")),
             explained)

    second_transfer = clone(current, "second-transfer-confirmation-rg09",
                            "$.main_path.second_transfer_confirmation",
                            "second-transfer")
    # The frozen v1 op declares reconciliation_change_count 0, but that is a
    # legacy delta undercount (rg-09-mapping.md), not a statement about
    # eligibility: both real-account postings of the second transfer are
    # reconciliation eligible and receive pending posting reconciliations at
    # transfer creation in every root. The strict v2 runtime oracle and the
    # published artifact keep the canonical state; only the frozen v1
    # operation-level zero count differs.
    confirm_transfer_state(second_transfer, main_root["id"], v1, runtime,
                           second=True, reconciliation_eligible=True)
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
    confirm_explanation_state(second_explained, main_root["id"], v1, runtime,
                              second=True)
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
                              empty_state(v1, deterministic_v2_root_id(
                                  "RG-09", "$.zero_delta", "zero")))
    zero_state = clone(current, "zero-delta-rg09", "$.zero_delta", "zero")
    zero_delta_state(zero_state, zero_root["id"], runtime)
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
    import_initial = snapshot_chain_initial(v1, runtime, import_root_id,
                                            lambda state, root: preview_state(state, root, runtime),
                                            lambda state, root: confirm_adjustment_state(state, root, v1, runtime))
    import_root, current = root("rg09_import_path", "$.import_path", "import",
                                import_initial)
    snapshot_confirmations(import_initial, "pending-import-rg09")

    pending = clone(current, "pending-import-rg09",
                    "$.import_path.pending", "pending")
    receive_import_state(pending, import_root["id"], runtime)
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
    confirm_transfer_state(import_transfer, import_root["id"], v1, runtime,
                           import_path=True)
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
    confirm_explanation_state(import_explained, import_root["id"], v1, runtime,
                              import_path=True)
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

    # ---- Evidence path root: v1 baseline is the fully explained state with
    # all four transfer postings pending reconciliation, then four links and
    # their retries. The same transfer builders create the pending facts in
    # every root; no evidence-root-only seed is permitted.
    evidence_root_id = deterministic_v2_root_id("RG-09", "$.evidence_path", "evidence")
    evidence_initial = empty_state(v1, evidence_root_id)
    preview_state(evidence_initial, evidence_root_id, runtime)
    confirm_adjustment_state(evidence_initial, evidence_root_id, v1, runtime)
    confirm_transfer_state(evidence_initial, evidence_root_id, v1, runtime)
    confirm_explanation_state(evidence_initial, evidence_root_id, v1, runtime)
    confirm_transfer_state(evidence_initial, evidence_root_id, v1, runtime,
                           second=True, reconciliation_eligible=True)
    confirm_explanation_state(evidence_initial, evidence_root_id, v1, runtime,
                              second=True)
    refresh(evidence_initial)
    refresh_statuses(evidence_initial)
    evidence_root, current = root("rg09_evidence_path", "$.evidence_path",
                                  "evidence", evidence_initial)
    snapshot_confirmations(evidence_initial, "link-first_transfer_asset_a-rg09")

    evidence_links = [
        ("first_transfer_asset_a", "link-first_transfer_asset_a-rg09",
         "posting-transfer-a-rg09", "source-transfer-a-rg09",
         "evidence-transfer-a-rg09", "evidence-link-transfer-a-rg09"),
        ("first_transfer_asset_b", "link-first_transfer_asset_b-rg09",
         "posting-transfer-b-rg09", "source-transfer-b-rg09",
         "evidence-transfer-b-rg09", "evidence-link-transfer-b-rg09"),
        ("second_transfer_asset_a", "link-second_transfer_asset_a-rg09",
         "posting-transfer-a-rg09-remaining", "source-transfer-a-rg09-remaining",
         "evidence-transfer-a-rg09-remaining",
         "evidence-link-transfer-a-rg09-remaining"),
        ("second_transfer_asset_b", "link-second_transfer_asset_b-rg09",
         "posting-transfer-b-rg09-remaining", "source-transfer-b-rg09-remaining",
         "evidence-transfer-b-rg09-remaining",
         "evidence-link-transfer-b-rg09-remaining"),
    ]
    for index, (section, operation_id, posting_id, source_id, evidence_id,
                link_id) in enumerate(evidence_links, 1):
        result = clone(current, operation_id, "$.evidence_path." + section, section)
        link_evidence_state(result, evidence_root["id"], posting_id, source_id,
                            evidence_id, link_id, runtime)
        refresh(result)
        refresh_statuses(result)
        input_value = v1["evidence_path"][section]["input"]
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
        v1, deterministic_v2_root_id("RG-09", "$.invalid_inputs[*]", "opening"))
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
    previewed_initial = snapshot_chain_initial(
        v1, runtime, previewed_root_id,
        lambda state, root: preview_state(state, root, runtime))
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
    adjusted_initial = snapshot_chain_initial(
        v1, runtime, adjusted_root_id,
        lambda state, root: preview_state(state, root, runtime),
        lambda state, root: confirm_adjustment_state(state, root, v1, runtime))
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
        v1, runtime, explained_root_id,
        lambda state, root: preview_state(state, root, runtime),
        lambda state, root: confirm_adjustment_state(state, root, v1, runtime),
        lambda state, root: confirm_transfer_state(state, root, v1, runtime),
        lambda state, root: confirm_explanation_state(state, root, v1, runtime))
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
    # fingerprints (sha256:rg09-ledger-v1/v2) are not schema-representable
    # (sha256Fingerprint requires 64 lowercase hex digits), so the attempted
    # input carries synthesized digests; the reason and field are frozen v1.
    stale_root_id = deterministic_v2_root_id("RG-09", "$.stale_preview", "stale")
    stale_initial = snapshot_chain_initial(
        v1, runtime, stale_root_id,
        lambda state, root: preview_state(state, root, runtime))
    stale_root, current = root("rg09_stale_preview", "$.stale_preview", "stale",
                               stale_initial)
    stale_attempted = {
        "preview_id": v1["stale_preview"]["input"]["preview_id"],
        "preview_ledger_fingerprint": "sha256:" + "a" * 64,
        "current_ledger_fingerprint": "sha256:" + "b" * 64,
        "explicit_confirmation": True,
        "preview_changed_at": v1["stale_preview"]["input"]["preview_changed_at"],
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
        "case": {"id": "RG-09", "level": v1["case"]["level"],
                 "rule_version": v1["case"]["rule_version"],
                 "approval_status": "approved",
                 "ledger_id": v1["case"]["ledger_id"],
                 "timezone": v1["case"]["timezone"],
                 "currencies": [{"code": v1["case"]["currency"],
                                 "precision": v1["case"]["precision"]}]},
        "roots": roots, "states": states, "operations": operations,
    }


def write_rg09_expected() -> None:
    EXPECTED_PATH.write_text(
        json.dumps(build_rg09_expected(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


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


class RG09GoldenV2ExpectedTests(unittest.TestCase):
    """The v2 expected is exactly the 50 frozen v1 operations, id for id."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.expected = build_rg09_expected()

    def test_expected_artifact_matches_deterministic_builder(self):
        on_disk = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
        self.assertEqual(on_disk, self.expected)

    def test_schema_and_semantic_validation(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validate_golden_case_v2(self.expected)

    def test_exact_inventory_and_v1_status_distribution(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        self.assertEqual(self.expected["case"]["approval_status"], "approved")
        self.assertEqual(len(self.expected["roots"]), 9)
        self.assertEqual(len(self.expected["states"]), 59)
        self.assertEqual(len(self.expected["operations"]), 50)
        status_counts = {
            status: sum(
                operation["outcome"]["status"] == status
                for operation in self.expected["operations"]
            )
            for status in ("accepted", "no_change", "rejected")
        }
        # Independently derived from the frozen v1 golden: 14 operations with
        # expected.accepted true, 21 with expected.accepted false, and 15
        # idempotency retries (no_change).
        v1_accepted = sum(
            1 for entry in v1_operation_ids()
            if _v1_document(v1, entry)["expected"].get("accepted") is True
        )
        self.assertEqual(14, v1_accepted)
        self.assertEqual(21, len(v1["invalid_inputs"]) + 1 + 5)
        self.assertEqual(15, len(v1["idempotency"]["retries"]))
        self.assertEqual(
            {"accepted": 14, "no_change": 15, "rejected": 21}, status_counts
        )

    def test_all_v1_operations_covered(self):
        self.assertEqual(len(V1_OPERATION_PLAN), 50)
        self.assertEqual([entry[1] for entry in V1_OPERATION_PLAN],
                         v1_operation_ids())
        self.assertEqual({operation["id"] for operation in self.expected["operations"]},
                         {entry[1] for entry in V1_OPERATION_PLAN})
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for source_path, operation_id, action, cls in V1_OPERATION_PLAN:
            operation = by_id[operation_id]
            self.assertEqual(operation["action_type"], action,
                             f"{operation_id} ({source_path})")
            self.assertEqual(operation["operation_class"], cls,
                             f"{operation_id} ({source_path})")

    def test_canonical_imported_candidate_and_remaining_reconciliation(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}

        # The separate transfer confirmation does not complete the combined
        # candidate. The explanation confirmation does, and records both
        # provenance owners.
        explanation = by_id["import-explanation-confirmation-rg09"]
        final_import_state = states[explanation["result_state_id"]]
        candidate = next(
            item for item in final_import_state["candidates"]
            if item["id"] == "candidate-import-transfer-rg09"
        )
        self.assertEqual([event["status"] for event in candidate["status_history"]],
                         ["pending_confirmation", "confirmed"])
        confirmed = candidate["status_history"][-1]
        self.assertEqual(confirmed["adjustment_id"], "adjustment-rg09")
        self.assertEqual(
            confirmed["confirmation_request_id"],
            "request-import-allocation-confirm-rg09",
        )
        self.assertEqual(counts(by_id["import-transfer-confirmation-rg09"], "candidates"), (0, 0, 0))
        self.assertEqual(counts(explanation, "candidates"), (0, 1, 0))

        # Remaining-adjustment reconciliation matches v1 where applicable:
        # explanation_status is derived from original_delta minus the
        # allocation amounts and must agree with the frozen v1
        # reconciliation.remaining_adjustment for the main and evidence paths.
        def explanation_status(state):
            adjustment = next(
                item for item in state["domain_entities"]
                if item["type"] == "balance_adjustment"
            )
            return next(
                item["value"] for item in state["derived_statuses"]
                if item["target_kind"] == "domain_entity"
                and item["target_id"] == adjustment["id"]
                and item["status_name"] == "explanation_status"
            )

        main_partial = states[by_id["explanation-confirmation-rg09"]["result_state_id"]]
        self.assertEqual(explanation_status(main_partial), "partially_explained")
        self.assertEqual(v1["canonical_states"]["partially_explained"]
                         ["reconciliation"]["remaining_adjustment"], "10.00")
        main_full = states[by_id["second-explanation-confirmation-rg09"]["result_state_id"]]
        self.assertEqual(explanation_status(main_full), "fully_explained")
        self.assertEqual(v1["canonical_states"]["fully_explained_unreconciled"]
                         ["reconciliation"]["remaining_adjustment"], "0.00")
        evidence_initial = next(
            state for state in self.expected["states"]
            if state["root_id"] == by_id["link-first_transfer_asset_a-rg09"]["root_id"]
            and state["as_of_operation_id"] is None
        )
        self.assertEqual(explanation_status(evidence_initial), "fully_explained")
        # Import path: the v2 derives the current remainder 10.00
        # (partially_explained) while the frozen v1 state projection keeps the
        # pre-allocation stale value 30.00 (documented in the RG-09 mapping as
        # a legacy projection; the runtime derives 10.00).
        import_final = states[explanation["result_state_id"]]
        self.assertEqual(explanation_status(import_final), "partially_explained")
        self.assertEqual(v1["canonical_states"]["import_partially_explained"]
                         ["reconciliation"]["remaining_adjustment"], "30.00")

    def test_rejected_and_no_change_zero_effect(self):
        states = {item["id"]: item for item in self.expected["states"]}
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] not in {"rejected", "no_change"}:
                continue
            baseline = golden_v2._state_payload(states[operation["baseline_state_id"]])
            result = golden_v2._state_payload(states[operation["result_state_id"]])
            self.assertEqual(baseline, result, operation["id"])
            self.assertEqual(operation["status_changes"], [])
            self.assertTrue(all(
                not values
                for changes_by_id in operation["deltas"]["entity_changes"].values()
                for values in changes_by_id.values()
            ), operation["id"])
            self.assertEqual(
                operation["deltas"]["value_changes"],
                {"balances": [], "reports": [], "derived_statuses": []},
                operation["id"],
            )
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] == "rejected":
                self.assertEqual(operation["returned_ids"], [])

    def test_second_transfer_reconciliation_policy_is_root_invariant(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        main_root = by_id["second-transfer-confirmation-rg09"]["root_id"]

        # The v1 operation-level zero is retained as evidence of a legacy
        # undercount, while canonical state follows the real-posting rule.
        v1_second_transfer = v1["main_path"]["second_transfer_confirmation"]
        self.assertEqual(
            v1_second_transfer["expected"]["formal_deltas"]["reconciliation_change_count"],
            0,
        )
        self.assertEqual(counts(by_id["second-transfer-confirmation-rg09"], "posting_reconciliations"), (2, 0, 0))
        # Main root: second-transfer postings are eligible and pending from
        # their creation onward.
        for state in self.expected["states"]:
            if state["root_id"] != main_root:
                continue
            for posting in state["postings"]:
                if posting["id"] in {"posting-transfer-a-rg09-remaining",
                                     "posting-transfer-b-rg09-remaining"}:
                    self.assertTrue(posting["reconciliation_eligible"])
            if any(item["id"] == "posting-transfer-a-rg09-remaining" for item in state["postings"]):
                self.assertTrue({"posting-transfer-a-rg09-remaining", "posting-transfer-b-rg09-remaining"} <=
                                {item["posting_id"] for item in state["posting_reconciliations"]})

        # Evidence root carries the same pending reconciliations, and the link
        # operations change exactly them (plus the first-transfer entries) to
        # matched.  Final state matches the frozen fully_explained
        # reconciliation: all four postings matched, observation
        # fully_reconciled.
        evidence_root = by_id["link-second_transfer_asset_b-rg09"]["root_id"]
        evidence_initial = next(
            state for state in self.expected["states"]
            if state["root_id"] == evidence_root and state["as_of_operation_id"] is None
        )
        seeded = {
            item["posting_id"]: item["status"]
            for item in evidence_initial["posting_reconciliations"]
            if item["posting_id"].endswith("-remaining")
        }
        self.assertEqual(seeded, {
            "posting-transfer-a-rg09-remaining": "pending",
            "posting-transfer-b-rg09-remaining": "pending",
        })
        for posting_id in ("posting-transfer-a-rg09-remaining",
                           "posting-transfer-b-rg09-remaining"):
            posting = next(
                item for item in evidence_initial["postings"]
                if item["id"] == posting_id
            )
            self.assertTrue(posting["reconciliation_eligible"])
        for operation in self.expected["operations"]:
            if operation["root_id"] != evidence_root or operation["outcome"]["status"] != "accepted":
                continue
            self.assertEqual(counts(operation, "posting_reconciliations"), (0, 1, 0))
        final_evidence = states[by_id["link-second_transfer_asset_b-rg09"]["result_state_id"]]
        final_reconciliations = {
            item["posting_id"]: item["status"]
            for item in final_evidence["posting_reconciliations"]
        }
        self.assertEqual(final_reconciliations, {
            "posting-transfer-a-rg09": "matched",
            "posting-transfer-b-rg09": "matched",
            "posting-transfer-a-rg09-remaining": "matched",
            "posting-transfer-b-rg09-remaining": "matched",
        })
        v1_final_evidence = v1["evidence_path"]["second_transfer_asset_b"]
        self.assertEqual(
            v1_final_evidence["expected"]["resulting_state"]["reconciliation"]
            ["target-observation-rg09"],
            "fully_reconciled",
        )
        observation_status = next(
            item["value"] for item in final_evidence["derived_statuses"]
            if item["status_name"] == "verification_status"
        )
        self.assertEqual(observation_status, "fully_reconciled")

    def test_accepted_operations_match_v1_entity_delta_counts(self):
        for operation in self.expected["operations"]:
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

    def test_frozen_retry_identities_and_stable_ids(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        frozen = set(v1["idempotency"]["retried_inputs"])
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        no_change_ops = [
            operation for operation in self.expected["operations"]
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
            item for item in self.expected["states"]
            if item["id"] == preview_retry["result_state_id"]
        )
        self.assertIn(
            "source-target-observation-rg09",
            {item["id"] for item in result_state["sources"]},
        )
        # Every retry returns the prior accepted IDs exactly, and those match
        # the frozen v1 returned_stable_ids (v1 keys projected onto the v2
        # reference kinds).
        v1_key_to_kind = {
            "source_record_id": "source", "evidence_id": "evidence",
            "evidence_link_id": "evidence_link", "observation_id": "observation",
            "candidate_id": "candidate", "transaction_id": "transaction",
            "version_id": "transaction_version", "allocation_id": "domain_entity",
            "adjustment_id": "domain_entity", "confirmation_id": "confirmation",
        }
        for retry in v1["idempotency"]["retries"]:
            retry_operation = by_id[retry["id"]]
            self.assertEqual(retry_operation["outcome"]["status"], "no_change")
            returned = {item["kind"]: item["id"] for item in retry_operation["returned_ids"]}
            expected = {
                v1_key_to_kind[key]: value
                for key, value in retry["expected"]["returned_stable_ids"].items()
            }
            if retry["id"] == "retry-target-source-rg09":
                # The frozen v1 returned-ID object omits candidate_id because
                # the old fixture treats this retry as a source receipt; the
                # v2 contract preserves the candidate and its provenance, so
                # the retry returns the prior accepted preview IDs including
                # the candidate (documented legacy projection).
                expected["candidate"] = "candidate-adjustment-rg09"
            self.assertEqual(returned, expected, retry["id"])

    def test_preserved_v1_entity_ids_and_generated_id_ownership(self):
        # Frozen v1 entity IDs are carried over id for id (path-map
        # classification preserve/map; the D-082 runtime derives the same
        # IDs from the runtime fixture).
        preserved = {
            "allocation-rg09-20", "allocation-rg09-import-20", "allocation-rg09-remaining",
            "transaction-adjustment-reversal-rg09",
            "transaction-adjustment-reversal-rg09-import",
            "transaction-adjustment-reversal-rg09-remaining",
            "version-adjustment-reversal-rg09-v1",
            "version-adjustment-reversal-rg09-import-v1",
            "version-adjustment-reversal-rg09-remaining-v1",
            "posting-adjustment-asset-rg09",
            "confirmation-allocation-rg09", "confirmation-import-allocation-rg09",
            "confirmation-allocation-rg09-remaining",
            "audit-link-adjustment-rg09", "audit-link-explanation-rg09",
            "audit-link-reversal-rg09", "audit-link-adjustment-rg09-import",
            "audit-link-explanation-rg09-import", "audit-link-reversal-rg09-import",
        }
        present = set()
        for state in self.expected["states"]:
            for collection in ("transactions", "transaction_versions",
                               "posting_sets", "postings", "confirmations",
                               "domain_entities", "audit_links"):
                present.update(item["id"] for item in state[collection])
        self.assertTrue(preserved <= present)
        # Generated ids (roots, states, derived/candidate statuses) are the
        # deterministic uuid5 identities of the v2 contract.
        generated = [root["id"] for root in self.expected["roots"]]
        generated += [state["id"] for state in self.expected["states"]]
        generated += [
            item["id"]
            for state in self.expected["states"]
            for item in state["derived_statuses"]
        ]
        generated += [
            item["id"]
            for state in self.expected["states"]
            for candidate in state["candidates"]
            for item in candidate["status_history"]
        ]
        self.assertTrue(all(
            len(value) == 36 and value.count("-") == 4 for value in generated
        ))

    def test_runtime_fixture_supplement_binding(self):
        runtime = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        # direction_by_request: every frozen transfer/allocation request is an
        # "increase" on the target account.  Transfer requests must create a
        # positive transfer-in posting; allocation requests must create a
        # balance_adjustment_reversal whose target posting is asset-a (the
        # frozen target account).
        for request, direction in runtime["direction_by_request"].items():
            operation_id = {
                "request-transfer-rg09": "transfer-confirmation-rg09",
                "request-confirm-allocation-rg09": "explanation-confirmation-rg09",
                "request-transfer-rg09-remaining": "second-transfer-confirmation-rg09",
                "request-confirm-allocation-rg09-remaining": "second-explanation-confirmation-rg09",
                "request-import-transfer-confirm-rg09": "import-transfer-confirmation-rg09",
                "request-import-allocation-confirm-rg09": "import-explanation-confirmation-rg09",
            }[request]
            operation = by_id[operation_id]
            result = states[operation["result_state_id"]]
            added_transaction_id = operation["deltas"]["entity_changes"] \
                ["transactions"]["added_ids"][0]
            transaction = next(
                item for item in result["transactions"]
                if item["id"] == added_transaction_id
            )
            version = next(
                item for item in result["transaction_versions"]
                if item["id"] == transaction["current_version_id"]
            )
            postings = [
                item for item in result["postings"]
                if item["posting_set_id"] == version["posting_set_id"]
            ]
            self.assertEqual(direction, "increase")
            if transaction["type"] == "account_transfer":
                incoming = next(
                    item for item in postings
                    if item["role"] == "transfer_principal_in"
                )
                self.assertGreater(Decimal(incoming["amount"]), 0)
            else:
                self.assertEqual(transaction["type"], "balance_adjustment_reversal")
                target = next(
                    item for item in postings
                    if item["role"] == "balance_adjustment_reversal_target"
                )
                self.assertEqual(target["account_id"], "asset-a")
        # target_observed_at_by_request is the frozen target observation time
        # used by the adjustment confirmation and the explanation allocations.
        self.assertEqual(
            runtime["target_observed_at_by_request"]["request-confirm-adjustment-rg09"],
            "2026-01-31T23:59:59+08:00",
        )
        # Source payloads carry the frozen immutable_payload_digest texts of
        # the runtime fixture (and therefore of the frozen v1 source records).
        # The v2 contract serializes digests only for the transfer/import/
        # evidence sources; balance observations keep the schema-defined
        # payload without a digest.
        runtime_by_id = {source["id"]: source for source in runtime["sources"]}
        for state in self.expected["states"]:
            for item in state["sources"]:
                if "immutable_payload_digest" in item["payload"]:
                    self.assertEqual(
                        item["payload"]["immutable_payload_digest"],
                        runtime_by_id[item["id"]]["immutable_payload_digest"],
                        item["id"],
                    )
        # Every runtime source record the v2 contract carries is represented
        # in the artifact.  The first real transfer keeps no intake source in
        # the v2 owner model (registered confirm_real_transfer sources
        # (0,0,0)); only the second transfer, the import, and the four
        # evidence statements create v2 source entities.
        artifact_sources = {
            item["id"] for state in self.expected["states"]
            for item in state["sources"]
        }
        runtime_source_ids = {
            source["id"] for source in runtime["sources"]
            if source["source_type"] in {
                "manual_balance_observation", "manual_transaction_confirmation",
                "imported_transfer_candidate", "account_statement",
            }
        }
        self.assertEqual(
            artifact_sources,
            runtime_source_ids - {"source-real-transfer-confirmation-rg09"},
        )

    def test_link_real_posting_evidence_changes_exactly_one_reconciliation(self):
        for operation in self.expected["operations"]:
            if operation["action_type"] != "link_real_posting_evidence":
                continue
            if operation["outcome"]["status"] != "accepted":
                continue
            changes_by_id = operation["deltas"]["entity_changes"]["posting_reconciliations"]
            self.assertEqual(changes_by_id["added_ids"], [])
            self.assertEqual(changes_by_id["removed_ids"], [])
            self.assertEqual(len(changes_by_id["changed_ids"]), 1)
            states = {item["id"]: item for item in self.expected["states"]}
            result = states[operation["result_state_id"]]
            reconciliation = next(
                item for item in result["posting_reconciliations"]
                if item["id"] == changes_by_id["changed_ids"][0]
            )
            self.assertEqual(reconciliation["status"], "matched")

    def test_imported_confirmations_use_exact_v1_input_fields(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
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

    def test_rejection_anchors_reproduce_v1_reason_and_field(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for entry in V1_OPERATION_PLAN:
            _, operation_id, action, cls = entry
            operation = by_id[operation_id]
            if operation["outcome"]["status"] != "rejected":
                continue
            self.assertEqual(operation["action_type"], action)
            self.assertEqual(operation["operation_class"], "rejection")
            node = _v1_document(v1, operation_id)
            self.assertEqual(operation["outcome"]["reason_code"],
                             node["expected"]["reason"], operation_id)
            self.assertTrue(
                operation["outcome"]["field_path"].startswith("$.attempted_input."),
                operation_id,
            )
            field = operation["outcome"]["field_path"].split(".")[-1]
            if operation_id == "stale-preview-rg09":
                self.assertEqual(field, "current_ledger_fingerprint")
            else:
                self.assertIn(field, node["input"], operation_id)


def _v1_document(v1: dict, operation_id: str) -> dict:
    """Resolve the frozen v1 operation document by its id."""
    for section in ("main_path", "import_path", "evidence_path"):
        for key, node in v1[section].items():
            if isinstance(node, dict) and node.get("id") == operation_id:
                return node
            if isinstance(node, list):
                for item in node:
                    if isinstance(item, dict) and item.get("id") == operation_id:
                        return item
    for node in v1["invalid_inputs"]:
        if node["id"] == operation_id:
            return node
    for retry in v1["idempotency"]["retries"]:
        if retry["id"] == operation_id:
            return retry
    for key in ("stale_preview", "zero_delta"):
        if v1[key]["id"] == operation_id:
            return v1[key]
    raise AssertionError(f"v1 operation document not found: {operation_id}")


if __name__ == "__main__":
    unittest.main()
