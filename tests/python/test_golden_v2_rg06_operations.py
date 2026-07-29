import json
from copy import deepcopy
from decimal import Decimal
from pathlib import Path
import unittest

import golden_cases.v2 as golden_v2
from golden_cases import validate_golden_case_v2
from jsonschema import Draft202012Validator

from tests.python.test_golden_v2_rg06_semantics import staged_payment_state


ROOT = Path(__file__).resolve().parents[2]


def rejection_baseline() -> dict:
    state = staged_payment_state()
    state["catalog"]["accounts"].extend(
        [
            {
                "id": "asset-nonfinancial",
                "name": "Synthetic nonfinancial asset",
                "kind": "asset",
                "currency": "CNY",
                "owned_by_user": True,
                "real_account": False,
                "reconciliation_eligible": False,
            },
            {
                "id": "asset-external",
                "name": "Synthetic external asset",
                "kind": "asset",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": True,
                "reconciliation_eligible": False,
            },
            {
                "id": "liability-owned",
                "name": "Synthetic liability",
                "kind": "liability",
                "currency": "CNY",
                "owned_by_user": True,
                "real_account": True,
                "reconciliation_eligible": True,
            },
            {
                "id": "income-synthetic",
                "name": "Synthetic income",
                "kind": "income",
                "currency": "CNY",
                "owned_by_user": False,
                "real_account": False,
                "reconciliation_eligible": False,
            },
        ]
    )
    state["catalog"]["categories"].extend(
        [
            {
                "id": "category-inactive",
                "name": "Synthetic inactive expense",
                "parent_id": "category-services",
                "posting_account_id": "expense-service",
                "active": False,
            },
            {
                "id": "category-income",
                "name": "Synthetic income category",
                "parent_id": "category-services",
                "posting_account_id": "income-synthetic",
                "active": True,
            },
        ]
    )
    lifecycle = next(
        item
        for item in state["domain_entities"]
        if item["type"] == "staged_payment_lifecycle"
    )["payload"]
    lifecycle.update(paid_amount="80.00", due_amount="220.00")
    return state


def empty_deltas() -> dict:
    return {
        "entity_changes": {
            name: {"added_ids": [], "changed_ids": [], "removed_ids": []}
            for name in golden_v2._ENTITY_COLLECTIONS
        },
        "value_changes": {
            "balances": [],
            "reports": [],
            "derived_statuses": [],
        },
    }


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
                "period_type": key[0],
                "period": key[1],
                "metric": key[2],
                **({"currency": key[3]} if key[3] is not None else {}),
            },
        ),
        "derived_statuses": items(
            golden_v2._changes(golden_v2._status_map(before), golden_v2._status_map(after)),
            lambda key: {"kind": key[0], "target_id": key[1], "status_name": key[2]},
        ),
    }


def operation_deltas(before: dict, after: dict) -> tuple[dict, list[dict]]:
    values = value_changes(before, after)
    return (
        {
            "entity_changes": golden_v2._expected_entity_changes(before, after),
            "value_changes": values,
        },
        [
            {
                "target_kind": item["key"]["kind"],
                "target_id": item["key"]["target_id"],
                "status_name": item["key"]["status_name"],
                "before": item["before"],
                "after": item["after"],
            }
            for item in values["derived_statuses"]
        ],
    )


def refresh_public_projections(state: dict) -> None:
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

    balances = {account["id"]: Decimal("0.00") for account in state["catalog"]["accounts"]}
    for _, _, postings in current.values():
        for posting in postings:
            balances[posting["account_id"]] += Decimal(posting["amount"])
    state["balances"] = [
        {"account_id": account_id, "currency": "CNY", "amount": f"{amount:.2f}"}
        for account_id, amount in balances.items()
    ]

    report_amount = f"{balances['expense-service']:.2f}"
    state["reports"] = [
        {
            "period_type": "day",
            "period": "2026-04-28",
            "metrics": [
                {
                    "metric": metric,
                    "applicability": "applicable",
                    "currency": "CNY",
                    "amount": report_amount,
                }
                for metric in ("consumption", "cash_outflow")
            ],
        }
    ]

    reconciliation_by_posting = {
        item["posting_id"]: item["status"]
        for item in state["posting_reconciliations"]
    }
    expected_statuses = golden_v2._expected_derived_statuses(
        state,
        indexes,
        current,
        reconciliation_by_posting,
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


def rg06_action_inputs() -> dict[str, dict]:
    return {
        "create_staged_payment": {
            "request_id": "request-create",
            "kind": "staged_payment",
            "total_amount": "300.00",
            "currency": "CNY",
            "category_id": "category-service",
            "created_at": "2026-04-20T09:00:00+08:00",
        },
        "record_staged_payment_installment": {
            "request_id": "request-deposit",
            "relation_id": "relation-staged-payment",
            "payment_role": "deposit",
            "payment_amount": "80.00",
            "currency": "CNY",
            "funding_account_id": "asset-bank",
            "actual_payment_at": "2026-04-28T10:00:00+08:00",
        },
        "change_staged_payment_fulfillment": {
            "request_id": "request-fulfillment",
            "relation_id": "relation-staged-payment",
            "fulfillment_status": "fulfilled",
            "occurred_at": "2026-05-01T12:00:00+08:00",
        },
        "confirm_staged_payment_completion": {
            "request_id": "request-completion",
            "relation_id": "relation-staged-payment",
            "confirmed": True,
            "occurred_at": "2026-05-04T09:00:00+08:00",
        },
        "link_staged_payment_evidence": {
            "source_id": "source-manual-bank",
            "evidence_id": "evidence-manual-bank",
            "payment_id": "installment-deposit",
            "posting_id": "posting-asset-deposit",
        },
        "ingest_staged_payment_bank_fact": {
            "source_id": "source-import",
            "evidence_id": "evidence-import",
            "source_payment_at": "2026-04-28T10:00:00+08:00",
            "amount": "80.00",
            "currency": "CNY",
            "suggested_payment_role": "deposit",
        },
        "confirm_staged_payment_candidate": {
            "request_id": "request-confirm",
            "candidate_id": "candidate-import",
            "relation_id": "relation-staged-payment",
            "payment_role": "deposit",
            "category_id": "category-service",
            "funding_account_id": "asset-bank",
            "exact_binding_confirmed": True,
        },
        "merge_staged_payment_mirror_evidence": {
            "source_id": "source-mirror",
            "evidence_id": "evidence-mirror",
            "payment_id": "installment-final",
            "posting_id": "posting-asset-final",
            "amount": "-220.00",
            "currency": "CNY",
            "source_payment_at": "2026-05-03T16:30:00+08:00",
        },
    }


def lifecycle_entity(state: dict) -> dict:
    return next(
        item
        for item in state["domain_entities"]
        if item["type"] == "staged_payment_lifecycle"
    )


def payment_entity(state: dict, role: str) -> dict:
    return next(
        item
        for item in state["domain_entities"]
        if item["type"] == "installment_payment"
        and item["payload"]["role"] == role
    )


def add_manual_confirmation(state: dict, role: str, operation_id: str) -> None:
    payment = payment_entity(state, role)
    confirmation_id = f"confirmation-{role}"
    state["confirmations"].append(
        {
            "id": confirmation_id,
            "type": "explicit_manual_save",
            "operation_id": operation_id,
            "subject": {"kind": "operation", "id": operation_id},
            "payload": {},
        }
    )
    transaction = next(
        item
        for item in state["transactions"]
        if item["id"] == payment["payload"]["transaction_id"]
    )
    version = next(
        item
        for item in state["transaction_versions"]
        if item["id"] == transaction["current_version_id"]
    )
    version["confirmation_id"] = confirmation_id


def deposit_result() -> dict:
    state = staged_payment_state()
    deposit = payment_entity(state, "deposit")
    transaction_id = deposit["payload"]["transaction_id"]
    transaction = next(item for item in state["transactions"] if item["id"] == transaction_id)
    version_id = transaction["current_version_id"]
    version = next(item for item in state["transaction_versions"] if item["id"] == version_id)
    posting_set_id = version["posting_set_id"]
    posting_set = next(item for item in state["posting_sets"] if item["id"] == posting_set_id)
    posting_ids = set(posting_set["posting_ids"])
    state["transactions"] = [transaction]
    state["transaction_versions"] = [version]
    state["posting_sets"] = [posting_set]
    state["postings"] = [item for item in state["postings"] if item["id"] in posting_ids]
    state["domain_entities"] = [lifecycle_entity(state), deposit]
    state["relations"][0]["member_refs"] = [
        {"kind": "domain_entity", "id": "lifecycle-staged-payment"},
        {"kind": "domain_entity", "id": deposit["id"]},
    ]
    lifecycle = lifecycle_entity(state)["payload"]
    lifecycle.update(paid_amount="80.00", due_amount="220.00")
    lifecycle["state_history"] = lifecycle["state_history"][:2]
    state["posting_reconciliations"] = [
        {
            "id": "reconciliation-rg06-deposit",
            "posting_id": deposit["payload"]["asset_posting_id"],
            "status": "pending",
        }
    ]
    state["confirmations"] = []
    add_manual_confirmation(state, "deposit", "operation-record-deposit")
    return state


def creation_result() -> dict:
    result = deposit_result()
    result["transactions"] = []
    result["transaction_versions"] = []
    result["posting_sets"] = []
    result["postings"] = []
    result["confirmations"] = []
    result["posting_reconciliations"] = []
    result["domain_entities"] = [lifecycle_entity(result)]
    result["relations"][0]["member_refs"] = [
        {"kind": "domain_entity", "id": "lifecycle-staged-payment"}
    ]
    lifecycle = lifecycle_entity(result)["payload"]
    lifecycle.update(paid_amount="0.00", due_amount="300.00")
    lifecycle["state_history"] = lifecycle["state_history"][:1]
    return result


def final_result() -> dict:
    state = staged_payment_state()
    state["confirmations"] = []
    add_manual_confirmation(state, "deposit", "operation-record-deposit")
    add_manual_confirmation(state, "final", "operation-record-final")
    lifecycle_entity(state)["payload"]["state_history"] = lifecycle_entity(state)[
        "payload"
    ]["state_history"][:4]
    state["posting_reconciliations"] = [
        {
            "id": "reconciliation-rg06-deposit",
            "posting_id": "posting-asset-deposit",
            "status": "pending",
        },
        {
            "id": "reconciliation-rg06-final",
            "posting_id": "posting-asset-final",
            "status": "pending",
        },
    ]
    return state


def rg06_effect_case(action: str) -> tuple[dict, dict, dict]:
    inputs = rg06_action_inputs()
    operation = {
        "id": f"operation-{action}",
        "action_type": action,
        "input": deepcopy(inputs[action]),
        "outcome": {"status": "accepted"},
        "returned_ids": [],
    }
    if action == "create_staged_payment":
        result = creation_result()
        baseline = deepcopy(result)
        baseline["relations"] = []
        baseline["domain_entities"] = []
        operation["returned_ids"] = [
            {"kind": "relation", "id": "relation-staged-payment"},
            {"kind": "domain_entity", "id": "lifecycle-staged-payment"},
        ]
    elif action == "record_staged_payment_installment":
        result = deposit_result()
        baseline = creation_result()
        operation["id"] = "operation-record-deposit"
        operation["returned_ids"] = [
            {"kind": "confirmation", "id": "confirmation-deposit"},
            {"kind": "transaction", "id": "transaction-deposit"},
            {"kind": "domain_entity", "id": "installment-deposit"},
        ]
    elif action == "change_staged_payment_fulfillment":
        baseline = deposit_result()
        result = deepcopy(baseline)
        result_lifecycle = lifecycle_entity(result)["payload"]
        result_lifecycle["state_history"].append(
            deepcopy(lifecycle_entity(staged_payment_state())["payload"]["state_history"][2])
        )
        operation["returned_ids"] = [
            {"kind": "domain_entity", "id": "lifecycle-staged-payment"}
        ]
    elif action == "confirm_staged_payment_completion":
        baseline = final_result()
        result = deepcopy(baseline)
        result_lifecycle = lifecycle_entity(result)["payload"]
        result_lifecycle["state_history"].append(
            deepcopy(lifecycle_entity(staged_payment_state())["payload"]["state_history"][4])
        )
        operation["returned_ids"] = [
            {"kind": "domain_entity", "id": "lifecycle-staged-payment"}
        ]
    elif action == "ingest_staged_payment_bank_fact":
        baseline = staged_payment_state()
        result = deepcopy(baseline)
        result["sources"] = [
            {
                "id": "source-import",
                "type": "staged_payment_bank_fact",
                "payload": {
                    "amount": "80.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                },
            }
        ]
        result["evidence"] = [
            {
                "id": "evidence-import",
                "type": "staged_payment_bank_payment",
                "source_ids": ["source-import"],
                "payload": {
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                },
            }
        ]
        result["candidates"] = [
            {
                "id": "candidate-import",
                "type": "staged_payment",
                "source_ids": ["source-import"],
                "confidence": "1.00",
                "payload": {
                    "payment_role": "deposit",
                    "amount": "80.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                    "evidence_ref": "evidence-import",
                    "provenance": {"rule": "staged_payment_bank_fact", "rule_version": 1},
                    "requires_confirmation": ["relation_id", "payment_role", "category_id", "funding_account_id"],
                },
                "status_history": [
                    {"id": "candidate-status-import-pending", "sequence": 1, "status": "pending_confirmation"}
                ],
            }
        ]
        operation["returned_ids"] = [
            {"kind": "source", "id": "source-import"},
            {"kind": "evidence", "id": "evidence-import"},
            {"kind": "candidate", "id": "candidate-import"},
        ]
    elif action == "link_staged_payment_evidence":
        baseline = deposit_result()
        result = deepcopy(baseline)
        observed_at = "2026-04-28T10:00:00+08:00"
        result["sources"].append(
            {"id": "source-manual-bank", "type": "staged_payment_bank_fact", "payload": {"amount": "80.00", "currency": "CNY", "observed_at": observed_at}}
        )
        result["evidence"].append(
            {"id": "evidence-manual-bank", "type": "staged_payment_bank_payment", "source_ids": ["source-manual-bank"], "payload": {"payment_id": "installment-deposit", "observed_at": observed_at}}
        )
        result["evidence_links"].append(
            {"id": "link-manual-bank", "evidence_id": "evidence-manual-bank", "target_kind": "posting", "target_id": "posting-asset-deposit", "role": "payment_asset_posting"}
        )
        result["posting_reconciliations"][0]["status"] = "matched"
        operation["returned_ids"] = [
            {"kind": "source", "id": "source-manual-bank"},
            {"kind": "evidence", "id": "evidence-manual-bank"},
            {"kind": "evidence_link", "id": "link-manual-bank"},
        ]
    elif action == "confirm_staged_payment_candidate":
        result = deposit_result()
        baseline = creation_result()
        candidate = rg06_effect_case("ingest_staged_payment_bank_fact")[1]["candidates"][0]
        source = rg06_effect_case("ingest_staged_payment_bank_fact")[1]["sources"][0]
        evidence = rg06_effect_case("ingest_staged_payment_bank_fact")[1]["evidence"][0]
        baseline["sources"] = [source]
        baseline["evidence"] = [evidence]
        baseline["candidates"] = [candidate]
        result["sources"] = deepcopy(baseline["sources"])
        result["evidence"] = deepcopy(baseline["evidence"])
        result["evidence"][0]["payload"]["payment_id"] = "installment-deposit"
        result["candidates"] = deepcopy(baseline["candidates"])
        result["candidates"][0]["status_history"].append(
            {"id": "candidate-status-import-confirmed", "sequence": 2, "status": "confirmed"}
        )
        payment_entity(result, "deposit")["payload"]["source_payment_at"] = (
            result["candidates"][0]["payload"]["source_payment_at"]
        )
        result["confirmations"] = [
            {"id": "confirmation-candidate", "type": "candidate_confirmation", "operation_id": operation["id"], "subject": {"kind": "candidate", "id": "candidate-import"}, "payload": {}}
        ]
        result["transaction_versions"][0]["confirmation_id"] = "confirmation-candidate"
        result["evidence_links"] = [
            {"id": "link-candidate", "evidence_id": "evidence-import", "target_kind": "posting", "target_id": "posting-asset-deposit", "role": "payment_asset_posting"}
        ]
        operation["returned_ids"] = [
            {"kind": "confirmation", "id": "confirmation-candidate"},
            {"kind": "transaction", "id": "transaction-deposit"},
            {"kind": "domain_entity", "id": "installment-deposit"},
            {"kind": "evidence_link", "id": "link-candidate"},
        ]
    else:
        baseline = final_result()
        result = deepcopy(baseline)
        payment_at = "2026-05-03T16:30:00+08:00"
        baseline["sources"] = [
            {"id": "source-original", "type": "staged_payment_bank_fact", "payload": {"amount": "220.00", "currency": "CNY", "source_payment_at": payment_at}}
        ]
        baseline["evidence"] = [
            {"id": "evidence-original", "type": "staged_payment_bank_payment", "source_ids": ["source-original"], "payload": {"payment_id": "installment-final", "source_payment_at": payment_at}}
        ]
        baseline["evidence_links"] = [
            {"id": "link-original", "evidence_id": "evidence-original", "target_kind": "posting", "target_id": "posting-asset-final", "role": "payment_asset_posting"}
        ]
        result = deepcopy(baseline)
        result["sources"].append(
            {"id": "source-mirror", "type": "staged_payment_bank_fact", "payload": {"mirror_of_source_id": "source-original", "amount": "-220.00", "currency": "CNY", "source_payment_at": payment_at}}
        )
        result["evidence"].append(
            {"id": "evidence-mirror", "type": "staged_payment_bank_payment", "source_ids": ["source-mirror"], "payload": {"payment_id": "installment-final", "source_payment_at": payment_at, "mirror_of_evidence_id": "evidence-original", "merged_into_evidence_link_id": "link-original"}}
        )
        operation["returned_ids"] = [
            {"kind": "source", "id": "source-mirror"},
            {"kind": "evidence", "id": "evidence-mirror"},
        ]
    return baseline, result, operation


class Rg06OperationRegistryTests(unittest.TestCase):
    def test_rg06_rejection_zero_total_uses_frozen_first_failure(self):
        baseline = staged_payment_state()
        operation = {
            "action_type": "create_staged_payment",
            "attempted_input": {"total_amount": "0.00"},
            "outcome": {
                "status": "rejected",
                "reason_code": "must_be_positive",
                "field_path": "$.attempted_input.total_amount",
            },
        }

        golden_v2._validate_rejected_rg06_attempt(
            operation, "$.operations[0]", baseline
        )

    def test_rg06_all_frozen_rejections_are_exact_and_atomic(self):
        cases = (
            ("zero_total", "create_staged_payment", {"total_amount": "0.00"}, "must_be_positive", "total_amount"),
            ("negative_total", "create_staged_payment", {"total_amount": "-1.00"}, "must_be_positive", "total_amount"),
            ("null_category", "create_staged_payment", {"category_id": None}, "secondary_category_required", "category_id"),
            ("primary_category", "create_staged_payment", {"category_id": "category-services"}, "secondary_category_required", "category_id"),
            ("inactive_category", "create_staged_payment", {"category_id": "category-inactive"}, "category_inactive", "category_id"),
            ("wrong_kind_category", "create_staged_payment", {"category_id": "category-income"}, "expense_category_required", "category_id"),
            ("zero_payment", "record_staged_payment_installment", {"payment_amount": "0.00"}, "must_be_positive", "payment_amount"),
            ("negative_payment", "record_staged_payment_installment", {"payment_amount": "-1.00"}, "must_be_positive", "payment_amount"),
            ("deposit_equals_total", "record_staged_payment_installment", {"payment_role": "deposit", "payment_amount": "300.00"}, "deposit_must_be_less_than_total", "payment_amount"),
            ("deposit_exceeds_total", "record_staged_payment_installment", {"payment_role": "deposit", "payment_amount": "301.00"}, "deposit_must_be_less_than_total", "payment_amount"),
            ("final_exceeds_due", "record_staged_payment_installment", {"payment_role": "final", "payment_amount": "221.00"}, "payment_exceeds_due", "payment_amount"),
            ("final_below_due", "record_staged_payment_installment", {"payment_role": "final", "payment_amount": "219.00"}, "final_must_equal_remaining_due", "payment_amount"),
            ("currency_mismatch", "record_staged_payment_installment", {"total_currency": "CNY", "payment_currency": "USD"}, "single_currency_required", "currency"),
            ("unknown_funding", "record_staged_payment_installment", {"funding_account_id": "asset-missing"}, "unknown_real_account", "funding_account_id"),
            ("nonfinancial_funding", "record_staged_payment_installment", {"funding_account_id": "asset-nonfinancial"}, "real_financial_account_required", "funding_account_id"),
            ("non_owned_funding", "record_staged_payment_installment", {"funding_account_id": "asset-external"}, "owned_account_required", "funding_account_id"),
            ("liability_funding", "record_staged_payment_installment", {"funding_account_id": "liability-owned"}, "asset_account_required", "funding_account_id"),
            ("paid_in_full_while_due", "confirm_staged_payment_completion", {"payment_progress": "paid_in_full"}, "due_must_be_zero", "payment_progress"),
        )
        self.assertEqual(18, len(cases))

        for index, (name, action, attempted, reason, field) in enumerate(cases):
            with self.subTest(name=name):
                baseline = rejection_baseline()
                result = deepcopy(baseline)
                result.update(
                    id=f"state-rg06-rejected-{index}",
                    as_of_operation_id=f"operation-rg06-rejected-{index}",
                )
                operation = {
                    "id": f"operation-rg06-rejected-{index}",
                    "action_type": action,
                    "operation_class": "rejection",
                    "attempted_input": attempted,
                    "outcome": {
                        "status": "rejected",
                        "reason_code": reason,
                        "field_path": f"$.attempted_input.{field}",
                    },
                    "deltas": empty_deltas(),
                    "status_changes": [],
                    "returned_ids": [],
                }

                golden_v2._validate_action_input(
                    operation,
                    "$.operations[0]",
                    baseline,
                    {"CNY": 2, "USD": 2},
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
                expected = golden_v2._expected_entity_changes(baseline, result)
                golden_v2._validate_registered_action_effects(
                    operation, "$.operations[0]", result, expected
                )

                self.assertEqual(
                    golden_v2._state_payload(baseline),
                    golden_v2._state_payload(result),
                )
                self.assertTrue(
                    all(
                        not ids
                        for changes in expected.values()
                        for ids in changes.values()
                    )
                )
                self.assertEqual(empty_deltas(), operation["deltas"])
                self.assertEqual([], operation["status_changes"])
                self.assertEqual([], operation["returned_ids"])
                wrong = deepcopy(operation)
                wrong["outcome"]["reason_code"] = "wrong_reason"
                with self.assertRaises(golden_v2.GoldenCaseError):
                    golden_v2._validate_action_input(
                        wrong,
                        "$.operations[0]",
                        baseline,
                        {"CNY": 2, "USD": 2},
                        golden_v2.ZoneInfo("Asia/Shanghai"),
                    )

    def test_rg06_no_change_retries_preserve_action_input_ids_and_zero_effects(self):
        for index, (action, input_value) in enumerate(rg06_action_inputs().items()):
            with self.subTest(action=action):
                returned_ids = [{"kind": "relation", "id": f"result-{index}"}]
                accepted = {
                    "action_type": action,
                    "input": deepcopy(input_value),
                    "outcome": {"status": "accepted"},
                    "returned_ids": returned_ids,
                }
                retry = {
                    "action_type": action,
                    "input": deepcopy(input_value),
                    "outcome": {
                        "status": "no_change",
                        "reason_code": "idempotent_replay",
                    },
                    "returned_ids": deepcopy(returned_ids),
                }
                baseline = staged_payment_state()
                result = deepcopy(baseline)
                result.update(
                    id=f"state-rg06-retry-{index}",
                    as_of_operation_id=f"operation-rg06-retry-{index}",
                )

                golden_v2._validate_no_change_retry(
                    retry, "$.operations[1]", [accepted]
                )
                expected = golden_v2._expected_entity_changes(baseline, result)
                golden_v2._validate_registered_action_effects(
                    retry, "$.operations[1]", result, expected
                )
                self.assertEqual(
                    golden_v2._state_payload(baseline),
                    golden_v2._state_payload(result),
                )

    def test_rg06_no_change_rejects_mismatched_identity_and_duplicate_effects(self):
        accepted = {
            "action_type": "record_staged_payment_installment",
            "input": rg06_action_inputs()["record_staged_payment_installment"],
            "outcome": {"status": "accepted"},
            "returned_ids": [{"kind": "domain_entity", "id": "installment-deposit"}],
        }
        retry = {
            "action_type": accepted["action_type"],
            "input": deepcopy(accepted["input"]),
            "outcome": {
                "status": "no_change",
                "reason_code": "idempotent_replay",
            },
            "returned_ids": deepcopy(accepted["returned_ids"]),
        }
        invalid = []
        changed_action = deepcopy(retry)
        changed_action["action_type"] = "create_staged_payment"
        invalid.append(changed_action)
        changed_input = deepcopy(retry)
        changed_input["input"]["relation_id"] = "relation-other"
        invalid.append(changed_input)
        changed_ids = deepcopy(retry)
        changed_ids["returned_ids"][0]["id"] = "installment-other"
        invalid.append(changed_ids)
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(golden_v2.GoldenCaseError):
                    golden_v2._validate_no_change_retry(
                        value, "$.operations[1]", [accepted]
                    )

        baseline = staged_payment_state()
        duplicate = deepcopy(baseline)
        duplicate["relations"].append(
            {
                "id": "relation-duplicate",
                "type": "staged_payment",
                "member_refs": [
                    {"kind": "domain_entity", "id": "lifecycle-staged-payment"}
                ],
                "payload": {},
            }
        )
        expected = golden_v2._expected_entity_changes(baseline, duplicate)
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_registered_action_effects(
                retry, "$.operations[1]", duplicate, expected
            )

    def test_rg06_candidate_confirmation_keeps_payload_immutable(self):
        baseline = staged_payment_state()
        baseline["candidates"] = [
            {
                "id": "candidate-import",
                "type": "staged_payment",
                "source_ids": ["source-import"],
                "confidence": "1.00",
                "payload": {
                    "payment_role": "deposit",
                    "amount": "80.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                    "evidence_ref": "evidence-import",
                    "provenance": {
                        "rule": "staged_payment_bank_fact",
                        "rule_version": 1,
                    },
                    "requires_confirmation": [
                        "relation_id",
                        "payment_role",
                        "category_id",
                        "funding_account_id",
                    ],
                },
                "status_history": [
                    {"id": "candidate-status-pending", "sequence": 1, "status": "pending_confirmation"}
                ],
            }
        ]
        result = deepcopy(baseline)
        result["candidates"][0]["status_history"].append(
            {"id": "candidate-status-confirmed", "sequence": 2, "status": "confirmed"}
        )

        golden_v2._validate_append_only_transition(
            baseline,
            result,
            "$.operations[0]",
            case_id="RG-06",
            action_type="confirm_staged_payment_candidate",
            target_candidate_id="candidate-import",
        )

        invalid = deepcopy(result)
        invalid["candidates"][0]["payload"]["transaction_id"] = "transaction-deposit"
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_append_only_transition(
                baseline,
                invalid,
                "$.operations[0]",
                case_id="RG-06",
                action_type="confirm_staged_payment_candidate",
                target_candidate_id="candidate-import",
            )

    def test_rg06_all_eight_actions_bind_exact_input_effects(self):
        mutations = {
            "create_staged_payment": lambda result: lifecycle_entity(result)["payload"].update(total_amount="301.00"),
            "record_staged_payment_installment": lambda result: payment_entity(result, "deposit")["payload"].update(amount="81.00"),
            "change_staged_payment_fulfillment": lambda result: lifecycle_entity(result)["payload"]["state_history"][-1].update(occurred_at="2026-05-01T12:00:01+08:00"),
            "confirm_staged_payment_completion": lambda result: lifecycle_entity(result)["payload"]["state_history"][-1].update(occurred_at="2026-05-04T09:00:01+08:00"),
            "link_staged_payment_evidence": lambda result: result["evidence_links"][-1].update(target_id="posting-asset-final"),
            "ingest_staged_payment_bank_fact": lambda result: result["sources"][-1]["payload"].update(amount="81.00"),
            "confirm_staged_payment_candidate": lambda result: payment_entity(result, "deposit")["payload"].update(funding_account_id="asset-other"),
            "merge_staged_payment_mirror_evidence": lambda result: result["sources"][-1]["payload"].update(source_payment_at="2026-05-03T16:30:01+08:00"),
        }
        self.assertEqual(set(rg06_action_inputs()), set(mutations))

        for action, mutate in mutations.items():
            with self.subTest(action=action):
                baseline, result, operation = rg06_effect_case(action)
                expected = golden_v2._expected_entity_changes(baseline, result)
                golden_v2._validate_rg06_action_effects(
                    operation, "$.operations[0]", baseline, result, expected
                )

                invalid = deepcopy(result)
                mutate(invalid)
                with self.assertRaises(golden_v2.GoldenCaseError):
                    golden_v2._validate_rg06_action_effects(
                        operation, "$.operations[0]", baseline, invalid, expected
                    )

    def test_rg06_all_eight_actions_pass_scoped_append_only_transitions(self):
        for action in rg06_action_inputs():
            with self.subTest(action=action):
                baseline, result, operation = rg06_effect_case(action)
                golden_v2._validate_append_only_transition(
                    baseline,
                    result,
                    "$.operations[0]",
                    case_id="RG-06",
                    action_type=action,
                    target_candidate_id=operation["input"].get("candidate_id"),
                    target_relation_id=operation["input"].get("relation_id"),
                )

    def test_rg06_scoped_append_only_rejects_unrelated_relation_and_lifecycle_mutations(self):
        baseline, result, operation = rg06_effect_case(
            "record_staged_payment_installment"
        )
        unrelated_lifecycle = deepcopy(lifecycle_entity(baseline))
        unrelated_lifecycle["id"] = "lifecycle-unrelated"
        unrelated_relation = {
            "id": "relation-unrelated",
            "type": "staged_payment",
            "member_refs": [
                {"kind": "domain_entity", "id": unrelated_lifecycle["id"]}
            ],
            "payload": {},
        }
        baseline["domain_entities"].append(unrelated_lifecycle)
        baseline["relations"].append(unrelated_relation)
        result["domain_entities"].append(deepcopy(unrelated_lifecycle))
        result["relations"].append(deepcopy(unrelated_relation))

        wrong_relation = deepcopy(result)
        next(item for item in wrong_relation["relations"] if item["id"] == "relation-unrelated")[
            "member_refs"
        ].append({"kind": "domain_entity", "id": "installment-deposit"})
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_append_only_transition(
                baseline,
                wrong_relation,
                "$.operations[0]",
                case_id="RG-06",
                action_type=operation["action_type"],
                target_relation_id=operation["input"]["relation_id"],
            )

        wrong_lifecycle = deepcopy(result)
        next(
            item
            for item in wrong_lifecycle["domain_entities"]
            if item["id"] == "lifecycle-unrelated"
        )["payload"]["state_history"].append(
            {
                "id": "lifecycle-unrelated-event",
                "sequence": 2,
                "event": "fulfillment_changed",
                "occurred_at": "2026-05-01T12:00:00+08:00",
                "total_amount": "300.00",
                "paid_amount": "0.00",
                "due_amount": "300.00",
                "payment_id": None,
                "payment_progress": "unpaid",
                "fulfillment_status": "fulfilled",
                "state_transition_effect_count": 0,
            }
        )
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_append_only_transition(
                baseline,
                wrong_lifecycle,
                "$.operations[0]",
                case_id="RG-06",
                action_type=operation["action_type"],
                target_relation_id=operation["input"]["relation_id"],
            )

    def test_rg06_installment_effects_enforce_member_cardinality_and_final_chronology(self):
        baseline, result, operation = rg06_effect_case(
            "record_staged_payment_installment"
        )
        duplicate_member = deepcopy(result)
        duplicate_member["relations"][0]["member_refs"].append(
            {"kind": "domain_entity", "id": "installment-deposit"}
        )
        expected = golden_v2._expected_entity_changes(baseline, result)
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_rg06_action_effects(
                operation, "$.operations[0]", baseline, duplicate_member, expected
            )

        result = final_result()
        baseline = deepcopy(result)
        final = payment_entity(result, "final")
        final_transaction_id = final["payload"]["transaction_id"]
        final_transaction = next(
            item for item in result["transactions"] if item["id"] == final_transaction_id
        )
        final_version_id = final_transaction["current_version_id"]
        final_version = next(
            item for item in result["transaction_versions"] if item["id"] == final_version_id
        )
        final_set_id = final_version["posting_set_id"]
        final_posting_ids = set(
            next(
                item for item in result["posting_sets"] if item["id"] == final_set_id
            )["posting_ids"]
        )
        baseline["transactions"] = [
            item for item in baseline["transactions"] if item["id"] != final_transaction_id
        ]
        baseline["transaction_versions"] = [
            item for item in baseline["transaction_versions"] if item["id"] != final_version_id
        ]
        baseline["posting_sets"] = [
            item for item in baseline["posting_sets"] if item["id"] != final_set_id
        ]
        baseline["postings"] = [
            item for item in baseline["postings"] if item["id"] not in final_posting_ids
        ]
        baseline["domain_entities"] = [
            item for item in baseline["domain_entities"] if item["id"] != final["id"]
        ]
        baseline["relations"][0]["member_refs"] = [
            item
            for item in baseline["relations"][0]["member_refs"]
            if item["id"] != final["id"]
        ]
        baseline["confirmations"] = [
            item for item in baseline["confirmations"] if item["id"] != "confirmation-final"
        ]
        baseline["posting_reconciliations"] = [
            item
            for item in baseline["posting_reconciliations"]
            if item["posting_id"] not in final_posting_ids
        ]
        baseline_lifecycle = lifecycle_entity(baseline)["payload"]
        baseline_lifecycle.update(paid_amount="80.00", due_amount="220.00")
        baseline_lifecycle["state_history"] = baseline_lifecycle["state_history"][:3]
        operation = deepcopy(operation)
        operation["id"] = "operation-record-final"
        operation["input"].update(
            request_id="request-final",
            payment_role="final",
            payment_amount="220.00",
            actual_payment_at="2026-05-03T16:30:00+08:00",
        )
        operation["returned_ids"] = [
            {"kind": "confirmation", "id": "confirmation-final"},
            {"kind": "transaction", "id": "transaction-final"},
            {"kind": "domain_entity", "id": "installment-final"},
        ]
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_rg06_action_effects(
            operation, "$.operations[0]", baseline, result, expected
        )

        invalid = deepcopy(result)
        payment_entity(invalid, "final")["payload"]["actual_payment_at"] = (
            "2026-04-28T10:00:00+08:00"
        )
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_rg06_action_effects(
                operation, "$.operations[0]", baseline, invalid, expected
            )

    def test_rg06_installment_effect_chronology_compares_offset_instants(self):
        def set_payment_time(state: dict, role: str, payment_at: str) -> None:
            payment = payment_entity(state, role)
            payment["payload"].update(
                actual_payment_at=payment_at,
                statistics_at=payment_at,
                source_payment_at=payment_at,
            )
            transaction = next(
                item
                for item in state["transactions"]
                if item["id"] == payment["payload"]["transaction_id"]
            )
            version = next(
                item
                for item in state["transaction_versions"]
                if item["id"] == transaction["current_version_id"]
            )
            version.update(occurred_at=payment_at, statistics_at=payment_at)
            next(
                item
                for item in lifecycle_entity(state)["payload"]["state_history"]
                if item["payment_id"] == payment["id"]
            )["occurred_at"] = payment_at

        result = final_result()
        deposit_at = "2026-05-03T10:00:00+08:00"
        later_at = "2026-05-03T03:00:01+00:00"
        set_payment_time(result, "deposit", deposit_at)
        set_payment_time(result, "final", later_at)
        lifecycle_entity(result)["payload"]["state_history"][2]["occurred_at"] = (
            "2026-05-03T10:30:00+08:00"
        )

        final = payment_entity(result, "final")
        final_transaction_id = final["payload"]["transaction_id"]
        final_transaction = next(
            item for item in result["transactions"] if item["id"] == final_transaction_id
        )
        final_version_id = final_transaction["current_version_id"]
        final_version = next(
            item
            for item in result["transaction_versions"]
            if item["id"] == final_version_id
        )
        final_set_id = final_version["posting_set_id"]
        final_posting_ids = set(
            next(
                item for item in result["posting_sets"] if item["id"] == final_set_id
            )["posting_ids"]
        )
        baseline = deepcopy(result)
        baseline["transactions"] = [
            item for item in baseline["transactions"] if item["id"] != final_transaction_id
        ]
        baseline["transaction_versions"] = [
            item for item in baseline["transaction_versions"] if item["id"] != final_version_id
        ]
        baseline["posting_sets"] = [
            item for item in baseline["posting_sets"] if item["id"] != final_set_id
        ]
        baseline["postings"] = [
            item for item in baseline["postings"] if item["id"] not in final_posting_ids
        ]
        baseline["domain_entities"] = [
            item for item in baseline["domain_entities"] if item["id"] != final["id"]
        ]
        baseline["relations"][0]["member_refs"] = [
            item
            for item in baseline["relations"][0]["member_refs"]
            if item["id"] != final["id"]
        ]
        baseline["confirmations"] = [
            item for item in baseline["confirmations"] if item["id"] != "confirmation-final"
        ]
        baseline["posting_reconciliations"] = [
            item
            for item in baseline["posting_reconciliations"]
            if item["posting_id"] not in final_posting_ids
        ]
        lifecycle_entity(baseline)["payload"].update(
            paid_amount="80.00", due_amount="220.00"
        )
        lifecycle_entity(baseline)["payload"]["state_history"] = lifecycle_entity(
            baseline
        )["payload"]["state_history"][:3]
        operation = {
            "id": "operation-record-final",
            "action_type": "record_staged_payment_installment",
            "input": {
                "request_id": "request-final-offset",
                "relation_id": "relation-staged-payment",
                "payment_role": "final",
                "payment_amount": "220.00",
                "currency": "CNY",
                "funding_account_id": "asset-bank",
                "actual_payment_at": later_at,
            },
            "outcome": {"status": "accepted"},
            "returned_ids": [
                {"kind": "confirmation", "id": "confirmation-final"},
                {"kind": "transaction", "id": "transaction-final"},
                {"kind": "domain_entity", "id": "installment-final"},
            ],
        }
        expected = golden_v2._expected_entity_changes(baseline, result)

        golden_v2._validate_rg06_action_effects(
            operation, "$.operations[0]", baseline, result, expected
        )

        for label, payment_at in (
            ("equal instant", deposit_at),
            ("earlier instant with lexically later offset", "2026-05-03T10:30:00+09:00"),
        ):
            with self.subTest(label=label):
                invalid = deepcopy(result)
                set_payment_time(invalid, "final", payment_at)
                invalid_operation = deepcopy(operation)
                invalid_operation["input"]["actual_payment_at"] = payment_at
                with self.assertRaises(golden_v2.GoldenCaseError):
                    golden_v2._validate_rg06_action_effects(
                        invalid_operation,
                        "$.operations[0]",
                        baseline,
                        invalid,
                        expected,
                    )

    def test_rg06_ingest_ambiguous_role_keeps_nulls_and_half_confidence(self):
        baseline, result, operation = rg06_effect_case(
            "ingest_staged_payment_bank_fact"
        )
        operation["input"].pop("suggested_payment_role")
        result["candidates"][0]["payload"]["payment_role"] = None
        result["candidates"][0]["payload"]["guessed_payment_role"] = None
        result["candidates"][0]["confidence"] = "0.50"
        expected = golden_v2._expected_entity_changes(baseline, result)
        golden_v2._validate_rg06_action_effects(
            operation, "$.operations[0]", baseline, result, expected
        )

        invalid = deepcopy(result)
        invalid["candidates"][0]["confidence"] = "1.00"
        with self.assertRaises(golden_v2.GoldenCaseError):
            golden_v2._validate_rg06_action_effects(
                operation, "$.operations[0]", baseline, invalid, expected
            )

    def test_rg06_no_change_rejects_duplicate_relation_history_member_source_and_evidence(self):
        retry = {
            "action_type": "create_staged_payment",
            "input": rg06_action_inputs()["create_staged_payment"],
            "outcome": {"status": "no_change", "reason_code": "idempotent_replay"},
            "returned_ids": [{"kind": "relation", "id": "relation-staged-payment"}],
        }
        baseline = staged_payment_state()
        mutations = {
            "relation": lambda state: state["relations"].append(
                {
                    "id": "relation-retry-duplicate",
                    "type": "staged_payment",
                    "member_refs": [],
                    "payload": {},
                }
            ),
            "history": lambda state: lifecycle_entity(state)["payload"]["state_history"].append(
                deepcopy(lifecycle_entity(state)["payload"]["state_history"][-1])
            ),
            "member": lambda state: state["relations"][0]["member_refs"].append(
                {"kind": "domain_entity", "id": "installment-deposit"}
            ),
            "source": lambda state: state["sources"].append(
                {"id": "source-retry-duplicate", "type": "staged_payment_bank_fact", "payload": {}}
            ),
            "evidence": lambda state: state["evidence"].append(
                {"id": "evidence-retry-duplicate", "type": "staged_payment_bank_payment", "source_ids": [], "payload": {}}
            ),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                result = deepcopy(baseline)
                mutate(result)
                expected = golden_v2._expected_entity_changes(baseline, result)
                with self.assertRaises(golden_v2.GoldenCaseError):
                    golden_v2._validate_registered_action_effects(
                        retry, "$.operations[1]", result, expected
                    )

    def test_public_validator_accepts_complete_anonymous_rg06_case(self):
        state = staged_payment_state()
        state["balances"] = [
            {"account_id": "asset-bank", "currency": "CNY", "amount": "-300.00"},
            {
                "account_id": "expense-service",
                "currency": "CNY",
                "amount": "300.00",
            },
        ]
        state["reports"] = [
            {
                "period_type": "day",
                "period": "2026-04-28",
                "metrics": [
                    {
                        "metric": "consumption",
                        "applicability": "applicable",
                        "currency": "CNY",
                        "amount": "80.00",
                    },
                    {
                        "metric": "cash_outflow",
                        "applicability": "applicable",
                        "currency": "CNY",
                        "amount": "80.00",
                    },
                ],
            },
            {
                "period_type": "day",
                "period": "2026-05-03",
                "metrics": [
                    {
                        "metric": "consumption",
                        "applicability": "applicable",
                        "currency": "CNY",
                        "amount": "220.00",
                    },
                    {
                        "metric": "cash_outflow",
                        "applicability": "applicable",
                        "currency": "CNY",
                        "amount": "220.00",
                    },
                ],
            },
        ]
        state["posting_reconciliations"] = [
            {
                "id": "reconciliation-rg06-deposit",
                "posting_id": "posting-asset-deposit",
                "status": "matched",
            },
            {
                "id": "reconciliation-rg06-final",
                "posting_id": "posting-asset-final",
                "status": "matched",
            },
        ]
        state["derived_statuses"] = [
            {
                "id": "status-rg06-deposit-reconciliation",
                "target_kind": "transaction",
                "target_id": "transaction-deposit",
                "status_name": "reconciliation_summary",
                "value": "matched",
            },
            {
                "id": "status-rg06-final-reconciliation",
                "target_kind": "transaction",
                "target_id": "transaction-final",
                "status_name": "reconciliation_summary",
                "value": "matched",
            },
            {
                "id": "status-rg06-payment-progress",
                "target_kind": "domain_entity",
                "target_id": "lifecycle-staged-payment",
                "status_name": "payment_progress",
                "value": "paid_in_full",
            },
            {
                "id": "status-rg06-fulfillment",
                "target_kind": "domain_entity",
                "target_id": "lifecycle-staged-payment",
                "status_name": "fulfillment_status",
                "value": "fulfilled",
            },
            {
                "id": "status-rg06-reconciliation",
                "target_kind": "domain_entity",
                "target_id": "lifecycle-staged-payment",
                "status_name": "reconciliation",
                "value": "complete",
            },
        ]
        case = {
            "contract": "unifiedledger.golden-case",
            "contract_version": "2.0.0",
            "case": {
                "id": "RG-06",
                "level": "core_required",
                "rule_version": 1,
                "approval_status": "draft_for_review",
                "ledger_id": "ledger-rg06-anonymous",
                "timezone": "Asia/Shanghai",
                "currencies": [{"code": "CNY", "precision": 2}],
            },
            "roots": [
                {
                    "id": state["root_id"],
                    "purpose": "rg06_complete_staged_payment",
                    "initial_state_id": state["id"],
                    "operation_ids": [],
                }
            ],
            "states": [state],
            "operations": [],
        }
        schema = json.loads(
            (ROOT / "schemas" / "golden-case-v2.schema.json").read_text(
                encoding="utf-8"
            )
        )
        errors = list(Draft202012Validator(schema).iter_errors(case))

        self.assertEqual([], errors, [error.message for error in errors])
        validate_golden_case_v2(case)

    def test_public_validator_accepts_ingest_then_confirm_without_candidate_link_mutation(self):
        initial = creation_result()
        ingested = deepcopy(initial)
        ingested["sources"] = [
            {
                "id": "source-import",
                "type": "staged_payment_bank_fact",
                "payload": {
                    "amount": "80.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                },
            }
        ]
        ingested["evidence"] = [
            {
                "id": "evidence-import",
                "type": "staged_payment_bank_payment",
                "source_ids": ["source-import"],
                "payload": {
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                },
            }
        ]
        ingested["candidates"] = [
            {
                "id": "candidate-import",
                "type": "staged_payment",
                "source_ids": ["source-import"],
                "confidence": "1.00",
                "payload": {
                    "payment_role": "deposit",
                    "amount": "80.00",
                    "currency": "CNY",
                    "source_payment_at": "2026-04-28T10:00:00+08:00",
                    "evidence_ref": "evidence-import",
                    "provenance": {
                        "rule": "staged_payment_bank_fact",
                        "rule_version": 1,
                    },
                    "requires_confirmation": [
                        "relation_id",
                        "payment_role",
                        "category_id",
                        "funding_account_id",
                    ],
                },
                "status_history": [
                    {
                        "id": "candidate-status-import-pending",
                        "sequence": 1,
                        "status": "pending_confirmation",
                    }
                ],
            }
        ]
        confirmed = deposit_result()
        confirmed["sources"] = deepcopy(ingested["sources"])
        confirmed["evidence"] = deepcopy(ingested["evidence"])
        confirmed["evidence"][0]["payload"]["payment_id"] = "installment-deposit"
        confirmed["candidates"] = deepcopy(ingested["candidates"])
        confirmed["candidates"][0]["status_history"].append(
            {
                "id": "candidate-status-import-confirmed",
                "sequence": 2,
                "status": "confirmed",
            }
        )
        payment_entity(confirmed, "deposit")["payload"]["source_payment_at"] = (
            confirmed["candidates"][0]["payload"]["source_payment_at"]
        )
        confirmed["confirmations"] = [
            {
                "id": "confirmation-candidate",
                "type": "candidate_confirmation",
                "operation_id": "operation-confirm-candidate",
                "subject": {"kind": "candidate", "id": "candidate-import"},
                "payload": {},
            }
        ]
        confirmed["transaction_versions"][0]["confirmation_id"] = (
            "confirmation-candidate"
        )
        confirmed["evidence_links"] = [
            {
                "id": "link-candidate",
                "evidence_id": "evidence-import",
                "target_kind": "posting",
                "target_id": "posting-asset-deposit",
                "role": "payment_asset_posting",
            }
        ]
        for state in (initial, ingested, confirmed):
            refresh_public_projections(state)

        root_id = initial["root_id"]
        states = [initial, ingested, confirmed]
        as_of_operation_ids = [
            None,
            "operation-ingest-bank-fact",
            "operation-confirm-candidate",
        ]
        for index, (state, as_of_operation_id) in enumerate(
            zip(states, as_of_operation_ids)
        ):
            state["id"] = f"state-rg06-ingest-confirm-{index}"
            state["root_id"] = root_id
            state["as_of_operation_id"] = as_of_operation_id
        ingest_deltas, ingest_status_changes = operation_deltas(initial, ingested)
        ingest_operation = {
            "id": "operation-ingest-bank-fact",
            "root_id": root_id,
            "sequence": 1,
            "operation_class": "creation",
            "action_type": "ingest_staged_payment_bank_fact",
            "baseline_state_id": initial["id"],
            "result_state_id": ingested["id"],
            "input": deepcopy(rg06_action_inputs()["ingest_staged_payment_bank_fact"]),
            "outcome": {"status": "accepted"},
            "status_changes": ingest_status_changes,
            "returned_ids": [
                {"kind": "source", "id": "source-import"},
                {"kind": "evidence", "id": "evidence-import"},
                {"kind": "candidate", "id": "candidate-import"},
            ],
            "deltas": ingest_deltas,
        }
        confirm_deltas, confirm_status_changes = operation_deltas(ingested, confirmed)
        confirm_operation = {
            "id": "operation-confirm-candidate",
            "root_id": root_id,
            "sequence": 2,
            "operation_class": "creation",
            "action_type": "confirm_staged_payment_candidate",
            "baseline_state_id": ingested["id"],
            "result_state_id": confirmed["id"],
            "input": deepcopy(
                rg06_action_inputs()["confirm_staged_payment_candidate"]
            ),
            "outcome": {"status": "accepted"},
            "status_changes": confirm_status_changes,
            "returned_ids": [
                {"kind": "confirmation", "id": "confirmation-candidate"},
                {"kind": "transaction", "id": "transaction-deposit"},
                {"kind": "domain_entity", "id": "installment-deposit"},
                {"kind": "evidence_link", "id": "link-candidate"},
            ],
            "deltas": confirm_deltas,
        }
        case = {
            "contract": "unifiedledger.golden-case",
            "contract_version": "2.0.0",
            "case": {
                "id": "RG-06",
                "level": "core_required",
                "rule_version": 1,
                "approval_status": "draft_for_review",
                "ledger_id": "ledger-rg06-ingest-confirm",
                "timezone": "Asia/Shanghai",
                "currencies": [{"code": "CNY", "precision": 2}],
            },
            "roots": [
                {
                    "id": root_id,
                    "purpose": "rg06_ingest_then_confirm",
                    "initial_state_id": initial["id"],
                    "operation_ids": [
                        ingest_operation["id"],
                        confirm_operation["id"],
                    ],
                }
            ],
            "states": states,
            "operations": [ingest_operation, confirm_operation],
        }

        validate_golden_case_v2(case)

        invalid = deepcopy(case)
        invalid["states"][2]["candidates"][0]["payload"]["transaction_id"] = (
            "transaction-deposit"
        )
        with self.assertRaises(golden_v2.GoldenCaseError):
            validate_golden_case_v2(invalid)

        for label, source_time in (
            ("missing", None),
            ("mismatched", "2026-04-29T10:00:00+08:00"),
        ):
            with self.subTest(source_time=label):
                invalid = deepcopy(case)
                payment = payment_entity(invalid["states"][2], "deposit")
                if source_time is None:
                    payment["payload"].pop("source_payment_at")
                else:
                    payment["payload"]["source_payment_at"] = source_time
                with self.assertRaises(golden_v2.GoldenCaseError):
                    validate_golden_case_v2(invalid)

    def test_rg06_actions_have_exact_entity_effect_counts(self):
        expected = {
            "create_staged_payment": {
                "relations": (1, 0, 0),
                "domain_entities": (1, 0, 0),
            },
            "record_staged_payment_installment": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "confirmations": (1, 0, 0),
                "relations": (0, 1, 0),
                "domain_entities": (1, 1, 0),
                "posting_reconciliations": (1, 0, 0),
            },
            "change_staged_payment_fulfillment": {
                "domain_entities": (0, 1, 0),
            },
            "confirm_staged_payment_completion": {
                "domain_entities": (0, 1, 0),
            },
            "ingest_staged_payment_bank_fact": {
                "sources": (1, 0, 0),
                "evidence": (1, 0, 0),
                "candidates": (1, 0, 0),
            },
            "confirm_staged_payment_candidate": {
                "transactions": (1, 0, 0),
                "transaction_versions": (1, 0, 0),
                "posting_sets": (1, 0, 0),
                "postings": (2, 0, 0),
                "evidence": (0, 1, 0),
                "candidates": (0, 1, 0),
                "confirmations": (1, 0, 0),
                "evidence_links": (1, 0, 0),
                "relations": (0, 1, 0),
                "domain_entities": (1, 1, 0),
                "posting_reconciliations": (1, 0, 0),
            },
            "link_staged_payment_evidence": {
                "sources": (1, 0, 0),
                "evidence": (1, 0, 0),
                "evidence_links": (1, 0, 0),
                "posting_reconciliations": (0, 1, 0),
            },
            "merge_staged_payment_mirror_evidence": {
                "sources": (1, 0, 0),
                "evidence": (1, 0, 0),
            },
        }

        observed = {
            action: golden_v2._ACCEPTED_ACTION_ENTITY_COUNTS.get(action)
            for action in expected
        }

        self.assertEqual(expected, observed)


if __name__ == "__main__":
    unittest.main()
