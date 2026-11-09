"""Offline proofs for the docs-only fast path of the PR gate (D-302, T1).

Everything here is static or local: the classifier's path rules, its behaviour
on a real temporary git repository, and the workflow wiring that consumes it.
No network, no Gradle, no emulator.
"""
from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLASSIFIER = ROOT / "tools" / "ci" / "classify-changes.py"
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
SHARDS = ROOT / "tools" / "ci" / "python-shards.txt"

SKIP_IF = "if: needs.changes.outputs.kind == 'code'"


def load_classifier():
    spec = importlib.util.spec_from_file_location("classify_changes", CLASSIFIER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def git(repo: Path, *args: str) -> str:
    env = os.environ.copy()
    env.update(
        {
            "GIT_AUTHOR_NAME": "CI Test",
            "GIT_AUTHOR_EMAIL": "ci-test@example.invalid",
            "GIT_COMMITTER_NAME": "CI Test",
            "GIT_COMMITTER_EMAIL": "ci-test@example.invalid",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
        }
    )
    completed = subprocess.run(
        ["git", *args], cwd=repo, env=env, check=True, capture_output=True, text=True
    )
    return completed.stdout.strip()


def commit_all(repo: Path, message: str) -> str:
    git(repo, "add", "-A")
    git(repo, "commit", "-m", message)
    return git(repo, "rev-parse", "HEAD")


class PathRules(unittest.TestCase):
    def setUp(self) -> None:
        self.classify_changes = load_classifier()

    def test_docs_tree_is_docs(self):
        for path in ("docs/a.md", "docs/sub/b.md", "docs/specs/c.md", "./docs/d.md"):
            self.assertTrue(self.classify_changes.is_docs_path(path), path)

    def test_root_markdown_is_docs(self):
        for path in ("README.md", "NOTICE.md"):
            self.assertTrue(self.classify_changes.is_docs_path(path), path)

    def test_everything_else_is_code(self):
        for path in (
            "README",
            "build.gradle.kts",
            "src/notes.md",
            "docsx/notes.md",
            "docs",
            ".github/workflows/ci.yml",
            "tools/ci/classify-changes.py",
            "android-app/src/main/AndroidManifest.xml",
            # Non-Markdown files under docs/ are test inputs, not documentation.
            "docs/migrations/golden-v2/rg-01-expected.json",
            "docs/examples/golden-schema-v2/state.json",
            "docs/sub/spec.yml",
        ):
            self.assertFalse(self.classify_changes.is_docs_path(path), path)

    def test_classify_path_lists(self):
        classify = self.classify_changes.classify
        self.assertEqual(classify(["docs/a.md", "README.md"]), "docs")
        self.assertEqual(classify(["docs/a.md", "app-ui/src/Foo.kt"]), "code")
        self.assertEqual(classify(["docs/migrations/golden-v2/rg-01-expected.json"]), "code")
        self.assertEqual(classify([]), "code")
        self.assertEqual(classify([".github/workflows/ci.yml"]), "code")


class RealRepository(unittest.TestCase):
    def setUp(self) -> None:
        self.classify_changes = load_classifier()
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        (self.repo / "docs").mkdir()
        (self.repo / "docs" / "seed.md").write_text("seed\n", encoding="utf-8")
        self.base = commit_all(self.repo, "seed")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_classifier(self, base: str) -> str:
        completed = subprocess.run(
            [sys.executable, str(CLASSIFIER), "--base", base, "--head", "HEAD"],
            cwd=self.repo,
            check=True,
            capture_output=True,
            text=True,
        )
        return completed.stdout.strip().splitlines()[-1]

    def test_docs_only_change(self):
        (self.repo / "docs" / "next.md").write_text("next\n", encoding="utf-8")
        (self.repo / "README.md").write_text("readme\n", encoding="utf-8")
        commit_all(self.repo, "docs")
        self.assertEqual(self.run_classifier(self.base), "docs")

    def test_code_change(self):
        (self.repo / "docs" / "next.md").write_text("next\n", encoding="utf-8")
        (self.repo / "build.gradle.kts").write_text("// build\n", encoding="utf-8")
        commit_all(self.repo, "code")
        self.assertEqual(self.run_classifier(self.base), "code")

    def test_rename_out_of_docs_is_code(self):
        (self.repo / "docs" / "moved.md").write_text("moved\n", encoding="utf-8")
        base = commit_all(self.repo, "seed the moved file")
        # A pure documentation commit is still docs.
        self.assertEqual(self.run_classifier(self.base), "docs")
        (self.repo / "src").mkdir()
        git(self.repo, "mv", "docs/moved.md", "src/moved.md")
        commit_all(self.repo, "move the file out of docs")
        # --no-renames decomposes this into a delete plus a code add, so the
        # change can never hide behind the deleted documentation path.
        self.assertEqual(self.run_classifier(base), "code")

    def test_empty_diff_is_code(self):
        head = git(self.repo, "rev-parse", "HEAD")
        self.assertEqual(self.run_classifier(head), "code")

    def test_unreadable_base_is_code(self):
        self.assertEqual(self.run_classifier("0" * 40), "code")


class WorkflowWiring(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")
        self.lines = self.text.splitlines()

    def job_block(self, job: str) -> list[str]:
        start = self.lines.index(f"  {job}:")
        block = [self.lines[start]]
        for line in self.lines[start + 1 :]:
            if line and not line.startswith("    "):
                break
            block.append(line)
        return block

    def test_classification_job_publishes_kind(self):
        block = self.job_block("changes")
        joined = "\n".join(block)
        self.assertIn("kind: ${{ steps.classify.outputs.kind }}", joined)
        self.assertIn("tools/ci/classify-changes.py", joined)

    def test_heavy_jobs_run_only_for_code(self):
        for job in ("kotlin-core", "ledger-data-shards", "android"):
            block = "\n".join(self.job_block(job))
            self.assertIn("needs: [changes]", block, job)
            self.assertIn(SKIP_IF, block, job)

    def test_verification_that_must_always_run_is_not_skipped(self):
        for job in ("python-shards", "trace"):
            block = "\n".join(self.job_block(job))
            self.assertNotIn(SKIP_IF, block, job)
            self.assertNotIn("needs: [changes]", block, job)

    def test_kotlin_gate_recomputes_and_judges_the_declared_kind(self):
        block = "\n".join(self.job_block("kotlin-gate"))
        self.assertIn(
            "needs: [changes, kotlin-core, ledger-data-shards, kotlin-migration-verify]", block
        )
        # D-303 batch E: the extracted migration job is judged like the other two.
        self.assertIn('MIGRATION_RESULT" != "skipped"', block)
        self.assertIn('MIGRATION_RESULT" != "success"', block)

    def test_the_migration_verification_runs_only_in_its_own_job(self):
        core = "\n".join(self.job_block("kotlin-core"))
        invocations = [
            line for line in core.splitlines()
            if line.lstrip().startswith("run:")
            and ":ledger-data:verifyCommonMainLedgerDatabaseMigration" in line
            and " -x :ledger-data:verifyCommonMainLedgerDatabaseMigration" not in line
        ]
        self.assertEqual(invocations, [])
        migration = "\n".join(self.job_block("kotlin-migration-verify"))
        self.assertIn(":ledger-data:verifyCommonMainLedgerDatabaseMigration", migration)

    def test_required_check_names_are_unchanged(self):
        for name in ("Kotlin tests", "Android compile", "Python tests", "Trace scan"):
            self.assertIn(f"    name: {name}", self.text, name)


class ShardManifest(unittest.TestCase):
    def test_this_module_is_in_its_shard(self):
        manifest = SHARDS.read_text(encoding="utf-8")
        self.assertIn("tests.python.test_ci_change_classifier", manifest)


if __name__ == "__main__":
    unittest.main()
