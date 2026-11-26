package com.unifiedledger.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
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
 * exactly one capability the test needs — `canRetrieveWindowContent` — and does three things once
 * connected: dumps a bounded summary of the visible tree (text / class / focusable / a11y-focused),
 * asks for accessibility focus on every node whose text contains one of {@link #TARGETS}, and
 * watches a host-commanded channel that focuses one named target inside an id-labelled speech
 * window (P8-06 V-12).
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
    private static final int MAX_NODES = 400;

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

    /**
     * Host-driven CLICK target: {@code adb shell settings put global ul_a11y_click <prefix>} makes
     * the pass click the first node whose text starts with that prefix instead of focusing one.
     * Needed because TalkBack changes touch semantics — a plain tap only selects an element, so the
     * host cannot drive the app with {@code input tap} while the screen reader is on.
     */
    private static final String SETTING_CLICK = "ul_a11y_click";

    /**
     * Host-commanded focus: {@code adb shell settings put global ul_a11y_command
     * "focus,<id>,<base64 of target>"}. The host needs the SAME target's speech window per screen
     * (P8-06 V-12), so the command carries a unique id, the probe logs the command's own base64
     * target as {@code needle64=...}, the found node as {@code target id=... found=...} with its
     * full text/description base64-encoded (so a trimmed preview cannot substitute a node), and
     * {@code speech-window-begin/end id=...} around the utterance, and the host can extract exactly
     * that window from logcat and re-decode the target it actually focused. The target is base64 so
     * a full composed sentence (spaces, CJK, punctuation) survives the settings transport
     * byte-for-byte; commas are shell-safe and not part of the base64 alphabet.
     */
    private static final String SETTING_COMMAND = "ul_a11y_command";

    private static final long COMMAND_POLL_MS = 300L;

    /** TalkBack needs the whole utterance to finish before the window closes. */
    private static final long COMMAND_SPEECH_MS = 8000L;

    /**
     * The single command watcher for the bound service instance. A host screen disables the service
     * between legs and enables it again for the next one; the process usually survives, so an old
     * watcher must be stopped before a second one starts — two watchers would run the same new
     * command twice and the second focus move would cancel the first utterance.
     */
    private static volatile Thread commandWatcher;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Replace any watcher left by an earlier bind BEFORE announcing the connection: the host
        // polls this log line and writes its first command right after it, and a stale watcher
        // would otherwise read that command on an unbound instance (its root is null).
        stopCommandWatcher();
        Thread started = new Thread(this::watchCommands, "ul-a11y-command");
        commandWatcher = started;
        started.start();
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
        Log.i(TAG, "service connected");
    }

    @Override
    public void onDestroy() {
        stopCommandWatcher();
        super.onDestroy();
    }

    /** Stops the bound instance's command watcher so a new bind can start exactly one. */
    private static void stopCommandWatcher() {
        Thread watcher = commandWatcher;
        commandWatcher = null;
        if (watcher == null) {
            return;
        }
        watcher.interrupt();
        try {
            watcher.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
        String clickTarget = setting(SETTING_CLICK, "");
        if (!clickTarget.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                CharSequence text = node.getText();
                if (text == null || !text.toString().startsWith(clickTarget)) {
                    continue;
                }
                // The text node is usually a label inside a clickable container: click the nearest
                // clickable ancestor instead of the label itself (a label answers false).
                AccessibilityNodeInfo clickable = node;
                for (int up = 0; up < 6 && clickable != null && !clickable.isClickable(); up++) {
                    clickable = clickable.getParent();
                }
                boolean clicked =
                        clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) {
                    // Compose buttons often answer no ACTION_CLICK to a service; a dispatched tap at
                    // the node's own bounds is the reliable activation under TalkBack.
                    clicked = tapNode(node);
                }
                Log.i(
                        TAG,
                        "click text=\"" + trim(text) + "\" target=" + (clickable == null ? "null" : clickable.getClassName())
                                + " accepted=" + clicked);
                return;
            }
            Log.i(TAG, "click target=\"" + clickTarget + "\" not-found");
            return;
        }
        String onlyPrefix = setting(SETTING_PREFIX, FOCUS_ONLY_PREFIX);
        int limit = Integer.parseInt(setting(SETTING_LIMIT, String.valueOf(MAX_FOCUSED_PER_PASS)));
        Log.i(TAG, "pass filter prefix=\"" + onlyPrefix + "\" limit=" + limit);
        int focused = 0;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence text = node.getText();
            if (text == null) {
                continue;
            }
            // The host's prefix REPLACES the built-in target list: a screen under test names its
            // own node ("EXPENSE", "月份：", …) and must not also satisfy the default list.
            boolean selected =
                    onlyPrefix.isEmpty() ? matches(text.toString()) : text.toString().startsWith(onlyPrefix);
            if (!selected) {
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

    /**
     * The host-command channel. Only a NEW value runs, and the value embeds its own id, so the host
     * can issue the same target twice and every run has one identifiable speech window.
     */
    private void watchCommands() {
        String last = "";
        while (!Thread.currentThread().isInterrupted()) {
            String command = setting(SETTING_COMMAND, "");
            if (!command.isEmpty() && !command.equals(last)) {
                last = command;
                runCommand(command);
            }
            try {
                Thread.sleep(COMMAND_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runCommand(String command) {
        String[] parts = command.split(",", 3);
        if (parts.length != 3 || !"focus".equals(parts[0])) {
            Log.i(TAG, "command unsupported");
            return;
        }
        String id = parts[1];
        String needle;
        try {
            needle = new String(Base64.decode(parts[2], Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            Log.i(TAG, "command id=" + id + " target=unreadable");
            return;
        }
        Log.i(
                TAG,
                "command action=focus id="
                        + id
                        + " needle64="
                        + parts[2]
                        + " target=\""
                        + trim(needle)
                        + "\"");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.i(TAG, "target id=" + id + " found=false root=null");
            return;
        }
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo node : collect(root)) {
            if (contains(node.getText(), needle) || contains(node.getContentDescription(), needle)) {
                candidates.add(node);
            }
        }
        AccessibilityNodeInfo target = null;
        Rect chosenBounds = new Rect();
        int visible = 0;
        StringBuilder seen = new StringBuilder();
        for (AccessibilityNodeInfo candidate : candidates) {
            Rect bounds = new Rect();
            candidate.getBoundsInScreen(bounds);
            boolean shown = visibleInWindow(candidate, window);
            if (shown) {
                visible++;
                if (target == null) {
                    target = candidate;
                    chosenBounds = bounds;
                }
            }
            if (seen.length() > 0) {
                seen.append(" | ");
            }
            seen.append(bounds.toShortString()).append(" visible=").append(shown);
        }
        // The needle is ordinary copy, so more than one node can carry it — the empty-ledger line is
        // the same sentence on two destinations — and only the node the user is looking at answers
        // ACTION_ACCESSIBILITY_FOCUS. Choosing the first match in traversal order is what refused a
        // focus on a node that was not on the screen under test; the visible candidate is chosen
        // instead, and every candidate's bounds/visibility is logged so the next round can read
        // which node answered.
        Log.i(
                TAG,
                "candidates id="
                        + id
                        + " candidates="
                        + candidates.size()
                        + " visible="
                        + visible
                        + " chosen="
                        + (target == null ? "none" : "visible")
                        + " window="
                        + window.toShortString()
                        + " seen="
                        + seen);
        if (target == null) {
            // Nothing focusable is on this screen. Report the refusal the host already knows how to
            // read rather than focusing an off-screen twin: a twin's answer describes a different
            // screen than the one under test.
            Log.i(
                    TAG,
                    "target id="
                            + id
                            + " found=false candidates="
                            + candidates.size()
                            + " visible=0");
            return;
        }
        Log.i(
                TAG,
                "target id="
                        + id
                        + " found=true visible=true bounds="
                        + chosenBounds.toShortString()
                        + " text=\""
                        + trim(target.getText())
                        + "\" desc=\""
                        + trim(target.getContentDescription())
                        + "\" text64=\""
                        + encode(target.getText())
                        + "\" desc64=\""
                        + encode(target.getContentDescription())
                        + "\"");
        Log.i(TAG, "speech-window-begin id=" + id);
        boolean accepted = false;
        try {
            accepted = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        } catch (RuntimeException failure) {
            Log.i(TAG, "focus-error id=" + id + " error=" + failure);
        }
        Log.i(TAG, "focus-result id=" + id + " found=true accepted=" + accepted);
        try {
            Thread.sleep(COMMAND_SPEECH_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "speech-window-end id=" + id);
    }

    private static boolean contains(CharSequence text, String needle) {
        return text != null && text.toString().contains(needle);
    }

    /**
     * Whether [node] sits on the screen the user is actually looking at, which is what
     * `ACTION_ACCESSIBILITY_FOCUS` requires: the platform's own visible-to-user flag
     * (`isVisibleToUser`, API 16 `AccessibilityNodeInfo.isVisibleToUser()`) AND screen bounds that
     * overlap the active window with a non-zero area (`getBoundsInScreen`, API 14;
     * `Rect#intersect` is the area test, and it answers false for an empty rect). Both are read
     * because either one alone can hold for a node that is still off the current screen: a sibling
     * destination laid out beside it reports a non-empty rect outside the window, and a clipped or
     * covered node keeps the flag.
     */
    private static boolean visibleInWindow(AccessibilityNodeInfo node, Rect window) {
        if (!node.isVisibleToUser()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (window.isEmpty()) {
            // A root that reports no window rect cannot be a yardstick; the platform flag alone
            // decides then, so such a window does not discard every candidate.
            return true;
        }
        return bounds.intersect(window);
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

    /** Dispatches a single tap at [node]'s centre; the fallback when ACTION_CLICK is refused. */
    private boolean tapNode(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        // A DOUBLE tap: with TalkBack enabled a single injected tap only explores/selects, and the
        // screen reader's activation gesture is the double tap. Two strokes at the same point, the
        // second starting after the first ends.
        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, 45))
                        .addStroke(new GestureDescription.StrokeDescription(path, 130, 45))
                        .build();
        return dispatchGesture(gesture, null, null);
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

    /**
     * The node value byte-for-byte, so the trimmed preview can never hide which node matched.
     */
    private static String encode(CharSequence text) {
        String value = text == null ? "" : text.toString();
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String trim(CharSequence text) {
        if (text == null) {
            return "";
        }
        String value = text.toString().replace('\n', ' ');
        return value.length() > 60 ? value.substring(0, 60) : value;
    }
}
