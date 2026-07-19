# 当前状态

## 已完成

- `RG-01` 至 `RG-12` 的正式设计、版本化机器答案和一致性验证已进入当前基线。
- Python 继续作为迁移、规则验证和黄金结果基线；完整 Python 测试共 `379` 项通过，正式文档验证通过。
- 建立两个可构建的 Kotlin Multiplatform 共享库模块 `ledger-domain` 与 `ledger-application`，均使用 `commonMain` 和 JVM 测试目标。
- `ledger-domain` 完成有限的 `RG-01` 资产付款创建与余额重放切片：精确 `Long` 最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、两条支出分录和当前分录余额重放；只修改备注的版本替代保留原分录集与经济时间。
- `ledger-application` 完成明确确认的最小请求边界：相同请求与完整输入重放不变更，不同请求身份独立创建，身份冲突和领域拒绝返回类型化结果；只含身份的回执不会在重试时回退备注替代版本。

## 当前检查点

`ledger-domain` 当前 `21` 项 Kotlin 测试通过，`ledger-application` 当前 `6` 项 Kotlin 测试通过；完整 Python 测试 `379` 项及正式文档验证通过。当前实现只覆盖上述有限切片，不代表完整 `RG-01` 黄金契约或正式账务核心已经完成。应用层原子提交只是端口契约，尚无数据库、schema、ORM、并发或持久化实现；报表、对账、导入、UI 和平台客户端运行时也均尚未实现。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- `ledger-domain` 与 `ledger-application` 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- Android 与 Desktop 工程尚未建立，因此没有应用运行命令。
- 具体数据库、UI、导航、依赖注入和同步库尚未选择。

## 阻塞

当前没有已知阻塞。

## 唯一下一步

以测试优先方式实施类型化领域验证 `AmountMustBePositive`、`SecondaryCategoryRequired` 与 `CategoryInactive`；完成该领域切片后，再实施应用边界的原始稀疏输入验证。
