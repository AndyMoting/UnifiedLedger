# P7-05 修错、作废/删除、回收站恢复设计（设计门候选）

状态：proposal（承接 `docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §5 P7-05；**本文为 proposal**：Q11/Q12 与 §6 决定点已由主代理于 2026-09-19 按常设授权裁决并登记 `docs/DECISIONS.md` D-156（§6/§7 为裁决记录），但本文在独立规格评审闭环前仍不构成产品行为、迁移、技术选型或发布授权；本文不写实现、不写迁移、不分配 schema 版本号）

**Revision:** draft-2（2026-09-19；按主代理 D-156 裁决记录 Q11/Q12 批准与 DP-1..DP-13 逐项结果、DP-10 授权范围与 DP-13 否决；本文状态仍为 proposal，待独立规格评审闭环）。draft-1（2026-09-19）工作基线 `main` = `23d3bd2`（P7-04 A05IMPORT-INCOME-FACE-001 收口批），schema v30；tracked 行号为该基线实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读；示例全部匿名合成；引用不粘贴大段产品代码。

**Scope:** 冻结 P7-05「修错、作废/删除、回收站恢复」的设计候选面：05.A 支持矩阵与契约、05.B 版本修正、05.C 逻辑作废与恢复、05.D 产品接线。**首个可实施切片 = 手工创建的 `EXPENSE`/`INCOME`（同一五字段形态）的备注/统计时间/金额/分类/资金账户修正 + 逻辑作废 + 回收站恢复**；转账、借贷、导入关联交易各自为后续切片，各有独立支持矩阵行与独立决定点（§3.1、§6）。逻辑作废首版不做永久清除。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为工作基线 `23d3bd2` 实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md:68-81`（§5 P7-05 四子项 05.A–05.D、Q11/Q12 推荐方案）、`:4`（本文为 proposal，未裁决项不构成授权）、`:54-64`（P7-05 依赖与顺序：P7-02 正式提交、P7-03 查询、P7-04 来源关系）、`:147-153`（共同实施纪律与总体验收）、`:159`（无现成实现的项明确写「待实现」，不得把 RG 回放或尚无生产接线的函数当作产品能力）。
- **账务规则**：`docs/ACCOUNTING_RULES.md:34`（录入修正使用版本替代；退款、撤销和真实冲回是新的经济事件）、`:207`（可修改统计时间，报表按新时间归属，来源凭证时间不变）、`:249`（新版本按正确发生时间参与余额和报表，旧版本保留但不再产生有效分录；修改当天不产生虚假现金流）、`:251`（退款/撤销/真实反向资金变化是独立交易，不改写原交易日期、版本或付款对账）、`:253`（修改金额、真实账户或币种时只失效受影响分录的原匹配并保留历史；只改非资金字段不重置未变化资金分录的对账）、`:243`（对账状态不改变任何余额或报表）、`:90`（已被使用的分类默认停用，彻底删除前必须迁移历史账目）、`:146`/`:148`（退款继承原交易精确二级分类；同币种有效退款累计不得超过原交易已确认可退费用）、`:299`（P7-03 月度读模型口径：按当前版本有效 kind 与分录账户类型分类，桶归属键取当前版本 `statistics_at`）。
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
| `P408CorrectionCommitPort.kt` | `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/P408CorrectionCommitPort.kt:171`；实现 `SqlDelightCorrectionStore.kt` | 仅 `P408CorrectionStoreTest.kt` | **不作为通用编辑 API**（D-113 边界，§1.2）；仅在 D-156 明确授权的授权面内被 05.B 的受影响资金腿失效组合复用（DP-10） |

- 复核（`23d3bd2` 实读）：`android-app`、`desktop-app`、`app-ui` 对 `TransactionNoteReplacement`/`ConfirmedTransactionNoteUpdate`/`P408CorrectionCommitPort`/`SqlDelightCorrectionStore`/`ExecuteConfirmedTransactionNoteUpdate` 的引用数为 **0**（`grep -rn ... android-app desktop-app app-ui` 无命中，exit 1）。`P408CorrectionCommitPort` 的 KDoc 自称「Sole product writer for the correction family」，但当前**没有任何组合根构造它**；其实现类只被自身测试引用。`TransactionVersionAppend.appendVersion` 的调用方为 `Rg11Operations.kt`/`Rg12Operations.kt`（RG 回放）与 `TransactionNoteReplacement.kt`。**结论：四者今天均不可由产品路径到达，不得当作既有产品能力。**

### 1.2 D-113 边界（原文引用，不可外推）

> **边界：** 余额、正式交易与 report financial 维度零变化；不删行、不回溯（12 竖井零改动、D-092 不退役）……

（`docs/DECISIONS.md:1833`。）其授权范围为 correction port 的 evidence link 失效/后继链接/投影受控 supersede 与 MISSING/DIFFERENCE 结果态（`:1822-1831`）。**D-113 明确不改变交易版本与余额，不是通用编辑 API**；本批不得以它为依据写入 `ledger_transaction`/`transaction_version`/`posting_set`/`posting` 或任何余额/报表维度。另：D-113 UQ-1 已把「版本替代自动触发与补充资料重匹配的跨层集成」登记为**后续独立批**（`:1822`）——P7-05 的金额/账户修正正是该延期项的落地批，其组合方式属决定点 DP-10。

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
| **对账 owner（绝不写）** | `posting_reconciliation`(`:8063`)、`posting_reconciliation_history`(`:8074`)、`evidence_link`(`:8029`)、`evidence_link_history`(`:8050`)、`evidence_projection`(`:8096`)、`reconciliation_correction_snapshot`(`:8143`)；guard 触发器 `:8197-8201` | 作废/恢复**零写入**；仅 05.B 受影响资金腿修正按 DP-10 裁决后经 D-113 授权面写入 |
| **借贷本金历史（绝不写）** | `lending_position`(`:196`)、`lending_position_history`(`:209`)，append-only guard `:225-228` | 不写（DP-4） |
| **导入 owner（绝不写）** | `import_confirmation`(`:7767`；`UNIQUE(ledger_id, candidate_id)` `:7778`)、`import_receipt`(`:7784`)、`import_candidate_status_history`(`:7698`) 与终态守卫 `:8264-8273` | 不写（DP-5） |

### 1.5 UI：无任何编辑/作废/删除入口

- 详情屏 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503LedgerView.kt:342-404 P503TransactionDetailScreen(detail, onClose)` 为零编辑只读面（KDoc `:333-341` 明示 "Zero edit entries"，唯一出口是「返回」）；宿主 `P503App.kt:446-450 selectTransaction` 只派发读结果；列表行 `P503OverviewScreen.kt:139` 只导航详情。**05.D 全部为新建面。**

### 1.6 CAS token 已在产品读路径（获取 CAS token 无需读模型改动）

- 条目行 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/LedgerEntryReadModels.kt:22 LedgerEntryRow.currentVersionId`（由 `QueryLedgerEntryRows.kt:16` 产出）；详情 `QueryTransactionDetail.kt:160 TransactionDetail.currentVersionId`（`:86` 填充）。两者均为产品读模型。
- 注意：`QueryTransactionDetail` 从 `loadLedgerEntryRows` 行集取行（`QueryTransactionDetail.kt:38-42`），行集若被作废过滤，**详情对作废交易返回 `NotFound`**（`:176`）——回收站读路径必须另行设计（DP-1）。

### 1.7 首切片必须 schema 迁移

- 现有表无一可承载「作废/恢复事实」：核心表无状态列（§1.4）；request/receipt 表均为创建专用（`manual_expense_request` `:65`、`confirmed_expense_receipt` `:79` 等）且回执含 `transaction_id UNIQUE`/一请求一回执语义，无法表达对既有交易的第二次事实；改写冻结行被规则（`ARCHITECTURE.md:19`、`ACCOUNTING_RULES.md:34`）与 guard 触发器禁止。
- 因此首切片新增 append-only 作废/恢复事实 owner（形状见 §4.3）；迁移只新增边、**不分配版本号**（沿计划 `:149`「迁移只新增边，版本在实施时分配，不预占 v31」），本规格不写迁移。

## 2. 目标与范围

### 2.1 目标

- 05.A：冻结逐 kind 的支持矩阵与契约（可改字段、可作废/恢复条件、依赖关系），不支持类型显式说明并保持 OPEN。
- 05.B：普通支出/收入的版本修正：预览差异 → 明确确认 → `expected current version` CAS → 原子写入 → 权威回读；历史版本与来源不可覆盖。
- 05.C：逻辑作废 + 不可变操作历史 + 回收站（原因/时间/依赖）+ 恢复重校验；作废后有效余额/报表按冻结口径移除原效果；恢复恰好恢复一次；取消/拒绝零效果。
- 05.D：详情编辑/作废入口、历史差异、回收站与恢复确认；两端共享用例，Android 人工验收。

### 2.2 首切片范围（冻结）

**范围内：** 仅**手工创建**的 `EXPENSE`/`INCOME`（创建入口 = `MANUAL_CREATED` 或 `UNMARKED`，`LedgerEntryReadModels.kt:63-69`）的（a）备注修正、（b）`statisticsAt` 修正、（c）金额/分类/资金账户修正、（d）逻辑作废、（e）回收站列出与恢复；application 端口与用例、ledger-data 新增非 `rgXX_` 产品表与加性迁移、两端组合根接线、`app-ui` 详情/回收站最小面、V-01..V-17。

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
| `ACCOUNT_TRANSFER` | 待定 | 待定 | 待定（含手续费腿） | 待定 | 后续切片 | DP-2 |
| `LEND`/`COLLECT` | 待定 | 待定 | 待定 | 待定（本金历史硬墙） | 后续切片 | DP-4 |
| `REFUND_RECEIPT` | 待定 | 待定 | 待定 | 待定（关联退款场景） | 后续切片 | DP-13 |
| `OPENING_BALANCE`/`STORED_VALUE_*`/`PREPAID_*`/`CREDIT_REPAYMENT`/`BALANCE_ADJUSTMENT` | 不支持 | 不支持 | 不支持 | 不支持 | 不交付 | 产品路径当前不可达（`ACCOUNTING_RULES.md:182-186`、`ARCHITECTURE.md:7`；`ledger_transaction.kind` CHECK `Ledger.sq:4` 仅五值，其余经 `canonical_kind` 表达）；**显式说明不支持**，不得静默 no-op |

- 冻结：**永不修改 kind**（含 `canonical_kind`）；修正后的交易保持原 kind、原 `occurredAt`、原创建身份与来源关系。
- 不支持类型/切片一律**类型化拒绝零写入**（`P705_KIND_NOT_SUPPORTED`/`P705_CREATION_LINEAGE_NOT_SUPPORTED`），不得以「看起来无效果」静默通过；支持矩阵行是实施与验收的分母，未交付切片继续 OPEN，不能用普通支出通过宣布全批完成（计划 `:74`）。

### 3.2 修正契约（05.B）

- **流程（冻结）**：读当前有效版本 → 预览差异（纯读，零写入）→ 用户明确确认 → 分配新 requestId → claim-first 原子提交（`expectedCurrentVersionId` CAS）→ 权威回读。预览不得作为提交许可（沿 `ACCOUNTING_RULES.md:261` 目录版本纪律的同一原则）。
- **字段集（首切片冻结）**：`note`、`statisticsAt`、`amount`、`categoryId`、`fundingAccountId`。`occurredAt` 不在内（DP-7）。
- **写形（冻结）**：以当前版本为基追加 `version_number + 1`；`note`/`statisticsAt` 复用当前 posting set（`TransactionVersionChange.Note`/`StatisticsAt`）；金额/分类/资金账户修正使用 `TransactionVersionChange.Postings`（完整替换 posting set，必须逐币种平衡且覆盖旧 set 每条 posting 恰好一次）。旧版本、旧 posting set、创建 receipt 与来源关系一律不改写。
- **校验（冻结）**：新版本的账户/分类引用必须在写入事务内按**当前权威目录**重校验（存在、同账本、kind、active、叶子/自有真实资产等，复用 P7-01/P7-02 准入 token 族；`ACCOUNTING_RULES.md:255-261`）；金额必须为币种精度的精确十进制且为正；备注长度沿既有上限。
- **对账影响（冻结）**：受影响资金腿 = 新版本中真实资产/负债（`catalog_account.real_account = 1`）分录且 `(accountId, amount, currency)` 相对旧版本发生变化者。**未变化资金腿的对账行与证据链接零改动**；非资金腿（费用/收入过账账户）变化不构成资金腿失效。受影响腿的失效/重匹配 = DP-10，已由 D-156 **明确授权**为同一事务内组合调用 D-113 `P408CorrectionCommitPort`（`reason = posting_replaced`，`evidence_link_history.reason` CHECK `Ledger.sq:8055`），未变化腿对账保留（§6、`P705_MATCHED_FUNDING_LEG_CHANGED`）。
- **无效/无变化**：同 requestId 同快照 replay → `NoChange` 返回原 receipt、零新增实体；同 requestId 不同快照 → `RequestIdentityConflict` 零写入；`expectedCurrentVersionId` 非当前 → `P705_STALE_CURRENT_VERSION` 零写入（不自动重试、不静默改用最新版本）；请求与结果完全一致 → `NoChange`。
- **冻结判定**：修正当天不产生虚假现金流（`ACCOUNTING_RULES.md:249`）：版本替换不创建任何新的经济事件、不新增现金流；100 → 80 修正后只有 80 参与余额与报表（读路径已 join current version，`Ledger.sq:8762-8767`）。

### 3.3 作废与恢复契约（05.C）

- **语义（冻结）**：作废 = 追加一条不可变事实，使该交易从**全部有效派生面**移除；不删除、不改写、不创建补偿交易、不改变任何历史行。恢复 = 追加第二条事实使原交易重新有效；每笔交易首切片最多一次作废 + 一次恢复（`sequence ≤ 2`）。
- **有效谓词（冻结）**：交易有效 ⟺ 其作废/恢复事实序列的最后一条不是「作废」；无事实 = 有效。谓词只有一处定义（SQL 命名查询/视图片段），所有有效面共用；不得各面各写一份。
- **有效时间口径（DP-3，D-156 批准）**：作废**追溯移除**原交易在其 `statistics_at` 所属月份的效果；作废日不产生任何新的月份/现金流/分类效果。恢复同样不产生新效果，只让原交易按原 `statistics_at` 重新参与。
- **恢复重校验（冻结）**：恢复必须按**当前**目录重校验该交易当前版本的账户/分类引用；不可准入时类型化拒绝（`P705_CATALOG_REFERENCE_NOT_ADMISSIBLE`）且交易保持作废，**不得盲目恢复旧状态、不得静默替换引用、不得改写历史版本**。恢复零写入对账 owner、证据链接、借贷历史与导入 owner（§1.4）。
- **作废前置条件（冻结）**：交易存在且在当前账本；交易有效（未作废）；交易为支持矩阵内的手工创建 `EXPENSE`/`INCOME`；**该交易不存在有效关联退款**（DP-13 首切片否决，登记后续切片）；必须明确确认；必须携带原因（回收站要列明原因/时间/依赖）。任一不满足 → 类型化拒绝零写入。
- **恢复前置条件（冻结）**：交易存在、当前为作废态、尚未恢复过、目录重校验通过、明确确认。
- **幂等（冻结）**：同 requestId 同快照 replay 返回原 receipt（`NoChange`）；同 requestId 不同快照 → `RequestIdentityConflict` 零写入；失败/拒绝整事务回滚（claim 一并回滚，身份可重试，沿 D-098 领域 4 语义）。
- **不写对账/证据/导入/借贷**：作废与恢复对 §1.4 表零写入，由行级/触发器断言证明（V-12、V-15）。
- **永久删除**：首版不提供（计划 Q12 推荐）；若未来需要，须另立隐私/证据保留与不可逆操作门（DP-6）。

### 3.4 读路径与回收站（05.C/05.D；DP-1）

- **事实**：`loadLedgerEntryRows` 是月度（`QueryMonthlyActivity.kt:92`）、流水（`QueryLedgerEntryRows.kt:16`）、详情（`QueryTransactionDetail.kt:38`）的**唯一行集来源**；行集若按作废过滤，作废交易在详情得 `NotFound`（`QueryTransactionDetail.kt:176`），回收站无法复用该路径。
- **冻结约束**：回收站读路径**不得发明第二事实来源**——它必须从同一组表（`ledger_transaction` + `ledger_transaction_current_version` + `transaction_version` + 作废事实 owner）派生，与有效面共用同一有效谓词与同一行形状（`LedgerEntryRow` 或其超集），差异只在谓词与附加事实元数据。
- **建议（DP-1，D-156 按建议批准）**：保留 `loadLedgerEntryRows` 的「有效行」语义（既有三个消费者零语义变化），新增只读端口方法（如 `loadVoidedTransactionRows(ledgerId)`）返回作废态交易的行 + 作废元数据；回收站与「作废详情」由该路径提供，恢复入口从回收站进入。备选（未采纳）：给行集/详情增加显式 `includeVoided` 参数（同一查询、显式开关），回收站与详情共用。
- **可及性（冻结要求，不因选项变化）**：作废交易必须能在回收站中列出（原因、作废时间、依赖说明），并可从回收站确认恢复；作废交易的历史（版本、创建入口、来源关系）必须可读，不得因作废而不可达。

### 3.5 产品接线（05.D）

- 详情屏（`P503LedgerView.kt:342`）新增「编辑」与「作废」入口（仅支持矩阵内的交易显示）；编辑面 = 字段表单 + 差异预览 + 明确确认；作废面 = 原因 + 明确确认；回收站 = 列表 + 恢复确认；恢复前展示当前目录重校验结果。
- 事件与状态沿既有 `P503UiEvent`/`P503AppState`/reducer 纪律扩展；新增事件只为 P7-05 定义转换，既有事件 × 状态矩阵不得改变（沿 P7-02 §6.2 G-B 先例）；`Submitting`/`UnknownCommit` 期间不得离开，提交中不重入。
- 两端组合根（`android-app/.../App.kt`、`desktop-app/.../Main.kt`）装配同一 application 用例与端口；共享 `app-ui` 不直接访问数据库（`ARCHITECTURE.md:55-58`）。
- 修正/作废/恢复成功后走权威回读（沿 P5-04.4/P7-02 先例）；详情/流水/月度在同一刷新链上更新；作废后从有效面消失、在回收站出现。

## 4. 领域 / 应用 / 持久化 / UI 契约

### 4.1 领域（`ledger-domain`）

- **复用**：`FormalTransaction.appendVersion`（`TransactionVersionAppend.kt:43`）与三形态 `TransactionVersionChange`（`:25-39`）作为唯一版本追加原语；不改其语义。
- **新增**：产品侧修正校验纯函数（字段集/准入/金额/平衡/受影响资金腿派生）；作废/恢复无领域新原语（事实与谓词属持久化/应用面），但「有效谓词」的领域等价表述（`TransactionVoidState` 纯值类型 + `isEffective`）必须单点定义。
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
| `P705_STALE_CURRENT_VERSION` | `expectedCurrentVersionId` ≠ 当前版本 | Rejected，零写入，不自动重试 |
| `P705_TRANSACTION_VOIDED` | 对作废交易发起修正/作废（DP-8 裁决前一律拒绝） | Rejected，零写入 |
| `P705_TRANSACTION_NOT_VOIDED` | 对有效交易发起恢复，或恢复已恢复过的交易 | Rejected，零写入 |
| `P705_VOID_REASON_REQUIRED` | 作废缺原因或原因超限 | Rejected，零写入 |
| `P705_CATALOG_REFERENCE_NOT_ADMISSIBLE` | 提交/恢复时目录引用不存在、跨账本、停用或类型不符 | Rejected，零写入 |
| `P705_MATCHED_FUNDING_LEG_CHANGED` | 受影响资金腿的失效组合未按 DP-10 裁决落地（防御性） | Rejected，零写入 |
| `P705_NO_CHANGE` | 请求与当前状态完全一致 | `NoChange`（原 receipt），零新增 |
| `P705_REQUEST_IDENTITY_CONFLICT` | 同 requestId 不同快照 | Conflict，零写入 |
| `P705_CONSTRAINT_VIOLATION` | 唯一/FK/触发器兜底失败 | 整事务回滚，类型化拒绝 |

- **快照感知 resolver**：提交后异常按完整快照/receipt 判定 success/conflict/unknown；Unknown 不自动重试、不换 requestId（沿 P7-02 S-3）。

### 4.3 持久化形状（`ledger-data`；只冻结形状，不分配版本号、不写迁移）

- **作废/恢复事实 owner（新增，append-only）**：`transaction_void_fact`——`(ledger_id, transaction_id, sequence)` 主键；`fact_kind ∈ {void, restore}`；`reason_code`（必填）+ 可选有界说明；`request_id`、`confirmation_id`、`created_at`；`UNIQUE(ledger_id, request_id)`（一请求一事实）；FK 指向 `ledger_transaction`（同账本）。守卫：`sequence` 必须为当前最大 + 1（禁止跳号/回填）；`void` 只能作为 sequence 1、`restore` 只能紧随 `void`（交替，首切片深度 ≤ 2）；`BEFORE UPDATE`/`BEFORE DELETE` 双 ABORT 触发器（沿 `lending_position_history_guard_*` `Ledger.sq:225-228` 与 `evidence_link_guard_*` `:8197-8198` 款）。
- **请求/回执（新增，claim-first）**：每个操作族一对表（`transaction_correction_request`/`receipt`、`transaction_void_request`/`receipt`、`transaction_restore_request`/`receipt`），沿 `manual_expense_request`/`confirmed_expense_receipt`（`Ledger.sq:65-91`）与 `transaction_note_update_request`/`confirmed_transaction_note_update_receipt`（`:92-121`）形状：`PRIMARY KEY (ledger_id, request_id)`、规范化快照列、`confirmation_marker` CHECK、回执含 `transaction_id`、`confirmation_id`、结果版本 ID/事实 ID；claim 用 `INSERT ... ON CONFLICT DO NOTHING` + `lastStatementChangedRowCount()`，claim 与写入同一事务（失败全回滚，身份可重试）。
- **版本复制（新增 SQL）**：以 `expected_current_version_id` 为条件、`INSERT INTO transaction_version ... SELECT ... version_number + 1, <new_posting_set_id>, <new occurred/statistics/effective times> ...` 复制当前版本并绑定新 posting set；`lastStatementChangedRowCount() == 1` 失败即 `P705_STALE_CURRENT_VERSION` 零写入；随后 `compareAndSetCurrentVersion`（`Ledger.sq:2127`）推进 current-version，`lastStatementChangedRowCount() == 1` 为硬断言（沿 `SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:47-64` 先例）。新 posting set 与 posting 行必须同事务插入且逐币种平衡。
- **读路径**：有效谓词以命名查询/视图片段单点定义，`ledgerEntryRowsForLedger`（`Ledger.sq:8748`）与新回收站查询共用；新查询返回作废态行 + 作废元数据（§3.4）。既有 22 个 `CurrentVersionRow`/`loadCurrentRows` 锚点与既有 22 项测试不改（沿 D-145 R-7 先例）。
- **迁移**：加性、只建结构零回填，fresh = migrated 逐字一致；**版本号在实施时分配**（计划 `:149`），本规格不预占。

### 4.4 UI 状态与事件（最小）

- 新增只读/编辑状态（如 `TransactionEdit`、`VoidConfirm`、`RecycleBin`）与对应事件；`Editing` 面复用 P7-02 的字段保留/键入保留纪律不适用于本面（独立面，不得改变既有录入面语义）。
- 差异预览必须逐字段显示旧值/新值（金额带符号与币种、分类/账户显示当前名、时间显示两个时区语义），确认页显示「历史版本保留、旧版本失效」的说明；作废确认页显示原因与影响面（该交易将从月度与流水中移除、可在回收站恢复）；恢复确认页显示目录重校验结果。
- 作废交易在有效面（月度/流水/详情）不可见；回收站列表按作废时间倒序稳定排序，展示原因/时间/创建入口；恢复成功后回到有效面并由权威回读刷新。

## 5. 验收矩阵（V-01..V-17；首切片）

| # | 场景 | 期望 | 关联 | 证据 |
| --- | --- | --- | --- | --- |
| V-01 | 作废一笔普通支出/收入 | 有效余额/月度/分类/趋势/流水不再包含其效果；原交易在回收站可见 | §3.3、`ACCOUNTING_RULES.md:249` | 自动 |
| V-02 | 作废后历史与来源保留 | 原版本、原 posting set、创建 receipt、创建入口与来源关系仍可读；无任何历史行被改写/删除 | §3.3、§1.4 | 自动 |
| V-03 | 恢复恰好一次 | 恢复后交易重新有效且效果与作废前逐值一致；第二次恢复请求（新 requestId）类型化拒绝零写入 | §3.3 | 自动 |
| V-04 | 取消/拒绝零效果 | 预览后取消、目录/字段/原因拒绝、CAS 冲突 → 零新增实体、零余额/报表/对账变化 | §3.2、§3.3 | 自动 |
| V-05 | 金额修正 100 → 80 | 只有 80 有效（余额/月度/分类）；修改日无虚假 20 现金流；旧版本 100 仍保留可查 | §3.2、`ACCOUNTING_RULES.md:249` | 自动 |
| V-06 | 修正保留版本历史 | 新版本 `version_number + 1`；旧版本、旧 posting set 不被覆盖；CAS 以 `expectedCurrentVersionId` 为条件 | §3.2、§4.3 | 自动 |
| V-07 | 跨月修正 | 改 `statisticsAt` 后效果只计正确月份；`occurredAt` 与来源凭证时间不变 | §3.2、`ACCOUNTING_RULES.md:207` | 自动 |
| V-08 | 非资金字段修正不重置无关对账 | 备注/`statisticsAt`/分类修正对未变化资金腿的对账行与证据链接零写入；金额/资金账户修正只影响变化腿（按 DP-10 裁决落地） | §3.2、`ACCOUNTING_RULES.md:253`、D-048 | 自动 |
| V-09 | stale CAS 冲突 | 旧 `expectedCurrentVersionId` 提交 → 类型化拒绝零写入；不自动改用最新版本；重读后可重试 | §3.2 | 自动 |
| V-10 | 幂等与身份冲突 | 三类操作同 requestId 同快照 replay → 原 receipt 零新增；同 requestId 不同快照 → 冲突零写入 | §3.2、§3.3 | 自动 |
| V-11 | 恢复按当前目录重校验 | 资金账户/分类停用后恢复 → 类型化拒绝且交易保持作废；不盲目恢复旧状态、不静默替换引用 | §3.3 | 自动 |
| V-12 | 对账/借贷/导入 owner 零写入 | 作废与恢复后 `posting_reconciliation*`、`evidence_link*`、`evidence_projection`、`lending_position*`、`import_confirmation`/`import_receipt`/`import_candidate_status_history` 行数与内容逐值不变 | §1.4、§3.3 | 自动 |
| V-13 | 支持矩阵强制 | 转账/借贷/退款/导入创建交易与特殊 kind 的修正/作废/恢复 → 类型化拒绝零写入；矩阵行与实现一一对应 | §3.1 | 自动 |
| V-14 | 关联退款场景（DP-13 否决路径） | 对存在有效关联退款的交易作废 → **类型化拒绝零写入**（`P705_REFUND_LINKED_VOID_NOT_SUPPORTED`），交易保持有效；退款交易、证据与对账逐值不变；该场景登记为后续切片 | §3.1、§3.3、D-156 DP-13、`ACCOUNTING_RULES.md:146-148` | 自动 |
| V-15 | DB 守卫 | 事实表跳号/重复、update/delete、一请求多事实、非交替序列 → ABORT；claim 唯一键并发单赢家 | §4.3 | 自动 |
| V-16 | 重开与权威回读 | 修正/作废/恢复后重开应用，详情、月度、回收站与数据库逐值一致 | §3.5 | 自动+设备 |
| V-17 | Android 人工验收 | 详情编辑/作废入口可达；差异预览逐字段正确；作废后月度与流水移除、回收站出现；恢复后回归；TalkBack 朗读精确金额与状态；记录 APK SHA、设备、步骤与结果 | §3.5、`PRODUCT_REQUIREMENTS.md:71` | 设备 |

- 自动项以 JVM 测试（domain/application/data）与 reducer/host 测试为主；V-16/V-17 的**设备部分不可由自动测试替代**（计划 `:176-182`）。作废/恢复对账零写入（V-12）与目录重校验（V-11）必须在同一事务断言，不以「未观察到写入」代替。

## 6. 决定点与 D-156 裁决（已记录）

以下决定点已由主代理于 2026-09-19 按常设授权、经独立设计取证评审后逐项裁决，并登记 `docs/DECISIONS.md` **D-156**。本表保留决定点原文并记录裁决结果：「按建议批准」= 采纳本规格原建议；DP-10 = 明确授权并限定授权范围；DP-13 = 主代理另行裁定（首切片否决）。实施按裁决结果落地，不得偏离。

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
| DP-10 | 受影响资金腿的失效组合：是否在同一事务调用 D-113 `P408CorrectionCommitPort`（`reason = posting_replaced`，`evidence_link_history.reason` CHECK `Ledger.sq:8055`） | 组合调用（D-113 UQ-1 延期的跨层集成由本批落地），并保留未变化腿的对账 | 不组合则金额修正后旧匹配残留（证据 §7 风险①）；组合则把 correction port 接入产品事务，需在裁决中明确授权范围 | **明确授权**：同一事务内组合调用 `P408CorrectionCommitPort`（`reason = posting_replaced`）处理受影响资金腿，未变化腿对账保留。授权范围仅限本批把该 port 接入产品修正事务；D-113 UQ-1 延期的跨层集成由本批解除；不得外推为通用编辑 API 授权 |
| DP-11 | 作废/恢复是否需要作废时间与原因的额外结构（原因值域、是否必填说明） | 原因必填（类型化码 + 可选有界说明），时间取审计 `created_at` | 若允许无原因，回收站无法满足计划 `:76`「列明原因」 | **按建议批准**：原因必填（类型化码 + 可选有界说明）；时间取审计 `created_at` |
| DP-12 | 修正/作废/恢复的入口范围：是否同时提供列表行快捷入口 | 首切片仅详情入口（列表行保持只导航） | 列表入口扩大 UI 回归面，且需定义误触保护 | **按建议批准**：首切片仅详情入口，列表行保持只导航 |
| DP-13 | 关联退款场景（Q11 子问题）：存在有效关联退款的交易可否作废 | 待裁决；若允许，退款交易本身零改动，且不得使累计退款超过原交易可退费用 | 拒绝会让用户无法修正被退款的原交易；允许需冻结「原交易作废后退款如何归属」的报表口径 | **否决（首切片拒绝）**：对存在有效关联退款的交易作废 → 类型化拒绝零写入（`P705_REFUND_LINKED_VOID_NOT_SUPPORTED`），登记后续切片。理由：原交易作废而退款仍有效时的报表归属语义尚未冻结，且 `ACCOUNTING_RULES.md:146-148` 要求累计退款不得超过可退费用——无归属规则即允许会带来错误报表风险；此为「禁止隐式级联」的保守读法 |

## 7. Q11 / Q12 实例化（已按 D-156 批准）

**Q11（支持范围，计划 `:79` 原文）**：推荐按普通收支 → 转账 → 借贷/导入及有关关联分片；必须决定每种类型哪些字段可改、关联退款/收回后能否作废，禁止隐式级联或破坏本金历史。替代为一次全类型，交付风险和回归面显著扩大。

**Q11 实例化（D-156 批准）**：首切片 = 手工创建的 `EXPENSE`/`INCOME`，字段集 = 备注/`statisticsAt`/金额/分类/资金账户（§3.2）；转账（DP-2）、借贷（DP-4）、导入关联（DP-5）与关联退款（DP-13）各自独立切片与决定点；**禁止隐式级联**（修正/作废不触碰关联交易、退款、借贷位置与导入候选），**禁止破坏本金历史**（`lending_position_history` 零写入）。主代理于 2026-09-19 按常设授权、经独立设计取证评审后批准本实例化（`docs/DECISIONS.md` D-156）；其中关联退款子问题另行裁定为 DP-13 首切片否决（§6）。

**Q12（删除/恢复语义，计划 `:81` 原文）**：推荐首版「删除」仅逻辑作废、可审计恢复，不提供永久清除；需冻结有效时间、恢复后证据重核规则、目录停用处理、原导入候选是否允许再次确认。恢复不盲目复用旧 CHECKED 状态。退款/真实冲回保持独立经济事件，不能用回收站替代。永久删除若需支持须另立隐私/证据保留与不可逆操作门。

**Q12 实例化（D-156 批准）**：首版「删除」= 逻辑作废 + 回收站可恢复，无永久清除（DP-6）；有效时间口径见 DP-3（追溯移除原月份效果，作废日无新效果）；恢复按当前目录重校验（DP-9）且**不重核、不写入**证据链接与对账状态（恢复不盲目复用旧 CHECKED 状态，对账 owner 零写入）；目录停用 → 类型化拒绝（DP-9）；原导入候选不允许再次确认（DP-5）；退款/真实冲回仍为独立经济事件，回收站不得替代。主代理于 2026-09-19 按常设授权、经独立设计取证评审后批准本实例化（`docs/DECISIONS.md` D-156）。

## 8. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 旧效果残留：作废/修正需在**全部**有效派生面同时生效（月度、分类、趋势、流水、详情、余额派生） | 用户看到作废交易仍有余额/报表效果 | 有效谓词单点定义 + 实施时枚举全部 `loadLedgerEntryRows`/`ledgerEntryRowsForLedger` 消费者（已验证：`QueryMonthlyActivity.kt:92`、`QueryLedgerEntryRows.kt:16`、`QueryTransactionDetail.kt:38`）并逐项断言；发现新消费者须登记受影响面 |
| R-2 | 仅 CAS 不等于幂等：CAS 只保证版本不丢，claim/receipt 必须同事务 | 重复入账/重复作废 | claim-first 与写入同事务，失败全回滚（§4.3）；并发单赢家测试 |
| R-3 | 作废 vs 借贷本金历史/导入 receipt 是硬墙 | 强行实现会破坏 append-only 与终态 | 首切片显式拒绝（§3.1）；后续切片先立规则（DP-4/DP-5） |
| R-4 | 对账残留：金额修正后旧匹配未失效 | 对账状态与事实不一致 | DP-10 已由 D-156 授权：同一事务组合调用 D-113 `P408CorrectionCommitPort`（`reason = posting_replaced`），未变化腿对账保留；实施须断言受影响腿失效与未变化腿零改动 |
| R-5 | 回收站读路径引入第二事实来源 | 有效面与回收站不一致 | §3.4 冻结约束：同一表组、同一谓词、同一行形状 |
| R-6 | 详情/列表仍显示 `currentVersionId` 等 CAS token 相关字段 | 误用为提交许可 | 预览不构成提交许可（§3.2）；CAS 失败零写入 |
| R-7 | 新增 schema 边与迁移 | 版本断言漂移 | 版本在实施时分配、fresh = migrated 逐字一致、迁移 verifier 通过（计划 `:149`） |

## 9. 本批不做（逐项）

转账、借贷（LEND/COLLECT）、退款与导入关联交易的修正/作废/恢复；对存在有效关联退款的交易作废（DP-13 否决，后续切片）；`occurredAt` 修改；kind 修改；永久删除/清除；批量作废；作废循环（>1 次）；多币种；自动重试；跨账本操作；`rgXX_` 竖井/golden/迁移既有边改动；对账 owner、借贷历史与导入 owner 的任何写入；`P408CorrectionCommitPort` 在其 D-156 授权面（受影响资金腿失效，DP-10）之外的复用。

## 边界断言

- 本文档状态为 **proposal**：Q11/Q12 与 §6 决定点已由主代理于 2026-09-19 按常设授权、经独立设计取证评审后裁决并登记 `docs/DECISIONS.md` D-156（§6/§7 为裁决记录），但本文在**独立规格评审闭环**（含主代理最终登记）之前不构成实施授权；实施须在隔离 worktree 的单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（根 `AGENTS.md` 变更路由）。
- D-156 的 DP-10 授权范围仅限本批把 D-113 `P408CorrectionCommitPort` 接入产品修正事务（受影响资金腿，`reason = posting_replaced`）；D-113 UQ-1 延期的跨层集成由本批解除，D-113 其余边界不变。DP-13 否决：首切片不得对存在有效关联退款的交易作废（类型化拒绝零写入）。
- 实施批必须保持本规格冻结的：首切片范围（§2.2）、支持矩阵与「不支持显式说明」（§3.1）、修正流程与字段集/写形/校验/对账影响（§3.2）、作废/恢复语义与前置条件与幂等（§3.3）、回收站可及性与无第二事实来源约束（§3.4）、失败码族（§4.2）、持久化形状与守卫（§4.3）、V-01..V-17 覆盖面、D-047/D-048/D-113 边界与 P7-01/P7-02/P7-03/P7-04 既有冻结面；任何变更即重开评审门。
- 真实金额/时间/锚点注册值与个人数据不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动；本文不含本机路径、临时研究或工具轨迹。