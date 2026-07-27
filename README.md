# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

Python 继续作为迁移、规则验证和黄金结果基线。仓库包含 `ledger-domain`、`ledger-application` 和 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块，SQLDelight schema 当前为 v8。RG-05 的领域、应用与持久化 runtime 已进入共享库；expected 已根据 `D-075` 批准，完整状态 oracle 也已完成。这不表示所有黄金场景、正式账务核心或发布闸门已经完成。仓库仍没有可运行的 Android 或 Desktop app，也没有应用运行命令。

阶段 3 的规则场景实现范围如下：

| 场景 | Kotlin runtime 与比较状态 | v2 发布状态 |
| --- | --- | --- |
| `RG-01` | create、retry、distinct、7 个拒绝及 `note_update` 已实现；备注替代覆盖 replay、request identity conflict 与 stale CAS 零写入；尚无完整 state/report/reconciliation/delta 比较 | 未发布 |
| `RG-02` | 已实现 `D-071` 批准的 manual-income 最小 slice：create、retry、2 个变体和 8 个拒绝；`category_rename` unsupported，尚无完整 state runtime 比较 | 未发布 |
| `RG-03` | 当前冻结范围的 13 roots、20 operations 已逐项比较 outcome、returned IDs、完整 state、deltas 与 status changes | 未发布 |
| `RG-04` | raw v1 的 26 operations 均有 runtime；其中 18 个 manual operations 有精确 projection 比较，26 项整体比较状态计数和部分 returned IDs；尚无全 26 项完整 state/report/reconciliation/delta 比较 | 已发布 |
| `RG-05` | 领域、应用与持久化 runtime 已进入共享库；`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected。共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes | 未发布；尚无 `golden/rules-v2` RG-05 工件 |
| `RG-06` 至 `RG-10` | 有冻结 v1、Python 测试和逐路径 mapping，无 Kotlin runtime | 未发布 |
| `RG-11`、`RG-12` | 有 approved direct-v2 fixtures 与 Python 语义测试，无 Kotlin runtime | direct-v2 fixture 已批准 |

这些结果不等于全部黄金场景或正式账务核心已经完成。RG-01、RG-02 和 RG-04 仍缺各自声明的完整比较；RG-06 至 RG-12 尚无 Kotlin runtime。`D-075` 只批准 RG-05 expected，不授权 adapter 实现或 fixture 迁移；publication 仍需单独授权，当前也没有 `golden/rules-v2` RG-05 工件。报表、通用导入与对账模块、UI 和平台客户端仍未建立；当前持久化选择也不预先决定同步或更广泛查询方案。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 验证

Kotlin 构建需要 JDK 21。使用仓库内的 Gradle Wrapper 分别运行三个共享库模块的 JVM 测试：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-data:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

验证全部 SQLDelight migrations：

```powershell
.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all
```

编译 Android system SQLite driver 装配：

```powershell
.\gradlew.bat :ledger-data:compileAndroidMain --stacktrace --rerun-tasks --warning-mode all
```

运行当前全部 Gradle 检查：

```powershell
.\gradlew.bat check --rerun-tasks --warning-mode all
```

依赖和 Gradle 分发包已在本机缓存时，可以为上述命令追加 `--offline`。三个模块都是 library；仓库当前没有可运行的 Android 或 Desktop app/client，因此没有应用运行命令。

运行完整 Python 测试：

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest discover -s tests -t . -v
```

验证正式文档：

```powershell
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

完整环境、分支、提交、合并和隐私要求见[开发规范](docs/CONTRIBUTING.md)。

## 正式文档

- [项目章程](docs/PROJECT_CHARTER.md)
- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [账务规则](docs/ACCOUNTING_RULES.md)
- [系统架构](docs/ARCHITECTURE.md)
- [决定记录](docs/DECISIONS.md)
- [路线图](docs/ROADMAP.md)
- [当前状态](docs/CURRENT_STATE.md)
- [黄金测试](docs/GOLDEN_TESTS.md)
- [开发规范](docs/CONTRIBUTING.md)
