# P7-04 导入审核 61k 可操作性缺陷设计门（`ACC-D-PERF-01`）——机制族、语义不变量与开放项

状态：approved（2026-10-18 设计门闭环：独立规格评审 + 独立质量评审（均 APPROVE-WITH-FINDINGS，`currentBatchBlockers` 空）+ distinct verifier PASS 后，主代理按常设授权作 J1–J9 裁决并批准本文；批准依据 = `docs/DECISIONS.md` **D-230**（含裁决回填）。本文只冻结**设计级**问题面、机制族比较框架、语义不变量与开放项归属，**不**构成产品行为、迁移、技术选型或发布授权；**不选机制**（J1 见 §10）、不写实现、不写迁移文件、**不分配 schema 版本号**。零产品代码、零测试、零 schema/迁移/依赖。）

**Revision:** v0.1（2026-10-18 首轮起草）。本文是新建设计门规格，承接 D-169 第 5 条登记的 61k 规模可操作性缺陷（`ACC-D-PERF-01` 在 61k **NOT MET**，维持开放）与 D-171/D-175 的两次归因登记，冻结该缺陷的**问题面、候选机制族、语义不变量、本批验收项索引与开放项**。本文**不改写**任何既有决定条目、**不取代**任何既有 approved 规格的裁决（A-PERF approved 规格本体按 D-168 第 3 条既定治理不改写），**不声称任何验收向量 PASS**。依据：阶段计划 §2 A-PERF 与 §10.1/§10.3/§10.4.1；`docs/DECISIONS.md` D-168/D-169/D-170/D-171/D-175/D-228/D-229；既有 OOM 修复规格 `docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md`（approved）；A-PERF 规格 `docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（approved）；以及本次起草在基点 `1eff1dc` 上的只读复核（行号/命令/提交计数，逐处标注）。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为当前 worktree 基点 `1eff1dc` 实读行号；`.local.md` 族与本地证据包以主 checkout 为基准只读打开，理由见下）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`（本地 ignored 计划，主 checkout 只读）
  - §3 收口判据 `:50` 原文「优先承接 A-PERF 测量复核、D-159 规模整链与已知开放缺陷；已完成的 A-01/A-02/A-04 不整批重做」。
  - §2 A-PERF 行 `:36` 红线原文「禁止靠截断、隐藏待确认项或更改组审核范围提速」。
  - §2 A-PERF 行 `:42` 分页许可条件原文「分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性」（五项）。
  - §2 A-PERF 行 `:44` 原文「禁止只计 SQL 或组枚举时间，却把解析收尾、数据映射、等待数据库连接和 UI 可操作前的时间排除后宣布整链达标」。
  - §10.1 `:383` 要求「每批设计冻结前，为该批原始要求分配稳定的验收项 ID；…新任务使用子项 ID 加序号」。
  - §10.4.1 `:422` 要求范围变更「记录原文、变更后内容、授权、影响和承接项」五要素。
  - §10.4.5 `:426` 要求「仍有必需项非 PASS 时，报告『部分完成/阻塞』」。
- **决定（已确认，只读引用，不改写）**：
  - `docs/DECISIONS.md:3239` **D-168**（OOM 修复设计批准与范围取代登记：其第 3 条登记对 A-PERF approved 规格「不改分页」这一条非目标的显式取代，并明写「A-PERF 规格本体**不改写**，只在其**下次实质修改时**同步该非目标的解除记录」）。
  - `docs/DECISIONS.md:3263` **D-169**（第 5 条登记 61k 规模可操作性缺陷 `ACC-D-PERF-01` **NOT MET**、维持开放；第 6 条列 `ACC-D-CONN-01` 与 `ACC-D-20K-01` 为开放设备向量）。
  - `docs/DECISIONS.md:3317` **D-171**（宿主侧分量归因：side A 分页读 + 折叠 ≈2.0–2.4 s/轮、side B 急切渲染模型 ≈10–50 ms/轮；并更正 D-169 第 5 条的代码定位推理）。
  - `docs/DECISIONS.md:3414` **D-175**（atrace 设备侧归因与对 D-171 假设的更正：主线程在 ~13.5 s `doFrame` 间隔内睡眠、仅 ~0.5 s CPU；`Dispatchers.Default` ~13.2 s CPU、GC ~1.2 s、堆 10→96 MB）。
  - `docs/DECISIONS.md:4772` **D-229**（当前末条；本条新决定 D-230 追加其后）。
- **既有 approved 规格（只读引用，本体不改写）**：
  - `docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md`（OOM 修复规格）：`:3` 状态 `approved`；`:284-294` §3.1 数值阈值（峰值堆 ≤160 MB / 列表可操作 ≤3 s / 零 ANR-OOM-FATAL）；`:332-364` §7 验收项索引（稳定 ID 表）；`:347` `P704OOM-ACC-THRESH-01` 行；`:277` §3 第 4 项 `ACC-COUNT-01`（完整、永不截断）；`:380-384` §9 交付物与范围外。**行号口径（本批新增）**：上列行号均为**基点 `1eff1dc`** 的实读行号，即本批向该文件 `:5` 与 `:368` 各插入 2 行指针**之前**的行号；在冻结候选上打开同一文件时须整体 **+2 偏移**（§3.1 起于 `:286`、`P704OOM-ACC-THRESH-01` 在 `:349`、`P704OOM-D-PERF-01` 在 `:364`、§7 起于 `:334`、§9 起于 `:382`）。
  - `docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（A-PERF 规格）：`:66`（列表读本体保留整账本读，**本批不改分页、不做投影裁剪**）；`:84`（不做分页/截断/隐藏待确认项/更改组审核范围）。
- **设备侧归因报告（只读，主 checkout）**：`local/artifacts/p7-05-scale/attribution/61k-atrace-attribution.md`（D-175 的原始归因报告；`:5` 含关于应用 UI 自该提交后无变更的自述，其适用面见 §1.4）。
- **不可触碰面**：项目外部只读参考树只读；零 DDL、零 schema 变更（schema 维持 v35、34 个 `.sqm`）、零迁移；零产品代码/测试/依赖变更；不改 D-169/D-171/D-175 既有登记文本；不改 A-PERF approved 规格本体；不改既有 OOM 修复规格的机制、阈值、§4/§5 证据与其余下裁决。

**事件纪律（本节约束全文）**：正文引用一律写清出处类型。凡**本次只读复核**（在基点 `1eff1dc` 的工作树或主 checkout 上直接运行命令取得）的结论，一律标为「本次只读复核（`<path>:<line>` 或 `<命令>`）」并给出可定位锚点；此类结论**不是**已裁决、**不是**冻结、**不是**权威。无 tracked 出处的判断一律列入 §7 开放项。

## 1. 问题现状（如实摘述）

### 1.1 61k 冷进入读数与 `ACC-D-PERF-01` 状态（D-169 第 5 条）

- 61k 库上冷启动首次进入导入审核列表的 tap→列表内容可见耗时，6 轮读数 **10869 / 11000 / 11459 / 11397 / 11695 / 11705 ms（≈11.0–11.7 s）**；「刷新清单」重读约 **9–11 s**；**超出** OOM 修复规格 §3.1 冻结的 **≤3 s** 阈值（`docs/DECISIONS.md:3277`）。
- **口径披露（不得省略）**：该 6 轮读数取自**截图尺寸轮询启发式**（首张 >250 KB 截图即判「已加载」），**不是**冻结的 B2 首帧口径；冻结 B2 方法（`/proc/uptime` 夹逼 + `dumpsys gfxinfo framestats` 首帧）的**首帧读数仅取自 20k 对照库**（`docs/DECISIONS.md:3277`）。故 61k 的 NOT MET 结论建立在截图轮询口径的读数上，该口径本身含 ~2.4 s 轮询地板。
- **61k 侧唯一原始工件（本次只读复核，主 checkout）**：`local/artifacts/p7-05-scale/oomfix-evidence/b2-61k-bracket-raw.txt`（25 B，内容仅 `up0=5671.57 up1=5671.65`，`grep -ci framestats` = **0**）——即 61k 上只留存了 `/proc/uptime` 夹逼值，**无 framestats 首帧行**。这与「61k 无冻结 B2 首帧读数」的主张互相印证；20k 对照库的 B2 首帧读数（0.035/0.035/0.041 s）本身亦无留存原始工件（D-169 第 5 条已披露）。
- **结论**：`ACC-D-PERF-01` 在 61k **NOT MET**，**维持开放**；D-169 第 7 条明写「≤160 MB 与零 ANR/OOM 部分通过；≤3 s 可操作性部分在 61k NOT MET」。本条规格**不改**该状态、**不改**其测量口径、**不**把该项并入 PASS。

### 1.2 宿主侧分量（D-171）

- **side A（分页读 + 折叠）**：`SqlDelightImportReviewReadAdapter.loadImportReviewRows` 在真实 61k 夹具副本上 ≈ **2.0–2.4 s/轮**（写者/评审者/verifier 三方读数区间）。
- **side B（急切渲染模型构建）**：`importReviewRenderItems` 在 61,000 行列表上 ≈ **10–50 ms/轮**，渲染模型条目数 **61,011**。
- 合计 ≈ **2.4 s**，即 D-169 登记的 ≈11 s 中约 2.4 s 由这两个宿主侧分量解释（`docs/DECISIONS.md:3327-3329`）。
- **披露（沿 D-171 第 2 条）**：以上为**宿主 JVM 单次运行值**（非设备读数），**不具跨运行可复现性**、**无留存原始工件**；只作区间引用，不取点估计。

### 1.3 设备侧 atrace（D-175）

- tap 后主线程出现 **~13.5 s 的 `Choreographer#doFrame` 间隔**；**该间隔内主线程处于睡眠**（仅周期性 `MSG_CHECK_INVALIDATION_IDLE`），**仅消耗 ~0.5 s CPU**；`ImportReviewResult` 在间隔末尾才派发。
- 按 `sched_switch` 归因：应用 `Dispatchers.Default` 线程 **~13.2 s CPU**；GC（`HeapTaskDaemon`）~1.2 s CPU；主线程 ~0.5 s；`RenderThread` ~0.33 s。应用堆从 ~10 MB 增至 **~96 MB**，间隔内 8 次并发标记-压缩 GC 周期。
- **结论（D-175 第 4 条）**：残余耗时**不是主线程 UI 组合/重组**（主线程在窗口内睡眠）；**主要由 off-UI-thread 的行物化工作与其引发的 GC 压力构成**；D-171 的「最可能为设备侧 UI 组合/重组」假设被证伪。D-175 第 5 条同时登记**未证事项**：`DefaultDispatch` 的 ~13.2 s 在「读/映射 CPU」与「GC 检查点 CPU」之间的**比例未定**；模拟器绝对值不代表真机。

### 1.4 D-175 适用面（本条为必写实质事实，不作结论化）

D-175 的设备侧分解是在候选 `5ea7f67` 上取得的。该归因报告自述其适用面如下：

- `local/artifacts/p7-05-scale/attribution/61k-atrace-attribution.md:5` 自述「应用 UI 自该提交后无变更（其后仅文档提交）」。

**本次只读复核表明该自述与仓库现实不符**（命令与结果逐条如下，均在基点 `1eff1dc` 上运行）：

- `git rev-list --count 5ea7f67..1eff1dc` = **417**（自 `5ea7f67` 至基点 `1eff1dc` 共 417 次提交。**基点语义**：同一命令写 `..HEAD` 在冻结候选上为 **418**，因本批新增 1 个提交；下文所有计数均以显式 `1eff1dc` 为准）。
- `git rev-list --count 5ea7f67..1eff1dc -- app-ui/src/commonMain` = **62**（其中 62 次触及 `app-ui` 的 commonMain）。
- 宿主读链已改经租约面（本次只读复核）：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:2376` 为 `val outcome = ledger.leased { facade, _ -> facade.queryImportReviewRows?.query(ledger.ledgerId) }`。同类 `queryImportReviewRows` 读点的包裹形态逐处核对如下（本次只读复核，逐行实读）：`:2019` 为直接的 `ledger.leased { … }`；`:1847`、`:2098`、`:2426`、`:2593` 各自位于外层 `ledger.leased { … }` 体内（外层面分别在 `:1830`、`:2096`、`:2422`、`:2589`）；`:2350` 的包裹形态**不是**租约而是 `ledger.probe { … }`（该读点上下文 `:2345-2350` 自述为 single-flight 槽位释放防泄漏路径）。
- **但导入审核渲染模型面 0 提交**（同一 `git rev-list --count` 命令逐文件运行）：
  - `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportReviewReadAdapter.kt` = **0**
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt` = **0**
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReview.kt` = **0**
  - `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ImportReviewReadPort.kt` = **0**
- **锚点纪律**：`git rev-list --count` 对**不存在的路径**同样返回 **0**（本次以不存在的路径名实测，退出码 0），故「0 提交」只有在路径正确时才是证据；上列四条路径均已逐字核对存在。

**结论写法（不得升格、不得当作既定事实）**：

- D-171 的 **side B** 读数（急切渲染模型 ≈10–50 ms/轮）**仍适用**——其目标文件 `P503ImportReviewPresentation.kt` 自 `5ea7f67` 起 0 提交。
- D-175 的**设备侧分解**（`Dispatchers.Default` ~13.2 s CPU、堆 10→96 MB、8 次 GC 周期）**必须限定为候选 `5ea7f67` 专属**，或明确列为**待重测**；**不得当作当前基点 `1eff1dc` 基线的既定事实**——因为宿主读链已改经租约面（62 次 `app-ui/src/commonMain` 提交），而设备侧分解的取得候选早于这些变更。
- 该重测义务登记为 §7 开放项 **J5**，归属**实施批或设备证据批**。

## 2. 现状实现面（实测锚点）

### 2.1 分批读已落地（D-168 实施 `1398e72`）

以下均为本次只读复核（基点 `1eff1dc`，`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportReviewReadAdapter.kt` 实读行号）：

- `:23` `private const val IMPORT_REVIEW_ROWS_PAGE_SIZE = 10_000L`（批大小常量，61k 候选 → 7 批）。
- `:56` 构造函数可注入 `pageSize: Long = IMPORT_REVIEW_ROWS_PAGE_SIZE`（注释说明 `Long` 因 SQLDelight 将 `LIMIT` 绑定类型化为 INTEGER）。
- `:63` `require(pageSize >= 1) { "import review page size must be >= 1, was $pageSize" }`（进入查询前拒绝退化值）。
- `:72` 单事务包裹：`database.transactionWithResult(noEnclosing = true) { … }`（`:64-71` 注释登记快照一致性与「连接持有窗口跨折叠」的代价）。
- `:82-91` 空串候选 id fail-loud 探针：以既有主键存在性查询检测 `candidate_id == ""`，命中即抛 `IllegalStateException`（避免该候选被 keyset 游标永久跳过）。
- `:94-110` 批循环：`while (true)` 内调用 `importReviewRowsForLedgerPage`，逐批 `groupBy` + `toImportReviewRow` 折叠，`result +=` 累积。
- `:108` 终止条件：`if (batch.size.toLong() < pageSize) break`；`:109` 游标推进 `afterCandidateId = batch.maxOf { it.candidate_id }`。

### 2.2 输出仍为完整列表

- `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ImportReviewReadPort.kt:108` 签名：`fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow>`——**分批是 adapter 内部实现，端口签名未变**。
- 同文件 `:234` 映射为 `ImportReviewRowsResult.Rows(readPort.loadImportReviewRows(ledgerId))`（`QueryImportReviewRows.query`；失败路径 `catch (failure: Exception)` → `ImportReviewRowsResult.Unavailable`，fail-closed 不返回部分列表）。

### 2.3 渲染模型与调用点（每次组合重算、未记忆化）

- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:843` `internal fun importReviewRenderItems(view: ImportReviewView?, platform: ImportPlatformKind?): List<ImportReviewRenderItem>`。
- 同文件 `:113-117` `internal fun importCandidateClassGroups(rows: List<ImportReviewRow>)`：按 `IMPORT_CANDIDATE_CLASS_DISPLAY_ORDER` 六类展示序对**全列表**各跑一次 `filter`。
- 同文件 `:884` 组头 `ImportReviewRenderItem.GroupHeader(group.classToken, group.rows.size)`、`:885` 行 `group.rows.forEach { … CandidateItem(row, selected = …) }`。
- 调用点：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReview.kt:377` `items = importReviewRenderItems(view, platform)`，位于 `LazyColumn` 的 `items(...)` 实参位置（`:376-380`）——**每次组合重算、未记忆化**（本次只读复核，基点 `1eff1dc`）。

## 3. 机制族（比较框架；具体实现取舍归实施批，见 §10 J1）

本规格冻结的是**问题面与候选机制族的比较框架**，不是具体机制的实现选择。下列五族逐条给出：精确改动面、内存口径论证、耗时口径论证、对 §4 各不变量的影响、回退、风险、以及**裁决归属**。**J1 的方向偏好已由 D-230 裁决（见 §7 第 1 条与 §10），但具体机制的最终取舍归实施批**，且须先取得 J5 的重测读数。

### 3.1 机制 (a)：流式/有界窗口物化

- **精确改动面**：`ImportReviewReadPort.kt:108` 的返回类型由 `List<ImportReviewRow>` 改为有界窗口/流式形态（`Sequence`/回调/分块结果），并改其所有消费点（见 §5 的 `.rows` 消费点判据）。
- **内存口径**：可消除「完整折叠结果与渲染模型同时驻留」——理论上把峰值降到窗口大小量级。
- **耗时口径**：读侧 CPU 不必然下降；GC 压力可能因分配次数增加而变化。须按计划 `:44` 口径**计入连接等待、映射与 UI 可操作前时间**，不得只计 SQL。
- **对 §4 不变量的影响**：**直接冲击 `ACC-COUNT-01`（完整、永不截断）与 `ACC-SEL-01`（跨页勾选）**——「完整列表」语义与「流式窗口」在端口层不共存；`ACC-TX-01`（连接持有窗口）可能因分块读取而拉长。
- **回退**：改回 `List` 返回形态；回退面大（端口 + 全部消费点）。
- **风险**：**高**。端口签名变更会扩散到 app-ui/reducer/宿主全部 `.rows` 消费点（§5 判据的 11 处 + 组合装配），回归面最广。
- **裁决归属**：**主会话裁决（J1）**；且若选此族，因改变「完整列表」语义，须另行范围裁决。

### 3.2 机制 (b)：保持完整 `List` 但压缩瞬时共存结构

- **精确改动面**：集中在 `SqlDelightImportReviewReadAdapter.kt:94-110` 的批循环与折叠结构（减少逐批 `toColumns()` 投影列表与 `groupBy` map 的瞬时共存），端口与 UI 零改动。
- **内存口径**：**这正是 D-168 已走的路线**（「批内折叠留事务内」使逐批结构在下一批前释放，见 `:64-71` 注释）。其内存收益来自「结构不再同时存活」的**结构变化**，**不是**量级断言（OOM 规格 §2.4 `:239`/`:246`/`:247` 已撤回「1/7」「一个数量级」——撤回语句在 **§2.4 内存面分析**内，不在 §1.2 行数推演或 §8 只读复核证据内）。
- **耗时口径**：与 side A 读数（≈2.0–2.4 s/轮，D-171）直接相关；须按 `:44` 口径计入连接等待。
- **对 §4 不变量的影响**：**中性偏好**——端口签名、UI 可见结果、排序、快照一致性均不变；`ACC-TX-01` 的连接持有窗口仍为「~7 次查询 + 逐批折叠」。
- **回退**：小（adapter 单函数）。
- **风险**：**低**。但 D-171 的 side A 仅 ≈2.4 s，**单独不足以解释** D-169 的 ≈11 s（D-171 第 3 条明写残余 ≈8.6 s 不能由 side A 或 side B 解释）。
- **裁决归属**：**主会话裁决（J1）**——是否继续在此族内加深优化，取决于 J5（设备侧重测）结果。

### 3.3 机制 (c)：渲染模型侧记忆化与分组增量化

- **精确改动面**：`P503ImportReviewPresentation.kt:843` `importReviewRenderItems` 与 `:113-117` `importCandidateClassGroups`（对全列表各跑六次 `filter`）；调用点 `P503ImportReview.kt:377`（每次组合重算、未记忆化）。
- **内存口径**：记忆化会**增加**长期驻留（缓存住渲染模型），须评估是否把峰值推向 ≤160 MB 阈值。
- **耗时口径**：D-171 side B 实测 ≈10–50 ms/轮，相对 ≈11 s **可忽略**；记忆化对**总耗时**的收益上限受此约束。它主要改善**滚动/重组的重复计算**，不是冷进入瓶颈。
- **对 §4 不变量的影响**：**中性**——渲染模型条目内容不变；`ACC-GROUP-01`（组枚举完整性）与 `ACC-FOLD-01`（blocking-first）须保持逐字节一致。
- **须一并冻结的列表身份语义（本次只读复核）**：调用点 `P503ImportReview.kt:377` 为 `key = { it.stableKey }`、`:378` 为 `contentType = { it.contentType }`。记忆化/分组增量若改变渲染模型条目的**身份或顺序**，会改变 `LazyColumn` 的滚动位置语义（用户在长列表中的位置可能跳变）。故若采纳本族，须额外冻结：**`stableKey` 与 `contentType` 逐条不变、条目顺序不变**，并在实施批以 `P503ImportReview.kt:377-378` 为复核点。
- **回退**：小（presentation 单函数 + 调用点）。
- **风险**：**低**，但**预期收益低**（side B 已证非瓶颈）。
- **裁决归属**：**主会话裁决（J1）**；若并入，属「顺带优化」而非主修复路线。

### 3.4 机制 (d)：UI 分页

- **精确改动面**：`P503ImportReview.kt` 的 `LazyColumn` 改为分页加载 + 页面导航；`ImportReviewView.rows` 的消费点（§5 判据 11 处）全部受影响。
- **内存口径**：可把 UI 侧驻留降到单页量级。
- **耗时口径**：首屏可操作时间可下降，但**总体验证**须覆盖「翻到末页」的累计成本（计划 §10.3 `:416` 要求「首/末项可达」）。
- **对 §4 不变量的影响**：**改变「完整列表」语义**（`ACC-COUNT-01`）与跨页勾选（`ACC-SEL-01`）——计划 `:42` 已为此预留路径，但要求**排序/快照一致性/跨页勾选/总数及组枚举完整性五项分别冻结**。**截断、隐藏待确认项、更改组审核范围永久禁止**（计划 `:36`）。另须计入 **`ACC-TX-01` 连接持有**：若按页重读，每次翻页可能在非 WAL 单连接上重新取 `BEGIN IMMEDIATE`，按页累计的连接持有窗口与后台读独占风险须一并实测（OOM 规格 §2.5 的 D-148 ANR 链同型风险）。
- **回退**：大（UI + 消费点 + 语义）。
- **风险**：**高**；且与既有 OOM 规格 §3 第 3 项「UI 层无『页』概念」的冻结陈述**存在张力**，须另行范围裁决。
- **裁决归属**：**主会话裁决（J1）+ 另行范围裁决**（不属本规格可自行决定的范围）。

### 3.5 机制 (e)：批大小调参

- **精确改动面**：`SqlDelightImportReviewReadAdapter.kt:23` 常量与 `:56` 构造参数（既有 OOM 规格 §6 第 1 项已把调参列为「实施内调整」，不改契约）。
- **内存口径**：OOM 规格 §8 第 4 项给出批行数序列——pageSize=10,000 → 最坏批 52,000 行；pageSize=5,000 → 最坏 27,000 行；pageSize=2,000 → 最坏 12,000 行。调参摊薄单批物化。
- **耗时口径**：批数增加 → 每批固定开销（一次查询执行 + 事务内步进）累加；须按 `:44` 口径实测，不得只计 SQL。
- **对 §4 不变量的影响**：**中性**——批大小不改变端口、UI、排序、快照、折叠语义；`ACC-TX-01` 的连接持有窗口随批数变化（更多批 → 更长持有）。
- **回退**：极小（改常量）。
- **风险**：**低**；但 D-171 side A ≈2.4 s 表明**读侧不是 ≈11 s 的主因**，调参的预期总收益受限。
- **裁决归属**：**主会话裁决（J1）/ 实施批**——OOM 规格 §6 第 1 项已授权为实施内调整，但本规格不预先宣称其效果。

**机制族小结（不裁决）**：机制 (b)/(e) 是低风险、低扩散面但**预期收益受 D-171 side A ≈2.4 s 上限约束**的路线；(a)/(d) 触及「完整列表」语义，须另行范围裁决；(c) 预期收益已被 side B 读数压到可忽略。D-175 把残余指向**off-thread 行物化与 GC 压力**，但该归因的**适用面受 §1.4 限制**（候选 `5ea7f67` 专属或待重测）。**J1 由主会话裁决，本规格不选机制。**

## 4. 语义不变量冻结

本设计门对以下不变量逐条冻结（每条映射既有稳定 ID）。**截断、隐藏待确认项、更改组审核范围永久禁止**（计划 `:36`）；任何机制族都不得违反本节。

1. **完整列表 / 永不截断（`ACC-COUNT-01`）**：列表读的返回值必须是完整列表，**永不截断、永不静默丢候选**；任何读取失败必须整体失败并映射 `Unavailable`，不得返回部分结果（OOM 规格 `:277` §3 第 4 项）。**本项冻结语义，不冻结条件性结果**——61k 规模是否已达成本项由设备读数裁决。
2. **排序与批间累积（`ACC-SORT-01`）**：`ORDER BY candidate.candidate_id, duplicate.candidate_id` 逐字节保留；批间按 `candidate_id` 升序累积；含 collation 前提（Kotlin UTF-16 序 vs SQLite BINARY 序，成立条件 = `candidate_id` 限于 BMP）。
3. **快照一致性（`ACC-SNAP-01`）**：全部批次在**单个事务**内执行；一致性为事务级，不弱于现状语句级快照。
4. **折叠语义 blocking-first（`ACC-FOLD-01`）**：`CONFIRMED_DUPLICATE` < `DEFERRED` < `CONFIRMED_DISTINCT` < `DISMISSED_LOOKALIKE` < `REJECTED` 的 fold 顺序与现状逐字节一致。
5. **组枚举完整性（`ACC-GROUP-01`）**：组枚举/整组处置读取完整投影，**无任何组因分批缺失**。
6. **跨页勾选（`ACC-SEL-01`）**：UI 层无「页」概念时，`view.rows` 仍是完整折叠结果，勾选/批量授权/整组处置读取同一完整列表。
7. **非嵌套契约与连接持有（`ACC-TX-01` / `ACC-D-CONN-01`）**：`noEnclosing = true`（不改 BEGIN 模式）、逐批折叠留事务内及其代价登记、非嵌套契约；连接持有窗口严格长于现状单语句读，`ACC-D-CONN-01` 为必测设备向量（D-168 第 5(b) 条）。
8. **阈值（`ACC-THRESH-01`）**：峰值堆 **≤160 MB** / 列表可操作 **≤3 s** / **零** ANR-OOM-FATAL（OOM 规格 `:284-294` §3.1）。**本项为冻结阈值，不是已达成结果**；`≤3 s` 在 61k 现为 NOT MET。

## 5. 本批验收项索引（稳定 ID）

**前缀选型说明（本节为建议，非裁决）**：计划 §10.1 `:383` 要求为每批原始要求分配稳定验收项 ID，且「新任务使用子项 ID 加序号」。既有稳定 ID 前缀为 `P704OOM`（OOM 修复规格）与 `P704`（更早批次）。本设计门建议使用前缀 **`P704OP`**（operability），理由：语义上区分「可操作性设计门」与「OOM 修复」，且与既有两个前缀无碰撞（本次只读复核：`git grep -n 'P704OP'` 在基点 tracked 树上 **0 命中、退出码 1**；`git grep -l 'P704OOM' -- docs` 仅命中既有 OOM 规格一份、退出码 0）。**该前缀选型须经主会话确认后方为冻结**（§7 开放项 J6）。

| ID | 类型 | 内容 | 承载位置 |
| --- | --- | --- | --- |
| `P704OP-ACC-COUNT-01` | 冻结 | 完整列表 / 永不截断语义（不冻结条件性结果） | §4 第 1 项 |
| `P704OP-ACC-SORT-01` | 冻结 | 排序与批间累积（含 collation 前提） | §4 第 2 项 |
| `P704OP-ACC-SNAP-01` | 冻结 | 快照一致性（事务级） | §4 第 3 项 |
| `P704OP-ACC-FOLD-01` | 冻结 | 折叠语义 blocking-first | §4 第 4 项 |
| `P704OP-ACC-GROUP-01` | 冻结 | 组枚举完整性 | §4 第 5 项 |
| `P704OP-ACC-SEL-01` | 冻结 | 跨页勾选 / 完整列表语义 | §4 第 6 项 |
| `P704OP-ACC-TX-01` | 冻结 | 非嵌套契约与连接持有 | §4 第 7 项 |
| `P704OP-ACC-THRESH-01` | 冻结 | 阈值：≤160 MB / ≤3 s / 零 ANR-OOM | §4 第 8 项 |
| `P704OP-ACC-ROWS-01` | 契约 | `.rows` 消费点完备性（确切命令与完整命中集，见下） | §5 判据 |
| `P704OP-ACC-MECH-01` | 治理 | J1 机制族五条比较框架（不裁决机制） | §3 |
| `P704OP-ACC-D175-01` | 治理 | D-175 设备侧分解的适用面限定（候选专属或待重测） | §1.4 |

### 5.1 `.rows` 消费点完备性判据（`P704OP-ACC-ROWS-01`）

**判据钉死为确切命令**：

```
grep -rn 'view\.rows' app-ui/src/commonMain
```

本次只读复核（基点 `1eff1dc`）该命令返回 **11 处**，逐处如下：

| # | 位置 | 形态 |
| --- | --- | --- |
| 1 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:2192` | `enumerateImportDuplicateGroupItems(sessionInputRef, view.rows)` |
| 2 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:2210` | `enumerateImportDuplicateGroupItems(sessionInputRef, view.rows)` |
| 3 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportBatchPresentation.kt:75` | `view.rows.filter { it.candidateId in selected }` |
| 4 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportBatchPresentation.kt:87` | `view.rows.filter { … }.map { it.candidateId }` |
| 5 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:879` | `view.notice == null && view.rows.isEmpty()` |
| 6 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:883` | `importCandidateClassGroups(view.rows)` |
| 7 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:892` | `importDuplicateGroupRows(view.rows, sessionInputRef)` |
| 8 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt:1411` | `view.rows` |
| 9 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt:1461` | `view.rows.firstOrNull { it.candidateId == candidateId }` |
| 10 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt:1496` | `view.rows` |
| 11 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt:1536` | `view.rows` |

**完备性措辞（不得写成会把正确规格判死的不自洽断言）**：**本规格所载的 11 处 = 该命令在基点的完整命中集**。任何机制族若改变 `.rows` 的形态或消费方式，必须逐处复核上表 11 处并在实施批登记新增/移除的消费点。

**范围外命中必须显式标注**：同一目录下 `\.rows` 另有非 `view\.rows` 形态。本次只读复核 `grep -rn '\.rows' app-ui/src/commonMain` 共 **31 行**，减去上表 11 处，**范围外 20 行**逐类标注如下：

| 形态 | 行数 | 位置 |
| --- | --- | --- |
| `event.rows` | 6 | `P503Reducer.kt:1408`、`:1409`、`:1416`、`:1533`、`:1534`、`:1539` |
| `refresh.rows` | 4 | `P503Reducer.kt:1493`、`:1494`、`:1502`、`:1621` |
| `state.rows` | 2 | `P503CorrectionScreen.kt:283`、`:288` |
| `rows.rows` | 2 | `P503ImportBatchPresentation.kt:344`、`:410` |
| `prePhase.rows` | 2 | `P503App.kt:2447`、`:2600` |
| `group.rows` | 2 | `P503ImportReviewPresentation.kt:884`、`:885` |
| `result.rows` | 1 | `P503Reducer.kt:1444` |
| `current.rows` | 1 | `P503App.kt:4105` |

上列 20 行为**非 `view.rows` 形态**，不属本判据的消费点集；其中 `event.rows`/`refresh.rows`/`result.rows` 属 `ImportReviewRowsResult` 的载体字段，`group.rows`/`rows.rows`/`prePhase.rows`/`state.rows`/`current.rows` 属其他列表或结果类型。

## 6. 取代与承接（J3 为开放项）

### 6.1 与既有 approved 规格的关系（按计划 §10.4.1 五要素）

| 要素 | 内容 |
| --- | --- |
| **原文** | (1) A-PERF 规格 `docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md:66`「列表读本体 `loadImportReviewRows` 保留整账本读…**本批不改分页、不做投影裁剪**」、`:84`「不做分页/截断/隐藏待确认项/更改组审核范围」；(2) OOM 修复规格 `docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md:291`（该文件在基点 `1eff1dc` 的行号；冻结候选上为 `:293`）§3.1 的「列表读可操作耗时（61k）**≤ 3 s**」阈值行及其 `:362`（冻结候选上 `:364`）`P704OOM-D-PERF-01` 验收项。 |
| **变更后内容** | 本设计门**只承接** OOM 修复规格 §3.1 中 **`ACC-D-PERF-01` 的 ≤3 s 可操作性部分**——即把该项在 61k 规模的 NOT MET 状态、其口径披露与后续机制族/开放项纳入本设计门的治理范围。**本设计门不取代、不改写 A-PERF 规格的任何条款**，也不取代 OOM 修复规格的机制、阈值定义、§4/§5 证据与其余下裁决。 |
| **授权** | 计划 §3 `:50`「优先承接 A-PERF 测量复核、D-159 规模整链与已知开放缺陷」；计划 §10.1 `:383` 稳定验收项 ID 要求；D-169 第 5 条把该项登记为开放缺陷；D-175 第 6 条明写「不实施修复（修复批或规模口径裁决属后续）」。 |
| **影响** | 新增一份设计门规格（本文）；既有 OOM 规格的 `≤3 s` 可操作性部分被本文承接为**治理对象**（其 NOT MET 状态与阈值定义均不变）；A-PERF 规格本体**不改写**（D-168 第 3 条既定治理：其解除记录延至该规格下次实质修改）；零产品代码/测试/schema/迁移/依赖。 |
| **承接项** | (1) 本规格获批 → J1 机制族裁决与后续实施批；(2) J5 设备侧重测（D-175 分解的适用面）；(3) J2 阈值与测量口径裁决；(4) J4 残余宿主侧成本拆分。 |

### 6.2 A-PERF 规格本体不改写（D-168 第 3 条既定治理）

D-168 第 3 条已确立：A-PERF approved 规格的**唯一一条**非目标（「不改分页」）由其规格取代，且 **A-PERF 规格本体不改写**，只在其**下次实质修改时**同步该非目标的解除记录。本设计门**沿此治理**：本文不触碰 A-PERF 规格的 `:66`/`:84`，也不重新登记该取代（该取代已由 D-168 完成）。

### 6.3 既有 OOM 规格的 ≤3 s 部分被承接，其余裁决不变

- **被承接**：§3.1 的 `≤3 s` 可操作性阈值在 61k 规模的**达成判定**，以及 `P704OOM-D-PERF-01` 在 61k 的**状态**（现为 NOT MET）。
- **不变**：OOM 修复机制（候选边界 keyset 分批读 + 批内折叠 + 单事务）、`≤160 MB` 与零 ANR-OOM 阈值、§3 各冻结项、§4 测试计划、§5 设备计划、§6 风险与回退、§7 其余验收项 ID。
- **J3 为开放项**：本设计门对既有 approved 规格的**承接边界**（即「承接多少、是否需显式取代某条」）归**主会话裁决**（§7）。

## 7. 开放项（J1–J9 已由 D-230 裁决；逐条标注归属与结论）

1. **J1 机制族选择**——§3 的 (a) 流式/有界窗口物化、(b) 压缩瞬时共存结构、(c) 渲染模型侧记忆化与分组增量化、(d) UI 分页、(e) 批大小调参五族的取舍。**归属：主会话裁决**。若选 (a)/(d)，因改变「完整列表」语义，**另需范围裁决**。**【D-230 裁决：本设计门不选机制；(b)/(e) 为首选方向，(c) 可作顺带优化，不纳入 (a)/(d)（`ACC-COUNT-01`/`ACC-SEL-01` 与既有 OOM 规格 §3 第 3 项张力）；最终取舍归实施批，且须先取得 J5 重测读数。见 §10。】**
2. **J2 阈值与测量口径**——冻结 B2 首帧口径（20k 对照库测得 0.035/0.035/0.041 s）与 61k 截图轮询口径（含 ~2.4 s 轮询地板）之间的口径选择与 `ACC-D-PERF-01` 在 61k 的最终判定口径。**在设备复验前 `ACC-D-PERF-01` 维持 NOT MET，不得改口径、不得并入 PASS**。**归属：主会话裁决**。**【D-230 裁决：设备复验一律采用冻结 B2 首帧口径；61k 现有截图轮询读数只作量级记录、不作达标依据；重测须先在 61k 上取合格 B2 首帧读数。不改阈值、不改口径、不并入 PASS。】**
3. **J3 承接边界**——§6.1 五要素表中「本设计门承接既有 approved 规格的哪些部分、是否需显式取代某条」的确认。**归属：主会话裁决**。**【D-230 裁决：只承接 OOM 修复规格 §3.1 的 ≤3 s 可操作性部分；不取代、不改写 A-PERF 规格任何条款，亦不取代 OOM 规格其余裁决；A-PERF 规格本体按 D-168 第 3 条下次实质修改时再同步。】**
4. **J4 残余宿主侧成本拆分**——D-171 第 3 条登记的「残余 ≈8.6 s 既不能由分页读解释，也不能由急切渲染模型解释」，与 D-175 的 off-thread GC 压力之间的**拆分与归属**（D-175 第 5 条已登记该比例未定）。**归属：实施批**（需设备侧仪器化）。
5. **J5 D-175 设备侧分解待重测**——§1.4 已证 D-175 取得候选 `5ea7f67` 早于宿主读链的租约面改造（62 次 `app-ui/src/commonMain` 提交）；D-175 的 ~13.2 s / 堆 10→96 MB 分解**须在当前基线上重测**方可作为修复依据。**归属：实施批或设备证据批**。**【D-230：列为实施批第一前置——未取得重测读数前不得据此选用机制。】**
6. **J6 验收项前缀确认**——§5 建议前缀 `P704OP`，**须经主会话确认后方为冻结**。**归属：主会话裁决**。**【D-230 裁决：确认 `P704OP` 为冻结前缀（基点全树 0 命中，无碰撞）。】**
7. **J7 连接持有窗口未仪器化**——D-169 第 6 条与 D-170 第 2 条均登记：`ACC-D-CONN-01` 的持有窗口本身**未被仪器化**，仅有「未观察到 ANR」的单次观察。任何机制族若改变持有窗口（(a)/(d)/(e) 均可能），须先补仪器化读数。**归属：实施批**。
8. **J8 批大小调参效果未测**——OOM 规格 §6 第 1 项授权为实施内调整，但其对 61k 可操作耗时的实际效果**未测**；且 D-171 side A ≈2.4 s 表明读侧非主因。**归属：实施批**。

## 7.1 主代理裁决回填（D-230，2026-10-18）

上列各项的归属与结论已由 **D-230 §3** 裁决并就地回填（标【D-230 裁决】者）。**未标回填的 J4/J5/J7/J8 保持开放、归实施批**，本设计门不作结论。裁决要点汇总见 §10。

## 8. 本批不做

- **零产品代码**：不改 `ledger-data`/`ledger-application`/`app-ui`/`android-app`/`desktop-app` 任何源文件。
- **零测试**：不新增、不修改任何测试文件。
- **零 schema / 零迁移**：schema 维持 **v35、34 个 `.sqm`**（本次只读复核：`find . -name '*.sqm' -not -path './.git/*' | wc -l` = 34）；**不分配 schema 版本号**（沿 D-187/D-225/D-228 设计门先例）。
- **零依赖**：不改任何依赖声明或锁文件。
- **不改 D-169/D-171/D-175 既有登记文本**：D-175 第 6 条已明写其为新增更正条目、不改写旧条目；本设计门沿此纪律。
- **不改 A-PERF approved 规格本体**（D-168 第 3 条既定治理，见 §6.2）。
- **不声称任何验收向量 PASS**：`ACC-D-PERF-01` 维持 NOT MET；`P704OP-*` 全部为设计冻结/治理项，非已达成结果。

## 9. 交付物与范围外

**交付物**：本规格（tracked，`docs/specs/`）；`docs/DECISIONS.md` 的 D-230 设计门候选登记；既有 OOM 修复规格的**最小指针修订**（§6.3 承接关系的指针，不改其任何既有条目）。

**范围外（如实登记，不静默扩大）**：
- 机制实现（属 J1 裁决后的实施批）。
- 阈值与口径的最终裁决（J2）。
- 设备侧重测（J5）与仪器化（J7）。
- 其他列表读路径（月度查询、写事务主线程现状）与 `loadImportReviewRows` 之外的内存面。
- A-PERF 规格中「不改分页」之外的任何非目标（投影裁剪、截断、隐藏、组范围、D-147 会话批量读语义等，逐条保留）。
- 正式状态文档同步（`docs/CURRENT_STATE.md`/`README.md`/`docs/ROADMAP.md`/`docs/PROJECT_MAP.md`）属后续独立同步批，不在本批。

## 10. 主代理裁决（D-230 §3，2026-10-18 设计门闭环）

本节汇总 J1–J9 的裁决结论，与 §7 各条的就地回填一致。

| 项 | 裁决 | 理由要点 |
| --- | --- | --- |
| **J1** 机制族 | **本设计门不选机制**；方向优先 **(b) 压缩瞬时共存结构** 与 **(e) 批大小调参**，**(c)** 可作顺带优化；**不纳入 (a) 流式/有界窗口** 与 **(d) UI 分页** | (a)/(d) 改变「完整列表」语义，直接冲击 `ACC-COUNT-01`/`ACC-SEL-01`，且与既有 OOM 规格 §3 第 3 项「UI 层无『页』概念」冻结陈述张力；(c) 的 side B 仅 10–50 ms，收益上限已证极低；(b)/(e) 端口与 UI 零改动、回退小、与 D-168 既有路线同向。**最终取舍归实施批**，且须先取得 J5 重测读数 |
| **J2** 阈值与口径 | 设备复验**一律采用冻结 B2 首帧口径**；61k 现有截图轮询读数只作**量级记录**、不作达标依据；重测须先在 61k 取得合格 B2 首帧读数。**不改阈值、不改口径、不并入 PASS** | 计划 `:42`/`:44` 明写不得事后改口径；61k 侧仅留存 `b2-61k-bracket-raw.txt`（无 framestats 行），故无合格 61k B2 首帧读数 |
| **J3** 承接边界 | **只承接** OOM 修复规格 §3.1 的 ≤3 s 可操作性部分；**不取代、不改写** A-PERF 规格任何条款，亦不取代 OOM 规格其余裁决；A-PERF 规格本体按 D-168 第 3 条在其下次实质修改时再同步 | 沿 D-168 第 3 条既定治理；范围变更五要素已在 §6.1 逐条登记 |
| **J4** 残余拆分 | **保持开放，归实施批**（需设备侧仪器化） | D-175 第 5 条已登记「读/映射 CPU 与 GC 检查点 CPU 比例未定」 |
| **J5** D-175 重测 | **列为实施批第一前置**：未取得当前基线的重测读数前，不得据 D-175 分解选用机制 | D-175 取得于 `5ea7f67`，早于宿主读链租约面改造（62 次 `app-ui/src/commonMain` 提交） |
| **J6** 前缀 | **确认 `P704OP` 为冻结前缀** | 基点上全树 0 命中，与既有 `P704-`/`P704C-`/`P704D-`/`P704OOM-`/`P704SPEC-` 无碰撞 |
| **J7** 连接持有 | **保持开放，归实施批** | `ACC-D-CONN-01` 持有窗口未被仪器化，仅「未观察到 ANR」单次观察 |
| **J8** 批大小调参 | **保持开放，归实施批** | 调参效果未测，且 side A ≈2.4 s 表明读侧非主因 |
| **J9** 门形/证据纪律 | **冻结为本项目后续批次的通用纪律**：累计性检查一律**限定到本批新增行**并显式列出新增命中（不要求「命中集恒等」）；ignored 路径（`local/**`、`docs/*.local.md`）在隔离 worktree 内不存在，引用时以**主 checkout 根**为基准；tracked 内容禁写该工具链目录名（任意大小写变体）与盘符绝对路径（CI 追踪扫描大小写不敏感） | 本轮两跑的实际教训：恒等形门对「须新增内容」的改动结构性不可满足；worktree 不物化 ignored 路径；本机大小写敏感预检会假绿 |

**边界（本设计门）：** 本条只批准**设计规格**；**零产品代码 / 零测试 / 零 schema / 零迁移 / 零依赖**（不分配 schema 版本号，schema 维持 v35、34 个 `.sqm`）；不授权实现、迁移、技术选型或发布；**不声称任何验收向量 PASS**（`ACC-D-PERF-01` 在设备复验前**维持 NOT MET**）；不改 D-169/D-171/D-175 既有登记文本；不改 A-PERF 规格本体。
