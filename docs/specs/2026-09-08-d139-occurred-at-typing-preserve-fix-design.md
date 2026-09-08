# 发生时间键入保留缺陷修复批 D-139（设计规格）

**状态：** approved — 本文件为 D138TYPING-001 缺陷修复批（D-139）的实施规格（已冻结 2026-09-09）。独立评审：REJECT（P0-1 冲突流残留断言过强、P1-1 等值回写子条款冲突）→ 处方闭环 → CLOSURE-APPROVE（同评审 delta 复查，无新增 P0/P1）。本批方向已获用户批级批准（2026-09-08，「批准 D-139 修复批（缺陷登记 + 规格增量 + 独立评审 + 实施 + 复验 + 双端人工门 + 合入）」）；冻结 SHA-256 回填 `docs/DECISIONS.md` D-139 条目（哈希链自 D-138 终值续）。实施按既有路由执行（本 worktree、单一 bounded writer、独立评审、distinct verifier、主代理最终验收）。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为 worktree 基线 `84e2ebc` 的实读行号；`.external/` 只读）：

- **主检出根指引**：主检出 `AGENTS.md`（worktree handoff 指定入口；外部证据门、验证分工与高风险路由按其执行）。
- **被违反的冻结条款**：D-138 规格 `docs/specs/2026-09-08-d138-occurred-at-lenient-manual-input-design.md` §2.4「手工键入的文本原样保留在文本框中（不重写为 ISO 串）」；对应决定条目 `docs/DECISIONS.md` D-138。
- **用户报告原文（缺陷判据，verbatim）**：「我输入2026-09-15的时候，输入到数字"5"的时候，它会自动补全后面的时间，然后选择2026-09-01的时候，点到"1"的时候，会直接变成2026-08-31和时间编码」。
- **源码锚点（worktree 实读）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt:98`（根因：`remember(draft.occurredAt)` keyed 文本状态）、`:229-239`（D-138 parse-on-type `onTextChange`：`:234` lenient 解析、`:235` 错误态、`:236-238` 有效派发 `UpdateOccurredAt`）、`:322-347`（TimePicker 确认回调：`:337` round-trip、`:338` DST fail-closed 错误态、`:340` 派发）、`:244-260`（Continue 门，`:251` `occurredAtTextReconciles`）、`:225`/`:227`（错误/辅助冻结文案）、`:119-137`（D-137 root `onPreviewKeyEvent`）、`:104-111`（`rememberUpdatedState`/`LaunchedEffect`/`DisposableEffect`：`onDialogVisibilityChanged` 接线与防御性补报）；`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:230`（`when (val current = state)` 条件宿主）、`:244-279`/`:313-338`/`:339-360`（三个 `P503EditScreen` 调用点）、`:85`/`:278`（`editDialogOpen` D-137 共享态）；`P503DraftValidation.kt` `occurredAtTextReconciles`（门，零改动）；`P503Reducer.kt` `UpdateOccurredAt`（零改动）；`ledger-application` `ParseManualExpenseOccurredAt`（零改动）。

术语与编号约定：`本批` = D-139 发生时间键入保留缺陷修复批；`缺陷编号` D138TYPING-001 仅在本批内稳定使用，与全局决定编号空间不同。

## 1. 目的与范围

### 1.1 目的

- 恢复 D-138 §2.4 冻结的显示语义：**主编辑流（Editing）**内手工键入的发生时间文本在任何键入阶段**原样保留**在文本框中，绝不因 parse-on-type 派发而被改写为 ISO 串或回退日期。冲突/拒绝流（RequestIdentityConflict/DomainRejected）的同类键入残留不在本批（§2.6，遗留缺陷 D138TYPING-002，扩范围与否留用户裁决）。
- 消除键入中断：前缀首次有效派发后用户可以**继续键入**（如继续补时间部分），不再被 ISO 串覆盖导致后续按键追加在错误文本尾。
- 显示语义其余部分不变：选择器确认仍回写 ISO 串（D-131/D-138；等值选择保持当前文本，§2.2 条件回写）；Continue 门 P503IMPL-Q-001 不变量（显示文本必须重解析为与 draft instant 逐值相等）不变。

### 1.2 范围（冻结）

**范围内：** 仅 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt` 发生时间文本状态机制（§2 两处改动）。

**范围外（本批明确不做，逐项冻结）：**

- **零 schema 变更、零新依赖**；`ledger-domain`、`ledger-application`（含 `ParseManualExpenseOccurredAt`）、`ledger-data` 零改动。
- `P503Reducer.kt`、`P503DraftValidation.kt`（含 `occurredAtTextReconciles` 与 `errors()`）、`P503LedgerFacade.kt`、`P503App.kt`、组合根（`desktop-app` `Main.kt` / `android-app` `App.kt`）零改动。
- parse-on-type 三分支语义（D-138 §2.3）零改动；错误/辅助文案零改动（`:225`「无法识别的时间格式，示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z」、`:227`）。
- D-137 Esc/back 全路径零改动：root `onPreviewKeyEvent`（`:119-137`）、`dismissOnEscape`、`DesktopEscBackHandler`、`editDialogOpen`（`P503App.kt:85`/`:278`）。
- 冲突/拒绝流键入残留（D138TYPING-002）零改动：该两态的 `UpdateOccurredAt` 跨 when 分支转 Editing（`P503Reducer.kt:237-238`/`:263-264`）→ 组合实例重建改写文本——机制在 reducer 跨分支转换与实例重建，不在本批文本状态机制内（§2.6）。
- CI/构建脚本/主题零改动；`.external/` 零触碰。
- **零新增单元测试**（权衡见 §2.3）；既有套件保持绿。

## 2. 机制（冻结）

### 2.1 发生时间文本状态去 key

现状（根因，`P503EditScreen.kt:98`）：

```kotlin
var occurredAtText by remember(draft.occurredAt) { mutableStateOf(draft.occurredAt?.toString() ?: "") }
```

`remember` 以 `draft.occurredAt` 为 key：D-138 parse-on-type 在键入前缀首次解析成功时派发 `UpdateOccurredAt(instant)`（`:236-238`），`draft.occurredAt` 变化 → key 变化 → 文本状态被**重新初始化**为 `instant.toString()`（ISO 串），覆盖用户正在键入的前缀。逐字对应缺陷向量：键入 `2026-09-15` 至「5」时 date-only 格式 (d) 恰好完整 → 文本改写为 `2026-09-14T16:00:00Z`（= 本地 09-15 00:00，Asia/Shanghai）；键入 `2026-09-01` 至末位「1」→ 改写为 `2026-08-31T16:00:00Z`。

修复（唯一代码改动之一）：

```kotlin
var occurredAtText by remember { mutableStateOf(draft.occurredAt?.toString() ?: "") }
```

- 行为变化：`draft.occurredAt` 的任何变化（parse-on-type 有效派发、选择器派发）不再重新初始化文本状态；**主编辑流（Editing）组合实例内，键入路径任何情况下不重置文本**（范围边界：冲突/拒绝流见 §2.6）。
- 初值语义保留：组合时若 `draft.occurredAt` 非空（提交失败重试、冲突/拒绝页重进编辑屏），文本初始化为该 instant 的 ISO 串——与 D-138 §2.4「选择器产物/外部写入显示为 ISO 串」既有显示语义一致。

### 2.2 选择器确认条件回写文本

现状：TimePicker 确认回调（`:322-347`）只派发 `onUpdateOccurredAt(instant)`（`:340`），文本同步此前完全依赖 §2.1 去掉的 key 重置；去 key 后必须显式补齐，否则选择器确认后文本框不再显示所选时间（显示语义回归）。

修复（唯一代码改动之二）：在 `:339-341` 的 `if (instant != null)` 块内，派发的同时**条件回写** `if (instant != draft.occurredAt) { occurredAtText = instant.toString() }`（允许一行说明性注释，约束 = 文本状态无 key、选择器产物须显式同步）。条件式精确保持 D-138 §2.4 全部既有子场景：等值选择（picked 值恰等于现有 draft instant）时文本保持当前文本（用户键入的友好格式原样保留，D-138 该冻结子条款零语义变更）；draft 为 null 或 picked 值不同（含此前文本无效被覆盖）时回写 ISO 串。

- DST fail-closed 路径（`instant == null`，`:338`）零改动：不派发、不写文本、错误态照旧（绝不猜测、绝不静默偏移）。
- 选择器产物恒为整分钟 `YYYY-MM-DDTHH:mm:00Z`，落在 D-138 格式 (a) 内，Continue 门 reconcile 天然通过（D-138 R-2 向量既有覆盖）。

### 2.3 可测试性权衡（决定）

Compose `remember`/状态重组语义在当前测试栈（无 compose-ui-test 基建）不可单测；本批**零新增单元测试**，正确性由 §4 双端人工门逐键向量保证（D-137 先例：机制修复 + 人工门闭环）。不为此抽取 state-holder 类：修复为行级两处，抽取引入新抽象面反而扩大评审与回归面。如独立评审推翻此权衡，按评审结论修订本节与 §3。

### 2.4 宿主方式核实（无 key remember 残留风险评估）

`P503App.kt:230` `when (val current = state)`：三个 `P503EditScreen` 调用点（Editing `:244-279`、RequestIdentityConflict `:313-338`、DomainRejected `:339-360`）**全部为条件 composition 分支**。编辑流离开（Back、继续到确认页、放弃冲突等）即离开组合树，`remember` 状态随 dispose 丢弃；重进（含 Editing → AwaitingConfirmation → 提交失败 → DomainRejected 的跨分支往返）均重新组合、按 §2.1 初值语义从 draft 恢复。Editing 内部（`Update*` 事件）状态类不变、draft 为同源 `copy`，不存在「换 draft 不换组合」路径。边界：RequestIdentityConflict/DomainRejected 两态的 `UpdateOccurredAt` 转换为 Editing（`P503Reducer.kt:237-238`/`:263-264`）——跨 when 分支即组合实例重建，文本状态不跨实例存活；该路径的键入改写为遗留缺陷 D138TYPING-002（§2.6），不在本批。**结论：主编辑流内无 key remember 无跨流程残留文本风险，无需额外 key**（风险 R-1 关闭依据）。

### 2.5 行为向量（冻结，供人工门与评审对照）

| # | 操作序列 | 修复后预期 |
| --- | --- | --- |
| 1 | 逐键键入 `2026-09-15`，至「5」 | 文本仍为 `2026-09-15`，无改写；draft.occurredAt = `2026-09-14T16:00:00Z`（本地 09-15 00:00） |
| 2 | 接 #1 继续键入 ` 08:30` → 继续 | 文本 `2026-09-15 08:30`；确认页显示 `发生时间：2026-09-15 08:30（UTC+8）＝ 2026-09-15T00:30:00Z` |
| 3 | 逐键键入 `2026-09-01` 至「1」 | 文本仍为 `2026-09-01`，无改写（用户原始向量二） |
| 4 | 选择器路径确认（选定与当前 draft 不同的值） | 文本回写为 ISO 串；继续通过（D-138 门 3 回归）。若选定值恰等于现有 draft instant，文本保持当前文本（§2.2 条件回写，D-138 等值子条款） |
| 5 | 键入拒绝向量（桌面 `昨天 20:00`；Android 以 ASCII locale 向量如 `2026/09/15 08:30` 替代） | 冻结错误文案 + 继续被拦（D-138 语义不变；中文向量由既有套件覆盖） |
| 6 | 选择器对话框打开按一次 Esc | 仅关对话框，编辑页保持、draft 完整（D-137 零回归） |
| 7 | 继续 → 确认页 → 取消返回编辑屏 | 文本恢复为 draft instant 的 ISO 串（§2.1 初值语义，重组合；**注意：显示为 ISO 串而非先前键入的友好格式——这是初值语义的正确预期，非回归**）；继续仍通过 |
| 8 | （记录性观察，不作为 PASS/FAIL 判据）冲突/拒绝流内键入 `2026-09-15` 至「5」 | 跨分支转回编辑态后文本显示 ISO 串——D138TYPING-002 现状照实记录（§2.6；本批不修） |

### 2.6 范围边界——冲突/拒绝流残留（遗留缺陷 D138TYPING-002）

RequestIdentityConflict 与 DomainRejected 两态的 `UpdateOccurredAt` 转换为 Editing（`P503Reducer.kt:237-238`/`:263-264`；「修改任一字段可返回编辑」语义），跨 when 分支即 Editing 分支的 `P503EditScreen` 重新组合、文本状态按 §2.1 初值从新 `draft.occurredAt` 初始化——用户在这两流的编辑屏键入 `2026-09-15` 至「5」时，文本仍会被改写为 `2026-09-14T16:00:00Z`，与 D138TYPING-001 同类症状。**去 key 修不了该路径**（根因是实例重建，非 keyed remember）。

修复需宿主级状态提升（文本状态上移 `P503App` 或三调用点合一）或 reducer 重设计，均超出本批冻结授权（D-138 冻结 reducer 零改动；用户批级批准的机制 = §2.1/§2.2 两处行级修复）。处置：另立缺陷登记 **D138TYPING-002**（DECISIONS D-139 条目遗留小节），§2.5 向量 8 照实记录现状；扩范围与否、修复路线选择留用户裁决，裁决后按既有规格增量路由另批执行。

## 3. 逐文件变更表（冻结）

| 文件（仓库相对路径） | 变更（冻结） |
| --- | --- |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt` | §2.1（`:98` 去 key）+ §2.2（选择器确认回调内条件回写 `if (instant != draft.occurredAt)`，允许一行约束注释）。其余零改动。 |
| `docs/specs/2026-09-08-d139-occurred-at-typing-preserve-fix-design.md`（本文件） | 新增。状态翻转（proposal → approved）与冻结 SHA-256 由主代理在评审通过后执行并回填 DECISIONS 条目。 |
| `docs/DECISIONS.md` D-139 条目 | part 2 并行派发已写入（缺陷登记/决定/范围冻结/验收计划/实施登记占位）；冻结 SHA-256 回填与实施登记补齐由主代理执行。 |
| 其余全部（含全部测试文件） | **零改动**（零新增单测，§2.3）。 |

## 4. 验证计划

资源旗标遵循 `docs/CONTRIBUTING.md`：本机串行、单 worker、`--offline`、`GRADLE_OPTS=-Xmx1024m`、`--rerun-tasks`、每轮后 `--stop`，一次只运行一个命令。受影响集合（`ledger-application` 本批零改动，不入集合）：

- `:app-ui:jvmTest`（既有 66 用例回归，零新增零删除）。
- `:desktop-app:jvmTest`（既有 5 用例）。
- `:android-app:testDebugUnitTest`（既有 7 用例）。
- `ktlintCheck`（app-ui，与 CI Ktlint 步骤一致）。
- `:android-app:compileDebugKotlin`（Android 编译门；APK 装配归 CI，按既有纪律）。
- `project_docs`。

**双端人工门（冻结，逐键注入 + 逐键截图，单一输入源独占）：**

- 桌面：复用 `local/artifacts/d131-desktop-esc/` 自动化工具链，逐键发送、每键间隔 ≥300ms，关键键位后立即截图断言；截图以 `d139-*` 前缀存该目录。执行 §2.5 向量 1-7；向量 8 为记录性观察，桌面执行并照实登记。
- Android：模拟器 uiautomator + adb 逐键 keyevent（同间隔与截图纪律），证据 `d139-a*` 前缀。执行 §2.5 向量 1-7；向量 5 中文不可注入（adb `input text` 限制），以 ASCII locale 拒绝向量替代（D-138 先例，中文向量由既有套件覆盖）。
- 断言核心：向量 1/3 的关键键位（`2026-09-15` 的「5」、`2026-09-01` 的末位「1」）截图中文本框必须仍为手输前缀，未被改写为 ISO 串或回退日期——此为缺陷判据的逐键复核。
- 向量 4 执行说明：从空白/无效初态（`draft.occurredAt` 为 null）进入选择器即覆盖 null-draft 子场景（D-138 门 ③ 同路径）；等值子场景可在向量 4 后以同值确认复核（文本保持当前文本）。

## 5. 验收判据（A-1..A-6）

- **A-1**：受影响套件全绿——`:app-ui:jvmTest`（66）、`:desktop-app:jvmTest`（5）、`:android-app:testDebugUnitTest`（7），零新增零删除。
- **A-2**：`ktlintCheck`（app-ui）零告警。
- **A-3**：`:android-app:compileDebugKotlin` 通过。
- **A-4**：`project_docs` 通过。
- **A-5**：双端人工门 §2.5 向量 1-7 全 PASS（逐键截图证据），含用户两原始向量（#1、#3）；向量 8 记录性观察照实登记（D138TYPING-002 现状，不作为判据）。
- **A-6**：独立评审通过、distinct verifier 复验、同提交 CI 成功（聚合门发布证据）；P503IMPL-Q-001 不变量经既有套件与人工门 #2/#4/#7 保持。

## 6. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 无 key remember 跨编辑流残留文本 | 文本串味、门误判 | §2.4 宿主核实关闭：三调用点全条件 composition，离开即 dispose，重进按初值从 draft 恢复；人工门向量 #7 覆盖跨分支往返 |
| R-2 | 选择器回写后用户再编辑 | 文本为 ISO 串，删除/续打走 parse-on-type 既有分支 | 无新路径（ISO 串在 D-138 格式 (a) 内）；人工门 #4 覆盖 |
| R-3 | 复门再次被注入连发掩盖 | 缺陷漏检重演 | 逐键注入、每键 ≥300ms、关键键位后立即截图断言；单一输入源独占（教训 ④） |
| R-4 | 单端通过、他端平台差异回归 | 一端修复不完整 | 双端各跑全向量（§4） |
| R-5 | 冲突/拒绝流键入残留（D138TYPING-002） | 冲突/拒绝编辑屏键入前缀有效后文本仍被改写为 ISO 串（D-138 §2.4 在该两流修复后依然被违反） | 超出本批冻结授权（修复需宿主级状态提升或 reducer 重设计）；另立缺陷登记，扩范围与否留用户裁决（§2.6）；本批全向量限定主编辑流，向量 8 照实记录现状 |

## 7. 登记路径

1. 独立评审通过后，主代理翻转本文件状态为 approved，计算冻结 SHA-256，回填 `docs/DECISIONS.md` D-139 条目占位（哈希链自 D-138 终值 `A3BFBE82…` 续链，全值见该条目）。
2. 实施（本 worktree、单一 bounded writer）→ 实施评审 → distinct verifier → merge/push/CI → 双端人工门 → 主代理补登实施登记六项并关闭 D138TYPING-001。
3. push 与 CI 由主代理按既有授权流程执行；批准与登记本身不触发提交或推送。

## 边界断言（本批不含）

- 本文件为实施规格草案：评审通过并翻转状态前不构成实施授权；实施批在单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径（仅仓库相对路径）、个人数据、账务锚点、agent/会话痕迹；`.external/` 内容零引用、零改动。
