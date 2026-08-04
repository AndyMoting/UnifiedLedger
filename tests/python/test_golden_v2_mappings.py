import json
import re
import unittest
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import golden_cases.v2 as golden_v2
from jsonschema import Draft202012Validator
from tests.python.test_golden_v2_rg06_semantics import (
    PRECISIONS as RG06_PRECISIONS,
    STATE_PATH as RG06_STATE_PATH,
    installment as rg06_installment,
    staged_payment_state,
    validate_relations as validate_rg06_relations,
)
from tests.python.test_golden_v2_rg06_operations import (
    creation_result as rg06_creation_result,
    deposit_result as rg06_deposit_result,
    final_result as rg06_final_result,
    lifecycle_entity as rg06_lifecycle_entity,
    payment_entity as rg06_payment_entity,
)


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
RG06_FIXTURE_PATH = ROOT / "golden" / "rules" / "rg-06.json"
RG06_CLOSURE_PROPOSAL_PATH = (
    ROOT / "docs" / "migrations" / "golden-v2" / "rg-06-closure-proposal.md"
)
RG06_CLOSURE_RULES_BEGIN = "<!-- rg06-closure-rules:begin -->"
RG06_CLOSURE_RULES_END = "<!-- rg06-closure-rules:end -->"
EXPECTED_INVENTORIES = {
    "RG-02": (154, 368),
    "RG-03": (304, 560),
    "RG-04": (406, 863),
    "RG-05": (549, 1407),
    "RG-06": (1188, 3610),
    "RG-07": (2523, 6084),
    "RG-08": (4969, 16797),
    "RG-09": (7445, 23869),
    "RG-10": (1161, 2022),
}
CLASSIFICATIONS = {"preserve", "map", "derive", "reject"}
DISPOSITIONS = {
    "ready",
    "requires_contract_amendment",
    "test_only_exclusion",
}
REJECTABLE_PREFIXES = ("$.forbidden_side_effects", "$.out_of_scope")
PLANNED_CONTRACT_PREFIX = "$.planned_contract."


def value_kind(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    raise AssertionError(f"unsupported JSON value: {type(value)!r}")


def normalized_leaves(value, path="$", result=None):
    if result is None:
        result = defaultdict(list)
    if isinstance(value, dict) and value:
        for key, child in value.items():
            normalized_leaves(child, f"{path}.{key}", result)
    elif isinstance(value, list) and value:
        for child in value:
            normalized_leaves(child, f"{path}[*]", result)
    else:
        result[path].append(value_kind(value))
    return result


def normalized_leaf_values(value, path="$", result=None):
    if result is None:
        result = defaultdict(list)
    if isinstance(value, dict) and value:
        for key, child in value.items():
            normalized_leaf_values(child, f"{path}.{key}", result)
    elif isinstance(value, list) and value:
        for child in value:
            normalized_leaf_values(child, f"{path}[*]", result)
    else:
        result[path].append(value)
    return result


def resolve_local_ref(document, ref):
    if not ref.startswith("#/"):
        raise AssertionError(f"schema reference must be local: {ref}")
    value = document
    for token in ref[2:].split("/"):
        value = value[token.replace("~1", "/").replace("~0", "~")]
    return value


def normalized_schema_paths(schema, document=None, path="$", seen=None):
    if document is None:
        document = schema
    if seen is None:
        seen = set()
    paths = {path}
    if not isinstance(schema, dict):
        return paths

    ref = schema.get("$ref")
    if ref is not None:
        marker = (ref, path)
        if marker not in seen:
            paths.update(
                normalized_schema_paths(
                    resolve_local_ref(document, ref),
                    document,
                    path,
                    seen | {marker},
                )
            )

    for keyword in ("allOf", "anyOf", "oneOf"):
        for branch in schema.get(keyword, []):
            paths.update(normalized_schema_paths(branch, document, path, seen))
    for keyword in ("if", "then", "else", "not"):
        if keyword in schema:
            paths.update(
                normalized_schema_paths(schema[keyword], document, path, seen)
            )

    for key, child in schema.get("properties", {}).items():
        child_path = f"{path}.{key}"
        paths.update(normalized_schema_paths(child, document, child_path, seen))
    if "items" in schema:
        paths.update(
            normalized_schema_paths(schema["items"], document, f"{path}[*]", seen)
        )
    return paths


def load_rg06_closure_rules(proposal_text=None):
    if proposal_text is None:
        proposal_text = RG06_CLOSURE_PROPOSAL_PATH.read_text(encoding="utf-8")
    if (
        proposal_text.count(RG06_CLOSURE_RULES_BEGIN) != 1
        or proposal_text.count(RG06_CLOSURE_RULES_END) != 1
    ):
        raise AssertionError("RG-06 closure rules block must have exactly one marker pair")
    try:
        fragment = proposal_text.split(RG06_CLOSURE_RULES_BEGIN, 1)[1].split(
            RG06_CLOSURE_RULES_END, 1
        )[0]
    except IndexError as error:
        raise AssertionError("RG-06 closure rules block is missing") from error
    match = re.fullmatch(r"\s*```json\s*(\{.*\})\s*```\s*", fragment, re.DOTALL)
    if match is None:
        raise AssertionError("RG-06 closure rules block must contain one JSON object")
    return json.loads(match.group(1))


def schema_validator_for(definition, schema):
    return Draft202012Validator(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$defs": schema["$defs"],
            "$ref": f"#/$defs/{definition}",
        }
    )


class GoldenV2MappingTests(unittest.TestCase):
    def test_rg06_closure_rules_require_single_marker_pair(self):
        proposal_text = RG06_CLOSURE_PROPOSAL_PATH.read_text(encoding="utf-8")
        duplicate_block = (
            f"\n{RG06_CLOSURE_RULES_BEGIN}\n```json\n"
            '{"artifact_version": 2}\n'
            f"```\n{RG06_CLOSURE_RULES_END}\n"
        )
        with self.assertRaisesRegex(AssertionError, "exactly one marker pair"):
            load_rg06_closure_rules(proposal_text + duplicate_block)

    def test_target_paths_are_individual_normalized_json_paths(self):
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            for entry in path_map["entries"]:
                for target_path in entry["target_paths"]:
                    with self.subTest(
                        case_id=case_id,
                        source_path=entry["source_path"],
                        target_path=target_path,
                    ):
                        self.assertTrue(target_path.startswith("$."))
                        self.assertEqual(target_path.count("$."), 1)
                        self.assertNotRegex(target_path, r"\s")

    def test_path_map_envelope_metadata_is_canonical(self):
        expected_normalization = {
            "root": "$",
            "object_member": ".key",
            "array_index": "[*]",
            "leaf_rule": "scalar, null, empty array, and empty object values are leaves",
            "aggregation": (
                "one entry per unique normalized path; source_value_kinds and "
                "occurrence_count aggregate all occurrences"
            ),
        }
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            with self.subTest(case_id=case_id):
                self.assertNotIn("source_file", path_map)
                self.assertEqual(path_map["artifact_type"], "golden_v2_path_map")
                self.assertEqual(path_map["artifact_version"], 1)
                self.assertEqual(path_map["case_id"], case_id)
                self.assertEqual(
                    path_map["source"], f"golden/rules/rg-{suffix}.json"
                )
                self.assertEqual(path_map["source_schema_version"], 1)
                self.assertEqual(path_map["target_contract_version"], "2.0.0")
                self.assertEqual(path_map["normalization"], expected_normalization)

    def test_rg02_through_rg10_path_maps_close_over_source_inventories(self):
        for case_id, (expected_paths, expected_occurrences) in EXPECTED_INVENTORIES.items():
            suffix = case_id[-2:]
            with self.subTest(case_id=case_id):
                source = json.loads(
                    (ROOT / "golden" / "rules" / f"rg-{suffix}.json").read_text(
                        encoding="utf-8"
                    )
                )
                path_map = json.loads(
                    (
                        ROOT
                        / "docs"
                        / "migrations"
                        / "golden-v2"
                        / f"rg-{suffix}-path-map.json"
                    ).read_text(encoding="utf-8")
                )
                inventory = normalized_leaves(source)
                entries = path_map["entries"]
                entries_by_path = {entry["source_path"]: entry for entry in entries}

                self.assertEqual(len(inventory), expected_paths)
                self.assertEqual(
                    sum(len(kinds) for kinds in inventory.values()),
                    expected_occurrences,
                )
                self.assertEqual(path_map["source_path_count"], len(inventory))
                self.assertEqual(
                    path_map["source_leaf_occurrence_count"],
                    sum(len(kinds) for kinds in inventory.values()),
                )
                self.assertEqual(path_map["classified_path_count"], len(entries))
                self.assertEqual(path_map["unclassified_path_count"], 0)
                self.assertEqual(len(entries_by_path), len(entries))
                self.assertEqual(set(entries_by_path), set(inventory))
                self.assertEqual(
                    [entry["source_path"] for entry in entries],
                    sorted(inventory),
                )

                for source_path, kinds in inventory.items():
                    entry = entries_by_path[source_path]
                    self.assertEqual(entry["source_value_kinds"], sorted(set(kinds)))
                    self.assertEqual(entry["occurrence_count"], len(kinds))
                    self.assertIn(entry["classification"], CLASSIFICATIONS)
                    self.assertIn(entry["disposition"], DISPOSITIONS)
                    for field in ("transform", "authority", "rationale"):
                        self.assertIsInstance(entry[field], str)
                        self.assertTrue(entry[field].strip(), (source_path, field))
                    self.assertIsInstance(entry["target_paths"], list)
                    if entry["classification"] == "reject":
                        self.assertEqual(entry["target_paths"], [])
                        self.assertTrue(source_path.startswith(REJECTABLE_PREFIXES))
                        self.assertEqual(
                            entry["disposition"], "test_only_exclusion"
                        )
                    else:
                        self.assertTrue(entry["target_paths"], source_path)

                classification_counts = Counter(
                    entry["classification"] for entry in entries
                )
                disposition_counts = Counter(entry["disposition"] for entry in entries)
                self.assertEqual(
                    path_map["classification_counts"], dict(classification_counts)
                )
                self.assertEqual(
                    path_map["disposition_counts"], dict(disposition_counts)
                )

    def test_rg04_mapping_closes_all_implemented_contract_gaps(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-04-path-map.json"
            ).read_text(encoding="utf-8")
        )

        self.assertEqual(path_map["status"], "approved")
        self.assertEqual(path_map["expected_output_gate"], "closed")
        self.assertEqual(path_map["contract_gap_count"], 0)
        self.assertEqual(path_map["unresolved_contract_gaps"], [])
        self.assertEqual(path_map["resolved_contract_gap_count"], 5)

        resolved_gaps = path_map["resolved_contract_gaps"]
        self.assertEqual(len(resolved_gaps), 5)
        self.assertEqual(
            {gap["id"] for gap in resolved_gaps},
            {f"RG04-GAP-{number:02d}" for number in range(1, 6)},
        )
        self.assertTrue(
            all(gap["status"] == "approved_implemented" for gap in resolved_gaps)
        )

        self.assertEqual(
            path_map["disposition_counts"],
            {"ready": 401, "test_only_exclusion": 5},
        )
        entries = path_map["entries"]
        self.assertEqual(
            Counter(entry["disposition"] for entry in entries),
            Counter({"ready": 401, "test_only_exclusion": 5}),
        )
        self.assertNotIn(
            "requires_contract_amendment",
            {entry["disposition"] for entry in entries},
        )
        self.assertTrue(all(entry["contract_gap_ids"] == [] for entry in entries))

    def test_rg04_category_ids_map_to_confirmation_and_posting_owners(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-04-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}

        confirmation_category = entries[
            "$.import_lifecycle.ordered_operations[*].input.category_id"
        ]
        self.assertEqual(
            confirmation_category["target_paths"],
            ["$.operations[*].input.category_id"],
        )

        for lifecycle in ("import_lifecycle", "manual_lifecycle"):
            source_path = (
                f"$.{lifecycle}.ordered_operations[*].expected.transaction."
                "postings[*].category_id"
            )
            with self.subTest(source_path=source_path):
                self.assertIn(
                    "$.states[*].postings[*].category_id",
                    entries[source_path]["target_paths"],
                )

    def test_rg04_mirror_input_maps_to_closed_source_and_evidence_owners(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-04-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        prefix = "$.import_lifecycle.ordered_operations[*].input."
        expected_targets = {
            "source_record_id": {
                "$.operations[*].input.source_record_id",
                "$.states[*].sources[*].id",
                "$.states[*].evidence[*].source_ids[*]",
            },
            "account_id": {
                "$.operations[*].input.account_id",
                "$.states[*].sources[*].payload.account_id",
            },
            "amount": {
                "$.operations[*].input.amount",
                "$.states[*].sources[*].payload.amount",
            },
            "currency": {
                "$.operations[*].input.currency",
                "$.states[*].sources[*].payload.currency",
            },
            "evidence_id": {
                "$.operations[*].input.evidence_id",
                "$.states[*].sources[*].payload.evidence_id",
                "$.states[*].evidence[*].id",
                "$.states[*].evidence[*].type",
            },
            "observed_at": {
                "$.operations[*].input.observed_at",
                "$.states[*].sources[*].payload.observed_at",
                "$.states[*].evidence[*].payload.observed_at",
            },
        }
        for field, required_targets in expected_targets.items():
            source_path = f"{prefix}{field}"
            with self.subTest(source_path=source_path):
                self.assertTrue(
                    required_targets.issubset(entries[source_path]["target_paths"]),
                    sorted(required_targets - set(entries[source_path]["target_paths"])),
                )

    def test_rg04_mirror_identities_have_exact_typed_returned_owners(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-04-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        returned_targets = {
            "$.operations[*].returned_ids[*].kind",
            "$.operations[*].returned_ids[*].id",
        }

        merged_transaction = entries[
            "$.import_lifecycle.ordered_operations[*].expected."
            "merged_into_transaction_id"
        ]
        self.assertEqual(
            set(merged_transaction["target_paths"]),
            {
                "$.states[*].transactions[*].id",
                "$.operations[*].input.transaction_id",
            },
        )
        self.assertTrue(
            returned_targets.isdisjoint(merged_transaction["target_paths"])
        )

        typed_identities = {
            "$.import_lifecycle.ordered_operations[*].input.source_record_id": (
                "source",
                "$.states[*].sources[*].id",
            ),
            "$.import_lifecycle.ordered_operations[*].input.evidence_id": (
                "evidence",
                "$.states[*].evidence[*].id",
            ),
            "$.import_lifecycle.ordered_operations[*].expected."
            "evidence_links[*].id": (
                "evidence_link",
                "$.states[*].evidence_links[*].id",
            ),
        }
        for source_path, (kind, state_owner) in typed_identities.items():
            entry = entries[source_path]
            targets = set(entry["target_paths"])
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=source_path):
                self.assertIn(state_owner, targets)
                self.assertTrue(returned_targets.issubset(targets))
                self.assertIn(f"kind={kind}", semantic_text)

    def test_rg04_resolved_gap_paths_are_frozen_and_bidirectionally_closed(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-04-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = path_map["entries"]
        entries_by_path = {entry["source_path"]: entry for entry in entries}
        gaps = path_map["resolved_contract_gaps"]
        gap_ids = [gap["id"] for gap in gaps]
        expected_counts = {
            "RG04-GAP-01": 84,
            "RG04-GAP-02": 48,
            "RG04-GAP-03": 13,
            "RG04-GAP-04": 48,
            "RG04-GAP-05": 13,
        }

        self.assertEqual(len(gap_ids), len(set(gap_ids)))
        self.assertEqual(set(gap_ids), set(expected_counts))
        self.assertEqual(path_map["source_path_count"], 406)
        self.assertEqual(path_map["source_leaf_occurrence_count"], 863)
        self.assertEqual(
            path_map["classification_counts"],
            {"preserve": 112, "map": 130, "derive": 159, "reject": 5},
        )
        self.assertEqual(
            path_map["disposition_counts"],
            {"ready": 401, "test_only_exclusion": 5},
        )
        self.assertFalse(
            any(
                target.startswith(PLANNED_CONTRACT_PREFIX)
                for entry in entries
                for target in entry["target_paths"]
            )
        )

        known_gap_ids = set(gap_ids)
        entry_paths_by_gap = defaultdict(set)
        for entry in entries:
            resolved_ids = entry.get("resolved_contract_gap_ids", [])
            self.assertEqual(len(resolved_ids), len(set(resolved_ids)))
            self.assertTrue(set(resolved_ids).issubset(known_gap_ids))
            for gap_id in resolved_ids:
                entry_paths_by_gap[gap_id].add(entry["source_path"])

        affected_union = set()
        for gap in gaps:
            affected_paths = gap["affected_source_paths"]
            with self.subTest(gap_id=gap["id"]):
                self.assertEqual(len(affected_paths), expected_counts[gap["id"]])
                self.assertEqual(len(affected_paths), len(set(affected_paths)))
                self.assertTrue(set(affected_paths).issubset(entries_by_path))
                self.assertEqual(set(affected_paths), entry_paths_by_gap[gap["id"]])
                if gap["id"] in {"RG04-GAP-01", "RG04-GAP-02"}:
                    self.assertEqual(affected_paths, sorted(affected_paths))
            affected_union.update(affected_paths)
        self.assertEqual(len(affected_union), 181)

    def test_ready_targets_exist_in_current_v2_schema(self):
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        schema_paths = normalized_schema_paths(schema)
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            for entry in path_map["entries"]:
                with self.subTest(case_id=case_id, source_path=entry["source_path"]):
                    for target_path in entry["target_paths"]:
                        if target_path in schema_paths:
                            continue
                        if (
                            target_path.startswith(PLANNED_CONTRACT_PREFIX)
                            or entry["disposition"] == "requires_contract_amendment"
                        ):
                            self.assertEqual(
                                entry["disposition"], "requires_contract_amendment"
                            )
                            self.assertTrue(entry["contract_gap_ids"])
                        else:
                            self.fail(f"target path is absent from v2 schema: {target_path}")

    def test_source_schema_version_maps_to_v2_envelope(self):
        required_targets = {"$.contract", "$.contract_version"}
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            entries = {entry["source_path"]: entry for entry in path_map["entries"]}
            with self.subTest(case_id=case_id):
                self.assertIn("$.schema_version", entries)
                self.assertTrue(
                    required_targets.issubset(
                        set(entries["$.schema_version"]["target_paths"])
                    )
                )

    def test_category_rename_does_not_use_closed_derived_statuses(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-02-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        category_id = entries["$.category_rename.expected.category_id"]
        self.assertIn("$.states[*].catalog.categories[*].id", category_id["target_paths"])
        self.assertNotIn(
            "$.states[*].catalog.categories[*].category_id",
            category_id["target_paths"],
        )
        for source_path in (
            "$.category_rename.expected.display_path",
            "$.category_rename.expected.transaction_category_id",
        ):
            with self.subTest(source_path=source_path):
                self.assertFalse(
                    any(
                        target.startswith("$.states[*].derived_statuses")
                        for target in entries[source_path]["target_paths"]
                    )
                )

    def test_not_applicable_reconciliation_uses_canonical_absence(self):
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            source = json.loads(
                (ROOT / "golden" / "rules" / f"rg-{suffix}.json").read_text(
                    encoding="utf-8"
                )
            )
            values = normalized_leaf_values(source)
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            entries = {entry["source_path"]: entry for entry in path_map["entries"]}
            for source_path, occurrences in values.items():
                if (
                    ".reconciliation." not in source_path
                    or "not_applicable" not in occurrences
                ):
                    continue
                entry = entries[source_path]
                with self.subTest(case_id=case_id, source_path=source_path):
                    self.assertNotIn(
                        "$.states[*].posting_reconciliations[*].status",
                        entry["target_paths"],
                    )
                    self.assertIn(
                        "$.states[*].postings[*].reconciliation_eligible",
                        entry["target_paths"],
                    )

    def test_posting_evidence_link_mappings_cover_typed_target_and_role(self):
        required_targets = {
            "$.states[*].evidence_links[*].target_kind",
            "$.states[*].evidence_links[*].target_id",
            "$.states[*].evidence_links[*].role",
        }
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            path_map = json.loads(
                (
                    ROOT
                    / "docs"
                    / "migrations"
                    / "golden-v2"
                    / f"rg-{suffix}-path-map.json"
                ).read_text(encoding="utf-8")
            )
            for entry in path_map["entries"]:
                source_path = entry["source_path"]
                target_paths = set(entry["target_paths"])
                if not source_path.endswith(".evidence_links[*].posting_id"):
                    continue
                if not any(
                    target.startswith("$.states[*].evidence_links[*].")
                    for target in target_paths
                ):
                    continue
                with self.subTest(case_id=case_id, source_path=source_path):
                    self.assertTrue(
                        required_targets.issubset(target_paths),
                        f"missing typed evidence-link targets: "
                        f"{sorted(required_targets - target_paths)}",
                    )

    def test_rg05_current_allocation_contract_and_item_evidence_targets(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-05-path-map.json"
            ).read_text(encoding="utf-8")
        )
        consumption_fields = {
            "id",
            "expense_posting_id",
            "category_id",
            "amount",
            "currency",
            "statistics_at",
        }
        allocation_fields = {
            "id",
            "consumption_record_id",
            "expense_posting_id",
            "category_id",
            "amount",
            "currency",
        }
        current_entries = []
        item_evidence_entries = []
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            field = source_path.rsplit(".", 1)[-1]
            if (
                ".consumption_records[*]." in source_path
                and field in consumption_fields
            ) or (
                ".item_allocations[*]." in source_path
                and field in allocation_fields
            ):
                current_entries.append(entry)
            if source_path.endswith(
                ".item_evidence_links[*].item_allocation_id"
            ):
                item_evidence_entries.append(entry)

        self.assertTrue(current_entries)
        for entry in current_entries:
            field = entry["source_path"].rsplit(".", 1)[-1]
            target_paths = set(entry["target_paths"])
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                if field == "id":
                    self.assertIn(
                        "$.states[*].domain_entities[*].id", target_paths
                    )
                    self.assertTrue(
                        target_paths.issubset(
                            {
                                "$.states[*].domain_entities[*].id",
                                "$.states[*].domain_entities[*].type",
                            }
                        )
                    )
                else:
                    self.assertEqual(
                        target_paths,
                        {f"$.states[*].domain_entities[*].payload.{field}"},
                    )
                self.assertFalse(
                    any(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        for target in entry["target_paths"]
                    )
                )

        required_evidence_targets = {
            "$.states[*].evidence_links[*].target_kind",
            "$.states[*].evidence_links[*].target_id",
            "$.states[*].evidence_links[*].role",
        }
        self.assertTrue(item_evidence_entries)
        for entry in item_evidence_entries:
            target_paths = set(entry["target_paths"])
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=entry["source_path"]):
                self.assertTrue(required_evidence_targets.issubset(target_paths))
                self.assertIn("target_kind=domain_entity", semantic_text)
                self.assertIn("role=item_allocation_fact", semantic_text)
                self.assertFalse(
                    any("consumption_record" in target for target in target_paths)
                )

    def test_rg06_staged_payment_relation_and_manual_source_absence(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-06.json").read_text(
                encoding="utf-8"
            )
        )
        values = normalized_leaf_values(source)
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}

        group_identity_entries = []
        history_entries = []
        top_level_business_entries = []
        manual_absence_entries = []
        for source_path, occurrences in values.items():
            entry = entries[source_path]
            if source_path.endswith((".group.id", ".group.type")):
                group_identity_entries.append(entry)
            if ".group.state_history[*]." in source_path:
                history_entries.append(entry)
            elif ".group." in source_path:
                group_tail = source_path.split(".group.", 1)[1]
                if "." not in group_tail and group_tail not in {"id", "type"}:
                    top_level_business_entries.append(entry)
            if (
                "manual" in source_path
                and source_path.endswith((".source_payment_at", ".source_refs"))
                and all(value is None or value == [] for value in occurrences)
            ):
                manual_absence_entries.append(entry)

        self.assertTrue(group_identity_entries)
        for entry in group_identity_entries:
            expected_field = entry["source_path"].rsplit(".", 1)[-1]
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(len(entry["target_paths"]), 1)
                self.assertTrue(
                    entry["target_paths"][0].endswith(
                        f".relations[*].{expected_field}"
                    )
                )

        for entry in path_map["entries"]:
            with self.subTest(source_path=entry["source_path"]):
                self.assertFalse(
                    any(
                        ".relations[*].payload" in target
                        for target in entry["target_paths"]
                    )
                )

        self.assertTrue(history_entries)
        for entry in history_entries:
            field = entry["source_path"].rsplit(".", 1)[-1]
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(
                    entry["target_paths"],
                    [
                        "$.states[*].domain_entities[*].payload."
                        f"state_history[*].{field}"
                    ],
                )

        self.assertTrue(top_level_business_entries)
        for entry in top_level_business_entries:
            field = entry["source_path"].split(".group.", 1)[1]
            with self.subTest(source_path=entry["source_path"]):
                if field in {"payment_progress", "fulfillment_status"}:
                    self.assertEqual(
                        set(entry["target_paths"]),
                        {
                            "$.states[*].derived_statuses[*].target_kind",
                            "$.states[*].derived_statuses[*].target_id",
                            "$.states[*].derived_statuses[*].status_name",
                            "$.states[*].derived_statuses[*].value",
                        },
                    )
                elif field in {"payment_ids[*]", "payments"}:
                    self.assertEqual(
                        set(entry["target_paths"]),
                        {
                            "$.states[*].domain_entities[*].id",
                            "$.states[*].relations[*].member_refs[*].kind",
                            "$.states[*].relations[*].member_refs[*].id",
                        },
                    )
                else:
                    self.assertIn(
                        "$.states[*].domain_entities[*].payload."
                        f"{field}",
                        entry["target_paths"],
                    )

        forbidden_provenance_collections = (
            ".sources[*]",
            ".evidence[*]",
            ".evidence_links[*]",
        )
        self.assertTrue(manual_absence_entries)
        for entry in manual_absence_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertNotIn("RG06-GAP-02", entry["contract_gap_ids"])
                self.assertFalse(
                    any(
                        collection in target
                        for target in entry["target_paths"]
                        for collection in forbidden_provenance_collections
                    )
                )
                semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
                self.assertTrue(
                    "canonical absence" in semantic_text
                    or "omission" in semantic_text
                )

    def test_rg06_contract_closure_freezes_topology_status_and_evidence_ownership(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-06.json").read_text(
                encoding="utf-8"
            )
        )
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )
        contract_text = (ROOT / "docs" / "GOLDEN_SCHEMA.md").read_text(
            encoding="utf-8"
        )
        mapping_text = (
            ROOT / "docs" / "migrations" / "golden-v2" / "rg-06-mapping.md"
        ).read_text(encoding="utf-8")

        groups = []
        group_reconciliations = set()

        def collect_rg06_state(value):
            if isinstance(value, dict):
                if value.get("type") == "staged_payment":
                    groups.append(value)
                reconciliation = value.get("reconciliation")
                if isinstance(reconciliation, dict) and "group" in reconciliation:
                    group_reconciliations.add(reconciliation["group"])
                for child in value.values():
                    collect_rg06_state(child)
            elif isinstance(value, list):
                for child in value:
                    collect_rg06_state(child)

        collect_rg06_state(source)
        self.assertTrue(groups)
        self.assertEqual(
            {
                group["payment_progress"]
                for group in groups
            }
            | {
                history["payment_progress"]
                for group in groups
                for history in group["state_history"]
            },
            {"unpaid", "partially_paid", "paid_in_full"},
        )
        self.assertEqual(
            {
                group["fulfillment_status"]
                for group in groups
            }
            | {
                history["fulfillment_status"]
                for group in groups
                for history in group["state_history"]
            },
            {"in_progress", "fulfilled"},
        )
        self.assertEqual(group_reconciliations, {"pending", "partial", "complete"})

        def assert_valid_payment_roles(roles):
            self.assertEqual(len(roles), len(set(roles)))
            self.assertLessEqual(roles.count("deposit"), 1)
            self.assertLessEqual(roles.count("final"), 1)
            self.assertTrue(set(roles).issubset({"deposit", "final"}))
            if "final" in roles:
                self.assertIn("deposit", roles)

        observed_role_sequences = set()
        for group in groups:
            roles = [payment["role"] for payment in group["payments"]]
            observed_role_sequences.add(frozenset(roles))
            with self.subTest(group_id=group["id"], roles=roles):
                assert_valid_payment_roles(roles)
        self.assertEqual(
            observed_role_sequences,
            {
                frozenset(),
                frozenset({"deposit"}),
                frozenset({"deposit", "final"}),
            },
        )
        for contradictory_roles in (
            ["deposit", "deposit"],
            ["final"],
            ["deposit", "final", "final"],
            ["deposit", "unknown"],
        ):
            with self.subTest(contradictory_roles=contradictory_roles):
                with self.assertRaises(AssertionError):
                    assert_valid_payment_roles(contradictory_roles)

        def expected_payment_progress(paid_amount, due_amount):
            if paid_amount == Decimal("0.00"):
                return "unpaid"
            if due_amount == Decimal("0.00"):
                return "paid_in_full"
            return "partially_paid"

        def assert_valid_lifecycle(group, ordered_operations=None):
            payments = {payment["id"]: payment for payment in group["payments"]}
            self.assertEqual(len(payments), len(group["payments"]))
            total_amount = Decimal(group["total_amount"])
            paid_amount = Decimal(group["paid_amount"])
            due_amount = Decimal(group["due_amount"])
            self.assertEqual(total_amount, paid_amount + due_amount)
            self.assertEqual(
                paid_amount,
                sum(
                    (Decimal(payment["amount"]) for payment in payments.values()),
                    Decimal("0.00"),
                ),
            )
            self.assertEqual(
                group["payment_progress"],
                expected_payment_progress(paid_amount, due_amount),
            )

            history = group["state_history"]
            self.assertTrue(history)
            seen_payment_ids = set()
            cumulative_paid = Decimal("0.00")
            previous_occurred_at = None
            payment_history_positions = {}
            for sequence, item in enumerate(history, start=1):
                item_total = Decimal(item["total_amount"])
                item_paid = Decimal(item["paid_amount"])
                item_due = Decimal(item["due_amount"])
                self.assertEqual(item_total, item_paid + item_due)
                self.assertEqual(item_total, total_amount)
                self.assertEqual(
                    item["payment_progress"],
                    expected_payment_progress(item_paid, item_due),
                )

                occurred_at = datetime.fromisoformat(item["occurred_at"])
                if previous_occurred_at is not None:
                    self.assertGreater(occurred_at, previous_occurred_at)
                previous_occurred_at = occurred_at

                payment_id = item["payment_id"]
                if item["event"] == "payment_confirmed":
                    self.assertIn(payment_id, payments)
                    self.assertNotIn(payment_id, seen_payment_ids)
                    seen_payment_ids.add(payment_id)
                    cumulative_paid += Decimal(payments[payment_id]["amount"])
                    payment_history_positions[payments[payment_id]["role"]] = sequence
                else:
                    self.assertIsNone(payment_id)
                self.assertEqual(item_paid, cumulative_paid)

            self.assertEqual(seen_payment_ids, set(payments))
            latest = history[-1]
            for field in (
                "total_amount",
                "paid_amount",
                "due_amount",
                "payment_progress",
                "fulfillment_status",
            ):
                self.assertEqual(group[field], latest[field])

            payments_by_role = {
                payment["role"]: payment for payment in payments.values()
            }
            if "final" in payments_by_role:
                self.assertIn("deposit", payments_by_role)
                deposit = payments_by_role["deposit"]
                final = payments_by_role["final"]
                self.assertGreater(
                    datetime.fromisoformat(final["actual_payment_at"]),
                    datetime.fromisoformat(deposit["actual_payment_at"]),
                )
                if (
                    deposit.get("source_payment_at") is not None
                    and final.get("source_payment_at") is not None
                ):
                    self.assertGreater(
                        datetime.fromisoformat(final["source_payment_at"]),
                        datetime.fromisoformat(deposit["source_payment_at"]),
                    )
                self.assertLess(
                    payment_history_positions["deposit"],
                    payment_history_positions["final"],
                )

                if ordered_operations is not None:
                    payment_operation_positions = {}
                    for position, operation in enumerate(ordered_operations):
                        payment = operation.get("expected", {}).get("payment")
                        if payment is not None:
                            payment_operation_positions[payment["role"]] = position
                    self.assertLess(
                        payment_operation_positions["deposit"],
                        payment_operation_positions["final"],
                    )

        for group in groups:
            with self.subTest(lifecycle_group=group["id"]):
                assert_valid_lifecycle(group)

        manual_group = source["manual_path"]["canonical_final_state"]["group"]
        manual_operations = source["manual_path"]["ordered_operations"]
        assert_valid_lifecycle(manual_group, manual_operations)

        reversed_members = deepcopy(manual_group)
        reversed_members["payments"].reverse()
        assert_valid_lifecycle(reversed_members, manual_operations)

        contradictory_lifecycles = []
        contradictory_arithmetic = deepcopy(manual_group)
        contradictory_arithmetic["paid_amount"] = "299.99"
        contradictory_lifecycles.append(contradictory_arithmetic)
        contradictory_history = deepcopy(manual_group)
        contradictory_history["state_history"][-1]["due_amount"] = "0.01"
        contradictory_lifecycles.append(contradictory_history)
        contradictory_progress = deepcopy(manual_group)
        contradictory_progress["payment_progress"] = "partially_paid"
        contradictory_lifecycles.append(contradictory_progress)
        equal_time_final = deepcopy(manual_group)
        equal_time_payments = {
            payment["role"]: payment for payment in equal_time_final["payments"]
        }
        equal_time_payments["final"]["actual_payment_at"] = (
            equal_time_payments["deposit"]["actual_payment_at"]
        )
        contradictory_lifecycles.append(equal_time_final)
        earlier_final = deepcopy(manual_group)
        earlier_final_payments = {
            payment["role"]: payment for payment in earlier_final["payments"]
        }
        earlier_final_payments["final"]["actual_payment_at"] = (
            "2026-04-28T09:59:59+08:00"
        )
        contradictory_lifecycles.append(earlier_final)
        contradictory_history_order = deepcopy(manual_group)
        contradictory_history_order["state_history"][1], contradictory_history_order[
            "state_history"
        ][3] = (
            contradictory_history_order["state_history"][3],
            contradictory_history_order["state_history"][1],
        )
        contradictory_lifecycles.append(contradictory_history_order)
        for contradictory_lifecycle in contradictory_lifecycles:
            with self.subTest(
                contradictory_lifecycle=contradictory_lifecycle["id"]
            ):
                with self.assertRaises(AssertionError):
                    assert_valid_lifecycle(contradictory_lifecycle)

        contradictory_operation_order = deepcopy(manual_operations)
        deposit_operation_index = next(
            index
            for index, operation in enumerate(contradictory_operation_order)
            if operation.get("expected", {}).get("payment", {}).get("role")
            == "deposit"
        )
        final_operation_index = next(
            index
            for index, operation in enumerate(contradictory_operation_order)
            if operation.get("expected", {}).get("payment", {}).get("role")
            == "final"
        )
        contradictory_operation_order[
            deposit_operation_index
        ], contradictory_operation_order[final_operation_index] = (
            contradictory_operation_order[final_operation_index],
            contradictory_operation_order[deposit_operation_index],
        )
        with self.assertRaises(AssertionError):
            assert_valid_lifecycle(manual_group, contradictory_operation_order)

        categories = {
            category["id"]: category for category in source["catalog"]["categories"]
        }
        for state_name, state in (
            ("manual", source["manual_path"]["canonical_final_state"]),
            ("import", source["import_path"]["canonical_final_state"]),
        ):
            group = state["group"]
            transactions = {
                transaction["id"]: transaction
                for transaction in state["transactions"]
            }
            category_account_id = categories[group["category_id"]]["posting_account_id"]
            for payment in group["payments"]:
                transaction = transactions[payment["transaction_id"]]
                postings = {
                    posting["id"]: posting for posting in transaction["postings"]
                }
                expense_posting = postings[payment["expense_posting_id"]]
                asset_posting = postings[payment["asset_posting_id"]]
                amount = Decimal(payment["amount"])
                with self.subTest(state=state_name, payment_id=payment["id"]):
                    self.assertNotEqual(expense_posting["id"], asset_posting["id"])
                    self.assertEqual(expense_posting["account_id"], category_account_id)
                    self.assertEqual(asset_posting["account_id"], payment["funding_account_id"])
                    self.assertEqual(Decimal(expense_posting["amount"]), amount)
                    self.assertEqual(Decimal(asset_posting["amount"]), -amount)
                    self.assertEqual(expense_posting["currency"], payment["currency"])
                    self.assertEqual(asset_posting["currency"], payment["currency"])
                    self.assertEqual(transaction["occurred_at"], payment["actual_payment_at"])
                    self.assertEqual(transaction["statistics_at"], payment["statistics_at"])

        final_sources = {
            item["id"]: item
            for item in source["import_path"]["canonical_final_state"][
                "source_records"
            ]
        }
        manual_sources = source["manual_path"]["canonical_final_state"][
            "source_records"
        ]

        def assert_evidence_bindings_match_installments(state):
            sources = {item["id"]: item for item in state["source_records"]}
            payments = {item["id"]: item for item in state["payments"]}
            postings = {item["id"]: item for item in state["postings"]}
            for link in state["evidence_links"]:
                source_fact = sources[link["source_id"]]
                payment = payments[link["payment_id"]]
                asset_posting = postings[link["posting_id"]]
                self.assertEqual(source_fact["evidence_id"], link["evidence_id"])
                self.assertEqual(link["posting_id"], payment["asset_posting_id"])
                self.assertEqual(asset_posting["amount"], f"-{payment['amount']}")
                self.assertEqual(source_fact["currency"], payment["currency"])
                self.assertEqual(source_fact["currency"], asset_posting["currency"])
                self.assertEqual(
                    abs(Decimal(source_fact["amount"])),
                    Decimal(payment["amount"]),
                )
                self.assertEqual(
                    abs(Decimal(source_fact["amount"])),
                    abs(Decimal(asset_posting["amount"])),
                )

        manual_final_state = source["manual_path"]["canonical_final_state"]
        import_final_state = source["import_path"]["canonical_final_state"]
        assert_evidence_bindings_match_installments(manual_final_state)
        assert_evidence_bindings_match_installments(import_final_state)
        for contradictory_source_fields in (
            {"amount": "219.99"},
            {"currency": "USD"},
        ):
            contradictory_state = deepcopy(import_final_state)
            contradictory_source = next(
                item
                for item in contradictory_state["source_records"]
                if item["id"] == "source-rg06-import-final"
            )
            contradictory_source.update(contradictory_source_fields)
            with self.subTest(
                contradictory_source_fields=contradictory_source_fields
            ):
                with self.assertRaises(AssertionError):
                    assert_evidence_bindings_match_installments(contradictory_state)

        def assert_valid_bank_fact_time(fact, expected_variant):
            time_fields = {"observed_at", "source_payment_at"}.intersection(fact)
            self.assertEqual(time_fields, {expected_variant})
            self.assertIsInstance(fact[expected_variant], str)
            self.assertTrue(fact[expected_variant])
            self.assertNotIn("created_at", fact)
            self.assertNotIn("confirmed_at", fact)

        for manual_source in manual_sources:
            with self.subTest(manual_source=manual_source["id"]):
                assert_valid_bank_fact_time(manual_source, "observed_at")
        for imported_source in final_sources.values():
            with self.subTest(imported_source=imported_source["id"]):
                assert_valid_bank_fact_time(imported_source, "source_payment_at")

        for contradictory_fact, expected_variant in (
            (
                {
                    **manual_sources[0],
                    "source_payment_at": manual_sources[0]["observed_at"],
                },
                "observed_at",
            ),
            (
                {
                    key: value
                    for key, value in manual_sources[0].items()
                    if key != "observed_at"
                },
                "observed_at",
            ),
            ({**manual_sources[0], "observed_at": None}, "observed_at"),
            (
                {
                    **next(iter(final_sources.values())),
                    "observed_at": next(iter(final_sources.values()))[
                        "source_payment_at"
                    ],
                },
                "source_payment_at",
            ),
            (
                {
                    key: value
                    for key, value in next(iter(final_sources.values())).items()
                    if key != "source_payment_at"
                },
                "source_payment_at",
            ),
            (
                {
                    **next(iter(final_sources.values())),
                    "source_payment_at": None,
                },
                "source_payment_at",
            ),
        ):
            with self.subTest(
                contradictory_fact=contradictory_fact,
                expected_variant=expected_variant,
            ):
                with self.assertRaises(AssertionError):
                    assert_valid_bank_fact_time(
                        contradictory_fact, expected_variant
                    )

        def assert_evidence_time_matches_source(source_fact, evidence_payload):
            source_time_fields = {
                "observed_at",
                "source_payment_at",
            }.intersection(source_fact)
            evidence_time_fields = {
                "observed_at",
                "source_payment_at",
            }.intersection(evidence_payload)
            self.assertEqual(len(source_time_fields), 1)
            self.assertEqual(evidence_time_fields, source_time_fields)
            time_field = next(iter(source_time_fields))
            self.assertEqual(evidence_payload[time_field], source_fact[time_field])
            self.assertNotIn("created_at", evidence_payload)
            self.assertNotIn("confirmed_at", evidence_payload)

        manual_time = manual_sources[0]["observed_at"]
        imported_source = next(iter(final_sources.values()))
        imported_time = imported_source["source_payment_at"]
        assert_evidence_time_matches_source(
            manual_sources[0], {"observed_at": manual_time}
        )
        assert_evidence_time_matches_source(
            imported_source, {"source_payment_at": imported_time}
        )
        for source_fact, contradictory_evidence in (
            (manual_sources[0], {"source_payment_at": manual_time}),
            (manual_sources[0], {}),
            (manual_sources[0], {"observed_at": None}),
            (manual_sources[0], {"observed_at": manual_time, "created_at": manual_time}),
            (manual_sources[0], {"observed_at": manual_time, "confirmed_at": manual_time}),
            (manual_sources[0], {"observed_at": "2026-04-28T10:00:01+08:00"}),
            (imported_source, {"observed_at": imported_time}),
            (imported_source, {}),
            (imported_source, {"source_payment_at": None}),
            (
                imported_source,
                {"source_payment_at": imported_time, "created_at": imported_time},
            ),
            (
                imported_source,
                {"source_payment_at": imported_time, "confirmed_at": imported_time},
            ),
            (
                imported_source,
                {"source_payment_at": "2026-04-28T10:00:01+08:00"},
            ),
            (
                imported_source,
                {"source_payment_at": imported_time, "observed_at": imported_time},
            ),
        ):
            with self.subTest(
                source_fact=source_fact["id"],
                contradictory_evidence=contradictory_evidence,
            ):
                with self.assertRaises(AssertionError):
                    assert_evidence_time_matches_source(
                        source_fact, contradictory_evidence
                    )

        source_time_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"].endswith(
                (
                    ".source_records[*].observed_at",
                    ".source_records[*].source_payment_at",
                )
            )
        ]
        self.assertTrue(source_time_entries)
        observed_entries = []
        imported_entries = []
        for entry in source_time_entries:
            source_path = entry["source_path"]
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                if source_path.endswith(".observed_at"):
                    observed_entries.append(entry)
                    self.assertIn("manual", source_path)
                    self.assertEqual(
                        entry["target_paths"],
                        [
                            "$.states[*].sources[*].payload.observed_at"
                        ],
                    )
                else:
                    imported_entries.append(entry)
                    self.assertIn("import", source_path)
                    self.assertEqual(
                        entry["target_paths"],
                        [
                            "$.states[*].sources[*].payload.source_payment_at"
                        ],
                    )
        self.assertTrue(observed_entries)
        self.assertTrue(imported_entries)

        mirror = final_sources["source-rg06-import-final-mirror"]
        original = final_sources[mirror["mirror_of_source_id"]]

        def assert_valid_mirror_pair(original_fact, mirror_fact):
            self.assertEqual(mirror_fact["currency"], original_fact["currency"])
            self.assertEqual(
                abs(Decimal(mirror_fact["amount"])),
                abs(Decimal(original_fact["amount"])),
            )
            self.assertEqual(
                Decimal(mirror_fact["amount"]),
                -Decimal(original_fact["amount"]),
            )

        assert_valid_mirror_pair(original, mirror)
        for contradictory_mirror in (
            {**mirror, "amount": original["amount"]},
            {**mirror, "amount": "-219.99"},
            {**mirror, "currency": "USD"},
        ):
            with self.subTest(contradictory_mirror=contradictory_mirror):
                with self.assertRaises(AssertionError):
                    assert_valid_mirror_pair(original, contradictory_mirror)

        status_registries = {
            "payment_progress": {"unpaid", "partially_paid", "paid_in_full"},
            "fulfillment_status": {"in_progress", "fulfilled"},
            "reconciliation": {"pending", "partial", "complete"},
        }

        def project_staged_payment_reconciliation(
            installments, postings, posting_reconciliations
        ):
            postings_by_id = {posting["id"]: posting for posting in postings}
            reconciliations_by_posting_id = {
                reconciliation["posting_id"]: reconciliation
                for reconciliation in posting_reconciliations
            }
            installment_count = len(installments)
            matched_count = 0
            for installment in installments:
                posting = postings_by_id.get(installment["asset_posting_id"])
                if posting is None:
                    continue
                reconciliation = reconciliations_by_posting_id.get(posting["id"])
                if (
                    posting.get("role") == "payment_asset"
                    and posting.get("reconciliation_eligible") is True
                    and reconciliation is not None
                    and reconciliation["status"] == "matched"
                ):
                    matched_count += 1
            if installment_count == 0 or matched_count == 0:
                return "pending"
            if matched_count == installment_count:
                return "complete"
            return "partial"

        def assert_valid_lifecycle_statuses(
            statuses,
            lifecycle_id,
            latest_history,
            installments,
            postings,
            posting_reconciliations,
        ):
            self.assertEqual(
                Counter(status["status_name"] for status in statuses),
                Counter({status_name: 1 for status_name in status_registries}),
            )
            statuses_by_name = {
                status["status_name"]: status for status in statuses
            }
            for status in statuses:
                self.assertEqual(status["target_kind"], "domain_entity")
                self.assertEqual(status["target_id"], lifecycle_id)
                self.assertIn(
                    status["value"], status_registries[status["status_name"]]
                )
            self.assertEqual(
                statuses_by_name["payment_progress"]["value"],
                latest_history["payment_progress"],
            )
            self.assertEqual(
                statuses_by_name["fulfillment_status"]["value"],
                latest_history["fulfillment_status"],
            )
            self.assertEqual(
                statuses_by_name["reconciliation"]["value"],
                project_staged_payment_reconciliation(
                    installments, postings, posting_reconciliations
                ),
            )

        lifecycle_id = "domain-rg06-lifecycle"
        zero_installment_group = next(group for group in groups if not group["payments"])
        two_installment_group = next(
            group for group in groups if len(group["payments"]) == 2
        )
        installments = [
            {
                "id": payment["id"],
                "asset_posting_id": payment["asset_posting_id"],
            }
            for payment in two_installment_group["payments"]
        ]
        postings = [
            {
                "id": installment["asset_posting_id"],
                "role": "payment_asset",
                "reconciliation_eligible": True,
            }
            for installment in installments
        ]
        unrelated_posting = {
            "id": "posting-rg06-unrelated",
            "role": "payment_asset",
            "reconciliation_eligible": True,
        }
        unrelated_reconciliation = {
            "id": "reconciliation-rg06-unrelated",
            "posting_id": unrelated_posting["id"],
            "status": "matched",
        }
        deposit_reconciliation = {
            "id": "reconciliation-rg06-deposit",
            "posting_id": installments[0]["asset_posting_id"],
            "status": "matched",
        }
        pending_final_reconciliation = {
            "id": "reconciliation-rg06-final",
            "posting_id": installments[1]["asset_posting_id"],
            "status": "pending",
        }
        final_reconciliation = {
            **pending_final_reconciliation,
            "status": "matched",
        }

        projection_cases = (
            (
                "zero_installments",
                zero_installment_group,
                [],
                [unrelated_posting],
                [unrelated_reconciliation],
                "pending",
                "complete",
            ),
            (
                "one_matched_one_unmatched",
                two_installment_group,
                installments,
                [*postings, unrelated_posting],
                [
                    deposit_reconciliation,
                    pending_final_reconciliation,
                    unrelated_reconciliation,
                ],
                "partial",
                "complete",
            ),
            (
                "all_matched",
                two_installment_group,
                installments,
                [*postings, unrelated_posting],
                [
                    deposit_reconciliation,
                    final_reconciliation,
                    unrelated_reconciliation,
                ],
                "complete",
                "partial",
            ),
        )
        for (
            case_name,
            lifecycle_group,
            case_installments,
            case_postings,
            case_reconciliations,
            expected_reconciliation,
            contradictory_reconciliation,
        ) in projection_cases:
            latest_history = lifecycle_group["state_history"][-1]
            valid_statuses = [
                {
                    "target_kind": "domain_entity",
                    "target_id": lifecycle_id,
                    "status_name": "payment_progress",
                    "value": latest_history["payment_progress"],
                },
                {
                    "target_kind": "domain_entity",
                    "target_id": lifecycle_id,
                    "status_name": "fulfillment_status",
                    "value": latest_history["fulfillment_status"],
                },
                {
                    "target_kind": "domain_entity",
                    "target_id": lifecycle_id,
                    "status_name": "reconciliation",
                    "value": expected_reconciliation,
                },
            ]
            with self.subTest(projection_case=case_name):
                assert_valid_lifecycle_statuses(
                    valid_statuses,
                    lifecycle_id,
                    latest_history,
                    case_installments,
                    case_postings,
                    case_reconciliations,
                )
                contradictory_statuses = [
                    *valid_statuses[:2],
                    {
                        **valid_statuses[2],
                        "value": contradictory_reconciliation,
                    },
                ]
                with self.assertRaises(AssertionError):
                    assert_valid_lifecycle_statuses(
                        contradictory_statuses,
                        lifecycle_id,
                        latest_history,
                        case_installments,
                        case_postings,
                        case_reconciliations,
                    )

        absent_reconciliation_installment = installments[:1]
        self.assertEqual(
            project_staged_payment_reconciliation(
                absent_reconciliation_installment,
                [postings[0], unrelated_posting],
                [unrelated_reconciliation],
            ),
            "pending",
        )
        ineligible_posting = {
            **postings[0],
            "reconciliation_eligible": False,
        }
        self.assertEqual(
            project_staged_payment_reconciliation(
                absent_reconciliation_installment,
                [ineligible_posting, unrelated_posting],
                [deposit_reconciliation, unrelated_reconciliation],
            ),
            "pending",
        )
        difference_reconciliation = {
            **deposit_reconciliation,
            "status": "has_difference",
        }
        self.assertEqual(
            project_staged_payment_reconciliation(
                absent_reconciliation_installment,
                [postings[0], unrelated_posting],
                [difference_reconciliation, unrelated_reconciliation],
            ),
            "pending",
        )

        latest_history = two_installment_group["state_history"][-1]
        valid_statuses = [
            {
                "target_kind": "domain_entity",
                "target_id": lifecycle_id,
                "status_name": "payment_progress",
                "value": latest_history["payment_progress"],
            },
            {
                "target_kind": "domain_entity",
                "target_id": lifecycle_id,
                "status_name": "fulfillment_status",
                "value": latest_history["fulfillment_status"],
            },
            {
                "target_kind": "domain_entity",
                "target_id": lifecycle_id,
                "status_name": "reconciliation",
                "value": "complete",
            },
        ]
        contradictory_status_sets = (
            [*valid_statuses, valid_statuses[0]],
            [{**valid_statuses[0], "target_kind": "relation"}, *valid_statuses[1:]],
            [{**valid_statuses[0], "target_id": "other"}, *valid_statuses[1:]],
            [{**valid_statuses[0], "value": "complete"}, *valid_statuses[1:]],
            [
                {**valid_statuses[0], "value": "partially_paid"},
                *valid_statuses[1:],
            ],
        )
        for contradictory_statuses in contradictory_status_sets:
            with self.subTest(contradictory_statuses=contradictory_statuses):
                with self.assertRaises(AssertionError):
                    assert_valid_lifecycle_statuses(
                        contradictory_statuses,
                        lifecycle_id,
                        latest_history,
                        installments,
                        postings,
                        [deposit_reconciliation, final_reconciliation],
                    )

        required_status_targets = {
            "$.states[*].derived_statuses[*].target_kind",
            "$.states[*].derived_statuses[*].target_id",
            "$.states[*].derived_statuses[*].status_name",
            "$.states[*].derived_statuses[*].value",
        }
        status_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"].endswith(
                (
                    ".group.payment_progress",
                    ".group.fulfillment_status",
                    ".reconciliation.group",
                )
            )
        ]
        self.assertTrue(status_entries)
        for entry in status_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                self.assertTrue(required_status_targets.issubset(entry["target_paths"]))

        posting_owner_entry = next(
            entry
            for entry in path_map["entries"]
            if entry["source_path"]
            == "$.manual_path.canonical_final_state.transactions[*].postings[*].account_id"
        )
        self.assertIn(
            "$.states[*].postings[*].role", posting_owner_entry["target_paths"]
        )
        self.assertIn("payment_asset", posting_owner_entry["transform"])
        self.assertIn("expense posting", posting_owner_entry["transform"])

        gap05 = next(
            gap for gap in path_map["resolved_contract_gaps"] if gap["id"] == "RG06-GAP-05"
        )
        self.assertEqual("approved_implemented", gap05["status"])
        gap05_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"] in gap05["affected_source_paths"]
        ]
        self.assertEqual(72, len(gap05_entries))
        self.assertTrue(all(entry["contract_gap_ids"] == [] for entry in gap05_entries))
        for entry in gap05_entries:
            semantic_text = f"{entry['transform']} {entry['rationale']}"
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertNotIn("merged_payment_bank_fact", semantic_text)

        rg06_source_row = next(
            line
            for line in contract_text.splitlines()
            if line.startswith("| source `staged_payment_bank_fact`")
        )
        self.assertIn("equal absolute magnitude", rg06_source_row)
        self.assertIn("opposite sign", rg06_source_row)
        self.assertNotIn("same amount", rg06_source_row)
        self.assertIn("exactly one of `observed_at` or `source_payment_at`", rg06_source_row)
        self.assertIn("manual bank-evidence fact requires `observed_at`", rg06_source_row)
        self.assertIn("imported payment fact requires `source_payment_at`", rg06_source_row)
        self.assertIn("the other time field is forbidden", rg06_source_row)
        rg06_evidence_row = next(
            line
            for line in contract_text.splitlines()
            if line.startswith("| evidence `staged_payment_bank_payment`")
        )
        self.assertIn("same required time field", rg06_evidence_row)
        self.assertIn("byte-for-byte equal", rg06_evidence_row)
        self.assertEqual(
            next(
                line
                for line in contract_text.splitlines()
                if line.startswith("| source `merged_payment_bank_fact`")
            ),
            "| source `merged_payment_bank_fact` | `evidence_id`, immutable "
            "`observed_at`, `details`, signed `amount`, `currency`, and "
            "`completeness:\"complete\"` |",
        )
        self.assertEqual(
            next(
                line
                for line in contract_text.splitlines()
                if line.startswith("| evidence `bank_payment`")
            ),
            "| evidence `bank_payment` | `observed_at`; exactly one "
            "`merged_payment_bank_fact` source |",
        )

        required_contract_clauses = (
            "At creation it has exactly one `staged_payment_lifecycle` domain-entity "
            "member; it may then append at most two distinct `installment_payment` "
            "domain-entity members, one per confirmed payment. Its closed payload is "
            "exactly `{}`.",
            "`payload.state_history` is the authoritative lifecycle state.",
            "`derived_statuses` is the sole current-state projection for "
            "`payment_progress`, `fulfillment_status`, and staged-payment "
            "`reconciliation`.",
            "The lifecycle payload must not duplicate any of those current status "
            "values.",
            "Every complete state that owns a `staged_payment_lifecycle` has exactly "
            "one current derived status for each of `payment_progress`, "
            "`fulfillment_status`, and `reconciliation`.",
            "All three use `target_kind=domain_entity` and the exact lifecycle entity "
            "ID as `target_id`.",
            "source `staged_payment_bank_fact`",
            "evidence `staged_payment_bank_payment`",
            "The existing RG-05 `bank_payment` evidence and its exact "
            "`merged_payment_bank_fact` source ownership are unchanged; neither RG-06 "
            "discriminator is an alias for either RG-05 discriminator.",
            "An RG-06 staged-payment bank evidence link contains only `id`, "
            "`evidence_id`, `target_kind`, `target_id`, and `role`.",
            "Its `target_kind` is `posting`, its `target_id` is the exact owned-real "
            "`payment_asset` posting of the referenced `installment_payment`, and its "
            "role is `payment_asset_posting`.",
            "A relation contains at most one `deposit` member and at most one `final` "
            "member; a `final` member is invalid unless the relation also contains "
            "its required `deposit` member.",
            "Relation member array order is not chronological evidence.",
            "Both referenced postings belong to that transaction's current posting "
            "set.",
            "The asset posting uses `funding_account_id`, role `payment_asset`, the "
            "installment currency, and the exact negative installment amount.",
            "The expense posting uses the lifecycle's second-level `category_id`, "
            "role `expense`, the same currency, and the exact positive installment "
            "amount.",
            "Neither variant permits both time fields, a missing required time, or a "
            "`null` time.",
            "The evidence payload uses the source variant's same required time field "
            "with the exact same timestamp text.",
            "`staged_payment_bank_fact` and `staged_payment_bank_payment` forbid "
            "`created_at` and `confirmed_at` fields entirely.",
            "Evidence-side `observed_at` and `source_payment_at` are each required "
            "only for their selected variant, and neither may be missing or `null`.",
            "The current lifecycle satisfies `total_amount = paid_amount + "
            "due_amount`, and `paid_amount` equals the sum of its distinct installment "
            "amounts.",
            "Current lifecycle totals and current payment/fulfillment projections "
            "equal the latest history item.",
            "A final installment's `actual_payment_at` must be strictly later than "
            "the deposit's `actual_payment_at`.",
            "The staged-payment evidence's sole source currency equals the referenced "
            "installment and `payment_asset` posting currency, and the source's "
            "absolute amount equals both the positive installment amount and the "
            "absolute posting amount.",
            "Let `N` be the number of distinct `installment_payment` members in the "
            "relation, and let `M` be the number of those members whose exact "
            "`asset_posting_id` resolves to an eligible `payment_asset` posting with "
            "a `posting_reconciliations` record whose status is `matched`.",
            "The reconciliation projection is `pending` exactly when `N = 0` or "
            "`M = 0`, `partial` exactly when `0 < M < N`, and `complete` exactly "
            "when `N > 0` and `M = N`.",
            "A missing reconciliation record, an ineligible installment asset "
            "posting, or any status other than `matched` contributes zero to `M`.",
            "Posting reconciliations for postings not named by the relation's "
            "installment members are ignored.",
        )
        for clause in required_contract_clauses:
            with self.subTest(clause=clause):
                self.assertIn(clause, contract_text)

        required_mapping_clauses = (
            "The initial relation contains exactly the lifecycle member and "
            "`payload={}`; each confirmed payment appends its distinct "
            "`installment_payment` member, up to two payment members total.",
            "Current `payment_progress`, `fulfillment_status`, and group "
            "`reconciliation` exist only in `derived_statuses`; none is duplicated in "
            "the lifecycle payload.",
            "RG-06 uses the distinct `staged_payment_bank_fact` source and "
            "`staged_payment_bank_payment` evidence discriminators.",
            "RG-05 `merged_payment_bank_fact` and `bank_payment` retain their existing "
            "closed ownership and semantics without aliasing or cross-subtype reuse.",
            "Each emitted evidence link uses only canonical evidence-link fields and "
            "targets the exact owned-real `payment_asset` posting with "
            "`target_kind=posting` and `role=payment_asset_posting`.",
            "Manual bank-evidence sources preserve `observed_at` only; imported "
            "payment sources, including mirrors, preserve `source_payment_at` only.",
            "The staged-payment evidence payload preserves the same field name and "
            "exact timestamp text as its sole source.",
            "Evidence-side `created_at` and `confirmed_at` are forbidden, not merely "
            "ignored.",
            "Member-array order is set-like and proves no payment chronology.",
            "Every lifecycle snapshot satisfies `total_amount = paid_amount + "
            "due_amount`.",
            "Current lifecycle totals and payment/fulfillment projections equal the "
            "latest history item.",
            "The final payment operation and payment-confirmed history item follow "
            "the deposit equivalents, and final payment time is strictly later than "
            "deposit payment time.",
            "Each evidence's sole bank fact has the installment and asset posting "
            "currency and an absolute amount equal to both.",
            "Let `N` be the count of distinct installment members and `M` the count "
            "whose exact eligible `payment_asset` posting reconciliation is "
            "`matched`.",
            "Group reconciliation is `pending` when `N = 0` or `M = 0`, `partial` "
            "when `0 < M < N`, and `complete` when `N > 0` and `M = N`.",
            "Missing records, ineligible installment postings, non-`matched` "
            "statuses, and reconciliations for unrelated postings do not increase "
            "`M`.",
        )
        for clause in required_mapping_clauses:
            with self.subTest(clause=clause):
                self.assertIn(clause, mapping_text)

    def test_rg06_closure_rules_match_closed_path_map_and_preserve_five_target_corrections(self):
        rules = load_rg06_closure_rules()
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}

        self.assertEqual("rg06_mapping_closure_proposal_rules", rules["artifact_type"])
        self.assertEqual(1, rules["artifact_version"])
        self.assertEqual("proposal_only", rules["scope"])
        self.assertEqual("approved", path_map["status"])
        self.assertEqual("approved", path_map["expected_output_gate"])
        self.assertEqual(1188, path_map["source_path_count"])
        self.assertEqual(3610, path_map["source_leaf_occurrence_count"])
        self.assertEqual(0, path_map["contract_gap_count"])
        self.assertEqual([], path_map["unresolved_contract_gaps"])
        self.assertEqual(5, path_map["resolved_contract_gap_count"])
        self.assertEqual(
            {"ready": 1181, "test_only_exclusion": 7},
            path_map["disposition_counts"],
        )
        self.assertTrue(
            all(
                gap["status"] == "approved_implemented"
                for gap in path_map["resolved_contract_gaps"]
            )
        )
        self.assertEqual(
            path_map["disposition_counts"],
            dict(Counter(entry["disposition"] for entry in path_map["entries"])),
        )

        corrected_targets = ["$.states[*].evidence[*].id"]
        for source_path in rules["five_target_corrections"]["source_paths"]:
            with self.subTest(source_path=source_path):
                entry = entries[source_path]
                self.assertEqual(corrected_targets, entry["target_paths"])
                self.assertEqual("preserve", entry["classification"])
                self.assertEqual("ready", entry["disposition"])
                self.assertEqual([], entry["contract_gap_ids"])

        planned_targets = [
            target_path
            for entry in path_map["entries"]
            for target_path in entry["target_paths"]
            if target_path.startswith(PLANNED_CONTRACT_PREFIX)
        ]
        self.assertEqual([], planned_targets)

        confirmation_entries = [
            entry
            for entry in path_map["entries"]
            if ".candidates[*].confirmation_provenance" in entry["source_path"]
        ]
        self.assertTrue(confirmation_entries)
        for entry in confirmation_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual("ready", entry["disposition"])
                self.assertEqual([], entry["contract_gap_ids"])
                self.assertFalse(
                    any(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        for target in entry["target_paths"]
                    )
                )

        candidate_rule_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"].endswith(".candidate.rule_version")
            or entry["source_path"].endswith(".candidates[*].rule_version")
        ]
        self.assertTrue(candidate_rule_entries)
        for entry in candidate_rule_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(
                    ["$.states[*].candidates[*].payload.rule_version"],
                    entry["target_paths"],
                )

    def test_rg06_closure_rules_cover_exact_legacy_targets_and_schema_paths(self):
        rules = load_rg06_closure_rules()
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        schema_paths = normalized_schema_paths(schema)

        self.assertEqual("explicit_per_target", rules["replacement_strategy"])
        prefix_rewrites = {
            (item["from"], item["to"]) for item in rules["prefix_rewrites"]
        }
        self.assertEqual(
            {
                ("$.planned_contract.states[*]", "$.states[*]"),
                ("$.planned_contract.operations[*]", "$.operations[*]"),
            },
            prefix_rewrites,
        )

        expected_legacy_targets = {
            "$.operations[*].input.association_group_id",
            "$.states[*].candidates[*].payload.association_group_id",
            "$.states[*].candidates[*].payload.candidates",
            "$.states[*].candidates[*].payload.category_id",
            "$.states[*].candidates[*].payload.confirmation_provenance",
            "$.states[*].candidates[*].payload.confirmed_at",
            "$.states[*].candidates[*].payload.evidence_id",
            "$.states[*].candidates[*].payload.exact_binding_confirmed",
            "$.states[*].candidates[*].payload.funding_account_id",
            "$.states[*].candidates[*].payload.immutable_source_fields[*]",
            "$.states[*].candidates[*].payload.kind",
            "$.states[*].candidates[*].payload.request_id",
            "$.states[*].domain_entities[*].payload.fulfillment_status",
            "$.states[*].domain_entities[*].payload.payment_ids[*]",
            "$.states[*].domain_entities[*].payload.payment_progress",
            "$.states[*].domain_entities[*].payload.payments",
            "$.states[*].domain_entities[*].payload.user_labels.fulfillment",
            "$.states[*].domain_entities[*].payload.user_labels.payment",
        }
        replacements = {
            rule["legacy_target"]: rule
            for rule in rules["legacy_target_replacements"]
        }
        self.assertEqual(expected_legacy_targets, set(replacements))
        self.assertEqual(18, len(replacements))

        def rewrite_prefix(target_path):
            for old_prefix, new_prefix in prefix_rewrites:
                if target_path.startswith(old_prefix):
                    return new_prefix + target_path[len(old_prefix) :]
            return target_path

        current_targets = {
            target_path
            for entry in path_map["entries"]
            for target_path in entry["target_paths"]
        }
        self.assertTrue(current_targets)
        self.assertTrue(current_targets.issubset(schema_paths))
        self.assertFalse(
            any(target.startswith(PLANNED_CONTRACT_PREFIX) for target in current_targets)
        )

        for legacy_target, rule in replacements.items():
            planned_target = f"$.planned_contract{legacy_target[1:]}"
            rewritten_target = rewrite_prefix(planned_target)
            canonical_targets = [
                *rule["replacement_targets"],
                *rule.get("retained_owner_paths", []),
            ]
            with self.subTest(legacy_target=legacy_target):
                self.assertEqual(legacy_target, rewritten_target)
                self.assertIn(rule["mode"], {"replace", "remove_redundant"})
                self.assertTrue(canonical_targets)
                self.assertTrue(set(canonical_targets).issubset(schema_paths))

    def test_rg06_closure_target_transform_emits_current_schema_paths(self):
        rules = load_rg06_closure_rules()
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        schema_paths = normalized_schema_paths(schema)
        prefix_rewrites = [
            (item["from"], item["to"]) for item in rules["prefix_rewrites"]
        ]
        self.assertEqual(
            [
                ("$.planned_contract.states[*]", "$.states[*]"),
                ("$.planned_contract.operations[*]", "$.operations[*]"),
            ],
            prefix_rewrites,
        )
        replacements = {
            rule["legacy_target"]: rule
            for rule in rules["legacy_target_replacements"]
        }
        self.assertEqual(18, len(replacements))
        redirect = rules["source_payload_evidence_id_closure"]
        special_target = redirect["planned_source_target"]
        special_emission = redirect["canonical_evidence_ownership"][
            "legacy_source_payload_evidence_id_emits_to"
        ]
        self.assertEqual(
            redirect["canonical_evidence_ownership"]["evidence_id_owner_path"],
            special_emission,
        )
        self.assertNotEqual(
            redirect["canonical_evidence_ownership"][
                "legacy_source_payload_evidence_id_must_not_emit_to"
            ],
            special_emission,
        )

        def rewrite_prefix(target_path):
            for old_prefix, new_prefix in prefix_rewrites:
                if target_path.startswith(old_prefix):
                    return new_prefix + target_path[len(old_prefix) :]
            return target_path

        def transform(target_path):
            if target_path == special_target:
                return [special_emission]
            rewritten_target = rewrite_prefix(target_path)
            replacement = replacements.get(rewritten_target)
            if replacement is None:
                return [rewritten_target]
            if replacement["mode"] == "remove_redundant":
                return []
            return replacement["replacement_targets"]

        current_targets = {
            target_path
            for entry in path_map["entries"]
            for target_path in entry["target_paths"]
        }
        self.assertTrue(current_targets)
        self.assertTrue(current_targets.issubset(schema_paths))
        self.assertFalse(
            any(target.startswith(PLANNED_CONTRACT_PREFIX) for target in current_targets)
        )
        planned_target_occurrences = [
            f"$.planned_contract{legacy_target[1:]}"
            for legacy_target in replacements
        ]
        planned_target_occurrences.append(special_target)
        self.assertEqual(19, len(planned_target_occurrences))

        emitted_targets = []
        applied_replacements = Counter()
        special_emissions = []
        for target_path in planned_target_occurrences:
            transformed_targets = transform(target_path)
            rewritten_target = rewrite_prefix(target_path)
            if target_path == special_target:
                special_emissions.extend(transformed_targets)
            elif rewritten_target in replacements:
                applied_replacements[rewritten_target] += 1
            for emitted_target in transformed_targets:
                with self.subTest(target_path=target_path, emitted_target=emitted_target):
                    self.assertFalse(
                        emitted_target.startswith(PLANNED_CONTRACT_PREFIX)
                    )
                    self.assertIn(emitted_target, schema_paths)
            emitted_targets.extend(transformed_targets)

        self.assertTrue(emitted_targets)
        self.assertEqual(
            {special_emission}, set(special_emissions)
        )
        self.assertNotIn(
            redirect["canonical_evidence_ownership"][
                "legacy_source_payload_evidence_id_must_not_emit_to"
            ],
            special_emissions,
        )
        self.assertEqual(set(replacements), set(applied_replacements))

        for replacement in replacements.values():
            if replacement["mode"] != "remove_redundant":
                continue
            for retained_owner_path in replacement["retained_owner_paths"]:
                with self.subTest(retained_owner_path=retained_owner_path):
                    self.assertIn(retained_owner_path, current_targets)

        for target_path in current_targets:
            with self.subTest(current_target=target_path):
                self.assertEqual([target_path], transform(target_path))

    def test_rg06_closure_rules_bind_closed_branches_confirmation_and_actions(self):
        rules = load_rg06_closure_rules()
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        schema_paths = normalized_schema_paths(schema)
        fixture = json.loads(RG06_FIXTURE_PATH.read_text(encoding="utf-8"))

        def assert_valid(definition, value):
            errors = list(schema_validator_for(definition, schema).iter_errors(value))
            self.assertEqual([], errors, [error.message for error in errors])

        def assert_invalid(definition, value):
            self.assertTrue(list(schema_validator_for(definition, schema).iter_errors(value)))

        candidate_branches = {
            branch["variant"]: branch for branch in rules["candidate_branches"]
        }
        self.assertEqual({"known_role", "ambiguous_role"}, set(candidate_branches))
        required_confirmation = [
            "relation_id",
            "payment_role",
            "category_id",
            "funding_account_id",
        ]
        self.assertEqual(
            required_confirmation,
            candidate_branches["known_role"]["requires_confirmation"],
        )
        self.assertEqual(
            required_confirmation,
            candidate_branches["ambiguous_role"]["requires_confirmation"],
        )
        self.assertEqual(
            {"rule": "staged_payment_bank_fact", "rule_version": 1},
            candidate_branches["known_role"]["provenance"],
        )
        self.assertEqual(
            candidate_branches["known_role"]["provenance"],
            candidate_branches["ambiguous_role"]["provenance"],
        )
        self.assertEqual(
            ["pending_confirmation"], rules["candidate_status_history"]["pending"]
        )
        self.assertEqual(
            ["pending_confirmation", "confirmed"],
            rules["candidate_status_history"]["confirmed"],
        )

        def staged_candidate(branch, statuses):
            payload = {
                "payment_role": (
                    branch["payment_role"][0]
                    if isinstance(branch["payment_role"], list)
                    else branch["payment_role"]
                ),
                "amount": "80.00",
                "currency": "CNY",
                "source_payment_at": "2026-04-28T10:00:00+08:00",
                "evidence_ref": "evidence-import",
                "provenance": branch["provenance"],
                "requires_confirmation": branch["requires_confirmation"],
            }
            if branch.get("guessed_payment_role") != "omitted":
                payload["guessed_payment_role"] = branch["guessed_payment_role"]
            return {
                "id": f"candidate-{branch['variant']}",
                "type": "staged_payment",
                "source_ids": ["source-import"],
                "confidence": branch["confidence"],
                "payload": payload,
                "status_history": [
                    {
                        "id": f"candidate-status-{sequence}",
                        "sequence": sequence,
                        "status": status,
                    }
                    for sequence, status in enumerate(statuses, start=1)
                ],
            }

        known_candidate = staged_candidate(
            candidate_branches["known_role"], rules["candidate_status_history"]["confirmed"]
        )
        ambiguous_candidate = staged_candidate(
            candidate_branches["ambiguous_role"],
            rules["candidate_status_history"]["pending"],
        )
        assert_valid(candidate_branches["known_role"]["definition"], known_candidate)
        assert_valid(
            candidate_branches["ambiguous_role"]["definition"], ambiguous_candidate
        )
        assert_valid("stagedPaymentCandidate", known_candidate)
        assert_valid("stagedPaymentCandidate", ambiguous_candidate)

        wrong_confidence = deepcopy(known_candidate)
        wrong_confidence["confidence"] = "0.50"
        assert_invalid("stagedPaymentCandidate", wrong_confidence)
        missing_ambiguous_null = deepcopy(ambiguous_candidate)
        missing_ambiguous_null["payload"].pop("guessed_payment_role")
        assert_invalid("stagedPaymentCandidate", missing_ambiguous_null)
        wrong_provenance = deepcopy(known_candidate)
        wrong_provenance["payload"]["provenance"]["rule_version"] = 2
        assert_invalid("stagedPaymentCandidate", wrong_provenance)
        wrong_requirements = deepcopy(known_candidate)
        wrong_requirements["payload"]["requires_confirmation"][-1] = (
            "association_group_id"
        )
        assert_invalid("stagedPaymentCandidate", wrong_requirements)

        authorization = rules["candidate_confirmation"]["authorization_operation"]
        self.assertTrue(
            rules["candidate_confirmation"]["candidate_status_is_not_authorization"]
        )
        self.assertEqual(
            authorization["required_input_fields"],
            schema["$defs"][authorization["input_definition"]]["required"],
        )
        self.assertEqual(
            "creates_installment_payment_only_after_authorized_operation",
            authorization["formal_effect"],
        )
        self.assertIn(authorization["actual_payment_at_owner"], schema_paths)

        confirmation_input = {
            "request_id": "request-confirm",
            "candidate_id": known_candidate["id"],
            "relation_id": "relation-staged-payment",
            "payment_role": "deposit",
            "category_id": "category-service",
            "funding_account_id": "asset-bank",
            "exact_binding_confirmed": True,
        }
        assert_valid(authorization["input_definition"], confirmation_input)
        for field in authorization["required_input_fields"]:
            missing = deepcopy(confirmation_input)
            missing.pop(field)
            with self.subTest(confirmation_field=field):
                assert_invalid(authorization["input_definition"], missing)
        false_confirmation = deepcopy(confirmation_input)
        false_confirmation["exact_binding_confirmed"] = False
        assert_invalid(authorization["input_definition"], false_confirmation)
        alias_confirmation = deepcopy(confirmation_input)
        alias_confirmation["association_group_id"] = "legacy-group"
        assert_invalid(authorization["input_definition"], alias_confirmation)

        confirmation = {
            "id": "confirmation-candidate",
            "type": authorization["confirmation_type"],
            "operation_id": "operation-confirm",
            "subject": {"kind": authorization["subject_kind"], "id": known_candidate["id"]},
            "confirmed_at": "2026-04-28T10:05:00+08:00",
            "payload": {},
        }
        assert_valid(authorization["confirmation_definition"], confirmation)
        wrong_subject = deepcopy(confirmation)
        wrong_subject["subject"]["kind"] = "operation"
        assert_invalid(authorization["confirmation_definition"], wrong_subject)
        wrong_confirmation_type = deepcopy(confirmation)
        wrong_confirmation_type["type"] = "explicit_operation_confirmation"
        assert_invalid(authorization["confirmation_definition"], wrong_confirmation_type)

        source_branches = {branch["variant"]: branch for branch in rules["source_branches"]}
        evidence_branches = {
            branch["variant"]: branch for branch in rules["evidence_branches"]
        }
        self.assertEqual({"manual", "imported"}, set(source_branches))
        self.assertEqual({"manual", "imported"}, set(evidence_branches))
        self.assertFalse(source_branches["manual"]["mirror"]["allowed"])
        self.assertTrue(source_branches["imported"]["mirror"]["allowed"])
        self.assertFalse(evidence_branches["manual"]["mirror"]["allowed"])
        self.assertTrue(evidence_branches["imported"]["mirror"]["allowed"])

        manual_source = {
            "id": "source-manual",
            "type": source_branches["manual"]["type"],
            "payload": {
                "amount": "-80.00",
                "currency": "CNY",
                "observed_at": "2026-04-28T10:00:00+08:00",
            },
        }
        frozen_import_sources = fixture["import_path"]["canonical_final_state"][
            "source_records"
        ]
        original_source_index, original_record = next(
            (index, record)
            for index, record in enumerate(frozen_import_sources)
            if record["id"] == "source-rg06-import-final"
        )
        mirror_source_index, mirror_record = next(
            (index, record)
            for index, record in enumerate(frozen_import_sources)
            if record["id"] == "source-rg06-import-final-mirror"
        )
        self.assertEqual(original_record["id"], mirror_record["mirror_of_source_id"])
        self.assertLess(original_source_index, mirror_source_index)
        original_source = {
            "id": original_record["id"],
            "type": source_branches["imported"]["type"],
            "payload": {
                "amount": original_record["amount"],
                "currency": original_record["currency"],
                "source_payment_at": original_record["source_payment_at"],
            },
        }
        mirror_source = {
            "id": mirror_record["id"],
            "type": source_branches["imported"]["type"],
            "payload": {
                "amount": mirror_record["amount"],
                "currency": mirror_record["currency"],
                "source_payment_at": mirror_record["source_payment_at"],
                "mirror_of_source_id": mirror_record["mirror_of_source_id"],
            },
        }
        assert_valid(source_branches["manual"]["definition"], manual_source)
        assert_valid(source_branches["imported"]["definition"], original_source)
        assert_valid(source_branches["imported"]["definition"], mirror_source)
        manual_as_imported = deepcopy(manual_source)
        manual_as_imported["payload"]["source_payment_at"] = "2026-04-28T10:00:00+08:00"
        assert_invalid(source_branches["manual"]["definition"], manual_as_imported)
        manual_mirror = deepcopy(manual_source)
        manual_mirror["payload"]["mirror_of_source_id"] = original_source["id"]
        assert_invalid(source_branches["manual"]["definition"], manual_mirror)
        imported_with_observed = deepcopy(original_source)
        imported_with_observed["payload"]["observed_at"] = original_source["payload"][
            "source_payment_at"
        ]
        assert_invalid(source_branches["imported"]["definition"], imported_with_observed)
        null_source_mirror = deepcopy(mirror_source)
        null_source_mirror["payload"]["mirror_of_source_id"] = None
        assert_invalid(source_branches["imported"]["definition"], null_source_mirror)

        manual_evidence = {
            "id": "evidence-manual",
            "type": evidence_branches["manual"]["type"],
            "source_ids": [manual_source["id"]],
            "payload": {
                "payment_id": "installment-deposit",
                "observed_at": "2026-04-28T10:00:00+08:00",
            },
        }
        original_evidence = {
            "id": "evidence-original",
            "type": evidence_branches["imported"]["type"],
            "source_ids": [original_source["id"]],
            "payload": {
                "payment_id": "installment-final",
                "source_payment_at": original_source["payload"]["source_payment_at"],
            },
        }
        mirror_evidence = {
            "id": "evidence-mirror",
            "type": evidence_branches["imported"]["type"],
            "source_ids": [mirror_source["id"]],
            "payload": {
                "payment_id": "installment-final",
                "source_payment_at": mirror_source["payload"]["source_payment_at"],
                "mirror_of_evidence_id": original_evidence["id"],
                "merged_into_evidence_link_id": "evidence-link-original",
            },
        }
        assert_valid(evidence_branches["manual"]["definition"], manual_evidence)
        assert_valid(evidence_branches["imported"]["definition"], original_evidence)
        assert_valid(evidence_branches["imported"]["definition"], mirror_evidence)
        manual_evidence_as_imported = deepcopy(manual_evidence)
        manual_evidence_as_imported["payload"]["source_payment_at"] = (
            "2026-04-28T10:00:00+08:00"
        )
        assert_invalid(
            evidence_branches["manual"]["definition"], manual_evidence_as_imported
        )
        incomplete_evidence_mirror = deepcopy(mirror_evidence)
        incomplete_evidence_mirror["payload"].pop("merged_into_evidence_link_id")
        assert_invalid(
            evidence_branches["imported"]["definition"], incomplete_evidence_mirror
        )
        null_evidence_mirror = deepcopy(mirror_evidence)
        null_evidence_mirror["payload"]["mirror_of_evidence_id"] = None
        assert_invalid(
            evidence_branches["imported"]["definition"], null_evidence_mirror
        )

        mirror_rule = rules["mirror_branch"]
        self.assertEqual("none", mirror_rule["formal_effect"])
        self.assertNotIn("mirror_of_source_id", original_source["payload"])
        self.assertLess(
            original_source_index,
            mirror_source_index,
        )
        self.assertEqual(
            original_source["payload"]["source_payment_at"],
            mirror_source["payload"]["source_payment_at"],
        )
        self.assertEqual(
            original_source["payload"]["currency"], mirror_source["payload"]["currency"]
        )
        self.assertEqual(
            abs(Decimal(original_source["payload"]["amount"])),
            abs(Decimal(mirror_source["payload"]["amount"])),
        )
        self.assertLess(
            Decimal(original_source["payload"]["amount"])
            * Decimal(mirror_source["payload"]["amount"]),
            Decimal("0"),
        )
        for invariant in (
            "original_is_non_mirror",
            "original_precedes_mirror_in_frozen_source_collection",
            "source_payment_at_must_be_byte_for_byte_equal",
            "currency_must_match",
            "absolute_amount_must_match",
            "amounts_must_have_opposite_sign",
        ):
            with self.subTest(mirror_invariant=invariant):
                self.assertTrue(mirror_rule[invariant])

        expected_action_classes = {
            "create_staged_payment": "creation",
            "record_staged_payment_installment": "creation",
            "change_staged_payment_fulfillment": "status_transition",
            "confirm_staged_payment_completion": "status_transition",
            "link_staged_payment_evidence": "reconciliation",
            "ingest_staged_payment_bank_fact": "creation",
            "confirm_staged_payment_candidate": "creation",
            "merge_staged_payment_mirror_evidence": "reconciliation",
        }
        action_rules = {rule["action_type"]: rule for rule in rules["action_rules"]}
        self.assertEqual(set(expected_action_classes), set(action_rules))
        for action_type, operation_class in expected_action_classes.items():
            rule = action_rules[action_type]
            with self.subTest(action_type=action_type):
                self.assertEqual(operation_class, rule["operation_class"])
                self.assertEqual(
                    rule["required_input_fields"],
                    schema["$defs"][rule["input_definition"]]["required"],
                )
                for optional_field in rule.get("optional_input_fields", []):
                    self.assertIn(
                        optional_field,
                        schema["$defs"][rule["input_definition"]]["properties"],
                    )
                    self.assertNotIn(optional_field, rule["required_input_fields"])

        action_inputs = {
            "create_staged_payment": {
                "request_id": "request-create",
                "kind": "staged_payment",
                "total_amount": "300.00",
                "currency": "CNY",
                "category_id": "category-service",
                "created_at": "2026-04-20T09:00:00+08:00",
            },
            "record_staged_payment_installment": {
                "request_id": "request-deposit",
                "relation_id": "relation-staged-payment",
                "payment_role": "deposit",
                "payment_amount": "80.00",
                "currency": "CNY",
                "funding_account_id": "asset-bank",
                "actual_payment_at": "2026-04-28T10:00:00+08:00",
            },
            "change_staged_payment_fulfillment": {
                "request_id": "request-fulfillment",
                "relation_id": "relation-staged-payment",
                "fulfillment_status": "fulfilled",
                "occurred_at": "2026-05-01T12:00:00+08:00",
            },
            "confirm_staged_payment_completion": {
                "request_id": "request-completion",
                "relation_id": "relation-staged-payment",
                "confirmed": True,
                "occurred_at": "2026-05-04T09:00:00+08:00",
            },
            "link_staged_payment_evidence": {
                "source_id": "source-manual",
                "evidence_id": "evidence-manual",
                "payment_id": "installment-deposit",
                "posting_id": "posting-asset",
            },
            "ingest_staged_payment_bank_fact": {
                "source_id": "source-import",
                "evidence_id": "evidence-import",
                "source_payment_at": "2026-04-28T10:00:00+08:00",
                "amount": "80.00",
                "currency": "CNY",
                "suggested_payment_role": "deposit",
            },
            "confirm_staged_payment_candidate": confirmation_input,
            "merge_staged_payment_mirror_evidence": {
                "source_id": "source-mirror",
                "evidence_id": "evidence-mirror",
                "payment_id": "installment-final",
                "posting_id": "posting-asset-final",
                "amount": "-220.00",
                "currency": "CNY",
                "source_payment_at": "2026-05-03T16:30:00+08:00",
            },
        }
        entity_collections = (
            "catalog_accounts",
            "catalog_categories",
            "transactions",
            "transaction_versions",
            "posting_sets",
            "postings",
            "sources",
            "candidates",
            "confirmations",
            "evidence",
            "evidence_links",
            "relations",
            "domain_entities",
            "audit_links",
            "posting_reconciliations",
        )
        for action_type, action_input in action_inputs.items():
            rule = action_rules[action_type]
            for status in ("accepted", "no_change"):
                outcome = {"status": status}
                if status == "no_change":
                    outcome["reason_code"] = "idempotent_replay"
                operation = {
                    "id": f"operation-{action_type}-{status}",
                    "root_id": "root-rg06",
                    "sequence": 1,
                    "operation_class": rule["operation_class"],
                    "action_type": action_type,
                    "baseline_state_id": "state-before",
                    "result_state_id": "state-after",
                    "outcome": outcome,
                    "status_changes": [],
                    "deltas": {
                        "entity_changes": {
                            name: {
                                "added_ids": [],
                                "changed_ids": [],
                                "removed_ids": [],
                            }
                            for name in entity_collections
                        },
                        "value_changes": {
                            "balances": [],
                            "reports": [],
                            "derived_statuses": [],
                        },
                    },
                    "returned_ids": [],
                    "input": action_input,
                }
                with self.subTest(action_type=action_type, status=status):
                    assert_valid("operation", operation)

    def test_rg06_rejection_dispatch_replays_all_fixture_invalid_inputs(self):
        rules = load_rg06_closure_rules()
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(RG06_FIXTURE_PATH.read_text(encoding="utf-8"))
        action_rules = {rule["action_type"]: rule for rule in rules["action_rules"]}
        dispatch = {
            action: action_rules[action]["rejection"]
            for action in (
                "create_staged_payment",
                "record_staged_payment_installment",
                "confirm_staged_payment_completion",
            )
        }
        self.assertEqual(
            [
                {
                    "legacy_operation_context": "group_creation",
                    "action_type": "create_staged_payment",
                    "operation_class": "rejection",
                    "attempted_input_definition": "createStagedPaymentAttemptedInput",
                    "outcome_definition": "createStagedPaymentRejectedOutcome",
                },
                {
                    "legacy_operation_context": "payment_creation",
                    "action_type": "record_staged_payment_installment",
                    "operation_class": "rejection",
                    "attempted_input_definition": "recordStagedPaymentInstallmentAttemptedInput",
                    "outcome_definition": "recordStagedPaymentInstallmentRejectedOutcome",
                },
                {
                    "legacy_operation_context": "payment_progress_transition",
                    "action_type": "confirm_staged_payment_completion",
                    "operation_class": "rejection",
                    "attempted_input_definition": "confirmStagedPaymentCompletionAttemptedInput",
                    "outcome_definition": "confirmStagedPaymentCompletionRejectedOutcome",
                },
            ],
            rules["rejection_dispatch"],
        )
        self.assertEqual(
            {
                "group_creation": "create_staged_payment",
                "payment_creation": "record_staged_payment_installment",
                "payment_progress_transition": "confirm_staged_payment_completion",
            },
            {
                context: action
                for action, rule in dispatch.items()
                for context in rule["legacy_operation_contexts"]
            },
        )

        def assert_valid(definition, value):
            errors = list(schema_validator_for(definition, schema).iter_errors(value))
            self.assertEqual([], errors, [error.message for error in errors])

        def empty_deltas():
            collections = (
                "catalog_accounts",
                "catalog_categories",
                "transactions",
                "transaction_versions",
                "posting_sets",
                "postings",
                "sources",
                "candidates",
                "confirmations",
                "evidence",
                "evidence_links",
                "relations",
                "domain_entities",
                "audit_links",
                "posting_reconciliations",
            )
            return {
                "entity_changes": {
                    name: {
                        "added_ids": [],
                        "changed_ids": [],
                        "removed_ids": [],
                    }
                    for name in collections
                },
                "value_changes": {
                    "balances": [],
                    "reports": [],
                    "derived_statuses": [],
                },
            }

        def baseline_for(action, baseline_id):
            state = deepcopy(staged_payment_state())
            state["catalog"]["categories"].extend(
                [
                    {
                        "id": "expense-category-service-parent",
                        "name": "Synthetic parent",
                        "parent_id": None,
                        "posting_account_id": None,
                        "active": True,
                    },
                    {
                        "id": "expense-category-inactive",
                        "name": "Synthetic inactive",
                        "parent_id": "expense-category-service-parent",
                        "posting_account_id": "expense-service",
                        "active": False,
                    },
                    {
                        "id": "income-category-other",
                        "name": "Synthetic income",
                        "parent_id": "expense-category-service-parent",
                        "posting_account_id": "income-account",
                        "active": True,
                    },
                ]
            )
            state["catalog"]["accounts"].extend(
                [
                    {
                        "id": "income-account",
                        "name": "Synthetic income",
                        "kind": "income",
                        "currency": "CNY",
                        "owned_by_user": False,
                        "real_account": False,
                        "reconciliation_eligible": False,
                    },
                    {
                        "id": "expense-account-service",
                        "name": "Synthetic expense",
                        "kind": "expense",
                        "currency": "CNY",
                        "owned_by_user": False,
                        "real_account": False,
                        "reconciliation_eligible": False,
                    },
                    {
                        "id": "asset-external-x",
                        "name": "Synthetic external asset",
                        "kind": "asset",
                        "currency": "CNY",
                        "owned_by_user": False,
                        "real_account": True,
                        "reconciliation_eligible": True,
                    },
                    {
                        "id": "liability-credit-a",
                        "name": "Synthetic liability",
                        "kind": "liability",
                        "currency": "CNY",
                        "owned_by_user": True,
                        "real_account": True,
                        "reconciliation_eligible": True,
                    },
                ]
            )
            if baseline_id == "after_deposit":
                lifecycle = rg06_lifecycle_entity(state)["payload"]
                lifecycle["paid_amount"] = "80.00"
                lifecycle["due_amount"] = "220.00"
            return state

        fixture_cases = {item["id"]: item for item in fixture["invalid_inputs"]}
        declared_cases = [
            case
            for rule in dispatch.values()
            for case in rule["cases"]
        ]
        self.assertEqual(18, len(declared_cases))
        self.assertEqual(set(fixture_cases), {case["fixture_id"] for case in declared_cases})

        for action, rejection in dispatch.items():
            self.assertTrue(rejection["sparse_attempted_input"])
            self.assertEqual("rejection", rejection["operation_class"])
            self.assertEqual(
                {
                    "status": "rejected",
                    "deltas": "empty",
                    "status_changes": [],
                    "returned_ids": [],
                    "state_effect": "none",
                },
                rejection["atomic_effect"],
            )
            for case in rejection["cases"]:
                fixture_case = fixture_cases[case["fixture_id"]]
                attempted_input = fixture_case["input"]
                outcome = fixture_case["expected"]
                operation = {
                    "id": f"operation-rg06-{fixture_case['id']}",
                    "root_id": "root-rg06",
                    "sequence": 1,
                    "operation_class": "rejection",
                    "action_type": action,
                    "baseline_state_id": "state-rg06-rejection-before",
                    "result_state_id": "state-rg06-rejection-after",
                    "attempted_input": deepcopy(attempted_input),
                    "outcome": {
                        "status": "rejected",
                        "reason_code": outcome["reason"],
                        "field_path": f"$.attempted_input.{outcome['field']}",
                    },
                    "status_changes": [],
                    "deltas": empty_deltas(),
                    "returned_ids": [],
                }
                baseline = baseline_for(action, case["baseline_id"])
                result = deepcopy(baseline)
                with self.subTest(fixture_id=fixture_case["id"], action=action):
                    self.assertEqual(case["baseline_id"], outcome["baseline_id"])
                    self.assertEqual(case["reason_code"], outcome["reason"])
                    self.assertEqual(case["field_path"], operation["outcome"]["field_path"])
                    assert_valid(case["attempted_input_definition"], attempted_input)
                    assert_valid(rejection["outcome_definition"], operation["outcome"])
                    assert_valid("operation", operation)
                    golden_v2._validate_action_input(
                        operation,
                        "$.operations[0]",
                        baseline,
                        RG06_PRECISIONS,
                        golden_v2.ZoneInfo("Asia/Shanghai"),
                    )
                    expected = golden_v2._expected_entity_changes(baseline, result)
                    golden_v2._validate_registered_action_effects(
                        operation, "$.operations[0]", result, expected
                    )
                    self.assertEqual(golden_v2._state_payload(baseline), golden_v2._state_payload(result))
                    self.assertEqual([], operation["status_changes"])
                    self.assertEqual([], operation["returned_ids"])
                    self.assertTrue(
                        all(
                            not ids
                            for changes in expected.values()
                            for ids in changes.values()
                        )
                    )
                    self.assertEqual(
                        0,
                        sum(outcome_count for outcome_count in fixture_case["expected"]["effect_counts"].values()),
                    )

    def test_rg06_manual_installment_confirmation_replay_owns_version(self):
        rules = load_rg06_closure_rules()
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(RG06_FIXTURE_PATH.read_text(encoding="utf-8"))
        manual_rule = next(
            rule
            for rule in rules["action_rules"]
            if rule["action_type"] == "record_staged_payment_installment"
        )
        confirmation_rule = manual_rule["manual_confirmation"]
        self.assertEqual("explicit_manual_save", confirmation_rule["confirmation_type"])
        self.assertEqual("confirmation-{payment_role}", confirmation_rule["confirmation_id_template"])
        self.assertEqual(
            {"kind": "operation", "id_source": "operation.id"},
            confirmation_rule["subject"],
        )
        self.assertEqual(
            "confirmation.id",
            confirmation_rule["transaction_version_confirmation_id_source"],
        )
        self.assertEqual(
            [
                {"kind": "confirmation", "id_source": "confirmation.id"},
                {"kind": "transaction", "id_source": "transaction.id"},
                {"kind": "domain_entity", "id_source": "installment.id"},
            ],
            confirmation_rule["returned_ids"],
        )

        manual_operations = {
            operation["id"]: operation
            for operation in fixture["manual_path"]["ordered_operations"]
            if operation["id"] in {"save-deposit", "save-final"}
        }
        self.assertEqual({"save-deposit", "save-final"}, set(manual_operations))
        self.assertEqual(
            {"save-deposit", "save-final"},
            {item["fixture_operation_id"] for item in confirmation_rule["fixture_replay"]},
        )

        for replay in confirmation_rule["fixture_replay"]:
            legacy = manual_operations[replay["fixture_operation_id"]]
            legacy_input = legacy["input"]
            operation_id = f"operation-{legacy['id']}"
            canonical_input = {
                "request_id": legacy_input["request_id"],
                "relation_id": "relation-staged-payment",
                "payment_role": legacy_input["payment_role"],
                "payment_amount": legacy_input["payment_amount"],
                "currency": legacy_input["currency"],
                "funding_account_id": "asset-bank",
                "actual_payment_at": legacy_input["actual_payment_at"],
            }
            confirmation_id = f"confirmation-{canonical_input['payment_role']}"
            if canonical_input["payment_role"] == "deposit":
                baseline = rg06_creation_result()
                result = rg06_deposit_result()
            else:
                result = rg06_final_result()
                baseline = deepcopy(result)
                final_payment = rg06_payment_entity(baseline, "final")
                final_transaction_id = final_payment["payload"]["transaction_id"]
                final_transaction = next(
                    item for item in baseline["transactions"] if item["id"] == final_transaction_id
                )
                final_version_id = final_transaction["current_version_id"]
                final_version = next(
                    item for item in baseline["transaction_versions"] if item["id"] == final_version_id
                )
                final_set_id = final_version["posting_set_id"]
                final_posting_ids = set(
                    next(item for item in baseline["posting_sets"] if item["id"] == final_set_id)["posting_ids"]
                )
                baseline["transactions"] = [
                    item for item in baseline["transactions"] if item["id"] != final_transaction_id
                ]
                baseline["transaction_versions"] = [
                    item for item in baseline["transaction_versions"] if item["id"] != final_version_id
                ]
                baseline["posting_sets"] = [
                    item for item in baseline["posting_sets"] if item["id"] != final_set_id
                ]
                baseline["postings"] = [
                    item for item in baseline["postings"] if item["id"] not in final_posting_ids
                ]
                baseline["domain_entities"] = [
                    item for item in baseline["domain_entities"] if item["id"] != final_payment["id"]
                ]
                baseline["relations"][0]["member_refs"] = [
                    item for item in baseline["relations"][0]["member_refs"] if item["id"] != final_payment["id"]
                ]
                baseline["confirmations"] = [
                    item for item in baseline["confirmations"] if item["id"] != confirmation_id
                ]
                baseline["posting_reconciliations"] = [
                    item
                    for item in baseline["posting_reconciliations"]
                    if item["posting_id"] not in final_posting_ids
                ]
                lifecycle = rg06_lifecycle_entity(baseline)["payload"]
                lifecycle["paid_amount"] = "80.00"
                lifecycle["due_amount"] = "220.00"
                lifecycle["state_history"] = lifecycle["state_history"][:3]

            result_confirmation = next(
                item for item in result["confirmations"] if item["id"] == confirmation_id
            )
            result_confirmation["operation_id"] = operation_id
            result_confirmation["subject"] = {"kind": "operation", "id": operation_id}
            operation = {
                "id": operation_id,
                "action_type": "record_staged_payment_installment",
                "operation_class": "creation",
                "input": canonical_input,
                "outcome": {"status": "accepted"},
                "returned_ids": [
                    {"kind": "confirmation", "id": confirmation_id},
                    {"kind": "transaction", "id": f"transaction-{canonical_input['payment_role']}"},
                    {"kind": "domain_entity", "id": f"installment-{canonical_input['payment_role']}"},
                ],
            }
            version = next(
                item
                for item in result["transaction_versions"]
                if item["id"] == next(
                    tx["current_version_id"]
                    for tx in result["transactions"]
                    if tx["id"] == f"transaction-{canonical_input['payment_role']}"
                )
            )
            with self.subTest(fixture_operation_id=legacy["id"]):
                self.assertEqual(confirmation_id, version["confirmation_id"])
                errors = list(
                    schema_validator_for("explicitManualSaveConfirmation", schema).iter_errors(
                        result_confirmation
                    )
                )
                self.assertEqual([], errors, [error.message for error in errors])
                expected = golden_v2._expected_entity_changes(baseline, result)
                golden_v2._validate_rg06_action_effects(
                    operation, "$.operations[0]", baseline, result, expected
                )

    def test_rg06_source_payload_evidence_redirect_uses_closed_branches(self):
        rules = load_rg06_closure_rules()
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(RG06_FIXTURE_PATH.read_text(encoding="utf-8"))
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-06-path-map.json"
            ).read_text(encoding="utf-8")
        )

        def assert_valid(definition, value):
            errors = list(schema_validator_for(definition, schema).iter_errors(value))
            self.assertEqual([], errors, [error.message for error in errors])

        def assert_invalid(definition, value):
            self.assertTrue(list(schema_validator_for(definition, schema).iter_errors(value)))

        redirect = rules["source_payload_evidence_id_closure"]
        self.assertEqual("RG-06", redirect["case_id"])
        self.assertFalse(redirect["future_closure_only"])
        self.assertEqual("redirected", redirect["current_path_map_action"])
        self.assertEqual(
            "remove_after_redirect", redirect["source_payload_action"]
        )
        self.assertEqual(
            "$.planned_contract.states[*].sources[*].payload.evidence_id",
            redirect["planned_source_target"],
        )
        self.assertEqual(
            "stagedPaymentBankFactSource", redirect["source_definition"]
        )
        self.assertEqual("evidence_id", redirect["forbidden_source_payload_field"])
        self.assertEqual("direct_schema_branches_only", redirect["validation"])
        self.assertEqual(
            {
                "evidence_definition": "stagedPaymentBankPaymentEvidence",
                "evidence_id_owner_path": "$.states[*].evidence[*].id",
                "legacy_source_payload_evidence_id_emits_to": (
                    "$.states[*].evidence[*].id"
                ),
                "evidence_source_reference_path": (
                    "$.states[*].evidence[*].source_ids[*]"
                ),
                "evidence_source_reference_value_source": "$.source_records[*].id",
                "linked_evidence_source_reference_validation": (
                    "$.evidence_links[*].source_id"
                ),
                "unlinked_pending_candidate_source_reference_value_source": (
                    "$.candidates[*].source_id"
                ),
                "unlinked_pending_candidate_evidence_link": "absent",
                "legacy_source_payload_evidence_id_must_not_emit_to": (
                    "$.states[*].evidence[*].source_ids[*]"
                ),
                "source_payload_evidence_id": "forbidden",
            },
            redirect["canonical_evidence_ownership"],
        )

        corrected_source_paths = set(rules["five_target_corrections"]["source_paths"])
        source_target_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"] in corrected_source_paths
        ]
        self.assertEqual(5, len(source_target_entries))
        self.assertEqual(
            set(rules["five_target_corrections"]["source_paths"]),
            {entry["source_path"] for entry in source_target_entries},
        )
        source_reference_entries = [
            entry
            for entry in path_map["entries"]
            if "$.states[*].evidence[*].source_ids[*]"
            in entry["target_paths"]
        ]
        self.assertTrue(source_reference_entries)
        self.assertTrue(
            all(
                entry["source_path"].endswith(".evidence_links[*].source_id")
                for entry in source_reference_entries
            )
        )
        for entry in source_target_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(
                    ["$.states[*].evidence[*].id"],
                    entry["target_paths"],
                )
                self.assertEqual("ready", entry["disposition"])
                self.assertEqual([], entry["contract_gap_ids"])

        branches = {branch["source_variant"]: branch for branch in redirect["branches"]}
        source_branches = {
            branch["variant"]: branch for branch in rules["source_branches"]
        }
        evidence_branches = {
            branch["variant"]: branch for branch in rules["evidence_branches"]
        }
        self.assertEqual({"manual", "imported"}, set(branches))
        for variant, route in branches.items():
            with self.subTest(variant=variant):
                self.assertEqual("stagedPaymentBankFactSource", route["source_definition"])
                self.assertEqual(
                    "stagedPaymentBankPaymentEvidence", route["evidence_definition"]
                )
                self.assertEqual(
                    "$.states[*].evidence[*].id", route["evidence_id_owner_path"]
                )
                self.assertEqual(
                    "$.states[*].evidence[*].id",
                    route["legacy_source_payload_evidence_id_emits_to"],
                )
                self.assertEqual(
                    "$.states[*].evidence[*].source_ids[*]",
                    route["evidence_source_reference_path"],
                )
                self.assertEqual(
                    "$.source_records[*].id",
                    route["evidence_source_reference_value_source"],
                )
                self.assertEqual(
                    "$.evidence_links[*].source_id",
                    route["linked_evidence_source_reference_validation"],
                )
                if variant == "imported":
                    self.assertEqual(
                        "$.candidates[*].source_id",
                        route["unlinked_pending_candidate_source_reference_value_source"],
                    )
                    self.assertEqual("absent", route["unlinked_pending_candidate_evidence_link"])
                self.assertEqual(
                    "$.states[*].evidence[*].source_ids[*]",
                    route["legacy_source_payload_evidence_id_must_not_emit_to"],
                )
                self.assertEqual("forbidden", route["source_payload_evidence_id"])
                self.assertEqual(route["source_definition"], source_branches[variant]["definition"])
                self.assertEqual(
                    route["evidence_definition"], evidence_branches[variant]["definition"]
                )
                self.assertIn(
                    "evidence_id", source_branches[variant]["forbidden_payload_fields"]
                )
        self.assertNotIn("candidate_evidence_reference_path", branches["manual"])
        self.assertEqual(
            "$.states[*].candidates[*].payload.evidence_ref",
            branches["imported"]["candidate_evidence_reference_path"],
        )
        direct_proof = rules["future_closure_acceptance"]["direct_schema_branch_proof"]
        self.assertEqual(["manual", "imported"], direct_proof["source_evidence_variants"])
        self.assertEqual("stagedPaymentCandidate", direct_proof["candidate_definition"])
        self.assertEqual(
            ["known_role", "ambiguous_role"], direct_proof["candidate_variants"]
        )
        self.assertTrue(direct_proof["candidate_cross_branch_rejections_required"])
        self.assertTrue(direct_proof["unlinked_pending_evidence_source_reference_required"])
        self.assertEqual("absent", direct_proof["unlinked_pending_evidence_link"])
        self.assertEqual(0, direct_proof["unlinked_pending_formal_effect"])
        self.assertEqual(0, direct_proof["unlinked_pending_reconciliation_effect"])

        fixture_paths = {"manual": "manual_path", "imported": "import_path"}
        for variant, route in branches.items():
            state = fixture[fixture_paths[variant]]["canonical_final_state"]
            links_by_source = {
                link["source_id"]: link for link in state["evidence_links"]
            }
            candidates_by_source = {
                candidate["source_id"]: candidate
                for candidate in state.get("candidates", [])
                if candidate.get("source_id") is not None
            }
            emitted_evidence_by_id = {}
            for record in state["source_records"]:
                payload = {
                    "amount": record["amount"],
                    "currency": record["currency"],
                }
                if variant == "manual":
                    payload["observed_at"] = record["observed_at"]
                else:
                    payload["source_payment_at"] = record["source_payment_at"]
                    if record["mirror_of_source_id"] is not None:
                        payload["mirror_of_source_id"] = record["mirror_of_source_id"]
                source = {
                    "id": record["id"],
                    "type": "staged_payment_bank_fact",
                    "payload": payload,
                }
                with self.subTest(variant=variant, source_id=record["id"]):
                    assert_valid(route["source_definition"], source)
                    assert_valid(
                        source_branches[variant]["payload_definition"],
                        source["payload"],
                    )
                    self.assertNotIn("evidence_id", source["payload"])
                    invalid_source = deepcopy(source)
                    invalid_source["payload"]["evidence_id"] = record["evidence_id"]
                    assert_invalid(route["source_definition"], invalid_source)
                    assert_invalid(
                        source_branches[variant]["payload_definition"],
                        invalid_source["payload"],
                    )

                    if variant == "manual":
                        evidence_payload = {
                            "payment_id": links_by_source[record["id"]]["payment_id"],
                            "observed_at": record["observed_at"],
                        }
                    else:
                        evidence_payload = {
                            "source_payment_at": record["source_payment_at"]
                        }
                        link = links_by_source.get(record["id"])
                        if link is not None:
                            evidence_payload["payment_id"] = link["payment_id"]
                            if link["mirror_of_evidence_id"] is not None:
                                evidence_payload["mirror_of_evidence_id"] = link[
                                    "mirror_of_evidence_id"
                                ]
                                evidence_payload["merged_into_evidence_link_id"] = link[
                                    "merged_into_evidence_link_id"
                                ]
                    evidence = {
                        "id": record["evidence_id"],
                        "type": "staged_payment_bank_payment",
                        "source_ids": [record["id"]],
                        "payload": evidence_payload,
                    }
                    assert_valid(route["evidence_definition"], evidence)
                    assert_valid(
                        evidence_branches[variant]["payload_definition"],
                        evidence["payload"],
                    )
                    self.assertEqual(record["evidence_id"], evidence["id"])
                    self.assertEqual([record["id"]], evidence["source_ids"])
                    self.assertNotIn(record["evidence_id"], evidence["source_ids"])
                    link = links_by_source.get(record["id"])
                    if link is not None:
                        self.assertEqual(record["id"], link["source_id"])
                    else:
                        self.assertEqual("imported", variant)
                        candidate = candidates_by_source[record["id"]]
                        self.assertEqual("pending_confirmation", candidate["status"])
                        self.assertEqual(record["id"], candidate["source_id"])
                        self.assertEqual(record["evidence_id"], candidate["evidence_id"])
                        self.assertEqual(
                            [],
                            [
                                candidate_link
                                for candidate_link in state["evidence_links"]
                                if candidate_link["source_id"] == record["id"]
                            ],
                        )
                    emitted_evidence_by_id[evidence["id"]] = evidence

            for link in state["evidence_links"]:
                record = next(
                    record
                    for record in state["source_records"]
                    if record["id"] == link["source_id"]
                )
                with self.subTest(variant=variant, evidence_id=link["evidence_id"]):
                    self.assertEqual(record["id"], link["source_id"])
                    self.assertEqual(record["evidence_id"], link["evidence_id"])
                    self.assertNotEqual(link["evidence_id"], link["source_id"])
                    emitted_evidence = emitted_evidence_by_id[link["evidence_id"]]
                    self.assertEqual([link["source_id"]], emitted_evidence["source_ids"])
                    self.assertNotIn(
                        record["evidence_id"], emitted_evidence["source_ids"]
                    )

    def test_rg06_unlinked_pending_evidence_is_semantically_zero_effect(self):
        state = staged_payment_state()
        payment_at = rg06_installment(state, "deposit")["payload"]["actual_payment_at"]
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

        golden_v2._validate_references(
            state,
            RG06_STATE_PATH,
            golden_v2._state_indexes(state, RG06_STATE_PATH),
            {"operation-confirm-pending-unbound": {"root_id": state["root_id"]}},
            RG06_PRECISIONS,
            golden_v2.ZoneInfo("Asia/Shanghai"),
        )
        validate_rg06_relations(state)
        self.assertEqual([], state["evidence_links"])
        self.assertEqual("pending_confirmation", state["candidates"][0]["status_history"][0]["status"])
        self.assertEqual([], state["posting_reconciliations"])

    def test_rg06_fixture_candidate_rewrites_and_confirmation_branches(self):
        rules = load_rg06_closure_rules()
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(RG06_FIXTURE_PATH.read_text(encoding="utf-8"))
        import_path = fixture["import_path"]
        state = import_path["canonical_final_state"]

        def assert_valid(definition, value):
            errors = list(schema_validator_for(definition, schema).iter_errors(value))
            self.assertEqual([], errors, [error.message for error in errors])

        token_rewrite = rules["candidate_requires_confirmation_token_rewrite"]
        self.assertEqual("RG-06", token_rewrite["case_id"])
        self.assertFalse(token_rewrite["future_closure_only"])
        self.assertEqual("staged_payment", token_rewrite["legacy_candidate_kind"])
        self.assertEqual("stagedPaymentCandidate", token_rewrite["source_definition"])
        self.assertEqual("requires_confirmation", token_rewrite["legacy_field"])
        self.assertEqual("payload.requires_confirmation", token_rewrite["target_field"])
        self.assertEqual("array_token", token_rewrite["rewrite_kind"])
        self.assertEqual(
            "staged_payment_candidate_requires_confirmation_token_only",
            token_rewrite["scope"],
        )
        self.assertTrue(token_rewrite["frozen_source_values_unchanged"])
        self.assertTrue(token_rewrite["forbid_broad_value_substitution"])
        self.assertEqual("association_group_id", token_rewrite["from"])
        self.assertEqual("relation_id", token_rewrite["to"])
        canonical_tokens = [
            "relation_id",
            "payment_role",
            "category_id",
            "funding_account_id",
        ]
        self.assertEqual(canonical_tokens, token_rewrite["canonical_tokens"])
        candidate_branches = {
            branch["variant"]: branch for branch in rules["candidate_branches"]
        }
        self.assertEqual({"known_role", "ambiguous_role"}, set(candidate_branches))

        def rewrite_token(value, entity_kind, field):
            if (
                entity_kind == token_rewrite["legacy_candidate_kind"]
                and field == token_rewrite["legacy_field"]
                and value == token_rewrite["from"]
            ):
                return token_rewrite["to"]
            return value

        candidates = state["candidates"]
        self.assertEqual(3, len(candidates))
        for candidate in candidates:
            with self.subTest(candidate_id=candidate["id"]):
                self.assertEqual("staged_payment", candidate["kind"])
                frozen_requires_confirmation = list(candidate["requires_confirmation"])
                self.assertEqual(
                    [
                        "association_group_id",
                        "payment_role",
                        "category_id",
                        "funding_account_id",
                    ],
                    frozen_requires_confirmation,
                )
                rewritten = [
                    rewrite_token(
                        token,
                        candidate["kind"],
                        token_rewrite["legacy_field"],
                    )
                    for token in frozen_requires_confirmation
                ]
                self.assertEqual(canonical_tokens, rewritten)
                self.assertEqual(canonical_tokens, list(rewritten))
                self.assertNotIn(token_rewrite["from"], rewritten)
                self.assertEqual(frozen_requires_confirmation, candidate["requires_confirmation"])

                variant = (
                    "ambiguous_role"
                    if candidate["payment_role"] is None
                    else "known_role"
                )
                branch = candidate_branches[variant]
                self.assertEqual(candidate["rule_version"], branch["provenance"]["rule_version"])
                payload = {
                    "payment_role": candidate["payment_role"],
                    "amount": candidate["amount"],
                    "currency": candidate["currency"],
                    "source_payment_at": candidate["source_payment_at"],
                    "evidence_ref": candidate["evidence_id"],
                    "provenance": branch["provenance"],
                    "requires_confirmation": rewritten,
                }
                if variant == "ambiguous_role":
                    payload["guessed_payment_role"] = candidate["guessed_payment_role"]
                status_key = (
                    "confirmed"
                    if candidate["confirmation_provenance"] is not None
                    else "pending"
                )
                emitted_candidate = {
                    "id": candidate["id"],
                    "type": "staged_payment",
                    "source_ids": [candidate["source_id"]],
                    "confidence": candidate["confidence"],
                    "payload": payload,
                    "status_history": [
                        {
                            "id": f"{candidate['id']}-status-{sequence}",
                            "sequence": sequence,
                            "status": status,
                        }
                        for sequence, status in enumerate(
                            rules["candidate_status_history"][status_key], start=1
                        )
                    ],
                }
                assert_valid(branch["schema_branch_definition"], emitted_candidate)
                assert_valid("stagedPaymentCandidate", emitted_candidate)
                self.assertEqual(
                    canonical_tokens,
                    emitted_candidate["payload"]["requires_confirmation"],
                )

        confirmation_operations = [
            operation
            for operation in import_path["ordered_operations"]
            if "candidate_id" in operation["input"]
        ]
        self.assertEqual(2, len(confirmation_operations))
        for operation in confirmation_operations:
            with self.subTest(operation_id=operation["id"]):
                self.assertIn("association_group_id", operation["input"])
                self.assertNotIn("relation_id", operation["input"])
                self.assertEqual(
                    "association_group_id",
                    rewrite_token(
                        "association_group_id", "operation", "association_group_id"
                    ),
                )

        authorization = rules["candidate_confirmation"]["authorization_operation"]
        provenance_branches = rules["candidate_confirmation"][
            "legacy_confirmation_provenance_branches"
        ]
        pending_branch = provenance_branches["pending_null"]
        confirmed_branch = provenance_branches["confirmed_non_null"]
        self.assertIsNone(pending_branch["legacy_confirmation_provenance"])
        self.assertEqual("pending_confirmation", pending_branch["candidate_status"])
        self.assertEqual(0, pending_branch["confirmation_count"])
        self.assertEqual(0, pending_branch["operation_count"])
        self.assertEqual("none", pending_branch["formal_effect"])
        self.assertEqual("non_null", confirmed_branch["legacy_confirmation_provenance"])
        self.assertEqual(1, confirmed_branch["confirmation_count"])
        self.assertEqual(1, confirmed_branch["operation_count"])
        self.assertEqual(
            authorization["confirmation_type"], confirmed_branch["confirmation_type"]
        )
        self.assertEqual(
            "$.states[*].confirmations[*].confirmed_at",
            confirmed_branch["confirmed_at_owner_path"],
        )
        self.assertEqual(
            authorization["subject_kind"], confirmed_branch["subject_kind"]
        )
        self.assertEqual(
            authorization["action_type"], confirmed_branch["operation_action_type"]
        )
        self.assertEqual(
            authorization["input_definition"],
            confirmed_branch["operation_input_definition"],
        )
        self.assertEqual(
            "candidate_id", confirmed_branch["operation_input_candidate_field"]
        )
        self.assertEqual(
            {
                "confirmed_at": {
                    "source": "confirmation_provenance.confirmed_at",
                    "preservation": "identical",
                },
                "subject": {"kind": "candidate", "id_source": "candidate.id"},
            },
            confirmed_branch["confirmation_projection"],
        )
        self.assertEqual(
            {
                "request_id": "confirmation_provenance.request_id",
                "candidate_id": "candidate.id",
                "relation_id": "confirmation_provenance.association_group_id",
                "payment_role": "confirmation_provenance.payment_role",
                "category_id": "confirmation_provenance.category_id",
                "funding_account_id": "confirmation_provenance.funding_account_id",
                "exact_binding_confirmed": (
                    "confirmation_provenance.exact_binding_confirmed"
                ),
            },
            confirmed_branch["operation_input_projection"],
        )
        self.assertEqual(
            "creates_installment_payment_only_after_authorized_operation",
            confirmed_branch["formal_effect"],
        )

        operations_by_request = {
            operation["input"]["request_id"]: operation
            for operation in confirmation_operations
        }
        entity_collections = (
            "catalog_accounts",
            "catalog_categories",
            "transactions",
            "transaction_versions",
            "posting_sets",
            "postings",
            "sources",
            "candidates",
            "confirmations",
            "evidence",
            "evidence_links",
            "relations",
            "domain_entities",
            "audit_links",
            "posting_reconciliations",
        )

        def target_confirmation_operation(operation_id, operation_input):
            return {
                "id": operation_id,
                "root_id": "root-rg06",
                "sequence": 1,
                "operation_class": authorization["operation_class"],
                "action_type": authorization["action_type"],
                "baseline_state_id": "state-before",
                "result_state_id": "state-after",
                "outcome": {"status": "accepted"},
                "status_changes": [],
                "deltas": {
                    "entity_changes": {
                        name: {
                            "added_ids": [],
                            "changed_ids": [],
                            "removed_ids": [],
                        }
                        for name in entity_collections
                    },
                    "value_changes": {
                        "balances": [],
                        "reports": [],
                        "derived_statuses": [],
                    },
                },
                "returned_ids": [],
                "input": operation_input,
            }

        confirmations = []
        confirming_operations = []
        for candidate in candidates:
            provenance = candidate["confirmation_provenance"]
            if provenance is None:
                with self.subTest(candidate_id=candidate["id"], branch="pending"):
                    self.assertEqual("pending_confirmation", candidate["status"])
                    self.assertNotIn(
                        candidate["id"],
                        {
                            operation["input"]["candidate_id"]
                            for operation in confirmation_operations
                        },
                    )
                    self.assertNotIn(
                        candidate["source_id"],
                        {
                            source_id
                            for transaction in state["transactions"]
                            for source_id in transaction["source_refs"]
                        },
                    )
                continue

            operation = operations_by_request[provenance["request_id"]]
            legacy_input = operation["input"]
            canonical_input = deepcopy(legacy_input)
            canonical_input["relation_id"] = canonical_input.pop(
                "association_group_id"
            )
            confirmation = {
                "id": f"confirmation-{candidate['id']}",
                "type": confirmed_branch["confirmation_type"],
                "operation_id": operation["id"],
                "subject": {
                    "kind": confirmed_branch["subject_kind"],
                    "id": candidate["id"],
                },
                "confirmed_at": provenance[confirmed_branch["confirmed_at_source_field"]],
                "payload": {},
            }
            target_operation = target_confirmation_operation(
                operation["id"], canonical_input
            )
            with self.subTest(candidate_id=candidate["id"], branch="confirmed"):
                self.assertEqual("confirmed", candidate["status"])
                self.assertEqual(candidate["id"], legacy_input["candidate_id"])
                self.assertEqual(
                    provenance["association_group_id"], canonical_input["relation_id"]
                )
                for field in (
                    "payment_role",
                    "category_id",
                    "funding_account_id",
                    "exact_binding_confirmed",
                ):
                    self.assertEqual(provenance[field], canonical_input[field])
                self.assertEqual(
                    set(authorization["required_input_fields"]), set(canonical_input)
                )
                assert_valid(authorization["input_definition"], canonical_input)
                assert_valid(authorization["confirmation_definition"], confirmation)
                assert_valid("operation", target_operation)
                self.assertEqual(
                    provenance["confirmed_at"], confirmation["confirmed_at"]
                )
                self.assertEqual(
                    {"kind": "candidate", "id": candidate["id"]},
                    confirmation["subject"],
                )
                self.assertEqual(operation["id"], confirmation["operation_id"])
                self.assertEqual(
                    authorization["action_type"], target_operation["action_type"]
                )
                self.assertEqual(canonical_input, target_operation["input"])
            confirmations.append(confirmation)
            confirming_operations.append(target_operation)

        non_null_candidates = [
            candidate
            for candidate in candidates
            if candidate["confirmation_provenance"] is not None
        ]
        self.assertEqual(len(non_null_candidates), len(confirmations))
        self.assertEqual(len(non_null_candidates), len(confirming_operations))
        self.assertEqual(
            {candidate["id"] for candidate in non_null_candidates},
            {confirmation["subject"]["id"] for confirmation in confirmations},
        )
        self.assertEqual(
            {
                candidate["confirmation_provenance"]["confirmed_at"]
                for candidate in non_null_candidates
            },
            {confirmation["confirmed_at"] for confirmation in confirmations},
        )

    def test_rg07_refund_cash_inflow_uses_current_cash_inflow_metric(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-07.json").read_text(
                encoding="utf-8"
            )
        )
        values = normalized_leaf_values(source)
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-07-path-map.json"
            ).read_text(encoding="utf-8")
        )
        self.assertNotIn(
            "RG07-GAP-04",
            {gap["id"] for gap in path_map["unresolved_contract_gaps"]},
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        refund_cash_inflow_entries = []
        for source_path, occurrences in values.items():
            if "refund_cash_inflow" in source_path or any(
                value == "refund_cash_inflow" for value in occurrences
            ):
                refund_cash_inflow_entries.append(entries[source_path])

        self.assertTrue(refund_cash_inflow_entries)
        for entry in refund_cash_inflow_entries:
            target_paths = set(entry["target_paths"])
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                self.assertIn(
                    "$.states[*].reports[*].metrics[*].metric", target_paths
                )
                self.assertIn("canonical cash_inflow", semantic_text)
                self.assertFalse(
                    any(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        or "ordinary_income" in target
                        for target in target_paths
                    )
                )
        self.assertFalse(
            any(
                "RG07-GAP-04" in entry["contract_gap_ids"]
                for entry in path_map["entries"]
            )
        )

    def test_rg07_operation_registry_and_payload_branches_are_explicit(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-07.json").read_text(
                encoding="utf-8"
            )
        )
        values = normalized_leaf_values(source)
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-07-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        action_registry = {
            "allocate_refund_receipt": {"rejection"},
            "attach_original_payment_evidence": {"reconciliation"},
            "attach_refund_destination_evidence": {"reconciliation"},
            "attach_refund_dual_role_evidence": {"reconciliation"},
            "confirm_imported_refund": {"creation", "rejection"},
            "confirm_manual_refund_receipt": {"creation", "rejection"},
            "confirm_refund_receipt": {"creation"},
            "ingest_refund_credit_source": {"creation"},
            "ingest_refund_status_source": {"status_transition"},
            "merge_refund_mirror_evidence": {"reconciliation"},
            "record_refund_request_status": {"status_transition"},
            "validate_refund_receipt": {"rejection"},
        }
        pair_pattern = re.compile(
            r"action_type=([a-z0-9_]+), operation_class=([a-z_]+)"
        )
        entries_by_pair = defaultdict(list)
        for entry in path_map["entries"]:
            match = pair_pattern.search(entry["transform"])
            if match is not None:
                entries_by_pair[match.groups()].append(entry)
            self.assertNotIn(
                "$.operations[*].input.kind", entry["target_paths"]
            )
            self.assertNotIn(
                "$.planned_contract.operations[*].input.kind",
                entry["target_paths"],
            )

        discovered_registry = defaultdict(set)
        for action_type, operation_class in entries_by_pair:
            discovered_registry[action_type].add(operation_class)
        self.assertEqual(dict(discovered_registry), action_registry)

        action_target = "$.operations[*].action_type"
        class_target = "$.operations[*].operation_class"
        for pair, pair_entries in entries_by_pair.items():
            action_type, operation_class = pair
            aggregate_targets = {
                target
                for entry in pair_entries
                for target in entry["target_paths"]
            }
            with self.subTest(
                action_type=action_type, operation_class=operation_class
            ):
                self.assertIn(action_target, aggregate_targets)
                self.assertIn(class_target, aggregate_targets)
                branch = (
                    "attempted_input" if operation_class == "rejection" else "input"
                )
                self.assertTrue(
                    any(
                        f".operations[*].{branch}." in target
                        for target in aggregate_targets
                    )
                )

            submitted_entries = [
                entry
                for entry in pair_entries
                if ".expected." not in entry["source_path"]
                and any(
                    ".operations[*].input." in target
                    or ".operations[*].attempted_input." in target
                    for target in entry["target_paths"]
                )
            ]
            self.assertTrue(submitted_entries, pair)
            for entry in submitted_entries:
                with self.subTest(
                    action_type=action_type,
                    operation_class=operation_class,
                    source_path=entry["source_path"],
                ):
                    if operation_class == "rejection":
                        self.assertFalse(
                            any(
                                ".operations[*].input." in target
                                and ".operations[*].attempted_input." not in target
                                for target in entry["target_paths"]
                            )
                        )
                    else:
                        self.assertFalse(
                            any(
                                ".operations[*].attempted_input." in target
                                for target in entry["target_paths"]
                            )
                        )

        fixture_action_types = {
            value
            for source_path, occurrences in values.items()
            if source_path.endswith(".operation_type")
            for value in occurrences
        }
        self.assertTrue(fixture_action_types)
        self.assertTrue(fixture_action_types.issubset(action_registry))
        for source_path, occurrences in values.items():
            if not source_path.endswith(".operation_type"):
                continue
            entry = entries[source_path]
            match = pair_pattern.search(entry["transform"])
            with self.subTest(source_path=source_path):
                self.assertIsNotNone(match)
                self.assertTrue(
                    all(
                        match.group(1) == action_type
                        and match.group(2) in action_registry[action_type]
                        for action_type in occurrences
                    )
                )

        retry_entry = entries["$.idempotency.retried_inputs[*]"]
        retry_text = (
            f"{retry_entry['transform']} {retry_entry['rationale']}".lower()
        )
        self.assertIn(action_target, retry_entry["target_paths"])
        self.assertIn(class_target, retry_entry["target_paths"])
        self.assertTrue(
            any(
                ".operations[*].input." in target
                for target in retry_entry["target_paths"]
            )
        )
        self.assertIn("originating registered action_type", retry_text)
        self.assertIn("no generic retry action", retry_text)

    def test_rg08_collection_identity_audit_time_and_effective_gap(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-08-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}

        complete_snapshot_patterns = {
            "planned": re.compile(
                r"(?:^|\.)canonical_state\.id$|^\$\.canonical_states\.[^.]+\.id$"
            ),
            "current": re.compile(
                r"(?:^|\.)resulting_state\.id$|"
                r"(?:^|\.)pre_operation_baseline\.id$|"
                r"(?:^|\.)baseline_state\.id$|(?:^|\.)result_state\.id$"
            ),
        }
        complete_snapshot_entries = []
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            snapshot_kind = next(
                (
                    kind
                    for kind, pattern in complete_snapshot_patterns.items()
                    if pattern.search(source_path)
                ),
                None,
            )
            if snapshot_kind is None:
                continue
            complete_snapshot_entries.append(entry)
            expected_target = (
                "$.planned_contract.states[*].id"
                if snapshot_kind == "planned"
                else "$.states[*].id"
            )
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["target_paths"], [expected_target])
                self.assertNotIn(
                    "$.planned_contract.operations[*].input.id",
                    entry["target_paths"],
                )
        self.assertTrue(complete_snapshot_entries)

        exact_collection_fields = {
            "transactions[*].postings[*].": {
                field: f"$.states[*].postings[*].{field}"
                for field in (
                    "id",
                    "account_id",
                    "amount",
                    "currency",
                    "reconciliation_eligible",
                )
            },
            "transactions[*].": {
                "id": "$.states[*].transactions[*].id",
                "current_version_id": "$.states[*].transactions[*].current_version_id",
                "occurred_at": "$.states[*].transaction_versions[*].occurred_at",
                "posting_set_id": "$.states[*].posting_sets[*].id",
                "statistics_at": "$.states[*].transaction_versions[*].statistics_at",
                "type": "$.states[*].transactions[*].type",
            },
            "versions[*].": {
                "id": "$.states[*].transaction_versions[*].id",
                "created_at": "$.states[*].transaction_versions[*].created_at",
                "posting_set_id": "$.states[*].transaction_versions[*].posting_set_id",
                "transaction_id": "$.states[*].transaction_versions[*].transaction_id",
            },
        }
        matched_collection_fields = 0
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            for marker, field_targets in exact_collection_fields.items():
                if marker not in source_path:
                    continue
                if (
                    marker == "transactions[*]."
                    and "transactions[*].postings[*]." in source_path
                ):
                    continue
                tail = source_path.split(marker, 1)[1]
                if tail not in field_targets:
                    continue
                matched_collection_fields += 1
                with self.subTest(source_path=source_path):
                    self.assertEqual(
                        entry["target_paths"], [field_targets[tail]]
                    )
        self.assertGreater(matched_collection_fields, 0)

        position_ids = []
        settlement_ids = []
        history_ids = []
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            if source_path.endswith(".positions[*].id"):
                position_ids.append(entry)
            if source_path.endswith(".settlements[*].id"):
                settlement_ids.append(entry)
            if source_path.endswith(
                (".positions[*].history[*].id", ".settlements[*].history[*].id")
            ):
                history_ids.append(entry)
        self.assertTrue(position_ids)
        self.assertTrue(settlement_ids)
        self.assertTrue(history_ids)
        for entry in position_ids + settlement_ids:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(
                    entry["target_paths"],
                    ["$.planned_contract.states[*].domain_entities[*].id"],
                )
        for entry in history_ids:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(
                    entry["target_paths"],
                    [
                        "$.planned_contract.states[*].domain_entities[*].payload."
                        "history[*].id"
                    ],
                )

        audit_ref_fields = {
            "mirror_of_evidence_id": {
                "$.planned_contract.states[*].audit_links[*].from.kind",
                "$.planned_contract.states[*].audit_links[*].from.id",
            },
            "merged_into_evidence_link_id": {
                "$.planned_contract.states[*].audit_links[*].to.kind",
                "$.planned_contract.states[*].audit_links[*].to.id",
            },
        }
        matched_audit_refs = 0
        for entry in path_map["entries"]:
            field = entry["source_path"].rsplit(".", 1)[-1]
            if field not in audit_ref_fields:
                continue
            matched_audit_refs += 1
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(set(entry["target_paths"]), audit_ref_fields[field])
        self.assertGreater(matched_audit_refs, 0)

        economic_time_targets = {
            "$.states[*].transaction_versions[*].occurred_at",
            "$.states[*].transaction_versions[*].statistics_at",
            "$.states[*].transaction_versions[*].effective_at",
        }
        for entry in path_map["entries"]:
            if not entry["source_path"].endswith((".created_at", ".confirmed_at")):
                continue
            with self.subTest(source_path=entry["source_path"]):
                self.assertTrue(
                    economic_time_targets.isdisjoint(entry["target_paths"])
                )

        gaps = {gap["id"]: gap for gap in path_map["unresolved_contract_gaps"]}
        self.assertIn("RG08-GAP-04", gaps)
        effective_gap = gaps["RG08-GAP-04"]
        ambiguous_paths = {
            source_path
            for source_path in entries
            if source_path.endswith(
                (".actual_receipt_at", ".proposed_actual_receipt_at")
            )
            or source_path == "$.lend.request.actual_at"
        }
        gap_paths = {
            entry["source_path"]
            for entry in path_map["entries"]
            if "RG08-GAP-04" in entry["contract_gap_ids"]
        }
        self.assertEqual(gap_paths, ambiguous_paths)
        self.assertEqual(set(effective_gap["affected_source_paths"]), gap_paths)
        self.assertEqual(
            effective_gap["affected_source_path_count"], len(gap_paths)
        )
        for source_path in gap_paths:
            entry = entries[source_path]
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["disposition"], "requires_contract_amendment")
                self.assertIn("effective_at is not inferred", semantic_text)
                self.assertFalse(
                    any(
                        target.endswith((".created_at", ".confirmed_at"))
                        for target in entry["target_paths"]
                    )
                )

    def test_rg09_snapshot_ids_current_audits_and_gap_scope(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-09-path-map.json"
            ).read_text(encoding="utf-8")
        )
        gaps = {gap["id"]: gap for gap in path_map["unresolved_contract_gaps"]}
        self.assertNotIn("RG09-GAP-04", gaps)

        snapshot_pattern = re.compile(
            r"^(?:\$\.canonical_states\.[^.]+\.id|.*\.(?:resulting_state|pre_operation_baseline)\.id)$"
        )
        state_id_entries = []
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            if "$.states[*].id" in entry["target_paths"]:
                state_id_entries.append(entry)
                with self.subTest(source_path=source_path):
                    self.assertIsNotNone(snapshot_pattern.fullmatch(source_path))
            elif source_path.endswith(".id"):
                with self.subTest(source_path=source_path):
                    self.assertNotIn("$.states[*].id", entry["target_paths"])
        self.assertTrue(state_id_entries)

        audit_field_targets = {
            "id": {"$.states[*].audit_links[*].id"},
            "allocation_id": {
                "$.states[*].audit_links[*].from.kind",
                "$.states[*].audit_links[*].from.id",
            },
            "target_id": {
                "$.states[*].audit_links[*].to.kind",
                "$.states[*].audit_links[*].to.id",
            },
            "role": {"$.states[*].audit_links[*].type"},
        }
        matched_audit_entries = 0
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            if ".audit_links[*]." not in source_path:
                continue
            self.assertFalse(
                any(".audit_links[*].payload" in target for target in entry["target_paths"])
            )
            field = source_path.rsplit(".", 1)[-1]
            if field not in audit_field_targets:
                continue
            matched_audit_entries += 1
            with self.subTest(source_path=source_path):
                self.assertEqual(
                    set(entry["target_paths"]), audit_field_targets[field]
                )
                self.assertEqual(entry["disposition"], "ready")
        self.assertGreater(matched_audit_entries, 0)

        current_semantic_entries = []
        for entry in path_map["entries"]:
            source_path = entry["source_path"]
            target_paths = entry["target_paths"]
            is_current_target = target_paths and not any(
                target.startswith(PLANNED_CONTRACT_PREFIX)
                for target in target_paths
            )
            is_current_semantic = any(
                marker in source_path
                for marker in (
                    ".adjustments[*].",
                    ".allocations[*].",
                    ".audit_links[*].",
                    ".reconciliation.",
                )
            ) or source_path.endswith(".status")
            if is_current_target and is_current_semantic:
                current_semantic_entries.append(entry)
        self.assertTrue(current_semantic_entries)
        for entry in current_semantic_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertNotIn("RG09-GAP-02", entry["contract_gap_ids"])

        gap02_entries = [
            entry
            for entry in path_map["entries"]
            if "RG09-GAP-02" in entry["contract_gap_ids"]
        ]
        self.assertTrue(gap02_entries)
        for entry in gap02_entries:
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "requires_contract_amendment")
                self.assertTrue(
                    all(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        for target in entry["target_paths"]
                    )
                )

    def test_rg10_gap05_uses_current_catalog_owners_and_exact_gap_boundary(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-10.json").read_text(
                encoding="utf-8"
            )
        )
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-10-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        gaps = {gap["id"]: gap for gap in path_map["unresolved_contract_gaps"]}
        accounts = source["catalog"]["accounts"]
        categories = source["catalog"]["categories"]

        case_ledger_id = source["case"]["ledger_id"]
        self.assertTrue(accounts)
        self.assertEqual(
            {account["ledger_id"] for account in accounts},
            {case_ledger_id},
        )
        for account in accounts:
            with self.subTest(account_id=account["id"], fact="financial"):
                self.assertEqual(
                    account["financial"],
                    account["type"] in {"asset", "liability"},
                )

        accounts_by_id = {account["id"]: account for account in accounts}
        self.assertTrue(categories)
        for category in categories:
            posting_account = accounts_by_id[category["account_id"]]
            with self.subTest(category_id=category["id"], fact="kind"):
                self.assertEqual(category["kind"], posting_account["type"])
            with self.subTest(category_id=category["id"], fact="parent_identity"):
                self.assertIn("level", category)
                self.assertNotIn("parent_id", category)

        fixture_roles = {
            account["system_role"]
            for account in accounts
            if "system_role" in account
        }
        current_role_enum = set(
            schema["$defs"]["account"]["properties"]["system_role"]["enum"]
        )
        self.assertEqual(len(fixture_roles), 3)
        self.assertTrue(all(role.startswith("stored_value_") for role in fixture_roles))
        self.assertTrue(fixture_roles.issubset(current_role_enum))

        ready_targets = {
            "$.case.precision": {"$.case.currencies[*].precision"},
            "$.catalog.accounts[*].ledger_id": {"$.case.ledger_id"},
            "$.catalog.accounts[*].type": {
                "$.states[*].catalog.accounts[*].kind"
            },
            "$.catalog.accounts[*].financial": {
                "$.states[*].catalog.accounts[*].real_account"
            },
            "$.catalog.categories[*].account_id": {
                "$.states[*].catalog.categories[*].posting_account_id"
            },
            "$.catalog.categories[*].kind": {
                "$.states[*].catalog.accounts[*].kind",
                "$.states[*].catalog.categories[*].posting_account_id",
            },
        }
        expected_ready_entries = {
            source_path: {
                "disposition": "ready",
                "target_paths": sorted(targets),
                "contract_gap_ids": [],
            }
            for source_path, targets in ready_targets.items()
        }
        actual_ready_entries = {
            source_path: {
                "disposition": entries[source_path]["disposition"],
                "target_paths": sorted(entries[source_path]["target_paths"]),
                "contract_gap_ids": entries[source_path]["contract_gap_ids"],
            }
            for source_path in ready_targets
        }

        new_account_targets = {
            "$.catalog.accounts[*].enabled": {
                "$.states[*].catalog.accounts[*].stored_value.enabled"
            },
            "$.catalog.accounts[*].merchant_id": {
                "$.states[*].catalog.accounts[*].stored_value.merchant_id"
            },
            "$.catalog.accounts[*].restricted": {
                "$.states[*].catalog.accounts[*].stored_value.merchant_restricted"
            },
            "$.catalog.accounts[*].stored_value": {
                "$.states[*].catalog.accounts[*].stored_value"
            },
            "$.catalog.accounts[*].system_role": {
                "$.states[*].catalog.accounts[*].system_role"
            },
        }
        expected_gap_paths = {"$.catalog.categories[*].level"}
        self.maxDiff = None
        with self.subTest(boundary="current owners are ready"):
            self.assertEqual(actual_ready_entries, expected_ready_entries)
        with self.subTest(boundary="exact affected paths"):
            self.assertEqual(
                gaps["RG10-GAP-05"]["affected_source_paths"],
                sorted(expected_gap_paths),
            )

        for source_path, target_paths in new_account_targets.items():
            entry = entries[source_path]
            with self.subTest(source_path=source_path, boundary="new owner"):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(set(entry["target_paths"]), target_paths)
                self.assertEqual(entry["contract_gap_ids"], [])

        for source_path in expected_gap_paths:
            entry = entries[source_path]
            with self.subTest(source_path=source_path, boundary="gap membership"):
                self.assertEqual(
                    entry["disposition"], "requires_contract_amendment"
                )
                self.assertEqual(entry["contract_gap_ids"], ["RG10-GAP-05"])
                self.assertTrue(
                    all(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        for target in entry["target_paths"]
                    )
                )

    def test_rg10_reconstruction_identity_owners_are_current_but_replay_stays_gated(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-10-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        gaps = {gap["id"]: gap for gap in path_map["unresolved_contract_gaps"]}
        prefix = "$.secondary_cases.activation_boundary.expected.replacement_semantics"

        expected_ready = {
            f"{prefix}.adjustment_id": {
                "$.states[*].domain_entities[*].payload.adjustment_id"
            },
            f"{prefix}.replacement_group_id": {
                "$.states[*].domain_entities[*].id"
            },
        }
        for source_path, targets in expected_ready.items():
            entry = entries[source_path]
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(set(entry["target_paths"]), targets)
                self.assertEqual(entry["contract_gap_ids"], [])
                self.assertIn("stored_value_reconstruction", entry["transform"])

        replay_gated = {
            f"{prefix}.active_effect_rule",
            f"{prefix}.full_reconstruction_algorithm",
            f"{prefix}.mode",
            f"{prefix}.preserve_adjustment_transaction_and_version",
        }
        gap_paths = set(gaps["RG10-GAP-04"]["affected_source_paths"])
        self.assertTrue(replay_gated.issubset(gap_paths))
        self.assertEqual(len(gap_paths), 137)
        for source_path in replay_gated:
            entry = entries[source_path]
            with self.subTest(source_path=source_path, boundary="operation gap"):
                self.assertEqual(
                    entry["disposition"], "requires_contract_amendment"
                )
                self.assertEqual(entry["contract_gap_ids"], ["RG10-GAP-04"])
                self.assertTrue(
                    all(
                        target.startswith(PLANNED_CONTRACT_PREFIX)
                        for target in entry["target_paths"]
                    )
                )

    def test_rg10_bonus_expiry_roles_close_topology_but_not_verification_status(self):
        path_map = json.loads(
            (ROOT / "docs" / "migrations" / "golden-v2" / "rg-10-path-map.json").read_text(
                encoding="utf-8"
            )
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        gap = next(
            item for item in path_map["unresolved_contract_gaps"] if item["id"] == "RG10-GAP-06"
        )
        prefixes = (
            "$.canonical_states.recharge_confirmed.evidence_links[*]",
            "$.canonical_states.spend_confirmed.evidence_links[*]",
            "$.canonical_states.expiry_confirmed.evidence_links[*]",
        )
        status_paths = {f"{prefix}.status" for prefix in prefixes}
        self.assertEqual(set(gap["affected_source_paths"]), status_paths)
        self.assertEqual(len(status_paths), 3)

        for prefix in prefixes:
            for field in ("id", "lot_id", "role", "target_id"):
                entry = entries[f"{prefix}.{field}"]
                with self.subTest(prefix=prefix, field=field):
                    self.assertEqual(entry["disposition"], "ready")
                    self.assertEqual(entry["contract_gap_ids"], [])
                    self.assertFalse(
                        any(target.startswith(PLANNED_CONTRACT_PREFIX) for target in entry["target_paths"])
                    )
            status = entries[f"{prefix}.status"]
            self.assertEqual(status["contract_gap_ids"], ["RG10-GAP-06"])
            self.assertEqual(status["disposition"], "requires_contract_amendment")

        bonus_amount_paths = {
            entry["source_path"]
            for entry in path_map["entries"]
            if entry["source_path"].endswith(".bonus_amount")
            and entry["target_paths"] == ["$.states[*].domain_entities[*].payload.amount"]
        }
        self.assertEqual(len(bonus_amount_paths), 7)
        self.assertTrue(all(entries[path]["disposition"] == "ready" for path in bonus_amount_paths))
        gaps = {item["id"]: item for item in path_map["unresolved_contract_gaps"]}
        self.assertEqual(len(gaps["RG10-GAP-02"]["affected_source_paths"]), 71)
        self.assertEqual(len(gaps["RG10-GAP-04"]["affected_source_paths"]), 137)
        self.assertEqual(path_map["disposition_counts"]["ready"], 452)
        self.assertEqual(path_map["disposition_counts"]["requires_contract_amendment"], 708)

    def test_rg10_operation_registry_only_closes_structural_operation_paths(self):
        path_map = json.loads(
            (ROOT / "docs" / "migrations" / "golden-v2" / "rg-10-path-map.json").read_text(
                encoding="utf-8"
            )
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        gap = next(
            item for item in path_map["unresolved_contract_gaps"] if item["id"] == "RG10-GAP-01"
        )

        operation_type_paths = {
            "$.main_path.expiry_confirmation.operation_context.operation_type",
            "$.main_path.expiry_reminder.operation_context.operation_type",
            "$.main_path.recharge.operation_context.operation_type",
            "$.main_path.spend.operation_context.operation_type",
            "$.reconciliation_path.bank_evidence.operation_context.operation_type",
            "$.reconciliation_path.merchant_evidence.operation_context.operation_type",
            "$.secondary_cases.activation_boundary.operation_context.operation_type",
            "$.secondary_cases.merchant_evidenced_allocation.operation_context.operation_type",
        }
        for source_path in operation_type_paths:
            entry = entries[source_path]
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                self.assertEqual(
                    entry["target_paths"],
                    [
                        "$.operations[*].action_type",
                        "$.operations[*].operation_class",
                    ],
                )

        expected_targets = {
            "$.main_path.recharge.input.request_id": "$.operations[*].input.request_id",
            "$.import_path.incomplete_confirmations[*].input.amount": (
                "$.operations[*].attempted_input.amount"
            ),
            "$.import_path.complete_unconfirmed[*].input.lot_allocations[*].lot_id": (
                "$.operations[*].input.lot_allocations[*].lot_id"
            ),
            "$.secondary_cases.merchant_evidenced_allocation.input.allocations[*].amount": (
                "$.operations[*].input.allocations[*].amount"
            ),
            "$.main_path.spend.expected.accepted": "$.operations[*].outcome.status",
        }
        for source_path, target in expected_targets.items():
            entry = entries[source_path]
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["disposition"], "ready")
                self.assertEqual(entry["contract_gap_ids"], [])
                self.assertIn(target, entry["target_paths"])
                self.assertFalse(
                    any(path.startswith(PLANNED_CONTRACT_PREFIX) for path in entry["target_paths"])
                )

        still_gated = {
            "$.idempotency.recharge_retry.input_id",
            "$.import_path.incomplete_confirmations[*].expected.reason",
            "$.invalid_inputs[*].expected.reason",
            "$.main_path.recharge.expected.resulting_state_id",
            "$.main_path.spend.expected.consumption[*].amount",
            "$.main_path.expiry_reminder.expected.status",
            "$.secondary_cases.merchant_evidenced_allocation.expected.consumptions[*].amount",
        }
        self.assertTrue(still_gated.issubset(set(gap["affected_source_paths"])))
        for source_path in {
            "$.import_path.incomplete_confirmations[*].expected.reason",
            "$.invalid_inputs[*].expected.reason",
        }:
            with self.subTest(source_path=source_path):
                self.assertEqual(
                    entries[source_path]["disposition"],
                    "requires_contract_amendment",
                )
                self.assertIn("RG10-GAP-01", entries[source_path]["contract_gap_ids"])
                self.assertIn(
                    "$.operations[*].outcome.reason_code",
                    entries[source_path]["target_paths"],
                )
        self.assertIn("14 unique", gap["capability_boundary"])
        self.assertIn("17 frozen cases", gap["capability_boundary"])
        self.assertIn("5 frozen cases", gap["required_change"])

        lossless_gated_inputs = {
            "$.import_path.incomplete_confirmations[*].input.actual_time": "$.operations[*].attempted_input.actual_time",
            "$.import_path.incomplete_confirmations[*].input.bank_payment_confirmed": "$.operations[*].attempted_input.bank_payment_confirmed",
            "$.import_path.incomplete_confirmations[*].input.category_confirmed": "$.operations[*].attempted_input.category_confirmed",
            "$.import_path.incomplete_confirmations[*].input.lot_allocation_confirmed": "$.operations[*].attempted_input.lot_allocation_confirmed",
            "$.import_path.incomplete_confirmations[*].input.merchant_credit_amount": "$.operations[*].attempted_input.merchant_credit_amount",
            "$.import_path.incomplete_confirmations[*].input.merchant_source_id": "$.operations[*].attempted_input.merchant_source_id",
            "$.import_path.incomplete_confirmations[*].input.model_confirmed": "$.operations[*].attempted_input.model_confirmed",
            "$.invalid_inputs[*].input.paid_bonus_composition": "$.operations[*].attempted_input.paid_bonus_composition",
        }
        for source_path, target_path in lossless_gated_inputs.items():
            entry = entries[source_path]
            with self.subTest(source_path=source_path, boundary="lossless gated input"):
                self.assertEqual(entry["disposition"], "requires_contract_amendment")
                self.assertIn("RG10-GAP-01", entry["contract_gap_ids"])
                self.assertIn(target_path, entry["target_paths"])
        self.assertEqual(len(gap["affected_source_paths"]), 387)
        self.assertEqual(path_map["disposition_counts"]["ready"], 452)
        self.assertEqual(path_map["disposition_counts"]["requires_contract_amendment"], 708)

    def test_rg09_fingerprint_paths_remain_gated_until_mandatory_generation(self):
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-09-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        gap = next(
            item
            for item in path_map["unresolved_contract_gaps"]
            if item["id"] == "RG09-GAP-02"
        )

        candidate_paths = {
            source_path
            for source_path in entries
            if source_path.endswith(".candidates[*].ledger_fingerprint")
        }
        self.assertTrue(candidate_paths)
        for source_path in candidate_paths:
            entry = entries[source_path]
            with self.subTest(source_path=source_path):
                self.assertEqual(entry["classification"], "derive")
                self.assertEqual(entry["disposition"], "requires_contract_amendment")
                self.assertEqual(
                    entry["target_paths"],
                    [
                        "$.planned_contract.states[*].candidates[*].payload."
                        "ledger_fingerprint"
                    ],
                )
                self.assertIn("RG09-GAP-02", entry["contract_gap_ids"])

        confirmation_fingerprint = entries[
            "$.main_path.confirmation.input.ledger_fingerprint"
        ]
        self.assertEqual(confirmation_fingerprint["classification"], "derive")
        self.assertEqual(
            confirmation_fingerprint["disposition"],
            "requires_contract_amendment",
        )
        self.assertEqual(
            confirmation_fingerprint["target_paths"],
            ["$.planned_contract.operations[*].input.ledger_fingerprint"],
        )
        self.assertEqual(
            confirmation_fingerprint["contract_gap_ids"], ["RG09-GAP-02"]
        )

        remaining = set(gap["affected_source_paths"])
        self.assertTrue(
            {
                "$.stale_preview.input.preview_ledger_fingerprint",
                "$.stale_preview.input.current_ledger_fingerprint",
                "$.stale_preview.expected.recomputed_replay_amount",
                "$.stale_preview.expected.recomputed_delta",
            }.issubset(remaining)
        )
        self.assertTrue(candidate_paths.issubset(remaining))
        self.assertIn(
            "$.main_path.confirmation.input.ledger_fingerprint", remaining
        )
        self.assertEqual(len(remaining), 68)

    def test_rg10_absence_dispatch_and_evidence_role_boundaries(self):
        source = json.loads(
            (ROOT / "golden" / "rules" / "rg-10.json").read_text(
                encoding="utf-8"
            )
        )
        values = normalized_leaf_values(source)
        path_map = json.loads(
            (
                ROOT
                / "docs"
                / "migrations"
                / "golden-v2"
                / "rg-10-path-map.json"
            ).read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}

        not_present_entries = [
            entries[source_path]
            for source_path, occurrences in values.items()
            if "not_present" in occurrences
        ]
        self.assertTrue(not_present_entries)
        for entry in not_present_entries:
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=entry["source_path"]):
                self.assertEqual(entry["disposition"], "ready")
                self.assertIn(
                    "$.states[*].postings[*].reconciliation_eligible",
                    entry["target_paths"],
                )
                self.assertNotIn(
                    "$.states[*].posting_reconciliations[*].status",
                    entry["target_paths"],
                )
                self.assertIn("canonical absence", semantic_text)
                self.assertIn("emit no posting_reconciliations record", semantic_text)

        mapping_markdown = (
            ROOT / "docs" / "migrations" / "golden-v2" / "rg-10-mapping.md"
        ).read_text(encoding="utf-8")
        registry_section = mapping_markdown.split(
            "## Structural Action Registry", 1
        )[1].split("## Unresolved Gaps", 1)[0]
        registry_rows = re.findall(
            r"^\|\s*([a-z0-9_]+)\s*\|\s*([^|]+?)\s*\|",
            registry_section,
            re.MULTILINE,
        )
        registry = {
            action_type: tuple(
                operation_class.strip().split(" / ")
            )
            for action_type, operation_class in registry_rows
            if action_type != "action_type"
        }
        self.assertEqual(len(registry), 13)
        self.assertTrue(
            {
                "confirm_imported_stored_value_spend",
                "ingest_stored_value_recharge_candidate",
                "ingest_stored_value_spend_candidate",
                "record_expiry_reminder",
                "confirm_stored_value_expiry_loss",
                "reconcile_merchant_credit",
                "reconcile_bank_payment",
                "apply_merchant_lot_allocation",
                "confirm_stored_value_activation_balance",
            }.issubset(registry)
        )
        expected_dispatch = {
            (action_type, operation_class)
            for action_type, operation_classes in registry.items()
            for operation_class in operation_classes
        }
        self.assertEqual(len(expected_dispatch), 17)

        pair_pattern = re.compile(
            r"action_type=([a-z0-9_]+)(?:,\s*operation_class=([a-z_]+)"
            r"|(?=[\s\S]*?operation_class=([a-z_]+)))"
        )
        entries_by_pair = defaultdict(list)
        for entry in path_map["entries"]:
            transform = entry["transform"].lower()
            for match in pair_pattern.finditer(transform):
                pair = (match.group(1), match.group(2) or match.group(3))
                entries_by_pair[pair].append(entry)

        actual_dispatch = set(entries_by_pair)
        self.assertEqual(actual_dispatch, expected_dispatch)
        for pair in expected_dispatch:
            pair_entries = entries_by_pair[pair]
            aggregate_targets = {
                target
                for entry in pair_entries
                for target in entry["target_paths"]
            }
            expected_branch = (
                "attempted_input" if pair[1] == "rejection" else "input"
            )
            unexpected_branch = (
                "input" if expected_branch == "attempted_input" else "attempted_input"
            )
            with self.subTest(action_type=pair[0], operation_class=pair[1]):
                self.assertIn(
                    "$.operations[*].action_type",
                    aggregate_targets,
                )
                self.assertIn(
                    "$.operations[*].operation_class", aggregate_targets
                )
                self.assertTrue(
                    any(
                        f".operations[*].{expected_branch}." in target
                        for target in aggregate_targets
                    )
                )
                self.assertFalse(
                    any(
                        f".operations[*].{unexpected_branch}." in target
                        for target in aggregate_targets
                    )
                )

        retry_entries = [
            entry
            for entry in path_map["entries"]
            if entry["source_path"].startswith("$.idempotency.")
            and "retain originating action_type=" in entry["transform"].lower()
        ]
        self.assertTrue(retry_entries)
        retry_pairs = set()
        for entry in retry_entries:
            retry_text = entry["transform"].lower()
            retry_matches = list(pair_pattern.finditer(retry_text))
            self.assertTrue(retry_matches)
            retry_pairs.update(
                (match.group(1), match.group(2) or match.group(3))
                for match in retry_matches
            )
            with self.subTest(source_path=entry["source_path"]):
                self.assertIn(
                    "$.planned_contract.operations[*].action_type",
                    entry["target_paths"],
                )
                self.assertIn(
                    "$.operations[*].operation_class", entry["target_paths"]
                )
                self.assertIn("retain originating action_type", retry_text)
                self.assertIn("no generic retry action", retry_text)
                self.assertNotIn("action_type=retry", retry_text)
        self.assertTrue(retry_pairs.issubset(expected_dispatch))

        role_entries = []
        for source_path, occurrences in values.items():
            if source_path.endswith(".role") and ".evidence_links[*]." in source_path:
                role_entries.append((entries[source_path], set(occurrences)))
        self.assertTrue(role_entries)
        typed_link_targets = {
            "$.states[*].evidence_links[*].target_kind",
            "$.states[*].evidence_links[*].target_id",
            "$.states[*].evidence_links[*].role",
        }
        for entry, roles in role_entries:
            targets = set(entry["target_paths"])
            semantic_text = f"{entry['transform']} {entry['rationale']}".lower()
            with self.subTest(source_path=entry["source_path"]):
                self.assertTrue(typed_link_targets.issubset(targets))
                if "stored_value_activation_balance_fact" in roles:
                    self.assertIn("target_kind=domain_entity", semantic_text)
                    self.assertIn(
                        "role=stored_value_activation_balance_fact", semantic_text
                    )
                    self.assertIn("never target the transaction or a posting", semantic_text)
                if "stored_value_credit_lot" in roles:
                    self.assertIn("two independently identified links", semantic_text)
                    self.assertIn("role=stored_value_asset_posting", semantic_text)
                    self.assertIn("role=stored_value_lot_fact", semantic_text)
                if roles.intersection(
                    {
                        "stored_value_bonus_component",
                        "stored_value_expiry_confirmation",
                    }
                ):
                    self.assertEqual(entry["disposition"], "ready")
                    self.assertEqual(entry["contract_gap_ids"], [])
                    self.assertIn("target_kind=domain_entity", semantic_text)
                    self.assertIn(
                        "never generate another stored_value_lot_fact link",
                        semantic_text,
                    )

    def test_contract_gap_references_are_bidirectionally_closed(self):
        for case_id in EXPECTED_INVENTORIES:
            suffix = case_id[-2:]
            with self.subTest(case_id=case_id):
                path_map = json.loads(
                    (
                        ROOT
                        / "docs"
                        / "migrations"
                        / "golden-v2"
                        / f"rg-{suffix}-path-map.json"
                    ).read_text(encoding="utf-8")
                )
                entries = path_map["entries"]
                gaps = path_map["unresolved_contract_gaps"]
                gaps_by_id = {gap["id"]: gap for gap in gaps}
                if gaps:
                    self.assertEqual(path_map["status"], "needs_contract_amendment")
                    self.assertEqual(path_map["expected_output_gate"], "closed")
                elif case_id == "RG-02":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(path_map["expected_output_gate"], "completed")
                elif case_id == "RG-03":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(path_map["expected_output_gate"], "completed")
                elif case_id == "RG-04":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(path_map["expected_output_gate"], "closed")
                elif case_id == "RG-05":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(
                        path_map["expected_output_gate"], "draft_for_review"
                    )
                elif case_id == "RG-06":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(
                        path_map["expected_output_gate"], "approved"
                    )
                elif case_id == "RG-07":
                    self.assertEqual(path_map["status"], "approved")
                    self.assertEqual(path_map["expected_output_gate"], "approved")
                else:
                    self.assertEqual(path_map["status"], "completed")
                    self.assertEqual(path_map["expected_output_gate"], "completed")
                self.assertEqual(path_map["contract_gap_count"], len(gaps))
                self.assertEqual(len(gaps_by_id), len(gaps))

                referenced_paths = defaultdict(set)
                for entry in entries:
                    gap_ids = entry["contract_gap_ids"]
                    self.assertEqual(gap_ids, sorted(set(gap_ids)))
                    for gap_id in gap_ids:
                        self.assertIn(gap_id, gaps_by_id)
                        referenced_paths[gap_id].add(entry["source_path"])
                    if entry["disposition"] == "requires_contract_amendment":
                        self.assertTrue(gap_ids, entry["source_path"])
                    else:
                        self.assertFalse(gap_ids, entry["source_path"])

                for gap_id, gap in gaps_by_id.items():
                    self.assertEqual(gap["status"], "unresolved")
                    for field in (
                        "title",
                        "capability_boundary",
                        "required_change",
                        "risk",
                    ):
                        self.assertIsInstance(gap[field], str)
                        self.assertTrue(gap[field].strip(), (gap_id, field))
                    self.assertEqual(
                        gap["affected_source_paths"],
                        sorted(referenced_paths[gap_id]),
                    )
                    self.assertTrue(gap["affected_source_paths"], gap_id)

    def test_rg05_time_and_rejection_ownership_never_targets_formal_postings(self):
        path_map = json.loads(
            (ROOT / "docs" / "migrations" / "golden-v2" / "rg-05-path-map.json").read_text(encoding="utf-8")
        )
        entries = {entry["source_path"]: entry for entry in path_map["entries"]}
        self.assertEqual(
            entries["$.opening.transactions[*].occurred_at"]["target_paths"],
            [
                "$.states[*].transaction_versions[*].occurred_at",
                "$.states[*].transaction_versions[*].statistics_at",
                "$.states[*].transaction_versions[*].effective_at",
            ],
        )
        self.assertIn("opening-only", entries["$.opening.transactions[*].occurred_at"]["transform"].lower())
        self.assertNotIn("created_at", entries["$.opening.transactions[*].occurred_at"]["target_paths"])
        self.assertNotIn("confirmed_at", entries["$.opening.transactions[*].occurred_at"]["target_paths"])
        for source_path, reason in (
            ("$.allocation_failures[*].expected.allocation_gap_amount", "allocation_incomplete"),
            ("$.allocation_failures[*].expected.over_allocation_amount", "allocation_conflict"),
        ):
            entry = entries[source_path]
            self.assertEqual(
                entry["target_paths"],
                [
                    "$.operations[*].attempted_input.payment_total",
                    "$.operations[*].attempted_input.allocation_total",
                    "$.operations[*].outcome.reason_code",
                ],
            )
            self.assertFalse(any("posting" in target for target in entry["target_paths"]))
            self.assertIn(reason, entry["transform"])

    def test_rg05_category_consumption_paths_are_category_bound_state_ownership(self):
        path_map = json.loads(
            (ROOT / "docs" / "migrations" / "golden-v2" / "rg-05-path-map.json").read_text(encoding="utf-8")
        )
        entries = [
            entry for entry in path_map["entries"]
            if ".category_consumption." in entry["source_path"]
        ]
        self.assertEqual(len(entries), 8)
        expected_targets = {
            "$.states[*].postings[*].category_id",
            "$.states[*].postings[*].amount",
            "$.states[*].domain_entities[*].payload.category_id",
            "$.states[*].domain_entities[*].payload.amount",
        }
        for entry in entries:
            self.assertEqual(set(entry["target_paths"]), expected_targets)
            self.assertNotIn("reports[*].metrics", " ".join(entry["target_paths"]))
            text = f"{entry['transform']} {entry['rationale']}".lower()
            self.assertIn("category-bound", text)
            self.assertIn("reports are derived", text)


if __name__ == "__main__":
    unittest.main()
