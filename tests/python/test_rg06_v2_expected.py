import json
import unittest
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

import golden_cases.v2 as golden_v2
from golden_cases import validate_golden_case_v2

from tests.python.test_golden_v2_rg06_operations import (
    creation_result,
    deposit_result,
    final_result,
)
from tests.python.test_golden_v2_rg06_semantics import staged_payment_state


ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = ROOT / "golden" / "rules" / "rg-06.json"
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-06-expected.json"
ENTITY_COLLECTIONS = tuple(golden_v2._ENTITY_COLLECTIONS)
REPORT_METRICS = (
    "balance_adjustment_net_worth_change",
    "budget",
    "cash_inflow",
    "cash_outflow",
    "consumption",
    "income",
    "internal_transfer_amount",
    "net_worth_change",
    "ordinary_expense",
    "ordinary_income",
)


def _replace(value, replacements):
    if isinstance(value, str):
        return replacements.get(value, value)
    if isinstance(value, list):
        return [_replace(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: _replace(child, replacements) for key, child in value.items()}
    return value


def _fixture_catalog(fixture):
    accounts = []
    for account in fixture["catalog"]["accounts"]:
        accounts.append(
            {
                "id": account["id"],
                "name": account["name"],
                "kind": account["kind"],
                "currency": fixture["case"]["currency"],
                "owned_by_user": account["owned_by_user"],
                "real_account": account["real_account"],
                "reconciliation_eligible": bool(
                    account["owned_by_user"]
                    and account["real_account"]
                    and account["kind"] in {"asset", "liability"}
                ),
            }
        )
    categories = [
        {
            "id": category["id"],
            "name": category["name"],
            "parent_id": category["parent_id"],
            "posting_account_id": category["posting_account_id"],
            "active": category["active"],
        }
        for category in fixture["catalog"]["categories"]
    ]
    return {"accounts": accounts, "categories": categories}


def _config(name, root_id):
    return {
        "name": name,
        "root_id": root_id,
        "relation_id": f"association-group-rg06-{name}",
        "lifecycle_id": f"lifecycle-rg06-{name}",
        "deposit_id": f"payment-rg06-{name}-deposit",
        "final_id": f"payment-rg06-{name}-final",
        "deposit_transaction_id": f"tx-rg06-{name}-deposit",
        "final_transaction_id": f"tx-rg06-{name}-final",
        "deposit_version_id": f"version-rg06-{name}-deposit-v1",
        "final_version_id": f"version-rg06-{name}-final-v1",
        "deposit_set_id": f"posting-set-rg06-{name}-deposit",
        "final_set_id": f"posting-set-rg06-{name}-final",
        "deposit_expense_posting_id": f"posting-expense-rg06-{name}-deposit",
        "deposit_asset_posting_id": f"posting-asset-rg06-{name}-deposit",
        "final_expense_posting_id": f"posting-expense-rg06-{name}-final",
        "final_asset_posting_id": f"posting-asset-rg06-{name}-final",
        "deposit_reconciliation_id": f"reconciliation-rg06-{name}-deposit",
        "final_reconciliation_id": f"reconciliation-rg06-{name}-final",
    }


def _replacement_map(config):
    return {
        "root-rg06-semantics": config["root_id"],
        "asset-bank": "asset-bank-a",
        "expense-service": "expense-account-service",
        "category-services": "expense-category-service-parent",
        "category-service": "expense-category-service",
        "relation-staged-payment": config["relation_id"],
        "lifecycle-staged-payment": config["lifecycle_id"],
        "installment-deposit": config["deposit_id"],
        "installment-final": config["final_id"],
        "transaction-deposit": config["deposit_transaction_id"],
        "transaction-final": config["final_transaction_id"],
        "version-deposit": config["deposit_version_id"],
        "version-final": config["final_version_id"],
        "posting-set-deposit": config["deposit_set_id"],
        "posting-set-final": config["final_set_id"],
        "posting-expense-deposit": config["deposit_expense_posting_id"],
        "posting-asset-deposit": config["deposit_asset_posting_id"],
        "posting-expense-final": config["final_expense_posting_id"],
        "posting-asset-final": config["final_asset_posting_id"],
        "reconciliation-rg06-deposit": config["deposit_reconciliation_id"],
        "reconciliation-rg06-final": config["final_reconciliation_id"],
    }


def _opening_components():
    transaction = {
        "id": "tx-opening-rg06",
        "type": "opening_balance",
        "current_version_id": "version-opening-rg06",
    }
    version = {
        "id": "version-opening-rg06",
        "transaction_id": "tx-opening-rg06",
        "version_number": 1,
        "posting_set_id": "posting-set-opening-rg06",
        "occurred_at": "2026-01-01T00:00:00+08:00",
        "statistics_at": "2026-01-01T00:00:00+08:00",
        "effective_at": "2026-01-01T00:00:00+08:00",
    }
    posting_set = {
        "id": "posting-set-opening-rg06",
        "posting_ids": [
            "posting-opening-bank-rg06",
            "posting-opening-equity-rg06",
        ],
    }
    postings = [
        {
            "id": "posting-opening-bank-rg06",
            "posting_set_id": "posting-set-opening-rg06",
            "account_id": "asset-bank-a",
            "amount": "1000.00",
            "currency": "CNY",
            "reconciliation_eligible": False,
        },
        {
            "id": "posting-opening-equity-rg06",
            "posting_set_id": "posting-set-opening-rg06",
            "account_id": "equity-opening-a",
            "amount": "-1000.00",
            "currency": "CNY",
            "reconciliation_eligible": False,
        },
    ]
    return transaction, version, posting_set, postings


def _mapped_helper_state(helper_state, fixture, config):
    state = _replace(deepcopy(helper_state), _replacement_map(config))
    state["catalog"] = _fixture_catalog(fixture)
    state["root_id"] = config["root_id"]
    opening_transaction, opening_version, opening_set, opening_postings = (
        _opening_components()
    )
    state["transactions"] = [opening_transaction, *state["transactions"]]
    state["transaction_versions"] = [opening_version, *state["transaction_versions"]]
    state["posting_sets"] = [opening_set, *state["posting_sets"]]
    state["postings"] = [*opening_postings, *state["postings"]]
    return state


def _current_entries(state):
    indexes = golden_v2._state_indexes(state, "$.state")
    current = {}
    for transaction in state["transactions"]:
        version = indexes["transaction_versions"][transaction["current_version_id"]]
        posting_set = indexes["posting_sets"][version["posting_set_id"]]
        current[transaction["id"]] = (
            transaction,
            version,
            [indexes["postings"][posting_id] for posting_id in posting_set["posting_ids"]],
        )
    return indexes, current


def _refresh_projections(state):
    indexes, current = _current_entries(state)
    accounts = indexes["catalog_accounts"]
    balances = {account_id: Decimal("0.00") for account_id in accounts}
    for _, _, postings in current.values():
        for posting in postings:
            balances[posting["account_id"]] += Decimal(posting["amount"])
    state["balances"] = [
        {"account_id": account_id, "currency": "CNY", "amount": f"{amount:.2f}"}
        for account_id, amount in balances.items()
    ]

    report = {"period_type": "cumulative", "period": "lifecycle", "metrics": []}
    values = golden_v2._report_values(current, accounts, report, "CNY")
    for metric in REPORT_METRICS:
        report["metrics"].append(
            {
                "metric": metric,
                "applicability": "applicable",
                "currency": "CNY",
                "amount": f"{values[metric]:.2f}",
            }
        )
    state["reports"] = [report]

    reconciliation_by_posting = {
        item["posting_id"]: item["status"]
        for item in state["posting_reconciliations"]
    }
    expected_statuses = golden_v2._expected_derived_statuses(
        state, indexes, current, reconciliation_by_posting
    )
    state["derived_statuses"] = [
        {
            "id": f"status-{kind}-{target_id}-{status_name}",
            "target_kind": kind,
            "target_id": target_id,
            "status_name": status_name,
            "value": value,
        }
        for (kind, target_id, status_name), value in sorted(expected_statuses.items())
    ]


def _state_identity(state, state_id, root_id, operation_id=None):
    state["id"] = state_id
    state["root_id"] = root_id
    state["as_of_operation_id"] = operation_id
    _refresh_projections(state)
    return state


def _opening_state(fixture, config):
    state = _mapped_helper_state(staged_payment_state(), fixture, config)
    state["transactions"] = state["transactions"][:1]
    state["transaction_versions"] = state["transaction_versions"][:1]
    state["posting_sets"] = state["posting_sets"][:1]
    state["postings"] = state["postings"][:2]
    for collection in (
        "sources",
        "candidates",
        "confirmations",
        "evidence",
        "evidence_links",
        "relations",
        "domain_entities",
        "audit_links",
        "posting_reconciliations",
    ):
        state[collection] = []
    return state


def _group_state(fixture, config):
    return _mapped_helper_state(creation_result(), fixture, config)


def _preserve_manual_history_ids(state, fixture):
    ordered = {
        item["id"]: item for item in fixture["manual_path"]["ordered_operations"]
    }
    history_ids = [
        ordered["create-group"]["expected"]["group"]["state_history"][0]["id"],
        ordered["save-deposit"]["expected"]["group"]["state_history"][-1]["id"],
        ordered["mark-fulfilled"]["expected"]["group"]["state_history"][-1]["id"],
        ordered["save-final"]["expected"]["group"]["state_history"][-1]["id"],
        ordered["confirm-completion"]["expected"]["group"]["state_history"][-1]["id"],
    ]
    for entity in state["domain_entities"]:
        for history in entity["payload"].get("state_history", []):
            history["id"] = history_ids[history["sequence"] - 1]
    return state


def _bind_manual_confirmations(state, operation_ids):
    for confirmation in state["confirmations"]:
        role = confirmation["id"].removeprefix("confirmation-")
        operation_id = operation_ids[role]
        confirmation["operation_id"] = operation_id
        confirmation["subject"] = {"kind": "operation", "id": operation_id}


def _manual_source_evidence(state, config, role):
    payment_id = config[f"{role}_id"]
    posting_id = config[f"{role}_asset_posting_id"]
    observed_at = (
        "2026-04-28T10:00:00+08:00"
        if role == "deposit"
        else "2026-05-03T16:30:00+08:00"
    )
    source_id = f"source-rg06-manual-{role}-bank"
    evidence_id = f"evidence-rg06-manual-{role}"
    state["sources"].append(
        {
            "id": source_id,
            "type": "staged_payment_bank_fact",
            "payload": {
                "amount": "80.00" if role == "deposit" else "220.00",
                "currency": "CNY",
                "observed_at": observed_at,
            },
        }
    )
    state["evidence"].append(
        {
            "id": evidence_id,
            "type": "staged_payment_bank_payment",
            "source_ids": [source_id],
            "payload": {"payment_id": payment_id, "observed_at": observed_at},
        }
    )
    state["evidence_links"].append(
        {
            "id": f"match-rg06-manual-{role}",
            "evidence_id": evidence_id,
            "target_kind": "posting",
            "target_id": posting_id,
            "role": "payment_asset_posting",
        }
    )
    reconciliation = next(
        item
        for item in state["posting_reconciliations"]
        if item["posting_id"] == posting_id
    )
    reconciliation["status"] = "matched"


def _append_history_payment(state, config, role, occurred_at):
    lifecycle = next(
        item
        for item in state["domain_entities"]
        if item["id"] == config["lifecycle_id"]
    )
    payload = lifecycle["payload"]
    latest = payload["state_history"][-1]
    amount = Decimal("80.00") if role == "deposit" else Decimal("220.00")
    paid = Decimal(latest["paid_amount"]) + amount
    due = Decimal(payload["total_amount"]) - paid
    progress = "paid_in_full" if due == 0 else "partially_paid"
    payload["state_history"].append(
        {
            "id": f"history-rg06-{config['name']}-{role}",
            "sequence": len(payload["state_history"]) + 1,
            "event": "payment_confirmed",
            "occurred_at": occurred_at,
            "total_amount": payload["total_amount"],
            "paid_amount": f"{paid:.2f}",
            "due_amount": f"{due:.2f}",
            "payment_id": config[f"{role}_id"],
            "payment_progress": progress,
            "fulfillment_status": latest["fulfillment_status"],
            "state_transition_effect_count": 0,
        }
    )
    payload["paid_amount"] = f"{paid:.2f}"
    payload["due_amount"] = f"{due:.2f}"


def _append_import_payment(
    state, fixture, config, role, candidate_id, operation_id, confirmed_at
):
    helper = _mapped_helper_state(staged_payment_state(), fixture, config)
    payment_id = config[f"{role}_id"]
    transaction_id = config[f"{role}_transaction_id"]
    version_id = config[f"{role}_version_id"]
    set_id = config[f"{role}_set_id"]
    posting_ids = {
        config[f"{role}_expense_posting_id"],
        config[f"{role}_asset_posting_id"],
    }
    state["transactions"].append(
        next(item for item in helper["transactions"] if item["id"] == transaction_id)
    )
    state["transaction_versions"].append(
        next(item for item in helper["transaction_versions"] if item["id"] == version_id)
    )
    state["posting_sets"].append(
        next(item for item in helper["posting_sets"] if item["id"] == set_id)
    )
    state["postings"].extend(
        item for item in helper["postings"] if item["id"] in posting_ids
    )
    payment = deepcopy(
        next(item for item in helper["domain_entities"] if item["id"] == payment_id)
    )
    source_time = next(
        candidate["payload"]["source_payment_at"]
        for candidate in state["candidates"]
        if candidate["id"] == candidate_id
    )
    payment["payload"]["source_payment_at"] = source_time
    state["domain_entities"].append(payment)
    state["relations"][0]["member_refs"].append(
        {"kind": "domain_entity", "id": payment_id}
    )

    candidate = next(item for item in state["candidates"] if item["id"] == candidate_id)
    candidate["status_history"].append(
        {
            "id": f"candidate-status-{candidate_id}-confirmed",
            "sequence": 2,
            "status": "confirmed",
        }
    )
    confirmation_id = f"confirmation-{candidate_id}"
    state["confirmations"].append(
        {
            "id": confirmation_id,
            "type": "candidate_confirmation",
            "operation_id": operation_id,
            "subject": {"kind": "candidate", "id": candidate_id},
            "confirmed_at": confirmed_at,
            "payload": {},
        }
    )
    version = next(item for item in state["transaction_versions"] if item["id"] == version_id)
    version["confirmation_id"] = confirmation_id
    evidence_id = candidate["payload"]["evidence_ref"]
    evidence = next(item for item in state["evidence"] if item["id"] == evidence_id)
    evidence["payload"]["payment_id"] = payment_id
    state["evidence_links"].append(
        {
            "id": f"match-rg06-import-{role}",
            "evidence_id": evidence_id,
            "target_kind": "posting",
            "target_id": config[f"{role}_asset_posting_id"],
            "role": "payment_asset_posting",
        }
    )
    state["posting_reconciliations"].append(
        {
            "id": config[f"{role}_reconciliation_id"],
            "posting_id": config[f"{role}_asset_posting_id"],
            "status": "matched",
        }
    )
    _append_history_payment(state, config, role, source_time)


def _candidate_record(record, ambiguous=False):
    role = record.get("suggested_payment_role")
    amount = f"{abs(Decimal(record['amount'])):.2f}"
    payload = {
        "payment_role": None if ambiguous else role,
        "amount": amount,
        "currency": record["currency"],
        "source_payment_at": record["source_payment_at"],
        "evidence_ref": record["evidence_id"],
        "provenance": {"rule": "staged_payment_bank_fact", "rule_version": 1},
        "requires_confirmation": [
            "relation_id",
            "payment_role",
            "category_id",
            "funding_account_id",
        ],
    }
    if ambiguous:
        payload["guessed_payment_role"] = None
    return {
        "id": f"candidate-{record['id'].removeprefix('source-')}",
        "type": "staged_payment",
        "source_ids": [record["id"]],
        "confidence": "0.50" if ambiguous else "1.00",
        "payload": payload,
        "status_history": [
            {
                "id": f"candidate-status-{record['id']}-pending",
                "sequence": 1,
                "status": "pending_confirmation",
            }
        ],
    }


def _append_intake(state, record, ambiguous=False):
    source_payload = {
        "amount": record["amount"],
        "currency": record["currency"],
        "source_payment_at": record["source_payment_at"],
    }
    if record.get("mirror_of_source_id") is not None:
        source_payload["mirror_of_source_id"] = record["mirror_of_source_id"]
    state["sources"].append(
        {
            "id": record["id"],
            "type": "staged_payment_bank_fact",
            "payload": source_payload,
        }
    )
    evidence_payload = {"source_payment_at": record["source_payment_at"]}
    if record.get("mirror_of_source_id"):
        evidence_payload.update(
            {
                "payment_id": record["payment_id"],
                "mirror_of_evidence_id": record["mirror_of_evidence_id"],
                "merged_into_evidence_link_id": record[
                    "merged_into_evidence_link_id"
                ],
            }
        )
    state["evidence"].append(
        {
            "id": record["evidence_id"],
            "type": "staged_payment_bank_payment",
            "source_ids": [record["id"]],
            "payload": evidence_payload,
        }
    )
    if not record.get("mirror_of_source_id"):
        state["candidates"].append(_candidate_record(record, ambiguous))


def _operation_deltas(baseline, result):
    value_changes = {
        "balances": [
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
        ],
        "reports": [
            {
                "key": {
                    "period_type": key[0],
                    "period": key[1],
                    "metric": key[2],
                    **({"currency": key[3]} if key[3] is not None else {}),
                },
                "before": old,
                "after": new,
            }
            for key, (old, new) in sorted(
                golden_v2._changes(
                    golden_v2._report_map(baseline), golden_v2._report_map(result)
                ).items()
            )
        ],
        "derived_statuses": [
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
        ],
    }
    status_changes = [
        {
            "target_kind": item["key"]["kind"],
            "target_id": item["key"]["target_id"],
            "status_name": item["key"]["status_name"],
            "before": item["before"],
            "after": item["after"],
        }
        for item in value_changes["derived_statuses"]
    ]
    return {
        "entity_changes": golden_v2._expected_entity_changes(baseline, result),
        "value_changes": value_changes,
    }, status_changes


def _empty_deltas():
    return {
        "entity_changes": {
            name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
            for name in ENTITY_COLLECTIONS
        },
        "value_changes": {"balances": [], "reports": [], "derived_statuses": []},
    }


def _add_operation(
    states,
    operations,
    root_id,
    operation_id,
    action_type,
    operation_class,
    operation_input,
    result,
    returned_ids,
    status="accepted",
):
    baseline = states[-1]
    result = deepcopy(result)
    _state_identity(result, operation_id + "-state", root_id, operation_id)
    deltas, status_changes = _operation_deltas(baseline, result)
    operation = {
        "id": operation_id,
        "root_id": root_id,
        "sequence": len(operations) + 1,
        "operation_class": operation_class,
        "action_type": action_type,
        "baseline_state_id": baseline["id"],
        "result_state_id": result["id"],
        "outcome": {"status": status},
        "status_changes": status_changes,
        "deltas": deltas,
        "returned_ids": returned_ids,
    }
    if status == "rejected":
        operation.pop("status_changes")
        operation["status_changes"] = []
        operation["deltas"] = _empty_deltas()
        operation["attempted_input"] = deepcopy(operation_input)
    else:
        operation["input"] = deepcopy(operation_input)
        if status == "no_change":
            operation["outcome"]["reason_code"] = "idempotent_replay"
            operation["deltas"] = _empty_deltas()
            operation["status_changes"] = []
    states.append(result)
    operations.append(operation)
    return operation


def _manual_case(fixture):
    root_id = "root-rg06-manual"
    config = _config("manual", root_id)
    states = [_state_identity(_opening_state(fixture, config), "state-rg06-manual-opening", root_id)]
    operations = []
    accepted = []

    def add(operation_id, action, operation_class, operation_input, result, returned):
        _preserve_manual_history_ids(result, fixture)
        operation = _add_operation(
            states,
            operations,
            root_id,
            operation_id,
            action,
            operation_class,
            operation_input,
            result,
            returned,
        )
        accepted.append(operation)
        return operation

    ordered = {item["id"]: item for item in fixture["manual_path"]["ordered_operations"]}
    create = ordered["create-group"]
    create_id = "operation-rg06-manual-create-group"
    add(
        create_id,
        "create_staged_payment",
        "creation",
        create["input"],
        _group_state(fixture, config),
        [
            {"kind": "relation", "id": config["relation_id"]},
            {"kind": "domain_entity", "id": config["lifecycle_id"]},
        ],
    )

    deposit = ordered["save-deposit"]
    deposit_id = "operation-rg06-manual-save-deposit"
    deposit_result_state = _mapped_helper_state(deposit_result(), fixture, config)
    _bind_manual_confirmations(deposit_result_state, {"deposit": deposit_id})
    add(
        deposit_id,
        "record_staged_payment_installment",
        "creation",
        {
            "request_id": deposit["input"]["request_id"],
            "relation_id": config["relation_id"],
            "payment_role": deposit["input"]["payment_role"],
            "payment_amount": deposit["input"]["payment_amount"],
            "currency": deposit["input"]["currency"],
            "funding_account_id": deposit["input"]["funding_account_id"],
            "actual_payment_at": deposit["input"]["actual_payment_at"],
        },
        deposit_result_state,
        [
            {"kind": "confirmation", "id": "confirmation-deposit"},
            {"kind": "transaction", "id": config["deposit_transaction_id"]},
            {"kind": "domain_entity", "id": config["deposit_id"]},
        ],
    )

    fulfilled = ordered["mark-fulfilled"]
    fulfilled_state = deepcopy(states[-1])
    helper_history = _mapped_helper_state(staged_payment_state(), fixture, config)
    lifecycle = next(
        item for item in fulfilled_state["domain_entities"] if item["id"] == config["lifecycle_id"]
    )
    source_lifecycle = next(
        item for item in helper_history["domain_entities"] if item["id"] == config["lifecycle_id"]
    )
    lifecycle["payload"]["state_history"].append(
        deepcopy(source_lifecycle["payload"]["state_history"][2])
    )
    add(
        "operation-rg06-manual-mark-fulfilled",
        "change_staged_payment_fulfillment",
        "status_transition",
        {
            "request_id": fulfilled["input"]["request_id"],
            "relation_id": config["relation_id"],
            "fulfillment_status": fulfilled["input"]["fulfillment_status"],
            "occurred_at": fulfilled["input"]["occurred_at"],
        },
        fulfilled_state,
        [{"kind": "domain_entity", "id": config["lifecycle_id"]}],
    )

    final = ordered["save-final"]
    final_id = "operation-rg06-manual-save-final"
    final_result_state = _mapped_helper_state(final_result(), fixture, config)
    _bind_manual_confirmations(
        final_result_state,
        {"deposit": deposit_id, "final": final_id},
    )
    add(
        final_id,
        "record_staged_payment_installment",
        "creation",
        {
            "request_id": final["input"]["request_id"],
            "relation_id": config["relation_id"],
            "payment_role": final["input"]["payment_role"],
            "payment_amount": final["input"]["payment_amount"],
            "currency": final["input"]["currency"],
            "funding_account_id": final["input"]["funding_account_id"],
            "actual_payment_at": final["input"]["actual_payment_at"],
        },
        final_result_state,
        [
            {"kind": "confirmation", "id": "confirmation-final"},
            {"kind": "transaction", "id": config["final_transaction_id"]},
            {"kind": "domain_entity", "id": config["final_id"]},
        ],
    )

    completion = ordered["confirm-completion"]
    completion_state = deepcopy(states[-1])
    lifecycle = next(
        item for item in completion_state["domain_entities"] if item["id"] == config["lifecycle_id"]
    )
    source_lifecycle = next(
        item for item in helper_history["domain_entities"] if item["id"] == config["lifecycle_id"]
    )
    lifecycle["payload"]["state_history"].append(
        deepcopy(source_lifecycle["payload"]["state_history"][4])
    )
    add(
        "operation-rg06-manual-confirm-completion",
        "confirm_staged_payment_completion",
        "status_transition",
        {
            "request_id": completion["input"]["request_id"],
            "relation_id": config["relation_id"],
            "confirmed": completion["input"]["confirmed"],
            "occurred_at": completion["input"]["occurred_at"],
        },
        completion_state,
        [{"kind": "domain_entity", "id": config["lifecycle_id"]}],
    )

    for legacy_id, role in (("link-deposit-evidence", "deposit"), ("link-final-evidence", "final")):
        legacy = ordered[legacy_id]
        link_operation_id = f"operation-rg06-manual-{role}-evidence"
        link_state = deepcopy(states[-1])
        _manual_source_evidence(link_state, config, role)
        add(
            link_operation_id,
            "link_staged_payment_evidence",
            "reconciliation",
            {
                "source_id": f"source-rg06-manual-{role}-bank",
                "evidence_id": f"evidence-rg06-manual-{role}",
                "payment_id": config[f"{role}_id"],
                "posting_id": config[f"{role}_asset_posting_id"],
            },
            link_state,
            [
                {"kind": "source", "id": f"source-rg06-manual-{role}-bank"},
                {"kind": "evidence", "id": f"evidence-rg06-manual-{role}"},
                {"kind": "evidence_link", "id": f"match-rg06-manual-{role}"},
            ],
        )

    retry_ids = [
        "operation-rg06-manual-retry-create",
        "operation-rg06-manual-retry-deposit",
        "operation-rg06-manual-retry-fulfilled",
        "operation-rg06-manual-retry-final",
        "operation-rg06-manual-retry-completion",
    ]
    for retry_id, original in zip(retry_ids, accepted[:5], strict=True):
        _add_operation(
            states,
            operations,
            root_id,
            retry_id,
            original["action_type"],
            original["operation_class"],
            original["input"],
            states[-1],
            original["returned_ids"],
            status="no_change",
        )

    return {
        "id": root_id,
        "purpose": "rg06_manual_staged_payment_lifecycle",
        "initial_state_id": states[0]["id"],
        "operation_ids": [operation["id"] for operation in operations],
    }, states, operations


def _import_case(fixture):
    root_id = "root-rg06-import"
    config = _config("import", root_id)
    states = [_state_identity(_group_state(fixture, config), "state-rg06-import-opening", root_id)]
    _refresh_projections(states[0])
    operations = []
    accepted = []
    ordered = {item["id"]: item for item in fixture["import_path"]["ordered_operations"]}
    source_records = {
        record["id"]: record
        for record in fixture["import_path"]["canonical_final_state"]["source_records"]
    }

    def add(operation_id, action, operation_class, operation_input, result, returned):
        operation = _add_operation(
            states,
            operations,
            root_id,
            operation_id,
            action,
            operation_class,
            operation_input,
            result,
            returned,
        )
        accepted.append(operation)
        return operation

    deposit = ordered["import-deposit"]
    deposit_id = "operation-rg06-import-intake-deposit"
    deposit_state = deepcopy(states[-1])
    deposit_record = {
        **source_records[deposit["input"]["source_id"]],
        "suggested_payment_role": deposit["input"]["suggested_payment_role"],
    }
    _append_intake(deposit_state, deposit_record)
    add(
        deposit_id,
        "ingest_staged_payment_bank_fact",
        "creation",
        deposit["input"],
        deposit_state,
        [
            {"kind": "source", "id": deposit["input"]["source_id"]},
            {"kind": "evidence", "id": deposit["input"]["evidence_id"]},
            {"kind": "candidate", "id": "candidate-rg06-import-deposit"},
        ],
    )

    ambiguous = ordered["import-ambiguous-role"]
    ambiguous_id = "operation-rg06-import-intake-ambiguous"
    ambiguous_state = deepcopy(states[-1])
    _append_intake(
        ambiguous_state,
        source_records[ambiguous["input"]["source_id"]],
        ambiguous=True,
    )
    add(
        ambiguous_id,
        "ingest_staged_payment_bank_fact",
        "creation",
        ambiguous["input"],
        ambiguous_state,
        [
            {"kind": "source", "id": ambiguous["input"]["source_id"]},
            {"kind": "evidence", "id": ambiguous["input"]["evidence_id"]},
            {"kind": "candidate", "id": "candidate-rg06-import-ambiguous"},
        ],
    )

    confirm_deposit = ordered["confirm-deposit"]
    confirm_deposit_id = "operation-rg06-import-confirm-deposit"
    confirmed_deposit_state = deepcopy(states[-1])
    _append_import_payment(
        confirmed_deposit_state,
        fixture,
        config,
        "deposit",
        "candidate-rg06-import-deposit",
        confirm_deposit_id,
        "2026-04-28T10:05:00+08:00",
    )
    add(
        confirm_deposit_id,
        "confirm_staged_payment_candidate",
        "creation",
        {
            "request_id": confirm_deposit["input"]["request_id"],
            "candidate_id": confirm_deposit["input"]["candidate_id"],
            "relation_id": config["relation_id"],
            "payment_role": confirm_deposit["input"]["payment_role"],
            "category_id": confirm_deposit["input"]["category_id"],
            "funding_account_id": confirm_deposit["input"]["funding_account_id"],
            "exact_binding_confirmed": confirm_deposit["input"]["exact_binding_confirmed"],
        },
        confirmed_deposit_state,
        [
            {"kind": "confirmation", "id": "confirmation-candidate-rg06-import-deposit"},
            {"kind": "transaction", "id": config["deposit_transaction_id"]},
            {"kind": "domain_entity", "id": config["deposit_id"]},
            {"kind": "evidence_link", "id": "match-rg06-import-deposit"},
        ],
    )

    final = ordered["import-final"]
    final_intake_id = "operation-rg06-import-intake-final"
    final_intake_state = deepcopy(states[-1])
    final_record = {
        **source_records[final["input"]["source_id"]],
        "suggested_payment_role": final["input"]["suggested_payment_role"],
    }
    _append_intake(final_intake_state, final_record)
    add(
        final_intake_id,
        "ingest_staged_payment_bank_fact",
        "creation",
        final["input"],
        final_intake_state,
        [
            {"kind": "source", "id": final["input"]["source_id"]},
            {"kind": "evidence", "id": final["input"]["evidence_id"]},
            {"kind": "candidate", "id": "candidate-rg06-import-final"},
        ],
    )

    confirm_final = ordered["confirm-final"]
    confirm_final_id = "operation-rg06-import-confirm-final"
    confirmed_final_state = deepcopy(states[-1])
    _append_import_payment(
        confirmed_final_state,
        fixture,
        config,
        "final",
        "candidate-rg06-import-final",
        confirm_final_id,
        "2026-05-03T16:35:00+08:00",
    )
    add(
        confirm_final_id,
        "confirm_staged_payment_candidate",
        "creation",
        {
            "request_id": confirm_final["input"]["request_id"],
            "candidate_id": confirm_final["input"]["candidate_id"],
            "relation_id": config["relation_id"],
            "payment_role": confirm_final["input"]["payment_role"],
            "category_id": confirm_final["input"]["category_id"],
            "funding_account_id": confirm_final["input"]["funding_account_id"],
            "exact_binding_confirmed": confirm_final["input"]["exact_binding_confirmed"],
        },
        confirmed_final_state,
        [
            {"kind": "confirmation", "id": "confirmation-candidate-rg06-import-final"},
            {"kind": "transaction", "id": config["final_transaction_id"]},
            {"kind": "domain_entity", "id": config["final_id"]},
            {"kind": "evidence_link", "id": "match-rg06-import-final"},
        ],
    )

    mirror = ordered["merge-final-mirror-evidence"]
    mirror_id = "operation-rg06-import-merge-final-mirror"
    mirror_state = deepcopy(states[-1])
    mirror_record = {
        **source_records[mirror["input"]["source_id"]],
        "payment_id": mirror["input"]["payment_id"],
        "mirror_of_evidence_id": "evidence-rg06-import-final",
        "merged_into_evidence_link_id": "match-rg06-import-final",
    }
    mirror_input = {
        **mirror["input"],
        "source_payment_at": mirror_record["source_payment_at"],
    }
    _append_intake(mirror_state, mirror_record)
    add(
        mirror_id,
        "merge_staged_payment_mirror_evidence",
        "reconciliation",
        mirror_input,
        mirror_state,
        [
            {"kind": "source", "id": mirror["input"]["source_id"]},
            {"kind": "evidence", "id": mirror["input"]["evidence_id"]},
        ],
    )

    retry_ids = [
        "operation-rg06-import-retry-deposit-intake",
        "operation-rg06-import-retry-deposit-confirm",
        "operation-rg06-import-retry-final-intake",
        "operation-rg06-import-retry-final-confirm",
        "operation-rg06-import-retry-final-mirror",
    ]
    retry_sources = [accepted[0], accepted[2], accepted[3], accepted[4], accepted[5]]
    for retry_id, original in zip(retry_ids, retry_sources, strict=True):
        _add_operation(
            states,
            operations,
            root_id,
            retry_id,
            original["action_type"],
            original["operation_class"],
            original["input"],
            states[-1],
            original["returned_ids"],
            status="no_change",
        )

    return {
        "id": root_id,
        "purpose": "rg06_import_staged_payment_lifecycle",
        "initial_state_id": states[0]["id"],
        "operation_ids": [operation["id"] for operation in operations],
    }, states, operations


def _invalid_case(fixture, invalid):
    root_id = f"root-rg06-rejection-{invalid['id']}"
    config = _config("invalid", root_id)
    baseline_id = invalid["expected"]["baseline_id"]
    if baseline_id == "opening_only":
        baseline = _opening_state(fixture, config)
    elif baseline_id == "group_only":
        baseline = _group_state(fixture, config)
    elif baseline_id == "after_deposit":
        baseline = _mapped_helper_state(deposit_result(), fixture, config)
        baseline["confirmations"] = []
        for version in baseline["transaction_versions"]:
            version.pop("confirmation_id", None)
    else:
        raise AssertionError(f"unsupported RG-06 invalid baseline: {baseline_id}")
    baseline = _state_identity(
        baseline,
        f"state-rg06-rejection-{invalid['id']}-before",
        root_id,
    )
    result = _state_identity(
        deepcopy(baseline),
        f"state-rg06-rejection-{invalid['id']}-after",
        root_id,
        f"operation-rg06-rejection-{invalid['id']}",
    )
    action_by_context = {
        "group_creation": "create_staged_payment",
        "payment_creation": "record_staged_payment_installment",
        "payment_progress_transition": "confirm_staged_payment_completion",
    }
    action = action_by_context[invalid["operation_context"]]
    operation_id = f"operation-rg06-rejection-{invalid['id']}"
    operation = {
        "id": operation_id,
        "root_id": root_id,
        "sequence": 1,
        "operation_class": "rejection",
        "action_type": action,
        "baseline_state_id": baseline["id"],
        "result_state_id": result["id"],
        "outcome": {
            "status": "rejected",
            "reason_code": invalid["expected"]["reason"],
            "field_path": f"$.attempted_input.{invalid['expected']['field']}",
        },
        "status_changes": [],
        "deltas": _empty_deltas(),
        "returned_ids": [],
        "attempted_input": deepcopy(invalid["input"]),
    }
    return (
        {
            "id": root_id,
            "purpose": f"rg06_rejected_{invalid['id']}",
            "initial_state_id": baseline["id"],
            "operation_ids": [operation_id],
        },
        [baseline, result],
        [operation],
    )


def build_expected():
    fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    manual_root, manual_states, manual_operations = _manual_case(fixture)
    import_root, import_states, import_operations = _import_case(fixture)
    roots = [manual_root, import_root]
    states = [*manual_states, *import_states]
    operations = [*manual_operations, *import_operations]
    for invalid in fixture["invalid_inputs"]:
        root, root_states, root_operations = _invalid_case(fixture, invalid)
        roots.append(root)
        states.extend(root_states)
        operations.extend(root_operations)

    roots.sort(key=lambda root: root["id"])
    root_order = {root["id"]: index for index, root in enumerate(roots)}
    operations.sort(key=lambda operation: (operation["root_id"], operation["sequence"]))
    states.sort(
        key=lambda state: (
            root_order[state["root_id"]],
            0 if state["as_of_operation_id"] is None else 1,
            state["as_of_operation_id"] or "",
        )
    )
    case = {
        "contract": "unifiedledger.golden-case",
        "contract_version": "2.0.0",
        "case": {
            "id": "RG-06",
            "level": fixture["case"]["level"],
            "rule_version": fixture["case"]["rule_version"],
            "approval_status": "approved",
            "ledger_id": fixture["case"]["ledger_id"],
            "timezone": fixture["case"]["timezone"],
            "currencies": [
                {"code": fixture["case"]["currency"], "precision": fixture["case"]["precision"]}
            ],
        },
        "roots": roots,
        "states": states,
        "operations": operations,
    }
    return case


class Rg06V2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generated = build_expected()
        cls.on_disk = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))

    def test_expected_artifact_matches_deterministic_builder(self):
        self.assertEqual(self.generated, self.on_disk)

    def test_expected_artifact_has_rg06_inventory_and_outcome_counts(self):
        validate_golden_case_v2(self.on_disk)
        self.assertEqual("approved", self.on_disk["case"]["approval_status"])
        self.assertEqual(20, len(self.on_disk["roots"]))
        self.assertEqual(61, len(self.on_disk["states"]))
        self.assertEqual(41, len(self.on_disk["operations"]))
        self.assertEqual(
            18,
            sum(
                operation["outcome"]["status"] == "rejected"
                for operation in self.on_disk["operations"]
            ),
        )
        self.assertEqual(
            10,
            sum(
                operation["outcome"]["status"] == "no_change"
                for operation in self.on_disk["operations"]
            ),
        )

    def test_expected_artifact_preserves_frozen_source_and_rejection_anchors(self):
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        final_source_ids = {
            source["id"]
            for final_state in (
                fixture["manual_path"]["canonical_final_state"],
                fixture["import_path"]["canonical_final_state"],
            )
            for source in final_state["source_records"]
        }
        expected_source_ids = {
            source["id"]
            for state in self.on_disk["states"]
            for source in state["sources"]
        }
        self.assertEqual(final_source_ids, final_source_ids & expected_source_ids)

        action_by_context = {
            "group_creation": "create_staged_payment",
            "payment_creation": "record_staged_payment_installment",
            "payment_progress_transition": "confirm_staged_payment_completion",
        }
        expected_rejections = {
            operation["id"]: operation
            for operation in self.on_disk["operations"]
            if operation["outcome"]["status"] == "rejected"
        }
        self.assertEqual(
            {f"operation-rg06-rejection-{invalid['id']}" for invalid in fixture["invalid_inputs"]},
            set(expected_rejections),
        )
        for invalid in fixture["invalid_inputs"]:
            operation = expected_rejections[f"operation-rg06-rejection-{invalid['id']}"]
            self.assertEqual(action_by_context[invalid["operation_context"]], operation["action_type"])
            self.assertEqual(invalid["expected"]["reason"], operation["outcome"]["reason_code"])
            self.assertEqual(
                f"$.attempted_input.{invalid['expected']['field']}",
                operation["outcome"]["field_path"],
            )
            self.assertEqual(invalid["input"], operation["attempted_input"])


if __name__ == "__main__":
    unittest.main()
