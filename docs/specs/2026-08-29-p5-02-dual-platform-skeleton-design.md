# P5-02 双平台骨架实施规格（阶段 5 实施批）

**状态：approved** — 本文件为 P5-02 双平台骨架实施规格（contract-only 设计冻结：零实现、零 schema、零生产代码）。2026-08-29 用户「批准动工」后实施并经独立评审 APPROVE 与全量套件验证，D-118 登记。

**Scope:** 按已批准契约 `docs/specs/2026-08-29-p5-01-dual-platform-shell-contract-design.md` §5 P5-02（D-117）的内容清单，把 P5-02 的每一项落地为可实施的冻结设计：两模块 + 组合根、UUIDv7 与 Clock 端口实装、桌面驱动接线、CI/CONTRIBUTING 接线、零 schema。本文件只冻结设计，不实施任何代码；实施发生在批准后的独立实施批。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `e774931` 的行号；`.external/` 只读）：

- **契约**：`docs/specs/2026-08-29-p5-01-dual-platform-shell-contract-design.md`（approved，D-117）：§1 术语、§4 F-1..F-5 及其残余自由度清单、§5 P5-02 内容/验收判据/验证命令、§7 边界、§8 R-1..R-6。
- **架构**：`docs/ARCHITECTURE.md` :21-34（目标模块边界表，`android-app`/`desktop-app` 只做组合根与界面）、:36-57（依赖方向；:55 ID 与时钟为运行时能力、database handle 不是组合根）、:59-67（运行时能力与时间：来源时间不可覆盖）、:138-156（技术选择状态表）。
- **开发规范**：`docs/CONTRIBUTING.md` :14-25（16 GB 主机串行 + 1 GB heap + `--max-workers=1`）、:27-75（Kotlin 验证命令）、:93-106（CI 与文档规则、`docs/specs/` 状态标记）。
- **源码锚点（只读检查）**：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedManualExpense.kt:54,59-61,68-73,99-122,124-155`；`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/OrdinaryFlowFormalFactory.kt:20-22`；`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/OrdinaryExpense.kt:11-17`；`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightConfirmedManualExpenseCommitPort.kt:16-21,51-55,202-209`；`ledger-application/src/commonTest/kotlin/com/unifiedledger/application/ConfirmedManualExpenseIdempotencyTest.kt:50-51,258-293`；`ledger-application/src/commonTest/kotlin/com/unifiedledger/application/GoldenManualExpenseAdapterTest.kt:676-698`；`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/ImportSpineAlipayYuebaoTransferEndToEndTest.kt:723-726`；`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/LedgerDatabaseMigrationTest.kt:305`。
- **构建锚点（现状，本批零升级）**：`build.gradle.kts:1-6`（Kotlin Multiplatform `2.4.10`、AGP-KMP library `9.1.0`、SQLDelight `2.3.2`、ktlint `14.2.0` 全部 `apply false`）；`settings.gradle.kts:19-23`；`ledger-data/build.gradle.kts:10-22`（ktlint 过滤块）、:24-39（jvmToolchain(21) 与 android minSdk 34/compileSdk 36）、:41-56（依赖）；`.github/workflows/ci.yml:14-35`（kotlin job）、:37-54（android job）；`README.md:9,72`（「仓库尚无 Android/Desktop app」叙述）。

术语：沿用 P5-01 §1（`组合根`、`最小外壳`、`皮肤批`、`演示面`）；新增 `实施批` = 本规格经用户批准后另行开批的 P5-02 实现批次（独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理验收）。

## 1. 目的与范围

1. **批定义**：P5-02 = 双平台骨架实施规格。本文件把契约 §5 P5-02 的内容清单逐项落地为冻结设计；实施在用户批准后进行。
2. **冻结范围（契约 §5 P5-02 内容清单逐项落地）**：
   - 创建 `android-app` + `desktop-app` 两个 Gradle 模块与组合根（IMP-1..IMP-3、IMP-10..IMP-12）；
   - UUIDv7 与 Clock 端口的产品实现及测试用确定性实现（IMP-4..IMP-9）；
   - 桌面驱动接线 = `desktop-app` jvmMain 声明 `app.cash.sqldelight:sqlite-driver:2.3.2`（F-2，IMP-1）；
   - 构建检查接入 CI 与 CONTRIBUTING（IMP-13）；
   - schema 零变更。
3. **范围外（本批明确不做）**：
   - `platform-android`/`platform-desktop` 模块不创建（承接契约 F-3 偏差延后）；
   - 皮肤实现：双皮肤选型证据已在 P5-01 §3.2 登记，依赖与实现随皮肤批经各自证据门进入（契约 §7）；
   - 发布打包（`jpackage` 等）延后；同步/AI 不进入；
   - `.external/` 零触碰（本批只保留门查阅声明，不复制其内容）。
4. **交付物清单与稳定 ID 约定**：本批交付物唯一 = 本文件。稳定 ID：冻结决定 `IMP-1..IMP-13`（逐条对应契约 F-1..F-5 残余自由度钉死）；UQ = 无（见「UQ 说明」节）。后续实施批与登记文档引用这些 ID，不重编号。

## 2. 契约依据与残余自由度闭合表

| 契约残余自由度 | 契约条款 | 本规格冻结值 | 冻结决定 |
| --- | --- | --- | --- |
| F-1 CMP 版本（UQ-1 已裁决） | §4 F-1、§6 UQ-1 | CMP `1.11.1`（UQ-1 方案 A）；`org.jetbrains.kotlin.plugin.compose` 恒 = Kotlin `2.4.10` | IMP-1/IMP-2/IMP-3 |
| F-1 desktop 主类名 | §4 F-1 | `com.unifiedledger.desktop.MainKt` | IMP-1 |
| F-1 `compose.desktop.application` 参数 | §4 F-1 | 仅 `mainClass` 一项；`jpackage` 不启用 | IMP-1 |
| F-2 驱动声明位置与写法 | §4 F-2 | `desktop-app` jvmMain 一条 `implementation("app.cash.sqldelight:sqlite-driver:2.3.2")`；`ledger-data` 构建脚本零改动 | IMP-1 |
| F-3 目录布局与组合根构造顺序 | §4 F-3 | 目录布局见 IMP-1/IMP-2；构造顺序 = IMP-10（desktop）/ IMP-11（android） | IMP-10/IMP-11 |
| F-3 本地测试账本物化方式 | §4 F-3 | 空库引导（desktop 本地文件或内存路径 + `LedgerDatabase.Schema.create`；android 应用私有库） | IMP-12 |
| F-4 Clock 方法命名与最终签名 | §4 F-4 | `LedgerClock` fun interface：`fun now(): Instant`（`kotlin.time.Instant`，单无参方法） | IMP-4..IMP-6 |
| F-5 UUIDv7 文本形态 | §4 F-5 | RFC 9562 规范形式（8-4-4-4-12 小写十六进制；version 7；variant 10xx） | IMP-7 |
| F-5 生成器类型与注入位置 | §4 F-5 | 纯 Kotlin 位打包生成器置 `ledger-application` commonMain；安全随机源由两端组合根注入 | IMP-8/IMP-9 |

## 3. 证据基础（2026-08-29 官方来源）

本批改变生产技术（平台集成与客户端 UI 栈），外部证据门与 P5-01 同路径（`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md` 已按门查阅，只读；本文件只保留中立结论与来源 URL，不复制上游正文）。以下来源全部为官方文档、官方发布页与项目仓库：

- **desktop-app 规范形态**：JetBrains compose-multiplatform 示例 `examples/chat/desktopApp/build.gradle.kts`（github.com/JetBrains/compose-multiplatform）+ kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html——插件三件套 `kotlin("multiplatform")` + `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose`（版本 = Kotlin 版本）；`kotlin { jvm(); jvmToolchain(21) }`；jvmMain 依赖 `compose.desktop.currentOs`、`compose.material3`、`compose.foundation` 与项目库；`compose.desktop { application { mainClass = ... } }`。
- **android-app 规范形态**：developer.android.com/kotlin/multiplatform/plugin + developer.android.com/build/releases/agp-9-0-release-notes + JetBrains `androidApp` 示例——KMP android 插件族只有 library 插件（`com.android.kotlin.multiplatform.library`），无 application 插件；KMP 与 `com.android.application` 不能共存于同一子项目；AGP 9.0+ 内置 Kotlin（`android.builtInKotlin=true` 默认），`org.jetbrains.kotlin.android` 与之不兼容；经典 `com.android.application` 可直接依赖 KMP library 的 Android target（发布标准 AAR）。Manifest：NoActionBar 主题 + MAIN/LAUNCHER activity + `android:exported="true"`；`MainActivity : ComponentActivity` + `setContent` 需 `androidx.activity:activity-compose`（无需 appcompat）。KGP 2.4.10 支持 AGP 8.5.2–9.1.0（kotlinlang.org/docs/gradle-configure-project.html）。
- **Compose 坐标**：foundation `1.11.1` stable；material3 多平台工件在 1.11 线全部为 alpha（当前解析到 `1.11.0-alpha07`，`compose.material3` 访问器指向它，编译无需 opt-in）；正式 Material3 版本选择延后至皮肤批（契约 R-2）。
- **kotlin.time.Clock**：Kotlin 2.4.10 中稳定（`@SinceKotlin("2.3")` + `@WasExperimental`，无 opt-in）；API = `interface Clock { fun now(): Instant }`，`Clock.System.now()`；与契约 F-4 冻结的端口形状（单无参方法返回 `kotlin.time.Instant`）一致。
- **CI/任务名**：Gradle 9.5.0 落在 KGP 2.4.10 支持区间（7.6.3–9.5.0）且 ≥ AGP 9.1 最低要求（9.3.1）（docs.gradle.org/current/userguide/compatibility.html）；`:desktop-app:run`/`:desktop-app:build`/`:android-app:assembleDebug`/`:android-app:build` 为正确任务名；CMP 1.11.1 无 desktop-on-Windows 已知回归；首建需下载 Skiko native（16 GB 主机构建期成本，R-5）。
- **SQLDelight**：github.com/sqldelight/sqldelight（docs/jvm_sqlite、docs/multiplatform_sqlite）——按目标驱动模式：androidMain = android-driver、jvmMain = sqlite-driver；传递 `org.xerial:sqlite-jdbc:3.51.3.0`（SQLite 3.51.3 ≥ 3.35.0，满足迁移链 `ALTER TABLE DROP COLUMN` 硬要求）。
- **RFC 9562**（rfc-editor.org/rfc/rfc9562.html）：UUIDv7 文本规范形式与 version/variant 位定义。

## 4. 模块形态冻结（F-1/F-2 残余）

### IMP-1 desktop-app 模块形态

- 新建模块 `desktop-app/`，KMP jvm-only（不声明任何非 jvm target）。
- plugins：`kotlin("multiplatform")`、`id("org.jetbrains.compose") version "1.11.1"`、`id("org.jetbrains.kotlin.plugin.compose")`（版本 = Kotlin `2.4.10`）、`org.jlleitschuh.gradle.ktlint`（D-115）+ 与既有模块相同的 build 过滤块（`ledger-data/build.gradle.kts:10-22` 同款）。
- `kotlin { jvm(); jvmToolchain(21) }`，jvm compilerOptions `jvmTarget = JVM_21`（与既有模块一致）。
- 包 `com.unifiedledger.desktop`。
- jvmMain 依赖：
  - `implementation(compose.desktop.currentOs)`（CMP Desktop 运行时，官方示例 `desktopApp` 形态）；
  - `implementation(compose.material3)`——1.11 线解析到 `1.11.0-alpha07`，仅占位组件使用，正式版本留皮肤批冻结（契约 R-2）；
  - `implementation(compose.foundation)`；
  - `implementation(project(":ledger-application"))`、`implementation(project(":ledger-data"))`（依赖方向：desktop-app → 应用/数据，无反向）；
  - `implementation("app.cash.sqldelight:sqlite-driver:2.3.2")`（F-2 驱动接线：声明于 desktop-app jvmMain，`ledger-data` 构建脚本零改动）。
- jvmTest 依赖：`implementation(kotlin("test"))`。
- `compose.desktop { application { mainClass = "com.unifiedledger.desktop.MainKt" } }`（F-1 主类名钉死；`jpackage` 不启用）。
- 目录布局（F-3）：`desktop-app/build.gradle.kts`、`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt`（`fun main()` 入口 + 组合根装配，IMP-10）、`desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopSkeletonSmokeTest.kt`（§8 判据 1）。
- 来源：JetBrains `examples/chat/desktopApp` 官方示例；kotlinlang.org CMP 版本兼容页（§3）。

### IMP-2 android-app 模块形态

- 新建模块 `android-app/`，经典 AGP application 模块。
- plugins：`id("com.android.application")`（AGP `9.1.0`，根构建已声明版本）、`id("org.jetbrains.compose") version "1.11.1"`、`id("org.jetbrains.kotlin.plugin.compose")`（`2.4.10`）、`org.jlleitschuh.gradle.ktlint` + 同款过滤块。**不应用 `org.jetbrains.kotlin.android`**（AGP 9.1 内置 Kotlin，`android.builtInKotlin=true` 默认；KMP android 库插件仅保留在既有三个库模块，组合根不应用——官方依据见 §3）。
- `android { namespace = "com.unifiedledger.android"; compileSdk = 36; defaultConfig { applicationId = "com.unifiedledger.android"; minSdk = 34; targetSdk = 36 } }`——applicationId 为占位产品标识，最终命名/发布属契约外延后；Java/Kotlin target 对齐仓库 JDK 21（具体 DSL 随实施批按 AGP 9 内置 Kotlin 官方文档落实）。
- dependencies：
  - `implementation(project(":ledger-application"))`、`implementation(project(":ledger-data"))`（KMP library Android target 以标准 AAR 消费）；
  - `implementation("androidx.activity:activity-compose:1.13.0")`——`MainActivity : ComponentActivity` + `setContent` 必需（无需 appcompat）。**契约 §7 合规性分类**：`androidx.activity:activity-compose` 属于冻结 CMP/AndroidX 栈的传递图内工件（提升为 direct 依赖仅为引用 `setContent`），不构成契约 §7「零新增第三方依赖」的违反。版本锁定 `1.13.0`（当前稳定线；来源：developer.android.com/jetpack/androidx/releases/activity 与 dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml，两源一致，最高稳定版 `1.13.0`）；登记为实施批开工时重检项（依赖版本可能继续前移，重检后锁定并登记）；
  - 占位 UI 另消费 `compose.runtime`、`compose.foundation`、`compose.material3` 访问器（与 IMP-1 占位集一致）。
- `MainActivity : ComponentActivity` + `setContent` 占位 UI（`com.unifiedledger.android.MainActivity`）；AndroidManifest：application theme = NoActionBar 主题 + MAIN/LAUNCHER activity + `android:exported="true"`。
- 目录布局（F-3）：`android-app/build.gradle.kts`、`android-app/src/main/AndroidManifest.xml`、`android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt`、`android-app/src/main/kotlin/com/unifiedledger/android/App.kt`（组合根装配，IMP-11）、`android-app/src/main/res/values/...`（NoActionBar 主题资源，具体以实施批为准）。
- 来源：developer.android.com/kotlin/multiplatform/plugin + AGP 9.0 release notes + JetBrains `androidApp` 示例（§3）。

### IMP-3 根构建与 settings 接线

- 根 `build.gradle.kts` plugins 块（现 1-6 行）新增两条 `apply false`：`id("org.jetbrains.compose") version "1.11.1"` 与 `kotlin("plugin.compose") version "2.4.10"`。
- `settings.gradle.kts`（现 21-23 行）新增 `include(":android-app", ":desktop-app")`。
- 其余根文件零改动。

**注记**：两组合根占位 UI 各自实现（皮肤与共享 UI 归皮肤批，契约 §7）；`ledger-application`/`ledger-data` 构建脚本零改动（除 §5/§6 新增源码文件）。

## 5. Clock 端口实装冻结（F-4）

### IMP-4 LedgerClock 端口（命名与最终签名）

- `ledger-application` commonMain 新增：

```kotlin
fun interface LedgerClock {
    fun now(): kotlin.time.Instant
}
```

- 命名冻结为 `LedgerClock`（与标准库 `kotlin.time.Clock` 区分）；返回 `kotlin.time.Instant`（与共享源码既有时间表示一致，零类型转换）；单一无参方法，每次调用返回读取时刻的值；端口不携带时区/偏移语义（契约 F-4 原样）。
- 端口只供应处理、创建、确认与审计事件自身的时间；来源发生、支付、入账、起息和观察时间是不可变来源事实，一律不由 Clock 补写或覆盖（契约 F-4 + `docs/ARCHITECTURE.md:67` 不变）。
- 本批不改变任何既有用例签名（契约 F-4：Clock 接入点由各用例签名在 P5-02/P5-03 决定；端口契约本身对既有签名零改动）。

### IMP-5 确定性实现（测试/golden，置 commonTest）

- `FixedLedgerClock(instant: Instant) : LedgerClock`（恒返回注入时刻）置于 `ledger-application` commonTest——只用于测试/golden，不进入产品装配（契约 F-4 风险注记）；测试 fixture 归 commonTest 属仓库约定（既有测试支持类均在 commonTest）。
- 本批 Clock 相关的 commonMain 产品新增仅限 IMP-4 的 `LedgerClock` 端口；系统实现按契约 F-4「平台实现 = 系统时钟，在各组合根装配」置于两端组合根（IMP-6）。
- commonTest：确定性时钟断言——`FixedLedgerClock` 恒等断言。

### IMP-6 系统实现与注入位置

- 系统实现 = `LedgerClock { Clock.System.now() }`（`kotlin.time.Clock`，Kotlin 2.4.10 稳定、无 opt-in）；置于两端组合根：android-app androidMain / desktop-app jvmMain。
- 系统实现作为可用能力注入对象图（IMP-10/IMP-11），接线到具体用例由 P5-02/P5-03 用例签名决定，本批不接线。
- 系统实现断言落在 `desktop-app` jvmTest：执行接线后的组合根时钟（注入对象图的 `ledgerClock`）返回接近当前时刻的 `Instant`（宽松容差断言）。该 lambda 位于组合根，`ledger-application` commonTest 不可达，故 commonTest 不设系统时钟断言；android 侧由模拟器人工门（判据 2）覆盖。

## 6. UUIDv7 实装冻结（F-5）

### IMP-7 文本形态

- RFC 9562 规范形式：8-4-4-4-12 小写十六进制、连字符分隔；version 位 = `0x7`（第 13 个 hex 字符组首字符为 `7`）；variant 位 = `10xx`（第 17 个 hex 字符组首字符 ∈ `8`–`b`）。无花括号、无 URN 前缀、无大写。
- 全部产品 ID 字段按此形态生成并落盘（既有持久化以文本列存储 ID 的既有事实不变）。

### IMP-8 生成器类型与注入位置（残余自由度「生成器类型与注入位置」冻结裁决）

- 纯 Kotlin 位打包生成器 `UuidV7Generator` 置 `ledger-application` commonMain：
  - 构造注入随机字节源（如 `randomBytes: (count: Int) -> ByteArray`）；
  - `fun next(): String` 返回规范形式 UUIDv7 文本；
  - 纯算法、确定性可测（固定随机字节 → 固定输出）。
- **注入位置冻结裁决**：位打包为纯算法置于共享 commonMain（两端零复制）；安全随机数由各组合根在平台侧注入（desktop jvmMain / android androidMain 各自构造平台安全随机源）。这满足契约 F-5 风险注记「随机源必须来自平台安全随机数，实现属组合根/平台侧」——「实现属组合根/平台侧」针对安全随机数来源；位打包本身是共享中性算法，共享放置避免两端复制且不引入平台 API。此为对契约残余自由度「生成器类型与注入位置」的冻结裁决，理由如上；实施中若发现承载缺口，回 P5-01 契约做修订与评审门（D-096），不静默变更。
- **与契约 F-3/D-117 的协调说明（裁决的一致性声明）**：契约 F-5 风险注记「随机源必须来自平台安全随机数，实现属组合根/平台侧」与契约 F-3 决定「最小壳所需的少数端口（Clock、ID 生成、驱动装配）由组合根直接实现」（P5-01 spec 第 121 行；D-117 登记摘要「组合根暂直接实现少数端口」，docs/DECISIONS.md:1938）共同约束本裁决的边界：组合根仍直接实现平台敏感部分——安全随机数注入与把 IdSource 接入用例的装配——而纯 RFC 9562 位打包算法共享于 `ledger-application` commonMain，以兑现 F-3「不得复制共享核心逻辑」的零复制意图。此为在契约授予的残余自由度「生成器类型与注入位置」范围内作出的裁决；若实施中发现承载缺口，经 D-096 修订门回 P5-01 契约修订与评审，不静默变更。
- 理由：`AssetPaidOrdinaryExpenseIds` 五字段 + confirmationId 共六个 ID 全部来自同一生成器（IMP-9），共享实现防两端复制；RFC 9562 无需协调方（契约 F-5）。

### IMP-9 UuidV7ConfirmedManualExpenseIdSource

- `ledger-application` commonMain 新增 `UuidV7ConfirmedManualExpenseIdSource(generator: UuidV7Generator) : ConfirmedManualExpenseIdSource`（既有接缝 `ConfirmedManualExpenseIdSource`，`ConfirmedManualExpense.kt:59-61`；放置于 commonMain 与接缝同层）。
- `next()` 为 `ConfirmedManualExpenseCommitIds` 的每个 ID 字段生成 UUIDv7：`confirmationId` + `AssetPaidOrdinaryExpenseIds` 全部五个字段——`transactionId`、`versionId`、`postingSetId`、`expensePostingId`、`paymentPostingId`（`OrdinaryExpense.kt:11-17`），每次 `next()` 恰好生成六个 UUIDv7。
- **惰性不变量不变**：`next()` 只在持久化 `commitOnce` 的原子首请求 callback 内被调用（`SqlDelightConfirmedManualExpenseCommitPort.kt:51-55`：claim 失败走 `resolveExisting`，仅 claim 胜者调用 callback）；精确重放、identity conflict 与并发失败方零消耗——既有 KDoc 契约（`ConfirmedManualExpense.kt:99-122`）与测试断言（`ConfirmedManualExpenseIdempotencyTest.kt:50-51,226-227`）不变。
- commonTest：格式（正则 `^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`）、version/variant 位、唯一性（多批全异）、确定性注入（固定随机字节 → 固定输出）。

## 7. 组合根与对象图（F-3）

### IMP-10 desktop 组合根构造顺序

`desktop-app` jvmMain 组合根装配（手工构造对象图，DI 暂缓——契约 F-3），顺序冻结为：

1. `driver = JdbcSqliteDriver("jdbc:sqlite:<本地测试账本路径或内存>")`（IMP-12 物化方式）；
2. `LedgerDatabase.Schema.create(driver)`（空库引导，等价既有 jvmTest 用法：`ImportSpineAlipayYuebaoTransferEndToEndTest.kt:723-726`）；
3. `database = LedgerDatabase(driver)`；
4. `port = SqlDelightConfirmedManualExpenseCommitPort(database, driver)`——构造签名（database, driver），构造内 `configureSqliteConnection(driver)`（`SqlDelightConfirmedManualExpenseCommitPort.kt:16-21`）；
5. `factory: ConfirmedExpenseTransactionFactory`——用既有测试同款接线形状（`ConfirmedManualExpenseIdempotencyTest.kt:258-293` / `GoldenManualExpenseAdapterTest.kt:676-698`）：lambda 内 `createAssetPaidOrdinaryExpense(catalog, AssetPaidOrdinaryExpenseCommand(ledgerId, amount, categoryId, paymentAccountId, times = TransactionTimes.collapsed(request.occurredAt)), ids.expenseIds)`，成功包装为 `ConfirmedManualExpenseCommit(ids.confirmationId, tx)`。**注**：既有 `OrdinaryFlowFormalFactory`（`OrdinaryFlowFormalFactory.kt:20-22`）实现 `ImportCandidateFormalFactory`，不满足 `ConfirmedExpenseTransactionFactory`，不能直接注入 `ExecuteConfirmedManualExpense`——组合根采用上述既有测试同款接线；
6. `idSource = UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(<平台安全随机源>))`（IMP-8/IMP-9；JVM `java.security.SecureRandom` 在 desktop-app 注入）；
7. `ledgerClock = LedgerClock { Clock.System.now() }`（IMP-6，作为可用能力注入对象图，接入点后续）；
8. `useCase = ExecuteConfirmedManualExpense(port, idSource, factory)`（`ConfirmedManualExpense.kt:124-155` 三参构造）。

组合根同时构造最小占位 `LedgerCatalog`（匿名合成账户/分类，无产品数据；真实目录管理不在 P5-02 范围）供 factory 使用。database handle 不是组合根（`docs/ARCHITECTURE.md:55` 既有事实不变）。

### IMP-11 android 组合根构造顺序

`android-app` androidMain 组合根装配，顺序与 IMP-10 相同，差异仅为数据库与随机源：

1. `driver = AndroidSqliteDriver(LedgerDatabase.Schema, context, "ledger.db")`（Android system SQLite，应用私有库；既有 `ledger-data` android-driver 声明不变）；
2. 同 IMP-10 步骤 2-8（`LedgerDatabase(driver)`、port、factory、idSource——Android 侧 `java.security.SecureRandom`、ledgerClock、useCase）。
3. `MainActivity : ComponentActivity` + `setContent` 占位 UI（IMP-2）。

### IMP-12 本地测试账本物化方式

- **冻结：空库引导**（非固定测试 fixture 文件）——desktop 用本地文件路径或内存路径 + `LedgerDatabase.Schema.create(driver)`；android 用应用私有数据库（`AndroidSqliteDriver` schema 引导）。这使契约 §5 P5-02 判据 1「打开本地测试账本」可测（§8）。
- 迁移链与 schema 零改动；首次打开即当前 v27 schema（既有 `verifyCommonMainLedgerDatabaseMigration` 验证链不变，IMP-13）。

## 8. 测试与验收（契约 §5 P5-02 判据 1-5 逐条映射）

| 判据（契约 §5 P5-02） | 本批测试/人工门 | 断言明细 |
| --- | --- | --- |
| 1. Desktop：`gradlew :desktop-app:run` 启动占位界面并打开本地测试账本；一次经确认用例的手工支出在 JVM 账本中形成逐币种平衡分录 | 人工门：`:desktop-app:run` 启动占位界面并打开本地测试账本；`desktop-app` jvmTest `DesktopSkeletonSmokeTest` | 空库引导打开测试账本（IMP-12）；`ExecuteConfirmedManualExpense` 一次手工支出 → `Created`；confirmationId/transactionId 为规范 UUIDv7 文本（正则）；分录逐币种平衡——每个 `currency_code` 分组内 posting 的 `amount.minorUnits` 和为零（非全局求和）；计数精确——transaction、request、receipt 各恰一条，posting set 恰一个且含恰两条 posting（expense leg + payment leg）；同请求重放 → `NoChange` 且 receipt 不变（零重复） |
| 2. Android：APK 安装到本地模拟器并启动；同一路径经模拟器人工验收 | 人工门（本地模拟器工具链：安装 + 启动 + 人工检查，非 Gradle 命令）；`:android-app:assembleDebug` 构建 APK | APK 构建成功并安装；应用启动显示占位界面（模拟器人工检查） |
| 3. 组合根零核心逻辑复制；`ledger-data` 构建脚本与迁移链零改动 | 评审门 + `git diff --stat` 核对 | 两组合根只装配与占位 UI；`ledger-data`/`ledger-domain` 构建脚本与迁移链零改动（§10） |
| 4. 既有验证全绿：三模块 jvmTest、migration verify、`ktlintCheck`（新增模块纳入） | 命令门（§11 既有全套） | `:ledger-domain:jvmTest`、`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`、`ktlintCheck` 全部通过；ktlint 覆盖新增两模块 |
| 5. CI 含两个新模块的构建检查，并与 CONTRIBUTING 验证命令一致 | IMP-13 | ci.yml 与 CONTRIBUTING.md 同步（修改验证步骤时两者同步更新，`CONTRIBUTING.md:93-95`） |

### IMP-13 CI/CONTRIBUTING/README 接线

- `.github/workflows/ci.yml`：kotlin job 增 `:desktop-app:build`（如实施批拆分为 `:desktop-app:jvmTest` + 编译等更细任务，以实施批冻结为准，总效果等价 build）；android job 增 `:android-app:assembleDebug`（契约 §5 注记允许 `:android-app:build` 拆分更细任务）。
- `docs/CONTRIBUTING.md`：Kotlin 验证节增 `:desktop-app:jvmTest`（或 build）与 `:android-app:assembleDebug` 命令；环境描述增两新模块与运行命令（`gradlew :desktop-app:run`）；与 ci.yml 同步。
- `README.md`：环境描述同步（「仓库尚无 Android/Desktop app」段，`README.md:9,72` 更新为两模块已建立并有运行命令）。
- `docs/PROJECT_MAP.md`：模块表与验证入口同步新增两模块（模块/文档/机器工件/验证入口的导航投影，`CONTRIBUTING.md:100-101`）。
- 上述均属实施批交付的一部分，本批（规格）不改动任何既有文件。

## 9. 资源约束与风险（承接契约 R-1..R-6 + 新增）

外层约束（`docs/CONTRIBUTING.md:14-25`）不变：16 GB 主机 Gradle/Kotlin 验证串行、单 worker、1 GB heap、禁并发 Gradle、每次验证前后 `gradlew --stop`；CI 语义不变。

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | CMP `1.12.0` 新鲜度（2026-08-25 发布） | 新版本未知缺陷影响双端稳定 | UQ-1 已裁决 CMP `1.11.1`（IMP-1/IMP-2），升级路径已登记 |
| R-2 | 1.11 线多平台 material3 为 alpha（`1.11.0-alpha07`） | alpha API 面进入构建 | 仅占位组件使用；正式版本留皮肤批冻结（IMP-1 注记）。注：契约 UQ-1 方案 A 以「零 alpha 暴露」为特征，而 1.11 线多平台 material3 工件实为 alpha——该差异已登记并限定占位-only 使用，最终 M3 版本在皮肤批冻结；实施批开工时即解析并登记 `compose.material3` 访问器的实际解析坐标（构建期解析），作为实施起点事实 |
| R-3 | 皮肤主皮库单维护者风险 | 上游停滞或破坏性变更 | 皮肤批采用时复核（P5-01 §3.2）；本批零依赖 |
| R-4 | xerial sqlite-jdbc native 解压边角（只读临时目录场景未验证） | 特殊环境启动失败 | 版本锁定 `3.51.3.0`；`JdbcSqliteDriver` 可换任意 JDBC URL（F-2） |
| R-5 | 新增两模块拉长 16 GB 主机构建时间；CMP 首建下载 Skiko native；desktop 首次运行渲染（Skiko native 下载 + D3D/软件回退）在人工 run 门前未验证 | 验证耗时上升；首次渲染可能失败 | 串行 + 单 worker + 1 GB heap 继续适用；首建耗时一次性；`:desktop-app:run` 人工门为首次渲染验证点，失败可 `-Dskiko.renderApi` 诊断（P5-01 §3.1） |
| R-6 | Android 模拟器验收依赖本地模拟器工具链 | P5-02 验收门受阻 | 验收前确认本地模拟器可用（环境前提，非产品依赖） |
| R-7（新增） | 两组首次配对：CMP 1.11.1 desktop 运行时 × Kotlin 2.4.10 compose 编译器；AGP 9 内置 Kotlin 的 `com.android.application` 模块 × compose 插件（官方文档对后者无直接示例，为本批最大构建配置未知项） | 构建配置风险 | 研究已给出官方示例背书（§3）；实施批先做最小冒烟（smoke-first）：首个提交即含两模块空壳构建通过 |
| R-8（新增） | 1.11 线 M3 alpha 的 API 面随 1.12 稳定线可能变化 | 皮肤批升级成本 | 皮肤批再冻结（R-2）；占位组件不依赖高级 M3 API |
| R-9（新增） | AGP 9 + Compose 首建内存压力（16 GB 主机 1 GB heap） | 潜在 OOM | 如遇 OOM，实施批上报资源控制裁决，不静默改 CONTRIBUTING |
| R-10（新增） | `kotlin.time.Clock` 无 opt-in 稳定性是编译级事实，须由实施冒烟证明 | 若实现期出现 opt-in 需求则影响契约 F-4 端口形状 | 实施批早期检查点：冒烟编译尽早证明 `Clock.System.now()` 无需 opt-in；与契约不符则回修订门（D-096） |

## 10. 边界（明确不做）

- 零 schema/迁移变更：迁移链 `1.sqm`~`26.sqm`（v1→v27）零改动，`ledger-data` 构建脚本零改动。
- 零共享核心语义变更：`ledger-application` 仅新增 `LedgerClock` 端口、`UuidV7Generator` 纯生成器与 `UuidV7ConfirmedManualExpenseIdSource` 适配；不触碰既有用例、工厂、端口签名与持久化契约（IMP-4..IMP-9 明确对既有签名零改动）。
- `platform-android`/`platform-desktop` 不创建（契约 F-3 偏差延后）。
- 零皮肤实现：双皮肤选型证据已在 P5-01 §3.2 登记；皮肤主皮库依赖在 P5-02/P5-03 不进入构建（契约 §7）。
- 零发布打包（`jpackage` 延后）；零网络/同步/AI。
- `.external/` 零触碰（本批只保留门查阅声明）。
- 零真实数据：全部测试使用完全匿名的合成数据（CONTRIBUTING 隐私规则）。

## 11. 验收命令清单

契约 §5 P5-02 五命令 verbatim（外层约束按 CONTRIBUTING：串行 + 1 GB heap + `--max-workers=1`，一次一个）：

```powershell
.\gradlew.bat :desktop-app:build --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :desktop-app:run
.\gradlew.bat :android-app:build --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat ktlintCheck --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all
```

新增 focused 命令：

```powershell
.\gradlew.bat :desktop-app:jvmTest --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :android-app:assembleDebug --stacktrace --rerun-tasks --warning-mode all
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

既有全套（判据 4）：

```powershell
.\gradlew.bat :ledger-domain:jvmTest --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :ledger-application:jvmTest --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :ledger-data:jvmTest --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat check --rerun-tasks --warning-mode all
```

Android 模拟器验收使用本地模拟器工具链（安装 + 启动 + 人工检查），非 Gradle 命令。

## 12. 批准后动工路径

1. 用户批准本规格（`状态：proposal` → `approved`）。
2. 实施批：独立 worktree、单一 bounded writer、独立评审（spec 评审 + quality 评审）、distinct verifier、主代理最终验收（契约 §5 P5-02 判据 1-5 + §11 命令全绿）。
3. 合入 `main`；同步 `docs/ARCHITECTURE.md` 技术选择状态表（UI 行更新、app 运行命令描述）与 `docs/CURRENT_STATE.md`。
4. D-118 实施登记（`docs/DECISIONS.md`）。
5. 推送（仍须用户授权）。

## UQ 说明

无。本批无产品级开放问题：契约 §5 P5-02 验收判据与命令已批准，F-1..F-5 残余自由度全部由 IMP-1..IMP-13 钉死。实施中如发现承载缺口，回 P5-01 契约做修订与评审门（D-096 契约优先纪律），不静默变更。

## 边界断言（本批不含）

- 本批唯一写入 = 本新文件；`docs/specs/` 既有文档、`DECISIONS.md`、`ARCHITECTURE.md`、`CONTRIBUTING.md`、CI 与全部模块零改动。
- 本文件为实施规格草案：未经用户批准不构成实施授权；P5-02 实施仍在独立 worktree、单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径、个人数据、图标资产品牌名、agent/会话痕迹；`.external/` 内容除门查阅声明外零引用。
