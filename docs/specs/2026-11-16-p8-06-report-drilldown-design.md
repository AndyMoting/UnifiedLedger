# P8-06 报表与下钻设计（周期、日均、收支日历、分类/账户下钻与详情回程）

状态：approved（2026-11-17，`D-343` 设计批准；**设计已批准、尚未实现**。实施另立批次，按仓库既有高风险变更路由（独立审查 + 云端 CI）推进；本规格不构成实现、迁移或发布授权）

**Revision:** v1（2026-11-17 批准）。本版冻结：期间模型与上海半开窗口、普通收支贡献集合与每 surface 一个复合 typed 快照、当地日桶与完整自然日分母、有符号舍入、未来标注口径、目录/轴/`occurred_at` 解析三类前置校验、载荷六态与回程语义、账户资金视图局部显示例外。变更以本文件与 `docs/DECISIONS.md` 的 `D-343` 为准。

日期：2026-11-16，批准日期 2026-11-17。观察基线：`main` `775ade1e`（tracked 行号为该基线实读行号）。**范围性质**：零 DDL（schema 停 v37）、零迁移、零依赖、零账务写入；新增内容全部是只读命名查询、应用层纯折叠、UI 只读接线与回程扩展。

**批准范围**：本规格冻结产品合同与读面设计（`D-343`）。§6 只保留四项**尚未选定**的实现细节（日均展示样式、索引迁移、性能门、日历视觉形制）；其余机制（端口形态与参数化贡献谓词、报表期间游标、分类子树谓词、单请求单事务快照、类型化 `returnTo`）均已在 §3 与 `D-343` 冻结，**其替代方案不采纳**。决定号、实施拆分与发布由 `docs/DECISIONS.md` 与批次任务书承载。

## 0. Authority And Boundary

本规格逐条对齐以下权威（行号为基线实读）：

- **账务规则**：`docs/ACCOUNTING_RULES.md:27`（金额精确整数/十进制、禁浮点）、`:42-50`（支出/收入方向）、`:54-60`（转账本金不计收支、手续费为费用）、`:66-68`（借贷本金与实收利息）、`:140-158`（退款按各自真实期间、到账期负支出、不记普通收入、不回写原消费日期）、`:176-186`（储值口径）、`:196-198`（预付分摊）、`:202-207`（**实际资金时间决定余额变化，统计时间决定报表归属**）、`:225`（余额调整零普通收支）、`:241-245`（对账在真实分录级、状态不改余额与报表）、`:259-263`（停用/改名不改变历史）、`:299-301`（看账读模型：普通收支 kind 与账户门控、上海自然月、桶键=当前版本 `statistics_at`、结余=普通收入−净支出）。
- **产品需求**：`docs/PRODUCT_REQUIREMENTS.md:21-25`（按账户与时间范围查看、每条实际账户变动独立对账）、`:42`（离线可用余额、报表与对账）、`:71`（P7-03 月卡与结余口径）。
- **架构**：`docs/ARCHITECTURE.md:11-19`（账务核心独立、规则经端口）、`:21-35`（模块职责）、`:53-59`（`app-ui` 只消费 application 层类型；不同币种不得汇总）、`:69`（来源时间与统计时间分离，不得被时钟覆盖）。
- **已批准决定**：`D-343`（本规格的设计批准，含账户资金视图局部例外与残余、验证义务）、`D-120`（P5-03 共享 `app-ui` 与演示面 B 边界）、`D-122`（三 Tab 与分析面消费派生汇总）、`D-145`（P7-03 读模型、R-Q06-1..4/R-Q07-1..4 冻结）、`D-184`（数值时间投影与缺投影 fail-loud）、`D-266`/`D-267`（有界月读、币种轴自有投影）、`D-268`（键集分页、页键、SQL 聚合与虚拟化）、`D-301`（P8-00 视觉与导航基线）、`D-308`（P8-01a 账户队列：账户范围 + `occurred_at DESC, posting_id ASC` 键集页 + 页 100）、`D-339`/`D-340`（HOME 月聚合文案已退役，设备侧月聚合观察面只剩分析页）。
- **既有本批前置规格**：`docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md:39`（分析分类条→分类下钻列表归 P8-06）、`:48`（**周期分段与日均支出是呈现占位，查询能力/口径/规模边界归 P8-06**）、`:102`（分类下钻实现出基线）；`docs/specs/2026-11-11-p8-02-search-filter-design.md:62`（计数与首屏可能非同快照的既有取舍）、`:74-76`（收支日历内页与聚合读模型归 P8-06）、`:183`（搜索结果行当前不可点）。
- **External Reference Gate**：已按 gate 读完三处必读（来源登记、决策日志、核心验收计划）。核心验收计划的相关约束是「每个正式场景冻结分录、余额变化与报表预期，用户界面显示业务结果」，且「关闭/失败/重复等反例必须写出零分录，不能用缺失结果代替」。三处证据只读，其内容不入 tracked 文档正文；第三方实现只作交互参考，**不作账务算法或口径依据**，其细节、路径与代码不入本文档。

**不可触碰面**：`.external/` 只读；`rgXX_` 竖井与 golden fixtures/expected 零改动；导入链写入语义不变；对账写入面（`posting_reconciliation`/`evidence_link`/确认动作）零改动；`P703SPEC-10` 的 `SelectMonth` 可选域与 `AnalysisMonthShift` 语义不变（§3.1.3）；并行 maximum-scale rig（`android-scale.yml` 与其驱动器）不属于本批可写面。

## 1. 已批准合同

以下按主题分组列出已批准的合同要点；组内序号不表示优先级。本规格只需给出可实现的读模型与判据，**不得改写语义**。

### 1.1 范围与呈现入口

1. 周期支持周/月/年/全部/自定义，默认本月，**周一起周**。
2. 纳入按账户有界的明细与下钻；**当前余额复用资产页**，不新增账户利润或历史余额曲线。
3. 纳入本期**真实日均支出**。
4. 自然月**逐日普通收支**日历，日期可打开对应明细；不扩为提醒/预算日历。
5. 报表下钻之外，一并接通搜索结果行、现有账户流水行的详情与原路返回，保留筛选、已加载内容与滚动位置。

### 1.2 期间与统计口径

1. **全部期间**：包含所有当前有效账目的统计日期；**未来统计日期纳入并明确标注**。
2. **自定义边界**：起止日期均包含；换算为「上海起始日零点 → 结束日次日零点」的半开窗口。
3. **日均**：普通净支出 ÷ 区间自然日数；**进行中期间不缩短分母**；退款保留负号；按币种精度四舍五入展示；全链精确整数/十进制，无浮点金额。
4. **日历集合**：笔数与日内明细只包含贡献普通收支的交易，含退款/手续费/利息，不含纯本金；突出统计贡献金额；能打开完整交易详情。

### 1.3 账户、读取与回程

1. **账户时间**：按实际资金发生时间筛选，金额为本账户真实分录变动；报表与日历仍按统计时间。**已批准的局部显示例外**（2026-11-17，见 `D-343`）：只有**账户资金流水主行**显示并标明正式账目的「**资金发生日期**」（`occurred_at`，上海当地日），与筛选、排序同轴；首页、搜索、普通报表、日历仍显示账目统计时间；原始凭证支付时间仍只在证据详情按需显示。该例外是对「平时界面只显示账目时间」规则的局部例外（该规则见外部决定日志 `D-043`，只读不改），不改任何日期、金额、来源事实或账务效果。
2. **全部跨度**：最早至最晚有效统计日期，首尾含，含未来；空账本无跨度，日均不适用。
3. **分类入口**：保留一级展开二级；一级另有「全部明细」入口，二级可下钻；一级合计包含子分类。
4. **同次读取**：新报表同一次请求内的总额/分类或日历聚合、该明细页首屏及计数采用**短读快照**；不承诺跨屏/跨页长期同一快照，不长期锁库。
5. **回程细化**：只读返回保留页与滚动；**本应用内确定写入后保留条件、失效旧深页，权威刷新到第一页并提示**；不自动检测外部变更。此项是 §1.1 第 5 条的细化。

## 2. 现状事实（基线实读）

1. **月卡与趋势已在分析页渲染，报表周期区不存在**：分析页签名与月卡/分类/趋势区落在 `P503TabShell.kt:479-497`、`:527-579`；分类区一级展开二级在 `P503LedgerView.kt:162-249`（`:183-227` 为展开行）；`app-ui` 生产源码里「周期」「日均」两词零命中，「周」只出现在一条时间解析测试夹具的字符串（`P503DraftValidationTest.kt:67-68`），即 P8-00 `:48` 的占位尚未实现。HOME 的月行与「收支日历」胶囊构件已在，但宿主不接线、不渲染（`P8HomeFlow.kt:250-280`、`P503App.kt:5081-5082`）。
2. **月度读模型是时间窗内的全量行，没有行数界**：`QueryMonthlyActivity` 经 `loadLedgerEntryRowsInWindow` 取一个时间窗（`QueryMonthlyActivity.kt:32-51`、`:117-129`）；端口默认实现是「整账读再过滤」（`LedgerCurrentStateReadPort.kt:143-151`），SQL 适配器走 `ledgerEntryRowsInWindow`（`SqlDelightLedgerCurrentStateReadAdapter.kt:337-365`），该查询只有时间谓词、**无 `LIMIT`**（`Ledger.sq:9258-9303`）。因此「一个月」不是行数界：50k 交易集中在单月时该读返回 50k 行，聚合与分类折叠都在应用层内存里。既有有界先例是另一类查询：键集页 `ledgerFlowPage`（页界 `LIMIT` 绑在交易上，`Ledger.sq:11659-11718`）、搜索页 50（`Ledger.sq:12515`、`:12657`）、SQL 计数 `effectiveTransactionCount`（`Ledger.sq:11722-11732`）、按账户键集页（`Ledger.sq:12013-12146`，页 100）。
3. **账户流水当前是「整账页 + 内存过滤」，不是账户读**：行源是首页整账键集页，宿主逐页按本账户分录过滤（`P503AccountFlowScreen.kt:27-58` 的注释与 `P503AppState.kt:452-476` 的 KDoc）；因此没有按账户计数（只显示「已加载 N 笔」）、末页是整账末页，行日期取统计时间（`P503AccountFlowScreen.kt:236-241`）。已存在账户范围的只读页读先例是 P8-01 队列（`P801AccountReconciliationReadPort.kt:313-332`，页键 `occurred_at DESC, posting_id ASC`）。
4. **`occurred_at` 是混合形态 TEXT，字典序不等于时间序**：`transaction_version.occurred_at` 为 `TEXT`（`Ledger.sq:15-27`，无 `occurred_at` 专用索引，唯一时间索引是统计时间的 `transaction_version_statistics_at_range_idx`，`Ledger.sq:60-61`）；真实写入既可能是来源自带偏移文本、也可能是 `Instant.toString()` 的 `Z` 形态，故按文本 DESC 会把较旧的 `+08:00` 行排到较新的 `Z` 行之上（`Ledger.sq:11983-11997` 的既有披露与内联 epoch 解析表达式）。同一文件已给出「无 DDL、把 `occurred_at` 解析成 epoch 纳秒后再做键集」的已交付写法（页面查询 `Ledger.sq:12013-12146`，键集谓词与 `ORDER BY page.occurred_at_nanos DESC, page.posting_id ASC` 在 `:12138-12146`）。
5. **详情回程只认 HOME**：`SelectTransaction` 携带的详情状态只记 `overview`+`originTab`，事件注释明写「仅 HOME 行可达」（`P503AppState.kt:424-450`、`P503UiEvent.kt:393-409`）；搜索结果行是 `LedgerEntryFlowRow(..., onSelectTransaction = {}, interactionsEnabled = false)`（`P503SearchScreen.kt:328`），账户流水行同样不开详情（`P503AccountFlowScreen.kt:205-233`）。已有多级返回目标先例：对账队列用类型化 `returnTo`（`P503AppState.kt:534-562`，`ReconciliationBackTarget` 见 `:1091-1102`）。
6. **确定写入后的刷新链可复用，但只到首屏**：`Created`/`NoChange`/`Recovered` 触发一次权威 `refresh()`（`P503App.kt:903-964`），落在 `RefreshResult` 后由宿主完成月度重取（`:940-950`）；月度重取会整轮替换流水并推进 `flowGeneration`（`:1010-1025`），晚到页按 generation 丢弃（`:1047-1072`）。`distinctBy` 去重只去重不刷新旧值（`:1065`）。所以：**首屏刷新 + 失效旧页是现成能力，跨页长期同一快照不存在**（`docs/specs/2026-11-11-p8-02-search-filter-design.md:62` 亦登记同族取舍）。
7. **短读快照有已交付先例**：`MonthlyContributionReadPort` 在一个 `transactionWithResult`（单连接一次读事务）里取目录版本与分录行，调用方传入 `expectedCatalogVersion`，不一致即类型化失败（`MonthlyContributionReadPort.kt:30-41`、`:85-97`；适配器 `SqlDelightMonthlyContributionReadAdapter.kt:24-45`）。
8. **目录与金额口径的现有事实**：`catalog_account` 带 `kind`/`real_account`/`owned_by_user`（`Ledger.sq:340-353`）；`catalog_category` 以 `posting_account_id` 反解叶子分类并带 `catalog_category_by_parent` 索引（`Ledger.sq:355-369`）；账户维度的 `posting(ledger_id, account_id)` **无索引**（全仓 `CREATE INDEX` 清单逐条核对无此索引，见 `Ledger.sq:60/263/368/449/500/656/671/8299/8461-8462/8501/8535/10759/12775`）。schema 当前 **v37**（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/CatalogV27ToV29MigrationTest.kt:24` 断言 `LedgerDatabase.Schema.version == 37`）。
9. **日均与「全部跨度」缺读面（口径更正）**：现有最早统计瞬时读只有 MIN（`Ledger.sq:9343`、端口 `LedgerCurrentStateReadPort.kt:247-251`），**没有 MAX**。区间计数并不缺——`p802SearchCount` 已是时间窗计数（`Ledger.sq:12657`），缺的是**普通收支口径的区间 count**（贡献集合 + 有效 + 去重交易），另有整账口径的 `effectiveTransactionCount`（`Ledger.sq:11722-11732`）。
10. **设备侧月聚合观察面只剩分析页**（`D-339`/`D-340` 的登记）：HOME 不再渲染月卡文案，月卡/月选择器由分析页渲染（`P503TabShell.kt:527-542`）。本批的设备证据因此只能打分析页与新增面，不能沿用原文案。
11. **读端口是可扩展的接口，不是新 port**（端口形态依据）：`LedgerCurrentStateReadPort` 是 `interface`（`LedgerCurrentStateReadPort.kt:66`），已有多处默认实现方法（如 `:120-151` 的 fail-loud 默认、`:170`、`:197-224` 的键集默认），因此新增带默认实现的方法不会破坏既有 doubles 与构造点；同形的一次事务先例见 `SqlDelightMonthlyContributionReadAdapter.kt:68-95`（`transactionWithResult(noEnclosing = true)` + `expectedCatalogVersion` 门）。

## 3. 冻结机制（本规格提议）

### 3.1 期间模型

1. **五种周期**：`WEEK`（周一起周）/`MONTH`/`YEAR`/`ALL`/`CUSTOM{startDate, endDate}`；默认 `MONTH`＝当次 `LedgerClock.now()` 在上海时区解析的当月（先例 `MonthlyBuckets.currentMonth`，`MonthlyBuckets.kt:39-46`）。
2. **一律换算成上海半开窗口** `[startInclusive, endExclusive)`：周＝周一 00:00 → 下周一 00:00；月＝当月 1 日 00:00 → 次月 1 日 00:00；年＝1 月 1 日 00:00 → 次年 1 月 1 日 00:00；自定义＝起始日 00:00 → 结束日次日 00:00（起止日都含，§1.2 第 2 条）；`ALL`＝最早有效统计日 00:00 → 最晚有效统计日次日 00:00（§1.3 第 2 条）。
3. **报表期间游标独立于月卡**：报表覆盖 `[最早有效统计日所在期间, 最晚有效统计日所在期间]`（两端含，**含未来**）；`P703SPEC-10` 的 `SelectMonth` 可选域与 `AnalysisMonthShift` 基数是月卡/趋势的既有语义，本批不修改（§2 事实 10）。两个游标同屏时必须各自标注期间（见 §3.1.3 与 §3.10.3）。空账本无任何期间：报表呈现「无期间」，日均不适用，不显示 0。
4. **未来标注**：口径＝**上海统计日期晚于今天**，阈值取「今天之后的下一个当地零点」的 epoch（`Asia/Shanghai`，由时区库生成，与 §3.10.2 同一处定义），即 `statistics_at_epoch_nanos >= :next_local_midnight_nanos`；今日尚未发生的较晚时刻**不算**未来日期。计数对象＝**贡献普通收支的交易**（§3.2.3(b) 的共享谓词），与报表/日历集合一致；特殊 kind 与纯本金交易不计入。N 由同一读事务里的独立 `COUNT(DISTINCT transaction_id)` 给出（§3.4、§3.5）；**N = 0 时不显示该标注**（缺席，而不是「含未来 0 笔」）。`ALL` 的跨度仍按 §3.1.2 含未来，两者不冲突。
5. **自定义输入的校验**：起日晚于止日、或日期不可表示 → 类型化拒绝并保持上一成功载荷；不静默改成相邻区间。

### 3.2 两条时间轴与口径门控

1. **报表与日历按统计时间**：窗口谓词与桶归属一律作用在 `transaction_version.statistics_at` 的数值投影 `statistics_at_epoch_nanos`；**缺投影即 fail-loud**——读前先跑既有**账本级**探针 `statisticsAtProjectionMissingForLedger`（`Ledger.sq:10875`，既有用法 `SqlDelightLedgerCurrentStateReadAdapter.kt:138-142`），**账本内任一**缺投影行（含窗口之外）即返回类型化 `MissingProjection`（§3.8），**不做 TEXT 比较回退**（`D-184`）。
2. **账户下钻按真实资金时间**：窗口、排序与页键作用在 `occurred_at` 的解析值上，沿用既有内联 epoch 解析表达式（`Ledger.sq:12013-12146` 的已交付写法，键集谓词 `:12138-12144`），**两端都按解析值比较、不按文本比较**；行金额取该账户自己的分录 `amount_minor`（本账户真实变动），不取交易合计。**展示与排序同轴**：账户资金流水**主行**的日期取 `occurred_at` 的上海当地日，并按 `D-343` 的已批准局部例外**标明「资金发生日期」**；无障碍标签同样说「资金发生日期」，统计时间只在交易详情页显示或另行明示。排序键与显示轴必须同为 `occurred_at`，不得出现「按资金发生时间排序、却显示统计日期」的混用。既有呈现函数 `P503AccountFlowScreen.accountFlowRowHeadlineText`（`:236-239`，现取 `statisticsAt`）与 `accountFlowRowContentDescription`（`:255-259`，现说「统计时间」）在实施批必须随之改成同一轴与同一措辞。**单位与文案**：技术口径的单位是**分录**（页与计数按当前有效 posting 计），界面按业务语言显示「N 条账户变动/记录」；不改计数口径、不做交易去重或合并、不新增会计效果。
3. **普通收支门控唯一，SQL 与 application 的边界显式**：唯一判定点是 `OrdinaryFlowClassification.ORDINARY_KINDS`（既有源码对象，`MonthlyBuckets.kt:367-379`）加账户 kind 门控（EXPENSE 账户分录按账本符号记普通支出、INCOME 账户分录取反记普通收入、ASSET/LIABILITY/EQUITY 恒零）。据此冻结两条：**（a）有效 kind** 一律取 `COALESCE(tx.canonical_kind, tx.kind)`（裸 `tx.kind` 会把 LEND/COLLECT/REFUND_RECEIPT 读成 `EXPENSE`，`Ledger.sq:9221` 的既有投影同形）；**（b）贡献集合**——一笔交易「贡献普通收支」⇔ 有效 kind ∈ O **且** 存在至少一条权威贡献分录：该交易当前版本的一条分录，其账户在 `catalog_account`（`Ledger.sq:340-353`）上的 `kind` ∈ (EXPENSE, INCOME)，且 `posting.amount_minor != 0`（`posting` 列定义 `Ledger.sq:63-77`）。**仅 kind ∈ O 不是贡献集合**：纯本金 `ACCOUNT_TRANSFER`、`LEND` 只有金融账户分录，对普通收支的贡献为零，既不得进计数，也不得进「无分类条件」的明细页。
4. **贡献谓词的取数与边界**：账户 kind 由权威账户表 join 给出（`JOIN catalog_account ON account.ledger_id = p.ledger_id AND account.account_id = p.account_id`，join 形状先例 `Ledger.sq:9077-9078`），账户 kind 以**绑定参数**筛选；**不得**借用「仅金融账户」的成员过滤（`owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`，`Ledger.sq:9080-9082`）去找 EXPENSE/INCOME——那组过滤只属于账户资金流水页（§3.2.8）。符号折叠只在 application 层做（SQL 返回 `amount_minor` 的账本符号原值）。该谓词是**共享谓词**：期间金额与笔数、逐币种笔数、未来笔数、日历逐日、以及分类条件为空的明细页与计数全部复用同一段（附录 A 的 `ordinary_contribution`），任一读面不得自行拼一个近义谓词。
5. **目录一致性由独立探针承担，且不是布尔计数**：贡献谓词内的 `EXISTS ... JOIN catalog_account` 是**内连接**——账户缺行、跨账本或币种不符的分录在过滤阶段就被丢掉；而聚合与明细读**不跑** `rows` 上的目录纯函数（`QueryMonthlyActivity.kt:150-158` 的 `isCatalogConsistent` 只被 `QueryMonthlyActivity` 自己调用），所以那层既看不见坏分录、也不是本批任何读面的兜底。正确做法是独立只读探针 `p806PostingCatalogConsistencyProbe`（§3.5、附录 A）：同一短事务内、在贡献与分类过滤**之前**，按「账本 + 当前有效版本 + 请求时间轴窗口」取范围内的交易并检查其**全部分录**（不按金额、kind 或分类过滤），scoped `LEFT JOIN catalog_account` 后给出三个**条件计数**，任一非零即 `InvalidState`：`unresolved_account`（本账本目录无此账户）、`cross_ledger_account`（该 `account_id` 只存在于其他账本的目录行，故为前者子集）、`currency_mismatch`（账户存在但 `currency_code` 或 `currency_precision` 与分录不等）。三个条件与既有行级合同一一对应：账户存在、同账本、币种相等（`QueryMonthlyActivity.kt:150-158` 的 `accountsById[posting.accountId]` / `account.ledgerId != ledgerId` / `account.currency != posting.amount.currency`，其中 `CurrencyUnit` 相等含 precision）；轴级检查 `isAxisCatalogConsistent`（`:148`）属于既有月读合同、覆盖面不同，本批原样保留、不作为探针的替代。坏分录**不得**被静默排除。
6. **当前余额不是期间末余额**（§1.3 第 1 条，`PRODUCT_REQUIREMENTS.md:71` 同向）：账户面的余额大字沿用资产页口径的当前余额，期间/账户范围内不推导期末余额、不推出利润曲线（§1.1 第 2 条）。
7. **多币种**：全部金额逐币种分列，任何合计、结余、日均都**只在同币种内**计算，不折算、不合并（`ACCOUNTING_RULES.md:23`、`ARCHITECTURE.md:73`）；报表轴取账本活动币种轴（`loadLedgerActivityCurrencies`，`Ledger.sq:9364`、`LedgerCurrentStateReadPort.kt:153-170`），使空期间也显式呈现零行而不是消失。
8. **账户页成员边界（已批准边界）**：账户下钻的成员＝「本账本 + 当前版本 + `transaction_effective_state` 有效 + 该账户满足 `owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`（`Ledger.sq:340-353`、`:9080-9082` 的同一复合标记，与资产页一致；其最新口径见 `D-309` 第 1 条所指规格 `docs/specs/2026-11-11-p8-01b-widened-reconciliation-design.md` §2.1）+ `occurred_at` 窗口」。**只删**队列专用的 `rg03_transfer_posting_semantic` 资格 join 与队列状态过滤，**不收缩**普通资金分录；对账状态若展示，只作独立的只读投影，不参与页集合与计数。费用/收入内部过账账户不因此新增入口（`ACCOUNTING_RULES.md:261`）。

### 3.3 日均

1. 定义（逐币种，`Long` minor units）：`日均 = 四舍五入( 普通净支出 ÷ 期间自然日数 )`，被除数是 §3.2 冻结门控后的净支出（含退款负号），除数＝窗口内**自然日数**（含首尾；`WEEK`=7、`MONTH`=当月天数、`YEAR`=365/366、`CUSTOM`＝止日−起日+1、`ALL`＝最晚有效统计日−最早有效统计日+1）。**进行中期间不缩短分母**（§1.2 第 3 条）。
2. 舍入（技术解释）：目标是 nearest、恰为半个最小单位时远离零；实现只能走精确整数。取**向零**的整数商 `q` 与同一除法的有符号余数 `r = n − q·d`（因此 `sign(r) = sign(n)` 或 `r = 0`，`|r| < d`），令 `a = |r|`：
   - `a < d − a`（严格小于半单位）→ 结果 `q`；
   - `a ≥ d − a`（恰半或超过半单位）→ 结果 `q + sign(n)`。
   该写法**不取被除数绝对值**（`abs(n)` 在 `n = Long.MIN_VALUE` 上溢出），**不比较 `2a` 与 `d`**（`2a` 同族溢出；比较 `a` 与 `d − a` 即可，二者都 `< d`），**不重复施加符号**（符号只经 `sign(n)` 进入一次）。`d > 0` 为契约前置（自然日数恒正）。用例：`5/2 → 3`、`−5/2 → −3`、`1/2 → 1`、`−1/2 → −1`、`4/3 → 1`、`−4/3 → −1`、`5/3 → 2`、`−5/3 → −2`、`0 → 0`。**不得**把「有符号四舍五入」实现成一般性远离零取整，也不得用浮点或十进制近似参与。金额始终是整数 minor units，展示用该币种精度，全链无浮点。
3. 零值与可解释性：日均结果为 0 时显示不带负号（符号只在非零时呈现）；原期间净支出仍以带符号数值呈现，用户可据它解释「净支出为负、日均显示 0」的成因，不得用日均掩盖净支出符号。
4. 进行中期间的分母是**该周期完整自然日数**：如 11 月按 30 日算，不按当月已过 16 日缩短（§1.2 第 3 条已裁，见 V-16）。
5. 不适用：`ALL` 在空账本上（无跨度）、或期间日数为 0（不可表示区间）→ 呈现「日均不适用」，**不显示 0，也不显示空串**。
6. 净支出为负（退款多于支出）时日均保留负号，不取绝对值伪装（沿用 `R-Q07-3` 的有符号呈现纪律）。

### 3.4 读模型与有界性（每维度给出数量界）

新增读面分三组，全部只读、全部按账本限定。表里的「数量界」是**返回行数/语句数的参数界**（随目录账户数、币种数、期间自然日数增长，与交易数无关），**不是执行性能证明**；扫描代价单列在表后的扫描成本段。

| 维度 | 读法（拟议命名查询见 Appendix A） | 数量界 |
| --- | --- | --- |
| 目录版本 | 读事务内取目录版本一次（沿 `MonthlyContributionReadPort` 的 `expectedCatalogVersion` 形状） | 1 行 |
| 缺投影探针（账本级） | 既有 `statisticsAtProjectionMissingForLedger`（`Ledger.sq:10875`） | 1 行 |
| **目录一致性探针** | 新 `p806PostingCatalogConsistencyProbe`：范围内的**交易**按「账本 + 当前版本 join + 有效状态 + 请求时间轴窗口」取（superseded 行不计），检查其**全部分录**，scoped `LEFT JOIN catalog_account` 后同一条 SELECT 返回三个条件计数 `unresolved_account` / `cross_ledger_account` / `currency_mismatch`；在贡献与分类过滤之前执行 | **1 行 3 列**（三项均为条件 `COUNT`，干净窗口含空窗口全 0；不用布尔 `COUNT`、不用裸 `SUM`）；`ALL` 与「全部时间」用解析后的全范围；日历用一次月范围探针，不逐桶重复；`C0` 不因此增加 |
| 期间金额（普通收入/净支出/结余） | 一条整数聚合：`SUM(amount_minor)` 按 `(account_id, currency)` 分组；**共享贡献谓词**（§3.2.3(b) + §3.2.4：kind ∈ O 的绑定参数 + `EXISTS` 一条 EXPENSE/INCOME 且 `amount_minor != 0` 的当前版本分录，账户 kind 经 `catalog_account` join 后按参数筛），窗口谓词下推数值统计投影 | 行数 ≤ 期间内出现的 (账户 × 币种) 组合数（随目录账户数与币种数增长，不是固定常数）；禁用 `TOTAL()`，整数溢出由 SQLite 报错并映射为类型化失败（`Ledger.sq:12154-12156` 的既有纪律） |
| 期间交易笔数（全局） | **独立** `COUNT(DISTINCT version.transaction_id)`，同窗口 + **同一贡献谓词** + 有效谓词 | 1 行；一笔交易跨多条分录仍计 1（`distinct` 绑交易 id） |
| 期间交易笔数（逐币种） | **独立** `COUNT(DISTINCT version.transaction_id)`，同贡献谓词，按 `currency` 分组 | ≤ 币种数行；同一交易若跨币种，会在每个币种各计一次（该币种视角的正确值），**不得**把逐币种笔数相加当全局笔数 |
| 期间未来笔数 | 同全局笔数形状 + 同贡献谓词，追加 `statistics_at_epoch_nanos >= :next_local_midnight_nanos` 下界（§3.1.4） | 1 行；特殊 kind 与纯本金不计 |
| 分类排行（一级/二级） | 期间金额聚合结果在 application 层按目录折叠（`catalog_category.posting_account_id` + `parent_id`） | ≤ 目录内一级+二级分类数 × 币种数 |
| 日历逐日金额 | **当地日桶**：由时区库生成该自然月 ≤31 个当地日（≤32 个零点边界），对每桶用同一参数化聚合 `(start,end)` 读一次，谓词含共享贡献谓词 | 每桶行数 ≤ (账户 × 币种) 组合数；**`D` 次语句，`2D ≤ 62` 只是逐日子查询上界**（整请求为 `C0 + 2D`，见 §3.5） |
| 日历逐日笔数 | 同桶边界 + 同贡献谓词，**每日一个全局** `COUNT(DISTINCT transaction_id)`（不分 kind、不分币种） | 每桶 **1 行**；同属 `2D` 那一半 |
| 明细页首屏 | 键集页，页 50，页键 `(statistics_at_epoch_nanos DESC, transaction_id DESC)`（沿 `ledgerFlowPage` 形状 `Ledger.sq:11659-11718`，数值列不是 TEXT）；谓词＝窗口 + 有效 + 共享贡献谓词 +（可选）分类子树 EXISTS，全部在 `LIMIT` 之前 | ≤ 50 笔交易（`LIMIT` 绑在交易上，分录不跨页） |
| 明细页计数 | 独立 `COUNT(DISTINCT transaction_id)`，**谓词与页逐字同形**（含共享贡献谓词，都在 `LIMIT` 之前） | 1 行 |
| 账户下钻页 | 键集页，页 100，页键 `(occurred_at 解析值 DESC, posting_id ASC)`（沿 `Ledger.sq:12013-12146`）；成员边界见 §3.2.8；单位是**分录**，界面文案「N 条账户变动/记录」 | ≤ 100 条分录 |
| 账户下钻计数 | 独立 `count(*)`，谓词与页同形；单位同上 | 1 行 |
| `occurred_at` 解析探针（账户面） | 单行 SELECT-only `p806OccurredAtParseFailureProbe`：该账户内**不可解析**的 `occurred_at` 行数（按解析器 nullable 结果判，不按数值 0），在窗口过滤**之前**执行 | 1 行；非零 → `InvalidState`；合法 epoch 0 放行 |
| 全部跨度 | 两条 MIN/MAX 数值投影（MIN 已存在 `Ledger.sq:9343`；MAX 新增），读前跑账本级缺投影探针 | 1 行 |
| 币种轴 | 既有 `ledgerActivityCurrencies`（`Ledger.sq:9364`），在报表/日历的**同一事务内**读取，供零贡献日按轴逐币种出显式零行 | ≤ 币种数 |

**有界性纪律**：本批任何「有界」声明都必须落在上表的某一格，且必须能指出 `LIMIT`、`GROUP BY`、固定语句数或单行投影。**「限定在一个月」不构成行数界**（§2 事实 2）：日历与期间金额禁止用全量月窗 List，明细页禁止先截断再在内存过滤——贡献集合谓词必须在 `LIMIT` 之内，页与计数共用同一谓词块（V-4 逐条断言）。计数只能来自 `COUNT(DISTINCT transaction_id)`：`(kind, account, currency)` 组的行数/组数不能相加成笔数，`SUM` 分录组计数会把多分录交易重复计入；且**计数只数贡献集合**：只有 kind ∈ O 不够，纯本金 `ACCOUNT_TRANSFER`/`LEND` 必须被贡献谓词挡在计数与无分类明细之外（V-9）。四个前置读都是固定语句、都不随交易数或桶数增长：目录版本与缺投影各 1 行，目录一致性探针 1 行 3 列，`occurred_at` 解析探针 1 行（仅账户面）。

**扫描成本（不伪装成有界）**：账户维度的 `posting(ledger_id, account_id)` 无索引、`occurred_at` 无索引（§2 事实 4、事实 8），账户页每页是一次范围扫描 + `LIMIT`，解析探针同样是一次范围扫描（数量 1 行、代价随账本增长）；分类下钻的子分类谓词走 `catalog_category_by_parent`（有索引，`Ledger.sq:368`）后按 `posting_account_id` 命中 `posting`（该列无索引）；日历是 `D` 次桶读，每次各自范围扫描；**目录一致性探针**要把范围内交易的全部分录与目录做一次 `LEFT JOIN`（无 `(ledger_id, account_id)` 索引可用，同样是一次范围扫描 + 目录探测）。五者共同的性质是：**返回行数有界，扫描代价随账本行数增长**，且与窗口宽度无关。索引属 DDL，另开迁移门（§6-T4）。

### 3.5 每 surface 一个复合 typed 快照（同一读事务）

1. **形态：每 surface 一个复合 typed 操作，不新建 port。** 四个读面（报表 / 日历 / 明细 / 账户）各一个复合方法，加在**既有** `interface LedgerCurrentStateReadPort`（`LedgerCurrentStateReadPort.kt:66`）上；每个方法带 `expectedCatalogVersion: Long` 参数，**默认实现 `throw UnsupportedOperationException`**（与同接口既有默认方法同形，`:120-151`），因此既有全部 doubles 与构造点零改动。实现内部用**一次** `database.transactionWithResult(noEnclosing = true)`（先例 `SqlDelightMonthlyContributionReadAdapter.kt:68-95`），该 surface 的全部 probes、金额、页、计数与轴都在这一次事务里返回。
   - **不新建公开 port**；**不把账户读挂到** `P801AccountReconciliationReadPort`——只借它的 `occurred_at` 内联解析表达式（`Ledger.sq:12013-12146`）与 §3.2.8 的资金账户成员边界，不借 rg03 资格 join、队列 `status` 过滤或 `active_link_count`。
   - **禁止**「一条 query 一个公共方法、各自事务」：跨语句一致性只能来自同一个事务。
   - 方法名与结果类型是**本规格的拟议新增**（不是现有 API；实施批不得把拟议类型说成已存在）：`readReportSnapshot` / `readCalendarSnapshot` / `readDrilldownSnapshot` / `readAccountPostingsSnapshot`，各返回该 surface 的密封结果族（`Success(payload)` / `Failed(typed reason)`，失败族见 §3.8）。adapter 职责因此增长，实施可抽 private helper，但不得造第二个公开 port。
2. **固定语句顺序**：目录版本快照 → 缺投影探针（账本级，§3.2.1）→ 目录一致性探针 →（仅账户面）`occurred_at` 解析有效性探针 → 金额/计数/页/轴/跨度。目录一致性必须在贡献与分类过滤**之前**执行，否则坏账户分录已被内连接丢掉、事后无从发现（§3.2.5）。**账户面跳过缺投影探针**（它不投影 `statistics_at`），与第 8 条的 `C0` 清单同义。
3. **`p806PostingCatalogConsistencyProbe`（SELECT-only，同一条 SELECT 返回 1 行 3 列）**：范围＝「本账本 + **当前版本 join**（`ledger_transaction_current_version`）+ **有效状态**（`transaction_effective_state.is_effective = 1`），两者都必须满足、superseded 旧版本的行不计入 + 请求时间轴窗口」的**交易**，检查这些交易的**全部分录**（不按金额、kind 或分类过滤）；eager 的 `LEFT JOIN catalog_account ON account.ledger_id = p.ledger_id AND account.account_id = p.account_id` 之后给出三个条件计数：
  - `unresolved_account`＝`COUNT(CASE WHEN account.account_id IS NULL THEN 1 END)`——本账本目录无此账户；
  - `cross_ledger_account`＝`COUNT(CASE WHEN account.account_id IS NULL AND EXISTS (SELECT 1 FROM catalog_account AS f WHERE f.account_id = p.account_id AND f.ledger_id <> p.ledger_id) THEN 1 END)`——该账户只存在于其他账本，**必为前者的子集**；
  - `currency_mismatch`＝`COUNT(CASE WHEN account.account_id IS NOT NULL AND (account.currency_code <> p.currency_code OR account.currency_precision <> p.currency_precision) THEN 1 END)`——账户存在但币种 code 或 precision 与分录不等（对齐 `isCatalogConsistent` 的 `account.currency != posting.amount.currency`；两列在 `posting` 与 `catalog_account` 上都是 `NOT NULL`，见 `Ledger.sq:63-77`、`:340-353`，所以该比较不会因 NULL 产生 unknown）。
  **计数语义**：三项都用**条件 `COUNT`**（条件真产生非 NULL 的 1，假或未知产生 NULL 因而不计数），**不用** `COUNT(<布尔表达式>)`——SQL 里假条件也是非 NULL 的 0，会把干净窗口计成全部行数；也**不用**裸 `SUM`——空窗口时 `SUM` 返回 NULL 而不是 0。因此干净窗口（含空窗口）三列都必须是 0。任一非零 → `InvalidState`（目录引用类，§3.8）。窗口按读面时间轴取：报表/日历/明细用统计数值投影半开窗，账户页用 `occurred_at` 解析值半开窗；**`ALL` 与账户页「全部时间」用解析后的全范围**（有跨度取最早至最晚有效日，无跨度取整账本），不额外缩小窗口。
4. **日历的币种轴与共享轴校验**：日历快照在**同一事务内**读 `ledgerActivityCurrencies`（`Ledger.sq:9364`，账本活动币种轴）并跑轴一致性校验；因此**零贡献日仍按轴逐币种各出显式零行**（CNY + USD 两轴 → 两行零），不得借用跨请求的陈旧轴。轴一致性判定抽成**共享纯函数** `isAxisCatalogConsistent`（当前是 `QueryMonthlyActivity.kt:148` 的 private 成员，本批要求提取到共享位置），月读与新报表/日历同源同规则，并以防分叉测试钉住两处一致；widget 月度快照不在本批登记范围、现状不动；纯函数不是 SQL 语句，不增加 `C0`。
5. **`occurred_at` 解析有效性**：账户面在**窗口过滤之前**跑单行 SELECT-only 探针 `p806OccurredAtParseFailureProbe`，统计该账户（账本 + 当前版本 + 有效）内 `occurred_at` **不可解析**的行数。判据是解析器的 **nullable 结果**（NULL 即坏），**不是**数值是否为 0——合法的 `1970-01-01T00:00:00Z`（epoch 0）解析成功、必须放行。任一非零 → `OccurredAtUnparseable`（§3.8）。理由：既有内联解析把不可解析文本 `COALESCE` 成 epoch 哨兵 0（`Ledger.sq:11994-11997`），这类行会被任何时间窗过滤**静默排除**，所以探针必须在窗过滤之前、且不能按值判断。数量界 1 行；扫描成本入残余；该探针计入账户 surface 的 `C0`。适配器侧仍以 `Instant.parse` 严格解析（`SqlDelightP801AccountReconciliationReadAdapter.kt:123`），探针只负责把「坏行会不会被窗口吞掉」变成显式失败。
6. **日历逐桶**：同一事务内 `D ≤ 31` 个日桶，桶边界由时区库给（§3.10.2）；探针与轴只读一次，**不逐桶重复**。
7. **整数 `SUM` 溢出**：SQL `sum()` 溢出由 SQLite 报错呈现（`Ledger.sq:12154-12156` 的既有纪律），必须映射为 `InvalidState`（**不是**可重试的 `Unavailable`），与 app 层 checked 运算同族（§3.8）。
8. **语句数口径**：请求语句数 = `C0 + 2D`，`D ≤ 31` 为日桶数，`2D` 是每桶「金额 + 笔数」两读（故 `2D ≤ 62` **只表示逐日子查询的上界，不是整个请求的上界**）；`C0` 是该 surface 的**全部固定语句**：报表 `C0 = 12`、日历 `C0 = 4`（+ `2D`）、明细 `C0 = 5`、账户 `C0 = 5`（明细构成见下表）。**默认只读 `selectCatalogVersion` 并使用调用方已装载的目录**，不隐含 `CatalogAuthorityReader.load`/`loadAuthority` 的四条 SELECT；若某个 surface 选择读取额外目录，必须重算 `C0` 并在实施批登记新增固定语句，不得沿用上列数字。
   
   | surface | `C0` 构成 |
   | --- | --- |
   | 报表 | 目录版本 1 + 缺投影 1 + 目录一致性 1 + 币种轴 1 + 跨度 MIN/MAX 2 + 期间金额 1 + 全局笔数 1 + 逐币种笔数 1 + 未来笔数 1 + 首屏 1 + 计数 1 |
   | 日历 | 目录版本 1 + 缺投影 1 + 目录一致性 1 + 币种轴 1 |
   | 明细 | 目录版本 1 + 缺投影 1 + 目录一致性 1 + 首屏 1 + 计数 1 |
   | 账户 | 目录版本 1 + 目录一致性 1 + 解析探针 1 + 页 1 + 计数 1（不跑缺投影探针） |
   
   实现批按真实语句清单逐项核对 `C0`，不得把 `C0` 折进 `2D`，也不得只报 `2D`。
9. **边界**：只是同一次请求内的短快照；不跨页、不跨屏、不跨请求持久化，不持有数据库或长事务（`docs/specs/2026-11-11-p8-02-search-filter-design.md:62` 的取舍同向）。翻下一页是一次新的短快照，页与页之间允许出现新提交（§3.7 负责让晚到页与旧载荷失效）。

### 3.6 平台边界

- `ledger-data`：只新增 SELECT-only 命名查询；**不承载普通收支口径语义**——两个参数集合（交易日 kind 与贡献账户 kind）只作为绑定参数出现（`IN (:kind1…:kindN)`、`IN (:contribKind1, :contribKind2)`，集合本体仍只在 `OrdinaryFlowClassification` 与 §3.2.3 的账户门控处定义；集合基数变动必须同时改查询，并由测试断言「参数集合 == 冻结集合」与基数，禁两处静默分叉），账户 kind 只经 `catalog_account` join 取得（不带金融账户成员过滤），不做符号折叠；日桶与未来阈值由 application 算好后传入，SQL 不认识时区；不新增列/表/索引/触发器。
- `ledger-application`：拥有期间窗口换算、当地日桶边界（时区库唯一落点）、普通收支折叠（复用 `MonthlyBuckets` 与 `OrdinaryFlowClassification`）、日均、分类树聚合、未来标注、共享轴校验（`isAxisCatalogConsistent` 提取）与全部类型化结果族；四个**复合快照方法**加在既有 `LedgerCurrentStateReadPort` 上、默认抛 `UnsupportedOperationException`（§3.5.1，不新建 port）；端口默认实现一律 fail-loud 而不是返回空（沿 `LedgerCurrentStateReadPort.kt:120-134` 先例）。
- `app-ui`：只消费 application 层类型（`ARCHITECTURE.md:55`）；本批不引入 Compose UI 测试框架；无障碍文本由纯函数生成以便单测（沿 `P503LedgerView`/`P8HomeFlow` 既有纯函数惯例）。
- 两端组合根：Android 与 Desktop 共用同一 facade 接线；是否渲染报表区由组合根已有的 `ledger.surfaces` 能力探针决定（沿 `ledger.surfaces.search` 形制，`P503App.kt:5076`），未接线即不渲染死入口。

### 3.7 状态与回程

1. **新增只读状态面**（拟议）：报表区不是独立 state，而是 `OverviewEmpty` 上的可选载荷（沿 `monthlyActivity` 形态，`P503AppState.kt:135-151`）；日历与下钻列表各为一个二级全屏状态（沿 `AccountFlow`/`Search` 形态，`:477-515`、`:1037`）。
2. **两个新事件族**：`SelectReportPeriod(period)` 与 `ReportPeriodResult(payload)`；下钻打开/关闭为 `OpenReportDrilldown(target)`/`CloseReportDrilldown`，详情打开为既有 `SelectTransaction` 的扩展。未列出的 `(state, event)` 继续抛 `IllegalStateException`（`P503Reducer.kt:5196` 的唯一兜底 helper 不变），只为本批新增事件定义语义（沿 P7-03 §6.2 纪律）。
3. **详情回程扩为类型化返回目标**：`TransactionDetail` 的返回目标从「`overview` + `originTab`」扩为「`overview` + 类型化 `returnTo`」，取值域＝首页 / 搜索结果面 / 账户下钻面 / 报表分类明细 / 日历某日（沿 `ReconciliationBackTarget` 先例 `P503AppState.kt:1091-1102`）。闭合语义：只读返回必须回到同一 `returnTo`、同一已加载页集合与滚动位（`originListScroll` 的一次性消费机制照用，`P503AppState.kt:193-207`）。
4. **确定写入后的处置（§1.3 第 5 条）**：本应用内出现确定成功（既有 `Created`/`NoChange`/`Recovered`）→ ①保留筛选与期间条件；②作废已加深页（不保留过期行、不沿用旧计数）；③权威刷新到首屏并在报表面上方显示「账目已更新，已回到第一页」提示；④晚到的旧页/旧计数按落地守卫丢弃（见下条），不回写。外部进程的变更不做检测、不轮询。
5. **落地守卫与请求身份**：HOME 的 `flowGeneration`（`P503App.kt:1010-1072`）只绑定首页流水，**不是**报表的请求身份。报表新载荷、明细页与计数各自携带独立请求身份，至少覆盖：单次请求 token（单调递增）、期间定义（周期 + 起止瞬时）、**币种轴与逐币种载荷标识**（轴本身 + 各币种载荷的标识，不是「单一币种」）、**下钻条件**（分类子树或某日，条件一旦变化必须分配新 token）、账户来源（账户下钻的 accountId）、目录版本（`catalogVersion`）与数据库代际（`Generation`，`LedgerRuntimeOwner.kt:47-48`；代际校验先例 `P503App.kt:421`）。落地规则：任一项与当前状态不符即整块丢弃（不部分应用、不覆盖新周期/新币种轴/新账户来源的首屏），只有完全匹配才落到状态；同一 surface 的重复请求由 token 吸收（单飞）。
6. **不重新请求的边界**：`CloseReportDrilldown`/只读返回不触发新读；报表重取触发集＝初始加载、期间变化、确定写入后的权威刷新、（可选）恢复换库后的重建。此集合与 `P703SPEC-04` 的月卡触发集分开登记，避免两处耦合。
7. **载荷状态与「不伪零」**：每个 surface 的载荷用**显式状态**表达，不用一个可空值兼表多义：
   - `Unwired`（组合根未接线）→ 不渲染该区域/入口，不显示 0；
   - `NotLoaded`（尚未请求）→ 「尚未加载」文案；
   - `Loading`（单飞中）→ 保留上一成功载荷，并**标注其来源上下文**（期间、币种轴、as-of 时点），旧载荷不得冒充当前；
   - `Failed(typed)` → 类型化失败面 + 重试，上一成功载荷留在旁边（`R-Q06-4`）；
   - `Ready(empty)` / `Ready(non-empty)` → 空成功与有数据分开；只有空成功才允许渲染零行/零金额。
   实现上以 §3.5 的密封结果族（`Success(payload)` / `Failed(reason)`）加宿主侧一个「最近成功载荷」槽表达；**禁止**用 `null` 同时表示未加载、未接线与失败，也禁止在未取得载荷时渲染 0 或空图表。

### 3.8 失败与金额

| 结果 | 触发 | 呈现 |
| --- | --- | --- |
| `Unavailable` | 读端口异常、时钟不可用、租约/装配失败 | 类型化失败面 + 重试；**保留上一成功载荷**，不以零或空渲染（`R-Q06-4`） |
| `InvalidState`（目录版本失配 `CatalogVersionMismatch`） | 读事务内目录版本 ≠ 调用方 `expectedCatalogVersion` | 类型化失败；绝不静默零替代（`MonthlyContributionReadPort.kt:30-41`） |
| `InvalidState`（目录引用：缺账户 / 跨账本 / 币种失配） | `p806PostingCatalogConsistencyProbe` 的三项条件计数 `unresolved_account` / `cross_ledger_account` / `currency_mismatch` 任一非零（§3.2.5、§3.5）；**无论该交易是否另有有效贡献分录**都失败 | 类型化失败；坏分录不得被静默排除；三项与既有行级合同 `isCatalogConsistent`（`QueryMonthlyActivity.kt:150-158`）逐一对应，聚合与明细读**不跑**该纯函数，故它不构成兜底 |
| `InvalidState`（轴币种无目录账户） | 共享轴校验 `isAxisCatalogConsistent`（自 `QueryMonthlyActivity.kt:148` 提取）在账本活动币种轴上发现某币种没有任何本账本目录账户（可来自**窗口之外**的有效分录） | 类型化失败；窗口内探针看不见它，因此轴校验必须独立且与月读同源 |
| `InvalidState`（`occurred_at` 不可解析） | `p806OccurredAtParseFailureProbe` 在窗口过滤前发现该账户有不可解析的 `occurred_at`（§3.5.5）；合法 epoch 0 不算坏行 | 类型化失败；不得让坏行被时间窗静默排除 |
| `InvalidState`（聚合/符号/`sum()` 溢出） | app 层 checked 加法/减法/取反溢出，或 SQL `sum()` 溢出（`Ledger.sq:12154-12156`） | 类型化失败（**不是**可重试的 `Unavailable`），零静默兜底 |
| `MissingProjection` | **账本内任一**版本行缺 `statistics_at_epoch_nanos`（账本级探针 `statisticsAtProjectionMissingForLedger` 非零，`Ledger.sq:10875`）；缺行在窗口之外**同样**失败 | 类型化失败；**不做 TEXT 比较回退**（`D-184`）；不另起窗口级探针、不因窗口无关而放行 |
| `InvalidWindow` | 自定义起晚于止、跨度不可表示、日桶边界不可生成 | 类型化拒绝并保留上一成功载荷（§3.1.5） |
| `NotApplicable` | 空账本无跨度、期间日数为 0 | 显式「不适用」文案，区别于零也区别于失败 |
| 不新增写失败码 | 本批零写入 | — |

金额纪律：全链 `Long` minor units 与精确十进制，禁浮点；聚合用 checked 加法/减法/取反，溢出即 `InvalidState`；每处合计只在本币种内；显示走 `formatMinorUnits` 并保留符号。

### 3.9 分类入口

1. 一级行：显示该一级合计（＝其全部二级合计之和）并可展开二级；一级行另带「全部明细」入口，进入该一级子树（含全部二级）的明细页。
2. 二级行：可展开/进入自身明细页。
3. 明细页谓词＝窗口（统计数值投影）+ 有效交易（有效谓词唯一落点）+ **共享贡献谓词**（§3.2.3(b)/§3.2.4：kind ∈ O 的绑定参数 + `EXISTS` 一条 `amount_minor != 0` 且账户 kind ∈ (EXPENSE, INCOME) 的当前版本分录）+（可选）「存在一条 `amount_minor != 0` 且落在该分类子树分录账户上的分录」。**分类条件为空时只剩共享贡献谓词**，此时纯本金 `ACCOUNT_TRANSFER`/`LEND` 必须被排除，不得因为「无分类」而放进页面。子树由 `catalog_category` 的 `parent_id`/`posting_account_id` 解析（`Ledger.sq:355-369`），**不接受调用方传分类名**；分类条件作为同一谓词族的可选附加 `EXISTS`，其余部分不变。该谓词**必须在 `LIMIT` 之前生效**，并与计数查询共用同一谓词块：页与计数逐字同形，禁止「先按窗口截断再在 application 过滤」。
4. 停用分类照常计入历史并显示当前名（`ACCOUNTING_RULES.md:259-263`，`R-Q07-2`）；无分类映射的普通分录仍以显式「无分类」行呈现，不发明分类（`P703SPEC-09` 既有纪律）。

### 3.10 收支日历

1. 数据面：当月逐日的普通收入/普通净支出，**逐币种**（金额按 §3.4 的日桶金额聚合）；**日格笔数是每天一个全局 `COUNT(DISTINCT transaction_id)`**（该日贡献交易数，不分币种），不冒充「逐币种笔数」，也不因此新增一条每日查询——金额与笔数仍各一条，`2D` 不变。集合＝**贡献普通收支的交易**，与报表同一共享谓词（§3.2.3(b)/§3.2.4：含退款/手续费/利息；不含特殊 kind、不含纯本金、储值、余额调整、预付腿）。
2. **日桶边界**：由时区库在 `Asia/Shanghai` 下逐日生成该自然月的当地零点边界（`date.atStartOfDayIn(REPORT_TIME_ZONE)`，先例 `MonthlyBuckets.kt:99-102`），得到 ≤31 个日桶、≤32 个零点界；对每桶用同一参数化聚合 `(startInclusive, endExclusive)` 读一次，全部桶读共用 §3.5 的同一短事务。**禁止**用固定 `+08:00` 偏移、固定 86400 秒累加或 SQL 侧自算日序替代；也**不**把产品日期域缩到「当代」。历史夏令时由 IANA 时区规则按当地零点处理（[IANA time-zones](https://www.iana.org/time-zones)；本仓不复制时区数据，只依赖平台时区实现），因此该形状对历史时段同样正确。
3. **初始月与游标**：日历打开时的初始月＝首页 `selectedMonth`；该值为 `null`（首页尚未选定）时用上海时区的当月。日历的月切换只改**日历自身**的游标状态，不写回首页月游标，也不改变 `P703SPEC-10` 的 `SelectMonth` 可选域与 `AnalysisMonthShift` 语义（§3.1.3）。报表区自身的期间游标仍按 §3.1.3 独立维护，本条只约束日历的初始月与内部游标。
4. 交互：点某日 → 该日明细页（同一明细页形态，窗口＝该日当地半开窗），可再进完整交易详情。
5. 视觉形制（格子密度、颜色深浅、金额与笔数的取舍）**本规格不批准**：实现批必须先出可读样本与设备证据再定，本期只冻结数据契约、空态（**零贡献日仍渲染格子并显式标注零**，不隐藏日期、不等于失败）、无障碍（每格朗读「日期 + 精确金额 + 笔数」）与「空期间/失败/不适用」三态区分。

## 4. 验收向量

全部匿名合成数据；每条给出可测断言与证据归属。`JVM` = 真库 JVM 测试（`ledger-data` / `ledger-application`），`APP` = `app-ui` commonTest 纯函数/reducer 测试，`DEV` = instrumented 或 font-scale 设备工件，`CI` = 四个必过检查。

| # | 向量 | 判据 | 归属 |
| --- | --- | --- | --- |
| V-1 | 期间窗口映射与跨度 | 周一起周、月、年、全部、自定义各给出上海半开窗；跨年/闰年/月末/时区非上海设备时钟各一组；自定义起止日均含；`ALL` 的跨度含未来统计日期（首尾含）；空账本无期间且日均 `NotApplicable`；自定义起晚于止 / 不可表示 → `InvalidWindow` | JVM+APP |
| V-2 | 静止夹具穷举 oracle | 静止匿名**混合夹具**（>50 笔贡献交易 + 特殊 kind + 纯本金 + 多分录交易）：①纯函数独立算出贡献交易 ID 集合（原分类器 + 账户 kind + 非零）；②SQL 明细页**遍历全部键集页至 `cursor = null`**；③断言「全部页并集 == 纯函数集合 == 全局 count」，无漏无重（页间不重叠、并集不缺失）；④**首屏 50 只与穷举排序前 50 比较**，不得与全部数据等同；⑤同夹具上报表「本月」逐币种收入/净支出/结余与冻结月卡（`QueryMonthlyActivity`）逐值相等，跨月退款只落在到账月 | JVM |
| V-3 | 部分期间独立期望 | 周/自定义部分月用相同半开窗独立算出期望值，**不得**由整月卡相加得出；断言与独立期望相等 | JVM |
| V-4 | 50k 集中单月有界性 | 单月 50k 交易的夹具上：期间金额聚合行数 ≤ (账户×币种) 组合数；日历逐日子查询 `2D ≤ 62` 且每桶行数有界；明细首屏恰 50 笔且游标可继续；计数为一条 `COUNT(DISTINCT)`；**贡献谓词在 `LIMIT` 之前**（构造「前 50 条按页键全是纯本金转账」的夹具，首屏仍须 50 笔贡献交易）；**运行期无「窗口全量行 List」、无先截断后过滤**（语句数/行数断言，非计时）；整请求语句数按 `C0 + 2D` 计数，不把 `2D ≤ 62` 当整请求上界 | JVM（CI `ledger-data` 分片） |
| V-5 | 多分录一交易与计数口径 | 一笔交易含多条普通分录（含拆分类、混合支付手续费腿）在全局笔数计 1、在其所属每个分类各出现 1 次、**不得**被 SUM 成分录数；逐币种笔数与全局笔数各自独立断言；退款多于支出时净支出与日均保留负号；无分类映射分录进显式「无分类」行且 Σ分类=月卡；两币种分列无合并行、无折算 | JVM+APP |
| V-6 | 首屏、深页与载荷状态 | 只读进入/返回：条件、已加载页、滚动位全保留；确定写入后：条件保留、旧深页与旧计数失效、首屏权威刷新且提示出现、晚到页被丢弃；**载荷状态六态可分辨**：未接线不渲染、未加载显示「尚未加载」、加载中显示上一成功载荷并标注其期间/币种轴/as-of、失败显示类型化失败且旧载荷在旁、`Ready(empty)` 与 `Ready(non-empty)` 分开——任一态都不得渲染成 0 或空图表的「成功」 | APP（reducer）+JVM |
| V-7 | 落地守卫、请求身份与代际 | 恢复切换/换代后上一代载荷丢弃；期间切换、**币种轴**切换、**分类/某日等条件变化**、账户来源切换、目录版本推进、单次请求 token 被更新请求取代时，晚到的 page/count 一律整块丢弃，不落到新周期/新币种轴/新账户来源；**负例**：条件变化后旧 token 的 page/count 到达必须被丢弃（按「条件已变」与「token 已旧」两路各测一次），且条件变化本身必须分配新 token | APP+JVM |
| V-8 | 账户真实资金时间轴与展示同轴 | 同一账户的行按 `occurred_at` 解析值排序（含 `+08:00` 与 `Z` 混合文本对照）；金额＝该账户分录金额；与统计时间轴对照证明两轴在被修正 `statistics_at` 后各自正确；**展示同轴**：主行日期 == `occurred_at` 的上海当地日、标签写「**资金发生日期**」，统计时间只在详情或另行明示处出现，不得「按资金发生时间排序却显示统计日期」；**单位与文案**：界面显示「N 条账户变动/记录」，技术断言是该账户当前有效 posting 条数（不叫「N 笔交易」、不做交易去重或合并）；成员边界只含 `owned_by_user=1 / real_account=1 / ASSET·LIABILITY` 账户且不受 rg03 资格或对账状态影响；余额大字＝资产页当前余额 | JVM+APP |
| V-9 | 贡献集合反例与日格 | **全期**（不经分类筛选）：纯本金转账、纯本金 `LEND`、余额调整、储值充值/消费各一笔 → 全部不进期间金额、不进全局/逐币种笔数、不进「无分类」明细页与计数；退款、手续费、利息各一笔 → 均计入；**日格笔数是该日全局 `COUNT(DISTINCT transaction_id)`**（两币种各一笔同日时仍计 2，不是按币种拆成两条计数）；点日明细与当日笔数一致；**零贡献日仍出格子并标零**（不隐藏、不显示为失败）；未来统计日期的贡献也出现在其日格 | JVM+APP |
| V-10 | 分类下钻 | 一级＝Σ二级；一级「全部明细」含子分类交易；二级下钻只含自身；停用分类历史可见且显示当前名；分类子树条件与共享贡献谓词叠加时不重复计入同一交易 | JVM+APP |
| V-11 | 失败不伪装与目录/轴/解析一致性 | **正向两路**：①干净**非空**窗口 → 目录探针三列全 0 且报表/明细正常出数；②**空窗口** → 三列全 0（不是 `NULL`、不是失败），报表出零行而非报错。**失败路**：③读异常/时钟不可用 → `Unavailable` 且保留上一成功载荷；④目录版本失配 → `CatalogVersionMismatch`；⑤**目录引用四类坏例**——缺账户、跨账本、`currency_code` 失配、`currency_precision` 失配——各使对应条件计数非零并判 `InvalidState`，且**每类都测「有另一条有效贡献分录」与「没有」两路**（两路都不许静默排除；跨账本同时满足 `cross_ledger ⊆ unresolved`）；⑥**superseded 旧版本**上的坏账户/坏币种**不**计入（current-version join 与有效状态都必须满足）；⑦**轴**：窗口之外的有效分录带来一个本账本目录里没有账户的币种 → 共享 `isAxisCatalogConsistent` 判 `InvalidState`（窗口内探针看不见它）；⑧**`occurred_at` 不可解析** → `InvalidState`（账户面探针在窗口前拦截），而合法的 `1970-01-01T00:00:00Z`（epoch 0）必须正常返回、不被误拒；⑨**SQL `sum()` 溢出** → `InvalidState` 而非 `Unavailable`；⑩缺投影行落在所请求窗口**之外**仍 `MissingProjection`（账本级探针，不退化成 TEXT 比较）；⑪空账本 → 「不适用」而非 0 | APP+JVM |
| V-12 | 无障碍与字号 | 1.3× 字号下报表区、日历格、明细行不截断不重叠；TalkBack 朗读精确带符号金额与币种（图形不作唯一载体） | DEV（font-scale 工件）+APP 纯函数 |
| V-13 | 计数与首屏同快照 | 同一请求内计数=首屏对应集合的穷举值；**装载后、请求前目录被改写** → 请求携带的 `expectedCatalogVersion` 与快照内目录版本不一致，返回 `CatalogVersionMismatch` 类型化失败，绝不返回零。（不把「同一读事务内看见写入」写成判据：单连接读事务内的写入不可见。） | JVM |
| V-14 | 规模读数（记录不设阈） | 50k 单月夹具上 instrumented 端口计时（期间金额/日历桶读/首屏/计数四项）各一组读数，落 logcat 前缀行；**只登记基线，不预设门槛** | DEV（instrumented 工件） |
| V-15 | 历史夏令时贴界日桶 | 取 `Asia/Shanghai` 历史夏令时时段（1986–1991）里**贴近日界**的统计瞬时（当地约 00:30 两侧各一组）：正确日桶与「固定 +08:00 偏移」会落到**不同日期**，断言按真实时区落桶、且该日明细与日格一致；同时断言日桶边界由 ≤32 个当地零点界生成、桶数 ≤31。该向量只验证已批准的日期域（全部日期、`Asia/Shanghai`），不引入「仅当代」范围 | JVM+APP |
| V-16 | 进行中期间完整分母 | 进行中月份（如 11 月 16 日取值）日均分母为该月完整自然日数（30），断言日均 = nearest(净支出 ÷ 30) ≠ 按已过天数算出的值；跨月边界前后各一组；分母不因「进行中」缩短 | JVM+APP |
| V-17 | 日均舍入边界 | 以 `q`（向零商）、`r`（有符号余数）、`a = abs(r)` 的表达逐例断言：`5/2 → 3`、`−5/2 → −3`、`1/2 → 1`、`−1/2 → −1`、`4/3 → 1`、`−4/3 → −1`、`5/3 → 2`、`−5/3 → −2`、`0 → 0`；边界另加 `Long.MIN_VALUE` 被除数与 `d = 1`（不得触发 `abs` 溢出）、恰半与非恰半各一组；`-0` 与 0 显示不带负号；原负净支出仍以带符号呈现 | JVM+APP |
| V-18 | 未来标注口径 | 一笔未来统计日期的普通贡献交易 + 一笔未来统计日期的特殊 kind（储值/调整）→ 标注 N = 1（而非 2）；只有特殊 kind 或纯本金的未来交易 → 无标注（N = 0 缺席）；**今日较晚时刻**的交易不算未来日期（阈值是下一当地零点），`ALL` 跨度仍含未来日期；跨月未来交易在其所属期间被计入 | JVM+APP |
| V-19 | 日历双币种零日与轴一致性 | 账本活动币种轴含 `CNY` 与 `USD`，构造一个**两币种都没有贡献**的日期：该日仍按轴各出**两行零**（不是一行、不是缺行）；该日笔数是全局 `COUNT(DISTINCT transaction_id)`（两币种零贡献 ⇒ 0），不是两条逐币种计数；同一请求内读轴（`ledgerActivityCurrencies`，同一事务），构造一次跨请求的轴变更证明**不借用陈旧轴**；轴一致性校验对新报表/日历与月读同源 | JVM+APP |

## 5. CI 与设备证据归属

- **必过门**：`Kotlin tests` / `Android compile` / `Python tests` / `Trace scan`（`.github/workflows/ci.yml`）。本批新增测试进既有名单：新增 `ledger-data` / `ledger-application` 测试类先按**暂定**位置登记进 `tools/ci/ledger-data-shards.txt`，首次 CI 运行后按实测类秒重新装箱平衡（`tools/ci/make-ledger-data-shards.py` 的两种输入见 `docs/CONTRIBUTING.md` 的 CI 章节），并保持 `verify-shard-coverage.py` 的「编译出的类 == 名单、执行出的类 == 该片名单」两道守卫绿；新增 Python 模块同理进 `tools/ci/python-shards.txt`。**不得**把尚未跑过 CI 的类说成「已按实测装箱」。
- **设备读数**：走 `.github/workflows/android-instrumented.yml` 的 instrumented 腿，`-e` 传额外参数（既有先例 `ul.p802.timing=1`），读数以 `P806-…` 前缀打印进 logcat 并由工件取证；**记录不断言阈值**（`D-289` 的「量测值只记录、不断言阈值」口径）。新增 instrumented 测试类必须**同时**登记 `tools/ci/android-instrumented-inventory.txt` 与 `tools/ci/android-instrumented-shards.txt`，否则 producer job 在设备启动前即拒（既有纪律）。
- **字号/呈现**：`.github/workflows/android-fontscale-sample.yml`（`workflow_dispatch`、必填 `expected_sha`、可选 `profile`/`font_scale`，`docs/CONTRIBUTING.md:137-149`）新增报表与日历屏的样本；实现批需同步扩展该档的 seed 与 gate 判据。
- **不使用**并行 maximum-scale rig（`android-scale.yml` 与其驱动器）：该链属导入批次线，本批不得改其腿、预算或判据。
- **本批本机零执行**：本机不跑 Gradle/测试/编译/ktlint/文档校验/trace 预检；全部结论由云端 CI 产出。文档批走 docs 快路径（`docs` 分类），四个必过检查名仍成立。

## 6. 尚未选定的实现细节（四项）

已裁定的产品合同与已冻结的读面机制**不在此列**，其替代方案也不得再作为候选提出：端口形态与参数化贡献谓词、报表期间游标、分类子树谓词、单请求单事务快照、类型化 `returnTo` 均见 §3 与 `D-343`。本节只剩四项尚未选定的细节。

- **T2 日均的展示细节**。仅剩展示层取舍：日均是否与净支出同精度并列、`-0` 归一后的空白/零显示样式、以及是否需要一行「分母为该周期完整自然日数」的说明文案。分母（§3.3.4、V-16）与舍入实现（§3.3.2 的向零商 + 有符号余数、恰半远离零）都已冻结，不在本节重议。
- **T4 账户维度索引**。推荐：本批零 DDL，接受「返回行数有界、扫描代价随账本增长」，以 V-14 读数登记。替代：给 `posting(ledger_id, account_id)` 与 `occurred_at` 数值投影加索引——那是 v37→v38 迁移边，必须独立迁移门与回填/验证证据。证据：§2 事实 4、事实 8；`Ledger.sq:60-61`（唯一时间索引）。风险：50k 账本上账户页每页一次全账扫描，Android 单连接下与月度读竞争。
- **T8 性能硬阈值**。本批**不预先批准**任何时间门；V-14 只登记读数。若后续要求门槛，须先有可重复夹具与已量化的噪声带——余量小于环境噪声带的门禁不可裁决，这是既有教训。
- **T9 日历视觉形制**。本批不批准网格密度、色阶、图形化占比；实现批先出样本 + 设备证据再裁（§3.10.5）。

## 7. 不做与残余

**不做**：任何账务写入路径改动；对账/证据写入；新迁移/新列/新索引/新触发器（schema 停 v37）；跨币种折算；账户利润、历史余额曲线、期末余额推导；预算与提醒日历；导出；跨账本报表；图形化占比的新形制；自动检测外部变更或轮询刷新；并行 rig 的任何改动。

**残余（登记，不并入任何 PASS）**：
1. 账户、分类、日历日桶与目录一致性探针的扫描代价都不随窗口收敛（无索引，§3.4 表后的成本段、T4）。
2. 明细页与计数是同一次请求的短快照，翻页之间允许新提交进入；跨页不承诺同一快照（§3.5）。
3. 报表期间游标与月卡游标并存，用户可能对两个期间控件产生歧义；两个游标本身已冻结（§3.1.3、§3.10.3），歧义属呈现层待解问题，实现批须给可读样本。
4. 日历请求的语句数是 `C0 + 2D`（`D ≤ 31`，`2D ≤ 62` 只是逐日子查询那一半，`C0 = 4` 含同事务的币种轴读），单连接下会推迟其他读；数量界与扫描成本都已在 §3.4 分列，不以语句数当性能结论。
5. 设备侧月聚合观察面只剩分析页（`D-339`/`D-340`），报表证据必须新打一面，不能沿用已退役文案。

## 8. 实施批次建议（划分与排期另行裁定，不构成实施授权）

1. **批 a（读面）**：`ledger-data` 新增命名查询（Appendix A：目录一致性探针 + `ordinary_contribution` 共享谓词 + 金额聚合 + 三种 `COUNT(DISTINCT)` + 页/计数同谓词 + 账户页成员边界 + `occurred_at` 解析探针）+ 账本级缺投影 fail-loud 前置 + `ledger-application` 期间窗口/当地日桶/日均/分类树折叠与类型化结果族 + **共享轴校验提取**：把 `isAxisCatalogConsistent` 提为共享纯函数，月读与新报表/日历同一判定同源，并加一处**防分叉测试**（同一夹具两处判定必须一致；widget 月度快照不在本批范围、不改动）+ JVM 真库测试（V-1/V-2/V-3/V-4/V-5/V-9 的读面部分/V-11 的目录、轴、缺投影与解析四路/V-13/V-15/V-17/V-18 的读面部分）。
2. **批 b（呈现与回程）**：`app-ui` 报表区（周期分段、2×2、分类排行、日均、未来标注）、日历二级面（≤31 桶同事务读、初始月取首页 `selectedMonth`）、明细二级面、详情 `returnTo` 扩展、账户面换读源与「资金发生日期」/「N 条账户变动/记录」文案、请求身份与落地守卫 + reducer/纯函数测试（V-6/V-7/V-8/V-9 的日格部分/V-10/V-11/V-12 的纯函数部分/V-16/V-18 的标注呈现部分）。
3. **批 c（设备证据）**：instrumented 读数（V-14）+ font-scale 报表/日历样本（V-12）+ 判定表回填。
4. 每批按仓库既有高风险变更路由推进（独立审查 + 云端 CI），全部验证归云端 CI，本机不作验收。

## Appendix A. 拟议命名查询清单（全部 SELECT-only、按 `:ledger_id` 限定、零 DDL）

```text
-- 共享谓词块 ordinary_contribution（所有普通收支读面逐字复用，不得各自近义拼写）：
--   COALESCE(tx.canonical_kind, tx.kind) IN (:kind1 … :kindN)              -- 集合来自 OrdinaryFlowClassification
--   AND EXISTS (SELECT 1 FROM posting AS p
--               JOIN catalog_account AS a
--                 ON a.ledger_id = p.ledger_id AND a.account_id = p.account_id   -- 权威账户表 join（:9077-9078 形状）
--               WHERE p.ledger_id = tx.ledger_id AND p.posting_set_id = version.posting_set_id
--                 AND a.kind IN (:contribKind1, :contribKind2)                  -- 绑定参数：EXPENSE / INCOME
--                 AND p.amount_minor != 0)                                      -- 零分录不计
--   不带 owned_by_user / real_account / ASSET·LIABILITY 过滤（那组只属账户资金流水页）
--   分类条件为可选附加 EXISTS：catalog_category c ON c.posting_account_id = a.account_id
--     AND (c.category_id = :category OR c.parent_id = :category)

-- 固定前置（同一次短事务、在贡献/分类过滤之前，顺序见 §3.5）：
p806PostingCatalogConsistencyProbe    -- SELECT-only，同一条 SELECT 返回 1 行 3 列
                                      --   范围 = 账本 + 当前版本 join + 有效状态 + 请求时间轴窗口内的【交易】，
                                      --     检查这些交易的全部 posting（不按金额/kind/分类过滤）
                                      --     （current-version join 与 effective 都必须满足；superseded 行不计）
                                      --   FROM ... JOIN posting p ON p.ledger_id = version.ledger_id
                                      --        AND p.posting_set_id = version.posting_set_id
                                      --   LEFT JOIN catalog_account account
                                      --     ON account.ledger_id = p.ledger_id AND account.account_id = p.account_id
                                      --   三项均为【条件 COUNT】：条件真 → 非 NULL 的 1；假/未知 → NULL 不计数
                                      --   unresolved_account =
                                      --     COUNT(CASE WHEN account.account_id IS NULL THEN 1 END)
                                      --   cross_ledger_account =            -- 必为 unresolved 的子集
                                      --     COUNT(CASE WHEN account.account_id IS NULL AND EXISTS (
                                      --         SELECT 1 FROM catalog_account f
                                      --         WHERE f.account_id = p.account_id AND f.ledger_id <> p.ledger_id)
                                      --       THEN 1 END)
                                      --   currency_mismatch =               -- 对齐 isCatalogConsistent 的币种相等
                                      --     COUNT(CASE WHEN account.account_id IS NOT NULL
                                      --         AND (account.currency_code <> p.currency_code
                                      --           OR account.currency_precision <> p.currency_precision)
                                      --       THEN 1 END)
                                      --   干净窗口（含空窗口）三列必须为 0；不用 COUNT(<布尔>)，不用裸 SUM（空窗口 NULL）
                                      --   任一非零 → InvalidState 目录引用类
                                      --   窗口：报表/日历用 statistics_at_epoch_nanos 半开；账户页用 occurred_at 解析值半开
                                      --   ALL / 全部时间用解析后的全范围；日历用一次月范围探针，不逐桶重复
                                      --   语句数：本探针固定 1 条、C0 不增加（整请求 = C0 + 2D，2D ≤ 62 只属逐日子查询）

p806PeriodOrdinaryPostingTotals       -- 期间金额：SUM(amount_minor) GROUP BY (account_id, currency)
                                      --   + ordinary_contribution；窗口 = statistics_at_epoch_nanos 半开
                                      --   禁 TOTAL()；整数溢出→类型化失败（:12154-12156 纪律）
p806PeriodOrdinaryTransactionCount    -- 期间全局笔数：COUNT(DISTINCT version.transaction_id)
                                      --   + 同窗口 + ordinary_contribution
p806PeriodOrdinaryCurrencyTransactionCount -- 逐币种笔数：同形按 currency 分组（不得相加当全局值）
p806PeriodFutureOrdinaryTransactionCount   -- 未来笔数：全局笔数形 + ordinary_contribution
                                      --   + statistics_at_epoch_nanos >= :next_local_midnight_nanos
p806DayBucketOrdinaryPostingTotals    -- 日历日桶金额：同金额形状 + ordinary_contribution，桶界 (:start, :end)
p806DayBucketOrdinaryTransactionCount -- 日历日桶笔数：每日一个全局 COUNT(DISTINCT transaction_id)，
                                      --   不分 kind、不分币种，1 行/桶
p806DrilldownTransactionsPage         -- 期/日/分类明细键集页（页 50）：
                                      --   页键 (statistics_at_epoch_nanos DESC, transaction_id DESC)
                                      --   谓词在 LIMIT 之前：窗口 + 有效 + ordinary_contribution
                                      --   + 可选分类子树 EXISTS（:category 为 NULL 表示整个期间，仍走 ordinary_contribution）
p806DrilldownTransactionsCount        -- COUNT(DISTINCT transaction_id)，谓词与页逐字同形
p806AccountPostingsPage              -- 账户明细键集页（页 100）：页键 (occurred_at 解析值 DESC, posting_id ASC)
                                      --   成员 = 账本 + 当前版本 + 有效 + 复合标记(owned_by_user/real_account/ASSET·LIABILITY)
                                      --   + occurred_at 窗口；无 rg03 资格 join、无队列状态过滤；单位是【分录】
p806AccountPostingsCount             -- count(*)，谓词与页同形；技术计数是该账户当前有效 posting 条数，
                                      --   对外界面表述「N 条账户变动/记录」（不得称「N 条分录」）
p806OccurredAtParseFailureProbe      -- 账户面解析有效性：SELECT-only 1 行
                                      --   范围 = 该账户 + 当前版本 + 有效（不看时间窗）
                                      --   计数判据 = 冻结形态解析器的 NULL 结果（不是数值 0）
                                      --   非零 → InvalidState；合法 epoch 0 解析成功、放行
                                      --   在窗口过滤之前执行，计入账户 surface 的 C0
p806LatestEffectiveStatisticsAt      -- MAX(statistics_at_epoch_nanos)（MIN 已存在：earliestEffectiveStatisticsAtNanos）
-- 复用既有查询（不新增）：earliestEffectiveStatisticsAtNanos、ledgerActivityCurrencies、
--   statisticsAtProjectionMissingForLedger（账本级缺投影 fail-loud 前置探针）、ledgerFlowPage（页形状先例）
-- 读面组织：以上查询**不各自成为公开 port 方法**；四个复合快照方法
--   readReportSnapshot / readCalendarSnapshot / readDrilldownSnapshot / readAccountPostingsSnapshot
--   加在既有 LedgerCurrentStateReadPort 上（默认 UOE），每方法一次事务带回本 surface 的
--   probes + 金额/页/计数/轴。方法名与结果类型是拟议新增，不是现有 API。
-- 轴一致性：isAxisCatalogConsistent 由 QueryMonthlyActivity:148 提取为共享纯函数，
--   月读与新报表/日历同源；它是纯函数、不占 SQL 语句、不增加 C0。
-- 时区与日桶边界不进 SQL：≤31 桶 / ≤32 零点界（含未来标注的 :next_local_midnight_nanos）由 application 生成
-- 缺投影一律 fail-loud（MissingProjection）：账本内任一行缺投影即失败，含窗口外
-- 零 DDL：无新索引/新列/新表；schema 停留 v37
```

## 边界断言

- 本文档是 **approved（设计批准，未实现）**：冻结产品合同、读面设计与验收向量；不授权实现、迁移或发布，实施另立批次并按仓库高风险变更路由推进。
- 设计冻结的要点：每 surface 一个复合 typed 快照（既有端口 + 默认 fail-loud + 单事务 + `expectedCatalogVersion`）、日历同事务读币种轴与双币种零日、账户资金流水主行的「资金发生日期」局部例外与「N 条账户变动/记录」文案、共享轴校验、目录探针的 current-version join、`occurred_at` 解析 fail-closed（合法 epoch 0 放行）、`sum()` 溢出归 `InvalidState`、载荷六态不伪零、CI 分片暂定与 instrumented 双名单。
- 实施批必须保持：§1 已批准合同与 §3 的窗口/门控/日均/有界性/短快照/回程语义、零 DDL（v37）、零账务写入、§4 全部向量与 §5 证据归属；任何语义变更须先修订本规格与 `D-343`。
- 示例全部匿名合成；无本机路径、无个人数据、无外部实现代码；外部参考树只读未触碰；`rgXX_` 竖井与 golden 零改动。
