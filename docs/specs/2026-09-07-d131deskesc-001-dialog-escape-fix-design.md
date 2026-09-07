# D131DESKESC-001 桌面 Esc 对话框修复批（设计规格）

**状态：** proposal — 本文件为缺陷 D131DESKESC-001 的 R-1 规格增量修复批（D-132 先例）草案，等待独立评审与用户批准；冻结前不授权任何代码实施。修复路线（人工门规程路线 (a)：共享状态驱动 back 禁用 + 确定性对话框侧 Esc 关闭）已经用户批准，本文件将其确定性地冻结为代码级条款（§3/§4）。批准后按既有实施路由执行（独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理最终验收），并重跑桌面 Esc 人工门复门（§5.3）。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `818baf6` 的行号；`.external/` 只读）：

- **门判定记录**：`local/artifacts/d131-desktop-esc/gate-verdict-2026-09-07.md`——2026-09-07 人工执行（main `818baf6` 新启演示实例、单一输入源、逐帧截图）：**门 FAIL**。A8-1 FAIL（日期对话框打开按一次 Esc → 对话框与编辑页同关回总览「账本为空」，草稿丢失，G04→G05）、A8-2 FAIL（TimePicker 同形，G07→G08）、③ PASS（G09/G10/G11：重选 09-15 + 表盘 08:30 → 字段 `2026-09-15T00:30:00Z` 精确）、② PASS（G11→G12：无对话框裸 Esc 正确关编辑页回首页 Tab、草稿丢弃，D-126 语义恢复）。
- **人工门规程**：`local/artifacts/d131-desktop-esc/manual-gate-procedure.md`——判定规则（A8-1/A8-2 任一 FAIL 即缺陷成立，候选编号 `D131DESKESC-001`）、机制疑点（`DesktopEscBackHandler` 的 AWT Dialog 让渡探测 + `dismissOnEscape` 焦点要求）、两条预批修复路线（a：共享状态直接禁用 backHandler；b：改 dispatcher 检测/让渡策略）。
- **交互权威条款**：`docs/specs/2026-09-03-input-ux-amount-time-design.md` §3.5（桌面 Esc 冻结行为，即本批修复目标）、§3.7（人工门计划，Esc 语义必验）、A8（验收判据）；§5/§6 验证命令与边界形式先例；§3.6 的选择器语义（尾随入口、对话框语义）在本批零改动。
- **修复路线裁决**：用户批准规程路线 (a)（共享状态驱动 back 禁用 + 确定性对话框侧 Esc 关闭）；路线 (b) 落选理由见 §2.3。
- **登记与格式先例**：docs/DECISIONS.md D-131（缺陷机制来源）、D-132（R-1 规格增量与「规格冻结 SHA-256」登记先例）、D-134/D-136（验证套件数量基线与本地 APK 组装口径 R-9 修订：`GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx2g -Dorg.gradle.workers.max=1'` + `--no-daemon --max-workers=1`）。
- **验证分工**：docs/CONTRIBUTING.md「本机与 CI 的验证分工」（:27-29）与「本机 Gradle 资源限制」（:14-25，串行、单 worker、1 GB heap）。
- **源码锚点（只读检查，worktree 基线 `818baf6`）**：
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt`：函数签名 :76-91（末位 `onClose: (() -> Unit)? = null`，:90）；`datePickerOpen`/`timePickerOpen` remember 状态 :94-95；根 `Column` 修饰链 :98-100；`DatePickerDialog` 块 :222-254（`dismissOnEscape` :250）；TimePicker `Dialog` 块 :256-311（`dismissOnEscape` :266）；`dismissOnEscape` 私有扩展 :314-327；key 相关 import 已齐备（`Key`/`KeyEventType`/`key`/`onPreviewKeyEvent`/`type`，:37-41）。
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`：`backHandler` 参数 :75；back 接线 :93-99（`editFlowBackEnabled` :93、`backHandler?.invoke(...)` :94-99，dispatch 体含 P5-04.3 双重触发守卫）；`P503EditScreen` 三处调用点 :241-274（Editing）、:309-331（RequestIdentityConflict）、:333-351（DomainRejected）；`isEditFlowBackEnabled` :384-394；`isBackDispatchSafe` :400。
  - `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt`：import `java.awt.Dialog` :48、`java.awt.Window as AwtWindow` :57；`DesktopEscBackHandler` doc 注释 :109-118（含让渡启发式描述 :114-117）、组合体 :119-142（`KeyEventDispatcher` 内 AWT Dialog 探测 :129-131）；唯一接线点 :95-97。
  - `android-app/src/main/kotlin/com/unifiedledger/android/App.kt`：`backHandler = { enabled, onBack -> BackHandler(enabled, onBack) }` :94——本批**零改动**（只读锚点）。

术语与编号约定：`本批` = D131DESKESC-001 修复批；`选择器对话框` = material3 `DatePickerDialog` 与 TimePicker `Dialog`（D-131 R2 引入的两类对话框）；`back 通道` = 桌面 `DesktopEscBackHandler`（JVM 级 `KeyboardFocusManager` dispatcher）与 Android `BackHandler` 经 `P503App` 的共享 back 派发通道；**本批局部编号 D-1..D-3、证据 E-1..E-3、测试 T-0 仅在本批内稳定使用，与全局 `docs/DECISIONS.md` 的决定编号空间不同；本批实施后整体登记为 D-137（§9）。**

## 1. 目的与范围

1. **批定义**：缺陷 D131DESKESC-001 的 R-1 规格增量修复批（D-132 先例：规格增量 → 评审 → 实施 → 验证 → 复门）。交付物第一阶段 = 本文件（冻结修复机制与验收）；批准后第二阶段 = 实施批（§4 冻结范围的代码变更 + §5 验证与复门）。
2. **缺陷一句话**：桌面端选择器对话框打开期间按一次 Esc，对话框与底层编辑页同时关闭回总览、草稿丢失（A8-1/A8-2 FAIL），违背 D-131 规格 §3.5 冻结行为「选择器对话框打开期间，Esc 不得关闭底层编辑页；底层编辑页必须保持打开、draft 保持完整」。
3. **修复目标（冻结行为复述）**：对话框打开期间 Esc（或 Android 系统返回）只关闭对话框本身，编辑页保持打开、draft 完整；对话框关闭后编辑页裸 Esc 语义（②，D-126 既有）原样恢复。
4. **范围外（本批明确不做）**：零 schema（*.sq/*.sqm、schema v27 与全部迁移文件）、零 ledger-domain/ledger-application/ledger-data、零 reducer 状态机与事件集、零导航库、零 compose ui-test harness、零新依赖、零 gradle/build 脚本、零 CI、零主题/玻璃、Android 零代码（§7）。

## 2. 缺陷与机制结论（门证据复述）

### 2.1 判定汇总（gate-verdict-2026-09-07.md 原文复述）

| 项 | 判定 | 现象 |
| --- | --- | --- |
| A8-1（日期对话框打开按一次 Esc） | **FAIL** | 对话框与编辑页同时关闭回总览「账本为空」，账户/分类/金额草稿全部丢失（G04→G05） |
| A8-2（TimePicker 打开按一次 Esc） | **FAIL** | 同形：时间对话框与编辑页同关回总览（G07→G08） |
| ③（选择器全流程） | PASS | 重选 09-15 + 表盘 08:30 + 确定 → 字段精确 `2026-09-15T00:30:00Z`，编辑页与草稿保持（G09/G10/G11） |
| ②（编辑页裸按 Esc） | PASS | 无对话框单次 Esc 正确关闭编辑页回首页 Tab、草稿丢弃（G11→G12） |

### 2.2 机制结论（E-1..E-3）

- **E-1（AWT 让渡启发式不可触发）**：`DesktopEscBackHandler` 的 `AwtWindow.getWindows().any { it is Dialog && it.isShowing }` 让渡探测在**当前 Compose Multiplatform 栈探测不到选择器对话框**。门证据：G07/G08 实证——TimePicker 对话框打开期间 Esc 仍落入 back 通道（A8-2 FAIL 的机制前提），若探测生效则 Esc 应被让渡给对话框；规程机制注记同证——material3 选择器对话框打开期间进程只有一个可见顶层窗口（Win32 EnumWindows），即 compose 选择器对话框**不映射为可见 AWT `Dialog` HWND**（无论焦点在何处）。② PASS 同时证明 back 通道本身工作正常——A8 FAIL 不是 back 通道故障，而是让渡探测失效 + 对话框未吸收。
- **E-2（`dismissOnEscape` 焦点受限）**：`P503EditScreen.kt` 的 `dismissOnEscape`（:314-327）挂在对话框内容侧，要求键盘焦点在对话框内容内才触发；门证据显示对话框打开期间键盘焦点默认仍在主窗口内容（金额字段等），故其未拦下 Esc。
- **E-3（组合复现）**：② PASS + A8-1/A8-2 FAIL 的组合恰好复现「AWT 探测失效 → Esc 落入 back 通道 → backHandler enabled → 编辑页关闭」的机制链。

### 2.3 修复路线选择（已裁决）

- **路线 (a)（本批采纳，用户批准）**：app-ui 已知 `datePickerOpen || timePickerOpen`（共享状态），对话框打开期间直接禁用 backHandler（Esc/系统返回不再进入 back 通道），同时在编辑页根以 `onPreviewKeyEvent` 确定性关闭对话框（不依赖焦点位置），并保留对话框内容侧 `dismissOnEscape`（焦点在对话框内路径）。
- **路线 (b)（落选）**：改 dispatcher 检测/让渡策略。落选理由：E-1 证明当前 CMP 栈中对话框不产生任何可被 AWT 层探测的窗口信号，窗口级检测无可靠判据——任何窗口启发式（含变体）都只是把缺陷从「关整页」换成「探测失败时仍关整页」，且新增检测面不可验证。共享状态（app-ui 的 pickerOpen）是唯一确定来源，是路线的决定性证据。

## 3. 修复机制（冻结）

### 3.1 机制总览与事件流矩阵（冻结）

机制由三件互相独立、可分别论证的改动组成：

1. **共享状态上报**：`P503EditScreen` 经可选回调参数上报 `(datePickerOpen || timePickerOpen)` 至 `P503App` 持有的 `editDialogOpen` 状态（D-1）。
2. **back 通道门控**：`P503App` 有效 back enabled = `isEditFlowBackEnabled(state) && !editDialogOpen`——对话框打开期间 back 通道（桌面 dispatcher / Android BackHandler）整体禁用（D-2）。
3. **确定性对话框侧关闭**：编辑页根 `onPreviewKeyEvent` 在对话框打开时对 Escape KeyDown 关闭开着的对话框并消费；对话框内容侧既有 `dismissOnEscape` 保留覆盖焦点在对话框内路径（D-3）。

**事件流矩阵（冻结，键盘焦点位置 × 对话框开闭）：**

| 场景 | 焦点位置 | 事件接收者 | 结果 |
| --- | --- | --- | --- |
| 对话框开 + Esc | 主窗口内容（如金额字段） | JVM dispatcher pass（back 禁用）→ 编辑页根 `onPreviewKeyEvent` 收 Event 并消费 | 仅对话框关闭；编辑页保持、draft 完整（A8-1/A8-2 目标） |
| 对话框开 + Esc | 对话框内容 | JVM dispatcher pass → 对话框内容 `dismissOnEscape` 收 Event 并消费 | 仅对话框关闭（既有路径，零改动） |
| 对话框关 + Esc | 主窗口内容 | JVM dispatcher（back enabled）消费 | 编辑页关闭回来源 Tab、草稿丢弃（②，D-126 语义原样） |
| 对话框开 + 系统返回（Android） | —（系统级） | app BackHandler 禁用 → 官方 Dialog 原生吸收 | 仅对话框关闭（D-131 §3.5 既有认定，零代码） |
| 对话框关 + 系统返回（Android） | — | app BackHandler（enabled）→ P5-04.3 守卫派发 | 编辑页关闭回来源 Tab（既有语义原样） |

**双触发不可能性论证（写入规格供评审对照）**：编辑页根 handler 与对话框内容 `dismissOnEscape` 位于**不同的 subcomposition/焦点链**——桌面端对话框为独立窗口（独立场景与焦点链），Android 端对话框为独立窗口层；一次 KeyDown 事件只沿焦点所在那一条链分发，根 handler 只有焦点在主窗口内容链上才可见、`dismissOnEscape` 只有焦点在对话框内容链上才可见，二者对同一按压缩互斥。拦截方消费（返回 true）后事件不会继续派发，因此不存在「两个 handler 先后各关一次」的路径；即便根 handler 与 dispatcher 都看到事件，dispatcher 在对话框打开期间因 back 禁用而 pass（不消费），事件继续到达焦点链，恰一个 handler 处理。（同批风险 R-1，§8。）

### 3.2 app-ui：`P503EditScreen.kt` 变更（冻结）

1. **签名（冻结）：** 在末位参数（`onClose`，:90）之后追加可选参数 `onDialogVisibilityChanged: (Boolean) -> Unit = {}`。默认参数保证既有调用点（仅 `P503App` 三处）与全部现有测试零影响（app-ui 测试为纯 reducer/状态机 JVM 测试，无任何测试直接组合该 composable）。零新增依赖；新增 `androidx.compose.runtime.LaunchedEffect`/`DisposableEffect`/`rememberUpdatedState` 三个 runtime import（key 事件相关 import 已齐备，:37-41）。
2. **上报（冻结）：** 在 `datePickerOpen`/`timePickerOpen` 声明（:94-95）之后的组合体内：

```kotlin
val latestOnDialogVisibilityChanged by rememberUpdatedState(onDialogVisibilityChanged)
LaunchedEffect(datePickerOpen, timePickerOpen) {
    latestOnDialogVisibilityChanged(datePickerOpen || timePickerOpen)
}
// 防御性自愈：编辑屏离开组合时补报关闭，防止 editDialogOpen 陈旧滞留
DisposableEffect(Unit) {
    onDispose { latestOnDialogVisibilityChanged(false) }
}
```

   `rememberUpdatedState` 防陈旧回调（与 `Main.kt` `latestOnBack` 同款模式）；`DisposableEffect(Unit)` 的 onDispose 补报为防御性条款——按事件流矩阵，对话框是顶层形态、所有合法退出路径都先关对话框（互斥流保证 `timePickerOpen` 与 `datePickerOpen` 永不同时为 true：日期确定先 `datePickerOpen = false` 再 `timePickerOpen = true`，:237-238），正常路径下 onDispose 时对话框必已关闭；补报仅覆盖任何不可预见路径的陈旧滞留（§8 R-4）。
3. **根 Escape 关闭（冻结）：** 根 `Column` 修饰链（:98-100，`fillMaxSize().padding(16.dp).verticalScroll(...)`）末尾追加 `onPreviewKeyEvent`：

```kotlin
.onPreviewKeyEvent { event ->
    // D-137: 仅当选择器对话框打开且 Escape KeyDown 时，关闭开着的对话框并消费；
    // 焦点在主窗口内容时由本 handler 兜底（对话框内容路径由 dismissOnEscape 覆盖）。
    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
        when {
            timePickerOpen -> {
                timePickerOpen = false
                true
            }
            datePickerOpen -> {
                datePickerOpen = false
                true
            }
            else -> false
        }
    } else {
        false
    }
}
```

   语义冻结：仅有「任意选择器对话框打开 + Escape KeyDown」一个触发条件；关闭恰好开着的那个对话框（互斥流保证；`when` 顺序冻结为 time 优先，实施不得改为先关 datePicker 或同时清两者）；关闭成功返回 `true`（消费，事件不再派发），否则返回 `false`（pass——对话框关闭时裸 Esc 语义、非 Escape 键、Android 端不可见路径全部原样）。
4. **`dismissOnEscape`（冻结为零改动）：** 本体（:314-327）与两处挂点（:250、:266）原样保留，覆盖焦点在对话框内容路径；其与根 handler 的双触发不可能性见 §3.1。`DatePickerDialog`/`Dialog` 块本体、确定/取消按钮、picker 状态逻辑（:222-254、:256-311）全部零改动。

### 3.3 app-ui：`P503App.kt` 变更（冻结）

1. **新增状态（冻结）：** 在既有 remember 状态声明区（:81-84 附近）新增 `var editDialogOpen by remember { mutableStateOf(false) }`（与 `statusCheckInFlight` 同区；`mutableStateOf`/`remember`/`getValue`/`setValue` import 已齐备）。
2. **back 有效标志（冻结）：** :93 行 `val editFlowBackEnabled = isEditFlowBackEnabled(state)` 保持；:94 行调用改为 `backHandler?.invoke(editFlowBackEnabled && !editDialogOpen) { ... }`——**派发体（:95-99 P5-04.3 双重触发守卫与 `isBackDispatchSafe`）逐字节不变**。:90-92 的 P5-04.2 注释追加一句 D-137 注记（对话框打开期间 back 通道额外禁用，Esc/系统返回只到达对话框层）。
3. **三个调用点传回调（冻结）：** `P503EditScreen` 三处调用（:241-274 Editing、:309-331 RequestIdentityConflict、:333-351 DomainRejected）各追加 `onDialogVisibilityChanged = { editDialogOpen = it }`——三处行为一致；任意时刻至多一个 `P503EditScreen` 在组合中（`when(current)` 单分支），`editDialogOpen` 共享状态无歧义。
4. **`isEditFlowBackEnabled`/`isBackDispatchSafe`（冻结为零改动）：** :384-394/:400 本体不动；`&& !editDialogOpen` 只作用于有效标志计算，不改变守卫函数语义。

### 3.4 desktop-app：`Main.kt` 变更（冻结）

1. **dispatcher 体（冻结）：** `DesktopEscBackHandler`（:119-142）内 `KeyEventDispatcher` 的让渡分支（:129-131）整体移除，简化为：

```kotlin
KeyEventDispatcher { event ->
    if (enabled && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
        latestOnBack()
        true
    } else {
        false
    }
}
```

   语义冻结：`enabled && KEY_PRESSED && VK_ESCAPE → onBack + 消费，否则 pass`；`enabled` 现为权威信号（由 `P503App` 按 `editDialogOpen` 门控，§3.3）；`rememberUpdatedState`、`DisposableEffect` 结构、注册/注销（:124-140）原样。
2. **doc 注释重写（冻结）：** :109-118 注释改为说明：(a) `enabled` 旗标为 back 通道唯一门（P503App 在对话框打开期间置 false，D-137）；(b) 原 AWT 窗口让渡启发式已移除——门证据 E-1（G07/G08 + 规程机制注记）：当前 CMP 栈 compose 选择器对话框不映射为可见 AWT `Dialog` HWND，该探测不可触发、只会掩盖缺陷；(c) 对话框内容路径的 Esc 关闭由 `P503EditScreen` 的 `dismissOnEscape` 承担。
3. **import 清理（冻结）：** 移除随之失效的 `java.awt.Dialog`（:48）与 `java.awt.Window as AwtWindow`（:57）两个 import（不清理则 ktlint unused-import 违规）。
4. **组合 API（冻结为零改动）：** `DesktopEscBackHandler(enabled, onBack)` 签名与唯一接线点（:95-97）原样。

### 3.5 Android：`App.kt` 零代码变更及其语义论证（冻结）

- **零改动：** :94 `backHandler = { enabled, onBack -> BackHandler(enabled, onBack) }` 原样；android-app 本批零代码变更。
- **语义论证（写入规格）：** 对话框打开期间 `editDialogOpen == true` → app `BackHandler` 禁用 → 系统返回不再进入共享 back 通道，回退到当前活跃对话框层；androidx Compose `Dialog`（`DatePickerDialog`/TimePicker `Dialog` 底层）**原生拦截系统返回并仅调用 `onDismissRequest`**（关闭对话框，不触碰编辑页）——这正是 D-131 §3.5「Android 侧系统返回在对话框打开时由官方 Dialog 自吸收，行为天然满足冻结语义」的既有认定；本批使该行为成为确定路径（此前系统返回同样被 Dialog 吸收，back 通道的 enabled 状态对本路径无影响）。对话框关闭后再次系统返回 → `BackHandler`（enabled）→ P5-04.3 守卫派发 → 编辑页关闭回来源 Tab（既有语义原样）。
- **验证：** 仅此论证不构成证据——按 §5.4 在 Android 模拟器执行抽查（开选择器 → 设备返回 → 仅对话框关、草稿保留 → 再返回 → 编辑页关）。

### 3.6 与既有语义的相容性（冻结）

- ② 裸 Esc 语义、D-126 草稿丢弃语义：零改动（事件流矩阵第三行）。
- P5-04.3 双重触发守卫、`isBackDispatchSafe`、Submitting 吸收：零改动。
- 可见「关闭」入口（P5-04.3，`onClose`）、确认/取消按钮、失败态（RequestIdentityConflict/DomainRejected）路径：零改动。
- **未来对话框种类（登记义务）：** 编辑流内未来新增任何对话框形态（含非 Esc 相关），必须复用同一模式——经 `onDialogVisibilityChanged` 上报可见性以门控 back 通道，并在根 `onPreviewKeyEvent` 内登记对应 Esc 关闭分支（或在累积到第二类对话框时抽取共享 helper，另立小批）；不得在 dispatcher 恢复任何窗口级探测。

## 4. 代码变更清单（按文件冻结）

| # | 文件（worktree 相对路径） | 变更 | 锚点 |
| --- | --- | --- | --- |
| 1 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt` | 签名末位追加 `onDialogVisibilityChanged: (Boolean) -> Unit = {}`；`rememberUpdatedState` + `LaunchedEffect` 上报 + `DisposableEffect(Unit)` 防御补报；根 `Column` 修饰链追加 `onPreviewKeyEvent` Escape 分支；新增 3 个 runtime import | :90/:94-95/:98-100 |
| 2 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt` | 新增 `editDialogOpen` remember 状态；:94 有效标志改 `editFlowBackEnabled && !editDialogOpen`（派发体零改动）；三处调用点传回调；P5-04.2 注释加 D-137 注记 | :81-84/:93-99/:241-274/:309-331/:333-351 |
| 3 | `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt` | dispatcher 移除 AWT Dialog 让渡分支；doc 注释重写；移除 2 个失效 import | :109-142/:48/:57 |
| 4 | `android-app/src/main/kotlin/com/unifiedledger/android/App.kt` | **零改动**（只读锚点） | :94 |
| 5 | 测试 | **零新增**（论证见 §5.2）；受影响既有套件全绿（§5.1） | — |
| 6 | 文档 | 本规格（§3/§4 冻结）+ docs/DECISIONS.md 追加 D-137 登记（本批规格阶段写入） | — |

实现批不得扩大本表；发现承载缺口须先回本规格修订并过评审门，不得静默变更（D-132 §5 同款纪律）。

## 5. 测试与验证计划

资源旗标遵循 CONTRIBUTING：本机 16 GB 主机上 Gradle/Kotlin 验证必须串行；每次验证前后 `gradlew --stop`；单命令 heap 不变（`GRADLE_OPTS=-Xmx1024m`、`-Dkotlin.daemon.jvmargs=-Xmx1024m`、`--max-workers=1`、`--no-daemon`、`--rerun-tasks`、`--warning-mode all`）。一次只运行一个命令。

### 5.1 受影响既有套件（必须全绿）

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :desktop-app:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :android-app:testDebugUnitTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :app-ui:ktlintCheck :desktop-app:ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

- `:app-ui:jvmTest`：61 tests（D-134/D-136 基线）——本批唯一代码模块，reducer/状态机测试零回归（reducer 零改动）。
- `:desktop-app:jvmTest`：5 tests（含 DesktopSkeletonSmokeTest）——desktop 组合根回归；现有测试不引用 `DesktopEscBackHandler` 内部（已核验），启发式移除无测试面影响。
- `:android-app:testDebugUnitTest`：7 tests——App.kt 零改动，纯回归。
- `ktlintCheck`（app-ui + desktop-app）：0 违规——覆盖 3 个变更文件的 lint 面（含 import 清理核验）。
- `:android-app:compileDebugKotlin`：Android 编译门（共享 app-ui 签名变更的编译面）。
- `project_docs`：正式文档验证（本规格为新 docs/specs 文件、DECISIONS.md 为新登记条目）。
- 聚合门按 AGENTS 验证路由归 CI（同提交 CI 成功为发布证据），本机不重复 `check`/APK/桌面 build。

### 5.2 新增单元测试裁决：**不新增**（冻结）

本批变更是组合接线（回调上报 + 有效标志计算 + key 事件分发），不引入任何新的纯逻辑。仓库既有纪律「不引入 compose ui-test harness」（D-131 §4/边界；app-ui 测试为纯 reducer/状态机 JVM 测试）依旧成立：`dismissOnEscape`（:314-327）与 `DesktopEscBackHandler`（Main.kt:119-142）迄今**仅由桌面键盘人工门覆盖**（D-131 §3.5/§3.7 A8），不存在 JVM 测试缝——新根 `onPreviewKeyEvent` 与 dispatcher 的行为本质是「事件沿真实窗口/焦点链分发到哪条链」的平台行为，纯 JVM 单测要么引入 ui-test harness（新依赖、越界），要么只能断言退化为平凡布尔谓词、无法反映真实焦点路由，价值低于成本。故本批确定性验证 = 桌面复门（A8-1/A8-2/③/②，§5.3）+ Android 模拟器抽查（§5.4），与 A8 判据的既有人工门属性一致。

### 5.3 桌面复门（按 `manual-gate-procedure.md` 重跑，全部判据）

- 启动 `.\gradlew.bat :desktop-app:run`（可加 `--offline`，单一输入源、无并发注入）。
- **A8-1**：填草稿（账户 + 分类 + `2.50`）→ 点「选择」→ 日期对话框打开 → 按一次 Esc → **PASS = 仅对话框关闭；编辑页保持打开；账户/分类/金额草稿原样保留**。
- **A8-2**：选日期 15 → 确定 → TimePicker 打开 → 按一次 Esc → **PASS = 仅对话框关闭；编辑页保持打开；时间未写入**。
- **③**：重选 09-15 → 确定 → 表盘设 08:30 → 确定 → 发生时间字段精确 `2026-09-15T00:30:00Z`、编辑页与草稿保持。
- **②**：无对话框按一次 Esc → 编辑页关闭回首页 Tab、草稿丢弃。
- （可选）规程第 9 步确认页 Esc 覆盖。
- 证据入 `local/artifacts/d131-desktop-esc/`（`recheck-` 前缀）或按主代理登记约定；四项全 PASS → 门关闭。

### 5.4 Android 模拟器抽查（按 §3.5 论证的实证）

- 工具：android-emulator MCP；AVD `ul_p6_api37`（首选，项目既有）或 `ul_p5_test`（API 36）。
- APK：本地 `:android-app:assembleDebug`（GRADLE_OPTS 按 D-136 登记的 CI 全口径 `'-Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx2g -Dorg.gradle.workers.max=1'` + `--no-daemon --max-workers=1`；R-9 机制澄清：GRADLE_OPTS 直传 `-Xmx` 只作用于客户端，构建 JVM 吃 `org.gradle.jvmargs`），APK SHA-256 登记。
- 步骤：进入编辑页 → 填草稿 → 点「选择」开日期对话框 → **设备返回键 → 仅对话框关闭、编辑页保持、草稿保留** → 再次返回键 → 编辑页关闭回来源 Tab → 重进编辑，TimePicker 同形验证 → 无对话框返回键关闭编辑页（既有语义回归）。
- 判据：上述全部满足 = PASS；任一不符 = FAIL（回 §3 修订，禁止静默改方案）。

## 6. 验收判据（复门映射）

- **A-1（复门核心）**：桌面复门四项全 PASS——A8-1 PASS、A8-2 PASS、③ PASS、② PASS（§5.3，判定标准逐字 = 规程）。
- **A-2（Android 抽查）**：§5.4 模拟器抽查全 PASS（含对话框关闭后再次返回关编辑页、既有返回语义回归）。
- **A-3（套件）**：`:app-ui:jvmTest` 61、`:desktop-app:jvmTest` 5、`:android-app:testDebugUnitTest` 7 全绿。
- **A-4（静态与编译）**：`ktlintCheck`（app-ui + desktop-app）0 违规；`:android-app:compileDebugKotlin` exit 0。
- **A-5（文档）**：`project_docs` 通过（本规格 + DECISIONS.md D-137 条目）。
- **A-6（评审与聚合）**：实施评审与 distinct verifier 通过；同提交 CI 成功（聚合门发布证据，AGENTS 验证路由）。
- **A-7（边界核查）**：§7 逐项零触碰（含 App.kt 逐字节零改动、dismissOnEscape 与派发体逐字节零改动、DesktopEscBackHandler API 不变）。

## 7. 边界（明确不做）

- 零 schema：*.sq/*.sqm、schema v27 与全部迁移文件不动；零账务/对账/导入语义变化。
- 零 ledger-domain / ledger-application / ledger-data 变更。
- 零 reducer 状态机与事件集变更（`P503Reducer`/`P503UiEvent`/`P503AppState` 本体零改动；`isEditFlowBackEnabled`/`isBackDispatchSafe` 本体零改动）。
- 零导航库引入；零 compose ui-test harness；零新依赖（含零测试依赖）；零 gradle/build.gradle.kts 变更；零 CI 变更。
- 零主题/玻璃/视觉变更（P503Theme、Glass 包、P503TabShell 零触碰）。
- `DesktopEscBackHandler` 组合 API（enabled/onBack）不变；`dismissOnEscape` 本体与挂点不变；确认/取消/选择按钮语义不变；Submitting 吸收语义不变；失败态（冲突/拒绝）Back 语义不变。
- Android `App.kt` 零改动（唯一改动面在共享 app-ui，经编译门与抽查回归）。
- 不做多对话框同时打开支持（互斥流不变）；不做 dispatcher 任何新检测/让渡机制。

## 8. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 焦点在对话框内时 Esc 被根 handler 与 `dismissOnEscape` 双触发、对话框关两次或编辑页误关 | 双关（第二次无对话框可关，空操作）或语义错乱 | 不可能：两 handler 位于不同 subcomposition/焦点链（§3.1），一次 KeyDown 恰沿一条链分发、恰被其一接收；接收方消费（true）后不再派发。desktop 对话框为独立窗口/焦点链、Android 对话框为独立窗口层，root handler 只可见主窗口内容链事件。A8-1/A8-2 复门实证 |
| R-2 | Android 系统返回依赖官方 Dialog 原生吸收；OEM/版本差异下 Dialog 未拦截返回 → 活动默认返回（退出应用） | 对话框打开时返回键退出应用 | 该吸收机制自 D-131 起即为 A8 依赖面（§3.5 认定），本批仅使 back 通道确定性让路，未新增依赖；模拟器抽查（§5.4）为实证；残余 OEM 差异登记为未验证面 |
| R-3 | 玻璃/胶囊交互回归（backHandler 接线影响视觉层） | 无 | 预期无：本批为行为-only 接线（有效标志计算 + key 路由），零渲染路径触碰（§7 主题/玻璃零改动）；desktop/app-ui 套件与复门覆盖行为面 |
| R-4 | `editDialogOpen` 陈旧滞留（编辑屏离开组合而对话框未报关）→ back 通道在后继编辑会话中失效 | 裸 Esc 失效、编辑页无法经 back 关闭 | 按构造不可达（对话框为顶层形态、全部合法退出路径先关对话框、互斥流 :237-238）；防御性 `DisposableEffect(Unit)` onDispose 补报 false 自愈（§3.2-2）；② 复门实证 |
| R-5 | 未来编辑流新增对话框形态遗漏本模式 | 新对话框重现同类缺陷 | 登记义务已冻结（§3.6）：必须复用上报 + 根 Esc 关闭模式，不得恢复窗口探测；累积到第二类时抽取共享 helper 另立小批 |

## 9. 批准后的登记路径

1. 独立规格评审（规格评审 ID 由评审者给出；评审意见闭环后）→ 本文件状态行由 `proposal` 翻转 `approved` → 主代理按常设授权批准并冻结：`docs/DECISIONS.md` 追加 **D-137**（内容 = 缺陷 FAIL 证据 + 机制结论 + 修复路线 (a) + 本规格 §3/§4 冻结条款 + 范围 + 验证/复门计划 + 关闭判据；规格冻结 SHA-256 由主代理在规格冻结/提交时填入占位行 `规格冻结 SHA-256：__FILL_AT_FREEZE__`）。
2. 实施批另开（待执行）：独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理最终验收；实施批同时完成 §5 全部验证与 §5.3/§5.4 复门与抽查，复门通过后登记验收注记。
3. 批准与登记不触发提交、推送或 CI 变更；push 由主代理按既有授权流程执行。仓库正式文档（CURRENT_STATE 等）的后续更新随主代理收口流程，不属本批。

## 边界断言（本批规格阶段不含）

- 本批规格阶段唯一写入 = 本新文件 + `docs/DECISIONS.md` 追加 D-137 一条；`docs/specs/` 既有文档与全部模块源码/测试/构建脚本/CI 零改动；本文件不含本机绝对路径、个人数据、账务锚点、agent/会话痕迹；`.external/` 零引用、零改动；writer 不执行任何 Git 写操作。
- 本文件为实施规格草案：未经批准不构成实施授权。