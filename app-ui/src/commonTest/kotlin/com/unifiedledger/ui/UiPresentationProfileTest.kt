package com.unifiedledger.ui

import com.unifiedledger.domain.CurrencyUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * D-369 A profile vectors: the default profile must keep the existing output exactly, and the
 * Android profile is selected by an explicit argument only. These are pure decisions, so they are
 * asserted here rather than through a Compose harness.
 */
class UiPresentationProfileTest {
    private val cny = CurrencyUnit("CNY", 2)

    @Test
    fun theDefaultProfileRendersTheLegacyFormat() {
        assertEquals("35.50", presentationAmountForProfile(3_550L, cny))
        assertEquals("-12.34", presentationAmountForProfile(-1_234L, cny))
        assertEquals(
            formatMinorUnits(-12_340L, 2),
            presentationAmountForProfile(-12_340L, cny, UiPresentationProfile.LEGACY),
        )
    }

    @Test
    fun theAndroidProfileAddsTheCurrencyCodeThroughTheApplicationProjection() {
        assertEquals("35.50 CNY", presentationAmountForProfile(3_550L, cny, UiPresentationProfile.ANDROID))
        assertEquals("-12.34 CNY", presentationAmountForProfile(-1_234L, cny, UiPresentationProfile.ANDROID))
        assertEquals("0.00 CNY", presentationAmountForProfile(0L, cny, UiPresentationProfile.ANDROID))
    }

    @Test
    fun theTwoProfilesSelectDistinctPolicies() {
        assertEquals(UiPresentationPolicy.LEGACY, uiPresentationPolicy(UiPresentationProfile.LEGACY))
        assertEquals(UiPresentationPolicy.ANDROID, uiPresentationPolicy(UiPresentationProfile.ANDROID))
        assertNotEquals(
            uiPresentationPolicy(UiPresentationProfile.LEGACY),
            uiPresentationPolicy(UiPresentationProfile.ANDROID),
        )
    }

    @Test
    fun theDefaultProfileIsLegacy() {
        assertEquals(UiPresentationProfile.LEGACY, UiPresentationProfile.entries.first())
        assertEquals(2, UiPresentationProfile.entries.size)
    }
}
