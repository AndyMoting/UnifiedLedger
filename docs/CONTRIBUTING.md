# 开发规范

## 当前环境

- Git
- Python 3.12
- PowerShell 7
- JDK 21
- Gradle Wrapper 9.5.0
- Kotlin Multiplatform 插件 2.4.20
- Compose Multiplatform 1.12.0
- Android Gradle Plugin 9.3.1

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

## 本机与 CI 的验证分工

默认工作流是 PR 门禁流：在短期任务分支上开发，推送后创建 Pull Request（`gh pr create`），required checks（`.github/workflows/ci.yml` 的 `Kotlin tests`、`Android compile`、`Python tests`、`Trace scan` 四个 job）全部通过后以 merge commit 合并（`gh pr merge --merge`）。job name 与分支保护规则强耦合，改名必须同步更新分支保护配置。`ci.yml` 只在 `pull_request` 上触发（另留 `workflow_dispatch` 供人工重跑），**合并动作本身不再产生第二遍全量运行**。

本机只保留快速反馈：受影响模块的聚焦定向测试、`project_docs`，以及可选的 `bash tools/ci/trace-scan.sh` 本地预检。完整 `check`、ktlint、Android/KMP 编译、Debug APK、完整 Python 测试、Desktop build、migration verifier 与 Windows JVM 测试由 CI 在资源更充足的 runner 上执行。PR run 检出并验证的是该 PR 的**预览合并树**（`Merge <head> into <base>`），因此发布证据是「合并树与该 PR tip 树逐字节相同」，合并后立即核对：

```bash
git rev-parse "<merge-sha>^{tree}" "<pr-tip-sha>^{tree}"
```

两个哈希相同即为等价证据；不同（合并引入了 PR tip 之外的内容，例如冲突解决或基点过期）必须重跑全量：

```bash
gh workflow run ci.yml --ref main
```

只有在变更范围或失败诊断需要时，才在本机重复相应的资源密集型命令。

修复轮纪律：每轮修改完成后一次性提交并推送。PR 的 concurrency 会在新一轮推送时取消旧 run，只有末次推送的 run 会跑完并产出完整的绿色检查；不要在旧 run 上等待结论。

## Trace scan

`tools/ci/trace-scan.sh` 是 harness `verify-project.ps1` trace scope 在产品仓内的 CI 等价实现（harness 脚本位于本地未跟踪的 unifiedledger-harness 技能目录，CI 无法读取）。它依次执行四项扫描：当前 tracked 文件内容、全部历史提交消息、全部历史路径（对照 agent 路径正则）、全部分批历史树内容（每批 200 commit，规避 Windows 32K argv 长度限制，Linux 保持同一分批语义）。CI 的 `Trace scan` job 与本机 Git Bash 使用同一命令：

```bash
bash tools/ci/trace-scan.sh
```

同步义务：脚本内的 `TRACE_PATTERN` 与 agent 路径正则必须与 harness 技能的 `verify-project.ps1` trace scope 逐字一致；任一侧修改必须在同一变更中同步另一侧。豁免路径通过 `ALLOWED_TRACE_PATH` 环境变量注入（冒号或换行分隔的仓库相对路径），CI 默认为空；脚本会自动豁免其自身路径（其内容逐字嵌入 pattern 常量，必然命中内容扫描），提交消息命中不接受任何豁免。

## Windows JVM tests

`.github/workflows/windows.yml` 在 `windows-latest` 上运行全部 JVM 测试任务（`:ledger-data:jvmTest`、`:desktop-app:jvmTest`、`:app-ui:jvmTest`、`:ledger-application:jvmTest`、`:ledger-domain:jvmTest`、`:android-app:testDebugUnitTest`）。该 workflow 不进入 required checks，属于非阻塞平台信号。

触发条件：`pull_request`（仅当改动涉及 `ledger-data/**`、`desktop-app/**`、`build.gradle.kts`、`settings.gradle.kts`、`gradle/**`，对应 r32 类平台缺陷的历史波及路径）、每日 UTC 18:00（北京时间 02:00）对默认分支的定时运行，以及手动触发。运行全部 JVM 测试而非 `check` 的理由：ktlint、打包与 migration 校验平台中立且 ubuntu CI 已覆盖；Windows 特有风险面是 xerial 文件锁、临时目录删除与 `ATOMIC_MOVE` 语义，分布在 `ledger-data`（94 类）与 `desktop-app`（14 类）中。

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

运行 `android-app` JVM 单元测试（P5-04.4 S2/S3 新增：`AndroidStartupController` fail-closed 与重试资源安全，`src/test`，不引 Robolectric；与 CI 的 Android app unit tests 步骤一致）：

```powershell
.\gradlew.bat :android-app:testDebugUnitTest --stacktrace --rerun-tasks --warning-mode all
```

编译 `android-app` 的 androidTest 源（P0 hotfix 缺陷 1 回归守卫：编译 `AndroidAbsoluteDatabasePathInstrumentedTest`，使驱动绝对路径守卫不会在 CI 中腐化；执行仍是人工 emulator 门禁，CI 不运行 connectedAndroidTest；与 CI 的 Android app instrumented test sources compile 步骤一致）：

```powershell
.\gradlew.bat :android-app:assembleDebugAndroidTest --stacktrace --rerun-tasks --warning-mode all
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

CI 专属并行与分片：`.github/workflows/ci.yml` 把 Kotlin 验证拆成四个 job——`Kotlin tests core` 承担除 `:ledger-data:jvmTest` 之外的全部检查，`ledger-data shard 1/2/3` 各自承担该任务三分之一的测试类（每台 4 个 fork），收口 job **`Kotlin tests`** 等前两者全部结束（`if: always()`）后按结果放行。分支保护要求 `Kotlin tests` 这个名字，因此**收口 job 不得改名、不得被跳过**（被跳过会让该检查永远停留在等待状态）。分片名单见 `tools/ci/ledger-data-shards.txt`（每行 `shard<TAB>完整类名`，按实测耗时装箱），需要时用 `python tools/ci/make-ledger-data-shards.py --results ledger-data/build/test-results/jvmTest --out tools/ci/ledger-data-shards.txt` 重新生成；测试类增删或改名的批次必须重新生成。两道覆盖守卫保证名单不脱节：主 job 以 `--classes` 核对「编译出的测试类 == 名单」，每个分片以 `--shard/--results` 核对「实际执行出结果的类 == 该片名单」（脚本 `tools/ci/verify-shard-coverage.py`，任一不符即失败）。

并行与缓存：`tools/ci/ci-parallel.init.gradle.kts` 为全部 `Test` 任务设置 `maxParallelForks`（主 job 与分片均为 4，由 `CI_TEST_MAX_PARALLEL_FORKS` 控制），只由 CI workflow 显式传入——本机命令不受影响，「本机 Gradle 资源限制」的串行要求保持不变。各 job 以 `--max-workers`、`--parallel`、`--build-cache` 运行，并用独立缓存步骤持久化 Gradle 用户缓存目录下的 `caches/build-cache-1`：key 由 job 名、矩阵下标与运行号组成（每个 job 各存一条，避免并发 job 争抢同一条缓存而保存失败），`restore-keys` 前缀用于复用最新一条；缓存作用域按 ref，同一 PR 的后续运行（修复轮）可复用，首次运行不会命中。**`Test` 任务被显式排除在构建缓存之外**（init 脚本里的 `outputs.cacheIf { false }`）：实测同一提交重跑时出现过 `> Task :ledger-data:jvmTest FROM-CACHE`、整个分片 42 秒结束而测试并未执行——缓存只允许服务编译与分析任务，必过检查必须意味着「测试本次真的执行并通过」。每个 job 末尾的 `Test timing report` 步骤把逐类耗时与 runner 的 CPU/内存写入 job summary；该步骤只报告，不改变任何检查结论。

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

- 所有 tracked 变更通过 Pull Request 合入 `main`：任务分支推送后创建 PR，说明包含目的、行为变化、验证结果和适用决定编号。
- 分支保护要求 required checks（`Kotlin tests`、`Android compile`、`Python tests`、`Trace scan`）全部通过且分支为最新（strict up-to-date）；ci.yml job name 改名时必须同步更新分支保护配置。
- 合并前同步最新 `main`，解决冲突；本机按「本机与 CI 的验证分工」执行快速反馈检查，聚合检查由 PR 的那一次 run 承担。
- 合并后立即按同一节核对「合并树 == PR tip 树」；不一致时用 `gh workflow run ci.yml --ref main` 重跑全量，再继续后续工作。
- 默认使用 merge commit（`gh pr merge --merge`），保留可独立理解的提交和分支边界。
- 只有提交确实琐碎且无法独立理解时才使用 squash merge；不使用 rebase merge 合入 `main`。
- 禁止强推或删除 `main`。

## 提交前检查

1. 运行受影响模块的聚焦测试（聚合检查、ktlint、迁移校验与 Windows JVM 测试由 CI 承担）。
2. 运行正式文档验证。
3. 使用 `git diff --check` 检查空白错误，并复核暂存 diff 和工作树状态。
4. 复核提交信息和跟踪文件没有个人数据、本机信息、外部实现、临时计划、会话内容或开发过程署名；可用 `bash tools/ci/trace-scan.sh` 做本地预检。
5. 推送任务分支并创建 Pull Request；修复轮在每轮修改完成后一次推送，以末次推送的 run 作为 required checks 证据。

## 隐私与测试数据

- 测试默认使用完全匿名的合成数据。
- 本地真实来源、私人配置、来源哈希、账户映射和余额锚点不得进入 Git。
- 外部行为证据只能改写为中立规格和测试，不能复制受限实现。
- 正式代码不得依赖本机绝对路径、本地参考目录或私人配置。
- 日志、异常和测试失败输出不得泄露完整账单、账号、密钥或可识别交易。
