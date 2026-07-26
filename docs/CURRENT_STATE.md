# 当前状态

## 已完成

- 建立 `ledger-domain`、`ledger-application` 与 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块；账务核心继续保持无 Android、网络、同步或 AI 依赖。
- SQLDelight schema 已推进到 v8。除既有正式交易版本、请求幂等、收入、账户互转、混合支付、import owner、候选/证据和分录级对账所有权外，v8 还加入 RG-05 lifecycle 持久化；迁移 verifier 与 JVM migration tests 已存在。
- `RG-01` create、retry、distinct、7 个拒绝及 `note_update` runtime 已实现。备注替代保留 posting set 与经济事实，并覆盖 replay、request identity conflict、stale CAS 零写入和 operation oracle。
- `RG-02` 完成 `D-071` 批准的 manual-income 最小 slice：create、retry、2 个独立变体与 8 个拒绝；`category_rename` 仍明确 unsupported。
- `RG-03` 当前冻结范围已实现：13 roots、20 operations 的 outcome、returned IDs、完整 state、deltas 与 status changes 均与 approved expected 精确比较。
- `RG-04` 的 26 个 raw v1 operations 均有 runtime。18 个 manual operations 有精确 operation projection 比较；26 项整体比较 accepted/no-change/rejected 状态计数和选定 returned IDs。import lifecycle、ownership 与 reconciliation 另有深入持久化测试，approved v2 已发布。
- `RG-05` 的领域、应用与持久化 runtime、schema v8 及相关迁移和测试已经进入共享库。
- `RG-06` 至 `RG-10` 已有冻结 v1、Python 测试与逐路径 mapping；`RG-11`、`RG-12` 已有 approved direct-v2 fixtures 与 Python 语义测试。它们尚无 Kotlin runtime。

## 验证证据

最新保存的 Gradle JVM 测试 XML 报告日期为 2026-07-26：`ledger-domain` 36 项、`ledger-application` 99 项、`ledger-data` 105 项，均为零 failure、零 error。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- 三个 KMP library 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行；`ledger-data` 的 SQLDelight 迁移验证与 Android target 编译也可独立运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标，但 Android app 与 Desktop app 尚未建立，因此没有应用运行命令。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；当前 schema 为 v8。UI、导航、依赖注入、同步和更广泛查询方案尚未选择。

## 未完成门槛

- RG-05 expected 仍为 `approval_status: draft_for_review`，v2 未发布。现有 runtime 不构成 expected 审批、完整场景通过或 publication 授权。
- RG-05 的 25 operations 已逐项比较 outcome、rejection reason/field、新增实体 ID 与 returned IDs，确定性 identity 与契约冻结值一致；尚无 RG-03 等级的完整 state/deltas/status-changes 比较。
- RG-05 仍需独立 expected/runtime 审查和明确用户批准；publication 需要后续单独授权。
- 其他 RG 的 v1 rewrite、v2 publication 和未完成完整比较仍分别受其现有 gate 约束。
