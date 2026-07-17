from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import re
import unittest

from jsonschema import Draft202012Validator

from golden_cases import (
    GoldenCaseError,
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    load_golden_case_v2,
    validate_golden_case_v2,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
V1_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-02.json"
V2_PATH = (
    REPOSITORY_ROOT
    / "docs"
    / "migrations"
    / "golden-v2"
    / "rg-02-expected.json"
)
PATH_MAP_PATH = (
    REPOSITORY_ROOT
    / "docs"
    / "migrations"
    / "golden-v2"
    / "rg-02-path-map.json"
)
SCHEMA_PATH = REPOSITORY_ROOT / "schemas" / "golden-case-v2.schema.json"
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


def by_id(items):
    return {item["id"]: item for item in items}


def state_payload(state):
    value = deepcopy(state)
    for key in ("id", "root_id", "as_of_operation_id"):
        value.pop(key)
    return value


def transaction_parts(state, transaction_id):
    transactions = by_id(state["transactions"])
    versions = by_id(state["transaction_versions"])
    posting_sets = by_id(state["posting_sets"])
    postings = by_id(state["postings"])
    version = versions[transactions[transaction_id]["current_version_id"]]
    return version, [postings[item_id] for item_id in posting_sets[version["posting_set_id"]]["posting_ids"]]


def metric_map(state):
    return {
        (report["period_type"], report["period"], metric["metric"]): metric
        for report in state["reports"]
        for metric in report["metrics"]
    }


def empty_deltas(operation):
    return (
        all(
            not changes[field]
            for changes in operation["deltas"]["entity_changes"].values()
            for field in ("added_ids", "changed_ids", "removed_ids")
        )
        and all(
            not operation["deltas"]["value_changes"][name]
            for name in ("balances", "reports", "derived_statuses")
        )
        and not operation["status_changes"]
    )


class RG02GoldenV2ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        cls.case = load_golden_case_v2(V2_PATH)
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.path_map = json.loads(PATH_MAP_PATH.read_text(encoding="utf-8"))
        cls.states = by_id(cls.case["states"])
        cls.operations = by_id(cls.case["operations"])
        cls.roots = by_id(cls.case["roots"])
        cls.main_root_id = deterministic_v2_root_id(
            "RG-02", "$.case.id", "RG-02"
        )
        cls.main_root = cls.roots[cls.main_root_id]
        cls.main_operations = [
            cls.operations[item_id] for item_id in cls.main_root["operation_ids"]
        ]
        cls.main_states = [cls.states[cls.main_root["initial_state_id"]]] + [
            cls.states[item["result_state_id"]] for item in cls.main_operations
        ]

    def test_schema_complete_validator_and_mapping_gate_pass(self):
        Draft202012Validator.check_schema(self.schema)
        Draft202012Validator(self.schema).validate(self.case)
        validate_golden_case_v2(self.case)

        self.assertEqual(self.case["contract"], "unifiedledger.golden-case")
        self.assertEqual(self.case["contract_version"], "2.0.0")
        self.assertEqual(self.case["case"]["id"], "RG-02")
        self.assertEqual(self.case["case"]["approval_status"], "approved")
        self.assertEqual(self.path_map["status"], "approved")
        self.assertEqual(self.path_map["expected_output_gate"], "completed")
        self.assertEqual(self.path_map["unclassified_path_count"], 0)
        self.assertEqual(self.path_map["contract_gap_count"], 0)
        self.assertEqual(self.path_map["unresolved_contract_gaps"], [])
        self.assertEqual(
            len(self.path_map["entries"]), self.path_map["source_path_count"]
        )
        self.assertTrue(
            all(
                item["disposition"] != "requires_contract_amendment"
                and item["contract_gap_ids"] == []
                for item in self.path_map["entries"]
            )
        )
        self.assertEqual(
            self.path_map["disposition_counts"],
            {"ready": 153, "test_only_exclusion": 1},
        )

    def test_complete_topology_and_stable_sorting(self):
        self.assertEqual(len(self.case["roots"]), 11)
        self.assertEqual(len(self.case["states"]), 24)
        self.assertEqual(len(self.case["operations"]), 13)
        self.assertEqual(
            [item["id"] for item in self.case["roots"]],
            sorted(item["id"] for item in self.case["roots"]),
        )
        self.assertEqual(
            [item["id"] for item in self.case["states"]],
            sorted(item["id"] for item in self.case["states"]),
        )
        self.assertEqual(
            [(item["root_id"], item["sequence"]) for item in self.case["operations"]],
            sorted(
                (item["root_id"], item["sequence"])
                for item in self.case["operations"]
            ),
        )
        expected_purposes = {"rg02_main_path"}
        expected_purposes.update(
            f"rg02_rejected_{item['id'].replace('-', '_')}"
            for item in self.v1["invalid_inputs"]
        )
        expected_purposes.update(
            f"rg02_variant_{item['id'].replace('-', '_')}"
            for item in self.v1["variants"]
        )
        self.assertEqual({item["purpose"] for item in self.case["roots"]}, expected_purposes)

        for state in self.case["states"]:
            self.assertEqual(
                [item["id"] for item in state["catalog"]["accounts"]],
                sorted(item["id"] for item in state["catalog"]["accounts"]),
            )
            self.assertEqual(
                [item["id"] for item in state["catalog"]["categories"]],
                sorted(item["id"] for item in state["catalog"]["categories"]),
            )
            self.assertEqual(
                [
                    (item["category_id"], item["version"])
                    for item in state["catalog"]["category_name_history"]
                ],
                sorted(
                    (item["category_id"], item["version"])
                    for item in state["catalog"]["category_name_history"]
                ),
            )
            for collection in (
                "transactions",
                "transaction_versions",
                "posting_sets",
                "postings",
                "confirmations",
                "posting_reconciliations",
                "derived_statuses",
            ):
                self.assertEqual(
                    [item["id"] for item in state[collection]],
                    sorted(item["id"] for item in state[collection]),
                )

    def test_all_generated_ids_recompute(self):
        expected = {self.main_root_id}

        def generated(actual, root_id, kind, locator, occurrence):
            value = deterministic_v2_migration_id(
                "RG-02", root_id, kind, locator, occurrence
            )
            self.assertEqual(actual, value)
            expected.add(value)
            return value

        self.assertEqual(
            self.main_root["initial_state_id"],
            generated(
                self.main_root["initial_state_id"],
                self.main_root_id,
                "state",
                "$.opening",
                self.v1["opening"]["transactions"][0]["id"],
            ),
        )
        main_specs = [
            ("$.create.request", self.v1["create"]["request"]["request_id"]),
            (
                "$.category_rename.request",
                self.v1["category_rename"]["request"]["category_id"],
            ),
            (
                "$.idempotency.repeated_request_id",
                self.v1["idempotency"]["repeated_request_id"],
            ),
        ]
        for operation, (locator, occurrence) in zip(
            self.main_operations, main_specs, strict=True
        ):
            self.assertEqual(
                operation["id"],
                generated(operation["id"], self.main_root_id, "operation", locator, occurrence),
            )
            self.assertEqual(
                operation["result_state_id"],
                generated(operation["result_state_id"], self.main_root_id, "state", locator, occurrence),
            )
        opening_version, _ = transaction_parts(self.main_states[0], "tx-opening-rg02-a")
        generated(
            opening_version["id"],
            self.main_root_id,
            "transaction_version",
            "$.opening.transactions[*]",
            self.v1["opening"]["transactions"][0]["id"],
        )
        generated(
            opening_version["posting_set_id"],
            self.main_root_id,
            "posting_set",
            "$.opening.transactions[*]",
            self.v1["opening"]["transactions"][0]["id"],
        )
        main_created = self.main_states[1]
        for kind, collection in (
            ("confirmation", "confirmations"),
            ("posting_reconciliation", "posting_reconciliations"),
            ("derived_status", "derived_statuses"),
        ):
            generated(main_created[collection][0]["id"], self.main_root_id, kind, *main_specs[0])

        for invalid in self.v1["invalid_inputs"]:
            invalid_id = invalid["id"]
            root_id = deterministic_v2_root_id(
                "RG-02", "$.invalid_inputs[*]", invalid_id
            )
            expected.add(root_id)
            root = self.roots[root_id]
            operation = self.operations[root["operation_ids"][0]]
            initial = self.states[root["initial_state_id"]]
            opening_version, _ = transaction_parts(initial, "tx-opening-rg02-a")
            generated(root["initial_state_id"], root_id, "state", "$.opening", invalid_id)
            generated(
                opening_version["id"],
                root_id,
                "transaction_version",
                "$.opening.transactions[*]",
                self.v1["opening"]["transactions"][0]["id"],
            )
            generated(
                opening_version["posting_set_id"],
                root_id,
                "posting_set",
                "$.opening.transactions[*]",
                self.v1["opening"]["transactions"][0]["id"],
            )
            generated(operation["id"], root_id, "operation", "$.invalid_inputs[*]", invalid_id)
            generated(operation["attempted_input"]["request_id"], root_id, "request", "$.invalid_inputs[*]", invalid_id)
            generated(operation["result_state_id"], root_id, "state", "$.invalid_inputs[*]", invalid_id)

        for variant in self.v1["variants"]:
            variant_id = variant["id"]
            root_id = deterministic_v2_root_id("RG-02", "$.variants[*]", variant_id)
            expected.add(root_id)
            root = self.roots[root_id]
            operation = self.operations[root["operation_ids"][0]]
            result = self.states[operation["result_state_id"]]
            generated(root["initial_state_id"], root_id, "state", "$.variants[*]", variant_id)
            spec = ("$.variants[*].request", variant["request"]["request_id"])
            generated(operation["id"], root_id, "operation", *spec)
            generated(operation["result_state_id"], root_id, "state", *spec)
            for kind, collection in (
                ("confirmation", "confirmations"),
                ("posting_reconciliation", "posting_reconciliations"),
                ("derived_status", "derived_statuses"),
            ):
                generated(result[collection][0]["id"], root_id, kind, *spec)

        actual = {item["id"] for item in self.case["roots"]}
        actual.update(item["id"] for item in self.case["states"])
        actual.update(item["id"] for item in self.case["operations"])
        for state in self.case["states"]:
            for collection in (
                "transaction_versions",
                "posting_sets",
                "confirmations",
                "posting_reconciliations",
                "derived_statuses",
            ):
                actual.update(
                    item["id"]
                    for item in state[collection]
                    if UUID_PATTERN.fullmatch(item["id"])
                )
        for operation in self.case["operations"]:
            request_id = operation.get("attempted_input", {}).get("request_id")
            if request_id:
                actual.add(request_id)
        self.assertEqual(actual, expected)

    def test_main_operation_order_inputs_and_retry(self):
        self.assertEqual(
            [item["action_type"] for item in self.main_operations],
            ["manual_income", "category_rename", "manual_income"],
        )
        self.assertEqual(
            [item["outcome"]["status"] for item in self.main_operations],
            ["accepted", "accepted", "no_change"],
        )
        create_input = dict(self.v1["create"]["request"])
        self.assertEqual(create_input.pop("kind"), "manual_income")
        create_input["explicit_confirmation"] = True
        rename_input = dict(self.v1["category_rename"]["request"])
        rename_input["explicit_confirmation"] = True
        self.assertEqual(self.main_operations[0]["input"], create_input)
        self.assertEqual(self.main_operations[1]["input"], rename_input)
        self.assertEqual(self.main_operations[2]["input"], create_input)
        self.assertEqual(
            self.main_operations[2]["outcome"]["reason_code"], "idempotent_replay"
        )
        self.assertTrue(empty_deltas(self.main_operations[2]))
        self.assertEqual(
            state_payload(self.main_states[2]), state_payload(self.main_states[3])
        )
        self.assertEqual(
            self.main_operations[2]["returned_ids"],
            self.main_operations[0]["returned_ids"],
        )
        self.assertEqual(
            self.main_operations[0]["returned_ids"],
            [{"kind": "transaction", "id": "tx-income-rg02"}],
        )

    def test_manual_income_rejects_returning_an_unrelated_transaction(self):
        mutated = deepcopy(self.case)
        operations = by_id(mutated["operations"])
        wrong = [{"kind": "transaction", "id": "tx-opening-rg02-a"}]
        operations[self.main_operations[0]["id"]]["returned_ids"] = wrong
        operations[self.main_operations[2]["id"]]["returned_ids"] = wrong
        with self.assertRaisesRegex(GoldenCaseError, r"returned_ids.*created transaction"):
            validate_golden_case_v2(mutated)

    def test_main_income_posting_signs_roles_times_and_reconciliation(self):
        created = self.main_states[1]
        version, postings = transaction_parts(created, "tx-income-rg02")
        by_role = {item["role"]: item for item in postings}
        self.assertEqual(
            {
                role: (item["account_id"], item["amount"], item["reconciliation_eligible"])
                for role, item in by_role.items()
            },
            {
                "income_classification": (
                    "income-account-salary",
                    "-3000.00",
                    False,
                ),
                "receiving_asset": ("asset-bank-a", "3000.00", True),
            },
        )
        self.assertEqual(sum(Decimal(item["amount"]) for item in postings), Decimal("0"))
        occurred_at = self.v1["create"]["request"]["occurred_at"]
        self.assertEqual(
            [version[name] for name in ("occurred_at", "statistics_at", "effective_at")],
            [occurred_at, occurred_at, occurred_at],
        )
        self.assertNotIn("created_at", version)
        reconciliation = created["posting_reconciliations"]
        self.assertEqual(len(reconciliation), 1)
        self.assertEqual(reconciliation[0]["posting_id"], "posting-bank-rg02")
        self.assertEqual(reconciliation[0]["status"], "pending")
        self.assertNotIn(
            "posting-income-rg02",
            {item["posting_id"] for item in reconciliation},
        )
        self.assertEqual(
            {
                (item["target_id"], item["status_name"]): item["value"]
                for item in created["derived_statuses"]
            },
            {("tx-income-rg02", "reconciliation_summary"): "pending"},
        )

    def test_rename_appends_history_and_has_zero_financial_effects(self):
        before, after = self.main_states[1], self.main_states[2]
        operation = self.main_operations[1]
        categories = by_id(after["catalog"]["categories"])
        self.assertEqual(categories["income-category-salary"]["name"], "薪资")
        self.assertEqual(
            {
                key: value
                for key, value in categories["income-category-salary"].items()
                if key != "name"
            },
            {
                key: value
                for key, value in by_id(before["catalog"]["categories"])[
                    "income-category-salary"
                ].items()
                if key != "name"
            },
        )
        history = [
            item
            for item in after["catalog"]["category_name_history"]
            if item["category_id"] == "income-category-salary"
        ]
        self.assertEqual(
            history,
            [
                {
                    "category_id": "income-category-salary",
                    "name": "工资",
                    "version": 1,
                    "status": "superseded",
                },
                {
                    "category_id": "income-category-salary",
                    "name": "薪资",
                    "version": 2,
                    "status": "current",
                },
            ],
        )
        self.assertEqual(
            operation["deltas"]["entity_changes"]["catalog_categories"]["changed_ids"],
            ["income-category-salary"],
        )
        for name in ENTITY_COLLECTIONS:
            if name != "catalog_categories":
                self.assertEqual(
                    operation["deltas"]["entity_changes"][name],
                    {"added_ids": [], "changed_ids": [], "removed_ids": []},
                )
        self.assertEqual(before["transactions"], after["transactions"])
        self.assertEqual(before["transaction_versions"], after["transaction_versions"])
        self.assertEqual(before["postings"], after["postings"])
        self.assertEqual(before["balances"], after["balances"])
        self.assertEqual(before["reports"], after["reports"])
        self.assertEqual(before["posting_reconciliations"], after["posting_reconciliations"])

    def test_rename_rejects_unrelated_category_history_changes(self):
        mutated = deepcopy(self.case)
        states = by_id(mutated["states"])
        category_id = "income-category-work"
        for state_id in (
            self.main_operations[1]["result_state_id"],
            self.main_operations[2]["result_state_id"],
        ):
            history = states[state_id]["catalog"]["category_name_history"]
            original = next(item for item in history if item["category_id"] == category_id)
            original["status"] = "superseded"
            history.append(
                {
                    "category_id": category_id,
                    "name": original["name"],
                    "version": 2,
                    "status": "current",
                }
            )
            history.sort(key=lambda item: (item["category_id"], item["version"]))
        with self.assertRaisesRegex(GoldenCaseError, r"category_name_history.*target category"):
            validate_golden_case_v2(mutated)

    def test_main_balances_and_day_month_reports_match_v1(self):
        state = self.main_states[1]
        balances = {
            item["account_id"]: item["amount"] for item in state["balances"]
        }
        self.assertEqual(balances["asset-bank-a"], "4000.00")
        self.assertEqual(balances["income-account-salary"], "-3000.00")
        metrics = metric_map(state)
        expected = {
            "budget": ("not_applicable", None),
            "cash_inflow": ("applicable", "3000.00"),
            "cash_outflow": ("applicable", "0.00"),
            "consumption": ("applicable", "0.00"),
            "income": ("applicable", "3000.00"),
            "net_worth_change": ("applicable", "3000.00"),
        }
        for period_type, period in (("day", "2026-01-16"), ("month", "2026-01")):
            for name, (applicability, amount) in expected.items():
                with self.subTest(period_type=period_type, metric=name):
                    metric = metrics[(period_type, period, name)]
                    self.assertEqual(metric["applicability"], applicability)
                    if amount is None:
                        self.assertNotIn("amount", metric)
                        self.assertNotIn("currency", metric)
                    else:
                        self.assertEqual(metric["amount"], amount)
                        self.assertEqual(metric["currency"], "CNY")

    def test_rg02_rejects_extra_self_consistent_report(self):
        mutated = deepcopy(self.case)
        states = by_id(mutated["states"])
        for state_id in (
            self.main_root["initial_state_id"],
            *(item["result_state_id"] for item in self.main_operations),
        ):
            report = deepcopy(states[state_id]["reports"][0])
            report["period"] = "2026-01-15"
            for metric in report["metrics"]:
                if metric["applicability"] == "applicable":
                    metric["amount"] = "0.00"
            states[state_id]["reports"].append(report)
            states[state_id]["reports"].sort(key=lambda item: (item["period_type"], item["period"]))
        with self.assertRaisesRegex(GoldenCaseError, r"reports.*day and month"):
            validate_golden_case_v2(mutated)

    def test_rg02_schema_requires_category_name_history(self):
        mutated = deepcopy(self.case)
        mutated["states"][0]["catalog"].pop("category_name_history")
        errors = list(Draft202012Validator(self.schema).iter_errors(mutated))
        self.assertTrue(errors)
        self.assertTrue(any("category_name_history" in error.message for error in errors))

    def test_rejected_cases_preserve_sparse_attempts_and_are_atomic(self):
        expected_by_id = {item["id"]: item for item in self.v1["invalid_inputs"]}
        for invalid_id, invalid in expected_by_id.items():
            root_id = deterministic_v2_root_id(
                "RG-02", "$.invalid_inputs[*]", invalid_id
            )
            root = self.roots[root_id]
            operation = self.operations[root["operation_ids"][0]]
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            request_id = deterministic_v2_migration_id(
                "RG-02", root_id, "request", "$.invalid_inputs[*]", invalid_id
            )
            self.assertEqual(
                operation["attempted_input"],
                {"request_id": request_id, **invalid["input"]},
            )
            self.assertEqual(
                operation["outcome"],
                {
                    "status": "rejected",
                    "reason_code": invalid["expected"]["reason"],
                    "field_path": f"$.attempted_input.{invalid['expected']['field']}",
                },
            )
            self.assertEqual(state_payload(baseline), state_payload(result))
            self.assertTrue(empty_deltas(operation))
            self.assertEqual(operation["returned_ids"], [])

    def test_both_variants_are_independent_exact_income_roots(self):
        for variant in self.v1["variants"]:
            root_id = deterministic_v2_root_id(
                "RG-02", "$.variants[*]", variant["id"]
            )
            root = self.roots[root_id]
            operation = self.operations[root["operation_ids"][0]]
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            request = dict(variant["request"])
            self.assertEqual(request.pop("kind"), "manual_income")
            request["explicit_confirmation"] = True
            self.assertEqual(operation["input"], request)
            self.assertEqual(len(baseline["transactions"]), 0)
            self.assertTrue(all(item["amount"] == "0.00" for item in baseline["balances"]))

            tx = variant["expected"]["transaction"]
            version, postings = transaction_parts(result, tx["id"])
            expected_postings = {
                item["id"]: (item["account_id"], item["amount"])
                for item in tx["postings"]
            }
            self.assertEqual(
                {
                    item["id"]: (item["account_id"], item["amount"])
                    for item in postings
                },
                expected_postings,
            )
            self.assertEqual(sum(Decimal(item["amount"]) for item in postings), Decimal("0"))
            self.assertEqual(
                [version[name] for name in ("occurred_at", "statistics_at", "effective_at")],
                [request["occurred_at"]] * 3,
            )
            receiving = next(item for item in postings if item["role"] == "receiving_asset")
            income = next(item for item in postings if item["role"] == "income_classification")
            self.assertEqual(receiving["amount"], request["amount"])
            self.assertEqual(income["amount"], f"-{request['amount']}")
            self.assertTrue(receiving["reconciliation_eligible"])
            self.assertFalse(income["reconciliation_eligible"])
            self.assertEqual(
                {item["posting_id"] for item in result["posting_reconciliations"]},
                {receiving["id"]},
            )
            metrics = metric_map(result)
            for period_type, period in (
                ("day", request["occurred_at"][:10]),
                ("month", request["occurred_at"][:7]),
            ):
                self.assertEqual(metrics[(period_type, period, "income")]["amount"], request["amount"])
                self.assertEqual(metrics[(period_type, period, "cash_inflow")]["amount"], request["amount"])
                self.assertEqual(metrics[(period_type, period, "cash_outflow")]["amount"], "0.00")
                self.assertEqual(metrics[(period_type, period, "consumption")]["amount"], "0.00")
                self.assertEqual(metrics[(period_type, period, "net_worth_change")]["amount"], request["amount"])
                self.assertEqual(metrics[(period_type, period, "budget")]["applicability"], "not_applicable")

    def test_forbidden_effects_are_absent(self):
        forbidden_collections = (
            "sources",
            "candidates",
            "evidence",
            "evidence_links",
            "relations",
            "domain_entities",
            "audit_links",
        )
        for state in self.case["states"]:
            for collection in forbidden_collections:
                self.assertEqual(state[collection], [])
        self.assertEqual(
            {item["type"] for state in self.case["states"] for item in state["transactions"]},
            {"opening_balance", "income"},
        )
        self.assertEqual(
            {item.get("role") for state in self.case["states"] for item in state["postings"] if "role" in item},
            {"income_classification", "receiving_asset"},
        )
        serialized = V2_PATH.read_text(encoding="utf-8")
        for token in self.v1["forbidden_side_effects"]:
            self.assertNotIn(token, serialized)


if __name__ == "__main__":
    unittest.main()
