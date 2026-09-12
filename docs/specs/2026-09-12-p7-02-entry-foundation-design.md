# P7-02 录入基础实施规格（设计门）

状态：approved（裁决依据 = 用户常设授权「除不 push 外默认采用推荐」+ `docs/DECISIONS.md` D-144 登记；D-144 与本规格同批落盘，登记完成前不构成实施授权）。

**Revision:** draft-3（2026-09-12；draft-2 复审 REQUEST-CHANGES 修正闭环：**G-A** note 默认值硬回归修正（纯本金转账保持 `note=null`，导入链绑定校验 `version.note != null` 通过）、**G-B** 事件 × 状态矩阵与既有 ISE 语义对齐（absorbed 仅限 P7-02 新增事件）、**G-C** 再记 `retainedIntent` 注入通道冻结、**G-D** `lending_position_history` 同刻次键冻结）。draft-2 由独立规格评审 REQUEST-CHANGES（2×P0 + 4×P1 + 7×P2）修正；draft-1 由主代理在用户常设授权下裁决 Q03/Q04/Q05。承接登记链：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §4（P7-02 录入基础，:74-100，含 §4.1 拆分与 §4.2 验收 B01–B06）与 §7（Q03/Q04/Q05 最迟决定点，:177-179）；该计划基线 `main` = `a710c6ce370b1eee8cd00ff8fe662377b930d795`，本规格工作基线 = `main` = `4eb41ee`（P7-01 merge，D-143），当前 schema = v28（`27.sqm` 由 P7-01 引入；`LedgerDatabaseMigrationTest.kt:307` 等断言 `assertEquals(28, ...)`）。tracked 行号为工作基线 `4eb41ee` 实读行号；`.local.md` 以主 checkout 为准、只读。

**Scope:** 冻结「录入基础」实施批（P7-02）的契约面：统一类型化录入数据流（含类型集合与 requestId/snapshot 纪律）、备注贯通、目录准入扩展、手工转账（含可选手续费）、手工借贷（最小稳定往来对象目录 + LEND/COLLECT + 按对象时间单调）、录入效率（字段保留矩阵、再记、精确算式、手动置顶）、失败码族、UI 状态机/事件矩阵、B01–B06 验收矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成；引用均带 file:line；不粘大段产品代码。本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 worktree 基线 `4eb41ee` 的实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **阶段计划**：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:74-100`（P7-02 目标、§4.1 执行拆分与共享数据流、§4.2 B01–B06 验收）、`:177-179`（Q03/Q04/Q05 最迟决定点与推荐范围）、`:12`（第一组目标含支出/收入/转账/借出与收回；多币种/自动记账/多人/多账本不在范围）。
- **会计规则**：`docs/ACCOUNTING_RULES.md:46-50`（收入：金额 + 二级收入分类 + 收款账户）、`:52-60`（转账：一对一账户互转、本金不计对外收支、手续费与本金分离、手续费费用为正）、`:62-82`（借贷：四种稳定行为、借出本金转应收、收回必须明确拆分总额/本金/利息/费用且闭合、费用首版恒 0.00、零利息仍需精确利息分类、本金不得超额或跨零、往来对象用稳定 `counterparty_id` 且改名不改余额、往来对象明细独立保留且不可变历史 `:74`）、`:239-245`（对账在真实资产/负债 posting 级、状态不改余额）、`:255-261`（P7-01 目录准入：新正式交易只引用 active 账户/叶子分类、提交须校验引用状态、整笔类型化拒绝零正式写入）。
- **Golden Schema**：`docs/GOLDEN_SCHEMA.md:37`（目录实体为状态拥有、稳定 ID 不变、变更追加历史）、`:63`（`catalog.accounts` 形状含 `name`、`kind` 五值、`owned_by_user`、`real_account`、可选 `system_role`/`hidden`）、`:64`（`catalog.categories` 形状）、`:70`（`posting.category_id` 归属规则：二级分类的 `posting_account_id` 必须等于该 posting 账户；分类停用后历史分录仍有效）。
- **决定（已确认/已批准）**：D-084（RG-08 lending：position/settlement payload、`LEND`/`COLLECT` kind、费用恒 0.00、本金上限原子拒绝，`DECISIONS.md:1071`）、D-087（完整 state/delta 比较、RG-02 rename 最小闭环，`:1149`）、D-098（共享导入链方案 A：非 `rgXX_` 前缀产品表 + claim-first 原子确认，`:1475`）、D-100（P4-04 转账 formalization，`:1548`）、D-113（受控 supersede 触发器与加性迁移先例，`:1814`）、D-119（ledger-scoped/current-version 读与选项边界，`:1978`）、D-120（P5-03 固定匿名目录、两端组合根、snapshot-aware resolver，`:1994`）、D-121/D-122（阶段重排与三 Tab，`:2016`/`:2034`）、D-125（编辑流系统返回关闭与确认/取消语义，`:2078`）、D-126（可见关闭入口、桌面 Esc 对等、确认页显示名、UnknownCommit 核对闭环，`:2092`）、D-131（金额宽容解析 + 发生时间选择器，`:2176`）、D-138（手工输入友好时间格式，`:2373`）、D-139（键入保留缺陷 D138TYPING-001 修复，`:2396`）、D-140（冲突/拒绝流键入保留 D138TYPING-002 修复，`:2431`）、D-143（P7-01 目录管理设计门 + Q01/Q02，`:2545`）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表冻结（D-066/D-098；`rg08_name_history`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:6251-6258`）不得复用为产品往来对象目录）；golden fixtures/expected 不改；P7-03/P7-04 不在本批。

## 1. 目标与范围

### 1.1 目标

- 交付统一的类型化录入数据流：类型化草稿 → 校验 → 明确确认快照 → application 用例 → claim-first 原子提交 → 权威回读；共享 UI 只消费 application 类型。
- 默认 `EXPENSE`，编辑页内可切 `INCOME`/`TRANSFER`/`LEND`/`COLLECT`；收入打通两点组合根并读回真实 kind。
- 可选备注真正写入正式交易版本 note，修复「note 仅存 request 表、`transaction_version.note` 硬编码空串」的断层。
- 手工转账：自有真实资产账户间一对一，可选手续费，本金不计收支，手续费独立对账。
- 手工借贷：最小稳定往来对象目录 + 对象级本金累计 + LEND/COLLECT 产品用例，不复用 RG-08 回放来源编排。
- 录入效率：字段保留矩阵、保存后再记一笔、精确金额算式、按账本+稳定 ID 的手动置顶。

### 1.2 范围（冻结）

**范围内：** application 类型化录入端口与用例、四类新正式交易的确认/commit/receipt 查询与 snapshot-aware resolver、ledger-data 新增非 `rgXX_` 产品表（往来对象目录 + 名称历史、对象级借贷位置/历史、手工转账与借贷 request/receipt、置顶偏好）、加性迁移 `28.sqm`（v28→v29）、两端组合根接线、app-ui 编辑页类型切换/再记/算式/置顶、V-2 目录准入扩展、B01–B06。

**范围外（本批明确不做，逐项冻结）：**

- **多币种**：本批所有类型固定 `CNY` / precision 2（T-2）。
- **周期/自动记账**：不做。
- **成员 / 多账本**：不做。
- **负债还款 / 组合转账**：不做（T-5）。
- **跨币种、信用还款、借入/还款 UI**：不做（T-5、L-6）；四种稳定行为码存在不构成借入/还款 UI 授权。
- **自动频率排序**：不做（E-4）；置顶仅手动。
- **复制/合并往来对象**：不做（`ACCOUNTING_RULES.md:74`：合并须另行定义明确可审计操作）。
- **借贷倒填时间**：不做（P0-1；`LendingBackdatedNotAllowed`）。
- **不复用导入工厂与回放竖井**：不得伪造成导入来源调用导入工厂，不得把 RG-08/RG-03 回放 store/identity source 接为产品默认（S-1、L-5）。
- 真实金额/时间/锚点注册值与个人数据不入文；示例全部匿名合成。

## 2. 现状与差距（file:line）

1. **支出完整链（模板）**：草稿 `ManualExpenseDraft`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503AppState.kt:137-142`）仅有 `paymentAccountId/categoryId/amountText/occurredAt`，**无类型判别、无 note**；提交快照 note 固定 `""`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:146`）；金额解析 `ParseManualExpenseAmount`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ParseManualExpenseAmount.kt:25`，strict 优先回退 lenient 于 `:48-50`）；领域工厂 `createAssetPaidOrdinaryExpense`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OrdinaryExpense.kt:19`）把版本 note 硬编码为 `""`（`:115`）；commit port `SqlDelightConfirmedManualExpenseCommitPort`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightConfirmedManualExpenseCommitPort.kt:15` claim-first 事务）；读端口 `LedgerCurrentStateReadPort`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/LedgerCurrentStateReadPort.kt:33` 仅 `findManualExpenseByRequest/Receipt`）；snapshot-aware resolver `ResolveManualExpenseCommitStatus`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ResolveManualExpenseCommitStatus.kt:27`，`resolve` 于 `:30`）。**结论：支出链可作模板，但类型、note、非支出类型均为空缺。**
2. **收入领域就绪、应用契约就绪、产品链零接线**：`createAssetReceivedOrdinaryIncome`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OrdinaryIncome.kt:19-75`）已含二级/active/kind/`realAccount=false` 隐藏过账账户/同账本/同币种校验，note 同样硬编码空串（`:73`）；应用契约 `ExplicitlyConfirmedManualIncome`/`ManualIncomeRequestSnapshot`/`ConfirmedManualIncomeCommitPort`/`ExecuteConfirmedManualIncome`/`ExecuteManualIncomeSave`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedManualIncome.kt:14-139`）；data port `SqlDelightConfirmedManualIncomeCommitPort`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightConfirmedManualIncomeCommitPort.kt:16`，与支出对称但**无 companion/常量**）。两端组合根（`android-app/src/main/kotlin/com/unifiedledger/android/App.kt`、`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt`）**零收入接线**（grep 无 `OrdinaryIncome`/`ManualIncome`/`ConfirmedManualIncome` 命中）；无收入选项 provider、无 `findManualIncomeBy*`、无 snapshot-aware 收入 resolver、无 UUIDv7 收入 id source。
3. **转账领域就绪、产品链全缺**：`createOwnAssetAccountTransfer`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/AccountTransfer.kt:92-249`）含三腿（`PRINCIPAL_OUT`/`PRINCIPAL_IN`/`FEE`）、`sourceDebit == destinationCredit + fee`（`:153-158`）、费用非负（`:150-152`）、同账本同币种（`:159-170`）、同账户拒绝（`:107-109`）、两端 `ownedByUser && realAccount && kind==ASSET`（`:110-139`），以及 `reportEffects`（`consumption=ordinaryExpense=cashOutflow=fee`、`netWorthChange=-fee`、`internalTransfer=destinationCredit`，`:236-246`）；费用分类校验 `validateTransferFeeCategory`（`:251-281`，要求 active 二级 EXPENSE + 隐藏 EXPENSE 同币种过账账户）；`AccountTransferPosting`（`:29-33`，仅 FEE 才带 `categoryId`）；纯本金两腿 `createOwnAssetPrincipalTransfer`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OwnAssetPrincipalTransfer.kt:40-137`）。`TransferFlowFormalFactory`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/TransferFlowFormalFactory.kt:35`）属**导入链**（`ImportCandidateFormalFactory`），不可作产品默认。**产品侧无转账用例、无 request/receipt、无选项、无 UI。**
4. **借贷领域就绪、回放竖井不可复用**：`LendingPosition`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/LendingPosition.kt:31-112`）按稳定 `counterpartyId` 归集、`PERSON_LEVEL_NET_POSITION`、`contractAllocationEnabled=false`、本金非负、history 追加-only 且按**传入顺序**逐项校验累计 `after`（`:77-99`，`after != entry.principalBalanceAfterMinor || after < 0L` 即拒绝）；`LendingSettlement`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/LendingSettlement.kt:72-196`）要求费用恒 `0.00`（`:146-151`）、零利息仍需精确 active 二级收入分类（`:109-122`）、`principal+interest+fee==totalReceived`（`:155-163`）、本金不超过 position 余额并原子拒绝（`:164-166`）。RG-08 回放编排 `Rg08Operations.kt:809-966` 强制 `BANK_DEBIT` 来源记录 + `MATCHED` 证据链接 + RG-08 竖井，**不可复用**；其 `buildLendTransaction`（`:1943-1975`）与 `buildCollectTransaction`（`:1980-2022`）提供三腿符号与 kind 的产品等价参照。**产品侧无往来对象目录、无应收账户关联、无对象级本金查询、无 LEND/COLLECT 用例。**
5. **无算式解析**：仅有 `ExactDecimal.kt` 严格 `parseExactDecimal`（`:13`）与宽容 `parseExactDecimalLenient`（`:49`），及 `ParseManualExpenseAmount` 的 strict 优先回退（`:47-51`）；**无 `+ - × ÷`、括号、表达式求值**。
6. **无置顶/偏好持久化**：`Ledger.sq` 无 pin/preference 相关表列（grep `pinned|favorite|preference|sort_order` 零命中）；无按稳定 ID 的排序持久化。
7. **note 断层（S-4 依据）**：request 表存储 snapshot note（支出 `ConfirmedManualExpense.kt:142`、收入 `SqlDelightConfirmedManualIncomeCommitPort.kt:42`），但两端组合根构造的领域工厂把 `transaction_version.note` 硬编码为 `""`（`OrdinaryExpense.kt:115`、`OrdinaryIncome.kt:73`，组合根调用点 `App.kt:217`/`Main.kt:277`）；`OwnAssetPrincipalTransfer` 的版本 note 取默认 `null`（`FormalLedger.kt:67-74` `note: String? = null`，工厂 `OwnAssetPrincipalTransfer.kt:111-118` 不传）。**用户可见备注无法写入正式版本。**
8. **目录准入现状**：V-2 已在手工支出提交事务内重校验并映射真实 token 族 `CatalogAdmissionRejection`（`ledger-domain/.../CatalogManagement.kt:64-82` 九值；application 纯校验 `CatalogAdmission.kt:53-81`；wrapper `:88-112` 映射 `toDomainViolation()` `:114-125`），但**仅覆盖支出路径**；收入/转账/借贷新类型必须在各自写入事务内扩展同一准入面。
9. **现状推论**：P7-02 的自由度边界 = 新增非 `rgXX_` 产品表 + 加性迁移 `28.sqm`（v28→v29）+ 复用已就绪领域规则（转账/借贷/收入）+ 统一类型化应用端口；硬约束 = 复用既有支出状态机语义（D-125/D-138/D-139/D-140）、不改 `rgXX_` 竖井/golden、不引入自动频率排序或未批准类型。

## 3. 裁决（S/T/L/E 逐条，已裁决推荐方案）

> 以下 S-1..S-5、T-1..T-5、L-1..L-6、E-1..E-5 为已裁决契约，实施批必须逐条落入，不得自行更改语义。S=共享基础，T=Q03 转账，L=Q04 借贷，E=Q05 录入效率。

### 3.1 S 共享录入基础（P7-02.A/D 基础）

- **S-1 统一录入数据流**：类型化草稿（含类型判别）→ 校验 → **明确确认快照** → application 用例 → claim-first 原子提交 → 权威回读。**共享 UI 只消费 application 类型**；不得伪造成导入来源调用导入工厂，不得把 RG-08/RG-03 回放 store/identity source 接为产品默认。
- **S-2 类型集合**：`EXPENSE`（已有）/ `INCOME` / `TRANSFER` / `LEND` / `COLLECT`。底部「+」默认 `EXPENSE`；编辑页内可切类型。
- **S-3 请求身份**：每个新意图分配**新 requestId**；等价 replay 同 id 同 snapshot → 幂等返回原 receipt；同 id 不同内容 → `RequestIdentityConflict`。提交后异常按完整 snapshot/receipt 判 success/conflict/unknown；Unknown **不自动重试、不换 id、不启用再记**。
- **S-4 备注**：类型化草稿增加可选 `note`（≤ 200 码点，允许空，实施冻结上限）；**必须真正写入正式交易版本 note**，修复「note 仅存 request 表、`transaction_version.note` 硬编码空串」的断层（§2.7）。既有 `TransactionVersion.note` 为可空默认 `null`（`FormalLedger.kt:67-74`）；新增命令字段**按导入链约束分别取值**（G-A）：支出/收入 `note: String = ""`（rg-01/rg-02 goldens 期望 `""`）、纯本金转账 `note: String? = null` 且域工厂**原样写入不做 `?: ""` 兜底**（导入链绑定校验要求 `null`）、含费用转账 `note: String = ""`（RG-03 现状即空串）。等价 replay 比较包含 note 值。
- **S-5 目录准入（P7-01 已建）**：所有新类型的账户/分类引用必须在**写入事务内**按当前权威目录重校验（V-2 扩展），复用既有真实 token 族 `CatalogAdmissionRejection`（`CatalogManagement.kt:64-82`）；失败整笔类型化拒绝零正式写入。产品提交路径**不携带 `expectedCatalogVersion`，不使用管理面码 `CatalogVersionConflict`**（见 §5.4 映射可达性说明；D-143 实施登记 D143-IMP-03）。

### 3.2 T Q03 转账裁决（P7-02.B）

- **T-1 手工转账提供可选手续费**：明确输入 转出账户、转入账户、到账本金、手续费（默认 `0.00`）、费用分类。
- **T-2 账户范围**：仅限**自有真实资产账户**（`ownedByUser && realAccount && kind==ASSET`）；两端必须同账本、同币种（本批固定 CNY）；同账户拒绝（`TransferSameAccount`）。
- **T-3 不变量**：`转出金额 = 到账本金 + 手续费`；`手续费 ≥ 0`；本金两腿不计收支；手续费计入普通支出并独立对账（`consumption=ordinaryExpense=cashOutflow=fee`，`netWorthChange=-fee`）。
- **T-4 金额派生与费用分类（P1-2）**：`sourceDebit`（转出金额）由 `destinationCredit + fee` **派生**，产品请求不接受独立转出金额输入，故 `TransferAmountMismatch` 在产品 UI **不可构造**，仅为防御性域边界（`AccountTransfer.kt:153-158` 的既有域校验），登记为不可达码。`手续费 > 0` 时费用分类必填，且须为当前目录中 **active 的二级支出分类**（复用 `validateTransferFeeCategory`，`AccountTransfer.kt:251-281`）；`手续费 == 0` 时不得携带费用分类。领域复用 `createOwnAssetAccountTransfer`（含费用）/`createOwnAssetPrincipalTransfer`（纯本金），不新增领域规则。含费用命令 `OwnAssetAccountTransferCommand` 新增 `note: String = ""`（默认空串，RG-03 竖井 `SqlDelightRg03TransferStore.kt:269/344/561/656` 既有调用不传 → 保持 `""`，零语义变化；产品含费用转账由命令显式传入自身 note）。
- **T-5 本批不含**：负债还款、组合转账、跨币种、信用还款。

### 3.3 L Q04 借贷裁决（P7-02.C）

- **L-1 最小稳定往来对象目录**（产品侧，非 RG-08 竖井）：创建、改名、稳定 `CounterpartyId`、名称历史（改名不改余额、历史显示当前名，`ACCOUNTING_RULES.md:74`）；每个对象关联一个同账本应收资产账户（`ASSET`、非真实、非自有、`systemRole==null`、隐藏，随对象生命周期）。应收账户形状按 D-143 体例披露：`owned_by_user=0`、`real_account=0`、`hidden=1`、`system_role=NULL`，**不满足** A-2 可管理集合谓词（`ownedByUser && realAccount && kind==ASSET`），故不进入普通账户管理入口。**与 RG-08 回放要求的并存说明**：RG-08 回放编排对 receivable 账户要求 `ownedByUser && realAccount`（`Rg08Operations.kt:837-843`），产品侧应收账户刻意相反（非自有、非真实、隐藏）；两者分属不同持久化面（RG-08 竖井 vs 产品 `counterparty`/`lending_position` 表），互不接线（L-5），不存在同一账户同时满足两侧谓词的要求。
- **L-2 借出与收回**：借出（`LEND`）选 对象 + 金额 + 出资账户（自有真实资产）+ 发生时间；按对象累计本金（`PERSON_LEVEL_NET_POSITION`），非合同级分摊。收回（`COLLECT`）选 对象 + 实收总额 + 本金 + 利息 + 费用（**必须 0.00**）+ 到账账户 + **精确有效利息分类**（零利息仍需，active 二级收入分类）+ 发生时间；`实收总额 = 本金 + 利息 + 费用`。
- **L-3 拒绝面**：超额、负组成、非零费用、无效利息分类、无效账户 → 整笔类型化拒绝、不截断不猜测分配；事务内重查剩余本金与当前目录，防并发超额收回。
- **L-4 对象时间单调（P0-1，取代原倒填裁决）**：借出与收回的 `occurredAt` 必须 **≥ 该往来对象现有历史的最近 `occurred_at`**（按对象单调非递减；同一时刻允许追加，稳定排序键 = `(occurred_at, entry_id)` 确定性）。任何使 `occurredAt` 早于该对象最近历史时间的操作 → 类型化拒绝 **`LendingBackdatedNotAllowed`**，不带任何写入。历史保持**只追加、不改写既有行**，`principal_balance_after_minor` 继续逐行存储；`createLendingPosition`（`LendingPosition.kt:51-112`）**原样作为唯一重建校验器**（不新增重建函数、不改其语义；`:77-99` 的按传入顺序逐项累计校验继续成立）。不同对象的 `occurredAt` 相互独立。**理由**：`ACCOUNTING_RULES.md:74` 要求往来对象明细与不可变历史；`LendingPosition.kt:73-103` 按传入顺序逐项校验 `after` 并拒绝任何负中间态，倒填必然破坏 append-only 顺序，故以「按对象时间单调」替代倒填。
- **L-5 手工借出只形成手工确认**：**不生成 `BANK_DEBIT`、不虚构哈希、不创建 `MATCHED` 证据**；手工资金腿保持**待对账**。不复用 `Rg08Operations` 回放编排。
- **L-6 类型开放面**：本批只开放 LEND/COLLECT；借入/还款 UI 不因四种行为码存在而增加。

### 3.4 E Q05 录入效率裁决（P7-02.D）

- **E-1 类型切换字段保留矩阵**（冻结于本规格，UI 不得自行定案，E-5）：

| 字段 | 语义 | 切换行为 |
| --- | --- | --- |
| 金额原始文本 | 各类型的主金额 | **跨类型保留**；按「类型→金额字段」表迁移 |
| `occurredAt` | 发生时间 | **跨类型保留** |
| `note` | 备注 | **跨类型保留** |
| 资产账户类 | 支出的支付账户 / 转账的转出账户 / 借出的出资账户 | 语义相容，**互相保留** |
| 转账转入账户 | 类型专有 | 切走即清空，切回不恢复 |
| 借贷往来对象 | 类型专有 | 切走即清空，切回不恢复 |
| 收入的收款账户 | 类型专有（与资产账户类不相容） | 切走即清空，切回不恢复 |
| 分类（含费用/利息分类） | 各类型独立 | **不跨类型保留** |

**类型→金额字段迁移表（P2-4）**：

| 目标类型 | 金额承载字段 | 来源映射 |
| --- | --- | --- |
| `EXPENSE` | `amountText` | 从任意类型的金额文本迁入 |
| `INCOME` | `amountText` | 从任意类型的金额文本迁入 |
| `TRANSFER` | `destinationCredit`（到账本金） | 金额文本迁入到账本金；手续费重置 `0.00` |
| `LEND` | `amount` | 金额文本迁入 |
| `COLLECT` | 仅 `totalReceived`（实收总额） | 金额文本迁入实收总额；本金/利息/费用**不**自动填充 |

- **E-2 保存后再记一笔（P0-2 修订；G-C 通道冻结）**：
  - `OverviewEmpty` 增加**可选**字段 `retainedIntent: RetainedEntryIntent? = null`；**仅在一次确定成功且权威回读完成后**由宿主（host）设置；初始进入、启动、普通刷新均为 `null`。
  - **可再记的确定成功集合 = `Created` / `NoChange` / `Recovered`**（`Recovered` 系 D-126 `MatchingReceipt` 恢复，等价确定成功，**纳入**）。
  - `RetainedEntryIntent` 携带：类型、原草稿中可复用字段（金额原始文本、账户/分类稳定 ID、`note`、`occurredAt`）、`originTab`。
  - **注入通道（G-C 冻结）**：`Created`/`NoChange`/`Recovered` 均为 `data object` 不带 draft，且 `RefreshResult` 只带 `currentState`，故 `retainedIntent` 无既有合法来源。冻结为：**`RefreshResult` 增加可选 `retainedIntent: RetainedEntryIntent? = null`**（后向兼容）；宿主在提交前捕获意图并从提交前的 `Submitting`/`AwaitingConfirmation` 取 `originTab`，在确定成功后触发的权威刷新上把该意图随 `RefreshResult` 注入；reducer 的 `reduceTransientResult`（`P503Reducer.kt:282-292`）在构建 `OverviewEmpty` 时透传 `event.retainedIntent`。宿主持有该意图为内存状态，跨 `Submitting`/`UnknownCommit`/`Recovered` 保持，不持久化。（备选通道 `EntryCommitted(lastIntent)` 事件与 `CatalogCommandCompleted` 同型，属同等实现选择；二选一，语义相同。）
  - 「再记一笔」在**成功后的首页**可用：以 `retainedIntent` 为基，**对照当前权威目录重新校验**，**失效对象不沿用**（不沿用的字段被清空）；清空金额、备注与旧确认；`occurredAt` = 当次 `LedgerClock.now()`（`LedgerClock.kt:12-13`）；分配**新 requestId**，进入新 `Editing`。
  - **必须重置宿主 hoisted `occurredAtText`**（与 `StartNewExpense` 同列；`P503App.kt:96` 现有重置点），否则旧键入文本会覆盖新 instant。
  - **生命周期**：`RetainedEntryIntent` 在新意图开始（进入新 `Editing`）后清除；`Exit`/返回/跨会话不保留（不持久化）。
- **E-3 金额算式/计算器**：支持 `+ - × ÷` 与括号、精确十进制运算（禁浮点、禁静默舍入）；`12.5+8` → 精确 `20.50`；除零、溢出、**非货币精度结果**（超过 2 位且非末位全零可整除）以及**负结果**一律**可解释地类型化拒绝**；计算器先给精确结果再确认。**文法冻结（P2-2）**：仅二元 `+ - × ÷`、标准优先级（`× ÷` 高于 `+ -`）、括号可嵌套；**不允许一元负号与负字面量**（负输入的表达式在词法阶段即 `EntryExpressionInvalid`）；限制 token 数 ≤ 64、括号深度 ≤ 8（超限 `EntryExpressionInvalid`，实施按此冻结值）。除法的中间态必须可精确终止：不可精确终止（如 `1/3`）→ `EntryExpressionNonCurrencyPrecision`；可精确终止但结果超目标币种精度（如 `10/4 = 2.5` 可终止但需 1 位小数，在 precision 2 下可表示；`1/8 = 0.125` 需 3 位且末位非零 → 拒绝）→ `EntryExpressionNonCurrencyPrecision`。负结果（如 `1-2`）→ `EntryExpressionNegativeResult`。
- **E-4 手动置顶**：按 账本 + 稳定 ID 手动置顶账户/分类，**持久化**、重开保留；置顶只影响排序，**不改账务**、**不使停用对象重新可选**。不得实现自动频率排序（本批不做）。
- **E-5 冻结责任**：字段/时间保留矩阵与算式运算集在实现前由本规格冻结，UI 编写者不得自行定案。

## 4. 领域与应用变更

### 4.1 领域层（`ledger-domain`）

1. **备注贯通（S-4，P2-1；G-A 修正）**：四个命令按导入链约束**分别**取值，不得一刀切：
   - `createAssetPaidOrdinaryExpense`（`OrdinaryExpense.kt:108-116`）与 `createAssetReceivedOrdinaryIncome`（`OrdinaryIncome.kt:72-74`）的命令新增 `note: String = ""`，版本 note 取自该字段，不再硬编码 `""`。理由：`OrdinaryFlowFormalFactory` 走 `validateImportFormalBinding` 的 `OrdinaryFlow` 分支要求 `version.note == ""`（`TransferFlowFormalFactory.kt:279/288`，`version.note != "" → invalid`），且 rg-01/rg-02 goldens 期望 `""`。既有调用点（RG 回放/测试/组合根）不传 → 默认 `""` → 逐值不变。
   - `createOwnAssetPrincipalTransfer`（`OwnAssetPrincipalTransfer.kt:40-137`，纯本金两腿）的命令新增 **`note: String? = null`**，域工厂**原样写入该字段、不做 `?: ""` 兜底**；既有 `TransactionVersion` 不传 note 时默认 `null`（`FormalLedger.kt:67-74`）。
     - **导入链**：`TransferFlowFormalFactory.kt:185` 直接调用该域工厂且不传 note → 版本 note 保持 `null` → 其后再经 `validateImportFormalBinding`（`:208`）的 `TransferFlow` 分支在 `:300` 要求的 `version.note != null` **通过**；`ImportSpineTransferEndToEndTest`/`ImportSpineBankEndToEndTest`/`ImportSpineAlipayYuebaoTransferEndToEndTest` 不受影响。**这正是必须把默认值定为 `null`（而非 `""`）的原因**——若为 `""`，`:300` 的 `"" != null` 会整体拒绝导入转账，破坏冻结 P4-04。
     - **产品链**：产品纯本金转账（`手续费 == 0`，走 `createOwnAssetPrincipalTransfer`）由应用用例**显式传入自身 note**，覆盖默认 `null`。
   - `createOwnAssetAccountTransfer`（含费用三腿）的命令新增 `note: String = ""`；RG-03 竖井既有调用不传 → `""`（与今日硬编码 `""` 逐值一致），产品含费用转账显式传入自身 note。
   - 等价 replay 比较包含 note 值；`RG-09` 回放（`Rg09Operations.kt:1197`）不传 note → `null`，与今日一致。
   - **评审更正留痕（G-A）**：draft-2 原结论「两工厂均写 `""`、两链不共享版本构造、无回归」**错误**——导入链 `TransferFlowFormalFactory.kt:185` **直接调用** `createOwnAssetPrincipalTransfer`，与产品链共享同一域工厂，故纯本金命令的默认值必须保持 `null`。本稿以该更正为准。
2. **LEND/COLLECT 正式交易构造契约（P1-3，领域级）**：新增产品侧纯领域构造，作为 RG-08 编排（`Rg08Operations.kt:1943-2022`）的**独立产品等价物**，**不复用** `Rg08Operations`：
   - **LEND**：`Transaction.kind = LEND`；1 version（versionNumber 1）+ 1 postingSet + **2 postings**，顺序 = [应收资产 `+principal`，出资资产 `-principal`]，同币种、`PostingSet.create` 校验平衡；
   - **COLLECT**：`Transaction.kind = COLLECT`；1 version + 1 postingSet + **3 postings**，顺序 = [到账资产 `+totalReceived`，应收资产 `-principal`，利息收入账户 `-interest`]，`fee` 恒 `0.00` 且**不产生 posting**，`principal + interest + fee == totalReceived`；
   - `times = TransactionTimes(occurredAt, occurredAt, occurredAt)`（`TransactionTimes.collapsed`，与既有支出/收入先例一致）；note 取命令 `note`（`String = ""`）；
   - **reportEffects（按类型，P3）**：LEND → 本金不计消费/普通收支，`principalExternalCashFlow = principal`（借出资金流出）、`internalTransfer = 0`、`netWorthChange = 0`、`ordinaryIncome = 0`；COLLECT → `cashInflow = totalReceived`、借贷本金现金流入 = `principal`、普通利息收入 = `interest`、`consumption = ordinaryExpense = 0`、`netWorthChange = interest`、`fee = 0`（对齐 `ACCOUNTING_RULES.md:66-68`）；
   - **不创建任何 `BANK_DEBIT` 来源记录、不虚构哈希、不创建 `MATCHED` 证据链接**（L-5）；资金腿创建后为**待对账**。
   - 领域规则继续复用 `createLendingPosition`（`LendingPosition.kt:51-112`，唯一重建校验器）与 `createLendingSettlement`（`LendingSettlement.kt:72-196`）校验组成/费用/本金上限/利息分类；**不新增重建函数**（P0-1）。
3. **不改既有领域规则**：`createOwnAssetAccountTransfer`/`createOwnAssetPrincipalTransfer`/`createLendingSettlement`/`createAssetReceivedOrdinaryIncome`/`createAssetPaidOrdinaryExpense` 的校验语义逐条不变（T-4 明示复用）。

### 4.2 应用层（`ledger-application`）

1. **类型集合与草稿（S-1/S-2/E-1/P1-4）**：新增 `EntryType`（`EXPENSE/INCOME/TRANSFER/LEND/COLLECT`）、密封 `TypedEntryDraft`（每类型一子类，`EXPENSE` 子类保留既有 `ManualExpenseDraft` 字段语义并逐值兼容）与 `EntryFieldRetention` 纯函数（§3.4 E-1 矩阵的单一实现，UI 不自行实现）。**draft 类型化范围（P1-4）**：所有持 draft 的状态统一携带 `TypedEntryDraft` —— `Editing`/`AwaitingConfirmation`/`Submitting`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure`（SUBMISSION 上下文）/`UnknownCommit`；`InfrastructureFailure(READ)` 的 draft 仍为 `null`。**冻结不变量：跨态转换时 draft 类型必须保留**（`Confirm`/`Cancel`/`Back`/`AbandonConflict`/`RetrySubmission`/`RetryCommitStatusCheck`/冲突与拒绝往返均不得改变 `TypedEntryDraft` 的类型判别）。
2. **备注校验（S-4）**：新增纯校验 `ValidateEntryNote`（≤ 200 码点；空允许；超限 `NoteTooLong`；与既有 `ManualExpenseRequestSnapshot.note`、收入 snapshot note 字段衔接）。
3. **收入产品化（S-1）**：装配既有 `ExecuteManualIncomeSave`/`ExecuteConfirmedManualIncome`；新增收入选项 provider（复用 `ManualExpenseOptionsProvider` 模式，从同一权威目录派生 `active leaf INCOME` 分类 + `owned ASSET real` 收款账户）、收入 `LedgerCurrentStateReadPort` 扩展（`findManualIncomeByRequest/Receipt`）、收入 snapshot-aware resolver（镜像 `ResolveManualExpenseCommitStatus`，比较含 note 的 `ManualIncomeRequestSnapshot` 逐字段）、收入 UUIDv7 id source（独立 `UuidV7Generator` 实例，沿 D-120 request ID 先例）。
4. **转账用例（T-1..T-4）**：新增 `ManualTransferSaveInput`（ledgerId/requestId/转出账户/转入账户/到账本金/手续费/费用分类?/occurredAt/note/明确确认）、`ManualTransferRequestSnapshot`、`ExecuteManualTransferSave` + `ExecuteConfirmedManualTransfer`、`ManualTransferSubmissionResult`、`ResolveManualTransferCommitStatus`；缺必填字段（账户、本金、发生时间；手续费 > 0 时费用分类）→ `ManualTransferSaveResult.InvalidInput(fields)`；域拒绝 → 类型化拒绝。**转出金额由 `destinationCredit + fee` 派生**（P1-2）。**不调用 `TransferFlowFormalFactory`/`ImportCandidateFormalFactory`**（S-1）。
5. **借贷用例（L-1..L-6）**：新增往来对象命令（`CreateCounterparty`/`RenameCounterparty`，claim-first request/snapshot/receipt）、`QueryCounterpartyDirectory`、`QueryLendingPositions(ledgerId, counterpartyId?)`（按 `occurredAt` 排序历史 + 当前本金）、`ManualLendSaveInput`/`ManualCollectSaveInput`、`ExecuteManualLendingSave`、`ConfirmedManualLendingCommitPort`、`ResolveManualLendingCommitStatus`。收回在写入事务内重查剩余本金 + 当前目录（L-3）；对象时间单调校验读取该对象历史最近 `occurred_at` 并拒绝早于它的写入（L-4）；手工资金腿创建即**待对账**，不创建证据/`MATCHED`（L-5）。
6. **算式求值（E-3/P2-2）**：新增纯求值器 `EntryExpressionEvaluator`（token：非负十进制字面量、二元 `+`/`-`/`*`/`×`/`/`/`÷`、括号；**无一元负号/负字面量**），全部以精确十进制/`Long` minor units 运算，禁止二进制浮点与静默舍入；token ≤ 64、括号深度 ≤ 8；结果必须可表示为目标币种精度，否则类型化拒绝（`EntryExpressionNonCurrencyPrecision`）；负结果 `EntryExpressionNegativeResult`。
7. **置顶偏好（E-4）**：新增 `EntryPreferenceStore` 端口（按 ledgerId + 目标 kind + 稳定 ID 读写置顶标记）+ application 排序派生（置顶优先，其余保持既有确定性顺序）；**不改账务、不改目录 active 语义**。
8. **各类型目录准入校验清单（P2-7，S-5 扩展）**：写入事务内按当前权威目录重校验，复用真实 token 族（`CatalogManagement.kt:64-82`）；错误 token 优先级按既有 `validateManualExpenseAdmission`（`CatalogAdmission.kt:53-81`）顺序（存在性 → 账本 → kind → 可管理/自有真实 → active；分类：存在性 → 账本 → kind → 叶子 → active → posting 映射）。

| 类型 | 账户准入 | 分类准入 | 映射到 `CatalogAdmissionRejection` |
| --- | --- | --- | --- |
| `EXPENSE` | 支付账户 `owned && real && ASSET && active && 同账本` | active 二级 EXPENSE 叶子 + 同账本隐藏 EXPENSE posting 账户 | 复用既有 `validateManualExpenseAdmission` |
| `INCOME` | 收款账户 `owned && real && ASSET && active && 同账本` | active 二级 INCOME 叶子 + 同账本隐藏 INCOME posting 账户 | `PaymentAccount*` / `Category*` 同名 token（INCOME 版校验，`CategoryKindMismatch` 判 INCOME） |
| `TRANSFER` | 转出/转入账户均 `owned && real && ASSET && active && 同账本 && 同币种` | `fee > 0` 时 active 二级 EXPENSE（费用分类） | `PaymentAccount*`（两端）/ `Category*`（费用分类） |
| `LEND` | 出资账户 `owned && real && ASSET && active && 同账本` | 无用户分类（应收账户由对象携带） | `PaymentAccount*`；对象/应收账户失败 → `LendingCounterpartyNotFound` |
| `COLLECT` | 到账账户 `owned && real && ASSET && active && 同账本` | active 二级 INCOME 叶子（利息分类）+ 隐藏 INCOME posting 账户 | `PaymentAccount*` / `Category*`；对象失败 → `LendingCounterpartyNotFound` |

- 装载失败（事务内无法取得权威目录）→ `CatalogUnavailable`（`CatalogManagement.kt:82`）。

### 4.3 持久化（`ledger-data`）

沿用 D-098 方案 A 的**非 `rgXX_` 前缀**产品表纪律与 D-113 受控触发器/加性迁移先例；完整性与并发由 store 单事务 + 唯一键 + 受控 guard 强制。

1. **往来对象目录**：新增 `counterparty`（`ledger_id, counterparty_id, current_name, receivable_account_id, active`）与 `counterparty_name_history`（`ledger_id, counterparty_id, version_number, name, status`，受控 supersede，沿 `catalog_name_history` 先例 `Ledger.sq:198-214`）。应收账户为 `catalog_account` 行（`kind='ASSET'`, `owned_by_user=0`, `real_account=0`, `hidden=1`, `system_role=NULL`），随对象生命周期创建/停用（隐藏，不进入 A-2 可管理集合，见 §3.3 L-1）。
2. **借贷位置**：新增 `lending_position`（`ledger_id, counterparty_id, receivable_account_id, currency_code, currency_precision, principal_balance_minor`）与 `lending_position_history`（`ledger_id, counterparty_id, entry_id, behavior_code, amount_minor, principal_balance_after_minor, transaction_id, occurred_at`）。**唯一键 `(ledger_id, counterparty_id, entry_id)`（P3）**；`transaction_id` 引用 `ledger_transaction`（同账本 FK）；追加-only + 按 `occurred_at` 排序；`L-4` 单调性由 application/store 在该对象最近 `occurred_at` 上强制（`occurred_at >= max(existing)`，同刻允许追加）。**同刻次键冻结（G-D）**：store 查询与重建一律 `ORDER BY (occurred_at, entry_id)`（升序），插入顺序与重建顺序显式一致，避免同刻顺序不确定；`entry_id` 为该对象内稳定 ID，同刻事件按 `entry_id` 确定性排序，`createLendingPosition` 按此顺序重建校验。
3. **手工转账/借贷幂等对**：新增 `manual_transfer_request/receipt` 与 `manual_lending_request/receipt`，claim-first（`INSERT ... ON CONFLICT DO NOTHING` + `changes()`），`request_snapshot` 为等价 replay 唯一依据（沿 P7-01 A4 纪律），`input_fingerprint` 仅派生校验。失败/冲突单事务回滚，claim 亦回滚（身份可重试）。
4. **置顶偏好**：新增 `entry_pin`（`ledger_id, target_kind ∈ {account, category}, target_id, pinned_at`，唯一键 + 重开保留）。
5. **迁移（P3 校准）**：当前基线 schema = **v28**（`27.sqm` 由 P7-01 引入；`LedgerDatabaseMigrationTest.kt:307`/`ImportSpineMigrationCoexistenceTest.kt:149` 等断言 `assertEquals(28, LedgerDatabase.Schema.version)`）。本批**新增 `28.sqm`、目标 v29**，**加性迁移、只建结构零回填**。步骤沿 `26.sqm`/`27.sqm`：`PRAGMA defer_foreign_keys` → 建表 + 索引 → 受控 guard 触发器 → late sentinel → 版本推进。fresh/migrated schema 逐字一致；`verifyMigrations = true`（`ledger-data/build.gradle.kts:79`）保持通过；全仓版本断言（`grep -rn "Schema.version"`）全部同步至 29。
6. **receivable 账户与 bootstrap**：往来对象应收账户为**命令内部铸造**的隐藏账户，不进入 P7-01 默认目录种子；旧库无此类对象，迁移零回填。

## 5. 应用接口与失败码

### 5.1 统一请求/结果形状

沿既有「手工支出」范式：请求携带 `requestId` + `requestSnapshot`（规范化载荷副本，**等价 replay 唯一判定依据**）+ 明确确认；结果四态 `Created(receipt)` / `NoChange(receipt)` / `Rejected(failureCode)`（零写入）/ `Conflict(failureCode)`（`RequestIdentityConflict`，零写入）。并发 `(ledger_id, request_id)` 唯一键冲突 → 读既有行：snapshot 相等 → `NoChange` 原 receipt；不等 → `RequestIdentityConflict`。

### 5.2 Snapshot-aware resolver

提交后异常按完整 snapshot/receipt 判定：逐值匹配 → `MatchingReceipt`（Recovered）；不等 → `SnapshotConflict`（→ `RequestIdentityConflict`）；`Absent`/`Unavailable` → Unknown（不自动重试、不换 id、不启用再记）。四类型各自 resolver 比较含 note 的完整 snapshot。

### 5.3 失败码（稳定命名；code 稳定、message 不稳定不比较，沿 D-098/D-113 taxonomy）

| code | 触发 | 结果 |
| --- | --- | --- |
| `EntryTypeNotSupported` | 请求类型 ∉ {EXPENSE, INCOME, TRANSFER, LEND, COLLECT}（防御性死码，产品 UI 不可构造，见 §5.4） | Rejected，零写入 |
| `NoteTooLong` | 规范化后 note > 200 码点（S-4） | Rejected，零写入 |
| `TransferSameAccount` | 转出账户 == 转入账户（T-2） | Rejected，零写入 |
| `TransferAccountNotEligible` | 任一端非 自有真实资产、跨账本或币种不符（T-2） | Rejected，零写入 |
| `TransferAmountMustBePositive` | 到账本金 ≤ 0（P2-5；亦可由 UI 非负门先行覆盖，域码保留） | Rejected，零写入 |
| `TransferFeeMustNotBeNegative` | 手续费 < 0（P2-5；UI 非负门覆盖，域码保留） | Rejected，零写入 |
| `TransferAmountMismatch` | `转出金额 != 到账本金 + 手续费`（**P1-2 防御性域边界，产品不可构造**，见 §5.4） | Rejected，零写入 |
| `TransferFeeCategoryRequired` | `手续费 > 0` 但缺费用分类或分类非 active 二级 EXPENSE（T-4） | Rejected，零写入 |
| `TransferFeeCategoryNotAllowed` | `手续费 == 0` 却携带费用分类（T-4） | Rejected，零写入 |
| `LendingCounterpartyNotFound` | 往来对象不存在、跨账本或 `active=false`（L-1、P3） | Rejected，零写入 |
| `LendingAccountNotEligible` | 出资/到账账户非自有真实资产、跨账本或币种不符（L-2） | Rejected，零写入 |
| `LendingPrincipalExceedsBalance` | 收回本金 > 该对象当前未结本金（L-2/L-3） | Rejected，零写入，不截断 |
| `LendingComponentsMismatch` | `本金 + 利息 + 费用 != 实收总额`，或本金/利息为负（L-2） | Rejected，零写入 |
| `LendingFeeMustBeZero` | 费用 != `0.00`（L-2） | Rejected，零写入 |
| `LendingInterestCategoryRequired` | 缺利息分类或非 active 精确二级 INCOME（零利息亦然）（L-2） | Rejected，零写入 |
| `LendingAmountMustBePositive` | 借出金额 ≤ 0（P2-5；UI 非负门覆盖，域码保留） | Rejected，零写入 |
| `LendingTotalMustBePositive` | 实收总额 ≤ 0（P2-5；UI 非负门覆盖，域码保留） | Rejected，零写入 |
| `LendingBackdatedNotAllowed` | `occurredAt` 早于该对象最近历史时间（L-4/P0-1） | Rejected，零写入 |
| `LendingBehaviorNotSupported` | 行为码 ∉ {LEND, COLLECT}（防御性死码，产品 UI 不可构造，见 §5.4） | Rejected，零写入 |
| `EntryExpressionInvalid` | 算式语法非法 / 含一元负号或负字面量 / 超 token 或括号深度上限（E-3/P2-2） | Rejected，零写入 |
| `EntryExpressionDivideByZero` | 除零（E-3） | Rejected，零写入 |
| `EntryExpressionOverflow` | 运算溢出（E-3） | Rejected，零写入 |
| `EntryExpressionNonCurrencyPrecision` | 结果不可精确终止或超出目标币种精度且非末位全零可整除（E-3） | Rejected，零写入 |
| `EntryExpressionNegativeResult` | 精确结果 < 0（E-3/P2-2） | Rejected，零写入 |
| `EntryPinTargetNotFound` | 置顶目标不存在或跨账本（E-4） | Rejected，零写入 |
| `PaymentAccountNotFound` / `PaymentAccountInactive` / `PaymentAccountNotManageableFinancial` / `PaymentAccountWrongKind` / `CategoryNotFound` / `CategoryInactive` / `CategoryNotLeaf` / `CategoryKindMismatch` / `CatalogUnavailable` | 目录准入重校验失败（S-5/V-2 扩展；真实 token 族 `CatalogAdmissionRejection`，`CatalogManagement.kt:64-82`） | Rejected，零写入 |
| `RequestIdentityConflict` | 同 requestId 不同 snapshot（S-3） | Conflict，零写入 |
| `EntryConstraintViolation` | 触发器/唯一/FK 兜底失败 | 整事务回滚，类型化拒绝 |

**失败码到用例映射（P1-1 修订）**：`TransferSameAccount`/`TransferFeeCategoryRequired`/`TransferFeeCategoryNotAllowed`/`TransferAccountNotEligible`/`TransferAmountMustBePositive`/`TransferFeeMustNotBeNegative` → 手工转账（T-1..T-4，B01/B03）；`LendingBackdatedNotAllowed`/`LendingPrincipalExceedsBalance`/`LendingComponentsMismatch`/`LendingFeeMustBeZero`/`LendingInterestCategoryRequired`/`LendingCounterpartyNotFound`/`LendingAccountNotEligible`/`LendingAmountMustBePositive`/`LendingTotalMustBePositive` → 手工借贷（L-1..L-6，B02/B03）；`EntryExpression*` → 金额算式（E-3，B06）；`NoteTooLong` → 备注（S-4，B01）；`EntryTypeNotSupported` → 类型切换（S-2）；`EntryPinTargetNotFound` → 置顶（E-4，B06）；`PaymentAccount*`/`Category*`/`CatalogUnavailable` → S-5/V-2 全部类型提交（B03）。

### 5.4 映射可达性说明（P1-1，沿 P7-01 §6.3 先例）

- **产品提交路径不携带目录版本**：手工提交不使用 `expectedCatalogVersion`，故 **`CatalogVersionConflict` 不属于产品提交路径可达码，已从提交映射移除**（管理面码；见 D-143 实施登记 D143-IMP-03）。同理移除管理面名 **`CatalogObjectNotFound`**，提交路径以准入族 `PaymentAccountNotFound`/`CategoryNotFound` 表达。
- **命令层不可达（防御性死码，保留供防御与后续批次）**：`EntryTypeNotSupported`（`TypedEntryDraft` 为密封类型，UI 只能构造五值）、`LendingBehaviorNotSupported`（借出/收回两个独立用例，行为码由用例固定）、`TransferAmountMismatch`（转出金额由 `destinationCredit + fee` 派生，UI 无法构造不闭合请求；仅当防御性调用方直传不闭合值时由域层 `AccountTransfer.kt:153-158` 拒绝）。
- **仍可达**：上表其余码。
- **零写入契约**：任一 Rejected/Conflict 沿命令事务全回滚（claim 行亦回滚 → 身份可重试，D-098 领域 4 语义）；等价 replay 返回原 receipt、零新增实体。

## 6. UI 契约（状态机/事件矩阵）

### 6.1 状态机扩展（保留既有支出语义）

- **既有状态集不变**：`Ready/OverviewEmpty/Editing/AwaitingConfirmation/Submitting/Created/NoChange/RequestIdentityConflict/DomainRejected/InfrastructureFailure/UnknownCommit/Recovered`（`P503AppState.kt:20-110`）保留。
- **`OverviewEmpty` 新增可选 `retainedIntent`**（E-2/P0-2）；**注入通道（G-C）**：`RefreshResult` 增加可选 `retainedIntent: RetainedEntryIntent? = null`，`reduceTransientResult`（`P503Reducer.kt:282-292`）在构建 `OverviewEmpty` 时透传；`originTab` 由宿主从提交前 `Submitting.originTab` 捕获放入该意图。可再记集合 = `Created`/`NoChange`/`Recovered`。
- **draft 类型化（P1-4）**：`Editing`/`AwaitingConfirmation`/`Submitting`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure(SUBMISSION)`/`UnknownCommit` 的 draft 统一为 `TypedEntryDraft`，`EXPENSE` 子类字段与 `ManualExpenseDraft`（`P503AppState.kt:137-142`）逐值兼容，**默认构造与既有复制点向后兼容**（沿 D-125 默认值先例）；`InfrastructureFailure(READ)` 的 draft 保持 `null`。
- **`Editing` 新增可选 `expressionPreview: ExpressionPreview? = null`（P2-6）**：算式求值结果（`Valid(minorUnits, displayText)` / `Invalid(code)`）的**存放位置**，避免在 reducer 内直接改金额文本或产生未处理分支。
- **类型切换**：`SelectEntryType(EntryType)` 以 `EntryFieldRetention`（§4.2.1）计算新草稿（E-1 矩阵）。
- **再记（E-2/P0-2）**：见 §3.4。
- **置顶（E-4）**：账户/分类列表支持 `TogglePin(target)`；置顶仅影响排序，停用对象仍不可选。

### 6.2 事件 × 状态矩阵（P2-6；G-B 修正）

**规则（G-B）**：既有 reducer 的设计是「任一未被 `when` 分支列出的 `(state, event)` 抛 `IllegalStateException`」（`P503Reducer.kt:372-375`），并有测试锁定。本批**只为 P7-02 新增事件**定义 absorbed 语义；**既有事件一律保持现状转换**，未列即继续抛 ISE——不得用「未列事件一律 absorbed」覆盖既有语义。任何要改变某条既有 ISE 行为的做法必须显式登记并同步更新对应测试与 D-125 边界。

**表 6.2a：P7-02 新增事件 × 状态**（`effect` = 正常转换；`absorbed` = 明确吸收、状态不变、不崩溃；新事件**在任何状态都不抛 ISE**）：

| 新事件 | Editing | AwaitingConfirmation | Submitting | RequestIdentityConflict | DomainRejected | InfrastructureFailure(SUBMISSION) | UnknownCommit | OverviewEmpty | Created/NoChange/Recovered | Ready |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `SelectEntryType` | effect | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed |
| `UpdateNote` | effect | absorbed | absorbed | effect（键入保留） | effect（键入保留） | absorbed | absorbed | absorbed | absorbed | absorbed |
| `Update*`（**新增**各类型字段更新事件：`UpdateTransfer*`/`UpdateLend*`/`UpdateCollect*` 等；**不含**既有 `UpdateAmount`/`UpdatePaymentAccount`/`UpdateCategory`/`UpdateOccurredAt`，后者见下表 6.2b） | effect | absorbed | absorbed | effect（键入保留，D-140） | effect（键入保留，D-140） | absorbed | absorbed | absorbed | absorbed | absorbed |
| `EvaluateEntryExpression` | effect（写 `expressionPreview`） | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed |
| `ApplyExpressionResult` | effect（仅有合法 `expressionPreview` 时改金额） | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed |
| `SaveAndRecordAgain` | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | effect（`retainedIntent != null` 时→新 `Editing`） | absorbed | absorbed |
| `TogglePin` | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | absorbed | effect（仅偏好，零账务） | absorbed | absorbed |

**表 6.2b：既有事件 × 状态（保持现状，逐格为 effect / absorbed / ISE）**

| 既有事件 | effect/absorbed 转换（保持现状） | 其余状态 |
| --- | --- | --- |
| `InitialLoadResult` | Ready→`OverviewEmpty` | ISE |
| `InitialLoadFailed` | Ready→`InfrastructureFailure(READ)` | ISE |
| `SelectTab` | OverviewEmpty→copy | **ISE**（锁定 `selectTabInsideEditingIsAProgrammingError`，`P503ReducerTest.kt:450`） |
| `UpdateAmount`/`UpdatePaymentAccount`/`UpdateCategory`/`UpdateOccurredAt` | Editing / RequestIdentityConflict / DomainRejected→effect（键入保留） | ISE |
| `Continue` | Editing→AwaitingConfirmation（有效）或 effect-retain（无效）；AwaitingConfirmation→state | ISE |
| `Cancel` | AwaitingConfirmation→Editing；InfrastructureFailure(SUBMISSION)→Editing | **ISE**（锁定 `cancelFromEditingAndSubmittingRemainProgrammingErrors`，`P503ReducerTest.kt:632`） |
| `Confirm` | AwaitingConfirmation→Submitting；Submitting→state | ISE |
| `RetrySubmission` | InfrastructureFailure(SUBMISSION)→Submitting；Submitting→state | ISE |
| `RetryRefresh` | InfrastructureFailure(READ)→state | ISE |
| `RetryCommitStatusCheck` | UnknownCommit→state | ISE |
| `AbandonConflict` | RequestIdentityConflict→Editing（新 requestId） | ISE |
| `Back` | Editing/AwaitingConfirmation/RequestIdentityConflict/DomainRejected/InfrastructureFailure(SUBMISSION)→OverviewEmpty；Submitting→**宿主拦截不派发；reducer 层 ISE**（`P503App.kt:105-110`/`:517-520` `isBackDispatchSafe` 吞返）；OverviewEmpty→关对话框/回 HOME | **ISE**（锁定 `backFromSubmittingNullOverviewAndOverviewEmptyThrowIse`，`P503ReducerTest.kt:611`） |
| `StartNewExpense` | OverviewEmpty→Editing | ISE |
| `Exit` | 现状：reducer 无分支 → 未定义（未使用） | ISE（**保持现状，不借口加 absorbed**） |
| `SubmissionResult` | Submitting→effect | ISE |
| `CommitStatusResolved` | UnknownCommit→effect | **ISE**（锁定 `commitStatusResolvedOutsideUnknownCommitFailsFast`，`P503ReducerTest.kt:803`） |
| `RefreshResult`/`RefreshFailed` | OverviewEmpty / InfrastructureFailure(READ) / Created / NoChange / Recovered→effect | ISE |
| P7-01 目录管理事件（`Open*Dialog`/`UpdateCatalogForm*`/`Manage*`/`EnableCategoryGroup`/`Dismiss*`/`CatalogCommandCompleted`/`CatalogSnapshotRefreshed`） | OverviewEmpty→effect/absorbed（现状） | ISE |
| `UnknownCommit` 下未列既有事件 | UnknownCommit→**absorbed**（`P503Reducer.kt:279` 现状，唯一 absorbed 既有状态，保持） | — |

- 新增事件清单：`SelectEntryType`、`UpdateNote`、各类型字段更新事件（`Update*`）、`EvaluateEntryExpression`、`ApplyExpressionResult`、`SaveAndRecordAgain`、`TogglePin`；`RefreshResult` 增加可选 `retainedIntent` 字段（G-C，非新事件，只是载荷扩展）。
- **`retainedIntent` 透传语义（P3-2/P3-3 冻结）**：`reduceTransientResult`（成功结果分支）按 `event.retainedIntent` 覆盖；`InfrastructureFailure(READ)` 状态的 `RefreshResult`/`RetryRefresh` 分支**同样透传** `event.retainedIntent`（回读失败重试成功后仍可再记）；`OverviewEmpty` 状态的普通 `RefreshResult` 分支（`P503Reducer.kt:76` `state.copy(state=…)`）**保留既有 `retainedIntent`**（不被普通刷新清空，仅由成功结果刷新覆盖）。
- **`Back`/`Submitting` 特别说明**：该组合的「吞返」发生在宿主 `isBackDispatchSafe`（不派发事件），reducer 层保持 ISE，不得在 reducer 增加 `Back -> state` 分支。
- **与 R-7 一致**：本批不得反转任何既有 ISE；`UnknownCommit` 的既有全吸收是唯一例外并保持。

### 6.3 保留既有支出语义（D-125/D-138/D-139/D-140，硬约束）

- **取消/返回**：确认页「取消」= 回编辑保留草稿；`Back`/系统返回关闭回来源 Tab 丢弃草稿；`Submitting` 拦截返回（D-125）。
- **键入文本保留**：发生时间与金额键入文本宿主级保留，跨分支持久、不在解析时改写（D-138/D-139/D-140）；`P503App.kt:96` 的 `StartNewExpense` 重置点保留并与再记同列（E-2）。
- **选择器条件回写**：发生时间选择器确认时 `instant != draft.occurredAt` 才回写 ISO 串（D-139 §2.2）。
- **Esc/Dialog 语义**：桌面 Esc 在对话框打开期间让渡（D-131）；编辑流 Esc/back 逐态等价（D-126）。
- **权威刷新恒回首页**：`Created/NoChange/Recovered` 后权威刷新（沿用 P5-04.4）。

## 7. 验收矩阵（B01–B06 可测步骤）

| # | 步骤 | 期望 | 关联裁决/失败码 |
| --- | --- | --- | --- |
| B01 | 记录支出与收入（各含二级分类、可选 note）；重开读回 | 金额/分类正确；每币种分录平衡；note 从正式版本读回可见（S-4）；转账本金不进收支、手续费独立（T-3） | S-1/S-4/T-3；`NoteTooLong` |
| B02 | 同人借出 `100.00`，再借出 `20.00`；收回本金 `40.00`/利息 `5.00`/费用 `0.00`；另一对象独立操作 | 剩余本金 `80.00`、实收 `45.00`（`ACCOUNTING_RULES.md:68`）；另一对象余额不受影响；历史按 `occurredAt` 非递减保留且只追加 | L-2/L-4；`LendingComponentsMismatch`/`LendingFeeMustBeZero` |
| B03 | 依次尝试：本金超额、并发收回、负组成、非零费用、无效利息分类、无效账户；转账**同账户**、**手续费 > 0 缺费用分类**、**账户不合格**（至少两条可构造向量，P1-2）；借贷**倒填时间早于该对象最近历史** | 全部整笔类型化拒绝、零正式写入；不截断、不猜测分配；并发收回单赢家；倒填零写入 | L-3/L-4/T-2..T-4；`LendingPrincipalExceedsBalance`/`LendingBackdatedNotAllowed`/`TransferSameAccount`/`TransferFeeCategoryRequired`/`TransferAccountNotEligible` |
| B04 | 各类型双击提交、等价 replay、同 ID 不同内容、提交前失败、提交后回执丢失、重开恢复 | 等价 replay 返回原 receipt 零新增；同 ID 不同内容 `RequestIdentityConflict`；Unknown 不自动重试、不换 ID、不启用再记 | S-3/§5.2 |
| B05 | 类型切换、取消/返回、确认取消保留草稿、提交中拦截返回 | 保留矩阵生效（E-1）；D-138～140 键入文本/解析时间一致性；返回后原样保留；跨态 draft 类型保留（P1-4）；**既有 ISE 用例保持通过（G-B：`P503ReducerTest.kt:450/611/632/803`）** | E-1/§4.2.1/§6.2/§6.3 |
| B06 | 算式向量：`12.5+8`、`2×(3+4)`、`10/4`、`1/3`、`1-2`；非法式/除零/溢出；再记（`Created`/`NoChange`/`Recovered` 后均可）；置顶后重开 | `20.50`；`14`；`2.50`；`1/3` 拒绝；`1-2` 拒绝；其余可解释拒绝；再记不复用旧确认、发生时间 = 当下、新 requestId、重置 hoisted 文本；置顶持久且不改账务、不使停用对象可选 | E-2/E-3/E-4；`EntryExpression*`/`EntryPinTargetNotFound` |

补充映射：收入产品链读回真实 `INCOME` kind（S-1）；目录停用后打开草稿提交被 V-2 扩展类型化拒绝零写入（S-5、`PaymentAccountInactive`/`CategoryInactive`）；借出资金腿为待对账、无 `BANK_DEBIT`/`MATCHED`（L-5）。

## 8. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | note 进 `transaction_version.note` 的兼容风险：`OwnAssetPrincipalTransfer` 今日版本 note 为 `null`，导入链 `TransferFlowFormalFactory.kt:185` 直接调用该域工厂、其后 `validateImportFormalBinding` 在 `:300` 明确要求 `version.note != null`（`TransferFlow` 分支）；若默认改为 `""` 则 `"" != null` 整体拒绝导入转账，破坏冻结 P4-04。支出/收入版本 note 今日为 `""` | 导入转账确认回归、golden 不一致 | 命令字段**按导入链分别取值**（G-A，§4.1.1）：`OwnAssetPrincipalTransferCommand.note: String? = null` 且域工厂原样写入、不做 `?: ""` 兜底（导入链不传→`null`→绑定校验通过）；`AssetPaidOrdinaryExpense`/`AssetReceivedOrdinaryIncome` 维持 `note: String = ""`（rg-01/rg-02 goldens）；含费用转账 `note: String = ""`（RG-03 现状）。**回归锚点（G-A 修正）：不能只用 RG oracle**——`Rg09FullStateOracleTest` 的版本序列化不含 note 键，覆盖不到该风险；**must-pass 锚点 = `ImportSpineTransferEndToEndTest`、`ImportSpineBankEndToEndTest`、`ImportSpineAlipayYuebaoTransferEndToEndTest`**（本地 jvmTest 已存在）加 rg-01/rg-02 oracle。不改 golden/解码器；新增空/非空 note 用例 |
| R-2 | 算式解析安全/溢出：分派解析器可能深度爆炸或溢出 | 拒绝或崩溃 | token ≤ 64、括号深度 ≤ 8、`Long` checked 运算、显式溢出/除零/精度/负结果拒绝（E-3/P2-2）；纯函数零 IO；不引入浮点 |
| R-3 | 对象时间单调（L-4）替代倒填：同刻事件排序键需确定性 | 歧义 | 稳定排序键 `(occurred_at, entry_id)`；`createLendingPosition` 原样作唯一重建校验器（P0-1）；新增合成用例（倒填拒绝、同刻追加成功） |
| R-4 | 置顶持久化迁移：新表 + 加性迁移版本号 | 版本断言漂移 | 新增 `28.sqm` 目标 v29；动态发现全部 `Schema.version` 断言（现有断言 = 28）并同步；`verifyMigrations` 保持通过；fresh=migrated 逐字一致 |
| R-5 | 往来对象目录与 A-2 可管理集合关系：应收账户为隐藏非自有非真实资产，必须不进普通账户管理入口；与 RG-08 回放 `owned/real` 要求相反 | 误暴露/误接线 | 应收账户 `hidden=1/owned=0/real=0`，不满足 A-2 谓词（D-143 A-2）；两侧分属不同持久化面，互不接线（§3.3 L-1、L-5）；测试断言 |
| R-6 | 借贷产品表与 RG-08 竖井并存，可能误接线 | 回放污染 | L-5 禁止复用 `Rg08Operations`/竖井；产品表非 `rgXX_` 前缀；测试断言无 `BANK_DEBIT`/`MATCHED` 写入 |
| R-7 | draft 类型化改动既有 `ManualExpenseDraft` 构造/复制点与全部持 draft 状态 | 编译面扩大 | `EXPENSE` 子类向后兼容 + 默认值（沿 D-125）；`P1-4` 冻结跨态类型保留；既有 `P503ReducerTest`/`P503HostCoordinatorTest` 保持通过 |
| R-8 | 本批固定 CNY/2，未来多币种扩展 | 用户期望 | T-2 明确不做；类型/字段留扩展位但不实现 |
| R-9 | 防御性死码（`EntryTypeNotSupported`/`LendingBehaviorNotSupported`/`TransferAmountMismatch`）与 P2-5 域码在产品 UI 不可达 | 覆盖率/误用 | §5.4 明确登记可达性；域码保留作防御与后续批次 |

## 9. 实施与验证流程

1. **高风险路由**（沿根 `AGENTS.md` 与 `unifiedledger-harness`）：主代理建立隔离 worktree、指定单一 bounded writer 与精确可写范围；子代理先读主检出根 `AGENTS.md`、不复制嵌套索引、不变更 Git、不写 `.external/`。本批涉及账务/架构/迁移/隐私，需**独立规格评审 + 独立质量评审 + distinct verifier + 主代理复核**。
2. **聚焦测试优先，再受影响模块**：先跑新增类型化录入/转账/借贷/算式/置顶聚焦 test，再跑受影响模块 `:ledger-domain:jvmTest`、`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:app-ui:jvmTest`，随后 `:ledger-data:verifyCommonMainLedgerDatabaseMigration`、`ktlintCheck`、`project_docs`；组合根变化追加 `:desktop-app:jvmTest`、`:android-app:testDebugUnitTest`。
3. **聚合门以 CI 为准**：完整 `check`、Android/KMP 编译、Debug APK、Desktop build、完整 Python suite 与 migration verification 由 `.github/workflows/ci.yml` 在精确提交上提供发布证据，本机不重复资源密集型聚合（`AGENTS.md` 验证分工）。
4. **Android 人工门隔离 adb 协议**：agent adb 一律 `ANDROID_ADB_SERVER_PORT=5038`、永不 `kill-server`、只操作本会话自行启动并已用 `emu avd name` 核实的设备；不触碰用户 MuMu/ALas 与 5554/5555 槽位。
5. **登记与实施前硬门**：独立规格评审通过后由主代理在 `docs/DECISIONS.md` 追加 D-144；**实施批开始前必须确认 D-144 已落盘**（本规格 `状态：approved` 的实施授权以 D-144 登记完成为前提）。writer 只写本规格指定的可写文件，不执行任何 Git 写操作。

## 10. 本批不做（逐项）

多币种；周期/自动记账；成员；多账本；负债还款/组合转账；借入/还款 UI；自动频率排序；借贷倒填时间；往来对象复制/合并。（另见 §1.2。）

## 边界断言

- 本文档为设计门冻结候选：在独立规格评审闭环、**D-144 已落盘**与主代理登记前不构成实施授权；实施在单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（根 `AGENTS.md` 变更路由）。
- 实施批必须保持本规格冻结的：S-1..S-5、T-1..T-5、L-1..L-6（含 L-4 对象时间单调与 `createLendingPosition` 原样唯一重建校验器）、E-1..E-5、失败码族与可达性说明、事件 × 状态矩阵、B01–B06 覆盖面、D-125/D-138/D-139/D-140 既有支出语义；任何变更即重开评审门。
- 本批明确不做：多币种、周期/自动记账、成员、多账本、负债还款/组合转账、借入/还款 UI、自动频率排序、借贷倒填、往来对象复制/合并。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动。

## Appendix A. 类型化草稿与保留矩阵（实施批以评审后文本为准）

```text
EntryType = EXPENSE | INCOME | TRANSFER | LEND | COLLECT

TypedEntryDraft (sealed)  -- carried by Editing/AwaitingConfirmation/Submitting/
  ExpenseDraft  : amountText, categoryId, paymentAccountId, occurredAt, note   -- 既有 ManualExpenseDraft 超集
  IncomeDraft   : amountText, categoryId, receivingAccountId, occurredAt, note
  TransferDraft : sourceAccountId, destinationAccountId, destinationCredit,
                  fee, feeCategoryId?, occurredAt, note
  LendDraft     : counterpartyId, amount, fundingAccountId, occurredAt, note
  CollectDraft  : counterpartyId, totalReceived, principal, interest, fee(=0.00),
                  interestCategoryId, destinationAccountId, occurredAt, note
  -- sourceDebit(转出金额) 由 destinationCredit + fee 派生，不单独存储（P1-2）
  -- 保留矩阵与类型→金额字段迁移表见 §3.4 E-1（正文为唯一权威，本附录不复述细节）

RetainedEntryIntent (E-2/P0-2/G-C)
  type, amountText, account/category stable ids, note, occurredAt, originTab
  injected by host via RefreshResult(retainedIntent?, ...) after Created/NoChange/Recovered
  + authoritative refresh; originTab captured by host from pre-submit Submitting.originTab;
  cleared on new intent; never persisted across Exit/sessions
```

## Appendix B. 失败码到用例映射（摘要；可达性见 §5.4）

```text
手工转账 (T-1..T-4): TransferSameAccount, TransferAccountNotEligible,
                     TransferFeeCategoryRequired, TransferFeeCategoryNotAllowed,
                     TransferAmountMustBePositive, TransferFeeMustNotBeNegative
                     (TransferAmountMismatch = 防御性死码)
手工借贷 (L-1..L-6): LendingCounterpartyNotFound, LendingAccountNotEligible,
                     LendingPrincipalExceedsBalance, LendingComponentsMismatch,
                     LendingFeeMustBeZero, LendingInterestCategoryRequired,
                     LendingBackdatedNotAllowed, LendingAmountMustBePositive,
                     LendingTotalMustBePositive
                     (LendingBehaviorNotSupported = 防御性死码)
算式   (E-3):        EntryExpressionInvalid, EntryExpressionDivideByZero,
                     EntryExpressionOverflow, EntryExpressionNonCurrencyPrecision,
                     EntryExpressionNegativeResult
备注/类型 (S-2/S-4): NoteTooLong (EntryTypeNotSupported = 防御性死码)
置顶   (E-4):        EntryPinTargetNotFound
准入   (S-5):        PaymentAccountNotFound, PaymentAccountInactive,
                     PaymentAccountNotManageableFinancial, PaymentAccountWrongKind,
                     CategoryNotFound, CategoryInactive, CategoryNotLeaf,
                     CategoryKindMismatch, CatalogUnavailable
身份/兜底 (S-3):     RequestIdentityConflict, EntryConstraintViolation
```
