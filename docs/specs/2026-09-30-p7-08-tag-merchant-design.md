# P7-08 标签与商家（tag / merchant）设计门规格

状态：proposal（2026-09-30 起草；本文是 P7-08「标签与商家」的**设计规格草案**，沿 `docs/CONTRIBUTING.md:168` 的允许分类标 `proposal`；批准（转 `approved`）由主代理以 `docs/DECISIONS.md` 下一条决定（建议编号 D-187）裁决 Q17/Q18 后登记。本文冻结 08.A 目录契约与注释聚合、08.B 录入与编辑贯通的首版切片边界、08.C 筛选与累计、08.D 双端与恢复的**设计面**。本文只冻结**设计级**语义，**不**构成产品行为、迁移、技术选型或发布授权；实现属后续独立实施批。本文不写实现、不写迁移文件、不分配 schema 版本号。）

**Revision:** draft-2（2026-09-30）。闭合设计门双评审（均 APPROVE-WITH-FINDINGS）的 1 high + 3 medium + 3 low + 行号符合性项：**HIGH-1** §3.1 冻结无注释交易的 CAS 哨兵——`expectedAnnotationRevision = 0` 当且仅当当前指针行不存在（镜像 07.B `expectedRevision == 0` 新建绑定先例，D-185 第 5 条 (b)），哨兵命中后在同事务创建 revision=1 + 指针行，`expectedCurrentVersionId` 照常校验；§3.2 CAS 顺序与 §4.4 同步引用。**MED-1** §3.1 冻结「清空全部关联」= 追加空注释新 revision（revision 递增、指针行不删、可回看），否决删指针行回退无注释；「无指针行」只在从未注释时出现且不可再进入。**MED-2** §4.3 裁决「快照字符串唯一等价依据」与「旧行缺注释字段」的字面张力：注释请求**不走裸字符串比较**，沿 `StoredNoteUpdate.matches`（`SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:100-119`）结构化逐列匹配，旧行缺失注释字段取冻结默认「无注释」参与匹配。**MED-3** §7 表增「归属实施切片」列（首版：A02/A03/A04/A05/A07 局部、A08 迁移腿；08.C：A01/A06/A08 恢复腿与 A09；按本文切片划分如实标注）。**MED-4** §8 开放项 7 归属 08.B UI 切片（最迟随 A03 取证）、项 11 归属 08.A 目录管理 UI 切片（最迟随 A02 取证）。**LOW-1/2/3** §2.2/§3.2 补 pending claim 不计引用面与双向竞态兜底；§3.1/§3.4 补 `observed_transaction_version_id` 悬挂防护依赖「金融 version 行永不删除」系统不变量（FK 与否留开放项 3）；§3.1 明示表名/列名为冻结设计提案、字面 DDL 留开放项 3。**LOW-4** §2.3 代理对排序方向改正（UTF-16 码元序中 BMP 私有区 U+E000–F8FF 在代理区 U+D800–DFFF 之后）。**P3/P4 行号修正**：`catalog_category` kind CHECK 实为 `Ledger.sq:344`；`transaction_note_update_request` 实为 `:119-130`；`BudgetConfiguration.kt` 请求形状实为 `:105-113`、receipt outcome `:116-127`、四态 `:128-145`（`:14-23` 为文件头镜像声明）；`P503Reducer.kt` 再记一笔挂点统一为 `:445-508`；`ConfirmedManualTransfer.kt` 快照统一为 `:29-35`。draft-1（2026-09-30）。初稿。单一写者起草于隔离 worktree（分支 `UL-p7-08design`），工作基点 = `main` `2c7cb1e`（schema **v33**，迁移链 `1.sqm`～`32.sqm` 共 32 个文件；`32.sqm` 为 v32→v33 的 budget 四表边，`33.sqm` **不存在**）。下一个可用 schema 版本候选为 **v34**（`33.sqm`），**本文不分配、不创建**该文件（沿 D-184 先例：设计门不分配版本号，由批准后的实施批按当前迁移链分配）。tracked 行号为本基点实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 为主 checkout 的本地只读文件，本 worktree 内不存在，凡引用一律标注「计划」并以其主 checkout 实读行号为准。本文**不**修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不复制大段产品代码；不写本机绝对路径、个人数据或工具轨迹；示例全部匿名合成。

**标记约定：** 全文用【已验证事实】标注本次实读代码/文档所得的现状（行号均经抽验），用【冻结设计提案】标注本文建议、尚未批准、实现者不得当作既有 API 的内容。

## Scope

冻结 P7-08 标签与商家的设计候选面：

- **08.A 目录契约/存储**：tag 与 merchant 两个独立目录的稳定身份、名称规范化、改名/停用/tombstone 删除与引用探针、revision/CAS 与 request/receipt owner。
- **08.A 注释聚合**：交易 root 上的版本化注释聚合（tags + 可空 merchant）、当前注释指针 CAS、与金融版本历史的分离语义。
- **08.B 录入与编辑**：五类手工录入的可选关联原子贯通、请求快照扩展与旧回执向后兼容、再记一笔/切类型保留矩阵、已有交易注释编辑命令。
- **08.C 筛选与累计**：多标签 OR/AND 筛选、商家条件、semi-join 去重读取、商家普通净支出累计。
- **08.D 双端与恢复**：P7-06 备份/恢复白名单扩展点、重开、目录改名/停用后的历史可见性。

**首版切片（本文冻结的交付边界）【冻结设计提案】：** 08.A 目录契约/存储 + 08.A 注释聚合与 CAS（Q17/Q18 核心）+ 08.B 手工录入原子贯通 + 已有交易注释编辑。**导入确认前的关联选择不在首版**——计划 §8.2 明示「首版导入确认前的关联选择若分后续切片，必须显式登记范围和承接项」（计划 `:288`），承接登记见 §4.5。08.C 的筛选/累计与 08.D 的双端接线为后续切片；本文仍冻结其设计语义。本文**只冻结设计**，实现、迁移、Git 写操作与最终验收属后续独立实施批。

## 非目标（本文不授权）

- 导入原始对方文本到商家候选：当前存储现实下**不存在**可依据的原始文本（§1.4）；本文不授权扩展导入留存（触碰 D-146 边界）。
- 来源商户自动升格关联、自动别名、目录合并：计划 Q17 明示「无合并、自动别名或来源商户自动升格关联」（计划 `:271`）。
- 以改标签/商家制造新资金分录、把标签塞进 RG10、来源文本或金融版本字段：计划 Q18 明示禁止（计划 `:275`）。
- 改写 `transaction_version.note` 语义或 P7-05 修正协议：注释聚合只管 tags + merchant（§3.6）。
- 退款自动继承原交易商家/标签：退款是独立事件，只按退款自身明确关联计入统计（§5.4）。
- 完整消费口径纳入储值/预付：如需另裁决且保留原范围变化（计划 `:292`），本文不涉及。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为本 worktree 基点 `2c7cb1e` 实读行号；计划行号为主 checkout 实读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §8（计划 `:258-310`，四子项 08.A–08.D 与验收 P708-A01..A09）、§8.1 的 Q17/Q18 推荐（计划 `:271`/`:275-281`）、§8.2 原子接线与读取（计划 `:284-292`）、§10.6 的 Q17/Q18 行（计划 `:455-456`：Q17 须补「两端 Unicode/唯一性一致、目录 revision、删除全部历史引用检查」；Q18 须补「五类确认快照向后兼容、原子边界、历史页语义、导入前选择的明确切片/范围裁决」）。计划 `:4` 表明未裁决项不构成授权；Q17/Q18 为**推荐、待审批**。
- **设计规格先例（approved）**：`docs/specs/2026-09-28-p7-07-budget-design.md`（本文结构与协议先例来源；其 §3.4 冻结的 claim-first/等价 replay/CAS/回执协议形状由 D-184 批准）、`docs/specs/2026-09-19-p7-05-correction-void-recycle-design.md`（金融修正/void/restore 与 note-update 先例）、`docs/specs/2026-09-13-p7-03-ledger-view-design.md`（月度投影与有效谓词先例）。
- **决定**：D-184（P7-07 设计门批准先例：设计门不分配 schema 版本号、Q 项裁决由决定登记）、D-185（07.B 实施批：恢复白名单 `{1,31,32}` 扩展先例、目录删除引用面扩展 `catalogReferencedBudgetCategoryIds`）、D-186（07.D 实施批：`CatalogAuthority.catalogVersion` 读一致版本机制、刷新联动先例）、D-146（导入不留原文件——§1.4 差距的权威依据）。
- **架构**：`docs/ARCHITECTURE.md`（四共享模块 + 两组合根；app-ui 只消费 application 类型）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；`transaction_effective_state` 是有效谓词的**唯一 SQL 定义点**（`Ledger.sq:10048-10055`），注释读与筛选读不得另立第二套状态规则；`rg10_lot.merchant_id`（`Ledger.sq:5686`）与 `formal_transaction_metadata`（`Ledger.sq:7175-7186`）是 RG 竖井/统计时间 owner，**不**是产品商家目录，不得复用或改写（§1.3）；不引入新依赖。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 无产品标签/商家目录【已验证事实】

- 产品目录只有 account 与 category 两族：`catalog_category` 的 `kind` CHECK 仅允许 `('EXPENSE','INCOME')`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:344`）、`active` 列在 `:347`；目录管理协议（请求/authority/四态结果/commit port 契约）在 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/CatalogManagement.kt:143-207`（`CatalogCommandRequest` `:143-150`、`CatalogCommandResult` 四态 `:168-184`、`CatalogAuthority` 含 `catalogVersion` `:186-191`、claim-first 契约 KDoc `:193-207`）。
- 目录名历史与当前名读取：`SqlDelightCatalogStore.kt`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/`）在 `:425-431` 以 `selectCatalogCurrentNamesByKind` 取当前名、`:466-470` 在装配时以 `currentCategoryNames[categoryId]` 填充——**名称不在主表**、当前名 = 名称历史末条，此形状将被新目录复用（§2.2）。
- 对方（counterparty）目录是**借出领域**目录（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/Counterparty.kt:26-33`，含 `nameHistory`；`:58`/`:84` 的建/改名是 `normalizeCatalogName` 的既有复用点），**不是**商家目录，本文不扩展其语义。
- **结论**：tag 与 merchant 目录、交易注释聚合均为**新建面**；不存在可「打开」的既有产品实体。

### 1.2 无注释聚合，note 是唯一文本注释且语义独立【已验证事实】

- `transaction_version`（`Ledger.sq:15-35`）只有 `note TEXT`（`:24`）与 P7-05/07.T 后加的 `statistics_at_epoch_nanos INTEGER`（`:26`，投影索引 `:61`）；**无**任何 tag/merchant 关联表。
- note 的独立更新链已存在但 **UI 未接线**：用例 `ledger-application/.../ConfirmedTransactionNoteUpdate.kt:10-26`（显式确认请求 + `TransactionNoteUpdateRequestSnapshot`）、提交端口 `ledger-data/.../SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:28-45`（claim-first，stale 时删 claim）、逐列匹配 `:100-119`；app-ui 组合根对 `ConfirmedTransactionNoteUpdate` 的引用为 **0 命中**（本次实读 `grep`）。SQL 请求表 `transaction_note_update_request` 在 `Ledger.sq:119-130`。
- 金融修正草案 `TransactionCorrectionDraft` 含 note 字段（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Correction.kt:25-31`）；P7-05 修正端口 `SqlDelightTransactionCorrectionCommitPort.kt:33-41` 为 claim-first + 语句级 CAS（`copyCurrentVersionWithNewPostingSet`，`Ledger.sq:10273`），`StaleCurrentVersion` 时删 claim 零写入（`:165-182`）。

### 1.3 RG 竖井里的 merchant_id 不是产品商家目录【已验证事实】

- `rg10_lot.merchant_id TEXT`（`Ledger.sq:5686`）：无外键、被储值竖井触发器冻结（如 `:5907` 的触发器体对 `old.merchant_id IS new.merchant_id` 断言），是 RG-10 统计时间的竖井内私有列，**非**产品目录、**非**注释聚合的数据源。
- `formal_transaction_metadata`（`Ledger.sq:7175-7186`）是 RG 08-12 的统计时间 owner（D-091 DATA-001 统一），同样与本文无关。
- 产品行永不进 `rgXX_` 竖井：`SqlDelightCatalogStore.kt:40-44` 的类注释明示并以此约束引导（fail-closed unknown-reference）。
- **边界声明【冻结设计提案】：** 本文的商家目录与注释聚合是产品新面，与上述竖井列**无数据耦合**；`rg10_lot.merchant_id` 与 `formal_transaction_metadata` 零改动。

### 1.4 导入不留原始对方文本，计划「导入原始商家仅作候选」与存储现实存在字面差距【已验证事实】

- `import_source_record`（`Ledger.sq:7803-7817`）只有 `input_ref`/`content_hash`/结构化枚举列，**无**商户/对方原始文本列；D-146 冻结「导入不留原文件」。
- 计划 08.B 交付字面「导入原始商家仅作候选」（计划 `:265`）与 §8.2「导入原始对方名称只作来源证据/候选」（计划 `:288`）在当前存储现实下**不可实现**：没有任何留存文本可用来生成候选。差距裁决建议见 §4.5（承接登记）与 §8 开放项 5。
- **结论【冻结设计提案】：** 导入交易的商家/标签关联只能由用户**显式建立**（确认后经 §4.4 的注释编辑链）；本文不以「确认后可编辑」冒充「导入前选择」，也不授权为生成候选而扩展导入留存。

### 1.5 录入/提交/读取的既有挂点【已验证事实】

- 五类草稿：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/EntryDraft.kt:14-20 EntryType`、`:26` 起 `TypedEntryDraft` 密封接口；切类型保留矩阵 `EntryFieldRetention.switchType`（`EntryDraft.kt:186-289`）。
- 草稿→请求映射：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:721-853` 五类型 `*SaveInput` 构造（`expenseSaveInput` `:721-740`、lending 尾段 `:845-853`）。
- 请求快照为强类型 data class：`ConfirmedManualExpense.kt:40 ManualExpenseRequestSnapshot`、`ConfirmedManualTransfer.kt:29-35`（含可空 `feeCategoryId` 的 0~1 先例）、`ConfirmedManualLending.kt:51`。
- 请求表全 NOT NULL、无版本列：`manual_expense_request`（`Ledger.sq:92-104`，含 `confirmation_marker` CHECK）。
- 首笔同事务模板：`SqlDelightConfirmedManualExpenseCommitPort.kt:32-50`（claim）→ 同事务写交易族与 `insertTransactionVersion`（`:140-150` 区段，含投影列）→ 回执；导入 spine 的确认同事务（`SqlDelightImportSpineStore.kt:443-455` claim → resolve）。
- 再记一笔与重校验：`P503Reducer.kt:445-508` 的 per-type 草稿重建；`RetainedIntentRevalidation`（`P503UiEvent.kt:1055-1066`，account/category 三个 id 集合）。
- 编辑器 UI：`P503EditScreen.kt:727-745 SelectorField`（可选 picker 先例）、`:835-866 CounterpartyFormDialog`；状态机 `Editing`/`AwaitingConfirmation`/`Submitting`（`P503AppState.kt:134`/`:178`/`:205`）。

### 1.6 读取与刷新的既有挂点【已验证事实】

- 无筛选参数的整账读：`ledgerEntryRowsForLedger`（`Ledger.sq:8923-8954`，join `transaction_effective_state ... is_effective = 1`，`ORDER BY tx.transaction_id, posting.posting_index`）；展示排序在 Kotlin 侧（`ledger-application/.../QueryLedgerEntryRows.kt:21-32`，`statisticsAt` DESC → `occurredAt` DESC → `transactionId` ASC）。
- 分页 + 单读事务先例（ACC-SNAP-01）：`SqlDelightImportReviewReadAdapter.kt:46-110`（keyset 分页、批内折叠、`transactionWithResult(noEnclosing = true)`）。
- 有界月读行形状：`monthlyContributionRowsInWindow`（`Ledger.sq:10113-10151`，走 `statistics_at_epoch_nanos` 范围谓词 + `is_effective = 1` + current-version join）——商家累计的候选行来源（§5.4、§8 开放项 6）。
- 回收站读：`voidedTransactionRowsForLedger`（`Ledger.sq:10153-10185`）；同名随目录展示（`ledger-application/.../QueryRecycleBin.kt:104-124` 依赖装配，`categoryActive` 随行）。
- 引用探针先例：`SqlDelightCatalogStore.kt:499-578`（`categoryHasReferences`/`referencedCategoryIds` 聚合面，`:526-530` 为 07.B 的 budget 扩展注释），删除引用面 = 「至少与 bootstrap 未知引用扫描同宽」纪律（`:499-505` 注释）。
- 刷新联动：`P503HostCoordinator.kt:197-215 decide`（成功后权威刷新 + 挂起月度重请求）、`:329-335 onImportBatchConfirmed`、`:349-357 onP705EffectiveSurfaceChanged`、`:368-380` 起预算月度 decide/re-request（D-186 先例）；surfaces 探针字段族 `LedgerRuntimeOwner.kt:663-707` 与 `LedgerSurfaces`（`:795-820`）。
- 预算 owner 的协议特化（mint-id-post-claim）：`SqlDelightBudgetStore.kt:115-145`（`claimBudgetCommandRequest` 后才 `mintBudgetId()`、`:145` 插配置、`:174` 历史、`:204` 回执）；协议镜像注释见 `ledger-application/.../BudgetConfiguration.kt:14-23`（文件头镜像声明）、请求形状 `BudgetCommandRequest` `:105-113`、receipt outcome `:116-127` 与四态结果族 `BudgetCommandResult` `:128-145`。

## 2. 08.A 目录契约（Q17 落地）

### 2.1 两个独立命名空间【冻结设计提案】

- 账本内新建 **tag 目录**与 **merchant 目录**两族，均 ledger 作用域；二者名称空间**互相独立**（同一名称可同时是某 tag 与某 merchant），跨账本引用一律类型化拒绝（请求校验层：目标 id 必须属于请求 ledgerId；沿目录管理既有 `CatalogAuthority.ledgerId` 纪律）。
- **不得**把 tag/merchant 行塞进 `catalog_category`/`catalog_account`（其 kind CHECK 不容，`Ledger.sq:344`【已验证事实】；且语义不同）。
- 关联基数（计划 Q17 字面，计划 `:271`）：单交易单注释 revision **0～20 个 tag**、**0～1 个 merchant**。基数在请求校验层类型化拒绝 + 注释关联表上的守卫触发器兜底（§3.2）。

### 2.2 目录形状与协议【冻结设计提案；镜像目录/预算 owner 先例】

- **稳定 ID**：每目录项一个稳定文本 ID，**由调用方在 claim 之后 mint**（mint-id-post-claim 特化先例：`SqlDelightBudgetStore.kt:115-145`【已验证事实】）；ID 与名称无耦合，改名不改 ID（P708-A02 断言面）。
- **名称历史/当前名**：镜像 category 形状——主表不存名称，追加式名称历史表 + 「当前名 = 历史末条」读取（先例 `SqlDelightCatalogStore.kt:425-431`/`:466-470`【已验证事实】）；改名只追加历史、旧引用按 ID 连表后显示当前名（计划 Q17：「改名后当前与历史交易默认显示当前名」）。
- **启用状态**：`active` 布尔（先例 `catalog_category.active`，`Ledger.sq:347`【已验证事实】）；停用（active=false）**可再启用**，保留全部历史引用，只禁止新增选择（计划 Q17：「停用保留所有历史引用，只禁止新增选择」）。
- **删除 = tombstone（与 category 硬删不同）**：
  - 前提：**无任何当前/历史引用**才允许删除；引用 = 任何注释 revision 的关联行（含作废交易上的注释与注释旧 revision 的关联行——计划 Q17 字面，计划 `:273`「作废交易、注释旧 revision 也算引用」）。引用探针沿 `CatalogCategoryReferenceProbe` 先例（`CatalogManagement.kt:214-218`）扩展注释引用面；注释 revision 与其关联行不可变，故关联表即**全部**引用面（与 budget 以不可变 `budget_settings_history` 为完整引用面同理，`SqlDelightCatalogStore.kt:526-530`【已验证事实】），探针宽度 ≥ 任何未来 bootstrap 扫描的纪律不变；pending 请求 claim 行**不计入**引用面，删除与并发注释提交的双向竞态由 §3.2 的 fail-closed 再校验兜底。
  - 执行：行**保留**、打 tombstone 标记、**不可再选**（任何选择/引用该 ID 的后续请求类型化失败）、**稳定 ID 永不复用**；tombstone 不可逆、不可解除。
  - **tombstone 与停用两态的明确区分**：停用 = 可逆的软禁用（管理列表可见、可再启用、历史引用照常显示）；tombstone = 不可逆的审计 tomb（从选择器与管理主列表消失；是否在独立的审计视图展示见 §8 开放项 11）。二者互斥且都不得物理删除行。
- **revision 与 owner**：每目录项一个 revision 计数器作命令 CAS（镜像 `BudgetCommandRequest.expectedRevision` 语义，`BudgetConfiguration.kt:105-113`【已验证事实】）；目录命令族（新建/改名/停用/启用/删除）使用**独立的** request/receipt owner——claim-first、等价 `requestSnapshot` replay 返回原回执且零写入、同 ID 异快照 `RequestIdentityConflict`、revision 失配版本冲突零写入（协议形状镜像 `CatalogManagement.kt:143-207` 与 `SqlDelightCatalogStore.kt:116-209` 先例【已验证事实】）；requestId 来源与既有源不共享消费计数（`CatalogManagementRequestIdSource` 独立先例，`CatalogManagement.kt:225`）。
- 表族命名与精确 DDL、以及目录族是否复用统一名称历史表，**OPEN**（§8 开放项 3）；但冻结：全部新表为非 `rgXX_` 产品表，fresh `Ledger.sq` 终态 DDL 与迁移边逐字节一致（`32.sqm` 纪律【已验证事实】：`32.sqm` 头注「Every CREATE statement below matches the fresh Ledger.sq terminal definitions byte for byte」）。

### 2.3 名称规范化与唯一性【冻结设计提案】

- **复用 `normalizeCatalogName`**（`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/CatalogManagement.kt:129-147`【已验证事实】：trim、仅折叠 U+0020/U+3000、拒绝 U+0000–U+001F 与 U+007F–U+009F 控制码点、规范化后 ≤ 64 码点；`CATALOG_MAX_NAME_CODE_POINTS = 64` 在 `:14`）。**不新增第二套规范化实现**，不自动 Unicode 折叠、不猜别名（计划 Q17 字面）。
- 唯一性：同一目录族内，规范化后**区分大小写**精确相等者视为重名（计划 Q17 字面「按 trim 后原字符串区分大小写并唯一」，计划 `:271`）；校验在 Kotlin 层对已加载目录做（先例 `CatalogManagement.kt` 的 `accountNameTaken`/category 同名检查路径），空名/超长/重名各自类型化失败（P708-A02 向量面）。
- **SQLite 与 Kotlin 的一致性答案（计划 §10.6 Q17 行「两端 Unicode/唯一性一致」）**：
  - 唯一性是**相等谓词**：Kotlin `String` 相等（UTF-16 码元全等）与 SQLite 默认 **BINARY** collation（UTF-8 字节全等）对合法字符串给出**相同答案**——两者都是「码点序列全等」的等价判定。因此：**名称的相等判定与唯一性检查只允许在 Kotlin 层**（单一共享实现，两端同源编译）；若实施批为防御性目的在名称历史表上加唯一索引，**必须**使用默认 BINARY（禁 `COLLATE NOCASE`/自定义 collation），且其唯一性答案与 Kotlin 判定一致（同为全等）。
  - **排序禁止下推 SQL**：UTF-8 字节序 ≠ UTF-16 码元序——在码元序中 BMP 私有区 U+E000–U+F8FF 排在代理区 U+D800–DFFF **之后**，故增补平面字符（以代理对编码，首码元落在 U+D800–DFFF）在 UTF-16 码元序中排在这些 BMP 字符**之前**，而在码点序/UTF-8 字节序中排在其**后**；名称的展示排序一律 Kotlin 侧；关联集合的规范化顺序按**稳定 ID 排序**（非名称排序，计划 §8.2「标签集去重并按稳定 ID 排序」，计划 `:286`）。
  - **计数一致**：码点计数用共享 Kotlin 实现（`codePointCount` 语义），Android JVM 与 desktop JVM 共用同一 kotlin-stdlib，计数逐位一致；两端各跑同一 commonTest 属性测试（匿名样本含 BMP、增补平面、U+3000 与控制码点边界）证明（P708-A02 证据面）。
- 无 SQL `COLLATE` 依赖的现状与既有答案一致：目录唯一性从来靠 Kotlin 规范化后比较（§1.1 的名称历史形状），本文延续而非新造。

## 3. 08.A 注释聚合（Q18 落地）

### 3.1 聚合形状【冻结设计提案；镜像计划 Q18 建议，计划 `:275-281`】

- `transaction_annotation_revision`：`(ledger_id, transaction_id, annotation_revision)` 主键语义；列含 `observed_transaction_version_id`（该次注释操作所见的**当时**当前金融版本 id）、**可空** `merchant_id`、`request_id`、时间列；`annotation_revision` 自 1 起单调递增。本条及下两条的**表名/列名是冻结设计提案**（实现按此语义命名），字面 DDL 形状（精确列序、类型、索引、是否加 FK）留 §8 开放项 3。
- 标签关联表：`(ledger_id, transaction_id, annotation_revision, tag_id)` **唯一**（计划 Q18 字面）；每 revision 至多 20 行。
- 当前注释指针表：`(ledger_id, transaction_id)` 主键 → 当前 `annotation_revision`（+ 当前 revision id），形态镜像 `ledger_transaction_current_version` 是**表**的先例（`Ledger.sq:79-90`【已验证事实】）；CAS 读改写在此指针上。
- `observed_transaction_version_id` 的悬挂防护依赖既有**系统不变量**「金融 version 行永不删除」（`transaction_version` 无任何产品删除路径；版本行只追加、作废/恢复只写 `transaction_void_fact`）；注释表对该列是否加 FK 留 §8 开放项 3，但**无论是否加 FK，删除防护语义不变**（version 行不可删）。
- 命令 owner：注释命令族（编辑注释 = 以新 revision 整体替换 tags 集与 merchant）使用**自己的** request/receipt owner（计划 Q18：「原子追加 revision/关联/当前指针/receipt」）；协议镜像 note-update + 预算先例。用例/端口命名是建议，实签名 OPEN（§8 开放项 1）。
- **0..1 聚合语义与「无注释」状态**：交易可以以**从未注释**状态存在（无当前指针行）——导入交易在首版切片即处于此状态（§4.5）。该状态只在「从未注释」时出现且**不可再进入**：一旦存在注释历史，后续编辑（包括**清空全部关联**）一律是**追加一条空注释的新 revision**（0 标签、无 merchant；revision 单调递增、当前指针行**不删除**、历史可回看「何时被清空」）；**否决**「删除指针行使交易回退为无注释」的语义。存储上「无指针行」与「指针指向空 revision」是两个可区分状态，语义上前者=无关联，后者=曾有关联、已被清空。
- **无注释交易的 CAS 哨兵（HIGH 闭合）**：`expectedAnnotationRevision = 0` 是**哨兵值**，含义为「期望该交易当前无注释（无当前指针行）」——镜像 07.B 的新建绑定先例（D-185 第 5 条 (b)：`expectedRevision == 0` 为新绑定；`BudgetCommandRequest.expectedRevision` 形状，`BudgetConfiguration.kt:105-113`【已验证事实】）。CAS 匹配判定：期望 0 **当且仅当**当前指针行**不存在**；期望 R>0 **当且仅当**指针行存在且当前 revision == R；任一失配 → 类型化失败（`AnnotationRevisionConflict` 语义）且零写入。哨兵命中后的成功路径：在同一事务内创建 `annotation_revision = 1` + 关联行 + 当前指针行 + 回执（§3.2 原子边界适用）；`expectedCurrentVersionId` 对无注释交易**照常校验**（任何正式交易都有当前版本指针行，`Ledger.sq:79-90`）。

### 3.2 原子边界【冻结设计提案】

- **首笔交易 + 初始注释 revision 同事务**：五类手工提交端口在同一事务内追加 revision=1（observed = 该事务的 version 1 id）+ 关联 + 当前指针 + 回执；注释写失败则**整个金融事务回滚**（P708-A05：正式交易与关联全成或全败，零「记账先成功、随后异步贴标签」，计划 `:286` 字面）。目录在事务内**再次校验**（tag/merchant id 存在、active、未 tombstone、属同一 ledger；未知引用 fail-closed）。
- **CAS 顺序**（计划 Q18 字面，计划 `:278`）：先 claim 并 resolve 等价 replay（返回原回执零写入）；再校验 `expectedCurrentVersionId`、`expectedAnnotationRevision` 与目录引用——`expectedAnnotationRevision` 对无注释交易取哨兵 0，匹配判定按 §3.1（指针行存在性 ⟺ 0/R）；任一失配 → 类型化失败且零写入（claim 按 note-update 先例删除，保持身份可重试，`SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:28-45`【已验证事实】）。
- **pending claim 与引用面的边界（low 闭合）**：目录 tombstone 删除的引用探针**不计入**仍在途的 pending 请求 claim 行（claim 不是引用）；「删除与并发注释提交竞态」由注释提交事务内的**fail-closed 目录再校验**兜底（目录项已 tombstone → 类型化拒绝、claim 删除、零写入），反向的「注释已提交、删除其后到来」由引用探针在删除事务内挡住——两个方向都不会产生孤儿关联。
- **基数守卫**：注释关联表守卫触发器强制单 revision ≤ 20 tag（ 超 20 类型化拒绝，P708-A02）；merchant 由可空列天然 0~1。
- **保守编辑门**：注释更新的保守首版门是「当前未作废交易」——提交事务内校验 `transaction_effective_state.is_effective = 1`（唯一 SQL 定义点，`Ledger.sq:10048-10055`【已验证事实】；不在注释路径另立状态规则）。作废交易的注释编辑类型化拒绝；恢复后才可编辑。

### 3.3 void/restore 与回收站【冻结设计提案】

- **void 不删注释**：作废路径只写 `transaction_void_fact`（其行 update/delete 全禁，`Ledger.sq:9947-10005`【已验证事实】），注释行与关联行原样保留；**restore 重新可见**（有效谓词翻回 1，注释随当前版本再次出现在有效读面）。
- 回收站（`voidedTransactionRowsForLedger`，`Ledger.sq:10153-10185`【已验证事实】）对注释**只读历史**展示；编辑入口被 §3.2 的保守门挡住。
- 注释引用在删除探针中**始终计入**，无论交易是否作废（§2.2），因此「先删目录再作废交易」不可能绕过引用门。

### 3.4 与金融修正的关系【冻结设计提案】

- 金融修正（P7-05）**保留 root 当前注释**：修正端口不读不写注释表，注释指针与 revision 原样跨版本存活；修正后的当前注释的 `observed_transaction_version_id` 仍指向注释操作当时所见版本——这是诚实语义（计划 Q18：「`observedTransactionVersionId` 表示该次操作所见版本，不虚称每个金融旧版本有当时全部标签快照」，计划 `:280`）。该引用的悬挂防护与 §3.1 同源：依赖「金融 version 行永不删除」的系统不变量，修正路径只追加新版本、不改写不删除旧版本行。
- **不改写 P7-05 修正协议**：`SqlDelightTransactionCorrectionCommitPort` 的 claim/CAS/回执形状与 `copyCurrentVersionWithNewPostingSet`/`copyCurrentVersionReusingPostingSet` 零改动。
- 若产品未来要求「注释合并进完整金融版本快照」，按计划 Q18 须退回设计并改所有新建/修正路径（计划 `:280`）；两种语义**不得混用**——本文冻结的是独立聚合语义。

### 3.5 历史显示【冻结设计提案】

- 历史金融版本与注释历史**分开显示**（计划 Q18 字面，计划 `:280`）：注释历史页按 annotation_revision 列「何时、谁请求、所见版本、tags/merchant 变化」；不在金融版本时间线上内嵌注释快照。历史页 UI 语义（入口、粒度、与交易详情的关系）OPEN（§8 开放项 7）。

### 3.6 与 note 的关系【冻结设计提案】

- `transaction_version.note`（P7-05 已批准行为，note-update 链与修正草案 note 字段）**保持原样**：注释聚合只管 tags + merchant，不动 note 的写入路径、语义或 UI；note-update 独立链（`ConfirmedTransactionNoteUpdate.kt:10-98` + 提交端口）作为 08.B 注释编辑命令的**结构模板**被镜像，而非被合并。

## 4. 08.B 录入与编辑贯通

### 4.1 草稿与五类录入【冻结设计提案】

- `TypedEntryDraft` 五子类各增可选关联：`tagIds: 集合`（按稳定 ID 去重并排序后进入快照）与可空 `merchantId`；均为可选，**不增加普通入账必填项**（计划 08.B 验收字面，计划 `:265`）。五类（EXPENSE/INCOME/TRANSFER/LEND/COLLECT）一律携带——注释在交易 root 上，与 kind 无关（计划 §8.3「执行规格必须逐类列支持矩阵，不用单笔支出代表五类」，计划 `:296`）。
- 草稿→请求映射（`P503App.kt:721-853` 五类型 `*SaveInput`）与强类型快照（`Manual*RequestSnapshot`）同步扩展；`Transfer` 的可空 `feeCategoryId`（`ConfirmedManualTransfer.kt:29-35`，快照 `:29` 起、`feeCategoryId` `:35`【已验证事实】）是「可选关联进快照」的既有形状先例。
- UI 挂点：编辑器新增可选 tag 选择器与 merchant 选择器（`SelectorField` 形态先例，`P503EditScreen.kt:727-745`【已验证事实】）；merchant 新建走名称表单对话框（`CounterpartyFormDialog` 形态先例，`:835-866`），**不凭名称自动关联**受管理商家（计划 08.B 字面）。

### 4.2 再记一笔与切类型【冻结设计提案】

- **再记一笔清空标签/商家**（计划 §8.2「再记一笔建议清空标签/商家」，计划 `:286`——本文冻结为清空）：挂点 = `P503Reducer.kt:445-508` 的 per-type 草稿重建（重建出的新草稿不携带旧关联）。
- **切类型保留可选关联、确认时重校验**（计划 §8.2 字面）：挂点 = `EntryFieldRetention.switchType`（`EntryDraft.kt:186-289`）——tags/merchant 与 kind 无关，跨类型携带；确认时重校验沿 `RetainedIntentRevalidation`（`P503UiEvent.kt:1055-1066`）扩展 tag/merchant id 集合：目录已删（tombstone）/停用/跨账本的引用被清除并给出通知（镜像既有 account/category 失效清除语义，`P503Reducer.kt:445-452` 区段）。
- 两条路径各写状态机测试（计划 `:286`「分别写入状态机测试」；P708-A07 的「再记一笔不带旧关联」向量）。

### 4.3 请求快照扩展与旧回执向后兼容【冻结设计提案；证明要求冻结，编码形状 OPEN】

- **匹配先例与张力裁决（medium-2 闭合）**：含注释字段的请求**不走裸字符串比较**。规范快照仍是请求的规范记录（列示于快照字符串），但 replay 等价判定沿 `StoredNoteUpdate.matches` 的**结构化逐列匹配**先例（`SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:100-119`【已验证事实】：对存储列与快照字段逐列相等判定）：注释字段作为**独立匹配列**参与，claim 表侧持久化编码二选一，由实施规格裁决（§8 开放项 4）：(a) 新**可空**列（规范化序列化形态），或 (b) 每请求关联子表（旧行无行）。无论哪种，**旧行缺失的注释字段在匹配函数中取冻结默认值「无注释」（空集/空 merchant）参与逐列比较**——这与「裸字符串唯一等价依据」不可同真的张力由此消解：目录/预算 owner 的新表无旧行，字符串比较对它们自洽；注释扩展面对的是**存量** `manual_*_request` 行，必须走逐列默认匹配。两种编码下：
  - **向后兼容证明要求（P708-A05）**：既有 `manual_*_request` 全 NOT NULL、无版本列（`Ledger.sq:92-104`【已验证事实】），旧回执**零迁移、零回填**；匹配函数对旧行取「无注释」默认——旧请求（无注释）重放必须等价返回原回执且零写入；同 requestId 但注释不同的新请求必须 `RequestIdentityConflict`；新 claim 写入完整快照。这是唯一的先例方向（结构化逐列匹配 + 缺失字段默认；可空列对照先例：`statistics_at_epoch_nanos` 以可空列加于 `transaction_version`，`Ledger.sq:26`【已验证事实】），实施规格须以测试逐条证明上述四点，不得以「新表」绕开对既有请求表的 replay 兼容。
- 「未知结果只查询同一请求，不另发补关联命令」（计划 `:286`）：注释与金融写同一事务、同一 request，Unknown 后重开只 replay 原请求（P708-A04）。

### 4.4 已有交易注释编辑【冻结设计提案】

- 新增**独立注释更新命令**（与 note-update 链平行的结构模板：`ConfirmedTransactionNoteUpdate.kt:10-98` + `SqlDelightConfirmedTransactionNoteUpdateCommitPort.kt:28-119`【已验证事实】）：显式确认请求 + 规范快照 + claim-first + `expectedAnnotationRevision`/`expectedCurrentVersionId` CAS（无注释交易——含全部已确认导入交易——取哨兵 0，见 §3.1）+ stale 删 claim 零写入 + 回执；提交事务内追加新 revision + 关联 + 指针 + 回执。
- 适用面：任何**当前未作废**交易（含已确认导入交易，计划 `:288` 字面），经 §3.2 保守门；每个请求重写完整 tags 集与 merchant（增删改都是「以新 revision 整体替换」），无部分更新。
- 与 P7-05 交互：修正草案（`P503Correction.kt:25-31`）不改；修正流内不做注释编辑（§3.4）；注释编辑不触发金融 CAS、金融修正不触发注释 CAS，二者只通过 `expectedCurrentVersionId` 观测同一当前版本指针保持诚实。
- UI 接线（编辑入口在交易详情/编辑面）属首版切片的 08.B 面；注释**历史页** UI OPEN（§8 开放项 7）。既有 note-update 链的 UI 接线**不在**本文范围（现状未接线，§1.2【已验证事实】），不因本文顺带接线。

### 4.5 导入切片承接登记（计划 §8.2 强制项）【冻结设计提案】

- **范围登记**：首版**不含**导入确认前的关联选择——导入确认流（`SqlDelightImportSpineStore.kt` 确认事务，`:443-455`【已验证事实】）零改动、零注释字段。
- **承接项**：(i) 已确认导入交易的关联经 §4.4 注释编辑链显式建立（与手工交易同权）；(ii) 计划 08.B 字面「导入原始商家仅作候选」（计划 `:265`）在当前存储现实下不可实现（§1.4：`import_source_record` 无商户/对方文本列，D-146 不留原文件）——若产品需要原始文本候选，须另立裁决扩展导入留存（触碰 D-146 边界，**本文不授权**，登记为 §8 开放项 5 的裁决项）；(iii) 后续切片若做导入前选择，须另立设计并处理「候选来源」与「确认同事务」两个新问题，不得以本文冒充已覆盖。
- **退款**：退款是独立事件，默认**不自动继承**原交易商家/标签（计划 `:288` 字面）；退款交易经其自身编辑入口显式关联后，其关联按 §5.4 计入统计。没有现成退款新建入口不扩大范围；存储/查询仍须以匿名退款交易验证（计划 `:288`；P708-A06）。

## 5. 08.C 筛选与累计

### 5.1 筛选语义【冻结设计提案】

- 标签条件：多选，默认 **OR**，可切 **AND**（计划 `:290` 字面）；同一笔 100 元带甲乙两标签：OR 下计一笔 100，AND 下计一笔 100，各标签分组各自显示全额 100 且**不可相加为总额**（P708-A01）。无标签条件 = 全部。
- 商家条件与标签条件 **AND**（计划 `:290` 字面）；商家单选（交易 0~1 商家）。
- 停用标签**仍可用于查历史**（计划 `:290` 字面）：筛选器可包含停用项并正常命中历史注释；tombstone 项因删除时已无任何引用（§2.2），不可能命中任何筛选，只存在于审计面。

### 5.2 读取形状【冻结设计提案】

- 两步式有界读：(1) 以 EXISTS/semi-join 在注释关联表（当前 revision）上求**唯一 transactionId 集**（标签条件进 EXISTS 子查询；OR = 任一命中，AND = 逐标签 EXISTS 全命中）；(2) 对命中的 transactionId 取**当前有效版本**行（join `transaction_effective_state` 有效谓词 + current-version 指针）。
- **禁止**：多标签 JOIN 倍增（同笔多标签会成倍展开行）、按金额 `DISTINCT` 去重（计划 `:290` 明禁——金额相同的不同交易会被误去重，P708-A01 第三向量「金额相同不被误去重」）、SQL 侧金额汇总标签分组（分组计数各自独立、重叠全额，Kotlin 侧折叠）。
- 排序/分页：稳定时间（`statistics_at_epoch_nanos` 投影 + 既有范围索引，`Ledger.sq:61`【已验证事实】）+ transaction id 排序（镜像 `QueryLedgerEntryRows.kt:21-32` 的 Kotlin 侧排序语义【已验证事实】）；有界分页 + 单读事务快照（ACC-SNAP-01 先例，`SqlDelightImportReviewReadAdapter.kt:46-110`【已验证事实】）；目录名一次性批量装配，禁止全账逐笔查目录（计划 `:292`「无 N+1」）。EXISTS 在库内已有多种先例（守卫视图/触发器区段 `Ledger.sq:769-1126`；产品读查询如 `:9001` 的 evidence_link 历史判定与 `:9053`/`:9108`/`:9164` 的导入评审确认门【已验证事实】），但多标签筛选的 EXISTS/semi-join 查询是新建命名查询——精确 SQL 形状 OPEN（§8 开放项 3）。
- 详情只查目标 transactionId（计划 `:292`）。

### 5.3 失败与旧结果纪律【冻结设计提案】

- 读失败/坏目录/投影缺失返回明确失败，不渲染零结果冒充空（沿 `MonthlyActivityResult.Unavailable/InvalidState` fail-closed 纪律）。
- 旧筛选条件或旧 generation 的返回**不得覆盖新页面**：筛选请求带条件指纹与代际门，落地时校验（镜像预算月的 arm/consume 与精确落地纪律，`P503HostCoordinator.kt:368-418` 区段先例【已验证事实】）。

### 5.4 商家普通净支出累计【冻结设计提案】

- 口径**复用 §7（P7-07）ordinary 贡献**：`OrdinaryFlowClassification` 六类 + 账户 kind 分派（D-184 第 2 条冻结的行为等价性硬约束原样适用——不得另写 kind 表）；商家累计 = 当前注释指向该商家的交易、按 ordinary 净支出贡献折叠；退款只按退款**自身明确关联**计入（退款费用腿负数计入的既有语义，计划 `:290`）；界面声明「不含特殊消费」（储值/预付不纳入，另裁决才扩展，计划 `:290`）。
- 行来源二选一，OPEN（§8 开放项 6）：(a) 扩展 `monthlyContributionRowsInWindow` 行形状加 merchant 列（join 当前注释指针），或 (b) 读端按筛选命中的 transactionId 二次关联折叠。取舍随 P708-A08 规模测量裁决。
- 有效谓词只用 `transaction_effective_state`（唯一 SQL 定义点）；月份/时区契约沿用 P7-03/P7-07 冻结面（上海自然月、精确整数、禁浮点与 `SUM(DISTINCT ...)`）。

### 5.5 刷新挂点【冻结设计提案】

显式触发集（计划 `:292`「注释/目录/正式变化显式触发相应视图刷新」；联动纪律镜像 `P503HostCoordinator` 既有链【已验证事实】）：

| 变化 | 触发的刷新 |
| --- | --- |
| 目录改名/停用/启用/删除 | 已加载的筛选结果与月视图的重请求（显示名与可选项变化） |
| 注释变更（录入/编辑） | 权威刷新链 + 筛选/商家累计重请求（沿 `onP705EffectiveSurfaceChanged` 同链先例，`:349-357`） |
| 筛选条件变化 | 新代际请求；旧代际返回丢弃（§5.3） |
| 导入确认 | 沿 `onImportBatchConfirmed`（`:329-335`）——首版导入不带注释，但有效面变化仍触发同链 |
| 金融修正/void/restore | 沿既有有效面变化链；restore 后注释重新可见即随刷新出现 |

surfaces 探针按需扩字段（`LedgerSurfaces` 字段族先例，`LedgerRuntimeOwner.kt:795-820`【已验证事实】）；精确接线范围属实现批（§8 开放项 10）。

## 6. 08.D 双端与恢复

### 6.1 P7-06 备份/恢复往返【冻结设计提案】

- 新 owner 表全部为产品表 → schema v34（候选）备份的恢复准入沿 07.B F3 先例（D-185 第 5 条）：`RESTORE_SUPPORTED_SOURCE_VERSIONS` 显式集合**由 `{1,31,32}` 扩展**加入新版本号（两组合根 `App.kt`/`Main.kt` 同步，白名单仍是规格认可的显式集合、非自动派生）；新版本沿用**有条件准入**——严格迁移 + 校验测试先行，A04 往返等价腿证明前不得视为无条件（D-185 先例字面）。精确集合值由实施批按届时迁移链登记（§8 开放项 2）。
- 迁移边（候选 `33.sqm`）纯加性、零回填、`defer_foreign_keys` 外层事务包裹 + late-sentinel 回滚哨兵（`32.sqm` 纪律，文件头注释【已验证事实】）；fresh-vs-migrated DDL 逐字节等价测试为闭合 P708-A08 的必要证据。
- 既有库升级：旧交易无关联即「无注释」（§3.1），目录表空起步；全新库与升级库行为一致（P708-A08）。

### 6.2 重开与双端【冻结设计提案】

- 重开/杀进程：注释与目录状态只从库派生，无内存权威缓存；Unknown 回执后重开只 replay 同一请求（§4.3）。
- 双端共享：目录/注释/筛选全部在共享模块（domain/application/data），组合根以 nullable surfaces 接线（budget 先例，`LedgerRuntimeOwner.kt:698-700`【已验证事实】）；两端行为差异为零，设备取证（P708 设备向量）沿 P7-05 片 1b V-17 先例属验收批。

## 7. 切片与验收映射（P708-A01..A09）

【设计交付，无测试在本批运行】。验收 ID 取计划 §8.3（计划 `:298-308`）；下表把每个 ID 映射到设计条款、**归属实施切片**（按 §Scope 首版切片划分如实标注，medium-3 闭合）并声明闭合所需证据（实现/测试属后续实施批；执行规格必须逐类列五类支持矩阵，计划 `:296`）。

| 验收 ID | 归属实施切片 | 设计条款 | 闭合所需证据（后续实施批） |
| --- | --- | --- | --- |
| P708-A01 | 08.C | §5.1/§5.2 | 三笔均 100：甲乙/仅甲/仅乙 → OR 三笔 300、AND 一笔 100；甲组 200 乙组 200 不可相加；金额相同不被 DISTINCT 误去重——筛选读向量 + 分组折叠测试 |
| P708-A02 | 首版（08.A 目录） | §2.1–§2.3 | 新建/改名/停用/启用/删除、空名/Unicode 边界/重名/跨账本/超 20 标签的类型化拒绝或成功；当前/历史/作废引用阻止删除；改名稳定 ID 不变——目录命令测试 + 两端 commonTest 属性测试（§2.3 计数/比较一致） |
| P708-A03 | 首版（08.A 注释 + P7-05 交互） | §3.2–§3.4 | 金融修正保留 root 关联、注释修改追加 revision（含无注释交易哨兵 0 首建与清空 = 空 revision，§3.1）、void 隐藏/restore 恢复；金额、分录、余额及对账零额外变化——修正 × 注释交互测试（P7-05 协议零改写断言） |
| P708-A04 | 首版（08.A/08.B 命令面） | §3.1/§3.2/§4.3 | 同 request replay/conflict、注释 CAS stale（含哨兵 0 与指针行存在/缺失的匹配判定）、金融版本并发变化、目录并发停用、Unknown 后重开——claim/CAS/回执/原子回滚测试，零半成功/孤儿关联 |
| P708-A05 | 首版（08.B 手工贯通） | §3.2/§4.3 | 五类手工创建在注释写失败时注入异常 → 全成或全败；旧无注释回执精确 replay——五类支持矩阵 + 旧回执兼容四点证明（§4.3） |
| P708-A06 | 08.C | §4.5/§5.4 | 退款未关联/显式关联、来源字符串同名不猜匹配；普通净支出只按明确关联——退款匿名向量 + 口径测试（复用 ordinary 分类器断言） |
| P708-A07 | 局部首版（再记一笔/注释编辑刷新属 08.B）；筛选/代际腿属 08.C | §4.2/§5.3/§5.5 | 新建/编辑/目录改名/切筛选/导入确认/恢复后晚到旧读 → 刷新正确、旧结果丢弃；再记一笔不带旧关联——状态机测试 + 代际门测试 |
| P708-A08 | 迁移/全新库腿随首版（schema v34 边随 08.A 落地）；备份/恢复往返腿属 08.D | §6.1 | 既有库迁移、全新库、备份恢复 → 旧交易无关联即空、目录/注释历史/receipt 完整往返；恢复白名单扩展与 A04 往返腿——迁移验证器 + P7-06 集成 |
| P708-A09 | 08.C（含首版写入面的规模基线） | §5.2/§5.4/§8-10 | 20k/50k 交易、多标签高关联、大目录 → 无 N+1、无全账物化、稳定首末项与准确总额——规模测试 + 匿名基线（计划 §10.3） |

## 8. 开放项（本文未冻结，须裁决；不得静默丢弃）

1. **用例/端口实签名与失败码族**：目录命令族、注释命令族、筛选读端口的精确命名、签名、失败码枚举——OPEN，由实施规格核验（本文命名均为建议）。
2. **schema v34 分配与 `33.sqm` 创建**：候选 v34；**本文不分配不创建**（D-184 先例）；由批准后的实施批按当前迁移链分配并登记恢复白名单精确集合。
3. **索引/查询精确形状**：tag/merchant 表族 DDL、名称历史表是否与既有目录历史统一、注释表族字面 DDL 与索引（含注释表对 `observed_transaction_version_id` 是否加 FK——悬挂防护语义不因此改变，§3.1）、筛选 EXISTS 查询与商家累计查询的 SQL 形状——OPEN，随 P708-A08 测量定夺。
4. **claim 表注释编码**：§4.3 的可空列 vs 每请求关联子表，及其匹配函数对旧行默认的四点证明落地——OPEN（语义要求已冻结，编码裁决归实施规格）。
5. **导入前选择的承接切片**：范围、候选来源（§1.4 的存储现实差距裁决：是否扩展导入留存属 D-146 边界裁决）、确认同事务接线——OPEN，须显式登记，不得以「确认后可编辑」冒充已实现。
6. **商家累计行来源**：`monthlyContributionRowsInWindow` 加 merchant 列 vs 读端二次关联（§5.4）——OPEN，随 P708-A08。
7. **注释历史页 UI 语义**：入口、粒度、与交易详情/金融版本历史的分离呈现（§3.5）——OPEN（Q18 审批面「历史页语义」的承接）；**归属**：由 08.B 实施批的 UI 切片裁决（历史页与 §4.4 编辑入口同面），最迟随 P708-A03 取证闭合。
8. **筛选 UI 形态**：OR/AND 开关、tag 多选、merchant 选择器的交互形态与停用项展示——OPEN（属 08.C 切片）。
9. **目录读一致版本机制**：tag/merchant 目录是否有独立 ledger 级版本（筛选读代际比对），还是复用 `CatalogAuthority.catalogVersion`（`CatalogManagement.kt:186-191`；D-186 第 2 条 (b) 已为预算裁决「事务内版本比对」机制）——OPEN，由实施规格冻结。
10. **P708-A08 规模测量**：20k/50k 基线、缺注释交易占比、关联密度上限行为——OPEN（沿 D-186 第 2 条 (d) 的基线登记残余先例）。
11. **tombstone 的管理面呈现**：tombstone 项是否/如何在审计视图中可见（§2.2）——OPEN；**归属**：由 08.A 实施批的目录管理 UI 切片裁决（tombstone 与新建/改名/停用同属 08.A 首版面），最迟随 P708-A02 取证闭合。
12. **Q17/Q18 审批**：本文即 Q17/Q18 的审批载体（计划 §10.6 `:455-456` 列明的须补项：两端 Unicode/唯一性一致 → §2.3；目录 revision → §2.2；删除全部历史引用检查 → §2.2；五类确认快照向后兼容 → §4.3；原子边界 → §3.2；历史页语义 → §3.5 + 开放项 7；导入前选择切片裁决 → §4.5 + 开放项 5）。批准由主代理以 D-187（建议编号）登记；未批准前本文无授权力。

## 边界断言

- 本文是**设计规格草案**（`proposal`）；只冻结设计级语义；**不授权实现**、迁移、技术选型或发布——实现属后续独立实施批，不由本文授权；P708-A01..A09 全部为未来实现/验证要求，本文**无一记 PASS**。
- 本文不修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不改写 P7-05 修正协议与 `transaction_version.note` 行为；不修改 P7-01～P7-07 的既有冻结面。
- `rgXX_` 竖井、golden fixtures/expected、`.external/` 零改动；`rg10_lot.merchant_id` 与 `formal_transaction_metadata` 零改动；产品行永不进 `rgXX_` 竖井。
- `transaction_effective_state` 保持有效谓词的唯一 SQL 定义点；注释读与筛选读不另立第二套状态规则。
- 全部金额为精确整数 minor units；禁浮点、禁按金额 `DISTINCT`。
- 示例全部匿名合成；无本机绝对路径、个人数据或工具轨迹。
