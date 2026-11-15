# D-333 着陆解耦设计规格——组处置结果两派发形制（outcomes 先落，行重读后置）

**状态：** approved v1（2026-11-15；用户批准「1, 2」推进后由 rig 第六跑判读直接落地，本文即实施批任务书的补全记录）
**基树：** `5c1ade4a`（最新 main）；本文全部 `file:line` 以该树为准。
**指针：** `docs/PROGRESS_LOG.local.md:5369-5381`（2026-11-15 晚二条，run `43707047398` 判读 + forensics 线程栈归因）、`local/artifacts/maxrig-43707047398/`（trace 全套 + stage-forensics 线程栈）、`docs/specs/2026-11-15-d332-confirm-vs-round-semantics-design.md`（确认 × 轮次语义前置批）、D-280 §3.3.1（可见部分成功语义出处）。

---

## 1. 背景：run 43707047398 着陆段时间线

D-332 链首次全程走通（晚二条 `:5373-5377`）：轮末 await 通过（~369s，50k 枚举轮实际时长，900s 界内）→ 重 click 被产品接受（44 天来第一次）→ 处置循环 ~81s 完成 50k 项落库（`db/gen-1/ledger.db` mtime 20:50、journal 空）——然后 outcome await 600s 超时（20:48:39→20:58:39），GroupMarkOutcomeProbe 60 样本全 `text="none"`、`firstHitMs=-1`。

决定性归因（stage-forensics 线程栈，晚二条 `:5378`）：worker-2 卡在 confirm finally 块 `P503App.kt:3247` 的全量行重读（`queryImportReviewRows.query(ledgerId)` → SqlDelight `loadImportReviewRows` → SQLite cursor 窗口填充，库 422MB，>520s 未完成）。**outcomes 与 rows 捆绑在同一 `ImportDuplicateGroupDispositionResult` dispatch**（reducer `P503Reducer.kt:2393-2429` 把 rows/notice 绑死在同一事件），重读不完成 UI 什么都看不到。outcomes 已算完却不显示 = 对已批语义 D-280 §3.3.1「可见部分成功」的**实现偏离**，非新语义问题。

探针 `text="none"` 的另一半形制因素：D-331 收起态零成员行 = outcome 行根本不在组合树——rig 需先展开再 await（交付件 2，随批落 `AndroidScaleLongInstrumentedTest.kt`）。

## 2. 拆分形制（交付件 1，`P503App.kt` confirm finally + `P503Reducer.kt` + `P503UiEvent.kt`）

1. **第一 hop（主 dispatcher）**：`coordinator.importGroupDispositionCompleted()`（单飞 slot 释放）+ stale-generation 守卫 + 立即 `dispatch(ImportDuplicateGroupDispositionResult(outcomes.toList(), null))`——rows 为 null。守卫语义与原实现一致：原单派发把 generation 守卫放在 dispatch 前、失效即整体丢弃；拆分后两个 hop 各自带守卫，generation 失效则该 hop 的 payload 整体丢弃（outcomes hop 同样受守卫）。
2. **第二 hop（异步，failure-safe）**：行重读原样搬进第一 hop 之后异步执行——仍在 `Dispatchers.Default`（A-PERF 离线程纪律不变），`runCatching` + `getOrNull()` 形制不变（throw 降级为 typed `Unavailable`）；完成后主 dispatcher 二次 `dispatch(ImportDuplicateGroupDispositionResult(emptyList(), rows))`——outcomes 空列表，行着陆。
3. **reducer null 语义**（`reduceImportGroupDispositionResult`）：rows 为 null = 「本次事件不带行」——保留旧行且**不设通知**（notice 原样保留，绝不伪造 ReviewReadFailed）；`Unavailable` = 「读失败要通知」分支行为原样（notice = ReviewReadFailed，F1）。null 与 Unavailable 是两个不同载荷。outcomes 空列表 = 零覆写：现有 `firstOrNull` 合并逻辑天然匹配不到任何项（注释钉住「empty outcomes = landing-only event」约定，零代码改动）。
4. **slot 释放与行着陆的配对硬边界**：`importGroupDispositionCompleted()`（slot 释放）保持在第一 hop，不挪到重读之后——慢读占住单飞 slot = 新缺陷。
5. **注释更新**：confirm finally 区域的 A-PERF/P704D-SPEC-03 注释随新结构重写，说明拆分理由 = rig 43707047398 的 600s outcome 超时实证 + D-280 §3.3.1 可见部分成功。

机械前提：`P503UiEvent.ImportDuplicateGroupDispositionResult.rows` 字段变可空（`ImportReviewRowsResult?`），生产构造点只有新两 hop（`P503App.kt`），测试构造点对可空类型兼容。

## 3. rig 腿（交付件 2，`AndroidScaleLongInstrumentedTest.kt` 重 click 之后）

现状：重 click 后直接进 `GroupMarkOutcomeProbe` + 600s outcome await（谓词 `已标记：CONFIRMED_DUPLICATE` 一字不动）。D-331 收起态零成员行 = outcome 行不在组合树，探针 60 样本全 `none`。

改形：重 click 成功后、构造探针前——先 `ui.has("查看全部 ", prefix = true)` 探测 D-330 view-all 入口行（可见文案 = `groupCardViewAllRowsText`，N = 整组 totalCount，50k 档即「查看全部 50000 条」；卡已展开态入口行不存在，探测即跳过——与 `AndroidScaleUi` 既有 seek/click 同形制：`ui.seek("查看全部 ", forward = false)` 反向走回入口行 → `ui.click("查看全部 ", prefix = true)`），展开后再进原探针 + 原 await。探针首样本预期 `待处置>0`（50k 处置循环已跑完、outcome 行未落前的 pending 行渲染）；探针采样逻辑与 await 谓词文本零改动。

## 4. 验证面（PresentationTest/reducer 测试面，`P503ImportReviewReducerTest.kt`）

- `aNullRowsDispositionResultKeepsThePreviousRowsAndNoticeWhileLandingTheOutcomes`：rows=null 事件保留旧行且 notice 原样（不设 ReviewReadFailed），verdict 照落；
- `anEmptyOutcomesRowsLandingWritesOnlyTheRowsWithZeroOutcomeOverwrites`：empty outcomes + Rows 事件 = 仅行着陆、零 outcome 覆写、既有 banner 清除（F1 Rows 臂原样）；
- `anUnavailableRowsDispositionResultKeepsTheRowsAndSurfacesReviewReadFailed`：rows=Unavailable 事件 = 旧行保留 + ReviewReadFailed 通知（现状回归钉）。

rig 改动不跑测试（CI 裁决，零本机执行纪律）。顺载项：枚举 catch 分流 `catch (failure: Throwable)` + `CancellationException` rethrow（D-330 健壮性批已批项，Error 类不再被静默吞成 enumerationFailed）。

## 5. 实施后修订（D-334，2026-11-16）

rig 第七跑（run `43727676294`，证据 `local/artifacts/maxrig-43727676294/`）判定本设计生效：group_disposition 首次 PASS（outcome-hit @202.8s，探针实测 待处置→已标记：CONFIRMED_DUPLICATE 演进），主链首次走到 batch_confirmation——该段 180s 相位预算耗尽于 `edge(last = true)` 的降级步行（异常栈 tick 检查 ← `AndroidScaleUi.scroll` → `settle`），归因 §3 展开腿把 50k 成员行铺开后未收尾。修订（D-334 本批）：group_disposition stage 结束前加折叠收尾腿——点卡块领位「收起成员行」行（`groupCardCollapseRowsText`，`查看全部` 的精确逆操作；非 header 的「收起全部比较信息」toggle，那只折叠行内比较文本），守卫式 `ui.has`+`ui.click` 与展开腿同形制，miss 由下游 edge 裁决。收起态即 D-331 零成员行形态，batch 段底边 walk 回 `scrollToPosition` 快路。

## 6. D-335 停位点探针（2026-11-16）

rig 第八跑 attempt 2（run `43741730054`，证据 `local/artifacts/maxrig-43741730054-a2/`）里 batch_confirmation 的 `ui.seek("进入批量确认", forward = false)` 80 步反向全程未命中，仅凭该结果两臂不可分辨：批量入口行从未渲染（selection 被上游 hop 清空），或 `scrollToPosition` 停位点高于真实列表底（a11y rowCount 口径 ≠ 真实内容）。判别器是停位点的视口文本 dump（`parkHasEntry`/`parkTexts` 两个 evidence 键，2000 字符截断）：dump 含「进入批量确认」→ 嫌疑转向 seek 自身漏检（第三臂）；行区可见而无入口 → 停位失真；列表尾既无行也无入口 → 入口未渲染。零行为改动：探针只读视口文本，其后 seek 的方向、步数、谓词一字不动。
