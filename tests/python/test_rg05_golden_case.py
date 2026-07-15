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
RG05_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-05.json"


class RG05GoldenCaseTests(unittest.TestCase):
    MONEY_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
    MONEY_CONTAINERS = {
        "expected_balances",
        "balances",
        "balance_changes",
        "statistics",
        "category_consumption",
    }
    NON_MONEY_STATISTICS = {"day", "month", "budget"}
    MONEY_KEYS = {"amount", "payment_total", "allocation_total"}

    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG05_PATH)
        cls.manual = cls.case["manual_path"]
        cls.import_operations = {
            operation["id"]: operation
            for operation in cls.case["import_path"]["ordered_operations"]
        }

    def assert_two_decimal_money_contract(self, node, path="case"):
        if isinstance(node, dict):
            for key, value in node.items():
                child_path = f"{path}.{key}"
                if key in self.MONEY_KEYS or key.endswith("_amount"):
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
        return [
            *case["opening"]["transactions"],
            case["manual_path"]["expected"]["transaction"],
            {
                operation["id"]: operation
                for operation in case["import_path"]["ordered_operations"]
            }["explicitly-confirm-candidate"]["expected"]["transaction"],
        ]

    def canonical_manual_state(self):
        expected = self.manual["expected"]
        transaction = expected["transaction"]
        return {
            "candidate": self.manual["candidate"],
            "transaction": transaction,
            "current_version": {
                "id": transaction["current_version_id"],
                "transaction_id": transaction["id"],
                "posting_set_id": transaction["posting_set_id"],
                "status": "current",
            },
            "postings": transaction["postings"],
            "consumption_records": expected["consumption_records"],
            "association_group": expected["association_group"],
            "provenance": expected["provenance"],
            "balances": expected["balances"],
            "statistics": expected["statistics"],
            "category_consumption": expected["category_consumption"],
            "reconciliation": expected["reconciliation"],
            "source_refs": [],
            "evidence_refs": [],
            "financial_evidence_links": expected["financial_evidence_links"],
            "item_evidence_links": expected["item_evidence_links"],
            "item_evidence_completeness": expected["item_evidence_completeness"],
        }

    def canonical_import_state(self):
        pending = self.import_operations["import-merged-payment-facts"]["expected"]
        confirmed = self.import_operations["explicitly-confirm-candidate"]["expected"]
        merged = self.import_operations["merge-item-b-mirror-evidence"]["expected"]
        candidate = deepcopy(pending["candidate"])
        candidate["status"] = confirmed["candidate_status"]
        transaction = merged["transaction"]
        return {
            "candidate": candidate,
            "transaction": transaction,
            "current_version": {
                "id": transaction["current_version_id"],
                "transaction_id": transaction["id"],
                "posting_set_id": transaction["posting_set_id"],
                "status": "current",
            },
            "postings": transaction["postings"],
            "consumption_records": merged["consumption_records"],
            "association_group": merged["association_group"],
            "provenance": merged["provenance"],
            "balances": merged["balances"],
            "statistics": merged["statistics"],
            "category_consumption": confirmed["category_consumption"],
            "reconciliation": merged["reconciliation"],
            "source_refs": merged["source_refs"],
            "evidence_refs": merged["evidence_refs"],
            "financial_evidence_links": merged["financial_evidence_links"],
            "item_evidence_links": merged["item_evidence_links"],
            "item_evidence_completeness": merged["item_evidence_completeness"],
        }

    def assert_cny_v1_contract(self, case):
        def assert_nested_currency(node, path="case"):
            if isinstance(node, dict):
                if node.get("id") == "mixed-currencies":
                    self.assertEqual(node["input"]["currency"], "CNY")
                    self.assertEqual(
                        [item["currency"] for item in node["input"]["items"]],
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
        self.assertEqual(
            self.case["case"],
            {
                "id": "RG-05",
                "level": "core_required",
                "rule_version": 1,
                "timezone": "Asia/Shanghai",
                "currency": "CNY",
                "precision": 2,
                "ledger_id": "ledger-a",
            },
        )
        self.assert_two_decimal_money_contract(self.case)
        self.assert_cny_v1_contract(self.case)

        binary_float = deepcopy(self.case)
        binary_float["manual_path"]["input"]["total_amount"] = 100.0
        with self.assertRaises(AssertionError):
            self.assert_two_decimal_money_contract(binary_float)

        changed_currency = deepcopy(self.case)
        transaction = changed_currency["manual_path"]["expected"]["transaction"]
        for posting in transaction["postings"]:
            posting["currency"] = "USD"
        validate_transactions([transaction], changed_currency["catalog"]["accounts"])
        with self.assertRaises(AssertionError):
            self.assert_cny_v1_contract(changed_currency)

    def test_opening_and_manual_payment_balance_and_replay_exactly(self):
        expected = self.manual["expected"]
        transactions = [*self.case["opening"]["transactions"], expected["transaction"]]
        validate_transactions(transactions, self.case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), expected["balances"])

        self.assertEqual(
            self.manual["confirmation"],
            {"mode": "explicit_manual_save", "confirmed": True},
        )
        self.assertIsNone(self.manual["candidate"])
        self.assertEqual(
            [
                (posting["id"], posting["account_id"], posting["amount"])
                for posting in expected["transaction"]["postings"]
            ],
            [
                ("posting-expense-a-rg05-manual", "expense-account-daily", "40.00"),
                ("posting-expense-b-rg05-manual", "expense-account-service", "60.00"),
                ("posting-asset-rg05-manual", "asset-bank-a", "-100.00"),
            ],
        )
        self.assertEqual(expected["balances"], {"asset-bank-a": "900.00"})
        self.assertEqual(expected["effective_transaction_count"], 1)
        self.assertEqual(expected["asset_payment_posting_count"], 1)
        self.assertEqual(expected["cash_flow_count"], 1)

    def test_consumptions_stay_separate_while_group_binds_one_payment(self):
        expected = self.manual["expected"]
        self.assertEqual(
            expected["consumption_records"],
            [
                {
                    "id": "consumption-rg05-manual-a",
                    "amount": "40.00",
                    "currency": "CNY",
                    "category_id": "expense-category-daily",
                    "details": "项目 A",
                    "statistics_at": "2026-04-10T18:30:00+08:00",
                    "source_observed_at": "2026-04-08T10:00:00+08:00",
                    "expense_posting_id": "posting-expense-a-rg05-manual",
                },
                {
                    "id": "consumption-rg05-manual-b",
                    "amount": "60.00",
                    "currency": "CNY",
                    "category_id": "expense-category-service",
                    "details": "项目 B",
                    "statistics_at": "2026-04-10T18:30:00+08:00",
                    "source_observed_at": "2026-04-09T15:00:00+08:00",
                    "expense_posting_id": "posting-expense-b-rg05-manual",
                },
            ],
        )
        group = expected["association_group"]
        self.assertEqual(group["type"], "merged_payment")
        self.assertEqual(group["display_name"], "合并付款")
        self.assertTrue(group["system_managed"])
        self.assertEqual(group["formal_transaction_id"], "tx-merged-rg05-manual")
        self.assertEqual(group["asset_posting_id"], "posting-asset-rg05-manual")
        self.assertEqual(group["payment_total"], "100.00")
        self.assertEqual(
            group["item_allocations"],
            [
                {
                    "id": "allocation-rg05-manual-a",
                    "consumption_record_id": "consumption-rg05-manual-a",
                    "expense_posting_id": "posting-expense-a-rg05-manual",
                    "category_id": "expense-category-daily",
                    "amount": "40.00",
                    "currency": "CNY",
                },
                {
                    "id": "allocation-rg05-manual-b",
                    "consumption_record_id": "consumption-rg05-manual-b",
                    "expense_posting_id": "posting-expense-b-rg05-manual",
                    "category_id": "expense-category-service",
                    "amount": "60.00",
                    "currency": "CNY",
                },
            ],
        )
        self.assertFalse(group["generic_order_lifecycle"])
        self.assertEqual(expected["consumption_record_count"], 2)
        self.assertEqual(expected["association_group_count"], 1)
        self.assertEqual(expected["user_view"]["consumption_line_count"], 2)
        self.assertEqual(expected["user_view"]["shared_payment_relation_count"], 1)
        self.assertFalse(expected["user_view"]["merged_consumption_line"])

    def test_common_payment_time_statistics_and_explanatory_discount_are_exact(self):
        expected = self.manual["expected"]
        self.assertEqual(
            expected["statistics"],
            {
                "day": "2026-04-10",
                "month": "2026-04",
                "day_consumption": "100.00",
                "month_consumption": "100.00",
                "day_cash_outflow": "100.00",
                "month_cash_outflow": "100.00",
                "income": "0.00",
                "net_worth_change": "-100.00",
                "budget": "not_applicable",
            },
        )
        self.assertEqual(
            expected["category_consumption"],
            {"expense-category-daily": "40.00", "expense-category-service": "60.00"},
        )
        self.assertEqual(expected["common_statistics_at"], self.manual["input"]["payment_at"])
        self.assertFalse(expected["item_statistics_time_override_allowed"])
        self.assertEqual(
            expected["settlement_explanation"],
            {
                "original_amount": "112.00",
                "discount_amount": "12.00",
                "settled_amount": "100.00",
                "discount_accounting_effect_count": 0,
            },
        )

    def test_manual_financial_reconciliation_is_only_on_the_asset_posting(self):
        expected = self.manual["expected"]
        self.assertEqual(
            expected["reconciliation"],
            {
                "posting-expense-a-rg05-manual": "not_applicable",
                "posting-expense-b-rg05-manual": "not_applicable",
                "posting-asset-rg05-manual": "pending",
                "transaction": "pending",
            },
        )
        self.assertEqual(expected["item_evidence_completeness"], "none")
        self.assertEqual(expected["financial_evidence_links"], [])
        self.assertEqual(expected["item_evidence_links"], [])

    def test_complete_import_stays_pending_with_zero_formal_effects(self):
        operation = self.import_operations["import-merged-payment-facts"]
        expected = operation["expected"]
        self.assertEqual(operation["input"], {
            "bank_fact": {
                "source_id": "source-bank-debit-rg05",
                "evidence_id": "evidence-bank-debit-rg05",
                "observed_at": "2026-04-10T18:30:00+08:00",
                "details": "资产账户扣款",
                "amount": "-100.00",
                "currency": "CNY",
            },
            "item_facts": [
                {
                    "item_id": "item-rg05-imported-a",
                    "source_id": "source-item-a-rg05",
                    "evidence_id": "evidence-item-a-rg05",
                    "evidence_kind": "item_receipt",
                    "observed_at": "2026-04-08T10:00:00+08:00",
                    "details": "项目 A",
                    "amount": "40.00",
                    "currency": "CNY",
                    "suggested_category_id": "expense-category-daily",
                },
                {
                    "item_id": "item-rg05-imported-b",
                    "source_id": "source-item-b-rg05",
                    "evidence_id": "evidence-item-b-summary-rg05",
                    "evidence_kind": "item_summary",
                    "observed_at": "2026-04-09T15:00:00+08:00",
                    "details": "项目 B",
                    "amount": "60.00",
                    "currency": "CNY",
                    "suggested_category_id": "expense-category-service",
                },
            ],
        })
        self.assertEqual(expected["candidate"]["status"], "pending_confirmation")
        self.assertEqual(expected["candidate"]["bank_fact"], operation["input"]["bank_fact"])
        self.assertEqual(expected["candidate"]["item_facts"], operation["input"]["item_facts"])
        self.assertEqual(expected["candidate"]["source_refs"], [
            "source-bank-debit-rg05",
            "source-item-a-rg05",
            "source-item-b-rg05",
        ])
        self.assertEqual(expected["candidate"]["evidence_refs"], [
            "evidence-bank-debit-rg05",
            "evidence-item-a-rg05",
            "evidence-item-b-summary-rg05",
        ])
        self.assertEqual(expected["candidate"]["provenance"], {
            "source_fact_count": 3,
            "immutable_source_fields": [
                "source_id", "evidence_id", "observed_at", "details", "amount", "currency"
            ],
            "category_values_are_suggestions": True,
        })
        effects = expected["formal_effects"]
        self.assertEqual(effects["new_transaction_count"], 0)
        self.assertEqual(effects["new_posting_count"], 0)
        self.assertEqual(effects["new_version_count"], 0)
        self.assertEqual(effects["new_consumption_count"], 0)
        self.assertEqual(effects["new_item_allocation_count"], 0)
        self.assertEqual(effects["new_association_group_count"], 0)
        self.assertEqual(effects["balances"], self.case["opening"]["expected_balances"])
        self.assertEqual(effects["statistics"], {
            "consumption": "0.00",
            "cash_outflow": "0.00",
            "income": "0.00",
            "net_worth_change": "0.00",
        })
        self.assertEqual(effects["reconciliation_change_count"], 0)

    def test_explicit_confirmation_creates_one_payment_and_separate_items(self):
        operation = self.import_operations["explicitly-confirm-candidate"]
        expected = operation["expected"]
        transactions = [*self.case["opening"]["transactions"], expected["transaction"]]
        validate_transactions(transactions, self.case["catalog"]["accounts"])
        assert_expected_balances(replay_balances(transactions), expected["balances"])

        self.assertEqual(operation["input"], {
            "request_id": "request-rg05-confirm-candidate",
            "candidate_id": "candidate-rg05-imported",
            "confirmed": True,
            "create_formal_transaction": True,
            "funding_account_id": "asset-bank-a",
            "payment_at": "2026-04-10T18:30:00+08:00",
            "common_statistics_at": "2026-04-10T18:30:00+08:00",
            "items": [
                {
                    "item_id": "item-rg05-imported-a",
                    "category_id": "expense-category-daily",
                    "allocation_amount": "40.00",
                    "currency": "CNY",
                },
                {
                    "item_id": "item-rg05-imported-b",
                    "category_id": "expense-category-service",
                    "allocation_amount": "60.00",
                    "currency": "CNY",
                },
            ],
        })
        self.assertEqual(expected["candidate_status"], "confirmed")
        self.assertEqual(expected["effective_transaction_count"], 1)
        self.assertEqual(expected["asset_payment_posting_count"], 1)
        self.assertEqual(expected["cash_flow_count"], 1)
        self.assertEqual(expected["consumption_record_count"], 2)
        self.assertEqual(expected["item_allocation_count"], 2)
        self.assertEqual(expected["association_group_count"], 1)
        self.assertEqual(expected["balances"], {"asset-bank-a": "900.00"})
        self.assertEqual(expected["statistics"]["day_consumption"], "100.00")
        self.assertEqual(expected["statistics"]["day_cash_outflow"], "100.00")
        self.assertEqual(expected["common_statistics_at"], "2026-04-10T18:30:00+08:00")
        self.assertEqual(
            [(posting["account_id"], posting["amount"], posting["currency"])
             for posting in expected["transaction"]["postings"]],
            [
                ("expense-account-daily", "40.00", "CNY"),
                ("expense-account-service", "60.00", "CNY"),
                ("asset-bank-a", "-100.00", "CNY"),
            ],
        )
        group = expected["association_group"]
        self.assertEqual(
            {
                "id": group["id"],
                "type": group["type"],
                "display_name": group["display_name"],
                "system_managed": group["system_managed"],
                "formal_transaction_id": group["formal_transaction_id"],
                "asset_posting_id": group["asset_posting_id"],
                "payment_total": group["payment_total"],
                "currency": group["currency"],
                "generic_order_lifecycle": group["generic_order_lifecycle"],
            },
            {
                "id": "association-group-rg05-imported",
                "type": "merged_payment",
                "display_name": "合并付款",
                "system_managed": True,
                "formal_transaction_id": "tx-merged-rg05-imported",
                "asset_posting_id": "posting-asset-rg05-imported",
                "payment_total": "100.00",
                "currency": "CNY",
                "generic_order_lifecycle": False,
            },
        )
        source_facts = self.import_operations["import-merged-payment-facts"]["input"]
        confirmations = operation["input"]["items"]
        self.assertEqual(
            [record["id"] for record in expected["consumption_records"]],
            ["consumption-rg05-imported-a", "consumption-rg05-imported-b"],
        )
        self.assertEqual(
            [allocation["id"] for allocation in group["item_allocations"]],
            ["allocation-rg05-imported-a", "allocation-rg05-imported-b"],
        )
        for fact, confirmation, record, allocation, posting in zip(
            source_facts["item_facts"],
            confirmations,
            expected["consumption_records"],
            expected["association_group"]["item_allocations"],
            expected["transaction"]["postings"][:2],
        ):
            self.assertEqual(record["source_item_id"], fact["item_id"])
            self.assertEqual(record["source_id"], fact["source_id"])
            self.assertEqual(record["evidence_id"], fact["evidence_id"])
            self.assertEqual(record["source_observed_at"], fact["observed_at"])
            self.assertEqual(record["details"], fact["details"])
            self.assertEqual(record["amount"], fact["amount"])
            self.assertEqual(record["currency"], fact["currency"])
            self.assertEqual(record["category_id"], confirmation["category_id"])
            self.assertEqual(record["statistics_at"], operation["input"]["common_statistics_at"])
            self.assertEqual(allocation["source_item_id"], fact["item_id"])
            self.assertEqual(allocation["source_id"], fact["source_id"])
            self.assertEqual(allocation["evidence_id"], fact["evidence_id"])
            self.assertEqual(allocation["consumption_record_id"], record["id"])
            self.assertEqual(allocation["expense_posting_id"], posting["id"])
            self.assertEqual(allocation["category_id"], confirmation["category_id"])
            self.assertEqual(allocation["amount"], confirmation["allocation_amount"])
            self.assertEqual(allocation["currency"], confirmation["currency"])
        self.assertEqual(
            expected["transaction"]["postings"][2]["amount"],
            source_facts["bank_fact"]["amount"],
        )
        self.assertEqual(
            expected["transaction"]["occurred_at"], operation["input"]["payment_at"]
        )
        self.assertEqual(
            expected["provenance"]["source_refs"],
            expected["transaction"]["source_refs"],
        )
        self.assertEqual(
            expected["provenance"]["evidence_refs"],
            expected["transaction"]["evidence_refs"],
        )
        self.assertEqual(expected["provenance"]["bank_posting_binding"], {
            "source_id": "source-bank-debit-rg05",
            "evidence_id": "evidence-bank-debit-rg05",
            "posting_id": "posting-asset-rg05-imported",
        })
        self.assertEqual(expected["provenance"]["item_bindings"], [
            {
                "item_id": "item-rg05-imported-a",
                "source_id": "source-item-a-rg05",
                "evidence_id": "evidence-item-a-rg05",
                "consumption_record_id": "consumption-rg05-imported-a",
                "allocation_id": "allocation-rg05-imported-a",
            },
            {
                "item_id": "item-rg05-imported-b",
                "source_id": "source-item-b-rg05",
                "evidence_id": "evidence-item-b-summary-rg05",
                "consumption_record_id": "consumption-rg05-imported-b",
                "allocation_id": "allocation-rg05-imported-b",
            },
        ])

    def test_bank_and_item_evidence_have_distinct_exact_targets(self):
        expected = self.import_operations["explicitly-confirm-candidate"]["expected"]
        self.assertEqual(expected["financial_evidence_links"], [
            {
                "id": "match-bank-rg05",
                "evidence_id": "evidence-bank-debit-rg05",
                "posting_id": "posting-asset-rg05-imported",
                "status": "matched",
            }
        ])
        self.assertEqual(expected["item_evidence_links"], [
            {
                "id": "match-item-a-rg05",
                "evidence_id": "evidence-item-a-rg05",
                "item_allocation_id": "allocation-rg05-imported-a",
                "status": "matched",
            }
        ])
        self.assertEqual(expected["reconciliation"], {
            "posting-expense-a-rg05-imported": "not_applicable",
            "posting-expense-b-rg05-imported": "not_applicable",
            "posting-asset-rg05-imported": "matched",
            "transaction": "complete",
        })
        self.assertEqual(expected["item_evidence_completeness"], "partial")
        self.assertEqual(expected["matched_item_allocation_count"], 1)
        self.assertEqual(expected["required_item_allocation_count"], 2)

    def test_later_item_evidence_merges_without_duplicate_formal_effects(self):
        confirmed = self.import_operations["explicitly-confirm-candidate"]["expected"]
        merged = self.import_operations["merge-item-b-mirror-evidence"]["expected"]
        self.assertEqual(merged["merged_into_transaction_id"], confirmed["transaction"]["id"])
        self.assertEqual(merged["current_version_id"], confirmed["transaction"]["current_version_id"])
        self.assertEqual(merged["posting_set_id"], confirmed["transaction"]["posting_set_id"])
        self.assertEqual(merged["association_group_id"], confirmed["association_group"]["id"])
        self.assertEqual(merged["candidate_id"], "candidate-rg05-imported")
        self.assertEqual(merged["candidate_status"], "confirmed")
        self.assertEqual(merged["transaction"], confirmed["transaction"])
        self.assertEqual(merged["consumption_records"], confirmed["consumption_records"])
        self.assertEqual(merged["association_group"], confirmed["association_group"])
        self.assertEqual(merged["provenance"], confirmed["provenance"])
        self.assertEqual(merged["posting_ids"], [posting["id"] for posting in confirmed["transaction"]["postings"]])
        self.assertEqual(merged["consumption_ids"], [record["id"] for record in confirmed["consumption_records"]])
        self.assertEqual(merged["item_allocation_ids"], [
            allocation["id"] for allocation in confirmed["association_group"]["item_allocations"]
        ])
        for key in (
            "new_transaction_count",
            "new_posting_count",
            "new_version_count",
            "new_consumption_count",
            "new_item_allocation_count",
            "new_association_group_count",
            "new_cash_flow_count",
        ):
            self.assertEqual(merged[key], 0, key)
        self.assertEqual(merged["source_refs"], [
            "source-bank-debit-rg05",
            "source-item-a-rg05",
            "source-item-b-rg05",
            "source-item-b-receipt-rg05",
        ])
        self.assertEqual(merged["evidence_refs"], [
            "evidence-bank-debit-rg05",
            "evidence-item-a-rg05",
            "evidence-item-b-summary-rg05",
            "evidence-item-b-receipt-rg05",
        ])
        self.assertEqual(merged["financial_evidence_links"], [
            {
                "id": "match-bank-rg05",
                "evidence_id": "evidence-bank-debit-rg05",
                "posting_id": "posting-asset-rg05-imported",
                "status": "matched",
            }
        ])
        self.assertEqual(merged["reconciliation"], {
            "posting-expense-a-rg05-imported": "not_applicable",
            "posting-expense-b-rg05-imported": "not_applicable",
            "posting-asset-rg05-imported": "matched",
            "transaction": "complete",
        })
        self.assertEqual(merged["item_evidence_completeness"], "complete")
        self.assertEqual(merged["matched_item_allocation_count"], 2)
        self.assertEqual(merged["item_evidence_links"], [
            {
                "id": "match-item-a-rg05",
                "evidence_id": "evidence-item-a-rg05",
                "item_allocation_id": "allocation-rg05-imported-a",
                "status": "matched",
            },
            {
                "id": "match-item-b-rg05",
                "evidence_id": "evidence-item-b-receipt-rg05",
                "item_allocation_id": "allocation-rg05-imported-b",
                "status": "matched",
            },
        ])
        self.assertEqual(merged["balances"], confirmed["balances"])
        self.assertEqual(merged["statistics"], confirmed["statistics"])

    def test_incomplete_and_over_allocation_never_guess_or_balance(self):
        failures = {item["id"]: item for item in self.case["allocation_failures"]}
        self.assertEqual(set(failures), {"incomplete-allocation", "over-allocation"})
        self.assertEqual(failures["incomplete-allocation"]["expected"]["candidate_status"], "pending_confirmation")
        self.assertEqual(failures["incomplete-allocation"]["expected"]["allocation_gap_amount"], "10.00")
        self.assertEqual(failures["over-allocation"]["expected"]["candidate_status"], "conflict")
        self.assertEqual(failures["over-allocation"]["expected"]["over_allocation_amount"], "10.00")
        for failure in failures.values():
            expected = failure["expected"]
            for key in (
                "new_transaction_count",
                "new_posting_count",
                "new_version_count",
                "new_consumption_count",
                "new_item_allocation_count",
                "new_association_group_count",
                "new_cash_flow_count",
            ):
                self.assertEqual(expected[key], 0, key)
            self.assertEqual(expected["reconciliation_change_count"], 0)
            self.assertIsNone(expected["guessed_item_id"])
            self.assertIsNone(expected["balancing_account_id"])
            self.assertEqual(expected["hidden_posting_count"], 0)
            self.assertEqual(expected["balances"], self.case["opening"]["expected_balances"])
            self.assertEqual(expected["statistics"], {
                "consumption": "0.00",
                "cash_outflow": "0.00",
                "income": "0.00",
                "net_worth_change": "0.00",
            })

    def test_idempotent_retries_preserve_complete_states_and_all_stable_ids(self):
        idempotency = self.case["idempotency"]
        self.assertEqual(idempotency["retried_inputs"], [
            "request-rg05-manual",
            "source-bank-debit-rg05",
            "request-rg05-confirm-candidate",
            "evidence-item-b-receipt-rg05",
        ])
        expected = idempotency["expected"]
        for key in (
            "new_candidate_count",
            "new_transaction_count",
            "new_posting_count",
            "new_version_count",
            "new_consumption_count",
            "new_item_allocation_count",
            "new_association_group_count",
            "new_financial_evidence_link_count",
            "new_item_evidence_link_count",
            "duplicate_cash_flow_count",
        ):
            self.assertEqual(expected[key], 0, key)
        self.assertEqual(expected["manual_state"], self.canonical_manual_state())
        self.assertEqual(expected["import_state"], self.canonical_import_state())
        self.assertEqual(expected["manual_returned_ids"], {
            "transaction_id": "tx-merged-rg05-manual",
            "consumption_ids": ["consumption-rg05-manual-a", "consumption-rg05-manual-b"],
            "item_allocation_ids": ["allocation-rg05-manual-a", "allocation-rg05-manual-b"],
            "association_group_id": "association-group-rg05-manual",
            "financial_evidence_link_ids": [],
            "item_evidence_link_ids": [],
        })
        self.assertEqual(expected["import_returned_ids"], {
            "candidate_id": "candidate-rg05-imported",
            "transaction_id": "tx-merged-rg05-imported",
            "consumption_ids": ["consumption-rg05-imported-a", "consumption-rg05-imported-b"],
            "item_allocation_ids": ["allocation-rg05-imported-a", "allocation-rg05-imported-b"],
            "association_group_id": "association-group-rg05-imported",
            "financial_evidence_link_ids": ["match-bank-rg05"],
            "item_evidence_link_ids": ["match-item-a-rg05", "match-item-b-rg05"],
        })

    def test_invalid_inputs_have_exact_reasons_and_zero_formal_effects(self):
        invalid = {item["id"]: item for item in self.case["invalid_manual_inputs"]}
        accounts = {account["id"]: account for account in self.case["catalog"]["accounts"]}
        categories = {category["id"]: category for category in self.case["catalog"]["categories"]}
        self.assertEqual(
            {key: accounts["liability-credit-a"][key] for key in ("kind", "real_account", "owned_by_user")},
            {"kind": "liability", "real_account": True, "owned_by_user": True},
        )
        self.assertEqual(
            {key: accounts["expense-account-daily"][key] for key in ("kind", "real_account")},
            {"kind": "expense", "real_account": False},
        )
        self.assertEqual(
            {key: accounts["asset-external-x"][key] for key in ("kind", "real_account", "owned_by_user")},
            {"kind": "asset", "real_account": True, "owned_by_user": False},
        )
        self.assertIsNone(categories["expense-category-living"]["parent_id"])
        self.assertIsNone(categories["expense-category-living"]["posting_account_id"])
        self.assertFalse(categories["expense-category-inactive"]["active"])
        self.assertEqual(categories["income-category-other"]["kind"], "income")
        self.assertEqual(
            {item_id: (item["expected"]["field"], item["expected"]["reason"]) for item_id, item in invalid.items()},
            {
                "missing-secondary-category": ("items", "secondary_category_required"),
                "primary-category": ("items", "secondary_category_required"),
                "inactive-secondary-category": ("items", "category_inactive"),
                "wrong-kind-income-category": ("items", "expense_category_required"),
                "zero-total": ("total_amount", "must_be_positive"),
                "negative-total": ("total_amount", "must_be_positive"),
                "zero-item-amount": ("items", "item_amount_must_be_positive"),
                "negative-item-amount": ("items", "item_amount_must_be_positive"),
                "allocation-total-mismatch": ("items", "allocation_total_must_equal_payment"),
                "duplicate-item-ids": ("items", "duplicate_item_id"),
                "unknown-funding-account": ("funding_account_id", "unknown_real_account"),
                "known-nonfinancial-account": ("funding_account_id", "real_financial_account_required"),
                "known-owned-liability-account": ("funding_account_id", "asset_account_required"),
                "known-non-owned-account": ("funding_account_id", "owned_account_required"),
                "mixed-currencies": ("items", "single_currency_required"),
            },
        )
        self.assertEqual(len(invalid), 15)
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                expected = item["expected"]
                self.assertFalse(expected["accepted"])
                self.assertTrue(expected["state_unchanged"])
                self.assertEqual(expected["new_transaction_count"], 0)
                self.assertEqual(expected["new_posting_count"], 0)
                self.assertEqual(expected["new_version_count"], 0)
                self.assertEqual(expected["new_consumption_count"], 0)
                self.assertEqual(expected["new_item_allocation_count"], 0)
                self.assertEqual(expected["new_association_group_count"], 0)
                self.assertEqual(expected["reconciliation_change_count"], 0)
                self.assertEqual(expected["balances"], self.case["opening"]["expected_balances"])
                self.assertEqual(expected["statistics"], {
                    "consumption": "0.00",
                    "cash_outflow": "0.00",
                    "income": "0.00",
                    "net_worth_change": "0.00",
                })

    def test_scope_and_forbidden_effects_are_frozen(self):
        self.assertEqual(self.case["out_of_scope"], {
            "one_item_multiple_funding_legs": "RG-04",
            "staged_or_unpaid_amounts": "RG-06",
            "refunds": "RG-07",
            "later_corrections": "RG-12",
            "discounts": "explanatory_only",
            "many_items_many_funding_legs": "future_version",
        })
        forbidden = set(self.case["forbidden_side_effects"])
        self.assertTrue({
            "create_second_asset_debit",
            "create_hidden_clearing_account",
            "create_hidden_balancing_posting",
            "merge_consumption_records",
            "create_generic_order_lifecycle",
            "auto_confirm_import_candidate",
            "guess_allocation_gap",
            "duplicate_transaction",
            "duplicate_posting",
            "duplicate_consumption",
            "duplicate_item_allocation",
            "duplicate_association_group",
            "duplicate_cash_flow",
            "create_discount_posting",
            "item_evidence_changes_financial_reconciliation",
            "invoke_network",
            "invoke_sync",
            "invoke_intelligent_suggestion",
        }.issubset(forbidden))


if __name__ == "__main__":
    unittest.main()
