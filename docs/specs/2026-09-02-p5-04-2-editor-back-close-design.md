# P5-04.2 新增记账全屏编辑页：系统返回关闭与固定确认/取消 — 实施规格

## 0. 授权与边界

- 授权：D-121 P5-04.2（共享状态控制的新增记账全屏编辑页；系统返回关闭编辑页；确认/取消行为固定；不引入导航库）+ 用户指令 2026-09-02「做P5-04.2」。
- 边界：不引入导航库；不新增第三方依赖或 Gradle 模块（Android 返回走 activity-compose 1.13.0 既有稳定 `androidx.activity.compose.BackHandler`，经 app-ui 平台钩子接入，app-ui 不引用 androidx.activity）；零 DDL、零 schema、零新正式交易类型；不改动 P5-03/P5-04.1 的账务与正式交易语义及「权威刷新恒回首页」不变量；本批按要求扩展编辑流程状态机（新增字段均带默认值、向后兼容）；桌面端无系统返回语义，本批不改平台接线（共享 `Back` 事件由 reducer 测试覆盖）。

## 1. 现状缺口（调研证据）

- `(Editing, Cancel)` 与 `(Submitting, Cancel)` 无转移行（P503Reducer unhandled → 抛 ISE）；`DomainRejected` 无 `AbandonConflict`（仅 Update* 回 Editing）。
- `Editing`/`AwaitingConfirmation`/`Submitting`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure(SUBMISSION)` 均不携带 overview 快照与来源 Tab，纯 reducer 无法回到 `OverviewEmpty`。
- Android 现无返回拦截（系统返回直接 finish 退出应用）。

## 2. 状态机（app-ui）

- `P503UiEvent` 新增用户事件 `data object Back`。
- 状态扩展（字段均带默认值，既有构造点与测试零破坏）：
  - `Editing`、`AwaitingConfirmation`、`Submitting`、`RequestIdentityConflict`、`DomainRejected` 各增 `overview: LedgerCurrentState? = null` 与 `originTab: P503Tab = P503Tab.HOME`。
  - `InfrastructureFailure` 增 `overview: LedgerCurrentState? = null` 与 `originTab: P503Tab = P503Tab.HOME`（仅 SUBMISSION 语义有意义；READ 恒 null）。
- 完整过渡表（Back 目标均为「关闭编辑页回总览」；overview 为 null 时 Back 走 `unhandled()` 抛 ISE，UI 层只在 overview != null 时启用返回拦截）：

| 源 | 事件 | 目标 |
| --- | --- | --- |
| OverviewEmpty | StartNewExpense | Editing(空草稿, requestId=null, overview=state.state, originTab=state.selectedTab) |
| Editing | Continue(有效) | AwaitingConfirmation(draft, requestId, overview, originTab) |
| Editing | Continue(无效) | Editing.copy(requestId)（threading 经 copy 保留） |
| Editing | Back | OverviewEmpty(overview, originTab)（丢弃草稿） |
| AwaitingConfirmation | Back | OverviewEmpty(overview, originTab)（丢弃草稿） |
| AwaitingConfirmation | Cancel | Editing(draft, requestId=null, overview, originTab)（保留草稿，回编辑；与 Back 语义区分） |
| AwaitingConfirmation | Confirm | Submitting(draft, requestId, overview, originTab) |
| Submitting | SubmissionResult(InvalidInput) | Editing(draft, requestId, overview, originTab) |
| Submitting | SubmissionResult(DomainRejected) | DomainRejected(draft, requestId, overview, originTab) |
| Submitting | SubmissionResult(RequestIdentityConflict) | RequestIdentityConflict(draft, requestId, overview, originTab) |
| Submitting | SubmissionResult(InfrastructureFailure) | InfrastructureFailure(SUBMISSION, draft, requestId, overview, originTab) |
| Submitting | Back | unhandled() 抛 ISE（UI 在 Submitting 吞返回、不 dispatch） |
| RequestIdentityConflict | Update* | Editing(draft, requestId, overview, originTab) |
| RequestIdentityConflict | AbandonConflict | Editing(draft, requestId=null, overview, originTab) |
| RequestIdentityConflict | Back | OverviewEmpty(overview, originTab) |
| DomainRejected | Update* | Editing(draft, requestId, overview, originTab)（DomainRejected 无 AbandonConflict） |
| DomainRejected | Back | OverviewEmpty(overview, originTab) |
| InfrastructureFailure(SUBMISSION) | RetrySubmission | Submitting(draft, requestId, overview, originTab)（threading 保留，重试链不丢 overview） |
| InfrastructureFailure(SUBMISSION) | Cancel | Editing(draft, requestId, overview, originTab) |
| InfrastructureFailure(SUBMISSION) | Back | OverviewEmpty(overview, originTab) |
| 其余状态 | Back | unhandled() 抛 ISE；UnknownCommit 维持吸收一切事件（含 Back） |

- `(Editing, Cancel)` 与 `(Submitting, Cancel)` 维持 unhandled（编辑页无取消按钮，Cancel 仅由确认页发出）。
- 既有 `InitialLoadResult`/`RefreshResult` 恒回 `OverviewEmpty(.., HOME)` 规则不变。
- 设计说明：确认页上「取消」= 回编辑保留草稿，「系统返回」= 关闭整个编辑页（丢弃草稿）；Back 恢复到的是进入编辑页时的 overview 快照（只读，与 SelectTab 复用同一 state 引用语义一致），非新查询；「返回回来源 Tab」与「权威刷新恒回首页」并存为有意设计。

## 3. 平台返回接线

- `P503App` 增加参数 `backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null`（默认 null）。
- `P503App` 内：`backHandler?.invoke(enabled = editFlowBackEnabled, onBack = { if (state !is Submitting) dispatch(Back) })`，其中 `editFlowBackEnabled = state is Editing/AwaitingConfirmation/Submitting/RequestIdentityConflict/DomainRejected/InfrastructureFailure(SUBMISSION) && (state.overview != null)`。编辑流各态按 overview 非空拦截系统返回；Submitting 拦截但吞掉返回（防提交中退出应用）；其余状态不拦截（系统默认返回 = 退出应用，维持现状）。
- android-app `App.kt` 传入 `backHandler = { enabled, onBack -> androidx.activity.compose.BackHandler(enabled, onBack) }`（activity-compose 1.13.0 稳定 API，已确认存在于依赖）。
- 不新增依赖；app-ui commonMain 不引用 androidx.activity。

## 4. 测试

- `P503ReducerTest` 新增：
  - `(Editing, Back)` → `OverviewEmpty`（overview 同值、selectedTab = originTab、草稿丢弃）；`(AwaitingConfirmation, Back)` 同（丢弃草稿）。
  - threading 保留：`(Submitting, InvalidInput)`、`(RequestIdentityConflict, Update*)`、`(DomainRejected, Update*)`、`(InfrastructureFailure[SUBMISSION], Cancel)`、`(InfrastructureFailure[SUBMISSION], RetrySubmission)` 到达的 Editing/Submitting 保留 overview/originTab，可继续 Back 关闭回总览。
  - Back 从 `RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure(SUBMISSION)` 关闭回 OverviewEmpty(overview, originTab)。
  - `(Submitting, Back)` 抛 ISE；null-overview `(Editing, Back)` 抛 ISE；`(OverviewEmpty, Back)` 抛 ISE；UnknownCommit 吸收 Back。
  - 回归钉住：`(Editing, Cancel)`、`(Submitting, Cancel)` 仍抛 ISE。
  - 既有 `Editing`/`AwaitingConfirmation`/`Submitting`/RIC/DomainRejected/InfraFailure 构造点无需改（默认值兼容）。
- 不引入 compose ui-test harness；Android 接线由编译（`:android-app:compileDebugKotlin`）与模拟器实机返回键验收。
- 验证命令须全绿：`:app-ui:jvmTest`、`:desktop-app:jvmTest`、`:app-ui:ktlintCheck`、`:desktop-app:ktlintCheck`、`project_docs`、`:android-app:compileDebugKotlin`；APK 按 R-9 由 CI artifact 承担。

## 5. 非目标

导航库选型；桌面 Esc 映射与桌面编辑页退出（后续批次）；编辑页可见关闭按钮或 UI 改版（P5-04.3 流程完善）；草稿自动保存；新交易类型；视觉（P6）。

## 6. 文档与决定

规格落盘 `docs/specs/2026-09-02-p5-04-2-editor-back-close-design.md`；DECISIONS.md 新增 D-125（批准依据 = D-121 规划授权 + 用户指令；引用本规格冻结 SHA-256；实施登记由合并后状态同步提交补全）；README/PROJECT_MAP/ARCHITECTURE 事实性同步；CURRENT_STATE/ROADMAP 状态更新放合并后提交。
