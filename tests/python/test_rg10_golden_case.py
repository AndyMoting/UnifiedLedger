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
RG10_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-10.json"
AMOUNT_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
ZERO_FORMAL_DELTAS = {
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_lot_count": 0,
    "new_lot_consumption_count": 0,
    "new_version_count": 0,
    "new_adjustment_count": 0,
    "new_confirmation_count": 0,
    "new_report_effect_count": 0,
}
ZERO_INTAKE_DELTAS = {
    "new_candidate_count": 0,
    "new_source_record_count": 0,
    "new_evidence_count": 0,
    "new_evidence_link_count": 0,
    "new_audit_link_count": 0,
}
ZERO_RECONCILIATION_DELTAS = {"new_reconciliation_change_count": 0}


class RG10GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG10_PATH)
        cls.accounts = cls.case["catalog"]["accounts"]
        cls.states = cls.case["canonical_states"]

    def _state(self, name):
        return self.states[name]

    def _state_by_id(self, state_id):
        matches = [state for state in self.states.values() if state["id"] == state_id]
        matches.extend(
            state
            for state in self.case["reconciliation_states"].values()
            if state["id"] == state_id
        )
        matches.extend(
            state
            for state in self.case["import_path"]["pending_states"].values()
            if state["id"] == state_id
        )
        matches.extend(
            state
            for state in self.case["secondary_cases"]["merchant_evidenced_allocation"]["states"].values()
            if state["id"] == state_id
        )
        self.assertEqual(len(matches), 1, state_id)
        return matches[0]

    def _transactions(self, state):
        return [*self.case["opening"]["transactions"], *state["transactions"]]

    def _assert_state_references(self, state):
        transaction_ids = {item["id"] for item in state["transactions"]}
        posting_ids = {
            posting["id"]
            for transaction in state["transactions"]
            for posting in transaction["postings"]
        }
        lot_ids = {item["id"] for item in state["lots"]}
        source_ids = {item["id"] for item in state["source_records"]}
        evidence_ids = {item["id"] for item in state["evidence"]}
        confirmation_ids = {item["id"] for item in state["confirmations"]}
        for transaction in state["transactions"]:
            if transaction.get("lot_id") is not None:
                self.assertIn(transaction["lot_id"], lot_ids)
            for consumption in transaction.get("lot_consumptions", []):
                self.assertIn(consumption["lot_id"], lot_ids)
        for lot in state["lots"]:
            for history in lot.get("history", []):
                self.assertIn(history["transaction_id"], transaction_ids)
        for confirmation in state["confirmations"]:
            if confirmation.get("transaction_id"):
                self.assertIn(confirmation["transaction_id"], transaction_ids)
            if confirmation.get("source_id"):
                self.assertIn(confirmation["source_id"], source_ids)
            if confirmation.get("evidence_id"):
                self.assertIn(confirmation["evidence_id"], evidence_ids)
        for link in state["evidence_links"]:
            self.assertIn(link["source_id"], source_ids)
            self.assertIn(link["evidence_id"], evidence_ids)
            self.assertIn(link["target_id"], posting_ids | transaction_ids | lot_ids)
            if link.get("lot_id"):
                self.assertIn(link["lot_id"], lot_ids)
        for adjustment in state["adjustments"]:
            self.assertIn(adjustment["transaction_id"], transaction_ids)
            for history in adjustment.get("history", []):
                self.assertIn(history["transaction_id"], transaction_ids)
        for link in state["audit_links"]:
            self.assertIn(link["transaction_id"], transaction_ids)
            self.assertIn(link["source_id"], source_ids)
            self.assertIn(link["evidence_id"], evidence_ids)
            self.assertIn(link["confirmation_id"], confirmation_ids)

    def _is_amount_field(self, key, child):
        return (
            not key.startswith("confirms_")
            and not isinstance(child, (dict, list))
            and (
                key == "amount"
                or key.endswith(("_amount", "_balance", "_value"))
                or key in {
                    "ordinary_income",
                    "special_non_cash_bonus_income",
                    "ordinary_expense",
                    "expiry_loss",
                    "consumption",
                    "budget_effect",
                    "category_effect",
                    "cash_inflow",
                    "cash_outflow",
                    "net_worth_change",
                }
            )
        )

    def _assert_exact_amount_strings(self, value, path="case"):
        if isinstance(value, dict):
            for key, child in value.items():
                if self._is_amount_field(key, child):
                    if child is not None:
                        self.assertIsInstance(child, str, f"{path}.{key}")
                        self.assertRegex(child, AMOUNT_PATTERN, f"{path}.{key}")
                self._assert_exact_amount_strings(child, f"{path}.{key}")
        elif isinstance(value, list):
            for index, child in enumerate(value):
                self._assert_exact_amount_strings(child, f"{path}[{index}]")

    def _derive_report(self, state):
        report = {
            "ordinary_income": Decimal("0.00"),
            "special_non_cash_bonus_income": Decimal("0.00"),
            "ordinary_expense": Decimal("0.00"),
            "expiry_loss": Decimal("0.00"),
            "consumption": Decimal("0.00"),
            "budget_effect": Decimal("0.00"),
            "category_effect": Decimal("0.00"),
            "cash_inflow": Decimal("0.00"),
            "cash_outflow": Decimal("0.00"),
            "net_worth_change": Decimal("0.00"),
        }
        for transaction in state["transactions"]:
            if transaction["type"] == "stored_value_recharge":
                paid = Decimal(transaction["paid_amount"])
                bonus = Decimal(transaction["bonus_amount"])
                report["cash_outflow"] += paid
                report["special_non_cash_bonus_income"] += bonus
                report["net_worth_change"] += bonus
            elif transaction["type"] == "stored_value_spend":
                amount = Decimal(transaction["amount"])
                report["ordinary_expense"] += amount
                report["consumption"] += amount
                report["category_effect"] += amount
                report["net_worth_change"] -= amount
            elif transaction["type"] == "stored_value_expiry_loss":
                amount = Decimal(transaction["confirmed_expired_amount"])
                report["expiry_loss"] += amount
                report["net_worth_change"] -= amount
            elif transaction["type"] == "stored_value_pre_activation_balance_adjustment":
                report["net_worth_change"] += Decimal(transaction["existing_balance"])
        return {key: f"{value:.2f}" for key, value in report.items()}

    def test_envelope_catalog_and_all_formal_amounts_are_exact(self):
        validate_case_envelope(self.case)
        self.assertEqual(self.case["case"]["id"], "RG-10")
        self.assertEqual(self.case["case"]["level"], "core_required")
        stored = next(item for item in self.accounts if item["id"] == "asset-stored-value-x")
        self.assertTrue(stored["owned_by_user"])
        self.assertTrue(stored["restricted"])
        self.assertTrue(stored["stored_value"])
        self.assertTrue(stored["enabled"])
        self.assertEqual(
            next(item for item in self.accounts if item["id"] == "income-special-bonus-rg10")["system_role"],
            "stored_value_bonus_right_income",
        )
        for state in self.states.values():
            self._assert_exact_amount_strings(state)
        for section in ("main_path", "reconciliation_path", "import_path", "secondary_cases"):
            self._assert_exact_amount_strings(self.case[section], section)

        numeric_cases = {
            item["id"]: item for item in self.case["invalid_inputs"]
            if any(isinstance(value, (int, float)) and not isinstance(value, bool) for value in item["input"].values())
        }
        self.assertEqual(set(numeric_cases), {"float-amount", "numeric-credited-amount", "numeric-bonus-amount"})
        for invalid in numeric_cases.values():
            with self.subTest(invalid=invalid["id"]), self.assertRaises(AssertionError):
                self._assert_exact_amount_strings(invalid["input"], invalid["id"])

        mutation_roots = {
            "canonical_states": self.states,
            "main_path": self.case["main_path"],
            "import_path": self.case["import_path"],
            "secondary_cases": self.case["secondary_cases"],
        }

        def amount_paths(value, path=()):
            if isinstance(value, dict):
                for key, child in value.items():
                    if self._is_amount_field(key, child) and isinstance(child, str):
                        yield (*path, key)
                    yield from amount_paths(child, (*path, key))
            elif isinstance(value, list):
                for index, child in enumerate(value):
                    yield from amount_paths(child, (*path, index))

        for root_name, root in mutation_roots.items():
            for path in amount_paths(root):
                mutated = deepcopy(root)
                target = mutated
                for component in path[:-1]:
                    target = target[component]
                target[path[-1]] = float(target[path[-1]])
                with self.subTest(root=root_name, path=path), self.assertRaises(AssertionError):
                    self._assert_exact_amount_strings(mutated, root_name)

        recharge = self.case["main_path"]["recharge"]["input"]
        for field in ("paid_amount", "credited_amount"):
            self.assertGreater(Decimal(recharge[field]), Decimal("0.00"))
        self.assertGreaterEqual(Decimal(recharge["bonus_amount"]), Decimal("0.00"))
        zero_bonus_lot = self.case["secondary_cases"]["multi_lot_allocation"]["base"]["lots"][1]
        self.assertEqual(zero_bonus_lot["bonus_amount"], "0.00")

    def test_recharge_is_balanced_and_has_only_special_bonus_income(self):
        state = self._state("recharge_confirmed")
        transaction = state["transactions"][0]
        validate_transactions(self._transactions(state), self.accounts)
        self.assertEqual(transaction["type"], "stored_value_recharge")
        self.assertEqual(
            [(item["account_id"], item["amount"]) for item in transaction["postings"]],
            [
                ("asset-stored-value-x", "1200.00"),
                ("asset-bank-a", "-1000.00"),
                ("income-special-bonus-rg10", "-200.00"),
            ],
        )
        self.assertEqual(state["reports"]["cumulative"]["cash_outflow"], "1000.00")
        self.assertEqual(state["reports"]["cumulative"]["consumption"], "0.00")
        self.assertEqual(state["reports"]["cumulative"]["ordinary_income"], "0.00")
        self.assertEqual(state["reports"]["cumulative"]["special_non_cash_bonus_income"], "200.00")
        self.assertEqual(state["reports"]["cumulative"]["net_worth_change"], "200.00")
        self.assertEqual(state["lots"][0]["remaining_face_value"], "1200.00")
        self.assertEqual([item["event"] for item in state["lots"][0]["history"]], ["loaded"])
        self.assertEqual(
            self.case["main_path"]["recharge"]["expected"]["report_delta"],
            {
                "ordinary_income": "0.00",
                "special_non_cash_bonus_income": "200.00",
                "ordinary_expense": "0.00",
                "consumption": "0.00",
                "budget_effect": "0.00",
                "category_effect": "0.00",
                "cash_inflow": "0.00",
                "cash_outflow": "1000.00",
                "net_worth_change": "200.00",
            },
        )

    def test_replay_balances_and_reports_are_derived_for_every_canonical_state(self):
        expected_balances = {
            "opening": {"asset-bank-a": "5000.00", "asset-stored-value-x": "0.00"},
            "activation_boundary": {"asset-bank-a": "5000.00", "asset-stored-value-x": "600.00"},
            "recharge_confirmed": {"asset-bank-a": "4000.00", "asset-stored-value-x": "1200.00"},
            "spend_confirmed": {"asset-bank-a": "4000.00", "asset-stored-value-x": "900.00"},
            "expiry_confirmed": {"asset-bank-a": "4000.00", "asset-stored-value-x": "800.00"},
        }
        for name, state in self.states.items():
            with self.subTest(state=name):
                validate_transactions(self._transactions(state), self.accounts)
                assert_expected_balances(replay_balances(self._transactions(state)), state["balances"])
                self.assertEqual(state["reports"]["cumulative"], self._derive_report(state))
                for account_id, amount in expected_balances[name].items():
                    self.assertEqual(state["balances"][account_id], amount)
                self._assert_state_references(state)

    def test_spend_consumes_face_value_once_and_does_not_reuse_cash_flow(self):
        state = self._state("spend_confirmed")
        spend = next(item for item in state["transactions"] if item["type"] == "stored_value_spend")
        self.assertEqual(spend["amount"], "300.00")
        self.assertEqual(spend["allocation_source"], "default_expiry_load_id_order")
        self.assertEqual(spend["composition_status"], "unknown")
        self.assertEqual(spend["lot_consumptions"][0]["paid_bonus_composition"], "unknown")
        self.assertEqual(state["balances"]["asset-stored-value-x"], "900.00")
        report = state["reports"]["cumulative"]
        self.assertEqual(report["consumption"], "300.00")
        self.assertEqual(report["ordinary_expense"], "300.00")
        self.assertEqual(report["budget_effect"], "0.00")
        self.assertEqual(report["category_effect"], "300.00")
        self.assertEqual(report["cash_outflow"], "1000.00")
        self.assertEqual(report["special_non_cash_bonus_income"], "200.00")
        self.assertEqual(
            self.case["main_path"]["spend"]["expected"]["report_delta"]["special_non_cash_bonus_income"],
            "0.00",
        )
        self.assertEqual(self.case["main_path"]["spend"]["expected"]["report_delta"]["cash_outflow"], "0.00")
        self.assertEqual(
            [item["id"] for item in state["source_records"]],
            ["source-bank-payment-rg10", "source-merchant-credit-rg10"],
        )
        self.assertEqual(
            [item["id"] for item in state["evidence"]],
            ["evidence-bank-payment-rg10", "evidence-merchant-credit-rg10"],
        )
        spend_input = self.case["main_path"]["spend"]["input"]
        for field in (
            "confirms_model",
            "confirms_behavior",
            "confirms_stored_value_account",
            "confirms_amount",
            "confirms_actual_time",
            "confirms_category",
            "confirms_lot_allocation",
        ):
            self.assertTrue(spend_input[field])

    def test_expiry_reminder_is_zero_effect_and_confirmation_is_a_loss_event(self):
        reminder = self.case["main_path"]["expiry_reminder"]
        self.assertEqual(reminder["expected"]["resulting_state_id"], "state-rg10-spend-confirmed")
        self.assertEqual(reminder["expected"]["formal_deltas"], {
            "new_transaction_count": 0,
            "new_posting_count": 0,
            "new_lot_consumption_count": 0,
            "new_report_effect_count": 0,
        })
        expiry = self._state("expiry_confirmed")
        transaction = next(item for item in expiry["transactions"] if item["type"] == "stored_value_expiry_loss")
        self.assertEqual(transaction["confirmed_expired_amount"], "100.00")
        self.assertEqual(
            [(item["account_id"], item["amount"]) for item in transaction["postings"]],
            [("expense-expiry-loss-rg10", "100.00"), ("asset-stored-value-x", "-100.00")],
        )
        self.assertEqual(expiry["reports"]["cumulative"]["cash_inflow"], "0.00")
        self.assertEqual(expiry["reports"]["cumulative"]["cash_outflow"], "1000.00")
        self.assertEqual(expiry["reports"]["cumulative"]["expiry_loss"], "100.00")
        self.assertEqual(expiry["reports"]["cumulative"]["budget_effect"], "0.00")
        self.assertEqual(expiry["balances"]["asset-stored-value-x"], "800.00")
        self.assertEqual(expiry["reconciliation"]["transaction-expiry-rg10"], "pending_financial_evidence")
        self.assertEqual(expiry["reconciliation"]["posting-stored-expiry-rg10"], "pending")
        expiry_link = next(item for item in expiry["evidence_links"] if item["id"] == "evidence-link-expiry-rg10")
        self.assertEqual(expiry_link["target_id"], "transaction-expiry-rg10")
        self.assertEqual(expiry_link["status"], "pending")

    def test_multi_lot_default_order_is_deterministic_without_composition_inference(self):
        case = self.case["secondary_cases"]["multi_lot_allocation"]
        result = case["expected"]
        self.assertEqual(
            result["allocation_order"],
            [
                "lot-rg10-expiring-first",
                "lot-rg10-loaded-first",
                "lot-rg10-stable-first",
                "lot-rg10-stable-second",
            ],
        )
        self.assertEqual([item["amount"] for item in result["consumptions"]], ["500.00", "100.00", "100.00", "100.00"])
        self.assertEqual(
            {item["paid_bonus_composition"] for item in result["consumptions"]},
            {"unknown"},
        )
        self.assertEqual(result["remaining_effective_balance"], "0.00")
        self.assertNotIn("paid_first", result["allocation_order"])
        self.assertEqual(result["forbidden_inference"], ["paid_first", "bonus_first"])
        lots = {item["id"]: item for item in case["base"]["lots"]}
        self.assertLess(lots["lot-rg10-expiring-first"]["expires_at"], lots["lot-rg10-loaded-first"]["expires_at"])
        self.assertEqual(lots["lot-rg10-loaded-first"]["expires_at"], lots["lot-rg10-stable-first"]["expires_at"])
        self.assertLess(lots["lot-rg10-loaded-first"]["loaded_at"], lots["lot-rg10-stable-first"]["loaded_at"])
        self.assertEqual(lots["lot-rg10-stable-first"]["expires_at"], lots["lot-rg10-stable-second"]["expires_at"])
        self.assertEqual(lots["lot-rg10-stable-first"]["loaded_at"], lots["lot-rg10-stable-second"]["loaded_at"])
        self.assertLess("lot-rg10-stable-first", "lot-rg10-stable-second")

        merchant = self.case["secondary_cases"]["merchant_evidenced_allocation"]
        baseline = self._state_by_id(merchant["pre_operation_baseline_id"])
        allocated = self._state_by_id(merchant["expected"]["resulting_state_id"])
        self.assertTrue(merchant["input"]["merchant_allocation_provided"])
        self.assertEqual(merchant["expected"]["allocation_source"], "merchant_evidence")
        self.assertEqual(merchant["expected"]["consumptions"][0]["lot_id"], "lot-rg10-loaded-first")
        self.assertTrue(merchant["expected"]["default_order_overridden"])

        baseline_sources = {item["id"]: item for item in baseline["source_records"]}
        baseline_evidence = {item["id"]: item for item in baseline["evidence"]}
        baseline_lots = {item["id"]: item for item in baseline["lots"]}
        evidence = baseline_evidence[merchant["input"]["merchant_evidence_id"]]
        self.assertIn(evidence["source_id"], baseline_sources)
        for requested in merchant["input"]["allocations"]:
            self.assertIn(requested["lot_id"], baseline_lots)
        self.assertEqual(baseline["allocations"], [])
        self.assertEqual(baseline["consumptions"], [])

        self.assertEqual(allocated["source_records"], baseline["source_records"])
        self.assertEqual(allocated["evidence"], baseline["evidence"])
        allocated_sources = {item["id"]: item for item in allocated["source_records"]}
        allocated_evidence = {item["id"]: item for item in allocated["evidence"]}
        allocated_lots = {item["id"]: item for item in allocated["lots"]}
        allocations = {item["id"]: item for item in allocated["allocations"]}
        consumptions = {item["id"]: item for item in allocated["consumptions"]}
        for allocation in allocations.values():
            self.assertIn(allocation["source_id"], allocated_sources)
            self.assertIn(allocation["evidence_id"], allocated_evidence)
            self.assertIn(allocation["lot_id"], allocated_lots)
            self.assertIn(allocation["consumption_id"], consumptions)
        for consumption in consumptions.values():
            self.assertIn(consumption["source_id"], allocated_sources)
            self.assertIn(consumption["evidence_id"], allocated_evidence)
            self.assertIn(consumption["lot_id"], allocated_lots)
            self.assertIn(consumption["allocation_id"], allocations)
        self.assertIn(merchant["expected"]["allocation_id"], allocations)
        self.assertEqual(merchant["expected"]["consumptions"], allocated["consumptions"])
        self.assertEqual(
            merchant["expected"]["returned_stable_ids"],
            [*allocations, *consumptions],
        )
        self.assertEqual(allocated_lots["lot-rg10-loaded-first"]["remaining_face_value"], "0.00")
        self.assertEqual(
            sum(Decimal(lot["remaining_face_value"]) for lot in allocated_lots.values()),
            Decimal(merchant["expected"]["remaining_effective_balance"]),
        )
        retry = self.case["idempotency"]["merchant_allocation_retry"]
        self.assertEqual(retry["pre_operation_baseline_id"], allocated["id"])
        self.assertEqual(retry["expected"]["resulting_state_id"], allocated["id"])
        self.assertEqual(
            retry["expected"]["returned_stable_ids"],
            [*allocations, *consumptions],
        )

    def test_account_and_lot_rename_preserve_stable_ids_and_all_economic_results(self):
        rename = self.case["secondary_cases"]["rename_zero_effect"]
        self.assertEqual(rename["pre_operation_baseline_id"], rename["expected"]["resulting_state_id"])
        self.assertEqual(rename["expected"]["stable_account_id"], "asset-stored-value-x")
        self.assertEqual(rename["expected"]["stable_lot_id"], "lot-rg10-20260110-a")
        for field in (
            "new_transaction_count",
            "new_posting_count",
            "balance_change_count",
            "report_change_count",
            "reconciliation_change_count",
        ):
            self.assertEqual(rename["expected"][field], 0)

    def test_activation_boundary_is_an_adjustment_and_reconstruction_replaces_it(self):
        operation = self.case["secondary_cases"]["activation_boundary"]
        expected = operation["expected"]
        state = self._state("activation_boundary")
        transaction = state["transactions"][0]
        validate_transactions(self._transactions(state), self.accounts)
        assert_expected_balances(replay_balances(self._transactions(state)), state["balances"])
        self.assertEqual(transaction["type"], "stored_value_pre_activation_balance_adjustment")
        self.assertEqual(
            [(posting["account_id"], posting["amount"]) for posting in transaction["postings"]],
            [("asset-stored-value-x", "600.00"), ("equity-stored-value-adjustment-rg10", "-600.00")],
        )
        self.assertEqual(
            [transaction[field] for field in ("occurred_at", "statistics_at", "effective_at")],
            [operation["input"]["activation_at"]] * 3,
        )
        self.assertEqual(transaction["created_at"], operation["input"]["created_at"])
        confirmation = state["confirmations"][0]
        self.assertTrue(confirmation["explicit_confirmation"])
        self.assertEqual(confirmation["source_id"], state["source_records"][0]["id"])
        self.assertEqual(confirmation["evidence_id"], state["evidence"][0]["id"])
        self.assertEqual(confirmation["audit_link_id"], state["audit_links"][0]["id"])
        ordinary_zero_fields = (
            "ordinary_income", "special_non_cash_bonus_income", "ordinary_expense", "expiry_loss",
            "consumption", "budget_effect", "category_effect", "cash_inflow", "cash_outflow",
        )
        report = state["reports"]["cumulative"]
        self.assertTrue(all(report[field] == "0.00" for field in ordinary_zero_fields))
        self.assertEqual(report["net_worth_change"], "600.00")
        self.assertEqual(expected["replacement_semantics"]["mode"], "replace_not_append")
        self.assertEqual(
            expected["replacement_semantics"]["active_effect_rule"],
            "exactly_one_of_adjustment_or_reconstructed_history",
        )
        self.assertTrue(expected["replacement_semantics"]["preserve_adjustment_transaction_and_version"])
        self.assertEqual(expected["replacement_semantics"]["full_reconstruction_algorithm"], "out_of_scope")
        self.assertFalse(expected["double_counting"])

    def test_evidence_roles_support_independent_partial_and_complete_reconciliation(self):
        partial = self.case["reconciliation_states"]["state-rg10-recharge-merchant-reconciled"]
        complete = self.case["reconciliation_states"]["state-rg10-recharge-fully-reconciled"]
        self.assertEqual(partial["reconciliation"]["transaction-recharge-rg10"], "partial")
        self.assertEqual(partial["reconciliation"]["posting-stored-recharge-rg10"], "matched")
        self.assertEqual(partial["reconciliation"]["posting-bank-recharge-rg10"], "pending")
        self.assertEqual(complete["reconciliation"]["transaction-recharge-rg10"], "complete")
        self.assertTrue(partial["balances_unchanged"])
        self.assertTrue(partial["reports_unchanged"])
        roles = {link["role"] for link in self._state("recharge_confirmed")["evidence_links"]}
        self.assertEqual(roles, {
            "bank_payment_posting",
            "stored_value_credit_lot",
            "stored_value_bonus_component",
        })
        self.assertNotEqual("bank_payment_posting", "stored_value_credit_lot")

    def test_complete_imports_stay_pending_with_zero_formal_effect(self):
        operations = self.case["import_path"]["complete_unconfirmed"]
        self.assertEqual({item["id"] for item in operations}, {
            "import-recharge-complete-unconfirmed-rg10",
            "import-spend-complete-unconfirmed-rg10",
        })
        for operation in operations:
            with self.subTest(operation=operation["id"]):
                self.assertTrue(operation["input"]["all_facts_complete"])
                self.assertFalse(operation["input"]["explicit_confirmation"])
                self.assertTrue(operation["expected"]["accepted"])
                self.assertEqual(operation["expected"]["status"], "pending_confirmation")
                self.assertEqual(operation["expected"]["formal_deltas"], ZERO_FORMAL_DELTAS)
                self.assertEqual(operation["expected"]["reconciliation_deltas"], ZERO_RECONCILIATION_DELTAS)
                baseline = self._state_by_id(operation["pre_operation_baseline_id"])
                result = self._state_by_id(operation["expected"]["resulting_state_id"])
                self.assertEqual(result["formal_state_id"], baseline["id"])
                self.assertEqual(self._state_by_id(result["formal_state_id"]), baseline)
                self.assertEqual(len(result["candidates"]), operation["expected"]["intake_deltas"]["new_candidate_count"])
                self.assertEqual(len(result["source_records"]), operation["expected"]["intake_deltas"]["new_source_record_count"])
                self.assertEqual(len(result["evidence"]), operation["expected"]["intake_deltas"]["new_evidence_count"])
                self.assertEqual(len(result["evidence_links"]), operation["expected"]["intake_deltas"]["new_evidence_link_count"])
                self.assertEqual(len(result["audit_links"]), operation["expected"]["intake_deltas"]["new_audit_link_count"])
                self.assertEqual(result["candidates"][0]["id"], operation["expected"]["pending_candidate_id"])

    def test_incomplete_import_confirmations_are_atomic(self):
        for operation in self.case["import_path"]["incomplete_confirmations"]:
            with self.subTest(operation=operation["id"]):
                self.assertFalse(operation["expected"]["accepted"])
                self.assertEqual(operation["expected"]["status"], "pending_confirmation")
                self.assertTrue(operation["expected"]["state_unchanged"])
                self.assertEqual(operation["expected"]["resulting_state_id"], operation["pre_operation_baseline_id"])
                self.assertEqual(operation["expected"]["formal_deltas"], ZERO_FORMAL_DELTAS)
                self.assertEqual(operation["expected"]["intake_deltas"], ZERO_INTAKE_DELTAS)
                self.assertEqual(operation["expected"]["reconciliation_deltas"], ZERO_RECONCILIATION_DELTAS)
                self.assertEqual(
                    self._state_by_id(operation["pre_operation_baseline_id"]),
                    self._state_by_id(operation["expected"]["resulting_state_id"]),
                )

    def test_every_registered_retry_is_fully_idempotent(self):
        retries = list(self.case["idempotency"].values())
        self.assertEqual({operation["input_id"] for operation in retries}, set(self.case["request_registry"]))
        complete_imports = self.case["import_path"]["complete_unconfirmed"]
        expected_ids = {
            "request-recharge-rg10": ["transaction-recharge-rg10", "lot-rg10-20260110-a"],
            "request-spend-rg10": ["transaction-spend-rg10"],
            "request-expiry-rg10": ["transaction-expiry-rg10"],
            "request-reminder-rg10": ["request-reminder-rg10"],
            "source-merchant-credit-rg10": ["evidence-link-merchant-recharge-rg10"],
            "source-bank-payment-rg10": ["evidence-link-bank-recharge-rg10"],
            "request-import-recharge-rg10": [complete_imports[0]["expected"]["pending_candidate_id"]],
            "request-import-spend-rg10": [complete_imports[1]["expected"]["pending_candidate_id"]],
            "request-activation-rg10": [
                self._state("activation_boundary")["transactions"][0]["id"],
                self._state("activation_boundary")["adjustments"][0]["id"],
                self._state("activation_boundary")["confirmations"][0]["id"],
            ],
            "request-merchant-allocation-rg10": [
                *[
                    item["id"]
                    for item in self._state_by_id(
                        self.case["idempotency"]["merchant_allocation_retry"]["pre_operation_baseline_id"]
                    )["allocations"]
                ],
                *[
                    item["id"]
                    for item in self._state_by_id(
                        self.case["idempotency"]["merchant_allocation_retry"]["pre_operation_baseline_id"]
                    )["consumptions"]
                ],
            ],
        }
        for operation in retries:
            with self.subTest(input_id=operation["input_id"]):
                self.assertEqual(operation["pre_operation_baseline_id"], operation["expected"]["resulting_state_id"])
                self.assertEqual(operation["expected"]["formal_deltas"], ZERO_FORMAL_DELTAS)
                self.assertEqual(operation["expected"]["intake_deltas"], ZERO_INTAKE_DELTAS)
                self.assertEqual(operation["expected"]["reconciliation_deltas"], ZERO_RECONCILIATION_DELTAS)
                self.assertEqual(operation["expected"]["returned_stable_ids"], expected_ids[operation["input_id"]])
                self.assertEqual(
                    self._state_by_id(operation["pre_operation_baseline_id"]),
                    self._state_by_id(operation["expected"]["resulting_state_id"]),
                )

    def test_invalid_inputs_are_atomic_and_cover_reachable_forbidden_effects(self):
        reasons = {item["id"]: item["expected"]["reason"] for item in self.case["invalid_inputs"]}
        self.assertEqual(len(reasons), 20)
        self.assertEqual(reasons["numeric-credited-amount"], "exact_decimal_string_required")
        self.assertEqual(reasons["numeric-bonus-amount"], "exact_decimal_string_required")
        self.assertEqual(reasons["nonpositive-credited-amount"], "credited_amount_must_be_positive")
        self.assertEqual(reasons["negative-bonus-amount"], "bonus_amount_must_be_zero_or_positive")
        self.assertEqual(reasons["disabled-stored-account"], "stored_value_account_not_enabled")
        self.assertEqual(reasons["spend-over-balance"], "insufficient_effective_stored_balance")
        self.assertEqual(reasons["unconfirmed-expiry"], "actual_expiry_requires_explicit_confirmation")
        self.assertEqual(reasons["guessed-composition"], "paid_bonus_composition_must_be_evidenced")
        self.assertEqual(reasons["unknown-payment-account"], "unknown_payment_account")
        self.assertEqual(reasons["unowned-payment-account"], "owned_payment_asset_required")
        self.assertEqual(reasons["wrong-stored-account-kind"], "enabled_restricted_stored_value_asset_required")
        self.assertEqual(reasons["wrong-currency"], "same_cny_currency_required")
        self.assertIn("negative_stored_value_asset", self.case["forbidden_side_effects"])
        self.assertIn("hidden_clearing_posting", self.case["forbidden_side_effects"])
        self.assertIn("reconciliation_changes_balance", self.case["forbidden_side_effects"])
        for operation in self.case["invalid_inputs"]:
            with self.subTest(operation=operation["id"]):
                expected = operation["expected"]
                self.assertFalse(expected["accepted"])
                self.assertTrue(expected["state_unchanged"])
                self.assertEqual(operation["pre_operation_baseline_id"], expected["resulting_state_id"])
                self.assertEqual(expected["formal_deltas"], ZERO_FORMAL_DELTAS)
                self.assertEqual(expected["intake_deltas"], ZERO_INTAKE_DELTAS)
                self.assertEqual(expected["reconciliation_deltas"], ZERO_RECONCILIATION_DELTAS)
                baseline = deepcopy(self._state_by_id(operation["pre_operation_baseline_id"]))
                result = deepcopy(self._state_by_id(expected["resulting_state_id"]))
                self.assertEqual(result, baseline)
        invalid_lot = next(item for item in self.case["invalid_inputs"] if item["id"] == "invalid-lot-allocation")
        lot = self._state_by_id(invalid_lot["pre_operation_baseline_id"])["lots"][0]
        self.assertEqual(lot["remaining_face_value"], "900.00")
        self.assertGreater(Decimal(invalid_lot["input"]["amount"]), Decimal(lot["remaining_face_value"]))

    def test_canonical_history_is_append_only(self):
        transitions = [
            (self._state("recharge_confirmed"), self._state("spend_confirmed")),
            (self._state("spend_confirmed"), self._state("expiry_confirmed")),
        ]
        for before, after in transitions:
            with self.subTest(before=before["id"], after=after["id"]):
                for key in (
                    "transactions", "versions", "confirmations", "source_records", "evidence",
                    "evidence_links", "audit_links",
                ):
                    self.assertEqual(after[key][:len(before[key])], before[key], key)
                self.assertEqual([lot["id"] for lot in after["lots"][:len(before["lots"])]], [lot["id"] for lot in before["lots"]])
                for old_lot, new_lot in zip(before["lots"], after["lots"]):
                    for key in (
                        "id", "recharge_transaction_id", "loaded_at", "expires_at", "face_value",
                        "paid_amount", "bonus_amount", "merchant_id",
                    ):
                        self.assertEqual(new_lot[key], old_lot[key])
                    self.assertEqual(new_lot["history"][:len(old_lot["history"])], old_lot["history"])

        mutated = deepcopy(self._state("expiry_confirmed"))
        mutated["transactions"][0]["paid_amount"] = "999.00"
        self.assertNotEqual(
            mutated["transactions"][:len(self._state("spend_confirmed")["transactions"])],
            self._state("spend_confirmed")["transactions"],
        )

    def test_nested_reference_mutations_are_detected(self):
        mutated = deepcopy(self._state("spend_confirmed"))
        mutated["transactions"][1]["lot_consumptions"][0]["lot_id"] = "lot-missing"
        with self.assertRaises(AssertionError):
            self._assert_state_references(mutated)

        mutated = deepcopy(self._state("recharge_confirmed"))
        mutated["evidence_links"][0]["target_id"] = "posting-missing"
        with self.assertRaises(AssertionError):
            self._assert_state_references(mutated)

        mutated = deepcopy(self._state("recharge_confirmed"))
        mutated["confirmations"][0]["transaction_id"] = "transaction-missing"
        with self.assertRaises(AssertionError):
            self._assert_state_references(mutated)

        mutated = deepcopy(self._state("activation_boundary"))
        mutated["audit_links"][0]["confirmation_id"] = "confirmation-missing"
        with self.assertRaises(AssertionError):
            self._assert_state_references(mutated)

    def test_operation_contexts_and_economic_creation_times_are_explicit(self):
        for operation in (
            self.case["main_path"]["recharge"],
            self.case["main_path"]["spend"],
            self.case["main_path"]["expiry_confirmation"],
            self.case["secondary_cases"]["activation_boundary"],
            self.case["secondary_cases"]["merchant_evidenced_allocation"],
        ):
            self.assertEqual(operation["operation_context"]["baseline_id"], operation["pre_operation_baseline_id"])
            self.assertEqual(operation["operation_context"]["result_id"], operation["expected"]["resulting_state_id"])
        recharge = self._state("recharge_confirmed")["transactions"][0]
        self.assertLess(recharge["occurred_at"], recharge["created_at"])
        expiry = next(item for item in self._state("expiry_confirmed")["transactions"] if item["type"] == "stored_value_expiry_loss")
        self.assertLess(expiry["occurred_at"], expiry["created_at"])

    def test_all_named_operation_baselines_and_results_resolve(self):
        known_state_ids = {state["id"] for state in self.states.values()}
        known_state_ids.update(self.case["reconciliation_states"])
        known_state_ids.update(state["id"] for state in self.case["import_path"]["pending_states"].values())
        known_state_ids.update(
            state["id"]
            for state in self.case["secondary_cases"]["merchant_evidenced_allocation"]["states"].values()
        )
        operations = [
            *self.case["main_path"].values(),
            *self.case["reconciliation_path"].values(),
            *self.case["import_path"]["complete_unconfirmed"],
            *self.case["import_path"]["incomplete_confirmations"],
            *self.case["idempotency"].values(),
            self.case["secondary_cases"]["rename_zero_effect"],
            self.case["secondary_cases"]["activation_boundary"],
            self.case["secondary_cases"]["merchant_evidenced_allocation"],
        ]
        for operation in operations:
            with self.subTest(operation=operation.get("id", operation.get("input_id"))):
                self.assertIn(operation["pre_operation_baseline_id"], known_state_ids)
                self.assertIn(operation["expected"]["resulting_state_id"], known_state_ids)


if __name__ == "__main__":
    unittest.main()
