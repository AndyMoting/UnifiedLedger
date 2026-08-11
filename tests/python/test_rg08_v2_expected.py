"""RG-08 Golden Schema v2 expected artifact: deterministic builder and regression tests.

Authorities
-----------
- ``golden/rules/rg-08.json`` (frozen v1, schema_version 1): the 44 operation
  documents (32 v1 operations + 12 retries).  Every v2 operation, input field,
  entity id, and state snapshot is traced to this file.  The 14
  ``operation_baselines`` and 6 ``canonical_states`` snapshots are converted
  shape for shape into v2 complete states.
- ``tests/fixtures/rg08-runtime-input.json``: the deterministic runtime
  supplement (ids, source facts, times, counterparty display names).  The
  builder anchors every generated id and source fact from this fixture instead
  of re-encoding them by hand (D-084:1089).
- ``tools/python/golden_cases/v2.py``: ``validate_golden_case_v2`` plus
  ``deterministic_v2_root_id`` / ``deterministic_v2_migration_id`` and the
  RG-08 semantic contract (B-1).  It is the validation gate.
- ``schemas/golden-case-v2.schema.json``: contract 2.0.0 (read-only).

Canonical semantics
-------------------
- Accepted 6 (lend, manual_collection, maximum_allocation, import_intake,
  formal_confirmation, mirror_merge), rejected 25 (18 invalid inputs + 1
  principal-cap over-balance attempt + 6 incomplete import confirmations),
  no_change 13 (1 rename + 12 generic ``retry_idempotent_input`` retries),
  exactly as D-084/D-089 fixed and the B-1 inventory enforces.
- Money is exact decimal strings; timestamps preserve the frozen v1 ``+08:00``
  zone; the three economic time roles collapse onto the frozen v1
  ``occurred_at`` text (D-088 fallback re-evaluation: the RG-08 runtime
  constructs ``TransactionTimes(occurredAt, occurredAt, occurredAt)``).
- D-090 rejected field-path mirrors: ``negative-interest`` maps to
  ``$.attempted_input.principal_amount`` and ``guessed-split`` maps to
  ``$.attempted_input.split_source``.  All 25 rejected operations use the 13
  registered distinct ``$.attempted_input.*`` paths of the schema enum
  (golden-case-v2.schema.json:4215); the Kotlin oracle's ``$.input.*`` roots
  for the six gate rejections and the allocate rejection are the runtime
  internal vocabulary that D-090 does not touch (field names 1:1 identical).
- D-090/authorized errata (option a): ``formal_confirmation`` creates the
  destination-posting reconciliation record already ``matched`` and
  ``mirror_merge`` adds no reconciliation change; the v1 mirror evidence block
  declares no reconciliation delta and the Kotlin store performs no merge
  transition (SqlDelightRg08Store.kt persistReconciliationTransitions).
- The confirmed import candidate keeps the intake-pending payload verbatim:
  the validator forces retention of all six ``requires_confirmation`` gates,
  and the ``proposed_*`` fields stay in their frozen intake form
  (``proposed_total_received`` / ``proposed_destination_account_id`` /
  ``proposed_actual_receipt_at`` non-null; ``proposed_principal_amount`` /
  ``proposed_interest_amount`` / ``proposed_fee_amount`` /
  ``proposed_behavior_code`` / ``proposed_counterparty_id`` null).  B-1's
  append-only candidate rule treats non-history fields as immutable, so the
  frozen v1 filled ``proposed_*`` projection is not mirrored into v2.
- Source anchor derivations (registered, RG08-DEV-01 precedent): the frozen
  lend-debit ``bank_debit`` source carries no account anchor, so its v2
  ``account_id`` is derived from the frozen ``lend.request.funding_account_id``
  (``asset-bank-a``), isomorphic to the manual credit's destination-derived
  anchor; the frozen manual ``bank_credit`` source carries no original payload
  record, so its v2 ``original_source_payload_hash`` equals its
  ``immutable_payload_hash`` (the import credit precedent has original ==
  immutable).  Both are the minimal way to satisfy the closed B-1 schema for
  these source subtypes.
- Source semantic bindings enforced in ``_validate_rg08_contract`` (batch 0
  F3, RG-07 :4786-4808 precedent): RG-08 sources carrying ``account_id``
  (``bank_debit`` / ``bank_credit``) must resolve to a catalog account whose
  currency byte-equals the source currency; ``lending_agreement``
  ``counterparty_id`` must resolve to a counterparty projected through a
  lending position/settlement; ``bank_credit_mirror`` ``mirror_of_source_id``
  must resolve to one earlier same-state ``bank_credit`` source with the
  exact same amount and currency, and no other RG-08 source subtype may own
  ``mirror_of_source_id`` lineage.  ``_validate_references`` keeps its
  lexical timestamp/amount checks on the four RG-08 source types; the
  semantic resolution above sits in ``_validate_rg08_contract`` next to the
  evidence<->source subtype pairing and ``observed_at`` byte equality.
  Unlike the RG-07 precedent (v2.py:4803-4811), this batch does not enforce
  the ``bank_debit``/``bank_credit`` amount-sign checks or the
  owned/real/asset nature check on the source account; both remain registered
  candidates for a later batch, bounded indirectly by the lending position
  history checks and the account existence/currency binding above.
- Accepted-operation v2 ids are builder-authored readable ids (RG-10
  precedent): ``operation-rg08-lend``, ``operation-rg08-manual-collection``,
  ``operation-rg08-cap-maximum``, ``operation-rg08-import-intake``,
  ``operation-rg08-confirm-import``, ``operation-rg08-merge-import-mirror``,
  plus the no-change rename ``operation-rg08-rename-counterparty``; rejected
  operations and retries keep their frozen v1 operation ids.
- Root/state/derived/reconciliation/audit ids are deterministic v2 identities.
  Retry roots reuse the anchored owner result state payload id for id (the
  B-1 retry contract requires cross-root contract-equivalent baselines), and
  retry root discriminators carry a fixed deterministic suffix so that every
  anchor owner root sorts before its retry root in the (root_id, sequence)
  operation order.
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
V1_PATH = ROOT / "golden" / "rules" / "rg-08.json"
RUNTIME_PATH = ROOT / "tests" / "fixtures" / "rg08-runtime-input.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-08-expected.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
CASE_ID = "RG-08"

# Canonical report metrics for RG-08: every metric is applicable and recomputes
# through golden_v2._report_values (lending disbursement/collection branches).
RG08_METRICS = (
    "budget",
    "cash_inflow",
    "cash_outflow",
    "consumption",
    "expense",
    "income",
    "interest_cash_flow",
    "net_worth_change",
    "ordinary_income",
    "principal_external_cash_flow",
)

# Frozen v1 operation ids -> v2 operation ids for the six accepted operations
# and the rename.  Rejected operations keep their frozen v1 operation ids
# (operation_context.operation_id) and retries keep their frozen retry ids.
ACCEPTED_OPERATION_IDS = {
    "lend": "operation-rg08-lend",
    "rename_counterparty": "operation-rg08-rename-counterparty",
    "manual_collection": "operation-rg08-manual-collection",
    "maximum_allocation": "operation-rg08-cap-maximum",
    "import_intake": "operation-rg08-import-intake",
    "formal_confirmation": "operation-rg08-confirm-import",
    "mirror_merge": "operation-rg08-merge-import-mirror",
}

# Every RG-08 confirmation names its frozen creating operation.  The v2
# contract requires confirmation.operation_id to be a case operation and the
# adding operation to match for newly added confirmations; baseline-embedded
# confirmations use the same creator id in every root so that the retry
# cross-root contract-equivalence holds.
CONFIRMATION_CREATORS = {
    "confirmation-rg08-lend": ACCEPTED_OPERATION_IDS["lend"],
    "confirmation-rg08-manual-collection": ACCEPTED_OPERATION_IDS["manual_collection"],
    "confirmation-rg08-cap-maximum": ACCEPTED_OPERATION_IDS["maximum_allocation"],
    "confirmation-rg08-import": ACCEPTED_OPERATION_IDS["formal_confirmation"],
}

# Canonical rejected field names per reason (D-090 mirror).  ``unknown_account``
# resolves by action: validate_lending_event -> funding_account_id,
# validate_lending_settlement -> destination_account_id.
CANONICAL_REJECTION_FIELDS = {
    "exact_decimal_string_required": "total_received",
    "total_must_be_positive": "total_received",
    "components_must_equal_total": "components",
    "component_must_be_nonnegative": "principal_amount",
    "fee_must_be_zero_in_rg08_v1": "fee_amount",
    "nonzero_fee_accounting_out_of_scope": "fee_amount",
    "principal_exceeds_outstanding_position": "principal_amount",
    "owned_account_required": "destination_account_id",
    "financial_asset_account_required": "destination_account_id",
    "unknown_counterparty": "counterparty_id",
    "invalid_lending_behavior": "behavior_code",
    "explicit_component_split_required": "split_source",
    "same_currency_required": "currency",
    "active_exact_interest_category_required": "interest_category_id",
    "behavior_confirmation_required": "behavior_code",
    "counterparty_confirmation_required": "counterparty_id",
    "destination_confirmation_required": "destination_account_id",
    "principal_confirmation_required": "principal_amount",
    "interest_and_fee_confirmation_required": "interest_and_fee_amounts",
    "actual_receipt_time_confirmation_required": "actual_receipt_time",
}

# The 13 distinct attempted_input paths of the schema field_path enum
# (golden-case-v2.schema.json:4215), which is the operative registry.
REGISTERED_ATTEMPTED_PATHS = {
    "$.attempted_input.total_received",
    "$.attempted_input.components",
    "$.attempted_input.principal_amount",
    "$.attempted_input.fee_amount",
    "$.attempted_input.funding_account_id",
    "$.attempted_input.destination_account_id",
    "$.attempted_input.counterparty_id",
    "$.attempted_input.behavior_code",
    "$.attempted_input.split_source",
    "$.attempted_input.currency",
    "$.attempted_input.interest_category_id",
    "$.attempted_input.interest_and_fee_amounts",
    "$.attempted_input.actual_receipt_time",
}

# Kotlin oracle expectedFieldPath field names per (operation action, reason),
# mirroring the 23 branches of Rg08FullStateOracleTest.expectedFieldPath.  The
# seven ``$.input.*``-rooted branches (six gates + allocate cap) are the Kotlin
# runtime vocabulary; the field name is identical on both sides (D-090).
KOTLIN_FIELD_NAMES = {
    ("validate_lending_settlement", "exact_decimal_string_required"): "total_received",
    ("validate_lending_settlement", "total_must_be_positive"): "total_received",
    ("validate_lending_settlement", "components_must_equal_total"): "components",
    ("validate_lending_settlement", "component_must_be_nonnegative"): "principal_amount",
    ("validate_lending_settlement", "fee_must_be_zero_in_rg08_v1"): "fee_amount",
    ("validate_lending_settlement", "nonzero_fee_accounting_out_of_scope"): "fee_amount",
    ("validate_lending_settlement", "principal_exceeds_outstanding_position"): "principal_amount",
    ("validate_lending_settlement", "unknown_account"): "destination_account_id",
    ("validate_lending_settlement", "owned_account_required"): "destination_account_id",
    ("validate_lending_settlement", "financial_asset_account_required"): "destination_account_id",
    ("validate_lending_event", "unknown_account"): "funding_account_id",
    ("validate_lending_settlement", "unknown_counterparty"): "counterparty_id",
    ("validate_lending_settlement", "invalid_lending_behavior"): "behavior_code",
    ("validate_lending_settlement", "explicit_component_split_required"): "split_source",
    ("validate_lending_settlement", "same_currency_required"): "currency",
    ("validate_lending_settlement", "active_exact_interest_category_required"): "interest_category_id",
    ("allocate_lending_collection", "principal_exceeds_outstanding_position"): "principal_amount",
    ("confirm_imported_lending_collection", "behavior_confirmation_required"): "behavior_code",
    ("confirm_imported_lending_collection", "counterparty_confirmation_required"): "counterparty_id",
    ("confirm_imported_lending_collection", "destination_confirmation_required"): "destination_account_id",
    ("confirm_imported_lending_collection", "principal_confirmation_required"): "principal_amount",
    ("confirm_imported_lending_collection", "interest_and_fee_confirmation_required"): "interest_and_fee_amounts",
    ("confirm_imported_lending_collection", "actual_receipt_time_confirmation_required"): "actual_receipt_time",
}

# Actions and classes per v2 operation id (frozen v1 classification).
OPERATION_ACTION_CLASS = {
    ACCEPTED_OPERATION_IDS["lend"]: ("validate_lending_event", "creation"),
    ACCEPTED_OPERATION_IDS["rename_counterparty"]: ("validate_lending_event", "update"),
    ACCEPTED_OPERATION_IDS["manual_collection"]: ("validate_lending_settlement", "creation"),
    ACCEPTED_OPERATION_IDS["maximum_allocation"]: ("allocate_lending_collection", "creation"),
    ACCEPTED_OPERATION_IDS["import_intake"]: ("confirm_imported_lending_collection", "creation"),
    ACCEPTED_OPERATION_IDS["formal_confirmation"]: ("confirm_imported_lending_collection", "creation"),
    ACCEPTED_OPERATION_IDS["mirror_merge"]: ("confirm_imported_lending_collection", "reconciliation"),
}

SOURCE_TYPE_BY_KIND = {
    "bank_debit": "bank_debit",
    "bank_credit": "bank_credit",
    "bank_credit_mirror": "bank_credit_mirror",
    "lending_agreement": "lending_agreement",
    "explicit_manual_lending_confirmation": "explicit_manual_lending_confirmation",
}

LINK_TARGET_KIND = {
    "funding_asset_posting": "posting",
    "destination_asset_posting": "posting",
    "counterparty_lending_relationship": "domain_entity",
}

SOURCE_PAYLOAD_FIELDS = (
    "booking_at",
    "value_at",
    "account_id",
    "amount",
    "currency",
    "original_source_payload_hash",
    "counterparty_id",
    "mirror_of_source_id",
)

# Retry roots must sort after their anchor owner roots in the (root_id,
# sequence) operation order (B-1 retry contract).  uuid5 root ids do not sort
# naturally, so each retry root discriminator tries this fixed deterministic
# suffix list until the owner-before-retry ordering holds.
RETRY_SUFFIX_CANDIDATES = [""] + [chr(code) for code in range(ord("a"), ord("z") + 1)]


def mid(root_id: str, kind: str, locator: str, occurrence: str) -> str:
    return deterministic_v2_migration_id(CASE_ID, root_id, kind, locator, occurrence)


def state_id(root_id: str, locator: str, occurrence: str) -> str:
    return mid(root_id, "state", locator, occurrence)


def build_catalog(v1: dict) -> dict:
    """v2 catalog: frozen v1 accounts plus the lending contract overrides.

    The D-084 contract requires the counterparty receivable account to be a
    non-real, non-owned asset in v2 (lending_position receivable owner rule and
    the lending posting semantics), so real_account/owned_by_user flip to
    False for ``receivable-counterparty-rg08``.  The frozen interest categories
    keep their posting accounts and the missing top-level grouping parent is
    synthesized (RG-07 precedent for two-level category catalogs).
    """
    accounts = []
    for item in v1["catalog"]["accounts"]:
        real_account = item["financial"]
        owned_by_user = item["owned_by_user"]
        if item["id"] == "receivable-counterparty-rg08":
            real_account = False
            owned_by_user = False
        accounts.append({
            "id": item["id"], "name": item["name"], "kind": item["type"],
            "currency": item["currency"], "owned_by_user": owned_by_user,
            "real_account": real_account,
            "reconciliation_eligible": item["reconciliation_eligible"],
        })
    categories = [{
        "id": "income-category-finance", "name": "income-category-finance",
        "parent_id": None, "posting_account_id": None, "active": True,
    }]
    for item in v1["catalog"]["interest_categories"]:
        categories.append({
            "id": item["id"], "name": item["name"], "parent_id": item["parent_id"],
            "posting_account_id": item["account_id"], "active": item["active"],
        })
    return {"accounts": accounts, "categories": categories}


def transaction_type(transaction: dict) -> str:
    if transaction["type"] == "opening_balance":
        return "opening_balance"
    if transaction["type"] == "lending":
        return (
            "lending_disbursement"
            if transaction["behavior_code"] == "lend"
            else "lending_collection"
        )
    raise AssertionError(f"unexpected frozen RG-08 transaction type {transaction['type']!r}")


def posting_role(tx_type: str, posting: dict) -> tuple[str | None, str | None]:
    if tx_type == "opening_balance":
        return None, None
    account_id = posting["account_id"]
    if account_id == "receivable-counterparty-rg08":
        return "lending_receivable", None
    if account_id == "asset-bank-a":
        return "lending_principal_out", None
    if account_id == "asset-wallet-b":
        return "lending_principal_in", None
    if account_id == "income-interest-rg08":
        return "lending_interest", "income-category-interest-rg08"
    raise AssertionError(f"unexpected RG-08 posting account {account_id!r}")


def pending_candidate_payload(v1: dict) -> dict:
    """The frozen intake-proposed candidate payload, kept verbatim at
    confirmation (B-1 candidate append-only rule)."""
    candidate = v1["import_collection"]["candidate"]["expected"]["candidate"]
    return {
        "proposed_total_received": candidate["proposed_total_received"],
        "proposed_principal_amount": candidate["proposed_principal_amount"],
        "proposed_interest_amount": candidate["proposed_interest_amount"],
        "proposed_fee_amount": candidate["proposed_fee_amount"],
        "currency": candidate["currency"],
        "proposed_destination_account_id": candidate["proposed_destination_account_id"],
        "proposed_actual_receipt_at": candidate["proposed_actual_receipt_at"],
        "proposed_behavior_code": candidate["proposed_behavior_code"],
        "proposed_counterparty_id": candidate["proposed_counterparty_id"],
        "bank_evidence_proves_component_split": candidate["bank_evidence_proves_component_split"],
        "expected_interest_may_confirm_split": candidate["expected_interest_may_confirm_split"],
        "name_match_may_confirm_counterparty": candidate["name_match_may_confirm_counterparty"],
        "requires_confirmation": list(candidate["requires_confirmation"]),
        "rule_version": candidate["rule_version"],
    }


def report_record(period_type: str, period: str) -> dict:
    return {
        "period_type": period_type, "period": period,
        "metrics": [
            {"metric": metric, "applicability": "applicable", "currency": "CNY", "amount": "0.00"}
            for metric in RG08_METRICS
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


def convert_snapshot(v1: dict, runtime: dict, root_id: str, snapshot: dict,
                     state_id_value: str) -> dict:
    """Convert a frozen v1 complete snapshot (operation_baselines /
    canonical_states / resulting_state shape) into a v2 complete state.

    Confirmation operation_ids come from CONFIRMATION_CREATORS so that every
    state in every root carries the same creator identity (required by the
    retry cross-root contract-equivalence).  The import candidate payload is
    the intake-pending form in every state (B-1 candidate append-only rule).
    """
    catalog = build_catalog(v1)
    # The frozen v1 snapshots publish only the lending-story transactions; the
    # v2 complete states always carry the opening ledger first (its postings
    # are required for the complete balance replay).
    opening = v1["opening"]["transactions"][0]
    transactions: list[dict] = [{
        "id": opening["id"], "type": "opening_balance",
        "current_version_id": opening["current_version_id"],
    }]
    versions: list[dict] = [{
        "id": opening["current_version_id"], "transaction_id": opening["id"],
        "version_number": 1, "posting_set_id": opening["posting_set_id"],
        "occurred_at": opening["occurred_at"],
        "statistics_at": opening["statistics_at"],
        "effective_at": opening["occurred_at"],
    }]
    opening_postings = [
        {
            "id": posting["id"], "posting_set_id": opening["posting_set_id"],
            "account_id": posting["account_id"], "amount": posting["amount"],
            "currency": posting["currency"],
            "reconciliation_eligible": posting["reconciliation_eligible"],
        }
        for posting in opening["postings"]
    ]
    posting_sets: list[dict] = [{
        "id": opening["posting_set_id"],
        "posting_ids": [item["id"] for item in opening_postings],
    }]
    postings: list[dict] = list(opening_postings)
    for transaction in snapshot["transactions"]:
        tx_type = transaction_type(transaction)
        transactions.append({
            "id": transaction["id"], "type": tx_type,
            "current_version_id": transaction["current_version_id"],
        })
        set_postings = []
        for posting in transaction["postings"]:
            role, category_id = posting_role(tx_type, posting)
            item = {
                "id": posting["id"], "posting_set_id": transaction["posting_set_id"],
                "account_id": posting["account_id"], "amount": posting["amount"],
                "currency": posting["currency"],
                "reconciliation_eligible": posting["reconciliation_eligible"],
            }
            if role is not None:
                item["role"] = role
            if category_id is not None:
                item["category_id"] = category_id
            set_postings.append(item)
            postings.append(item)
        posting_sets.append({
            "id": transaction["posting_set_id"],
            "posting_ids": [item["id"] for item in set_postings],
        })
    for version in snapshot["versions"]:
        transaction = next(
            item for item in snapshot["transactions"]
            if item["id"] == version["transaction_id"]
        )
        item = {
            "id": version["id"], "transaction_id": version["transaction_id"],
            "version_number": 1, "posting_set_id": version["posting_set_id"],
            "occurred_at": transaction["occurred_at"],
            "statistics_at": transaction["statistics_at"],
            "effective_at": transaction["occurred_at"],
        }
        if "created_at" in version:
            item["created_at"] = version["created_at"]
        confirmation = next(
            (candidate for candidate in snapshot["confirmation_provenance"]
             if candidate["transaction_id"] == version["transaction_id"]),
            None,
        )
        if confirmation is not None:
            item["confirmation_id"] = confirmation["id"]
        versions.append(item)

    runtime_sources = {item["id"]: item for item in runtime["sources"]}
    sources = []
    for record in snapshot["source_records"]:
        facts = runtime_sources[record["id"]]
        payload = {
            "source_record_id": facts["source_record_id"],
            "observed_at": facts["observed_at"],
            "immutable_payload_hash": facts["immutable_payload_hash"],
        }
        for key in SOURCE_PAYLOAD_FIELDS:
            if key in facts:
                payload[key] = facts[key]
        if facts["kind"] == "bank_debit" and "account_id" not in payload:
            # RG08-DEV-01 precedent: the frozen lend debit source carries no
            # account anchor; the v2 schema requires one for bank_debit, so it
            # is derived from the frozen lend funding input anchor.
            payload["account_id"] = v1["lend"]["request"]["funding_account_id"]
        if facts["kind"] == "bank_credit" and "original_source_payload_hash" not in payload:
            # The frozen manual credit carries no original payload record (the
            # runtime projection omits the null hash); the v2 schema requires
            # the original hash for bank_credit, so the source record itself is
            # the original (import credit precedent: original == immutable).
            payload["original_source_payload_hash"] = payload["immutable_payload_hash"]
        sources.append({
            "id": facts["id"], "type": SOURCE_TYPE_BY_KIND[facts["kind"]],
            "payload": payload,
        })

    pending_payload = pending_candidate_payload(v1)
    candidates = []
    for candidate in snapshot["candidates"]:
        candidates.append({
            "id": candidate["id"], "type": "lending_collection_credit",
            "source_ids": list(candidate["source_ids"]),
            "confidence": candidate["confidence"],
            "payload": deepcopy(pending_payload),
            "status_history": [
                {"id": event["id"], "sequence": index + 1, "status": event["status"],
                 "occurred_at": event["occurred_at"],
                 "formal_effect_count": event["formal_effect_count"]}
                for index, event in enumerate(candidate["status_history"])
            ],
        })

    confirmations = []
    for entry in snapshot["confirmation_provenance"]:
        payload = {
            "confirmation_request_id": entry["confirmation_request_id"],
            "transaction_id": entry["transaction_id"],
            "counterparty_id": entry["counterparty_id"],
        }
        if "settlement_id" in entry:
            payload["settlement_id"] = entry["settlement_id"]
        if "candidate_id" in entry:
            payload["candidate_id"] = entry["candidate_id"]
        confirmations.append({
            "id": entry["id"], "type": entry["role"],
            "operation_id": CONFIRMATION_CREATORS[entry["id"]],
            "subject": {"kind": "transaction", "id": entry["transaction_id"]},
            "confirmed_at": entry["confirmed_at"],
            "payload": payload,
        })

    evidence = [
        {"id": item["id"], "type": item["type"],
         "source_ids": [item["source_id"]], "payload": {"observed_at": item["observed_at"]}}
        for item in snapshot["evidence"]
    ]
    evidence_links = [
        {"id": item["id"], "evidence_id": item["evidence_id"],
         "target_kind": LINK_TARGET_KIND[item["role"]],
         "target_id": item["target_id"], "role": item["role"]}
        for item in snapshot["evidence_links"]
    ]

    domain_entities = []
    for position in snapshot["positions"]:
        domain_entities.append({
            "id": position["id"], "type": "lending_position",
            "payload": {
                "counterparty_id": position["counterparty_id"],
                "position_scope": position["allocation_scope"],
                "contract_allocation_enabled": position["contract_allocation_enabled"],
                "receivable_account_id": position["receivable_account_id"],
                "principal_balance": position["principal_balance"],
                "currency": position["currency"],
                "history": [
                    {"id": event["id"], "sequence": index + 1,
                     "behavior_code": event["behavior_code"], "amount": event["amount"],
                     "principal_balance_after": event["principal_balance_after"],
                     "transaction_id": event["transaction_id"], "occurred_at": event["occurred_at"]}
                    for index, event in enumerate(position["history"])
                ],
            },
        })
    for settlement in snapshot["settlements"]:
        domain_entities.append({
            "id": settlement["id"], "type": "lending_settlement",
            "payload": {
                "behavior_code": settlement["behavior_code"],
                "counterparty_id": settlement["counterparty_id"],
                "linked_position_id": settlement["linked_position_id"],
                "allocated_lend_transaction_id": settlement["allocated_lend_transaction_id"],
                "transaction_id": settlement["transaction_id"],
                "destination_account_id": settlement["destination_account_id"],
                "interest_category_id": settlement["interest_category_id"],
                "total_received": settlement["total_received"],
                "currency": settlement["currency"],
                "actual_receipt_at": settlement["actual_receipt_at"],
                "confirmed_at": settlement["confirmed_at"],
                "components": [
                    {"id": component["id"], "kind": component["kind"],
                     "amount": component["amount"], "posting_id": component["posting_id"]}
                    for component in settlement["components"]
                ],
                "history": [
                    {"id": event["id"], "sequence": index + 1, "status": event["status"],
                     "occurred_at": event["occurred_at"],
                     "transaction_id": event["transaction_id"],
                     "formal_effect_count": event["formal_effect_count"]}
                    for index, event in enumerate(settlement["history"])
                ],
            },
        })

    relations = [
        {"id": mid(root_id, "relation", "$.counterparty_identity", entity["payload"]["counterparty_id"]),
         "type": "counterparty_lending_relationship",
         "member_refs": [{"kind": "domain_entity", "id": entity["id"]}],
         "payload": {"counterparty_id": entity["payload"]["counterparty_id"]}}
        for entity in domain_entities
        if entity["type"] == "lending_position"
    ]

    audit_links = []
    for link in snapshot["evidence_links"]:
        if "mirror_of_evidence_id" in link:
            audit_links.append({
                "id": mid(root_id, "audit_link", "$.import_collection.mirror_evidence",
                          "mirror_of_evidence_id"),
                "type": "mirror_of_evidence_id",
                "from": {"kind": "evidence", "id": link["evidence_id"]},
                "to": {"kind": "evidence", "id": link["mirror_of_evidence_id"]},
                "payload": {},
            })
            audit_links.append({
                "id": mid(root_id, "audit_link", "$.import_collection.mirror_evidence",
                          "merged_into_evidence_link_id"),
                "type": "merged_into_evidence_link_id",
                "from": {"kind": "evidence_link", "id": link["id"]},
                "to": {"kind": "evidence_link", "id": link["merged_into_evidence_link_id"]},
                "payload": {},
            })

    posting_reconciliations = []
    for posting_id, status in snapshot["reconciliation"].items():
        if status == "not_applicable" or posting_id.startswith("transaction-"):
            continue
        posting_reconciliations.append({
            "id": mid(root_id, "posting_reconciliation", "$.reconciliation", posting_id),
            "posting_id": posting_id, "status": status,
        })

    periods = sorted(snapshot.get("reports", {}).keys())
    if not periods:
        periods = ["2026-01", "cumulative"]
    reports = [
        report_record("cumulative" if period == "cumulative" else "month", period)
        for period in periods
    ]

    state = {
        "id": state_id_value, "root_id": root_id, "as_of_operation_id": None,
        "catalog": catalog, "transactions": transactions,
        "transaction_versions": versions, "posting_sets": posting_sets,
        "postings": postings, "sources": sources, "candidates": candidates,
        "confirmations": confirmations, "evidence": evidence,
        "evidence_links": evidence_links, "relations": relations,
        "domain_entities": domain_entities, "audit_links": audit_links,
        "posting_reconciliations": posting_reconciliations,
        "balances": [], "reports": reports, "derived_statuses": [],
    }
    refresh(state)
    refresh_statuses(state)
    return state


def opening_snapshot(v1: dict) -> dict:
    # The opening ledger is prepended by convert_snapshot to every state; the
    # snapshot shape itself carries no transactions/versions.
    return {
        "id": "opening",
        "transactions": [], "versions": [],
        "positions": [], "settlements": [], "candidates": [],
        "confirmation_provenance": [], "source_records": [],
        "evidence": [], "evidence_links": [],
        "balances": v1["opening"]["expected_balances"],
        "reports": {}, "reconciliation": {},
    }


def lend_input(v1: dict, runtime: dict) -> dict:
    request = v1["lend"]["request"]
    return {
        "variant": "lend", "request_id": request["request_id"],
        "behavior_code": request["behavior_code"],
        "counterparty_id": request["counterparty_id"],
        "funding_account_id": request["funding_account_id"],
        "principal_amount": request["principal_amount"],
        "currency": request["currency"],
        "actual_at": request["actual_at"],
        "confirmed_at": runtime["times"]["request-rg08-lend"]["confirmed_at"],
        "explicit_confirmation": True,
    }


def rename_input(v1: dict, runtime: dict) -> dict:
    rename = v1["counterparty_identity"]["rename"]
    return {
        "variant": "rename_counterparty",
        "request_id": rename["request_id"],
        "counterparty_id": rename["counterparty_id"],
        "old_display_name": runtime["counterparties"][rename["counterparty_id"]]["display_name"],
        "new_display_name": rename["new_display_name"],
        "name_history_id": runtime["ids"]["request-rg08-rename-counterparty"]["name_history_id"],
    }


def manual_collection_input(v1: dict) -> dict:
    request = v1["manual_collection"]["request"]
    return {
        "variant": "manual_collection", "request_id": request["request_id"],
        "behavior_code": request["behavior_code"],
        "counterparty_id": request["counterparty_id"],
        "linked_position_id": request["linked_position_id"],
        "allocated_lend_transaction_id": request["allocated_lend_transaction_id"],
        "destination_account_id": request["destination_account_id"],
        "total_received": request["total_received"],
        "principal_amount": request["principal_amount"],
        "interest_amount": request["interest_amount"],
        "fee_amount": request["fee_amount"],
        "interest_category_id": request["interest_category_id"],
        "currency": request["currency"],
        "actual_receipt_at": request["actual_receipt_at"],
        "confirmed_at": request["confirmed_at"],
        "explicit_confirmation": True,
    }


def maximum_allocation_input(v1: dict) -> dict:
    request = v1["principal_cap"]["maximum_valid_collection"]["input"]
    return {
        "variant": "maximum_allocation", "request_id": request["request_id"],
        "behavior_code": request["behavior_code"],
        "counterparty_id": request["counterparty_id"],
        "destination_account_id": request["destination_account_id"],
        "total_received": request["total_received"],
        "principal_amount": request["principal_amount"],
        "interest_amount": request["interest_amount"],
        "fee_amount": request["fee_amount"],
        "interest_category_id": request["interest_category_id"],
        "currency": request["currency"],
        "actual_receipt_at": request["actual_receipt_at"],
        "confirmed_at": request["confirmed_at"],
    }


def import_intake_input(v1: dict, runtime: dict) -> dict:
    candidate = v1["import_collection"]["candidate"]["expected"]["candidate"]
    ids = runtime["ids"]["source-rg08-import-credit"]
    return {
        "variant": "import_intake",
        "credit_source_id": "source-rg08-import-credit",
        "agreement_source_id": "source-rg08-agreement",
        "candidate_id": ids["candidate_id"],
        "candidate_type": ids["candidate_type"],
        "proposed_total_received": candidate["proposed_total_received"],
        "proposed_destination_account_id": candidate["proposed_destination_account_id"],
        "proposed_actual_receipt_at": candidate["proposed_actual_receipt_at"],
        "currency": candidate["currency"],
        "rule_version": candidate["rule_version"],
        "confidence": candidate["confidence"],
    }


def formal_confirmation_input(v1: dict) -> dict:
    request = v1["import_collection"]["confirmation"]["request"]
    return {
        "variant": "formal_confirmation",
        "request_id": request["request_id"],
        "candidate_id": request["candidate_id"],
        "behavior_code": request["behavior_code"],
        "counterparty_id": request["counterparty_id"],
        "destination_account_id": request["destination_account_id"],
        "principal_amount": request["principal_amount"],
        "interest_amount": request["interest_amount"],
        "fee_amount": request["fee_amount"],
        "interest_category_id": request["interest_category_id"],
        "currency": "CNY",
        "actual_receipt_at": request["actual_receipt_at"],
        "confirmed_at": request["confirmed_at"],
        "explicit_confirmation": True,
        "explicitly_confirmed_fields": list(request["explicitly_confirmed_fields"]),
    }


def mirror_merge_input(v1: dict, runtime: dict) -> dict:
    expected = v1["import_collection"]["mirror_evidence"]["expected"]
    source = expected["source_record"]
    link = expected["evidence_link"]
    return {
        "variant": "mirror_merge",
        "request_id": "request-rg08-merge-import-mirror",
        "source_id": source["id"],
        "observed_at": source["observed_at"],
        "amount": source["amount"],
        "currency": source["currency"],
        "mirror_of_source_id": source["mirror_of_source_id"],
        "target_posting_id": link["target_id"],
        "mirror_of_evidence_id": link["mirror_of_evidence_id"],
        "merged_into_evidence_link_id": link["merged_into_evidence_link_id"],
    }


def retry_input(anchor: str) -> dict:
    return {"variant": "retry", "input_anchor_id": anchor}


def settlement_attempt(v1_input: dict) -> dict:
    attempted = deepcopy(v1_input)
    if "binary_float_total" in attempted:
        attempted["total_received"] = attempted.pop("binary_float_total")
    return attempted


def canonical_field(action: str, reason: str) -> str:
    if reason == "unknown_account":
        return "funding_account_id" if action == "validate_lending_event" else "destination_account_id"
    return CANONICAL_REJECTION_FIELDS[reason]


def lend_returned_ids() -> list[dict]:
    return [
        {"kind": "transaction", "id": "transaction-lend-rg08"},
        {"kind": "transaction_version", "id": "version-lend-rg08-v1"},
        {"kind": "domain_entity", "id": "lending-position-rg08"},
    ]


def rename_returned_ids() -> list[dict]:
    return [
        {"kind": "counterparty", "id": "counterparty-rg08"},
        {"kind": "name_history", "id": "history-counterparty-rg08-rename"},
    ]


def manual_collection_returned_ids() -> list[dict]:
    return [
        {"kind": "transaction", "id": "transaction-collect-rg08-manual"},
        {"kind": "transaction_version", "id": "version-collect-rg08-manual-v1"},
        {"kind": "domain_entity", "id": "settlement-rg08-manual"},
        {"kind": "component", "id": "component-rg08-manual-principal"},
        {"kind": "component", "id": "component-rg08-manual-interest"},
        {"kind": "component", "id": "component-rg08-manual-fee"},
    ]


def maximum_allocation_returned_ids() -> list[dict]:
    return [
        {"kind": "transaction", "id": "transaction-collect-rg08-cap-maximum"},
        {"kind": "transaction_version", "id": "version-collect-rg08-cap-maximum-v1"},
        {"kind": "domain_entity", "id": "settlement-rg08-cap-maximum"},
        {"kind": "domain_entity", "id": "lending-position-rg08"},
    ]


def import_intake_returned_ids() -> list[dict]:
    return [
        {"kind": "source", "id": "source-rg08-import-credit"},
        {"kind": "candidate", "id": "candidate-rg08-import-collection"},
    ]


def formal_confirmation_returned_ids() -> list[dict]:
    return [
        {"kind": "candidate", "id": "candidate-rg08-import-collection"},
        {"kind": "transaction", "id": "transaction-collect-rg08-import"},
        {"kind": "transaction_version", "id": "version-collect-rg08-import-v1"},
        {"kind": "domain_entity", "id": "settlement-rg08-import"},
    ]


def mirror_merge_returned_ids() -> list[dict]:
    return [
        {"kind": "source", "id": "source-rg08-import-mirror"},
        {"kind": "evidence", "id": "evidence-rg08-import-mirror"},
        {"kind": "evidence_link", "id": "evidence-link-rg08-import-mirror"},
        {"kind": "posting", "id": "posting-collect-asset-rg08-import"},
    ]


# Retry id -> (anchor, owner operation id, owner returned ids).
RETRY_PLAN: dict[str, tuple[str, str, list[dict]]] = {
    "retry-rg08-request-lend": ("request-rg08-lend", ACCEPTED_OPERATION_IDS["lend"], lend_returned_ids()),
    "retry-rg08-source-lend-debit": ("source-rg08-lend-debit", ACCEPTED_OPERATION_IDS["lend"], [
        {"kind": "source", "id": "source-rg08-lend-debit"},
        {"kind": "evidence", "id": "evidence-rg08-lend-debit"},
        {"kind": "evidence_link", "id": "evidence-link-rg08-lend-debit"},
        {"kind": "posting", "id": "posting-lend-asset-rg08"},
    ]),
    "retry-rg08-request-rename-counterparty": (
        "request-rg08-rename-counterparty", ACCEPTED_OPERATION_IDS["rename_counterparty"], rename_returned_ids()),
    "retry-rg08-request-manual-collection": (
        "request-rg08-manual-collection", ACCEPTED_OPERATION_IDS["manual_collection"], manual_collection_returned_ids()),
    "retry-rg08-source-manual-confirmation": ("source-rg08-manual-confirmation", ACCEPTED_OPERATION_IDS["manual_collection"], [
        {"kind": "source", "id": "source-rg08-manual-confirmation"},
        {"kind": "transaction", "id": "transaction-collect-rg08-manual"},
        {"kind": "domain_entity", "id": "settlement-rg08-manual"},
    ]),
    "retry-rg08-source-manual-credit": ("source-rg08-manual-credit", ACCEPTED_OPERATION_IDS["manual_collection"], [
        {"kind": "source", "id": "source-rg08-manual-credit"},
        {"kind": "evidence", "id": "evidence-rg08-manual-credit"},
        {"kind": "evidence_link", "id": "evidence-link-rg08-manual-credit"},
        {"kind": "posting", "id": "posting-collect-asset-rg08-manual"},
    ]),
    "retry-rg08-request-cap-maximum": (
        "request-rg08-cap-maximum", ACCEPTED_OPERATION_IDS["maximum_allocation"], maximum_allocation_returned_ids()),
    "retry-rg08-source-import-credit": ("source-rg08-import-credit", ACCEPTED_OPERATION_IDS["import_intake"], [
        {"kind": "source", "id": "source-rg08-import-credit"},
        {"kind": "candidate", "id": "candidate-rg08-import-collection"},
    ]),
    "retry-rg08-source-agreement": ("source-rg08-agreement", ACCEPTED_OPERATION_IDS["import_intake"], [
        {"kind": "source", "id": "source-rg08-agreement"},
        {"kind": "evidence", "id": "evidence-rg08-agreement"},
        {"kind": "evidence_link", "id": "evidence-link-rg08-agreement"},
        {"kind": "domain_entity", "id": "lending-position-rg08"},
    ]),
    "retry-rg08-request-confirm-import": (
        "request-rg08-confirm-import", ACCEPTED_OPERATION_IDS["formal_confirmation"], formal_confirmation_returned_ids()),
    "retry-rg08-request-merge-import-mirror": (
        "request-rg08-merge-import-mirror", ACCEPTED_OPERATION_IDS["mirror_merge"], mirror_merge_returned_ids()),
    "retry-rg08-source-import-mirror": ("source-rg08-import-mirror", ACCEPTED_OPERATION_IDS["mirror_merge"], mirror_merge_returned_ids()),
}

# Owner root (locator, discriminator) per retry.  The frozen fixture treats
# lend/rename, manual collection, and the cap maximum as independent branches
# on the lend-confirmed baseline (the Kotlin oracle replays each operation on
# its own producer chain from the opening), so each has its own root.
RETRY_OWNER_ROOT = {
    "retry-rg08-request-lend": ("$.lend", "main"),
    "retry-rg08-source-lend-debit": ("$.lend", "main"),
    "retry-rg08-request-rename-counterparty": ("$.lend", "main"),
    "retry-rg08-request-manual-collection": ("$.manual_collection", "manual"),
    "retry-rg08-source-manual-confirmation": ("$.manual_collection", "manual"),
    "retry-rg08-source-manual-credit": ("$.manual_collection", "manual"),
    "retry-rg08-request-cap-maximum": ("$.principal_cap.maximum_valid_collection", "cap"),
    "retry-rg08-source-import-credit": ("$.import_collection", "import"),
    "retry-rg08-source-agreement": ("$.import_collection", "import"),
    "retry-rg08-request-confirm-import": ("$.import_collection", "import"),
    "retry-rg08-request-merge-import-mirror": ("$.import_collection", "import"),
    "retry-rg08-source-import-mirror": ("$.import_collection", "import"),
}


def retry_root_id(retry_id: str, owner_root_id: str) -> str:
    for suffix in RETRY_SUFFIX_CANDIDATES:
        candidate = deterministic_v2_root_id(
            CASE_ID, "$.idempotency.retries[*]", retry_id + suffix
        )
        if owner_root_id < candidate:
            return candidate
    raise AssertionError(
        f"no deterministic retry root discriminator satisfies owner ordering for {retry_id}"
    )


def counts(operation: dict, collection: str) -> tuple[int, int, int]:
    changes_by_id = operation["deltas"]["entity_changes"][collection]
    return (len(changes_by_id["added_ids"]), len(changes_by_id["changed_ids"]),
            len(changes_by_id["removed_ids"]))


def build_rg08_expected() -> dict:
    v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
    runtime = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
    roots: list[dict] = []
    states: list[dict] = []
    operations: list[dict] = []

    def root(purpose: str, locator: str, discriminator: str,
             initial: dict) -> tuple[dict, dict]:
        root_id = deterministic_v2_root_id(CASE_ID, locator, discriminator)
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

    # ---- Main root: opening -> lend -> rename (rename is a zero-effect
    # no_change on the lend-confirmed state, matching its frozen baseline).
    main_root_id = deterministic_v2_root_id(CASE_ID, "$.lend", "main")
    main_initial = convert_snapshot(
        v1, runtime, main_root_id, opening_snapshot(v1),
        state_id(main_root_id, "$.opening", "initial"))
    main_root, current = root("rg08_main", "$.lend", "main", main_initial)
    sequence = 0

    lend_confirmed = v1["operation_baselines"]["baseline-rg08-lend-confirmed"]
    manual_complete = v1["manual_collection"]["expected"]["canonical_state"]
    cap_resulting = v1["principal_cap"]["maximum_valid_collection"]["expected"]["resulting_state"]

    lend_result = convert_snapshot(
        v1, runtime, main_root_id, lend_confirmed,
        state_id(main_root_id, "$.lend", "lend"))
    lend_op = op(main_root["id"], 1, ACCEPTED_OPERATION_IDS["lend"],
                 "validate_lending_event", "creation", current, lend_result,
                 input_value=lend_input(v1, runtime),
                 returned=lend_returned_ids())
    current = emit(main_root, current, 1, lend_op, lend_result)

    rename_result = convert_snapshot(
        v1, runtime, main_root_id, lend_confirmed,
        state_id(main_root_id, "$.counterparty_identity.rename", "rename"))
    rename_op = op(main_root["id"], 2, ACCEPTED_OPERATION_IDS["rename_counterparty"],
                   "validate_lending_event", "update", current, rename_result,
                   input_value=rename_input(v1, runtime),
                   outcome={"status": "no_change", "reason_code": "zero_formal_effect"},
                   returned=rename_returned_ids())
    current = emit(main_root, current, 2, rename_op, rename_result)

    # ---- Manual collection root: independent branch on lend-confirmed.
    manual_root_id = deterministic_v2_root_id(CASE_ID, "$.manual_collection", "manual")
    manual_initial = convert_snapshot(
        v1, runtime, manual_root_id, lend_confirmed,
        state_id(manual_root_id, "$.manual_collection", "initial"))
    manual_root, current = root("rg08_manual_collection", "$.manual_collection",
                                "manual", manual_initial)
    manual_result = convert_snapshot(
        v1, runtime, manual_root_id, manual_complete,
        state_id(manual_root_id, "$.manual_collection", "manual"))
    manual_op = op(manual_root["id"], 1, ACCEPTED_OPERATION_IDS["manual_collection"],
                   "validate_lending_settlement", "creation", current, manual_result,
                   input_value=manual_collection_input(v1),
                   returned=manual_collection_returned_ids())
    current = emit(manual_root, current, 1, manual_op, manual_result)

    # ---- Cap maximum root: independent branch on lend-confirmed.
    cap_root_id = deterministic_v2_root_id(
        CASE_ID, "$.principal_cap.maximum_valid_collection", "cap")
    cap_initial = convert_snapshot(
        v1, runtime, cap_root_id, lend_confirmed,
        state_id(cap_root_id, "$.principal_cap.maximum_valid_collection", "initial"))
    cap_root, current = root("rg08_principal_cap_maximum",
                             "$.principal_cap.maximum_valid_collection", "cap",
                             cap_initial)
    cap_result = convert_snapshot(
        v1, runtime, cap_root_id, cap_resulting,
        state_id(cap_root_id, "$.principal_cap.maximum_valid_collection", "cap"))
    cap_op = op(cap_root["id"], 1, ACCEPTED_OPERATION_IDS["maximum_allocation"],
                "allocate_lending_collection", "creation", current, cap_result,
                input_value=maximum_allocation_input(v1),
                returned=maximum_allocation_returned_ids())
    current = emit(cap_root, current, 1, cap_op, cap_result)

    # ---- Principal-cap rejection root: lend-confirmed baseline plus the
    # over-balance allocate rejection.
    ob_root_id = deterministic_v2_root_id(
        CASE_ID, "$.principal_cap.over_balance_attempt", "over-balance")
    ob_initial = convert_snapshot(
        v1, runtime, ob_root_id, lend_confirmed,
        state_id(ob_root_id, "$.principal_cap.over_balance_attempt", "initial"))
    ob_root, current = root("rg08_principal_cap_rejection",
                            "$.principal_cap.over_balance_attempt", "over-balance",
                            ob_initial)
    over_balance = v1["principal_cap"]["over_balance_attempt"]
    over_balance_result = convert_snapshot(
        v1, runtime, ob_root_id, lend_confirmed,
        state_id(ob_root_id, "$.principal_cap.over_balance_attempt", "over-balance"))
    over_balance_op = rejected(
        current, over_balance_result, ob_root["id"], 1,
        over_balance["operation_context"]["operation_id"],
        over_balance["operation_context"]["operation_type"],
        deepcopy(over_balance["input"]),
        over_balance["expected"]["reason"],
        canonical_field("allocate_lending_collection", over_balance["expected"]["reason"]))
    current = emit(ob_root, current, 1, over_balance_op, over_balance_result)

    # ---- Invalid-input root: lend-confirmed baseline plus 18 rejected probes.
    invalid_root_id = deterministic_v2_root_id(CASE_ID, "$.invalid_inputs[*]", "invalid")
    invalid_initial = convert_snapshot(
        v1, runtime, invalid_root_id, lend_confirmed,
        state_id(invalid_root_id, "$.invalid_inputs[*]", "initial"))
    invalid_root, current = root("rg08_invalid_inputs", "$.invalid_inputs[*]",
                                 "invalid", invalid_initial)
    for index, node in enumerate(v1["invalid_inputs"], 1):
        operation_id = node["operation_context"]["operation_id"]
        action = node["operation_context"]["operation_type"]
        attempted = settlement_attempt(node["input"])
        if action == "validate_lending_event":
            attempted = deepcopy(node["input"])
        reason = node["expected"]["reason"]
        field = canonical_field(action, reason)
        result = convert_snapshot(
            v1, runtime, invalid_root_id, lend_confirmed,
            state_id(invalid_root_id, "$.invalid_inputs[*]", operation_id))
        operation = rejected(current, result, invalid_root["id"], index,
                             operation_id, action, attempted, reason, field)
        current = emit(invalid_root, current, index, operation, result)

    # ---- Import root: lend-confirmed baseline, intake, six incomplete
    # rejections, formal confirmation, mirror merge.
    import_root_id = deterministic_v2_root_id(CASE_ID, "$.import_collection", "import")
    import_initial = convert_snapshot(
        v1, runtime, import_root_id, lend_confirmed,
        state_id(import_root_id, "$.import_collection", "initial"))
    import_root, current = root("rg08_import_collection", "$.import_collection",
                                "import", import_initial)

    import_pending = v1["operation_baselines"]["baseline-rg08-import-pending"]
    import_confirmed = v1["canonical_states"]["import_confirmed"]
    import_mirror_complete = v1["canonical_states"]["import_mirror_complete"]

    intake_result = convert_snapshot(
        v1, runtime, import_root_id, import_pending,
        state_id(import_root_id, "$.import_collection.source_record", "intake"))
    intake_op = op(import_root["id"], 1, ACCEPTED_OPERATION_IDS["import_intake"],
                   "confirm_imported_lending_collection", "creation", current,
                   intake_result, input_value=import_intake_input(v1, runtime),
                   returned=import_intake_returned_ids())
    current = emit(import_root, current, 1, intake_op, intake_result)

    incomplete = v1["import_collection"]["incomplete_confirmations"]
    for index, node in enumerate(incomplete, 2):
        operation_id = node["operation_context"]["operation_id"]
        reason = node["expected"]["reason"]
        field = canonical_field("confirm_imported_lending_collection", reason)
        result = convert_snapshot(
            v1, runtime, import_root_id, import_pending,
            state_id(import_root_id, "$.import_collection.incomplete_confirmations[*]",
                     operation_id))
        operation = rejected(current, result, import_root["id"], index,
                             operation_id, "confirm_imported_lending_collection",
                             deepcopy(node["input"]), reason, field)
        current = emit(import_root, current, index, operation, result)

    formal_result = convert_snapshot(
        v1, runtime, import_root_id, import_confirmed,
        state_id(import_root_id, "$.import_collection.confirmation", "formal"))
    formal_op = op(import_root["id"], 8, ACCEPTED_OPERATION_IDS["formal_confirmation"],
                   "confirm_imported_lending_collection", "creation", current,
                   formal_result, input_value=formal_confirmation_input(v1),
                   returned=formal_confirmation_returned_ids())
    current = emit(import_root, current, 8, formal_op, formal_result)

    mirror_result = convert_snapshot(
        v1, runtime, import_root_id, import_mirror_complete,
        state_id(import_root_id, "$.import_collection.mirror_evidence", "mirror"))
    mirror_op = op(import_root["id"], 9, ACCEPTED_OPERATION_IDS["mirror_merge"],
                   "confirm_imported_lending_collection", "reconciliation", current,
                   mirror_result, input_value=mirror_merge_input(v1, runtime),
                   returned=mirror_merge_returned_ids())
    current = emit(import_root, current, 9, mirror_op, mirror_result)

    # ---- Retry roots: each retry replays one anchor owner in its own root.
    # The initial state is the anchored owner result payload id for id (B-1
    # retry contract-equivalence), with only state id, root id, and as_of
    # adjusted.  The root discriminator carries a deterministic suffix so the
    # owner root sorts before its retry root in the operation order.
    owner_results: dict[str, dict] = {
        ACCEPTED_OPERATION_IDS["lend"]: lend_result,
        ACCEPTED_OPERATION_IDS["rename_counterparty"]: rename_result,
        ACCEPTED_OPERATION_IDS["manual_collection"]: manual_result,
        ACCEPTED_OPERATION_IDS["maximum_allocation"]: cap_result,
        ACCEPTED_OPERATION_IDS["import_intake"]: intake_result,
        ACCEPTED_OPERATION_IDS["formal_confirmation"]: formal_result,
        ACCEPTED_OPERATION_IDS["mirror_merge"]: mirror_result,
    }
    for retry_id, (anchor, owner_id, returned) in RETRY_PLAN.items():
        owner_root_id = deterministic_v2_root_id(
            CASE_ID, *RETRY_OWNER_ROOT[retry_id])
        retry_root_identity = retry_root_id(retry_id, owner_root_id)
        initial = deepcopy(owner_results[owner_id])
        initial["id"] = state_id(retry_root_identity, "$.idempotency.retries[*]", "initial")
        initial["root_id"] = retry_root_identity
        initial["as_of_operation_id"] = None
        retry_root_item = {
            "id": retry_root_identity, "purpose": f"rg08_retry_{retry_id}",
            "initial_state_id": initial["id"], "operation_ids": [],
        }
        roots.append(retry_root_item)
        states.append(initial)
        retry_root, current = retry_root_item, initial
        result = clone(current, retry_id, "$.idempotency.retries[*]", retry_id)
        retry_op = op(retry_root["id"], 1, retry_id, "retry_idempotent_input",
                      "read", current, result, input_value=retry_input(anchor),
                      outcome={"status": "no_change", "reason_code": "idempotent_replay"},
                      returned=returned)
        current = emit(retry_root, current, 1, retry_op, result)

    roots.sort(key=lambda item: item["id"])
    states.sort(key=lambda item: item["id"])
    operations.sort(key=lambda item: (item["root_id"], item["sequence"]))
    return {
        "contract": "unifiedledger.golden-case", "contract_version": "2.0.0",
        "case": {"id": CASE_ID, "level": v1["case"]["level"],
                 "rule_version": v1["case"]["rule_version"],
                 "approval_status": "approved",
                 "ledger_id": v1["case"]["ledger_id"],
                 "timezone": v1["case"]["timezone"],
                 "currencies": [{"code": v1["case"]["currency"],
                                 "precision": v1["case"]["precision"]}]},
        "roots": roots, "states": states, "operations": operations,
    }


def write_rg08_expected() -> None:
    """Deterministic LF-only UTF-8 write (D-090 publication byte contract)."""
    with open(EXPECTED_PATH, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(build_rg08_expected(), ensure_ascii=False, indent=2) + "\n")


def v1_operation_documents(v1: dict) -> list[dict]:
    """The 44 frozen v1 operation-shaped documents in fixture order."""
    documents = []
    documents.append(v1["lend"])
    documents.append(v1["counterparty_identity"]["rename"])
    documents.append(v1["manual_collection"])
    documents.append(v1["principal_cap"]["maximum_valid_collection"])
    documents.append(v1["principal_cap"]["over_balance_attempt"])
    documents.append(v1["import_collection"]["source_record"])
    documents.extend(v1["import_collection"]["incomplete_confirmations"])
    documents.append(v1["import_collection"]["confirmation"])
    documents.append(v1["import_collection"]["mirror_evidence"])
    documents.extend(v1["invalid_inputs"])
    documents.extend(v1["idempotency"]["retries"])
    return documents


class RG08GoldenV2ExpectedTests(unittest.TestCase):
    """The v2 expected is exactly the 44 frozen v1 operations."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.expected = build_rg08_expected()

    def test_expected_artifact_matches_deterministic_builder(self):
        # Raw-byte equality: the committed artifact must be exactly the
        # deterministic builder output (json.dumps(ensure_ascii=False,
        # indent=2) + "\n", LF-only), not merely JSON-equal.
        on_disk = EXPECTED_PATH.read_bytes()
        expected_bytes = json.dumps(
            self.expected, ensure_ascii=False, indent=2
        ).encode("utf-8") + b"\n"
        self.assertEqual(on_disk, expected_bytes)
        self.assertEqual(json.loads(on_disk.decode("utf-8")), self.expected)

    def test_artifact_bytes_are_lf_only(self):
        raw = EXPECTED_PATH.read_bytes()
        self.assertEqual(raw.count(b"\r"), 0)
        self.assertIn(b"\n", raw)
        raw.decode("utf-8")

    def test_schema_and_semantic_validation(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validate_golden_case_v2(self.expected)

    def test_exact_inventory_and_v1_status_distribution(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        self.assertEqual(self.expected["case"]["approval_status"], "approved")
        self.assertEqual(len(self.expected["roots"]), 18)
        self.assertEqual(len(self.expected["states"]), 62)
        self.assertEqual(len(self.expected["operations"]), 44)
        status_counts = {
            status: sum(
                operation["outcome"]["status"] == status
                for operation in self.expected["operations"]
            )
            for status in ("accepted", "no_change", "rejected")
        }
        self.assertEqual(
            {"accepted": 6, "no_change": 13, "rejected": 25}, status_counts
        )
        variant_counts = {}
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] == "rejected":
                continue
            variant = operation["input"]["variant"]
            variant_counts[variant] = variant_counts.get(variant, 0) + 1
        self.assertEqual(variant_counts, {
            "lend": 1, "rename_counterparty": 1, "manual_collection": 1,
            "maximum_allocation": 1, "import_intake": 1,
            "formal_confirmation": 1, "mirror_merge": 1, "retry": 12,
        })
        # Frozen v1 operation-shaped counts independently derived from the
        # fixture: 18 invalid inputs, 1 over-balance attempt, 6 incomplete
        # confirmations, 12 retries.
        self.assertEqual(len(v1["invalid_inputs"]), 18)
        self.assertEqual(len(v1["import_collection"]["incomplete_confirmations"]), 6)
        self.assertEqual(len(v1["idempotency"]["retries"]), 12)
        self.assertEqual(len(v1_operation_documents(v1)), 44)

    def test_all_v1_operations_covered(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        self.assertEqual(len(by_id), 44)
        # Rejected and retry operations keep their frozen v1 operation ids.
        for node in v1["invalid_inputs"]:
            self.assertIn(node["operation_context"]["operation_id"], by_id)
        self.assertIn(v1["principal_cap"]["over_balance_attempt"]
                      ["operation_context"]["operation_id"], by_id)
        for node in v1["import_collection"]["incomplete_confirmations"]:
            self.assertIn(node["operation_context"]["operation_id"], by_id)
        for retry in v1["idempotency"]["retries"]:
            self.assertIn(retry["id"], by_id)
        for operation_id, (action, cls) in OPERATION_ACTION_CLASS.items():
            operation = by_id[operation_id]
            self.assertEqual(operation["action_type"], action, operation_id)
            self.assertEqual(operation["operation_class"], cls, operation_id)

    def test_deterministic_builder_repeatability(self):
        again = build_rg08_expected()
        self.assertEqual(
            json.dumps(self.expected, ensure_ascii=False, indent=2, sort_keys=True),
            json.dumps(again, ensure_ascii=False, indent=2, sort_keys=True),
        )

    def test_name_history_id_anchored_from_runtime_fixture(self):
        runtime = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
        expected_id = runtime["ids"]["request-rg08-rename-counterparty"]["name_history_id"]
        self.assertEqual(expected_id, "history-counterparty-rg08-rename")
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        rename = by_id[ACCEPTED_OPERATION_IDS["rename_counterparty"]]
        self.assertEqual(rename["input"]["name_history_id"], expected_id)
        self.assertIn({"kind": "name_history", "id": expected_id}, rename["returned_ids"])
        retry = by_id["retry-rg08-request-rename-counterparty"]
        self.assertEqual(retry["input"]["input_anchor_id"], "request-rg08-rename-counterparty")
        self.assertEqual(retry["returned_ids"], rename["returned_ids"])

    def test_rejected_field_paths_match_kotlin_oracle_field_names(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        distinct_paths = set()
        for operation in self.expected["operations"]:
            if operation["outcome"]["status"] != "rejected":
                continue
            action = operation["action_type"]
            reason = operation["outcome"]["reason_code"]
            field_path = operation["outcome"]["field_path"]
            # The v2 schema/validator root every rejected path at
            # attempted_input (D-090); the Kotlin oracle roots the six gates
            # and the allocate rejection at input in its runtime vocabulary.
            self.assertTrue(field_path.startswith("$.attempted_input."), operation["id"])
            field = field_path.split(".", 2)[2]
            self.assertEqual(
                field, KOTLIN_FIELD_NAMES[(action, reason)],
                f"{operation['id']}: {action}/{reason} must match the Kotlin oracle field name",
            )
            distinct_paths.add(field_path)
        self.assertEqual(distinct_paths, REGISTERED_ATTEMPTED_PATHS)
        # D-090 canonical mirrors asserted explicitly.
        self.assertEqual(
            by_id["operation-rg08-negative-interest"]["outcome"]["field_path"],
            "$.attempted_input.principal_amount",
        )
        self.assertEqual(
            by_id["operation-rg08-guessed-split"]["outcome"]["field_path"],
            "$.attempted_input.split_source",
        )

    def test_errata_regressions(self):
        """D-090 option (a): formal_confirmation creates the destination
        reconciliation matched; mirror_merge adds no reconciliation change."""
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        mirror = by_id[ACCEPTED_OPERATION_IDS["mirror_merge"]]
        self.assertEqual(counts(mirror, "posting_reconciliations"), (0, 0, 0))
        formal = by_id[ACCEPTED_OPERATION_IDS["formal_confirmation"]]
        self.assertEqual(counts(formal, "posting_reconciliations"), (1, 0, 0))
        added = formal["deltas"]["entity_changes"]["posting_reconciliations"]["added_ids"]
        states = {item["id"]: item for item in self.expected["states"]}
        result = states[formal["result_state_id"]]
        record = next(item for item in result["posting_reconciliations"]
                      if item["id"] == added[0])
        self.assertEqual(record["posting_id"], "posting-collect-asset-rg08-import")
        self.assertEqual(record["status"], "matched")

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

    def test_accepted_operations_match_registered_entity_counts(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        for operation_id, operation in by_id.items():
            if operation["outcome"]["status"] != "accepted":
                continue
            registered = golden_v2._rg08_effect_counts(operation, "$")
            for collection, required in registered.items():
                self.assertEqual(counts(operation, collection), required,
                                 f"{operation_id} {collection}")
            for collection in ("transactions", "transaction_versions",
                               "posting_sets", "postings", "confirmations",
                               "sources", "candidates", "evidence",
                               "evidence_links", "relations", "domain_entities",
                               "audit_links", "posting_reconciliations"):
                if collection not in registered:
                    self.assertEqual(counts(operation, collection), (0, 0, 0),
                                     f"{operation_id} {collection}")
            # Every accepted operation must declare a state or intake effect.
            self.assertTrue(any(
                values
                for changes_by_id in operation["deltas"]["entity_changes"].values()
                for values in changes_by_id.values()
            ), operation_id)

    def test_retry_anchors_resolve_exactly_one_owner(self):
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        owner_ids = set(ACCEPTED_OPERATION_IDS.values())
        for retry_id, (anchor, owner_id, returned) in RETRY_PLAN.items():
            retry = by_id[retry_id]
            self.assertEqual(retry["outcome"]["status"], "no_change")
            self.assertEqual(retry["input"]["input_anchor_id"], anchor)
            self.assertEqual(retry["returned_ids"], returned, retry_id)
            owner = by_id[owner_id]
            # The retry baseline is contract-equivalent to the owner result
            # payload (cross-root, id/root/as_of stripped).
            owner_payload = golden_v2._state_payload(
                states[owner["result_state_id"]])
            owner_payload.pop("root_id", None)
            retry_payload = golden_v2._state_payload(
                states[retry["baseline_state_id"]])
            retry_payload.pop("root_id", None)
            self.assertEqual(owner_payload, retry_payload, retry_id)
            self.assertEqual(owner_ids & {retry["root_id"]}, set())

    def test_frozen_snapshot_pairs_match(self):
        """The frozen retry baselines equal the anchor result snapshots except
        for the snapshot id, so the id-for-id retry baseline construction is
        faithful to the frozen fixture."""
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        ob = v1["operation_baselines"]
        cs = v1["canonical_states"]
        lend_confirmed = ob["baseline-rg08-lend-confirmed"]
        import_pending = ob["baseline-rg08-import-pending"]
        pairs = [
            (ob["baseline-rg08-retry-request-lend"], lend_confirmed),
            (ob["baseline-rg08-retry-source-lend-debit"], lend_confirmed),
            (ob["baseline-rg08-retry-request-rename-counterparty"], lend_confirmed),
            (ob["baseline-rg08-retry-request-manual-collection"],
             v1["manual_collection"]["expected"]["canonical_state"]),
            (ob["baseline-rg08-retry-source-manual-confirmation"],
             v1["manual_collection"]["expected"]["canonical_state"]),
            (ob["baseline-rg08-retry-source-manual-credit"],
             v1["manual_collection"]["expected"]["canonical_state"]),
            (ob["baseline-rg08-retry-request-cap-maximum"],
             v1["principal_cap"]["maximum_valid_collection"]["expected"]["resulting_state"]),
            (ob["baseline-rg08-retry-source-import-credit"], import_pending),
            (ob["baseline-rg08-retry-source-agreement"], import_pending),
            (ob["baseline-rg08-retry-request-confirm-import"], cs["import_confirmed"]),
            (ob["baseline-rg08-retry-request-merge-import-mirror"], cs["import_mirror_complete"]),
            (ob["baseline-rg08-retry-source-import-mirror"], cs["import_mirror_complete"]),
        ]
        for retry_baseline, owner_result in pairs:
            self.assertEqual(
                {key: value for key, value in retry_baseline.items() if key != "id"},
                {key: value for key, value in owner_result.items() if key != "id"},
            )

    def test_runtime_fixture_supplement_binding(self):
        runtime = json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
        states = {item["id"]: item for item in self.expected["states"]}
        runtime_sources = {source["id"]: source for source in runtime["sources"]}
        artifact_sources = {
            source["id"]
            for state in self.expected["states"]
            for source in state["sources"]
        }
        # Every runtime fixture source is represented in the artifact.
        self.assertEqual(artifact_sources, set(runtime_sources))
        # Source payloads are read from the runtime fixture, not re-encoded.
        for state in self.expected["states"]:
            for source in state["sources"]:
                facts = runtime_sources[source["id"]]
                self.assertEqual(source["payload"]["source_record_id"],
                                 facts["source_record_id"], source["id"])
                self.assertEqual(source["payload"]["observed_at"],
                                 facts["observed_at"], source["id"])
                self.assertEqual(source["payload"]["immutable_payload_hash"],
                                 facts["immutable_payload_hash"], source["id"])
        # The lend debit source carries the funding account anchor derived
        # from the frozen lend input (RG08-DEV-01 ruling).
        for state in self.expected["states"]:
            for source in state["sources"]:
                if source["id"] == "source-rg08-lend-debit":
                    self.assertEqual(source["payload"]["account_id"], "asset-bank-a")
        # Counterparty display names: the runtime fixture supplies the
        # pre-rename name; the frozen fixture supplies the post-rename name.
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        rename = by_id[ACCEPTED_OPERATION_IDS["rename_counterparty"]]
        self.assertEqual(rename["input"]["old_display_name"],
                         runtime["counterparties"]["counterparty-rg08"]["display_name"])
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        self.assertEqual(rename["input"]["new_display_name"],
                         v1["counterparty_identity"]["rename"]["new_display_name"])
        # The confirmed_at supplement binds the lend confirmation and version.
        lend = by_id[ACCEPTED_OPERATION_IDS["lend"]]
        confirmed_at = runtime["times"]["request-rg08-lend"]["confirmed_at"]
        self.assertEqual(lend["input"]["confirmed_at"], confirmed_at)
        lend_result = states[lend["result_state_id"]]
        confirmation = next(
            item for item in lend_result["confirmations"]
            if item["id"] == "confirmation-rg08-lend"
        )
        self.assertEqual(confirmation["confirmed_at"], confirmed_at)

    def test_formal_confirmation_candidate_keeps_intake_payload(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        states = {item["id"]: item for item in self.expected["states"]}
        formal = by_id[ACCEPTED_OPERATION_IDS["formal_confirmation"]]
        result = states[formal["result_state_id"]]
        candidate = next(
            item for item in result["candidates"]
            if item["id"] == "candidate-rg08-import-collection"
        )
        pending = v1["import_collection"]["candidate"]["expected"]["candidate"]
        self.assertEqual(
            candidate["payload"]["requires_confirmation"],
            list(pending["requires_confirmation"]),
        )
        self.assertEqual(
            [event["status"] for event in candidate["status_history"]],
            ["pending_confirmation", "confirmed"],
        )
        self.assertEqual([event["formal_effect_count"] for event in candidate["status_history"]], [0, 1])

    def test_lending_positions_and_reconciliation_are_v1_faithful(self):
        v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        states = {item["id"]: item for item in self.expected["states"]}
        by_id = {operation["id"]: operation for operation in self.expected["operations"]}
        manual = by_id[ACCEPTED_OPERATION_IDS["manual_collection"]]
        manual_state = states[manual["result_state_id"]]
        position = next(
            item for item in manual_state["domain_entities"]
            if item["type"] == "lending_position"
        )
        self.assertEqual(position["payload"]["principal_balance"], "60.00")
        self.assertEqual(
            [event["behavior_code"] for event in position["payload"]["history"]],
            ["lend", "collect"],
        )
        cap = by_id[ACCEPTED_OPERATION_IDS["maximum_allocation"]]
        cap_state = states[cap["result_state_id"]]
        cap_position = next(
            item for item in cap_state["domain_entities"]
            if item["type"] == "lending_position"
        )
        self.assertEqual(cap_position["payload"]["principal_balance"], "0.00")
        # Cap maximum allocation has no evidence: the destination posting
        # reconciliation stays pending (frozen v1 reconciliation map).
        destination = next(
            item for item in cap_state["posting_reconciliations"]
            if item["posting_id"] == "posting-collect-asset-rg08-cap"
        )
        self.assertEqual(destination["status"], "pending")
        # Manual collection evidence proves the destination posting matched.
        manual_destination = next(
            item for item in manual_state["posting_reconciliations"]
            if item["posting_id"] == "posting-collect-asset-rg08-manual"
        )
        self.assertEqual(manual_destination["status"], "matched")


if __name__ == "__main__":
    unittest.main()
