# P7-03 看账实施规格（设计门）

状态：approved（2026-09-13 冻结；独立规格评审 REQUEST-CHANGES（无 P0/P1）→ draft-2/draft-3 修正闭环 → DELTA CLOSURE APPROVE）。

**Revision:** draft-3（2026-09-13）。draft-3 = 冻结前修正批：**P703SPEC-11（P2，阻断冻结）**——C03 镜像向量按 `ACCOUNTING_RULES.md:80`/`:152` 重写（镜像证据追加到既有经济事件、不创建第二笔交易；受影响腿经 §3.2.1(b) 获得对账资格，腿状态值与交易级 rollup 除对账操作外不受影响；创建入口显示不变），并新增「不同 raw identity 的合法相似记录」独立向量（计划 D03，多笔独立交易展示测试归位其正确名称）；另登记 3 项 P3 注记（§6.2 触发 (d) 措辞修正、封闭触发集三残余边界各一句）。draft-2 = 独立规格评审 REQUEST-CHANGES（**无 P0/P1**，4×P2 + 6×P3）单批修正闭环：P703SPEC-01 枚举计数更正 16、P703SPEC-02 结余三值入统计契约、P703SPEC-03 流水排序契约、P703SPEC-04 月度重请求触发冻结、P703SPEC-05 C03/C04 增补向量、P703SPEC-06 引用精确化、P703SPEC-07 RG-12 第二原地 metadata 写入方披露、P703SPEC-08 储值/预付排除依据补引、P703SPEC-09 无分类腿呈现、P703SPEC-10 报表时区常量化与月份可选域。draft-1 由主代理在用户常设授权「除不 push 外默认采用推荐方案」下裁决 Q06/Q07（standing authorization），本稿将裁决逐条冻结为 R-Q06-1..4 / R-Q07-1..4；本规格经独立规格评审修正闭环并 **DELTA CLOSURE APPROVE** 冻结（2026-09-13）。承接登记链：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §5（P7-03 看账，:102-131，含 §5.2 执行拆分与 §5.3 验收 C01–C04）、§7（Q06 :180 / Q07 :181 最迟决定点）、D06（:167，隐私注记）、§8.1（P7-03 回归锚点，:202）；该计划基线 `main` = `a710c6ce370b1eee8cd00ff8fe662377b930d795`，本规格工作基线 = 分支 `UL-p7-03` @ `b56913d`（P7-02 merge，D-144），当前 schema = **v29**（`28.sqm` 由 P7-02 引入；`LedgerDatabaseMigrationTest.kt:307` 断言 `assertEquals(29, ...)`）。tracked 行号为工作基线 `b56913d` 实读行号；`.local.md` 以主 checkout 为准、只读。

**Scope:** 冻结「看账」实施批（P7-03）的契约面：ledger-scoped 当前版本读模型扩展（真实 kind、统计时间、备注、创建入口）、月度汇总投影（月界/时区/桶键）、普通收支分类枚举、趋势与分类下钻、交易详情与多腿对账资格投影（只读）、失败码族及可达性、UI 状态机/事件矩阵、C01–C04 验收矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成；引用均带 file:line；不粘大段产品代码。**本批零账务写入路径改动、零正式交易产生**；本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 worktree 基线 `b56913d` 的实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **阶段计划**：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:102-131`（P7-03 目标、§5.1 读模型与统计契约、§5.2 执行拆分 A–D、§5.3 验收 C01–C04）、`:180`（Q06：有效统计时间取值及 metadata/版本优先级；建议上海自然月、普通收入减净支出、特殊项隔离）、`:181`（Q07：近 12 月趋势、一级到二级下钻、正支出与退款分图/列表、多腿对账状态优先级及零/负图呈现）、`:167`（D06：脱敏诊断不泄露原文件名/URI/整行/个人标识/底层异常）、`:202`（P7-03 回归锚点 = `SummarizeLedgerActivityTest`、`QueryLedgerCurrentStateTest`、`SqlDelightLedgerCurrentStateReadAdapterTest`）。
- **会计规则**：`docs/ACCOUNTING_RULES.md:42-50`（普通支出/收入核心字段与方向）、`:54-60`（转账：本金不计对外收支、手续费与本金分离）、`:62-82`（借贷：`:66` 借出本金不计消费/收支/净资产、`:68` 收回拆分与普通利息收入、`:70` 本金不超未结）、`:140-158`（退款：`:144` 退款交易分录形状、`:146` 报表按各自真实期间统计、到账期消费为负、退款不得记入普通收入、不回写原消费日期）、`:200-207`（时间规则：`:204` 导入默认来源支付时间为统计时间、`:205` 用户可修改统计时间且报表按新时间归属、`:206` 手工账目可修改统计时间并保留版本历史）、`:217-237`（余额调整：`:225` 调整只改变目标资产余额与净资产，普通收入/普通费用/消费/预算/分类统计/现金流全部为零）、`:164-190`（储值：`:176` 赠送权益收入单列、`:178` `credited = paid + bonus`、`:182` 储值充值/消费的报告口径）、`:239-245`（对账规则：`:241` 对账发生在真实资产或负债 Posting 级、交易列表显示由相关分录汇总出的状态、`:243` 同一交易可部分核对且对账状态不改余额）、`:86-92`（分类规则：`:91` 改名后历史账目显示当前名并保留名称变更历史）、`:255-261`（P7-01 目录准入）。
- **产品需求**：`docs/PRODUCT_REQUIREMENTS.md:9-13`（日常记账：手工记录四类、内部转账与对外收支明确区分）、`:21-25`（对账审计：每条实际账户变动独立对账、同一交易可只完成部分核验、解释已核对/未核对/差异原因）、`:42`（本地离线可完成余额、报表和对账）。
- **决定（已确认/已批准）**：D-119/D-120（ledger-scoped current-version 读边界、facade/组合根装配、snapshot-aware resolver）、D-122（三 Tab + 分析 Tab 消费纯派生 `SummarizeLedgerActivity`，零 DDL 零新依赖）、D-125/D-131/D-138/D-139/D-140（编辑流返回/取消、时间选择器与键入保留语义——本批读路径必须原样保留）、D-143（P7-01 目录持久化与名称投影先例）、D-144（P7-02 类型化录入、note 贯通、`28.sqm` v28→v29）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表冻结（含 `rg03_transfer_posting_semantic`、`rg03_posting_reconciliation`）；golden fixtures/expected 不改；导入链（`import_*` 共享 spine）语义与写入路径不变；P7-04 不在本批；本批不新增迁移版本（schema 停留 v29）。

## 1. 目标与范围

### 1.1 目标

- 扩展 ledger-scoped 当前版本读模型：有效业务 kind（`COALESCE(canonical_kind, kind)`）、`occurred_at` 与 `statistics_at` 双时间、当前版本 `note`，使 LEND/COLLECT/REFUND_RECEIPT 等不再被兼容列误读为 EXPENSE（§2.1）。
- 统一月度投影：首页月卡、分类合计、趋势共用同一月度结果（计划 §5.1）；月界、时区、「本月」与桶归属键按 R-Q06-2 冻结。
- 普通收支分类冻结枚举（R-Q06-3 + §3.1.1 表）：本金互转、借贷本金、储值/余额调整等特殊 kind 不入普通收支；手续费与实收利息按规则计入；退款 = 收款期负支出。
- 交易详情：金额、双时间、账户、分类（当前名）、备注、创建入口（导入链溯源 / 手工确认）与多腿对账资格投影（只读，R-Q07-4）。
- 趋势与分类：近 12 个自然月趋势（空月显式零）、一级汇总下钻二级、退款与正向支出分开呈现（R-Q07-1..3）。
- 失败不伪装：读路径异常/溢出类型化失败，不以零或空月掩盖（R-Q06-4）。

### 1.2 范围（冻结）

**范围内：** `ledger-data` 新增命名查询（零 DDL，§5）；`ledger-application` 读模型端口扩展、`QueryMonthlyActivity`/`QueryTransactionDetail` 拟议用例、月度桶与分类纯函数、创建入口与对账腿投影装配；`app-ui` ANALYSIS/HOME 月度呈现、分类下钻、趋势、详情导航（只读）与 TalkBack 数值；两端组合根接线；C01–C04。

**范围外（本批明确不做，逐项冻结）：**

- **不做编辑/修正 UI**：详情页只读；版本替代、备注修改（`copyCurrentVersionWithNewNote`，`Ledger.sq:2111-2125`）、分类重指均不做，留待 P7-05。
- **不做对账操作**：对账资格投影与状态汇总**只读**；不创建/不修改 `posting_reconciliation`/`evidence_link`/对账请求；不新增对账写入语义（`ACCOUNTING_RULES.md:243`：对账状态不改余额）。
- **不做多账本**：全部查询按单一 `ledgerId` 限定（沿 D-119 边界）。
- **不改 rgXX 竖井与 golden**：不读改任何 `rgXX_` 表的写入路径；golden fixtures/expected 零改动。
- **导入链语义不变**：`import_*` spine 表与确认链零写入改动；本批只新增「transaction → import_confirmation」反向读查询。
- **不做**：导出/分享、搜索/全文检索、预算执行、跨币种汇总（沿用「不同币种不得汇总」，D-120）、多报表口径切换、收入/支出以外维度的自定义报表、日期选择器或解析器改动（`ParseManualExpenseOccurredAt.kt:150` / `OccurredAtPicker.kt:18` 的 Asia/Shanghai 冻结原样保留）。
- 真实金额/时间/锚点注册值与个人数据不入文；示例全部匿名合成（含 D06 脱敏边界）。

## 2. 现状与差距（file:line）

1. **当前版本投影缺失真实 kind（C02 根因）**：`CurrentVersionRow` 仅 5 字段（transactionId/currentVersionId/kind/occurredAt/postings，`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/LedgerCurrentStateReadPort.kt:17-23`）；`currentVersionRowsForLedger` 直接选 `tx.kind`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:8712-8739`，`tx.kind` 于 `:8716`），而 `insertTransaction` 把新 kind 降级写入 `kind='EXPENSE'` + `canonical_kind=<真实>`（`Ledger.sq:1945-1984`，legacy `kind` CHECK 限五值 `OPENING_BALANCE/EXPENSE/INCOME/ACCOUNT_TRANSFER/CREDIT_REPAYMENT`，`Ledger.sq:1-7`）。**后果：LEND/COLLECT/REFUND_RECEIPT/BALANCE_ADJUSTMENT/STORED_VALUE_*/PREPAID_* 在现有读路径全部读回 EXPENSE。** `COALESCE(canonical_kind, kind)` 模式已存在于 oracle/P408 查询（`Ledger.sq:2438`、`:8623`、`:8672`、`:8696`、`:8703`），产品读查询未采用。
2. **投影缺统计时间与备注**：`transaction_version` 含 `occurred_at/statistics_at/effective_at TEXT NOT NULL` 与可空 `note`（`Ledger.sq:15-34`，`:21-24`）；note 由 P7-02 各 commit port 写入，备注修正经 `copyCurrentVersionWithNewNote` 追加新版本（`Ledger.sq:2111-2125`）；`statistics_at` 逐版本经 `insertTransactionVersion` 写入（`Ledger.sq:1996-2007`）。现有 `CurrentVersionRow`/`LedgerCurrentState` 均不投影 statistics_at 与 note。
3. **统计时间双存储面（Q06 依据）**：版本列 `transaction_version.statistics_at` 逐版本 NOT NULL（`:22`）；`formal_transaction_metadata.statistics_at_text NOT NULL`（`19.sqm:19-27`，`:23`）**仅由 RG-08..12 store 写入**；手工/导入链（manual + import-spine）写零 metadata 行；手工链 `TransactionTimes.collapsed(occurredAt)` 使 statistics==occurred==effective（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/Values.kt:60-73`）；RG-11 StatisticsAt 修正**既追加新版本又原地更新 metadata**（`Rg11Operations.kt:934-946`；`SqlDelightRg11Store.kt:639-652`），静止态两者一致但 metadata 非全量存在。
4. **分析面仅全期汇总（C01 根因）**：`SummarizeLedgerActivity` 无月份/时区参数、按账户 kind（EXPENSE 账户分录入支出、INCOME 账户分录取反入收入）聚合当前版本（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/SummarizeLedgerActivity.kt:27-100`，checked 溢出 fail-closed `:103-112`）；ANALYSIS Tab 整页渲染该全期汇总（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503TabShell.kt:132-164`）；Tab 集 = `HOME/ACCOUNTS/ANALYSIS`（`P503AppState.kt:149-153`）；HOME 流水行**不可点击**（`P503OverviewScreen.kt:28-79`，`CurrentTransactionRow` 仅 `Column+Text` 无 click 修饰），无详情页；返回通道沿用 `backHandler(enabled, onBack)` + 双击守卫（`P503App.kt:116`、`:204-208`、`:1067-1084`）。
5. **对账读取面现状（Q07 依据）**：`selectP408ReconciliationReport` 硬门 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'`（`Ledger.sq:8658-8685`，`:8672`），行级状态 `COALESCE(reconciliation.status,'PENDING')`，active 证据链以最新 history `state='active'` 判定（`:8677-8682`）；`posting_reconciliation` 有 `UNIQUE(ledger_id, posting_id)` 与 status CHECK（PENDING/PARTIAL/DIFFERENCE/MISSING/CHECKED）（`Ledger.sq:8062-8071`）；产品状态枚举含中文标签（`P408Reconciliation.kt:12-26`：待对账/部分匹配/有差异/待补资料/已核对）；**手工 P7-02 链写零对账行**；对账资格语义唯一落点是 `rg03_transfer_posting_semantic.reconciliation_eligible`（本金腿=1、费用腿=0，`Ledger.sq:497-506`，唯一写入方 `SqlDelightRg03TransferStore.kt:1094` = RG-03 回放竖井）；**无交易级 rollup**；`readReconciliationReport` 无生产调用方（端口 `P408Reconciliation.kt:161`、实现 `SqlDelightP408ReconciliationStore.kt:332`）。
6. **创建入口无反向溯源（C03 缺口）**：`import_confirmation` 有 `operation_class CHECK IN ('creation')` 与 `UNIQUE(ledger_id, candidate_id)`（`Ledger.sq:7767-7783`，`:7774`/`:7778`），但**不存在 transaction → confirmation 反向查询**（现有唯一读取 `selectImportConfirmationByRequest` 按 request_id，`Ledger.sq:8443-8447`）；手工四链 request 表均带 `confirmation_marker CHECK = 'explicit_manual_save'`（`Ledger.sq:75`、`:97`、`:130`、`:154`、`:246`、`:390-391`、`:524`、`:539`），receipt 表以 `transaction_id TEXT NOT NULL UNIQUE` 关联交易（`Ledger.sq:79-91` 等）。
7. **分类名投影缺失（R-Q07-2 缺口）**：`catalog_category` 无 name 列（`Ledger.sq:314-326`），当前名经 `catalog_name_history`（`Ledger.sq:329-344`，受控 guard trigger）与既有查询解析（`:9132-9207`，含 `selectCatalogCurrentNamesByKind` `:9207-9208`）；`LedgerCurrentState` 仅有 `accountNames`（`QueryLedgerCurrentState.kt:30-42`），**无 categoryNames**。
8. **时区与时钟能力就绪（Q06 依据）**：解析/选择器冻结 Asia/Shanghai（`ParseManualExpenseOccurredAt.kt:150`、`OccurredAtPicker.kt:18`）；`LedgerClock` fun-interface `now(): Instant`（`LedgerClock.kt:12-14`），reducer 注入先例（`P503Reducer.kt:60`、读取 `:174`）；`kotlinx-datetime 0.8.0` 已在 `ledger-application` 与 `app-ui` 可用（`ledger-application/build.gradle.kts:39`、`app-ui/build.gradle.kts:49`）。
9. **失败族先例（R-Q06-4 依据）**：`LedgerCurrentStateResult = Success / InvalidState / Unavailable`（`QueryLedgerCurrentState.kt:44-53`）——读端口异常 → `Unavailable`，目录/分录一致性失败与溢出 → `InvalidState`，UI 已有 `InfrastructureFailure(READ)` 承接（`P503AppState.kt:118` 起）。
10. **现状推论**：P7-03 自由度边界 = 仅新增命名查询 + application 纯函数 + UI 只读呈现；硬约束 = 零 DDL（schema 停留 v29）、零账务写入路径改动、不改 `rgXX_`/golden/导入链语义、不破坏 §8 R-7 列出的 22 个既有测试锚。

## 3. 裁决（R-Q06 / R-Q07 逐条冻结）

> 以下裁决由主代理在用户常设授权下作出，本规格逐字冻结；实施批必须逐条落入，不得自行更改语义。登记解读（如 3.1 R-Q06-1 括注、3.2 R-Q07-4 资格判定）为规格对裁决的操作化，随本规格一并送评审确认。

### 3.1 Q06 有效统计时间与报表口径（P7-03.A/B）

- **R-Q06-1 有效统计时间**：产品读模型一律取当前版本的 `transaction_version.statistics_at`（逐版本 NOT NULL、修正经追加新版本天然生效）；`formal_transaction_metadata.statistics_at_text` 不被产品读模型读取（登记解读：仅 RG-08..12 存在该行、原地更新与版本追加在静止态一致，但非全量存在，统一读版本列避免按交易特判）。`occurred_at` 与 `statistics_at` 均入投影（详情分别展示）。
- **R-Q06-2 报表口径**：报表时区冻结 `Asia/Shanghai`；月界 = `[当地当月1日00:00, 次月1日00:00)`；「本月」由注入 `LedgerClock` 的当次 `now()` 在该时区解析；桶归属键 = **统计时间**（非 `occurred_at`）。
- **R-Q06-3 普通收支分类**：按 canonical_kind（读 `COALESCE(canonical_kind, kind)`）+ 分录账户类型分类；本金互转（ACCOUNT_TRANSFER 全部腿）、借贷本金（LEND/COLLECT 的本金腿）、储值/余额调整等特殊 kind 不入普通收支；手续费、实收利息按 `ACCOUNTING_RULES.md` 计入；退款 = 收款期负支出。精确枚举表由规格以 `ACCOUNTING_RULES.md` 行号为据冻结（§3.1.1），且普通收支总额必须与既有 `SummarizeLedgerActivity` 全期口径在「无特殊 kind」账本上可核对一致（核对锚定义见 §3.1.2）。
- **R-Q06-4 失败不伪装**：任何读路径异常/溢出 → 类型化失败（沿用 `Unavailable`/`InvalidState` 族，`QueryLedgerCurrentState.kt:44-53` 先例），不以零或空月掩盖。

#### 3.1.1 普通收支分类枚举表（R-Q06-3 冻结实现）

有效 kind = `COALESCE(canonical_kind, kind)`（16 值枚举 `FormalLedger.kt:41-58`）。普通收支仅由「有效 kind ∈ O = {EXPENSE, INCOME, ACCOUNT_TRANSFER, LEND, COLLECT, REFUND_RECEIPT}」的当前版本贡献，且逐分录按**分录账户 kind** 门控（EXPENSE 账户分录 → 普通支出，账本符号原样；INCOME 账户分录 → 普通收入，取反；ASSET/LIABILITY/EQUITY 账户分录 → 恒零）。本金腿全落在资产/负债账户，故被账户门控自然排除；费用腿/利息腿落在费用/收入账户，故被自然计入——与 `SummarizeLedgerActivity` 的账户 kind 门控同构（`SummarizeLedgerActivity.kt:61-81`）。

| 有效 kind | 普通收入 | 普通支出 | 规则依据 |
| --- | --- | --- | --- |
| `EXPENSE` | 0 | 费用账户分录（账本符号） | `ACCOUNTING_RULES.md:42-44` |
| `INCOME` | 收入账户分录取反 | 0 | `:46-50` |
| `ACCOUNT_TRANSFER` | 0（本金腿为资产账户） | 费用腿费用账户分录（手续费） | `:54-60`（`:56` 示例：本金不计、手续费为费用） |
| `LEND` | 0（本金腿资产↔应收） | 0 | `:66` |
| `COLLECT` | 利息腿收入账户分录取反（实收利息） | 0 | `:68` |
| `REFUND_RECEIPT` | **0**（退款不得记入普通收入） | 原费用账户负分录 → **负支出**（收款期） | `:144-148`（`:146` 报表按各自真实期间、不回写原消费日期） |
| `BALANCE_ADJUSTMENT` / `BALANCE_ADJUSTMENT_REVERSAL` | 0 | 0（普通收入、普通费用、消费、分类统计全部为零） | `:225` |
| `STORED_VALUE_RECHARGE` | 0（赠送为单列特殊非现金赠送权益收益，不入普通收入） | 0 | `:176`、`:178`、`:182` |
| `STORED_VALUE_SPEND` | 0 | 0（整 kind 排除，依据 `ACCOUNTING_RULES.md:299` 看账读模型条款；其消费与费用分类增加属储值口径，按 `:182` 与普通收支分列） | `:182`、`:299` |
| `STORED_VALUE_EXPIRY_LOSS` | 0 | 0 | `:184` |
| `STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT` | 0 | 0 | `:186` |
| `PREPAID_PURCHASE` / `PREPAID_RECOGNITION` | 0 | 0（依据 `:299` 整 kind 排除；分摊消费口径按 `:196-198` 独立保留） | `:196-198`、`:299` |
| `CREDIT_REPAYMENT`（legacy，无产品写入路径） | 0 | 0（还款不得重复原消费） | `:104` |
| `OPENING_BALANCE`（legacy，无产品写入路径） | 0 | 0 | 无对应产品规则；按特殊 kind 排除 |

补充冻结：分类归属经 posting `account_id` → `catalog_category.posting_account_id` 反解叶子分类（`Ledger.sq:314-326`），显示名取 `catalog_name_history` CURRENT（R-Q07-2）；同一交易内逐币种分别聚合，禁止跨币种相加（D-120）。

#### 3.1.2 与 `SummarizeLedgerActivity` 的核对锚（R-Q06-3 一致性要求）

「无特殊 kind」账本 ≜ 当前版本有效 kind 集合 ⊆ `O`（即不含 §3.1.1 表中 `REFUND_RECEIPT` 以外的任何排除 kind——`REFUND_RECEIPT` 本身在 `O` 内）。在此类账本上：**Σ(全部月份普通收入) == `SummarizeLedgerActivity` 全期 income、Σ(全部月份普通支出) == 全期 expense（逐币种）**（`SummarizeLedgerActivity.kt:48-100`）。含特殊 kind 的账本上两者**允许并预期不一致**（特殊 kind 分录落在费用/收入账户但被 kind 排除；如 `STORED_VALUE_SPEND` 的费用分类分录、储值赠送的收入账户分录），该差异为 R-Q06-3 的既定结果并登记于 §8 R-5；分析 Tab 的全期汇总展示在 P7-03 保持现状（D-122 面），不因月度口径改写。

### 3.2 Q07 呈现与对账投影（P7-03.C/D）

- **R-Q07-1 趋势**：近 12 个自然月（含本月），旧→新排序；空月显式零值呈现。
- **R-Q07-2 分类**：一级汇总下钻二级（两级，与 P7-01 目录层级一致）；停用/改名分类按当前名显示且历史可追溯（`catalog_name_history`）。
- **R-Q07-3 退款呈现**：正向支出与退款分开列表，数值保留正负号；零/净负分类不画饼图扇区，精确数值表始终伴随。
- **R-Q07-4 多腿对账状态汇总**：腿级资格投影（每腿 标注 对账资格 有/无 —— 依据既有 reconciliation_eligible 语义与证据链存在性）；交易级 rollup 仅在有资格腿上计算：任一 MISSING → MISSING，否则任一 DIFFERENCE → DIFFERENCE，否则任一 PARTIAL → PARTIAL，否则全部 CHECKED → CHECKED，否则 PENDING；全部腿无资格 → 「无对账资格」。**只读，不改对账状态。**

#### 3.2.1 R-Q07-4 资格判定的注册解读（随本规格送评审确认）

裁决中「既有 reconciliation_eligible 语义与证据链存在性」操作化为**仅读既有行、零新语义**的判定：

- **资格 = 有** ⇔ 该当前版本 posting 满足任一：
  - (a) 存在 `rg03_transfer_posting_semantic` 行且 `reconciliation_eligible = 1`（schema 中唯一资格列，`Ledger.sq:497-506`；费用腿 `=0` 显式排除，见下）；
  - (b) 该 posting 存在证据链或对账状态行——即 active evidence link（沿 `selectP408ReconciliationReport` 的最新 history `state='active'` 判定语义，`Ledger.sq:8677-8682`）或 `posting_reconciliation` 状态行（`Ledger.sq:8062-8071`）。证据职责只指向真实账户分录（`ACCOUNTING_RULES.md:245`），故 (b) 天然限于真实账户腿。
- **资格 = 无**：`rg03_transfer_posting_semantic.reconciliation_eligible = 0` 的费用腿（既有语义显式排除，优先于 (b)）；以及其余一切腿——**含手工 P7-02 转账全部腿**（无 rg03 行、无证据链、无对账行；`SqlDelightRg03TransferStore.kt:1094` 为该表唯一写入方 = RG-03 回放竖井）与无证据链的普通收支腿。
- 每腿状态显示 = `COALESCE(posting_reconciliation.status, 'PENDING')`，中文标签沿用 `P408ReconciliationStatus`（`P408Reconciliation.kt:12-26`）；资格=无 的腿不参与 rollup、状态列显示「—（无对账资格）」。
- **后果披露**：纯手工账本（无导入证据链、无 rg03 行）的全部交易显示「无对账资格」；`ACCOUNTING_RULES.md:241` 的「交易列表显示由相关分录汇总出的状态」在本批以该只读投影承载，手工腿的可对账化留待后续对账批次定义（与 P7-02 L-5「零对账写入」不冲突；见 §8 R-4）。

## 4. 领域与应用变更

### 4.1 读模型端口扩展（`ledger-data` → `ledger-application`）

1. **新行类型（拟议 `LedgerEntryRow`）**：`transactionId, currentVersionId, kind(=COALESCE(canonical_kind, kind)), occurredAt, statisticsAt, note(String?), postings(List<Posting>)`。**既有 `CurrentVersionRow` 与 `loadCurrentRows`（`LedgerCurrentStateReadPort.kt:17-23`、`:45-46`）零改动**，新增独立端口方法（拟议 `loadLedgerEntryRows(ledgerId)`），保证 §8 R-7 的 22 个锚点不需修改即保持绿。
2. **新命名查询（零 DDL，完整清单见 Appendix A）**：条目行查询以 `COALESCE(tx.canonical_kind, tx.kind)` 选 kind（先例 `Ledger.sq:2438`、`:8672`），JOIN 当前版本 + posting_set + posting（沿 `currentVersionRowsForLedger` 的 join 形状，`Ledger.sq:8712-8739`）；创建入口反向查询 `transaction_id → import_confirmation`（operation_class 限 `'creation'`，`Ledger.sq:7774`）；分类当前名复用既有 `selectCatalogCurrentNamesByKind`（`Ledger.sq:9207-9208`）；腿级资格/状态/证据链查询沿 P408 报告的行语义（`:8658-8685`）按 transaction 维度收窄。
3. **不读 `formal_transaction_metadata`**（R-Q06-1）；不读 `rgXX_` 竖井新语义——资格判定 (a) 只读既有 `rg03_transfer_posting_semantic.reconciliation_eligible` 列值，不新增/不改写该表（§3.2.1）。

### 4.2 应用层拟议接口（名字为拟议契约，实施批不得更名语义）

1. **`QueryMonthlyActivity(ledgerId, month: YearMonth)`** → `MonthlyActivityResult = Success(MonthlyActivity) / InvalidState / Unavailable`。报表时区为 application 层冻结常量 `Asia/Shanghai`（R-Q06-2），**不作为调用方参数**（P703SPEC-10）。`MonthlyActivity` 携带（逐币种，`Long` minor units，checked 运算）：`ordinaryIncomeMinorUnits`（普通收入）、`netExpenseMinorUnits`（净支出）、**`balanceMinorUnits = ordinaryIncome − netExpense`（结余；checked 相减，溢出 → 类型化失败，沿 R-Q06-4）**，以及分类一级合计（含下钻所需的二级合计）、正向支出与退款分离列表（R-Q07-3）、当月交易计数（按有效 kind）。**结余不得称为账户余额或现金流**（计划 `:110` 原文冻结）。月度桶纯函数（拟议 `MonthlyBuckets`）：输入 `LedgerEntryRow` 列表 + 12 个月窗口（R-Q07-1：含本月、旧→新），按 `statisticsAt` 落桶（R-Q06-2），月界换算用 `kotlinx-datetime`（`:39`/`:49` 依赖就绪）在 Asia/Shanghai 下取 `[当月1日00:00, 次月1日00:00)`；**空月显式零值**（R-Q07-1）。
2. **`QueryTransactionDetail(ledgerId, transactionId)`** → `TransactionDetailResult = Success(TransactionDetail) / NotFound / InvalidState / Unavailable`。`TransactionDetail` 携带：有效 kind、金额腿（账户名 + 精确金额 + 币种）、`occurredAt` 与 `statisticsAt` 分别展示（R-Q06-1）、当前版本 note、分类当前名（含停用分类，R-Q07-2；**真实账户腿无 `catalog_category.posting_account_id` 映射（`Ledger.sq:314-326`）时按「无分类」缺席呈现（或账户 kind 标签），绝不失败——读模型把无分类腿处理为缺席分类而非 `InvalidState`（P703SPEC-09）**）、创建入口（`import_confirmation` 反查命中 → 「导入创建」；否则命中手工四链 receipt（`transaction_id UNIQUE`，`Ledger.sq:83/:135/:161/:253`）→ 「手工创建」；两者皆无 → 「来源未标注」，不以猜测补位）、多腿对账投影（§3.2.1）与交易级 rollup 结果（优先级链 R-Q07-4）。
3. **「本月」解析**：由组合根注入的 `LedgerClock.now()` 在 Asia/Shanghai 解析当月（R-Q06-2；注入先例 `P503Reducer.kt:60/:174`）；时钟异常按 `Unavailable`（R-Q06-4）。
4. **分类下钻（R-Q07-2）**：一级合计 = 其全部二级合计之和（以 `catalog_category.parent_id` 归组，`Ledger.sq:314-326`）；停用分类照常计入历史金额并显示当前名（`ACCOUNTING_RULES.md:91`、`:257`：历史分录不因停用改变）。
5. **流水排序契约（P703SPEC-03 冻结）**：首页/月度流水排序键 = **`statistics_at` DESC**（与 R-Q06-2 桶键一致）；同刻并列以 **`occurred_at` DESC** 决胜，再以 **`transaction_id` ASC** 决胜（UUIDv7 ⇒ 确定性全序）；同交易内多分录按 `posting_index` 稳定呈现，分组与行序无关。**现状读路径的 `ORDER BY tx.transaction_id, posting.posting_index`（`Ledger.sq:8739`）是创建序，不是本批显示契约**；排序在 application 纯函数施加，data 查询不新增 ORDER BY 依赖（零 DDL 不变）。

### 4.3 失败码族及可达性（§5.4 式说明，沿 P7-01 §6.3 / P7-02 §5.4 先例）

| code（沿用既有类型） | 触发 | 结果 |
| --- | --- | --- |
| `Unavailable` | 读端口异常、时钟不可用、组合根装配失败 | 类型化读失败，UI 呈现 `InfrastructureFailure(READ)` 既有重试面，**不以零/空月渲染**（R-Q06-4） |
| `InvalidState` | posting 账户不在目录/跨账本（沿 `isCatalogConsistent`，`QueryLedgerCurrentState.kt:94-98`）、桶累加 checked 溢出（沿 `SummarizeLedgerActivity.kt:103-112`）、余额/展示符号取反溢出 | 类型化读失败，零静默兜底 |
| `NotFound` | 详情请求的 transactionId 不在当前账本当前版本集（仅 `QueryTransactionDetail`） | 类型化读失败 |
| **不新增写失败码** | 本批零写入路径，`Rejected/Conflict` 族不可达 | — |

可达性说明：`InvalidState` 在产品读路径可达（目录演化与并发修正可产生暂时不一致）；`NotFound` 仅详情直达路径可达；`Unavailable` 沿既有读失败语义。**真实账户腿无 `catalog_category.posting_account_id` 映射（P703SPEC-09）不构成 `InvalidState`**——按「无分类」缺席呈现（§4.2.2）。读失败不得清空既有已渲染月份（UI 保持上一成功载荷 + 显式失败条），重试沿既有 `RetryRefresh`（`P503Reducer` 既有分支，未列事件仍 ISE，见 §6.2）。

### 4.4 领域层（`ledger-domain`）

**零改动。** 本批不新增领域规则、不改 `TransactionTimes`/`Money`/`TransactionKind`（`Values.kt:60-72`、`FormalLedger.kt:41-58`）；月度桶与分类聚合为 application 纯函数（消费领域类型，不反向上推）。

## 5. 持久化（`ledger-data`）

1. **只新增命名查询，零 DDL**：不改任何表列、不建索引、不新增迁移——schema 停留 **v29**（`LedgerDatabaseMigrationTest.kt:307` 断言 29 保持）；新查询在既有 join 形状上运行（先例：`currentVersionRowsForLedger` 无专用索引，`Ledger.sq:8712-8739`）。**若实施中发现确需新列/新表/新索引，必须停下显式登记并给出 `29.sqm`→v30 影响评估送评审，不得静默引入**（本设计判定不需要；全账本扫描 + application 聚合在演示规模下可接受，见 §8 R-6）。
2. **命名查询纪律**：查询命名/注释沿既有段风格（`Ledger.sq` P5-03 read boundary 段 `:8709-8711`）；全部按 `:ledger_id` 限定，杜绝跨账本泄漏（锚点 `SqlDelightLedgerCurrentStateReadAdapterTest` 的 ledger 隔离用例语义）。
3. **只读不变量**：本批在 `ledger-data` 零 INSERT/UPDATE/DELETE 新增；对账表、metadata 表、导入链表零写入。

## 6. UI 契约（状态机/事件矩阵）

### 6.1 状态机扩展（保留既有十二态与支出语义）

- **既有状态集不变**：十二态与字段语义原样保留（`P503AppState.kt:27-142`；`OverviewEmpty.selectedTab` `:40`；D-125/D-138/D-139/D-140 编辑语义不动）。
- **新增只读详情态（拟议 `TransactionDetail`）**：携带 `overview`（回跳载荷）、`originTab`、`transactionId`、详情载荷；仅可从 HOME 流水行进入（现为不可点击 `Text`，`P503OverviewScreen.kt:59-79` → 新增可点击行）；详情页零编辑入口。
- **`OverviewEmpty` 新增可选 `selectedMonth: LedgerMonth?`（默认 null = 本月）**：月度呈现的归约状态；「本月」由 `LedgerClock` 解析（R-Q06-2），跨月刷新 = 重新解析当月（C01 的「本月」跨月刷新向量）；首页月卡呈现 **普通收入/净支出/结余 三值**（§4.2.1；**结余不得称为账户余额或现金流**，计划 `:110`）。
- **事件新增（拟议）**：`SelectTransaction(transactionId)`、`CloseTransactionDetail`、`SelectMonth(LedgerMonth)`、`AnalysisMonthShift(offset)`（趋势/月卡联动）；全部为只读事件，零账务效应。
- **ANALYSIS/HOME 数据源**：分析 Tab 在既有全期汇总（D-122 面，`P503TabShell.kt:132-164`）下增月度区（月卡 + 分类下钻 + 趋势）；HOME 增月选择与详情入口；两者共用 `QueryMonthlyActivity` 结果（计划 §5.1）；流水行按 §4.2.5 排序契约呈现（`statistics_at` DESC → `occurred_at` DESC → `transaction_id` ASC）。

### 6.2 事件 × 状态矩阵（沿 P7-02 §6.2a 语义；G-B 纪律原样适用）

**规则**：既有 reducer 未被 `when` 列出的 `(state, event)` 抛 `IllegalStateException`（`P503Reducer.kt:1004` 的兜底分支，测试锁定不变）；本批**只为新增事件**定义 absorbed 语义，既有事件一律保持现状转换，未列即继续 ISE，不得反转任何既有 ISE（P7-02 §6.2 R-7 纪律）。

**表 6.2a：P7-03 新增事件 × 状态**（`effect` = 正常转换；`absorbed` = 明确吸收、状态不变、不崩溃；新事件在任何状态都不抛 ISE）：

| 新事件 | OverviewEmpty | TransactionDetail | 其余全部状态（Editing/AwaitingConfirmation/Submitting/RequestIdentityConflict/DomainRejected/InfrastructureFailure(SUBMISSION)/UnknownCommit/Created/NoChange/Recovered/Ready） |
| --- | --- | --- | --- |
| `SelectTransaction` | effect（→ `TransactionDetail`，仅 HOME 行可达） | absorbed | absorbed |
| `CloseTransactionDetail` | — | effect（→ 原 `OverviewEmpty`，保留 `selectedTab`/`selectedMonth`） | absorbed |
| `SelectMonth` | effect（更新 `selectedMonth`，重新请求月度结果） | absorbed | absorbed |
| `AnalysisMonthShift` | effect（ANALYSIS 月窗平移） | absorbed | absorbed |
| `MonthlyActivityResult`（新载荷事件，沿 `RefreshResult` 形状） | OverviewEmpty/TransactionDetail → effect（成功更新载荷；失败 → `InfrastructureFailure(READ)` 且**保留上一成功载荷于 overview**） | effect（详情态同语义） | absorbed |

- `Back`/系统返回：`TransactionDetail` 纳入 `isBackEnabled`/`isBackDispatchSafe`（`P503App.kt` 扩展 isBackEnabled 的 when（:1067-1082）与 isBackDispatchSafe 布尔表达式（:1083-1084）），返回即 `CloseTransactionDetail` 语义（保留 Tab/月，C03）；`Submitting` 拦截与宿主吞返语义不变。
- **`QueryMonthlyActivity` 重请求触发（P703SPEC-04 冻结；唯一触发集，其余一律不重新请求）**：(a) 初始加载；(b) `SelectMonth`；(c) `AnalysisMonthShift`；(d) 所选月变化时（含发生时间重解析导致所选月变化）；(e) 每次确定成功后的权威刷新（`Created`/`NoChange`/`Recovered`）。**`CloseTransactionDetail` 不重新请求**（月保留、数据不变，除非期间发生了 (e)）。
- **封闭触发集残余边界（P703SPEC-11 批登记；一句一条，不改动触发集）**：(a) 月度读失败 + `RetryRefresh` 成功后，月度载荷**不**重新请求（`RetryRefresh` 不在触发集 (a)-(e) 内），恢复路径 = 重新派发 `SelectMonth`（触发 (b)，无条件重请求）；(b) 空账本无可选月（无「首个交易统计月」）：`SelectMonth` absorbed，月卡呈现空态；(c) 统计时间晚于「本月」的交易落在 `SelectMonth` 可选域与 12 个月趋势窗之外，但仍在流水列表按 §4.2.5 排序可见。
- **`SelectMonth` 可选域（P703SPEC-10 冻结）**= **[首个交易统计月, 本月]**（两端含，按报表时区计算）；越界 `SelectMonth` absorbed、零状态变化。
- 详情态下的目录管理/录入事件一律 absorbed（详情只读，无对话框入口）。

### 6.3 既有语义保留（硬约束）

- 编辑流全链（D-125/D-131/D-138/D-139/D-140）不动；`Created/NoChange/Recovered` 后权威刷新恒回首页不变。
- 读失败不篡改：`InfrastructureFailure(READ)` 既有转换保持；新增读失败分支仅新增载荷，不改既有重试语义。
- `Exit` 事件维持现状未使用/ISE（沿 P7-02 §6.2b，不借口加 absorbed）。

### 6.4 TalkBack 与数值呈现

- 全部金额以 `formatMinorUnits` 精确呈现并保留符号（负支出/退款不取绝对值伪装，R-Q07-3；沿 `SummarizeLedgerActivity.kt:10-14` 的有符号显示规则）；TalkBack 朗读精确数值与币种，不朗读无精度百分比。
- 饼图：零/净负分类不出扇区；**精确数值表始终伴随**（R-Q07-3）；图形仅作比例辅助，数值以文本为准。
- 空月/空账本显式文案（「该月无交易」），区别于读失败（R-Q06-4：失败不伪装成空）。

## 7. 验收矩阵（C01–C04 可测断言）

| # | 步骤（全部匿名合成数据） | 期望（可测断言） | 关联裁决 |
| --- | --- | --- | --- |
| C01 | 同一交易 `statistics_at` 落上海月界两侧（`2026-03-31T15:59:59Z` 与 `2026-03-31T16:00:00Z` 对 4 月界）；跨年（2025-12→2026-01）/闰年 2 月/空月；「本月」跨月刷新（注入 `LedgerClock` 固定 instant 前后）；修正经追加新版本后 statistics_at 变化的交易 | 只进入统计时间所在月恰一次；旧版本不重复计入；空月显式零值；「本月」随时钟当次 `now()` 在 Asia/Shanghai 重解析 | R-Q06-1/R-Q06-2；桶键=statistics_at |
| C02 | 账本含 LEND `100.00`、COLLECT（本金 `40.00`+利息 `5.00`）、转账（本金 `60.00`+手续费 `1.00`）、支出 `30.00`、收入 `100.00`、跨月退款 `-30.00`；储值充值（实付 `1000`/赠送 `200`）与余额调整各一笔；在所选月内提交一笔新交易后**不手动刷新** | LEND/COLLECT 不误标支出；本金零普通收支；手续费计普通支出 `1.00`；利息计普通收入 `5.00`；退款计收款期负支出 `-30.00`；储值与调整两笔对普通收支贡献为 `0.00`；首页月卡/分类表/趋势单月数据三方一致；在「无特殊 kind」对照账本上 Σ月度 == `SummarizeLedgerActivity` 全期（逐币种）；**提交后月卡/计数/结余随权威刷新自动更新（重请求触发 (e)），三方一致（P703SPEC-04）；结余 = 普通收入 − 净支出 逐币种核对** | R-Q06-3；R-Q06-4；§3.1.1 表；§3.1.2 锚；§4.2.1 |
| C03 | 分类改名/停用后查历史月；交易带备注、手工后补证据；导入创建交易；多资金腿交易部分核对（含 rg03 行账本与仅证据链账本两向量）；手工 P7-02 转账；**镜像向量**：同 raw identity 的后到镜像证据（对既有交易追加）；**合法相似向量（计划 D03）**：不同 raw identity 的合法相似记录 | 分类显示当前名且一级=Σ二级；历史金额不变；详情显示「手工创建」（后补证据不改创建入口）/「导入创建」；有资格腿显示逐腿状态并按优先级链 rollup（MISSING>DIFFERENCE>PARTIAL>全 CHECKED>CHECKED>PENDING）；手工转账显示「无对账资格」；全程零对账写入（`posting_reconciliation` 行数不变）；**镜像向量（P703SPEC-11 重写）：镜像后仍只有一笔交易，镜像证据追加到该交易、不创建第二笔交易（`ACCOUNTING_RULES.md:80`、`:152`；导入链同 raw identity 重复由重复审核阻断）；被镜像的真实账户腿经 active 证据链获得对账资格（§3.2.1(b)），腿对账状态值与交易级 rollup 除对账操作外不受影响（证据追加不是对账操作；新获资格腿按既有 `COALESCE('PENDING')` 语义进入投影，不升级任何既有状态值）；创建入口显示不因镜像证据改变（证据链接存在不能单独证明创建入口为导入，计划 §5.2 `:123`）；合法相似向量：不同 raw identity 的合法相似记录形成多笔各自独立交易，各带自己的创建入口、从不合并** | R-Q07-2/R-Q07-4；§3.2.1；P703SPEC-11 |
| C04 | 构造读端口异常、目录不一致 posting、`Long` 溢出聚合（合成极大金额）；空账本；净负月份；长列表（大当前版本集流水滚动）；TalkBack 巡检 | 全部显式类型化失败（`Unavailable`/`InvalidState`），不渲染为零或空月；上一成功载荷保留 + 失败条；空月文案区别于失败；净负月不画误导扇区、精确表伴随；**长列表按 §4.2.5 稳定排序渲染、数值对无障碍可达，不声明虚拟化行为（P703SPEC-05）**；TalkBack 朗读精确带符号数值；返回保留 Tab/月 | R-Q06-4；R-Q07-1/R-Q07-3；§6.4 |

## 8. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 兼容 kind 降级（`Ledger.sq:1945-1984`）使旧读路径把 LEND/COLLECT/REFUND_RECEIPT 读回 EXPENSE；本批新查询必须 `COALESCE` | 分类/月度全错 | 新行类型独立于 `CurrentVersionRow`（§4.1.1）；C02 向量断言真实 kind；不改 `insertTransaction` |
| R-2 | 统计时间双存储面（§2.3）：原地 metadata 写入方有**两个**——RG-11（`SqlDelightRg11Store.kt:639-652`，另走版本追加 `Rg11Operations.kt:934-946`）与 **RG-12（`SqlDelightRg12Store.kt:610`）**，且 RG-12 的 metadata 值本身派生自当前版本的 `statistics_at`（`SqlDelightRg12Store.kt:603-608`），故读版本列在两条修正路径下均保持正确 | RG-11/RG-12 修正月历史统计口径误读 | R-Q06-1 冻结只读版本列；`formal_transaction_metadata` 零读取；测试含追加版本后月份迁移向量（C01） |
| R-3 | 月界/时区：Asia/Shanghai 无 DST，但月 Arithmetic 需以 `kotlinx-datetime` 显式构造，不得手写 86400 秒累加 | 月界漂移 | R-Q06-2 冻结；C01 跨年/闰年向量；`kotlinx-datetime 0.8.0` 既有依赖 |
| R-4 | R-Q07-4 资格判定为**注册解读**（§3.2.1）：手工 P7-02 转账腿显示「无对账资格」，与 P7-02 L-5「保持待对账」的产品语感存在差距 | 用户误解手工腿对账状态 | 只读零写入不改变事实；§3.2.1 后果披露 + 评审确认；后续对账批次拥有手工腿资格定义；C03 含两向量 |
| R-5 | 特殊 kind 账本上月度普通收支 ≠ `SummarizeLedgerActivity` 全期（如储值花费的费用分录） | 用户对照两处数字不一致 | R-Q06-3 既定结果；§3.1.2 锚限定「无特殊 kind」账本；披露于分类区（特殊 kind 单列计数，不入普通收支行） |
| R-6 | 性能：全账本扫描 + application 聚合，无新索引 | 大账本卡顿 | 演示规模可接受（现读路径同形，`Ledger.sq:8712-8739`）；零 DDL 冻结；确需索引必须走 v30 影响评估（§5.1） |
| R-7 | **既有测试锚（22）必须保持绿且不需修改**：`SummarizeLedgerActivityTest`（7，`ledger-application/src/commonTest/kotlin/com/unifiedledger/application/`）、`QueryLedgerCurrentStateTest`（9，同目录）、`SqlDelightLedgerCurrentStateReadAdapterTest`（6，`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/`） | 读模型扩展回归 | §4.1.1 零改动策略 + 新增并行端口方法；实施批逐类复跑（计划 §8.1 `:202`） |
| R-8 | 金额溢出：聚合/取反全程 `Long` minor units | 静默回绕 | checked 运算沿 `SummarizeLedgerActivity.kt:103-112` 先例；溢出 → `InvalidState`（R-Q06-4）；禁浮点（领域不变量 `ACCOUNTING_RULES.md:27`） |
| R-9 | 隐私（D06 `:167`）：详情/创建入口显示不得引入来源原文名、URI 或原始行 | 泄露 | 创建入口仅显示「导入创建/手工创建/来源未标注」枚举，不携带来源文件名或 URI；示例匿名合成 |

## 9. 实施拆分（收编 `docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §5.2，:114-121）

| 子项 | 具体工作及接口责任 | 完成条件 |
| --- | --- | --- |
| P7-03.A 当前读模型 | data 返回 ledger/current-version 且未被作废的限定数据（P7-05 重冻结「current-version 即有效」为「current-version 且未被作废」），application 验证真实 kind、当前时间/备注、名称与来源关系 | 新交易和旧数据均能正确读取；失败不伪装空账/零余额 |
| P7-03.B 统一月度投影 | 先用规则级匿名向量冻结普通收支、分类、退款/特殊项、月份口径，再扩展已有汇总 | 首页月卡、分类合计、趋势单月数据一致，精确金额溢出 fail-closed |
| P7-03.C 首页与详情 | 首页小结/按时间排序流水/详情入口；详情展示金额、时间、账户、分类、备注、创建入口及资金腿对账 | 返回保留 Tab/月；手工后补证据仍显示手工创建；停用分类历史可见 |
| P7-03.D 分类与趋势 | 建议趋势近 12 个月；分类一级汇总下钻二级；饼图伴随精确数值表 | 零/净负分类不画误导比例；建议正向支出与退款分开展示，数值保留正负，Q07 已裁决 |

## 10. 本批不做（逐项）

编辑/修正 UI（版本替代、note 修改、分类重指）；对账操作（任何对账/证据写入）；多账本；多币种汇总；导出/分享；搜索/检索；预算；自动刷新/后台任务；日期选择器与解析器改动；rgXX 竖井与 golden 改动；导入链写入语义改动；新迁移/新列/新索引（schema 停留 v29）。（另见 §1.2。）

## 边界断言

- 本文档为设计门 **冻结规格（approved）**：独立规格评审闭环（DELTA CLOSURE APPROVE）并冻结后构成本批实施契约；实施在单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（根 `AGENTS.md` 变更路由）。
- 实施批必须保持本规格冻结的：R-Q06-1..4（含 §3.1.1 枚举表与 §3.1.2 核对锚）、R-Q07-1..4（含 §3.2.1 注册解读）、零 DDL、零账务写入路径改动、22 个既有测试锚不修改即绿、C01–C04 覆盖面；任何变更即重开评审门。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动。

## Appendix A. 拟议新增命名查询清单（实施批以评审后文本为准；全部零 DDL、按 `:ledger_id` 限定）

```text
ledgerEntryRowsForLedger          -- 当前版本条目行：COALESCE(canonical_kind, kind)、
                                  -- occurred_at/statistics_at/note + postings（join 形状沿 :8712-8739）
importCreationConfirmationByTransaction  -- transaction_id → import_confirmation（operation_class='creation'，
                                  -- 反向溯源；现仅 selectImportConfirmationByRequest :8443-8447）
manualCreationReceiptByTransaction       -- transaction_id → 手工四链 receipt（transaction_id UNIQUE :83/:135/:161/:253）
transactionReconciliationLegs            -- 按交易列 posting + COALESCE(status,'PENDING') + active evidence link
                                  -- （行语义沿 :8658-8685）+ rg03_transfer_posting_semantic.reconciliation_eligible
                                  -- （只读既有列，:497-506）
-- 复用既有查询（不新增）：selectCatalogCurrentNamesByKind（:9207-9208，分类当前名）
-- 零 DDL：无新索引/新列/新表；schema 停留 v29
```
