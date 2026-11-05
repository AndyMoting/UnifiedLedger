package com.unifiedledger.android;

import android.accessibilityservice.AccessibilityService;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * P7-09 A-03 / A-VIS device probe (test-only): sets ACCESSIBILITY focus on the values the ordinary
 * TAB traversal cannot reach, so TalkBack's own utterance log proves whether they are readable.
 *
 * <p>Why a service and not UiAutomator: TalkBack speaks when the ACCESSIBILITY focus moves. A
 * non-focusable `TextView` (the month-card values, per-leg amounts) never receives that focus from
 * TAB traversal, so its screen-reader leg could not be measured at all. This fixture service holds
 * exactly one capability the test needs — `canRetrieveWindowContent` — and does two things once
 * connected: dumps a bounded summary of the visible tree (text / class / focusable / a11y-focused)
 * and asks for accessibility focus on every node whose text contains one of {@link #TARGETS}.
 *
 * <p>It holds no product data, lives only in the androidTest APK (never in the product build), and
 * logs under the {@code ULA11Y} tag so the host can read the transcript with plain `adb logcat`.
 *
 * <p>Pure Java on purpose: the manifest registers it for the TEST package, so the system starts
 * that package's own process and loads this class from the test APK alone — the classloading path
 * where the Kotlin runtime is not visible (see the sibling restore fixture's note).
 */
public final class A11yFocusProbeService extends AccessibilityService {

    /** The host-visible log tag; the probe's whole transcript carries it. */
    public static final String TAG = "ULA11Y";

    /** How long to wait after the service connects before probing (the app must be drawn). */
    private static final long PROBE_DELAY_MS = 6000L;

    /** Bounded tree dump: enough to see the regions under test, never a whole page. */
    private static final int MAX_NODES = 220;

    /** The texts whose accessibility focus is in question (substring match, case-insensitive). */
    private static final List<String> TARGETS =
            Arrays.asList("CNY", "净支出", "结余", "普通收入", "月度小结", "账户", "分析", "标签与商家");

    /** Paced passes: the app needs a few seconds to draw, and TalkBack needs time to SPEAK. */
    private static final int PROBE_PASSES = 3;

    /** Delay between two accessibility-focus moves: TalkBack de-duplicates and cancels otherwise. */
    private static final long FOCUS_PACING_MS = 4000L;

    /** Long wait before each pass so the previous pass's speech has finished. */
    private static final long PASS_GAP_MS = 6000L;

    /** How many target nodes one pass may focus (bounded so a pass cannot run away). */
    private static final int MAX_FOCUSED_PER_PASS = 1;

    /**
     * When set, only nodes whose text starts with this prefix are focused. Used to isolate ONE
     * long merged node (the month card's three-value sentence) so TalkBack can finish it before
     * the next focus move.
     */
    private static final String FOCUS_ONLY_PREFIX = "CNY：";

    /**
     * Host-driven overrides, so one installed fixture can measure every screen without a rebuild:
     * {@code adb shell settings put global ul_a11y_prefix <text>} restricts the pass to nodes whose
     * text starts with that prefix ("" = every target), and {@code ul_a11y_limit} caps how many
     * nodes one pass focuses. Both are read fresh at each pass.
     */
    private static final String SETTING_PREFIX = "ul_a11y_prefix";

    private static final String SETTING_LIMIT = "ul_a11y_limit";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "service connected");
        new Thread(
                        () -> {
                            for (int pass = 1; pass <= PROBE_PASSES; pass++) {
                                try {
                                    Thread.sleep(pass == 1 ? PROBE_DELAY_MS : PASS_GAP_MS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                probePaced("pass-" + pass);
                            }
                        },
                        "ul-a11y-probe")
                .start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // One probe per window-content change is enough; the host triggers the app screens itself.
        // The scheduled rounds do the measuring; events only mark the timeline.
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "interrupted");
    }

    /**
     * One paced pass: dump the tree once, then move the accessibility focus ONE node at a time with
     * [FOCUS_PACING_MS] between moves. TalkBack cancels a pending utterance when the focus moves
     * again, so a burst of focus moves produces almost no speech — the pacing is what makes the
     * utterance log usable evidence.
     */
    private void probePaced(String trigger) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.i(TAG, "pass trigger=" + trigger + " root=null");
            return;
        }
        List<AccessibilityNodeInfo> nodes = collect(root);
        Log.i(TAG, "pass trigger=" + trigger + " nodes=" + nodes.size());
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            if ((text == null || text.length() == 0) && (description == null || description.length() == 0)) {
                continue;
            }
            Log.i(
                    TAG,
                    "node text=\""
                            + trim(text)
                            + "\" desc=\""
                            + trim(description)
                            + "\" cls="
                            + node.getClassName()
                            + " focusable="
                            + node.isFocusable()
                            + " screenReaderFocusable="
                            + node.isScreenReaderFocusable());
        }
        String onlyPrefix = setting(SETTING_PREFIX, FOCUS_ONLY_PREFIX);
        int limit = Integer.parseInt(setting(SETTING_LIMIT, String.valueOf(MAX_FOCUSED_PER_PASS)));
        Log.i(TAG, "pass filter prefix=\"" + onlyPrefix + "\" limit=" + limit);
        int focused = 0;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence text = node.getText();
            if (text == null || !matches(text.toString())) {
                continue;
            }
            if (!onlyPrefix.isEmpty() && !text.toString().startsWith(onlyPrefix)) {
                continue;
            }
            if (focused >= limit) {
                break;
            }
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            focused++;
            Log.i(TAG, "focus# " + focused + " text=\"" + trim(text) + "\" accepted=" + ok);
            try {
                Thread.sleep(FOCUS_PACING_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.i(TAG, "pass summary trigger=" + trigger + " nodes=" + nodes.size() + " focused=" + focused);
    }

    private List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && nodes.size() < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            nodes.add(node);
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return nodes;
    }

    /** A Settings.Global value the host can change without reinstalling this fixture. */
    private String setting(String key, String fallback) {
        try {
            String value = Settings.Global.getString(getContentResolver(), key);
            return value == null ? fallback : value;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private static boolean matches(String text) {
        for (String target : TARGETS) {
            if (text.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(CharSequence text) {
        if (text == null) {
            return "";
        }
        String value = text.toString().replace('\n', ' ');
        return value.length() > 60 ? value.substring(0, 60) : value;
    }
}
