"""Offline proofs for the squash-evidence path of the merge-tree guard (D-302, T3).

The network seam is `verify_merge_tree.run_gh`; every test patches it, so no
`gh`, no API and no network are involved. The repository cases build throwaway
git repositories, and the workflow assertions read the guard file as text.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "ci" / "verify-merge-tree.py"
GUARD = ROOT / ".github" / "workflows" / "merge-tree-guard.yml"
SHARDS = ROOT / "tools" / "ci" / "python-shards.txt"

LANDED = "1" * 40
HEAD = "2" * 40
TREE = "3" * 40
OTHER_TREE = "4" * 40


def load_module():
    spec = importlib.util.spec_from_file_location("verify_merge_tree", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def git(repo: Path, *args: str) -> str:
    env = os.environ.copy()
    env.update(
        {
            "GIT_AUTHOR_NAME": "Guard Test",
            "GIT_AUTHOR_EMAIL": "guard-test@example.invalid",
            "GIT_COMMITTER_NAME": "Guard Test",
            "GIT_COMMITTER_EMAIL": "guard-test@example.invalid",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
        }
    )
    completed = subprocess.run(
        ["git", *args], cwd=repo, env=env, check=True, capture_output=True, text=True
    )
    return completed.stdout.strip()


def rollup(**overrides: str) -> list[dict]:
    checks = {
        "Kotlin tests": "SUCCESS",
        "Android compile": "SUCCESS",
        "Python tests": "SUCCESS",
        "Trace scan": "SUCCESS",
    }
    checks.update(overrides)
    return [{"name": name, "conclusion": value} for name, value in checks.items()]


class SquashEvidence(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_module()
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        (self.repo / "file.txt").write_text("one\n", encoding="utf-8")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", "seed")
        (self.repo / "file.txt").write_text("two\n", encoding="utf-8")
        git(self.repo, "commit", "-am", "feat: a change (#42)")
        self.landed = git(self.repo, "rev-parse", "HEAD")
        self.tree = git(self.repo, "rev-parse", "HEAD^{tree}")
        self.messages: list[str] = []

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def fake_gh(self, *, merged: bool = True, tree: str | None = None, checks=None,
                fail: bool = False, associated=None):
        payload = {
            "mergeCommit": {"oid": self.landed if merged else "9" * 40},
            "headRefOid": HEAD,
            "state": "MERGED",
            "statusCheckRollup": rollup() if checks is None else checks,
        }
        association = [] if associated is None else associated

        def run(*args: str):
            self.messages.append(" ".join(args))
            if fail:
                return 1, "", "boom"
            if args[:2] == ("api", f"repos/example/ledger/git/commits/{HEAD}"):
                return 0, self.tree if tree is None else tree, ""
            if args[:2] == ("api", f"repos/example/ledger/commits/{self.landed}/pulls"):
                return 0, json.dumps(association), ""
            return 0, json.dumps(payload), ""

        return run

    def decide(self, **kwargs) -> tuple[int, str]:
        with patch.object(self.module, "run_gh", side_effect=self.fake_gh(**kwargs)), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": "example/ledger"}), \
                patch.object(self.module, "run_git", side_effect=self._git_at_repo):
            return self.module.squash_evidence(self.landed)

    def _git_at_repo(self, *args: str) -> tuple[int, str, str]:
        completed = subprocess.run(
            ["git", *args], cwd=self.repo, capture_output=True, text=True, encoding="utf-8"
        )
        return completed.returncode, completed.stdout.strip(), completed.stderr.strip()

    def test_complete_evidence_is_zero_action(self):
        code, verdict = self.decide()
        self.assertEqual(code, 0, verdict)
        self.assertIn("zero-action", verdict)

    def test_skipped_required_check_still_counts(self):
        code, verdict = self.decide(checks=rollup(**{"Android compile": "SKIPPED"}))
        self.assertEqual(code, 0, verdict)

    def test_wrong_merge_identity_dispatches(self):
        code, verdict = self.decide(merged=False)
        self.assertEqual(code, 1)
        self.assertIn("merged as", verdict)

    def test_different_pr_head_tree_dispatches(self):
        code, verdict = self.decide(tree=OTHER_TREE)
        self.assertEqual(code, 1)
        self.assertIn("head tree", verdict)

    def test_missing_required_check_dispatches(self):
        checks = rollup()
        checks[0] = {"name": "Kotlin tests", "conclusion": "FAILURE"}
        code, verdict = self.decide(checks=checks)
        self.assertEqual(code, 1)
        self.assertIn("Kotlin tests=FAILURE", verdict)

    def test_absent_required_check_dispatches(self):
        code, verdict = self.decide(checks=rollup()[:2])
        self.assertEqual(code, 1)
        self.assertIn("absent", verdict)

    def test_unreadable_pull_request_dispatches(self):
        code, verdict = self.decide(fail=True)
        self.assertEqual(code, 1)
        self.assertIn("cannot read pull request", verdict)

    def test_subject_without_a_number_falls_back_to_the_association(self):
        git(self.repo, "commit", "--allow-empty", "-m", "chore: no number here")
        self.landed = git(self.repo, "rev-parse", "HEAD")
        association = [{"number": 77, "merge_commit_sha": self.landed,
                        "merged_at": "2026-11-09T00:00:00Z"}]
        code, verdict = self.decide(associated=association)
        self.assertEqual(code, 0, verdict)
        self.assertIn("#77", verdict)

    def test_ambiguous_association_dispatches(self):
        git(self.repo, "commit", "--allow-empty", "-m", "chore: no number here")
        self.landed = git(self.repo, "rev-parse", "HEAD")
        association = [
            {"number": 77, "merge_commit_sha": self.landed, "merged_at": "2026-11-09T00:00:00Z"},
            {"number": 78, "merge_commit_sha": self.landed, "merged_at": "2026-11-09T00:00:00Z"},
        ]
        code, verdict = self.decide(associated=association)
        self.assertEqual(code, 1)
        self.assertIn("2 merged pull requests", verdict)

    def test_association_pointing_elsewhere_dispatches(self):
        git(self.repo, "commit", "--allow-empty", "-m", "chore: no number here")
        self.landed = git(self.repo, "rev-parse", "HEAD")
        association = [{"number": 77, "merge_commit_sha": "9" * 40,
                        "merged_at": "2026-11-09T00:00:00Z"}]
        code, verdict = self.decide(associated=association)
        self.assertEqual(code, 1)
        self.assertIn("0 merged pull requests", verdict)

    def test_two_parent_commit_is_not_squash_evidence(self):
        git(self.repo, "checkout", "-q", "-b", "side")
        (self.repo / "side.txt").write_text("side\n", encoding="utf-8")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", "side")
        git(self.repo, "checkout", "-q", "main")
        git(self.repo, "merge", "-q", "--no-ff", "side", "-m", "merge side")
        self.landed = git(self.repo, "rev-parse", "HEAD")
        code, verdict = self.decide()
        self.assertEqual(code, 1)
        self.assertIn("one-parent", verdict)

    def test_missing_repository_slug_dispatches(self):
        with patch.object(self.module, "run_gh", side_effect=self.fake_gh()), \
                patch.object(self.module, "run_git", side_effect=self._git_at_repo), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": ""}):
            code, verdict = self.module.squash_evidence(self.landed)
        self.assertEqual(code, 1)
        self.assertIn("GITHUB_REPOSITORY", verdict)


class CommandLine(unittest.TestCase):
    def test_squash_evidence_rejects_merge_arguments(self):
        module = load_module()
        with patch.object(module.sys, "argv", ["x", "--squash-evidence", "abc"]), \
                patch.object(module, "squash_evidence", return_value=(0, "ok")), \
                self.assertRaises(SystemExit) as raised:
            module.main()
        self.assertEqual(raised.exception.code, 2)

    def test_squash_evidence_passes_the_landed_commit(self):
        module = load_module()
        with patch.object(module.sys, "argv", ["x", "--squash-evidence", "--landed", "abc"]), \
                patch.object(module, "squash_evidence", return_value=(0, "ok")) as called:
            self.assertEqual(module.main(), 0)
        called.assert_called_once_with("abc")


class GuardWiring(unittest.TestCase):
    def setUp(self) -> None:
        self.text = GUARD.read_text(encoding="utf-8")

    def test_guard_checks_squash_evidence_before_dispatching(self):
        self.assertIn("--squash-evidence", self.text)
        self.assertIn("gh workflow run ci.yml --ref main", self.text)

    def test_zero_action_scans_the_landed_commit(self):
        block = self.text.split("TRACE_BASE_SHA=", 1)
        self.assertEqual(len(block), 2, "the zero-action path must scan the landed commit")
        self.assertIn("trace-scan.sh", block[1])

    def test_guard_can_read_pull_requests_and_checks(self):
        for scope in ("pull-requests: read", "checks: read", "statuses: read"):
            self.assertIn(scope, self.text, scope)


class ShardManifest(unittest.TestCase):
    def test_this_module_is_in_its_shard(self):
        self.assertIn(
            "tests.python.test_verify_merge_tree_squash", SHARDS.read_text(encoding="utf-8")
        )


if __name__ == "__main__":
    unittest.main()
