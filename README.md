# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

Python 实现仍是迁移、规则验证和黄金结果基线；仓库现已建立两个可构建的 Kotlin Multiplatform 共享库模块，均带 JVM 测试目标。`ledger-domain` 已实现精确最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、资产账户付款的 `RG-01` 普通支出创建与余额重放，以及只修改备注的版本替代。普通支出当前还会把非正金额、一级分类和结构有效但已停用的二级分类分别返回为 `AmountMustBePositive`、`SecondaryCategoryRequired` 与 `CategoryInactive`；其他目录、账户和跨账本错误仍使用通用领域失败。当前共 `26` 项领域测试。

`ledger-application` 目前只实现 `RG-01` 的最小共享应用边界：明确确认请求、请求身份与完整快照、只含身份的回执，以及原子提交端口契约。当前 `6` 项应用测试覆盖相同请求与完整输入重放时不变更、不同请求身份的独立创建、类型化身份冲突和类型化领域拒绝。回执不携带可回写的业务快照，因此重试不会将已替代的备注版本回退到初始版本。

这不是完整的 `RG-01` 或正式账务核心实现。应用层的原子提交目前只是端口契约，尚无数据库、schema、ORM、并发或持久化实现；报表、对账、导入、UI 和平台客户端运行时也均尚未实现。Android 与 Desktop 工程仍未建立，具体数据库、UI、导航、依赖注入和同步实现将在相应接口与验收边界稳定后选择。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 验证

Kotlin 构建需要 JDK 21。使用仓库内的 Gradle Wrapper 分别运行两个共享库模块的 JVM 测试：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

运行当前全部 Gradle 检查：

```powershell
.\gradlew.bat check --rerun-tasks --warning-mode all
```

依赖和 Gradle 分发包已在本机缓存时，可以为上述命令追加 `--offline`。`ledger-domain` 与 `ledger-application` 都是共享库模块；后者不是可运行的 app/client，当前尚无应用运行命令。

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
