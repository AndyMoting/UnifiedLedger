from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import re
import unittest

from jsonschema import Draft202012Validator

from golden_cases import (
    deterministic_v2_migration_id,
    deterministic_v2_root_id,
    load_golden_case_v2,
    validate_golden_case_v2,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_PATH = (
    REPOSITORY_ROOT / "docs" / "migrations" / "golden-v2" / "rg-01-expected.json"
)
V1_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-01.json"
SCHEMA_PATH = REPOSITORY_ROOT / "schemas" / "golden-case-v2.schema.json"
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
EMPTY_COLLECTIONS = {
    "sources",
    "candidates",
    "evidence",
    "evidence_links",
    "relations",
    "domain_entities",
    "audit_links",
}


def by_id(items):
    return {item["id"]: item for item in items}


def state_payload(state):
    value = deepcopy(state)
    for key in ("id", "root_id", "as_of_operation_id"):
        value.pop(key)
    return value


def report_metrics(state, period_type, period):
    report = next(
        item
        for item in state["reports"]
        if item["period_type"] == period_type and item["period"] == period
    )
    return {item["metric"]: item for item in report["metrics"]}


def balance_map(state):
    return {item["account_id"]: item["amount"] for item in state["balances"]}


def all_empty_delta_arrays(operation):
    arrays = []
    for change in operation["deltas"]["entity_changes"].values():
        arrays.extend(change.values())
    arrays.extend(operation["deltas"]["value_changes"].values())
    return arrays


def expected_catalog(v1):
    account_flags = {
        "asset-bank-a": (True, True, True),
        "equity-opening-a": (False, False, False),
        "expense-account-breakfast": (False, False, False),
    }
    accounts = []
    for source in v1["catalog"]["accounts"]:
        owned, real, eligible = account_flags[source["id"]]
        accounts.append(
            {
                "id": source["id"],
                "name": source["name"],
                "kind": source["kind"],
                "currency": v1["case"]["currency"],
                "owned_by_user": owned,
                "real_account": real,
                "reconciliation_eligible": eligible,
            }
        )
    categories = [dict(item) for item in v1["catalog"]["categories"]]
    return {
        "accounts": sorted(accounts, key=lambda item: item["id"]),
        "categories": sorted(categories, key=lambda item: item["id"]),
    }


def expected_opening_balances(v1):
    balances = {
        account["id"]: Decimal("0.00") for account in v1["catalog"]["accounts"]
    }
    for posting in v1["opening"]["transactions"][0]["postings"]:
        balances[posting["account_id"]] += Decimal(posting["amount"])
    return [
        {
            "account_id": account_id,
            "currency": v1["case"]["currency"],
            "amount": f"{amount:.2f}",
        }
        for account_id, amount in sorted(balances.items())
    ]


class GoldenV2Rg01ExpectedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v1 = json.loads(V1_PATH.read_text(encoding="utf-8"))
        cls.case = load_golden_case_v2(EXPECTED_PATH)
        cls.states = by_id(cls.case["states"])
        cls.operations = by_id(cls.case["operations"])
        cls.main_root_id = deterministic_v2_root_id(
            cls.v1["case"]["id"], "$.case.id", cls.v1["case"]["id"]
        )
        cls.main_root = by_id(cls.case["roots"])[cls.main_root_id]
        cls.main_operations = [
            cls.operations[item_id] for item_id in cls.main_root["operation_ids"]
        ]
        cls.main_states = [cls.states[cls.main_root["initial_state_id"]]] + [
            cls.states[item["result_state_id"]] for item in cls.main_operations
        ]

    def assert_opening_state_matches_v1(self, state):
        source = self.v1["opening"]["transactions"][0]
        self.assertEqual(state["catalog"], expected_catalog(self.v1))
        self.assertEqual(len(state["transactions"]), 1)
        transaction = state["transactions"][0]
        self.assertEqual(
            transaction,
            {
                "id": source["id"],
                "type": "opening_balance",
                "current_version_id": transaction["current_version_id"],
            },
        )
        self.assertEqual(len(state["transaction_versions"]), 1)
        version = state["transaction_versions"][0]
        self.assertEqual(
            version,
            {
                "id": transaction["current_version_id"],
                "transaction_id": source["id"],
                "version_number": 1,
                "posting_set_id": version["posting_set_id"],
                "occurred_at": source["occurred_at"],
                "statistics_at": source["occurred_at"],
                "effective_at": source["occurred_at"],
            },
        )
        self.assertEqual(
            state["posting_sets"],
            [
                {
                    "id": version["posting_set_id"],
                    "posting_ids": sorted(item["id"] for item in source["postings"]),
                }
            ],
        )
        self.assertEqual(
            state["postings"],
            sorted(
                (
                    {
                        "id": item["id"],
                        "posting_set_id": version["posting_set_id"],
                        "account_id": item["account_id"],
                        "amount": item["amount"],
                        "currency": item["currency"],
                        "reconciliation_eligible": False,
                    }
                    for item in source["postings"]
                ),
                key=lambda item: item["id"],
            ),
        )
        self.assertEqual(state["balances"], expected_opening_balances(self.v1))
        zero_metrics = [
            {"metric": "budget", "applicability": "not_applicable"},
            {
                "metric": "cash_outflow",
                "applicability": "applicable",
                "currency": self.v1["case"]["currency"],
                "amount": "0.00",
            },
            {
                "metric": "consumption",
                "applicability": "applicable",
                "currency": self.v1["case"]["currency"],
                "amount": "0.00",
            },
            {
                "metric": "income",
                "applicability": "applicable",
                "currency": self.v1["case"]["currency"],
                "amount": "0.00",
            },
            {
                "metric": "net_worth_change",
                "applicability": "applicable",
                "currency": self.v1["case"]["currency"],
                "amount": "0.00",
            },
        ]
        statistics = self.v1["create"]["expected"]["statistics"]
        self.assertEqual(
            state["reports"],
            [
                {
                    "period_type": "day",
                    "period": statistics["day"],
                    "metrics": zero_metrics,
                },
                {
                    "period_type": "month",
                    "period": statistics["month"],
                    "metrics": zero_metrics,
                },
            ],
        )
        for collection in EMPTY_COLLECTIONS | {
            "confirmations",
            "posting_reconciliations",
            "derived_statuses",
        }:
            self.assertEqual(state[collection], [])

    def test_expected_output_is_a_valid_review_draft(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        self.assertEqual(
            list(Draft202012Validator(schema).iter_errors(self.case)), []
        )
        validate_golden_case_v2(self.case)
        self.assertEqual(self.case["contract"], "unifiedledger.golden-case")
        self.assertEqual(self.case["contract_version"], "2.0.0")
        self.assertEqual(
            self.case["case"],
            {
                "id": self.v1["case"]["id"],
                "level": self.v1["case"]["level"],
                "rule_version": self.v1["case"]["rule_version"],
                "approval_status": "approved",
                "ledger_id": self.v1["case"]["ledger_id"],
                "timezone": self.v1["case"]["timezone"],
                "currencies": [
                    {
                        "code": self.v1["case"]["currency"],
                        "precision": self.v1["case"]["precision"],
                    }
                ],
            },
        )
        self.assertEqual(len(self.case["roots"]), 8)
        self.assertEqual(len(self.case["states"]), 19)
        self.assertEqual(len(self.case["operations"]), 11)

    def test_static_output_uses_stable_set_and_operation_order(self):
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
        for state in self.case["states"]:
            for collection in (
                state["catalog"]["accounts"],
                state["catalog"]["categories"],
                state["transactions"],
                state["transaction_versions"],
                state["posting_sets"],
                state["postings"],
                state["confirmations"],
                state["posting_reconciliations"],
                state["derived_statuses"],
            ):
                self.assertEqual(
                    [item["id"] for item in collection],
                    sorted(item["id"] for item in collection),
                )
            self.assertEqual(
                [(item["account_id"], item["currency"]) for item in state["balances"]],
                sorted(
                    (item["account_id"], item["currency"])
                    for item in state["balances"]
                ),
            )
            self.assertEqual(
                [(item["period_type"], item["period"]) for item in state["reports"]],
                sorted(
                    (item["period_type"], item["period"])
                    for item in state["reports"]
                ),
            )
            for report in state["reports"]:
                keys = [
                    (item["metric"], item.get("currency", ""))
                    for item in report["metrics"]
                ]
                self.assertEqual(keys, sorted(keys))
            for posting_set in state["posting_sets"]:
                self.assertEqual(
                    posting_set["posting_ids"], sorted(posting_set["posting_ids"])
                )
        for root in self.case["roots"]:
            root_operations = sorted(
                (
                    item
                    for item in self.case["operations"]
                    if item["root_id"] == root["id"]
                ),
                key=lambda item: item["sequence"],
            )
            self.assertEqual(
                root["operation_ids"], [item["id"] for item in root_operations]
            )
        for operation in self.case["operations"]:
            returned_keys = [
                (item["kind"], item["id"]) for item in operation["returned_ids"]
            ]
            self.assertEqual(returned_keys, sorted(returned_keys))
            for change in operation["deltas"]["entity_changes"].values():
                for field in ("added_ids", "changed_ids", "removed_ids"):
                    self.assertEqual(change[field], sorted(change[field]))
            value_changes = operation["deltas"]["value_changes"]
            balance_keys = [
                (item["key"]["account_id"], item["key"]["currency"])
                for item in value_changes["balances"]
            ]
            self.assertEqual(balance_keys, sorted(balance_keys))
            report_keys = [
                (
                    item["key"]["period_type"],
                    item["key"]["period"],
                    item["key"]["metric"],
                    item["key"].get("currency", ""),
                )
                for item in value_changes["reports"]
            ]
            self.assertEqual(report_keys, sorted(report_keys))
            status_keys = [
                (
                    item["key"]["kind"],
                    item["key"]["target_id"],
                    item["key"]["status_name"],
                )
                for item in value_changes["derived_statuses"]
            ]
            self.assertEqual(status_keys, sorted(status_keys))

    def test_all_generated_ids_recompute_from_approved_migration_inputs(self):
        case_id = self.v1["case"]["id"]
        expected = {self.main_root_id}

        def main_id(kind, locator, occurrence):
            value = deterministic_v2_migration_id(
                case_id, self.main_root_id, kind, locator, occurrence
            )
            expected.add(value)
            return value

        self.assertEqual(
            self.main_root["initial_state_id"],
            main_id("state", "$.opening", "tx-opening-a"),
        )
        opening = self.states[self.main_root["initial_state_id"]]
        self.assertEqual(
            opening["transaction_versions"][0]["id"],
            main_id(
                "transaction_version", "$.opening.transactions[*]", "tx-opening-a"
            ),
        )
        self.assertEqual(
            opening["posting_sets"][0]["id"],
            main_id("posting_set", "$.opening.transactions[*]", "tx-opening-a"),
        )

        main_specs = [
            ("$.create.request", self.v1["create"]["request"]["request_id"]),
            (
                "$.note_update.request",
                self.v1["note_update"]["request"]["request_id"],
            ),
            (
                "$.idempotency.repeated_request_id",
                self.v1["idempotency"]["repeated_request_id"],
            ),
            (
                "$.distinct_reentry.request",
                self.v1["distinct_reentry"]["request"]["request_id"],
            ),
        ]
        for operation, (locator, occurrence) in zip(
            self.main_operations, main_specs, strict=True
        ):
            self.assertEqual(
                operation["id"], main_id("operation", locator, occurrence)
            )
            self.assertEqual(
                operation["result_state_id"], main_id("state", locator, occurrence)
            )

        for index in (0, 1, 3):
            operation = self.main_operations[index]
            locator, occurrence = main_specs[index]
            confirmation_id = main_id("confirmation", locator, occurrence)
            self.assertIn(
                confirmation_id,
                by_id(self.states[operation["result_state_id"]]["confirmations"]),
            )
        for index in (0, 3):
            operation = self.main_operations[index]
            locator, occurrence = main_specs[index]
            state = self.states[operation["result_state_id"]]
            self.assertIn(
                main_id("posting_reconciliation", locator, occurrence),
                by_id(state["posting_reconciliations"]),
            )
            self.assertIn(
                main_id("derived_status", locator, occurrence),
                by_id(state["derived_statuses"]),
            )

        invalid_by_id = {item["id"]: item for item in self.v1["invalid_inputs"]}
        for invalid_id in invalid_by_id:
            root_id = deterministic_v2_root_id(
                case_id, "$.invalid_inputs[*]", invalid_id
            )
            expected.add(root_id)
            root = by_id(self.case["roots"])[root_id]

            def invalid_id_value(kind, locator, occurrence=invalid_id):
                value = deterministic_v2_migration_id(
                    case_id, root_id, kind, locator, occurrence
                )
                expected.add(value)
                return value

            self.assertEqual(
                root["initial_state_id"],
                invalid_id_value("state", "$.opening", invalid_id),
            )
            initial = self.states[root["initial_state_id"]]
            self.assertEqual(
                initial["transaction_versions"][0]["id"],
                invalid_id_value(
                    "transaction_version",
                    "$.opening.transactions[*]",
                    "tx-opening-a",
                ),
            )
            self.assertEqual(
                initial["posting_sets"][0]["id"],
                invalid_id_value(
                    "posting_set", "$.opening.transactions[*]", "tx-opening-a"
                ),
            )
            operation = self.operations[root["operation_ids"][0]]
            self.assertEqual(
                operation["id"],
                invalid_id_value("operation", "$.invalid_inputs[*]"),
            )
            self.assertEqual(
                operation["attempted_input"]["request_id"],
                invalid_id_value("request", "$.invalid_inputs[*].id"),
            )
            self.assertEqual(
                operation["result_state_id"],
                invalid_id_value("state", "$.invalid_inputs[*].expected"),
            )

        actual = set()
        for root in self.case["roots"]:
            actual.add(root["id"])
        for state in self.case["states"]:
            actual.add(state["id"])
            for collection_name in (
                "transaction_versions",
                "posting_sets",
                "confirmations",
                "posting_reconciliations",
                "derived_statuses",
            ):
                actual.update(
                    item["id"]
                    for item in state[collection_name]
                    if UUID_PATTERN.fullmatch(item["id"])
                )
        for operation in self.case["operations"]:
            actual.add(operation["id"])
            request_id = operation.get("attempted_input", {}).get("request_id")
            if request_id is not None:
                actual.add(request_id)
        self.assertEqual(actual, expected)

    def test_main_path_preserves_opening_create_update_retry_and_reentry(self):
        self.assertEqual(
            [item["action_type"] for item in self.main_operations],
            [
                "manual_expense",
                "transaction_note_update",
                "manual_expense",
                "manual_expense",
            ],
        )
        self.assertEqual(
            [item["outcome"]["status"] for item in self.main_operations],
            ["accepted", "accepted", "no_change", "accepted"],
        )

        create_input = dict(self.v1["create"]["request"])
        self.assertEqual(create_input.pop("kind"), "manual_expense")
        create_input["explicit_confirmation"] = True
        note_input = dict(self.v1["note_update"]["request"])
        note_input["explicit_confirmation"] = True
        distinct_input = dict(self.v1["distinct_reentry"]["request"])
        self.assertEqual(distinct_input.pop("kind"), "manual_expense")
        distinct_input["explicit_confirmation"] = True
        self.assertEqual(self.main_operations[0]["input"], create_input)
        self.assertEqual(self.main_operations[1]["input"], note_input)
        self.assertEqual(
            self.main_operations[2]["input"]["request_id"],
            self.v1["idempotency"]["repeated_request_id"],
        )
        self.assertEqual(self.main_operations[2]["input"], create_input)
        self.assertEqual(self.main_operations[3]["input"], distinct_input)

        frozen_catalog = expected_catalog(self.v1)
        for state in self.case["states"]:
            self.assertEqual(state["catalog"], frozen_catalog)

        opening_source = self.v1["opening"]["transactions"][0]
        opening_state = self.main_states[0]
        self.assert_opening_state_matches_v1(opening_state)
        opening_tx = by_id(opening_state["transactions"])[opening_source["id"]]
        opening_version = by_id(opening_state["transaction_versions"])[
            opening_tx["current_version_id"]
        ]
        opening_set = by_id(opening_state["posting_sets"])[
            opening_version["posting_set_id"]
        ]
        self.assertEqual(
            opening_set["posting_ids"],
            sorted(item["id"] for item in opening_source["postings"]),
        )
        self.assertEqual(
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in opening_state["postings"]
            },
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in opening_source["postings"]
            },
        )
        create_source = self.v1["create"]
        created_state = self.main_states[1]
        created_tx = by_id(created_state["transactions"])[
            create_source["expected"]["transaction"]["id"]
        ]
        created_version = by_id(created_state["transaction_versions"])[
            created_tx["current_version_id"]
        ]
        self.assertEqual(
            created_version["posting_set_id"],
            create_source["expected"]["transaction"]["posting_set_id"],
        )
        self.assertEqual(
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in created_state["postings"]
                if item["id"]
                in {
                    posting["id"]
                    for posting in create_source["expected"]["transaction"]["postings"]
                }
            },
            {
                item["id"]: (item["account_id"], item["amount"], item["currency"])
                for item in create_source["expected"]["transaction"]["postings"]
            },
        )
        create_expected = create_source["expected"]["transaction"]
        self.assertEqual(
            self.main_operations[0]["returned_ids"],
            [
                {
                    "kind": "confirmation",
                    "id": created_version["confirmation_id"],
                },
                {"kind": "transaction", "id": create_expected["id"]},
            ],
        )
        self.assertEqual(
            self.main_operations[0]["deltas"]["entity_changes"]["transaction_versions"]
            ["added_ids"],
            [create_expected["current_version_id"]],
        )
        self.assertEqual(
            self.main_operations[0]["deltas"]["entity_changes"]["posting_sets"]
            ["added_ids"],
            [create_expected["posting_set_id"]],
        )
        self.assertEqual(
            self.main_operations[0]["deltas"]["entity_changes"]["postings"]
            ["added_ids"],
            sorted(item["id"] for item in create_expected["postings"]),
        )
        create_changes = self.main_operations[0]["deltas"]["entity_changes"]
        self.assertEqual(len(create_changes["transactions"]["added_ids"]), 1)
        self.assertEqual(len(create_changes["transaction_versions"]["added_ids"]), 1)
        self.assertEqual(len(create_changes["posting_sets"]["added_ids"]), 1)
        self.assertEqual(len(create_changes["postings"]["added_ids"]), 2)
        self.assertEqual(len(create_changes["confirmations"]["added_ids"]), 1)
        self.assertEqual(
            len(create_changes["posting_reconciliations"]["added_ids"]), 1
        )

        noted_state = self.main_states[2]
        noted_tx = by_id(noted_state["transactions"])[
            self.v1["note_update"]["expected"]["transaction_id"]
        ]
        versions = {
            item["id"]: item
            for item in noted_state["transaction_versions"]
            if item["transaction_id"] == noted_tx["id"]
        }
        self.assertEqual(
            noted_tx["current_version_id"],
            self.v1["note_update"]["expected"]["current_version_id"],
        )
        self.assertEqual(set(versions), {"version-expense-rg01-v1", "version-expense-rg01-v2"})
        self.assertEqual(
            {item["posting_set_id"] for item in versions.values()},
            {self.v1["note_update"]["expected"]["effective_posting_set_id"]},
        )
        self.assertEqual(
            {item["id"]: item["note"] for item in versions.values()},
            {
                item["id"]: item["note"]
                for item in self.v1["note_update"]["expected"]["versions"]
            },
        )
        self.assertEqual(
            self.main_operations[1]["returned_ids"],
            [
                {
                    "kind": "transaction",
                    "id": self.v1["note_update"]["expected"]["transaction_id"],
                },
                {
                    "kind": "transaction_version",
                    "id": self.v1["note_update"]["expected"]["current_version_id"],
                },
            ],
        )
        note_expected = self.v1["note_update"]["expected"]
        self.assertEqual(
            len([item for item in noted_state["transactions"] if item["type"] == "expense"]),
            note_expected["effective_transaction_count"],
        )
        self.assertEqual(
            len(
                [
                    item
                    for item in noted_state["postings"]
                    if item.get("role") == "payment_asset"
                ]
            ),
            note_expected["funding_effect_count"],
        )

        retry = self.main_operations[2]
        self.assertEqual(retry["input"], self.main_operations[0]["input"])
        self.assertEqual(
            retry["returned_ids"], self.main_operations[0]["returned_ids"]
        )
        self.assertEqual(
            state_payload(self.main_states[2]), state_payload(self.main_states[3])
        )
        self.assertTrue(all(not item for item in all_empty_delta_arrays(retry)))
        retry_expected = self.v1["idempotency"]["expected"]
        retry_changes = retry["deltas"]["entity_changes"]
        self.assertEqual(
            len(retry_changes["transactions"]["added_ids"]),
            retry_expected["new_transaction_count"],
        )
        self.assertEqual(
            len(retry_changes["posting_sets"]["added_ids"]),
            retry_expected["new_posting_set_count"],
        )
        self.assertEqual(
            len(retry_changes["transaction_versions"]["added_ids"]),
            retry_expected["new_version_count"],
        )

        distinct_source = self.v1["distinct_reentry"]
        final_state = self.main_states[4]
        distinct_tx = by_id(final_state["transactions"])[
            distinct_source["expected"]["transaction"]["id"]
        ]
        self.assertNotEqual(distinct_tx["id"], created_tx["id"])
        self.assertEqual(
            len(
                [
                    item
                    for item in final_state["transactions"]
                    if item["type"] == "expense"
                ]
            ),
            distinct_source["expected"]["effective_transaction_count"],
        )
        self.assertEqual(
            len(
                self.main_operations[3]["deltas"]["entity_changes"]["transactions"]
                ["added_ids"]
            ),
            distinct_source["expected"]["new_transaction_count"],
        )
        distinct_expected = distinct_source["expected"]["transaction"]
        distinct_version = by_id(final_state["transaction_versions"])[
            distinct_tx["current_version_id"]
        ]
        self.assertEqual(
            self.main_operations[3]["returned_ids"],
            [
                {
                    "kind": "confirmation",
                    "id": distinct_version["confirmation_id"],
                },
                {"kind": "transaction", "id": distinct_expected["id"]},
            ],
        )
        self.assertEqual(
            self.main_operations[3]["deltas"]["entity_changes"]["transaction_versions"]
            ["added_ids"],
            [distinct_expected["current_version_id"]],
        )
        self.assertEqual(
            self.main_operations[3]["deltas"]["entity_changes"]["posting_sets"]
            ["added_ids"],
            [distinct_expected["posting_set_id"]],
        )
        self.assertEqual(
            self.main_operations[3]["deltas"]["entity_changes"]["postings"]
            ["added_ids"],
            sorted(item["id"] for item in distinct_expected["postings"]),
        )

    def test_balances_reports_times_and_reconciliation_match_v1(self):
        opening_balances = {
            account["id"]: Decimal("0.00")
            for account in self.v1["catalog"]["accounts"]
        }
        for posting in self.v1["opening"]["transactions"][0]["postings"]:
            opening_balances[posting["account_id"]] += Decimal(posting["amount"])
        first_balances = dict(opening_balances)
        for posting in self.v1["create"]["expected"]["transaction"]["postings"]:
            first_balances[posting["account_id"]] += Decimal(posting["amount"])
        final_balances = dict(first_balances)
        for posting in self.v1["distinct_reentry"]["expected"]["transaction"]["postings"]:
            final_balances[posting["account_id"]] += Decimal(posting["amount"])

        expected_balances = [
            opening_balances,
            first_balances,
            first_balances,
            first_balances,
            final_balances,
        ]
        for state, expected in zip(self.main_states, expected_balances, strict=True):
            self.assertEqual(
                balance_map(state),
                {key: f"{value:.2f}" for key, value in expected.items()},
            )

        opening_time = self.v1["opening"]["transactions"][0]["occurred_at"]
        create_time = self.v1["create"]["request"]["occurred_at"]
        for state in self.main_states:
            for version in state["transaction_versions"]:
                expected = opening_time if version["transaction_id"] == "tx-opening-a" else create_time
                self.assertEqual(
                    [
                        version["occurred_at"],
                        version["statistics_at"],
                        version["effective_at"],
                    ],
                    [expected, expected, expected],
                )
                self.assertNotIn("created_at", version)
        self.assertTrue(
            all("confirmed_at" not in item for state in self.case["states"] for item in state["confirmations"])
        )

        statistics_sources = [
            None,
            self.v1["create"]["expected"]["statistics"],
            self.v1["note_update"]["expected"]["statistics"],
            self.v1["idempotency"]["expected"]["statistics"],
            self.v1["distinct_reentry"]["expected"]["statistics"],
        ]
        for state, source in zip(self.main_states, statistics_sources, strict=True):
            expected_consumption = "0.00" if source is None else source["day_consumption"]
            expected_outflow = "0.00" if source is None else source["day_cash_outflow"]
            expected_income = "0.00" if source is None else source["income"]
            expected_net = "0.00" if source is None else source["net_worth_change"]
            for period_type, period in (("day", "2026-01-15"), ("month", "2026-01")):
                metrics = report_metrics(state, period_type, period)
                suffix = "day" if period_type == "day" else "month"
                if source is not None:
                    expected_consumption = source[f"{suffix}_consumption"]
                    expected_outflow = source[f"{suffix}_cash_outflow"]
                self.assertEqual(metrics["consumption"]["amount"], expected_consumption)
                self.assertEqual(metrics["cash_outflow"]["amount"], expected_outflow)
                self.assertEqual(metrics["income"]["amount"], expected_income)
                self.assertEqual(metrics["net_worth_change"]["amount"], expected_net)
                self.assertEqual(
                    metrics["budget"],
                    {"metric": "budget", "applicability": "not_applicable"},
                )

        final_state = self.main_states[-1]
        reconciliations = {
            item["posting_id"]: item["status"]
            for item in final_state["posting_reconciliations"]
        }
        self.assertEqual(
            reconciliations,
            {
                "posting-bank-rg01": self.v1["create"]["expected"]["reconciliation"]["posting-bank-rg01"],
                "posting-bank-rg01-distinct": "pending",
            },
        )
        self.assertNotIn("posting-expense-rg01", reconciliations)
        self.assertEqual(
            {
                (item["target_id"], item["status_name"]): item["value"]
                for item in final_state["derived_statuses"]
            },
            {
                ("tx-expense-rg01", "reconciliation_summary"): self.v1["create"]["expected"]["reconciliation"]["transaction"],
                ("tx-expense-rg01-distinct", "reconciliation_summary"): "pending",
            },
        )

    def test_rejected_roots_preserve_sparse_attempts_and_zero_effects(self):
        roots = by_id(self.case["roots"])
        for invalid in self.v1["invalid_inputs"]:
            invalid_id = invalid["id"]
            root_id = deterministic_v2_root_id(
                self.v1["case"]["id"], "$.invalid_inputs[*]", invalid_id
            )
            root = roots[root_id]
            operation = self.operations[root["operation_ids"][0]]
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            expected_attempt = dict(invalid["input"])
            expected_attempt["request_id"] = deterministic_v2_migration_id(
                self.v1["case"]["id"],
                root_id,
                "request",
                "$.invalid_inputs[*].id",
                invalid_id,
            )
            self.assertEqual(operation["attempted_input"], expected_attempt)
            self.assertEqual(operation["operation_class"], "rejection")
            self.assertEqual(operation["outcome"]["status"], "rejected")
            self.assertEqual(
                operation["outcome"]["field_path"],
                f"$.attempted_input.{invalid['expected']['field']}",
            )
            self.assertEqual(
                operation["outcome"]["reason_code"],
                invalid["expected"].get("reason", "missing_required_field"),
            )
            self.assert_opening_state_matches_v1(baseline)
            self.assert_opening_state_matches_v1(result)
            self.assertEqual(state_payload(baseline), state_payload(result))
            self.assertTrue(all(not item for item in all_empty_delta_arrays(operation)))
            self.assertEqual(operation["status_changes"], [])
            self.assertEqual(operation["returned_ids"], [])
            self.assertEqual(balance_map(result)["asset-bank-a"], "1000.00")
            for report in result["reports"]:
                for metric in report["metrics"]:
                    if metric["applicability"] == "applicable":
                        self.assertEqual(metric["amount"], "0.00")

    def test_non_applicable_collections_and_forbidden_effects_remain_absent(self):
        for state in self.case["states"]:
            for collection in EMPTY_COLLECTIONS:
                self.assertEqual(state[collection], [])
        serialized = EXPECTED_PATH.read_text(encoding="utf-8")
        for forbidden in self.v1["forbidden_side_effects"]:
            self.assertNotIn(forbidden, serialized)
        self.assertNotIn('"candidate": null', serialized)
        self.assertNotIn('"created_at"', serialized)
        self.assertNotIn('"confirmed_at"', serialized)


if __name__ == "__main__":
    unittest.main()
