# P8-05 用户可读导出（READABLE_EXPORT_XLSX）设计门——全部有效交易 → 单 sheet XLSX 流式写出

**状态：** design-draft → 待独立评审 **revision 2**（2026-11-15；rev1 依据 = 用户六裁决（同日）+ 四条须用户裁项按推荐项批准（同日，含 ⑥ 由 lead 依 golden 契约钉死）+ lead 七条裁决按推荐值批准；rev2 = 第二遍独立评审 CHANGES-REQUIRED 两项必改（F-8 金额符号叙述以 golden 佐证改向 / F-9 ③④ 改账户 kind 锚定）+ 随带 F-6/F-7/F-10 落死。设计草案 `local/p8-05-design/DESIGN-DRAFT.md` revision 2 经首轮评审闭合后转正。批准流程 = 独立评审 → 用户批准 → 决定条目；实施另立批）
**写手依据：** 本文全部 file:line 由写手于 2026-11-15 亲读复核（main = `8d4fe5c8`，P8-04 i4b squash）；竞品语料只读（`local/competitor-evidence/`），仅行为对照，不复制实现、不搬私有约定。
**指针：** 计划 `docs/PHASE8_ANDROID_PLAN.local.md:47`（出口 = 行与权威有效版本逐项一致 / 长账本有界内存 / 失败不报告成功 / 文件中无超范围标识 / 入口可发现）；结构先例 = `docs/specs/2026-11-14-p8-04-self-entry-import-design.md`（裁决表/列附表/切片/验收向量/风险的章节形状）；决定 D-006（四大类型与分类，DECISIONS.md:67-77）、D-043（账目时间与凭证时间，:515-525）、D-097（安全 location 与隐私边界，:1461）、D-138（固定 Asia/Shanghai 换算与文本时间先例，:2373-2385）、D-177（备份导出设计门，:3459-3481）、D-267/D-268（有界读与分页读模型，:5790/:5816）、D-271（归属恒上海自然月，:5865）、D-288（云端真 ENOSPC + 设备 SAF 中途写失败证据基建，:6401）。

本文不改写上述任何既有裁决：不改 D-097 诊断与隐私纪律、不改 D-177 备份容器冻结面、不改 D-267/D-268 有界读纪律、不改 D-271 上海月界归属。

---

## 1. 现状与复用边界（全部 file:line 亲读复核）

### 1.1 权威读面：能支撑导出循环的只有分页读

- **分页读（本设计的行源）**：`QueryLedgerFlowPage`（`ledger-application/.../QueryLedgerFlowPage.kt:38-67`）——`firstPage`/`pageAfter` 页大小默认 `HOME_FLOW_PAGE_SIZE = 200`（`:5-6`），页键 = `(statistics_at_epoch_nanos DESC, transaction_id DESC)`（KDoc `:30-33`）；SQL `ledgerFlowPage`（`Ledger.sq:11634-11693`）的 `page_ids` CTE 以同一序 `ORDER BY version.statistics_at_epoch_nanos DESC, version.transaction_id DESC LIMIT :page_size` 取页（`:11659-11660`），外层按 `page_nanos DESC, page_transaction_id DESC, posting_index` 展开（`:11693`）；**实参面只有四个**：`:ledger_id/:cursor_nanos/:cursor_transaction_id/:page_size`（无任何筛选臂；适配器调用点 `SqlDelightLedgerCurrentStateReadAdapter.kt:143-150` 恰传此四参）——`min_tag_hits = 0`/单元素 tags 的绑定纪律属于同族 `tagMerchantFilterRows`/搜索读（`SqlDelightTagMerchantFilterReadAdapter.kt:77/:107`、`SqlDelightSearchReadAdapter.kt:44`），**不在本路径上**。`nextCursor` 由 SQL 投影 `page_ids.page_nanos`（`SqlDelightLedgerCurrentStateReadAdapter.kt:153-163`），适配器读 `page_size + 1` 笔判下页（`:149-152`），前置 fail-loud 投影探针 `statisticsAtProjectionMissingForLedger`（`:138-142`；SQL `Ledger.sq:10850-10852`）。
- **精确总数（进度分母）**：`QueryLedgerFlowPage.total()` → `readPort.effectiveTransactionCount`（`QueryLedgerFlowPage.kt:49-55`）；SQL 单条 `count(*)`、无 posting join（`Ledger.sq:11695-11707`）。
- **有效版本定义点**：行集 = "current-version AND not voided"（`LedgerEntryReadModels.kt:20-23`）；SQL 侧 = `ledger_transaction_current_version` join `transaction_effective_state.is_effective = 1`（`Ledger.sq:11446-11453`）；作废行只经 `QueryRecycleBin`/`loadVoidedTransactionRows` 可达（`QueryRecycleBin.kt:88-114`、`LedgerCurrentStateReadPort.kt:386-390`）——**本导出不读**。
- **禁入读面（D-267 DEF-A 全账读）**：`QueryLedgerEntryRows.query()` 无界读整账（`QueryLedgerEntryRows.kt:12-17`）；`loadLedgerEntryRows` 默认 fail-loud（`LedgerCurrentStateReadPort.kt:130-134`）。导出循环一律走 `QueryLedgerFlowPage`，永不物化全账。
- **展示序 vs 页序**：显示序 = `statistics_at DESC → occurred_at DESC → transaction_id ASC`（`QueryLedgerEntryRows.kt:25-30`），是**应用层重排**；页序（cursor 序）只定页界（`LedgerCurrentStateReadPort.kt:188-196`）。两者主键同（statistics_at DESC），并列键不同。

### 1.2 流式写先例（复用形状，改流式 sink）

- **备份容器两遍写序**：`BackupContainerWriter.kt:10`（pass-1 先算 `payload_sha256` 再写头）、`:69/:150`（「ORDER IS LOAD-BEARING」）；失败不报告成功的结构先例 = `BackupExport.kt:190-218`（lease → 步骤链 → `finally` 清暂存）、`:58` 与 `:75`（「Success is reported ONLY for Succeeded」「never leaves a success-claiming artifact」）。
- **XLSX 写先例 = `SelfEntryTemplateGenerator`**（jvmMain，`SelfEntryTemplateGenerator.kt:112-125`）：六 part 最小 OOXML（`[Content_Types].xml`、`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/sharedStrings.xml`、`xl/worksheets/sheet1.xml`，KDoc `:20-46`）；**全内存 ByteArray**（`ByteArrayOutputStream`）——导出不可照抄；金额列 = 数值单元格、`<v>` 为精确十进制文本「never a binary float」（`:232-243`）；文本全走 sharedStrings（`:257-266` `LinkedHashSet` 全内存池）；XML 五实体转义（`:294-306`）；zip entry 时间戳冻在 DOS epoch（`:308-319`，可复现字节）。
- **读取器语义面（导出产物将在 jvmTest 用它读回）**：`BoundedXlsxReader`（`BoundedXlsxReader.kt:19-73`）最小面**含 inline strings**（`<is><t>`，`:41`）与数值单元格精确缓存文本（`:38-39`）；共享串、A1 `r` 引用、缺格不伪造均在面内。
- **零 docProps 先例**：模板生成器六 part 结构不含 docProps/core.xml（`SelfEntryTemplateGenerator.kt:20-46` part 清单即全部），无任何作者/标题/时间戳元数据；导出产物沿用同一清单。

### 1.3 SAF / 写出面与取消语义

- **目标端口契约**：`BackupTargetPort.openTarget(): BackupTargetWriter?`（null = 用户取消，`BackupExport.kt:121-123`）；`BackupTargetWriter.write/flushAndSync/commit`（commit 恰一次，`:134-145`）；「流关闭干净才报成功」纪律在 `AndroidTargetWriter.commit()`（`AndroidBackupTargetPort.kt:108-112`）与模板写出（`ImportFilePick.kt:188-206`）两处落地。
- **Android**：SAF `CreateDocument` + 主线程 poster + 后台线程 latch（10 分钟等待窗，`AndroidBackupTargetPort.kt:30-65`）；模板下载先例为 fire-and-forget 变体（`ImportFilePick.kt:167-215`）。
- **桌面**：Swing `JFileChooser` SAVE_DIALOG + 建议名预填（`DesktopBackupTargetPort.kt:74-85`）。
- **取消**：`BackupCancellationSignal`（`BackupExport.kt:84-87`）+ `Cancelled` 结果（`:81-82`）；`BackupCancelledException` 不逃逸用例（`:89-90`）。
- **运行租约**：`owner.acquireLease()` 非阻塞、`finally` 释放（`BackupExport.kt:199-217`）。

### 1.4 名称解析与来源标识（列的取数来源）

- **账户名**：`LedgerCatalog.accounts[].name`（`Catalog.kt:26-37`）；产品侧既有投影 `LedgerCurrentState.accountNames`（`QueryLedgerCurrentState.kt:115-120`，无名账户回退裸 id——**导出不照抄**，见 §4）；UI 逐腿行用 `accountNames[posting.accountId] ?: posting.accountId.value`（`P503OverviewScreen.kt:309-311`）。
- **分类**：`Category{parentId, postingAccountId, name}`（`Catalog.kt:43-51`）；`catalog_category` 表含 `posting_account_id`（`Ledger.sq:10430-10434`）；「posting_account_id → 叶分类」映射先例 = `MonthlyBuckets.kt:144-148`（`leafCategoryByPostingAccount`）；CURRENT 名称解析 SQL 先例 = `Ledger.sq:12818-12827`（`catalog_name_history` CURRENT，COALESCE 回退裸 id——**导出不照抄**）。一级/二级完整路径消歧先例 = P8-04 D-18（规格 `:74`）。
- **金额文本**：`formatMinorUnits`（`P503Formatting.kt:8-26`）纯整数展位、无浮点；详情金额副行的「单一带符号金额」冻结形态 = `P503LedgerView.kt:627-652`（单腿；两腿同币精确相反 → 按 kind 签名的共享幅度）。
- **来源标识**：`CreationEntry{IMPORT_CREATED, MANUAL_CREATED, UNMARKED}`（`LedgerEntryReadModels.kt:68-74`），解析优先级 = 导入确认回链 → 手工四链回执 → 未标注（`QueryRecycleBin.kt:143-147`、`QueryTransactionDetail.kt:106-108`）；批量 UNION 先例 = `Ledger.sq:10537-10539`（手工三回执表 DISTINCT UNION）与 `:12494-12500`（七语义表 UNION ALL）。`ImportRecordKind`（`ImportSpine.kt:68-78`）是候选**行形态**枚举，不是来源 App 名——「来源 = 支付宝/招行」的更细粒度无现成读面（见 D-E6）。
- **kind 可读名现状**：详情页直接渲染 `data.kind.name` 原文（`P503LedgerView.kt:398/:490`）；部分中文先例：`EntryType` 支出/收入/转账（`P503RefundEntryPresentation.kt:27-32`）与 借出/收回/退款到账（`P503ConfirmationPresentation.kt:49-51`）。**16 个 `TransactionKind`（`FormalLedger.kt:41-58`）无完整用户可读映射表**——导出新增冻结映射（D-E4）。

### 1.5 入口与呈现先例

- **入口**：侧栏抽屉「数据」组（`P503TabShell.kt:334-385`，行 `:367-379`：备份与恢复/回收站/导入账单）；调用点 `P503App.kt:4878-4909`；wiring 探针 `LedgerSurfaces`（`LedgerRuntimeOwner.kt:969-1029`，`backupExport` 先例 `:986`）。导出面呈现先例：类型化结果横幅 + liveRegion（`P503BackupExportScreen.kt:68-81`）、运行中行「正在导出…」（`:113-116`）。
- **竞品对照（只读语料）**：iCost 功能面含「账单导出」（`local/competitor-evidence/icost-110/ICOST110_D3_UI.md:146`）；其 CSV 五级编码 fallback 教训（`icost-110/ICOST110_D5_IMPORT.md:162-194`，Latin1 类永不失败兜底）是「CSV 另批」的既有依据。小星双轨文案、钱迹导出行为见 explore 语料（评审可复核，本文不逐行引用）。

---

## 2. 目标与非目标

**目标**

1. 侧栏「数据」组新增「导出账目」入口，导出**全部有效交易**（current-version 且非 voided，零筛选）为单 sheet XLSX，Android 与桌面同链路。
2. 双时间两列（凭证时间 + 统计时间，上海墙钟文本形态，D-043/D-271）与精确金额（十进制文本，禁二进制浮点）。
3. 流式有界写出：分页循环逐页写 sink，任意账本规模内存有界；取消/失败类型化，失败绝不报告成功。

**非目标**：不承诺回灌（用途差异在入口文案说明，备份走加密备份容器）；无任何筛选（筛选另立裁决）；不含作废行（回收站与历史块已承载）；不做 CSV（无先例 + iCost 编码教训，另批另裁）；不碰备份容器格式与 06.B 任何冻结面；不动桌面报表与 P8-06 报表口径；零 DDL。

---

## 3. 裁决

### 3.1 用户裁决一（2026-11-15 六项，grilling 轮批准）

| # | 裁决 | 落点 |
| --- | --- | --- |
| U1 | 导出范围 = 全部有效交易，零筛选 | 行源 = `QueryLedgerFlowPage` 全页遍历至 `nextCursor == null`（§1.1）；有效性谓词照抄 `ledgerFlowPage` 自身谓词 `Ledger.sq:11646-11649`（与计数查询 `:11695-11707` 同谓词同join形状，两者各自独立拼写于同一文件） |
| U2 | 不含作废行 | 不读 `QueryRecycleBin`/voided 面；V-6 断言作废行缺席 |
| U3 | 双时间两列，上海墙钟文本 | 列 A/B（§4）；文本形态 `yyyy-MM-dd HH:mm:ss`（D-138 先例，P8-04 规格 `:81` 同形）；换算冻结 `MonthlyBuckets.REPORT_TIME_ZONE`（`MonthlyBuckets.kt:29`），无 DST（`:28` 注） |
| U4 | 不承诺回灌；用途差异入口文案 | 导出面首行固定说明（小星双轨文案先例）：「导出的账目文件用于浏览与对账，不能用于恢复数据；整机备份请用导出备份」 |
| U5 | 入口 = 侧栏数据组与「导出备份」并列 | 抽屉「数据」组新行（D-E8） |
| U6 | 首版 XLSX 流式；列集不带内部 ID/URI；CSV 另批 | 写出器 jvmMain `java.util.zip` 流式（D-E3）；列集自查清单（D-E7）；用例与平台写出面解耦——ledger-application 冻结自有最小 sink/cancellation 契约（D-E12），app-ui 适配器桥接（批 b） |

### 3.2 用户裁决二（2026-11-15 四条须用户裁项，按推荐项批准）

| # | 裁决 | 落点 |
| --- | --- | --- |
| U7 | D-E4 kind 拟名表按草案批准 | 十个无先例拟名 + 有先例六值照抄（D-E4 全表冻结） |
| U8 | D-E8 行文案 = 「导出账目」 | 抽屉行 + 探针 `LedgerSurfaces.readableExport`（D-E8） |
| U9 | D-E5 金额列 = 数值单元格；⑥ PREPAID_RECOGNITION 钉死 | 数值格打开端语义登记为已知边界（§7 R4）；⑥ 按 golden 契约钉死（D-E5 组⑥，出处 `golden/rules-v2/rg-11.json:901-919`）；符号叙述的 golden 佐证链随 D-E5 前言登记 |
| U10 | D-E11 一致性口径 = 页级一致 | 并发修改边界声明冻结（D-E11）；VACUUM INTO 快照路线关门 |

### 3.3 机制裁决（规格冻结；lead 裁决七条按推荐值并入）

| # | 裁决 | 内容 |
| --- | --- | --- |
| D-E1 | **行序 = 页序直写**（lead 裁决） | 导出按 `QueryLedgerFlowPage` 取页顺序逐页直写（`(statistics_at DESC, transaction_id DESC)`）。理由：①流式义务——显示序的 `occurred_at` 并列键需全账物化后重排，违反 U6/有界内存；②页序本身是冻结的确定性全序（D-268 页键），同表内可对账可排序；③与读模型零新语义。**显示序不进文件**：本规格显式声明「文件行序 = 页键序，非显示序」；并列键差异不做文件内说明（表头即契约）。 |
| D-E2 | **行粒度 = 交易**，一交易一行（当前版本） | 与读模型一行一交易一致；多腿交易不拆行（拆行会把转账两腿伪装成两笔业务）。腿信息由 账户1/账户2/金额 按 D-E5 逐 kind 呈现表承载（§4）。 |
| D-E3 | **写出器 = jvmMain 流式 sink，形状改自模板生成器** | 六 part 结构与模板生成器逐字同族（§1.2）；差异两处：① `sheet1.xml` 逐行直写 `ZipOutputStream`（包批 a 自有 `ReadableExportSink` 流，D-E12——不引用任何 app-ui 类型），不建 `ByteArrayOutputStream`；② 文本单元格走 **inlineStr**（`t="inlineStr"><is><t>`，读取器语义面内 `BoundedXlsxReader.kt:41`），弃 sharedStrings 池（量级估算与理由 §7 R1）。金额 = 数值单元格精确文本（模板先例 `SelfEntryTemplateGenerator.kt:232-243`）；时间/文本 = inlineStr；zip 冻 DOS epoch、`BEST_SPEED`（`:308-319` 同族）。jvmTest 用 `BoundedXlsxReader` 读回（round-trip，V-1）。 |
| D-E4 | **kind → 用户可读名映射冻结表（单一常量源）**（U7 批准） | 16 kind 全覆盖（`FormalLedger.kt:41-58`）。**有先例六值照抄既有中文**：EXPENSE=支出、INCOME=收入、ACCOUNT_TRANSFER=转账、LEND=借出、COLLECT=收回、REFUND_RECEIPT=退款到账（`P503RefundEntryPresentation.kt:27-32`、`P503ConfirmationPresentation.kt:49-51`）。**十个拟名（U7 批准）**：OPENING_BALANCE=期初余额、CREDIT_REPAYMENT=信用还款、BALANCE_ADJUSTMENT=余额调整、BALANCE_ADJUSTMENT_REVERSAL=余额调整冲正、STORED_VALUE_RECHARGE=储值充值、STORED_VALUE_SPEND=储值消费、STORED_VALUE_EXPIRY_LOSS=储值过期损失、STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT=激活前余额调整、PREPAID_PURCHASE=预付购买、PREPAID_RECOGNITION=预付确认。映射为应用层单一常量源（P8-04 D-16 同源纪律，规格 `:72`），详情页不换用（扩散面为零）。 |
| D-E5 | **逐 kind 符号/账户呈现表（全 16 kind 已按 golden 契约/RG 语义逐组钉死，出处随行登记）** | 入账符号约定（本表全部行的判定来源，**golden 佐证**）：**费用/支出类腿记正、收入/资金来源类腿记负（原符号）**——佐证：费用腿 `+120.00`（ACCOUNTING_RULES.md:102）、`rg-01:1660` 早餐费用腿 `+35.80`、`rg-10:1745` 储值消费费用腿 `+300`、`rg-11:905` 预付确认费用腿 `+33.33`；收入腿 `rg-10:663` 赠送收入腿 `-200`。**资金流方向由 kind 语义与腿角色判定，不由符号判定**（`Money.ofMinor` 为无符号包装，`Values.kt:47-58`）。与 app 内详情副行的差异显式声明：副行是「幅度 + 按 kind 翻转」（EXPENSE 渲染负/INCOME 渲染正，`P503LedgerView.kt:630-649`），导出列取**原符号**不翻转——同一笔支出文件里为正、app 内显示为负，两者各自如实，不互为矛盾。「分类腿」= `posting_account_id` 命中 `catalog_category` 的腿。逐组：<br>① **EXPENSE / INCOME**（语义先例 `MonthlyBuckets.kt:360-365` 分账法）：金额 = 唯一分类腿**原符号值（费用腿正/收入腿负，golden 佐证 rg-01:1660/rg-10:663；不加表格级翻转——与详情副行的差异见前言）**；账户1 = 该分类腿账户（= 资金流方：支出的资金流出方/收入的流入方）；账户2 = 同 set 内唯一资金腿账户（对手方）；分类锚定该腿。<br>② **ACCOUNT_TRANSFER / LEND / COLLECT / CREDIT_REPAYMENT**（rg-03/rg-08 语义）：金额 = **非分类腿中带负号者**（资产资金流出腿，负号原样；手续费分类腿被排除，主金额不被误取）；账户1 = 该资金流出方；账户2 = 同 set 唯一非分类、非账户1 的腿（资金流入方）；分类锚定手续费腿（存在时，多笔手续费取 posting_index 最小；无 → 空格）。转出/转入币种不同时：金额/币种取**账户1（转出）腿**（不发明折算）。<br>③ **BALANCE_ADJUSTMENT / BALANCE_ADJUSTMENT_REVERSAL**（rg-09 语义，佐证 `rg-09.json:328-340` 资产腿 `+30`/权益腿 `−30`、`:364-376` 冲正资产腿 `−20`/权益腿 `+20`）：按**账户 kind 锚定非权益腿**——金额 = 非权益（资产）腿原符号值（增向调整 +30、负向调整 −30、冲正 −20 各自如实呈现），账户1 = 该资产腿（资金方），账户2 = 唯一其余腿（权益对手），分类空格。**含多账户调整（两笔以上资产腿）→ 腿不唯一 → `PRESENTATION_UNRESOLVED` 兜底（rg-03 三腿期初同拓扑），V-1 向量记录该发生率。**<br>④ **OPENING_BALANCE / STORED_VALUE_RECHARGE / STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT**（rg-01/rg-10 语义，佐证 `rg-01.json:175-188` 期初资产 `+1000.00`/权益 `−1000.00`、`rg-10:645-667` 充值三腿）：按**账户 kind 锚定非权益资产腿**——金额 = 非权益腿原符号值（期初 +1000；充值 = 储值资产腿 +1200），账户1 = 该资产腿（资金流入方），账户2 = 唯一其余腿（权益对手/充值付款腿），分类空格。**含赠送腿充值（rg-10 三腿：储值资产 +1200 / 银行 −1000 / 赠送收入 −200，非权益腿两枚不唯一）→ 腿不唯一 → `PRESENTATION_UNRESOLVED` 兜底（多账户期初 rg-03 三腿同拓扑），V-1 向量记录该发生率。**<br>⑤ **STORED_VALUE_SPEND / STORED_VALUE_EXPIRY_LOSS / PREPAID_PURCHASE**（rg-10/rg-11 语义，佐证 `rg-10:1742-1758` 消费费用腿 `+300`/储值资产腿 `−300`）：金额 = 唯一分类腿原符号值（费用腿正），账户1 = 分类腿账户，账户2 = 唯一其余腿（流出资产腿），分类锚定该分类腿（CHECK 形态 `Ledger.sq:7413-7418`/`:3616-3618`）。<br>⑥ **PREPAID_RECOGNITION**（**钉死出处 = `golden/rules-v2/rg-11.json:901-919`**：main-recognition-01 两腿——expense 腿 `+33.33` 带 `category_id: category-main-service`、prepaid_asset 腿 `-33.33`）：金额 = **唯一分类腿原符号值**（正号），账户1 = 分类腿账户，账户2 = 唯一其余腿（prepaid 资产腿），分类锚定该腿——与组①同构。<br>⑦ **REFUND_RECEIPT**（rg-07 语义）：金额 = 退款到账分类腿原符号值（可为负 = 负支出，`MonthlyBuckets.kt:360-365` 语义）；账户1 = 该分类腿；账户2 = 唯一资金腿；分类锚定该腿。<br>兜底：真库出现本表未覆盖的腿形（多腿/同号双腿）→ 行级 `PRESENTATION_UNRESOLVED`（D-E10），行内占位「（呈现未定）」并整体计数，不静默编造（D-032 不得猜测）。实施批 jvmTest 须为每组至少一条真库向量，并记录③④兜底发生率（V-1）。 |
| D-E6 | **来源列 = `CreationEntry` 三值**（lead 裁决；「来源 App 名」不做） | v1 只承诺既有读面可证明的事实：导入创建/手工创建/来源未标注（`LedgerEntryReadModels.kt:68-74`）。解析优先级 = 导入确认回链 → 手工四链回执 → 未标注（`QueryRecycleBin.kt:143-147` 同优先级）。为支撑 50k 级不 N+1：批 a 新增**一条加性命名查询**（手工四链回执 + 导入确认的 UNION ALL 批量回链；形状先例 `Ledger.sq:10537-10539`/`:12494-12500`；SELECT-only 零 DDL，schema 不动，落 ledger-data）。「来源 App 名」（支付宝/招行/自填模板）需新读面（`import_creation_confirmation` 回链只给 id 与 operationClass，`LedgerEntryReadModels.kt:36-43`，无格式身份），留待后续批。 |
| D-E7 | **超范围标识自查清单（D-097，验收逐字进 V-5）** | 文件任何单元格/表头/文件名不得出现：transaction/version/posting/confirmation/request 等 UUID 形内部 id；account_id/category_id 裸值（无名账户/分类 → 固定占位「未命名账户」「未命名分类」，**不用 UI 的裸 id 回退**——`QueryLedgerCurrentState.kt:115-120` 与 `Ledger.sq:12827` 的回退是给 app 内排序可达性的，文件不带 id）；ledger_id；绝对路径/原文件名/URI；系统内部枚举原文（kind 用 D-E4 中文名）。备注列 = 用户自己的内容原样透传（非系统标识）。文件名 = `unifiedledger-export-<yyyyMMdd>.xlsx`，无账本 id（日期 = 注入 LedgerClock 的上海墙钟当日；展示用，平台选择器可改）。**sheet 显示名冻结常量「账目导出」**（display copy only，读取器按 first sheet 关系解析，`SelfEntryTemplateGenerator.kt:100-101` 同纪律）。**零 docProps 声明**：六 part 结构不含 docProps/core.xml（无作者/标题/时间戳元数据），写出器永不新增该 part；jvmTest 结构断言钉住 part 清单恒等六 part。 |
| D-E8 | **入口 = 抽屉数据组新行「导出账目」**（U8 批准） | 行位 = 「备份与恢复」之下、「回收站」之上（两项并列、用途相邻；`P503TabShell.kt:367-379` 加一行，`P503App.kt:4878-4909` 接线）；wiring 探针 = `LedgerSurfaces.readableExport`（纯字段探针，`backupExport` 先例 `LedgerRuntimeOwner.kt:983-986`；无死行纪律）；无后缀行（导入账单行形）；导出面首行 = U4 用途差异说明。 |
| D-E9 | **进度 = 计数式进度行 + 完成横幅（liveRegion）**（lead 裁决按页刷新） | `total()` 精确且廉价（§1.1），进度行「正在导出… N / M 笔」按页刷新（备份面「正在导出…」先例 `P503BackupExportScreen.kt:113-116`；横幅 liveRegion 先例 `:68-81`）。 |
| D-E10 | **失败类型化族（新枚举，不复用 BackupExportFailure）** | `RUNTIME_NOT_READY`（租约拒绝）/ `READ_FAILED`（读面异常或投影探针 fail-loud，`SqlDelightLedgerCurrentStateReadAdapter.kt:138-142` 语义）/ `WRITE_FAILED`（sink 写/commit 失败，含 ENOSPC）/ `NOTE_TOO_LONG`（任一行备注 > 32,767 字符；§7 R6：前置检出 = 零产物零残留，中途检出 = 中止不 commit + F-3 残留登记）/ `PRESENTATION_UNRESOLVED`（D-E5 兜底：真库腿形本表未覆盖，行内占位并计数，不静默编造）。`Cancelled`（选择器取消或信号触发；信号逐页检查，语义形状 = `BackupCancellationSignal`，类型归属 D-E12 自有契约）。失败绝不报告成功、不留成功横幅（`BackupExport.kt:58/:75` 结构纪律）。**空账本 = 合法空文件（仅表头）+ 完成横幅「已导出 0 笔」**（lead 裁决：导出承诺是「可读可对账」，空集是真实答案）。数据条件中止（备注超长、呈现未解）与用户/信号中止（Cancelled）的分界：两者都不 commit，只是横幅文案与登记类别不同。 |
| D-E11 | **一致性口径 = 操作租约 + 页级一致声明，不做快照**（U10 批准；快照路线关门） | 导出全程持 `acquireLease`（`BackupExport.kt:199-204` 先例）；不做 `VACUUM INTO` 快照（61k 实测 ~65.8 s，D-177 `:3472` 登记，超出「可读对账」用途预算）。keyset 谓词 = `ledgerFlowPage` 自身 WHERE 子句（`Ledger.sq:11650-11658`；同一 keyset 形状在 `tagMerchantFilterRows`/`p802SearchPage` 各自独立拼写，本导出只经 `ledgerFlowPage` 读，不跨用其谓词）。**稳定性声明（冻结）**：并发修改（导入、修正、作废恢复，以及**statistics_at 修正**——改列即改页键）可致**已读行重复出现在文件中、未读行缺席**；导出不承诺时点快照、不承诺去重，页序与有效性谓词只保证「每个读到的页内部与当时读面一致」。导出面文案显式声明此边界。 |
| D-E12 | **ledger-application 自有最小 sink/cancellation 契约（jvmMain）** | 用例不依赖 app-ui 的 `BackupTargetPort`/`BackupTargetWriter`/`BackupCancellationSignal`。jvmMain 新增：`fun interface ReadableExportSink : AutoCloseable { fun write(bytes, offset, length); fun commit() }`（`openTarget(): ReadableExportSink?`，null = 用户取消；commit 恰一次、流关闭干净才报成功、`close` 幂等——语义照抄 `BackupExport.kt:134-145`，字节不引用 app-ui 类型）与 `fun interface ReadableExportCancellation { fun isCancelled(): Boolean }`（`BackupExport.kt:84-87` 同形）。批 a 用例 + 写出器只面向此契约（JVM 注入假 sink 测 ENOSPC/中途失败/取消）；批 b app-ui 适配器把 `ReadableExportSink` 桥到既有 `BackupTargetWriter`（薄适配 ~20 行）、`ReadableExportCancellation` 桥到既有信号——两端口平台实现零改动（`AndroidBackupTargetPort.kt`/`DesktopBackupTargetPort.kt` 原样复用，仅新建议名参数传入），依赖方向 = app-ui → ledger-application，无反向边。**适配器的 `commit()` 必须先调 `flushAndSync()` 再 `flush()/close()`（折叠 `BackupTargetWriter.flushAndSync()`，防漏刷**——`BackupTargetWriter.commit()` 自身只做 flush+close（`AndroidBackupTargetPort.kt:108-112`），文件字节达盘的义务由契约方承担，桥接时不得丢）。 |

### 3.4 列规范附表（D-E2/D-E4/D-E5 落点；单 sheet，第 1 行表头冻结；sheet 显示名 = 冻结常量「账目导出」）

| 列 | 语义 | 来源字段 | 形态 | 空值语义 |
| --- | --- | --- | --- | --- |
| A 凭证时间 | occurred_at（用户输入/来源时间，D-043） | `LedgerEntryRow.occurredAt`（`LedgerEntryReadModels.kt:29`） | 上海墙钟文本 `yyyy-MM-dd HH:mm:ss`（D-138 先例） | 恒非空（域保证） |
| B 统计时间 | statistics_at（归类/上海自然月，D-271） | `LedgerEntryRow.statisticsAt`（`:30`） | 同 A | 恒非空 |
| C 类型 | 有效业务 kind 可读名 | `LedgerEntryRow.kind`（`COALESCE(canonical_kind, kind)`，`Ledger.sq:11499`） | D-E4 冻结映射中文名 | 恒非空 |
| D 金额 | 主腿带符号精确金额 | `Posting.amount.minorUnits`（`FormalLedger.kt:3-7`）；符号/取腿 = D-E5 逐 kind 呈现表（入账符号约定在该表显式陈述并带 golden 佐证：费用/支出类腿记正、收入/资金来源类腿记负，原符号不翻转） | 数值单元格，`<v>` = 精确十进制文本（`formatMinorUnits` 同族整数展位，`P503Formatting.kt:8-26`） | 恒非空（呈现表兜底 = PRESENTATION_UNRESOLVED 占位行，不静默编造） |
| E 币种 | 主腿币种 | `CurrencyUnit.code`（`Values.kt:37-45`） | 文本（code） | 恒非空 |
| F 账户1 | 金额腿账户显示名（方向语义 = D-E5 呈现表：统一按**资金方向**锚定——金额腿所在账户；EXPENSE/INCOME 行与转账族同约定，无差异） | `LedgerCatalog.accounts[].name`（`Catalog.kt:35`） | 文本 | 无名 → 「未命名账户」（不用 id 回退，D-E7） |
| G 账户2 | 对手腿账户显示名 | 同 F | 文本 | 无唯一对手腿 / 无名 → 空格 / 「未命名账户」 |
| H 分类 | 金额腿分类完整路径 | `posting_account_id → catalog_category`（`Ledger.sq:10430-10434`）→ `parentId` 路径（`Catalog.kt:43-51`；映射先例 `MonthlyBuckets.kt:144-148`）；名称 = catalog 快照名 | 文本「一级/二级」（完整路径消歧先例 P8-04 D-18） | 金额腿无分类（转账族无手续费等）→ 空；无 CURRENT 名 → 「未命名分类」 |
| I 备注 | 当前版本 note | `LedgerEntryRow.note`（`LedgerEntryReadModels.kt:31`） | 文本原样（XML 五实体转义，模板先例 `SelfEntryTemplateGenerator.kt:294-306`） | null → 空格 |
| J 来源 | 创建来源枚举 | 批 a 新命名查询（D-E6） | 文本：导入创建/手工创建/来源未标注（`LedgerEntryReadModels.kt:68-74`） | 恒非空（UNMARKED 兜底） |

表头 10 串为**单一常量源**（写出器持有、测试同源引用，D-16 纪律）；建议名 `unifiedledger-export-<yyyyMMdd>.xlsx`（日期 = 注入 LedgerClock 的上海墙钟当日；展示用，平台选择器可改）。

---

## 4. 实施切片与可写范围（每批零 DDL、独立 PR、写范围互斥）

**批 a：流式写出器 + 列契约 + 自有 sink/cancellation 契约（jvmTest 真库）**
- `ledger-application` jvmMain 新增：`export/readable/ReadableLedgerXlsxWriter.kt`（六 part 流式、inlineStr、D-E3 形状）；`ReadableExportSink`/`ReadableExportCancellation` 自有契约（D-E12）；commonMain 新增列契约纯函数（双时间文本化、kind 映射 D-E4、逐 kind 呈现表 D-E5、名称/分类解析）与导出用例（lease + `QueryLedgerFlowPage` 循环 + 注入 sink/cancellation/进度参数，结果 = D-E10 类型化族）。**批 a 对 app-ui 零依赖**。
- 加性命名查询一条（D-E6 批量来源回链；SELECT-only 零 DDL，schema 不动，落 ledger-data）。
- 测试：`ReadableLedgerXlsxWriterJvmTest`（结构含「part 清单恒等六 part」断言 + `BoundedXlsxReader` round-trip 含 inlineStr 向量）；真库对账测试（合成账本覆盖 16 kind（D-E5 每组至少一条，含 rg-09 增向/负向调整、rg-01 期初、rg-10 无赠送单腿充值）+ 作废行 + 多版本修正 + 同秒并列 + 跨币种转账 + 手续费转账 + 超 32,767 字符备注，行级与全页遍历 + `effectiveTransactionCount` 逐项相等；NOTE_TOO_LONG 前置/中途两腿；③④ 兜底腿（赠送腿充值 rg-10:645-667 三腿 / 多账户期初 rg-03:228-234 三腿）各一条 `PRESENTATION_UNRESOLVED` 向量并记录发生率）；大样本有界性测试（假页源「next page 请求计数 = 页数 + 已交付未消费 ≤ 1」，5 万行级）。
- 写范围：`ledger-application`（jvmMain/commonMain/jvmTest）+ `ledger-data` 新命名查询 + 其 shard 登记行。

**批 b：双端入口接线 + SAF/桌面 chooser + 进度/失败呈现（依赖批 a 契约，不碰批 a 文件）**
- `app-ui`：`LedgerSurfaces.readableExport` 探针（`LedgerRuntimeOwner.kt:969` 区）；抽屉新行「导出账目」（`P503TabShell.kt` + `P503App.kt` 接线）；导出面（用途差异说明行 U4 + 进度行 D-E9 + 取消钮 + 类型化横幅，reducer 事件/状态新增）。写出面 = 适配器桥（D-E12）：app-ui 适配器把批 a 的 `ReadableExportSink`/`ReadableExportCancellation` 桥到既有 `BackupTargetPort`/`BackupTargetWriter`/`BackupCancellationSignal`（依赖方向 app-ui → ledger-application；`AndroidBackupTargetPort.kt`/`DesktopBackupTargetPort.kt` 平台实现原样复用，建议名参数化传入）。
- `android-app`：CreateDocument 接线（latch 形状照 `AndroidBackupTargetPort.kt:42-65`；后台线程跑用例）。
- `desktop-app`：`JFileChooser` SAVE_DIALOG（`DesktopBackupTargetPort.kt:74-85` 先例）。
- 测试：app-ui commonTest 呈现/reducer 矩阵（含空账本 D-E10、取消、每类失败文案、F-3 残留提示文案）；适配器桥 JVM 注入测试（sink 语义等价性：commit 恰一次/关闭干净才成功/取消 null，两端口既有注入先例 `AndroidBackupTargetPortTest.kt:21`）。
- 写范围：`app-ui` + `android-app` + `desktop-app` 各自测试；与批 a 无文件交集。

**批 c：设备验证腿 + 大样本读数**
- 仪器化：SAF 全链（入口 → chooser → 进度 → 横幅；成功/取消/写出失败三腿，ENOSPC/SAF 中途失败证据基建已在 D-288）；TalkBack 腿（横幅 liveRegion、进度行可朗读）；Excel/WPS 真机可打开腿（§7 R3 登记纪律）。
- 大样本读数：2k 与 50k 级合成库导出设备读数（耗时/内存观察），沿共享 APK producer + AVD 分片流程；**新仪器化类须登记 `android-instrumented-inventory.txt` + `android-instrumented-shards.txt` 双清单**（checkpoint 教训）。
- 写范围：`android-app` 仪器化 + 两 manifest + Python 冻数测试（如有分片断言）。

**顺序**：a → b → c（b 依赖 a 的契约与结果族；c 依赖 b 的入口接线）。Rig 线写面（`P503ImportReview*.kt`）零交集，按 D-15 惯例在批 b 登记写范围申报。

---

## 5. 验收向量

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | 行与权威有效版本逐项一致：真库合成账本（16 kind 按 D-E5 每组至少一条，含 rg-09 增向/负向调整与 rg-01 期初，及③④兜底腿向量并记录发生率）+ 作废 + 多版本修正 + 手续费转账 + 跨币种转账 + 同秒并列导出后经 `BoundedXlsxReader` 读回，逐行与 `QueryLedgerFlowPage` 全页遍历、`effectiveTransactionCount` 相等；金额为精确十进制文本、无浮点，符号逐组对齐 D-E5 golden 佐证链 | jvmTest（真库） |
| V-2 | 双时间两列：A/B 文本按 Asia/Shanghai 往返解析回原 instant（含月界 00:00、跨日边界向量）；统计月归属与 `MonthlyBuckets.bucketKey` 一致 | jvmTest |
| V-3 | 长账本有界内存：代码级论证（逐页直写、无全账 `toList`/无全表 StringBuilder——grep 纪律入自查）+ 假页源断言 + 50k 行级 jvmTest；设备 50k 读数登记。「在途页 ≤ 1」的可观察定义：假页源（jvmTest 假 `readPort`）在每次「next page 请求」时计数一次，导出结束后断言请求数 = 页数（每页恰好一次请求、零预取零重读），且任一时刻假页源持有的「已交付未消费」页 ≤ 1；两条断言都以假页源自身的回调计数为观察量，不依赖内存采样 | jvmTest + 设备读数 |
| V-4 | 失败不报告成功：注入型 sink 中途抛（ENOSPC 语义）/ 尾部失败 / 逐页取消 / 前置与中途 NOTE_TOO_LONG → 对应类型化结果，无成功横幅语义；断言 `commit` 未被调用（无「提交过的半成品」语义）；设备腿 = 云端真 ENOSPC + SAF 中途失败（基建 D-288）；F-3 残留半截文件为登记边界（横幅明示，不承诺自动删除） | jvmTest + 设备 |
| V-5 | 无超范围标识：读回全部单元格断言无 UUID 形 id/URI/绝对路径/裸 account_id/category_id/ledger_id；无名账户/分类落固定占位；文件名无 id；part 清单恒等六 part（零 docProps） | jvmTest |
| V-6 | 不含作废行：作废交易在导出文件缺席且不计入行数；app 内回收站可达性不受影响（既有用例回归） | jvmTest |
| V-7 | 入口可发现 + 用途差异文案：抽屉新行 wiring 探针三态（wired/unwired/无死行）、导出面固定说明行存在；空账本导出 = 仅表头 + 「已导出 0 笔」 | app-ui commonTest |
| V-8 | TalkBack：完成/失败横幅 liveRegion 宣告、进度行可朗读、取消钮可触达；1.3× 字号不溢出（P8-00 出口条件同族） | 设备仪器化 |
| V-9 | 兼容性：产物在设备 Excel/WPS 可打开且行数/列数正确（§7 R3 登记纪律） | 设备证据 |

---

## 6. 风险与承接

1. **sharedStrings 内存（已处置）**：模板先例 `LinkedHashSet` 全内存池（`SelfEntryTemplateGenerator.kt:257-266`）不可搬。量级估算：50k 行 × 双时间列几乎全异（~100k 串 × ~20 字符 ≈ 2 MB 数据 + ~100k × ~90 B 集合开销 ≈ 9 MB）+ 备注（50k × ~50 B ≈ 2.5 MB）→ 池化全程驻留 **~10-15 MB** 且在写完前不可释放，与有界纪律相悖。**处置 = D-E3 inlineStr 直写**：池归零；代价 = sheet1.xml 无去重膨胀，估算 50k 行 ≈ 24 MB 未压缩 → DEFLATE 后 **~2-4 MB** 文件（ZipOutputStream 流式，压缩器窗口有界）。若设备兼容性否决 inlineStr（R3），回退 = sharedStrings 池化 + 上限混合（1 万条封顶，超上限字符串转 inlineStr 混排——两者都在读取器语义面内），池内存被上限钉死。
2. **XML 尺寸与写出耗时**：50k 行未压缩 ~24 MB、BEST_SPEED 下秒级~十秒级；与 D-E9 计数式进度匹配。不做提前优化，读数归批 c。
3. **Excel/WPS 兼容性无本地测试可钉**：inlineStr 数值/文本混合包在真实编辑器的可打开性只能设备证据兜底——沿 P8-04 规格 `:123` 登记纪律：若编辑器要求 styles.xml 等额外 part，按「写出器扩 part、契约不变」处理并登记；V-9 为硬向量。
4. **金额数值单元格的打开端语义（U9 批准登记）**：`<v>` 内文本精确，但 Excel 打开后按二进制双精度接管（显示 15 位有效数字内无损；求和属 Excel 语义）。登记为已知边界，不改（模板先例同形态已在产）。
5. **并发一致性（U10 批准）**：租约内页级一致，无时点快照承诺（D-E11 冻结声明）；快照路线关门。
6. **备注超限（已定死）**：任一行备注 > 32,767 字符（OOXML 单元格硬上限，登记的外部事实，规格冻结时以 ECMA-376 文本复核）→ `NOTE_TOO_LONG`：创建目标**前**（第一页读出后即可检出）拒绝，零产物零残留；循环**中途**检出（第一页之后）→ 中止写出、不 commit、`close` 收敛，按 R8 登记残留，横幅明示「备注超长，导出未完成」。截断永不采用（破坏「可对账」承诺）。
7. **批 a 新增命名查询**是唯一 SQL 面（SELECT-only）；零新 SQL 的回退 = 逐交易回链（50k 级 N+1，分钟级），不采纳。
8. **失败残留半截 zip（登记）**：WRITE_FAILED/中途中断时，SAF/桌面 provider 处通常留下不完整的 `.xlsx`（Android SAF 无原子替换、桌面按平台能力，`AndroidBackupTargetPort.kt:86-89` 与 `BackupExport.kt:131-134` 同一边界）。导出面承诺：失败/取消不报告成功、横幅明示「可能留有不完整的文件，请删除」；不承诺平台自动删除（备份导出同边界）。验收不要求零残留。
9. **`BoundedXlsxReader` 读回面上的 inlineStr**：读取器最小语义面已含 inline strings（`:41`），但 round-trip 测试须显式断言 inlineStr 形态被读回（模板生成器产物全走 sharedStrings，读回测试没有 inlineStr 先例向量）；若读取器读回有缺陷，修复属读取器扩面（登记纪律），不动写出契约。

---

## 7. 边界

入：本文、批 a–c 的 tracked 变更、随之的决定条目。

出：**零 DDL**（schema/迁移序列不动）；不碰 `.external/`（竞品语料只读，golden 契约只作钉死出处引用）；不碰备份容器冻结格式、06.B/06.C/06.D 任何行为与文案；不做 CSV、筛选、回灌承诺；不动桌面报表与 P8-06；kind 映射不回写详情页（D-E4 扩散面为零）；不改 `QueryLedgerEntryRows`/`QueryMonthlyActivity` 任何调用方（D-267 全账读禁入纪律原样）。不 commit：本规格落 tracked 文件但不 commit，commit 由 lead 评审流程统一执行。
