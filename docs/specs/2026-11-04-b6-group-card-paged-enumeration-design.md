# B6 组卡分页枚举设计规格（20k 组开卡 ≤3 s 的修法，承接 D-278/D-279/D-280）

状态：approved（2026-11-04 设计门：依据 = 会话 goal 的常设授权「自主推进、决定一律批准」（D-275 第 4 条同款先例、D-278/D-279 状态节同款引用）；证据链 = D-278 未达标读数 → D-279 归因（读侧排除、分段）→ 本地判别与复杂度定性（`local/artifacts/p7-closeout/b6-cpu/CPU-VERDICT.md`、`assembly-complexity.md`、`fix-rethink-C.md`，gitignored 主检出自更）。本文只冻结**设计级**语义与五项分页治理门落点，不构成实现合入或发布授权；实施批另走评审拓扑与 CI 门。零 schema/零迁移/零依赖。）

**Revision:** v1.0（2026-11-04 首轮起草并批准）。规格类别标记（`docs/CONTRIBUTING.md` 文档规则）：**approved**。本文承接 `docs/DECISIONS.md` **D-280**（判别与修法裁决条目，随本批同一 PR 提交登记）。

## 0. 范围与边界

- 本条只修 **B6 一个向量**：重复组置首屏 20k 夹具上的「组开卡 ≤3 s」（D-278 冻结门槛）。
- 修法 = **整组确认页（组卡）的呈现分页**：首窗枚举落盘即开卡渲染，后台按页续枚举，确认恒授权全组。
- **相邻面不改**：61k 候选列表读路径（`ACC-D-PERF-01`，D-230 J1 已裁决不纳入分页，其裁决面是候选列表，不是组卡——两组语义约束不同，见 §2.5）；B5/B2 口径；PR #205 代表行口径（关闭未合并，不影响本设计——本设计在现状「每候选折叠行」口径上工作）。
- **不做**：D-280 曾评估的「渲染模型瘦身 A + 装配并轮 B」不实施（预期 ~5.5–6 s 仍不达标，见 §1.3）；纯函数 `enumerateImportDuplicateGroupItems` 签名**逐字不动**（P704C-SPEC-05 的 JVM pin 保持）。

## 1. 证据链（本设计的依据，如实摘述）

### 1.1 判别读数（2026-11-04，本地工件，gitignored）

逐线程 CPU 探针（`local/artifacts/p7-closeout/b6-cpu/`）在 `main` @ `af391c9d`、本会话自建 `ul_p7_d01`（API 36，隔离 adb 5038，`emu avd name` 核实）上取得：

- 点击窗口（D278STAGE 合计 7,236 ms 的那轮）CPU/墙钟 **≈1.04**；同轮点击前 11 s 空闲平台 **0.00x** → **租约等待出局**（D-279 候选③关闭）。
- 窗口内线程拆分：`DefaultDispatcher` worker ×2 = **63.6%**（约 5.0 s，串行长段）、main = **26.9%**（约 2.1 s，全程均摊 ~200–290 ms/s，与 worker 并行，非尾部渲染峰）、`HeapTaskDaemon` 5.0%（GC，2 万级对象逐轮分配的旁证）。
- 相位拆分（`analyze4-phases.py`）：worker 从窗口起点烧到 rel≈7.5 s 突停；main 全窗均摊。**worker 的 ~5 s 是串行物化+装配长段，主线程侧修法（渲染瘦身）对它无效。**

### 1.2 复杂度定性（本地工件）

装配链逐级 file:line 复杂度（`assembly-complexity.md`）：全链**无超线性**——20,001 行经历「SQL 生成行 → session review 行 → disposition 项 → item state（+render list 第五轮）」四~五轮完整重建；本夹具 `view.rows` 仅 ~3 行，行过滤/分类皆小常数；成本集中在**同一批 20k 行的逐轮全量物化**。61k 家族读数（D-175/D-231/D-234：`Dispatchers.Default` 24–26 s CPU 主导、main 1.5–1.7 s、GC 2.1–2.5 s）与本判别同形。

### 1.3 修法对比与裁决（D-280）

| 修法 | 可砍的 CPU | 预期窗口 | 结论 |
| --- | --- | --- | --- |
| A 渲染瘦身 | main 的 diff/重建份额 ≤1 s | ~6.2 s | 不达标 |
| B 装配并轮 | 去一层轻包装 ~0.3–0.5 s；游标物化（worker 大头）动不了 | ~5.5–6 s | 不达标 |
| **C 分页枚举（本设计）** | worker 物化降到页大小、main 组合降到页大小 | **首轮开卡 ~1.5–2.5 s（证据原值，`fix-rethink-C.md:14`）** | **达标路径唯一** |

A+B 买不来 ≤3 s 却要付 P704C-SPEC-05 与 A-VIS 渲染模型两个 pin 的迁移成本 → **否**；C 的纯函数签名零改动、schema 零改动。

## 2. 必须守住的不变量与分页治理门

### 2.1 产品义务（PRODUCT_REQUIREMENTS.md:79）

「对同一次导入中判定为疑似重复且尚未处置的条目，系统提供整组标记为重复的便捷操作，并在**执行前逐条列出各条目的比较信息**供核对；该操作**逐条独立生效**，某一条失败不影响其余各条。」分页只改变**呈现节奏**（逐条列出 = 随页落盘即可核对），不改变授权对象与逐条生效语义。

### 2.2 组枚举完整性（ACC-GROUP-01，继承 D-230 §4 第 5 项）

全组条目最终必须全部落盘，**无任何一条因分页缺失**：确认动作的生效集 = 全组（§3.4）；落盘完成后 `items` 与现状全量枚举**逐字节同序同内容**（keyset 页序 == 现状 `ORDER BY duplicate.candidate_id`，JVM 等价测试钉住）。

### 2.3 类型化失败（R-Q06-4 族）

任何一页读失败 → 整体失败路径与现状同型（typed `ReadFailed` → 列表失败横幅），**不得把失败页静默降级为「少几条」**；已落盘页在失败时由生成门裁决去留（§3.5）。

### 2.4 语义树与 A-VIS 契约

- 卡片头部披露「共 N 条」的 N = **全组总数**（count 查询），不是已落盘数——B6 探针逐字读数 `itemCount=20001`、`itemCountText=…共 20001 条。` 在分页下**保持逐字不变**。
- 已落盘条目的比较信息默认全展开（D-235 R1）、`contentDescription` 契约（D-235/D-274）不变；`stableKey` 与 `contentType` 逐条不变、条目顺序不变（D-230 §3.3 的列表身份纪律，复核点 = `P503ImportReview.kt` 的 `items(key=…, contentType=…)` 调用）。
- 批量折叠开关在续页期间作用于已落盘集合（瞬态，登记于 §6 风险 2）。

### 2.5 计划 `:42` 五项分页治理门（逐项落点）

| 项 | 本设计的落点 |
| --- | --- |
| 排序 | 页序 = `duplicate.candidate_id` 升序 keyset（与现状全量 `ORDER BY` 逐字节同序）；页键即显示序，无需重排 |
| 快照一致性 | **一次租约贯穿整轮分页枚举**（与现状全量枚举同窗，ACC-TX-01 持有窗口语义不变）：同轮各页读同一快照，续页期间状态历史不被并发翻转（本应用本地单写者 + 枚举/确认各自单飞）；逐页多租约的「短持有」变体登记为残余（§6 风险 6），不在本批 |
| 跨页勾选 | N/A——组卡无勾选；组内确认授权集恒为全组（§3.4），不存在「只确认本页」语义 |
| 总数 | 新增 `importDuplicateCandidateCountForSession` count 查询（§3.2），头部「共 N 条」与 reducer `totalCount` 使用它；不为凑数把已落盘数当总数 |
| 组枚举完整性 | §2.2：确认前强制补齐（§3.4）；JVM 等价测试「分页累积 == 全量枚举」钉住 |

### 2.6 与 D-230 J1 裁决的关系

D-230 J1「不纳入 (d) UI 分页」裁决的对象是 **61k 候选列表读路径**（`ACC-COUNT-01`/`ACC-SEL-01`：列表读返回完整 `List`、勾选/批量授权读同一完整列表）。组卡是**独立呈现面**：其完整性由 §2.2/§3.4 保障（确认恒全组），无跨页勾选语义，且该面在 20k 规模的 ≤3 s 门槛只有分页可达（§1.3）。本设计沿 **D-268（DEF-B 首页流水分页）的许可路径**：逐面范围裁决 + 五项治理门逐项落点。D-230 J1 的裁决正文不改写。

## 3. 提议契约

### 3.1 页读（ledger-data，`Ledger.sq` EOF 追加命名查询，零 DDL）

```sql
importDuplicateReviewsForSessionPage:
-- importDuplicateReviewsForSession（Ledger.sq:9676）的分页变体：列集、join 形状、
-- 最新 review 语义与 WHERE 谓词逐字相同；仅增加 keyset 定界与 LIMIT：
--   AND duplicate.candidate_id > :after_candidate_id   （首页传空串外的前哨；见下）
-- ORDER BY duplicate.candidate_id（不变）+ LIMIT :page_size
```

- **页界绑定**：`duplicate.candidate_id > :after`（TEXT 主键，BINARY 序）；首页用 `> ''` 起步（candidate_id 非空，空串探针沿 `SqlDelightImportReviewReadAdapter` 既有 fail-loud 先例：空串候选 id 在读取入口即抛错，防 keyset 永久跳过）。
- **计数**：`importDuplicateCandidateCountForSession`：同 WHERE、不 join `review`/`existing` 侧（保留到 `latest_status` 的 join 以维持「组员」口径逐字一致——计数必须与页读的 WHERE 谓词逐字节同形，否则头部 N 与最终 items.size 不符）；覆盖索引（v30 `import_duplicate_candidate_subject_idx`）一次范围扫描。
- 页大小**固定 200**（D-268 Q5 先例）；`page_size` 绑定类型按 D-168 先例为 `Long`。

### 3.2 端口与应用层

- `ImportReviewReadPort` 新增 `loadImportDuplicateReviewsForSessionPage(ledgerId, sessionInputRef, afterCandidateId, pageSize): ImportDuplicateSessionPage?` 与 `countImportDuplicateCandidatesForSession(...)`；`null` = 会话缺席（沿用 null/empty 判别语义与存在性探针先例——探针每会话一次，不是每页）。
- `QueryImportDuplicateReviewsForSession` 的**现有签名与判别逐字不动**（其 JVM 断言与现有调用方保持）；页读是**新增**端口方法 + 新 `Query` 类。
- 宿主编排（`P503App.kt` 的 `startImportDuplicateGroupDisposition`）：`ledger.leased { }` 单租约内先取 count，再循环页读；**每页**结果经 `ImportDuplicateGroupItemsPageLoaded` 事件落盘（reducer append）；页循环在租约内串行，租约语义与现状全量枚举一致（§2.5）。
- **纯函数逐字复用**：每页把该页 session 行经既有 `groupBy` 折叠喂入逐字未改的 `enumerateImportDuplicateGroupItems`（其 `loadReviews` 回调返回该页折叠加空集）——P704C-SPEC-05 的类型化中止语义逐页生效：任一页出现 typed 读失败即整轮 `ReadFailed`（§2.3）。

### 3.3 状态与呈现

- `ImportDuplicateGroupDispositionPage` 增 `totalCount: Int`（count 查询值）与 `items` 语义改为「已落盘前缀」；头部披露 `groupDispositionCardHeaderDisclosure(totalCount)`——呈现纯函数签名不变，传参从 `items.size` 改为 `totalCount`（调用点改动）。
- 首窗事件落盘后立即开卡（卡片 marker = 头部三行 + 批量开关 + 首窗条目）；「正在整理重复组……」进度行在**续页期间**由 `groupEnumerationInProgress` 继续承载（首窗落盘时翻 false 的现状时序改为**全部落盘**时翻 false——该时序变化在 reducer 测试钉住）。
- **ordinal 预计算**（执行 D-235 已登记残余，`docs/DECISIONS.md:4966`「`itemOrdinal` …登记归 A-PERF」）：`ImportDuplicateGroupItemState` 增 `ordinal: Int`（落盘时按全组序赋值，keyset 前缀序保证稳定），`P503ImportReview.kt` 的 `indexOf` 改读该字段——O(visible×n) 归零。

### 3.4 确认授权恒为全组（本设计的核心语义裁定）

`confirmImportDuplicateGroupDisposition`（`P503App.kt:2362`）的确认集由「当前 `page.items`」改为「**全组**」：

- 续页仍在飞时点确认 → 宿主先**在既有单飞纪律内补齐剩余页**（等同把后台枚举跑完；总读工作量与现状全量枚举相同，不劣于现状），再逐条确认全组；UI 上确认按钮的等待由既有 single-flight 槽承载。
- 幂等重跑（`outcome !is Reviewed` 过滤）逐字保留。
- **裁定措辞**：呈现可分页；**授权对象恒为「整组」**——「整组标记为重复」的确认动作永不只作用于已落盘子集。

### 3.5 失败与关闭

- 任一页 typed 失败 → 整轮 `ReadFailed`：已落盘页**丢弃**（现状全量枚举失败时同样零落盘——组卡不开），列表失败横幅（F1/G6）呈现。不引入「部分组卡」新形态。
- 关闭组卡（`CloseImportDuplicateGroupDisposition`）后续页落盘事件被代际/状态门丢弃（既有 stale-landing 纪律）；重开组入口重算新一轮枚举（现状语义）。

## 4. 验收映射

| ID | 内容 | 判据 |
| --- | --- | --- |
| `P704GP-ACC-GROUP-01` | 组枚举完整性 | JVM 等价测试：分页累积 items == 全量枚举 items（逐字段）；确认补齐语义测试 |
| `P704GP-ACC-ORDER-01` | 页序 == 现状全量序 | 同上（逐字节同序断言） |
| `P704GP-ACC-FAIL-01` | 页失败整体失败 | 页注入 `Unavailable` → `ReadFailed`、零落盘 |
| `P704GP-ACC-COUNT-01` | 头部 N = 全组总数 | count 查询 == 最终 items.size；头部文案逐字断言（含 20k 量级 JVM 用例） |
| `P704GP-ACC-CONFIRM-01` | 确认恒全组 | 续页未完时确认 → 补齐后逐条生效；幂等重跑不重复提交 |
| `P704GP-ACC-KEY-01` | 列表身份不变 | `stableKey`/`contentType` 逐条不变；ordinal 与落盘序一致 |
| `P704GP-D-PERF-01` | B6 ≤3 s | 设备三轮**取最大值** ≤3 s（A-PERF approved 规格冻结的「3 次取最大值适用于修复后验收全部场景」纪律，`docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md:104/:116`——不放宽统计口径；`run-cpu-probe-v4.sh` 同法）；窗口 CPU 总量对比基线 7,100 ms——**旁证（D-284 裁决）**：其基线属本机全量装配口径，分页与云端环境改判（D-280/D-281）后无跨环境可比基线，不作为翻 PASS 的必要条件；`itemCount=20001` 与 key texts 逐字不变；`forwardActions=0` 组入口可达不变 |
| `P704GP-D-SCALE-01` | 小组无回归 | 现有小夹具（D-274 的 3 条组）三轮读数不劣化；语义树三态开关不变 |

**口径披露（`P704GP-D-PERF-01`）**：D-278 的「卡片可见」= 全量装配后的卡片 marker；分页下同一 marker（卡片头部 + 首窗条目）出现更早。**该口径变化是本设计的登记行为**：门槛数值（≤3 s）不变，测得的是「用户可见组卡首响应」，与现状「全量装配完成」的旧口径不可直接比较——两者都在证据里并列登记（D-280 与本表），旧口径读数（3,635–7,236 ms）保留为「全量装配完成时间」参照。计划 `:44` 的整链口径纪律（不排除映射/等待时间）逐字适用。

## 5. 实施批次的测试责任（沿计划 §10.5 与 D-268 §7 体例）

- ledger-data：页读 JVM（空会话、恰好整页、尾部不足页、keyset 续页、计数精确、`EXPLAIN QUERY PLAN` 无整组排序）、等价测试（分页累积 == 全量，复用 `ImportScaleTraversalInstrumentedTest` 夹具生成器的 JVM 侧同构数据或既有 B6 夹具库读路径）。
- app-ui：reducer 追加语义（首窗/续页/完成时序/失败丢弃/关闭后代际门）、头部 totalCount 传参、ordinal 预计算、确认补齐宿主编排（coordinator 单飞不变）。
- 设备（实施批合入后）：§4 `P704GP-D-PERF-01`/`P704GP-D-SCALE-01` 两向量，证据落 `local/artifacts/p7-closeout/b6-cpu/`（同目录新报告，不改写判别原件）。

## 6. 风险与缓解

1. **首窗后的续页期间用户操作**：展开/折叠作用于已落盘集合（§2.4 登记瞬态）；确认走补齐（§3.4）；关闭走代际门（§3.5）。
2. **批量开关文案与已落盘数不一致的瞬态**：开关 `contentDescription` 含「共 N 条」（全组数），作用集是已落盘——续页完成后两者自然一致；登记为瞬态，不为它改文案契约。
3. **count 与 items 最终不一致**：只可能由续页期间的状态翻转引起（本应用单写者 + 单飞 + 同租约快照下被 §2.5 排除）；JVM 等价测试钉住「count == 最终 size」。
4. **keyset TEXT 序**：`candidate_id` 为 UUIDv7 文本（BMP 内 ASCII），SQLite BINARY 序 == Kotlin UTF-16 序（D-230 §4 第 2 项同款前提）；空串探针 fail-loud 防跳过。
5. **首窗大小与首响应的权衡**：200 条首窗 ≈ 首响应亚秒级；若设备复测首响应仍慢，调页大小属实施内调整（OOM 修复规格 §6 第 1 项先例，`docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md` §6，经 D-168 批准），不改契约。
6. **逐页多租约的短持有变体**：可缩短 ACC-TX-01 持有窗口，但引入跨页快照一致性问题；登记为残余，不与本批混做。
7. **与 PR #205 的关系**：代表行口径若日后重启并合入，组员集合口径变化会使本设计的等价测试基线随之更新——届时按其自身 Decision 承接，本批不预改。

## 7. 本批（设计门 + D-280 登记）不做

- 零产品代码 / 零测试 / 零 schema / 零迁移 / 零依赖（实施批另立 PR，走 §5 测试责任与评审拓扑）。
- 不改 D-278/D-279 判决正文与读数；不改 D-230 J1 裁决正文；不改 A-VIS/D-235/D-274 契约文本。
- 不改 B5/B2 测量方法；不声称任何验收向量 PASS（§4 全部为设计冻结/实施批判据）。

## 8. 锚点汇总

- 缺陷与证据：`docs/DECISIONS.md` D-278（:6070）/D-279（:6095）/D-280（本批登记）；本地判别原件 `local/artifacts/p7-closeout/b6-cpu/`（CPU-VERDICT / assembly-complexity / fix-rethink-C / analyze4-phases.py）。
- 现状实现面：`Ledger.sq:9676-9726`（session 全量查询）；`SqlDelightImportReviewReadAdapter.kt:217-241,282-311`；`ImportReviewReadPort.kt:153-160,208-310`；`P503LedgerFacade.kt:360-361`；`P503App.kt:2246-2348`（枚举编排）、`:2362-2467`（确认循环）；`P503Reducer.kt:1090-1119`；`P503ImportReview.kt:389-420,534-608`；`P503ImportReviewPresentation.kt:317-392,598,677-699,982-1071`。
- 分页先例：D-268（DEF-B，页键/计数/单飞/失败保留/冻结文档修订的完整体例）；D-230 §3.3（列表身份纪律）、§4（不变量）、§10 J9（累计性检查限定新增行）。
- 治理：计划 `:42`（五项分页门）/`:44`（整链口径）/§10.1（稳定 ID）/§10.4.1（范围变更五要素——见 D-280 第 4 条）；`PRODUCT_REQUIREMENTS.md:79`（逐条列出与整组授权）。
