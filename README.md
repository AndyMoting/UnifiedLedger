# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

阶段 4“导入与对账闭环”已于 2026-08-23 收口；当前处于阶段 5（双端最小外壳与稳定 Android MVP）：P5-01、P5-02、P5-03 已交付，P5-04.1 三 Tab（首页、账户、分析）与新增入口已实施（布局经 D-123 调整为 Tab 左组 + 底右圆形新增按钮），下一步继续 P5-04 基础交互计划。P4-01 acceptance contract、P4-02 shared import spine、P4-03 微信 XLSX 普通收支、P4-04 transfer formalization、P4-05 支付宝普通收支及 RL-04 余额宝转账路由均已闭环；P4-08 matcher 与 reconciliation persistence 已按 D-103、P4-07 duplicate candidate/closed-records 已按 D-104/D-105 于 2026-08-22 合入 `main`；此后 P4-08 第二批 normalized evidence projection 已按 D-112 完成并合入 `main`（merge `b8755dd`，schema v26，2026-08-27 推送）。此后 P4-08 correction / successor invalidation 已按 D-113 完成并合入 `main`（merge `a3d11bb`，schema v27）——激活预留的失效事件/后继链接与 MISSING/DIFFERENCE 对账结果态，并解除 D-103 登记的该项延期。此后 BP-01 银行 parser 门承接批已按 D-116 于 2026-08-29 交付并推送（CMB 网银 CSV + CCB 网银 XLS 解析器、余额镜像 `note` 诊断与 RL-07 镜像代表路径；银行 parser 门 D-109 O-8 就此关闭，零 schema 变更；PDF/其他银行/信用卡属 D-116 边界外待样本）。

Python 继续作为迁移、规则验证和黄金结果基线。仓库包含 `ledger-domain`、`ledger-application`、`ledger-data` 与 `app-ui` 四个可构建的 Kotlin Multiplatform 共享库模块，SQLDelight schema 当前为 v27（v1→v27 共 26 个迁移文件）。RG-01 至 RG-12 的 runtime 均已进入共享库（RG-01/02 完整 state/delta/status 比较已实现，D-087）：RG-04 全 26 项完整比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states），RG-05 expected 已根据 `D-075` 批准，RG-06 已根据 `D-081` 完成 41-operation full-state replay 并发布 v2 工件，RG-07 expected 已根据 `D-079` 批准，完整状态 oracle 也已完成，RG-09 runtime/persistence 按 `D-082` 批准范围实现，mapping gate 已 approved，严格 9-root/50-operation/59-state runtime oracle 比较发布工件（merge `07986b0`），RG-10 runtime/oracle/persistence 按 `D-083` 批准范围实现并已合入 main（`22f3141`），RG-11/12 direct-v2 runtime 按 `D-085` 实现，RG-08 完整 lending runtime 按 `D-084` 实现。这不表示所有黄金场景或正式账务核心已经完成：RG-01/02 完整比较已实现且 v2 publication 已按 `D-089` A 批完成（`D-090` LF 验收已闭合），RG-08 的 v2 expected 工件与 publication 已按 `D-089` B 批完成；阶段 5 已交付双端最小外壳与 P5-03 演示面 B：`desktop-app` 与 `android-app` 两个组合根应用模块可构建，`app-ui` 提供共享启动、总览和手工支出流程，桌面端可经 `.\gradlew.bat :desktop-app:run` 启动，Android 调试 APK 经 `.\gradlew.bat :android-app:assembleDebug` 构建并由 CI artifact 完成人工门。

阶段 3 的规则场景实现范围如下：

| 场景 | Kotlin runtime 与比较状态 | v2 发布状态 |
| --- | --- | --- |
| `RG-01` | 11 个 operation 级 runtime（manual_expense 10 + note_update 1）已实现，覆盖 replay、request identity conflict 与 stale CAS 零写入；完整 state/report/reconciliation/delta 比较已实现（`Rg01FullStateOracleTest`，8 roots/11 ops/19 states，D-087；`Rg01RawJsonEndToEndTest` 保留） | 已发布：`golden/rules-v2/rg-01.json`（D-089 A 批；8 roots/11 ops/19 states） |
| `RG-02` | 12/13 个 operation 级 runtime（`D-071` 批准的 manual-income 最小 slice：create、retry、2 个变体和 8 个拒绝）；`category_rename` 已按 `D-087` 实现最小闭环（append-only name history，schema v18 的 17.sqm）；完整 state/report/reconciliation/delta 比较已实现（`Rg02FullStateOracleTest`，11 roots/13 ops/24 states） | 已发布：`golden/rules-v2/rg-02.json`（D-089 A 批；11 roots/13 ops/24 states） |
| `RG-03` | 13 roots、20 operations 全量比较完成（`Rg03FullStateOracleTest`）；mapping gate 已 approved | 已发布：`golden/rules-v2/rg-03.json`（`dec854e`，D-086；13 roots/20 ops/33 states） |
| `RG-04` | raw v1 的 26 operations 均有 runtime；全 26 项完整 state/report/reconciliation/delta 比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states） | 已发布 |
| `RG-05` | 领域、应用与持久化 runtime 已进入共享库；`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected。共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes | 已发布：`golden/rules-v2/rg-05.json`（`dec854e`，D-086；17 roots/25 ops/42 states） |
| `RG-06` | Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated rehydration、八 action 应用契约，以及 schema v9/SQLDelight 原子持久化 runtime 已完成；41 operations 已比较完整 state、deltas、status changes；仅剩 clean release 验证门（`rg-06-gate-checklist.md` RG06-G06） | 已发布：`golden/rules-v2/rg-06.json` |
| `RG-07` | contract、expected、adapter、fixture replay、schema v10、migration 与 Kotlin runtime 已完成；49 个 operation 比较完整 state、deltas、status changes | 已发布：`golden/rules-v2/rg-07.json` |
| `RG-08` | 有冻结 v1、Python 测试与 mapping（4969 路径、2964 requires_contract_amendment）；`D-084` 已关闭 4 个 gap（`RG08-GAP-01..04`）并完成完整 lending runtime、44-op oracle 与 schema v15 持久化，mapping gate 已 approved（`rg-08-closure-proposal.md`） | 已发布：`golden/rules-v2/rg-08.json`（D-089 B 批；44 ops，accepted 6/no_change 13/rejected 25，18 roots/62 states） |
| `RG-09` | runtime/persistence、50-operation v1 比较、严格 9-root/50-operation/59-state v2 oracle、D-065 proof、schema v14 派生 adjustment projection migration 与 closure overlay 已完成；历史 gaps 已 closed，mapping gate 已 approved | 已发布：`golden/rules-v2/rg-09.json` |
| `RG-10` | 13 actions 的领域、应用与持久化 runtime 已进入共享库；`Rg10FullStateOracleTest` 对全部 44 operations 比较完整 state、deltas 与 status changes；schema v13 SQLDelight persistence 与 migration 已完成；mapping gate 已 approved，closure proposal 已登记（`rg-10-closure-proposal.md`） | 已发布：`golden/rules-v2/rg-10.json`（`7cf419a`；44 ops，accepted 12/no_change 10/rejected 22，14 roots/58 states） |
| `RG-11`、`RG-12` | direct-v2（无 mapping/adapter；expected = 冻结契约字节副本，契约批准于 `efbb13a`）：`D-085` 已完成 Kotlin runtime、oracle 与 schema v16/v17 持久化；closure gate 已 approved（`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md` review disposition） | 已发布：`golden/rules-v2/rg-11.json`（22 ops/3 roots/25 states）、`golden/rules-v2/rg-12.json`（12 ops/3 roots/15 states）（`dec854e`，D-086） |

这些结果不等于全部黄金场景或正式账务核心已经完成。已发布集合为 RG-01 至 RG-12（12 个 case，`golden/rules-v2` 工件 + manifest 登记）：RG-03/05/11/12 于 `dec854e` 按 `D-086` 发布，RG-10 于 `7cf419a` 发布（expected 由冻结 v1 契约与 runtime-input 确定性生成，`validate_golden_case_v2` 同步扩展支持 RG-10 事务类型）；RG-01/02 于 A 批按 `D-089` 发布（A 批工件与 manifest 登记经 `D-090` LF 验收闭合），RG-08 于 B 批按 `D-089` 发布（44 ops，accepted 6/no_change 13/rejected 25，18 roots/62 states，expected SHA-256 `b3434dfc…`）；RG-04 全 26 项完整比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`），manifest discovery 已登记为 26-operation full comparison；RG-06 仅剩 clean release 验证门（`rg-06-gate-checklist.md` RG06-G06）；RG-07 的空壳测试类 `Rg07FixtureReplayTest` 非门禁；RG-09 的三个历史 mapping gaps 已由 closure overlay 标记 closed，mapping gate 已 approved。RG-11/12 为 direct-v2：expected 是冻结契约 `golden/rules/rg-11.json`/`rg-12.json` 的字节副本，manifest `source_sha256 == expected_byte_sha256`，closure gate 已 approved（`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md` review disposition）。`D-075` 只批准 RG-05 expected；`D-079` 批准 RG-07 expected、adapter、fixture replay、schema v10 migration 与 runtime，`D-080` 记录了 RG-07 的明确 publication target；`D-081` 批准 RG-06 closure、adapter/replay、fixture rewrite 和 publication；`D-082` 批准 RG-09 contract closure、完整 oracle、schema v12 persistence 与 migration；RG-09 后续 schema v14 移除 current adjustment duplicate projections，发布则由独立 publication gate 完成；`D-083` 批准 RG-10 contract closure（13 actions runtime、44-op 完整 oracle、schema v13 persistence 与 migration、4 个新 `TransactionKind`），不授权 publication；RG-10 mapping gate 已 approved，closure proposal 已登记，D-083 “50-operation” 的 44-op 处置与 W3 delta（reconciliation 值域塌缩、disabled balance、publication-side expiry event、3 个 rejected field_path 对齐 Kotlin 权威）说明见 `docs/migrations/golden-v2/rg-10-closure-proposal.md`；`D-084` 关闭 RG-08 的 4 个 mapping gap 并批准完整 lending runtime（`rg-08-closure-proposal.md`）；`D-085` 批准 RG-11/12 direct-v2 runtime、oracle 与 schema v16/v17 持久化，closure gate 已 approved（`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md`）；`D-086` 统一授权 RG-03/05/10/11/12 的 v2 发布；`D-087` 授权 RG-01/02 完整比较执行边界并批准 `category_rename` 最小闭环（schema v18 的 17.sqm）；`D-088` 登记异源审查缺陷修复批次（schema v19 的 18.sqm = RG-12 guard、发布工具恢复语义、CONTRACT-001、RG08-001 与文档卫生）；`D-089` 授权 RG-01/02（A 批）与 RG-08（B 批）的 golden v2 publication；`D-090` 订立 publication LF integrity 契约（raw-byte hash 以 UTF-8+LF 为规范域、`.gitattributes` 3 条、共享 pre-publish integrity gate、11 case raw-hash metadata 重算）。报表、UI 和平台客户端仍未建立（通用导入与对账链路已随阶段 4 的 shared import spine 与 P4-08 对账持久化进入共享库）；当前持久化选择也不预先决定同步或更广泛查询方案。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 验证

Kotlin 构建需要 JDK 21。使用仓库内的 Gradle Wrapper 分别运行三个共享库模块的 JVM 测试。16 GB Windows 主机上 Gradle/Kotlin 验证必须串行运行，使用单 worker 和 1 GB heap；每次运行结束后停止 daemon。完整约束与命令见[开发规范](docs/CONTRIBUTING.md)。

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :app-ui:jvmTest --stacktrace --rerun-tasks --warning-mode all
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

依赖和 Gradle 分发包已在本机缓存时，可以为上述命令追加 `--offline`。四个共享库模块都是 library；`desktop-app` 与 `android-app` 为组合根应用模块。桌面端运行共享演示面 B（P5-03 只读总览与手工支出流程）：

```powershell
.\gradlew.bat :desktop-app:run
```

构建 Android 调试 APK（P5-03 构建门；CI artifact 用于 Android 安装、启动与人工验收）：

```powershell
.\gradlew.bat :android-app:assembleDebug --stacktrace --rerun-tasks --warning-mode all
```

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
