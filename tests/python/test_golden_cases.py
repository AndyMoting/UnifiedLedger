from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from golden_cases import (
    GoldenCaseError,
    assert_expected_balances,
    load_golden_case,
    replay_balances,
    validate_case_envelope,
    validate_transactions,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RG01_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-01.json"


class GoldenCaseLoaderTests(unittest.TestCase):
    def test_loader_rejects_non_object_root(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text("[]", encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "root must be an object"):
                load_golden_case(path)

    def test_loader_rejects_unknown_schema(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "case.json"
            path.write_text(json.dumps({"schema_version": 2}), encoding="utf-8")

            with self.assertRaisesRegex(GoldenCaseError, "unsupported schema_version: 2"):
                load_golden_case(path)


class RG01FrozenAnswerTests(unittest.TestCase):
    def test_rg01_contains_the_approved_user_visible_results(self):
        case = load_golden_case(RG01_PATH)

        self.assertEqual(case["case"]["id"], "RG-01")
        self.assertEqual(case["case"]["level"], "core_required")
        self.assertEqual(case["case"]["timezone"], "Asia/Shanghai")
        self.assertEqual(case["create"]["request"]["amount"], "35.80")
        self.assertEqual(case["create"]["request"]["payment_account_id"], "asset-bank-a")
        self.assertEqual(case["create"]["request"]["category_id"], "expense-category-breakfast")
        self.assertEqual(
            case["create"]["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(case["create"]["candidate"])
        self.assertEqual(case["create"]["expected"]["balances"]["asset-bank-a"], "964.20")
        self.assertEqual(
            case["create"]["expected"]["statistics"],
            {
                "day": "2026-01-15",
                "month": "2026-01",
                "day_consumption": "35.80",
                "month_consumption": "35.80",
                "day_cash_outflow": "35.80",
                "month_cash_outflow": "35.80",
                "income": "0.00",
                "net_worth_change": "-35.80",
                "budget": "not_applicable",
            },
        )
        self.assertEqual(
            case["create"]["expected"]["reconciliation"],
            {
                "posting-bank-rg01": "pending",
                "posting-expense-rg01": "not_applicable",
                "transaction": "pending",
            },
        )
        self.assertEqual(case["create"]["expected"]["evidence_refs"], [])
        self.assertEqual(len(case["create"]["expected"]["transaction"]["postings"]), 2)
        self.assertEqual(case["note_update"]["request"]["note"], "早餐")
        self.assertEqual(case["note_update"]["expected"]["funding_effect_count"], 1)
        self.assertEqual(
            case["note_update"]["expected"]["effective_posting_set_id"],
            case["create"]["expected"]["transaction"]["posting_set_id"],
        )
        self.assertEqual(
            [version["status"] for version in case["note_update"]["expected"]["versions"]],
            ["superseded", "current"],
        )
        self.assertEqual(case["idempotency"]["expected"]["new_transaction_count"], 0)
        self.assertEqual(case["idempotency"]["expected"]["new_posting_set_count"], 0)
        self.assertEqual(case["idempotency"]["expected"]["funding_effect_count"], 1)
        zero_amount = next(item for item in case["invalid_inputs"] if item["id"] == "zero-amount")
        self.assertEqual(zero_amount["expected"]["reason"], "must_be_positive")
        invalid_reasons = {
            item["id"]: item["expected"].get("reason")
            for item in case["invalid_inputs"]
        }
        self.assertEqual(invalid_reasons["negative-amount"], "must_be_positive")
        self.assertEqual(invalid_reasons["primary-category"], "secondary_category_required")
        self.assertEqual(invalid_reasons["inactive-secondary-category"], "category_inactive")
        self.assertEqual(len(case["invalid_inputs"]), 7)
        self.assertEqual(
            set(case["forbidden_side_effects"]),
            {
                "create_order_relation",
                "create_budget_result",
                "create_import_candidate",
                "create_external_evidence",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            },
        )

    def test_rg01_freezes_unchanged_state_after_non_funding_operations(self):
        case = load_golden_case(RG01_PATH)
        created = case["create"]["expected"]
        note_updated = case["note_update"]["expected"]
        repeated = case["idempotency"]["expected"]

        self.assertEqual(case["case"]["rule_version"], 1)
        self.assertEqual(case["case"]["currency"], "CNY")
        self.assertEqual(case["case"]["precision"], 2)
        self.assertEqual(case["case"]["ledger_id"], "ledger-a")
        self.assertEqual(case["opening"]["expected_balances"]["asset-bank-a"], "1000.00")
        self.assertEqual(case["create"]["request"]["occurred_at"], "2026-01-15T08:30:00+08:00")

        self.assertEqual(note_updated["balances"], created["balances"])
        self.assertEqual(note_updated["statistics"], created["statistics"])
        self.assertEqual(note_updated["reconciliation"], created["reconciliation"])
        self.assertEqual(note_updated["evidence_refs"], created["evidence_refs"])

        self.assertEqual(repeated["returned_transaction_id"], created["transaction"]["id"])
        self.assertEqual(repeated["new_version_count"], 0)
        self.assertEqual(repeated["balances"], created["balances"])
        self.assertEqual(repeated["statistics"], created["statistics"])
        self.assertEqual(repeated["reconciliation"], created["reconciliation"])

        distinct = case["distinct_reentry"]
        self.assertNotEqual(distinct["request"]["request_id"], case["create"]["request"]["request_id"])
        self.assertEqual(distinct["request"]["amount"], case["create"]["request"]["amount"])
        self.assertEqual(distinct["request"]["occurred_at"], case["create"]["request"]["occurred_at"])
        self.assertTrue(distinct["expected"]["accepted"])
        self.assertEqual(distinct["expected"]["new_transaction_count"], 1)
        self.assertEqual(distinct["expected"]["effective_transaction_count"], 2)
        self.assertEqual(distinct["expected"]["balances"]["asset-bank-a"], "928.40")
        self.assertEqual(distinct["expected"]["statistics"]["month_consumption"], "71.60")

        zero_changes = {
            "balance": {"asset-bank-a": "0.00"},
            "statistics": {
                "consumption": "0.00",
                "cash_outflow": "0.00",
                "income": "0.00",
                "net_worth": "0.00",
            },
            "new_version_count": 0,
            "reconciliation_change_count": 0,
        }
        for invalid_input in case["invalid_inputs"]:
            with self.subTest(invalid_input=invalid_input["id"]):
                self.assertEqual(invalid_input["expected"]["state_changes"], zero_changes)


class GoldenCaseInvariantTests(unittest.TestCase):
    def test_rg01_satisfies_schema_and_financial_invariants(self):
        case = load_golden_case(RG01_PATH)
        validate_case_envelope(case)
        transactions = [
            *case["opening"]["transactions"],
            case["create"]["expected"]["transaction"],
        ]
        validate_transactions(transactions, case["catalog"]["accounts"])

        balances = replay_balances(transactions)
        self.assertEqual(balances["asset-bank-a"], Decimal("964.20"))
        assert_expected_balances(balances, case["create"]["expected"]["balances"])

    def test_distinct_reentry_replays_from_a_second_balanced_transaction(self):
        case = load_golden_case(RG01_PATH)
        transactions = [
            *case["opening"]["transactions"],
            case["create"]["expected"]["transaction"],
            case["distinct_reentry"]["expected"]["transaction"],
        ]

        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(
            replay_balances(transactions),
            case["distinct_reentry"]["expected"]["balances"],
        )

    def test_validator_reports_an_unbalanced_posting_set(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        case["create"]["expected"]["transaction"]["postings"][1]["amount"] = "-35.79"

        with self.assertRaisesRegex(
            GoldenCaseError,
            r"transactions\[0\] is not balanced for CNY: 0.01",
        ):
            validate_transactions(
                [case["create"]["expected"]["transaction"]],
                case["catalog"]["accounts"],
            )

    def test_validator_rejects_a_posting_id_reused_by_another_transaction(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        first_transaction = case["create"]["expected"]["transaction"]
        second_transaction = case["distinct_reentry"]["expected"]["transaction"]
        second_transaction["postings"][0]["id"] = first_transaction["postings"][0]["id"]

        with self.assertRaisesRegex(GoldenCaseError, "transactions contain duplicate posting ids"):
            validate_transactions(
                [first_transaction, second_transaction],
                case["catalog"]["accounts"],
            )

    def test_validator_rejects_a_transaction_time_without_timezone(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        transaction = case["create"]["expected"]["transaction"]
        transaction["occurred_at"] = "2026-01-15T08:30:00"

        with self.assertRaisesRegex(
            GoldenCaseError,
            r"transactions\[0\].occurred_at must be timezone-aware",
        ):
            validate_transactions([transaction], case["catalog"]["accounts"])

    def test_validator_rejects_binary_floating_point_amounts(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        transaction = case["create"]["expected"]["transaction"]
        transaction["postings"][0]["amount"] = 35.80

        with self.assertRaisesRegex(GoldenCaseError, "must be an exact two-decimal string"):
            validate_transactions([transaction], case["catalog"]["accounts"])

    def test_validator_rejects_an_unknown_posting_account(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        transaction = case["create"]["expected"]["transaction"]
        transaction["postings"][0]["account_id"] = "unknown-account"

        with self.assertRaisesRegex(
            GoldenCaseError,
            "posting references unknown account: unknown-account",
        ):
            validate_transactions([transaction], case["catalog"]["accounts"])

    def test_validator_reports_a_balance_that_does_not_replay(self):
        case = deepcopy(load_golden_case(RG01_PATH))
        case["create"]["expected"]["balances"]["asset-bank-a"] = "964.21"
        transactions = [
            *case["opening"]["transactions"],
            case["create"]["expected"]["transaction"],
        ]

        with self.assertRaisesRegex(
            GoldenCaseError,
            "asset-bank-a balance must replay to 964.20, got 964.21",
        ):
            assert_expected_balances(
                replay_balances(transactions),
                case["create"]["expected"]["balances"],
            )


if __name__ == "__main__":
    unittest.main()
