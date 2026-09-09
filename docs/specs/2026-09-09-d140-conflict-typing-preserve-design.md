# 冲突/拒绝流键入保留缺陷修复批 D-140（设计规格）

**状态：** approved — 本文件为 D138TYPING-002 修复批（D-140）的实施规格（已冻结 2026-09-09）。独立评审：APPROVE（P1-1 显示语义自述修正 + P2-1..P2-5 文档级处方，全部在冻结前折入；机制本体无 P0/P1 阻断）。实施方向已获用户批级批准（2026-09-09，「批准D138TYPING-002」，verbatim 照录见 `docs/DECISIONS.md` D-140 条目批准登记节）。本批为遗留缺陷 D138TYPING-002 的修复规格增量（D-139 规格 §2.6 所列「宿主级状态提升」路线；reducer 冻结不动）。实施按既有路由执行（本 worktree、单一 bounded writer、独立评审、distinct verifier、主代理最终验收）。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为 worktree 基线 `68312e8` 的实读行号；`.external/` 只读）：

- **主检出根指引**：主检出 `AGENTS.md`（worktree handoff 指定入口；含新增 adb 共享协议条——本批无任何 adb 操作，仅遵守不触碰用户设备；验证分工、外部证据门与高风险路由按其执行）。
- **缺陷登记依据**：`docs/DECISIONS.md` D-140 条目（:2431-2464，基线实读；条目本身授权「事件枚举以实施规格实读为准」，本规格 §2.2 枚举即该授权下的实读结论）；缺陷来源 = D-139 条目「遗留缺陷登记（D138TYPING-002，不在本批）」节（`docs/DECISIONS.md:2412`）+ D-139 规格 §2.6（:89-93）与 §2.5 向量 8（:87）。
- **被修订的冻结条款**：D-139 规格 §2.5 向量 7（:86）「取消返回后文本恢复为 draft instant 的 ISO 串」——本批修订为「键入文本原样保留」（§2.3 显式条款，防未来会话按旧向量误判回归）。
- **源码锚点（worktree 实读）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:85`（`editDialogOpen` 宿主共享态）、`:87-89`（单一路径 `dispatch` 漏斗）、`:236`（`StartNewExpense` 唯一派发点）、`:245`/`:314`/`:340`（`P503EditScreen` 三个调用点，Editing/RequestIdentityConflict/DomainRejected）；`P503EditScreen.kt:82-97`（签名）、`:98-99`（D-139 无 key 文本状态，本批删除）、`:230-239`（parse-on-type 三分支）、`:252`（Continue 门 `occurredAtTextReconciles`）、`:341-345`（选择器确认条件回写）；`P503Reducer.kt:63-75`/`:104-107`/`:122-128`/`:155-156`/`:167-174`/`:196-202`/`:231-238`/`:240-246`/`:257-264`/`:284-290`（全部转 Editing 的转换，§2.2 枚举表）；`P503UiEvent.kt:20`（`StartNewExpense`）、`:65`（`AbandonConflict`）。
- **可达性证据链（§2.5）**：`ledger-application` `ExecuteManualExpenseSubmission.kt:70-102`、`ResolveManualExpenseCommitStatus.kt:35-46`、`ledger-data` `SqlDelightConfirmedManualExpenseCommitPort.kt:47-62`/`:120-124`、`ledger-domain` `OrdinaryExpense.kt:24-72`、`desktop Main.kt:252-277`/`:279`/`:333-384`、`android App.kt:195-221`/`:223`/`:248-299`、`P503HostCoordinator.kt:57-70`；实证 = D-139 实施登记双端人工门结论（`docs/DECISIONS.md:2426`「冲突/拒绝流演示库不可达」）。

术语与编号约定：`本批` = D-140 冲突/拒绝流键入保留修复批；`缺陷编号` D138TYPING-002 仅在本批内稳定使用，与全局决定编号空间不同。

## 1. 目的与范围

### 1.1 目的

- 恢复 D-138 §2.4 冻结的显示语义于冲突/拒绝流：RequestIdentityConflict/DomainRejected 两态的编辑屏上，手工键入的发生时间文本在**任何跨 when 分支转换后原样保留**，不再因组合实例重建被改写为 ISO 串（D-139 已修复主编辑流，本批消除其遗留同类症状）。
- 将键入文本状态提升至宿主（`P503App`），使文本存活于 `P503EditScreen` 组合实例重建之外；同流内一切跨分支往返（冲突/拒绝 → 编辑、确认 → 取消、提交失败 → 返回、未知提交 → 冲突页）均保留键入文本——这正是修复本体。
- 行为语义修订显式登记：取消返回后文本由「draft ISO 串」修订为「键入文本原样保留」（§2.3），与 D-138 §2.4「手工键入原样保留」彻底一致。

### 1.2 范围（冻结）

**范围内（唯一代码触点）：** 仅 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt` + `P503EditScreen.kt` 两文件（§2 五条机制）。

**范围外（本批明确不做，逐项冻结）：**

- **零 schema 变更、零新依赖、零新增单元测试**（§2.4 权衡；D-137/D-139 先例，正确性由 §4 双端人工门逐键向量保证）。
- `P503Reducer.kt`、`P503DraftValidation.kt`（含 `occurredAtTextReconciles`）、`ParseManualExpenseOccurredAt`、facade、两组合根（`desktop-app` `Main.kt` / `android-app` `App.kt`）零改动——「修改任一字段可返回编辑」语义依赖 reducer 跨分支转换，reducer 冻结不动（D-139 §2.6 否决项）。
- parse-on-type 三分支语义（D-138 §2.3）、错误/辅助文案、Continue 门（P503IMPL-Q-001 不变量）零改动；选择器确认条件回写逻辑（D-139 §2.2）语义零变更，仅读写通道改走新参数。
- D-137 Esc/back 全路径零改动：root `onPreviewKeyEvent`（`P503EditScreen.kt:120-138`）、`dismissOnEscape`、`DesktopEscBackHandler`、`editDialogOpen` 门控（`P503App.kt:85`/`:97-102`）。
- 既有 specs（含 D-139 规格）、`docs/DECISIONS.md`、测试、CI/构建脚本、主题、`.external/` 零改动。

## 2. 机制（冻结）

### 2.1 宿主级状态提升（机制 1、2）

**宿主新增（`P503App.kt`，插于 `:85` `editDialogOpen` 之后、`:87` `dispatch` 之前）：**

```kotlin
// D-140 (spec 2.1): 键入文本宿主级提升；null = 未初始化，显示按 draft 派生。
var hoistedOccurredAtText by remember { mutableStateOf<String?>(null) }
```

- 宿主为单一状态源：`P503EditScreen` 组合实例重建（跨 when 分支）不再持有或丢失文本状态，缺陷根因（实例重建重置文本）消除。
- null 语义 = 未初始化：hoisted 为 null（首次组合或新草稿流重置后）时按 `draft.occurredAt` 派生显示——与 D-139 初值语义（`draft.occurredAt?.toString() ?: ""`）逐字节等价；hoisted 非 null 时恒以键入文本为准。**显示语义随本批的实质变化（显式登记，防误判回归）**：冲突/拒绝/提交失败重进编辑屏此前按 D-139 初值语义显示 ISO 串；本批机制下 draft.occurredAt 的两个派发路径（parse-on-type `P503EditScreen.kt:230-239`、选择器确认 :341-345）均在派发前先写 hoisted，故不变量「`draft.occurredAt ≠ null ⟹ hoisted ≠ null`」成立，而这些重进路径（Continue 门 P503IMPL-Q-001 保证 occurredAt 非空）显示的是 hoisted 键入文本（或选择器 ISO），**不再是 ISO 初值**——这是修复本体的一部分（§2.3 一并登记），非「不变」。

**`P503EditScreen` 签名改造（`P503EditScreen.kt:82-97`）：** 删除内部 `occurredAtText` remember（:98-99 连同 D-139 约束注释一并删除——文本状态已上移，无 key 注释随状态消失）；新增两参数（插于 `:92` `onUpdateOccurredAt` 之后、`:93` `onContinue` 之前）：

```kotlin
occurredAtText: String,
onOccurredAtTextChange: (String) -> Unit,
```

**三个调用点（`P503App.kt:245`/`:314`/`:340`）统一传值：**

```kotlin
occurredAtText = hoistedOccurredAtText ?: (draft.occurredAt?.toString() ?: ""),
onOccurredAtTextChange = { hoistedOccurredAtText = it },
```

- 回退表达式按各分支当前 `draft` 求值，等价于旧按组合初始化的初值语义；hoisted 非 null 时恒以键入文本为准。
- 屏幕内全部读写改走新参数，逻辑零改动：parse-on-type 三分支（:230-239，仅 `occurredAtText = newText`（:231）改 `onOccurredAtTextChange(newText)`）；Continue 门（:252，读参数）；选择器确认条件回写（:341-345，`occurredAtText = instant.toString()`（:343）改 `onOccurredAtTextChange(instant.toString())`，`if (instant != draft.occurredAt)` 条件与派发顺序原样保留——D-139 §2.2 语义零变更，等值选择保持当前文本）。

### 2.2 新流程重置与重置事件枚举表（机制 3）

**重置判据：** hoisted 仅在「创建全新 Editing 草稿」的事件派发时重置为 null。实读 `P503Reducer.kt` 全部相关事件转换（8 处直接进入 Editing——#5/#7 各含 4 个 `Update*` 事件，代码级转换实为 14 处；另 5 处相关非 Editing 路径列入以备完备性），逐一核实转换目标：

| # | 事件（状态 → 转换） | 实读行号 | 转换目标 | 全新 draft？ | 处置 |
| --- | --- | --- | --- | --- | --- |
| 1 | `StartNewExpense`（OverviewEmpty → Editing） | `:63-75` | 全字段 null 的新 draft | **是** | **重置** |
| 2 | `Continue` 门失败（Editing → Editing） | `:104-107` | 同 draft（仅留 requestId） | 否（无分支切换，无实例重建） | 不动 |
| 3 | `Cancel`（AwaitingConfirmation → Editing） | `:122-128` | 同 draft，requestId = null | 否 | 保留 |
| 4 | `SubmissionResult` InvalidInput（Submitting → Editing） | `:155-156` | 同 draft | 否 | 保留 |
| 5 | `UpdateAmount/PaymentAccount/Category/OccurredAt`（RequestIdentityConflict → Editing） | `:231-238` | 同 draft 内容（copy） | 否 | **保留（缺陷核心向量）** |
| 6 | `AbandonConflict`（RequestIdentityConflict → Editing） | `:240-246` | **同 draft**（仅 requestId = null，新保存意图） | 否 | 保留（结论见下） |
| 7 | `UpdateAmount/PaymentAccount/Category/OccurredAt`（DomainRejected → Editing） | `:257-264` | 同 draft 内容（copy） | 否 | **保留（缺陷核心向量）** |
| 8 | `Cancel`（InfrastructureFailure SUBMISSION → Editing） | `:284-290` | 同 draft | 否 | 保留 |
| 9 | `RetrySubmission`（InfrastructureFailure SUBMISSION → Submitting） | `:277-283` | 非 Editing | — | 不动（其后 #8 保留） |
| 10 | `UpdateOccurredAt` 等经 Submitting 携带 draft 的间接路径（`SubmissionResult` InfrastructureFailure :167-174、UnknownCommit :175-176 → `CommitStatusResolved` SnapshotConflict :196-202 → RequestIdentityConflict） | `:167-174`/`:196-202` | 同 draft 传递 | 否 | 保留（冲突页进入即本批修复本体） |
| 11 | `Back`（Editing/AwaitingConfirmation/冲突/拒绝/失败 → OverviewEmpty） | `:110-111`/`:141-142`/`:247-248`/`:265-266`/`:291-292` | 流退出，无 Editing | — | 不重置（退出后无编辑屏展示，不可见；下一 `StartNewExpense` 必重置） |
| 12 | `Confirm`/`Continue`（Submitting 内吞并） | `:180-182` | 非 Editing | — | 不动 |
| 13 | 其余事件（Ready/OverviewEmpty 其他分支、瞬态结果） | `:47-53`/`:214-224` | 非 Editing | — | 不动 |

**实读结论：重置集合 = {`StartNewExpense`} 唯一**。特别登记：`AbandonConflict`（#6）**不是**重置事件——其转换目标为 `P503AppState.Editing(draft = state.draft, requestId = null, …)`，draft 内容原样保留（仅丢弃 requestId 开启新保存意图），非「全新 draft」；按「键入原样保留」语义，用户在冲突屏的键入经保留后继续显示是正确行为，重置反而丢弃用户输入。此结论对 `docs/DECISIONS.md` D-140 条目机制 3 的**临时枚举（StartNewExpense + AbandonConflict）构成修订**——条目本身授权「事件枚举以实施规格实读为准」，本表即该授权下的实读结论，独立评审需核验本表完备性（风险 R-1）。

**重置落点（`P503App.kt:87-89` dispatch 漏斗顶部，唯一派发路径）：**

```kotlin
fun dispatch(event: P503UiEvent) {
    // D-140 (spec 2.2): 全新草稿流事件重置 hoisted 文本（枚举表：#1 唯一）。
    if (event is P503UiEvent.StartNewExpense) hoistedOccurredAtText = null
    state = reducer.reduce(state, event).also { latestState.value = it }
}
```

- 漏斗重置覆盖 :236 唯一派发点及任何未来调用方（重置与 reduce 顺序无关：reduce 产物为新 null draft，hoisted 为 null → 显示派生为空串）。

### 2.3 行为语义修订（显式条款，机制 5）

D-139 规格 §2.5 向量 7（:86）预期「继续 → 确认页 → 取消返回编辑屏 → 文本恢复为 draft instant 的 ISO 串」系初值语义下的正确预期；宿主级保留语义下本批显式修订：

- **修订后 D-139 向量 7 预期：「取消返回后文本 = 键入文本原样保留」（非 ISO 串）。**
- **同类重进显示语义一并登记**：冲突/拒绝/提交失败重进编辑屏的显示由「ISO 初值」改为「键入文本保留」（§2.1 null 语义条），与向量 7 修订同源同因——本批修复本体。
- 该修订更彻底满足 D-138 §2.4「手工键入的文本原样保留在文本框中」冻结条款；D-139 规格为已冻结文件，本批不改写其文本，修订由本条款 + `docs/DECISIONS.md` D-140 条目机制 5 双重显式登记，防未来会话按 D-139 旧向量 7 误判回归。
- 等值/非等值选择器回写、DST fail-closed、Continue 门 reconcile（P503IMPL-Q-001：显示文本必须重解析为与 draft instant 逐值相等）全部保持：保留的友好格式文本可继续通过门（重解析得同一 instant），无效键入保留但门拦截并显示冻结错误文案——语义自洽。

### 2.4 范围冻结与可测试性权衡（机制 4）

范围冻结见 §1.2。**零新增单元测试**：Compose 宿主提升/组合实例重建语义在当前测试栈（无 compose-ui-test 基建）不可单测；修复为参数化接线（无状态逻辑抽离点），正确性由 §4 双端人工门逐键向量保证（D-137/D-139 先例）。不为此抽取 state-holder 类：本批两文件行级接线，抽取引入新抽象面反而扩大评审与回归面。如独立评审发现干净纯逻辑抽取点（如重置判据谓词），可在评审意见中给出权衡，默认不加。

### 2.5 可达性调查结论（机制 6，实读核实）

**结论：RequestIdentityConflict/DomainRejected 在演示库（桌面 + Android）无 UI 可达触发**，与 D-139 双端人工门实证一致（`docs/DECISIONS.md:2426`）。证据链：

- **DomainRejected**：`ledger-data` `SqlDelightConfirmedManualExpenseCommitPort.kt:55-62`——仅当 `createFormalTransaction()` 返回 `DomainResult.Failure`；演示工厂（`desktop Main.kt:252-277`/`android App.kt:195-221`）调用 `ledger-domain` `OrdinaryExpense.kt:24-72` 的 `createAssetPaidOrdinaryExpense`，失败仅发生于非法目录/非正金额/溢出；演示合成目录（`Main.kt:333-384`/`App.kt:248-299`）满足全部约束，UI 门（Continue 校验 + `P503App.kt:117-140` 提交构造守卫）保证输入完整非零——**不可达**。
- **RequestIdentityConflict（直接）**：`ledger-data` `SqlDelightConfirmedManualExpenseCommitPort.kt:47-53`（claim 失败 → `resolveExisting`）+ `:120-124`（快照不同 → 冲突）——要求同 requestId 已有不同快照的已提交记录；演示 requestId 为 UuidV7 每保存意图新建（`Main.kt:279`/`App.kt:223`；`P503App.kt:260` 仅意图内复用），且 Created/NoChange/Recovered 自动刷新结束流（`P503HostCoordinator.kt:57-70`）——**不可达**。
- **UnknownCommit → SnapshotConflict → 冲突页**：`ledger-application` `ExecuteManualExpenseSubmission.kt:74-101`（handoff 后异常才入解析）+ `ResolveManualExpenseCommitStatus.kt:35-46`（SnapshotConflict 需已持久化冲突快照）——需故障注入（DB 异常），演示单用户本地 SQLite 无 UI 触发面——**不可达**。
- **InfrastructureFailure(SUBMISSION) → 返回编辑**：同上需异常注入——**不可达**。

**覆盖策略（照实登记覆盖边界）：** 不可达 → 采用**机制等价覆盖**：人工门核心向量 = Editing → AwaitingConfirmation → Cancel → Editing 跨分支持久（§2.6 向量 4），与缺陷同一 hoisted 状态、同一组合重建恢复路径（恢复逻辑分支无关，仅 draft 与 when 分支不同）；冲突/拒绝流保持记录性观察（§2.6 向量 9）并如实登记覆盖边界——本批对冲突/拒绝流的修复正确性由「机制等价 + 源码机制论证」共同保证，非直接逐键实证。

### 2.6 行为向量（冻结，供人工门与评审对照）

| # | 操作序列 | 修复后预期 |
| --- | --- | --- |
| 1 | 主编辑流逐键键入 `2026-09-15` 至「5」 | 文本仍为 `2026-09-15`，无改写；draft.occurredAt = `2026-09-14T16:00:00Z`（D-139 向量 1 语义保持，无回归） |
| 2 | 主编辑流逐键键入 `2026-09-01` 至「1」 | 文本仍为 `2026-09-01`（用户原始向量二，D-139 向量 3 保持） |
| 3 | 键入 `2026-09-15 08:30` → 继续 → 确认页 → **取消返回编辑屏** | 文本 = `2026-09-15 08:30` **原样保留**（修订自 D-139 §2.5 向量 7；旧预期 ISO 串被显式修订）；继续仍通过 |
| 4 | **跨分支持久（等价核心向量）**：键入 `2026-09-15` → 继续 → 确认页 → 取消 → 编辑屏；再继续 → 确认页 → 取消 | 文本两次跨分支往返均原样保留（同一 hoisted 状态同一恢复路径——与冲突/拒绝流同机制）；继续每次均通过 |
| 5 | 选择器路径确认（选定与当前 draft 不同值） | 文本回写 ISO 串并经 hoisted 保留；继续通过（D-139 向量 4 回归）。等值选择保持当前文本（D-139 §2.2 条件回写零变更） |
| 6 | 拒绝向量（桌面 `昨天 20:00`；Android 以 ASCII locale 向量如 `2026/09/15 08:30` 替代） | 冻结错误文案 + 继续被拦（D-138 语义不变；中文向量由既有套件覆盖） |
| 7 | 选择器对话框打开按一次 Esc | 仅关对话框，编辑页保持、draft 完整（D-137 零回归） |
| 8 | 新草稿重置：编辑屏 Back（或关闭）退出流 → 新建支出 | 文本框为空（hoisted 在 `StartNewExpense` 重置为 null）——新流程无残留文本（R-1 关闭依据） |
| 9 | （记录性观察，不作为 PASS/FAIL 判据）冲突/拒绝流内键入 `2026-09-15` 至「5」 | 演示库不可达（§2.5）；机制等价向量 4 覆盖同一恢复路径；现状照实登记覆盖边界 |

## 3. 逐文件变更表（冻结）

| 文件（仓库相对路径） | 变更（冻结） |
| --- | --- |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt` | ① `:85` 后新增 `hoistedOccurredAtText` 宿主状态（§2.1）；② `:87-89` `dispatch` 漏斗顶部新增 `StartNewExpense` 重置（§2.2）；③ `:245`/`:314`/`:340` 三个 `P503EditScreen` 调用点各新增 `occurredAtText` 传值（hoisted 回退派生）与 `onOccurredAtTextChange` setter（§2.1）。其余零改动。 |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt` | ① 签名（:82-97）新增 `occurredAtText: String` + `onOccurredAtTextChange: (String) -> Unit`（插于 `onUpdateOccurredAt` 后）；② 删除 :98-99 内部文本状态与 D-139 约束注释；③ :231 与 :343 两处写文本改走 `onOccurredAtTextChange`（parse-on-type 与选择器条件回写，其余逻辑逐字节保持）。其余零改动。 |
| `docs/specs/2026-09-09-d140-conflict-typing-preserve-design.md`（本文件） | 新增。状态翻转（proposal → approved）与冻结 SHA-256 由主代理在评审通过后执行并回填 DECISIONS 条目。 |
| `docs/DECISIONS.md` D-140 条目 | part 2 并行派发已写入（缺陷登记/决定/范围冻结/验收计划/实施登记占位）；本批只读不改；冻结 SHA-256 占位由主代理在规格冻结/提交时回填（哈希链自 D-139 终值 `8F1C62AB…` 续，全值见该条目）。 |
| 其余全部（含全部测试文件） | **零改动**（零新增单测，§2.4）。 |

## 4. 验证计划

资源旗标遵循 `docs/CONTRIBUTING.md`：本机串行、单 worker、`--offline`、`GRADLE_OPTS=-Xmx1024m`、`--rerun-tasks`、每轮后 `--stop`，一次只运行一个命令。受影响集合（本批仅 app-ui 两文件，ledger-application/desktop/android 零改动）：

- `:app-ui:jvmTest`（既有 66 用例回归，零新增零删除）。
- `:desktop-app:jvmTest`（既有 5 用例）。
- `:android-app:testDebugUnitTest`（既有 7 用例）。
- `ktlintCheck`（app-ui，与 CI Ktlint 步骤一致）。
- `:android-app:compileDebugKotlin`（Android 编译门；签名变更调用点遗漏在此必现编译错误，R-3；APK 装配归 CI，按既有纪律）。
- `project_docs`。

**双端人工门（冻结，逐键注入 + 逐键截图，单一输入源独占）：**

- 桌面：复用 `local/artifacts/d131-desktop-esc/` 自动化工具链，逐键发送、每键间隔 ≥350ms，关键键位后立即截图断言；截图以 `d140-*` 前缀存该目录。执行 §2.6 向量 1-8；向量 9 为记录性观察照实登记。
- Android：模拟器 uiautomator + adb 逐键 keyevent（同间隔与截图纪律，证据 `d140-a*` 前缀）。执行 §2.6 向量 1-8；向量 6 中文不可注入（adb `input text` 限制），以 ASCII locale 拒绝向量替代（D-138 先例，中文向量由既有套件覆盖）。
- 断言核心：向量 1/2 关键键位（「5」/「1」）截图中文本框必须仍为手输前缀；向量 3/4 取消返回后截图断言文本框 = 键入文本（非 ISO 串）——新语义逐键复核；向量 8 断言新流程文本框为空（重置无残留）。

## 5. 验收判据（A-1..A-6）

- **A-1**：受影响套件全绿——`:app-ui:jvmTest`（66）、`:desktop-app:jvmTest`（5）、`:android-app:testDebugUnitTest`（7），零新增零删除。
- **A-2**：`ktlintCheck`（app-ui）零告警。
- **A-3**：`:android-app:compileDebugKotlin` 通过。
- **A-4**：`project_docs` 通过。
- **A-5**：双端人工门 §2.6 向量 1-8 全 PASS（逐键截图证据），含新语义向量 3/4 与重置向量 8；向量 9 记录性观察照实登记（覆盖边界，不作为判据）。
- **A-6**：独立评审通过（含 §2.2 重置枚举表完备性核验）、distinct verifier 复验、同提交 CI 成功（聚合门发布证据）；P503IMPL-Q-001 不变量经既有套件与人工门向量 3-5 保持；reducer 冻结零改动经 diff 核验。

## 6. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | hoisted 跨流程残留与重置枚举不完备 | 新草稿流文本串味（残留上一流键入） | §2.2 全 13 转换实读枚举表（重置集 = {StartNewExpense} 唯一）经独立评审核验；dispatch 漏斗单点重置；人工门向量 8 覆盖重置；`Back` 退出后 hoisted 滞留但不可见（无编辑屏），下一 `StartNewExpense` 必重置 |
| R-2 | 取消返回新语义的用户感知/误判 | 未来会话按 D-139 旧向量 7 误判为回归 | §2.3 显式条款 + DECISIONS D-140 条目机制 5 双重登记；人工门向量 3/4 逐键复核并照实登记 |
| R-3 | 签名变更调用点遗漏 | 编译失败或漏传调用点行为不一致 | 三调用点全部位于 `P503App.kt`（grep 实证仅 3 处）；新参数无默认值 → 遗漏必现编译错误，`:android-app:compileDebugKotlin` + app-ui 编译门兜底 |
| R-4 | 复门注入连发掩盖（D-138 教训） | 前缀阶段改写在终态断言下不可见 | 逐键注入、每键 ≥350ms、关键键位后立即截图断言；单一输入源独占 |
| R-5 | 冲突/拒绝流不可达，修复无直接逐键实证 | 覆盖边界外回归漏检 | §2.5 机制等价覆盖（向量 4 同一 hoisted 状态同一恢复路径）+ 源码机制论证 + 记录性观察照实登记；评审核验等价性论证 |
| R-6 | 选择器回写改道后行为漂移 | 等值/非等值回写或派发顺序回归 | 回写条件 `if (instant != draft.occurredAt)` 与派发顺序逐字节保持（仅写通道改参数）；人工门向量 5 回归 |

## 7. 登记路径

1. 独立评审通过后，主代理翻转本文件状态为 approved，计算冻结 SHA-256（**冻结字节域 = UTF-8+LF，与链上既有环节 D-138 `A3BFBE82…`/D-139 `8F1C62AB…` 一致**），回填 `docs/DECISIONS.md` D-140 条目占位（哈希链自 D-139 终值 `8F1C62AB54E03CC3290CD50C258DB8B9075BFB3DD4EC7B8C55D7F2E9FEA2D0DE` 续链，全值见该条目；同时按评审 P2-2 将条目机制 3 的临时枚举对齐为规格实读结论 {`StartNewExpense`}）。
2. 实施（本 worktree、单一 bounded writer，仅 §3 两代码文件）→ 实施评审 → distinct verifier → merge/push/CI → 双端人工门 → 主代理补登实施登记六项并关闭 D138TYPING-002。
3. push 与 CI 由主代理按既有授权流程执行；批准与登记本身不触发提交或推送。

## 边界断言（本批不含）

- 本文件为实施规格草案：评审通过并翻转状态前不构成实施授权；实施批在单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径（仅仓库相对路径）、个人数据、账务锚点、agent/会话痕迹；`.external/` 内容零引用、零改动。
