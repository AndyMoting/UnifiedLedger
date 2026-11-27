"""D-369 A presentation rig configuration proofs (no device, no emulator, no Gradle)."""
from __future__ import annotations

import unittest

from android_fontscale.sampler import (
    PRESENTATION_EVIDENCE_PLAN,
    PRESENTATION_PROFILE_NAME,
    PRESENTATION_SCREENS,
    PROFILES,
    SampleError,
    UI_MODES,
    gate_profile_problems,
    ui_mode_night_value,
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
        self.assertEqual("no", ui_mode_night_value("light"))
        self.assertEqual("yes", ui_mode_night_value("dark"))
        with self.assertRaises(SampleError):
            ui_mode_night_value("sepia")

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
