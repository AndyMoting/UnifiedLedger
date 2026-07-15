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
RG03_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-03.json"


class RG03GoldenCaseTests(unittest.TestCase):
    MONEY_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
    MONEY_CONTAINERS = {"expected_balances", "balances", "balance_changes", "statistics"}
    NON_MONEY_STATISTICS = {"day", "month", "budget"}

    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG03_PATH)
        cls.import_operations = {
            operation["id"]: operation
            for operation in cls.case["import_lifecycle"]["ordered_operations"]
        }

    def assert_two_decimal_money_contract(self, node, path="case"):
        if isinstance(node, dict):
            for key, value in node.items():
                child_path = f"{path}.{key}"
                if key == "amount" or key.endswith("_amount"):
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

    def assert_cny_contract(self, case):
        self.assertEqual(case["case"]["currency"], "CNY")
        import_operations = {
            operation["id"]: operation
            for operation in case["import_lifecycle"]["ordered_operations"]
        }
        transactions = [
            *case["opening"]["transactions"],
            case["manual_create"]["expected"]["transaction"],
            import_operations["confirm-import-candidate"]["expected"]["transaction"],
        ]
        for transaction in transactions:
            self.assertEqual(
                {posting["currency"] for posting in transaction["postings"]},
                {"CNY"},
                transaction["id"],
            )

        self.assertEqual(case["manual_create"]["request"]["currency"], "CNY")
        for operation in case["import_lifecycle"]["ordered_operations"]:
            source_record = operation["input"].get("source_record")
            if source_record is not None:
                self.assertEqual(source_record["currency"], "CNY")
        self.assertEqual(
            case["unknown_one_sided_debit"]["input"]["source_record"]["currency"],
            "CNY",
        )
        for invalid in case["invalid_manual_inputs"]:
            request = invalid["input"]
            if invalid["id"] == "cross-currency":
                self.assertEqual(request["source_currency"], "CNY")
                self.assertEqual(request["destination_currency"], "USD")
            else:
                self.assertEqual(request["currency"], "CNY")

    def test_fixture_enforces_cny_and_exact_two_decimal_money(self):
        self.assert_cny_contract(self.case)
        self.assert_two_decimal_money_contract(self.case)

        changed_currency = deepcopy(self.case)
        changed_transaction = changed_currency["manual_create"]["expected"]["transaction"]
        for posting in changed_transaction["postings"]:
            posting["currency"] = "USD"
        validate_transactions([changed_transaction], changed_currency["catalog"]["accounts"])
        with self.assertRaises(AssertionError):
            self.assert_cny_contract(changed_currency)

        binary_float = deepcopy(self.case)
        binary_float["manual_create"]["request"]["fee_amount"] = 1.0
        with self.assertRaises(AssertionError):
            self.assert_two_decimal_money_contract(binary_float)

    def test_manual_transfer_is_balanced_and_only_the_fee_is_external(self):
        case = self.case
        validate_case_envelope(case)
        created = case["manual_create"]["expected"]
        transactions = [*case["opening"]["transactions"], created["transaction"]]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), created["balances"])

        self.assertEqual(case["case"]["id"], "RG-03")
        self.assertEqual(
            case["case"]["scope"],
            "one_to_one_same_currency_own_real_financial_account_transfer",
        )
        self.assertEqual(
            case["manual_create"]["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(case["manual_create"]["candidate"])
        self.assertEqual(
            [
                (posting["account_id"], posting["amount"])
                for posting in created["transaction"]["postings"]
            ],
            [
                ("asset-bank-a", "-60.00"),
                ("asset-wallet-b", "59.00"),
                ("expense-account-transfer-fee", "1.00"),
            ],
        )
        self.assertEqual(
            created["transaction"]["postings"][2]["category_id"],
            "expense-category-transfer-fee",
        )
        self.assertEqual(
            created["balances"],
            {"asset-bank-a": "940.00", "asset-wallet-b": "159.00"},
        )
        self.assertEqual(created["statistics"]["day_consumption"], "1.00")
        self.assertEqual(created["statistics"]["day_cash_outflow"], "1.00")
        self.assertEqual(created["statistics"]["day_income"], "0.00")
        self.assertEqual(created["statistics"]["principal_consumption"], "0.00")
        self.assertEqual(
            created["statistics"]["principal_external_cash_flow"],
            "0.00",
        )
        self.assertEqual(created["statistics"]["net_worth_change"], "-1.00")
        self.assertEqual(created["reconciliation"]["transaction"], "pending")
        self.assertEqual(created["evidence_refs"], [])

    def test_complete_import_stays_pending_with_zero_formal_effects(self):
        operations = self.case["import_lifecycle"]["ordered_operations"]
        self.assertEqual([operation["sequence"] for operation in operations], [1, 2, 3])

        imported = self.import_operations["import-complete-source"]
        source = imported["input"]["source_record"]
        expected = imported["expected"]
        effects = expected["formal_effects"]

        self.assertEqual(source["completeness"], "complete")
        self.assertEqual(source["source_debit_amount"], "60.00")
        self.assertEqual(source["destination_credit_amount"], "59.00")
        self.assertEqual(source["fee_amount"], "1.00")
        self.assertEqual(expected["candidate"]["status"], "pending_confirmation")
        self.assertEqual(expected["candidate"]["source_refs"], ["source-record-rg03-debit"])
        self.assertEqual(expected["candidate"]["evidence_refs"], ["evidence-rg03-debit"])
        self.assertEqual(effects["new_transaction_count"], 0)
        self.assertEqual(effects["new_posting_count"], 0)
        self.assertEqual(effects["funding_effect_count"], 0)
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

    def test_confirmed_import_creates_one_transfer_with_source_provenance(self):
        case = self.case
        confirmed = self.import_operations["confirm-import-candidate"]
        expected = confirmed["expected"]
        transactions = [*case["opening"]["transactions"], expected["transaction"]]
        validate_transactions(transactions, case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), expected["balances"])

        self.assertTrue(confirmed["input"]["confirmed"])
        self.assertEqual(confirmed["input"]["kind"], "explicit_candidate_confirmation")
        self.assertEqual(expected["candidate_status"], "confirmed")
        self.assertEqual(expected["effective_transaction_count"], 1)
        self.assertEqual(expected["funding_effect_count"], 1)
        self.assertEqual(
            [
                (
                    posting["id"],
                    posting["account_id"],
                    posting["amount"],
                    posting["currency"],
                    posting.get("category_id"),
                )
                for posting in expected["transaction"]["postings"]
            ],
            [
                ("posting-source-rg03-imported", "asset-bank-a", "-60.00", "CNY", None),
                ("posting-destination-rg03-imported", "asset-wallet-b", "59.00", "CNY", None),
                (
                    "posting-fee-rg03-imported",
                    "expense-account-transfer-fee",
                    "1.00",
                    "CNY",
                    "expense-category-transfer-fee",
                ),
            ],
        )
        self.assertEqual(
            expected["transaction"]["provenance"],
            {
                "kind": "confirmed_import_candidate",
                "candidate_id": "candidate-transfer-rg03",
                "confirmation_ref": "request-rg03-confirm-candidate",
                "source_refs": ["source-record-rg03-debit"],
            },
        )
        self.assertEqual(expected["statistics"]["day_consumption"], "1.00")
        self.assertEqual(expected["statistics"]["day_cash_outflow"], "1.00")
        self.assertEqual(expected["statistics"]["day_income"], "0.00")
        self.assertEqual(expected["statistics"]["net_worth_change"], "-1.00")
        self.assertEqual(expected["reconciliation"]["transaction"], "partially_matched")
        self.assertEqual(
            expected["reconciliation"]["posting-destination-rg03-imported"],
            "pending",
        )
        self.assertEqual(
            expected["evidence_links"],
            [
                {
                    "id": "match-rg03-debit",
                    "evidence_id": "evidence-rg03-debit",
                    "posting_id": "posting-source-rg03-imported",
                    "status": "matched",
                }
            ],
        )

    def test_mirror_evidence_merges_without_duplicate_money_or_statistics(self):
        confirmed = self.import_operations["confirm-import-candidate"]["expected"]
        merged = self.import_operations["merge-mirror-evidence"]["expected"]

        self.assertEqual(merged["merged_into_transaction_id"], confirmed["transaction"]["id"])
        self.assertEqual(
            merged["current_version_id"],
            confirmed["transaction"]["current_version_id"],
        )
        self.assertEqual(
            merged["effective_posting_set_id"],
            confirmed["transaction"]["posting_set_id"],
        )
        self.assertEqual(
            merged["posting_ids"],
            [posting["id"] for posting in confirmed["transaction"]["postings"]],
        )
        self.assertEqual(merged["new_transaction_count"], 0)
        self.assertEqual(merged["new_posting_count"], 0)
        self.assertEqual(merged["new_version_count"], 0)
        self.assertEqual(merged["effective_transaction_count"], 1)
        self.assertEqual(merged["funding_effect_count"], 1)
        self.assertEqual(merged["balances"], confirmed["balances"])
        self.assertEqual(merged["statistics"], confirmed["statistics"])
        self.assertEqual(merged["reconciliation"]["transaction"], "matched")
        self.assertEqual(
            merged["reconciliation"]["posting-destination-rg03-imported"],
            "matched",
        )
        self.assertEqual(
            merged["evidence_refs"],
            ["evidence-rg03-debit", "evidence-rg03-credit-mirror"],
        )
        self.assertEqual(
            merged["evidence_links"],
            [
                {
                    "id": "match-rg03-debit",
                    "evidence_id": "evidence-rg03-debit",
                    "posting_id": "posting-source-rg03-imported",
                    "status": "matched",
                },
                {
                    "id": "match-rg03-credit-mirror",
                    "evidence_id": "evidence-rg03-credit-mirror",
                    "posting_id": "posting-destination-rg03-imported",
                    "status": "matched",
                },
            ],
        )
        self.assertEqual(merged["duplicate_income_count"], 0)
        self.assertEqual(merged["duplicate_statistics_effect_count"], 0)

    def test_unknown_debit_validation_and_retries_have_no_hidden_effects(self):
        unknown = self.case["unknown_one_sided_debit"]["expected"]
        self.assertEqual(unknown["candidate"]["status"], "pending_confirmation")
        self.assertIn(
            "destination_account_id",
            unknown["candidate"]["requires_confirmation"],
        )
        self.assertEqual(unknown["new_transaction_count"], 0)
        self.assertEqual(unknown["new_posting_count"], 0)
        self.assertEqual(unknown["new_version_count"], 0)
        self.assertEqual(unknown["balance_changes"], {"asset-bank-a": "0.00", "asset-wallet-b": "0.00"})
        self.assertEqual(unknown["balances"], self.case["opening"]["expected_balances"])
        self.assertEqual(
            unknown["statistics"],
            {"consumption": "0.00", "cash_outflow": "0.00", "income": "0.00", "net_worth_change": "0.00"},
        )
        self.assertEqual(unknown["reconciliation_change_count"], 0)
        self.assertIsNone(unknown["balancing_account_id"])
        self.assertEqual(unknown["suspense_posting_count"], 0)

        unknown_retry = self.case["unknown_one_sided_debit"]["retry"]["expected"]
        self.assertEqual(unknown_retry["candidate_status"], "pending_confirmation")
        self.assertEqual(unknown_retry["new_candidate_count"], 0)
        self.assertEqual(unknown_retry["new_transaction_count"], 0)
        self.assertEqual(unknown_retry["new_posting_count"], 0)
        self.assertEqual(unknown_retry["new_version_count"], 0)
        self.assertEqual(unknown_retry["reconciliation_change_count"], 0)
        self.assertEqual(unknown_retry["balance_changes"], unknown["balance_changes"])
        self.assertEqual(unknown_retry["balances"], unknown["balances"])
        self.assertEqual(unknown_retry["statistics"], unknown["statistics"])

        invalid = {item["id"]: item for item in self.case["invalid_manual_inputs"]}
        self.assertEqual(
            set(invalid),
            {
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
            },
        )
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                self.assertFalse(item["expected"]["accepted"])
                self.assertTrue(item["expected"]["state_unchanged"])
                self.assertEqual(item["expected"]["new_transaction_count"], 0)
                self.assertEqual(item["expected"]["new_posting_count"], 0)
                self.assertEqual(item["expected"]["new_version_count"], 0)
                self.assertEqual(item["expected"]["reconciliation_change_count"], 0)

        liability = next(
            account
            for account in self.case["catalog"]["accounts"]
            if account["id"] == "liability-credit-c"
        )
        self.assertEqual(liability["kind"], "liability")
        self.assertTrue(liability["real_account"])
        self.assertTrue(liability["owned_by_user"])
        self.assertFalse(
            any(
                "liability-credit-c" in item["input"].values()
                for item in self.case["invalid_manual_inputs"]
            )
        )

        retry = self.case["idempotency"]["expected"]
        self.assertEqual(retry["new_candidate_count"], 0)
        self.assertEqual(retry["new_transaction_count"], 0)
        self.assertEqual(retry["new_posting_count"], 0)
        self.assertEqual(retry["new_evidence_link_count"], 0)
        self.assertEqual(retry["funding_effect_count_per_flow"], 1)
        manual = self.case["manual_create"]["expected"]
        self.assertEqual(retry["manual_state"]["transaction_id"], manual["transaction"]["id"])
        self.assertEqual(retry["manual_state"]["current_version_id"], manual["transaction"]["current_version_id"])
        self.assertEqual(retry["manual_state"]["posting_set_id"], manual["transaction"]["posting_set_id"])
        self.assertEqual(retry["manual_state"]["balances"], manual["balances"])
        self.assertEqual(retry["manual_state"]["statistics"], manual["statistics"])
        self.assertEqual(retry["manual_state"]["reconciliation"], manual["reconciliation"])
        self.assertEqual(retry["manual_state"]["source_refs"], [])
        self.assertEqual(retry["manual_state"]["evidence_refs"], manual["evidence_refs"])
        self.assertEqual(retry["manual_state"]["evidence_links"], [])

        merged = self.import_operations["merge-mirror-evidence"]["expected"]
        imported = retry["import_state"]
        self.assertEqual(imported["candidate_id"], retry["import_returned_candidate_id"])
        self.assertEqual(imported["candidate_status"], "confirmed")
        self.assertEqual(imported["transaction_id"], merged["merged_into_transaction_id"])
        self.assertEqual(imported["current_version_id"], merged["current_version_id"])
        self.assertEqual(imported["posting_set_id"], merged["effective_posting_set_id"])
        self.assertEqual(imported["posting_ids"], merged["posting_ids"])
        self.assertEqual(imported["balances"], merged["balances"])
        self.assertEqual(imported["statistics"], merged["statistics"])
        self.assertEqual(imported["reconciliation"], merged["reconciliation"])
        self.assertEqual(imported["source_refs"], merged["source_refs"])
        self.assertEqual(imported["evidence_refs"], merged["evidence_refs"])
        self.assertEqual(imported["evidence_links"], merged["evidence_links"])
        self.assertEqual(self.case["out_of_scope"]["combination_transfer"], "future_draft")
        self.assertEqual(self.case["out_of_scope"]["fee_refund"], "RG-07")
        self.assertEqual(self.case["out_of_scope"]["target_balance_adjustment"], "RG-09")
        self.assertIn("create_suspense_posting", self.case["forbidden_side_effects"])
        self.assertIn("auto_confirm_import_candidate", self.case["forbidden_side_effects"])


if __name__ == "__main__":
    unittest.main()
