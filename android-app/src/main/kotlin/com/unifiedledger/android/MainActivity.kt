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
        // P1-B + B3 (spec sections 2.2/4): the receipt is resolved by the PROCESS-scoped intake, so
        // an Activity recreation (rotation / config change) re-reading the SAME launch Intent reuses
        // the SAME deliveryId and its repeated delivery is REFUSED by the idempotent intake (the same
        // share is never admitted twice). HONEST LIMIT: the reducer-side pending slot lives in a
        // composition `remember`, so a recreation DOES lose the previously admitted held slot — this
        // is the spec section 8 item 7 device-gated residual, NOT a "slot survives config change"
        // guarantee. To keep the loss NON-SILENT, a refused repeat whose retained source is still
        // present is re-held under its EXISTING deliveryId (below), so the share surfaces the same
        // format prompt / typed re-share rather than vanishing.
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
            }
        if (request == null) {
            // P7-09 09.A fix round (B3; spec section 2.2 line 105 / section 4): a bare early return
            // here would silently drop an already-admitted share on a config recreation. When the
            // repeat read of the SAME cold-launch Intent is REFUSED (already consumed) AND its
            // retained source is still present, re-hold the request under its EXISTING deliveryId so
            // the share is non-silent (the format prompt / typed re-share surfaces again). The id is
            // NOT re-consumed (the receipt ledger is untouched) and the retained source is not
            // re-retained; zero formal effect. A genuine non-share launch or a share with no retained
            // source is a true no-op.
            if (coldLaunch) {
                val receipt = holder.inboundIntake.coldLaunchReceipt()
                if (receipt != null &&
                    holder.inboundIntake.isRefusedRepeat(receipt.deliveryId) &&
                    holder.shareImportRegistry.contains(receipt.deliveryId) &&
                    retainedShareUri(intent) != null
                ) {
                    StartupTrace.emit("entry.inboundIntake kind=reHeldRefusedRepeat")
                    holder.externalNavigationChannel.deliver(com.unifiedledger.ui.ExternalNavigationRequest.OpenImport(receipt.deliveryId))
                }
            }
            return
        }
        // P7-09 09.A closed loop (spec sections 2.3/4; D-225; invariant 2): the accepted share's
        // platform Uri is retained IN-PROCESS, keyed by the neutral deliveryId, so the later format
        // selection can read the SAME stream. It is NEVER persisted, NEVER written to disk/env and
        // takePersistableUriPermission is NEVER called (D-146); the composition root's
        // AndroidShareImportSourcePort takes (and thereby releases) it on read/abandon.
        if (request is com.unifiedledger.ui.ExternalNavigationRequest.OpenImport) {
            retainedShareUri(intent)?.let { uri -> holder.shareImportRegistry.retain(request.deliveryId, uri) }
        }
        StartupTrace.emit("entry.inboundIntake kind=${request::class.simpleName}")
        holder.externalNavigationChannel.deliver(request)
    }
}
