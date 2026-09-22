# P7-04 Import Review List OOM Fix Design — Keyset Paged Read（导入审核列表 61k 规模 OOM 修复设计规格）

状态：approved（修订版 v0.2。修复机制经独立规格评审确认**sound**（首轮评审结论 REVISE，机制不需重新设计）；v0.2 按评审缺陷清单逐条修订 P1-1..P1-7、P2-1..P2-6 与 P3 项后重新提交评审，独立闭包复评对本修订（`9c43787`）给出结论 **APPROVE**——既有阻断条件 C1–C4 全部闭合、未引入新 P1/P2，且独立复核了 `runtime-jvm-2.3.2.jar` 中 `transactionWithResult` 的参数名为 `noEnclosing`（默认 `false`、嵌套抛 `IllegalStateException("Already in a transaction")`）这一关键依赖事实。CONTRIBUTING.md:162 的允许分类为 `approved`/`proposal`/`superseded`/`historical`（v0.1 的 `draft` 不在允许分类内，该标记已在 v0.2 更正）；**主代理已于本次批准本规格**，批准依据即上述独立规格评审的首轮与闭包复评（闭包复评结论 APPROVE），故本规格按 `approved` 分类。实施、Git 写操作与最终验收属后续实施批）。

**Revision:** v0.2（2026-09-21 修订，回应独立规格评审 REVISE）。v0.1 的机制骨架（候选边界 keyset 分批 + 批内折叠 + 事务包裹）经评审确认成立并逐字保留；本轮修订集中于**可编译性**（P1-1 参数绑定、P1-2 `LIMIT ?` 类型）、**证据更正**（P1-3 行数推演与峰值口径、P1-4 夹具归属）、**治理补齐**（P1-5 非目标冻结的显式取代、P1-7 稳定验收项 ID）、**平台事实更正**（P1-6 Android BEGIN 模式与连接持有风险）、以及 P2/P3 的边界与阈值补齐。依据：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF 与 §10.3/§10.4；`docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（approved，本规格显式取代其一条非目标）；D-147/D-148（读治理与 A-PERF 证据纪律）；D-158 第 5 条与 D-159（热读增本的实测纪律）；D-166（DECISIONS.md:3186，第 3 条 OOM 缺陷登记、第 5 条承接）；本 worktree 基线 `83f92bf` 上的只读复核（行数/查询计划/分批等价性，见 §2.4 与 §8）。本规格不改动任何既有裁决，只对 D-166 登记的缺陷给出修复设计。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为当前 worktree 基线 `83f92bf` 实读行号；`.local.md` 与本地证据包以主 checkout 为准、只读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF（红线：第 36 行「禁止靠截断、隐藏待确认项或更改组审核范围提速」；第 42 行「分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性」）、§10.3（规模项处置：61k 规模「完整计数」受 OOM 阻断，属 D-166 第 5 条剩余缺口，须修复或规模口径裁决——本设计走修复）、§10.4.1（范围变更记录原文/变更后内容/授权/影响/承接项）、§10.4.5（未闭合项不得并入 PASS）。
- **决定（已确认）**：D-148（导入候选读治理批：层0 统计刷新 / 层1 定向读 / 层2 主线程 catalog 读治理；「列表读本体保留整账本读」裁决基于 20k 库 0.24s 测量，未覆盖列表整表物化的内存面）；D-147（会话级批量读与 v30 覆盖索引，A-PERF 读形态治理的直接前置）；D-158 第 5 条（热读增本须先取实测读数，**不得静默通过**）；D-159（规模遍历证据与差异说明纪律）；D-166（61k 规模 OOM 缺陷登记：`SqlDelightImportReviewReadAdapter.kt:43-49` 整表物化点，栈 `SqlDelightImportReviewReadAdapter.kt:46` → `LedgerQueries.kt:6604`（CursorWindow.getString），堆 192 MB，进程死亡两次；承接 = 缺陷修复（列表读分页/投影裁剪/流式物化等）或规模口径裁决）。
- **被取代的已批准非目标（P1-5，显式登记）**：`docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（状态 `approved`）§2.2 第 3 点原文——「列表读本体 `loadImportReviewRows` 保留整账本读（一次查询、客户端折叠、19 子型渲染模型与披露行先例零改动），**本批不改分页、不做投影裁剪**」（该文件 :66）；同文件 §3「明确不做」原文——「不做分页/截断/隐藏待确认项/更改组审核范围（阶段计划 §2 A-PERF 红线）」（该文件 :84）。本规格**取代**这两句中的「不改分页」约束，取代的授权与边界见 §1.1；其余部分（不做截断、不做隐藏、不改组审核范围、不做投影裁剪、不动 D-147 会话批量读语义/19 子型渲染模型/披露行先例）逐条保留、不被取代。
- **源码现实**：`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportReviewReadAdapter.kt`（`loadImportReviewRows` 43–49 行；`toColumns` 重载 252–274 行与 276–298 行——**每个生成行类型一个**；`toImportReviewRow` 300–336 行；`foldDuplicateStatus` 338–350 行）；`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq`（`importReviewRowsForLedger` **8869–8918** 行，20 列、**5 个 JOIN 族成员（1×JOIN + 4×LEFT JOIN）**、`ORDER BY candidate.candidate_id, duplicate.candidate_id`；`import_candidate` 定义 **7677–7688** 行，`PRIMARY KEY (ledger_id, candidate_id)`）；等价性测试 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/ImportReviewTargetedReadEquivalenceTest.kt`（`wholeLedgerDetailBaseline` 104–107 行以 `loadImportReviewRows` 为基线）。
- **设备证据（只读，主 checkout）**：D-166 第 3 条登记与 `docs/PROJECT_STATE.local.md` 检查点。**两个不同的事实源必须分开引用（P1-4 更正）**：(a) 备份夹具 `local/artifacts/p7-05-scale/ledger-61000-v31-backup.db` —— schema v31，**61,000 候选 / 158,000 重复关系 / 2 交易**（本 worktree 只读复核实测）；(b) **设备当前库**—— **61,220 候选 / 160,200 重复关系 / 2 交易**（计数以 `docs/PROJECT_STATE.local.md` 检查点为准），是 D-166 第 3 条 OOM 登记与 §5 设备计划的真实对象；其规模由 D-166 第 1/2 条登记的产品 SAF 路径导入（+200 与 +20）在同一 61,000 夹具上累积而成。v0.1 把 (b) 的计数错误归属到 (a) 的文件路径，本轮更正；凡引用计数处均标注来源。
- **不可触碰面**：`.external/` 只读；零 DDL、零 schema 变更（schema 停留 v31）、零迁移；零 UI/端口/用例层改动（`ImportReviewReadPort` 签名不变）；零依赖变更；`importReviewRowForCandidate`（单候选详情定向读）与两个存在性探针、会话批量读不动；golden fixtures/expected 零改动；`rgXX_` 竖井不动。

## 1. 问题与根因（证据与推演）

**缺陷事实（D-166 第 3 条登记）**：冷启动/刷新触发 `loadImportReviewRows` 在 61k+ 候选库上 **OutOfMemoryError 两次**（设备时 10:30:27、10:57:40；堆 192 MB 上限，进程死亡）。**产品影响**：61k 库加载即崩 ⇒ §10.3「完整计数」在 61k 规模当前不可达。D-148 读治理未覆盖列表整表物化的内存面：A-PERF 门槛在 ≤30k 尺度测得，61k 尺度超出其证据面（D-166 第 3 条明示）。

### 1.1 范围变更登记（计划 §10.4.1 五要素，P1-5；治理项 P704OOM-ACC-SUPERSEDE-01）

| 要素 | 内容 |
| --- | --- |
| **原文** | A-PERF approved 规格 :66「列表读本体 `loadImportReviewRows` 保留整账本读…**本批不改分页、不做投影裁剪**」；:84「不做分页/截断/隐藏待确认项/更改组审核范围（阶段计划 §2 A-PERF 红线）」。 |
| **变更后内容** | 列表读本体改为**候选边界 keyset 分批读**（§2）。**仅**解除「不改分页」这一条；不做截断、不做隐藏待确认项、不更改组审核范围、不做投影裁剪、不动 D-147 会话批量读语义/19 子型渲染模型/披露行先例——这些约束**逐条保留**。 |
| **授权** | 阶段计划 :42 原文「**分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性**」——计划本身即预留了分页路径，其许可条件是五项分别冻结（本规格 §3 逐项冻结）。计划 :36 的红线只禁「靠截断、隐藏待确认项或更改组审核范围提速」，**未禁分页**；A-PERF :84 把「分页」与「截断/隐藏/改范围」并列写为「不做」，是当时基于 20k 耗时证据的批内自我约束，不是计划红线。D-166 第 5 条把「列表读分页」列为承接的候选修复路线之一。 |
| **影响** | 列表读从「单语句整表读」变为「单事务内 ≤N 次候选边界批读」。对外语义、返回类型、端口签名、UI 可见结果均不变（§3 逐项冻结）；新增面 = Ledger.sq 一个只读命名查询 + adapter 单函数改造 + 一个生成行类型的 `toColumns` 重载（P2-1）+ 新增 jvmTest。风险面 = 事务/连接持有窗口（§2.5、§6 第 2 项）与批间排序等价的前提（ACC-SORT-01、§6 第 4 项）。 |
| **承接项** | (1) 本规格获批 → 实施批（Ledger.sq + adapter + 测试同批提交）；(2) §5 设备复测报告（主代理设备证据批，含 ACC-THRESH-01 的阈值读数与 §6 第 2 项的连接持有读数）；(3) D-148 approved 规格在**下次实质修改时**按 CONTRIBUTING.md:162 分类并同步该非目标的解除记录（本规格不直接改写那份冻结工件）；(4) §6 第 1 项的批大小调参与第 5 项的回退。 |

**与 approved 规格的关系（一句话）**：本规格是 A-PERF approved 规格 :66/:84 中**唯一一条**非目标（「不改分页」）的显式取代件，取代依据是 D-166 登记的 61k 内存面缺陷；该 approved 规格的其余全部裁决（层0 统计刷新、层1 定向读、层2 主线程治理、零 DDL、20k 门槛、不做投影裁剪）**继续有效且未被触碰**。

### 1.2 行数推演（P1-3 更正）

**`importReviewRowsForLedger` 的输出行数（更正后的正确公式）**：查询每 `(candidate, duplicate candidate)` 对产生一行；`duplicate` 侧是 LEFT JOIN，因此**仅当某候选在 `import_duplicate_candidate` 中零匹配时才产生一行全 null duplicate 行**；**有重复的候选得到的是它的重复行，而不是全 null 行**（v0.1 把它写成「候选数 + 重复关系数」是错的——那等于给每个候选都额外算了一行全 null 行）。正确公式：

```
行数 = Σ_candidates max(1, dup_count(candidate))
```

**夹具实测（本 worktree 只读复核，`ledger-61000-v31-backup.db`）**：61,000 候选 = 51,000 个有 ≥1 重复的候选（合计 158,000 行）+ 10,000 个无重复候选（各 1 行全 null）= **168,000 行 × 20 列**。（v0.1 的 `61,220 + 160,200 = 221,420` 既用错了公式、又把设备库计数挂到了夹具路径上——两处均已更正。）

**逐候选行数分布（夹具实测）**：每候选行数取值为 1..10，其中 20,000 个候选各 1 行、10,000 个各 2 行、10,000 个各 3 行、10,000 个各 4 行、10,000 个各 5 行、200 个各 6 行、200 个各 7 行、200 个各 8 行、200 个各 9 行、200 个各 10 行（合计 61,000 候选 / 168,000 行）。

### 1.3 多份大结构同时存活（OOM 根因）

`loadImportReviewRows` 现有链（`SqlDelightImportReviewReadAdapter.kt:43-49`）：

```
executeAsList()          // ① 物化 16.8 万生成行（CursorWindow.getString 逐列拷贝，栈顶压力点 LedgerQueries.kt:6604）
  .map { it.toColumns() }   // ② 16.8 万 ImportReviewRowColumns 中间列表（与 ① 共存）
  .groupBy { it.candidate_id }  // ③ 61,000 键的 Map<候选, 行列表>（与 ① ② 共存）
  .map { 折叠 }               // ④ 61,000 行 ImportReviewRow 结果列表
```

峰值 = ① ② ③ ④ 四份大结构并列存活：16.8 万行的生成行列表与列投影列表、61,000 键的 groupBy Map（值列表合计仍为 16.8 万元素）、以及最终结果列表。192 MB 堆不足以容纳该峰值组合。**最终结果列表（④，61,000 行 ImportReviewRow）本身是必需的输出**（UI 层 `view.rows` 即此投影，D-166 第 2 条），OOM 的根因是①②③与④同时存活，而非④单独过大——这决定了修复方向：**缩小瞬时共存结构，而不是裁剪输出**。

**与 D-148 裁决的关系（不冲突）**：D-148「列表读本体保留整账本读」的推论「无需分页」由 20k 库 0.24s 测量支撑——那是**耗时口径**（计划器选索引问题经层0 统计修复后解决）；整表物化的**内存口径**在 ≤30k 尺度未构成问题、在 61k 尺度构成 OOM。本设计在 61k 内存面上修订「无需分页」推论，**不推翻 20k 测量**（§6 第 3 项）。

## 2. 修复设计（客户端 keyset 分批读取 + 批内折叠）

### 2.1 总览

`loadImportReviewRows` 的读法改为：**按候选主键 keyset 分批**读取——每批以 `importReviewRowsForLedgerPage` 取「候选 id 大于上一批最大 id、最多 pageSize 个候选」的全部行，批内执行与现状**逐字节相同**的 `map{toColumns()}.groupBy{candidate_id}.map{折叠}`，折叠结果按批序累积为最终列表。**输出语义与现状逐字节等价**（§3 逐项冻结）。

修复方向为何不是其他选项（登记，供评审对照）：

- **不做投影裁剪**：20 列全部进入 `ImportReviewRow`（`ImportReviewRowColumns` 229–250 行为全列投影，`toImportReviewRow` 300–336 行逐字段消费），裁剪会改变读模型与折叠输入；且 A-PERF approved 规格 :66 的「不做投影裁剪」**不在本规格的取代范围内**。
- **不做行级 LIMIT 分页**：无候选边界地截断行会**把同一候选的重复行切到不同批**，直接破坏 `groupBy` 折叠——候选边界限定是正确性的核心装置（§2.2）。
- **不做流式物化**：SQLDelight 生成面返回 List，流式需动驱动/生成层，超出本批范围且不必要——keyset 分批已把**单批**峰值降到有界（注意：单批有界 ≠ 全局峰值有界，见 §2.4）。
- **不改 UI/端口**：`ImportReviewReadPort.loadImportReviewRows` 签名与 `view.rows` 完整结果语义不变。

### 2.2 Ledger.sq：新增命名查询 `importReviewRowsForLedgerPage`（契约项 P704OOM-ACC-SQL-01）

紧邻 `importReviewRowsForLedger`（Ledger.sq:8869）新增只读命名查询：**同一 SELECT 形状**（20 列、JOIN/LEFT JOIN 不变、`ORDER BY candidate.candidate_id, duplicate.candidate_id` 不变），仅 WHERE 增加候选边界限定。

**参数命名（P1-1 修复，编译阻断项）**：v0.1 的 SQL 含四个未命名 `?`，而 §2.2 第 3 点与 §2.3 只绑三个参数——SQLDelight **不合并未命名 `?` 绑定**，每个 `?` 各自成为一个参数，同名者以 `_` 后缀递增消歧（先例：`Ledger.sq:3999` 的 `updateRg06Lifecycle` 两个 `latest_sequence` → 生成 `latest_sequence`/`latest_sequence_`（`LedgerQueries.kt:10453`/`:10456`）；`Ledger.sq:9586` 的 `catalogReferencedStagingCategoryIds` 十个 `ledger_id` → 生成 `ledger_id`…`ledger_id_________`（`LedgerQueries.kt:7573-7582`））。**因此本查询改用命名参数**，使四个占位符的绑定与语义一一对应、不依赖消歧后缀：外层 `ledger_id`、子查询 `page_ledger_id`、游标 `after_candidate_id`、页大小 `page_size`（四个不同名，无需后缀消歧）。命名参数是本文件既有机制（如 `Ledger.sq:8802` 的 `manualCreationReceiptByTransaction` 用四个同名 `:ledger_id`/`:transaction_id` 并被正确去重为一个参数，`LedgerQueries.kt:6521`）。

**子查询的 `ledger_id` 为何与外层分开绑定（P1-1 的显式裁决）**：子查询是对 `import_candidate` 的**独立扫描**（"哪些候选进入本批"），外查询是候选 × 关联表的**投影**（"本批候选的全部行"）。两者是同一张表但**两个独立的表实例**，SQLite 无法把外层的绑定值传给子查询——必须各绑一次。本规格选择**绑定两次**（`ledger_id` + `page_ledger_id`），而不是重构成 `EXISTS`/自连接：理由是保持与 `importReviewRowsForLedger` 的 SELECT/JOIN 形状逐字节一致（评审已确认该形状等价性成立，重构会引入新的形状漂移面），且两个绑定在调用点由同一 `ledgerId.value` 赋值，不存在误传面。调用点必须**两次传同一值**，此约束在 §4 以断言钉死。

```sql
importReviewRowsForLedgerPage:
-- P7-04 OOM fix (design spec 2026-09-21 v0.2): byte-identical SELECT/JOIN/ORDER BY shape of
-- importReviewRowsForLedger (:8869-8918), narrowed to at most :page_size candidates strictly
-- after :after_candidate_id by the import_candidate PRIMARY KEY (ledger_id, candidate_id)
-- range. The candidate-boundary IN subquery guarantees all rows of one candidate land in one
-- batch (the adapter's per-candidate fold never splits). Zero DDL: the subquery is a pure
-- PK range scan. Named parameters (not bare '?') so the four bindings stay distinct without
-- SQLDelight's underscore-suffix disambiguation; the subquery's ledger scope is a separate
-- table instance from the outer one, so it binds the ledger id a second time
-- (:page_ledger_id) — the caller passes the same value twice.
SELECT
  candidate.candidate_id,
  candidate.candidate_kind,
  candidate.confidence,
  source.input_ref,
  source.amount_minor,
  source.currency_code,
  source.currency_precision,
  source.occurred_at,
  source.direction_token,
  source.status_token,
  source.funding_state,
  source.completeness,
  source.content_hash,
  latest_candidate_status.status AS candidate_status,
  EXISTS(SELECT 1 FROM import_candidate_requires_confirmation AS requirement
     WHERE requirement.ledger_id = candidate.ledger_id
       AND requirement.candidate_id = candidate.candidate_id
       AND requirement.requirement = 'formal_transaction_creation') AS requires_confirmation,
  duplicate.candidate_id AS duplicate_candidate_id,
  duplicate_status.status AS duplicate_latest_status,
  profile.variant AS payment_profile_variant,
  profile.asset_leg_kind_token AS payment_profile_asset_leg_kind_token,
  profile.credit_leg_kind_token AS payment_profile_credit_leg_kind_token
FROM import_candidate AS candidate
JOIN import_source_record AS source
  ON source.ledger_id = candidate.ledger_id AND source.source_id = candidate.source_id
LEFT JOIN import_candidate_status_history AS latest_candidate_status
  ON latest_candidate_status.ledger_id = candidate.ledger_id
 AND latest_candidate_status.candidate_id = candidate.candidate_id
 AND latest_candidate_status.sequence = (SELECT max(s3.sequence) FROM import_candidate_status_history AS s3
       WHERE s3.ledger_id = candidate.ledger_id AND s3.candidate_id = candidate.candidate_id)
LEFT JOIN import_candidate_payment_profile AS profile
  ON profile.ledger_id = candidate.ledger_id AND profile.candidate_id = candidate.candidate_id
LEFT JOIN import_duplicate_candidate AS duplicate
  ON duplicate.ledger_id = candidate.ledger_id
 AND duplicate.subject_source_id = candidate.source_id
LEFT JOIN import_duplicate_status_history AS duplicate_status
  ON duplicate_status.ledger_id = duplicate.ledger_id
 AND duplicate_status.candidate_id = duplicate.candidate_id
 AND duplicate_status.sequence = (SELECT max(s2.sequence) FROM import_duplicate_status_history AS s2
       WHERE s2.ledger_id = duplicate.ledger_id AND s2.candidate_id = duplicate.candidate_id)
WHERE candidate.ledger_id = :ledger_id
  AND candidate.candidate_id IN (
    SELECT candidate_id FROM import_candidate
    WHERE ledger_id = :page_ledger_id AND candidate_id > :after_candidate_id
    ORDER BY candidate_id LIMIT :page_size
  )
ORDER BY candidate.candidate_id, duplicate.candidate_id;
```

**生成签名（必须逐字成立，实施时以编译产物核对）**：

```kotlin
// ledger-data/build/generated/sqldelight/.../db/LedgerQueries.kt
public fun <T : Any> importReviewRowsForLedgerPage(
  ledger_id: String,
  page_ledger_id: String,
  after_candidate_id: String,
  page_size: Long,          // 见下方 P1-2：LIMIT 参数被推断为 INTEGER = Long
  mapper: (...) -> T,
): Query<T>

public fun importReviewRowsForLedgerPage(
  ledger_id: String,
  page_ledger_id: String,
  after_candidate_id: String,
  page_size: Long,
): Query<ImportReviewRowsForLedgerPage>   // 新生成的 20 列行类型（见 P2-1）
```

**P1-2：`LIMIT :page_size` 的类型是 `Long`，不是 `Int`。** SQLDelight 把 `LIMIT` 后的绑定表达式按 `PrimitiveType.INTEGER` 定型（`ArgumentsKt` 对 `SqlLimitingTerm` 直接返回 `PrimitiveType.INTEGER`，其 `javaType` 为 `TypeNames.LONG`——`dialect-api` 2.3.2 `PrimitiveType` 静态初始化实测），因此生成参数是 `page_size: Long`。仓库内既有先例：`duplicateMatchCountForIntake`（`Ledger.sq:8865` 的 `? = 1`）生成 `` `value`: Long ``（`LedgerQueries.kt:6565`）。**同时登记**：`LIMIT` 后接绑定参数在本仓库**没有先例**——`Ledger.sq` 现有 21 处 `LIMIT` 全部是字面量（如 `:4535` `LIMIT 1`、`:4444` `LIMIT 2`），全仓无 `LIMIT ?` / `LIMIT :x`。故这是**首次使用**，实施批必须以上述生成签名核对，不得假定 `Int` 可编译通过。adapter 的 `pageSize` 构造参数因此定为 `Long`（§2.3），`require(pageSize >= 1)` 与批大小常量随之同型。

设计要点：

1. **候选边界 = 正确性核心**：`IN (SELECT candidate_id FROM import_candidate WHERE ledger_id = :page_ledger_id AND candidate_id > :after_candidate_id ORDER BY candidate_id LIMIT :page_size)` 把本批限定为「严格大于 `after_candidate_id`、按 id 升序至多 page_size 个候选」。同一候选的全部行共享同一 `candidate_id`，IN 成员判定对它们一致——**同一候选的行必然全部落入同一批**，批内折叠与整表折叠输入逐字节一致。**该性质已在夹具上实证**：pageSize ∈ {2, 3, 7, 9999, 10000, 10001, 20000, 30500, 61000, 61001} 的分批结果与整表读**哈希一致**（20 列全量，168,000 行，零切分、零丢行），见 §8。
2. **零 DDL 成立**：`import_candidate` 主键为 `(ledger_id, candidate_id)`（Ledger.sq:7677-7688）——子查询是纯主键范围扫描，外查询候选侧同样走主键前缀。夹具 `EXPLAIN QUERY PLAN` 实测：外查询 `SEARCH candidate USING COVERING INDEX sqlite_autoindex_import_candidate_1 (ledger_id=? AND candidate_id=?)` + 子查询 `SEARCH import_candidate USING COVERING INDEX sqlite_autoindex_import_candidate_1 (ledger_id=? AND candidate_id>?)` + `CREATE BLOOM FILTER`——**无新索引需求**（61k 规模有层0 统计在位，D-148 交付后接治收尾点已 ANALYZE；若设备验证显示计划器退化，按 §10.3 另行取证，不静默改 DDL）。
3. **参数序**：生成的 Kotlin 调用为 `importReviewRowsForLedgerPage(ledgerId, ledgerId, afterCandidateId, pageSize)`——四个参数按 WHERE 出现序：`ledger_id`、`page_ledger_id`、`after_candidate_id`、`page_size`。**前两个由调用点传同一 `ledgerId.value`**（§4 断言钉死）。
4. **初始游标与空串失败模式（P3，登记）**：初始 `after_candidate_id = ""`（空串，TEXT BINARY 排序先于一切非空 id）。**失败模式如实登记**：`import_candidate.candidate_id` 在 schema 上只受 `NOT NULL` 约束（Ledger.sq:7679），**允许 `''`**；若某候选 id 恰为空串，它会因 `candidate_id > ''` 永假而**被永久跳过、静默缺席**（既不报错也不出现在任何批）。当前该风险为零：id 由 `UuidV7Generator` 产出（`UuidV7ImportIntakeIdSource.kt:21`），恒为 36 字符小写 8-4-4-4-12 文本，夹具实测 61,000 个 id 全为 36 字符、非 `[0-9a-f-]` 字符 0 个、空串 0 个。**缓解**：实施批在 adapter 入口加一条 `require(afterCandidateId.isNotEmpty() || 首轮)` 形态的显式前置断言不可行（首轮本就是空串），故改为在 §4 新增一条**结构性断言**——对空串 id 候选的库（测试可构造）断言其**不会**被静默丢弃，或明确声明该输入为契约外并 fail-loud。**本条冻结为实施批必须处置的登记项，不得以「id 生成器保证非空」静默带过**（契约依赖属实施假设，见 §6 第 4 项）。
5. 注释沿用本文件既有纪律：说明与 `importReviewRowsForLedger` 的形状等价关系，防止未来两查询列漂移。

### 2.3 adapter 改造（`loadImportReviewRows` 43–49 行）

```kotlin
class SqlDelightImportReviewReadAdapter(
    private val database: LedgerDatabase,
    // P7-04 OOM fix: candidate-per-batch bound, injectable so tests exercise the multi-batch
    // path with a tiny page size. Long because SQLDelight types a LIMIT bind as INTEGER=Long.
    private val pageSize: Long = IMPORT_REVIEW_ROWS_PAGE_SIZE,
) : ImportReviewReadPort {
    override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> {
        // P2-2: pageSize <= 0 is rejected up front. 0 would make `batch.size < pageSize` false
        // on an empty batch and send `batch.maxOf {}` into NoSuchElementException; a negative
        // value makes SQLite read LIMIT as unlimited (LIMIT -1 == no limit), silently losing
        // paging. Both are programming errors, not user input, so they fail loudly.
        require(pageSize >= 1) { "import review page size must be >= 1, was $pageSize" }
        // One read-only transaction covers every batch (snapshot consistency, freeze item
        // ACC-SNAP-01). Each batch is folded INSIDE the transaction body so the raw per-batch
        // lists are released before the next batch is read — that release IS the bounded-memory
        // property this fix exists for (section 2.4). The cost is stated honestly in section
        // 2.5: the single Android connection stays held across the folds too. noEnclosing = true
        // does not change the BEGIN mode on this driver version; it makes the nesting contract
        // fail loud instead of silently nesting. (SQLDelight 2.3.2's Transacter.transactionWithResult
        // names this parameter noEnclosing — there is no `readOnly` parameter.)
        return database.transactionWithResult(noEnclosing = true) {
            val result = mutableListOf<ImportReviewRow>()
            var afterCandidateId = ""
            while (true) {
                val batch =
                    database.ledgerQueries
                        .importReviewRowsForLedgerPage(
                            ledgerId.value,
                            ledgerId.value, // :page_ledger_id — same scope, bound a second time
                            afterCandidateId,
                            pageSize,
                        )
                        .executeAsList()
                        .map { it.toColumns() } // additive overload for the new generated row type
                result +=
                    batch
                        .groupBy { it.candidate_id }
                        .map { (_, groupedRows) -> groupedRows.first().toImportReviewRow(groupedRows) }
                if (batch.size.toLong() < pageSize) break
                afterCandidateId = batch.maxOf { it.candidate_id }
            }
            result
        }
    }
}
```

设计要点：

1. **批内折叠复用现有私有链，但有一个加性改动（P2-1 更正）**：`toImportReviewRow`（300–336 行）、`foldDuplicateStatus`（338–350 行）确实**不改一行**；但 v0.1 声称 `toColumns` 也「不改一行」是**错的**。`toColumns` 是**按生成行类型定义的重载**——现有两个分别是 `com.unifiedledger.data.db.ImportReviewRowsForLedger.toColumns()`（252–274 行）与 `com.unifiedledger.data.db.ImportReviewRowForCandidate.toColumns()`（276–298 行），每个生成行类型一个。**新增命名查询会铸造新的生成行类型 `ImportReviewRowsForLedgerPage`**，因此 `.map { it.toColumns() }` 需要**第三个加性重载**（同一 20 字段的机械展开）。这是纯加性、与既有两个重载同形，不改变任何既有行为；实施批须把它计入改动面。折叠语义（blocking-first，`foldPriority` 0–4）不变。
2. **终止条件（批行数 < pageSize）**：不变式——`import_duplicate_candidate` 为 LEFT JOIN，**每个候选至少贡献一行**（无重复候选产生一行全 null duplicate 行）。故「批返回行数 < pageSize ⟹ 批内候选数 < pageSize ⟹ 子查询范围已耗尽 ⟹ 无更多候选」。边界情形（尾批候选数 < pageSize 但重复行使其行数恰好 ≥ pageSize）至多多跑一次空批查询（返回 0 行 < pageSize）后终止——**终止性不受影响**。夹具实测边界例：pageSize=5,000 时尾批为 6,000 行（1,000 候选），随后恰有一次 0 行查询；pageSize=10,000 时尾批 6,000 行（1,000 候选）且**不需要**空批（§8）。**该条件依赖 §6 第 4 项 的 `pageSize >= 1` 前置**（P2-2）。
3. **keyset 推进**：`afterCandidateId = 批内最大 candidate_id`（TEXT 字典序与 SQL `ORDER BY candidate_id` 一致，见 §3 第 1 项 ACC-SORT-01 的 collation 前提）；下一批只取严格更大的候选，无重叠、无遗漏，候选集合为全集的严格递增划分。
4. **批内排序与批间累积**：批内行按 `(candidate_id, duplicate.candidate_id)` 升序；`groupBy` 保插入序（键序 = 候选升序）；批间按 candidate_id 升序累积。**最终列表顺序与现状整表读折叠完全一致**（§3 第 1 项 ACC-SORT-01）。
5. **批大小常量与注入**：`private const val IMPORT_REVIEW_ROWS_PAGE_SIZE = 10_000L`（候选/批），经构造参数 `pageSize: Long = IMPORT_REVIEW_ROWS_PAGE_SIZE` 注入——**加性构造注入是源码兼容的**：生产构造点 2 处（`android-app/.../App.kt:486`、`desktop-app/.../Main.kt:553`）、测试构造点 3 处（`ImportReviewTargetedReadEquivalenceTest.kt:142`/`:198`/`:289`）全部使用单参数形式，带默认值的第二个参数不破坏它们。**测试以小 pageSize 覆盖多批路径**（§4），运行时默认 10,000。
6. **异常语义（G6）不变**：批读抛出的任何异常沿 `loadImportReviewRows` 原抛出路径传播到用例边界映射 `Unavailable`；事务因异常回滚、不产生部分结果返回。与现状整表读失败同语义（一次读失败 = 整个读失败，绝不返回截断列表）。

### 2.4 内存面分析（修复后峰值，P1-3 更正后的口径）

**峰值结构** = ① 单批生成行列表 + ② 单批列投影列表 + ③ 单批 groupBy（≤pageSize 键）+ ④ 累积结果列表（61,000 行 ImportReviewRow，与现状相同的必需输出）。① ② ③ 随批释放，④ 为线性累积。

**单批行数的真实上界（更正）**：v0.1 用「平均重复率」估出「每批峰值 ≈ 3.6 万行」，并据此声称「峰值下降一个数量级以上 / ≈ 1/7」——**该估计不成立**。批是按**候选**计数的（每批至多 pageSize 个候选），但**行数取决于这些候选各自带多少重复**，而候选在 `candidate_id` 升序下**并非均匀混合**：夹具上无重复的 10,000 个候选恰好排在最前（rank 1–10,000 全部零重复），其后才是高重复候选。按本规格的精确循环在夹具上模拟（§8）：

- pageSize=10,000 → 批行数序列 `[10000, 10000, 20000, 30000, 40000, 52000, 6000]`，**最坏批 52,000 行 = 整表读 168,000 行的 31%**。
- pageSize=5,000 → 最坏批 27,000 行（约 16%）。
- pageSize=20,000 → 最坏批 92,000 行（约 55%）。
- pageSize=2,000 → 最坏批 12,000 行（约 7%）。

**可陈述的真实界**：`单批行数 ≤ pageSize × max_c(dup_count(c))`（夹具上 `max_c = 10`，故 pageSize=10,000 的理论上界 100,000 行、实测最坏 52,000 行）。**内存收益来自「① ② ③ 不再与 ④ 的整表版本共存」这一结构变化，而不是来自「单批只有整表的 1/7」这个量级断言**——后者已被实测否定。诚实的收益陈述是：

- **确定性收益**：整表读时 ①②③ 各自持有 168,000 元素并与 ④ 同时存活；分批后 ①②③ 只持有**单批**元素，且在批间释放。峰值从「168,000 元素 × 3 份结构 + 61,000 行结果」降为「最坏单批元素 × 3 份结构 + 61,000 行结果」。夹具上 pageSize=10,000 的最坏单批是 52,000 行（而非 168,000 行），即 ①②③ 的规模约降为 **1/3.2**（不是 1/7，也不是「一个数量级」）。
- **未获证明的部分**：该 3.2× 的降幅**是否足以**把峰值压回 192 MB 之下，**本规格不作断言**——v0.1 的「下降一个数量级以上」是无依据的推断，本轮撤回。这必须由 §5 第 1 项的 `dumpsys meminfo` 峰值读数与 ACC-THRESH-01 的数值门槛裁决（P2-6）；若读数显示仍超堆，按 §6 第 1 项下调 pageSize（下调会按上界线性缩小单批 ①②③，但会增加批数与总耗时），或按 §10.3 另行登记裁决。
- **④ 的大小与修复前完全一致**——这正是「输出不裁剪」的边界：若设备验证显示 ④ 本身在 61k 规模仍超堆，属另一问题（UI 层渲染面），按 §10.3 另行登记裁决，不在本批静默扩大。

### 2.5 事务与连接持有（P1-6 更正，契约项 P704OOM-ACC-TX-01）

**v0.1 的平台断言是错的。** v0.1 称 `database.transactionWithResult { }` 是「SQLDelight DEFERRED 事务」，并据此称「无写锁竞争…与现状单语句读一致」。实测（反编译 2.3.2 驱动 + AOSP 源码）如下：

| 平台 | 事务入口 | 实际 BEGIN 模式 |
| --- | --- | --- |
| **Android** | `AndroidSqliteDriver.newTransaction()` → `SupportSQLiteDatabase.beginTransactionNonExclusive()` | `FrameworkSQLiteDatabase.beginTransactionNonExclusive()` → `SQLiteDatabase.beginTransaction(listener, exclusive = false)` → `SQLiteSession.TRANSACTION_MODE_IMMEDIATE` → **`BEGIN IMMEDIATE;`**（BEGIN 即取 RESERVED 锁） |
| **Desktop (JDBC)** | `JdbcDriver.newTransaction()` → `ConnectionManager.beginTransaction(connection)` → `Connection.setAutoCommit(false)` | **DEFERRED**（JDBC 不发出 BEGIN IMMEDIATE） |

- **`noEnclosing` 不选 BEGIN 模式**：`TransacterImpl.transactionWithResult(noEnclosing)` 的 `noEnclosing` 只影响**嵌套检查**（`enclosingTransaction != null && noEnclosing` → 抛 `IllegalStateException("Already in a transaction")`）；它**不**选择 `BEGIN` 模式，也不改变驱动行为。**参数名与字节码证据（可复核）**：SQLDelight 2.3.2 的 `runtime-jvm-2.3.2.jar` 中 `app.cash.sqldelight.TransacterImpl` 与 `app.cash.sqldelight.Transacter` 暴露 `transactionWithResult(boolean, Function1)`，其 Kotlin 类文件元数据里的参数名为 **`noEnclosing`**（该 class 内不存在字符串 `readOnly`）；默认值来自 `Transacter$DefaultImpls.transactionWithResult$default`，为 **`false`**；`TransacterImpl.transactionWithWrapper(boolean, Function1)` 的字节码为 `if (enclosingTransaction != null && flag) throw IllegalStateException("Already in a transaction")`，即该 flag 只做嵌套检查、不选 BEGIN 模式。SQLDelight 的 Android 驱动**没有**调用 androidx 的 `beginTransactionReadOnly()`（该方法存在于 androidx 但驱动不调用）——即该 flag 与 Android 的只读事务入口无关。
- **本仓库未启用 WAL**：全仓零 `enableWriteAheadLogging` / `journal_mode` 命中；夹具 `PRAGMA journal_mode` 实测为 `delete`。AOSP `SQLiteConnectionPool.setMaxConnectionPoolSizeLocked()`：非 WAL 模式下 `mMaxConnectionPoolSize = 1`——**Android 侧整个应用只有一条连接**。
- **因此真实风险是连接/锁持有窗口**：把全部 ~7 次查询执行**加上客户端折叠**（v0.1 把 `groupBy/map` 放在事务块**内部**）压在一个 `BEGIN IMMEDIATE` 事务里，是对单条连接与 RESERVED 锁的**严格更长持有**，不是「与现状单语句读一致」。仓库自己的 A-PERF 规格 :48 记录的 D-148 ANR 链正是这一机制：后台读独占单条 Android 连接 → 主线程 `catalogSnapshot()` 等连接 → Input dispatch 超时。

**本规格的处置（三项，均须实施）**：

1. **改 `noEnclosing = true`**：`database.transactionWithResult(noEnclosing = true) { … }`。理由——(a) 语义正确：本读纯只读，声明 noEnclosing 使契约显式，并让**嵌套调用立即 fail-loud**（见下）而不是静默进入；(b) 它与 androidx 的 `beginTransactionReadOnly()` 语义对齐，若未来驱动改用该入口，Android 侧将得到真正的 DEFERRED 只读事务。**必须如实登记**：在 2.3.2 驱动上 `noEnclosing = true` **不会**把 Android 的 `BEGIN IMMEDIATE` 变成 `BEGIN DEFERRED`——它当前只兑现嵌套检查。该收益是**面向未来的语义对齐**，不是当下的锁模式改善。
2. **逐批折叠留在事务内，并如实登记其代价（与 v0.1 的差异说明）**：本规格**保留** v0.1 的「事务内逐批折叠」结构。理由与权衡（**显式说明，不静默选择**）：把折叠移出事务确实能缩短连接持有窗口，但**代价是峰值内存**——移出后必须把所有批的原始列投影列表一并留存到事务提交之后才能折叠（无法在事务内逐批释放），这正是本修复要消除的那类共存结构，且与 §2.4 的收益口径直接冲突。**取舍结论：内存是本批要修的缺陷（D-166 的 OOM），连接持有是次要风险（可观测、可回退）——故优先保内存，保留事务内逐批折叠**。连接持有的代价如实计入 §6 第 2 项的残留风险与 §5 第 4 项的必测项；若设备读数显示连接等待成为实际瓶颈，备选处置是**把折叠移出事务**（接受内存回升）或按 §10.3 另行取证，该选择由 §5 读数裁决，**不得在本规格中预先宣称两者兼得**。
3. **非嵌套契约**：`loadImportReviewRows` 声明为**不得在既有事务内调用**。`noEnclosing = true` 使嵌套调用抛出 `IllegalStateException("Already in a transaction")` 而非静默进入。**同时如实登记当前失败模式**：`QueryImportReviewRows.query`（`ledger-application/.../ImportReviewReadPort.kt:232-237`）以 `catch (failure: Exception)` 吞掉一切异常并返回 `ImportReviewRowsResult.Unavailable`——**嵌套抛错会静默降级为 Unavailable**（UI 呈现为读取失败，不是崩溃，也不产生截断列表，故不违反 G6；但它**会掩盖编程错误**）。处置：§4 新增一条断言，覆盖「已在事务内调用 → 不静默返回错误结果」的可观察面；实施批须确认该行为符合预期（fail-loud 与静默 Unavailable 之间选一，不得两种都存在）。

## 3. 语义冻结（逐项说明为何不变）

本设计对计划 §2 第 42 行「分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性」逐项冻结如下（每项均带 §7 的稳定验收项 ID）：

1. **排序（ACC-SORT-01）**：`ORDER BY candidate.candidate_id, duplicate.candidate_id` 在批查询中逐字节保留；批间按 candidate_id 升序累积（§2.3 第 3 点）。最终 `List<ImportReviewRow>` 的元素顺序与现状整表读折叠一致。`groupedRows.first()` 的选择（候选在批内的首行）不受分批影响：同一候选的行全在同批（§2.2 第 1 点），批内顺序即现状顺序。
   - **collation 前提（P2-3，显式声明而非断言无条件等价）**：keyset 推进用 Kotlin 的 `batch.maxOf { it.candidate_id }`，其比较是 **UTF-16 code unit 序**；SQLite 的 `ORDER BY candidate_id` 在无 `COLLATE` 修饰时用 **BINARY collation（UTF-8 字节序）**。两者对**补充平面字符（码点 ≥ U+10000）**并不等价——例如 U+FFFD（UTF-8 `efbfbd`）与 U+1F600（UTF-8 `f09f9880`）：BINARY 下 U+FFFD 在前，UTF-16 下 U+1F600 在前（本 worktree 实测两个比较结果相反）。**前提因此写明**：本等价性成立于「candidate_id 的字符集限定在 BMP 内（当前实现 = UUIDv7 的 36 字符 ASCII 小写 8-4-4-4-12，夹具 61,000 个 id 全部满足）」。**失败模式**：一旦出现补充平面字符，后果是 `afterCandidateId` 可能**小于**批内某候选的 BINARY 序位置，导致该候选在下一批被**重复读取**——即累积结果里出现**重复候选**（不是丢行）。该失败模式在 §4 以一条断言覆盖，并在 §6 第 4 项登记为实施假设。
2. **快照一致性（ACC-SNAP-01）**：全部批次在**单个事务**内执行（§2.3 的循环整体位于 `transactionWithResult` 体内，§2.5）。现状整表读是单语句（语句级快照）；改造后是单事务多语句（事务级快照）——一致性从语句级提升为事务级，**不弱化**。批间即使有其他连接写入，本读看到的是首个读时刻的一致视图。**平台注记**：Android 上该事务是 `BEGIN IMMEDIATE`（取 RESERVED 锁），故批间写入者也被阻挡；desktop 上是 DEFERRED。
3. **跨页勾选（ACC-SEL-01）**：UI 层无「页」概念——`view.rows` 仍是完整折叠结果（61,000 行 ImportReviewRow），勾选/批量授权/整组处置读取同一完整列表；分批只是 adapter 内部实现，逐项无感知。
4. **总数（ACC-COUNT-01）**：**本项冻结语义，不冻结条件性结果**（P2-5 更正）。v0.1 把「§10.3「完整计数」在 61k 规模可达」写成了冻结的**结果**，而 §2.4/§6 同时承认 ④ 的大小未变、仍可能溢出——那是**条件性验收结论**，不是设计语义，不应冻结。冻结的语义为：**列表读的返回值必须是完整列表，永不截断、永不静默丢候选；任何读取失败必须整体失败并映射 `Unavailable`，不得返回部分结果**。该语义在 61k 规模上**是否已达成**由 §5 第 1/2 项的设备读数裁决（并受 ACC-THRESH-01 约束），**不在本规格中预先宣布**。组头计数语义不变（D-166 第 2 条登记的「渲染 `view.rows` 投影不含会话内导入的新候选」是既有渲染语义，本设计不触碰渲染面）。
5. **组枚举完整性（ACC-GROUP-01）**：组枚举/整组处置读取 `view.rows` 完整投影（61,000 行），无任何组因分批缺失；批量处置的会话批量读（`loadImportDuplicateReviewsForSession`）不在本批范围、不动。
6. **折叠语义（ACC-FOLD-01）**：blocking-first `foldDuplicateStatus`（`CONFIRMED_DUPLICATE` < `DEFERRED` < `CONFIRMED_DISTINCT` < `DISMISSED_LOOKALIKE` < `REJECTED`）与重复候选集内的 fold 结果均与现状一致——重复候选按 `duplicate.candidate_id` 升序喂入，与现状同序。
7. **等价性测试基线（ACC-EQ-01）**：`wholeLedgerDetailBaseline`（`ImportReviewTargetedReadEquivalenceTest.kt:104-107`）以 `loadImportReviewRows` 为基线——分批是内部实现，**基线语义不变，既有测试继续有效**（§4）。
8. **G6 / absent-vs-empty（ACC-G6-01）**：本设计只改列表读的实现形态，不触碰详情/探针/会话读；`loadImportReviewRows` 的返回契约（完整列表，永不截断）不变。
9. **热读成本读数（ACC-PERF-01，P2-4 新增）**：本设计在**最热读路径**上增加工作（每批一次查询执行、每批一次主键范围扫描）。D-158 第 5 条要求热读增本先取实测读数并明示「**不得静默通过**」；计划 :44 要求保留基线、设备/API/资源配置、样本生成方式与规模、起止计时事件，且禁止把等待数据库连接与映射时间排除后宣布达标。v0.1 把这项推迟；本规格**承诺**在实施批验收前取得读数，口径与位置见 §5 第 3 项（记录批数、各批行数、整读耗时与峰值内存，并与整表读基线对照）。零 DDL 一侧已有证据：夹具 `EXPLAIN QUERY PLAN` 确认子查询走既有主键覆盖索引 + bloom filter（§2.2 第 2 点），即**无新增索引或表扫**——这支持「计划形状未退化」的判断，但不替代耗时读数。

### 3.1 数值验收阈值（P2-6 新增）

计划 :42 要求「每场至少 3 次记录最大值、内存与 ANR」，:44 禁止把等待数据库连接与 UI 可操作前的时间排除后宣布达标。据此为 61k 路径冻结以下**数值**阈值（本规格冻结，实施后由 §5 读数裁决）：

| 阈值 | 值 | 口径与依据 |
| --- | --- | --- |
| **峰值堆（列表读全程）** | **≤ 160 MB** | `dumpsys meminfo` 的 Java Heap + Native Heap 之和的峰值。基线 = D-166 第 3 条的 192 MB 堆上限下两次 OOM（进程死亡）；阈值取上限的 ~83%，留出 UI 渲染与 GC 余量。**这是本批的硬门**。 |
| **列表读可操作耗时（61k）** | **≤ 3 s** | 沿 A-PERF 冻结的 B2 口径（进导入 Tab → 组头计数可见且首组候选行可见），3 次取最大值。计划 :42 的原目标即「首屏/刷新可操作 ≤3 秒」；61k 规模沿用同一门槛（不新造口径）。 |
| **ANR / OOM / FATAL** | **零** | 计划 :190「合法规模下 ANR/OOM 为未通过」。 |

**未达标处置**：任一阈值未达 → 按 §6 第 1 项下调 pageSize 并重测；仍不达 → 按计划 §10.3/§10.4.5 登记为非 PASS、写明剩余缺口与承接，**不得改口径或把残余并入 PASS**。

## 4. 测试计划（JVM，等价性优先）

**既有测试回归（零改动）**：`ImportReviewTargetedReadEquivalenceTest.kt` 三用例（详情定向等价、blocking-first 折叠跨读等价、存在性探针 absent-vs-empty）以 `loadImportReviewRows` 为基线，改造后语义不变，预期全绿；ledger-data 全套 jvmTest 零回归。

**新增 jvmTest（同文件或同目录新文件，沿既有纪律：真实 spine 写路径 `ExecuteImportIntake` + `ReviewImportDuplicateCandidate`、IN_MEMORY JDBC、匿名合成值、不引 mock 库）。每条带 §7 的稳定 ID**：

1. **ACC-T-BATCH-01 分批与整表逐行全量等价**：在含**多候选 / 多重复 / 无重复**混合库上（沿 `ImportReviewTargetedReadEquivalenceTest` fixture 纪律铺库），以**小 pageSize 注入**（如 2，强制多批路径）断言 `loadImportReviewRows` 分批结果与整表读取基线（以现有私有折叠形状重建的参照实现，或先取修复前语义的参照函数）**逐行全量相等**（`List<ImportReviewRow>` 整体 assertEquals；含 candidate 字段、折叠 duplicateStatus、payment profile 列——完整值相等）。
2. **ACC-T-BOUNDARY-01 页边界候选完整性**：构造候选 id 恰在批边界上的库（如候选数 = pageSize + 1，或 pageSize 精确整除候选数），断言**边界候选的全部重复行完整落入单批**（等价断言整体覆盖；另加一个结构性断言：边界候选的折叠结果与其全部重复行在结果中的一致——由全量等价断言隐含，单独列出便于评审定位）。
3. **ACC-T-PARAM-01 批大小参数化**：adapter 构造注入 `pageSize`，覆盖 pageSize=1（每批单候选）、pageSize=整库候选数（单批，行为退化为现状整表读）两个端点。
4. **ACC-T-TAIL-01 空/尾批终止**：空库（0 候选）→ 空列表；候选数恰为 pageSize 整数倍 → 尾批恰好满、无多余查询（可由注入的查询计数可观察面断言，或仅依赖全量等价）。
5. **ACC-T-G6-01 G6 回归**：既有失败路径断言不因事务包裹改变（批内异常 → 整读异常传播，不返回部分列表）——若既有测试已覆盖读取失败面则保持，否则补一条最小断言。
6. **ACC-T-PAGESIZE-01 退化 pageSize 拒绝（P2-2 新增）**：`pageSize = 0` 与 `pageSize < 0` **两个端点各一条断言**，均须在进入查询前抛出（`IllegalArgumentException`，来自 `require(pageSize >= 1)`）——不得走到 `batch.maxOf {}` 的 `NoSuchElementException`，也不得让负值被 SQLite 读作 `LIMIT` 无限。
7. **ACC-T-COLLATION-01 排序前提（P2-3 新增）**：构造一个含**补充平面字符**的 candidate_id 的库，断言「Kotlin UTF-16 推进 vs SQLite BINARY 序」的分歧**被观察到**（即该候选在结果中**重复出现**，而不是静默丢失）——把 ACC-SORT-01 声明的失败模式钉成可观察事实，避免未来误以为等价性无条件成立。
8. **ACC-T-EMPTYID-01 空串候选 id（P3 新增）**：构造一个 `candidate_id = ''` 的候选，断言其**不会静默缺席**（或按 §2.2 第 4 点的决定 fail-loud）。本项的具体判定口径在实施批冻结（见 §6 第 4 项）。
9. **ACC-T-NESTING-01 非嵌套契约（P1-6 新增）**：在既有事务内调用 `loadImportReviewRows`，断言可观察行为（`noEnclosing = true` 下为 `IllegalStateException`），并断言 `QueryImportReviewRows.query` 的映射结果与预期一致——使「静默降级为 Unavailable」这一当前失败模式成为**被测试钉死的事实**，而不是未被察觉的行为。

## 5. 设备验证计划（实施后，主代理设备窗口）

前置：**设备当前库**（61,220 候选 / 160,200 重复关系 / 2 交易，schema v31）——P1-4 更正：§5 的对照基线是**设备库现状**，不是夹具文件。**设备库在仓库内没有任何工件**——它只存在于设备上（仅可经设备读取），仓库内唯一落盘的相关工件是夹具文件 `local/artifacts/p7-05-scale/ledger-61000-v31-backup.db`（61,000 / 158,000 / 2）。该夹具是**另一份工件**，可作为恢复起点，但恢复后须重新导入至 61,220 才能与 D-166 的 OOM 基线同规模对照（或在报告中显式声明以 61,000 规模复测并给出规模差异说明，沿 D-159 §10.2 差异说明先例）。恢复/铺库至 AVD `ul_p7_d01`（API 36，隔离 adb 端口纪律按 AGENTS.md）。

1. **ACC-D-OOM-01 OOM 消除复证**：冷启动与「刷新清单」各触发 `loadImportReviewRows`，logcat 全程零 `OutOfMemoryError`/`FATAL`/`ANR`；`dumpsys meminfo` 记录峰值，对照 ACC-THRESH-01 的 **≤160 MB** 阈值与 D-166 的 192 MB 堆死亡基线。
2. **ACC-D-COUNT-01 完整计数语义复证**：重跑 countProbe 仪器（`ImportScaleTraversalInstrumentedTest`，D-166 交付）——postScroll 读数流程在 61k 库上完整跑通，DB 权威计数与渲染可达；修复前该流程因加载即崩不可达。**本项裁决 ACC-COUNT-01 的语义是否达成，不预先宣布结果**。
3. **ACC-D-PERF-01 热读成本读数（P2-4，不得静默通过）**：记录 7 批读的**批数与各批行数**、整体耗时与峰值内存，并与**整表读基线**对照（D-158 第 5 条与计划 :44 口径：热读增本须有实测读数，不得只计 SQL 或排除连接等待）；同时以 B2 口径（进导入 Tab → 组头计数可见且首组候选行可见）计时，logcat 时间戳交叉验证（D-148 测量经济性：每场 ≥3 次取最大值；uiautomator dump 地板伪影按 D-148 B5 复核先例处理）。对照 ACC-THRESH-01 的 **≤3 s** 阈值。
4. **ACC-D-CONN-01 连接持有读数（P1-6 新增）**：在批次读进行中触发一次主线程 `catalogSnapshot()` 读（或等效的 UI 交互），确认不出现 D-148 ANR 链形态的输入超时；记录连接等待是否可观察。若观察到等待，按 §2.5 第 2 点登记的备选（把折叠移出事务、接受内存回升）处置，或按 §10.3 另行登记。
5. **ACC-D-20K-01 20k 回归**：D-148 的 20k 库场景（B2/B3/B6）复测不退化（本设计在 20k 库上为少数批即完成）——作为回归向量，不重开整套 A-PERF 门槛。
6. 验证报告与结论由主代理设备证据批落盘，不在本 worktree。

## 6. 风险与回退

1. **批大小调参**：10,000 为默认（61,000 候选 → 7 批）。若设备峰值内存仍紧，下调（如 5,000 → 14 批；夹具最坏批从 52,000 行降到 27,000 行）摊薄单批物化；若批间固定开销（每批一次查询执行 + 事务内步进）可见，上调。批大小为构造参数 + 常量，**调参属实施内调整**，不改变本规格契约；调参后须重跑 §5 第 1/3 项。
2. **事务开销与连接持有（P1-6 更正后的诚实陈述）**：Android 侧事务是 `BEGIN IMMEDIATE`（取 RESERVED 锁），且**非 WAL 库的连接池被 AOSP 强制为 1 条连接**。因此本设计的真实代价是：在单条连接的持有窗口内执行 ~7 次查询，而不是 1 次。**处置**：`noEnclosing = true`（语义对齐 + 嵌套 fail-loud，不改变 BEGIN 模式，§2.5 第 1 点）、非嵌套契约（§2.5 第 3 点）。**未采用的备选与理由**：把折叠移出事务可缩短连接持有，但会把所有批的原始列表留到事务之后才能折叠，直接牺牲 §2.4 的内存收益——本批要修的是内存，故**不采用**（§2.5 第 2 点）。**残留风险如实登记**：连接持有窗口**就是**「~7 次查询 + 逐批折叠」，比现状单语句读长；A-PERF 规格 :48 记录的 D-148 ANR 链（后台读独占连接 → 主线程等连接 → Input dispatch 超时）在本设计下**理论上仍可能被触发**。故 §5 第 4 项为**必测项**；若读数显示等待可观察，按 §2.5 第 2 点登记的备选（把折叠移出事务、接受内存回升）处置，或按 §10.3 另行取证，**不静默改**。
3. **与 D-148 裁决的关系（诚实边界）**：本设计**不推翻** D-148「列表读本体保留整账本读」及其 20k 库 0.24s 测量（耗时口径）；修订的是「无需分页」在 **61k 内存面**的推论——D-166 第 3 条已登记 D-148 未覆盖整表物化内存面、A-PERF 门槛在 ≤30k 尺度测得。取代该非目标的显式登记见 §1.1。20k 库上分批与整表读语义等价（单/双批即完成），不构成对既有裁决的偏离；本设计是 D-166 第 5 条承接的**修复路线**（相对「规模口径裁决」路线）。
4. **实施假设登记（契约依赖，须在实施批复核）**：(a) 初始 `after_candidate_id = ""` 依赖 `candidate_id` 非空契约——schema 只保证 `NOT NULL`（允许 `''`），当前由 UUIDv7 生成器保证 36 字符 ASCII；空串 id 的失败模式与处置见 §2.2 第 4 点与 ACC-T-EMPTYID-01。(b) keyset 推进依赖「candidate_id 字符集限于 BMP」以使 Kotlin UTF-16 序与 SQLite BINARY 序一致——当前 UUIDv7 ASCII 满足；失败模式与断言见 ACC-SORT-01 与 ACC-T-COLLATION-01。(c) `LIMIT :page_size` 的参数类型为 `Long`，本仓首次使用（§2.2 P1-2）。若未来 id 生成器变更任一契约，须复核本设计——登记为实施假设，不构成当前风险。
5. **回退**：改动面 = Ledger.sq 新增一个只读命名查询 + adapter 单函数改造 + 一个 `toColumns` 加性重载 + 新增 jvmTest。回退 = 还原 `loadImportReviewRows` 至整表读并移除命名查询（或保留查询不接线）；零 schema/迁移/依赖影响，回退面与风险面一致。
6. **状态分类（P3）**：本规格状态标记由 v0.1 的 `draft` 更正为 v0.2 的 `proposal`，现已升为 `approved`（CONTRIBUTING.md:162 的允许分类为 `approved`/`proposal`/`superseded`/`historical`，`draft` 不在其中）。**批准依据**：独立规格评审的首轮评审（结论 REVISE，机制 sound）通过后提交修订版 v0.2，针对修订版 `9c43787` 的独立闭包复评给出结论 **APPROVE**（C1–C4 全部闭合、无新 P1/P2）；主代理据此于本次批准本规格，故本规格标 `approved`。本规格既已获批，§1.1 的取代记录（P704OOM-ACC-SUPERSEDE-01）随之对 A-PERF approved 规格生效；实施、Git 写操作与最终验收属后续实施批。

## 7. 验收项索引（稳定 ID，P1-7 新增）

计划 :157 要求「每批设计冻结前，为该批原始要求分配稳定的验收项 ID；…新任务使用子项 ID 加序号」。本批前缀 `P704OOM`，下表为**评审处置的稳定句柄**（每条在 §3/§4/§5 中重复出现，评审按 ID 逐项给结论，不得用「整体通过」覆盖）：

| ID | 类型 | 内容 | 承载位置 |
| --- | --- | --- | --- |
| P704OOM-ACC-SORT-01 | 冻结 | 排序：`ORDER BY` 逐字节保留、批间升序累积、含 collation 前提 | §3 第 1 项 |
| P704OOM-ACC-SNAP-01 | 冻结 | 快照一致性：单事务多语句，不弱于现状语句级快照 | §3 第 2 项 |
| P704OOM-ACC-SEL-01 | 冻结 | 跨页勾选：UI 无「页」概念，完整列表语义不变 | §3 第 3 项 |
| P704OOM-ACC-COUNT-01 | 冻结 | 总数：冻结「完整、永不截断」语义，不冻结条件性结果 | §3 第 4 项 |
| P704OOM-ACC-GROUP-01 | 冻结 | 组枚举完整性：无组因分批缺失 | §3 第 5 项 |
| P704OOM-ACC-FOLD-01 | 冻结 | 折叠语义：blocking-first 顺序与现状一致 | §3 第 6 项 |
| P704OOM-ACC-EQ-01 | 冻结 | 等价性测试基线语义不变 | §3 第 7 项 |
| P704OOM-ACC-G6-01 | 冻结 | G6 / absent-vs-empty 不变 | §3 第 8 项 |
| P704OOM-ACC-PERF-01 | 冻结 | 热读增本须取实测读数，不得静默通过 | §3 第 9 项 |
| P704OOM-ACC-THRESH-01 | 冻结 | 数值阈值：峰值堆 ≤160 MB、列表可操作 ≤3 s、零 ANR/OOM/FATAL | §3.1 |
| P704OOM-ACC-SQL-01 | 契约 | SQL 四参数命名绑定与生成签名（`ledger_id`/`page_ledger_id`/`after_candidate_id`/`page_size: Long`） | §2.2 |
| P704OOM-ACC-SUPERSEDE-01 | 治理 | A-PERF approved 规格 :66/:84「不改分页」的显式取代（五要素） | §1.1 |
| P704OOM-ACC-TX-01 | 契约 | `noEnclosing = true`（不改 BEGIN 模式）、逐批折叠留事务内及其代价登记、非嵌套契约 | §2.5 |
| P704OOM-T-BATCH-01 | JVM | 分批与整表逐行全量等价（小 pageSize） | §4 第 1 项 |
| P704OOM-T-BOUNDARY-01 | JVM | 页边界候选完整性 | §4 第 2 项 |
| P704OOM-T-PARAM-01 | JVM | 批大小端点（1 / 整库） | §4 第 3 项 |
| P704OOM-T-TAIL-01 | JVM | 空库与尾批终止 | §4 第 4 项 |
| P704OOM-T-G6-01 | JVM | G6 失败路径不因事务包裹改变 | §4 第 5 项 |
| P704OOM-T-PAGESIZE-01 | JVM | `pageSize = 0` 与 `< 0` 两端点拒绝 | §4 第 6 项 |
| P704OOM-T-COLLATION-01 | JVM | 补充平面字符下重复候选（非静默丢失）可观察 | §4 第 7 项 |
| P704OOM-T-EMPTYID-01 | JVM | 空串 candidate_id 不静默缺席（或 fail-loud） | §4 第 8 项 |
| P704OOM-T-NESTING-01 | JVM | 事务内调用的可观察行为与 Unavailable 映射 | §4 第 9 项 |
| P704OOM-D-OOM-01 | 设备 | 冷启动/刷新零 OOM/FATAL/ANR + 峰值内存 ≤160 MB | §5 第 1 项 |
| P704OOM-D-COUNT-01 | 设备 | countProbe postScroll 在 61k 跑通（裁决 ACC-COUNT-01 语义） | §5 第 2 项 |
| P704OOM-D-PERF-01 | 设备 | 批数/各批行数/耗时/峰值 + 整表读对照 + B2 ≤3 s | §5 第 3 项 |
| P704OOM-D-CONN-01 | 设备 | 连接持有窗口不触发 D-148 ANR 链形态 | §5 第 4 项 |
| P704OOM-D-20K-01 | 设备 | 20k 库 B2/B3/B6 不退化 | §5 第 5 项 |

## 8. 本规格自身的只读复核证据（P1-3/P1-4 更正依据）

以下读数均在**主 checkout 的夹具上只读**取得（`local/artifacts/p7-05-scale/ledger-61000-v31-backup.db`，未写入；工具：`sqlite3 -readonly` 3.50.6 与 Python 3.12 `sqlite3` 模块 3.49.1），用于更正 v0.1 的行数推演与峰值口径。夹具计数：**61,000 候选 / 158,000 重复关系 / 2 交易 / user_version 31**（`PRAGMA user_version` = 31；候选表与重复表计数直接查询）。

1. **行数**：`Σ max(1, dup_count(candidate))` = **168,000**（51,000 个候选有 ≥1 重复 → 158,000 行；10,000 个无重复候选 → 10,000 行全 null duplicate 行）。
2. **逐候选行数分布**：1..10 行，其中 rank 1–10,000 全部为 1 行（零重复），rank 10,001–30,000 全部有重复——这解释了为何重复候选集中在后段、最坏批出现在末段。
3. **分批等价性**：pageSize ∈ {2, 3, 7, 9999, 10000, 10001, 20000, 30500, 61000, 61001} 的全 20 列分批读与整表读**逐字节相同**（168,000 行，哈希一致），零切分、零丢行。
4. **批行数序列（精确循环模拟）**：pageSize=10,000 → `[10000, 10000, 20000, 30000, 40000, 52000, 6000]`（最坏 52,000 行）；pageSize=5,000 → 最坏 27,000 行，且尾批 6,000 行后恰有一次 0 行查询；pageSize=2,000 → 最坏 12,000 行。
5. **查询计划（零 DDL）**：外查询 `SEARCH candidate USING COVERING INDEX sqlite_autoindex_import_candidate_1 (ledger_id=? AND candidate_id=?)`；子查询 `SEARCH import_candidate USING COVERING INDEX sqlite_autoindex_import_candidate_1 (ledger_id=? AND candidate_id>?)` + `CREATE BLOOM FILTER`。
6. **排序前提**：`PRAGMA journal_mode` = `delete`（非 WAL）；61,000 个 `candidate_id` 全为 36 字符、零空串、零非 `[0-9a-f-]` 字符。
7. **`LIMIT` 无先例**：`Ledger.sq` 全部 21 处 `LIMIT` 均为字面量，全仓无 `LIMIT ?` / `LIMIT :x`。

**未取读数（不冒充）**：本规格**未**测量修复后的真实内存占用与耗时（那需要实施后的设备窗口，§5）；§2.4 的 1/3.2 降幅是**由行数推得的结构比**，不是实测内存比。ACC-THRESH-01 是**冻结门槛**，不是已达成结果。

## 9. 交付物与范围外

**交付物**：本规格（tracked，`docs/specs/`）；实施批交付 Ledger.sq 命名查询、adapter 改造（含 `toColumns` 加性重载与构造注入）、新增 jvmTest（同批提交）；设备复测报告（主代理设备证据批，含 ACC-THRESH-01 读数与 §5 第 3/4 项）。

**范围外（如实登记，不静默扩大）**：UI/端口/用例层改动（`view.rows` 渲染面、勾选状态、组枚举）；其他列表读路径（月度查询、写事务主线程现状）；`loadImportReviewRows` 之外的内存面（结果列表 ④ 本身的渲染内存——若 61k 规模另有 UI 层 OOM，另行登记）；schema/迁移/依赖；D-166 剩余缺口（≥20k 组卡页脚/批量入口可达性，承接 D-165 第 4 条，与本批无关）；A-PERF approved 规格中「不改分页」之外的任何非目标（投影裁剪、截断、隐藏、组范围、D-147 会话批量读语义等，逐条保留）。
