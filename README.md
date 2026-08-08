# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

Python 继续作为迁移、规则验证和黄金结果基线。仓库包含 `ledger-domain`、`ledger-application` 和 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块，SQLDelight schema 当前为 v13。RG-04、RG-05、RG-06、RG-07、RG-09 和 RG-10 的 runtime 已进入共享库；RG-04 全 26 项完整比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states），RG-05 expected 已根据 `D-075` 批准，RG-06 已根据 `D-081` 完成 41-operation full-state replay 并发布 v2 工件，RG-07 expected 已根据 `D-079` 批准，完整状态 oracle 也已完成，RG-09 runtime/persistence 按 `D-082` 批准范围实现并已发布 v2 工件（`e30611b`），RG-10 runtime/oracle/persistence 按 `D-083` 批准范围实现并已合入 main（`22f3141`）。这不表示所有黄金场景或正式账务核心已经完成。RG-08、RG-11 和 RG-12 尚无 Kotlin runtime（RG-11/12 已有 approved direct-v2 fixtures）；仓库仍没有可运行的 Android 或 Desktop app，也没有应用运行命令。

阶段 3 的规则场景实现范围如下：

| 场景 | Kotlin runtime 与比较状态 | v2 发布状态 |
| --- | --- | --- |
| `RG-01` | 11 个 operation 级 runtime（manual_expense 10 + note_update 1）已实现，覆盖 replay、request identity conflict 与 stale CAS 零写入；仅 operation-scoped 投影比较（`Rg01RawJsonEndToEndTest`，D-069/070 边界），尚无完整 state/report/reconciliation/delta 比较 | 未发布 |
| `RG-02` | 12/13 个 operation 级 runtime（`D-071` 批准的 manual-income 最小 slice：create、retry、2 个变体和 8 个拒绝）；`category_rename` 按 `D-071` 有意 Unsupported；仅 op 级投影比较，无完整 state/report/reconciliation/delta 比较 | 未发布 |
| `RG-03` | 13 roots、20 operations 全量比较完成（`Rg03FullStateOracleTest`）；mapping gate 已 approved；v2 工件不存在 | 未发布 |
| `RG-04` | raw v1 的 26 operations 均有 runtime；全 26 项完整 state/report/reconciliation/delta 比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states） | 已发布 |
| `RG-05` | 领域、应用与持久化 runtime 已进入共享库；`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected。共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes；缺 v2 fixture rewrite 与发布授权 | 未发布；尚无 `golden/rules-v2` RG-05 工件 |
| `RG-06` | Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated rehydration、八 action 应用契约，以及 schema v9/SQLDelight 原子持久化 runtime 已完成；41 operations 已比较完整 state、deltas、status changes；仅剩 clean release 验证门（`rg-06-gate-checklist.md` RG06-G06） | 已发布：`golden/rules-v2/rg-06.json` |
| `RG-07` | contract、expected、adapter、fixture replay、schema v10、migration 与 Kotlin runtime 已完成；49 个 operation 比较完整 state、deltas、status changes | 已发布：`golden/rules-v2/rg-07.json` |
| `RG-08` | 有冻结 v1、Python 测试与 mapping（4969 路径、2964 requires_contract_amendment）；4 个 gap（`RG08-GAP-01..04`）尚无处置决定；无 Kotlin runtime、无 v2 工件 | 未发布 |
| `RG-09` | runtime/persistence、50-operation 全量比较与 D-065 proof 已完成，v2 工件已发布（`e30611b`）；mapping gate 仍 `proofs_passed_pending_independent_review`（缺独立审查证据，待办） | 已发布：`golden/rules-v2/rg-09.json` |
| `RG-10` | 13 actions 的领域、应用与持久化 runtime 已进入共享库；`Rg10FullStateOracleTest` 对全部 44 operations 比较完整 state、deltas 与 status changes；schema v13 SQLDelight persistence 与 migration 已完成；已合入 main（`22f3141`）；mapping gate 已 approved | 未发布 |
| `RG-11`、`RG-12` | direct-v2 fixtures 已批准（`efbb13a`，无 D 编号）：RG-11 22 ops（accepted 11/no_change 1/rejected 10）、RG-12 12 ops（accepted 1/no_change 1/rejected 10）；有 Python 语义测试，无 Kotlin runtime | direct-v2 fixture 已批准 |

这些结果不等于全部黄金场景或正式账务核心已经完成。已发布集合为 RG-04、RG-06、RG-07 与 RG-09（`golden/rules-v2` 工件 + manifest 登记）：RG-04 全 26 项完整比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`），manifest discovery 元数据仍为发布时点的 partial 记录（工件更新属发布 gate）；RG-06 仅剩 clean release 验证门（`rg-06-gate-checklist.md` RG06-G06）；RG-07 的空壳测试类 `Rg07FixtureReplayTest` 非门禁；RG-09 的 mapping gate 仍为 `proofs_passed_pending_independent_review`（缺独立高风险审查证据，closure proposal 仍 active），是待办状态而非已 approved。未发布集合为 RG-01、RG-02、RG-03、RG-05、RG-10、RG-11 与 RG-12：其中 RG-03/05/10 的 runtime/oracle 已就绪仅缺发布授权（RG-03 20/20 全量比较、mapping gate approved；RG-10 已合入 `main@22f3141`、mapping gate approved；RG-05 缺 v2 fixture rewrite）；RG-01/02 仅有 operation-scoped 投影比较，无完整 state/report/reconciliation/delta 比较；RG-11/12 已有 approved direct-v2 fixtures（`efbb13a`），无 Kotlin runtime；RG-08 有 mapping（4969 路径、2964 requires_contract_amendment）与 4 个 gap（`RG08-GAP-01..04`）但无处置决定，开工前置为契约修订决定。`D-075` 只批准 RG-05 expected；`D-079` 批准 RG-07 expected、adapter、fixture replay、schema v10 migration 与 runtime，`D-080` 记录了 RG-07 的明确 publication target；`D-081` 批准 RG-06 closure、adapter/replay、fixture rewrite 和 publication；`D-082` 批准 RG-09 contract closure、完整 oracle、schema v12 persistence 与 migration，但不授权 publication；`D-083` 批准 RG-10 contract closure（13 actions runtime、44-op 完整 oracle、schema v13 persistence 与 migration、4 个新 `TransactionKind`），不授权 publication；RG-10 mapping gate 已 approved，closure proposal 与 D-083 “50-operation” 的 44-op 处置说明见 `docs/migrations/golden-v2/rg-10-closure-proposal.md`。报表、通用导入与对账模块、UI 和平台客户端仍未建立；当前持久化选择也不预先决定同步或更广泛查询方案。

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

- [项目地图](docs/PROJECT_MAP.md)
- [项目章程](docs/PROJECT_CHARTER.md)
- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [账务规则](docs/ACCOUNTING_RULES.md)
- [系统架构](docs/ARCHITECTURE.md)
- [决定记录](docs/DECISIONS.md)
- [路线图](docs/ROADMAP.md)
- [当前状态](docs/CURRENT_STATE.md)
- [黄金测试](docs/GOLDEN_TESTS.md)
- [开发规范](docs/CONTRIBUTING.md)
- [领域模块](docs/modules/ledger-domain.md)
- [应用模块](docs/modules/ledger-application.md)
- [数据模块](docs/modules/ledger-data.md)
