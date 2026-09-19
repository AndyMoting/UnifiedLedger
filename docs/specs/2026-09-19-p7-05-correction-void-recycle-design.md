# P7-05 修错、作废/删除、回收站恢复设计（设计门候选）

状态：approved（2026-09-19 设计门闭环：主代理按常设授权裁决 Q11/Q12 与 DP-1..DP-13 并登记 `docs/DECISIONS.md` D-156；独立规格评审与独立质量评审终局 **APPROVE WITH CONDITIONS**，P3 残余已随 draft-4 修复；§6/§7 为裁决记录）。实施仍按高风险路由由主代理另行建立实施批；本文不写实现、不写迁移、不分配 schema 版本号。

**Revision:** draft-4（2026-09-19；终局评审修复并转 approved：DP-9 澄清（改名但 active 可准入）同步 D-156、stale 结果面单元格改 `StaleCurrentVersion` 变体、DP-10 收窄后两处过期表述更正（§1.2/§1.4）、`Rg08FixtureReplay.kt:1034` 补入枚举、V-15 降级为索引存在性/行为断言（不冻结 `EXPLAIN QUERY PLAN`）、§3.6 增列目录删除引用面（#15，有意不过滤）、事实/回执形状三处不一致修正（`fact_id` 声明 + `UNIQUE(ledger_id, transaction_id, fact_id)`、void/restore 合并为单一父表、回执 FK 父键声明）、A-DOC 清单补 `LedgerEntryReadModels.kt:18`、新增 V-23 谓词双表示等价、V-21 收窄为可观测项、V-08 增 transfer-free fixture 前置）。draft-3（2026-09-19；规格评审修复轮：P705QUAL-001/002 有效谓词扩展到全部交易派生读面并登记 P7-03 读模型重冻结（§3.6）、P705QUAL-003 收窄 DP-10 至后续转账切片（首切片零对账暴露）、P705SPEC-001 更正 `CREDIT_REPAYMENT` 可达性，P705SPEC-002..015 与 P705QUAL-004..009 逐项落地）。draft-2（2026-09-19）按主代理 D-156 裁决记录 Q11/Q12 批准与 DP-1..DP-13 结果、DP-10 授权范围与 DP-13 否决。draft-1（2026-09-19）工作基线 `main` = `23d3bd2`（P7-04 A05IMPORT-INCOME-FACE-001 收口批），schema v30；tracked 行号为该基线实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读；示例全部匿名合成；引用不粘贴大段产品代码。

**Scope:** 冻结 P7-05「修错、作废/删除、回收站恢复」的设计候选面：05.A 支持矩阵与契约、05.B 版本修正、05.C 逻辑作废与恢复、05.D 产品接线。**首个可实施切片 = 手工创建的 `EXPENSE`/`INCOME`（同一五字段形态）的备注/统计时间/金额/分类/资金账户修正 + 逻辑作废 + 回收站恢复**；转账、借贷、导入关联交易各自为后续切片，各有独立支持矩阵行与独立决定点（§3.1、§6）。逻辑作废首版不做永久清除。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为工作基线 `23d3bd2` 实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md:68-81`（§5 P7-05 四子项 05.A–05.D、Q11/Q12 推荐方案）、`:4`（本文为 proposal，未裁决项不构成授权）、`:54-64`（P7-05 依赖与顺序：P7-02 正式提交、P7-03 查询、P7-04 来源关系）、`:147-153`（共同实施纪律与总体验收）、`:159`（无现成实现的项明确写「待实现」，不得把 RG 回放或尚无生产接线的函数当作产品能力）。
- **账务规则**：`docs/ACCOUNTING_RULES.md:34`（录入修正使用版本替代；退款、撤销和真实冲回是新的经济事件）、`:205-206`（统计时间可修改，报表按新时间归属，来源凭证时间不变；手工账目保留版本历史）、`:249`（新版本按正确发生时间参与余额和报表，旧版本保留但不再产生有效分录；修改当天不产生虚假现金流）、`:251`（退款/撤销/真实反向资金变化是独立交易，不改写原交易日期、版本或付款对账）、`:253`（修改金额、真实账户或币种时只失效受影响分录的原匹配并保留历史；只改非资金字段不重置未变化资金分录的对账）、`:243`（对账状态不改变任何余额或报表）、`:90`（已被使用的分类默认停用，彻底删除前必须迁移历史账目）、`:146`/`:148`（退款继承原交易精确二级分类；同币种有效退款累计不得超过原交易已确认可退费用）、`:299`（P7-03 月度读模型口径：按当前版本有效 kind 与分录账户类型分类，桶归属键取当前版本 `statistics_at`）。
- **产品需求**：`docs/PRODUCT_REQUIREMENTS.md:71`（P7-03 详情为只读，本批新增编辑/作废入口属新增面）、`:55`（目录删除须无任何经济引用；停用保留历史）、`:61-67`（P7-02 录入基础，本批修正/作废的输入与请求纪律来源）。
- **架构**：`docs/ARCHITECTURE.md:19`（数据迁移和版本替换必须保留来源、审计历史及失败恢复能力）、`:23-28`（模块职责）、`:58`（客户端 UI 只能调用应用用例，不直接写库）、`:136-150`（候选/确认边界：不得覆盖用户已确认的版本历史）、`:65`（ID/Clock 属应用能力；适配器不选择生成策略）、`:81`（D-144 录入边界）、`:83`（D-145 只读读模型）。
- **决定**：D-047（`docs/DECISIONS.md:563-573`：录入错误用版本替代，旧版本保留但失效；退款、撤销等真实反向资金变化才创建独立经济事件）、D-048（`:575-585`：修改金额/真实账户/币种只失效受影响分录原匹配，非资金字段不重置）、D-113（`:1814-1855`，边界原文见 §1.2）、D-098（claim-first 幂等/零写入先例）、D-144（P7-02 录入边界与请求身份纪律）、D-145（P7-03 只读读模型）、D-146（导入候选生命周期与终态守卫）、D-154（导入决策面）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；`lending_position_history` append-only 触发器、对账 owner guard 触发器与导入终态守卫不得绕过或放宽；不引入新依赖。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 计划列为「可复用」的四个机制：现状全部 test-only / RG-only，零产品组合根接线

| 机制 | 位置 | 现状唯一调用方 | 本设计处置 |
| --- | --- | --- | --- |
| `TransactionNoteReplacement.kt` | `ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/TransactionNoteReplacement.kt` | 仅自身测试 `TransactionNoteReplacementTest.kt` | **不复用为产品入口**；其底层原语 `appendVersion`（`TransactionVersionAppend.kt:43`）作为共享域规则被产品修正端口复用（仅 `Note`/`StatisticsAt`/`Postings` 三形态） |
| `ConfirmedTransactionNoteUpdate.kt` | `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedTransactionNoteUpdate.kt:1-139`（结果族 `:46-63`，含 `StaleCurrentVersion` `:59`；端口 `:66-71`） | 实现 `SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:14`；其唯一调用方为 RG-01 测试（`Rg01FullStateOracleTest.kt`、`Rg01RawJsonEndToEndTest.kt`） | **扩展**：取 claim-first + `expectedCurrentVersionId` CAS + receipt 形状作产品修错端口模板；其请求形状（仅 note）不直接复用 |
| 版本/CAS 对 | `Ledger.sq:2111 copyCurrentVersionWithNewNote`（`:2112-2125`，复制当前版本、**沿用当前 `posting_set_id`**、仅换 `note`）；`Ledger.sq:2127 compareAndSetCurrentVersion`（唯一 CAS 更新语句） | `SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:47/:58`（RG-01 测试） | `compareAndSetCurrentVersion` **原样复用**；note 形状复制语句不满足金额/分类/账户修正，产品须**新造**「以新 posting set 复制当前版本」语句（§4.3） |
| `P408CorrectionCommitPort.kt` | `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/P408CorrectionCommitPort.kt:171`；实现 `SqlDelightCorrectionStore.kt` | 仅 `P408CorrectionStoreTest.kt` | **不作为通用编辑 API**（D-113 边界，§1.2）；DP-10 复审收窄后首切片不使用（零对账暴露、无失效对象），组合授权延后至转账切片（§6） |

- 复核（`23d3bd2` 实读）：`android-app`、`desktop-app`、`app-ui` 对 `TransactionNoteReplacement`/`ConfirmedTransactionNoteUpdate`/`P408CorrectionCommitPort`/`SqlDelightCorrectionStore`/`ExecuteConfirmedTransactionNoteUpdate` 的引用数为 **0**（`grep -rn ... android-app desktop-app app-ui` 无命中，exit 1）。`P408CorrectionCommitPort` 的 KDoc 自称「Sole product writer for the correction family」，但当前**没有任何组合根构造它**；其实现类只被自身测试引用。`TransactionVersionAppend.appendVersion` 的调用方为 `Rg11Operations.kt`/`Rg12Operations.kt`（RG 回放）与 `TransactionNoteReplacement.kt`。**结论：四者今天均不可由产品路径到达，不得当作既有产品能力。**

### 1.2 D-113 边界（原文引用，不可外推）

> **边界：** 余额、正式交易与 report financial 维度零变化；不删行、不回溯（12 竖井零改动、D-092 不退役）……

（`docs/DECISIONS.md:1833`。）其授权范围为 correction port 的 evidence link 失效/后继链接/投影受控 supersede 与 MISSING/DIFFERENCE 结果态（`:1822-1831`）。**D-113 明确不改变交易版本与余额，不是通用编辑 API**；本批不得以它为依据写入 `ledger_transaction`/`transaction_version`/`posting_set`/`posting` 或任何余额/报表维度。另：D-113 UQ-1 已把「版本替代自动触发与补充资料重匹配的跨层集成」登记为**后续独立批**（`:1822`）；DP-10 复审收窄后，该延期集成**不在首切片落地**——首切片手工 `EXPENSE`/`INCOME` 无对账/证据行，不组合该 port，组合授权延后至转账切片单独裁决（§6 DP-10、D-156 修订）。

### 1.3 版本变更词汇与产品 SQL 现状

- `TransactionVersionChange` 仅三形态：`Note`（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/TransactionVersionAppend.kt:26`）、`StatisticsAt`（`:30`）、`Postings`（`:34`）；`appendVersion`（`:43`）以 `currentVersion.copy(...)`（`:86`）追加 `version_number + 1`，历史版本与既有 posting set 不变（`:85-101`）。**无 `occurredAt` 变更形态**：发生时间在 `copy` 中不参与，只有 `statisticsAt` 可改（DP-7：D-156 批准首切片不可改，保持 OPEN）。
- `Ledger.sq` 全库只有两条 `INSERT INTO transaction_version`：`:1997 insertTransactionVersion`（新建 version 1）与 `:2112 copyCurrentVersionWithNewNote`（note 形状复制）。**没有任何产品 SQL 以新 posting set 复制版本**；产品金额/分类/账户修正必须新造该语句（§4.3）。
- 参照（非产品接线）：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/PostingFactsCorrection.kt:81 validatePostingFactsCorrection` 冻结「matched 资产腿不得在事实层被改」（`:143-147 MatchedUnaffectedPostingMustBePreserved`）与「历史不可变」（`:163-166`），唯一调用方为 `Rg12Operations.kt`/`Rg12FixtureReplay.kt`（RG-12）。

### 1.4 作废/软删/回收站/有效版本：全仓不存在

- 产品源码（四个共享模块 commonMain + `android-app`/`desktop-app`）对 void / soft-delete / recycle / effective-version 类概念零命中；`ledger_transaction`（`Ledger.sq:1-7`）与 `transaction_version`（`:15-34`）**均无状态列**；`ledger_transaction_current_version`（`:52-63`）只是 current-version 指针（CAS token），**不构成「有效版本」语义**。既有历史/审计是 append-only 历史，不是作废能力。
- 逻辑作废的触及面（实施时必须逐项断言）：

| 分类 | owner | 本设计写入许可 |
| --- | --- | --- |
| 只读引用（不改写冻结行） | `ledger_transaction`、`transaction_version`、`posting_set`、`posting`、`ledger_transaction_current_version`（`Ledger.sq:1-63`）；`manual_*_request`/`confirmed_*_receipt`（`:65-262`） | 不写 |
| **对账 owner（绝不写）** | `posting_reconciliation`(`:8063`)、`posting_reconciliation_history`(`:8074`)、`evidence_link`(`:8029`)、`evidence_link_history`(`:8050`)、`evidence_projection`(`:8096`)、`reconciliation_correction_snapshot`(`:8143`)；guard 触发器 `:8197-8201` | 作废/恢复**零写入**；首切片修正亦零写入（无对账/证据行可失效；DP-10 已收窄，D-113 组合授权延后至转账切片） |
| **借贷本金历史（绝不写）** | `lending_position`(`:196`)、`lending_position_history`(`:209`)，append-only guard `:225-228` | 不写（DP-4） |
| **导入 owner（绝不写）** | `import_confirmation`(`:7767`；`UNIQUE(ledger_id, candidate_id)` `:7778`)、`import_receipt`(`:7784`)、`import_candidate_status_history`(`:7698`) 与终态守卫 `:8264-8273` | 不写（DP-5） |

### 1.5 UI：无任何编辑/作废/删除入口

- 详情屏 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503LedgerView.kt:342-408 P503TransactionDetailScreen(detail, onClose)` 为零编辑只读面（KDoc `:333-340` 明示 "Zero edit entries"，唯一出口是「返回」）；宿主 `P503App.kt:446-450 selectTransaction` 只派发读结果；列表行 `P503OverviewScreen.kt:139` 只导航详情。**05.D 全部为新建面。**

### 1.6 CAS token 已在产品读路径（获取 CAS token 无需读模型改动）

- 条目行 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/LedgerEntryReadModels.kt:22 LedgerEntryRow.currentVersionId`（由 `QueryLedgerEntryRows.kt:16` 产出）；详情 `QueryTransactionDetail.kt:160 TransactionDetail.currentVersionId`（`:86` 填充）。两者均为产品读模型。
- 注意：`QueryTransactionDetail` 从 `loadLedgerEntryRows` 行集取行（`QueryTransactionDetail.kt:38-42`），行集若被作废过滤，**详情对作废交易返回 `NotFound`**（`:176`）——回收站读路径必须另行设计（DP-1）。另注意 HOME/Analysis 走**另一条** current-version 投影（`currentVersionRowsForLedger`），其有效谓词见 §3.6。

### 1.7 首切片必须 schema 迁移

- 现有表无一可承载「作废/恢复事实」：核心表无状态列（§1.4）；request/receipt 表均为**单意图专用、无状态列**（`manual_expense_request` `:65`、`confirmed_expense_receipt` `:79` 等；更新对 `transaction_note_update_request`/`confirmed_transaction_note_update_receipt` `:92-124` 亦为单意图对），且创建回执含 `transaction_id UNIQUE`/一请求一回执语义，无法表达对既有交易的第二次事实；改写冻结行被规则（`ARCHITECTURE.md:19`、`ACCOUNTING_RULES.md:34`）与 guard 触发器禁止。
- 因此首切片新增 append-only 作废/恢复事实 owner（形状见 §4.3）；迁移只新增边、**不分配版本号**（沿计划 `:149`「迁移只新增边，版本在实施时分配，不预占 v31」），本规格不写迁移。

## 2. 目标与范围

### 2.1 目标

- 05.A：冻结逐 kind 的支持矩阵与契约（可改字段、可作废/恢复条件、依赖关系），不支持类型显式说明并保持 OPEN。
- 05.B：普通支出/收入的版本修正：预览差异 → 明确确认 → `expected current version` CAS → 原子写入 → 权威回读；历史版本与来源不可覆盖。
- 05.C：逻辑作废 + 不可变操作历史 + 回收站（原因/时间/依赖）+ 恢复重校验；作废后有效余额/报表按冻结口径移除原效果；恢复恰好恢复一次；取消/拒绝零效果。
- 05.D：详情编辑/作废入口、历史差异、回收站与恢复确认；两端共享用例，Android 人工验收。

### 2.2 首切片范围（冻结）

**范围内：** 仅**手工创建**的 `EXPENSE`/`INCOME`（创建入口 = `MANUAL_CREATED` 或 `UNMARKED`，`LedgerEntryReadModels.kt:63-69`）的（a）备注修正、（b）`statisticsAt` 修正、（c）金额/分类/资金账户修正、（d）逻辑作废、（e）回收站列出与恢复；application 端口与用例、ledger-data 新增非 `rgXX_` 产品表与加性迁移、两端组合根接线、`app-ui` 详情/回收站最小面、V-01..V-23。

**范围外（本批明确不做，逐项冻结）：** 转账、借贷（LEND/COLLECT）、退款与导入关联交易（含导入创建的交易）的修正/作废/恢复；`occurredAt` 修改；kind 修改；永久删除/清除；批量作废；作废撤销（void 后的再作废或二次循环，首切片每笔最多一次作废 + 一次恢复）；多币种；自动重试；`rgXX_` 竖井与 golden 的任何改动。

### 2.3 与既有冻结面的关系

- 修正不改变 `ACCOUNTING_RULES.md:34`「退款、撤销和真实冲回是新的经济事件」：作废**不创建补偿交易**，只移除原交易的有效效果；真实冲回仍须走独立经济事件（回收站不得替代）。
- 作废不改变对账状态、证据链接、借贷本金历史与导入 owner（§1.4 表）；恢复同样零写入这些 owner。
- 本批不反转 P7-03 的只读读模型契约（D-145）与 P7-04 的候选终态契约（D-146），只在其上新增有效/作废谓词与新读路径。

## 3. 支持矩阵与契约（05.A）

### 3.1 支持矩阵（逐 kind；首切片冻结，其余 OPEN）

| kind | 备注 | `statisticsAt` | 金额/分类/资金账户 | 作废/恢复 | 切片 | 决定点 |
| --- | --- | --- | --- | --- | --- | --- |
| `EXPENSE`（手工创建） | 可改 | 可改 | 可改 | 可作废/恢复 | **首切片** | — |
| `INCOME`（手工创建） | 可改 | 可改 | 可改 | 可作废/恢复 | **首切片** | — |
| `EXPENSE`/`INCOME`（导入创建） | 待定 | 待定 | 待定 | 待定 | 后续切片 | DP-5（原候选能否再确认、终态守卫） |
| `CREDIT_REPAYMENT`（导入创建） | 待定 | 待定 | 待定 | 待定 | 后续切片 | DP-5（导入 lineage：`CreditFlowFormalFactory.kt:107-135` → `createCreditPrincipalRepayment` → `TransactionKind.CREDIT_REPAYMENT`（`MixedPayment.kt:206`），两端组合根已装配 `CreditFlowFormalFactory`（`android-app/.../App.kt:507`、`desktop-app/.../Main.kt:573`），故为**产品可达**；首切片不含） |
| `ACCOUNT_TRANSFER` | 待定 | 待定 | 待定（含手续费腿） | 待定 | 后续切片 | DP-2 |
| `LEND`/`COLLECT` | 待定 | 待定 | 待定 | 待定（本金历史硬墙） | 后续切片 | DP-4 |
| `REFUND_RECEIPT` | 待定 | 待定 | 待定 | 待定（关联退款场景） | 后续切片 | DP-13 |
| `OPENING_BALANCE`/`STORED_VALUE_*`/`PREPAID_*`/`BALANCE_ADJUSTMENT` | 不支持 | 不支持 | 不支持 | 不支持 | 不交付 | 产品组合根无可达创建路径（`ledger_transaction.kind` CHECK `Ledger.sq:4` 仅五值，其余经 `canonical_kind` 表达；产品类型权威见 `ARCHITECTURE.md:81`（D-144）；复核：`TransactionKind.OPENING_BALANCE`/`STORED_VALUE*`/`PREPAID*`/`BALANCE_ADJUSTMENT` 在 `ledger-application`/`ledger-data` commonMain 与两端组合根的非 RG 路径零命中，创建仅存在于 `Rg08FixtureReplay.kt:1034`（OPENING_BALANCE）与 `Rg09`/`Rg10`/`Rg11` 回放）；**显式说明不支持**，不得静默 no-op |

- 冻结：**永不修改 kind**（含 `canonical_kind`）；修正后的交易保持原 kind、原 `occurredAt`、原创建身份与来源关系。
- 不支持类型/切片一律**类型化拒绝零写入**（`P705_KIND_NOT_SUPPORTED`/`P705_CREATION_LINEAGE_NOT_SUPPORTED`），不得以「看起来无效果」静默通过；支持矩阵行是实施与验收的分母，未交付切片继续 OPEN，不能用普通支出通过宣布全批完成（计划 `:74`）。

### 3.2 修正契约（05.B）

- **流程（冻结）**：读当前有效版本 → 预览差异（纯读，零写入）→ 用户明确确认 → 分配新 requestId → claim-first 原子提交（`expectedCurrentVersionId` CAS）→ 权威回读。预览不得作为提交许可（沿 `ACCOUNTING_RULES.md:261` 目录版本纪律的同一原则）。
- **字段集（首切片冻结）**：`note`、`statisticsAt`、`amount`、`categoryId`、`fundingAccountId`。`occurredAt` 不在内（DP-7）。
- **写形（冻结）**：以当前版本为基追加 `version_number + 1`；`note`/`statisticsAt` 复用当前 posting set（`TransactionVersionChange.Note`/`StatisticsAt`）；金额/分类/资金账户修正使用 `TransactionVersionChange.Postings`（完整替换 posting set，必须逐币种平衡且覆盖旧 set 每条 posting 恰好一次）。**三个时间列显式冻结**：新版本 `occurred_at` 与 `effective_at` **逐字复制**当前版本（域原语 `currentVersion.copy(...)` 不改这两列，`TransactionVersionAppend.kt:86-104`），仅 `statistics_at` 随请求变更（未提交 `statisticsAt` 时也逐字复制）。旧版本、旧 posting set、创建 receipt 与来源关系一律不改写。
- **校验（冻结）**：新版本的账户/分类引用必须在写入事务内按**当前权威目录**重校验（存在、同账本、kind、active、叶子/自有真实资产等，复用 P7-01/P7-02 准入 token 族；`ACCOUNTING_RULES.md:255-261`）；金额必须为币种精度的精确十进制且为正；备注长度沿既有上限。
- **对账影响（冻结；DP-10 已收窄）**：受影响资金腿 = 新版本中真实资产/负债（`catalog_account.real_account = 1`）分录且 `(accountId, amount, currency)` 相对旧版本发生变化者。**未变化资金腿的对账行与证据链接零改动**；非资金腿（费用/收入过账账户）变化不构成资金腿失效。**首切片对账暴露为零**：手工创建的 `EXPENSE`/`INCOME` 不存在 `posting_reconciliation` 行，也不存在 `evidence_link` 行（对账/证据行只由 ACCOUNT_TRANSFER 资格面产生：v23 迁移种子仅覆盖 `COALESCE(canonical_kind, kind) = 'ACCOUNT_TRANSFER'` 的 posting（`Ledger.sq:8688-8704`），手工提交端口对这两张表零写入），故**无失效对象**。因此首切片**不组合、也不授权组合** D-113 `P408CorrectionCommitPort`；DP-10 的 D-113 组合授权**收窄到后续转账切片**单独裁决（§6；D-113 查询设计不在本批重开）。首切片仍需断言这些 owner 零写入（V-12），因为其成立依据是「无行可写」而非「无需写」。
- **无效/无变化**：同 requestId 同快照 replay → `NoChange` 返回原 receipt、零新增实体；同 requestId 不同快照 → `RequestIdentityConflict` 零写入；`expectedCurrentVersionId` 非当前 → `P705_STALE_CURRENT_VERSION` 零写入（不自动重试、不静默改用最新版本）；请求与结果完全一致 → `NoChange`。**结果面冻结**：stale 版本以结果族独立变体 `StaleCurrentVersion` 表达（`P705_STALE_CURRENT_VERSION` 为其稳定码，沿 `ConfirmedTransactionNoteUpdateResult.StaleCurrentVersion` `ConfirmedTransactionNoteUpdate.kt:59` 先例），不折叠进 `Rejected`；`Rejected(code)` 只承载域/准入/前置条件拒绝。**replay 先于 CAS 求值**：claim 未赢得时先按 request 身份比对快照并返回 `NoChange`/`RequestIdentityConflict`，只有赢得 claim 的请求才求值 CAS——故「成功后重放携带新读到的 CAS token」按快照不等判定为身份冲突，绝不误判为 stale（§4.3）。
- **冻结判定**：修正当天不产生虚假现金流（`ACCOUNTING_RULES.md:249`）：版本替换不创建任何新的经济事件、不新增现金流；100 → 80 修正后只有 80 参与余额与报表（读路径已 join current version，`Ledger.sq:8762-8767`）。

### 3.3 作废与恢复契约（05.C）

- **语义（冻结）**：作废 = 追加一条不可变事实，使该交易从**全部有效派生面**移除；不删除、不改写、不创建补偿交易、不改变任何历史行。恢复 = 追加第二条事实使原交易重新有效；每笔交易首切片最多一次作废 + 一次恢复（`sequence ≤ 2`）。
- **有效谓词（冻结）**：交易有效 ⟺ 其作废/恢复事实序列的最后一条不是「作废」；无事实 = 有效。谓词只有一处定义（SQL 命名查询/视图片段），所有有效面共用；不得各面各写一份。SQL 谓词与领域 `TransactionVoidState.isEffective`（§4.1）必须逐例等价，由 V-23 断言（SQL 派生有效集 == 领域派生有效集）。
- **有效时间口径（DP-3，D-156 批准）**：作废**追溯移除**原交易在其 `statistics_at` 所属月份的效果；作废日不产生任何新的月份/现金流/分类效果。恢复同样不产生新效果，只让原交易按原 `statistics_at` 重新参与。
- **恢复重校验（冻结）**：恢复必须按**当前**目录重校验该交易当前版本的账户/分类引用；不可准入时类型化拒绝（`P705_CATALOG_REFERENCE_NOT_ADMISSIBLE`）且交易保持作废，**不得盲目恢复旧状态、不得静默替换引用、不得改写历史版本**。**仅「停用/不可准入」构成拒绝理由**：改名但 active 的账户/分类仍可准入（目录改名只改变显示名，稳定 ID 不变，`ACCOUNTING_RULES.md:261`），不构成拒绝。恢复零写入对账 owner、证据链接、借贷历史与导入 owner（§1.4）。
- **作废前置条件（冻结）**：交易存在且在当前账本；交易有效（未作废）；交易为支持矩阵内的手工创建 `EXPENSE`/`INCOME`；**该交易不存在有效关联退款**（DP-13 首切片否决，登记后续切片）；**该交易尚未作废过**（DP-8 深度 ≤ 2：恢复后再次作废 → `P705_VOID_CYCLE_EXHAUSTED` 类型化拒绝零写入）；必须明确确认；必须携带原因（回收站要列明原因/时间/依赖）。任一不满足 → 类型化拒绝零写入。
- **原因（冻结）**：必填，取冻结的类型化码集 + 可选有界说明；长度上限在实施时冻结并在规格修订中登记。原因与说明**不得进入日志或测试失败消息**（仅存于事实行、经回收站读路径展示；当前产品无崩溃上报面，该维度不可观测、不作为断言）；领域类型（§4.1）是该信息的唯一替代表达，存储形式与领域形式必须有等价性测试（V-22）。
- **恢复前置条件（冻结）**：交易存在、当前为作废态、尚未恢复过（DP-8 深度 ≤ 2）、目录重校验通过、必须携带原因（DP-11：作废/恢复同规则）、明确确认。
- **幂等（冻结）**：同 requestId 同快照 replay 返回原 receipt（`NoChange`）；同 requestId 不同快照 → `RequestIdentityConflict` 零写入；失败/拒绝整事务回滚（claim 一并回滚，身份可重试，沿 D-098 领域 4 语义）。
- **不写对账/证据/导入/借贷**：作废与恢复对 §1.4 表零写入，由行级/触发器断言证明（V-12、V-15）。
- **永久删除**：首版不提供（计划 Q12 推荐）；若未来需要，须另立隐私/证据保留与不可逆操作门（DP-6）。

### 3.4 读路径与回收站（05.C/05.D；DP-1）

- **事实**：`loadLedgerEntryRows` 是月度（`QueryMonthlyActivity.kt:92`）、流水（`QueryLedgerEntryRows.kt:16`）、详情（`QueryTransactionDetail.kt:38`）的唯一行集来源；行集若按作废过滤，作废交易在详情得 `NotFound`（`QueryTransactionDetail.kt:176`），回收站无法复用该路径。另有一条独立 current-version 投影（`currentVersionRowsForLedger`）供 HOME 余额与 Analysis Tab，见 §3.6 枚举。
- **冻结约束**：回收站读路径**不得发明第二事实来源**——它必须从同一组表（`ledger_transaction` + `ledger_transaction_current_version` + `transaction_version` + 作废事实 owner）派生，与有效面共用同一有效谓词与同一行形状（`LedgerEntryRow` 或其超集），差异只在谓词与附加事实元数据。
- **建议（DP-1，D-156 按建议批准）**：保留 `loadLedgerEntryRows` 的「有效行」语义（既有消费者零语义变化），新增只读端口方法（如 `loadVoidedTransactionRows(ledgerId)`）返回作废态交易的行 + 作废元数据；回收站与「作废详情」由该路径提供，恢复入口从回收站进入。备选（未采纳）：给行集/详情增加显式 `includeVoided` 参数（同一查询、显式开关），回收站与详情共用。
- **可及性（冻结要求，不因选项变化）**：作废交易必须能在回收站中列出（原因、作废时间、依赖说明），并可从回收站确认恢复；作废交易的历史（版本、创建入口、来源关系）必须可读，不得因作废而不可达。

### 3.5 产品接线（05.D）

- 详情屏（`P503LedgerView.kt:342`）新增「编辑」与「作废」入口（仅支持矩阵内的交易显示）；编辑面 = 字段表单 + 差异预览 + 明确确认；作废面 = 原因 + 明确确认；回收站 = 列表 + 恢复确认；恢复前展示当前目录重校验结果。
- 事件与状态沿既有 `P503UiEvent`/`P503AppState`/reducer 纪律扩展；新增事件只为 P7-05 定义转换，既有事件 × 状态矩阵不得改变（沿 P7-02 §6.2 G-B 先例）；`Submitting`/`UnknownCommit` 期间不得离开，提交中不重入。
- 两端组合根（`android-app/.../App.kt`、`desktop-app/.../Main.kt`）装配同一 application 用例与端口；共享 `app-ui` 不直接访问数据库（`ARCHITECTURE.md:55-58`）。
- 修正/作废/恢复成功后走权威回读（沿 P5-04.4/P7-02 先例）；详情/流水/月度在同一刷新链上更新；作废后从有效面消失、在回收站出现。

### 3.6 有效派生面枚举与 P7-03 读模型重冻结（P705QUAL-001/002/004）

**背景（复核）**：P7-03 的读模型有**两个**独立的 current-version 投影，不是一条。`ledgerEntryRowsForLedger`（`Ledger.sq:8748`）供月度/流水/详情；`currentVersionRowsForLedger`（`Ledger.sq:8713-8740`）经 `SqlDelightLedgerCurrentStateReadAdapter.kt:47-74` 供 `QueryLedgerCurrentState`（`QueryLedgerCurrentState.kt:64-92`）的 `transactions` 与 `balances`，即 HOME 的「当前交易」回退与**账户余额**（`P503App.kt:365`/`:827` → `P503OverviewScreen.kt:101`/`:109`），以及 Analysis Tab 整期汇总的输入（`SummarizeLedgerActivity.kt:48-99`，调用点 `P503TabShell.kt:171`）。只过滤前者会让作废交易保留余额效果并继续计入 Analysis Tab——正是 R-1 的失效模式，且直接违反 D-156 DP-3。

**冻结**：有效谓词必须施加到**每一个**交易派生读面。下表为本轮从代码逐项枚举的结果；实施时若出现新消费者必须登记并纳入，不得遗漏：

| # | 读面 | 入口（查询 / 适配器 / 用例） | 消费点 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | 月度活动（月卡/分类/趋势） | `ledgerEntryRowsForLedger`（`Ledger.sq:8748`）→ `SqlDelightLedgerCurrentStateReadAdapter.kt:191-192` → `QueryMonthlyActivity.kt:92` | `P503LedgerView`/`P503TabShell` | **过滤**（有效谓词） |
| 2 | 流水列表 | 同 #1 → `QueryLedgerEntryRows.kt:16` | `P503OverviewScreen.kt:96` | **过滤** |
| 3 | 交易详情 | 同 #1 → `QueryTransactionDetail.kt:38-42` | `P503LedgerView.kt:342-408` | **过滤**（作废交易经回收站路径，§3.4） |
| 4 | HOME 账户余额 + 「当前交易」回退 | `currentVersionRowsForLedger`（`Ledger.sq:8713-8740`）→ adapter `:47-74` → `QueryLedgerCurrentState.kt:64-92` | `P503App.kt:365`/`:827`；`P503OverviewScreen.kt:101`/`:109` | **过滤（本轮 P1 修正新增）** |
| 5 | Analysis Tab 整期汇总 | 同 #4 的 `LedgerCurrentState.transactions` → `SummarizeLedgerActivity.kt:48-99` | `P503TabShell.kt:171` | **过滤（输入面即 #4）** |
| 6 | 详情对账腿投影 | `transactionReconciliationLegs` → adapter `:272-276` | `QueryTransactionDetail.kt:58` | 不额外过滤（只经 #3 入口可达；首切片无对账行） |
| 7 | 创建入口/来源溯源 | `importCreationConfirmationByTransaction`/`manualCreationReceiptByTransaction` → adapter `:223`/`:242` | `QueryTransactionDetail.kt:104-109` | 不过滤（provenance：作废交易仍须可读来源，V-02） |
| 8 | 手工链 request/receipt 反查 | adapter `:76-169` | snapshot-aware resolver | 不过滤（请求身份面，非经济面） |
| 9 | 账户目录管理面（余额列） | `QueryCatalogSnapshot.query(ledgerId)`（`CatalogProjection.kt:72-79`），组合根以默认 `balanceMinorUnits = { null }` 调用（`android-app/.../App.kt:555`、`desktop-app/.../Main.kt:622`） | `P503CatalogManagementScreen` | 当前不派生余额（provider 为 null）；**若未来接入余额 provider，必须纳入有效谓词并登记受影响面**（不在首切片） |
| 10 | 借贷位置/往来对象 | `SqlDelightCounterpartyStore.kt`（`lending_position*`） | `QueryLendingPositions` | 首切片范围外（借贷后续切片 DP-4）；作废/恢复零写入 |
| 11 | 导入候选/重复审核 | `SqlDelightImportReviewReadAdapter.kt`（`import_*`） | 导入审核面 | 不适用（候选生命周期，非正式账目面）；作废/恢复零写入 |
| 12 | 置顶偏好 | `SqlDelightEntryPreferenceStore.kt` | 排序 | 不适用（非经济面） |
| 13 | 对账/证据投影 | `SqlDelightP408ReconciliationStore.kt`/`SqlDelightEvidenceProjectionStore.kt` | 对账面 | 首切片无行；作废/恢复零写入 |
| 14 | RG 回放竖井 | `rgXX_` stores | RG oracle | 不适用（冻结竖井零改动） |
| 15 | 目录删除引用面 | `catalogReferencedPostingAccounts`（`Ledger.sq:9518-9519`）→ `SqlDelightCatalogStore.kt:566` | P7-01 目录管理（「无任何经济引用」才可删除） | **不过滤（有意决定，非遗漏）**：作废交易的 posting 仍计为经济引用、继续阻止账户删除——历史行保留，引用面必须与历史一致；作废不解除引用，用户须先恢复再按 P7-01 处置（若未来要允许删除，须另立决定点并明确历史归属） |

**P7-03 读模型重冻结（P705QUAL-004）**：本批**重开并重冻结** D-145 的读模型语义——D-145 冻结的「current-version 即有效」重冻结为「current-version **且未被作废**」。受影响锚点：#4 `loadCurrentRows`/`currentVersionRowsForLedger`、#4 `QueryLedgerCurrentState.transactions`/`balances`、#5 `SummarizeLedgerActivity` 输入语义、#1-#3 `loadLedgerEntryRows`/`ledgerEntryRowsForLedger`。D-145 登记的 22 个既有测试锚（`SummarizeLedgerActivityTest` 7 / `QueryLedgerCurrentStateTest` 9 / `SqlDelightLedgerCurrentStateReadAdapterTest` 6）在**无作废事实的账本上逐值不变**（谓词为 no-op），故不重写既有期望、只新增向量。**A-DOC 同步清单（实施批范围）**：D-145 规格文档中的「current-version 即有效」表述、`ACCOUNTING_RULES.md:299` 的读模型口径、以及 `LedgerEntryReadModels.kt:18` 的 "Existing [CurrentVersionRow] loads are untouched" 注释（本批后不再成立）必须随实施批同步重述。

## 4. 领域 / 应用 / 持久化 / UI 契约

### 4.1 领域（`ledger-domain`）

- **复用**：`FormalTransaction.appendVersion`（`TransactionVersionAppend.kt:43`）与三形态 `TransactionVersionChange`（`:25-39`）作为唯一版本追加原语；不改其语义。
- **新增**：产品侧修正校验纯函数（字段集/准入/金额/平衡/受影响资金腿派生）；作废/恢复无领域新原语（事实与谓词属持久化/应用面），但「有效谓词」的领域等价表述（`TransactionVoidState` 纯值类型 + `isEffective`）必须单点定义；作废原因以单一领域类型 `VoidReason`（类型化码 + 可选有界说明）表达，是该信息的**唯一替代表达**（存储形式 ↔ 领域形式等价性测试 V-22），UI 与日志不得各自再造一份。
- **不改既有规则**：`PostingFactsCorrection`（RG-12 校验器）、`OrdinaryExpense`/`OrdinaryIncome` 工厂、`LendingPosition`/`LendingSettlement`、退款规则一律不动。

### 4.2 应用（`ledger-application`）

- **端口与用例（首切片）**：`CorrectTransactionVersion`（请求：ledgerId/requestId/transactionId/expectedCurrentVersionId/字段载荷/明确确认）、`VoidTransaction`、`RestoreTransaction`、`QueryRecycleBin`（只读）；结果族沿既有四态（`Created`/`NoChange`/`Rejected(code)`/`Conflict`）+ `StaleCurrentVersion`，与 `ConfirmedTransactionNoteUpdateResult`（`ConfirmedTransactionNoteUpdate.kt:46-63`）同构。
- **失败码（稳定命名，首切片冻结；code 稳定、message 不稳定不比较）**：

| code | 触发 | 结果 |
| --- | --- | --- |
| `P705_TRANSACTION_NOT_FOUND` | 交易不在当前账本 | Rejected，零写入 |
| `P705_KIND_NOT_SUPPORTED` | kind 不在首切片支持矩阵（含 transfer/lending/refund/其余特殊 kind） | Rejected，零写入 |
| `P705_CREATION_LINEAGE_NOT_SUPPORTED` | 导入创建的交易（首切片不含，DP-5） | Rejected，零写入 |
| `P705_REFUND_LINKED_VOID_NOT_SUPPORTED` | 作废目标存在有效关联退款（DP-13 首切片否决；原交易作废后退款的报表归属语义未冻结） | Rejected，零写入，登记后续切片 |
| `P705_FIELD_NOT_SUPPORTED` | 请求含 `occurredAt`/kind/未冻结字段 | Rejected，零写入 |
| `P705_STALE_CURRENT_VERSION` | `expectedCurrentVersionId` ≠ 当前版本（**以结果族独立变体 `StaleCurrentVersion` 表达，见 §3.2「结果面冻结」**） | `StaleCurrentVersion`，零写入，不自动重试 |
| `P705_TRANSACTION_VOIDED` | 对作废交易发起修正/作废（DP-8 裁决前一律拒绝） | Rejected，零写入 |
| `P705_TRANSACTION_NOT_VOIDED` | 对有效交易发起恢复，或恢复已恢复过的交易 | Rejected，零写入 |
| `P705_VOID_CYCLE_EXHAUSTED` | 交易已作废并已恢复，再次作废（DP-8 深度 ≤ 2） | Rejected，零写入 |
| `P705_VOID_REASON_REQUIRED` | 作废缺原因或原因超限 | Rejected，零写入 |
| `P705_CATALOG_REFERENCE_NOT_ADMISSIBLE` | 提交/恢复时目录引用不存在、跨账本、停用或类型不符 | Rejected，零写入 |
| `P705_MATCHED_FUNDING_LEG_CHANGED` | 受影响资金腿的失效组合未按 DP-10 裁决落地（首切片授权面为空，故为防御性死码） | Rejected，零写入 |
| `P705_NO_CHANGE` | 请求与当前状态完全一致 | `NoChange`（原 receipt），零新增 |
| `P705_REQUEST_IDENTITY_CONFLICT` | 同 requestId 不同快照 | Conflict，零写入 |
| `P705_CONSTRAINT_VIOLATION` | 唯一/FK/触发器兜底失败 | 整事务回滚，类型化拒绝 |

- **快照感知 resolver**：提交后异常按完整快照/receipt 判定 success/conflict/unknown；Unknown 不自动重试、不换 requestId（沿 P7-02 S-3）。

### 4.3 持久化形状（`ledger-data`；只冻结形状，不分配版本号、不写迁移）

- **作废/恢复事实 owner（新增，append-only）**：`transaction_void_fact`——`(ledger_id, transaction_id, sequence)` 主键；`fact_id TEXT NOT NULL` + `UNIQUE(ledger_id, transaction_id, fact_id)`（回执 FK 的父键）；`fact_kind ∈ {void, restore}`；`reason_code`（必填，冻结码集）+ 可选有界 `reason_note`；`request_id`、`confirmation_id`、`created_at`；`UNIQUE(ledger_id, request_id)`（一请求一事实）；FK `(ledger_id, request_id, fact_kind)` → `transaction_void_request(ledger_id, request_id, fact_kind)`（**单一父表**，void/restore 合并见下），FK `(transaction_id, ledger_id)` → `ledger_transaction`。守卫：`sequence` 必须为当前最大 + 1（禁止跳号/回填）；`void` 只能作为 sequence 1、`restore` 只能紧随 `void`（交替，首切片深度 ≤ 2）；`BEFORE UPDATE`/`BEFORE DELETE` 双 ABORT 触发器（沿 `lending_position_history_guard_*` `Ledger.sq:225-228` 与 `evidence_link_guard_*` `:8197-8198` 款）。
  - **谓词服务（冻结）**：有效谓词 = 最新事实不是 `void`（无事实 = 有效）；事实表主键前缀 `(ledger_id, transaction_id)` 直接服务「按交易取最新事实」，无需为该谓词新增索引（V-15 断言索引存在性与 PK 前缀的服务性；**不断言 `EXPLAIN QUERY PLAN`**——仓库无该先例且计划文本跨 SQLite 版本脆弱，见 V-15）。
  - **回收站排序索引（冻结）**：新增索引 `(ledger_id, created_at DESC, transaction_id)`，服务回收站按作废时间倒序稳定排序（`Ledger.sq` 现无此索引）；排序键 = `(created_at DESC, transaction_id ASC)` 确定性全序（同刻多笔可稳定复现）。
  - **统计刷新（冻结）**：作废/恢复提交后沿用既有权威刷新链，月度/余额/Analysis 面必须在同一次刷新中重读（不允许只刷局部面），沿 D-153 触发 (f) 语义。
- **请求/回执（新增，claim-first；快照列与唯一性冻结）**：
  - 每族一对 request/receipt：`transaction_correction_request`/`receipt`、`transaction_void_request`/`receipt`（**void 与 restore 合并为一族**，以 `fact_kind ∈ {void, restore}` 判别并纳入唯一键——SQLite 无法用单个 FK 指向两张父表，合并后事实表/回执的 FK 只有一个父表）。
  - **request 快照列（逐族冻结，规范化后逐列比较）**：correction = `(ledger_id, request_id, transaction_id, expected_current_version_id, note, statistics_at, amount_minor, currency_code, currency_precision, category_id, funding_account_id, confirmation_marker = 'explicit_manual_save')`——存**修正后的完整目标状态**（非差分）；规范化 = `statistics_at` 取 `Instant.toString()` UTC 串、金额取 minor units + 币种码 + 精度、ID 取稳定 ID 原文、`note` 原文（可空按 `NULL`）。void/restore 共用一族 = `(ledger_id, request_id, transaction_id, fact_kind, reason_code, reason_note, confirmation_marker)`（restore 亦必填原因，DP-11）。比较排除生成列（`confirmation_id`/`version_id`/`fact_id`/`created_at`）。
  - **receipt 唯一性（冻结；不照抄创建回执）**：创建回执含 `transaction_id UNIQUE`（`confirmed_expense_receipt` `Ledger.sq:79-91`），照抄会阻断同一交易的第二次修正。故 `transaction_correction_receipt` = `PRIMARY KEY (ledger_id, request_id)`、`confirmation_id UNIQUE`、`version_id UNIQUE`、`transaction_id` **不唯一**（同交易可多次修正、各得独立版本）；FK = `(ledger_id, request_id)` → correction request（`ON DELETE CASCADE`）、`(transaction_id, version_id, ledger_id)` → `transaction_version`、`(transaction_id, expected_current_version_id, ledger_id)` → `transaction_version`（沿 `confirmed_transaction_note_update_receipt` `Ledger.sq:105-124` 形状）。`transaction_void_receipt` = `PRIMARY KEY (ledger_id, request_id)`、`confirmation_id UNIQUE`、`fact_id UNIQUE`、`transaction_id` **不唯一**、`fact_kind ∈ {void, restore}`；FK = `(ledger_id, request_id, fact_kind)` → `transaction_void_request`（单一父表，`ON DELETE CASCADE`）、`(ledger_id, transaction_id, fact_id)` → `transaction_void_fact`（**父键 = 事实表 `UNIQUE(ledger_id, transaction_id, fact_id)`**，已在上方声明，满足 SQLite 对 FK 父键必须是 PK/UNIQUE 的要求）。
  - **孤儿防护（冻结）**：事实行的 request FK、回执行的 fact/version FK 与 claim 同事务写入；失败/拒绝整事务回滚（claim 一并回滚，D-098 领域 4），故不存在「无 request 的事实」或「无事实/版本的回执」；`UNIQUE(ledger_id, request_id)` 保证一请求至多一事实/一版本。
  - **claim 语义（冻结）**：`INSERT ... ON CONFLICT DO NOTHING` + `lastStatementChangedRowCount()`；**未赢得 claim 时先按 request 身份比对快照**（相等 → `NoChange` 原 receipt；不等 → `RequestIdentityConflict`），**只有赢得 claim 的请求才求值 CAS**（消除「成功后重放携带新读到的 CAS token」的歧义，§3.2）。
- **版本复制（新增 SQL）**：以 `expected_current_version_id` 为条件、`INSERT INTO transaction_version ... SELECT ... version_number + 1, <new_posting_set_id>, occurred_at（逐字复制）, statistics_at（请求值，未提交则逐字复制）, effective_at（逐字复制）, note ...` 复制当前版本并绑定新 posting set；`lastStatementChangedRowCount() == 1` 失败即 `P705_STALE_CURRENT_VERSION` 零写入；随后 `compareAndSetCurrentVersion`（`Ledger.sq:2127`）推进 current-version，`lastStatementChangedRowCount() == 1` 为硬断言（沿 `SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:47-64` 先例）。新 posting set 与 posting 行必须同事务插入且逐币种平衡。
- **读路径**：有效谓词以命名查询/视图片段单点定义，`ledgerEntryRowsForLedger`（`Ledger.sq:8748`）、`currentVersionRowsForLedger`（`:8713-8740`）与新回收站查询共用（§3.6）；新查询返回作废态行 + 作废元数据（§3.4）。既有 22 个锚点在无作废事实账本上逐值不变（谓词为 no-op；R-7 语义见 `LedgerEntryReadModels.kt:18`），不重写既有期望。
- **迁移**：加性、只建结构零回填，fresh = migrated 逐字一致；**版本号在实施时分配**（计划 `:149`），本规格不预占。

### 4.4 UI 状态与事件（最小）

- 新增只读/编辑状态（如 `TransactionEdit`、`VoidConfirm`、`RecycleBin`）与对应事件；`Editing` 面复用 P7-02 的字段保留/键入保留纪律不适用于本面（独立面，不得改变既有录入面语义）。
- 差异预览必须逐字段显示旧值/新值（金额带符号与币种、分类/账户显示当前名、时间显示两个时区语义），确认页显示「历史版本保留、旧版本失效」的说明；作废确认页显示原因与影响面（该交易将从月度与流水中移除、可在回收站恢复）；恢复确认页显示目录重校验结果。
- 作废交易在有效面（月度/流水/详情/HOME 余额/Analysis）不可见；回收站列表按 `(作废时间 DESC, transaction_id ASC)` 稳定排序，逐行展示**原因、作废时间、依赖说明**（依赖 = 该交易当前版本引用的账户/分类当前名与可准入状态、是否关联退款、创建入口），恢复前展示目录重校验结果与「不可准入」原因；恢复成功后回到有效面并由权威回读刷新。
- **「历史差异」的冻结解释（P705SPEC-010）**：05.D 的「历史差异」= **提交前的差异预览**（旧值/新值逐字段），首切片**不提供事后版本历史视图**；旧版本数据始终保留且可经持久化层审计（V-02），但产品面不新增历史列表/对比页。若后续批次要提供事后历史视图，须另立决定点与向量（不在首切片）。
- **原因隐私（冻结）**：作废/恢复原因与说明只存于事实行、只经回收站读路径展示；**不得进入日志或测试失败消息**（V-21 断言；当前产品无崩溃上报面，该维度不可观测）。

## 5. 验收矩阵（V-01..V-23；首切片）

| # | 场景 | 期望 | 关联 | 证据 |
| --- | --- | --- | --- | --- |
| V-01 | 作废一笔普通支出/收入 | **全部有效派生面**（§3.6 #1-#5：月度/分类/趋势/流水/详情/HOME 余额/「当前交易」/Analysis 计数与金额）不再包含其效果，且月度 `transactionCount` 不含该笔（DP-3）；原交易在回收站可见 | §3.3、§3.6、D-156 DP-3、`ACCOUNTING_RULES.md:249` | 自动 |
| V-02 | 作废后历史与来源保留 | 原版本、原 posting set、创建 receipt、创建入口与来源关系仍可读；无任何历史行被改写/删除 | §3.3、§1.4 | 自动 |
| V-03 | 恢复恰好一次 | 恢复后交易重新有效且效果与作废前逐值一致；第二次恢复请求（新 requestId）类型化拒绝零写入 | §3.3 | 自动 |
| V-04 | 取消/拒绝零效果 | 预览后取消、目录/字段/原因拒绝、CAS 冲突 → 零新增实体、零余额/报表/对账变化 | §3.2、§3.3 | 自动 |
| V-05 | 金额修正 100 → 80 | 只有 80 有效（余额/月度/分类/Analysis）；修改日无虚假 20 现金流；旧版本 100 仍保留可查；`occurred_at`/`effective_at` 逐字未变（§3.2 写形） | §3.2、`ACCOUNTING_RULES.md:249` | 自动 |
| V-06 | 修正保留版本历史 | 新版本 `version_number + 1`；旧版本、旧 posting set 不被覆盖；CAS 以 `expectedCurrentVersionId` 为条件 | §3.2、§4.3 | 自动 |
| V-07 | 跨月修正 | 改 `statisticsAt` 后效果只计正确月份；`occurredAt` 与来源凭证时间不变 | §3.2、`ACCOUNTING_RULES.md:206` | 自动 |
| V-08 | 对账暴露为零（首切片；**前置：transfer-free fixture**） | 在**不含 ACCOUNT_TRANSFER 的 fixture** 上，手工 `EXPENSE`/`INCOME` 修正前后 `posting_reconciliation*`/`evidence_link*` 行数恒为 0 且零写入；未变化资金腿零改动；`P705_MATCHED_FUNDING_LEG_CHANGED` 不可达（防御性死码）。含转账的账本以 V-12 的「逐值不变」为准（该形式不依赖 fixture 无转账） | §3.2（DP-10 收窄）、`ACCOUNTING_RULES.md:253`、D-048 | 自动 |
| V-09 | stale CAS 冲突（新 requestId） | 新 requestId 携带旧 `expectedCurrentVersionId` → `StaleCurrentVersion`（`P705_STALE_CURRENT_VERSION`）零写入；不自动改用最新版本；重读后可重试 | §3.2 | 自动 |
| V-10 | 幂等、身份冲突与求值顺序 | 三类操作同 requestId 同快照 replay → 原 receipt 零新增；同 requestId 不同快照（含成功后重放携带新读到的 CAS token）→ 冲突零写入；**replay 判定先于 CAS 求值**（不误判 stale） | §3.2、§4.3 | 自动 |
| V-11 | 恢复按当前目录重校验 | 资金账户/分类**停用**后恢复 → 类型化拒绝且交易保持作废；**改名但 active** 的引用仍可准入（不构成拒绝）；不盲目恢复旧状态、不静默替换引用 | §3.3 | 自动 |
| V-12 | 对账/借贷/导入 owner 零写入 | 作废/恢复/修正后 `posting_reconciliation*`、`evidence_link*`、`evidence_projection`、`lending_position*`、`import_confirmation`/`import_receipt`/`import_candidate_status_history` 行数与内容逐值不变（首切片这些 owner **本就无行**，断言同时证明「无行可写」与「未写」） | §1.4、§3.2、§3.3 | 自动 |
| V-13 | 支持矩阵强制 | 转账/借贷/退款/导入创建交易（含 `CREDIT_REPAYMENT`）与不支持 kind 的修正/作废/恢复 → 类型化拒绝零写入；矩阵行与实现一一对应（含 `CREDIT_REPAYMENT` 的导入 lineage 行） | §3.1 | 自动 |
| V-14 | 关联退款场景（DP-13 否决路径） | 对存在有效关联退款的交易作废 → **类型化拒绝零写入**（`P705_REFUND_LINKED_VOID_NOT_SUPPORTED`），交易保持有效；退款交易、证据与对账逐值不变；该场景登记为后续切片 | §3.1、§3.3、D-156 DP-13、`ACCOUNTING_RULES.md:146-148` | 自动 |
| V-15 | DB 守卫与索引（**已降级为套件可执行的断言**） | 事实表跳号/重复、update/delete、一请求多事实、非交替序列（含**恢复后再次作废** → `P705_VOID_CYCLE_EXHAUSTED`）、claim 唯一键并发单赢家 → ABORT/类型化拒绝；索引存在性：`sqlite_master` 断言回收站排序索引 `(ledger_id, created_at DESC, transaction_id)` 与事实表 `UNIQUE(ledger_id, transaction_id, fact_id)`/`UNIQUE(ledger_id, request_id)` 存在（形态先例 `EnumerationPerfV29ToV30MigrationTest.kt:57` 的 `queryLong(driver, ...)`）；PK 前缀的服务性以**行为断言**覆盖（同交易多事实下取最新事实的结果正确且排序确定）。**不冻结 `EXPLAIN QUERY PLAN` 断言**：原始 SQL 经 `driver.executeQuery` 在 jvmTest 可运行（先例 `LedgerDatabaseMigrationTest.kt:30`），但仓库无查询计划断言先例、计划文本跨 SQLite 版本脆弱 | §4.3 | 自动 |
| V-16 | 重开与权威回读 | 修正/作废/恢复后重开应用，详情、月度、回收站与数据库逐值一致 | §3.5 | 自动+设备 |
| V-17 | Android 人工验收 | 详情编辑/作废入口可达；差异预览逐字段正确；作废后月度/流水/HOME 余额/Analysis 移除、回收站出现（含原因/时间/依赖）；恢复后回归；**证据按计划 `:176-182`**：记录期望与实际结果、设备/API、APK SHA 与步骤对应的截图或观察记录；TalkBack 记录实际朗读内容、精确金额与符号、焦点顺序与操作可达性；相关 UI/宿主/查询/schema/依赖变化后重跑受影响向量 | §3.5、`PRODUCT_REQUIREMENTS.md:71`、计划 `:176-182` | 设备 |
| V-18 | 并发同版本修正 | 两请求携带同一 `expectedCurrentVersionId` 并发提交 → 恰好一个版本胜出、败方 `P705_STALE_CURRENT_VERSION` 零写入；无部分写入、无第二版本 | §3.2、§4.3 | 自动 |
| V-19 | 结果未知（UnknownCommit） | 提交后回执丢失 → 按完整快照/receipt 判定：命中 → 原 receipt；未命中 → 保持未知，**不自动重试、不换 requestId**；重开后可再次核对（沿 P7-02 S-3） | §4.2、§3.5 | 自动 |
| V-20 | HOME 余额与 Analysis 计数/金额（P1 修正面） | 作废后 HOME 账户余额（`QueryLedgerCurrentState.balances`）逐账户移除该笔效果、「当前交易」不再列出；Analysis Tab 的 `countsByKind` 与费用/收入总额同步移除；恢复后逐值还原 | §3.6 #4-#5、D-156 DP-3 | 自动 |
| V-21 | 回收站元数据与原因隐私 | 回收站逐行展示原因/作废时间/依赖说明；原因与说明不出现在**日志与测试失败消息**（当前产品无崩溃上报面，该维度不可观测、不纳入断言） | §3.3、§4.4 | 自动 |
| V-22 | 原因表示等价性 | `VoidReason` 领域类型与持久化形式往返等价（类型化码 + 可空有界说明逐值一致）；无第二套表示 | §4.1、§3.3 | 自动 |
| V-23 | 有效谓词双表示等价 | 对同一事实集，SQL 谓词（命名查询/视图片段）派生的有效交易集 == 领域 `TransactionVoidState.isEffective`（§4.1）派生的有效集（无事实 / 仅 void / void+restore 三类逐例）；任一实现变化必须保持等价 | §3.3、§4.1 | 自动 |

- 自动项以 JVM 测试（domain/application/data）与 reducer/host 测试为主；V-16/V-17 的**设备部分不可由自动测试替代**（计划 `:176-182`）。V-12（owner 零写入）、V-15（守卫/索引）、V-18（并发单赢家）、V-20（HOME/Analysis 面）必须在同一事务/同一读路径断言，不以「未观察到写入」代替。

## 6. 决定点与 D-156 裁决（已记录）

以下决定点已由主代理于 2026-09-19 按常设授权、经独立设计取证评审后逐项裁决，并登记 `docs/DECISIONS.md` **D-156**（DP-9 措辞澄清、DP-10 经规格评审复审后收窄，均见 D-156 修订段）。本表保留决定点原文并记录裁决结果：「按建议批准」= 采纳本规格原建议；DP-10 = **复审后收窄**；DP-13 = 主代理另行裁定（首切片否决）。实施按裁决结果落地，不得偏离。

| ID | 决定点 | 建议 | 备选/风险 | 裁决（D-156） |
| --- | --- | --- | --- | --- |
| DP-1 | 回收站读路径形状：有效行集过滤 + 新只读端口，或行集/详情显式 `includeVoided` 参数 | 新只读端口（既有三消费者零语义变化，谓词单点共用） | 参数化改动面更小，但需防止消费者误用；两者都必须证明无第二事实来源 | **按建议批准**：新只读回收站端口（单一共用谓词、无第二事实来源） |
| DP-2 | 转账切片：何时开放、手续费腿与本金腿的失效/作废语义 | 后续独立切片，先只读支持矩阵 | 转账两腿+费用腿的资金腿失效组合更复杂，混入首切片显著扩大回归面 | **按建议批准**：转账为后续切片，首切片仅只读支持矩阵行 |
| DP-3 | 月度口径：作废交易是否计入 `transactionCount`（`MonthlyBuckets.kt:136`）、作废/恢复是否产生新月份效果 | 作废交易**不计**任何有效计数与金额；作废日不产生新效果（追溯移除原月份效果） | 若保留计数需另定义「已作废」标注，且月度卡文案（`P503LedgerViewPresentation.kt:83`）需同步 | **按建议批准**：作废交易不计入任何有效计数与金额；作废日无新效果，原月份效果被追溯移除 |
| DP-4 | 借贷切片：`lending_position_history` 为 append-only 且本金单调，作废 LEND/COLLECT 的派生位置规则 | 后续切片；只新增事实并派生，绝不改写历史行 | 无新规则前作废借贷会破坏本金历史一致性，必须保持 OPEN | **按建议批准**：借贷为后续切片，`lending_position_history` 零写入 |
| DP-5 | 导入创建的交易：作废后原候选能否再次确认 | 否——候选保持终态（`UNIQUE(ledger_id, candidate_id)` `Ledger.sq:7778`、终态守卫 `:8264-8273`），作废只是交易上的新事实 | 放开需 schema/守卫变更与新语义，属新授权 | **按建议批准**：原候选保持终态、不得再次确认 |
| DP-6 | 「删除」语义：首版是否仅逻辑作废、是否提供永久清除 | 仅逻辑作废 + 可审计恢复；永久删除须另立隐私/证据保留与不可逆操作门（计划 Q12） | 永久清除与 append-only 审计、对账证据保留直接冲突 | **按建议批准**：仅逻辑作废；永久清除须另立隐私/证据保留与不可逆操作门 |
| DP-7 | `occurredAt` 是否可改 | 首切片保持 OPEN（域无变更形态，`TransactionVersionAppend.kt:25-39`） | 需要新增域变更形态 + 统计/来源时间语义裁决，超出本批 | **按建议批准**：首切片不可改，保持 OPEN |
| DP-8 | 作废态交易能否被修正；恢复后能否再次作废（循环深度） | 首切片：作废态不可修正（先恢复再修正）；每笔最多一次作废 + 一次恢复 | 多循环需要新的序列语义与回收站展示规则 | **按建议批准**：作废态不可修正（先恢复）；每笔最多一次作废 + 一次恢复 |
| DP-9 | 恢复遇停用目录：拒绝还是要求重选引用 | 类型化拒绝、交易保持作废，不改写历史版本 | 重选会改写引用语义，等于恢复期修正，风险更高 | **按建议批准**：类型化拒绝零写入，交易保持作废 |
| DP-10 | 受影响资金腿的失效组合：是否在同一事务调用 D-113 `P408CorrectionCommitPort`（`reason = posting_replaced`，`evidence_link_history.reason` CHECK `Ledger.sq:8055`） | 组合调用（D-113 UQ-1 延期的跨层集成由本批落地），并保留未变化腿的对账 | 不组合则金额修正后旧匹配残留（证据 §7 风险①）；组合则把 correction port 接入产品事务，需在裁决中明确授权范围 | **收窄（规格评审复审 P705QUAL-003；D-156 修订）**：D-113 `P408CorrectionCommitPort` 为 ACCOUNT_TRANSFER 专用——affected-posting 完整性查询以 `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'` 为条件（`Ledger.sq:8613-8625`，同款条件见 `:8673` 与 v23 种子 `:8688-8704`），非 ACCOUNT_TRANSFER posting 在 invalidation-only 路径被 `P408_CORRECTION_AFFECTED_POSTING_MISMATCH` 拒绝（`SqlDelightCorrectionStore.kt:174-181`；`P408CorrectionStoreTest.kt:415-428` 以 EXPENSE 腿钉死）。首切片手工 `EXPENSE`/`INCOME` 无对账/证据行、无失效对象，故 **DP-10 收窄到后续转账切片**：首切片金额修正零对账暴露、不组合也不授权组合该 port；D-113 查询设计不在本批重开。**D-156 修订原文（逐字引用）**：「DP-10 收窄：首切片手工 EXPENSE/INCOME 的金额修正对账暴露为零（无 posting_reconciliation/evidence_link 行），不组合、不授权 D-113 `P408CorrectionCommitPort`；该组合授权延后至转账切片单独裁决。」 |
| DP-11 | 作废/恢复是否需要作废时间与原因的额外结构（原因值域、是否必填说明） | 原因必填（类型化码 + 可选有界说明），时间取审计 `created_at` | 若允许无原因，回收站无法满足计划 `:76`「列明原因」 | **按建议批准**：原因必填（类型化码 + 可选有界说明）；时间取审计 `created_at` |
| DP-12 | 修正/作废/恢复的入口范围：是否同时提供列表行快捷入口 | 首切片仅详情入口（列表行保持只导航） | 列表入口扩大 UI 回归面，且需定义误触保护 | **按建议批准**：首切片仅详情入口，列表行保持只导航 |
| DP-13 | 关联退款场景（Q11 子问题）：存在有效关联退款的交易可否作废 | 待裁决；若允许，退款交易本身零改动，且不得使累计退款超过原交易可退费用 | 拒绝会让用户无法修正被退款的原交易；允许需冻结「原交易作废后退款如何归属」的报表口径 | **否决（首切片拒绝）**：对存在有效关联退款的交易作废 → 类型化拒绝零写入（`P705_REFUND_LINKED_VOID_NOT_SUPPORTED`），登记后续切片。理由：原交易作废而退款仍有效时的报表归属语义尚未冻结，且 `ACCOUNTING_RULES.md:146-148` 要求累计退款不得超过可退费用——无归属规则即允许会带来错误报表风险；此为「禁止隐式级联」的保守读法 |

## 7. Q11 / Q12 实例化（已按 D-156 批准）

**Q11（支持范围，计划 `:79` 原文）**：推荐按普通收支→转账→借贷/导入及有关关联分片；必须决定每种类型哪些字段可改、关联退款/收回后能否作废，禁止隐式级联或破坏本金历史。替代为一次全类型，交付风险和回归面显著扩大。

**Q11 实例化（D-156 批准）**：首切片 = 手工创建的 `EXPENSE`/`INCOME`，字段集 = 备注/`statisticsAt`/金额/分类/资金账户（§3.2）；转账（DP-2）、借贷（DP-4）、导入关联（DP-5）与关联退款（DP-13）各自独立切片与决定点；**禁止隐式级联**（修正/作废不触碰关联交易、退款、借贷位置与导入候选），**禁止破坏本金历史**（`lending_position_history` 零写入）。主代理于 2026-09-19 按常设授权、经独立设计取证评审后批准本实例化（`docs/DECISIONS.md` D-156）；其中关联退款子问题另行裁定为 DP-13 首切片否决（§6）。

**Q12（删除/恢复语义，计划 `:81` 原文）**：推荐首版“删除”仅逻辑作废、可审计恢复，不提供永久清除；需冻结有效时间、恢复后证据重核规则、目录停用处理、原导入候选是否允许再次确认。恢复不盲目复用旧 CHECKED 状态。退款/真实冲回保持独立经济事件，不能用回收站替代。永久删除若需支持须另立隐私/证据保留与不可逆操作门。

**Q12 实例化（D-156 批准）**：首版「删除」= 逻辑作废 + 回收站可恢复，无永久清除（DP-6）；有效时间口径见 DP-3（追溯移除原月份效果，作废日无新效果）；恢复按当前目录重校验（DP-9）且**不重核、不写入**证据链接与对账状态（恢复不盲目复用旧 CHECKED 状态，对账 owner 零写入）；目录停用 → 类型化拒绝（DP-9）；原导入候选不允许再次确认（DP-5）；退款/真实冲回仍为独立经济事件，回收站不得替代。主代理于 2026-09-19 按常设授权、经独立设计取证评审后批准本实例化（`docs/DECISIONS.md` D-156）。

## 8. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 旧效果残留：作废/修正需在**全部**交易派生读面同时生效（§3.6 枚举的 14 面，含 HOME 余额与 Analysis Tab） | 用户看到作废交易仍有余额/报表效果（原稿只过滤 `ledgerEntryRowsForLedger`，遗漏 `currentVersionRowsForLedger` 面——P705QUAL-001/002） | 有效谓词单点定义并施加到 §3.6 全部「过滤」行（#1-#5）；实施时重跑枚举（新增消费者必须登记）；V-01/V-20 逐面断言 |
| R-2 | 仅 CAS 不等于幂等：CAS 只保证版本不丢，claim/receipt 必须同事务 | 重复入账/重复作废 | claim-first 与写入同事务，失败全回滚（§4.3）；并发单赢家测试 |
| R-3 | 作废 vs 借贷本金历史/导入 receipt 是硬墙 | 强行实现会破坏 append-only 与终态 | 首切片显式拒绝（§3.1）；后续切片先立规则（DP-4/DP-5） |
| R-4 | 对账残留（**原前提已更正**）：原稿假定金额修正须失效旧匹配；实测手工 `EXPENSE`/`INCOME` 不存在对账/证据行，无失效对象 | 无实际暴露；误组合 D-113 会使每次金额修正被 `P408_CORRECTION_AFFECTED_POSTING_MISMATCH` 拒绝 | DP-10 收窄：首切片零对账暴露、不组合该 port（V-08/V-12 断言行数恒为 0）；该风险归转账切片 |
| R-5 | 回收站读路径引入第二事实来源 | 有效面与回收站不一致 | §3.4 冻结约束：同一表组、同一谓词、同一行形状 |
| R-6 | 详情/列表仍显示 `currentVersionId` 等 CAS token 相关字段 | 误用为提交许可 | 预览不构成提交许可（§3.2）；CAS 失败零写入 |
| R-7 | 新增 schema 边与迁移 | 版本断言漂移 | 版本在实施时分配、fresh = migrated 逐字一致、迁移 verifier 通过（计划 `:149`） |

## 9. 本批不做（逐项）

转账、借贷（LEND/COLLECT）、退款与导入关联交易的修正/作废/恢复；对存在有效关联退款的交易作废（DP-13 否决，后续切片）；`occurredAt` 修改；kind 修改；永久删除/清除；批量作废；作废循环（>1 次）；事后版本历史视图（§4.4 冻结为提交前预览）；多币种；自动重试；跨账本操作；`rgXX_` 竖井/golden/迁移既有边改动；**作废/恢复**对对账 owner、借贷历史与导入 owner 零写入；**修正**仅按 DP-10 授权面写入（首切片授权面为空——无对账行可失效）；`P408CorrectionCommitPort` 在 DP-10 授权面之外的任何复用。

## 10. 实施与验证流程

1. **高风险路由**（沿根 `AGENTS.md` 与 `unifiedledger-harness`）：主代理建立隔离 worktree、指定单一 bounded writer 与精确可写范围；子代理先读主检出根 `AGENTS.md`、不复制嵌套索引、不变更 Git、不写 `.external/`。本批涉及账务/架构/迁移/隐私，需**独立规格评审 + 独立质量评审 + distinct verifier + 主代理复核**；规格评审闭环并由主代理登记实施授权后才开工。
2. **聚焦测试优先，再受影响模块**：先跑新增修正/作废/恢复/回收站聚焦 test（V-01..V-23 自动项），再跑受影响模块 `:ledger-domain:jvmTest`、`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:app-ui:jvmTest`、`:desktop-app:jvmTest`；随后 `:ledger-data:verifyCommonMainLedgerDatabaseMigration`（本批新增 schema 边）、`ktlintCheck`、`project_docs`。
3. **聚合门以 CI 为准**：完整 `check`、Android/KMP 编译、Debug APK、Desktop build、完整 Python suite 与 migration verification 由 `.github/workflows/ci.yml` 在精确提交上提供证据，本机不重复资源密集型聚合（根 `AGENTS.md` 验证分工）。
4. **Android 人工门隔离 adb 协议**：agent adb 一律 `ANDROID_ADB_SERVER_PORT=5038`、永不 `kill-server`、只操作本会话自行启动并已用 `emu avd name` 核实的设备；不触碰用户 MuMu/ALas 与 5554/5555 槽位（V-17）。
5. **逐项证据索引（计划 §10.1）**：实施批开始前为 V-01..V-23 分配稳定验收项 ID，并按计划 §10.1 逐项补齐「原要求/权威章节、匿名输入与预期用户结果、源码完整仓库相对路径及符号、**具体测试路径及测试名**、设备步骤、候选提交 SHA（设备项另记 APK 哈希）、证据位置、实测结果、剩余问题」——**测试路径及测试名一列在实施批填写**（本规格只冻结向量与断言，不预写测试名）。

## 边界断言

- 本文档状态为 **approved**（2026-09-19 设计门闭环）：Q11/Q12 与 §6 决定点已由主代理按常设授权、经独立设计取证评审后裁决并登记 `docs/DECISIONS.md` D-156（§6/§7 为裁决记录；DP-9 澄清与 DP-10 收窄见 D-156），独立规格评审与独立质量评审终局 APPROVE WITH CONDITIONS、P3 残余已随 draft-4 修复。**实施**按高风险路由另行建立：隔离 worktree 的单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收（根 `AGENTS.md` 变更路由）；本文不构成实施授权本身。
- D-156 修订后的 DP-10 为**收窄**形态：首切片金额修正零对账暴露、不组合也不授权组合 D-113 `P408CorrectionCommitPort`；该组合授权延后至转账切片单独裁决。DP-13 否决：首切片不得对存在有效关联退款的交易作废（类型化拒绝零写入）。本批**重开并重冻结 P7-03 读模型语义**（§3.6：「current-version 即有效」→「current-version 且未被作废」；受影响锚点 §3.6 #1-#5；D-145 登记的 22 个既有测试锚在无作废事实账本上逐值不变）。
- 实施批必须保持本规格冻结的：首切片范围（§2.2）、支持矩阵与「不支持显式说明」（§3.1）、修正流程与字段集/写形/校验/对账影响（§3.2）、作废/恢复语义与前置条件与幂等（§3.3）、回收站可及性与无第二事实来源约束（§3.4）、产品接线（§3.5）、有效派生面枚举与 P7-03 重冻结（§3.6）、失败码族（§4.2）、持久化形状/快照列/回执唯一性/守卫与索引（§4.3）、V-01..V-23 覆盖面、D-047/D-048/D-113 边界与 P7-01/P7-02/P7-03（按 §3.6 重冻结）/P7-04 既有冻结面；任何变更即重开评审门。
- 真实金额/时间/锚点注册值与个人数据不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动；本文不含本机路径、临时研究或工具轨迹。