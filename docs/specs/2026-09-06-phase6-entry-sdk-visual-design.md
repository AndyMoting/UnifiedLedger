# 阶段 6 入口证据包与 SDK/视觉决策规格

**状态：** approved

**Scope:** 本文件是阶段 6 的入口证据批交付物（contract/decision-only：零实现、零 schema、零依赖、零 CI 变更）。交付四组内容：外部证据门记录（§3，E-1..E-10）、六项决定（§4，P6-D1..P6-D6）、D1 实施批冻结范围（§5）、批准后的登记路径（§8）。targetSdk 37 升级、主皮库采用与玻璃/Backdrop 实现细节明确不在本批（§6，归 D2/D3 或另立批）。本文件由 2026-09-06 的阶段 6 入口本地规划触发（该规划为本地文档，不入库）；其全部要求已中立化后重述于本文件，正文不依赖任何未跟踪文件。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线的行号；`.external/` 只读）：

- **路线权威**：docs/ROADMAP.md:62-67（阶段 6 定义，逐字）：

  > 在阶段 5 稳定基线上完成 Android 视觉和平台适配。包括 SDK/工具链证据门、以 `compileSdk 37` 为目标（minSdk 34 保持）；`targetSdk 37` 只有在官方兼容性证据、行为审计和 Android 14-17 回归全部通过后才升级，未通过则保持 targetSdk 36。阶段 6 再验证稳定主皮库双主题、AndroidLiquidGlass/Backdrop、Material3 回退路径，以及 Android 14-17 的设备、性能和人工验收；阶段 5 不提前修改这些依赖。
  >
  > - 进入条件：阶段 5 完成，SDK/工具链与目标依赖的官方证据门通过，且有可重复的 Android 基线验收。
  > - 完成条件：Android 14-17 设备上的视觉功能或稳定 Material3 回退路径、平台证据和性能证据通过验收；玻璃效果失败但回退路径稳定时允许阶段 6 收口，且不阻塞核心账务流程。

  本规格满足进入条件中的「官方证据门」要素（§3）；「可重复的 Android 基线验收」由 2026-09-06 人工门提供（§2 第 6 项）。
- **决定承接**：docs/DECISIONS.md:1919（D-117，CMP 1.11.1 栈冻结 + miuix 0.9.3 选型证据）、:2122（D-128，edge-to-edge 修复与证据登记纪律；E2E-R-001/E2E-R-002 首登记）、:2156（D-130，阶段 5 收口与 API 36 回归门）、:2176（D-131，已知卫生项登记延后风格）。
- **开发与验证规范**：docs/CONTRIBUTING.md:14-25（16 GB 主机 Gradle 串行/单 worker/1 GB 约束）、:27-29（本机与 CI 验证分工）、:151-153（验证命令与 ci.yml 同步规则）。
- **工具链锚点（现状，D1 批仅升 compileSdk）**：

| 锚点 | 现状值 | 位置 |
| --- | --- | --- |
| Kotlin Multiplatform 插件 | `2.4.10` | build.gradle.kts:2 |
| AGP-KMP library 插件 | `9.1.0` | build.gradle.kts:3 |
| SQLDelight | `2.3.2` | build.gradle.kts:4 |
| ktlint 插件 | `14.2.0` | build.gradle.kts:5 |
| Compose Multiplatform | `1.11.1` | build.gradle.kts:6 |
| Compose 编译器插件 | `2.4.10`（恒等 Kotlin 插件，D-117 F-1） | build.gradle.kts:7 |
| Gradle Wrapper | `9.5.0` | gradle/wrapper/gradle-wrapper.properties:3 |
| JDK | `21` | .github/workflows/ci.yml（java-version 21） |
| `android-app` | minSdk 34 / compileSdk 36 / targetSdk 36 | android-app/build.gradle.kts:28-29,24 |
| `app-ui` | minSdk 34 / compileSdk 36 | app-ui/build.gradle.kts:36-37 |
| `ledger-data` | minSdk 34 / compileSdk 36（minSdk 34 理由：v13→v14 的 `ALTER TABLE DROP COLUMN` 需 SQLite ≥ 3.35.0，Android 系统 SQLite 自 API 34 满足） | ledger-data/build.gradle.kts:35-38 |
| CI android job | `:ledger-data:compileAndroidMain` + `:android-app:testDebugUnitTest` + `:android-app:assembleDebug` + APK artifact；无模拟器、无 connectedAndroidTest | .github/workflows/ci.yml:49-78 |

- **主题/平台现状锚点（只读检查，缺陷登记见 P6-D6）**：`android-app/src/main/res/values/themes.xml:3`（`Theme.UnifiedLedger` parent=`android:Theme.Material.NoActionBar`）；`android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt:13`（`enableEdgeToEdge()`，D-128）；`android-app/src/main/kotlin/com/unifiedledger/android/App.kt:88`（根级 `Box(Modifier.fillMaxSize().statusBarsPadding())`，D-128）；`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:199`（`MaterialTheme { }` 未传 colorScheme）。

术语：`D1` = 阶段 6 实施批序列第 1 批「SDK/工具链批」（§5）；`D2` = 第 2 批「主题与组件批」（双主题 + 候选玻璃效果 + Material3 fallback）；`D3` = 第 3 批「平台回归批」（Android 14-17 设备、性能与人工验收）；`E-n` = §3 证据条目；`P6-Dn` = §4 决定；`R-n` = §7 风险；`ps16k` = 16 KB 页大小的系统镜像变体。

## 1. 目的与范围

1. **批定义**：阶段 6 入口证据批 = 修改任何 SDK、主题或新增任何依赖之前必须形成的可审查证据包与决策集（阶段 6 进入条件的「官方证据门」要素）。交付物唯一 = 本文件：E-1..E-10（§3）、P6-D1..P6-D6（§4）、D1 实施批冻结范围（§5）。零代码、零 schema、零新依赖、零 CI 变更、零主题修复。
2. **冻结对象**：§4 六项决定（compileSdk 37 升级、targetSdk 36 维持与升级触发、视觉回退架构、CI 矩阵边界、性能判据、主题缺陷登记）与 §5 D1 批的冻结写入路径、验证命令与验收标准。
3. **范围外**：targetSdk 37 升级、miuix 采用/整套换肤、玻璃/Backdrop 依赖引入与实现细节、主题缺陷修复（全部见 §6）；API 35/37 模拟器回归的执行（属 D3 或独立验证批）。
4. **阶段定位**：阶段 5 已按 D-130 收口（2026-09-03）；本批开启阶段 6。阶段 6 的完成判据（§3 ROADMAP 逐字引用）由 D2/D3 及阶段收口批承接，本批不预先裁决其结果。
5. **交付物清单与稳定 ID 约定**：本批交付物唯一 = 本文件。文中稳定 ID：证据 `E-1..E-10`、决定 `P6-D1..P6-D6`、风险 `R-1..R-6`、主题缺陷 `P6-ENTRY-THEME-001/002`、实施批 `D1/D2/D3`；后续批次与登记文档引用这些 ID，不重编号。

## 2. 既有裁决承接（批准后随 DECISIONS.md 登记批并入）

以下为既有决定与已核验事实的承接复述；本文件不新增对它们的裁决，冲突处置显式声明：

1. **D-117（P5-01）**：CMP `1.11.1` 栈冻结（Kotlin `2.4.10` + `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose` 恒等配对）、miuix `0.9.3` 选型证据登记（Apache-2.0、与 Kotlin 2.4.x/CMP 1.11.1 精确配对；依赖不随 P5 进入构建，采用归未来皮肤批经各自证据门）。本批承接其全部工具链锚点；P6-D3 对阶段 6 视觉架构的裁决不推翻该证据的历史有效性，也不关闭未来皮肤批方向。
2. **D-128**：平台集成缺陷的登记纪律——中性事实 + 根因到文件/行 + 官方 URL 中性依据 + 实机人工门复验；E2E-R-001（浅色状态栏对比度）与 E2E-R-002（API 34 未验证面）首登记于此。本批 §3/§4 沿用同一纪律。
3. **D-130**：阶段 5 收口（Android API 36 模拟器 11 项门 + Desktop 8 项门，CI APK 逐 SHA 核验）；P5-04.5-FOUND-001 登记并延后至独立决策批（该 FOUND-001 决策批与本批并行、零交集）。阶段 6 进入条件第一要素「阶段 5 完成」由 D-130 提供。
4. **D-131**：已知卫生项「登记延后、随触及批处置」的登记风格（先例：kotlinx-datetime 弃用 API）；P6-D6 的缺陷登记沿用该风格。
5. **D-125 边界段**：「P6 拥有视觉」。阶段 6 拥有视觉与主题实现权；D-125/D-126/D-129 交付的交互与账务状态机语义（编辑流状态机、Back/Esc 语义、fail-closed 重试、「权威刷新恒回首页」）零改动是阶段 6 全部批次的硬边界。
6. **2026-09-06 人工门（本地证据，不入库）**：关闭按钮人工确认 **PASS——确认为 API 36 模拟器（Android 16）面**：以 CI APK（提交 `98fc242` 工件，SHA-256 `3a8df9348bc7c03a5e2489320c59f51a4c3842c76129daecaddb4314e60f40c5`）经 UI 自动化执行，依据本地规划 §C.1「使用现有 Android 16 APK 完成关闭按钮人工确认」、按当前目标的执行委派完成；D-128 原文「用户 Android 16 实机最终确认」已由该 §C.1 的执行方式承接（模拟器面），如需实机复验由用户保留触发。E2E-R-002 API 34 回归全项 PASS 并关闭；E2E-R-001 显式验证 FAIL，定位并登记 `P6-ENTRY-THEME-001/002`（修复归 D2）；D-131 TalkBack 走查 PASS。其截图与像素证据存于本地工件目录 `local/artifacts/p6-gates/`（不入库）；中性结论经 P6-D6 转入本规格。D-131 遗留清单的其余人工门项（模拟器选择器全流程、系统返回、桌面 Esc、Android ICU tzdb 抽查）不在本规格记录范围：TalkBack 走查已关闭（见上），桌面 Esc 门由同一目标下单独执行，其余项随触及批处置。
7. **口径冲突处置（Material3 映射）**：E-3 官方文档映射与 D-127 登记的实际解析工件存在口径差异；本规格不裁决孰是孰非，要求 D1 批以实际解析工件复核（P6-D1 理由段）。

## 3. 外部证据门记录

本批改变平台集成与生产技术决策（SDK 目标、视觉技术方向），按 AGENTS.md 外部证据门触发：`docs/SOURCE_REFERENCES.md`、`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md` 已按门要求查阅（只读）；本文件只保留由此转化的中立事实与决策条款，不复制原始研究记录，`.external/` 内容零改动。证据时点：2026-09-06。来源为官方文档与官方/维护中项目仓库发布页。

### 3.1 SDK/工具链证据

- **E-1 平台与 AGP 基线**：Android 17（API 37）stable 已于 2026-06-16 发布；`compileSdk 37` 需要 AGP ≥ 9.1；AGP 9.1.1 明示「supports Android API level 37.0 and below」；AGP 9.1 要求 Gradle ≥ 9.3.1、JDK ≥ 17、Build Tools 36.0.0。仓库现状 AGP `9.1.0`、Gradle `9.5.0`、JDK 21 全部满足，升 compileSdk 37 零工具链变更。`https://developer.android.com/build/releases/agp-9-1-0-release-notes`
- **E-2 AGP 升级上限**：最新 AGP `9.4.0`（2026-09）要求 Gradle ≥ `9.6.0`。在 Gradle `9.5.0` 上 AGP 可用上限为 9.3.x 线；本批维持 AGP `9.1.0` 不动，任何 AGP 升级必须与 Gradle Wrapper 升级同批决策。`https://developer.android.com/build/releases/agp-9-4-0-release-notes`
- **E-3 Kotlin/CMP 配对**：Kotlin `2.4.10` stable（2026-07-14）与 CMP `1.11.1`（2026-06-02）官方配对兼容。CMP 1.11.1 的 Material3 映射为 1.5.0-alpha17 系工件。`https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html` ；`https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.11.1`。**口径差异注记**：仓库当前实际解析的 JetBrains material3 工件为 `1.9.0`（D-127 经 AAR 字节码核验，其 androidx material3 基座登记为 `1.4.0`），与官方页面映射口径存在差异；D1 批在 compileSdk 37 复验时以实际解析工件为准并登记，不得以文档映射替代实际解析结果。
- **E-4 targetSdk 37 行为变化（官方 behavior changes）**：sw ≥ 600dp 的大屏设备移除 orientation/resizability/aspect-ratio 的 opt-out（本应用的主要风险项——全屏 Compose 布局与 `statusBarsPadding` 在大屏/分屏/自由窗口下的表现必须实测）；`MessageQueue` lock-free 实现；static-final 反射限制；background-activity-start 收紧；`ACCESS_LOCAL_NETWORK` 强制执行。无 edge-to-edge、alarm、notification 或 16 KB 相关的 targetSdk 37 新变化。`https://developer.android.com/about/versions/17/behavior-changes-17`
- **E-5 16 KB 页大小**：纯 Kotlin/Compose、不捆绑自有 `.so` 的应用天然合规——本仓 Android 侧经 SQLDelight `android-driver` 使用系统 SQLite，不打包 native SQLite；Play 自 2027-02 起强制。后续可在本机已有的 API 37 `ps16k` 镜像上补一次实证验证（非本批门）。`https://developer.android.com/guide/practices/page-sizes`
- **E-6 SQLDelight**：`2.3.2`（2026-03-16 发布）：minSdk 23、AGP 9.0 DSL 兼容；无 API 37 专项声明。仓库锚点 `2.3.2` 不变。`https://github.com/sqldelight/sqldelight/releases`

### 3.2 视觉/效果库候选证据

- **E-7 miuix**：`0.9.3`（2026-07-04，Apache-2.0，minSdk 23，全 KMP），与 Kotlin 2.4.x/CMP 1.11.1 精确配对；`0.9.4-rc01` 面向 CMP 1.12；官方自述含 experimental APIs。性质为 Material3 的整套换肤替换（改造与回退成本高于效果层）。D-117 已登记其六维证据包（含单维护者风险）；本批仅引用结论。`https://github.com/compose-miuix-ui/miuix`
- **E-8 Kyant0 AndroidLiquidGlass/backdrop**：`2.0.1`（2026-08-26，Apache-2.0，KMP）；`2.0.1` 基于 Compose `1.12.0` 构建——与本仓冻结的 CMP `1.11.1` 存在版本张力；`1.x` 线为 CMP 1.11 时代版本。`https://github.com/Kyant0/AndroidLiquidGlass`。六维核对状态（D-099 模板口径）：许可（Apache-2.0）与跨平台形态（KMP）已核；维护（仅版本时点已知，未做系统性核对）、传递依赖、包体影响、Android 14-17 兼容——**缺失——登记为 D2 采纳门的强制核对项**（P6-D3 引用）。
- **E-9 Haze**：`1.7.3`（2026-08-27，Apache-2.0，非常活跃）；blur/glassmorphism 效果层；跟随最新 stable CMP（版本配对须在采用时逐版核验）。`https://github.com/chrisbanes/haze`。六维核对状态（D-099 模板口径）：许可（Apache-2.0）已核；维护仅初判（发布活跃，未做系统性核对）；传递依赖、包体影响、Android 14-17 兼容、与 CMP `1.11.1` 的精确配对——**缺失——登记为 D2 采纳门的强制核对项**（P6-D3 引用）。
- **E-10 官方平台构件**：截至 API 37 无官方平台 liquid-glass API；官方基础构件为 `RenderEffect`（API 31+）与 AGSL `RuntimeShader`（API 33+）。任何玻璃/液态玻璃效果在可预见版本内都依赖第三方或自研实现，回退层是唯一稳定资产。`https://developer.android.com/reference/android/graphics/RenderEffect`

## 4. 决定（P6-D1..P6-D6）

### P6-D1 compileSdk 36 → 37（全部 Android 编译目标）

- **决定**：D1 工具链批将全部 Android 编译目标（`android-app`、`app-ui`、`ledger-data`）的 `compileSdk` 从 `36` 升至 `37`。`minSdk 34`、`targetSdk 36`、AGP `9.1.0`、Kotlin `2.4.10`、CMP `1.11.1`、Gradle `9.5.0`、JDK 21 与全部依赖坐标保持不变。
- **理由**：E-1 表明升 compileSdk 37 零工具链变更；ROADMAP 阶段 6 明确以 `compileSdk 37` 为目标；提前对齐编译目标使 D2 视觉批与后续 targetSdk 决策有编译期事实可用。**经验性检查**：当前 CMP 1.11.1 material3 在 compileSdk 36 构建通过，升到 37 后必须在 CI android job 复验（含 E-3 口径差异注记的实际解析工件复核）。
- **备选与落选理由**：维持 compileSdk 36 落选——阶段 6 进入条件（官方证据门 + SDK 目标）无法满足；同步升级 AGP（9.3.x/9.4.0）落选——E-2 表明其收益为零而 9.4.0 强制 Gradle ≥ 9.6.0，会扩大为 Wrapper 升级批，违背「零工具链变更」边界；随本批同步升 targetSdk 落选——见 P6-D2。
- **回退触发**：CI android job 在 compileSdk 37 下出现无法以依赖/工件复核解决的编译失败时，回退 compileSdk 至 36（三处单行改动）并把失败事实登记为阶段 6 证据门的阻断项。

### P6-D2 targetSdk 维持 36（升级触发条件冻结）

- **决定**：本批（含 D1）保持 `targetSdk 36`。未来升级 targetSdk 37 必须同时满足以下全部条件，且另立批执行：(a) 行为审计完成——sw ≥ 600dp 大屏设备的 orientation/resizability/aspect-ratio 行为审计（E-4 主要风险项）；(b) Android 14/15/16/17 模拟器回归全部 PASS（API 34 已于 2026-09-06 经 E2E-R-002 关闭；API 36 由 D-130 与 2026-09-06 门覆盖；API 35 与 API 37 尚未执行）；(c) targetSdk 37 行为变化清单（E-4 全项）逐项核对完成。任一证据不足即保持 36 并记录缺口与后续触发路径。
- **理由**：ROADMAP 阶段 6 明文「`targetSdk 37` 只有在官方兼容性证据、行为审计和 Android 14-17 回归全部通过后才升级，未通过则保持 targetSdk 36」；targetSdk 是运行时行为开关而非编译开关，提前升级等于绕过证据门。
- **备选与落选理由**：随 D1 一并升 targetSdk 37 落选——(a)(b)(c) 均未齐备；保持 36 且不设触发条件落选——与阶段 6 平台适配目标相悖，本决定以三条件把升级路径显式化。
- **回退触发**：未来升级批在 API 35/37 回归或行为清单核对失败时，回退 targetSdk 至 36（单行配置）；升级批验收条款必须自带「失败即回退」。

**附表：Android 14-17 人工回归矩阵现状（2026-09-06 时点）**

| API / Android | 状态 | 证据 |
| --- | --- | --- |
| API 34 / Android 14 | PASS（2026-09-06，模拟器） | E2E-R-002 全项通过并关闭（启动、三 Tab、返回、关闭、手工支出、失败状态） |
| API 35 / Android 15 | 未执行 | targetSdk 37 决策前置要求，尚未安排 |
| API 36 / Android 16 | PASS | D-130 API 36 模拟器门（11 项）+ 2026-09-06 关闭按钮人工确认（API 36 模拟器面，§2 第 6 项） |
| API 37 / Android 17 | 未执行 | targetSdk 37 决策前置要求；本机已有 API 37 `ps16k` 系统镜像可用于后续验证 |

注：关闭按钮门的确认面为 API 36 模拟器（Android 16），可追溯链 = D-128/D-130 登记 → 本地规划 §C.1 执行方式（当前目标执行委派）→ §2 第 6 项与本表记录；D-128 原文「用户 Android 16 实机最终确认」已由 §C.1 承接（模拟器面），如需实机复验由用户保留触发，非本批门。其余真机门按既有纪律保持用户侧；16 KB 合规判定见 E-5。本表为时点快照，随门执行更新，不构成完成裁决。

### P6-D3 视觉架构：Material3 稳定基线 + 可回退效果层

- **决定**：阶段 6 视觉架构采用可回退分层设计。①Material3 保持稳定基线与默认回退；②玻璃/Backdrop 效果封装在独立的主题与组件层，不与既有共享 UI 直接耦合；③效果不可用、性能不达标或平台异常时回退 Material3；④账务状态、导航、提交与失败状态绝不依赖玻璃组件——回退后功能等价。候选效果库（E-8/E-9）的版本选型延后至 D2 实施规格：D2 必须先钉死一个经核验与 CMP `1.11.1` 兼容的版本（backdrop 1.x 时代版本或等效物），且其 D2 采纳门必须强制核对 E-8/E-9 登记为缺失的六维核对项（维护、传递依赖、包体影响、Android 14-17 兼容）；或声明「无候选、仅交付稳定回退路径」——后者被 ROADMAP 完成条件明文允许。miuix 整套换肤在本阶段拒绝采用；D-117 登记的 miuix 0.9.3 选型证据保留为历史事实与未来皮肤批输入，本批不关闭该方向。
- **理由**：ROADMAP 完成条件允许「玻璃效果失败但回退路径稳定时收口」；E-8 显示当前 backdrop 2.0.1 面向 CMP 1.12 构建，直接引入等于用未验证的版本配对替换 D-117 冻结并经受阶段 5 收口的稳定栈；E-10 表明玻璃效果必然依赖第三方实现，Material3 回退层是唯一可冻结承诺的稳定资产。
- **备选与落选理由**：miuix 0.9.3 立即整套换肤落选——E-7 它替换 Material3 而非叠加效果层，回退面等于重铺整个 UI，与「Material3 稳定基线」直接冲突并放大 D2/D3 验收矩阵；冻结 backdrop 2.0.1 落选——E-8 版本张力未消除；立即自研平台 shader 层落选——E-10 官方仅提供基础构件（RenderEffect API 31+/AGSL API 33+），自研属 D2 之后的实现决策，且不改变「必须可回退」的架构裁决。
- **回退触发**：D2 候选版本核验失败 → 仅交付 fallback-only；D3 平台回归批发现玻璃层破坏记账（创建/提交/确认）、返回、关闭或错误处理任一流程 → 整层禁用，以 Material3 回退路径收口阶段 6。

### P6-D4 CI 矩阵：android job 同 job 复验 compileSdk 37

- **决定**：D1 批按以下边界更新 CI 矩阵：android job 在 compileSdk 37 下执行编译与测试——现有步骤（`:ledger-data:compileAndroidMain`、`:android-app:testDebugUnitTest`、`:android-app:assembleDebug`）随 compileSdk 升级自然成为 API 37 的编译/测试证据；如无步骤级变更需要，`ci.yml` 零改动。不新增模拟器 job、不新增 `connectedAndroidTest`（该边界不变：模拟器与真机门保持人工/用户侧，D-127/D-128/D-130 连续先例）。
- **理由**：P6-D1 的 compileSdk 37 复验必须落在资源充足的 CI runner（CONTRIBUTING 本机/CI 分工）；同一 job 内完成，避免重复 setup 与双倍 runner 时间。
- **备选与落选理由**：新增独立 API 37 job 落选——compileSdk 由构建脚本统一决定，两个 job 必然同值，徒增验证面；CI 引入模拟器跑 connectedAndroidTest 落选——翻转既有人工门纪律需独立决策，本批不捆绑。
- **回退触发**：随 P6-D1——compileSdk 回退 36 时 CI 同步回到原状。

### P6-D5 性能判据（D2/D3 视觉批验收门）

- **决定**：为 D2/D3 视觉批建立性能判据，先行冻结定性行门槛，具体数值判据与测量方法由 D2/D3 冻结：①冷启动；②滚动/转场帧稳定性；③内存；④低端设备可用性。硬性不变量：任何视觉效果不得阻塞记账（创建/提交/确认）、返回、关闭或错误处理流程——效果层阻塞或异常时必须即时回退且核心流程保持可用。规划输入中同时列出的功耗观察项归入 D2/D3 测量设计，不作独立门。
- **理由**：ROADMAP 完成条件要求「性能证据通过验收」；没有判据则 D3 验收退化为观感判断、不可重复；现阶段无测量方法与设备矩阵，冻结具体数值会造成假精确。
- **备选与落选理由**：不设判据落选——完成条件无法可重复验收；现在冻结具体数值落选——缺测量方法与样本设备支撑，数值无证据效力。
- **回退触发**：不适用（判据是验收门而非代码）。D2/D3 可收紧数值，不得豁免硬性不变量；豁免须另立决定。

### P6-D6 主题缺陷登记（D2 视觉批输入）

- **决定**：将 2026-09-06 人工门核验的主题缺陷正式登记为 D2 视觉批输入（截图与像素证据存于本地工件目录 `local/artifacts/p6-gates/`，不入库）：
  - **P6-ENTRY-THEME-001 浅色模式状态栏对比度不足**：浅色系统模式下状态栏为深图标 on 深色窗口背景（背景 RGB 48,48,48，像素对比度 1.41:1，条带内近白像素为 0）；深色模式白图标 13.20:1 PASS。修复方向：显式 `SystemBarStyle` + 主题自适应窗口背景（`enableEdgeToEdge` 参数或 values/values-night 主题资源）。
  - **P6-ENTRY-THEME-002 首页/编辑页主题错位（两种模式均存在）**：activity 主题 parent=`android:Theme.Material.NoActionBar`（深色平台主题，themes.xml:3）+ `P503App` 的 `MaterialTheme { }` 未传 colorScheme（恒浅色 scheme，P503App.kt:199）——首页 Scaffold 涂浅底、编辑页裸 Column 露深窗口底。修复方向：D2 双 colorScheme（明/暗）+ 主题 parent 对齐（含 values-night 资源）。
  - **状态联动**：E2E-R-001 保持未关闭，直至 D2 修复并实机复验；E2E-R-002 已关闭（API 34 回归 PASS，2026-09-06）；关闭按钮人工确认已关闭（API 36 模拟器面，Android 16，2026-09-06；CI APK `98fc242`，面别与 D-128 原文承接说明见 §2 第 6 项）；D-131 TalkBack 走查已关闭（2026-09-06）。
- **理由**：D-128/D-130 先例——缺陷以中性事实 + 根因到文件/行 + 修复方向登记，修复归后续批；两缺陷根因已在 2026-09-06 门中定位到具体文件与行，D2 可直接冻结修复方案。
- **备选与落选理由**：随 D1 顺手修复落选——主题修复属 D2 范围且需双主题设计决策（明暗两套 colorScheme 与效果回退层的交互），本批保持零范围蠕变；不登记落选——E2E-R-001 是已登记未关闭项，缺失登记使 D2 缺输入。
- **回退触发**：不适用（登记性决定）。D2 修复后实机复验失败则缺陷保持未关闭并更新证据。

## 5. D1 实施批冻结范围

D1 = SDK/工具链批，仅在本文件 approved 后开批。冻结写入路径、验证与验收如下；实施批不得扩大范围，发现承载缺口须先回本规格修订并过评审门。

### 5.1 冻结写入路径

| # | 文件 | 变更 | 条件 |
| --- | --- | --- | --- |
| 1 | `android-app/build.gradle.kts` | `compileSdk` 36 → 37（:24） | 必须 |
| 2 | `app-ui/build.gradle.kts` | `compileSdk` 36 → 37（:37） | 必须 |
| 3 | `ledger-data/build.gradle.kts` | `compileSdk` 36 → 37（:38）；minSdk 34 及其理由注释零改动 | 必须 |
| 4 | `.github/workflows/ci.yml` | 仅当出现步骤级变更需要；现有 android job 步骤已隐式覆盖 compileSdk 37 编译/测试，预期零改动 | 需要时 |
| 5 | `docs/CONTRIBUTING.md` / `README.md` | 仅当两者登记的验证命令或工具链叙述受影响（现状核对：两文件均未登记 compileSdk/targetSdk 数值；若 CI 步骤变更则按 CONTRIBUTING「CI 配置」同步规则更新） | 需要时 |

约束：除上表外全部跟踪文件零改动（含 `docs/DECISIONS.md`、`docs/ROADMAP.md`、`themes.xml`、一切 `.kt` 源与迁移）。`minSdk 34` / `targetSdk 36` / 全部依赖坐标 / AGP / Kotlin / CMP / Gradle / JDK 逐值不变。

### 5.2 验证命令与前置检查（本机，PowerShell，从仓库根执行；CONTRIBUTING 资源约束：串行、单 worker、1 GB，每轮前后 `--stop`）

**步骤 0（D1 前置检查，先于下列命令）**：本地 SDK 平台现状为 `platforms;android-34`、`platforms;android-35`、`platforms;android-36`、`platforms;android-36.1`，**无 `platforms;android-37`**。D1 必须先经 `sdkmanager` 安装 `platforms;android-37`（及 AGP 要求时的 `build-tools;37.x`）并确认许可证接受，方可执行 compileSdk 37 构建；CI ubuntu runner 经 SDK 自动安装提供该平台，不依赖本地安装状态。

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :ledger-data:compileAndroidMain --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat :android-app:testDebugUnitTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat --stop
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

逐条串行执行，一次一条；预期全部 exit 0。**APK 组装证据归属 CI（D-118 R-9 资源受限路径登记）**：`:android-app:assembleDebug` 不在本机验证命令之列——该任务在本机曾有内存不足（OOM）失败记录；若本机尝试发生 OOM 不构成阻断，APK 组装证据以 CI 对合并提交的运行为准（5.3 第 3 项）。CI 侧以 android job 在 compileSdk 37 的绿结果为聚合证据（5.3 第 3 项）。

### 5.3 验收标准

1. 三处 `compileSdk = 37`；`minSdk 34`、`targetSdk 36`、依赖坐标与插件版本逐值零变化（diff 仅三行级别）。
2. 5.2 全部命令 exit 0。
3. CI android job 于 compileSdk 37 绿（P6-D1 的复验判据；CI run 对应精确提交），含 E-3 口径差异注记的实际解析 material3 工件复核登记与 `:android-app:assembleDebug` 的 APK 组装证据（D-118 R-9：组装证据归属 CI）。
4. 零 schema/迁移变更（migration verifier 由 CI kotlin job 承担）、零账务行为变更、零新依赖。
5. `ci.yml`/`docs/CONTRIBUTING.md`/`README.md` 的同步一致性核查记录（含「零改动」结论本身）。

## 6. 边界（明确不做）

- **targetSdk 37 升级**：不做。触发条件 = P6-D2 三条件全部满足，另立批执行。
- **miuix 采用与整套换肤**：本阶段拒绝（P6-D3）；不关闭 D-117 未来皮肤批方向。
- **玻璃/Backdrop 依赖引入与实现细节**：候选版本钉死、组件层实现、降级开关设计全部归 D2 规格证据门；本批不引入任何依赖。
- **主题缺陷修复**：P6-ENTRY-THEME-001/002 修复归 D2；E2E-R-001 保持未关闭。
- **CI 模拟器与 connectedAndroidTest**：不新增；人工门边界不变。
- **API 35/37 模拟器回归的执行**：属 D3 平台回归批或独立验证批，不在 D1。
- **零新依赖、零 schema/迁移、零账务行为变更**；不引入导航库与 compose ui-test harness；共享 UI 的交互与账务状态机语义零改动（D-125/D-126/D-129 边界延续）。
- **`.external/` 零触碰**：本批只保留门查阅声明，不复制其内容。
- 既有决定零改动：D-114/D-117/D-119/D-125/D-128/D-129/D-130/D-131 的全部语义与本批正交；并行 FOUND-001 决策批（Android 损坏数据库）零交集。

## 7. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | CMP 1.11.1 / material3 在 compileSdk 37 下未经验证（当前仅 compileSdk 36 构建事实） | D1 编译失败或工件行为漂移 | CI android job 强制复验（P6-D1/P6-D4）；失败即回退 36 并登记阻断项 |
| R-2 | Gradle 9.5.0 上 AGP 上限为 9.3.x（E-2），AGP 9.4.0 需 Gradle ≥ 9.6.0 | 后续任何 AGP 升级被 Wrapper 绑定 | 维持 AGP 9.1.0；AGP 与 Gradle 升级同批决策，不拆分 |
| R-3 | targetSdk 37 大屏 opt-out 移除（sw ≥ 600dp，E-4） | 大屏/分屏/自由窗口下布局行为变化未实测 | P6-D2 三条件门 + (a) 行为审计前置；未过门保持 36 |
| R-4 | 效果层库版本张力（backdrop 2.0.1 面向 CMP 1.12，E-8；Haze 跟随最新 CMP，E-9） | 引入未验证版本配对，破坏 D-117 冻结栈 | P6-D3：候选延后至 D2 钉死 CMP 1.11.1 兼容版本，或 fallback-only |
| R-5 | P6-ENTRY-THEME-001/002 在 D2 修复前持续存在 | 浅色模式可读性缺陷保留（E2E-R-001 未关闭） | 缺陷已定位到文件/行（P6-D6）；D2 双 colorScheme + SystemBarStyle 修复后复验 |
| R-6 | 视觉库维护风险（miuix 单维护者/experimental APIs，E-7；效果库快速迭代） | 上游停滞或破坏性变更 | 效果层独立封装 + Material3 回退（P6-D3）；采用时点逐版复核维护状态 |

## 8. 批准后的登记路径

**批准记录：** 独立评审 P6SPEC-001..005 APPROVE-WITH-FINDINGS → delta 修订 → 闭环复核全部 CLOSED、终局 APPROVE；主代理按常设授权批准 2026-09-06。

1. 本文件状态行由 `proposal` 翻转为 `approved`（2026-09-06 已执行）。
2. `docs/DECISIONS.md` 登记为下一个全局空闲决定编号：内容 = §4 P6-D1..P6-D6 + §3 证据结论 + §4 附表时点快照。**注意**：D-132 已由并行 FOUND-001（P5-04.5 Android 损坏数据库）决策批先行占用（该批先合并入库）；本批登记取下一空闲编号 **D-133**，以登记时 `docs/DECISIONS.md` 末尾为准，不得与 FOUND-001 批抢占或跳号。
3. 登记批不改写本文件正文以外任何既有文档的历史内容；`docs/CURRENT_STATE.md` 与本地检查点同步由主代理执行。
4. D1 实施批仅在本文件 approved 后开批：独立 worktree、单一 bounded writer、独立规格与质量评审、distinct verifier、主代理最终验收与合并。
5. 阶段 6 进入条件证据 = 本规格（官方证据门结论）+ D1 完成 + 可重复 Android 基线验收（2026-09-06 关闭按钮确认、E2E-R-002、TalkBack 走查已提供基线）。API 35/37 回归属完成条件与 targetSdk 门要素，不阻塞进入条件。

## 边界断言（本批不含）

- 本批唯一写入 = 本新文件；`docs/specs/` 既有文档、`docs/DECISIONS.md`、全部构建脚本、CI 配置与一切源码零改动。
- 本文件为决策草案：未经批准不构成实施授权；批准后的 DECISIONS.md 登记由独立登记批执行，本批不自行登记。
- 本文件不含本机绝对路径、个人数据、真实账务数据或 agent/会话痕迹；本地工件目录仅以相对名称提及且不入库；`.external/` 内容除门查阅声明外零引用。
