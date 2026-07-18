from copy import deepcopy
import json
from pathlib import Path
import unittest

from golden_cases import GoldenCaseError, validate_golden_case_v2


ROOT = Path(__file__).resolve().parents[2]
CASE_PATH = ROOT / "golden" / "rules" / "rg-12.json"
ENTITY_PATHS = {
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


def value_at(state, path):
    for key in path:
        state = state[key]
    return state


def payload(state):
    state = deepcopy(state)
    for key in ("id", "root_id", "as_of_operation_id"):
        state.pop(key)
    return state


def report_map(state):
    return {
        (report["period_type"], report["period"], metric["metric"], metric.get("currency")):
        {key: value for key, value in metric.items() if key != "metric"}
        for report in state["reports"]
        for metric in report["metrics"]
    }


def status_map(state):
    return {
        (item["target_kind"], item["target_id"], item["status_name"]): item["value"]
        for item in state["derived_statuses"]
    }


def refresh_operation_deltas(case, operation_id):
    operation = by_id(case["operations"])[operation_id]
    before = by_id(case["states"])[operation["baseline_state_id"]]
    after = by_id(case["states"])[operation["result_state_id"]]
    for name, path in ENTITY_PATHS.items():
        before_items = by_id(value_at(before, path))
        after_items = by_id(value_at(after, path))
        operation["deltas"]["entity_changes"][name] = {
            "added_ids": sorted(set(after_items) - set(before_items)),
            "changed_ids": sorted(item_id for item_id in set(before_items) & set(after_items) if before_items[item_id] != after_items[item_id]),
            "removed_ids": sorted(set(before_items) - set(after_items)),
        }
    before_balances = {(item["account_id"], item["currency"]): item["amount"] for item in before["balances"]}
    after_balances = {(item["account_id"], item["currency"]): item["amount"] for item in after["balances"]}
    operation["deltas"]["value_changes"]["balances"] = [
        {"key": {"account_id": key[0], "currency": key[1]}, "before": before_balances.get(key), "after": after_balances.get(key)}
        for key in sorted(set(before_balances) | set(after_balances))
        if before_balances.get(key) != after_balances.get(key)
    ]
    before_reports, after_reports = report_map(before), report_map(after)
    operation["deltas"]["value_changes"]["reports"] = [
        {"key": {"period_type": key[0], "period": key[1], "metric": key[2], **({"currency": key[3]} if key[3] else {})}, "before": before_reports.get(key), "after": after_reports.get(key)}
        for key in sorted(set(before_reports) | set(after_reports))
        if before_reports.get(key) != after_reports.get(key)
    ]
    before_statuses, after_statuses = status_map(before), status_map(after)
    operation["deltas"]["value_changes"]["derived_statuses"] = [
        {"key": {"kind": key[0], "target_id": key[1], "status_name": key[2]}, "before": before_statuses.get(key), "after": after_statuses.get(key)}
        for key in sorted(set(before_statuses) | set(after_statuses))
        if before_statuses.get(key) != after_statuses.get(key)
    ]


def changed_asset_case(case):
    value = deepcopy(case)
    states = by_id(value["states"])
    for operation in value["operations"]:
        if operation["action_type"] != "correct_transaction_version":
            continue
        inputs = operation.get("input", operation.get("attempted_input"))
        if inputs.get("correction_kind") != "posting_facts":
            continue
        for posting in inputs["replacement_postings"]:
            if posting["source_posting_id"] == "root-correction-expense-v1":
                posting["amount"] = "100.00"
            elif posting["source_posting_id"] == "root-correction-asset-v1":
                posting["amount"] = "-60.00"
    for state_id in ("state-correction-01", "state-correction-02"):
        state = states[state_id]
        postings = by_id(state["postings"])
        postings["root-correction-expense-v2"]["amount"] = "100.00"
        postings["root-correction-asset-v2"]["amount"] = "-60.00"
        balances = {item["account_id"]: item for item in state["balances"]}
        balances["root-correction-expense"]["amount"] = "100.00"
        balances["root-correction-asset"]["amount"] = "-60.00"
        matches = by_id(state["domain_entities"])
        invalidated = {
            "id": "root-correction-match-asset-v1-history-2",
            "sequence": 2,
            "status": "invalidated",
            "at": "2026-04-20T10:00:00+08:00",
            "reason": "posting_replaced",
        }
        matches["root-correction-match-asset-v1"]["payload"]["status_history"].append(deepcopy(invalidated))
        matches["root-correction-match-asset-v2"]["payload"]["status_history"].append({**invalidated, "id": "root-correction-match-asset-v2-history-2"})
        matches["root-correction-consumption-v2"]["payload"]["amount"] = "100.00"
        by_id(state["audit_links"])["root-correction-replacement-asset"]["payload"]["reconciliation_effect"] = "invalidated"
        by_id(state["posting_reconciliations"])["root-correction-reconciliation-asset-v2"]["status"] = "pending"
        by_id(state["derived_statuses"])["root-correction-summary"]["value"] = "pending"
        for report in state["reports"]:
            for metric in report["metrics"]:
                if report["period"] != "2026-04-10":
                    continue
                if metric["metric"] == "cash_outflow":
                    metric["amount"] = "60.00"
                elif metric["metric"] in {"consumption", "category_consumption"}:
                    metric["amount"] = "100.00"
                elif metric["metric"] == "net_worth_change":
                    metric["amount"] = "-100.00"
    refresh_operation_deltas(value, "root-correction-correct")
    value["operations"][0]["status_changes"] = [{
        "target_kind": "transaction",
        "target_id": "root-correction-transaction",
        "status_name": "reconciliation_summary",
        "before": "matched",
        "after": "pending",
    }]
    return value


class Rg12GoldenV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.case = json.loads(CASE_PATH.read_text(encoding="utf-8"))
        cls.states = by_id(cls.case["states"])
        cls.operations = by_id(cls.case["operations"])
        cls.roots = by_id(cls.case["roots"])

    def test_00_validate_golden_case_v2(self):
        validate_golden_case_v2(self.case)

    def test_complete_state_chains_and_exhaustive_deltas(self):
        self.assertEqual(self.case["case"]["level"], "core_required")
        self.assertEqual(self.case["case"]["approval_status"], "draft_for_review")
        self.assertEqual(set(self.roots), {"root-partial", "root-correction", "root-rejections"})
        for root in self.roots.values():
            previous = root["initial_state_id"]
            self.assertIsNone(self.states[previous]["as_of_operation_id"])
            for operation_id in root["operation_ids"]:
                operation = self.operations[operation_id]
                self.assertEqual(operation["baseline_state_id"], previous)
                result = self.states[operation["result_state_id"]]
                self.assertEqual(result["as_of_operation_id"], operation_id)
                self.assertEqual(result["root_id"], root["id"])
                previous = result["id"]
            self.assertEqual(
                len(root["operation_ids"]) + 1,
                len([state for state in self.states.values() if state["root_id"] == root["id"]]),
            )

        required = {"id", "root_id", "as_of_operation_id", "catalog", "transactions", "transaction_versions", "posting_sets", "postings", "sources", "candidates", "confirmations", "evidence", "evidence_links", "relations", "domain_entities", "audit_links", "posting_reconciliations", "balances", "reports", "derived_statuses"}
        for state in self.states.values():
            self.assertEqual(set(state), required)
            self.assertEqual(len(state["balances"]), len(state["catalog"]["accounts"]))
            for posting_set in state["posting_sets"]:
                postings = by_id(state["postings"])
                self.assertEqual(sum(int(postings[item]["amount"].replace(".", "")) for item in posting_set["posting_ids"]), 0)

        for operation in self.operations.values():
            baseline = self.states[operation["baseline_state_id"]]
            result = self.states[operation["result_state_id"]]
            for name, path in ENTITY_PATHS.items():
                before, after = by_id(value_at(baseline, path)), by_id(value_at(result, path))
                expected = {"added_ids": sorted(set(after) - set(before)), "changed_ids": sorted(key for key in set(before) & set(after) if before[key] != after[key]), "removed_ids": sorted(set(before) - set(after))}
                self.assertEqual(operation["deltas"]["entity_changes"][name], expected, operation["id"] + ":" + name)
            if operation["outcome"]["status"] in {"rejected", "no_change"}:
                self.assertEqual(payload(baseline), payload(result))
                self.assertTrue(all(not values for changes in operation["deltas"]["entity_changes"].values() for values in changes.values()))
                self.assertTrue(all(not values for values in operation["deltas"]["value_changes"].values()))

    def test_partial_and_corrected_lifecycle(self):
        partial = self.states["state-partial-00"]
        self.assertEqual(status_map(partial)[("transaction", "root-partial-transaction", "reconciliation_summary")], "partial")
        self.assertEqual(by_id(partial["posting_reconciliations"])["root-partial-reconciliation-asset-v1"]["status"], "matched")
        self.assertEqual(by_id(partial["posting_reconciliations"])["root-partial-reconciliation-liability-v1"]["status"], "pending")

        before, after = self.states["state-correction-00"], self.states["state-correction-01"]
        versions = by_id(after["transaction_versions"])
        self.assertEqual([versions[f"root-correction-transaction-v{i}"]["statistics_at"] for i in (1, 2)], ["2026-04-10T09:30:00+08:00"] * 2)
        self.assertEqual(versions["root-correction-transaction-v2"]["occurred_at"], versions["root-correction-transaction-v1"]["occurred_at"])
        self.assertEqual(status_map(after)[("transaction", "root-correction-transaction", "reconciliation_summary")], "partial")
        self.assertEqual(by_id(after["audit_links"])["root-correction-replacement-asset"]["payload"]["reconciliation_effect"], "preserved")
        self.assertEqual(by_id(after["audit_links"])["root-correction-replacement-liability"]["payload"]["reconciliation_effect"], "invalidated")
        history = by_id(after["domain_entities"])["root-correction-match-liability-v1"]["payload"]["status_history"]
        self.assertEqual([item["status"] for item in history], ["matched", "invalidated"])
        self.assertEqual(by_id(before["evidence"]), by_id(after["evidence"]))

        reports = report_map(after)
        self.assertEqual(reports[("day", "2026-04-10", "cash_outflow", "CNY")]["amount"], "70.00")
        for metric in ("consumption", "category_consumption"):
            self.assertEqual(reports[("day", "2026-04-10", metric, "CNY")]["amount"], "110.00")
        self.assertEqual(reports[("day", "2026-04-10", "net_worth_change", "CNY")]["amount"], "-110.00")
        for metric in ("cash_outflow", "consumption", "category_consumption"):
            self.assertEqual(reports[("day", "2026-04-20", metric, "CNY")]["amount"], "0.00")
        self.assertEqual(payload(after), payload(self.states["state-correction-02"]))

    def test_changed_matched_asset_is_symmetric_and_requires_lineage_mapping(self):
        changed = changed_asset_case(self.case)
        validate_golden_case_v2(changed)

        broken = deepcopy(changed)
        state = by_id(broken["states"])["state-correction-01"]
        by_id(state["audit_links"])["root-correction-replacement-asset"]["payload"]["reconciliation_effect"] = "preserved"
        with self.assertRaises(GoldenCaseError):
            validate_golden_case_v2(broken)

    def test_rejections_and_owner_boundaries(self):
        rejected = [operation for operation in self.operations.values() if operation["outcome"]["status"] == "rejected"]
        self.assertEqual(len(rejected), 10)
        self.assertEqual({operation["outcome"]["reason_code"] for operation in rejected}, {"complete_replacement_postings_required", "replacement_postings_must_balance", "duplicate_source_posting_id", "known_account_required", "owned_account_required", "account_currency_mismatch", "matched_unaffected_posting_must_be_preserved", "explicit_confirmation_required", "exact_decimal_string_required", "historical_facts_immutable"})
        for operation in rejected:
            self.assertEqual(operation["operation_class"], "rejection")
            self.assertNotIn("input", operation)
            self.assertTrue(operation["outcome"]["field_path"].startswith("$.attempted_input."))

        mutations = []
        invalid = deepcopy(self.case)
        by_id(invalid["states"])["state-correction-01"]["transaction_versions"][1]["note"] = "drift"
        mutations.append(invalid)
        invalid = deepcopy(self.case)
        by_id(invalid["states"])["state-correction-01"]["domain_entities"][2]["payload"]["status_history"][0]["reason"] = "drift"
        mutations.append(invalid)
        invalid = deepcopy(self.case)
        invalid["states"][2]["audit_links"] = invalid["states"][2]["audit_links"][0:1] + invalid["states"][2]["audit_links"][2:]
        mutations.append(invalid)
        invalid = deepcopy(self.case)
        by_id(invalid["states"])["state-correction-01"]["posting_reconciliations"][-2]["status"] = "pending"
        mutations.append(invalid)
        for operation_id, value in (("root-correction-correct", "109.00"), ("root-rejections-reject-3", "root-rejections-asset-v1")):
            invalid = deepcopy(self.case)
            operation = by_id(invalid["operations"])[operation_id]
            operation.get("input", operation.get("attempted_input"))["replacement_postings"][0]["amount"] = value
            mutations.append(invalid)
        for invalid in mutations:
            with self.assertRaises(GoldenCaseError):
                validate_golden_case_v2(invalid)


if __name__ == "__main__":
    unittest.main()
