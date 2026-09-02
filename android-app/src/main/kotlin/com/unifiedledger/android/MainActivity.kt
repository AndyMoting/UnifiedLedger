package com.unifiedledger.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 enforces edge-to-edge on Android 15+ with no opt-out; enable it explicitly
        // before setContent per the official migration path so insets dispatch is set up (D-128).
        enableEdgeToEdge()
        setContent {
            app()
        }
    }
}
