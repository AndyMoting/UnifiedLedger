# 路线图

## 阶段 1：正式文档与规则迁移

建立权威文档，迁移已确认决定与通用账务规则，并完成结构和隐私检查。

- 进入条件：项目目标、迁移边界和隐私要求已经确认。
- 完成条件：九份正式文档职责明确，决定编号可追溯，文档检查通过，当前状态具有唯一下一步。

## 阶段 2：黄金测试冻结

为匿名规则场景和本地真实来源场景写出完整预期交易、分录、余额、统计与对账结果。

- 进入条件：账务规则和决定已完成首次迁移，场景范围及隐私边界明确。
- 完成条件：核心场景具有冻结版本，能够逐交易、逐分录和逐证据比较，不只比较最终总额。

## 阶段 3：共享核心验证

使用纯 Kotlin/Kotlin Multiplatform 实现已冻结的领域模型和用例，并与 Python 基线比较。

- 进入条件：首批黄金场景的输入和完整预期已经冻结。
- 完成条件：黄金场景全部通过，核心不依赖客户端平台、网络、同步或 AI，并能确定性重放余额。
- `RG-01`：create、retry、distinct、7 个拒绝和 `note_update` runtime 已完成；完整 state/report/reconciliation/delta 比较已实现（`Rg01FullStateOracleTest`，8 roots/11 ops/19 states，D-087）；v2 已按 `D-089` A 批发布（`golden/rules-v2/rg-01.json`，8 roots/11 ops/19 states）。
- `RG-02`：`D-071` 批准的 `manual_income` 最小 slice 已完成，包括主创建、重试、2 个变体和 8 个拒绝。`category_rename` 已按 `D-087` 实现最小闭环（append-only name history，schema v18 的 17.sqm）；完整 state runtime 比较已实现（`Rg02FullStateOracleTest`，11 roots/13 ops/24 states）；v2 已按 `D-089` A 批发布（`golden/rules-v2/rg-02.json`，11 roots/13 ops/24 states），transaction correction/CAS 仍待完成。
- `RG-03`：当前冻结范围的 13 roots、20 operations 已完成 outcome、returned IDs、完整 state、deltas 和 status changes 比较，mapping gate 已 approved；v2 工件已发布（`golden/rules-v2/rg-03.json`，`dec854e`，D-086；13 roots/20 ops/33 states）。
- `RG-04`：raw v1 的 26 operations 均有 runtime；全 26 项完整 state/report/reconciliation/delta 比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states）；import lifecycle、ownership 和 reconciliation 已实现，v2 已发布。manifest discovery 已登记为 26-operation full comparison。
- `RG-05`：领域、应用与持久化 runtime 及 schema v8 已进入共享库。`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected；共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes。这不关闭其他黄金场景或完整产品闸门；`D-075` 也不授权 adapter 实现或 fixture 迁移。v2 工件已按 `D-086` 发布（`golden/rules-v2/rg-05.json`，`dec854e`；17 roots/25 ops/42 states）。
- `RG-06`：Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated snapshot rehydration、八 action 应用契约、schema v9/SQLDelight persistence adapter、41-operation full-state replay、fixture rewrite 与 publication 已完成；v2 工件为 `golden/rules-v2/rg-06.json`。
- `RG-07`：contract、expected、adapter、fixture replay、schema v10 migration 与 Kotlin runtime 已完成，49 个 operation 比较完整 state、deltas、status changes；v2 已发布（`golden/rules-v2/rg-07.json`）。
- `RG-08`：冻结 v1、Python 测试与 mapping 之上，`D-084` 已关闭 4 个 mapping gap（`RG08-GAP-01..04`）并完成完整 lending runtime、44-op oracle 与 schema v15 持久化，mapping gate 已 approved；v2 expected 与工件已按 `D-089` B 批发布（`golden/rules-v2/rg-08.json`，44 ops，accepted 6/no_change 13/rejected 25，18 roots/62 states）。
- `RG-09`：runtime/persistence、50-operation v1 比较、严格 9-root/50-operation/59-state v2 oracle、D-065 proof、schema v14 派生 adjustment projection migration 与 closure overlay 已完成，历史 gaps 已 closed，mapping gate 已 approved；v2 已发布（`golden/rules-v2/rg-09.json`）。
- `RG-10`：13 actions runtime、44-op 完整 oracle（accepted 12/no_change 10/rejected 22，14 roots/58 states）与 schema v13 persistence 已完成，mapping gate 已 approved，closure proposal 已登记；v2 已发布（`golden/rules-v2/rg-10.json`，`7cf419a`）。
- `RG-11`、`RG-12`：direct-v2 场景（无 mapping/adapter，expected=冻结契约字节副本）；`D-085` 已实现完整 Kotlin runtime、22-op/12-op oracle 与 schema v16/v17 持久化，closure gate 已 approved（`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md`）；v2 工件已发布（`golden/rules-v2/rg-11.json`、`golden/rules-v2/rg-12.json`，`dec854e`，D-086）。
- 跨场景可复用范围限于严格解析、明确确认、request snapshot 与正式账务链；不得提前泛化专项 DTO、表或业务 owner。

## 阶段 4：导入与对账闭环

建立来源适配、标准化、去重、候选确认、证据匹配和差异处理。

- 进入条件：共享核心模型稳定，来源记录、候选、正式账目和对账记录的边界已经由测试证明。
- 完成条件：支持的标准来源能够从导入走到正式账目与可解释对账状态，重复导入不产生重复账目。

## 阶段 5：双端最小外壳

建立 Android 与 Desktop 可运行外壳，持续编译并调用同一业务核心。`android-app` 与 `desktop-app` 两个组合根模块已按 P5-02 建立（只做装配与占位界面）；桌面占位应用可经 `.\gradlew.bat :desktop-app:run` 运行，Android 调试 APK 经 `.\gradlew.bat :android-app:assembleDebug` 构建（模拟器安装/启动为人工门）。

`D-119` 已批准 P5-01/P5-02 到 P5-03 的闭环补充契约，D-118 保持已交付。当前方向按 approved 规格顺序推进：可起草 P5-03 spec-only，并独立执行 `closure-evidence follow-up`；ledger-scoped/current-version read/options、snapshot-aware unknown resolution、固定 catalog、startup fail-closed、精确金额/requestId 和最小 UI 均待后续批实现。Android emulator entry 证据开放；本登记不授权 P5-03 代码实施，零 schema/生产行为变化。

- 进入条件：共享核心具备稳定调用边界，导入与对账最小闭环通过验收。
- 完成条件：两端可以打开本地测试账本、调用共享用例并持续通过构建检查。

## 阶段 6：Android 日常工作流

实现移动端的日常记录、查询、草稿确认和必要的系统入口。

- 进入条件：Android 最小外壳可稳定调用共享核心，关键日常流程已有验收场景。
- 完成条件：用户可在离线状态完成支出、收入、转账、借贷、草稿确认和账目查询的日常闭环。

## 阶段 7：Desktop 批量工作台

实现桌面端的批量导入、密集审核、规则维护和差异处理。

- 进入条件：Desktop 最小外壳稳定，导入与对账接口能够支持批量操作。
- 完成条件：用户可对一批来源完成导入、去重、审核、入账和差异处理，并获得可追溯结果。

## 阶段 8：可选同步和 AI 扩展

在核心闭环稳定后评估并实现可关闭、可替换的同步与智能辅助能力。

- 进入条件：双端本地流程稳定，数据导出、版本历史、隐私边界和失败恢复已经验证。
- 完成条件：扩展能力默认关闭，关闭后核心验收不受影响；开启后不能绕过正式账本规则和用户确认边界。

## 阶段约束

阶段按顺序推进。每一阶段必须以可重复验证的结果满足完成条件，才能进入下一阶段；新想法不能跳过当前闸门，后续能力不得破坏更早阶段的不变量和离线可用性。
