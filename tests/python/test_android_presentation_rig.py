"""D-369 A presentation rig configuration proofs (no device, no emulator, no Gradle)."""
from __future__ import annotations

import unittest

from android_fontscale.sampler import (
    DEFAULT_UI_MODE,
    P806_REPORT_TITLE,
    PRESENTATION_EVIDENCE_PLAN,
    PRESENTATION_PROFILE_NAME,
    PRESENTATION_SCREENS,
    PROFILES,
    SampleError,
    ScrollStep,
    TapStep,
    UI_MODES,
    gate_profile_problems,
    gate_ui_mode_problems,
    ui_mode_night_value,
    ui_mode_readback_problem,
)


class PresentationProfileTests(unittest.TestCase):
    def test_presentation_profile_is_registered_with_its_own_screens(self):
        profile = PROFILES[PRESENTATION_PROFILE_NAME]
        self.assertEqual(profile.name, PRESENTATION_PROFILE_NAME)
        self.assertTrue(profile.seeded)
        self.assertTrue(profile.talkback)
        self.assertEqual([screen.stem for screen in PRESENTATION_SCREENS], [screen.stem for screen in profile.screens])
        self.assertEqual(8, len(profile.screens))
        self.assertEqual(
            [
                "presentation-home",
                "presentation-detail",
                "presentation-search-results",
                "presentation-account-flow",
                "presentation-reconciliation",
                "presentation-asset",
                "presentation-analysis",
                "presentation-calendar",
            ],
            [screen.stem for screen in profile.screens],
        )

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

        presentation = next(screen for screen in PRESENTATION_SCREENS if screen.stem == "presentation-analysis")
        self.assertEqual(expected_lead, presentation.steps)
        self.assertEqual(P806_REPORT_TITLE, presentation.marker)

    def test_non_android_profiles_keep_the_default_analysis_steps(self):
        # The presentation/profile path must not leak the extra toggle into a screen set that never
        # had the Android subtraction (the LEGACY analysis page keeps the report inline).
        empty_analysis = next(screen for screen in PROFILES["empty"].screens if screen.stem == "analysis")
        self.assertEqual((TapStep("分析"),), empty_analysis.steps)
        self.assertNotIn(TapStep("更多分析"), list(empty_analysis.steps))

    def test_the_eight_future_scenarios_are_named_in_one_configuration(self):
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

    def test_presentation_screens_carry_speech_targets_for_real_talkback(self):
        speaking = [screen.stem for screen in PRESENTATION_SCREENS if screen.speech]
        self.assertEqual(
            ["presentation-home", "presentation-detail", "presentation-search-results"],
            speaking,
        )
        for screen in PRESENTATION_SCREENS:
            self.assertTrue(screen.marker, screen.stem)
            for check in screen.speech:
                self.assertTrue(check.target, screen.stem)
                self.assertTrue(check.required, screen.stem)


if __name__ == "__main__":
    unittest.main()
