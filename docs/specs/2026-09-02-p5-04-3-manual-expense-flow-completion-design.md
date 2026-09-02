# P5-04.3 完善既有手工支出流程 — 实施规格

## 0. 授权与边界

- 授权：D-121 P5-04 规划授权「P5-04.3 完善既有手工支出流程，不新增收入、转账或借贷正式类型」（`docs/DECISIONS.md` D-121「P5-04 规划授权」段）+ 用户指令 2026-09-02 启动本批规格起草。`docs/ROADMAP.md` P5-04.3 句与 `docs/CURRENT_STATE.md` P5-04.3 句同源。
- 边界：零 DDL、零 schema 变更（schema v27 与 26 个迁移文件不变）；零新第三方依赖、零新 Gradle 模块；不新增收入/转账/借贷正式交易类型；不引入导航库；不改变 P5-03/P5-04.1/P5-04.2 已交付的账务语义、提交编排语义（D-119 冻结的恢复顺序）与「权威刷新恒回首页」不变量；不接入主皮库/Backdrop/Liquid Glass/SDK 升级（P6 范围）；不引入 compose ui-test harness。
- 既有 `P503*` 类型命名保持不变（本批扩展同一状态机家族；`UnknownCommit` 由 data object 调整为 data class 属本批声明的行为延伸，见 §5/§11）。
- 范围四项（主代理研判给定，本规格逐项完整规格化）：R1 编辑页可见关闭入口；R2 桌面端编辑流退出对等；R3 确认页人类可读文案；R4 UnknownCommit 核对闭环。

## 1. 现状缺口（调研证据）

- 编辑页无可见关闭操作：`P503EditScreen` 仅有字段、「继续」按钮与横幅（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt:47-163`），关闭只依赖系统返回（Android）或退出应用（桌面）。
- 桌面端未接返回钩子：`P503App` 已暴露 `backHandler` 平台钩子（`P503App.kt:47`），Android 已接线（`android-app/src/main/kotlin/com/unifiedledger/android/App.kt:77`），desktop `Main.kt:85` 调用 `P503App(facade, onExit)` 未传 `backHandler`——桌面编辑流中 Esc/返回无任何编辑流退出语义（P5-04.2 规格非目标先例明示桌面 Esc 映射归后续批次）。
- 确认页显示原始 id：`P503ConfirmationScreen.kt:35-36` 直接渲染 `draft.paymentAccountId?.value` 与 `draft.categoryId?.value`；`ManualExpenseOptions` 的 `PaymentAccountOption.label`/`ExpenseCategoryOption.label` 显示名通道已存在（`ledger-application/.../ManualExpenseOptionsProvider.kt:20-31`），确认页未使用。
- UnknownCommit 死端：`(Submitting, SubmissionResult(UnknownCommit))` 落入无载荷 `P503AppState.UnknownCommit`（`P503Reducer.kt:180`），该状态吸收一切事件（`P503Reducer.kt:45`），屏幕呈永久 spinner 且无任何操作（`P503ResultScreen.kt:32,40-42`）；核对所需的 draft/requestId 在进入该状态时丢失。已装配的 `ResolveManualExpenseCommitStatus`（`P503LedgerFacade.kt:27`）未被该路径复用。
- `ResolveManualExpenseCommitStatus.resolve(ledgerId, requestId, attempted)`（`ResolveManualExpenseCommitStatus.kt:30-47`）冻结四态 `MatchingReceipt/SnapshotConflict/Absent/Unavailable`，数据库异常映射 `Unavailable`（`:37-39`）；`ExecuteManualExpenseSubmission` 在返回 UnknownCommit 前已内部执行过一次该核对（`ExecuteManualExpenseSubmission.kt:83-101`），其 attempted snapshot 构造为私有（`:104-116`）。

## 2. R1 编辑页可见关闭入口（行为规格）

- `P503EditScreen` 新增可选参数 `onClose: (() -> Unit)? = null`：非 null 时在标题「新增手工支出」同行尾渲染可见「关闭」`TextButton`，并以 `minimumInteractiveComponentSize()` 保证 ≥48dp 触达；null 时完全不渲染（沿用既有 `onContinue: (() -> Unit)?` 的显隐先例）。
- 点击「关闭」dispatch 既有 `P503UiEvent.Back`，不新增状态、不新增事件；语义与 P5-04.2 系统返回完全一致：回来源 Tab、丢弃草稿（reducer 既有过渡行不变，`P503Reducer.kt:118-119`）。
- `P503App` 接线：`Editing`、`RequestIdentityConflict`、`DomainRejected` 三个使用 `P503EditScreen` 的分支传入 `onClose = if (current.overview != null) ({ dispatch(P503UiEvent.Back) }) else null`（与 `editFlowBackEnabled` 的 overview 非空门一致；null-overview 仅存在于直接构造，运行流不可达）。
- 不加关闭确认对话框（丢弃语义已由 P5-04.2 固定）；具体视觉样式（颜色/图标化）归 P6。
- `AwaitingConfirmation` 屏不新增关闭入口（已有「取消」= 回编辑保留草稿；系统返回=关闭丢弃，语义区分维持 D-125）。

## 3. R2 桌面端编辑流退出对等（平台接线）

- desktop-app 组合根（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt`）提供与 Android 等价的 `backHandler` 实现：`P503App(facade, onExit = onExit, backHandler = { enabled, onBack -> DesktopEscBackHandler(enabled, onBack) })`。
- `DesktopEscBackHandler(enabled, onBack)` 为 desktop-app 内私有 `@Composable`：`enabled` 为真时通过 JDK `java.awt.KeyboardFocusManager.addKeyEventDispatcher` 注册 Esc 拦截——`KEY_PRESSED` 且 `keyCode == VK_ESCAPE` 时消费事件并调用 `onBack`；`enabled` 为假不消费任何按键。注册/注销经 `DisposableEffect(enabled)` 生命周期管理，`onBack` 经 `rememberUpdatedState` 保持最新。
- 语义对等：`enabled`/`onBack` 即 `P503App` 既有钩子参数（`P503App.kt:63-76`）——`enabled` = 编辑流各态且 overview 非空；`onBack` 在 `Submitting` 吞返回。故 Esc 行为与 Android 系统返回逐态等价（编辑流各态关闭回来源 Tab；Submitting 中无效）。
- 不改变桌面窗口关闭行为：`Window.onCloseRequest`/`onExit` 路径不变；非编辑流状态 Esc 不注册拦截（与现状一致：Esc 无行为）。
- 非 Esc 按键原样放行：dispatcher 对非 Esc 事件返回 false，不做任何拦截、读取或记录（隐私明示；处理器仅比较事件类型与键码）。
- 线程模型：`KeyEventDispatcher.dispatchKeyEvent` 回调发生在 AWT 事件分发线程（EDT），Compose Desktop 的 UI 线程即 EDT，`onBack` 内直接触碰 Compose state 安全，不得引入额外线程跳转或协程切换。
- 全局性披露：`addKeyEventDispatcher` 注册于 JVM 级 `KeyboardFocusManager`，enabled 期间会消费本进程任何窗口的 Esc；当前桌面应用为单窗口且编辑流无对话框，风险可接受。
- Esc 双发守卫：共享 `onBack` 通道仅在 dispatch 时状态仍处于 Back 合法编辑流态（`Editing`/`AwaitingConfirmation`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure(SUBMISSION)` 且 overview 非空）才 dispatch `Back`，其余（含 `Submitting` 与已离开编辑流）忽略——防止连按 Esc 的第二次 Back 落入 `OverviewEmpty` 触发 unhandled ISE；Android 系统返回经同一通道同等受益（P5-04.2 既有形态的顺手加固，单次返回行为不变）。
- app-ui 不引入 `java.awt`；实现全部落在 desktop-app jvmMain。零新依赖（`java.awt` 为 JDK 标准 API）。API 选型证据见 §11 披露 5。

## 4. R3 确认页人类可读文案（状态/事件级规格）

- `P503UiEvent.Continue` 扩展两个带默认值的可选字段：`paymentAccountLabel: String? = null`、`categoryLabel: String? = null`（既有 `Continue(requestId1)` 位置构造零破坏）。
- `P503AppState.AwaitingConfirmation` 追加两个带默认值字段（置于 `originTab` 之后）：`paymentAccountLabel: String = ""`、`categoryLabel: String = ""`（既有 4 参构造零破坏）。
- reducer `Continue` 有效分支（`P503Reducer.kt:109-111`）计算显示名（回退链在 reducer 内，纯可测）：
  - `paymentAccountLabel = event.paymentAccountLabel ?: state.draft.paymentAccountId?.value ?: ""`
  - `categoryLabel = event.categoryLabel ?: state.draft.categoryId?.value ?: ""`
  - 终值 `""` 为防御性不可达（`Continue` 通过校验后两 id 非空）。无效分支（`state.copy(requestId = ...)`）不变。
- `P503App` Editing 分支 `onContinue`（`P503App.kt:186-191`）dispatch 时从 `ManualExpenseOptions` 解析显示名：`options.paymentAccounts.firstOrNull { it.accountId == current.draft.paymentAccountId }?.label` 与 `options.expenseCategories.firstOrNull { it.categoryId == current.draft.categoryId }?.label`（选中的 id 不在选项内时传 null，由 reducer 回退 id 原值）。
- `P503ConfirmationScreen` 参数由 `draft` 直接渲染改为渲染 `paymentAccountLabel`/`categoryLabel`（`P503App.kt:193-207` 传 `current.paymentAccountLabel`/`current.categoryLabel`）；「金额」「发生时间」两行不变；`currencyCode` 维持既有渲染期解析（`P503App.kt:196-201`），不改。确认页不再直接渲染 `draft.paymentAccountId?.value`/`draft.categoryId?.value`。
- `Cancel`/`Confirm`/各提交结果过渡不携带显示名（`Editing` 等状态无此字段），仅 `AwaitingConfirmation` 承载；再次 `Continue` 按当时选项重算。
- 显示名内容现状与升级边界见 §11 披露 1。

## 5. R4 UnknownCommit 核对闭环（状态/事件级规格）

### 5.1 状态与事件

- `P503AppState.UnknownCommit` 由 `data object` 调整为 data class（字段带默认值，沿 `InfrastructureFailure` SUBMISSION 的 nullable+checkNotNull 先例）：

```kotlin
data class UnknownCommit(
    val draft: ManualExpenseDraft? = null,
    val requestId: RequestId? = null,
    val overview: LedgerCurrentState? = null,
    val originTab: P503Tab = P503Tab.HOME,
    val lastCheckOutcome: UnknownCommitCheckOutcome = UnknownCommitCheckOutcome.NONE,
) : P503AppState

enum class UnknownCommitCheckOutcome { NONE, ABSENT, UNAVAILABLE }
```

- `P503UiEvent` 新增两个事件：
  - 用户事件 `data object RetryCommitStatusCheck`（手动重新核对）。
  - 异步结果事件 `data class CommitStatusResolved(val resolution: ManualExpenseCommitResolution)`（载荷直接使用 application 层冻结四态，app-ui 已有引用 application sealed 类型的先例）。
- reducer 顶层 `UnknownCommit` 分支由无条件吸收（`P503Reducer.kt:45`）改为 `reduceUnknownCommit`，过渡表：

| 源 | 事件 | 目标 |
| --- | --- | --- |
| Submitting | SubmissionResult(UnknownCommit) | `UnknownCommit(draft, requestId, overview, originTab)`（原无载荷对象；携带流上下文） |
| UnknownCommit | CommitStatusResolved(MatchingReceipt) | `Recovered`（既有 transient；随后权威刷新回 `OverviewEmpty(HOME)`） |
| UnknownCommit | CommitStatusResolved(SnapshotConflict) | `RequestIdentityConflict(checkNotNull(draft), checkNotNull(requestId), overview, originTab)`（既有冲突屏；可 Update*/AbandonConflict/Back） |
| UnknownCommit | CommitStatusResolved(Absent) | `state.copy(lastCheckOutcome = ABSENT)`（停留，可重试） |
| UnknownCommit | CommitStatusResolved(Unavailable) | `state.copy(lastCheckOutcome = UNAVAILABLE)`（停留，可重试） |
| UnknownCommit | RetryCommitStatusCheck | `state`（同实例，幂等；host 在点击处理器内直接发起核对，沿 `RetrySubmission`/`RetryRefresh` 先例） |
| UnknownCommit | 其余全部既有事件 | 吸收（返回 `state` 原实例；含 Back/SelectTab/RetrySubmission/StartNewExpense/Continue/RefreshResult/RefreshFailed 等，P5-04.2 钉住不变） |

- 简报映射句「Created→Created 屏、NoChange→NoChange」的落实说明见 §11 披露 2。

### 5.2 host 接线（P503App）

- 抽取共享构造 `manualExpenseSaveInput(draft, requestId): ManualExpenseSaveInput?`（输入不完整返回 null），`submit()`（`P503App.kt:89-140`）改用之；保证核对快照与提交快照逐字段一致（`ResolveManualExpenseCommitStatus` 逐字段比较含空 note，`ResolveManualExpenseCommitStatus.kt:8-13`）。`submit()` 的 null 分支必须保留既有防御性 `InvalidInput` dispatch（`P503App.kt:99-124`），不静默搁置 `Submitting`。
- 新增 `checkCommitStatus(draft, requestId)`：`scope.launch` 内以 `facade.resolveCommitStatus.resolve(facade.ledgerId, requestId, attempted)` 执行只读核对并 dispatch `CommitStatusResolved(resolution)`；`attempted = ManualExpenseRequestSnapshot(...)` 由同一 `manualExpenseSaveInput` 字段构建（`ConfirmedManualExpense.kt:40-47`）；输入不完整时静默返回。该静默返回分支在守卫通过后不可达，属纯防御：调用侧守卫（draft/requestId 非空）通过且 UnknownCommit 仅经校验通过的 `AwaitingConfirmation` 到达，草稿四字段完整，共享构造不会返回 null。
- 进入自动核对恰好一次：扩展现有 `LaunchedEffect(state)`（`P503App.kt:152-157`）——`current is UnknownCommit && current.lastCheckOutcome == NONE && draft/requestId 非空` 时执行 `checkCommitStatus`。停留结果（Absent/Unavailable）写入 `lastCheckOutcome` 后实例变化但守卫不满足，不重跑；`RetryCommitStatusCheck` 返回同实例亦不重跑，手动核对由点击处理器直接调用（沿 `P503App.kt:255-259` 先例）。
- 单飞守卫：host 以 in-flight 标记忽略并行的新核对请求，防止竞态下向已离开 UnknownCommit 的状态 dispatch `CommitStatusResolved`（reducer 对该事件在其余状态无转移行，unhandled 抛 ISE）。
- LedgerClock：`resolve()` 无时间参数，核对路径不使用 `LedgerClock`（facade 成员保持原样，`P503LedgerFacade.kt:30`）。
- `P503App.kt:209-213` 的结果屏分派拆出 `UnknownCommit`：Created/NoChange/Recovered 维持 `P503ResultScreen`；UnknownCommit 使用专用呈现（建议新私有 composable，与结果屏同文件或 `P503App.kt` 内，由实施者定）。

### 5.3 UnknownCommit 屏呈现

- `lastCheckOutcome == NONE`：`CircularProgressIndicator` +「提交结果未知，正在核对中…」（liveRegion Assertive 维持；表示核对在途）。
- `ABSENT`：「提交结果未知：账本中未找到该提交的入库记录。」+「重新核对」按钮。
- `UNAVAILABLE`：「提交结果未知：核对暂不可用（本地数据库读取失败）。」+「重新核对」按钮。
- 文案不得表述为成功或失败（Absent 不能证明回滚，D-119）；按钮触达 ≥48dp；无自动提交重试、无乐观刷新、requestId 全程不变（`UnknownCommit` 禁止自动重试的 D-120/D-121 边界不因本批改变——只读核对与手动重核均不触碰 `commitOnce`）。

## 6. 平台与文件落点汇总

- `app-ui`：`P503UiEvent.kt`（Continue 默认参数、新增 2 事件）、`P503AppState.kt`（UnknownCommit 数据类化 + 新 enum、AwaitingConfirmation 2 字段）、`P503Reducer.kt`（Continue 回退链、Submitting 入口行载荷、reduceUnknownCommit）、`P503App.kt`（onClose 接线、显示名解析、onBack 双发守卫、checkCommitStatus/单飞/LaunchedEffect 扩展、UnknownCommit 分支拆出）、`P503EditScreen.kt`（onClose + 关闭按钮）、`P503ConfirmationScreen.kt`（显示名渲染）、`P503ResultScreen.kt`（UnknownCommit 专用呈现）。
- `desktop-app`：`Main.kt`（backHandler 接线 + `DesktopEscBackHandler`）。
- `android-app`：零改动（backHandler 已接线）。
- `ledger-application`/`ledger-domain`/`ledger-data`：零改动。零 DDL/零新依赖/零新正式交易类型。

## 7. 无障碍

- 「关闭」按钮具备文本语义「关闭」，`minimumInteractiveComponentSize()` ≥48dp 触达；与既有字段错误 semantics 关联约定（D-120 落点 4）同层。
- UnknownCommit 屏在途文案 liveRegion Assertive 维持；停留态文案变更可被读屏感知；「重新核对」按钮文本语义明确。
- Esc 关闭对键盘用户即编辑流退出路径；桌面无系统返回概念，Esc 即对等物（本批新增，此前编辑流在桌面无键盘退出路径）。

## 8. 测试计划与验收判据映射

纯 reducer/validation 测试为主（`:app-ui:jvmTest`；风格沿 `reduceFrom`/`assertIs`/`assertEquals`/`assertFailsWith`）。计数双口径：`P503ReducerTest` 既有 30 个 @Test → 预期 41；`:app-ui:jvmTest` 模块合计 32 → 预期 43（43−2=41，差额为 `P503DraftValidationTest` 2 条，零改动）。预期新增 11 条（T-01..T-11）、机械更新 3 条。`:desktop-app:jvmTest` 4 条零改动（P503App 不在其覆盖内）。

新增测试（`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/P503ReducerTest.kt`）：

| 编号 | 名称 | 断言要点 |
| --- | --- | --- |
| T-01 | continueCarriesHostResolvedDisplayLabelsIntoAwaitingConfirmation | 带 label 的 `Continue` → AwaitingConfirmation 两 label 字段原值携带，overview/originTab 穿线不变 |
| T-02 | continueWithoutLabelsFallsBackToDraftIdValues | `Continue(requestId)` → label 字段 = 草稿 id 原值（`asset-payment-local`/`expense-category-breakfast`） |
| T-03 | unknownCommitEntryCarriesFlowContextWithPendingCheckOutcome | `(Submitting(.., overview, tab), SubmissionResult(UnknownCommit))` → UnknownCommit(draft, requestId, overview, tab)，lastCheckOutcome=NONE |
| T-04 | unknownCommitCheckMatchingReceiptRecoversThenRefreshesToOverview | MatchingReceipt → `Recovered`；`RefreshResult` → `OverviewEmpty(.., HOME)` |
| T-05 | unknownCommitCheckSnapshotConflictEntersConflictScreenWithContext | SnapshotConflict → RequestIdentityConflict(checkNotNull 载荷 + overview/originTab)；再 `Back` → `OverviewEmpty(overview, tab)` |
| T-06 | unknownCommitCheckAbsentStaysWithRecordedOutcome | Absent → 上下文字段不变、lastCheckOutcome=ABSENT |
| T-07 | unknownCommitCheckUnavailableStaysWithRecordedOutcome | Unavailable → 同上（UNAVAILABLE） |
| T-08 | unknownCommitRetryCheckIsIdempotentSameInstance | `RetryCommitStatusCheck` → 同一实例（===/equals）；停留态重复重试仍同实例 |
| T-09 | unknownCommitStillAbsorbsLegacyEvents | Back/SelectTab/RetrySubmission/RetryRefresh/Continue/StartNewExpense/RefreshResult/RefreshFailed/SubmissionResult(UnknownCommit) 重入 → 吸收为同值 UnknownCommit（新增的汇总吸收面测试；既有两条吸收测试机械更新后保留） |
| T-10 | unknownCommitSnapshotConflictWithoutContextFailsFast | 缺省构造 `UnknownCommit()` 收 SnapshotConflict → `checkNotNull` 抛 ISE（防御行） |
| T-11 | commitStatusResolvedOutsideUnknownCommitFailsFast | `(Recovered, CommitStatusResolved(MatchingReceipt(..)))` → `assertFailsWith<IllegalStateException>`（该事件仅 UnknownCommit 有转移行，其余状态均 unhandled） |

机械更新（构造/断言改用数据类实例，行为断言不削弱）：`unknownCommitForbidsRetryRefreshAndNewRequestId`、`unknownCommitAbsorbsSelectTab`、`unknownCommitAbsorbsBack`（吸收语义本身不变）。

验收判据（A1..A8）与映射：

| 判据 | 行为 | 测试/证据 | 命令 |
| --- | --- | --- | --- |
| A1 | R1：编辑流三态（overview 非空）可见「关闭」，行为与系统返回一致；null 不渲染 | 状态机行为由既有 Back 测试组承载（无新增）；可见性/触发由双端人工门 | `:app-ui:jvmTest` + 人工门 |
| A2 | R2：桌面 Esc 仅在 enabled 时关闭编辑流回来源 Tab；Submitting 无效；非编辑流无行为；窗口关闭不变；连按 Esc 由双发守卫忽略（不落 unhandled ISE） | 桌面回归 + 桌面 Esc 人工门（含连按 Esc 实测） | `:desktop-app:jvmTest` + 人工门 |
| A3 | R3：显示名快照携带、缺失回退 id 原值、确认页不渲染 draft id | T-01/T-02；确认页渲染由人工门（当前目录下可见文本与现状一致，见披露 1） | `:app-ui:jvmTest` |
| A4 | R4 入口载荷：UnknownCommit 携带 draft/requestId/overview/originTab，初始 NONE | T-03 | `:app-ui:jvmTest` |
| A5 | R4 映射：MatchingReceipt→Recovered、SnapshotConflict→RIC、Absent/Unavailable→停留并记录 | T-04/T-05/T-06/T-07 | `:app-ui:jvmTest` |
| A6 | R4 手动重试：幂等同实例、可反复重核 | T-08 | `:app-ui:jvmTest` |
| A7 | R4 钉住：既有事件维持吸收、Submitting 吞返回不变、无提交自动重试/乐观刷新/换 requestId | 更新后的 3 条既有测试 + T-09/T-10/T-11 | `:app-ui:jvmTest` |
| A8 | R4 自动核对一次性：进入后恰一次只读核对，不触发提交重试 | reducer 侧 T-03/T-06/T-07；host 时序（一次性/单飞）由实施代码评审 + 人工门承载（无 compose ui-test harness，明确不在纯 reducer 测试范围） | `:app-ui:jvmTest` + 评审 |

## 9. 验证命令集

按 `docs/CONTRIBUTING.md`「本机 Gradle 资源限制」串行执行；资源限制旗标与 `--stop` 包裹已直接写入命令块（每条 Gradle 验证前后 `--stop`，一次只运行一个命令）；任务集与 CI 步骤一致，本机旗标仅资源控制、不改变 CI 验证语义：

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :desktop-app:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :desktop-app:ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop

$env:PYTHONPATH="tools\python"
python -m project_docs .
```

APK 产物验证按已登记 R-9 由授权 push 后的 CI artifact 承担；聚合 `check` 按已登记 R-17 归 CI。

## 10. 非目标

时间/日期选择器（归 P6 或后续批次）；顶栏与看板（D-123 推迟，需求定义后另立决定）；草稿自动保存；编辑既有交易；新收入/转账/借贷正式类型（D-121 边界）；导航库选型；视觉改版/主题（P6）；StartupError 重试接线（平台根所有）；schema/DDL；P5-03 账务与状态边界改动；P5-04.4 的 fail-closed 重试策略重排（本批只读核对与手动重核不预支其授权）；账户明细下钻与分析维度扩展（P5-04.1 非目标先例）；P5-04.5 回归收口；UnknownCommit 的 Back/退出语义变更（维持吸收，如需改变另立裁决）。

## 11. 披露与风险

1. **R3 显示名来源现状**：域 `Account`/`Category` 无显示名字段；`QueryManualExpenseOptions` 的 `label` 派生自 id 原值（`ManualExpenseOptionsProvider.kt:60,76`）。本批不改该派生、不改域类型、零 schema；「确认页不再直接渲染 draft id」的契约经 label 通道落地，当前固定目录下可见文本与现状相同（label==id 值），显示名内容升级需目录显示名来源扩展，属后续批次另立授权。
2. **R4 简报映射句落实**：按 D-119 冻结的 resolve 四态落实为 MatchingReceipt→`Recovered` 屏、SnapshotConflict→`RequestIdentityConflict` 屏；`resolve()` 不产生 Created/NoChange（那是 `commitOnce` 结果变体，核对路径不触碰提交）。恢复成功的最终体验与 Created 一致（权威刷新回总览显示该交易）；若裁决改用「恢复成功显示 Created 文案」属呈现层微调，可在评审时定。
3. **UnknownCommit 形状变化**：data object → data class（默认值兼容），P5-04.2 规格过渡表「UnknownCommit 维持吸收一切事件（含 Back）」的文字快照由本批有意延伸——新增两事件有转移行、其余事件吸收语义不变；P5-04.2 的三条相关测试机械更新构造形式。
4. **UnknownCommit 的 Back/系统返回维持吸收**（Android 上该屏系统返回仍退出应用）：属 P5-04.2 已钉住行为，本批不改；如需编辑流退出对等覆盖该屏，须用户裁决。
5. **R2 API 选型证据**：CMP 1.11.1 解析构件含 `androidx.compose.ui.backhandler.BackHandler`（`ui-backhandler-desktop 1.11.1` 在本机 Gradle 缓存中存在），但其桌面触发源（Esc→back dispatch）在 `desktop-jvm`/`ui-desktop`/`navigationevent-desktop` 已解析构件的常量池扫描中未见实现证据，本批不依赖该 API；改用组合根 JDK `KeyboardFocusManager` 显式 Esc 处理（零新依赖、语义确定）。后续 CMP 提供官方桌面 back 触发时可替换实现，语义不变。
6. **单飞守卫与竞态**：停留态重复点击重核可并发发起只读核对；host 单飞忽略并行请求，避免向已离开 UnknownCommit 的状态 dispatch 造成 unhandled ISE。核对为只读 `resolve`，并发本身无写入风险。
7. **外部证据门结论**：本批不新增第三方依赖、不改账务/迁移/安全语义；桌面键盘接线仅用 JDK 标准 API，`docs/SOURCE_REFERENCES.md` 与 `.external/requirements/` 现有登记无需扩充。

## 12. 文档与决定登记

- 规格落盘 `docs/specs/2026-09-02-p5-04-3-manual-expense-flow-completion-design.md`（本文件）。
- `DECISIONS.md` 新增 D-126（批准依据 = D-121 规划授权 + 用户启动指令；引用本规格冻结 SHA-256 与规格评审 disposition；实施登记段由合并后状态同步提交补全）。
- 预计零 README/PROJECT_MAP/ARCHITECTURE 变更（无新模块、无新依赖、无新验证入口）；CURRENT_STATE/ROADMAP 状态更新放合并后提交（主代理）。
