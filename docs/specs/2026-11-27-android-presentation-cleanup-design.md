# Android 呈现专项设计（A 契约与呈现基座）

状态：approved

批准：2026-11-27，`D-369`。本规格是 Android 呈现专项的唯一设计来源；A 批只交付契约与呈现基座。用户已批准 B–E 按本规格分轨实施：各批独立评审、验证、CI 与合并，不得把后续批次范围混入 A。

## 1. 结论

Android 的界面展示由一层**纯显示投影**统一决定：业务类型、时间、金额、分类、空值和占比都有唯一输出形状，屏幕只负责排版。A 批交付这套投影、两条呈现 profile 的编译期选择点，以及可复用的设备取证配置；它不改变任何账务语义、schema、迁移或写入路径。

A 批的可检查结论：

- `ledger-application` 新增纯显示投影，输入是既有读模型与目录快照，输出是确定性的显示值。
- `app-ui` 的 `P503App` 新增默认 `LEGACY` 的呈现 profile 参数，并用 CompositionLocal 下传；桌面组合根不传，输出保持原样。
- Android 组合根显式传 `ANDROID`，其余调用点零改动。
- 字号机新增独立 `profile=presentation` 与 `ui_mode=light|dark`（默认 light）；已有 profile 的屏幕集合与判据不变。
- A 不声称任何界面已通过呈现验收，`profile=presentation` 的帧不作为验收证据。

## 2. 现状与差距

### 2.1 已批准的呈现基线

`D-301` 冻结三 Tab、导入入口与按币种分块的净资产，并明确「本条只冻结呈现层基线，不批准任何业务功能新行为」。`D-343` 冻结「平时界面只显示账目统计时间」的一处局部例外：只有账户资金流水主行显示「资金发生日期」，其余面仍显示统计时间。`D-367` 只做阶段验收，不改写任何切片判据。

### 2.2 未落地差距（本专项承接）

- `D-301` 所登记的 P8-00 规格 §2 六屏基线中尚未落地的显示差距不是新的产品行为，而是既有屏幕仍以模板化或技术化文案呈现业务事实：交易类型直接用枚举或固定文案、金额只有数值没有币种、分类只按名称无法消歧、空值把「未加载 / 失败 / 无记录 / 未分类 / 无资格 / 真实零」混为一谈。本专项把这些统一到 §4 的显示契约，但不回溯修改任何已完成批次的证据。
- `D-311` 第 3 条把「结果行的裸 UTC 时间戳把备注挤到下一行、日组与行内时区口径不同」登记为源自既有流水行格式、不修。本专项把该显示问题纳入范围：业务时间一律按 `Asia/Shanghai` 呈现，发生时间与统计时间分开，不再输出裸 UTC；旧批次已登记的披露原样保留，不重判 `P8-02`。
- 分类同名只在同一父级内唯一，跨父级同名合法（`ACCOUNTING_RULES.md`「分类规则」）；界面在消歧时必须给出层级路径，不能按名称猜测。
- 首页与报表标签各自拼字符串，`净支出`、`普通收入`、`结余` 没有单一来源；转账行的多腿金额可能被折叠成伪净额。

### 2.3 不变量

- 金额只以整数最小单位或精确十进制参与显示，二进制浮点不进入金额。
- 显示层不读 SQL、不持有 database handle、不写正式账本。
- 读失败、目录版本不一致与金额溢出显式失败；显示层不以零、空图或空列表冒充结果。
- schema 与迁移零变化；A 批零 DDL、零新依赖。

## 3. profile 数据流

### 3.1 运行时数据链

```text
读端口 / 目录快照（既有类型）
  -> ledger-application 纯显示投影（本批新增）
  -> app-ui 屏幕（按 LocalUiPresentationProfile 选择显示契约）
  -> android-app 组合根显式 ANDROID；desktop-app 组合根不传（LEGACY）
```

- `ledger-application` 的投影输入只有既有类型：`LedgerEntryRow`、`CatalogSnapshotView`、`LedgerCatalog`、`MonthlyActivity` 等。`LedgerEntryRow` 不加字段，不改 SQL/driver/schema/迁移。
- `app-ui` 通过 `LocalUiPresentationProfile` 读取当前 profile，不复制整套 UI；同一屏幕只换纯显示决策。
- `P503App` 的 `uiPresentationProfile` 参数默认 `LEGACY`，所有既有调用点（桌面组合根与测试）不传即保持旧输出。
- Android 组合根传 `ANDROID`；这是唯一新增的产品侧装配点。

### 3.2 设备取证 profile

字号机新增独立档：

- `profile=presentation`：复用共享的 P8-01 数据种子，捕获呈现基座当前可呈现的面；屏幕集合与既有 `empty / seeded / refund / p806` 互不替代。
- `ui_mode=light|dark`：默认 `light`，由 `cmd uimode night no|yes` 应用并回读；驱动与 `gate` 都记录/比对。
- `PRESENTATION_EVIDENCE_PLAN` 固定八个未来场景名：普通支出、多分类、转账、多币种、空日、单分类排行、跨日界对账、技术折叠。A 只冻结该配置；B/C/D 落地各自的面后再加屏幕与判据。
- 已有 profile 的屏幕集合、marker、TalkBack 目标与屏幕/朗读判据不变；新增的 `ui_mode` 记录与比对只约束该配置字段，`light` 是托管 AVD 的启动状态，因此既有读数不变。

## 4. 显示规则

本节是 A 批的机器契约；实现以 `PresentationProjection.kt` 与 `UiPresentationProfile.kt` 为准。

### 4.1 业务类型

`TransactionKind` 到中文业务名的映射必须穷尽。冻结文案：

| kind | 业务名 |
| --- | --- |
| `OPENING_BALANCE` | 期初余额 |
| `EXPENSE` | 支出 |
| `INCOME` | 收入 |
| `ACCOUNT_TRANSFER` | 转账 |
| `CREDIT_REPAYMENT` | 信用还款 |
| `REFUND_RECEIPT` | 退款到账 |
| `BALANCE_ADJUSTMENT` | 余额调整 |
| `BALANCE_ADJUSTMENT_REVERSAL` | 余额调整冲回 |
| `STORED_VALUE_RECHARGE` | 储值充值 |
| `STORED_VALUE_SPEND` | 储值消费 |
| `STORED_VALUE_EXPIRY_LOSS` | 储值到期损失 |
| `STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT` | 储值接入前余额调整 |
| `LEND` | 借出 |
| `COLLECT` | 收回 |
| `PREPAID_PURCHASE` | 预付购买 |
| `PREPAID_RECOGNITION` | 预付确认 |

新增枚举值时必须在同一 `when` 里选择业务名，编译失败优先于静默回退到枚举名。

### 4.2 时间

- 业务 `Instant` 一律先转 `Asia/Shanghai`（`MonthlyBuckets.REPORT_TIME_ZONE`），再取本地日期或本地墙钟文本。
- 发生时间（`occurred_at`）与统计时间（`statistics_at`）是两个带角色的值，不可互换、不可只带一个。`PresentationTimes` 在构造时要求角色正确。
- 默认文本是本地墙钟（`2026-11-27T10:47`），不是裸 UTC。账户资金流水主行显示「资金发生日期」的 `D-343` 局部例外不变。
- 月界、日界与跨日界对账使用同一冻结时区；其他屏幕不得自行选择时区。

### 4.3 金额

- 文本形状固定为「带符号金额 + 一个空格 + 币种代码」，例如 `-12.34 CNY`、`0.00 CNY`、`87 JPY`。正数不带 `+`，与既有产品文案一致。
- 数值来自精确 minor units 与 `CurrencyUnit.precision`；precision 支持 0..18。
- `Long.MIN_VALUE` 从其十进制文本直接渲染（precision 2 时为 `-92233720368547758.08 CNY`），不得取绝对值溢出，也不得夹到 `Long.MAX_VALUE`。
- 任何一步不得先转 `Double`；金额相加使用 checked 整数运算，溢出显式失败。

### 4.4 统一汇总标签

| 标签 | 文案 |
| --- | --- |
| 净支出（含退款） | `净支出（含退款）` |
| 普通收入 | `普通收入` |
| 结余 | `结余` |

这三个文案由 `PresentationSummaryLabel` 单一来源提供；结余仍为普通收入减净支出，不是账户余额或现金流。

### 4.5 空值与真实零

六个状态各自有文案，不得用同一空白或零替代：

| 状态 | 文案 |
| --- | --- |
| 未加载 | 未加载 |
| 读取失败 | 读取失败 |
| 无记录 | 无记录 |
| 未分类 | 未分类 |
| 无对账资格 | 无对账资格 |
| 真实零 | 金额 `0.00 <code>`（走 `Present(0)`） |

真实零是已加载的确定值；其余五个是缺席状态。显示层不得把 `null` 同时解释为「未加载」与「失败」。

### 4.6 分类

- 关联只走 `catalog_category.posting_account_id`：候选按稳定 `CategoryId` 文本升序排序后以 posting 的 `accountId` 做显式 first-wins 关联。`CatalogSnapshotView` 是 UI 侧缓存，`LedgerCatalog` 是领域侧回退源；两者都缺时为「未加载」。`QueryTransactionDetail`、`MonthlyBuckets`、`P806Folds`、`QueryRecycleBin` 对重复 `posting_account_id` 仍是 last-wins，本批不改这四处生产文件，差异登记为 C 批残余（§8 第 7 条）。
- 输出保持 posting 原顺序，按稳定 `CategoryId` 去重；每项带当前名与层级路径（`餐饮 > 早餐`）。
- 同名二级分类跨父级出现时，显示名使用完整路径；名称唯一时用裸名。分类义务只属于 `EXPENSE` / `INCOME` 腿：只有这两类账户映射缺失才置「未分类」；真实 `ASSET` / `LIABILITY` 腿与 `EQUITY` 腿按设计没有 `posting_account_id`，不承担分类义务、不计未分类。目录缺失或账户不在权威目录时 fail-closed 记未分类。未分类的腿只标记「未分类」，不发明分类、不落到一级分组。
- 分类色块按稳定 `CategoryId` 做确定性映射（32 位 FNV-1a 取模调色板长度）；A 只冻结 helper 与契约，颜色值属后续呈现批。

### 4.7 首页行契约

后续首页批必须按以下顺序取主标题：

1. 非空备注（原样，不裁剪业务内容）；
2. 恰好一个已解析分类且无未分类腿时，取该分类显示名；
3. 否则取 §4.1 的中文业务类型。

分类小角标按 posting 原顺序，最多显示三枚；超过三枚时显示「其余 N 个」的数量，不静默丢弃。角标以稳定 `CategoryId` 为键。

金额规则：

- 任何交易的真实 `ASSET` / `LIABILITY` 腿（`real_account = 1` 且 kind 属于两者）都按 posting 原顺序逐腿显示精确金额与所属真实账户；**多真实腿在任何类型下都不相加**——混合支付逐真实账户显示，转账也不得压成伪净额；单真实腿普通支出仍是一行。隐藏费用/收入过账腿与权益腿不参与。
- 跨币种不合并、不折算；每条腿/每个币种各自保留精确金额。

未实现占位保持禁用或静态说明，不放置可点击的死入口。技术 ID、请求码与日志不删除，后续统一进入可展开的技术信息区；A 只冻结该契约，不实现展开控件。

### 4.8 横向占比

- 输入是每分类的非负精确金额；分母为 checked 和，溢出显式失败。
- 百分比是 `round(amount × 1000 / denominator)` 的整数有理数运算，固定一位小数、half-up；不先转 `Double`。
- 单分类恰为 `100.0%`。
- 分母为零、任一金额为负或中间结果溢出时返回明确的无图结果，并保留精确表；不得画零高条、空图或把失败读成 0%。

## 5. 无障碍、隐私与设备验收

### 5.1 无障碍

- 每个金额、时间、状态和业务类型都以文本节点呈现；TalkBack 句子必须包含精确金额与币种代码。
- 分类角标与「其余 N 个」必须有可朗读文案；颜色不是唯一区分手段。
- 技术信息折叠区默认收起，但事实与日志不删除；折叠控件要有可读的展开/收起标签。
- A 批不新增 Compose UI 测试框架；纯显示决策用 JVM 测试断言。

### 5.2 隐私

- 显示投影只接收匿名合成数据与现有读模型；tracked 文件不写个人数据、绝对本机路径、请求码或代理痕迹。
- 技术信息区不泄露原始整行、文件路径、账户锚点或密钥；`D-143`/`D-146` 的隐私边界继续有效。

### 5.3 设备与验收

- `profile=presentation` 与 `ui_mode` 只提供取证配置；A 不运行、不声称任何 PNG、语义树或 TalkBack 结果通过。
- 后续批次使用同一字号机：PNG、`uiautomator` 语义树、`host.json` 配置（profile、ui_mode、font_scale、种子哈希）与真实 TalkBack 逐目标窗口。
- `gate` 只做机械判定（配置一致、屏幕集合一致、marker 与必需串存在、真实朗读窗口成功）；「无视觉溢出」仍是人工读图结论。
- 既有 `empty / seeded / refund / p806` 的设备证据与披露不因本批改写。

## 6. A 批交付与验收向量

| 向量 | 内容 | 证据类型 |
| --- | --- | --- |
| V-A1 | 业务类型穷尽且文案冻结 | `PPresentationProjectionTest` |
| V-A2 | 上海时区、发生时/统计时分离、本地墙钟文本 | `PPresentationProjectionTest` |
| V-A3 | 金额符号、币种、precision 0..18、`Long.MIN_VALUE`、真实零 | `PPresentationProjectionTest` |
| V-A4 | 六个空值状态互不混淆 | `PPresentationProjectionTest` |
| V-A5 | 分类按 posting 顺序、稳定 ID、同名二级路径、未分类不猜 | `PPresentationProjectionTest` |
| V-A6 | 首页标题优先级、三枚角标与剩余数量、真实腿逐腿不合并（含混合支付）、单腿一行、转账逐腿、多币种分列 | `PPresentationProjectionTest` |
| V-A7 | 占比一位小数 half-up、单分类 100.0%、无图结果 | `PPresentationProjectionTest` |
| V-A8 | 默认 profile 旧输出、Android profile 新输出 | `UiPresentationProfileTest`、`AndroidUiPresentationProfileTest` |
| V-A9 | `profile=presentation` 与 `ui_mode` 配置、既有 profile 不变 | `tests/python/test_android_presentation_rig.py` |

A 批的四项必过检查（`Kotlin tests`、`Android compile`、`Python tests`、`Trace scan`）由 PR 承担；本机只跑文档校验与空白检查。

## 7. B–E 批次契约

### 7.1 B：主 Tab

- 使用 §4 的投影组织首页/账户/分析三 Tab；首页行按 §4.7 渲染。
- 保留 `D-301` 的三 Tab 与导入入口；`D-122`/`D-146` 的四 Tab 契约继续处于被取代状态。
- 不改变账务读写、schema 或导入语义。

#### 7.1.1 B 落地记录

B 在 `LEGACY` profile 之外只改显示与布局，落地落点如下（本小节只记录实现位置，不新增批准内容）：

- 纯决策集中在 `app-ui` 的 `P503LedgerViewPresentation.kt`：`androidTabPresentation`、`homeShowsAccountBalancesTail`、`flowFooterForProfile`（Android 去掉「已到末页」，加载/失败/重试保留）、`homeRowAndroidPresentation(For)`（消费 §4.7 的 `projectHomeRow`，Android 下无论目录是否到达都走该投影，缺失时显示明确未加载/读取失败，不回退枚举或裸 UTC 行）、`homeBadgeText`/`homeRemainingBadgesText`/`homeBadgeTintSlot`（§4.6 的稳定 `CategoryId` 槽位映射到现有 `categoryTints`）、`accountsCatalogManagementRouteActive`/`accountsShowsManagementSections`/`catalogManagementScreenTitle`、`androidAnalysisLayout`、`androidCategoryShareSections`（§4.8 有理数占比）、`currentRowAndroidHeader`/`currentRowAndroidAmountLine`（月度未加载时回退行的中文业务类型＋上海墙钟＋精确金额）、`calendarDayShowsAmounts`/`calendarVisibleCurrencyRows`/`calendarDayCountText`、`catalogDisplayPairComplete`/`landCatalogDisplayPair`、`moreAnalysisOpenLabel`；预算的短/长口径切换在 `P503BudgetPresentation.kt`。
- 目录权威：`P503LedgerFacade.currentAuthoritativeCatalog()` 返回当前 session 的目录（`session.authority.catalog`，无 session 时才回退注入目录），在既有读取封装内与 `catalogSnapshot()` 配对调用：后台首屏装载走 leased `snapshot` 读取，管理命令成功与手动/标签商家刷新走既有的同步 `probe` 封装。`P503App` 缓存 `CatalogSnapshotView` 与其所属 `LedgerCatalog` 一对，由同一个纯决策 `landCatalogDisplayPair` 落地；三处 host 落点共用该决策，任一半缺失时两半都保留上次成功配对（不一半新一半旧，也不只更新 snapshot 半）。组合期只读缓存，不在 compose 里读 facade 或数据库。本批不新增 `catalogVersion` 一致性语义：仍以既有同一次读取内的目录与 generation 判定为范围，配对只保证显示缓存不在两次命令刷新之间混合半对。
- 首页（`P503OverviewScreen.kt`）：Android profile 下每行按备注→单分类→中文业务类型取主标题，`#分类` 角标按 posting 原顺序最多三枚并显示「其余 N 个」，真实资产/负债腿逐腿一行、不合并、不折算；首屏去掉重复的账户余额尾。正常路径与保留读失败页传同一对缓存。首页 Android 行的 TalkBack 保留发生时间与统计时间两个值（批准的精确朗读），不合并、不省略。
- 共享流水行（`P503OverviewScreen.kt` 的 `LedgerEntryFlowRow`）：它同时服务首页、搜索结果与报表下钻。B 在该行按 profile 切换文案：`LEGACY` 保持旧枚举与裸 UTC 文案，`ANDROID` 用中文业务名 + 上海统计时间 + 非空备注；posting 行仍使用调用方传入的精确金额 formatter（报表下钻的 `Long.MIN_VALUE` 口径不变）。因此搜索结果与报表下钻的既有设备文案期望由 B 一并同步；详情页与 C 批二级页面不在该行范围内。
- 账户（`P503CatalogManagementScreen.kt`）：Android 首屏 = 按币种净资产 + 借贷占位 + 资产/负债真实账户与账户流水入口 + 单一「账户与分类管理」入口；管理面（新增/改名/停用/置顶/分类/标签/商家）搬到二级路由，沿用同一 `P503CatalogManagementScreen`、同一目录版本与同一命令语义。
- 分析（`P503TabShell.kt`）：Android 默认月摘要 + 横向分类排行 + 预算；移除顶部原始枚举与币种堆叠；无收入时不渲染收入分类区；趋势与独立期间报表移入「更多分析」，查询与期间语义不变。`LEGACY` 保持原样：原始堆叠保留、支出区仍是带饼图的 `P503CategoryRegion(withPie = true)`、趋势与报表原处内联，因此 `showsExpenseRanking` 只在 Android 为真。
- 横向占比（`P503LedgerView.kt`）：每行分类名 + 精确金额 + 一位小数占比 + 水平条；单分类恰为 `100.0%`。零金额分类不进入图表输入（否则会画一条无意义的 `0.0%` 条），但仍以精确金额留在精确表中；负 net 不预先滤掉，原样交给 §4.8 的 helper，由它返回 `NEGATIVE_AMOUNT` 的 `NoChart` 结果。`NoChart` 在排行区只显示原因，完整精确表与下钻由下方统一分类明细区提供。
- 日历（`P503CalendarScreen.kt`）：Android 无交易日只显示日期；有交易日显示非零收支与笔数。真实零、读取失败与旧数据/重试仍是各自的面。
- 预算（`P503BudgetView.kt`）：仅当读取**成功**且当月没有已配置范围时，Android 用短说明 + 设置入口，长口径说明进「口径说明」信息区；未加载与两类读取失败保持完整状态，`LEGACY` 也保持完整说明。
- 导航状态（`P503AppState.kt`/`P503UiEvent.kt`/`P503Reducer.kt`）：`OverviewEmpty.catalogManagementSurface` 与 `OpenCatalogManagement`/`CloseCatalogManagement` 是加性字段与事件；`Back` 先关路由再走 ACCOUNTS→HOME 规则，底部 Tab 点击也离开路由；其余状态由集中守卫吸收，不产生 ISE。「更多分析」展开是视图偏好（`remember`，非账本状态），折叠不改变任何查询或期间游标。

未在本批验证：任何 PNG、语义树或 TalkBack 实跑（E 批）；`profile=presentation` 的屏幕集合与 marker 未因 B 增加。

### 7.2 C：二级页面

- 详情、搜索、账户流水、报表与日历消费同一投影；发生/统计时间分列。
- 分类关联统一为 A 的稳定 `CategoryId` 文本 first-wins：C 批把 `QueryTransactionDetail`、`MonthlyBuckets`、`P806Folds`、`QueryRecycleBin` 现有的重复 `posting_account_id` last-wins 与投影对齐；A 不改这四处生产文件，只登记差异（§8 第 7 条）。
- 报告与日历的期间、日均与空日口径沿用 `D-343`；本专项只统一呈现，不重开产品口径。
- 技术信息折叠进入二级页面或详情，事实与日志不删除。

### 7.3 D：widget 与 icon

- widget 只消费既有只读投影与平台暴露的数据，不读 database handle，不在 widget 进程写正式账本。
- 分类色块按稳定 `CategoryId` 的确定性映射由 B 批完成、D 不实现；同一分类跨屏幕同色。D 的图标底色沿用现有浅色 accent 色值并保持不改。
- 不因 widget 引入新的账务状态或跨币种合并。

D 批落地（2026-11-27）：

- **widget 消费共享投影。** `LedgerWidgetSnapshotReader` 的三行金额改为 `presentationAmountText` + `PresentationSummaryLabel`：文本固定为「带符号金额 + 空格 + 币种代码」，precision 0..18，`Long.MIN_VALUE` 从其自身十进制文本渲染（precision 2 时为 `-92233720368547758.08 CNY`），不再取绝对值后夹到 `Long.MAX_VALUE`；标签统一为 `普通收入`／`净支出（含退款）`／`结余`。该文件的私有 formatter 与私有标签常量已删除，widget 已与共享显示投影同源；屏幕侧同源待 B/C 落地。RemoteViews 结构、进程 owner、generation/月份/revision 丢弃与隐藏金额路径均未改动；widget 仍不读 database handle、不写正式账本。
- **项目自有 adaptive launcher icon。** 三层 XML：P8 浅色 accent `#2B5FCB` 蓝底（color 资源）、白色账本/统一流水前景（单一描边 vector，108dp 画布，端点距画布中心不超过 30dp，6dp 圆头描边再外扩 3dp，因此整体落在 66dp 直径的遮罩安全区内）、同几何 monochrome 层供系统主题图标取色；`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 装配这三层，manifest 的 `android:icon`／`android:roundIcon` 指向它们。minSdk 34 高于 adaptive icon 的 API 26 门槛，因此不需要密度 PNG 回退；没有引入图标库、图像依赖或任何复制资产。label、动态 shortcut 与 widget 入口不变。依据：[自适应图标官方设计指南](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)。
- **验证边界。** `LedgerWidgetSnapshotReaderTest` 断言精确金额（含 `Long.MIN_VALUE`）、precision 0、统一词汇与「私有 formatter 已删除」；`LauncherIconResourceTest` 断言 manifest 图标属性、三层资源闭合、前景与 monochrome 几何一致且落在安全区、无栅格或图标库依赖。两者都是 JVM/资源解析断言；OEM launcher 的遮罩视觉与真实桌面渲染未在设备上实测，其证据留给 E 批。

### 7.4 E：验收与收口

- 在固定候选上取得 `profile=presentation` 的 PNG、语义树、配置与真实 TalkBack 证据。
- 八场景逐项有证据或登记缺证；不把 A 的配置当验收结果。
- 收口另开决定，不在本规格内宣称完成。

## 8. 非目标与残余

非目标：

- A 不实现 B–E 的界面或平台面；B–E 按各自批次交付，不得越出各批边界。
- 不改 SQL、driver、schema、迁移、账务写入或对账语义。
- 不做跨币种折算、预算、提醒、同步或 AI。
- 不重判 `P8-00`～`P8-07` 的任何既有结论；不追溯改写旧批次披露。

残余（登记，不并入任何 PASS）：

1. `profile=presentation` 的八场景屏幕尚未齐全，当前屏幕只覆盖既有面；后续批次补屏与判据。
2. 分类色板的具体色值未批准，A 只给稳定 `CategoryId` 映射 helper。
3. 技术信息折叠控件未实现，A 只冻结「事实与日志不删」的契约。
4. 占比舍入后各行之和可能不等于 `100.0%`；显示精确表并保留一位小数是冻结口径，不做强制配平。
5. `profile=presentation` 的新导航与 marker 未在设备上实跑；A 只登记配置与计划，不产生或引用任何设备证据。
6. 多币种占比的图表分母按单一币种；跨币种排序与合并仍不在范围内。
7. `QueryTransactionDetail`、`MonthlyBuckets`、`P806Folds`、`QueryRecycleBin` 四处对重复 `posting_account_id` 仍按 `associateBy` last-wins；本批 A 的投影按稳定 id 文本 first-wins，四处生产文件不在 A 修改，统一留给 C 批（不扩大本批修改面）。
