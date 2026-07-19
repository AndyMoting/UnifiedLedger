# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 当前阶段

Python 实现仍是迁移、规则验证和黄金结果基线；仓库现已建立 `ledger-domain`、`ledger-application` 和 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块。`ledger-domain` 已实现精确最小货币单位、稳定 ID 与目录、逐币种平衡分录集、正式交易当前版本链、资产账户付款的 `RG-01` 普通支出创建与余额重放，以及只修改备注的版本替代。普通支出当前还会把非正金额、一级分类和结构有效但已停用的二级分类分别返回为 `AmountMustBePositive`、`SecondaryCategoryRequired` 与 `CategoryInactive`；其他目录、账户和跨账本错误仍使用通用领域失败。当前共 `26` 项领域测试。

`ledger-application` 目前只实现 `RG-01` 的最小共享应用边界：明确确认请求、请求身份与完整快照、只含身份的回执、原子提交端口契约，以及稀疏保存输入 wrapper。wrapper 只允许金额、分类和付款账户为空；缺失项返回无序的语义类型集合且不调用确认用例，完整输入和已提供的零金额继续委托既有确认与领域验证。当前共 `12` 项应用测试。回执不携带可回写的业务快照，因此重试不会将已替代的备注版本回退到初始版本。

`ledger-data` 使用 SQLDelight `2.3.2` 实现该端口的最小正式持久化边界：事务内原子 claim、等价重放与身份冲突；领域拒绝显式释放 claim，callback 异常或后续 SQL 失败回滚事务，所有路径均不留下部分正式状态。正式交易版本和整数最小货币单位分录受关系完整性约束。当前 schema 版本为 `2`，从 schema-only v1 的迁移同时经过 SQLDelight 官方验证和 JVM 测试；Android 装配只使用系统 SQLite driver，不包含 bundled SQLite。当前共 `15` 项 data 测试。

这仍不是完整的 `RG-01` 或正式账务核心实现。当前尚未实现 JSON `field_path`/`reason_code` 映射、原始十进制字符串解析或序列化 adapter；报表、导入、对账、UI 和平台客户端运行时也均尚未实现。仓库已有 `ledger-data` 的 Android 编译目标，但 Android app 与 Desktop app 仍未建立；当前数据库选择只适用于上述正式持久化边界，UI、导航、依赖注入、同步及更广泛查询方案仍需在各自验收边界明确后决定。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 验证

Kotlin 构建需要 JDK 21。使用仓库内的 Gradle Wrapper 分别运行三个共享库模块的 JVM 测试：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :ledger-data:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

验证 SQLDelight v1 到 v2 迁移：

```powershell
.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all
```

编译 Android system SQLite driver 装配：

```powershell
.\gradlew.bat :ledger-data:compileAndroidMain --stacktrace --rerun-tasks --warning-mode all
```

运行当前全部 Gradle 检查：

```powershell
.\gradlew.bat check --rerun-tasks --warning-mode all
```

依赖和 Gradle 分发包已在本机缓存时，可以为上述命令追加 `--offline`。三个模块都是 library；仓库当前没有可运行的 Android 或 Desktop app/client，因此没有应用运行命令。

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
