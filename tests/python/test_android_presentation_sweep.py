"""Offline proofs for the D-369 presentation sweep driver and its single-line CI entry.

Why this module exists: the emulator action executes its `script:` input one line at a time, each
as its own `sh -c`, so a multi-line shell loop there never becomes one program (run 46054038653:
`[command]/usr/bin/sh -c for ui in light dark; do` then `Syntax error: end of file unexpected
(expecting "done")`, exit 2, and no capture ran). The four combinations therefore live in
`tools/ci/android-presentation-sweep.py`, and the workflow invokes it on one line.

Nothing here boots an emulator or touches adb: every combination's subprocess is a stub, the
clock is injected, and the workflow checks read the YAML as text.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SWEEP_PATH = ROOT / "tools" / "ci" / "android-presentation-sweep.py"
WORKFLOW = ROOT / ".github" / "workflows" / "android-presentation-evidence.yml"
# The only multi-line control flow the workflow may never contain again (the failure above).
OLD_LOOP = "for ui in light " + "dark"

SHA = "a" * 40
SEED = "presentation-seed/ledger.db"
TEST_APK = (
    "android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk"
)


def load_sweep():
    """The module is a hyphenated CLI script, so it is loaded by path.

    It is registered in `sys.modules` first: `dataclasses` resolves a class's own module while
    decorating it, and a module that is not registered yet has no entry to resolve.
    """
    spec = importlib.util.spec_from_file_location("android_presentation_sweep", SWEEP_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


sweep = load_sweep()


def combination_of(argv: list[str]) -> tuple[str, str]:
    """The (ui_mode, font_scale) pair an invoked subprocess was given."""
    return argv[argv.index("--ui-mode") + 1], argv[argv.index("--font-scale") + 1]


class Recorded:
    """A dispatch stub: records every argv and answers with a per-combination exit code."""

    def __init__(self, codes: dict[tuple[str, str], int] | None = None, default: int = 0):
        self.codes = dict(codes or {})
        self.default = default
        self.calls: list[list[str]] = []

    def __call__(self, argv: list[str]) -> int:
        self.calls.append(list(argv))
        return self.codes.get(combination_of(argv), self.default)


def sequence_clock(points: list[float]):
    pending = iter(points)

    def clock() -> float:
        return next(pending)

    return clock


class SweepCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name) / ("presentation-sweep-" + SHA)

    def config(self, command: str = "run") -> object:
        return sweep.SweepConfig(
            command=command,
            evidence_root=self.root,
            sha=SHA,
            seed=SEED,
            avd_name="ul-presentation",
            test_apk=TEST_APK,
        )

    def run_sweep(
        self,
        command: str = "run",
        codes: dict[tuple[str, str], int] | None = None,
        clock_points: list[float] | None = None,
    ) -> tuple[int, str, Recorded]:
        recorded = Recorded(codes)
        output = io.StringIO()
        code = sweep.sweep(
            self.config(command),
            dispatch=recorded,
            clock=sequence_clock(clock_points or [0.0, 1.0] * 4),
            out=output,
        )
        return code, output.getvalue(), recorded

    def record(self, name: str = "sweep.json") -> dict:
        return json.loads((self.root / name).read_text(encoding="utf-8"))


class CombinationList(unittest.TestCase):
    def test_the_four_plan_combinations_are_fixed_and_unique(self) -> None:
        # Spelled here independently of the module so a dropped or reordered combination fails.
        self.assertEqual(
            (("light", "1.0"), ("light", "1.3"), ("dark", "1.0"), ("dark", "1.3")),
            sweep.COMBINATIONS,
        )
        self.assertEqual(4, len(set(sweep.COMBINATIONS)))

    def test_each_combination_owns_the_sha_named_evidence_directory(self) -> None:
        config = sweep.SweepConfig("run", Path("presentation-sweep-x"), SHA, SEED, "ul-presentation", TEST_APK)
        self.assertEqual(
            Path("presentation-sweep-x/light-1.0"), sweep.evidence_dir(config, "light", "1.0")
        )
        self.assertEqual(
            Path("presentation-sweep-x/dark-1.3"), sweep.evidence_dir(config, "dark", "1.3")
        )

    def test_the_font_scale_script_the_sweep_drives_exists(self) -> None:
        self.assertEqual(ROOT / "tools" / "ci" / "android-fontscale.py", sweep.FONTSCALE_SCRIPT)
        self.assertTrue(sweep.FONTSCALE_SCRIPT.is_file())


class ForwardedArguments(SweepCase):
    def test_one_combination_forwards_exactly_the_documented_arguments(self) -> None:
        # A literal argv, not a re-derivation: a changed flag, order or value fails here, and the
        # sweep would otherwise hand the sampler a different capture configuration.
        expected = [
            sys.executable,
            str(SWEEP_PATH.with_name("android-fontscale.py")),
            "run",
            "--evidence",
            str(self.root / "dark-1.3"),
            "--sha",
            SHA,
            "--profile",
            "presentation",
            "--seed",
            SEED,
            "--font-scale",
            "1.3",
            "--ui-mode",
            "dark",
            "--avd-name",
            "ul-presentation",
            "--test-apk",
            TEST_APK,
        ]
        self.assertEqual(expected, sweep.fontscale_argv(self.config(), "dark", "1.3"))

    def test_the_command_word_is_the_subcommand_and_nothing_else_changes(self) -> None:
        run = sweep.fontscale_argv(self.config("run"), "light", "1.0")
        gate = sweep.fontscale_argv(self.config("gate"), "light", "1.0")
        self.assertEqual("run", run[2])
        self.assertEqual("gate", gate[2])
        self.assertEqual(run[:2] + run[3:], gate[:2] + gate[3:])


class Capture(SweepCase):
    def test_every_combination_runs_once_with_its_own_pairing(self) -> None:
        code, _output, recorded = self.run_sweep()
        self.assertEqual(0, code)
        self.assertEqual(4, len(recorded.calls))
        self.assertEqual(
            [("light", "1.0"), ("light", "1.3"), ("dark", "1.0"), ("dark", "1.3")],
            [combination_of(argv) for argv in recorded.calls],
        )
        self.assertEqual(
            [str(self.root / "light-1.0"), str(self.root / "light-1.3"),
             str(self.root / "dark-1.0"), str(self.root / "dark-1.3")],
            [argv[argv.index("--evidence") + 1] for argv in recorded.calls],
        )
        # Every call is a `run`; a capture step that gated instead would produce no screenshots.
        self.assertEqual({"run"}, {argv[2] for argv in recorded.calls})

    def test_all_passing_combinations_exit_zero_without_a_failure_marker(self) -> None:
        code, output, _recorded = self.run_sweep()
        self.assertEqual(0, code)
        self.assertNotIn("FAIL", output)
        self.assertIn("sweep run: 0 of 4 combinations failed", output)

    def test_a_failing_combination_does_not_stop_the_other_three(self) -> None:
        code, output, recorded = self.run_sweep(codes={("light", "1.3"): 1})
        # All four still ran: four evidence directories matter more than an early exit.
        self.assertEqual(4, len(recorded.calls))
        self.assertEqual(1, code)
        self.assertIn("light-1.3 -> exit 1", output)
        # The summary table marks the failure and keeps the passing rows distinguishable.
        self.assertIn("FAIL", output)
        self.assertIn("ok", output)
        self.assertIn("light-1.0", output)
        self.assertIn("dark-1.0", output)
        self.assertIn("dark-1.3", output)
        self.assertIn("sweep run: 1 of 4 combinations failed", output)

    def test_the_exit_code_is_non_zero_whenever_any_combination_failed(self) -> None:
        self.assertEqual(1, self.run_sweep(codes={("dark", "1.0"): 2})[0])
        self.assertEqual(1, self.run_sweep(codes={("light", "1.0"): 1, ("dark", "1.3"): 1})[0])
        self.assertEqual(0, self.run_sweep()[0])

    def test_the_summary_table_is_printed_after_every_combination_has_run(self) -> None:
        _code, output, _recorded = self.run_sweep()
        lines = output.splitlines()
        header = next(
            index for index, line in enumerate(lines) if line.startswith("combination")
        )
        last_report = max(
            index
            for index, line in enumerate(lines)
            if line.startswith("sweep run: light-1.0 ->")
        )
        self.assertGreater(header, last_report)


class Record(SweepCase):
    def test_the_capture_record_carries_what_the_registry_quotes(self) -> None:
        timings = [0.0, 10.0, 20.0, 25.0, 30.0, 45.0, 50.0, 50.5]
        code, _output, _recorded = self.run_sweep(clock_points=timings)
        self.assertEqual(0, code)
        self.assertEqual(
            {
                "command": "run",
                "profile": "presentation",
                "sha": SHA,
                "seed": SEED,
                "combinations": [
                    {
                        "ui_mode": "light",
                        "font_scale": "1.0",
                        "evidence": str(self.root / "light-1.0"),
                        "exit_code": 0,
                        "seconds": 10.0,
                    },
                    {
                        "ui_mode": "light",
                        "font_scale": "1.3",
                        "evidence": str(self.root / "light-1.3"),
                        "exit_code": 0,
                        "seconds": 5.0,
                    },
                    {
                        "ui_mode": "dark",
                        "font_scale": "1.0",
                        "evidence": str(self.root / "dark-1.0"),
                        "exit_code": 0,
                        "seconds": 15.0,
                    },
                    {
                        "ui_mode": "dark",
                        "font_scale": "1.3",
                        "evidence": str(self.root / "dark-1.3"),
                        "exit_code": 0,
                        "seconds": 0.5,
                    },
                ],
                "failures": 0,
                "seconds": 30.5,
            },
            self.record(),
        )

    def test_the_recorded_exit_codes_and_failure_count_follow_reality(self) -> None:
        self.run_sweep(codes={("dark", "1.3"): 1, ("light", "1.0"): 127})
        record = self.record()
        self.assertEqual(2, record["failures"])
        self.assertEqual(
            [127, 0, 0, 1], [row["exit_code"] for row in record["combinations"]]
        )

    def test_the_printed_record_is_the_pasteable_file_content(self) -> None:
        _code, output, _recorded = self.run_sweep()
        self.assertIn(str(self.root / "sweep.json") + ":", output)
        self.assertIn(json.dumps(self.record(), ensure_ascii=False, indent=2), output)

    def test_the_record_is_written_even_when_every_combination_fails(self) -> None:
        # The failing run is exactly the one whose record matters: it is the only evidence of what
        # each combination did, so a hard failure must still leave the file behind.
        code, _output, _recorded = self.run_sweep(
            codes={combination: 1 for combination in sweep.COMBINATIONS}
        )
        self.assertEqual(1, code)
        record = self.record()
        self.assertEqual(4, record["failures"])
        self.assertEqual([1, 1, 1, 1], [row["exit_code"] for row in record["combinations"]])


class Gate(SweepCase):
    def test_the_gate_runs_the_same_four_combinations_as_a_gate(self) -> None:
        code, _output, recorded = self.run_sweep("gate")
        self.assertEqual(0, code)
        self.assertEqual(
            [("light", "1.0"), ("light", "1.3"), ("dark", "1.0"), ("dark", "1.3")],
            [combination_of(argv) for argv in recorded.calls],
        )
        self.assertEqual({"gate"}, {argv[2] for argv in recorded.calls})

    def test_a_failing_gate_combination_keeps_going_and_fails_the_step(self) -> None:
        code, output, recorded = self.run_sweep("gate", codes={("dark", "1.0"): 1})
        self.assertEqual(4, len(recorded.calls))
        self.assertEqual(1, code)
        self.assertIn("sweep gate: 1 of 4 combinations failed", output)
        self.assertEqual(1, self.record("sweep-gate.json")["failures"])

    def test_a_gate_never_overwrites_the_capture_record_it_judges(self) -> None:
        self.run_sweep("run", codes={("light", "1.0"): 1})
        capture = self.record("sweep.json")
        self.run_sweep("gate")
        self.assertEqual(capture, self.record("sweep.json"))
        self.assertEqual("gate", self.record("sweep-gate.json")["command"])


class SubprocessSeam(unittest.TestCase):
    def test_a_real_child_process_exit_code_is_returned(self) -> None:
        self.assertEqual(3, sweep.execute([sys.executable, "-c", "raise SystemExit(3)"]))
        self.assertEqual(0, sweep.execute([sys.executable, "-c", "raise SystemExit(0)"]))

    def test_a_child_that_cannot_start_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            missing = str(Path(temp) / "not-a-python")
            self.assertEqual(127, sweep.execute([missing, "-c", ""]))


class CommandLine(SweepCase):
    def test_main_turns_the_workflow_arguments_into_four_capture_calls(self) -> None:
        recorded = Recorded({("light", "1.3"): 1})
        output = io.StringIO()
        with patch.object(sweep, "execute", recorded), contextlib.redirect_stdout(output):
            code = sweep.main(
                [
                    "run",
                    "--evidence-root",
                    str(self.root),
                    "--sha",
                    SHA,
                    "--seed",
                    SEED,
                    "--avd-name",
                    "ul-presentation",
                    "--test-apk",
                    TEST_APK,
                ]
            )
        self.assertEqual(1, code)
        self.assertEqual(4, len(recorded.calls))
        self.assertEqual("presentation", recorded.calls[0][recorded.calls[0].index("--profile") + 1])
        self.assertTrue((self.root / "sweep.json").is_file())

    def test_an_unknown_subcommand_is_rejected_before_any_combination_runs(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                sweep.main(["capture", "--evidence-root", str(self.root), "--sha", SHA])


class WorkflowEntry(unittest.TestCase):
    """The YAML is the caller; a multi-line `script:` here is the bug this batch fixes."""

    def inline_value(self, prefix: str) -> str:
        for raw in WORKFLOW.read_text(encoding="utf-8").splitlines():
            stripped = raw.strip()
            if stripped.startswith(prefix):
                return stripped[len(prefix):].strip()
        raise AssertionError(f"no `{prefix}` line in {WORKFLOW.name}")

    def test_the_emulator_script_is_one_complete_command_line(self) -> None:
        script = self.inline_value("script:")
        self.assertTrue(
            script.startswith("python3 tools/ci/android-presentation-sweep.py run "), script
        )
        # A block scalar or a trailing continuation would hand the action a multi-line body again.
        self.assertFalse(script.startswith(("|", ">")))
        self.assertFalse(script.endswith("\\"))
        for flag in (
            '--evidence-root "${EVIDENCE_ROOT}"',
            '--sha "${EXPECTED_SHA}"',
            '--seed "${SEED_DB}"',
            "--avd-name ul-presentation",
            f"--test-apk {TEST_APK}",
        ):
            self.assertIn(flag, script)

    def test_the_gate_step_invokes_the_same_script_on_one_line(self) -> None:
        for raw in WORKFLOW.read_text(encoding="utf-8").splitlines():
            stripped = raw.strip()
            if "android-presentation-sweep.py gate" in stripped:
                self.assertTrue(
                    stripped.startswith("run: python3 tools/ci/android-presentation-sweep.py gate "),
                    stripped,
                )
                self.assertFalse(stripped.endswith("\\"))
                for flag in ('--evidence-root "${EVIDENCE_ROOT}"', '--sha "${EXPECTED_SHA}"'):
                    self.assertIn(flag, stripped)
                return
        raise AssertionError("the gate step does not call the sweep script")

    def test_the_combination_loop_never_returns_to_the_workflow(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        # The failure quote is allowed in the explanatory comment above `script:`; an executable
        # line carrying the loop header is what must never come back.
        for raw in text.splitlines():
            if raw.lstrip().startswith("#"):
                continue
            self.assertNotIn(OLD_LOOP, raw, raw)
        self.assertNotIn("android-fontscale.py run", text)
        self.assertNotIn("android-fontscale.py gate", text)

    @unittest.skipUnless(shutil.which("sh"), "POSIX sh is not on PATH")
    def test_the_single_line_script_is_valid_posix_shell(self) -> None:
        script = self.inline_value("script:")
        completed = subprocess.run(
            ["sh", "-n"], input=script, capture_output=True, text=True, encoding="utf-8"
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("", completed.stderr)


if __name__ == "__main__":
    unittest.main()
