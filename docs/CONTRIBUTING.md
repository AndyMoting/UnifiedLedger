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

该核对由守卫 workflow 自动执行：`Merge tree guard`（`.github/workflows/merge-tree-guard.yml`）在每次 push 到 `main` 时运行，用判据脚本 `tools/ci/verify-merge-tree.py` 比较合并树与合并提交自身记录的第二父树（即合并那一刻被并入的 PR tip），相同即零动作；树不等、线性提交或无法判定时自动执行上面的 `gh workflow run ci.yml --ref main` 重跑全量。上面的手工 `git rev-parse` 命令保留为兜底与解释。

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

## Android instrumented tests

`.github/workflows/android-instrumented.yml` 在 `ubuntu-latest` 上以硬件加速的模拟器（KVM；GitHub 自 2024-04-02 起对 2 vCPU 的托管 Linux runner 开放）执行设备专属的 instrumented 回归：`:android-app:connectedDebugAndroidTest` 的 **41 个用例**（10 个套件中排除 `ImportScaleTraversalInstrumentedTest`——它硬编码 1080×2400 的 tab-bar 几何与回退坐标，与单一设备 profile 耦合）。该 workflow 不进入 required checks，属于非阻塞设备信号（D-197）；`ci.yml` 的 job 列表与四个必过检查名不受影响，因为它是独立文件。

模拟器配置：`api-level 36`、`target google_apis`、`arch x86_64`（对齐本机受管 AVD；API 37 镜像在本机从未达 adb）、`cores 2`、`ram-size 2048M`、`emulator-boot-timeout 300`（默认 600 过长，卡死需快速失败）。**两个 APK 在模拟器会话之外构建**：action 在 `script` 结束后会杀掉模拟器，编译不应占用模拟器在线时间。

触发条件：`pull_request` 覆盖 `android-app/**`、`app-ui/**`、`ledger-data/**`、`ledger-application/**`、`ledger-domain/**`、根 Gradle 构建/settings/properties、wrapper 脚本与 `gradle/**`，以及本 workflow、普通测试清单及其校验工具/测试；另支持手动触发，不设定时运行。无关文档变动不启动设备。

`tools/ci/android-instrumented-inventory.txt` 是显式用例清单（当前 41 例）。构建后先核对源码中的类/方法集合；运行后 `tools/ci/android-instrumented-inventory.py` 再核对真实 JUnit XML 的精确集合及通过状态。缺报告、零执行、重复、遗漏、多余用例、失败或跳过均失败；新增、删除或改名用例必须同步清单。普通回归排除 `ImportScaleTraversalInstrumentedTest`、`AndroidScaleLongInstrumentedTest` 与专用 `AndroidScalePreflightInstrumentedTest`。

实测成本（2026-10-01 一次性探针，同一提交三次）：整条 job 407–417 秒，其中构建两个 APK 212–225 秒、模拟器启动 44.6–52.5 秒、7 个 P0 守卫的安装加执行约 122 秒；3/3 全绿。失败时上传 `android-instrumented-<sha>` 工件（JUnit XML 与 `logcat.txt`）。

**脚本约束**：action 用 `/usr/bin/sh`（dash）执行 `script`，因此该段内不能用 bash 专有的 `set -o pipefail`，也不能用反斜杠续行（反斜杠会被当作 Gradle 任务名并报 `Task '\' not found`）；每条命令写在一行。`run:` 步骤走 bash，不受此限。

人工 emulator 门仍然保留：探索式设备工作（坐标矩阵、dump 分析、像素采样）、UI/无障碍与性能向量由人工执行；本 workflow 只承担设备专属的**回归**执行。

### Android maximum-scale long test

`.github/workflows/android-scale.yml` 是**手动触发、非阻塞**的最大规模长测入口：只提供 `workflow_dispatch`，必填 `expected_sha` 为完整 40 位 SHA 且必须等于所选 ref 的 SHA；不加入 required checks，不设定时触发，也不因后续推送取消正在执行的手动运行。

云端链路：先在托管 runner 上生成确定性匿名夹具（准备阶段五批各 10,000 条共享业务内容、不同导入会话的记录，加 1,000 条唯一记录 = 51,000 候选 / 100,000 重复关系；最后一次真实 SAF 导入后 61,000 候选 / 150,000 总重复关系 / 本次会话 50,000 关系），再在**模拟器会话之外**构建两个 APK；会话内由 host 驱动器 `tools/python/android_scale/runner.py` 独占设备交互——AVD 名固定 `ul-scale`、API 36 `google_apis` x86_64、2 核 / 2048M、`wm size 1080x2400`、`wm density 420`、字号 1.0、中文 locale、`Asia/Shanghai`、三个动画尺度 1.0（保留正常动画，程序化滚动依赖动画推进）——逐阶段执行整链并把证据写入 `scale-evidence/`。

判据由 `tools/python/android_scale/result.py` 唯一实现：缺夹具、缺阶段、缺报告、SHA 不符、设备配置不符、计数 oracle 不符、超时或崩溃/OOM/ANR 一律失败；不以 `am instrument` 的 shell 返回码或单个 `OK` 字符串作为唯一通过依据。模拟器步之后另设一步独立的运行后校验（`tools/ci/android-scale-validate.py`，`if: always()`）：重跑严格判定器、重新校验夹具清单，并把设备上报的计数绑定到生成器独立算出的清单——成功信号不只依赖 host 驱动器经第三方 action 转达的退出码。工件 `android-scale-<sha>-<run-id>-<attempt>`（含 run 标识与 attempt：同一 SHA 的并行验收轮各自产出按名可辨的独立证据，整条重跑不与旧工件冲突）含 `host.json`、`device.json`、`junit.xml`、`memory.txt`、`configuration.txt`、`logcat.txt`、各阶段 instrumentation 日志，以及失败时的 `failure.png` 与 `failure-ui.xml`，保留 7 天；不上传数据库与整份输入文件。

触发命令（仅在该提交已落到目标分支后）：

```bash
gh workflow run android-scale.yml --ref <branch> -f expected_sha=<完整40位SHA>
```

云端耗时与采样内存只作观察（标注「观察到的最大值」），不替代固定设备性能阈值。2026-10-02 的已观察运行在设施启动阶段失败，业务阶段未执行，尚无完整长测通过证据。设施交付、本次功能整链通过、历史规模验收闭合是三个独立结论。验收对精确 merge SHA 发起，完整通过后同 SHA 再独立跑一次确认可重复性。

旧的 `ImportScaleTraversalInstrumentedTest` 保留为人工取证工具，语义不变；`android-instrumented.yml` 的 `notClass` 已同时排除它与 `AndroidScaleLongInstrumentedTest`，长测入口不会被普通 PR 带入。本机不跑该链：不启动模拟器，也不为该链装配 APK。

### 本地诊断通道（D-216，仅诊断，不是验收）

云端 `maximum` 长测仍是唯一验收权威，但每次失败要等 20–40 分钟的云往返。D-216 开放一条**显式 opt-in** 的本地诊断通道：默认关闭，关闭时 host 驱动器行为与今日逐字节相同（非托管 runner 环境仍直接报 `this driver is CI-only; local devices are forbidden`）；只有显式传 `--local-diagnostic` 才跳过托管 runner 环境三元组检查。该通道**不**替代验收，本地证据不得作为验收证据。

本地通道不放宽设备所有权检查：与 CI 相同，仍要求恰好一个 `emulator-` 行且 `state=device`，并 `adb -s <serial> emu avd name` 返回配置的 AVD 名（`--avd-name`，默认 `ul-scale`，故 CI 不受影响）。这取代了原先只认 `ul-scale` 名字的排他检查，强度等价：只接受本会话由 agent 亲自启动并核实过的模拟器。绝不能用于用户的 MuMu/ALas 设备。

本地运行步骤（一并遵守 `unifiedledger-harness` skill 的 MuMu 共存协议）：

1. 用隔离端口 5038 的 adb（`ANDROID_ADB_SERVER_PORT=5038`），**绝不对默认端口执行 `adb kill-server`/`start-server`**。
2. 由 agent 亲自启动一个 API 36 `google_apis` x86_64 AVD（例如 `ul_p7_d01`），显式用高位端口（`-port 5680` 附近），记录 serial 与 AVD 名；绝不操作用户 MuMu 编写的任何 `emulator-NNNN`。
3. 生成小档夹具（数分钟内跑完，仍覆盖 SAF 导入、明细判定、遍历、组处置、批量确认五个阶段；`local-small` = 20 行/会话 ×6 + 5 唯一行）：
   `PYTHONPATH=tools/python python -c "from pathlib import Path; from android_scale.fixture import generate_fixture; generate_fixture(Path('<fixture-dir>'), profile='local-small')"`。验收用的 `tools/ci/android-scale-fixture.py` 仍只生成/校验 `maximum` 档，不新增本地档参数。
4. 构建两个 APK 后调用驱动器（`--mode maximum` 仍指五阶段机器）：
   `python tools/ci/android-scale-run.py --fixture <fixture-dir> --evidence <evidence-dir> --app <app.apk> --test <test.apk> --sha <完整40位SHA> --outer-deadline-epoch <epoch> --local-diagnostic --avd-name ul_p7_d01`。
   本地运行会在 `host.json` 打上 `authority: local-diagnostic`，严格判定器 `validate_evidence` 会因此拒绝它作为验收证据。

**已知边界（务必知悉）**：设备侧 `AndroidScaleLongInstrumentedTest` 目前硬编码 `profile == "maximum"` 与 61,000/150,000 判据，故 `local-small` 夹具在本地目前无法驱动该 instrumentation 走完业务阶段——本批只打通 host 侧的 opt-in 通道与档位，本地端到端调试的最后一环（参数化设备侧 oracle）留作后续批次。此外本机模拟器与 CI 托管 runner 并非逐位相同；本地运行只作诊断，绝不产生任何验收结论。

云端 `maximum` 在**精确 merge SHA** 上的完整通过（同 SHA 再独立跑一次确认可重复）仍是该链唯一验收权威；`--local-diagnostic` 与 `--avd-name` 不得出现在任何 CI 调用中（`.github/workflows/android-scale.yml` 零改动，仍走默认路径）。

### Android scale preflight 与 APK 来源（D-200 / D-201）

可信双 APK 工件与专用预检支持同仓库 PR 和手动运行；fork PR 跳过这两项，其既有 `Android compile` 编译、单测、APK 构建及普通单 APK 上传照常执行。预检 producer 被跳过时，其依赖的设备 job 同步跳过。

`.github/workflows/android-preflight.yml` 对相关 PR 自动运行非 required 的设施预检，另支持手动运行。触发路径覆盖 Android 全部传递模块、构建入口、`tools/python/android_scale/**`、`tools/ci/android-*`、相关 Python 测试及 CI/Android workflow。预检复用长测驱动器的设备配置、安装、启动与应用私有目录传输路径；`--mode preflight` 准备含提交 SHA 的匿名小探针并执行 `AndroidScalePreflightInstrumentedTest.privateFixtureRoundTrip`，设备读回、重新写入并上报探针哈希。该用例先以只读 oracle 确认已有有效活动指针、代目录及账本，再调用正式 `openAndroidStableStorageLedger`、权威读回、关闭并重开；开前、首次打开和重开均须是同一账本/代，正式交易与 posting 均为零。必须先观察已有存储，不能让生产 opener 的 FreshInstall 补建掩盖首启失败。每个 job 最多 30 分钟，设备步骤 15 分钟，驱动执行 600 秒，失败诊断最多另用 120 秒。两个 APK 在独立 producer job 准备，模拟器会话内不构建。

默认 `--mode maximum` 保持五阶段、十一业务步骤及 61,000 候选 / 150,000 关系判据。预检报告明确标 `mode=preflight`，工件 `android-preflight-<sha>-<attempt>` 独立保存；最大规模 reducer 要求 `mode=maximum`，不能接受预检报告。D-201 加固候选必须新跑两次同候选的独立加强预检，再进行最大规模验收；旧版仅探针往返成功不满足新门。只重跑设备 job 可复用已经成功的 producer 产物。

框架重启后最多 300 秒等待窗口服务、包管理服务及用户 0 的 `RUNNING_UNLOCKED` 状态，安装后核对应用/测试包、instrumentation target、launcher 实际启动结果，并通过 `run-as` 对暂存文件逐个读回校验。应用数据根由 Android 自行管理。设施失败在 JUnit 增记 `setup ERROR`，未执行业务阶段保持 `NOT_RUN`；后续缺证只追加诊断，不覆盖首因。logcat 持续采集，断线重连/可能缺口单独记录；证据保留 7 天，仍不上传数据库或完整输入。

`am start -W` 返回不等于账本初始化完成。两种模式均需在当前剩余执行预算内、最多 180 秒，以每次独立输出路径的新鲜 UI XML 等待本包可见的 `账本：` 前缀；同包启动失败/指针恢复提示优先判错，Starting、短暂读取失败、缺失或未知 XML 均不得当作 Ready。失败 dump 的残留、其他包的文本和不可见节点不能通过。只有 Ready 后才正常 force-stop 或启动 instrumentation；失败保留 `setup ERROR`，不点击恢复、不清库、不补写指针，已有的 owned 失败清理仍可停止本包。host 必须保存 `app_ready` 与哈希绑定的 `ready-ui.xml`，预检和 maximum reducer 均强制校验；预检设备 schema 2 还必须包含开前指针观察及三次同一空账本快照，缺字段即失败。普通设备回归清单仍为 41 例，专用预检不混入其中。

最大规模执行全局预算仍为 13,800 秒，设备步骤 240 分钟，设备 job 300 分钟。`prepare` 与 `chain` 共用剩余全局预算；`reopen`、`replay`、`final-reopen` 各最多 480 秒。全部命令和 best-effort 探针受当前阶段/全局剩余时间约束；结束后的采证总预算 120 秒。不宣称准备或主链拥有能提前阻止其耗尽全局时间的独立预算。

设备步骤的时间包含模拟器启动：workflow 在 emulator action 前记录绝对截止时间，驱动入口必须收到 `--outer-deadline-epoch`，再一次性转换为单调时钟。实际执行上限取模式上限（600 / 13,800 秒）与「外层剩余时间 − 120 秒采证 − 30 秒报告/清理余量」中的较小值；采证也受外层截止前 30 秒约束。即使启动已用尽可执行时间，仍先写 `setup ERROR` 与未运行报告，不再启动设备操作。

主 CI 的 `Android compile` 保留 `android-debug-apk-<sha>` 单 APK 工件，新增两个 APK 与 `provenance.json` 组成的 `android-apks-<完整Git-tree>-<run-id>-<attempt>` 工件，保留 7 天。长测与预检先用 `tools/ci/android-apks.py` 明确查找历史 run/artifact，再用最小 `actions: read` 权限下载；仅接受同仓库的主 CI、长测 APK producer 或预检 APK producer 的成功 job，拒绝 fork 来源。校验来源 run/attempt、workflow、GitHub 实际 commit/tree、PR 预览合并父提交关系与两个 APK 的 SHA-256；复用后的新清单保留原始构建来源和直接复用来源。只有完整受控文件树相同才可复用；无匹配、已过期或下载前已删除时在云端构建，来源不符、缺 APK、清单损坏或哈希不符直接失败。产物名包含 attempt，整条重跑不与旧工件冲突。普通设备回归本批继续自行构建。

PR 产物清单只从构建时 `GITHUB_EVENT_PATH` 留存 PR 编号和当时 base/head SHA。消费时把它们与实际 source commit 的两个父提交和 run 的 head SHA 绑定，再分页查询该历史 head 所关联的 PR，验证编号、同仓库来源及 main 目标分支。PR 后来合并、关闭或继续提交导致 run 的 PR 数组清空、当前 base/head 变化，不会抹去合法历史来源；同 tip 已合并的 PR 另核实落地 merge commit（两父合并核对第二父；允许单父 squash）。

聚焦离线验证（不执行构建或设备操作）：

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest tests.python.test_android_ci_repair tests.python.test_android_scale_result tests.python.test_android_scale_fixture
python tools/ci/android-instrumented-inventory.py --source android-app/src/androidTest --manifest tools/ci/android-instrumented-inventory.txt
```

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

编译 `android-app` 的 androidTest 源（P0 hotfix 缺陷 1 回归守卫：编译 `AndroidAbsoluteDatabasePathInstrumentedTest`，使驱动绝对路径守卫不会在 CI 中腐化；该步骤只编译、不执行，设备侧的回归执行由非阻塞 workflow `Android instrumented tests` 承担（见同名小节，D-197），人工 emulator 门保留用于探索、UI/无障碍与性能向量；与 CI 的 Android app instrumented test sources compile 步骤一致）：

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

非阻塞的设备回归在独立文件 `.github/workflows/android-instrumented.yml`（见「Android instrumented tests」小节，D-197）；`ci.yml` 的 job 列表与四个必过检查名不因它改变。

CI 专属并行与分片：`.github/workflows/ci.yml` 把 Kotlin 验证拆成四个 job——`Kotlin tests core` 承担除 `:ledger-data:jvmTest` 之外的全部检查，`ledger-data shard 1/2/3` 各自承担该任务三分之一的测试类（每台 4 个 fork），收口 job **`Kotlin tests`** 等前两者全部结束（`if: always()`）后按结果放行。分支保护要求 `Kotlin tests` 这个名字，因此**收口 job 不得改名、不得被跳过**（被跳过会让该检查永远停留在等待状态）。分片名单见 `tools/ci/ledger-data-shards.txt`（每行 `shard<TAB>完整类名`，按实测耗时装箱）。重新生成有两种输入：本机用 `python tools/ci/make-ledger-data-shards.py --results ledger-data/build/test-results/jvmTest --out tools/ci/ledger-data-shards.txt`；**按托管 runner 的实测数字重排**时，从 `ledger-data shard N` 的 `Test timing report`（该步骤以 `--top 0` 列出全部类、并输出完整类名）抄出 `秒数<TAB>完整类名` 存成 TSV，再 `python tools/ci/make-ledger-data-shards.py --times <tsv> --out tools/ci/ledger-data-shards.txt`。两种输入都要按 3 片装箱；测试类增删或改名的批次必须重新生成。两道覆盖守卫保证名单不脱节：主 job 以 `--classes` 核对「编译出的测试类 == 名单」，每个分片以 `--shard/--results` 核对「实际执行出结果的类 == 该片名单」（脚本 `tools/ci/verify-shard-coverage.py`，任一不符即失败）。

并行与缓存：`tools/ci/ci-parallel.init.gradle.kts` 为全部 `Test` 任务设置 `maxParallelForks`（主 job 与分片均为 4，由 `CI_TEST_MAX_PARALLEL_FORKS` 控制），只由 CI workflow 显式传入——本机命令不受影响，「本机 Gradle 资源限制」的串行要求保持不变。各 job 以 `--max-workers`、`--parallel`、`--build-cache` 运行，并用独立缓存步骤持久化 Gradle 用户缓存目录下的 `caches/build-cache-1`：key 由 job 名、矩阵下标与运行号组成（每个 job 各存一条，避免并发 job 争抢同一条缓存而保存失败），`restore-keys` 前缀用于复用最新一条；缓存作用域按 ref，同一 PR 的后续运行（修复轮）可复用，首次运行不会命中。**`Test` 任务被显式排除在构建缓存之外**（init 脚本里的 `outputs.cacheIf { false }`）：实测同一提交重跑时出现过 `> Task :ledger-data:jvmTest FROM-CACHE`、整个分片 42 秒结束而测试并未执行——缓存只允许服务编译与分析任务，必过检查必须意味着「测试本次真的执行并通过」。每个 job 末尾的 `Test timing report` 步骤把逐类耗时与 runner 的 CPU/内存写入 job summary；该步骤只报告，不改变任何检查结论。

Python 分片：`python-shards`（矩阵 1/2）按 `tools/ci/python-shards.txt`（每行 `shard<TAB>完整模块名`）各自运行一组测试模块，`Project docs` 固定由 shard 1 承担；收口 job **`Python tests`** 等两个分片全部结束（`if: always()`）后按结果放行——与 Kotlin 侧同一模式，该名字同样不得改名或被跳过。名单按实测逐模块耗时装箱，用 `python tools/ci/make-python-shards.py --times <每模块耗时 TSV> --out tools/ci/python-shards.txt` 重新生成；测试模块增删或改名必须重新生成。静态守卫以 `--python-manifest` 与 `--tests-dir` 断言「`tests/python/test_*.py` 的模块集合 == 名单且无重复」（不执行测试、秒级完成），漏加或重复登记即失败。

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
- 合并后由守卫 workflow（`Merge tree guard`）自动执行同一节「合并树 == PR tip 树」的核对，不一致时自动重跑全量；手工 `git rev-parse` 命令保留为兜底与解释，确认等价后再继续后续工作。
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
