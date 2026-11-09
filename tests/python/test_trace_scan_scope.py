"""Offline proofs for the pull-request scope of the trace gate (D-302, T2).

The banned marker is assembled at runtime on purpose: a literal would itself be
a trace hit in this tracked file. Every case builds a throwaway git repository,
so no network, no Gradle and no emulator are involved.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "ci" / "trace-scan.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
SHARDS = ROOT / "tools" / "ci" / "python-shards.txt"

# Runtime-only marker: splitting it keeps this file clean for the tracked-file scan.
MARKER = "".join(["Deep", "Seek"])
BASH = shutil.which("bash")


def git(repo: Path, *args: str) -> str:
    env = os.environ.copy()
    env.update(
        {
            "GIT_AUTHOR_NAME": "Trace Test",
            "GIT_AUTHOR_EMAIL": "trace-test@example.invalid",
            "GIT_COMMITTER_NAME": "Trace Test",
            "GIT_COMMITTER_EMAIL": "trace-test@example.invalid",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_SYSTEM": os.devnull,
        }
    )
    completed = subprocess.run(
        ["git", *args], cwd=repo, env=env, check=True, capture_output=True, text=True
    )
    return completed.stdout.strip()


@unittest.skipUnless(BASH, "bash is required by the trace gate itself")
class TraceScanScope(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        self.legacy = self.commit(
            {"docs/legacy.txt": f"historical {MARKER}\n"}, "seed the history"
        )
        git(self.repo, "rm", "-q", "docs/legacy.txt")
        self.base = self.commit(
            {"docs/keep.md": "clean base\n"}, "drop the legacy file before the base"
        )

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def commit(self, files: dict[str, str], message: str) -> str:
        for relative, content in files.items():
            target = self.repo / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", message)
        return git(self.repo, "rev-parse", "HEAD")

    def scan(self, base: str | None) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.pop("TRACE_BASE_SHA", None)
        if base is not None:
            env["TRACE_BASE_SHA"] = base
        return subprocess.run(
            [BASH or "bash", str(SCRIPT)],
            cwd=self.repo,
            env=env,
            capture_output=True,
            text=True,
        )

    def test_full_scan_sees_the_seeded_history(self):
        completed = self.scan(None)
        self.assertEqual(completed.returncode, 1, completed.stderr)
        self.assertIn("docs/legacy.txt", completed.stderr)

    def test_scoped_scan_ignores_history_before_the_base(self):
        self.commit({"docs/next.md": "clean change\n"}, "add a clean note")
        completed = self.scan(self.base)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn(self.base, completed.stderr)  # scope was announced

    def test_scoped_scan_catches_a_new_commit_message(self):
        self.commit({"docs/next.md": "clean change\n"}, f"add notes about {MARKER}")
        completed = self.scan(self.base)
        self.assertEqual(completed.returncode, 1, completed.stderr)
        self.assertIn("Git history messages", completed.stderr)

    def test_scoped_scan_catches_a_new_file(self):
        self.commit({"app/src/notes.txt": f"current {MARKER}\n"}, "add a note")
        completed = self.scan(self.base)
        self.assertEqual(completed.returncode, 1, completed.stderr)
        self.assertIn("tracked paths", completed.stderr)

    def test_unresolvable_base_is_fatal(self):
        completed = self.scan("0" * 40)
        self.assertEqual(completed.returncode, 2, completed.stderr)
        self.assertIn("TRACE_BASE_SHA", completed.stderr)


class WorkflowWiring(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")
        self.lines = self.text.splitlines()

    def job_block(self, job: str) -> str:
        start = self.lines.index(f"  {job}:")
        block = [self.lines[start]]
        for line in self.lines[start + 1 :]:
            if line and not line.startswith("    "):
                break
            block.append(line)
        return "\n".join(block)

    def test_trace_job_passes_the_pull_request_base(self):
        block = self.job_block("trace")
        self.assertIn("TRACE_BASE_SHA: ${{ github.event.pull_request.base.sha }}", block)
        self.assertIn("bash tools/ci/trace-scan.sh", block)

    def test_trace_job_keeps_full_history_checkout(self):
        block = self.job_block("trace")
        self.assertIn("fetch-depth: 0", block)


class ShardManifest(unittest.TestCase):
    def test_this_module_is_in_its_shard(self):
        self.assertIn(
            "tests.python.test_trace_scan_scope", SHARDS.read_text(encoding="utf-8")
        )


if __name__ == "__main__":
    unittest.main()
