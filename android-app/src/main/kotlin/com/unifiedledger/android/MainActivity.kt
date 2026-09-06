package com.unifiedledger.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unifiedledger.ui.P503Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 enforces edge-to-edge on Android 15+ with no opt-out; enable it explicitly
        // before setContent per the official migration path so insets dispatch is set up (D-128).
        // D-134 D2-D2: the icon appearance contract is explicit — auto follows the system dark
        // mode (dark icons on the now-light window background in light mode, light icons in dark
        // mode); transparent scrims are inert at minSdk 34 (> API 29).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            // D-134 D2-D1: pre-Ready screens render outside P503App, so the shared dual theme
            // wraps the whole root; nesting with P503App's inner wrapper is harmless.
            P503Theme {
                app()
            }
        }
    }
}
