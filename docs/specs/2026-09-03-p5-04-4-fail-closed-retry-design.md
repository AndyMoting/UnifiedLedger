# P5-04.4 fail-closed 重试策略与启动/加载/提交失败状态单一来源 — 实施规格

## 0. 授权与边界

- 授权：D-121 P5-04 规划授权「P5-04.4 覆盖启动、加载、提交和失败状态并保持 fail-closed，仅 startup error 或 handoff 前可证明 `commitOnce` 零调用的 `InfrastructureFailure` 可恢复重试，`UnknownCommit`、冲突和领域拒绝禁止自动重试」（`docs/DECISIONS.md` D-121「P5-04 规划授权」段）+ 用户指令 2026-09-02 启动 P5-04.4 规格起草。P5-04.5 为 Android 与既有 Desktop 回归收口（本批不覆盖）。
- 边界：本批不新增收入/转账/借贷正式类型；不引入导航库；不新增第三方依赖或新 Gradle 模块（Android 启动测试落 JVM 单测、不引 Robolectric）；零 DDL、零 schema、零 ledger-domain/ledger-data 语义变更；不改变 P5-03/P5-04.1/.2/.3 已交付的账务与正式交易语义、提交编排语义（D-119 冻结的恢复顺序）、「权威刷新恒回首页」不变量与 UnknownCommit 吸收语义。
- 属性：本批是「显式化现状 + 补钉测试 + 资源安全」，不引入任何新形式的重试能力（自动重试在任何形式下被明确禁止）。
- 语义立场所属：本批按任务「推荐范围 S1-S5」逐项规格化；凡触及核心语义/DDL/新正式类型者按任务要求标记为 **STOP-ITEM** 并在 §12 停下报告，不静默扩大范围。

## 1. 现状缺口（调研证据，写前已逐条核实 file:line）

1. **共享 P503App 的启动状态机绝大部分不可达**：`P503AppState` 初始状态在宿主硬编码 `Ready`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:54`）；`Starting`/`StartupError` 两分支与 `Ready` 分支全部走 `P503StartupScreen(...)` 且 `onRetry = {}` 空操作（`P503App.kt:198-200`）；`StartRetry`/`StartupCompleted`/`StartupFailed` 三个启动事件（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503UiEvent.kt:18,73,75`）从未被宿主或平台根派发（全仓 `grep` 仅 reducer 定义与 reducer 测试引用）。真实启动重试在各平台根各写一套：Android `app()` 内 `controller::start`（`android-app/src/main/kotlin/com/unifiedledger/android/App.kt:71-72,89`）、desktop `DesktopRoot` 同（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:98`）。
2. **共享层启动重试状态与平台启动状态机重复**：`P503AppState.Starting`/`StartupError`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503AppState.kt:18,23）与平台 `P503StartupState.Starting/Ready/StartupError`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503StartupScreen.kt:27-34`）重叠；后者才是平台组合根实际承载的重试状态（`App.kt:86-92`、`Main.kt:95-101`）。`P503AppState.Ready` 例外：它是 reducer 的种子初始态，承载首次权威加载的消费（`P503Reducer.kt:56-62`），必须保留（见 §3）。
3. **Android 启动重试无测试**：`AndroidStartupController`（`android-app/src/main/kotlin/com/unifiedledger/android/App.kt:100-121`）无任何测试；Desktop 有对称的 `DesktopStartupControllerTest` 两条（`desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopStartupControllerTest.kt:18-58`：失败→重试→Ready、重复失败保持 fail-closed 无 graph）。
4. **重试资源安全缺口**：两端 controller 在 `start()` 重试时重置 facade 并重建驱动，但未关闭旧驱动（`AndroidSqliteDriver`/`JdbcSqliteDriver` 均每次新建）。Android 侧 `AndroidLedgerDatabaseHandle` 已实现 `AutoCloseable`（`ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:33-37`，`close()` 调 `driver.close()`），但 controller 仅持有 `P503LedgerFacade`、不持有 handle，`start()`（`App.kt:109-120`）不调用 close；Desktop 侧 `openDesktopLedger`（`Main.kt:270-274`）创建 `JdbcSqliteDriver` 后仅返回 `facade`，driver 引用在返回时丢失（`DesktopLedgerGraph` 持有 database 但不持 driver，`Main.kt:164-172,205`），`start()`（`Main.kt:149-161`）无 close 路径。失败中途已打开的驱动同样泄漏。
5. **commitOnce handoff 边界语义仅 KDoc**：「进入 `commitOnce` 入口即算 handoff，入口前异常=可重试 `InfrastructureFailure`，入口后异常=`UnknownCommit` 解析」仅写在 `ExecuteManualExpenseSubmission.kt:9-24` KDoc 且由 `CommitOnceInvocationTracker` 置位机制承载（`ExecuteManualExpenseSubmission.kt:44-63,70-81`）。测试已覆盖大部分（`preSubmitZeroCallFailureIsInfrastructureFailure`/`postHandoff*`/`secondSubmissionPreHandoffFailureIsNotContaminatedByPreviousHandoff`，`ledger-application/src/commonTest/kotlin/com/unifiedledger/application/ExecuteManualExpenseSubmissionTest.kt:99,112-171,174-219`），但「进入 `commitOnce` 入口即置位」这一判定点无显式名称性钉住（无断言确认入口调用与 `commitOnceInvoked` 置位的对应关系）。
6. **共享 host 接线无 commonTest**：`P503App` 的宿主行为（`RetryRefresh→refresh`、`RetrySubmission→submit` 同 requestId、Created 后自动权威刷新恰一次、UnknownCommit 自动核对恰一次 + `lastCheckOutcome` 守卫）仅由 reducer 纯函数测试覆盖（`P503ReducerTest.kt`），无对 host 层接线（事件分派→具体调用）的 JVM 测试。

## 2. 已达标且必须保持（钉住不回退）

- SUBMISSION 同意图重试链路：`InfrastructureFailure(SUBMISSION)` → `RetrySubmission` → 同 draft/requestId 重新 `Submitting`（`P503Reducer.kt:282-305`）。
- `CommitOnceInvocationTracker` 的 reset-per-submit + 入口置位证明机制（`ExecuteManualExpenseSubmission.kt:44-63`）及其测试（`ExecuteManualExpenseSubmissionTest.kt`：尤其 `secondSubmissionPreHandoffFailureIsNotContaminatedByPreviousHandoff` :174-219）。
- `UnknownCommit`/冲突/领域拒绝禁止自动重试（reducer 吸收 + unhandled ISE，`P503Reducer.kt:197-223,237-279`；`P503ReducerTest` 多条既有测试钉住）。
- READ 重试只读幂等：`InfrastructureFailure(READ)` → `RetryRefresh → state`（同值吸收）、成功 `RefreshResult → OverviewEmpty`（`P503Reducer.kt:306-312`）。
- `P503AppState.Ready` 入口态 + `InitialLoadResult`/`InitialLoadFailed` 转移行（`P503Reducer.kt:56-62`）与 `P503App` 首次权威加载（`P503App.kt:171-176`）。
- `UnknownCommit` 恰一次只读核对 + `lastCheckOutcome` 守卫 + 单飞（`P503App.kt:143-168,185-194`，P5-04.3 已交付）。

## 3. S1 启动重试单一来源裁决（显式化，非新功能）

### 3.1 裁决

启动重试的控制权在平台组合根（Android `AndroidStartupController`/desktop `DesktopStartupController`），各自真实接线已经存在且对称；共享 `app-ui` 不应保留一套不可达的启动重试状态机。因此**删除共享层不可达的启动重试状态**（`Starting`/`StartupError` 与启动事件），共享层只保留 `P503AppState.Ready` 作为「平台已就绪后」的种子入口态（承载首次权威加载消费，是实现上必需、运行流可达的状态）。平台 `P503StartupState`/`P503StartupScreen` 继续作为共享组件由两端组合根复用（`App.kt:86-92`、`Main.kt:95-101`）。

### 3.2 变更清单（精确）

对 `app-ui/src/commonMain`：

| 文件 | 变更项 | 证据链/理由 |
| --- | --- | --- |
| `P503AppState.kt` | **删除** 状态 `P503AppState.Starting`（:18）、`P503AppState.StartupError`（:23） | `Starting`/`StartupError` 只能由 `StartupCompleted`/`StartupFailed`/`StartRetry` 进入，而这三事件从不被派发（见下表事件行）；运行流唯一入口是 `Ready`（`P503App.kt:54`） |
| `P503AppState.kt` | **保留** `P503AppState.Ready`（:20），KDoc 更新为「platform startup concluded; the entry state that consumes the initial authoritative load」；「Exactly fourteen states」措辞同为新数（十四 → 十二） | `Ready` 是 reducer 种子态，`reduceReady` 消费 `InitialLoadResult`/`InitialLoadFailed`（`P503Reducer.kt:56-62`），运行流第一帧即执行；删除会破坏 reducer 全称性并在首次 dispatch 触发 unhandled ISE |
| `P503UiEvent.kt` | **删除** 事件 `P503UiEvent.StartRetry`（:18）、`P503UiEvent.StartupCompleted`（:73）、`P503UiEvent.StartupFailed`（:75） | 全仓 `grep`（.kt，排除 build）仅 reducer 定义（`P503Reducer.kt:51-53,66`）与 reducer 测试（`P503ReducerTest.kt:271-288`）引用；无任何宿主/平台根/业务路径派发 |
| `P503Reducer.kt` | **删除** `reduceStarting`（:33,49-54）、`reduceStartupError`（:35,64-71）及其 `Starting`/`StartupError` 源分支；**保留** `reduceReady`（:34,56-62）原样 | 源状态不可达 ⇒ 对应 reducer 分支不可达；`reduceReady` 为入口加载语义，保留 |
| `P503App.kt` | **删除** 渲染分支 `P503AppState.Starting`（:198）、`P503AppState.StartupError`（:200）（含 `onRetry = {}` 空操作）；**保留** `P503AppState.Ready` 分支（:199）原样（初始加载在途呈现，即现有 `P503StartupScreen(P503StartupState.Starting, ...)` 加载画面） | `Starting`/`StartupError` 不可达；`onRetry = {}` 空操作与平台真实重试重复定义冲突；`Ready` 分支是第一帧的加载呈现（随后 `LaunchedEffect(Unit)` dispatch 后重组成 `OverviewEmpty`/`InfrastructureFailure(READ)`） |
| `P503AppState.kt` | KDoc「Exactly fourteen states」同步为「Exactly twelve states」 | 状态数 14 − 2 = 12 |

保留：`P503StartupState` 密封接口（`P503StartupScreen.kt:27-34`）与 `P503StartupScreen` 组件——平台组合根依赖其作为失败/启动呈现与重试按钮宿主；本批不改其状态集与语义。

### 3.3 状态机结果

共享 `P503AppState` 剩十二态：`Ready`（种子入口）+ `OverviewEmpty`、`Editing`、`AwaitingConfirmation`、`Submitting`、`Created`、`NoChange`、`Recovered`、`RequestIdentityConflict`、`DomainRejected`、`InfrastructureFailure`、`UnknownCommit`。`P503App` 在组合根判定 `controller.state == P503StartupState.Ready && facade != null` 后才调用（现状如此），故共享层除 `Ready` 入口外均为「平台已就绪后」的行为状态；启动失败/重试的呈现与所有权完全归平台 `P503StartupState` + 两端 controller。

### 3.4 入口语义确认（无新增状态）

- `P503App` 进入即代表「平台启动完成」；`state` 种子为 `Ready`（`P503App.kt:54`），`LaunchedEffect(Unit)` 立即执行首次权威加载并 dispatch `InitialLoadResult`/`InitialLoadFailed`（`P503App.kt:171-176`）。
- `reduceReady` 的两条转移行维持现状：`InitialLoadResult → OverviewEmpty(HOME)`、`InitialLoadFailed → InfrastructureFailure(READ)`（`P503Reducer.kt:56-62`）；`InfrastructureFailure(READ)` 的可重试/成功路径也维持（`P503Reducer.kt:306-312`）。
- 不引入新入口态：`Ready` 即入口态，语义与 D-121「平台启动完成才是共享 UI 入口」一致。
- 不改变任何可观察行为：删除 `Starting`/`StartupError`/三事件仅移除死代码路径；`Ready` 分支的渲染与 `reduceReady` 的转移行逐字保留。

### 3.5 渲染语义

- 共享层不再渲染启动重试/失败画面（`Starting`/`StartupError` 分支删除）；所有启动/失败/重试呈现均由两端组合根 + `P503StartupScreen`（平台 `P503StartupState`）负责。
- `Ready` 分支保留现有加载呈现（`P503StartupScreen(P503StartupState.Starting, ...)`，显示「正在打开本地账本…」转圈）：它是首次权威加载在途（通常一帧）的诚实呈现，且 `when` 对 sealed interface 全称性要求该分支存在。
- `P503StartupScreen` 的 `P503StartupState.Ready` 分支（`P503StartupScreen.kt:57-59`）在现状下从不被渲染（两端仅当 Ready 才调 `P503App`，不传该态给 screen）；本批不删除该分支（平台状态集保持自洽、供未来复用），仅备注其为当前未触达分支。

## 4. S2 Android 启动重试测试补钉

- 为 `AndroidStartupController`（`App.kt:100-121`）建立 JVM 单测，对称于 `DesktopStartupControllerTest`（`DesktopStartupControllerTest.kt:18-58`）。落点：`android-app/src/test/kotlin/com/unifiedledger/android/AndroidStartupControllerTest.kt`（新 test 源集），任务 `:android-app:testDebugUnitTest`。
- **不引 Robolectric**。可测性先决两项：
  1. controller 依赖可注入：`AndroidStartupController.openDatabase: () -> P503LedgerFacade` 已是注入点（`App.kt:101`），测试注入受控 lambda（首调抛异常、次调返回假 facade）即可测状态机本身，无需真 `AndroidSqliteDriver`。**签名交叉引用（P5044-S-004）**：§5 S3 将 `openDatabase` 契约升级为 `openDatabase: () -> CloseableLedgerGraph`（返回可关闭体，见 `§5` 的 `CloseableLedgerGraph` 定义），**该签名 supersede 本节旧签名**；S2 测试须按 §5 的新签名构造注入的受控 lambda（返回 `CloseableLedgerGraph(fakeFacade) { closeCount++ }`），而非旧的裸 `P503LedgerFacade` lambda——以避免实现时按已废弃签名落测试。
  2. **必须抽象日志**：`start()` 的 catch 块调用 `android.util.Log.w`（`App.kt:117`）。抛出异常并调用该行会在 JVM 单测触发「Method w in android.util.Log not mocked」崩溃（android.util.Log 非 JVM stub）。故 controller 的失败标记改为经注入的日志载体：增加构造参数 `logFailure: (String) -> Unit = { msg -> android.util.Log.w(LOG_TAG, msg) }`（默认保真现状；测试注入计数/无操作 lambda）。这是最小的可测性加法，位于 android-app 组合根、不触碰共享层。
- 测试三条（与 desktop 对称 + 资源安全断言见 §5）：
  - 失败→重试→Ready：首调抛 `IllegalStateException` → `StartupError` 且 facade null；次调成功 → `Ready` 且 facade 非 null（对称 `injectedOpenFailureGoesToErrorThenRetryReachesReady`）。
  - 重复失败保持 fail-closed：连跑两次均 `StartupError` 且 facade null，无 graph 外泄（对称 `repeatedInjectedFailureStaysFailClosedWithNoGraphExposed`）。
  - 重试时关闭上一连接（与 S3 合并断言，见 §5 测试）。

## 5. S3 重试资源安全

行为不变式：**重试成功后恰好一个活跃连接；失败中途已打开的 driver 也须关闭**。

- **Android**：`AndroidLedgerDatabaseHandle` 已实现 `AutoCloseable`（`AndroidLedgerDatabaseHandle.kt:33-37`），无需新增 ledger-data API。改动仅在 android-app 组合根 controller：`start()` 在 `facade = openDatabase()` 前，若已有上一轮成功或失败中途残留的 handle/facade 所持连接，先 close。为此 `openDatabase` 契约从 `() -> P503LedgerFacade` 调整为返回可关闭体——
  `openDatabase: () -> CloseableLedgerGraph`，其中 `internal data class CloseableLedgerGraph(val facade: P503LedgerFacade, val close: () -> Unit)`；`app()` 的构造（`App.kt:65-70`）从 `createAndroidLedgerDatabase(...).let { handle -> buildLedgerFacade(handle) }` 改为同时捕获 `handle`：`{ createAndroidLedgerDatabase(context, "ledger.db").let { handle -> CloseableLedgerGraph(buildLedgerFacade(handle)) { handle.close() } } }`。`start()` 重试/重建前调用上一 graph 的 `close`，并用 try/finally 保证失败中途新 handle 也 close。
- **Desktop**：`openDesktopLedger` 需保留 driver 引用以 close。改动在 desktop-app：`openDesktopLedger` 返回 `CloseableLedgerGraph(facade, close = { driver.close() })`（driver 为 JVM `app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver`，`Main.kt:271`）；`DesktopStartupController`（`Main.kt:140-162`）与 Android 对称地改为持有可关闭 graph，`start()` 在重建前 close 旧连接、失败中途 finally close 新开连接。desktop `DesktopStartupControllerTest` 增一条资源安全断言。
- **防双击/重入**：controller 增加 in-flight 守卫（复用 `state == P503StartupState.Starting`：进入 `start()` 先置 `Starting`，若已处于 `Starting` 直接返回，不重复重建与不重复 close）。两端一致。
- **close 幂等**：`close()` 被重复调用无害（`AndroidSqliteDriver.close` 为 SQLDelight 幂等语义；desktop 由 JdbcSqliteDriver 保证）。测试对 close 调用次数做会计（见下）。
- **当面技术边界**：`AndroidLedgerDatabaseHandle.close()` 已存在（ledger-data，P5-03），本批不新增 ledger-data API；desktop 的 driver close 走 JDBC 标准。**不触及 ledger-domain/ledger-application 语义**。若评审认为 desktop 反查数据库（`DesktopLedgerGraph.database`，`Main.kt:164-172`）的 close 语义需纳入 ledger-data 型契约，标记潜在的 ledger-data 最小 additive 需求 → 归 STOP-ITEM（§12），不在本批静默实施。

## 6. S4 commitOnce handoff 边界显式钉住

- 语义已由 KDoc（`ExecuteManualExpenseSubmission.kt:9-24`）与机制（`CommitOnceInvocationTracker.commitOnceInvoked` 入口置位，`ExecuteManualExpenseSubmission.kt:55-62`）锁定，测试已覆盖大部分既有场景。本批补缺口条目，仅在 `ledger-application/src/commonTest/.../ExecuteManualExpenseSubmissionTest.kt` 增加：
  - **T-H1 入口即置位**：`RecordingCommitPort` 在 `commitOnce` 内断言 `harness.tracker.commitOnceInvoked == true`（进入入口即置位、返回值尚未决定），证明「入口调用」即 handoff 判定点。当前 `RecordingCommitPort` 未断言置位（`ExecuteManualExpenseSubmissionTest.kt:295-310`）。
  - **T-H2 入口前异常 = 可重试**：已在 `preSubmitZeroCallFailureIsInfrastructureFailure`（:99-109）覆盖，本条注明「维持不回退」，不做重复新增。
  - **T-H3 入口后异常 = 解析**：已在 `postHandoffMatchingReceiptRecovers`/`postHandoffSnapshotConflictMapsToStableConflict`/`postHandoffAbsentStaysUnknownCommit`/`postHandoffUnavailableStaysUnknownCommit`（:112-171）覆盖，注明「维持不回退」。
  - 本批仅新增 **T-H1** 一条；T-H2/T-H3 为既有钉住转述，不重复计数。该项不改变任何行为。

## 7. S5 共享 host 接线钉住

- 目标：以可注入 fake（不引新框架）为 `P503App` 的 host 行为建纯 JVM 测试，钉住 `RetryRefresh→refresh` 调用、`RetrySubmission→submit` 同 requestId、Created 后自动权威刷新恰一次、UnknownCommit 自动核对恰一次 + `lastCheckOutcome` 守卫。
- **可测性判定**：`P503App` 是 `@Composable`，由于 P5-03 明确不引入 compose ui-test harness，无法直接在 JVM 测 `@Composable` 内部逻辑。按任务允许的最小重构边界：**把可测决策逻辑抽为纯 Kotlin 协调器**，不改状态机语义、不改 reducer、不改平台接线。
- 设计（最小 + 显式边界）：
  - 新增纯 Kotlin 类 `P503HostCoordinator`（app-ui commonMain，非 @Composable）：持有 `P503Reducer` 与依赖注入的回调集合——
    重试/刷新/提交/核对判定的输入事件与守卫状态。它既不持有 Compose 状态，也不持有 facade，只表达「收到某 UI 事件/进入某状态 → 调用哪个 host 回调 + 守卫是否放行」，把 `P503App.kt` 中 `LaunchedEffect(state)`（:180-194）与各按钮回调（`P503App.kt:325-339`）的决策骨架外移到纯可测层。
  - `P503App`（@Composable）保留调度执行（coroutine、scope、facade 调用、dispatch 到 reducer），但把「何时调用 refresh/submit/check」的判定委托给 `P503HostCoordinator`。**边界**：协调器不得触碰 `P503LedgerFacade`/`P503AppState` 的写端（可读 state 以判定、不可改），不得改 reducer 语义，不得引入 IO。
  - 若评审判定该抽取横跨面过大或破坏现状，允许退化为「仅对已外联的 `checkCommitStatus`/`submit`/`refresh` 的触发条件做提取常量 + 条件测试」，并在此处记录备选；默认按协调器方案规格化。
  - **decide 契约（纯函数签名）**：协调器暴露一个纯决策入口，输入为只读的当前共享状态，输出为「本次评估决定执行的回调 + 是否放行」——
    ```kotlin
    // 输入：只读共享状态；输出：本帧应执行的 host 回调动作（可空）。
    internal fun decide(state: P503AppState): HostAction?
    // HostAction 为密封动作：RetryRefresh、RetrySubmission(draft, requestId)、
    // RefreshAfterResult、UnknownCheck(draft, requestId) 之一。
    ```
    该 `decide` 由 `P503App` 在既有评估点（`LaunchedEffect(state)` 与各按钮回调）调用；callback 实际执行仍留 `P503App`（coroutine/scope/facade/dispatch）。协调器只判定「哪个动作、守卫是否放行」，不执行 IO、不触碰 `P503LedgerFacade`、不改 `P503AppState`。
  - **per-instance 守卫**：T-C3/T-C4 的「同实例不重跑」语义由协调器内部持有的显式 per-instance `已出货动作` 标记（按输入 state 实例身份/引用记录已完成 `RefreshAfterResult`/`UnknownCheck` 的动作；某实例已消费即不再对该实例放行）支持——与 `P503App` 实际重入语义（`LaunchedEffect(state)` 依赖 `state` 实例变化触发）匹配：同一实例评估多次只在首次放行回调，实例变化才重新评估。`UnknownCommit` 停留（ABSENT/UNAVAILABLE）写入 `lastCheckOutcome` 后 `state` 实例变化但守卫条件不满足，依旧不重跑。（若在不引入新行为的前提下无法以纯 JVM 钉住此守卫，按既定回退方案退到「触发条件常量 + 条件测试」并记录。）
- **S5 测试**（`app-ui/src/commonTest/.../P503HostCoordinatorTest.kt`，纯 JVM）：
  - T-C1 `retryRefreshInvokesRefreshCallback`：`InfrastructureFailure(READ)` + 触发 → refresh 回调恰一次。
  - T-C2 `retrySubmissionReusesSameRequestId`：`InfrastructureFailure(SUBMISSION, draft, requestId)` 触发 → submit 回调收到同 draft 同 requestId。
  - T-C3 `createdTriggersAuthoritativeRefreshExactlyOnce`：进入 Created 态 → refresh 恰一次；重复评估同实例不再触发。
  - T-C4 `unknownCommitEntryTriggersReadOnlyCheckExactlyOnceThenGuardBlocks`：进入 UnknownCommit(NONE) → check 恰一次；写入 ABSENT/UNAVAILABLE 后守卫不重跑；RetryCommitStatusCheck 返回同实例不重跑。
  - T-C5 `unknownCommitManualRetryStaysGuardedByIdempotentState`：手动重核后协调器经 `lastCheckOutcome` 守卫不复制触发。
- **计数**：S5 新增 commonTest 落入 `:app-ui:jvmTest`（见 §8）。

## 8. 测试计划与验收判据映射

### 8.1 测试计数（双口径）

- `:app-ui:jvmTest` 基线 43（`P503ReducerTest` 41 + `P503DraftValidationTest` 2，见 `app-ui/src/commonTest` `grep -c "@Test"`）。
  - S1 删除启动纯函数测试两条：`startupErrorSupportsRetryAndExit`（:271-276，断言对象为已删共享启动机）、`startupCompletedThenInitialLoadReachesOverview`（:278-288，前置 `Starting`→`StartupCompleted` 为已删事件且其核心断言 `InitialLoadResult → OverviewEmpty` 已由 `initialLoadResultEntersOverviewOnTheHomeTab`（:373-381）重复覆盖）。
  - `initialLoadFailureMapsToReadInfrastructureFailureAndRetries`（:290-303）零改动（直接从 `P503AppState.Ready` 起测，`Ready` 保留）。
  - 本批新增 commonTest：S5 `P503HostCoordinatorTest` 5 条（T-C1..T-C5）。
  - 预期 `:app-ui:jvmTest` 合计：43 − 2 + 5 = **46**。
- `:desktop-app:jvmTest` 基线 4（DesktopCurrentSchemaReopenTest 1 + DesktopStartupControllerTest 2 + DesktopSkeletonSmokeTest 1）。
  - S3 desktop 资源安全：在 `DesktopStartupControllerTest` 增 1 条（T-D1 close 计数/重试后单活跃连接）。
  - 预期 `:desktop-app:jvmTest` 合计：4 + 1 = **5**。
- `:android-app:testDebugUnitTest` 新增 test 源集：S2 三条 + S3 android close 一条 = **4**（新任务，无基线）。
- `:ledger-application:jvmTest` 基线 369（当前工作树实测 = 211 commonTest + 158 jvmTest，`grep -c "@Test"`）；S4 增 T-H1 一条 → 预期 = 369 + 1 = **370**。最终以 verifier 的 `:ledger-application:jvmTest` 实测为准（vts 实际计数可能随当前树基线漂移，杜绝以旧 361 为锚）。

### 8.2 新增测试明细表

| 模块/文件 | 编号 | 名称 | 断言要点 | 关联 S/判据 |
| --- | --- | --- | --- | --- |
| ledger-application `ExecuteManualExpenseSubmissionTest` | T-H1 | commitOnceEntrySetsHandoffMarkerBeforeResult | 进入 `commitOnce` 入口时 `tracker.commitOnceInvoked==true`（返回值前） | S4 / A7 |
| desktop-app `DesktopStartupControllerTest` | T-D1 | retryClosesPriorConnectionAndKeepsSingleActive | 重试后上一 driver close 调一次、成功仅一个活跃连接 | S3 / A4, A6 |
| android-app test `AndroidStartupControllerTest` | T-A1 | injectedOpenFailureGoesToErrorThenRetryReachesReady | 首调抛异常→StartupError、facade null；次调成功→Ready、facade 非 null | S2 / A3 |
| android-app test `AndroidStartupControllerTest` | T-A2 | repeatedInjectedFailureStaysFailClosedWithNoGraphExposed | 连跑两次均 StartupError、facade null | S2 / A3 |
| android-app test `AndroidStartupControllerTest` | T-A3 | retryClosesPriorConnectionAndKeepsSingleActive | 重试后旧连接 close 一次、成功单活跃连接 | S3 / A4, A6 |
| app-ui commonTest `P503HostCoordinatorTest` | T-C1 | retryRefreshInvokesRefreshCallback | READ 失败触发 → refresh 恰一次 | S5 / A9 |
| app-ui commonTest `P503HostCoordinatorTest` | T-C2 | retrySubmissionReusesSameRequestId | SUBMISSION 失败触发 → submit 收到同 draft/requestId | S5 / A9 |
| app-ui commonTest `P503HostCoordinatorTest` | T-C3 | createdTriggersAuthoritativeRefreshExactlyOnce | Created → refresh 恰一次；同实例不重跑 | S5 / A9 |
| app-ui commonTest `P503HostCoordinatorTest` | T-C4 | unknownCommitEntryTriggersCheckExactlyOnceThenGuardBlocks | UnknownCommit(NONE)→check 恰一次；ABSENT/UNAVAILABLE 守卫不重跑 | S5 / A9 |
| app-ui commonTest `P503HostCoordinatorTest` | T-C5 | unknownCommitManualRetryStaysGuardedByIdempotent | 手动重核后守卫不复制触发 | S5 / A9 |

### 8.3 验收判据（A1..A9）与映射

| 判据 | 行为 | 测试/证据 | 命令 |
| --- | --- | --- | --- |
| A1 | S1：共享层不再表达启动重试状态/事件（`Starting`/`StartupError`/三启动事件删除）；`P503AppState.Ready` 入口态保留原样；平台启动重试所有权归 `P503StartupState` | 删除最小集（2 状态 + 3 事件 + 2 reducer 分支 + 2 渲染分支）+ 编译 + 双端人工启动门 | `:app-ui:jvmTest`、`:android-app:compileDebugKotlin` + 人工门 |
| A2 | S1：删除后无死引用——全仓 `grep` 无 `P503AppState.Starting`/`P503AppState.StartupError`/`StartRetry`/`StartupCompleted`/`StartupFailed` 残留（`P503AppState.Ready` 保留） | 编译 + `grep` 检查（实施自查） | `:android-app:compileDebugKotlin`、`:desktop-app:jvmTest`、`:app-ui:jvmTest` |
| A3 | S2：AndroidStartupController 失败→重试→Ready、重复失败 fail-closed 无 graph；controller 日志可注入（JVM 单测不崩） | T-A1/T-A2 | `:android-app:testDebugUnitTest` |
| A4 | S3：两端 start() 重试关闭旧连接、失败中途关闭新开 driver、成功后恰好一个活跃连接；close 幂等 | T-D1/T-A3（会计 close 调用） | `:desktop-app:jvmTest`、`:android-app:testDebugUnitTest` |
| A5 | S3：防双击/重入——start 重入忽略或串行化，不重复重建与不重复 close | T-A3/T-D1（start 内 in-flight 守卫断言） | 同 A4 |
| A6 | S3：不触 ledger-domain/ledger-application/DDL；ledger-data 零新 API（Android handle.close 已存在） | 代码评审 + 零 schema/账务漂移；`grep` ledger-data 变更为零 | 评审 + `:android-app:compileDebugKotlin` |
| A7 | S4：commitOnce 入口即置位 handoff 标记、入口前异常=可重试、入口后=解析；reset-per-submit 不污染次次提交 | T-H1 + 既有 T-H2/T-H3（维持不回退） | `:ledger-application:jvmTest` |
| A8 | S1+S4：自动重试在任何形式下被禁止（启动 Retry 为手工按钮；SUBMISSION/UnknownCommit 无自动重试） | reducer 既有吸收测试 + 无新增自动重试码（评审） | `:app-ui:jvmTest` + 评审 |
| A9 | S5：host 接线钉住（RetryRefresh→refresh、RetrySubmission→同 requestId、Created 自动刷新恰一次、UnknownCommit 核对恰一次+守卫） | T-C1..T-C5 | `:app-ui:jvmTest` |

## 9. 非目标

新增收入/转账/借贷正式类型（D-121 边界）；导航库；视觉（P6）；IME/safeDrawing 全量 insets（后续）；UnknownCommit 的 Back/退出语义变更（维持吸收，如需另立裁决）；P5-04.5 回归收口（后续批次）；任何形式自动重试的引入（`UnknownCommit`/冲突/领域拒绝禁止，D-120/D-121）；桌面窗口管理差异化（窗口行为不变）；schema/DDL；ledger-domain/ledger-application 语义变更；时间/日期选择器；草稿自动保存；编辑既有交易；账户明细下钻与分析维度扩展（P5-04.1 非目标先例）；启动 UI 视觉改版。

## 10. 验证命令集

按 `docs/CONTRIBUTING.md`「本机 Gradle 资源限制」串行执行；资源限制旗标与 `--stop` 包裹已直接写入命令块（每条 Gradle 验证前后 `--stop`，一次只运行一个命令）；任务集与 CI 步骤一致，本机旗标仅资源控制、不改变 CI 验证语义。

**新增本地 + CI 步骤（P5044-S-001）**：`:android-app:testDebugUnitTest` 是本批**新增的本地验证步骤，且为新增 CI 步骤**——本批在 android-app 新建 test 源集（S2/S3 的 `AndroidStartupControllerTest`），`ci.yml` 的 Android job 须同步增加该步骤；「任务集与 CI 步骤一致」的声明以**本规格验收命令集含 `:android-app:testDebugUnitTest` 的理解成立**——本批实施合并时必须把该命令与 CI 对齐，否则 CI 与本地验证集不一致（见下方 CI 同步义务注）。

**CI 同步义务（P5044-S-001）**：按 `docs/AGENTS.md` 与 `docs/CONTRIBUTING.md`「修改验证步骤时同步更新 CI」要求，实施本批时须同时：①在 `.github/workflows/ci.yml` 的 Android job 增加 `:android-app:testDebugUnitTest` 步骤；②在 `docs/CONTRIBUTING.md` 对应节登记该命令；两者与本节命令集保持一致。

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :desktop-app:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :android-app:testDebugUnitTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :desktop-app:ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop

$env:PYTHONPATH="tools\python"
python -m project_docs .
```

- `:ledger-application:jvmTest`（S4 补钉）按 CONTRIBUTING 既有单命令块执行（不在聚合命令集中重复，实施时按需增跑：`.\gradlew.bat :ledger-application:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all`，前后 `--stop`）。
- APK 产物验证按已登记 R-9 由授权 push 后的 CI artifact 承担；聚合 `check` 按 R-17 归 CI。

## 11. 披露与风险

1. **Android handle close 已存在**：任务缺口 #4 描述「handle 增加 close 能力」，但 `AndroidLedgerDatabaseHandle` 已在 P5-03 实现 `AutoCloseable`（`AndroidLedgerDatabaseHandle.kt:33-37`）。本批对 Android **不新增任何 ledger-data API**，仅在 android-app 组合根接线 close，避免任务描述与实际代码的偏移。
2. **Desktop driver 引用丢失**：`openDesktopLedger` 现仅返回 facade、丢失 `JdbcSqliteDriver` 引用（`Main.kt:270-274`），是本批桌面侧资源安全的核心缺口；改动限 desktop-app，经 `CloseableLedgerGraph` 包一层 close 而非改动 ledger-data。
3. **S1 破坏既有 reducer 测试**：删除共享启动机必然删除 `P503ReducerTest` 两条（:271-288）。已在 §8.1 写明删除；实施时按「先改测试再删状态」顺序保证每步可编译。`initialLoadFailureMapsToReadInfrastructureFailureAndRetries`（:290-303）零改动。
4. **S5 协调器抽取成本**：抽 `P503HostCoordinator` 是最小但确定的重构；若评审认为横跨过大，退化为触发条件提取（§7 备选），行为变更仍为零。此项不改变 reducer/状态机语义。
5. **start() 重入守卫**：两端 controller 当前无 in-flight 守卫，双击「重试」可并行重建。本批加 `state == P503StartupState.Starting` 守卫，行为改变仅在连点场景（此前会重复重建），属修复而非回归。
6. **`Ready` 保留的论证**：任务指引文字列删除项包含 `P503AppState.Starting/Ready/StartupError`（`P503AppState.kt:18-23`），但 `Ready` 是 reducer 种子态、消费 `InitialLoadResult`/`InitialLoadFailed`（`P503Reducer.kt:56-62`），删除将破坏 reducer 全称性并在 `P503App` 首次 dispatch 触发 unhandled ISE；且 `P503App.kt:199` 的 `Ready` 渲染分支是第一帧加载呈现、`when` 全称性需要它。因此本规格删除 `Starting`/`StartupError` 而**保留 `Ready`**（其属于「平台已就绪后、首次加载在途」的入口态，不承载启动重试语义；启动重试所有权仍归平台）。此判定已在上文 §3 证据链完整呈现，评审如需连 `Ready` 一并删除须另行设计入口态并评估首次 dispatch 路径。
7. **外部证据门结论**：本批不新增第三方依赖、不改账务/迁移/安全语义；Android 启动测试用 `testDebugUnitTest`（JVM 单测 + 注入日志抽象，不引 Robolectric）；`docs/SOURCE_REFERENCES.md` 与 `.external/requirements/` 现有登记无需扩充。

## 12. STOP-ITEM（如发现需核心语义/DDL/新类型者在此停下报告）

- 本规格起草阶段未发现强制触发 STOP-ITEM 的新核心语义/DDL/新类型需求。仅在 §5（desktop driver close 是否需纳入 ledger-data 型契约）列一项评审待决；若评审裁定需引入 ledger-data 新类型/新语义 API，转为 STOP-ITEM 并停下报告，不本批实施。
- S3 的 desktop `JdbcSqliteDriver.close` 属 JDBC 标准、非 ledger-data API；Android `AndroidLedgerDatabaseHandle.close` 已存在，二者均不触发 STOP-ITEM。

## 13. 文档与决定登记

- 规格落盘 `docs/specs/2026-09-03-p5-04-4-fail-closed-retry-design.md`（本文件）。
- `DECISIONS.md` 预计新增 D-129（批准依据 = D-121 规划授权 + 用户启动指令；引用本规格冻结 SHA-256 与规格评审 disposition；实施登记段由合并后状态同步提交补全）——编号以实际续号为准，由主代理确认。
- **CI/验证同步（P5044-S-001）**：`.github/workflows/ci.yml` 的 Android job **增加 `:android-app:testDebugUnitTest` 步骤**（本批在 android-app 新建 test 源集，S2/S3 落该任务），并与 `docs/CONTRIBUTING.md` §验证命令同步更新（符合 `docs/AGENTS.md`「修改验证步骤时同步更新 CI 配置」要求）；不得再写作「零 build/CI 变更」——本批有一个新增验证入口（android-app test 源集）。README/PROJECT_MAP/ARCHITECTURE 预计零实质变更；CURRENT_STATE/ROADMAP 状态更新放合并后提交（主代理）。