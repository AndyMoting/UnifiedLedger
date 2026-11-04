"""Offline proofs for the B6 group-card open gate (cloud AVD chain)."""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from android_b6_card import (
    DEFAULT_AVD,
    FIXTURE_RELATIONS,
    TEST_CLASS,
    B6Error,
    judge,
    gate_evidence,
    owned_serial,
    parse_avd_name,
    parse_round,
    require_hosted_runner,
    run_session,
    slice_last_round,
)

SHA = "0123456789abcdef0123456789abcdef01234567"

ROUND_BLOCK = """\
== b6CardOpen start epochMillis=1762300000000
args target=scale-duplicate-group maxForwardActions=40 settleMillis=200 stallChecks=3 seedFixture=true
b6 fixture reuse: generation=1 relations=20000 historySources=3 subject=scale-duplicate-source (no rebuild)
group affordance click accepted=true
D278STAGE: progress-seen=true click-to-progress-ms=158 progress-to-card-ms=482 click-to-card-ms=640 poll-ms=60
group card open=true elapsedMs=655
group card itemCount=20002 itemCountText=共 20002 条。
== b6CardOpen end
"""

STAGE_ONLY = (
    "D278STAGE: progress-seen=false click-to-progress-ms=null "
    "progress-to-card-ms=null click-to-card-ms=2810 poll-ms=60\n"
    "group card open=true elapsedMs=2830\n"
    "b6 fixture reuse: generation=1 relations=20000 historySources=3 subject=scale-duplicate-source\n"
)


def block(click_to_card: int, *, relations: int = 20000, opened: bool = True) -> str:
    text = ROUND_BLOCK.replace("click-to-card-ms=640", f"click-to-card-ms={click_to_card}")
    text = text.replace("relations=20000", f"relations={relations}")
    if not opened:
        text = text.replace("group card open=true", "group card open=false")
    return text


class Parsing(unittest.TestCase):
    def test_staged_marks_are_read_verbatim(self) -> None:
        parsed = parse_round(ROUND_BLOCK)
        self.assertTrue(parsed["card_open"])
        self.assertEqual(640, parsed["click_to_card_ms"])
        self.assertEqual(158, parsed["click_to_progress_ms"])
        self.assertEqual(482, parsed["progress_to_card_ms"])
        self.assertEqual(FIXTURE_RELATIONS, parsed["fixture_relations"])
        self.assertEqual(20002, parsed["item_count"])

    def test_absent_progress_mark_stays_none(self) -> None:
        parsed = parse_round(STAGE_ONLY)
        self.assertFalse(parsed["progress_seen"])
        self.assertIsNone(parsed["click_to_progress_ms"])
        self.assertIsNone(parsed["progress_to_card_ms"])
        self.assertEqual(2810, parsed["click_to_card_ms"])

    def test_a_round_without_the_stage_line_is_a_refusal(self) -> None:
        with self.assertRaises(B6Error):
            parse_round("group card open=true elapsedMs=12\n")

    def test_a_round_without_the_open_line_is_a_refusal(self) -> None:
        with self.assertRaises(B6Error):
            parse_round("D278STAGE: progress-seen=true click-to-progress-ms=1 progress-to-card-ms=2 click-to-card-ms=3 poll-ms=60\n")

    def test_the_last_block_of_an_accumulated_log_is_the_round(self) -> None:
        accumulated = block(9000) + block(700) + block(810)
        self.assertIn("click-to-card-ms=810", slice_last_round(accumulated))
        self.assertNotIn("click-to-card-ms=700", slice_last_round(accumulated))

    def test_a_log_without_a_block_is_a_refusal(self) -> None:
        with self.assertRaises(B6Error):
            slice_last_round("SEED_FIXTURE REUSED\n")


class Judging(unittest.TestCase):
    def rounds(self, *values: int, relations: int = 20000, opened: bool = True) -> list[dict]:
        return [parse_round(block(value, relations=relations, opened=opened)) for value in values]

    def test_the_statistic_is_the_worst_round_not_the_median(self) -> None:
        verdict = judge(self.rounds(400, 500, 2900), 3, 3000)
        self.assertEqual("PASS", verdict["verdict"])
        self.assertEqual(2900, verdict["max_click_to_card_ms"])

    def test_a_single_slow_round_fails_the_gate(self) -> None:
        verdict = judge(self.rounds(400, 500, 3001), 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("3001 ms", " ".join(verdict["reasons"]))

    def test_the_bound_is_inclusive(self) -> None:
        self.assertEqual("PASS", judge(self.rounds(3000, 3000, 3000), 3, 3000)["verdict"])

    def test_an_unopened_card_fails_the_gate(self) -> None:
        verdict = judge(self.rounds(400, 400, 400, opened=False), 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("never became visible", " ".join(verdict["reasons"]))

    def test_a_missing_fixture_fails_the_gate(self) -> None:
        verdict = judge(self.rounds(400, relations=3), 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertTrue(any("duplicate-group fixture" in reason for reason in verdict["reasons"]))

    def test_a_short_round_set_fails_the_gate(self) -> None:
        verdict = judge(self.rounds(400, 500), 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("2 round log(s)", " ".join(verdict["reasons"]))


class Ownership(unittest.TestCase):
    def test_exactly_one_emulator(self) -> None:
        self.assertEqual("emulator-5556", owned_serial("List of devices attached\nemulator-5556\tdevice\n"))

    def test_two_emulators_are_refused(self) -> None:
        with self.assertRaises(B6Error):
            owned_serial("emulator-5554\tdevice\nemulator-5556\tdevice\n")

    def test_no_emulator_is_refused(self) -> None:
        with self.assertRaises(B6Error):
            owned_serial("List of devices attached\n")

    def test_the_console_ok_line_is_stripped(self) -> None:
        self.assertEqual("ul-b6-card", parse_avd_name("ul-b6-card\nOK\n"))

    def test_an_unexpected_transcript_yields_no_name(self) -> None:
        self.assertEqual("", parse_avd_name("OK\nOK\n"))

    def test_a_local_environment_is_refused(self) -> None:
        with self.assertRaises(B6Error):
            require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted", "RUNNER_OS": "Linux"})

    def test_a_hosted_runner_is_accepted(self) -> None:
        require_hosted_runner({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux"})


class FakeDevice:
    """A device that answers from a script instead of an emulator."""

    def __init__(self, avd: str = DEFAULT_AVD, blocks: list[str] | None = None):
        self.serial = "emulator-9999"
        self.avd = avd
        self.calls: list[tuple[str, ...]] = []
        self.blocks = list(blocks or [block(640), block(700), block(810)])
        self.seeded = 0
        self.force_stops = 0
        self.clears = 0

    def text(self, *args: str, timeout: float = 120.0) -> str:
        self.calls.append(args)
        if args == ("emu", "avd", "name"):
            return f"{self.avd}\nOK\n"
        if args[:1] == ("install",):
            return "Performing Streamed Install\nSuccess\n"
        if args[:3] == ("shell", "pm", "list"):
            return f"instrumentation:com.unifiedledger.android.test/androidx.test.runner.AndroidJUnitRunner (target=com.unifiedledger.android)\n"
        if args[:3] == ("shell", "am", "force-stop"):
            self.force_stops += 1
            return ""
        if args[:3] == ("shell", "pm", "clear"):
            self.clears += 1
            return "Success\n"
        return ""

    def instrument(self, target: str, timeout: float, extra=()) -> tuple[int, str]:
        self.calls.append(("instrument", target, str(extra)))
        if target.endswith("#seedFixture"):
            self.seeded += 1
            return 0, "OK (1 test)\n"
        return 0, "OK (1 test)\n"

    def read_private(self, relative: str) -> str | None:
        self.calls.append(("read_private", relative))
        if not self.blocks:
            raise AssertionError("more rounds ran than the fake prepared")
        return "".join(self.blocks[: self.seeded]) + self.blocks.pop(0)


class Session(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.app.write_bytes(b"app")
        self.test.write_bytes(b"test")
        self.evidence = self.root / "evidence"

    def test_every_round_is_a_clear_seed_measure_cycle(self) -> None:
        device = FakeDevice()
        payload = run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        # The published fixture is only reusable until the product's activation intake adds a
        # candidate, so each round rebuilds it: three seeds, three clears, and a force-stop on
        # both sides of every seed.
        self.assertEqual(3, device.seeded)
        self.assertEqual(3, device.clears)
        self.assertEqual(6, device.force_stops)
        self.assertEqual([640, 700, 810], [item["click_to_card_ms"] for item in payload["rounds"]])
        self.assertEqual(SHA, payload["sha"])
        self.assertEqual(DEFAULT_AVD, payload["avd"])
        self.assertTrue((self.evidence / "host.json").is_file())
        for index in (1, 2, 3):
            self.assertTrue((self.evidence / f"round-{index}.log").is_file())
            self.assertTrue((self.evidence / f"seed-{index}.txt").is_file())
        self.assertEqual(
            f"{TEST_CLASS}#b6CardOpen",
            [call for call in device.calls if call[0] == "instrument"][1][1],
        )

    def test_a_foreign_avd_is_refused_before_installing(self) -> None:
        device = FakeDevice(avd="ul-scale")
        with self.assertRaises(B6Error):
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertFalse(any(call[:1] == ("install",) for call in device.calls))

    def test_a_failed_seed_stops_the_session(self) -> None:
        device = FakeDevice()
        device.instrument = lambda target, timeout, extra=(): (0, "FAILURES!!!\n")  # type: ignore[method-assign]
        with self.assertRaises(B6Error):
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)

    def test_a_later_round_seed_failure_stops_the_session(self) -> None:
        device = FakeDevice()
        original = device.instrument
        seeds = {"count": 0}

        def flaky(target: str, timeout: float, extra=()) -> tuple[int, str]:
            if target.endswith("#seedFixture"):
                seeds["count"] += 1
                if seeds["count"] == 2:
                    return 0, "FAILURES!!!\n"
            return original(target, timeout, extra)

        device.instrument = flaky  # type: ignore[method-assign]
        with self.assertRaises(B6Error):
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertEqual(2, seeds["count"])

    def test_a_failed_measuring_process_names_its_cause(self) -> None:
        device = FakeDevice()
        original = device.instrument

        def failing_measure(target: str, timeout: float, extra=()) -> tuple[int, str]:
            if target.endswith("#b6CardOpen"):
                return 0, (
                    "There was 1 failure:\n"
                    "1) b6CardOpen\n"
                    "java.lang.IllegalStateException: the published fixture is invalid\n"
                    "FAILURES!!!\n"
                )
            return original(target, timeout, extra)

        device.instrument = failing_measure  # type: ignore[method-assign]
        with self.assertRaises(B6Error) as caught:
            run_session(self.app, self.test, self.evidence, SHA, device_factory=lambda _budget: device)
        self.assertIn("the published fixture is invalid", str(caught.exception))


class Gating(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.evidence = Path(self.temp.name)

    def write(self, *blocks: str, sha: str = SHA, rounds: dict | None = None) -> None:
        payload = {
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "avd": DEFAULT_AVD,
            "rounds": rounds if rounds is not None else [],
            "serial": "emulator-9999",
            "sha": sha,
        }
        (self.evidence / "host.json").write_text(json.dumps(payload))
        for index, text in enumerate(blocks, start=1):
            (self.evidence / f"round-{index}.log").write_text(text)

    def test_the_gate_recomputes_from_the_round_logs(self) -> None:
        self.write(block(640), block(700), block(810))
        verdict = gate_evidence(self.evidence, SHA, 3, 3000)
        self.assertEqual("PASS", verdict["verdict"])
        self.assertEqual(810, verdict["max_click_to_card_ms"])
        self.assertTrue((self.evidence / "gate.json").is_file())

    def test_a_lying_host_json_cannot_make_the_gate_green(self) -> None:
        self.write(block(640), block(700), block(9000), rounds={"max_click_to_card_ms": 10})
        verdict = gate_evidence(self.evidence, SHA, 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])

    def test_evidence_for_another_commit_is_refused(self) -> None:
        self.write(block(640), block(700), block(810), sha="f" * 40)
        with self.assertRaises(B6Error):
            gate_evidence(self.evidence, SHA, 3, 3000)

    def test_an_empty_evidence_directory_is_refused(self) -> None:
        with self.assertRaises(B6Error):
            gate_evidence(self.evidence, SHA, 3, 3000)


class Workflow(unittest.TestCase):
    """The workflow and this module must agree on the AVD name and the gate bounds."""

    def test_the_workflow_drives_this_module_with_the_module_bounds(self) -> None:
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github" / "workflows" / "android-b6-card.yml").read_text()
        self.assertIn("tools/ci/android-b6-card.py run", text)
        self.assertIn("tools/ci/android-b6-card.py gate", text)
        self.assertIn(f"--avd-name {DEFAULT_AVD}", text)
        self.assertIn("--rounds", text)
        self.assertIn("--limit-ms", text)
        self.assertIn("avd-name: ul-b6-card", text)

    def test_the_workflow_never_runs_on_a_pull_request(self) -> None:
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github" / "workflows" / "android-b6-card.yml").read_text()
        self.assertNotIn("pull_request:", text)
        self.assertIn("workflow_dispatch:", text)


if __name__ == "__main__":
    unittest.main()
