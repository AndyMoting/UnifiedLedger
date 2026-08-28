# P5-01 双平台最小外壳契约（阶段 5 规划/证据批）

**状态：approved** — 本文件为 P5-01 设计契约（contract-only：零实现、零 schema、零生产代码）。2026-08-29 用户裁决 UQ-1 方案 A 与 UQ-2 保持拆分后批准，D-117 登记。

**Scope:** 冻结阶段 5「双端最小外壳」的开启契约。冻结对象为既定 P5 计划的四项：CMP 栈、桌面驱动、组合根契约与 Clock 端口细节（§4 F-1..F-4），并附带冻结产品 ID 端口的算法与语义（F-5，实装随 P5-02）；同时交付三个六维证据包（§3）、阶段 5 批次计划（§5）与待用户裁决清单（§6）。UI 皮肤实现、schema 与账务行为明确不在本批范围（§1、§7）。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `41509e8` 的行号；`.external/` 只读）：

- **架构与路线**：docs/ARCHITECTURE.md:21-34（目标模块边界表，含 `platform-android`/`platform-desktop`/`android-app`/`desktop-app` 行）、:36-57（依赖方向；:55 ID 与时钟为应用经端口消费的运行时能力，database handle 不是组合根）、:59-67（运行时能力与时间：ID 在持久化 `commitOnce` 原子首请求 callback 内惰性物化、当前源码无产品 Clock 端口、来源时间不可由运行时时间覆盖）、:138-156（技术选择状态表：「UI 与导航库」「依赖注入方案」「产品运行时 ID 算法」均为暂缓决定）；docs/ROADMAP.md:43-48（阶段 5 进入与完成条件）。
- **决定承接**：docs/DECISIONS.md:1530（D-099 六维技术选择模板）、:1857（D-114 mixed confirm 的 null explicitConfirmedAt 已知限制登记并延后）、:1869（D-115 ktlint 启用）、:1892（D-116，当前最后一个决定编号；本批批准后登记 D-117）。
- **批形状先例**：docs/specs/2026-08-28-bank-import-cmb-ccb-design.md（契约优先纪律 D-096/IMPORT-001、状态行与章节纪律、`docs/specs` 状态标记用法）；docs/CONTRIBUTING.md（正式文档中文为主、代码/API/命令保留英文；文档不含本机绝对路径与个人数据；16 GB 主机 Gradle 串行约束）。
- **源码锚点（只读检查）**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/Values.kt:60-73`；`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/LendingEvidence.kt:3`；`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedManualExpense.kt:12,30,45,59-61,110-121`；`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightConfirmedManualExpenseCommitPort.kt:23-79`；`ledger-data/build.gradle.kts:37-38,48,53`；`build.gradle.kts:2-5`；`gradle/wrapper/gradle-wrapper.properties`。
- **工具链锚点（现状，本批零升级）**：Kotlin Multiplatform 插件 `2.4.10`（build.gradle.kts:2）；`com.android.kotlin.multiplatform.library` `9.1.0`（build.gradle.kts:3）；SQLDelight `2.3.2`（build.gradle.kts:4）；ktlint 插件 `org.jlleitschuh.gradle.ktlint` `14.2.0`（build.gradle.kts:5，D-115）；Gradle Wrapper `9.5.0`；JDK 21；`ledger-data` `minSdk = 34` / `compileSdk = 36`（ledger-data/build.gradle.kts:37-38）。

术语：`本批` = P5-01；`组合根`（composition root）= 应用进程入口模块，把端口实现、持久化与应用用例装配为可运行对象；`最小外壳` = docs/ROADMAP.md 阶段 5 定义的可运行双端壳（打开本地测试账本、调用共享用例、持续通过构建检查）；`演示面` = 阶段 5 的最小可见功能面（用户裁决为 B：只读总览 + 一条手工支出写路径）；`双皮肤` = miuix 主皮 + Material 3 辅皮的 UI 外观层选型（实现归皮肤批）；`皮肤批` = 阶段 5 批次序列之外、由用户选定的前端模型拥有的 UI 皮肤实现批次；`六维` = D-099 模板的格式兼容/安全/跨平台/许可/维护/替换（对非 parser 技术自然映射为工具链与 API 兼容维度）。

## 1. 目的与范围

1. **批定义**：P5-01 = 阶段 5 的契约与证据批。交付物只有本文件：四项冻结决定（§4 F-1..F-4）、产品 ID 端口的算法与语义冻结（F-5）、三个六维证据包（§3）、P5 批次计划（§5）与待用户裁决清单（§6）。零实现、零 schema、零生产代码、零新增依赖。
2. **冻结对象（恰四项主交付）**：按既定 P5 计划冻结 CMP 栈（F-1）、桌面驱动（F-2）、组合根契约（F-3）与 Clock 端口细节（F-4）；另以 F-5 冻结产品 ID 的算法（UUIDv7）与端口语义，其实装随 P5-02。
3. **范围外（本批明确不做）**：
   - 实现：不创建 `android-app`/`desktop-app` 模块，不改动任何构建脚本、生产代码或 CI。
   - UI 皮肤实现：双皮肤（miuix 主皮 + Material 3 辅皮）的选型证据在本批登记（§3.2），皮肤实现归属后续由用户选定的前端模型拥有的皮肤批；阶段 5 最小壳只使用原生 Compose/Material3 占位最简 UI。
   - schema 与账务行为：迁移链零变更；不触碰任何确认、导入、对账语义；既有冻结 oracle 逐值不变。
4. **阶段定位**：docs/ROADMAP.md:43-48 阶段 5 完成条件（两端打开本地测试账本、调用共享用例、持续通过构建检查）由 P5-02/P5-03 承接；本批只解决其开启前提——技术栈与端口契约先冻结、后实装（D-096/IMPORT-001 契约优先纪律先例）。
5. **交付物清单与稳定 ID 约定**：本批交付物唯一 = 本文件。文中稳定 ID：冻结决定 `F-1..F-5`、待用户裁决 `UQ-1`/`UQ-2`、风险 `R-1..R-6`；后续批次与登记文档引用这些 ID，不重编号。

## 2. 既有裁决承接（批准后并入 D-117 登记）

以下为用户已作出的 P5 前置裁决；本批原文承接复述，不新增裁决。本文件获用户批准后，四项随本批冻结决定一并作为 D-117 内容登记：

1. **双皮肤**：miuix 主皮 + Material 3 辅皮。同一组件树可分别包裹在 `MiuixTheme {}` 与 `MaterialTheme {}` 中（§3.2 跨平台维度的共存证据）。皮肤实现归属皮肤批；本批只登记选型证据，不引入依赖。
2. **演示面 = B**：阶段 5 演示面 = 只读总览 + 一条手工支出写路径；写路径避开 mixed confirm，不激活 D-114（docs/DECISIONS.md:1857）登记的 null explicitConfirmedAt 已知限制。
3. **产品 ID = UUIDv7**（RFC 9562），随 P5-02 实装；本批以 F-5 冻结算法与端口语义。
4. **ktlint 已启用**（D-115，docs/DECISIONS.md:1869；插件 `org.jlleitschuh.gradle.ktlint` `14.2.0`，build.gradle.kts:5）：P5-02 起新增模块一并纳入 `ktlintCheck`。

**隐私规则（图标资产）**：皮肤批使用的图标资产为用户自有第一方图标资产（已授权图标资产）；其原始来源品牌名不得出现在任何 tracked 文件（包括本文件），只能以上述称谓指代。第三方项目名 miuix、Material 3 等作为许可证与技术事实可正常出现在 tracked 文件；该豁免不适用于图标资产品牌名。

**本批落点映射**（承接裁决 → 本批对应条款 → 实装归属）：

| 承接裁决 | 本批落点 | 实装归属 |
| --- | --- | --- |
| 双皮肤（miuix 主皮 + Material 3 辅皮） | §3.2 证据包；F-1 的 Material3 工件延后条款 | 皮肤批 |
| 演示面 = B（避开 mixed confirm） | §5 P5-03（D-114 已知限制保持休眠） | P5-03 |
| 产品 ID = UUIDv7（RFC 9562） | §4 F-5（算法与端口语义冻结） | P5-02 |
| ktlint 已启用（D-115） | §5 P5-02 验收第 4 项（新增模块纳入检查） | P5-02 |

## 3. 外部证据门记录

本批改变生产技术（平台集成与客户端 UI 栈），按 AGENTS.md 外部证据门触发：`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md` 已按门要求查阅（只读）；本文件只保留由此转化的中立契约与证据结论，不复制原始研究记录，`.external/` 内容零改动。证据时点：2026-08-29。三个证据包全部按 D-099 六维模板（docs/DECISIONS.md:1530）组织：格式兼容、安全、跨平台、许可、维护、替换。证据来源为官方发布页、官方文档、项目仓库与 OSV 数据库；全部转换为中立事实与契约条款，不复制上游文档正文。

### 3.1 CMP 工具链兼容矩阵

- **格式兼容（技术兼容）**：本节首组条目（版本事实、KGP/AGP/Gradle 兼容、JDK、插件配对约束、锚点逐项核对、结论、Windows 渲染）同属本维度；其余四维见本节末尾。
- **版本事实**（github.com/JetBrains/compose-multiplatform/releases）：CMP `1.12.0` 于 2026-08-25 发布（证据时点前 4 天）；官方配对声明为「Update versions to Compose 1.12.0 and Kotlin 2.4.10」。上一稳定线 `1.11.1` 于 2026-06-02 发布。
- **KGP/AGP/Gradle 兼容**（kotlinlang.org/docs/gradle-configure-project.html）：KGP `2.4.0`–`2.4.10` 支持 AGP `8.5.2`–`9.1.0`、Gradle `7.6.3`–`9.5.0`；仓库锚点 Kotlin `2.4.10`、AGP-KMP library 插件 `9.1.0`、Gradle `9.5.0` 均落在区间内。（developer.android.com/build/releases/about-agp）AGP `9.1` 要求 Gradle ≥ `9.3.1`、实测上限 `9.5`。（docs.gradle.org 官方兼容性矩阵，docs.gradle.org/current/userguide/compatibility.html）Gradle `9.5` 要求 JVM `17`–`26`，仓库 JDK 21 满足。
- **JDK**：CMP Desktop 运行时要求 JDK 11+，`jpackage` 打包要求 JDK 17+；仓库 JDK 21 两者均满足。
- **插件配对约束**：`org.jetbrains.kotlin.plugin.compose` 的版本必须与 Kotlin 插件版本一致（当前即 `2.4.10`）。
- **锚点逐项核对**：

| 仓库锚点 | 现状值 | CMP `1.11.1` 线要求 | CMP `1.12.0` 线要求 | 结论 |
| --- | --- | --- | --- | --- |
| Kotlin 插件 | `2.4.10` | 落在 KGP `2.4.0`–`2.4.10` 支持区间 | 官方配对「Compose 1.12.0 and Kotlin 2.4.10」 | 满足 |
| AGP-KMP library 插件 | `9.1.0` | 落在 AGP `8.5.2`–`9.1.0` 支持区间 | 同左 | 满足 |
| Gradle Wrapper | `9.5.0` | 落在 Gradle `7.6.3`–`9.5.0` 区间且 ≥ AGP 9.1 要求的 `9.3.1` | 同左 | 满足 |
| JVM | JDK 21 | Gradle `9.5` 要求 JVM `17`–`26`；CMP Desktop 运行时 JDK 11+ | 同左 | 满足 |
| `jpackage`（延后项） | 未使用 | 打包时要求 JDK 17+ | 同左 | JDK 21 满足（打包本身延后，F-1） |
| Android SDK | minSdk 34 / compileSdk 36 | 无 CMP 侧更高要求登记 | 同左 | 满足 |

- **结论：零工具链升级**。两个候选 CMP 线（`1.11.1` / `1.12.0`）的全部工具链要求均被仓库现有锚点满足；Kotlin `2.4.10`、AGP-KMP `9.1.0`、Gradle `9.5.0`、JDK 21、minSdk 34/compileSdk 36 全部保持不变。
- **Windows 渲染**：CMP Desktop 默认 Skia/Direct3D 渲染，失败时自动回退软件渲染（blog.jetbrains.com）；可用 `-Dskiko.renderApi` 覆盖。登记为诊断手段，非默认配置。
- **风险注记**：CMP `1.12.0` 发布仅 4 天（§8 R-1）；CMP `1.12.0` 的多平台 material3 工件为 alpha（`org.jetbrains.compose.material3:material3:1.12.0-alpha03`，基于 androidx material3 `1.5.0-alpha22`）——Material3 多平台工件的精确版本选择延后至皮肤批证据门冻结（F-1）。
- **安全**：CMP 工件为 JetBrains 第一方 Maven 发布物，构建期消费；运行期 UI 不引入网络访问、不引入动态代码加载；除 CMP desktop 工件自带的 Skiko 图形后端外不打包任何其他 native 代码。`org.jetbrains.compose` 工件的 OSV 记录本批不断言为零，留待 P5-02 依赖引入时复核登记。
- **许可**：`org.jetbrains.compose` 工件与 androidx.compose 均为 Apache-2.0（androidx/JetBrains 第一方）。该结论为本批必需项——docs/ARCHITECTURE.md:156 要求每项选择说明适用模块、许可证与替换成本后才能翻转技术选择状态行（§9 第 2 项）。
- **维护**：JetBrains 第一方维护；`org.jetbrains.compose` 插件线跟随 Kotlin 版本演进，发布节奏与版本事实同源（github.com/JetBrains/compose-multiplatform/releases）。
- **替换**：最小壳 UI 只消费基础 Compose/Material3 组件；后续替换 CMP 栈 = 重铺占位 UI + 更换 desktop 入口点，业务代码零耦合（端口与应用用例不依赖 UI，F-3/F-4）。

### 3.2 miuix 六维证据包

- **规范仓库与版本事实**：规范仓库 github.com/compose-miuix-ui/miuix（原 miuix-kotlin-multiplatform/miuix）。稳定版 `0.9.3`（2026-07-04）构建于 Kotlin `2.4.0` + CMP `1.11.1`；main 分支已升级 Kotlin `2.4.10` + CMP `1.12.0`（2026-08-26 commit）；`0.9.4-rc01`（2026-08-13）已发布，官方宣布稳定版 `0.9.4` 随 CMP `1.12.0` stable 之后发布。坐标 `top.yukonga.miuix.kmp:miuix-ui`；`0.9.x` 起模块化（core/ui/preference/icons/nav），旧单体工件止于 `0.8.8`。Android `minSdk 23`（0.9.3）/ `24`（0.9.4+），compileSdk 37。
- **格式兼容（技术兼容）**：minSdk 23/24 低于仓库 `minSdk 34`，满足；compileSdk 37 高于仓库当前 `compileSdk 36`，皮肤批进入时须复核 SDK/AGP 组合或版本选择（不阻塞本批——依赖不随阶段 5 进入构建，§7）。
- **安全**：无网络访问、无动态代码加载、无 native 代码；OSV 数据库 0 条记录。
- **跨平台**：目标平台 Android / JVM Desktop / iOS / macOS Native / JS / WasmJs；提供 desktop 示例与 hot-reload。传递依赖 = miuix-core/squircle + CMP foundation + kotlin-stdlib + androidx.navigationevent-compose `1.1.2` + JetBrains material3-window-size-class `1.9.0` + materialkolor material-color-utilities `4.1.1`。M3 共存有内证（miuix 自身依赖 JetBrains material3-window-size-class）；双皮肤形态 = 同一组件树分别包裹 `MiuixTheme {}` 与 `MaterialTheme {}`；动态取色经 `ThemeController(ColorSchemeMode.MonetSystem, ...)`。
- **许可**：Apache-2.0（项目本体与上列全部传递依赖）。
- **维护**：最后提交 2026-08-27；约 10 个月 20 个 release（2-6 周节奏）；issue 5 open / 30 closed；单维护者风险（约 74% commit 来自单一作者，API 自述 experimental）。
- **替换**：替换 `MiuixTheme` 包裹层并把组件映射回 Material3；一行回退（主题包裹层互换），无深度耦合。
- **批归属注记**：本证据包只服务于双皮肤选型的事实基础；miuix 依赖在 P5-02/P5-03 不进入构建，随皮肤批经各自证据门进入（§7）。

### 3.3 桌面驱动六维证据包

- **推荐物**：`app.cash.sqldelight:sqlite-driver:2.3.2`（JDBC 形态，经 `JdbcSqliteDriver`）。
- **格式兼容（技术兼容）**：SQLDelight 官方 `2.3.2` 文档（github.com/sqldelight/sqldelight，docs/jvm_sqlite 与 docs/multiplatform_sqlite）规定的按目标驱动模式恰为本推荐：androidMain = android-driver、jvmMain = sqlite-driver、nativeMain = native-driver；native-driver 无 jvm 变体（对 Desktop 不适用）。传递依赖 `org.xerial:sqlite-jdbc:3.51.3.0`，其 SQLite `3.51.3` ≥ `3.35.0`，满足仓库迁移链的 `ALTER TABLE DROP COLUMN` 硬要求（docs/ARCHITECTURE.md:7）。
- **安全**：OSV 数据库——sqlite-jdbc 仅 CVE-2023-32697（`3.41.2.1` 已修复；`3.51.3.0` 不受影响）、sqlite-driver 0 条记录。xerial 将平台 native 库打包进 jar 并在运行期解压提取（供应链：锁定版本；只读临时目录场景未验证，§8 R-4）。
- **跨平台**：仅 jvmMain 作用域；Android 侧 `app.cash.sqldelight:android-driver:2.3.2` 声明原样不动（ledger-data/build.gradle.kts:48）。仓库既有事实：jvmTest 已使用 `app.cash.sqldelight:sqlite-driver:2.3.2`（ledger-data/build.gradle.kts:53）——Desktop 化只新增 `jvmMain` 一条 implementation 依赖（置于 `desktop-app`，F-2），`ledger-data` 构建脚本零改动。
- **许可**：Apache-2.0（sqlite-driver 与 sqlite-jdbc 两份 POM 均已核）。
- **维护**：SQLDelight `2.3.2`（2026-03-16 发布，约半年节奏）；xerial 非常活跃（`3.53.4.0`，2026-08-26）。
- **替换**：`JdbcSqliteDriver` 接受任意 JDBC URL（更换连接形态成本低）；版本约束一行可升。

## 4. 冻结决定

### F-1 CMP 栈（版本配对见 §6 UQ-1）

- **决定**：双端 UI 栈 = Compose Multiplatform。插件对 = `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose`（后者版本恒等于 Kotlin 插件版本，当前 `2.4.10`）；Desktop 入口经 `compose.desktop.application` 配置。`jpackage` 安装器打包明确延后——阶段 5 验收只使用 Gradle run/build 任务。CMP 版本配对 = UQ-1（推荐默认：方案 A，CMP `1.11.1`）。Material3 多平台工件版本选择延后至皮肤批证据门（§3.1 alpha 注记）。UI 皮肤实现不在阶段 5 最小壳内：占位最简 UI（Text/Button 级原生组件）。
- **理由**：docs/ARCHITECTURE.md 客户端策略已确定 Android 与 Desktop 共享完整业务核心并保持最小可运行外壳；CMP 是 KMP 共享核心之上的双端 UI 栈，最小壳阶段只消费其基础组件；工具链零升级（§3.1）；两条稳定线均有回退余地（`1.11.1`，2026-06-02）。
- **备选与落选理由**：非 CMP 的独立桌面 UI 框架落选——需为 Desktop 重复实现界面层并另接一遍用例调用，违背既定共享核心策略；CMP `1.12.0` 直接冻结（UQ-1 方案 B）作为备选登记，落选理由见 §6。
- **风险登记**：§8 R-1（`1.12.0` 新鲜度）、R-2（M3 多平台 alpha 工件）。

### F-2 桌面驱动

- **决定**：Desktop 持久化驱动 = `app.cash.sqldelight:sqlite-driver:2.3.2`，置于 `desktop-app` 的 `jvmMain`（确切模块放置随 P5-02 落地）；Android 侧驱动声明零改动。
- **理由**：官方按目标驱动模式（§3.3）；SQLite `3.51.3` 满足 `ALTER TABLE DROP COLUMN` 硬要求；jvmTest 已在同一版本上长期使用，行为面已知。
- **备选与落选理由**：native-driver 无 jvm 变体，不适用；Android system SQLite driver 为 Android 专属，不适用于 Desktop；绕过 SQLDelight 直连其他 SQLite 绑定落选——破坏既有持久化端口与迁移验证链。
- **风险登记**：§8 R-4（xerial native 解压边角）。

### F-3 组合根契约

- **决定**：
  - 新建 Gradle 模块 `android-app` 与 `desktop-app`（docs/ARCHITECTURE.md:33-34 目标边界）：只做组合根（装配端口实现、持久化与应用用例）与占位界面；只调用应用用例，不得复制共享核心逻辑。
  - `platform-android`/`platform-desktop` 在最小壳阶段不创建（有意偏差延后）：最小壳所需的少数端口（Clock、ID 生成、驱动装配）由组合根直接实现，待真实系统集成需求（权限、通知、文件采集等）出现时再独立建模块承接（docs/ARCHITECTURE.md:31-32）。该延后与本表目标边界的一致性随本批登记，评审批不得视作对目标边界表的静默修改。
  - 依赖注入框架与导航库维持暂缓决定（docs/ARCHITECTURE.md:148-149）：组合根手工构造对象图；界面为占位最简 UI，不引入导航库。
  - 既有事实保持：`ledger-data` 的 Android database handle 只是数据装配，不是 app 组合根（docs/ARCHITECTURE.md:55）。
- **理由**：阶段 5「最小外壳」的完成条件只需要可运行双端、共享用例调用与构建检查；platform-* 模块此刻没有真实职责可承载。依赖方向保持 `android-app`/`desktop-app` → `ledger-application`（→ `ledger-domain`），并在组合根接入 `ledger-data`，不引入反向依赖。
- **备选与落选理由**：一步到位创建 platform-* 空模块落选——空模块无端口可承载，只增加构建时间与维护面（16 GB 主机构建预算敏感，§8 R-5）；把应用入口挂进既有三个 library 模块落选——违背目标模块边界表（docs/ARCHITECTURE.md:33-34），并使共享库依赖 UI 栈。
- **风险登记**：§8 R-5（新增模块拉长构建）；偏差延后已随本批显式登记。

### F-4 Clock 端口契约

- **决定**：
  - 端口归属 `ledger-application`（commonMain，应用层拥有的端口）；平台实现 = 系统时钟，在各组合根装配；测试/golden 使用确定性可注入实现。
  - 端口只供应处理、创建、确认与审计事件自身的时间；来源发生、支付、入账、起息和观察时间是不可变来源事实，一律不由 Clock 补写或覆盖（docs/ARCHITECTURE.md:67；任何 adapter、用例或 Store 均不得违反）。
  - **端口形状（本批冻结）**：返回类型 = `kotlin.time.Instant`，与共享源码既有时间表示一致；单一无参读取方法，每次调用返回读取时刻的值；端口不携带时区/偏移语义（zone 换算属界面/平台关注点）。方法命名与最终签名随 P5-02 实装冻结。
- **现状证据（形状依据）**：共享源码全部使用 Kotlin 标准库 `kotlin.time.Instant`（非 kotlinx-datetime）：`Values.kt:60-73`（`TransactionTimes(occurredAt/statisticsAt/effectiveAt: Instant)` 与 `collapsed(instant)` 工厂）；`LendingEvidence.kt:3` 等（全库 `import kotlin.time.Instant`）；`ConfirmedManualExpense.kt:12,30,45`（应用层请求字段同类型）；持久化以 `Instant.toString()` 文本落盘（`SqlDelightConfirmedManualExpenseCommitPort.kt:43,149-151`）。构建依赖中无 kotlinx-datetime（全仓 grep 零命中）。当前源码没有产品 Clock 端口（docs/ARCHITECTURE.md:63）——本批只冻结其契约，不改动任何现状。
- **理由**：端口返回既有类型使共享用例零类型转换；最小表面（单方法）满足最小壳阶段处理/创建/确认/审计时间的全部读取需求。确认类用例当前以显式请求字段承载确认事实（如 `ExplicitlyConfirmedManualExpense`，`ConfirmedManualExpense.kt:24-33`），Clock 端口的接入点由各用例签名在 P5-02/P5-03 决定；端口契约本身不改变任何既有用例签名。
- **备选与落选理由**：kotlinx-datetime `Clock` 落选——引入与既有表示不同的类型体系并需全库转换，共享核心零此依赖；端口放 `ledger-domain` 落选——Clock 是运行时能力，ARCHITECTURE 定位其为应用经端口消费的能力（docs/ARCHITECTURE.md:55）；返回含时区/偏移的富时间结构落选——共享核心统一以 `Instant` 表示与交换时间值，富结构属界面层。
- **风险登记**：无新增外部风险（纯应用层端口）；确定性实现只用于测试/golden，不进入产品装配。

### F-5 ID 端口契约（算法与语义冻结，实装随 P5-02）

- **决定**：产品随机 ID = UUIDv7（RFC 9562）；生成保持惰性——只在持久化 `commitOnce` 的原子首请求 callback 内物化（docs/ARCHITECTURE.md:63 既有不变量）。精确重放、identity conflict 与并发失败方永不消耗 ID（既有 KDoc 契约：`ConfirmedManualExpense.kt:110-113`「`createFormalTransaction` MUST be invoked at most once」；持久化实现 `SqlDelightConfirmedManualExpenseCommitPort.kt:51-55`：claim 失败走 `resolveExisting`，仅 claim 胜者调用 callback）。本批只冻结算法与端口语义；生成器实装、文本形态与接缝命名随 P5-02 冻结。**迁移策略**：当前不存在任何已发布客户端或产品存量 ID 数据（仓库无 app 模块、schema 仅产品账务库），UUIDv7 引入无需数据迁移；将来算法变更按版本替换语义另行立批，不静默切换。
- **理由**：UUIDv7 是 RFC 9562 标准的时间排序随机 UUID，无需协调方；惰性物化不变量已被既有 spine 与用例测试证明。P5-02 只是在既有 `*IdSource` fun interface 接缝（如 `ConfirmedManualExpenseIdSource`，`ConfirmedManualExpense.kt:59-61`）背后填入产品实现，端口消费方式零改动。
- **备选与落选理由**：UUIDv4 落选（无时间排序，索引局部性差）；ULID/雪花类方案落选（非 RFC 标准 ID 语义或需要协调方）；请求入口立即预生成落选——重放/冲突路径将无谓消耗 ID，改变既有「失败方零消耗」断言。
- **风险登记**：随机源必须来自平台安全随机数，实现属组合根/平台侧（P5-02）；Golden v2 命名空间与名字布局不是产品默认、零改动（docs/ARCHITECTURE.md:65）。

### 冻结与实施批边界（残余自由度清单）

每项冻结决定显式留给实施批（P5-02）钉死的残余自由度如下；实施批不得将其扩大为契约变更，实施中发现承载缺口须先回本批做契约修订与评审门，不得静默变更（D-096 契约优先纪律）：

- **F-1**：CMP 版本（UQ-1 裁决后固定）、desktop 主类名、`compose.desktop.application` 块内的具体参数。
- **F-2**：驱动声明在 `desktop-app` 构建脚本内的确切位置与写法。
- **F-3**：两个模块的目录布局与组合根对象图的构造顺序；本地测试账本的物化方式（空库引导或固定测试 fixture）——钉死后使 P5-02 验收判据「打开本地测试账本」可测。
- **F-4**：Clock 端口的方法命名与最终签名。
- **F-5**：UUIDv7 的文本形态（大小写/连字符规范形式）、生成器类型与注入位置。

## 5. P5 批次计划

编号是规划结构，不构成对后续批次的批准；每批仍须各自过证据门、独立评审与用户裁决。

### P5-01（本批）：契约与证据

- 内容：仅本文件（§1）。零代码、零 schema、零依赖。
- 验证：文档门 `$env:PYTHONPATH="tools\python"` 后 `python -m project_docs .`。

### P5-02 双平台骨架（契约依据 F-1..F-3、F-5；实施批，待各自门）

- 内容：创建 `android-app` + `desktop-app` 两个 Gradle 模块与组合根；UUIDv7 与 Clock 端口的产品实现及测试用确定性实现；Desktop 应用经 Gradle run 启动并打开本地测试账本（JVM）；Android 应用安装并在本地模拟器启动；共享核心只经应用用例调用；构建检查接入 CI（`.github/workflows/ci.yml` 与 `docs/CONTRIBUTING.md` 同步更新）；schema 零变更。`platform-android`/`platform-desktop` 不创建（承接 F-3 的偏差延后）。
- 验收标准：
  1. Desktop：`gradlew :desktop-app:run` 启动占位界面并打开本地测试账本；一次经确认用例的手工支出在 JVM 账本中形成逐币种平衡分录。
  2. Android：APK 安装到本地模拟器并启动；同一路径经模拟器人工验收。
  3. 组合根零核心逻辑复制；`ledger-data` 构建脚本与迁移链零改动。
  4. 既有验证全绿：三模块 jvmTest、migration verify、`ktlintCheck`（新增模块纳入检查范围）。
  5. CI 含两个新模块的构建检查，并与 `docs/CONTRIBUTING.md` 验证命令一致。
- 验证命令（均由 P5-02 引入，当前尚不存在；外层约束按 docs/CONTRIBUTING.md 串行 + 1 GB heap + `--max-workers=1`）：

```powershell
.\gradlew.bat :desktop-app:build --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :desktop-app:run
.\gradlew.bat :android-app:build --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat ktlintCheck --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all
```

  Android 模拟器验收使用本地模拟器工具链（安装 + 启动 + 人工检查），非 Gradle 命令；`:android-app:build` 可按实施批拆分为 `assembleDebug` 等更细任务，以实施批冻结为准。

### P5-03 演示面 B 最小 UI（待各自门）

- 内容：只读总览 + 一条手工支出写路径（经已批准的确认用例）；避开 mixed confirm，D-114 已知限制保持休眠（docs/DECISIONS.md:1857）；仅占位样式——双皮肤（miuix 主皮 / Material 3 辅皮）归属皮肤批。
- 验收标准：总览正确渲染账本状态；写路径在双端各形成恰好一笔正式交易（分录逐币种平衡；同请求重放 no_change，零重复提交）；写路径复用既有确认用例语义（`ExecuteConfirmedManualExpense`，ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ConfirmedManualExpense.kt:124），零新增 record kind、零新增诊断码、零 schema 变更；既有全部套件零改动全绿。
- 验证命令：`.\gradlew.bat check --rerun-tasks --warning-mode all`（既有）+ 双端人工演示路径检查（复用 P5-02 引入的 run/模拟器通道）。

### 资源约束（全批次适用）

16 GB Windows 主机：Gradle/Kotlin 验证串行执行、单 worker、1 GB heap、禁并发 Gradle；每批验证前后 `.\gradlew.bat --stop`（docs/CONTRIBUTING.md 约束，仅限本机资源控制，不改变 CI 语义）。

## 6. UQ 待用户裁决清单

### UQ-1 CMP 版本配对（F-1 的唯一开放参数）

- **方案 A（推荐）**：冻结 CMP `1.11.1`（2026-06-02）+ miuix `0.9.3`（2026-07-04）。成熟稳定配对：两者均已发布数月、配对经受时日检验、零 alpha 暴露；登记升级路径——CMP `1.12.0` + miuix `0.9.4` stable 可用后经小批升级。
- **方案 B**：冻结 CMP `1.12.0`（2026-08-25）+ miuix `0.9.3`。代价：CMP 侧依赖 4 天新版本的稳定线二进制兼容承诺，miuix 侧依赖其 `1.11.1` 构建对 `1.12.0` 运行时的向前兼容；收益：减少一次近期升级、直接对齐 miuix main 的配对方向。
- **权衡（中立陈述）**：A 的风险是已知且小的（版本旧但稳定）；B 的风险是不可知的（新版本的未知缺陷）。最小壳只消费基础组件，两方案功能面等价。
- **推荐**：A。阶段 5 的目标是可运行外壳而非版本先进性；升级路径已登记，A 不构成锁死。
- **两方案共同点**：无论 A/B，`org.jetbrains.kotlin.plugin.compose` 恒为 `2.4.10`、全部仓库工具链锚点零升级（§3.1 矩阵）；升级到 CMP `1.12.0` 线时同样零工具链升级。
- **时点快照注记**：两方案中的 miuix 版本配对均为 2026-08-29 时点快照；miuix 依赖实际进入构建在皮肤批，届时按 §3.2 与 §8 R-3 复核维护状态与版本配对后再固定依赖版本。

### UQ-2 批次边界：P5-02/P5-03 拆分确认

- **方案 A（推荐）**：保持拆分——P5-02 骨架（纯装配与构建接入，零 UI 逻辑、零 schema）与 P5-03 演示面 B（首条写路径 UI）各自过门。理由：骨架批的验收是构建与启动，演示面批的验收是行为与账务结果；分离使零 schema 断言独立成立，评审面更小。
- **方案 B**：合并为单批（骨架 + 演示面）。收益：少一轮门与评审；代价：单批同时引入模块、驱动、端口实装与写路径 UI，验收矩阵显著变大。
- 请裁决 A 或 B。

## 7. 边界（明确不做）

- 零 schema/迁移变更；零账务/对账行为变更（既有冻结 oracle 逐值不变）。
- 零 UI 皮肤实现：miuix 依赖在 P5-02/P5-03 不进入构建；P5-02/P5-03 只使用原生 Compose/Material3 占位 UI。miuix 采用决定在本批只完成证据登记（§3.2），实际依赖随未来皮肤批经各自证据门进入构建。
- 零网络/同步/AI；零安装器/发布打包（`jpackage` 延后）。
- `.external/` 零触碰（本批只保留门查阅声明，不复制其内容）。
- 除冻结栈（CMP 运行时及其传递依赖）外零新增第三方依赖；miuix 与图标资产均不在阶段 5 批次内进入构建。
- 依赖注入与导航库维持暂缓决定（docs/ARCHITECTURE.md:148-149），不因本批翻转。
- 既有决定零改动：D-098/D-099/D-103/D-112/D-113/D-114/D-116 的全部语义与本批正交。

## 8. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | CMP `1.12.0` 发布仅 4 天（2026-08-25） | 新版本未知缺陷影响双端稳定 | UQ-1 推荐方案 A（`1.11.1`）；升级路径已登记；§3.1 兼容矩阵证明两条线均零工具链升级 |
| R-2 | CMP `1.12.0` 的多平台 material3 工件为 alpha（`1.12.0-alpha03`，基于 androidx material3 `1.5.0-alpha22`） | 皮肤批若选 `1.12.0` 线将引入 alpha UI 依赖 | Material3 工件版本选择延后至皮肤批证据门（F-1）；最小壳只用基础组件 |
| R-3 | miuix 单维护者（约 74% commit 来自单一作者）、API 自述 experimental | 上游停滞或破坏性变更 | 替换维：`MiuixTheme` 包裹层一行回退到 Material3，无深度耦合；采用时点在皮肤批，届时复核维护状态 |
| R-4 | xerial sqlite-jdbc 将平台 native 库打包进 jar 并在运行期解压（只读临时目录场景未验证） | 特殊环境启动失败 | 版本锁定 `3.51.3.0`（供应链）；必要时 `JdbcSqliteDriver` 可换任意 JDBC URL（替换维） |
| R-5 | 新增 `android-app`/`desktop-app` 拉长 16 GB 主机的 Gradle 构建时间 | 验证耗时上升 | 串行 + 单 worker + 1 GB heap 约束继续适用；platform-* 模块延后创建（F-3）控制模块数 |
| R-6 | Android 模拟器验收依赖本地模拟器工具链 | P5-02 验收门受阻 | 验收前确认本地模拟器可用；该工具链属环境前提，非产品依赖 |

## 9. 批准后的登记路径

用户批准本文件后，全部登记动作由独立登记批执行（本批不改动任何既有文件）：

1. `docs/DECISIONS.md` 登记 **D-117**：内容 = §2 四项承接裁决 + §4 F-1..F-5 + UQ-1/UQ-2 裁决结果。
2. `docs/ARCHITECTURE.md` 技术选择状态表（docs/ARCHITECTURE.md:138-156）同步：「UI 与导航库」行部分翻转（CMP 栈结论入行；皮肤库与导航库结论仍待后续批）、「当前正式持久化边界的数据库与迁移」行补记 Desktop driver 结论、「产品运行时 ID 算法」行翻转为已确定（UUIDv7，P5-02 实装；当前无产品存量 ID 数据、无需迁移，将来算法变更按版本替换语义另行立批）；「依赖注入方案」维持暂缓。同一批须同步修正 docs/ARCHITECTURE.md:7 的过期 schema 版本叙述（仍写 v21/20 个迁移文件，仓库现处 v27），使其与 :147 行登记后的结论一致，避免表内与正文携带互相矛盾的 schema 版本。
3. `docs/CURRENT_STATE.md` 与（如存在）`docs/WORK_PLAN.local.md` 同步批次状态；`docs/CONTRIBUTING.md` 与 CI 的命令同步属 P5-02 实施批。
4. 本文件状态行由 `proposal` 翻转为 `approved`，随后 P5-02 方可开批。

## 边界断言（本批不含）

- 本批唯一写入 = 本新文件；`docs/specs/` 既有文档、`DECISIONS.md`、`ARCHITECTURE.md`、CI 与全部模块零改动。
- 本文件为契约草案：未经用户批准不构成实施授权；P5-02/P5-03 实施仍在独立 worktree、单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径、个人数据、图标资产品牌名、agent/会话痕迹；`.external/` 内容除门查阅声明外零引用。
