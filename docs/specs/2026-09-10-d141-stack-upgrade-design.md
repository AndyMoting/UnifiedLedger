# 技术栈升级批 D-141（设计规格）

**状态：** approved — 本文件为 D-141 技术栈升级批的实施规格（已冻结 2026-09-10）。独立评审：APPROVE（D141-SPEC-01..05 五项 P2/P3 披露/完整性项已在冻结前折入，delta CLOSURE APPROVE；无 P0/P1 阻断）。冻结 SHA-256 登记于 `docs/DECISIONS.md` D-141 条目（冻结字节域 = UTF-8+LF 规范域，哈希链自 D-140 规格冻结终值续接）。本批依据用户 2026-09-10 批级批准（用户指令原文照录：「D-141先记录吧」，并在批准时裁定 kotlinx-datetime 0.8.0 并入升级项、授权启动规格与实施流程，见 `docs/DECISIONS.md` D-141 条目状态节与背景 7）。验收路由照既有批次纪律：本 worktree、单一 bounded writer、独立评审、distinct verifier、双端人工门、主代理最终验收；push 仅在显式授权后由主代理执行。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为 worktree 基线 `ef31d2b` 的实读行号；`.external/` 只读）：

- **主检出根指引**：主检出 `AGENTS.md`（worktree handoff 指定入口；本批含 Android 构件与模拟器人工门，须遵守 adb 共享协议条——agent adb 走隔离 server 端口、不 `kill-server`、不触碰非本会话自启设备；验证分工、外部证据门与高风险路由按其执行）。
- **批准依据（主权威）**：`docs/DECISIONS.md` D-141 技术栈升级批条目（:2466-2515，基线实读）——冻结升级矩阵六项、保持清单、范围冻结、已知回归面与批 B 关联登记；条目授权「实施规格冻结 SHA-256 自 D-140 终值续链登记」。
- **版本声明锚点（worktree 实读，本规格唯一代码触点）**：根 `build.gradle.kts:2`（Kotlin `2.4.10`）、`:3`（AGP KMP library 插件 `9.1.0`）、`:6`（CMP `1.11.1`）、`:7`（`plugin.compose` `2.4.10`）；`app-ui/build.gradle.kts:49`（kotlinx-datetime `0.7.1`）、`:53`（backdrop `2.0.0`）；`ledger-application/build.gradle.kts:39`（kotlinx-datetime `0.7.1`）；`android-app/build.gradle.kts:29`（`targetSdk = 36`）。构建脚本内联版本声明，**无 version catalog、无依赖 lockfile/verification-metadata、无 `gradle.properties`**（实读核实）。
- **保持项锚点**：Gradle `9.5.0`（`gradle/wrapper/gradle-wrapper.properties:3`）、SQLDelight `2.3.2`、kotlinx-serialization-json `1.11.0`、activity-compose `1.13.0`、POI `5.5.1`、ktlint `14.2.0`、JDK/toolchain 21、material3（由 CMP 解析为稳定 `1.9.0`）、`compileSdk 37` / `minSdk 34` 本批零改动。
- **回归面锚点**：测试任务与用例数 `:app-ui:jvmTest` 66 / 4 类、`:desktop-app:jvmTest` 5 / 3 类、`:android-app:testDebugUnitTest` 7 / 1 类、`:ledger-data:jvmTest` 465、`:ledger-domain:jvmTest` 121、`:ledger-application:jvmTest` 376（218 common + 158 jvm）；CI `.github/workflows/ci.yml` 三 job（kotlin / android / python）。
- **升级风险面锚点（worktree 实读）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/Glass.kt`（唯一 `com.kyant.backdrop.*` 消费点，经 `P503TabShell.kt` 接线，`GLASS_ENABLED = true` 路径存活）；桌面嵌入/无限约束测量风险面 = `P503EditScreen.kt:119`（`verticalScroll`）、`P503OverviewScreen.kt:31`、`P503TabShell.kt:124`/`:153`、`P503EditScreen.kt:306`（`Dialog`）、`:271`（`DatePickerDialog`）、`:120`（`onPreviewKeyEvent`）、`:365`（仓内 `dismissOnEscape`）、`desktop-app/.../Main.kt:87`（`Window`）；全仓无 lazy list。kotlinx-datetime 使用面仅 4 文件（`OccurredAtPicker.kt`、`P503EditScreen.kt`、`OccurredAtPickerTest.kt`、`ParseManualExpenseOccurredAt.kt`），API 面 = `LocalDate`/`LocalDateTime`/`TimeZone`/`atStartOfDayIn`/`toInstant`/`toLocalDateTime`，无 `Clock`/`kotlinx.datetime.Instant`/`periodUntil`/`until`/`todayIn`/`toLocalDate`/`TimeZone` 序列化。
- **边界**：`.external/` 只读，本批零引用、零改动；本规格不引入任何产品语义推断（零账务/模式/迁移判据），仅构建坐标与 targetSdk 声明。

术语与编号约定：`本批` = D-141 技术栈升级批；`批 B` = 指定皮肤主皮库升级批（本批明确不含，见 §1.2）。

## 1. 目的与范围

### 1.1 目的

- 将六项已批准版本/Target SDK 声明一次性推进到与 Kotlin 官方受支持矩阵一致的稳定版本组合，**零业务代码变更**：CMP `1.11.1→1.12.0`、backdrop `2.0.0→2.0.1`、Kotlin `2.4.10→2.4.20`（含 `plugin.compose`）、AGP `9.1.0→9.3.1`、targetSdk `36→37`、kotlinx-datetime `0.7.1→0.8.0`。
- 解除 2026-08-29 技术栈讨论遗留的矛盾「API 37 需 AGP 9.3+ 但 Kotlin 2.4.10 官方上限 9.1.0」——Kotlin 2.4.20 抬升上限后，「全部稳定版 + API 37 + 官方受支持矩阵」首次真实存在（背景 1-3，`docs/DECISIONS.md:2470-2479`）。
- 借升级窗口使 `backdrop` 与 CMP 成对升级，保持 D-136 玻璃启用线在受支持组合上，而不是停在旧 CMP 兼容线。

### 1.2 范围（冻结）

**范围内（唯一代码触点）：** 恰好 8 处版本/声明行，分布于 4 个构建脚本文件（§3）：根 `build.gradle.kts` 4 行、`app-ui/build.gradle.kts` 2 行、`ledger-application/build.gradle.kts` 1 行、`android-app/build.gradle.kts` 1 行。

**范围外（本批明确不做，逐项冻结）：**

- **零 schema / 零迁移 / 零账务语义变更**；零 `app-ui` / `ledger-*` 业务源码改动（仅构建坐标与 `targetSdk` 声明，§3）；零新增/删除源文件；零新增单元测试（无业务逻辑变更，`§4` 既有套件回归）。
- **不做 Gradle 版本变更**：Gradle `9.5.0` 在 Kotlin 2.4.20 矩阵内（≤9.7.0）且满足 AGP 9.3 最低 Gradle 9.5 要求，保持不动（`docs/DECISIONS.md:2491`）。
- **批次 B 明确不含**：指定皮肤主皮库（D-117 所指、命名见规格的指定皮肤主皮库）升级——触发条件 = 其 0.9.4 稳定版转正 + 六维证据门（D-133 P6-D3）+ D-133「整套换肤本阶段落选」决定的反转裁决；D-117 选型证据仅作历史输入（`docs/DECISIONS.md:2502`）。
- **不取未稳定版本**：SQLDelight `2.4.0-rc1`、kotlinx-serialization `1.12-RC`、activity-compose `1.14-alpha` 一律不取（保持清单 3-5，`docs/DECISIONS.md:2493-2495`）。
- **material3 不单独升级**：CMP 1.12 坐标仍指向稳定 `1.9.0`，alpha 不自动进入（`docs/DECISIONS.md:2492`）。
- `docs/DECISIONS.md`（冻结 SHA-256 由主代理评审批后回填，本规格不写入）、`docs/PROJECT_STATE.local.md`、`docs/WORK_PLAN.local.md` 及既有 specs 本批零改动；`.external/` 零触碰。
- **追踪文档/注释中的版本陈述本批不改（D141-SPEC-01 显式披露）**：升级后以下描述性版本陈述将过时，本批冻结 8 行构建坐标 diff（A-7）不含它们，统一随阶段 6 收口登记（D4）文档同步处置，并在 `docs/DECISIONS.md` D-141 条目登记为披露项——`docs/CONTRIBUTING.md:10`（「Kotlin Multiplatform 插件 2.4.10」）、`docs/CURRENT_STATE.md:35`/`:44`（当前版本陈述与「保持 Kotlin 2.4.10、Compose Multiplatform 1.11.1」）、`docs/ROADMAP.md:45`（同）、`docs/ARCHITECTURE.md:158`（「CMP 1.11.1 已实装」）、`README.md:7`（「minSdk/targetSdk 34/36 不变」）、描述性源码注释 `android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt:14`（targetSdk 36）与 `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/UuidV7Generator.kt:15`（Kotlin 2.4.10）。`docs/DECISIONS.md` 与既有 specs 中的历史版本陈述属冻结历史，永不变更。

## 2. 机制（冻结）

### 2.1 CMP `1.11.1 → 1.12.0`（根 `build.gradle.kts:6`）

- 理由：1.12.0 为 2026-08-25 发布的稳定版（`docs/DECISIONS.md:2475`）；1.11.1 为 D-117 冻结栈，本批按矩阵推进到最新稳定。
- 兼容性实读：1.12 release notes 的弃用项（`NativeCanvas`/`NativePaint` typealias 转 ERROR、`SwingPanel` background 弃用）在本仓**零用法**（实读核实）；material3 坐标仍解析为稳定 `1.9.0`，故 material3 不随动。
- **桌面受影响面（升级批必验）**：1.12 变更「桌面嵌入/无限约束测量」，风险触点为编辑页 `verticalScroll`、`Dialog`/`DatePickerDialog` 尺寸。风险面清单见 Authority 锚点，由双端人工门向量 3 覆盖（§2.7、§6 R-1）。
- 机制：单行版本字面量替换，`apply false` 语义不变；由 app-ui / android-app / desktop-app 三模块以 `id("org.jetbrains.compose")` 在各模块构建脚本内无版本引用解析（版本自根 classpath 解析）。

### 2.2 backdrop `2.0.0 → 2.0.1`（`app-ui/build.gradle.kts:53`）

- 理由：2.0.1（2026-08-26）构建于 CMP 1.12，为 `2.0.0`（CMP 1.11 兼容线，D-136 接线）的成对升级目标，保证玻璃层与 CMP 1.12 同源（`docs/DECISIONS.md:2476`）。
- 消费面实读：全仓仅 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/Glass.kt` 消费 `com.kyant.backdrop.*`（`layerBackdrop`/`rememberLayerBackdrop`/`drawBackdrop`/`effects.blur`/`lens`/`vibrancy`），经 `P503TabShell.kt` 接线；`GLASS_ENABLED = true` 使库路径存活（非 dead code）。符号级 API 兼容风险见 §6 R-2。
- 机制：单行坐标版本替换；`implementation` 作用域与注释保持，不动 D-134 采纳门注记（如需注记版本演化，由实施评审裁定，非本规格预设）。

### 2.3 Kotlin `2.4.10 → 2.4.20`（根 `build.gradle.kts:2` 与 `:7`）

- 理由：2.4.20 稳定版 2026-09-07 发布；Kotlin 官方 KGP↔AGP 兼容矩阵 `2.4.20` 行 = AGP 8.5.2–9.3.1 / Gradle 7.6.3–9.7.0，正是解除 AGP 9.1.0 上限矛盾的行（`docs/DECISIONS.md:2472`、`:2474`）。
- 范围含 `plugin.compose`（`:7`）——Kotlin 主插件与 Compose 编译器插件为同一版本线，必须同步，否则 `plugin.compose` 与 KGP 版本错配。
- 机制：两行版本字面量同步替换（`:2` 与 `:7` 均 `2.4.10 → 2.4.20`）；Gradle `9.5.0` 在该行 ≤9.7.0 内，保持不动。

### 2.4 AGP `9.1.0 → 9.3.1`（根 `build.gradle.kts:3`）

- 理由：AGP 9.3.x 稳定 = 9.3.0/9.3.1/9.3.2；9.4.0 虽稳定但超出 Kotlin 2.4.20 官方矩阵上限 9.3.1，按纪律取矩阵内最高稳定 = `9.3.1`（`docs/DECISIONS.md:2473`）。
- 生效范围实读：`com.android.application` 在 `android-app/build.gradle.kts:2` 为**无版本**应用，其版本自根 classpath 解析；app-ui / ledger-data 以 `id("com.android.kotlin.multiplatform.library")` 无版本引用。故根 `build.gradle.kts:3` 单点 bump 即三模块（android-app、app-ui、ledger-data）的有效 AGP bump。
- compileSdk 37 支持：三模块 compileSdk 已为 37（`android-app:24`、`app-ui:37`、`ledger-data:38`），AGP 9.3.1 对 compileSdk 37 的支持/告警风险见 §6 R-6；minSdk 34 保持。
- 机制：单行版本字面量替换；不动内置 Kotlin 配置（`android-app` 注释所述 AGP 9 built-in Kotlin 路径）。

### 2.5 targetSdk `36 → 37`（`android-app/build.gradle.kts:29`）

- 理由：D-133 P6-D2 三前置 + 真机确认全部满足——大屏审计 ✓（D-135）、Android 14/15/16/17 模拟器回归全 PASS ✓（D-135 + API 37 闭合 2026-09-07）、官方行为变化逐项核对 ✓（D-135 七域无影响）、真机确认 ✓（用户 2026-09-10「测试好了」+ D-140 真机键入验证）（`docs/DECISIONS.md:2477`）。
- 机制：`defaultConfig` 内单行声明替换；`compileSdk` 已 37（无变化）、`minSdk = 34` 保持。
- 行为变更风险：targetSdk 37 引入的运行时行为变化面见 §6 R-5；由 `:android-app:assembleDebug` + 双端人工门 Android 向量覆盖（§2.7、§4）。

### 2.6 kotlinx-datetime `0.7.1 → 0.8.0`（`app-ui/build.gradle.kts:49` 与 `ledger-application/build.gradle.kts:39`）

- 理由：0.8.0 changelog 实读 + 本仓使用面审计（2026-09-10）判定为无感升级；用户 2026-09-10 裁决并入本批（`docs/DECISIONS.md:2478`）。
- 唯一 breaking = `TimeZone` 序列化弃用（#576）——全库 grep **零 `TimeZone` 序列化用法**；其余修复（`Instant.until`/`periodUntil`、RFC_1123 输出秒、Kotlin/Native Windows DST）不触本仓已用 API 或目标平台。
- 构件重构风险：0.8.0 将 `kotlinx.datetime.Instant`/`Clock` 移出主构件；本仓 `Instant` 全库已用 `kotlin.time.Instant`（D-131 引 0.7.1 时完成硬性迁移），且无 `kotlinx.datetime.Clock` 用法，方向顺路（§6 R-3、R-4）。
- 使用面 = 4 文件、API 面 = `LocalDate`/`LocalDateTime`/`TimeZone`/`atStartOfDayIn`/`toInstant`/`toLocalDateTime`，均在 0.8.0 主构件的稳定面内。
- 机制：两模块坐标同步替换为 `0.8.0`；D-131 §3.1 与 D-138 §2.1 的固定时区语义零变更。

### 2.7 双端人工门向量（冻结，供人工门与评审对照）

每条向量逐键注入、每键间隔 ≥350ms、关键键位后立即截图断言、单一输入源独占；证据以 `d141-*` 前缀存本地未跟踪目录（照 D-140 先例，不提交）。

| # | 端 | 操作序列 | 预期 |
| --- | --- | --- | --- |
| 1 | 桌面 | `:desktop-app:run` 启动 | 应用启动成功、主界面正常渲染、无启动期异常 |
| 2 | 桌面 | 编辑页打开发生时间对话框（`P503EditScreen.kt:306`，仓内 `dismissOnEscape` `:365`）后按一次 Esc；以及打开 material3 `DatePickerDialog`（`P503EditScreen.kt:271`）后按一次 Esc | 仅关闭对话框/选择器，编辑页保持、draft 完整（D-137 零回归） |
| 3 | 桌面 | 打开编辑页并触发垂直滚动 | 内容正常滚动、无测量异常/裁剪（CMP 1.12 无限约束测量风险面） |
| 4 | 桌面 | 目视玻璃层（`GLASS_ENABLED = true` 路径） | 玻璃视觉正常，无渲染缺失/崩溃（D-136 视觉门） |
| 5 | 桌面 | 发生时间逐键键入至前缀态 → 继续 → 确认页 → 取消返回 | 文本框仍为键入前缀原样保留；继续通过（D-138/D-139/D-140 语义保持） |
| 6 | Android | 安装 debug APK（`com.unifiedledger.android`） | 安装成功 |
| 7 | Android | 冷启动 | 应用冷启动成功、主界面正常 |
| 8 | Android | 手工支出创建流（键入金额/发生时间 → 继续 → 确认） | 流转正常、无崩溃、金额/时间键入原样保留 |
| 9 | Android | 发生时间选择器打开并确认 | 选择器可开、确认回写生效、继续通过 |

## 3. 逐文件变更表（冻结）

| 文件 | 行 | 现值 | 目标值 | 说明 |
| --- | --- | --- | --- | --- |
| `build.gradle.kts` | 2 | `kotlin("multiplatform") version "2.4.10" apply false` | `kotlin("multiplatform") version "2.4.20" apply false` | Kotlin KGP 升级（§2.3） |
| `build.gradle.kts` | 3 | `id("com.android.kotlin.multiplatform.library") version "9.1.0" apply false` | `id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false` | AGP 升级，根 classpath 版本源，三 Android 模块同步生效（§2.4） |
| `build.gradle.kts` | 6 | `id("org.jetbrains.compose") version "1.11.1" apply false` | `id("org.jetbrains.compose") version "1.12.0" apply false` | CMP 升级（§2.1） |
| `build.gradle.kts` | 7 | `kotlin("plugin.compose") version "2.4.10" apply false` | `kotlin("plugin.compose") version "2.4.20" apply false` | Compose 编译器插件与 KGP 同步（§2.3） |
| `app-ui/build.gradle.kts` | 49 | `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")` | `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")` | kotlinx-datetime 升级（§2.6） |
| `app-ui/build.gradle.kts` | 53 | `implementation("io.github.kyant0:backdrop:2.0.0")` | `implementation("io.github.kyant0:backdrop:2.0.1")` | backdrop 成对升级（§2.2） |
| `ledger-application/build.gradle.kts` | 39 | `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")` | `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")` | kotlinx-datetime 升级，与 app-ui 坐标同步（§2.6） |
| `android-app/build.gradle.kts` | 29 | `targetSdk = 36` | `targetSdk = 37` | targetSdk 升级，compileSdk 已 37、minSdk 34 不变（§2.5） |
| 其余全部（含全部源码、测试、schema、迁移、配置） | — | — | — | **零改动**（§1.2 范围冻结） |
| `docs/specs/2026-09-10-d141-stack-upgrade-design.md`（本文件） | — | 新增 | proposal → approved | 状态翻转与冻结 SHA-256 由主代理在独立评审通过后执行并回填 DECISIONS D-141 条目；实时性哈希不写入本文件 |

## 4. 验证计划

资源旗标遵循 `docs/CONTRIBUTING.md`：本机串行、单 worker、`--rerun-tasks`、每轮后 `--stop`，一次只运行一个命令。受影响集合（本批为构建坐标 + targetSdk，全模块受影响）：

- `./gradlew --offline --max-workers=1 :app-ui:jvmTest --rerun-tasks`（既有 66 用例，零增删）。
- `./gradlew --offline --max-workers=1 :desktop-app:jvmTest --rerun-tasks`（既有 5 用例）。
- `./gradlew --offline --max-workers=1 :android-app:testDebugUnitTest --rerun-tasks`（既有 7 用例）。
- `./gradlew --offline --max-workers=1 :ledger-domain:jvmTest --rerun-tasks`（既有 121 用例）。
- `./gradlew --offline --max-workers=1 :ledger-application:jvmTest --rerun-tasks`（既有 376 用例 = 218 common + 158 jvm）。
- `./gradlew --offline --max-workers=1 :ledger-data:jvmTest --rerun-tasks`（既有 465 用例）。
- `./gradlew --offline --max-workers=1 :android-app:compileDebugKotlin`（Android 编译门）。
- `./gradlew --offline --max-workers=1 ktlintCheck`（与 CI Ktlint 步骤一致）。
- `./gradlew --offline --max-workers=1 :ledger-data:verifyCommonMainLedgerDatabaseMigration`（迁移校验，零 schema 变更下应通过）。
- `python -m project_docs .`（`PYTHONPATH=tools/python`，与 CI python job 一致）。
- 每轮结束执行 `./gradlew --stop` 释放守护进程（本机串行纪律）。

**CI 三 job 为聚合权威：** `.github/workflows/ci.yml` 的 kotlin（`check`、`ktlintCheck`、`:app-ui:jvmTest`、`:desktop-app:build`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`）、android（`:ledger-data:compileAndroidMain`、`:android-app:testDebugUnitTest`、`:android-app:assembleDebug`、APK artifact 上传）、python（unittest discover + `project_docs`）。资源密集型聚合检查以 CI 为准，同提交 CI 成功为发布证据（`AGENTS.md` 验证分工）。

**在线解析要求（唯一网络依赖）：** 首次实施构建必须**在线执行一次**以解析新构件——CMP `1.12.0`、backdrop `2.0.1`、Kotlin `2.4.20`、AGP `9.3.1`、kotlinx-datetime `0.8.0` 均不在本地 `--offline` 缓存；解析成功后即回到 `--offline`，后续全部命令离线执行。

**实际解析版本证据（D141-SPEC-05）：** 该首次在线构建须记录实际解析出的版本作为证据——material3 自 CMP `1.12.0` 坐标解析所得（预期稳定 `1.9.0`），以及解析出的 Kotlin/Gradle 插件与 AGP 版本；因本仓无 version catalog、无 lockfile，静态无以为证，只能以构建解析结果留证。

**主机纪律：** 串行、单 worker、`--stop` 每轮后执行；不在 ALas/MuMu 自动化运行期间启动模拟器或重型构建，需先询问用户（§6 R-8）。

**adb 共存协议：** agent adb 命令一律走隔离 server 端口 `ANDROID_ADB_SERVER_PORT=5038`；**永不** 执行 `adb kill-server`；**永不** 操作非本会话自行启动并已用 `emu avd name` 核实过的设备——MuMu/ALas 为用户所有，标准 5554/5555 槽位概不触碰。Android 人工门需先经用户确认后再启动模拟器。

## 5. 验收判据（A-1..A-7）

- **A-1**：受影响套件全绿且用例数不变——`:app-ui:jvmTest`（66）、`:desktop-app:jvmTest`（5）、`:android-app:testDebugUnitTest`（7）、`:ledger-domain:jvmTest`（121）、`:ledger-application:jvmTest`（376）、`:ledger-data:jvmTest`（465），零新增零删除。
- **A-2**：`ktlintCheck` 零告警。
- **A-3**：`:android-app:compileDebugKotlin` 通过。
- **A-4**：`project_docs` 通过。
- **A-5**：双端人工门 §2.7 向量 1-9 全 PASS（逐键截图证据，桌面 1-5 + Android 6-9）。
- **A-6**：独立评审通过（含规格与变更集评审）、distinct verifier 复验、同提交 CI 三 job 成功（聚合门发布证据）；冻结 SHA-256 独立复算一致。
- **A-7**：diff 收敛——仅 §3 声明的 8 行变更，零 schema/迁移/业务代码 diff（以 diff 核验；`.external/` 零触碰、零新增文件）。

## 6. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | CMP 1.12 桌面无限约束测量变化（编辑页 `verticalScroll` / `Dialog` 尺寸） | 桌面编辑页滚动或对话框尺寸回归 | 双端人工门向量 3（§2.7）；风险面清单已实读锚定（`P503EditScreen.kt:119`/`:306`、`P503OverviewScreen.kt:31`、`P503TabShell.kt:124`/`:153`）；聚焦验证先跑 `:desktop-app:jvmTest` 后 `:desktop-app:build` |
| R-2 | backdrop 2.0.1 对 `Glass.kt` 消费的 `com.kyant.backdrop.*` 符号（`layerBackdrop`/`rememberLayerBackdrop`/`drawBackdrop`/`effects.blur`/`lens`/`vibrancy`）API 不兼容 | 玻璃层编译失败或渲染异常（`GLASS_ENABLED = true` 路径存活） | `:app-ui:jvmTest` 编译门 + `ktlintCheck` + 人工门向量 4；符号级差异由实施评审核验 |
| R-3 | kotlinx-datetime 0.8.0 构件重构（`kotlinx.datetime.Instant`/`Clock` 移出主构件） | 编译失败或误引旧符号 | 全库已用 `kotlin.time.Instant`、无 `kotlinx.datetime.Clock`；4 文件使用面已审计，均在稳定面；`:app-ui:jvmTest` + `:ledger-application:jvmTest` 编译/运行门 |
| R-4 | kotlinx-datetime 0.8.0 `TimeZone` 序列化弃用（#576） | 弃用告警或序列化行为变化 | 全库零 `TimeZone` 序列化用法（实读核实）；`ktlintCheck`/编译告警观察 |
| R-5 | targetSdk 37 运行时行为变更 | Android 运行时行为漂移 | D-133 P6-D2 三前置 + 真机确认已满足；`:android-app:assembleDebug`（CI）+ 人工门 Android 向量 6-9 |
| R-6 | AGP 9.3.1 对 compileSdk 37 的支持/告警 | 构建告警或兼容性提示 | 矩阵内最高稳定（≤9.3.1）；`:android-app:compileDebugKotlin` + CI android job；构建告警照实登记 |
| R-7 | 在线构件解析是本批唯一网络依赖（CMP 1.12.0 / backdrop 2.0.1 / Kotlin 2.4.20 / AGP 9.3.1 / datetime 0.8.0 不在本地离线缓存） | 离线构建首轮必失败 | 首次实施构建在线执行一次，成功即回退 `--offline`（§4）；网络失败视作阻塞如实登记 |
| R-8 | ALas/MuMu 自动化与实施构建/模拟器 CPU 争用 | 构建变慢或人工门注入受扰；误触用户设备 | 遵守 adb 共享协议（`ANDROID_ADB_SERVER_PORT=5038`、不 `kill-server`、不触碰用户设备）；构建串行单 worker、每轮 `--stop`；重大构建/启动模拟器前询问用户 |
| R-9 | Kotlin 2.4.10→2.4.20 编译器/行为变化本身（非纯字面量替换） | 编译告警或运行期行为漂移 | 检测面 = `:app-ui:jvmTest`(66) + `:desktop-app:jvmTest`(5) + `:android-app:testDebugUnitTest`(7) + `:ledger-domain/application/data:jvmTest` + `check` + `ktlintCheck` + 同提交 CI kotlin job；告警照实登记 |
| R-10 | CMP 1.12.0 发布时间早于 Kotlin 2.4.20；SQLDelight 2.3.2 / ktlint 14.2.0 代码生成与解析面对新 KGP | 插件/代码生成兼容问题 | 均为 CMP 1.12 官方配对线与矩阵内保持项；检测面 = CI kotlin job（`check`、`ktlintCheck`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`）+ `:android-app:compileDebugKotlin` |

## 7. 登记路径

1. 独立规格评审通过后，主代理翻转本文件状态为 approved，计算冻结 SHA-256（**冻结字节域 = UTF-8+LF 规范域，与链上既有环节 D-138 `A3BFBE82…` / D-139 `8F1C62AB…` / D-140 `CE817EC9…` 一致**），哈希链自 D-140 终值 `CE817EC9BC418B7AEAE6FF030D6F257DFB3069D4FCA860102748504735CCC0AE` 续接，并回填 `docs/DECISIONS.md` D-141 条目。
2. 实施（本 worktree、单一 bounded writer，仅 §3 声明行）→ 实施评审 → distinct verifier（复算受影响套件与冻结哈希）→ merge/push/CI（三 job 聚合门）→ 双端人工门 §2.7 → 主代理补登 D-141 条目实施登记六项。
3. merge 到本地 main；push 与 CI 仅在显式授权后由主代理执行；批准与登记本身不触发提交或推送。

## 边界断言（本批不含）

- 本文件为 proposal 草案：评审通过并翻转状态前不构成实施授权；实施批在单一 bounded writer、独立评审、distinct verifier 与主代理最终验收之下。
- 冻结 SHA-256 由主代理登记于 `docs/DECISIONS.md` D-141 条目，**不自我嵌入**本文件。
- 本文件不含本机绝对路径（仅仓库相对路径）、个人数据、账务锚点、agent/会话痕迹或密钥；`.external/` 内容零引用、零改动。
