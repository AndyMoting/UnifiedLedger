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
        //
        // P1-B fix: the receipt is resolved by the PROCESS-scoped intake, so an Activity recreation
        // (rotation / config change) re-reading the SAME launch Intent reuses the SAME deliveryId
        // and is refused — the same share is never admitted twice and its held slot is not lost.
        handleInboundIntent(intent, coldLaunch = true)
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
        // A hot receipt is a NEW user share (spec section 4), so it mints a FRESH deliveryId; the
        // process-scoped intake keeps the cold-launch id untouched.
        handleInboundIntent(intent, coldLaunch = false)
    }

    /**
     * Validates one received Intent into a neutral request and delivers it to the process-scoped
     * channel. Zero formal effect on any rejection (spec section 2.3): the validator only reads the
     * Intent's own extras and never opens a stream or queries the provider, so a rejected share
     * leaves no trace and nothing blocks the main thread (P2-5 fix: the readability probe is a
     * non-opening permission check, never an `openInputStream`).
     *
     * [coldLaunch] selects the receipt identity: a cold launch reuses ONE stable deliveryId for the
     * process launch (P1-B: an Activity recreation must not re-consume), while a hot `onNewIntent`
     * receipt mints a fresh id.
     */
    private fun handleInboundIntent(
        intent: Intent?,
        coldLaunch: Boolean,
    ) {
        if (intent == null) return
        val snapshot =
            snapshotShareIntent(intent) { uri ->
                // The read grant check: a NON-opening permission probe. It must never open the
                // stream here — the bounded read happens later, off the UI thread, under the user's
                // explicit format choice (spec sections 2.3/2.4).
                shareUriReadGranted(
                    uri = uri,
                    checkPermission = { candidate ->
                        checkUriPermission(candidate, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    permissionGranted = android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
            }
        val holder = ledgerProcessHolderFor(this) ?: return
        val request =
            if (coldLaunch) {
                holder.inboundIntake.coldLaunchRequest(snapshot)
            } else {
                holder.inboundIntake.hotReceiptRequest(snapshot)
            } ?: return
        StartupTrace.emit("entry.inboundIntake kind=${request::class.simpleName}")
        holder.externalNavigationChannel.deliver(request)
    }
}
