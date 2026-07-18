# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

Python 实现仍是迁移、规则验证和黄金结果基线；仓库现已建立首个可构建的 Kotlin Multiplatform 模块 `ledger-domain`。该模块目前是带 JVM 测试目标的纯共享领域库，已实现精确最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、资产账户付款的 `RG-01` 普通支出创建，以及当前分录余额重放这一有限切片。

这不是完整的正式账务核心；持久化、报表、对账、导入、UI 和平台客户端运行时均尚未实现。Android 与 Desktop 工程仍未建立，具体数据库、UI、导航、依赖注入和同步实现将在相应接口与验收边界稳定后选择。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 验证

Kotlin 构建需要 JDK 21。使用仓库内的 Gradle Wrapper 运行 `ledger-domain` JVM 测试：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

运行当前全部 Gradle 检查：

```powershell
.\gradlew.bat check --rerun-tasks --warning-mode all
```

依赖和 Gradle 分发包已在本机缓存时，可以为上述命令追加 `--offline`。`ledger-domain` 是库模块，当前没有应用模块，因此尚无应用运行命令。

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

- [项目章程](docs/PROJECT_CHARTER.md)
- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [账务规则](docs/ACCOUNTING_RULES.md)
- [系统架构](docs/ARCHITECTURE.md)
- [决定记录](docs/DECISIONS.md)
- [路线图](docs/ROADMAP.md)
- [当前状态](docs/CURRENT_STATE.md)
- [黄金测试](docs/GOLDEN_TESTS.md)
- [开发规范](docs/CONTRIBUTING.md)
