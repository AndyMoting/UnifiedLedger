# DEF-B（W6）首页流水分页与虚拟化设计规格（已批准）

状态：approved（2026-10-30 起草并批准；本文是 D-266 第 5 条 **DEF-B** 修复的**设计规格**，经两轮独立设计评审——首轮 REQUEST-CHANGES（6×P1、9×P2、11×P3）、闭包 APPROVE-WITH-FINDINGS、末轮行级复核 3 处修正后 **CONFIRMED**——并由用户在 2026-10-30 对 §8 的 Q1–Q7 逐项裁决批准（批准登记 = D-268，随本批同一 PR 提交）。本文只冻结**设计级**语义，不构成实现、迁移或发布授权；A06 的最终判决不在本文范围。）

**Revision:** approved-by-user（2026-10-30）。裁决记录：**Q1** 确认页键 A′（`(statistics_at_epoch_nanos DESC, transaction_id DESC)` + 累积页重排，P703SPEC-03 显示序不变）；**Q2** 取 (b)+(a)——分析汇总改 SQL 聚合（per-(account,currency) 合计 + Kotlin 注入目录分类，`tx.kind` 计数），回退分支改有界「最近 200 笔（创建序倒序）+ 总数」；**Q3** v1 不设固定保留上限，A06 实测 retained rows/heap，越过 192 MB 堆的 1/3 即自动落 5,000 行上限并登记对 §10.3:416 的偏离；**Q4** 授权随实施批修订 P703SPEC-03 第 2 条、P703SPEC-05 与 C04 无障碍句；**Q5** 页大小固定 200、文案「流水 · 共 N 笔」与「当前交易（最近 200 笔，按创建序；共 N 笔）」；**Q6** 接受 A06 证明责任限定（同刻并列只在 JVM 断言）；**Q7** 回收站整账读与借出仓位历史 TEXT 排序登记为残余、不并入本批。draft-3（2026-10-30）：两轮评审全部 findings 闭合，末轮行级复核修正 §2.5 位置、写语句计数与版本标签。

## 0. 范围与边界

- 本条只闭合 D-266 第 5 条的 **DEF-B**（首页流水不虚拟化 + 两支无界读）；DEF-A/DEF-C 已随 PR #181 合入 `main`（`780134b`）。
- 不改代码、不改 schema、不改冻结文档；草案给出待批契约、备选、风险、验收映射。
- **相邻残余（本批不修，登记）**：回收站读 `loadVoidedTransactionRows`（`SqlDelightLedgerCurrentStateReadAdapter.kt:464-504`，整账、无 LIMIT）；借出仓位历史按 TEXT `occurred_at` 排序（`Ledger.sq:10055-10059`）——后者与本条 §3 的序问题同源，属范围外。

## 1. 缺陷证据（`main` = `780134b` 复核）

| 事实 | 锚点 |
| --- | --- |
| 首页整屏 `Column` + `verticalScroll`，流水逐行组合 | `P503OverviewScreen.kt:81-82`、`:125-128`、`:131-133` |
| 两支：`flowRows`（entry rows）/ `state.transactions`（当前版本行），由 `flowRowsForDisplay` 切换（G1） | `P503LedgerViewPresentation.kt:133-136` |
| 当前版本行来自整账读 | `QueryLedgerCurrentState.kt:66` → 适配器 `:60-61` → `Ledger.sq:9131-9162`（effective 过滤 `:9157-9160`，`ORDER BY tx.transaction_id` `:9162`，无 LIMIT） |
| entry rows 来自整账读 + Kotlin 排序 | `QueryLedgerEntryRows.kt:16`、`:25-30` |
| 首页持整表 | `P503App.kt:536`；正常接线 `:3911`、`:4582`；保留失败面 `:4893-4905`（`interactionsEnabled=false`） |
| 冻结措辞「不声明虚拟化行为」 | P7-03 规格 `:199`（P703SPEC-05 / C04） |
| 冻结显示顺序 + 「data 查询不新增 ORDER BY 依赖」 | P7-03 规格 `:127`（P703SPEC-03） |

## 2. 必须守住的不变量

1. **R-Q06-4**：读失败/不一致必须类型化失败，不得渲染零或空列表。
2. **G1**：月度周期未重载时不得把上一周期流水当当前列表（现由 `flowRowsForDisplay` 的 null 分支承担）。
3. **显示序**：P703SPEC-03 的三键全序不变；**页键不必等于显示序**（见 §3）。
4. **可证一致性**（替代原「并发不重不漏」，见 P1-4）：翻页期间**新增插入**不重不漏——插入到游标之上的新行本次不出现，页键前缀性质保证已读部分不被重复；**修正/作废**允许某行重复或消失，直到下一次权威刷新整页重置——这是登记行为，不是缺陷。
5. **零 DDL 优先**：P7-03 系列命名查询只加，不新增索引/schema（`Ledger.sq:9164-9165`）。

### 2.5 分页治理门（计划 `:42` 要求的五项，逐项落点）

| 项 | 本设计的落点 |
| --- | --- |
| 排序 | P703SPEC-03 三键全序不变；页键与显示序分离（§3 A′） |
| 快照一致性 | §2.4：插入不重不漏；修正/作废为登记行为并由权威刷新整页重置 |
| 跨页勾选 | **N/A**：首页流水无选择/批量操作 |
| 总数 | `effectiveTransactionCount`（§4.2） |
| 组枚举完整性 | **N/A**：首页流水无分组 |

## 3. 页键与显示序（P1-1 修订：A′，保留冻结序）

**事实（精确版）**：`occurred_at` 是 TEXT（`Ledger.sq:21`），四条 `transaction_version` 写语句（`:2362`、`:2525`、`:10711`、`:10754`）都绑定 `Instant.toString()` 或其副本；全仓无数值投影。`Instant.toString()` 的小数位只有 0/3/6/9 位，**唯一字典序与时间序翻转的形态是「同秒内整秒值 vs 有小数部分的值」**（`'Z'` 0x5A > `'.'` 0x2E）；有小数位之间序一致。写入侧证据：`SqlDelightConfirmedManualIncomeCommitPort.kt:153`、`SqlDelightImportSpineStore.kt:1311`、`SqlDelightRg07Store.kt:670/698` 均绑定 `Instant.toString()`；另三条写语句（`:2525`、`:10711`、`:10754`）逐字复制该列。另：所有产品写路径的 `occurred_at == statistics_at`（`TransactionTimes.collapsed` 等），**只有修正**（`Ledger.sq:10711-10716`，statistics 取请求值、occurred 逐字复制）才让第二键真正决胜。

**三条路（A′ 推荐）**：

| 方案 | 页键 | 显示序 | 代价 |
| --- | --- | --- | --- |
| **A′（推荐）** | `(statistics_at_epoch_nanos DESC, transaction_id DESC)` | 落地后由 application 重排为 P703SPEC-03 | 每次 landing O(n log n)（可忽略）；已加载集合是**页键前缀**而非显示序前缀（同刻组被切开时补页会插行到中部，与既有先例同款）；**零 DDL、不改冻结显示序**（P703SPEC-03 第 2 条与 P703SPEC-05 仍按 §4.4 修订） |
| A | 改并列序为 tx ASC | 同上 | 需修订 P703SPEC-03；无收益（tie 方向不承载显示语义） |
| B | 加 `occurred_at` 数值投影 | 不变 | 一次 schema 迁移 + 全部写入点 + 回填预检；只为并列序，代价不成比例 |

A′ 的先例是**已上线**的：`P503Reducer.kt:568` 对累积页调用 `sortTagMerchantFilterEntriesForDisplay`；`TagMerchantFiltering.kt:237-243/266-271`。页键取 DESC/DESC 的理由是**与既有关键集先例逐字同形**（`Ledger.sq:11164/11167`），**不是**「纯反向索引扫描」——宿主 sqlite 探针显示 keyset 谓词只作过滤、计划仍从最新端反向扫并跳过已返回行，代价随页深缓增（§6.2）；tie 方向不影响显示，Q1 因此降为确认。

## 4. 提议契约（A′）

### 4.1 页读（ledger-data，EOF 追加，零 DDL）

```sql
WITH page_ids AS (
  SELECT version.transaction_id AS page_transaction_id,
         version.statistics_at_epoch_nanos AS page_nanos
  FROM transaction_version AS version
  JOIN ledger_transaction AS tx ON tx.ledger_id = version.ledger_id AND tx.transaction_id = version.transaction_id
  JOIN ledger_transaction_current_version AS cv
    ON cv.ledger_id = tx.ledger_id AND cv.transaction_id = tx.transaction_id AND cv.current_version_id = version.version_id
  JOIN transaction_effective_state AS effective
    ON effective.ledger_id = tx.ledger_id AND effective.transaction_id = tx.transaction_id AND effective.is_effective = 1
  WHERE version.ledger_id = :ledger_id
    AND (:cursor_nanos IS NULL
         OR version.statistics_at_epoch_nanos < :cursor_nanos
         OR (version.statistics_at_epoch_nanos = :cursor_nanos AND version.transaction_id < :cursor_transaction_id))
  ORDER BY version.statistics_at_epoch_nanos DESC, version.transaction_id DESC
  LIMIT :page_size
)
SELECT <ledgerEntryRowsForLedger 的列>, page_ids.page_nanos AS statistics_at_epoch_nanos
FROM page_ids
-- 外层 join 逐字照 Ledger.sq:11186-11195（含 `cv.current_version_id = version.version_id`；
-- 漏掉它会把被修正交易的全部历史版本 postings 带进本页，行倍增且不报错）
JOIN transaction_version AS version ON version.ledger_id = :ledger_id AND version.transaction_id = page_ids.page_transaction_id
JOIN ledger_transaction AS tx ...
JOIN ledger_transaction_current_version AS cv ON cv.current_version_id = version.version_id AND ...
JOIN posting_set ... JOIN posting ...
ORDER BY page_ids.page_nanos DESC, page_ids.page_transaction_id DESC, posting.posting_index;
```

- **游标必须由查询投影回来**（P1-5）：`page_ids.page_nanos AS statistics_at_epoch_nanos`（先例 `Ledger.sq:11183`、`:11209`），适配器返回 `(rows, nextCursor = (nanos, transactionId))`，**不得**从 TEXT 反解析。
- 页界绑在 `page_ids`（每交易一行），posting 不跨页（先例注释 `:11111-11112`）。
- **页大小固定 200**（先例：`P503App.kt:4664` 用 50、`SqlDelightImportReviewReadAdapter.kt:30` 用 10_000；本批取 200，Q5 确认）；`page_size` 绑定类型按 D-168 先例为 `Long`（`docs/DECISIONS.md:3253`），适配器 `.toLong()`。
- **缺投影探针前置且每次加载起点调用一次**（不是每页）：`statisticsAtProjectionMissingForLedger`（`Ledger.sq:10531-10533`）是全账本、**含被替代版本**的 `count(*)`；NULL 行永不满足页键（`:11109-11110`），故必须 fail-loud。**有意分歧**：窗口读 `ledgerEntryRowsInWindow` 允许 NULL 走文本回退（`:9248-9259`），页读不走——文本边界不可索引且本身序错。
- 端口默认实现：按全读 + 页键切片，且 nanos 换算必须全精度（`epochSeconds * 1_000_000_000L + nanosecondsOfSecond`），**不得**沿用窗口默认的毫秒截断（`LedgerCurrentStateReadPort.kt:148`，P2-4）；补「分页折叠 == 整账排序」等价测试（模型 `ImportReviewPagedReadEquivalenceTest.kt:299-340`）。

### 4.2 计数、回退分支与分析页（P1-2/P1-3/P2-3 修订）

- `effectiveTransactionCount(ledgerId)`：`count(*)`，join 形状同 `page_ids`（不 join posting），供「共 N 笔」。
- **不得给 `loadCurrentRows` 加 LIMIT**：它的第二个消费方是分析页（`P503App.kt:4002-4003` → `P503TabShell.kt:176` → `SummarizeLedgerActivity.kt:48-53` 直接遍历 `currentState.transactions` 出 totalTransactionCount/countByKind/totals）。
- 回退分支（月度未加载）的「最近 200」**方向须先纠正**（P1-3）：现 `ORDER BY tx.transaction_id ASC`（`:9162`）是 UUIDv7 创建序，`LIMIT` 会拿到**最早** 200。方案：
  - (a) 新命名查询 `recentCurrentRowsForLedger`（`ORDER BY tx.transaction_id DESC LIMIT :limit`，创建序倒序）+ `currentVersionCount`；`LedgerCurrentState` 增 `transactionTotal`，文案「当前交易（最近 200 笔，按创建序；共 N 笔）」。**连带可见变化**：回退分支的可见顺序由创建序升序变为降序且限 200 笔，须按 §6.10 登记。
  - (b) 分析页改为 SQL 聚合（`ledgerActivitySummary`：按 kind 计数 + 逐币种收支合计），与 DEF-A 的 `accountBalanceSums` 同向，随后 `loadCurrentRows` 可退役。
  - **推荐 (b)+(a)**：(b) 保留分析页完整口径并顺手消掉 50k 分析页的整表物化（登记为同类风险收敛）；(a) 让回退展示有界。若只想收窄本批，(a) 单独也成立，但分析页的整表读须登记残余。
  - **(b) 的两条硬约束**（照 `currentAccountBalanceSums` `Ledger.sq:9348-9373` 的形状）：SQL 只回 per-(account, currency) 合计，分类由 Kotlin 用**注入的** `LedgerCatalog` 施加，缺账户时照 `SummarizeLedgerActivity.kt:55-59` fail-closed —— 直接 `JOIN catalog_account` 会在映射缺失时静默丢行、金额凭空变少；kind 计数必须取 `tx.kind`（`:9135`），**不得**用 entry rows 的 `COALESCE(tx.canonical_kind, tx.kind)`（`:9178`），否则 LEND/COLLECT/REFUND 等带 canonical_kind 的账本 `countByKind` 会变。
  - **验收断言**：截断前后 `LedgerActivitySummary` 完全相同。

### 4.3 状态与 UI（P2-5）

- 页状态放进 `OverviewEmpty`（沿用 `P503AppState.kt:659-683` 的 `TagMerchantFilter` 状态先例）：`loadedEntries`、`hasMore`、`nextCursor`、`pageFailure`、`pageRequestToken`。
- 自动保留：13 个内嵌 `overview` 的变体（`P503AppState.kt:361/382/…/660`）与 `InfrastructureFailure.monthlyOverview`（`:337`）在 Back/失败恢复时带回已加载页与游标；`OverviewEmpty` 76 处构造点依赖默认值（零改构造点）。
- **F1 保留失败面**（`P503App.kt:4893-4905`）的 `entryRows` 形参要改成页状态载体，保证失败面仍能渲染已加载行（R-Q06-4：失败不清空）。
- 单飞与失败：`Loading` 期间**吸收页字段、事件照常返回**（D-223 的措辞更正，P3-10）；失败保留已加载行 + 页尾失败条 + 重试。
- UI：`LazyColumn`，稳定 `key = transactionId`；header（账本名/入口/月卡/月选择）、items（`LedgerEntryFlowRow` 原样）、footer（加载中/失败重试/末页/上限说明）、tail（分隔 + 余额）。回退分支同样虚拟化（`CurrentTransactionRow` 原样，`P503OverviewScreen.kt:191-212`）。
- 月重载：重置到第一页并重排；G1 的 null 分支不变。

### 4.4 冻结文档修订清单（Q4）

1. **P703SPEC-03 第 2 条**（规格 `:127`）：「data 查询不新增 ORDER BY 依赖」——页读必然带 ORDER BY。建议改为「显示序由 application 纯函数施加；分页读以同一全序的页键在 data 层定界，仅用于取页」。
2. **P703SPEC-05 / C04**（`:199`）：「不声明虚拟化行为」→「首页流水为虚拟化列表：仅组合可见行」。
3. **C04 无障碍句**：可达性从「长列表数值可达」改为「已加载行可达；跨页需滚动或加载更多」，A06 巡检加「TalkBack 逐页到达末页」。

### 4.5 桌面端

共享 `P503App`（`desktop-app/.../Main.kt:344`；`:164` 只是 import），虚拟化与分页同源生效；B3 补桌面组合测试。

## 5. 备选（含被否理由）

| 方案 | 结论 |
| --- | --- |
| **A′ 页键 + 累积重排（本方案）** | 保冻结序、零 DDL，推荐 |
| A″ 按统计瞬间整组分页 | 保序零重排，但单瞬间行数无上界（批量导入可共享同一时刻），页界失控 |
| A 改并列序 | 需改冻结契约且无收益，否 |
| B 加 occurred 数值投影 | 仅为并列序做迁移，代价不成比例；若将来其它面需要 occurred 序，另立迁移批 |
| 只截 200 不分页 | 砍掉「看全部历史」，产品退化 |
| `LazyColumn` + 整表在内存 | 组合节点消失但整账读/物化仍在，违背 DEF-A 方向 |
| offset 分页 | 并发写入下重复/漏行，深翻页线性代价，否 |

## 6. 风险与缓解

1. **页键前缀 ≠ 显示序前缀**：同刻组被切开时补页插行到中部（已登记为 A′ 的固有行为；测试钉住「累积集合 == 全量按显示序排序」）。
2. **页读代价（宿主实测，非设备证据）**：50k 交易 / 每笔 2 posting / 页 200 的最小忠实 schema 上，计划为 `SEARCH version USING INDEX transaction_version_statistics_at_range_idx (ledger_id=?)`——**keyset 谓词只作过滤、不构成索引范围约束**；每页从最新端反向扫并跳过已返回行：页 1 2.8 ms、页 100 ≈4.5 ms、页 250 10.2 ms，250 页累计 1.46 s；同数据集 `count(*)` = 70 ms。行值比较写法 `(nanos, tx_id) < (?, ?)` 在 sqlite 3.49 上计划不变（不再尝试）。缓解与验收：页键 DESC/DESC 保证页键**无排序**（只有外层 `posting_index` 的 right-part 排序）；**测晚期页**（≈页 200/250）与全量累计，设备按同法复测；晚期页超预算时再评估索引（依据 P703SPEC-03 `:127` 与 R-6 `:210`，另立迁移）。
3. **分页期间修正**：重复/消失按 §2.4 登记；由下一次权威刷新整页重置消除；测试拆「插入」与「修正」两条。
4. **计数成本**：`count(*)` 在 50k 上的一次 effective join 扫描；A06 单列测量。
5. **分析页口径**：若选 (a) 单独，则分析页仍整表读——登记残余；选 (b) 则用「截断前后 summary 相同」钉住。
6. **状态面扩大**：`OverviewEmpty` 13 个内嵌变体 + 76 处构造点；依赖默认值 + 定向测试。
7. **缺投影**：探针前置、fail-loud、不走文本回退。
8. **无障碍**：虚拟化后只有已加载行可达（C04 修订 + A06 TalkBack 逐页巡检）。
9. **桌面/布局**：header/tail 迁移后的滚动位置与「月卡在顶部」观感复验。
10. **回退分支可见顺序变化**：创建序升序 → 降序且限 200 笔（§4.2(a)），文案与显示测试同步；不视为回归，属登记的产品变化。

## 7. 验收映射

- **§10.3 整链**（`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md:416`）：冷启动→首页可操作→列表可操作→滚动→**首/末项可达**（Q3(a) 直接成立；Q3(b)/(c) 经「跳到最早」入口成立）；20k 与 50k 库上无 ANR/OOM。
- **A06（批 3）**：2k 对照 + 20k + 50k + A6-2/3/4；首屏可操作时间、**首/次页与晚期页（≈页 200/250）读耗时、全量滚动累计**、count 耗时、retained rows、heap 峰值单列登记；页大小固定 200（§4.1）。
- **A06 的证明责任限定**（P3-6）：A06 数据面统计时间每 7 分钟一笔（D-266 第 2 条）⇒ **零同刻并列**，既不能验证 tie 序也不能验证「txId ≈ 创建序」；tie/同刻跨页只在 JVM 断言，A06 只证规模、内存与首/末项可达。
- **新增测试**：页读 JVM（空账本、恰好整页、同刻并列跨页、累积 == 全量排序、插入不重不漏、修正重复/消失、缺投影 fail-loud、EXPLAIN 无整集排序）；计数精确；状态机（单飞、失败保留、重试、月重载重置）；UI 组合（项数、header/footer/tail、页尾状态）；桌面组合；分析摘要「截断前后相同」。
- **不声称**：A06 最终判决、P7-09 完成度、50k 全程滚动可操作性（须实测）。

## 8. 已裁决（R-268-1..7，2026-10-30）

| 编号 | 裁决 | 内容 |
| --- | --- | --- |
| R-268-1 | Q1 | 页键 A′：`(statistics_at_epoch_nanos DESC, transaction_id DESC)`；累积页由 application 重排为 P703SPEC-03 显示序；DESC/DESC 与既有关键集先例逐字同形，tie 方向不承载显示语义 |
| R-268-2 | Q2 | (b)+(a)：`ledgerActivityAccountSums`（per-(account,currency) 合计）+ `ledgerActivityKindCounts`（`tx.kind`）替代分析页整表读；回退分支 `recentCurrentRowsForLedger`（`transaction_id DESC LIMIT`）+ `currentVersionCount`；验收断言「截断前后 `LedgerActivitySummary` 相同」 |
| R-268-3 | Q3 | v1 不设固定保留上限（按需加载）；A06 实测 retained rows/heap，越过 192 MB 堆的 1/3 自动落 5,000 行上限并登记对 §10.3:416 的偏离；预期量级 20k ≈ 8–12 MB、50k ≈ 20–30 MB（粗估） |
| R-268-4 | Q4 | 授权修订三处冻结文档：P703SPEC-03 第 2 条（data 查询 ORDER BY 只用于取页）、P703SPEC-05（虚拟化列表声明）、C04 无障碍可达性句（已加载行可达 + 跨页需加载更多） |
| R-268-5 | Q5 | 页大小固定 200；文案「流水 · 共 N 笔」与「当前交易（最近 200 笔，按创建序；共 N 笔）」 |
| R-268-6 | Q6 | 接受 A06 证明责任限定：A06 数据面零同刻并列，tie/同刻跨页只在 JVM 断言；A06 只证规模、内存与首/末项可达 |
| R-268-7 | Q7 | 回收站 `loadVoidedTransactionRows` 整账读、借出仓位历史 TEXT `occurred_at` 排序登记为残余，不并入本批 |

## 9. 实施批次（批准后）

1. **B1 读取层**：`ledgerFlowPage`（含 page_nanos 投影与游标）、`effectiveTransactionCount`、回退读/计数（按 Q2）、探针前置；端口/适配器；JVM 页读、计数、等价与 EXPLAIN 测试。
2. **B2 状态与 UI**：`OverviewEmpty` 页状态（单飞/失败/重载重置）、`LazyColumn`（header/items/footer/tail）、回退分支、F1 失败面形参；reducer/组合测试；若 Q2(b)：分析聚合与「截断前后相同」测试。
3. **B3 冻结文档与证据**：P703SPEC-03/05 与 C04 修订、D-268 登记、桌面组合测试、instrumented 触底加载；随后进入批 3（A06 重跑）。

## 10. 锚点汇总

- 缺陷面：`P503OverviewScreen.kt:81-82/125-128/131-133/191-212`；`P503LedgerViewPresentation.kt:133-136`；`QueryLedgerCurrentState.kt:66`；`SqlDelightLedgerCurrentStateReadAdapter.kt:60-61`；`Ledger.sq:9131-9162`；`QueryLedgerEntryRows.kt:16/25-30`；`P503App.kt:536/3911/4582/4893-4905/4002-4003`；`P503TabShell.kt:176`；`SummarizeLedgerActivity.kt:48-53`。
- 先例：`Ledger.sq:11113-11209`（keyset CTE；`:11164/11167` DESC/DESC；`:11183/:11209` page_nanos 投影；`:11169` CTE 界）；`P503Reducer.kt:568`、`TagMerchantFiltering.kt:266-271`（累积重排）；`ImportReviewPagedReadEquivalenceTest.kt:299-340`（等价模型）；D-168（`docs/DECISIONS.md:3249/3253`）、D-223（单飞 token）。
- 约束：`Ledger.sq:60-61`（索引）、`:10531-10533`（探针）、`:10711-10716`（修正挪 statistics）、`:9248-9259`（窗口文本回退，有意不照搬）；P7-03 规格 `:127`/`:199`/`:210`；计划 `:42`（五项治理门，落点见 §2.5）/`:416`；D-266 第 2/5/7 条。
- 写入侧：`SqlDelightConfirmedManualIncomeCommitPort.kt:153`、`SqlDelightImportSpineStore.kt:1311`、`SqlDelightRg07Store.kt:670/698`（`Instant.toString()` 绑定）。