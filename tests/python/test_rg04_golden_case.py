from copy import deepcopy
from pathlib import Path
import re
import unittest

from golden_cases import (
    assert_expected_balances,
    load_golden_case,
    replay_balances,
    validate_case_envelope,
    validate_transactions,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RG04_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-04.json"


class RG04GoldenCaseTests(unittest.TestCase):
    MONEY_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
    MONEY_CONTAINERS = {
        "expected_balances",
        "balances",
        "balance_changes",
        "statistics",
        "liability_display",
        "lifecycle_statistics",
    }
    NON_MONEY_STATISTICS = {"day", "month", "budget"}
    MONEY_KEYS = {"amount_owed", "payment_composition_total"}

    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG04_PATH)
        cls.manual_operations = {
            operation["id"]: operation
            for operation in cls.case["manual_lifecycle"]["ordered_operations"]
        }
        cls.import_operations = {
            operation["id"]: operation
            for operation in cls.case["import_lifecycle"]["ordered_operations"]
        }

    def assert_two_decimal_money_contract(self, node, path="case"):
        if isinstance(node, dict):
            for key, value in node.items():
                child_path = f"{path}.{key}"
                if key == "amount" or key.endswith("_amount") or key in self.MONEY_KEYS:
                    self.assertIsInstance(value, str, child_path)
                    self.assertRegex(value, self.MONEY_PATTERN, child_path)
                if key in self.MONEY_CONTAINERS:
                    for money_key, money_value in value.items():
                        if key == "statistics" and money_key in self.NON_MONEY_STATISTICS:
                            continue
                        money_path = f"{child_path}.{money_key}"
                        self.assertIsInstance(money_value, str, money_path)
                        self.assertRegex(money_value, self.MONEY_PATTERN, money_path)
                self.assert_two_decimal_money_contract(value, child_path)
        elif isinstance(node, list):
            for index, value in enumerate(node):
                self.assert_two_decimal_money_contract(value, f"{path}[{index}]")

    def all_formal_transactions(self, case):
        manual_operations = {
            operation["id"]: operation
            for operation in case["manual_lifecycle"]["ordered_operations"]
        }
        import_operations = {
            operation["id"]: operation
            for operation in case["import_lifecycle"]["ordered_operations"]
        }
        return [
            *case["opening"]["transactions"],
            manual_operations["manual-mixed-purchase"]["expected"]["transaction"],
            manual_operations["repay-credit-principal"]["expected"]["transaction"],
            import_operations["complete-and-confirm-candidate"]["expected"]["transaction"],
        ]

    def assert_cny_contract(self, case):
        def assert_nested_currency(node, path="case"):
            if isinstance(node, dict):
                if node.get("id") == "mixed-funding-currencies":
                    self.assertEqual(node["input"]["currency"], "CNY")
                    self.assertEqual(
                        [
                            component["currency"]
                            for component in node["input"]["funding_components"]
                        ],
                        ["CNY", "USD"],
                    )
                    return
                for key, value in node.items():
                    child_path = f"{path}.{key}"
                    if key == "currency":
                        self.assertEqual(value, "CNY", child_path)
                    assert_nested_currency(value, child_path)
            elif isinstance(node, list):
                for index, value in enumerate(node):
                    assert_nested_currency(value, f"{path}[{index}]")

        assert_nested_currency(case)
        for transaction in self.all_formal_transactions(case):
            self.assertEqual(
                {posting["currency"] for posting in transaction["postings"]},
                {"CNY"},
                transaction["id"],
            )

    def test_fixture_enforces_schema_cny_and_exact_two_decimal_money(self):
        validate_case_envelope(self.case)
        self.assertEqual(self.case["case"]["id"], "RG-04")
        self.assertEqual(self.case["case"]["rule_version"], 1)
        self.assertEqual(self.case["case"]["precision"], 2)
        self.assertEqual(self.case["case"]["timezone"], "Asia/Shanghai")
        self.assert_two_decimal_money_contract(self.case)
        self.assert_cny_contract(self.case)

        binary_float = deepcopy(self.case)
        binary_float["manual_lifecycle"]["ordered_operations"][0]["input"]["total_amount"] = 120.0
        with self.assertRaises(AssertionError):
            self.assert_two_decimal_money_contract(binary_float)

        changed_currency = deepcopy(self.case)
        transaction = changed_currency["manual_lifecycle"]["ordered_operations"][0][
            "expected"
        ]["transaction"]
        for posting in transaction["postings"]:
            posting["currency"] = "USD"
        validate_transactions([transaction], changed_currency["catalog"]["accounts"])
        with self.assertRaises(AssertionError):
            self.assert_cny_contract(changed_currency)

    def test_opening_and_manual_purchase_are_balanced_and_replay_exactly(self):
        case = self.case
        purchase_operation = self.manual_operations["manual-mixed-purchase"]
        expected = purchase_operation["expected"]
        transactions = [*case["opening"]["transactions"], expected["transaction"]]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), expected["balances"])

        self.assertEqual(
            purchase_operation["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(purchase_operation["candidate"])
        self.assertEqual(
            [
                (posting["account_id"], posting["amount"])
                for posting in expected["transaction"]["postings"]
            ],
            [
                ("expense-account-daily", "120.00"),
                ("asset-bank-a", "-70.00"),
                ("liability-credit-b", "-50.00"),
            ],
        )
        self.assertEqual(
            expected["transaction"]["postings"][0]["category_id"],
            "expense-category-daily",
        )
        self.assertEqual(
            expected["balances"],
            {"asset-bank-a": "930.00", "liability-credit-b": "-50.00"},
        )
        self.assertEqual(expected["liability_display"], {"amount_owed": "50.00"})

    def test_purchase_reports_consumption_once_and_actual_asset_cash_timing(self):
        expected = self.manual_operations["manual-mixed-purchase"]["expected"]
        self.assertEqual(
            expected["statistics"],
            {
                "day": "2026-02-10",
                "month": "2026-02",
                "day_consumption": "120.00",
                "month_consumption": "120.00",
                "day_cash_outflow": "70.00",
                "month_cash_outflow": "70.00",
                "income": "0.00",
                "net_worth_change": "-120.00",
                "budget": "not_applicable",
            },
        )
        explanation = expected["settlement_explanation"]
        self.assertEqual(explanation["original_amount"], "135.00")
        self.assertEqual(explanation["discount_amount"], "15.00")
        self.assertEqual(explanation["settled_amount"], "120.00")
        self.assertEqual(explanation["discount_accounting_effect_count"], 0)

    def test_mixed_payment_group_has_specific_identity_and_exact_components(self):
        group = self.manual_operations["manual-mixed-purchase"]["expected"][
            "association_group"
        ]
        self.assertEqual(group["type"], "mixed_payment")
        self.assertTrue(group["system_managed"])
        self.assertEqual(group["display_name"], "混合支付")
        self.assertEqual(group["business_transaction_id"], "tx-purchase-rg04-manual")
        self.assertEqual(
            [
                (
                    component["posting_id"],
                    component["account_id"],
                    component["funding_amount"],
                )
                for component in group["funding_components"]
            ],
            [
                ("posting-asset-rg04-manual", "asset-bank-a", "70.00"),
                ("posting-liability-rg04-manual", "liability-credit-b", "50.00"),
            ],
        )
        self.assertEqual(group["payment_composition_total"], "120.00")
        self.assertFalse(group["generic_order_lifecycle"])

    def test_follow_up_repayment_is_balanced_cash_outflow_without_consumption(self):
        case = self.case
        purchase = self.manual_operations["manual-mixed-purchase"]["expected"]
        repayment = self.manual_operations["repay-credit-principal"]["expected"]
        self.assertEqual(
            self.manual_operations["repay-credit-principal"]["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(self.manual_operations["repay-credit-principal"]["candidate"])
        transactions = [
            *case["opening"]["transactions"],
            purchase["transaction"],
            repayment["transaction"],
        ]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), repayment["balances"])

        self.assertEqual(repayment["transaction"]["business_type"], "credit_repayment")
        self.assertEqual(repayment["transaction"]["principal_amount"], "50.00")
        self.assertEqual(
            [
                (posting["account_id"], posting["amount"])
                for posting in repayment["transaction"]["postings"]
            ],
            [("asset-bank-a", "-50.00"), ("liability-credit-b", "50.00")],
        )
        self.assertEqual(
            repayment["statistics"],
            {
                "day": "2026-03-05",
                "month": "2026-03",
                "day_consumption": "0.00",
                "month_consumption": "0.00",
                "day_cash_outflow": "50.00",
                "month_cash_outflow": "50.00",
                "income": "0.00",
                "net_worth_change": "0.00",
                "budget": "not_applicable",
            },
        )
        self.assertEqual(repayment["lifecycle_statistics"]["total_consumption"], "120.00")
        self.assertEqual(repayment["lifecycle_statistics"]["total_cash_outflow"], "120.00")
        self.assertEqual(repayment["lifecycle_statistics"]["total_income"], "0.00")
        self.assertEqual(
            repayment["lifecycle_statistics"]["total_net_worth_change"],
            "-120.00",
        )
        self.assertEqual(repayment["duplicate_consumption_count"], 0)

    def test_complete_import_stays_pending_with_zero_formal_effects(self):
        imported = self.import_operations["import-complete-mixed-payment"]
        expected = imported["expected"]
        effects = expected["formal_effects"]
        self.assertEqual(imported["input"]["source_record"]["completeness"], "complete")
        self.assertEqual(expected["candidate"]["status"], "pending_confirmation")
        self.assertEqual(
            expected["candidate"]["requires_confirmation"],
            ["category_id", "funding_components", "formal_transaction_creation"],
        )
        self.assertEqual(effects["new_transaction_count"], 0)
        self.assertEqual(effects["new_posting_count"], 0)
        self.assertEqual(effects["new_association_group_count"], 0)
        self.assertEqual(effects["balances"], self.case["opening"]["expected_balances"])
        self.assertEqual(
            effects["statistics"],
            {
                "consumption": "0.00",
                "cash_outflow": "0.00",
                "income": "0.00",
                "net_worth_change": "0.00",
            },
        )
        self.assertEqual(effects["reconciliation_change_count"], 0)

    def test_user_completion_creates_one_purchase_and_partial_reconciliation(self):
        case = self.case
        operation = self.import_operations["complete-and-confirm-candidate"]
        expected = operation["expected"]
        transactions = [*case["opening"]["transactions"], expected["transaction"]]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), expected["balances"])

        self.assertTrue(operation["input"]["confirmed"])
        self.assertEqual(expected["candidate_status"], "confirmed")
        self.assertEqual(expected["effective_transaction_count"], 1)
        self.assertEqual(expected["expense_effect_count"], 1)
        self.assertEqual(expected["consumption_effect_count"], 1)
        self.assertEqual(expected["cash_flow_effect_count"], 1)
        self.assertEqual(expected["statistics"]["day_consumption"], "120.00")
        self.assertEqual(expected["statistics"]["day_cash_outflow"], "70.00")
        self.assertEqual(
            expected["reconciliation"],
            {
                "posting-expense-rg04-imported": "not_applicable",
                "posting-asset-rg04-imported": "matched",
                "posting-liability-rg04-imported": "pending",
                "transaction": "partial",
            },
        )
        self.assertEqual(expected["source_refs"], ["source-record-rg04-complete"])
        self.assertEqual(expected["evidence_refs"], ["evidence-rg04-asset-debit"])
        self.assertEqual(
            expected["evidence_links"],
            [
                {
                    "id": "match-rg04-asset-imported",
                    "evidence_id": "evidence-rg04-asset-debit",
                    "posting_id": "posting-asset-rg04-imported",
                    "status": "matched",
                }
            ],
        )
        group = expected["association_group"]
        self.assertEqual(
            group,
            {
                "id": "association-group-rg04-imported",
                "type": "mixed_payment",
                "system_managed": True,
                "display_name": "混合支付",
                "business_transaction_id": "tx-purchase-rg04-imported",
                "funding_components": [
                    {
                        "posting_id": "posting-asset-rg04-imported",
                        "account_id": "asset-bank-a",
                        "funding_amount": "70.00",
                        "currency": "CNY",
                    },
                    {
                        "posting_id": "posting-liability-rg04-imported",
                        "account_id": "liability-credit-b",
                        "funding_amount": "50.00",
                        "currency": "CNY",
                    },
                ],
                "payment_composition_total": "120.00",
                "generic_order_lifecycle": False,
            },
        )
        self.assertEqual(expected["association_group_count"], 1)

    def test_mirror_evidence_completes_exact_posting_without_duplicates(self):
        confirmed = self.import_operations["complete-and-confirm-candidate"]["expected"]
        merged = self.import_operations["merge-liability-mirror-evidence"]["expected"]
        self.assertEqual(merged["merged_into_transaction_id"], confirmed["transaction"]["id"])
        self.assertEqual(merged["current_version_id"], confirmed["transaction"]["current_version_id"])
        self.assertEqual(merged["effective_posting_set_id"], confirmed["transaction"]["posting_set_id"])
        self.assertEqual(merged["posting_ids"], [posting["id"] for posting in confirmed["transaction"]["postings"]])
        self.assertEqual(merged["new_transaction_count"], 0)
        self.assertEqual(merged["new_posting_count"], 0)
        self.assertEqual(merged["new_version_count"], 0)
        self.assertEqual(merged["new_association_group_count"], 0)
        self.assertEqual(merged["expense_effect_count"], 1)
        self.assertEqual(merged["consumption_effect_count"], 1)
        self.assertEqual(merged["cash_flow_effect_count"], 1)
        self.assertEqual(merged["statistics"], confirmed["statistics"])
        self.assertEqual(
            merged["reconciliation"],
            {
                "posting-expense-rg04-imported": "not_applicable",
                "posting-asset-rg04-imported": "matched",
                "posting-liability-rg04-imported": "matched",
                "transaction": "complete",
            },
        )
        self.assertEqual(
            merged["source_refs"],
            ["source-record-rg04-complete", "source-record-rg04-liability-mirror"],
        )
        self.assertEqual(
            merged["evidence_refs"],
            ["evidence-rg04-asset-debit", "evidence-rg04-liability-mirror"],
        )
        self.assertEqual(
            merged["evidence_links"],
            [
                {
                    "id": "match-rg04-asset-imported",
                    "evidence_id": "evidence-rg04-asset-debit",
                    "posting_id": "posting-asset-rg04-imported",
                    "status": "matched",
                },
                {
                    "id": "match-rg04-liability-mirror",
                    "evidence_id": "evidence-rg04-liability-mirror",
                    "posting_id": "posting-liability-rg04-imported",
                    "status": "matched",
                },
            ],
        )
        self.assertEqual(
            merged["association_group"],
            confirmed["association_group"],
        )
        self.assertEqual(merged["association_group_count"], 1)

    def test_missing_funding_leg_is_never_guessed_or_hidden(self):
        missing = self.case["missing_funding_leg"]
        expected = missing["expected"]
        self.assertEqual(expected["candidate"]["status"], "pending_confirmation")
        self.assertEqual(expected["candidate"]["missing_funding_amount"], "50.00")
        self.assertIn("funding_account_id", expected["candidate"]["requires_confirmation"])
        self.assertEqual(expected["new_transaction_count"], 0)
        self.assertEqual(expected["new_posting_count"], 0)
        self.assertEqual(expected["new_association_group_count"], 0)
        self.assertIsNone(expected["guessed_funding_account_id"])
        self.assertIsNone(expected["balancing_account_id"])
        self.assertEqual(expected["hidden_posting_count"], 0)
        self.assertEqual(expected["balances"], self.case["opening"]["expected_balances"])
        self.assertEqual(expected["statistics"]["consumption"], "0.00")
        self.assertEqual(expected["statistics"]["cash_outflow"], "0.00")
        self.assertEqual(missing["retry"]["expected"]["new_candidate_count"], 0)
        self.assertEqual(missing["retry"]["expected"]["new_transaction_count"], 0)

    def test_idempotent_retries_preserve_complete_resulting_states(self):
        expected = self.case["idempotency"]["expected"]
        manual_group = {
            "id": "association-group-rg04-manual",
            "type": "mixed_payment",
            "system_managed": True,
            "display_name": "混合支付",
            "business_transaction_id": "tx-purchase-rg04-manual",
            "funding_components": [
                {
                    "posting_id": "posting-asset-rg04-manual",
                    "account_id": "asset-bank-a",
                    "funding_amount": "70.00",
                    "currency": "CNY",
                },
                {
                    "posting_id": "posting-liability-rg04-manual",
                    "account_id": "liability-credit-b",
                    "funding_amount": "50.00",
                    "currency": "CNY",
                },
            ],
            "payment_composition_total": "120.00",
            "generic_order_lifecycle": False,
        }
        imported_group = {
            "id": "association-group-rg04-imported",
            "type": "mixed_payment",
            "system_managed": True,
            "display_name": "混合支付",
            "business_transaction_id": "tx-purchase-rg04-imported",
            "funding_components": [
                {
                    "posting_id": "posting-asset-rg04-imported",
                    "account_id": "asset-bank-a",
                    "funding_amount": "70.00",
                    "currency": "CNY",
                },
                {
                    "posting_id": "posting-liability-rg04-imported",
                    "account_id": "liability-credit-b",
                    "funding_amount": "50.00",
                    "currency": "CNY",
                },
            ],
            "payment_composition_total": "120.00",
            "generic_order_lifecycle": False,
        }
        self.assertEqual(
            expected["manual_state"],
            {
                "transaction_id": "tx-purchase-rg04-manual",
                "current_version_id": "version-purchase-rg04-manual-v1",
                "posting_set_id": "posting-set-purchase-rg04-manual",
                "posting_ids": [
                    "posting-expense-rg04-manual",
                    "posting-asset-rg04-manual",
                    "posting-liability-rg04-manual",
                ],
                "association_group_count": 1,
                "association_group": manual_group,
                "balances": {
                    "asset-bank-a": "930.00",
                    "liability-credit-b": "-50.00",
                },
                "statistics": {
                    "day": "2026-02-10",
                    "month": "2026-02",
                    "day_consumption": "120.00",
                    "month_consumption": "120.00",
                    "day_cash_outflow": "70.00",
                    "month_cash_outflow": "70.00",
                    "income": "0.00",
                    "net_worth_change": "-120.00",
                    "budget": "not_applicable",
                },
                "reconciliation": {
                    "posting-expense-rg04-manual": "not_applicable",
                    "posting-asset-rg04-manual": "pending",
                    "posting-liability-rg04-manual": "pending",
                    "transaction": "pending",
                },
                "source_refs": [],
                "evidence_refs": [],
                "evidence_links": [],
            },
        )
        self.assertEqual(
            expected["repayment_state"],
            {
                "transaction_id": "tx-repayment-rg04",
                "current_version_id": "version-repayment-rg04-v1",
                "posting_set_id": "posting-set-repayment-rg04",
                "posting_ids": [
                    "posting-repayment-asset-rg04",
                    "posting-repayment-liability-rg04",
                ],
                "balances": {
                    "asset-bank-a": "880.00",
                    "liability-credit-b": "0.00",
                },
                "statistics": {
                    "day": "2026-03-05",
                    "month": "2026-03",
                    "day_consumption": "0.00",
                    "month_consumption": "0.00",
                    "day_cash_outflow": "50.00",
                    "month_cash_outflow": "50.00",
                    "income": "0.00",
                    "net_worth_change": "0.00",
                    "budget": "not_applicable",
                },
                "reconciliation": {
                    "posting-repayment-asset-rg04": "pending",
                    "posting-repayment-liability-rg04": "pending",
                    "transaction": "pending",
                },
                "source_refs": [],
                "evidence_refs": [],
                "evidence_links": [],
            },
        )
        self.assertEqual(
            expected["import_state"],
            {
                "candidate_id": "candidate-purchase-rg04",
                "candidate_status": "confirmed",
                "transaction_id": "tx-purchase-rg04-imported",
                "current_version_id": "version-purchase-rg04-imported-v1",
                "posting_set_id": "posting-set-purchase-rg04-imported",
                "posting_ids": [
                    "posting-expense-rg04-imported",
                    "posting-asset-rg04-imported",
                    "posting-liability-rg04-imported",
                ],
                "association_group_count": 1,
                "association_group": imported_group,
                "balances": {
                    "asset-bank-a": "930.00",
                    "liability-credit-b": "-50.00",
                },
                "statistics": {
                    "day": "2026-02-11",
                    "month": "2026-02",
                    "day_consumption": "120.00",
                    "month_consumption": "120.00",
                    "day_cash_outflow": "70.00",
                    "month_cash_outflow": "70.00",
                    "income": "0.00",
                    "net_worth_change": "-120.00",
                    "budget": "not_applicable",
                },
                "reconciliation": {
                    "posting-expense-rg04-imported": "not_applicable",
                    "posting-asset-rg04-imported": "matched",
                    "posting-liability-rg04-imported": "matched",
                    "transaction": "complete",
                },
                "source_refs": [
                    "source-record-rg04-complete",
                    "source-record-rg04-liability-mirror",
                ],
                "evidence_refs": [
                    "evidence-rg04-asset-debit",
                    "evidence-rg04-liability-mirror",
                ],
                "evidence_links": [
                    {
                        "id": "match-rg04-asset-imported",
                        "evidence_id": "evidence-rg04-asset-debit",
                        "posting_id": "posting-asset-rg04-imported",
                        "status": "matched",
                    },
                    {
                        "id": "match-rg04-liability-mirror",
                        "evidence_id": "evidence-rg04-liability-mirror",
                        "posting_id": "posting-liability-rg04-imported",
                        "status": "matched",
                    },
                ],
            },
        )

    def test_retries_validation_scope_and_forbidden_effects_are_frozen(self):
        idempotency = self.case["idempotency"]
        self.assertEqual(
            idempotency["retried_inputs"],
            [
                "request-rg04-manual-purchase",
                "request-rg04-repayment",
                "source-record-rg04-complete",
                "request-rg04-confirm-candidate",
                "evidence-rg04-liability-mirror",
            ],
        )
        expected = idempotency["expected"]
        self.assertEqual(
            expected["manual_returned_transaction_id"],
            "tx-purchase-rg04-manual",
        )
        self.assertEqual(
            expected["repayment_returned_transaction_id"],
            "tx-repayment-rg04",
        )
        self.assertEqual(
            expected["import_returned_candidate_id"],
            "candidate-purchase-rg04",
        )
        self.assertEqual(
            expected["import_returned_transaction_id"],
            "tx-purchase-rg04-imported",
        )
        for key in (
            "new_candidate_count",
            "new_transaction_count",
            "new_posting_count",
            "new_version_count",
            "new_evidence_link_count",
            "new_association_group_count",
            "duplicate_expense_count",
            "duplicate_consumption_count",
            "duplicate_cash_flow_count",
        ):
            self.assertEqual(expected[key], 0, key)

        invalid = {item["id"]: item for item in self.case["invalid_manual_inputs"]}
        self.assertEqual(
            set(invalid),
            {
                "missing-secondary-category",
                "funding-total-mismatch",
                "zero-total",
                "unknown-funding-account",
                "negative-total",
                "zero-funding-leg",
                "negative-funding-leg",
                "duplicate-funding-account",
                "known-nonfinancial-account",
                "known-non-owned-account",
                "primary-category",
                "inactive-secondary-category",
                "wrong-kind-income-category",
                "mixed-funding-currencies",
            },
        )
        self.assertEqual(len(invalid), 14)
        self.assertEqual(
            {
                item_id: (item["expected"]["field"], item["expected"]["reason"])
                for item_id, item in invalid.items()
            },
            {
                "missing-secondary-category": (
                    "category_id",
                    "secondary_category_required",
                ),
                "funding-total-mismatch": (
                    "funding_components",
                    "funding_total_must_equal_expense",
                ),
                "zero-total": ("total_amount", "must_be_positive"),
                "unknown-funding-account": (
                    "funding_components",
                    "unknown_real_account",
                ),
                "negative-total": ("total_amount", "must_be_positive"),
                "zero-funding-leg": (
                    "funding_components",
                    "funding_leg_must_be_positive",
                ),
                "negative-funding-leg": (
                    "funding_components",
                    "funding_leg_must_be_positive",
                ),
                "duplicate-funding-account": (
                    "funding_components",
                    "duplicate_funding_account",
                ),
                "known-nonfinancial-account": (
                    "funding_components",
                    "real_financial_account_required",
                ),
                "known-non-owned-account": (
                    "funding_components",
                    "owned_account_required",
                ),
                "primary-category": (
                    "category_id",
                    "secondary_category_required",
                ),
                "inactive-secondary-category": (
                    "category_id",
                    "category_inactive",
                ),
                "wrong-kind-income-category": (
                    "category_id",
                    "expense_category_required",
                ),
                "mixed-funding-currencies": (
                    "funding_components",
                    "single_currency_required",
                ),
            },
        )
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                self.assertFalse(item["expected"]["accepted"])
                self.assertTrue(item["expected"]["state_unchanged"])
                self.assertEqual(item["expected"]["new_transaction_count"], 0)
                self.assertEqual(item["expected"]["new_posting_count"], 0)
                self.assertEqual(item["expected"]["new_version_count"], 0)
                self.assertEqual(item["expected"]["new_association_group_count"], 0)
                self.assertEqual(item["expected"]["reconciliation_change_count"], 0)

        accounts = {account["id"]: account for account in self.case["catalog"]["accounts"]}
        self.assertFalse(accounts["expense-account-daily"]["real_account"])
        self.assertTrue(accounts["asset-external-x"]["real_account"])
        self.assertFalse(accounts["asset-external-x"]["owned_by_user"])
        categories = {
            category["id"]: category
            for category in self.case["catalog"]["categories"]
        }
        self.assertIsNone(categories["expense-category-living"]["parent_id"])
        self.assertFalse(categories["expense-category-inactive"]["active"])
        self.assertEqual(categories["income-category-other"]["kind"], "income")

        self.assertEqual(
            self.case["out_of_scope"],
            {
                "refunds": "RG-07",
                "merged_payments": "RG-05",
                "staged_or_unpaid_amounts": "RG-06",
                "later_posting_corrections": "RG-12",
            },
        )
        forbidden = set(self.case["forbidden_side_effects"])
        self.assertTrue(
            {
                "create_generic_order_lifecycle",
                "guess_missing_funding_leg",
                "create_hidden_balancing_posting",
                "auto_confirm_import_candidate",
                "duplicate_expense",
                "duplicate_consumption",
                "duplicate_cash_flow",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            }.issubset(forbidden)
        )


if __name__ == "__main__":
    unittest.main()
