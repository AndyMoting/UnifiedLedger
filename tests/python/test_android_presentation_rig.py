"""D-369 E1 presentation rig configuration proofs (no device, no emulator, no Gradle)."""
from __future__ import annotations

import base64
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from android_fontscale.sampler import (
    ACCOUNT_FLOW_HEADER,
    BACK,
    BackStep,
    DEFAULT_UI_MODE,
    P8_00_ASSET_TARGET,
    P806_REPORT_TITLE,
    PRESENTATION_CALENDAR_EMPTY_MARKER,
    PRESENTATION_CALENDAR_EMPTY_TITLE,
    PRESENTATION_CALENDAR_EMPTY_VISIBLE_ABSENT,
    PRESENTATION_CATEGORY_DAILY,
    PRESENTATION_CATEGORY_FOOD,
    PRESENTATION_CATEGORY_FOOD_LUNCH,
    PRESENTATION_CATEGORY_TRANSPORT,
    PRESENTATION_CATEGORY_TRANSPORT_BREAKFAST,
    PRESENTATION_CATEGORY_TRAVEL,
    PRESENTATION_CATEGORY_TRAVEL_FLIGHT,
    PRESENTATION_CROSS_DAY_ACCOUNT,
    PRESENTATION_CROSS_DAY_AMOUNT_B,
    PRESENTATION_CROSS_DAY_DATE_A,
    PRESENTATION_CROSS_DAY_DATE_B,
    PRESENTATION_CROSS_DAY_NOTE_B,
    PRESENTATION_CROSS_DAY_QUEUE_COUNT,
    PRESENTATION_CROSS_DAY_ROW_A,
    PRESENTATION_CROSS_DAY_ROW_B,
    PRESENTATION_CROSS_DAY_UTC_ERROR,
    PRESENTATION_EVIDENCE_PLAN,
    PRESENTATION_HOME_COUNT,
    PRESENTATION_MANAGEMENT_ABSENT,
    PRESENTATION_MANAGEMENT_ACCOUNTS,
    PRESENTATION_MANAGEMENT_CATEGORIES,
    PRESENTATION_MANAGEMENT_MARKER,
    PRESENTATION_MANAGEMENT_STEMS,
    PRESENTATION_MANAGEMENT_TITLE,
    PRESENTATION_MULTI_CATEGORY_LEDGER_ID,
    PRESENTATION_MULTI_CATEGORY_NOTE,
    PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID,
    PRESENTATION_MULTI_CATEGORY_REMAINING,
    PRESENTATION_MULTI_CATEGORY_TRANSACTION_ID,
    PRESENTATION_MULTI_CATEGORY_VERSION_ID,
    PRESENTATION_MULTI_CURRENCY_MARKER,
    PRESENTATION_MULTI_CURRENCY_NOTE,
    PRESENTATION_PROFILE_NAME,
    PRESENTATION_RANKING_MARKER,
    PRESENTATION_RANKING_TEXT,
    PRESENTATION_SCENARIO_STEMS,
    PRESENTATION_SCREENS,
    PRESENTATION_TECHNICAL_COLLAPSED,
    PRESENTATION_TECHNICAL_EXPANDED,
    PRESENTATION_TECHNICAL_EXPAND_LABEL,
    PRESENTATION_TECHNICAL_HEADER,
    PRESENTATION_TECHNICAL_ROW_COUNT,
    PROFILES,
    RECONCILIATION_TITLE,
    SEEDED_ACCOUNT_NAME,
    SEEDED_QUEUE_MARKER,
    Sampler,
    SampleError,
    ScrollStep,
    Screen,
    SpeechCheck,
    TALKBACK_COMMAND_ATTEMPTS,
    TALKBACK_FOCUS_RETRY_BUDGET,
    TALKBACK_LOST_COMMAND_RETRY_BUDGET,
    TALKBACK_NEVER_ENDED_RETRY_BUDGET,
    TALKBACK_SILENT_RETRY_BUDGET,
    TapStep,
    UI_MODE_READBACK_ATTEMPTS,
    UI_MODE_READBACK_INTERVAL_S,
    UI_MODES,
    gate_evidence_plan_problems,
    gate_profile_problems,
    gate_ui_mode_problems,
    isolate_talkback_window,
    marker_problems,
    profile_by_name,
    screen_problems,
    speech_fragments,
    speech_warmup_screen,
    talkback_check_verdict,
    talkback_lost_command_retry_reason,
    talkback_never_ended_retry_reason,
    talkback_readiness,
    talkback_silent_retry_reason,
    talkback_window_began,
    talkback_window_never_ended,
    ui_mode_apply_and_readback,
    ui_mode_night_value,
    ui_mode_readback_problem,
    visible_absent_problems,
)

PRESENTATION_STEMS = [
    "presentation-home",
    "presentation-search-results",
    "presentation-ordinary-expense",
    "presentation-detail",
    "presentation-technical-collapsed",
    "presentation-technical-expanded",
    "presentation-multiple-categories",
    "presentation-multi-currency",
    "presentation-asset",
    "presentation-account-flow",
    "presentation-reconciliation",
    "presentation-reconciliation-cross-day",
    "presentation-catalog-management",
    "presentation-catalog-categories-upper",
    "presentation-catalog-categories-middle",
    "presentation-catalog-categories-lower",
    "presentation-analysis",
    "presentation-calendar-empty",
]

# The presentation seed's expense tree as the route renders it (P503CatalogManagementScreen.kt:
# CategoriesSection renders each group row followed by that group's child rows): CatalogProjection.kt
# sorts each level by (parentId, name), so the groups are 交通 < 差旅 < 日用品 < 餐饮 and each group's
# children follow its own row. The second column is the row's height at 1.3, derived in sampler.py's
# block comment from run 46058888914's account rows: one bodyLarge line (80 px, group rows) or one
# bodyMedium line (71 px, leaf rows) plus one measured 127 px TextButton row plus the 4 dp paddings
# (21 px) that both row kinds carry.
CATEGORY_ROWS = (
    ("交通", 228),
    ("交通 / 早餐", 219),
    ("差旅", 228),
    ("差旅 / 机票", 219),
    ("日用品", 228),
    ("日用品 / 咖啡", 219),
    ("日用品 / 零食", 219),
    ("餐饮", 228),
    ("餐饮 / 午餐", 219),
    ("餐饮 / 早餐", 219),
)
# The management route's 1.3 viewport: the top bar's own nodes end at y=275, the dock clips the
# scrolling content at y=2133, and one `scroll_swipe_points` swipe moves the content by
# window_height/3 = 800 px over the dump's 2400 px window.
CATEGORY_VIEWPORT_TOP = 275
CATEGORY_VIEWPORT_BOTTOM = 2133
CATEGORY_SWIPE_STEP = 800


class PresentationProfileTests(unittest.TestCase):
    def test_presentation_profile_is_registered_with_its_own_screens(self):
        profile = PROFILES[PRESENTATION_PROFILE_NAME]
        self.assertEqual(profile.name, PRESENTATION_PROFILE_NAME)
        self.assertTrue(profile.seeded)
        self.assertTrue(profile.talkback)
        self.assertEqual([screen.stem for screen in PRESENTATION_SCREENS], [screen.stem for screen in profile.screens])
        self.assertEqual(PRESENTATION_HOME_COUNT, profile.launch_marker)
        self.assertEqual(PRESENTATION_STEMS, [screen.stem for screen in profile.screens])

    def test_existing_profiles_keep_their_screen_sets(self):
        # Adding the presentation target must not move an existing profile's evidence contract.
        self.assertEqual(
            ["home", "asset", "analysis"], [screen.stem for screen in PROFILES["empty"].screens]
        )
        self.assertEqual(
            ["search-guide", "search-results", "detail", "reconciliation"],
            [screen.stem for screen in PROFILES["seeded"].screens],
        )
        self.assertEqual(
            ["refund-entry", "refund-confirmation"],
            [screen.stem for screen in PROFILES["refund"].screens],
        )
        self.assertEqual("p806", PROFILES["p806"].name)

    def test_android_analysis_expands_more_analysis_and_scrolls_the_report_region_on(self):
        # D-369 B keeps the independent report behind 更多分析 on Android. Both Android report
        # captures must expand that labelled section and then scroll the report header on screen
        # (QR-A4: the report itself is visible, not merely present in the tree).
        expected_lead = (
            TapStep("分析"),
            ScrollStep("更多分析"),
            TapStep("更多分析"),
            ScrollStep(P806_REPORT_TITLE),
        )

        p806_report = next(screen for screen in PROFILES["p806"].screens if screen.stem == "report")
        self.assertEqual(expected_lead, p806_report.steps[:4])
        self.assertEqual(P806_REPORT_TITLE, p806_report.marker)
        self.assertIsInstance(p806_report.steps[4], ScrollStep)

    def test_presentation_analysis_captures_the_single_category_ranking(self):
        # E1 replaces the old report-face capture with the plan's single-category ranking: the
        # analysis page opens and the one 100.0% row is scrolled into view.
        presentation = next(screen for screen in PRESENTATION_SCREENS if screen.stem == "presentation-analysis")
        self.assertEqual((TapStep("分析"), ScrollStep(PRESENTATION_RANKING_TEXT)), presentation.steps)
        self.assertEqual(PRESENTATION_RANKING_MARKER, presentation.marker)
        self.assertEqual((PRESENTATION_RANKING_TEXT,), presentation.required)

    def test_home_rows_are_filmed_in_their_statistics_order_and_taps_pre_scroll(self):
        # The HOME flow is statistics_at DESC: the multi-category row (last month 10:00) sits ABOVE
        # the USD row (09:00). ScrollStep only moves forward, so the screens must be captured in
        # that same order; reversing them leaves the multi-category row above the restored viewport.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        stems = [screen.stem for screen in PRESENTATION_SCREENS]
        self.assertLess(
            stems.index("presentation-multiple-categories"),
            stems.index("presentation-multi-currency"),
        )
        self.assertEqual(
            (BACK, ScrollStep(PRESENTATION_MULTI_CATEGORY_NOTE)),
            by_stem["presentation-multiple-categories"].steps,
        )
        self.assertEqual(
            (
                ScrollStep(PRESENTATION_MULTI_CURRENCY_NOTE),
                TapStep(PRESENTATION_MULTI_CURRENCY_NOTE, contains=True),
            ),
            by_stem["presentation-multi-currency"].steps,
        )
        # TapStep only reads find_center and never checks reachability: the composed account row and
        # the composed management entry both need a forward ScrollStep before their tap.
        account_steps = by_stem["presentation-account-flow"].steps
        account_tap = account_steps.index(TapStep(SEEDED_ACCOUNT_NAME, contains=True))
        self.assertIn(ScrollStep(SEEDED_ACCOUNT_NAME), account_steps[:account_tap])
        management_steps = by_stem["presentation-catalog-management"].steps
        management_tap = management_steps.index(TapStep(PRESENTATION_MANAGEMENT_TITLE, contains=True))
        self.assertIn(ScrollStep(PRESENTATION_MANAGEMENT_TITLE), management_steps[:management_tap])
        # The route opens at the top of its list, so the accounts frame's speech target is the
        # account row the capture already requires: the TalkBack limb proves the DATA row is
        # announced instead of repeating the screenshot marker. The button ScrollStep is still the
        # last step; it is what puts that row inside the frame.
        self.assertEqual(ScrollStep(PRESENTATION_MANAGEMENT_MARKER), management_steps[-1])
        self.assertEqual(
            (PRESENTATION_MANAGEMENT_ACCOUNTS[0],),
            tuple(check.target for check in by_stem["presentation-catalog-management"].speech),
        )
        # I3: a real TalkBack target must be IN the window, so the two screens whose speech target
        # can sit below the fold scroll to the exact target before the speech pass.
        self.assertEqual(
            (BACK, TapStep("账户"), ScrollStep(P8_00_ASSET_TARGET)),
            by_stem["presentation-asset"].steps,
        )
        self.assertEqual(
            (TapStep(RECONCILIATION_TITLE), ScrollStep(SEEDED_QUEUE_MARKER)),
            by_stem["presentation-reconciliation"].steps,
        )
        # The ACCOUNTS first screen must be filmed from a FRESH top: after the account-flow Back the
        # list restores to the tapped account row, which puts 总借入 above the viewport where a
        # forward-only ScrollStep cannot reach it.
        self.assertLess(stems.index("presentation-asset"), stems.index("presentation-account-flow"))
        # I4: the USD detail's absent proves it left the HOME list, not merely the multi-category row.
        self.assertEqual(
            (PRESENTATION_HOME_COUNT, PRESENTATION_MULTI_CATEGORY_REMAINING),
            by_stem["presentation-multi-currency"].absent,
        )

    def test_the_management_categories_are_split_across_viewports_without_dropping_one(self):
        # D-369 E2d: the management route is one verticalScroll Column, but the device dump carries
        # only the rows inside the scroll viewport (run 46058888914: the 1.3 frame ends at the
        # 支出分类 header, clipped at the dock's top edge y=2133, and holds no 交通 row although the
        # window node still reports [0,0][1080,2400]). The ten rows of CATEGORY_ROWS are ~2226 px at
        # 1.3 against an ~1858 px viewport, so the tree is captured as three frames, each anchored
        # on the row that bounds its own required block.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        management = by_stem["presentation-catalog-management"]
        upper = by_stem["presentation-catalog-categories-upper"]
        middle = by_stem["presentation-catalog-categories-middle"]
        lower = by_stem["presentation-catalog-categories-lower"]
        # The accounts screen keeps the route guard and names the account rows it photographs.
        self.assertEqual((PRESENTATION_MANAGEMENT_ABSENT,), management.absent)
        self.assertEqual(PRESENTATION_MANAGEMENT_MARKER, management.marker)
        self.assertEqual(PRESENTATION_MANAGEMENT_ACCOUNTS, management.required)
        # All four seeded group names stay required, each on exactly one of the three captures.
        required = upper.required + middle.required + lower.required
        for name in PRESENTATION_MANAGEMENT_CATEGORIES:
            self.assertEqual(1, required.count(name), name)
        self.assertEqual(
            (PRESENTATION_CATEGORY_TRANSPORT, PRESENTATION_CATEGORY_TRAVEL, PRESENTATION_CATEGORY_TRAVEL_FLIGHT),
            upper.required,
        )
        # 日用品 is the middle frame's OWN anchor: the stop condition puts it inside the viewport by
        # construction, so the frame needs no group row above it. Its other entry is the leaf row
        # directly under 交通 — the row the old 交通-to-anchor arithmetic left out — so the frame
        # proves a leaf path and a group row, not one group name alone.
        self.assertEqual(
            (PRESENTATION_CATEGORY_TRANSPORT_BREAKFAST, PRESENTATION_CATEGORY_DAILY),
            middle.required,
        )
        self.assertEqual(
            (PRESENTATION_CATEGORY_FOOD, PRESENTATION_CATEGORY_FOOD_LUNCH),
            lower.required,
        )
        # Each frame scrolls forward to its own anchor (the leaf row one step BELOW its group block,
        # or the group row itself) and uses that same string as its marker: the forward-only swipe
        # stops at first appearance, so the block above the anchor is what stays in the window.
        self.assertEqual((ScrollStep(PRESENTATION_CATEGORY_TRAVEL_FLIGHT),), upper.steps)
        self.assertEqual(PRESENTATION_CATEGORY_TRAVEL_FLIGHT, upper.marker)
        self.assertEqual((ScrollStep(PRESENTATION_CATEGORY_DAILY),), middle.steps)
        self.assertEqual(PRESENTATION_CATEGORY_DAILY, middle.marker)
        self.assertEqual((ScrollStep(PRESENTATION_CATEGORY_FOOD_LUNCH),), lower.steps)
        self.assertEqual(PRESENTATION_CATEGORY_FOOD_LUNCH, lower.marker)
        # Each frame speaks its own group name; the leaf paths stay out of speech because TalkBack's
        # reading of `/` is unverified.
        self.assertEqual((PRESENTATION_CATEGORY_TRAVEL,), tuple(check.target for check in upper.speech))
        self.assertEqual((PRESENTATION_CATEGORY_DAILY,), tuple(check.target for check in middle.speech))
        self.assertEqual((PRESENTATION_CATEGORY_FOOD,), tuple(check.target for check in lower.speech))
        # They sit straight after the accounts screen, in the rendered (name-sorted) order, so every
        # swipe moves forward and no capture ever needs to scroll back up.
        stems = [screen.stem for screen in PRESENTATION_SCREENS]
        self.assertEqual(
            [
                stems.index("presentation-catalog-management") + 1,
                stems.index("presentation-catalog-categories-upper") + 1,
                stems.index("presentation-catalog-categories-middle") + 1,
            ],
            [
                stems.index("presentation-catalog-categories-upper"),
                stems.index("presentation-catalog-categories-middle"),
                stems.index("presentation-catalog-categories-lower"),
            ],
        )
        # Dropping any capture un-covers at least one group name: the split is load bearing.
        for dropped in (
            "presentation-catalog-categories-upper",
            "presentation-catalog-categories-middle",
            "presentation-catalog-categories-lower",
        ):
            remaining = [name for screen in PRESENTATION_SCREENS if screen.stem != dropped for name in screen.required]
            self.assertTrue(
                [name for name in PRESENTATION_MANAGEMENT_CATEGORIES if name not in remaining],
                dropped,
            )
        # The gate requires the whole management set, so dropping any one of the four stems is red.
        self.assertEqual([], gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME))
        for dropped in PRESENTATION_MANAGEMENT_STEMS:
            remaining = tuple(screen for screen in PRESENTATION_SCREENS if screen.stem != dropped)
            self.assertTrue(
                gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME, screens=remaining),
                dropped,
            )

    def _assert_category_frame_margin(self, screen: Screen) -> None:
        """F-03's one-row-of-slack rule for one category frame (the positive control below reuses it)."""
        heights = {name: height for name, height in CATEGORY_ROWS}
        rows = [name for name, _ in CATEGORY_ROWS]
        band = (CATEGORY_VIEWPORT_BOTTOM - CATEGORY_SWIPE_STEP) - CATEGORY_VIEWPORT_TOP
        # Each frame's required block is listed in rendered order and ends on its own anchor.
        self.assertEqual(screen.marker, screen.required[-1], screen.stem)
        self.assertEqual(
            [],
            [name for name in screen.required if name not in heights],
            f"{screen.stem}: a required row outside CATEGORY_ROWS cannot be margin-proven; add it",
        )
        indexes = [rows.index(name) for name in screen.required]
        self.assertEqual(sorted(indexes), indexes, screen.stem)
        top, anchor = indexes[0], indexes[-1]
        self.assertGreater(anchor, top, f"{screen.stem} must require at least one row above its anchor")
        # Consecutive row text tops differ by the upper row's height, so the distance from the
        # topmost required row to the anchor is the sum of the heights it spans.
        distance = sum(heights[rows[index]] for index in range(top, anchor))
        self.assertLess(
            distance,
            band,
            f"{screen.stem}: {rows[top]} sits {distance} px above {screen.marker}, outside the {band} px band",
        )
        self.assertLess(
            distance,
            band - 219,
            f"{screen.stem}: {rows[top]} leaves under one leaf row of slack above {screen.marker}",
        )

    def test_every_category_frame_keeps_a_row_of_slack_above_its_anchor(self):
        # F-03: a forward swipe can leave its target a whole swipe above the dock edge, so a required
        # row is proven only while its distance above the frame's own anchor leaves at least one leaf
        # row of slack inside the viewport: the worst stop puts the anchor at
        # CATEGORY_VIEWPORT_BOTTOM - CATEGORY_SWIPE_STEP = 1333, and the row above it must stay below
        # CATEGORY_VIEWPORT_TOP = 275 with one 219 px leaf row to spare. 1.3 is the binding scale —
        # its rows are taller and its dock sits 9 px higher — so the model is stated there; at 1.0
        # the same blocks keep more room.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        for stem in (
            "presentation-catalog-categories-upper",
            "presentation-catalog-categories-middle",
            "presentation-catalog-categories-lower",
        ):
            self._assert_category_frame_margin(by_stem[stem])
        # Positive control: the old two-frame split put 日用品 on the same capture as the 餐饮 / 午餐
        # anchor, four rendered rows (894 px) above it, leaving 164 px of slack — under the one-leaf
        # row this rule demands. The control shows the rule rejects that arrangement rather than
        # describing it.
        old_lower = Screen(
            "presentation-catalog-categories-lower",
            (ScrollStep(PRESENTATION_CATEGORY_FOOD_LUNCH),),
            PRESENTATION_CATEGORY_FOOD_LUNCH,
            required=(
                PRESENTATION_CATEGORY_DAILY,
                PRESENTATION_CATEGORY_FOOD,
                PRESENTATION_CATEGORY_FOOD_LUNCH,
            ),
        )
        with self.assertRaises(AssertionError):
            self._assert_category_frame_margin(old_lower)

    def test_a_screen_after_a_detail_capture_starts_with_back(self):
        # The detail surface is a sibling of the tab shell (P503App.kt:5804 against the shell's
        # :5508), so the dock a later screen taps does not exist there and that screen's first
        # TapStep would abort the remaining captures. Every capture whose marker is the detail's
        # Success-only 备注 row ends on that surface, so the capture after one must press Back onto
        # the retained HOME overview first.
        detail_stems = {
            "presentation-ordinary-expense",
            "presentation-detail",
            "presentation-multi-currency",
        }
        # Pinning the set keeps the rule below from going vacuous when a marker changes.
        self.assertEqual(
            detail_stems,
            {screen.stem for screen in PRESENTATION_SCREENS if screen.marker.startswith("备注：")},
        )
        stems = [screen.stem for screen in PRESENTATION_SCREENS]
        for stem in sorted(detail_stems):
            index = stems.index(stem)
            self.assertLess(index + 1, len(stems), f"{stem} ends the table, so nothing follows it")
            follower = PRESENTATION_SCREENS[index + 1]
            self.assertTrue(follower.steps, follower.stem)
            self.assertIsInstance(follower.steps[0], BackStep, follower.stem)

    def test_non_android_profiles_keep_the_default_analysis_steps(self):
        # The presentation/profile path must not leak the extra toggle into a screen set that never
        # had the Android subtraction (the LEGACY analysis page keeps the report inline).
        empty_analysis = next(screen for screen in PROFILES["empty"].screens if screen.stem == "analysis")
        self.assertEqual((TapStep("分析"),), empty_analysis.steps)
        self.assertNotIn(TapStep("更多分析"), list(empty_analysis.steps))

    def test_the_eight_plan_scenarios_are_named_in_one_configuration(self):
        self.assertEqual(
            (
                "ordinary-expense",
                "multiple-categories",
                "transfer",
                "multi-currency",
                "empty-day",
                "single-category-ranking",
                "cross-day-reconciliation",
                "technical-details",
            ),
            PRESENTATION_EVIDENCE_PLAN,
        )

    def test_every_plan_scenario_resolves_to_a_captured_screen(self):
        # E2's eight-scenario contract: every plan scenario resolves to at least one screen of THIS
        # profile, every mapped stem is captured, no stem stands for two scenarios, and the mapping
        # names the plan in its own order. There is no deferred list any more, so this assertion is
        # the one place the "all eight are captured" claim lives.
        captured = {screen.stem for screen in PRESENTATION_SCREENS}
        mapped: list[str] = []
        for name, stems in PRESENTATION_SCENARIO_STEMS:
            self.assertTrue(stems, name)
            self.assertTrue(set(stems) <= captured, name)
            mapped.extend(stems)
        self.assertEqual(len(mapped), len(set(mapped)))
        self.assertEqual(list(PRESENTATION_EVIDENCE_PLAN), [name for name, _ in PRESENTATION_SCENARIO_STEMS])
        self.assertEqual([], gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME))
        self.assertEqual([], gate_evidence_plan_problems("seeded"))
        # The anti-regression core: dropping the screen(s) of ANY one scenario turns the gate red, so
        # a later edit cannot quietly shrink the evidence set back to six or seven scenarios.
        for name, stems in PRESENTATION_SCENARIO_STEMS:
            remaining = tuple(screen for screen in PRESENTATION_SCREENS if screen.stem not in stems)
            self.assertTrue(
                gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME, screens=remaining),
                name,
            )
        self.assertIn(PRESENTATION_MANAGEMENT_MARKER, {screen.marker for screen in PRESENTATION_SCREENS})
        self.assertIn("presentation-catalog-management", captured)

    def test_evidence_plan_gate_rejects_a_broken_mapping(self):
        screens = (
            Screen("presentation-home", (), PRESENTATION_HOME_COUNT),
            # The technical stem is captured by a synthetic table entry so the `complete` case below
            # can isolate the management-screen problem; the gate reads stems, never captures.
            Screen("presentation-technical-collapsed", (), "x"),
        )
        plan = ("ordinary-expense", "technical-details")

        def problems(scenario_stems):
            return gate_evidence_plan_problems(
                PRESENTATION_PROFILE_NAME,
                screens=screens,
                evidence_plan=plan,
                scenario_stems=scenario_stems,
            )

        complete = (
            ("ordinary-expense", ("presentation-home",)),
            ("technical-details", ("presentation-technical-collapsed",)),
        )
        # The management screen is missing from the synthetic table, which is a problem by itself:
        # the whole D7 management set (accounts + all three category viewports) is required.
        self.assertEqual(
            [f"the D7 management screen stem {stem} is absent" for stem in PRESENTATION_MANAGEMENT_STEMS],
            problems(complete),
        )
        # Dropping ONE of the split category frames is red by itself: the three together are what
        # keeps all four seeded group names required.
        one_gone = screens + tuple(
            Screen(stem, (), "x")
            for stem in PRESENTATION_MANAGEMENT_STEMS
            if stem != "presentation-catalog-categories-lower"
        )
        self.assertEqual(
            ["the D7 management screen stem presentation-catalog-categories-lower is absent"],
            gate_evidence_plan_problems(
                PRESENTATION_PROFILE_NAME,
                screens=one_gone,
                evidence_plan=plan,
                scenario_stems=complete,
            ),
        )
        # A mapped stem the profile does not capture is red. This is the branch that replaced the E1
        # deferred list: a scenario can no longer be excused by declaring it deferred.
        uncaptured = (
            ("ordinary-expense", ("presentation-ordinary-expense",)),
            ("technical-details", ("presentation-technical-collapsed",)),
        )
        self.assertTrue(any("does not capture" in problem for problem in problems(uncaptured)))
        # One captured stem standing for two scenarios is red.
        shared = (
            ("ordinary-expense", ("presentation-home",)),
            ("technical-details", ("presentation-technical-collapsed", "presentation-home")),
        )
        self.assertTrue(any("is mapped to both" in problem for problem in problems(shared)))
        # A scenario the plan names but the mapping omits is red.
        incomplete = (("ordinary-expense", ("presentation-home",)),)
        self.assertTrue(any("PRESENTATION_EVIDENCE_PLAN names" in problem for problem in problems(incomplete)))
        # An empty scenario is red.
        empty = (
            ("ordinary-expense", ()),
            ("technical-details", ("presentation-technical-collapsed",)),
        )
        self.assertTrue(any("maps no screen" in problem for problem in problems(empty)))

    def test_ui_modes_map_to_the_platform_night_value(self):
        self.assertEqual(("light", "dark"), UI_MODES)
        self.assertEqual("light", DEFAULT_UI_MODE)
        self.assertEqual("no", ui_mode_night_value("light"))
        self.assertEqual("yes", ui_mode_night_value("dark"))
        with self.assertRaises(SampleError):
            ui_mode_night_value("sepia")

    def test_ui_mode_readback_requires_the_exact_independent_line(self):
        self.assertIsNone(ui_mode_readback_problem("Night mode: no\n", "light"))
        self.assertIsNone(ui_mode_readback_problem("Night mode: yes\n", "dark"))
        # Unknown commands, a stale other mode, a duplicated line and a prefixed line all fail.
        for raw in (
            "Unknown command: uimode",
            "",
            "Night mode: yes\n",
            "night mode: no\n",
            "Night mode: no\nNight mode: no\n",
            "Night mode: no (cached)",
        ):
            self.assertIsNotNone(ui_mode_readback_problem(raw, "light"), raw)
        self.assertIsNotNone(ui_mode_readback_problem("Night mode: yes", "light"))

    def test_gate_ui_mode_must_match_the_host_record(self):
        self.assertEqual([], gate_ui_mode_problems({"ui_mode": "light"}, "light"))
        self.assertTrue(gate_ui_mode_problems({}, "light"))
        self.assertTrue(gate_ui_mode_problems({"ui_mode": "dark"}, "light"))


# The failure run 46088630336 (`light-1.0`, the first combination, exit 1 after 6.3 s) printed,
# verbatim below its `run: ` prefix. It is the ui_mode readback check, not a TalkBack verdict, and it
# is the whole reason the readback is retried; the console line is the only artifact that run left
# (it produced no evidence directory).
_SWEEP5_UI_MODE_FAILURE = (
    "ui_mode 'light' did not take effect: `cmd uimode night` did not report exactly one "
    "'Night mode: no' line"
)


class UiModeReadbackRetry(unittest.TestCase):
    """D-369 E2g: a boot-time unsettled `cmd uimode night` readback earns a bounded retry.

    The first combination of run 46088630336 began 6.3 s after the AVD booted and died inside this
    check, before any screen. A readback that reports no unique `Night mode: no` line there is a
    system service still starting, so the write and its readback are re-attempted; the bound and
    the final message stay exactly as the single attempt had them.
    """

    def test_a_boot_transient_readback_is_retried_until_it_is_unique(self):
        answers = iter(["", "Night mode: no\n"])
        applied: list[int] = []
        slept: list[float] = []
        mode = ui_mode_apply_and_readback(
            lambda: applied.append(1),
            lambda: next(answers),
            "light",
            sleep=slept.append,
        )
        self.assertEqual("light", mode)
        # The write is re-issued with the second attempt: a booting service can lose it too.
        self.assertEqual(2, len(applied))
        self.assertEqual([UI_MODE_READBACK_INTERVAL_S], slept)

    def test_a_multi_line_readback_is_also_a_retried_transient(self):
        answers = iter(["Night mode: no (cached)\n", "Night mode: no\n"])
        self.assertEqual(
            "light",
            ui_mode_apply_and_readback(
                lambda: None, lambda: next(answers), "light", sleep=lambda _seconds: None
            ),
        )

    def test_three_bad_readbacks_fail_with_the_unchanged_message(self):
        raws = ["", "Unknown command: uimode", "Night mode: yes\n"]
        answers = iter(raws)
        applied: list[int] = []
        slept: list[float] = []
        with self.assertRaises(SampleError) as caught:
            ui_mode_apply_and_readback(
                lambda: applied.append(1), lambda: next(answers), "light", sleep=slept.append
            )
        message = str(caught.exception)
        self.assertEqual(
            f"ui_mode 'light' did not take effect: {ui_mode_readback_problem(raws[-1], 'light')}",
            message,
        )
        # The run's own opening line is what a red still reports.
        self.assertTrue(message.startswith(_SWEEP5_UI_MODE_FAILURE), message)
        self.assertEqual(UI_MODE_READBACK_ATTEMPTS, len(applied))
        self.assertEqual([UI_MODE_READBACK_INTERVAL_S] * (UI_MODE_READBACK_ATTEMPTS - 1), slept)

    def test_a_unique_first_readback_needs_no_second_attempt(self):
        applied: list[int] = []
        self.assertEqual(
            "dark",
            ui_mode_apply_and_readback(
                lambda: applied.append(1),
                lambda: "Night mode: yes\n",
                "dark",
                sleep=lambda _seconds: self.fail("a passing readback must not sleep"),
            ),
        )
        self.assertEqual(1, len(applied))

    def test_gate_accepts_a_presentation_host_and_rejects_a_swapped_profile(self):
        expected = [screen.stem for screen in PRESENTATION_SCREENS]
        host = {"profile": PRESENTATION_PROFILE_NAME, "screens": expected}
        self.assertEqual([], gate_profile_problems(host, PRESENTATION_PROFILE_NAME))

        swapped = {"profile": "seeded", "screens": expected}
        self.assertTrue(gate_profile_problems(swapped, PRESENTATION_PROFILE_NAME))

    def test_every_presentation_screen_carries_a_real_talkback_target(self):
        # Plan section 2.2 requires each screen to declare marker, absent, required and speech. E2
        # captures all eight scenarios, so every screen — including the cross-day queue and both
        # technical states — carries all four, and every speech target names its required literal.
        for screen in PRESENTATION_SCREENS:
            self.assertTrue(screen.marker, screen.stem)
            self.assertTrue(screen.absent, screen.stem)
            self.assertTrue(screen.required, screen.stem)
            self.assertTrue(screen.speech, screen.stem)
            for check in screen.speech:
                self.assertTrue(check.target, screen.stem)
                self.assertTrue(check.required, screen.stem)
        self.assertEqual(
            ["presentation-calendar-empty"],
            [screen.stem for screen in PRESENTATION_SCREENS if screen.visible_absent],
        )

    def test_every_presentation_screen_marker_is_a_product_literal(self):
        # The four home-row screens plus the tab screens are the evidence; each marker is the
        # production copy named in sampler.py's constant block, never a seed-derived number except
        # where the screen's own copy carries it.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        self.assertEqual(PRESENTATION_MANAGEMENT_MARKER, by_stem["presentation-catalog-management"].marker)
        self.assertEqual((PRESENTATION_MANAGEMENT_ABSENT,), by_stem["presentation-catalog-management"].absent)
        self.assertEqual(PRESENTATION_MULTI_CATEGORY_REMAINING, by_stem["presentation-multiple-categories"].marker)
        self.assertEqual(PRESENTATION_MULTI_CURRENCY_MARKER, by_stem["presentation-multi-currency"].marker)
        self.assertEqual(P8_00_ASSET_TARGET, by_stem["presentation-asset"].marker)
        self.assertEqual(PRESENTATION_CALENDAR_EMPTY_MARKER, by_stem["presentation-calendar-empty"].marker)
        self.assertEqual(
            PRESENTATION_CALENDAR_EMPTY_VISIBLE_ABSENT,
            by_stem["presentation-calendar-empty"].visible_absent,
        )

    def test_cross_day_rows_print_the_shanghai_day_and_veto_the_utc_day(self):
        # The two 2020 transfers share ONE UTC day (01-15) and straddle the Shanghai midnight, so
        # the queue must print 01-15 for A and 01-16 for B. Both full row lines are pinned from the
        # production formatter (reconciliationQueueRowText over the seed's fixed instants), and the
        # negative marker is B's line under the OLD UTC bucket — 01-15 with B's own note — so a
        # regression to the stored-date rendering reds the capture instead of passing it.
        cross_day = next(screen for screen in PRESENTATION_SCREENS if screen.stem == "presentation-reconciliation-cross-day")
        self.assertEqual(PRESENTATION_CROSS_DAY_ROW_B, cross_day.marker)
        self.assertEqual(
            (PRESENTATION_CROSS_DAY_QUEUE_COUNT, PRESENTATION_CROSS_DAY_ROW_A),
            cross_day.required,
        )
        self.assertEqual((PRESENTATION_CROSS_DAY_UTC_ERROR, ACCOUNT_FLOW_HEADER), cross_day.absent)
        # The two rows are the SAME note shape under DIFFERENT natural days, and the veto names B's
        # note under A's day — the precise shape the old UTC bucket produced.
        self.assertNotEqual(PRESENTATION_CROSS_DAY_DATE_A, PRESENTATION_CROSS_DAY_DATE_B)
        self.assertTrue(PRESENTATION_CROSS_DAY_ROW_A.startswith(f"{PRESENTATION_CROSS_DAY_DATE_A} · "))
        self.assertTrue(PRESENTATION_CROSS_DAY_ROW_B.startswith(f"{PRESENTATION_CROSS_DAY_DATE_B} · "))
        self.assertEqual(
            f"{PRESENTATION_CROSS_DAY_DATE_A} · {PRESENTATION_CROSS_DAY_NOTE_B}",
            PRESENTATION_CROSS_DAY_UTC_ERROR,
        )
        # The speech window is the row's OWN text node, and its required facts are the same day,
        # note and exact amount the marker carries.
        self.assertEqual((PRESENTATION_CROSS_DAY_ROW_B,), tuple(check.target for check in cross_day.speech))
        self.assertEqual(
            (PRESENTATION_CROSS_DAY_DATE_B, PRESENTATION_CROSS_DAY_NOTE_B, PRESENTATION_CROSS_DAY_AMOUNT_B),
            cross_day.speech[0].required,
        )
        # The row is scrolled to EXACTLY: a marker merely present in a scrollable tree would not put
        # the speech target inside the window, which is the failure a missing ScrollStep produces.
        self.assertEqual(ScrollStep(PRESENTATION_CROSS_DAY_ROW_B), cross_day.steps[-1])
        self.assertEqual((BACK, BACK), cross_day.steps[:2])
        # The queue is reached through the WINDOW account row: the forward-only scroll must address
        # that seeded account by name, and the tap must be preceded by it (its row can sit far below
        # the restored list position).
        self.assertIn(ScrollStep(PRESENTATION_CROSS_DAY_ACCOUNT), cross_day.steps)
        self.assertLess(
            cross_day.steps.index(ScrollStep(PRESENTATION_CROSS_DAY_ACCOUNT)),
            cross_day.steps.index(TapStep(PRESENTATION_CROSS_DAY_ACCOUNT, contains=True)),
        )

    def test_the_technical_disclosure_is_collapsed_by_default_then_expanded(self):
        # spokenText() (P503LedgerViewPresentation.kt:1135-1142) is the toggle's one content
        # description in both states, so each state is a distinct announcement a screen can pin.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        collapsed = by_stem["presentation-technical-collapsed"]
        expanded = by_stem["presentation-technical-expanded"]
        # Collapsed: the default state, with no tap on the toggle before the capture, and the
        # transaction id the normal rows never print named as an absence.
        self.assertEqual(PRESENTATION_TECHNICAL_COLLAPSED, collapsed.marker)
        self.assertIn("已收起", collapsed.marker)
        self.assertIn(PRESENTATION_TECHNICAL_EXPAND_LABEL, collapsed.marker)
        self.assertNotIn(PRESENTATION_MULTI_CATEGORY_TRANSACTION_ID, collapsed.marker)
        self.assertFalse(
            any(isinstance(step, TapStep) and step.target == PRESENTATION_TECHNICAL_COLLAPSED for step in collapsed.steps)
        )
        self.assertIn(PRESENTATION_MULTI_CATEGORY_TRANSACTION_ID, collapsed.absent)
        self.assertEqual((PRESENTATION_TECHNICAL_EXPAND_LABEL,), collapsed.required)
        self.assertEqual(
            ScrollStep(PRESENTATION_TECHNICAL_COLLAPSED, 8),
            collapsed.steps[-1],
        )
        # Expanded: the SAME toggle after exactly one tap, its marker the expanded announcement
        # prefix and the collapsed sentence now absent (a missed tap leaves it in place).
        self.assertEqual((TapStep(PRESENTATION_TECHNICAL_COLLAPSED),), expanded.steps)
        self.assertEqual(PRESENTATION_TECHNICAL_EXPANDED, expanded.marker)
        self.assertEqual((PRESENTATION_TECHNICAL_COLLAPSED,), expanded.absent)
        self.assertEqual(
            (
                PRESENTATION_MULTI_CATEGORY_LEDGER_ID,
                PRESENTATION_MULTI_CATEGORY_VERSION_ID,
                PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID,
            ),
            expanded.required,
        )
        # The row count is the page's own: three detail ids plus the seed's five posting ids. A drift
        # in either direction changes the announcement and reds the marker, never passes silently.
        self.assertEqual(3 + 5, PRESENTATION_TECHNICAL_ROW_COUNT)
        self.assertIn(f"共 {PRESENTATION_TECHNICAL_ROW_COUNT} 项", PRESENTATION_TECHNICAL_COLLAPSED)
        self.assertIn(
            f"{PRESENTATION_TECHNICAL_HEADER}，共 {PRESENTATION_TECHNICAL_ROW_COUNT} 项：",
            PRESENTATION_TECHNICAL_EXPANDED,
        )
        # The spoken limb covers both directions of the control: the collapsed announcement names
        # the expand affordance, the expanded one speaks the full original values.
        self.assertEqual(
            (PRESENTATION_TECHNICAL_COLLAPSED,),
            tuple(check.target for check in collapsed.speech),
        )
        self.assertEqual((PRESENTATION_TECHNICAL_EXPAND_LABEL,), collapsed.speech[0].required)
        self.assertEqual(
            (PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID,),
            tuple(check.target for check in expanded.speech),
        )
        self.assertEqual(
            (PRESENTATION_MULTI_CATEGORY_VERSION_ID, PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID),
            expanded.speech[0].required,
        )


class VisibleAbsentTests(unittest.TestCase):
    def test_visible_absent_ignores_content_description(self):
        # The empty calendar cell renders its date only while its accessibility sentence keeps
        # spelling the zero values; `visible_absent` must classify that frame as correct.
        tree = (
            '<hierarchy><node text="该月暂无收支。" content-desc="" bounds="[0,0][100,100]"/>'
            '<node text="16" content-desc="2020-01-16，CNY 支出 0.00、收入 0.00；共 0 笔" '
            'bounds="[0,100][100,200]"/></hierarchy>'
        )
        self.assertEqual([], visible_absent_problems(tree, ("0.00", "共 0 笔")))
        # `absent` keeps its own text-and-description meaning and WOULD red this correct frame.
        self.assertTrue(marker_problems(tree, PRESENTATION_CALENDAR_EMPTY_MARKER, ("0.00",)))
        # The same criterion must still catch a zero printed as visible text.
        bad = '<hierarchy><node text="0.00 CNY" content-desc="" bounds="[0,0][100,100]"/></hierarchy>'
        self.assertEqual(["visible text '0.00' is still shown"], visible_absent_problems(bad, ("0.00",)))
        # A needle in a readable text node is reported even when another node only carries it in
        # its content description.
        mixed = (
            '<hierarchy><node text="共 0 笔" content-desc="" bounds="[0,0][100,100]"/>'
            '<node text="17" content-desc="0.00" bounds="[0,100][100,200]"/></hierarchy>'
        )
        self.assertEqual(
            ["visible text '共 0 笔' is still shown"],
            visible_absent_problems(mixed, ("0.00", "共 0 笔")),
        )

    def test_screen_problems_is_the_shared_driver_and_gate_criterion(self):
        # Both the driver and the gate call this one function, so a screen's whole verdict — marker,
        # previous-screen absence, required values, visible-only absence and viewport — cannot drift
        # between the live capture and the replay from disk.
        screen = next(s for s in PRESENTATION_SCREENS if s.stem == "presentation-calendar-empty")
        correct = (
            "<hierarchy>"
            f'<node text="{screen.marker}" content-desc="" bounds="[0,0][100,100]"/>'
            f'<node text="{PRESENTATION_CALENDAR_EMPTY_TITLE}" content-desc="" bounds="[0,100][100,200]"/>'
            '<node text="16" content-desc="2020-01-16，CNY 支出 0.00、收入 0.00；共 0 笔" '
            'bounds="[0,200][100,300]"/>'
            "</hierarchy>"
        )
        self.assertEqual([], screen_problems(correct, screen))
        wrong = correct.replace('<node text="16"', '<node text="0.00"')
        self.assertEqual(["visible text '0.00' is still shown"], screen_problems(wrong, screen))
        missing_marker = correct.replace(screen.marker, "x")
        self.assertTrue(any("marker" in problem for problem in screen_problems(missing_marker, screen)))
        missing_title = correct.replace(PRESENTATION_CALENDAR_EMPTY_TITLE, "x")
        self.assertTrue(
            any("required string" in problem for problem in screen_problems(missing_title, screen))
        )


class TraceAttemptTests(unittest.TestCase):
    """D4's trace limb: every failure is a recorded reason, never an escaping exception."""

    @staticmethod
    def _sampler(evidence: Path) -> Sampler:
        return Sampler(adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-presentation")

    def test_a_hung_adb_timeout_is_recorded_not_raised(self):
        # subprocess.TimeoutExpired is not a SampleError, so the old version escaped main()'s
        # handler and aborted the whole combination; D4 forbids that.
        with tempfile.TemporaryDirectory() as tmp:
            sampler = self._sampler(Path(tmp))
            with mock.patch.object(Sampler, "adb", side_effect=subprocess.TimeoutExpired("adb", 30)):
                status, reason = sampler.attempt_trace()
            self.assertEqual("", status)
            self.assertIn("TimeoutExpired", reason)
            self.assertFalse((Path(tmp) / "trace.txt").exists())

    def test_a_real_trace_is_kept_as_the_raw_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            sampler = self._sampler(Path(tmp))
            with mock.patch.object(Sampler, "adb", return_value="# tracer: gfx view\nTRACE:\n"):
                status, reason = sampler.attempt_trace()
            self.assertEqual(("available", ""), (status, reason))
            self.assertEqual("# tracer: gfx view\nTRACE:\n", (Path(tmp) / "trace.txt").read_text(encoding="utf-8"))

    def test_an_empty_or_erroring_trace_is_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            sampler = self._sampler(Path(tmp))
            with mock.patch.object(Sampler, "adb", return_value=""):
                self.assertEqual("", sampler.attempt_trace()[0])
            with mock.patch.object(Sampler, "adb", return_value="error opening /sys/kernel/tracing: Permission denied\n"):
                status, reason = sampler.attempt_trace()
            self.assertEqual("", status)
            self.assertIn("Permission denied", reason)


# ---------------------------------------------------------------------------------------------------
# D-369 E2f: TalkBack fragment provenance and the per-transient reissue (run 46074246784, candidate
# cc76a0c9). Every log row below is VERBATIM from that run's sweep4 artifacts; only the probe's
# node-dump rows (the `pass …/node text=…` block between focus-result and the end stage) are elided,
# and the elision cannot create or remove an utterance.
#
# What these tests pin, in one place: the three "green" middle captures (light-1.3, dark-1.0,
# dark-1.3) each carry a GENUINE `Speaking fragment text="日用品"` inside the accepted window, and the
# `TTS is not ready … fragments:[{text:日用品,…}]` row is a dropped utterance the parser never
# counts. So narrowing the fragment source does NOT turn those three captures red — they really
# spoke the group row. light-1.0 is the one that is red, and it is red for a different reason: its
# home command never began a window (the lost-command shape) and its middle window completed
# accepted with no utterance (the accepted-but-silent shape).
# ---------------------------------------------------------------------------------------------------
_RUN = "46074246784"
_DARK_MIDDLE = "local/artifacts/sweep4/dark-1.0/talkback-presentation-catalog-categories-middle.txt"
_LIGHT_MIDDLE = "local/artifacts/sweep4/light-1.0/talkback-presentation-catalog-categories-middle.txt"

# `light-1.3/talkback-presentation-catalog-categories-middle.txt`: the engine dropped the utterance.
_TTS_NOT_READY_DAILY = (
    'E/talkback: SpeechControllerImpl(15700): TTS is not ready. Attempted to speak before TTS was '
    'initialized. Item: {utteranceId:"", fragments:[{text:日用品, earcons:[2132017155], '
    "haptics:[2130903141], speechParams:Bundle[{pitch=1.0}]nonSpeechParams:Bundle[{}]"
    "fragmentStartIndex:0}], uninterruptible:false, isGloballyUninterruptible:false, "
    "mCanIgnoreInterrupts:false, flushGlobalTtsQueue:true, flags:2156, "
    "creationTime:1795819396264}"
)
# The same file one announcement later: the engine actually spoke the group row.
_SPEAKING_DAILY = (
    'V/talkback: SpeechControllerImpl(15700): Speaking fragment text="日用品", '
    "utteranceId=talkback_2, TtsSpan=null, locale=en_US, "
    "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_VIEW_ACCESSIBILITY_FOCUSED displayId:0 "
    "time:1871860"
)
_SPEAKING_STARTUP = (
    'V/talkback: SpeechControllerImpl(28258): Speaking fragment text="TalkBack on", '
    "utteranceId=talkback_0, TtsSpan=null, locale=null, event=null"
)
# Verbatim, all seven lines of local/artifacts/sweep4/light-1.0/talkback-presentation-home.txt
# (run 46074246784): the host headers and no probe row at all, which is the lost-command shape. The
# `# service-info:` line quotes a real `I/ULA11Y  ( 4733): service-info …` row inside a comment, so
# the fixture also pins that a ULA11Y string embedded in a header is not a command stage.
_LOST_COMMAND_ARTIFACT = (
    "# stem: presentation-home",
    "# enable: success components=['com.unifiedledger.android.test/"
    "com.unifiedledger.android.A11yFocusProbeService', "
    "'com.google.android.marvin.talkback/.TalkBackService'] bound=True",
    "# ready: waited=1.1s polls=2 fragments=1",
    "# notification-prompt: clears=1 quiet_after_round=3",
    "# service-info: I/ULA11Y  ( 4733): service-info eventTypes=0x8820 flags=0x2",
    "# target: 对账样本转账",
    "# command: 1",
)
# `dark-1.0/talkback-presentation-catalog-categories-middle.txt` (cmd 16): the green window. The
# group name is a non-focusable Text whose action buttons are siblings, so the probe logs
# `target=none` for EVERY group row — and still answers accepted from the action it sends to the
# visible Text itself, which is enough for TalkBack to announce it.
_DARK_MIDDLE_WINDOW = (
    'I/ULA11Y  (24830): command action=focus id=16 needle64=5pel55So5ZOB target="日用品"',
    "I/ULA11Y  (24830): target id=16 found=true visible=true bounds=[42,1853][172,1916] "
    'text="日用品" desc="" text64="5pel55So5ZOB" desc64="" actions=0x20340 clickable=false '
    "scrollable=false",
    'I/ULA11Y  (24830): focus-before id=16 found=true bounds=[42,296][519,330] '
    'text64="6Leo5pel55WM5qC35pys6LSm5oi377yI6LWE5Lqn77yJ" desc64=""',
    "I/ULA11Y  (24830): focus-target id=16 target=none reason=no-focusable-ancestor "
    "focusNodeBounds=[42,1853][172,1916]",
    "I/ULA11Y  (24830): speech-window-begin id=16",
    "I/ULA11Y  (24830): focus-result id=16 found=true accepted=true source=action",
    'V/talkback: SpeechControllerImpl(28258): Speaking fragment text="UnifiedLedger", '
    "utteranceId=talkback_1, TtsSpan=null, locale=null, "
    "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_WINDOWS_CHANGED displayId:0 time:2766931",
    'V/talkback: SpeechControllerImpl(28258): Speaking fragment text="日用品", '
    "utteranceId=talkback_2, TtsSpan=null, locale=en_US, "
    "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_VIEW_ACCESSIBILITY_FOCUSED displayId:0 "
    "time:2767859",
    "I/ULA11Y  (24830): speech-window-end id=16",
)
# `light-1.0/talkback-presentation-catalog-categories-middle.txt` (cmd 17): completed, accepted, and
# no utterance at all. The one fragment before the command line belongs to the PREVIOUS command's
# window and must not count; the `TTS is not ready` rows sit even earlier, in cmd 16's window.
_LIGHT_SILENT_WINDOW = (
    'V/talkback: SpeechControllerImpl(13148): Speaking fragment text="UnifiedLedger", '
    "utteranceId=talkback_1, TtsSpan=null, locale=null, "
    "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_WINDOWS_CHANGED displayId:0 time:871788",
    'I/ULA11Y  ( 4733): command action=focus id=17 needle64=5pel55So5ZOB target="日用品"',
    "I/ULA11Y  ( 4733): target id=17 found=true visible=true bounds=[42,1857][172,1920] "
    'text="日用品" desc="" text64="5pel55So5ZOB" desc64="" actions=0x20380 clickable=false '
    "scrollable=false",
    'I/ULA11Y  ( 4733): focus-before id=17 found=true bounds=[42,1857][172,1920] '
    'text64="5pel55So5ZOB" desc64=""',
    "I/ULA11Y  ( 4733): focus-clear id=17 answered=true",
    "I/ULA11Y  ( 4733): focus-target id=17 target=none reason=no-focusable-ancestor "
    "focusNodeBounds=[42,1857][172,1920]",
    "I/ULA11Y  ( 4733): speech-window-begin id=17",
    "I/ULA11Y  ( 4733): focus-result id=17 found=true accepted=true source=action",
    "I/ULA11Y  ( 4733): speech-window-end id=17",
)


# `light-1.3/talkback-presentation-catalog-categories-middle.txt` (command 15, run 46088630336):
# VERBATIM lines 8, 212, 235, 236, 237, 239 and 446 — the command, its found target, the begin and
# accepted focus stages, then TalkBack's own startup announcement, with no `speech-window-end`. The
# probe's second `command … id=15` row (a mid-window rebind re-runs the same value, artifact lines
# 187/188) is elided: it is the same defect the reissue targets, but this fixture pins the pure
# opened-never-closed shape that stage rows alone define.
_SWEEP5_NEVER_ENDED = (
    'I/ULA11Y  ( 5639): command action=focus id=15 needle64=5pel55So5ZOB target="日用品"',
    'I/ULA11Y  ( 5639): target id=15 found=true visible=true bounds=[42,1724][206,1804] '
    'text="日用品" desc="" text64="5pel55So5ZOB" desc64="" actions=0x20340 clickable=false '
    "scrollable=false",
    "I/ULA11Y  ( 5639): focus-before id=15 found=false reason=focus-none",
    "I/ULA11Y  ( 5639): focus-target id=15 target=none reason=no-focusable-ancestor "
    "focusNodeBounds=[42,1724][206,1804]",
    "I/ULA11Y  ( 5639): speech-window-begin id=15",
    "I/ULA11Y  ( 5639): focus-result id=15 found=true accepted=true source=action",
    'V/talkback: SpeechControllerImpl( 9852): Speaking fragment text="TalkBack on", '
    "utteranceId=talkback_0, TtsSpan=null, locale=null, event=null",
)


def _artifact(*rows: str) -> str:
    """One recorded talkback artifact body: the rows in order, newline-terminated."""
    return "\n".join(rows) + "\n"


def _spoken(fragment: str, *, utterance_id: str = "talkback_2") -> str:
    """A `Speaking fragment` row in the real brief shape (PID inside the controller columns)."""
    return (
        f'V/talkback: SpeechControllerImpl(28258): Speaking fragment text="{fragment}", '
        f"utteranceId={utterance_id}, TtsSpan=null, locale=en_US, "
        "event=type:EVENT_TYPE_ACCESSIBILITY subtype:TYPE_VIEW_ACCESSIBILITY_FOCUSED displayId:0 "
        "time:1"
    )


def _probe_window(
    *,
    command_id: int,
    target: str,
    fragments: tuple[str, ...] = (),
    pre_begin: tuple[str, ...] = (),
    accepted: bool = True,
    stopped: str = "end",
    problem: str | None = None,
) -> dict[str, object]:
    """A command window in the probe's own shape, for driving `p806_talkback_screen`.

    `stopped` names how far the probe's own stage rows got: `"end"` is a complete window,
    `"focus-result"` is a window that began and never ended, and `"nothing"` is a lost command (no
    probe row at all). `began` is DERIVED from those rows with the production
    `talkback_window_began`, and an incomplete shape carries the production
    `isolate_talkback_window` message unless `problem` overrides it for a deliberately corrupted
    fixture. A fixture therefore cannot claim a stage its own rows do not show.
    """
    if stopped not in ("end", "focus-result", "nothing"):
        raise AssertionError(f"unknown stop {stopped!r}")
    encoded = base64.b64encode(target.encode("utf-8")).decode("ascii")
    command_row = (
        f'I/ULA11Y  ( 4733): command action=focus id={command_id} needle64={encoded} '
        f'target="{target}"'
    )
    target_row = (
        f"I/ULA11Y  ( 4733): target id={command_id} found=true visible=true "
        f'bounds=[42,1857][172,1920] text="{target}" desc="" text64="{encoded}" desc64="" '
        "actions=0x20380 clickable=false scrollable=false"
    )
    begin_row = f"I/ULA11Y  ( 4733): speech-window-begin id={command_id}"
    focus_row = (
        f"I/ULA11Y  ( 4733): focus-result id={command_id} found=true "
        f"accepted={'true' if accepted else 'false'} source=action"
    )
    end_row = f"I/ULA11Y  ( 4733): speech-window-end id={command_id}"
    completed = stopped == "end"
    if stopped == "nothing":
        lines: list[str] = []
        problem = (
            f"command id={command_id} never began a speech window" if problem is None else problem
        )
    elif stopped == "focus-result":
        lines = [command_row, target_row, begin_row, focus_row]
        problem = (
            f"command id={command_id}'s speech window never ended" if problem is None else problem
        )
    else:
        lines = [
            command_row,
            target_row,
            *pre_begin,
            begin_row,
            focus_row,
            *(_spoken(fragment) for fragment in fragments),
            end_row,
        ]
        problem = "" if problem is None else problem
    return {
        "id": command_id,
        "target": target,
        "began": talkback_window_began(lines, command_id),
        "completed": completed,
        "accepted": accepted and completed,
        "problem": problem,
        "lines": lines,
    }


class TalkbackFragmentProvenance(unittest.TestCase):
    """D-369 E2f: a speech check is satisfied only by a fragment TalkBack actually spoke."""

    def test_the_dropped_utterance_payload_is_not_a_fragment(self) -> None:
        # The row quotes the intended word inside its own payload. It is the engine REFUSING the
        # utterance, so a required value can never be satisfied by it.
        self.assertIn("日用品", _TTS_NOT_READY_DAILY)
        self.assertEqual([], speech_fragments(_TTS_NOT_READY_DAILY))
        # The real spoken row of the same file is the one that counts, and only that one.
        self.assertEqual(["日用品"], speech_fragments(_SPEAKING_DAILY))
        self.assertEqual(
            ["日用品"], speech_fragments(_TTS_NOT_READY_DAILY + "\n" + _SPEAKING_DAILY + "\n")
        )

    def test_a_silent_accepted_window_stays_red_despite_a_fragment_before_the_command(self) -> None:
        status, fragments, missing, reason = talkback_check_verdict(
            _artifact(*_LIGHT_SILENT_WINDOW), 17, "日用品", ("日用品",)
        )
        self.assertEqual("fragments-missing", status)
        self.assertEqual([], fragments)
        self.assertEqual(["日用品"], missing)
        self.assertEqual("the accepted window produced no TalkBack fragment", reason)

    def test_the_green_middle_capture_derived_its_word_from_a_real_utterance(self) -> None:
        # This refutes "the gate counted the error payload": the dark-1.0 capture passes on its own
        # `Speaking fragment text="日用品"` row, which sits INSIDE the accepted window. Swapping that
        # row for the dropped-utterance row carrying the same word must turn the very same window red
        # — proving the green never came from the payload.
        text = _artifact(*_DARK_MIDDLE_WINDOW)
        status, fragments, missing, reason = talkback_check_verdict(
            text, 16, "日用品", ("日用品",)
        )
        self.assertEqual("success", status)
        self.assertEqual([], missing)
        self.assertEqual("", reason)
        self.assertIn("日用品", fragments)
        dropped = _artifact(
            *_DARK_MIDDLE_WINDOW[:6],
            _TTS_NOT_READY_DAILY,
            _DARK_MIDDLE_WINDOW[8],
        )
        self.assertEqual(
            "fragments-missing", talkback_check_verdict(dropped, 16, "日用品", ("日用品",))[0]
        )

    def test_the_two_window_shapes_are_pinned_to_their_own_artifacts(self) -> None:
        # A future edit that "fixes" the three green captures by excluding them would have to
        # contradict the windows themselves, so the two shapes are asserted against their sources.
        self.assertEqual(9, len(_DARK_MIDDLE_WINDOW))
        self.assertEqual(9, len(_LIGHT_SILENT_WINDOW))
        self.assertNotIn(_TTS_NOT_READY_DAILY, _DARK_MIDDLE_WINDOW)
        self.assertNotIn(_TTS_NOT_READY_DAILY, _LIGHT_SILENT_WINDOW)
        self.assertTrue(
            any(
                row.endswith("focus-result id=17 found=true accepted=true source=action")
                for row in _LIGHT_SILENT_WINDOW
            )
        )
        self.assertEqual(_RUN, "46074246784")
        self.assertEqual(
            "local/artifacts/sweep4/dark-1.0/talkback-presentation-catalog-categories-middle.txt",
            _DARK_MIDDLE,
        )
        self.assertEqual(
            "local/artifacts/sweep4/light-1.0/talkback-presentation-catalog-categories-middle.txt",
            _LIGHT_MIDDLE,
        )


class TalkbackReadinessHasSpoken(unittest.TestCase):
    """D-369 E2f: readiness is "TalkBack has spoken at all"; a dropped LAST row does not revoke it.

    Five of run 46074246784's readiness windows end their speech with a dropped utterance while the
    artifact header already says `# readiness: ready`. Requiring the last speech event to be a
    spoken fragment would stall each of them at the 90 s bound and red five green screens before any
    command was driven. The dropped utterance is the command window's own fact and is judged on that
    window's final fragments, so readiness stays "any real fragment".
    """

    # (artifact reference, the header's recorded fragments, the dropped row that ends the window).
    # The dropped rows are VERBATIM at the cited line; the fragment rows are rebuilt with `_spoken`
    # from each artifact's own `# fragment:` values, because the truncated `# log tail:` no longer
    # carries the earlier spoken rows.
    DROPPED_LAST = (
        (
            "dark-1.0/talkback-readiness-presentation-reconciliation.txt:48",
            ("TalkBack on", "TalkBack off"),
            'E/talkback: SpeechControllerImpl(28258): TTS is not ready. Attempted to speak before '
            'TTS was initialized. Item: {utteranceId:"", fragments:[{text:账户对账, '
            "earcons:[2132017175], haptics:[2130903141], speechParams:Bundle[{pitch=1.0}]"
            "nonSpeechParams:Bundle[{}]fragmentStartIndex:0}], uninterruptible:false, "
            "isGloballyUninterruptible:false, mCanIgnoreInterrupts:false, "
            "flushGlobalTtsQueue:false, flags:2148, creationTime:1795820079462}",
        ),
        (
            "dark-1.3/talkback-readiness-presentation-catalog-categories-lower.txt:54",
            ("TalkBack off",),
            "E/talkback: SpeechControllerImpl( 3464): TTS is not ready. Attempted to speak before "
            'TTS was initialized. Item: {utteranceId:"", fragments:[{text:追加二级, '
            "earcons:[2132017155], haptics:[2130903137], speechParams:Bundle[{pitch=1.0}]"
            "nonSpeechParams:Bundle[{}]fragmentStartIndex:0}, {text:Button, earcons:[], haptics:[], "
            "speechParams:Bundle[{}]nonSpeechParams:Bundle[{}]fragmentStartIndex:0}], "
            "uninterruptible:false, isGloballyUninterruptible:false, mCanIgnoreInterrupts:false, "
            "flushGlobalTtsQueue:false, flags:2148, creationTime:1795821301487}",
        ),
        (
            "dark-1.3/talkback-readiness-presentation-catalog-categories-upper.txt:51",
            ("TalkBack on", "TalkBack off"),
            "E/talkback: SpeechControllerImpl( 3464): TTS is not ready. Attempted to speak before "
            'TTS was initialized. Item: {utteranceId:"", fragments:[{text:跨日界样本账户（资产）, '
            "earcons:[2132017155], haptics:[2130903141], speechParams:Bundle[{pitch=1.0}]"
            "nonSpeechParams:Bundle[{}]fragmentStartIndex:0}], uninterruptible:false, "
            "isGloballyUninterruptible:false, mCanIgnoreInterrupts:false, "
            "flushGlobalTtsQueue:false, flags:2148, creationTime:1795821225938}",
        ),
        (
            "light-1.3/talkback-readiness-presentation-asset.txt:66",
            ("TalkBack on", "TalkBack off"),
            "E/talkback: SpeechControllerImpl(15700): TTS is not ready. Attempted to speak before "
            'TTS was initialized. Item: {utteranceId:"", fragments:[{text:账户, '
            "earcons:[2132017155], haptics:[2130903141], speechParams:Bundle[{pitch=1.0}]"
            "nonSpeechParams:Bundle[{}]fragmentStartIndex:0}], uninterruptible:false, "
            "isGloballyUninterruptible:false, mCanIgnoreInterrupts:false, "
            "flushGlobalTtsQueue:false, flags:2148, creationTime:1795819080232}",
        ),
        (
            "light-1.3/talkback-readiness-presentation-catalog-management.txt:14",
            ("TalkBack on", "TalkBack off"),
            "E/talkback: SpeechControllerImpl(15700): TTS is not ready. Attempted to speak before "
            'TTS was initialized. Item: {utteranceId:"", fragments:[{text:账户与分类管理, '
            "earcons:[2132017155], haptics:[2130903141], speechParams:Bundle[{pitch=1.0}]"
            "nonSpeechParams:Bundle[{}]fragmentStartIndex:0}], uninterruptible:false, "
            "isGloballyUninterruptible:false, mCanIgnoreInterrupts:false, "
            "flushGlobalTtsQueue:false, flags:2148, creationTime:1795819307924}",
        ),
    )

    def test_a_plain_spoken_window_is_ready(self) -> None:
        # The locale-independent limb itself is unchanged: one real fragment is readiness, whatever
        # language the startup announcement is spoken in.
        self.assertEqual(
            (True, ["TalkBack on"]), talkback_readiness(_artifact(_SPEAKING_STARTUP))
        )

    def test_a_dropped_utterance_after_a_spoken_fragment_is_still_ready(self) -> None:
        for where, fragments, dropped in self.DROPPED_LAST:
            spoken_rows = [_spoken(fragment) for fragment in fragments]
            log = _artifact(*spoken_rows, dropped)
            self.assertEqual((True, list(fragments)), talkback_readiness(log), where)
            # The dropped row really is the LAST speech event of the window, so the case the old
            # rule reded is the case under test.
            self.assertIn("TTS is not ready", dropped, where)
            self.assertGreater(log.rindex(dropped), log.rindex(spoken_rows[-1]), where)

    def test_a_window_that_never_spoke_is_not_ready(self) -> None:
        for where, _fragments, dropped in self.DROPPED_LAST:
            self.assertEqual((False, []), talkback_readiness(_artifact(dropped)), where)

    def _sampler(self, evidence: Path, logs: list[str]) -> Sampler:
        class _EngineWaitSampler(Sampler):
            def __init__(self, path: Path, texts: list[str]) -> None:
                super().__init__(
                    adb_serial=None, evidence=path, font_scale=1.3, avd_name="ul-presentation"
                )
                self.texts = list(texts)

            def logcat_tail(self, line_count: int = 4000) -> str:
                return self.texts.pop(0) if len(self.texts) > 1 else self.texts[0]

        return _EngineWaitSampler(evidence, logs)

    def test_the_readiness_gate_accepts_a_dropped_last_row_without_waiting(self) -> None:
        # The generate side of the same rule: the first command is driven immediately, and the
        # artifact records `ready` instead of stalling to the 90 s bound.
        where, fragments, dropped = self.DROPPED_LAST[0]
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            sampler = self._sampler(
                evidence, [_artifact(*(_spoken(fragment) for fragment in fragments), dropped)]
            )
            with mock.patch("android_fontscale.sampler.TALKBACK_READY_TIMEOUT_S", 0.01), mock.patch(
                "android_fontscale.sampler.TALKBACK_READY_POLL_INTERVAL_S", 0.0
            ):
                record = sampler.wait_talkback_ready("reconciliation")
            artifact = (evidence / "talkback-readiness-reconciliation.txt").read_text(encoding="utf-8")
        self.assertEqual("ready", record["status"], where)
        self.assertEqual(len(fragments), record["fragments"], where)
        self.assertEqual("# readiness: ready", artifact.splitlines()[0])
        for fragment in fragments:
            self.assertIn(f"# fragment: {fragment}", artifact)
        self.assertNotIn("dropped utterance", artifact)


class TalkbackSilentWindowRetry(unittest.TestCase):
    """D-369 E2f: each transient owns one reissue, and the total stays bounded by the command cap."""

    TARGET = "日用品"

    class _RetrySampler(Sampler):
        def __init__(self, evidence: Path, windows: list[dict[str, object]]) -> None:
            super().__init__(
                adb_serial=None, evidence=evidence, font_scale=1.3, avd_name="ul-presentation"
            )
            self.windows = list(windows)
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
            # Two polls is the real "only finished starting inside this pass" transient the middle
            # screen's first command hit (light-1.0: `# ready: waited=1.1s polls=2 fragments=1`).
            return {"status": "ready", "waited_s": 1.1, "polls": 2, "fragments": 1}

        def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
            return {"clears": 0, "quiet_reads": 2, "quiet_after_round": 2, "rounds": 2}

        def handle_notification_permission_dialog(self) -> bool:
            return False

        def talkback_focus(self, target: str, command_id: int) -> dict[str, object]:
            self.focus_ids.append(command_id)
            window = dict(self.windows.pop(0))
            window["id"] = command_id
            window["target"] = target
            return window

        def disable_talkback_accessibility(self) -> None:
            pass

        def wait_accessibility_disabled(self) -> bool:
            return True

    def _screen(self) -> Screen:
        return Screen(
            "presentation-catalog-categories-middle",
            (),
            PRESENTATION_CATEGORY_DAILY,
            speech=(SpeechCheck(self.TARGET, (self.TARGET,)),),
        )

    def _drive(
        self,
        windows: list[dict[str, object]],
        *,
        warmup: bool = False,
        start_sequence: int = 0,
    ) -> tuple[dict[str, object], list[int], str]:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            sampler = self._RetrySampler(evidence, windows)
            sampler.talkback_sequence = start_sequence
            record = sampler.p806_talkback_screen(
                self._screen(), target_screenshots=False, warmup=warmup
            )
            artifact = (evidence / "talkback-presentation-catalog-categories-middle.txt").read_text(
                encoding="utf-8"
            )
        return record, sampler.focus_ids, artifact

    def test_the_refusal_then_silent_shape_gets_its_own_reissue(self) -> None:
        # The exact middle shape of run 46074246784: cmd 16 refused ("TalkBack only finished
        # starting inside this pass") -> cmd 17 accepted but silent -> the silent transient must
        # still own a reissue, so cmd 18 speaks and the screen succeeds. A shared command counter
        # would spend the one reissue on the refusal and leave the silent window red.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, accepted=False),
            _probe_window(command_id=2, target=self.TARGET),
            _probe_window(command_id=3, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2, 3], focus_ids)
        self.assertEqual(1, TALKBACK_FOCUS_RETRY_BUDGET)
        self.assertEqual(1, TALKBACK_SILENT_RETRY_BUDGET)
        self.assertEqual(1, TALKBACK_LOST_COMMAND_RETRY_BUDGET)
        self.assertEqual(1, TALKBACK_NEVER_ENDED_RETRY_BUDGET)
        self.assertEqual(5, TALKBACK_COMMAND_ATTEMPTS)
        self.assertEqual(1, artifact.count("# retry=1"))
        self.assertEqual(1, artifact.count("# retry=2"))
        self.assertIn("# retry=1 previous-command: 1 reason: TalkBack only finished starting", artifact)
        self.assertIn("# retry=2 previous-command: 2", artifact)
        self.assertIn("produced no utterance", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(3, record["checks"][0]["command_id"])
        # The verdict is re-derived from the window that decided, never from the first attempt.
        self.assertEqual(
            "success", talkback_check_verdict(artifact, 3, self.TARGET, (self.TARGET,))[0]
        )

    def test_all_three_transients_can_fire_in_one_check(self) -> None:
        # Lost -> refused -> silent -> spoken uses one reissue per transient and proves the total cap
        # admits all three while still deriving the verdict from the last window.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="nothing"),
            _probe_window(command_id=2, target=self.TARGET, accepted=False),
            _probe_window(command_id=3, target=self.TARGET),
            _probe_window(command_id=4, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2, 3, 4], focus_ids)
        self.assertEqual(3, artifact.count("# retry="))
        self.assertIn("never reached the probe", artifact)
        self.assertIn("TalkBack only finished starting", artifact)
        self.assertIn("produced no utterance", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(4, record["checks"][0]["command_id"])

    def test_an_exhausted_silent_budget_keeps_the_final_silent_window_red(self) -> None:
        # Refused -> silent -> silent exhausts the silent budget, and the FINAL window is silent, so
        # the check stays red even though the first window carried speech.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, accepted=False, fragments=(self.TARGET,)),
            _probe_window(command_id=2, target=self.TARGET),
            _probe_window(command_id=3, target=self.TARGET),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2, 3], focus_ids)
        self.assertEqual("fragments-missing", record["status"])
        self.assertEqual(3, record["checks"][0]["command_id"])
        self.assertEqual(2, artifact.count("# retry="))
        # The earlier window DID carry an utterance; it is not allowed to turn the final silence
        # green. This is the "never loosen fragments-missing" pin.
        self.assertEqual(
            [self.TARGET],
            talkback_check_verdict(artifact, 1, self.TARGET, (self.TARGET,))[1],
        )
        self.assertEqual(
            "fragments-missing", talkback_check_verdict(artifact, 3, self.TARGET, (self.TARGET,))[0]
        )

    def test_a_silent_window_is_reissued_once(self) -> None:
        windows = [
            _probe_window(command_id=1, target=self.TARGET),
            _probe_window(command_id=2, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2], focus_ids)
        self.assertIn("# retry=1 previous-command: 1", artifact)
        self.assertIn("produced no utterance", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])

    def test_two_silent_windows_fail_at_the_silent_budget(self) -> None:
        windows = [
            _probe_window(command_id=1, target=self.TARGET),
            _probe_window(command_id=2, target=self.TARGET),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual(1 + TALKBACK_SILENT_RETRY_BUDGET, len(focus_ids))
        self.assertEqual([1, 2], focus_ids)
        self.assertEqual("fragments-missing", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(1, artifact.count("# retry="))

    def test_a_lost_command_is_reissued_under_a_fresh_id(self) -> None:
        # light-1.0's home shape: no probe rows at all, so the workspace's `# command: 1` window
        # never began. The same command id completed and succeeded on the other three combinations,
        # so the delivery was lost and a fresh id is the natural remedy.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="nothing"),
            _probe_window(command_id=2, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2], focus_ids)
        self.assertIn("# retry=1 previous-command: 1", artifact)
        self.assertIn("never reached the probe", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(
            "success", talkback_check_verdict(artifact, 2, self.TARGET, (self.TARGET,))[0]
        )

    def test_a_second_lost_command_is_not_reissued(self) -> None:
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="nothing"),
            _probe_window(command_id=2, target=self.TARGET, stopped="nothing"),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual(1 + TALKBACK_LOST_COMMAND_RETRY_BUDGET, len(focus_ids))
        self.assertEqual([1, 2], focus_ids)
        self.assertEqual(1, artifact.count("# retry="))
        self.assertEqual("not-obtained", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])

    def test_all_four_transients_can_fire_in_one_check(self) -> None:
        # Lost -> refused -> silent -> opened-but-never-closed -> spoken: one reissue per named
        # transient, and the cap admits all four while the verdict still comes from the last window.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="nothing"),
            _probe_window(command_id=2, target=self.TARGET, accepted=False),
            _probe_window(command_id=3, target=self.TARGET),
            _probe_window(command_id=4, target=self.TARGET, stopped="focus-result"),
            _probe_window(command_id=5, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2, 3, 4, 5], focus_ids)
        self.assertEqual(4, artifact.count("# retry="))
        self.assertIn("never reached the probe", artifact)
        self.assertIn("TalkBack only finished starting", artifact)
        self.assertIn("produced no utterance", artifact)
        self.assertIn("began but never ended", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(5, record["checks"][0]["command_id"])

    def test_a_window_that_began_but_never_ended_is_reissued_once(self) -> None:
        # D-369 E2g supersedes E2f's "never ended is deterministic" ruling: run 46088630336's
        # light-1.3 windows opened, carried only TalkBack's startup announcement and never closed,
        # while the warmed dark-1.3 combination spoke the same targets. One whole reissue under a
        # fresh id is allowed; the second window speaks and decides the check.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="focus-result"),
            _probe_window(command_id=2, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2], focus_ids)
        self.assertIn("# retry=1 previous-command: 1", artifact)
        self.assertIn("began but never ended", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(
            "success", talkback_check_verdict(artifact, 2, self.TARGET, (self.TARGET,))[0]
        )

    def test_a_second_never_ended_window_still_fails_red(self) -> None:
        windows = [
            _probe_window(command_id=1, target=self.TARGET, stopped="focus-result"),
            _probe_window(command_id=2, target=self.TARGET, stopped="focus-result"),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2], focus_ids)
        self.assertEqual(1, artifact.count("# retry="))
        self.assertEqual("not-obtained", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(
            "not-obtained", talkback_check_verdict(artifact, 2, self.TARGET, (self.TARGET,))[0]
        )

    def test_the_real_sweep5_never_ended_window_is_reissued_and_its_successor_decides(self) -> None:
        # The command-15 fixture is VERBATIM from run 46088630336's light-1.3 middle artifact: the
        # window opened and only TalkBack's own "TalkBack on" was spoken inside it. The reissue under
        # id 16 speaks the target, and both the host record and the artifact derive that successor.
        windows = [
            {
                "id": 15,
                "target": self.TARGET,
                "began": True,
                "completed": False,
                "accepted": True,
                "problem": "command id=15's speech window never ended",
                "lines": list(_SWEEP5_NEVER_ENDED),
            },
            _probe_window(command_id=16, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows, start_sequence=14)
        self.assertEqual([15, 16], focus_ids)
        self.assertIn("# retry=1 previous-command: 15", artifact)
        self.assertIn("began but never ended", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(16, record["checks"][0]["command_id"])
        self.assertEqual(
            "success", talkback_check_verdict(artifact, 16, self.TARGET, (self.TARGET,))[0]
        )
        # The first window itself is still red in isolation: the reissue earns a new window, it
        # never relabels the old one.
        self.assertEqual(
            "not-obtained", talkback_check_verdict(artifact, 15, self.TARGET, (self.TARGET,))[0]
        )

    def test_a_structurally_broken_window_is_reported_red(self) -> None:
        # The artifact itself carries a duplicated begin stage, so the window is structurally
        # broken rather than lost or silent; it is reported red without a reissue.
        window = _probe_window(command_id=1, target=self.TARGET)
        lines = list(window["lines"])
        lines.insert(3, lines[2])
        window = dict(
            window,
            lines=lines,
            problem="command id=1 records begin more than once",
        )
        record, focus_ids, artifact = self._drive([window])
        self.assertEqual([1], focus_ids)
        self.assertNotIn("# retry=", artifact)
        self.assertEqual("not-obtained", record["status"])

    def test_a_spoken_window_is_not_retried(self) -> None:
        windows = [_probe_window(command_id=1, target=self.TARGET, fragments=(self.TARGET,))]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1], focus_ids)
        self.assertNotIn("# retry=", artifact)
        self.assertEqual("success", record["status"])


class TalkbackSpeechWarmup(unittest.TestCase):
    """D-369 E2g: the presentation combination's first speech screen opens with one warm-up command.

    Run 46088630336's light-1.3 windows opened, carried only TalkBack's own startup announcement,
    and never ended. The warm-up pays the combination's cold TTS start there — before that screen's
    graded commands — and its rows are `# warmup-…` (never a `# command:` check): it can never
    satisfy one, and the graded verdict still comes from the last graded window alone.
    """

    TARGET = "日用品"

    def _drive(self, windows, *, warmup=True):
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            sampler = TalkbackSilentWindowRetry._RetrySampler(evidence, windows)
            record = sampler.p806_talkback_screen(
                TalkbackSilentWindowRetry()._screen(),
                target_screenshots=False,
                warmup=warmup,
            )
            artifact = (evidence / "talkback-presentation-catalog-categories-middle.txt").read_text(
                encoding="utf-8"
            )
        return record, sampler.focus_ids, artifact

    def test_the_warmup_command_precedes_the_graded_command(self):
        windows = [
            _probe_window(command_id=1, target=self.TARGET, fragments=(self.TARGET,)),
            _probe_window(command_id=2, target=self.TARGET, fragments=(self.TARGET,)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1, 2], focus_ids)
        self.assertIn("# warmup-target: 日用品", artifact)
        self.assertIn("# warmup-command: 1", artifact)
        self.assertIn("# warmup-result: success", artifact)
        self.assertLess(artifact.index("# warmup-command: 1"), artifact.index("# command: 2"))
        # The warm-up is evidence, never a check: exactly one `# command:` header exists and the
        # gate's own header binding (`# target:` immediately before it) still points at only that id.
        self.assertEqual(1, artifact.count("# command:"))
        lines = artifact.splitlines()
        index = lines.index("# command: 2")
        self.assertEqual("# target: 日用品", lines[index - 1])
        self.assertEqual("success", record["status"])
        self.assertEqual(2, record["checks"][0]["command_id"])
        self.assertEqual(
            "success", talkback_check_verdict(artifact, 2, self.TARGET, (self.TARGET,))[0]
        )

    def test_a_warmup_that_only_speaks_the_startup_announcement_reds_the_screen(self):
        # "TalkBack on" IS a fragment by the parser's definition, so the warm-up cannot be "any
        # fragment"; it must carry the target's own required speech. This is the sweep5 cold-start
        # shape, and it fails the screen closed before a graded command is driven.
        windows = [
            _probe_window(command_id=1, target=self.TARGET, fragments=("TalkBack on",)),
        ]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1], focus_ids)
        self.assertIn("# warmup-result: amount-wrong", artifact)
        self.assertIn("# speech failed:", artifact)
        self.assertNotIn("# command:", artifact)
        self.assertEqual("not-obtained", record["status"])
        self.assertIn("speech warm-up", record["checks"][0]["reason"])
        self.assertIn("日用品", record["checks"][0]["reason"])

    def test_a_warmup_window_that_never_ends_reds_the_screen(self):
        windows = [_probe_window(command_id=1, target=self.TARGET, stopped="focus-result")]
        record, focus_ids, artifact = self._drive(windows)
        self.assertEqual([1], focus_ids)
        self.assertIn("# warmup-result: not-obtained", artifact)
        self.assertEqual("not-obtained", record["status"])
        self.assertIn("never ended", record["checks"][0]["reason"])

    def test_the_warmup_is_scoped_to_the_presentation_first_speech_screen(self):
        self.assertEqual(
            "presentation-home",
            speech_warmup_screen(profile_by_name(PRESENTATION_PROFILE_NAME)),
        )
        # The presentation table's own order is what "first" means; pin it so a reorder that puts
        # an unscrollable or unlaunched screen first is visible here.
        self.assertEqual(
            "presentation-home",
            next(screen.stem for screen in PRESENTATION_SCREENS if screen.speech),
        )
        for name in ("empty", "seeded", "refund", "p806"):
            self.assertEqual("", speech_warmup_screen(profile_by_name(name)), name)
        # Exactly one screen carries the warm-up: the comparison in `run()` can therefore match
        # only that stem, even if a later screen is added.
        self.assertEqual(
            1,
            sum(
                1
                for screen in PRESENTATION_SCREENS
                if screen.speech
                and screen.stem == speech_warmup_screen(profile_by_name(PRESENTATION_PROFILE_NAME))
            ),
        )

    def test_a_non_warmup_drive_starts_with_the_graded_command(self):
        windows = [_probe_window(command_id=1, target=self.TARGET, fragments=(self.TARGET,))]
        record, focus_ids, artifact = self._drive(windows, warmup=False)
        self.assertEqual([1], focus_ids)
        self.assertNotIn("# warmup", artifact)
        self.assertEqual("success", record["status"])
        self.assertEqual(1, record["checks"][0]["command_id"])


class TalkbackRetryPredicates(unittest.TestCase):
    """The retry predicates are decisions about the artifact, not about elapsed time.

    Five shapes, five outcomes:
    - focus-refused + a named transient -> reissue (`focus_retry_reason`);
    - completed, accepted, no utterance -> reissue (`talkback_silent_retry_reason`);
    - no probe `command` stage at all -> reissue (`talkback_lost_command_retry_reason`);
    - opened and never ended -> reissue (`talkback_never_ended_retry_reason`, D-369 E2g);
    - an inconsistent stage sequence inside a COMPLETED window -> red as it stands.
    """

    TARGET = "日用品"

    def _window(self, **kwargs: object) -> dict[str, object]:
        return _probe_window(command_id=1, target=self.TARGET, **kwargs)

    def test_only_a_completed_accepted_window_without_its_own_utterance_is_silent(self) -> None:
        self.assertIn("no utterance", talkback_silent_retry_reason(self._window()))
        self.assertEqual(
            "", talkback_silent_retry_reason(self._window(fragments=(self.TARGET,)))
        )
        # A fragment before the begin stage belongs to another command's window.
        self.assertIn(
            "no utterance",
            talkback_silent_retry_reason(
                self._window(pre_begin=(_spoken(self.TARGET, utterance_id="prior"),))
            ),
        )
        # A refused focus has its own named transient (`focus_retry_reason`), not this one.
        self.assertEqual(
            "", talkback_silent_retry_reason(self._window(accepted=False))
        )
        # A broken completed window is not re-rolled either.
        self.assertEqual(
            "",
            talkback_silent_retry_reason(self._window(problem="command id=1 began more than one window")),
        )
        # A window that began but never ended owns its own transient (D-369 E2g), not this one.
        self.assertEqual(
            "",
            talkback_silent_retry_reason(self._window(stopped="focus-result")),
        )

    def test_only_a_window_without_the_probe_command_stage_is_lost(self) -> None:
        self.assertIn(
            "never reached the probe",
            talkback_lost_command_retry_reason(self._window(stopped="nothing")),
        )
        self.assertEqual(
            "",
            talkback_lost_command_retry_reason(self._window()),
        )
        # Began but never ended: its own transient (D-369 E2g), not a lost delivery.
        self.assertEqual(
            "",
            talkback_lost_command_retry_reason(self._window(stopped="focus-result")),
        )
        # A completed window with an inconsistent stage sequence is deterministic too.
        self.assertEqual(
            "",
            talkback_lost_command_retry_reason(
                self._window(problem="command id=1 began more than one window")
            ),
        )


class TalkbackNeverEndedRetry(unittest.TestCase):
    """D-369 E2g: an opened-but-never-closed window is its own transient, derived from the rows.

    The predicate reads the probe's own `speech-window-begin`/`speech-window-end` stages for the
    command id (the same scan the isolator uses), so a target-not-found window that never opened a
    speech window stays outside it, and a completed window can never trip it.
    """

    def test_the_sweep5_window_is_an_opened_never_closed_transient(self):
        window, problem = isolate_talkback_window(_artifact(*_SWEEP5_NEVER_ENDED), 15)
        self.assertEqual(list(_SWEEP5_NEVER_ENDED), window)
        self.assertEqual("command id=15's speech window never ended", problem)
        self.assertTrue(talkback_window_began(window, 15))
        fixture = {
            "id": 15,
            "began": True,
            "completed": False,
            "accepted": True,
            "problem": problem,
            "lines": window,
        }
        self.assertTrue(talkback_window_never_ended(fixture))
        self.assertIn("began but never ended", talkback_never_ended_retry_reason(fixture))

    def test_a_completed_window_is_not_this_transient(self):
        fixture = _probe_window(command_id=1, target="日用品")
        self.assertFalse(talkback_window_never_ended(fixture))
        self.assertEqual("", talkback_never_ended_retry_reason(fixture))

    def test_a_target_not_found_window_never_opened_its_speech_window(self):
        # No begin stage: the probe stopped at `target ... found=false`. The reissue is for an
        # OPENED window only; this stays a red target rather than a cold-start transient.
        encoded = base64.b64encode("日用品".encode("utf-8")).decode("ascii")
        rows = (
            f'I/ULA11Y  ( 4733): command action=focus id=9 needle64={encoded} target="日用品"',
            "I/ULA11Y  ( 4733): target id=9 found=false root=null",
        )
        window, problem = isolate_talkback_window(_artifact(*rows), 9)
        self.assertEqual("command id=9 carries no begin stage", problem)
        fixture = {
            "id": 9,
            "began": True,
            "completed": False,
            "accepted": False,
            "problem": problem,
            "lines": window,
        }
        self.assertFalse(talkback_window_never_ended(fixture))
        self.assertEqual("", talkback_never_ended_retry_reason(fixture))

    def test_the_never_ended_budget_bounds_the_command_cap(self):
        self.assertEqual(1, TALKBACK_NEVER_ENDED_RETRY_BUDGET)
        self.assertEqual(5, TALKBACK_COMMAND_ATTEMPTS)


class TalkbackWindowBegan(unittest.TestCase):
    """The probe's stage-1 fact is DERIVED from the window rows, never supplied by the caller.

    Both real and fixture windows are checked here: the seven-line lost artifact has no probe row at
    all, while a window that logs its `command …` row and then never ends still began. This is the
    fact `talkback_lost_command_retry_reason` discriminates on, so a future change that assumes the
    fact (or drops its derivation) reds here and on the reissue tests.
    """

    def test_the_real_lost_command_artifact_has_no_command_stage(self) -> None:
        self.assertEqual(7, len(_LOST_COMMAND_ARTIFACT))
        self.assertEqual("# command: 1", _LOST_COMMAND_ARTIFACT[-1])
        self.assertFalse(
            any(
                line.startswith(("V/", "D/", "I/", "E/", "W/", "F/"))
                for line in _LOST_COMMAND_ARTIFACT
            )
        )
        self.assertFalse(talkback_window_began(list(_LOST_COMMAND_ARTIFACT), 1))
        window, problem = isolate_talkback_window(_artifact(*_LOST_COMMAND_ARTIFACT), 1)
        self.assertEqual([], window)
        self.assertEqual("command id=1 never began a speech window", problem)

    def test_a_command_stage_without_a_window_end_still_began(self) -> None:
        fixture = _probe_window(command_id=7, target="日用品", stopped="focus-result")
        self.assertTrue(talkback_window_began(fixture["lines"], 7))
        self.assertEqual("command id=7's speech window never ended", fixture["problem"])
        self.assertEqual("", talkback_lost_command_retry_reason(fixture))

    def test_a_command_stage_for_another_id_is_not_this_window(self) -> None:
        fixture = _probe_window(command_id=2, target="日用品", stopped="focus-result")
        self.assertFalse(talkback_window_began(fixture["lines"], 3))

    def test_the_lost_reissue_depends_on_the_derived_began_fact(self) -> None:
        lost = _probe_window(command_id=1, target="日用品", stopped="nothing")
        self.assertFalse(lost["began"])
        self.assertIn("never reached the probe", talkback_lost_command_retry_reason(lost))
        # Force the fact and the lost transient disappears: this is what a "began is always true"
        # change does to the reissue test below.
        self.assertEqual(
            "", talkback_lost_command_retry_reason(dict(lost, began=True))
        )

    def test_the_fixture_shape_matches_the_production_isolator(self) -> None:
        # The fixtures must carry production's own window slice and problem text; a fixture with an
        # empty problem is what let a `problem`-based regression pass unnoticed.
        never_ended = _probe_window(command_id=1, target="日用品", stopped="focus-result")
        window, problem = isolate_talkback_window(
            _artifact(*never_ended["lines"]), 1
        )
        self.assertEqual(never_ended["lines"], window)
        self.assertEqual(never_ended["problem"], problem)
        lost = _probe_window(command_id=1, target="日用品", stopped="nothing")
        lost_window, lost_problem = isolate_talkback_window(_artifact(*lost["lines"]), 1)
        self.assertEqual([], lost_window)
        self.assertEqual(lost["problem"], lost_problem)


if __name__ == "__main__":
    unittest.main()
