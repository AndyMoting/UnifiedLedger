from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from project_docs.validator import FORMAL_DOCUMENTS, validate_formal_docs


def build_minimal_document_tree(root: Path) -> Path:
    for relative_path in FORMAL_DOCUMENTS:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Test\n", encoding="utf-8")
    (root / "docs" / "DECISIONS.md").write_text(
        "# Decisions\n\n## D-001 First\n", encoding="utf-8"
    )
    return root


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


if __name__ == "__main__":
    unittest.main()
