"""D-369 E1 presentation rig configuration proofs (no device, no emulator, no Gradle)."""
from __future__ import annotations

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
    TapStep,
    UI_MODES,
    gate_evidence_plan_problems,
    gate_profile_problems,
    gate_ui_mode_problems,
    marker_problems,
    screen_problems,
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
    "presentation-catalog-categories-lower",
    "presentation-analysis",
    "presentation-calendar-empty",
]


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
        # window node still reports [0,0][1080,2400]). Four group rows plus six leaf rows - ten 48 dp
        # button rows alone - cannot be inside one viewport, so the tree is captured as two stack
        # frames, each anchored on the leaf row BELOW the two group names it requires.
        by_stem = {screen.stem: screen for screen in PRESENTATION_SCREENS}
        management = by_stem["presentation-catalog-management"]
        upper = by_stem["presentation-catalog-categories-upper"]
        lower = by_stem["presentation-catalog-categories-lower"]
        # The accounts screen keeps the route guard and now names the account rows it photographs.
        self.assertEqual((PRESENTATION_MANAGEMENT_ABSENT,), management.absent)
        self.assertEqual(PRESENTATION_MANAGEMENT_MARKER, management.marker)
        self.assertEqual(PRESENTATION_MANAGEMENT_ACCOUNTS, management.required)
        # All four seeded group names stay required, each on exactly one of the two captures.
        required = upper.required + lower.required
        for name in PRESENTATION_MANAGEMENT_CATEGORIES:
            self.assertEqual(1, required.count(name), name)
        self.assertEqual(
            (PRESENTATION_CATEGORY_TRANSPORT, PRESENTATION_CATEGORY_TRAVEL, PRESENTATION_CATEGORY_TRAVEL_FLIGHT),
            upper.required,
        )
        self.assertEqual(
            (PRESENTATION_CATEGORY_DAILY, PRESENTATION_CATEGORY_FOOD, PRESENTATION_CATEGORY_FOOD_LUNCH),
            lower.required,
        )
        # Each frame scrolls forward to the leaf path one row BELOW its required block and uses that
        # same path as its marker: the forward-only swipe stops at first appearance, so the block
        # above the target is what stays in the window.
        self.assertEqual((ScrollStep(PRESENTATION_CATEGORY_TRAVEL_FLIGHT),), upper.steps)
        self.assertEqual(PRESENTATION_CATEGORY_TRAVEL_FLIGHT, upper.marker)
        self.assertEqual((ScrollStep(PRESENTATION_CATEGORY_FOOD_LUNCH),), lower.steps)
        self.assertEqual(PRESENTATION_CATEGORY_FOOD_LUNCH, lower.marker)
        # They sit straight after the accounts screen, in the rendered (name-sorted) order, so every
        # swipe moves forward and no capture ever needs to scroll back up.
        stems = [screen.stem for screen in PRESENTATION_SCREENS]
        self.assertEqual(
            stems.index("presentation-catalog-management") + 1,
            stems.index("presentation-catalog-categories-upper"),
        )
        self.assertEqual(
            stems.index("presentation-catalog-categories-upper") + 1,
            stems.index("presentation-catalog-categories-lower"),
        )
        # Dropping either capture un-covers at least one group name: the split is load bearing.
        for dropped in ("presentation-catalog-categories-upper", "presentation-catalog-categories-lower"):
            remaining = [name for screen in PRESENTATION_SCREENS if screen.stem != dropped for name in screen.required]
            self.assertTrue(
                [name for name in PRESENTATION_MANAGEMENT_CATEGORIES if name not in remaining],
                dropped,
            )
        # The gate requires the whole management set, so dropping any one of the three stems is red.
        self.assertEqual([], gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME))
        for dropped in PRESENTATION_MANAGEMENT_STEMS:
            remaining = tuple(screen for screen in PRESENTATION_SCREENS if screen.stem != dropped)
            self.assertTrue(
                gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME, screens=remaining),
                dropped,
            )

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
        # the whole D7 management set (accounts + both category viewports) is required.
        self.assertEqual(
            [f"the D7 management screen stem {stem} is absent" for stem in PRESENTATION_MANAGEMENT_STEMS],
            problems(complete),
        )
        # Dropping ONE of the split category viewports is red by itself: the two together are what
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


if __name__ == "__main__":
    unittest.main()
