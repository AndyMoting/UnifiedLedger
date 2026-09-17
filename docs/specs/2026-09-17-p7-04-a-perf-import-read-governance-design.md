# P7-04 收尾批：A-PERF 导入候选全量读治理实施规格（性能批）

状态：approved（规格草案 v2.2 冻结候选 = v2.1 评审 APPROVE + 修复前基线实测 D-D 裁决吸收；本 tracked 版由该冻结候选按本目录既有 design 文档组织风格正式落盘，全部裁决/约束/门槛逐条忠实保留。源码基线 `f13f426`，schema v30）。

**Revision:** v2.2（2026-09-17 冻结候选）。依据：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF、§10.3；D-146/D-147；2026-09-16/17 设备证据（PROGRESS_LOG，主检出本地文件）；修复前基线实测（2026-09-17，本地证据包：ANR 实测 + host 复算 255.98s→0.24s + SQLite 官方文档语义核实）。v2.2 相对 v2.1 的唯一增量 = §0 基线裁决（D-D，替代悬置分支 D-B/D-C 的证据裁决）。本规格冻结三层修复面（层0 统计刷新 / 层1 定向读 / 层2 主线程 catalog 读治理）、测量门槛与显式非目标；实施、Git 写操作与最终验收属本 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为工作基线 `f13f426` 实读行号；`.local.md` 与本地证据包以主 checkout 为准、只读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF（导入候选全量读治理）、§10.3（超门槛项处置流程）。
- **决定（已确认/已批准）**：D-146（P7-04 导入与草稿确认批次裁决族）、D-147（P7-05 枚举性能批：会话级批量读 + v30 覆盖索引 `import_duplicate_candidate_subject_idx`，本批读形态治理的直接前置）。
- **SQLite 官方语义（基线报告 §2 落库的中立契约）**：ANALYZE 统计不随内容自动更新；内容约 10 倍变化触发 `PRAGMA optimize` 重分析；长连接推荐 open 后 `PRAGMA optimize=0x10002` + 周期 `PRAGMA optimize`；`PRAGMA optimize` 通常近 no-op 且很快，并随 schema 变化（CREATE INDEX 后）推荐执行；3.46.0+ 自动带 `analysis_limit`。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井与 golden fixtures/expected 零改动；D-147 会话批量读语义、19 子型渲染模型、披露行先例不动；schema 停留 v30（本批零 DDL）；月度查询/单笔读/写事务的主线程现状不在本批（§5 披露）。

## 0. 基线裁决（D-D，主修复输入）

修复前基线实测（2026-09-17，本地证据包，固定 APK = 基线 `f13f426` 本地重建）：10k 库 B2=5.00s / B3=4.97s / B5=4.92s（均超 §6 门槛）；20k 库 B6 组开卡触发 **app ANR**（主线程等连接 30.015s，logcat SQL 原文 + /data/anr 文件 + 系统弹窗截图三重留证）。

**根因 = 缺索引统计**：设备 20k 库（v30 索引在位）host 复跑列表查询 255.98s——计划器选错 `sqlite_autoindex_import_duplicate_candidate_1`（每候选按 ledger_id 前缀扫 10,010 行 dup 表，O(候选×dup)）；`ANALYZE`/`PRAGMA optimize` 后同库同查询 0.24s/0.23s（1,066×，复用既有 v30 索引两列定位）。SQLite 官方语义核实：统计不随内容自动更新；10 倍行数变化触发 optimize 重分析；v30 迁移 CREATE INDEX 后从未跑过统计——D-147 上线即埋下统计缺口。

**D-D 裁决（已按基线证据裁决，替代 D-B/D-C 悬置分支）**：

- **层0（新增，主修复）**：统计刷新机制——Android 端启动 bootstrap 完成点后台执行 `PRAGMA optimize`（安全网）；接治收尾（intake pipeline 完成事务后）后台执行**显式 `ANALYZE;`（全 schema）**（返工 3，设备证据裁决，见下）。desktop 同链共享。零 DDL、零 schema 变更、零查询重写、零产品语义变更。PRAGMA/ANALYZE 语句经现有驱动执行面（AndroidSqliteDriver / JDBC），不新增依赖。
- **返工 3 根因披露（2026-09-17 设备复测，AVD ul_p7_d01，API 36 系统 SQLite 3.44.3，20k 库）**：`PRAGMA optimize` 的重分析资格依赖「该表曾被 stat1 规划」——import 表首次启动时无统计、无规划历史，二者皆无，官方 10× 规则救不了首次；实测 bootstrap 触发后 `sqlite_stat1` 仅 catalog 表 4 行、import 表零统计，`PRAGMA optimize=0x10002` 重跑同样不分析 import 表（0x10000 全表位属较新版本行为），列表查询仍选错 autoindex 卡 300+s；手动 `ANALYZE` import 三表后列表 2.4s 落地。故接治收尾点改用强保证 `ANALYZE;`，且该 hook 在 `runImportIntakePipeline` 的 Default 线程、列表重读**之前**执行——统计永远先于读落地。**边界披露**：外部注入库（未来 P7-06 恢复路径）的统计新鲜度归该批处理，本批不静默扩大。**执行面披露（返工 3 实测，对返工 3 指示"统一 executeQuery 面"的偏离）**：按语句结果形态各归其消耗面，两个触发点测试分别钉死——`PRAGMA optimize` 有结果列，走 executeQuery 面（Android rawQuery 等价安全面；有结果列语句经 execute/executeForChangedRowCount 会被拒，busy_timeout 教训路径）；`ANALYZE;` 无结果行：JDBC 端实测 sqlite-jdbc executeQuery 拒绝无结果语句（"Query does not return results"，DesktopQueryStatisticsOptimizeTriggerTest 失败钉死）→ desktop 走 `driver.execute()`；Android 端 executeForChangedRowCount 的拒绝仅适用于**返回结果行**的语句，无结果行的 ANALYZE 预期可行（代码级推论，非设备实测）——**设备复测必须实测**：接治收尾后 `sqlite_stat1` 须出现 import 表统计行，否则本返工 FAIL。
- **层0 实施坑与接口约束（反编译 android-driver 2.3.2 证实 + 实测钉死，实施必须遵守）**：
  1. `PRAGMA optimize` **不得进入 Ledger.sq 命名查询**——SQLDelight 将 PRAGMA 归类为 EXECUTE 语句走 `driver.execute()`，Android 链到 androidx `executeUpdateDelete`/`SQLiteSession.executeForChangedRowCount`，对返回结果列的语句抛 "Queries can be performed using SQLiteDatabase query or rawQuery methods only"（busy_timeout 教训同路径，`AndroidLedgerDatabaseHandle.kt:98-100` 注记）。
  2. Android 端执行面 = `driver.executeQuery(null, "PRAGMA optimize", mapper, 0, null)`（rawQuery 等价安全面；先例 = `AndroidLedgerDatabaseHandle.kt:25-32` SELECT 1 探针）。`AndroidSqliteDriver` 的 driver 字段是 handle 的 private 成员——**handle 新开一个受控执行入口**，由组合根在触发点调用。
  3. Desktop 落点 = `buildLedgerGraph` 内 `bootstrapAuthority`（`Main.kt:325` 调用点）之后执行一次（官方 open 时模式；JDBC `driver.execute` 对返回列 PRAGMA 不抛，busy_timeout 先例 `configureSqliteConnection` 即该面；但语义上不塞进 configureSqliteConnection，保持统计维护与连接配置分离）；接治收尾触发与 Android 共享 commonMain `runImportIntakePipeline`，desktop 侧 driver 执行入口同样经受控 handle/graph 方法暴露（`CloseableLedgerGraph` 现不暴露 driver，同接口缺口一并补）。
  4. 触发点最小集推理留痕：整组处置/审核只写 `import_duplicate_status_history` 状态行、不新增 `import_duplicate_candidate` 行——接治收尾跑过一次 ANALYZE 后，dup 表行数不变（官方 10× 规则）不再依赖新分析，处置收尾**不需要第三个触发点**。`import_duplicate_status_history` 在整组处置中可新增 ~10k 行（10× 可达），但其读路径全走 PK `(ledger_id, candidate_id, sequence)` 索引、无统计敏感性，且接治收尾点已在处置前跑过 optimize——不为其加触发点，如实登记此边界。
- D-B 分支（轻量列表投影）**不启用**：基线证明统计修复后列表查询 0.24s（20k 库 host），无需投影裁剪。
- D-C 分支（列表读零改动）确认：列表读本体保留整账本读。
- 30k 铺库不必要：20k 已复现 ANR（同根因更劣规模），修复后复测以 20k 库为准（B6 组开卡、B2/B3 刷新、B5 详情）。

层1（定向读）与层2（主线程 catalog 读治理）保留执行：B5 详情 4.92s 的主成分是同一慢查询的整账本读+过滤，统计修复会自然回落，但定向读消除 O(全库) 读形态仍是正确性/规模收益；B6 ANR 的主线程等待面（catalog loadAuthority flags 0x5）由层2 治理。

## 1. 问题与根因（代码证实）

四路径整账本读 `importReviewRowsForLedger`（`Ledger.sq:8853`，无 LIMIT，4×JOIN + 相关子查询）：

| 路径 | 位置 | 触发点（全部） |
| --- | --- | --- |
| 候选列表 | `SqlDelightImportReviewReadAdapter.kt:34-39` | P503App.kt:795 接治收尾、:842 刷新/进 Tab、:904-906 审核提交后、:1109 整组处置收尾 finally、:1124-1126 requestImportReviewRowsRead（:1209/:1252/:1265/:1324 上游）、:1167/:1302 Unknown 核对 |
| 单候选详情 | 同 :57-61 | P503App.kt:848-856 |
| 单候选重复探针 | 同 :83-87 | loadImportDuplicateReviews 存在性探针 |
| 会话批量空探针 | 同 :113-123 | loadImportDuplicateReviewsForSession 空结果探针 |

主线程 catalog 读（ANR 链，ANR trace + 代码佐证）：Android 单连接（`AndroidLedgerDatabaseHandle.kt:13-19`，无 busy_timeout）→ 后台 20k 行全量读占连接 → 主线程 `catalogSnapshot()` 读（组合期 `P503App.kt:145` 与 :1613 详情屏、刷新链 :264-277 refresh 与 :689-698 初始加载、事件路径 :1344 pinnedCatalogSnapshot（:1365/:1437 调用）→ App.kt 组合根 `catalogSnapshot = { snapshotQuery.query(ledgerId) }` → `CatalogProjection.kt:75-78` → `SqlDelightCatalogStore.kt:424` loadAuthority）等连接 → Input dispatch 超时。

既有索引现实：duplicate LEFT JOIN 走 v30 `import_duplicate_candidate_subject_idx (ledger_id, subject_source_id)`（D-147 后在位，`Ledger.sq:7885`）；`import_source_record` 有 `UNIQUE (ledger_id, input_ref, record_ordinal)`（`Ledger.sq:7661`）、`import_candidate` 有 `UNIQUE (ledger_id, source_id)`（`Ledger.sq:7686`）可用于定向探针。列表读在 v30 后的真实耗时**已测**（基线报告：20k 库慢读根因为缺统计，非缺索引）。

## 2. 修复范围（三层）

### 2.1 层0：统计刷新机制（主修复，零 DDL）

- Android 端启动 bootstrap 完成点后台执行 `PRAGMA optimize`（安全网，对有 stat1 规划历史的表有效）；接治收尾（intake pipeline 完成事务后）后台执行显式 `ANALYZE;`（强保证，§0 返工 3 根因披露）。desktop 同链共享（§0 实施坑 1-4 逐条适用）。
- 触发点最小集 = 恰好两处（bootstrap 完成点 + 接治收尾点）；不加第三个触发点的推理边界见 §0 第 4 条。

### 2.2 层1：定向读（纯查询形态替换，零 DDL）

1. 新增 Ledger.sq 只读命名查询 `importReviewRowForCandidate`：与 `importReviewRowsForLedger` 同 JOIN 形态、保留 `ORDER BY candidate.candidate_id, duplicate.candidate_id`，`WHERE candidate.ledger_id = ? AND candidate.candidate_id = ?`（主键定向）。
   - `loadImportCandidateDetail` 改用它（替换整账本读+filter）；absent（0 行→null）与折叠输入等价（评审核实）。
   - `loadImportDuplicateReviews` 存在性探针改用轻量定向查询（`SELECT 1 FROM import_candidate WHERE ledger_id=? AND candidate_id=?` 一类主键探针），不再为存在性执行 4-JOIN。
2. 新增轻量定向存在性查询（`input_ref` 定向）：candidate JOIN source 形态、走 :7661/:7686 索引，替换 `loadImportDuplicateReviewsForSession` 的整账本空探针（:113-123）；空探针语义（session 有候选→emptyList，无→null）不变。
3. 列表读本体 `loadImportReviewRows` 保留整账本读（一次查询、客户端折叠、19 子型渲染模型与披露行先例零改动），**本批不改分页、不做投影裁剪**（§0 D-D：统计修复后 20k 库列表查询 0.24s，无需进一步治理）。

### 2.3 层2：主线程 catalog 读治理（直击 ANR 根因）

**范围（冻结）**：catalog **读**路径的主线程化——组合期（P503App.kt:145 catalogVersion、:1613 详情屏 catalogAccounts）、状态刷新链（:264-277 refresh()、:689-698 初始加载，LaunchedEffect(state) :745-748 触发）、事件路径读（:1344 pinnedCatalogSnapshot 及其 :1365/:1437 调用）。

**明确不在本批（另立披露，不静默扩大）**：月度查询 requestMonthlyPayload（:290-316）、单笔交易读 selectTransaction（:319-322）、主线程写事务（runPinToggle :1394-1405、runCatalogForm/runCatalogToggle :1373-1429 的 commitOnce 写链）。这些短读写的连接占用风险由层1 长读消除间接缓解；如后续仍观察到卡顿，另立批次。

**实现要求**：

- catalog 快照缓存化/后台加载：主线程组合期读缓存 State；数据未就绪（null）时**不得把空集呈现为权威目录**（S2-2）：options/catalogAccounts 未就绪呈现明确的载入中占位或禁用提交入口（先例：P503App 导入进度行/`P503ImportReviewPresentation` GroupEnumerationInProgress 进度行先例），就绪后重组刷新。账务安全不变（必填 null 不提交）。
- **状态刷新链（refresh()/初始加载）的 queryCurrentState 读同样移出主线程**（实施批返工补正，路径 1a）：读在 `Dispatchers.Default` 执行、结果经主调度器 hop 后串行 dispatch；refresh() 与初始加载共享 single-flight 合并准入（一次在飞的读 + 合并请求的恰一次补读，晚到结果不覆盖新状态，S2-3）。reducer 的 Created/NoChange/Recovered 自动刷新链会密集触发 refresh，合并语义防重复读也防"读到提交前数据后留在屏上"。
- **异步完成事件的 reducer 吸收适配**（实施批返工 2，评审 APQUAL-01）：refresh()/初始加载后台化后，`RefreshResult`/`RefreshFailed`/`InitialLoadResult`/`InitialLoadFailed` 可能落进编辑/确认/提交/冲突/拒绝/交易详情/SUBMISSION 失败等瞬态——这些状态的吸收表按 reduceImportCandidateDetail 既有先例吸收上述四事件（状态原样保留，已完成的刷新在流程回到 OverviewEmpty 时经下一次刷新落地），防止 `unhandled` 尾 ISE 崩溃。配套：retainedIntent 消费点以身份门控（只消费本读准入点捕获的实例，窗口内新提交的 intent 不被销毁，APQUAL-02）；读体 runCatching 守卫 + 槽位在主线程 hop 内必释（APQUAL-05）。
- 后台加载沿用 single-flight + 主调度器 hop 先例（P704C-SPEC-01/QUAL-02，P503App.kt:798-810）：并发请求合并，结果回主线程串行 dispatch，晚到旧快照不得覆盖新状态（S2-3）。
- desktop（Main.kt 组合根同链）由共享修复天然覆盖；desktop JDBC 有 busy_timeout 排队语义、无 ANR 证据，本批门槛验收绑定 AVD（Android）；desktop 专项验证不在本批（S3-2）。

## 3. 明确不做

- 不做分页/截断/隐藏待确认项/更改组审核范围（阶段计划 §2 A-PERF 红线）。
- 不动 D-147 会话批量读语义、19 子型渲染模型、披露行先例。
- 不引入新生产依赖；零 DDL（层1 全部只读命名查询；若基线证明需新索引，另行裁决——基线已证明无需）。
- 不改月度查询/写事务的主线程现状（§2.3 范围外披露）。
- 不做第三个 optimize 触发点（§0 推理边界如实登记）。

## 4. 决策点（全部已裁决）

- D-A：层2 缓存形态（推荐并采纳：nullable State + single-flight 后台加载 + 主线程旧值保留直至新值就绪；null 空窗占位）。
- D-D（主修复，已裁决；返工 3 细化）：统计刷新机制——bootstrap 完成点 `PRAGMA optimize`（安全网）+ 接治收尾点显式 `ANALYZE;`（强保证，§0 返工 3 根因披露）（基线证据 255.98s→0.23s；官方语义 10× 行变化触发；零 DDL）。D-B 轻量投影不启用；D-C 列表读零改动确认（§0）。

## 5. 测试与验收

- **JVM**：adapter 层定向读等价测试（详情/探针与整账本读+过滤逐行等价，含重复折叠 / absent-vs-empty / G6 Unavailable 语义）；层0 触发点结构断言（bootstrap 后/接治收尾后各一次 optimize 的可观察性——以最小注入面实现，不引 mock 库，按仓内既有测试风格）；层2 空窗占位与 single-flight 断言。既有 536/521/343/48 套件零回归。
- **设备**：§6 门槛全程计时 + 修复后复测；ANR/OOM 零容忍（合法规模下）。设备验收绑定 AVD（Android）。

## 6. 测量门槛与验收（冻结）

**修复前基线（已完成，本地证据包）**：AVD ul_p7_d01、固定 APK（基线 `f13f426` 本地重建）+ 注入 10k 库（10,039 候选 + 10 dup）→ 重导 10k 样本至 20,039 候选 + 10,010 dup。读数与根因分析见基线报告；30k 铺库不必要（20k 已复现 ANR，同根因）。

**重复次数（测量经济性）**：3 次取最大值规则适用于**修复后验收**的全部场景（PASS 不得是偶然）。基线不是验收主张而是量级记录与 §0 D-D 裁决输入；修复后复测每场景 ≥3 次取最大值；修复后计时另用 logcat 时间戳交叉验证（tap→首帧事件），修正 uiautomator 轮询粒度（基线方法校准披露的 ~2.5s/dump 成分）。

**计时起止事件操作化定义（S4）**：

- B1 冷启动→首页可操作：`am start` 发出 → uiautomator idle 且底部 Tab 容器可查询。
- B2 进导入 Tab→列表可操作：点击 Tab → 组头计数可见且首组候选行可见。
- B3 热刷新→列表可操作：点击「刷新清单」→ 同 B2 终点；触发变体含审核提交后刷新（:904-906）、整组处置收尾（:1109）。
- B4 接治收尾→列表可操作：SAF 点选完成 → 同 B2 终点（含 10k 接治本体 ~3.2 分钟，仅计时收尾段：接治完成结果行可见 → 列表可操作）。
- B5 单候选详情：点击首行候选 → 详情屏关键内容（金额/状态行）可见。
- B6 组开卡：点击「整组标记为重复」→ 进度行消失 + 卡头可见（D-147 先例定义）。
- 全程记录：logcat ANR/FATAL/OOM、`dumpsys meminfo` 峰值、每场结果。

**门槛（阶段计划 §A-PERF 待批准目标，本批采纳）**：B2/B3/B4 列表可操作 ≤3s（首屏/刷新）；B5 定向详情 ≤1s；B6 20k 组开卡 ≤3s（D-147 已达标，不得退化）。整份 10k 解析/接治不压 3s 口径（B4 只计收尾段）。每场 3 次最大值达标才算 PASS；超门槛项按阶段计划 §10.3 优化或明确裁决，不事后改口径。

**修复后复测**：同场景同门槛，另加杀进程重开、20k 滚动零 OOM 复证。

## 7. 交付物

- 本规格（tracked，`docs/specs/`）。
- 层0/层1/层2 实现与 JVM 测试（同批提交）。
- 设备复测报告（主代理设备证据批，不在本 worktree）。
- CURRENT_STATE/ARCHITECTURE 滞后项按 A-DOC 批处理，不混入本批。
