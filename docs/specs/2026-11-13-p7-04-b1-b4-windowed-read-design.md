# P7-04 B1/B4 有界首窗设计门——组卡首窗即开、接治落地只读一页、逐输出行成本归属

**状态：** **approved v1**（2026-11-13，批准依据 `D-319`）
**Revision：** v1（2026-11-13 首稿；同日按 D-319 批准，正文未改）

**指针：** 计划 `docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF（`:36`「启动、接治收尾、刷新、单候选详情全量读治理 …… 禁止靠截断、隐藏待确认项或更改组审核范围提速」）、`:40`（`runImportIntakePipeline` 与刷新链的宿主归属）、`:42`/`:44`（测量口径与整链纪律）、§10.3（`:412`/`:418`）；A-PERF approved 规格 `docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（B2/B3/B4 终点与 ≤3 s 门槛）；分页读规格 `docs/specs/2026-11-05-p7-04-import-review-paged-read-design.md`（`:31`/`:49`「`Rows(rows)` 保留为兼容入口，UI 不再调用它」）；B6 分页枚举规格 `docs/specs/2026-11-04-b6-group-card-paged-enumeration-design.md`（§3.3 `:98`「首窗事件落盘后立即开卡」、§3.4 确认授权恒为全组）；决定 `D-230`/`D-241`/`D-243`/`D-278`–`D-284`/`D-293`/`D-294`/`D-308`。

本文**不改写**上述任何既有裁决：不改 ≤3 s 门槛、不改 max-of-3 统计口径、不改 `P704GP-D-PERF-01` 的 marker、不把 `ACC-D-PERF-01` 的 MET 状态改判、不改 `ACC-COUNT-01`/`ACC-SEL-01`。

---

## 1. 问题现状（全部 `file:line` 可复核）

### 1.1 B1：组卡在整轮分页枚举之后才开，`progress→card` 吃掉全部预算

- 点击入口 `onGroupDisposition`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:4624`）→ `startImportDuplicateGroupDisposition()`（`:2709-2908`）。
- 该方法在同一租约内先取 count（`:2771`），再 `while (true)` 逐页读（`:2779-2831`），首页存 `firstPage`（`:2808-2809`），其余页压入 `landedPages`（`:2816-2821`，注释写明「Collected, not dispatched」，R2-5），**全部页跑完**后由 `finally` 的主线程 hop 一次性派发 `StartImportDuplicateGroupDisposition`（`:2895-2897`）再逐条倒出 landing（`:2898-2900`）。
- `ImportDuplicateGroupItemState.ordinal`（`app-ui/.../P503ImportReview.kt`）与 `ImportGroupEnumerationCompleted` 的进度行时序（`P503Reducer.kt:1330-1336`）**已按 §3.3 实现**；缺的只有「首窗即派发 `Start`」这一条。
- 后果（设备读数，run `43301412180`，commit `78163d67`，20k 夹具）：三轮 `click-to-card` = **3071 / 2883 / 2905 ms**，其中 `click-to-progress` = 119 / 113 / 125 ms、`progress-to-card` = 2952 / 2770 / 2780 ms（`local/artifacts/b6-threshold/run-43301412180.log:747-749`）。判据 `P704GP-D-PERF-01` 是三轮取最大值 ≤3000 ms，故 worst=3071 超界 71 ms。
- 对照：3 条组（`fixture_relations=2`）同机 `progress-to-card` = 76 ms（run `41813084464:750`）。页大小 200（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportReviewReadAdapter.kt:42`）+ `itemCount=20001` ⇒ 101 次 keyset 页查询，`~27 ms/页` 与 2952 ms 同量级。**即：缺口就是「101 页跑完才开卡」本身，不是统计口径，也不是阈值。**
- **同制环境对照（本次新增，run `43374890643`）**：把 D-282 判 PASS 的那棵树（`4f04b60e`，预分页后的同一实现）在今天的 runner 上重派一次（`probe/b6-env-4f04b60e` 分支钉住该提交，`android-b6-card.yml`，默认 20k / 3 轮 / 3000 ms），得 **2707 / 2685 / 2610 ms**（worst 2707，`progress-to-card` = 2476 / 2458 / 2482）。同一棵树在 2026-11-04 读 1770 ms。⇒ ①环境自身漂移 ≈ **+0.9 s**（同一 `progress→card` 段 1602 → 2476 ms，+55%）；②`78163d67` 相对该对照的代码增量为 **+0.5 s / +19%**（2952 vs 2476）。两者都不足以单独解释 3071，但合起来说明：**在 3000 ms 界上，今天的 runner 只剩 293 ms 余量（旧树 2707），跨日读数不可比**，因此本门只能靠同日报文约束，且真正稳妥的出路是让 `progress→card` 不再绑定页数（D-1）。

### 1.2 B4：接治落地仍走整表读，61k 上把用户结果行挡住 ~25 分钟

- `runImportIntakePipeline`（`P503App.kt:2356-2443`）在同一个租约内做三件事：接治事务 → `importIntakeStatisticsRefresh()`（`:2401`，全 schema `ANALYZE;`，`ledger-data/.../QueryStatisticsOptimize.kt:107-113`）→ **`facade.queryImportReviewRows?.query(ledger.ledgerId)`（`:2402`，整表读）**，然后把「接治摘要 + 整表行」作为**一个** `P503UiEvent.ImportFileIntakeResult` 投出（`:2431-2440`）。
- 该事件是「接治完成：新增 N……」这句结果行（`app-ui/.../P503ImportReviewPresentation.kt:193-205`）的唯一来源（`P503Reducer.kt:2167-2176`）。**结果行在整表读返回之前不可能出现。**
- 用例层 `QueryImportReviewRows.query(ledgerId)`（`ledger-application/.../ImportReviewReadPort.kt:349-357`）→ `loadImportReviewRows`（`SqlDelightImportReviewReadAdapter.kt:85-148`）：按候选 keyset 分批（页大小 10,000 候选，`:32`）**累计折叠成完整列表**后返回。
- 设备证据（maximum rig，run `43322793290`，commit `d7cc7f6a`）：`saf_import` stage 预算 1800 s（`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleLongInstrumentedTest.kt:407`）耗尽，instrumentation 共 1829.8 s（`instrumentation-chain.txt:45`），失败点 `ui.await(1800000) { ui.has("接治完成：…") }`（`:410`）。`host.json` 记录 `ledger.db` = 356,052,992 B、mtime `2026-11-13 08:17`；接治写入在 08:17 结束，失败发生在 08:42 ⇒ 写入之后仍有 **~25 min**。
- 该 25 min 的**性质**由本 run 的两份工件定住：①失败瞬间采样栈停在 `SQLiteCursor.fillWindow` → `executeAsList` → `ImportReviewRowsForLedgerPageQuery.execute`（`stage-forensics.json` 的 `DefaultDispatcher-worker-2`），即**在 SQLite 侧填游标窗口**，不是 Kotlin 折叠；②`memory.txt` 的 chain 阶段样本显示应用堆在 `elapsed≈5074 s` 起稳定在 ~50 MB（`Dalvik Heap Alloc` 32–50 MB / size 56–75 MB），此后 1800 s **无增长**；③logcat 的 GC 行全程稳态 ~38 行/分钟（系统噪声量级），**无应用侧 GC 风暴**。⇒ 这是**单线程 SQL 执行时间**，不是物化内存、不是 GC。
- 分页读**已经存在**且已被首页使用：`requestImportReview()` 走 `queryPage(ledgerId, IMPORT_REVIEW_PAGE_SIZE=50, null)`（`P503App.kt:816`，页大小 `:5548`），`加载更多` 走 `:2572`。D-294 的「61k 首屏 ≤0.6 s」量的就是这一条。**分页读规格 `:49` 明文假设「UI 不再调用 `Rows(rows)`」，而 UI 还有六处调用**：`:2402`（本批，接治落地）、`:2652`（重复审核提交后刷新）、`:3022`（整组处置落地）、`:3048`（`requestImportReviewRowsRead()`，被 `:3160`/`:3208`/`:3221`/`:3292` 四条后处理路径调用）、`:3098`（批量确认前相）、`:3265`（Unknown 核对前相）。

### 1.3 两条面耦合：组卡成员集今天来自列表状态

- `enumerateImportDuplicateGroupItems(sessionInputRef, rows, loadReviews)`（`app-ui/.../P503ImportReviewPresentation.kt`）先 `importDuplicateGroupRows(rows, sessionInputRef)` 取「该 session 的 `SUSPECTED_DUPLICATE_PENDING_REVIEW` 行」，再为每行取重复审阅。
- 调用点传的是**轮次开始时读到的 `view.rows`**（`P503App.kt:2797-2801`、`:2840-2842`）。所以在 20k 组上它能枚举出 20,001 项，**只是因为接治落地那次整表读把 20k 行灌进了列表状态**。
- 因此「把落地读改成分页」单独做会**静默缩小组成员集**——这正是 `ACC-COUNT-01`（完整计数，绝不静默丢弃）与 `D-243` 第 3 条红线（「不得靠改『完整列表』语义提速」）禁止的形态。两片必须同批裁决。

### 1.4 逐输出行成本：P8-01a 给候选行加了两个按 `source_id` 的探针，`import_evidence` 无对应索引

- 候选列表/分页查询输出一行 per (candidate, duplicate)，`Ledger.sq:9595`（`importReviewRowsForLedgerPage`）与 `:9515`（`importReviewRowsForLedger`）的投影里含 P8-01a 新增的 `evidence_id`（标量子查询 `min(evidence_id)`，`:9624-9626`）与 `used_for_reconciliation`（嵌套 `EXISTS` + `max(sequence)`，`:9629-9639`）。
- `import_evidence` 只有 `PRIMARY KEY (ledger_id, evidence_id)`（`Ledger.sq:8082-8090`），**全仓无 `(ledger_id, source_id)` 索引**。
- 61k 夹具的输出行数 = 候选数 + 重复关系数（`tools/python/android_scale/result.py:19-22`、`:33-34`）≈ 211,000 行。若该探针走的是表扫描而非自动索引，成本随「输出行数 × 表行数」放大，与 §1.2 观察到的「游标窗口内长时间执行、堆与 GC 平稳」形态一致。
- 该风险**未被任何测试钉住**：全仓无一处对真实页查询跑 `EXPLAIN QUERY PLAN`（既有等价测试用手抄简化 SQL，`B6PagedSessionReadEquivalenceTest` 一族；D-283 残余 R2-14）。

---

## 2. 目标与非目标

**目标**

1. B1：点击「整组标记为重复」后，卡片在**首窗落盘**时即出现（兑现 §3.3 `:98`），20k 组的三轮 `click-to-card` 最大值落回 3000 ms 以内——**靠实现对齐设计，不改阈值、不改统计量、不改 marker**。
2. B4：接治落地的列表读**有界**（一次一页），用户结果行不再等待整表物化；61k maximum rig 的 `saf_import` 在 1800 s 内 PASS。
3. 让组卡的成员集**不再依赖列表状态已装载多少行**，从而 §2.2 的提速不触碰 `ACC-COUNT-01`/`ACC-SEL-01`。
4. 把 §1.4 的逐行成本做成**可判的测量**（`EXPLAIN QUERY PLAN` 钉点 + 规模读数），并给出裁决。

**非目标**

- 不改任何门槛、统计量、marker、stage 预算（`result.py` 的 stage 上限一字不动）。
- 不改确认授权范围（恒为全组，§3.4 原样）。
- 不重排候选顺序、不改组键、不改折叠语义、不改文案。
- 不在本批优化 `ANALYZE;` 的时长（无登记读数，另片）。
- 不动 `P704GP-D-SCALE-01` 与 B2/B3/B5 的口径。

---

## 3. 裁决

| # | 裁决 | 内容 |
| --- | --- | --- |
| **D-1** | B1 首窗即派发 | 首页 `ImportDuplicateGroupEnumeration.Ready` 一到手即经主线程 hop 派发 `StartImportDuplicateGroupDisposition(sessionInputRef, firstPage.items, totalCount)`；其后每页经 hop 按 keyset 顺序派发 `ImportGroupEnumerationPageLanded`；`ImportGroupEnumerationCompleted` 仍在末页之后派发（进度行翻转时机不变）。**R2-5 的解释由「末跳倾倒」收紧为「任何归约都必须经主线程 hop」**——hop 之间的 FIFO 同时提供顺序保证；R2-5 的原意（不得从 worker 直接归约）逐字保留。 |
| **D-2** | 中途关闭的语义 | `Start` 派发**之前**用 `cardStillOpen()` 重算「轮次开始卡片未开 ⇒ 必须建卡」/「轮次开始卡片已开且中途被关 ⇒ 不得复活」；`Start` 之后每次 landing hop 前再判一次，被关则丢弃后续 landing 且不重开。不新增状态位。 |
| **D-3** | 失败恒为整轮作废 | 任何一页 `Unavailable`/`Absent`/`ReadFailed` ⇒ 关闭卡片 + 类型化横幅（R2-1 原样）；**首窗已开卡之后**才失败同样必须收起卡片，不得把已落盘前缀当成更小的组。 |
| **D-4** | B4 落地读有界 | `runImportIntakePipeline` 的列表读改为 `queryPage(ledgerId, IMPORT_REVIEW_PAGE_SIZE, null)`；`P503UiEvent.ImportFileIntakeResult` 增 `nextAfterCandidateId: String?`（与 `ImportReviewResult` 同形），reducer 在 `Rows` 分支落 `nextAfterCandidateId`、在 `Unavailable` 分支保留旧行与旧游标（F1 语义原样）。 |
| **D-5** | 组成员集与列表状态解耦 | 组卡枚举的成员集改由**会话范围**的候选分页读取得（新增读端口 `loadImportReviewRowsForSessionPage(ledgerId, sessionInputRef, pageSize, afterCandidateId)`，命名查询与 `importReviewRowsForLedgerPage`（`Ledger.sq:9595`）同形、只多一个 session 谓词），`enumerateImportDuplicateGroupItems` 的 `rows` 参数改为**页加载回调**。列表状态只承载已加载页；组卡的可达性不再依赖它。`ACC-COUNT-01` 由 count 查询继续保证（`:2771`），`ACC-SEL-01` 不受影响（选择集仍是 candidateId 集，来源是已加载页）。 |
| **D-6** | 组页大小独立 | 组成员页大小取实现常量（与 review 列表的 50 独立；B6 组页 200 不变），由端口暴露给宿主做耗尽判定（`importDuplicateGroupPageSize()` 的同一纪律，D-280 R2-7）。 |
| **D-7** | §1.4 逐行成本的处置 | 本批**先测量后裁决**：给两条候选行查询各加一条 `EXPLAIN QUERY PLAN` 钉点（真实语句，非手抄），并在 61k 夹具上登记 `importReviewRowsForLedgerPage` 与新增 session 分页读的「页查询耗时 / 页输出行数」。若计划显示按 `source_id` 的探针为 `SCAN`，则**另立迁移批**加 `import_evidence(ledger_id, source_id)` 加性索引（v37→v38）；若显示已走自动索引，则本项关闭并只保留读数。**本批不引入任何 DDL。** |
| **D-8** | 其余五处整表读的归属 | `:2652`（重复审核提交后刷新）、`:3022`（整组处置落地）、`:3048`（`requestImportReviewRowsRead()` 及其四条调用路径）、`:3098`（批量确认前相）、`:3265`（Unknown 核对前相）**本批不改**，登记为同一缺陷的第二片，并逐条写明它们为何可能确实需要全量（批量前相要对每个待派发项做新鲜度重校验；`requestImportReviewRowsRead` 服务于批后刷新）。第二片的闭合条件 = 每条各自给出「有界化后语义不变」的证据或明确的裁决。 |
| **D-9** | 口径归属 | B1 的验收读数就是 D-280 §6 冻结的「用户可见组卡首响应」口径（卡片 marker = 头部 + 首窗条目）；本批**不改**该口径，只是让实现第一次真正兑现它。B4 不新设门槛：验收 = maximum rig 的 `saf_import` 在既有 1800 s 内 PASS，并登记「接治摘要行出现耗时」。 |

---

## 4. 实施切片与可写范围

**S1（B1 首窗派发）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`（`startImportDuplicateGroupDisposition` 的派发结构）、`P503UiEvent.kt`（`ImportGroupEnumerationPageLanded` 的 KDoc 契约改写：从「末跳倾倒」改为「逐页 hop」）、对应 reducer/host 测试。零 schema。

**S2（B4 落地有界 + 组成员解耦）**：`ledger-data/.../Ledger.sq`（新增一条**只读**命名查询 `importReviewRowsForSessionPage`）、`SqlDelightImportReviewReadAdapter.kt`、`ledger-application/.../ImportReviewReadPort.kt`（新端口 + 用例 + 结果族）、`P503App.kt`（`runImportIntakePipeline` 与组卡枚举循环）、`P503Reducer.kt`、`P503ImportReviewPresentation.kt`（`enumerateImportDuplicateGroupItems` 的成员源）、两处 shard 清单与 `ci.yml` 类数注释（若新增测试类）。零 DDL、零迁移。

**S3（D-7 测量）**：`ledger-data/src/jvmTest` 的计划钉点与规模读数（可并入 S2 的 PR，但独立测试类；读数走既有 `P707-A08 `/`P803-V15 ` 之外的独立前缀，例如 `P704B4-PLAN `，并把该前缀登记进 `tools/ci/scale-readings-report.py`）。

**顺序**：S1 与 S2 同批交付（S1 是 S2 的前置语义：没有逐页 landing，成员分页读的收益会被末跳吞掉）；S3 随 S2 同一 PR。

---

## 5. 验收向量

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | 首窗落盘即派发 `Start`：给定首页事件与失败标志，宿主产生的派发序列为 `Start` → `PageLanded`… → `Completed` | 纯函数/宿主 seam 测试 |
| V-2 | 续页期间 `groupEnumerationInProgress` 保持 true，末页之后翻 false | reducer 测试 |
| V-3 | 首窗已开卡后某页失败 ⇒ 序列内含 `CloseImportDuplicateGroupDisposition` 且屏上不留更小的组 | reducer + 宿主 seam 测试 |
| V-4 | 用户中途关闭 ⇒ 后续 landing 不派发、卡片不重开 | 宿主 seam 测试 |
| V-5 | `ImportFileIntakeResult` 携带游标，`Rows` 落 `nextAfterCandidateId`、`Unavailable` 保留旧行与旧游标 | reducer 测试 |
| V-6 | 接治落地只读一页：以计数端口断言落地路径的单次读 `pageSize == IMPORT_REVIEW_PAGE_SIZE`，且**不**调用整表 `query()` | 宿主 seam 测试（fake 端口计数） |
| V-7 | 组成员集与列表状态无关：列表态只有首页 50 行时，组卡枚举仍返回全组成员（用 fake 会话分页读） | 纯函数测试 |
| V-8 | 会话分页读与整表读在**同一会话**上逐行等价（顺序、折叠、字段） | 真库 JVM 等价测试（沿 `ImportReviewPagedReadEquivalenceTest` 形态） |
| V-9 | `EXPLAIN QUERY PLAN` 钉点覆盖真实语句（不是手抄 SQL） | JVM 测试断言 + 读数 |
| V-10 | 61k 夹具上的 session 分页读页耗时与输出行数登记 | `Recorded scale readings` 步骤的读数行 |
| V-11 | 20k 组设备读数：`click-to-card` 三轮取最大值 ≤3000 ms，且 `itemCount=20001` 与 key texts 逐字不变、`forwardActions=0` | 云设备 run（`android-b6-card.yml`，`fixture_relations=20000`） |
| V-12 | maximum rig `saf_import` 在 1800 s 内 PASS，并登记接治摘要行出现耗时 | 云设备 run（`android-scale.yml`，`mode=maximum`） |
| V-13 | maximum rig 后续阶段不因成员源改造回归（至少 `detail_decision` 的候选可通过既有寻址到达；若该阶段另有独立阻塞，登记为承接而非 PASS） | 同 V-12 的 run |
| V-14 | 全仓 grep：`P503App.kt` 的 UI 路径整表 `query(` 调用点从 6 处降为 5 处（`:2402` 消失），其余五处逐条登记 | 全仓 grep + 登记表 |

**披露（不计 PASS）**：`ANALYZE;` 在 356 MB 库上的耗时仍无读数；runner 镜像/系统镜像 revision 未固定，跨日读数不可比（D-283 残余⑤原样）；`import_evidence` 索引是否必要取决于 V-9 的计划读数。

---

## 6. 风险与承接

1. **派发顺序竞态**：`Start` 提前派发后，续页 landing 与用户事件（勾选、关闭、确认）会交错。缓解：一切归约走主线程 hop（D-1），`cardStillOpen()` 在每个 hop 判一次（D-2），确认路径的「补齐剩余页」语义不变（§3.4）。
2. **会话分页读的谓词正确性**：session 谓词必须与 `importDuplicateGroupRows` 的 Kotlin 过滤逐项等价（`classifyImportCandidate == SUSPECTED_DUPLICATE_PENDING_REVIEW` 且 `sourceInputRef == session`），否则会静默改变成员集。缓解：V-8 的真库等价测试是硬门。
3. **`totalCount` 与已落盘前缀的口径**：头部披露继续绑 count 查询，不用 `items.size`；V-11 的 `itemCount=20001` 是这条的判别读数。
4. **读数可比性**：V-11 与 D-282 的 1770 ms 不在同一环境（该 run 的 runner 镜像为 `20261025.353.1`，本轮的为 `20261111.127.1`，且 `sdkmanager` 未固定系统镜像 revision）。同制对照 run `43374890643` 已把这一点量化为「同一棵树今天读 2707 ms（旧读数 1770）」——本批只用**同 workflow、同 job 类、同规模、当日**的读数对 3000 ms 判据，跨日差值只作旁证；判据数值与统计量不动。
5. **承接（显式登记，不得静默过期）**：§1.4 的索引/重写裁决（D-7）；其余五处整表读（D-8）；`ANALYZE;` 时长读数；`import_evidence` 的 `EXPLAIN` 结论若为自动索引，则本项关闭并保留读数。

---

## 7. 边界

入：本文、S1/S2/S3 的 tracked 变更、以及随之的决定条目。

出：不改任何 thresholds / 统计量 / marker / stage 预算；不改 `D-230`/`D-243`/`D-278`–`D-284`/`D-293`/`D-294`/`D-308` 的裁决文本与开放状态；不把任何 A 项并入 PASS；不改 `.external/`；不引入依赖；**本批零 DDL、零迁移**（索引裁决按 D-7 另立迁移批）。

---

## 8. S1 实施登记（2026-11-13，随 S1 批；§1–§7 正文未改）

**S1 交付**：`startImportDuplicateGroupDisposition` 的派发结构改为「每个窗口一个主线程 hop」，三个纯 seam 承载顺序——`importGroupEnumerationFirstWindowEvents` / `importGroupEnumerationContinuationEvents` / `importGroupEnumerationRoundEndEvents`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503HostCoordinator.kt`）；`Start` 在首窗落盘时即派发，后续窗口按 keyset 序逐页追加，`ImportGroupEnumerationCompleted` 仍在末页之后（进度行翻转时机不变）。零 schema、零迁移、零依赖。

**实施期三处发现（逐条登记，非静默变更）：**

1. **进度行的渲染门必须同批改**。`importReviewRenderItems` 的渲染条件是 `groupEnumerationInProgress && groupDisposition == null`——卡一开就吞掉进度行。而 §3.3 明文要求「进度行在**续页期间**由 `groupEnumerationInProgress` 继续承载」；旧时序下「卡已开」几乎等价「轮次已结束」，该门近乎空转，首窗开卡后保留它会让 §3.3 那句话永远无法为真。S1 删掉后半条件并反转对应测试（断言 按钮 → 进度行 → 卡头 的渲染顺序）。这是呈现改动，理由如上，**不是**派发重排的副作用。
2. **「首窗为空、其余页非空」的语义增量**。旧码 `ready.items.isEmpty() -> Unit` 在该形态下**静默不开卡**（潜在 `ACC-COUNT-01` 缺口）。S1 改为按 count 查询的 N 开卡，随后续页生长；`totalCount <= 0 && items.isEmpty()` 仍不开卡，保住「整组空」的旧行为。**本门承认这是对旧实现的修正**，不是规格扩展。
3. **重跑轮次的可见瞬态**。轮次开始时卡片已开时，`Start` 会把已展示的卡替换成首窗再逐页长回；头部「共 N 条」不变。登记为可见瞬态，不改。

**新增承接（本批不做，须在后续片点名闭合；`R-8-n` 是本文 §8 的局部承接标签，不是 `docs/DECISIONS.md` 的决定编号）：**

- **R-8-1 §3.4 的确认门在首窗开卡后更容易被用户撞到，且当前是静默无反馈**。规格 §3.4 要求「续页仍在飞时点确认 → 宿主先补齐剩余页再逐条确认」，实现是 `isGroupEnumerationRunning()` 的静默 `return`（`P503App.kt` 的 `confirmImportDuplicateGroupDisposition`）。**这条路径在改造前就已可达**（重跑轮次时上一轮的卡片在轮次内仍然可见），S1 只是让它变成常态可达；授权对象恒为全组，因此是 UX 缺口而非账务缺陷。**闭合条件** = 确认在单飞槽内排队到轮次结束后执行，或至少落一条类型化可见反馈。归属 §3.4 承接小片。
- **R-8-2 host 派的顺序没有 JVM 形态可钉**。S1 把三处决策抽成纯 seam 并各自断言，但「每窗口一个 hop、hop 之间 FIFO」这一段只存在于 `P503App` 的协程队列里：把 `landWindow`/`dispatchEnumerationHop` 整条回退，五个 seam/reducer 用例仍全绿。**闭合条件** = 由设备读数 V-11 兜住（首窗即开卡的 `click-to-card` 量级），或在测试脚手架上补一个可驱动的队列；在那之前不得把 V-1 读成「host 派发顺序已钉」。
- **R-8-3 逐跳代际门的中途失效形态**。每个窗口 hop 各自判代际（`P706-A07`），轮次末 hop 在代际失效时 `return`。于是「换代发生在轮次中途」会留下「卡片已开 + 部分前缀 + 无 Close/横幅」的形态——旧实现单跳「全有全无」产生不了它。**闭合条件** = 换代即整屏重建（当前 `latestState` 与 `importReview` 全量替换）被证实覆盖该形态，或轮末 hop 在代际失效时也派发失败端。

**§4 可写范围的实施期增补**：S1 实际还改了两处 §4 未列的 S1 文件——`P503UiEvent.kt`（`ImportGroupEnumerationPageLanded` 的契约 KDoc：改为「逐页 hop、hop 可能晚于租约关闭、任一页失败则整轮作废并收起首窗已开的卡」）与 `P503ImportReviewPresentation.kt`（本节 #1 的渲染门）。§4 的清单是起草时的估计，两处增补由本节授权，**§4 正文不改**。

**登记（不改，供后续片参考）**：①计数为 0 而页非空的组合会开出「共 0 条」的卡；按规格 §2.3 的契约破坏纪律它本应走 `Absent` 端；②成功轮次不以 `items.size == totalCount` 收口，页全空而计数 > 0 时屏上可能是「共 N 条 / 0 行」且无横幅——规格 §6 风险 3 以论证排除，无测试钉。
