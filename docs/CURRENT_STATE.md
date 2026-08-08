# 当前状态

## 已完成

- 建立 `ledger-domain`、`ledger-application` 与 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块；账务核心继续保持无 Android、网络、同步或 AI 依赖。
- SQLDelight schema 当前为 v13。v10 以 dedicated normalized owners 保存 RG-06 与 RG-07 operation、relation/lifecycle/installment、source/evidence/candidate 与 reconciliation；v11 以 additive nullable column 保存 RG-06 candidate confirmation provenance；v12 以 additive normalized owners 保存 D-082 批准的 RG-09 operation、source/evidence/candidate、adjustment/allocation、formal metadata 与 posting reconciliation；v13 以 additive normalized owners 保存 D-083 批准的 RG-10 operation、returned id、lot、consumption、allocation、expiry、reconstruction、evidence、audit 与 reconciliation facts，继续复用共享正式账本表；迁移 verifier 与 JVM migration tests 已存在。
- `RG-01` create、retry、distinct、7 个拒绝及 `note_update` runtime 已实现。备注替代保留 posting set 与经济事实，并覆盖 replay、request identity conflict、stale CAS 零写入和 operation oracle。
- `RG-02` 完成 `D-071` 批准的 manual-income 最小 slice：create、retry、2 个独立变体与 8 个拒绝；`category_rename` 仍明确 unsupported。
- `RG-03` 当前冻结范围已实现：13 roots、20 operations 的 outcome、returned IDs、完整 state、deltas 与 status changes 均与 approved expected 精确比较；mapping gate 已 approved，v2 工件尚未发布。
- `RG-04` 的 26 个 raw v1 operations 均有 runtime。全 26 项完整 state/report/reconciliation/delta 比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states）。import lifecycle、ownership 与 reconciliation 另有深入持久化测试，approved v2 已发布。
- `RG-05` 的领域、应用与持久化 runtime、schema v8 及相关迁移和测试已经进入共享库。`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected；共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes；v2 fixture rewrite 与 publication 仍未授权。
- `RG-06` 的 Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated snapshot rehydration、八 action 应用/原子 commit-port 契约、schema v9 base/v11 migration、SQLDelight adapter、fixture replay 和 publication 已完成。41 个 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes；发布工件为 `golden/rules-v2/rg-06.json`，manifest 保存 source/expected/canonical/output hashes 与 20/41/61 对象计数。手工付款建立 pending 分录对账；导入候选只在原子精确确认时直接建立 matched 对账；镜像合并不改变对账。当前 gate 状态与执行顺序见 [`RG-06 Gate 清单`](migrations/golden-v2/rg-06-gate-checklist.md)。
- `RG-07` 的 contract、expected、adapter、fixture replay、schema v10 migration 与 Kotlin runtime 已完成；49 个 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes，v2 工件已发布到 `golden/rules-v2/rg-07.json`。
- `RG-08` 已有冻结 v1、Python 测试与逐路径 mapping（4969 路径、2964 requires_contract_amendment），4 个 gap（`RG08-GAP-01..04`）尚无处置决定，无 Kotlin runtime；`RG-10` 的 13 actions 领域、应用与持久化 runtime、44-op 完整状态 oracle（`Rg10FullStateOracleTest`，逐 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes）与 schema v13 SQLDelight persistence 已按 `D-083` 批准范围实现并合入 main（`22f3141`），4 个新 `TransactionKind` 已进入共享账本模型；mapping gate 已 approved（`rg-10-mapping.md`），closure proposal 见 [`RG-10 Closure Proposal`](migrations/golden-v2/rg-10-closure-proposal.md)，v2 工件尚未发布。RG-09 已有 D-082 批准范围内的 domain/application/runtime、schema v12 persistence、50-operation oracle 与 D-065 proof，v2 工件已发布（`e30611b`）；mapping gate 仍为 `proofs_passed_pending_independent_review`（缺独立高风险审查证据），closure proposal 仍 active。`RG-11`、`RG-12` 已有 approved direct-v2 fixtures（`efbb13a`：RG-11 22 ops、RG-12 12 ops）与 Python 语义测试，尚无 Kotlin runtime。

## 验证证据

三个 Kotlin 模块的 JVM 测试与 Python 套件在最近一次完整验证中全部通过，零 failure、零 error。逐模块计数随测试增删而变，不在此复制；验证命令见 [README](../README.md)，实际结果以 `build/test-results` 下的报告为准。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- 三个 KMP library 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行；`ledger-data` 的 SQLDelight 迁移验证与 Android target 编译也可独立运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标，但 Android app 与 Desktop app 尚未建立，因此没有应用运行命令。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；当前 schema 为 v13。UI、导航、依赖注入、同步和更广泛查询方案尚未选择。

## 未完成门槛

- `D-075` 只批准 RG-05 expected，不授权 adapter 实现或 fixture 迁移。
- RG-05 publication 仍未授权，当前没有 `golden/rules-v2` RG-05 工件。
- RG-05 完整状态 oracle、RG-06 publication 与 RG-07 closure 不表示全部黄金场景或正式账务核心已完成；RG-01、RG-02 仍缺各自声明的完整比较（仅 operation-scoped 投影），RG-08、RG-11 和 RG-12 尚无 Kotlin runtime，其中 RG-08 的 4 个 mapping gap（`RG08-GAP-01..04`）尚无契约修订处置决定。
- 其他 RG 的 v1 rewrite、v2 publication 和未完成完整比较仍分别受其现有 gate 约束；RG-03、RG-05 与 RG-10 publication 仍未授权；RG-09 已发布但 mapping gate 独立高风险审查仍为待办。
