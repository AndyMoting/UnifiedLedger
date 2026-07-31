# `ledger-domain` 模块导航

**状态：** 当前可构建模块。

本文件是[系统架构](../ARCHITECTURE.md)和当前源码、测试的导航投影，不独立拥有模块边界或账务规则。出现冲突时回到架构、账务规则和相关决定核验。

## 当前职责

`ledger-domain` 承载精确金额、账户、正式交易、Posting、版本和纯领域不变量。它位于共享核心最内层，不依赖应用、持久化、平台、网络或本地外部参考树。

## 读取入口

| 目的 | 读取 |
| --- | --- |
| 核验账务行为 | [账务规则](../ACCOUNTING_RULES.md)、相关[决定](../DECISIONS.md) |
| 核验模块边界 | [系统架构](../ARCHITECTURE.md) |
| 查看当前实现 | [`src/commonMain`](../../ledger-domain/src/commonMain) |
| 查看当前验证 | [`src/commonTest`](../../ledger-domain/src/commonTest) |

修改金额语义、Posting 平衡、交易类型、版本替代、退款、reconciliation 所有权或正式状态恢复时，先读取对应 owner 文档；本导航中的摘要不能单独授权行为变化。

## 验证

完整命令由[开发规范的 Kotlin 验证](../CONTRIBUTING.md#kotlin-验证)维护；本模块对应 `ledger-domain` JVM 测试入口。
