# P5-04.1 底部栏悬浮样式与垂直对齐增量 — 实施规格

依据：用户 2026-09-02 指令（登记 D-124）：(1) Tab 栏做悬浮样式（脱离屏幕边缘的圆角面板）；(2) 「+」按钮中心与 Tab 栏中心处于同一水平线。

## 1. `P503TabShell` bottomBar 重构（在 D-123 基础上）

- 外层 Row：`Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 24.dp, bottom = 12.dp)`，`verticalAlignment = Alignment.CenterVertically`（系统 inset 由外层统一承担）。
- 左：`Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(percent = 50), tonalElevation = 3.dp, shadowElevation = 6.dp)` 内嵌 `NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0.dp))`；三个 `NavigationBarItem` 原样（icon 槽文本 首/账/析 + label 首页/账户/分析，selected 绑定 `selectedTab`，onClick dispatch `SelectTab`）。
- 右：`Spacer(Modifier.width(16.dp))` + `FloatingActionButton`（默认圆形），内容 `Text("+", color = MaterialTheme.colorScheme.error)`，保留 `semantics { contentDescription = "新增支出" }`，onClick 仍 dispatch `StartNewExpense`；不再携带自身 end padding（由外层 Row end padding 承担）。
- 垂直对齐原理：`NavigationBar` 的 `windowInsets` 归零后其高度即为内容高度，外层 Row 的 `CenterVertically` 使 FAB 中心与 Tab 条中心严格同线；系统 inset 由 `navigationBarsPadding` 统一加在整行之外。

## 2. 不改动

reducer/状态机、其余屏幕、facade、两根组合根、既有测试断言、schema、依赖（所需 API 均为当前 material3 1.9.0 / compose foundation 既有：Surface、WindowInsets、navigationBarsPadding、RoundedCornerShape）。

## 3. 测试与验证

- 无新增状态机测试（reducer 未动）。
- 验证命令须全绿：`:app-ui:jvmTest`、`:desktop-app:jvmTest`、`:app-ui:ktlintCheck`、`:desktop-app:ktlintCheck`、`project_docs`。
- APK 产物按 R-9 由 CI artifact 承担（push 后下载并做模拟器验收：悬浮圆角面板、FAB 与 Tab 同一水平线）。

## 4. 非目标

顶栏（☰/年月）、看板、月份维度、导航库、material3 升级（`HorizontalFloatingToolbar` 等新组件需升级 material3，归 P6/SDK 证据门）；最终配色、圆角半径与阴影归 P6 调整。
