from __future__ import annotations

from decimal import Decimal
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
RG06_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-06.json"
TWO_DECIMAL = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
ZERO_STATISTICS = {
    "consumption": "0.00",
    "cash_outflow": "0.00",
    "income": "0.00",
    "net_worth_change": "0.00",
}
ZERO_EFFECT_COUNTS = {
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_version_count": 0,
    "new_payment_count": 0,
    "new_association_group_count": 0,
    "new_history_count": 0,
    "new_consumption_count": 0,
    "new_cash_flow_count": 0,
    "reconciliation_change_count": 0,
}


class RG06GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG06_PATH)
        cls.manual_operations = {
            operation["id"]: operation
            for operation in cls.case["manual_path"]["ordered_operations"]
        }
        cls.import_operations = {
            operation["id"]: operation
            for operation in cls.case["import_path"]["ordered_operations"]
        }

    def assert_zero_effects(self, effects, baseline):
        self.assertEqual(
            {key: effects[key] for key in ZERO_EFFECT_COUNTS},
            ZERO_EFFECT_COUNTS,
        )
        self.assertEqual(effects["balances"], baseline["balances"])
        self.assertEqual(effects["statistics"], baseline["statistics"])
        self.assertEqual(effects["group_state"], baseline["group_state"])
        self.assertEqual(effects["history_ids"], baseline["history_ids"])
        self.assertEqual(effects["reconciliation"], baseline["reconciliation"])

    def assert_group_amounts(self, group, total, paid, due):
        self.assertEqual(group["currency"], "CNY")
        self.assertEqual(group["total_amount"], total)
        self.assertEqual(group["paid_amount"], paid)
        self.assertEqual(group["due_amount"], due)

    def assert_derived_payment_progress(self, state):
        paid = Decimal(state["paid_amount"])
        due = Decimal(state["due_amount"])
        if paid == Decimal("0.00"):
            expected = "unpaid"
        elif due == Decimal("0.00"):
            expected = "paid_in_full"
        else:
            expected = "partially_paid"
        self.assertEqual(state["payment_progress"], expected)

    def assert_amount_strings(self, value, path="root"):
        money_keys = {
            "amount",
            "total_amount",
            "paid_amount",
            "due_amount",
            "day_consumption",
            "month_consumption",
            "day_cash_outflow",
            "month_cash_outflow",
            "consumption",
            "cash_outflow",
            "income",
            "net_worth_change",
            "cumulative_consumption",
            "cumulative_cash_outflow",
            "cumulative_income",
            "cumulative_net_worth_change",
        }
        if isinstance(value, dict):
            for key, item in value.items():
                child_path = f"{path}.{key}"
                if key in money_keys:
                    self.assertIsInstance(item, str, child_path)
                    self.assertRegex(item, TWO_DECIMAL, child_path)
                self.assert_amount_strings(item, child_path)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                self.assert_amount_strings(item, f"{path}[{index}]")

    def test_fixture_enforces_schema_cny_exact_money_and_catalog_boundaries(self):
        validate_case_envelope(self.case)
        self.assertEqual(
            self.case["case"],
            {
                "id": "RG-06",
                "level": "core_required",
                "rule_version": 1,
                "timezone": "Asia/Shanghai",
                "currency": "CNY",
                "precision": 2,
                "ledger_id": "ledger-a",
            },
        )
        self.assert_amount_strings(self.case)

        accounts = {
            account["id"]: account for account in self.case["catalog"]["accounts"]
        }
        categories = {
            category["id"]: category
            for category in self.case["catalog"]["categories"]
        }
        self.assertEqual(
            {
                key: accounts["asset-bank-a"][key]
                for key in ("kind", "real_account", "owned_by_user")
            },
            {"kind": "asset", "real_account": True, "owned_by_user": True},
        )
        self.assertFalse(accounts["expense-account-service"]["real_account"])
        self.assertFalse(accounts["asset-external-x"]["owned_by_user"])
        self.assertEqual(accounts["liability-credit-a"]["kind"], "liability")
        self.assertIsNone(categories["expense-category-service-parent"]["parent_id"])
        self.assertIsNone(
            categories["expense-category-service-parent"]["posting_account_id"]
        )
        self.assertFalse(categories["expense-category-inactive"]["active"])
        self.assertEqual(categories["income-category-other"]["kind"], "income")

    def test_opening_and_both_payments_are_balanced_and_replay_exactly(self):
        opening = self.case["opening"]["transactions"]
        deposit = self.manual_operations["save-deposit"]["expected"]["transaction"]
        final = self.manual_operations["save-final"]["expected"]["transaction"]
        validate_transactions(
            [*opening, deposit, final], self.case["catalog"]["accounts"]
        )

        after_deposit = replay_balances([*opening, deposit])
        self.assertEqual(after_deposit["asset-bank-a"], Decimal("920.00"))
        assert_expected_balances(
            after_deposit,
            self.manual_operations["save-deposit"]["expected"]["balances"],
        )

        after_final = replay_balances([*opening, deposit, final])
        self.assertEqual(after_final["asset-bank-a"], Decimal("700.00"))
        assert_expected_balances(
            after_final,
            self.manual_operations["save-final"]["expected"]["balances"],
        )
        self.assertNotEqual(deposit["id"], final["id"])
        self.assertEqual(
            [len(deposit["postings"]), len(final["postings"])], [2, 2]
        )

    def test_group_creation_is_nonfinancial_and_has_stable_specific_identity(self):
        operation = self.manual_operations["create-group"]
        expected = operation["expected"]
        group = expected["group"]
        self.assertEqual(operation["input"]["total_amount"], "300.00")
        self.assertEqual(
            {
                "id": group["id"],
                "type": group["type"],
                "display_name": group["display_name"],
                "system_managed": group["system_managed"],
                "category_id": group["category_id"],
                "generic_order_lifecycle": group["generic_order_lifecycle"],
            },
            {
                "id": "association-group-rg06-manual",
                "type": "staged_payment",
                "display_name": "分阶段付款",
                "system_managed": True,
                "category_id": "expense-category-service",
                "generic_order_lifecycle": False,
            },
        )
        self.assert_group_amounts(group, "300.00", "0.00", "300.00")
        self.assertEqual(group["payments"], [])
        self.assertEqual(group["payment_progress"], "unpaid")
        self.assertEqual(group["fulfillment_status"], "in_progress")
        self.assertEqual([item["event"] for item in group["state_history"]], ["group_created"])
        effects = expected["formal_effects"]
        self.assertEqual(effects["new_association_group_count"], 1)
        for key in ZERO_EFFECT_COUNTS.keys() - {"new_association_group_count"}:
            self.assertEqual(effects[key], 0, key)
        self.assertEqual(effects["balances"], {"asset-bank-a": "1000.00"})
        self.assertEqual(effects["statistics"], ZERO_STATISTICS)

    def test_payment_progress_is_derived_for_every_group_state_and_history_entry(self):
        states = []

        def collect(value):
            if isinstance(value, dict):
                if {"paid_amount", "due_amount", "payment_progress"}.issubset(value):
                    states.append(value)
                for child in value.values():
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)

        collect(self.case)
        self.assertGreater(len(states), 30)
        for state in states:
            with self.subTest(state=state):
                self.assert_derived_payment_progress(state)

    def test_deposit_has_its_own_expense_cash_flow_and_unpaid_due_is_display_only(self):
        expected = self.manual_operations["save-deposit"]["expected"]
        transaction = expected["transaction"]
        self.assertEqual(transaction["occurred_at"], "2026-04-28T10:00:00+08:00")
        self.assertEqual(transaction["statistics_at"], transaction["occurred_at"])
        self.assertEqual(
            [
                (posting["account_id"], posting["amount"], posting["currency"])
                for posting in transaction["postings"]
            ],
            [
                ("expense-account-service", "80.00", "CNY"),
                ("asset-bank-a", "-80.00", "CNY"),
            ],
        )
        self.assertEqual(
            expected["operation_statistics"],
            {
                "day": "2026-04-28",
                "month": "2026-04",
                "day_consumption": "80.00",
                "month_consumption": "80.00",
                "day_cash_outflow": "80.00",
                "month_cash_outflow": "80.00",
                "income": "0.00",
                "net_worth_change": "-80.00",
                "budget": "not_applicable",
            },
        )
        self.assert_group_amounts(expected["group"], "300.00", "80.00", "220.00")
        self.assertEqual(expected["unpaid_due_formal_effects"], ZERO_STATISTICS)
        self.assertEqual(expected["unpaid_due_balance_effect"], "0.00")
        self.assertEqual(expected["unpaid_due_posting_count"], 0)
        self.assertIsNone(expected["payable_account_id"])
        self.assertIsNone(expected["liability_account_id"])

    def test_fulfillment_can_be_marked_while_due_and_state_transition_is_zero_effect(self):
        before = self.manual_operations["save-deposit"]["expected"]
        operation = self.manual_operations["mark-fulfilled"]
        expected = operation["expected"]
        group = expected["group"]
        self.assert_group_amounts(group, "300.00", "80.00", "220.00")
        self.assertEqual(group["payment_progress"], "partially_paid")
        self.assertEqual(group["fulfillment_status"], "fulfilled")
        self.assertEqual(
            group["user_labels"],
            {"fulfillment": "履约：已交付", "payment": "付款：待付 220.00"},
        )
        self.assertEqual(group["payments"], before["group"]["payments"])
        self.assertEqual(expected["new_history_count"], 1)
        self.assertEqual(group["state_history"][-1]["event"], "fulfillment_changed")
        self.assertEqual(group["state_history"][-1]["state_transition_effect_count"], 0)
        self.assert_zero_effects(
            expected["formal_effects"], self.case["baselines"]["after_deposit_manual"]
        )

    def test_final_payment_is_independent_and_completion_never_duplicates_money(self):
        expected = self.manual_operations["save-final"]["expected"]
        transaction = expected["transaction"]
        self.assertEqual(transaction["occurred_at"], "2026-05-03T16:30:00+08:00")
        self.assertEqual(transaction["statistics_at"], transaction["occurred_at"])
        self.assertEqual(
            [posting["amount"] for posting in transaction["postings"]],
            ["220.00", "-220.00"],
        )
        self.assertEqual(expected["operation_statistics"]["day_consumption"], "220.00")
        self.assertEqual(expected["operation_statistics"]["day_cash_outflow"], "220.00")
        self.assertEqual(
            expected["cumulative_statistics"],
            {
                "cumulative_consumption": "300.00",
                "cumulative_cash_outflow": "300.00",
                "cumulative_income": "0.00",
                "cumulative_net_worth_change": "-300.00",
            },
        )
        self.assert_group_amounts(expected["group"], "300.00", "300.00", "0.00")
        self.assertEqual(expected["group"]["payment_progress"], "paid_in_full")
        self.assertEqual(expected["effective_payment_transaction_count"], 2)
        self.assertEqual(expected["consumption_effect_count"], 2)
        self.assertEqual(expected["cash_flow_effect_count"], 2)

        completion = self.manual_operations["confirm-completion"]["expected"]
        self.assertEqual(completion["new_history_count"], 1)
        self.assertEqual(completion["group"]["state_history"][-1]["event"], "completion_confirmed")
        self.assert_zero_effects(
            completion["formal_effects"], self.case["baselines"]["after_final_manual"]
        )

    def test_group_preserves_exact_payment_and_history_identities(self):
        group = self.manual_operations["confirm-completion"]["expected"]["group"]
        self.assertEqual(
            group["payments"],
            [
                {
                    "id": "payment-rg06-manual-deposit",
                    "role": "deposit",
                    "amount": "80.00",
                    "currency": "CNY",
                    "actual_payment_at": "2026-04-28T10:00:00+08:00",
                    "statistics_at": "2026-04-28T10:00:00+08:00",
                    "source_payment_at": None,
                    "funding_account_id": "asset-bank-a",
                    "transaction_id": "tx-rg06-manual-deposit",
                    "expense_posting_id": "posting-expense-rg06-manual-deposit",
                    "asset_posting_id": "posting-asset-rg06-manual-deposit",
                },
                {
                    "id": "payment-rg06-manual-final",
                    "role": "final",
                    "amount": "220.00",
                    "currency": "CNY",
                    "actual_payment_at": "2026-05-03T16:30:00+08:00",
                    "statistics_at": "2026-05-03T16:30:00+08:00",
                    "source_payment_at": None,
                    "funding_account_id": "asset-bank-a",
                    "transaction_id": "tx-rg06-manual-final",
                    "expense_posting_id": "posting-expense-rg06-manual-final",
                    "asset_posting_id": "posting-asset-rg06-manual-final",
                },
            ],
        )
        self.assertEqual(
            [entry["event"] for entry in group["state_history"]],
            [
                "group_created",
                "payment_confirmed",
                "fulfillment_changed",
                "payment_confirmed",
                "completion_confirmed",
            ],
        )
        self.assertEqual(
            [entry["id"] for entry in group["state_history"]],
            [
                "history-rg06-manual-created",
                "history-rg06-manual-deposit",
                "history-rg06-manual-fulfilled",
                "history-rg06-manual-final",
                "history-rg06-manual-completed",
            ],
        )
        self.assertTrue(
            all(entry["state_transition_effect_count"] == 0 for entry in group["state_history"])
        )

    def test_imports_and_ambiguous_role_stay_pending_with_exact_zero_effects(self):
        baseline = self.case["baselines"]["group_only_import"]
        for operation_id in ("import-deposit", "import-ambiguous-role"):
            operation = self.import_operations[operation_id]
            expected = operation["expected"]
            self.assertEqual(expected["candidate"]["status"], "pending_confirmation")
            self.assert_zero_effects(expected["formal_effects"], baseline)

        ambiguous = self.import_operations["import-ambiguous-role"]["expected"]["candidate"]
        self.assertIsNone(ambiguous["payment_role"])
        self.assertIsNone(ambiguous["guessed_payment_role"])
        self.assertIn("payment_role", ambiguous["requires_confirmation"])
        self.assertEqual(ambiguous["amount"], "220.00")

        after_deposit = self.case["baselines"]["after_deposit_import"]
        final_import = self.import_operations["import-final"]["expected"]
        self.assertEqual(final_import["candidate"]["status"], "pending_confirmation")
        self.assert_zero_effects(final_import["formal_effects"], after_deposit)

    def test_import_confirmation_requires_exact_group_role_and_preserves_source_time(self):
        deposit_operation = self.import_operations["confirm-deposit"]
        final_operation = self.import_operations["confirm-final"]
        for operation, role, payment_id, transaction_id, posting_id in (
            (
                deposit_operation,
                "deposit",
                "payment-rg06-import-deposit",
                "tx-rg06-import-deposit",
                "posting-asset-rg06-import-deposit",
            ),
            (
                final_operation,
                "final",
                "payment-rg06-import-final",
                "tx-rg06-import-final",
                "posting-asset-rg06-import-final",
            ),
        ):
            request = operation["input"]
            expected = operation["expected"]
            self.assertEqual(request["association_group_id"], "association-group-rg06-import")
            self.assertEqual(request["payment_role"], role)
            self.assertTrue(request["exact_binding_confirmed"])
            self.assertEqual(expected["candidate_status"], "confirmed")
            self.assertEqual(expected["payment"]["id"], payment_id)
            self.assertEqual(expected["payment"]["role"], role)
            self.assertEqual(expected["payment"]["transaction_id"], transaction_id)
            self.assertEqual(expected["payment"]["asset_posting_id"], posting_id)
            self.assertEqual(
                expected["payment"]["source_payment_at"],
                expected["transaction"]["source_payment_at"],
            )
            self.assertEqual(
                expected["payment"]["statistics_at"],
                expected["payment"]["actual_payment_at"],
            )
            self.assertEqual(
                expected["transaction"]["statistics_at"],
                expected["transaction"]["source_payment_at"],
            )

    def test_imported_payments_are_balanced_and_replay_to_the_same_exact_balances(self):
        opening = self.case["opening"]["transactions"]
        deposit = self.import_operations["confirm-deposit"]["expected"]["transaction"]
        final = self.import_operations["confirm-final"]["expected"]["transaction"]
        validate_transactions(
            [*opening, deposit, final], self.case["catalog"]["accounts"]
        )
        after_deposit = replay_balances([*opening, deposit])
        self.assertEqual(after_deposit["asset-bank-a"], Decimal("920.00"))
        assert_expected_balances(
            after_deposit,
            self.import_operations["confirm-deposit"]["expected"]["balances"],
        )
        after_final = replay_balances([*opening, deposit, final])
        self.assertEqual(after_final["asset-bank-a"], Decimal("700.00"))
        assert_expected_balances(
            after_final,
            self.import_operations["confirm-final"]["expected"]["balances"],
        )

    def test_each_bank_evidence_targets_its_asset_posting_and_reconciliation_is_independent(self):
        deposit_link = self.manual_operations["link-deposit-evidence"]["expected"]
        final_link = self.manual_operations["link-final-evidence"]["expected"]
        self.assertEqual(
            deposit_link["evidence_links"],
            [
                {
                    "id": "match-rg06-manual-deposit",
                    "evidence_id": "evidence-rg06-manual-deposit",
                    "payment_id": "payment-rg06-manual-deposit",
                    "posting_id": "posting-asset-rg06-manual-deposit",
                    "status": "matched",
                    "source_id": "source-rg06-manual-deposit-bank",
                    "mirror_of_evidence_id": None,
                    "merged_into_evidence_link_id": None,
                }
            ],
        )
        self.assertEqual(deposit_link["reconciliation"]["group"], "partial")
        self.assertEqual(
            deposit_link["reconciliation"]["transactions"],
            {
                "tx-rg06-manual-deposit": "complete",
                "tx-rg06-manual-final": "pending",
            },
        )
        self.assertEqual(len(final_link["evidence_links"]), 2)
        self.assertEqual(final_link["reconciliation"]["group"], "complete")
        self.assertEqual(
            {
                link["posting_id"] for link in final_link["evidence_links"]
            },
            {
                "posting-asset-rg06-manual-deposit",
                "posting-asset-rg06-manual-final",
            },
        )
        effects = final_link["formal_effects"]
        for key in ZERO_EFFECT_COUNTS.keys() - {"reconciliation_change_count"}:
            self.assertEqual(effects[key], 0, key)
        self.assertEqual(effects["reconciliation_change_count"], 1)
        self.assertEqual(effects["balances"], {"asset-bank-a": "700.00"})
        self.assertEqual(
            effects["statistics"],
            self.case["baselines"]["after_final_manual"]["statistics"],
        )

    def test_mirror_evidence_merges_into_existing_payment_without_duplicates(self):
        operation = self.import_operations["merge-final-mirror-evidence"]
        expected = operation["expected"]
        canonical = self.case["import_path"]["canonical_final_state"]
        self.assertEqual(expected["merged_into_payment_id"], "payment-rg06-import-final")
        self.assertEqual(expected["merged_into_transaction_id"], "tx-rg06-import-final")
        self.assertEqual(expected["target_posting_id"], "posting-asset-rg06-import-final")
        self.assertEqual(expected["state"], canonical)
        for key in (
            "new_transaction_count",
            "new_posting_count",
            "new_version_count",
            "new_payment_count",
            "new_consumption_count",
            "new_association_group_count",
            "new_cash_flow_count",
        ):
            self.assertEqual(expected[key], 0, key)
        self.assertEqual(len(canonical["transactions"]), 2)
        self.assertEqual(len(canonical["payments"]), 2)
        self.assertEqual(canonical["reconciliation"]["group"], "complete")

    def test_idempotent_retries_preserve_both_full_canonical_states(self):
        idempotency = self.case["idempotency"]
        self.assertEqual(
            idempotency["retried_inputs"],
            [
                "request-rg06-manual-group",
                "request-rg06-manual-deposit",
                "request-rg06-manual-fulfilled",
                "request-rg06-manual-final",
                "request-rg06-manual-completion",
                "source-rg06-import-deposit",
                "request-rg06-confirm-deposit",
                "source-rg06-import-final",
                "request-rg06-confirm-final",
                "evidence-rg06-import-final-mirror",
            ],
        )
        expected = idempotency["expected"]
        self.assertEqual(
            {key: expected[key] for key in ZERO_EFFECT_COUNTS}, ZERO_EFFECT_COUNTS
        )
        self.assertEqual(expected["new_candidate_count"], 0)
        self.assertEqual(expected["new_evidence_link_count"], 0)
        self.assertEqual(expected["manual_state"], self.case["manual_path"]["canonical_final_state"])
        self.assertEqual(expected["import_state"], self.case["import_path"]["canonical_final_state"])
        for state in (expected["manual_state"], expected["import_state"]):
            self.assertEqual(len(state["transactions"]), 2)
            self.assertEqual(len(state["versions"]), 2)
            self.assertEqual(len(state["postings"]), 4)
            self.assertEqual(len(state["payments"]), 2)
            self.assertIn("state_history", state["group"])
            self.assertIn("evidence_links", state)
            for transaction in state["transactions"]:
                self.assertIn("postings", transaction)
                self.assertEqual(len(transaction["postings"]), 2)
                self.assertIn("current_version_id", transaction)
                self.assertIn("posting_set_id", transaction)
        self.assertEqual(expected["manual_state"]["candidates"], [])
        imported = expected["import_state"]
        self.assertEqual(len(imported["candidates"]), 3)
        self.assertEqual(len(imported["source_records"]), 4)
        for candidate in imported["candidates"]:
            self.assertIn("source_fact", candidate)
            self.assertIn("immutable_source_fields", candidate)
            self.assertIn("confidence", candidate)
            self.assertIn("confirmation_provenance", candidate)
        for link in imported["evidence_links"]:
            self.assertIn("source_id", link)
            self.assertIn("mirror_of_evidence_id", link)
        self.assertEqual(
            expected["manual_returned_ids"],
            {
                "association_group_id": "association-group-rg06-manual",
                "payment_ids": [
                    "payment-rg06-manual-deposit",
                    "payment-rg06-manual-final",
                ],
                "transaction_ids": [
                    "tx-rg06-manual-deposit",
                    "tx-rg06-manual-final",
                ],
            },
        )

    def test_canonical_snapshots_preserve_exact_objects_and_provenance(self):
        manual = self.case["manual_path"]["canonical_final_state"]
        imported = self.case["import_path"]["canonical_final_state"]

        self.assertEqual(
            manual["transactions"],
            [
                self.manual_operations["save-deposit"]["expected"]["transaction"],
                self.manual_operations["save-final"]["expected"]["transaction"],
            ],
        )
        self.assertEqual(
            manual["postings"],
            [posting for transaction in manual["transactions"] for posting in transaction["postings"]],
        )
        self.assertEqual(manual["payments"], manual["group"]["payments"])
        self.assertEqual(
            manual["versions"],
            [
                {"id": "version-rg06-manual-deposit-v1", "transaction_id": "tx-rg06-manual-deposit", "posting_set_id": "posting-set-rg06-manual-deposit", "status": "current"},
                {"id": "version-rg06-manual-final-v1", "transaction_id": "tx-rg06-manual-final", "posting_set_id": "posting-set-rg06-manual-final", "status": "current"},
            ],
        )
        self.assertEqual(
            [record["id"] for record in manual["source_records"]],
            ["source-rg06-manual-deposit-bank", "source-rg06-manual-final-bank"],
        )

        self.assertEqual(
            imported["transactions"],
            [
                self.import_operations["confirm-deposit"]["expected"]["transaction"],
                self.import_operations["confirm-final"]["expected"]["transaction"],
            ],
        )
        self.assertEqual(
            imported["postings"],
            [posting for transaction in imported["transactions"] for posting in transaction["postings"]],
        )
        self.assertEqual(
            imported["versions"],
            [
                {"id": "version-rg06-import-deposit-v1", "transaction_id": "tx-rg06-import-deposit", "posting_set_id": "posting-set-rg06-import-deposit", "status": "current"},
                {"id": "version-rg06-import-final-v1", "transaction_id": "tx-rg06-import-final", "posting_set_id": "posting-set-rg06-import-final", "status": "current"},
            ],
        )
        self.assertEqual(imported["payments"], imported["group"]["payments"])

        candidates = {candidate["id"]: candidate for candidate in imported["candidates"]}
        self.assertEqual(
            {
                candidate_id: (
                    candidate["status"],
                    candidate["payment_role"],
                    candidate["confidence"],
                    candidate["source_fact"],
                )
                for candidate_id, candidate in candidates.items()
            },
            {
                "candidate-rg06-import-deposit": ("confirmed", "deposit", "1.00", {"source_id": "source-rg06-import-deposit", "evidence_id": "evidence-rg06-import-deposit", "source_payment_at": "2026-04-28T10:00:00+08:00", "amount": "80.00", "currency": "CNY"}),
                "candidate-rg06-import-ambiguous": ("pending_confirmation", None, "0.50", {"source_id": "source-rg06-import-ambiguous", "evidence_id": "evidence-rg06-import-ambiguous", "source_payment_at": "2026-04-25T15:00:00+08:00", "amount": "220.00", "currency": "CNY"}),
                "candidate-rg06-import-final": ("confirmed", "final", "1.00", {"source_id": "source-rg06-import-final", "evidence_id": "evidence-rg06-import-final", "source_payment_at": "2026-05-03T16:30:00+08:00", "amount": "220.00", "currency": "CNY"}),
            },
        )
        self.assertIsNone(candidates["candidate-rg06-import-ambiguous"]["confirmation_provenance"])
        self.assertEqual(
            candidates["candidate-rg06-import-deposit"]["confirmation_provenance"],
            {"request_id": "request-rg06-confirm-deposit", "association_group_id": "association-group-rg06-import", "payment_role": "deposit", "category_id": "expense-category-service", "funding_account_id": "asset-bank-a", "exact_binding_confirmed": True, "confirmed_at": "2026-04-28T10:05:00+08:00"},
        )
        self.assertEqual(
            candidates["candidate-rg06-import-final"]["confirmation_provenance"],
            {"request_id": "request-rg06-confirm-final", "association_group_id": "association-group-rg06-import", "payment_role": "final", "category_id": "expense-category-service", "funding_account_id": "asset-bank-a", "exact_binding_confirmed": True, "confirmed_at": "2026-05-03T16:35:00+08:00"},
        )
        self.assertEqual(
            [(record["id"], record["mirror_of_source_id"]) for record in imported["source_records"]],
            [
                ("source-rg06-import-deposit", None),
                ("source-rg06-import-ambiguous", None),
                ("source-rg06-import-final", None),
                ("source-rg06-import-final-mirror", "source-rg06-import-final"),
            ],
        )
        self.assertEqual(
            [(link["source_id"], link["mirror_of_evidence_id"], link["merged_into_evidence_link_id"]) for link in imported["evidence_links"]],
            [
                ("source-rg06-import-deposit", None, None),
                ("source-rg06-import-final", None, None),
                ("source-rg06-import-final-mirror", "evidence-rg06-import-final", "match-rg06-import-final"),
            ],
        )

    def test_import_state_history_is_complete_ordered_and_zero_effect(self):
        history = self.case["import_path"]["canonical_final_state"]["group"]["state_history"]
        self.assertEqual(
            [(item["event"], item["occurred_at"]) for item in history],
            [
                ("group_created", "2026-04-20T09:00:00+08:00"),
                ("payment_confirmed", "2026-04-28T10:05:00+08:00"),
                ("payment_confirmed", "2026-05-03T16:35:00+08:00"),
            ],
        )
        self.assertEqual(
            [(item["payment_progress"], item["fulfillment_status"]) for item in history],
            [
                ("unpaid", "in_progress"),
                ("partially_paid", "in_progress"),
                ("paid_in_full", "in_progress"),
            ],
        )
        self.assertTrue(
            all(item["state_transition_effect_count"] == 0 for item in history)
        )
        for item in history:
            self.assert_derived_payment_progress(item)

    def test_invalid_inputs_have_exact_reasons_and_unchanged_named_baselines(self):
        invalid = {item["id"]: item for item in self.case["invalid_inputs"]}
        self.assertEqual(
            {item_id: (item["expected"]["field"], item["expected"]["reason"])
             for item_id, item in invalid.items()},
            {
                "zero-total": ("total_amount", "must_be_positive"),
                "negative-total": ("total_amount", "must_be_positive"),
                "zero-payment": ("payment_amount", "must_be_positive"),
                "negative-payment": ("payment_amount", "must_be_positive"),
                "deposit-equals-total": ("payment_amount", "deposit_must_be_less_than_total"),
                "deposit-exceeds-total": ("payment_amount", "deposit_must_be_less_than_total"),
                "final-not-remaining": ("payment_amount", "final_must_equal_remaining_due"),
                "payment-exceeds-due": ("payment_amount", "payment_exceeds_due"),
                "mixed-currencies": ("currency", "single_currency_required"),
                "missing-category": ("category_id", "secondary_category_required"),
                "primary-category": ("category_id", "secondary_category_required"),
                "inactive-category": ("category_id", "category_inactive"),
                "wrong-kind-category": ("category_id", "expense_category_required"),
                "unknown-funding-account": ("funding_account_id", "unknown_real_account"),
                "nonfinancial-funding-account": ("funding_account_id", "real_financial_account_required"),
                "non-owned-funding-account": ("funding_account_id", "owned_account_required"),
                "liability-funding-account": ("funding_account_id", "asset_account_required"),
                "paid-in-full-while-due": ("payment_progress", "due_must_be_zero"),
            },
        )
        self.assertEqual(len(invalid), 18)
        self.assertEqual(
            {
                item_id: (item["operation_context"], item["expected"]["baseline_id"])
                for item_id, item in invalid.items()
            },
            {
                "zero-total": ("group_creation", "opening_only"),
                "negative-total": ("group_creation", "opening_only"),
                "zero-payment": ("payment_creation", "group_only"),
                "negative-payment": ("payment_creation", "group_only"),
                "deposit-equals-total": ("payment_creation", "group_only"),
                "deposit-exceeds-total": ("payment_creation", "group_only"),
                "final-not-remaining": ("payment_creation", "after_deposit"),
                "payment-exceeds-due": ("payment_creation", "after_deposit"),
                "mixed-currencies": ("payment_creation", "group_only"),
                "missing-category": ("group_creation", "opening_only"),
                "primary-category": ("group_creation", "opening_only"),
                "inactive-category": ("group_creation", "opening_only"),
                "wrong-kind-category": ("group_creation", "opening_only"),
                "unknown-funding-account": ("payment_creation", "group_only"),
                "nonfinancial-funding-account": ("payment_creation", "group_only"),
                "non-owned-funding-account": ("payment_creation", "group_only"),
                "liability-funding-account": ("payment_creation", "group_only"),
                "paid-in-full-while-due": ("payment_progress_transition", "after_deposit"),
            },
        )
        opening_only = self.case["invalid_baselines"]["opening_only"]
        self.assertEqual(opening_only["transactions"], [])
        self.assertEqual(opening_only["payments"], [])
        self.assertEqual(opening_only["association_groups"], [])
        self.assertEqual(opening_only["history"], [])
        self.assertIsNone(opening_only["group"])
        self.assertNotIn("group", opening_only["reconciliation"])
        self.assertEqual(opening_only["balances"], {"asset-bank-a": "1000.00"})
        self.assertEqual(opening_only["statistics"], ZERO_STATISTICS)
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                expected = item["expected"]
                self.assertFalse(expected["accepted"])
                self.assertTrue(expected["state_unchanged"])
                self.assertEqual(expected["effect_counts"], ZERO_EFFECT_COUNTS)
                self.assertIn(expected["baseline_id"], self.case["invalid_baselines"])
                baseline = self.case["invalid_baselines"][expected["baseline_id"]]
                self.assertEqual(expected["resulting_state"], baseline)

    def test_scope_and_forbidden_effects_are_frozen(self):
        self.assertEqual(
            self.case["out_of_scope"],
            {
                "cancellation_refund_deposit_forfeiture": "RG-07",
                "later_corrections": "RG-12",
                "mixed_funding_per_stage": "RG-04",
                "merged_items": "RG-05",
                "full_payment_allocation": "RG-11",
                "combined_variants": "not_in_v1",
            },
        )
        forbidden = set(self.case["forbidden_side_effects"])
        self.assertTrue(
            {
                "create_payable_or_liability_for_due",
                "create_hidden_clearing_account",
                "create_generic_order_lifecycle",
                "auto_confirm_import_candidate",
                "guess_payment_role",
                "status_change_creates_accounting_effect",
                "duplicate_transaction",
                "duplicate_payment",
                "duplicate_consumption",
                "duplicate_association_group",
                "duplicate_cash_flow",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            }.issubset(forbidden)
        )


if __name__ == "__main__":
    unittest.main()
