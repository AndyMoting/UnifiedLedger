from decimal import Decimal
from pathlib import Path
import unittest

from golden_cases import (
    assert_expected_balances,
    load_golden_case,
    replay_balances,
    validate_case_envelope,
    validate_transactions,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RG02_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-02.json"


class RG02GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG02_PATH)

    def test_salary_income_has_the_approved_balance_and_statistics(self):
        case = self.case
        validate_case_envelope(case)
        created = case["create"]["expected"]
        transactions = [*case["opening"]["transactions"], created["transaction"]]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), created["balances"])

        self.assertEqual(case["case"]["id"], "RG-02")
        self.assertEqual(case["create"]["request"]["amount"], "3000.00")
        self.assertEqual(case["create"]["request"]["category_id"], "income-category-salary")
        self.assertEqual(case["create"]["request"]["receiving_account_id"], "asset-bank-a")
        self.assertEqual(created["balances"], {"asset-bank-a": "4000.00"})
        self.assertEqual(
            created["statistics"],
            {
                "day": "2026-01-16",
                "month": "2026-01",
                "day_income": "3000.00",
                "month_income": "3000.00",
                "day_cash_inflow": "3000.00",
                "month_cash_inflow": "3000.00",
                "consumption": "0.00",
                "net_worth_change": "3000.00",
                "budget": "not_applicable",
            },
        )
        self.assertEqual(
            created["reconciliation"],
            {
                "posting-bank-rg02": "pending",
                "posting-income-rg02": "not_applicable",
                "transaction": "pending",
            },
        )
        self.assertEqual(created["evidence_refs"], [])
        self.assertEqual(len(created["transaction"]["postings"]), 2)
        self.assertEqual(
            case["create"]["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(case["create"]["candidate"])

    def test_category_rename_and_request_retry_do_not_change_money(self):
        case = self.case
        created = case["create"]["expected"]
        renamed = case["category_rename"]["expected"]
        repeated = case["idempotency"]["expected"]

        self.assertEqual(renamed["category_id"], "income-category-salary")
        self.assertEqual(renamed["current_name"], "薪资")
        self.assertEqual(renamed["display_path"], "职业收入 > 薪资")
        self.assertEqual(
            [version["name"] for version in renamed["name_versions"]],
            ["工资", "薪资"],
        )
        self.assertEqual(renamed["transaction_category_id"], "income-category-salary")
        self.assertEqual(renamed["posting_account_id"], "income-account-salary")
        self.assertEqual(renamed["transaction_version_count"], 1)
        self.assertEqual(renamed["funding_effect_count"], 1)
        self.assertEqual(renamed["balances"], created["balances"])
        self.assertEqual(renamed["statistics"], created["statistics"])
        self.assertEqual(renamed["reconciliation"], created["reconciliation"])
        self.assertEqual(renamed["evidence_refs"], created["evidence_refs"])
        self.assertEqual(repeated["returned_transaction_id"], created["transaction"]["id"])
        self.assertEqual(repeated["new_transaction_count"], 0)
        self.assertEqual(repeated["new_posting_set_count"], 0)
        self.assertEqual(repeated["balances"], created["balances"])
        self.assertEqual(repeated["statistics"], created["statistics"])
        self.assertEqual(repeated["reconciliation"], created["reconciliation"])
        self.assertEqual(repeated["evidence_refs"], created["evidence_refs"])

    def test_invalid_income_inputs_have_no_side_effects(self):
        invalid = {item["id"]: item for item in self.case["invalid_inputs"]}
        self.assertEqual(
            set(invalid),
            {
                "missing-amount",
                "zero-amount",
                "negative-amount",
                "missing-receiving-account",
                "missing-income-category",
                "primary-income-category",
                "inactive-income-category",
                "expense-category",
            },
        )
        self.assertEqual(
            invalid["primary-income-category"]["expected"]["reason"],
            "secondary_category_required",
        )
        self.assertEqual(
            invalid["inactive-income-category"]["expected"]["reason"],
            "category_inactive",
        )
        self.assertEqual(
            invalid["expense-category"]["expected"]["reason"],
            "income_category_required",
        )
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                self.assertFalse(item["expected"]["accepted"])
                self.assertTrue(item["expected"]["state_unchanged"])
                self.assertEqual(item["expected"]["new_transaction_count"], 0)
                self.assertEqual(item["expected"]["new_posting_count"], 0)
                self.assertEqual(item["expected"]["new_version_count"], 0)
                self.assertEqual(item["expected"]["reconciliation_change_count"], 0)

    def test_confirmed_income_variants_are_independent_and_balanced(self):
        expected = {
            "wallet-red-packet": ("asset-wallet-b", Decimal("88.88")),
            "bank-project-payment": ("asset-bank-a", Decimal("1200.00")),
        }
        variants = {variant["id"]: variant for variant in self.case["variants"]}
        self.assertEqual(set(variants), set(expected))

        for variant_id, (asset_account_id, amount) in expected.items():
            with self.subTest(variant=variant_id):
                variant = variants[variant_id]
                transaction = variant["expected"]["transaction"]
                validate_transactions([transaction], self.case["catalog"]["accounts"])
                balances = replay_balances([transaction])
                self.assertEqual(balances[asset_account_id], amount)
                assert_expected_balances(balances, variant["expected"]["balances"])
                self.assertEqual(Decimal(variant["request"]["amount"]), amount)
                self.assertEqual(Decimal(variant["expected"]["statistics"]["income"]), amount)
                self.assertEqual(Decimal(variant["expected"]["statistics"]["cash_inflow"]), amount)
                self.assertEqual(variant["expected"]["statistics"]["consumption"], "0.00")
                self.assertEqual(variant["expected"]["reconciliation"]["transaction"], "pending")
                self.assertTrue(variant["expected"]["accepted"])
                self.assertEqual(variant["expected"]["evidence_refs"], [])
                self.assertEqual(
                    variant["confirmation"],
                    {"mode": "explicit_manual_save", "confirmed": True},
                )
                self.assertIsNone(variant["candidate"])

        self.assertEqual(
            set(self.case["forbidden_side_effects"]),
            {
                "create_refund_relation",
                "create_transfer",
                "create_loan",
                "create_order_relation",
                "create_consumption",
                "create_budget_result",
                "create_import_candidate",
                "create_external_evidence",
                "automatic_name_based_income",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            },
        )


if __name__ == "__main__":
    unittest.main()
