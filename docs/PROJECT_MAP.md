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

`import-core`、`reconcile-core`、`reporting-core`、平台模块和客户端仍是[架构](ARCHITECTURE.md)中的目标职责，不是当前可构建模块。不存在的模块不得被文档或代码当作已经实现。

## 跨模块契约

| 主题 | 人类可读规则 | 机器工件 | 验证 |
| --- | --- | --- | --- |
| 产品和账务行为 | [产品需求](PRODUCT_REQUIREMENTS.md)、[账务规则](ACCOUNTING_RULES.md)、[决定](DECISIONS.md) | 无 | 相关模块测试和 Golden 场景 |
| Golden v2 | [Golden Schema](GOLDEN_SCHEMA.md)、[Golden 测试](GOLDEN_TESTS.md)、[v2 清单](GOLDEN_V2_INVENTORY.md) | [`schemas/`](../schemas)、[`golden/`](../golden) | [`tests/python`](../tests/python) |
| v1 到 v2 迁移 | [`docs/migrations/golden-v2`](migrations/golden-v2) | mapping、path-map、expected draft | mapping、contract 和 semantic tests |
| P4-02 共享导入 spine | [设计规格](specs/2026-08-13-p4-02-shared-import-spine-design.md)（approved） | `ledger-data` 的 20.sqm（v20→v21）、`Ledger.sq` 共享 `import_*` 表族 | migration verifier、`ImportSpineLifecycleEndToEndTest`、`ImportSpineMigrationCoexistenceTest` |
| P4-03 微信普通收支 | [设计规格](specs/2026-08-14-p4-03-wechat-ordinary-flows-design.md)（approved） | `ledger-application` jvm 源集的 `WechatBillParser`、`WechatSourceTokens`、`WechatParserTypes`（POI 仅 jvm 作用域） | `WechatBillParserJvmTest`、`ImportSpineWechatEndToEndTest` |
| 开发和发布验证 | [开发规范](CONTRIBUTING.md) | Gradle Wrapper、Python validator | focused tests、affected full suite、文档验证 |

`golden/rules/` 保存冻结输入或答案，`golden/rules-v2/` 只保存已进入正式 v2 发布边界的机器工件。`docs/migrations/golden-v2/` 保存迁移和审查材料；其中的 expected 只有在明确批准后才能成为发布工件。

## 依赖方向

```text
ledger-data --------+
                    +--> ledger-application --> ledger-domain
future adapters ----+
```

- `ledger-domain` 不依赖平台、持久化、网络或外部参考树。
- `ledger-application` 依赖领域类型并定义端口，不拥有数据库 schema。
- `ledger-data` 实现应用端口并持久化领域状态，不重新定义账务规则。
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
