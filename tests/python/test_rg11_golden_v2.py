from copy import deepcopy
import json
from pathlib import Path
import unittest

from golden_cases import GoldenCaseError, validate_golden_case_v2


ROOT = Path(__file__).resolve().parents[2]
CASE_PATH = ROOT / "golden" / "rules" / "rg-11.json"
ENTITY_COLLECTIONS = {
    "catalog_accounts": ("catalog", "accounts"),
    "catalog_categories": ("catalog", "categories"),
    "transactions": ("transactions",),
    "transaction_versions": ("transaction_versions",),
    "posting_sets": ("posting_sets",),
    "postings": ("postings",),
    "sources": ("sources",),
    "candidates": ("candidates",),
    "confirmations": ("confirmations",),
    "evidence": ("evidence",),
    "evidence_links": ("evidence_links",),
    "relations": ("relations",),
    "domain_entities": ("domain_entities",),
    "audit_links": ("audit_links",),
    "posting_reconciliations": ("posting_reconciliations",),
}


def by_id(items):
    return {item["id"]: item for item in items}


def collection(state, path):
    value = state
    for key in path:
        value = value[key]
    return value


def report_map(state):
    return {
        (report["period_type"], report["period"], metric["metric"], metric.get("currency")): {
            key: value for key, value in metric.items() if key != "metric"
        }
        for report in state["reports"]
        for metric in report["metrics"]
    }


def status_map(state):
    return {
        (item["target_kind"], item["target_id"], item["status_name"]): item["value"]
        for item in state["derived_statuses"]
    }


def state_payload(state):
    value = deepcopy(state)
    for key in ("id", "root_id", "as_of_operation_id"):
        value.pop(key)
    return value


class Rg11GoldenV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = json.loads(CASE_PATH.read_text(encoding="utf-8"))
        cls.states = by_id(cls.case["states"])
        cls.roots = by_id(cls.case["roots"])
        cls.operations = by_id(cls.case["operations"])

    def test_00_validate_golden_case_v2(self):
        validate_golden_case_v2(self.case)

    def test_roots_have_complete_state_chains(self):
        self.assertEqual(self.case["case"]["level"], "core_reserved")
        self.assertEqual(self.case["case"]["approval_status"], "approved")
        self.assertEqual(set(self.roots), {"root-main", "root-revision", "root-z-rejections"})
        self.assertEqual(len(self.case["states"]), 25)
        for root in self.roots.values():
            operations = [self.operations[item] for item in root["operation_ids"]]
            initial = self.states[root["initial_state_id"]]
            self.assertIsNone(initial["as_of_operation_id"])
            self.assertEqual(initial["root_id"], root["id"])
            previous = initial["id"]
            for operation in operations:
                self.assertEqual(operation["root_id"], root["id"])
                self.assertEqual(operation["baseline_state_id"], previous)
                result = self.states[operation["result_state_id"]]
                self.assertEqual(result["root_id"], root["id"])
                self.assertEqual(result["as_of_operation_id"], operation["id"])
                if operation["outcome"]["status"] == "accepted":
                    self.assertNotEqual(operation["baseline_state_id"], operation["result_state_id"])
                previous = result["id"]
            self.assertEqual(len(operations) + 1, len([state for state in self.case["states"] if state["root_id"] == root["id"]]))

    def test_all_states_are_complete_and_balanced(self):
        required = {
            "id", "root_id", "as_of_operation_id", "catalog", "transactions",
            "transaction_versions", "posting_sets", "postings", "sources", "candidates",
            "confirmations", "evidence", "evidence_links", "relations", "domain_entities",
            "audit_links", "posting_reconciliations", "balances", "reports", "derived_statuses",
        }
        for state in self.case["states"]:
            self.assertEqual(set(state), required)
            self.assertEqual(len(state["balances"]), 4)
            self.assertEqual(len(state["catalog"]["accounts"]), 4)
            for posting_set in state["posting_sets"]:
                postings = [item for item in state["postings"] if item["id"] in posting_set["posting_ids"]]
                self.assertEqual(sum(int(item["amount"].replace(".", "")) for item in postings), 0)

    def test_operation_deltas_match_actual_state_differences(self):
        for operation in self.case["operations"]:
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            declared = operation["deltas"]
            for name, path in ENTITY_COLLECTIONS.items():
                before = by_id(collection(baseline, path))
                after = by_id(collection(result, path))
                expected = {
                    "added_ids": sorted(set(after) - set(before)),
                    "changed_ids": sorted(item for item in set(before) & set(after) if before[item] != after[item]),
                    "removed_ids": sorted(set(before) - set(after)),
                }
                self.assertEqual(declared["entity_changes"][name], expected, operation["id"] + ":" + name)
            balance_before = {(item["account_id"], item["currency"]): item["amount"] for item in baseline["balances"]}
            balance_after = {(item["account_id"], item["currency"]): item["amount"] for item in result["balances"]}
            expected_balances = [
                {"key": {"account_id": key[0], "currency": key[1]}, "before": balance_before.get(key), "after": balance_after.get(key)}
                for key in sorted(set(balance_before) | set(balance_after))
                if balance_before.get(key) != balance_after.get(key)
            ]
            self.assertEqual(declared["value_changes"]["balances"], expected_balances, operation["id"])
            for name, mapper in (("reports", report_map), ("derived_statuses", status_map)):
                before, after = mapper(baseline), mapper(result)
                self.assertEqual(
                    {(tuple(sorted(item["key"].items())), json.dumps(item["before"], sort_keys=True), json.dumps(item["after"], sort_keys=True)) for item in declared["value_changes"][name]},
                    {(tuple(sorted((({"period_type": key[0], "period": key[1], "metric": key[2], **({"currency": key[3]} if key[3] else {})}) if name == "reports" else {"kind": key[0], "target_id": key[1], "status_name": key[2]}).items())), json.dumps(before.get(key), sort_keys=True), json.dumps(after.get(key), sort_keys=True)) for key in set(before) | set(after) if before.get(key) != after.get(key)},
                    operation["id"] + ":" + name,
                )
            if operation["outcome"]["status"] == "no_change":
                self.assertEqual(state_payload(baseline), state_payload(result))
                self.assertTrue(all(not value for change in declared["entity_changes"].values() for value in change.values()))
                self.assertTrue(all(not value for value in declared["value_changes"].values()))

    def test_main_and_revision_lifecycles(self):
        self.assertEqual(
            status_map(self.states["state-main-00"])[
                ("transaction", "main-opening", "reconciliation_summary")
            ],
            "pending",
        )
        self.assertEqual(
            status_map(self.states["state-main-01"])[
                ("transaction", "main-purchase", "reconciliation_summary")
            ],
            "pending",
        )
        self.assertEqual(
            status_map(self.states["state-revision-01"])[
                ("transaction", "revision-purchase", "reconciliation_summary")
            ],
            "pending",
        )
        main = self.states["state-main-06"]
        revision = self.states["state-revision-06"]
        main_entities = by_id(main["domain_entities"])
        revision_entities = by_id(revision["domain_entities"])
        self.assertEqual(main_entities["schedule-main"]["payload"]["anchor"], {"type": "month_end"})
        self.assertEqual(revision_entities["schedule-revision"]["payload"]["anchor"], {"type": "day_of_month", "day": 15})
        self.assertNotIn("day_of_month_anchor", main_entities["schedule-main"]["payload"])
        self.assertEqual([main_entities[item]["payload"]["amount"] for item in ("main-installment-01", "main-installment-02", "main-installment-03")], ["33.33", "33.33", "33.34"])
        self.assertEqual([revision_entities[item]["payload"]["amount"] for item in ("revision-installment-04", "revision-installment-05", "revision-installment-06")], ["22.22", "22.22", "22.23"])
        self.assertIsNone(revision_entities["revision-revision-01"]["payload"]["recognized_through"])
        self.assertEqual(revision_entities["revision-revision-02"]["payload"]["remaining_amount"], "66.67")
        self.assertEqual({item["account_id"]: item["amount"] for item in main["balances"]}["account-main-prepaid"], "0.00")
        self.assertEqual({item["account_id"]: item["amount"] for item in revision["balances"]}["account-revision-prepaid"], "0.00")
        versions = [item for item in main["transaction_versions"] if item["transaction_id"] == "main-recognition-01"]
        self.assertEqual([item["statistics_at"] for item in versions], ["2026-01-31T00:00:00+08:00", "2026-02-01T00:00:00+08:00"])
        self.assertEqual(self.operations["main-replay"]["outcome"]["status"], "no_change")

    def test_invalid_mutations_are_rejected(self):
        cases = []
        for amount in ("0.00", "-0.01", 100.0):
            value = deepcopy(self.case)
            value["operations"][0]["input"]["amount"] = amount
            cases.append(value)
        value = deepcopy(self.case)
        by_id(value["states"])["state-main-02"]["domain_entities"][1]["payload"]["remaining_amount"] = "99.99"
        cases.append(value)
        value = deepcopy(self.case)
        value["operations"][0]["input"]["currency"] = "USD"
        cases.append(value)
        value = deepcopy(self.case)
        by_id(value["states"])["state-revision-01"]["domain_entities"][0]["payload"]["anchor"] = {"type": "day_of_month", "day": 0}
        cases.append(value)
        value = deepcopy(self.case)
        duplicate = deepcopy(value["operations"][1]); duplicate["id"] = "duplicate-recognition"; duplicate["sequence"] = 99
        value["operations"].append(duplicate); cases.append(value)
        value = deepcopy(self.case)
        value["operations"][1]["input"]["amount"] = "100.01"; cases.append(value)
        value = deepcopy(self.case)
        by_id(value["states"])["state-revision-06"]["domain_entities"][2]["payload"]["amount"] = "22.22"; cases.append(value)
        for value in cases:
            with self.assertRaises(GoldenCaseError):
                validate_golden_case_v2(value)

    def test_rejected_operations_are_atomic_and_recomputed(self):
        root = self.roots["root-z-rejections"]
        operations = [self.operations[item] for item in root["operation_ids"]]
        self.assertEqual(len(operations), 10)
        self.assertEqual(
            {operation["outcome"]["reason_code"] for operation in operations},
            {
                "exact_decimal_string_required", "must_be_positive",
                "unsupported_currency", "currency_mismatch", "invalid_anchor",
                "installment_not_pending", "exceeds_remaining_prepaid",
                "invalid_revision_boundary", "invalid_installment_count",
            },
        )
        for operation in operations:
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            self.assertEqual(operation["operation_class"], "rejection")
            self.assertEqual(operation["outcome"]["status"], "rejected")
            self.assertNotIn("input", operation)
            self.assertIn("attempted_input", operation)
            self.assertNotEqual(baseline["id"], result["id"])
            self.assertEqual(state_payload(baseline), state_payload(result))
            self.assertEqual(operation["status_changes"], [])
            self.assertEqual(operation["returned_ids"], [])
            self.assertTrue(all(not ids for change in operation["deltas"]["entity_changes"].values() for ids in change.values()))
            self.assertTrue(all(not values for values in operation["deltas"]["value_changes"].values()))

        invalid = deepcopy(self.case)
        operation = by_id(invalid["operations"])["operation-reject-zero-amount"]
        operation["outcome"]["reason_code"] = "exact_decimal_string_required"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid)

    def test_calendar_anchor_drift_is_rejected(self):
        for state_id, installment_id, scheduled_at in (
            ("state-main-01", "main-installment-02", "2026-02-27T00:00:00+08:00"),
            ("state-revision-01", "revision-installment-02", "2026-02-16T00:00:00+08:00"),
        ):
            invalid = deepcopy(self.case)
            state = by_id(invalid["states"])[state_id]
            by_id(state["domain_entities"])[installment_id]["payload"]["scheduled_at"] = scheduled_at
            with self.subTest(installment_id=installment_id):
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(invalid)

    def test_correction_rejects_note_drift(self):
        invalid = deepcopy(self.case)
        state = by_id(invalid["states"])["state-main-05"]
        by_id(state["transaction_versions"])["main-recognition-01-v2"]["note"] = "drift"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(invalid)

    def test_accepted_actions_reject_unrelated_returned_ids(self):
        mutations = (
            ("main-create", [{"kind": "transaction", "id": "main-opening"}, {"kind": "domain_entity", "id": "schedule-main"}]),
            ("main-recognize-01", [{"kind": "transaction", "id": "main-opening"}]),
            ("revision-revise", [{"kind": "domain_entity", "id": "schedule-revision"}]),
            ("main-correct", [{"kind": "transaction_version", "id": "main-recognition-01-v1"}]),
        )
        for operation_id, returned_ids in mutations:
            invalid = deepcopy(self.case)
            by_id(invalid["operations"])[operation_id]["returned_ids"] = returned_ids
            with self.subTest(operation_id=operation_id):
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(invalid)

    def test_payment_time_counts_and_revision_boundaries_are_owned(self):
        mutations = []

        invalid = deepcopy(self.case)
        by_id(invalid["operations"])["main-create"]["input"]["installment_count"] = 2
        mutations.append(("create_count", invalid))

        invalid = deepcopy(self.case)
        state = by_id(invalid["states"])["state-main-01"]
        by_id(state["transaction_versions"])["main-purchase-v1"]["statistics_at"] = "2026-01-16T09:00:00+08:00"
        mutations.append(("payment_time", invalid))

        invalid = deepcopy(self.case)
        state = by_id(invalid["states"])["state-revision-02"]
        by_id(state["domain_entities"])["revision-revision-01"]["payload"]["recognized_through"] = "revision-installment-01"
        mutations.append(("revision_one_boundary", invalid))

        invalid = deepcopy(self.case)
        state = by_id(invalid["states"])["state-revision-03"]
        by_id(state["domain_entities"])["revision-revision-02"]["payload"]["recognized_through"] = "revision-installment-02"
        mutations.append(("latest_boundary", invalid))

        invalid = deepcopy(self.case)
        by_id(invalid["operations"])["revision-revise"]["input"]["remaining_installment_count"] = 2
        mutations.append(("revision_count", invalid))

        for name, invalid in mutations:
            with self.subTest(name=name):
                with self.assertRaises(GoldenCaseError):
                    validate_golden_case_v2(invalid)


if __name__ == "__main__":
    unittest.main()
