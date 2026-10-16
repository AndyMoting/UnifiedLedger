# P7-05 转账（ACCOUNT_TRANSFER）修错/作废/恢复第二切片设计门候选规格

状态：proposal（2026-10-16 起草；本文是 P7-05 第二切片「转账 `ACCOUNT_TRANSFER`」的**设计门候选规格**。**起草阶段不自行宣布 `approved`**——本文转为 `approved` 的**批准依据将是** `docs/DECISIONS.md` **D-228**（本批登记），转换以**独立规格评审闭环 + 主会话对 J1/J2/J3 开放项的裁决**为前提。本文只冻结**设计级**语义，**不**构成产品行为、迁移、技术选型或发布授权；实施属后续独立实施批。本文不写实现、不写迁移文件、**不分配 schema 版本号**。）

**Revision:** draft-1（2026-10-16）。初稿。单一写者起草于隔离 worktree（分支 `UL-p7-05-transfer-design`，基点 = `cea2940d3167f988a1a7c48d89a4acdff14cc82f`）。tracked 行号为该基点实读行号，**引用时以符号为准重新定位**（行号会随提交漂移）。`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`、`docs/PROJECT_STATE.local.md` 等 `.local.md` 为主 checkout 的本地只读文件，凡引用一律标注「计划」/「checkpoint」并以其主 checkout 实读行号为准。示例全部匿名合成；不复制大段产品代码；不写本机绝对路径、个人账务数据或工具轨迹。

**标记约定：** 全文用【已验证事实】标注本次在基点实读代码/文档所得的现状；用【本次会话内推导】标注本会话内推导、非仓库冻结权威的结论（附可定位 file:line）；用【开放项】标注须主会话裁决、本文不自行裁决的项。**冻结项以「冻结」字样标注**（形状类另标「冻结形状」，要求类另标「冻结要求/冻结约束」等）；**未带任何「冻结」字样者一律为建议或待裁决项**。凡 J1/J2/J3 覆盖处，即使含「冻结」字样也只冻结**已由 D-156/D-158 批准或 substrate 事实**的部分，候选 A/B 的裁决本身不因该字样被视为已裁决（§11、边界断言）。

## Scope

冻结 P7-05 第二切片「转账 `ACCOUNT_TRANSFER` 的修正 / 作废 / 恢复」的设计候选面。第一切片（手工创建 `EXPENSE`/`INCOME`）已由 D-156 批准、D-158 登记实施，其冻结面（`docs/specs/2026-09-19-p7-05-correction-void-recycle-design.md`，approved）**不在本文重开**；本文只在其上新增转账切片，并承接 D-156 **DP-2**（转账为后续独立切片）与 D-156 **DP-10 收窄**（D-113 `P408CorrectionCommitPort` 的组合授权延后至转账切片单独裁决）。

计划 §5 的 05.A 行（计划 `:120` 实读）要求「逐类型列出可编辑字段、可作废/恢复条件、依赖关系；先对普通支出/收入作窄切片，转账/借贷/导入及复杂关联分别列出后续切片」，并明写「不支持类型显式说明；**未交付切片继续 OPEN，不能用普通支出通过宣布全批完成**」。本文即该行「转账」列的实例化。

## 非目标（本文不授权）

- **不授权任何实现**：零产品代码、零测试、零 schema/迁移/依赖；本文不创建、不修改任何 `.kt`/`.sq`/`.sqm`。
- **不分配 schema 版本号**：本文只冻结**形状**，迁移边与版本号在实施批分配（沿 D-187/D-225 设计门先例；当前 schema = v35，34 个 `.sqm`【已验证事实：`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/` 下 34 个 `.sqm`；`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/CatalogV27ToV29MigrationTest.kt:24` 断言 `LedgerDatabase.Schema.version == 35`】）。
- **不裁决 J1/J2/J3**：可改字段集与腿失效语义（J1）、借贷/导入关联/退款切片保持 OPEN（J2）、DP-10 是否在本切片组合（J3）——全部列为**待主会话裁决的开放项**（§11）。
- **不改既有冻结面**：不改 D-156/D-158 的任何裁决；不改第一切片支持矩阵的 `EXPENSE`/`INCOME` 行；不改 `transaction_effective_state` 的「唯一 SQL 定义点」地位；不改 `lending_position_history` append-only 硬墙、对账 owner guard、导入终态守卫。
- **不声称任何验收向量 PASS**：本批零实施，§10 的 T-01..T-14（含 T-03b）全为未来实现/验证要求。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为基点 `cea2940d3` 实读行号；计划/checkpoint 行号为主 checkout 实读）：

- **主 checkout 根 `AGENTS.md`**（任务书以绝对路径点名，写作时已实读）：`Load By Task`、`Non-Negotiable Boundaries`、`Change And Review Routing`。工作树内**不得**创建或复制任何嵌套 `AGENTS.md`。
- **项目 harness skill**（任务书点名 `unifiedledger-harness`，主 checkout 内实读）：高风险路由（本批涉及账务/架构/迁移，属高风险）、角色拓扑、`Non-Negotiable Boundaries`。
- **第一切片设计规格（approved，D-156）**：`docs/specs/2026-09-19-p7-05-correction-void-recycle-design.md`——§3.1 支持矩阵 `ACCOUNT_TRANSFER` 行 `:102`【已验证事实：`:102` = `| \`ACCOUNT_TRANSFER\` | 待定 | 待定 | 待定（含手续费腿） | 待定 | 后续切片 | DP-2 |`】、§3.2 修正契约、§3.3 作废与恢复契约、§3.4 读路径与回收站、§3.6 有效派生面枚举（`:147-171`）、§6 决定点表（`:260-279`）、§9 本批不做（`:302`）。
- **决定**：D-156（`docs/DECISIONS.md:2947-2969`，Q11/Q12 与 DP-1..DP-13；DP-2 转账为后续切片、DP-4 借贷保持 OPEN、DP-5 导入候选终态、DP-10 收窄、DP-13 否决）；D-158（`:2987-3017`，片 1a 基底层交付与边界，含「转账切片必须收窄 DP-10 保守占位」具名残余）；D-187（`:3749-3769`）与 D-225（`:4645-4684`）设计门批准先例（只写规格与决定登记，零产品代码/零测试/零 schema/零迁移/**不分配 schema 版本号**）；D-219（`:4498-4518`，迁移批次清单；本批不分配版本号，但新切片所需 schema 面须登记为后续实施批承接项）。
- **账务规则** `docs/ACCOUNTING_RULES.md`：`:52-56`（转账基础：一对一账户互转、本金不计对外收支、手续费独立，含 `:56` 的 60/59/1 示例）、`:271-275`（手工转账 P7-02.B 不变量；`:273` = 手续费 > 0.00 须有当前目录可用二级支出分类、= 0.00 不得携带费用分类；`:275` = 本金两腿不计入对外收入或支出、手续费计入普通支出并按其资金腿独立对账）、`:243`（对账状态不改余额/报表）、`:249`/`:251`/`:253`（修正与失效/未变化腿对账保留）、`:299`（P7-03 月度口径，含有效谓词与「本金互转不入普通收支」）。
- **开发与验证** `docs/CONTRIBUTING.md`：「文档规则」节的 specs 状态标记条目（`:327`【已验证事实：`:327` = 「新建或实质修改的 `docs/specs/` 设计…必须标记为 `approved`、`proposal`、`superseded` 或 `historical`」】）、「文档验证」、「本机与 CI 的验证分工」、「Schema 迁移批次清单」（`:270`）。
- **阶段计划** `docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §5（`:114-127`；05.A 行 `:120`）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；`transaction_effective_state` 是有效谓词**唯一 SQL 定义点**；`lending_position_history` append-only 触发器（`Ledger.sq:264`/`:266`【已验证事实】）、对账 owner guard 与导入终态守卫不得绕过或放宽；金额一律精确整数 minor units，禁浮点；不引入新依赖。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 转账今天不可修正、不可作废（两道 kind 门 + 一处硬编码）

- **修正 kind 门**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/TransactionCorrection.kt:46` `isP705CorrectionSupportedKind` = `kind == TransactionKind.EXPENSE || kind == TransactionKind.INCOME`【已验证事实】；持久化端同款门 `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightTransactionCorrectionCommitPort.kt:101-103`（`if (!isP705CorrectionSupportedKind(kind)) → P705_KIND_NOT_SUPPORTED`）【已验证事实】。
- **作废 kind 门**：`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightTransactionVoidCommitPort.kt:91-93` 同款门【已验证事实】。
- **恢复重校验硬编码**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/OrdinaryVersionRevalidation.kt:31` 复用同一 kind 门，`:45-46` = `if (kind == TransactionKind.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME`【已验证事实】——**转账直接不可用**（`ACCOUNT_TRANSFER` 会在 `:31` 被拒；即便越过，`:45-46` 的二元硬编码也无法表达「两端真实资产账户 + 手续费分类」）。
- **结论**：转账的修正/作废/恢复在**今天**是**类型化拒绝零写入**，非「无效果静默通过」。本切片必须扩展这三处（§8.2）。

### 1.2 转账的 posting 形状：两腿与三腿两种，手续费腿带分类

- **三腿（fee > 0）**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/AccountTransfer.kt:187-202`【已验证事实】：`:191` = `TransferPostingRole.PRINCIPAL_OUT`、`:195` = `PRINCIPAL_IN`、`:199` = `FEE` 且 `:200` 带 `command.feeCategoryId`。角色枚举 `:28-32`（`PRINCIPAL_OUT`/`PRINCIPAL_IN`/`FEE`）。
- **手续费分类准入**：`AccountTransfer.kt:256-286`【已验证事实】：`:273` 须 `CategoryKind.EXPENSE`、`:274` 须 active、`:275-277` 父分类须存在且为一级 EXPENSE、`:279-281` 过账账户须 `AccountKind.EXPENSE`、非真实账户、同币种。
- **两腿（fee == 0）**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OwnAssetPrincipalTransfer.kt:94-104`【已验证事实】（仅 `PRINCIPAL_OUT`/`PRINCIPAL_IN`，无分类）。
- **金额关系**：`AccountTransfer.kt:161` = `command.sourceDebit.minorUnits != creditedWithFee → AmountsMustBalance`（转出 = 到账 + 手续费）【已验证事实】。

### 1.3 产品分发与请求快照

- **分发**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ManualTransferTransactionFactory.kt:49-115`【已验证事实】：`:49` 起 `if (request.fee.minorUnits > 0L)` → `:54` `createOwnAssetAccountTransfer`（三腿）；`:84` 起 `fee == 0` → `:85` `createOwnAssetPrincipalTransfer`（两腿），且 `:82` 在 fee == 0 时拒绝携带分类（`TransferFeeCategoryNotAllowed`）；`:44` 负手续费类型化拒绝；`:46-47` 求和溢出检查。违规映射 `:120-145`【已验证事实】。
- **请求快照**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedManualTransfer.kt:31-42`【已验证事实】字段 = `sourceAccountId`/`destinationAccountId`/`destinationCredit`/`fee`/`feeCategoryId`/`occurredAt`/`note`/`tagIds`/`merchantId`；`:22-24` KDoc 明写「source debit 派生为 `destinationCredit + fee`，产品请求从不接受独立 source amount」。
- **产品可达性**：手工转账 `android-app/src/main/kotlin/com/unifiedledger/android/App.kt:1293`、`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:968`（`TransferFlowFormalFactory`）；手工链 `Main.kt:851`（`SqlDelightConfirmedManualTransferCommitPort`）【已验证事实】。**导入**创建的转账亦产品可达：`TransferFlowFormalFactory.kt:185` → `createOwnAssetPrincipalTransfer`，绑定校验 `:299-304` 断言 `kind == ACCOUNT_TRANSFER` 且恰 2 腿【已验证事实】。
- **持久化形状**：`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:179-194` `manual_transfer_request`（`:187` `fee_minor`、`:188` `fee_category_id`、`:184` `destination_credit_minor`）与 `:196-207` `confirmed_transfer_receipt`（`:200` `transaction_id TEXT NOT NULL UNIQUE`）【已验证事实】。

### 1.4 修正请求表无法承载转账目标状态

- `Ledger.sq:10279-10294` `transaction_correction_request`【已验证事实】列 = `transaction_id`/`expected_current_version_id`/`note`/`statistics_at`/`amount_minor`/`currency_code`/`currency_precision`/`category_id`/`funding_account_id`/`confirmation_marker`——**无第二账户列、无手续费列、无手续费分类列**。转账修正无法以现形状表达（§8.3）。
- 修正回执 `:10296-10310` `transaction_correction_receipt`（`version_id UNIQUE`、`transaction_id` 不唯一）可复用【已验证事实】。

### 1.5 作废/恢复是**交易粒度**，不是腿粒度

- `Ledger.sq:10215-10233` `transaction_void_fact`【已验证事实】：`:10226` `PRIMARY KEY (ledger_id, transaction_id, sequence)`；`:10220` `fact_kind IN ('void','restore')`；`:10221` `reason_code` 五值 CHECK；`:10228` `UNIQUE(ledger_id, request_id)`。
- 深度守卫紧随其后：`:10253-10257` 序列守卫（下一事实 = max + 1）、`:10258-10269` 交替守卫（sequence 1 必为 void、sequence 2 必为 restore、`> 2` ABORT `void cycle exhausted`）、`:10270-10273` update/delete 双 ABORT【已验证事实】。
- **推论（J1 2b 的 substrate 现实）**：作废事实以 `(ledger_id, transaction_id)` 为粒度，**无法表达「仅手续费腿作废」**。任何腿粒度语义必须落在「修正的新 posting set」或「新 schema 面」（§4.2、§11 J1）。
- **有效谓词唯一定义点**：`Ledger.sq:10316-10323` `CREATE VIEW transaction_effective_state`【已验证事实】（最新事实非 void 即有效，无事实即有效）。

### 1.6 对账/证据行的产生面：只有 ACCOUNT_TRANSFER 资格面，且产品写入面为零

- **DP-10 探针**：`Ledger.sq:10672-10684` `matchedCurrentVersionReconciliationCount`【已验证事实】（当前版本上任一对账行即计数）。
- **P408 完整性查询**：`Ledger.sq:9027-9039` `selectP408PostingIntegrity`【已验证事实】，条件 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'` 在 `:9038`。
- **P408 端口**：接口 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/P408CorrectionCommitPort.kt:171`【已验证事实】；实现 `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightCorrectionStore.kt:26-28`【已验证事实】。失效原因码 `P408CorrectionReason.POSTING_REPLACED`（`P408CorrectionCommitPort.kt:28`）【已验证事实】；`evidence_link_history.reason` CHECK 在 `Ledger.sq:8469`（`('confirmed','posting_replaced','corrected')`，三值）【已验证事实】。**注意**：`Ledger.sq:7754` 的同名值域 `('EXACT_EVIDENCE','POSTING_REPLACED')` 属 **`rg12_reconciliation_match_history`**（建表 `:7749`），**不是** `evidence_link_history`——第一切片规格旧引的 `:8055` 亦非该表；两者均不得以 `evidence_link_history` 冠名（本条已按表身份重新定位）。
- **种子面**：fresh `Ledger.sq:9103-9122`【已验证事实】——`:9103-9104` 为注释，`:9105`/`:9112`/`:9119` 为三条 INSERT，条件均为 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'`，**空库 no-op**（无 ACCOUNT_TRANSFER posting 时零行）。迁移种子 `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/22.sqm:134-152`【已验证事实】（v22→v23，`:135`/`:143`/`:150` 三条 INSERT，条件 `:141`/`:149` 同款）。
- **手工转账提交端口零写对账/证据**：`SqlDelightConfirmedManualTransferCommitPort.kt:51-230` 只发 **11 条**命名查询（`claimManualTransferRequest`/`deleteManualTransferRequest`/`insertConfirmedTransferReceipt`/`updateManualTransferRequestAnnotation`/`insertPostingSet`/`insertTransaction`/`insertTransactionVersion`/`insertTransactionCurrentVersion`/`insertPosting`/`lastStatementChangedRowCount`，以及等价 replay 比对用的 `selectCommittedManualTransferRequest`）【已验证事实】，**零写 `posting_reconciliation`/`evidence_link`**。
- **导入 spine store 零写**：`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportSpineStore.kt` 对 `posting_reconciliation`/`evidence_link` 命中数 **0**【已验证事实：`grep -c` exit 1】。
- **触发器面零写**：以正则遍历 `Ledger.sq` 全部 `CREATE TRIGGER … END;` 块（526 块）得 `INSERT INTO posting_reconciliation` 命中 **0**、`INSERT INTO evidence_link` 命中 **0**【已验证事实：本次 `python` 正则扫描】。
- **诚实推论（重要）**：`posting_reconciliation` 行**只**由 v22→v23 迁移种子（`22.sqm:143`）与 fresh 种子（`Ledger.sq:9112`）产生，条件都是**当时的** ACCOUNT_TRANSFER posting。因此：**新库且无转账 → 零行**；**经 v23 迁移且当时已有 ACCOUNT_TRANSFER posting → 该批 posting 有 PENDING 行**；**v23 之后由产品路径新建的转账（手工或导入）→ 零行**。DP-10 的「受影响资金腿」收窄对**有行的那批**才有实际意义。
- **组合根零接线**：`grep` 在 `android-app/src`、`desktop-app/src`、`app-ui/src` 对 `P408ReconciliationCommitPort`、`SqlDelightCorrectionStore`、`P408CorrectionCommitPort` **三处均零命中**【已验证事实：三条 grep exit 1】。

### 1.7 回收站与月度口径

- **回收站投影**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/QueryRecycleBin.kt:114-152`【已验证事实】逐行三次 SQL 往返（退款链、导入创建、手工创建），`restoreRejectionCode` 优先取退款码（`:138`），否则取目录重校验结果。D-158 第 4 条已登记「回收站读每行三次 SQL 往返」为 O(rows) 成本。
- **月度口径**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/MonthlyBuckets.kt:345-357` `OrdinaryFlowClassification.ORDINARY_KINDS` **含** `ACCOUNT_TRANSFER`（`:350`）【已验证事实】；`:136` `bucket.transactionCount += 1`（对**所有** kind 计数）；`:138` `if (!isOrdinary(row.kind)) continue`【已验证事实】。
- **转账在月度中的实际效果**：本金两腿走 ASSET 账户（不入普通收支），手续费腿走 EXPENSE 过账账户（入普通支出），与 `ACCOUNTING_RULES.md:275` 一致；但 `transactionCount` 与 `countByKind[ACCOUNT_TRANSFER]` 仍计该笔【已验证事实：`:136`/`:137`】。故作废转账必须在**全部**有效派生面移除，不能只处理手续费腿。
- **退款只可指向 EXPENSE**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/RefundReceipt.kt:162` `original.kind != TransactionKind.EXPENSE → InvalidRefundReceipt`【已验证事实】→ DP-13 探针对转账**必然为空转**（§5.2）。

### 1.8 版本追加原语只有三形态

- `ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/TransactionVersionAppend.kt`【已验证事实】：`TransactionVersionChange` 三形态 `Note`（`:30`）/`StatisticsAt`（`:34`）/`Postings`（`:38`）；`appendVersion`（`:47`）以 `currentVersion.copy(...)` 追加 `version_number + 1`，`occurred_at`/`effective_at` 逐字复制、仅 `statistics_at` 随请求变【已验证事实：`:94-99` 的 `times` 构造只对 `StatisticsAt` 形态改 `statisticsAt`】。**无 `occurredAt` 变更形态**（DP-7 保持 OPEN）。
- `reuseCurrentPostingSet` 写形标志：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/CorrectTransactionVersion.kt:120`【已验证事实】；D-158 第 4 条已登记「片 1b 不得把该标志接入用户可见判定」。

## 2. 支持矩阵（转账行）

### 2.1 转账行（本文冻结的候选；J1 覆盖项标【开放项】）

| kind | 备注 | `statisticsAt` | 金额 | 源账户 | 目标账户 | 手续费 | 手续费分类 | 作废/恢复 | 切片 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ACCOUNT_TRANSFER`（手工创建） | 可改【开放项 J1】 | 可改【开放项 J1】 | 可改（`destinationCredit`，`sourceDebit = destinationCredit + fee` 派生）【开放项 J1】 | 可改【开放项 J1】 | 可改【开放项 J1】 | 可改【开放项 J1】 | 可改【开放项 J1】 | 建议候选 A：交易粒度（三腿同进退）【开放项 J1，待裁决】 | **本文切片** |
| `ACCOUNT_TRANSFER`（导入创建） | 见 §5.1（J2 开放项） | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 | 同左 | **OPEN** |

- **冻结（不因 J1/J2/J3 裁决而变）**：**永不修改 kind**（含 `canonical_kind`）；修正后的交易保持原 kind、原 `occurredAt`、原创建身份与来源关系；`occurredAt` 不在可改字段集内（DP-7 保持 OPEN）。
- **J1 覆盖说明**：上表「可改字段集」**与**「两腿/手续费腿的失效、作废语义」两者**一并**列为【开放项】（§11 J1），写成「建议 + 备选/风险，待主会话裁决」，**不得**读作已裁决、冻结或按建议批准。
- 不支持类型/切片一律**类型化拒绝零写入**（`P705_KIND_NOT_SUPPORTED`/`P705_CREATION_LINEAGE_NOT_SUPPORTED`），不得静默 no-op；未交付切片继续 OPEN。

### 2.2 与第一切片矩阵行的关系

第一切片 `EXPENSE`/`INCOME` 行（第一切片规格 `:98-99`）**不改**。第一切片 `:102` 的 `ACCOUNT_TRANSFER` 行由本文承接，并已在第一切片规格内就地加指针（§13 指针维护说明）。

## 3. 修正契约（05.B 的转账实例）

- **流程（冻结，沿第一切片 §3.2）**：读当前有效版本 → 预览差异（纯读、零写入）→ 用户明确确认 → 分配新 requestId → claim-first 原子提交（`expectedCurrentVersionId` CAS）→ 权威回读。预览不得作为提交许可。
- **字段集（J1 覆盖，【开放项】）**：`note` / `statisticsAt` / `destinationCredit`（及派生 `sourceDebit = destinationCredit + fee`）/ `sourceAccountId` / `destinationAccountId` / `fee` / `feeCategoryId`。`occurredAt`（DP-7 OPEN）与 `kind`（永不修改）不在内。
- **写形（冻结形状 = 版本追加与 posting 覆盖机制；「金额/手续费是否属可改字段集」归 J1 待裁决，见 §11 DP-T3、§2.1）**：以当前版本为基追加 `version_number + 1`；**腿数不变时**（两腿→两腿、三腿→三腿）新 posting set 必须**逐币种平衡**且覆盖旧 set 每条 posting 恰好一次；**腿数减少时**（三腿→两腿，即 `fee > 0` → `fee == 0`）旧 `FEE` 腿**不是**必须覆盖项，新 set 只须覆盖旧本金两腿各一次并逐币种平衡（§8.1/§8.3、T-03）；**腿数增加时**（两腿→三腿，即 `fee == 0` → `fee > 0`）新 set 须覆盖旧本金两腿各一次、**新增**一条 `FEE` 腿并携带 `feeCategoryId`、逐币种平衡且 `sourceDebit == destinationCredit + fee`（T-03b）。三腿/两腿形状由**修正后的目标状态**决定（`fee > 0` → 三腿含手续费腿；`fee == 0` → 两腿且不得携带分类，沿 `ManualTransferTransactionFactory.kt:82`）。`occurred_at`/`effective_at` 逐字复制（`TransactionVersionAppend.kt:94-99` 的 `times` 构造只对 `StatisticsAt` 形态改 `statisticsAt`，其余形态 `else -> currentVersion.times` 原样复制）。
- **校验（冻结）**：新版本的账户/分类引用必须在写入事务内按**当前权威目录**重校验——两端账户须同一账本、同币种、`realAccount`、`ownedByUser`、`AccountKind.ASSET`、互不相同（沿 `AccountTransfer.kt:70-93` 与 `:112-175`）；`fee > 0` 时手续费分类须 active 二级 EXPENSE 分类、其过账账户须同账本隐藏 EXPENSE 账户、同币种（沿 `:256-286`）；金额须为币种精度的精确十进制；手续费不得为负（沿 `ManualTransferTransactionFactory.kt:44`）。
- **对账影响（J3 覆盖，【开放项】）**：受影响资金腿 = 新版本中真实资产分录且 `(accountId, amount, currency)` 相对旧版本变化者；未变化资金腿的对账行与证据链接零改动（沿 `ACCOUNTING_RULES.md:253`、D-048）。**是否在本切片组合 D-113 `P408CorrectionCommitPort` 见 §11 J3，本文不裁决。**
- **DP-10 保守占位必须收窄（冻结要求，非裁决）**：`SqlDelightTransactionCorrectionCommitPort.kt:110-120` 现为「当前版本上存在**任何**对账行即拒绝」；其注释明写「the transfer slice that wires DP-10 must narrow it to legs that actually changed, or a note-only correction would be rejected for no reason」【已验证事实】。本切片实施时必须收窄为**实际变化的资金腿**，否则仅改备注的转账修正也会被无理由拒绝（D-158 第 4 条具名残余）。收窄后的语义归 J3 裁决。
- **无效/无变化/幂等（冻结，沿第一切片 §3.2/§3.3）**：同 requestId 同快照 replay → `NoChange` 返回原 receipt、零新增；同 requestId 不同快照 → `RequestIdentityConflict` 零写入；`expectedCurrentVersionId` 非当前 → `StaleCurrentVersion`（`P705_STALE_CURRENT_VERSION`）零写入；**replay 判定先于 CAS 求值**。

## 4. 作废与恢复契约（05.C 的转账实例）

### 4.1 交易粒度语义（substrate 事实，非裁决；腿粒度语义归 J1 待裁决）

- **作废 = 追加一条不可变事实，使该转账从全部有效派生面移除**；不删除、不改写、不创建补偿交易。恢复 = 追加第二条事实使原转账重新有效；每笔最多一次作废 + 一次恢复（`sequence ≤ 2`）。
- **substrate 现实（J1 2b 的前提，必须写清）**：`transaction_void_fact` PK = `(ledger_id, transaction_id, sequence)`（`Ledger.sq:10226`）→ **作废是交易粒度，不是腿粒度**。三腿转账作废时，本金两腿与手续费腿**一起**从有效面移除；**无法**表达「仅手续费腿作废」。

### 4.2 腿粒度语义若需要，只能落在两处之一（【开放项】，J1）

- **候选 A（建议基线）**：交易粒度作废（三腿同进退）。代价：无新增 schema 面；与现有 substrate、守卫、视图、回收站读路径完全一致。风险：无法表达「只作废手续费」；但「只改手续费金额/分类」本质是**修正**（新 posting set），不是作废。
- **候选 B（备选）**：腿粒度语义。两条落地路径——(i) 落在**修正的新 posting set**（例如修正后 `fee == 0` 即丢掉手续费腿，`ACCOUNTING_RULES.md:273` 要求此时不得携带分类）；(ii) 新增**新 schema 面**（如 posting 级作废事实）。代价：(ii) 需新 PK/守卫/索引，且有效谓词必须**仍是** `transaction_effective_state` 单点——若引入 posting 级谓词即产生**第二定义点**，违反冻结面，须同时重开第一切片 §3.3 的「谓词只有一处定义」。风险：回收站/恢复的粒度语义、深度守卫、`sequence` 语义全部需重新定义。
- **本文不裁决 A/B**（§11 J1）。冻结要求：无论选哪条，`transaction_effective_state` 的**唯一 SQL 定义点**地位不得打破。

### 4.3 有效时间口径（沿 DP-3，D-156 批准）

作废**追溯移除**原转账在其 `statistics_at` 所属月份的效果；作废日不产生任何新的月份/现金流/分类效果。恢复同样不产生新效果，只让原转账按原 `statistics_at` 重新参与。转账的移除语义逐面见 §7.3。

### 4.4 前置条件（冻结，沿第一切片 §3.3）

- **作废**：交易存在且在当前账本；交易有效；交易为 `ACCOUNT_TRANSFER`；**不存在有效关联退款**（DP-13 语义；对转账见 §5.2 空转说明）；**尚未作废过**（DP-8 深度 ≤ 2）；必须明确确认；必须携带原因（类型化码 + 可选有界说明，沿 `VoidReasonCode` 五值、`VOID_REASON_NOTE_MAX_LENGTH = 200`，`TransactionVoidState.kt:68-89`【已验证事实】）。任一不满足 → 类型化拒绝零写入。
- **恢复**：交易存在、当前为作废态、尚未恢复过、目录重校验通过（§8.2）、必须携带原因、明确确认。
- **不写对账/证据/导入/借贷**：作废与恢复对 §7.4 的 owner 零写入。

## 5. 与 DP-5 / DP-13 的关系（J2 覆盖，【开放项】）

### 5.1 导入创建的转账

- **实读事实**：导入创建的转账产品可达（`TransferFlowFormalFactory.kt:185`，绑定校验 `:299-304` 断言 2 腿），其创建 lineage 由 `import_confirmation` 承载；修正/作废端口以 `importCreationConfirmationCountForTransaction`（`Ledger.sq:10631`）判 lineage【已验证事实】。今天该 lineage 一律拒绝（`SqlDelightTransactionCorrectionCommitPort.kt:104-106`、`SqlDelightTransactionVoidCommitPort.kt:94-96`）。
- **J2 开放项**：导入创建的转账**是否纳入本切片**（或保持 OPEN）须主会话裁决。本文**不裁决**、不静默缩小。备选与代价：(i) 保持 OPEN（沿 D-156 DP-5「原候选保持终态」的保守读法）——代价：导入转账仍不可修错；(ii) 纳入——须逐项裁决「作废后原导入候选能否再次确认」（D-156 DP-5 已裁「否」）与终态守卫（`import_candidate_status_history` 的 `import_status_history_transition_guard`，基点实读 `Ledger.sq:8678-8687`；D-156 原文的 `:8264-8273` 已随提交漂移，此处以符号为准）的兼容性。
- **DP-5 禁止隐式级联**：作废不改变导入候选终态、不触碰 `import_confirmation`/`import_receipt`/`import_candidate_status_history`。

### 5.2 DP-13 退款探针对转账是否为空转

- **实读事实**：退款只可指向 EXPENSE（`RefundReceipt.kt:162`）；DP-13 的产品路径探针 `productLinkedRefundCountForTransaction`（`Ledger.sq:10643`）经 `import_candidate_decision_snapshot.original_transaction_id` 关联【已验证事实】。由于退款原交易必为 EXPENSE，**对 `ACCOUNT_TRANSFER` 该探针恒返回 0**（空转）。
- **J2 开放项**：本文**不裁决**转账是否需要独立的关联退款语义；须主会话裁决并在规格内**显式保持 OPEN**，不得静默缩小（D-156 DP-13 已把「原交易作废后退款如何归属」登记为后续切片）。

### 5.3 借贷本金历史硬墙（J2，冻结不变量）

`lending_position_history` append-only（`Ledger.sq:248` 建表、`:264`/`:266` update/delete 双 ABORT 守卫）【已验证事实】。**本切片对 `lending_position`/`lending_position_history` 零写入**（D-156 DP-4 不变）。借贷（`LEND`/`COLLECT`）切片**保持 OPEN**，不得因转账切片交付而静默缩小或宣布 P7-05 全批完成。

## 6. 读路径与回收站

- **冻结约束（沿第一切片 §3.4）**：回收站读路径**不得发明第二事实来源**——从 `ledger_transaction` + `ledger_transaction_current_version` + `transaction_version` + `transaction_void_fact` 派生，与有效面共用同一有效谓词（`transaction_effective_state`）与同一行形状。
- **现状（实读）**：`QueryRecycleBin.kt:114-152` 对每行发三次 SQL 往返，其中目录重校验调 `revalidateOrdinaryVersionReferences`（`OrdinaryVersionRevalidation.kt:25`）——**对转账在 `:31` 即被 kind 门拒绝**，回收站会把转账行的 `restoreRejectionCode` 置为 `P705_KIND_NOT_SUPPORTED`【本次会话内推导：由 `QueryRecycleBin.kt:128-140` 与 `OrdinaryVersionRevalidation.kt:31` 推导】。
- **冻结要求**：本切片必须让转账行在回收站可列出（原因、作废时间、依赖说明）并可恢复；恢复前展示目录重校验结果（§8.2）。转账的「依赖说明」须含两端账户与手续费分类的当前名与可准入状态。
- **O(rows) 成本**：D-158 第 4 条已登记回收站每行三次往返；本切片**不得**继续放大（若新增转账专用探针，须按账本批量）。

## 7. 有效派生面枚举与相对 D-156 §3.6 的差异

### 7.1 D-156 §3.6 的漂移（【本次会话内推导】，附可定位 file:line）

D-156 §3.6（`:147-171`）枚举的 15 面（#1-#15）**全部存活、零消失**；其 file:line 引用**部分漂移**——`Ledger.sq` 锚（3 条：`:8713-8740`/`:8748`/`:9518-9519`）与 `SqlDelightLedgerCurrentStateReadAdapter.kt` 锚（6 条：`:47-74`/`:76-169`/`:191-192`/`:223`/`:242`/`:272-276`）确已漂移（该 adapter 由 416 行增至 597 行），而 `QueryMonthlyActivity.kt:92`、`QueryTransactionDetail.kt:38`/`:58`/`:104`、`QueryLedgerCurrentState.kt:64`/`:92`、`SummarizeLedgerActivity.kt:48`/`:99`、`QueryLedgerEntryRows.kt:16` 与 `23d3bd2` 逐字相同（`git diff 23d3bd2 HEAD -- <file>` 为空）【已验证事实】。引用一律以符号为准重新定位。本会话实读的当前锚点（以符号为准）：

| 符号 | D-156 记录 | 本会话实读 |
| --- | --- | --- |
| `currentVersionRowsForLedger` | `:8713-8740` | `:9131`（语句体止于 `:9162`） |
| `ledgerEntryRowsForLedger` | `:8748` | `:9174` |
| `catalogReferencedPostingAccounts` | `:9518` | `:10025` |
| `transaction_effective_state`（视图） | 视图在 D-158 记 ≈`:9719` | `:10316` |

### 7.2 D-156 之后**新增**的交易派生读面（N1-N5、N10 须移除效果；N2b、N6-N9 须保留；【本次会话内推导】）

| # | 符号 | 行锚 | 处置 |
| --- | --- | --- | --- |
| N1 | `projectedVersionsInStatisticsWindow` | `Ledger.sq:10344` | 已 join `transaction_effective_state`（`:10351`）；作废后须移除效果 |
| N2 | `monthlyContributionRowsInWindow` | `Ledger.sq:10381` | 已 join（`:10409`，`is_effective = 1`）；作废后须移除效果 |
| N2b | `voidedTransactionRowsForLedger`（回收站补集读） | `Ledger.sq:10421` | 已 join（`:10440`，`is_effective = 0`）；**须保留**（作废行必须可见，非移除对象） |
| N3 | 09.C widget 复用 N2 | 经 `MonthlyContributionReadPort` | 随 N2 移除效果 |
| N4 | `tagMerchantFilterRows` | `Ledger.sq:10942` | 已 join（`:10953`）；作废后须移除效果 |
| N5 | `tagMerchantAccumulationRowsInWindow` | `Ledger.sq:11063` | 已 join（`:11091`）；作废后须移除效果 |
| N10 | `productLinkedRefundCountForTransaction` | `Ledger.sq:10643` | 已 join（`:10649`）；作废后须移除效果 |
| N6 | `transactionEffectiveState`（注解写守卫） | `Ledger.sq:10876` | 不过滤；**须保留**（守卫读有效谓词，非效果面） |
| N7 | `transactionAnnotationHistoryForTransaction` | `Ledger.sq:11117` | 不过滤；**须保留**（注解历史） |
| N8 | `catalogReferencedTagIds` / `catalogReferencedMerchantIds` | `Ledger.sq:10829` / `:10832` | 不过滤；**须保留**（目录引用面） |
| N9 | 备份/校验面 | 经备份读路径 | 不过滤；**须保留** |

**须保留（历史/引用/请求身份/备份校验）的面**：#6 `transactionReconciliationLegs`（`Ledger.sq:9231`）、#7 `importCreationConfirmationByTransaction`（`:9209`）/`manualCreationReceiptByTransaction`（`:9216`）、#8 手工链 request/receipt、#13 `selectP408ReconciliationReport`（`:9073`）、#15 `catalogReferencedPostingAccounts`（`:10025`，有意不过滤）；新增面 N2b、N6-N9 见上表（均标「须保留」）。

**归 J3 面（本批不裁）的读面**：`selectP408PostingIntegrity`（`Ledger.sq:9027-9039`，条件 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'` 在 `:9038`）——**不 join** `transaction_effective_state`，故作废后的转账 posting 仍会被判为 D-113 invalidation-only 路径的合法目标（`SqlDelightCorrectionStore.kt:174-181`）。该查询是否须纳入有效谓词，取决于 J3（DP-10 是否组合 D-113），**本批不裁**；登记为 J3 子项。

**枚举完备性义务（沿 D-156 §3.6 R-1）**：本节的「须移除 / 须保留 / 归 J3」三分是本轮枚举的结果；**实施时必须在全部交易派生读面上重跑该枚举，任何新增消费者必须登记并纳入相应分类（不得遗漏、不得各自另写谓词）**——沿第一切片规格 R-1（`.md:294`）与 §3.6（`:151`「实施时若出现新消费者必须登记并纳入，不得遗漏」）的同一纪律。本规格不因本次枚举完成而免除此义务。

### 7.3 转账在 {#1-#5, N1-N5, N10} 的移除语义（J1 覆盖字段集；移除语义 = substrate 事实 + D-156 已批准口径，腿粒度归 J1 待裁决）

- **本金两腿**：走 ASSET 账户，本不入对外收支（`MonthlyBuckets.kt:341-343` 说明 ASSET/LIABILITY/EQUITY posting 不计普通收支）；作废后它们从余额面（#4 HOME 余额）移除。
- **手续费腿**：走 EXPENSE 过账账户，作废后从普通支出移除（`MonthlyBuckets.kt:145-158` 的 EXPENSE 分支；`ACCOUNTING_RULES.md:275`）。
- **计数面**：`transactionCount`（`MonthlyBuckets.kt:136`）与 `countByKind[ACCOUNT_TRANSFER]`（`:137`）必须在作废后移除该笔（沿 D-156 DP-3：作废交易不计任何有效计数与金额）。
- **#15 目录删除引用面**：**不过滤**（有意决定，沿第一切片 §3.6 #15）——作废转账的 posting 仍计为经济引用、继续阻止账户删除。

### 7.4 禁止写入的 owner（冻结）

| 分类 | owner | 本切片写入许可 |
| --- | --- | --- |
| 只读引用 | `ledger_transaction`/`transaction_version`/`posting_set`/`posting`/`ledger_transaction_current_version`；`manual_transfer_request`/`confirmed_transfer_receipt` | 不写 |
| 对账 owner | `posting_reconciliation`、`posting_reconciliation_history`、`evidence_link`、`evidence_link_history`、`evidence_projection`、`reconciliation_correction_snapshot` 及 guard 触发器 | 作废/恢复**零写入**；修正**仅按 J3 裁决面**（本文不授权） |
| 借贷本金历史 | `lending_position`、`lending_position_history`（append-only guard） | 零写入（DP-4） |
| 导入 owner | `import_confirmation`、`import_receipt`、`import_candidate_status_history` 与终态守卫 | 零写入（DP-5） |
| `rgXX_` 竖井 / golden | 全部 | 零改动 |

## 8. 领域 / 应用 / 持久化形状（只冻结形状，不分配版本号、不写迁移）

### 8.1 领域（`ledger-domain`）

- **复用**：`FormalTransaction.appendVersion`（`TransactionVersionAppend.kt:47`）与三形态 `TransactionVersionChange` 作为唯一版本追加原语；`TransactionVoidState.isEffective`（`TransactionVoidState.kt:44`）作为有效谓词的领域单点；`VoidReason`/`VoidReasonCode` 作为原因的唯一领域表达。
- **新增（形状冻结）**：转账修正的纯校验函数（两端账户 + 手续费分类 + 手续费过账账户准入、金额/平衡派生、受影响资金腿派生），**复用** `AccountTransfer.kt:256-286` 的手续费分类准入规则与 `:70-93` 的端点账户准入规则，**不得**再造第二套。
- **不改既有规则**：`AccountTransfer`/`OwnAssetPrincipalTransfer` 工厂、`RefundReceipt`、`LendingPosition`、`PostingFactsCorrection`（RG-12 校验器）一律不动。
- **腿数变化时的覆盖校验（冻结约束）**：转账修正的纯校验函数**不得**直接复用 `PostingFactsCorrection` 的等数覆盖分支——`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/PostingFactsCorrection.kt:95-96`（`replacementPostings.size != oldPostingIds.size → CompleteReplacementPostingsRequired`）与 `:113`（`seenSources != oldPostingIds.toSet()`）要求替换腿数恒等于旧腿数，仅对首切片的两腿形态成立；**两个方向均不得走该分支**：腿数减少（三腿→两腿，`fee > 0` → `fee == 0`）时旧 `FEE` 腿不属必覆盖项（§3 写形、T-03），腿数增加（两腿→三腿，`fee == 0` → `fee > 0`）时新 set 腿数多于旧 set、须覆盖旧本金两腿各一次并新增带分类 `FEE` 腿（§3 写形、T-03b）。

### 8.2 应用（`ledger-application`）

- **kind 门扩展（冻结要求）**：`isP705CorrectionSupportedKind`（`TransactionCorrection.kt:46`）必须扩展为含 `ACCOUNT_TRANSFER`（或新增转账专用谓词并由同一处调用），**不得**在两个端口各写一份。
- **恢复重校验替换（冻结要求）**：`OrdinaryVersionRevalidation.kt:31`/`:45-46` 的 EXPENSE/INCOME 二元硬编码必须替换为转账感知规则：**两端账户 + 手续费分类 + 手续费过账账户**。规则语义沿 D-156 DP-9 澄清——**改名但 active 可准入；停用/不可准入类型化拒绝零写入、交易保持作废**；不盲目恢复旧状态、不静默替换引用、不改写历史版本。
- **端口与用例（形状冻结，沿第一切片 §4.2）**：`CorrectTransactionVersion`/`VoidTransaction`/`RestoreTransaction`/`QueryRecycleBin` 复用同一结果族（`Created`/`NoChange`/`Rejected(code)`/`Conflict` + `StaleCurrentVersion`，`CorrectTransactionVersion.kt:140-155`）。
- **快照感知 resolver**：提交后异常按完整快照/receipt 判定 success/conflict/unknown；Unknown 不自动重试、不换 requestId。

### 8.3 持久化形状（`ledger-data`；只冻结形状，不分配版本号、不写迁移）

- **修正请求表扩展（形状冻结）**：`transaction_correction_request`（`Ledger.sq:10279-10294`）必须能承载转账目标状态。**既有列在转账行与普通行的取值/可空化（冻结形状，不得留给实施者猜）**：
  - `amount_minor`/`currency_code`/`currency_precision`：**复用**为「修正后的规范金额三元组」——普通行 = 修正金额；转账行 = `destinationCredit`（`sourceDebit` 为派生值，**从不单独存储**，沿 `ConfirmedManualTransfer.kt:22-24`）。**不**新增 `destination_credit_minor` 冗余列。
  - `funding_account_id`：**复用**——普通行 = 资金账户；转账行 = **源账户**（真实资产腿）。保持 NOT NULL。
  - `category_id`：**可空化**（迁移需重建表）。普通行仍恒非 NULL（写入事务内按 kind 强制）；转账行 `fee > 0` = 手续费分类 ID、`fee == 0` = NULL。**转账行的手续费分类一律存于此列，不另设 `fee_category_id`**（避免与 `category_id` 冗余同值；沿本段「不新增冗余列」原则）。可空化是本形状唯一的既有列约束放宽，其迁移代价 = 表重建（沿 D-219 加列/重建纪律）。
  - `note`/`statistics_at`/`expected_current_version_id`/`transaction_id`/`confirmation_marker`：语义不变，两类行同用。
  - **新增列**：`destination_account_id TEXT`（可空；普通行 NULL、转账行必填）、`fee_minor INTEGER NOT NULL DEFAULT 0`（普通行 0）。**不新增 `fee_category_id`**（手续费分类复用 `category_id`，见上）。
  - **写入期不变量（fail-closed，由端口在事务内强制；因请求表无 kind 列、无法用表级 CHECK 判别，故不设表级 CHECK）**：转账行要求 `destination_account_id` 非空、`fee_minor >= 0`、且 `category_id` 非空当且仅当 `fee_minor > 0`；普通行要求 `destination_account_id IS NULL`、`fee_minor = 0`，`category_id` 仍按普通行规则非空。
  - **若主会话偏好不同映射**（如新增独立 `source_account_id`/`destination_credit_minor` 而不复用既有列），须在本规格修订中冻结，**不得**由实施者临场选择；本段即为「实施批必须冻结的参数 + 判据」，默认采用上述复用映射。
  - 等价 replay 的**逐列比较**必须纳入上述全部列。沿 D-219 的加列纪律（`docs/CONTRIBUTING.md:270`）：新列带 Kotlin 默认值或把全部构造点纳入写范围；位置式 `INSERT` 改显式列清单；`columnInfo` 契约验证。
- **作废/恢复事实表**：**形状不变**（`transaction_void_fact` PK/守卫/索引全部复用）——这是 substrate 事实（作废事实以 `(ledger_id, transaction_id)` 为粒度，见 §4.1），**非** J1 裁决；腿粒度语义归 J1 待裁决。若 J1 裁向腿粒度（候选 B），须新增 schema 面并重开谓词单点——**本文不授权**。
- **手续费腿**：三腿转账的 `FEE` posting 在修正后的新 posting set 中按 `fee > 0` 保留、`fee == 0` 丢弃（沿 `ACCOUNTING_RULES.md:273`）。
- **迁移**：加性、只建结构零回填，fresh = migrated 逐字一致；**版本号在实施时分配**，本规格不预占；新切片所需 schema 面登记为后续实施批承接项（沿 D-219）。
- **对账面**：本文不新增对账写面；是否组合 D-113 见 §11 J3。

## 9. 失败码族

**复用（冻结，沿第一切片 §4.2；`P705FailureCode` 现 15 码）**：`P705_TRANSACTION_NOT_FOUND`、`P705_KIND_NOT_SUPPORTED`、`P705_CREATION_LINEAGE_NOT_SUPPORTED`、`P705_REFUND_LINKED_VOID_NOT_SUPPORTED`、`P705_FIELD_NOT_SUPPORTED`、`P705_STALE_CURRENT_VERSION`、`P705_TRANSACTION_VOIDED`、`P705_TRANSACTION_NOT_VOIDED`、`P705_VOID_CYCLE_EXHAUSTED`、`P705_VOID_REASON_REQUIRED`、`P705_CATALOG_REFERENCE_NOT_ADMISSIBLE`、`P705_MATCHED_FUNDING_LEG_CHANGED`、`P705_NO_CHANGE`、`P705_REQUEST_IDENTITY_CONFLICT`、`P705_CONSTRAINT_VIOLATION`。

**转账新增码（【开放项】，J1/J3 相关，本文不裁决）**——候选（仅在主会话裁决需要腿粒度或专门拒绝语义时才引入；否则一律复用上表）：
- 手续费腿与本金腿不一致的目标状态（如 `fee == 0` 却携带分类，或 `sourceDebit != destinationCredit + fee`）——**建议复用** `P705_FIELD_NOT_SUPPORTED`（沿 D-158 第 4 条对该码的扩展读法登记），避免新增 token。
- 腿粒度作废不被支持（若 J1 选候选 A 而请求表达腿粒度）——**建议复用** `P705_KIND_NOT_SUPPORTED` 或 `P705_FIELD_NOT_SUPPORTED`。
- 转账专门的对账面拒绝——沿用 `P705_MATCHED_FUNDING_LEG_CHANGED`。

**纪律**：code 稳定、message 不稳定不比较；不支持路径一律类型化拒绝零写入，不得静默 no-op。

## 10. 验收向量矩阵（T-01..T-14 与 T-03b；全为未来实现/验证要求）

| # | 场景 | 期望 | 关联 |
| --- | --- | --- | --- |
| T-01 | 三腿转账（fee > 0）金额修正 | 新版本三腿逐币种平衡、`sourceDebit == destinationCredit + fee`；旧版本保留；`occurred_at`/`effective_at` 逐字未变 | §3、§1.2、`ACCOUNTING_RULES.md:249` |
| T-02 | 两腿转账（fee == 0）改备注 | 仅 note 变；posting set 复用；零新增现金流；手续费分类不得被携带 | §3、§1.3 |
| T-03 | fee > 0 → fee == 0 修正 | 手续费腿从新 posting set 丢弃；分类引用一并移除；普通支出与 cash outflow 归零 | §3、§8.3、`ACCOUNTING_RULES.md:273` |
| T-03b | fee == 0 → fee > 0 修正（腿数增加，两腿→三腿） | 新 posting set 覆盖旧本金两腿各一次、新增 `FEE` 腿并携带 `feeCategoryId`、逐币种平衡且 `sourceDebit == destinationCredit + fee`；普通支出与 cash outflow 等于新手续费；分类须为当前目录可用二级 EXPENSE 分类 | §3、§8.3、`ACCOUNTING_RULES.md:273` |
| T-04 | 源账户/目标账户修正 | 两端须同一账本、同币种、`realAccount`、`ownedByUser`、ASSET、互不相同；否则类型化拒绝零写入 | §3、§1.2 |
| T-05 | 手续费分类停用/跨账本/非 EXPENSE | 类型化拒绝零写入（`P705_CATALOG_REFERENCE_NOT_ADMISSIBLE`） | §3、§8.2 |
| T-06 | 作废三腿转账 | 全部有效派生面（§7.2 的 {#1-#5, N1-N5, N10}）移除该笔；本金两腿移出余额、手续费腿移出普通支出、`transactionCount` 与 `countByKind` 移除；原转账在回收站可见 | §4.1、§7.3、D-156 DP-3 |
| T-07 | 作废后历史与来源保留 | 原版本、原 posting set、`confirmed_transfer_receipt`、`manual_transfer_request` 仍可读；无历史行被改写/删除 | §4.1、§1.3 |
| T-08 | 恢复恰好一次 | 恢复后逐值还原；第二次恢复类型化拒绝零写入 | §4.4 |
| T-09 | 恢复按当前目录重校验（转账） | 两端账户/手续费分类**停用**后恢复 → 类型化拒绝且交易保持作废；**改名但 active** 仍可准入 | §8.2、D-156 DP-9 |
| T-10 | 深度与循环守卫 | 恢复后再次作废 → `P705_VOID_CYCLE_EXHAUSTED`；跳号/重复/update/delete → ABORT | §4.1、§1.5 |
| T-11 | 对账 owner 零写入（作废/恢复） | `posting_reconciliation*`/`evidence_link*`/`evidence_projection`/`lending_position*`/`import_*` 行数与内容逐值不变 | §7.4 |
| T-12 | DP-10 收窄 | 仅改备注的转账修正**不得**被 `P705_MATCHED_FUNDING_LEG_CHANGED` 拒绝；仅实际变化的资金腿才构成失效对象 | §3、`SqlDelightTransactionCorrectionCommitPort.kt:110-120` |
| T-13 | 幂等、身份冲突与求值顺序 | 同 requestId 同快照 replay → 原 receipt；不同快照 → 冲突零写入；replay 判定先于 CAS | §3 |
| T-14 | 支持矩阵强制 | 导入创建转账（若 J2 判保持 OPEN）与借贷/退款/其余 kind → 类型化拒绝零写入 | §2.1、§5 |

- 自动项以 JVM 测试（domain/application/data）为主；设备项与 TalkBack 归后续实施批的设备验收。
- **本批零实施，T-01..T-14 与 T-03b 无一记 PASS，不声称任何向量通过。**

## 11. 决定点（J1/J2/J3 覆盖处一律「建议 + 备选/风险，待主会话裁决」）

| ID | 决定点 | 建议 | 备选/风险 | 状态 |
| --- | --- | --- | --- | --- |
| **J1** | 任务 2a + 2b：转账的**可改字段集**（备注/`statisticsAt`/金额/源账户/目标账户/手续费/手续费分类）**与**两腿/手续费腿的失效、作废语义，两者**一并** | 可改字段集 = §3；作废/失效 = 候选 A（交易粒度，三腿同进退） | 见 §4.2 候选 B（腿粒度）的代价；J1 是开放项，不得写成已裁决 | **待主会话裁决** |
| DP-T1 | `occurredAt` 是否可改 | 保持 OPEN（DP-7 不变） | 需新增域变更形态 + 统计/来源时间语义裁决 | 沿 DP-7 保持 OPEN |
| DP-T2 | `kind` 是否可改 | 永不修改（含 `canonical_kind`） | 无 | 冻结 |
| DP-T3 | 金额表示（J1 覆盖） | 建议：`destinationCredit` 可改、`sourceDebit = destinationCredit + fee` 派生（沿 `ConfirmedManualTransfer.kt:22-24`） | 若允许独立 `sourceDebit` 会破坏 `AR:275` 不变量 | **待主会话裁决（J1）** |
| **J2** | 任务 2e + 2g：本金历史硬墙不得破坏；借贷/导入关联/关联退款切片若本批不交付，必须**显式保持 OPEN** | 导入转账、借贷、关联退款均**保持 OPEN**，显式登记 | 纳入需逐项裁决（DP-5 候选终态、DP-13 退款归属） | **待主会话裁决** |
| DP-T4 | 导入创建的转账是否纳入本切片 | 保持 OPEN（保守读法） | 纳入须裁 DP-5 兼容性 | **待主会话裁决**（J2） |
| DP-T5 | DP-13 退款探针对转账是否空转 | 是（`RefundReceipt.kt:162` 只允许 EXPENSE 原交易） | 若未来允许转账退款须另立决定 | **待主会话裁决**（J2） |
| **J3** | 任务 2f：DP-10 对账/证据 port（D-113 `P408CorrectionCommitPort`）是否在本切片组合 | 见下三候选，本文**不自行裁决** | 三候选各有代价 | **待主会话裁决** |
| DP-T6 | DP-10 组合候选 | 候选 (a) **开放组合**：收窄占位后组合 D-113，处理有对账行的转账 | 候选 (b) **收窄**：仅收窄占位、不组合 port（金额修正后旧匹配残留）；候选 (c) **保持 OPEN**：本切片不做对账面 | **待主会话裁决**（J3） |

**J3 的实读事实（列全，供裁决）**：
1. D-113 `P408CorrectionCommitPort` 为 **ACCOUNT_TRANSFER 专用**：affected-posting 完整性查询以 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'` 为条件（`Ledger.sq:9027-9039`，条件在 `:9038`；同款条件见 v23 种子 `22.sqm:141`/`:149`）。
2. 手工转账提交端口零写对账/证据（`SqlDelightConfirmedManualTransferCommitPort.kt:51-230`，11 条命名查询，含 replay 比对用的 `selectCommittedManualTransferRequest`）；导入 spine store 亦零写；全部触发器块零写（526 块扫描，0 命中）。
3. 对账行**只**由 v22→v23 迁移种子与 fresh 种子为**当时的** ACCOUNT_TRANSFER posting 产生；空库 no-op。
4. port 未接入两端组合根——`grep` 在 `android-app/src`、`desktop-app/src`、`app-ui/src` 对 `P408ReconciliationCommitPort`/`SqlDelightCorrectionStore`/`P408CorrectionCommitPort` **三处均零命中**。
5. 无论选哪条候选，`SqlDelightTransactionCorrectionCommitPort.kt:110-120` 的保守占位**必须**收窄为实际变化的资金腿（D-158 第 4 条具名残余）。

## 12. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 作废是交易粒度，腿粒度不可表达 | 无法「只作废手续费腿」 | §4.2 明列 substrate 现实与两候选代价；J1 待裁决 |
| R-2 | 修正请求表缺转账列 | 现形状无法承载转账目标状态 | §8.3 冻结所需列形状；迁移在实施批 |
| R-3 | DP-10 保守占位过宽 | 仅改备注的转账修正被无理由拒绝 | §3 冻结「必须收窄为实际变化腿」；D-158 第 4 条残余；J3 待裁决 |
| R-4 | 恢复重校验硬编码 | 转账恢复在 `OrdinaryVersionRevalidation.kt:31` 即被拒 | §8.2 冻结替换要求 |
| R-5 | 有对账行的转账（v23 前遗留） | 修正后旧匹配残留（若不组合 D-113） | J3 候选 (a)/(b)/(c) 各自代价已列 |
| R-6 | 回收站 O(rows) 成本 | 转账行新增探针会放大 | §6 冻结「不得放大，须按账本批量」 |
| R-7 | D-156 §3.6 行锚部分漂移（`Ledger.sq` 与 adapter 锚漂移；`Query*` 应用层锚不变） | 引用失准 | §7.1 以符号重新定位；引用一律以符号为准 |
| R-8 | 新增 schema 边与迁移 | 版本断言漂移 | 版本在实施时分配、fresh = migrated 逐字一致、迁移 verifier（D-219 清单） |

## 13. 指针维护说明（对第一切片规格的就地最小修订）

本批对 `docs/specs/2026-09-19-p7-05-correction-void-recycle-design.md` 做**原地最小修订**，只更新指针与状态，**禁止整段改写**（历史叙述必须保留，沿 D-223 F1 教训）：§3.1 支持矩阵 `ACCOUNT_TRANSFER` 行加「已由 P7-05 第二切片规格 + D-228 承接」标注与指针；§9「本批不做」的转账项加指针；头部 `Revision` 追加一行。

## 14. 本批不做（逐项）

借贷（`LEND`/`COLLECT`）修正/作废/恢复（DP-4 保持 OPEN）；导入关联交易的修正/作废/恢复（DP-5 保持 OPEN，除非 J2 裁决纳入）；关联退款场景（DP-13 保持 OPEN）；`occurredAt` 修改（DP-7 OPEN）；`kind` 修改；永久删除/清除；批量作废；作废循环（> 1 次）；多币种；自动重试；跨账本操作；腿粒度作废的 schema 面（除非 J1 裁决候选 B）；`rgXX_` 竖井/golden/既有迁移边改动；对 `lending_position_history`、对账 owner、导入 owner 的任何写入（除 J3 裁决授权的对账面）。

## 边界断言

- 本文是**设计门候选规格**（`proposal`，2026-10-16）；**不授权实现**、迁移、技术选型或发布；批准依据将是 `docs/DECISIONS.md` **D-228**（本批登记），转换以独立规格评审闭环 + 主会话对 J1/J2/J3 的裁决为前提。本文**不自行**宣布 `approved`。
- **零代码 / 零测试 / 零 schema / 零迁移 / 零依赖**：本文不写实现、不写迁移文件、**不分配 schema 版本号**（沿 D-187/D-225 设计门先例）。
- **J1/J2/J3 为开放项**：可改字段集与腿失效语义（J1）、借贷/导入关联/退款切片保持 OPEN（J2）、DP-10 是否组合（J3）——**待主会话裁决**；本文任何 J1/J2/J3 覆盖处**不得**读作「已裁决」「冻结」「权威」「按建议批准」。
- **不改既有冻结面**：不改 D-156/D-158 的任何裁决；不改第一切片支持矩阵 `EXPENSE`/`INCOME` 行；`transaction_effective_state` 保持有效谓词**唯一 SQL 定义点**；`lending_position_history` append-only 硬墙、对账 owner guard、导入终态守卫不得绕过或放宽。
- **会计不变量**：金额一律精确整数 minor units，禁浮点；失败与拒绝路径 fail-closed、零正式账务效果；命令幂等（claim-first/等价 replay）；corrections 保版本；`AR:275` 的「本金两腿不计对外收支、手续费计入普通支出并按其资金腿独立对账」不得违反。
- **T-01..T-14 与 T-03b 无一记 PASS**；本批零实施，不声称任何验收向量通过。
- 真实金额/时间/锚点注册值与个人数据不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动；本文不含本机绝对路径、临时研究或工具轨迹。
