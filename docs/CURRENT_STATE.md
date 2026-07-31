# 当前状态

## 已完成

- 建立 `ledger-domain`、`ledger-application` 与 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块；账务核心继续保持无 Android、网络、同步或 AI 依赖。
- SQLDelight schema 已推进到 v9。除既有正式交易版本与各场景 owner 外，v9 以 dedicated normalized owners 保存 RG-06 operation、relation/lifecycle/installment、source/evidence/candidate 与 reconciliation，并继续复用共享正式账本表；迁移 verifier 与 JVM migration tests 已存在。
- `RG-01` create、retry、distinct、7 个拒绝及 `note_update` runtime 已实现。备注替代保留 posting set 与经济事实，并覆盖 replay、request identity conflict、stale CAS 零写入和 operation oracle。
- `RG-02` 完成 `D-071` 批准的 manual-income 最小 slice：create、retry、2 个独立变体与 8 个拒绝；`category_rename` 仍明确 unsupported。
- `RG-03` 当前冻结范围已实现：13 roots、20 operations 的 outcome、returned IDs、完整 state、deltas 与 status changes 均与 approved expected 精确比较。
- `RG-04` 的 26 个 raw v1 operations 均有 runtime。18 个 manual operations 有精确 operation projection 比较；26 项整体比较 accepted/no-change/rejected 状态计数和选定 returned IDs。import lifecycle、ownership 与 reconciliation 另有深入持久化测试，approved v2 已发布。
- `RG-05` 的领域、应用与持久化 runtime、schema v8 及相关迁移和测试已经进入共享库。`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected；共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes。
- `RG-06` 的 Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated snapshot rehydration，以及八 action 应用/原子 commit-port 契约已完成。手工付款建立 pending 分录对账；导入候选只在原子精确确认时直接建立 matched 对账；镜像合并不改变对账。
- `RG-07` 至 `RG-10` 已有冻结 v1、Python 测试与逐路径 mapping；`RG-11`、`RG-12` 已有 approved direct-v2 fixtures 与 Python 语义测试。它们尚无 Kotlin runtime。

## 验证证据

三个 Kotlin 模块的 JVM 测试与 Python 套件在最近一次完整验证中全部通过，零 failure、零 error。逐模块计数随测试增删而变，不在此复制；验证命令见 [README](../README.md)，实际结果以 `build/test-results` 下的报告为准。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- 三个 KMP library 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行；`ledger-data` 的 SQLDelight 迁移验证与 Android target 编译也可独立运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标，但 Android app 与 Desktop app 尚未建立，因此没有应用运行命令。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；当前 schema 为 v9。UI、导航、依赖注入、同步和更广泛查询方案尚未选择。

## 未完成门槛

- `D-075` 只批准 RG-05 expected，不授权 adapter 实现或 fixture 迁移。
- RG-05 publication 仍未授权，当前没有 `golden/rules-v2` RG-05 工件。
- RG-05 完整状态 oracle 与 RG-06 domain/application/persistence 完成不表示全部黄金场景或正式账务核心已完成；RG-01、RG-02 和 RG-04 仍缺各自声明的完整比较，RG-06 publication 尚未完成，RG-07 至 RG-12 尚无 Kotlin runtime。
- 其他 RG 的 v1 rewrite、v2 publication 和未完成完整比较仍分别受其现有 gate 约束。
