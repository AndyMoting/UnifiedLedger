# P8-02 搜索与筛选设计（零迁移；独立结果面）

状态：approved v1（2026-11-11 用户逐条批准 D1–D5 与「分类臂」）

日期：2026-11-11。前置：`P8-00` 视觉基线（搜索胶囊形态已冻结、搜索行为移出）、`P8-01b` 已交付（`main` = `0c23457e`）。

**本规格性质**：零 schema 变更（停留 v36）、零迁移、零账务语义变更。搜索与筛选都是**只读**面：新增具名查询与只读端口，写路径、对账、报表、余额一律不动。

## 1. 起点（实施前快照；读面已于 i1 落地，见 §8）

**界面**
- 首页搜索胶囊**已画、已接插槽、宿主从不传参 → 恒不渲染**：`P8HomeFlow.kt:288-307`（文案「搜索账目、金额、备注」、语义名「搜索账目」、最小 44dp）、`P503OverviewScreen.kt:82`（`onOpenSearch: (() -> Unit)? = null`）、`P503App.kt:4655-4686`。`P503OverviewScreen(` 有**两处**调用点——`:4655` 正常首页、`:5742` 读失败保留面（`interactionsEnabled = false`），两处都不传 `onOpenSearch`，`:4681-4682` 自述「宁可不渲染也不给死入口」。实施批需分别判断第二处是否接线。
- **搜索面零实现**：无屏、无 state、无 event、无应用层端口（`**/*Search*.kt` 0 文件）。
- **筛选只有一个真接入面**：标签/商家筛选（P7-08 08.C），入口在**分析页顶部**（`P503App.kt:4751`），作用于**全账本**当前有效交易，键集 `(statistics_at_epoch_nanos DESC, transaction_id DESC)`、页 50（`P503App.kt:5496`），维度只有「标签 OR/AND + 商家单选」。
- 首页流水无过滤（月份不筛列表）；账户流水靠 200 行/页内存过滤（`P503Reducer.kt:2011`）；导入审核列表无筛选。

**读面**
- 生产 schema **零文本检索**：唯一 `LIKE` 是 rg03 排序技巧（`Ledger.sq:2691`）；FTS/`MATCH`/虚拟表全仓 0 命中。
- `transaction_version.note` **可空且无索引**（列在 `Ledger.sq:24`，表 `:15-35`）；**交易时间列的唯一索引**是 `transaction_version_statistics_at_range_idx`（`Ledger.sq:60-61`）。
- 有索引的维度：时间；标签（`Ledger.sq:671`，`transaction_annotation_tag`）；商家标注（`Ledger.sq:656`，落在 `transaction_annotation_revision.merchant_id` `:645`）。一笔交易的**当前**商家标注经 `transaction_annotation_current`（`:687`）取，读投影里别名成 `annotation_merchant_id`（`:11311`）。**无索引**：`account_id`、`amount_minor`、`occurred_at`、`note`、`category_id`。
- **分类没有落在一张表上**：posting 级分类分散在 7 张 `rg*_posting_semantic`（rg03/04/05/06/07/11/12），既有跨表先例 `catalogReferencedSemanticCategoryIds`（`Ledger.sq:10328-10335`），注释 `:10326-10327` 明说这些表是 posting 级分类的唯一落点。
- 名称不在 `catalog_category` / `catalog_merchant` 上，且**两张名历史表分工不同**：分类名在 `catalog_name_history`（`Ledger.sq:370`，`owner_kind IN ('account','category')`），商家/标签名在 `catalog_item_name_history`（`Ledger.sq:590`，`owner_kind IN ('tag','merchant')`）。当前名读取的先例分别是 `selectCatalogCurrentNamesByKind`（`Ledger.sq:10344`，生产调用只传 category）与 `selectCatalogItemCurrentNamesByKind`（`Ledger.sq:11074`，调用点 `SqlDelightTagMerchantCatalogStore.kt:272`）。

**性能证据的边界**
- 现成地板全是**搜索条件之外**的读数：61k 首屏 ≤0.6 s（`D-294`，口径是**导入审核候选**列表的键集分页第一页（页 50 **候选**），没有搜索条件，且该夹具只有 2 笔交易）、20k 开组 2.61 s（`D-278` 登记不可比）、50k 整表冷读 31.6 s（原始登记在 `D-148`，`D-159` 记录未复现）。
- **「带条件查询 + ≥50k + 设备计时」是空格**；`D-289` 残余①自认须 instrumented 端口计时。
- Android **单连接**：长读会阻塞主线程读（`D-148` 的 ANR 根因），搜索读的代价按「占用同一连接多久」裁。

**冻结基线**
- `P8-00` 冻结入口形态、把搜索行为移出（`docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md:46`、`:102`）。
- 画布 `local/p8-00-design/07-搜索-占位.pen`：标题「搜索」、条件 chips「关键词 / 金额 / 标签 / 商家」「时间」「账户」「金额区间」「标签/商家」、主体「输入条件后开始搜索」。
- 清单 `local/p8-00-design/P8-00-功能屏幕状态全景清单.md:99` 点名「标签/商家筛选归置——现在在分析页顶、作用于首页流水——P8-02 一并理」。

## 2. 冻结的机制

### 2.1 条件模型与强制时间窗

条件组固定为四项：**关键词**、**时间窗**、**标签**、**商家**（分类并入关键词，见 §2.2）。

- **时间窗强制**：每次搜索必须在窗口内取数，默认近 90 天，用户可改（本月/近 90 天/自定义起止）。无窗口的搜索不提供。理由：`note` 无索引，分页扫描只能靠窗口变有界，窗口决定分页扫描那一部分的「占用单连接多久」（分类臂与每页计数重算见 §2.2 与 §8 第 1 条）。窗口**约束的是分页扫描**；它**不**约束分类臂在七张语义表上的扫描——那条代价见 §2.2，实施期已按实际写回。
- 条件之间是 **AND**；标签内部沿用既有 **OR / AND**（任一/全部）语义，与 P7-08 的 `tagMerchantFilterRows` 一致。

### 2.2 关键词＝三臂 OR（备注 / 商家名 / 分类名）

一次关键词先在**两张名历史表**里各自解析（两张表分工不同，见 §1），再落到行上：

1. **解析分类**：`catalog_name_history` 按当前名做子串匹配 → `category_id` 集（先例 `selectCatalogCurrentNamesByKind`，`Ledger.sq:10344`）。
2. **解析商家**：`catalog_item_name_history` 按当前名做子串匹配 → `merchant_id` 集（先例 `selectCatalogItemCurrentNamesByKind`，`Ledger.sq:11074`）。
   两张目录表每账本只有几十到几百行，解析代价可忽略。
3. 三臂 OR：
   - **备注**：`transaction_version.note LIKE '%q%'`（`Ledger.sq:24`，可空；大小写不敏感仅限 ASCII、中文按 UTF-8 子串，见 §8 第 5 条）；
   - **商家**：该笔交易的**当前**商家标注命中商家 id 集 —— `transaction_annotation_current`（`:687`）→ `transaction_annotation_revision.merchant_id`（`:645`），索引 `:656`；
   - **分类**：该笔交易的任一分录命中分类 id 集 —— 走 7 张语义表的 UNION，先例 `catalogReferencedSemanticCategoryIds`（`Ledger.sq:10328-10335`）。
4. 关键词为空时三臂全不生效，条件组退化为「时间窗 + 标签/商家」的纯筛选。

**已登记的代价**（实施期按实际改写信，2026-11-11，原因见 §8）：分类臂是 7 张表的 OR，逐行判定。这七张表都能按 `ledger_id` 前缀进表：rg03/04/05/06/07 的主键是 `(ledger_id, posting_id)`，rg11/rg12 的主键是 `posting_id` 并另有 `UNIQUE (ledger_id, posting_id)`；**`category_id` 上没有可用索引**，因此该 union **按整账本物化**，不受 §2.1 的时间窗约束；它的代价由账本的语义行数决定，而不是由窗口决定。反向做法（先用窗口内的 posting_set 再逐行探测七表）在本账本**稀疏**的语义表上更贵——窗口内的 posting 远多于语义行——故本片保留整账本 union。真正的收敛手段（语义表加 `category_id` 索引，或维护分类倒排）归后续批次，本片按 D5=A 不加任何索引。`category_arm = 0`（关键词没解析出分类名）时该分支在运行时不求值，但这一点依赖查询计划器，**不作为性能承诺**。商户/分类名依赖目录名历史的**当前名**，改名前的旧名不命中。

### 2.3 读面形状

- 新具名查询 `p802SearchPage`：单语句两步 CTE，`page_ids` 只取版本行（`LIMIT :page_size` 绑在 CTE 上，分页单位是**交易**不是分录），再按页批量装配分录 —— 与 `ledgerFlowPage`（`Ledger.sq:11435`）同形。
- 排序与键集：冻结为 `(statistics_at_epoch_nanos DESC, transaction_id DESC)`，与首页/标签筛选一致；游标 `NULL` = 首页。
- 计数：另给一条 `p802SearchCount`，只用于「共 N 笔」；**登记**：计数与首屏可能不是同一快照（沿 P7-04 分页读规格 `:64` 的既有取舍）。该查询属规格落地形状，不是六条裁决之一。
- 页大小：**50**，与标签筛选、导入审核一致（首页的 200 是另一条路径，不复用）。
- 失败语义：读失败返回类型化失败，绝不呈现为「无结果」（`D-089` / R-Q06-4）。

### 2.4 结果面与入口

- **一个独立结果面**（全账本作用域），不是首页原地过滤。
- 首页搜索胶囊接线（`onOpenSearch` 不再为 `null`），进入结果面；未有条件时呈现空态引导「输入条件后开始搜索」。
- **标签/商家筛选从分析页顶部搬进同一结果面的条件区，分析页顶部入口退役** —— 清单 `:99` 的 ⚠️ 由此收口。`OpenTagMerchantFilter` 事件族迁到结果面，语义不变。
- 条件区按画布 07 落地，但**首片只启用四项**：关键词、时间窗、标签、商家。画布上的「账户」「金额区间」两个 chip 在本片保持**未启用**（置灰或以"暂不支持"呈现），不得静默隐藏。
- 结果呈现：按日分组的结果列表，复用既有流水行与分组头；分组头只数不合计（沿首页已登记口径）。

### 2.5 日历不在本片

「收支日历」内页与聚合读模型归 `P8-06`。本片不建日历数据、不画月历网格；月份行上的「收支日历」胶囊保持未接线。

### 2.6 零迁移边界

不新增索引、不改表、不加触发器、不建 FTS 表；schema 停 v36。所有新增能力由**具名查询 + 应用层条件解析**构成。

## 3. 裁决记录（2026-11-11 用户逐条批准）

- **D1＝B**：关键词 = 备注 **OR** 商家名 **OR** 分类名；金额不当关键词（用户选择「更符合直觉」的那条，代价见 §2.2）。
- **D2＝A（首次标定）**：固定夹具 = **61k 交易**的可检索账本（备注/商家/分类三臂各有确定性覆盖）+ 默认窗 90 天 + 固定条件组；终点记两个数（端口返回首屏页 / 首屏渲染完成）；门槛由首轮读数登记为基线、不预设数字；设备走 instrumented 端口计时（补 `D-289` 残余①），JVM 另给同夹具对照。**不沿用** `D-294` 的 61k ≤0.6 s：那条读数的口径是导入审核候选列表的键集分页首屏（页 50 **候选**、无搜索条件，且该夹具只有 2 笔交易），与本片不可比。
- **D3＝A**：独立结果面，标签/商家筛选搬入并退役分析页入口。
- **D4＝A**：日历归 `P8-06`。
- **D5＝A**：零迁移；首片维度 = 关键词 + 时间窗 + 标签 + 商家（分类并入关键词）。
- **分类臂＝A**：接受跨 7 表，靠强制时间窗兜底，登记为残余。

## 4. 读写契约

| 用途 | 形态 | 说明 |
| --- | --- | --- |
| 搜索/筛选取数 | 新具名查询 `p802SearchPage` | 时间窗 + 标签/商家 EXISTS + 关键词三臂；键集分页 50 |
| 结果计数 | 新具名查询 `p802SearchCount` | 仅「共 N 笔」；与首屏非同快照已登记 |
| 关键词解析 | 分类名读 `catalog_name_history`、商家名读 `catalog_item_name_history`（两张表分工不同，见 §1） | 各得一 id 集；空集则该臂不生效 |
| 结果面 | UI 新屏 | 条件区四项 + 按日分组列表 + 空态/失败态 |
| 首页入口 | `onOpenSearch` 接线 | 胶囊由「恒不渲染」变可达 |
| 分析页筛选入口 | 退役 | 事件族迁入结果面，语义不变 |

本片**零写入**：不产生任何账务、对账、目录变更。

## 5. 验收向量

| # | 向量 | 判据 |
| --- | --- | --- |
| V-1 | 入口可达 | 首页胶囊渲染且可点击，进入结果面；无语条件时呈现引导空态 |
| V-2 | 关键词命中备注 | 子串命中（含中文与大小写变体）；窗内命中、窗外不命中 |
| V-3 | 关键词命中商家名 | `catalog_item_name_history` 的当前名命中 → 该商家的交易出现在结果里（商家臂走 `transaction_annotation_current` → `transaction_annotation_revision.merchant_id`，不是手工请求表的 claim 列）；改名后旧名不命中（登记口径） |
| V-4 | 关键词命中分类名 | `catalog_name_history` 的当前名命中 → 含该分类分录的交易出现在结果里；7 张语义表**各至少一条**夹具，逐表断言可命中 |
| V-5 | 三臂 OR 与 AND 组合 | 关键词三臂互不吞并；标签 OR/AND 与商家条件按 AND 组合，与 P7-08 语义等价 |
| V-6 | 键集分页与计数 | 逐页遍历不跳不重、末页游标为 null；「共 N 笔」与穷举一致 |
| V-7 | 失败不呈现为空 | 端口失败 → 类型化失败文案，绝不显示「没有找到」 |
| V-8 | 性能首次标定 | 61k **交易**夹具（现成 61k 夹具是候选库、只有 2 笔交易，**不能**作为本向量样本；未建交易级夹具时 V-8 判不可判定且必须先建）+ 默认窗 + 固定条件组：instrumented 端口计时与 JVM 同夹具对照各一组读数，登记为基线（不预设门槛） |
| V-9 | 无障碍 | 1.3× 字号下条件区与结果行不截断、不重叠；TalkBack 语义与首页行同口径 |
| V-10 | 入口退役不丢功能 | 分析页顶部筛选入口消失后，同一筛选语义在结果面可达且结果一致 |
| V-11 | 日历不越界 | 结果面无日历入口、无月历网格；月份行的「收支日历」胶囊仍不可达（`onOpenCalendar` 保持 `null`） |
| V-12 | 未启用 chip 可见且不可激活 | 条件区呈现「账户」「金额区间」两个 chip，处于未启用态且不可点击（不静默隐藏） |

## 6. 范围边界

**本片不做**：账户维度；金额区间维度（画布 chip 保持未启用）；日历视图与聚合读模型（归 `P8-06`）；FTS/全文检索；任何索引或迁移；筛选条件持久化/收藏；跨账本搜索；结果导出；搜索历史的服务端/AI 能力；任何账务写入。

**不变量**：账务行、余额、报表、版本、对账、作废恢复零变化；搜索与筛选只读。

## 7. 验证与残余

**验证**：任务分支 + PR，四门全绿后 squash 合并；性能首次标定的设备读数由 instrumented 链路给出，JVM 同夹具给出对照。

**登记残余**：分类臂跨 7 表逐行判定且按**整账本**物化（`category_id` 无可用索引，见 §2.2），代价随账本语义行数增长、不受时间窗约束；账户/金额区间维度缺席（画布 chip 未启用）；`note` 无索引，窗越宽代价越大；计数与首屏可能非同快照；目录改名后旧名不命中；Android 单连接下搜索读与导入/月度读竞争；`PRODUCT_REQUIREMENTS.md` 至今没有搜索条款，本片的产品承诺目前只由本规格与首页文案承载。

**V-10 的「结果一致」按谓词等价成立**（读层的搜索 vs `tagMerchantFilterRows` 等价测试跑在**全账本窗**下），但结果面的强制时间窗使**默认近 90 天**的行集与退役面的「全部交易」不同：同一标签在退役面上能看到更早的匹配，在结果面上要改窗才能看到。这是 §2.1 强制时间窗的直接后果，不是缺陷；用户可见差异在此登记。

## 8. i1 实施登记（2026-11-11，读面已实施）

本节只登记实施事实与残余，不改任何裁决、判据与验收向量。实施期对**代价口径**的三处事实纠正已直接写回 §2.1、§2.2 与 §7（各条就地注明），本节不另立 override，也不复述那三处结论。

**§3「分类臂＝A」的理由句已过时**：该条写的「靠强制时间窗兜底」是批准当时的代价口径，已被 §2.1/§2.2 的实施期事实纠正取代——分类臂按整账本物化、不受时间窗约束。**裁决本身不变**（接受跨 7 表、登记为残余）；§3 原文按批准记录保留、不改写，此处只作指向。

**首次性能标定（D2=A，JVM 腿，单次读数，未设门槛）**：61k 交易夹具 + 默认窗 + 固定条件组，首屏 **232.1 ms** / 50 行 / 计 436 笔 / 每页 12 条语句 / 夹具构建 6.36 s；三臂命中 备注 8715 笔、商家 436 笔、分类 7 笔。**该读数偏乐观**：夹具只有两个统计瞬时、分类只有 7 笔落在语义表，且计时那次的关键词不解析任何分类名（`category_arm = 0`），七表 union 的代价没有进这个数。设备腿与更真实的分类臂读数归 i2。**读数产自修轮前的候选 `4d011372` 的 JVM 运行**（JUnit XML 记录）；修轮新增的分类臂计时与全部新断言由本批 PR 的云端 CI 复跑，本文件不把修轮前的读数当作修轮后的执行证据。

1. **计数按页重算**：每次翻页都执行 `p802SearchCount`。同一读事务内计数与页面同快照（比 §7 的「非同快照」更严），但单连接下每页多付一次全窗扫描；是否改为仅首页由 i2 裁决。
2. **名称解析走产品目录读**：关键词的分类/商家解析复用 `CatalogAuthorityReader` 与 `TagMerchantCatalogReader`（`SearchQuery.kt`），**不新增窄端口——本条取代批次任务书 R1.1 的窄端口要求**，由主代理在修轮后裁定并登记于此。后果三条：目录不可校验时关键词搜索整体 `SEARCH_BAD_CATALOG`；已 tombstone 的目录项当前名仍参与命中；每次翻页两次整目录读。
3. **默认为滚动 90×24 h**：`[clock.now() - 90d, clock.now())`，不按冻结时区的日界对齐，因此「近 90 天」与日历口径在日界上有差；未来日期的交易在窗内不可见。
4. **结果行不含标注投影**：`SearchPage.entries` 只有 `LedgerEntryRow`，没有商家/标签；i2 结果面若要标注徽章，由 i2 首个改动扩展查询。
5. **`note` 的 LIKE 仅 ASCII 大小写不敏感**（SQLite 默认，零 DDL 不加 NOCASE 索引）；CJK 按字节子串匹配，与目录名解析的 `ignoreCase` 在非 ASCII 大小写上不对称。
6. **真正的漂移风险是重复的 union CTE，不是谓词行**：`keyword_category_postings` 的七分支在分页查询与计数查询里各写一遍；将来加第八张语义表只改一处，计数就会与分页静默分叉。今日这条由 V-4 的 `assertEquals(7L, result.totalCount)` 与 V-5/V-6 的计数断言兜住——加表时必须让它们变红。两条谓词块本身逐行相同（缩进除外）。
7. **反斜杠只到单元测试为止**：`escapeSearchLikeKeyword` 的 `\` 转义有单测（含转义顺序），但夹具里没有含字面反斜杠的备注，`ESCAPE '\'` 的端到端路径只由 `%`/`_` 覆盖。
8. **游标取自精确 nanos 列这件事没有断言钉住**：夹具的统计瞬时全是整秒，所以「解析 `statistics_at` 文本」与「读 `statistics_at_epoch_nanos` 列」在当前夹具上给出逐字节相同的游标，V-6 的遍历对两者都绿。代码取的是精确列，但要钉住它需要一个亚秒级夹具交易。

## 9. i2b 设备腿登记（2026-11-12）

**V-8 首轮基线（2026-11-12 已登记）**：派发 `gh workflow run android-instrumented.yml --ref main -f instrumentation_extra_args="ul.p802.timing=1"`，run `43075174195`（`015be2a1`、`ul-instrumented-2`、API 36、shard 2 `PASS` 21/21，用例 `P802SearchDeviceTimingInstrumentedTest#thePortFirstPageReadingIsRecordedOnTheDevice`）从 `android-instrumented-015be2a1…-shard-2` 工件的 `logcat.txt` 取到：`P802-V8DEV fixture=61000 windowStart=2026-10-13T18:13:51Z windowEnd=2026-11-11T18:13:52Z condition=fixed-note+tag+merchant0 portFirstPageMs=448.2 renderFirstPageMs=unmeasurable-no-compose-harness pageRows=50 totalCount=436 schema=36`。即 61,000 笔、默认 90 天窗、固定四项条件组下**端口返回首屏页 448.2 ms**、首屏 50 行、`totalCount` 436、schema 36。JVM 同夹具对照 232.1 ms（`4d011372`，见 §8）——两者**不可相减**，理由见下四条差异。**不预设门槛**（D2=A），此读数即基线；V-8 的最终判定在 P8-02 收口时给，本条只登记读数。

**终点收窄（必须与读数一起读）**：设备腿只取「端口返回首屏页」一个终点。「首屏渲染完成」本轮**未取得**——本 rig 上两条设备观测通道都绑不住 Compose 首帧：`uiautomator dump` 只返回可见节点（另有约 2.29 s 的 dump 地板），`gfxinfo framestats` 在 `swiftshader_indirect` 上返回不可换算值（`DECISIONS.md:6462`），且本仓不引入 Compose UI 测试框架（`P503SearchPresentation.kt:15`）。这是「本 rig 上未取得」，不是普适的「不可测」；未来路径是既有 `tools/python/android_scale` 的 B2 framestats 机制。

**四条样本差异**（与 JVM 腿对照时必须一起读，两个数**不可相减**）：①腿类型——设备夹具全 `EXPENSE` 两腿（默认种子无隐藏收入账户），JVM 交替支出/收入，读面谓词不看 kind/account；②统计瞬时——设备 30 个滚动瞬时（相对设备时钟），JVM 两个固定瞬时，两者都是高频并列键集且 tie-break 相同；③分类臂——设备用产品默认叶分类「早餐」，JVM 用合成分类，两臂都只解析一个 id、命中七行；④时钟与连接——设备走产品图与真实时钟，JVM 用固定 `CLOCK_NOW` 与第二条 JDBC 连接，这恰是设备腿存在的理由，但两个数不可相减。

**跳过与读数分前缀**：`P802-V8DEV-SKIPPED …` 只表示「没带参数」；读数行为 `P802-V8DEV fixture=61000 …`。派发时新增的守卫步骤要求 host shard 的 logcat 里出现 `P802-V8DEV fixture=`，缺席即该 shard 红——「绿」不再能等于「没取到读数」。

**两处实现期接受的偏离**：`-e` 走**两个 token**（AOSP `Am.java` 的 `-e` 分支连读两次 `nextArgRequired()`；仓库内先例 `ImportEnospcInstrumentedTest.kt:96`），任务书的 `KEY=VALUE` 字面写法作废；`--extra-arg` 落在 `tools/ci/android-instrumented-cloud.py` 而不是 `instrumented.py`（后者没有 argparse），该文件在批次任务书可写清单外，由主代理接受。

## 10. 验收判定（2026-11-12，主代理）

判据逐条取自 §5；每条写明证据类型（JVM 真库测试 / 纯 reducer·呈现测试 / 全仓 grep / 设备工件 / 人工读图）。**12/12 PASS，全部带披露**；披露项不是遗留缺陷，是本片范围外或本 rig 取不到的东西，均已登记在 §7/§8/§9。

| # | 判据 | 证据 | 判定 |
| --- | --- | --- | --- |
| V-1 | 入口可达 + 引导空态 | `P503SearchReducerTest.openEntersTheResultSurfaceFromTheOverviewAndReadsNothing`、`P503SearchPresentationTest.theHomeCapsuleRendersOnlyWhenWiredAndInteractive`（四行真值表）、`theGuideFaceIsTheNeverRequestedSurface`；**设备**：run `43060066555` 的 `search-guide.png` 正是从首页胶囊点进来的引导态 | **PASS** |
| V-2 | 关键词命中备注（中文/大小写、窗内命中窗外不命中） | `v2KeywordMatchesTheNoteInWindowAndNeverOutsideIt`（含半开窗边界、`%`/`_`/`\` 转义） | **PASS** |
| V-3 | 商家名当前名命中、改名旧名不命中 | `v3KeywordMatchesTheCurrentMerchantNameAndNotTheSupersededOne`（当前注解版本命中；superseded 版本的名字解析不到商家；旧名 0 命中且是空 Success 非失败） | **PASS** |
| V-4 | 分类名在 7 张语义表各至少一条夹具、逐表可命中 | `v4KeywordMatchesTheCategoryNameInEveryOneOfTheSevenSemanticTables`（七行 keyset 序 + 逐表断言） | **PASS** |
| V-5 | 三臂 OR 互不吞并；标签 OR/AND 与商家按 AND 组合 | `v5KeywordArmsDoNotSwallowEachOtherAndTheTagMerchantFilterStaysEquivalent`（三臂单测 + 并集计数 + 与 P7-08 `tagMerchantFilterRows` 逐条比对） | **PASS** |
| V-6 | 键集分页不跳不重、末页游标 null、计数与穷举一致 | `v6KeysetPagesDoNotSkipOrRepeatAndTheTotalEqualsTheExhaustiveWalk`（3/3/1 页、末页游标 null、每页计数=穷举） | **PASS** |
| V-7 | 失败不呈现为空 | `v7AFailedReadIsTypedAndNeverAnEmptyPage`（关闭连接 / 坏目录 / 抛异常 / 反向窗 / 零长窗 / 不可表示窗 / 投影缺失七路）+ 逐失败码文案测试（禁「没有找到」与空结果句） | **PASS** |
| V-8 | 性能首次标定 | JVM `232.1 ms`（`4d011372`，§8）+ **设备 `448.2 ms`**（run `43075174195`，§9）；不预设门槛（D2=A） | **PASS**（披露：两个终点只取得「端口返回首屏页」；夹具四条差异见 §9；两数不可相减） |
| V-9 | 1.3× 下条件区与结果行不截断、不重叠；TalkBack 与首页行同口径 | run `43060066555` 四屏 marker 齐全；**人工读图** `search-guide.png` / `search-results.png` 确认条件区十一项与结果行完整、无重叠；「TalkBack 同口径」由结果行复用同一 `LedgerEntryFlowRow`（含同一 `flowRowContentDescription`）保证 | **PASS**（披露：机械 gate 只证 marker 存活，裁切/重叠的结论来自人读图；两处可读性瑕疵已登记；**朗读巡检未做**，沿 `A-03`/`D-181` 需真机） |
| V-10 | 入口退役不丢功能 | 全仓「标签与商家筛选」**0 命中**；`theConditionAreaBuildsTheEquivalentSearchCondition` / `anEmptyKeywordDegradesToTheWindowAndTagMerchantFilter` / `theConditionOptionsIncludeDisabledTagsAndExcludeTombstonedOnes`；谓词等价由读层 V-5（`fa4e3262`）承载 | **PASS**（披露：结果面默认近 90 天窗，行集与退役面「全部交易」不同，§7 已登记） |
| V-11 | 结果面无日历入口、`onOpenCalendar` 保持未接 | `theResultSurfaceCarriesNoCalendarAffordance`（标签精确集）+ 全仓 `onOpenCalendar` 无非空调用点 | **PASS** |
| V-12 | 「账户」「金额区间」两 chip 可见且不可激活、不静默隐藏 | `theDisabledDimensionChipsAreVisibleAndNotClickable` + 屏内 `enabled = P8_SEARCH_DISABLED_CHIP_ENABLED`；**设备图**两个 chip 灰显可见 | **PASS** |

**判定不覆盖的东西（如实登记）**：①V-8 的「首屏渲染完成」终点在本 rig 上未取得（§9 写明原因与未来路径）；②V-9 的朗读巡检需要真机（沿 `A-03`/`D-181` 口径，与 `P8-00` 的同类披露合并管理）；③结果行不可点（开详情需第三回程目标，退役面本来也不可点）；④商家月度累积块随入口退役（D7-3）；⑤`DECISIONS.md` 两处历史条目仍把已删测试类记为 CI 证据（文档后续）。

**范围**：本条只判定 `P8-02`，不判定 `P8-03`～`P8-07`。
