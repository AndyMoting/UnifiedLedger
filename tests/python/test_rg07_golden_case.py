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
RG07_PATH = REPOSITORY_ROOT / "golden" / "rules" / "rg-07.json"
AMOUNT_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)\.\d{2}$")
ZERO_EFFECT_COUNTS = {
    "new_candidate_count": 0,
    "new_relation_count": 0,
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
}


def assert_exact_money(test_case, value, path="amount"):
    test_case.assertIsInstance(value, str, path)
    test_case.assertRegex(value, AMOUNT_PATTERN, path)


class RG07GoldenCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = load_golden_case(RG07_PATH)
        cls.accounts = cls.case["catalog"]["accounts"]
        cls.original = cls.case["original"]
        cls.request = cls.case["refund_request"]
        cls.manual = cls.case["manual_receipt"]
        cls.import_path = cls.case["import_path"]

    def test_fixture_enforces_schema_cny_exact_money_and_catalog_boundaries(self):
        validate_case_envelope(self.case)
        self.assertEqual(
            self.case["case"],
            {
                "id": "RG-07",
                "level": "core_required",
                "rule_version": 1,
                "timezone": "Asia/Shanghai",
                "currency": "CNY",
                "precision": 2,
                "ledger_id": "ledger-a",
            },
        )
        for account in self.accounts:
            self.assertEqual(account["currency"], "CNY")
        for path, amount in self._all_amounts(self.case):
            assert_exact_money(self, amount, path)

        accounts = {item["id"]: item for item in self.accounts}
        self.assertTrue(accounts["asset-bank-a"]["owned_by_user"])
        self.assertTrue(accounts["asset-wallet-b"]["owned_by_user"])
        self.assertEqual(accounts["asset-wallet-b"]["destination_kind"], "owned_asset")
        self.assertEqual(accounts["liability-credit-c"]["type"], "liability")
        self.assertEqual(accounts["asset-store-credit-d"]["destination_kind"], "store_credit")

        categories = {item["id"]: item for item in self.case["catalog"]["categories"]}
        exact = categories["expense-category-daily"]
        self.assertEqual(exact["account_id"], "expense-account-daily")
        self.assertEqual(exact["parent_id"], "expense-category-living")
        self.assertEqual(exact["kind"], "expense")
        self.assertTrue(exact["active"])

    def test_original_and_refund_are_separate_balanced_transactions_and_replay(self):
        original_transaction = self.original["transaction"]
        refund_transaction = self.manual["expected"]["transaction"]
        self.assertNotEqual(original_transaction["id"], refund_transaction["id"])
        self.assertEqual(refund_transaction["linked_original_transaction_id"], original_transaction["id"])
        self.assertEqual(original_transaction["occurred_at"], "2026-01-10T12:00:00+08:00")
        self.assertEqual(refund_transaction["occurred_at"], "2026-02-02T15:20:00+08:00")

        transactions = [
            *self.case["opening"]["transactions"],
            original_transaction,
            refund_transaction,
        ]
        validate_transactions(transactions, self.accounts)
        replay = replay_balances(transactions)
        self.assertEqual(replay["asset-bank-a"], Decimal("880.00"))
        self.assertEqual(replay["asset-wallet-b"], Decimal("230.00"))
        self.assertEqual(replay["expense-account-daily"], Decimal("90.00"))
        assert_expected_balances(replay, self.manual["expected"]["balances"])

        self.assertEqual(
            refund_transaction["postings"],
            [
                {
                    "id": "posting-refund-asset-rg07-manual",
                    "account_id": "asset-wallet-b",
                    "amount": "30.00",
                    "currency": "CNY",
                    "reconciliation_eligible": True,
                },
                {
                    "id": "posting-refund-expense-rg07-manual",
                    "account_id": "expense-account-daily",
                    "amount": "-30.00",
                    "currency": "CNY",
                    "reconciliation_eligible": False,
                },
            ],
        )

    def test_request_approval_and_processing_have_exact_zero_formal_effect(self):
        expected = self.request["expected"]
        self.assertEqual(expected["relation"]["state"], "processing")
        self.assertEqual(
            [item["state"] for item in expected["relation"]["state_history"]],
            ["requested", "approved", "processing"],
        )
        for item in expected["relation"]["state_history"]:
            self.assertEqual(item["formal_effect_count"], 0)
        self.assertEqual(
            expected["formal_effects"],
            {
                "new_transaction_count": 0,
                "new_posting_count": 0,
                "new_version_count": 0,
                "balance_change": {"asset-bank-a": "0.00", "asset-wallet-b": "0.00"},
                "consumption": "0.00",
                "cash_inflow": "0.00",
                "income": "0.00",
                "net_worth_change": "0.00",
                "reconciliation_change_count": 0,
            },
        )
        self.assertEqual(expected["balances"], self.original["balances"])
        self.assertEqual(expected["reports"], self.original["reports"])
        self.assertEqual(expected["reconciliation"], self.original["reconciliation"])

    def test_arrival_reports_use_receipt_period_without_rewriting_original_period(self):
        reports = self.manual["expected"]["reports"]
        self.assertEqual(
            reports["2026-01"],
            {
                "consumption": "120.00",
                "cash_outflow": "120.00",
                "refund_cash_inflow": "0.00",
                "ordinary_income": "0.00",
                "net_worth_change": "-120.00",
            },
        )
        self.assertEqual(
            reports["2026-02"],
            {
                "consumption": "-30.00",
                "cash_outflow": "0.00",
                "refund_cash_inflow": "30.00",
                "ordinary_income": "0.00",
                "net_worth_change": "30.00",
            },
        )
        self.assertEqual(reports["cumulative"]["consumption"], "90.00")
        self.assertEqual(reports["cumulative"]["cash_outflow"], "120.00")
        self.assertEqual(reports["cumulative"]["refund_cash_inflow"], "30.00")
        self.assertEqual(reports["cumulative"]["ordinary_income"], "0.00")
        self.assertEqual(self.original["transaction"]["current_version_id"], "version-original-rg07-v1")
        self.assertEqual(self.manual["expected"]["original_version_after_refund"], "version-original-rg07-v1")

    def test_refund_inherits_exact_secondary_category_and_never_guesses(self):
        policy = self.case["category_policy"]
        self.assertEqual(policy["original_category_id"], "expense-category-daily")
        self.assertEqual(policy["refund_category_id"], "expense-category-daily")
        self.assertEqual(policy["refund_expense_account_id"], "expense-account-daily")
        self.assertEqual(policy["inheritance"], "exact_original_secondary_category")
        self.assertEqual(policy["misclassified_original_action"], "correct_original_version_first")
        self.assertFalse(policy["allow_refund_category_override"])
        self.assertFalse(policy["allow_guessed_category"])
        self.assertEqual(policy["multi_category_allocation"], "out_of_scope")

    def test_refund_cap_is_cumulative_and_over_cap_allocation_rejects_atomically(self):
        cap = self.case["refund_cap"]
        self.assertEqual(cap["original_confirmed_refundable_expense"], "120.00")
        self.assertEqual(cap["active_linked_refunds"], "30.00")
        self.assertEqual(cap["remaining_refundable"], "90.00")
        maximum = cap["maximum_valid_allocation"]
        self.assertEqual(maximum["amount"], "90.00")
        self.assertTrue(maximum["expected"]["accepted"])
        validate_transactions([maximum["expected"]["transaction"]], self.accounts)

        over_cap = cap["over_cap_attempt"]
        self.assertEqual(over_cap["requested_allocation"], "100.00")
        self.assertEqual(over_cap["available_allocation"], "90.00")
        self.assertFalse(over_cap["expected"]["accepted"])
        self.assertEqual(over_cap["expected"]["reason"], "refund_amount_exceeds_remaining_refundable")
        self.assertEqual(over_cap["expected"]["allocated_amount"], "0.00")
        self.assertEqual(over_cap["expected"]["unallocated_amount"], "100.00")
        self.assertEqual(over_cap["expected"]["candidate_status"], "pending_explicit_classification")
        self.assertIsNone(over_cap["expected"]["fallback_event_type"])
        self.assertEqual(over_cap["expected"]["new_transaction_count"], 0)
        self.assertEqual(over_cap["expected"]["new_posting_count"], 0)
        self.assertTrue(over_cap["expected"]["state_unchanged"])

    def test_maximum_cap_allocation_has_complete_state_and_replays_to_zero_expense(self):
        maximum = self.case["refund_cap"]["maximum_valid_allocation"]
        expected = maximum["expected"]
        state = expected["resulting_state"]
        existing = self.case["refund_cap"]["existing_refund"]
        self.assertEqual(maximum["operation_context"], {
            "operation_type": "confirm_refund_receipt",
            "operation_id": "request-rg07-cap-maximum",
            "baseline_id": "baseline-rg07-cap-existing-30",
        })
        self.assertEqual(state, self.case["canonical_states"]["cap_complete_120"])
        transactions = [
            *self.case["opening"]["transactions"],
            self.original["transaction"],
            existing["transaction"],
            expected["transaction"],
        ]
        validate_transactions(transactions, self.accounts)
        replay = replay_balances(transactions)
        self.assertEqual(replay["asset-bank-a"], Decimal("880.00"))
        self.assertEqual(replay["asset-wallet-b"], Decimal("320.00"))
        self.assertEqual(replay["expense-account-daily"], Decimal("0.00"))
        assert_expected_balances(replay, state["balances"])
        self.assertEqual(expected["transaction"]["category_id"], "expense-category-daily")
        self.assertEqual(expected["transaction"]["statistics_at"], "2026-02-10T10:00:00+08:00")
        self.assertEqual(expected["relation"]["refund_transaction_id"], expected["transaction"]["id"])
        self.assertEqual(
            [item["id"] for item in state["transactions"]],
            [
                "transaction-original-rg07",
                "transaction-refund-rg07-cap-first",
                "transaction-refund-rg07-cap-maximum",
            ],
        )
        self.assertNotEqual(expected["transaction"]["id"], existing["transaction"]["id"])
        self.assertNotEqual(expected["relation"]["id"], existing["relation"]["id"])
        self.assertEqual(state["reports"]["2026-02-02"]["consumption"], "-30.00")
        self.assertEqual(state["reports"]["2026-02-10"]["consumption"], "-90.00")
        self.assertEqual(state["reports"]["2026-02"]["consumption"], "-120.00")
        self.assertEqual(state["reports"]["2026-02"]["refund_cash_inflow"], "120.00")
        self.assertEqual(state["reports"]["cumulative"]["consumption"], "0.00")
        self.assertEqual(state["reports"]["cumulative"]["ordinary_income"], "0.00")

    def test_destination_requires_evidence_or_explicit_confirmation_and_may_differ(self):
        receipt = self.manual["request"]
        self.assertEqual(receipt["destination_account_id"], "asset-wallet-b")
        self.assertNotEqual(receipt["destination_account_id"], self.original["payment_account_id"])
        self.assertEqual(receipt["confirmation_mode"], "explicit_manual_receipt")
        self.assertTrue(receipt["arrival_confirmed"])
        self.assertIsNone(self.manual["expected"]["guessed_destination_account_id"])
        self.assertEqual(
            self.manual["expected"]["destination_basis"],
            "explicit_manual_confirmation",
        )

    def test_evidence_roles_and_reconciliation_are_independent(self):
        notice = self.case["merchant_notice"]
        self.assertEqual(notice["expected"]["evidence_link"]["role"], "refund_relationship")
        self.assertEqual(notice["expected"]["evidence_link"]["target_id"], "refund-relation-rg07-manual")
        self.assertEqual(notice["expected"]["reconciliation"], self.original["reconciliation"])

        bank = self.case["bank_credit_evidence"]["expected"]
        self.assertEqual(bank["new_transaction_count"], 0)
        self.assertEqual(bank["new_posting_count"], 0)
        self.assertEqual(bank["reconciliation"]["posting-original-asset-rg07"], "matched")
        self.assertEqual(bank["reconciliation"]["posting-refund-asset-rg07-manual"], "matched")
        self.assertEqual(bank["reconciliation"]["transaction-original-rg07"], "complete")
        self.assertEqual(bank["reconciliation"]["transaction-refund-rg07-manual"], "complete")
        self.assertEqual(
            bank["evidence_link"],
            {
                "id": "evidence-link-rg07-bank-credit",
                "source_id": "source-rg07-bank-credit",
                "evidence_id": "evidence-rg07-bank-credit",
                "role": "destination_asset_posting",
                "target_id": "posting-refund-asset-rg07-manual",
                "status": "matched",
            },
        )

        dual = self.case["dual_role_source"]["expected"]["evidence_role_links"]
        self.assertEqual(len(dual), 2)
        self.assertEqual({item["source_id"] for item in dual}, {"source-rg07-dual-role"})
        self.assertEqual(
            {(item["role"], item["target_id"]) for item in dual},
            {
                ("refund_relationship", "refund-relation-rg07-manual"),
                ("destination_asset_posting", "posting-refund-asset-rg07-manual"),
            },
        )

    def test_imported_credit_only_targets_destination_posting_and_confirmation_links_relationship(self):
        confirmed = self.import_path["confirmation"]["expected"]
        provenance = confirmed["provenance"]
        self.assertEqual(
            {item["role"] for item in provenance["evidence_links"]},
            {"destination_asset_posting"},
        )
        self.assertEqual(len(provenance["evidence_links"]), 1)
        self.assertEqual(
            provenance["evidence_links"][0]["target_id"],
            "posting-refund-asset-rg07-import",
        )
        self.assertEqual(
            provenance["confirmation_links"],
            [
                {
                    "id": "confirmation-link-rg07-import-relationship",
                    "confirmation_request_id": "request-rg07-confirm-import",
                    "role": "refund_relationship_confirmation",
                    "relation_id": "refund-relation-rg07-import",
                    "original_transaction_id": "transaction-original-rg07",
                    "confirmed_at": "2026-02-02T18:05:00+08:00",
                }
            ],
        )

    def test_manual_confirmation_preserves_all_distinct_times_and_history(self):
        relation = self.manual["expected"]["relation"]
        request = self.manual["request"]
        self.assertEqual(
            relation["times"],
            {
                "requested_at": "2026-01-20T09:00:00+08:00",
                "approved_at": "2026-01-21T10:00:00+08:00",
                "processor_reported_at": "2026-01-23T11:00:00+08:00",
                "source_observed_at": "2026-02-02T15:25:00+08:00",
                "booking_at": "2026-02-02T15:20:00+08:00",
                "value_at": "2026-02-02T15:20:00+08:00",
                "confirmed_at": "2026-02-02T18:00:00+08:00",
                "arrived_at": "2026-02-02T15:20:00+08:00",
            },
        )
        self.assertEqual(relation["times"]["source_observed_at"], request["source_observed_at"])
        self.assertEqual(relation["times"]["booking_at"], request["booking_at"])
        self.assertEqual(relation["times"]["value_at"], request["value_at"])
        self.assertEqual(relation["times"]["confirmed_at"], request["confirmed_at"])
        self.assertEqual(relation["times"]["arrived_at"], request["arrived_at"])
        self.assertEqual(request["observation_mode"], "manual_account_observation")
        history = relation["state_history"]
        self.assertEqual([item["state"] for item in history], ["requested", "approved", "processing", "received"])
        self.assertEqual([item["id"] for item in history], [
            "history-rg07-requested",
            "history-rg07-approved",
            "history-rg07-processing",
            "history-rg07-received",
        ])
        self.assertEqual(history[-1]["transaction_id"], "transaction-refund-rg07-manual")
        self.assertEqual(history[-1]["formal_effect_count"], 1)

    def test_import_credit_stays_pending_until_all_exact_fields_are_confirmed(self):
        candidate = self.import_path["candidate"]["expected"]
        self.assertEqual(candidate["candidate"]["status"], "pending_confirmation")
        self.assertEqual(
            candidate["candidate"]["requires_confirmation"],
            [
                "original_transaction_id",
                "category_id_and_allocation",
                "destination_account_id",
                "arrival",
            ],
        )
        self.assertEqual(candidate["candidate"]["proposed_amount"], "30.00")
        self.assertEqual(candidate["candidate"]["currency"], "CNY")
        self.assertEqual(candidate["candidate"]["status_history"][0]["status"], "pending_confirmation")
        self.assertEqual(candidate["formal_effects"]["new_transaction_count"], 0)
        self.assertEqual(candidate["formal_effects"]["new_posting_count"], 0)
        self.assertEqual(candidate["formal_effects"]["consumption"], "0.00")
        self.assertEqual(candidate["formal_effects"]["cash_inflow"], "0.00")
        self.assertEqual(candidate["balances"], self.original["balances"])

        for attempt in self.import_path["incomplete_confirmations"]:
            with self.subTest(attempt=attempt["id"]):
                self.assertEqual(attempt["expected"]["candidate_status"], "pending_confirmation")
                self.assertEqual(attempt["expected"]["new_transaction_count"], 0)
                self.assertEqual(attempt["expected"]["new_posting_count"], 0)
                self.assertTrue(attempt["expected"]["state_unchanged"])

    def test_all_rejections_have_exact_context_full_baseline_result_and_zero_effects(self):
        rejected = [
            *self.case["invalid_inputs"],
            *self.import_path["incomplete_confirmations"],
            self.case["manual_unconfirmed_arrival"],
            self.case["refund_cap"]["over_cap_attempt"],
        ]
        baselines = self.case["operation_baselines"]
        expected_contexts = {
            **{
                item["id"]: {
                    "operation_type": "validate_refund_receipt",
                    "operation_id": f"operation-rg07-{item['id']}",
                    "baseline_id": "baseline-rg07-original-confirmed",
                }
                for item in self.case["invalid_inputs"]
            },
            **{
                item["id"]: {
                    "operation_type": "confirm_imported_refund",
                    "operation_id": f"operation-rg07-{item['id']}",
                    "baseline_id": "baseline-rg07-original-confirmed",
                }
                for item in self.import_path["incomplete_confirmations"]
            },
            "manual-unconfirmed-arrival": {
                "operation_type": "confirm_manual_refund_receipt",
                "operation_id": "request-rg07-manual-unconfirmed-arrival",
                "baseline_id": "baseline-rg07-original-confirmed",
            },
            "over-cap-attempt": {
                "operation_type": "allocate_refund_receipt",
                "operation_id": "operation-rg07-over-cap",
                "baseline_id": "baseline-rg07-cap-existing-30",
            },
        }
        for operation in rejected:
            with self.subTest(operation=operation["id"]):
                context = operation["operation_context"]
                self.assertEqual(context, expected_contexts[operation["id"]])
                baseline = baselines[context["baseline_id"]]
                self.assertEqual(operation["pre_operation_baseline"], baseline)
                self.assertEqual(operation["expected"]["resulting_state"], baseline)
                self.assertEqual(operation["expected"]["effect_counts"], ZERO_EFFECT_COUNTS)

    def test_manual_arrival_must_be_confirmed_before_any_formal_effect(self):
        operation = self.case["manual_unconfirmed_arrival"]
        self.assertFalse(operation["input"]["arrival_confirmed"])
        self.assertEqual(operation["expected"]["reason"], "arrival_confirmation_required")
        self.assertEqual(operation["expected"]["candidate_status"], "pending_confirmation")
        self.assertEqual(operation["expected"]["effect_counts"], ZERO_EFFECT_COUNTS)
        self.assertEqual(operation["expected"]["resulting_state"], operation["pre_operation_baseline"])

    def test_import_confirmation_creates_exact_linked_receipt_with_provenance(self):
        confirmed = self.import_path["confirmation"]["expected"]
        transaction = confirmed["transaction"]
        self.assertEqual(confirmed["candidate_status"], "confirmed")
        self.assertEqual(transaction["linked_original_transaction_id"], "transaction-original-rg07")
        self.assertEqual(transaction["occurred_at"], "2026-02-02T15:20:00+08:00")
        self.assertEqual(transaction["statistics_at"], transaction["occurred_at"])
        self.assertEqual(transaction["postings"][0]["account_id"], "asset-wallet-b")
        self.assertEqual(transaction["postings"][1]["account_id"], "expense-account-daily")
        transactions = [
            *self.case["opening"]["transactions"],
            self.original["transaction"],
            transaction,
        ]
        validate_transactions(transactions, self.accounts)
        assert_expected_balances(
            replay_balances(transactions),
            confirmed["balances"],
        )

        provenance = confirmed["provenance"]
        self.assertEqual(provenance["source_ids"], ["source-rg07-import-credit"])
        self.assertEqual(provenance["candidate_id"], "candidate-refund-rg07-import")
        self.assertEqual(provenance["confirmation_request_id"], "request-rg07-confirm-import")
        self.assertEqual(provenance["rule_version"], 1)
        self.assertEqual(provenance["confidence"], "0.97")
        self.assertEqual(provenance["original_source_payload_hash"], "sha256:rg07-synthetic-credit")
        self.assertEqual(
            confirmed["candidate_history"],
            [
                {
                    "id": "history-rg07-import-pending",
                    "status": "pending_confirmation",
                    "occurred_at": "2026-02-02T15:26:00+08:00",
                    "formal_effect_count": 0,
                },
                {
                    "id": "history-rg07-import-confirmed",
                    "status": "confirmed",
                    "occurred_at": "2026-02-02T18:05:00+08:00",
                    "formal_effect_count": 1,
                },
            ],
        )

    def test_mirror_evidence_and_retries_are_idempotent_full_snapshots(self):
        confirmed = self.import_path["confirmation"]["expected"]
        mirror = self.import_path["mirror_evidence"]["expected"]
        self.assertEqual(mirror["merged_into_transaction_id"], confirmed["transaction"]["id"])
        self.assertEqual(mirror["current_version_id"], confirmed["transaction"]["current_version_id"])
        self.assertEqual(mirror["new_transaction_count"], 0)
        self.assertEqual(mirror["new_posting_count"], 0)
        self.assertEqual(mirror["new_version_count"], 0)
        self.assertEqual(mirror["new_source_record_count"], 1)
        self.assertEqual(mirror["new_evidence_link_count"], 1)
        self.assertEqual(
            mirror["source_record"],
            {
                "id": "source-rg07-import-mirror",
                "evidence_id": "evidence-rg07-import-mirror",
                "observed_at": "2026-02-02T15:27:00+08:00",
                "amount": "30.00",
                "currency": "CNY",
                "mirror_of_source_id": "source-rg07-import-credit",
            },
        )
        self.assertEqual(
            mirror["evidence_link"],
            {
                "id": "evidence-link-rg07-import-mirror",
                "source_id": "source-rg07-import-mirror",
                "evidence_id": "evidence-rg07-import-mirror",
                "role": "destination_asset_posting",
                "target_id": "posting-refund-asset-rg07-import",
                "status": "merged",
                "mirror_of_evidence_id": "evidence-rg07-import-credit",
                "merged_into_evidence_link_id": "evidence-link-rg07-import-posting",
            },
        )
        confirmed_snapshot = confirmed["canonical_snapshot"]
        mirror_snapshot = mirror["canonical_snapshot"]
        for key in (
            "transaction_id",
            "current_version_id",
            "posting_set_id",
            "posting_ids",
            "relation_id",
            "relation_state",
            "candidate_id",
            "candidate_status",
            "original_transaction_id",
            "original_current_version_id",
            "category_id",
            "destination_account_id",
            "received_amount",
            "currency",
            "balances",
            "reports",
            "reconciliation",
            "candidate_history_ids",
            "relation_history_ids",
        ):
            self.assertEqual(mirror_snapshot[key], confirmed_snapshot[key], key)
        self.assertEqual(
            mirror_snapshot["source_ids"],
            ["source-rg07-import-credit", "source-rg07-import-mirror"],
        )
        self.assertEqual(
            mirror_snapshot["evidence_link_ids"],
            [
                "evidence-link-rg07-import-posting",
                "evidence-link-rg07-import-mirror",
            ],
        )

        idempotency = self.case["idempotency"]
        self.assertEqual(
            idempotency["retried_inputs"],
            [
                "request-rg07-original",
                "request-rg07-refund-state",
                "source-rg07-merchant-notice",
                "request-rg07-manual-receipt",
                "source-rg07-bank-credit",
                "source-rg07-original-bank",
                "source-rg07-dual-role",
                "request-rg07-cap-first",
                "request-rg07-cap-maximum",
                "request-rg07-manual-unconfirmed-arrival",
                "source-rg07-import-credit",
                "request-rg07-confirm-import",
                "request-rg07-merge-import-mirror",
                "source-rg07-import-mirror",
            ],
        )
        self.assertEqual(idempotency["effect_counts"], ZERO_EFFECT_COUNTS)
        for state_name, expected_state in (
            ("manual_state", self.case["canonical_states"]["manual_complete"]),
            ("import_state", self.case["canonical_states"]["import_mirror_complete"]),
        ):
            state = idempotency["expected"][state_name]
            self.assertEqual(state, expected_state)
            self.assertEqual(
                set(state),
                {
                    "id",
                    "transactions",
                    "versions",
                    "relations",
                    "candidates",
                    "confirmation_provenance",
                    "source_records",
                    "evidence",
                    "evidence_links",
                    "balances",
                    "reports",
                    "reconciliation",
                },
            )
            for transaction in state["transactions"]:
                self.assertGreaterEqual(len(transaction["postings"]), 2)
            for relation in state["relations"]:
                self.assertIn("times", relation)
                self.assertIn("state_history", relation)

    def test_canonical_snapshots_preserve_transactions_relations_reports_and_provenance(self):
        manual = self.manual["expected"]
        manual_snapshot = manual["canonical_snapshot"]
        self.assertEqual(manual_snapshot["transaction_id"], manual["transaction"]["id"])
        self.assertEqual(manual_snapshot["current_version_id"], manual["transaction"]["current_version_id"])
        self.assertEqual(manual_snapshot["posting_ids"], [item["id"] for item in manual["transaction"]["postings"]])
        self.assertEqual(manual_snapshot["relation_id"], manual["relation"]["id"])
        self.assertEqual(manual_snapshot["relation_state"], manual["relation"]["state"])
        self.assertEqual(manual_snapshot["balances"], manual["balances"])
        self.assertEqual(manual_snapshot["reports"], manual["reports"])
        self.assertEqual(manual_snapshot["reconciliation"], manual["reconciliation"])
        self.assertEqual(manual_snapshot["source_ids"], manual["provenance"]["source_ids"])
        self.assertEqual(
            manual_snapshot["evidence_link_ids"],
            [item["id"] for item in manual["provenance"]["evidence_links"]],
        )
        self.assertEqual(
            manual_snapshot["state_history_ids"],
            [item["id"] for item in manual["relation"]["state_history"]],
        )

        imported = self.import_path["confirmation"]["expected"]
        imported_snapshot = imported["canonical_snapshot"]
        self.assertEqual(imported_snapshot["transaction_id"], imported["transaction"]["id"])
        self.assertEqual(imported_snapshot["current_version_id"], imported["transaction"]["current_version_id"])
        self.assertEqual(imported_snapshot["posting_ids"], [item["id"] for item in imported["transaction"]["postings"]])
        self.assertEqual(imported_snapshot["relation_id"], imported["relation"]["id"])
        self.assertEqual(imported_snapshot["relation_state"], imported["relation"]["state"])
        self.assertEqual(imported_snapshot["balances"], imported["balances"])
        self.assertEqual(imported_snapshot["reports"], imported["reports"])
        self.assertEqual(imported_snapshot["reconciliation"], imported["reconciliation"])
        self.assertEqual(imported_snapshot["source_ids"], imported["provenance"]["source_ids"])
        self.assertEqual(
            imported_snapshot["evidence_link_ids"],
            [item["id"] for item in imported["provenance"]["evidence_links"]],
        )
        self.assertEqual(
            imported_snapshot["candidate_history_ids"],
            [item["id"] for item in imported["candidate_history"]],
        )
        self.assertEqual(
            imported_snapshot["relation_history_ids"],
            [item["id"] for item in imported["relation"]["state_history"]],
        )

    def test_all_registry_references_resolve_and_evidence_roles_match_target_types(self):
        registry = self.case["entity_registry"]
        sources = {item["id"]: item for item in registry["source_records"]}
        evidence = {item["id"]: item for item in registry["evidence"]}
        transactions = {item["id"]: item for item in registry["transactions"]}
        relations = {item["id"]: item for item in registry["relations"]}
        postings = {
            posting["id"]: posting
            for transaction in transactions.values()
            for posting in transaction["postings"]
        }
        accounts = {item["id"]: item for item in self.accounts}
        self.assertEqual(len(sources), len(registry["source_records"]))
        self.assertEqual(len(evidence), len(registry["evidence"]))
        self.assertEqual(len(transactions), len(registry["transactions"]))
        self.assertEqual(len(relations), len(registry["relations"]))
        self.assertEqual(len(postings), sum(len(item["postings"]) for item in transactions.values()))

        for transaction in transactions.values():
            linked_original = transaction.get("linked_original_transaction_id")
            if linked_original is not None:
                self.assertIn(linked_original, transactions)
            for posting in transaction["postings"]:
                self.assertIn(posting["account_id"], accounts)

        for relation in relations.values():
            self.assertIn(relation["original_transaction_id"], transactions)
            if relation["refund_transaction_id"] is not None:
                self.assertIn(relation["refund_transaction_id"], transactions)

        for item in registry["evidence_links"]:
            self.assertIn(item["source_id"], sources)
            self.assertIn(item["evidence_id"], evidence)
            if item["role"] == "refund_relationship":
                self.assertIn(item["target_id"], relations)
            elif item["role"] in {"payment_asset_posting", "destination_asset_posting"}:
                self.assertIn(item["target_id"], postings)
                posting = postings[item["target_id"]]
                self.assertTrue(posting["reconciliation_eligible"])
                if item["role"] == "destination_asset_posting":
                    self.assertEqual(accounts[posting["account_id"]]["destination_kind"], "owned_asset")
            else:
                self.fail(f"unknown evidence role: {item['role']}")

        for link in registry["confirmation_links"]:
            self.assertEqual(link["role"], "refund_relationship_confirmation")
            self.assertIn(link["relation_id"], relations)
            self.assertIn(link["original_transaction_id"], transactions)

        embedded_evidence_links = []
        embedded_confirmation_links = []
        embedded_source_refs = []
        embedded_evidence_refs = []

        def walk(value):
            if isinstance(value, dict):
                if {"source_id", "evidence_id", "role", "target_id"}.issubset(value):
                    embedded_evidence_links.append(value)
                if value.get("role") == "refund_relationship_confirmation":
                    embedded_confirmation_links.append(value)
                for key, child in value.items():
                    if key == "source_ids" and isinstance(child, list):
                        embedded_source_refs.extend(child)
                    if key == "evidence_ids" and isinstance(child, list):
                        embedded_evidence_refs.extend(child)
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)

        walk(self.case)
        self.assertGreater(len(embedded_evidence_links), len(registry["evidence_links"]))
        for item in embedded_evidence_links:
            self.assertIn(item["source_id"], sources)
            self.assertIn(item["evidence_id"], evidence)
            if item["role"] == "refund_relationship":
                self.assertIn(item["target_id"], relations)
            else:
                self.assertIn(item["target_id"], postings)
        for link in embedded_confirmation_links:
            self.assertIn(link["relation_id"], relations)
            self.assertIn(link["original_transaction_id"], transactions)
        for source_id in embedded_source_refs:
            self.assertIn(source_id, sources)
        for evidence_id in embedded_evidence_refs:
            self.assertIn(evidence_id, evidence)

    def test_validation_reasons_zero_effects_and_no_guesses_are_frozen(self):
        invalid = {item["id"]: item for item in self.case["invalid_inputs"]}
        self.assertEqual(
            {
                item_id: (item["expected"]["field"], item["expected"]["reason"])
                for item_id, item in invalid.items()
            },
            {
                "zero-amount": ("amount", "must_be_positive"),
                "negative-amount": ("amount", "must_be_positive"),
                "cross-currency": ("currency", "same_currency_required"),
                "ineffective-original": ("original_transaction_id", "effective_confirmed_original_expense_required"),
                "unknown-destination": ("destination_account_id", "known_destination_account_required"),
                "non-owned-destination": ("destination_account_id", "owned_real_asset_destination_required"),
                "liability-destination": ("destination_account_id", "owned_real_asset_destination_required"),
                "store-credit-destination": ("destination_account_id", "owned_real_asset_destination_required"),
                "missing-category": ("category_id", "exact_original_secondary_category_required"),
                "primary-category": ("category_id", "exact_original_secondary_category_required"),
                "different-secondary-category": ("category_id", "exact_original_secondary_category_required"),
                "over-remaining-refundable": ("amount", "refund_amount_exceeds_remaining_refundable"),
                "missing-original-link": ("original_transaction_id", "original_transaction_confirmation_required"),
                "missing-destination-confirmation": ("destination_account_id", "destination_confirmation_required"),
            },
        )
        self.assertEqual(len(invalid), 14)
        for item in invalid.values():
            with self.subTest(invalid_input=item["id"]):
                expected = item["expected"]
                self.assertFalse(expected["accepted"])
                self.assertTrue(expected["state_unchanged"])
                self.assertEqual(expected["new_transaction_count"], 0)
                self.assertEqual(expected["new_posting_count"], 0)
                self.assertEqual(expected["new_version_count"], 0)
                self.assertEqual(expected["reconciliation_change_count"], 0)
                self.assertIsNone(expected["guessed_original_transaction_id"])
                self.assertIsNone(expected["guessed_category_id"])
                self.assertIsNone(expected["guessed_destination_account_id"])

    def test_scope_and_forbidden_effects_are_frozen(self):
        self.assertEqual(
            self.case["out_of_scope"],
            {
                "cross_currency_refund": "future_rule",
                "liability_or_store_credit_destination": "future_rule",
                "chargeback_or_compensation_excess": "future_rule",
                "multi_category_or_merged_purchase_allocation": "future_rule",
                "fee_refund": "future_rule",
                "staged_payment_cancellation_or_refund": "future_rule",
                "posting_corrections": "RG-12",
            },
        )
        self.assertTrue(
            {
                "rewrite_original_transaction",
                "rewrite_original_date",
                "classify_refund_as_ordinary_income",
                "auto_confirm_import_candidate",
                "guess_original_transaction",
                "guess_refund_category",
                "guess_destination_account",
                "partially_allocate_over_cap_refund",
                "reconcile_destination_from_merchant_notice",
                "reset_original_payment_reconciliation",
                "create_hidden_balancing_posting",
                "invoke_network",
                "invoke_sync",
                "invoke_intelligent_suggestion",
            }.issubset(set(self.case["forbidden_side_effects"]))
        )

    def _all_amounts(self, value, path="root"):
        amounts = []
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}.{key}"
                if (
                    isinstance(child, str)
                    and any(token in key for token in ("amount", "balance", "consumption", "cash_", "income", "net_worth"))
                    and key not in {"original_source_payload_hash"}
                ):
                    amounts.append((child_path, child))
                else:
                    amounts.extend(self._all_amounts(child, child_path))
        elif isinstance(value, list):
            for index, child in enumerate(value):
                amounts.extend(self._all_amounts(child, f"{path}[{index}]"))
        return amounts


if __name__ == "__main__":
    unittest.main()
