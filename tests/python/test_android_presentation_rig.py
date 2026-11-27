"""D-369 E1 presentation rig configuration proofs (no device, no emulator, no Gradle)."""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from android_fontscale.sampler import (
    BACK,
    DEFAULT_UI_MODE,
    P8_00_ASSET_TARGET,
    P806_REPORT_TITLE,
    PRESENTATION_CALENDAR_EMPTY_MARKER,
    PRESENTATION_CALENDAR_EMPTY_TITLE,
    PRESENTATION_CALENDAR_EMPTY_VISIBLE_ABSENT,
    PRESENTATION_DEFERRED_SCENARIOS,
    PRESENTATION_EVIDENCE_PLAN,
    PRESENTATION_HOME_COUNT,
    PRESENTATION_MANAGEMENT_ABSENT,
    PRESENTATION_MANAGEMENT_MARKER,
    PRESENTATION_MANAGEMENT_TITLE,
    PRESENTATION_MULTI_CATEGORY_NOTE,
    PRESENTATION_MULTI_CATEGORY_REMAINING,
    PRESENTATION_MULTI_CURRENCY_MARKER,
    PRESENTATION_MULTI_CURRENCY_NOTE,
    PRESENTATION_PROFILE_NAME,
    PRESENTATION_RANKING_MARKER,
    PRESENTATION_RANKING_TEXT,
    PRESENTATION_SCENARIO_STEMS,
    PRESENTATION_SCREENS,
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
    "presentation-multiple-categories",
    "presentation-multi-currency",
    "presentation-asset",
    "presentation-account-flow",
    "presentation-reconciliation",
    "presentation-catalog-management",
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
        # The route inherits the entry-tap offset, so its title can be above the viewport; the
        # on-screen category button is the speech target and is scrolled to after the route opens.
        self.assertEqual(ScrollStep(PRESENTATION_MANAGEMENT_MARKER), management_steps[-1])
        self.assertEqual(
            (PRESENTATION_MANAGEMENT_MARKER,),
            tuple(check.target for check in by_stem["presentation-catalog-management"].speech),
        )
        # I3: a real TalkBack target must be IN the window, so the two screens whose speech target
        # can sit below the fold scroll to the exact target before the speech pass.
        self.assertEqual(
            (TapStep("账户"), ScrollStep(P8_00_ASSET_TARGET)),
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

    def test_scenario_mapping_covers_the_implemented_stems_and_defers_two(self):
        captured = {screen.stem for screen in PRESENTATION_SCREENS}
        mapped: list[str] = []
        for name, stems in PRESENTATION_SCENARIO_STEMS:
            self.assertTrue(stems, name)
            mapped.extend(stems)
            if name in PRESENTATION_DEFERRED_SCENARIOS:
                self.assertFalse(captured & set(stems), name)
            else:
                self.assertTrue(set(stems) <= captured, name)
        # One stem never stands for two scenarios.
        self.assertEqual(len(mapped), len(set(mapped)))
        self.assertEqual(["cross-day-reconciliation", "technical-details"], list(PRESENTATION_DEFERRED_SCENARIOS))
        self.assertIn(PRESENTATION_MANAGEMENT_MARKER, {screen.marker for screen in PRESENTATION_SCREENS})
        self.assertIn("presentation-catalog-management", captured)
        self.assertEqual([], gate_evidence_plan_problems(PRESENTATION_PROFILE_NAME))
        self.assertEqual([], gate_evidence_plan_problems("seeded"))

    def test_evidence_plan_gate_rejects_a_broken_mapping(self):
        screens = (Screen("presentation-home", (), PRESENTATION_HOME_COUNT),)
        plan = ("ordinary-expense", "technical-details")

        def problems(scenario_stems, deferred=("technical-details",)):
            return gate_evidence_plan_problems(
                PRESENTATION_PROFILE_NAME,
                screens=screens,
                evidence_plan=plan,
                scenario_stems=scenario_stems,
                deferred=deferred,
            )

        complete = (
            ("ordinary-expense", ("presentation-home",)),
            ("technical-details", ("presentation-technical-collapsed",)),
        )
        # The management screen is missing from the synthetic table, which is a problem by itself.
        self.assertEqual(
            ["the D7 management screen stem presentation-catalog-management is absent"],
            problems(complete),
        )
        # A mapped stem the profile does not capture is red.
        uncaptured = (
            ("ordinary-expense", ("presentation-ordinary-expense",)),
            ("technical-details", ("presentation-technical-collapsed",)),
        )
        self.assertTrue(any("does not capture" in problem for problem in problems(uncaptured)))
        # A deferred scenario that already names a captured stem is red.
        deferred_captured = (
            ("ordinary-expense", ("presentation-home",)),
            ("technical-details", ("presentation-home",)),
        )
        self.assertTrue(any("already maps captured stem" in problem for problem in problems(deferred_captured)))
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
        # Plan section 2.2 requires each screen to declare marker, absent, required and speech, and
        # Block B's deferred screens are the only ones absent. Every current screen therefore
        # carries all four, and every speech target names its required literal.
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
