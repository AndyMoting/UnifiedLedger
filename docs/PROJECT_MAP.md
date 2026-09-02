# 项目地图

## 用途

本文件是 UnifiedLedger 的正式导航入口。它说明当前模块、文档、机器契约和验证入口之间的关系，但不重复各文档拥有的业务规则。开始任务时先定位目标，再只读取对应材料。

## 权威归属

不同材料各自拥有不同事实，不能只根据目录位置或文件新旧决定优先级：

- 项目章程、产品需求、账务规则和决定拥有已批准的产品与账务行为。
- Golden 文档、schema、已批准结果和语义测试拥有 Golden 数据交换与验收契约。
- 已合入 `main` 的构建文件、模块实现、测试和迁移拥有当前工程现实。
- 设计材料只在状态明确且其结论已进入所属正式文档后约束实现。

材料互相矛盾时暂停扩散相关结论，回到对应 owner 和决定核验，并在同一工作项中修正文档、测试或实现。本地状态、临时计划和外部参考不构成产品规则。

## 当前模块

| 模块 | 当前职责 | 主要入口 | 模块导航 | 验证入口 |
| --- | --- | --- | --- | --- |
| `ledger-domain` | 精确金额、正式交易、分录、版本和纯领域不变量 | [`src/commonMain`](../ledger-domain/src/commonMain) | [领域模块](modules/ledger-domain.md) | [`src/commonTest`](../ledger-domain/src/commonTest) |
| `ledger-application` | 操作用例、确认、候选、幂等和外部能力端口 | [`src/commonMain`](../ledger-application/src/commonMain) | [应用模块](modules/ledger-application.md) | [`src/commonTest`](../ledger-application/src/commonTest)、[`src/jvmTest`](../ledger-application/src/jvmTest) |
| `ledger-data` | SQLDelight、原子存储、迁移、重读和平台数据库装配 | [`src/commonMain`](../ledger-data/src/commonMain) | [数据模块](modules/ledger-data.md) | [`src/jvmTest`](../ledger-data/src/jvmTest) |
| `app-ui` | 共享 P5-03 演示面 B、P5-04.1 三 Tab 壳（首页/账户/分析与新增入口，布局经 D-123 调整；分析 Tab 经 facade 消费 application 纯派生 `SummarizeLedgerActivity`）、P5-04.2 编辑页系统返回关闭与固定确认/取消（`overview`/`originTab` 穿线 + `Back` 事件 + `backHandler` 平台钩子，D-125）、纯 UI 状态机/reducer、手工支出流程与无障碍呈现 | [`src/commonMain`](../app-ui/src/commonMain) | [架构](ARCHITECTURE.md) | [`src/commonTest`](../app-ui/src/commonTest)、`:app-ui:jvmTest`（[开发规范](CONTRIBUTING.md)） |
| `desktop-app` | Desktop 组合根、P5-03 演示面 B 启动与数据库装配 | [`src/jvmMain`](../desktop-app/src/jvmMain) | [架构](ARCHITECTURE.md) | [`src/jvmTest`](../desktop-app/src/jvmTest)、`:desktop-app:build`、`:desktop-app:run`（[开发规范](CONTRIBUTING.md)） |
| `android-app` | Android 组合根、P5-03 演示面 B 启动与数据库装配 | [`src/main`](../android-app/src/main) | [架构](ARCHITECTURE.md) | `:android-app:compileDebugKotlin`、`:android-app:assembleDebug`（CI artifact 与 Android 人工门；[开发规范](CONTRIBUTING.md)） |

`import-core`、`reconcile-core`、`reporting-core` 与平台模块（`platform-android`/`platform-desktop`）仍是[架构](ARCHITECTURE.md)中的目标职责，不是当前可构建模块；`android-app` 与 `desktop-app` 两个客户端组合根已可构建。不存在的模块不得被文档或代码当作已经实现。

P5-03 演示面 B 已在两端组合根接通：`app-ui` 提供启动、总览、编辑、确认和结果屏幕，以及纯 reducer 驱动的异步状态转场；Desktop 的鼠标流程和键盘/焦点流程、Android 的模拟器启动/重开/持久化流程与 TalkBack 流程均已完成人工验证。P5-04.2（D-125）将编辑流状态机扩展 `overview`/`originTab` 穿线与 `Back` 事件：编辑流各态系统返回关闭回来源 Tab，`app-ui` 暴露 `backHandler` 平台钩子，Android 组合根以 activity-compose `BackHandler` 接入（零依赖）。Android 调试 APK 由 CI artifact 提供，当前版本的数据库 schema 为 v27。

阶段路线与当前检查点分别见[路线图](ROADMAP.md)和[当前状态](CURRENT_STATE.md)；当前阶段为 P5-04 基础交互计划。

## 跨模块契约

| 主题 | 人类可读规则 | 机器工件 | 验证 |
| --- | --- | --- | --- |
| 产品和账务行为 | [产品需求](PRODUCT_REQUIREMENTS.md)、[账务规则](ACCOUNTING_RULES.md)、[决定](DECISIONS.md) | 无 | 相关模块测试和 Golden 场景 |
| Golden v2 | [Golden Schema](GOLDEN_SCHEMA.md)、[Golden 测试](GOLDEN_TESTS.md)、[v2 清单](GOLDEN_V2_INVENTORY.md) | [`schemas/`](../schemas)、[`golden/`](../golden) | [`tests/python`](../tests/python) |
| v1 到 v2 迁移 | [`docs/migrations/golden-v2`](migrations/golden-v2) | mapping、path-map、expected draft | mapping、contract 和 semantic tests |
| P4-02 共享导入 spine | [设计规格](specs/2026-08-13-p4-02-shared-import-spine-design.md)（approved） | `ledger-data` 的 20.sqm（v20→v21）、`Ledger.sq` 共享 `import_*` 表族 | migration verifier、`ImportSpineLifecycleEndToEndTest`、`ImportSpineMigrationCoexistenceTest` |
| P4-03 微信普通收支 | [设计规格](specs/2026-08-14-p4-03-wechat-ordinary-flows-design.md)（approved） | `ledger-application` jvm 源集的 `WechatBillParser`、`WechatSourceTokens`、`WechatParserTypes`（POI 仅 jvm 作用域） | `WechatBillParserJvmTest`、`ImportSpineWechatEndToEndTest` |
| P4-04 转账正式化切片 | [设计规格](specs/2026-08-14-p4-04-transfer-formalization-slice-design.md)（approved，2026-08-17 重新批准；D-100） | `21.sqm`（v21→v22）、`TransferFlowFormalFactory`、`ImportRecordKind` 判别确认链 | `ImportSpineTransferEndToEndTest`、迁移 verifier |
| P4-05 支付宝普通收支与 RL-04 余额宝转账 | [普通收支规格](specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md)（approved）与 [RL-04 规格](specs/2026-08-18-p4-05b-rl04-yuebao-transfer-routing-design.md)（approved） | `ledger-application` jvm 源集的支付宝解析器与余额宝转账路由；schema v22 不变 | `AlipayCsvParserJvmTest`、`ImportSpineAlipayEndToEndTest`、`ImportSpineAlipayYuebaoTransferEndToEndTest` |
| P4-08 matcher 与对账持久化 | [设计规格](specs/2026-08-19-p4-08-matcher-contract-design.md)（approved；D-103） | schema v23 的 evidence link、posting reconciliation 及追加历史；matcher runtime | `ImportSpineLifecycleEndToEndTest`、`ImportSpineMigrationCoexistenceTest`、ledger-data JVM tests |
| P4-07 重复候选与关闭记录 | [契约规格](specs/2026-08-19-p4-07-duplicate-closed-records-design.md)（approved；D-104）与 [实施规格](specs/2026-08-19-p4-07-duplicate-closed-records-implementation-design.md)（approved；D-105） | schema v24（`23.sqm`）：duplicate candidate/status history/review claim/snapshot/receipt 五张 append-only 表、`import_source_record` funding 列（重建保后代）；`ReviewImportDuplicateCandidate` 用例 | `P407DuplicateClosedFullStateOracleTest`、`ImportSpineLifecycleEndToEndTest`、`LedgerDatabaseMigrationTest` |
| P4-06 信用与混合支付（RL-05/RL-06） | [片 1 实施规格](specs/2026-08-22-p4-06-slice1-credit-implementation-design.md)（approved；D-107）与 [片 2 实施规格](specs/2026-08-22-p4-06-slice2-mixed-activation-design.md)（approved；D-108） | schema v25（`24.sqm`，片 1 一次建成、片 2 零 schema 变更）：多腿 decision snapshot 扩展（片 2 启用混合腿金额列）、信用/混合 candidate 与 `import_candidate_payment_profile`、`mixed_payment` 关联组两表（片 2 启用：确认事务内腿先头后写入）；`CreditFlowFormalFactory`、`MixedPaymentFlowFormalFactory`（纯复用 `createMixedPaymentExpense`）、`AlipayCsvParser` 信用腿路由与混合激活/构成门、`SqlDelightImportSpineStore` v3 kind、混合确认门与 replay 等价链 | `P406CreditFullStateOracleTest`、`AlipayCsvParserCreditJvmTest`、`MixedPaymentTest`、`LedgerDatabaseMigrationTest` |
| P5-04.2 编辑页系统返回关闭与固定确认/取消 | [实施规格](specs/2026-09-02-p5-04-2-editor-back-close-design.md)（冻结 SHA-256 `e8d2e660…`；D-125） | `app-ui` 编辑流状态机 `overview`/`originTab` 穿线 + `Back` 事件 + `backHandler` 平台钩子；`android-app` 以 activity-compose `BackHandler` 接入 | `P503ReducerTest` 新增 back 关闭/threading/ISE 用例、`:app-ui:jvmTest`、`:android-app:compileDebugKotlin` |
| 开发和发布验证 | [开发规范](CONTRIBUTING.md) | Gradle Wrapper、Python validator | focused tests、affected full suite、文档验证 |

`golden/rules/` 保存冻结输入或答案，`golden/rules-v2/` 只保存已进入正式 v2 发布边界的机器工件。`docs/migrations/golden-v2/` 保存迁移和审查材料；其中的 expected 只有在明确批准后才能成为发布工件。

## 依赖方向

```text
android-app ----+                 +--> ledger-application --> ledger-domain
desktop-app ----+--> app-ui -----+                 ^
ledger-data --------------------------------------+
```

- `ledger-domain` 不依赖平台、持久化、网络或外部参考树。
- `ledger-application` 依赖领域类型并定义端口，不拥有数据库 schema。
- `ledger-data` 实现应用端口并持久化领域状态，不重新定义账务规则。
- `app-ui` 共享界面只消费 application 层类型（facade/use cases/read projection），永不依赖 `ledger-data`、SQLDelight 或 database handle。
- `android-app`/`desktop-app` 只做组合根，装配端口实现、持久化与应用用例后调用共享 `P503App`，不复制共享核心逻辑。
- Python 工具用于迁移、验证和 Golden 基线，不是生产运行依赖。

## 按任务加载

| 任务 | 最小读取集合 |
| --- | --- |
| 修改领域不变量 | 账务规则、相关决定、领域模块文档、相关领域测试 |
| 修改应用操作或确认 | 产品需求、相关决定、应用模块文档、相关 contract/operation tests |
| 修改数据库或迁移 | 架构、数据模块文档、目标 schema/migration、迁移测试 |
| 修改 Golden contract | Golden Schema、Golden 测试、目标 mapping/path-map、Python validator 和目标测试 |
| 修改正式文档 | 本地图、目标 owner 文档、文档验证规则 |

不要因为文件存在就读取无关 RG、其他模块内部实现或本地参考资料。

## 本地与外部材料

本地材料被 Git 忽略，可能只存在于维护者环境，不属于正式产品规则，也不能成为构建依赖。它们通过根 `AGENTS.md` 和当前 checkpoint 按需路由，不作为模块导航的必读项：

由 checkpoint 路由的每份本地文档（根 `AGENTS.md` 索引除外）必须紧接标题后、且在文件级恰好一次声明 `状态：active` 或 `状态：archived`。只有 `active` 文档可在 checkpoint 点名后按其用途加载；`archived` 文档只作历史记录，不授权执行。状态标记缺失或与 checkpoint 冲突时，必须停止加载该文档并先协调一致的状态。

| 本地入口 | 用途 | 加载规则 |
| --- | --- | --- |
| `docs/PROJECT_STATE.local.md` | 当前目标、Git 现实、停止位置和唯一下一步 | 存在时作为恢复 checkpoint 读取，并与仓库现实核对 |
| `docs/WORK_PLAN.local.md` | 当前任务的详细本地计划 | 只有 checkpoint 明确点名时读取；存档计划不得执行 |
| `docs/SOURCE_REFERENCES.md` | 本地外部参考树的用途和只读边界 | 只有外部证据门禁触发时读取 |
| 其他 `docs/*.local.md` | 任务研究、历史基线或 Harness 存档 | 只读取 checkpoint 点名的精确文件；禁止扫描后全部加载 |

`PROJECT_MAP.md` 只登记这些类别和加载边界。当前有哪些本地文件及其状态，由 `PROJECT_STATE.local.md` 维护；三个模块导航不得要求这些文件存在。

## 设计文档生命周期

新建或实质修改的 `docs/specs/` 设计在人工审查时必须在标题后标明一种状态：

- `approved`：已批准并可约束实现；
- `proposal`：等待决定，不授权实现；
- `superseded`：已被后续决定或契约取代；
- `historical`：只解释历史，不是当前规则。

设计状态不能代替决定、schema、Golden 批准或迁移门禁。尚未标记的既有设计继续保留其在正式文档、决定、冻结 fixture 或 Golden 登记中已有的 authority；本规则不降级或重新批准它们，下次实质修改时再分类。`project_docs` 只做正式文档卫生检查，不推断或强制迁移既有 spec 状态。状态变化时必须同步更新其指向的权威文档。
