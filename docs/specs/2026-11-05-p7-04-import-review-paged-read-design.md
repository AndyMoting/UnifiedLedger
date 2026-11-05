# P7-04 导入复核列表分页读设计（A-PERF 残余修复）

状态：approved
Revision: v1.0（2026-11-05 冻结；依据 D-293 与本轮设备/JVM 实测）

**依据：**

- 计划 `docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-PERF、§10.3；`ACC-D-PERF-01`（61k 列表读 ≤ 3 s）。
- 既有规格 `docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`（层0 统计刷新）与 `docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md`（keyset 分批消除 OOM）。
- 实测（2026-11-05，`local/artifacts/a-perf-61k-2026-11-05/`）：
  - 设备 `ul_p7_d01` 61k 夹具：冷启动 → 导入 → 待确认草稿可见 **13 / 13 / 13 s**（含 dump 轮询 2–4 s 开销）；
  - 同一夹具本机 JVM 跑既有 `ImportReview61kReadAttributionTest`：**读+折叠 2,937 / 2,993 / 3,041 ms**（61,000 候选、158,000 重复行折成 61,000 行）；
  - 13 s 窗口内进程 **100–119% CPU**、累计 CPU +11 s / 9 s 墙钟 → **CPU 受限，不是 I/O 等待**。

**背景与「为什么层0 不够」：**

层0（`PRAGMA optimize` 统计刷新，`QueryStatisticsOptimize.kt`）已落地并关闭了「查询计划选错索引」那条根因。但**即使统计正确**，读+折叠在桌面级 CPU 上就要 ~3.0 s——门槛已在「不渲染、不启动」时用尽。当前绑定的是**工作量**：`ImportReviewRowsResult.Rows(rows: List<ImportReviewRow>)` 把 61k 候选连同 158k 重复行**全量物化**成 61k 个行对象。

**目标（可检验）：**

1. 设备上 61k 夹具：冷启动 → 导入 → **首页可见 ≤ 3 s**（与 `ACC-D-PERF-01` 同口径、同夹具、同测量方法）；
2. 内容与顺序**等价**：分页读按页拼接后的 `(candidateId, status, 金额, 统计时间)` 序列与当前整表读**逐项相同**；
3. 无 N+1：单页语句数固定（沿用 `CountingSqlDriver` 断言口径）；
4. 零 ANR/OOM。

**设计（选定：keyset 分页 + 首屏优先）**

1. **端口形状**：`ImportReviewReadPort` 增加分页读：
   - 输入：`ledgerId`、`pageSize`（默认 50，与标签/商家筛选列表的既有页大小一致）、`cursor: ImportReviewCursor?`；
   - 输出：`ImportReviewRowsResult.Page(rows: List<ImportReviewRow>, nextCursor: ImportReviewCursor?)`；
   - 现有 `Rows(rows)` **保留**为「整表读」的兼容入口（供导出/对账等需要全量的调用方），但**UI 不再调用它**。
2. **游标**：`(statisticsAtEpochNanos DESC, candidateId ASC)`，与既有 keyset 分批同一排序键，避免偏移分页在并发写入下跳行/重复。
3. **折叠仍在 SQL 侧**：重复行折叠沿用既有 `foldDuplicateStatus`，但**只对小页内的行做**；每页一条主查询 + 一条计数查询（用于「共 N 条」文案），语句数固定为 2。
4. **UI**：`P503AppState.ImportReview` 改为持有 `rows`（已加载）+ `nextCursor` + `total`；列表按页追加，首屏只读第 1 页；`刷新清单` 重置游标。
5. **不变的东西**：排序键、状态折叠优先级、`IMPORT_REVIEW` 的事件/reducer 名字、失败态文案、以及「所有待确认候选都可到达」的产品语义（通过滚动追加实现，不是截断）。

**为什么不选其它方案：**

| 方案 | 否决理由 |
|---|---|
| 调大 keyset 批大小 | 批越大单页越慢，且总量不变；CPU 受限下无收益 |
| 降低每行成本（少解析/少分配） | 值得做，但省下的常数救不了「61k 行物化」这个线性项；可作为分页后的第二轮 |
| 只在 UI 侧虚拟化（LazyColumn 只渲染可见行） | 读侧仍然全量物化，13 s 的读取一分不少 |
| 加缓存 | 首次冷启动没有缓存，`ACC-D-PERF-01` 量的正是首次 |

**契约与兼容：**

- 这是**交付方式**变更，不是语义变更：同一排序、同一折叠规则、同一可达性。分页等价性由新增测试钉住。
- `Rows(rows)` 保留，避免一次性改动所有调用方；`P503App` 切到 `Page(...)` 后，`Rows(...)` 的调用方只剩非 UI 路径。
- 事件/reducer 名字不变；`P503AppState.ImportReview` 增加 `nextCursor`/`total` 字段（内部状态，不是持久化模型）。

**验收（实现批必须逐条给出读数）：**

1. 设备 61k：冷启动 → 导入 → 首页可见 **≤ 3 s**，3 轮取中位数，与 `local/artifacts/a-perf-61k-2026-11-05/` 的 13 s 同方法对比；
2. JVM 61k：`readPage` 首页 p50（本机，记录而非门限）；
3. 分页等价测试：按页拼接到末尾 == 既有整表读（逐项、含顺序）；
4. 语句数断言：单页固定 2 条；
5. 零 ANR/OOM；失败态与空态文案不变。

**风险：**

- `P503AppState.ImportReview` 字段变化会牵动既有 reducer 测试，实现批要一次性更新；
- 并发写入下游标稳定性依赖 `(statisticsAtNanos, candidateId)` 唯一性——既有 keyset 分批已经依赖它，本设计不改该前提；
- 首屏只读 1 页后，「共 N 条」需要单独计数查询，可能与列表首屏不完全同一快照（与标签筛选列表的既有取舍一致，页内一致）。

**非目标：**

- 不改 `PRAGMA optimize` 层0；
- 不改重复判定/合并语义、不改确认路径、不改导入解析；
- 不做跨页缓存或预取。

**关联：** D-293（本规格的批准与实施排期）、`docs/specs/2026-09-17-p7-04-a-perf-import-read-governance-design.md`、`docs/specs/2026-09-21-p7-04-import-review-list-oom-fix-design.md`、`local/artifacts/a-perf-61k-2026-11-05/VERDICT.md`。
