"""RG-10 Golden Schema v2 expected artifact: deterministic builder and regression tests.

Authority chain (RG10-SPEC-002/003/005/007 registered projections)
-------------------------------------------------------------------
- ``golden/rules/rg-10.json`` (frozen v1, schema_version 1): the 44 operation
  documents (main_path 4 / reconciliation_path 2 / import_path 4 / secondary_cases 4 /
  idempotency 10 / invalid_inputs 20).  Every v2 operation, input field, entity id
  and entity count is traced to this file (D-083:1061; the expected is never read
  back into runtime input).
- ``tests/fixtures/rg10-runtime-input.json``: the deterministic runtime supplement
  (ids, sources, times, categories parent ids, lot facts).  The builder reads it
  instead of re-encoding IDs and source facts by hand.
- ``tools/python/golden_cases/v2.py``: ``validate_golden_case_v2`` plus
  ``deterministic_v2_root_id`` / ``deterministic_v2_migration_id`` and the
  projection helpers.  It is the semantic validation gate.
- ``schemas/golden-case-v2.schema.json`` (contract 2.0.0): the v2 shape gate.
- ``docs/DECISIONS.md`` D-083 (contract closure; 12 accepted / 10 no_change /
  22 rejected; retry equality; identity sharing) and D-086 (publication target
  ``golden/rules-v2/rg-10.json`` <- ``docs/migrations/golden-v2/rg-10-expected.json``,
  comparison "44-operation full comparison").
- Kotlin oracle ``Rg10FullStateOracleTest.kt`` is the behavior authority for
  returned ids, field paths, the evidence-link 3->4 projection, the reconciliation
  synthesis, the multi-lot synthetic input and the classification chain.  Where the
  v2 schema cannot express an oracle-asserted fact, this builder registers the v2
  projection decision here instead of fabricating a runtime fact.

Classification (RG10-SPEC-005): retry source id => no_change; explicit
``accepted: true`` => accepted; ``reason`` present => rejected;
``resulting_state_id`` present => accepted (rename_zero_effect, zero-effect
acceptance); otherwise => rejected.  The 22 rejected cases are the 20
``invalid_inputs`` plus the 2 ``incomplete_confirmations``.

Registered v2 projections and decisions
---------------------------------------
1. Evidence links keep the v2 schema shape ``{id, evidence_id, target_kind,
   target_id, role}``.  The legacy mixed ``stored_value_credit_lot`` link expands
   into ``stored_value_asset_posting`` (legacy id preserved) plus
   ``stored_value_lot_fact`` (id from the runtime inputs ``merchant_lot_link_id``)
   per the mapping "the old mixed stored_value_credit_lot link is never emitted";
   the recharge intake therefore projects 3 -> 4 links (RG10-SPEC-002).  The
   activation fact link target is rewritten from the legacy adjustment transaction
   to the ``activation_adjustment`` domain entity (mapping: "it never targets the
   adjustment transaction"); the v2 link has no status field, so the legacy
   ``pending``/``confirmed_business_fact`` statuses are not projected (RG10-GAP-06).
2. Reconciliation is expressed posting-level in ``posting_reconciliations``
   (pending/matched) plus the transaction-level ``reconciliation_summary`` derived
   status (pending/partial/matched per the v2 status registry; the v1
   complete/pending_financial_evidence tokens map onto matched/pending because the
   v2 derivation has no financial-evidence kind).  Legacy ``not_present``/
   ``not_applicable`` values map to record absence (mapping authority).  The v1
   reconciliation_states ``derived_from``/``transactions_ref``/``lots_ref``/
   ``balances_unchanged``/``reports_unchanged`` shape is not part of the v2 state
   shape; reconciliation states are complete v2 states (the v1 ``balances_unchanged:
   true``/``reports_unchanged: true`` facts are preserved by construction).
3. Intake sources are projected as ``stored_value_source`` entities carrying the
   frozen v1 ``source_type`` values; imported candidates as ``stored_value_candidate``
   (``status_history`` pending_confirmation); evidence with the frozen v1
   ``evidence_type`` values (``merchant_credit_and_lot`` maps onto the v2
   ``merchant_stored_value_credit`` token - registered rename).
4. Confirmations project onto ``explicit_operation_confirmation`` with only
   ``id/type/operation_id/subject/confirmed_at/payload``; the v1 per-role extra
   fields (request_id, role, transaction_id, source_id, evidence_id,
   audit_link_id, explicit_confirmation, confirms_actual_expiry) are not part of
   the v2 shape (the operation_id + subject carry the binding).
5. Lots project as ``stored_value_lot`` with the 4-field payload
   (recharge_transaction_id/loaded_at/face_value/currency); synthetic lots have a
   null recharge_transaction_id.  Remaining face value, paid/bonus composition,
   expiry and merchant facts are frozen v1 authority and are not projected.
6. Consumptions project as ``stored_value_consumption`` and merchant allocations as
   ``merchant_lot_allocation`` domain entities; ``paid_bonus_composition`` is the
   unknown token (the frozen ``forbidden_inference`` rules forbid paid_first /
   bonus_first anywhere).  Confirmed expiry deterministically publishes one
   publication-side ``stored_value_expiry_event`` so the v2 confirmation can target
   the exact event.  This is not a current runtime-owned collection: the runtime
   keeps the transaction target in its snapshot projection.  The reminder is a
   zero-effect acceptance and creates no v2 expiry event.
7. The activation audit link (``explicit_confirmation_provenance``) has no v2
   audit-link type and is not projected; the confirmation subject carries the
   provenance binding.
8. The v2 validator requires exact catalog balance coverage, so every published
   state includes ``asset-stored-value-disabled`` at its ledger-derived amount
   (0.00 in the frozen RG-10 data).  Disabled means RG-10 operations cannot use the
   account; it never means that publication may omit its balance or hide a future
   nonzero ledger-derived amount.  The v1 canonical states also omit the opening
   transaction from their transactions lists while every v2 state carries it (the
   v2 validator replays balances from the complete current postings).  The two
   synthetic-baseline roots (multi-lot, merchant allocation) carry no opening
   transaction, matching the oracle's empty formal baseline.
9. The three reviewed rejection anchors mirror the Kotlin runtime/oracle exactly:
   ``component_sum_mismatch`` identifies ``$.attempted_input.credited_amount``;
   both incomplete-import reasons identify
   ``$.attempted_input.explicit_confirmation``.  The frozen v1 declares no field
   paths, so this explicit mirror prevents the publication artifact, schema, and
   semantic validator from drifting from the behavior authority.
10. Retry operations replay the original accepted input and are chained on the
    root that owns the original (the v2 topology rule "baseline follows the root
    execution path"); their v1-declared baselines name the original's result state.
    The incomplete imports run on fresh roots against their fixture baselines
    (RG10-SPEC-007 identity isolation).
11. The multi-lot case is adapted by the runtime as ``confirm_stored_value_spend``
    with the synthetic input registry (RG10-SPEC-003): ids follow the
    ``*-multi-lot-rg10`` convention, times mirror the main-path spend, and the
    acceptance flags are forced true.  The v2 operation carries that synthesized
    input; its consumption count (one per base lot) is derived from the inline
    base only.
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
V1_PATH = ROOT / "golden" / "rules" / "rg-10.json"
RUNTIME_PATH = ROOT / "tests" / "fixtures" / "rg10-runtime-input.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-10-expected.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
RG10_METRICS = (
    "budget",
    "cash_inflow",
    "cash_outflow",
    "category_effect",
    "consumption",
    "expiry_loss",
    "net_worth_change",
    "ordinary_expense",
    "ordinary_income",
    "special_income",
)

# The 44 frozen v1 operations in v1 order: (v1 source path, v2 operation id,
# v2 action_type, v2 operation_class, expected status).  Operation ids are
# carried over id for id from golden/rules/rg-10.json (raw document id or
# operation_context.operation_id); the retry blocks keep their section names.
V1_OPERATION_PLAN: list[tuple[str, str, str, str, str]] = [
    ("$.main_path.recharge", "request-recharge-rg10", "confirm_stored_value_recharge", "creation", "accepted"),
    ("$.main_path.spend", "request-spend-rg10", "confirm_stored_value_spend", "creation", "accepted"),
    ("$.main_path.expiry_reminder", "request-reminder-rg10", "record_expiry_reminder", "status_transition", "accepted"),
    ("$.main_path.expiry_confirmation", "request-expiry-rg10", "confirm_stored_value_expiry_loss", "creation", "accepted"),
    ("$.reconciliation_path.merchant_evidence", "source-merchant-credit-rg10", "reconcile_merchant_credit", "reconciliation", "accepted"),
    ("$.reconciliation_path.bank_evidence", "source-bank-payment-rg10", "reconcile_bank_payment", "reconciliation", "accepted"),
    ("$.import_path.complete_unconfirmed[*]", "import-recharge-complete-unconfirmed-rg10", "ingest_stored_value_recharge_candidate", "creation", "accepted"),
    ("$.import_path.complete_unconfirmed[*]", "import-spend-complete-unconfirmed-rg10", "ingest_stored_value_spend_candidate", "creation", "accepted"),
    ("$.import_path.incomplete_confirmations[*]", "import-recharge-only-merchant-rg10", "confirm_imported_stored_value_recharge", "rejection", "rejected"),
    ("$.import_path.incomplete_confirmations[*]", "import-spend-without-category-rg10", "confirm_imported_stored_value_spend", "rejection", "rejected"),
    ("$.secondary_cases.multi_lot_allocation", "multi_lot_allocation", "confirm_stored_value_spend", "creation", "accepted"),
    ("$.secondary_cases.merchant_evidenced_allocation", "request-merchant-allocation-rg10", "apply_merchant_lot_allocation", "update", "accepted"),
    ("$.secondary_cases.rename_zero_effect", "rename_zero_effect", "rename_stored_value_labels", "update", "accepted"),
    ("$.secondary_cases.activation_boundary", "request-activation-rg10", "confirm_stored_value_activation_balance", "adjustment", "accepted"),
] + [
    ("$.invalid_inputs[*]", op_id, action, "rejection", "rejected")
    for (op_id, action) in (
        ("float-amount", "confirm_stored_value_recharge"),
        ("numeric-credited-amount", "confirm_stored_value_recharge"),
        ("numeric-bonus-amount", "confirm_stored_value_recharge"),
        ("nonpositive-amount", "confirm_stored_value_recharge"),
        ("nonpositive-credited-amount", "confirm_stored_value_recharge"),
        ("negative-bonus-amount", "confirm_stored_value_recharge"),
        ("credited-less-than-paid", "confirm_stored_value_recharge"),
        ("component-mismatch", "confirm_stored_value_recharge"),
        ("disabled-stored-account", "confirm_stored_value_recharge"),
        ("model-overlap", "confirm_stored_value_recharge"),
        ("unknown-payment-account", "confirm_stored_value_recharge"),
        ("unowned-payment-account", "confirm_stored_value_recharge"),
        ("wrong-payment-account-kind", "confirm_stored_value_recharge"),
        ("wrong-stored-account-kind", "confirm_stored_value_recharge"),
        ("wrong-currency", "confirm_stored_value_recharge"),
        ("spend-over-balance", "confirm_stored_value_spend"),
        ("unknown-category", "confirm_stored_value_spend"),
        ("invalid-lot-allocation", "apply_merchant_lot_allocation"),
        ("unconfirmed-expiry", "confirm_stored_value_expiry_loss"),
        ("guessed-composition", "confirm_stored_value_spend"),
    )
] + [
    ("$.idempotency." + name, name, action, cls, "no_change")
    for (name, action, cls) in (
        ("recharge_retry", "confirm_stored_value_recharge", "creation"),
        ("spend_retry", "confirm_stored_value_spend", "creation"),
        ("expiry_retry", "confirm_stored_value_expiry_loss", "creation"),
        ("reminder_retry", "record_expiry_reminder", "status_transition"),
        ("merchant_reconciliation_retry", "reconcile_merchant_credit", "reconciliation"),
        ("bank_reconciliation_retry", "reconcile_bank_payment", "reconciliation"),
        ("import_recharge_retry", "ingest_stored_value_recharge_candidate", "creation"),
        ("import_spend_retry", "ingest_stored_value_spend_candidate", "creation"),
        ("activation_retry", "confirm_stored_value_activation_balance", "adjustment"),
        ("merchant_allocation_retry", "apply_merchant_lot_allocation", "update"),
    )
]

# Returned ids per accepted operation, mirroring the Kotlin oracle
# (Rg10FullStateOracleTest.expectedReturnedIds) projected onto the v2 ref kinds.
# Rg10ReturnedId.Request projects onto the operation ref (the reminder operation
# id IS its request id).
RETURNED_IDS: dict[str, list[tuple[str, str]]] = {
    "request-recharge-rg10": [
        ("transaction", "transaction-recharge-rg10"),
        ("domain_entity", "lot-rg10-20260110-a"),
    ],
    "request-spend-rg10": [("transaction", "transaction-spend-rg10")],
    "request-reminder-rg10": [("operation", "request-reminder-rg10")],
    "request-expiry-rg10": [("transaction", "transaction-expiry-rg10")],
    "source-merchant-credit-rg10": [
        ("evidence_link", "evidence-link-merchant-recharge-rg10"),
    ],
    "source-bank-payment-rg10": [
        ("evidence_link", "evidence-link-bank-recharge-rg10"),
    ],
    "import-recharge-complete-unconfirmed-rg10": [
        ("candidate", "candidate-import-recharge-rg10"),
    ],
    "import-spend-complete-unconfirmed-rg10": [
        ("candidate", "candidate-import-spend-rg10"),
    ],
    "multi_lot_allocation": [("transaction", "transaction-multi-lot-rg10")],
    "request-merchant-allocation-rg10": [
        ("domain_entity", "allocation-merchant-rg10"),
        ("domain_entity", "consumption-merchant-rg10"),
    ],
    "rename_zero_effect": [],
    "request-activation-rg10": [
        ("transaction", "transaction-activation-adjustment-rg10"),
        ("domain_entity", "adjustment-pre-activation-rg10"),
        ("confirmation", "confirmation-activation-rg10"),
    ],
}

# Frozen v1 evidence_type -> v2 evidence type token (registered rename).
EVIDENCE_TYPE_MAP = {
    "bank_payment": "bank_payment",
    "merchant_credit_and_lot": "merchant_stored_value_credit",
    "confirmed_actual_expiry": "confirmed_actual_expiry",
    "imported_recharge_candidate": "imported_stored_value_recharge_candidate",
    "imported_spend_candidate": "imported_stored_value_spend_candidate",
    "confirmed_activation_balance": "confirmed_activation_balance",
    "merchant_lot_allocation": "merchant_lot_allocation",
}

# v1 canonical state keys in v1 file order.
CANONICAL_STATE_KEYS = (
    "opening",
    "activation_boundary",
    "recharge_confirmed",
    "spend_confirmed",
    "expiry_confirmed",
)


def mid(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id("RG-10", root_id, kind, locator, occurrence)


def state_id(root_id: str, locator: str, occurrence: str) -> str:
    return mid(root_id, "state", locator, occurrence)


def catalog(v1: dict, runtime: dict) -> dict:
    """v2 catalog built from the frozen v1 catalog plus the fixture parent ids."""
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
        if item.get("stored_value") is True:
            account["stored_value"] = {
                "enabled": item["enabled"],
                "merchant_restricted": item["restricted"],
                "merchant_id": item["merchant_id"],
            }
        accounts.append(account)
    categories = []
    for item in v1["catalog"]["categories"]:
        parent_id = runtime["categories"].get(item["id"], {}).get("parent_id")
        categories.append({
            "id": item["id"], "name": item["name"],
            "parent_id": parent_id, "posting_account_id": item["account_id"],
            "active": item["active"],
        })
        if parent_id is not None and not any(
            category["id"] == parent_id for category in categories
        ):
            # GAP-05: the parent identity comes from the runtime fixture, never
            # from the frozen numeric level; the parent is a grouping node.
            categories.insert(0, {
                "id": parent_id, "name": "Grouping",
                "parent_id": None, "posting_account_id": None, "active": True,
            })
    return {"accounts": accounts, "categories": categories}


def report() -> dict:
    return {
        "period_type": "cumulative", "period": "all",
        "metrics": [
            {"metric": metric, "applicability": "applicable", "currency": "CNY", "amount": "0.00"}
            for metric in RG10_METRICS
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


def empty_state(v1: dict, root_id: str, *, include_opening: bool = True) -> dict:
    """Root base state with the v2 catalog and (by default) the opening transaction."""
    result = {
        "id": state_id(root_id, "$.opening", "initial"), "root_id": root_id,
        "as_of_operation_id": None, "catalog": catalog(v1, RUNTIME),
        "transactions": [], "transaction_versions": [], "posting_sets": [],
        "postings": [], "sources": [], "candidates": [], "confirmations": [],
        "evidence": [], "evidence_links": [], "relations": [], "domain_entities": [],
        "audit_links": [], "posting_reconciliations": [], "balances": [],
        "reports": [report()], "derived_statuses": [],
    }
    if include_opening:
        opening = v1["opening"]["transactions"][0]
        version_id = mid(root_id, "transaction_version", "$.opening", "opening")
        set_id = mid(root_id, "posting_set", "$.opening", "opening")
        postings = [
            {"id": posting["id"], "posting_set_id": set_id, "account_id": posting["account_id"],
             "amount": posting["amount"], "currency": posting["currency"],
             "reconciliation_eligible": posting["reconciliation_eligible"]}
            for posting in opening["postings"]
        ]
        result["transactions"] = [{"id": opening["id"], "type": "opening_balance",
                                   "current_version_id": version_id}]
        result["transaction_versions"] = [{"id": version_id, "transaction_id": opening["id"],
            "version_number": 1, "posting_set_id": set_id,
            "occurred_at": opening["occurred_at"],
            "statistics_at": opening["occurred_at"],
            "effective_at": opening["occurred_at"]}]
        result["posting_sets"] = [{"id": set_id, "posting_ids": [item["id"] for item in postings]}]
        result["postings"] = postings
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
    result = clone(current, operation_id, "$.idempotency." + operation_id, operation_id)
    operation = op(
        accepted["root_id"], sequence, operation_id, accepted["action_type"],
        accepted["operation_class"], current, result,
        input_value=deepcopy(accepted["input"]),
        outcome={"status": "no_change", "reason_code": "idempotent_replay"},
        returned=deepcopy(accepted["returned_ids"]),
    )
    return result, operation


def snapshot_confirmations(state: dict, first_operation_id: str) -> None:
    """Pre-chain snapshot confirmations reference the root's first operation."""
    for confirmation in state["confirmations"]:
        confirmation["operation_id"] = first_operation_id
        confirmation["subject"] = {"kind": "operation", "id": first_operation_id}


def _v2_evidence_type(v1_type: str) -> str:
    return EVIDENCE_TYPE_MAP[v1_type]


def _role_for(transaction_type: str, account_id: str, accounts: dict) -> str | None:
    account = accounts[account_id]
    if transaction_type == "opening_balance":
        return None
    if transaction_type == "stored_value_recharge":
        if "stored_value" in account:
            return "stored_value_asset"
        if account["kind"] == "income":
            return "stored_value_bonus_income"
        return "bank_payment"
    if transaction_type == "stored_value_spend":
        return "expense" if account["kind"] == "expense" else "stored_value_asset"
    if transaction_type == "stored_value_expiry_loss":
        if account.get("system_role") == "stored_value_expiry_loss":
            return "stored_value_expiry_loss"
        return "stored_value_asset"
    if transaction_type == "stored_value_pre_activation_balance_adjustment":
        if "stored_value" in account:
            return "stored_value_asset"
        return "stored_value_pre_activation_adjustment"
    raise AssertionError(f"no RG-10 role for {transaction_type}")


def _apply_v1_state(state: dict, v1_state: dict, runtime: dict, *,
                    confirmation_operation_ids: dict[str, str] | None = None,
                    first_operation_id: str | None = None) -> None:
    """Project one frozen v1 state document onto the v2 state collections.

    Frozen v1 states are cumulative supersets (spend_confirmed repeats the
    recharge_confirmed content), so every entity is added only when its id is
    not already present; a state therefore equals the union of the v1 documents
    applied so far plus the opening transaction.
    """
    accounts = {item["id"]: item for item in state["catalog"]["accounts"]}
    existing_transactions = {item["id"] for item in state["transactions"]}
    existing_versions = {item["id"] for item in state["transaction_versions"]}
    existing_sets = {item["id"] for item in state["posting_sets"]}
    existing_postings = {item["id"] for item in state["postings"]}
    existing_sources = {item["id"] for item in state["sources"]}
    existing_candidates = {item["id"] for item in state["candidates"]}
    existing_confirmations = {item["id"] for item in state["confirmations"]}
    existing_evidence = {item["id"] for item in state["evidence"]}
    existing_links = {item["id"] for item in state["evidence_links"]}
    existing_entities = {item["id"] for item in state["domain_entities"]}
    expiry_amount = None
    expiry_occurred_at = None
    for transaction in v1_state.get("transactions", []):
        if transaction["type"] == "stored_value_expiry_loss":
            expiry_amount = transaction["confirmed_expired_amount"]
            expiry_occurred_at = transaction["occurred_at"]

    for transaction in v1_state.get("transactions", []):
        if transaction["id"] in existing_transactions:
            continue
        transaction_type = transaction["type"]
        version = next(
            item for item in v1_state["versions"]
            if item["id"] == transaction["current_version_id"]
        )
        set_id = version["posting_set_id"]
        postings = []
        for posting in transaction["postings"]:
            if posting["id"] in existing_postings:
                continue
            item = {
                "id": posting["id"], "posting_set_id": set_id,
                "account_id": posting["account_id"], "amount": posting["amount"],
                "currency": posting["currency"],
                "reconciliation_eligible": posting["reconciliation_eligible"],
            }
            role = _role_for(transaction_type, posting["account_id"], accounts)
            if role is not None:
                item["role"] = role
            postings.append(item)
        state["transactions"].append({
            "id": transaction["id"], "type": transaction_type,
            "current_version_id": transaction["current_version_id"],
        })
        if version["id"] not in existing_versions:
            state["transaction_versions"].append({
                "id": version["id"], "transaction_id": version["transaction_id"],
                "version_number": version["version_number"],
                "posting_set_id": version["posting_set_id"],
                "occurred_at": transaction["occurred_at"],
                "statistics_at": transaction["statistics_at"],
                "effective_at": transaction["effective_at"],
                "created_at": version["created_at"],
            })
        if set_id not in existing_sets:
            state["posting_sets"].append({"id": set_id, "posting_ids": [item["id"] for item in postings]})
        state["postings"].extend(postings)

    for source in v1_state.get("source_records", []):
        if source["id"] in existing_sources:
            continue
        payload = {
            "source_type": source["source_type"],
            "observed_at": source["observed_at"],
            "immutable_payload_digest": source["immutable_payload_digest"],
        }
        for field in ("account_id", "amount", "currency", "lot_id"):
            if field in source:
                payload[field] = source[field]
        state["sources"].append({"id": source["id"], "type": "stored_value_source", "payload": payload})

    for item in v1_state.get("evidence", []):
        if item["id"] in existing_evidence:
            continue
        state["evidence"].append({
            "id": item["id"], "type": _v2_evidence_type(item["evidence_type"]),
            "source_ids": [item["source_id"]],
            "payload": {"observed_at": item["observed_at"]},
        })

    for link in v1_state.get("evidence_links", []):
        if link["id"] in existing_links:
            continue
        if link["role"] == "stored_value_credit_lot":
            entry = next(
                fields for fields in runtime["ids"].values()
                if fields.get("merchant_source_id") == link["source_id"]
            )
            state["evidence_links"].append({
                "id": link["id"], "evidence_id": link["evidence_id"],
                "target_kind": "posting", "target_id": link["target_id"],
                "role": "stored_value_asset_posting",
            })
            state["evidence_links"].append({
                "id": entry["merchant_lot_link_id"], "evidence_id": link["evidence_id"],
                "target_kind": "domain_entity", "target_id": link["lot_id"],
                "role": "stored_value_lot_fact",
            })
        elif link["role"] == "stored_value_activation_balance_fact":
            adjustment_id = runtime["ids"]["request-activation-rg10"]["adjustment_id"]
            state["evidence_links"].append({
                "id": link["id"], "evidence_id": link["evidence_id"],
                "target_kind": "domain_entity", "target_id": adjustment_id,
                "role": "stored_value_activation_balance_fact",
            })
        elif link["role"] == "stored_value_bonus_component":
            # The mapping ("targets the exact immutable stored_value_bonus_component")
            # and the v2 validator register the domain-entity target.  The frozen v1
            # bonus link targets the lot; the v2 artifact expresses the registered
            # bonus component (id following the fixture convention, amount from the
            # recharge bonus posting) and points the link at it.
            component_id = "bonus-component-recharge-rg10"
            if component_id not in existing_entities:
                state["domain_entities"].append({
                    "id": component_id, "type": "stored_value_bonus_component",
                    "payload": {
                        "lot_id": link["lot_id"],
                        "recharge_transaction_id": next(
                            item["id"] for item in v1_state.get("transactions", [])
                            if item["type"] == "stored_value_recharge"
                        ),
                        "amount": next(
                            posting["amount"] for transaction in v1_state.get("transactions", [])
                            if transaction["type"] == "stored_value_recharge"
                            for posting in transaction["postings"]
                            if posting["account_id"] == "income-special-bonus-rg10"
                        ).removeprefix("-"),
                        "currency": "CNY",
                    },
                })
            state["evidence_links"].append({
                "id": link["id"], "evidence_id": link["evidence_id"],
                "target_kind": "domain_entity", "target_id": component_id,
                "role": "stored_value_bonus_component",
            })
        elif link["role"] == "stored_value_expiry_confirmation":
            # Schema-registered domain target: the confirmed expiry event
            # (registered projection 6).  The event id follows the fixture
            # convention and carries the registered append-only status history:
            # the reminder status is recorded at the lot's frozen expires_at
            # instant (reminder_status expired_date_reached; the frozen v1
            # reminder carries no time), then the confirmed status appends with
            # the frozen loss transaction binding.
            event_id = "expiry-event-rg10"
            if event_id not in existing_entities:
                state["domain_entities"].append({
                    "id": event_id, "type": "stored_value_expiry_event",
                    "payload": {
                        "lot_id": link["lot_id"], "amount": expiry_amount,
                        "currency": "CNY",
                        "status_history": [
                            {
                                "id": "expiry-event-status-reminder-rg10",
                                "sequence": 1, "status": "reminder",
                                # The frozen v1 reminder carries no time; the
                                # registered projection records the reminder
                                # status at the preceding frozen main-path time
                                # (spend created_at), strictly before the
                                # confirmed status.
                                "recorded_at": "2026-01-20T12:03:00+08:00",
                            },
                            {
                                "id": "expiry-event-status-confirmed-rg10",
                                "sequence": 2, "status": "confirmed",
                                # Registered projection: the confirmed status is
                                # recorded at the loss transaction economic time
                                # (the validator binds the event to the version
                                # times); the confirmation created_at is 09:05.
                                "recorded_at": expiry_occurred_at,
                                "loss_transaction_id": link["target_id"],
                            },
                        ],
                    },
                })
            state["evidence_links"].append({
                "id": link["id"], "evidence_id": link["evidence_id"],
                "target_kind": "domain_entity", "target_id": event_id,
                "role": "stored_value_expiry_confirmation",
            })
        else:
            target_kind = "posting" if link["role"] in {
                "bank_payment_posting", "stored_value_asset_posting",
            } else "domain_entity"
            state["evidence_links"].append({
                "id": link["id"], "evidence_id": link["evidence_id"],
                "target_kind": target_kind, "target_id": link["target_id"],
                "role": link["role"],
            })

    for confirmation in v1_state.get("confirmations", []):
        if confirmation["id"] in existing_confirmations:
            continue
        operation_id = (
            (confirmation_operation_ids or {}).get(confirmation["id"], first_operation_id)
        )
        state["confirmations"].append({
            "id": confirmation["id"], "type": "explicit_operation_confirmation",
            "operation_id": operation_id,
            "subject": {"kind": "operation", "id": operation_id},
            "confirmed_at": confirmation["confirmed_at"], "payload": {},
        })

    for lot in v1_state.get("lots", []):
        if lot["id"] in existing_entities:
            continue
        state["domain_entities"].append({
            "id": lot["id"], "type": "stored_value_lot",
            "payload": {
                "recharge_transaction_id": lot.get("recharge_transaction_id"),
                "loaded_at": lot["loaded_at"], "face_value": lot["face_value"],
                "currency": "CNY",
            },
        })
    # Frozen spend lot_consumptions project onto stored_value_consumption
    # entities with the runtime fixture consumption ids.
    spend_request = next(
        (fields for fields in runtime["ids"].values()
         if fields.get("transaction_id") == "transaction-spend-rg10"),
        None,
    )
    for transaction in v1_state.get("transactions", []):
        if transaction["type"] != "stored_value_spend":
            continue
        for index, consumption in enumerate(transaction.get("lot_consumptions", [])):
            consumption_id = (
                spend_request["consumption_id"] if spend_request is not None
                else f"consumption-multi-lot-{index + 1}-rg10"
            )
            if consumption_id in existing_entities:
                continue
            payload = {
                "lot_id": consumption["lot_id"], "amount": consumption["amount"],
                "currency": "CNY",
                "paid_bonus_composition": consumption["paid_bonus_composition"],
            }
            if consumption.get("evidence_id") is not None:
                payload["evidence_id"] = consumption["evidence_id"]
            state["domain_entities"].append({
                "id": consumption_id, "type": "stored_value_consumption",
                "payload": payload,
            })
    for adjustment in v1_state.get("adjustments", []):
        if adjustment["id"] in existing_entities:
            continue
        state["domain_entities"].append({
            "id": adjustment["id"], "type": "activation_adjustment",
            "payload": {"transaction_id": adjustment["transaction_id"]},
        })

    for allocation in v1_state.get("allocations", []):
        if allocation["id"] in existing_entities:
            continue
        state["domain_entities"].append({
            "id": allocation["id"], "type": "merchant_lot_allocation",
            "payload": {
                "request_id": allocation["request_id"],
                "source_id": allocation["source_id"],
                "evidence_id": allocation["evidence_id"],
                "lot_id": allocation["lot_id"],
                "consumption_id": allocation["consumption_id"],
                "amount": allocation["amount"], "currency": "CNY",
                "allocation_source": allocation["allocation_source"],
            },
        })
    for consumption in v1_state.get("consumptions", []):
        if consumption["id"] in existing_entities:
            continue
        payload = {
            "lot_id": consumption["lot_id"], "amount": consumption["amount"],
            "currency": "CNY",
            "paid_bonus_composition": consumption["paid_bonus_composition"],
        }
        if consumption.get("evidence_id") is not None:
            payload["evidence_id"] = consumption["evidence_id"]
        state["domain_entities"].append({
            "id": consumption["id"], "type": "stored_value_consumption",
            "payload": payload,
        })

    candidate_source_by_type = {
        "stored_value_recharge": "source-import-recharge-rg10",
        "stored_value_spend": "source-import-spend-rg10",
    }
    for candidate in v1_state.get("candidates", []):
        if candidate["id"] in existing_candidates:
            continue
        payload = {"candidate_type": candidate["candidate_type"], "currency": "CNY"}
        for field in ("paid_amount", "credited_amount", "bonus_amount", "amount"):
            if field in candidate:
                payload[field] = candidate[field]
        state["candidates"].append({
            "id": candidate["id"], "type": "stored_value_candidate",
            "source_ids": [candidate_source_by_type[candidate["candidate_type"]]],
            "confidence": "1.00", "payload": payload,
            "status_history": [
                {"id": mid(state["root_id"], "candidate_status",
                           "$.import_path.pending_states",
                           candidate["candidate_type"] + "-1"),
                 "sequence": 1, "status": candidate["status"]},
            ],
        })

    # Posting-level reconciliation only; not_present/not_applicable -> absence.
    # Status transitions (pending -> matched) upsert the same posting key.
    existing_reconciliation_items = {
        item["posting_id"]: item for item in state["posting_reconciliations"]
    }
    for key, value in v1_state.get("reconciliation", {}).items():
        if value in {"not_present", "not_applicable"}:
            continue
        if not any(item["id"] == key for item in state["postings"]):
            continue
        current = existing_reconciliation_items.get(key)
        if current is None:
            item = {
                "id": "reconciliation-" + key, "posting_id": key, "status": value,
            }
            state["posting_reconciliations"].append(item)
            existing_reconciliation_items[key] = item
        elif current["status"] != value:
            current["status"] = value


def apply_v1_state(state: dict, v1_state: dict, *,
                   confirmation_operation_ids: dict[str, str] | None = None,
                   first_operation_id: str | None = None) -> None:
    _apply_v1_state(
        state, v1_state, json.loads(RUNTIME_PATH.read_text(encoding="utf-8")),
        confirmation_operation_ids=confirmation_operation_ids,
        first_operation_id=first_operation_id,
    )
    refresh(state)
    refresh_statuses(state)


def snapshot_chain_initial(v1: dict, root_id: str, *builders,
                           include_opening: bool = True) -> dict:
    """Construct a root initial state that embeds v1 baseline snapshot builders."""
    state = empty_state(v1, root_id, include_opening=include_opening)
    for builder in builders:
        builder(state)
    refresh(state)
    refresh_statuses(state)
    return state


def multi_lot_base_state(state: dict, v1: dict) -> None:
    base = v1["secondary_cases"]["multi_lot_allocation"]["base"]
    for lot in base["lots"]:
        state["domain_entities"].append({
            "id": lot["id"], "type": "stored_value_lot",
            "payload": {
                "recharge_transaction_id": None,
                "loaded_at": lot["loaded_at"], "face_value": lot["face_value"],
                "currency": "CNY",
            },
        })


def multi_lot_spend_state(state: dict, v1: dict) -> None:
    """The synthetic multi-lot spend commit (RG10-SPEC-003)."""
    set_id = "posting-set-multi-lot-rg10"
    postings = [
        {"id": "posting-expense-multi-lot-rg10", "posting_set_id": set_id,
         "account_id": "expense-consumption-rg10", "amount": "800.00",
         "currency": "CNY", "reconciliation_eligible": False,
         "role": "expense"},
        {"id": "posting-stored-multi-lot-rg10", "posting_set_id": set_id,
         "account_id": "asset-stored-value-x", "amount": "-800.00",
         "currency": "CNY", "reconciliation_eligible": True,
         "role": "stored_value_asset"},
    ]
    state["transactions"].append({
        "id": "transaction-multi-lot-rg10", "type": "stored_value_spend",
        "current_version_id": "version-multi-lot-rg10-v1",
    })
    state["transaction_versions"].append({
        "id": "version-multi-lot-rg10-v1", "transaction_id": "transaction-multi-lot-rg10",
        "version_number": 1, "posting_set_id": set_id,
        "occurred_at": "2026-01-20T12:00:00+08:00",
        "statistics_at": "2026-01-20T12:00:00+08:00",
        "effective_at": "2026-01-20T12:00:00+08:00",
        "created_at": "2026-01-20T12:03:00+08:00",
    })
    state["posting_sets"].append({"id": set_id, "posting_ids": [item["id"] for item in postings]})
    state["postings"].extend(postings)
    state["confirmations"].append({
        "id": "confirmation-multi-lot-rg10", "type": "explicit_operation_confirmation",
        "operation_id": "multi_lot_allocation",
        "subject": {"kind": "operation", "id": "multi_lot_allocation"},
        "confirmed_at": "2026-01-20T12:03:00+08:00", "payload": {},
    })
    base = v1["secondary_cases"]["multi_lot_allocation"]["base"]
    for index, lot in enumerate(base["lots"], 1):
        state["domain_entities"].append({
            "id": f"consumption-multi-lot-{index}-rg10", "type": "stored_value_consumption",
            "payload": {
                "lot_id": lot["id"], "amount": lot["remaining_face_value"],
                "currency": "CNY", "paid_bonus_composition": "unknown",
            },
        })
    state["posting_reconciliations"].append({
        "id": "reconciliation-posting-stored-multi-lot-rg10",
        "posting_id": "posting-stored-multi-lot-rg10", "status": "pending",
    })


def activation_state(state: dict, v1: dict) -> None:
    boundary = v1["canonical_states"]["activation_boundary"]
    apply_v1_state(
        state, boundary,
        confirmation_operation_ids={"confirmation-activation-rg10": "request-activation-rg10"},
    )
    reconstruction = RUNTIME["ids"]["request-activation-rg10"]
    state["domain_entities"].append({
        "id": reconstruction["reconstruction_id"], "type": "stored_value_reconstruction",
        "payload": {
            "adjustment_id": reconstruction["adjustment_id"],
            "reconstructed_transaction_ids": [],
            "active_mode": "adjustment",
            "history": [{
                "id": "reconstruction-history-created-rg10", "sequence": 1,
                "active_mode": "adjustment",
                "confirmed_at": v1["secondary_cases"]["activation_boundary"]["input"]["created_at"],
            }],
        },
    })
    # Registered typed audit link covering the adjustment endpoint (mapping:
    # "Typed empty-payload audit links exactly cover the adjustment and
    # reconstructed transaction endpoints"); the v1 explicit_confirmation_provenance
    # link stays unprojected (the confirmation subject carries the binding).
    state["audit_links"].append({
        "id": "audit-link-reconstruction-adjustment-rg10",
        "type": "reconstruction_adjustment",
        "from": {"kind": "domain_entity", "id": reconstruction["reconstruction_id"]},
        "to": {"kind": "domain_entity", "id": reconstruction["adjustment_id"]},
        "payload": {},
    })


def v1_contract() -> dict:
    return json.loads(V1_PATH.read_text(encoding="utf-8"))


def v1_input(v1: dict, *path_parts: str) -> dict:
    node = v1
    for part in path_parts:
        node = node[part]
    return deepcopy(node["input"])


def multi_lot_input() -> dict:
    return {
        "request_id": "request-multi-lot-rg10",
        "model": "stored_value_asset",
        "behavior": "stored_value_spend",
        "stored_value_account_id": "asset-stored-value-x",
        "category_id": "expense-category-meal-rg10",
        "amount": "800.00",
        "currency": "CNY",
        "occurred_at": "2026-01-20T12:00:00+08:00",
        "created_at": "2026-01-20T12:03:00+08:00",
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


def returned_ids(operation_id: str) -> list[dict]:
    return [
        {"kind": kind, "id": item_id}
        for kind, item_id in RETURNED_IDS[operation_id]
    ]


RUNTIME = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))


def build_rg10_expected() -> dict:
    v1 = v1_contract()
    roots, states, operations = [], [], []

    def root(purpose: str, locator: str, discriminator: str,
             initial: dict) -> tuple[dict, dict]:
        root_id = deterministic_v2_root_id("RG-10", locator, discriminator)
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

    # ---- Main path root: recharge -> spend -> reminder -> expiry, then the
    # four retries chained at the end (v2 topology rule, registered above).
    main_root, current = root("rg10_main_path", "$.main_path", "main",
                              empty_state(v1, deterministic_v2_root_id(
                                  "RG-10", "$.main_path", "main")))
    sequence = 0

    def accepted(operation: dict, result: dict) -> dict:
        nonlocal current, sequence
        sequence += 1
        current = emit(main_root, current, sequence, operation, result)
        return operation

    recharge_confirmed = clone(current, "request-recharge-rg10",
                               "$.main_path.recharge", "recharge")
    apply_v1_state(
        recharge_confirmed, v1["canonical_states"]["recharge_confirmed"],
        confirmation_operation_ids={"confirmation-recharge-rg10": "request-recharge-rg10"},
    )
    recharge_op = accepted(
        op(main_root["id"], sequence, "request-recharge-rg10",
           "confirm_stored_value_recharge", "creation", current,
           recharge_confirmed,
           input_value=v1_input(v1, "main_path", "recharge"),
           returned=returned_ids("request-recharge-rg10")),
        recharge_confirmed)

    spend_confirmed = clone(current, "request-spend-rg10",
                            "$.main_path.spend", "spend")
    apply_v1_state(
        spend_confirmed, v1["canonical_states"]["spend_confirmed"],
        confirmation_operation_ids={"confirmation-spend-rg10": "request-spend-rg10"},
    )
    spend_op = accepted(
        op(main_root["id"], sequence, "request-spend-rg10",
           "confirm_stored_value_spend", "creation", current,
           spend_confirmed,
           input_value=v1_input(v1, "main_path", "spend"),
           returned=returned_ids("request-spend-rg10")),
        spend_confirmed)

    reminded = clone(current, "request-reminder-rg10",
                     "$.main_path.expiry_reminder", "reminder")
    reminder_op = accepted(
        op(main_root["id"], sequence, "request-reminder-rg10",
           "record_expiry_reminder", "status_transition", current,
           reminded,
           input_value=v1_input(v1, "main_path", "expiry_reminder"),
           returned=returned_ids("request-reminder-rg10")),
        reminded)

    expiry_confirmed = clone(current, "request-expiry-rg10",
                             "$.main_path.expiry_confirmation", "expiry")
    apply_v1_state(
        expiry_confirmed, v1["canonical_states"]["expiry_confirmed"],
        confirmation_operation_ids={"confirmation-expiry-rg10": "request-expiry-rg10"},
    )
    expiry_op = accepted(
        op(main_root["id"], sequence, "request-expiry-rg10",
           "confirm_stored_value_expiry_loss", "creation", current,
           expiry_confirmed,
           input_value=v1_input(v1, "main_path", "expiry_confirmation"),
           returned=returned_ids("request-expiry-rg10")),
        expiry_confirmed)

    for retry_id, accepted_operation in (
        ("recharge_retry", recharge_op),
        ("spend_retry", spend_op),
        ("expiry_retry", expiry_op),
        ("reminder_retry", reminder_op),
    ):
        sequence += 1
        current = emit_retry(main_root, current, sequence, accepted_operation, retry_id)

    # ---- Reconciliation merchant root: recharge-confirmed snapshot, reconcile,
    # then the merchant retry.
    merchant_root_id = deterministic_v2_root_id(
        "RG-10", "$.reconciliation_path.merchant_evidence", "merchant")
    merchant_initial = snapshot_chain_initial(
        v1, merchant_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["recharge_confirmed"],
            first_operation_id="source-merchant-credit-rg10"))
    merchant_root, current = root(
        "rg10_reconciliation_merchant", "$.reconciliation_path.merchant_evidence",
        "merchant", merchant_initial)

    sequence = 1
    merchant_reconciled = clone(current, "source-merchant-credit-rg10",
                                "$.reconciliation_states.state-rg10-recharge-merchant-reconciled",
                                "merchant-reconciled")
    apply_v1_state(
        merchant_reconciled,
        v1["reconciliation_states"]["state-rg10-recharge-merchant-reconciled"],
    )
    merchant_reconcile_op = op(merchant_root["id"], sequence, "source-merchant-credit-rg10",
                               "reconcile_merchant_credit", "reconciliation", current,
                               merchant_reconciled,
                               input_value=v1_input(v1, "reconciliation_path", "merchant_evidence"),
                               returned=returned_ids("source-merchant-credit-rg10"))
    current = emit(merchant_root, current, sequence, merchant_reconcile_op, merchant_reconciled)
    current = emit_retry(merchant_root, current, 2, merchant_reconcile_op,
                         "merchant_reconciliation_retry")

    # ---- Reconciliation bank root: merchant-reconciled snapshot, reconcile,
    # then the bank retry.
    bank_root_id = deterministic_v2_root_id(
        "RG-10", "$.reconciliation_path.bank_evidence", "bank")
    bank_initial = snapshot_chain_initial(
        v1, bank_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["recharge_confirmed"],
            first_operation_id="source-bank-payment-rg10"),
        lambda state: apply_v1_state(
            state, v1["reconciliation_states"]["state-rg10-recharge-merchant-reconciled"],
            first_operation_id="source-bank-payment-rg10"))
    bank_root, current = root(
        "rg10_reconciliation_bank", "$.reconciliation_path.bank_evidence",
        "bank", bank_initial)

    sequence = 1
    fully_reconciled = clone(current, "source-bank-payment-rg10",
                             "$.reconciliation_states.state-rg10-recharge-fully-reconciled",
                             "fully-reconciled")
    apply_v1_state(
        fully_reconciled,
        v1["reconciliation_states"]["state-rg10-recharge-fully-reconciled"],
    )
    bank_reconcile_op = op(bank_root["id"], sequence, "source-bank-payment-rg10",
                           "reconcile_bank_payment", "reconciliation", current,
                           fully_reconciled,
                           input_value=v1_input(v1, "reconciliation_path", "bank_evidence"),
                           returned=returned_ids("source-bank-payment-rg10"))
    current = emit(bank_root, current, sequence, bank_reconcile_op, fully_reconciled)
    current = emit_retry(bank_root, current, 2, bank_reconcile_op,
                         "bank_reconciliation_retry")

    # ---- Import recharge root: opening, ingest candidate, then its retry.
    import_recharge_root_id = deterministic_v2_root_id(
        "RG-10", "$.import_path.complete_unconfirmed[*]", "recharge")
    import_recharge_root, current = root(
        "rg10_import_recharge", "$.import_path.complete_unconfirmed[*]", "recharge",
        empty_state(v1, import_recharge_root_id))
    sequence = 1
    pending_recharge = clone(current, "import-recharge-complete-unconfirmed-rg10",
                             "$.import_path.pending_states.recharge", "pending")
    apply_v1_state(
        pending_recharge, v1["import_path"]["pending_states"]["recharge"],
        first_operation_id="import-recharge-complete-unconfirmed-rg10",
    )
    ingest_recharge_op = op(import_recharge_root["id"], sequence,
                            "import-recharge-complete-unconfirmed-rg10",
                            "ingest_stored_value_recharge_candidate", "creation", current,
                            pending_recharge,
                            input_value=deepcopy(
                                v1["import_path"]["complete_unconfirmed"][0]["input"]),
                            returned=returned_ids("import-recharge-complete-unconfirmed-rg10"))
    current = emit(import_recharge_root, current, sequence, ingest_recharge_op, pending_recharge)
    current = emit_retry(import_recharge_root, current, 2, ingest_recharge_op,
                         "import_recharge_retry")

    # ---- Import spend root: recharge-confirmed snapshot, ingest spend, retry.
    import_spend_root_id = deterministic_v2_root_id(
        "RG-10", "$.import_path.complete_unconfirmed[*]", "spend")
    import_spend_initial = snapshot_chain_initial(
        v1, import_spend_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["recharge_confirmed"],
            first_operation_id="import-spend-complete-unconfirmed-rg10"))
    import_spend_root, current = root(
        "rg10_import_spend", "$.import_path.complete_unconfirmed[*]", "spend",
        import_spend_initial)
    sequence = 1
    pending_spend = clone(current, "import-spend-complete-unconfirmed-rg10",
                          "$.import_path.pending_states.spend", "pending")
    apply_v1_state(
        pending_spend, v1["import_path"]["pending_states"]["spend"],
        first_operation_id="import-spend-complete-unconfirmed-rg10",
    )
    ingest_spend_op = op(import_spend_root["id"], sequence,
                         "import-spend-complete-unconfirmed-rg10",
                         "ingest_stored_value_spend_candidate", "creation", current,
                         pending_spend,
                         input_value=deepcopy(
                             v1["import_path"]["complete_unconfirmed"][1]["input"]),
                         returned=returned_ids("import-spend-complete-unconfirmed-rg10"))
    current = emit(import_spend_root, current, sequence, ingest_spend_op, pending_spend)
    current = emit_retry(import_spend_root, current, 2, ingest_spend_op,
                         "import_spend_retry")

    # ---- Incomplete-import roots: fresh runtimes against their fixture
    # baselines (RG10-SPEC-007); each rejected operation owns its root.
    incomplete_recharge_root_id = deterministic_v2_root_id(
        "RG-10", "$.import_path.incomplete_confirmations[*]", "recharge")
    incomplete_recharge_root, current = root(
        "rg10_import_incomplete_recharge", "$.import_path.incomplete_confirmations[*]",
        "recharge", empty_state(v1, incomplete_recharge_root_id))
    node = v1["import_path"]["incomplete_confirmations"][0]
    result = clone(current, "import-recharge-only-merchant-rg10",
                   "$.import_path.incomplete_confirmations[*]",
                   "import-recharge-only-merchant-rg10")
    current = emit(incomplete_recharge_root, current, 1,
                   rejected(current, result, incomplete_recharge_root["id"], 1,
                            "import-recharge-only-merchant-rg10",
                            "confirm_imported_stored_value_recharge",
                            deepcopy(node["input"]),
                            node["expected"]["reason"], "explicit_confirmation"),
                   result)

    incomplete_spend_root_id = deterministic_v2_root_id(
        "RG-10", "$.import_path.incomplete_confirmations[*]", "spend")
    incomplete_spend_initial = snapshot_chain_initial(
        v1, incomplete_spend_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["recharge_confirmed"],
            first_operation_id="import-spend-without-category-rg10"))
    incomplete_spend_root, current = root(
        "rg10_import_incomplete_spend", "$.import_path.incomplete_confirmations[*]",
        "spend", incomplete_spend_initial)
    node = v1["import_path"]["incomplete_confirmations"][1]
    result = clone(current, "import-spend-without-category-rg10",
                   "$.import_path.incomplete_confirmations[*]",
                   "import-spend-without-category-rg10")
    current = emit(incomplete_spend_root, current, 1,
                   rejected(current, result, incomplete_spend_root["id"], 1,
                            "import-spend-without-category-rg10",
                            "confirm_imported_stored_value_spend",
                            deepcopy(node["input"]),
                            node["expected"]["reason"], "explicit_confirmation"),
                   result)

    # ---- Multi-lot root: synthetic base snapshot, one synthesized spend.
    multi_lot_root_id = deterministic_v2_root_id(
        "RG-10", "$.secondary_cases.multi_lot_allocation", "multi-lot")
    multi_lot_initial = snapshot_chain_initial(
        v1, multi_lot_root_id,
        lambda state: multi_lot_base_state(state, v1),
        include_opening=False)
    multi_lot_root, current = root(
        "rg10_multi_lot", "$.secondary_cases.multi_lot_allocation", "multi-lot",
        multi_lot_initial)
    sequence = 1
    multi_lot_result = clone(current, "multi_lot_allocation",
                             "$.secondary_cases.multi_lot_allocation", "consumed")
    multi_lot_spend_state(multi_lot_result, v1)
    refresh(multi_lot_result)
    refresh_statuses(multi_lot_result)
    multi_lot_op = op(multi_lot_root["id"], sequence, "multi_lot_allocation",
                      "confirm_stored_value_spend", "creation", current,
                      multi_lot_result,
                      input_value=multi_lot_input(),
                      returned=returned_ids("multi_lot_allocation"))
    current = emit(multi_lot_root, current, sequence, multi_lot_op, multi_lot_result)

    # ---- Merchant allocation root: v1 baseline snapshot, allocation, retry.
    allocation_root_id = deterministic_v2_root_id(
        "RG-10", "$.secondary_cases.merchant_evidenced_allocation", "allocation")
    allocation_initial = snapshot_chain_initial(
        v1, allocation_root_id,
        lambda state: apply_v1_state(
            state, v1["secondary_cases"]["merchant_evidenced_allocation"]["states"]["baseline"],
            first_operation_id="request-merchant-allocation-rg10"),
        include_opening=False)
    allocation_root, current = root(
        "rg10_merchant_allocation", "$.secondary_cases.merchant_evidenced_allocation",
        "allocation", allocation_initial)
    sequence = 1
    allocated = clone(current, "request-merchant-allocation-rg10",
                      "$.secondary_cases.merchant_evidenced_allocation.states.allocated",
                      "allocated")
    apply_v1_state(
        allocated, v1["secondary_cases"]["merchant_evidenced_allocation"]["states"]["allocated"],
        first_operation_id="request-merchant-allocation-rg10",
    )
    allocation_op = op(allocation_root["id"], sequence, "request-merchant-allocation-rg10",
                       "apply_merchant_lot_allocation", "update", current,
                       allocated,
                       input_value=v1_input(v1, "secondary_cases", "merchant_evidenced_allocation"),
                       returned=returned_ids("request-merchant-allocation-rg10"))
    current = emit(allocation_root, current, sequence, allocation_op, allocated)
    current = emit_retry(allocation_root, current, 2, allocation_op,
                         "merchant_allocation_retry")

    # ---- Rename root: spend-confirmed snapshot, zero-effect acceptance.
    rename_root_id = deterministic_v2_root_id(
        "RG-10", "$.secondary_cases.rename_zero_effect", "rename")
    rename_initial = snapshot_chain_initial(
        v1, rename_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["spend_confirmed"],
            first_operation_id="rename_zero_effect"))
    rename_root, current = root(
        "rg10_rename", "$.secondary_cases.rename_zero_effect", "rename",
        rename_initial)
    sequence = 1
    renamed = clone(current, "rename_zero_effect",
                    "$.secondary_cases.rename_zero_effect", "renamed")
    rename_op = op(rename_root["id"], sequence, "rename_zero_effect",
                   "rename_stored_value_labels", "update", current,
                   renamed,
                   input_value=v1_input(v1, "secondary_cases", "rename_zero_effect"),
                   returned=returned_ids("rename_zero_effect"))
    current = emit(rename_root, current, sequence, rename_op, renamed)

    # ---- Activation root: opening, activation, retry.
    activation_root_id = deterministic_v2_root_id(
        "RG-10", "$.secondary_cases.activation_boundary", "activation")
    activation_root, current = root(
        "rg10_activation", "$.secondary_cases.activation_boundary", "activation",
        empty_state(v1, activation_root_id))
    sequence = 1
    activated = clone(current, "request-activation-rg10",
                      "$.canonical_states.activation_boundary", "activated")
    activation_state(activated, v1)
    refresh(activated)
    refresh_statuses(activated)
    activation_op = op(activation_root["id"], sequence, "request-activation-rg10",
                       "confirm_stored_value_activation_balance", "adjustment", current,
                       activated,
                       input_value=v1_input(v1, "secondary_cases", "activation_boundary"),
                       returned=returned_ids("request-activation-rg10"))
    current = emit(activation_root, current, sequence, activation_op, activated)
    current = emit_retry(activation_root, current, 2, activation_op,
                         "activation_retry")

    # ---- Invalid-input roots aggregated by their baseline chains.
    opening_initial = empty_state(
        v1, deterministic_v2_root_id("RG-10", "$.invalid_inputs[*]", "opening"))
    invalid_opening_root, current = root(
        "rg10_invalid_opening", "$.invalid_inputs[*]", "opening", opening_initial)
    opening_probe_ids = [
        "float-amount", "numeric-credited-amount", "numeric-bonus-amount",
        "nonpositive-amount", "nonpositive-credited-amount",
        "negative-bonus-amount", "credited-less-than-paid", "component-mismatch",
        "disabled-stored-account", "model-overlap", "unknown-payment-account",
        "unowned-payment-account", "wrong-payment-account-kind",
        "wrong-stored-account-kind", "wrong-currency",
    ]
    for index, probe_id in enumerate(opening_probe_ids, 1):
        node = next(item for item in v1["invalid_inputs"] if item["id"] == probe_id)
        result = clone(current, probe_id, "$.invalid_inputs[*]", probe_id)
        operation = rejected(current, result, invalid_opening_root["id"], index,
                             probe_id, "confirm_stored_value_recharge",
                             deepcopy(node["input"]), node["expected"]["reason"],
                             _rejection_field(probe_id, node["input"]))
        current = emit(invalid_opening_root, current, index, operation, result)

    invalid_recharge_root_id = deterministic_v2_root_id(
        "RG-10", "$.invalid_inputs[*]", "recharge-confirmed")
    invalid_recharge_initial = snapshot_chain_initial(
        v1, invalid_recharge_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["recharge_confirmed"],
            first_operation_id="spend-over-balance"))
    invalid_recharge_root, current = root(
        "rg10_invalid_recharge", "$.invalid_inputs[*]", "recharge-confirmed",
        invalid_recharge_initial)
    for index, probe_id in enumerate(("spend-over-balance", "unknown-category"), 1):
        node = next(item for item in v1["invalid_inputs"] if item["id"] == probe_id)
        result = clone(current, probe_id, "$.invalid_inputs[*]", probe_id)
        operation = rejected(current, result, invalid_recharge_root["id"], index,
                             probe_id, "confirm_stored_value_spend",
                             deepcopy(node["input"]), node["expected"]["reason"],
                             _rejection_field(probe_id, node["input"]))
        current = emit(invalid_recharge_root, current, index, operation, result)

    invalid_spend_root_id = deterministic_v2_root_id(
        "RG-10", "$.invalid_inputs[*]", "spend-confirmed")
    invalid_spend_initial = snapshot_chain_initial(
        v1, invalid_spend_root_id,
        lambda state: apply_v1_state(
            state, v1["canonical_states"]["spend_confirmed"],
            first_operation_id="invalid-lot-allocation"))
    invalid_spend_root, current = root(
        "rg10_invalid_spend", "$.invalid_inputs[*]", "spend-confirmed",
        invalid_spend_initial)
    spend_probes = [
        ("invalid-lot-allocation", "apply_merchant_lot_allocation"),
        ("unconfirmed-expiry", "confirm_stored_value_expiry_loss"),
        ("guessed-composition", "confirm_stored_value_spend"),
    ]
    for index, (probe_id, action) in enumerate(spend_probes, 1):
        node = next(item for item in v1["invalid_inputs"] if item["id"] == probe_id)
        result = clone(current, probe_id, "$.invalid_inputs[*]", probe_id)
        operation = rejected(current, result, invalid_spend_root["id"], index,
                             probe_id, action,
                             deepcopy(node["input"]), node["expected"]["reason"],
                             _rejection_field(probe_id, node["input"]))
        current = emit(invalid_spend_root, current, index, operation, result)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: item["id"])
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {
        "contract": "unifiedledger.golden-case", "contract_version": "2.0.0",
        "case": {"id": "RG-10", "level": v1["case"]["level"],
                 "rule_version": v1["case"]["rule_version"],
                 "approval_status": "approved",
                 "ledger_id": v1["case"]["ledger_id"],
                 "timezone": v1["case"]["timezone"],
                 "currencies": [{"code": v1["case"]["currency"],
                                 "precision": v1["case"]["precision"]}]},
        "roots": roots, "states": states, "operations": operations,
    }


def _rejection_field(probe_id: str, attempted: dict) -> str:
    """Frozen v1 reason -> artifact field (mirrors the schema registrations)."""
    fields = {
        "float-amount": "paid_amount",
        "numeric-credited-amount": "credited_amount",
        "numeric-bonus-amount": "bonus_amount",
        "nonpositive-amount": "paid_amount",
        "nonpositive-credited-amount": "credited_amount",
        "negative-bonus-amount": "bonus_amount",
        "credited-less-than-paid": "credited_amount",
        "component-mismatch": "credited_amount",
        "disabled-stored-account": "stored_value_account_id",
        "model-overlap": "model",
        "unknown-payment-account": "payment_account_id",
        "unowned-payment-account": "payment_account_id",
        "wrong-payment-account-kind": "payment_account_id",
        "wrong-stored-account-kind": "stored_value_account_id",
        "wrong-currency": "currency",
        "spend-over-balance": "amount",
        "unknown-category": "category_id",
        "invalid-lot-allocation": "amount",
        "unconfirmed-expiry": "explicit_confirmation",
        "guessed-composition": "paid_bonus_composition",
    }
    field = fields[probe_id]
    assert field in attempted, (probe_id, field, attempted)
    return field


def write_rg10_expected() -> None:
    EXPECTED_PATH.write_text(
        json.dumps(build_rg10_expected(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def counts(operation: dict, collection: str) -> tuple[int, int, int]:
    changes_by_id = operation["deltas"]["entity_changes"][collection]
    return (len(changes_by_id["added_ids"]), len(changes_by_id["changed_ids"]),
            len(changes_by_id["removed_ids"]))


class RG10GoldenV2ExpectedTests(unittest.TestCase):
    """The v2 expected is exactly the 44 frozen v1 operations, id for id."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.expected = build_rg10_expected()

    def test_expected_artifact_matches_deterministic_builder(self):
        on_disk = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
        self.assertEqual(on_disk, self.expected)

    def test_schema_and_semantic_validation(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validate_golden_case_v2(self.expected)

    def test_exact_inventory_and_v1_status_distribution(self):
        self.assertEqual(self.expected["case"]["approval_status"], "approved")
        self.assertEqual(len(self.expected["roots"]), 14)
        self.assertEqual(len(self.expected["states"]), 58)
        self.assertEqual(len(self.expected["operations"]), 44)
        status_counts = {
            status: sum(
                operation["outcome"]["status"] == status
                for operation in self.expected["operations"]
            )
            for status in ("accepted", "no_change", "rejected")
        }
        self.assertEqual({"accepted": 12, "no_change": 10, "rejected": 22}, status_counts)
        v1 = v1_contract()
        self.assertEqual(20, len(v1["invalid_inputs"]))
        self.assertEqual(10, len(v1["idempotency"]))

    def test_all_v1_operations_covered(self):
        self.assertEqual(len(V1_OPERATION_PLAN), 44)
        self.assertEqual({operation["id"] for operation in self.expected["operations"]},
                         {entry[1] for entry in V1_OPERATION_PLAN})
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for source_path, operation_id, action, cls, status in V1_OPERATION_PLAN:
            operation = by_id[operation_id]
            self.assertEqual(operation["action_type"], action,
                             f"{operation_id} ({source_path})")
            self.assertEqual(operation["operation_class"], cls,
                             f"{operation_id} ({source_path})")
            self.assertEqual(operation["outcome"]["status"], status,
                             f"{operation_id} ({source_path})")

    def test_topology_and_uuid_exhaustiveness(self):
        roots = {item["id"]: item for item in self.expected["roots"]}
        states = {item["id"]: item for item in self.expected["states"]}
        self.assertEqual(
            set(states),
            {root["initial_state_id"] for root in self.expected["roots"]}
            | {operation["result_state_id"] for operation in self.expected["operations"]},
        )
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
        opening_version_ids = {
            item["id"]
            for state in self.expected["states"]
            for item in state["transaction_versions"]
            if item["transaction_id"] == "transaction-opening-rg10"
        }
        opening_posting_set_ids = {
            item["posting_set_id"]
            for state in self.expected["states"]
            for item in state["transaction_versions"]
            if item["transaction_id"] == "transaction-opening-rg10"
        }
        opening_ids = opening_version_ids | opening_posting_set_ids
        self.assertEqual(12, len(opening_version_ids))
        self.assertEqual(12, len(opening_posting_set_ids))
        self.assertEqual(24, len(opening_ids))
        generated += sorted(opening_ids)
        self.assertTrue(all(
            len(value) == 36 and value.count("-") == 4 for value in generated
        ))
        for state in self.expected["states"]:
            if not any(item["id"] == "transaction-opening-rg10" for item in state["transactions"]):
                continue
            expected_version_id = mid(
                state["root_id"], "transaction_version", "$.opening", "opening"
            )
            expected_posting_set_id = mid(
                state["root_id"], "posting_set", "$.opening", "opening"
            )
            self.assertIn(expected_version_id, opening_ids)
            self.assertIn(expected_posting_set_id, opening_ids)
            opening_transaction = next(
                item for item in state["transactions"]
                if item["id"] == "transaction-opening-rg10"
            )
            self.assertEqual(expected_version_id, opening_transaction["current_version_id"])
            opening_version = next(
                item for item in state["transaction_versions"]
                if item["id"] == expected_version_id
            )
            self.assertEqual(expected_posting_set_id, opening_version["posting_set_id"])
        by_root: dict[str, list[dict]] = {}
        for operation in self.expected["operations"]:
            by_root.setdefault(operation["root_id"], []).append(operation)
        for root_id, operations in by_root.items():
            self.assertEqual(
                [item["sequence"] for item in sorted(operations, key=lambda item: item["sequence"])],
                list(range(1, len(operations) + 1)),
                root_id,
            )
            self.assertEqual(
                roots[root_id]["operation_ids"],
                [item["id"] for item in sorted(operations, key=lambda item: item["sequence"])],
            )
            self.assertIn(roots[root_id]["initial_state_id"],
                          {state["id"] for state in self.expected["states"]
                           if state["root_id"] == root_id and state["as_of_operation_id"] is None})

    def test_rejected_and_no_change_zero_effect(self):
        states = {item["id"]: item for item in self.expected["states"]}
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] not in {"rejected", "no_change"}:
                continue
            baseline = golden_v2._state_payload(states[operation["baseline_state_id"]])
            result = golden_v2._state_payload(states[operation["result_state_id"]])
            self.assertEqual(baseline, result, operation["id"])
            self.assertEqual(operation["status_changes"], [], operation["id"])
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
            if operation["outcome"]["status"] == "no_change":
                self.assertEqual(operation["outcome"]["reason_code"], "idempotent_replay")
                self.assertTrue(operation["returned_ids"])

    def test_returned_ids_match_oracle_registry(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for operation_id, ids in RETURNED_IDS.items():
            operation = by_id[operation_id]
            self.assertEqual(
                [{"kind": kind, "id": item_id} for kind, item_id in ids],
                operation["returned_ids"],
                operation_id,
            )
        # Every retry returns exactly the prior accepted IDs (D-083 retry equality).
        retry_sources = {
            "recharge_retry": "request-recharge-rg10",
            "spend_retry": "request-spend-rg10",
            "expiry_retry": "request-expiry-rg10",
            "reminder_retry": "request-reminder-rg10",
            "merchant_reconciliation_retry": "source-merchant-credit-rg10",
            "bank_reconciliation_retry": "source-bank-payment-rg10",
            "import_recharge_retry": "import-recharge-complete-unconfirmed-rg10",
            "import_spend_retry": "import-spend-complete-unconfirmed-rg10",
            "activation_retry": "request-activation-rg10",
            "merchant_allocation_retry": "request-merchant-allocation-rg10",
        }
        for retry_id, accepted_id in retry_sources.items():
            self.assertEqual(by_id[retry_id]["returned_ids"],
                             by_id[accepted_id]["returned_ids"], retry_id)
            self.assertEqual(by_id[retry_id]["input"], by_id[accepted_id]["input"], retry_id)

    def test_rejection_anchors_reproduce_v1_reason_and_field(self):
        v1 = v1_contract()
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        expected_reviewed_paths = {
            "component-mismatch": "$.attempted_input.credited_amount",
            "import-recharge-only-merchant-rg10": "$.attempted_input.explicit_confirmation",
            "import-spend-without-category-rg10": "$.attempted_input.explicit_confirmation",
        }
        self.assertEqual(
            expected_reviewed_paths,
            {
                operation_id: by_id[operation_id]["outcome"]["field_path"]
                for operation_id in expected_reviewed_paths
            },
        )
        invalid = {item["id"]: item for item in v1["invalid_inputs"]}
        incomplete = {item["id"]: item for item in v1["import_path"]["incomplete_confirmations"]}
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] != "rejected":
                continue
            operation_id = operation["id"]
            if operation_id in invalid:
                source = invalid[operation_id]
            else:
                source = incomplete[operation_id]
            self.assertEqual(operation["outcome"]["reason_code"],
                             source["expected"]["reason"], operation_id)
            self.assertTrue(
                operation["outcome"]["field_path"].startswith("$.attempted_input."),
                operation_id,
            )
            field = operation["outcome"]["field_path"].split(".")[-1]
            self.assertIn(field, operation["attempted_input"], operation_id)
            self.assertEqual(operation["attempted_input"], source["input"], operation_id)
            self.assertEqual(operation["returned_ids"], [], operation_id)

    def test_evidence_link_3_to_4_projection(self):
        runtime = RUNTIME
        states = {item["id"]: item for item in self.expected["states"]}
        recharge_op = next(
            operation for operation in self.expected["operations"]
            if operation["id"] == "request-recharge-rg10"
        )
        recharge_result = states[recharge_op["result_state_id"]]
        links = {item["role"]: item for item in recharge_result["evidence_links"]}
        self.assertEqual(
            {"bank_payment_posting", "stored_value_asset_posting",
             "stored_value_lot_fact", "stored_value_bonus_component"},
            set(links),
        )
        self.assertEqual(links["stored_value_asset_posting"]["id"],
                         "evidence-link-merchant-recharge-rg10")
        self.assertEqual(links["stored_value_lot_fact"]["id"],
                         runtime["ids"]["request-recharge-rg10"]["merchant_lot_link_id"])
        self.assertEqual(links["stored_value_lot_fact"]["target_kind"], "domain_entity")
        self.assertEqual(links["stored_value_lot_fact"]["target_id"],
                         "lot-rg10-20260110-a")
        # The legacy mixed role never appears.
        for state in self.expected["states"]:
            self.assertTrue(all(
                link["role"] != "stored_value_credit_lot"
                for link in state["evidence_links"]
            ))
        # The activation link targets the activation_adjustment entity.
        activation_op = next(
            operation for operation in self.expected["operations"]
            if operation["id"] == "request-activation-rg10"
        )
        activation_result = states[activation_op["result_state_id"]]
        activation_link = next(
            item for item in activation_result["evidence_links"]
            if item["role"] == "stored_value_activation_balance_fact"
        )
        self.assertEqual(activation_link["target_kind"], "domain_entity")
        self.assertEqual(activation_link["target_id"],
                         runtime["ids"]["request-activation-rg10"]["adjustment_id"])
        self.assertEqual(counts(activation_op, "evidence_links"), (1, 0, 0))

    def test_reconciliation_projection_matches_v1(self):
        v1 = v1_contract()
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}

        def posting_statuses(state, *posting_ids):
            return {
                item["posting_id"]: item["status"]
                for item in state["posting_reconciliations"]
                if item["posting_id"] in posting_ids
            }

        # Recharge confirmed: both postings pending.
        recharge_result = states[by_id["request-recharge-rg10"]["result_state_id"]]
        self.assertEqual(
            posting_statuses(recharge_result, "posting-bank-recharge-rg10",
                             "posting-stored-recharge-rg10"),
            {"posting-bank-recharge-rg10": "pending",
             "posting-stored-recharge-rg10": "pending"},
        )
        # Merchant reconciled: stored matched, bank still pending; the
        # transaction summary is partial.
        merchant_result = states[by_id["source-merchant-credit-rg10"]["result_state_id"]]
        self.assertEqual(
            posting_statuses(merchant_result, "posting-bank-recharge-rg10",
                             "posting-stored-recharge-rg10"),
            {"posting-bank-recharge-rg10": "pending",
             "posting-stored-recharge-rg10": "matched"},
        )
        summary = next(
            item["value"] for item in merchant_result["derived_statuses"]
            if item["status_name"] == "reconciliation_summary"
            and item["target_id"] == "transaction-recharge-rg10"
        )
        self.assertEqual(summary, "partial")
        # Fully reconciled: both matched, summary matched.
        bank_result = states[by_id["source-bank-payment-rg10"]["result_state_id"]]
        self.assertEqual(
            posting_statuses(bank_result, "posting-bank-recharge-rg10",
                             "posting-stored-recharge-rg10"),
            {"posting-bank-recharge-rg10": "matched",
             "posting-stored-recharge-rg10": "matched"},
        )
        summary = next(
            item["value"] for item in bank_result["derived_statuses"]
            if item["status_name"] == "reconciliation_summary"
            and item["target_id"] == "transaction-recharge-rg10"
        )
        self.assertEqual(summary, "matched")
        # v1 frozen tokens: the merchant state says partial, the full state
        # complete (v2 token matched); pending_financial_evidence (expiry)
        # projects onto pending (v2 registry).
        self.assertEqual(
            v1["reconciliation_states"]["state-rg10-recharge-merchant-reconciled"]
            ["reconciliation"]["transaction-recharge-rg10"],
            "partial",
        )
        self.assertEqual(
            v1["reconciliation_states"]["state-rg10-recharge-fully-reconciled"]
            ["reconciliation"]["transaction-recharge-rg10"],
            "complete",
        )
        expiry_result = states[by_id["request-expiry-rg10"]["result_state_id"]]
        self.assertEqual(
            next(
                item["value"] for item in expiry_result["derived_statuses"]
                if item["status_name"] == "reconciliation_summary"
                and item["target_id"] == "transaction-expiry-rg10"
            ),
            "pending",
        )
        self.assertEqual(
            v1["canonical_states"]["expiry_confirmed"]["reconciliation"]
            ["transaction-expiry-rg10"],
            "pending_financial_evidence",
        )
        # not_present / not_applicable map to record absence.
        opening_state = states[by_id["request-recharge-rg10"]["baseline_state_id"]]
        self.assertEqual(opening_state["posting_reconciliations"], [])
        self.assertEqual(
            v1["canonical_states"]["opening"]["reconciliation"]
            ["posting-bank-recharge-rg10"],
            "not_present",
        )

    def test_runtime_fixture_supplement_binding(self):
        runtime = RUNTIME
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        # Every runtime source record appears in the artifact with its frozen
        # payload facts.
        artifact_sources = {
            item["id"]: item
            for state in self.expected["states"]
            for item in state["sources"]
        }
        self.assertEqual(
            {source["id"] for source in runtime["sources"]},
            set(artifact_sources),
        )
        for source in runtime["sources"]:
            payload = artifact_sources[source["id"]]["payload"]
            self.assertEqual(payload["source_type"], source["source_type"], source["id"])
            self.assertEqual(payload["observed_at"], source["observed_at"], source["id"])
            self.assertEqual(payload["immutable_payload_digest"],
                             source["immutable_payload_digest"], source["id"])
            for field in ("account_id", "amount", "lot_id"):
                self.assertEqual(payload.get(field), source.get(field), f"{source['id']}.{field}")
        # The category parent id comes from the fixture (GAP-05: never inferred).
        meal = next(
            item for item in self.expected["states"][0]["catalog"]["categories"]
            if item["id"] == "expense-category-meal-rg10"
        )
        self.assertEqual(meal["parent_id"],
                         runtime["categories"]["expense-category-meal-rg10"]["parent_id"])
        # The lot fact expires_at/merchant_id are frozen v1 authority; the v2 lot
        # payload keeps the four registered fields.
        lot = next(
            item for state in self.expected["states"]
            for item in state["domain_entities"]
            if item["id"] == "lot-rg10-20260110-a"
        )
        self.assertEqual(lot["payload"], {
            "recharge_transaction_id": "transaction-recharge-rg10",
            "loaded_at": "2026-01-10T10:00:00+08:00",
            "face_value": "1200.00",
            "currency": "CNY",
        })
        # The merchant reconcile returned link id comes from the fixture entry.
        merchant_link_id = runtime["ids"]["request-recharge-rg10"]["merchant_lot_link_id"]
        self.assertEqual(merchant_link_id, "evidence-link-merchant-lot-rg10")
        # The activation ids come from the fixture.
        activation_ids = runtime["ids"]["request-activation-rg10"]
        activation_op = by_id["request-activation-rg10"]
        activation_result = states[activation_op["result_state_id"]]
        self.assertEqual(
            {item["id"] for item in activation_result["domain_entities"]},
            {activation_ids["adjustment_id"], activation_ids["reconstruction_id"]},
        )

    def test_balances_cover_catalog_with_frozen_amounts(self):
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        recharge_result = states[by_id["request-recharge-rg10"]["result_state_id"]]
        balances = {item["account_id"]: item["amount"] for item in recharge_result["balances"]}
        self.assertEqual(balances["asset-bank-a"], "4000.00")
        self.assertEqual(balances["asset-stored-value-x"], "1200.00")
        self.assertEqual(balances["income-special-bonus-rg10"], "-200.00")
        self.assertEqual(balances["equity-opening"], "-5000.00")
        # The validator requires exact catalog coverage, so the disabled
        # stored-value account appears at its zero balance even though the
        # frozen v1 balances omit it (registered decision 8).
        self.assertEqual(balances["asset-stored-value-disabled"], "0.00")
        self.assertEqual(set(balances),
                         {item["id"] for item in self.expected["states"][0]["catalog"]["accounts"]})
        spend_result = states[by_id["request-spend-rg10"]["result_state_id"]]
        self.assertEqual(
            {item["account_id"]: item["amount"] for item in spend_result["balances"]}
            ["asset-stored-value-x"],
            "900.00",
        )

    def test_reports_recompute_frozen_v1_amounts(self):
        v1 = v1_contract()
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        v1_metrics = {
            "special_non_cash_bonus_income": "special_income",
            "budget_effect": "budget",
        }

        def v2_amounts(state):
            return {
                metric["metric"]: metric["amount"]
                for report in state["reports"]
                for metric in report["metrics"]
            }

        recharge = states[by_id["request-recharge-rg10"]["result_state_id"]]
        v1_recharge = v1["canonical_states"]["recharge_confirmed"]["reports"]["cumulative"]
        for v1_key, v2_key in v1_metrics.items():
            self.assertEqual(v2_amounts(recharge)[v2_key], v1_recharge[v1_key], v1_key)
        for key in ("cash_outflow", "net_worth_change"):
            self.assertEqual(v2_amounts(recharge)[key], v1_recharge[key], key)
        spend = states[by_id["request-spend-rg10"]["result_state_id"]]
        v1_spend = v1["canonical_states"]["spend_confirmed"]["reports"]["cumulative"]
        for key in ("ordinary_expense", "consumption", "category_effect", "net_worth_change"):
            self.assertEqual(v2_amounts(spend)[key], v1_spend[key], key)
        expiry = states[by_id["request-expiry-rg10"]["result_state_id"]]
        v1_expiry = v1["canonical_states"]["expiry_confirmed"]["reports"]["cumulative"]
        self.assertEqual(v2_amounts(expiry)["expiry_loss"], v1_expiry["expiry_loss"])
        self.assertEqual(v2_amounts(expiry)["net_worth_change"], v1_expiry["net_worth_change"])

    def test_accepted_operations_match_registered_counts(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        registered = golden_v2._ACCEPTED_ACTION_ENTITY_COUNTS
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] != "accepted":
                continue
            action = operation["action_type"]
            if action == "confirm_stored_value_spend":
                # Two accepted instances with different consumption counts
                # (main spend 1, synthetic multi-lot 4): asserted here.
                if operation["id"] == "request-spend-rg10":
                    self.assertEqual(counts(operation, "domain_entities"), (1, 0, 0))
                    self.assertEqual(counts(operation, "posting_reconciliations"), (1, 0, 0))
                else:
                    self.assertEqual(operation["id"], "multi_lot_allocation")
                    self.assertEqual(counts(operation, "domain_entities"), (4, 0, 0))
                    self.assertEqual(counts(operation, "posting_reconciliations"), (1, 0, 0))
                continue
            for collection, required in registered[action].items():
                self.assertEqual(counts(operation, collection), required,
                                 f"{operation['id']} {collection}")
            for collection in ("transactions", "transaction_versions",
                               "posting_sets", "postings", "sources", "candidates",
                               "confirmations", "evidence", "evidence_links",
                               "audit_links", "domain_entities",
                               "posting_reconciliations"):
                if collection not in registered[action]:
                    self.assertEqual(counts(operation, collection), (0, 0, 0),
                                     f"{operation['id']} {collection}")

    def test_zero_effect_accepted_operations(self):
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for operation_id in ("request-reminder-rg10", "rename_zero_effect"):
            operation = by_id[operation_id]
            self.assertEqual(operation["outcome"]["status"], "accepted")
            baseline = golden_v2._state_payload(states[operation["baseline_state_id"]])
            result = golden_v2._state_payload(states[operation["result_state_id"]])
            self.assertEqual(baseline, result, operation_id)
            self.assertTrue(all(
                not values
                for changes_by_id in operation["deltas"]["entity_changes"].values()
                for values in changes_by_id.values()
            ), operation_id)
        # The reminder keeps its zero formal effect and returns its request id.
        self.assertEqual(by_id["request-reminder-rg10"]["returned_ids"],
                         [{"kind": "operation", "id": "request-reminder-rg10"}])

    def test_multi_lot_synthetic_input_registry(self):
        v1 = v1_contract()
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        operation = by_id["multi_lot_allocation"]
        self.assertEqual(operation["input"], multi_lot_input())
        base = v1["secondary_cases"]["multi_lot_allocation"]["base"]
        self.assertEqual(len(base["lots"]), 4)
        result = next(
            state for state in self.expected["states"]
            if state["id"] == operation["result_state_id"]
        )
        consumptions = [
            item for item in result["domain_entities"]
            if item["type"] == "stored_value_consumption"
        ]
        self.assertEqual(
            [item["payload"]["lot_id"] for item in consumptions],
            [lot["id"] for lot in base["lots"]],
        )
        self.assertEqual(
            [item["payload"]["amount"] for item in consumptions],
            [lot["remaining_face_value"] for lot in base["lots"]],
        )
        # Forbidden inference: composition stays unknown everywhere.
        self.assertTrue(all(
            item["payload"]["paid_bonus_composition"] == "unknown"
            for item in consumptions
        ))

    def test_import_candidates_stay_pending_confirmation(self):
        v1 = v1_contract()
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        for operation_id in ("import-recharge-complete-unconfirmed-rg10",
                             "import-spend-complete-unconfirmed-rg10"):
            operation = by_id[operation_id]
            self.assertEqual(operation["outcome"]["status"], "accepted")
            result = states[operation["result_state_id"]]
            self.assertEqual(len(result["candidates"]), 1)
            candidate = result["candidates"][0]
            self.assertEqual(candidate["type"], "stored_value_candidate")
            self.assertEqual([item["status"] for item in candidate["status_history"]],
                             ["pending_confirmation"])
            self.assertEqual(counts(operation, "candidates"), (1, 0, 0))
            self.assertEqual(counts(operation, "sources"), (1, 0, 0))
            self.assertEqual(counts(operation, "evidence"), (1, 0, 0))
        # The frozen pending states keep exactly their intake on top of the
        # formal baseline (state-rg10-import-spend-pending references the
        # recharge-confirmed state).
        self.assertEqual(
            v1["import_path"]["pending_states"]["spend"]["formal_state_id"],
            "state-rg10-recharge-confirmed",
        )

    def test_activation_domain_projection(self):
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        operation = by_id["request-activation-rg10"]
        result = states[operation["result_state_id"]]
        entities = {item["id"]: item for item in result["domain_entities"]}
        adjustment = entities[RUNTIME["ids"]["request-activation-rg10"]["adjustment_id"]]
        self.assertEqual(adjustment["type"], "activation_adjustment")
        self.assertEqual(adjustment["payload"]["transaction_id"],
                         "transaction-activation-adjustment-rg10")
        reconstruction = entities[RUNTIME["ids"]["request-activation-rg10"]["reconstruction_id"]]
        self.assertEqual(reconstruction["type"], "stored_value_reconstruction")
        self.assertEqual(reconstruction["payload"]["active_mode"], "adjustment")
        self.assertEqual(reconstruction["payload"]["reconstructed_transaction_ids"], [])
        # The activation transaction uses the registered equity role.
        transaction = next(
            item for item in result["transactions"]
            if item["id"] == "transaction-activation-adjustment-rg10"
        )
        version = next(
            item for item in result["transaction_versions"]
            if item["id"] == transaction["current_version_id"]
        )
        postings = {
            item["id"]: item
            for item in result["postings"]
            if item["posting_set_id"] == version["posting_set_id"]
        }
        self.assertEqual(postings["posting-stored-activation-adjustment-rg10"]["role"],
                         "stored_value_asset")
        self.assertEqual(postings["posting-equity-activation-adjustment-rg10"]["role"],
                         "stored_value_pre_activation_adjustment")
        # The v1 explicit_confirmation_provenance audit link is not projected
        # (registered decision 7): the confirmation subject carries the binding.
        # The registered reconstruction_adjustment link covers the adjustment
        # endpoint (mapping: typed links exactly cover the endpoints).
        self.assertEqual([item["type"] for item in result["audit_links"]],
                         ["reconstruction_adjustment"])
        reconstruction_link = result["audit_links"][0]
        self.assertEqual(reconstruction_link["from"],
                         {"kind": "domain_entity",
                          "id": RUNTIME["ids"]["request-activation-rg10"]["reconstruction_id"]})
        self.assertEqual(reconstruction_link["to"],
                         {"kind": "domain_entity",
                          "id": RUNTIME["ids"]["request-activation-rg10"]["adjustment_id"]})
        confirmation = next(
            item for item in result["confirmations"]
            if item["id"] == "confirmation-activation-rg10"
        )
        self.assertEqual(confirmation["operation_id"], "request-activation-rg10")
        self.assertEqual(confirmation["subject"],
                         {"kind": "operation", "id": "request-activation-rg10"})

    def test_expiry_event_projection(self):
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        operation = by_id["request-expiry-rg10"]
        result = states[operation["result_state_id"]]
        event = next(
            item for item in result["domain_entities"]
            if item["type"] == "stored_value_expiry_event"
        )
        self.assertEqual(event["payload"]["lot_id"], "lot-rg10-20260110-a")
        self.assertEqual(event["payload"]["amount"], "100.00")
        self.assertEqual([item["status"] for item in event["payload"]["status_history"]],
                         ["reminder", "confirmed"])
        self.assertEqual(event["payload"]["status_history"][1]["loss_transaction_id"],
                         "transaction-expiry-rg10")
        link = next(
            item for item in result["evidence_links"]
            if item["role"] == "stored_value_expiry_confirmation"
        )
        self.assertEqual(link["target_kind"], "domain_entity")
        self.assertEqual(link["target_id"], event["id"])
        # The reminder root keeps no expiry event (zero-effect acceptance).
        reminder_result = states[by_id["request-reminder-rg10"]["result_state_id"]]
        self.assertTrue(all(
            item["type"] != "stored_value_expiry_event"
            for item in reminder_result["domain_entities"]
        ))

    def test_pre_chain_confirmations_reference_first_operation(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        for operation_id in ("source-merchant-credit-rg10", "source-bank-payment-rg10",
                             "import-spend-complete-unconfirmed-rg10",
                             "rename_zero_effect", "spend-over-balance"):
            root_id = by_id[operation_id]["root_id"]
            initial = next(
                state for state in self.expected["states"]
                if state["root_id"] == root_id and state["as_of_operation_id"] is None
            )
            for confirmation in initial["confirmations"]:
                self.assertEqual(confirmation["operation_id"], operation_id)
                self.assertEqual(confirmation["subject"],
                                 {"kind": "operation", "id": operation_id})
        # Main-root confirmations name their creating operations.
        for operation_id, confirmation_id in (
            ("request-recharge-rg10", "confirmation-recharge-rg10"),
            ("request-spend-rg10", "confirmation-spend-rg10"),
            ("request-expiry-rg10", "confirmation-expiry-rg10"),
        ):
            result = states[by_id[operation_id]["result_state_id"]]
            confirmation = next(
                item for item in result["confirmations"]
                if item["id"] == confirmation_id
            )
            self.assertEqual(confirmation["operation_id"], operation_id)
            self.assertEqual(confirmation["subject"],
                             {"kind": "operation", "id": operation_id})

    def test_frozen_v1_entity_ids_carried_id_for_id(self):
        v1 = v1_contract()
        present = set()
        for state in self.expected["states"]:
            for collection in ("transactions", "transaction_versions",
                               "posting_sets", "postings", "sources", "candidates",
                               "confirmations", "evidence", "evidence_links",
                               "domain_entities"):
                present.update(item["id"] for item in state[collection])
        v1_ids = set()
        for state in (
            list(v1["canonical_states"].values())
            + list(v1["reconciliation_states"].values())
            + list(v1["import_path"]["pending_states"].values())
        ):
            for collection in ("transactions", "versions", "lots",
                               "adjustments", "candidates", "confirmations",
                               "source_records", "evidence", "evidence_links"):
                v1_ids.update(item["id"] for item in state.get(collection, []))
            for transaction in state.get("transactions", []):
                v1_ids.update(item["id"] for item in transaction["postings"])
        v1_ids.update(item["id"] for item in v1["opening"]["transactions"][0]["postings"])
        self.assertTrue(v1_ids <= present)
        # The two v2-only link/entity ids are the registered split projection
        # and the schema-registered expiry event.
        self.assertIn("evidence-link-merchant-lot-rg10", present)
        self.assertIn("expiry-event-rg10", present)


if __name__ == "__main__":
    unittest.main()
