from __future__ import annotations

from decimal import Decimal
import json
from pathlib import Path
import re
import unittest

from jsonschema import Draft202012Validator

from golden_cases.v2 import (
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    validate_golden_case_v2,
)


ROOT = Path(__file__).resolve().parents[2]
EXPECTED_PATH = ROOT / "docs" / "migrations" / "golden-v2" / "rg-03-expected.json"
V1_PATH = ROOT / "golden" / "rules" / "rg-03.json"
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
UUID_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)

ENTITY_COLLECTIONS = (
    "catalog_accounts",
    "catalog_categories",
    "transactions",
    "transaction_versions",
    "posting_sets",
    "postings",
    "sources",
    "candidates",
    "confirmations",
    "evidence",
    "evidence_links",
    "relations",
    "domain_entities",
    "audit_links",
    "posting_reconciliations",
)


def state_payload(state: dict) -> dict:
    return {
        key: value
        for key, value in state.items()
        if key not in {"id", "as_of_operation_id"}
    }


def empty_deltas(operation: dict) -> bool:
    return (
        all(
            not ids
            for collection in operation["deltas"]["entity_changes"].values()
            for ids in collection.values()
        )
        and all(
            not values
            for values in operation["deltas"]["value_changes"].values()
        )
        and operation["status_changes"] == []
    )


def transaction_parts(state: dict, transaction_id: str) -> tuple[dict, list[dict]]:
    transaction = next(item for item in state["transactions"] if item["id"] == transaction_id)
    version = next(
        item
        for item in state["transaction_versions"]
        if item["id"] == transaction["current_version_id"]
    )
    posting_set = next(
        item for item in state["posting_sets"] if item["id"] == version["posting_set_id"]
    )
    postings = [
        item for item in state["postings"] if item["id"] in posting_set["posting_ids"]
    ]
    return version, postings


class RG03GoldenV2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
        cls.v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.roots = {root["id"]: root for root in cls.case["roots"]}
        cls.roots_by_purpose = {root["purpose"]: root for root in cls.case["roots"]}
        cls.states = {state["id"]: state for state in cls.case["states"]}
        cls.operations = {operation["id"]: operation for operation in cls.case["operations"]}

    def root_operations(self, purpose: str) -> list[dict]:
        return [
            self.operations[operation_id]
            for operation_id in self.roots_by_purpose[purpose]["operation_ids"]
        ]

    def assert_opening_state(self, state: dict) -> None:
        opening = self.v1["opening"]["transactions"][0]
        self.assertEqual(len(state["transactions"]), 1)
        self.assertEqual(state["transactions"][0]["id"], opening["id"])
        version, postings = transaction_parts(state, opening["id"])
        self.assertEqual(
            [version[name] for name in ("occurred_at", "statistics_at", "effective_at")],
            [opening["occurred_at"]] * 3,
        )
        self.assertEqual(
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in postings
            },
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in opening["postings"]
            },
        )
        self.assertEqual(sum(Decimal(item["amount"]) for item in postings), Decimal("0"))
        balances = {item["account_id"]: item["amount"] for item in state["balances"]}
        self.assertEqual(
            {key: balances[key] for key in self.v1["opening"]["expected_balances"]},
            self.v1["opening"]["expected_balances"],
        )

    def test_schema_and_complete_semantic_validator(self):
        Draft202012Validator.check_schema(self.schema)
        Draft202012Validator(self.schema).validate(self.case)
        validate_golden_case_v2(self.case)
        self.assertEqual(self.case["contract"], "unifiedledger.golden-case")
        self.assertEqual(self.case["contract_version"], "2.0.0")
        self.assertEqual(self.case["case"]["approval_status"], "approved")

    def test_exact_topology_root_purposes_and_operation_inventory(self):
        self.assertEqual(
            (len(self.case["roots"]), len(self.case["operations"]), len(self.case["states"])),
            (13, 20, 33),
        )
        invalid_purposes = {
            "rg03_invalid_" + item["id"].replace("-", "_")
            for item in self.v1["invalid_manual_inputs"]
        }
        self.assertEqual(
            set(self.roots_by_purpose),
            {
                "rg03_manual_account_transfer",
                "rg03_import_lifecycle",
                "rg03_incomplete_source",
                *invalid_purposes,
            },
        )
        self.assertEqual(
            [root["id"] for root in self.case["roots"]],
            sorted(root["id"] for root in self.case["roots"]),
        )
        self.assertEqual(
            [(item["root_id"], item["sequence"]) for item in self.case["operations"]],
            sorted((item["root_id"], item["sequence"]) for item in self.case["operations"]),
        )
        statuses = [item["outcome"]["status"] for item in self.case["operations"]]
        self.assertEqual(statuses.count("accepted"), 5)
        self.assertEqual(statuses.count("no_change"), 5)
        self.assertEqual(statuses.count("rejected"), 10)
        self.assertEqual(
            {purpose: len(root["operation_ids"]) for purpose, root in self.roots_by_purpose.items() if "invalid" not in purpose},
            {
                "rg03_manual_account_transfer": 2,
                "rg03_import_lifecycle": 6,
                "rg03_incomplete_source": 2,
            },
        )

    def test_every_root_is_reachable_and_every_state_is_owned(self):
        referenced_operations = []
        referenced_states = []
        for root in self.case["roots"]:
            current_state_id = root["initial_state_id"]
            referenced_states.append(current_state_id)
            self.assertEqual(self.states[current_state_id]["root_id"], root["id"])
            for sequence, operation_id in enumerate(root["operation_ids"], start=1):
                operation = self.operations[operation_id]
                referenced_operations.append(operation_id)
                self.assertEqual(operation["root_id"], root["id"])
                self.assertEqual(operation["sequence"], sequence)
                self.assertEqual(operation["baseline_state_id"], current_state_id)
                current_state_id = operation["result_state_id"]
                referenced_states.append(current_state_id)
                self.assertEqual(self.states[current_state_id]["root_id"], root["id"])
                self.assertEqual(self.states[current_state_id]["as_of_operation_id"], operation_id)
        self.assertEqual(set(referenced_operations), set(self.operations))
        self.assertEqual(set(referenced_states), set(self.states))
        self.assertEqual(len(referenced_states), len(set(referenced_states)))

    def test_all_independent_roots_replay_the_v1_opening(self):
        for root in self.case["roots"]:
            with self.subTest(purpose=root["purpose"]):
                self.assert_opening_state(self.states[root["initial_state_id"]])

    def test_manual_transfer_preserves_postings_times_reports_and_reconciliation(self):
        accepted, retry = self.root_operations("rg03_manual_account_transfer")
        source = self.v1["manual_create"]
        state = self.states[accepted["result_state_id"]]
        version, postings = transaction_parts(state, source["expected"]["transaction"]["id"])
        self.assertEqual(
            accepted["input"],
            {
                key: value
                for key, value in source["request"].items()
                if key != "kind"
            }
            | {"explicit_confirmation": True},
        )
        self.assertEqual(
            [version[name] for name in ("occurred_at", "statistics_at", "effective_at")],
            [source["request"]["occurred_at"]] * 3,
        )
        self.assertEqual(
            {item["role"]: (item["account_id"], item["amount"]) for item in postings},
            {
                "transfer_principal_out": ("asset-bank-a", "-60.00"),
                "transfer_principal_in": ("asset-wallet-b", "59.00"),
                "transfer_fee": ("expense-account-transfer-fee", "1.00"),
            },
        )
        self.assertEqual(sum(Decimal(item["amount"]) for item in postings), Decimal("0"))
        self.assertEqual(
            {item["posting_id"]: item["status"] for item in state["posting_reconciliations"]},
            {
                "posting-source-rg03-manual": "pending",
                "posting-destination-rg03-manual": "pending",
            },
        )
        for report in state["reports"]:
            metrics = {item["metric"]: item["amount"] for item in report["metrics"]}
            self.assertEqual(metrics["internal_transfer_amount"], "59.00")
            self.assertEqual(metrics["consumption"], "1.00")
            self.assertEqual(metrics["cash_outflow"], "1.00")
            self.assertEqual(metrics["cash_inflow"], "0.00")
            self.assertEqual(metrics["ordinary_income"], "0.00")
            self.assertEqual(metrics["net_worth_change"], "-1.00")
        self.assertEqual(retry["input"], accepted["input"])
        self.assertEqual(retry["returned_ids"], accepted["returned_ids"])
        self.assertTrue(empty_deltas(retry))
        self.assertEqual(
            state_payload(self.states[retry["baseline_state_id"]]),
            state_payload(self.states[retry["result_state_id"]]),
        )

    def test_complete_import_intake_confirmation_and_retries_match_v1(self):
        intake, intake_retry, confirmation, confirmation_retry, _, _ = self.root_operations(
            "rg03_import_lifecycle"
        )
        source_operation = self.v1["import_lifecycle"]["ordered_operations"][0]
        pending = self.states[intake["result_state_id"]]
        candidate = pending["candidates"][0]
        self.assertEqual(candidate["id"], source_operation["expected"]["candidate"]["id"])
        self.assertEqual(candidate["status_history"][-1]["status"], "pending_confirmation")
        self.assertEqual(candidate["source_ids"], source_operation["expected"]["candidate"]["source_refs"])
        self.assertEqual(pending["transactions"], self.states[intake["baseline_state_id"]]["transactions"])
        self.assertEqual(pending["balances"], self.states[intake["baseline_state_id"]]["balances"])
        self.assertEqual(pending["reports"], self.states[intake["baseline_state_id"]]["reports"])
        self.assertTrue(empty_deltas(intake_retry))
        self.assertEqual(intake_retry["returned_ids"], intake["returned_ids"])

        confirmed = self.states[confirmation["result_state_id"]]
        candidate = confirmed["candidates"][0]
        self.assertEqual(
            [item["status"] for item in candidate["status_history"]],
            ["pending_confirmation", "confirmed"],
        )
        self.assertEqual(candidate["payload"]["transaction_id"], "tx-transfer-rg03-imported")
        version, postings = transaction_parts(confirmed, "tx-transfer-rg03-imported")
        self.assertEqual(
            [version[name] for name in ("occurred_at", "statistics_at", "effective_at")],
            ["2026-01-21T11:00:00+08:00"] * 3,
        )
        self.assertEqual(
            {item["role"]: (item["account_id"], item["amount"]) for item in postings},
            {
                "transfer_principal_out": ("asset-bank-a", "-60.00"),
                "transfer_principal_in": ("asset-wallet-b", "59.00"),
                "transfer_fee": ("expense-account-transfer-fee", "1.00"),
            },
        )
        self.assertEqual(
            {item["posting_id"]: item["status"] for item in confirmed["posting_reconciliations"]},
            {
                "posting-source-rg03-imported": "matched",
                "posting-destination-rg03-imported": "pending",
            },
        )
        self.assertEqual(
            next(item for item in confirmed["derived_statuses"] if item["target_kind"] == "transaction")["value"],
            "partial",
        )
        self.assertTrue(empty_deltas(confirmation_retry))
        self.assertEqual(confirmation_retry["returned_ids"], confirmation["returned_ids"])

    def test_mirror_adds_only_provenance_link_and_reconciliation(self):
        _, _, _, confirmation_retry, mirror, mirror_retry = self.root_operations(
            "rg03_import_lifecycle"
        )
        baseline = self.states[confirmation_retry["result_state_id"]]
        result = self.states[mirror["result_state_id"]]
        for collection in (
            "transactions",
            "transaction_versions",
            "posting_sets",
            "postings",
            "candidates",
            "confirmations",
            "balances",
            "reports",
        ):
            self.assertEqual(result[collection], baseline[collection])
        self.assertEqual(
            {key: value for key, value in mirror["deltas"]["entity_changes"].items() if any(value.values())},
            {
                "sources": {
                    "added_ids": ["source-record-rg03-credit-mirror"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
                "evidence": {
                    "added_ids": ["evidence-rg03-credit-mirror"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
                "evidence_links": {
                    "added_ids": ["match-rg03-credit-mirror"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
                "posting_reconciliations": {
                    "added_ids": [],
                    "changed_ids": [
                        next(
                            item["id"]
                            for item in result["posting_reconciliations"]
                            if item["posting_id"] == "posting-destination-rg03-imported"
                        )
                    ],
                    "removed_ids": [],
                },
            },
        )
        mirror_source = next(
            item for item in result["sources"] if item["id"] == "source-record-rg03-credit-mirror"
        )
        self.assertEqual(mirror_source["type"], "account_credit_observation")
        self.assertEqual(
            set(mirror_source["payload"]),
            {"account_id", "credit_amount", "currency", "observed_at", "evidence_id"},
        )
        self.assertEqual(
            next(item for item in result["evidence_links"] if item["id"] == "match-rg03-credit-mirror"),
            {
                "id": "match-rg03-credit-mirror",
                "evidence_id": "evidence-rg03-credit-mirror",
                "target_kind": "posting",
                "target_id": "posting-destination-rg03-imported",
                "role": "destination_asset_posting",
            },
        )
        self.assertEqual(
            {item["posting_id"]: item["status"] for item in result["posting_reconciliations"]},
            {
                "posting-source-rg03-imported": "matched",
                "posting-destination-rg03-imported": "matched",
            },
        )
        self.assertEqual(
            next(item for item in result["derived_statuses"] if item["target_kind"] == "transaction")["value"],
            "matched",
        )
        self.assertEqual(
            mirror["returned_ids"],
            [
                {"kind": "source", "id": "source-record-rg03-credit-mirror"},
                {"kind": "evidence", "id": "evidence-rg03-credit-mirror"},
                {"kind": "evidence_link", "id": "match-rg03-credit-mirror"},
            ],
        )
        self.assertEqual(mirror_retry["input"], mirror["input"])
        self.assertEqual(mirror_retry["returned_ids"], mirror["returned_ids"])
        self.assertTrue(empty_deltas(mirror_retry))
        self.assertEqual(
            state_payload(self.states[mirror_retry["baseline_state_id"]]),
            state_payload(self.states[mirror_retry["result_state_id"]]),
        )

    def test_incomplete_source_omits_destination_and_has_zero_formal_effects(self):
        accepted, retry = self.root_operations("rg03_incomplete_source")
        baseline = self.states[accepted["baseline_state_id"]]
        result = self.states[accepted["result_state_id"]]
        source = result["sources"][0]
        candidate = result["candidates"][0]
        self.assertNotIn("destination_account_id", accepted["input"])
        self.assertNotIn("destination_account_id", source["payload"])
        self.assertNotIn("destination_account_id", candidate["payload"])
        self.assertNotIn("destination_credit_amount", candidate["payload"])
        self.assertNotIn("fee_amount", candidate["payload"])
        self.assertEqual(source["payload"]["completeness"], "missing_destination")
        self.assertEqual(
            candidate["payload"]["requires_confirmation"],
            ["destination_account_id", "formal_transaction_creation"],
        )
        for collection in (
            "transactions",
            "transaction_versions",
            "posting_sets",
            "postings",
            "confirmations",
            "evidence_links",
            "posting_reconciliations",
            "balances",
            "reports",
        ):
            self.assertEqual(result[collection], baseline[collection])
        self.assertEqual(
            {key: value for key, value in accepted["deltas"]["entity_changes"].items() if any(value.values())},
            {
                "sources": {
                    "added_ids": ["source-record-rg03-unknown-debit"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
                "candidates": {
                    "added_ids": ["candidate-transfer-rg03-unknown-debit"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
                "evidence": {
                    "added_ids": ["evidence-rg03-unknown-debit"],
                    "changed_ids": [],
                    "removed_ids": [],
                },
            },
        )
        self.assertEqual(retry["input"], accepted["input"])
        self.assertEqual(retry["returned_ids"], accepted["returned_ids"])
        self.assertTrue(empty_deltas(retry))
        self.assertEqual(
            state_payload(self.states[retry["baseline_state_id"]]),
            state_payload(self.states[retry["result_state_id"]]),
        )

    def test_all_ten_invalid_inputs_are_exact_atomic_rejections(self):
        expected_ids = {
            "missing-source",
            "missing-destination",
            "same-account",
            "unknown-account",
            "non-owned-account",
            "non-financial-account",
            "zero-principal",
            "negative-principal",
            "unbalanced-fee",
            "cross-currency",
        }
        self.assertEqual({item["id"] for item in self.v1["invalid_manual_inputs"]}, expected_ids)
        for invalid in self.v1["invalid_manual_inputs"]:
            invalid_id = invalid["id"]
            purpose = "rg03_invalid_" + invalid_id.replace("-", "_")
            operation = self.root_operations(purpose)[0]
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            root_id = deterministic_v2_root_id("RG-03", "$.invalid_manual_inputs[*]", invalid_id)
            request_id = deterministic_v2_migration_id(
                "RG-03", root_id, "request", "$.invalid_manual_inputs[*].id", invalid_id
            )
            self.assertEqual(operation["operation_class"], "rejection")
            self.assertEqual(operation["action_type"], "manual_account_transfer")
            self.assertEqual(operation["attempted_input"], {"request_id": request_id, **invalid["input"]})
            self.assertEqual(
                operation["outcome"],
                {
                    "status": "rejected",
                    "reason_code": invalid["expected"]["reason"],
                    "field_path": f"$.attempted_input.{invalid['expected']['field']}",
                },
            )
            self.assertEqual(operation["returned_ids"], [])
            self.assertTrue(empty_deltas(operation))
            self.assertEqual(state_payload(baseline), state_payload(result))
            self.assert_opening_state(result)

    def test_all_generated_and_preserved_uuids_are_exhaustive(self):
        expected = set(UUID_PATTERN.findall(V1_PATH.read_text(encoding="utf-8")))

        def generated(actual, root_id, kind, locator, occurrence):
            value = deterministic_v2_migration_id(
                "RG-03", root_id, kind, locator, occurrence
            )
            self.assertEqual(actual, value)
            expected.add(value)
            return value

        opening_id = self.v1["opening"]["transactions"][0]["id"]

        manual_root = self.roots_by_purpose["rg03_manual_account_transfer"]
        manual_root_id = deterministic_v2_root_id(
            "RG-03", "$.manual_create", "request-rg03-manual-create"
        )
        self.assertEqual(manual_root["id"], manual_root_id)
        expected.add(manual_root_id)
        generated(
            manual_root["initial_state_id"],
            manual_root_id,
            "state",
            "$.opening.transactions[*]",
            opening_id,
        )
        manual_initial = self.states[manual_root["initial_state_id"]]
        manual_opening_version, _ = transaction_parts(manual_initial, opening_id)
        generated(
            manual_opening_version["id"],
            manual_root_id,
            "transaction_version",
            "$.opening.transactions[*]",
            opening_id,
        )
        generated(
            manual_opening_version["posting_set_id"],
            manual_root_id,
            "posting_set",
            "$.opening.transactions[*]",
            opening_id,
        )
        manual_specs = [
            ("$.manual_create.request", "request-rg03-manual-create"),
            ("$.idempotency.repeated_manual_request_id", "request-rg03-manual-create"),
        ]
        manual_operations = self.root_operations("rg03_manual_account_transfer")
        for operation, (locator, occurrence) in zip(
            manual_operations, manual_specs, strict=True
        ):
            generated(operation["id"], manual_root_id, "operation", locator, occurrence)
            generated(
                operation["result_state_id"],
                manual_root_id,
                "state",
                locator,
                occurrence,
            )
        manual_created = self.states[manual_operations[0]["result_state_id"]]
        generated(
            manual_created["confirmations"][0]["id"],
            manual_root_id,
            "confirmation",
            "$.manual_create.confirmation",
            "request-rg03-manual-create",
        )
        for reconciliation in manual_created["posting_reconciliations"]:
            generated(
                reconciliation["id"],
                manual_root_id,
                "posting_reconciliation",
                "$.manual_create.expected.reconciliation",
                reconciliation["posting_id"],
            )
        manual_summary = next(
            item
            for item in manual_created["derived_statuses"]
            if item["target_kind"] == "transaction"
        )
        generated(
            manual_summary["id"],
            manual_root_id,
            "derived_status",
            "$.manual_create.expected.reconciliation",
            "tx-transfer-rg03-manual",
        )

        import_root = self.roots_by_purpose["rg03_import_lifecycle"]
        import_root_id = deterministic_v2_root_id(
            "RG-03", "$.import_lifecycle", "import-complete-source"
        )
        self.assertEqual(import_root["id"], import_root_id)
        expected.add(import_root_id)
        generated(
            import_root["initial_state_id"],
            import_root_id,
            "state",
            "$.opening",
            "import-complete-source",
        )
        import_initial = self.states[import_root["initial_state_id"]]
        import_opening_version, _ = transaction_parts(import_initial, opening_id)
        generated(
            import_opening_version["id"],
            import_root_id,
            "transaction_version",
            "$.opening.transactions[*]",
            opening_id,
        )
        generated(
            import_opening_version["posting_set_id"],
            import_root_id,
            "posting_set",
            "$.opening.transactions[*]",
            opening_id,
        )
        import_specs = [
            ("$.import_lifecycle.ordered_operations[*]", "import-complete-source"),
            ("$.idempotency.repeated_source_request_id", "request-rg03-import-source"),
            ("$.import_lifecycle.ordered_operations[*]", "confirm-import-candidate"),
            ("$.idempotency.repeated_confirmation_request_id", "request-rg03-confirm-candidate"),
            ("$.import_lifecycle.ordered_operations[*]", "merge-mirror-evidence"),
            ("$.idempotency.repeated_mirror_request_id", "request-rg03-import-mirror"),
        ]
        import_operations = self.root_operations("rg03_import_lifecycle")
        for operation, (locator, occurrence) in zip(
            import_operations, import_specs, strict=True
        ):
            generated(operation["id"], import_root_id, "operation", locator, occurrence)
            generated(
                operation["result_state_id"],
                import_root_id,
                "state",
                locator,
                occurrence,
            )
        pending_state = self.states[import_operations[0]["result_state_id"]]
        pending_status = pending_state["candidates"][0]["status_history"][0]
        generated(
            pending_status["id"],
            import_root_id,
            "candidate_status",
            "$.import_lifecycle.ordered_operations[*].expected.candidate.status",
            "import-complete-source",
        )
        confirmed_state = self.states[import_operations[2]["result_state_id"]]
        confirmed_status = confirmed_state["candidates"][0]["status_history"][1]
        generated(
            confirmed_status["id"],
            import_root_id,
            "candidate_status",
            "$.import_lifecycle.ordered_operations[*].expected.candidate_status",
            "confirm-import-candidate",
        )
        generated(
            confirmed_state["confirmations"][0]["id"],
            import_root_id,
            "confirmation",
            "$.import_lifecycle.ordered_operations[*].expected.transaction.provenance.confirmation_ref",
            "request-rg03-confirm-candidate",
        )
        reconciliation_paths = {
            "posting-source-rg03-imported": "$.import_lifecycle.ordered_operations[*].expected.reconciliation.posting-source-rg03-imported",
            "posting-destination-rg03-imported": "$.import_lifecycle.ordered_operations[*].expected.reconciliation.posting-destination-rg03-imported",
        }
        for reconciliation in confirmed_state["posting_reconciliations"]:
            generated(
                reconciliation["id"],
                import_root_id,
                "posting_reconciliation",
                reconciliation_paths[reconciliation["posting_id"]],
                reconciliation["posting_id"],
            )
        import_summary = next(
            item
            for item in confirmed_state["derived_statuses"]
            if item["target_kind"] == "transaction"
        )
        generated(
            import_summary["id"],
            import_root_id,
            "derived_status",
            "$.import_lifecycle.ordered_operations[*].expected.reconciliation.transaction",
            "tx-transfer-rg03-imported",
        )

        incomplete_root = self.roots_by_purpose["rg03_incomplete_source"]
        incomplete_root_id = deterministic_v2_root_id(
            "RG-03", "$.unknown_one_sided_debit", "request-rg03-unknown-debit"
        )
        self.assertEqual(incomplete_root["id"], incomplete_root_id)
        expected.add(incomplete_root_id)
        generated(
            incomplete_root["initial_state_id"],
            incomplete_root_id,
            "state",
            "$.opening",
            "request-rg03-unknown-debit",
        )
        incomplete_initial = self.states[incomplete_root["initial_state_id"]]
        incomplete_opening_version, _ = transaction_parts(incomplete_initial, opening_id)
        generated(
            incomplete_opening_version["id"],
            incomplete_root_id,
            "transaction_version",
            "$.opening.transactions[*]",
            opening_id,
        )
        generated(
            incomplete_opening_version["posting_set_id"],
            incomplete_root_id,
            "posting_set",
            "$.opening.transactions[*]",
            opening_id,
        )
        incomplete_specs = [
            ("$.unknown_one_sided_debit.input", "request-rg03-unknown-debit"),
            ("$.unknown_one_sided_debit.retry.repeated_request_id", "request-rg03-unknown-debit"),
        ]
        incomplete_operations = self.root_operations("rg03_incomplete_source")
        for operation, (locator, occurrence) in zip(
            incomplete_operations, incomplete_specs, strict=True
        ):
            generated(
                operation["id"],
                incomplete_root_id,
                "operation",
                locator,
                occurrence,
            )
            generated(
                operation["result_state_id"],
                incomplete_root_id,
                "state",
                locator,
                occurrence,
            )
        incomplete_state = self.states[incomplete_operations[0]["result_state_id"]]
        generated(
            incomplete_state["candidates"][0]["status_history"][0]["id"],
            incomplete_root_id,
            "candidate_status",
            "$.unknown_one_sided_debit.expected.candidate.status",
            "candidate-transfer-rg03-unknown-debit",
        )

        for invalid in self.v1["invalid_manual_inputs"]:
            invalid_id = invalid["id"]
            purpose = "rg03_invalid_" + invalid_id.replace("-", "_")
            root = self.roots_by_purpose[purpose]
            root_id = deterministic_v2_root_id("RG-03", "$.invalid_manual_inputs[*]", invalid_id)
            expected.add(root_id)
            operation = self.root_operations(purpose)[0]
            self.assertEqual(root["id"], root_id)
            generated(
                root["initial_state_id"],
                root_id,
                "state",
                "$.opening",
                invalid_id,
            )
            invalid_initial = self.states[root["initial_state_id"]]
            invalid_opening_version, _ = transaction_parts(invalid_initial, opening_id)
            generated(
                invalid_opening_version["id"],
                root_id,
                "transaction_version",
                "$.opening.transactions[*]",
                opening_id,
            )
            generated(
                invalid_opening_version["posting_set_id"],
                root_id,
                "posting_set",
                "$.opening.transactions[*]",
                opening_id,
            )
            generated(
                operation["id"],
                root_id,
                "operation",
                "$.invalid_manual_inputs[*]",
                invalid_id,
            )
            generated(
                operation["attempted_input"]["request_id"],
                root_id,
                "request",
                "$.invalid_manual_inputs[*].id",
                invalid_id,
            )
            generated(
                operation["result_state_id"],
                root_id,
                "state",
                "$.invalid_manual_inputs[*].expected",
                invalid_id,
            )

        actual = set(UUID_PATTERN.findall(EXPECTED_PATH.read_text(encoding="utf-8")))
        self.assertEqual(len(actual), 113)
        self.assertEqual(actual, expected)

    def test_forbidden_effects_and_out_of_scope_behaviors_are_unreachable(self):
        serialized = EXPECTED_PATH.read_text(encoding="utf-8")
        for forbidden in self.v1["forbidden_side_effects"]:
            self.assertNotIn(forbidden, serialized)
        for value in self.v1["out_of_scope"].values():
            self.assertNotIn(f'"{value}"', serialized)
        account_ids = {
            account["id"]
            for state in self.case["states"]
            for account in state["catalog"]["accounts"]
        }
        self.assertFalse(any("suspense" in account_id or "balancing" in account_id for account_id in account_ids))
        mirror = self.root_operations("rg03_import_lifecycle")[4]
        self.assertEqual(mirror["deltas"]["entity_changes"]["transactions"]["added_ids"], [])
        self.assertEqual(mirror["deltas"]["entity_changes"]["transaction_versions"]["added_ids"], [])
        self.assertEqual(mirror["deltas"]["entity_changes"]["postings"]["added_ids"], [])
        self.assertEqual(mirror["deltas"]["value_changes"]["balances"], [])
        self.assertEqual(mirror["deltas"]["value_changes"]["reports"], [])
        for purpose in ["rg03_incomplete_source", *[name for name in self.roots_by_purpose if "invalid" in name]]:
            final = self.states[self.root_operations(purpose)[-1]["result_state_id"]]
            self.assertEqual([item["type"] for item in final["transactions"]], ["opening_balance"])


if __name__ == "__main__":
    unittest.main()
