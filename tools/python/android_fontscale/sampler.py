#!/usr/bin/env python3
"""Drive or gate the P8-00 V-8 / P8-01a font-scale sample on a cloud AVD.

WHAT ONE GREEN RUN PROVES
-------------------------
V-8 of `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md` section 5 asks for the
empty-state samples plus "light theme and 1.3x font scale must not overflow"; P8-01a's
`docs/specs/2026-11-11-p8-01-account-reconciliation-design.md` section 6 additionally requires a
pre-merge device read of the detail screen and the queue copy. The light theme is a canvas-side
sample; the device legs run here against a hosted AVD at the requested `system font_scale`:

- `profile=empty` (the P8-00 leg): the three empty-ledger screens on a **fresh empty install** —
  an empty ledger is exactly the state V-8 samples, so no fixture is needed;
- `profile=seeded` (the P8-01a i3b / P8-02 V-9 leg): one prepared v37 database is pushed into the
  app's stable storage before launch, so the search guide face, the search result face, the
  read-only 交易详情 screen and the 账户对账 queue are sampled on a ledger that actually holds one
  transfer. The seeded instant is clock-relative (D7-9), so the database bytes — and the recorded
  seed sha256 — differ per dispatch; the database itself is never uploaded;
- `profile=refund` (the P8-03 V-14 leg): the same seeded database, whose confirmed manual expense
  is the selectable original, drives the refund-receipt entry (type radio + candidate read) and
  its confirmation page at the requested scale;
- `profile=p806` (the P8-06 batch 6 V-12 leg): an independent anonymous seed of two fixed expenses
  on one clock-relative day, driving the ANALYSIS report region (plus a second scrolled frame that
  shows the natural-day statement in full), the calendar day cell and that day's drilldown rows at
  the requested scale. Its month/day/amount markers are re-derived from the seed database by both
  the driver and the gate, never trusted from host.json alone; the report's resolved month line and
  the absence of the previous-payload caption are required, and a seed from another reporting month
  fails before any capture. The same run then drives REAL TalkBack on the image's own package
  (D-292): the androidTest focus probe moves accessibility focus to each screen's target, TalkBack's
  VERBOSE `SpeechControllerImpl` utterance log is captured inside that target's time window, and
  both the driver and the gate classify each screen as not-obtained / fragments-missing /
  amount-wrong / success. A semantic dump or a focus callback alone is never read as speech.

This rig is NOT a PR gate and never runs on a pull request: dispatch it against a commit that is
already on a branch, and read the artifact.

WHAT IT DOES AND DOES NOT CLAIM
-------------------------------
The screenshot is the evidence a human reads; the gate re-checks only what is mechanically
decidable: the font scale actually took effect, every screenshot is a real image rather than a
blank frame, and each screen's marker text is present in the accessibility tree at that scale (a
row removed from the composition loses its text node) while the strings its previous screen renders
are gone (a capture whose navigation did not land is not evidence). A scrollable screen is NOT
judged for viewport containment here: its off-screen children stay in the tree, so "the marker is
present" never proves "the marker is visible"; the seeded screen's own dump and the artifact read
cover that half.
"No visual overflow" is still a human judgement on the artifact — this rig makes that judgement
cheap, it does not replace it.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import ClassVar

APP_PACKAGE = "com.unifiedledger.android"
MAIN_ACTIVITY = f"{APP_PACKAGE}/.MainActivity"

# The app's stable-storage host (`androidStableStoragePaths` in AndroidLedgerFileSystem.kt): the
# host is the app-private `databases/` directory, and `databases/ledger.db` is the legacy path the
# first generation-aware start migrates. `ledger-generations/` is AUTHORITATIVE whenever it exists
# (resolve rules 2/4), so a reseed must remove the whole generation set — removing only the
# pointer would fail closed with POINTER_MISSING instead of falling back to the legacy file.
APP_DATABASES = "databases"
APP_LEGACY_DATABASE = f"{APP_DATABASES}/ledger.db"
APP_STORAGE_PATHS = (
    f"{APP_DATABASES}/ledger-generations",
    f"{APP_DATABASES}/active-generation",
    f"{APP_DATABASES}/active-generation.new",
    f"{APP_DATABASES}/switch-journal",
    APP_LEGACY_DATABASE,
    APP_LEGACY_DATABASE + "-wal",
    APP_LEGACY_DATABASE + "-shm",
    APP_LEGACY_DATABASE + "-journal",
)
# /data/local/tmp is the one path the shell user always owns (the android_scale runner's staging
# directory precedent: push there, then `run-as … cp` into the app-private host).
REMOTE_SEED_DIR = "/data/local/tmp/ul-fontscale-seed"
REMOTE_SEED = f"{REMOTE_SEED_DIR}/ledger.db"

# The seeded ledger's literals. The app fixes its single ledger identity in
# `android-app/.../App.kt` (`PRODUCT_LEDGER_ID = "ledger-local-test"`); the seed writer in
# `ledger-data/src/jvmTest` uses the same two account/note literals, so a drift shows up as a
# missing tap target or marker rather than as a silently empty sample.
SEEDED_LEDGER_ID = "ledger-local-test"
SEEDED_ACCOUNT_NAME = "对账样本账户"
SEEDED_TRANSFER_NOTE = "对账样本转账"
RECONCILIATION_TITLE = "账户对账"

# P8-03 i2c (V-14): the seed now also holds one confirmed manual expense (12.34, paid from a third
# owned asset account, booked into the DEFAULT catalog leaf 早餐) so the refund entry has a
# selectable original. The candidate row's category name is that leaf's CURRENT name and the
# arrival account is the seeded third asset account; both literals are owned by the seed writer,
# so a drift shows up as a missing tap target or marker, never as a silently empty sample. The
# amount field label and the typed amount are the entry form's own strings.
SEEDED_REFUND_ACCOUNT = "退款样本账户"
SEEDED_REFUND_CATEGORY = "早餐"
REFUND_AMOUNT_FIELD = "退款金额"
REFUND_AMOUNT_TEXT = "3.00"
# The refund draft starts with no occurred-at (the home FAB opens an empty draft and the type
# switch only carries that null), and the Continue gate requires it, so the leg must type one.
# The ISO-8601 instant form is one of the frozen accepted formats and needs no wall-clock math.
REFUND_OCCURRED_AT_FIELD = "发生时间"
REFUND_OCCURRED_AT_TEXT = "2026-11-12T08:00:00Z"
# The entry form's and the confirmation page's own titles. The confirmation's negative marker is
# the entry title, so a 继续 that did not leave the form is not evidence.
REFUND_ENTRY_TITLE = "新增退款到账"

# Capture markers that only a SUCCESSFUL read renders. A screen title is not one: the detail's
# 交易详情 is also in the 无法读取交易详情 failure copy, and the queue's 账户对账 is also the
# account-flow header button that opens it (so that wait could be satisfied on the previous screen).
# - P503LedgerView.kt renders `备注：${data.note ?: "无"}` inside the Success-only 账目信息 card; no
#   failure branch renders a 备注 row. The home flow row composes `，备注 <note>` (a comma, not the
#   full-width colon), so this marker cannot match the previous screen either.
SEEDED_DETAIL_MARKER = f"备注：{SEEDED_TRANSFER_NOTE}"
# - reconciliationUncheckedPartitionText (P503ReconciliationPresentation.kt) renders
#   `未核对 <count> 条` only in the queue's LIST face (P503ReconciliationScreen.kt); the seed
#   writes exactly one PENDING leg, so the count is 1.
SEEDED_QUEUE_MARKER = "未核对 1 条"
# The queue sits one navigation step behind the account-flow screen, whose header renders 账户流水;
# its absence from the queue dump proves the 账户对账 tap actually left that screen.
ACCOUNT_FLOW_HEADER = "账户流水"

# P8-02 i2b-1 (V-9, spec section 5; D7-10) markers for the two search faces the 1.3x sample now
# captures first. Both are strings only the face that follows a successful step renders:
# - the guide copy exists ONLY before the first submit (P503SearchPresentation.searchFace's
#   `!readIssued` branch), so it cannot be satisfied by the home screen the capsule leaves;
# - the result header is `搜索结果 · 共 N 笔`, and the empty-result face's copy (没有找到匹配的交易。)
#   shares none of it, so a failed read cannot pass as a result list.
# Neither marker is a count form (…… · N 笔, 共 N 笔): those already ride the seeded home screen.
SEARCH_GUIDE_MARKER = "输入条件后开始搜索"
SEARCH_RESULTS_MARKER = "搜索结果 ·"
# The seeded home's count header. It is the launch marker AND the string the search and refund
# screens must not show, so "the captured screen left the home tab" is one literal with one source.
# The seeded ledger holds the transfer and the refund-leg manual expense, hence 2.
SEEDED_HOME_COUNT = "流水 · 共 2 笔"

# P8-06 batch 6 (V-12): the independent `p806` seed is written by
# `ledger-data/src/jvmTest/.../P806FontscaleSeedWriterTest.kt` through the real manual-expense
# commit port. Its two fixed amounts and its default-catalog accounts are the only constants the
# sampler needs; the month, day, count and exact displayed amounts are DERIVED from the pushed
# database (see `derive_p806_seed_facts`), so a change to the seed or to the reporting zone shows
# up as a gate disagreement rather than as a silently stale literal.
P806_LEDGER_ID = "ledger-local-test"
P806_DAY_ACCOUNT_ID = "expense-account-local"
# The default payment account: its negative legs are the SIGNED amounts a detail row renders
# (`-12.34 CNY`), so the day-detail screen's required strings are derived, never typed.
P806_PAYMENT_ACCOUNT_ID = "asset-payment-local"
P806_SEED_TRANSACTION_COUNT = 2
# The report/calendar/drilldown strings a SUCCESSFUL read renders. The values are filled per run
# by `p806_screens`; the labels are the product's own frozen text.
P806_REPORT_TITLE = "报表期间"
P806_REPORT_NET_LABEL = "净支出（含退款）"
P806_REPORT_INCOME_LABEL = "普通收入"
P806_REPORT_COUNT_LABEL = "普通收支笔数："
# The explicit "this block is the previous success" caption. A capture that shows it is not the
# current period's report, so the report frame must not carry it.
P806_REPORT_LAST_SUCCESS_CAPTION = "以下为最近一次成功载荷（旧数据）："
P806_CALENDAR_TITLE = "收支日历"
P806_DRILLDOWN_TITLE = "报表明细"
P806_REPORT_BALANCE_LABEL = "结余"

# The D-292 behavioural evidence: real TalkBack emits this VERBOSE log line with the spoken
# fragment text. Only TalkBack's own line can satisfy the speech limb; the focus probe supplies the
# target and the time window, never the utterance. This is the `-v brief` shape the run reads
# (`adb logcat -d -v brief`) with BOTH pids elided: the `pid` right after the tag is the logging
# process's own pid (the padded `(   20):` column) and may be absent on API 36, where the row reads
# `V/talkback: SpeechControllerImpl( 5884): Speaking fragment text=…`; the one on
# `SpeechControllerImpl` is the controller's. Every pid changes per run, so the recorded signal
# names the columns that `_TALKBACK_FRAGMENT` anchors to instead of one run's numbers.
P806_TALKBACK_LOG_SIGNAL = "V/talkback[(pid)]: SpeechControllerImpl[(pid)]: Speaking fragment text="
P806_TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
# The API 33+ POST_NOTIFICATIONS prompt Android Accessibility Suite raises on this AVD is a
# permissioncontroller grant dialog. The exact package-scoped resource ids and the exact message
# are both required, so no other app's or permission's dialog is ever touched.
P806_PERMISSION_CONTROLLER_PACKAGE = "com.android.permissioncontroller"
P806_NOTIFICATION_PROMPT_MESSAGE = "Allow Android Accessibility Suite to send you notifications?"
# TalkBack's touch exploration consumes a lone tap as "move accessibility focus", so its activation
# gesture is the double tap. Android recognises that gesture when the second touch lands inside its
# own double-tap window (300 ms); 120 ms keeps one adb round-trip well inside it while staying a
# distinct second press rather than one coalesced touch.
P806_TALKBACK_DOUBLE_TAP_INTERVAL_S = 0.12
# The prompt is dismissed by the app process, so the first frame read back after the tap can still
# show it. Four readbacks 0.8 s apart cover the ~2 s the observed close needed and bound a stuck
# prompt at 2.4 s — the old fixed 2 s wait, without failing on one slow frame.
PERMISSION_DIALOG_READBACK_ATTEMPTS = 4
PERMISSION_DIALOG_READBACK_INTERVAL_S = 0.8
# The exported developer-settings entry point verified in google/talkback source: the preferences
# activity reads a `FragmentName` extra and instantiates that fragment inside the TalkBack process,
# so the LogUtils level it sets is the one the service uses.
P806_TALKBACK_PREFS_ACTIVITY = (
    "com.google.android.marvin.talkback/com.android.talkback.TalkBackPreferencesActivity"
)
P806_TALKBACK_DEVELOPER_FRAGMENT = (
    "com.google.android.accessibility.talkback.preference.base.DeveloperPrefFragment"
)
# The list entries are literal, non-translated strings (`pref_log_level_entries` in
# donottranslate.xml), so the row can be addressed by the installed app's own rendered value rather
# than by a locale-dependent title.
P806_TALKBACK_LEVEL_NAMES = ("NONE", "ASSERT", "ERROR", "WARN", "INFO", "DEBUG", "VERBOSE")
P806_TALKBACK_LEVEL = "VERBOSE"
# The test-only probe that moves accessibility focus (D-292). It lives in the androidTest APK; the
# settings component string is the platform's flattened form of that manifest entry.
P806_PROBE_PACKAGE = "com.unifiedledger.android.test"
P806_PROBE_COMPONENT = (
    "com.unifiedledger.android.test/com.unifiedledger.android.A11yFocusProbeService"
)
SETTING_A11Y_COMMAND = "ul_a11y_command"
SETTING_A11Y_PREFIX = "ul_a11y_prefix"
SETTING_A11Y_LIMIT = "ul_a11y_limit"
SETTING_A11Y_CLICK = "ul_a11y_click"
# The auto pass must not move focus while a host-commanded window is open: this prefix matches no
# node (the probe compares with startsWith) and a zero limit caps the pass anyway.
P806_TALKBACK_AUTO_SENTINEL = "__ul_p806_speech_window__"
TALKBACK_STATUS_LABELS = {
    "not-obtained": "未取得",
    "fragments-missing": "片段缺失",
    "amount-wrong": "金额错误",
    "success": "成功",
}

MIN_SCREENSHOT_BYTES = 20_000
DEFAULT_TIMEOUT = 60


class SampleError(RuntimeError):
    """A precondition or observation failed; the run must not be read as evidence."""


def parse_bounds(bounds: str) -> tuple[int, int, int, int]:
    """The `[x1,y1][x2,y2]` corners of one uiautomator bounds attribute."""
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds.strip())
    if match is None:
        raise SampleError(f"unparsable bounds: {bounds!r}")
    x1, y1, x2, y2 = (int(value) for value in match.groups())
    return x1, y1, x2, y2


def screen_center(bounds: str) -> tuple[int, int]:
    """Centre of a `[x1,y1][x2,y2]` uiautomator bounds attribute."""
    x1, y1, x2, y2 = parse_bounds(bounds)
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def _nodes(xml_text: str) -> list[ET.Element]:
    """Every `node` of an accessibility dump, in document order."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    return list(root.iter("node"))


def _matches(node: ET.Element, label: str, contains: bool) -> bool:
    return any(
        (label in value) if contains else (value == label)
        for value in (node.get("text") or "", node.get("content-desc") or "")
    )


def _rect(node: ET.Element) -> tuple[int, int, int, int]:
    return parse_bounds(node.get("bounds") or "")


def _vertical_overlap(left: tuple[int, int, int, int], right: tuple[int, int, int, int]) -> bool:
    return left[1] < right[3] and right[1] < left[3]


def _vertical_gap(left: tuple[int, int, int, int], right: tuple[int, int, int, int]) -> int:
    """Zero when the two rects share a row band, else the distance between the facing edges."""
    if _vertical_overlap(left, right):
        return 0
    return min(abs(left[1] - right[3]), abs(right[1] - left[3]))


def _is_clickable_radio(node: ET.Element) -> bool:
    return node.get("clickable") == "true" and (
        node.get("checkable") == "true" or (node.get("class") or "").endswith("RadioButton")
    )


def _focused_text_field(xml_text: str) -> bool:
    """True when any text-field node reports `focused="true"` (the caret landed without a redraw)."""
    return any(
        (node.get("class") or "").endswith("EditText") and node.get("focused") == "true"
        for node in _nodes(xml_text)
    )


def find_radio_center(xml_text: str, label: str, contains: bool = False) -> tuple[int, int]:
    """Centre of the radio that owns the option row labelled `label`.

    A Compose option row is a clickable RadioButton plus a sibling Text, i.e. two separate
    semantics nodes, so the label alone does not address the hit target. The radio is the nearest
    clickable node to the LEFT of the label in the same vertical band: nearest rather than first,
    because every option of the six-way type row shares one band and only the radio immediately
    left of a label belongs to it. Two label nodes resolving to different radios, a tie for
    nearest, or no clickable node at all is an ambiguity — never a guess.
    """
    if not label:
        raise SampleError("an empty radio label would match every row")
    nodes = _nodes(xml_text)
    radios: list[tuple[int, int]] = []
    for node in nodes:
        if not _matches(node, label, contains):
            continue
        node_rect = _rect(node)
        if _is_clickable_radio(node):
            radios.append(screen_center(node.get("bounds") or ""))
            continue
        clickables = [
            other
            for other in nodes
            if other is not node
            and other.get("clickable") == "true"
            and _vertical_overlap(node_rect, _rect(other))
            and screen_center(other.get("bounds") or "")[0] < (node_rect[0] + node_rect[2]) // 2
        ]
        if not clickables:
            raise SampleError(f"radio label {label!r} has no clickable node to its left in its row")
        # Order by vertical gap first and horizontal distance second: a wrapped candidate label at
        # 1.3x can sit closer to the next row's radio, and same-width radios all end at the same x,
        # so "largest right edge" would either pick the wrong row or call an equal-width row a tie.
        label_center_x = (node_rect[0] + node_rect[2]) // 2
        scored = sorted(
            (
                (
                    _vertical_gap(node_rect, _rect(other)),
                    abs(label_center_x - ((_rect(other)[0] + _rect(other)[2]) // 2)),
                    other,
                )
                for other in clickables
            ),
            key=lambda item: (item[0], item[1]),
        )
        if len(scored) > 1 and scored[0][0] == scored[1][0] and scored[0][1] == scored[1][1]:
            raise SampleError(f"radio label {label!r} is ambiguous: two clickables are equidistant")
        radios.append(screen_center(scored[0][2].get("bounds") or ""))
    distinct = list(dict.fromkeys(radios))
    if not distinct:
        raise SampleError(f"radio label {label!r} is not in the accessibility tree")
    if len(distinct) > 1:
        raise SampleError(f"radio label {label!r} matches {len(distinct)} different option rows")
    return distinct[0]


def find_field_center(xml_text: str, field_label: str) -> tuple[int, int]:
    """Centre of the editable field that belongs to `field_label`.

    Compose renders an OutlinedTextField's label as its own text node while the editable area is an
    `android.widget.EditText` node, so the label alone does not address the caret. The field is the
    EditText nearest to any node carrying the label — nearest rather than first, because the
    supporting text under the same field also carries the label ("退款金额不超过…") and the form
    holds other text fields (occurred-at, amount expression). A label with no EditText at all fails
    loudly: that screen has no writable field.
    """
    if not field_label:
        raise SampleError("an empty field label cannot address a text field")
    nodes = _nodes(xml_text)
    labels = [node for node in nodes if _matches(node, field_label, contains=True)]
    if not labels:
        raise SampleError(f"text field label {field_label!r} is not in the accessibility tree")
    for node in labels:
        if (node.get("class") or "").endswith("EditText"):
            return screen_center(node.get("bounds") or "")
    fields = [node for node in nodes if (node.get("class") or "").endswith("EditText")]
    if not fields:
        raise SampleError(f"text field label {field_label!r} has no EditText node to focus")
    scored: list[tuple[int, int, int, tuple[int, int]]] = []
    for index, field in enumerate(fields):
        field_rect = _rect(field)
        for label in labels:
            label_rect = _rect(label)
            scored.append(
                (
                    _vertical_gap(label_rect, field_rect),
                    abs((label_rect[0] + label_rect[2]) // 2 - (field_rect[0] + field_rect[2]) // 2),
                    index,
                    screen_center(field.get("bounds") or ""),
                )
            )
    return min(scored)[3]


# `adb shell input text` sends its argument through the device shell and cannot type non-ASCII (an
# NPE inside InputShellCommand.sendText). A literal space would end the argument, so it is `%s`;
# every shell metacharacter is backslash-escaped for the remote shell.
_ADB_TEXT_SHELL_CHARACTERS = frozenset("\\\"'`$&<>|;()*?~#!{}[]^")


def adb_input_text(text: str) -> str:
    """The exact `input text` argument for `text` (ASCII only, shell-escaped)."""
    if not text:
        raise SampleError("refusing to type an empty string into a field")
    if any(ord(character) > 0x7F for character in text):
        raise SampleError(f"adb input text cannot type non-ASCII text: {text!r}")
    return "".join(
        "%s"
        if character == " "
        else ("\\" + character if character in _ADB_TEXT_SHELL_CHARACTERS else character)
        for character in text
    )


def ime_is_shown(dumpsys: str) -> bool:
    """`mInputShown` from `dumpsys input_method`; a missing answer fails loudly.

    A BACK dismisses the keyboard only while it is actually shown — otherwise it is a navigation
    step — so this read decides whether one is sent. The dump can print the flag in more than one
    service section (the manager and the IME itself, which may briefly disagree during an
    animation), so the LAST occurrence is the current state; only a dump with no occurrence at all
    is unreadable.
    """
    values = re.findall(r"mInputShown=(true|false)", dumpsys)
    if not values:
        raise SampleError("dumpsys input_method reports no mInputShown; cannot tell whether the keyboard is up")
    return values[-1] == "true"


def scroll_swipe_points(xml_text: str) -> tuple[int, int, int, int]:
    """One content swipe's `(x1, y1, x2, y2)` from the dump's window bounds: from two-thirds down
    to one-third down, so a full-height form advances by about a third of the screen per swipe."""
    nodes = _nodes(xml_text)
    if not nodes:
        raise SampleError("the accessibility dump has no window node to derive a swipe from")
    x1, y1, x2, y2 = _rect(nodes[0])
    if x2 <= x1 or y2 <= y1:
        raise SampleError(f"the dump's window bounds {nodes[0].get('bounds')!r} are empty")
    x = (x1 + x2) // 2
    return x, y1 + 2 * (y2 - y1) // 3, x, y1 + (y2 - y1) // 3


def find_reachable_center(xml_text: str, label: str, contains: bool = False) -> tuple[int, int] | None:
    """Centre of the first matching node whose centre is INSIDE the dump's window bounds.

    A scrollable Compose form exposes its off-screen children too, so "the label is in the dump" is
    not "the label is reachable": a centre below the window is exactly the dead tap a bare
    `dump_contains` would accept.
    """
    if not label:
        raise SampleError("an empty label cannot be looked up on screen")
    nodes = _nodes(xml_text)
    if not nodes:
        raise SampleError("the accessibility dump has no window node to compare against")
    x1, y1, x2, y2 = _rect(nodes[0])
    for node in nodes:
        if not _matches(node, label, contains):
            continue
        center = screen_center(node.get("bounds") or "")
        if x1 <= center[0] <= x2 and y1 <= center[1] <= y2:
            return center
    return None


def find_center(xml_text: str, label: str, contains: bool = False) -> tuple[int, int] | None:
    """Centre of the first node whose text or content-description matches `label`.

    `contains=False` is the exact match the dock labels need (账户 is a substring of 账户对账 and
    of every account name, so an exact match is what keeps the dock tap honest). `contains=True`
    is the substring match the composed rows need: an account row appends 已停用 to an inactive
    name, and a flow row's content-description is one composed sentence, so no visible node equals
    the account name or the note.
    """
    if not label:
        raise SampleError("an empty tap/marker label would match every node")
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    for node in root.iter("node"):
        for attribute in ("text", "content-desc"):
            value = node.get(attribute) or ""
            if (label in value) if contains else (value == label):
                return screen_center(node.get("bounds", ""))
    return None


def dump_contains(xml_text: str, needle: str) -> bool:
    """True when any node's text or content-description carries `needle`."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        raise SampleError(f"unparsable accessibility dump: {error}") from error
    for node in root.iter("node"):
        for attribute in ("text", "content-desc"):
            value = node.get(attribute) or ""
            if needle in value:
                return True
    return False


def marker_problems(xml_text: str, marker: str, absent: tuple[str, ...] = ()) -> list[str]:
    """One screen's capture verdict: its marker is present and no previous-screen string is.

    A marker alone cannot always prove the tap landed (the queue's title is also the account-flow
    button that opens it), so a screen may declare strings its previous screen renders and it never
    does; a stuck navigation then fails the capture instead of passing it.
    """
    problems: list[str] = []
    if not dump_contains(xml_text, marker):
        problems.append(f"marker {marker!r} is absent")
    for needle in absent:
        if dump_contains(xml_text, needle):
            problems.append(f"previous-screen string {needle!r} is still visible")
    return problems


def required_problems(xml_text: str, required: tuple[str, ...]) -> list[str]:
    """The dynamic markers a screen must carry (the seed's exact signed amounts and currency)."""
    return [f"required string {needle!r} is absent" for needle in required if not dump_contains(xml_text, needle)]


def viewport_problems(xml_text: str, marker: str) -> list[str]:
    """The marker node's whole rect must sit inside the dump's window bounds.

    `marker_problems` proves the string is in the tree, which a scrollable screen keeps true for
    off-screen children; the supplemental report frame must show the natural-day statement IN
    FULL, so its node may not be clipped by the viewport edge either.
    """
    nodes = _nodes(xml_text)
    if not nodes:
        return ["the accessibility dump has no window node to compare against"]
    window = _rect(nodes[0])
    matches: list[ET.Element] = []
    for node in nodes:
        text = node.get("text") or ""
        description = node.get("content-desc") or ""
        if text == marker or description == marker:
            matches.insert(0, node)
        elif marker in text or marker in description:
            matches.append(node)
    if not matches:
        return [f"marker {marker!r} is absent"]
    rect = _rect(matches[0])
    if rect[0] >= window[0] and rect[1] >= window[1] and rect[2] <= window[2] and rect[3] <= window[3]:
        return []
    return [f"marker {marker!r} is clipped by the window bounds {window!r} (node {rect!r})"]


# AOSP `liblog/logprint.cpp` FORMAT_BRIEF is `%c/%-8.*s(%s%5d): `: the tag is left-justified and
# padded with ASCII spaces to 8 columns, then `(`, the pid right-justified to 5, and `): `. An
# 8-column tag such as `talkback` carries no padding; a shorter one does, e.g. the probe's own
# `I/ULA11Y  (   42): service connected`. Both recognizers anchor to that outer priority/tag
# column and take only horizontal padding ([ \t]) at the structural separators, so padding never
# bridges a line break; a different tag whose message merely quotes a real-looking line, at the
# start of the line or mid-message, is not attributable to the probe or to TalkBack. TalkBack's
# utterance names its speaker in the message: the P7 raw row renders
# `V talkback: SpeechControllerImpl: Speaking fragment text=…`, and the pid columns are optional.
# Two device forms were observed: `V/talkback(   20): SpeechControllerImpl( 20): …` and, on API 36,
# `V/talkback: SpeechControllerImpl( 5884): …` — the tag carries no pid there while the controller
# pid stays inside the message. The spoken text is wrapped in ASCII quotes
# (`String.format("\"%s\"", …)`), and the quotes are stripped so a required value matches the words
# the device actually spoke.
_ULA11Y_LINE = re.compile(r"^[VDIWEF]/ULA11Y[ \t]*\([ \t]*\d+\):[ \t]*(.*)", re.MULTILINE)
_TALKBACK_FRAGMENT = re.compile(
    r"^[VDIWEF]/talkback(?:[ \t]*\([ \t]*\d+\))?:[ \t]*SpeechControllerImpl(?:\([ \t]*\d+\))?:"
    r"[ \t]*Speaking fragment text=(.*?), utteranceId=",
    re.MULTILINE,
)
_COMMAND_NEEDLE64 = re.compile(r"\bneedle64=([A-Za-z0-9+/=]+)")


def notification_permission_dialog_identity(tree: str) -> bool:
    """True iff `tree` is the exact Accessibility Suite POST_NOTIFICATIONS grant dialog.

    The permissioncontroller package-scoped ids and the exact prompt text must all be present; a
    differently worded or differently owned permission dialog never matches.
    """
    dialog = False
    message = False
    for node in _nodes(tree):
        resource_id = node.get("resource-id") or ""
        if resource_id == f"{P806_PERMISSION_CONTROLLER_PACKAGE}:id/grant_dialog":
            dialog = True
        elif (
            resource_id == f"{P806_PERMISSION_CONTROLLER_PACKAGE}:id/permission_message"
            and (node.get("text") or "").strip() == P806_NOTIFICATION_PROMPT_MESSAGE
        ):
            message = True
    return dialog and message


def enabled_accessibility_components(enabled_services: str) -> list[str]:
    """The colon-separated components `enabled_accessibility_services` currently lists."""
    return [
        part.strip()
        for part in enabled_services.strip().split(":")
        if part.strip() and part.strip() != "null"
    ]


def permission_deny_tap_delays(
    enabled_services: str, talkback_component: str = ""
) -> tuple[float, ...]:
    """The waits *between* the deny taps; one gap for a double tap, none for a single tap.

    A tap only activates the button when no accessibility service owns touch exploration. With
    TalkBack enabled the same tap merely moves accessibility focus, so the press has to be repeated
    inside Android's double-tap window. The exact enabled component decides, because a run that
    enabled only the focus probe keeps ordinary touch semantics.
    """
    components = enabled_accessibility_components(enabled_services)
    if talkback_component and talkback_component in components:
        return (P806_TALKBACK_DOUBLE_TAP_INTERVAL_S,)
    return ()


def permission_dialog_readback_delays() -> tuple[float, ...]:
    """The wait before each readback of the prompt, so one poll cannot disagree with another."""
    return (0.0,) + (PERMISSION_DIALOG_READBACK_INTERVAL_S,) * (
        PERMISSION_DIALOG_READBACK_ATTEMPTS - 1
    )


def find_resource_center(xml_text: str, resource_id: str) -> tuple[int, int] | None:
    """Centre of the first node whose `resource-id` equals `resource_id`."""
    for node in _nodes(xml_text):
        if node.get("resource-id") == resource_id:
            return screen_center(node.get("bounds") or "")
    return None


def find_talkback_level_summary(xml_text: str) -> tuple[int, int] | None:
    """Centre of the TalkBack developer screen's Log output level row.

    The row is the preference whose summary currently renders one of the literal level names; the
    title is localized, the summary value is not.
    """
    nodes = _nodes(xml_text)
    summaries = [
        node
        for node in nodes
        if (node.get("resource-id") or "").endswith(":id/summary")
        and (node.get("text") or "").strip() in P806_TALKBACK_LEVEL_NAMES
    ]
    for node in summaries or nodes:
        if (node.get("text") or "").strip() in P806_TALKBACK_LEVEL_NAMES:
            return screen_center(node.get("bounds") or "")
    return None


def talkback_level_summary_value(xml_text: str) -> str | None:
    """The rendered Log output level summary, when the developer screen exposes one."""
    for node in _nodes(xml_text):
        if not (node.get("resource-id") or "").endswith(":id/summary"):
            continue
        text = (node.get("text") or "").strip()
        if text in P806_TALKBACK_LEVEL_NAMES:
            return text
    return None


def speech_fragments(log_text: str) -> list[str]:
    """Every TalkBack utterance fragment in `log_text`, in log order, unquoted and verbatim.

    `SpeechControllerImpl` logs a silenced fragment as the literal `text=null` (unquoted); that is
    no utterance at all, so it never counts as a spoken fragment.
    """
    fragments = []
    for match in _TALKBACK_FRAGMENT.finditer(log_text):
        text = match.group(1)
        if text == "null":
            continue
        if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
            text = text[1:-1]
        fragments.append(text)
    return fragments


def _normalized_speech(text: str) -> str:
    """Speech matching ignores layout whitespace, never characters or order."""
    return re.sub(r"\s+", "", text)


def _speech_contains(spoken: str, needle: str) -> bool:
    """`needle` occurs in the normalized `spoken` on a number boundary.

    Fragment joining removes spaces, so a bare substring test would let `5.00` match inside
    `15.00` or `5.001`. A match is accepted only when neither edge of the matched range is a digit
    or decimal point continuing a number the needle's own digit edge would then split.
    """
    target = _normalized_speech(needle)
    if not target:
        return False
    start = 0
    while True:
        index = spoken.find(target, start)
        if index < 0:
            return False
        before = spoken[index - 1] if index > 0 else ""
        after_index = index + len(target)
        after = spoken[after_index] if after_index < len(spoken) else ""
        before_conflict = (
            (target[0].isdigit() or target[0] == ".")
            and bool(before)
            and (before.isdigit() or before == ".")
        )
        after_conflict = (
            (target[-1].isdigit() or target[-1] == ".")
            and bool(after)
            and (after.isdigit() or after == ".")
        )
        if not before_conflict and not after_conflict:
            return True
        start = index + 1


def _ula11y_tokens(line: str) -> set[str] | None:
    match = _ULA11Y_LINE.search(line)
    return None if match is None else set(match.group(1).split())


def probe_service_connected(log_text: str) -> bool:
    """True iff the probe's own outer line records `service connected`.

    Uses the same anchored brief-column recognizer as the command window, so padded tag/pid and a
    foreign tag or a mid-message mimic are classified identically in both places.
    """
    for line in log_text.splitlines():
        match = _ULA11Y_LINE.search(line)
        if match is not None and match.group(1).strip() == "service connected":
            return True
    return False


def talkback_bound_service(bound_section: str) -> bool:
    """True when a `dumpsys accessibility` Bound services section lists TalkBack.

    API 36 renders each bound entry as `Service[label=TalkBack, feedbackType[…], …]` and never
    prints the package name, so the exact label is the primary signal. The package name stays as
    the fallback for images whose dump carries it; an empty or probe-only section matches neither.
    """
    return "Service[label=TalkBack," in bound_section or P806_TALKBACK_PACKAGE in bound_section


def _ula11y_event(line: str) -> tuple[str, str] | None:
    """`(kind, command id)` for one probe control line, else None.

    The kinds are the ordered probe stages: `command` (the host's request with its lossless
    `needle64` target), `target` (the node search result), `begin`, `focus-result` and `end`.
    """
    tokens = _ula11y_tokens(line)
    if tokens is None:
        return None
    ids = [token[3:] for token in tokens if token.startswith("id=")]
    if not ids:
        return None
    command_id = ids[0]
    if "command" in tokens and "action=focus" in tokens:
        return "command", command_id
    if "target" in tokens:
        return "target", command_id
    if "speech-window-begin" in tokens:
        return "begin", command_id
    if "focus-result" in tokens:
        return "focus-result", command_id
    if "speech-window-end" in tokens:
        return "end", command_id
    return None


def isolate_talkback_window(log_text: str, command_id: int) -> tuple[list[str], str]:
    """One command's ordered raw window: `(lines, problem)`.

    The window opens at the probe's own `command ... id=<command_id> needle64=...` line and must
    then carry exactly one `target` result, one `speech-window-begin`, one `focus-result` and one
    `speech-window-end`, all with the same id and in that order. No other command's control line
    may sit inside the window. A missing or duplicated stage, an end without a begin, a reordered
    stage, an unclosed window or an interleaved command is a problem: no stage or fragment from
    another command may ever support this target.
    """
    marker = f"id={command_id}"
    lines = log_text.splitlines()
    events: list[tuple[int, str, str]] = []
    for index, line in enumerate(lines):
        event = _ula11y_event(line)
        if event is not None:
            events.append((index, event[0], event[1]))
    own = [(index, kind) for index, kind, ident in events if ident == str(command_id)]
    problem = ""
    commands = [index for index, kind in own if kind == "command"]
    if not commands:
        return [], f"command {marker} never began a speech window"
    if len(commands) > 1:
        problem = f"command {marker} began more than one window"
    start = commands[0]
    stages: dict[str, list[int]] = {}
    for index, kind in own:
        if index >= start:
            stages.setdefault(kind, []).append(index)
    end = stages.get("end", [None])[0]
    stop = end if isinstance(end, int) else len(lines)
    for index, _kind, ident in events:
        if start < index < stop and ident != str(command_id):
            problem = problem or f"command {marker} interleaves another command's window"
    expected_order = ("command", "target", "begin", "focus-result", "end")
    previous_index = -1
    for kind in expected_order:
        indexes = stages.get(kind, [])
        if not indexes:
            if kind == "end":
                problem = problem or f"command {marker}'s speech window never ended"
                return lines[start:], problem
            problem = problem or f"command {marker} carries no {kind} stage"
            return lines[start:], problem
        if len(indexes) > 1:
            problem = problem or f"command {marker} records {kind} more than once"
        if indexes[0] <= previous_index:
            problem = problem or f"command {marker}'s {kind} stage is out of order"
        previous_index = indexes[0]
    if not isinstance(end, int):
        return lines[start:], problem or f"command {marker}'s speech window never ended"
    return lines[start : end + 1], problem


def _window_command_target(window: list[str], command_id: int) -> tuple[str | None, str]:
    """Re-decode the lossless target the probe's own command line carries."""
    for line in window:
        tokens = _ula11y_tokens(line)
        if tokens is None or "command" not in tokens or "action=focus" not in tokens:
            continue
        if f"id={command_id}" not in tokens:
            continue
        match = _COMMAND_NEEDLE64.search(line)
        if match is None:
            return None, "the command line carries no lossless target identifier"
        try:
            return (
                base64.b64decode(match.group(1), validate=True).decode("utf-8"),
                "",
            )
        except (ValueError, UnicodeDecodeError):
            return None, "the command line's target identifier is unreadable"
    return None, "the isolated window carries no probe command"


def _decode_node_field(line: str, name: str) -> str | None:
    """One base64 text/description field of the found-node record, decoded losslessly."""
    match = re.search(rf'\b{name}="([^"]*)"', line)
    if match is None:
        return None
    try:
        return base64.b64decode(match.group(1), validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None


def _window_node_found(window: list[str], command_id: int, target: str) -> bool:
    """True iff the probe's found-node record for this id carries `target` in ITS text/description.

    The record's human-readable fields are trimmed previews, so only the base64 text/description
    count: a preview saying `text="target"` for every node cannot bind the expected target.
    """
    for line in window:
        tokens = _ula11y_tokens(line)
        if tokens is None or "target" not in tokens or f"id={command_id}" not in tokens:
            continue
        if "found=true" not in tokens:
            continue
        values = (_decode_node_field(line, "text64"), _decode_node_field(line, "desc64"))
        return any(value is not None and target in value for value in values)
    return False


def _speech_window_lines(window: list[str], command_id: int) -> list[str]:
    """Only the lines strictly between this command's begin and end stages.

    Command and found-node lines stay in the isolated window for attribution, but their text is
    never speech: a required amount present only before `speech-window-begin` (or a fragment after
    `speech-window-end`) cannot satisfy the spoken requirement.
    """
    begin: int | None = None
    for index, line in enumerate(window):
        tokens = _ula11y_tokens(line)
        if tokens is None or f"id={command_id}" not in tokens:
            continue
        if "speech-window-begin" in tokens:
            begin = index
            continue
        if "speech-window-end" in tokens and begin is not None:
            return window[begin + 1 : index]
    return window[begin + 1 :] if begin is not None else []


def _window_focus_accepted(window: list[str], command_id: int) -> bool:
    accepted = False
    rejected = False
    for line in window:
        tokens = _ula11y_tokens(line)
        if tokens is None or "focus-result" not in tokens or f"id={command_id}" not in tokens:
            continue
        accepted = accepted or "accepted=true" in tokens
        rejected = rejected or "accepted=false" in tokens
    return accepted and not rejected


def talkback_check_verdict(
    log_text: str, command_id: int, target: str, required: tuple[str, ...]
) -> tuple[str, list[str], list[str], str]:
    """Classify one command's window: `(status, fragments, missing, reason)`.

    Every value is derived from THIS command's isolated raw window only. The window must carry the
    probe's own command line and re-decode to `target`, record a found node whose own text or
    description contains `target`, complete the ordered accepted/end stages, and contain every
    required value in the fragments strictly between begin and end. `not-obtained` covers a
    missing, malformed, relabelled, unfound or unaccepted window; `fragments-missing` means it
    completed accepted but produced no TalkBack fragment; `amount-wrong` means it spoke, but a
    required value is absent from ITS fragments (on a number boundary); `success` means all hold.
    """
    window, problem = isolate_talkback_window(log_text, command_id)
    fragments = speech_fragments("\n".join(_speech_window_lines(window, command_id)))
    spoken = _normalized_speech("".join(fragments))
    missing = [needle for needle in required if not _speech_contains(spoken, needle)]
    if problem:
        return "not-obtained", fragments, missing, problem
    focused_target, target_problem = _window_command_target(window, command_id)
    if target_problem:
        return "not-obtained", fragments, missing, target_problem
    if focused_target != target:
        return (
            "not-obtained",
            fragments,
            missing,
            f"the probe's raw command focused {focused_target!r}, not the required target {target!r}",
        )
    if not _window_node_found(window, command_id, target):
        return (
            "not-obtained",
            fragments,
            missing,
            "the probe's found-node record does not carry the required target",
        )
    if not _window_focus_accepted(window, command_id):
        return "not-obtained", fragments, missing, f"command id={command_id} has no accepted focus result"
    if not fragments:
        return (
            "fragments-missing",
            fragments,
            missing,
            "the accepted window produced no TalkBack fragment",
        )
    if missing:
        return (
            "amount-wrong",
            fragments,
            missing,
            "required values are absent from this window's fragments",
        )
    return "success", fragments, missing, ""


def overall_speech_status(statuses: list[str]) -> str:
    """A screen succeeds only when every one of its ordered target windows succeeds."""
    for status in statuses:
        if status != "success":
            return status
    return "success"


def talkback_status_label(status: str) -> str:
    return TALKBACK_STATUS_LABELS.get(status, status)


def sha256_file(path: Path) -> str:
    """The exact digest of a file's bytes (the seed a run pushed, re-read by a gate)."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_font_scale(raw: str) -> float:
    """The device's `settings get system font_scale` answer."""
    try:
        return float(raw.strip())
    except ValueError as error:
        raise SampleError(f"font_scale is not a number: {raw!r}") from error


@dataclass(frozen=True)
class TapStep:
    """Tap the centre of the first node whose text/content-description matches `target`."""

    target: str
    # Composed strings (an account row's name suffix, a flow row's content-description) need a
    # substring; the dock labels are exact so 账户 never taps 账户对账.
    contains: bool = False


@dataclass(frozen=True)
class BackStep:
    """The system back key — the host's back dispatch (`input keyevent 4`)."""


@dataclass(frozen=True)
class RadioStep:
    """Tap the radio that owns the option row labelled `label` (see `find_radio_center`)."""

    label: str
    contains: bool = False


@dataclass(frozen=True)
class InputTextStep:
    """Focus the field labelled `field_label`, then type `text` through `adb input text`."""

    field_label: str
    text: str


@dataclass(frozen=True)
class DismissImeStep:
    """Collapse the software keyboard, but only when the platform reports it is actually shown."""


@dataclass(frozen=True)
class ScrollStep:
    """Swipe the content region until `label` is ON SCREEN, at most `max_swipes` swipes."""

    label: str
    max_swipes: int = 6
    # Also require the label node's whole rect inside the window (the supplemental report frame
    # must show the natural-day statement in full, not merely its centre).
    fully: bool = False


BACK = BackStep()
DISMISS_IME = DismissImeStep()

Step = TapStep | BackStep | RadioStep | InputTextStep | DismissImeStep | ScrollStep


def tap_radio_for(label: str, contains: bool = False) -> RadioStep:
    """The step that taps an option row's radio by the row's visible label."""
    if not label:
        raise SampleError("an empty radio label would match every row")
    return RadioStep(label, contains)


def input_text(field_label: str, text: str) -> InputTextStep:
    """The step that types `text` into the field labelled `field_label` (ASCII only)."""
    if not field_label:
        raise SampleError("an empty field label cannot address a text field")
    # Fail at import/construction time, not after a device has already focused the field.
    adb_input_text(text)
    return InputTextStep(field_label, text)


def dismiss_ime() -> DismissImeStep:
    """The step that collapses the keyboard (a no-op when it is already down)."""
    return DISMISS_IME


def scroll_until(label: str, max_swipes: int = 6, fully: bool = False) -> ScrollStep:
    """The step that swipes the content region until `label` is on screen."""
    if not label:
        raise SampleError("an empty scroll label would match every screen")
    if max_swipes <= 0:
        raise SampleError(f"scroll_until needs a positive swipe budget, got {max_swipes}")
    return ScrollStep(label, max_swipes, fully)


def describe_step(step: Step) -> str:
    """The step's short name for host.json and every failure message."""
    if isinstance(step, TapStep):
        return f"tap containing {step.target!r}" if step.contains else f"tap {step.target!r}"
    if isinstance(step, RadioStep):
        return f"tap radio containing {step.label!r}" if step.contains else f"tap radio {step.label!r}"
    if isinstance(step, InputTextStep):
        return f"type {step.text!r} into {step.field_label!r}"
    if isinstance(step, DismissImeStep):
        return "dismiss ime"
    if isinstance(step, ScrollStep):
        suffix = ", fully visible" if step.fully else ""
        return f"scroll to {step.label!r} (max {step.max_swipes} swipes{suffix})"
    return "back"


@dataclass(frozen=True)
class SpeechCheck:
    """One accessibility-focus target and the exact values ITS OWN speech window must contain."""

    target: str
    required: tuple[str, ...]


@dataclass(frozen=True)
class Screen:
    """One artifact: its stem, the steps from the previous captured screen (the launch frame before
    the first capture), its capture marker, and the previous-screen strings the capture must not
    show (the negative marker)."""

    stem: str
    steps: tuple[Step, ...]
    marker: str
    absent: tuple[str, ...] = ()
    # Additional strings a SUCCESSFUL read must render on the same frame (the dynamic markers
    # whose exact values only the seed can tell). The driver checks them before the capture and
    # the gate re-derives the same tuple from the seed, so neither trusts the other's word.
    required: tuple[str, ...] = ()
    # The D-292 speech limb: each ordered check names its focus target (substring match on the
    # node's text or content description) and the values TalkBack's fragments inside THAT target's
    # accepted, completed window must contain. Empty on every non-p806 screen, which keeps those
    # profiles on the screenshot-only path.
    speech: tuple[SpeechCheck, ...] = ()
    # Capture-time proof that the marker's WHOLE rect is inside the window, not merely that the
    # string exists in a scrollable tree (the supplemental report frame needs this).
    marker_in_viewport: bool = False


@dataclass(frozen=True)
class Profile:
    """One dispatch profile: what the launch waits for and the screens it captures in order."""

    name: str
    launch_marker: str
    screens: tuple[Screen, ...]
    # Seeded profiles need the prepared v37 database pushed before launch; empty profiles must
    # never touch the app's data directory (a fresh empty install is the state V-8 samples).
    seeded: bool = False


# The P8-00 V-8 screens: a fresh install bootstraps one default account, so the accounts tab is
# never empty and 资产 samples the 有户无余额 state the E2s canvas documents. Since D-307 the home
# tab paints 账本为空 for a ledger with no transactions at all — before that the branch was
# unreachable and the screen showed 流水 · 共 0 笔 instead. The rig samples what the device shows.
EMPTY_SCREENS: tuple[Screen, ...] = (
    Screen("home", (), "账本为空"),
    Screen("asset", (TapStep("账户"),), "总借入"),
    Screen("analysis", (TapStep("分析"),), "账本为空"),
)

# The seeded profile's screens: the P8-02 V-9 search leg (D7-10) leads, then the P8-01a i3b detail
# screen and the reconciliation queue. The search leg starts from the home tab through the search
# capsule (P8HomeFlow.kt: content-description 搜索账目, visible text 搜索账目、金额、备注 — the longer
# rendered text is why the tap is a substring match) and submits an EMPTY keyword (D7-6 allows it),
# which lands the seeded transactions in the default 近 90 天 window. The detail screen therefore
# backs out of the result face first; every other marker and absent string is unchanged since i3b.
#
# Fold margin: both search markers are the LAST LazyColumn slot on their screen, and the seeded
# catalog holds no tags and no merchants (SqlDelightCatalogStore seeds accounts and categories
# only). Adding a tag or a merchant to the seed grows the condition area and can push a marker
# below the fold, which surfaces as a 180 s wait_for timeout — loud, never a false green.
SEEDED_SCREENS: tuple[Screen, ...] = (
    Screen(
        "search-guide",
        (TapStep("搜索账目", contains=True),),
        SEARCH_GUIDE_MARKER,
        absent=(SEEDED_HOME_COUNT,),
    ),
    # D7-8: the submit button is tapped by its unique semantics name — its visible text 搜索 is also
    # the screen title, and `find_center` returns the first node in document order.
    Screen(
        "search-results",
        (TapStep("提交搜索"),),
        SEARCH_RESULTS_MARKER,
        absent=(SEARCH_GUIDE_MARKER, SEEDED_HOME_COUNT),
    ),
    Screen("detail", (BACK, TapStep(SEEDED_TRANSFER_NOTE, contains=True)), SEEDED_DETAIL_MARKER),
    Screen(
        "reconciliation",
        (
            BACK,
            TapStep("账户"),
            TapStep(SEEDED_ACCOUNT_NAME, contains=True),
            TapStep(RECONCILIATION_TITLE),
        ),
        SEEDED_QUEUE_MARKER,
        absent=(ACCOUNT_FLOW_HEADER,),
    ),
)

# P8-03 i2c (V-14) refund leg: the same seeded ledger, whose confirmed 12.34 manual expense is the
# selectable original. The entry screen's candidate row is the ONLY place 早餐 renders once the
# refund draft is active (a refund has no category selector), so the marker proves the candidate
# read landed rather than a leftover form. The confirmation leg fills the four refund fields the
# entry rendered (original, arrival account, amount, occurred-at — the draft starts with no
# occurred-at, and the Continue gate requires it), collapses the keyboard between text entries,
# scrolls the continue button into view and taps it. Its marker is the inherited category (only a
# resolved candidate renders it there) and its negative marker is the entry title, so a 继续 that
# never left the form, or a confirmation whose original never resolved, is not evidence.
REFUND_SCREENS: tuple[Screen, ...] = (
    Screen(
        "refund-entry",
        (TapStep("新增支出"), tap_radio_for("退款到账")),
        SEEDED_REFUND_CATEGORY,
        # 费用分类 is the expense form's own category selector: the seed category name appears there
        # too, so its absence is what proves the type radio actually switched to the refund form.
        absent=(SEEDED_HOME_COUNT, "费用分类"),
    ),
    Screen(
        "refund-confirmation",
        (
            tap_radio_for(SEEDED_REFUND_CATEGORY, contains=True),
            tap_radio_for(SEEDED_REFUND_ACCOUNT, contains=True),
            input_text(REFUND_AMOUNT_FIELD, REFUND_AMOUNT_TEXT),
            dismiss_ime(),
            scroll_until(REFUND_OCCURRED_AT_FIELD),
            input_text(REFUND_OCCURRED_AT_FIELD, REFUND_OCCURRED_AT_TEXT),
            dismiss_ime(),
            scroll_until("继续"),
            TapStep("继续"),
        ),
        SEEDED_REFUND_CATEGORY,
        absent=(REFUND_ENTRY_TITLE,),
    ),
)

@dataclass(frozen=True)
class P806SeedFacts:
    """The exact facts the `p806` profile's seed carries, derived from the seed database.

    Both the driver and the gate derive this from the pushed file, so the screen strings are a
    re-derivation of the evidence rather than a literal copied into host.json.
    """

    month_label: str
    day_date: str
    day_count: int
    currency: str
    precision: int
    net_expense_minor: int
    income_minor: int
    # The signed amounts a day-detail row renders (the negative payment legs), in transaction-id
    # order: exact minor-unit rendering plus the currency code, e.g. "-12.34 CNY".
    payment_texts: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, object]:
        return {
            "month_label": self.month_label,
            "day_date": self.day_date,
            "day_count": self.day_count,
            "currency": self.currency,
            "precision": self.precision,
            "net_expense_minor": self.net_expense_minor,
            "income_minor": self.income_minor,
            "payment_texts": list(self.payment_texts),
        }

    def net_expense_text(self) -> str:
        return f"{P806_REPORT_NET_LABEL} {minor_text(self.net_expense_minor, self.precision)} {self.currency}"

    def income_text(self) -> str:
        return f"{P806_REPORT_INCOME_LABEL} {minor_text(self.income_minor, self.precision)} {self.currency}"

    def report_period_text(self) -> str:
        """The product's resolved MONTH period line (`月：<start> 至 <end>（<n> 天）`).

        The start/end and day count are computed from the seed's own local day, so a report frame
        that landed on another month cannot carry this line.
        """
        year, month, _ = (int(part) for part in self.day_date.split("-"))
        first = date(year, month, 1)
        next_first = date(year + (month == 12), month % 12 + 1, 1)
        last = next_first - timedelta(days=1)
        return f"月：{first.isoformat()} 至 {last.isoformat()}（{(last - first).days + 1} 天）"

    def month_day_count(self) -> int:
        year, month, _ = (int(part) for part in self.day_date.split("-"))
        first = date(year, month, 1)
        next_first = date(year + (month == 12), month % 12 + 1, 1)
        return (next_first - first).days

    def balance_amount_text(self) -> str:
        """The exact signed 结余 value and currency (`-17.34 CNY`), as the report renders it."""
        return f"{minor_text(self.income_minor - self.net_expense_minor, self.precision)} {self.currency}"

    def balance_text(self) -> str:
        return f"{P806_REPORT_BALANCE_LABEL} {self.balance_amount_text()}"

    def daily_average_note_text(self) -> str:
        """The natural-day statement the supplemental scrolled frame must show in full."""
        return (
            f"日均按该期间完整自然日数（{self.month_day_count()} 天）计算："
            "进行中的期间不缩短分母。"
        )

    def day_expense_amount_text(self) -> str:
        """The calendar cell's exact expense amount with its currency (`CNY 支出 17.34`)."""
        return f"{self.currency} 支出 {minor_text(self.net_expense_minor, self.precision)}"

    def day_income_amount_text(self) -> str:
        """The calendar cell's exact income amount (`收入 0.00`; the currency precedes the pair)."""
        return f"收入 {minor_text(self.income_minor, self.precision)}"

    def day_cell_text(self) -> str:
        return (
            f"{self.day_date}，{self.day_expense_amount_text()}、"
            f"{self.day_income_amount_text()}；共 {self.day_count} 笔"
        )


def minor_text(minor_units: int, precision: int) -> str:
    """The product's fixed two-place rendering (`formatMinorUnits`) for a non-negative magnitude."""
    if precision <= 0:
        return str(minor_units)
    digits = str(abs(minor_units)).rjust(precision + 1, "0")
    return ("-" if minor_units < 0 else "") + digits[:-precision] + "." + digits[-precision:]


# The Asia/Shanghai offset: the reporting zone has had no DST since 1991, and the seed writer and
# the product both use `Asia/Shanghai`, so a fixed UTC+8 wall clock is exact for these instants.
P806_REPORT_OFFSET = timezone(timedelta(hours=8))


def derive_p806_seed_facts(seed: Path) -> P806SeedFacts:
    """Re-derive the `p806` sample's month, day, count and exact amounts from the seed file.

    Reads with a read-only connection; an unexpected shape is a loud `SampleError`, never a guess.
    """
    if not seed.is_file():
        raise SampleError(f"seed database {str(seed)!r} does not exist")
    uri = f"file:{seed.as_posix()}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
    except sqlite3.Error as error:
        raise SampleError(f"cannot open the seed database for derivation: {error}") from error
    try:
        ledgers = [row[0] for row in connection.execute("SELECT DISTINCT ledger_id FROM ledger_transaction")]
        if ledgers != [P806_LEDGER_ID]:
            raise SampleError(f"the p806 seed must hold exactly {P806_LEDGER_ID!r}, found {ledgers!r}")
        kinds = sorted(row[0] for row in connection.execute("SELECT DISTINCT kind FROM ledger_transaction"))
        if kinds != ["EXPENSE"]:
            raise SampleError(f"the p806 seed must hold only EXPENSE rows, found {kinds!r}")
        bounds = connection.execute(
            "SELECT MIN(statistics_at_epoch_nanos), MAX(statistics_at_epoch_nanos), COUNT(*) "
            "FROM transaction_version WHERE ledger_id = ?",
            (P806_LEDGER_ID,),
        ).fetchone()
        if bounds is None or bounds[2] != P806_SEED_TRANSACTION_COUNT:
            raise SampleError(f"the p806 seed must hold {P806_SEED_TRANSACTION_COUNT} versions, found {bounds!r}")
        earliest, latest, _ = bounds
        first = datetime.fromtimestamp(earliest // 1_000_000_000, tz=P806_REPORT_OFFSET)
        last = datetime.fromtimestamp(latest // 1_000_000_000, tz=P806_REPORT_OFFSET)
        if first.date() != last.date():
            raise SampleError(f"the p806 seed's rows must share one local day, found {first.date()} and {last.date()}")
        day = first.date()
        day_start = int(datetime(day.year, day.month, day.day, tzinfo=P806_REPORT_OFFSET).timestamp()) * 1_000_000_000
        next_day = day + timedelta(days=1)
        day_end = int(datetime(next_day.year, next_day.month, next_day.day, tzinfo=P806_REPORT_OFFSET).timestamp()) * 1_000_000_000
        count = connection.execute(
            "SELECT count(DISTINCT transaction_id) FROM transaction_version "
            "WHERE ledger_id = ? AND statistics_at_epoch_nanos >= ? AND statistics_at_epoch_nanos < ?",
            (P806_LEDGER_ID, day_start, day_end),
        ).fetchone()[0]
        if count != P806_SEED_TRANSACTION_COUNT:
            raise SampleError(f"the p806 seed's day must hold {P806_SEED_TRANSACTION_COUNT} transactions, found {count}")
        amounts = connection.execute(
            "SELECT p.currency_code, p.currency_precision, "
            "COALESCE(SUM(CASE WHEN p.amount_minor > 0 THEN p.amount_minor ELSE 0 END), 0), "
            "COALESCE(SUM(CASE WHEN p.amount_minor < 0 THEN -p.amount_minor ELSE 0 END), 0) "
            "FROM posting p "
            "JOIN transaction_version v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id "
            "JOIN ledger_transaction_current_version c "
            "ON c.transaction_id = v.transaction_id AND c.current_version_id = v.version_id "
            "WHERE p.ledger_id = ? AND p.account_id = ? "
            "AND v.statistics_at_epoch_nanos >= ? AND v.statistics_at_epoch_nanos < ? "
            "GROUP BY p.currency_code, p.currency_precision",
            (P806_LEDGER_ID, P806_DAY_ACCOUNT_ID, day_start, day_end),
        ).fetchall()
        if len(amounts) != 1:
            raise SampleError(f"the p806 seed's day must hold one currency, found {amounts!r}")
        currency, precision, positive, refund = amounts[0]
        payments = connection.execute(
            "SELECT p.amount_minor, p.currency_code, p.currency_precision "
            "FROM posting p "
            "JOIN transaction_version v ON v.posting_set_id = p.posting_set_id AND v.ledger_id = p.ledger_id "
            "JOIN ledger_transaction_current_version c "
            "ON c.transaction_id = v.transaction_id AND c.current_version_id = v.version_id "
            "WHERE p.ledger_id = ? AND p.account_id = ? AND p.amount_minor < 0 "
            "AND v.statistics_at_epoch_nanos >= ? AND v.statistics_at_epoch_nanos < ? "
            "ORDER BY v.transaction_id",
            (P806_LEDGER_ID, P806_PAYMENT_ACCOUNT_ID, day_start, day_end),
        ).fetchall()
        if len(payments) != P806_SEED_TRANSACTION_COUNT:
            raise SampleError(
                f"the p806 seed's day must hold {P806_SEED_TRANSACTION_COUNT} signed payment rows, "
                f"found {payments!r}"
            )
        payment_texts = tuple(
            f"{minor_text(amount_minor, row_precision)} {row_currency}"
            for amount_minor, row_currency, row_precision in payments
        )
        return P806SeedFacts(
            month_label=f"{day.year}年{day.month}月",
            day_date=day.isoformat(),
            day_count=count,
            currency=currency,
            precision=precision,
            net_expense_minor=positive - refund,
            # The seed holds only EXPENSE rows (checked above), so ordinary income is exactly zero.
            income_minor=0,
            payment_texts=payment_texts,
        )
    finally:
        connection.close()


def p806_rollover_problem(facts: P806SeedFacts, now: datetime | None = None) -> str | None:
    """A refusal when the seed's month is no longer the reporting zone's current month.

    The report region opens on the CURRENT month, so a seed prepared before a Shanghai month
    rollover would photograph an empty current month while its marker strings describe the
    previous one. That is an explicit failure with the fix (regenerate and repush the seed), not a
    capture that is quietly read as evidence for the wrong month.
    """
    current = (now or datetime.now(P806_REPORT_OFFSET)).astimezone(P806_REPORT_OFFSET)
    current_label = f"{current.year:04d}-{current.month:02d}"
    seed_label = facts.day_date[:7]
    if current_label == seed_label:
        return None
    return (
        f"the p806 seed's month {seed_label} is not the current reporting month {current_label}; "
        "regenerate the seed before dispatching this profile"
    )


def p806_screens(facts: P806SeedFacts) -> tuple[Screen, ...]:
    """The `p806` profile's V-12 screens, parameterized by the facts re-derived from the seed.

    Navigation is the production one: the ANALYSIS tab renders the report region; the HOME month
    row's 收支日历 button opens the calendar; tapping the seeded day's own cell opens the shared
    day drilldown. Each screen's `required` tuple is the exact signed amount and currency of the
    seed, so a zero, a failure face or a different day cannot pass.

    The second frame is the report region scrolled to the natural-day statement: at 1.3x the
    statement sits below the first frame's fold, and V-12 needs it readable, not merely present in
    the tree. The speech tuple follows the same rule — the report speaks its signed 结余 and
    currency, the calendar its own day and currency, and the drilldown BOTH signed payment rows.
    """
    report_required = (
        facts.report_period_text(),
        facts.net_expense_text(),
        facts.income_text(),
        f"{P806_REPORT_COUNT_LABEL}{facts.day_count}",
    )
    day_required = (facts.day_cell_text(),)
    note_text = facts.daily_average_note_text()
    drilldown_count_text = f"共 {facts.day_count} 笔明细"
    drilldown_required = (
        P806_DRILLDOWN_TITLE,
        f"{facts.day_date} 明细",
        drilldown_count_text,
        *facts.payment_texts,
    )
    return (
        Screen(
            "report",
            # The analysis page stacks the budget region above the report region, so the capture
            # scrolls the exact net-expense line into view before the frame is recorded (a scroll
            # that stopped at the section title could photograph an empty fold and still pass a
            # tree-only check).
            (TapStep("分析"), ScrollStep(facts.net_expense_text())),
            P806_REPORT_TITLE,
            absent=("账本为空", P806_REPORT_LAST_SUCCESS_CAPTION),
            required=report_required,
            # The report page also renders the HOME month card, whose line ends `结余 <value>` with
            # no trailing currency. Targeting the report cell's full text (`结余 <signed> <code>`)
            # lets the gate's found-node binding accept only that cell, never the same-named card.
            speech=(SpeechCheck(facts.balance_text(), (facts.balance_amount_text(),)),),
        ),
        Screen(
            "report-daily-average",
            (ScrollStep(note_text, fully=True),),
            note_text,
            required=(note_text,),
            marker_in_viewport=True,
        ),
        Screen(
            "calendar",
            # The day cell may sit in a lower week row at 1.3x; scroll it onto the screen so the
            # screenshot shows the seeded cell, not just a tree node.
            (TapStep("首页"), TapStep(P806_CALENDAR_TITLE), ScrollStep(facts.day_cell_text())),
            P806_CALENDAR_TITLE,
            absent=(P806_REPORT_TITLE, SEEDED_HOME_COUNT),
            required=day_required,
            # The date alone would accept a cell whose amounts are wrong or missing; the same
            # seed-derived expense/income pair the cell renders (with its currency) is required in
            # this cell's own accepted focus window.
            speech=(
                SpeechCheck(
                    facts.day_date + "，",
                    (
                        facts.day_date,
                        facts.day_expense_amount_text(),
                        facts.day_income_amount_text(),
                    ),
                ),
            ),
        ),
        Screen(
            "day-detail",
            # The scroll precedes the tap because the calendar's speech pass may have moved the
            # grid; the tap must address the cell's current reachable centre.
            (ScrollStep(facts.day_cell_text()), TapStep(facts.day_cell_text())),
            # The loaded count, not the screen title: 报表明细 renders before the first page and
            # count land, so waiting on the count proves the rows the capture is for are present.
            drilldown_count_text,
            absent=(P806_CALENDAR_TITLE,),
            required=drilldown_required,
            # One ordered check per payment row: each row's amount must be spoken inside its OWN
            # command's accepted, completed window, never borrowed from the other row.
            speech=tuple(SpeechCheck(text, (text,)) for text in facts.payment_texts),
        ),
    )


P806_SCREENS: tuple[Screen, ...] = p806_screens(
    # A well-formed placeholder month, so the derived period line is computable at import time; run
    # and gate replace these with the screens built from the pushed seed.
    P806SeedFacts("0001年1月", "0001-01-01", 0, "CNY", 2, 0, 0)
)

PROFILES: dict[str, Profile] = {
    "empty": Profile("empty", "账本为空", EMPTY_SCREENS),
    "seeded": Profile("seeded", SEEDED_HOME_COUNT, SEEDED_SCREENS, seeded=True),
    "refund": Profile("refund", SEEDED_HOME_COUNT, REFUND_SCREENS, seeded=True),
    # The screens are placeholders here: run/gate replace them with `p806_screens` built from the
    # pushed seed, so a static literal can never drift out of the evidence.
    "p806": Profile("p806", SEEDED_HOME_COUNT, P806_SCREENS, seeded=True),
}

DYNAMIC_PROFILES = frozenset({"p806"})


def profile_screens(profile: Profile, seed: str) -> tuple[Screen, ...]:
    """The profile's screens; dynamic profiles build them from the seed they will push."""
    if profile.name not in DYNAMIC_PROFILES:
        return profile.screens
    if not seed:
        raise SampleError(f"profile {profile.name!r} needs --seed <path to the prepared ledger database>")
    return p806_screens(derive_p806_seed_facts(Path(seed)))


def profile_by_name(name: str) -> Profile:
    """The named profile; an unknown name is a loud failure, never a default."""
    profile = PROFILES.get(name)
    if profile is None:
        raise SampleError(f"unknown profile {name!r}; expected one of {sorted(PROFILES)}")
    return profile


def gate_profile_problems(host: dict[str, object], profile_name: str) -> list[str]:
    """The gate's profile consistency check, re-derived from host.json alone.

    The driver and the gate must select the SAME profile set: a run recorded under one profile
    cannot be gated as another, and the recorded screen list must be exactly the profile's stems
    (an artifact set that silently lost or gained a screen is not evidence for this profile).
    """
    profile = profile_by_name(profile_name)
    problems: list[str] = []
    recorded = host.get("profile")
    if recorded != profile_name:
        problems.append(f"host.json records profile {recorded!r}, expected {profile_name!r}")
    expected = [screen.stem for screen in profile.screens]
    recorded_screens = host.get("screens")
    if recorded_screens != expected:
        problems.append(
            f"host.json records screens {recorded_screens!r}, the {profile_name!r} profile captures {expected!r}"
        )
    return problems


@dataclass
class Sampler:
    """Owns every device interaction; the workflow must not duplicate any of it."""

    # /data/local/tmp is the one path the shell user always owns; scoped storage made /sdcard
    # unreliable for `uiautomator dump` on API 36.
    DUMP_DIR: ClassVar[str] = "/data/local/tmp/ul-fontscale"

    adb_serial: str | None
    evidence: Path
    font_scale: float
    avd_name: str
    screenshots: list[str] = field(default_factory=list)
    dumps: list[str] = field(default_factory=list)
    # Monotonic id for the probe's host-command channel: every focus command needs a fresh value,
    # because the probe executes only a value it has not already seen.
    talkback_sequence: int = 0
    # The image's own TalkBackService component (empty when the image does not ship one).
    talkback_component: str = ""
    # The image's exported preferences activity, discovered from its package dump.
    talkback_prefs_activity: str = P806_TALKBACK_PREFS_ACTIVITY
    # P806 only: dismiss the observed Accessibility Suite notification prompt when it appears.
    notification_dialog_guard: bool = False

    def adb(self, *args: str, timeout: int = DEFAULT_TIMEOUT, best_effort: bool = False) -> str:
        command = ["adb"]
        if self.adb_serial:
            command += ["-s", self.adb_serial]
        command += list(args)
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=timeout, check=False
        )
        if completed.returncode != 0 and not best_effort:
            raise SampleError(
                f"{' '.join(command)} failed ({completed.returncode}): "
                f"{(completed.stderr or completed.stdout).strip()[:400]}"
            )
        return completed.stdout

    def adb_bytes(self, *args: str, timeout: int = DEFAULT_TIMEOUT) -> bytes:
        """The binary adb surface (`exec-out`), used for the seed read-back digest."""
        command = ["adb"]
        if self.adb_serial:
            command += ["-s", self.adb_serial]
        command += list(args)
        completed = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
        if completed.returncode != 0:
            raise SampleError(
                f"{' '.join(command)} failed ({completed.returncode}): "
                f"{completed.stderr.decode(errors='replace').strip()[:400]}"
            )
        return completed.stdout

    def require_owned_avd(self) -> None:
        """Refuse to touch any emulator this run did not boot itself."""
        name = self.adb("emu", "avd", "name").strip().splitlines()
        if not name or name[0] != self.avd_name:
            raise SampleError(
                f"device answers emu avd name {name!r}; this rig only drives {self.avd_name!r}"
            )

    def logcat_tail(self, line_count: int = 4000) -> str:
        """The recent logcat in `brief` form; the probe and TalkBack lines both fit that shape."""
        return self.adb(
            "shell", "logcat", "-d", "-v", "brief", "-t", str(line_count), best_effort=True
        )

    def accessibility_dumpsys(self) -> str:
        return self.adb("shell", "dumpsys", "accessibility", best_effort=True)

    def find_talkback_component(self) -> str:
        """The image's own TalkBack accessibility service component, read from the package dump.

        The class name is not guessed: the dump must mention the service, and a package that does
        not ship it yields an empty answer (the caller records 未取得 instead of enabling a
        non-existent component).
        """
        dump = self.adb("shell", "dumpsys", "package", P806_TALKBACK_PACKAGE, best_effort=True)
        full = f"{P806_TALKBACK_PACKAGE}.TalkBackService"
        if full in dump:
            return f"{P806_TALKBACK_PACKAGE}/{full}"
        if f"{P806_TALKBACK_PACKAGE}/.TalkBackService" in dump:
            return f"{P806_TALKBACK_PACKAGE}/.TalkBackService"
        return ""

    def find_talkback_prefs_activity(self) -> str:
        """The image's exported preferences activity, read from the same package dump if named."""
        dump = self.adb("shell", "dumpsys", "package", P806_TALKBACK_PACKAGE, best_effort=True)
        for match in re.finditer(r"([A-Za-z0-9_.]+\.TalkBackPreferencesActivity)", dump):
            return f"{P806_TALKBACK_PACKAGE}/{match.group(1)}"
        return P806_TALKBACK_PREFS_ACTIVITY

    def prepare_talkback_device(self) -> dict[str, str]:
        """Put the run-owned AVD into a known accessibility state and return the prior one.

        The emulator belongs to this run, and a crashed previous dispatch can leave either service
        enabled; starting from "no accessibility services" keeps the screenshot navigation on the
        ordinary touch semantics it was written for.
        """
        prior_services = self.adb(
            "shell", "settings", "get", "secure", "enabled_accessibility_services", best_effort=True
        ).strip()
        prior_enabled = self.adb(
            "shell", "settings", "get", "secure", "accessibility_enabled", best_effort=True
        ).strip()
        for key in (
            SETTING_A11Y_COMMAND,
            SETTING_A11Y_PREFIX,
            SETTING_A11Y_LIMIT,
            SETTING_A11Y_CLICK,
        ):
            self.adb("shell", "settings", "delete", "global", key, best_effort=True)
        self.disable_talkback_accessibility()
        return {
            "enabled_accessibility_services": prior_services,
            "accessibility_enabled": prior_enabled,
        }

    def disable_talkback_accessibility(self) -> None:
        self.adb(
            "shell",
            "settings",
            "delete",
            "secure",
            "enabled_accessibility_services",
            best_effort=True,
        )
        self.adb(
            "shell",
            "settings",
            "put",
            "secure",
            "accessibility_enabled",
            "0",
            best_effort=True,
        )
        time.sleep(2)

    def _probe_connected(self, log_text: str) -> bool:
        return probe_service_connected(log_text)

    def _bound_accessibility_section(self, dump: str) -> str:
        start = dump.find("Bound services:")
        if start < 0:
            return ""
        end = dump.find("Enabled services:", start)
        return dump[start : end if end > start else start + 6000]

    def wait_probe_connected(self, timeout_s: int = 90) -> bool:
        """Wait for the probe's own `service connected` acknowledgement.

        Only that exact line decides readiness. An enabled-services entry is not proof of binding,
        and API 36 renders bound services as `Service[label=…]` without a guaranteed component, so
        the dumpsys is read alongside as raw context rather than accepted as connection. On a
        not-obtained verdict the last logcat tail and dumpsys are retained for diagnosis.
        """
        deadline = time.monotonic() + timeout_s
        last_log = ""
        last_dump = ""
        while time.monotonic() < deadline:
            last_log = self.logcat_tail()
            if self._probe_connected(last_log):
                return True
            last_dump = self.accessibility_dumpsys()
            time.sleep(1)
        (self.evidence / "readiness-logcat.txt").write_text(last_log + "\n", encoding="utf-8")
        (self.evidence / "readiness-accessibility.txt").write_text(
            last_dump + "\n", encoding="utf-8"
        )
        return False

    def wait_accessibility_disabled(self, timeout_s: int = 30) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            bound = self._bound_accessibility_section(self.accessibility_dumpsys())
            if not bound:
                time.sleep(1)
                continue
            if P806_PROBE_PACKAGE not in bound and P806_TALKBACK_PACKAGE not in bound:
                return True
            time.sleep(1)
        return False

    def enable_talkback(self, stem: str) -> dict[str, object]:
        """Enable probe plus TalkBack for one screen and keep ITS read-back raw record.

        The secure services value is written to `talkback-enabled-<stem>.txt` (never one shared
        file): the gate reads that screen's own device observation back and re-derives which
        TalkBack component was enabled while its fragments were captured.
        """
        enabled_file = f"talkback-enabled-{stem}.txt"
        if not self.talkback_component:
            return {
                "status": "not-obtained",
                "enabled_services_file": enabled_file,
                "reason": "the image exposes no TalkBackService component to enable",
            }
        # The automatic probe passes must never move focus inside a commanded speech window.
        self.adb("shell", "settings", "put", "global", SETTING_A11Y_PREFIX, P806_TALKBACK_AUTO_SENTINEL)
        self.adb("shell", "settings", "put", "global", SETTING_A11Y_LIMIT, "0")
        self.adb("shell", "settings", "delete", "global", SETTING_A11Y_CLICK, best_effort=True)
        # A stale command would run on connect; the host sets the first real one after "service
        # connected", and every later command carries a fresh id.
        self.adb("shell", "settings", "delete", "global", SETTING_A11Y_COMMAND, best_effort=True)
        # A stale buffer could hold an earlier command id; a failed clear would make a collision
        # possible, so it is a hard precondition rather than a best-effort convenience.
        self.adb("shell", "logcat", "-c")
        self.adb(
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            f"{P806_PROBE_COMPONENT}:{self.talkback_component}",
        )
        self.adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        # The put command reports only its own exit; the value Android actually holds is the
        # evidence that the exact TalkBack component-the one whose fragments this run will
        # accept-is enabled while sampling. A substituted or truncated component would otherwise
        # leave speech attributed to a service the gate never verified.
        enabled_raw = self.adb(
            "shell",
            "settings",
            "get",
            "secure",
            "enabled_accessibility_services",
            best_effort=True,
        ).strip()
        enabled_components = [
            part.strip() for part in enabled_raw.split(":") if part.strip() and part.strip() != "null"
        ]
        (self.evidence / enabled_file).write_text(enabled_raw + "\n", encoding="utf-8")
        if self.talkback_component not in enabled_components:
            return {
                "status": "not-obtained",
                "enabled_components": enabled_components,
                "enabled_services_file": enabled_file,
                "reason": (
                    "enabled_accessibility_services does not list the exact TalkBack component "
                    f"{self.talkback_component}"
                ),
            }
        if not self.wait_probe_connected():
            return {
                "status": "not-obtained",
                "enabled_components": enabled_components,
                "enabled_services_file": enabled_file,
                "reason": "the focus probe never logged 'service connected'; no focus can be driven",
            }
        # Binding is not required for the probe to answer, but an unbound TalkBack service cannot
        # speak; record it as context and let the fragment verdict report the consequence.
        deadline = time.monotonic() + 45
        bound = False
        dump = ""
        while time.monotonic() < deadline:
            # Enabling TalkBack starts the service that raises its notification prompt. Dismiss it
            # in these first seconds, before any focus command exists, or the probe's active window
            # is the dialog and the command reports found=false.
            self.handle_notification_permission_dialog()
            dump = self.accessibility_dumpsys()
            if talkback_bound_service(self._bound_accessibility_section(dump)):
                bound = True
                break
            time.sleep(1)
        (self.evidence / "talkback-accessibility.txt").write_text(
            self._bound_accessibility_section(dump) + "\n", encoding="utf-8"
        )
        return {
            "status": "success",
            "enabled_components": enabled_components,
            "enabled_services_file": enabled_file,
            "talkback_bound": bound,
            "reason": "" if bound else "TalkBack was enabled but is not listed as a bound service",
        }

    def talkback_log_level_setup(self) -> dict[str, object]:
        """Select TalkBack's own Log output level VERBOSE through its exported settings UI.

        The persisted preference is application-private, so it is set the way the product exposes
        it: open the developer fragment by its verified intent extra, tap the row whose SUMMARY is
        one of the literal level names, choose VERBOSE from the list (literal entries), and confirm
        the framework dialog's positive button. The final developer-screen dump proves the summary
        now renders VERBOSE; no private file is copied and no preference key is typed.
        """
        record: dict[str, object] = {
            "status": "not-obtained",
            "value": "",
            "reason": "",
            "steps": [],
        }
        steps: list[str] = record["steps"]  # type: ignore[assignment]
        started = False
        try:
            self.adb(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                self.talkback_prefs_activity,
                "--es",
                "FragmentName",
                P806_TALKBACK_DEVELOPER_FRAGMENT,
                timeout=120,
            )
            started = True
            steps.append("open the exported developer preferences fragment")
            tree = ""
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                tree = self.dump("talkback-prefs-before")
                if find_talkback_level_summary(tree) is not None:
                    break
                time.sleep(3)
            else:
                # The developer list is long and the row may be below the 1.3x fold; scroll the
                # preference list (not the app) until a level-name summary is composed.
                for _attempt in range(6):
                    x1, y1, x2, y2 = scroll_swipe_points(tree)
                    self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
                    time.sleep(2)
                    tree = self.dump("talkback-prefs-before")
                    if find_talkback_level_summary(tree) is not None:
                        break
                else:
                    raise SampleError(
                        "the developer preferences screen exposed no Log output level summary"
                    )
            current = talkback_level_summary_value(tree)
            steps.append(f"rendered Log output level summary is {current!r}")
            if current == P806_TALKBACK_LEVEL:
                record.update(status="success", value=P806_TALKBACK_LEVEL, reason="already selected")
                return record
            center = find_talkback_level_summary(tree)
            if center is None:
                raise SampleError("the Log output level row has no tappable centre")
            self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
            steps.append("open the level list")
            time.sleep(2)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                tree = self.dump("talkback-prefs-dialog")
                if find_center(tree, P806_TALKBACK_LEVEL) is not None:
                    break
                time.sleep(2)
            else:
                # At 1.3x the last entry can sit below the dialog's fold; the single-choice list
                # itself scrolls, so swipe inside it before giving up.
                for _attempt in range(4):
                    x1, y1, x2, y2 = scroll_swipe_points(tree)
                    self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
                    time.sleep(2)
                    tree = self.dump("talkback-prefs-dialog")
                    if find_center(tree, P806_TALKBACK_LEVEL) is not None:
                        break
                else:
                    raise SampleError("the level list never rendered a VERBOSE entry")
            center = find_center(tree, P806_TALKBACK_LEVEL)
            if center is None:
                raise SampleError("the VERBOSE entry has no tappable centre")
            self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
            steps.append("select the literal VERBOSE entry")
            time.sleep(2)
            # The list change raises a confirmation dialog; its positive button is the framework's
            # `android:id/button1`, and the changed summary is the proof it persisted.
            deadline = time.monotonic() + 20
            confirmed = False
            while time.monotonic() < deadline:
                tree = self.dump("talkback-prefs-confirm")
                button = find_resource_center(tree, "android:id/button1")
                if button is not None:
                    self.adb("shell", "input", "tap", str(button[0]), str(button[1]))
                    confirmed = True
                    steps.append("confirm the framework dialog's positive button")
                    break
                if talkback_level_summary_value(tree) == P806_TALKBACK_LEVEL:
                    confirmed = True
                    steps.append("the dialog closed with the level already applied")
                    break
                time.sleep(1)
            if not confirmed:
                raise SampleError("the level change showed no confirmation dialog and no new summary")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                tree = self.dump("talkback-prefs-after")
                if talkback_level_summary_value(tree) == P806_TALKBACK_LEVEL:
                    record.update(
                        status="success", value=P806_TALKBACK_LEVEL, reason="summary shows VERBOSE"
                    )
                    return record
                time.sleep(1)
            raise SampleError("the developer screen's summary never rendered VERBOSE")
        except SampleError as error:
            record["reason"] = str(error)
            return record
        finally:
            if started:
                # Let the dialog's persisted preference reach disk, leave the settings activity,
                # and drop the cached process so the accessibility service starts fresh and reads
                # the level from preferences. `am kill` (not force-stop) keeps the package out of
                # the stopped state the accessibility manager refuses to bind from.
                time.sleep(3)
                self.adb("shell", "input", "keyevent", "3", best_effort=True)
                time.sleep(2)
                self.adb("shell", "am", "kill", P806_TALKBACK_PACKAGE, best_effort=True)
                time.sleep(1)

    def p806_talkback_capability(self, log_level: dict[str, object]) -> dict[str, object]:
        """Record the image's TalkBack capability and the route this run actually drives."""
        flavor = self.adb("shell", "getprop", "ro.build.flavor", best_effort=True).strip()
        product = self.adb("shell", "getprop", "ro.product.name", best_effort=True).strip()
        packages = self.adb("shell", "pm", "list", "packages", P806_TALKBACK_PACKAGE, best_effort=True)
        enabled = self.adb(
            "shell", "settings", "get", "secure", "enabled_accessibility_services", best_effort=True
        ).strip()
        accessibility_enabled = self.adb(
            "shell", "settings", "get", "secure", "accessibility_enabled", best_effort=True
        ).strip()
        package_present = P806_TALKBACK_PACKAGE in packages
        capability: dict[str, object] = {
            "status": "package-present" if package_present else "package-absent-on-image",
            "package_present": package_present,
            "device_service": self.talkback_component,
            "prefs_activity": self.talkback_prefs_activity,
            "package_listing": packages.strip(),
            "enabled_accessibility_services_at_probe": enabled,
            "accessibility_enabled_at_probe": accessibility_enabled,
            "build_flavor": flavor,
            "product_name": product,
            "log_signal": P806_TALKBACK_LOG_SIGNAL,
            "log_level": log_level,
            "probe_component": P806_PROBE_COMPONENT,
        }
        lines = [f"{key}: {value}" for key, value in capability.items()]
        (self.evidence / "talkback-capability.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return capability

    def talkback_focus(self, needle: str, command_id: int) -> dict[str, object]:
        """Ask the probe to focus the node containing `needle`; return ONLY that command's window."""
        encoded = base64.b64encode(needle.encode("utf-8")).decode("ascii")
        self.adb(
            "shell",
            "settings",
            "put",
            "global",
            SETTING_A11Y_COMMAND,
            f"focus,{command_id},{encoded}",
        )
        deadline = time.monotonic() + 75
        log_text = ""
        while time.monotonic() < deadline:
            log_text = self.logcat_tail()
            if (
                f"speech-window-end id={command_id}" in log_text
                or f"target id={command_id} found=false" in log_text
            ):
                break
            time.sleep(1)
        window, problem = isolate_talkback_window(log_text, command_id)
        token_sets = [tokens for tokens in map(_ula11y_tokens, window) if tokens]
        return {
            "id": command_id,
            "target": needle,
            "completed": any(
                "speech-window-end" in tokens and f"id={command_id}" in tokens
                for tokens in token_sets
            ),
            "accepted": _window_focus_accepted(window, command_id),
            "problem": problem,
            "lines": window,
        }

    def p806_talkback_screen(self, screen: Screen) -> dict[str, object]:
        """Drive one screen's ordered speech checks, each bound to its own command window."""
        artifact_name = f"talkback-{screen.stem}.txt"
        lines: list[str] = [f"# stem: {screen.stem}"]
        enabled: dict[str, object] = {
            "status": "not-obtained",
            "reason": "",
            "enabled_components": [],
            "enabled_services_file": f"talkback-enabled-{screen.stem}.txt",
        }
        driven: list[tuple[SpeechCheck, dict[str, object] | None]] = []
        try:
            enabled = self.enable_talkback(screen.stem)
            lines.append(
                f"# enable: {enabled.get('status')} components={enabled.get('enabled_components')} "
                f"bound={enabled.get('talkback_bound')} {enabled.get('reason', '')}".rstrip()
            )
            if enabled.get("status") != "success":
                raise SampleError(str(enabled.get("reason") or "TalkBack could not be enabled"))
            # The prompt can arrive after the bind wait (TalkBack asks once its process is up), so
            # the last clear sits between enabling the service and the first focus command.
            self.handle_notification_permission_dialog()
            for check in screen.speech:
                self.talkback_sequence += 1
                command_id = self.talkback_sequence
                window = self.talkback_focus(check.target, command_id)
                driven.append((check, window))
                lines.append(f"# target: {check.target}")
                lines.append(f"# command: {command_id}")
                lines.extend(str(line) for line in window["lines"])
        except SampleError as error:
            enabled = {
                "status": "not-obtained",
                "reason": str(error),
                "enabled_components": list(enabled.get("enabled_components") or []),
                "enabled_services_file": enabled.get(
                    "enabled_services_file", f"talkback-enabled-{screen.stem}.txt"
                ),
                "talkback_bound": bool(enabled.get("talkback_bound")),
            }
            lines.append(f"# enable failed: {error}")
        finally:
            self.disable_talkback_accessibility()
            self.wait_accessibility_disabled()
        for check in screen.speech[len(driven) :]:
            driven.append((check, None))
        artifact = "\n".join(lines) + "\n"
        (self.evidence / artifact_name).write_text(artifact, encoding="utf-8")
        checks = []
        for check, window in driven:
            if window is None:
                status, fragments, missing, reason = (
                    "not-obtained",
                    [],
                    list(check.required),
                    str(enabled.get("reason") or "the check produced no command window"),
                )
            else:
                status, fragments, missing, reason = talkback_check_verdict(
                    artifact, int(window["id"]), check.target, check.required
                )
            checks.append(
                {
                    "target": check.target,
                    "command_id": int(window["id"]) if window is not None else None,
                    "required": list(check.required),
                    "status": status,
                    "label": talkback_status_label(status),
                    "completed": bool(window["completed"]) if window is not None else False,
                    "accepted": bool(window["accepted"]) if window is not None else False,
                    "fragments": fragments,
                    "missing": missing,
                    "reason": reason,
                }
            )
        status = overall_speech_status([check["status"] for check in checks])
        return {
            "status": status,
            "label": talkback_status_label(status),
            "device_service": self.talkback_component,
            "checks": checks,
            "artifact": artifact_name,
            "enable": enabled,
        }

    def apply_font_scale(self) -> float:
        self.adb("shell", "settings", "put", "system", "font_scale", f"{self.font_scale:g}")
        # The framework re-reads the scale on the next configuration change, so restart the app
        # instead of trusting the setting alone.
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        time.sleep(2)
        observed = parse_font_scale(self.adb("shell", "settings", "get", "system", "font_scale"))
        if abs(observed - self.font_scale) > 0.001:
            raise SampleError(f"font_scale did not take effect: asked {self.font_scale}, read {observed}")
        return observed

    def seed(self, seed_path: str) -> str:
        """Replace the app's ledger with the prepared database (the seeded profile only).

        The stable-storage resolution makes `databases/ledger.db` authoritative ONLY while no
        `ledger-generations/` directory exists, so the whole generation set is removed before the
        push; the app then runs its own non-destructive legacy upgrade on the next start (copy ->
        fsync -> open -> publish pointer -> remove legacy). The pushed bytes are read back through
        `run-as cat` and hashed, so a truncated push can never be sampled as evidence. Returns the
        digest of the bytes this run pushed (host.json records it; the gate compares it).
        """
        source = Path(seed_path)
        if not source.is_file():
            raise SampleError(f"seed database {seed_path!r} does not exist")
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        self.adb("shell", "run-as", APP_PACKAGE, "mkdir", "-p", APP_DATABASES)
        for path in APP_STORAGE_PATHS:
            self.adb("shell", "run-as", APP_PACKAGE, "rm", "-rf", path)
        self.adb("shell", "mkdir", "-p", REMOTE_SEED_DIR)
        self.adb("push", str(source), REMOTE_SEED, timeout=180)
        self.adb("shell", "run-as", APP_PACKAGE, "cp", REMOTE_SEED, APP_LEGACY_DATABASE)
        expected = sha256_file(source)
        observed = hashlib.sha256(
            self.adb_bytes("exec-out", "run-as", APP_PACKAGE, "cat", APP_LEGACY_DATABASE)
        ).hexdigest()
        if observed != expected:
            raise SampleError(
                f"the pushed seed did not read back byte-for-byte (host {expected}, device {observed})"
            )
        return expected

    def launch(self) -> None:
        self.adb("shell", "am", "start", "-W", "-n", MAIN_ACTIVITY, timeout=120)

    def wait_for(self, needle: str, timeout_s: int = 180) -> str:
        """Poll the accessibility tree until `needle` appears.

        A cold cloud AVD spends several seconds on the startup screen (正在打开本地账本…), so a
        fixed sleep would capture the loading frame and call it a sample. The last dump stays on
        disk as `wait.xml` when the wait fails, so a red run still shows what the device held.
        """
        deadline = time.monotonic() + timeout_s
        last = ""
        while time.monotonic() < deadline:
            last = self.dump("wait")
            if dump_contains(last, needle):
                return last
            time.sleep(3)
        raise SampleError(f"timed out after {timeout_s}s waiting for {needle!r}; see wait.xml")

    def wait_for_change(self, previous: str, timeout_s: int = 60) -> str:
        """Poll until the tree differs from `previous` — proof that the step landed.

        首页 and 分析 both render 账本为空, so the copy alone cannot prove the tab changed; the
        frame that follows the tap has to be a different frame.
        """
        deadline = time.monotonic() + timeout_s
        last = previous
        while time.monotonic() < deadline:
            last = self.dump("wait")
            if last != previous:
                return last
            time.sleep(2)
        raise SampleError(f"the screen did not change within {timeout_s}s after the step; see wait.xml")

    def screenshot(self, stem: str) -> Path:
        target = self.evidence / f"{stem}.png"
        remote = f"/sdcard/{stem}.png"
        self.adb("shell", "screencap", "-p", remote)
        self.adb("pull", remote, str(target))
        self.adb("shell", "rm", "-f", remote)
        if not target.exists() or target.stat().st_size < MIN_SCREENSHOT_BYTES:
            raise SampleError(f"screenshot {stem} is missing or blank ({target})")
        self.screenshots.append(target.name)
        return target

    def dump(self, stem: str) -> str:
        remote = f"{self.DUMP_DIR}/{stem}.xml"
        # `uiautomator dump` answers exit 0 while writing nothing when the window will not settle
        # ("could not get idle state"), so the file's presence is the only proof it worked.
        for _attempt in range(4):
            dump_output = self.adb("shell", "uiautomator", "dump", remote, best_effort=True)
            # The probe is a plain `ls`: adb hands the arguments to the device shell and drops the
            # quoting, so a `sh -c "test -s … && echo ok"` probe answers for the wrong command.
            listing = self.adb("shell", "ls", "-l", remote, best_effort=True)
            if remote.rsplit("/", 1)[-1] in listing:
                (self.evidence / f"uiautomator-{stem}.txt").write_text(
                    dump_output, encoding="utf-8"
                )
                break
            time.sleep(2)
        else:
            raise SampleError(
                f"uiautomator produced no {stem}.xml after 4 attempts; last answer: {dump_output.strip()[:200]!r}"
            )
        self.adb("pull", remote, str(self.evidence / f"{stem}.xml"))
        self.dumps.append(f"{stem}.xml")
        return (self.evidence / f"{stem}.xml").read_text(encoding="utf-8")

    def handle_notification_permission_dialog(self) -> bool:
        """Deny the observed Accessibility Suite POST_NOTIFICATIONS prompt when it is present.

        On the P806 AVD, TalkBack raises the API 33+ notification prompt after it starts; left up
        it intercepts focus/scroll input. Only the exact permissioncontroller grant dialog whose
        message is the Accessibility Suite notification prompt is touched, and denying an
        unnecessary notification permission is the least-privilege action. TalkBack owns touch
        exploration by then (the run enables it), so the deny gesture and its readback are chosen
        from the services Android actually holds at this instant: a prompt that survives the
        bounded readbacks raises rather than silently continuing.
        """
        if not self.notification_dialog_guard:
            return False
        tree = self.dump("permission-dialog")
        if not notification_permission_dialog_identity(tree):
            return False
        center = find_resource_center(
            tree, f"{P806_PERMISSION_CONTROLLER_PACKAGE}:id/permission_deny_button"
        )
        if center is None:
            center = find_resource_center(
                tree,
                f"{P806_PERMISSION_CONTROLLER_PACKAGE}:id/permission_deny_and_dont_ask_again_button",
            )
        if center is None:
            raise SampleError(
                "the Accessibility Suite notification prompt has no deny button; "
                "see permission-dialog.xml"
            )
        # The enabled set is read now, not remembered: the services change inside this run
        # (prepare disables them, enable_talkback adds probe plus TalkBack before the prompt shows).
        enabled_services = self.adb(
            "shell", "settings", "get", "secure", "enabled_accessibility_services", best_effort=True
        )
        self.tap_permission_deny(center, enabled_services)
        self.wait_permission_dialog_closed()
        (self.evidence / "notification-dialog.txt").write_text(
            "dismissed the Android Accessibility Suite POST_NOTIFICATIONS prompt (deny)\n",
            encoding="utf-8",
        )
        return True

    def tap_permission_deny(self, center: tuple[int, int], enabled_services: str) -> None:
        """Press the deny button with the activation gesture the a11y state requires."""
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        for delay in permission_deny_tap_delays(enabled_services, self.talkback_component):
            time.sleep(delay)
            self.adb("shell", "input", "tap", str(center[0]), str(center[1]))

    def wait_permission_dialog_closed(self) -> str:
        """Read the prompt back until it is gone, bounded; the last frame stays on disk.

        A prompt still rendered after every readback is a hard failure: continuing would sample a
        dialog instead of the screen, and the two dumps show what the device actually held.
        """
        for delay in permission_dialog_readback_delays():
            time.sleep(delay)
            after = self.dump("permission-dialog-after")
            if not notification_permission_dialog_identity(after):
                return after
        raise SampleError(
            "the Accessibility Suite notification prompt did not close after "
            f"{PERMISSION_DIALOG_READBACK_ATTEMPTS} readbacks; "
            "see permission-dialog.xml and permission-dialog-after.xml"
        )

    def save_tree(self, stem: str, tree: str) -> None:
        """Write the frame the sample was taken from under the screen's own name.

        `wait_for` reuses one remote name, so without this the artifact would carry only
        `wait.xml` and the gate would have nothing per-screen to re-check.
        """
        (self.evidence / f"{stem}.xml").write_text(tree, encoding="utf-8")
        self.dumps.append(f"{stem}.xml")

    def tap_label(self, label: str, contains: bool = False) -> str:
        """Tap the first matching node; return the pre-tap frame for change detection."""
        tree = self.dump("tap-probe")
        center = find_center(tree, label, contains=contains)
        if center is None:
            match = "containing" if contains else "equal to"
            raise SampleError(f"tap target {label!r} ({match}) is not in the accessibility tree")
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        time.sleep(2)
        return tree

    def press_back(self) -> None:
        self.adb("shell", "input", "keyevent", "4")
        time.sleep(2)

    def tap_radio(self, label: str, contains: bool = False) -> str:
        """Tap an option row's radio; returns the pre-tap frame for change detection."""
        tree = self.dump("tap-probe")
        center = find_radio_center(tree, label, contains=contains)
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        return tree

    def type_into_field(self, field_label: str, text: str) -> str:
        """Focus the labelled field, type `text`, and wait until the typed value is in the tree."""
        escaped = adb_input_text(text)
        tree = self.dump("tap-probe")
        center = find_field_center(tree, field_label)
        self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
        # The proof the tap landed is either a changed frame or the field itself turning focused: a
        # label that already floated above the box may gain the caret without changing the tree.
        deadline = time.monotonic() + 30
        focused = False
        while time.monotonic() < deadline:
            fresh = self.dump("focus-probe")
            if fresh != tree or _focused_text_field(fresh):
                focused = True
                break
            time.sleep(0.5)
        if not focused:
            raise SampleError(f"tapping {field_label!r} never focused a text field")
        self.adb("shell", "input", "text", escaped)
        return self.wait_for(text, timeout_s=30)

    def hide_ime(self) -> str:
        """Collapse the keyboard only when it is shown, wait for the platform to agree, re-dump.

        A BACK sent while the keyboard is already down is a navigation step (it would leave the
        form), so `mInputShown` decides. The wait polls the platform's own answer; the closing
        animation is not a fixed sleep.
        """
        if ime_is_shown(self.adb("shell", "dumpsys", "input_method")):
            self.adb("shell", "input", "keyevent", "4")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if not ime_is_shown(self.adb("shell", "dumpsys", "input_method")):
                    break
                time.sleep(1)
            else:
                raise SampleError("the software keyboard stayed up after KEYCODE_BACK")
        return self.dump("after-ime")

    def scroll_to(self, label: str, max_swipes: int = 6, fully: bool = False) -> str:
        """Swipe the content region until `label` is on screen; each swipe must change the frame."""
        if not label:
            raise SampleError("an empty scroll label would match every screen")
        # A system permission prompt would swallow the swipes; clear the one observed P806 prompt
        # before the first frame is read. Other profiles never set the guard.
        self.handle_notification_permission_dialog()
        tree = self.dump("scroll-probe")

        def on_screen(frame: str) -> bool:
            if find_reachable_center(frame, label) is None:
                return False
            return not fully or not viewport_problems(frame, label)

        for _ in range(max_swipes):
            if on_screen(tree):
                return tree
            # A prompt raised between two swipes would swallow the next one and freeze the frame.
            self.handle_notification_permission_dialog()
            x1, y1, x2, y2 = scroll_swipe_points(tree)
            self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
            tree = self.wait_for_change(tree, timeout_s=30)
        if on_screen(tree):
            return tree
        raise SampleError(f"scroll target {label!r} is not on screen after {max_swipes} swipes")

    def apply_step(self, step: Step, previous: str) -> str:
        """Run one step and wait for the tree to change; returns the new frame."""
        if isinstance(step, TapStep):
            return self.wait_for_change(self.tap_label(step.target, contains=step.contains))
        if isinstance(step, BackStep):
            if not previous:
                raise SampleError("the back step has no frame to compare against")
            self.press_back()
            return self.wait_for_change(previous)
        if isinstance(step, RadioStep):
            return self.wait_for_change(self.tap_radio(step.label, contains=step.contains))
        if isinstance(step, InputTextStep):
            return self.type_into_field(step.field_label, step.text)
        if isinstance(step, DismissImeStep):
            return self.hide_ime()
        if isinstance(step, ScrollStep):
            return self.scroll_to(step.label, max_swipes=step.max_swipes, fully=step.fully)
        raise SampleError(f"unknown step {step!r}")


def require_cloud_runner() -> None:
    triple = (
        os.environ.get("GITHUB_ACTIONS"),
        os.environ.get("RUNNER_ENVIRONMENT"),
        os.environ.get("RUNNER_OS"),
    )
    if triple != ("true", "github-hosted", "Linux"):
        raise SampleError(
            "this rig only runs on a GitHub-hosted Linux runner "
            f"(GITHUB_ACTIONS/RUNNER_ENVIRONMENT/RUNNER_OS = {triple}); the development host's "
            "adb surface belongs to the user's own emulator"
        )


def run(args: argparse.Namespace) -> int:
    require_cloud_runner()
    profile = profile_by_name(args.profile)
    if profile.seeded and not args.seed:
        raise SampleError(f"profile {profile.name!r} needs --seed <path to the prepared ledger database>")
    facts = derive_p806_seed_facts(Path(args.seed)) if profile.name in DYNAMIC_PROFILES else None
    if facts is not None:
        rollover = p806_rollover_problem(facts)
        if rollover is not None:
            raise SampleError(rollover)
    screens = p806_screens(facts) if facts is not None else profile.screens
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    sampler = Sampler(
        adb_serial=args.serial or None,
        evidence=evidence,
        font_scale=args.font_scale,
        avd_name=args.avd_name,
    )
    sampler.require_owned_avd()
    # The notification prompt is an Android Accessibility Suite behavior; only the P806 dynamic
    # profile enables its handler, and only on this session's owned AVD.
    sampler.notification_dialog_guard = facts is not None

    def dismiss_notification_prompt(context: str) -> None:
        """Deny the Accessibility Suite prompt, naming what was under way when it fails."""
        try:
            sampler.handle_notification_permission_dialog()
        except SampleError as error:
            raise SampleError(f"{context}: clearing the notification prompt: {error}") from error

    sampler.adb("shell", "mkdir", "-p", Sampler.DUMP_DIR, best_effort=True)
    applied = sampler.apply_font_scale()
    sampler.adb("install", "-r", args.apk, timeout=180)
    talkback_capability: dict[str, object] | None = None
    talkback_log_level: dict[str, object] | None = None
    prior_accessibility: dict[str, str] | None = None
    if facts is not None:
        if not Path(args.test_apk).is_file():
            raise SampleError(
                f"the p806 profile drives the focus probe from {args.test_apk!r}, which is missing; "
                "build :android-app:assembleDebugAndroidTest before the emulator session"
            )
        sampler.adb("install", "-r", args.test_apk, timeout=180)
        sampler.talkback_component = sampler.find_talkback_component()
        sampler.talkback_prefs_activity = sampler.find_talkback_prefs_activity()
        # The level is the app-private VERBOSE threshold TalkBack's own log gate reads; it is set
        # before the service is ever enabled, and the route's evidence is its own settings screen.
        prior_accessibility = sampler.prepare_talkback_device()
        talkback_log_level = sampler.talkback_log_level_setup()
        talkback_capability = sampler.p806_talkback_capability(talkback_log_level)
    seed_sha256 = ""
    if profile.seeded:
        seed_sha256 = sampler.seed(args.seed)
    sampler.launch()
    dismiss_notification_prompt("the launch frame")
    # The launch frame is the change-detection baseline for the first tap, and its marker proves
    # the expected ledger state (empty, or the seeded transaction) before any step runs.
    previous = sampler.wait_for(profile.launch_marker)

    observations: dict[str, dict[str, object]] = {}
    talkback_screens: dict[str, dict[str, object]] = {}
    for screen in screens:
        for index, step in enumerate(screen.steps):
            dismiss_notification_prompt(
                f"screen {screen.stem!r} step {index} ({describe_step(step)})"
            )
            try:
                previous = sampler.apply_step(step, previous)
            except SampleError as error:
                raise SampleError(
                    f"screen {screen.stem!r} step {index} ({describe_step(step)}) while navigating "
                    f"to marker {screen.marker!r}: {error}"
                ) from error
        # Wait for the screen to be the one we are about to photograph, then photograph it.
        dismiss_notification_prompt(f"screen {screen.stem!r} marker {screen.marker!r}")
        try:
            tree = sampler.wait_for(screen.marker)
        except SampleError as error:
            raise SampleError(f"screen {screen.stem!r} marker {screen.marker!r}: {error}") from error
        previous = tree
        sampler.save_tree(screen.stem, tree)
        sampler.screenshot(screen.stem)
        observations[screen.stem] = {
            "steps": [describe_step(step) for step in screen.steps],
            "marker": screen.marker,
            "marker_present": dump_contains(tree, screen.marker),
            "absent": list(screen.absent),
            "required": list(screen.required),
        }
        problems = marker_problems(tree, screen.marker, screen.absent)
        problems += required_problems(tree, screen.required)
        if screen.marker_in_viewport:
            problems += viewport_problems(tree, screen.marker)
        if problems:
            raise SampleError(
                f"screen {screen.stem}: {'; '.join(problems)} at font_scale {applied:g}"
            )
        # The screenshot/dump limbs above are already on disk and independent of this leg: the
        # speech pass only moves accessibility focus on the live screen.
        if facts is not None and screen.speech:
            talkback_screens[screen.stem] = sampler.p806_talkback_screen(screen)

    if facts is not None:
        sampler.disable_talkback_accessibility()
        for key in (
            SETTING_A11Y_COMMAND,
            SETTING_A11Y_PREFIX,
            SETTING_A11Y_LIMIT,
            SETTING_A11Y_CLICK,
        ):
            sampler.adb("shell", "settings", "delete", "global", key, best_effort=True)

    def apk_record(path: str) -> dict[str, str]:
        return {"path": str(path), "sha256": sha256_file(Path(path))}

    host = {
        "sha": args.sha,
        "profile": profile.name,
        "avd_name": args.avd_name,
        "font_scale": applied,
        "screens": [screen.stem for screen in screens],
        "screenshots": sampler.screenshots,
        "dumps": sampler.dumps,
        "observations": observations,
        "artifacts": {"app_apk": apk_record(args.apk)},
    }
    if facts is not None:
        host["artifacts"]["test_apk"] = apk_record(args.test_apk)
    if profile.seeded and facts is None:
        # The seed contract this evidence was produced against (the ledger id must be the app's
        # PRODUCT_LEDGER_ID; a mismatch would have failed the launch marker above). The digest is
        # of the bytes actually pushed, so a gate can prove it re-derived against the same seed.
        host["seed"] = {
            "ledger_id": SEEDED_LEDGER_ID,
            "account": SEEDED_ACCOUNT_NAME,
            "note": SEEDED_TRANSFER_NOTE,
            "sha256": seed_sha256,
        }
    if facts is not None:
        # The p806 seed carries none of the P8-01/P8-03 literals; its identity is the pushed
        # digest plus the facts re-derived from that file. The TalkBack record names the device
        # service, its own log-level route and every screen's focus/speech window.
        host["seed"] = {"ledger_id": P806_LEDGER_ID, "sha256": seed_sha256}
        host["p806"] = {
            "seed_facts": facts.as_dict(),
            "talkback": {
                "package": P806_TALKBACK_PACKAGE,
                "device_service": sampler.talkback_component,
                "package_present": bool(
                    talkback_capability.get("package_present") if talkback_capability else False
                ),
                "prior_accessibility": prior_accessibility,
                "log_level": talkback_log_level,
                "screens": talkback_screens,
            },
        }
    (evidence / "host.json").write_text(json.dumps(host, indent=2), encoding="utf-8")
    return 0


def gate_seed_problems(host: dict[str, object], seed_sha256: str | None) -> list[str]:
    """The gate's seed-identity check: host.json's digest must be the gate seed file's own digest.

    Without it a gate can be re-derived over a different database than the run pushed: profile,
    screens and markers would all still match. `seed_sha256` is None when the gate cannot read a
    seed file (missing --seed, or a path that is not a file), which is itself a failure.
    """
    seed = host.get("seed")
    recorded = seed.get("sha256") if isinstance(seed, dict) else None
    if not isinstance(recorded, str) or not recorded:
        return ["host.json records no seed sha256 — the evidence is not tied to a seed"]
    if seed_sha256 is None:
        return [f"the gate's seed file cannot be read; host.json records {recorded}"]
    if recorded != seed_sha256:
        return [f"host.json records seed sha256 {recorded}, the gate's seed is {seed_sha256}"]
    return []


def p806_gate_problems(
    host: dict[str, object], facts: P806SeedFacts, evidence: Path
) -> list[str]:
    """Re-derive the p806 hand-off: the seed facts AND every screen's per-target speech windows.

    Each screen check is bound to its own recorded command id and re-derived from that id's
    isolated `speech-window-begin`/accepted/`speech-window-end` window in the raw artifact. The
    required values come from the seed facts, not from host.json: an evidence file that narrowed
    or swapped a target's requirements fails. Any class other than success (未取得 / 片段缺失 /
    金额错误) fails the gate. The image capability record is context, not a substitute for speech.
    """
    problems: list[str] = []
    recorded = host.get("p806")
    if not isinstance(recorded, dict):
        return ["host.json records no p806 seed facts"]
    if recorded.get("seed_facts") != facts.as_dict():
        problems.append(
            f"host.json records p806 facts {recorded.get('seed_facts')!r}, the seed derives {facts.as_dict()!r}"
        )
    talkback = recorded.get("talkback")
    if not isinstance(talkback, dict):
        return problems + ["host.json records no TalkBack evidence"]
    if talkback.get("package") != P806_TALKBACK_PACKAGE:
        problems.append(
            f"host.json records TalkBack package {talkback.get('package')!r}, "
            f"expected {P806_TALKBACK_PACKAGE!r}"
        )
    device_service = talkback.get("device_service")
    if not isinstance(device_service, str) or not device_service:
        problems.append("host.json records no device TalkBack service component")
        device_service = ""
    elif not (
        device_service.startswith(f"{P806_TALKBACK_PACKAGE}/")
        and device_service.endswith("TalkBackService")
    ):
        problems.append(
            f"host.json records {device_service!r} as the TalkBack service, "
            f"which is not a {P806_TALKBACK_PACKAGE} TalkBackService component"
        )
    log_level = talkback.get("log_level")
    if not isinstance(log_level, dict) or log_level.get("status") != "success":
        problems.append(
            "TalkBack's VERBOSE log-level route did not succeed: "
            f"{log_level.get('reason') if isinstance(log_level, dict) else log_level!r}"
        )
    else:
        if log_level.get("value") != P806_TALKBACK_LEVEL:
            problems.append(
                f"TalkBack's log-level route records {log_level.get('value')!r}, "
                f"expected {P806_TALKBACK_LEVEL!r}"
            )
        steps = log_level.get("steps")
        if not isinstance(steps, list) or not steps:
            problems.append("TalkBack's log-level route records no settings-UI steps")
    screen_records = talkback.get("screens")
    if not isinstance(screen_records, dict):
        return problems + ["host.json records no per-screen TalkBack evidence"]
    seen_ids: set[int] = set()
    for screen in p806_screens(facts):
        if not screen.speech:
            continue
        artifact = evidence / f"talkback-{screen.stem}.txt"
        if not artifact.is_file():
            problems.append(f"{screen.stem}: TalkBack window artifact is missing")
            continue
        record = screen_records.get(screen.stem)
        if not isinstance(record, dict):
            problems.append(f"{screen.stem}: host.json records no TalkBack screen result")
            continue
        if record.get("artifact") != artifact.name:
            problems.append(
                f"{screen.stem}: host.json names artifact {record.get('artifact')!r}, "
                f"the gate read {artifact.name!r}"
            )
        if record.get("device_service") != device_service:
            problems.append(
                f"{screen.stem}: host.json records TalkBack device service "
                f"{record.get('device_service')!r}, the run-level record is {device_service!r}"
            )
        enable = record.get("enable")
        enabled_components = enable.get("enabled_components") if isinstance(enable, dict) else None
        expected_enabled_file = f"talkback-enabled-{screen.stem}.txt"
        if not isinstance(enable, dict) or enable.get("status") != "success":
            problems.append(
                f"{screen.stem}: TalkBack was not enabled for the speech pass: "
                f"{enable.get('reason') if isinstance(enable, dict) else enable!r}"
            )
        else:
            if enable.get("enabled_services_file") != expected_enabled_file:
                problems.append(
                    f"{screen.stem}: the enable record names "
                    f"{enable.get('enabled_services_file')!r}, the screen's raw record is "
                    f"{expected_enabled_file!r}"
                )
            raw_services = evidence / expected_enabled_file
            if not raw_services.is_file():
                problems.append(
                    f"{screen.stem}: the raw enabled-services record "
                    f"{expected_enabled_file!r} is missing"
                )
            else:
                raw_components = [
                    part.strip()
                    for part in raw_services.read_text(encoding="utf-8").strip().split(":")
                    if part.strip() and part.strip() != "null"
                ]
                if device_service not in raw_components:
                    problems.append(
                        f"{screen.stem}: the raw enabled-services record {raw_components!r} "
                        f"does not contain the exact TalkBack component {device_service!r}"
                    )
                if not isinstance(enabled_components, list) or enabled_components != raw_components:
                    problems.append(
                        f"{screen.stem}: the host enable summary {enabled_components!r} does not "
                        f"match the raw record {raw_components!r}"
                    )
        checks = record.get("checks")
        if not isinstance(checks, list) or len(checks) != len(screen.speech):
            problems.append(
                f"{screen.stem}: host.json records {len(checks) if isinstance(checks, list) else checks!r} "
                f"TalkBack checks, the seed derives {len(screen.speech)}"
            )
            continue
        text = artifact.read_text(encoding="utf-8")
        artifact_lines = text.splitlines()
        derived_statuses: list[str] = []
        previous_id = 0
        for expected, check in zip(screen.speech, checks):
            if not isinstance(check, dict):
                problems.append(f"{screen.stem}: a TalkBack check record is not an object")
                derived_statuses.append("not-obtained")
                continue
            target = check.get("target")
            if target != expected.target:
                problems.append(
                    f"{screen.stem}: a TalkBack target is recorded as {target!r}, "
                    f"the seed derives {expected.target!r}"
                )
            if check.get("required") != list(expected.required):
                problems.append(
                    f"{screen.stem}: target {target!r} records requirements "
                    f"{check.get('required')!r}, the seed derives {list(expected.required)!r}"
                )
            command_id = check.get("command_id")
            if type(command_id) is not int or command_id <= 0:
                problems.append(
                    f"{screen.stem}: target {target!r} records command id {command_id!r}, "
                    "expected a positive integer"
                )
                derived_statuses.append("not-obtained")
                continue
            if command_id in seen_ids:
                problems.append(f"{screen.stem}: command id {command_id} is recorded twice")
            seen_ids.add(command_id)
            if command_id <= previous_id:
                problems.append(
                    f"{screen.stem}: command id {command_id} does not follow {previous_id}; "
                    "the ordered checks were not driven in order"
                )
            previous_id = command_id
            # The header must bind command to target, and the raw window itself must carry the
            # probe's command with the same losslessly encoded target and a found node: a
            # relabelled host header alone cannot attribute another focus target's speech.
            command_line = f"# command: {command_id}"
            target_line = f"# target: {expected.target}"
            if not any(
                line == command_line
                and index > 0
                and artifact_lines[index - 1] == target_line
                for index, line in enumerate(artifact_lines)
            ):
                problems.append(
                    f"{screen.stem}: the artifact does not bind command {command_id} "
                    f"to target {expected.target!r}"
                )
            status, _fragments, missing, reason = talkback_check_verdict(
                text, command_id, expected.target, expected.required
            )
            derived_statuses.append(status)
            if check.get("status") != status:
                problems.append(
                    f"{screen.stem}: target {target!r} records TalkBack status "
                    f"{check.get('status')!r}, the artifact derives {status!r}"
                )
            if status != "success":
                suffix = f"; missing {missing!r}" if missing else (f"; {reason}" if reason else "")
                problems.append(
                    f"{screen.stem}: TalkBack {talkback_status_label(status)} ({status}) "
                    f"for target {target!r}{suffix}"
                )
        if len(derived_statuses) != len(screen.speech):
            continue
        overall = overall_speech_status(derived_statuses)
        if record.get("status") != overall:
            problems.append(
                f"{screen.stem}: host.json records TalkBack status {record.get('status')!r}, "
                f"the artifact derives {overall!r}"
            )
    return problems


def gate_artifact_problems(
    host: dict[str, object], app_apk: Path, test_apk: Path
) -> list[str]:
    """The p806 gate's artifact identity: the APKs the evidence names are the files on disk."""
    problems: list[str] = []
    recorded = host.get("artifacts")
    if not isinstance(recorded, dict):
        return ["host.json records no APK artifacts"]
    for key, path in (("app_apk", app_apk), ("test_apk", test_apk)):
        entry = recorded.get(key)
        digest = entry.get("sha256") if isinstance(entry, dict) else None
        if not path.is_file():
            problems.append(f"the {key} file {str(path)!r} is not present for the gate")
            continue
        actual = sha256_file(path)
        if digest != actual:
            problems.append(f"host.json records {key} sha256 {digest!r}, the file is {actual}")
    return problems


def gate(args: argparse.Namespace) -> int:
    """Re-derive the verdict from the evidence on disk; never trust the driver's own word."""
    profile = profile_by_name(args.profile)
    facts = derive_p806_seed_facts(Path(args.seed)) if profile.name in DYNAMIC_PROFILES else None
    screens = p806_screens(facts) if facts is not None else profile.screens
    evidence = Path(args.evidence)
    host_path = evidence / "host.json"
    if not host_path.exists():
        print("gate: host.json is missing — the run produced no evidence", file=sys.stderr)
        return 1
    host = json.loads(host_path.read_text(encoding="utf-8"))
    problems: list[str] = gate_profile_problems(host, profile.name)
    if host.get("sha") != args.sha:
        problems.append(f"host.json records sha {host.get('sha')!r}, expected {args.sha!r}")
    applied = host.get("font_scale")
    if not isinstance(applied, (int, float)) or abs(float(applied) - args.font_scale) > 0.001:
        problems.append(f"font_scale {applied!r} is not the requested {args.font_scale:g}")
    for screen in screens:
        shot = evidence / f"{screen.stem}.png"
        tree = evidence / f"{screen.stem}.xml"
        if not shot.exists() or shot.stat().st_size < MIN_SCREENSHOT_BYTES:
            problems.append(f"{screen.stem}: screenshot missing or blank")
        if not tree.exists():
            problems.append(f"{screen.stem}: accessibility dump missing")
            continue
        for problem in marker_problems(
            tree.read_text(encoding="utf-8"), screen.marker, screen.absent
        ):
            problems.append(f"{screen.stem}: {problem}")
        for problem in required_problems(tree.read_text(encoding="utf-8"), screen.required):
            problems.append(f"{screen.stem}: {problem}")
        if screen.marker_in_viewport:
            for problem in viewport_problems(tree.read_text(encoding="utf-8"), screen.marker):
                problems.append(f"{screen.stem}: {problem}")
    if profile.seeded:
        seed_file = Path(args.seed) if args.seed else None
        actual = sha256_file(seed_file) if seed_file is not None and seed_file.is_file() else None
        problems.extend(gate_seed_problems(host, actual))
    if facts is not None:
        problems.extend(p806_gate_problems(host, facts, evidence))
        problems.extend(gate_artifact_problems(host, Path(args.apk), Path(args.test_apk)))
    if problems:
        for problem in problems:
            print(f"gate: {problem}", file=sys.stderr)
        return 1
    print(
        f"gate: font-scale sample complete — {len(profile.screens)} screens captured for profile "
        f"{profile.name!r} with their markers present and no previous-screen string left"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "gate"))
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--profile", choices=tuple(PROFILES), default="empty")
    parser.add_argument(
        "--seed",
        default="",
        help=(
            "prepared v37 ledger database: pushed for seeded profiles, and hash-compared by the "
            "gate against the digest host.json recorded"
        ),
    )
    parser.add_argument("--font-scale", type=float, default=1.3)
    parser.add_argument("--avd-name", default="ul-fontscale")
    parser.add_argument("--apk", default="android-app/build/outputs/apk/debug/android-app-debug.apk")
    parser.add_argument(
        "--test-apk",
        default="android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk",
        help=(
            "the androidTest APK that hosts the D-292 focus probe; installed and hash-recorded for "
            "the p806 profile, and hash-compared by the p806 gate"
        ),
    )
    parser.add_argument("--serial", default="")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    try:
        return run(args) if args.command == "run" else gate(args)
    except SampleError as error:
        print(f"{args.command}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
