# P7-02 A-02 提交后刷新链缺陷修复设计（月度小结 AWAITING + 借出后整屏读失败）

状态：approved

更新：2026-09-19。基线：main `0dc1444`（schema v30）。本文为缺陷修复设计，零账务语义、零 schema/迁移、零新依赖、零数据模型变更；触发器冻结集（P703SPEC-04）不变，`MonthlyActivityResult` 吸收表不变。产品裁定登记 **D-152**（随批提交）。

## 1. 缺陷与根因（取证结论）

### 1.1 A02MONTH-001（提交后月度小结停留 AWAITING `暂无月度数据。`）

- 权威要求：P7-03 规格 §6.2 触发器冻结集 (e)——「每次确定成功后的权威刷新（`Created`/`NoChange`/`Recovered`）」重请求月度结果；触发集之外一律不重请求。
- 实现偏离：A-PERF（`e61b9a0`）将 `refresh()`（`P503App.kt:346-381`）由同步改为异步；`decide()`（`P503HostCoordinator.kt:159-175`）在触发 (e) 时仍**同步**调用 `requestMonthlyNow(state)`：
  1. `requestMonthlyPayload()`（`P503App.kt:394-420`）读 `latestState.value as? OverviewEmpty` → 此时状态仍是瞬态 `Created` → 读出的载荷被 reducer 按 §6.2 吸收表**吸收**（`P503Reducer.kt:1958`）；
  2. 异步 `RefreshResult` 随后落地，重建**无载荷**的新 `OverviewEmpty`（`P503Reducer.kt:1918-1924`）；
  3. `decideMonthly` 的 (a)/(d) 守卫（`P503HostCoordinator.kt:208-216`）看到 `monthlyLastEffectiveMonth` 已在步骤 1 盖章（同一月）→ 永不重请求 → AWAITING 直至重启（重启走触发 (a)）。
- 编辑器开关路径（同缺陷第二触发）：编辑流状态（`Editing` 等）不携带月度字段，`Back` 重建的 `OverviewEmpty` 载荷为空；同月场景 (d) 守卫抑制重请求 → AWAITING。此路径在 A-PERF 前后行为相同（`Editing.overview` 从未携带月度载荷），属**状态模型缺口**而非时序回归。

### 1.2 A02LEND-001（借出提交后整屏「无法读取账本数据（本地数据库不可用）」）

- 会话内经录入对话框新建往来对象（`runCounterpartyForm`，`P503App.kt:1620-1633`）写入了目录账户行（`SqlDelightCounterpartyStore.kt:139-151` 的 `receivable` 账户），但**未调用 `facade.refreshCatalog()`**（目录管理命令路径 `P503App.kt:1506-1512` 与手动 `refreshCatalogSnapshot()` `:1602-1604` 均有调用）→ `CatalogConsumerSession.authority` 陈旧。
- 借出写入事务内用 store 直读目录（`App.kt:430-436`）→ 写入正确；提交后的权威读 `QueryLedgerCurrentState.query()` 一致性门（`QueryLedgerCurrentState.kt:94-98`）发现应收 posting 引用了会话目录中不存在的账户 → `InvalidState` → 宿主映射 `RefreshFailed`（`P503App.kt:375`）→ 瞬态态上整屏 `InfrastructureFailure(READ)`（`P503Reducer.kt:1925`）。
- 重试确定性再失败（`P503Reducer.kt:2323` 失败保留）；重启重建 authority 后恢复。设备反向印证：重启后向同一往来对象收回不触发。
- `QueryMonthlyActivity` 具有同型一致性门（`QueryMonthlyActivity.kt:95-100`）——本修复同时消除该隐藏失败面。

## 2. 修复设计

### 2.1 FIX-MONTH-1：触发 (e) 的月度重请求改到权威刷新落地之后

- `decide()`（`Created`/`NoChange`/`Recovered` 分支）：保留 `onRefresh()`；**移除同步 `requestMonthlyNow(state)`**，改为置协调器待定标记（如 `pendingMonthlyReRequestAfterRefresh = true`）。
- 协调器新增消费方法（宿主在 `refresh()` 的结果分发后调用）：成功分支（`RefreshResult` 已 dispatch 后）若待定标记置位 → 清标记 → 以落地后的 `latestState.value`（新 `OverviewEmpty`）调用既有 `requestMonthlyNow`（无条件盖章 + 触发）→ `requestMonthlyPayload` 此时能读到 overview，载荷落地。失败分支（`RefreshFailed` 已 dispatch 后）清待定标记（空转）。
- 语义不变式：触发集不变（仍恰好每个 (e) 事件一次无条件重请求；失败场景落空属 P703SPEC-11 残余边界 (a) 的既定恢复路径——用户重选月份或重试）；(a)/(d) 守卫逻辑不变（消费时盖章后，紧随的 `LaunchedEffect` 守卫自然抑制，无双请求）；与 `currentStateLoadCoordinator` 合并重跑兼容（标记存活到首个成功落地）。
- `NoChange` 同为确定成功（触发 (e)），同一机制覆盖。

### 2.2 FIX-MONTH-2：编辑流状态携带月度快照，Back 还原

- 编辑流状态类（`Editing`、`AwaitingConfirmation`、`Submitting`、`RequestIdentityConflict`、`DomainRejected`、`InfrastructureFailure(SUBMISSION)`、`UnknownCommit`）新增三个可空快照字段：`selectedMonth: YearMonth?`、`selectableMonths: List<YearMonth>`、`monthlyActivity: MonthlyActivity?`（默认空，向后兼容）。
- 编辑流内所有状态转移机械携带三字段（同 FIX-PIN-2 先例）；**Back 重建 `OverviewEmpty` 时还原**三字段（`monthlyReloadRequired` 还原为 `false`——载荷在场即无需重载；载荷本为空则还原为空，AWAITING 行为与现状一致，无回归）。
- 语义不变式：编辑期间零写入（Cancel/Back 丢弃草稿），载荷不可能过期；确认提交产生 `Created` 走 FIX-MONTH-1 的新鲜刷新，不消费携带快照。§6.2 吸收表不变（编辑态内 `MonthlyActivityResult` 仍被吸收——不更新，仅保留）。触发器冻结集不变（Back 不新增触发）。
- 附带效果（同一机械携带，非独立特性）：编辑器关闭后月份游标（`selectedMonth`/`selectableMonths`）不再丢失。

### 2.3 FIX-LEND-1：往来对象命令成功后刷新目录权威

- `runCounterpartyForm`：在既有 `shouldRefreshOptionsAfterCounterpartyCommand(result)` 成功分支内，先调用 `facade.refreshCatalog()`（与目录管理命令 `:1506-1512` 及 `refreshCatalogSnapshot()` 同一既有约定；`CatalogConsumerSession.refresh()` 同步重建 authority），再 `counterpartyVersion++` 并 `dispatch(DismissCounterpartyDialog)`。
- create 与 rename 都刷新（rename 改名不影响 posting 引用，刷新无害且一致）。
- 若 `refreshCatalog()` 内部失败/异常的既有处理沿用目录命令路径的现状（writer 核对 `:1506-1512` 的实际形态并保持一致；不新造失败面）。

## 3. 测试要求

- 协调器：触发 (e) → `onRefresh` 触发且**无**立即月度请求；宿主报告成功落地（overview）→ 恰好一次无条件 `onMonthlyRequest`；落地失败 → 待定标记清除、无请求；非 (e) 成功落地不消费。若无既有 `P503HostCoordinator` 测试文件则新建 `app-ui/src/commonTest/.../P503HostCoordinatorTest.kt`。
- Reducer：编辑流携带三字段并在 Back 还原（含载荷在场/不在场两态）；编辑态内 `MonthlyActivityResult` 仍被吸收（§6.2 表不变）；既有 `pinnedTargets` 携带向量不回归。
- FIX-LEND-1 为宿主接线（无 Compose 测试基建），以设备回归证明；writer 须核对不破坏既有对话框/notice 语义（对照 FIX-PIN-3 的 `pinnedCatalogSnapshot` 注释约束）。

## 4. 可写文件范围（§6）

- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503HostCoordinator.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503AppState.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt`
- `app-ui/src/commonTest/kotlin/com/unifiedledger/ui/`（新增/扩展测试；含 `P503EntryEfficiencyReducerTest.kt`、新 `P503HostCoordinatorTest.kt`）
- `docs/specs/2026-09-19-p7-02-refresh-chain-defect-fix-design.md`（本文件）
- `docs/DECISIONS.md`（追加 D-152）

如实现中发现必需的额外调用点文件（如 `P503UiEvent.kt`），先在交付说明中登记理由再改动；不得触碰 ledger-data/ledger-domain/ledger-application/schema/迁移/依赖。

## 5. 验收

- 自动：`:app-ui:jvmTest` 全绿（新增向量全过、既有不回归）；`:app-ui:ktlintCheck`；`:android-app:compileDebugKotlin` + `:desktop-app:compileKotlinJvm`。
- 设备（合并后新 APK，隔离 adb 5038）：①提交任一类型 → 月度小结**当次会话内**立即更新；②打开并关闭录入页 → 月度小结与月份游标保留；③会话内新建往来对象 → 借出 → 无整屏读失败、月度立即更新、重试可用性与正常态一致；④重启持久回归不破。
- 独立 verifier 命名断言（合并前）：W1 触发 (e) 时序（无同步请求、落地后恰一次）；W2 编辑流携带/还原完整性（全部构造点覆盖）；W3 §6.2 吸收表与触发集零改动；W4 往来对象命令后 refreshCatalog 接线（且不破坏 notice/dialog）；W5 仅呈现/状态/宿主接线（无账务/schema/依赖）；W6 测试与检查结果真实；W7 隐私卫生。
