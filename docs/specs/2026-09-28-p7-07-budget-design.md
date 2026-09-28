# P7-07 预算（budget）设计门规格（设计门候选）

状态：proposal（2026-09-28 起草；等待裁决，**不授权实现**）。本文是 P7-07「预算」的**设计门候选**，冻结 07.A 计量矩阵、07.B 配置模型、07.C 计算契约、07.D 模块/读取边界与 **07.T 时间投影技术门**的设计面。审批（含 Q15/Q16 裁决与 schema 版本号分配）是**独立裁决**；在裁决前本文不构成产品行为、迁移、技术选型或发布授权。本文不写实现、不写迁移文件、不分配 schema 版本号。

**Revision:** draft-1（2026-09-28）。初稿。工作基线 = 本 worktree 分支 `UL-p7-07design`，基点 `a895ee4`（当前 `main` 头）。schema 停留 **v31**，迁移链 `1.sqm`～`30.sqm`（30 个文件，v1→v31）；下一个可用版本号候选为 v32（`31.sqm`），**本文不分配、不创建**该文件。tracked 行号为本基点实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 为主 checkout 的本地只读文件，本 worktree 内不存在，凡引用该计划一律标注「计划」并以其主 checkout 实读行号为准。本文**不**修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不复制大段产品代码；不写本机绝对路径、个人数据或工具轨迹；示例全部匿名合成。

**标记约定：** 全文用【已验证事实】标注本次实读代码/文档所得的现状，用【冻结设计提案】标注本文建议、尚未批准、实现者不得当作既有 API。

## Scope

冻结 P7-07 预算的设计候选面：

- **07.A 口径冻结**：普通净支出预算的计量矩阵（16 个 `TransactionKind` 全覆盖）、月份/时区/币种、退款/作废/恢复/跨月修正归属。
- **07.B 配置持久化**：按月版本化配置的身份、scope 语义、非负金额、历史与 CAS/回执协议。
- **07.C 查询与提示**：`remaining`/`overspent` 计算契约、整数算术与边界语义。
- **07.D 模块与读取**：domain/application/data/app-ui 职责与共享有界读取端口。
- **07.T 技术门**：`transaction_version.statistics_at` 的时间投影、索引、回填与原子维护。

**首个设计切片 = 07.A 计量矩阵 + 07.B 配置模型 + 07.C 计算契约 + 07.T 技术门**；UI 呈现与联动刷新（07.D 的 app-ui 面）是后续切片。本文**只冻结设计**，实现、迁移、Git 写操作与最终验收属后续独立实施批。

## 非目标（本文不授权）

- 多币种预算：首版**固定 CNY**，不做跨币种预算或汇率换算。
- 周期预算（日/周/季/年）、结转（carry-over）、自动记账、系统通知：均不纳入。
- Golden `budget_effect`：**不**作为产品预算模型，**不**因「像支出」而改写（见 §1.4）。
- 储值/预付领域预算：`STORED_VALUE_*`、`PREPAID_*` 的领域按期预算语义保留，未来完整消费预算须另立契约；本文不声称这些领域预算为零。
- 任何资金分录、余额或对账副作用：预算绝不创建 Posting、绝不改变余额/对账（见 §5）。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为本 worktree 基点 `a895ee4` 实读行号；计划行号为主 checkout 实读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §7（计划 `:196-256`，P7-07 四子项 07.A–07.D、§7.1 Q15/Q16 推荐与 16-kind 表、§7.2 模块与读取设计、§7.3 切片与验收、§7 内 07.T 技术门）、§10.5 的 T07 测试导航（计划 `:439-445`）、§10.6 的 Q15/Q16 行（计划 `:453-454`）。计划 `:4` 表明未裁决项不构成授权。
- **账务规则**：`docs/ACCOUNTING_RULES.md:114`（多分类混合支付「预算不适用」、消费合计口径）、`:182`（RG-10 储值充值/消费/到期 `budget_effect` 均为零，只有实际消费计入 `category_effect`）、`:196`（按期分摊「只改变消费与预算的期间归属」）、`:225`（余额调整预算为零）、`:227`（账户互转不产生预算）。
- **产品需求**：`docs/PRODUCT_REQUIREMENTS.md` 当前**无**任何预算实体、配置或计算契约（本次实读：`预算`/`budget` 命中 0 次）；P7-07 全部为新建面。本规格不虚构产品需求。
- **架构**：`docs/ARCHITECTURE.md:5`（四共享模块 + 两组合根）、`:25-28`（模块职责：domain 纯不变量、application 用例/端口、data 持久化、app-ui 只消费 application 类型）、`:7`/`:173`（SQLDelight 2.3.2、schema v31、迁移链 `1.sqm`~`30.sqm`）。
- **Golden**：`docs/GOLDEN_SCHEMA.md:482-488`（`budget` metric 的 `not_applicable` 语义、零与不适用不可互换）、`golden/rules-v2/rg-10.json:390-394`（RG-10 充值 `budget_effect` 为 `applicable`/`0.00`）。
- **设计规格先例**：`docs/specs/2026-09-19-p7-05-correction-void-recycle-design.md`（approved；claim-first/CAS/回执形状与有效谓词先例）、`docs/specs/2026-09-13-p7-03-ledger-view-design.md`（approved；月度投影、时区常量、普通收支分类冻结）、`docs/specs/2026-09-26-p7-06-restore-confirm-switch-design.md`（approved；header/Revision/状态约定）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；`transaction_effective_state` 是有效谓词的**唯一 SQL 定义点**，预算读不得另立第二套状态规则；不引入新依赖。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 月度收支已有派生能力【已验证事实】

- 冻结的月度投影在 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/MonthlyBuckets.kt`：报表时区常量 `REPORT_TIME_ZONE = TimeZone.of("Asia/Shanghai")`（`:29`）、桶键 `bucketKey(statisticsAt)`（`:39`，取统计时间在冻结时区的自然月）、月起点 `monthStart`（`:99`）、统一聚合 `aggregate`（`:113`）。`TREND_MONTH_COUNT = 12`（`:32`）。
- 读用例 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/QueryMonthlyActivity.kt`：`query(month)`（`:30`）、`trend()`（`:47`）、`selectableMonths()`（`:78`），三者均经私有 `loadRows()`（`:90`）调用 `readPort.loadLedgerEntryRows(ledgerId)`——**整账本读取**，无月份谓词下推。
- 读适配器 `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightLedgerCurrentStateReadAdapter.kt:201-230 loadLedgerEntryRows`：`database.ledgerQueries.ledgerEntryRowsForLedger(ledgerId.value).executeAsList()`（`:202`）后 `groupBy { it.transaction_id }`（`:204`）在内存折叠。

### 1.2 普通收支分类已冻结为六类【已验证事实】

`MonthlyBuckets.kt:345-357 OrdinaryFlowClassification`：

- `ORDINARY_KINDS`（`:346-354`）恰为六项：`EXPENSE`、`INCOME`、`ACCOUNT_TRANSFER`、`LEND`、`COLLECT`、`REFUND_RECEIPT`。
- `isOrdinary(kind)`（`:356`）为集合成员判定；其余十个 kind 整体排除。
- 分类器随后**按分录账户 kind** 分派（`aggregate`，`:139-186`）：`AccountKind.EXPENSE`（`:145-168`）保留账本符号计入普通费用（负号分录即负费用，退款腿因此为负）；`AccountKind.INCOME`（`:170-180`）取负计入普通收入；`AccountKind.ASSET`/`LIABILITY`/`EQUITY`（`:182-185`）贡献零——**所有本金腿由此被移除**。
- 净支出 `netExpense = positive + refund`（`:196`，`refund` 为非正数），经 `checkedAdd`/`checkedSubtract`（`:294-312`）精确 Long 运算。

### 1.3 目录与稳定分类【已验证事实】

`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/Catalog.kt:43-51 Category`：`id`、`ledgerId`、`parentId`（可空，空为一级）、`postingAccountId`（可空）、`active`、`kind`（默认 `CategoryKind.EXPENSE`）、`name`。`Catalog.kt:11-14 CategoryKind { EXPENSE, INCOME }`；`Catalog.kt:3-9 AccountKind { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }`。

### 1.4 Golden `budget_effect` 不是产品预算模型【已验证事实】

- `docs/GOLDEN_SCHEMA.md:485` 的 `not_applicable` 形态（`{"metric":"budget","applicability":"not_applicable"}`）与 `:488` 的语义：`applicable` 要求显式零，零不等于不适用。
- `golden/rules-v2/rg-10.json:390-394`：RG-10 充值场景的 `budget` metric 为 `applicable`、`CNY`、`0.00`。`docs/ACCOUNTING_RULES.md:182` 与 `docs/specs/2026-07-16-rg-10-stored-value-design.md:27`/`:31` 均声明 RG-10 的 `budget_effect` 为零，只有实际消费产生 `category_effect`。
- **结论【冻结设计提案】：** Golden `budget_effect` 是 RG 报表维度，**不是**产品预算模型，不得被预算 UI 复用、改写或"打开"；产品预算与它无数据耦合。

### 1.5 无产品预算实体、配置或计算契约【已验证事实】

- 四个共享模块 `commonMain` 与两端组合根对 `Budget`/`budget` 概念零命中（本次实读 `grep` 无产品源码命中）；`PRODUCT_REQUIREMENTS.md` 零预算需求。07.A–07.D 全部为**新建面**。
- 既有 `QueryMonthlyActivity`/`MonthlyBuckets` 只产出普通收支观察值，**不含**预算额度、剩余或超支。

### 1.6 07.T 现状：时间列为 TEXT，无月份谓词、无时间索引【已验证事实】

- `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:15-34 CREATE TABLE transaction_version`：`statistics_at TEXT NOT NULL`（`:22`）。
- `Ledger.sq:8760-8788 ledgerEntryRowsForLedger`：`WHERE tx.ledger_id = ?` 后 `ORDER BY tx.transaction_id, posting.posting_index`；**无 `statistics_at` 范围谓词**，无月份过滤。
- `Ledger.sq:9779-9789 CREATE VIEW transaction_effective_state`（有效谓词唯一 SQL 定义点，原文）：

  ```sql
  CREATE VIEW transaction_effective_state AS
  SELECT tx.ledger_id AS ledger_id, tx.transaction_id AS transaction_id,
    CASE WHEN (
      SELECT fact.fact_kind FROM transaction_void_fact AS fact
      WHERE fact.ledger_id = tx.ledger_id AND fact.transaction_id = tx.transaction_id
      ORDER BY fact.sequence DESC LIMIT 1
    ) = 'void' THEN 0 ELSE 1 END AS is_effective
  FROM ledger_transaction AS tx;
  ```

- `transaction_version` 上**没有** `(ledger_id, time, stable_id)` 之类的范围索引：全库 `CREATE INDEX` 中与本表相关者为零（`Ledger.sq:9713` 的 `transaction_void_fact_recycle_bin_idx` 属作废事实表，非本表）。

## 2. 07.A 计量矩阵（Q15）

### 2.1 口径【冻结设计提案】

推荐**普通净支出预算**：固定 **CNY**、**上海自然月**、**无结转**。预算是对已批准普通收支的**观察视图**，不宣称覆盖所有领域消费，也不等于 Golden `budget_effect`。归属月取**当前有效版本**的 `statistics_at`（经 `MonthlyBuckets.bucketKey`，`:39`）。退款按**该退款交易自身的统计月**记负支出（默认到账期），**不回冲原消费月**。收入**不抵**支出额度。

### 2.2 16 kind 纳入矩阵【冻结设计提案；逐 kind 已对 `FormalLedger.kt` 复核】

`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/FormalLedger.kt:41-58 TransactionKind` 实读为 16 项，逐项纳入建议如下（复用于计划 §7.1 表，本次逐项核对 kind 存在）：

| # | TransactionKind（`FormalLedger.kt` 行） | 首版普通净支出预算 | 依据/理由 |
| --- | --- | --- | --- |
| 1 | `OPENING_BALANCE`（`:42`） | **排除** | 特殊 kind，非普通收支 |
| 2 | `EXPENSE`（`:43`） | 当前有效费用腿按有符号精确金额计入 | 六类普通之一（`ORDINARY_KINDS`） |
| 3 | `INCOME`（`:44`） | 收入腿**不计支出**；不以收入抵预算 | 六类普通之一，但账户 kind `INCOME` 只入普通收入 |
| 4 | `ACCOUNT_TRANSFER`（`:45`） | 本金**不计**，手续费费用腿计入 | 六类普通之一；账户 kind 分派移除资产本金，保留费用腿 |
| 5 | `CREDIT_REPAYMENT`（`:46`） | **排除** | 特殊 kind，保持调整零预算效果 |
| 6 | `REFUND_RECEIPT`（`:47`） | 费用腿**负数**计入退款当月，不计普通收入 | 六类普通之一；`AccountKind.EXPENSE` 负号分录为负费用 |
| 7 | `BALANCE_ADJUSTMENT`（`:48`） | **排除** | 保持调整零预算效果（`ACCOUNTING_RULES.md:225`） |
| 8 | `BALANCE_ADJUSTMENT_REVERSAL`（`:49`） | **排除** | 同上 |
| 9 | `STORED_VALUE_RECHARGE`（`:50`） | **排除** | 保持 RG-10 已批准 `budget_effect=0` |
| 10 | `STORED_VALUE_SPEND`（`:51`） | **排除** | 同上（实际消费另计 `category_effect`） |
| 11 | `STORED_VALUE_EXPIRY_LOSS`（`:52`） | **排除** | 同上 |
| 12 | `STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT`（`:53`） | **排除** | 同上 |
| 13 | `LEND`（`:54`） | 本金**不计**；当前手工借出无费用；遵守既有 ordinary 费用腿规则 | 六类普通之一 |
| 14 | `COLLECT`（`:55`） | 本金与收入利息**不计**；当前产品费用必须为零，不借预算开放收费 | 六类普通之一 |
| 15 | `PREPAID_PURCHASE`（`:56`） | **不进入**本次「普通」预算 | 领域按期预算语义保留；未来完整消费预算须另立契约 |
| 16 | `PREPAID_RECOGNITION`（`:57`） | **不进入**本次「普通」预算 | 同上；不声称预付领域预算为零 |

**排除项汇总（10 个 kind 整体排除）：** #1、#5、#7、#8、#9、#10、#11、#12、#15、#16。排除依据分三类：特殊 kind（#1/#5/#7/#8）、RG-10 冻结零预算效果（#9–#12）、预付领域预算另立契约（#15/#16）。

**行为等价性要求【冻结设计提案】：** 预算的纳入判定**必须复用** `OrdinaryFlowClassification`（`MonthlyBuckets.kt:345-357`）与 `aggregate` 的账户 kind 分派（`:139-186`），**不得**另写一套 kind 表或从分录账户类型直接猜预算。原 kind、金额事实与 Golden 预期均不因预算 UI 改写。

### 2.3 与月度净支出的异同【冻结设计提案】

- **相同：** 归属月、时区、桶键、六类普通集合、费用腿账户 kind 分派、有符号精确金额——全部复用既有冻结实现。
- **不同：** 预算只观察**费用侧**（普通净支出 = `EXPENSE` 账户腿的有符号和），**不**把普通收入计入额度、**不**计算结余；`MonthlyCurrencyActivity`（`:384`）的 `ordinaryIncomeMinorUnits`/`balanceMinorUnits` 不参与预算。预算额度与 scope 是**产品配置**，不是从分录账户类型推导。

## 3. 07.B 配置模型（Q16）

### 3.1 配置主键与 scope【冻结设计提案】

- 主键语义：`(ledgerId, month, currency, scope)`。
- `currency` 首版固定 **CNY**（`CurrencyUnit("CNY", 2)` 形态，precision 2）。
- `scope` 是二选一：
  - `TOTAL`：覆盖该月**所有**普通净支出（**含无分类**），不是分类额度或执行额之和。
  - 稳定支出 `categoryId`：一个 `CategoryKind.EXPENSE` 的稳定分类 ID。
- `month` 为 `YearMonth`（上海自然月）。

### 3.2 层级覆盖与独立观察【冻结设计提案】

- 一级分类额度**覆盖**其全部二级子项：一级 scope 的净支出 = 其所有二级子项费用腿之和（复用 `MonthlyBuckets.buildCategoryNodes` 的汇总规则，`:221-256`；退化目录状态按 `:241-244` 将无父叶子提升为一级，不丢金额）。
- 若同时设父/子预算，它们是**独立观察限制**：各自计算各自的净支出与超支，**不**分配/扣减彼此额度，也**不**能把父子金额相加。
- `TOTAL` 覆盖所有普通净支出（含无分类），与分类额度**独立**，不是分类额度之和。UI 必须明示「总预算、分类预算独立观察」。
- 二级可下钻。

### 3.3 金额语义【冻结设计提案】

- 额度为非负精确 minor units（`Long`，CNY precision 2）。
- **零额度是有效预算**（监控且额度为 0）；**未设置/关闭**才是**不监控**。零与未设置**必须可区分**（对齐 `GOLDEN_SCHEMA.md:488` 的「零 ≠ 不适用」语义，但此为产品面独立语义，不引用 Golden 结构）。
- 负额度输入拒绝（类型化失败）。

### 3.4 按月版本化配置与协议形状【冻结设计提案；镜像 `CatalogManagement` 先例】

配置 owner 必须镜像目录管理的 **claim-first / 等价 replay / CAS / 独立回执**协议形状（先例：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/CatalogManagement.kt:143-201 CatalogCommandRequest` 携带 `requestSnapshot`（唯一等价 replay 依据）+ 派生的 `inputFingerprint`（**不**参与等价）+ `expectedCatalogVersion`（乐观并发）；结果族四态 `Accepted`/`NoChange`/`Rejected`/`Conflict`（`:168-181`）；持久化侧 `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightCatalogStore.kt:116-208 commitOnce/resolveExisting`，SQL 侧 `Ledger.sq:9564-9592 claimCatalogCommandRequest`/`selectCatalogCommandRequest`/`updateCatalogCommandRequestOutcome` 及 `:346-372 catalog_command_request`/`catalog_command_receipt` 与其不可更新/不可删除守卫触发器）。

冻结要求：

- **稳定预算身份**：每 `(ledgerId, month, currency, scope)` 一个稳定预算 ID。
- **保存当前 revision + 不可变设置历史**：每次新增/修改追加一条不可变历史；当前 revision 仅作 CAS 指针。
- **独立请求/回执**：claim-first（`ON CONFLICT DO NOTHING` 语义）、等价 `requestSnapshot` replay 返回原回执且**零写入**、同 ID 异快照返回 `RequestIdentityConflict`、`expectedRevision` 不匹配返回版本冲突且零写入。
- **关闭监控也留历史**：关闭是追加一条「关闭/未设置」历史，不删除既有配置与历史。
- **显式确认**：新增与修改均须显式确认（请求快照），不得静默写入。

### 3.5 历史月编辑与引用规则【冻结设计提案】

- 允许编辑所选**历史月份**，但 UI 须明示：历史比较按该月**最新配置**重算，并可查看修改记录；编辑**不改交易**。
- 停用分类**保留**既有预算及统计；**不允许**新绑定停用分类；调整既有额度**不等于**重新启用分类。
- 分类删除须检查**预算全部历史引用**（沿 `CatalogManagement.kt:213-216 hasReferences`/`SqlDelightCatalogStore.kt:60-64` 的引用检查先例）：存在任何当前或历史预算引用即拒绝删除。

## 4. 07.C 计算契约

【冻结设计提案】

- `remaining = limit - netExpense`
- `overspent = max(netExpense - limit, 0)`
- **精确整数算术**：每次加/减/取负都做溢出检查（复用 `MonthlyBuckets.kt:294-312 checkedAdd/checkedNegate/checkedSubtract` 的模式），溢出**类型化失败**，绝不静默回绕。
- **不钳零**：净退款可使 `netExpense` 为负、`remaining` 大于 `limit`；金额**不得**钳到零。
- **边界（恰好等于）语义**：`netExpense == limit` 时 `overspent = 0`、`remaining = 0`，**不**算超支。
- 进度图可限制**绘图**范围，但金额与无障碍朗读**保留精确值**。
- 首版只做**应用内提示**（只读结果），不纳入日/周预算、结转或系统通知。

## 5. 07.D 模块边界（§7.2）

【冻结设计提案；模块职责对齐 `ARCHITECTURE.md:25-28`】

| 模块 | 职责 | 禁止 |
| --- | --- | --- |
| `ledger-domain` | 月份/范围/额度值对象与**纯**金额比较、`remaining`/`overspent` 纯函数 | 持久化、平台 API、UI 状态 |
| `ledger-application` | `SaveBudget`、`QueryBudgetMonth` 用例；配置端口与**有界**执行读取端口 | 具体数据库、文件选择器 |
| `ledger-data` | 原子配置、不可变历史、迁移与 SQL | 决定账务规则、绕过应用用例 |
| `app-ui` | 配置确认、月份列表、一级/二级下钻、超支与失败态 | 数据库、SQLDelight、driver、账务规则 |

以上用例/端口名称是**建议**，实签名由规格核验。**预算绝不创建 Posting，也绝不改变余额/对账。**

### 5.1 共享 `MonthlyContributionReadPort`【冻结设计提案】

推荐新增共享端口，输入 `ledgerId`、月份区间 `[start, end)`（由稳定键界定），在**同一读快照**按稳定键分批返回该范围的最小分录贡献；**一次查询/折叠**同时得到总额与所有一级/二级观察值。

硬约束：

- **复用同一精确分类器**（`OrdinaryFlowClassification` + 账户 kind 分派），保持 E07-01 行为一致。
- **不得**为每个预算重新调用现有全账 `QueryMonthlyActivity`（§1.1 的整账本读取）。
- **不得** SQL 浮点汇总，**不得** `SUM(DISTINCT amount)` 去重（多标签/多腿场景会错误去重同额分录）。
- 目录与交易读要有**一致版本**；数据库失败、坏目录与溢出返回**明确失败**，**不显示零执行额**（沿 `MonthlyActivityResult.Unavailable`/`InvalidState` 的 fail-closed 纪律，`MonthlyBuckets.kt:417-427`）。
- **不得**为分页/同一快照而牺牲数据库连接持有时间：须与端到端响应一起测量（P707-A08）。
- 共享端口复用范围：预算需费用贡献；标签/商家按其筛选条件取相同 ordinary 语义；widget 还需收入/结余，**不能**误用「仅支出」接口。
- **是否将既有月卡切换新读取路径必须在规格列明**，并用对照测试证明原币种集合、空月、无分类、`countByKind` 等输出**不变**（`MonthlyCurrencyActivity` `:384-397`）；本批**不**顺手改变既有读契约。

## 6. 07.T 时间投影技术门（关键设计，须独立评审）

### 6.1 问题陈述【已验证事实】

`transaction_version.statistics_at` 是 `TEXT`（`Ledger.sq:22`），多个写入者产生**不同格式**，故**字典序 ≠ 时间序**。

### 6.2 写入者与格式枚举【已验证事实；逐处实读】

**(a) `kotlin.time.Instant.toString()` 规范形（`Z` 结尾，小数位按需）：**

| 写入者（file:line） | 绑定表达式 |
| --- | --- |
| `ledger-data/.../SqlDelightConfirmedManualExpenseCommitPort.kt:150` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightConfirmedManualIncomeCommitPort.kt:92` | `it.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightConfirmedManualTransferCommitPort.kt:150` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightConfirmedManualLendingCommitPort.kt:211` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightImportSpineStore.kt:1312` | `it.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg06Store.kt:773` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg08Store.kt:349` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg09Store.kt:323` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg10Store.kt:300` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg11Store.kt:298` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg12Store.kt:461` | `version.times.statisticsAt.toString()` |
| `ledger-data/.../SqlDelightRg07Store.kt:698`（及 `:1129`） | `input.occurredAt.toString()`（`occurred/statistics/effective` 三列同值） |
| `ledger-data/.../SqlDelightTransactionCorrectionCommitPort.kt:79/157/170` | `requestSnapshot.statisticsAt.toString()` |

该形实例（测试/夹具实读）：`"2026-03-05T00:00:00.500Z"`（`SqlDelightConfirmedManualLendingCommitPortTest.kt:205-206`）、`"2026-05-01T01:00:00Z"`（`P705EffectiveSurfaceTest.kt:52`）、`"2026-09-15T00:30:00.5Z"`（`ParseManualExpenseOccurredAtTest.kt:88`，一位小数）。

**(b) 原始文本透传（来源 JSON 原样字符串，带偏移量 `+08:00`）：**

| 写入者（file:line） | 透传来源 |
| --- | --- |
| `ledger-data/.../SqlDelightRg03TransferStore.kt:1056-1058` | `persistFormalTransaction(..., originalTimeText)`，`originalTimeText` 来自 `Rg03OperationAdapter.kt:111` 的原始 JSON 值 |
| `ledger-data/.../SqlDelightRg04ImportStore.kt:763` | `persistFormal(aggregate.formalTransaction, source.observed_at)`（`:303`），`observed_at` 为原始字符串 |
| `ledger-data/.../SqlDelightRg04Store.kt:355` | 透传 `time` 参数（原始字符串） |
| `ledger-data/.../SqlDelightRg05Store.kt:880-882` | `occurredAtText`/`statisticsAtText`/`effectiveAtText`（`Rg05Operations.kt:47`/`:53` 的 `paymentAtText`/`statisticsAtText`，原始文本） |

该形实例（夹具实读，`golden/rules-v2/rg-10.json` 与 RG-03/04/09 夹具）：`"2026-01-21T11:00:00+08:00"`、`"2026-01-01T00:00:00+08:00"`。

**(c) 另注：** `Rg11Operations.kt:1648-1667` 与 `Rg12Operations.kt:1393-1412` 的 `localDateTimeText` 手工构造 `yyyy-MM-ddTHH:mm:ss±HH:mm`（无小数秒），该文本进入**快照列**（如 `Rg11Operations.kt:783` 的 `statisticsAtText`）；`transaction_version.statistics_at` 由 (a) 路径写入，但该函数证明本仓存在多种时间文本构造器。

### 6.3 字典序失效的具体反例【已验证事实】

- **同刻不同形：** `"2026-01-21T11:00:00+08:00"`（(b)）与 `"2026-01-21T03:00:00Z"`（(a)）是**同一瞬时**，但字符串不同；`'+'`(0x2B) < `'Z'`(0x5A)，故偏移形**恒排**在 `Z` 形之前，与实际时间序无关。
- **同形不同精度：** `"2026-09-15T00:30:00.5Z"` 与 `"2026-09-15T00:30:00.500Z"` 是**同一瞬时**，但 `.5Z` 与 `.500Z` 字节不同；`'.'`(0x2E) < `'Z'`(0x5A)，故 `"...00.5Z"` 排在 `"...00Z"` 之前——**晚于**它，字典序与时间序**相反**。
- **结论：** 直接比较原始 ISO 字符串**不能**作为时间序，**不得**上线。

### 6.4 推荐方案【冻结设计提案】

1. **新增数值时间投影列**：在 `transaction_version` 上新增一个可重建的**整数 epoch 微秒**列（如 `statistics_at_epoch_micros INTEGER`），保留原始 `statistics_at TEXT` 列**不动**（版本原始时间不改写）。
2. **范围索引**：建立 `(ledger_id, statistics_at_epoch_micros, transaction_id)` 范围索引，支撑 `[start, end)` 有界月读。
3. **回填**：迁移中把既有全部 `statistics_at` 解析并写入投影；解析失败的行**整迁移回滚**（fail-loud，不留半投影）。回填须覆盖 §6.2 全部写入者产生的**全部历史格式**（(a) 与 (b)）。
4. **原子维护**：**每一条** `INSERT INTO transaction_version` 路径（`Ledger.sq:1996 insertTransactionVersion`、`:9917 copyCurrentVersionWithNewPostingSet`、`:9938 copyCurrentVersionReusingPostingSet`，以及 §6.2 全部 Kotlin 调用点）在**同一事务**内同时写入投影；修正版本（P7-05 的 `copyCurrentVersion*`，`:9923`/`:9944`）取请求的 `:statistics_at` 同步投影。
5. **缺投影 fail-loud**：读路径若遇到投影为 `NULL`/缺失，返回**明确失败**，**绝不**回退到原始 TEXT 比较，**绝不**静默当零。
6. **有效性谓词**：基于 `transaction_effective_state`（§1.6）联接；void/restore **不**复制第二套状态规则。

### 6.5 设计门替换条件【冻结设计提案】

若在裁决时能提出**更简单且已证明等价**的规范化方案（例如对全部写入者统一并证明可逆的规范化文本），**可在设计门替换**本推荐；替换须满足：对 §6.2 全部写入者与全部历史格式证明「规范化后字典序 == 时间序」、证明可重建/可回填、证明无精度损失（保留秒与亚秒）。**不得**仅凭「有索引」或「UI 出现进度」宣称通过。

## 7. 切片与验收（P707-A01..A09）

【设计交付，无测试在本批运行】。验收 ID 取计划 §7.3（计划 `:246-254`）；下表把每个 ID 映射到设计条款并声明**闭合所需证据**（实现/测试属后续实施批）。

| 验收 ID | 设计条款 | 闭合所需证据（后续实施批） |
| --- | --- | --- |
| P707-A01 | §2.1/§2.2（#2/#6）、§4 | 预算100，支出120，退款30→净用90、剩余10；支出80退款30→净用50；跨月退款只影响退款统计月——纯函数向量 + 有界读集成 |
| P707-A02 | §2.2 全表、§5.1 | 转账本金1000+费用2→预算2；借出/收回本金、收入利息不占额度；16 kind 逐项断言，特殊排除可见且 Golden 不变——kind 矩阵测试 + Golden 回归 |
| P707-A03 | §3.1/§3.2 | 父分类两叶30+40→父70；子预算只看自身；总额70不因父子都设置变140；另有无分类5则总75——配置 + 聚合向量 |
| P707-A04 | §2.1、§6.4 #6 | 金额/分类/统计月修正、void、restore→只计当前有效结果一次，旧版本不双算；来源时间与对账不变——P7-05 有效面 + 跨月修正测试 |
| P707-A05 | §3.3、§4 | 零额度、未设置、净退款负值、恰好额度、负额度输入、Long 溢出、坏目录/读失败均有明确结果——类型化失败矩阵 |
| P707-A06 | §3.4/§3.5 | 同请求 replay、同 ID 异快照、并发 CAS、分类停用/删除、Unknown 后重开→不丢配置/历史，无重复效果——claim/CAS/引用删除/原子回滚/Unknown 回执测试 |
| P707-A07 | §6.2–§6.5 | 月界前后亚秒、不同精度/偏移的等值时间、闰月、设备时区变化→上海月份不变，新旧投影一致——投影正确性 + 新旧对照测试 |
| P707-A08 | §5.1、§6.4 #2 | 20k/50k 正式交易、跨月与多版本、多个父子预算→无逐预算全账读取；记录扫描/返回行、查询数、峰值内存、连接持有与端到端响应——规模测试 + 匿名基线 |
| P707-A09 | §3.4/§3.5、§5 | 历史月改额度、分类改名/停用、重开/备份恢复→历史可追溯、稳定归属，余额/对账零副作用——往返 + P7-06 集成 |

测试导航（后续新增，非本批运行）：T07 现有锚点见计划 §10.5 的 T07/T07-T08 行（计划 `:439-445`）——`MonthlyBucketsTest.kt`、`QueryMonthlyActivityTest.kt`（月界、六类普通/十类特殊、退款/修正、精确溢出与读失败）、`P705EffectiveSurfaceTest.kt`、`P705CorrectionCommitPortTest.kt`、`P705VoidCorrectionMigrationV30ToV31Test.kt`（有效谓词、跨月修正、replay 优先 CAS 与迁移）、`SqlDelightCatalogStoreTest.kt`、`CatalogV27ToV29MigrationTest.kt`（目录事务与历史），须**新增**总/父/子预算配置及执行矩阵、月份投影正确性/规模、预算 owner 的 claim/CAS/引用删除/原子回滚/Unknown 回执。

性能门在 07.T 按**匿名基线**冻结，不能只以「SQL 有索引」或「UI 出现进度」证明通过（计划 §7.3 收尾，计划 `:256`）。

## 8. 开放项（本文未冻结，须裁决）

1. **投影规范化选择**：§6.4 的 epoch 微秒 INTEGER + 索引是推荐；§6.5 的更简单等价方案是否采用，**OPEN**。
2. **索引精确列**：`(ledger_id, epoch_micros, transaction_id)` 是否最优（是否含 `statistics_at`、是否 partial index 只覆盖有效交易），**OPEN**，须以 P707-A08 规模测量定夺。
3. **既有月卡是否切换新读取路径**：§5.1 要求「必须列明并用对照测试证明输出不变」；**是否切换**、以及切换范围，**OPEN**，不得随本批顺手改变既有读契约。
4. **schema 版本号**：v32（`31.sqm`）是下一个候选，**本文不分配**；由裁决后的实施批按当前迁移链分配。
5. **配置用例/端口实签名**：`SaveBudget`、`QueryBudgetMonth`、`MonthlyContributionReadPort` 的精确签名与失败码族，**OPEN**，由实施规格核验。
6. **产品选择未决**：UI 是否提供「总预算」单独进度视图、历史月编辑入口的确认文案、超支提示的呈现形态——**OPEN**（属 07.D 后续切片）。
7. **预付/储值完整消费预算**：另立契约的边界与命名，**OPEN**（本文明确不声称其领域预算为零）。
8. **Q15/Q16 审批**：本文的计量矩阵与配置模型是**提案**；计划 §10.6 列明 Q15 需补「确认普通预算名称和领域预算区别；07.T 精确时间投影/索引/全部写入维护和金额对照」，Q16 需补「设置/关闭的确认快照、CAS、历史编辑/停用分类 UI 与引用删除约束」（计划 `:453-454`）。裁决前不授权实现。

## 边界断言

- 本文是 `proposal`，不授权实现、迁移、技术选型或发布；审批是独立裁决。
- 本文不修改任何既有决定、已批准规格、`DECISIONS.md` 或代码。
- 预算绝不创建 Posting、绝不改变余额/对账；Golden `budget_effect` 与 `rgXX_` 竖井零改动。
- 全部金额为精确整数 minor units；禁浮点、禁 `SUM(DISTINCT amount)`。
- 有效谓词保持 `transaction_effective_state` 唯一 SQL 定义点，预算读不另立第二套状态规则。
- 示例全部匿名合成；无本机绝对路径、个人数据或工具轨迹。
