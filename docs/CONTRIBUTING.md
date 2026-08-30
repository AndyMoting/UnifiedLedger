# 开发规范

## 当前环境

- Git
- Python 3.12
- PowerShell 7
- JDK 21
- Gradle Wrapper 9.5.0
- Kotlin Multiplatform 插件 2.4.10

所有命令从仓库根目录使用 PowerShell 7 执行。Gradle 命令统一使用仓库内的 Wrapper；首次联网运行会下载固定版本的 Gradle 分发包和依赖，缓存完备时可以追加 `--offline`。当前有 `ledger-domain`、`ledger-application`、`ledger-data` 与 `app-ui` 四个 library 模块，以及 `desktop-app` 与 `android-app` 两个组合根应用模块；`ledger-data` 与 `app-ui` 带 Android 编译目标。桌面应用运行命令（启动 P5-03 演示面 B 并打开本地测试账本）：

## 本机 Gradle 资源限制

当前 16 GB Windows 主机上的 Gradle/Kotlin 验证必须串行执行；不得并发运行 Gradle、Kotlin 编译或共享测试输出的任务。每次 Gradle 验证前后使用以下命令。该限制仅适用于本机资源控制，不改变 CI 验证语义。

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat <task> --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
```

将 `<task>` 替换为本节列出的单个 Gradle task；一次只运行一个命令。不要在同一主机上同时运行 `check` 与模块测试。

## Kotlin 验证

确认 Gradle 使用 JDK 21：

```powershell
.\gradlew.bat --version
```

运行 `ledger-domain` JVM 测试：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

运行 `ledger-application` JVM 测试：

```powershell
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

运行 `app-ui` 的共享 UI 纯 reducer/状态机测试（P5-03；不引入 compose ui-test harness）：

```powershell
.\gradlew.bat :app-ui:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

```powershell
.\gradlew.bat :desktop-app:run
```

运行 `desktop-app` JVM 测试（P5-03 回归：空库引导、一次手工支出 Created、UUIDv7 文本、逐币种平衡与重放 NoChange）：

```powershell
.\gradlew.bat :desktop-app:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

构建 `desktop-app` 模块（与 CI 的 Desktop app build 步骤一致）：

```powershell
.\gradlew.bat :desktop-app:build --stacktrace --rerun-tasks --warning-mode all
```

构建 `android-app` 调试 APK（P5-03 构建门；CI artifact 用于 Android 安装、启动与人工验收）：

```powershell
.\gradlew.bat :android-app:assembleDebug --stacktrace --rerun-tasks --warning-mode all
```

### Android APK 下载与人工安装

CI 在 `:android-app:assembleDebug` 后上传调试 APK 工件，名称为 `android-debug-apk-<sha>`（保留 7 天）。人工验收必须使用固定 SHA 对应的工件；CI 成功不构成 emulator 人工证据已完成。

下载固定 SHA 对应 APK（任选其一）：

```powershell
# 从对应提交的 CI 运行下载
gh run download --repo <owner>/UnifiedLedger --name "android-debug-apk-<sha>" --dir apk-download
# 或从 GitHub Actions artifact 页面手动下载
```

安装并核对（首次启动、创建/打开应用私有当前 schema 数据库、退出后同版本重开）：

```powershell
adb install -r apk-download\android-app-debug.apk
adb shell am start -n com.unifiedledger.android/.MainActivity
# 核对启动状态与空态；退出进程后再次启动核对同版本重开恢复正式结果
```

运行 `ledger-data` JVM 测试：

```powershell
.\gradlew.bat :ledger-data:jvmTest --stacktrace --rerun-tasks --warning-mode all
```

验证 SQLDelight migration：

```powershell
.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all
```

编译 `ledger-data` Android system SQLite driver 装配：

```powershell
.\gradlew.bat :ledger-data:compileAndroidMain --stacktrace --rerun-tasks --warning-mode all
```

运行当前全部 Gradle 检查：

```powershell
.\gradlew.bat check --rerun-tasks --warning-mode all
```

运行 ktlint 对全部模块的跟踪 Kotlin 源（.kt）与模块构建脚本检查（`desktop-app`/`android-app` 一并纳入；与 CI 的 Ktlint check 步骤一致）：

```powershell
.\gradlew.bat ktlintCheck --stacktrace --rerun-tasks --warning-mode all
```

## 完整 Python 测试

从仓库根目录执行：

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest discover -s tests -t . -v
```

## 文档验证

```powershell
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

## CI 配置

以上验证命令与 `.github/workflows/ci.yml` 的 CI 步骤保持一致。修改本地验证步骤时需同步更新 CI 配置；修改 CI 步骤时需同步更新本文档。

## 文档规则

- 正式文档以中文为主，代码类型、文件名、命令和 API 名称保留英文。
- `PROJECT_MAP.md` 只维护模块、文档、机器工件和验证入口之间的导航关系，不复制业务规则。
- `docs/modules/` 是 `ARCHITECTURE.md` 和当前源码/测试的导航投影，不独立拥有模块边界或业务规则；源码路径只作导航，不把易变类名和函数名写成长期契约。
- 新建或实质修改的 `docs/specs/` 设计在人工审查时必须标记为 `approved`、`proposal`、`superseded` 或 `historical`。尚未标记的既有设计保留其已在正式文档、决定或 Golden 登记中确认的 authority，并在下次实质修改时分类；`project_docs` 不负责推断或批量迁移该状态。
- 确认后的产品行为、账务规则和架构变化必须同步更新对应文档。
- `CURRENT_STATE.md` 只保留当前检查点、阻塞和唯一下一步。
- 文档不得包含本机绝对路径、个人账务数据或临时讨论记录。
- 需求、账务规则、架构、决定、黄金测试和当前状态各自只维护所属职责，避免复制整段内容。

## 分支

- `main` 始终保持测试通过。
- 新功能、底层模型、解析器、数据迁移、对账逻辑和跨文件迁移使用短期任务分支。
- 一个分支只对应一个明确目标，完成后删除本地和远端任务分支。
- 禁止强制推送或删除 `main`。

## 提交

- 一个提交只表达一个可独立理解的逻辑变化。
- 代码行为变化时，实现、测试和必要文档在同一工作项中更新。
- 在行为完整、适用测试通过且可以安全回退的稳定检查点提交；不按固定时间或文件数量机械提交。
- 提交信息采用 Conventional Commits 规范，标题与正文均使用英文。前缀为 `feat`、`fix`、`refactor`、`test`、`docs`、`chore`、`release`、`merge`、`ci`。标题简洁，关联决定编号时以括号附在末尾；例如 `fix: align RG-08 statistics fallback with RG-11/12 semantics (RG08-001, D-088)`。
- 不提交调试输出、半成品、真实账务数据或仅供本地工作的文件。

## 合并

- 重要代码变更通过 Pull Request 合入 `main`；说明包含目的、行为变化、验证结果和适用决定编号。
- 合并前同步最新 `main`，解决冲突，完成全量适用测试、文档检查、隐私检查和完整自审。
- 默认使用 merge commit，保留可独立理解的提交和分支边界。
- 只有提交确实琐碎且无法独立理解时才使用 squash merge；不使用 rebase merge 合入 `main`。
- 仓库支持时为 `main` 启用必要状态检查，并禁止强推和删除。

## 提交前检查

1. 运行聚焦测试和完整测试。
2. 运行正式文档验证。
3. 运行适用的编译、构建或静态检查。
4. 使用 `git diff --check` 检查空白错误，并复核暂存 diff 和工作树状态。
5. 确认没有个人数据、本机信息、外部实现、临时计划、会话内容或开发过程署名。
6. 推送前复核分支新增历史，确保提交信息和跟踪文件都符合隐私边界。

## 隐私与测试数据

- 测试默认使用完全匿名的合成数据。
- 本地真实来源、私人配置、来源哈希、账户映射和余额锚点不得进入 Git。
- 外部行为证据只能改写为中立规格和测试，不能复制受限实现。
- 正式代码不得依赖本机绝对路径、本地参考目录或私人配置。
- 日志、异常和测试失败输出不得泄露完整账单、账号、密钥或可识别交易。
