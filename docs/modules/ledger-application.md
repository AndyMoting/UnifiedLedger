# `ledger-application` 模块导航

**状态：** 当前可构建模块。

本文件是[系统架构](../ARCHITECTURE.md)和当前源码、测试的导航投影，不独立拥有应用行为、Golden 契约或确认规则。出现冲突时回到对应 owner 文档和决定核验。

## 当前职责

`ledger-application` 编排共享用例、确认流程、请求身份、幂等结果和外部能力端口。它依赖 `ledger-domain`，但不拥有具体数据库 schema、平台 API 或客户端界面。

## 读取入口

| 目的 | 读取 |
| --- | --- |
| 核验用户操作和确认 | [产品需求](../PRODUCT_REQUIREMENTS.md)、相关[决定](../DECISIONS.md) |
| 核验模块边界 | [系统架构](../ARCHITECTURE.md) |
| 核验 Golden adapter | [Golden Schema](../GOLDEN_SCHEMA.md)、[Golden 测试](../GOLDEN_TESTS.md)、目标 mapping 和 validator |
| 查看当前实现 | [`src/commonMain`](../../ledger-application/src/commonMain) |
| 查看跨平台测试 | [`src/commonTest`](../../ledger-application/src/commonTest) |
| 查看 JVM adapter 测试 | [`src/jvmTest`](../../ledger-application/src/jvmTest) |

新增或修改用例、候选、确认、decoder、端口或请求语义时，先读取对应 owner 文档和测试；相邻 Golden、runtime、persistence 或 publication 阶段的完成状态不能自动授权该变化。

## 验证

完整命令由[开发规范的 Kotlin 验证](../CONTRIBUTING.md#kotlin-验证)维护；本模块对应 `ledger-application` JVM 测试入口。
