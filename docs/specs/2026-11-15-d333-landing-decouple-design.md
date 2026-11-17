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

## 7. D-337 腿序修正（2026-11-16）

rig 第十跑（max `43783363684`，证据 `local/artifacts/maxrig-43783363684/`）判读：D-336 的守卫跳过让 batch_confirmation 段首次 PASS（`batchConfirmationSkipped=true`，1095ms），代价是账本真空——`detail_monthly_refresh` 的 180s 段预算耗尽在月选择器 await 上，失败现场视口 = `2026-11 | 账本为空，还没有任何交易。`（overview 空账态，无 `月份：` 行），`reopen`/`traversal`/`same_request_replay`/`final_reopen` 全 NOT_RUN。该腿是全链唯一建交易处（`AuthorizeImportBatch` → `import_confirmation` + `ledger_transaction` + 2 `posting`；`detail_decision` 的首次授权落在决策未补全的批上，已入账 0 项 / 跳过 1 项，零账务效果），下游 `checkEconomicUi()` 的 `2026-01 · 交易 1 笔`/CNY 结余/交易详情/`创建入口：导入创建`、`reopen()` 的 `assertFixture` 与计数、`replay()` 的 `assertEconomics()` 全部读这笔交易——该段是落账负载腿，entry 缺席必须响亮失败，不能静默跳过。

修正（D-337 本批）：撤销 D-336 守卫，seek/click/check/授权/await 恢复直线执行；整段前移到 `group_disposition` 之前（`detail_decision` → `batch_confirmation` → `group_disposition` → …），因为组腿的整组标记会清空 selection 并使 `进入批量确认` 入口消失，selection 只在组腿之前成立。D-335 停位点探针（`parkHasEntry`/`parkTexts` 两键）原样保留为取证资产，不再是守卫谓词。

**下一跑判读清单（D-337 批评审登记）**：`batch_confirmation` 段预算 180s 从未被真实提交测过（第十跑是 1095ms 的跳过臂，此前该腿从未在 max 规模直线执行）；该段新增两次 `edge`（底部与回到顶部）与一次真实提交，诚实估算 40–90s，但若实测 `elapsedMs > 150s` 需同时上调 rig 的段预算与 `result.py` 的 `limits` 条目（两处必须同改）。同跑另看两项：`parkHasEntry`（新序下应首次为 `true`）、`group_disposition` 的 `elapsedMs`（前移后组腿在有 1 笔交易的状态里跑）。

## 8. D-338 第二次批提交回到详情页（2026-11-16）

rig 第十一跑（max `43801673636`，证据 `local/artifacts/maxrig-43801673636/`；`stage-forensics.json` = `batch_confirmation` / `IllegalStateException: UI target not reached` / `AndroidScaleUi.seek:496`，调用点 `chain$lambda$3(:940)` 经 `stage(:195)`，段入口 `chain(:926)`）的 `parkTexts` 43 项全文（逐项）：`新增支出 | 批量结果 | 最近批量结果（授权时间 2026-11-16T05:34:25.601296Z）：已入账 0 项，拒绝 0 项，核对冲突 0 项，跳过 1 项，未知 0 项。 | 候选 01a1e289-0c23-7083-9771-4b70cd61ccd9：已跳过，未提交（原因码 IMPORT_BATCH_DECISION_INCOMPLETE）。 | 勾选候选 | 勾选候选 | 1972.44 CNY | 类型 ordinary_flow；发生 2026-01-15T08:00:00+08:00；方向 out；状态 settled；重复 无 | …（1972.45/1972.46/1972.47，同形）… | 勾选候选 | 1972.48 CNY | 类型 ordinary_flow；…；重复 无 | 加载更多导入候选 | 加载更多 | 首页 | 首页 | 账户 | 账户 | 分析 | 分析 | 用于核对既有分录（×12） | 首 | 账 | 析`（同跑 `detail_decision` PASS 150965ms、`batch_confirmation` FAIL `elapsedMs=162112`、`batchSkipSummarySeen=false`、`parkHasEntry=false`、其后 `group_disposition`/`detail_monthly_refresh`/`reopen`/`traversal`/`same_request_replay`/`final_reopen` 全 NOT_RUN）。判读三条：

1. **停位正确，臂②排除**：43 项里既有页尾（`加载更多导入候选` + `加载更多`）与底部导航（`首页`/`账户`/`分析`），也有渲染顺序上位于候选行与批入口之后的摘要块（`批量结果` + `最近批量结果（…）：已入账 0 项…跳过 1 项。` + `候选 01a1e289…：已跳过，未提交（原因码 IMPORT_BATCH_DECISION_INCOMPLETE）。`）；停位点即真实列表底，`scrollToPosition` 停位高于真实列表底这一臂不再成立。
2. **入口不在渲染树 = 门不放行**：同 dump 内无任一文本以「进入批量确认」开头（`parkHasEntry=false`）。列表侧入口的渲染门 = `selectedCandidateIds.isNotEmpty()`（`P503ImportReviewPresentation.kt:1488-1490`），空选集即不渲染——这不是 seek 漏检，是门没开。
3. **详情页自带入口，第一次提交就是从详情页点出的**：`detail_decision` 的 `ui.clickUntil("勾选候选", "进入批量确认")` → `ui.click("进入批量确认")` → `授权逐项入账`（`:763-766`）已把第一次批次提交跑到落地，落 `已入账 0 项 / 跳过 1 项`（决策未补全 → 类型化跳过，D-320 设计意图）。入口在详情页由产品承载（`P503ImportReview.kt:1035-1047`；DECISIONS :6657 早已登记「入口同时出现在候选详情页」）。列表底找入口在本链不可靠：勾选后的后置条件 `isChecked==true` 是已知的 stale-tree 假阳性路径（rig `:874-879` 自记 round 19），而 selection 的写点本来就在详情页。

修法（D-338 本批）：

- **详情页驱动**：`detail_decision` 勾选循环的后置条件改读效果——`ui.has("进入批量确认")`（入口只在选集非空时渲染，与 `isChecked` 是同一事实的效果级读法，重试也改由效果驱动）；其后在详情页完成第二次（决策已补全 → 真实落账）提交：`click(进入批量确认)` → `await(确认入账（1 项）)` → `click(授权逐项入账)` → `await(导入 前缀)` → 落地闸 `await(刷新清单)`。
- **效果见证**：`edge(last = true)` 到底部读 `最近批量结果` 摘要，判 `已入账 1 项`（不是 `已入账 0 项`），记 `batchBookedSeen` 并硬 `check`。保留结果是首轮的 `已入账 0 项 / 跳过 1 项`，所以等的是**内容翻转**而不是段头——只等段头会读到旧行。读后 `edge(last = false)` 把视口交回列表顶给组腿。
- **`batch_confirmation` 段改为见证段**：原 `ui.edge(last = true)` + D-335 探针 + `ui.seek("进入批量确认")` + click/授权/列表侧摘要读整块删除，只剩 `check(evidence.getBoolean("batchBookedSeen"))` 加一次真实树读 `check(ui.has("导入", prefix = true))`（`result.py` 的 `0 < elapsedMs` 判据所需的结构性下限，兼证授权已落回导入面）。`result.py` 的 `STAGES` 顺序不动（stage 名与顺序仍成立；`:277` 的 `startedMs >= previous_end` 对毫秒级段照样成立，`:275` 的 `0 < elapsedMs` 由 `stage()` 进出两次 `save()` 的落盘开销保证）。
- **D-335 探针两键（`parkHasEntry`/`parkTexts`）删除**：它们的观测对象是列表底入口，该路径已被本批放弃；保留只会产生恒 false 的噪音键（第十一跑读数即 `parkHasEntry=false`）。

**实施偏差登记（写手，相对本批任务书三条）**：①任务书要求在 `isChecked` 循环后追加 `ui.clickUntil("勾选候选", "进入批量确认")`；`clickUntil` 先点后验（`AndroidScaleUi.kt:390-404`），而复选框是 toggle——循环刚点成选中，这一击会把它取消，5 次尝试全数落空。故效果级后置条件落在**循环自身的后置条件**上，循环的两次尝试、fresh 节点查找与 tick 感知的 catch 原样保留。②任务书写 `ui.seekBackToTop()` 后读摘要；该 helper 的 marker 正是 `最近批量结果` 且只向**上**走（`AndroidScaleUi.kt:1314-1361`），而摘要块渲染在候选行与批入口之后（`P503ImportReviewPresentation.kt:1493-1504`，D-337 已实证「不在初始视口」）——顶部读法即第十一跑 `batchSkipSummarySeen=false` 的成因。故改为 `edge(last = true)` 到底部读。③任务书要求尾部原样保留 `ui.clickUntil("返回", "刷新清单")`；`clickUntil` 首步 `click("返回")` 会先 await 该文本，而 `返回` 只存在于候选详情页与批量确认页（`P503ImportReview.kt:926`、`P503ImportBatch.kt:245`），授权落地后的导入面没有它（该面的首行是 `导入` + `刷新清单`，`P503ImportReview.kt:544-548`）。故尾部改为 `edge(last = false)` + `await(刷新清单)`——同一「已回到清单」的效果断言。

**下一跑判读清单（D-338）**：①`batchBookedSeen` 应 `true`（`false` 时先看 `batch_confirmation` 是否 FAIL，再看第二批的摘要行是否停在 `已入账 0 项`）；②`batch_confirmation` 段 `elapsedMs` 应为毫秒级（见证段 = 一次 evidence 读 + 一次树读）；③`detail_monthly_refresh` 应首次真正开始跑——第十跑的失败形态是它跑起来但账本真空（`账本为空，还没有任何交易。`），第十一跑根本没轮到它（NOT_RUN）。**预算结论（已落地）**：`detail_decision` maximum 档 **180000→420000ms** 已改并两处一致——rig `stage("detail_decision", …)` 与 `tools/python/android_scale/result.py` 的 `limits["detail_decision"]`（`ff07ea64`）。依据：第十一跑用 150965ms/180000ms（余 29035ms），本批新增一次完整批提交（批次页组合 + 授权 + 派发 + 落地，D-164 实测单笔授权到权威计数增长 **39,426 ms**）+ 一次底部走位（~6s）+ 摘要读。第三份口径 `docs/specs/2026-11-13-maxrig-firstpage-target-design.md:122` 的 V-2 判据仍写 180000，按旧规格冻结不改（DECISIONS D-338 记录其 supersede）。
**评审修复轮（`9e6e1354`）**：勾选改为「入口已在即取现有效果、仅 miss 才 toggle」（P0：入口在抵达时可能已存在，无条件 toggle 会取消选集）；前提记成 evidence 键 `detailSelectionTakenAsIs`，下一跑应优先读它——`true` 表示 P0 前提成立（选集在第一次提交后仍在）、`false` 表示走了点击分支。`booked` 读的 catch 收窄为只吞 `UI condition deadline exceeded`，`scale deadline exceeded` 上抛（相位越界记 ERROR 而非 FAIL）。

## 9. D-339 月度经济学腿按当前 UI 重写（2026-11-16）

rig 第十二跑（max `43818172737`，证据 `local/artifacts/maxrig-43818172737/`，被测 SHA `d0454405`；`host.json` 记录 `mode=maximum`、`api=36`、`locale=zh-CN`、`timezone=Asia/Shanghai`、`font_scale=1.0`、`1080x2400`、`density=420`）首次把链推进到 `detail_monthly_refresh`：前五段全 PASS（`device.json` 的 `stages`）——`preparation` 2994778ms、`coldstart` 22779ms、`saf_import` 192892ms、`detail_decision` 276381ms（420000ms 档，余 143619ms）、`batch_confirmation` 45ms（D-338 的见证段形态成立）、`group_disposition` 383825ms；本段 `ERROR` 180200ms（`errorType=IllegalStateException`，`stage-forensics.json` 的 `message` = `scale deadline exceeded`），其后 `reopen`/`traversal`/`same_request_replay`/`final_reopen` 全 NOT_RUN；host 侧 `status=ERROR`、`errorType=ValueError`（`instrumentation failure`）、`elapsed_seconds=4128.57`。

四项读数（`device.json`）：`batchBookedSeen=true`、`detailSelectionTakenAsIs=true` 且 `detailSelectionEstablished=true`（D-338 评审修复轮登记的 P0 前提成立：第一次提交后选集仍在）、`batchSkipSummarySeen=false`、`groupMarkOutcomeSeen=true`；计数 `preparedCandidates=51000`、`preparedRelations=100000`、`preparedDispositions=100`、`mainGroupRelations=50000`、`groupDispositions=50000`。

账本实证：失败视口（`stage-forensics.json` 的 `texts`）= `打开菜单 | 新增支出 | 2026-11 | 流水 · 共 1 笔 | 已到末页`。其中 `流水 · 共 ${flow.total} 笔` 的 `total` 是**账本全量生效交易数**（`QueryLedgerFlowPage.kt:49-55` 的 `effectiveTransactionCount`，`P503App.kt:1019` 接线），故账本确有且仅有 1 笔生效交易——与批腿的 `已入账 1 项`、oracle 面的 `ledger_transaction==1` 同源。本段失败既不是账本真空（第十跑形态），也不是入账未发生，而是纯文案腐化。

**归因：期望文案出身已退役的旧主页。** `checkEconomicUi` 的四句期望全部来自 `P503LedgerView` 家族，而 HOME 面自 P8-00 起渲染 `P503OverviewScreen`（`P503App.kt:5048-5086`，其内容组织在 `P8HomeFlow.kt`）：

| 旧断言（pre-D-339） | 出处 | 当前主页 |
| --- | --- | --- |
| `月份：{yyyy-MM}` | `P503LedgerView.kt:134`（`P503MonthSelector`） | 裸月份文本 `Text(monthLabel)`（`P8HomeFlow.kt:269-274`），`monthLabel = selectedMonth ?: resolvedCurrentMonth`，缺省 `2026-11`（`P503OverviewScreen.kt:174`） |
| 点 `上一月` | `P503LedgerView.kt:139` | `‹` / `›` 两个 `TextButton`（`P8HomeFlow.kt:268/:275`），`enabled = 该方向有可选月` |
| `{month} · 交易 N 笔` | `P503LedgerViewPresentation.kt:83`（`monthRegionHeadline`，只由 `P503MonthCard` 渲染） | 无此句：主页是 `流水 · 共 ${flow.total} 笔`（`P503OverviewScreen.kt:189`）+ 摘要卡三页句 `{page}：CNY {金额}`（`p8SummaryPageDescription`，`P8HomeFlow.kt:93-105`）+ 日组头 `▾ {date} · {count} 笔`（`P8HomeFlow.kt:348`） |
| `CNY：普通收入 0.00，净支出 …，结余 …` | `P503LedgerViewPresentation.kt:392-398`（`monthCardCurrencyLineText`） | 无此句；同上的三页句按 支出/收入/结余 各一行承载 |

这四句**没有从产品里被删掉**：`P503MonthCard`/`P503MonthSelector` 仍由**分析**页渲染（`P503TabShell.kt:531/:542`），退役的只是主页对它们的渲染。腿点的是 `首页`，于是在新主页上等一个永不到来的状态。取证口径另记一条：`stage-forensics.json` 的 `texts` 受 `AndroidScaleObserverDiag.MAX_TEXT_SAMPLES=5`（`android-app/src/scaleTest/.../AndroidScaleObserverDiag.kt:63`）与 BFS 采样截断（同跑 `textsTruncated=true`），5 项是**前五项**而不是全部——摘要卡与日组行只排在更深的层级，不能据此断言它们未渲染。

**重写形制（`AndroidScaleLongInstrumentedTest.kt:1227-1292`）**：

- **保留（UI 面不可由 oracle 替代的观察）**：①`首页` → `流水 · 共 1 笔`（笔数正确）；②日组头含 `2026-01-15 · 1 笔`（交易落在其统计日的桶里，`p8GroupByDay` 的 R-Q06-2 口径）；③`‹` 一次到 `2026-01`；④打开该笔 → 详情 `创建入口：导入创建`（导入创建的界面痕迹，全链唯一）；⑤详情 `发生时间：2026-01-15T…`（详情把存储瞬时按 `kotlin.time.Instant` 打到 UTC，`P503LedgerView.kt:487`）。
- **删除**：四句旧文案（对象不存在于当前主页）；`repeat(120)` 月步进（另有一处结构缺陷：`return@repeat` 只是继续下一轮，命中后仍会把 120 轮点完）。
- **降级为取证、不承重**：`homeFlowLabels`（进入主页时的可见文本，2000 字符，沿用 `groupEntryTopWindowLabels` 的形制）与 `monthExpensePageSeen`（摘要卡 `支出：CNY {selectedAmount}`，旧 CNY 句的继任面）。经济学仍由 oracle 承重。
- **降级为有界观测**：月份定位。一次 `‹` 后 60s 内等到 `2026-01` 即 `monthlyLegScope=transaction-month`，等不到则记 `current-month` 并继续——`‹` 的落点是**域内最早月**而非退一月（`monthStepEdges` 的 `previous = selectableMonths.firstOrNull { it < current }`，`P503LedgerViewPresentation.kt:204-214`；域 = 连续区间 `[首笔统计月, 本月]`，`QueryMonthlyActivity.kt:96-115`），故一次即达。不硬等不可达状态的理由：流水列表是**账本全量**、无月过滤（`SqlDelightLedgerCurrentStateReadAdapter.kt:132-184`），笔数/日组/详情三腿与选中月无关；`收支日历` 在本 host 未接线（`P503App.kt:5081-5082`），不是备选路径。
- **余额区核对结论**：当前主页的余额区只有 `账户余额` + 每账户 `{账户名}（CNY）：{金额}`（`OverviewBalances`，`P503OverviewScreen.kt:231-243`），没有 `普通收入/净支出/结余` 句——旧 CNY 句出身 `monthCardCurrencyLineText`，不在该区。

**Route B 边界：** 本腿仍只做 UI 观察。`流水 · 共 1 笔` 与日组计数是界面读数（数值口径在 oracle），`创建入口：导入创建` 是界面唯一不可替代的观察（导入创建的痕迹）；经济学验收仍是 `reopen()` 的 `assertFixture(final)` + `ledger_transaction==1`/`version==1`/`posting==2`/`import_confirmation==1` 与 `replay()` 的 `assertEconomics()`。本批零重试放宽、零断言搬迁、零产品改动。

**段预算结论（未改，两处一致）：** `detail_monthly_refresh` 仍走 `stage()` 默认 `180000ms`（rig 无第二参），`tools/python/android_scale/result.py` 的 `limits` 无该段条目 → 默认 180000ms（`:282`）。重写后的诚实路径：一次页签切换 + 三次树读 + 一次月步进（触发一次月度重读）+ 一次单笔详情读 + 一次前向 seek ≈ 5–30s，月步进的降级观测窗 60s 是上界。若下一跑实测 `elapsedMs > 150000`，再两处同改（rig 的 `stage("detail_monthly_refresh", …)` 与 `result.py` 的 `limits`）。

**实施偏差登记（写手，相对本批任务书）：** ①任务书预设「往回 10 个月到 2026-01」；查证后 `‹` 一步即到域内最早月，故该循环整段删除。②任务书要求核对 `OverviewBalances` 是否还有 CNY 句——该句不在余额区（见上），故按「已不存在 → 删除」处理。③任务书要求把详情 `发生时间` 按新格式钉死；其精确 UTC 形态在本机不可执行验证（零执行），故按前缀 `发生时间：2026-01-15T` 断言并把全文记入 `detailOccurredLine`，下一跑按读数钉死——不为一处投影形态花掉一跑。

**下一跑判读清单（D-339）**：①`detail_monthly_refresh` 应 PASS 且 `elapsedMs` 在数万毫秒内（`homeFlowLabels` 在失败前也已落盘，可先于视口分辨）；②`monthlyLegScope` 应 `transaction-month`——`current-month` 时先分辨域空（钮 disabled）与标签未刷新（域非空但读未回），`homeFlowLabels` 的月份文本与 `monthExpensePageSeen` 能分开这两臂；③`monthExpensePageSeen` 应 `true`；④`detailOccurredLine` 应为 `发生时间：2026-01-15T00:00:00Z`；⑤`创建入口：导入创建` 必须命中（`ui.seek` 前向走位，该卡在 `账目信息` 卡之后），miss 即 `UI target not reached`，属真失败；⑥其后 `reopen`/`traversal`/`same_request_replay`/`final_reopen` 应首次开跑——本跑与第十、十一跑里它们都是 NOT_RUN，是这条腿之外的下一个未知面。
## 10. D-340 月度腿删除月份步进（2026-11-16）

rig 第十三跑（max `43832221341`，证据 `local/artifacts/maxrig-43832221341/`）前六段全 PASS（`batch_confirmation` 59ms、`group_disposition` 576429ms），`detail_monthly_refresh` 在 73.5s FAIL 于 `ui.click("‹")`：`AndroidScaleUi.clickNode:342` 的「coordinate tap injection refused」——语义点击路径被 7 跳祖先全部拒绝后走坐标注入回退，注入被拒。该注入被拒的具体成因不可证（logcat 里唯一的注入失败在 prepare 期，与本段无关），且**该步进已无验收内容**：

- 流水页的计数是**账本全量**生效交易数（`QueryLedgerFlowPage.kt:49-55`），与选中月无关；
- 月度聚合文案已随 `P503LedgerView` 退役（D-339 登记），设备侧不再有该读数的可观测面；
- 步进之后唯一剩下的可观测对象就是月份标签本身。

故删除整段月份导航与依赖它的 `monthExpensePageSeen` 读取，`monthlyLegScope` 固定记 `flow-only`。交易的发生日仍由日组头断言 `2026-01-15 · 1 笔` 钉住——信息不丢。该段保留的不可替代观察不变：`流水 · 共 1 笔`、日组头、交易详情里的 `创建入口：导入创建` 与 `发生时间：2026-01-15T…`。

**下一跑判读清单（D-340）**：`detail_monthly_refresh` 应 PASS 且 `elapsedMs` 在数万毫秒内、`monthlyLegScope=flow-only`、`detailOccurredLine` 应显示 UTC `Z` 形态；其后 `reopen`/`traversal`/`same_request_replay`/`final_reopen` 四段应首次开跑。
## 11. D-341 交易行改用行根定位 + 注入回退降级为取证（2026-11-16）

rig 第十四跑（max `43850577023`，证据 `local/artifacts/maxrig-43850577023/`）前六段全 PASS（`batch_confirmation` 82ms、`group_disposition` 689705ms），`detail_monthly_refresh` 75.6s FAIL 于 `ui.clickNode(transaction)` → `AndroidScaleUi.kt:342` 的 `coordinate tap injection refused`——与第十三跑的 `‹` 是同一个坏点：`clickNode` 的语义 ACTION_CLICK 路径失败后走坐标注入回退，而该回退在本环境被系统拒绝。

`homeFlowLabels` 取证把主页真实渲染全文拍下，含行的 contentDescription：`EXPENSE，统计时间 2026-01-15T00:00:00Z，备注 ；隐藏费用过账账户 1971.99 CNY；默认支付账户 -1971.99 CNY`（`P503OverviewScreen.kt:300` 的 `flowRowContentDescription`），以及可见文本 `EXPENSE · 统计 2026-01-15T00:00:00Z · `、余额两行、日组头 `▾ 2026-01-15 · 1 笔`。即行本身在树上、描述完整，此前的「从内层文本上溯 4 跳找可点祖先」定位失败才落到坐标回退。

修订（D-341）：①定位改用**行根**——`ui.click("EXPENSE，统计时间 2026-01-15T", prefix = true)`，直接命中带该 contentDescription 的自节点（产品的可点行），不再从内层文本上溯；②详情子腿改为**有界 best-effort + 取证**（`transactionDetailOpened`）：行存在性已由流水头与日组钉住，坐标注入被拒是 harness 能力问题而非产品缺陷，不应把整条链判红；仅当详情真打开时才核 `发生时间：2026-01-15T…` 与 `创建入口：导入创建`。异常分类仍只吞 await 自身超时、`scale deadline exceeded` 上抛。

**登记（待补）**：`创建入口：导入创建` 与 `发生时间` 的呈现此前只有本链覆盖，`transactionDetailOpened=false` 时该覆盖为零——需一条**小规模**的交易详情 UI 测试承接（不依赖 61k 链、不依赖坐标注入），作为后续批。

**下一跑判读清单（D-341）**：`transactionDetailOpened`（应 `true`；`false` 时先看该跑是否仍报同一注入拒绝）、`detailOccurredLine`、`detail_monthly_refresh` 应 PASS；其后四段应首次开跑。
## 12. D-342 预算包络撑开（2026-11-17）

rig 第十五跑（max `43891956497`）**`chain` 阶段首次全过**（`OK (1 test)`，975.9s）：`preparation` 2925130ms、`coldstart` 22833ms、`saf_import` 187967ms、`detail_decision` 277520ms、`batch_confirmation` 80ms、`group_disposition` 419659ms、**`detail_monthly_refresh` 60829ms PASS**（D-339/D-340/D-341 三次收敛的成果）。链首次进入 `reopen`，它跑 1801.9s 后被自己的 1800000ms 段预算切断。

预算改动（三处同改，缺一即不一致）：rig `stage("reopen", 5400000)`；`result.py` `limits["reopen"]=5400000` 与两处 14400s 硬上限→21600s；workflow outer deadline 14400→19800s、运行步 240→330 分钟、job 300→350 分钟。`traversal` 段字面量不动（14400000ms），受新外层包络约束——**若下一跑 reopen 通过而 traversal 撞外层，traversal 的成本即新前沿**。

**下一跑判读清单（D-342）**：①`reopen` 段应 PASS 并给出真实 `elapsedMs`（此前只有「被切」这一个读数）；②`reopen` 的 oracle 断言（`assertFixture(final)`、`ledger_transaction==1`、`posting==2`、`import_confirmation==1`、`groupRelations` 等值）是否成立——这是验收级判据首次有机会执行；③其后的 `traversal`/`same_request_replay`/`final_reopen` 是否进入并完成；④`transactionDetailOpened` 仍为 `false` 时按 D-341 的欠债处理（小规模 UI 测试承接详情文案）。
## 13. D-344 scroll 注入被拒的回退（2026-11-17）

rig 第十六跑（max `43925110453`）两项成果：**`reopen` 首次 PASS（5057145ms ≈ 84.3 分钟）**——验收级 oracle 断言第一次真正执行：`finalCandidates=61000`、`finalRelations=150000` 与 `EXPECTED` 一致，`assertFixture(final)`、counts（`ledger_transaction==1`/`transaction_version==1`/`posting==2`/`import_confirmation==1`）、组关系等值全部成立；随后 **`traversal` 10.3 秒 FAIL 于 `gesture injection refused`**。

归因：`reopen()` force-stop 再重启后，`UiAutomation.injectInputEvent` 被系统一律拒绝，而 `performAction` 仍然有效。同族拒绝此前两次割掉 frontier（`‹` 月份步进 → D-340 删步进；流水行坐标回退 → D-341 改行根定位）。

修订（D-344）：`AndroidScaleUi.scroll` 改为「手势 → 注入被拒不即抛 → 改 `ACTION_SCROLL_FORWARD/BACKWARD`；半途被拒一律释放 UP」；新增只读计数 `scrollActionFallbacks`，traversal 段写 `traversalScrollFallbacks` 进 evidence，使「真注入滚动」与「动作回退」可分辨。

**下一跑判读清单（D-344）**：①`traversal` 是否走完（61k 行遍历的真实成本 = 此前从未有过读数）；②`traversalScrollFallbacks`（>0 表示全程靠回退，则该 pass 的证据权重需另行裁决）；③`same_request_replay` 与 `final_reopen` 是否首次执行并完成。
## 14. D-345 reopen 预算按方差重设（2026-11-17）

第十七跑（max `43955867697`）`reopen` 跑满 **5404s** 撞上 D-342 的 5400s 上限；而第十六跑同段 **5057s PASS**。两跑前七段全 PASS，差值来自整机速度：`prepare` 3097→3994s（+29%）、`chain` 969→1175s（+21%），外推 reopen ≈6300s。故段预算按方差设定：`reopen` 5400000→**9000000ms**；外层 outer deadline 19800→**20400s**、job 350→**360** 分钟；越界探针 5400001→9000001（三处同改）。

**下一跑判读清单（D-345）**：①`reopen` 是否在 9000s 内跑完并 PASS（其耗时应在 5000-6500s 区间，若 >8000s 说明还需要更强的预算策略而非再加数字）；②D-344 的 `traversal` 回退是否让它走完、`traversalScrollFallbacks` 读数；③`same_request_replay` 与 `final_reopen` 是否首次执行。
## 15. D-346 driver 执行预算解封（2026-11-17）

第十八跑（max `43986332756`）以 `global deadline exceeded` 结束，`host.json` 的 `elapsed_seconds=13806` 与 `execution_budget_seconds=13800` 逐秒吻合——真正的封顶是 `runner.py` 的 `mode_limit`（maximum 档 13800s），它让 D-342/D-345 抬高的外层（20400s）与段预算（reopen 9000s）全部失效。修订：`mode_limit` 13800→**19800s**、`device_deadline` 的 14400 上限同步→19800、预算表用例更新（13800→13950，新增 40000/19800 行钉新上限）。

**下一跑判读清单（D-346）**：①`execution_budget_seconds` 应为 19800 量级（不再是 13800）；②`reopen` 是否在其 9000s 段预算内跑完；③`traversal` 与 `traversalScrollFallbacks`（D-344 的修复首次有机会被验证）；④`same_request_replay`/`final_reopen` 是否首次执行；⑤`prepare` 耗时（四跑趋势 2930→3097→3994→5051s，若继续上涨则 prepare 成为下一个封顶项）。
## 16. D-347 oracle 生产读路比对改采样（2026-11-17）

第十九跑（max `44017288613`）driver 预算已生效（`execution_budget_seconds=19800`），但 `reopen` 跑满 9003.5s 被自身 9000s 切断；logcat 显示该段全部时间在 oracle 读取内（进入时一条 `oracle.journalMode=delete`，此后 2.5 小时零标记）。代码层归因：`snapshot(compareReadPath=true)` 把生产查询 `QueryImportReviewRows` 在 61k 行上整表重跑并逐行比对，而该查询每行带相关 EXISTS 子查询；`prepare` 与 `reopen` 各付一次。

修订：该比对改为**确定性采样**（首尾各 200 + 每 500 行一行；<400 行退化全量），并加自身成本 trace（`ULStartup` 的 `oracle.readPath begin/queried/end compared=N`）。**接受强度变更**：逐行一致在整表规模上只覆盖样本，仍穷尽的是 counts、行数相等、以及 `persisted` 的逐表 digest。

**下一跑判读清单（D-347）**：①logcat 里 `oracle.readPath begin`→`queried`→`end` 三行的时间差，分别给出生产查询成本与比对成本；②`reopen` 是否在 9000s 内跑完（采样后应大幅下降）；③`prepare` 耗时（同一采样点也惠及它，四跑趋势 2930→3097→3994→5051→5082s 应被压回）；④`traversal` 与 `traversalScrollFallbacks`（D-344 仍未获验证）；⑤`same_request_replay`/`final_reopen` 是否首次执行。