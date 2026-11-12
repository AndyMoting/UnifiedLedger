"""Offline proofs for the P8-00 V-8 / P8-01a / P8-02 / P8-03 font-scale sample rig (cloud AVD chain)."""
from __future__ import annotations

import unittest
from pathlib import Path

from android_fontscale.sampler import (
    BACK,
    DISMISS_IME,
    BackStep,
    DismissImeStep,
    InputTextStep,
    RadioStep,
    SampleError,
    Sampler,
    ScrollStep,
    TapStep,
    adb_input_text,
    describe_step,
    dismiss_ime,
    dump_contains,
    find_center,
    find_field_center,
    find_radio_center,
    find_reachable_center,
    gate_profile_problems,
    gate_seed_problems,
    ime_is_shown,
    input_text,
    marker_problems,
    parse_font_scale,
    profile_by_name,
    screen_center,
    scroll_until,
    scroll_swipe_points,
    tap_radio_for,
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

    def scroll_to(self, label: str, max_swipes: int = 6) -> str:
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


if __name__ == "__main__":
    unittest.main()
