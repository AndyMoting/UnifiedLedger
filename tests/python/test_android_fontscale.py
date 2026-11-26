"""Offline proofs for the P8-00 V-8 / P8-01a / P8-02 / P8-03 font-scale sample rig (cloud AVD chain)."""
from __future__ import annotations

import base64
import unittest
from pathlib import Path
from unittest.mock import patch

from android_fontscale.sampler import (
    BACK,
    DISMISS_IME,
    BackStep,
    DismissImeStep,
    InputTextStep,
    MIN_SCREENSHOT_BYTES,
    NOTIFICATION_DIALOG_QUIET_READS,
    NOTIFICATION_DIALOG_SETTLE_INTERVAL_S,
    NOTIFICATION_DIALOG_SETTLE_ROUNDS,
    P806SeedFacts,
    P806_TALKBACK_DOUBLE_TAP_INTERVAL_S,
    PERMISSION_DIALOG_GESTURE_ATTEMPTS,
    PERMISSION_DIALOG_READBACK_ATTEMPTS,
    PERMISSION_DIALOG_READBACK_INTERVAL_S,
    RadioStep,
    SampleError,
    Sampler,
    Screen,
    ScrollStep,
    SpeechCheck,
    TALKBACK_VERSION_ARTIFACT,
    TapStep,
    accessibility_services_bound,
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
    focus_retry_reason,
    gate_artifact_problems,
    gate_profile_problems,
    gate_seed_problems,
    ime_is_shown,
    input_text,
    isolate_talkback_window,
    marker_problems,
    notification_permission_dialog_identity,
    overall_speech_status,
    p806_gate_problems,
    p806_rollover_problem,
    p806_screens,
    package_version_name,
    parse_font_scale,
    parse_talkback_version_artifact,
    permission_deny_gesture,
    permission_deny_gesture_sequence,
    permission_deny_tap_command,
    permission_dialog_readback_delays,
    profile_by_name,
    profile_screens,
    probe_bound_service,
    probe_service_connected,
    records_talkback_evidence,
    required_problems,
    screen_center,
    scroll_until,
    scroll_swipe_points,
    speech_fragments,
    talkback_check_verdict,
    talkback_bound_service,
    talkback_gate_problems,
    talkback_level_summary_value,
    talkback_readiness,
    talkback_status_label,
    talkback_version_artifact,
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

# The observed API 33+ POST_NOTIFICATIONS grant dialog (permissioncontroller ids and message).
_PERMISSION_DIALOG = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0"><node bounds="[0,0][1080,2400]" text="" content-desc="">
  <node bounds="[0,900][1080,1500]" text="" resource-id="com.android.permissioncontroller:id/grant_dialog">
    <node bounds="[0,950][1080,1050]" text="Allow Android Accessibility Suite to send you notifications?" resource-id="com.android.permissioncontroller:id/permission_message" />
    <node bounds="[100,1350][500,1450]" text="Allow" resource-id="com.android.permissioncontroller:id/permission_allow_button" />
    <node bounds="[600,1350][1000,1450]" text="Don’t allow" resource-id="com.android.permissioncontroller:id/permission_deny_button" />
  </node>
</node></hierarchy>"""

_DIALOG_DISMISSED = (
    '<hierarchy rotation="0"><node bounds="[0,0][1080,2400]" text="首页" content-desc="" /></hierarchy>'
)

# The same dialog as the device actually rendered it: the node PACKAGE is the Google-signed
# permissioncontroller while the resource ids keep the `com.android.permissioncontroller` namespace.
# An identity that keyed on the package attribute would match this tree too, so the fixture must
# carry both or a future change could pass while rejecting the real dump.
_PERMISSION_DIALOG_REAL = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.google.android.permissioncontroller" content-desc="" bounds="[28,769][1052,1696]"><node index="0" text="" resource-id="com.android.permissioncontroller:id/grant_dialog" class="android.widget.LinearLayout" package="com.google.android.permissioncontroller" content-desc="" bounds="[28,769][1052,1696]"><node index="0" text="Allow Android Accessibility Suite to send you notifications?" resource-id="com.android.permissioncontroller:id/permission_message" class="android.widget.TextView" package="com.google.android.permissioncontroller" content-desc="" bounds="[133,990][947,1219]" /><node index="1" text="Don’t allow" resource-id="com.android.permissioncontroller:id/permission_deny_button" class="android.widget.Button" package="com.google.android.permissioncontroller" content-desc="" bounds="[133,1444][947,1591]" /></node></node></hierarchy>"""

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


class TalkbackProfileLimbs(unittest.TestCase):
    """P8-07 batch D (Q3/D2): which screens of the three screenshot profiles speak, and on what.

    The expected literals are the product's own copy, spelled here independently of the module's
    constants so a drift in either side fails this test.
    """

    EXPECTED: dict[str, list[tuple[str, str, tuple[str, ...]]]] = {
        "empty": [
            ("home", "账本为空，还没有任何交易。", ("账本为空，还没有任何交易。",)),
            ("asset", "总借入", ("总借入",)),
            ("analysis", "账本为空，还没有任何交易。", ("账本为空，还没有任何交易。",)),
        ],
        "seeded": [
            ("search-guide", "输入条件后开始搜索", ("输入条件后开始搜索",)),
            # The result row's own window must carry BOTH legs of the seed's two-leg transfer —
            # the seed's 1_234 minor units in CNY/2 as the row renders them, sign and code kept.
            # The credit leg spells its account name: a bare `12.34 CNY` also occurs inside the
            # debit leg's `-12.34 CNY`, so the name is what binds the amount to its own leg.
            (
                "search-results",
                "对账样本转账",
                ("对账样本转账", "-12.34 CNY", "对账样本账户 12.34 CNY"),
            ),
        ],
        "refund": [
            ("refund-entry", "支出腿金额 12.34", ("支出腿金额 12.34",)),
            ("refund-confirmation", "继承分类：早餐", ("继承分类：早餐",)),
        ],
    }

    def test_each_named_screen_carries_exactly_its_own_copy(self) -> None:
        for name, expected in self.EXPECTED.items():
            profile = profile_by_name(name)
            self.assertTrue(profile.talkback, name)
            recorded = [
                (screen.stem, check.target, check.required)
                for screen in profile.screens
                for check in screen.speech
            ]
            # One focus target per speaking screen, and the required strings are spelled here
            # independently of the module's constants: a narrowed requirement (the note without the
            # amount) or an extra one the screen never renders fails this table.
            self.assertEqual(expected, recorded)

    def test_the_unnamed_screens_stay_screenshot_only(self) -> None:
        seeded = {screen.stem: screen.speech for screen in profile_by_name("seeded").screens}
        self.assertEqual((), seeded["detail"])
        self.assertEqual((), seeded["reconciliation"])

    def test_only_the_three_screenshot_profiles_write_the_separate_record(self) -> None:
        p806 = profile_by_name("p806")
        self.assertTrue(p806.talkback)
        self.assertFalse(records_talkback_evidence(p806))
        for name in ("empty", "seeded", "refund"):
            self.assertTrue(records_talkback_evidence(profile_by_name(name)), name)


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
        # The real brief line: outer tag `talkback` (8 columns, so unpadded), pid right-justified
        # to 5, and the speaker named in the message (the P7-derived no-inner-pid form; the
        # parenthesized controller form is exercised apart).
        return (
            "V/talkback(   20): SpeechControllerImpl: Speaking fragment text="
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

        # AOSP brief: `%c/%-8.*s(%s%5d): ` — `ULA11Y` pads to 8 columns and pid 10 to width 5.
        probe = "I/ULA11Y  (   10): "
        lines = [
            probe + f"command action=focus id={command_id} needle64={encoded} "
            f'target="{target}"',
            probe + f'target id={command_id} found=true text="{preview(actual_text)}" '
            f'desc="{preview(actual_desc)}" text64="{text64}" desc64="{desc64}"',
        ]
        lines.extend(cls._fragment_line(fragment) for fragment in pre_begin or [])
        lines.extend(
            [
                probe + f"speech-window-begin id={command_id}",
                probe + f"focus-result id={command_id} found=true "
                f"accepted={'true' if accepted else 'false'}",
            ]
        )
        lines.extend(cls._fragment_line(fragment) for fragment in fragments)
        lines.append(probe + f"speech-window-end id={command_id}")
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


class TalkbackVersionArtifact(unittest.TestCase):
    """D4: the version is the package dump's own versionName, and the artifact round-trips it."""

    DUMP = (
        "Packages:\n"
        "  Package [com.google.android.marvin.talkback] (abc123):\n"
        "    userId=10123\n"
        "    versionCode=14000000\n"
        "    versionName=14.0.0.600000000\n"
        "    targetSdk=36\n"
    )

    def test_the_package_dumps_version_name_is_the_version(self) -> None:
        self.assertEqual("14.0.0.600000000", package_version_name(self.DUMP))

    def test_a_dump_without_a_version_name_yields_nothing(self) -> None:
        self.assertEqual(
            "",
            package_version_name(
                "Packages:\n  Package [com.google.android.marvin.talkback] (abc):\n"
                "    versionCode=1\n"
            ),
        )

    def test_the_recorded_artifact_round_trips_both_values(self) -> None:
        text = talkback_version_artifact(self.DUMP, "36\n")
        self.assertEqual(("14.0.0.600000000", "36"), parse_talkback_version_artifact(text))

    def test_an_empty_device_answer_records_no_values(self) -> None:
        self.assertEqual(
            ("", ""), parse_talkback_version_artifact(talkback_version_artifact("", ""))
        )


class TalkbackProfileGate(unittest.TestCase):
    """P8-07 batch D (Q3): the three profiles' record — version, API level and per-target pngs."""

    DUMP = TalkbackVersionArtifact.DUMP
    PROBE_COMPONENT = P806Profile.PROBE_COMPONENT
    DEVICE_SERVICE = P806Profile.DEVICE_SERVICE

    def _screens(self, profile_name: str = "empty") -> tuple[Screen, ...]:
        return profile_by_name(profile_name).screens

    def _ids(self, profile_name: str = "empty") -> dict[tuple[str, str], int]:
        ids: dict[tuple[str, str], int] = {}
        for screen in self._screens(profile_name):
            for check in screen.speech:
                ids[(screen.stem, check.target)] = len(ids) + 1
        return ids

    def _write(self, evidence: Path, profile_name: str = "empty") -> dict[str, object]:
        """One consistent record: the raw version artifact, every target window and every png."""
        ids = self._ids(profile_name)
        enabled_raw = f"{self.PROBE_COMPONENT}:{self.DEVICE_SERVICE}"
        recorded: dict[str, object] = {}
        for screen in self._screens(profile_name):
            if not screen.speech:
                continue
            blocks: list[str] = []
            checks: list[dict[str, object]] = []
            for check in screen.speech:
                command_id = ids[(screen.stem, check.target)]
                # Every string the table demands is spoken in this target's window, so a record
                # that drops one fails on the required-value line, not on its fragments.
                blocks.append(P806Profile._block(check.target, command_id, list(check.required)))
                shot = f"talkback-{screen.stem}-cmd-{command_id}.png"
                (evidence / shot).write_bytes(b"\0" * MIN_SCREENSHOT_BYTES)
                checks.append(
                    {
                        "target": check.target,
                        "command_id": command_id,
                        "screenshot": shot,
                        "required": list(check.required),
                        "status": "success",
                        "label": talkback_status_label("success"),
                        "completed": True,
                        "accepted": True,
                        "fragments": [check.target],
                        "missing": [],
                        "reason": "",
                    }
                )
            (evidence / f"talkback-{screen.stem}.txt").write_text(
                "\n".join(blocks), encoding="utf-8"
            )
            (evidence / f"talkback-enabled-{screen.stem}.txt").write_text(
                enabled_raw + "\n", encoding="utf-8"
            )
            recorded[screen.stem] = {
                "status": "success",
                "label": talkback_status_label("success"),
                "device_service": self.DEVICE_SERVICE,
                "artifact": f"talkback-{screen.stem}.txt",
                "checks": checks,
                "enable": {
                    "status": "success",
                    "enabled_components": [self.PROBE_COMPONENT, self.DEVICE_SERVICE],
                    "enabled_services_file": f"talkback-enabled-{screen.stem}.txt",
                    "talkback_bound": True,
                    "reason": "",
                },
            }
        (evidence / TALKBACK_VERSION_ARTIFACT).write_text(
            talkback_version_artifact(self.DUMP, "36"), encoding="utf-8"
        )
        return {
            "talkback": {
                "package": "com.google.android.marvin.talkback",
                "version": "14.0.0.600000000",
                "api_level": "36",
                "version_artifact": TALKBACK_VERSION_ARTIFACT,
                "device_service": self.DEVICE_SERVICE,
                "log_level": {
                    "status": "success",
                    "value": "VERBOSE",
                    "steps": ["open the exported developer preferences fragment"],
                    "reason": "summary shows VERBOSE",
                },
                "screens": recorded,
            },
        }

    def test_a_complete_record_passes(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            self.assertEqual([], talkback_gate_problems(host, self._screens(), evidence))

    def test_a_missing_record_is_named(self) -> None:
        self.assertEqual(
            ["host.json records no talkback record"],
            talkback_gate_problems({}, self._screens(), Path(".")),
        )

    def test_the_version_is_reparsed_from_the_raw_device_artifact(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            host["talkback"]["version"] = "13.0.0"
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("raw artifact says" in problem for problem in problems), problems)

    def test_an_artifact_without_a_usable_version_name_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            (evidence / TALKBACK_VERSION_ARTIFACT).write_text(
                talkback_version_artifact(
                    "Packages:\n  Package [x] (y):\n    versionName=null\n", "36"
                ),
                encoding="utf-8",
            )
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("no usable versionName" in problem for problem in problems))

    def test_a_missing_version_artifact_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            (evidence / TALKBACK_VERSION_ARTIFACT).unlink()
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("version artifact" in problem for problem in problems))

    def test_an_api_level_that_is_not_the_artifacts_own_value_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            host["talkback"]["api_level"] = "33"
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("device API level" in problem for problem in problems))

    def test_a_missing_target_screenshot_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            (evidence / "talkback-home-cmd-1.png").unlink()
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("has no screenshot" in problem for problem in problems), problems)

    def test_a_blank_target_screenshot_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            (evidence / "talkback-home-cmd-1.png").write_bytes(
                b"\0" * (MIN_SCREENSHOT_BYTES - 1)
            )
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("is blank" in problem for problem in problems), problems)

    def test_a_screenshot_not_named_by_its_target_id_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            host["talkback"]["screens"]["home"]["checks"][0]["screenshot"] = "home.png"
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(
                any("the target id names" in problem for problem in problems), problems
            )

    def test_a_failing_target_window_fails(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence)
            (evidence / "talkback-asset.txt").write_text(
                P806Profile._block("总借入", 2, []), encoding="utf-8"
            )
            problems = talkback_gate_problems(host, self._screens(), evidence)
            self.assertTrue(any("片段缺失" in problem for problem in problems), problems)

    def test_a_recorded_requirement_that_drops_the_amount_fails(self) -> None:
        """The required tuple is re-derived from the profile table, never trusted as recorded.

        The twin string is the target recorded as its own only requirement — what a host that
        dropped the signed amount would write while still naming the right row.
        """
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            host = self._write(evidence, "seeded")
            self.assertEqual([], talkback_gate_problems(host, self._screens("seeded"), evidence))
            checks = host["talkback"]["screens"]["search-results"]["checks"]
            checks[0]["required"] = [checks[0]["target"]]
            problems = talkback_gate_problems(host, self._screens("seeded"), evidence)
            self.assertEqual(
                [
                    "search-results: target '对账样本转账' records requirements "
                    "['对账样本转账'], the profile derives "
                    "['对账样本转账', '-12.34 CNY', '对账样本账户 12.34 CNY']"
                ],
                [problem for problem in problems if "records requirements" in problem],
            )


class TargetScreenshots(unittest.TestCase):
    """D5: each ordered target's command is followed by its own png, named by the command id."""

    def test_each_target_command_is_followed_by_its_own_screenshot(self) -> None:
        import tempfile

        targets = ["结余 -17.34 CNY", "-12.34 CNY"]

        class _ShotSampler(Sampler):
            def __init__(self, evidence: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.calls: list[str] = []

            def enable_talkback(self, stem: str) -> dict[str, object]:
                self.calls.append("enable")
                return {
                    "status": "success",
                    "enabled_components": ["component"],
                    "enabled_services_file": f"talkback-enabled-{stem}.txt",
                    "talkback_bound": True,
                    "reason": "",
                }

            def handle_notification_permission_dialog(self) -> bool:
                return False

            # The r4 readiness gate and prompt settle run before the first command and are stubbed
            # here, so this case keeps pinning the per-target png/command pairing alone.
            def wait_talkback_ready(self, stem: str) -> dict[str, object]:
                return {"status": "ready", "waited_s": 0.0, "polls": 1, "fragments": 1}

            def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
                return {"clears": 0, "quiet_reads": 2, "quiet_after_round": 2, "rounds": 2}

            def talkback_focus(self, target: str, command_id: int) -> dict[str, object]:
                self.calls.append(f"focus:{target}")
                return {
                    "id": command_id,
                    "target": target,
                    "completed": True,
                    "accepted": True,
                    "problem": "",
                    "lines": P806Profile._window(
                        [target], command_id=command_id, target=target
                    ).splitlines(),
                }

            def screenshot(self, stem: str) -> Path:
                self.calls.append(f"screenshot:{stem}")
                return self.evidence / f"{stem}.png"

            def disable_talkback_accessibility(self) -> None:
                pass

            def wait_accessibility_disabled(self) -> bool:
                return True

        with tempfile.TemporaryDirectory() as temp:
            sampler = _ShotSampler(Path(temp))
            screen = Screen(
                "day-detail",
                (),
                "共 2 笔明细",
                speech=tuple(SpeechCheck(target, (target,)) for target in targets),
            )
            record = sampler.p806_talkback_screen(screen, target_screenshots=True)
        self.assertEqual("success", record["status"])
        self.assertEqual(
            [
                "enable",
                "focus:结余 -17.34 CNY",
                "screenshot:talkback-day-detail-cmd-1",
                "focus:-12.34 CNY",
                "screenshot:talkback-day-detail-cmd-2",
            ],
            sampler.calls,
        )
        self.assertEqual(
            ["talkback-day-detail-cmd-1.png", "talkback-day-detail-cmd-2.png"],
            [check["screenshot"] for check in record["checks"]],
        )


class ReadinessGateOrder(unittest.TestCase):
    """QK-1: the rig runs enable -> ready -> settle -> its first command, in that order.

    The doubles that pin the per-target png pairing and the retry bound stub the two r4 gates
    without recording them, so deleting the readiness call from `p806_talkback_screen` — or moving
    it after the command loop — left every earlier case green. This double records each step name,
    so the recorded sequence itself is the assertion.
    """

    TARGETS = ("结余 -17.34 CNY", "-12.34 CNY")

    def test_the_rig_gates_readiness_and_settle_before_its_first_command(self) -> None:
        import tempfile

        class _OrderSampler(Sampler):
            def __init__(self, evidence: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.calls: list[str] = []

            def enable_talkback(self, stem: str) -> dict[str, object]:
                self.calls.append("enable")
                return {
                    "status": "success",
                    "enabled_components": ["component"],
                    "enabled_services_file": f"talkback-enabled-{stem}.txt",
                    "talkback_bound": True,
                    "reason": "",
                }

            def wait_talkback_ready(self, stem: str) -> dict[str, object]:
                self.calls.append("ready")
                return {"status": "ready", "waited_s": 0.0, "polls": 1, "fragments": 1}

            def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
                self.calls.append("settle")
                return {"clears": 0, "quiet_reads": 2, "quiet_after_round": 2, "rounds": 2}

            def handle_notification_permission_dialog(self) -> bool:
                self.calls.append("dialog")
                return False

            def talkback_focus(self, target: str, command_id: int) -> dict[str, object]:
                self.calls.append(f"focus:{target}")
                return {
                    "id": command_id,
                    "target": target,
                    "completed": True,
                    "accepted": True,
                    "problem": "",
                    "lines": P806Profile._window(
                        [target], command_id=command_id, target=target
                    ).splitlines(),
                }

            def disable_talkback_accessibility(self) -> None:
                pass

            def wait_accessibility_disabled(self) -> bool:
                return True

        with tempfile.TemporaryDirectory() as temp:
            sampler = _OrderSampler(Path(temp))
            screen = Screen(
                "day-detail",
                (),
                "共 2 笔明细",
                speech=tuple(SpeechCheck(target, (target,)) for target in self.TARGETS),
            )
            record = sampler.p806_talkback_screen(screen, target_screenshots=False)
        self.assertEqual("success", record["status"])
        # enable -> ready -> settle -> the first command, with the second command only after it: a
        # deleted readiness call loses "ready" here, a postponed one lands after the first focus.
        self.assertEqual(
            [
                "enable",
                "ready",
                "settle",
                "focus:结余 -17.34 CNY",
                "focus:-12.34 CNY",
            ],
            sampler.calls,
        )


class TalkbackReadyGate(unittest.TestCase):
    """T1: the first command waits for TalkBack's own utterance, and a timeout fails closed."""

    # The verbatim fragment row from the first real-device round's `talkback-home.txt`, where the
    # probe's command id=1 had already run (and been refused) before this announcement was spoken.
    _SPEAKING = (
        'V/talkback: SpeechControllerImpl( 5326): Speaking fragment text="TalkBack on", '
        "utteranceId=talkback_0, TtsSpan=null, locale=null, event=null\n"
    )
    # The window that round opened with: the probe's own passes, and no TalkBack utterance.
    _SILENT = (
        "I/ULA11Y  ( 5328): pass trigger=pass-1 root=null\n"
        "I/ULA11Y  ( 5328): pass trigger=pass-2 root=null\n"
    )

    def _sampler(self, evidence: Path, log_text: str) -> Sampler:
        class _ReadySampler(Sampler):
            def logcat_tail(self, line_count: int = 4000) -> str:
                return log_text

        return _ReadySampler(
            adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
        )

    def test_a_silent_window_fails_closed_with_the_wait_and_the_tail(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._sampler(evidence, self._SILENT)
            with patch("android_fontscale.sampler.TALKBACK_READY_TIMEOUT_S", 0.01):
                with patch("android_fontscale.sampler.TALKBACK_READY_POLL_INTERVAL_S", 0.0):
                    with self.assertRaises(SampleError) as raised:
                        sampler.wait_talkback_ready("home")
            message = str(raised.exception)
            self.assertIn("logged no utterance", message)
            self.assertIn("talkback-readiness-home.txt", message)
            artifact = (evidence / "talkback-readiness-home.txt").read_text(encoding="utf-8")
            self.assertIn("# readiness: timeout", artifact)
            self.assertIn("# talkback fragments: 0", artifact)
            self.assertIn("# waited:", artifact)
            self.assertIn("pass trigger=pass-1", artifact)

    def test_readiness_is_the_announcement_in_whatever_locale_it_is_spoken(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            self.assertEqual((True, ["TalkBack on"]), talkback_readiness(self._SPEAKING))
            ready = self._sampler(evidence, self._SPEAKING).wait_talkback_ready("home")
            self.assertEqual("ready", ready["status"])
            self.assertEqual(1, ready["fragments"])
            artifact = (evidence / "talkback-readiness-home.txt").read_text(encoding="utf-8")
            self.assertIn("# readiness: ready", artifact)
            self.assertIn("# fragment: TalkBack on", artifact)
            # The announcement is spoken in the device locale: the limb is "TalkBack spoke", so a
            # localized startup utterance is readiness too and no English literal is required.
            localized = (
                'V/talkback: SpeechControllerImpl( 5326): Speaking fragment '
                'text="TalkBack 已开启", utteranceId=talkback_0, TtsSpan=null, locale=zh_CN, '
                "event=null\n"
            )
            self.assertEqual(
                "ready", self._sampler(evidence, localized).wait_talkback_ready("home")["status"]
            )


class NotificationPromptSettle(unittest.TestCase):
    """T2: the prompt that follows TalkBack's announcement is cleared in bounded rounds."""

    class _SettleSampler(Sampler):
        def __init__(self, evidence: Path, answers: list[bool]) -> None:
            super().__init__(
                adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
            )
            self.answers = list(answers)
            self.reads = 0

        def handle_notification_permission_dialog(self) -> bool:
            self.reads += 1
            return self.answers.pop(0) if self.answers else False

    def test_a_prompt_that_only_appears_after_readiness_is_cleared_and_counted(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._SettleSampler(evidence, [True, False, False])
            with patch("android_fontscale.sampler.NOTIFICATION_DIALOG_SETTLE_INTERVAL_S", 0.0):
                record = sampler.settle_notification_permission_dialog("home")
            self.assertEqual(1, record["clears"])
            self.assertEqual(3, record["quiet_after_round"])
            self.assertEqual(3, sampler.reads)
            artifact = (evidence / "notification-dialog-home.txt").read_text(encoding="utf-8")
            self.assertIn("clears=1", artifact)
            self.assertIn("quiet_after_round=3", artifact)
            self.assertIn("round=1 prompt=true", artifact)
            self.assertIn("round=2 prompt=false", artifact)
            self.assertIn("round=3 prompt=false", artifact)

    def test_a_prompt_that_keeps_returning_stops_at_the_round_bound(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._SettleSampler(evidence, [True] * 8)
            with patch("android_fontscale.sampler.NOTIFICATION_DIALOG_SETTLE_INTERVAL_S", 0.0):
                record = sampler.settle_notification_permission_dialog("home")
            self.assertEqual(NOTIFICATION_DIALOG_SETTLE_ROUNDS, record["clears"])
            self.assertEqual(NOTIFICATION_DIALOG_SETTLE_ROUNDS, sampler.reads)
            self.assertIsNone(record["quiet_after_round"])
            artifact = (evidence / "notification-dialog-home.txt").read_text(encoding="utf-8")
            self.assertIn("quiet_after_round=still-present", artifact)
            self.assertIn(
                f"quiet_required={NOTIFICATION_DIALOG_QUIET_READS}", artifact
            )

    def test_the_round_bound_and_the_growing_interval_are_what_the_device_waits(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._SettleSampler(evidence, [True] * 8)
            # The interval is NOT patched to zero here: the sleeps the settle asks for are the
            # values the device really waits, so a smaller round bound or one flat wait changes the
            # recorded list. Only the sleeping itself is replaced, to keep the case instant.
            with patch("android_fontscale.sampler.time.sleep") as sleeper:
                record = sampler.settle_notification_permission_dialog("home")
            waits = [call.args[0] for call in sleeper.call_args_list]
            artifact = (evidence / "notification-dialog-home.txt").read_text(encoding="utf-8")
        self.assertEqual([2.0, 4.0, 6.0], waits)
        self.assertEqual(4, record["rounds"])
        self.assertEqual(4, sampler.reads)
        # The documented promise: at most 4 rounds, spaced 2 / 4 / 6 s.
        self.assertEqual(4, NOTIFICATION_DIALOG_SETTLE_ROUNDS)
        self.assertEqual(2.0, NOTIFICATION_DIALOG_SETTLE_INTERVAL_S)
        self.assertIn("space=2 s rounds=4 quiet_required=2", artifact)

    def test_two_consecutive_quiet_reads_stop_the_settle_short_of_the_bound(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._SettleSampler(evidence, [True, False, False, False, False])
            with patch("android_fontscale.sampler.time.sleep"):
                record = sampler.settle_notification_permission_dialog("home")
            artifact = (evidence / "notification-dialog-home.txt").read_text(encoding="utf-8")
        self.assertEqual(2, NOTIFICATION_DIALOG_QUIET_READS)
        self.assertEqual(3, record["quiet_after_round"])
        self.assertEqual(3, record["rounds"])
        self.assertEqual(3, sampler.reads)
        self.assertIn("quiet_reads=2", artifact)
        self.assertIn("quiet_after_round=3", artifact)
        # A fourth read would exist if the stop needed more quiet reads, or only if the bound ended
        # the loop.
        self.assertNotIn("round=4", artifact)

    def test_one_quiet_read_does_not_stop_the_settle(self) -> None:
        import tempfile

        # Alternating reads: every quiet one is broken by a returning prompt, so the counter never
        # reaches two in a row and only the round bound ends the loop.
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._SettleSampler(evidence, [False, True, False, True, True])
            with patch("android_fontscale.sampler.time.sleep"):
                record = sampler.settle_notification_permission_dialog("home")
        self.assertEqual(4, record["rounds"])
        self.assertEqual(4, sampler.reads)
        self.assertIsNone(record["quiet_after_round"])


class FocusRetryBound(unittest.TestCase):
    """T3: a refused focus is retried once, inside a named transient, as a whole command."""

    TARGET = "账本为空，还没有任何交易。"

    def _window(
        self, *, accepted: bool, command_id: int = 1, target: str | None = None
    ) -> dict[str, object]:
        needle = self.TARGET if target is None else target
        lines = P806Profile._window(
            [needle],
            command_id=command_id,
            accepted=accepted,
            target=needle,
            needle=needle,
            node_text=needle,
        ).splitlines()
        return {
            "id": command_id,
            "target": needle,
            "completed": True,
            "accepted": accepted,
            "problem": "",
            "lines": lines,
        }

    def test_the_retry_needs_a_named_transient(self) -> None:
        refused = self._window(accepted=False)
        self.assertEqual(
            "",
            focus_retry_reason(
                self._window(accepted=True),
                first_command=True,
                dialog_cleared=True,
                readiness_delayed=True,
            ),
        )
        self.assertEqual(
            "",
            focus_retry_reason(
                refused, first_command=False, dialog_cleared=False, readiness_delayed=True
            ),
        )
        self.assertIn(
            "notification prompt",
            focus_retry_reason(
                refused, first_command=False, dialog_cleared=True, readiness_delayed=False
            ),
        )
        self.assertIn(
            "finished starting",
            focus_retry_reason(
                refused, first_command=True, dialog_cleared=False, readiness_delayed=True
            ),
        )
        broken = dict(refused, problem="command id=1 began more than one window")
        self.assertEqual(
            "",
            focus_retry_reason(
                broken, first_command=True, dialog_cleared=True, readiness_delayed=True
            ),
        )

    def _sampler(self, evidence: Path, refusals: int) -> Sampler:
        class _RetrySampler(Sampler):
            def __init__(self, path: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=path, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.focus_ids: list[int] = []

            def enable_talkback(self, stem: str) -> dict[str, object]:
                return {
                    "status": "success",
                    "enabled_components": ["component"],
                    "enabled_services_file": f"talkback-enabled-{stem}.txt",
                    "talkback_bound": True,
                    "reason": "",
                }

            def wait_talkback_ready(self, stem: str) -> dict[str, object]:
                return {"status": "ready", "waited_s": 1.0, "polls": 1, "fragments": 1}

            def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
                # One clear is the transient a retry is allowed in: the prompt was just removed.
                return {"clears": 1, "quiet_reads": 2, "quiet_after_round": 3, "rounds": 3}

            def handle_notification_permission_dialog(self) -> bool:
                return False

            def talkback_focus(self, target: str, command_id: int) -> dict[str, object]:
                self.focus_ids.append(command_id)
                accepted = len(self.focus_ids) > refusals
                lines = P806Profile._window(
                    [target],
                    command_id=command_id,
                    accepted=accepted,
                    target=target,
                    needle=target,
                    node_text=target,
                ).splitlines()
                return {
                    "id": command_id,
                    "target": target,
                    "completed": True,
                    "accepted": accepted,
                    "problem": "",
                    "lines": lines,
                }

            def disable_talkback_accessibility(self) -> None:
                pass

            def wait_accessibility_disabled(self) -> bool:
                return True

        return _RetrySampler(evidence)

    def test_a_refused_window_is_retried_once_as_a_whole_command(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._sampler(evidence, refusals=1)
            screen = Screen(
                "home", (), "账本为空", speech=(SpeechCheck(self.TARGET, (self.TARGET,)),)
            )
            record = sampler.p806_talkback_screen(screen, target_screenshots=False)
            artifact = (evidence / "talkback-home.txt").read_text(encoding="utf-8")
        self.assertEqual([1, 2], sampler.focus_ids)
        self.assertEqual("success", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertIn("# retry=1 previous-command: 1", artifact)
        self.assertIn("# command: 2", artifact)
        # The verdict is still re-derived from the window that decided, and it is that window the
        # gate reads: the retry bought a command, never a looser criterion.
        status, fragments, missing, reason = talkback_check_verdict(
            artifact, 2, self.TARGET, (self.TARGET,)
        )
        self.assertEqual("success", status)
        self.assertEqual([self.TARGET], fragments)
        self.assertEqual([], missing)
        self.assertEqual("", reason)

    def test_a_second_refusal_is_not_retried_again(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._sampler(evidence, refusals=2)
            screen = Screen(
                "home", (), "账本为空", speech=(SpeechCheck(self.TARGET, (self.TARGET,)),)
            )
            record = sampler.p806_talkback_screen(screen, target_screenshots=False)
            artifact = (evidence / "talkback-home.txt").read_text(encoding="utf-8")
        self.assertEqual([1, 2], sampler.focus_ids)
        self.assertEqual("not-obtained", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(artifact.count("# retry=1"), 1)
        self.assertEqual(
            "not-obtained",
            talkback_check_verdict(artifact, 2, self.TARGET, (self.TARGET,))[0],
        )


class TalkbackArtifacts(unittest.TestCase):
    """The D-292 speech parser: only TalkBack's own fragment line counts."""

    def test_fragments_are_extracted_in_log_order(self) -> None:
        log = (
            'V/talkback(   20): SpeechControllerImpl: Speaking fragment text="结余", '
            "utteranceId=a, TtsSpan=null, locale=zh_CN, event=x\n"
            "I/ULA11Y  (   10): focus-result id=1 found=true accepted=true\n"
            'V/talkback(   20): SpeechControllerImpl: Speaking fragment text="-17.34", '
            "utteranceId=b, TtsSpan=null, locale=zh_CN, event=x\n"
            "V/talkback(   20): SpeechControllerImpl: Speaking fragment text=null, "
            "utteranceId=c, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["结余", "-17.34"], speech_fragments(log))

    def test_the_parenthesized_controller_pid_is_the_same_utterance(self) -> None:
        # The observed brief form names the controller's own pid inside the message; it is the
        # same TalkBack utterance as the P7-derived no-pid form and is extracted the same way.
        log = (
            'V/talkback(   20): SpeechControllerImpl( 20): Speaking fragment text="父级", '
            "utteranceId=a, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["父级"], speech_fragments(log))

    def test_a_speaking_line_with_another_tag_is_not_a_talkback_utterance(self) -> None:
        # A different logger whose MESSAGE quotes the line must not impersonate the tag column,
        # whether the controller form sits unquoted or is embedded as a real-looking brief line.
        log = (
            'V/SomeOtherLogger(   20): payload SpeechControllerImpl( 20): '
            'Speaking fragment text="伪造一", utteranceId=fake, event=x\n'
            'I/SomeOtherLogger(   20): V/talkback(   20): SpeechControllerImpl: '
            'Speaking fragment text="伪造二", utteranceId=fake, event=x\n'
            'V/talkback(   20): SpeechControllerImpl: Speaking fragment text="真实", '
            "utteranceId=real, TtsSpan=null, locale=zh_CN, event=x\n"
        )
        self.assertEqual(["真实"], speech_fragments(log))

    def test_padded_brief_readiness_is_recognized_and_mimics_are_not(self) -> None:
        # AOSP brief pads `ULA11Y` to 8 columns and the pid to 5: `I/ULA11Y  (   42): ...`.
        self.assertTrue(probe_service_connected("I/ULA11Y  (   42): service connected\n"))
        self.assertFalse(probe_service_connected("I/OtherTag(   42): service connected\n"))
        self.assertFalse(
            probe_service_connected(
                "I/OtherTag(   42): I/ULA11Y  (   42): service connected\n"
            )
        )
        self.assertFalse(
            probe_service_connected("trailing I/ULA11Y  (   42): service connected\n")
        )
        # Structural padding is horizontal; it must never bridge a line break.
        self.assertFalse(probe_service_connected("I/ULA11Y\n(   42): service connected\n"))

    def test_the_api36_bound_section_names_talkback_by_its_label(self) -> None:
        # The real talkback-accessibility.txt paragraph from run 45218212942: API 36 renders each
        # bound service by label only, so a package-name match alone can never see TalkBack.
        real = (
            "Bound services:{Service[label=UL a11y probe, feedbackType[FEEDBACK_GENERIC], "
            "capabilities=33, eventTypes=[TYPE_WINDOW_STATE_CHANGED, "
            "TYPE_WINDOW_CONTENT_CHANGED], notificationTimeout=100, requestA11yBtn=false], \n"
            "                     Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN, "
            "FEEDBACK_HAPTIC, FEEDBACK_AUDIBLE], capabilities=251, eventTypes=TYPES_ALL_MASK, "
            "notificationTimeout=0, requestA11yBtn=false]}\n"
        )
        self.assertTrue(talkback_bound_service(real))
        self.assertFalse(
            talkback_bound_service(
                "Bound services:{Service[label=UL a11y probe, "
                "feedbackType[FEEDBACK_GENERIC], capabilities=33]}\n"
            )
        )
        self.assertFalse(talkback_bound_service(""))
        # The package-name form remains the fallback for an image whose dump carries it.
        self.assertTrue(
            talkback_bound_service(
                "Bound services:\n"
                "  Service[label=TalkBack, package=com.google.android.marvin.talkback]\n"
            )
        )

    def test_the_disabled_wait_needs_both_bound_services_to_leave(self) -> None:
        import tempfile

        # The real label-only shape: the teardown has not reached the device until BOTH entries are
        # gone; matching on package names alone saw neither and returned immediately.
        both = (
            "Bound services:{Service[label=UL a11y probe, feedbackType[FEEDBACK_GENERIC], "
            "capabilities=33, eventTypes=[TYPE_WINDOW_STATE_CHANGED], notificationTimeout=100, "
            "requestA11yBtn=false], \n"
            "                     Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN], "
            "capabilities=251, eventTypes=TYPES_ALL_MASK, notificationTimeout=0, "
            "requestA11yBtn=false]}\n"
        )
        probe_only = both.replace(
            "Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN], capabilities=251, "
            "eventTypes=TYPES_ALL_MASK, notificationTimeout=0, requestA11yBtn=false]",
            "Service[label=Other, capabilities=0]",
        )
        self.assertNotEqual(both, probe_only)
        self.assertTrue(probe_bound_service(both))
        self.assertTrue(talkback_bound_service(both))
        self.assertTrue(accessibility_services_bound(both))
        self.assertTrue(probe_bound_service(probe_only))
        self.assertFalse(talkback_bound_service(probe_only))
        self.assertTrue(accessibility_services_bound(probe_only))
        # The package-name shape still counts as bound through the fallback.
        package_form = (
            "Bound services:\n"
            "  com.unifiedledger.android.test/.A11yFocusProbeService\n"
            "  com.google.android.marvin.talkback/.TalkBackService\n"
        )
        self.assertTrue(probe_bound_service(package_form))
        self.assertTrue(talkback_bound_service(package_form))
        self.assertTrue(accessibility_services_bound(package_form))
        self.assertFalse(accessibility_services_bound("Bound services:\n"))

        class _BoundSampler(Sampler):
            def __init__(self, evidence: Path, dump: str) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.dump_text = dump

            def accessibility_dumpsys(self) -> str:
                return self.dump_text

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            self.assertFalse(
                _BoundSampler(evidence, both).wait_accessibility_disabled(timeout_s=0.01)
            )
            self.assertTrue(
                _BoundSampler(evidence, "Bound services:\n").wait_accessibility_disabled(
                    timeout_s=1
                )
            )

    def test_structural_padding_never_bridges_a_line_break(self) -> None:
        self.assertEqual(
            [],
            speech_fragments(
                "V/talkback(   20):\n"
                "SpeechControllerImpl: Speaking fragment text=joined, utteranceId=1\n"
            ),
        )
        self.assertEqual(
            [],
            speech_fragments(
                "V/talkback\n(   20): SpeechControllerImpl: "
                "Speaking fragment text=joined, utteranceId=1\n"
            ),
        )

    def test_a_mid_message_probe_line_is_not_a_probe_command(self) -> None:
        encoded = base64.b64encode(b"target").decode("ascii")
        mimic = (
            'I/OtherTag(   10): I/ULA11Y  (   10): command action=focus id=1 '
            f'needle64={encoded} target="target"\n'
        )
        window, problem = isolate_talkback_window(mimic, 1)
        self.assertEqual([], window)
        self.assertIn("never began", problem)

    @staticmethod
    def _dialog_sampler(
        evidence: Path, frames: list[str], *, guard: bool = True
    ) -> Sampler:
        class _DialogSampler(Sampler):
            def __init__(self) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.frames = list(frames)
                self.commands: list[tuple[str, ...]] = []
                self.dump_stems: list[str] = []
                self.notification_dialog_guard = guard

            def adb(self, *args: str, **kwargs: object) -> str:
                self.commands.append(args)
                return ""

            def dump(self, stem: str) -> str:
                self.dump_stems.append(stem)
                return self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]

        return _DialogSampler()

    def test_the_accessibility_suite_notification_prompt_is_denied(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            sampler = self._dialog_sampler(
                evidence, [_PERMISSION_DIALOG, _DIALOG_DISMISSED]
            )
            self.assertTrue(sampler.handle_notification_permission_dialog())
            self.assertIn(("shell", "input", "tap", "800", "1400"), sampler.commands)
            self.assertEqual(
                ["permission-dialog", "permission-dialog-after"], sampler.dump_stems
            )
            self.assertTrue((evidence / "notification-dialog.txt").is_file())

    def test_a_different_permission_or_app_dialog_is_not_touched(self) -> None:
        import tempfile

        other_owner = _PERMISSION_DIALOG.replace(
            "com.android.permissioncontroller", "com.example.othercontroller"
        )
        other_message = _PERMISSION_DIALOG.replace(
            "Allow Android Accessibility Suite to send you notifications?",
            "Allow Example to access photos?",
        )
        for foreign in (other_owner, other_message):
            with tempfile.TemporaryDirectory() as temp:
                sampler = self._dialog_sampler(Path(temp), [foreign])
                self.assertFalse(sampler.handle_notification_permission_dialog())
                self.assertFalse(
                    any(
                        command[:3] == ("shell", "input", "tap")
                        for command in sampler.commands
                    )
                )

    def test_a_prompt_that_survives_the_deny_is_reported(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            sampler = self._dialog_sampler(
                Path(temp), [_PERMISSION_DIALOG, _PERMISSION_DIALOG]
            )
            # The alternating retry is part of the path; its real 0.8 s bound is asserted in
            # PermissionDialogGesture, so the failure here must not spend 3 x 4 x 0.8 s on it.
            with patch("android_fontscale.sampler.PERMISSION_DIALOG_READBACK_INTERVAL_S", 0.0):
                with self.assertRaises(SampleError):
                    sampler.handle_notification_permission_dialog()

    def test_the_prompt_is_not_touched_when_the_guard_is_off(self) -> None:
        # The guard is the profile switch now (P8-07 batch D, D6): every talkback profile turns it
        # on, so this pins the field's own off behaviour — no dump, no tap, no artifact.
        import tempfile

        with tempfile.TemporaryDirectory() as temp:
            sampler = self._dialog_sampler(
                Path(temp), [_PERMISSION_DIALOG], guard=False
            )
            self.assertFalse(sampler.handle_notification_permission_dialog())
            self.assertEqual([], sampler.dump_stems)

    def test_the_real_rendered_prompt_tree_is_recognized_by_its_ids(self) -> None:
        # The device renders package="com.google.android.permissioncontroller" with
        # com.android.permissioncontroller ids; the ids, not the package attribute, decide.
        self.assertTrue(notification_permission_dialog_identity(_PERMISSION_DIALOG_REAL))
        self.assertFalse(
            notification_permission_dialog_identity(
                _PERMISSION_DIALOG_REAL.replace(
                    "com.android.permissioncontroller:id/permission_message",
                    "com.example.other:id/permission_message",
                )
            )
        )

    def test_the_prompt_is_denied_before_the_first_swipe(self) -> None:
        import tempfile

        app_without = (
            '<hierarchy rotation="0"><node bounds="[0,0][1080,2400]" text="首页" '
            'content-desc="" /></hierarchy>'
        )
        app_with = (
            '<hierarchy rotation="0"><node bounds="[0,100][1080,200]" text="日均按该期间" '
            'content-desc="" /></hierarchy>'
        )

        class _ScrollSampler(Sampler):
            def __init__(self, evidence: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.frames = [
                    _PERMISSION_DIALOG,
                    _DIALOG_DISMISSED,
                    app_without,
                    app_without,
                    app_with,
                ]
                self.commands: list[tuple[str, ...]] = []
                self.notification_dialog_guard = True

            def adb(self, *args: str, **kwargs: object) -> str:
                self.commands.append(args)
                return ""

            def dump(self, stem: str) -> str:
                return self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]

        with tempfile.TemporaryDirectory() as temp:
            sampler = _ScrollSampler(Path(temp))
            self.assertEqual(app_with, sampler.scroll_to("日均按该期间"))
            kinds = [command[:3] for command in sampler.commands]
            swipe = next(i for i, kind in enumerate(kinds) if kind == ("shell", "input", "swipe"))
            tap = next(i for i, kind in enumerate(kinds) if kind == ("shell", "input", "tap"))
            self.assertLess(tap, swipe)

    def test_the_api36_brief_row_names_talkback_without_a_tag_pid(self) -> None:
        # The verbatim rows from run 45218212942's talkback-report.txt: API 36 emits
        # `V/talkback:` with no pid after the tag; the controller pid stays in the message.
        log = (
            'V/talkback: SpeechControllerImpl( 5884): Speaking fragment text="TalkBack on", '
            "utteranceId=talkback_0, TtsSpan=null, locale=null, event=null\n"
            'V/talkback: SpeechControllerImpl( 5884): Speaking fragment text="UnifiedLedger", '
            "utteranceId=talkback_1, TtsSpan=null, locale=null, "
            "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_WINDOWS_CHANGED displayId:0 "
            "time:262253\n"
            'V/talkback: SpeechControllerImpl( 5884): Speaking fragment text="结余 -17.34 CNY", '
            "utteranceId=talkback_2, TtsSpan=null, locale=en_US, "
            "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_VIEW_ACCESSIBILITY_FOCUSED "
            "displayId:0 time:263316\n"
        )
        self.assertEqual(
            ["TalkBack on", "UnifiedLedger", "结余 -17.34 CNY"], speech_fragments(log)
        )

    def test_the_fragment_row_keeps_its_tag_and_null_rules(self) -> None:
        self.assertEqual(
            [],
            speech_fragments(
                "V/talkback: SpeechControllerImpl( 5884): Speaking fragment text=null, "
                "utteranceId=quiet, TtsSpan=null, locale=null, event=null\n"
            ),
        )
        self.assertEqual(
            [],
            speech_fragments(
                'V/other: SpeechControllerImpl( 5884): Speaking fragment text="伪造", '
                "utteranceId=fake, TtsSpan=null, locale=null, event=null\n"
            ),
        )
        # A different tag that merely quotes the real row is not TalkBack's own line.
        self.assertEqual(
            [],
            speech_fragments(
                'I/ULA11Y  ( 5888): V/talkback: SpeechControllerImpl( 5884): '
                'Speaking fragment text="mid-message", utteranceId=fake\n'
            ),
        )

    def test_the_bind_wait_clears_the_prompt_right_after_the_service_starts(self) -> None:
        import tempfile

        class _EnableSampler(Sampler):
            def __init__(self, evidence: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.talkback_component = (
                    "com.google.android.marvin.talkback/.TalkBackService"
                )
                self.calls: list[str] = []

            def adb(self, *args: str, **kwargs: object) -> str:
                if args[:3] == ("shell", "settings", "get"):
                    return (
                        "com.unifiedledger.android.test/"
                        "com.unifiedledger.android.A11yFocusProbeService:"
                        f"{self.talkback_component}\n"
                    )
                return ""

            def logcat_tail(self, line_count: int = 4000) -> str:
                return "I/ULA11Y  (   42): service connected\n"

            def accessibility_dumpsys(self) -> str:
                # The API 36 shape: bound services by label only, no package name anywhere.
                return (
                    "Bound services:{Service[label=UL a11y probe, "
                    "feedbackType[FEEDBACK_GENERIC], capabilities=33, "
                    "eventTypes=[TYPE_WINDOW_STATE_CHANGED], notificationTimeout=100, "
                    "requestA11yBtn=false], \n"
                    "                     Service[label=TalkBack, "
                    "feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=251, "
                    "eventTypes=TYPES_ALL_MASK, notificationTimeout=0, requestA11yBtn=false]}\n"
                )

            def handle_notification_permission_dialog(self) -> bool:
                self.calls.append("dialog")
                return False

        with tempfile.TemporaryDirectory() as temp:
            sampler = _EnableSampler(Path(temp))
            record = sampler.enable_talkback("report")
            self.assertEqual("success", record["status"])
            self.assertTrue(record["talkback_bound"])
            self.assertGreaterEqual(len(sampler.calls), 1)

    def test_the_speech_leg_clears_the_prompt_before_its_first_focus(self) -> None:
        import tempfile

        class _SpeechSampler(Sampler):
            def __init__(self, evidence: Path) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.calls: list[str] = []

            def handle_notification_permission_dialog(self) -> bool:
                self.calls.append("dialog")
                return False

            # The r4 gates are stubbed to their no-op outcome here: this case pins the ORDER of
            # enable -> prompt clear -> first focus, and the readiness/settle seams have their own
            # cases below.
            def wait_talkback_ready(self, stem: str) -> dict[str, object]:
                return {"status": "ready", "waited_s": 0.0, "polls": 1, "fragments": 1}

            def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
                self.handle_notification_permission_dialog()
                return {"clears": 0, "quiet_reads": 2, "quiet_after_round": 2, "rounds": 2}

            def enable_talkback(self, stem: str) -> dict[str, object]:
                self.calls.append("enable")
                return {
                    "status": "success",
                    "enabled_components": ["component"],
                    "enabled_services_file": "f",
                    "talkback_bound": True,
                    "reason": "",
                }

            def talkback_focus(self, target: str, command_id: int) -> dict[str, object]:
                self.calls.append(f"focus:{target}")
                lines = P806Profile._window(
                    ["结余 -17.34 CNY"],
                    command_id=command_id,
                    target=target,
                    needle=target,
                    node_text=target,
                ).splitlines()
                return {
                    "id": command_id,
                    "target": target,
                    "completed": True,
                    "accepted": True,
                    "problem": "",
                    "lines": lines,
                }

            def disable_talkback_accessibility(self) -> None:
                pass

            def wait_accessibility_disabled(self) -> bool:
                return True

        with tempfile.TemporaryDirectory() as temp:
            sampler = _SpeechSampler(Path(temp))
            screen = Screen(
                "report",
                (),
                "结余",
                speech=(SpeechCheck("结余 -17.34 CNY", ("-17.34 CNY",)),),
            )
            record = sampler.p806_talkback_screen(screen, target_screenshots=False)
            self.assertEqual("success", record["status"])
            # The dynamic p806 record keeps its batch-6 shape: no per-target screenshot key, because
            # only the Q3 profiles (P8-07 batch D) capture one per target.
            self.assertNotIn("screenshot", record["checks"][0])
            self.assertEqual("enable", sampler.calls[0])
            self.assertEqual("dialog", sampler.calls[1])
            self.assertTrue(sampler.calls[2].startswith("focus:"))

    def test_readiness_needs_the_probe_acknowledgement_not_a_bound_listing(self) -> None:
        import tempfile

        class _ReadinessSampler(Sampler):
            def __init__(self, evidence: Path, log_text: str) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.log_text = log_text

            def logcat_tail(self, line_count: int = 4000) -> str:
                return self.log_text

            def accessibility_dumpsys(self) -> str:
                return (
                    "Bound services:\n"
                    "  Service[label=UL a11y probe, "
                    "package=com.unifiedledger.android.test]\n"
                )

        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp)
            # A bound-service line naming the package is not the probe's acknowledgement.
            silent = _ReadinessSampler(evidence, "I/OtherTag(   42): service connected\n")
            self.assertFalse(silent.wait_probe_connected(timeout_s=0.01))
            self.assertIn(
                "UL a11y probe",
                (evidence / "readiness-accessibility.txt").read_text(encoding="utf-8"),
            )
            self.assertIn(
                "service connected",
                (evidence / "readiness-logcat.txt").read_text(encoding="utf-8"),
            )
            # The probe's own exact, real-format acknowledgement is what decides readiness.
            ack = _ReadinessSampler(evidence, "I/ULA11Y  (   42): service connected\n")
            self.assertTrue(ack.wait_probe_connected(timeout_s=0.01))

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


class PermissionDialogGesture(unittest.TestCase):
    """V-12: the deny is an on-device gesture with a bounded, alternating retry.

    Run 45210967652 denied the prompt once while TalkBack was enabled and read the same dialog back;
    run 45226637611 failed the same way after the double tap had already worked in run 45218212942,
    so the gesture is timing-sensitive rather than wrong, and a surviving prompt is retried with the
    other gesture instead of failing on one explanation.
    """

    _PROBE = "com.unifiedledger.android.test/com.unifiedledger.android.A11yFocusProbeService"
    _TALKBACK = "com.google.android.marvin.talkback/.TalkBackService"
    # The deny button's centre in the real rendered prompt (`permission-dialog.xml`).
    _CENTER = (540, 1517)

    @staticmethod
    def _prompt_sampler(evidence: Path) -> Sampler:
        class _PromptSampler(Sampler):
            def __init__(self) -> None:
                super().__init__(
                    adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-fontscale"
                )
                self.talkback_component = PermissionDialogGesture._TALKBACK
                self.notification_dialog_guard = True
                self.enabled_services = "null"
                # 1-based readback number at which the prompt is gone; None means never.
                self.closes_on_readback: int | None = 1
                self.readbacks = 0
                self.commands: list[tuple[str, ...]] = []
                self.dump_stems: list[str] = []

            def adb(self, *args: str, **kwargs: object) -> str:
                self.commands.append(args)
                if args[:5] == (
                    "shell",
                    "settings",
                    "get",
                    "secure",
                    "enabled_accessibility_services",
                ):
                    return self.enabled_services + "\n"
                return ""

            def dump(self, stem: str) -> str:
                self.dump_stems.append(stem)
                if stem == "permission-dialog":
                    return _PERMISSION_DIALOG_REAL
                self.readbacks += 1
                if (
                    self.closes_on_readback is not None
                    and self.readbacks >= self.closes_on_readback
                ):
                    return _DIALOG_DISMISSED
                return _PERMISSION_DIALOG_REAL

        return _PromptSampler()

    def test_a_talkback_service_sends_the_double_tap_as_one_adb_call(self) -> None:
        import tempfile

        services = f"{self._PROBE}:{self._TALKBACK}"
        self.assertEqual("double", permission_deny_gesture(services, self._TALKBACK))
        self.assertLess(P806_TALKBACK_DOUBLE_TAP_INTERVAL_S, 0.15)
        expected = (
            "shell",
            "input",
            "tap",
            "540",
            "1517",
            ";",
            "sleep",
            str(P806_TALKBACK_DOUBLE_TAP_INTERVAL_S),
            ";",
            "input",
            "tap",
            "540",
            "1517",
        )
        self.assertEqual(
            expected, ("shell",) + permission_deny_tap_command(self._CENTER, "double")
        )
        with tempfile.TemporaryDirectory() as temp:
            sampler = self._prompt_sampler(Path(temp))
            sampler.enabled_services = services
            self.assertTrue(sampler.handle_notification_permission_dialog())
        taps = [command for command in sampler.commands if command[:3] == ("shell", "input", "tap")]
        # ONE adb call carries both taps, so the 120 ms gap never includes an adb round-trip.
        self.assertEqual([expected], taps)

    def test_without_talkback_the_deny_stays_a_single_press(self) -> None:
        import tempfile

        self.assertEqual("single", permission_deny_gesture("null", self._TALKBACK))
        # Only the focus probe enabled is still ordinary touch: TalkBack is not the service there.
        self.assertEqual("single", permission_deny_gesture(self._PROBE, self._TALKBACK))
        expected = ("shell", "input", "tap", "540", "1517")
        self.assertEqual(
            expected, ("shell",) + permission_deny_tap_command(self._CENTER, "single")
        )
        with tempfile.TemporaryDirectory() as temp:
            sampler = self._prompt_sampler(Path(temp))
            self.assertTrue(sampler.handle_notification_permission_dialog())
        taps = [command for command in sampler.commands if command[:3] == ("shell", "input", "tap")]
        self.assertEqual([expected], taps)

    def test_the_gesture_sequence_alternates_from_either_start(self) -> None:
        self.assertEqual(3, PERMISSION_DIALOG_GESTURE_ATTEMPTS)
        self.assertEqual(
            ("double", "single", "double"), permission_deny_gesture_sequence("double")
        )
        self.assertEqual(
            ("single", "double", "single"), permission_deny_gesture_sequence("single")
        )

    def test_the_other_gesture_closes_a_prompt_that_outlived_the_first(self) -> None:
        import tempfile

        services = f"{self._PROBE}:{self._TALKBACK}"
        with patch("android_fontscale.sampler.PERMISSION_DIALOG_READBACK_INTERVAL_S", 0.0):
            with tempfile.TemporaryDirectory() as temp:
                evidence = Path(temp)
                sampler = self._prompt_sampler(evidence)
                sampler.enabled_services = services
                sampler.closes_on_readback = PERMISSION_DIALOG_READBACK_ATTEMPTS + 1
                self.assertTrue(sampler.handle_notification_permission_dialog())
                diagnostic = (evidence / "permission-dialog-gesture.txt").read_text(
                    encoding="utf-8"
                )
        self.assertEqual(5, sampler.readbacks)
        self.assertEqual(
            [
                f"enabled_accessibility_services={services}",
                "attempt=1 gesture=double closed=false",
                "attempt=2 gesture=single closed=true",
            ],
            diagnostic.splitlines(),
        )
        taps = [command for command in sampler.commands if command[:3] == ("shell", "input", "tap")]
        self.assertEqual(2, len(taps))
        self.assertEqual(("shell", "input", "tap", "540", "1517"), taps[1])

    def test_a_prompt_that_survives_every_gesture_names_both_dumps(self) -> None:
        import tempfile

        plan = permission_dialog_readback_delays()
        self.assertEqual(PERMISSION_DIALOG_READBACK_ATTEMPTS, len(plan))
        self.assertLessEqual(max(plan), PERMISSION_DIALOG_READBACK_INTERVAL_S)
        self.assertLessEqual(PERMISSION_DIALOG_READBACK_INTERVAL_S, 0.8)
        services = f"{self._PROBE}:{self._TALKBACK}"
        with patch("android_fontscale.sampler.PERMISSION_DIALOG_READBACK_INTERVAL_S", 0.0):
            with tempfile.TemporaryDirectory() as temp:
                evidence = Path(temp)
                sampler = self._prompt_sampler(evidence)
                sampler.enabled_services = services
                sampler.closes_on_readback = None
                with self.assertRaises(SampleError) as raised:
                    sampler.handle_notification_permission_dialog()
                diagnostic = (evidence / "permission-dialog-gesture.txt").read_text(
                    encoding="utf-8"
                )
        message = str(raised.exception)
        self.assertIn("permission-dialog-after.xml", message)
        self.assertIn("permission-dialog.xml", message)
        self.assertIn("3 gestures", message)
        self.assertEqual(
            PERMISSION_DIALOG_GESTURE_ATTEMPTS * PERMISSION_DIALOG_READBACK_ATTEMPTS,
            sampler.readbacks,
        )
        self.assertEqual(
            [
                f"enabled_accessibility_services={services}",
                "attempt=1 gesture=double closed=false",
                "attempt=2 gesture=single closed=false",
                "attempt=3 gesture=double closed=false",
            ],
            diagnostic.splitlines(),
        )


if __name__ == "__main__":
    unittest.main()
