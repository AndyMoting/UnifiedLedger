# P7-02 A-02 设备验收缺陷修复设计（置顶状态接线 + 确认页类型化呈现）

状态：approved

日期：2026-09-18
依据：设备验收 A-02 的五个 OPEN 缺陷中的三个（`defect-A02PIN-001.md`、`defect-A02PIN-002.md`、`defect-A02CONFIRM-001.md`，均在 `local/artifacts/a01-a05/`）。A02MONTH-001 / A02LEND-001 属提交后权威刷新链，**不在本规格范围**，另批处理。

## 1. 范围

**范围内（纯 UI 状态接线与呈现，零账务、零 schema、零迁移、零新依赖、零数据模型语义变更）：**

- **FIX-PIN-1（A02PIN-002 主因）**：`TogglePin` 事件携带 store 权威结果，reducer 以「设为期望值」更新成员关系，不再自行翻转。
- **FIX-PIN-2（A02PIN-002 主因）**：`OverviewEmpty` 的 5 处 `Back` 重建保留 `pinnedTargets`。
- **FIX-PIN-3（A02PIN-001）**：置顶成功后重新派生管理快照，使管理列表排序即时生效。
- **FIX-PIN-4（A02PIN-002 自愈，可选加固）**：普通 `RefreshResult` 可携带 `pinnedTargets`（可空，缺省保持现状），使刷新可自愈分叉。
- **FIX-CONFIRM-1（A02CONFIRM-001）**：确认页按类型渲染标题与字段，引入**纯呈现函数**以便单元测试。

**显式非目标：**

- 不改 `entry_pin` 表结构、不加迁移、不改 `EntryPreferenceStore` 端口语义。
- 不改排序比较器 `EntryPinOrdering`（其稳定分区语义已被 `EntryPinOrderingTest.kt:27-38` 固定，且经设备实测证实正确）。
- 不改 `pinned_at` 参与排序（规格「其余保持既有确定性顺序」）。
- 不引入 Compose UI 测试基建；呈现逻辑以纯函数 + commonTest 覆盖。
- 不改提交/确认的账务语义、请求身份、幂等与重放。
- 不改 `A02MONTH-001`/`A02LEND-001` 相关链路。

## 2. 现状与根因（均已代码级定位）

| 缺陷 | 根因锚点 |
| --- | --- |
| A02PIN-002 | `P503AppState.kt:82`（`pinnedTargets` 默认 `emptySet()`）；`P503Reducer.kt:1491/1582/2079/2187/2202-2203`（`Back` 重建 `OverviewEmpty(overview, originTab)` 不传 `pinnedTargets`）；`P503UiEvent.kt:211-213`（`TogglePin` 只带 target）；`P503Reducer.kt:262-270`（reducer 自行翻转）；`SqlDelightEntryPreferenceStore.kt:70-76`（store 盲目翻转）；`P503Reducer.kt:191`（普通 `RefreshResult` 忽略 `event.pinnedTargets`） |
| A02PIN-001 | `P503App.kt:1551-1562`（`runPinToggle` 不重派生快照）；快照仅在 `:1683`/`:1519-1524`/`:1603-1608` 重派生；`P503CatalogManagementScreen.kt:57` 直接渲染 `state.catalogSnapshot` |
| A02CONFIRM-001 | `P503ConfirmationScreen.kt:41/45/46`（二元硬编码）；`P503App.kt:1911-1925`（五类型同一分支）；`P503App.kt:1657-1673`（只填两个通用槽位）；草稿已带子类字段（`EntryDraft.kt:94-116/123-143/150-174`） |

## 3. FIX-PIN 设计

### 3.1 FIX-PIN-1 权威结果下发

- `P503UiEvent.TogglePin` 扩展为 `data class TogglePin(val target: EntryPinTarget, val pinned: Boolean)`；`pinned` 为 store 返回的 `EntryPinResult.Toggled.pinned`。
- reducer 分支改为**设为期望值**：`pinnedTargets = if (event.pinned) state.pinnedTargets + event.target else state.pinnedTargets - event.target`。
- 宿主 `runPinToggle`（`P503App.kt:1551-1562`）在 `Toggled` 分支以 `result.pinned` 同时更新宿主镜像并派发事件（两者同源，不可能分叉）。
- **不改** `EntryPinResult` 端口类型与 store 的翻转语义（store 保持「翻转持久化状态」，权威结果由 store 决定）。

### 3.2 FIX-PIN-2 重建保留

- 5 处 `Back` 分支重建 `OverviewEmpty` 时传入 `pinnedTargets = state.pinnedTargets`。
- 任何其他重建 `OverviewEmpty` 的路径（若实施时发现）同样必须保留 `pinnedTargets`；规格要求「凡从既有 `OverviewEmpty` 派生新 `OverviewEmpty` 且语义为返回/刷新者，一律显式携带 `pinnedTargets`」。

### 3.3 FIX-PIN-3 管理快照即时重派生

- `runPinToggle` 的 `Toggled` 分支在更新宿主镜像后，重新派生管理快照（复用既有 `pinnedCatalogSnapshot()`，`P503App.kt:1476-1483`）并随 `TogglePin` 一并交给 reducer 安装。
- 实现方式二选一（writer 择一并在提交说明中记录理由）：
  - (a) `TogglePin` 事件增加可空 `catalogSnapshot` 字段，reducer 在该字段非空时更新 `catalogSnapshot`；
  - (b) 复用既有 `CatalogSnapshotRefreshed` 路径，在 `Toggled` 后追加派发。
- 约束：不得使 `catalogNotice`/`catalogDialog` 被意外重置（`SelectTab` 会重置这两者，本路径不得复用其重置语义）。

### 3.4 FIX-PIN-4 刷新自愈（加固）

- `RefreshResult` 增加可空 `pinnedTargets: Set<EntryPinTarget>? = null`；普通分支改为 `pinnedTargets = event.pinnedTargets ?: state.pinnedTargets`。
- **必须保持** `P503EntryEfficiencyReducerTest.kt:195-217` 现有断言语义（缺省 null 时保持 state 现值）。

### 3.5 必测用例（commonTest）

1. `TogglePin(target, pinned=false)` 在 state 无该成员时**仍**产生无该成员的结果（幂等设为期望值，而非翻转）——直接钉死 A02PIN-002 的反向副作用。
2. `TogglePin(target, pinned=true)` 在 state 已有该成员时结果不变。
3. 5 处 `Back`（`Editing`/`AwaitingConfirmation`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure(SUBMISSION)`）后 `pinnedTargets` 与进入前**相等**（逐分支一例）。
4. `SelectTab` 后 `pinnedTargets` 保持不变（补现有缺口）。
5. `TogglePin` 携带快照时 `catalogSnapshot` 更新且 `catalogNotice`/`catalogDialog` 不被重置。
6. 普通 `RefreshResult` 缺省 `pinnedTargets` 时保持现值；提供时采用提供值。

## 4. FIX-CONFIRM-1 设计

### 4.1 产品口径（本条为决定内容，须随批登记 D-151）

**问题**：确认页对转账/借出/收回套用支出模板；含手续费转账的确认金额（到账本金）与实际扣款（本金+手续费）不一致且手续费不呈现；借出把往来对象标为「费用分类」；收回把利息分类标为「费用分类」且不显示本金/利息构成；转账不显示转入账户。

**选定口径**：

1. 标题按类型：`确认支出` / `确认收入` / `确认转账` / `确认借出` / `确认收回`。
2. 字段标签按类型（不再使用「支付账户/费用分类」的二元兜底）：
   - 支出：支付账户、费用分类、金额
   - 收入：收款账户、收入分类、金额
   - 转账：转出账户、**转入账户**、到账本金、手续费、手续费分类（仅手续费 > 0）、**转出总额**
   - 借出：往来对象、出资账户、借出金额
   - 收回：往来对象、到账账户、本金、利息、利息分类（仅利息 > 0）、**实收总额**
3. **金额消歧（关键）**：转账**同时**显示「到账本金」与「转出总额（= 到账本金 + 手续费）」，两者并列呈现，不做单值取舍；收回**同时**显示「本金」「利息」与「实收总额」。依据 `ACCOUNTING_RULES.md:76`「精确组成金额……均已明确填写时才构成确认」——确认页必须让用户看到完整组成，而不是只给一个可能被误读为扣款额的单一数字。
4. 金额一律精确十进制（`minor units` 渲染），**禁浮点**。
5. 备注与发生时间保持既有呈现（备注为空显示 `—`；发生时间沿用既有 `occurredAtDisplayText`）。

**理由**：`PRODUCT_REQUIREMENTS.md:11` 要求「内部转账与对外收支在用户界面中明确区分」；`ACCOUNTING_RULES.md:76` 要求确认须含精确组成金额。并列呈现两值可同时满足两条且无需在产品上取舍，也不丢失任何信息。

**影响面**：仅确认页文案与字段集合；不改账务、不改请求载荷、不改 receipt、不改结果页（结果页已正确，见 `a02-105-done`）。

### 4.2 实现结构（纯呈现函数）

- 新增 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ConfirmationPresentation.kt`：
  - `internal fun confirmationTitle(draft: TypedEntryDraft): String`
  - `internal data class ConfirmationRow(val label: String, val value: String)`
  - `internal fun confirmationRows(draft: TypedEntryDraft, labels: ConfirmationLabels, currencyCode: String): List<ConfirmationRow>`
  - `internal data class ConfirmationLabels(paymentAccount: String, category: String, destinationAccount: String?, counterparty: String?)`
- `P503ConfirmationScreen` 改为渲染 `confirmationTitle` + `confirmationRows`（`Column` 顺序渲染，保持既有 `titleLarge`/`bodyMedium` 排版风格与既有按钮行）。
- 金额计算须用**精确十进制**：`转出总额 = destinationCredit + fee`、`实收总额 = principal + interest`。若仓库已有精确十进制工具（`ledger-domain` 的精确金额类型），**优先复用**；不得用 `Double`/`Float`。若草稿已直接携带 `totalReceived`，直接渲染该字段而不重算。
- 标签来源：宿主已解析 `paymentAccountLabel`/`categoryLabel`；新增需要 `destinationAccountLabel`（转账转入账户）与 `counterpartyLabel`（借出/收回往来对象）。状态 `AwaitingConfirmation` 相应扩展字段（可空），宿主在进入 `AwaitingConfirmation` 时按类型解析填充（复用既有目录解析路径 `P503App.kt:1657-1673` 附近）。
- **`categoryLabel` 语义修正**：转账时它当前承载「手续费分类」、借出时承载「往来对象」、收回时承载「利息分类」。实施时须让纯函数按类型把正确的标签放到正确的行，必要时新增专用标签字段而不再复用 `categoryLabel`。**保留既有字段以兼容现有测试**（`P503ReducerTest.kt:642-677` 断言 `continueCarriesHostResolvedDisplayLabelsIntoAwaitingConfirmation` 与 `continueWithoutLabelsFallsBackToDraftIdValues`），若确需改签名必须同步更新这些用例并在提交说明中列出。

### 4.3 必测用例（commonTest，纯函数）

1. 五类型标题正确（含 TRANSFER/LEND/COLLECT 不再是 `确认支出`）。
2. 转账（fee=2.50）：行集合含 `转出账户`、`转入账户`、`到账本金 100.00`、`手续费 2.50`、`手续费分类`、`转出总额 102.50`；**断言不出现 `费用分类` 标签**。
3. 转账（fee=0.00）：不出现 `手续费分类` 行；`转出总额 == 到账本金`。
4. 借出：出现 `往来对象` 行且值 = 往来对象名；**断言不出现 `费用分类` 标签**。
5. 收回（本金 40 / 利息 5）：出现 `本金 40.00`、`利息 5.00`、`实收总额 45.00`；**断言不出现 `费用分类` 标签**。
6. 支出/收入：与既有文案逐字一致（防回归）。
7. 金额渲染为精确两位小数，且对 `1/8` 类不可表示值不参与本路径（确认页只渲染已校验草稿的既有金额文本，不做算式求值）。
8. 转账空白手续费：`手续费` 行为 `—`（不得为只有币种代码的裸值），`转出总额` 可见且等于 `到账本金`，且不出现 `手续费分类` 行。
9. 转账手续费 `2.500` / `2.5000000000000000000`（超出币种精度的零尾数，Continue 门接受）：`转出总额` 按币种精度渲染为 `102.50`，`手续费分类` 行保留。收回本金/利息空白：两行为 `—`。

### 4.4 分量金额空白与精度规则（缺陷复查补录）

复查发现 Continue 门对**空白**分量（转账手续费、收回本金/利息）不报错，写入路径把空白手续费存为 0；门同时也接受超出币种精度但多余位全为零的金额文本。呈现必须与之一致：

1. `confirmationRows` 除 `currencyCode` 外还接收 `currencyPrecision`（宿主由 `resolvedCurrency(draft).precision` 提供）。
2. **派生**的 `转出总额` 按 `currencyPrecision` 解析并按该精度渲染（`formatMinorUnits`），因此 `2.500` 与 `2.5000000000000000000` 都得到 `102.50`，与页面其余金额同尺度。
3. `手续费分类` / `利息分类` 的判定按 `currencyPrecision` 解析，与 Continue 门的 `feeMinor > 0` 口径一致。
4. **空白**操作数在求和时视为 0（与写入路径一致），使空白手续费下 `转出总额` 仍可见且等于 `到账本金`；**非空白但不可解析**的操作数不视为 0，总额保持 `—`（item 7：页面从不求值算式）。
5. 分量行本身：空白渲染 `—`，非空白逐字渲染草稿已有文本，不做归一化。

### 4.5 缺陷复查的 D-151 记录更正

D-151 原记「`SelectTab` 与 READ 重试的集合保留同样显式化」不准确：基点提交（`470e83a`）上 `SelectTab` 已通过 `state.copy(...)` 保留集合、READ 重试分支已传 `event.pinnedTargets`。本批实际新增的是 `SelectTab` 的测试覆盖，以及 READ 重试在事件缺省时回退到保留的月度概览（`state.monthlyOverview?.pinnedTargets`）。

## 5. 验收

1. 聚焦测试：新增 `P503ConfirmationPresentationTest`、扩展 `P503EntryEfficiencyReducerTest`/`P503ReducerTest`（或新增 `P503PinStateReducerTest`），随后 `:app-ui:jvmTest`。
2. 受影响模块：`:ledger-application:jvmTest`、`:app-ui:jvmTest`、`ktlintCheck`、`project_docs`；组合根未变，但为稳妥追加 `:android-app:compileDebugKotlin`（本地或由 CI 承担）。
3. 设备回归（修复后必须执行，不得只跑单测）：五类型各提交一笔 → **确认页逐字段核对（含转账手续费与转出总额、收回本金/利息构成）** → 结果页 → 重启对照；置顶向量（置顶 → 关闭录入页 → 回管理列表核对标签与存储一致 → 点击方向正确 → 重进 Tab 顺序即时正确 → 重启持久化）。
4. 本批**不**声称 A-02 整体 PASS：A02MONTH-001/A02LEND-001 仍 OPEN，月度核对与借出呈现向量维持 FAIL，直到其独立批次修复并复验。

## 6. 可写文件边界

writer 只可写：

- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ConfirmationScreen.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ConfirmationPresentation.kt`（新增）
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503CatalogManagementScreen.kt`（置顶点击点改传期望 `pinned` 值）
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503AppState.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503UiEvent.kt`
- `app-ui/src/commonTest/kotlin/com/unifiedledger/ui/`（新增/扩展测试）
- `docs/DECISIONS.md`（仅追加 D-151）
- `docs/specs/2026-09-18-p7-02-a02-defect-fix-design.md`（本文件，仅状态与实施记录）

**不得**触碰 `ledger-domain`/`ledger-application`/`ledger-data`、schema、迁移、`EntryPreference.kt` 的排序派生语义、`.external/`、任何 Golden/fixture。**不得执行任何 Git 写操作**（提交/合并/push 由主代理负责）。
