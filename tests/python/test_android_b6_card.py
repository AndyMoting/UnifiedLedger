"""Offline proofs for the B6 group-card open gate (cloud AVD chain)."""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

from android_b6_card import (
    ARG_FIXTURE_RELATIONS,
    DEFAULT_AVD,
    FIXTURE_RELATIONS,
    TEST_CLASS,
    B6Error,
    format_verdict,
    judge,
    gate_evidence,
    main,
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

    def test_the_default_scale_stays_the_20k_fixture(self) -> None:
        self.assertEqual(20_000, FIXTURE_RELATIONS)
        verdict = judge(self.rounds(400, 400, 400, relations=FIXTURE_RELATIONS), 3, 3000)
        self.assertEqual("PASS", verdict["verdict"])
        verdict = judge(self.rounds(400, 400, 400, relations=FIXTURE_RELATIONS - 1), 3, 3000)
        self.assertEqual("FAIL", verdict["verdict"])

    def test_a_round_above_the_requested_scale_fails_and_names_both_values(self) -> None:
        # Equality, not a lower bound: 20k evidence must never be relabelled a 4k reading.
        verdict = judge(self.rounds(400, relations=20_000), 1, 3000, 4000)
        self.assertEqual("FAIL", verdict["verdict"])
        joined = " ".join(verdict["reasons"])
        self.assertIn("20000 relations", joined)
        self.assertIn("requested 4000", joined)

    def test_a_round_below_the_requested_scale_fails_and_names_both_values(self) -> None:
        verdict = judge(self.rounds(400, relations=1500), 1, 3000, 4000)
        self.assertEqual("FAIL", verdict["verdict"])
        joined = " ".join(verdict["reasons"])
        self.assertIn("1500 relations", joined)
        self.assertIn("requested 4000", joined)

    def test_a_three_relation_round_passes_at_the_small_scale(self) -> None:
        # The SCALE-01 shape: the same gate must accept the D-274 three-relation group, and equality
        # is exactly the test for it (three relations in, three declared).
        verdict = judge(self.rounds(400, relations=3), 1, 3000, 3)
        self.assertEqual("PASS", verdict["verdict"])

    def test_a_three_relation_round_fails_when_judged_at_20k(self) -> None:
        verdict = judge(self.rounds(400, relations=3), 1, 3000, FIXTURE_RELATIONS)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("3 relations", " ".join(verdict["reasons"]))

    def test_a_zero_scale_can_never_read_as_pass(self) -> None:
        # judge "never raises", so the refusal is a FAIL reason instead of an exception; what it
        # must never be is a silent PASS with the bound disabled.
        verdict = judge(self.rounds(400, relations=20_000), 1, 3000, 0)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertTrue(verdict["reasons"])
        self.assertIn("is not a group scale", " ".join(verdict["reasons"]))

    def test_a_negative_scale_can_never_read_as_pass(self) -> None:
        # The equality bound would also reject a negative scale, but for the wrong reason and with a
        # misleading message; the positivity reason is what says the scale itself is invalid.
        verdict = judge(self.rounds(400, relations=1_000), 1, 3000, -1)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("is not a group scale", " ".join(verdict["reasons"]))


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

    def test_the_scale_reaches_both_instrumentation_modes(self) -> None:
        # The driver is the only place the scale is declared; the device side reads it back from
        # the `-e fixtureRelations` argument, so a driver that forgot it would silently measure
        # the default group.
        device = FakeDevice()
        payload = run_session(self.app, self.test, self.evidence, SHA, rounds=1, fixture_relations=3, device_factory=lambda _budget: device)
        extras = {call[1]: call[2] for call in device.calls if call[0] == "instrument"}
        for target, rendered in extras.items():
            self.assertIn(ARG_FIXTURE_RELATIONS, rendered, target)
            self.assertIn("'3'", rendered, target)
        self.assertEqual(3, payload["fixture_relations"])
        self.assertEqual(3, json.loads((self.evidence / "host.json").read_text())["fixture_relations"])

    def test_the_default_session_declares_the_20k_scale(self) -> None:
        device = FakeDevice()
        payload = run_session(self.app, self.test, self.evidence, SHA, rounds=1, device_factory=lambda _budget: device)
        extras = {call[1]: call[2] for call in device.calls if call[0] == "instrument"}
        for target, rendered in extras.items():
            self.assertIn(f"'{FIXTURE_RELATIONS}'", rendered, target)
        self.assertEqual(FIXTURE_RELATIONS, payload["fixture_relations"])


class Gating(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.evidence = Path(self.temp.name)

    def write(self, *blocks: str, sha: str = SHA, rounds: dict | None = None, fixture_relations: int = FIXTURE_RELATIONS) -> None:
        payload = {
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "avd": DEFAULT_AVD,
            "fixture_relations": fixture_relations,
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

    def test_the_default_scale_prints_the_pre_scale_verdict_shape(self) -> None:
        # The 20k chain must stay reproducible from the same command, so a default-scale verdict
        # prints what it printed before the scale existed; the scale still lands in gate.json.
        self.write(block(640), block(700), block(810))
        verdict = gate_evidence(self.evidence, SHA, 3, 3000)
        self.assertIn("limit=3000 ms\n", format_verdict(verdict))
        self.assertNotIn("scale=", format_verdict(verdict))
        self.assertEqual(FIXTURE_RELATIONS, json.loads((self.evidence / "gate.json").read_text())["fixture_relations"])

    def test_a_non_default_scale_is_named_in_the_verdict(self) -> None:
        self.write(block(640, relations=3), fixture_relations=3)
        verdict = gate_evidence(self.evidence, SHA, 1, 3000, 3)
        self.assertIn("scale=3 relations", format_verdict(verdict))

    def test_an_empty_evidence_directory_is_refused(self) -> None:
        with self.assertRaises(B6Error):
            gate_evidence(self.evidence, SHA, 3, 3000)

    def test_a_small_scale_evidence_passes_when_judged_at_that_scale(self) -> None:
        self.write(block(640, relations=3), fixture_relations=3)
        verdict = gate_evidence(self.evidence, SHA, 1, 3000, 3)
        self.assertEqual("PASS", verdict["verdict"])

    def test_the_gate_refuses_evidence_produced_at_another_scale(self) -> None:
        self.write(block(640, relations=3), fixture_relations=3)
        with self.assertRaises(B6Error) as caught:
            gate_evidence(self.evidence, SHA, 1, 3000, FIXTURE_RELATIONS)
        self.assertIn("3", str(caught.exception))
        self.assertIn("20000", str(caught.exception))

    def test_a_pre_parameter_host_json_re_gates_from_its_round_relations(self) -> None:
        # The D-282 record (runs 41768710390 / 41758573398) was written before the scale existed:
        # its host.json carries exactly apk_sha256/avd/rounds/serial/sha. That evidence must keep
        # re-deriving its original verdict, so an absent scale key defers to the round logs, which
        # report `relations=20000` in the `b6 fixture reuse:` line.
        self.write(block(1770), block(1644), block(1563))
        payload = json.loads((self.evidence / "host.json").read_text())
        del payload["fixture_relations"]
        (self.evidence / "host.json").write_text(json.dumps(payload))
        verdict = gate_evidence(self.evidence, SHA, 3, 3000)
        self.assertEqual("PASS", verdict["verdict"])
        self.assertEqual(1770, verdict["max_click_to_card_ms"])

    def test_a_scale_less_host_json_still_fails_a_small_group_judged_at_20k(self) -> None:
        # The fallback must not become a hole: with no declared scale the round logs decide, so a
        # 3-relation evidence set judged at the default still fails on the relations bound.
        self.write(block(640, relations=3))
        payload = json.loads((self.evidence / "host.json").read_text())
        del payload["fixture_relations"]
        (self.evidence / "host.json").write_text(json.dumps(payload))
        verdict = gate_evidence(self.evidence, SHA, 1, 3000, FIXTURE_RELATIONS)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("reused 3 relations", " ".join(verdict["reasons"]))

    def test_a_scale_less_20k_record_judged_as_small_fails_instead_of_relabelling(self) -> None:
        # SCALE-1: the pre-parameter 20k record must never be re-gated as a small-group PASS. With
        # no host key the round logs decide, and equality rejects 20000 against 3.
        self.write(block(1770), block(1644), block(1563))
        payload = json.loads((self.evidence / "host.json").read_text())
        del payload["fixture_relations"]
        (self.evidence / "host.json").write_text(json.dumps(payload))
        verdict = gate_evidence(self.evidence, SHA, 3, 3000, 3)
        self.assertEqual("FAIL", verdict["verdict"])
        joined = " ".join(verdict["reasons"])
        self.assertIn("reused 20000 relations", joined)
        self.assertIn("requested 3", joined)

    def test_a_declared_scale_that_agrees_with_the_judge_still_fails_a_round_at_another_scale(self) -> None:
        # The host key and the judge agree, so the host-level mismatch refusal does not fire and
        # `judge` actually runs. What disagrees is the ROUND: its reuse line reports 20000 while the
        # declared scale is 3, so the equality bound is the mechanism that fails this verdict.
        self.write(block(640, relations=FIXTURE_RELATIONS), fixture_relations=3)
        verdict = gate_evidence(self.evidence, SHA, 1, 3000, 3)
        self.assertEqual("FAIL", verdict["verdict"])
        self.assertIn("reused 20000 relations", " ".join(verdict["reasons"]))

    def test_a_zero_scale_is_refused_by_the_gate(self) -> None:
        self.write(block(640))
        with self.assertRaises(B6Error) as caught:
            gate_evidence(self.evidence, SHA, 1, 3000, 0)
        self.assertIn("not a group scale", str(caught.exception))
        self.assertFalse((self.evidence / "gate.json").exists())

    def test_a_negative_scale_is_refused_by_the_gate(self) -> None:
        self.write(block(640))
        with self.assertRaises(B6Error) as caught:
            gate_evidence(self.evidence, SHA, 1, 3000, -5)
        self.assertIn("not a group scale", str(caught.exception))
        self.assertFalse((self.evidence / "gate.json").exists())

    def test_a_declared_scale_that_disagrees_with_the_judging_scale_is_refused(self) -> None:
        # A PRESENT key is the anti-mismatch discipline: the session declared a scale, so judging it
        # at another is refused rather than silently re-derived.
        self.write(block(640), fixture_relations=FIXTURE_RELATIONS)
        with self.assertRaises(B6Error) as caught:
            gate_evidence(self.evidence, SHA, 1, 3000, 3)
        self.assertIn("20000", str(caught.exception))
        self.assertIn("not 3", str(caught.exception))

    def test_the_archived_pre_parameter_host_shape_stays_judgeable(self) -> None:
        # The literal shape of the D-282 fixed-final evidence directory: five host keys, no scale,
        # and a round whose reuse line reports 20000. Pinned as literals so this shape stays
        # re-gateable for as long as the record is cited.
        archived_host = {
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "avd": "ul-b6-card",
            "rounds": [],
            "serial": "emulator-5554",
            "sha": "4f04b60e" + "0" * 32,
        }
        self.assertEqual(
            ["apk_sha256", "avd", "rounds", "serial", "sha"],
            sorted(archived_host),
        )
        (self.evidence / "host.json").write_text(json.dumps(archived_host))
        for index, value in enumerate((1770, 1644, 1563), start=1):
            (self.evidence / f"round-{index}.log").write_text(block(value))
        verdict = gate_evidence(self.evidence, archived_host["sha"], 3, 3000)
        self.assertEqual("PASS", verdict["verdict"])
        self.assertEqual(3000, verdict["limit_ms"])
        self.assertEqual(FIXTURE_RELATIONS, verdict["fixture_relations"])
        self.assertEqual(1770, verdict["max_click_to_card_ms"])


class Cli(unittest.TestCase):
    """`main` end to end: the flag must be parsed, honoured and reported, or CI cannot see it."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.evidence = Path(self.temp.name) / "evidence"
        self.evidence.mkdir()

    def write(self, *blocks: str, sha: str = SHA, fixture_relations: int = FIXTURE_RELATIONS) -> None:
        payload = {
            "apk_sha256": {"app": "a" * 64, "test": "b" * 64},
            "avd": DEFAULT_AVD,
            "fixture_relations": fixture_relations,
            "rounds": [],
            "serial": "emulator-9999",
            "sha": sha,
        }
        (self.evidence / "host.json").write_text(json.dumps(payload))
        for index, text in enumerate(blocks, start=1):
            (self.evidence / f"round-{index}.log").write_text(text)

    def gate(self, *extra: str) -> tuple[int, str]:
        buffer = StringIO()
        with redirect_stdout(buffer):
            code = main(["gate", "--evidence", str(self.evidence), "--sha", SHA, *extra])
        return code, buffer.getvalue()

    def test_the_cli_honours_the_scale_and_exits_zero_on_pass(self) -> None:
        self.write(block(640, relations=3), fixture_relations=3)
        code, out = self.gate("--rounds", "1", "--limit-ms", "3000", "--fixture-relations", "3")
        self.assertEqual(0, code)
        self.assertIn("PASS", out)
        self.assertIn("scale=3 relations", out)

    def test_the_cli_fails_a_20k_record_judged_as_small_and_exits_one(self) -> None:
        # The SCALE-1 shape through the real entry point: a FAIL must exit 1 so CI sees it, and the
        # printed verdict must keep naming the requested scale.
        self.write(block(640, relations=FIXTURE_RELATIONS))
        payload = json.loads((self.evidence / "host.json").read_text())
        del payload["fixture_relations"]
        (self.evidence / "host.json").write_text(json.dumps(payload))
        code, out = self.gate("--rounds", "1", "--limit-ms", "3000", "--fixture-relations", "3")
        self.assertEqual(1, code)
        self.assertIn("FAIL", out)
        self.assertIn("scale=3 relations", out)

    def test_the_cli_defaults_to_the_20k_scale(self) -> None:
        self.write(block(640))
        code, out = self.gate("--rounds", "1", "--limit-ms", "3000")
        self.assertEqual(0, code)
        self.assertNotIn("scale=", out)

    def test_the_cli_refuses_a_non_positive_scale_with_exit_two(self) -> None:
        self.write(block(640))
        for value in ("0", "-5"):
            code, _ = self.gate("--rounds", "1", "--limit-ms", "3000", "--fixture-relations", value)
            self.assertEqual(2, code, value)


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
