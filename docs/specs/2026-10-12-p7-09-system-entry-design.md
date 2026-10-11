# P7-09 系统入口（system entry：分享导入 / 图标快捷方式 / 月度小组件）设计门规格

状态：draft（2026-10-12 起草；本文是 P7-09「系统入口」的**设计规格草案**，**尚未批准**，不得当作既有 API 或实施授权。本批只做设计门起草；预期批准依据由 `docs/DECISIONS.md` 后续决定登记——编号沿用本文所见的下一可用候选，见 §8 开放项 12，此编号为**建议/占位**、**不是已登记决定**。本文只冻结**设计级**语义，**不**构成产品行为、迁移、技术选型或发布授权；实施属后续独立实施批。本文不写实现、不写迁移文件、不分配 schema 版本号。）

**Revision:** draft-1（2026-10-12）。初稿。单一写者起草于隔离 worktree（分支 `UL-p7-09design`），工作基点 = `27c842f`（schema 维持既有版本；本文**不分配、不创建**任何迁移文件）。tracked 行号为本基点实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 为主 checkout 的本地只读文件，本 worktree 内不存在，凡引用一律标注「计划」并以其主 checkout 实读行号为准。本文只冻结 09.A 分享导入、09.B 图标快捷方式、09.C 月度小组件、09.D 平台回归四子项的**设计候选面**。本文**不**修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不复制大段官方原文与产品代码；不写本机绝对路径、个人数据或工具轨迹；示例全部匿名合成。

**标记约定：** 全文用【已验证事实】标注本次实读代码/文档所得的现状（行号均经抽验），用【冻结设计提案】标注本文建议、尚未批准、实现者不得当作既有 API 的内容；【技术门】标注须先取得资源/设备证据才能裁决的能力。

## Scope

冻结 P7-09 系统入口的设计候选面（计划 §9，计划 `:312-369`）：

- **09.A 分享导入**：受控接收单文件 `ACTION_SEND` → 复用既有有界读取/格式矩阵/候选链；冷热启动与当前编辑冲突路由；无效输入类型化拒绝、零正式效果、Intent 重投递不悄然重复接治。
- **09.B 图标快捷方式**：记支出/记收入/导入三项直达既有流程；启动未就绪先经启动门；不丢草稿、不打断提交、不绕过确认。
- **09.C 月度小组件**：只读展示 P7-03 月度收支结果，显示更新时间/不可用，点击回到应用；默认隐藏金额。
- **09.D 平台回归**：授权、进程死亡、刷新/节电约束、无障碍、桌面组合根回归；支持条件有证据，无新增敏感后台采集或自动记账。

**首版切片【冻结设计提案】：** 统一导航门（09.0 入站导航门）+ 09.A 单文件分享 → 09.B 动态快捷方式 → 09.C 有界只读快照/小组件 → 09.D 完整平台回归。09.C 依赖 07.T 有界月份读取，未满足则只推进独立状态机/布局设计，不交付全账扫描的临时小组件（计划 `:355`）。本文**只冻结设计**，实现、设备取证、Git 写操作与最终验收属后续独立实施批。

## 非目标（本文不授权）

- **多文件分享**：首版只接受单文件 `ACTION_SEND`；`ACTION_SEND_MULTIPLE` 不纳入（计划 `:325`，计划 `:337`）。
- **跨设备/云同步**：widgetId/host 绑定配置是设备本地状态，不跨设备恢复；新增可迁移用户偏好另入 P7-06 清单（计划 `:351`）。
- **持久 URI grant / 磁盘排队**：沿 D-146 不保存文件副本、不长期持有 URI、不调用无普遍保证的 `takePersistableUriPermission`；将来要磁盘排队须先修订 D-146（计划 `:339`）。
- **引入 Glance / WorkManager / 导航框架**：除非先给出资源证据证明现有平台 API 无法满足后台预算（计划 `:345`）；本文不预先授权新依赖。
- **在 BroadcastReceiver 主线程做数据库聚合**：`onReceive` 恒在主线程，系统上限约 10 秒否则 ANR（计划 `:345`、`:110`）。
- **绕过启动门直接正式写账**：平台入口只导航/产生草稿，不直接正式写账（计划 `:314`、`:330`）。
- **widget 显示金额而不经用户开启**：默认隐藏，主动开启后才显示（计划 `:345`、`:351`）。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为本 worktree 基点 `27c842f` 实读行号；计划行号为主 checkout 实读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §9（计划 `:312-369`，四子项 09.A–09.D、§9.1 Q19 行 `:325-339`、§9.2 Q20 行 `:345-351`、§9.3 切片与验收 P709-A01..A09 行 `:355-367`）、§4.3 官方依据表（计划 `:99-112`）、§7.2 有界月份读取（计划 `:234-238`）、§10.2 设备证据（计划 `:402-408`）、§10.5 T09 测试导航（计划 `:443-445`）。计划 `:4` 表明未裁决项不构成授权；Q19/Q20 为**推荐、待审批**。
- **官方平台依据（计划 §4.3 表，计划 `:108-110`，本文只作来源 + 一句转述）**：
  - 接收分享与临时文件权限：`https://developer.android.com/training/sharing/receive`、`https://developer.android.com/training/secure-file-sharing/share-file`——不信任 MIME、声明大小或 extras；临时 content grant 不能普遍持久化；读流离开 UI 线程。
  - 任务栈与快捷方式：`https://developer.android.com/guide/components/activities/tasks-and-back-stack`、`https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts`——`singleTop` 只复用栈顶；静态 shortcut 首 Intent 的 NEW_TASK/CLEAR_TASK 可能销毁现有编辑状态；优先验证动态快捷方式的显式任务策略。
  - App widgets 与高级行为：`https://developer.android.com/develop/ui/views/appwidgets`、`https://developer.android.com/develop/ui/views/appwidgets/advanced`——周期更新最低 30 分钟，receiver 约 10 秒预算，`goAsync` 不是无限工作时间；需要短任务与有界读，禁止广播主线程扫全账。
  - `PendingIntent`（`https://developer.android.com/reference/android/app/PendingIntent`）、`AppWidgetProvider`（`https://developer.android.com/reference/android/appwidget/AppWidgetProvider`）、`RemoteViews`（`https://developer.android.com/reference/android/widget/RemoteViews`）、`Activity`（`https://developer.android.com/reference/android/app/Activity`）、`Intent`（`https://developer.android.com/reference/android/content/Intent`）、`BroadcastReceiver`（`https://developer.android.com/reference/android/content/BroadcastReceiver`）——见 §2/§6 的具体转述。【技术门】列出的能力须实测/查 API 参考核验，不得当既有事实。
- **设计规格先例（approved）**：`docs/specs/2026-09-30-p7-08-tag-merchant-design.md`（本文结构与标记约定模板来源）、`docs/specs/2026-09-28-p7-07-budget-design.md`（有界月份读取与刷新联动措辞先例）。
- **决定**：D-187（最近一个设计门批准先例：设计门**不分配 schema 版本号**、Q 项裁决由决定登记）、D-176（P7-06 `LedgerRuntimeOwner` 稳定存储/世代设计门批准）、D-184（07.T 有界月份读取与 fail-closed 纪律）、D-146（导入不留原文件、不持久 URI grant）、D-162/D-161（程序化滚动/规模项设备证据先例）。
- **架构**：`docs/ARCHITECTURE.md`（四共享模块 + 两组合根；app-ui 只消费 application 类型；平台面留在组合根）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；`transaction_effective_state` 是有效谓词**唯一 SQL 定义点**，widget/分享读不得另立第二套状态规则；不引入新依赖。**零 schema、零迁移**（本文不分配版本号，沿 D-187 先例）。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 无外部入口能力【已验证事实】

- `android-app/src/main/AndroidManifest.xml:6-13` 只有 `.MainActivity` + `MAIN`/`LAUNCHER`：**无** `receiver`、**无** `provider`、**无** `ACTION_SEND`/shortcut/widget 的 `<intent-filter>`。
- `android-app/build.gradle.kts:25` `compileSdk = 37`、`:28` `minSdk = 34`、`:29` `targetSdk = 37`；`:47-74` 依赖只含 `app-ui`/`ledger-application`/`ledger-data`、`android-driver:2.3.2`、`kotlinx-datetime:0.8.0`、`activity-compose:1.13.0`、`compose.runtime`、`compose.foundation`（及 test-only 工件）——**无** Glance、**无** WorkManager、**无** 导航框架依赖。
- `android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt:11-32` `onCreate` 只做 `StartupTrace.emit` + `enableEdgeToEdge` + `setContent { P503Theme { app() } }`：**无** `onNewIntent` 覆盖、**无**导航参数读取（全仓对 `onNewIntent`/`setIntent` 的命中为 0）。分享/快捷方式/window 均无接收入口。

### 1.2 导入选择链与格式槽现状（E09-02/03）【已验证事实】

- 有界读取：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/ImportFilePick.kt:6` `IMPORT_FILE_PICK_MAX_READ_BYTES = 16L*1024*1024`；`:147-150` `fun readImportPickBounded(sizeBytes: Long?, openStream: () -> ImportPickRawRead): BoundedFileRead`（已知尺寸 > limit 短路 `ExceedsLimit`；否则读至多 limit+1；`:195-227` 读循环，`:230` chunk 64 KiB；`:175-191` try/finally 恒释放流）。
- Android 适配器：`android-app/src/main/kotlin/com/unifiedledger/android/ImportFilePick.kt:40-45` `internal class AndroidImportFilePickPort<Picked>`（`launchOpenDocument`/`resolveMetadata`/`openInputStream`/`onResult` 注入，可无 Robolectric 测试）；`:28-30` 注释明确**不做** `takePersistableUriPermission`、不复制文件、不落临时副本。
- 格式槽：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:429` `var pendingImportFormat by remember { mutableStateOf<ImportFormatId?>(null) }`（写入 `:1600`；`runImportIntakePipeline` `:1616` 读取 `:1617`、消费/置空 `:1627`）。**先选格式再 SAF**——直接把外来文件塞进旧 channel 会缺格式（计划 `:97`、`:337`）。

### 1.3 无统一导航门【已验证事实】

- `P503App` 是唯一入站入口：`app-ui/.../P503App.kt:174-178` `fun P503App(ledger: LedgerLeaseScope, onExit: () -> Unit, backHandler: ...)`——**无**导航参数（无 `openImport`/`openNewEntry`/`openMonth`）。
- 组合根：`android-app/.../App.kt:334-353` `when { controller.state == P503StartupState.Ready && facade != null -> P503App(...) else -> P503StartupScreen(...) }`；`:524-525` `val ledger: LedgerLeaseScope? get() = if (state == P503StartupState.Ready) leaseScope else null`。启动态机 `app-ui/.../P503StartupScreen.kt:27-44` `sealed interface P503StartupState { Starting; Ready; StartupError; ... }`。
- `P503UiEvent.kt:38` `sealed interface P503UiEvent` 当前**无** `OpenNewEntry`/`OpenImport`/`OpenCurrentMonth` 类事件。

### 1.4 既有 runtime owner / 租约面【已验证事实】

- `app-ui/.../LedgerRuntimeOwner.kt:255-271` `class LedgerRuntimeOwner<G>`；公开面 `:337 startup()`、`:386 acquireLease()`、`:432 suspend quiesce()`、`:473 closeActiveGraph()`、`:494 reopen(target = GenerationSelection.ActivePointer)`、`:281 activeGeneration`、`:276 state`、`:224-237 LedgerLease`。
- `facade` 是 `internal val facade: P503LedgerFacade?`（`:320-321`），**刻意非 public**——产品只经 `LedgerLeaseScope`（`:574`）消费（`:316-318` 注释）；`:764 isCurrentGeneration`、`:546-549 shouldDiscardLandingResult`、`:771 withFacade`、`:793 leased`。
- 启动控制器：`android-app/.../App.kt:389 class AndroidStartupController`；`:432-443` 内含 owner；`:573 fun start()`（非 suspend，`Dispatchers.IO`）、`:613 dispose()`、`:643-648 applyStartupResult`、`:1113-1118 private fun buildLedgerGraph(...)`（**会 bootstrap/migrate**，见 §6）。

### 1.5 有界月份读取与 P7-03 月视图现状【已验证事实】

- 用例 `ledger-application/.../QueryMonthlyActivity.kt:17-22 class QueryMonthlyActivity(readPort, ledgerId, catalog, clock)`；`:30 query(month)`、`:47 trend()`、`:78 selectableMonths()`。
- 投影 `MonthlyBuckets.kt:27 object MonthlyBuckets`；`:29 REPORT_TIME_ZONE = Asia/Shanghai`；`:113-118 aggregate(...)`；三项精确金额 `:200 ordinaryIncomeMinorUnits`、`:201 netExpenseMinorUnits`、`:202 balanceMinorUnits`；`:384-397 MonthlyCurrencyActivity`（`:388` 注释：结余 = ordinary income − net expense；**从不是账户余额或现金流**）。
- **有界月份端口**（07.T 已交付）：`ledger-application/.../MonthlyContributionReadPort.kt:98 fun interface MonthlyContributionReadPort`、`:104 fun readContributions(...)`；文件头 `:7-42` 明确**禁止**走整账 `loadLedgerEntryRows`。
- P7-03 取数：`P503App.kt:736 requestMonthlyPayload()`、`:749-771` `ledger.probe { facade.queryMonthlyActivity?... }` → `dispatch(P503UiEvent.MonthlyActivityResult(...))`；渲染 `P503LedgerView.kt:78-79 @Composable internal fun P503MonthCard`、`P503LedgerViewPresentation.kt:355-359`「普通收入…净支出…结余」。

### 1.6 可复用共享 UI 机制：刷新 / 代际 / token / arm【已验证事实】

- `P503App.kt:658 refresh()`；`:1585-1591 LaunchedEffect(state) { coordinator.decide(state); coordinator.decideMonthly(state); coordinator.decideBudgetMonth(state) }`；`:687-690 consumeRetainedIntentAfterRefresh(...)`。
- 生代丢弃：`:675`、`:1659`、`:1724`（`if (outcome is LeaseOutcome.Completed && !ledger.isCurrentGeneration(outcome.generation)) ...`）。
- 08.C 单调 token：`:471 requestTagMerchantFilterPage`；`P503AppState.kt:567 TagMerchantFilter(... requestToken ...)`。08.D arm seam：`:585 armTagMerchantFilterReRequestIfLoaded`、`:590 consume...`、`:600 drop...`。
- 导入外挂事件族：`P503UiEvent.kt:432 StartImportFilePick(format)`、`:442 ImportFilePicked`、`:446 ImportFilePickCancelled`、`:448 ImportFilePickFailed`、`:457 ImportFileIntakeResult`；月度 `:418 MonthlyActivityResult`。
- 刷新链先例：`P503HostCoordinator.kt:197 decide(state)`、`:251 decideMonthly`、`:329 onImportBatchConfirmed`、`:349 onP705EffectiveSurfaceChanged`、`:368 decideBudgetMonth`。

### 1.7 AlreadyInFlight 静默丢弃差距（E09-03）【已验证事实】

- `P503HostCoordinator.kt:484 fun handleImportPickResult(result): ImportPickIntakeDecision`；`:750-757 sealed interface ImportPickIntakeDecision { StartIntake / AlreadyInFlight / NoPipeline }`。
- `P503App.kt:1700-1705` `when (decision) { StartIntake -> runImportIntakePipeline(...); AlreadyInFlight, NoPipeline -> Unit }`——**AlreadyInFlight/NoPipeline 落 `-> Unit`，静默丢弃、无可见反馈**。这正是系统入口必须修的缺口：第二文件忙碌结果目前可能被忽略（计划 `:97`、`:337`：「AlreadyInFlight 必须变成可见 Busy/待处理结果」）。

## 2. 统一导航门与单文件分享（Q19 落地）

### 2.1 中立导航请求与 common/app-ui 边界【冻结设计提案；沿计划 Q19，计划 `:325`】

- Android 侧把收到的 `Intent` **校验**为**中立请求**；`common` app-ui 只接收三类中立请求：
  - `OpenNewEntry(type)`——记支出/记收入等直达录入；
  - `OpenImport(inputToken)`——受控文件分享的导入（含格式由用户在 Android capability matrix 选择后再并入统一命令）；
  - `OpenCurrentMonth`——回到应用当前月份/看账。
- **Uri、文件名与平台对象留在 Android 适配器内**：`common` 侧不出现 `android.net.Uri`/`ContentResolver`/`java.io` 形状（沿 `ImportFilePick.kt:102-108` 的 commonMain 边界纪律）。
- **实签名建议、字面留开放项**：三类请求的精确 sealed 形状、字段类型与失败码族 OPEN（§8 开放项 1）；实现者不得把本文的示意名当作既有 API。

### 2.2 deliveryId 与本地不透明 token【冻结设计提案；沿计划 `:325`】

- 本地生成**不透明 deliveryId**；消费状态**跨重组 / Activity 重建**保持——`onNewIntent` **必须** `setIntent()` 更新（`getIntent()` 否则仍返回原始 Intent，`https://developer.android.com/reference/android/app/Activity`）。
- 启动**未 Ready** 时暂存**一个**请求（single pending slot）；已有请求时明确 **Busy / 保留或替换确认**，**禁止无界队列或静默丢弃**（计划 `:325`）。
- **修 AlreadyInFlight 静默丢弃**：外部入口的结果不得落入 `-> Unit`（§1.7）；忙碌必须呈现为**可见 Busy/待处理**（计划 `:337`）。

### 2.3 分享验证面【冻结设计提案；沿计划 `:337`】

接受前逐项校验：action（仅 `ACTION_SEND`）、**单** `content://` stream、`EXTRA_STREAM` 与 `ClipData` **一致性**、**实际可读权限**。以下一律**类型化拒绝、关闭流、零正式效果**（P709-A02）：

| 拒绝向量 | 处理 |
| --- | --- |
| 多流 / 冲突流（`EXTRA_STREAM` 与 `ClipData` 不一致） | 类型化拒绝 |
| `file://` / `http(s)://` 等非 `content://` URI | 类型化拒绝 |
| 无读权限 / grant 中途撤回 | 类型化拒绝（流关闭） |
| 异常 extras（缺失、类型错、未知 key） | 类型化拒绝 |
| 缺格式（未经 capability matrix 选择） | 类型化拒绝（不得直接塞旧 channel） |
| 假 MIME / 伪扩展名 | MIME/扩展名只作线索，不作判据 |
| known/unknown size 超 16 MiB | 复用 `readImportPickBounded` 超限语义（`:151-153`/`:226`），类型化 `ExceedsLimit` |
| 超过 10,000 接治候选上限 | 类型化拒绝 |

- **发送方 MIME/扩展名/声明长度只作线索**；先让用户从 Android capability matrix 选择格式，再构造含格式、inputToken 与入站身份的统一命令（计划 `:337`）。
- 复用既有有界 reader、parser 与 10,000 接治候选上限（计划 `:337`）；**不能直接 deliver 到缺 `pendingImportFormat` 的旧 channel**。
- 沿 **D-146**：不保存文件副本、不长期持有 URI、不调用无普遍保证的 `takePersistableUriPermission`；流结束/取消/失败及时关闭并释放引用（计划 `:339`）。
- 官方依据【技术门】：「MIME 不可信、二进制处理移出主线程」「临时 content grant 不能普遍持久化」（计划 `:108`、`:339`）——具体第三方发送方行为须实测（§8 开放项 9）。

### 2.4 Android 侧入口声明【冻结设计提案】

- 分享接收面需要 Android 侧声明 `ACTION_SEND` 的 `<intent-filter>`（当前 manifest 只有 `MAIN`/`LAUNCHER`，§1.1）；官方明示 `<intent-filter>` 以 action + mimeType 声明接收，`*/*` 官方劝阻，混合/未知类型须逐 URI 用 content resolver 判定（`https://developer.android.com/training/sharing/receive`、`https://developer.android.com/reference/android/content/Intent`）。
- 入口 Activity/接收点把 Intent 校验为中立请求（§2.1）；校验逻辑放 Android 组合根，**common 侧只见中立请求**。
- `ACTION_SEND` 自 JELLY_BEAN 起可 `setClipData(ClipData)` + 授予读权限；接收方 task stack 结束后临时 URI 授权即过期，唯一安全的授予方式是 `setFlags()`，**不得**用 `Uri.fromFile()`（`https://developer.android.com/training/sharing/receive`、`https://developer.android.com/training/secure-file-sharing/share-file`）。
- 有界读取执行在**非 UI 线程**（沿既有 `ImportFilePick.kt:145` 线程契约）；manifest/入口的精确声明形状（exported、launchMode、permission）OPEN（§8 开放项 1/7）。


## 3. 状态机与冲突路由【冻结设计提案；逐状态对齐计划 Q19 表，计划 `:325-335`】

外部请求到达时，按当前状态路由（**不改原 requestId/确认快照/授权时间、不自动续批**）：

| 当前状态 | 外部请求处理（冻结语义） |
| --- | --- |
| 启动 / 失败重试 | 等待 `Ready`；启动失败**不消费**请求；`Retry` 成功后**重新过导航门** |
| Idle | 路由到既有编辑/导入/看账；**不直接正式写账** |
| Editing / AwaitingConfirmation | 明确「保留当前并不切换」或「放弃后切换」；保留路径**不覆盖原草稿** |
| Submitting / UnknownCommit / ImportBatchSubmitting / 未决批次 | 挂起一个外部请求；**不改**原 requestId、确认快照或授权时间；**不自动续批** |
| P7-06 quiesce / 换库 | 暂缓导航、导入与 widget；恢复完成后按**新 generation 重校验**，旧库会话**不能写入新库** |

- 与既有启动门/租约面一致：路由只在 `Ready` 且 `facade != null` 后放行（§1.3 `App.kt:334-353`）；generation 失配到达的结果一律丢弃（沿 `P503App.kt:675/1659/1724` 与 `LedgerRuntimeOwner.kt:546-549 shouldDiscardLandingResult`）。
- 恢复/换库后的重校验沿 `LedgerRuntimeOwner.kt:494 reopen` / `:764 isCurrentGeneration` 语义（D-176 冻结面）；本文不改其协议。

## 4. 分享身份与去重【冻结设计提案；沿计划 `:335`】

- `ACTION_SEND` **没有普适可用的「同一次分享」稳定 ID**。
- **只保证**：同一**已接收 token** 的内部重复 / 配置重建**不重复接治**（幂等消费，沿 §2.2 deliveryId）。
- **新的用户分享 / 新的 `onNewIntent` 接收创建新 token**——即使 URI/字节相同，也沿 **D-146 新建导入**、交**人工疑似重复判断**（计划 `:335`）。
- **禁止**用 URI、文件名、内容哈希或时间窗**自动吞掉**用户新分享。
- **进程重建**不得凭丢失的流假称可重试：**已接治候选从 DB 恢复**，**未接治源需重新分享**（计划 `:335`、`:339`）。
- 【技术门】`onNewIntent` 后 Compose 编辑态的实际保留、单 activity 架构下静态 shortcut `CLEAR_TASK` 的真实影响，均须设备实测（§8 开放项 7/9）。

## 5. 图标快捷方式（09.B）【冻结设计提案；沿计划 §9.2，计划 `:343`】

- **优先评估动态 `ShortcutManager`**；稳定**通用** ID：「记支出 / 记收入 / 导入」——**无账户名、无分类名、无金额**（计划 `:343`；launcher 可读 shortcut 元数据，故须隐藏敏感信息）。
- 显式目标 Activity，经**已验证的 singleTop/flags 策略**交给**同一导航门**（§2）。
- **静态 shortcut 不照搬**：其首 Intent **默认强制 `NEW_TASK` + `CLEAR_TASK`**（会销毁 app 内既有 activity，可能丢草稿/打断提交）；如改用静态 **trampoline**，须**单独核实任务栈与编辑保留**（计划 `:343`、`:109`）。
- **singleTop ≠ 全应用单实例**：`singleTop` 只在实例位于栈顶时经 `onNewIntent` 复用；运行时 owner 仍须防止**多 Activity 各自开业务图**（`LedgerRuntimeOwner` 的世代/租约面不得被多实例绕过；计划 `:343`）。
- **shortcut extras 只表达路由**，**不得**成为正式提交快照（计划 `:343`）。
- 隐私：shortcut 元数据不含金额/账户名/分类名。
- 【技术门】动态 vs 静态/trampoline 的最终选择、`getMaxShortcutCountPerActivity()` 具体数值、shortcut 自定义 extras 的官方支持面，须查 API 参考/实测（§8 开放项 3/6）。

### 5.1 任务栈与 flag 语义【冻结设计提案；官方依据一句转述】

- `singleTop`：仅当目标实例位于**栈顶**时经 `onNewIntent()` 复用，否则仍新建实例；复用栈顶时 Back 无法回到旧状态（`https://developer.android.com/guide/components/activities/tasks-and-back-stack`）。
- `FLAG_ACTIVITY_NEW_TASK`：已有 task 则前置并经 `onNewIntent`（等价 singleTask）；`FLAG_ACTIVITY_CLEAR_TOP` 销毁其上的 activity 并经 `onNewIntent` 交付；`FLAG_ACTIVITY_CLEAR_TASK` 清空既有 task（`https://developer.android.com/reference/android/content/Intent`）。
- `onNewIntent` 中 `getIntent()` 仍返回**原始** Intent，须 `setIntent()` 更新（`https://developer.android.com/reference/android/app/Activity`）；送达的新授予 URI 权限会叠加，`FLAG_GRANT_READ_URI_PERMISSION` 覆盖 `ClipData` 内 URI。
- **静态 shortcut 的默认 `NEW_TASK`+`CLEAR_TASK` 不得照搬**（§5 正文）；具体 flag 组合交给同一导航门后**独立核实**（§8 开放项 3）。


## 6. 月度小组件（09.C，Q20 落地）【冻结设计提案；沿计划 §9.2，计划 `:345-351`】

### 6.1 载体与默认【冻结设计提案】

- **RemoteViews + `AppWidgetProvider`**（`AppWidgetProvider` 即 `BroadcastReceiver`）；**默认隐藏金额**，主动开启后才显示（计划 `:345`、`:351`）。
- **先用现有平台 API 验证**本月普通收入/净支出/结余/刷新时间/点击目标，**不先引** Glance/WorkManager/导航框架（计划 `:345`）；若平台短任务无法满足后台执行预算，**先给资源证据**再决定调度依赖。
- 点击经 `RemoteViews.setOnClickPendingIntent(viewId, PendingIntent)`；`PendingIntent` 显式 **`FLAG_IMMUTABLE`**，仅带**通用路由/widgetId**（计划 `:351`；官方：自 S/API 31 起必须显式指定 mutability，强荐 IMMUTABLE）。

### 6.2 共享月度快照【冻结设计提案；计算复用 §7.2】

- 返回 `ledgerId`、`month`、`currency`、**三项精确金额**、`asOf`、`generation` 与**可用/失效/失败**状态（计划 `:347`）。
- 计算**复用 §7.2 有界月份读取**（`MonthlyContributionReadPort`，§1.5）与 **P7-03 语义**，**不抄第二套公式**：归属上海自然月、精确整数 minor units、`netExpense`/`balance` 语义同 `MonthlyBuckets.kt:196-202`/`:384-397`；widget **还需收入/结余**，不能误用「仅支出」接口（计划 §7.2 收尾，计划 `:238`）。
- 有效谓词只用 `transaction_effective_state`（唯一 SQL 定义点）；void/restore 不复制第二套状态规则。

### 6.3 访问路径：经 runtime owner 受控租约【冻结设计提案】

- widget **不能**直接调用会 bootstrap/migrate 的 `buildLedgerGraph`（`App.kt:1113-1118`，§1.4）；须经 **P7-06 runtime owner 的受控租约**访问**已验证库**（`LedgerRuntimeOwner.kt:386 acquireLease`/`:432 quiesce`/`:494 reopen`；计划 `:347`）。
- 首次启动**未初始化**、**缺失/损坏库**：显示**不可用**并**引导打开应用**——**不后台造空账本、不静默迁移**（计划 `:347`；P709-A06「缺失/损坏库无新空库」）。

### 6.4 刷新语义【冻结设计提案；沿计划 `:349`】

- 刷新 = **已知正式写入成功/恢复成功的合并失效** + 平台**周期请求（≥30 分钟）** + 用户**主动刷新**；**不承诺准点或秒级**（官方：`updatePeriodMillis` 不接受 <30 分钟；计划 `:349`）。
- 覆盖：手工、导入逐项成功、金融修正、void/restore、换库、月界和重启；**纯解析候选不改变金额**（计划 `:349`）。
- **异步结果必须匹配 generation、月份及 widget 当前配置 revision**；隐藏开关变化后旧可见结果**不能重新显示金额**（计划 `:349`；P709-A07）。**旧月数据不得标「本月」，失败不得伪装零值**（P709-A05）。
- 【技术门】`updatePeriodMillis` 实际投递节流受厂商省电策略影响；是否需 WorkManager 是**门**（§8 开放项 5）。

### 6.5 隐私、缓存与 host 边界【冻结设计提案；沿计划 `:351`】

- **隐藏态 RemoteViews 载荷**（文字、`contentDescription` 等）**不含金额**（P709-A07）。
- 不同 widget 的显示设置**独立**，默认隐藏；需提示**系统桌面/锁屏由 host 控制**，**隐藏开关不能撤回历史截图**（计划 `:351`）。
- **缓存（若有）定义为可重建派生缓存**，含 `generation`/`month`/`configRevision`；**默认不落盘保存明文金额**；**换库前失效**，失败**不回退旧账本金额**（计划 `:351`）。
- widgetId/host 绑定配置是**设备本地状态，不跨设备恢复**；新增可迁移用户偏好另入 P7-06 清单。
- 【技术门】锁屏 widget 实际可用性、`MAX_INIT_VIEW_COUNT` 常量值须查 API 参考/实测（§8 开放项 6）。

### 6.6 receiver 主线程预算【冻结设计提案；沿计划 `:345`、`:110`】

- **receiver 主线程禁 DB 聚合**：`onReceive` 恒在主线程，系统上限约 10 秒否则 ANR（官方依据）；`goAsync()` 可移出主线程但**广播执行时限仍适用**（含到 `PendingResult.finish()`）。
- 超预算时**先给资源证据**再决定调度依赖（§8 开放项 5）；不得在广播主线程扫全账。
- 【技术门】receiver 主线程 DB 聚合的具体失败模式、`goAsync` 时间保证须实测（§8 开放项 8）。

## 7. 切片与验收映射（P709-A01..A09）

【设计交付，无测试在本批运行】。验收 ID 取计划 §9.3（计划 `:359-367`）；下表把每个 ID 映射到**归属切片**、设计条款并声明**闭合所需证据**（实现/设备取证属后续实施批；按 §10.2 分开记录自动测试与设备实证，计划 `:402-408`）。

| 验收 ID | 归属切片 | 设计条款 | 闭合所需证据（后续实施批） |
| --- | --- | --- | --- |
| P709-A01 | 09.0 导航门 + 09.A | §2.1/§2.2/§4 | 冷启动/热 `onNewIntent`/旋转/重组/启动失败 Retry，同 token 只接治一次；同 URI 主动再分享产生**新候选**——状态机测试 + 实际 Android 分享 |
| P709-A02 | 09.A | §2.3 | 缺格式、假 MIME、多流、无 grant/中途撤回、known/unknown size 超 16 MiB、超 10k 候选 → 类型化拒绝、流关闭、零正式效果；不持久化路径/文件名 |
| P709-A03 | 09.0 导航门 | §2.2/§3 | 编辑中保留/放弃，提交/未知/批次中分享或快捷方式、pending 已满 → 旧 snapshot/requestId 不变、Busy 可见、不自动续批 |
| P709-A04 | 09.B（含 09.0） | §5/§3 | 分享方前台、本应用后台、launcher 动态/固定快捷方式及应用升级 → 真实任务栈设备核验，无 CLEAR_TASK 丢草稿/提交，可正确返回 |
| P709-A05 | 09.C | §6.2/§6.4 | widget 与应用月卡对照：空月、跨月退款、转账费用/本金、利息、修正、void/restore → 精确金额/币种相同；溢出/读失败是失败，不能变零 |
| P709-A06 | 09.C（含 09.D 竞争腿） | §6.3/§6.4 | 20k/50k 正式交易叠加，后台刷新与恢复竞争，旧读晚到 → 无 ANR/OOM、超时释放租约；旧 generation 不覆盖；缺失/损坏库无新空库 |
| P709-A07 | 09.C | §6.4/§6.5 | 初始隐藏、主动显示再隐藏、多 widget、TalkBack → 隐藏后视图载荷和朗读无金额，旧异步结果不重新泄露 |
| P709-A08 | 09.C（09.D 复核腿） | §6.4/§6.5 | 月界、设备时区/时钟调整、杀进程/重启、清除数据、恢复备份 → 归属仍上海自然月，旧缓存无泄漏，明确更新时间/不可用 |
| P709-A09 | 09.D | 全文 + 边界断言 | 桌面客户端正常启动、手工录入、导入与月度回读 → Android 新接口未破坏 common/desktop 装配，原确认规则不变 |

设备覆盖 `minSdk 34` 与 `targetSdk 37` 的代表环境（计划 `:369`）；测试导航见 §10.5 的 T09（计划 `:443-445`：`AndroidImportFilePickPortTest.kt`、`ImportFilePickBoundedReadTest.kt`、`P503ImportReviewHostCoordinatorTest.kt`、`P503ImportBatchReducerTest.kt`、`DesktopLedgerViewCompositionRootTest.kt`、`DesktopImportBatchConfirmationCompositionRootTest.kt`——均为**新增面**，现有测试**未覆盖**系统入口）。

## 8. 开放项（本文未冻结，须裁决；不得静默丢弃）

1. **中立导航请求实签名与失败码族**：`OpenNewEntry`/`OpenImport`/`OpenCurrentMonth` 的精确 sealed 形状、`inputToken` 类型、拒绝码枚举——OPEN（本文命名均为示意），由实施规格核验。
2. **deliveryId / inputToken 生命周期精确契约**：mint 时机、是否持久化、跨进程重建的恢复语义、消费/去重判定点——OPEN（语义要求已冻结：同一已接收 token 幂等、新分享新 token，§4）。
3. **快捷方式动态 vs 静态 / trampoline 最终选择**：任务栈与编辑保留需设备核实（§5）；`getMaxShortcutCountPerActivity()` 具体数值需设备查询。
4. **widget 配置持久化与 host 隐私机制**：widgetId/host 绑定配置的存储位置（设备本地）、显示设置逐 widget 的持久化形状——OPEN；另须裁定是否需新增可迁移用户偏好条目（入 P7-06 清单，§6.5）。
5. **更新调度是否需 WorkManager 的门**：先以现有平台 API + 资源证据评估周期刷新是否达标；引入 WorkManager 属**新依赖**，须先过资源证据门（§6.4/§6.6），本文不预先授权。
6. **平台常量/能力取证**：`MAX_INIT_VIEW_COUNT` 常量值、`getMaxShortcutCountPerActivity()` 数值、锁屏 widget 实际可用性、shortcut 自定义 extras 的官方支持面——OPEN，须查 API 参考/设备实测（§5/§6.5）。
7. **ACTION_SEND 发送方兼容性实测门**：第三方分享方是否一定带 `ClipData`/读权限、`onNewIntent` 后 Compose 编辑态实际保留、静态 shortcut `CLEAR_TASK` 对单 activity 架构的实际影响——OPEN，须设备实测（§2.3/§4/§5）。
8. **receiver 主线程 DB 聚合失败模式与 `goAsync` 时间保证**：约 10 秒预算的具体失败形态（ANR/OOM）、`goAsync` 到 `finish()` 的真实可用时间——OPEN，须实测（§6.6）。
9. **第三方分享方 / 临时 grant 行为**：官方仅证明「MIME 不可信、临时 grant 不能普遍持久化」（计划 `:108`）；具体发送方与设备上的实际行为须实测（§2.3）。
10. **官方未证实能力的逐项承接（本文 C 组）**：shortcut 自定义 extras、`getMaxShortcutCountPerActivity()`、`onNewIntent` 后编辑态保留、锁屏 widget 可用性、`MAX_INIT_VIEW_COUNT=16`、`updatePeriodMillis` 投递节流、`goAsync` 30 秒无精确保证、receiver 主线程 DB 聚合失败模式——全部**不得当既有事实**，逐项列为技术门。
11. **平台载荷/TalkBack 设备隐私测试归属**：隐藏态载荷无金额、朗读无金额的测试须另做**平台载荷与设备隐私测试**（T09 行，计划 `:445`）——OPEN（属 09.C/09.D）。
12. **Q19/Q20 审批与批准编号**：本文即 Q19/Q20 的审批载体（计划 `:325-339`/`:345-351`）；批准由主代理以后续决定（编号**建议** D-225，**未登记**）登记。未批准前本文**无授权力**。

## 9. 边界断言

- 本文是**设计规格草案**（`draft`）；只冻结设计级语义；**不授权实现**、迁移、技术选型或发布——实现属后续独立实施批，不由本文授权；P709-A01..A09 全部为未来实现/验证要求，本文**无一记 PASS**，**不声称**任何设备证据（设备证据归验收批，计划 §10.2）。
- **不改 D-146 留存**：不保存文件副本、不长期持有 URI、不调用 `takePersistableUriPermission`；磁盘排队须先修订 D-146（本文不授权）。
- **不引新依赖**：无 Glance / WorkManager / 导航框架（除非先过资源证据门，§8 开放项 5）。
- **不绕过启动门/确认链**：平台入口只导航/产生草稿，不直接正式写账；路由只在 `Ready` 后放行；只有显式确认才创建或替换正式账务交易。
- **零 schema、零迁移**：本文**不分配 schema 版本号**、不创建迁移文件（沿 D-187 先例）。
- 有效谓词保持 `transaction_effective_state` 唯一 SQL 定义点；widget/分享读不另立第二套状态规则；金额为精确整数 minor units，禁浮点。
- 本文不修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；`rgXX_` 竖井、golden fixtures/expected、`.external/` 零改动。
- 示例全部匿名合成；无本机绝对路径、个人数据或工具轨迹；官方依据只引 URL + 一句转述，不复制大段原文。
