# P7-04 Import Review List OOM Fix Design — Keyset Paged Read（导入审核列表 61k 规模 OOM 修复设计规格）

状态：draft（待评审批准。本文件为设计规格草案：修复方案由主代理依据 D-166 缺陷登记与既有 A-PERF 裁决取证后定案，本规格负责把方案落盘为可评审、可实施、可验收的契约；未冻结任何实施细节，评审后可修订。实施、Git 写操作与最终验收属后续实施批）。

**Revision:** v0.1（2026-09-21 草案）。依据：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF 与 §10.3；D-148（DECISIONS.md:2831）；D-166（DECISIONS.md:3186，第 3 条 OOM 缺陷登记、第 5 条承接）；2026-09-21 设备取证（61,220 候选 / 160,200 重复 / 2 交易库上两次 OutOfMemoryError，备份 `local/artifacts/p7-05-scale/ledger-61000-v31-backup.db`）。本规格不改动任何既有裁决，只对 D-166 登记的缺陷给出修复设计。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为当前 worktree 基线 `83f92bf` 实读行号；`.local.md` 与本地证据包以主 checkout 为准、只读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF（红线：第 36 行「禁止靠截断、隐藏待确认项或更改组审核范围提速」；第 42 行「分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性」）、§10.3（规模项处置：61k 规模「完整计数」受 OOM 阻断，属 D-166 第 5 条剩余缺口，须修复或规模口径裁决——本设计走修复）。
- **决定（已确认）**：D-148（导入候选读治理批：层0 统计刷新 / 层1 定向读 / 层2 主线程 catalog 读治理；「列表读本体保留整账本读」裁决基于 20k 库 0.24s 测量，未覆盖列表整表物化的内存面）；D-166（61k 规模 OOM 缺陷登记：`SqlDelightImportReviewReadAdapter.kt:43-49` 整表物化点，栈 `SqlDelightImportReviewReadAdapter.kt:46` → `LedgerQueries.kt:6604`（CursorWindow.getString），堆 192 MB，进程死亡两次；承接 = 缺陷修复（列表读分页/投影裁剪/流式物化等）或规模口径裁决）。
- **源码现实**：`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportReviewReadAdapter.kt`（`loadImportReviewRows` 43–49 行；`toColumns`/`toImportReviewRow`/`foldDuplicateStatus` 私有折叠链 229–350 行）；`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq`（`importReviewRowsForLedger` 8869–8903 行，20 列、4×JOIN/LEFT JOIN、`ORDER BY candidate.candidate_id, duplicate.candidate_id`；`import_candidate` 定义 7677–7687 行，`PRIMARY KEY (ledger_id, candidate_id)`）；等价性测试 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/ImportReviewTargetedReadEquivalenceTest.kt`（`wholeLedgerDetailBaseline` 104–107 行以 `loadImportReviewRows` 为基线）。
- **设备证据（只读，主 checkout）**：D-166 第 3 条登记与 `docs/PROJECT_STATE.local.md` 检查点；61k 库备份 `local/artifacts/p7-05-scale/ledger-61000-v31-backup.db`（schema v31，61,220 候选 / 160,200 重复 / 2 交易）。
- **不可触碰面**：`.external/` 只读；零 DDL、零 schema 变更（schema 停留 v31）、零迁移；零 UI/端口/用例层改动（`ImportReviewReadPort` 签名不变）；零依赖变更；`importReviewRowForCandidate`（单候选详情定向读）与两个存在性探针、会话批量读不动；golden fixtures/expected 零改动；`rgXX_` 竖井不动。

## 1. 问题与根因（证据与推演）

**缺陷事实（D-166 第 3 条登记）**：冷启动/刷新触发 `loadImportReviewRows` 在 61k+ 候选库上 **OutOfMemoryError 两次**（设备时 10:30:27、10:57:40；堆 192 MB 上限，进程死亡）。**产品影响**：61k 库加载即崩 ⇒ §10.3「完整计数」在 61k 规模当前不可达。D-148 读治理未覆盖列表整表物化的内存面：A-PERF 门槛在 ≤30k 尺度测得，61k 尺度超出其证据面（D-166 第 3 条明示）。

**行数推演（61,220 候选 / 160,200 重复）**：`importReviewRowsForLedger`（Ledger.sq:8869-8903）每 (candidate, duplicate candidate) 对一行；LEFT JOIN `import_duplicate_candidate` 保证**无重复候选也恰产生一行**（全 null duplicate 列）。结果行数 = 候选数 + 重复关系数 = 61,220 + 160,200 = **221,420 ≈ 22.1 万行 × 20 列**。

**多份大结构同时存活（OOM 根因）**：`loadImportReviewRows` 现有链（`SqlDelightImportReviewReadAdapter.kt:43-49`）：

```
executeAsList()          // ① 物化 22.1 万生成行（CursorWindow.getString 逐列拷贝，栈顶压力点 LedgerQueries.kt:6604）
  .map { it.toColumns() }   // ② 22.1 万 ImportReviewRowColumns 中间列表（与 ① 共存）
  .groupBy { it.candidate_id }  // ③ 61,220 键的 Map<候选, 行列表>（与 ① ② 共存）
  .map { 折叠 }               // ④ 61,220 行 ImportReviewRow 结果列表
```

峰值 = ① ② ③ ④ 四份大结构并列存活：22.1 万行的生成行列表与列投影列表、61,220 键的 groupBy Map（值列表合计仍为 22.1 万元素）、以及最终结果列表。192 MB 堆不足以容纳该峰值组合。**最终结果列表（④，61,220 行 ImportReviewRow）本身是必需的输出**（UI 层 `view.rows` 即此投影，D-166 第 2 条），OOM 的根因是①②③与④同时存活，而非④单独过大——这决定了修复方向：**缩小瞬时共存结构，而不是裁剪输出**。

**与 D-148 裁决的关系（不冲突）**：D-148「列表读本体保留整账本读」的推论「无需分页」由 20k 库 0.24s 测量支撑——那是**耗时口径**（计划器选索引问题经层0 统计修复后解决）；整表物化的**内存口径**在 ≤30k 尺度未构成问题、在 61k 尺度构成 OOM。本设计在 61k 内存面上修订「无需分页」推论，**不推翻 20k 测量**（§6）。

## 2. 修复设计（客户端 keyset 分批读取 + 批内折叠 + 事务快照）

### 2.1 总览

`loadImportReviewRows` 的读法改为：**单只读事务内、按候选主键 keyset 分批**读取——每批以 `importReviewRowsForLedgerPage` 取「候选 id 大于上一批最大 id、最多 pageSize 个候选」的全部行，批内执行与现状**逐字节相同**的 `map{toColumns()}.groupBy{candidate_id}.map{折叠}`，折叠结果按批序累积为最终列表。**输出语义与现状逐字节等价**（§3 逐项冻结）。

修复方向为何不是其他选项（登记，供评审对照）：

- **不做投影裁剪**：20 列全部进入 `ImportReviewRow`（`ImportReviewRowColumns` 229–250 行为全列投影，`toImportReviewRow` 300–336 行逐字段消费），裁剪会改变读模型与折叠输入。
- **不做行级 LIMIT 分页**：无候选边界地截断行会**把同一候选的重复行切到不同批**，直接破坏 `groupBy` 折叠——候选边界限定是正确性的核心装置（§2.2）。
- **不做流式物化**：SQLDelight 生成面返回 List，流式需动驱动/生成层，超出本批范围且不必要——keyset 分批已把峰值降到有界。
- **不改 UI/端口**：`ImportReviewReadPort.loadImportReviewRows` 签名与 `view.rows` 完整结果语义不变。

### 2.2 Ledger.sq：新增命名查询 `importReviewRowsForLedgerPage`

紧邻 `importReviewRowsForLedger`（Ledger.sq:8869）新增只读命名查询：**同一 SELECT 形状**（20 列、JOIN/LEFT JOIN 不变、`ORDER BY candidate.candidate_id, duplicate.candidate_id` 不变），仅 WHERE 增加候选边界限定：

```sql
importReviewRowsForLedgerPage:
-- P7-04 OOM fix (design spec 2026-09-21): byte-identical SELECT/JOIN/ORDER BY shape of
-- importReviewRowsForLedger (:8869-8903), narrowed to at most :pageSize candidates strictly
-- after :afterCandidateId by the import_candidate PRIMARY KEY (ledger_id, candidate_id)
-- range. The candidate-boundary IN subquery guarantees all rows of one candidate land in one
-- batch (the adapter's per-candidate fold never splits). Zero DDL: the subquery is a pure
-- PK range scan.
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
WHERE candidate.ledger_id = ?
  AND candidate.candidate_id IN (
    SELECT candidate_id FROM import_candidate
    WHERE ledger_id = ? AND candidate_id > ?
    ORDER BY candidate_id LIMIT ?
  )
ORDER BY candidate.candidate_id, duplicate.candidate_id;
```

设计要点：

1. **候选边界 = 正确性核心**：`IN (SELECT candidate_id FROM import_candidate WHERE ledger_id=? AND candidate_id>? ORDER BY candidate_id LIMIT ?)` 把本批限定为「严格大于 `afterCandidateId`、按 id 升序至多 pageSize 个候选」。同一候选的全部行共享同一 `candidate_id`，IN 成员判定对它们一致——**同一候选的行必然全部落入同一批**，批内折叠与整表折叠输入逐字节一致。
2. **零 DDL 成立**：`import_candidate` 主键为 `(ledger_id, candidate_id)`（Ledger.sq:7677-7687）——子查询是纯主键范围扫描，外查询候选侧同样走主键前缀。**无新索引需求**（61k 规模有层0 统计在位，D-148 交付后接治收尾点已 ANALYZE；若设备验证显示计划器退化，按 §10.3 另行取证，不静默改 DDL）。
3. **参数序**（生成的 Kotlin 调用 `importReviewRowsForLedgerPage(ledgerId, afterCandidateId, pageSize)`）：`?` 依次为 ledgerId、afterCandidateId、pageSize，与 WHERE 出现序一致。初始 `afterCandidateId = ""`（空串，TEXT 排序先于一切非空 id；依赖 `candidate_id` 非空契约——id 由生成器产出、绝不为空串，登记为实施假设，§6）。
4. 注释沿用本文件既有纪律：说明与 `importReviewRowsForLedger` 的形状等价关系，防止未来两查询列漂移。

### 2.3 adapter 改造（`loadImportReviewRows` 43–49 行）

```kotlin
override fun loadImportReviewRows(ledgerId: LedgerId): List<ImportReviewRow> =
    database.transactionWithResult {
        val result = mutableListOf<ImportReviewRow>()
        var afterCandidateId = ""
        while (true) {
            val batch =
                ledgerQueries
                    .importReviewRowsForLedgerPage(ledgerId.value, afterCandidateId, pageSize)
                    .executeAsList()
                    .map { it.toColumns() }
            if (batch.size < pageSize) {
                // 尾批：行数 < pageSize ⟹ 批内候选 < pageSize（每候选 ≥1 行的不变式）⟹ 无更多候选。
                result += batch.groupBy { it.candidate_id }
                    .map { (_, groupedRows) -> groupedRows.first().toImportReviewRow(groupedRows) }
                break
            }
            afterCandidateId = batch.maxOf { it.candidate_id }
            result += batch.groupBy { it.candidate_id }
                .map { (_, groupedRows) -> groupedRows.first().toImportReviewRow(groupedRows) }
        }
        result
    }
```

设计要点：

1. **批内折叠复用现有私有链逐字节不动**：`toColumns`（252–274 行）、`toImportReviewRow`（300–336 行）、`foldDuplicateStatus`（338–350 行）不改一行；仅外层从「一次整表读」改为「循环批读」。折叠语义（blocking-first，`foldPriority` 0–4）不变。
2. **终止条件（批行数 < pageSize）**：不变式——`import_duplicate_candidate` 为 LEFT JOIN，**每个候选至少贡献一行**（无重复候选产生一行全 null duplicate 行）。故「批返回行数 < pageSize ⟹ 批内候选数 < pageSize ⟹ 子查询范围已耗尽 ⟹ 无更多候选」。边界情形（尾批候选数 < pageSize 但重复行使其行数恰好 ≥ pageSize）至多多跑一次空批查询（返回 0 行 < pageSize）后终止——**终止性不受影响**。
3. **keyset 推进**：`afterCandidateId = 批内最大 candidate_id`（TEXT 字典序与 SQL `ORDER BY candidate_id` 一致）；下一批只取严格更大的候选，无重叠、无遗漏，候选集合为全集的严格递增划分。
4. **批内排序与批间累积**：批内行按 `(candidate_id, duplicate.candidate_id)` 升序；`groupBy` 保插入序（键序 = 候选升序）；批间按 candidate_id 升序累积。**最终列表顺序与现状整表读折叠完全一致**（§3.1）。
5. **单只读事务**：`database.transactionWithResult { }`（SQLDelight DEFERRED 事务）包裹全部批次。同一连接上全部读在单个读事务内执行——SQLite 读事务自首个读起持有一致视图（WAL 下钉住快照；回滚日志模式下持共享锁），**批间库变化不会造成批间不一致**（§3.2）。`loadImportReviewRows` 本身只读、不改变任何写路径。
6. **异常语义（G6）不变**：批读抛出的任何异常沿 `loadImportReviewRows` 原抛出路径传播到用例边界映射 `Unavailable`；事务因异常回滚、不产生部分结果返回。与现状整表读失败同语义（一次读失败 = 整个读失败，绝不返回截断列表）。
7. **批大小常量**：`private const val IMPORT_REVIEW_ROWS_PAGE_SIZE = 10_000`（候选/批），经构造参数 `pageSize: Int = IMPORT_REVIEW_ROWS_PAGE_SIZE` 注入——**测试以小 pageSize 覆盖多批路径**（§4），运行时默认 10,000。61,220 候选 → 6 满批 + 1 尾批（1,220 候选）≈ 7 批；每批峰值 ≈ pageSize ×（1 + 平均重复率 160,200/61,220 ≈ 2.6）≈ 3.6 万行 × 20 列，**瞬时结构有界**。

### 2.4 内存面分析（修复后峰值）

峰值结构 = ① 单批生成行列表（≈3.6 万行，而非 22.1 万）+ ② 单批列投影列表 + ③ 单批 groupBy（≤10,000 键）+ ④ 累积结果列表（61,220 行 ImportReviewRow，与现状相同的必需输出）。① ② ③ 随批释放，④ 为线性累积。**192 MB 堆下峰值较现状下降一个数量级以上**（单批物化行数 ≈ 现状的 1/7，且不再与 ③ 的 22.1 万元素值列表共存）。④ 的大小与修复前完全一致——这正是「输出不裁剪」的边界：若设备验证显示④本身在 61k 规模仍超堆，属另一问题（UI 层渲染面），按 §10.3 另行登记裁决，不在本批静默扩大。

## 3. 语义冻结（逐项说明为何不变）

本设计对计划 §2 第 42 行「分页若采用，必须另冻结排序、快照一致性、跨页勾选、总数及组枚举完整性」逐项冻结如下：

1. **排序**：`ORDER BY candidate.candidate_id, duplicate.candidate_id` 在批查询中逐字节保留；批间按 candidate_id 升序累积（§2.3 第 3 点）。最终 `List<ImportReviewRow>` 的元素顺序与现状整表读折叠一致。`groupedRows.first()` 的选择（候选在批内的首行）不受分批影响：同一候选的行全在同批（§2.2 第 1 点），批内顺序即现状顺序。
2. **快照一致性**：全部批次在**单个只读事务**内执行（§2.3 第 5 点）。现状整表读是单语句（语句级快照）；改造后是单事务多语句（事务级快照）——一致性从语句级提升为事务级，**不弱化**。批间即使有其他连接写入，本读看到的是首个读时刻的一致视图。
3. **跨页勾选**：UI 层无「页」概念——`view.rows` 仍是完整折叠结果（61,220 行 ImportReviewRow），勾选/批量授权/整组处置读取同一完整列表；分批只是 adapter 内部实现，逐项无感知。
4. **总数**：列表完整（不再 OOM 截断）⇒ **§10.3「完整计数」在 61k 规模可达**（本设计的直接目标，D-166 第 5 条剩余缺口②的前置）。组头计数语义不变（D-166 第 2 条登记的「渲染 `view.rows` 投影不含会话内导入的新候选」是既有渲染语义，本设计不触碰渲染面）。
5. **组枚举完整性**：组枚举/整组处置读取 `view.rows` 完整投影（61,220 行），无任何组因分批缺失；批量处置的会话批量读（`loadImportDuplicateReviewsForSession`）不在本批范围、不动。
6. **折叠语义**：blocking-first `foldDuplicateStatus`（`CONFIRMED_DUPLICATE` < `DEFERRED` < `CONFIRMED_DISTINCT` < `DISMISSED_LOOKALIKE` < `REJECTED`）与重复候选集内的 fold 结果均与现状一致——重复候选按 `duplicate.candidate_id` 升序喂入，与现状同序。
7. **等价性测试基线**：`wholeLedgerDetailBaseline`（`ImportReviewTargetedReadEquivalenceTest.kt:104-107`）以 `loadImportReviewRows` 为基线——分批是内部实现，**基线语义不变，既有测试继续有效**（§4）。
8. **G6 / absent-vs-empty**：本设计只改列表读的实现形态，不触碰详情/探针/会话读；`loadImportReviewRows` 的返回契约（完整列表，永不截断）不变。

## 4. 测试计划（JVM，等价性优先）

**既有测试回归（零改动）**：`ImportReviewTargetedReadEquivalenceTest.kt` 三用例（详情定向等价、blocking-first 折叠跨读等价、存在性探针 absent-vs-empty）以 `loadImportReviewRows` 为基线，改造后语义不变，预期全绿；ledger-data 全套 jvmTest 零回归。

**新增 jvmTest（同文件或同目录新文件，沿既有纪律：真实 spine 写路径 `ExecuteImportIntake` + `ReviewImportDuplicateCandidate`、IN_MEMORY JDBC、匿名合成值、不引 mock 库）**：

1. **分批与整表逐行全量等价**：在含**多候选 / 多重复 / 无重复**混合库上（沿 `ImportReviewTargetedReadEquivalenceTest` fixture 纪律铺库），以**小 pageSize 注入**（如 2，强制多批路径）断言 `loadImportReviewRows` 分批结果与整表读取基线（以现有私有折叠形状重建的参照实现，或先取修复前语义的参照函数）**逐行全量相等**（`List<ImportReviewRow>` 整体 assertEquals；含 candidate 字段、折叠 duplicateStatus、payment profile 列——完整值相等）。
2. **页边界候选完整性**：构造候选 id 恰在批边界上的库（如候选数 = pageSize + 1，或 pageSize 精确整除候选数），断言**边界候选的全部重复行完整落入单批**（等价断言整体覆盖；另加一个结构性断言：边界候选的折叠结果与其全部重复行在结果中的一致——由全量等价断言隐含，单独列出便于评审定位）。
3. **批大小参数化**：adapter 构造注入 `pageSize`，覆盖 pageSize=1（每批单候选）、pageSize=整库候选数（单批，行为退化为现状整表读）两个端点。
4. **空/尾批终止**：空库（0 候选）→ 空列表；候选数恰为 pageSize 整数倍 → 尾批恰好满、无多余查询（可由注入的查询计数可观察面断言，或仅依赖全量等价）。
5. **G6 回归**：既有失败路径断言不因事务包裹改变（批内异常 → 整读异常传播，不返回部分列表）——若既有测试已覆盖读取失败面则保持，否则补一条最小断言。

## 5. 设备验证计划（实施后，主代理设备窗口）

前置：61k 库备份 `local/artifacts/p7-05-scale/ledger-61000-v31-backup.db`（schema v31，61,220 候选 / 160,200 重复 / 2 交易）恢复至 AVD `ul_p7_d01`（API 36，隔离 adb 端口纪律按 AGENTS.md）。

1. **OOM 消除复证**：冷启动与「刷新清单」各触发 `loadImportReviewRows`，logcat 全程零 `OutOfMemoryError`/`FATAL`/`ANR`；`dumpsys meminfo` 记录峰值（对照 D-166 的 192 MB 堆死亡基线）。
2. **完整计数可达（§10.3 剩余缺口②的前置）**：重跑 countProbe 仪器（`ImportScaleTraversalInstrumentedTest`，D-166 交付）——postScroll 读数流程在 61k 库上完整跑通，DB 权威计数 61,220 与渲染可达；修复前该流程因加载即崩不可达。
3. **列表可操作耗时**：B2 口径（进导入 Tab → 组头计数可见且首组候选行可见）计时，logcat 时间戳交叉验证（D-148 测量经济性：每场 ≥3 次取最大值；uiautomator dump 地板伪影按 D-148 B5 复核先例处理）；记录 7 批读的整体耗时与峰值内存。
4. **20k 回归**：D-148 的 20k 库场景（B2/B3/B6）复测不退化（本设计在 20k 库上为 2 批，仍在既有门槛内）——作为回归向量，不重开整套 A-PERF 门槛。
5. 验证报告与结论由主代理设备证据批落盘，不在本 worktree。

## 6. 风险与回退

1. **批大小调参**：10,000 为默认（61,220 候选 → ~7 批）。若设备峰值内存仍紧，下调（如 5,000 → ~13 批）摊薄单批物化；若批间固定开销（每批一次查询执行 + 事务内步进）可见，上调。批大小为构造参数 + 常量，**调参属实施内调整**，不改变本规格契约；调参后须重跑 §5 第 1/3 项。
2. **事务开销**：单只读 DEFERRED 事务内多语句，SQLite 读事务开销为共享锁/快照一次获取，远小于批读本身；61k 库 7 批 ≈ 7 次查询执行，无写锁竞争（只读事务不阻塞其他读者；写者仅在提交语义上与读事务共存——与现状单语句读一致）。若设备显示事务级快照引入可测差异，按 §10.3 取证，不静默改。
3. **与 D-148 裁决的关系（诚实边界）**：本设计**不推翻** D-148「列表读本体保留整账本读」及其 20k 库 0.24s 测量（耗时口径）；修订的是「无需分页」在 **61k 内存面**的推论——D-166 第 3 条已登记 D-148 未覆盖整表物化内存面、A-PERF 门槛在 ≤30k 尺度测得。20k 库上分批与整表读语义等价（单/双批即完成），不构成对既有裁决的偏离；本设计是 D-166 第 5 条承接的**修复路线**（相对「规模口径裁决」路线）。
4. **实施假设登记**：初始 `afterCandidateId = ""` 依赖 `candidate_id` 非空契约（TEXT 排序下空串最小）；keyset 推进依赖 candidate_id 字典序与 SQL ORDER BY 一致（BINARY collation，无 COLLATE 修饰，成立）。若未来 id 生成器变更该契约，须复核本设计——登记为实施假设，不构成当前风险。
5. **回退**：改动面 = Ledger.sq 新增一个只读命名查询 + adapter 单函数改造（+ 新增 jvmTest）。回退 = 还原 `loadImportReviewRows` 至整表读并移除命名查询（或保留查询不接线）；零 schema/迁移/依赖影响，回退面与风险面一致。

## 7. 交付物与范围外

**交付物**：本规格（tracked，`docs/specs/`）；实施批交付 Ledger.sq 命名查询、adapter 改造、新增 jvmTest（同批提交）；设备复测报告（主代理设备证据批）。

**范围外（如实登记，不静默扩大）**：UI/端口/用例层改动（`view.rows` 渲染面、勾选状态、组枚举）；其他列表读路径（月度查询、写事务主线程现状）；`loadImportReviewRows` 之外的内存面（结果列表④本身的渲染内存——若 61k 规模另有 UI 层 OOM，另行登记）；schema/迁移/依赖；D-166 剩余缺口（≥20k 组卡页脚/批量入口可达性，承接 D-165 第 4 条，与本批无关）。