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

**§4 可写范围的实施期增补**：§4 的 S1 清单已列 `P503UiEvent.kt`，S1 按该行交付了 `ImportGroupEnumerationPageLanded` 的契约 KDoc（改为「逐页 hop、hop 可能晚于租约关闭、任一页失败则整轮作废并收起首窗已开的卡」）。**另一处 S1 未列的是 `P503ImportReviewPresentation.kt`**（§4 只在 S2 列它，本节 #1 的渲染门落在那里）。§4 的清单是起草时的估计，这处增补由本节授权，**§4 正文不改**。

**登记（不改，供后续片参考）**：①计数为 0 而页非空的组合会开出「共 0 条」的卡；按规格 §2.3 的契约破坏纪律它本应走 `Absent` 端；②成功轮次不以 `items.size == totalCount` 收口，页全空而计数 > 0 时屏上可能是「共 N 条 / 0 行」且无横幅——规格 §6 风险 3 以论证排除，无测试钉。

---

## 9. S1 设备首测：V-11 **未通过**，且**残余成本不在页数上**（2026-11-13）

**读数**：run `43379169190`（`3771555e`，`android-b6-card.yml`，20k / 3 轮 / 3000 ms）→ **3092 / 3215 / 3059 ms**，gate `FAIL`（worst 3215，超界 215 ms）。分段：`click-to-progress` = 289 / 127 / 151 ms，`progress-to-card` = **2803 / 3088 / 2908 ms**。同轮 `host.json`：`cardOpen=true`、`itemCount=20001`、`relations=20000`。

**与改前对比（`78163d67`，run `43301412180`：3071 / 2883 / 2905，`progress-to-card` 2952 / 2770 / 2780）**：没有改善。**这个「没有改善」本身就是本次最有判别力的读数**：

- 改造前 `progress→card` = 整轮 101 页 + 卡首帧；改造后同一量级。若成本在页循环上，改造后应掉到「1 页 + 首帧」量级（3 条组的实测是 76 ms）。⇒ **该段的主体成本发生在第 1 页之前**，页循环只占约 0.15 s（预估 101 页 × ~1.5 ms；旧实现的算术估计「27 ms/页」由此被证伪）。
- 第 1 页之前只有三件事：`ledger.leased` 的**租约获取**、`countImportDuplicateCandidatesForSession` 的**计数查询**（`Ledger.sq:11647`，三表 join + 每行相关 `max(sequence)` 子查询）、以及第 1 页查询本身。
- 同一轮证据显示该 b6 轮次的整组**成员为空**（`checkboxes=0`、`confirm affordance=none`，头部仍显示「共 20001 条」）：列表态只有分页首页 50 行，`importDuplicateGroupRows` 找不到成员 —— 这正是 D-5 要解的耦合，也说明枚举本身几乎没做工作，2.8 s 不可能是装配。

**由此确定的后续顺序（不得颠倒）**：B4/S2 的**落地读有界**（D-4）与**成员源解耦**（D-5）先落地，再复跑本门；若届时 `progress→card` 仍停在秒级，则剩余嫌疑只剩计数查询，按 D-7 的「先测量后裁决」给它单独的 EXPLAIN + 规模读数，再决定索引或重写。**V-11 维持未通过，不并入任何 PASS**；本轮读数登记为「残余成本定位读数」，不改门槛与统计量。

---

## 10. S2/D-4 实施登记：接治落地只读一页（2026-11-13，随 D-4 批；§1–§9 正文未改）

**交付**：`runImportIntakePipeline` 的列表读由整表 `query()` 改为 `queryPage(IMPORT_REVIEW_PAGE_SIZE = 50, null)`；`P503UiEvent.ImportFileIntakeResult` 增 `nextAfterCandidateId`（默认 `null`，既有构造点原样编译）；reducer 在 `Rows` 落行 + 落游标、在 `Unavailable` **保留旧行与旧游标**；「给定摘要与一页结果产出哪个事件」抽成纯 seam `importIntakeLandingEvent`（`P503HostCoordinator.kt`）。零 schema、零迁移、零依赖。

**三处实施期登记（非静默变更）：**

1. **V-6 的证据口径收窄**。§5 的 V-6 原写「宿主 seam 测试（fake 端口计数）」，但 `runImportIntakePipeline` 是 `@Composable`，`commonTest` 无法驱动它做端口计数。实际证据 = 纯 seam 的「一页进、一页出」结构（该 seam 没有整表读取入口，事件行数不可能多于所给一页）+ V-14 的调用点计数（6→5）+ 页大小是宿主调用点的实参。**要真正的端口计数需另立宿主层缝**，本批不做，登记为证据类型变更。
2. **V-12/V-13 的判定顺序**。D-4 单独落地后，组卡成员源仍读 `view.rows`（D-5 未落地），因此「选完文件立刻点整组标记为重复」路径的候选全集从整表降为一页——这不是新缺陷类（D-293 分页首读后该耦合已存在，S1 设备首测在 20k 上 `checkboxes=0` 即其证），但 **V-12/V-13（maximum rig 的组卡与后续阶段）必须在 D-5 之后才判**，不得在 D-4 单独落地时记 PASS 或 FAIL。
3. **跨页勾选在落地时被丢弃**。落地事件只带首页 50 行，`retainedSelection` 沿用「行不在即丢」的既有规则，因此用户经「加载更多」勾选的非首页行会在下一次接治落地时被清掉。登记为 D-4 的可见行为变化：`ACC-SEL-01`（跨页勾选）的正确性仍由批量确认前相的重校验保证（D-8 未动那一条整表读），本批不改选择语义。

**D-8 五处在 `989152fc` 上的实际位置**：`:2659`（重复审核提交后刷新）/ `:3038`（整组处置落地）/ `:3064`（`requestImportReviewRowsRead()`）/ `:3114`（批量确认前相）/ `:3281`（Unknown 核对前相）。§1.3 与 D-8 里写的行号是起草时的锚点，不随代码移动改写；核对时以本节为准。

---

## 11. S2/D-5 实施登记：组卡成员页（2026-11-13，随 D-5 批；§1–§10 正文未改）

**交付**：整组确认页的成员集改由**会话范围的成员候选页**取得，并用**同游标、同页大小**的成员页级联重复审阅读取回该页成员的审阅行；`enumerateImportDuplicateGroupItems` 的函数体与签名未改，只是入参从列表态换成「本页成员」。宿主循环只看成员页游标终止（不看行数）。三读任缺 ⇒ 落 `LegacyListState`，旧实现逐行未改。Android 根已接线；`desktop` 根未接成员复核读，故仍走 legacy。

**四处实施期登记：**

1. **D-6 的第二句未实现（登记为偏差）**：D-6 要求「成员页大小由端口暴露给宿主（`importDuplicateGroupPageSize()` 同一纪律）」。实现改为宿主常量 `IMPORT_REVIEW_GROUP_MEMBER_PAGE_SIZE = 200L` + 在循环外**绑定一次局部 `val`** 后供两条读共用。理由：耗尽判据已改为「成员页游标为 null」，宿主不再需要该值做判定；保留端口访问器会造出一个没人读的镜像值。代价是「两条读必须同页大小」这条不变式只由注释与单点绑定维护——**已登记为残余，闭合条件 = 若日后出现按页大小分支的判据，则改由端口暴露**。
2. **`EXISTS` 谓词是承重的，不是优化**：没有它，键集子查询会被「本会话内无重复的候选」占满窗口，外层 JOIN 再丢掉这些行，于是页面「看起来短」而适配器以「成员数 < 页大小」判末页 ⇒ **静默丢成员**。仓库外的一次同构 SQLite 对照（8 候选 / 4 成员 / 首窗两名成员都无重复）显示：无 `EXISTS` 只返回 1/7 行且游标为 null。等价测试用连续三名无重复成员作反例钉住它（去掉 `EXISTS` 即红）。
3. **成员复核读没有 `EXPLAIN` 与 61k 读数**：D-7 目前只覆盖两条候选行查询。这条读是新循环每页必付的成本，**在 D-7 裁决时必须一并取计划与页耗时**，否则「B1 残余成本定位」缺一条腿。
4. **两条读不共享同一快照**：成员候选页与成员复核页各起一个只读事务，同游标同页大小只保证**覆盖**、不保证同一快照；若两读之间落下一条重复处置，该成员会「消耗窗口但不产项」且无失败。旧实现同构（成员源是轮次开始时的列表态），故登记为承接而非本批缺陷。

**承接**：R-8-2（宿主派发顺序无 JVM 形态可钉）在新路径上复现——V-7 的七例在测试侧复刻了宿主循环，把宿主那段整体回退仍全绿；`desktop` 根未接成员复核读，B1 的目标平台目前只有 Android；§10 登记③（跨页勾选在落地时丢弃）未变。

---

**§11 追加（V-11 设备读数之后，2026-11-13；§1–§10 与本节上文未改）**

1. **读数与归因**。V-11 复跑（run `43388323866`，`4cb12aef`，20k / 3 轮）：`click-to-card` = 4848 / 4963 / 4904 ms，其中 `progress-to-card` = **4727 / 4811 / 4759 ms**；D-5 之前（run `43379169190`）同段为 **2803 / 3088 / 2908 ms**。慢了约 1.9 s，而这一轮的卡片**真的带上了成员**（`itemCount=20001`）。原因已定位、不再诊断：b6 的 20k 夹具形状是「**一个成员携带 20000 条重复关系**」，而 D-5 的页粒度是**成员**，所以第 1 页（200 名成员的窗口）里只有 1 名成员，但它的审阅行有 20000 条 ⇒ 纯函数在这一窗返回 20000 个 item ⇒ `landWindow` 一次性落盘 ⇒ 首窗 = 整组，a11y 树多出两万个节点，卡片 marker 探测随之变慢。**旧实现之所以「更快」（2803 / 3088 / 2908）是因为它的成员集为空（`checkboxes=0`）——那不是更快的实现，是没干活**；这一条同时把 §9「同轮 `checkboxes=0` 而 2.8 s 不可能是装配」的判断钉死。
2. **处置（本片）：每窗 item 数封顶**。新增宿主常量 `IMPORT_REVIEW_GROUP_WINDOW_ITEMS = 200`（与旧 B6 组页同量级）与纯 helper `importDuplicateGroupItemWindows`：成员页仍按 200 名成员分页，两条读仍**同游标、同页大小**（§11 上文第 1 条的绑定不变），但一页的 item 序列按 ≤200 项切成若干窗，逐窗 `landWindow(chunk, firstOrdinal)`、`firstOrdinal += chunk.size`。**不变式（登记为本片的核心约束）：一窗的 item 上限是「用户可见首响应的预算」，与成员页大小是两个不同的界**——成员页决定一次读多少行，窗决定一次落多少项；两者不可互相推导。切分在纯函数层完成并单独测试（20000 项 → 100 窗且 ordinal 连续 1..20000；整除不出空窗；0 项不出窗；不足一窗只有一窗）。
3. **行为变化（登记）**：0 项的页不再落空窗，于是「计数 > 0 而所有成员页都空」的形态不再开出「共 N 条 / 0 行」的卡，而是落到 `anyPageLanded == false` 的轮末失败端（关卡拉 + 类型化横幅）——即 §8 登记② 的悬置形态按 §6 风险 3 收口。旧实现的同形轮次（首窗为空）曾以 count 开卡，本片不再复现该瞬态。
4. **剩余残余（下一片必须取，不得静默过期）**。成员复核读**一次返回该页全部成员的全部重复行**：在「一成员 20000 行」形态下，`importDuplicateReviewsForSessionMemberPage` 这一条查询本身就是秒级来源，且成本随「该页成员行总量」而非页大小增长——item 封顶发生在读**之后**，对它无缓解。二选一：①把该读改成**按 item 键集分页**（item 序是 member-major，而 `P704GP-ACC-ORDER-01` 冻的是候选序，两者关系需裁决，可能与既有序定义冲突）；②在 **D-7** 里给它补 `EXPLAIN QUERY PLAN` 与 61k 规模下的「页耗时 / 页输出行数」读数（升级本节上文第 3 条），按读数再裁决。**本批不引入任何 DDL / 迁移。**
5. **判定**：**V-11 仍不通过，不得并入任何 PASS**。本片只从读数里摘掉「首窗 = 整组」这一项；`progress-to-card` 的剩余成本（秒级的成员复核读）尚未收口，§9 的「改前更快」读数保留为对照、不重判。


---

## 12. S2/D-5 设备读数：V-11 **通过**（2026-11-13，随 D-5 批；§1–§11 正文未改）

**读数**：run `43391803446`（`e9e0aba4`，`android-b6-card.yml`，20k / 3 轮 / 3000 ms）→ **2912 / 2714 / 2739 ms**，worst-of-3 = **2912 ≤ 3000**，gate **PASS**。分段：`click-to-progress` = 107 / 108 / 124 ms，`progress-to-card` = 2805 / 2606 / 2615 ms。同轮 `host.json`：`cardOpen=true`×3、`itemCount=20001`、`relations=20000`；轮次日志的 `forwardActions=0`、三条 key texts（标题 / 「共 20001 条」披露行 / 确认行文案）逐字在场。

**`P704GP-D-PERF-01` 的四项判据**（规格 `2026-11-04-b6-group-card-paged-enumeration-design.md:124`）：①时延 worst-of-3 ≤ 3 s ✔（2912）；②`itemCount=20001` 与 key texts 逐字不变 ✔；③`forwardActions=0` 组入口可达 ✔；④窗口 CPU 总量为旁证（D-284 裁决），本链不取 ✔。

**与前一读数的关系**：同批上一轮（`4cb12aef`，尚无窗上限）为 4848 / 4963 / 4904，`progress-to-card` 4727 / 4811 / 4759 —— 首窗落盘两万个 item 所致（一个成员携带全部 20000 条重复关系）。加窗上限（`IMPORT_REVIEW_GROUP_WINDOW_ITEMS = 200`）后回到 2912，且卡片这次**真的带成员**（成员集不再为空）。因此 2812–2912 这个量级是「首窗 = 200 项 + 一页成员的全部审阅行」的真实成本，**不是**改前那种「成员集为空、没干活」的 2803。

**余量只有 88 ms，必须如实登记**：承载该窗口成本的两项都不是本批能降的——①计数查询 `Ledger.sq:11647`（三表 join + 每行相关 `max(sequence)`，b6 落在卡片打开之前）；②成员页的审阅读**一次返回该页成员的全部重复行**（b6 形态下 1 名成员 = 20000 行，item 封顶发生在读之后，救不了它）。两项都归 **D-7** 的「先测量后裁决」：先取 `EXPLAIN QUERY PLAN` + 页耗时/页输出行数，再决定加性索引（另立迁移批）或把读切到 item 粒度（后者会动组卡 item 序的合同，须单独裁决，见 §11 残余 4）。

**证据差异披露**：合并头比该 run 的 `e9e0aba4` 多两个提交（`7cc810ac`、`d79dafb7`），内容**只是测试文件的 ktlint 折行**，产品代码零改动；因此本次读数对合并头成立。


---

## 13. D-4 之后的 maximum rig 读数：`saf_import` 不再打满，卡在 `detail_decision`（2026-11-13，§1–§12 正文未改）

**读数**：run `43386393213`（`fa2ce94f` = D-4 合入后的 `main`；61k 候选 / 150,000 重复关系；`android-scale.yml`，mode=maximum）→ host `status=ERROR`、`phases=["prepare"]`、`elapsed=5548 s`；`instrumentation-chain.txt` 的失败是
`java.lang.IllegalStateException: unique candidate not reached (amount=2071.99 CNY sawTargetRow=false clickAttempts=0 scans=65)`，栈在 `AndroidScaleUi.openCandidateByAmount`（`AndroidScaleUi.kt:943`）← `AndroidScaleLongInstrumentedTest.chain`（`:441`），即 **`detail_decision`**；该次 instrumentation 全程 **408 s**（对照前一轮 run `43322793290` 是 1829.8 s 且**卡在 `saf_import`**）。

**结论一：B4 的目标达成。** 61k 的接治落地不再把结果行挡在整表读之后——链条从 `saf_import` 一举推进到 `detail_decision`，整条 chain 从「1800 s 预算耗尽」变成「6.8 分钟跑完全部前面的阶段」。`saf_import` 本身没有出现在失败栈里，也就没有再打满预算。

**结论二：新的阻塞是 rig 的深行寻址，不是产品缺陷。** 复核列表（D-293 起）首屏只读 50 行，而 `openCandidateByAmount` 的机制是「seek 到列表顶部 → `ACTION_SCROLL_TO_POSITION` 跳到 `rowsPerSession + 12` → 有界 160 步前向扫描」。在 61k 规模下目标候选在**第 10,012 位**：跳转索引落在未装载区间，扫描 65 步后判定未达（`sawTargetRow=false`）。要真正到达，需要驱动 `加载更多导入候选` 约 200 次（每次 50 行），既超 180 s 的 stage 预算也没有意义。

**因此 V-12 / V-13 仍不判 PASS**：`saf_import` 这一半已由结论一解除，但 rig 在 `detail_decision` 无法完成，`phases` 只到 `prepare`。**下一动作（承接，需单独裁决）**：maximum rig 的深行寻址必须换机制——候选是二选一：①把 `detail_decision` 的目标改成**落在首页**的候选（这会打破「唯一 amount 全局唯一」的寻址前提，需重新设计寻址键）；②给 rig 一条**有界的深行通路**（例如按 `candidate_id` 键集直接跳页的测试专用入口，或先按会话/组过滤再寻址）。无论哪条都要单独的设计门，且**不得**用「调大 stage 预算」或「跳过该 stage」来解决。

---

## 14. D-8 第二片实施登记：批量确认与批后刷新整表读有界化（2026-11-14，随 D-321 批；§1–§13 正文未改）

**交付（scope = D-320 残余① 点名的三个站点）**：

1. **`:3203` 批量派发前相**（`runImportBatchDispatch`，`fac241f6` 当前位置）：`undispatched` 在主线程捕获处（同一快照、同一时刻）派生 `undispatchedCandidateIds`，前相 `loadRows` 改为 `queryRowsForCandidates(ledgerId, undispatchedCandidateIds)`。空 id 集经适配器卫式返回 `Rows(empty)`，零项循环正常完成。
2. **`:3370` Unknown 核对前相**（`checkImportUnknownItem`）：id 集 = 单个 `item.candidateId`，同上改法。
3. **`:3153` `requestImportReviewRowsRead()`**（批后刷新）：主线程在启动协程前捕获 `latestState` 复核视图的**已装载窗口**候选 id 集与 `nextAfterCandidateId`；窗口为空则**直接返回、不派发**（见下方登记①）。租约结构（`ledger.leased` + `isCurrentGeneration` 代际丢弃）与表面卫式 `ledger.surfaces.importReviewRows` 原样保留；`Rows` 分支以捕获游标经既有 `P503UiEvent.ImportReviewResult` 落地，`Unavailable` 分支照旧派发（reducer 保留旧行与旧游标）。

**机制**：新增命名查询 `importReviewRowsForCandidates`（投影、JOIN/LEFT JOIN 形状、相关子查询与 `ORDER BY candidate.candidate_id, duplicate.candidate_id` 与 `importReviewRowsForLedgerPage` 字节一致；WHERE 收窄为 `candidate.ledger_id = :ledger_id AND candidate.candidate_id IN :candidate_ids`）→ 适配器 `loadImportReviewRowsForCandidateIds`（空集卫式返回空列表、不执行 SQL；同一 `foldCandidateRuns` 折叠）→ 端口 `loadImportReviewRowsForCandidateIds`（fail-loud 默认）→ 用例 `QueryImportReviewRows.queryRowsForCandidates`（异常映射 `Unavailable`，与 `query`/`queryPage` 同纪律）。

**语义等价证据（`file:line`）**：

- 前相消费者只按候选 id 找行：`admitImportBatchItem`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportBatchPresentation.kt:344`）与 `importUnknownCheckContext`（`:410`），均为 `rows.rows.firstOrNull { it.candidateId == item.candidateId }`。按未派发项候选 id 的有界读恰好返回这些查找能消费的行；两条查询都无状态谓词、无会话谓词，故 **行在场 ⟺ 候选存在**——整表读对这组 id 能给出的全部相关内容。
- 复核列表是一个 candidate_id 有序窗口（`requestImportReview` 首页读 `P503App.kt:816`，加载更多按 keyset 追加 `:2579`）：重读**同一 id 集**并经既有 `P503UiEvent.ImportReviewResult` 落地，以相同 candidate 序替换窗口——**零 reducer 改动**。游标语义：`reduceImportReviewResult`（`P503Reducer.kt:2204-2218`）在 `Rows` 落 `event.nextAfterCandidateId`（`:2213`），在 `Unavailable` 保留旧行与旧游标（`:2215-2217`），故失败刷新既不缩短窗口也不会让加载更多从头开始。
- **刷新竞态的可见瞬态（登记，非静默）**：若 `加载更多` 追加页落地（`reduceAppendImportReviewPage`，`P503Reducer.kt:2225-2243`，rows+cursor 写在 `:2235-2237`）或一次接治落地与在飞的有界刷新交错，刷新的 `Rows` 会把窗口替换回**捕获时刻的 id 集**并把游标恢复为**捕获时刻**（追加前）的值——屏上瞬态回退到追加前形态。**自愈性**：游标被保留，下一次「加载更多」按捕获游标重新取页，无持久丢失（不违反 `ACC-COUNT-01` 的静默丢弃禁令——行只是暂时不在屏上，读路径无损失）。**对照旧实现同形竞态更差**：整表读落地把游标重置为 `ImportReviewResult.nextAfterCandidateId` 的默认 `null`（`P503UiEvent.kt:745`），追加页被复制重读。新实现用捕获游标把该竞态的自愈面收窄，不新增机制。

**D-7 裁决关闭（索引项）**：云端 CI run `43407734033`（job `ledger-data shard 1`，2026-11-13）的 `EXPLAIN QUERY PLAN` 显示两条候选行查询的每条访问路径均为 `SEARCH … USING INDEX …`，无任何 `SCAN`；`import_evidence` 探针走 `sqlite_autoindex_import_evidence_1 (ledger_id=?)` 自动索引。按 D-7 本文（§3 D-7「若显示已走自动索引，则本项关闭并只保留读数」），**索引裁决关闭、零 DDL**（schema 版本保持 v37）；61k 规模的页耗时读数仍 registered-open（需 CI 不提供的夹具环境），无任何裁决等待它们。本片新增的 `importReviewRowsForCandidates` 沿用同一批已关闭的自动索引探针（逐输出行的 `min(evidence_id)` 标量与 active-link `EXISTS` 未改）。

**V-9 扩展（计划钉点）**：`P704B4SessionPagePlanTest` 增两条 `P704B4-PLAN ` 钉点——(a) `importReviewRowsForCandidates`（`IN` 集合参数展开为字面量元组 `('p704-b4-plan-1','p704-b4-plan-2')`）；(b) `importDuplicateReviewsForSessionMemberPage`（`Ledger.sq:11831`，§11 第 3/4 条点名、D-7 测量腿的成员复核读）。既有「session-vs-ledger 文本等价」测试原样未动；`P704B4-PLAN ` 前缀已在 `tools/ci/scale-readings-report.py` 的 `DEFAULT_PREFIXES` 登记（本批未改该脚本）。

**等价测试**：`ImportReviewPagedReadEquivalenceTest` 增 D-8-2ND-T-1..6 六例：单 id / 多 id（乱序请求、按候选序返回）/ 带重复的候选（折叠同一 blocking-first 判定）/ 空集（适配器卫式，结构证明为「驱动已关闭的库上执行任何语句必抛」）/ 跨会话 id（账本域读无会话谓词，行必须返回）/ 未知 id（不产行）+ 用例异常映射 `Unavailable`。

**边界外站点（本批不改，各自闭合登记）**：

- **`:2659` 重复审核提交后刷新**：关闭登记——该读同时服务「详情页 + 复核行 + 重复集」的一次性联合刷新（`ImportDuplicateReviewRefresh` 三载荷同租约），且其消费者按候选 id 找行，但事件契约是整表语义的 `Rows` 覆盖；本批未动，闭合条件 = 后续片若做详情路径有界化时一并裁决。
- **`:3127` 整组处置落地重读**：关闭登记——整组确认落地的行载荷直接进 `ImportDuplicateGroupDispositionResult`，消费方为组卡行序（整组语义），有界化需先裁决「窗口外行」的呈现语义；本批未动。
- 实施期全仓 grep 确认：本批后 `P503App.kt` 的 UI 路径整表 `query(` 调用点 = **2 处**（`:2659` 与 `:3127`），与上述登记一一对应。

**实施期登记（非静默变更）**：

1. **空窗口提前返回**：装载窗口为空时 `requestImportReviewRowsRead` 直接返回、不派发任何事件。旧行为在该形态下会派发一次整表读并以 `Rows(empty)`/`Unavailable` 落地（对空视图无可见效果差）；新行为省掉该往返。登记为实现注册，不属语义收紧（`ImportReviewView()` 默认空视图的首次装载走 `requestImportReview()`，不经本方法）。
2. **测试侧 fake 的最小覆盖**：`P706RealJobLeaseConcurrencyTest.FixedImportReviewReadPort` 增 `loadImportReviewRowsForCandidateIds` 覆盖（其 `runImportDispatch` 复刻生产前相形状，改为 `queryRowsForCandidates`）；`MemberPageReadPort`（`P503ImportGroupMemberWindowHostTest`）不驱动三个改动站点，未动。全仓 fake 扫描命令与残余命中数见 D-321 的验收记录。
3. **计划钉点参数表**：`BINDS` 从「五参数」扩为「六参数」（`candidate_ids` 加入）；既有两条钉点与文本等价测试的参数面未变。

**边界**：入 = 本节、`Ledger.sq`（新命名查询，零 DDL/零迁移）、`SqlDelightImportReviewReadAdapter.kt`、`ImportReviewReadPort.kt`、`P503App.kt` 三站点、两测试文件、`P706RealJobLeaseConcurrencyTest` 的 fake 覆盖、D-321 条目。出 = 不改 thresholds / 统计量 / marker / stage 预算；不改 `D-230`/`D-243`/`D-278`–`D-284`/`D-293`/`D-294`/`D-308` 的裁决文本与开放状态；不改 `.external/`；`:2659`/`:3127` 两站点按上文登记闭合，本批不改。
