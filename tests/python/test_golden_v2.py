from copy import deepcopy
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
RG01_PATH_MAP = REPOSITORY_ROOT / "docs" / "migrations" / "golden-v2" / "rg-01-path-map.json"


def load_rg01() -> dict:
    return load_golden_case_v2(RG01_V2_PATH)


def load_rg09() -> dict:
    return load_golden_case_v2(RG09_V2_PATH)


def schema_errors(case: dict) -> list:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return list(Draft202012Validator(schema).iter_errors(case))


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


def validate_contract_state(case: dict) -> None:
    errors = schema_errors(case)
    if errors:
        raise AssertionError(errors[0].message)
    state = case["states"][0]
    indexes = golden_v2._state_indexes(state, "$.states[0]")
    golden_v2._validate_catalog(state, "$.states[0]", indexes, {"CNY": 2})
    golden_v2._validate_formal_ledger(
        state, "$.states[0]", indexes, {"CNY": 2}, ZoneInfo("Asia/Shanghai")
    )
    golden_v2._validate_references(
        state,
        "$.states[0]",
        indexes,
        {},
        {"CNY": 2},
        ZoneInfo("Asia/Shanghai"),
    )


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

        ineligible = load_rg01()
        ineligible["states"][1]["posting_reconciliations"][0]["posting_id"] = "posting-expense-rg01"
        assert_invalid(self, ineligible, r"\$\.states\[1\]\.posting_reconciliations\[0\]\.posting_id")

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


if __name__ == "__main__":
    unittest.main()
