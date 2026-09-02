# P5-04.1 底部栏布局增量 — 实施规格

依据：用户 2026-09-02 线框（登记于 D-123）；范围裁决：仅底部栏布局。顶栏（☰/年月）与首页「看板」经用户裁决本批不做，推迟至后续批次（需求定义后另立决定）。

## 1. `P503TabShell`（app-ui）底部栏改为单行 Row

- 左：`NavigationBar`（Modifier.weight(1f)），三个既有 `NavigationBarItem`（icon 槽文本 首/账/析 + label 首页/账户/分析，selected 绑定 `selectedTab`，onClick dispatch `SelectTab`）保持不变。
- 右：`FloatingActionButton`（默认圆形形状），内容 `Text("+")` 且文字颜色为红色强调（`MaterialTheme.colorScheme.error`）；`contentDescription="新增支出"` 语义保留（既有 semantics 修饰符不变）；在 Row 内垂直居中、尾端 padding 24dp；onClick 仍 dispatch `StartNewExpense`。
- 移除 `Scaffold` 的 `floatingActionButton` / `floatingActionButtonPosition`（`FabPosition`）用法；`bottomBar` 承载上述 Row；content 区与 `innerPadding` 行为不变。

## 2. 不改动

reducer/状态机、其余屏幕、facade、两根组合根、既有测试断言、schema、依赖。顶栏与看板不做。

## 3. 测试与验证

- 无新增状态机测试（reducer 未动）。
- 验证命令须全绿：`:app-ui:jvmTest`、`:desktop-app:jvmTest`、`:app-ui:ktlintCheck`、`:desktop-app:ktlintCheck`、`project_docs`。
- APK 产物按 R-9 由 CI artifact 承担（push 后下载并做模拟器验收）。

## 4. 非目标

顶栏（☰/年月）、看板、月份维度、导航库、P6 视觉样式（配色/皮肤以本次红色「+」强调与既有 material3 默认为准，P6 统一调整）。
