package com.unifiedledger.android

import com.unifiedledger.ui.UiPresentationProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * D-369 A: the Android composition root selects the Android presentation profile explicitly while
 * the shared default stays LEGACY. This is the only Android-side test the foundation batch adds.
 */
class AndroidUiPresentationProfileTest {
    @Test
    fun theAndroidRootSelectsTheAndroidProfile() {
        assertEquals(UiPresentationProfile.ANDROID, ANDROID_UI_PRESENTATION_PROFILE)
        assertNotEquals(UiPresentationProfile.LEGACY, ANDROID_UI_PRESENTATION_PROFILE)
    }
}
