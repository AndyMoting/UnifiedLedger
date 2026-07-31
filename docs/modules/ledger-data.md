# `ledger-data` 模块导航

**状态：** 当前可构建模块。

本文件是[系统架构](../ARCHITECTURE.md)和当前源码、测试的导航投影，不独立拥有持久化行为、迁移规则或账务语义。出现冲突时回到架构、适用迁移和相关决定核验。

## 当前职责

`ledger-data` 实现共享持久化端口、SQLDelight schema、原子写入、查询、迁移、重读和 Android system SQLite 装配。它依赖应用端口和领域类型，不反向决定账务规则。

## 读取入口

| 目的 | 读取 |
| --- | --- |
| 核验模块和技术边界 | [系统架构](../ARCHITECTURE.md)、相关[决定](../DECISIONS.md) |
| 核验开发与迁移门槛 | [开发规范](../CONTRIBUTING.md)、目标 migration 和测试 |
| 查看当前 Kotlin store | [`src/commonMain`](../../ledger-data/src/commonMain) |
| 查看 schema 与 migrations | [`src/commonMain/sqldelight`](../../ledger-data/src/commonMain/sqldelight) |
| 查看 JVM 验证 | [`src/jvmTest`](../../ledger-data/src/jvmTest) |

修改 schema、迁移、事务边界、恢复或平台数据库装配时，先核验来源/目标版本、失败与重读行为以及对应 owner 文档；本导航中的摘要不能替代迁移验收。

## 验证

完整命令由[开发规范的 Kotlin 验证](../CONTRIBUTING.md#kotlin-验证)维护；本模块对应 `ledger-data` JVM 测试、SQLDelight migration 验证和 Android driver 装配编译入口。
