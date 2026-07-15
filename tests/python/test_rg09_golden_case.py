from copy import deepcopy
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
RG09_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-09.json"
AMOUNT_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
STATE_KEYS = {
    "id",
    "transactions",
    "versions",
    "adjustments",
    "allocations",
    "candidates",
    "confirmations",
    "observations",
    "source_records",
    "evidence",
    "evidence_links",
    "audit_links",
    "balances",
    "reports",
    "reconciliation",
}
ZERO_FORMAL_DELTAS = {
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_version_count": 0,
    "new_adjustment_count": 0,
    "new_allocation_count": 0,
    "new_reversal_count": 0,
    "balance_change_count": 0,
    "report_change_count": 0,
    "reconciliation_change_count": 0,
}
ZERO_INTAKE_DELTAS = {
    "new_candidate_count": 0,
    "new_confirmation_count": 0,
    "new_observation_count": 0,
    "new_source_record_count": 0,
    "new_evidence_count": 0,
    "new_evidence_link_count": 0,
    "new_audit_link_count": 0,
}


class RG09GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG09_PATH)
        cls.accounts = cls.case["catalog"]["accounts"]
        cls.states = cls.case["canonical_states"]

    def test_fixture_envelope_catalog_and_exact_decimal_contract(self):
        validate_case_envelope(self.case)
        self.assertEqual(
            self.case["case"],
            {
                "id": "RG-09",
                "level": "core_required",
                "rule_version": 1,
                "timezone": "Asia/Shanghai",
                "currency": "CNY",
                "precision": 2,
                "ledger_id": "ledger-a",
            },
        )
        accounts = {item["id"]: item for item in self.accounts}
        for account_id in ("asset-a", "asset-b"):
            self.assertEqual(accounts[account_id]["type"], "asset")
            self.assertTrue(accounts[account_id]["owned_by_user"])
            self.assertTrue(accounts[account_id]["financial"])
        equity = accounts["equity-balance-adjustments"]
        self.assertEqual(equity["type"], "equity")
        self.assertEqual(equity["system_role"], "balance_adjustments")
        self.assertTrue(equity["system_managed"])
        self.assertTrue(equity["hidden"])
        self.assertNotEqual(equity["id"], "equity-opening")
        for path, amount in self._all_amounts(self.case):
            self.assertIsInstance(amount, str, path)
            self.assertRegex(amount, AMOUNT_PATTERN, path)

    def test_preview_freezes_replay_target_delta_and_requires_confirmation(self):
        preview = self.case["main_path"]["preview"]
        self.assertEqual(
            preview["input"],
            {
                "request_id": "request-preview-rg09",
                "account_id": "asset-a",
                "target_amount": "130.00",
                "currency": "CNY",
                "target_observed_at": "2026-01-31T23:59:59+08:00",
                "explicit_confirmation": False,
            },
        )
        result = preview["expected"]
        self.assertEqual(result["replayed_amount"], "100.00")
        self.assertEqual(result["target_amount"], "130.00")
        self.assertEqual(result["delta"], "30.00")
        self.assertEqual(result["status"], "pending_explicit_confirmation")
        self.assertEqual(result["formal_deltas"], ZERO_FORMAL_DELTAS)
        self.assertEqual(result["resulting_state"], self.states["previewed"])
        self.assertEqual(self.states["previewed"]["transactions"], [])
        self.assertEqual(self.states["previewed"]["balances"]["asset-a"], "100.00")

    def test_confirmation_creates_balanced_effective_adjustment_without_ordinary_reports(self):
        confirmed = self.states["adjustment_confirmed"]
        transaction = self._by_id(confirmed["transactions"], "transaction-adjustment-rg09")
        self.assertEqual(transaction["occurred_at"], "2026-01-31T23:59:59+08:00")
        self.assertEqual(transaction["statistics_at"], transaction["occurred_at"])
        self.assertEqual(transaction["type"], "balance_adjustment")
        self.assertEqual(
            transaction["postings"],
            [
                {
                    "id": "posting-adjustment-asset-rg09",
                    "account_id": "asset-a",
                    "amount": "30.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
                {
                    "id": "posting-adjustment-equity-rg09",
                    "account_id": "equity-balance-adjustments",
                    "amount": "-30.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
            ],
        )
        validate_transactions([*self.case["opening"]["transactions"], transaction], self.accounts)
        assert_expected_balances(
            replay_balances([*self.case["opening"]["transactions"], transaction]),
            confirmed["balances"],
        )
        report = confirmed["reports"]["cumulative"]
        self.assertEqual(report["balance_adjustment_net_worth_change"], "30.00")
        self.assertEqual(report["net_worth_change"], "30.00")
        for field in (
            "ordinary_income",
            "ordinary_expense",
            "consumption",
            "budget_effect",
            "category_effect",
            "cash_inflow",
            "cash_outflow",
        ):
            self.assertEqual(report[field], "0.00")

    def test_adjustment_is_append_only_and_never_overwrites_balance(self):
        adjustment = self.states["adjustment_confirmed"]["adjustments"][0]
        self.assertEqual(adjustment["original_delta"], "30.00")
        self.assertEqual(adjustment["explained_amount"], "0.00")
        self.assertEqual(adjustment["remaining_amount"], "30.00")
        self.assertEqual(adjustment["state"], "open")
        self.assertFalse(adjustment["overwrites_balance"])
        self.assertEqual(adjustment["transaction_id"], "transaction-adjustment-rg09")
        self.assertEqual(adjustment["target_observation_id"], "observation-target-rg09")

    def test_stale_preview_rejects_atomically_and_returns_recomputed_delta(self):
        stale = self.case["stale_preview"]
        self._assert_rejected_operation(stale)
        self.assertEqual(stale["expected"]["reason"], "ledger_changed_since_preview")
        self.assertEqual(stale["expected"]["original_preview_delta"], "30.00")
        replay = self._replay_as_of(
            [*self.case["opening"]["transactions"], *stale["pre_operation_baseline"]["transactions"]],
            self.case["main_path"]["preview"]["input"]["target_observed_at"],
        )
        self.assertEqual(replay["asset-a"], Decimal("105.00"))
        self.assertEqual(stale["expected"]["recomputed_replay_amount"], f"{replay['asset-a']:.2f}")
        self.assertEqual(
            Decimal(stale["expected"]["recomputed_delta"]),
            Decimal(self.case["main_path"]["preview"]["input"]["target_amount"]) - replay["asset-a"],
        )
        self.assertEqual(stale["expected"]["next_action"], "renew_explicit_confirmation")

    def test_target_replay_filters_by_effective_time_and_excludes_post_target_change(self):
        target = self.case["main_path"]["preview"]["input"]["target_observed_at"]
        preview_baseline = self.case["main_path"]["preview"]["pre_operation_baseline"]
        preview_replay = self._replay_as_of(
            [*self.case["opening"]["transactions"], *preview_baseline["transactions"]], target
        )
        self.assertEqual(preview_replay["asset-a"], Decimal("100.00"))
        self.assertEqual(
            Decimal(self.case["main_path"]["preview"]["expected"]["delta"]),
            Decimal("130.00") - preview_replay["asset-a"],
        )
        post_target = self.case["post_target_fixture"]
        self.assertGreater(post_target["transaction"]["effective_at"], target)
        self.assertGreater(post_target["transaction"]["created_at"], target)
        self.assertEqual(
            self._replay_as_of(
                [*self.case["opening"]["transactions"], post_target["transaction"]], target
            )["asset-a"],
            Decimal("100.00"),
        )
        self.assertEqual(post_target["target_replay"], {"asset-a": "100.00", "asset-b": "50.00"})
        self.assertEqual(post_target["all_time_replay"]["asset-a"], "105.00")

    def test_operation_chronology_separates_economic_and_creation_times(self):
        adjustment = self._by_id(self.states["adjustment_confirmed"]["transactions"], "transaction-adjustment-rg09")
        transfer = self._by_id(self.states["transfer_confirmed"]["transactions"], "transaction-transfer-rg09")
        reversal = self._by_id(self.states["partially_explained"]["transactions"], "transaction-adjustment-reversal-rg09")
        self.assertEqual(adjustment["occurred_at"], "2026-01-31T23:59:59+08:00")
        self.assertEqual(adjustment["statistics_at"], adjustment["effective_at"])
        self.assertEqual(adjustment["effective_at"], "2026-01-31T23:59:59+08:00")
        self.assertEqual(adjustment["created_at"], "2026-02-01T09:05:00+08:00")
        self.assertEqual(self.case["main_path"]["confirmation"]["input"]["confirmed_at"], adjustment["created_at"])
        self.assertEqual(transfer["occurred_at"], "2026-01-20T12:00:00+08:00")
        self.assertEqual(transfer["effective_at"], transfer["occurred_at"])
        self.assertEqual(transfer["created_at"], "2026-02-10T18:00:00+08:00")
        self.assertGreater(transfer["created_at"], transfer["occurred_at"])
        self.assertEqual(reversal["effective_at"], "2026-01-31T23:59:59+08:00")
        self.assertEqual(reversal["created_at"], "2026-02-10T18:05:00+08:00")
        self.assertEqual(self.case["main_path"]["explanation_confirmation"]["input"]["confirmed_at"], reversal["created_at"])
        imported_transfer = self._by_id(self.states["import_transfer_confirmed"]["transactions"], "transaction-transfer-rg09-import")
        imported_reversal = self._by_id(self.states["import_partially_explained"]["transactions"], "transaction-adjustment-reversal-rg09-import")
        self.assertEqual(imported_transfer["created_at"], self.case["import_path"]["transfer_confirmation"]["input"]["confirmed_at"])
        self.assertEqual(imported_reversal["created_at"], self.case["import_path"]["explanation_confirmation"]["input"]["confirmed_at"])
        stale_tx = self.states["stale_changed"]["transactions"][0]
        self.assertEqual(stale_tx["effective_at"], "2026-01-25T08:00:00+08:00")
        self.assertEqual(stale_tx["created_at"], "2026-02-02T12:00:00+08:00")
        self.assertEqual(self.case["stale_preview"]["input"]["preview_changed_at"], "2026-02-02T11:00:00+08:00")
        self.assertGreater(stale_tx["created_at"], self.case["stale_preview"]["input"]["preview_changed_at"])
        for state_name in ("adjustment_confirmed", "transfer_confirmed", "partially_explained"):
            for version in self.states[state_name]["versions"]:
                transaction = self._by_id(self.states[state_name]["transactions"], version["transaction_id"])
                self.assertGreaterEqual(version["created_at"], transaction["created_at"])

    def test_real_transfer_and_link_confirmation_create_one_atomic_reversal(self):
        transfer_operation = self.case["main_path"]["transfer_confirmation"]
        transfer_state = transfer_operation["expected"]["resulting_state"]
        self.assertEqual(transfer_operation["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(transfer_operation["expected"]["formal_deltas"]["new_allocation_count"], 0)
        self.assertEqual(transfer_operation["expected"]["formal_deltas"]["new_reversal_count"], 0)
        self.assertEqual(transfer_state, self.states["transfer_confirmed"])
        self.assertEqual(transfer_state["allocations"], [])
        self.assertEqual(transfer_state["transactions"][-1]["type"], "account_transfer")

        link_operation = self.case["main_path"]["explanation_confirmation"]
        final = link_operation["expected"]["resulting_state"]
        self.assertEqual(link_operation["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(link_operation["expected"]["formal_deltas"]["new_allocation_count"], 1)
        self.assertEqual(link_operation["expected"]["formal_deltas"]["new_reversal_count"], 1)
        self.assertEqual(link_operation["pre_operation_baseline"], transfer_state)
        transfer = self._by_id(final["transactions"], "transaction-transfer-rg09")
        reversal = self._by_id(final["transactions"], "transaction-adjustment-reversal-rg09")
        self.assertEqual(transfer["occurred_at"], "2026-01-20T12:00:00+08:00")
        self.assertEqual(transfer["type"], "account_transfer")
        self.assertEqual(
            [(p["account_id"], p["amount"]) for p in transfer["postings"]],
            [("asset-a", "20.00"), ("asset-b", "-20.00")],
        )
        self.assertEqual(reversal["occurred_at"], "2026-01-31T23:59:59+08:00")
        self.assertEqual(
            [(p["account_id"], p["amount"]) for p in reversal["postings"]],
            [("asset-a", "-20.00"), ("equity-balance-adjustments", "20.00")],
        )
        allocation = final["allocations"][0]
        self.assertEqual(allocation["amount"], "20.00")
        self.assertEqual(allocation["real_transaction_id"], transfer["id"])
        self.assertEqual(allocation["reversal_transaction_id"], reversal["id"])
        self.assertEqual(allocation["confirmed_trigger"], "explicit_explanation_allocation")
        self.assertEqual(len(final["allocations"]), 1)
        self.assertEqual(len([t for t in final["transactions"] if t["type"] == "balance_adjustment_reversal"]), 1)

        for operation in (transfer_operation, link_operation):
            input_value = operation["input"]
            for field in (
                "target_account_id",
                "counter_account_id",
                "actual_occurred_at",
                "currency",
                "amount",
                "explanation_allocation",
                "explicit_confirmation",
                "confirms_target_account",
                "confirms_counter_account",
                "confirms_actual_occurred_at",
                "confirms_currency",
                "confirms_amount",
                "confirms_explanation_allocation",
                "confirmed_at",
            ):
                self.assertIn(field, input_value)
            self.assertTrue(input_value["explicit_confirmation"])
            self.assertTrue(input_value["confirms_target_account"])
            self.assertTrue(input_value["confirms_counter_account"])
            self.assertTrue(input_value["confirms_actual_occurred_at"])
            self.assertTrue(input_value["confirms_currency"])
            self.assertTrue(input_value["confirms_amount"])

    def test_import_transfer_and_import_explanation_are_separate_explicit_operations(self):
        transfer = self.case["import_path"]["transfer_confirmation"]
        link = self.case["import_path"]["explanation_confirmation"]
        self.assertEqual(transfer["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(transfer["expected"]["formal_deltas"]["new_allocation_count"], 0)
        self.assertEqual(transfer["expected"]["formal_deltas"]["new_reversal_count"], 0)
        self.assertEqual(link["pre_operation_baseline"], transfer["expected"]["resulting_state"])
        self.assertEqual(link["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(link["expected"]["formal_deltas"]["new_allocation_count"], 1)
        self.assertEqual(link["expected"]["formal_deltas"]["new_reversal_count"], 1)
        for operation in (transfer, link):
            value = operation["input"]
            self.assertTrue(value["explicit_confirmation"])
            self.assertTrue(value["confirms_target_account"])
            self.assertTrue(value["confirms_counter_account"])
            self.assertTrue(value["confirms_actual_occurred_at"])
            self.assertTrue(value["confirms_currency"])
            self.assertTrue(value["confirms_amount"])
            self.assertIn("explanation_allocation", value)
            self.assertIn("confirms_explanation_allocation", value)
            self.assertIn("confirmed_at", value)
        self.assertFalse(transfer["input"]["confirms_explanation_allocation"])
        self.assertTrue(link["input"]["confirms_explanation_allocation"])

    def test_partial_explanation_preserves_target_history_and_report_boundaries(self):
        final = self.states["partially_explained"]
        transactions = [*self.case["opening"]["transactions"], *final["transactions"]]
        validate_transactions(transactions, self.accounts)
        assert_expected_balances(replay_balances(transactions), final["balances"])
        self.assertEqual(final["balances"]["asset-a"], "130.00")
        self.assertEqual(final["balances"]["asset-b"], "30.00")
        adjustment = final["adjustments"][0]
        self.assertEqual(adjustment["original_delta"], "30.00")
        self.assertEqual(adjustment["explained_amount"], "20.00")
        self.assertEqual(adjustment["remaining_amount"], "10.00")
        self.assertEqual(adjustment["state"], "partially_explained")
        self.assertEqual(final["reports"], self._derive_reports(final))
        cumulative = final["reports"]["cumulative"]
        self.assertEqual(cumulative["internal_transfer_amount"], "20.00")
        self.assertEqual(cumulative["balance_adjustment_net_worth_change"], "10.00")
        self.assertEqual(cumulative["net_worth_change"], "10.00")
        for field in ("ordinary_income", "ordinary_expense", "consumption", "budget_effect", "category_effect", "cash_inflow", "cash_outflow"):
            self.assertEqual(cumulative[field], "0.00")

    def test_adjustment_state_is_derived_from_allocations(self):
        cases = self.case["state_derivation_cases"]
        self.assertEqual(
            [(item["allocated_amount"], item["remaining_amount"], item["state"]) for item in cases],
            [
                ("0.00", "30.00", "open"),
                ("20.00", "10.00", "partially_explained"),
                ("30.00", "0.00", "fully_explained"),
            ],
        )
        for item in cases:
            original = Decimal(item["original_delta"])
            allocated = Decimal(item["allocated_amount"])
            remaining = Decimal(item["remaining_amount"])
            self.assertEqual(abs(original) - allocated, remaining)

    def test_explanation_constraints_and_overallocation_are_atomic(self):
        reasons = {item["id"]: item["expected"]["reason"] for item in self.case["invalid_inputs"]}
        self.assertEqual(
            reasons,
            {
                "invalid-target-decimal": "exact_decimal_string_required",
                "invalid-target-time": "timezone_aware_target_time_required",
                "wrong-target-timezone": "ledger_timezone_required",
                "unknown-target-account": "unknown_account",
                "unowned-target-account": "owned_real_asset_required",
                "nonasset-target-account": "owned_real_asset_required",
                "wrong-target-currency": "same_currency_required",
                "wrong-adjustment-equity": "dedicated_adjustment_equity_required",
                "wrong-explanation-direction": "explanation_direction_mismatch",
                "wrong-explanation-account": "same_target_account_required",
                "wrong-explanation-currency": "same_currency_required",
                "explanation-after-target": "explanation_must_not_follow_target_time",
                "over-remaining-allocation": "allocation_exceeds_remaining_adjustment",
                "guessed-link": "explicit_link_confirmation_required",
                "duplicate-conflicting-key": "idempotency_key_conflict",
            },
        )
        for operation in self.case["invalid_inputs"]:
            with self.subTest(operation=operation["id"]):
                self._assert_rejected_operation(operation)
        over = next(item for item in self.case["invalid_inputs"] if item["id"] == "over-remaining-allocation")
        self.assertEqual(over["input"]["requested_amount"], "11.00")
        self.assertEqual(over["input"]["remaining_amount"], "10.00")

    def test_invalid_operations_assert_predicates_and_fixed_probes_no_longer_match(self):
        for operation in self.case["invalid_inputs"]:
            with self.subTest(operation=operation["id"]):
                self._assert_invalid_predicate(operation)
                fixed = operation["fixed_probe"]
                self.assertTrue(fixed["predicate_resolved"])
                self.assertNotEqual(fixed["reason"], operation["expected"]["reason"])
                self.assertTrue(fixed["would_be_accepted"])
                probe = deepcopy(operation)
                probe["input"] = fixed["input"]
                with self.assertRaises((AssertionError, KeyError)):
                    self._assert_invalid_predicate(probe)

    def test_negative_delta_direction_is_balanced_but_main_case_remains_positive(self):
        example = self.case["negative_delta_boundary"]
        self.assertEqual(example["replayed_amount"], "100.00")
        self.assertEqual(example["target_amount"], "70.00")
        self.assertEqual(example["delta"], "-30.00")
        validate_transactions([example["adjustment_transaction"]], self.accounts)
        self.assertEqual(
            [(p["account_id"], p["amount"]) for p in example["adjustment_transaction"]["postings"]],
            [("asset-a", "-30.00"), ("equity-balance-adjustments", "30.00")],
        )
        self.assertEqual(example["required_explanation_direction"], "decrease_target_account")

    def test_zero_delta_saves_observation_evidence_without_zero_transaction(self):
        operation = self.case["zero_delta"]
        result = operation["expected"]
        self.assertEqual(result["delta"], "0.00")
        self.assertEqual(result["new_transaction_count"], 0)
        self.assertEqual(result["new_posting_count"], 0)
        self.assertEqual(result["new_observation_count"], 1)
        self.assertEqual(result["new_evidence_count"], 1)
        self.assertEqual(result["resulting_state"], self.states["zero_delta_observed"])
        self.assertFalse(any(t["type"] == "balance_adjustment" for t in result["resulting_state"]["transactions"]))

    def test_pending_import_and_proposed_link_have_intake_but_zero_formal_effect(self):
        pending = self.case["import_path"]["pending"]
        baseline = pending["pre_operation_baseline"]
        result = pending["expected"]["resulting_state"]
        self.assertEqual(pending["expected"]["formal_deltas"], ZERO_FORMAL_DELTAS)
        self.assertEqual(
            pending["expected"]["intake_deltas"],
            {
                "new_candidate_count": 1,
                "new_confirmation_count": 0,
                "new_observation_count": 0,
                "new_source_record_count": 1,
                "new_evidence_count": 1,
                "new_evidence_link_count": 0,
                "new_audit_link_count": 0,
            },
        )
        self.assertEqual(result, self.states["import_pending"])
        self.assertEqual(result["transactions"], baseline["transactions"])
        candidate = self._by_id(result["candidates"], "candidate-import-transfer-rg09")
        self.assertEqual(candidate["status"], "pending_confirmation")
        self.assertEqual(
            candidate["requires_confirmation"],
            [
                "transaction_id",
                "target_account_id",
                "actual_time",
                "currency",
                "explanation_allocation",
            ],
        )
        self.assertFalse(candidate["confidence_can_trigger_reversal"])

    def test_every_incomplete_import_confirmation_preserves_full_baseline(self):
        expected_reasons = {
            "missing-transaction": "exact_transaction_required",
            "missing-account": "exact_target_account_required",
            "missing-actual-time": "actual_time_required",
            "missing-currency": "exact_currency_required",
            "missing-allocation": "explicit_explanation_allocation_required",
        }
        operations = self.case["import_path"]["incomplete_confirmations"]
        self.assertEqual({item["id"]: item["expected"]["reason"] for item in operations}, expected_reasons)
        for operation in operations:
            with self.subTest(operation=operation["id"]):
                self._assert_rejected_operation(operation)

    def test_typed_evidence_audit_links_and_reconciliation_semantics(self):
        final = self.states["evidence_reconciled"]
        roles = {(item["role"], item["target_id"]) for item in final["evidence_links"]}
        self.assertIn(("target_balance_observation", "observation-target-rg09"), roles)
        self.assertIn(("real_account_posting", "posting-transfer-a-rg09"), roles)
        self.assertIn(("real_account_posting", "posting-transfer-b-rg09"), roles)
        audit_roles = {(item["role"], item["target_id"]) for item in final["audit_links"]}
        self.assertIn(("adjustment_transaction", "transaction-adjustment-rg09"), audit_roles)
        self.assertIn(("explanation_transaction", "transaction-transfer-rg09"), audit_roles)
        self.assertIn(("allocation_reversal", "transaction-adjustment-reversal-rg09"), audit_roles)
        reconciliation = final["reconciliation"]
        self.assertEqual(reconciliation["posting-transfer-a-rg09"], "matched")
        self.assertEqual(reconciliation["posting-transfer-b-rg09"], "matched")
        self.assertEqual(reconciliation["target-observation-rg09"], "balanced_with_unexplained_adjustment")
        self.assertEqual(reconciliation["remaining_adjustment"], "10.00")
        self.assertEqual(reconciliation["full_reconciliation_requirement"], "remaining_adjustment_zero_and_actual_postings_evidenced")

    def test_fully_explained_requires_the_second_allocation_and_all_real_posting_evidence(self):
        partial = self.states["partially_explained"]
        unreconciled = self.states["fully_explained_unreconciled"]
        fully = self.states["fully_explained"]
        self.assertEqual(partial["reconciliation"]["target-observation-rg09"], "balanced_with_unexplained_adjustment")
        self.assertEqual(partial["reconciliation"]["posting-transfer-a-rg09"], "pending_evidence")
        self.assertEqual(partial["reconciliation"]["posting-transfer-b-rg09"], "pending_evidence")
        self.assertEqual(unreconciled["adjustments"][0]["state"], "fully_explained")
        self.assertEqual(unreconciled["adjustments"][0]["remaining_amount"], "0.00")
        self.assertEqual(unreconciled["reconciliation"]["target-observation-rg09"], "evidence_incomplete")
        for posting_id in (
            "posting-transfer-a-rg09",
            "posting-transfer-b-rg09",
            "posting-transfer-a-rg09-remaining",
            "posting-transfer-b-rg09-remaining",
        ):
            self.assertEqual(unreconciled["reconciliation"][posting_id], "pending_evidence")
        self.assertEqual(fully["adjustments"][0]["state"], "fully_explained")
        self.assertEqual(fully["adjustments"][0]["remaining_amount"], "0.00")
        self.assertEqual(len(fully["allocations"]), 2)
        self.assertEqual(fully["balances"]["asset-a"], "130.00")
        self.assertEqual(fully["balances"]["asset-b"], "20.00")
        self.assertEqual(fully["reconciliation"]["target-observation-rg09"], "fully_reconciled")
        for posting_id in (
            "posting-transfer-a-rg09",
            "posting-transfer-b-rg09",
            "posting-transfer-a-rg09-remaining",
            "posting-transfer-b-rg09-remaining",
        ):
            self.assertEqual(fully["reconciliation"][posting_id], "matched")
        transactions = [*self.case["opening"]["transactions"], *fully["transactions"]]
        validate_transactions(transactions, self.accounts)
        assert_expected_balances(replay_balances(transactions), fully["balances"])
        self.assertEqual(self._derive_reports(fully), fully["reports"])

    def test_second_transfer_allocation_and_each_evidence_link_are_independent_operations(self):
        operations = [
            self.case["main_path"]["second_transfer_confirmation"],
            self.case["main_path"]["second_explanation_confirmation"],
            self.case["evidence_path"]["first_transfer_asset_a"],
            self.case["evidence_path"]["first_transfer_asset_b"],
            self.case["evidence_path"]["second_transfer_asset_a"],
            self.case["evidence_path"]["second_transfer_asset_b"],
        ]
        expected_results = [
            "state-rg09-second-transfer-confirmed",
            "state-rg09-fully-explained-unreconciled",
            "state-rg09-fully-explained-evidence-1",
            "state-rg09-fully-explained-evidence-2",
            "state-rg09-fully-explained-evidence-3",
            "state-rg09-fully-explained",
        ]
        self.assertEqual(
            [item["expected"]["resulting_state"]["id"] for item in operations],
            expected_results,
        )
        for index, operation in enumerate(operations):
            with self.subTest(operation=operation["id"]):
                self.assertTrue(operation["expected"]["accepted"])
                self.assertEqual(set(operation["pre_operation_baseline"]), STATE_KEYS)
                self.assertEqual(set(operation["expected"]["resulting_state"]), STATE_KEYS)
                self.assertEqual(operation["operation_context"]["baseline_id"], operation["pre_operation_baseline"]["id"])
                self.assertEqual(operation["operation_context"]["result_id"], operation["expected"]["resulting_state"]["id"])
                self.assertIn("formal_deltas", operation["expected"])
                self.assertIn("intake_deltas", operation["expected"])
                if index:
                    self.assertEqual(operation["pre_operation_baseline"], operations[index - 1]["expected"]["resulting_state"])
        second_transfer, second_allocation = operations[:2]
        self.assertEqual(second_transfer["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(second_transfer["expected"]["formal_deltas"]["new_allocation_count"], 0)
        self.assertEqual(second_allocation["expected"]["formal_deltas"]["new_transaction_count"], 1)
        self.assertEqual(second_allocation["expected"]["formal_deltas"]["new_allocation_count"], 1)
        self.assertEqual(second_allocation["expected"]["formal_deltas"]["new_reversal_count"], 1)
        for evidence_operation in operations[2:]:
            self.assertEqual(evidence_operation["expected"]["formal_deltas"]["new_transaction_count"], 0)
            self.assertEqual(evidence_operation["expected"]["intake_deltas"]["new_source_record_count"], 1)
            self.assertEqual(evidence_operation["expected"]["intake_deltas"]["new_evidence_count"], 1)
            self.assertEqual(evidence_operation["expected"]["intake_deltas"]["new_evidence_link_count"], 1)
        self.assertEqual(operations[-2]["expected"]["resulting_state"]["reconciliation"]["target-observation-rg09"], "evidence_incomplete")
        self.assertEqual(operations[-1]["expected"]["resulting_state"]["reconciliation"]["target-observation-rg09"], "fully_reconciled")

    def test_all_canonical_states_are_complete_replayable_and_report_derived(self):
        replayable = {
            "previewed",
            "adjustment_confirmed",
            "stale_changed",
            "partially_explained",
            "import_pending",
            "evidence_reconciled",
            "transfer_confirmed",
            "import_transfer_confirmed",
            "import_partially_explained",
            "fully_explained",
            "second_transfer_confirmed",
            "fully_explained_unreconciled",
            "fully_explained_evidence_1",
            "fully_explained_evidence_2",
            "fully_explained_evidence_3",
            "zero_delta_observed",
        }
        for name, state in self.states.items():
            with self.subTest(state=name):
                self.assertEqual(set(state), STATE_KEYS)
                self._assert_state_references(state)
                if name in replayable:
                    transactions = [*self.case["opening"]["transactions"], *state["transactions"]]
                    validate_transactions(transactions, self.accounts)
                    assert_expected_balances(replay_balances(transactions), state["balances"])
                    self.assertEqual(self._derive_reports(state), state["reports"])

    def test_all_operation_snapshots_have_full_baseline_result_and_exact_deltas(self):
        operations = [
            self.case["stale_preview"],
            self.case["zero_delta"],
            self.case["main_path"]["preview"],
            self.case["main_path"]["confirmation"],
            self.case["main_path"]["transfer_confirmation"],
            self.case["main_path"]["explanation_confirmation"],
            self.case["main_path"]["second_transfer_confirmation"],
            self.case["main_path"]["second_explanation_confirmation"],
            self.case["import_path"]["pending"],
            self.case["import_path"]["transfer_confirmation"],
            self.case["import_path"]["explanation_confirmation"],
            self.case["evidence_path"]["first_transfer_asset_a"],
            self.case["evidence_path"]["first_transfer_asset_b"],
            self.case["evidence_path"]["second_transfer_asset_a"],
            self.case["evidence_path"]["second_transfer_asset_b"],
            *self.case["import_path"]["incomplete_confirmations"],
            *self.case["invalid_inputs"],
        ]
        for operation in operations:
            with self.subTest(operation=operation["id"]):
                self.assertEqual(set(operation["pre_operation_baseline"]), STATE_KEYS)
                self.assertEqual(set(operation["expected"]["resulting_state"]), STATE_KEYS)
                self.assertIn("operation_context", operation)
                self.assertEqual(operation["operation_context"]["baseline_id"], operation["pre_operation_baseline"]["id"])
                self.assertEqual(operation["operation_context"]["result_id"], operation["expected"]["resulting_state"]["id"])
                self._assert_state_references(operation["pre_operation_baseline"])
                self._assert_state_references(operation["expected"]["resulting_state"])
                self.assertIn("formal_deltas", operation["expected"])
                self.assertIn("intake_deltas", operation["expected"])

    def test_idempotency_is_per_input_with_full_baseline_and_result(self):
        retries = self.case["idempotency"]["retries"]
        expected_inputs = {
            "request-preview-rg09",
            "request-confirm-adjustment-rg09",
            "source-target-observation-rg09",
            "request-transfer-rg09",
            "request-confirm-allocation-rg09",
            "request-import-transfer-confirm-rg09",
            "request-import-allocation-confirm-rg09",
            "request-transfer-rg09-remaining",
            "request-confirm-allocation-rg09-remaining",
            "source-import-transfer-rg09",
            "source-transfer-a-rg09",
            "source-transfer-b-rg09",
            "source-transfer-a-rg09-remaining",
            "source-transfer-b-rg09-remaining",
            "request-zero-delta-rg09",
        }
        self.assertEqual({item["input_id"] for item in retries}, expected_inputs)
        self.assertEqual(len(retries), len(expected_inputs))
        for retry in retries:
            with self.subTest(retry=retry["id"]):
                self.assertEqual(retry["pre_operation_baseline"], retry["expected"]["resulting_state"])
                self.assertEqual(retry["expected"]["formal_deltas"], ZERO_FORMAL_DELTAS)
                self.assertEqual(retry["expected"]["intake_deltas"], ZERO_INTAKE_DELTAS)
                self.assertIn("operation_context", retry)
                self.assertEqual(retry["operation_context"]["baseline_id"], retry["pre_operation_baseline"]["id"])
                self.assertEqual(retry["operation_context"]["result_id"], retry["expected"]["resulting_state"]["id"])
                self._assert_returned_ids(retry["input_id"], retry["expected"]["returned_stable_ids"], retry["expected"]["resulting_state"])
                self._assert_state_references(retry["pre_operation_baseline"])

    def _assert_invalid_predicate(self, operation):
        predicate = operation["predicate"]
        value = operation["input"]
        if predicate == "exact_decimal":
            self.assertFalse(isinstance(value["target_amount"], str) and re.fullmatch(AMOUNT_PATTERN, value["target_amount"]))
        elif predicate == "timezone_aware":
            self.assertNotIn("+", value["target_observed_at"])
        elif predicate == "ledger_timezone":
            self.assertNotEqual(value["target_observed_at"][-6:], "+08:00")
        elif predicate == "known_account":
            self.assertNotIn(value["account_id"], {item["id"] for item in self.accounts})
        elif predicate == "owned_real_asset":
            account = next(item for item in self.accounts if item["id"] == value["account_id"])
            self.assertFalse(account["type"] == "asset" and account["owned_by_user"] and account["financial"])
        elif predicate == "currency_cny":
            self.assertNotEqual(value["currency"], "CNY")
        elif predicate == "dedicated_equity":
            self.assertNotEqual(value["equity_account_id"], "equity-balance-adjustments")
        elif predicate == "same_direction":
            self.assertNotEqual(value["direction"], "increase_target_account")
        elif predicate == "same_target_account":
            self.assertNotEqual(value["account_id"], "asset-a")
        elif predicate == "before_target":
            self.assertGreater(value["actual_at"], "2026-01-31T23:59:59+08:00")
        elif predicate == "remaining_cap":
            self.assertGreater(Decimal(value["requested_amount"]), Decimal(value["remaining_amount"]))
        elif predicate == "explicit_link":
            self.assertFalse(value["explicit_confirmation"])
        elif predicate == "idempotency_conflict":
            self.assertNotEqual(value["target_amount"], "130.00")
        else:
            self.fail(f"unknown invalid predicate: {predicate}")

    def _assert_returned_ids(self, input_id, returned, state):
        transactions = {item["id"]: item for item in state["transactions"]}
        versions = {item["id"]: item for item in state["versions"]}
        adjustments = {item["id"]: item for item in state["adjustments"]}
        allocations = {item["id"]: item for item in state["allocations"]}
        confirmations = {item["id"]: item for item in state["confirmations"]}
        candidates = {item["id"]: item for item in state["candidates"]}
        observations = {item["id"]: item for item in state["observations"]}
        sources = {item["id"]: item for item in state["source_records"]}
        evidence = {item["id"]: item for item in state["evidence"]}
        links = {item["id"]: item for item in state["evidence_links"]}
        for key, entity_id in returned.items():
            registry = {
                "transaction_id": transactions,
                "version_id": versions,
                "adjustment_id": adjustments,
                "allocation_id": allocations,
                "confirmation_id": confirmations,
                "candidate_id": candidates,
                "observation_id": observations,
                "source_record_id": sources,
                "evidence_id": evidence,
                "evidence_link_id": links,
            }
            self.assertIn(key, registry, f"{input_id}:{key}")
            self.assertIn(entity_id, registry[key], f"{input_id}:{key}")
            if key == "transaction_id":
                transaction = transactions[entity_id]
                self.assertIn(transaction["type"], {"account_transfer", "balance_adjustment", "balance_adjustment_reversal"})
                for posting in transaction["postings"]:
                    account = next(item for item in self.accounts if item["id"] == posting["account_id"])
                    self.assertIn(account["type"], {"asset", "equity"})
                    if transaction["type"] == "account_transfer":
                        self.assertTrue(account["owned_by_user"])
                        self.assertTrue(account["financial"])
            elif key == "version_id":
                self.assertIn(versions[entity_id]["transaction_id"], transactions)
            elif key == "adjustment_id":
                adjustment = adjustments[entity_id]
                account = next(item for item in self.accounts if item["id"] == adjustment["target_account_id"])
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
            elif key == "allocation_id":
                allocation = allocations[entity_id]
                account = next(item for item in self.accounts if item["id"] == allocation["target_account_id"])
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
        expected = self.case["idempotency_expected_ids"][input_id]
        self.assertEqual(returned, expected)

    def test_idempotency_wrong_other_input_ids_are_rejected(self):
        retry = next(item for item in self.case["idempotency"]["retries"] if item["input_id"] == "request-confirm-adjustment-rg09")
        mutated = deepcopy(retry["expected"]["returned_stable_ids"])
        mutated["transaction_id"] = "transaction-transfer-rg09"
        with self.assertRaises(AssertionError):
            self._assert_returned_ids(retry["input_id"], mutated, retry["expected"]["resulting_state"])
        mutated["transaction_id"] = "transaction-unknown-rg09"
        with self.assertRaises(AssertionError):
            self._assert_returned_ids(retry["input_id"], mutated, retry["expected"]["resulting_state"])

    def test_original_entities_and_history_are_immutable_across_explanation(self):
        before = self.states["adjustment_confirmed"]
        after = self.states["partially_explained"]
        for collection in ("transactions", "versions", "observations", "source_records", "evidence", "evidence_links"):
            for original in before[collection]:
                matching = [item for item in after[collection] if item["id"] == original["id"]]
                self.assertEqual(matching, [original], f"{collection}:{original['id']}")
        original_adjustment = before["adjustments"][0]
        explained_adjustment = after["adjustments"][0]
        self.assertEqual(explained_adjustment["history"][:1], original_adjustment["history"])
        for key in (
            "id",
            "transaction_id",
            "target_observation_id",
            "target_account_id",
            "equity_account_id",
            "currency",
            "target_observed_at",
            "replayed_amount_at_confirmation",
            "target_amount",
            "original_delta",
            "overwrites_balance",
        ):
            self.assertEqual(explained_adjustment[key], original_adjustment[key], key)

    def test_nested_reference_and_type_mutations_are_rejected(self):
        def mutate(collection, item_id, key, value):
            return lambda state: self._by_id(state[collection], item_id).__setitem__(key, value)

        mutations = [
            ("adjustment-transaction", mutate("adjustments", "adjustment-rg09", "transaction_id", "transaction-transfer-rg09")),
            ("adjustment-observation", mutate("adjustments", "adjustment-rg09", "target_observation_id", "evidence-target-rg09")),
            ("adjustment-equity-role", mutate("adjustments", "adjustment-rg09", "equity_account_id", "equity-opening")),
            ("allocation-adjustment", mutate("allocations", "allocation-rg09-20", "adjustment_id", "transaction-adjustment-rg09")),
            ("allocation-real-transaction", mutate("allocations", "allocation-rg09-20", "real_transaction_id", "transaction-adjustment-rg09")),
            ("allocation-real-posting-owner", mutate("allocations", "allocation-rg09-20", "real_posting_id", "posting-transfer-b-rg09")),
            ("allocation-reversal", mutate("allocations", "allocation-rg09-20", "reversal_transaction_id", "transaction-transfer-rg09")),
            ("observation-account-role", mutate("observations", "observation-target-rg09", "account_id", "equity-opening")),
            ("candidate-account-role", mutate("candidates", "candidate-adjustment-rg09", "account_id", "equity-opening")),
            ("source-account-role", mutate("source_records", "source-transfer-a-rg09", "account_id", "equity-opening")),
            ("evidence-source", mutate("evidence", "evidence-target-rg09", "source_id", "source-unknown")),
            ("evidence-link-role", mutate("evidence_links", "evidence-link-target-rg09", "role", "real_account_posting")),
            ("evidence-link-source-swap", mutate("evidence_links", "evidence-link-transfer-a-rg09", "source_id", "source-transfer-b-rg09")),
            ("evidence-link-evidence-swap", mutate("evidence_links", "evidence-link-transfer-a-rg09", "evidence_id", "evidence-transfer-b-rg09")),
            ("evidence-link-target", mutate("evidence_links", "evidence-link-transfer-a-rg09", "target_id", "posting-reversal-a-rg09")),
            ("audit-link-allocation", mutate("audit_links", "audit-link-reversal-rg09", "allocation_id", "allocation-unknown")),
            ("audit-link-target", mutate("audit_links", "audit-link-reversal-rg09", "target_id", "posting-reversal-a-rg09")),
            ("confirmation-request", mutate("confirmations", "confirmation-allocation-rg09", "request_id", "request-unknown")),
            ("confirmation-target", mutate("confirmations", "confirmation-allocation-rg09", "allocation_id", "allocation-unknown")),
        ]
        for name, change in mutations:
            with self.subTest(mutation=name):
                state = deepcopy(self.states["evidence_reconciled"])
                change(state)
                with self.assertRaises((AssertionError, KeyError)):
                    self._assert_state_references(state)

    def test_scope_and_forbidden_implicit_effects_are_frozen(self):
        self.assertEqual(
            set(self.case["out_of_scope"]),
            {
                "multi_account_targets",
                "liability_targets",
                "foreign_exchange",
                "concurrent_adjustments",
                "income_or_expense_explanations",
                "correction_workflows_beyond_basic_boundary",
            },
        )
        self.assertEqual(
            set(self.case["forbidden_side_effects"]),
            {
                "overwrite_balance",
                "auto_confirm_adjustment",
                "auto_reverse_from_match_confidence",
                "auto_link_formal_transaction",
                "overallocate_explanation",
                "mutate_or_delete_audit_entities",
                "create_zero_adjustment_transaction",
                "treat_adjustment_as_income_or_expense",
                "treat_numeric_agreement_as_full_reconciliation",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            },
        )

    def _assert_rejected_operation(self, operation):
        expected = operation["expected"]
        self.assertFalse(expected["accepted"])
        self.assertTrue(expected["state_unchanged"])
        self.assertEqual(expected["resulting_state"], operation["pre_operation_baseline"])
        self.assertEqual(expected["formal_deltas"], ZERO_FORMAL_DELTAS)
        self.assertEqual(expected["intake_deltas"], ZERO_INTAKE_DELTAS)

    def _assert_state_references(self, state):
        self.assertEqual(set(state), STATE_KEYS)
        accounts = {item["id"]: item for item in self.accounts}
        transactions = {item["id"]: item for item in state["transactions"]}
        versions = {item["id"]: item for item in state["versions"]}
        adjustments = {item["id"]: item for item in state["adjustments"]}
        allocations = {item["id"]: item for item in state["allocations"]}
        candidates = {item["id"]: item for item in state["candidates"]}
        observations = {item["id"]: item for item in state["observations"]}
        sources = {item["id"]: item for item in state["source_records"]}
        evidence = {item["id"]: item for item in state["evidence"]}
        postings = {}
        for collection, index in (
            (state["transactions"], transactions),
            (state["versions"], versions),
            (state["adjustments"], adjustments),
            (state["allocations"], allocations),
            (state["candidates"], candidates),
            (state["observations"], observations),
            (state["source_records"], sources),
            (state["evidence"], evidence),
        ):
            self.assertEqual(len(collection), len(index))
        for collection in (
            state["confirmations"],
            state["evidence_links"],
            state["audit_links"],
        ):
            ids = [item["id"] for item in collection]
            self.assertEqual(len(ids), len(set(ids)))
        for transaction in transactions.values():
            version = versions[transaction["current_version_id"]]
            self.assertEqual(version["transaction_id"], transaction["id"])
            self.assertEqual(version["posting_set_id"], transaction["posting_set_id"])
            self.assertEqual(transaction["effective_at"], transaction["occurred_at"])
            self.assertGreaterEqual(version["created_at"], transaction["created_at"])
            for posting in transaction["postings"]:
                self.assertIn(posting["account_id"], accounts)
                self.assertNotIn(posting["id"], postings)
                postings[posting["id"]] = (transaction, posting)
        self.assertEqual({item["transaction_id"] for item in versions.values()}, set(transactions))
        for adjustment in adjustments.values():
            self.assertEqual(transactions[adjustment["transaction_id"]]["type"], "balance_adjustment")
            observation = observations[adjustment["target_observation_id"]]
            self.assertEqual(observation["account_id"], adjustment["target_account_id"])
            self.assertEqual(observation["currency"], adjustment["currency"])
            owned = accounts[adjustment["target_account_id"]]
            self.assertEqual(owned["type"], "asset")
            self.assertTrue(owned["owned_by_user"])
            self.assertTrue(owned["financial"])
            equity = accounts[adjustment["equity_account_id"]]
            self.assertEqual(equity["type"], "equity")
            self.assertEqual(equity["system_role"], "balance_adjustments")
            self.assertTrue(equity["system_managed"])
            self.assertTrue(equity["hidden"])
            transaction = transactions[adjustment["transaction_id"]]
            target_posting = next(
                item for item in transaction["postings"]
                if item["account_id"] == adjustment["target_account_id"]
            )
            equity_posting = next(
                item for item in transaction["postings"]
                if item["account_id"] == adjustment["equity_account_id"]
            )
            self.assertEqual(Decimal(target_posting["amount"]), Decimal(adjustment["original_delta"]))
            self.assertEqual(Decimal(equity_posting["amount"]), -Decimal(adjustment["original_delta"]))
            linked = [item for item in allocations.values() if item["adjustment_id"] == adjustment["id"]]
            explained = sum((abs(Decimal(item["amount"])) for item in linked), Decimal("0.00"))
            self.assertEqual(explained, Decimal(adjustment["explained_amount"]))
            self.assertEqual(abs(Decimal(adjustment["original_delta"])) - explained, Decimal(adjustment["remaining_amount"]))
            derived = "open" if explained == 0 else "fully_explained" if Decimal(adjustment["remaining_amount"]) == 0 else "partially_explained"
            self.assertEqual(adjustment["state"], derived)
        for allocation in allocations.values():
            adjustment = adjustments[allocation["adjustment_id"]]
            real = transactions[allocation["real_transaction_id"]]
            reversal = transactions[allocation["reversal_transaction_id"]]
            self.assertEqual(real["type"], "account_transfer")
            self.assertEqual(reversal["type"], "balance_adjustment_reversal")
            self.assertLessEqual(real["occurred_at"], adjustment["target_observed_at"])
            self.assertEqual(reversal["occurred_at"], adjustment["target_observed_at"])
            self.assertEqual(allocation["target_account_id"], adjustment["target_account_id"])
            self.assertEqual(allocation["currency"], adjustment["currency"])
            owner, real_posting = postings[allocation["real_posting_id"]]
            self.assertEqual(owner["id"], real["id"])
            self.assertEqual(real_posting["account_id"], adjustment["target_account_id"])
            amount = Decimal(allocation["amount"])
            if allocation["direction"] == "increase_target_account":
                self.assertEqual(Decimal(real_posting["amount"]), amount)
                reversal_amount = -amount
            elif allocation["direction"] == "decrease_target_account":
                self.assertEqual(Decimal(real_posting["amount"]), -amount)
                reversal_amount = amount
            else:
                self.fail(f"unknown allocation direction: {allocation['direction']}")
            reversal_target = next(
                item for item in reversal["postings"]
                if item["account_id"] == adjustment["target_account_id"]
            )
            reversal_equity = next(
                item for item in reversal["postings"]
                if item["account_id"] == adjustment["equity_account_id"]
            )
            self.assertEqual(Decimal(reversal_target["amount"]), reversal_amount)
            self.assertEqual(Decimal(reversal_equity["amount"]), -reversal_amount)
        for observation in observations.values():
            account = accounts[observation["account_id"]]
            self.assertEqual(account["type"], "asset")
            self.assertTrue(account["owned_by_user"])
            self.assertTrue(account["financial"])
            self.assertIn(observation["source_id"], sources)
        for candidate in candidates.values():
            for source_id in candidate["source_ids"]:
                self.assertIn(source_id, sources)
            for key in ("account_id", "proposed_target_account_id", "proposed_counter_account_id"):
                if candidate.get(key) is not None:
                    account = accounts[candidate[key]]
                    self.assertEqual(account["type"], "asset")
                    self.assertTrue(account["owned_by_user"])
                    self.assertTrue(account["financial"])
        for source in sources.values():
            for key in ("account_id", "counter_account_id"):
                if source.get(key) is not None:
                    account = accounts[source[key]]
                    self.assertEqual(account["type"], "asset")
                    self.assertTrue(account["owned_by_user"])
                    self.assertTrue(account["financial"])
        for item in evidence.values():
            self.assertIn(item["source_id"], sources)
        for link in state["evidence_links"]:
            self.assertIn(link["source_id"], sources)
            self.assertIn(link["evidence_id"], evidence)
            self.assertEqual(evidence[link["evidence_id"]]["source_id"], link["source_id"])
            if link["role"] == "target_balance_observation":
                self.assertIn(link["target_id"], observations)
                observation = observations[link["target_id"]]
                source = sources[link["source_id"]]
                self.assertEqual(source["account_id"], observation["account_id"])
                self.assertEqual(source["currency"], observation["currency"])
                self.assertEqual(source["amount"], observation["target_amount"])
            elif link["role"] == "real_account_posting":
                _, posting = postings[link["target_id"]]
                account = accounts[posting["account_id"]]
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
                self.assertTrue(posting["reconciliation_eligible"])
                source = sources[link["source_id"]]
                self.assertEqual(source["account_id"], posting["account_id"])
                self.assertEqual(source["currency"], posting["currency"])
                self.assertEqual(source["amount"], posting["amount"])
            else:
                self.fail(f"unknown evidence role: {link['role']}")
        for link in state["audit_links"]:
            self.assertIn("created_at", link)
            allocation = allocations[link["allocation_id"]]
            self.assertGreaterEqual(link["created_at"], allocation["confirmed_at"])
            if link["role"] == "adjustment_transaction":
                self.assertEqual(link["target_id"], adjustments[allocation["adjustment_id"]]["transaction_id"])
            elif link["role"] == "explanation_transaction":
                self.assertEqual(link["target_id"], allocation["real_transaction_id"])
            elif link["role"] == "allocation_reversal":
                self.assertEqual(link["target_id"], allocation["reversal_transaction_id"])
            else:
                self.fail(f"unknown audit role: {link['role']}")
        request_ids = set(self.case["request_registry"])
        for confirmation in state["confirmations"]:
            self.assertIn(confirmation["request_id"], request_ids)
            self.assertIn("created_at", confirmation)
            self.assertEqual(confirmation["created_at"], confirmation["confirmed_at"])
            if confirmation["role"] == "balance_adjustment_confirmation":
                self.assertIn(confirmation["adjustment_id"], adjustments)
            elif confirmation["role"] == "real_transfer_confirmation":
                self.assertIn(confirmation["transaction_id"], transactions)
                self.assertEqual(transactions[confirmation["transaction_id"]]["type"], "account_transfer")
            elif confirmation["role"] == "explanation_allocation_confirmation":
                self.assertIn(confirmation["allocation_id"], allocations)
            else:
                self.fail(f"unknown confirmation role: {confirmation['role']}")
        for target_id, status in state["reconciliation"].items():
            if target_id.startswith("posting-"):
                self.assertIn(target_id, postings)
                account = accounts[postings[target_id][1]["account_id"]]
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
                self.assertIn(status, {"pending_evidence", "matched"})
            elif target_id not in {
                "target-observation-rg09",
                "remaining_adjustment",
                "full_reconciliation_requirement",
            }:
                self.fail(f"unknown reconciliation target: {target_id}")

    def _replay_as_of(self, transactions, target_time):
        selected = [
            transaction for transaction in transactions
            if transaction.get("effective", False)
            and transaction["effective_at"] <= target_time
        ]
        return replay_balances(selected)

    def _derive_reports(self, state):
        fields = [
            "ordinary_income",
            "ordinary_expense",
            "consumption",
            "budget_effect",
            "category_effect",
            "cash_inflow",
            "cash_outflow",
            "internal_transfer_amount",
            "balance_adjustment_net_worth_change",
            "net_worth_change",
        ]
        periods = {}
        allocations = {item["reversal_transaction_id"]: item for item in state["allocations"]}
        for transaction in state["transactions"]:
            period = transaction["statistics_at"][:7]
            report = periods.setdefault(period, {field: Decimal("0.00") for field in fields})
            if transaction["type"] == "balance_adjustment":
                amount = next(Decimal(p["amount"]) for p in transaction["postings"] if p["account_id"] == "asset-a")
                report["balance_adjustment_net_worth_change"] += amount
                report["net_worth_change"] += amount
            elif transaction["type"] == "balance_adjustment_reversal":
                amount = Decimal(allocations[transaction["id"]]["amount"])
                target_posting = next(p for p in transaction["postings"] if p["account_id"] == "asset-a")
                signed = Decimal(target_posting["amount"])
                self.assertEqual(abs(signed), amount)
                report["balance_adjustment_net_worth_change"] += signed
                report["net_worth_change"] += signed
            elif transaction["type"] == "account_transfer":
                amount = max(Decimal(p["amount"]) for p in transaction["postings"])
                report["internal_transfer_amount"] += amount
            else:
                self.fail(f"unexpected RG-09 transaction type: {transaction['type']}")
        cumulative = {field: sum((report[field] for report in periods.values()), Decimal("0.00")) for field in fields}
        result = {period: {field: f"{amount:.2f}" for field, amount in report.items()} for period, report in sorted(periods.items())}
        result["cumulative"] = {field: f"{amount:.2f}" for field, amount in cumulative.items()}
        return result

    @staticmethod
    def _by_id(items, item_id):
        return next(item for item in items if item["id"] == item_id)

    def _all_amounts(self, value, path="root"):
        amount_keys = {
            "amount",
            "target_amount",
            "replayed_amount",
            "delta",
            "original_delta",
            "explained_amount",
            "remaining_amount",
            "allocated_amount",
            "requested_amount",
        }
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}.{key}"
                if ".invalid_inputs[" in child_path and ".input." in child_path:
                    continue
                if key in {"balances", "reports"}:
                    yield from self._all_amounts(child, child_path)
                elif key in amount_keys and child is not None:
                    yield child_path, child
                else:
                    yield from self._all_amounts(child, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                yield from self._all_amounts(child, f"{path}[{index}]")
        elif isinstance(value, str) and path.split(".")[-1] in {
            "ordinary_income",
            "ordinary_expense",
            "consumption",
            "budget_effect",
            "category_effect",
            "cash_inflow",
            "cash_outflow",
            "internal_transfer_amount",
            "balance_adjustment_net_worth_change",
            "net_worth_change",
            "remaining_adjustment",
        }:
            yield path, value


if __name__ == "__main__":
    unittest.main()
