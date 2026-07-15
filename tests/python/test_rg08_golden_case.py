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
RG08_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-08.json"
AMOUNT_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
ZERO_EFFECT_COUNTS = {
    "new_candidate_count": 0,
    "new_position_count": 0,
    "new_settlement_count": 0,
    "new_component_count": 0,
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_version_count": 0,
    "new_history_count": 0,
    "new_source_record_count": 0,
    "new_evidence_link_count": 0,
    "balance_change_count": 0,
    "report_change_count": 0,
    "reconciliation_change_count": 0,
    "consumption_effect_count": 0,
    "cash_flow_effect_count": 0,
    "income_effect_count": 0,
}
FORMAL_ZERO_COUNTS = {
    "new_position_event_count": 0,
    "new_settlement_count": 0,
    "new_component_count": 0,
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_version_count": 0,
    "balance_change_count": 0,
    "report_change_count": 0,
    "reconciliation_change_count": 0,
    "consumption_effect_count": 0,
    "cash_flow_effect_count": 0,
    "income_effect_count": 0,
}
PENDING_INTAKE_DELTAS = {
    "new_candidate_count": 1,
    "new_candidate_history_count": 1,
    "new_source_record_count": 2,
    "new_evidence_count": 2,
    "new_evidence_link_count": 1,
}
ZERO_DUPLICATE_COUNTS = {
    "new_candidate_count": 0,
    "new_position_count": 0,
    "new_settlement_count": 0,
    "new_component_count": 0,
    "new_transaction_count": 0,
    "new_posting_count": 0,
    "new_version_count": 0,
    "new_history_count": 0,
    "new_source_record_count": 0,
    "new_evidence_count": 0,
    "new_evidence_link_count": 0,
}
STATE_KEYS = {
    "id",
    "transactions",
    "versions",
    "positions",
    "settlements",
    "candidates",
    "confirmation_provenance",
    "source_records",
    "evidence",
    "evidence_links",
    "balances",
    "reports",
    "reconciliation",
}


def assert_exact_money(test_case, value, path="amount"):
    test_case.assertIsInstance(value, str, path)
    test_case.assertRegex(value, AMOUNT_PATTERN, path)


class RG08GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG08_PATH)
        cls.accounts = cls.case["catalog"]["accounts"]
        cls.lend = cls.case["lend"]
        cls.manual = cls.case["manual_collection"]
        cls.import_path = cls.case["import_collection"]

    def test_fixture_enforces_schema_catalog_and_frozen_behavior_codes(self):
        validate_case_envelope(self.case)
        self.assertEqual(
            self.case["case"],
            {
                "id": "RG-08",
                "level": "core_required",
                "rule_version": 1,
                "timezone": "Asia/Shanghai",
                "currency": "CNY",
                "precision": 2,
                "ledger_id": "ledger-a",
            },
        )
        self.assertEqual(
            [item["code"] for item in self.case["behavior_codes"]],
            ["borrow", "lend", "collect", "repay"],
        )
        self.assertEqual(len({item["code"] for item in self.case["behavior_codes"]}), 4)

        accounts = {item["id"]: item for item in self.accounts}
        self.assertEqual(accounts["asset-bank-a"]["type"], "asset")
        self.assertTrue(accounts["asset-bank-a"]["owned_by_user"])
        self.assertTrue(accounts["asset-wallet-b"]["owned_by_user"])
        self.assertEqual(accounts["receivable-counterparty-rg08"]["position_kind"], "lending_receivable")
        self.assertEqual(accounts["receivable-counterparty-rg08"]["counterparty_id"], "counterparty-rg08")
        self.assertFalse(accounts["receivable-counterparty-rg08"]["reconciliation_eligible"])
        self.assertEqual(accounts["income-interest-rg08"]["type"], "income")

        category = self.case["catalog"]["interest_categories"][0]
        self.assertEqual(category["id"], "income-category-interest-rg08")
        self.assertEqual(category["account_id"], "income-interest-rg08")
        self.assertEqual(category["kind"], "interest_income")
        self.assertTrue(category["active"])

        for path, amount in self._all_amounts(self.case):
            assert_exact_money(self, amount, path)

    def test_lend_creates_balanced_receivable_and_principal_cash_outflow(self):
        transaction = self.lend["expected"]["transaction"]
        self.assertEqual(transaction["behavior_code"], "lend")
        self.assertEqual(transaction["counterparty_id"], "counterparty-rg08")
        self.assertEqual(
            transaction["postings"],
            [
                {
                    "id": "posting-lend-receivable-rg08",
                    "account_id": "receivable-counterparty-rg08",
                    "amount": "100.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
                {
                    "id": "posting-lend-asset-rg08",
                    "account_id": "asset-bank-a",
                    "amount": "-100.00",
                    "currency": "CNY",
                    "reconciliation_eligible": True,
                },
            ],
        )
        transactions = [*self.case["opening"]["transactions"], transaction]
        validate_transactions(transactions, self.accounts)
        replay = replay_balances(transactions)
        self.assertEqual(replay["asset-bank-a"], Decimal("900.00"))
        self.assertEqual(replay["asset-wallet-b"], Decimal("200.00"))
        self.assertEqual(replay["receivable-counterparty-rg08"], Decimal("100.00"))
        assert_expected_balances(replay, self.lend["expected"]["balances"])

        self.assertEqual(
            self.lend["expected"]["reports"]["2026-01"],
            {
                "consumption": "0.00",
                "expense": "0.00",
                "lending_principal_cash_outflow": "100.00",
                "cash_outflow": "100.00",
                "lending_principal_cash_inflow": "0.00",
                "interest_cash_inflow": "0.00",
                "total_cash_inflow": "0.00",
                "ordinary_interest_income": "0.00",
                "ordinary_income": "0.00",
                "net_worth_change": "0.00",
            },
        )
        position = self.lend["expected"]["position"]
        self.assertEqual(position["id"], "lending-position-rg08")
        self.assertEqual(position["counterparty_id"], "counterparty-rg08")
        self.assertEqual(position["principal_balance"], "100.00")
        self.assertEqual(position["allocation_scope"], "person_level_net_position")
        self.assertFalse(position["contract_allocation_enabled"])

    def test_expected_interest_is_metadata_and_counterparty_rename_has_zero_effect(self):
        metadata = self.lend["expected_interest_metadata"]
        self.assertEqual(metadata["expected_interest"], "8.00")
        self.assertEqual(metadata["accrued_interest"], "0.00")
        self.assertEqual(metadata["formal_effects"], ZERO_EFFECT_COUNTS)
        self.assertEqual(metadata["income"], "0.00")
        self.assertEqual(metadata["net_worth_change"], "0.00")

        rename = self.case["counterparty_identity"]["rename"]
        self.assertEqual(rename["counterparty_id"], "counterparty-rg08")
        self.assertNotEqual(rename["old_display_name"], rename["new_display_name"])
        self.assertEqual(rename["expected"]["effect_counts"], ZERO_EFFECT_COUNTS)
        self.assertEqual(rename["expected"]["resulting_state"], rename["pre_operation_baseline"])
        self.assertEqual(self.case["counterparty_identity"]["duplicate_merge"], "out_of_scope_explicit_audited_operation")

    def test_collection_splits_principal_and_actual_interest_without_contract_allocation(self):
        expected = self.manual["expected"]
        transaction = expected["transaction"]
        self.assertEqual(transaction["behavior_code"], "collect")
        self.assertEqual(transaction["counterparty_id"], "counterparty-rg08")
        self.assertEqual(transaction["occurred_at"], "2026-02-15T14:30:00+08:00")
        self.assertEqual(transaction["statistics_at"], transaction["occurred_at"])
        self.assertEqual(
            transaction["postings"],
            [
                {
                    "id": "posting-collect-asset-rg08-manual",
                    "account_id": "asset-wallet-b",
                    "amount": "45.00",
                    "currency": "CNY",
                    "reconciliation_eligible": True,
                },
                {
                    "id": "posting-collect-principal-rg08-manual",
                    "account_id": "receivable-counterparty-rg08",
                    "amount": "-40.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
                {
                    "id": "posting-collect-interest-rg08-manual",
                    "account_id": "income-interest-rg08",
                    "amount": "-5.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
            ],
        )
        settlement = expected["settlement"]
        self.assertEqual(settlement["id"], "settlement-rg08-manual")
        self.assertEqual(settlement["linked_position_id"], "lending-position-rg08")
        self.assertIsNone(settlement["allocated_lend_transaction_id"])
        self.assertEqual(settlement["total_received"], "45.00")
        self.assertEqual(
            settlement["components"],
            [
                {
                    "id": "component-rg08-manual-principal",
                    "kind": "principal",
                    "amount": "40.00",
                    "posting_id": "posting-collect-principal-rg08-manual",
                },
                {
                    "id": "component-rg08-manual-interest",
                    "kind": "interest",
                    "amount": "5.00",
                    "posting_id": "posting-collect-interest-rg08-manual",
                },
                {
                    "id": "component-rg08-manual-fee",
                    "kind": "fee",
                    "amount": "0.00",
                    "posting_id": None,
                },
            ],
        )

        transactions = [
            *self.case["opening"]["transactions"],
            self.lend["expected"]["transaction"],
            transaction,
        ]
        validate_transactions(transactions, self.accounts)
        replay = replay_balances(transactions)
        self.assertEqual(replay["asset-bank-a"], Decimal("900.00"))
        self.assertEqual(replay["asset-wallet-b"], Decimal("245.00"))
        self.assertEqual(replay["receivable-counterparty-rg08"], Decimal("60.00"))
        self.assertEqual(replay["income-interest-rg08"], Decimal("-5.00"))
        assert_expected_balances(replay, expected["balances"])

    def test_collection_reports_cash_basis_principal_interest_and_net_worth(self):
        reports = self.manual["expected"]["reports"]
        self.assertEqual(
            reports["2026-02"],
            {
                "consumption": "0.00",
                "expense": "0.00",
                "lending_principal_cash_outflow": "0.00",
                "cash_outflow": "0.00",
                "lending_principal_cash_inflow": "40.00",
                "interest_cash_inflow": "5.00",
                "total_cash_inflow": "45.00",
                "ordinary_interest_income": "5.00",
                "ordinary_income": "5.00",
                "net_worth_change": "5.00",
            },
        )
        self.assertEqual(reports["cumulative"]["lending_principal_cash_outflow"], "100.00")
        self.assertEqual(reports["cumulative"]["lending_principal_cash_inflow"], "40.00")
        self.assertEqual(reports["cumulative"]["interest_cash_inflow"], "5.00")
        self.assertEqual(reports["cumulative"]["total_cash_inflow"], "45.00")
        self.assertEqual(reports["cumulative"]["ordinary_interest_income"], "5.00")
        self.assertEqual(reports["cumulative"]["consumption"], "0.00")
        self.assertEqual(reports["cumulative"]["net_worth_change"], "5.00")
        self.assertEqual(self.manual["expected"]["position"]["principal_balance"], "60.00")

    def test_principal_cap_rejects_atomically_and_never_auto_caps_or_guesses(self):
        cap = self.case["principal_cap"]
        maximum = cap["maximum_valid_collection"]
        self.assertEqual(
            maximum["input"],
            {
                "request_id": "request-rg08-cap-maximum",
                "behavior_code": "collect",
                "counterparty_id": "counterparty-rg08",
                "destination_account_id": "asset-wallet-b",
                "total_received": "105.00",
                "principal_amount": "100.00",
                "interest_amount": "5.00",
                "fee_amount": "0.00",
                "interest_category_id": "income-category-interest-rg08",
                "currency": "CNY",
                "actual_receipt_at": "2026-02-20T10:00:00+08:00",
                "confirmed_at": "2026-02-20T10:05:00+08:00",
            },
        )
        self.assertTrue(maximum["expected"]["accepted"])
        self.assertEqual(maximum["expected"]["position"]["principal_balance"], "0.00")
        validate_transactions([maximum["expected"]["transaction"]], self.accounts)

        over = cap["over_balance_attempt"]
        self.assertEqual(over["input"]["principal_amount"], "101.00")
        self.assertEqual(over["input"]["total_received"], "106.00")
        self.assertFalse(over["expected"]["accepted"])
        self.assertEqual(over["expected"]["reason"], "principal_exceeds_outstanding_position")
        self.assertEqual(over["expected"]["allocated_principal"], "0.00")
        self.assertEqual(over["expected"]["pending_total"], "106.00")
        self.assertIsNone(over["expected"]["fallback_event_type"])
        self.assertFalse(over["expected"]["auto_capped"])
        self.assertEqual(over["expected"]["candidate_status"], "pending_explicit_reallocation")

    def test_import_remains_pending_until_every_economic_fact_is_confirmed(self):
        expected = self.import_path["candidate"]["expected"]
        candidate = expected["candidate"]
        self.assertEqual(candidate["status"], "pending_confirmation")
        self.assertEqual(candidate["proposed_total_received"], "45.00")
        self.assertIsNone(candidate["proposed_principal_amount"])
        self.assertIsNone(candidate["proposed_interest_amount"])
        self.assertEqual(
            candidate["requires_confirmation"],
            [
                "behavior_code",
                "counterparty_id",
                "destination_account_id",
                "principal_amount",
                "interest_and_fee_amounts",
                "actual_receipt_time",
            ],
        )
        self.assertFalse(candidate["bank_evidence_proves_component_split"])
        self.assertFalse(candidate["expected_interest_may_confirm_split"])
        self.assertFalse(candidate["name_match_may_confirm_counterparty"])
        self.assertEqual(expected["formal_effect_counts"], FORMAL_ZERO_COUNTS)
        self.assertEqual(expected["intake_entity_deltas"], PENDING_INTAKE_DELTAS)
        resulting = expected["resulting_state"]
        baseline = self.case["canonical_states"]["lend_confirmed"]
        self.assertEqual(resulting, self.case["canonical_states"]["import_pending"])
        actual_deltas = {
            "new_candidate_count": len(resulting["candidates"]) - len(baseline["candidates"]),
            "new_candidate_history_count": sum(len(item["status_history"]) for item in resulting["candidates"]),
            "new_source_record_count": len(resulting["source_records"]) - len(baseline["source_records"]),
            "new_evidence_count": len(resulting["evidence"]) - len(baseline["evidence"]),
            "new_evidence_link_count": len(resulting["evidence_links"]) - len(baseline["evidence_links"]),
        }
        self.assertEqual(actual_deltas, PENDING_INTAKE_DELTAS)

    def test_import_has_exactly_six_field_specific_confirmation_gates(self):
        gates = self.import_path["incomplete_confirmations"]
        expected = [
            ("missing-behavior", "behavior_code", "behavior_confirmation_required"),
            ("missing-counterparty", "counterparty_id", "counterparty_confirmation_required"),
            ("missing-destination", "destination_account_id", "destination_confirmation_required"),
            ("missing-principal", "principal_amount", "principal_confirmation_required"),
            ("missing-interest-fees", "interest_and_fee_amounts", "interest_and_fee_confirmation_required"),
            ("missing-receipt-time", "actual_receipt_time", "actual_receipt_time_confirmation_required"),
        ]
        self.assertEqual(len(gates), 6)
        self.assertEqual(
            [(item["id"], item["input"]["missing_field"], item["expected"]["reason"]) for item in gates],
            expected,
        )
        self.assertEqual(len({item["id"] for item in gates}), 6)

        for attempt in gates:
            with self.subTest(attempt=attempt["id"]):
                self.assertEqual(attempt["expected"]["candidate_status"], "pending_confirmation")
                self.assertEqual(attempt["expected"]["effect_counts"], ZERO_EFFECT_COUNTS)
                self.assertEqual(attempt["expected"]["resulting_state"], attempt["pre_operation_baseline"])

    def test_import_confirmation_preserves_source_confirmation_and_component_history(self):
        expected = self.import_path["confirmation"]["expected"]
        self.assertEqual(expected["candidate_status"], "confirmed")
        self.assertEqual(expected["transaction"]["occurred_at"], "2026-02-15T14:30:00+08:00")
        self.assertEqual(expected["transaction"]["statistics_at"], "2026-02-15T14:30:00+08:00")
        self.assertEqual(expected["settlement"]["linked_position_id"], "lending-position-rg08")
        self.assertIsNone(expected["settlement"]["allocated_lend_transaction_id"])
        self.assertEqual(
            [item["id"] for item in expected["settlement"]["components"]],
            [
                "component-rg08-import-principal",
                "component-rg08-import-interest",
                "component-rg08-import-fee",
            ],
        )
        self.assertEqual(
            [item["status"] for item in expected["candidate_history"]],
            ["pending_confirmation", "confirmed"],
        )
        provenance = expected["provenance"]
        self.assertEqual(provenance["source_ids"], ["source-rg08-import-credit"])
        self.assertEqual(provenance["candidate_id"], "candidate-rg08-import-collection")
        self.assertEqual(provenance["confirmation_request_id"], "request-rg08-confirm-import")
        self.assertEqual(provenance["rule_version"], 1)
        self.assertEqual(provenance["original_source_payload_hash"], "sha256:rg08-synthetic-credit")

    def test_evidence_roles_are_typed_and_destination_reconciles_independently(self):
        roles = self.case["evidence_policy"]
        self.assertEqual(
            roles,
            {
                "bank_role": "destination_asset_posting",
                "agreement_role": "counterparty_lending_relationship",
                "bank_proves": ["amount", "destination_account_id", "booking_at", "value_at"],
                "bank_does_not_prove": ["behavior_code", "counterparty_id", "principal_amount", "interest_amount", "fee_amount"],
                "agreement_proves": ["counterparty_id", "lending_relationship"],
                "agreement_does_not_prove": ["destination_asset_posting"],
            },
        )
        state = self.case["canonical_states"]["import_confirmed"]
        links = {item["id"]: item for item in state["evidence_links"]}
        self.assertEqual(links["evidence-link-rg08-import-posting"]["target_id"], "posting-collect-asset-rg08-import")
        self.assertEqual(links["evidence-link-rg08-agreement"]["target_id"], "lending-position-rg08")
        self.assertNotEqual(links["evidence-link-rg08-agreement"]["target_id"], links["evidence-link-rg08-import-posting"]["target_id"])
        reconciliation = state["reconciliation"]
        self.assertEqual(reconciliation["posting-collect-asset-rg08-import"], "matched")
        self.assertEqual(reconciliation["posting-collect-principal-rg08-import"], "not_applicable")
        self.assertEqual(reconciliation["posting-collect-interest-rg08-import"], "not_applicable")
        self.assertEqual(reconciliation["transaction-collect-rg08-import"], "complete")

    def test_mirror_merge_and_all_retries_are_full_snapshot_idempotent(self):
        mirror = self.import_path["mirror_evidence"]["expected"]
        confirmed = self.case["canonical_states"]["import_confirmed"]
        complete = self.case["canonical_states"]["import_mirror_complete"]
        self.assertEqual(mirror["merged_into_transaction_id"], "transaction-collect-rg08-import")
        self.assertEqual(mirror["new_transaction_count"], 0)
        self.assertEqual(mirror["new_posting_count"], 0)
        self.assertEqual(mirror["new_version_count"], 0)
        self.assertEqual(mirror["new_source_record_count"], 1)
        self.assertEqual(mirror["new_evidence_link_count"], 1)
        self.assertEqual(mirror["resulting_state"], complete)
        self.assertEqual(complete["transactions"], confirmed["transactions"])
        self.assertEqual(complete["versions"], confirmed["versions"])
        self.assertEqual(complete["positions"], confirmed["positions"])
        self.assertEqual(complete["settlements"], confirmed["settlements"])
        self.assertEqual(complete["balances"], confirmed["balances"])
        self.assertEqual(complete["reports"], confirmed["reports"])
        self.assertEqual(complete["reconciliation"], confirmed["reconciliation"])

        idempotency = self.case["idempotency"]
        expected_inputs = [
            "request-rg08-lend",
            "source-rg08-lend-debit",
            "request-rg08-rename-counterparty",
            "request-rg08-manual-collection",
            "source-rg08-manual-confirmation",
            "source-rg08-manual-credit",
            "request-rg08-cap-maximum",
            "source-rg08-import-credit",
            "source-rg08-agreement",
            "request-rg08-confirm-import",
            "request-rg08-merge-import-mirror",
            "source-rg08-import-mirror",
        ]
        self.assertEqual(
            idempotency["retried_inputs"],
            expected_inputs,
        )
        self.assertNotIn("effect_counts", idempotency)
        self.assertNotIn("expected", idempotency)
        retries = idempotency["retries"]
        self.assertEqual([item["input_id"] for item in retries], expected_inputs)
        self.assertEqual(len({item["operation_context"]["baseline_id"] for item in retries}), len(retries))
        expected_returned_ids = {
            "request-rg08-lend": {
                "transaction_id": "transaction-lend-rg08",
                "version_id": "version-lend-rg08-v1",
                "position_id": "lending-position-rg08",
            },
            "source-rg08-lend-debit": {
                "source_record_id": "source-rg08-lend-debit",
                "evidence_id": "evidence-rg08-lend-debit",
                "evidence_link_id": "evidence-link-rg08-lend-debit",
                "target_posting_id": "posting-lend-asset-rg08",
            },
            "request-rg08-rename-counterparty": {
                "counterparty_id": "counterparty-rg08",
                "name_history_id": "history-counterparty-rg08-rename",
            },
            "request-rg08-manual-collection": {
                "transaction_id": "transaction-collect-rg08-manual",
                "version_id": "version-collect-rg08-manual-v1",
                "settlement_id": "settlement-rg08-manual",
                "component_ids": [
                    "component-rg08-manual-principal",
                    "component-rg08-manual-interest",
                    "component-rg08-manual-fee",
                ],
            },
            "source-rg08-manual-confirmation": {
                "source_record_id": "source-rg08-manual-confirmation",
                "transaction_id": "transaction-collect-rg08-manual",
                "settlement_id": "settlement-rg08-manual",
            },
            "source-rg08-manual-credit": {
                "source_record_id": "source-rg08-manual-credit",
                "evidence_id": "evidence-rg08-manual-credit",
                "evidence_link_id": "evidence-link-rg08-manual-credit",
                "target_posting_id": "posting-collect-asset-rg08-manual",
            },
            "request-rg08-cap-maximum": {
                "transaction_id": "transaction-collect-rg08-cap-maximum",
                "version_id": "version-collect-rg08-cap-maximum-v1",
                "settlement_id": "settlement-rg08-cap-maximum",
                "position_id": "lending-position-rg08",
            },
            "source-rg08-import-credit": {
                "source_record_id": "source-rg08-import-credit",
                "candidate_id": "candidate-rg08-import-collection",
            },
            "source-rg08-agreement": {
                "source_record_id": "source-rg08-agreement",
                "evidence_id": "evidence-rg08-agreement",
                "evidence_link_id": "evidence-link-rg08-agreement",
                "position_id": "lending-position-rg08",
            },
            "request-rg08-confirm-import": {
                "candidate_id": "candidate-rg08-import-collection",
                "transaction_id": "transaction-collect-rg08-import",
                "version_id": "version-collect-rg08-import-v1",
                "settlement_id": "settlement-rg08-import",
            },
            "request-rg08-merge-import-mirror": {
                "source_record_id": "source-rg08-import-mirror",
                "evidence_id": "evidence-rg08-import-mirror",
                "evidence_link_id": "evidence-link-rg08-import-mirror",
                "target_posting_id": "posting-collect-asset-rg08-import",
            },
            "source-rg08-import-mirror": {
                "source_record_id": "source-rg08-import-mirror",
                "evidence_id": "evidence-rg08-import-mirror",
                "evidence_link_id": "evidence-link-rg08-import-mirror",
                "target_posting_id": "posting-collect-asset-rg08-import",
            },
        }
        for retry in retries:
            with self.subTest(retry=retry["id"]):
                baseline = self.case["operation_baselines"][retry["operation_context"]["baseline_id"]]
                self.assertEqual(retry["pre_operation_baseline"], baseline)
                self.assertEqual(retry["expected"]["resulting_state"], baseline)
                self.assertEqual(retry["expected"]["duplicate_counts"], ZERO_DUPLICATE_COUNTS)
                self.assertEqual(retry["expected"]["formal_effect_counts"], FORMAL_ZERO_COUNTS)
                self.assertEqual(
                    retry["expected"]["returned_stable_ids"],
                    expected_returned_ids[retry["input_id"]],
                )

    def test_all_invalid_and_incomplete_operations_preserve_full_named_baseline(self):
        rejected = [
            *self.case["invalid_inputs"],
            *self.import_path["incomplete_confirmations"],
            self.case["principal_cap"]["over_balance_attempt"],
        ]
        baselines = self.case["operation_baselines"]
        for operation in rejected:
            with self.subTest(operation=operation["id"]):
                context = operation["operation_context"]
                self.assertIn(context["baseline_id"], baselines)
                baseline = baselines[context["baseline_id"]]
                self.assertEqual(set(baseline), STATE_KEYS)
                self.assertEqual(operation["pre_operation_baseline"], baseline)
                self.assertEqual(operation["expected"]["resulting_state"], baseline)
                self.assertEqual(operation["expected"]["effect_counts"], ZERO_EFFECT_COUNTS)
                self.assertTrue(operation["expected"]["state_unchanged"])

    def test_validation_reasons_and_no_guess_fallbacks_are_frozen(self):
        reasons = {item["id"]: item["expected"]["reason"] for item in self.case["invalid_inputs"]}
        self.assertEqual(
            reasons,
            {
                "floating-total": "exact_decimal_string_required",
                "zero-total": "total_must_be_positive",
                "negative-total": "total_must_be_positive",
                "component-sum-mismatch": "components_must_equal_total",
                "negative-principal": "component_must_be_nonnegative",
                "negative-interest": "component_must_be_nonnegative",
                "negative-fee": "fee_must_be_zero_in_rg08_v1",
                "positive-fee": "nonzero_fee_accounting_out_of_scope",
                "principal-over-balance": "principal_exceeds_outstanding_position",
                "unknown-destination": "unknown_account",
                "unowned-destination": "owned_account_required",
                "nonfinancial-destination": "financial_asset_account_required",
                "unknown-funding-account": "unknown_account",
                "unknown-counterparty": "unknown_counterparty",
                "invalid-behavior": "invalid_lending_behavior",
                "guessed-split": "explicit_component_split_required",
                "cross-currency": "same_currency_required",
                "inactive-interest-category": "active_exact_interest_category_required",
            },
        )
        for item in self.case["invalid_inputs"]:
            expected = item["expected"]
            self.assertIsNone(expected["guessed_behavior_code"])
            self.assertIsNone(expected["guessed_counterparty_id"])
            self.assertIsNone(expected["guessed_destination_account_id"])
            self.assertIsNone(expected["guessed_principal_amount"])
            self.assertIsNone(expected["guessed_interest_amount"])
            self.assertIsNone(expected["fallback_event_type"])

    def test_all_registry_references_resolve_with_typed_targets(self):
        registry = self.case["entity_registry"]
        ids_by_kind = {}
        for kind, items in registry.items():
            ids = [item["id"] for item in items]
            self.assertEqual(len(ids), len(set(ids)), kind)
            ids_by_kind[kind] = set(ids)

        posting_ids = {
            posting["id"]
            for transaction in registry["transactions"]
            for posting in transaction["postings"]
        }
        component_ids = {
            component["id"]
            for settlement in registry["settlements"]
            for component in settlement["components"]
        }
        account_ids = {item["id"] for item in self.accounts}
        counterparty_ids = ids_by_kind["counterparties"]

        for account in self.accounts:
            if account.get("counterparty_id") is not None:
                self.assertIn(account["counterparty_id"], counterparty_ids)
        for transaction in registry["transactions"]:
            if transaction.get("counterparty_id") is not None:
                self.assertIn(transaction["counterparty_id"], counterparty_ids)
            for posting in transaction["postings"]:
                self.assertIn(posting["account_id"], account_ids)
        for position in registry["positions"]:
            self.assertIn(position["counterparty_id"], counterparty_ids)
            self.assertIn(position["receivable_account_id"], account_ids)
        for settlement in registry["settlements"]:
            self.assertIn(settlement["linked_position_id"], ids_by_kind["positions"])
            self.assertIn(settlement["transaction_id"], ids_by_kind["transactions"])
            for component in settlement["components"]:
                if component["posting_id"] is not None:
                    self.assertIn(component["posting_id"], posting_ids)
        self.assertTrue(component_ids)

        for evidence in registry["evidence"]:
            self.assertIn(evidence["source_id"], ids_by_kind["source_records"])
        for link in registry["evidence_links"]:
            self.assertIn(link["source_id"], ids_by_kind["source_records"])
            self.assertIn(link["evidence_id"], ids_by_kind["evidence"])
            if link["role"] == "destination_asset_posting":
                self.assertIn(link["target_id"], posting_ids)
            elif link["role"] == "funding_asset_posting":
                self.assertIn(link["target_id"], posting_ids)
            elif link["role"] == "counterparty_lending_relationship":
                self.assertIn(link["target_id"], ids_by_kind["positions"])
            else:
                self.fail(f"unknown evidence role: {link['role']}")
        for candidate in registry["candidates"]:
            for source_id in candidate["source_ids"]:
                self.assertIn(source_id, ids_by_kind["source_records"])
        for confirmation in registry["confirmation_links"]:
            self.assertIn(confirmation["candidate_id"], ids_by_kind["candidates"])
            self.assertIn(confirmation["settlement_id"], ids_by_kind["settlements"])
            self.assertIn(confirmation["transaction_id"], ids_by_kind["transactions"])

    def test_every_full_canonical_state_replays_balances_and_derived_reports(self):
        for name, state in self.case["canonical_states"].items():
            with self.subTest(state=name):
                self.assertEqual(set(state), STATE_KEYS)
                for transaction in state["transactions"]:
                    self.assertGreaterEqual(len(transaction["postings"]), 2)

        complete_names = [
            "lend_confirmed",
            "manual_complete",
            "import_confirmed",
            "import_mirror_complete",
            "position_closed",
        ]
        for name in complete_names:
            with self.subTest(replay=name):
                state = self.case["canonical_states"][name]
                transactions = [*self.case["opening"]["transactions"], *state["transactions"]]
                validate_transactions(transactions, self.accounts)
                assert_expected_balances(replay_balances(transactions), state["balances"])
                self.assertEqual(self._derive_reports(state), state["reports"])

        pending = self.case["canonical_states"]["import_pending"]
        lend = self.case["canonical_states"]["lend_confirmed"]
        self.assertEqual(pending["transactions"], lend["transactions"])
        self.assertFalse(any(item.get("behavior_code") == "collect" for item in pending["transactions"]))
        self.assertEqual(pending["balances"], lend["balances"])
        self.assertEqual(pending["reports"], lend["reports"])
        self.assertEqual(pending["reconciliation"], lend["reconciliation"])

    def test_every_state_snapshot_has_exhaustive_referential_integrity(self):
        snapshots = list(self.case["canonical_states"].values())
        snapshots.extend(self.case["operation_baselines"].values())
        snapshots.extend(
            [
                self.manual["expected"]["canonical_state"],
                self.case["principal_cap"]["maximum_valid_collection"]["expected"]["resulting_state"],
                self.import_path["candidate"]["expected"]["resulting_state"],
                self.import_path["confirmation"]["expected"]["resulting_state"],
                self.import_path["mirror_evidence"]["expected"]["resulting_state"],
            ]
        )
        operations = [
            *self.case["invalid_inputs"],
            *self.import_path["incomplete_confirmations"],
            self.case["principal_cap"]["over_balance_attempt"],
            self.case["counterparty_identity"]["rename"],
        ]
        for operation in operations:
            snapshots.append(operation["pre_operation_baseline"])
            snapshots.append(operation["expected"]["resulting_state"])
        for retry in self.case["idempotency"]["retries"]:
            snapshots.append(retry["pre_operation_baseline"])
            snapshots.append(retry["expected"]["resulting_state"])

        for index, state in enumerate(snapshots):
            with self.subTest(snapshot=index, state=state["id"]):
                self._assert_state_references(state)

    def test_scope_and_forbidden_effects_are_frozen(self):
        self.assertEqual(
            set(self.case["out_of_scope"]),
            {
                "contract_level_lending",
                "accrual_interest",
                "collateral",
                "foreign_exchange",
                "forgiveness",
                "counterparty_merge",
                "tax",
                "collection_and_fee_lifecycle_beyond_explicit_component",
                "nonzero_settlement_fee_accounting",
                "detailed_borrow_and_repay_lifecycle",
            },
        )
        self.assertEqual(
            set(self.case["forbidden_side_effects"]),
            {
                "auto_cap_principal",
                "cross_position_zero",
                "allocate_to_specific_lend",
                "guess_component_split",
                "guess_counterparty_from_name",
                "recognize_expected_interest",
                "create_clearing_posting",
                "create_fallback_income",
                "reconcile_relationship_evidence_as_bank_posting",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            },
        )

    def test_nested_reference_mutations_are_rejected(self):
        def by_id(items, item_id):
            return next(item for item in items if item["id"] == item_id)

        mutations = [
            (
                "settlement-history-transaction-owner",
                "manual_complete",
                lambda state: by_id(state["settlements"], "settlement-rg08-manual")["history"][0].__setitem__(
                    "transaction_id", "transaction-lend-rg08"
                ),
            ),
            (
                "settlement-counterparty",
                "manual_complete",
                lambda state: by_id(state["settlements"], "settlement-rg08-manual").__setitem__(
                    "counterparty_id", "counterparty-rg08-other"
                ),
            ),
            (
                "settlement-destination-account-type",
                "manual_complete",
                lambda state: by_id(state["settlements"], "settlement-rg08-manual").__setitem__(
                    "destination_account_id", "income-interest-rg08"
                ),
            ),
            (
                "settlement-position-target-type",
                "manual_complete",
                lambda state: by_id(state["settlements"], "settlement-rg08-manual").__setitem__(
                    "linked_position_id", "transaction-lend-rg08"
                ),
            ),
            (
                "principal-component-account-role",
                "manual_complete",
                lambda state: by_id(
                    by_id(state["transactions"], "transaction-collect-rg08-manual")["postings"],
                    "posting-collect-principal-rg08-manual",
                ).__setitem__("account_id", "asset-bank-a"),
            ),
            (
                "interest-component-account-role",
                "manual_complete",
                lambda state: by_id(
                    by_id(state["transactions"], "transaction-collect-rg08-manual")["postings"],
                    "posting-collect-interest-rg08-manual",
                ).__setitem__("account_id", "expense-validation"),
            ),
            (
                "lend-funding-account-role",
                "lend_confirmed",
                lambda state: by_id(
                    by_id(state["transactions"], "transaction-lend-rg08")["postings"],
                    "posting-lend-asset-rg08",
                ).__setitem__("account_id", "income-interest-rg08"),
            ),
            (
                "position-receivable-account-role",
                "manual_complete",
                lambda state: by_id(state["positions"], "lending-position-rg08").__setitem__(
                    "receivable_account_id", "asset-bank-a"
                ),
            ),
            (
                "candidate-destination-account-role",
                "import_confirmed",
                lambda state: by_id(state["candidates"], "candidate-rg08-import-collection").__setitem__(
                    "proposed_destination_account_id", "income-interest-rg08"
                ),
            ),
            (
                "source-account-role",
                "import_confirmed",
                lambda state: by_id(state["source_records"], "source-rg08-import-credit").__setitem__(
                    "account_id", "income-interest-rg08"
                ),
            ),
            (
                "source-counterparty",
                "import_confirmed",
                lambda state: by_id(state["source_records"], "source-rg08-agreement").__setitem__(
                    "counterparty_id", "counterparty-unknown"
                ),
            ),
            (
                "confirmation-counterparty",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "counterparty_id", "counterparty-rg08-other"
                ),
            ),
            (
                "confirmation-transaction-owner",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "transaction_id", "transaction-lend-rg08"
                ),
            ),
            (
                "confirmation-settlement-target-type",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "settlement_id", "lending-position-rg08"
                ),
            ),
            (
                "confirmation-candidate-target-type",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "candidate_id", "settlement-rg08-import"
                ),
            ),
            (
                "confirmation-request-id",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "confirmation_request_id", "request-unknown"
                ),
            ),
            (
                "confirmation-role",
                "import_confirmed",
                lambda state: by_id(state["confirmation_provenance"], "confirmation-rg08-import").__setitem__(
                    "role", "lending_event_confirmation"
                ),
            ),
        ]
        for name, state_name, mutate in mutations:
            with self.subTest(mutation=name):
                state = deepcopy(self.case["canonical_states"][state_name])
                mutate(state)
                with self.assertRaises((AssertionError, KeyError)):
                    self._assert_state_references(state)

    def _assert_state_references(self, state):
        self.assertEqual(set(state), STATE_KEYS)
        accounts = {item["id"]: item for item in self.accounts}
        interest_categories = {item["id"]: item for item in self.case["catalog"]["interest_categories"]}
        counterparties = {item["id"] for item in self.case["catalog"]["counterparties"]}
        confirmation_request_ids = {
            self.lend["request"]["request_id"],
            self.manual["request"]["request_id"],
            self.case["principal_cap"]["maximum_valid_collection"]["input"]["request_id"],
            self.import_path["confirmation"]["request"]["request_id"],
        }
        transactions = {item["id"]: item for item in state["transactions"]}
        versions = {item["id"]: item for item in state["versions"]}
        positions = {item["id"]: item for item in state["positions"]}
        settlements = {item["id"]: item for item in state["settlements"]}
        candidates = {item["id"]: item for item in state["candidates"]}
        sources = {item["id"]: item for item in state["source_records"]}
        evidence = {item["id"]: item for item in state["evidence"]}
        evidence_links = {item["id"]: item for item in state["evidence_links"]}
        postings = {}

        for items, indexed in (
            (state["transactions"], transactions),
            (state["versions"], versions),
            (state["positions"], positions),
            (state["settlements"], settlements),
            (state["candidates"], candidates),
            (state["source_records"], sources),
            (state["evidence"], evidence),
            (state["evidence_links"], evidence_links),
        ):
            self.assertEqual(len(items), len(indexed))
        confirmation_ids = [item["id"] for item in state["confirmation_provenance"]]
        self.assertEqual(len(confirmation_ids), len(set(confirmation_ids)))

        for transaction in transactions.values():
            if transaction.get("counterparty_id") is not None:
                self.assertIn(transaction["counterparty_id"], counterparties)
            version = versions[transaction["current_version_id"]]
            self.assertEqual(version["transaction_id"], transaction["id"])
            self.assertEqual(version["posting_set_id"], transaction["posting_set_id"])
            for posting in transaction["postings"]:
                self.assertNotIn(posting["id"], postings)
                self.assertIn(posting["account_id"], accounts)
                postings[posting["id"]] = (transaction, posting)
            if transaction["behavior_code"] == "lend":
                matching_positions = [
                    item for item in positions.values() if item["counterparty_id"] == transaction["counterparty_id"]
                ]
                self.assertEqual(len(matching_positions), 1)
                receivable_account_id = matching_positions[0]["receivable_account_id"]
                receivable_postings = [
                    item for item in transaction["postings"] if item["account_id"] == receivable_account_id
                ]
                self.assertEqual(len(receivable_postings), 1)
                self.assertGreater(Decimal(receivable_postings[0]["amount"]), Decimal("0.00"))
                funding_postings = [
                    item for item in transaction["postings"] if item["id"] != receivable_postings[0]["id"]
                ]
                self.assertEqual(len(funding_postings), 1)
                funding_account = accounts[funding_postings[0]["account_id"]]
                self.assertEqual(funding_account["type"], "asset")
                self.assertTrue(funding_account["owned_by_user"])
                self.assertTrue(funding_account["financial"])
                self.assertLess(Decimal(funding_postings[0]["amount"]), Decimal("0.00"))

        self.assertEqual({item["transaction_id"] for item in versions.values()}, set(transactions))
        for position in positions.values():
            self.assertIn(position["counterparty_id"], counterparties)
            self.assertIn(position["receivable_account_id"], accounts)
            receivable_account = accounts[position["receivable_account_id"]]
            self.assertEqual(receivable_account["position_kind"], "lending_receivable")
            self.assertEqual(receivable_account["counterparty_id"], position["counterparty_id"])
            history_ids = [item["id"] for item in position["history"]]
            self.assertEqual(len(history_ids), len(set(history_ids)))
            for history in position["history"]:
                self.assertIn(history["transaction_id"], transactions)

        for settlement in settlements.values():
            self.assertIn(settlement["transaction_id"], transactions)
            transaction = transactions[settlement["transaction_id"]]
            self.assertEqual(transaction["settlement_id"], settlement["id"])
            self.assertIn(settlement["linked_position_id"], positions)
            position = positions[settlement["linked_position_id"]]
            self.assertIn(settlement["counterparty_id"], counterparties)
            self.assertEqual(settlement["counterparty_id"], position["counterparty_id"])
            self.assertEqual(settlement["counterparty_id"], transaction["counterparty_id"])
            self.assertIsNone(settlement["allocated_lend_transaction_id"])
            self.assertIn(settlement["destination_account_id"], accounts)
            destination_account = accounts[settlement["destination_account_id"]]
            self.assertEqual(destination_account["type"], "asset")
            self.assertTrue(destination_account["owned_by_user"])
            self.assertTrue(destination_account["financial"])
            self.assertIn(settlement["interest_category_id"], interest_categories)
            interest_category = interest_categories[settlement["interest_category_id"]]
            self.assertTrue(interest_category["active"])
            self.assertEqual(interest_category["kind"], "interest_income")
            self.assertIn(interest_category["account_id"], accounts)
            self.assertEqual(accounts[interest_category["account_id"]]["type"], "income")
            self.assertEqual([item["kind"] for item in settlement["components"]], ["principal", "interest", "fee"])
            component_ids = [item["id"] for item in settlement["components"]]
            self.assertEqual(len(component_ids), len(set(component_ids)))
            self.assertEqual(settlement["components"][2]["amount"], "0.00")
            self.assertIsNone(settlement["components"][2]["posting_id"])
            for component in settlement["components"][:2]:
                owner, posting = postings[component["posting_id"]]
                self.assertEqual(owner["id"], transaction["id"])
                self.assertEqual(Decimal(posting["amount"]), -Decimal(component["amount"]))
                if component["kind"] == "principal":
                    self.assertEqual(posting["account_id"], position["receivable_account_id"])
                else:
                    self.assertEqual(posting["account_id"], interest_category["account_id"])
            destination_postings = [
                item
                for item in transaction["postings"]
                if item["account_id"] == settlement["destination_account_id"]
            ]
            self.assertEqual(len(destination_postings), 1)
            self.assertEqual(Decimal(destination_postings[0]["amount"]), Decimal(settlement["total_received"]))
            self.assertEqual(
                sum(Decimal(item["amount"]) for item in settlement["components"]),
                Decimal(settlement["total_received"]),
            )
            settlement_history_ids = [item["id"] for item in settlement["history"]]
            self.assertEqual(len(settlement_history_ids), len(set(settlement_history_ids)))
            for history in settlement["history"]:
                self.assertIn(history["transaction_id"], transactions)
                self.assertEqual(history["transaction_id"], settlement["transaction_id"])

        for candidate in candidates.values():
            history_ids = [item["id"] for item in candidate["status_history"]]
            self.assertEqual(len(history_ids), len(set(history_ids)))
            if candidate.get("proposed_counterparty_id") is not None:
                self.assertIn(candidate["proposed_counterparty_id"], counterparties)
            if candidate.get("proposed_destination_account_id") is not None:
                self.assertIn(candidate["proposed_destination_account_id"], accounts)
                account = accounts[candidate["proposed_destination_account_id"]]
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
            for source_id in candidate["source_ids"]:
                self.assertIn(source_id, sources)
        for source in sources.values():
            if source.get("account_id") is not None:
                self.assertIn(source["account_id"], accounts)
                account = accounts[source["account_id"]]
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
            if source.get("counterparty_id") is not None:
                self.assertIn(source["counterparty_id"], counterparties)
            if source.get("mirror_of_source_id") is not None:
                self.assertIn(source["mirror_of_source_id"], sources)
        for item in evidence.values():
            self.assertIn(item["source_id"], sources)
        for link in evidence_links.values():
            self.assertIn(link["source_id"], sources)
            self.assertIn(link["evidence_id"], evidence)
            if link.get("mirror_of_evidence_id") is not None:
                self.assertIn(link["mirror_of_evidence_id"], evidence)
            if link.get("merged_into_evidence_link_id") is not None:
                self.assertIn(link["merged_into_evidence_link_id"], evidence_links)
            if link["role"] in {"destination_asset_posting", "funding_asset_posting"}:
                _, posting = postings[link["target_id"]]
                account = accounts[posting["account_id"]]
                self.assertEqual(account["type"], "asset")
                self.assertTrue(account["owned_by_user"])
                self.assertTrue(account["financial"])
            elif link["role"] == "counterparty_lending_relationship":
                self.assertIn(link["target_id"], positions)
            else:
                self.fail(f"unknown evidence role: {link['role']}")

        for confirmation in state["confirmation_provenance"]:
            self.assertIn(confirmation["confirmation_request_id"], confirmation_request_ids)
            self.assertIn(confirmation["counterparty_id"], counterparties)
            for key, collection in (
                ("transaction_id", transactions),
                ("settlement_id", settlements),
                ("candidate_id", candidates),
            ):
                if confirmation.get(key) is not None:
                    self.assertIn(confirmation[key], collection)
            transaction = transactions[confirmation["transaction_id"]]
            self.assertEqual(confirmation["counterparty_id"], transaction["counterparty_id"])
            if confirmation["role"] == "lending_event_confirmation":
                self.assertEqual(transaction["behavior_code"], "lend")
                self.assertNotIn("settlement_id", confirmation)
                self.assertNotIn("candidate_id", confirmation)
            elif confirmation["role"] == "lending_settlement_confirmation":
                self.assertEqual(transaction["behavior_code"], "collect")
                settlement = settlements[confirmation["settlement_id"]]
                self.assertEqual(settlement["transaction_id"], transaction["id"])
                self.assertEqual(settlement["counterparty_id"], confirmation["counterparty_id"])
            else:
                self.fail(f"unknown confirmation role: {confirmation['role']}")

    def _derive_reports(self, state):
        fields = [
            "consumption",
            "expense",
            "lending_principal_cash_outflow",
            "cash_outflow",
            "lending_principal_cash_inflow",
            "interest_cash_inflow",
            "total_cash_inflow",
            "ordinary_interest_income",
            "ordinary_income",
            "net_worth_change",
        ]
        periods = {}
        settlements = {item["transaction_id"]: item for item in state["settlements"]}

        def period_for(transaction):
            period = transaction["statistics_at"][:7]
            return periods.setdefault(period, {field: Decimal("0.00") for field in fields})

        for transaction in state["transactions"]:
            report = period_for(transaction)
            if transaction["behavior_code"] == "lend":
                principal = next(
                    Decimal(item["amount"])
                    for item in transaction["postings"]
                    if item["account_id"] == "receivable-counterparty-rg08"
                )
                report["lending_principal_cash_outflow"] += principal
                report["cash_outflow"] += principal
            elif transaction["behavior_code"] == "collect":
                components = {item["kind"]: Decimal(item["amount"]) for item in settlements[transaction["id"]]["components"]}
                self.assertEqual(components["fee"], Decimal("0.00"))
                total = sum(components.values())
                report["lending_principal_cash_inflow"] += components["principal"]
                report["interest_cash_inflow"] += components["interest"]
                report["total_cash_inflow"] += total
                report["ordinary_interest_income"] += components["interest"]
                report["ordinary_income"] += components["interest"]
                report["net_worth_change"] += components["interest"]
            else:
                self.fail(f"unexpected behavior in RG-08 state: {transaction['behavior_code']}")

        cumulative = {field: sum(period[field] for period in periods.values()) for field in fields}
        result = {
            period: {field: f"{amount:.2f}" for field, amount in report.items()}
            for period, report in sorted(periods.items())
        }
        result["cumulative"] = {field: f"{amount:.2f}" for field, amount in cumulative.items()}
        return result

    def _all_amounts(self, value, path="root"):
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}.{key}"
                if key == "balances" or key == "balance_change":
                    for amount_key, amount in child.items():
                        yield f"{child_path}.{amount_key}", amount
                elif key in {
                    "amount",
                    "total_received",
                    "principal_amount",
                    "interest_amount",
                    "fee_amount",
                    "principal_balance",
                    "expected_interest",
                    "accrued_interest",
                    "allocated_principal",
                    "pending_total",
                    "income",
                    "net_worth_change",
                }:
                    if child is not None:
                        yield child_path, child
                elif key == "reports":
                    for period, report in child.items():
                        for report_key, amount in report.items():
                            yield f"{child_path}.{period}.{report_key}", amount
                else:
                    yield from self._all_amounts(child, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                yield from self._all_amounts(child, f"{path}[{index}]")


if __name__ == "__main__":
    unittest.main()
