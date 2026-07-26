from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from project_docs.validator import (
    FORMAL_DOCUMENTS,
    TEST_EVIDENCE_CLAIM,
    TEST_EVIDENCE_CLEAN_CLAIM,
    TEST_EVIDENCE_MODULES,
    validate_formal_docs,
    validate_test_evidence,
)


def build_minimal_document_tree(root: Path) -> Path:
    for relative_path in FORMAL_DOCUMENTS:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Test\n", encoding="utf-8")
    (root / "docs" / "DECISIONS.md").write_text(
        "# Decisions\n\n## D-001 First\n", encoding="utf-8"
    )
    return root


def write_jvm_test_report(
    root: Path, module: str, *, tests: int, failures: int = 0, errors: int = 0, skipped: int = 0
) -> None:
    reports = root / module / "build" / "test-results" / "jvmTest"
    reports.mkdir(parents=True, exist_ok=True)
    (reports / f"TEST-{module}.xml").write_text(
        f'<testsuite name="{module}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"></testsuite>',
        encoding="utf-8",
    )


def write_test_evidence(root: Path, counts: dict[str, int]) -> None:
    recorded = "、".join(f"`{module}` {count} 项" for module, count in counts.items())
    (root / "docs" / "CURRENT_STATE.md").write_text(
        f"# Current state\n\n最新保存的报告：{recorded}，均为零 failure、零 error。\n",
        encoding="utf-8",
    )


class ProjectDocsValidatorTests(unittest.TestCase):
    def test_reports_missing_required_documents(self):
        with TemporaryDirectory() as directory:
            issues = validate_formal_docs(Path(directory))
        self.assertTrue(any(issue.code == "missing-document" for issue in issues))

    def test_reports_duplicate_decision_ids(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            (root / "docs" / "DECISIONS.md").write_text(
                "## D-001 A\n## D-001 B\n", encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertEqual(["duplicate-decision-id"], [i.code for i in issues])

    def test_reports_malformed_decision_ids(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            (root / "docs" / "DECISIONS.md").write_text(
                "## D-01 Invalid\n", encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertEqual(["invalid-decision-id"], [i.code for i in issues])

    def test_reports_external_reference_names(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            external_name = "Tal" + "ly"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                external_name, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertEqual(["prohibited-reference"], [i.code for i in issues])

    def test_reports_development_assistant_traces(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            assistant_name = "Code" + "x"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                assistant_name, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertEqual(["assistant-trace"], [i.code for i in issues])

    def test_reports_broken_relative_links(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            (root / "README.md").write_text(
                "[missing](docs/MISSING.md)\n", encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "broken-link" for issue in issues))

    def test_reports_relative_links_outside_repository(self):
        with TemporaryDirectory() as directory:
            base = Path(directory)
            root = build_minimal_document_tree(base / "repo")
            (base / "private.md").write_text("private\n", encoding="utf-8")
            (root / "README.md").write_text(
                "[outside](../private.md)\n", encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "outside-root-link" for issue in issues))

    def test_accepts_uppercase_external_url_scheme(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            (root / "README.md").write_text(
                "[external](HTTPS://example.invalid/docs)\n", encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertEqual([], issues)

    def test_reports_machine_absolute_paths(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            private_path = "C:" + "\\" + "Users" + "\\" + "example"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                private_path, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "absolute-path" for issue in issues))

    def test_reports_unc_machine_paths(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            private_path = "\\" + "\\" + "server" + "\\" + "share"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                private_path, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "absolute-path" for issue in issues))

    def test_reports_home_shorthand_paths(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            private_path = "~" + "/private/file"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                private_path, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "absolute-path" for issue in issues))

    def test_reports_unix_home_without_trailing_slash(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            private_path = "/" + "home" + "/example"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                private_path, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "absolute-path" for issue in issues))

    def test_reports_unfinished_markers(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            marker = "TO" + "DO"
            (root / "docs" / "CURRENT_STATE.md").write_text(
                marker, encoding="utf-8"
            )
            issues = validate_formal_docs(root)
        self.assertTrue(any(issue.code == "unfinished-marker" for issue in issues))

    def test_accepts_a_complete_minimal_tree(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            issues = validate_formal_docs(root)
        self.assertEqual([], issues)

    def test_accepts_recorded_test_counts_that_match_the_saved_reports(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            for module in TEST_EVIDENCE_MODULES:
                write_jvm_test_report(root, module, tests=7)
            write_test_evidence(root, {module: 7 for module in TEST_EVIDENCE_MODULES})
            issues = validate_test_evidence(root)
        self.assertEqual([], issues)

    def test_reports_recorded_test_counts_that_drifted_from_the_saved_reports(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            for module in TEST_EVIDENCE_MODULES:
                write_jvm_test_report(root, module, tests=7)
            counts = {module: 7 for module in TEST_EVIDENCE_MODULES}
            counts["ledger-data"] = 6
            write_test_evidence(root, counts)
            issues = validate_test_evidence(root)
        self.assertEqual(["stale-test-evidence"], [i.code for i in issues])

    def test_reports_a_clean_claim_contradicted_by_the_saved_reports(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            for module in TEST_EVIDENCE_MODULES:
                write_jvm_test_report(root, module, tests=7)
            write_jvm_test_report(root, "ledger-domain", tests=7, failures=1)
            write_test_evidence(root, {module: 7 for module in TEST_EVIDENCE_MODULES})
            issues = validate_test_evidence(root)
        self.assertEqual(["stale-test-evidence"], [i.code for i in issues])

    def test_reports_absent_reports_instead_of_passing_silently(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            write_test_evidence(root, {module: 7 for module in TEST_EVIDENCE_MODULES})
            issues = validate_test_evidence(root)
        self.assertEqual(
            ["stale-test-evidence"] * len(TEST_EVIDENCE_MODULES), [i.code for i in issues]
        )

    def test_reports_unreadable_reports_instead_of_skipping_the_module(self):
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            for module in TEST_EVIDENCE_MODULES:
                write_jvm_test_report(root, module, tests=7)
            broken = root / "ledger-data" / "build" / "test-results" / "jvmTest" / "TEST-broken.xml"
            broken.write_text("<testsuite", encoding="utf-8")
            write_test_evidence(root, {module: 7 for module in TEST_EVIDENCE_MODULES})
            issues = validate_test_evidence(root)
        self.assertEqual(["stale-test-evidence"], [i.code for i in issues])

    def test_reports_evidence_wording_it_cannot_parse(self):
        for wording in ("`{module}` 共 {count} 项", "{module} {count} 项", "`{module}` {count} 个"):
            with TemporaryDirectory() as directory:
                root = build_minimal_document_tree(Path(directory))
                for module in TEST_EVIDENCE_MODULES:
                    write_jvm_test_report(root, module, tests=7)
                recorded = "、".join(
                    wording.format(module=module, count=7) for module in TEST_EVIDENCE_MODULES
                )
                (root / "docs" / "CURRENT_STATE.md").write_text(
                    f"# Current state\n\n{recorded}\n",
                    encoding="utf-8",
                )
                issues = validate_test_evidence(root)
            self.assertEqual(
                ["test-evidence-unparsable"], [i.code for i in issues], f"wording: {wording}"
            )

    def test_the_real_current_state_document_records_every_module(self):
        """The check is worthless if the wording it parses drifts away from the real document."""
        repository_root = Path(__file__).resolve().parents[2]
        text = (repository_root / "docs" / "CURRENT_STATE.md").read_text(encoding="utf-8")
        claims = dict(TEST_EVIDENCE_CLAIM.findall(text))
        self.assertEqual(sorted(TEST_EVIDENCE_MODULES), sorted(claims))
        self.assertIn(TEST_EVIDENCE_CLEAN_CLAIM, text)

    def test_formal_document_validation_ignores_test_evidence(self):
        """Focused test runs leave partial reports; routine validation must stay green."""
        with TemporaryDirectory() as directory:
            root = build_minimal_document_tree(Path(directory))
            write_jvm_test_report(root, "ledger-domain", tests=1)
            write_test_evidence(root, {module: 999 for module in TEST_EVIDENCE_MODULES})
            self.assertEqual([], validate_formal_docs(root))
            self.assertNotEqual([], validate_test_evidence(root))


if __name__ == "__main__":
    unittest.main()
