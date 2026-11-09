# UnifiedLedger

UnifiedLedger 是一个 Android-first、local-first 的个人财务应用，将日常记账、账单导入和对账审计建立在同一份正式账本之上。

## 核心原则

- 正式账本采用 `Account -> Transaction -> Posting` 模型。
- 金额使用精确十进制或整数最小货币单位，不使用二进制浮点数。
- 来源事实、推断候选和正式账目相互分离。
- 解析、匹配和未来的 AI 能力只能提出带来源与置信度的候选。
- 本地账本是事实来源；网络、同步和 AI 默认不是核心功能的前置条件。
- 修正保留历史版本，退款和真实冲回作为独立经济事件记录。

## 当前阶段

阶段 1～7 已收口（阶段 7 于 2026-11-05 按 `D-296` 收口，验收向量 59/62 ≈ 95.2%）。当前在**阶段 8「Android 功能与界面深化」**：`P8-00` 界面与导航基线已按 `D-301` 批准，验收向量进行中。

- 阶段划分、进入与完成条件：[路线图](docs/ROADMAP.md)
- 正式交付事实、剩余门槛与唯一下一步：[当前状态](docs/CURRENT_STATE.md)
- 已裁决事项与依据：[决定记录](docs/DECISIONS.md)

## 仓库结构

| 模块 | 职责 |
| --- | --- |
| `ledger-domain` | 账务领域模型、规则与不变量；不依赖 Android、网络或 AI |
| `ledger-application` | 用例、端口、读模型与导入/对账编排 |
| `ledger-data` | SQLDelight schema v36（v1→v36 共 35 个迁移边）与持久化适配 |
| `app-ui` | 共享 Compose Multiplatform 界面与纯状态机 |
| `android-app`、`desktop-app` | 两端组合根 |
| `tools/python` | 迁移、规则验证与黄金结果基线 |

## 黄金契约

`golden/rules-v2/` 已发布 `RG-01`～`RG-12` 共 12 个 case：每个 case 有冻结 expected 工件，并有对 operation outcome、完整 state、deltas 与 status changes 的比较 oracle。逐 case 的对象计数与覆盖状态见[黄金 v2 清单](docs/GOLDEN_V2_INVENTORY.md)，测试入口与失败矩阵见[黄金测试](docs/GOLDEN_TESTS.md)。

已发布的 case 集合不表示所有黄金场景或正式账务核心已完成；未完成的验收向量按 [当前状态](docs/CURRENT_STATE.md) 的「未完成门槛」逐条保留。

## 验证

Kotlin 构建需要 JDK 21。验证采用 PR 门禁流：本机只做快速反馈，聚合门禁由 CI 在资源更充足的 runner 上执行；分支、提交、合并与隐私要求见[开发规范](docs/CONTRIBUTING.md)。

### 本机（快速反馈）

正式文档校验，必须 exit 0：

```powershell
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

推送前可选的 trace 预检：`bash tools/ci/trace-scan.sh`。Kotlin 测试与编译归 CI，本机只在诊断红 CI 时按需复跑，命令见[贡献指南](docs/CONTRIBUTING.md)。

### 聚合门禁（CI）

`.github/workflows/ci.yml` 的四个 required checks：`Kotlin tests`、`Android compile`、`Python tests`、`Trace scan`。完整 `check`、ktlint、Android/KMP 编译、Debug APK、完整 Python 测试、Desktop build、migration verifier 与 Windows JVM 测试（夜跑，非阻塞）均由 CI 承担。

发布证据 = 该 PR 运行上的四个必过检查 + 合并守卫对「落地树 == 被测树」的核对；证据齐备时合并后不再重跑，机制见[开发规范](docs/CONTRIBUTING.md)的「合并」章节。

### 本机执行约束

需要本地复现时，Gradle/Kotlin 验证必须串行运行，使用单 worker 和 1 GB heap，并在每次运行结束后停止 Gradle daemon。具体命令见[开发规范](docs/CONTRIBUTING.md)的「PowerShell 执行规范」。

### 构建与运行（人工验收）

`desktop-app` 运行共享演示面：

```powershell
.\gradlew.bat :desktop-app:run
```

`android-app` 构建调试 APK（CI artifact 用于 Android 安装与人工验收）：

```powershell
.\gradlew.bat :android-app:assembleDebug --stacktrace --rerun-tasks --warning-mode all
```

## 正式文档

- [项目地图](docs/PROJECT_MAP.md)
- [项目章程](docs/PROJECT_CHARTER.md)
- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [账务规则](docs/ACCOUNTING_RULES.md)
- [系统架构](docs/ARCHITECTURE.md)
- [决定记录](docs/DECISIONS.md)
- [路线图](docs/ROADMAP.md)
- [当前状态](docs/CURRENT_STATE.md)
- [黄金测试](docs/GOLDEN_TESTS.md)
- [开发规范](docs/CONTRIBUTING.md)
- [领域模块](docs/modules/ledger-domain.md)、[应用模块](docs/modules/ledger-application.md)、[数据模块](docs/modules/ledger-data.md)
