"""Offline proofs for the P8-00 V-8 / P8-01a font-scale sample rig (cloud AVD chain)."""
from __future__ import annotations

import unittest

from android_fontscale.sampler import (
    BACK,
    BackStep,
    SampleError,
    TapStep,
    describe_step,
    dump_contains,
    find_center,
    gate_profile_problems,
    gate_seed_problems,
    marker_problems,
    parse_font_scale,
    profile_by_name,
    screen_center,
)

_DUMP = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2400]" text="" content-desc="">
    <node bounds="[0,700][1080,800]" text="" content-desc="ACCOUNT_TRANSFER，统计时间 2026-11-10T02:00:00Z，备注 对账样本转账；默认支付账户 -12.34 CNY" />
    <node bounds="[0,2180][360,2400]" text="首页" content-desc="首页" />
    <node bounds="[360,2180][720,2400]" text="账户" content-desc="账户，2 个账户" />
    <node bounds="[400,900][900,1000]" text="账本为空，还没有任何交易。" content-desc="" />
    <node bounds="[100,1200][500,1300]" text="对账样本账户" content-desc="" />
  </node>
</hierarchy>
"""


class ScreenGeometry(unittest.TestCase):
    def test_screen_center_is_the_middle_of_the_bounds(self) -> None:
        self.assertEqual((540, 2290), screen_center("[360,2180][720,2400]"))

    def test_screen_center_rejects_unparsable_bounds(self) -> None:
        with self.assertRaises(SampleError):
            screen_center("0,0,1080,2400")


class LabelLookup(unittest.TestCase):
    def test_find_center_matches_text_and_content_description(self) -> None:
        self.assertEqual((540, 2290), find_center(_DUMP, "账户"))
        self.assertEqual((540, 2290), find_center(_DUMP, "账户，2 个账户"))

    def test_find_center_returns_none_when_the_label_is_absent(self) -> None:
        self.assertIsNone(find_center(_DUMP, "分析"))

    def test_find_center_contains_matches_a_composed_row(self) -> None:
        # The flow row's accessibility label is one composed sentence, so only a substring match
        # can reach it (the seed note never equals a whole node value).
        self.assertEqual((540, 750), find_center(_DUMP, "对账样本转账", contains=True))
        self.assertIsNone(find_center(_DUMP, "对账样本转账"))

    def test_find_center_contains_picks_the_first_node_in_document_order(self) -> None:
        self.assertEqual((540, 750), find_center(_DUMP, "ACCOUNT_TRANSFER", contains=True))

    def test_find_center_rejects_an_empty_label(self) -> None:
        with self.assertRaises(SampleError):
            find_center(_DUMP, "", contains=True)

    def test_dump_contains_searches_text_and_description(self) -> None:
        self.assertTrue(dump_contains(_DUMP, "账本为空"))
        self.assertTrue(dump_contains(_DUMP, "2 个账户"))
        self.assertFalse(dump_contains(_DUMP, "还没有可管理的账户"))

    def test_dump_contains_rejects_a_broken_dump(self) -> None:
        with self.assertRaises(SampleError):
            dump_contains("<hierarchy", "账本为空")


class FontScaleReading(unittest.TestCase):
    def test_parse_font_scale_reads_the_platform_answer(self) -> None:
        self.assertAlmostEqual(1.3, parse_font_scale("1.3\n"), places=6)

    def test_parse_font_scale_fails_loudly_on_a_non_number(self) -> None:
        with self.assertRaises(SampleError):
            parse_font_scale("null")


class ScreenCaptureVerdict(unittest.TestCase):
    def test_a_present_marker_without_absent_strings_passes(self) -> None:
        self.assertEqual([], marker_problems(_DUMP, "账本为空"))
        self.assertEqual([], marker_problems(_DUMP, "账本为空", ("账户对账",)))

    def test_an_absent_marker_fails_the_capture(self) -> None:
        problems = marker_problems(_DUMP, "未核对 1 条")
        self.assertEqual(1, len(problems))
        self.assertIn("is absent", problems[0])

    def test_a_previous_screen_string_fails_the_capture(self) -> None:
        # 对账样本账户 is text on this dump; ACCOUNT_TRANSFER rides a content-description — both
        # must count, exactly as dump_contains does.
        for needle in ("对账样本账户", "ACCOUNT_TRANSFER"):
            problems = marker_problems(_DUMP, "账本为空", (needle,))
            self.assertEqual(1, len(problems))
            self.assertIn("previous-screen string", problems[0])


class Profiles(unittest.TestCase):
    def test_empty_profile_keeps_the_three_frozen_screens(self) -> None:
        profile = profile_by_name("empty")
        self.assertFalse(profile.seeded)
        self.assertEqual("账本为空", profile.launch_marker)
        self.assertEqual(["home", "asset", "analysis"], [screen.stem for screen in profile.screens])
        self.assertEqual(["账本为空", "总借入", "账本为空"], [screen.marker for screen in profile.screens])
        self.assertEqual((), profile.screens[0].steps)
        self.assertEqual((TapStep("账户"),), profile.screens[1].steps)
        self.assertEqual((TapStep("分析"),), profile.screens[2].steps)
        # The dock labels are exact: 账户 must never substring-match 账户对账 or an account name.
        self.assertFalse(profile.screens[1].steps[0].contains)
        # The empty leg declares no negative markers: its screens, paths and markers stay frozen.
        self.assertEqual([(), (), ()], [screen.absent for screen in profile.screens])

    def test_seeded_profile_captures_search_then_detail_then_the_queue(self) -> None:
        profile = profile_by_name("seeded")
        self.assertTrue(profile.seeded)
        self.assertEqual("流水 · 共 1 笔", profile.launch_marker)
        # D7-10: the V-9 search leg leads the profile; the detail screen backs out of the result
        # face first, and the queue is unchanged from i3b.
        self.assertEqual(
            ["search-guide", "search-results", "detail", "reconciliation"],
            [screen.stem for screen in profile.screens],
        )
        # The markers are success-only content, never the screen titles (交易详情 is also in the
        # failure copy; 账户对账 is also the account-flow entry button), and never a count form
        # (…… · N 笔 and 共 1 笔 already ride the seeded home screen).
        self.assertEqual(
            ["输入条件后开始搜索", "搜索结果 ·", "备注：对账样本转账", "未核对 1 条"],
            [screen.marker for screen in profile.screens],
        )
        # The capsule's rendered text is longer than its content-description, so the tap is a
        # substring; the submit button is reached by its D7-8 semantics name alone.
        self.assertEqual((TapStep("搜索账目", contains=True),), profile.screens[0].steps)
        self.assertEqual((TapStep("提交搜索"),), profile.screens[1].steps)
        self.assertEqual(
            (BACK, TapStep("对账样本转账", contains=True)),
            profile.screens[2].steps,
        )
        self.assertEqual(
            (
                BACK,
                TapStep("账户"),
                TapStep("对账样本账户", contains=True),
                TapStep("账户对账"),
            ),
            profile.screens[3].steps,
        )
        self.assertEqual(BackStep(), profile.screens[3].steps[0])
        # The search screens must not still show the home header (the launch marker), the result
        # face must not still show the guide copy; the queue sits behind the account-flow screen,
        # whose header string must be gone.
        self.assertEqual(("流水 · 共 1 笔",), profile.screens[0].absent)
        self.assertEqual(("输入条件后开始搜索", "流水 · 共 1 笔"), profile.screens[1].absent)
        self.assertEqual((), profile.screens[2].absent)
        self.assertEqual(("账户流水",), profile.screens[3].absent)

    def test_unknown_profile_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            profile_by_name("nope")

    def test_describe_step_names_the_tap_shape(self) -> None:
        self.assertEqual("tap '账户'", describe_step(TapStep("账户")))
        self.assertEqual("tap containing '对账样本账户'", describe_step(TapStep("对账样本账户", contains=True)))
        self.assertEqual("back", describe_step(BACK))


class GateProfileConsistency(unittest.TestCase):
    # Literal host payloads, not a re-derivation from PROFILES: a production change to the profile
    # table must fail this test, which a payload built from that same table could never do.
    EMPTY_HOST: dict[str, object] = {"profile": "empty", "screens": ["home", "asset", "analysis"]}
    SEEDED_HOST: dict[str, object] = {
        "profile": "seeded",
        "screens": ["search-guide", "search-results", "detail", "reconciliation"],
    }

    def test_a_matching_host_has_no_profile_problem(self) -> None:
        self.assertEqual([], gate_profile_problems(self.EMPTY_HOST, "empty"))
        self.assertEqual([], gate_profile_problems(self.SEEDED_HOST, "seeded"))

    def test_a_host_recorded_under_another_profile_is_rejected(self) -> None:
        problems = gate_profile_problems(self.EMPTY_HOST, "seeded")
        self.assertTrue(any("records profile" in problem for problem in problems))
        self.assertTrue(any("records screens" in problem for problem in problems))

    def test_a_truncated_screen_list_is_rejected(self) -> None:
        problems = gate_profile_problems({"profile": "seeded", "screens": ["detail"]}, "seeded")
        self.assertEqual(1, len(problems))
        self.assertIn("records screens", problems[0])

    def test_an_unknown_profile_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            gate_profile_problems(self.EMPTY_HOST, "nope")


class GateSeedIdentity(unittest.TestCase):
    def test_a_matching_seed_digest_has_no_problem(self) -> None:
        self.assertEqual([], gate_seed_problems({"seed": {"sha256": "a" * 64}}, "a" * 64))

    def test_a_different_seed_is_rejected(self) -> None:
        problems = gate_seed_problems({"seed": {"sha256": "a" * 64}}, "b" * 64)
        self.assertEqual(1, len(problems))
        self.assertIn("seed sha256", problems[0])

    def test_a_host_without_a_seed_digest_is_rejected(self) -> None:
        problems = gate_seed_problems({"seed": {"ledger_id": "ledger-local-test"}}, "a" * 64)
        self.assertEqual(1, len(problems))
        self.assertIn("no seed sha256", problems[0])

    def test_an_unreadable_gate_seed_is_rejected(self) -> None:
        problems = gate_seed_problems({"seed": {"sha256": "a" * 64}}, None)
        self.assertEqual(1, len(problems))
        self.assertIn("cannot be read", problems[0])


if __name__ == "__main__":
    unittest.main()
