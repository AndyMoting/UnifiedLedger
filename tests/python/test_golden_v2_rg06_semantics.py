from copy import deepcopy
import unittest

import golden_cases.v2 as golden_v2
from golden_cases import GoldenCaseError


STATE_PATH = "$.states[0]"
PRECISIONS = {"CNY": 2, "USD": 2}


def history_event(
    sequence: int,
    event: str,
    occurred_at: str,
    paid_amount: str,
    due_amount: str,
    payment_id: str | None,
    payment_progress: str,
    fulfillment_status: str,
) -> dict:
    return {
        "id": f"history-{sequence}",
        "sequence": sequence,
        "event": event,
        "occurred_at": occurred_at,
        "total_amount": "300.00",
        "paid_amount": paid_amount,
        "due_amount": due_amount,
        "payment_id": payment_id,
        "payment_progress": payment_progress,
        "fulfillment_status": fulfillment_status,
        "state_transition_effect_count": 0,
    }


def staged_payment_state() -> dict:
    deposit_at = "2026-04-28T10:00:00+08:00"
    final_at = "2026-05-03T16:30:00+08:00"
    state = {
        "id": "state-rg06-semantics",
        "root_id": "root-rg06-semantics",
        "as_of_operation_id": None,
        "catalog": {
            "accounts": [
                {
                    "id": "asset-bank",
                    "name": "Synthetic bank",
                    "kind": "asset",
                    "currency": "CNY",
                    "owned_by_user": True,
                    "real_account": True,
                    "reconciliation_eligible": True,
                },
                {
                    "id": "expense-service",
                    "name": "Synthetic service expense",
                    "kind": "expense",
                    "currency": "CNY",
                    "owned_by_user": False,
                    "real_account": False,
                    "reconciliation_eligible": False,
                },
            ],
            "categories": [
                {
                    "id": "category-services",
                    "name": "Services",
                    "parent_id": None,
                    "posting_account_id": None,
                    "active": True,
                },
                {
                    "id": "category-service",
                    "name": "Synthetic service",
                    "parent_id": "category-services",
                    "posting_account_id": "expense-service",
                    "active": True,
                },
            ],
        },
        "transactions": [
            {
                "id": "transaction-deposit",
                "type": "expense",
                "current_version_id": "version-deposit",
            },
            {
                "id": "transaction-final",
                "type": "expense",
                "current_version_id": "version-final",
            },
        ],
        "transaction_versions": [
            {
                "id": "version-deposit",
                "transaction_id": "transaction-deposit",
                "version_number": 1,
                "posting_set_id": "posting-set-deposit",
                "occurred_at": deposit_at,
                "statistics_at": deposit_at,
                "effective_at": deposit_at,
                "note": "",
            },
            {
                "id": "version-final",
                "transaction_id": "transaction-final",
                "version_number": 1,
                "posting_set_id": "posting-set-final",
                "occurred_at": final_at,
                "statistics_at": final_at,
                "effective_at": final_at,
                "note": "",
            },
        ],
        "posting_sets": [
            {
                "id": "posting-set-deposit",
                "posting_ids": ["posting-expense-deposit", "posting-asset-deposit"],
            },
            {
                "id": "posting-set-final",
                "posting_ids": ["posting-expense-final", "posting-asset-final"],
            },
        ],
        "postings": [
            {
                "id": "posting-expense-deposit",
                "posting_set_id": "posting-set-deposit",
                "account_id": "expense-service",
                "category_id": "category-service",
                "amount": "80.00",
                "currency": "CNY",
                "role": "expense",
                "reconciliation_eligible": False,
            },
            {
                "id": "posting-asset-deposit",
                "posting_set_id": "posting-set-deposit",
                "account_id": "asset-bank",
                "amount": "-80.00",
                "currency": "CNY",
                "role": "payment_asset",
                "reconciliation_eligible": True,
            },
            {
                "id": "posting-expense-final",
                "posting_set_id": "posting-set-final",
                "account_id": "expense-service",
                "category_id": "category-service",
                "amount": "220.00",
                "currency": "CNY",
                "role": "expense",
                "reconciliation_eligible": False,
            },
            {
                "id": "posting-asset-final",
                "posting_set_id": "posting-set-final",
                "account_id": "asset-bank",
                "amount": "-220.00",
                "currency": "CNY",
                "role": "payment_asset",
                "reconciliation_eligible": True,
            },
        ],
        "sources": [],
        "candidates": [],
        "confirmations": [],
        "evidence": [],
        "evidence_links": [],
        "relations": [
            {
                "id": "relation-staged-payment",
                "type": "staged_payment",
                "member_refs": [
                    {"kind": "domain_entity", "id": "lifecycle-staged-payment"},
                    {"kind": "domain_entity", "id": "installment-deposit"},
                    {"kind": "domain_entity", "id": "installment-final"},
                ],
                "payload": {},
            }
        ],
        "domain_entities": [
            {
                "id": "lifecycle-staged-payment",
                "type": "staged_payment_lifecycle",
                "payload": {
                    "total_amount": "300.00",
                    "paid_amount": "300.00",
                    "due_amount": "0.00",
                    "currency": "CNY",
                    "category_id": "category-service",
                    "display_name": "Synthetic staged payment",
                    "system_managed": True,
                    "generic_order_lifecycle": False,
                    "state_history": [
                        history_event(
                            1,
                            "group_created",
                            "2026-04-20T09:00:00+08:00",
                            "0.00",
                            "300.00",
                            None,
                            "unpaid",
                            "in_progress",
                        ),
                        history_event(
                            2,
                            "payment_confirmed",
                            deposit_at,
                            "80.00",
                            "220.00",
                            "installment-deposit",
                            "partially_paid",
                            "in_progress",
                        ),
                        history_event(
                            3,
                            "fulfillment_changed",
                            "2026-05-01T12:00:00+08:00",
                            "80.00",
                            "220.00",
                            None,
                            "partially_paid",
                            "fulfilled",
                        ),
                        history_event(
                            4,
                            "payment_confirmed",
                            final_at,
                            "300.00",
                            "0.00",
                            "installment-final",
                            "paid_in_full",
                            "fulfilled",
                        ),
                        history_event(
                            5,
                            "completion_confirmed",
                            "2026-05-04T09:00:00+08:00",
                            "300.00",
                            "0.00",
                            None,
                            "paid_in_full",
                            "fulfilled",
                        ),
                    ],
                },
            },
            {
                "id": "installment-deposit",
                "type": "installment_payment",
                "payload": {
                    "role": "deposit",
                    "amount": "80.00",
                    "currency": "CNY",
                    "funding_account_id": "asset-bank",
                    "transaction_id": "transaction-deposit",
                    "expense_posting_id": "posting-expense-deposit",
                    "asset_posting_id": "posting-asset-deposit",
                    "actual_payment_at": deposit_at,
                    "statistics_at": deposit_at,
                },
            },
            {
                "id": "installment-final",
                "type": "installment_payment",
                "payload": {
                    "role": "final",
                    "amount": "220.00",
                    "currency": "CNY",
                    "funding_account_id": "asset-bank",
                    "transaction_id": "transaction-final",
                    "expense_posting_id": "posting-expense-final",
                    "asset_posting_id": "posting-asset-final",
                    "actual_payment_at": final_at,
                    "statistics_at": final_at,
                },
            },
        ],
        "audit_links": [],
        "posting_reconciliations": [],
        "balances": [],
        "reports": [],
        "derived_statuses": [],
    }
    return state


def validate_relations(state: dict) -> None:
    indexes = golden_v2._state_indexes(state, STATE_PATH)
    current = {}
    for transaction in state["transactions"]:
        version = indexes["transaction_versions"][transaction["current_version_id"]]
        posting_set = indexes["posting_sets"][version["posting_set_id"]]
        current[transaction["id"]] = (
            transaction,
            version,
            [indexes["postings"][posting_id] for posting_id in posting_set["posting_ids"]],
        )
    golden_v2._validate_relations(state, STATE_PATH, indexes, current, PRECISIONS)


def lifecycle(state: dict) -> dict:
    return next(
        entity
        for entity in state["domain_entities"]
        if entity["type"] == "staged_payment_lifecycle"
    )


def installment(state: dict, role: str) -> dict:
    return next(
        entity
        for entity in state["domain_entities"]
        if entity["type"] == "installment_payment"
        and entity["payload"]["role"] == role
    )


class Rg06StateSemanticsCheckpointTests(unittest.TestCase):
    def assert_relation_mutations_rejected(self, mutations: dict[str, dict]) -> None:
        for label, state in mutations.items():
            with self.subTest(label=label):
                with self.assertRaises(GoldenCaseError):
                    validate_relations(state)

    def test_complete_relation_lifecycle_and_installments_reach_relation_helper(self):
        state = staged_payment_state()

        validate_relations(state)

        self.assertEqual(1, len(state["relations"]))
        self.assertEqual(3, len(state["relations"][0]["member_refs"]))

    def test_relation_requires_one_lifecycle_and_unique_deposit_final_members(self):
        mutations = {}

        state = staged_payment_state()
        state["relations"][0]["member_refs"].pop(0)
        mutations["missing lifecycle"] = state

        state = staged_payment_state()
        state["relations"][0]["member_refs"][0]["kind"] = "transaction"
        mutations["wrong member kind"] = state

        state = staged_payment_state()
        state["relations"][0]["member_refs"][2] = deepcopy(
            state["relations"][0]["member_refs"][1]
        )
        mutations["duplicate member reference"] = state

        state = staged_payment_state()
        state["relations"][0]["member_refs"].pop(1)
        mutations["final without deposit"] = state

        state = staged_payment_state()
        extra = deepcopy(installment(state, "deposit"))
        extra["id"] = "installment-deposit-duplicate-role"
        state["domain_entities"].append(extra)
        state["relations"][0]["member_refs"][2] = {
            "kind": "domain_entity",
            "id": extra["id"],
        }
        mutations["two deposit members"] = state

        state = staged_payment_state()
        extra = deepcopy(lifecycle(state))
        extra["id"] = "lifecycle-staged-payment-duplicate"
        state["domain_entities"].append(extra)
        state["relations"][0]["member_refs"][2] = {
            "kind": "domain_entity",
            "id": extra["id"],
        }
        mutations["two lifecycle members"] = state

        state = staged_payment_state()
        second_relation = deepcopy(state["relations"][0])
        second_relation["id"] = "relation-staged-payment-overlap"
        state["relations"].append(second_relation)
        mutations["member owned by two staged relations"] = state

        self.assert_relation_mutations_rejected(mutations)

    def test_lifecycle_arithmetic_currency_installment_sum_and_chronology_are_exact(self):
        mutations = {}

        state = staged_payment_state()
        lifecycle(state)["payload"]["due_amount"] = "1.00"
        mutations["current total arithmetic"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["paid_amount"] = "299.00"
        mutations["paid amount equals installment sum"] = state

        state = staged_payment_state()
        installment(state, "final")["payload"]["currency"] = "USD"
        mutations["single lifecycle currency"] = state

        state = staged_payment_state()
        installment(state, "final")["payload"]["amount"] = "219.00"
        mutations["installment sum equals paid amount"] = state

        state = staged_payment_state()
        installment(state, "final")["payload"]["actual_payment_at"] = (
            installment(state, "deposit")["payload"]["actual_payment_at"]
        )
        mutations["final actual time after deposit"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][3]["occurred_at"] = (
            lifecycle(state)["payload"]["state_history"][1]["occurred_at"]
        )
        mutations["final history time after deposit"] = state

        self.assert_relation_mutations_rejected(mutations)

    def test_source_payment_chronology_uses_parsed_instants(self):
        state = staged_payment_state()
        installment(state, "deposit")["payload"]["source_payment_at"] = (
            "2026-05-03T10:00:00+08:00"
        )
        installment(state, "final")["payload"]["source_payment_at"] = (
            "2026-05-03T03:00:01+00:00"
        )

        validate_relations(state)

        mutations = {}

        state = staged_payment_state()
        installment(state, "deposit")["payload"]["source_payment_at"] = (
            "2026-05-03T10:00:00+08:00"
        )
        installment(state, "final")["payload"]["source_payment_at"] = (
            "2026-05-03T02:00:00+00:00"
        )
        mutations["equal source instants"] = state

        state = staged_payment_state()
        installment(state, "deposit")["payload"]["source_payment_at"] = (
            "2026-05-03T10:00:00+08:00"
        )
        installment(state, "final")["payload"]["source_payment_at"] = (
            "2026-05-03T10:30:00+09:00"
        )
        mutations["lexically later but instant earlier"] = state

        self.assert_relation_mutations_rejected(mutations)

    def test_lifecycle_history_is_continuous_ordered_and_snapshot_exact(self):
        mutations = {}

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][2]["sequence"] = 4
        mutations["continuous sequence"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][2]["occurred_at"] = (
            "2026-04-19T12:00:00+08:00"
        )
        mutations["ordered occurrence time"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][1]["payment_id"] = (
            "installment-final"
        )
        mutations["payment event exact installment"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][2]["payment_id"] = (
            "installment-deposit"
        )
        mutations["non-payment event null payment"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][3]["paid_amount"] = "299.00"
        mutations["history cumulative payment sum"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][1]["due_amount"] = "219.00"
        mutations["history total arithmetic"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"][-1]["due_amount"] = "1.00"
        mutations["latest snapshot equals current lifecycle"] = state

        state = staged_payment_state()
        lifecycle(state)["payload"]["state_history"].pop(3)
        mutations["every installment has one payment event"] = state

        self.assert_relation_mutations_rejected(mutations)

    def test_installments_bind_exact_current_transaction_postings_accounts_and_category(self):
        mutations = {}

        state = staged_payment_state()
        installment(state, "deposit")["payload"]["transaction_id"] = "transaction-final"
        mutations["exact transaction"] = state

        state = staged_payment_state()
        installment(state, "deposit")["payload"]["expense_posting_id"] = (
            "posting-expense-final"
        )
        mutations["expense posting belongs to current set"] = state

        state = staged_payment_state()
        installment(state, "deposit")["payload"]["asset_posting_id"] = (
            "posting-expense-deposit"
        )
        mutations["distinct expense and asset postings"] = state

        state = staged_payment_state()
        state["postings"][1]["role"] = "expense"
        mutations["asset posting role"] = state

        state = staged_payment_state()
        state["postings"][0]["role"] = "payment_asset"
        mutations["expense posting role"] = state

        state = staged_payment_state()
        state["postings"][1]["amount"] = "-79.00"
        mutations["asset posting exact negative amount"] = state

        state = staged_payment_state()
        state["postings"][0]["amount"] = "79.00"
        mutations["expense posting exact positive amount"] = state

        state = staged_payment_state()
        state["postings"][1]["currency"] = "USD"
        mutations["posting currency"] = state

        state = staged_payment_state()
        state["postings"][0]["category_id"] = "category-services"
        mutations["lifecycle secondary category"] = state

        state = staged_payment_state()
        state["postings"][1]["account_id"] = "expense-service"
        mutations["funding account binding"] = state

        state = staged_payment_state()
        state["catalog"]["accounts"][0]["owned_by_user"] = False
        mutations["funding account ownership"] = state

        state = staged_payment_state()
        state["catalog"]["accounts"][0]["real_account"] = False
        mutations["funding account reality"] = state

        state = staged_payment_state()
        state["catalog"]["accounts"][0]["kind"] = "liability"
        mutations["funding account asset kind"] = state

        state = staged_payment_state()
        state["catalog"]["accounts"][0]["reconciliation_eligible"] = False
        mutations["funding account eligibility"] = state

        state = staged_payment_state()
        state["transaction_versions"][0]["occurred_at"] = (
            "2026-04-28T10:00:01+08:00"
        )
        mutations["actual payment transaction time"] = state

        state = staged_payment_state()
        state["transaction_versions"][0]["statistics_at"] = (
            "2026-04-28T10:00:01+08:00"
        )
        mutations["installment statistics time"] = state

        self.assert_relation_mutations_rejected(mutations)

    def test_source_and_evidence_use_one_exact_origin_time_key(self):
        def add_pair(state: dict, label: str, role: str, time_key: str) -> None:
            payment = installment(state, role)
            payment_id = payment["id"]
            amount = payment["payload"]["amount"]
            source_id = f"source-{label}"
            evidence_id = f"evidence-{label}"
            origin_time = payment["payload"]["actual_payment_at"]
            source_payload = {
                "amount": amount,
                "currency": payment["payload"]["currency"],
                time_key: origin_time,
            }
            state["sources"].append(
                {
                    "id": source_id,
                    "type": "staged_payment_bank_fact",
                    "payload": source_payload,
                }
            )
            state["evidence"].append(
                {
                    "id": evidence_id,
                    "type": "staged_payment_bank_payment",
                    "source_ids": [source_id],
                    "payload": {"payment_id": payment_id, time_key: origin_time},
                }
            )
            state["evidence_links"].append(
                {
                    "id": f"evidence-link-{label}",
                    "evidence_id": evidence_id,
                    "target_kind": "posting",
                    "target_id": payment["payload"]["asset_posting_id"],
                    "role": "payment_asset_posting",
                }
            )

        def error_for(state: dict) -> Exception | None:
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    {},
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        for label, role, time_key in (
            ("manual", "deposit", "observed_at"),
            ("imported", "final", "source_payment_at"),
        ):
            state = staged_payment_state()
            add_pair(state, label, role, time_key)
            error = error_for(state)
            if error is not None:
                failures.append(f"valid {label}: {type(error).__name__}: {error}")

        invalid_states = {}
        state = staged_payment_state()
        add_pair(state, "manual", "deposit", "observed_at")
        state["sources"][0]["payload"].pop("observed_at")
        invalid_states["missing"] = state

        state = staged_payment_state()
        add_pair(state, "manual", "deposit", "observed_at")
        state["sources"][0]["payload"]["observed_at"] = None
        state["evidence"][0]["payload"]["observed_at"] = None
        invalid_states["null"] = state

        state = staged_payment_state()
        add_pair(state, "manual", "deposit", "observed_at")
        state["sources"][0]["payload"]["source_payment_at"] = (
            state["sources"][0]["payload"]["observed_at"]
        )
        state["evidence"][0]["payload"]["source_payment_at"] = (
            state["evidence"][0]["payload"]["observed_at"]
        )
        invalid_states["doubled"] = state

        for replacement in ("payment_at", "created_at", "confirmed_at"):
            state = staged_payment_state()
            add_pair(state, "manual", "deposit", "observed_at")
            for item in (state["sources"][0], state["evidence"][0]):
                item["payload"][replacement] = item["payload"].pop("observed_at")
            invalid_states[replacement] = state

        state = staged_payment_state()
        add_pair(state, "imported", "final", "source_payment_at")
        state["evidence"][0]["payload"]["source_payment_at"] = (
            "2026-05-03T16:30:00.000000+08:00"
        )
        invalid_states["not byte equal"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_source_amount_and_currency_bind_exact_installment_and_negative_asset_posting(self):
        def evidence_state() -> dict:
            state = staged_payment_state()
            payment = installment(state, "final")
            source_id = "source-final-bank"
            evidence_id = "evidence-final-bank"
            source_payload = {
                "amount": "220.00",
                "currency": "CNY",
                "source_payment_at": payment["payload"]["actual_payment_at"],
            }
            state["sources"] = [
                {
                    "id": source_id,
                    "type": "staged_payment_bank_fact",
                    "payload": source_payload,
                }
            ]
            state["evidence"] = [
                {
                    "id": evidence_id,
                    "type": "staged_payment_bank_payment",
                    "source_ids": [source_id],
                    "payload": {
                        "payment_id": payment["id"],
                        "source_payment_at": payment["payload"]["actual_payment_at"],
                    },
                }
            ]
            state["evidence_links"] = [
                {
                    "id": "evidence-link-final-bank",
                    "evidence_id": evidence_id,
                    "target_kind": "posting",
                    "target_id": "posting-asset-final",
                    "role": "payment_asset_posting",
                }
            ]
            return state

        def error_for(state: dict) -> Exception | None:
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    {},
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        error = error_for(evidence_state())
        if error is not None:
            failures.append(f"valid binding: {type(error).__name__}: {error}")

        invalid_states = {}
        state = evidence_state()
        state["sources"][0]["payload"]["amount"] = "219.00"
        invalid_states["amount differs from installment"] = state

        state = evidence_state()
        state["sources"][0]["payload"]["currency"] = "USD"
        invalid_states["currency differs from installment"] = state

        state = evidence_state()
        state["evidence_links"][0]["target_id"] = "posting-asset-deposit"
        invalid_states["evidence target differs from payment"] = state

        state = evidence_state()
        state["postings"][3]["amount"] = "-219.00"
        invalid_states["asset posting amount mismatch"] = state

        state = evidence_state()
        state["postings"][3]["amount"] = "220.00"
        invalid_states["asset posting must be negative"] = state

        state = evidence_state()
        state["evidence"][0]["payload"]["payment_id"] = "installment-deposit"
        invalid_states["evidence payment mismatch"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_mirror_requires_exact_earlier_original_lineage_and_merged_into_link(self):
        def mirror_state() -> dict:
            state = staged_payment_state()
            payment_at = installment(state, "final")["payload"]["actual_payment_at"]
            original_source_payload = {
                "amount": "220.00",
                "currency": "CNY",
                "source_payment_at": payment_at,
            }
            mirror_source_payload = {
                "amount": "-220.00",
                "currency": "CNY",
                "source_payment_at": payment_at,
            }
            state["sources"] = [
                {
                    "id": "source-final-original",
                    "type": "staged_payment_bank_fact",
                    "payload": original_source_payload,
                },
                {
                    "id": "source-final-mirror",
                    "type": "staged_payment_bank_fact",
                    "payload": {
                        "mirror_of_source_id": "source-final-original",
                        **mirror_source_payload,
                    },
                },
            ]
            state["evidence"] = [
                {
                    "id": "evidence-final-original",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-final-original"],
                    "payload": {
                        "payment_id": "installment-final",
                        "source_payment_at": payment_at,
                    },
                },
                {
                    "id": "evidence-final-mirror",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-final-mirror"],
                    "payload": {
                        "payment_id": "installment-final",
                        "source_payment_at": payment_at,
                        "mirror_of_evidence_id": "evidence-final-original",
                        "merged_into_evidence_link_id": "evidence-link-final-original",
                    },
                },
            ]
            state["evidence_links"] = [
                {
                    "id": "evidence-link-final-original",
                    "evidence_id": "evidence-final-original",
                    "target_kind": "posting",
                    "target_id": "posting-asset-final",
                    "role": "payment_asset_posting",
                }
            ]
            return state

        def error_for(state: dict) -> Exception | None:
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    {},
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        error = error_for(mirror_state())
        if error is not None:
            failures.append(f"valid mirror: {type(error).__name__}: {error}")

        invalid_states = {}
        state = mirror_state()
        state["sources"].reverse()
        invalid_states["original source must be earlier"] = state

        state = mirror_state()
        state["sources"][1]["payload"]["mirror_of_source_id"] = "source-missing"
        invalid_states["exact original source"] = state

        state = mirror_state()
        state["evidence"][1]["payload"]["mirror_of_evidence_id"] = "evidence-missing"
        invalid_states["exact original evidence"] = state

        state = mirror_state()
        state["sources"][1]["payload"]["amount"] = "220.00"
        invalid_states["opposite sign"] = state

        state = mirror_state()
        state["sources"][1]["payload"]["amount"] = "-219.00"
        invalid_states["equal absolute amount"] = state

        state = mirror_state()
        state["sources"][1]["payload"]["currency"] = "USD"
        invalid_states["same currency"] = state

        state = mirror_state()
        state["evidence"][1]["payload"]["payment_id"] = "installment-deposit"
        invalid_states["same payment"] = state

        state = mirror_state()
        state["evidence"][1]["payload"].pop("merged_into_evidence_link_id")
        invalid_states["missing merged_into"] = state

        state = mirror_state()
        state["evidence"][1]["payload"]["merged_into_evidence_link_id"] = (
            "evidence-link-missing"
        )
        invalid_states["exact merged_into link"] = state

        state = mirror_state()
        duplicate = deepcopy(state["evidence_links"][0])
        duplicate["id"] = "evidence-link-final-mirror"
        duplicate["evidence_id"] = "evidence-final-mirror"
        state["evidence_links"].append(duplicate)
        invalid_states["no duplicate posting result"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_known_staged_candidate_has_exact_pending_provenance_without_formal_binding(self):
        def candidate_state() -> dict:
            state = staged_payment_state()
            payment_at = installment(state, "deposit")["payload"]["actual_payment_at"]
            state["sources"] = [
                {
                    "id": "source-candidate-deposit",
                    "type": "staged_payment_bank_fact",
                    "payload": {
                        "amount": "-80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence"] = [
                {
                    "id": "evidence-candidate-deposit",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-candidate-deposit"],
                    "payload": {
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence_links"] = []
            state["candidates"] = [
                {
                    "id": "candidate-staged-deposit",
                    "type": "staged_payment",
                    "source_ids": ["source-candidate-deposit"],
                    "confidence": "1.00",
                    "payload": {
                        "payment_role": "deposit",
                        "amount": "80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                        "evidence_ref": "evidence-candidate-deposit",
                        "provenance": {
                            "rule": "staged_payment_bank_fact",
                            "rule_version": 1,
                        },
                        "requires_confirmation": [
                            "relation_id",
                            "payment_role",
                            "category_id",
                            "funding_account_id",
                        ],
                    },
                    "status_history": [
                        {
                            "id": "candidate-status-pending",
                            "sequence": 1,
                            "status": "pending_confirmation",
                        }
                    ],
                }
            ]
            return state

        def error_for(state: dict) -> Exception | None:
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    {
                        "operation-confirm-candidate": {
                            "root_id": state["root_id"],
                        }
                    },
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        error = error_for(candidate_state())
        if error is not None:
            failures.append(f"valid known candidate: {type(error).__name__}: {error}")

        invalid_states = {}
        state = candidate_state()
        state["candidates"][0]["source_ids"] = []
        invalid_states["sole source"] = state

        state = candidate_state()
        state["candidates"][0]["payload"]["evidence_ref"] = "evidence-missing"
        invalid_states["sole exact evidence"] = state

        state = candidate_state()
        state["candidates"][0]["confidence"] = "0.50"
        invalid_states["known confidence"] = state

        state = candidate_state()
        state["candidates"][0]["payload"]["payment_role"] = "final"
        invalid_states["known role"] = state

        state = candidate_state()
        state["candidates"][0]["payload"]["provenance"]["rule_version"] = 2
        invalid_states["exact provenance"] = state

        state = candidate_state()
        state["confirmations"] = [
            {
                "id": "confirmation-premature",
                "type": "candidate_confirmation",
                "operation_id": "operation-confirm-candidate",
                "subject": {"kind": "candidate", "id": "candidate-staged-deposit"},
                "payload": {},
            }
        ]
        state["transaction_versions"][0]["confirmation_id"] = "confirmation-premature"
        invalid_states["pending formal prebinding"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_pending_staged_candidate_rejects_any_formal_binding(self):
        def candidate_state() -> dict:
            state = staged_payment_state()
            payment_at = installment(state, "deposit")["payload"]["actual_payment_at"]
            state["sources"] = [
                {
                    "id": "source-pending-unbound",
                    "type": "staged_payment_bank_fact",
                    "payload": {
                        "amount": "-80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence"] = [
                {
                    "id": "evidence-pending-unbound",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-pending-unbound"],
                    "payload": {"source_payment_at": payment_at},
                }
            ]
            state["evidence_links"] = []
            state["candidates"] = [
                {
                    "id": "candidate-pending-unbound",
                    "type": "staged_payment",
                    "source_ids": ["source-pending-unbound"],
                    "confidence": "1.00",
                    "payload": {
                        "payment_role": "deposit",
                        "amount": "80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                        "evidence_ref": "evidence-pending-unbound",
                        "provenance": {
                            "rule": "staged_payment_bank_fact",
                            "rule_version": 1,
                        },
                        "requires_confirmation": [
                            "relation_id",
                            "payment_role",
                            "category_id",
                            "funding_account_id",
                        ],
                    },
                    "status_history": [
                        {
                            "id": "candidate-status-pending-unbound",
                            "sequence": 1,
                            "status": "pending_confirmation",
                        }
                    ],
                }
            ]
            return state

        def validate(state: dict) -> None:
            golden_v2._validate_references(
                state,
                STATE_PATH,
                golden_v2._state_indexes(state, STATE_PATH),
                {
                    "operation-confirm-pending-unbound": {
                        "root_id": state["root_id"],
                    }
                },
                PRECISIONS,
                golden_v2.ZoneInfo("Asia/Shanghai"),
            )

        validate(candidate_state())

        mutations = {}

        state = candidate_state()
        state["evidence"][0]["payload"]["payment_id"] = "installment-deposit"
        mutations["evidence payment"] = state

        state = candidate_state()
        state["evidence_links"].append(
            {
                "id": "link-pending-unbound",
                "evidence_id": "evidence-pending-unbound",
                "target_kind": "posting",
                "target_id": "posting-asset-deposit",
                "role": "payment_asset_posting",
            }
        )
        mutations["evidence link"] = state

        state = candidate_state()
        state["confirmations"] = [
            {
                "id": "confirmation-pending-unbound",
                "type": "candidate_confirmation",
                "operation_id": "operation-confirm-pending-unbound",
                "subject": {"kind": "candidate", "id": "candidate-pending-unbound"},
                "payload": {},
            }
        ]
        state["transaction_versions"][0]["confirmation_id"] = (
            "confirmation-pending-unbound"
        )
        mutations["candidate confirmation and formal version"] = state

        for label, state in mutations.items():
            with self.subTest(label=label):
                with self.assertRaises(GoldenCaseError):
                    validate(state)

    def test_ambiguous_staged_candidate_preserves_explicit_null_role_and_half_confidence(self):
        def candidate_state() -> dict:
            state = staged_payment_state()
            payment_at = installment(state, "deposit")["payload"]["actual_payment_at"]
            state["sources"] = [
                {
                    "id": "source-candidate-ambiguous",
                    "type": "staged_payment_bank_fact",
                    "payload": {
                        "amount": "-80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence"] = [
                {
                    "id": "evidence-candidate-ambiguous",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-candidate-ambiguous"],
                    "payload": {
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence_links"] = []
            state["candidates"] = [
                {
                    "id": "candidate-staged-ambiguous",
                    "type": "staged_payment",
                    "source_ids": ["source-candidate-ambiguous"],
                    "confidence": "0.50",
                    "payload": {
                        "payment_role": None,
                        "guessed_payment_role": None,
                        "amount": "80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                        "evidence_ref": "evidence-candidate-ambiguous",
                        "provenance": {
                            "rule": "staged_payment_bank_fact",
                            "rule_version": 1,
                        },
                        "requires_confirmation": [
                            "relation_id",
                            "payment_role",
                            "category_id",
                            "funding_account_id",
                        ],
                    },
                    "status_history": [
                        {
                            "id": "candidate-status-ambiguous-pending",
                            "sequence": 1,
                            "status": "pending_confirmation",
                        }
                    ],
                }
            ]
            return state

        def error_for(state: dict) -> Exception | None:
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    {},
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        error = error_for(candidate_state())
        if error is not None:
            failures.append(f"valid ambiguous candidate: {type(error).__name__}: {error}")

        invalid_states = {}
        state = candidate_state()
        state["candidates"][0]["payload"].pop("payment_role")
        invalid_states["missing explicit payment null"] = state

        state = candidate_state()
        state["candidates"][0]["payload"].pop("guessed_payment_role")
        invalid_states["missing explicit guessed null"] = state

        state = candidate_state()
        state["candidates"][0]["payload"]["payment_role"] = "deposit"
        invalid_states["invented payment role"] = state

        state = candidate_state()
        state["candidates"][0]["payload"]["guessed_payment_role"] = "deposit"
        invalid_states["guessed payment role"] = state

        state = candidate_state()
        state["candidates"][0]["confidence"] = "1.00"
        invalid_states["ambiguous confidence"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_confirmed_staged_candidate_has_one_exact_confirmation_and_formal_binding(self):
        def candidate_state() -> dict:
            state = staged_payment_state()
            payment_at = installment(state, "deposit")["payload"]["actual_payment_at"]
            state["sources"] = [
                {
                    "id": "source-candidate-confirmed",
                    "type": "staged_payment_bank_fact",
                    "payload": {
                        "amount": "-80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence"] = [
                {
                    "id": "evidence-candidate-confirmed",
                    "type": "staged_payment_bank_payment",
                    "source_ids": ["source-candidate-confirmed"],
                    "payload": {
                        "payment_id": "installment-deposit",
                        "source_payment_at": payment_at,
                    },
                }
            ]
            state["evidence_links"] = [
                {
                    "id": "evidence-link-candidate-confirmed",
                    "evidence_id": "evidence-candidate-confirmed",
                    "target_kind": "posting",
                    "target_id": "posting-asset-deposit",
                    "role": "payment_asset_posting",
                }
            ]
            state["candidates"] = [
                {
                    "id": "candidate-staged-confirmed",
                    "type": "staged_payment",
                    "source_ids": ["source-candidate-confirmed"],
                    "confidence": "1.00",
                    "payload": {
                        "payment_role": "deposit",
                        "amount": "80.00",
                        "currency": "CNY",
                        "source_payment_at": payment_at,
                        "evidence_ref": "evidence-candidate-confirmed",
                        "provenance": {
                            "rule": "staged_payment_bank_fact",
                            "rule_version": 1,
                        },
                        "requires_confirmation": [
                            "relation_id",
                            "payment_role",
                            "category_id",
                            "funding_account_id",
                        ],
                    },
                    "status_history": [
                        {
                            "id": "candidate-status-confirmed-pending",
                            "sequence": 1,
                            "status": "pending_confirmation",
                        },
                        {
                            "id": "candidate-status-confirmed",
                            "sequence": 2,
                            "status": "confirmed",
                        },
                    ],
                }
            ]
            state["confirmations"] = [
                {
                    "id": "confirmation-staged-candidate",
                    "type": "candidate_confirmation",
                    "operation_id": "operation-confirm-candidate",
                    "subject": {
                        "kind": "candidate",
                        "id": "candidate-staged-confirmed",
                    },
                    "payload": {},
                }
            ]
            state["transaction_versions"][0]["confirmation_id"] = (
                "confirmation-staged-candidate"
            )
            return state

        def error_for(state: dict) -> Exception | None:
            operations = {
                "operation-confirm-candidate": {"root_id": state["root_id"]},
                "operation-confirm-candidate-duplicate": {
                    "root_id": state["root_id"]
                },
            }
            try:
                golden_v2._validate_references(
                    state,
                    STATE_PATH,
                    golden_v2._state_indexes(state, STATE_PATH),
                    operations,
                    PRECISIONS,
                    golden_v2.ZoneInfo("Asia/Shanghai"),
                )
            except Exception as error:
                return error
            return None

        failures = []
        error = error_for(candidate_state())
        if error is not None:
            failures.append(f"valid confirmed candidate: {type(error).__name__}: {error}")

        invalid_states = {}
        state = candidate_state()
        state["transaction_versions"][0].pop("confirmation_id")
        invalid_states["dangling formal binding"] = state

        state = candidate_state()
        state["transaction_versions"][0].pop("confirmation_id")
        state["transaction_versions"][1]["confirmation_id"] = (
            "confirmation-staged-candidate"
        )
        invalid_states["wrong transaction binding"] = state

        state = candidate_state()
        state["relations"][0]["member_refs"] = [
            ref
            for ref in state["relations"][0]["member_refs"]
            if ref["id"] != "installment-deposit"
        ]
        invalid_states["wrong relation binding"] = state

        state = candidate_state()
        duplicate = deepcopy(state["confirmations"][0])
        duplicate["id"] = "confirmation-staged-candidate-duplicate"
        duplicate["operation_id"] = "operation-confirm-candidate-duplicate"
        state["confirmations"].append(duplicate)
        invalid_states["duplicate candidate confirmation"] = state

        state = candidate_state()
        state["candidates"][0]["status_history"].pop()
        invalid_states["pending candidate prebinding"] = state

        for label, state in invalid_states.items():
            error = error_for(state)
            if not isinstance(error, GoldenCaseError):
                failures.append(f"invalid {label}: {type(error).__name__}: {error}")

        self.assertEqual([], failures)

    def test_lifecycle_has_exact_three_statuses_from_latest_history(self):
        state = staged_payment_state()
        indexes = golden_v2._state_indexes(state, STATE_PATH)
        current = {}
        for transaction in state["transactions"]:
            version = indexes["transaction_versions"][transaction["current_version_id"]]
            posting_set = indexes["posting_sets"][version["posting_set_id"]]
            current[transaction["id"]] = (
                transaction,
                version,
                [
                    indexes["postings"][posting_id]
                    for posting_id in posting_set["posting_ids"]
                ],
            )
        reconciliation_by_posting = {
            "posting-asset-deposit": "matched",
            "posting-asset-final": "matched",
        }
        expected = golden_v2._expected_derived_statuses(
            state, indexes, current, reconciliation_by_posting
        )
        lifecycle_id = lifecycle(state)["id"]
        latest_history = lifecycle(state)["payload"]["state_history"][-1]
        lifecycle_statuses = {
            key: value
            for key, value in expected.items()
            if key[0] == "domain_entity" and key[1] == lifecycle_id
        }
        self.assertEqual(
            {
                ("domain_entity", lifecycle_id, "payment_progress"): latest_history[
                    "payment_progress"
                ],
                ("domain_entity", lifecycle_id, "fulfillment_status"): latest_history[
                    "fulfillment_status"
                ],
                ("domain_entity", lifecycle_id, "reconciliation"): "complete",
            },
            lifecycle_statuses,
        )

        state["derived_statuses"] = [
            {
                "target_kind": target_kind,
                "target_id": target_id,
                "status_name": status_name,
                "value": value,
            }
            for (target_kind, target_id, status_name), value in expected.items()
        ]
        golden_v2._validate_derived_statuses(
            state,
            STATE_PATH,
            indexes,
            {},
            current,
            reconciliation_by_posting,
        )

        lifecycle_indexes = [
            index
            for index, status in enumerate(state["derived_statuses"])
            if status["target_kind"] == "domain_entity"
            and status["target_id"] == lifecycle_id
        ]
        mutations = {}

        mutation = deepcopy(state)
        mutation["derived_statuses"].pop(lifecycle_indexes[0])
        mutations["missing"] = mutation

        mutation = deepcopy(state)
        mutation["derived_statuses"].append(
            deepcopy(mutation["derived_statuses"][lifecycle_indexes[0]])
        )
        mutations["duplicate"] = mutation

        mutation = deepcopy(state)
        mutation["derived_statuses"][lifecycle_indexes[0]]["target_kind"] = "relation"
        mutations["wrong target kind"] = mutation

        mutation = deepcopy(state)
        mutation["derived_statuses"][lifecycle_indexes[0]]["target_id"] = (
            "installment-deposit"
        )
        mutations["wrong target id"] = mutation

        mutation = deepcopy(state)
        mutation["derived_statuses"][lifecycle_indexes[0]].update(
            {"status_name": "allocation_status", "value": "active"}
        )
        mutations["extra or wrong status name"] = mutation

        mutation = deepcopy(state)
        mutation["derived_statuses"][lifecycle_indexes[0]]["value"] = "unpaid"
        mutations["wrong value"] = mutation

        for label, mutation in mutations.items():
            with self.subTest(label=label):
                with self.assertRaises(GoldenCaseError):
                    golden_v2._validate_derived_statuses(
                        mutation,
                        STATE_PATH,
                        golden_v2._state_indexes(mutation, STATE_PATH),
                        {},
                        current,
                        reconciliation_by_posting,
                    )

    def test_lifecycle_reconciliation_uses_exact_installment_n_m_matrix(self):
        cases = (
            ("zero installments ignores unrelated matches", (), "matched", "matched", False, "pending"),
            ("no matches ignores expense and unrelated", ("deposit", "final"), "pending", "has_difference", False, "pending"),
            ("one of two matched", ("deposit", "final"), "matched", "pending", False, "partial"),
            ("all installments matched", ("deposit", "final"), "matched", "matched", False, "complete"),
            ("ineligible exact posting does not count", ("deposit", "final"), "matched", "matched", True, "partial"),
        )
        for (
            label,
            member_roles,
            deposit_status,
            final_status,
            final_ineligible,
            expected_reconciliation,
        ) in cases:
            with self.subTest(label=label):
                state = staged_payment_state()
                allowed_member_ids = {"lifecycle-staged-payment"} | {
                    installment(state, role)["id"] for role in member_roles
                }
                state["relations"][0]["member_refs"] = [
                    ref
                    for ref in state["relations"][0]["member_refs"]
                    if ref["id"] in allowed_member_ids
                ]
                if final_ineligible:
                    next(
                        posting
                        for posting in state["postings"]
                        if posting["id"] == "posting-asset-final"
                    )["reconciliation_eligible"] = False

                indexes = golden_v2._state_indexes(state, STATE_PATH)
                current = {}
                for transaction in state["transactions"]:
                    version = indexes["transaction_versions"][
                        transaction["current_version_id"]
                    ]
                    posting_set = indexes["posting_sets"][version["posting_set_id"]]
                    current[transaction["id"]] = (
                        transaction,
                        version,
                        [
                            indexes["postings"][posting_id]
                            for posting_id in posting_set["posting_ids"]
                        ],
                    )
                reconciliation_by_posting = {
                    "posting-asset-deposit": deposit_status,
                    "posting-asset-final": final_status,
                    "posting-expense-deposit": "matched",
                    "posting-expense-final": "matched",
                    "posting-unrelated": "matched",
                }
                expected = golden_v2._expected_derived_statuses(
                    state, indexes, current, reconciliation_by_posting
                )

                self.assertEqual(
                    expected_reconciliation,
                    expected.get(
                        (
                            "domain_entity",
                            lifecycle(state)["id"],
                            "reconciliation",
                        )
                    ),
                )

    def test_confirmed_candidate_status_is_not_reconciliation_authority(self):
        state = staged_payment_state()
        state["candidates"] = [
            {
                "id": "candidate-confirmed-without-reconciliation",
                "type": "staged_payment",
                "source_ids": ["source-confirmed-without-reconciliation"],
                "confidence": "1.00",
                "payload": {},
                "status_history": [
                    {"id": "candidate-status-pending", "sequence": 1, "status": "pending_confirmation"},
                    {"id": "candidate-status-confirmed", "sequence": 2, "status": "confirmed"},
                ],
            }
        ]
        indexes = golden_v2._state_indexes(state, STATE_PATH)
        current = {}
        for transaction in state["transactions"]:
            version = indexes["transaction_versions"][transaction["current_version_id"]]
            posting_set = indexes["posting_sets"][version["posting_set_id"]]
            current[transaction["id"]] = (
                transaction,
                version,
                [indexes["postings"][posting_id] for posting_id in posting_set["posting_ids"]],
            )

        expected = golden_v2._expected_derived_statuses(
            state,
            indexes,
            current,
            {
                "posting-asset-deposit": "pending",
                "posting-asset-final": "pending",
            },
        )
        self.assertEqual(
            "pending",
            expected[("domain_entity", lifecycle(state)["id"], "reconciliation")],
        )


if __name__ == "__main__":
    unittest.main()
