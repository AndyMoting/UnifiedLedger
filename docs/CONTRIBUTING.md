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

## PowerShell 执行规范

编写或运行任何脚本、命令与幂等环境交互时遵守以下五条。

- **解释器**：PowerShell 命令用 `pwsh`（PowerShell 7，本机 7.6.3），不用旧版 `powershell.exe`，也不用 `cmd.exe`。由脚本自身解释器决定的命令按其解释器原样调用——`bash tools/ci/trace-scan.sh`（本文件「Trace scan」与「本机与 CI 的验证分工」两节）以及 `bash` 围栏内的 `git`/`gh` 命令不受本条限制。
- **不混 shell 语法**：bash 与 PowerShell 的转义、引号、变量规则不同；跨平台脚本里按调用解释器一种语法写到底，不混用另一种的写法。
- **失败即停**：新建或修改的 `.ps1` 首部必须包含 `$ErrorActionPreference = 'Stop'`，让命令失败立即中断并报错，避免静默通过。harness `scripts/` 下的脚本与 `test-powershell-hygiene.ps1` 守卫共同保证这一点。
- **编码**：脚本读写文本显式指定 `-Encoding UTF8`，防止中文与特殊符号乱码。
- **对象管道优先**：解析数据优先用 `Select-Object`/`Where-Object` 等对象管道，少用文本截取与字符串切分。

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

默认工作流是 PR 门禁流：在短期任务分支上开发，推送后创建 Pull Request（`gh pr create`），required checks（`.github/workflows/ci.yml` 的 `Kotlin tests`、`Android compile`、`Python tests`、`Trace scan` 四个 job）全部通过后以 squash merge 合并（消息显式给出，见「合并」节）。job name 与分支保护规则强耦合，改名必须同步更新分支保护配置。`ci.yml` 只在 `pull_request` 上触发（另留 `workflow_dispatch` 供人工重跑）；合并推送 `main` 后由 `Merge tree guard` 兜底：它在每次 push 到 `main` 时运行——merge commit 走树判据、squash 提交走「合并」节的 squash 证据；证据完整即零动作（只扫描落地提交），无法判定或证据不全时派发一次全量 CI。

本机只保留快速反馈：`project_docs`，以及可选的 `bash tools/ci/trace-scan.sh` 本地预检；受影响模块的聚焦定向测试归 CI。完整 `check`、ktlint、Android/KMP 编译、Debug APK、完整 Python 测试、Desktop build、migration verifier 与 Windows JVM 测试由 CI 在资源更充足的 runner 上执行。PR run 检出并验证的是该 PR 的**预览合并树**（`Merge <head> into <base>`），因此发布证据是「合并树与该 PR tip 树逐字节相同」，合并后立即核对：

```bash
git rev-parse "<merge-sha>^{tree}" "<pr-tip-sha>^{tree}"
```

两个哈希相同即为等价证据；不同（合并引入了 PR tip 之外的内容，例如冲突解决或基点过期）必须重跑全量：

```bash
gh workflow run ci.yml --ref main
```

该核对由守卫 workflow 自动执行：`Merge tree guard`（`.github/workflows/merge-tree-guard.yml`）在每次 push 到 `main` 时运行，用判据脚本 `tools/ci/verify-merge-tree.py` 比较合并树与合并提交自身记录的第二父树（即合并那一刻被并入的 PR tip），相同即零动作；树不等、线性提交或无法判定时，再核对 squash 证据（见「合并」节），证据不全才自动执行上面的 `gh workflow run ci.yml --ref main` 重跑全量。零动作路径仍扫描落地提交，因此「每个落地提交至少被扫描一次」成立。该命令只作用于 `main` 当前 tip；需要某个历史 SHA 的整树 APK bundle 时先确认它仍是 tip。手工 `git rev-parse` 命令保留为兜底与解释。

只有在变更范围或失败诊断需要时，才在本机重复相应的资源密集型命令。

修复轮纪律：每轮修改完成后一次性提交并推送。PR 的 concurrency 会在新一轮推送时取消旧 run，只有末次推送的 run 会跑完并产出完整的绿色检查；不要在旧 run 上等待结论。

## Trace scan

`tools/ci/trace-scan.sh` 是 harness `verify-project.ps1` trace scope 在产品仓内的 CI 等价实现（harness 脚本位于本地未跟踪的 unifiedledger-harness 技能目录，CI 无法读取）。它依次执行四项扫描：当前 tracked 文件内容、全部历史提交消息、全部历史路径（对照 agent 路径正则）、全部分批历史树内容（每批 200 commit，规避 Windows 32K argv 长度限制，Linux 保持同一分批语义）。CI 的 `Trace scan` job 与本机 Git Bash 使用同一命令：

```bash
bash tools/ci/trace-scan.sh
```

同步义务：脚本内的 `TRACE_PATTERN` 与 agent 路径正则必须与 harness 技能的 `verify-project.ps1` trace scope 逐字一致；任一侧修改必须在同一变更中同步另一侧。豁免路径通过 `ALLOWED_TRACE_PATH` 环境变量注入（冒号或换行分隔的仓库相对路径），CI 默认为空；脚本会自动豁免其自身路径（其内容逐字嵌入 pattern 常量，必然命中内容扫描），已合并入保护分支、无法重写历史的提交消息命中，可经 `tools/ci/trace-exempt-commits.txt` 登记豁免——豁免仅作用于历史消息扫描（按提交块整块剔除），清单缺失或为空时机制默认休眠，且不是对未来提交的预批准。

PR 范围（D-302, T2）：PR 上设置 `TRACE_BASE_SHA`，三类历史扫描收窄到 `<base>..HEAD`，tracked 文件内容扫描仍然覆盖全树；`main` 侧与手动 dispatch 下该变量未设置或为空，保持全历史四类扫描。base 无法解析时脚本以退出码 2 失败，而不是退回全量或静默通过——隐私门只允许红，不允许自行改变覆盖面。

## Windows JVM tests

`.github/workflows/windows.yml` 在 `windows-latest` 上运行全部 JVM 测试任务（`:ledger-data:jvmTest`、`:desktop-app:jvmTest`、`:app-ui:jvmTest`、`:ledger-application:jvmTest`、`:ledger-domain:jvmTest`、`:android-app:testDebugUnitTest`）。该 workflow 不进入 required checks，属于非阻塞平台信号。

触发条件：每日 UTC 18:00（北京时间 02:00）对默认分支的定时运行，以及手动触发。PR 不再触发（D-302）：该信号非阻塞，且截至 2026-11-09 的 105 次 PR 触发里 75 次被后续推送取消（71%）；r32 类平台风险面由每夜对默认分支的运行覆盖。运行全部 JVM 测试而非 `check` 的理由：ktlint、打包与 migration 校验平台中立且 ubuntu CI 已覆盖；Windows 特有风险面是 xerial 文件锁、临时目录删除与 `ATOMIC_MOVE` 语义，分布在 `ledger-data`（94 类）与 `desktop-app`（14 类）中。

## Android instrumented tests

`.github/workflows/android-instrumented.yml` 在 `ubuntu-latest` 上以硬件加速的模拟器（KVM；GitHub 自 2024-04-02 起对 2 vCPU 的托管 Linux runner 开放）执行设备专属的普通 instrumented 回归：**61 个用例、17 个测试类**，由 `tools/ci/android-instrumented-inventory.txt` 显式列出（`ImportScaleTraversalInstrumentedTest` 不在清单内——它硬编码 1080×2400 的 tab-bar 几何与回退坐标，与单一设备 profile 耦合）。该 workflow 不进入 required checks，属于非阻塞设备信号（D-197）；`ci.yml` 的 job 列表与四个必过检查名不受影响，因为它是独立文件。

作业分两条设备路径，收口 job 都叫 `Android instrumented tests`。同仓 PR 与手动触发走可信路径：producer job `Android instrumented APKs` 先用 `tools/ci/android-apks.py reuse` 查找并校验同树（完整 Git tree 相同）的历史双 APK 工件，命中即复用、未命中才构建，随后 `pack` 出本 run 的 `android-apks-<tree>-<run>-<attempt>` 工件并在设备结果之外上传；两个 `Android instrumented shard 1/2` 各自下载并用 `android-apks.py verify` 复核该工件，各自启动一个 AVD，直接 `adb install` 两个 APK 后以 `am instrument -w -r -e class <class#method,...>` 执行 `tools/ci/android-instrumented-shards.txt` 分给该片的用例（模拟器会话内不运行任何 Gradle）。收口 job 等待适用路径全部结束，任一部分失败、跳过或缺证据即红。

fork PR 走单独的 fallback job `Android instrumented fork fallback`：在单个 runner 内自行构建两个 APK，并用同一 direct-instrumentation 驱动器在一个 AVD 上执行全部 61 例；它不共享任何可信工件（可信 APK 工件要求 producing run 与仓库同源，`android-apks.py verify` 拒绝 fork 来源），使用与两片相同的 AVD 固定配置和同一套精确集合校验。

模拟器配置：`profile pixel_6`（1080×2400 / density 420，与长测链同一设备）、`api-level 36`、`target google_apis`、`arch x86_64`（对齐本机受管 AVD；API 37 镜像在本机从未达 adb）、`cores 2`、`ram-size 2048M`、`emulator-boot-timeout 300`（默认 600 过长，卡死需快速失败）。**两个 APK 在模拟器会话之外构建**：action 在 `script` 结束后会杀掉模拟器，编译不应占用模拟器在线时间。

fork PR 的 fallback 与同仓两片使用同一 AVD 固定配置，并以同一校验器要求实际执行的 `class#method` 集合等于完整 61 例清单。

触发条件：`pull_request` 覆盖 `android-app/**`、`app-ui/**`、`ledger-data/**`、`ledger-application/**`、`ledger-domain/**`、根 Gradle 构建/settings/properties、wrapper 脚本与 `gradle/**`，以及本 workflow、普通测试清单、分片清单、APK 来源校验工具及其测试；另支持手动触发，不设定时运行。无关文档变动不启动设备。

`tools/ci/android-instrumented-inventory.txt` 是显式用例清单（当前 61 例 / 17 类），`tools/ci/android-instrumented-shards.txt` 是权威分片清单（每行 `shard<TAB>完整类名`；两片并集等于清单、互不重叠）。producer 在构建前核对源码中的类/方法集合与清单一致，并静态证明分片划分；每片跑完后 `tools/ci/android-instrumented-cloud.py validate` 重新解析原始 `am instrument` 转录、该片 JUnit XML、设备配置与导出的 logcat，要求实际执行的 `class#method` 集合精确等于该片清单子集，且无 failure/error/skip/缺失。缺报告、零执行、重复、遗漏、多余用例、失败或跳过均失败；新增、删除或改名用例必须同步清单与分片文件。普通回归排除 `ImportScaleTraversalInstrumentedTest`、`AndroidScaleLongInstrumentedTest` 与专用 `AndroidScalePreflightInstrumentedTest`；`P807Q2Flow2ReconciliationInstrumentedTest` 同样不在清单与分片内，它走下方的专用 Q2 rig。

手动派发的 `instrumentation_extra_args` 是 D7-13 的按需读数通道。普通 PR、fork PR 和空参数手动派发均执行普通回归。传入一个 `KEY=VALUE` 时，`tools/python/android_scale/instrumented.py` 的 `ON_DEMAND_HOSTS` 为已登记参数指定唯一宿主类。只有该类所在的分片将参数作为单个 `-e KEY VALUE` 传给 `am instrument`。宿主类缺席时在占用设备前失败。

- `ul.p802.timing=1` 启用 P8-02 V-8 搜索首页计时用例，并要求对应读数。
- `ul.p804.v7=1` 在 SelfEntry 宿主分片额外运行 10,000 行和 10,001 行两档边界样本。原 1,000 行用例始终执行。宿主分片必须各有一条匹配的 `P804-V7DEV` 读数。`fixture=at-limit` 记录接收和设备账本读回成功，`fixture=over-limit` 记录 `BatchExceedsLimit(actualAcceptedRecords=10001)` 及零写入。两条读数均须记录 `formalLedgerRows=0`。
- `ul.p806.timing=1` 启用 P8-06 V-14 报表计时用例。四条 `P806-V14DEV reading=<label> ms=<x.xxx> statements=<n>` 须各出现一次，每条语句数 ≥1，分别覆盖报表期间金额语句、日历桶、明细首页与计数。P8-02 和 P8-06 的计时用例在未启用时打印 `SKIPPED` 后正常返回。

`tools/ci/android-instrumented-cloud.py validate` 接收同一派发参数，重算转发集合并核对 `shard.json` 中的请求与转发记录。它是唯一的按需证据门，要求宿主分片的每条预期读数恰好出现一次，并按各通道定义的格式与结果要求校验。非宿主分片、普通回归、值为非 `1` 的参数及仅在值里包含已登记键的其他参数均无需读数。耗时只记录，不设阈值（D-347）。

实测成本（2026-10-01 一次性探针，同一提交三次，拆分前为单 job）：整条 job 407–417 秒，其中构建两个 APK 212–225 秒、模拟器启动 44.6–52.5 秒、7 个 P0 守卫的安装加执行约 122 秒；3/3 全绿。拆分后构建归 producer、两片并行；每片证据工件名为 `android-instrumented-<sha>-shard-<n>`，含 `shard.json`、原始转录、JUnit XML 与 `logcat.txt`。

**脚本约束**：action 用 `/usr/bin/sh`（dash）执行 `script`，因此该段内不能用 bash 专有的 `set -o pipefail`，也不能用反斜杠续行（反斜杠会被当作 Gradle 任务名并报 `Task '\' not found`）；每条命令写在一行。`run:` 步骤走 bash，不受此限。

模拟器步骤失败会自动重试一次（首尝试 `continue-on-error`，重试发生时在 job summary 留痕；判定仍由独立校验器在 `always()` 下做，真实失败重试后仍是红；D-304）。Gradle wrapper 下载带 `retries=3`，且 `apks` job 缓存 Gradle 依赖与 wrapper 分发。

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

旧的 `ImportScaleTraversalInstrumentedTest` 保留为人工取证工具，语义不变；普通设备回归改用清单白名单执行（`tools/ci/android-instrumented-inventory.txt` 加 `tools/ci/android-instrumented-shards.txt`），`ImportScaleTraversalInstrumentedTest`、`AndroidScaleLongInstrumentedTest` 与专用预检套件都不在清单内，长测入口不会被普通 PR 带入。CI 上该链仍不随 PR 执行；本机执行只经下方 D-216 本地诊断通道（显式 opt-in，非验收）。

### Android B6 组卡开卡门（P704GP-D-PERF-01）

`.github/workflows/android-b6-card.yml` 是**手动触发、非阻塞**的单门入口：只提供 `workflow_dispatch`，必填 `expected_sha`（完整 40 位 SHA，必须等于所选 ref 的头），可选 `rounds`（默认 3）与 `limit_ms`（默认 3000）；不加入 required checks，不设定时触发，也不因后续推送取消正在执行的手动运行。

该门回答一个问题：在**参考设备 profile** 上，产品自己的 20k 重复组夹具发布后，点击组入口到组卡可见是否在 `limit_ms` 以内——判据取 N 轮中的**最差一轮**（max-of-N，不是中位数：单轮慢正是该门要抓的失败）。数值由 `ImportScaleTraversalInstrumentedTest#b6CardOpen` 从无障碍树读出，是产品 UI 可观察的 click→card-visible 区间，不是内部计时器。设备 profile 与历史本机读数相同：`pixel_6`（1080×2400 / 420）、2 核、2048M、保留平台动画。

`tools/ci/android-b6-card.py run` 独占设备交互（单设备所有权断言 `emu avd name` 必须答 `ul-b6-card`、两个 APK 安装并计哈希、**每轮一次完整循环：清数据 → `seedFixture` → 测量**（已发布的夹具只可用到产品激活新增候选为止，故第 2 轮起必须重建）、traversal 日志切片、`host.json`）；模拟器会话之后另设独立 `gate` 步（`if: always()`），只从磁盘上的轮次日志重算判据，不读驱动器自己的数字，因此驱动器自解析错误无法让 job 变绿。两个 APK 在**模拟器会话之外**由本次 checkout 构建，配合 `git rev-parse HEAD` 断言，无需 APK 工件链。工件 `android-b6-card-<sha>-<run-id>-<attempt>` 含 `host-apks.txt`、`seed.txt`、每轮 `round-N.log` / `round-N.stdout.txt`、`host.json`、`gate.json`，保留 7 天。

触发命令（仅在该提交已落到目标分支后）：

```bash
gh workflow run android-b6-card.yml --ref <branch> -f expected_sha=<完整40位SHA>
```

**已知边界**：本门不覆盖 `P704GP-D-SCALE-01`（小组不回归需要另一份夹具与另一轮运行），也不对任何未画在屏幕上的层作结论。开发机不再为该门启动模拟器——本机 adb 面归用户的 MuMu 使用，云端托管 runner 是唯一执行环境（显式本地诊断通道不适用于本门）。

### Android P8-07 Q2 验收 rig（导入证据 → 对账 → 审计回看；手工退款 → 跨月报表 → 日明细）

`.github/workflows/android-p807-q2.yml` 是**手动触发、非阻塞**的单流程入口：只提供 `workflow_dispatch`，必填 `expected_sha`（完整 40 位 SHA，必须等于所选 ref 的头）与可选 `flow`（默认 `2`；`tools/python/android_p807_q2.py` 的 `FLOWS` 表当前有 flow 2 与 flow 3，未知 flow 在占用设备前拒跑）；不加入 required checks，不设定时触发，也不因后续推送取消正在执行的手动运行。

`-f flow=2`（默认）跑阶段验收设计门的第二条跨切片流程（`docs/P807_DESIGN_GATE.local.md` 的「已批准的跨切片流程」第二行；行为判据是 P8-01a 规格 §2.2–2.7 与 §4 的 V-8 / V-10 / V-11）。同一个用例内先走入口 A（账户流水 → 账户对账队列）读队列与审计，再走入口 B（导入审核候选行的「用于核对既有分录」）完成绑定、候选与确认，最后回导入审核面核对「已用于核对既有分录」标记，并用同源候选再确认一次，要求被 `IMPORT_EVIDENCE_ALREADY_RECONCILED` 拒绝且正式账本零变化。`P807Q2Flow2ReconciliationInstrumentedTest` 不在普通清单内、也不在任何分片里：用例在启动应用之前播种应用自有存储，种子守卫要求它是本进程的第一条用例，而普通两片每片只有一次 `am instrument`、两片首位都已占用，因此它独占一个 rig 与本流程自己的 AVD 名 `ul-p807-q2`（D-365）。

`-f flow=3` 派发第三条跨切片流程（同一设计门的第三行；行为判据是 P8-03 退款规格与 P8-06 报表下钻规格）。`P807Q2Flow3RefundReportInstrumentedTest` 在同一个用例内先输入超额退款并确认一次，要求界面显示冻结的拒绝句，且正式账本（含每交易版本）、退款候选与报表/日历/明细三面读值零变化；再把金额改为满额 21.00 确认，要求新增恰好一笔 `REFUND_RECEIPT`、到账时刻即统计时刻；随后用自定义窗分别读原支出月与到账月，要求原支出月逐项不变、到账月出现同笔退款的负净支出；最后从首页月游标与日历自身游标到达 2026-11，点到达日格进入日明细并往返只读交易详情，全程正式账本再读一次仍等于写入后的读数。该用例与 flow 2 同因（种子守卫要求本进程首条用例）登记在 `tools/python/android_scale/inventory.py` 的 `EXCLUDED`，因此同样不在普通清单与分片内，并复用同一个 AVD 名。

`tools/ci/android-p807-q2.py run` 独占设备交互：单设备所有权断言（`emu avd name` 必须答 `ul-p807-q2`，否则拒跑且不碰别的模拟器）、装机前 `am force-stop com.unifiedledger.android`、两个 APK 安装并计 sha256、**唯一**一次 `am instrument -w -r -e class <class#method>`、原始转录逐字落盘、logcat 与设备配置，然后写 `host.json`（schema 版本、sha、flow、case、avd、serial、api_level、起止时间、status、各证据文件名与 APK 哈希）。两个 APK 在**模拟器会话之外**由本次 checkout 构建，配合 `git rev-parse HEAD` 断言，无需 APK 工件链。设备 profile 与普通设备回归一致：`pixel_6`（1080×2400 / 420）、`api-level 36`、`google_apis`、`x86_64`、2 核、2048M、保留平台动画（程序化滚动依赖动画推进）。

模拟器会话之后另设独立 `gate` 步（`if: always()`）：只从磁盘上的转录重算判据，不读驱动器自己的数字，因此驱动器自解析错误无法让 job 变绿。它对 `host.json` 的自报状态只作**单向**信任：自报非 `PASS` 一律红（一次自报失败不会被重判为绿），自报 `PASS` 仍须由转录证明。判据：sha 绑定、flow/case 身份、`OK (1 test)`、执行集恰为该一条用例（多跑、漏跑、零用例、重复都红）、无失败标记（标记清单不在这里维护，由 `tools/python/android_p807_q2.py` 的两处决定：普通行看 `FAILURE_MARKERS`，被解析器自行消费的 STATUS 行看 `STATUS_FAILURE_KEYS`，所以 STATUS 行里的 `shortMsg=` 与 `Error=` 同样算红）、完成码 `-1`、退出码 0、`api_level == 36`（`host.json` 记录与实际设备配置各核一次）。缺转录、缺 `host.json` 或 sha 不符直接拒判。工件 `android-p807-q2-<sha>-<run-id>-<attempt>` 含 `host-apks.txt`、`host.json`、`instrumentation.txt`、`logcat.txt`、`configuration.txt` 与 `gate.json`，保留 7 天；`gate.json` 的 `reasons` 是红例的唯一清单。

触发命令（仅在该提交已落到目标分支后）：

```bash
gh workflow run android-p807-q2.yml --ref <branch> -f expected_sha=<完整40位SHA> -f flow=2
gh workflow run android-p807-q2.yml --ref <branch> -f expected_sha=<完整40位SHA> -f flow=3
```

**已知边界**：本 rig 只跑 `-f flow` 指定的那一条用例，不替代普通设备回归，也不进入 required checks。`import_candidate_status_history` 行数、V-11 同参重放 `NoChange` 与 P8-01a §2.5 的「结果未知」分支在界面不可观测，按设计门 Q5 不计入本流程判据，其服务层设备证据在 `P801AccountReconciliationChainInstrumentedTest`；V-10 的「返回保留滚动位」在单条队列上无可观测滚动，引该链的 `theScrollSlotsRoundTripThroughTheReducer` 读面证据。

### Android P8-02 V-8 渲染终点 rig（61k 首屏渲染完成）

`.github/workflows/android-p802-v8.yml` 是**手动触发、非阻塞**的单读数入口：只提供 `workflow_dispatch`，必填 `expected_sha`（完整 40 位 SHA，必须等于所选 ref 的头）；不加入 required checks，不设定时触发，也不因后续推送取消正在执行的手动运行。

该 rig 回答一个问题：61,000 笔交易样本上，固定四项条件组（关键词「咖啡标记」+ 默认近 90 天窗 + `tag-00` + `merchant-0`）查询的**首屏渲染完成**终点是多少。终点在**进程内**取：提交动作之后按固定间隔调用 `UiAutomation.takeScreenshot()`，每次只保留结果列表区域并缩放成比较网格（原始帧与中间位图立即 `recycle()`），稳定态取连续 `settleSamples` 次互相相等的采样，且这一段必须**以最新采样结尾**（否则继续采样：以旧平台期结尾的候选会把读数钉在换脸前的帧上，而像素判据仍然成立），完成时刻取**首次与最终稳定态相等的那一采样**减提交动作的派发时刻。**分辨率 = 采样间隔 + 提交派发到首采样的固定开销**（该开销记在读数行 `firstSampleAfterSubmitMs=`），读数按**上界**表述。搜索端口的返回时间、无障碍语义树与 `gfxinfo`/framestats 都不是这个终点（`D-311` 第 2 条与 `D-166` 系列的口径限制）。语义树只在**接受稳定态的那一刻**读一次，读它只为断言、不为计时：结果头 `搜索结果 · 共 N 笔` 已渲染且 N 等于夹具构造清单、列表面自带的静态 `正在搜索…` 不在屏上（该行没有动画，单靠它就能让像素提前稳定、把读数读小）、结果头之下至少有一个**带分录金额标记的节点**（标记即管理币种代码，产品行渲染器把它写在每条分录行与行的无障碍标签上，`P503LedgerViewPresentation.kt:437`）的 bounds 与裁剪带**相交**、且应用窗口 bounds 与提交前相等。四条不成立时的处置不同：结果头未渲染、计数读不出、或 `正在搜索…` 仍在屏上时继续采样；应用窗口 bounds 变了、或结果头之下没有任何带该标记的节点与裁剪带相交时直接判红——后一条是「被测区域内没有可判定的内容」的 **fail-closed 守卫**，不是不变量：换脸已发生、行尚未布局的一两帧里它也可能成立，红的是守卫，不是产品结论。`P802V8RenderInstrumentedTest` 不在普通清单、也不在任何分片内：用例在启动应用之前把样本播种进应用自有稳定存储（`/proc/self/fd` 守卫要求它是本进程首条用例），因此它独占一个 rig 与自己的 AVD 名 `ul-p802-v8`，并登记在 `tools/python/android_scale/inventory.py` 的 `EXCLUDED`（D-366）。普通清单、分片与 required checks 均不变。

`tools/ci/android-p802-v8.py run` 独占设备交互：单设备所有权断言（`emu avd name` 必须答 `ul-p802-v8`，否则拒跑且不碰别的模拟器）、装机前 `am force-stop com.unifiedledger.android`、两个 APK 安装并计 sha256、**唯一**一次 `am instrument -w -r -e class <class#method>`、原始转录逐字落盘、logcat 与设备配置，然后写 `host.json`。证据目录在会话开始时必须**不含上一次会话的工件**（唯一放行 workflow 在会话前落盘的 `host-apks.txt`，gate 从不读它）：否则报错退出并点名 stale evidence，避免上一次同 sha 残留的三件套被当成本次读数。两个 APK 在**模拟器会话之外**由本次 checkout 构建，配合 `git rev-parse HEAD` 断言。设备 profile 与普通设备回归一致：`pixel_6`（1080×2400 / 420）、`api-level 36`、`google_apis`、`x86_64`、2 核、2048M、保留平台动画（读数取像素稳定态，冻结动画会换一幅不同的表面）。

模拟器会话之后另设独立 `gate` 步（`if: always()`）：只从磁盘上的转录、logcat 与两个 APK 文件重算判据，不读驱动器自己的数字，因此驱动器自解析错误无法让 job 变绿；对 `host.json` 的自报状态只作**单向**信任。判据：sha 绑定与用例身份、`OK (1 test)`、执行集恰为该一条用例、无失败标记、完成码 `-1`、退出码 0、`api_level == 36` 与 AVD 名、**读数行恰一次**、`fixture=61000`、`condition=fixed-note+tag+merchant0`、`renderFirstPageMs` 为有限正数、`firstSampleAfterSubmitMs` 为有限非负数、`samples >= settleSamples` 且 `settleSamples == 3`、`pageRows == 50`、`totalCount == 436`、读数行 `apk=` 等于**从磁盘重算**的 app APK sha256。任一项缺失即红，读数缺失不会被当成通过。工件 `android-p802-v8-<sha>-<run-id>-<attempt>` 含 `host-apks.txt`、`host.json`、`instrumentation.txt`、`logcat.txt`、`configuration.txt` 与 `gate.json`，保留 7 天。

触发命令（仅在该提交已落到目标分支后）：

```bash
gh workflow run android-p802-v8.yml --ref <branch> -f expected_sha=<完整40位SHA>
```

**已知边界**：本 rig 只登记读数与终点，不设耗时阈值、不判快慢，也不构成阶段验收结论。它不证明长账本翻页与滚动、不证明 TalkBack 朗读、不覆盖条件区或结果行的字号与裁切，也不替代普通设备回归与既有 V-8 端口读数（两者是不同证据面，不可相减）。

### Android font-scale sample（P8-00 V-8 / P8-01a i3b / P8-02 V-9 / P8-03 V-14 / P8-06 V-12）

`.github/workflows/android-fontscale-sample.yml` 是**手动触发、非阻塞**的设备读数入口：只提供 `workflow_dispatch`，必填 `expected_sha`（完整 40 位 SHA，必须等于 checkout 的 HEAD），可选 `profile`（`empty` 默认 / `seeded` / `refund` / `p806`）与 `font_scale`（默认 `1.3`）；不加入 required checks，不设定时触发。

触发命令（仅在该提交已落到目标分支后）：

```bash
gh workflow run android-fontscale-sample.yml --ref <branch> -f expected_sha=<完整40位SHA> -f profile=empty
gh workflow run android-fontscale-sample.yml --ref <branch> -f expected_sha=<完整40位SHA> -f profile=seeded
gh workflow run android-fontscale-sample.yml --ref <branch> -f expected_sha=<完整40位SHA> -f profile=refund
gh workflow run android-fontscale-sample.yml --ref <branch> -f expected_sha=<完整40位SHA> -f profile=p806
```

`profile=empty` 在全新空安装上产出 `home.png` / `asset.png` / `analysis.png`；`profile=seeded` 与 `profile=refund` 先在模拟器会话之外以 `:ledger-data:jvmTest --tests 'com.unifiedledger.data.P801FontscaleSeedWriterTest' -Dul.fontscale.seed=<path>` 生成 v37 种子库（含一笔可退的已确认手工支出），`seeded` 再产出 `search-guide.png` / `search-results.png`（搜索面的引导态与结果态，P8-02 V-9）、`detail.png`（交易详情）与 `reconciliation.png`（账户对账队列），`refund` 产出 `refund-entry.png`（退款类型选中且候选读落地）与 `refund-confirmation.png`（原交易/到账账户/金额/发生时间填写后点继续的确认页，P8-03 V-14）。`profile=p806` 同样先以 `:ledger-data:jvmTest --tests 'com.unifiedledger.data.P806FontscaleSeedWriterTest' -Dul.p806.fontscale.seed=<path>` 生成 v38 种子库（同一上海日两笔已确认手工支出，净支出 17.34 CNY），再产出四屏 1.3× 截图与同名 uiautomator XML（P8-06 V-12）：`report.png`（报表期间金额/收入/笔数）、`report-daily-average.png`（同一报表区域滚动到自然日说明完整可读）、`calendar.png`（收支日历中含该日单元格的内容描述）与 `day-detail.png`（报表明细的已加载计数）；p806 还构建并安装承载 D-292 焦点探针的 androidTest APK，产品 APK 与该测试 APK 的 sha256 一并记入 `host.json`，由同一次 job 内的 `gate` 与磁盘文件比对（下载的工件只携带哈希，不上传 APK 本体）。各档各带自己的 uiautomator dump 与 `host.json`；`gate` 用同一个 `--profile`（`--seed` 对所有档都传，`empty` 档忽略）重算判据：profile、屏幕集合、屏幕成功态标记（含上一屏字符串缺席）或种子 sha256 与实际文件不一致即红。P8-06 档的判据不信任 `host.json` 里的字面量：`gate` 打开实际种子库，独立重算期间、日期、币种、精度、净支出、笔数与每笔支付腿的带符号金额，再与 `host.json` 记录逐项比对。报表屏要求种子月份解析出的屏幕期间行（`月：<起> 至 <止>（<n> 天）`）、精确金额与笔数，并拒绝上一份成功载荷的旧数据说明；日历屏要求当日单元格内容描述包含该日、精确金额与笔数；明细屏以 `共 N 笔明细` 为等待标记（标题先于行渲染），并要求该日标题、计数与每一笔的带符号金额及币种（如 `-12.34 CNY`）。若种子月份不是派发时报告时区的当前月（上海跨月），`run` 直接失败并要求重新生成种子，而不是拍下空月后再被读作旧月证据。`empty` / `seeded` / `refund` 三档自 P8-07 批 D 起同样驱动真实 TalkBack（目标与通路口径见下段），并同样构建、安装承载 D-292 焦点探针的 androidTest APK：产品 APK 与测试 APK 的 sha256 一并记入 `host.json`，由 `gate` 与磁盘文件比对。
语音证据在 p806 档是真实 TalkBack：三个朗读屏（报表、日历、日明细）各自启用探针与 TalkBack 后，把该屏自己的设备原始回读 `settings get secure enabled_accessibility_services` 保留为 `talkback-enabled-<屏幕>.txt`，再以唯一命令 ID 逐目标驱动；探针自身的命令记录（含无损 base64 目标）、找到的节点（其完整文本/描述也以 base64 记录并必须包含该目标）、`speech-window-begin`、accepted 焦点结果与 `speech-window-end` 必须按序属于同一 ID，`gate` 从原始窗口重新解码被聚焦目标、只重解 begin 与 end 之间的片段，并仅当该屏每个目标都判为成功（未取得 / 片段缺失 / 金额错误 均判红）时通过；每屏原始 enabled 服务记录缺失、与 `host.json` 摘要不一致或不含 `host.json` 记录的 TalkBack 组件，同样判红。无障碍焦点回调或语义 dump 都不构成朗读证据。`empty` / `seeded` / `refund` 三档共用同一条朗读通路，每档在各自的朗读屏上写死一个 `sampler.py` 屏表里的产品文案目标（`seeded` 的 `detail` 与 `reconciliation` 两屏 `speech` 为空，保持截图轨；`host.json` 的 `talkback.screens` 记每个目标的命令窗口）：三档每个目标的 `required` 串同样从 `sampler.py` 屏表重解，`host.json` 记录的字面量只作比对（与重解值不一致即红）；`seeded` 结果行目标因此要求两腿各一笔带符号金额（`-12.34 CNY` 与 `对账样本账户 12.34 CNY`），只念一腿同样红。每个目标命令之后再截一张 `talkback-<屏幕>-cmd-<命令ID>.png`，`gate` 要求该 png 存在、≥20 000 B 且名字由该目标的命令 ID 推出；同一 `talkback` 块还记设备的 TalkBack `versionName` 与 API level，`gate` 从原始件 `talkback-version.txt`（`dumpsys package` 原文加 `getprop` 回读）重解并与之比对，`host.json` 的字面量不作依据。TalkBack 起服务后 Android Accessibility Suite 会弹出 API 33+ 的 POST_NOTIFICATIONS 授权框并吞掉随后的滑动，四档在启用 TalkBack 之后、启动帧、每步、每屏标记与每次滑动前各清一次：只认权限控制器 `grant_dialog` 与精确提示文案同时出现的那个弹窗，点 deny 后回读证明已关闭，没有 deny 按钮或回读仍在即直接失败；该守卫只作用于本次自建的 AVD。工件 `fontscale-sample-<sha>-<profile>` 保留 30 天。

### 本地诊断通道（D-216，仅诊断，不是验收）

云端 `maximum` 长测仍是唯一验收权威，但每次失败要等 20–40 分钟的云往返。D-216 开放一条**显式 opt-in** 的本地诊断通道：默认关闭，关闭时 host 驱动器行为与今日逐字节相同（非托管 runner 环境仍直接报 `this driver is CI-only; local devices are forbidden`）；只有显式传 `--local-diagnostic` 才跳过托管 runner 环境三元组检查。该通道**不**替代验收，本地证据不得作为验收证据。

本地通道不放宽设备所有权检查：与 CI 相同，仍要求恰好一个 `emulator-` 行且 `state=device`，并 `adb -s <serial> emu avd name` 返回配置的 AVD 名（`--avd-name`，默认 `ul-scale`，故 CI 不受影响）。这取代了原先只认 `ul-scale` 名字的排他检查，强度等价：只接受本会话由 agent 亲自启动并核实过的模拟器。绝不能用于用户的 MuMu/ALas 设备。

本地运行步骤（一并遵守 `unifiedledger-harness` skill 的 MuMu 共存协议）：

1. 用隔离端口 5038 的 adb（`ANDROID_ADB_SERVER_PORT=5038`），**绝不对默认端口执行 `adb kill-server`/`start-server`**。
2. 由 agent 亲自启动一个 API 36 `google_apis` x86_64 AVD（例如 `ul_p7_d01`），显式用高位端口（`-port 5680` 附近），记录 serial 与 AVD 名；绝不操作用户 MuMu 编写的任何 `emulator-NNNN`。
3. 生成小档夹具（数分钟内跑完，仍覆盖 SAF 导入、明细判定、遍历、组处置、批量确认五个阶段；`local-small` = 20 行/会话 ×6 + 5 唯一行）：
   `python tools/ci/android-scale-fixture.py --out <fixture-dir> --profile local-small`。该 CLI 默认仍是 `maximum`（CI 零改动）；`--profile` 只在本地显式使用。
4. 构建两个 APK 后调用驱动器（`--mode maximum` 仍指六相位机器）：
   `python tools/ci/android-scale-run.py --fixture <fixture-dir> --evidence <evidence-dir> --app <app.apk> --test <test.apk> --sha <完整40位SHA> --outer-deadline-epoch <epoch> --local-diagnostic --avd-name ul_p7_d01`。
   本地运行会在 `host.json` 打上 `authority: local-diagnostic`，严格判定器 `validate_evidence` 会因此拒绝它作为验收证据。
5. **每次本地运行之间必须重置设备状态**：`adb -s <serial> uninstall com.unifiedledger.android`（CI 每次都是全新模拟器，本地 AVD 会跨运行残留应用数据，上一轮的设备证据文件会让 prepare 以 "stage must not be retried" 秒退）。prepare 现在会在发布 SAF 夹具前清理本档的旧 MediaStore 副本（去重副本会让 picker 的精确名匹配落空——本地实测根因之一）；若运行在 prepare 之前崩溃，`rm -f /sdcard/Download/session-06*.csv` 手动兜底。

**已知边界（务必知悉）**：设备侧判据已全部由 `AndroidScaleFixtureSpec`（纯数据类，从夹具 manifest 派生，JVM 纯逻辑测试锁定其与 `maximum` 历史字面量逐项一致）参数化，`local-small` 与 `maximum` 走同一条设备链，云端判据严格不变。本机模拟器与 CI 托管 runner 并非逐位相同；本地运行只作诊断，绝不产生任何验收结论。

云端 `maximum` 在**精确 merge SHA** 上的完整通过（同 SHA 再独立跑一次确认可重复）仍是该链唯一验收权威；`--local-diagnostic` 与 `--avd-name` 不得出现在任何 CI 调用中（`.github/workflows/android-scale.yml` 零改动，仍走默认路径）。

### Android scale preflight 与 APK 来源（D-200 / D-201）

可信双 APK 工件由主 CI 的 `Android compile` 在 PR 上产出；专用预检的模拟器链路不再跑 PR，改在合并后（`push:main`）、每夜与手动 dispatch 上运行（D-302，取代 D-200 第 2 条的 PR 自动触发；该条要求覆盖驱动、校验与 workflow 改动的范围不变，只是运行位置后移）。fork PR 不产出可信工件，其既有 `Android compile` 编译、`Android unit tests` 单测、APK 构建及普通单 APK 上传照常执行。预检 producer 被跳过时，其依赖的设备 job 同步跳过。

`.github/workflows/android-preflight.yml` 在合并后（`push:main`，`paths` 限 Android 相关路径）、每夜与手动 dispatch 上运行非 required 的设施预检；PR 不再触发（D-302）。触发路径覆盖 Android 全部传递模块、构建入口、`tools/python/android_scale/**`、`tools/ci/android-*`、相关 Python 测试及 CI/Android workflow。预检复用长测驱动器的设备配置、安装、启动与应用私有目录传输路径；`--mode preflight` 准备含提交 SHA 的匿名小探针并执行 `AndroidScalePreflightInstrumentedTest.privateFixtureRoundTrip`，设备读回、重新写入并上报探针哈希。该用例先以只读 oracle 确认已有有效活动指针、代目录及账本，再调用正式 `openAndroidStableStorageLedger`、权威读回、关闭并重开；开前、首次打开和重开均须是同一账本/代，正式交易与 posting 均为零。必须先观察已有存储，不能让生产 opener 的 FreshInstall 补建掩盖首启失败。每个 job 最多 30 分钟，设备步骤 15 分钟，驱动执行 600 秒，失败诊断最多另用 120 秒。两个 APK 在独立 producer job 准备，模拟器会话内不构建。

默认 `--mode maximum` 保持六相位、十一业务步骤及 61,000 候选 / 150,000 关系判据。预检报告明确标 `mode=preflight`，工件 `android-preflight-<sha>-<attempt>` 独立保存；最大规模 reducer 要求 `mode=maximum`，不能接受预检报告。D-201 加固候选必须新跑两次同候选的独立加强预检，再进行最大规模验收；旧版仅探针往返成功不满足新门。只重跑设备 job 可复用已经成功的 producer 产物。

框架重启后最多 300 秒等待窗口服务、包管理服务及用户 0 的 `RUNNING_UNLOCKED` 状态，安装后核对应用/测试包、instrumentation target、launcher 实际启动结果，并通过 `run-as` 对暂存文件逐个读回校验。应用数据根由 Android 自行管理。设施失败在 JUnit 增记 `setup ERROR`，未执行业务阶段保持 `NOT_RUN`；后续缺证只追加诊断，不覆盖首因。logcat 持续采集，断线重连/可能缺口单独记录；证据保留 7 天，仍不上传数据库或完整输入。

`am start -W` 返回不等于账本初始化完成。两种模式均需在当前剩余执行预算内、最多 180 秒，以每次独立输出路径的新鲜 UI XML 等待本包可见的就绪标记：`tools/python/android_scale/result.py` 的 `READY_MARKERS` 集合覆盖空账本首页、非空流水头，以及流水尚未加载时的当前交易分支，host 记录实际命中的那一个，验证器要求它属于该集合并等于从 `ready-ui.xml` 重新推导的标记。集合由 `tests/python/test_android_scale_result.py` 的守卫测试锚定——每个标记必须逐字出现在 `P503OverviewScreen.kt` 的渲染代码里（注释行不计），首页文案再改时 `Python tests` 先红，而不是设备探针静默失效；同包启动失败/指针恢复提示优先判错，Starting、短暂读取失败、缺失或未知 XML 均不得当作 Ready。失败 dump 的残留、其他包的文本和不可见节点不能通过。只有 Ready 后才正常 force-stop 或启动 instrumentation；失败保留 `setup ERROR`，不点击恢复、不清库、不补写指针，已有的 owned 失败清理仍可停止本包。host 必须保存 `app_ready` 与哈希绑定的 `ready-ui.xml`，预检和 maximum reducer 均强制校验；预检设备 schema 2 还必须包含开前指针观察及三次同一空账本快照，缺字段即失败。普通设备回归清单仍为 61 例，专用预检不混入其中。

最大规模执行全局预算为 19,800 秒（D-346 从 13,800 秒抬高：旧值在 reopen 还没跑满自身段预算时就截断了整轮），设备步骤 330 分钟，设备 job 360 分钟。相位表为六相，顺序固定（D-353）：`prepare` → `chain` → `reopen` → `replay` → `final-reopen` → `traversal`。`prepare` 与 `chain` 共用剩余全局预算；其余四相各有一个 driver 侧上界——`reopen` 18,000 秒、`replay` 480 秒、`final-reopen` 480 秒、`traversal` 3,000 秒（`tools/python/android_scale/runner.py` 的 `PHASE_BUDGETS`）。这些上界是包住对应段字面量的**封套**，不是预留额度：`reopen` 的 18,000 秒包住该相唯一的 1,800 秒段（D-350 从 9,000 秒收回），`traversal` 的 3,000 秒包住走查段的 2,400 秒（D-350 从 14,400 秒收回）再加 instrumentation 启动与证据落盘，`final-reopen` 的 480 秒包住 reducer 对未列名阶段使用的 180 秒默认上界，因此各相封套之和可以超过全局预算，靠后的相位实际受剩余全局时间约束。全部命令和 best-effort 探针受当前阶段/全局剩余时间约束；结束后的采证总预算 120 秒。不宣称准备或主链拥有能提前阻止其耗尽全局时间的独立预算。

设备步骤的时间包含模拟器启动：workflow 在 emulator action 前记录绝对截止时间，驱动入口必须收到 `--outer-deadline-epoch`，再一次性转换为单调时钟。实际执行上限取模式上限（600 / 19,800 秒）与「外层剩余时间 − 120 秒采证 − 30 秒报告/清理余量」中的较小值；采证也受外层截止前 30 秒约束。即使启动已用尽可执行时间，仍先写 `setup ERROR` 与未运行报告，不再启动设备操作。

主 CI 的 `Android compile` 保留 `android-debug-apk-<sha>` 单 APK 工件，新增两个 APK 与 `provenance.json` 组成的 `android-apks-<完整Git-tree>-<run-id>-<attempt>` 工件，保留 7 天。长测与预检先用 `tools/ci/android-apks.py` 明确查找历史 run/artifact，再用最小 `actions: read` 权限下载；仅接受同仓库的主 CI、长测、预检或普通设备回归 APK producer 的成功 job，拒绝 fork 来源。校验来源 run/attempt、workflow、GitHub 实际 commit/tree、PR 预览合并父提交关系与两个 APK 的 SHA-256；复用后的新清单保留原始构建来源和直接复用来源。只有完整受控文件树相同才可复用；无匹配、已过期或下载前已删除时在云端构建，来源不符、缺 APK、清单损坏或哈希不符直接失败。产物名包含 attempt，整条重跑不与旧工件冲突。普通设备回归同样先复用该工件，未命中才在 producer 内构建。

PR 产物清单只从构建时 `GITHUB_EVENT_PATH` 留存 PR 编号和当时 base/head SHA。消费时把它们与实际 source commit 的两个父提交和 run 的 head SHA 绑定，再分页查询该历史 head 所关联的 PR，验证编号、同仓库来源及 main 目标分支。PR 后来合并、关闭或继续提交导致 run 的 PR 数组清空、当前 base/head 变化，不会抹去合法历史来源；同 tip 已合并的 PR 另核实落地 merge commit（两父合并核对第二父；允许单父 squash）。

聚焦离线验证（不执行构建或设备操作）：

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest tests.python.test_android_ci_repair tests.python.test_android_instrumented_shards tests.python.test_android_scale_result tests.python.test_android_scale_fixture
python tools/ci/android-instrumented-inventory.py --source android-app/src/androidTest --manifest tools/ci/android-instrumented-inventory.txt
python tools/ci/android-instrumented-cloud.py check --inventory tools/ci/android-instrumented-inventory.txt --shards tools/ci/android-instrumented-shards.txt
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

运行 `ledger-domain` 的 Android host 单元测试（P708-A02 Android 腿：`commonTest` 测试在 Android host-test 编译面上执行，D-238 缺口 (iii)/规格 §2.3；与 CI 的 Android app unit tests 步骤一致，两腿执行证据以 CI 为准）：

```powershell
.\gradlew.bat :ledger-domain:testAndroidHostTest --stacktrace --rerun-tasks --warning-mode all
```

编译 `android-app` 的 androidTest 源（P0 hotfix 缺陷 1 回归守卫：编译 `AndroidAbsoluteDatabasePathInstrumentedTest`，使驱动绝对路径守卫不会在 CI 中腐化；该步骤只编译、不执行，设备侧的回归执行由非阻塞 workflow `Android instrumented tests` 承担（见同名小节，D-197），人工 emulator 门保留用于探索、UI/无障碍与性能向量；与 CI 的 Android app test sources and debug APK 步骤一致，D-303 批 D 起该步骤同时装配 debug APK）：

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
$env:ANDROID_ADB_SERVER_PORT = "5038"   # 隔离 adb：MuMu 占用 5554/5555，本会话只操作自己启动的设备
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

## Schema 迁移批次清单

升版本号（N→N+1）或扩展被广泛引用的表/类型时，实施批必须穷尽下列落点，并把每个落点纳入写范围。

1. **穷尽枚举三族版本字面量**，逐处标注语义：是「当前版本 → 升」还是「历史边 → 保持」。
   - `assertEquals(N, LedgerDatabase.Schema.version)` 族（08.A 后为 26 处）；
   - `Schema.migrate(driver, …, N)` / `migrate(driver, oldVersion = …, newVersion = N)` 族；
   - `assertEquals(NL, <派生表达式>)` 族，例如 `verification.schemaVersion`（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/BackupSnapshotDriverTest.kt:50`）、`queryUserVersion(url)`（`desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopCatalogMigrationTest.kt:46`）、`currentSupportedSchemaVersion()`（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/RestoreIsolatedMigrationTest.kt:91`）。这一族最易漏：命名式 grep（如按 `user_version = N`）捕不到它，自证时用可执行的宽 grep（`grep -rniE 'user_?version|schema_?version' --include='*.kt' .`，并另搜当前版本字面量）；
   - 历史边示例：`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/P708TagMerchantV33ToV34MigrationTest.kt:107` 与 `:191` 的 `assertEquals(34L, queryLong(driver, "PRAGMA user_version"))`，升 v35 时必须保持 34。

2. **fresh-vs-migrated 比较必须比到当前版本的全链**：只迁到某个中间版本再与 fresh（当前版本）比 `schemaMetadata`/schema 文本，升版本后必然不一致；迁移目标用 `LedgerDatabase.Schema.version` 派生（先例 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/CatalogV27ToV29MigrationTest.kt:178`），不要硬编码当前版本号。

3. **加列批次沿 `columnInfo` 契约验证**：`ALTER TABLE ADD COLUMN` 无法与 fresh 一行一列 DDL 逐字节相等（SQLite 把新列定义拼在末列同一物理行）；仓库冻结的契约是比较 `columnInfo`（列名/类型/非空/主键：`name|type|notnull|pk`，按声明行序；新列位于表尾），说明与用法见 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/P707TimeProjectionMigrationV31ToV32Test.kt:300-312`，禁止要求 `sqlite_master` 文本逐字节一致。

4. **同步改写位置式 INSERT**：`INSERT INTO <table> VALUES (…)` 不给列名时，加列后 SQLite 报 `has N columns but M values were supplied`（值个数校验先于缺省填充，新列加 `DEFAULT NULL` 也救不了）；以 `manual_expense_request` 与 `manual_income_request` 两表为例共 6 处须随加列批改写为显式列清单：`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/LedgerMigrationV1ToV11Test.kt:167`、`SqlDelightCatalogStoreTest.kt:290/539/544`、`Rg06StoreTestSupport.kt:323/333`。加列批必须以受影响表为范围在批时重新 grep 盘点位置式 INSERT 落点，不得复用本清单的数字；写范围必须覆盖盘点结果。

5. **给被广泛构造的类型加必填字段会打断所有构造点**：SQLDelight 生成函数不给可空参数 Kotlin 默认值，快照/SaveInput 等类型同理；新字段一律带 Kotlin 默认值，或把全部构造点纳入写范围（生产代码与测试都要数；示例构造点：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/GoldenManualExpenseAdapter.kt`、`Rg02ManualIncomeAdapter.kt`，及多个 `desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/*CompositionRootTest.kt`）。

6. **同步恢复白名单与组合根**：`RESTORE_SUPPORTED_SOURCE_VERSIONS` 在 `android-app/src/main/kotlin/com/unifiedledger/android/App.kt` 与 `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt` 两处必须同步扩展；新版本沿 D-185 先例为有条件准入。

7. **重生成 ledger-data 分片名单**：新增/重命名 ledger-data 测试类必须重生成 `tools/ci/ledger-data-shards.txt`（覆盖守卫逐类比对，漏登会让测试永不执行），并同步 `.github/workflows/ci.yml` 头注释的类数（例数不再复述，原因见该处注释）。

8. **登记正式状态文档残余**：`docs/CURRENT_STATE.md`、`docs/ARCHITECTURE.md`、`README.md`、`docs/ROADMAP.md` 中的 schema 版本与迁移链文件数属独立 A-DOC 同步批（D-160 先例），不在实施批内改，但必须登记残余。

9. **新增或重建 DDL 对象时，扫「先 create 当前版再反向降级」的夹具**：这类夹具用 `LedgerDatabase.Schema.create`（当前版本）建库，再逐段反向撤掉新对象来伪造历史版本；新增一个迁移对象后，它们必须在降级块里把它一并撤掉，否则新增边会在新库上 `CREATE` 撞已存在的对象、整链失败（先例：v37 的 `manual_refund_receipt` 令 `P708RestoreRoundtripEquivalenceTest` 的 v33/v34 夹具撞 `36.sqm`，同批还令 `DesktopCatalogMigrationTest` 的每一层 look-alike 世界撞同一张表）。这类夹具在迁移失败时可能吞掉 throwable，失败日志里没有对象名，判定只能靠夹具形状，不能等报错文本。**该表的探针查询若被产品代码读取，降级只能发生在快照副本上**，source 必须保留该对象，否则 seeding 阶段的引用探针会因缺表而炸。

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

每个 job 落一条 `timeout-minutes`：`ledger-data` 分片 60、Python 分片 30、`kotlin-core` 45、`kotlin-migration-verify` 30、`android` 30、`android-unit-tests` 20、`trace` 20、`changes` 5、两个收口 gate 5；设备侧两条 workflow 各有自己的预算：preflight 的 apks 30 与 preflight 45，instrumented 的 apks 30 与 shard / fork 60（后者要容纳「两次尝试 + 校验器」，D-304）；instrumented 的收口 gate 不设预算，它只做结果聚合。失控的运行在 5 到 60 分钟内被截断，而不占用 runner 的 6 小时默认上限。

**文档快路径（D-302）**：PR 的改动全部落在 `docs/**/*.md` 与仓库根 `*.md` 时，`changes` job 判为 `docs`，`Kotlin tests core`、`Kotlin migration verify`、`ledger-data shard N`、`Android compile`、`Android unit tests` 五个 job 不执行（job 级 `if:`；job 级跳过在 GitHub 记为 success，四个必过检查名因此仍被满足）；Python 两片、`Trace scan` 与两个收口 gate 照常执行。`docs/` 下的非 Markdown 文件（golden 夹具等测试输入）判 `code`。分类器是 `tools/ci/classify-changes.py`，无法判定（空 diff、取不到 base、git 报错）一律判 `code`。收口 job **`Kotlin tests` 会用同一提交独立重算分类**，与 `changes` 声明不一致即红；改动分类器、工作流或任何非文档路径的 PR 因此天然判 `code` 并跑全量。

非阻塞的设备回归在独立文件 `.github/workflows/android-instrumented.yml`（见「Android instrumented tests」小节，D-197）；`ci.yml` 的 job 列表与四个必过检查名不因它改变。设备步骤的重试与预算语义见 D-304 与「Android instrumented tests」小节。

CI 专属并行与分片：`.github/workflows/ci.yml` 把 Kotlin 验证拆成九个 job——`Kotlin tests core` 承担除 `:ledger-data:jvmTest` 与迁移校验之外的全部检查，`Kotlin migration verify` 单独跑 `:ledger-data:verifyCommonMainLedgerDatabaseMigration`（profiled 3m27s 单任务，D-303 批 E 从 core 拆出并行），`ledger-data shard 1/2/3/4/5` 各承担五分之一（每台 4 个 fork），`Android compile` 承担 Android 编译与 APK 生产（两条上传与整树 bundle，D-315），`Android unit tests` 并行承担 Android 主机单测腿（D-315），收口 job **`Kotlin tests`** 等上述 Kotlin job 与 `Android unit tests` 全部结束（`if: always()`）后按结果放行。分支保护要求 `Kotlin tests` 这个名字，因此**收口 job 不得改名、不得被跳过**——收口 job 若被跳过，依赖失败就无法变成红色的必过检查。分片名单见 `tools/ci/ledger-data-shards.txt`（每行 `shard<TAB>完整类名`，按实测耗时装箱）。重新生成有两种输入：本机用 `python tools/ci/make-ledger-data-shards.py --results ledger-data/build/test-results/jvmTest --out tools/ci/ledger-data-shards.txt --shards 5`；**按托管 runner 的实测数字重排**时，直接下载该次 run 的 `timing-shard-1`…`timing-shard-5` 工件、按顺序拼成一个 TSV（每个分片只含自己那份 `秒数<TAB>完整类名`，`timing-core` 是 core job 的全部测试类、含非 ledger-data 模块，不用于装箱；工件保留 7 天；`junit-shard-1`…`junit-shard-5` 为同一 run 的 JUnit XML，仅在 `workflow_dispatch` 时上传（同为 7 天），供拆分重类时读逐例秒数——类级 TSV 看不到重类内部哪一例主导），再 `python tools/ci/make-ledger-data-shards.py --times <tsv> --out tools/ci/ledger-data-shards.txt --shards 5`。两种输入都按 5 片装箱；测试类增删或改名的批次必须重新生成。装箱依据是**实测类秒**而不是类数：单个 251 s 的迁移大测试曾独占一个 fork，拖慢它所在的分片（D-303）。两道覆盖守卫保证名单不脱节：主 job 以 `--classes` 核对「编译出的测试类 == 名单」，每个分片以 `--shard/--results` 核对「实际执行出结果的类 == 该片名单」（脚本 `tools/ci/verify-shard-coverage.py`，任一不符即失败）。

**缓存与冷热轮的账：** 仓库没有 `gradle.properties`（`kotlin.incremental`、`org.gradle.caching`、`org.gradle.parallel` 均未配置），缓存面不含项目的 `build/` 与 `.gradle/`——所以每个 job 都从零编译，唯一的替身是 Gradle 构建缓存里内容哈希一致的任务产物。实测（run `43210719821` 的 `ledger-data shard 1`）：热轮命中五行 `FROM-CACHE`，即 `:ledger-domain:compileKotlinJvm`、`:ledger-application:compileKotlinJvm`、`:ledger-data:generateCommonMainLedgerDatabaseInterface`、`:ledger-data:compileKotlinJvm`、`:ledger-data:compileTestKotlinJvm`；冷轮（run `43162001320` 同一 job，总 474 s）为 **0 命中**，该 job 实测拆账：环境准备 18 s、Gradle 配置 14 s、**编译/生成链 112 s**、测试执行 254 s、其余安静段与收尾约 70 s（日志没有逐任务计时，边界按打印行差分，每处 ±30 s）。因此**预编译一个前置 job 省的是账单不是墙钟**：它把每个分片各 112 s 的编译从五处并行挪成最前面的一段串行，而墙钟取最慢 job，所以不变或更差（五个分片约 560 s 机器时间换成约 112 s，账单省下、延迟不减）；能降墙钟的只有减少最慢分片自身的工作量——本例里测试执行 254 s 是编译的两倍多。

并行与缓存：`tools/ci/ci-parallel.init.gradle.kts` 为全部 `Test` 任务设置 `maxParallelForks`（各 job 均为 4，由 `CI_TEST_MAX_PARALLEL_FORKS` 控制；六 fork 试验因四 vCPU 超订已撤回，见 D-303），只由 CI workflow 显式传入——本机命令不受影响，「本机 Gradle 资源限制」的串行要求保持不变。`android` job 的构建步骤使用 `--max-workers=4 --build-cache`（内存仍是全局 3 g 堆），但**单元测试步骤（D-315 起在 `android-unit-tests`）故意不带 `--build-cache`**：构建缓存目录会随 Gradle 用户缓存目录一起恢复，允许 Test 任务 FROM-CACHE 就等于让必过检查在测试未执行的情况下变绿（D-192 的先例）。`Kotlin tests core` 的聚合 Kotlin 任务带 `--profile`，报告作为 `gradle-profile-core` 工件保留 7 天，只作测量输入、不改变判据；若该任务相对基线提升 <0.5 min，则按 profile 数据另批按子项目拆分（D-303）。各 job 以 `--max-workers`、`--parallel`、`--build-cache` 运行，并用独立缓存步骤持久化 Gradle 用户缓存目录下的 `caches/build-cache-1`：key 由 job 名、矩阵下标与运行号组成（每个 job 各存一条，避免并发 job 争抢同一条缓存而保存失败），`restore-keys` 前缀用于复用最新一条；缓存作用域按 ref，同一 PR 的后续运行（修复轮）可复用，首次运行不会命中。**`Test` 任务被显式排除在构建缓存之外**（init 脚本里的 `outputs.cacheIf { false }`）：实测同一提交重跑时出现过 `> Task :ledger-data:jvmTest FROM-CACHE`、整个分片 42 秒结束而测试并未执行——缓存只允许服务编译与分析任务，必过检查必须意味着「测试本次真的执行并通过」。每个 job 末尾的 `Test timing report` 步骤把逐类耗时与 runner 的 CPU/内存写入 job summary；该步骤只报告，不改变任何检查结论。紧随其后的 `Recorded scale readings` 步骤（`tools/ci/scale-readings-report.py`）把规模测试以 `P707-A08 ` / `P708-A09 ` 前缀打印的量测行抄进同一 summary——Gradle 把这些行写在 JUnit XML 的**套件级** `<system-out>`（不在每个 `<testcase>` 内），脚本两处都读；量测值只记录、不断言阈值，该步骤同样只报告，没有匹配行时不算失败。

Python 分片：`python-shards`（矩阵 1/2）按 `tools/ci/python-shards.txt`（每行 `shard<TAB>完整模块名`）各自运行一组测试模块，`Project docs` 固定由 shard 1 承担；收口 job **`Python tests`** 等两个分片全部结束（`if: always()`）后按结果放行——与 Kotlin 侧同一模式，该名字同样不得改名或被跳过。名单按实测逐模块耗时装箱，用 `python tools/ci/make-python-shards.py --times <每模块耗时 TSV> --out tools/ci/python-shards.txt` 重新生成；测试模块增删或改名必须重新生成。静态守卫以 `--python-manifest` 与 `--tests-dir` 断言「`tests/python/test_*.py` 的模块集合 == 名单且无重复」（不执行测试、秒级完成），漏加或重复登记即失败。

## 文档规则

- 正式文档以中文为主，代码类型、文件名、命令和 API 名称保留英文。
- `PROJECT_MAP.md` 只维护模块、文档、机器工件和验证入口之间的导航关系，不复制业务规则。
- `docs/modules/` 是 `ARCHITECTURE.md` 和当前源码/测试的导航投影，不独立拥有模块边界或业务规则；源码路径只作导航，不把易变类名和函数名写成长期契约。
- 新建或实质修改的 `docs/specs/` 设计在人工审查时必须标记为 `approved`、`proposal`、`superseded` 或 `historical`。尚未标记的既有设计保留其已在正式文档、决定或 Golden 登记中确认的 authority，并在下次实质修改时分类；`project_docs` 不负责推断或批量迁移该状态。
- 确认后的产品行为、账务规则和架构变化必须同步更新对应文档。
- `CURRENT_STATE.md` 只保留当前检查点、阻塞和唯一下一步。
- `docs/ARCHIVE_*.md` 保存逐字历史，不参与正式文档检查；归档内容随原文档一并人工复核，当前事实只写正式文档。
- 文档不得包含本机绝对路径、个人账务数据或临时讨论记录。
- 需求、账务规则、架构、决定、黄金测试和当前状态各自只维护所属职责，避免复制整段内容。

### 语言

本仓的文档写给要据此行动的人。五条：

1. **先事实，后理由。** 一行一件事：谁做了什么、结果是什么；数字、run id、路径照抄。理由跟一句，且是能被检验的那句。
2. **用日常词。** 「残余」写「还没做」，「披露」写「没验证的部分」，「收口」写「做完了」，「立项」写「用户同意开始」。真正的术语保留：`shard`、`golden`、`probe`、`run id`。
3. **名词不叠三层。** 一个短语最多两层修饰；三个以上名词连排就拆成句子。
4. **括号只放证据。** 行号、run id、数字、文件名进括号；自我辩解、免责声明、软化语气不进。
5. **一段一结论。** 结论放第一句；理由留读得懂的那半。

进度日志的新条目按四段写：**做了什么 / 结果数字 / 还没做的 / 下一件**。旧条目按 append-only 不回改。

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
- 合并后由守卫 workflow（`Merge tree guard`）自动执行「合并树 == PR tip 树」的核对：merge commit 按第二父比对；squash 提交在 `main` 上没有第二父，守卫改为核对 **squash 证据**——从提交唯一解析出该 PR（标题末尾 `(#N)`，或 GitHub 的 commit→PR 关联恰好一个已合并 PR），该 PR 的 `mergeCommit` 就是落地提交、落地树与 PR head 树相同、四个必过检查在该 PR 上全绿（job 级跳过按 success 计，与分支保护口径一致）。证据齐备时零动作，并在同一 run 内扫描落地提交（squash 消息在合并时生成，PR 侧从未扫过）；证据不完整、树不等或任何一项不可读时照旧派发全量 CI。手工 `git rev-parse` 命令保留为兜底与解释。
- 默认使用 squash merge，一个 PR 落成 `main` 上一个可独立理解的提交：`gh pr merge <n> --squash --subject "<conventional subject>" --body "<正文>"`。
- 消息必须显式给出：分支提交作者与合并者不同时，GitHub 会在自动生成的消息末尾追加一行协作者署名，而该行落在 `Trace scan` 的禁止串里（见 `tools/ci/trace-scan.sh` 的 `TRACE_PATTERN`）；显式给出的 `--subject`/`--body` 原样落库。合并后用 `git log -1 --format=%B <merge-sha>` 复核：不含分隔线与协作者署名行。
- 分支上的提交使用与本仓库既有提交一致的身份（`AndyMoting <147218659+AndyMoting@users.noreply.github.com>`），不用工具或会话身份；用 `git log -1 --format='%an <%ae>'` 复核。
- 只有确需保留分支内多个可独立理解的提交时才使用 merge commit（`gh pr merge --merge`）；不使用 rebase merge 合入 `main`。
- 禁止强推或删除 `main`。

## 提交前检查

1. 本机不跑测试：受影响模块的聚焦测试、聚合检查、ktlint、迁移校验与 Windows JVM 测试全部由 CI 承担。
2. 运行正式文档验证。
3. 使用 `git diff --check` 检查空白错误，并复核暂存 diff 和工作树状态。
4. 复核提交信息和跟踪文件没有个人数据、本机信息、外部实现、临时计划、会话内容或开发过程署名；可用 `bash tools/ci/trace-scan.sh` 做本地预检。
5. 静态读先行——不启动 JVM、不启动 Gradle，零内存：新增或改名的测试类先扫全包重名，同一包内的私有顶层 `class`/`object` 与公开类一样算重名（Kotlin 只对函数豁免），取 `class|object|interface|typealias` 的名字逐文件对比即可；编译器只报第一个重名，其余同类冲突照样在下一轮让检查变红。ktlint 报错按原文逐条对照实际形态（链式折行、表达式体是否并入签名行、导入分组），不凭判断。
6. 推送任务分支并创建 Pull Request；修复轮在每轮修改完成后一次推送，以末次推送的 run 作为 required checks 证据。

## 隐私与测试数据

- 测试默认使用完全匿名的合成数据。
- 本地真实来源、私人配置、来源哈希、账户映射和余额锚点不得进入 Git。
- 外部行为证据只能改写为中立规格和测试，不能复制受限实现。
- 正式代码不得依赖本机绝对路径、本地参考目录或私人配置。
- 日志、异常和测试失败输出不得泄露完整账单、账号、密钥或可识别交易。
