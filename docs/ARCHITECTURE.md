# 系统架构

## 当前状态

本文件定义目标模块边界和依赖约束。仓库当前包含 `ledger-domain`、`ledger-application` 与 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块。它们已承载 RG-01 至 RG-05 当前实现的 runtime 范围，包括精确金额、平衡分录与版本替代，明确确认、严格 raw JSON 与 request identity，以及 SQLDelight 原子持久化、import/candidate/evidence ownership 和分录级 reconciliation。RG-05 的领域、应用与持久化 runtime 及其 schema/migration 支持已经进入共享库。

`ledger-data` 使用 SQLDelight `2.3.2`，当前 schema 为 v8；迁移链、fresh/migrated schema、一致性约束与 Android system SQLite 装配均有验证。RG-01 的 `note_update` 已实现 replacement、replay、request identity conflict 与 stale CAS 零写入；RG-03 已完成当前冻结 20 operations 的完整状态比较；RG-04 的 26 operations 均有 runtime，但只有 18 个 manual operations 做精确 projection 比较，26 项整体仅比较状态计数和部分 returned IDs。RG-05 已有领域、应用与持久化 runtime，25 operations 已逐项比较 outcome、rejection reason/field、新增实体 ID 与 returned IDs，确定性 identity 与契约冻结值一致；尚无 RG-03 等级的完整 state/deltas/status-changes 比较；expected 仍为 `draft_for_review`，独立 expected/runtime 审查、明确用户批准和后续单独 publication 授权尚未完成。

上述范围仍不代表全部黄金契约或正式账务核心已经完成。RG-01、RG-02 和 RG-04 仍缺各自声明的完整 state/report/reconciliation/delta 比较；RG-05 的 expected 与验证闸门仍未关闭；RG-06 至 RG-12 尚无 Kotlin runtime。下表中除现有三个模块之外的模块仍是后续实现必须遵守的逻辑职责，仓库尚未包含对应构建模块；Android 与 Desktop app 也尚未建立，因此没有应用运行命令。`ledger-application` 在此指共享 library，`ledger-data` 的 Android target 也不是可运行的 app/client。

## 架构原则

- 账务核心不依赖 Android、Desktop UI、网络、同步、AI 或本地外部参考树。
- Android 与 Desktop 共享完整业务核心，不各自实现账务规则。
- 业务规则通过端口访问持久化、文件和平台能力。
- 解析器、匹配器、约束求解器和未来智能能力只生成候选，不直接写入正式账本。
- 正式交易只能通过应用层的确认用例创建或替换。
- 平台模块负责权限和系统集成，界面层不能修补账务规则。
- 数据迁移和版本替换必须保留来源、审计历史及失败恢复能力。

## 目标模块边界

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| `ledger-domain` | 精确金额、账户、交易、分录、版本、证据值对象和纯业务不变量 | 持久化、平台 API、界面状态 |
| `ledger-application` | 用例、事务边界、确认流程、权限无关的应用服务和端口 | 具体数据库、文件选择器、窗口或权限 |
| `ledger-data` | 共享持久化实现、查询、原子写入、schema 迁移和审计历史存储 | 决定账务规则或绕过应用用例 |
| `import-core` | `SourceRecord` 标准化、解析结果、来源哈希、去重和导入候选 | 自动确认正式交易 |
| `reconcile-core` | 分录匹配、证据引用、差异、置信度和对账候选 | 修改余额或伪造补平交易 |
| `reporting-core` | 有效分录重放、余额、收支、消费和其他确定性报表计算 | 保存独立于正式账本的报表事实 |
| `platform-android` | Android 权限、采集、通知、文件和系统入口 | 账务规则与正式数据写入策略 |
| `platform-desktop` | 桌面文件、拖放、窗口和系统入口 | 账务规则与正式数据写入策略 |
| `android-app` | Android 组合根、界面和平台依赖装配 | 复制共享核心逻辑 |
| `desktop-app` | Desktop 组合根、界面和平台依赖装配 | 复制共享核心逻辑 |

## 依赖方向

```text
android-app  -> platform-android  --+
                                     |
desktop-app  -> platform-desktop  --+--> ledger-application --> ledger-domain
                                     |           ^                 ^
                                     +-----------|-----------------+
                                                 |
ledger-data -----+-------------------------------+
import-core -----+
reconcile-core --+
reporting-core --+
```

- `ledger-domain` 位于最内层，不依赖其他目标模块。
- `ledger-application` 依赖领域类型，并定义外部能力端口。
- 数据、导入、对账和报表模块依赖领域或应用契约，不能反向成为核心依赖。
- 平台模块实现系统能力端口，应用组合根负责装配。
- 客户端 UI 只能调用应用用例，不直接写数据库或构造绕过不变量的正式分录。
- 模块之间交换稳定 ID、精确金额和带时区的时间，不通过显示名称或备注建立业务关系。

## 正式数据流

### 手工入口

```text
手工输入 -> 录入候选 -> 用户确认 -> Transaction / Posting
                                      |-> 余额与报表
                                      +-> 对账记录
```

手工输入没有外部来源文件时仍可形成用户确认事实，并保留创建与后续修正历史。

### 导入与自动入口

```text
来源文件或采集事实
  -> SourceRecord
  -> 标准化与去重
  -> 解析、匹配或约束候选
  -> 用户确认
  -> Transaction / Posting
       |-> 余额与报表
       +-> 独立对账记录 <-> 外部证据
```

每一层保留前一层引用。候选必须带来源、规则、置信度和待确认字段；重复来源可以补充证据，但不能再次影响余额。

余额与报表从有效分录确定性计算。对账独立关联真实账户分录与证据，只描述核验状态，不回写金额或余额。

## 候选与确认边界

解析器、去重器、匹配器、约束求解器和未来 AI 均属于候选生成能力。它们可以：

- 提取来源字段；
- 识别重复或镜像记录；
- 建议账户、分类和交易关系；
- 给出差异原因和处理候选。

它们不能直接：

- 创建、修改或删除正式交易；
- 无依据补平余额或对账差异；
- 将推断写成来源事实；
- 覆盖用户已经确认的版本历史。

确定性规则只有在用户对该来源或规则明确开启自动确认后，才能通过与人工确认相同的应用用例形成正式账目。

## Python 的位置

Python 只用于旧账迁移、规则原型、来源解析实验和黄金结果基线。个人配置与真实来源场景保持在本地；生产客户端不能调用个人脚本或依赖本地参考目录。Python 中确认过的通用行为已经开始通过匿名黄金测试逐项迁移到 `ledger-domain`，而不是整套脚本直接嵌入客户端。

## 技术选择状态

| 事项 | 状态 | 当前结论或决定门槛 |
| --- | --- | --- |
| 共享核心语言与平台 | 已确定 | Kotlin Multiplatform 共享 Android 与 Desktop 的完整业务核心 |
| 客户端策略 | 已确定 | Android 与 Desktop 保持最小可运行、可持续构建的外壳 |
| 平台边界 | 已确定 | 业务核心共享，系统能力和 UI 平台独立 |
| Python | 已确定 | 仅用于迁移、规则原型和黄金结果基线 |
| 运行方式 | 已确定 | 本地优先；同步与 AI 默认关闭且不影响核心验收 |
| 当前正式持久化边界的数据库与迁移 | 已确定 | `ledger-data` 使用 SQLDelight `2.3.2`；Android 只使用 system SQLite driver；当前 schema v8 及其迁移链均经过验证。该选择不预先决定报表、同步或更广泛查询的存储方案 |
| UI 与导航库 | 暂缓决定 | Android 与 Desktop 的最小工作流、可访问性和预览需求明确后选择 |
| 依赖注入方案 | 暂缓决定 | 模块构造关系和测试替身需求稳定后选择 |
| RG-01 Golden JSON decoding | 已确定 | `ledger-application/commonMain` 使用 `kotlinx-serialization-json 1.11.0` runtime-only；不启用 serialization compiler plugin，不引入 Ktor；严格 duplicate/unknown/type/resource guard 位于 adapter 边界 |
| 网络库 | 暂缓决定 | 第一个可选网络边界及其安全、离线和替换要求确认后选择 |
| 同步实现 | 暂缓决定 | 本地闭环、版本语义、冲突策略、加密和恢复要求通过验收后选择 |

暂缓决定不列未经比较的候选清单。每项选择必须说明适用模块、许可证、升级与替换成本，并用最小验证证明满足对应门槛后，才能改为“已确定”。
