import json
import re
import unittest
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "schemas" / "golden-case-v2.schema.json"
EXPECTED_INVENTORIES = {
    "RG-02": (154, 368),
    "RG-03": (304, 560),
    "RG-04": (406, 863),
    "RG-05": (549, 1407),
    "RG-06": (1188, 3610),
    "RG-07": (2523, 6084),
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


class GoldenV2MappingTests(unittest.TestCase):
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

    def test_rg02_through_rg07_path_maps_close_over_source_inventories(self):
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
                        "$.planned_contract.states[*].domain_entities[*].payload."
                        f"state_history[*].{field}"
                    ],
                )

        self.assertTrue(top_level_business_entries)
        for entry in top_level_business_entries:
            field = entry["source_path"].split(".group.", 1)[1]
            with self.subTest(source_path=entry["source_path"]):
                self.assertIn(
                    "$.planned_contract.states[*].domain_entities[*].payload."
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

        action_target = "$.planned_contract.operations[*].action_type"
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
                self.assertEqual(path_map["status"], "needs_contract_amendment")
                self.assertEqual(path_map["expected_output_gate"], "closed")
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


if __name__ == "__main__":
    unittest.main()
