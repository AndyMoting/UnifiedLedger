# 当前状态

## 已完成

- `RG-01` 至 `RG-12` 的正式设计、版本化机器答案和一致性验证已进入当前基线。
- Python 继续作为迁移、规则验证和黄金结果基线；完整 Python 测试共 `379` 项通过，正式文档验证通过。
- 建立两个可构建的 Kotlin Multiplatform 共享库模块 `ledger-domain` 与 `ledger-application`，均使用 `commonMain` 和 JVM 测试目标。
- `ledger-domain` 完成有限的 `RG-01` 资产付款创建与余额重放切片：精确 `Long` 最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、两条支出分录和当前分录余额重放；只修改备注的版本替代保留原分录集与经济时间；非正金额、一级分类和结构有效但已停用的二级分类分别返回 `AmountMustBePositive`、`SecondaryCategoryRequired` 与 `CategoryInactive`，其他目录、账户和跨账本错误仍保持通用领域失败。
- `ledger-application` 完成明确确认的最小请求边界：相同请求与完整输入重放不变更，不同请求身份独立创建，身份冲突和领域拒绝返回类型化结果；只含身份的回执不会在重试时回退备注替代版本。稀疏保存输入 wrapper 只允许金额、分类和付款账户为空，缺失项返回无序的语义类型集合并保证确认用例、ID source、transaction factory 与 commit port 零调用；完整输入和已提供的零金额继续委托既有确认用例。

## 当前检查点

`ledger-domain` 当前 `26` 项 Kotlin 测试通过，`ledger-application` 当前 `12` 项 Kotlin 测试通过；完整 Python 测试 `379` 项及正式文档验证通过。当前实现只覆盖上述有限切片，不代表完整 `RG-01` 黄金契约或正式账务核心已经完成。JSON `field_path`/`reason_code` 映射、原始十进制字符串解析和序列化 adapter 尚未实现；应用层原子提交只是端口契约，尚无数据库、schema、ORM、并发或持久化实现。报表、对账、导入、UI 和平台客户端运行时也均尚未实现。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- `ledger-domain` 与 `ledger-application` 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- Android 与 Desktop 工程尚未建立，因此没有应用运行命令。
- 具体数据库、UI、导航、依赖注入和同步库尚未选择。

## 阻塞

当前没有已知阻塞。

## 唯一下一步

对明确确认手工支出的持久化边界开展只读研究：收敛原子 commit port 的可复用 contract tests、失败与并发语义、最小查询需求，以及 schema 和生产技术的决定门槛。该研究仍不选择数据库或 ORM，不直接实现 adapter，也不开始公开用户界面或 Golden JSON adapter。
