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
- **`batch_confirmation` 段改为见证段**：原 `ui.edge(last = true)` + D-335 探针 + `ui.seek("进入批量确认")` + click/授权/列表侧摘要读整块删除，只剩 `check(evidence.getBoolean("batchBookedSeen"))`。`result.py` 的 `STAGES` 顺序不动（stage 名与顺序仍成立；`:277` 的 `startedMs >= previous_end` 对毫秒级段照样成立，`:275` 的 `0 < elapsedMs` 由 `stage()` 进出两次 `save()` 的落盘开销保证）。
- **D-335 探针两键（`parkHasEntry`/`parkTexts`）删除**：它们的观测对象是列表底入口，该路径已被本批放弃；保留只会产生恒 false 的噪音键（第十一跑读数即 `parkHasEntry=false`）。

**实施偏差登记（写手，相对本批任务书三条）**：①任务书要求在 `isChecked` 循环后追加 `ui.clickUntil("勾选候选", "进入批量确认")`；`clickUntil` 先点后验（`AndroidScaleUi.kt:390-404`），而复选框是 toggle——循环刚点成选中，这一击会把它取消，5 次尝试全数落空。故效果级后置条件落在**循环自身的后置条件**上，循环的两次尝试、fresh 节点查找与 tick 感知的 catch 原样保留。②任务书写 `ui.seekBackToTop()` 后读摘要；该 helper 的 marker 正是 `最近批量结果` 且只向**上**走（`AndroidScaleUi.kt:1314-1361`），而摘要块渲染在候选行与批入口之后（`P503ImportReviewPresentation.kt:1493-1504`，D-337 已实证「不在初始视口」）——顶部读法即第十一跑 `batchSkipSummarySeen=false` 的成因。故改为 `edge(last = true)` 到底部读。③任务书要求尾部原样保留 `ui.clickUntil("返回", "刷新清单")`；`clickUntil` 首步 `click("返回")` 会先 await 该文本，而 `返回` 只存在于候选详情页与批量确认页（`P503ImportReview.kt:926`、`P503ImportBatch.kt:245`），授权落地后的导入面没有它（该面的首行是 `导入` + `刷新清单`，`P503ImportReview.kt:544-548`）。故尾部改为 `edge(last = false)` + `await(刷新清单)`——同一「已回到清单」的效果断言。

**下一跑判读清单（D-338）**：①`batchBookedSeen` 应 `true`（`false` 时先看 `batch_confirmation` 是否 FAIL，再看第二批的摘要行是否停在 `已入账 0 项`）；②`batch_confirmation` 段 `elapsedMs` 应为毫秒级（见证段只剩一次 evidence 读）；③`detail_monthly_refresh` 应首次真正开始跑——第十跑的失败形态是它跑起来但账本真空（`账本为空，还没有任何交易。`），第十一跑根本没轮到它（NOT_RUN）。**预算结论（只读核对）**：`detail_decision` maximum 档仍为 180000ms，第十一跑用 150965ms（余 29035ms）；本批新增一次完整批提交（批次页组合 + 授权 + 派发 + 落地，D-164 实测单笔授权到权威计数增长 **39,426 ms**）+ 一次底部走位（~6s）+ 摘要读，**超界风险实质存在，需上调**——rig 的 `stage("detail_decision", …)` maximum 档与 `tools/python/android_scale/result.py` 的 `limits` **两处同改**（建议 420000ms；该 Python 文件不在本批 writablePaths，见 D-338 的「遗留」）。