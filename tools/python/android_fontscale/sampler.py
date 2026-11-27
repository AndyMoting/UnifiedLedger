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
- `profile=presentation` (D-369 E1/E2): an independent target over the dedicated P369 presentation
  seed (six transactions: ordinary expense, transfer, multi-category, multi-currency, two
  fixed-instant cross-day transfers) that captures the current presentation surfaces in either
  platform `ui_mode` (light default, dark opt-in) and records REAL TalkBack on every screen.
  `PRESENTATION_EVIDENCE_PLAN` names the eight plan scenarios and `PRESENTATION_SCENARIO_STEMS`
  maps every one of them onto this table — there is no deferred scenario left — so the mapping
  gate's only contract is that each scenario resolves to a captured stem;
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

The screenshot profiles (empty/seeded/refund plus presentation) also drive that same REAL TalkBack pass on one named target per
screen (P8-07 batch D, the Q3 evidence): the same probe, the same VERBOSE utterance window, the
same four-value verdict, plus one screenshot per target and the image's own TalkBack versionName.
Their expected targets come from the frozen profile table, never from a seed.

This rig is NOT a PR gate and never runs on a pull request: dispatch it against a commit that is
already on a branch, and read the artifact.

WHAT IT DOES AND DOES NOT CLAIM
-------------------------------
The screenshot is the evidence a human reads; the gate re-checks only what is mechanically
decidable: the font scale actually took effect, every screenshot is a real image rather than a
blank frame, and each screen's marker text is present in the accessibility tree at that scale (a
row removed from the composition loses its text node) while the strings its previous screen renders
are gone (a capture whose navigation did not land is not evidence). A scrollable screen is NOT
judged for viewport containment here: the dump carries only the rows inside its scroll viewport (a
row outside it is ABSENT, a partially visible one is reported clipped at the edge), so "the marker
is present" proves the row reached the viewport, never that it is shown in full; the screenshot and
the artifact read cover that half.
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

# P8-07 batch D (Q3): one speech target per SPEAKING screen of the screenshot profiles (empty/seeded/refund plus presentation) (the
# seeded profile's detail and reconciliation screens declare no speech and stay on the screenshot
# track). Each literal is the product copy that screen actually renders, named with the product
# file:line so a drift shows up as a reviewable diff; the probe focuses the first node whose text
# or content description contains it, and TalkBack's own fragments inside that target's window
# must repeat it.
# - P8-00: the empty-ledger sentence is the SAME copy on the home and the analysis tab
#   (P503OverviewScreen.kt:201, P503TabShell.kt:529); the assets tab renders no empty copy, so its
#   always-composed 总借入 card label (P503AssetsScreen.kt:215) is the screen's main line.
P8_00_EMPTY_COPY = "账本为空，还没有任何交易。"
P8_00_ASSET_TARGET = "总借入"
# - P8-02: the guide face's condition-area copy (P503SearchPresentation.kt:33), and the seeded
#   transfer's row on the result face — the 结果行 the design gate names, addressed by the note its
#   composed description carries (the note literal is owned by the seed writer, like 早餐 below).
#   That description lists every posting line of the row (flowRowContentDescription,
#   P503LedgerViewPresentation.kt:440-457), each one as `<account> <signed value> <code>`
#   (:523-529), and the design gate requires 金额逐项核对正负号、数值与币种
#   (docs/specs/2026-11-22-p8-07-stage-acceptance-design.md:71). The seed writer fixes the transfer
#   at 1_234 minor units out of the default payment account (P801FontscaleSeedWriterTest.kt:504,
#   :318-330) in CNY/2 (CatalogManagementUseCases.kt:21, :30), so its two legs announce
#   `默认支付账户 -12.34 CNY` and `对账样本账户 12.34 CNY` (source leg negative, destination leg
#   positive: :330 / :318). The target therefore requires BOTH legs' signed amounts with their
#   codes: the note alone accepts a row whose amounts were never spoken, and one leg alone
#   accepts a row whose other leg was never spoken. The credit leg spells its account name
#   because the bare `12.34 CNY` also occurs inside the debit leg's `-12.34 CNY`:
#   `_speech_contains` compares whitespace-free text, where the leading sign is no digit boundary.
P8_02_GUIDE_TARGET = "输入条件后开始搜索"
P8_02_RESULT_DEBIT = "-12.34 CNY"
P8_02_RESULT_CREDIT = f"{SEEDED_ACCOUNT_NAME} 12.34 CNY"
# - P8-03: the refund candidate row's own expense-leg label with the seed's fixed cap
#   (P503RefundEntryPresentation.kt:98). The amount FIELD's label carries a currency code that is
#   unknown until an arrival account is chosen — the switch into 退款到账 always yields a null
#   arrival account (EntryDraft.kt:435), so that label renders `退款金额（—）` on this screen — and
#   the row's copy is the stable target. The confirmation target is the inherited-category row
#   (P503ConfirmationPresentation.kt:124), which the page renders as one `label：value` node
#   (P503ConfirmationScreen.kt:47).
P8_03_CANDIDATE_TARGET = "支出腿金额 12.34"
P8_03_INHERITED_TARGET = "继承分类：早餐"

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

# D-369 A presentation rig configuration. `ui_mode` names the platform night-mode state the run
# applies before launch and records in host.json; light is the default every existing profile
# already ran under (a hosted AVD boots in light), so adding the switch leaves their evidence
# unchanged. The presentation profile is an independent dispatch target: it captures the current
# surfaces the foundation contract renders and records the planned future scenario set, but A never
# claims these frames as an accepted presentation result.
UI_MODES: tuple[str, ...] = ("light", "dark")
DEFAULT_UI_MODE = "light"
PRESENTATION_PROFILE_NAME = "presentation"
# The future evidence plan the later B/C/D batches fill with screen entries. The gate never reads
# this tuple; it is the configuration anchor that keeps the eight scenarios named in one place.
PRESENTATION_EVIDENCE_PLAN: tuple[str, ...] = (
    "ordinary-expense",
    "multiple-categories",
    "transfer",
    "multi-currency",
    "empty-day",
    "single-category-ranking",
    "cross-day-reconciliation",
    "technical-details",
)

# D-369 E1: which captured stems stand for which plan scenario. E2 completes the two scenarios
# E1 left deferred — the cross-day reconciliation queue and the technical-information disclosure —
# so every one of the eight now resolves to at least one stem of PRESENTATION_SCREENS and no
# deferred list is left to bypass that check. A stem belongs to exactly one scenario, and every
# mapped stem is in PRESENTATION_SCREENS; the unit test
# (tests/python/test_android_presentation_rig.py) holds both directions of that contract.
PRESENTATION_SCENARIO_STEMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("ordinary-expense", ("presentation-ordinary-expense",)),
    ("multiple-categories", ("presentation-multiple-categories",)),
    ("transfer", ("presentation-detail", "presentation-account-flow")),
    ("multi-currency", ("presentation-multi-currency",)),
    ("empty-day", ("presentation-calendar-empty",)),
    ("single-category-ranking", ("presentation-analysis",)),
    ("cross-day-reconciliation", ("presentation-reconciliation-cross-day",)),
    ("technical-details", ("presentation-technical-collapsed", "presentation-technical-expanded")),
)

# E1 presentation profile's production literals, each copied from the source the device renders
# (line numbers re-verified against the post-C-batch main after the D-369 secondary-presentation
# merge):
# - `流水 · 共 N 笔` is the HOME flow header (P503OverviewScreen.kt:207); the P369 seed carries six
#   transactions, so N is six.
# - Each detail screen's marker is the Success-only 账目信息 row `备注：<note>`, rendered on the
#   ANDROID profile by cTransactionDetailUserLines (P503LedgerViewPresentation.kt:1158) and composed
#   by P503LedgerView.kt:627-628; its note is the seed's own literal (P369PresentationSeedWriterTest).
# - The expense headline is `-<amount> <code>` for the two-leg EXPENSE shape
#   (P503LedgerView.kt:799-816, the EXPENSE branch at :808-809), which is exactly the 8.88 CNY and
#   100.00 USD rows.
# - `其余 N 个` is the home row's badge-cap copy (P503LedgerViewPresentation.kt:738); the cap is
#   MAX_HOME_CATEGORY_BADGES = 3 in ledger-application's PresentationProjection.kt:446, so the
#   four-category seed row shows three badges and `其余 1 个`.
# - The ranking row text is categoryShareRowText (P503LedgerViewPresentation.kt:949-952):
#   `<category> <amount> <code> <tenths>%`. The current month has exactly one level-1 expense
#   category (餐饮) at 8.88 CNY, i.e. a single 100.0% row.
# - `该月暂无收支。` is the calendar's READY_EMPTY copy (P503CalendarScreen.kt:139); the two `‹`
#   month steps land on the month before the seed's last-month rows, where every cell is a real
#   zero. calendarDayShowsAmounts (P503LedgerViewPresentation.kt:967) then renders the date only,
#   while the cell's contentDescription still spells the zeros (P806CalendarPresentation.kt:122-130)
#   — the reason this screen alone uses `visible_absent`.
# - `新建一级分类` is the management route's category button (P503CatalogManagementScreen.kt:343);
#   总借入 belongs to the ACCOUNTS first screen only (P503AssetSummaryRegion), so its absence proves
#   the route switched.
PRESENTATION_HOME_COUNT = "流水 · 共 6 笔"
PRESENTATION_ORDINARY_EXPENSE_NOTE = "午餐样本支出"
PRESENTATION_ORDINARY_EXPENSE_MARKER = f"备注：{PRESENTATION_ORDINARY_EXPENSE_NOTE}"
PRESENTATION_ORDINARY_EXPENSE_AMOUNT = "-8.88 CNY"
PRESENTATION_MULTI_CURRENCY_NOTE = "美元样本支出"
PRESENTATION_MULTI_CURRENCY_MARKER = f"备注：{PRESENTATION_MULTI_CURRENCY_NOTE}"
PRESENTATION_MULTI_CURRENCY_AMOUNT = "-100.00 USD"
PRESENTATION_MULTI_CATEGORY_NOTE = "多分类样本支出"
PRESENTATION_MULTI_CATEGORY_REMAINING = "其余 1 个"
PRESENTATION_MULTI_CATEGORY_BADGE = "午餐"
PRESENTATION_RANKING_TEXT = "餐饮 8.88 CNY 100.0%"
PRESENTATION_RANKING_AMOUNT = "8.88 CNY"
PRESENTATION_RANKING_MARKER = "100.0%"
PRESENTATION_CALENDAR_EMPTY_MARKER = "该月暂无收支。"
PRESENTATION_CALENDAR_EMPTY_TITLE = "收支日历"
PRESENTATION_CALENDAR_EMPTY_VISIBLE_ABSENT = ("0.00", "共 0 笔")
PRESENTATION_MANAGEMENT_MARKER = "新建一级分类"
PRESENTATION_MANAGEMENT_TITLE = "账户与分类管理"
PRESENTATION_MANAGEMENT_ABSENT = P8_00_ASSET_TARGET
# - The management route's account rows (E2d): AccountsSection renders one Text per account as
#   `name（kind）` (P503CatalogManagementScreen.kt:286-290) and the P369 seed names the four rows
#   (P369PresentationSeedWriterTest.kt:739-745). The route opens at the TOP of its list in every
#   combination of run 46058888914 (the 账户与分类管理 title sits at y=340 at 1.3 and y=349 at 1.0),
#   so all four rows are in the window at capture. Only the LAST TWO rows are required: the marker
#   ScrollStep can stop up to one swipe (a third of the 2400 px window) higher than the two observed
#   stops, which would put 对账样本账户 above the top bar while 跨日界样本账户 and 默认支付账户
#   (681 px and 382 px above the marker at 1.3) stay inside the window.
PRESENTATION_MANAGEMENT_ACCOUNTS = ("跨日界样本账户", "默认支付账户")
# - The seeded expense groups, in the order the route RENDERS them. The seed lists 餐饮,交通,日用品,
#   差旅 (P369PresentationSeedWriterTest.kt:659-668), but CatalogProjection.kt:69 sorts each tree
#   level by (parentId, name) and UTF-16 puts 交通 < 差旅 < 日用品 < 餐饮. The forward-only scroll
#   steps and the three-frame split depend on the rendered order, never on the seed's list order.
PRESENTATION_CATEGORY_TRANSPORT = "交通"
PRESENTATION_CATEGORY_TRAVEL = "差旅"
PRESENTATION_CATEGORY_DAILY = "日用品"
PRESENTATION_CATEGORY_FOOD = "餐饮"
PRESENTATION_MANAGEMENT_CATEGORIES = (
    PRESENTATION_CATEGORY_TRANSPORT,
    PRESENTATION_CATEGORY_TRAVEL,
    PRESENTATION_CATEGORY_DAILY,
    PRESENTATION_CATEGORY_FOOD,
)
# The leaf path rows: LeafRow renders `groupName / childName` (P503CatalogManagementScreen.kt:
# 408-411), and the seed's children give 差旅/机票, 交通/早餐, 日用品/咖啡 then 零食 (咖啡 < 零食),
# and 餐饮/午餐 then 早餐 (午餐 < 早餐). 差旅/机票 and 餐饮/午餐 are the anchors of the frames whose
# required block sits above them; 交通/早餐 is the middle frame's row above its own anchor.
PRESENTATION_CATEGORY_TRAVEL_FLIGHT = "差旅 / 机票"
PRESENTATION_CATEGORY_FOOD_LUNCH = "餐饮 / 午餐"
PRESENTATION_CATEGORY_TRANSPORT_BREAKFAST = "交通 / 早餐"
# The D7 management page's whole capture set (the accounts screen plus the three category viewports).
# The gate requires every stem, so deleting one frame cannot silently shrink the evidence back to a
# screen whose `required` names only the groups that happened to fit in its viewport.
PRESENTATION_MANAGEMENT_STEMS = (
    "presentation-catalog-management",
    "presentation-catalog-categories-upper",
    "presentation-catalog-categories-middle",
    "presentation-catalog-categories-lower",
)
PRESENTATION_ACCOUNT_FLOW_REQUIRED = ("CNY 12.34", f"{SEEDED_ACCOUNT_NAME} 12.34 CNY")
PRESENTATION_RECONCILIATION_REQUIRED = (SEEDED_TRANSFER_NOTE, "12.34 CNY")

# - The cross-day queue (E2 scenario `cross-day-reconciliation`): both 2020 transfers land in
#   `p369-window-asset` (跨日界样本账户 — the seed writer's WINDOW_ACCOUNT_NAME) and are PENDING, so
#   the list face renders `未核对 2 条` (reconciliationUncheckedPartitionText,
#   P503ReconciliationPresentation.kt:132). A row is ONE Text node,
#   reconciliationQueueRowText(row, ANDROID) (P503ReconciliationPresentation.kt:200-211): the date
#   comes from reconciliationRowDateText (:175-182), i.e. occurredPresentationTime(...).localDate()
#   — the Asia/Shanghai natural day (PresentationProjection.kt:104, MonthlyBuckets.kt:44) — then
#   the transaction's own note, cAmountText (P503LedgerViewPresentation.kt:1089-1093, which is
#   `presentationAmountText`: exact minor units, no sign for a positive leg, code appended) and the
#   status label. P408ReconciliationStatus.PENDING.label is 待对账 (P408Reconciliation.kt:36).
#   15:30Z is Shanghai 23:30 on 01-15 while 16:30Z is Shanghai 00:30 on 01-16 — ONE UTC day, two
#   natural days — so the negative marker is the line the old UTC bucket would print for B:
#   `2020-01-15 · 跨日界样本B`.
PRESENTATION_CROSS_DAY_ACCOUNT = "跨日界样本账户"
PRESENTATION_CROSS_DAY_NOTE_A = "跨日界样本A"
PRESENTATION_CROSS_DAY_NOTE_B = "跨日界样本B"
PRESENTATION_CROSS_DAY_DATE_A = "2020-01-15"
PRESENTATION_CROSS_DAY_DATE_B = "2020-01-16"
PRESENTATION_CROSS_DAY_AMOUNT_A = "5.00 CNY"
PRESENTATION_CROSS_DAY_AMOUNT_B = "6.00 CNY"
PRESENTATION_CROSS_DAY_STATUS = "待对账"
PRESENTATION_CROSS_DAY_QUEUE_COUNT = "未核对 2 条"
PRESENTATION_CROSS_DAY_ROW_A = (
    f"{PRESENTATION_CROSS_DAY_DATE_A} · {PRESENTATION_CROSS_DAY_NOTE_A} · "
    f"{PRESENTATION_CROSS_DAY_AMOUNT_A} · {PRESENTATION_CROSS_DAY_STATUS}"
)
PRESENTATION_CROSS_DAY_ROW_B = (
    f"{PRESENTATION_CROSS_DAY_DATE_B} · {PRESENTATION_CROSS_DAY_NOTE_B} · "
    f"{PRESENTATION_CROSS_DAY_AMOUNT_B} · {PRESENTATION_CROSS_DAY_STATUS}"
)
# The pre-C-batch UTC-day bucket for B's stored instant: a row that still printed the UTC date would
# render exactly this, so its presence on the cross-day capture is the regression this screen vetoes.
PRESENTATION_CROSS_DAY_UTC_ERROR = f"{PRESENTATION_CROSS_DAY_DATE_A} · {PRESENTATION_CROSS_DAY_NOTE_B}"

# - The multi-category detail's 技术信息 area (E2 scenario `technical-details`):
#   TechnicalInfoSection (P503LedgerView.kt:890-918) renders ONE toggle whose contentDescription is
#   TechnicalInfoDisclosure.spokenText() (P503LedgerViewPresentation.kt:1135-1142); the three labels
#   are :1097-1099. The toggle merges its children, and the expanded body is
#   clearAndSetSemantics (P503LedgerView.kt:915), so the announcement — built from the SAME
#   disclosure rows — is what both states expose to the accessibility tree; the visible body text
#   stays a screenshot judgement. The seed's multi-category row has five legs, so
#   cTransactionDetailTechnicalRows (P503LedgerViewPresentation.kt:1166-1172) yields 3 + 5 = 8 rows,
#   and their values are the seed writer's own ids (P369PresentationSeedWriterTest:
#   MULTI_CATEGORY_TRANSACTION_ID plus its `-version` and posting-id suffixes). The expanded state's
#   other affordance label, C_TECHNICAL_INFO_COLLAPSE_LABEL = 收起技术信息 (:1099), is the toggle's
#   onClickLabel there and is deliberately NOT a required speech string: the expanded announcement
#   does not carry it (only an action hint could, and its wording is unverified), so requiring it
#   would be a guess rather than evidence.
PRESENTATION_TECHNICAL_HEADER = "技术信息"
PRESENTATION_TECHNICAL_EXPAND_LABEL = "展开技术信息"
PRESENTATION_TECHNICAL_ROW_COUNT = 8
PRESENTATION_TECHNICAL_COLLAPSED = (
    f"{PRESENTATION_TECHNICAL_HEADER}，已收起，共 {PRESENTATION_TECHNICAL_ROW_COUNT} 项，"
    f"{PRESENTATION_TECHNICAL_EXPAND_LABEL}"
)
PRESENTATION_TECHNICAL_EXPANDED = f"{PRESENTATION_TECHNICAL_HEADER}，共 {PRESENTATION_TECHNICAL_ROW_COUNT} 项："
PRESENTATION_MULTI_CATEGORY_LEDGER_ID = SEEDED_LEDGER_ID
PRESENTATION_MULTI_CATEGORY_TRANSACTION_ID = "p369-multi-category"
PRESENTATION_MULTI_CATEGORY_VERSION_ID = "p369-multi-category-version"
PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID = "p369-multi-category-payment"


def ui_mode_night_value(ui_mode: str) -> str:
    """The `cmd uimode night` value for a supported mode; an unknown mode is a loud failure."""
    if ui_mode == "light":
        return "no"
    if ui_mode == "dark":
        return "yes"
    raise SampleError(f"unknown ui_mode {ui_mode!r}; expected one of {list(UI_MODES)}")


def ui_mode_readback_problem(output: str, ui_mode: str) -> str | None:
    """Whether the readback line names the exact requested mode; anything else is a failure.

    `cmd uimode night` must print exactly one standalone `Night mode: yes` / `Night mode: no`
    line. An `Unknown command: uimode` reply, a missing line, a duplicate, or any other mode is a
    loud problem rather than a substring match on the raw text.
    """
    expected = f"Night mode: {ui_mode_night_value(ui_mode)}"
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if lines.count(expected) == 1:
        return None
    return (
        f"`cmd uimode night` did not report exactly one {expected!r} line; "
        f"raw output: {output.strip()[:200]!r}"
    )


def gate_ui_mode_problems(host: dict[str, object], requested: str) -> list[str]:
    """The gate's ui_mode consistency check, re-derived from host.json alone."""
    recorded = host.get("ui_mode")
    if recorded != requested:
        return [f"host.json records ui_mode {recorded!r}, the gate requested {requested!r}"]
    return []

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
# One gesture can miss for a reason the host cannot observe (a pushed-past double-tap window, or an
# enabled-services read that no longer describes binding), and both explanations stay live, so the
# deny is retried with the alternating gesture: inferred, other, inferred again.
PERMISSION_DIALOG_GESTURE_ATTEMPTS = 3
# P8-07 batch D r4 (T1): enabling the service is not the same as TalkBack having taken over. On the
# first real-device round the probe's first command reached the framework while TalkBack was still
# announcing itself, the focus request was refused (accepted=false), and the notification prompt
# then owned the active window. The host now waits, bounded, for TalkBack's own speech controller to
# log its first fragment after the enable write: that is the service speaking, which no settings
# read, bound-service listing or probe acknowledgement can show. The startup announcement is spoken
# in the device locale, so the limb is "TalkBack spoke at all", never an English literal. The bound
# matches `wait_probe_connected`'s own 90 s; a timeout fails closed with the wait and the log tail.
TALKBACK_READY_TIMEOUT_S = 90
TALKBACK_READY_POLL_INTERVAL_S = 1.0
# The window tail the readiness artifact keeps, so a red run is diagnosable from the evidence alone.
TALKBACK_READY_TAIL_LINES = 60
# D-369 E2f (run 46074246784): "TalkBack spoke once" is not yet "TalkBack's engine can speak". The
# readiness read accepted ONE startup fragment ("TalkBack on") while the TTS engine was still
# initialising; the focus command that followed then produced a window with no utterance and the
# gate correctly failed it. TalkBack logs `Speaking fragment text=…` only when it hands the text to
# the engine, so a readiness limb that keys on that line is exactly "the engine worked"; the
# `TTS is not ready` rows name an utterance the engine DROPPED and must not be read as speech.
# Readiness therefore also requires the LAST speech event to be a spoken fragment, never a dropped
# one, and a command whose window still carries no utterance is re-issued once as a WHOLE command
# under a fresh id after a bounded readiness wait. Two commands is the bound: the observed TTS
# initialisation finished within seconds, every attempt costs up to the 75 s command poll, and a
# second silent window is already the red signal the gate must see.
TALKBACK_COMMAND_ATTEMPTS = 2
TALKBACK_RETRY_READY_TIMEOUT_S = 30
TALKBACK_RETRY_READY_POLL_INTERVAL_S = 1.0
# P8-07 batch D r4 (T2): the prompt arrives AFTER that announcement, so one clear at the enable
# boundary cannot cover it. The panel is re-read in bounded rounds, spaced further apart, until
# NOTIFICATION_DIALOG_QUIET_READS consecutive reads render no prompt at all.
NOTIFICATION_DIALOG_SETTLE_ROUNDS = 4
NOTIFICATION_DIALOG_SETTLE_INTERVAL_S = 2.0
NOTIFICATION_DIALOG_QUIET_READS = 2
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
# P8-07 batch D r6 (T2): the tap that opens the level list is the one step the run cannot prove
# from its own commands. Run 45760191802 left `talkback-prefs-dialog.xml` as the preference list
# itself — its `…:id/summary` row still rendering ERROR — and the single attempt gave up with "the
# level list never rendered a VERBOSE entry". The route is now retried, and every attempt re-reads
# the preference tree and re-taps the row, keeping its own tree as `talkback-prefs-level-<n>.xml`.
# Three attempts, then the route fails exactly as before: the gate still requires
# `status=success` and `value=VERBOSE`, and this bound only removes the single-jitter death.
TALKBACK_LEVEL_ATTEMPTS = 3
TALKBACK_LEVEL_ENTRY_TIMEOUT_S = 30
TALKBACK_LEVEL_ENTRY_POLL_S = 2.0
# Two consecutive reads that still render the preference row's OWN summary mean the tap never
# opened the list: the single-choice list renders level names as plain rows, never as `:id/summary`,
# so the summary row's continued presence is the exact "the list did not open" signal.
TALKBACK_LEVEL_STALE_READS = 2
TALKBACK_LEVEL_DIALOG_SWIPES = 4
TALKBACK_LEVEL_RETRY_INTERVAL_S = 2.0
# The test-only probe that moves accessibility focus (D-292). It lives in the androidTest APK; the
# settings component string is the platform's flattened form of that manifest entry.
P806_PROBE_PACKAGE = "com.unifiedledger.android.test"
P806_PROBE_COMPONENT = (
    "com.unifiedledger.android.test/com.unifiedledger.android.A11yFocusProbeService"
)
# `dumpsys accessibility` renders a bound service by its LABEL on API 36
# (`Service[label=TalkBack, feedbackType[…]]`), with no package name anywhere, so the label is what
# a bound-state check matches first; the package name stays as the fallback for older dumps.
P806_TALKBACK_LABEL = "TalkBack"
P806_PROBE_LABEL = "UL a11y probe"
SETTING_A11Y_COMMAND = "ul_a11y_command"
SETTING_A11Y_PREFIX = "ul_a11y_prefix"
SETTING_A11Y_LIMIT = "ul_a11y_limit"
SETTING_A11Y_CLICK = "ul_a11y_click"
# The auto pass must not move focus while a host-commanded window is open: this prefix matches no
# node (the probe compares with startsWith) and a zero limit caps the pass anyway.
P806_TALKBACK_AUTO_SENTINEL = "__ul_p806_speech_window__"
# P8-07 batch D (D4): the one raw device answer the Q3 gate re-parses for the TalkBack versionName
# and the device API level. It holds the `dumpsys package` output itself, so the version is read
# from the device's own line rather than from host.json.
TALKBACK_VERSION_ARTIFACT = "talkback-version.txt"
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

    The dump carries only what a scroll container's viewport shows: a row entirely outside the
    viewport is ABSENT from the dump, and a partially visible row is reported clipped at the
    viewport edge. Run 46058888914 measures both on the management route's verticalScroll column:
    at 1.3 the last content node is the 支出分类 header reported (42,2085)-(615,2133), i.e. clipped
    at the dock's top edge (the same header is 63 px tall at 1.0), while the window node still
    reports [0,0][1080,2400] and NO 交通 row exists in the dump at all. "The label is in the dump"
    is therefore already a visibility claim, and the window-bounds test below is the remaining
    bound: it rejects a centre the dump reports outside the window, but it cannot recover a row the
    dump omits — a caller that needs the row to exist at all asserts it with `dump_contains`, which
    is what a screen's `required` does.
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


def visible_absent_problems(xml_text: str, visible_absent: tuple[str, ...]) -> list[str]:
    """The strings a screen's VISIBLE text must not carry.

    Only the `text` attribute is inspected: `content-desc` is the accessibility sentence and may
    keep stating a value the visible composition deliberately omits (an empty calendar day renders
    its date only, while its content description still says `支出 0.00 … 共 0 笔`). `absent` keeps
    its own text-and-description meaning; this criterion never weakens it.
    """
    texts = [node.get("text") or "" for node in _nodes(xml_text)]
    return [
        f"visible text {needle!r} is still shown"
        for needle in visible_absent
        if any(needle in text for text in texts)
    ]


def viewport_problems(xml_text: str, marker: str) -> list[str]:
    """The marker node's whole rect must sit inside the dump's window bounds.

    The dump carries only what the scroll viewport shows, so `marker_problems` proves the marker is
    in the tree — not that its node is unclipped — and this containment test is the only extra
    bound the dump settles: it rejects a node reported beyond the window. A node the dump reports
    clipped at the viewport edge still lies inside the window, so the supplemental report frame's
    requirement (the natural-day statement shown IN FULL) keeps the artifact read as its proof.
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


def screen_problems(xml_text: str, screen: Screen) -> list[str]:
    """Every mechanically decidable verdict for one screen, in one place.

    The driver checks these before it keeps a capture and the gate re-derives them from the dump on
    disk; one shared function keeps the two paths from drifting (a screen must satisfy the marker,
    the previous-screen strings' absence, the required values, the visible-only absence criterion
    and, when declared, whole-rect containment). `Screen` is a later dataclass; the module's
    postponed annotations make the reference safe.
    """
    problems = marker_problems(xml_text, screen.marker, screen.absent)
    problems += required_problems(xml_text, screen.required)
    problems += visible_absent_problems(xml_text, screen.visible_absent)
    if screen.marker_in_viewport:
        problems += viewport_problems(xml_text, screen.marker)
    return problems


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
# The utterance the engine DROPPED, on the same brief columns one message later. Its payload quotes
# the intended words (`Item: {utteranceId:"", fragments:[{text:日用品, …}]}`), which is exactly why
# the fragment recognizer above must stay anchored to `Speaking fragment text=`: the dropped
# utterance was never spoken. Run 46074246784's four middle management captures all carry this row
# (light-1.3/talkback-presentation-catalog-categories-middle.txt), and the three green combinations
# derive their genuine `Speaking fragment text="日用品"` from a later row in the same window, so this
# recognizer is the place the two shapes are told apart.
_TALKBACK_TTS_NOT_READY = re.compile(
    r"^[VDIWEF]/talkback(?:[ \t]*\([ \t]*\d+\))?:[ \t]*SpeechControllerImpl(?:\([ \t]*\d+\))?:"
    r"[ \t]*TTS is not ready\.",
    re.MULTILINE,
)
_COMMAND_NEEDLE64 = re.compile(r"\bneedle64=([A-Za-z0-9+/=]+)")

# The mask the probe logs when its accessibility service connects (D0 on the device side; kept in
# the screen's window artifact since P8-07 r7 D4). Matched as a plain substring so the artifact
# comment carries the whole original logcat line rather than a re-rendered subset of it.
PROBE_SERVICE_INFO_MARKER = "service-info eventTypes="


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


def permission_deny_gesture(enabled_services: str, talkback_component: str = "") -> str:
    """The deny gesture the enabled accessibility services call for.

    A lone tap activates the button only while no accessibility service owns touch exploration.
    With the exact TalkBack component enabled the same tap merely moves accessibility focus, so the
    activation gesture is a double tap; a run that enabled only the focus probe keeps ordinary touch
    semantics. The component, not a package guess, decides.
    """
    components = enabled_accessibility_components(enabled_services)
    if talkback_component and talkback_component in components:
        return "double"
    return "single"


def permission_deny_gesture_sequence(first: str) -> tuple[str, ...]:
    """The gestures tried in order: the inferred one first, then the other, alternating.

    A prompt that survives the first gesture leaves two explanations live — the gesture missed, or
    the enabled-services read does not describe what the screen actually holds — so the retry covers
    the other gesture instead of repeating the same one.
    """
    other = "single" if first == "double" else "double"
    return tuple(
        first if attempt % 2 == 0 else other
        for attempt in range(PERMISSION_DIALOG_GESTURE_ATTEMPTS)
    )


def permission_deny_tap_command(center: tuple[int, int], gesture: str) -> tuple[str, ...]:
    """The `adb shell` arguments for one deny press.

    A double tap is TWO taps inside ONE shell invocation: separate adb calls put the host's
    adb+binder round-trip inside AOSP's 300 ms double-tap window, while a device-side `sleep`
    orders both taps without it. adb joins the arguments and hands them to the device shell, so
    the `;` separators reach `sh` as written (the same reason `dump` cannot rely on quoting).
    """
    press = ("input", "tap", str(center[0]), str(center[1]))
    if gesture != "double":
        return press
    return press + (";", "sleep", str(P806_TALKBACK_DOUBLE_TAP_INTERVAL_S), ";") + press


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

    Only the `Speaking fragment text=…` row is an utterance, because only that row is logged after
    the engine accepted the text. The dropped-utterance row (`TTS is not ready … Item:
    {utteranceId:"", fragments:[{text:…}]}`) quotes the intended words but was never spoken, so it
    is not counted here — a `TTS is not ready` payload can never satisfy a speech check. A silenced
    fragment is the literal `text=null` (unquoted); that is no utterance at all either, so it never
    counts as a spoken fragment.
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


def talkback_readiness(log_text: str) -> tuple[bool, list[str]]:
    """`(ready, fragments)`: TalkBack has spoken and its LAST speech event was not dropped.

    Readiness is TalkBack SPEAKING, not TalkBack being enabled. The settings read-back, the bound
    service listing and the probe's acknowledgement all only say the service was asked to run; the
    first real-device round shows a focus request refused while TalkBack was still announcing
    itself. Any fragment counts: the startup announcement is spoken in the device locale, so keying
    on an English literal would make readiness a locale dependency rather than a proof.

    Speaking once is not enough while the engine is still initialising (D-369 E2f, run
    46074246784): the startup fragment is logged, the NEXT utterance is answered with
    `TTS is not ready` and dropped, and the command that follows records a window with no
    utterance. So the last speech event has to be a spoken fragment too; a dropped utterance after
    it is the engine saying it is not ready yet, and the caller must keep waiting.
    """
    fragments = speech_fragments(log_text)
    if not fragments:
        return False, fragments
    spoken_positions = [
        match.start() for match in _TALKBACK_FRAGMENT.finditer(log_text) if match.group(1) != "null"
    ]
    dropped_positions = [match.start() for match in _TALKBACK_TTS_NOT_READY.finditer(log_text)]
    if dropped_positions and (not spoken_positions or dropped_positions[-1] > spoken_positions[-1]):
        return False, fragments
    return True, fragments


def talkback_readiness_artifact(
    waited_s: float, polls: int, fragments: list[str], log_text: str, ready: bool
) -> str:
    """The readiness gate's own raw record: how long it waited and the window that decided.

    Kept on both outcomes. A ready pass explains why the first command waited; a timed-out pass
    carries the log that never showed an utterance (or whose last speech event was a dropped one),
    so the red run is diagnosable from the evidence directory without a re-run.
    """
    lines = [
        f"# readiness: {'ready' if ready else 'timeout'}",
        f"# waited: {waited_s:.1f} s",
        f"# logcat reads: {polls}",
        f"# talkback fragments: {len(fragments)}",
    ]
    lines.extend(f"# fragment: {fragment}" for fragment in fragments)
    if fragments and not ready:
        lines.append("# last speech event: dropped utterance (TTS is not ready)")
    lines.append("# log tail:")
    lines.extend(log_text.splitlines()[-TALKBACK_READY_TAIL_LINES:])
    return "\n".join(lines) + "\n"


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


def service_info_comment(log_text: str) -> str:
    """The probe's connect-time `service-info` line as one artifact comment, or `absent`.

    D4 keeps the mask this bind actually received next to the screen's own windows: without it a
    `seen=0` transcript cannot separate "the event mask never reached this connection" from "the
    platform posted no focus event", because the service XML states an intent rather than what the
    framework handed the service. The LAST occurrence wins (a service can reconnect inside one
    screen's capture), and the whole original line is kept verbatim so its hex values can be
    compared with the platform's own bit constants without the host re-rendering them.

    The `#` prefix is what keeps it out of every stage scan: `_ULA11Y_LINE` anchors on the logcat
    brief column (`priority/TAG(pid):`), so a comment line can never be a stage or a fragment.
    """
    last = ""
    for line in log_text.splitlines():
        if PROBE_SERVICE_INFO_MARKER in line:
            last = line
    return f"# service-info: {last or 'absent'}"


def accessibility_service_bound(bound_section: str, label: str, package: str) -> bool:
    """True when a `dumpsys accessibility` Bound services section lists one service.

    API 36 renders each entry as `Service[label=TalkBack, feedbackType[…], …]` and never prints the
    package name, so the exact label is the primary signal; the package name stays as the fallback
    for images whose dump carries it.
    """
    return f"Service[label={label}," in bound_section or package in bound_section


def talkback_bound_service(bound_section: str) -> bool:
    """True when the Bound services section lists TalkBack (label first, package as fallback)."""
    return accessibility_service_bound(bound_section, P806_TALKBACK_LABEL, P806_TALKBACK_PACKAGE)


def probe_bound_service(bound_section: str) -> bool:
    """True when the Bound services section lists the focus probe (label first, package fallback)."""
    return accessibility_service_bound(bound_section, P806_PROBE_LABEL, P806_PROBE_PACKAGE)


def accessibility_services_bound(bound_section: str) -> bool:
    """True while either the probe or TalkBack is bound; both must leave for a teardown to count."""
    return probe_bound_service(bound_section) or talkback_bound_service(bound_section)


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


def _window_focus_rejected(window: dict[str, object]) -> bool:
    """True iff this command's window completed, is structurally sound and still refused focus."""
    return (
        bool(window.get("completed"))
        and not window.get("problem")
        and not window.get("accepted")
    )


def focus_retry_reason(
    window: dict[str, object],
    *,
    first_command: bool,
    dialog_cleared: bool,
    readiness_delayed: bool,
) -> str:
    """The transient a refused focus may be retried in, or "" when no transient holds.

    A refused focus is the device's verdict and stays `not-obtained`. Only the two transients the
    first real-device round exposed earn one more command: the notification prompt was just cleared
    out of the active window, or TalkBack itself only finished starting inside this pass. The retry
    re-runs the WHOLE command under a fresh id — it never splits one window into two halves — and
    the verdict is still derived from the window that decided.
    """
    if not _window_focus_rejected(window):
        return ""
    if dialog_cleared:
        return "the notification prompt was cleared just before this command"
    if first_command and readiness_delayed:
        return "TalkBack only finished starting inside this pass"
    return ""


def talkback_window_fragments(window: list[str], command_id: int) -> list[str]:
    """This command's own utterances: only the lines strictly between begin and end.

    The same parser and the same window slice `talkback_check_verdict` uses, so "the command
    produced no utterance" is decided by the artifact, never by a caller's guess.
    """
    return speech_fragments("\n".join(_speech_window_lines(window, command_id)))


def talkback_silent_retry_reason(window: dict[str, object]) -> str:
    """The transient a window with no utterance may be retried in, or "" when it must stand.

    D-369 E2f: run 46074246784's `light-1.0` home screen recorded a command that never began a
    window at all, and its middle management screen recorded a completed accepted window with no
    utterance — both while the TTS engine was still initialising. Either shape earns ONE more whole
    command under a fresh id (the bound lives in the caller, `TALKBACK_COMMAND_ATTEMPTS`). A refused
    focus has its own bounded retry (`focus_retry_reason`) and is not this transient, and a window
    that carries any utterance stands even when a required value is missing: re-issuing it would not
    be a transient, it would be re-rolling the die on the device's own reading.
    """
    if not window.get("completed"):
        return "the command's speech window never completed"
    if window.get("problem"):
        return ""
    command_id = window.get("id")
    if not isinstance(command_id, int):
        return ""
    if window.get("accepted") and not talkback_window_fragments(
        [str(line) for line in window.get("lines") or []], command_id
    ):
        return "the accepted window produced no utterance (the TTS engine was not ready)"
    return ""


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


def package_version_name(dump: str) -> str:
    """The `versionName=` answer inside a `dumpsys package` dump, or "" when it carries none.

    Only the package's own line counts: a dump that names no version (the package is absent, or the
    image stopped printing it) yields no version at all, which the gate fails closed on.
    """
    match = re.search(r"^\s*versionName=(\S+)\s*$", dump, re.MULTILINE)
    return match.group(1) if match is not None else ""


def talkback_version_artifact(dump: str, api_level: str) -> str:
    """The raw device answer the gate re-parses: the package dump plus the API-level `getprop`.

    The two header lines name what the body is; the version itself is never copied into them, so a
    gate reading this file re-derives it from the dump exactly as the run did.
    """
    return (
        f"# talkback package: {P806_TALKBACK_PACKAGE}\n"
        f"# device api level: {api_level.strip()}\n"
        f"{dump}"
    )


def parse_talkback_version_artifact(text: str) -> tuple[str, str]:
    """`(version, api_level)` re-derived from the recorded raw artifact, never from host.json."""
    api = re.search(r"^# device api level: (\S+)$", text, re.MULTILINE)
    return package_version_name(text), (api.group(1) if api is not None else "")


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
    # Strings a screen's VISIBLE text must not carry, checked against the `text` attribute only.
    # A cell's contentDescription can still spell the value it deliberately stops showing (the
    # empty-day calendar cell announces `0.00`/`共 0 笔` while rendering only its date), so `absent`
    # would turn that correct frame red; this criterion states the visible-only claim instead.
    visible_absent: tuple[str, ...] = ()
    # The D-292 speech limb: each ordered check names its focus target (substring match on the
    # node's text or content description) and the values TalkBack's fragments inside THAT target's
    # accepted, completed window must contain. Empty keeps the screen on the screenshot-only path,
    # so a profile can carry the limb on some of its screens only (the seeded profile's detail and
    # queue screens do).
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
    # The D-292 speech limb runs for this profile (P8-07 batch D, D3): the switch decides the test
    # APK install, the probe discovery, the notification-prompt guard and the TalkBack teardown, so
    # the screenshot profiles (empty/seeded/refund plus presentation) drive real TalkBack exactly as the dynamic p806 profile does.
    # Which screens actually speak is still the screen's own `speech` tuple.
    talkback: bool = False


# The P8-00 V-8 screens: a fresh install bootstraps one default account, so the accounts tab is
# never empty and 资产 samples the 有户无余额 state the E2s canvas documents. Since D-307 the home
# tab paints 账本为空 for a ledger with no transactions at all — before that the branch was
# unreachable and the screen showed 流水 · 共 0 笔 instead. The rig samples what the device shows.
EMPTY_SCREENS: tuple[Screen, ...] = (
    Screen(
        "home",
        (),
        "账本为空",
        speech=(SpeechCheck(P8_00_EMPTY_COPY, (P8_00_EMPTY_COPY,)),),
    ),
    Screen(
        "asset",
        (TapStep("账户"),),
        "总借入",
        speech=(SpeechCheck(P8_00_ASSET_TARGET, (P8_00_ASSET_TARGET,)),),
    ),
    Screen(
        "analysis",
        (TapStep("分析"),),
        "账本为空",
        speech=(SpeechCheck(P8_00_EMPTY_COPY, (P8_00_EMPTY_COPY,)),),
    ),
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
#
# P8-07 batch D gives the guide face (its condition-area copy) and the result face (the seeded
# transfer's row) one speech target each; the detail and queue screens stay screenshot-only.
SEEDED_SCREENS: tuple[Screen, ...] = (
    Screen(
        "search-guide",
        (TapStep("搜索账目", contains=True),),
        SEARCH_GUIDE_MARKER,
        absent=(SEEDED_HOME_COUNT,),
        speech=(SpeechCheck(P8_02_GUIDE_TARGET, (P8_02_GUIDE_TARGET,)),),
    ),
    # D7-8: the submit button is tapped by its unique semantics name — its visible text 搜索 is also
    # the screen title, and `find_center` returns the first node in document order.
    Screen(
        "search-results",
        (TapStep("提交搜索"),),
        SEARCH_RESULTS_MARKER,
        absent=(SEARCH_GUIDE_MARKER, SEEDED_HOME_COUNT),
        # The 结果行 the design gate names: the seeded transfer, addressed by its own note as the
        # row's composed description carries it, with one required string per leg. A header match
        # would only prove the page opened, the note alone would accept a row whose amounts were
        # never spoken, and one leg alone would accept a row whose other leg was never spoken.
        speech=(
            SpeechCheck(
                SEEDED_TRANSFER_NOTE,
                (SEEDED_TRANSFER_NOTE, P8_02_RESULT_DEBIT, P8_02_RESULT_CREDIT),
            ),
        ),
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
#
# P8-07 batch D adds one speech target per screen: the entry's candidate row (the expense-leg cap
# label and the seed's amount) and the confirmation's inherited-category row.
REFUND_SCREENS: tuple[Screen, ...] = (
    Screen(
        "refund-entry",
        (TapStep("新增支出"), tap_radio_for("退款到账")),
        SEEDED_REFUND_CATEGORY,
        # 费用分类 is the expense form's own category selector: the seed category name appears there
        # too, so its absence is what proves the type radio actually switched to the refund form.
        absent=(SEEDED_HOME_COUNT, "费用分类"),
        speech=(SpeechCheck(P8_03_CANDIDATE_TARGET, (P8_03_CANDIDATE_TARGET,)),),
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
        speech=(SpeechCheck(P8_03_INHERITED_TARGET, (P8_03_INHERITED_TARGET,)),),
    ),
)

# D-369 E1 presentation profile: the presentation surfaces over the dedicated P369 seed, captured
# in either ui_mode and either font_scale by the E-batch sweep. Each stem names the surface it
# photographs. The HOME flow is ordered `statistics_at DESC`, and the four home-row screens follow
# exactly that order so every `ScrollStep` moves FORWARD (a ScrollStep swipes forward and never
# back): slot 1 the 8.88 expense (now), slot 2 the transfer (now - 1 day), slot 3 the multi-category
# expense (last month 15th 10:00), slot 4 the USD expense (last month 15th 09:00). 10:00 sorts ABOVE
# 09:00, so the multi-category screen MUST precede the multi-currency one; reversing them would leave
# the multi-category row above the restored viewport and unreachable.
#
# The ACCOUNTS first screen is captured BEFORE the account-flow round trip on purpose: it opens from
# HOME with a fresh scroll at the top, so the forward-only step can reach 总借入 (the lending-card
# pair, P503AssetsScreen.kt:215) and the TalkBack focus target is on screen. The run must first
# press Back off the USD detail: that surface composes no tab shell (P503App.kt:5804 against the
# shell's :5508), so the 账户 dock does not exist there and the screen's first tap would abort the
# rest of the sweep; Back returns the retained overview (P503Reducer.kt:2107-2109). Capturing this
# screen after the flow's Back would restore the accounts list to the tapped account row instead
# (P503App.kt passes the sampled OverviewEmpty.originListScroll back to the screen), which leaves
# 总借入 ABOVE the viewport where a forward-only ScrollStep can never reach it. For the same
# reachability reason every screen whose speech target is not guaranteed above the fold scrolls to
# that exact target before the speech pass: 未核对 1 条 on the queue and 总借入 on ACCOUNTS. The
# management route scrolls to its 新建一级分类 button instead, and that step is what puts the account
# row the screen actually SPEAKS (跨日界样本账户) inside the frame. The speech target is that account
# row, not the scrolled-to marker: the route opens at the top of its list (all four rows are in the
# frame), so the TalkBack limb proves a DATA row's announcement instead of repeating the screenshot
# marker. The route's three category frames speak their group names (差旅, 日用品, 餐饮) for the
# same reason: none of those targets — like 跨日界样本账户 on the accounts frame — has been exercised
# on device yet, so the probe's ability to focus a category or account row through its four-ancestor
# rule is what the next sweep proves, not this table. Leaf paths (`差旅 / 机票`) stay out of every
# speech target: TalkBack's reading of the `/` is unverified, while each required literal is a plain
# word the fragments carry verbatim.
#
# System Back walks the captured surfaces: from the USD detail it returns the retained HOME overview
# (P503Reducer.kt:2107-2109), from the reconciliation queue it returns the carried account flow whole
# (P503Reducer.kt:2464), and from the account flow it returns the preserved overview whose selectedTab
# is ACCOUNTS (P503Reducer.kt:2335). The management route itself is left by the 分析 dock, not by
# Back: a bottom-bar tap clears catalogManagementSurface (P503Reducer.kt:1502), which is how the
# analysis screen leaves it.
#
# E2's two additions keep that walk intact by sitting exactly where their starting state exists:
# - `presentation-technical-collapsed` follows `presentation-detail`, so its first step is a Back
#   from the transfer detail onto the retained HOME list, which is still scrolled to the transfer
#   row (slot 2). The multi-category row is slot 3, i.e. BELOW it, so one forward ScrollStep reaches
#   it; tapping it opens the multi-category detail and a forward scroll walks down that page to the
#   技术信息 toggle (the page is a plain verticalScroll Column whose rows outside the viewport are
#   absent from the dump, so the swipe is what brings the toggle into the tree at all).
#   `presentation-technical-expanded`
#   taps that same toggle in place, so both states are photographed on the same page.
#   `presentation-multiple-categories` then keeps its own leading Back — it leaves the expanded
#   detail for the retained list and finds the multi-category row where it was tapped — so neither
#   its steps nor the multi-currency screen's change.
# - `presentation-reconciliation-cross-day` follows `presentation-reconciliation`, whose capture
#   ends on the 对账样本账户 queue. Two Backs (queue -> that account flow -> ACCOUNTS list restored
#   at the tapped row) put the list at slot 2 of its account_id order (asset-payment-local,
#   p369-recon-asset, p369-usd-asset, p369-window-asset — SqlDelightCatalogStore.kt:434 over
#   `Ledger.sq` `selectCatalogAccounts ... ORDER BY account_id`), so the window account is BELOW it
#   and one forward ScrollStep reaches it. Tap the row -> its account flow -> 账户对账 -> queue, then
#   scroll the B row into the viewport (the queue is a verticalScroll Column, and the dump carries
#   only the rows inside its viewport, so the swipe is what puts B's row in the tree and its centre
#   inside the window for the speech pass).
#   `presentation-catalog-management` keeps its two Backs: they leave the WINDOW queue the same way
#   and land on the ACCOUNTS list restored at the window row — the last account row — with the
#   management entry below it, so its forward ScrollStep still reaches it.
#
# E2d splits the management page into an accounts frame plus THREE category frames because ONE
# viewport cannot carry the whole category tree. The route is a plain verticalScroll Column
# (P503CatalogManagementScreen.kt:104-106), so every row is composed, but the accessibility dump the
# `required` strings are checked against carries only the rows that intersect the scroll viewport: in
# run 46058888914 the 1.3 dump ends at the 支出分类 header, reported (42,2085)-(615,2133) — 48 px,
# against the same header's 63 px at 1.0 — and holds NO 交通 row although the dump's window node still
# reports [0,0][1080,2400]. At 1.0 the last content node is the 交通 group Text at
# (42,2080)-(129,2137), and the leaf row below it — one group row further down, 2080 + 21 + 57 +
# 127 = ≈2285 — is absent although 2285 is inside the 2400 px window: the prune boundary is the dock
# top (2142 at 1.0, 2133 at 1.3), not the window. A row behind the dock is therefore absent evidence,
# not present-but-clipped. Whether the same clip runs at the top bar (y=275) is unproven in this run,
# so the margin arithmetic below assumes it does — the stricter reading, and the one that counts a
# row above the bar as gone.
#
# Row geometry, derived from the account rows of those same dumps: a 16 dp padding measures 42 px, so
# 1 dp = 2.625 px; the account row's 299 px pitch at 1.3 is one bodyLarge line (80 px), one
# bodyMedium line (71 px), the 4 dp paddings (21 px) and ONE 48 dp TextButton row — 127 px as a row,
# the same row measured 126 px as a clickable node. A category group row is that button row plus one
# bodyLarge line and the paddings: 228 px at 1.3 (211 at 1.0). A leaf row swaps in a bodyMedium line:
# 219 px at 1.3 (201 at 1.0). The rendered order is NAME order (CatalogProjection.kt:69): 交通,
# 交通/早餐, 差旅, 差旅/机票, 日用品, 日用品/咖啡, 日用品/零食, 餐饮, 餐饮/午餐, 餐饮/早餐 — ten
# rows, 2226 px at 1.3, against the 1858 px viewport between the top bar's bottom (measured: the
# bar's own nodes end at y=275) and the dock top (2133 at 1.3).
#
# Where a forward swipe can leave its target: a ScrollStep swipes the content by window_height/3
# (800 px over the dump's 2400 px window, scroll_swipe_points) and stops at the FIRST frame whose
# dump carries the target, so the target's text top comes to rest anywhere between (dock top − 800)
# and the dock top — it does NOT have to land at the dock edge. The rows above it stay inside the
# window while their distance is under 2133 − 800 − 275 = 1058 px. That bound counts the 800 px drag
# only: a fling can carry the content further, which is why every frame keeps more than one rendered
# row (219 px) of slack inside the band.
#
# The three frames, each anchored on the row that bounds its own required block:
# - `presentation-catalog-categories-upper` scrolls to `差旅 / 机票`, the leaf directly BELOW 差旅,
#   and requires 交通 + 差旅 + that leaf. 交通 sits 675 px above the anchor at 1.3 — the 交通 group
#   row (228) plus 交通's OWN 早餐 leaf row (219) plus the 差旅 group row (228); the earlier note
#   left that leaf row out and claimed 445 px — so the margin to the 1058 px band is 383 px, and the
#   frame survives a swipe that carries up to ≈1180 px instead of 800.
# - `presentation-catalog-categories-middle` scrolls to `日用品` ITSELF and requires that group name
#   plus `交通 / 早餐`, 666 px above it (the 早餐 leaf, the 差旅 group and the 差旅 / 机票 leaf). The
#   anchor cannot come to rest outside the viewport — it is the stop condition — and the row above it
#   keeps 392 px of margin.
# - `presentation-catalog-categories-lower` scrolls to `餐饮 / 午餐`, the 餐饮 group's first leaf
#   (午餐 sorts before 早餐), and requires 餐饮 + that leaf; 餐饮 is one group row above the anchor
#   (228 px), 830 px of margin.
# - What the split FIXES: 日用品 used to ride on the lower frame, four rendered rows above the
#   anchor (日用品, 咖啡, 零食, 餐饮) = 894 px at 1.3. That stayed inside the band only while one
#   swipe carried under ≈960 px, i.e. 164 px of slack — less than one leaf row (219 px) — so any
#   fling past the 800 px drag reds it. Putting 日用品 on its own frame removes that coin flip.
# - The accounts frame keeps the route guard and names the two account rows it films.
#
# Evidence strength, stated honestly: `required` is a SUBSTRING test over every node, so a group name
# never proves its GROUP row. 差旅 and 餐饮 are also carried by a leaf path the same frame requires
# (差旅 / 机票, 餐饮 / 午餐), which makes them redundant there; 交通 and 日用品 are the two group
# names no other required string of their frame implies — but each is still a substring of its own
# leaf paths (交通 / 早餐, 日用品 / 咖啡), so a missing group row with its leaves present would pass
# the check. The middle frame's stop condition is the one place the GROUP row is addressed exactly
# (`find_reachable_center`'s exact match, not `dump_contains`), and the screenshot carries the rest.
#
# The group row is also the middle frame's SPEECH target and stays so (D-369 E2f, run 46074246784):
# GroupRow renders the name as a `Text` whose action buttons sit in a SIBLING Row
# (P503CatalogManagementScreen.kt:364-393, the name Text at :365-368), so the name itself is not
# focusable and has no focusable ancestor inside the probe's four-level walk
# (A11yFocusProbeService.java:122 MAX_FOCUS_ANCESTOR_DEPTH). EVERY group row reports exactly that,
# 差旅 and 餐饮 included; the probe then sends ACTION_ACCESSIBILITY_FOCUS to the visible Text itself
# when no ancestor qualifies (:836-838, :886), and Compose answers
# `focus-result … accepted=true source=action` (:818). The name is therefore announced: the same run's
# middle captures carry `Speaking fragment text="日用品"` inside the accepted window in light-1.3,
# dark-1.0 and dark-1.3, next to the identical `Speaking fragment text="差旅"` of the upper frame.
# Moving the target to a leaf row would buy nothing and cost the unverified `/` reading that is the
# stated reason leaf paths stay out of every speech target (see the block comment above); the
# four-level constant is not what limits this screen.
PRESENTATION_SCREENS: tuple[Screen, ...] = (
    Screen(
        "presentation-home",
        (),
        PRESENTATION_HOME_COUNT,
        absent=(P8_00_EMPTY_COPY,),
        required=(SEEDED_TRANSFER_NOTE,),
        speech=(SpeechCheck(SEEDED_TRANSFER_NOTE, (SEEDED_TRANSFER_NOTE,)),),
    ),
    Screen(
        "presentation-search-results",
        (TapStep("搜索账目", contains=True), TapStep("提交搜索")),
        SEARCH_RESULTS_MARKER,
        absent=(SEARCH_GUIDE_MARKER, PRESENTATION_HOME_COUNT),
        required=(SEEDED_TRANSFER_NOTE, P8_02_RESULT_DEBIT, P8_02_RESULT_CREDIT),
        speech=(
            SpeechCheck(
                SEEDED_TRANSFER_NOTE,
                (SEEDED_TRANSFER_NOTE, P8_02_RESULT_DEBIT, P8_02_RESULT_CREDIT),
            ),
        ),
    ),
    Screen(
        "presentation-ordinary-expense",
        (BACK, ScrollStep(PRESENTATION_ORDINARY_EXPENSE_NOTE), TapStep(PRESENTATION_ORDINARY_EXPENSE_NOTE, contains=True)),
        PRESENTATION_ORDINARY_EXPENSE_MARKER,
        absent=(PRESENTATION_HOME_COUNT,),
        required=(PRESENTATION_ORDINARY_EXPENSE_AMOUNT,),
        speech=(SpeechCheck(PRESENTATION_ORDINARY_EXPENSE_NOTE, (PRESENTATION_ORDINARY_EXPENSE_NOTE,)),),
    ),
    Screen(
        "presentation-detail",
        (BACK, ScrollStep(SEEDED_TRANSFER_NOTE), TapStep(SEEDED_TRANSFER_NOTE, contains=True)),
        SEEDED_DETAIL_MARKER,
        absent=(PRESENTATION_HOME_COUNT,),
        required=(P8_02_RESULT_DEBIT, P8_02_RESULT_CREDIT),
        speech=(SpeechCheck(SEEDED_TRANSFER_NOTE, (SEEDED_TRANSFER_NOTE,)),),
    ),
    # The technical area's two states. The collapsed capture is the DEFAULT state of the page — no
    # tap precedes its marker — and the expanded capture changes only that toggle. `absent` carries
    # the transaction id on the collapsed frame (the normal rows never print it, so its appearance
    # would mean the identifiers leaked out of the fold) and the collapsed sentence on the expanded
    # frame (so a tap that missed, or a body that never replaced the collapsed announcement, is red).
    Screen(
        "presentation-technical-collapsed",
        (
            BACK,
            ScrollStep(PRESENTATION_MULTI_CATEGORY_NOTE),
            TapStep(PRESENTATION_MULTI_CATEGORY_NOTE, contains=True),
            # Eight rows sit after the 账目信息 card's five leg rows and five reconciliation rows, so
            # at 1.3x the toggle can be more than one viewport below the fold; the budget only
            # bounds the swipe loop, it never turns a red frame green.
            scroll_until(PRESENTATION_TECHNICAL_COLLAPSED, max_swipes=8),
        ),
        PRESENTATION_TECHNICAL_COLLAPSED,
        absent=(PRESENTATION_HOME_COUNT, PRESENTATION_MULTI_CATEGORY_TRANSACTION_ID),
        required=(PRESENTATION_TECHNICAL_EXPAND_LABEL,),
        speech=(SpeechCheck(PRESENTATION_TECHNICAL_COLLAPSED, (PRESENTATION_TECHNICAL_EXPAND_LABEL,)),),
    ),
    Screen(
        "presentation-technical-expanded",
        (TapStep(PRESENTATION_TECHNICAL_COLLAPSED),),
        PRESENTATION_TECHNICAL_EXPANDED,
        absent=(PRESENTATION_TECHNICAL_COLLAPSED,),
        required=(
            PRESENTATION_MULTI_CATEGORY_LEDGER_ID,
            PRESENTATION_MULTI_CATEGORY_VERSION_ID,
            PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID,
        ),
        speech=(
            SpeechCheck(
                PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID,
                (PRESENTATION_MULTI_CATEGORY_VERSION_ID, PRESENTATION_MULTI_CATEGORY_PAYMENT_POSTING_ID),
            ),
        ),
    ),
    Screen(
        "presentation-multiple-categories",
        (BACK, ScrollStep(PRESENTATION_MULTI_CATEGORY_NOTE)),
        PRESENTATION_MULTI_CATEGORY_REMAINING,
        absent=(SEEDED_DETAIL_MARKER,),
        required=(PRESENTATION_MULTI_CATEGORY_NOTE, PRESENTATION_MULTI_CATEGORY_BADGE),
        speech=(SpeechCheck(PRESENTATION_MULTI_CATEGORY_REMAINING, (PRESENTATION_MULTI_CATEGORY_REMAINING,)),),
    ),
    Screen(
        "presentation-multi-currency",
        (ScrollStep(PRESENTATION_MULTI_CURRENCY_NOTE), TapStep(PRESENTATION_MULTI_CURRENCY_NOTE, contains=True)),
        PRESENTATION_MULTI_CURRENCY_MARKER,
        absent=(PRESENTATION_HOME_COUNT, PRESENTATION_MULTI_CATEGORY_REMAINING),
        required=(PRESENTATION_MULTI_CURRENCY_AMOUNT,),
        speech=(SpeechCheck(PRESENTATION_MULTI_CURRENCY_NOTE, (PRESENTATION_MULTI_CURRENCY_NOTE,)),),
    ),
    Screen(
        "presentation-asset",
        (BACK, TapStep("账户"), ScrollStep(P8_00_ASSET_TARGET)),
        P8_00_ASSET_TARGET,
        absent=(PRESENTATION_MULTI_CURRENCY_MARKER,),
        required=(SEEDED_ACCOUNT_NAME,),
        speech=(SpeechCheck(P8_00_ASSET_TARGET, (P8_00_ASSET_TARGET,)),),
    ),
    Screen(
        "presentation-account-flow",
        (ScrollStep(SEEDED_ACCOUNT_NAME), TapStep(SEEDED_ACCOUNT_NAME, contains=True)),
        ACCOUNT_FLOW_HEADER,
        absent=(P8_00_ASSET_TARGET,),
        required=PRESENTATION_ACCOUNT_FLOW_REQUIRED,
        speech=(SpeechCheck(SEEDED_TRANSFER_NOTE, (SEEDED_TRANSFER_NOTE,)),),
    ),
    Screen(
        "presentation-reconciliation",
        (TapStep(RECONCILIATION_TITLE), ScrollStep(SEEDED_QUEUE_MARKER)),
        SEEDED_QUEUE_MARKER,
        absent=(ACCOUNT_FLOW_HEADER,),
        required=PRESENTATION_RECONCILIATION_REQUIRED,
        speech=(SpeechCheck(SEEDED_QUEUE_MARKER, (SEEDED_QUEUE_MARKER,)),),
    ),
    # The cross-day queue: B's row line is both the marker and the scroll/speech target, so the
    # capture cannot pass on a date that is merely present elsewhere in the tree.
    Screen(
        "presentation-reconciliation-cross-day",
        (
            BACK,
            BACK,
            ScrollStep(PRESENTATION_CROSS_DAY_ACCOUNT),
            TapStep(PRESENTATION_CROSS_DAY_ACCOUNT, contains=True),
            TapStep(RECONCILIATION_TITLE),
            ScrollStep(PRESENTATION_CROSS_DAY_ROW_B),
        ),
        PRESENTATION_CROSS_DAY_ROW_B,
        absent=(PRESENTATION_CROSS_DAY_UTC_ERROR, ACCOUNT_FLOW_HEADER),
        required=(PRESENTATION_CROSS_DAY_QUEUE_COUNT, PRESENTATION_CROSS_DAY_ROW_A),
        speech=(
            SpeechCheck(
                PRESENTATION_CROSS_DAY_ROW_B,
                (PRESENTATION_CROSS_DAY_DATE_B, PRESENTATION_CROSS_DAY_NOTE_B, PRESENTATION_CROSS_DAY_AMOUNT_B),
            ),
        ),
    ),
    Screen(
        "presentation-catalog-management",
        (
            BACK,
            BACK,
            ScrollStep(PRESENTATION_MANAGEMENT_TITLE),
            TapStep(PRESENTATION_MANAGEMENT_TITLE, contains=True),
            ScrollStep(PRESENTATION_MANAGEMENT_MARKER),
        ),
        PRESENTATION_MANAGEMENT_MARKER,
        absent=(PRESENTATION_MANAGEMENT_ABSENT,),
        required=PRESENTATION_MANAGEMENT_ACCOUNTS,
        speech=(
            SpeechCheck(
                PRESENTATION_MANAGEMENT_ACCOUNTS[0],
                (PRESENTATION_MANAGEMENT_ACCOUNTS[0],),
            ),
        ),
    ),
    # E2d: three category frames, each anchored on the row that bounds its required block — the leaf
    # row directly below the group names it requires, or the group row itself (the block comment above
    # the table derives the viewport geometry and the margin each block keeps).
    Screen(
        "presentation-catalog-categories-upper",
        (ScrollStep(PRESENTATION_CATEGORY_TRAVEL_FLIGHT),),
        PRESENTATION_CATEGORY_TRAVEL_FLIGHT,
        absent=(PRESENTATION_MANAGEMENT_ABSENT,),
        required=(
            PRESENTATION_CATEGORY_TRANSPORT,
            PRESENTATION_CATEGORY_TRAVEL,
            PRESENTATION_CATEGORY_TRAVEL_FLIGHT,
        ),
        speech=(SpeechCheck(PRESENTATION_CATEGORY_TRAVEL, (PRESENTATION_CATEGORY_TRAVEL,)),),
    ),
    Screen(
        "presentation-catalog-categories-middle",
        (ScrollStep(PRESENTATION_CATEGORY_DAILY),),
        PRESENTATION_CATEGORY_DAILY,
        absent=(PRESENTATION_MANAGEMENT_ABSENT,),
        required=(
            PRESENTATION_CATEGORY_TRANSPORT_BREAKFAST,
            PRESENTATION_CATEGORY_DAILY,
        ),
        speech=(SpeechCheck(PRESENTATION_CATEGORY_DAILY, (PRESENTATION_CATEGORY_DAILY,)),),
    ),
    Screen(
        "presentation-catalog-categories-lower",
        (ScrollStep(PRESENTATION_CATEGORY_FOOD_LUNCH),),
        PRESENTATION_CATEGORY_FOOD_LUNCH,
        absent=(PRESENTATION_MANAGEMENT_ABSENT,),
        required=(
            PRESENTATION_CATEGORY_FOOD,
            PRESENTATION_CATEGORY_FOOD_LUNCH,
        ),
        speech=(SpeechCheck(PRESENTATION_CATEGORY_FOOD, (PRESENTATION_CATEGORY_FOOD,)),),
    ),
    Screen(
        "presentation-analysis",
        (TapStep("分析"), ScrollStep(PRESENTATION_RANKING_TEXT)),
        PRESENTATION_RANKING_MARKER,
        absent=(PRESENTATION_MANAGEMENT_MARKER,),
        required=(PRESENTATION_RANKING_TEXT,),
        speech=(
            # The spoken limb claims the exact amount, not the percentage: TalkBack's reading of
            # `%` is unverified (the same reason `#`/`>` stay out of required), while the visible
            # `100.0%` share is already a required tree string on this screen.
            SpeechCheck(PRESENTATION_RANKING_TEXT, (PRESENTATION_RANKING_AMOUNT,)),
        ),
    ),
    Screen(
        "presentation-calendar-empty",
        (
            TapStep("首页"),
            TapStep(PRESENTATION_CALENDAR_EMPTY_TITLE),
            TapStep("‹"),
            TapStep("‹"),
            ScrollStep(PRESENTATION_CALENDAR_EMPTY_MARKER),
        ),
        PRESENTATION_CALENDAR_EMPTY_MARKER,
        absent=(PRESENTATION_RANKING_MARKER,),
        required=(PRESENTATION_CALENDAR_EMPTY_TITLE,),
        visible_absent=PRESENTATION_CALENDAR_EMPTY_VISIBLE_ABSENT,
        speech=(SpeechCheck(PRESENTATION_CALENDAR_EMPTY_MARKER, (PRESENTATION_CALENDAR_EMPTY_MARKER,)),),
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
            # The Android analysis page keeps the independent report behind 更多分析 (D-369 B,
            # spec section 7.1). At 1.3x the toggle sits below the first screen, so the step first
            # scrolls the labelled switch into view, taps it once, then scrolls the report header
            # on screen (QR-A4) and the exact net-expense line, so the frame cannot photograph an
            # empty fold and still pass a tree-only check.
            (TapStep("分析"), ScrollStep("更多分析"), TapStep("更多分析"), ScrollStep(P806_REPORT_TITLE), ScrollStep(facts.net_expense_text())),
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
    "empty": Profile("empty", "账本为空", EMPTY_SCREENS, talkback=True),
    "seeded": Profile(
        "seeded", SEEDED_HOME_COUNT, SEEDED_SCREENS, seeded=True, talkback=True
    ),
    "refund": Profile(
        "refund", SEEDED_HOME_COUNT, REFUND_SCREENS, seeded=True, talkback=True
    ),
    # The screens are placeholders here: run/gate replace them with `p806_screens` built from the
    # pushed seed, so a static literal can never drift out of the evidence.
    "p806": Profile("p806", SEEDED_HOME_COUNT, P806_SCREENS, seeded=True, talkback=True),
    # D-369 E1: the presentation profile rides the dedicated P369 seed (D1: the eight scenarios are
    # physically unreachable on the P8-01 ledger shape) and adds the ui_mode switch; it is an
    # independent target, not a replacement for any existing profile.
    PRESENTATION_PROFILE_NAME: Profile(
        PRESENTATION_PROFILE_NAME,
        PRESENTATION_HOME_COUNT,
        PRESENTATION_SCREENS,
        seeded=True,
        talkback=True,
    ),
}

DYNAMIC_PROFILES = frozenset({"p806"})


def records_talkback_evidence(profile: Profile) -> bool:
    """Whether this profile writes the separate top-level TalkBack record (P8-07 batch D, D4).

    The dynamic p806 profile already carries its TalkBack block inside `host["p806"]` (batch 6) and
    that block keeps its exact shape, so only the other talkback profiles record the version, the
    API level and their per-target windows under `host["talkback"]`.
    """
    return profile.talkback and profile.name not in DYNAMIC_PROFILES


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


def gate_evidence_plan_problems(
    profile_name: str,
    screens: tuple[Screen, ...] | None = None,
    evidence_plan: tuple[str, ...] | None = None,
    scenario_stems: tuple[tuple[str, tuple[str, ...]], ...] | None = None,
) -> list[str]:
    """The gate's re-derivation of the plan-to-screen mapping for the presentation profile.

    Plan section 2.4 asks the mapping to be asserted rather than trusted: every scenario name is
    accounted for exactly once and in the plan's order, every scenario resolves to at least one
    stem of this profile's screen table, no stem stands for two scenarios, and every stem of the
    D7 management page's capture set ([PRESENTATION_MANAGEMENT_STEMS]) is present — the accounts
    frame plus the three category viewports the page cannot fit into one frame. There is no deferred
    branch: a scenario that names a stem this profile does not capture is red, so dropping a screen
    can never quietly leave a scenario unproven. A non-presentation profile has no mapping to
    check. The parameters default to the module constants so the unit test can feed synthetic
    tables without touching the real configuration.
    """
    if profile_name != PRESENTATION_PROFILE_NAME:
        return []
    screens = PRESENTATION_SCREENS if screens is None else screens
    evidence_plan = PRESENTATION_EVIDENCE_PLAN if evidence_plan is None else evidence_plan
    scenario_stems = PRESENTATION_SCENARIO_STEMS if scenario_stems is None else scenario_stems
    problems: list[str] = []
    names = [name for name, _ in scenario_stems]
    if names != list(evidence_plan):
        problems.append(f"the scenario mapping names {names!r}, PRESENTATION_EVIDENCE_PLAN names {list(evidence_plan)!r}")
    stems = {screen.stem for screen in screens}
    owner: dict[str, str] = {}
    for name, mapped in scenario_stems:
        if name not in evidence_plan:
            problems.append(f"scenario {name!r} is mapped but is not in PRESENTATION_EVIDENCE_PLAN")
            continue
        if not mapped:
            problems.append(f"scenario {name!r} maps no screen")
        for stem in mapped:
            if stem in owner:
                problems.append(f"stem {stem!r} is mapped to both {owner[stem]!r} and {name!r}")
            owner[stem] = name
            if stem not in stems:
                problems.append(f"scenario {name!r} maps {stem!r}, which this profile does not capture")
    for stem in PRESENTATION_MANAGEMENT_STEMS:
        if stem not in stems:
            problems.append(f"the D7 management screen stem {stem} is absent")
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
    # D-369 A: the platform night-mode state applied before launch and recorded in host.json. The
    # default keeps the hosted AVD's boot state (light) every existing profile already ran under.
    ui_mode: str = DEFAULT_UI_MODE
    screenshots: list[str] = field(default_factory=list)
    dumps: list[str] = field(default_factory=list)
    # Monotonic id for the probe's host-command channel: every focus command needs a fresh value,
    # because the probe executes only a value it has not already seen.
    talkback_sequence: int = 0
    # The image's own TalkBackService component (empty when the image does not ship one).
    talkback_component: str = ""
    # The gesture sequence tried by the LAST prompt-guard call. The shared gesture file is
    # overwritten by every call, so a caller that keeps one failure's record reads it from here.
    permission_dialog_gestures: tuple[str, ...] = ()
    # The image's exported preferences activity, discovered from its package dump.
    talkback_prefs_activity: str = P806_TALKBACK_PREFS_ACTIVITY
    # The logcat read that carried the probe's own `service connected` acknowledgement for the
    # current bind. `enable_talkback` clears the buffer just before binding, so this read IS the
    # whole buffer of that connection — the one place the probe's `service-info` line exists — and a
    # screen's window artifact keeps that line as a comment (P8-07 r7 D4).
    probe_connection_log: str = ""
    # The Accessibility Suite notification-prompt guard: on for every TalkBack profile (p806, and
    # since P8-07 batch D the screenshot profiles (empty/seeded/refund plus presentation)), so the prompt is cleared wherever the run
    # drives TalkBack.
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

    def record_talkback_version(self) -> dict[str, str]:
        """Read the image's TalkBack versionName and API level; keep the raw answer as the artifact.

        D4: the Q3 evidence must name the TalkBack the run actually spoke through. The version comes
        from the same `dumpsys package` the component discovery reads, and the API level from the
        device's own property; both are written into one raw artifact, so the gate re-derives them
        from the device answer instead of trusting host.json.
        """
        dump = self.adb("shell", "dumpsys", "package", P806_TALKBACK_PACKAGE, best_effort=True)
        api_level = self.adb("shell", "getprop", "ro.build.version.sdk", best_effort=True).strip()
        (self.evidence / TALKBACK_VERSION_ARTIFACT).write_text(
            talkback_version_artifact(dump, api_level), encoding="utf-8"
        )
        return {"version": package_version_name(dump), "api_level": api_level}

    def attempt_trace(self) -> tuple[str, str]:
        """D4: the per-combination best-effort atrace; `(status, unavailable_reason)`.

        `atrace -t 5 gfx view sched freq` is attempted once per presentation combination. A managed
        AVD can refuse it (tracing permissions, a read-only tracefs, or a build without the
        categories), and `adb(..., timeout=30)` raises `subprocess.TimeoutExpired` rather than the
        rig's own error on a hung device. EVERY failure mode is therefore converted into
        `trace_unavailable`: D4 forbids a trace failure from reaching the verdict or aborting the
        run. The raw output is kept as `trace.txt` only when the attempt produced one.
        """
        try:
            output = self.adb(
                "shell", "atrace", "-t", "5", "gfx", "view", "sched", "freq", timeout=30, best_effort=True
            )
            stripped = output.strip()
            if not stripped:
                return "", "atrace produced no output (the device refused the trace or the categories are unavailable)"
            (self.evidence / "trace.txt").write_text(output, encoding="utf-8")
            error_lines = [
                line for line in stripped.splitlines() if line.strip().lower().startswith(("error", "cannot", "unable"))
            ]
            if error_lines:
                return "", error_lines[0].strip()[:200]
            return "available", ""
        except Exception as error:  # noqa: BLE001 - D4: a trace failure must never abort the run
            return "", f"{type(error).__name__}: {error}"[:200]

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

        The read that proves the connection is kept on the instance (`probe_connection_log`): the
        probe logs `service-info eventTypes=…` immediately before `service connected`, so this very
        read already carries this bind's mask, and a screen's artifact can keep it without a second
        device round-trip (P8-07 r7 D4).
        """
        deadline = time.monotonic() + timeout_s
        last_log = ""
        last_dump = ""
        while time.monotonic() < deadline:
            last_log = self.logcat_tail()
            if self._probe_connected(last_log):
                self.probe_connection_log = last_log
                return True
            last_dump = self.accessibility_dumpsys()
            time.sleep(1)
        (self.evidence / "readiness-logcat.txt").write_text(last_log + "\n", encoding="utf-8")
        (self.evidence / "readiness-accessibility.txt").write_text(
            last_dump + "\n", encoding="utf-8"
        )
        return False

    def wait_accessibility_disabled(self, timeout_s: int = 30) -> bool:
        """Wait until neither the probe nor TalkBack is bound.

        Both services are matched by their API 36 bound-service label (package name as fallback):
        an entry still in the Bound services section means the teardown has not reached the device
        yet, and a following screen would enable TalkBack on top of the old binding.
        """
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            bound = self._bound_accessibility_section(self.accessibility_dumpsys())
            if not bound:
                time.sleep(1)
                continue
            if not accessibility_services_bound(bound):
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
        bind_failures: list[str] = []
        bind_gestures: tuple[str, ...] = ()
        while time.monotonic() < deadline:
            # Enabling TalkBack starts the service that raises its notification prompt. Clear it in
            # these first seconds, before any focus command exists, or the probe's active window is
            # the dialog and the command reports found=false. This clear is BEST EFFORT (P8-07
            # batch D r6): a prompt that outlives its three gestures is recorded on disk and the
            # wait continues. The authority is the screen's own settle after the readiness gate,
            # which re-reads the same `notification_permission_dialog_identity` tree — the two never
            # disagree about what the prompt is, only about when it is fatal.
            try:
                self.handle_notification_permission_dialog()
            except SampleError as error:
                bind_failures.append(str(error))
                bind_gestures = self.permission_dialog_gestures
            dump = self.accessibility_dumpsys()
            if talkback_bound_service(self._bound_accessibility_section(dump)):
                bound = True
                break
            time.sleep(1)
        (self.evidence / "talkback-accessibility.txt").write_text(
            self._bound_accessibility_section(dump) + "\n", encoding="utf-8"
        )
        bind_artifact = f"notification-dialog-bind-{stem}.txt"
        if bind_failures:
            # `guard_failures` counts GUARD CALLS, not gestures: one call already tries the whole
            # double/single/double sequence. The sequence that belongs to the recorded failure is
            # written here, because `permission-dialog-gesture.txt` is shared and overwritten by
            # every guard call, so it cannot be read back for this failure later.
            (self.evidence / bind_artifact).write_text(
                "closed=false\n"
                f"guard_failures={len(bind_failures)}\n"
                f"gestures={','.join(bind_gestures) if bind_gestures else 'unknown'}\n"
                "gesture_artifact=permission-dialog-gesture.txt "
                "(shared; overwritten by every guard call)\n"
                "authority=the screen's post-readiness settle re-reads the same prompt identity\n"
                f"reason={bind_failures[-1]}\n",
                encoding="utf-8",
            )
        return {
            "status": "success",
            "enabled_components": enabled_components,
            "enabled_services_file": enabled_file,
            "talkback_bound": bound,
            # Additive context, never a gate input: the gate still derives red from the settle and
            # from every target's own window.
            "notification_prompt_binding": {
                "closed": not bind_failures,
                "guard_failures": len(bind_failures),
                "gestures": list(bind_gestures),
                "artifact": bind_artifact if bind_failures else "",
                "reason": bind_failures[-1] if bind_failures else "",
            },
            "reason": "" if bound else "TalkBack was enabled but is not listed as a bound service",
        }

    def save_level_attempt(
        self, attempt: int, tree: str, facts: dict[str, object]
    ) -> None:
        """Keep one log-level attempt's own frame and the facts that decide it.

        The red round could only show `talkback-prefs-dialog.xml` as the preference list itself, so
        the tap's target and the tree's VERBOSE entry are written next to that frame per attempt:
        `talkback-prefs-level-<n>.xml` is the frame, `talkback-prefs-level-<n>.txt` the two facts.
        """
        stem = f"talkback-prefs-level-{attempt}"
        self.save_tree(stem, tree)
        self.save_level_facts(attempt, facts)

    def save_level_facts(self, attempt: int, facts: dict[str, object]) -> None:
        """Write one attempt's decided facts beside the frame they were read from."""
        (self.evidence / f"talkback-prefs-level-{attempt}.txt").write_text(
            "\n".join(f"{key}={value}" for key, value in facts.items()) + "\n",
            encoding="utf-8",
        )

    def record_level_outcome(
        self, attempt_index: int, tree: str, attempt: dict[str, object]
    ) -> None:
        """Record the frame's VERBOSE entry and keep the frame plus its decided facts.

        Every outcome writes both files (QL-4): a branch that only `continue`d left the pre-tap
        frame on disk with no `outcome` line, so the artifact could not show what that attempt
        actually saw. `verbose_entry` is the frame's own fact, computed before the save on every
        branch (CONTRIBUTING: the tree's VERBOSE entry lands in the `.txt`).
        """
        attempt["verbose_entry"] = find_center(tree, P806_TALKBACK_LEVEL) is not None
        self.save_level_attempt(attempt_index, tree, attempt)

    def talkback_log_level_setup(self) -> dict[str, object]:
        """Select TalkBack's own Log output level VERBOSE through its exported settings UI.

        The persisted preference is application-private, so it is set the way the product exposes
        it: open the developer fragment by its verified intent extra, tap the row whose SUMMARY is
        one of the literal level names, choose VERBOSE from the list (literal entries), and confirm
        the framework dialog's positive button. The final developer-screen dump proves the summary
        now renders VERBOSE; no private file is copied and no preference key is typed.

        The whole tap-open-choose-confirm route is bounded at TALKBACK_LEVEL_ATTEMPTS attempts
        (r6): every attempt re-reads the preference frame and re-taps the row, and keeps its own
        tree plus the two facts the red run could not show — which node it tapped and whether the
        tree carried a VERBOSE entry — as `talkback-prefs-level-<attempt>.xml`/`.txt`. Only a route
        that fails every attempt reports `not-obtained`, so the gate's `success` + `VERBOSE`
        requirement is exactly as strict as before; the bound only removes one-gesture death.
        """
        record: dict[str, object] = {
            "status": "not-obtained",
            "value": "",
            "reason": "",
            "steps": [],
            "attempts": [],
        }
        steps: list[str] = record["steps"]  # type: ignore[assignment]
        attempts: list[dict[str, object]] = record["attempts"]  # type: ignore[assignment]
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
            last_reason = "the Log output level row was never tappable"
            for attempt_index in range(1, TALKBACK_LEVEL_ATTEMPTS + 1):
                if attempt_index > 1:
                    # "Short wait, then a forced fresh read and a fresh tap": a tap that missed has
                    # no observable cause, so the next attempt re-derives the row from a new frame
                    # instead of reusing the frame that already failed.
                    time.sleep(TALKBACK_LEVEL_RETRY_INTERVAL_S)
                attempt: dict[str, object] = {
                    "attempt": attempt_index,
                    "artifact": f"talkback-prefs-level-{attempt_index}.xml",
                }
                attempts.append(attempt)
                tree = self.dump("talkback-prefs-before")
                if talkback_level_summary_value(tree) is None:
                    # Not the preference screen: a failed attempt can leave the level list (or a
                    # confirmation dialog) above it, so BACK first and re-read.
                    self.press_back()
                    tree = self.dump("talkback-prefs-before")
                # The row is addressed by its OWN summary node, never by the first level-named node
                # in the tree: an accidentally open level list renders all seven names as rows.
                summary = talkback_level_summary_value(tree)
                center = find_talkback_level_summary(tree) if summary is not None else None
                attempt["row_center"] = list(center) if center is not None else []
                attempt["summary"] = summary
                if center is None:
                    attempt["outcome"] = "no-row"
                    self.record_level_outcome(attempt_index, tree, attempt)
                    last_reason = "the developer preferences screen exposed no Log output level row"
                    continue
                self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
                steps.append(f"attempt {attempt_index}: tap the Log output level row at {center}")
                time.sleep(2)
                opened = False
                stale = 0
                deadline = time.monotonic() + TALKBACK_LEVEL_ENTRY_TIMEOUT_S
                while time.monotonic() < deadline:
                    tree = self.dump("talkback-prefs-dialog")
                    if find_center(tree, P806_TALKBACK_LEVEL) is not None:
                        opened = True
                        break
                    stale = stale + 1 if talkback_level_summary_value(tree) is not None else 0
                    if stale >= TALKBACK_LEVEL_STALE_READS:
                        break
                    time.sleep(TALKBACK_LEVEL_ENTRY_POLL_S)
                if not opened and stale >= TALKBACK_LEVEL_STALE_READS:
                    # The preference list still renders its own summary row, so this tap did not
                    # open the list at all — the exact shape of run 45760191802's artifact. Do not
                    # swipe the preference list here: that only moves the row this attempt failed
                    # to hit, and the next attempt re-reads and re-taps it.
                    attempt["level_list_rendered"] = False
                    attempt["outcome"] = "list-not-opened"
                    self.record_level_outcome(attempt_index, tree, attempt)
                    last_reason = (
                        "the tap on the Log output level row left the preference list rendered "
                        f"({stale} consecutive reads still carry its own summary)"
                    )
                    steps.append(f"attempt {attempt_index}: {last_reason}")
                    continue
                if not opened:
                    # At 1.3x the last entry can sit below the dialog's fold; the single-choice list
                    # itself scrolls, so swipe inside it before this attempt is given up.
                    for _swipe in range(TALKBACK_LEVEL_DIALOG_SWIPES):
                        x1, y1, x2, y2 = scroll_swipe_points(tree)
                        self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
                        time.sleep(2)
                        tree = self.dump("talkback-prefs-dialog")
                        if find_center(tree, P806_TALKBACK_LEVEL) is not None:
                            opened = True
                            break
                attempt["level_list_rendered"] = opened
                if not opened:
                    attempt["outcome"] = "entry-missing"
                    self.record_level_outcome(attempt_index, tree, attempt)
                    last_reason = "the level list never rendered a VERBOSE entry"
                    continue
                center = find_center(tree, P806_TALKBACK_LEVEL)
                if center is None:
                    attempt["outcome"] = "no-entry-centre"
                    self.record_level_outcome(attempt_index, tree, attempt)
                    last_reason = "the VERBOSE entry has no tappable centre"
                    continue
                attempt["verbose_entry"] = find_center(tree, P806_TALKBACK_LEVEL) is not None
                self.save_level_attempt(attempt_index, tree, attempt)
                self.adb("shell", "input", "tap", str(center[0]), str(center[1]))
                steps.append(f"attempt {attempt_index}: select the literal VERBOSE entry")
                time.sleep(2)
                # The list change raises a confirmation dialog; its positive button is the
                # framework's `android:id/button1`, and the changed summary is the proof it
                # persisted.
                deadline = time.monotonic() + 20
                confirmed = False
                while time.monotonic() < deadline:
                    tree = self.dump("talkback-prefs-confirm")
                    button = find_resource_center(tree, "android:id/button1")
                    if button is not None:
                        self.adb("shell", "input", "tap", str(button[0]), str(button[1]))
                        confirmed = True
                        steps.append(
                            f"attempt {attempt_index}: confirm the framework dialog's positive button"
                        )
                        break
                    if talkback_level_summary_value(tree) == P806_TALKBACK_LEVEL:
                        confirmed = True
                        steps.append(
                            f"attempt {attempt_index}: the dialog closed with the level already applied"
                        )
                        break
                    time.sleep(1)
                if not confirmed:
                    attempt["outcome"] = "unconfirmed"
                    self.record_level_outcome(attempt_index, tree, attempt)
                    last_reason = "the level change showed no confirmation dialog and no new summary"
                    continue
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    tree = self.dump("talkback-prefs-after")
                    if talkback_level_summary_value(tree) == P806_TALKBACK_LEVEL:
                        attempt["outcome"] = "success"
                        record.update(
                            status="success",
                            value=P806_TALKBACK_LEVEL,
                            reason="summary shows VERBOSE",
                        )
                        # The level list's own frame stays on disk (it is the proof the tree carried
                        # a VERBOSE entry); only the decided outcome is added beside it.
                        self.save_level_facts(attempt_index, attempt)
                        return record
                    time.sleep(1)
                attempt["outcome"] = "summary-not-updated"
                self.record_level_outcome(attempt_index, tree, attempt)
                last_reason = "the developer screen's summary never rendered VERBOSE"
            raise SampleError(
                f"{last_reason} after {TALKBACK_LEVEL_ATTEMPTS} attempts ("
                + "; ".join(
                    f"attempt {entry.get('attempt')}={entry.get('outcome')}"
                    for entry in attempts
                )
                + "); see talkback-prefs-level-<attempt>.xml"
            )
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

    def p806_talkback_screen(
        self, screen: Screen, *, target_screenshots: bool
    ) -> dict[str, object]:
        """Drive one screen's ordered speech checks, each bound to its own command window.

        With `target_screenshots` (the P8-07 batch D Q3 profiles) every target's command is followed
        by one screenshot whose stem carries that target's command id, so a target's picture, its
        focus command and its utterance window are three artifacts of the same target. The dynamic
        p806 profile keeps its single per-screen screenshot set; its host record may gain read-only
        diagnostic keys (such as the enable step's `notification_prompt_binding`), and its gate
        criterion is unchanged.

        The three r4 gates sit between the enable write and the first command, in this order: the
        bounded readiness wait (TalkBack must have spoken), the bounded notification-prompt settle
        (the prompt arrives after that announcement), and one bounded retry per target inside a
        named transient. None of them can rescue a window: each target's status is still the four
        value verdict re-derived from the window that decided. Since r6 the settle is also the one
        authority on the prompt: the enable-boundary clear is best effort, and a prompt that
        outlives the settle's bound fails the screen here with the bound and the wait in its message.

        Since r7 the header also keeps the probe's connect-time `service-info` line as a `#` comment
        (D4), from the same logcat read that proved this bind, so a `seen=0` transcript can separate
        a missing event mask from a platform that posted nothing. It is a comment and nothing else:
        no stage, fragment or verdict reads it.
        """
        artifact_name = f"talkback-{screen.stem}.txt"
        # The `#` header lines stay their own block so D4's connect-time `service-info` comment can
        # sit after them on EVERY outcome — including the failure paths, whose last header line is
        # `# enable failed:`. A comment there can never be a stage: `_ULA11Y_LINE` anchors on the
        # logcat brief column, so the window is derived from the probe's own lines only.
        header: list[str] = [f"# stem: {screen.stem}"]
        lines: list[str] = []
        # The comment below must describe THIS screen's bind, never the previous screen's: a screen
        # whose enable never reaches the probe keeps `absent` instead of borrowing an older mask.
        self.probe_connection_log = ""
        enabled: dict[str, object] = {
            "status": "not-obtained",
            "reason": "",
            "enabled_components": [],
            "enabled_services_file": f"talkback-enabled-{screen.stem}.txt",
        }
        driven: list[tuple[SpeechCheck, dict[str, object] | None, str]] = []
        try:
            enabled = self.enable_talkback(screen.stem)
            header.append(
                f"# enable: {enabled.get('status')} components={enabled.get('enabled_components')} "
                f"bound={enabled.get('talkback_bound')} {enabled.get('reason', '')}".rstrip()
            )
            if enabled.get("status") != "success":
                raise SampleError(str(enabled.get("reason") or "TalkBack could not be enabled"))
            binding = enabled.get("notification_prompt_binding")
            if isinstance(binding, dict) and not binding.get("closed", True):
                # The enable-boundary clear is best effort (r6): it did not close the prompt, so the
                # frame it left is named here and the settle below is what decides.
                header.append(f"# prompt-bind: closed=false artifact={binding.get('artifact')}")
            # T1: no command may reach the framework before TalkBack itself speaks. T2: the prompt
            # that follows that announcement is cleared here, in bounded rounds, and only then does
            # the first command go out — the old single clear sat before the announcement existed.
            ready = self.wait_talkback_ready(screen.stem)
            header.append(
                f"# ready: waited={ready.get('waited_s')}s polls={ready.get('polls')} "
                f"fragments={ready.get('fragments')}"
            )
            settled = self.settle_notification_permission_dialog(screen.stem)
            settled_quiet = settled.get("quiet_after_round")
            header.append(
                f"# notification-prompt: clears={settled.get('clears')} quiet_after_round="
                f"{settled_quiet if settled_quiet is not None else 'still-present'}"
            )
            if settled_quiet is None:
                # The settle's own observation is the authority (r6 T1): the prompt outlived every
                # round, so no command is driven into a window it steals. The message names the
                # bound and the growing wait, because that is what distinguishes this red from the
                # old single-gesture failure.
                waits = ", ".join(
                    f"{NOTIFICATION_DIALOG_SETTLE_INTERVAL_S * round_index:g} s"
                    for round_index in range(1, NOTIFICATION_DIALOG_SETTLE_ROUNDS)
                )
                settle_artifact = settled.get("artifact") or f"notification-dialog-{screen.stem}.txt"
                raise SampleError(
                    "the Accessibility Suite notification prompt was still present after "
                    f"{NOTIFICATION_DIALOG_SETTLE_ROUNDS} rounds (waits {waits}, "
                    f"{NOTIFICATION_DIALOG_QUIET_READS} consecutive quiet reads required): "
                    f"{settled.get('reason') or 'the last round did not close it'}; see "
                    f"{settle_artifact}"
                )
            polls = ready.get("polls")
            readiness_delayed = isinstance(polls, int) and polls > 1
            for index, check in enumerate(screen.speech):
                # D-369 E2f: a command is a WHOLE four-stage window under one fresh id, and the
                # bounded second attempt below re-runs all of it. `attempt` is the number of
                # commands already spent on this check, so the loop spends at most
                # TALKBACK_COMMAND_ATTEMPTS commands and the LAST window is the one recorded.
                attempt = 0
                while True:
                    self.talkback_sequence += 1
                    command_id = self.talkback_sequence
                    window = self.talkback_focus(check.target, command_id)
                    lines.append(f"# target: {check.target}")
                    lines.append(f"# command: {command_id}")
                    lines.extend(str(line) for line in window["lines"])
                    # T3: one bounded retry, only inside a named transient, and always as a WHOLE
                    # command under a fresh id — the four-stage window is never split. The rejected
                    # window stays in the artifact; `# retry=1` names the command it belongs to.
                    retry_reason = ""
                    retry_waits_for_engine = False
                    if _window_focus_rejected(window):
                        cleared_now = self.handle_notification_permission_dialog()
                        retry_reason = focus_retry_reason(
                            window,
                            first_command=index == 0,
                            dialog_cleared=bool(settled.get("clears")) or cleared_now,
                            readiness_delayed=readiness_delayed,
                        )
                    if not retry_reason:
                        retry_reason = talkback_silent_retry_reason(window)
                        retry_waits_for_engine = bool(retry_reason)
                    if not retry_reason or attempt + 1 >= TALKBACK_COMMAND_ATTEMPTS:
                        break
                    lines.append(
                        f"# retry={attempt + 1} previous-command: {command_id} reason: {retry_reason}"
                    )
                    if retry_waits_for_engine:
                        # The window produced no utterance, so the engine may still be initialising:
                        # wait, bounded, for a spoken fragment to be the last speech event, then
                        # re-issue. The wait never decides anything — the window that follows does.
                        retry_ready = self.wait_talkback_utterance_ready()
                        lines.append(
                            f"# retry-ready: ready={str(bool(retry_ready.get('ready'))).lower()} "
                            f"waited={retry_ready.get('waited_s')}s "
                            f"polls={retry_ready.get('polls')} "
                            f"fragments={retry_ready.get('fragments')}"
                        )
                    attempt += 1
                # D5: the picture is taken AFTER the target's own command completed, and its stem
                # carries the command id that decided, so the gate can bind the png to this window.
                shot = ""
                if target_screenshots:
                    shot_stem = f"talkback-{screen.stem}-cmd-{command_id}"
                    self.screenshot(shot_stem)
                    shot = f"{shot_stem}.png"
                driven.append((check, window, shot))
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
            header.append(f"# enable failed: {error}")
        finally:
            self.disable_talkback_accessibility()
            self.wait_accessibility_disabled()
        for check in screen.speech[len(driven) :]:
            driven.append((check, None, ""))
        artifact = "\n".join(
            header + [service_info_comment(self.probe_connection_log)] + lines
        ) + "\n"
        (self.evidence / artifact_name).write_text(artifact, encoding="utf-8")
        checks = []
        for check, window, shot in driven:
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
            entry: dict[str, object] = {
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
            if target_screenshots:
                # D5: the png this target's own command produced. Only the Q3 profiles capture one,
                # so the dynamic p806 check record keeps its exact batch-6 shape.
                entry["screenshot"] = shot
            checks.append(entry)
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

    def apply_ui_mode(self) -> str:
        """Apply and read back the requested light/dark platform mode (D-369 A)."""
        value = ui_mode_night_value(self.ui_mode)
        self.adb("shell", "cmd", "uimode", "night", value)
        # The platform re-reads the mode on the next configuration change; restart the app so the
        # first launch frame already renders the requested scheme.
        self.adb("shell", "am", "force-stop", APP_PACKAGE)
        time.sleep(2)
        raw = self.adb("shell", "cmd", "uimode", "night")
        problem = ui_mode_readback_problem(raw, self.ui_mode)
        if problem is not None:
            raise SampleError(f"ui_mode {self.ui_mode!r} did not take effect: {problem}")
        return self.ui_mode

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

        On the AVD this rig boots, TalkBack raises the API 33+ notification prompt after it starts
        on every profile whose run drives TalkBack (all four since P8-07 batch D); left up it
        intercepts focus/scroll input. Only the exact permissioncontroller grant dialog whose
        message is the Accessibility Suite notification prompt is touched, and denying an
        unnecessary notification permission is the least-privilege action. The enabled services are
        read at click time and choose the first gesture; a prompt that survives it is retried with
        the alternating gesture, each attempt followed by its own bounded readback, and only a
        prompt that outlives every attempt raises.
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
        # (prepare disables them, enable_talkback adds probe plus TalkBack before the prompt shows),
        # and the raw answer is what the gesture diagnostic records.
        enabled_services = self.adb(
            "shell", "settings", "get", "secure", "enabled_accessibility_services", best_effort=True
        )
        gestures = permission_deny_gesture_sequence(
            permission_deny_gesture(enabled_services, self.talkback_component)
        )
        attempts: list[tuple[str, bool]] = []
        closed = False
        for gesture in gestures:
            self.tap_permission_deny(center, gesture)
            closed = self.read_permission_dialog_closed()
            attempts.append((gesture, closed))
            if closed:
                break
        self.permission_dialog_gestures = tuple(gesture for gesture, _closed in attempts)
        self.write_permission_dialog_gesture(enabled_services, attempts)
        if not closed:
            raise SampleError(
                "the Accessibility Suite notification prompt did not close after "
                f"{len(attempts)} gestures "
                f"({', '.join(gesture for gesture, _closed in attempts)}); "
                "see permission-dialog.xml and permission-dialog-after.xml "
                "and permission-dialog-gesture.txt"
            )
        (self.evidence / "notification-dialog.txt").write_text(
            "dismissed the Android Accessibility Suite POST_NOTIFICATIONS prompt (deny)\n",
            encoding="utf-8",
        )
        return True

    def tap_permission_deny(self, center: tuple[int, int], gesture: str) -> None:
        """Send one deny press as a single `adb shell` call so a double tap stays on-device."""
        self.adb("shell", *permission_deny_tap_command(center, gesture))

    def read_permission_dialog_closed(self) -> bool:
        """Read the prompt back until it is gone, bounded; the last frame stays on disk.

        A prompt still rendered after every readback is not a hard failure on its own — the caller
        retries the other gesture — so this reports the observation and leaves the verdict to it.
        """
        for delay in permission_dialog_readback_delays():
            time.sleep(delay)
            after = self.dump("permission-dialog-after")
            if not notification_permission_dialog_identity(after):
                return True
        return False

    def write_permission_dialog_gesture(
        self, enabled_services: str, attempts: list[tuple[str, bool]]
    ) -> None:
        """Keep the click-time services and every gesture/readback outcome as run evidence.

        `enabled_accessibility_services` is the raw `settings get` answer, never a re-derivation:
        a stale or `null` value is exactly what the diagnostic has to expose when the gesture that
        "should" work does not.
        """
        lines = [f"enabled_accessibility_services={enabled_services.strip() or 'null'}"]
        lines.extend(
            f"attempt={index} gesture={gesture} closed={'true' if closed else 'false'}"
            for index, (gesture, closed) in enumerate(attempts, start=1)
        )
        (self.evidence / "permission-dialog-gesture.txt").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )

    def wait_talkback_ready(self, stem: str) -> dict[str, object]:
        """Wait, bounded, for TalkBack itself to speak before any focus command is issued.

        The enable write, its read-back, the bound listing and the probe's acknowledgement all only
        say the services were asked to run. The first real-device round shows what that misses: the
        framework refused the probe's first focus request (accepted=false) while TalkBack was still
        announcing itself, and the notification prompt then owned the active window. The one
        host-visible proof that TalkBack has taken over is its own speech controller logging a
        fragment after that enable write, so the first command waits for it. A timeout fails closed
        — the seconds waited, the number of reads and the window's tail are named, and the pass does
        not continue into a command that could only report a refusal. D-369 E2f also requires the
        LAST speech event to be a spoken fragment (`talkback_readiness`): a dropped utterance after
        the startup announcement means the engine is still initialising, and the command that would
        follow can only record a silent window.
        """
        artifact_name = f"talkback-readiness-{stem}.txt"
        started = time.monotonic()
        deadline = started + TALKBACK_READY_TIMEOUT_S
        log_text = ""
        fragments: list[str] = []
        polls = 0
        while True:
            polls += 1
            log_text = self.logcat_tail()
            ready, fragments = talkback_readiness(log_text)
            if ready or time.monotonic() >= deadline:
                break
            time.sleep(TALKBACK_READY_POLL_INTERVAL_S)
        waited_s = time.monotonic() - started
        (self.evidence / artifact_name).write_text(
            talkback_readiness_artifact(waited_s, polls, fragments, log_text, ready),
            encoding="utf-8",
        )
        if not ready:
            tail = "\n".join(log_text.splitlines()[-TALKBACK_READY_TAIL_LINES:])
            if fragments:
                raise SampleError(
                    f"TalkBack's last speech event within {waited_s:.1f} s of being enabled was a "
                    "dropped utterance (TTS is not ready), so no focus request can be driven yet; "
                    f"see {artifact_name}; the window ended with:\n{tail}"
                )
            raise SampleError(
                f"TalkBack logged no utterance within {waited_s:.1f} s of being enabled "
                f"({polls} logcat reads), so no focus request can be driven yet; see "
                f"{artifact_name}; the window ended with:\n{tail}"
            )
        return {
            "status": "ready",
            "waited_s": round(waited_s, 1),
            "polls": polls,
            "fragments": len(fragments),
            "artifact": artifact_name,
        }

    def wait_talkback_utterance_ready(self) -> dict[str, object]:
        """Wait, bounded, for the engine to be past its not-ready burst, then let the retry run.

        The silent-window retry (D-369 E2f) is the only caller. Unlike `wait_talkback_ready` this
        never raises: a bound that expires is an observation, and the re-issued command's own window
        still decides the check. The wait is short by design — on run 46074246784 the engine spoke
        the pending announcement seconds after the dropped one, and the command after it was
        accepted — so it can never become a way to sit out a genuinely silent screen.
        """
        started = time.monotonic()
        deadline = started + TALKBACK_RETRY_READY_TIMEOUT_S
        polls = 0
        ready = False
        fragments: list[str] = []
        while True:
            polls += 1
            ready, fragments = talkback_readiness(self.logcat_tail())
            if ready or time.monotonic() >= deadline:
                break
            time.sleep(TALKBACK_RETRY_READY_POLL_INTERVAL_S)
        return {
            "ready": ready,
            "waited_s": round(time.monotonic() - started, 1),
            "polls": polls,
            "fragments": len(fragments),
        }

    def settle_notification_permission_dialog(self, stem: str) -> dict[str, object]:
        """Clear the prompt in bounded rounds until it stays away for consecutive reads.

        The prompt arrives AFTER TalkBack's startup announcement, so the single clear at the enable
        boundary cannot cover it: on the first real-device round it was raised inside the first
        command's own speech window and cleared only by the next screen's step guard. Each round
        re-reads the panel; a round that finds it denies it through the existing exact-dialog guard,
        and the loop ends after NOTIFICATION_DIALOG_QUIET_READS consecutive reads render none —
        bounded at NOTIFICATION_DIALOG_SETTLE_ROUNDS, with the wait growing every round. A prompt
        that outlives one round's three gestures is not fatal on its own (r6): the round is recorded
        as `still-present` and the next round re-reads and retries after a longer wait, so the pass
        pays for the whole bound instead of dying on one gesture miss. Only a prompt that outlives
        every round is reported as `quiet_after_round=still-present`, and the screen's driver turns
        that into the pass's own red.
        """
        artifact_name = f"notification-dialog-{stem}.txt"
        rounds: list[tuple[int, str]] = []
        clears = 0
        quiet = 0
        quiet_after: int | None = None
        last_reason = ""
        for round_index in range(1, NOTIFICATION_DIALOG_SETTLE_ROUNDS + 1):
            state = "false"
            try:
                if self.handle_notification_permission_dialog():
                    state = "true"
            except SampleError as error:
                # The prompt is still on screen and this round's gestures could not close it. That
                # is an observation, not a verdict: the gesture record stays on disk from the
                # guard itself, and the next round reads the panel again.
                state = "still-present"
                last_reason = str(error)
            rounds.append((round_index, state))
            if state == "false":
                quiet += 1
            else:
                quiet = 0
                if state == "true":
                    clears += 1
            if quiet >= NOTIFICATION_DIALOG_QUIET_READS:
                quiet_after = round_index
                break
            if round_index < NOTIFICATION_DIALOG_SETTLE_ROUNDS:
                time.sleep(NOTIFICATION_DIALOG_SETTLE_INTERVAL_S * round_index)
        lines = [
            f"# stem: {stem}",
            f"clears={clears}",
            f"quiet_reads={quiet}",
            "quiet_after_round="
            + (str(quiet_after) if quiet_after is not None else "still-present"),
            f"space={NOTIFICATION_DIALOG_SETTLE_INTERVAL_S:g} s "
            f"rounds={NOTIFICATION_DIALOG_SETTLE_ROUNDS} "
            f"quiet_required={NOTIFICATION_DIALOG_QUIET_READS}",
        ]
        lines.extend(f"round={index} prompt={state}" for index, state in rounds)
        (self.evidence / artifact_name).write_text("\n".join(lines) + "\n", encoding="utf-8")
        return {
            "clears": clears,
            "quiet_reads": quiet,
            "quiet_after_round": quiet_after,
            "rounds": len(rounds),
            "artifact": artifact_name,
            "reason": last_reason,
        }

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
    # The profile-level switch (D3): p806 is unchanged, and the screenshot profiles (empty/seeded/refund plus presentation) now take
    # the same probe / notification / teardown route. `records_talkback_evidence` owns the separate
    # top-level record; p806 keeps its own block under host["p806"].
    talkback = profile.talkback
    target_screenshots = records_talkback_evidence(profile)
    evidence = Path(args.evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    # D4 read-only diagnostic: the presentation sweep records how long one combination actually
    # took (sampler.py's `sweep_duration_seconds`), so the E2 registry can state measured time
    # instead of assuming the per-combination budget.
    run_started = time.monotonic()
    sampler = Sampler(
        adb_serial=args.serial or None,
        evidence=evidence,
        font_scale=args.font_scale,
        avd_name=args.avd_name,
        ui_mode=args.ui_mode,
    )
    sampler.require_owned_avd()
    # The notification prompt is an Android Accessibility Suite behavior that appears whenever
    # TalkBack starts, so every talkback profile enables its handler — and only on this session's
    # owned AVD (D6: the screenshot profiles (empty/seeded/refund plus presentation) now drive TalkBack too).
    sampler.notification_dialog_guard = talkback

    def dismiss_notification_prompt(context: str) -> None:
        """Deny the Accessibility Suite prompt, naming what was under way when it fails."""
        try:
            sampler.handle_notification_permission_dialog()
        except SampleError as error:
            raise SampleError(f"{context}: clearing the notification prompt: {error}") from error

    sampler.adb("shell", "mkdir", "-p", Sampler.DUMP_DIR, best_effort=True)
    applied = sampler.apply_font_scale()
    applied_ui_mode = sampler.apply_ui_mode()
    sampler.adb("install", "-r", args.apk, timeout=180)
    talkback_capability: dict[str, object] | None = None
    talkback_log_level: dict[str, object] | None = None
    prior_accessibility: dict[str, str] | None = None
    if talkback:
        if not Path(args.test_apk).is_file():
            raise SampleError(
                f"profile {profile.name!r} drives the focus probe from {args.test_apk!r}, which is "
                "missing; build :android-app:assembleDebugAndroidTest before the emulator session"
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
            "visible_absent": list(screen.visible_absent),
        }
        problems = screen_problems(tree, screen)
        if problems:
            raise SampleError(
                f"screen {screen.stem}: {'; '.join(problems)} at font_scale {applied:g}"
            )
        # The screenshot/dump limbs above are already on disk and independent of this leg: the
        # speech pass only moves accessibility focus on the live screen. The profile switch decides
        # whether the pass exists at all, and the screen's own tuple decides whether it speaks.
        if talkback and screen.speech:
            talkback_screens[screen.stem] = sampler.p806_talkback_screen(
                screen, target_screenshots=target_screenshots
            )

    if talkback:
        sampler.disable_talkback_accessibility()
        for key in (
            SETTING_A11Y_COMMAND,
            SETTING_A11Y_PREFIX,
            SETTING_A11Y_LIMIT,
            SETTING_A11Y_CLICK,
        ):
            sampler.adb("shell", "settings", "delete", "global", key, best_effort=True)
    # D4: one best-effort trace attempt per PRESENTATION combination only (D4 says 每组合 of the
    # sweep; the other profiles keep their existing device-call contract). It never changes the
    # verdict; it only records whether this run could leave the E2 registry's R9 with a trace or a
    # reason.
    trace_status, trace_unavailable = "", ""
    if profile.name == PRESENTATION_PROFILE_NAME:
        trace_status, trace_unavailable = sampler.attempt_trace()

    def apk_record(path: str) -> dict[str, str]:
        return {"path": str(path), "sha256": sha256_file(Path(path))}

    host = {
        "sha": args.sha,
        "profile": profile.name,
        "avd_name": args.avd_name,
        "font_scale": applied,
        "ui_mode": applied_ui_mode,
        "screens": [screen.stem for screen in screens],
        "screenshots": sampler.screenshots,
        "dumps": sampler.dumps,
        "observations": observations,
        "artifacts": {"app_apk": apk_record(args.apk)},
    }
    if talkback:
        host["artifacts"]["test_apk"] = apk_record(args.test_apk)
    if profile.name == PRESENTATION_PROFILE_NAME:
        if trace_unavailable:
            host["trace_unavailable"] = trace_unavailable
        else:
            host["trace"] = trace_status
        # D4 read-only diagnostic (the gate never reads it): the measured wall time of this one
        # combination, so E2 can state what the 2x2 sweep actually costs.
        host["sweep_duration_seconds"] = round(time.monotonic() - run_started, 1)
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
    if target_screenshots:
        # P8-07 batch D (Q3): the screenshot profiles (empty/seeded/refund plus presentation) name the TalkBack they spoke through —
        # the versionName and API level re-parsed from the raw device artifact — and keep every
        # target's window plus its own png under this record. The dynamic p806 profile writes no
        # such record and no extra artifact, so its evidence contract stays exactly as batch 6 left
        # it.
        version = sampler.record_talkback_version()
        host["talkback"] = {
            "package": P806_TALKBACK_PACKAGE,
            "version": version["version"],
            "api_level": version["api_level"],
            "version_artifact": TALKBACK_VERSION_ARTIFACT,
            "device_service": sampler.talkback_component,
            "prior_accessibility": prior_accessibility,
            "log_level": talkback_log_level,
            "screens": talkback_screens,
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


def talkback_screen_problems(
    talkback: dict[str, object],
    screens: tuple[Screen, ...],
    evidence: Path,
    derived_from: str,
    device_service: str,
    *,
    require_screenshots: bool = False,
) -> list[str]:
    """Re-derive every speech screen's enable record and each target's own accepted window.

    Each check is bound to its own recorded command id and re-derived from that id's isolated
    `speech-window-begin`/accepted/`speech-window-end` window in the raw artifact. The required
    values come from `derived_from`'s own table (the pushed seed, or the frozen profile), never from
    host.json: an evidence file that narrowed or swapped a target's requirements fails. Any class
    other than success (未取得 / 片段缺失 / 金额错误) fails the gate. The image capability record is
    context, not a substitute for speech. With `require_screenshots` every ordered target must also
    carry its own png, named by that target's command id, at or above MIN_SCREENSHOT_BYTES.
    """
    screen_records = talkback.get("screens")
    if not isinstance(screen_records, dict):
        return ["host.json records no per-screen TalkBack evidence"]
    problems: list[str] = []
    seen_ids: set[int] = set()
    for screen in screens:
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
                f"TalkBack checks, the {derived_from} derives {len(screen.speech)}"
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
                    f"the {derived_from} derives {expected.target!r}"
                )
            if check.get("required") != list(expected.required):
                problems.append(
                    f"{screen.stem}: target {target!r} records requirements "
                    f"{check.get('required')!r}, the {derived_from} derives {list(expected.required)!r}"
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
            if require_screenshots:
                # D5: the png's own name carries this target's command id, and the bytes on disk are
                # re-measured here — a host that points at another screen's picture, or at a blank
                # frame, cannot satisfy it.
                expected_shot = f"talkback-{screen.stem}-cmd-{command_id}.png"
                recorded_shot = check.get("screenshot")
                if recorded_shot != expected_shot:
                    problems.append(
                        f"{screen.stem}: target {target!r} records screenshot {recorded_shot!r}, "
                        f"the target id names {expected_shot!r}"
                    )
                shot_path = evidence / expected_shot
                if not shot_path.is_file():
                    problems.append(
                        f"{screen.stem}: target {target!r} has no screenshot {expected_shot!r}"
                    )
                elif shot_path.stat().st_size < MIN_SCREENSHOT_BYTES:
                    problems.append(
                        f"{screen.stem}: target {target!r} screenshot {expected_shot!r} is blank "
                        f"({shot_path.stat().st_size} bytes)"
                    )
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


def talkback_device_problems(talkback: dict[str, object]) -> tuple[list[str], str]:
    """The run-level TalkBack identity: package, device service component and VERBOSE route.

    Shared by the dynamic p806 record and the screenshot profiles' (empty/seeded/refund plus presentation) record, so both gates
    reject the same substitutions: another package, a service that is not a TalkBackService, or a
    log level that cannot produce the fragment line the speech limb reads.
    """
    problems: list[str] = []
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
    return problems, device_service


def p806_gate_problems(
    host: dict[str, object], facts: P806SeedFacts, evidence: Path
) -> list[str]:
    """Re-derive the p806 hand-off: the seed facts AND every screen's per-target speech windows.

    The p806 record lives under `host["p806"]` and keeps its batch-6 shape: the seed facts are
    re-derived from the pushed database and each screen check from its own raw window. The shared
    helpers below carry the parts the screenshot profiles (empty/seeded/refund plus presentation) also use.
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
    device_problems, device_service = talkback_device_problems(talkback)
    problems.extend(device_problems)
    problems.extend(
        talkback_screen_problems(talkback, p806_screens(facts), evidence, "seed", device_service)
    )
    return problems


def talkback_gate_problems(
    host: dict[str, object], screens: tuple[Screen, ...], evidence: Path
) -> list[str]:
    """Re-derive the screenshot profiles' (empty/seeded/refund plus presentation) TalkBack record (P8-07 batch D, the Q3 evidence).

    D4: the TalkBack version and the device API level are re-parsed from the raw artifact the run
    wrote out of the device's own `dumpsys package` and `getprop` answers, so an edited or emptied
    host.json field fails instead of naming a version no artifact on this run can prove. D5: every
    ordered target must carry its own png, named by that target's command id and at or above
    MIN_SCREENSHOT_BYTES, so a picture cannot be reused across targets.
    """
    recorded = host.get("talkback")
    if not isinstance(recorded, dict):
        return ["host.json records no talkback record"]
    problems: list[str] = []
    if recorded.get("version_artifact") != TALKBACK_VERSION_ARTIFACT:
        problems.append(
            f"host.json names the TalkBack version artifact {recorded.get('version_artifact')!r}, "
            f"the run writes {TALKBACK_VERSION_ARTIFACT!r}"
        )
    artifact = evidence / TALKBACK_VERSION_ARTIFACT
    if not artifact.is_file():
        problems.append(f"the raw TalkBack version artifact {TALKBACK_VERSION_ARTIFACT!r} is missing")
    else:
        version, api_level = parse_talkback_version_artifact(artifact.read_text(encoding="utf-8"))
        if not version or not any(character.isdigit() for character in version):
            problems.append("the raw TalkBack version artifact records no usable versionName")
        elif recorded.get("version") != version:
            problems.append(
                f"host.json records TalkBack version {recorded.get('version')!r}, "
                f"the raw artifact says {version!r}"
            )
        if not api_level.isdigit() or int(api_level) <= 0:
            problems.append("the raw TalkBack version artifact records no device API level")
        elif str(recorded.get("api_level")) != api_level:
            problems.append(
                f"host.json records device API level {recorded.get('api_level')!r}, "
                f"the raw artifact says {api_level!r}"
            )
    device_problems, device_service = talkback_device_problems(recorded)
    problems.extend(device_problems)
    problems.extend(
        talkback_screen_problems(
            recorded, screens, evidence, "profile", device_service, require_screenshots=True
        )
    )
    return problems


def gate_artifact_problems(
    host: dict[str, object], app_apk: Path, test_apk: Path
) -> list[str]:
    """The gate's artifact identity: the APKs the evidence names are the files on disk.

    The check serves every profile since P8-07 batch D: p806's own record and the three screenshot
    profiles' top-level record both carry the two APK hashes.
    """
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
    problems.extend(gate_evidence_plan_problems(profile.name))
    if host.get("sha") != args.sha:
        problems.append(f"host.json records sha {host.get('sha')!r}, expected {args.sha!r}")
    applied = host.get("font_scale")
    if not isinstance(applied, (int, float)) or abs(float(applied) - args.font_scale) > 0.001:
        problems.append(f"font_scale {applied!r} is not the requested {args.font_scale:g}")
    problems.extend(gate_ui_mode_problems(host, args.ui_mode))
    for screen in screens:
        shot = evidence / f"{screen.stem}.png"
        tree = evidence / f"{screen.stem}.xml"
        if not shot.exists() or shot.stat().st_size < MIN_SCREENSHOT_BYTES:
            problems.append(f"{screen.stem}: screenshot missing or blank")
        if not tree.exists():
            problems.append(f"{screen.stem}: accessibility dump missing")
            continue
        for problem in screen_problems(tree.read_text(encoding="utf-8"), screen):
            problems.append(f"{screen.stem}: {problem}")
    if profile.seeded:
        seed_file = Path(args.seed) if args.seed else None
        actual = sha256_file(seed_file) if seed_file is not None and seed_file.is_file() else None
        problems.extend(gate_seed_problems(host, actual))
    if facts is not None:
        problems.extend(p806_gate_problems(host, facts, evidence))
        problems.extend(gate_artifact_problems(host, Path(args.apk), Path(args.test_apk)))
    elif records_talkback_evidence(profile):
        # P8-07 batch D (Q3): the screenshot profiles' (empty/seeded/refund plus presentation) own record — the device's TalkBack
        # version and API level, every target's window and its own png — plus the same APK identity
        # check the p806 segment runs.
        problems.extend(talkback_gate_problems(host, screens, evidence))
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
    parser.add_argument(
        "--ui-mode",
        choices=UI_MODES,
        default=DEFAULT_UI_MODE,
        help="platform night-mode state applied before launch and recorded in host.json (D-369 A)",
    )
    parser.add_argument("--avd-name", default="ul-fontscale")
    parser.add_argument("--apk", default="android-app/build/outputs/apk/debug/android-app-debug.apk")
    parser.add_argument(
        "--test-apk",
        default="android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk",
        help=(
            "the androidTest APK that hosts the D-292 focus probe; installed and hash-recorded for "
            "every talkback profile, and hash-compared by the gate"
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
