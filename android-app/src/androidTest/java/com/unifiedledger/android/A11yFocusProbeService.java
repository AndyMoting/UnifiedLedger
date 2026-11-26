package com.unifiedledger.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
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
 * window (P8-06 V-12), and judges that focus by the action's own answer plus, when the action
 * refuses, by the platform's `TYPE_VIEW_ACCESSIBILITY_FOCUSED` event for the same node (P8-07 Q3
 * r4: candidate 4064dbfc logged `focus-result id=3 found=true accepted=false` and TalkBack still
 * spoke that sentence), plus, when the action AND the event both stay silent, by the platform's
 * current accessibility focus read back after the window (P8-07 r7 D0). The focus that is already
 * on the target is cleared before the request, because Compose refuses to re-focus it and the host
 * only counts a fragment spoken inside this command's own window.
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
     * How many parents a refused focus may walk (P8-07 Q3). Compose merges a bare `Text` into its
     * nearest container within a couple of levels, so a deeper walk reaches outside the region the
     * user is on; the bound also keeps a pathological tree from turning one command into a scan.
     */
    private static final int MAX_FOCUS_ANCESTOR_DEPTH = 4;

    /**
     * The event subtype that proves a focus move reached the platform (P8-07 Q3 r4).
     *
     * <p>`ACTION_ACCESSIBILITY_FOCUS` answers for the ACTION, not for the focus: candidate 4064dbfc
     * (run 45783028573, `profile=empty`, analysis screen) logged a visible, correctly chosen node
     * whose own four ancestors were all unfocusable, `focus-result id=3 found=true accepted=false`,
     * and then `Speaking fragment text="账本为空，还没有任何交易。" …
     * event=type:EVENT_TYPE_ACCESSIBILITY_SUBTYPE_TYPE_VIEW_ACCESSIBILITY_FOCUSED`. The event is the
     * platform's own record that the focus arrived, which is what the host's `accepted` is asked to
     * mean.
     */
    private static final String FOCUS_EVENT_SUBTYPE = "TYPE_VIEW_ACCESSIBILITY_FOCUSED";

    /**
     * How long a REFUSED focus may still be proven by that event. Bounded on purpose: an event that
     * never arrives has to stay a refusal, and the command's own speech window
     * ([COMMAND_SPEECH_MS]) has to stay long enough for the utterance that follows it. 2500 ms
     * covers event dispatch plus one TalkBack cycle.
     */
    private static final long FOCUS_EVENT_WAIT_MS = 2500L;

    /** Poll interval while matching an event inside [FOCUS_EVENT_WAIT_MS]. */
    private static final long FOCUS_EVENT_POLL_MS = 100L;

    /** How many recent focus events stay readable for matching; a burst cannot grow unbounded. */
    private static final int MAX_FOCUS_EVENTS = 32;

    /** The reason value of a focus read that found a node: the node itself is the whole answer. */
    private static final String FOCUS_READ_NONE = "none";

    /**
     * The read's reason when this service could not resolve an active window's root at all (no
     * active window, or a tree that refuses `findFocus`): nothing is known about the focus then.
     */
    private static final String FOCUS_READ_ROOT_NULL = "root-null";

    /** The read's reason when the active window's root reports no accessibility focus. */
    private static final String FOCUS_READ_FOCUS_NONE = "focus-none";

    /**
     * The focus events observed since this instance connected, newest last, each stamped with the
     * count it was recorded under. A command reads the count BEFORE its action and accepts only an
     * event with a HIGHER count, so an event that had already arrived cannot prove the action. The
     * queue and the count are guarded by [focusEventLock] because the platform delivers events on
     * the service's main thread while a command runs on the watcher thread.
     */
    private final Object focusEventLock = new Object();

    private final Deque<FocusEvent> focusEvents = new ArrayDeque<>();

    private long focusEventSequence;

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
        // The mask this connection actually got, not the XML's intent: a stale or reparsed
        // AccessibilityServiceInfo shows up here as a different hex value, so a `seen=0` window has
        // an immediate cause instead of blaming the platform. The focus event this probe accepts is
        // AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED (bit 15, 0x8000); the config's two
        // retained window types are TYPE_WINDOW_STATE_CHANGED (bit 5, 0x20) and
        // TYPE_WINDOW_CONTENT_CHANGED (bit 11, 0x800), so the configured mask prints as 0x8820, and
        // the one configured flag is FLAG_INCLUDE_NOT_IMPORTANT_VIEWS (bit 1, 0x2). A focus proof
        // needs 0x8000 in that value: without the bit onAccessibilityEvent is never called for it
        // and every refused command decays to `source=none` after the full wait.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            Log.i(TAG, "service-info eventTypes=none flags=none");
        } else {
            Log.i(
                    TAG,
                    "service-info eventTypes=0x"
                            + Integer.toHexString(info.eventTypes)
                            + " flags=0x"
                            + Integer.toHexString(info.flags));
        }
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
        // The scheduled rounds do the paced passes; the events themselves are the second half of a
        // command's evidence, so every focus event is kept until a command stops matching it.
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            return;
        }
        recordFocusEvent(event);
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "interrupted");
    }

    /** Keeps one focus event: its count, its arrival time, what it carries and the node's rect. */
    private void recordFocusEvent(AccessibilityEvent event) {
        long receivedAt = SystemClock.elapsedRealtime();
        List<String> parts = textParts(event.getText());
        String text = joinText(parts);
        CharSequence carried = event.getContentDescription();
        String description = carried == null ? "" : carried.toString();
        Rect bounds = new Rect();
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                source.getBoundsInScreen(bounds);
            }
        } catch (RuntimeException unavailable) {
            // A source this service may not read leaves the rect empty; the text still decides.
        }
        synchronized (focusEventLock) {
            focusEventSequence++;
            focusEvents.addLast(
                    new FocusEvent(
                            focusEventSequence, receivedAt, text, parts, description, bounds));
            while (focusEvents.size() > MAX_FOCUS_EVENTS) {
                focusEvents.removeFirst();
            }
        }
    }

    /** The count of focus events received so far — the boundary a command's action is measured at. */
    private long focusEventSequence() {
        synchronized (focusEventLock) {
            return focusEventSequence;
        }
    }

    /**
     * Waits [FOCUS_EVENT_WAIT_MS] for a focus event that provably belongs to THIS command's target
     * and returns the basis it matched on (`text` / `bounds`), or `""` when none arrived in time.
     *
     * <p>Four conditions decide, and each is read back from a named key of the raw window rather
     * than trusted from here: the event was recorded under a count HIGHER than the one read before
     * the action (that gate is the predecessor of every field below, so a matched line is a
     * post-action event by construction; the count itself is not a log field);
     * `0 <= afterActionMs <= waitMs` on the matched line places its arrival after the action and
     * inside the window, the same shape the timeout line has with its own capped wait; `matched=`
     * names the basis, and BOTH bases are re-derivable — `eventText64=` decodes to the event's own
     * text, which must contain the `needle64` of this command's `command` line for `matched=text`,
     * while `eventBounds=` must intersect the `focusNodeBounds=` of its `focus-target` line for
     * `matched=bounds` (`eventBounds=[]` — an unreadable or empty source rect — intersects nothing,
     * so it can never sit on a bounds match); and that `focusNodeBounds=` is the rect of
     * [focusNode], the node this command's action was actually sent to, which is an ancestor when
     * the chosen `Text` is unfocusable, not the pre-action target.
     *
     * <p>The event's own rect and text are printed for exactly that last check: `matched=text` /
     * `matched=bounds` named the basis but not the event, so an ancestor's, a container's or an
     * overlay's event that merely overlapped the acted node read the same as the acted node's own
     * (P8-07 Q3 r6). `eventBounds=` is always present and `eventText64=` is omitted when the event
     * carried no text, so a reviewer can tell which node's event was accepted. A timeout logs
     * `matched=none` with how many post-action events were seen and returns `""` — the caller keeps
     * `accepted=false` rather than inventing a focus.
     */
    private String awaitFocusEvent(
            String id,
            String needle,
            AccessibilityNodeInfo focusNode,
            long sequenceBeforeAction,
            long actionAt) {
        Rect focusBounds = new Rect();
        focusNode.getBoundsInScreen(focusBounds);
        long deadline = actionAt + FOCUS_EVENT_WAIT_MS;
        String head = "focus-event id=" + id + " subtype=" + FOCUS_EVENT_SUBTYPE;
        while (true) {
            List<FocusEvent> observed = new ArrayList<>();
            synchronized (focusEventLock) {
                observed.addAll(focusEvents);
            }
            int seen = 0;
            for (FocusEvent candidate : observed) {
                if (candidate.sequence <= sequenceBeforeAction || candidate.receivedAt < actionAt) {
                    continue;
                }
                // Counted before it is judged, so the timeout line reports what the window held: an
                // event stamped after the deadline is outside this window whatever the wake-up
                // order was, and it stays a `seen=` number instead of a pass.
                seen++;
                if (candidate.receivedAt > deadline) {
                    continue;
                }
                String basis = matchBasis(candidate, needle, focusBounds);
                if (basis.isEmpty()) {
                    continue;
                }
                Log.i(
                        TAG,
                        head
                                + " matched="
                                + basis
                                + " afterActionMs="
                                + (candidate.receivedAt - actionAt)
                                + " waitMs="
                                + FOCUS_EVENT_WAIT_MS
                                + " eventBounds="
                                + rectToken(candidate.bounds)
                                + (candidate.text.isEmpty()
                                        ? ""
                                        : " eventText64=" + encode(candidate.text)));
                return basis;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= deadline) {
                // A scheduled poll can wake a few milliseconds past the deadline; the wait this line
                // reports is capped at the window it names, so `afterActionMs <= waitMs` holds on
                // every line of the transcript.
                long waited = Math.min(now - actionAt, FOCUS_EVENT_WAIT_MS);
                Log.i(
                        TAG,
                        head
                                + " matched=none afterActionMs="
                                + waited
                                + " waitMs="
                                + FOCUS_EVENT_WAIT_MS
                                + " seen="
                                + seen);
                return "";
            }
            try {
                Thread.sleep(Math.min(FOCUS_EVENT_POLL_MS, deadline - now));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }

    /**
     * Why an observed focus event belongs to this command's target, or `""` when it does not.
     *
     * <p>Text first: the event's own text or content description has to CONTAIN the command's
     * needle — the sentence the host asked for — which is also what TalkBack reads back. Bounds
     * second: an event whose text the platform left empty still carries its node's rect, so a rect
     * overlapping [focusBounds] is that node's move. [focusBounds] is the rect of the node this
     * command's action was actually sent to (an ancestor may receive the action, see
     * [focusTarget]), never the pre-action target's rect: a node that merely overlaps where the
     * needle was found is a different node and its focus proves nothing about this command. An
     * empty needle never matches by text (`contains("")` is always true and would accept the first
     * unrelated event of the screen) and an empty rect never matches by bounds, so neither
     * degenerate case can produce a pass.
     */
    private static String matchBasis(FocusEvent event, String needle, Rect focusBounds) {
        if (!needle.isEmpty()
                && (carriedText(event, needle) || event.description.contains(needle))) {
            return "text";
        }
        if (!focusBounds.isEmpty() && Rect.intersects(event.bounds, focusBounds)) {
            return "bounds";
        }
        return "";
    }

    /**
     * Whether the event's own text carries [needle]: the space-joined form first, then every part on
     * its own and the no-separator concatenation of all parts.
     *
     * <p>The platform may split one node's text across several `CharSequence` parts and TalkBack
     * speaks them as one run, so a needle spanning two parts is a real utterance; matching only the
     * space-joined form turned exactly that into a false refusal (P8-07 Q3 r5). The empty needle is
     * refused by the caller, so nothing here has to guard `contains("")`.
     */
    private static boolean carriedText(FocusEvent event, String needle) {
        if (event.text.contains(needle)) {
            return true;
        }
        StringBuilder tight = new StringBuilder();
        for (String part : event.textParts) {
            if (part.contains(needle)) {
                return true;
            }
            tight.append(part);
        }
        return tight.toString().contains(needle);
    }

    /** The event's own text, one entry per platform part, nulls dropped. */
    private static List<String> textParts(List<CharSequence> values) {
        List<String> parts = new ArrayList<>();
        if (values == null) {
            return parts;
        }
        for (CharSequence value : values) {
            if (value != null) {
                parts.add(value.toString());
            }
        }
        return parts;
    }

    /** The human-readable form of [parts]: one line, a space between the parts. */
    private static String joinText(List<String> parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(part);
        }
        return joined.toString();
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
                        + "\" actions=0x"
                        + Integer.toHexString(target.getActions())
                        + " clickable="
                        + target.isClickable()
                        + " scrollable="
                        + target.isScrollable());
        // P8-07 r7 D0: the platform's CURRENT accessibility focus is read before anything is sent,
        // and a target that already holds it is cleared first. Compose answers
        // ACTION_ACCESSIBILITY_FOCUS with false when the virtual view is ALREADY accessibility
        // focused (AndroidComposeViewAccessibilityDelegateCompat#requestAccessibilityFocus skips
        // the event and returns false on that branch), and an action that changed nothing posts no
        // TYPE_VIEW_ACCESSIBILITY_FOCUSED at all: that is the analysis leg's `returned=false` with
        // `seen=0` and no fragment inside the window. The host counts a fragment only strictly
        // between this command's begin and end, so the utterance has to come from a move this
        // command made. And a fragment inside that window does not even prove that much: speech
        // started before begin can be logged after it (the wave-5 analysis window carries a
        // pre-command utterance between begin and end). The clear is therefore not a trick to make a
        // fragment appear — it is what makes the post-action read a record of a MOVE: with the
        // standing focus taken away, a focus that is back afterwards is one this command asked for,
        // and a standing focus the clear could not remove proves nothing and accepts nothing. That
        // is why the clear belongs before the request, not after a refusal.
        FocusRead before = currentAccessibilityFocus();
        AccessibilityNodeInfo current = before.node;
        Log.i(
                TAG,
                "focus-before id="
                        + id
                        + " found="
                        + (current != null)
                        + (current == null
                                ? " reason=" + before.reason
                                : " bounds="
                                        + focusNodeBounds(current)
                                        + " text64=\""
                                        + encode(current.getText())
                                        + "\" desc64=\""
                                        + encode(current.getContentDescription())
                                        + "\""));
        // P8-07 r8 QR7-1: what the platform held when this command STARTED, and whether this
        // command's own clear took it away, are facts the verdict at the end of the method needs —
        // so they live as long as the method instead of inside the clear's `if` block. A target that
        // already held the focus and kept it never moved for this command: reading the same node
        // back afterwards is the same focus, not the platform's record of a move.
        boolean startedOnTarget = isCommandTarget(current, needle, chosenBounds);
        boolean cleared = false;
        if (startedOnTarget) {
            // A failed clear is not fatal: the request below still goes out, and the verdict is
            // re-derived from the action answer, the focus event and the post-action read, never
            // from this line.
            try {
                cleared = current.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            } catch (RuntimeException failure) {
                Log.i(TAG, "focus-error id=" + id + " error=" + failure);
            }
            Log.i(TAG, "focus-clear id=" + id + " answered=" + cleared);
        }
        // The admission fact for [source] = "focus-read", NOT an observation: this command moved the
        // focus only when the platform ANSWERED the first read with a node the target was not, or
        // when the clear above took that focus away — then the request below starts from "not
        // focused" and a read-back can only be the platform's record of a move this command asked
        // for. A read this service could not take is not the fact "the target was not focused":
        // [startedOnTarget] is false both when the platform answered "not this node" and when it
        // answered nothing at all, so the move test needs the read itself to have a node. Without
        // one, a target that never stopped holding the focus still reads back as the target, and
        // accepting that read would report a move this command never made.
        boolean readAnswered = current != null;
        boolean moved = (readAnswered && !startedOnTarget) || cleared;
        AccessibilityNodeInfo focus = focusTarget(target, id);
        Log.i(TAG, "speech-window-begin id=" + id);
        // The action's answer is measured, never assumed, and the count/time below are the boundary
        // that separates an event this action caused from one that was already on the screen.
        long sequenceBeforeAction = focusEventSequence();
        long actionAt = SystemClock.elapsedRealtime();
        boolean answered = false;
        try {
            answered = focus.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        } catch (RuntimeException failure) {
            Log.i(TAG, "focus-error id=" + id + " error=" + failure);
        }
        String source = "none";
        if (answered) {
            source = "action";
        } else {
            // A refused action is not yet a refused focus: the platform posts its own focus event
            // for this move, and only that event (belonging to this command's node, after this
            // action, inside the window) may upgrade the verdict.
            Log.i(TAG, "focus-action id=" + id + " returned=false");
            if (!awaitFocusEvent(id, needle, focus, sequenceBeforeAction, actionAt).isEmpty()) {
                source = "accessibility-event";
            } else {
                // Third and last source (P8-07 r7 D0, admitted only when the focus moved): the action
                // answered false AND no matching event arrived, so the platform's current focus is
                // read once more. A focus that now IS this command's target is the platform's own
                // record that the move landed — but only a focus this command moved counts, so the
                // read is accepted with [moved] and logged with it, and a standing focus that was
                // never cleared stays [source] = "none" however this read answers.
                FocusRead reread = currentAccessibilityFocus();
                AccessibilityNodeInfo readBack = reread.node;
                Log.i(
                        TAG,
                        "focus-read id="
                                + id
                                + " found="
                                + (readBack != null)
                                + (readBack == null
                                        ? " reason=" + reread.reason
                                        : " bounds="
                                                + focusNodeBounds(readBack)
                                                + " text64=\""
                                                + encode(readBack.getText())
                                                + "\" desc64=\""
                                                + encode(readBack.getContentDescription())
                                                + "\"")
                                + " before="
                                + before.reason
                                + " startedOnTarget="
                                + startedOnTarget
                                + " cleared="
                                + cleared
                                + " moved="
                                + moved);
                if (isCommandTarget(readBack, needle, chosenBounds) && moved) {
                    source = "focus-read";
                }
            }
        }
        boolean accepted = !"none".equals(source);
        Log.i(TAG, "focus-result id=" + id + " found=true accepted=" + accepted + " source=" + source);
        try {
            Thread.sleep(COMMAND_SPEECH_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "speech-window-end id=" + id);
    }

    /**
     * The node that receives this command's `ACTION_ACCESSIBILITY_FOCUS` (P8-07 Q3).
     *
     * <p>A Compose `Text` is visible without being focusable, so the action answers false on it
     * even though TalkBack's touch exploration lands on the nearest focusable ancestor and speaks
     * that ancestor's merged text. Candidate 482f1568 recorded exactly that on the analysis leg:
     * `target id=4 found=true text="账本为空，还没有任何交易。"` followed by `focus-result id=4
     * found=true accepted=false`. A non-focusable target therefore hands the action to its nearest
     * focusable ancestor within [MAX_FOCUS_ANCESTOR_DEPTH]. When no ancestor qualifies the visible
     * target itself stays the receiver: its own answer is a real measured refusal, and no action
     * goes to a node outside the screen under test.
     *
     * <p>The ancestor's text/description are logged losslessly (`ancestor-text64`/`ancestor-desc64`)
     * because the host still judges this command by `accepted` and by the speech fragment: those
     * fields are what let the next round read WHICH sentence the ancestor spoke. The target needle
     * itself is never rewritten, so a wide merged ancestor cannot pass a narrower requirement.
     *
     * <p>Every outcome names the rect of the node this command's action actually reaches
     * (`focusNodeBounds=`), because that rect is the one a focus event must overlap to count as this
     * command's move (P8-07 Q3 r5). The chosen target's rect is NOT it when the action goes to an
     * ancestor, and a node that merely overlaps where the needle was found is a different node.
     */
    private AccessibilityNodeInfo focusTarget(AccessibilityNodeInfo target, String id) {
        if (target.isFocusable()) {
            Log.i(
                    TAG,
                    "focus-target id="
                            + id
                            + " target=node focusable=true focusNodeBounds="
                            + focusNodeBounds(target));
            return target;
        }
        AccessibilityNodeInfo ancestor = target.getParent();
        for (int depth = 1; depth <= MAX_FOCUS_ANCESTOR_DEPTH && ancestor != null; depth++) {
            if (ancestor.isFocusable()) {
                Log.i(
                        TAG,
                        "focus-target id="
                                + id
                                + " target=ancestor depth="
                                + depth
                                + " focusable=true focusNodeBounds="
                                + focusNodeBounds(ancestor)
                                + " ancestor-text64=\""
                                + encode(ancestor.getText())
                                + "\" ancestor-desc64=\""
                                + encode(ancestor.getContentDescription())
                                + "\"");
                return ancestor;
            }
            ancestor = ancestor.getParent();
        }
        Log.i(
                TAG,
                "focus-target id="
                        + id
                        + " target=none reason=no-focusable-ancestor focusNodeBounds="
                        + focusNodeBounds(target));
        return target;
    }

    /** One node's screen rect as a log token: no spaces, so it never splits another field. */
    private static String focusNodeBounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.toShortString();
    }

    /**
     * The node the platform currently reports as accessibility-focused, or the reason there is none
     * (P8-07 r7 D0, split into its two failure modes in r8 QR7-3).
     *
     * <p>Both reads are evidence: the one BEFORE the request decides whether the focus has to be
     * cleared so the move starts from "not focused" (see [runCommand]), and the one AFTER a silent
     * action and a silent event lets a focus that did land be accepted without an event. A read this
     * service cannot take is not a verdict — a null answer writes `found=false` plus [FocusRead]'s
     * reason on its own line and the command still runs the action and the bounded wait — so a tree
     * that refuses `findFocus` can never invent an acceptance.
     *
     * <p>`found=false` used to conflate "no active window", "the root does not resolve" and "the
     * window has no focus set", and only the last one says anything about the target. The reason
     * token separates them, so the artifact can be re-read without guessing which read failed.
     */
    private FocusRead currentAccessibilityFocus() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return new FocusRead(null, FOCUS_READ_ROOT_NULL);
            }
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            return focused == null
                    ? new FocusRead(null, FOCUS_READ_FOCUS_NONE)
                    : new FocusRead(focused, FOCUS_READ_NONE);
        } catch (RuntimeException unavailable) {
            // A tree that refuses the read is a root this service never reached, not "no focus".
            return new FocusRead(null, FOCUS_READ_ROOT_NULL);
        }
    }

    /**
     * One focus read: the node when the platform reports one, and otherwise WHICH read came up
     * empty (P8-07 r8 QR7-3).
     *
     * <p>[reason] is exactly one space-free token (`none`, `root-null`, `focus-none`), so it can sit
     * on a probe log line without splitting any other field and without colliding with a stage
     * token the host scans for.
     */
    private static final class FocusRead {

        /** The accessibility-focused node, or null when this read has none to report. */
        private final AccessibilityNodeInfo node;

        /** `none` with a node; `root-null` or `focus-none` without one. */
        private final String reason;

        FocusRead(AccessibilityNodeInfo node, String reason) {
            this.node = node;
            this.reason = reason;
        }
    }

    /**
     * Whether [node] is THIS command's target, by the same two bases the focus-event matcher uses:
     * its own text or content description carries [needle], or its rect overlaps [commandBounds] —
     * the rect of the chosen visible target.
     *
     * <p>An ancestor that merged the target's text, or whose rect covers the target's, is the node
     * TalkBack speaks for this target, so it is the focus that has to be cleared for the utterance
     * to happen inside the command's window. A null [node], an empty needle and an empty rect all
     * answer false, so neither degenerate read can clear a focus or accept one.
     */
    private static boolean isCommandTarget(
            AccessibilityNodeInfo node, String needle, Rect commandBounds) {
        if (node == null) {
            return false;
        }
        if (!needle.isEmpty()
                && (contains(node.getText(), needle)
                        || contains(node.getContentDescription(), needle))) {
            return true;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !commandBounds.isEmpty() && Rect.intersects(bounds, commandBounds);
    }

    /**
     * One observed event's rect as a log token: `[left,top,right,bottom]`, or `[]` for no rect.
     *
     * <p>A focus event's source may be unreadable (see [recordFocusEvent]) and an empty rect
     * intersects nothing, so `[]` is both "the service could not read it" and "this rect cannot be
     * the bounds basis" — never a zero-size rect at the origin, which is what
     * `Rect#toShortString` would print for an unreadable source. Four comma-separated sides (rather
     * than two bracketed pairs) keep the empty case distinguishable and still space-free.
     */
    private static String rectToken(Rect bounds) {
        if (bounds.isEmpty()) {
            return "[]";
        }
        return "["
                + bounds.left
                + ","
                + bounds.top
                + ","
                + bounds.right
                + ","
                + bounds.bottom
                + "]";
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

    /**
     * One observed `TYPE_VIEW_ACCESSIBILITY_FOCUSED`, kept only until a command stops matching it.
     * The count and the arrival time are what let a command prove the event followed its action.
     */
    private static final class FocusEvent {

        /** The monotonic count this event was recorded under; a command compares its own reading. */
        private final long sequence;

        /** `SystemClock.elapsedRealtime()` at receipt, in the same clock as the action's stamp. */
        private final long receivedAt;

        /** The event's own text, joined; empty when the platform carried none. */
        private final String text;

        /**
         * The same text as the platform split it into `CharSequence` parts, kept because TalkBack
         * speaks those parts as one run: a needle spanning two of them is a real utterance.
         */
        private final List<String> textParts;

        /** The event's content description; empty when it carried none. */
        private final String description;

        /** The focused node's screen rect, empty when this service could not read its source. */
        private final Rect bounds;

        FocusEvent(
                long sequence,
                long receivedAt,
                String text,
                List<String> textParts,
                String description,
                Rect bounds) {
            this.sequence = sequence;
            this.receivedAt = receivedAt;
            this.text = text;
            this.textParts = textParts;
            this.description = description;
            this.bounds = bounds;
        }
    }
}
