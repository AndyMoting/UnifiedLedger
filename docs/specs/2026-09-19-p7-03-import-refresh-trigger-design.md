# P7-03 A05HOME-STALE-001 修复设计：导入批量确认落地触发权威刷新与月度重请求（触发集 (f) 增补）

状态：approved

更新：2026-09-19。基线：main `959666e`（schema v30）。零账务语义、零 schema/迁移、零新依赖、零数据模型变更。**本批包含冻结触发集的有意增补**：(f) 触发器——已登记 **D-153**（随批提交），属经独立取证（缺陷报告 `local/artifacts/a01-a05/defect-A05HOME-STALE-001.md`）与常设授权下的裁决性契约修正。

## 1. 缺陷与根因

- **现象**：导入批量确认入账后切到首页，月度小结与流水列表停留陈旧态（流水显示「账本为空」而 DB 已有确认入账的交易；月度卡不反映新交易月份）；月份切换恢复被陈旧载荷的可选域困住（新交易月不在陈旧 SelectMonth 域内 → SelectMonth 被吸收）；仅重启（触发 (a)）恢复。
- **根因**：触发器冻结集（P703SPEC-04，(a)–(e)）不含导入确认触发器。导入批量确认走导入自身状态机（`ImportBatchSubmitting → ImportBatchResultSummary` 等），不是入口提交的 `Created/NoChange/Recovered`，故 `decide()` 不触发权威刷新；首页 (a)/(d) 守卫在陈旧载荷上抑制重请求。
- **影响**：用户批量确认后首页呈现与事实相反（「账本为空」），违反呈现原则（失败不伪装、旧数据不冒充实时）。

## 2. 修复设计（FIX-STALE-1：触发集增补 (f)）

### 2.1 触发集 (f) 定义（D-153 裁决）

- **(f) 导入批量确认派发完成**：一次批量派发运行到达终态——全部项到达逐项终态（已入账/拒绝/核对冲突/跳过），含 run 级类型化失败（pre-phase 失败也使全部项终态）与 Unknown 暂停后恢复完成的运行。触发一次**无条件**权威刷新 + 月度重请求（与 (e) 同型：刷新落地后执行，复用既有 arm/consume 机制）。部分成功的运行恰触发一次。
- 不触发：逐项结果落地（仅运行完成触发一次，避免 10k 级逐项风暴）；批量暂停/放弃（放弃时未入账项本就零正式效果，列表重读已有）。

### 2.2 实现

- **P503HostCoordinator.kt**：新增 `internal fun onImportBatchConfirmed()`：`onRefresh()` + 置位既有 `pendingMonthlyReRequestAfterRefresh`（复用 FIX-MONTH-1 的 arm/consume：刷新落地消费 → 恰一次无条件月度重请求，落月盖章抑制随后的 (a)/(d) 守卫）。`decide()` 的 (e) 分支、consume/drop 方法、(a)/(d) 守卫逻辑零改动。
- **P503App.kt `runImportBatchDispatch()`**：在 finally 主线程跳内、`completed == true` 分支（现有 `coordinator.importBatchDispatchCompleted()` 与 `requestImportReviewRowsRead()` 旁）调用 `coordinator.onImportBatchConfirmed()`。`completed == false`（暂停）不触发（恢复完成的运行会到达 completed 分支）。
- **语义不变式**：触发集 (a)–(e) 原有语义逐字保留，仅增补 (f)；消费/丢弃/盖章机制与既有 (e) 完全共用；与 `P503CurrentStateLoadCoordinator` 合并重跑兼容（标记存活到首个成功落地——FIX-MONTH-1 已验证的同一机制）。
- **效果**：批量确认完成 → 权威刷新（余额/账本状态）+ 月度周期重请求（月卡三值 + SelectMonth 可选域 + 趋势 + 流水行）→ 用户切回首页即见确认结果，无需重启；SelectMonth 域含新交易月（陈旧可选域陷阱消除）。

## 3. 测试要求

- 协调器测试（扩写 `P503LedgerViewHostCoordinatorTest.kt`）：`onImportBatchConfirmed()` 臂标记 → 宿主成功落地消费 → 恰一次无条件 `onMonthlyRequest`；未消费前失败落地 → 丢弃；无臂落地 no-op；与 (e) 的 arm/consume 互不串扰（(e) arm 后 (f) 落地消费属同一标记，断言恰一次）。
- 接线测试：`runImportBatchDispatch` 的 completed 分支调用（若现有测试基建可断言 host 回调；否则以协调器测试+设备验证覆盖，如实登记）。
- 既有向量不回归：FIX-MONTH-1/2 的全部既有测试保持绿。

## 4. 可写文件范围

- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503HostCoordinator.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`（仅 `runImportBatchDispatch` finally 跳 + 必要注释）
- `app-ui/src/commonTest/kotlin/com/unifiedledger/ui/P503LedgerViewHostCoordinatorTest.kt`
- `docs/specs/2026-09-19-p7-03-import-refresh-trigger-design.md`（本文件）
- `docs/DECISIONS.md`（追加 D-153）

不得触碰 ledger-data/ledger-domain/ledger-application/*.sq/迁移/依赖/清单；如需其他文件先在交付说明中登记理由。

## 5. 验收

- 自动：`:app-ui:jvmTest` 全绿（新增向量 + 既有不回归）；`:app-ui:ktlintCheck`；`:android-app:compileDebugKotlin :desktop-app:compileKotlinJvm`。
- verifier 命名断言：V1 触发 (f) 仅在 completed 分支且恰一次 arm；V2 (a)/(d) 守卫、(e) 机制、吸收表零改动；V3 范围仅 3 源文件+2 文档；V4 测试真实；V5 隐私卫生。
- 设备（合并后新 APK）：导入 CCB 样本 → 批量确认入账 → 切首页 → **月卡与流水当次会话立即反映确认结果**（无需重启）→ 月份切换正常（SelectMonth 域含新交易月）→ 重启持久。
