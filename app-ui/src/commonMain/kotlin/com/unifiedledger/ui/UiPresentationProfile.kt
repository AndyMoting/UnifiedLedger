package com.unifiedledger.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.unifiedledger.application.presentationAmountText
import com.unifiedledger.domain.CurrencyUnit

/**
 * D-369 A: which presentation contract a composition renders. [LEGACY] is the default and keeps
 * every existing call site's output byte-identical; Android's composition root explicitly selects
 * [ANDROID] once its screens consume the new presentation projections. This is a profile flag, not
 * a copy of the UI: the screens stay shared and only the pure display decisions differ.
 */
enum class UiPresentationProfile {
    LEGACY,
    ANDROID,
}

val LocalUiPresentationProfile = staticCompositionLocalOf { UiPresentationProfile.LEGACY }

/**
 * The pure per-profile display switches. Tests assert both profiles here instead of through a
 * Compose harness, so adding [ANDROID] cannot silently change the legacy surface.
 */
data class UiPresentationPolicy(
    val amountIncludesCurrencyCode: Boolean,
    val homeTitleUsesBusinessName: Boolean,
    val homeCategoryBadgesEnabled: Boolean,
) {
    companion object {
        val LEGACY = UiPresentationPolicy(false, false, false)
        val ANDROID = UiPresentationPolicy(true, true, true)
    }
}

fun uiPresentationPolicy(profile: UiPresentationProfile): UiPresentationPolicy =
    when (profile) {
        UiPresentationProfile.LEGACY -> UiPresentationPolicy.LEGACY
        UiPresentationProfile.ANDROID -> UiPresentationPolicy.ANDROID
    }

/**
 * The one amount renderer both profiles share. The default profile delegates to the existing
 * formatter, so a caller that does not name a profile keeps the current output exactly; the
 * Android profile appends the currency code from the frozen application projection.
 */
fun presentationAmountForProfile(
    minorUnits: Long,
    currency: CurrencyUnit,
    profile: UiPresentationProfile = UiPresentationProfile.LEGACY,
): String =
    when (profile) {
        UiPresentationProfile.LEGACY -> formatMinorUnits(minorUnits, currency.precision)
        UiPresentationProfile.ANDROID -> presentationAmountText(minorUnits, currency)
    }
