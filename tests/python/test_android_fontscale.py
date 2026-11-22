"""Offline proofs for the P8-00 V-8 / P8-01a / P8-02 / P8-03 font-scale sample rig (cloud AVD chain)."""
from __future__ import annotations

import base64
import unittest
from pathlib import Path

from android_fontscale.sampler import (
    BACK,
    DISMISS_IME,
    BackStep,
    DismissImeStep,
    InputTextStep,
    P806SeedFacts,
    RadioStep,
    SampleError,
    Sampler,
    ScrollStep,
    SpeechCheck,
    TapStep,
    adb_input_text,
    derive_p806_seed_facts,
    describe_step,
    dismiss_ime,
    dump_contains,
    find_center,
    find_field_center,
    find_radio_center,
    find_reachable_center,
    find_resource_center,
    find_talkback_level_summary,
    gate_artifact_problems,
    gate_profile_problems,
    gate_seed_problems,
    ime_is_shown,
    input_text,
    isolate_talkback_window,
    marker_problems,
    overall_speech_status,
    p806_gate_problems,
    p806_rollover_problem,
    p806_screens,
    parse_font_scale,
    profile_by_name,
    profile_screens,
    required_problems,
    screen_center,
    scroll_until,
    scroll_swipe_points,
    speech_fragments,
    talkback_check_verdict,
    talkback_level_summary_value,
    talkback_status_label,
    tap_radio_for,
    viewport_problems,
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

# One option row's device shape: a clickable RadioButton plus a sibling Text. The 支出 row's radio
# is also in the 退款到账 row's band, so the lookup has to pick the NEAREST left radio, not the first.
_RADIO_DUMP = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2400]" class="android.widget.FrameLayout">
    <node bounds="[0,600][1080,760]" class="" text="" content-desc="">
      <node bounds="[32,620][200,740]" class="android.widget.RadioButton" checkable="true" clickable="true" text="" content-desc="" />
      <node bounds="[220,600][420,760]" class="android.widget.TextView" text="支出" content-desc="" />
      <node bounds="[560,620][728,740]" class="android.widget.RadioButton" checkable="true" clickable="true" text="" content-desc="" />
      <node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />
    </node>
  </node>
</hierarchy>
"""

# The same row with a second clickable node carrying IDENTICAL bounds: two hit targets resolve to
# the same point, which is an ambiguity rather than a choice (the lookup orders by vertical gap then
# horizontal distance, so only a true duplicate can tie).
_AMBIGUOUS_RADIO_DUMP = _RADIO_DUMP.replace(
    '<node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />',
    '<node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />'
    '<node bounds="[560,620][728,740]" class="android.widget.RadioButton" checkable="true" clickable="true" text="" content-desc="" />',
)

# A field's floating label, its supporting text (which also carries the label) and two EditTexts:
# the nearest-to-the-label field must win over the first EditText in document order.
_FIELD_DUMP = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2400]" class="android.widget.FrameLayout">
    <node bounds="[48,760][400,820]" class="android.widget.TextView" text="退款金额（CNY）" content-desc="" />
    <node bounds="[32,840][1048,960]" class="android.widget.EditText" text="" content-desc="" />
    <node bounds="[32,980][1048,1040]" class="android.widget.TextView" text="退款金额不超过原交易的支出金额与剩余可退金额" content-desc="" />
    <node bounds="[32,1100][400,1160]" class="android.widget.TextView" text="金额算式（可选）" content-desc="" />
    <node bounds="[32,1160][1048,1280]" class="android.widget.EditText" text="" content-desc="" />
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


class RadioTap(unittest.TestCase):
    def test_the_radio_is_the_nearest_clickable_left_of_the_label(self) -> None:
        # The 支出 row's radio is further left in the same band; the 退款到账 row's own radio must win.
        self.assertEqual((644, 680), find_radio_center(_RADIO_DUMP, "退款到账"))
        self.assertEqual((116, 680), find_radio_center(_RADIO_DUMP, "支出"))

    def test_a_clickable_label_node_is_its_own_radio(self) -> None:
        dump = """<hierarchy><node bounds="[0,0][1080,2400]">
          <node bounds="[32,620][200,740]" class="android.widget.RadioButton"
                checkable="true" clickable="true" text="退款到账" content-desc="" />
        </node></hierarchy>"""
        self.assertEqual((116, 680), find_radio_center(dump, "退款到账"))

    def test_a_contains_match_reaches_a_composed_row(self) -> None:
        dump = """<hierarchy><node bounds="[0,0][1080,2400]">
          <node bounds="[32,620][200,740]" class="android.widget.RadioButton"
                checkable="true" clickable="true" text="" content-desc="" />
          <node bounds="[748,600][1000,760]" class="android.widget.TextView"
                text="2026-11-11 · 早餐 · 支出腿金额 12.34" content-desc="" />
        </node></hierarchy>"""
        self.assertEqual((116, 680), find_radio_center(dump, "早餐", contains=True))
        with self.assertRaises(SampleError):
            find_radio_center(dump, "早餐")

    def test_an_ambiguous_row_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            find_radio_center(_AMBIGUOUS_RADIO_DUMP, "退款到账")

    def test_two_matching_rows_fail_loudly(self) -> None:
        dump = _RADIO_DUMP.replace(
            '<node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />',
            '<node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />'
            '<node bounds="[32,1000][200,1120]" class="android.widget.RadioButton" checkable="true" clickable="true" text="" content-desc="" />'
            '<node bounds="[748,1000][1000,1120]" class="android.widget.TextView" text="退款到账" content-desc="" />',
        )
        with self.assertRaises(SampleError):
            find_radio_center(dump, "退款到账")

    def test_a_label_without_a_left_clickable_fails_loudly(self) -> None:
        dump = """<hierarchy><node bounds="[0,0][1080,2400]">
          <node bounds="[748,600][1000,760]" class="android.widget.TextView" text="退款到账" content-desc="" />
        </node></hierarchy>"""
        with self.assertRaises(SampleError):
            find_radio_center(dump, "退款到账")

    def test_an_absent_or_empty_label_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            find_radio_center(_RADIO_DUMP, "转账")
        with self.assertRaises(SampleError):
            find_radio_center(_RADIO_DUMP, "")

    def test_the_step_constructor_rejects_an_empty_label(self) -> None:
        with self.assertRaises(SampleError):
            tap_radio_for("")


class TextFieldLookup(unittest.TestCase):
    def test_the_nearest_edit_text_to_the_label_is_the_field(self) -> None:
        # The supporting text also carries the label; both resolve to the amount field, and the
        # first EditText in document order happens to be the same one here.
        self.assertEqual((540, 900), find_field_center(_FIELD_DUMP, "退款金额"))

    def test_a_nearer_later_edit_text_wins_over_the_first_in_document_order(self) -> None:
        dump = """<hierarchy><node bounds="[0,0][1080,2400]">
          <node bounds="[32,300][1048,420]" class="android.widget.EditText" text="" content-desc="" />
          <node bounds="[48,760][400,820]" class="android.widget.TextView" text="退款金额（CNY）" content-desc="" />
          <node bounds="[32,840][1048,960]" class="android.widget.EditText" text="" content-desc="" />
        </node></hierarchy>"""
        self.assertEqual((540, 900), find_field_center(dump, "退款金额"))

    def test_an_edit_text_carrying_its_own_label_is_the_field(self) -> None:
        dump = """<hierarchy><node bounds="[0,0][1080,2400]">
          <node bounds="[32,840][1048,960]" class="android.widget.EditText"
                text="" content-desc="退款金额（CNY）" />
        </node></hierarchy>"""
        self.assertEqual((540, 900), find_field_center(dump, "退款金额"))

    def test_a_missing_label_or_no_edit_text_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            find_field_center(_FIELD_DUMP, "备注")
        with self.assertRaises(SampleError):
            find_field_center(
                '<hierarchy><node bounds="[0,0][1080,2400]">'
                '<node bounds="[48,760][400,820]" class="android.widget.TextView" text="退款金额（CNY）" />'
                "</node></hierarchy>",
                "退款金额",
            )
        with self.assertRaises(SampleError):
            find_field_center("<hierarchy />", "退款金额")
        with self.assertRaises(SampleError):
            find_field_center(_FIELD_DUMP, "")


class AdbTextEscaping(unittest.TestCase):
    def test_plain_ascii_passes_through(self) -> None:
        self.assertEqual("3.00", adb_input_text("3.00"))

    def test_a_space_is_the_adb_placeholder(self) -> None:
        self.assertEqual("12%s34", adb_input_text("12 34"))

    def test_shell_metacharacters_are_escaped(self) -> None:
        self.assertEqual("a\\&b\\<c", adb_input_text("a&b<c"))

    def test_non_ascii_and_empty_text_fail_loudly(self) -> None:
        with self.assertRaises(SampleError):
            adb_input_text("退款")
        with self.assertRaises(SampleError):
            adb_input_text("")

    def test_the_step_constructor_validates_before_touching_a_device(self) -> None:
        self.assertEqual(InputTextStep("退款金额", "3.00"), input_text("退款金额", "3.00"))
        with self.assertRaises(SampleError):
            input_text("", "3.00")
        with self.assertRaises(SampleError):
            input_text("退款金额", "退款")


class ImeState(unittest.TestCase):
    def test_minputshown_drives_the_answer(self) -> None:
        self.assertTrue(ime_is_shown("  mInputShown=true\n"))
        self.assertFalse(ime_is_shown("  mInputShown=false\n"))

    def test_a_missing_answer_fails_loudly_and_the_last_occurrence_wins(self) -> None:
        with self.assertRaises(SampleError):
            ime_is_shown("mCurMethodId=com.example/.Ime\n")
        # The manager and the IME service both print the flag and may briefly disagree during an
        # animation; the last occurrence is the current client state.
        self.assertFalse(ime_is_shown("mInputShown=true\nmInputShown=false\n"))
        self.assertTrue(ime_is_shown("mInputShown=false\nmInputShown=true\n"))

    def test_the_constructor_is_the_frozen_step(self) -> None:
        self.assertIsInstance(dismiss_ime(), DismissImeStep)
        self.assertEqual(DISMISS_IME, dismiss_ime())


class _StubSampler(Sampler):
    """The Sampler surface the offline step tests need, with no device and no real dump."""

    tree = '<hierarchy><node bounds="[0,0][1080,2400]" class="" text="" content-desc="" /></hierarchy>'

    def __init__(self, dumpsys_answers: list[str] | None = None) -> None:
        super().__init__(adb_serial=None, evidence=Path("."), font_scale=1.3, avd_name="ul-fontscale")
        self.dumpsys_answers = list(dumpsys_answers or [])
        self.commands: list[tuple[str, ...]] = []
        self.dump_stems: list[str] = []

    def adb(self, *args: str, **kwargs: object) -> str:
        self.commands.append(args)
        if args[:3] == ("shell", "dumpsys", "input_method") and self.dumpsys_answers:
            return self.dumpsys_answers.pop(0)
        return ""

    def dump(self, stem: str) -> str:
        self.dump_stems.append(stem)
        return self.tree

    def wait_for_change(self, previous: str, timeout_s: int = 60) -> str:
        return self.tree


class _DispatchSampler(_StubSampler):
    """Records which sampler action each new step type dispatches to; no device interaction."""

    def __init__(self) -> None:
        super().__init__()
        self.calls: list[str] = []

    def tap_radio(self, label: str, contains: bool = False) -> str:
        self.calls.append(f"radio:{label}")
        return self.tree

    def type_into_field(self, field_label: str, text: str) -> str:
        self.calls.append(f"text:{field_label}:{text}")
        return self.tree

    def hide_ime(self) -> str:
        self.calls.append("ime")
        return self.tree

    def scroll_to(self, label: str, max_swipes: int = 6, fully: bool = False) -> str:
        self.calls.append(f"scroll:{label}")
        return self.tree


class StepDispatch(unittest.TestCase):
    def test_every_new_step_type_dispatches_to_its_sampler_action(self) -> None:
        sampler = _DispatchSampler()
        sampler.apply_step(tap_radio_for("退款到账"), sampler.tree)
        sampler.apply_step(input_text("退款金额", "3.00"), sampler.tree)
        sampler.apply_step(dismiss_ime(), sampler.tree)
        sampler.apply_step(scroll_until("继续"), sampler.tree)
        self.assertEqual(
            ["radio:退款到账", "text:退款金额:3.00", "ime", "scroll:继续"],
            sampler.calls,
        )


class ScrollAndImeSteps(unittest.TestCase):
    def test_the_swipe_goes_from_two_thirds_down_to_one_third_down(self) -> None:
        self.assertEqual((540, 1600, 540, 800), scroll_swipe_points(_RADIO_DUMP))

    def test_reachable_center_requires_the_label_inside_the_window(self) -> None:
        self.assertEqual(
            (200, 900),
            find_reachable_center(
                '<hierarchy><node bounds="[0,0][1080,2400]" />'
                '<node bounds="[0,800][400,1000]" text="继续" /></hierarchy>',
                "继续",
            ),
        )
        # A scrollable form exposes off-screen children; their bounds are below the window, so a
        # bare "is it in the dump" check would accept a dead tap.
        self.assertIsNone(
            find_reachable_center(
                '<hierarchy><node bounds="[0,0][1080,2400]" />'
                '<node bounds="[0,2600][400,2800]" text="继续" /></hierarchy>',
                "继续",
            ),
        )
        self.assertIsNone(
            find_reachable_center('<hierarchy><node bounds="[0,0][1080,2400]" /></hierarchy>', "继续")
        )
        with self.assertRaises(SampleError):
            find_reachable_center('<hierarchy><node bounds="[0,0][1080,2400]" /></hierarchy>', "")

    def test_empty_or_unparsable_window_bounds_fail_loudly(self) -> None:
        with self.assertRaises(SampleError):
            scroll_swipe_points('<hierarchy><node bounds="[0,0][0,0]" /></hierarchy>')
        with self.assertRaises(SampleError):
            scroll_swipe_points("<hierarchy />")

    def test_scroll_to_stops_loudly_after_the_swipe_budget(self) -> None:
        sampler = _StubSampler()
        with self.assertRaises(SampleError) as raised:
            sampler.scroll_to("继续", max_swipes=2)
        self.assertIn("after 2 swipes", str(raised.exception))
        self.assertEqual(
            2,
            len([command for command in sampler.commands if command[:3] == ("shell", "input", "swipe")]),
        )

    def test_hide_ime_sends_no_back_when_the_keyboard_is_already_down(self) -> None:
        sampler = _StubSampler(["mInputShown=false"])
        self.assertEqual(sampler.tree, sampler.hide_ime())
        self.assertNotIn(("shell", "input", "keyevent", "4"), sampler.commands)
        self.assertEqual(["after-ime"], sampler.dump_stems)

    def test_hide_ime_backs_out_and_waits_for_the_platform_to_agree(self) -> None:
        sampler = _StubSampler(["mInputShown=true", "mInputShown=false"])
        self.assertEqual(sampler.tree, sampler.hide_ime())
        self.assertIn(("shell", "input", "keyevent", "4"), sampler.commands)
        self.assertEqual(
            2,
            len([command for command in sampler.commands if command[:3] == ("shell", "dumpsys", "input_method")]),
        )

    def test_scroll_until_requires_a_label_and_a_positive_budget(self) -> None:
        self.assertEqual(ScrollStep("继续", 6), scroll_until("继续"))
        with self.assertRaises(SampleError):
            scroll_until("")
        with self.assertRaises(SampleError):
            scroll_until("继续", max_swipes=0)


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

    def test_the_viewport_check_rejects_a_clipped_marker(self) -> None:
        self.assertEqual([], viewport_problems(_DUMP, "账本为空"))
        clipped = _DUMP.replace(
            '[400,900][900,1000]', '[400,2300][900,2500]'
        )
        problem = viewport_problems(clipped, "账本为空")
        self.assertEqual(1, len(problem))
        self.assertIn("clipped", problem[0])
        self.assertTrue(viewport_problems(_DUMP, "未核对 1 条"))


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
        self.assertEqual("流水 · 共 2 笔", profile.launch_marker)
        # D7-10: the V-9 search leg leads the profile; the detail screen backs out of the result
        # face first, and the queue is unchanged from i3b.
        self.assertEqual(
            ["search-guide", "search-results", "detail", "reconciliation"],
            [screen.stem for screen in profile.screens],
        )
        # The markers are success-only content, never the screen titles (交易详情 is also in the
        # failure copy; 账户对账 is also the account-flow entry button), and never a count form
        # (…… · N 笔 and 共 N 笔 already ride the seeded home screen).
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
        self.assertEqual(("流水 · 共 2 笔",), profile.screens[0].absent)
        self.assertEqual(("输入条件后开始搜索", "流水 · 共 2 笔"), profile.screens[1].absent)
        self.assertEqual((), profile.screens[2].absent)
        self.assertEqual(("账户流水",), profile.screens[3].absent)

    def test_refund_profile_captures_the_entry_then_the_confirmation(self) -> None:
        profile = profile_by_name("refund")
        self.assertTrue(profile.seeded)
        # The refund leg rides the same prepared seeded ledger as `seeded`.
        self.assertEqual("流水 · 共 2 笔", profile.launch_marker)
        self.assertEqual(
            ["refund-entry", "refund-confirmation"],
            [screen.stem for screen in profile.screens],
        )
        # The entry's marker is the seed expense's secondary category name, which only the loaded
        # candidate row renders; the confirmation's marker is the same category, which only a
        # resolved candidate renders in its inherited-category row.
        self.assertEqual(
            ["早餐", "早餐"],
            [screen.marker for screen in profile.screens],
        )
        self.assertEqual((TapStep("新增支出"), RadioStep("退款到账")), profile.screens[0].steps)
        self.assertEqual(
            (
                RadioStep("早餐", contains=True),
                RadioStep("退款样本账户", contains=True),
                InputTextStep("退款金额", "3.00"),
                DISMISS_IME,
                ScrollStep("发生时间", 6),
                InputTextStep("发生时间", "2026-11-12T08:00:00Z"),
                DISMISS_IME,
                ScrollStep("继续", 6),
                TapStep("继续"),
            ),
            profile.screens[1].steps,
        )
        # A capture that never left the home tab, or never left the entry form, is not evidence.
        self.assertEqual(("流水 · 共 2 笔", "费用分类"), profile.screens[0].absent)
        self.assertEqual(("新增退款到账",), profile.screens[1].absent)

    def test_unknown_profile_fails_loudly(self) -> None:
        with self.assertRaises(SampleError):
            profile_by_name("nope")

    def test_describe_step_names_the_tap_shape(self) -> None:
        self.assertEqual("tap '账户'", describe_step(TapStep("账户")))
        self.assertEqual("tap containing '对账样本账户'", describe_step(TapStep("对账样本账户", contains=True)))
        self.assertEqual("tap radio '退款到账'", describe_step(tap_radio_for("退款到账")))
        self.assertEqual("tap radio containing '早餐'", describe_step(tap_radio_for("早餐", contains=True)))
        self.assertEqual("type '3.00' into '退款金额'", describe_step(input_text("退款金额", "3.00")))
        self.assertEqual("dismiss ime", describe_step(dismiss_ime()))
        self.assertEqual("scroll to '继续' (max 6 swipes)", describe_step(scroll_until("继续")))
        self.assertEqual("back", describe_step(BACK))


class GateProfileConsistency(unittest.TestCase):
    # Literal host payloads, not a re-derivation from PROFILES: a production change to the profile
    # table must fail this test, which a payload built from that same table could never do.
    EMPTY_HOST: dict[str, object] = {"profile": "empty", "screens": ["home", "asset", "analysis"]}
    SEEDED_HOST: dict[str, object] = {
        "profile": "seeded",
        "screens": ["search-guide", "search-results", "detail", "reconciliation"],
    }
    REFUND_HOST: dict[str, object] = {
        "profile": "refund",
        "screens": ["refund-entry", "refund-confirmation"],
    }

    def test_a_matching_host_has_no_profile_problem(self) -> None:
        self.assertEqual([], gate_profile_problems(self.EMPTY_HOST, "empty"))
        self.assertEqual([], gate_profile_problems(self.SEEDED_HOST, "seeded"))
        self.assertEqual([], gate_profile_problems(self.REFUND_HOST, "refund"))

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


class P806Profile(unittest.TestCase):
    """P8-06 batch 6 V-12: the dynamic p806 profile and its seed re-derivation."""

    FACTS = P806SeedFacts(
        month_label="2026年11月",
        day_date="2026-11-22",
        day_count=2,
        currency="CNY",
        precision=2,
        net_expense_minor=1_734,
        income_minor=0,
        payment_texts=("-12.34 CNY", "-5.00 CNY"),
    )

    def test_the_four_screens_carry_the_seeds_exact_amounts_and_speech_checks(self) -> None:
        screens = p806_screens(self.FACTS)
        self.assertEqual(
            ["report", "report-daily-average", "calendar", "day-detail"],
            [screen.stem for screen in screens],
        )
        note = self.FACTS.daily_average_note_text()
        self.assertEqual(
            ["报表期间", note, "收支日历", "共 2 笔明细"],
            [screen.marker for screen in screens],
        )
        report, daily_average, calendar, detail = screens
        self.assertEqual(
            (TapStep("分析"), ScrollStep("净支出（含退款） 17.34 CNY")), report.steps
        )
        self.assertIn("月：2026-11-01 至 2026-11-30（30 天）", report.required)
        self.assertIn("净支出（含退款） 17.34 CNY", report.required)
        self.assertIn("普通收入 0.00 CNY", report.required)
        self.assertIn("普通收支笔数：2", report.required)
        self.assertIn("以下为最近一次成功载荷（旧数据）：", report.absent)
        self.assertEqual(
            (SpeechCheck("结余 -17.34 CNY", ("-17.34 CNY",)),), report.speech
        )
        self.assertEqual("结余 -17.34 CNY", self.FACTS.balance_text())
        self.assertEqual((ScrollStep(note, fully=True),), daily_average.steps)
        self.assertEqual((note,), daily_average.required)
        self.assertTrue(daily_average.marker_in_viewport)
        self.assertFalse(report.marker_in_viewport)
        self.assertEqual((), daily_average.speech)
        self.assertEqual("-17.34 CNY", self.FACTS.balance_amount_text())
        self.assertEqual(
            (TapStep("首页"), TapStep("收支日历"), ScrollStep(self.FACTS.day_cell_text())), calendar.steps
        )
        self.assertEqual(
            ("2026-11-22，CNY 支出 17.34、收入 0.00；共 2 笔",), calendar.required
        )
        self.assertEqual(
            (
                SpeechCheck(
                    "2026-11-22，", ("2026-11-22", "CNY 支出 17.34", "收入 0.00")
                ),
            ),
            calendar.speech,
        )
        self.assertEqual(
            (ScrollStep(self.FACTS.day_cell_text()), TapStep(self.FACTS.day_cell_text())),
            detail.steps,
        )
        self.assertEqual(
            ("报表明细", "2026-11-22 明细", "共 2 笔明细", "-12.34 CNY", "-5.00 CNY"), detail.required
        )
        self.assertEqual(
            tuple(SpeechCheck(text, (text,)) for text in self.FACTS.payment_texts),
            detail.speech,
        )

    def test_the_period_line_follows_the_seed_month(self) -> None:
        february = P806SeedFacts("2028年2月", "2028-02-29", 2, "CNY", 2, 1_734, 0, ())
        self.assertEqual("月：2028-02-01 至 2028-02-29（29 天）", february.report_period_text())
        self.assertEqual(29, february.month_day_count())
        self.assertEqual(
            "日均按该期间完整自然日数（29 天）计算：进行中的期间不缩短分母。",
            february.daily_average_note_text(),
        )

    def test_a_seed_month_that_is_not_the_current_reporting_month_is_an_explicit_failure(self) -> None:
        from datetime import datetime, timezone, timedelta

        now = datetime(2026, 12, 1, 0, 30, tzinfo=timezone(timedelta(hours=8)))
        problem = p806_rollover_problem(self.FACTS, now)
        self.assertIsNotNone(problem)
        self.assertIn("2026-12", problem)
        self.assertIsNone(p806_rollover_problem(
            self.FACTS, datetime(2026, 11, 30, 23, 59, tzinfo=timezone(timedelta(hours=8)))))

    def test_the_p806_profile_is_seeded_and_dynamic(self) -> None:
        profile = profile_by_name("p806")
        self.assertTrue(profile.seeded)
        self.assertEqual("流水 · 共 2 笔", profile.launch_marker)
        self.assertEqual(
            ["report", "report-daily-average", "calendar", "day-detail"],
            [screen.stem for screen in profile.screens],
        )
        with self.assertRaises(SampleError):
            profile_screens(profile, "")

    def _write_seed(self, root: Path, *, mismatched: bool = False) -> Path:
        import sqlite3
        from datetime import datetime, timedelta, timezone

        seed = root / "ledger.db"
        noon = int(datetime(2026, 11, 22, 12, tzinfo=timezone(timedelta(hours=8))).timestamp()) * 1_000_000_000
        connection = sqlite3.connect(seed)
        try:
            connection.executescript(
                "CREATE TABLE ledger_transaction(transaction_id TEXT, ledger_id TEXT, kind TEXT);"
                "CREATE TABLE posting_set(posting_set_id TEXT, ledger_id TEXT);"
                "CREATE TABLE transaction_version(version_id TEXT, transaction_id TEXT, ledger_id TEXT, "
                "posting_set_id TEXT, statistics_at_epoch_nanos INTEGER);"
                "CREATE TABLE ledger_transaction_current_version(transaction_id TEXT, ledger_id TEXT, "
                "current_version_id TEXT);"
                "CREATE TABLE posting(posting_id TEXT, posting_set_id TEXT, ledger_id TEXT, account_id TEXT, "
                "amount_minor INTEGER, currency_code TEXT, currency_precision INTEGER);"
            )
            rows = [("0001", 1_234), ("0002", 500)]
            for row_index, (index, amount) in enumerate(rows):
                tx = f"tx-{index}"
                # The mismatched seed moves ONLY the second row one day earlier: the two rows then
                # span two local days, which is the shape `derive_p806_seed_facts` must reject.
                instant = noon
                if mismatched and row_index == 1:
                    instant = noon - 24 * 3_600_000_000_000
                connection.execute("INSERT INTO ledger_transaction VALUES (?, 'ledger-local-test', 'EXPENSE')", (tx,))
                connection.execute("INSERT INTO posting_set VALUES (?, 'ledger-local-test')", (f"{tx}-set",))
                connection.execute(
                    "INSERT INTO transaction_version VALUES (?, ?, 'ledger-local-test', ?, ?)",
                    (f"{tx}-version", tx, f"{tx}-set", instant),
                )
                connection.execute(
                    "INSERT INTO ledger_transaction_current_version VALUES (?, 'ledger-local-test', ?)",
                    (tx, f"{tx}-version"),
                )
                connection.execute(
                    "INSERT INTO posting VALUES (?, ?, 'ledger-local-test', 'expense-account-local', ?, 'CNY', 2)",
                    (f"{tx}-expense", f"{tx}-set", amount),
                )
                connection.execute(
                    "INSERT INTO posting VALUES (?, ?, 'ledger-local-test', 'asset-payment-local', ?, 'CNY', 2)",
                    (f"{tx}-payment", f"{tx}-set", -amount),
                )
            connection.commit()
        finally:
            connection.close()
        return seed

    def test_the_seed_facts_are_re_derived_from_the_database(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            facts = derive_p806_seed_facts(self._write_seed(Path(temp)))
        self.assertEqual(self.FACTS, facts)

    def test_a_seed_spanning_two_days_is_rejected(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            seed = self._write_seed(Path(temp), mismatched=True)
            with self.assertRaises(SampleError):
                derive_p806_seed_facts(seed)

    @staticmethod
    def _fragment_line(fragment: str) -> str:
        # The real brief line: outer tag `talkback`, speaker named in the message. This uses the
        # P7-derived controller form with no inner pid; the parenthesized form is exercised apart.
        return (
            "V/talkback( 20): SpeechControllerImpl: Speaking fragment text="
            f'"{fragment}", utteranceId=utt, TtsSpan=null, locale=zh_CN, event=TYPE_VIEW_FOCUSED'
        )

    @classmethod
    def _window(
        cls,
        fragments: list[str],
        *,
        command_id: int = 1,
        accepted: bool = True,
        target: str = "target",
        needle: str | None = None,
        node_text: str | None = None,
        node_desc: str | None = None,
        pre_begin: list[str] | None = None,
    ) -> str:
        raw_target = target if needle is None else needle
        encoded = base64.b64encode(raw_target.encode("utf-8")).decode("ascii")
        actual_text = target if node_text is None else node_text
        actual_desc = "" if node_desc is None else node_desc
        text64 = base64.b64encode(actual_text.encode("utf-8")).decode("ascii")
        desc64 = base64.b64encode(actual_desc.encode("utf-8")).decode("ascii")

        def preview(value: str) -> str:
            return value.replace("\n", " ")[:60]

        lines = [
            f"I/ULA11Y( 10): command action=focus id={command_id} needle64={encoded} "
            f'target="{target}"',
            f'I/ULA11Y( 10): target id={command_id} found=true text="{preview(actual_text)}" '
            f'desc="{preview(actual_desc)}" text64="{text64}" desc64="{desc64}"',
        ]
        lines.extend(cls._fragment_line(fragment) for fragment in pre_begin or [])
        lines.extend(
            [
                f"I/ULA11Y( 10): speech-window-begin id={command_id}",
                f"I/ULA11Y( 10): focus-result id={command_id} found=true "
                f"accepted={'true' if accepted else 'false'}",
            ]
        )
        lines.extend(cls._fragment_line(fragment) for fragment in fragments)
        lines.append(f"I/ULA11Y( 10): speech-window-end id={command_id}")
        return "\n".join(lines) + "\n"

    PROBE_COMPONENT = (
        "com.unifiedledger.android.test/"
        "com.unifiedledger.android.A11yFocusProbeService"
    )

    DEVICE_SERVICE = (
        "com.google.android.marvin.talkback/"
        "com.google.android.marvin.talkback.TalkBackService"
    )

    def _speech_fragments(self, stem: str, target: str) -> list[str]:
        if stem == "report":
            return ["结余", "-17.34", "CNY"]
        if stem == "calendar":
            return [self.FACTS.day_cell_text()]
        return [target]

    def _canonical_ids(self) -> dict[tuple[str, str], int]:
        """The run's global command sequence, one id per ordered speech check."""
        ids: dict[tuple[str, str], int] = {}
        for screen in p806_screens(self.FACTS):
            for check in screen.speech:
                ids[(screen.stem, check.target)] = len(ids) + 1
        return ids

    @classmethod
    def _block(
        cls,
        target: str,
        command_id: int,
        fragments: list[str],
        *,
        accepted: bool = True,
        needle: str | None = None,
        node_text: str | None = None,
        node_desc: str | None = None,
        pre_begin: list[str] | None = None,
    ) -> str:
        return (
            f"# target: {target}\n# command: {command_id}\n"
            + cls._window(
                fragments,
                command_id=command_id,
                accepted=accepted,
                target=target,
                needle=needle,
                node_text=node_text,
                node_desc=node_desc,
                pre_begin=pre_begin,
            )
        )

    def _write_windows(
        self,
        evidence: Path,
        *,
        override: dict[tuple[str, str], str] | None = None,
        services: list[str] | None = None,
    ) -> None:
        """Write one artifact per speech screen: every check in its own bound window.

        Each screen also keeps its own raw `enabled_accessibility_services` read-back, because the
        coordinator's gate re-derives the enabled TalkBack component per screen.
        """
        ids = self._canonical_ids()
        enabled_raw = ":".join(
            [self.PROBE_COMPONENT, self.DEVICE_SERVICE] if services is None else services
        )
        for screen in p806_screens(self.FACTS):
            if not screen.speech:
                continue
            blocks = []
            for check in screen.speech:
                key = (screen.stem, check.target)
                block = (override or {}).get(key)
                if block is None:
                    block = self._block(
                        check.target,
                        ids[key],
                        self._speech_fragments(screen.stem, check.target),
                    )
                blocks.append(block)
            (evidence / f"talkback-{screen.stem}.txt").write_text(
                "\n".join(blocks), encoding="utf-8"
            )
            (evidence / f"talkback-enabled-{screen.stem}.txt").write_text(
                enabled_raw + "\n", encoding="utf-8"
            )

    def _host(
        self,
        *,
        statuses: dict[tuple[str, str], str] | None = None,
        device_service: str | None = None,
        enabled_components: list[str] | None = None,
    ) -> dict[str, object]:
        ids = self._canonical_ids()
        device = self.DEVICE_SERVICE if device_service is None else device_service
        components = (
            [self.PROBE_COMPONENT, self.DEVICE_SERVICE]
            if enabled_components is None
            else enabled_components
        )
        recorded: dict[str, object] = {}
        for screen in p806_screens(self.FACTS):
            if not screen.speech:
                continue
            checks = []
            for check in screen.speech:
                status = (statuses or {}).get((screen.stem, check.target), "success")
                checks.append(
                    {
                        "target": check.target,
                        "command_id": ids[(screen.stem, check.target)],
                        "required": list(check.required),
                        "status": status,
                        "label": talkback_status_label(status),
                        "completed": True,
                        "accepted": True,
                        "fragments": [],
                        "missing": [],
                        "reason": "",
                    }
                )
            overall = overall_speech_status([check["status"] for check in checks])
            recorded[screen.stem] = {
                "status": overall,
                "label": talkback_status_label(overall),
                "device_service": device,
                "artifact": f"talkback-{screen.stem}.txt",
                "checks": checks,
                "enable": {
                    "status": "success",
                    "enabled_components": components,
                    "enabled_services_file": f"talkback-enabled-{screen.stem}.txt",
                    "talkback_bound": True,
                    "reason": "",
                },
            }
        return {
            "p806": {
                "seed_facts": self.FACTS.as_dict(),
                "talkback": {
                    "package": "com.google.android.marvin.talkback",
                    "device_service": device,
                    "log_level": {
                        "status": "success",
                        "value": "VERBOSE",
                        "steps": ["open the exported developer preferences fragment"],
                        "reason": "summary shows VERBOSE",
                    },
                    "screens": recorded,
                },
            },
        }

    def test_the_gate_accepts_complete_talkback_windows(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_the_gate_names_each_speech_failure_class(self) -> None:
        import tempfile

        target = self.FACTS.balance_text()
        report = ("report", target)
        cases = {
            "not-obtained": self._block(target, 1, [""], accepted=False),
            "fragments-missing": self._block(target, 1, []),
            "amount-wrong": self._block(target, 1, ["结余", "-99.99", "CNY"]),
        }
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            for status, window in cases.items():
                self._write_windows(evidence, override={report: window})
                host = self._host(statuses={report: status})
                problems = p806_gate_problems(host, self.FACTS, evidence)
                self.assertTrue(
                    any("report: TalkBack" in problem for problem in problems), (status, problems)
                )
                self.assertTrue(
                    any(talkback_status_label(status) in problem for problem in problems),
                    (status, problems),
                )
            # A host that claims success over a failing artifact is named twice.
            self._write_windows(evidence, override={report: cases["fragments-missing"]})
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(any("records TalkBack status 'success'" in p for p in problems))

    def test_the_calendar_window_requires_its_exact_amounts_not_just_the_date(self) -> None:
        import tempfile

        target = self.FACTS.day_date + "，"
        wrong_amounts = [
            "2026-11-22，CNY 支出 19.99、收入 0.00；共 2 笔",
            "2026-11-22，CNY 支出 17.34、收入 9.99；共 2 笔",
            "2026-11-22，支出 17.34、收入 0.00；共 2 笔",
            "2026-11-22，CNY 支出 17.34；共 2 笔",
        ]
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            for spoken in wrong_amounts:
                self._write_windows(
                    evidence,
                    override={("calendar", target): self._block(target, 2, [spoken])},
                )
                problems = p806_gate_problems(self._host(), self.FACTS, evidence)
                self.assertTrue(
                    any(
                        "calendar: TalkBack 金额错误" in problem
                        and repr(target) in problem
                        for problem in problems
                    ),
                    (spoken, problems),
                )
            # The exact seed-derived cell (date + both amounts + currency) passes.
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_a_partial_amount_does_not_satisfy_a_longer_number(self) -> None:
        import tempfile

        target = self.FACTS.day_date + "，"
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # `收入 0.00` occurs inside `收入 10.00`, and `支出 17.34` inside `支出 117.34`; the
            # digit on the edge means neither is the cell's own number.
            for spoken in (
                "2026-11-22，CNY 支出 17.34、收入 10.00；共 2 笔",
                "2026-11-22，CNY 支出 117.34、收入 0.00；共 2 笔",
                "2026-11-22，CNY 支出 17.345、收入 0.00；共 2 笔",
            ):
                self._write_windows(
                    evidence,
                    override={("calendar", target): self._block(target, 2, [spoken])},
                )
                problems = p806_gate_problems(self._host(), self.FACTS, evidence)
                self.assertTrue(
                    any("calendar: TalkBack 金额错误" in p for p in problems), (spoken, problems)
                )

    def test_a_failed_or_mixed_second_detail_row_cannot_borrow_the_first(self) -> None:
        import tempfile

        first, second = self.FACTS.payment_texts
        key = ("day-detail", second)
        ids = self._canonical_ids()
        second_id = ids[key]
        first_id = ids[("day-detail", first)]
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # The second row's own focus was rejected: its window must not inherit the first's
            # accepted flag or its spoken amount.
            rejected = self._block(second, second_id, [first], accepted=False)
            self._write_windows(evidence, override={key: rejected})
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(
                any("day-detail: TalkBack 未取得" in problem and repr(second) in problem
                    for problem in problems),
                problems,
            )
            # The second window is relabelled with the first row's command id: no id may serve
            # two checks, and the ordered sequence cannot go backwards.
            relabelled = self._block(second, first_id, [second])
            self._write_windows(evidence, override={key: relabelled})
            host = self._host()
            host["p806"]["talkback"]["screens"]["day-detail"]["checks"][1]["command_id"] = first_id
            problems = p806_gate_problems(host, self.FACTS, evidence)
            self.assertTrue(any("recorded twice" in problem for problem in problems), problems)
            self.assertTrue(any("does not follow" in problem for problem in problems), problems)
            # A window whose header names a different command than the recorded one is unbound.
            misbound = self._block(second, second_id, [second]).replace(
                f"# command: {second_id}\n", f"# command: {second_id + 100}\n", 1
            )
            self._write_windows(evidence, override={key: misbound})
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(
                any("does not bind command" in problem for problem in problems), problems
            )
            # Independent windows, each carrying its own amount, pass.
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_a_raw_probe_target_mismatch_fails_even_with_correct_headers(self) -> None:
        import tempfile

        first, second = self.FACTS.payment_texts
        key = ("day-detail", second)
        second_id = self._canonical_ids()[key]
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # Headers, host record, fragments and status all say the second row; only the probe's
            # own raw command still names the first row's target.
            self._write_windows(
                evidence,
                override={key: self._block(second, second_id, [second], needle=first)},
            )
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(any("raw command focused" in p for p in problems), problems)
            self.assertTrue(any("not the required target" in p for p in problems), problems)
            self.assertTrue(any("day-detail: TalkBack 未取得" in p for p in problems), problems)
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_a_raw_found_node_for_another_target_fails_even_with_correct_amounts(self) -> None:
        import tempfile

        first, second = self.FACTS.payment_texts
        key = ("day-detail", second)
        second_id = self._canonical_ids()[key]
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # Command target, header, fragments and logged amount all name the second row, but the
            # probe's found-node text is the first row's: the amount cannot belong to this target.
            self._write_windows(
                evidence,
                override={
                    key: self._block(second, second_id, [second], node_text=first)
                },
            )
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(any("found-node record" in p for p in problems), problems)
            self.assertTrue(any("day-detail: TalkBack 未取得" in p for p in problems), problems)
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_speech_before_the_window_begin_fails_even_with_all_required_values(self) -> None:
        import tempfile

        target = self.FACTS.balance_text()
        report = ("report", target)
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # Every required value is spoken, but only before `speech-window-begin`; the accepted
            # window itself is silent.
            self._write_windows(
                evidence,
                override={
                    report: self._block(
                        target, 1, [], pre_begin=["结余", "-17.34", "CNY"]
                    )
                },
            )
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(any("report: TalkBack 片段缺失" in p for p in problems), problems)
            self._write_windows(evidence)
            self.assertEqual([], p806_gate_problems(self._host(), self.FACTS, evidence))

    def test_the_report_window_rejects_a_same_named_month_card_node(self) -> None:
        import tempfile

        target = self.FACTS.balance_text()
        # The HOME month card ends `结余 -17.34` with no trailing currency. Its command target and
        # spoken amount/currency are otherwise valid, so only the found-node binding excludes it.
        month_card = f"{self.FACTS.currency}：普通收入 0.00，净支出 17.34，结余 -17.34"
        self.assertIn("结余 -17.34", month_card)
        self.assertNotIn(target, month_card)
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            self._write_windows(
                evidence,
                override={
                    ("report", target): self._block(
                        target, 1, ["结余", "-17.34", "CNY"], node_text=month_card
                    )
                },
            )
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(any("report: TalkBack 未取得" in p for p in problems), problems)
            self.assertTrue(any("found-node record" in p for p in problems), problems)

    def test_the_gate_requires_the_exact_enabled_talkback_component(self) -> None:
        import tempfile

        substituted = "com.google.android.marvin.talkback/.TalkBackService"
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # The host summary claims the device component, but the raw read-back never had it.
            self._write_windows(evidence, services=[self.PROBE_COMPONENT])
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(
                any("does not contain the exact TalkBack component" in problem
                    for problem in problems),
                problems,
            )
            self.assertTrue(
                any("does not match the raw record" in problem for problem in problems), problems
            )
            # The raw record contains the device component, but the host summary adds another.
            self._write_windows(evidence)
            summary_only = self._host(enabled_components=[
                self.PROBE_COMPONENT,
                self.DEVICE_SERVICE,
                "com.example.substitute/.TalkBackService",
            ])
            self.assertTrue(
                any("does not match the raw record" in problem
                    for problem in p806_gate_problems(summary_only, self.FACTS, evidence))
            )
            # A missing per-screen raw record fails even when everything else agrees.
            (evidence / "talkback-enabled-calendar.txt").unlink()
            problems = p806_gate_problems(self._host(), self.FACTS, evidence)
            self.assertTrue(
                any("'talkback-enabled-calendar.txt' is missing" in problem
                    for problem in problems),
                problems,
            )
            # The enable record must name that screen's own raw record.
            renamed = self._host()
            renamed["p806"]["talkback"]["screens"]["calendar"]["enable"][
                "enabled_services_file"
            ] = "talkback-enabled-report.txt"
            self.assertTrue(
                any("the screen's raw record is" in problem
                    for problem in p806_gate_problems(renamed, self.FACTS, evidence))
            )
            # A different component passes only when its own raw record proves it.
            self._write_windows(evidence, services=[substituted])
            self.assertEqual(
                [],
                p806_gate_problems(
                    self._host(
                        device_service=substituted, enabled_components=[substituted]
                    ),
                    self.FACTS,
                    evidence,
                ),
            )
            missing_enable = self._host()
            for record in missing_enable["p806"]["talkback"]["screens"].values():
                record["enable"] = {"status": "not-obtained", "reason": "not listed"}
            self.assertTrue(
                any("was not enabled" in problem
                    for problem in p806_gate_problems(missing_enable, self.FACTS, evidence))
            )

    def test_the_gate_rejects_stale_facts_or_a_missing_talkback_record(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            self._write_windows(evidence)
            stale = {"p806": {"seed_facts": P806SeedFacts(
                "2026年10月", "2026-10-22", 2, "CNY", 2, 1, 0).as_dict()}}
            self.assertTrue(any("seed derives" in p for p in p806_gate_problems(stale, self.FACTS, evidence)))
            without_talkback = {"p806": {"seed_facts": self.FACTS.as_dict()}}
            self.assertTrue(
                any("no TalkBack" in p for p in p806_gate_problems(without_talkback, self.FACTS, evidence))
            )

    def test_the_gate_checks_the_installable_artifact_identity(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            app = root / "app.apk"
            test = root / "test.apk"
            app.write_bytes(b"app")
            test.write_bytes(b"test")
            host = {
                "artifacts": {
                    "app_apk": {"sha256": __import__("hashlib").sha256(b"app").hexdigest()},
                    "test_apk": {"sha256": __import__("hashlib").sha256(b"test").hexdigest()},
                }
            }
            self.assertEqual([], gate_artifact_problems(host, app, test))
            test.write_bytes(b"other")
            self.assertTrue(gate_artifact_problems(host, app, test))
            self.assertTrue(gate_artifact_problems({"artifacts": {}}, app, test))

    def test_required_strings_are_checked_on_the_captured_frame(self) -> None:
        tree = '<hierarchy><node text="净支出（含退款） 17.34 CNY" content-desc="" /></hierarchy>'
        self.assertEqual([], required_problems(tree, ("净支出（含退款） 17.34 CNY",)))
        self.assertEqual(1, len(required_problems(tree, ("普通收入 0.00 CNY",))))


class TalkbackArtifacts(unittest.TestCase):
    """The D-292 speech parser: only TalkBack's own fragment line counts."""

    def test_fragments_are_extracted_in_log_order(self) -> None:
        log = (
            'V/talkback( 20): SpeechControllerImpl: Speaking fragment text="结余", '
            "utteranceId=a, TtsSpan=null, locale=zh_CN, event=x\n"
            "I/ULA11Y( 10): focus-result id=1 found=true accepted=true\n"
            'V/talkback( 20): SpeechControllerImpl: Speaking fragment text="-17.34", '
            "utteranceId=b, TtsSpan=null, locale=zh_CN, event=x\n"
            "V/talkback( 20): SpeechControllerImpl: Speaking fragment text=null, "
            "utteranceId=c, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["结余", "-17.34"], speech_fragments(log))

    def test_the_parenthesized_controller_pid_is_the_same_utterance(self) -> None:
        # The observed brief form names the controller's own pid inside the message; it is the
        # same TalkBack utterance as the P7-derived no-pid form and is extracted the same way.
        log = (
            'V/talkback( 20): SpeechControllerImpl( 20): Speaking fragment text="父级", '
            "utteranceId=a, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["父级"], speech_fragments(log))

    def test_a_speaking_line_with_another_tag_is_not_a_talkback_utterance(self) -> None:
        # A different logger whose MESSAGE quotes the line must not impersonate the tag column,
        # whether the controller form sits unquoted or is embedded as a real-looking brief line.
        log = (
            'V/SomeOtherLogger( 20): payload SpeechControllerImpl( 20): '
            'Speaking fragment text="伪造一", utteranceId=fake, event=x\n'
            'I/SomeOtherLogger( 20): V/talkback( 20): SpeechControllerImpl: '
            'Speaking fragment text="伪造二", utteranceId=fake, event=x\n'
            'V/talkback( 20): SpeechControllerImpl: Speaking fragment text="真实", '
            "utteranceId=real, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["真实"], speech_fragments(log))

    def test_a_window_is_isolated_by_its_command_id(self) -> None:
        log = P806Profile._window(["first"], command_id=1) + P806Profile._window(
            ["second"], command_id=2
        )
        window, problem = isolate_talkback_window(log, 2)
        self.assertEqual("", problem)
        self.assertTrue(any("command action=focus id=2" in line for line in window))
        self.assertTrue(any("target id=2 found=true" in line for line in window))
        self.assertTrue(any("id=2" in line for line in window))
        self.assertEqual(["second"], speech_fragments("\n".join(window)))
        self.assertNotIn("first", "\n".join(window))

    def test_a_missing_duplicated_or_interleaved_window_is_rejected(self) -> None:
        first = P806Profile._window(["first"], command_id=1)
        second = P806Profile._window(["second"], command_id=2)
        self.assertIn("never began", isolate_talkback_window(first, 2)[1])
        unclosed = "\n".join(
            line for line in first.splitlines() if "speech-window-end" not in line
        )
        self.assertIn("never ended", isolate_talkback_window(unclosed, 1)[1])
        self.assertIn(
            "began more than one window",
            isolate_talkback_window(unclosed + "\n" + first, 1)[1],
        )
        # The next command's begin/end while this window is still open is an interleaving.
        interleaved = unclosed + "\n" + second
        self.assertIn(
            "interleaves another command", isolate_talkback_window(interleaved, 1)[1]
        )
        # The interleaved command is still isolated to its own window.
        window, problem = isolate_talkback_window(interleaved, 2)
        self.assertEqual("", problem)
        self.assertEqual(["second"], speech_fragments("\n".join(window)))

    def test_the_four_verdicts(self) -> None:
        required = ("-12.34 CNY", "-5.00 CNY")
        self.assertEqual(
            "not-obtained",
            talkback_check_verdict(
                P806Profile._window([], accepted=False), 1, "target", required
            )[0],
        )
        self.assertEqual(
            "fragments-missing",
            talkback_check_verdict(P806Profile._window([]), 1, "target", required)[0],
        )
        self.assertEqual(
            "amount-wrong",
            talkback_check_verdict(
                P806Profile._window(["-12.34", "CNY"]), 1, "target", required
            )[0],
        )
        status, fragments, missing, _reason = talkback_check_verdict(
            P806Profile._window(["-12.34", "CNY", "-5.00", "CNY"]), 1, "target", required
        )
        self.assertEqual("success", status)
        self.assertEqual([], missing)
        self.assertEqual(["-12.34", "CNY", "-5.00", "CNY"], fragments)
        self.assertEqual("未取得", talkback_status_label("not-obtained"))
        self.assertEqual("片段缺失", talkback_status_label("fragments-missing"))
        self.assertEqual("金额错误", talkback_status_label("amount-wrong"))
        self.assertEqual("成功", talkback_status_label("success"))

    def test_a_window_whose_raw_command_names_another_target_is_rejected(self) -> None:
        required = ("-5.00 CNY",)
        log = P806Profile._window(["-5.00 CNY"], target="target", needle="-12.34 CNY")
        status, _fragments, _missing, reason = talkback_check_verdict(log, 1, "target", required)
        self.assertEqual("not-obtained", status)
        self.assertIn("-12.34 CNY", reason)
        self.assertIn("not the required target", reason)

    def test_a_found_node_whose_own_text_lacks_the_target_is_rejected(self) -> None:
        log = P806Profile._window(
            ["-5.00 CNY"], target="target", node_text="-12.34 CNY", node_desc="other"
        )
        status, _fragments, _missing, reason = talkback_check_verdict(
            log, 1, "target", ("-5.00 CNY",)
        )
        self.assertEqual("not-obtained", status)
        self.assertIn("found-node record", reason)

    def test_speech_only_before_the_window_begin_does_not_count(self) -> None:
        log = P806Profile._window([], target="target", pre_begin=["-5.00 CNY"])
        status, fragments, _missing, _reason = talkback_check_verdict(
            log, 1, "target", ("-5.00 CNY",)
        )
        self.assertEqual("fragments-missing", status)
        self.assertEqual([], fragments)

    def test_a_required_number_must_match_on_its_own_boundary(self) -> None:
        # `5.00` inside `15.00`, `-5.00` inside `-15.00` or `-5.001`, and `0.00` inside `10.00`
        # are not the spoken amount.
        for spoken in (["-15.00 CNY"], ["-5.001 CNY"], ["结余", "-17.34", "CNY"]):
            log = P806Profile._window(spoken)
            self.assertNotEqual(
                "success", talkback_check_verdict(log, 1, "target", ("-5.00 CNY",))[0], spoken
            )
        self.assertEqual(
            "success",
            talkback_check_verdict(
                P806Profile._window(["-15.00", "-5.00 CNY"]), 1, "target", ("-5.00 CNY",)
            )[0],
        )
        # Whitespace is layout, not meaning: `-5.00 CNY` spoken as two fragments still matches.
        self.assertEqual(
            "success",
            talkback_check_verdict(
                P806Profile._window(["-5.00", "CNY"]), 1, "target", ("-5.00 CNY",)
            )[0],
        )

    def test_the_overall_status_names_the_first_non_success_check(self) -> None:
        self.assertEqual("success", overall_speech_status(["success", "success"]))
        self.assertEqual(
            "amount-wrong", overall_speech_status(["success", "amount-wrong", "not-obtained"])
        )
        self.assertEqual("not-obtained", overall_speech_status(["not-obtained", "amount-wrong"]))

    def test_the_level_row_is_found_by_its_literal_summary(self) -> None:
        tree = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0"><node bounds="[0,0][1080,2400]">
  <node bounds="[0,600][1080,760]" text="Log output level" content-desc=""
        resource-id="com.google.android.marvin.talkback:id/title" />
  <node bounds="[0,760][1080,840]" text="ERROR" content-desc=""
        resource-id="com.google.android.marvin.talkback:id/summary" />
  <node bounds="[0,900][1080,1000]" text="VERBOSE" content-desc="" />
  <node bounds="[0,1800][540,1900]" text="OK" content-desc="" resource-id="android:id/button1" />
</node></hierarchy>"""
        self.assertEqual("ERROR", talkback_level_summary_value(tree))
        self.assertEqual((540, 800), find_talkback_level_summary(tree))
        self.assertEqual((270, 1850), find_resource_center(tree, "android:id/button1"))
        self.assertIsNone(find_resource_center(tree, "android:id/button2"))


if __name__ == "__main__":
    unittest.main()
