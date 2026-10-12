package com.unifiedledger.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unifiedledger.ui.P503Theme

class MainActivity : ComponentActivity() {
    /**
     * P7-09 09.0 (spec section 4): the locally minted, opaque inbound identity ledger. One
     * instance per Activity; a NEW receipt (cold start or `onNewIntent`) mints a NEW deliveryId,
     * so an identical URI shared again is a new import (D-146), while a repeated internal delivery
     * of the SAME deliveryId is consumed at most once.
     */
    private val inboundIntake = InboundNavigationIntake { java.util.UUID.randomUUID().toString() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // D-203 startup trace: the earliest application-code point of the cold-start path.
        StartupTrace.emit("entry.activityCreate")
        super.onCreate(savedInstanceState)
        // targetSdk 37 enforces edge-to-edge on Android 15+ with no opt-out; enable it explicitly
        // before setContent per the official migration path so insets dispatch is set up (D-128).
        // D-134 D2-D2: the icon appearance contract is explicit — auto follows the system dark
        // mode (dark icons on the now-light window background in light mode, light icons in dark
        // mode); transparent scrims are inert at minSdk 34 (> API 29).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // P7-09 09.A (spec sections 2.1/2.2): the cold-start share. The Intent that launched this
        // Activity is validated into a neutral request here (before the composition subscribes);
        // the channel buffers it and the host routes it once Ready. A non-share launch is a no-op.
        handleInboundIntent(intent)
        setContent {
            // D-134 D2-D1: pre-Ready screens render outside P503App, so the shared dual theme
            // wraps the whole root; nesting with P503App's inner wrapper is harmless.
            P503Theme {
                app()
            }
        }
    }

    /**
     * P7-09 09.0 (spec section 2.2): a hot share/shortcut receipt. `onNewIntent` MUST call
     * `setIntent()` so `getIntent()` returns the NEW Intent — otherwise a later reader (a
     * re-composition, an Activity recreation restoring state) would still see the original launch
     * Intent and could re-consume or misroute it (official `Activity` reference).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The official requirement: without this the Activity keeps reporting the ORIGINAL Intent.
        setIntent(intent)
        StartupTrace.emit("entry.onNewIntent")
        handleInboundIntent(intent)
    }

    /**
     * Validates one received Intent into a neutral request and delivers it to the process-scoped
     * channel. Zero formal effect on any rejection (spec section 2.3): the validator only reads
     * the Intent's own extras and never opens a stream, so a rejected share leaves no trace.
     *
     * The deliveryId is minted per receipt and recorded in [inboundIntake]; a repeated internal
     * delivery of the same deliveryId is not re-delivered (idempotent consumption, spec section 4).
     */
    private fun handleInboundIntent(intent: Intent?) {
        if (intent == null) return
        val snapshot =
            snapshotShareIntent(intent) { uri ->
                // The read grant check: the platform reports readability of the granted URI. A
                // revoked/absent grant resolves false and the share is typed-rejected (spec 2.3).
                runCatching { contentResolver.openInputStream(uri)?.close() }.isSuccess
            }
        val request = inboundIntake.requestFor(snapshot) ?: return
        StartupTrace.emit("entry.inboundIntake kind=${request::class.simpleName}")
        ledgerProcessHolderFor(this)?.externalNavigationChannel?.deliver(request)
    }
}
