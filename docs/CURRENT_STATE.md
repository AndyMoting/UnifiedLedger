# 当前状态

## 已完成

- `RG-01` 至 `RG-12` 的正式设计、版本化机器答案和一致性验证已进入当前基线。
- Python 继续作为迁移、规则验证和黄金结果基线；完整 Python 测试共 `379` 项通过，正式文档验证通过。
- 建立三个可构建的 Kotlin Multiplatform 共享库模块 `ledger-domain`、`ledger-application` 与 `ledger-data`。
- `ledger-domain` 完成有限的 `RG-01` 资产付款创建与余额重放切片：精确 `Long` 最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、两条支出分录和当前分录余额重放；只修改备注的版本替代保留原分录集与经济时间；非正金额、一级分类和结构有效但已停用的二级分类分别返回 `AmountMustBePositive`、`SecondaryCategoryRequired` 与 `CategoryInactive`，其他目录、账户和跨账本错误仍保持通用领域失败。
- `ledger-application` 完成明确确认的最小请求边界：相同请求与完整输入重放不变更，不同请求身份独立创建，身份冲突和领域拒绝返回类型化结果；只含身份的回执不会在重试时回退备注替代版本。稀疏保存输入 wrapper 只允许金额、分类和付款账户为空，缺失项返回无序的语义类型集合并保证确认用例、ID source、transaction factory 与 commit port 零调用；完整输入和已提供的零金额继续委托既有确认用例。
- `ledger-application` 已实现 `RG-01` typed decoded-field Golden adapter：保留 omitted/null/value，精确解析十进制字符串和 case timezone，直接验证 sparse attempted rejection，并把既有应用结果投影为冻结的 `field_path`/`reason_code`。
- `ledger-application` 已使用 `kotlinx-serialization-json 1.11.0` runtime-only 实现严格 RG-01 v1 raw JSON decoder；tree 映射前拒绝重复对象名，受支持对象执行 closed-key 与类型校验，非字符串金额不进入精确金额 parser，并以 1 MiB UTF-8 与 64 层嵌套上限返回类型化资源拒绝。
- `ledger-data` 使用 SQLDelight `2.3.2` 实现 `RG-01` commit port：数据库事务中的请求 claim 保证并发等价请求只创建一次，重放返回原回执，变更快照返回冲突，领域拒绝、callback 异常和持久化异常均不留下部分状态；正式交易、当前版本关系、posting set 和整数金额分录以同账本复合约束保存。
- tracked v1 `golden/rules/rg-01.json` 的 create、retry 与 distinct re-entry 已经 decoder、typed adapter、application use case 和 SQLDelight port 端到端验证；7 个 invalid outcomes 在 typed adapter 前置拒绝，严格 application 与 commit port 调用计数不增加，数据库保持不变。approved v2 operations 只以结构化 JSON 解析作为结果 oracle。
- `ledger-data` schema 当前为 v2；schema-only v1 快照、v1 到 v2 迁移、fresh/migrated 元数据一致性和非法关系拒绝均已验证。Android target 使用 system SQLite driver，并由可关闭 handle 持有数据库与 driver；生产依赖不包含 bundled SQLite。

## 当前检查点

`ledger-domain` 当前 `26` 项 Kotlin 测试通过，`ledger-application` 当前 `38` 项 Kotlin 测试通过，`ledger-data` 当前 `16` 项 Kotlin 测试通过；完整 Python 测试 `379` 项及正式文档验证通过。当前实现只覆盖上述有限切片，不代表完整 `RG-01` 黄金契约或正式账务核心已经完成。`note_update` 和完整 state/report/reconciliation/delta comparison 仍未覆盖，报表、对账、导入、UI 和平台客户端运行时也均尚未实现。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- 三个 KMP library 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行；`ledger-data` 的 SQLDelight 迁移验证与 Android target 编译也可独立运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标，但 Android app 与 Desktop app 尚未建立，因此没有应用运行命令。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；UI、导航、依赖注入、同步和更广泛查询方案尚未选择。

## 阻塞

当前没有已知阻塞。

## 后续门槛

完整 state/report/reconciliation/delta comparison 与 `note_update` 仍需要单独验收边界。当前 RG-01 decoder 授权不开放 v1 fixture rewrite、migration publication、其他 RG adapter 或网络序列化方案。
