# P5-04.1 三 Tab 与中央新增入口 — 实施规格

规格评审：2026-09-02，独立评审 disposition APPROVE-WITH-FINDINGS（发现 P041S-R1..R9 全部闭环；残留 info P041S-N1 已并入 §2.1）。

## 0. 授权与边界

- 授权：D-121 P5-04 规划授权（docs/DECISIONS.md）+ 用户指令 2026-09-02「做P5-04.1」+ 推荐方案常设批准（不 push 除外）。
- 边界：不引入导航库；不新增第三方依赖或 Gradle 模块；零 DDL、零 schema 变更（schema v27 不变）；不新增正式交易类型；不改变 P5-03 账务与状态边界；不接入主皮库/Backdrop/Liquid Glass/SDK 升级（P6 范围）；不引入 compose ui-test harness。
- 既有 `P503*` 类型命名保持不变（本批扩展同一状态机家族，重命名超范围）。

## 1. 状态机变更（app-ui commonMain）

- 新增 `enum class P503Tab { HOME, ACCOUNTS, ANALYSIS }`。
- `P503AppState.OverviewEmpty` 增加 `selectedTab: P503Tab` 字段，默认 `HOME`；`state: LedgerCurrentState` 不变。既有 `OverviewEmpty(state)` 构造点因默认值零破坏。
- `P503UiEvent` 新增用户事件 `SelectTab(val tab: P503Tab)`。
- reducer 规则（`P503ReducerImpl`）：
  - `(OverviewEmpty, SelectTab)` → `state.copy(selectedTab = event.tab)`（同一 `LedgerCurrentState` 引用，无数据重取）。
  - `InitialLoadResult` → `OverviewEmpty(currentState, HOME)`；`RefreshResult` → `OverviewEmpty(currentState, HOME)`（权威刷新恒回首页；提交流程各状态不携带 tab）。
  - 未列 (state, event) 组合沿既有 `unhandled()` 路径抛 `IllegalStateException`；`UnknownCommit` 维持对一切事件（含 `SelectTab`）的吸收行为。
  - 其余 13 个用户事件、7 个异步结果事件与全部其他状态的行为不变。
- 中央新增入口复用既有 `StartNewExpense` 事件与进入 `Editing` 的转移，不改转移语义，仅入口 UI 迁移（§2）。

## 2. UI 壳（app-ui）

- `OverviewEmpty` 渲染重构为 material3 `Scaffold`：
  - `bottomBar`：`NavigationBar`，三个 `NavigationBarItem`（顺序 HOME/ACCOUNTS/ANALYSIS，label 文案 首页/账户/分析，selected = `selectedTab`，onClick dispatch `SelectTab`）。icon 槽一律渲染标签首字 `Text`（首/账/析）——不引用任何 material-icons 构件（material3 1.9.0 无 icons 传递依赖，已核实），不新增模块。
  - `floatingActionButton`：`FloatingActionButton` 内容为 `Text("+")` 并追加 `contentDescription = "新增支出"`，`fabPosition = FabPosition.Center`，onClick dispatch `StartNewExpense`；空态与非空态常显。
  - 内容区按 `selectedTab` 切换三个内容 composable（文件归档由实施者决定，建议新文件或按屏分文件）：
    - **首页**：迁移现 `P503OverviewScreen` 内容（账本标题、当前交易列表、账户余额区，全部沿用既有渲染与 `formatMinorUnits`）；删除原两处「新增支出」按钮。
    - **账户**：数据源仅为 `state.balances`（权威读取载荷；有分录的账户，零余额/无活动账户不出现，本批接受），逐行渲染 accountId + currency code + `formatMinorUnits(displayMinorUnits)`，并用 `facade.catalog.accounts` 自建 id→Account 映射补充账户 kind 文案；不使用 internal `LedgerCatalog.account(id)`；balances 为空显示空态文案；不做按账户明细（需新查询，超本批范围）。
    - **分析**：调用 `facade.summarizeActivity.summarize(state)`（纯函数，无 IO）渲染：交易总笔数、按交易 kind 笔数、按币种支出/收入合计（`formatMinorUnits` 原样渲染，含负值）；无交易显示空态文案；UI 不自行累计金额。
- 显式接线改动：`P503App.kt` 的 `OverviewEmpty` 分支传递含 `selectedTab` 的完整状态、分发 `SelectTab`、向内容区传 facade 所需成员（catalog、summarizeActivity）；`P503OverviewScreen` 签名随内容化变更并删除两处「新增支出」按钮。
- 其余状态（Editing/AwaitingConfirmation/Submitting/Created/NoChange/Recovered/RequestIdentityConflict/DomainRejected/InfrastructureFailure READ|SUBMISSION/UnknownCommit/Starting/Ready/StartupError）渲染与路由不变，全屏覆盖、无底栏。

### 2.1 无障碍

沿用 D-120 实施落点 4 的共享 UI 约定：`NavigationBarItem` 选中态即 `selectedTab` 语义；组件默认触达面积 ≥48dp，不缩小；中央 FAB 具备明确文本语义（`contentDescription = "新增支出"`）；不引入新 liveRegion；不改变既有屏幕语义结构。

## 3. application 纯派生（ledger-application commonMain）

- 新增 `SummarizeLedgerActivity`（构造注入 `LedgerCatalog`），`fun summarize(currentState: LedgerCurrentState): LedgerActivitySummary`；不引用 `java.lang.Math`（commonMain 无 JDK 类路径）。
- `LedgerActivitySummary`：`totalTransactionCount: Int`、`countByKind: Map<TransactionKind, Int>`、`totalsByCurrency: List<CurrencyActivityTotal>`；`CurrencyActivityTotal(currency: CurrencyUnit, expenseMinorUnits: Long, incomeMinorUnits: Long)`；`totalsByCurrency` 按币种 code 升序、precision 次序键确定性排序（沿 `QueryLedgerCurrentState` 排序风格）。
- 语义（只用既有事实，不发明新账务语义）：
  - 每笔 current-version 交易计数 1 并按交易 `kind` 归入 `countByKind`。
  - posting 级金额归集按 catalog `Account.kind`：EXPENSE 账户 posting 以 ledger-signed 原号计入该币种 `expenseMinorUnits`；INCOME 账户 posting 取反计入 `incomeMinorUnits`（与 `QueryLedgerCurrentState` displayMinorUnits 的 normal-balance 符号规则一致）；其他 kind 账户（如 ASSET）不计入任何金额。
  - 精确 Long 累加与取反使用文件私有 `checkedAdd`/`checkedNegate`（返回 `Long?`，模式同 `QueryLedgerCurrentState` 手写先例），null 时抛 `ArithmeticException`（fail-closed，覆盖 `Long.MIN_VALUE` 取反）。
  - catalog 中不存在的 accountId 抛 `IllegalStateException`（纯函数不静默兜底；两组合根以同一 catalog 实例注入，生产路径为防御性不可达）。
  - 显示语义：合计可因 REFUND_RECEIPT 等事件为负，经 `formatMinorUnits` 原样渲染；UI 层禁止截零、取绝对值或重定符号。

## 4. facade 与组合根

- `P503LedgerFacade` 增加只读成员 `val summarizeActivity: SummarizeLedgerActivity`（第 11 个成员）；「UI 只经 facade 触达 application 类型」铁律不变。
- Android `App.kt` 与 Desktop `Main.kt` 各构造一次，与既有 catalog 同一实例；两根 fixture catalog 与数据库引导零改动。

## 5. 测试

- `P503ReducerTest` 新增：`InitialLoadResult`→`OverviewEmpty(.., HOME)`；`SelectTab` 三向切换与来回切换；`RefreshResult`→HOME；`Editing` 收 `SelectTab` 抛 `IllegalStateException`；`UnknownCommit` 吸收 `SelectTab`。风格沿 `reduceFrom`/`assertIs`/`assertEquals`。
- ledger-application 新增 `SummarizeLedgerActivityTest`：空 state 全零；单笔手工支出（expense +3580/payment -3580 → CNY expense=3580、income=0、`countByKind[EXPENSE]=1`、total=1）；INCOME 交易计入 income（取反为正）；ACCOUNT_TRANSFER（双 ASSET 分录）计数 1 且金额不计入；多币种分币种合计且按 code 升序；未知 accountId 抛 `IllegalStateException`；INCOME posting 为 `Long.MIN_VALUE` 时抛 `ArithmeticException`。
- desktop `DesktopSkeletonSmokeTest`：既有断言不削弱，扩展提交后 summary 断言（CNY expense=3580）。
- 不引入 compose ui-test harness。验证命令：`:app-ui:jvmTest`、`:ledger-application:jvmTest`、`:desktop-app:jvmTest`、`:android-app:assembleDebug`。

## 6. 文档与决定登记

- 本规格落盘 `docs/specs/2026-09-02-p5-04-1-three-tab-shell-implementation-design.md`。
- `DECISIONS.md` 新增 D-122（批准依据 = 用户指令 2026-09-02 + D-121 规划授权 + 推荐方案常设批准；引用本规格冻结 SHA-256 与规格评审 disposition；实施登记段由合并后状态同步提交补全）。
- README、PROJECT_MAP、ARCHITECTURE 做事实性同步；CURRENT_STATE/ROADMAP 状态更新放合并后提交（主代理）。

## 7. 非目标

账户明细下钻；月份/时区/分类维度分析；编辑页系统返回/确认/取消行为改造（P5-04.2）；手工支出流程完善（P5-04.3）；回归收口（P5-04.5）；主皮库/Backdrop/Liquid Glass/SDK 升级（P6）；导航库选型。
