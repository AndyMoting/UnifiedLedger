# D2 主题与组件批（双主题修复、状态栏与玻璃层采纳）设计规格

**状态：** approved

**Scope:** 本文件是阶段 6 D2 实施批「主题与组件」的设计规格（design + implementation-freeze：主题修复与玻璃层封装归本批实施，数值性能测量与玻璃启用归后续批）。交付五项内容：D2 采纳门证据记录（§3，E-1..E-8）、五项决定（§4，D2-D1..D2-D5）、D2 实施批冻结写入路径与验收（§5）、批准后的登记路径（§8）。P6-ENTRY-THEME-001/002 两项主题缺陷的修复设计在本文件冻结（D2-D1/D2-D2）；玻璃库版本选型按 P6-D3 的 D2 采纳门裁决为 backdrop 2.0.0（D2-D3），本批以默认关闭的独立封装层交付，不接线任何界面。数值性能判据、API 35/37 回归、targetSdk 决策明确不在本批（§6，归 D3 或另立批）。本文件由 2026-09-06 的 D2 批规划触发（该规划为本地文档，不入库）；正文只依赖 tracked 文件与官方公开来源。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `530c3d0` 的行号；`.external/` 只读）：

- **路线权威**：docs/ROADMAP.md:62-67（阶段 6 定义，逐字引用见阶段 6 入口规格 §Authority；本批落点）：:64「阶段 6 再验证稳定主皮库双主题、AndroidLiquidGlass/Backdrop、Material3 回退路径」与「主题与组件批（D2）待执行」；:67 完成条件「玻璃效果失败但回退路径稳定时允许阶段 6 收口，且不阻塞核心账务流程」。
- **决定承接**：docs/DECISIONS.md:2224（D-133，阶段 6 入口裁决批；:2232 P6-D3 视觉架构与 D2 采纳门、:2234 P6-D5 性能判据、:2235 P6-D6 主题缺陷登记、:2252-2254 D1 实施登记 compileSdk 37）、:2078（D-125，:2086 边界段「P6 拥有视觉」及编辑流状态机/Back/Esc 语义零改动硬边界）、:2122（D-128，`enableEdgeToEdge()` 与根级 `statusBarsPadding()`；E2E-R-001 首登记并保持未关闭至 D2 修复复验）、:2142（D-129，fail-closed 状态单一来源）、:2176（D-131，登记风格先例）、:1919（D-117，CMP 1.11.1 栈冻结——本批玻璃依赖不得扰动该栈）。
- **源码锚点（只读检查，缺陷根因见 P6-D6/D-133）**：
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:199`：`MaterialTheme { }` 未传 colorScheme（恒浅色 scheme）；:376-419 基础设施失败两屏为裸 Column（不涂底）。
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503TabShell.kt:55`：Scaffold（containerColor 默认 = `colorScheme.background`）；:61-66 底部胶囊 Surface（tonalElevation/shadowElevation，玻璃启用批的唯一预期挂点，本批零改动）。
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt:98`：编辑页裸 Column 根（不涂底，露出窗口背景）。
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503StartupScreen.kt:42`：启动/错误屏裸 Column（不涂底、文本用环境主题默认色）。
  - `android-app/src/main/res/values/themes.xml:3`：`Theme.UnifiedLedger` parent=`android:Theme.Material.NoActionBar`（深色平台主题，静态）；`AndroidManifest.xml` activity 未声明 `configChanges`（uiMode 变更走平台默认 activity 重建）。
  - `android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt:13`：`enableEdgeToEdge()` 无参调用（D-128）；:14 `setContent { app() }`（app 之外无任何主题包装）。
  - `android-app/src/main/kotlin/com/unifiedledger/android/App.kt:88`（根级 `Box(Modifier.fillMaxSize().statusBarsPadding())`，D-128）、:96-102（未就绪分支在 `P503App` 之外直接渲染 `P503StartupScreen`——预就绪屏因此处于任何 `MaterialTheme` 之外）。
  - `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:88`：`Window(onCloseRequest, title)` 未传任何背景；:90-105 两分支直出（桌面组合根不绘制任何背景，桌面窗口底色为 Compose Desktop 窗口默认值）。
- **工具链锚点（现状，D1 批已交付，本批零升级）**：Kotlin `2.4.10`、CMP `1.11.1`、AGP `9.1.0`、Gradle `9.5.0`、JDK 21；`android-app` minSdk 34 / compileSdk 37 / targetSdk 36（android-app/build.gradle.kts:28-29,24）；`app-ui` minSdk 34 / compileSdk 37（app-ui/build.gradle.kts:36-37）；`androidx.activity:activity-compose:1.13.0`（android-app/build.gradle.kts:50）；`app-ui` commonMain 现有依赖 = `compose.runtime`/`compose.foundation`/`compose.material3`/`kotlinx-datetime:0.7.1`（app-ui/build.gradle.kts:41-49）；`android-app/src/main/res` 现状仅有 `values/` 一个资源目录，无 `values-night/`。

术语与编号约定：`D2` = 阶段 6 实施批序列第 2 批「主题与组件批」（本规格）；`D3` = 第 3 批「平台回归批」；`P503Theme` = 本批引入的共享双主题包装 composable（D2-D1）；`玻璃开关` = 玻璃封装层内的编译期常量开关（名称由实施批冻结）；本批局部决定编号 `D2-D1..D2-D5`、证据编号 `E-1..E-8`、风险编号 `R-1..R-7` 仅在本批内稳定使用，与全局 `docs/DECISIONS.md` 的决定编号空间（如 D-133）不同；缺陷编号 `P6-ENTRY-THEME-001/002` 承接 D-133 登记，不重编号。证据编号存在两个命名空间：本批证据 `E-1..E-8`（§3）与阶段 6 入口规格的证据 `E-1..E-10`（docs/specs/2026-09-06-phase6-entry-sdk-visual-design.md）；本文件引用入口规格证据时一律加 `P6-` 前缀（如 `P6-E-8`/`P6-E-10`），两个命名空间不互换。

## 1. 目的与范围

1. **批定义**：D2 主题与组件批 = P6-D6 两项主题缺陷的修复实施 + P6-D3 授权的 D2 玻璃采纳门裁决 + 玻璃封装层交付。交付物 = 本规格（批准后）+ §5 冻结范围内的代码/资源变更与其验证证据。
2. **冻结对象**：§4 五项决定（双主题修复形态、状态栏图标契约、玻璃库版本与封装边界、桌面一致性、性能判据执行）与 §5 的冻结写入路径、验证命令与验收标准。
3. **范围外**：玻璃启用（接线任何界面）与数值性能测量（数值判据正式冻结权威为 D3，见 D2-D3/D2-D5）；targetSdk/compileSdk 变更；API 35/37 回归执行（D3）；miuix 整套换肤（P6-D3 已拒绝，方向保留见 D-117）；新界面、导航库、账务/RG/导入/对账/schema 变更（全部为零，§6）。
4. **阶段定位**：阶段 6 进入条件已满足（D-133）、D1 工具链批已完成（compileSdk 37，D-133 D1 实施登记）；本批为阶段 6 的 D2。阶段 6 完成条件（ROADMAP:67）由 D2/D3 及阶段收口批承接，本批不预先裁决其结果；本批即使玻璃层保持关闭，P6-ENTRY-THEME-001/002 修复后的 Material3 稳定路径已满足「稳定 Material3 回退路径」要素的 D2 侧输入。
5. **交付物清单与稳定 ID 约定**：规格阶段交付物唯一 = 本文件。实施批交付物 = §5.1 表列文件 + 验证证据登记。稳定 ID 见 Authority And Boundary 末段。

## 2. 既有裁决承接（批准后随 D-134 登记批并入）

以下为既有决定与已核验事实的承接复述；本文件不新增对它们的裁决，冲突处置显式声明：

1. **D-133 / P6-D3（视觉架构）**：Material3 为稳定基线与默认回退；玻璃/Backdrop 封装在独立主题与组件层，账务状态、导航、提交与失败状态绝不依赖玻璃组件；D2 采纳门必须强制核对 P6-E-8/P6-E-9 登记为缺失的六维核对项并钉死与 CMP 1.11.1 兼容的版本，或声明 fallback-only。本规格 §3/§4-D2-D3 即该采纳门的执行。
2. **D-133 / P6-D6（主题缺陷登记，归 D2）**：P6-ENTRY-THEME-001（浅色模式状态栏深图标 on 深窗口背景，像素对比度 1.41:1；深色模式 13.20:1 PASS）与 P6-ENTRY-THEME-002（首页/编辑页主题错位：activity 主题 parent 深色 + `MaterialTheme{}` 未传 colorScheme 恒浅色——首页 Scaffold 涂浅底、编辑页裸 Column 露深窗口底）根因已定位到文件/行；E2E-R-001 保持未关闭直至 D2 修复并复验。本规格 D2-D1/D2-D2 即修复设计；截图与像素证据存于本地工件目录 `local/artifacts/p6-gates/`（不入库）。
3. **D-133 / P6-D5（性能判据）**：定性行门槛（冷启动、滚动/转场帧稳定、内存、低端设备可用性）已冻结；数值判据与测量方法由 D2/D3 冻结；硬性不变量 = 视觉效果不得阻塞记账（创建/提交/确认）、返回、关闭或错误处理流程。本批按 D2-D5 执行其 D2 侧义务，数值测量设计归启用批/D3。
4. **D-128**：`enableEdgeToEdge()` 于 `setContent` 前调用（androidx.activity，不依赖主题属性）+ Android 组合根根级 `statusBarsPadding()` 为已交付不变量；E2E-R-001（浅色状态栏对比度）登记未关闭。本批 MainActivity 变更限于该调用的显式参数化（D2-D2），根级 insets 结构零改动。
5. **D-125 边界段**：「P6 拥有视觉」。本批拥有主题与视觉实现权；编辑流状态机、Back/Esc 语义、fail-closed 重试、「权威刷新恒回首页」语义零改动是硬边界——本批全部变更限于主题/颜色/背景/封装层，不触及 `P503Reducer`、`P503HostCoordinator`、`P503EditScreen` 的结构与任何事件语义（`dismissOnEscape`、关闭按钮、提交编排零改动）。
6. **D-117**：CMP 1.11.1 栈冻结（Kotlin 2.4.10 + org.jetbrains.compose 恒等配对）。本批玻璃依赖的采纳形式为「精确钉定与该栈兼容的上游版本 + Gradle 冲突收敛复核」（D2-D3），不升级 CMP/Kotlin；miuix 选型证据保留为历史事实，未来皮肤批方向不关闭。
7. **D-130/D-132**：阶段 5 收口基线与并行 FOUND-001 批已合并入库（D-132 实施登记与本 worktree 基线 `530c3d0` 一致）；本批与其零交集。

## 3. 外部证据门记录

本批改变平台集成（主题/状态栏）与生产技术（新增玻璃效果库依赖），按主检出 `AGENTS.md` 触发外部证据门：`docs/SOURCE_REFERENCES.md`、`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md` 已按门要求查阅（只读；两处为本地未跟踪文件，主检出可读）；本文件只保留由此转化的中立事实与决策条款，不复制原始研究记录，`.external/` 内容零改动。证据时点：2026-09-06；来源为 Maven Central 官方 POM、GitHub 官方仓库发布页、Android 官方文档与 AOSP 源码。

### 3.1 采纳候选：Kyant0 backdrop（P6-E-8 承接 + 本门补全）

- **E-1 backdrop 2.0.0 工件事实（Maven Central POM）**：`https://repo1.maven.org/maven2/io/github/kyant0/backdrop/2.0.0/backdrop-2.0.0.pom`。groupId/artifactId = `io.github.kyant0:backdrop`，版本 `2.0.0`（stable，2026-05-28 发布），许可 Apache-2.0；依赖列示：`org.jetbrains.compose.foundation:foundation:1.11.0`、`org.jetbrains.compose.ui:ui:1.11.0`、`org.jetbrains.compose.ui:ui-graphics:1.11.0`、`org.jetbrains.kotlin:kotlin-stdlib:2.3.21`、Kyant0 `shapes:1.2.0`、`org.jetbrains:annotations`。与 D-117 冻结栈的配对判定：CMP 侧 1.11.0 → 本仓 1.11.1 为同线 patch（1.11.1 于 2026-06-02 发布），Gradle 冲突收敛取最高版本即 1.11.1（drop-in）；kotlin-stdlib 2.3.21 低于本仓 2.4.10（向前兼容）。上游当前线 2.0.1（2026-08-26）面向 CMP 1.12（P6-E-8 口径）——**2.0.0 是本仓栈下的 fork-point pin**，采用即接受「钉旧不追新」。
- **E-2 平台形态与体量**：2.0.0 提供 per-target android + desktop 工件（KMP）；minSdk 21（≤ 本仓 34）与 compileSdk 37 构建配置由库构建脚本载明：`https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/backdrop/build.gradle.kts` ；工件尺寸以实施批依赖审计时的本地解析产物复核为准（预计约 130 KB/目标）。对照：**1.x 线（1.0.6）为 Android-only AAR，钉 androidx.compose 1.10.3，无法服务 Desktop**——P6-E-8 曾推测「1.x 线为 CMP 1.11 时代版本」，本门证伪：与本仓栈兼容的版本是 2.0.0 而非 1.x 线。`https://repo1.maven.org/maven2/io/github/kyant0/backdrop/` ；`https://github.com/Kyant0/AndroidLiquidGlass/releases`
- **E-3 维护状态**：最后发布 2.0.1（2026-08-26）；33 个 open issues；3,627 stars；仓库活跃。`https://github.com/Kyant0/AndroidLiquidGlass/releases`
- **E-4 Android 14-17 兼容**：截至 2026-09-06 未检索到 Android 14/15/16/17 兼容性问题报告；其效果基于 AGSL `RuntimeShader`（API 33+，官方构件见 P6-E-10/D-133 口径），本仓 minSdk 34 覆盖。构建配置 compileSdk 37 与 D1 后的本仓编译目标一致。
- **E-5 六维核对表（D-099 模板口径，P6-D3 采纳门）**：

| 维度 | 结论 | 证据 |
| --- | --- | --- |
| 许可 | Apache-2.0 | E-1（POM） |
| 与 CMP 1.11.1 配对 | 钉定 org.jetbrains.compose 1.11.0；1.11.1 为同线 patch，Gradle 收敛至 1.11.1；kotlin-stdlib 2.3.21 向前兼容 2.4.10 | E-1 |
| 平台形态（跨平台） | KMP android+desktop 双目标；1.x 线 Android-only AAR 落选 | E-2 |
| 维护 | 2.0.1（2026-08-26）最后发布；33 open issues；3,627 stars；活跃 | E-3 |
| 传递依赖 | shapes 1.2.0 + org.jetbrains:annotations（POM 列示），无重依赖 | E-1 |
| 包体影响 | 预计约 130 KB/目标工件（实施批依赖审计复核）；本批开关关闭期运行时不可达（debug 保留、release 经 R8 剔除） | E-2 + D2-D3 |
| Android 14-17 兼容 | 未检索到问题报告；AGSL 需 API 33+，minSdk 34 覆盖 | E-4 |

### 3.2 落选候选：Haze（P6-E-9 承接 + 本门补全）

- **E-6 Haze 1.7.2 工件事实（Maven Central POM）**：`https://repo1.maven.org/maven2/dev/chrisbanes/haze/haze/1.7.2/haze-1.7.2.pom`。Apache-2.0；钉定 `org.jetbrains.compose.ui/foundation:1.10.0`、kotlin-stdlib 2.2.21、传递 `androidx.collection:1.5.0`；Android+Desktop 双目标，minSdk 23，约 212 KB。1.x stable 线中可服务本仓栈的最后版本即 1.7.2（其后的 stable 面向 CMP 1.12 线）；1.10.0 钉定可在 1.11.1 运行但非精确配对。维护非常活跃（2026-09-05 仍有推送，3 个 open issues）。落选候选的 Android 14-17 兼容维度未做核对——P6-D3 六维强制核对义务仅及于被采纳候选。`https://github.com/chrisbanes/haze/releases` ；`https://github.com/chrisbanes/haze/blob/v1/CHANGELOG.md`
- **E-7 Haze 2.0.0-beta01 与已知缺陷**：2.0.0-beta01 精确钉定 CMP 1.11.1 + Kotlin 2.4.10（与本仓栈完全配对），但为 pre-release：haze 2.0 拆分 haze-blur 模块（API 变动期），且存在已登记的 Android 渲染缺陷 issue #1258。`https://github.com/chrisbanes/haze/issues/1258` 。对本仓「稳定性冻结栈」口径：pre-release + 已知渲染缺陷不可采纳（与 P6-D3「不引入未验证版本配对」一致）。
- **E-8 平台主题与状态栏官方依据**：①`android:Theme.DeviceDefault.DayNight` 为纯框架 DayNight 主题（API 29+；本仓 minSdk 34 满足），DeviceDefault 系为 OEM 原生外观别名，框架无 `Theme.Material.DayNight`/`Theme.DayNight`：`https://developer.android.com/reference/android/R.style` ；`https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/themes_device_defaults.xml` 。②values-night 资源限定符为框架原生的按 uiMode 切换资源机制：`https://developer.android.com/develop/ui/views/theming/darktheme` 。③`enableEdgeToEdge`/`SystemBarStyle` 语义（auto = 图标随系统明暗、透明 scrim；minSdk 34 > API 29 下 scrim 不生效）沿用 D-128 已登记官方依据（R-3/R-4）：`https://developer.android.com/reference/androidx/activity/EdgeToEdge` 。

## 4. 决定（D2-D1..D2-D5）

### D2-D1 双主题修复（P6-ENTRY-THEME-002）：共享双 colorScheme + 主题 parent 对齐（values/values-night）

- **决定**：
  - **共享包装**：在 `P503App.kt` 引入共享 composable `P503Theme`（置于同文件；形态 = `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content)`），并把 `P503App.kt:199` 的 `MaterialTheme { }` 替换为 `P503Theme { }`。同时在该包装内、`when` 之外加一个 `fillMaxSize` 且以 `MaterialTheme.colorScheme.background` 涂底的容器——使编辑页、基础设施失败屏等裸 Column 屏不再依赖窗口背景（桌面窗口默认底色非明暗自适应，见 D2-D4；Android 侧涂底与 Scaffold 的 containerColor 同源，视觉零漂移）。
  - **Android 主题资源（最小资源变更集 = 两文件、仅 parent 属性）**：`android-app/src/main/res/values/themes.xml` 的 parent 改为 `android:Theme.Material.Light.NoActionBar`（现值原样移入新文件 `values-night/themes.xml`，parent 保持 `android:Theme.Material.NoActionBar`）。效果：窗口背景（含启动闪底与状态栏条带背后的底色）随系统明暗切换，浅色模式为浅底、深色模式为深底；未声明 `configChanges`（manifest 现状）下 uiMode 切换走平台默认 activity 重建，窗口背景与组合一致刷新。
  - **预就绪屏接入同一主题**：`MainActivity.kt` 的 `setContent { app() }` 改为 `setContent { P503Theme { app() } }`。根因：未就绪分支（App.kt:96-102）在 `P503App` 之外渲染 `P503StartupScreen`，现处于任何 `MaterialTheme` 之外——窗口背景随模式切换后，若不接入双主题，深色模式下启动/错误屏将保持默认浅色 scheme 的深色文本叠深色窗口（现状缺陷延续）。`App.kt` 零改动。注意：material3 `MaterialTheme{}` 不传 colorScheme 时恒用浅色默认（P6-D6 登记事实），故 `P503App` 内层包装必须继续携带双 colorScheme 逻辑（嵌套同值无害），不得退化为裸 `MaterialTheme{}`。
- **理由**：P6-ENTRY-THEME-002 的两个根因（静态深色平台主题 + 未传 colorScheme）分别由资源对齐与双 colorScheme 消除；裸 Column 露底问题的根治是把背景绘制收敛进共享层而非依赖各平台窗口默认值——单点修复同时覆盖两端全部屏；预就绪屏接入是同一缺陷面（无主题包装的裸 Column）的完整闭合，且落点 `MainActivity.kt` 本就在本批冻结清单内。
- **备选与落选理由**：
  - `android:Theme.DeviceDefault.DayNight`（单文件、免 values-night，E-8-① 已核实存在）——落选：DeviceDefault 是 OEM 原生外观别名，随设备改变窗口/控件基座且把主题家族从 Material 换为 DeviceDefault，与「最小且确定性正确」冲突；values-night 方案保持 Material 家族逐模式静态钉定，行为可预测。
  - appcompat `Theme.AppCompat.DayNight`——落选：引入 androidx.appcompat 新依赖，仅为省一个资源文件，违反零依赖纪律。
  - 仅改 Compose colorScheme、不动 themes.xml——落选：状态栏条带与编辑页露出的窗口背景保持静态深色，P6-ENTRY-THEME-001/002 均不修复。
  - 双主题包装上移到两端组合根（`App.kt`/`Main.kt` 各包一层）——落选：扩大触碰面至两个组合根文件且桌面组合根需要额外背景处理；共享层涂底 + MainActivity 单点包装以最小文件集达成等价效果。
- **回退触发**：模拟器人工门在任一模式发现任一屏出现 scheme 文本与底色错位（可读性不达标）→ 该屏归入缺陷记录并评估回退本决定对应子项（colorScheme/parent/涂底相互独立，可单点回退）；`P503Theme` 引入导致既有 JVM 测试或 ktlint 无法收敛的意外面 → 回退该文件为 `MaterialTheme(colorScheme = …)` 内联形态（语义等价）。

### D2-D2 状态栏图标契约（P6-ENTRY-THEME-001）：显式 `SystemBarStyle.auto`

- **决定**：`MainActivity.kt:13` 的 `enableEdgeToEdge()` 改为显式形态 `enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))`（import `androidx.activity.SystemBarStyle` 与 `android.graphics.Color`）；`navigationBarStyle` 不传（保持 D-128 交付的默认）。语义：auto 模式下状态栏图标随系统明暗——浅色模式深图标、深色模式浅图标；透明 scrim 在 minSdk 34（> API 29）下不生效，条带背后的实际底色即窗口背景。**对比度修复的实质承载是 D2-D1 的窗口背景对齐**（浅色模式下深图标落浅底）；本决定的职责是把图标外观契约从隐式默认显式化为可评审代码，并防未来默认值漂移。
- **理由**：P6-ENTRY-THEME-001 的缺陷机制是「图标行为正确（auto 跟随系统）但底色错误（静态深色窗口背景）」；官方修复方向（P6-D6 登记）即「显式 `SystemBarStyle` + 主题自适应窗口背景」，两项分别落在本决定与 D2-D1。
- **备选与落选理由**：维持无参 `enableEdgeToEdge()`——落选：契约不可评审，E2E-R-001 复验缺少被验对象；硬编码浅色图标 `SystemBarStyle.light(...)`——落选：与深色模式冲突，等于放弃 auto 跟随；同时显式化 `navigationBarStyle`——落选：底部区域 D-128 已按默认交付且无缺陷记录，扩大变更面无对应缺陷输入。
- **回退触发**：E2E-R-001 复验按 §5.3 第 3 项判据执行；浅色或深色任一模式对比度 < 3:1 → 缺陷保持未关闭、更新证据并回本规格修订（禁止实施批静默改判据或改钉值）。

### D2-D3 玻璃层采纳（P6-D3 的 D2 采纳门裁决）：ADOPT `io.github.kyant0:backdrop:2.0.0`，默认关闭的独立封装层

- **决定**：
  - **采纳与钉定**：`app-ui` commonMain 精确钉定 `io.github.kyant0:backdrop:2.0.0`（E-5 六维表全项满足，P6-D3 采纳门通过）。不引入该库的任何其他坐标或版本范围。
  - **封装边界**：玻璃效果只存在于新增的小型主题/组件层 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/`（单文件；类名与 `玻璃开关` 常量名由实施批冻结）：单一带 Material3 回退路径的包装 composable（backdrop 形态与回退形态由同文件冻结），无任何账务/导航/提交/失败状态依赖；`玻璃开关` 为编译期常量，**本批默认 `false`**。
  - **不接线**：本批不把玻璃包装接入任何既有屏（冻结清单无消费方文件）；`P503TabShell.kt:61-66` 的底部胶囊 Surface 登记为未来启用批的唯一预期挂点。本批交付后无新执行路径，运行时行为等价（不主张 APK 字节等价；开关关闭 + 无调用点）。
  - **落选登记**：Haze 落选——stable 线可服务本仓栈的最后版本 1.7.2 钉定 CMP 1.10.0（非精确配对，E-6），精确配对的 2.0.0-beta01 为 pre-release 且带已知 Android 渲染缺陷 #1258（E-7），与稳定性冻结栈冲突；backdrop 1.x 线（1.0.6）落选——Android-only AAR 无法服务 Desktop 且钉 androidx.compose 1.10.3（E-2）；miuix 整套换肤落选——P6-D3 已裁决（替换而非叠加，回退面等于重铺 UI），D-117 证据保留为历史输入。
  - **启用路径（后续批，非本批）**：启用批以 `玻璃开关 = true` + 最小接线（挂点唯一）交付，并以模拟器双模式视觉验证 + P6-D5 数值判据冻结为前置门——数值判据与测量方法的正式冻结权威为 D3 平台回归批；若启用批先于 D3 开批，其规格必须自带数值冻结条款并作为批准条件。届时按本规格 §3 证据时点复核六维（R-2）。
- **理由**：本批冻结写入路径不含任何界面文件，启用即无法在批内完成模拟器视觉回归验证——按 P6-D3 的回退架构与 ROADMAP:67「玻璃效果失败但回退路径稳定时允许阶段 6 收口」，默认关闭 + 封装先行是唯一既能交付采纳门裁决、又不让未验证视觉面进入运行时的形态；P6-D5 硬性不变量（效果层不阻塞核心流程）在开关关闭期结构性成立。
- **备选与落选理由**：默认启用并接线底部胶囊——落选：需触碰 `P503TabShell.kt`（冻结外文件）且玻璃视觉回归无法在批内人工门闭环；fallback-only（不采纳任何库）——落选：P6-D3 允许但本门六维已全部满足，无理由放弃已核验候选；依赖加在 `android-app`——落选：玻璃层按 P6-D3 属共享主题/组件层，Desktop 同为服务对象。
- **回退触发**：依赖引入导致解析冲突无法收敛（如 compose 工件被拉向 1.12 线）→ 移除依赖与封装文件（单文件 + 单行依赖），采纳门裁决降级为 fallback-only 并登记；启用批在门验证失败 → 保持关闭，阶段 6 按稳定 Material3 回退路径收口（ROADMAP:67 明文允许）。

### D2-D4 双主题与玻璃的桌面一致性：共享层覆盖，desktop-app 零改动

- **决定**：Desktop 不做任何平台代码变更（`Main.kt` 零改动）。依据（只读核实）：`DesktopRoot`（Main.kt:88-105）不绘制任何背景，桌面窗口底色为 Compose Desktop 窗口默认值；D2-D1 的共享层涂底使 Ready 路径全部屏（含编辑页裸 Column）不依赖窗口默认底色，`P503Theme` 的 `isSystemInDarkTheme()`（compose.foundation 已有依赖，Desktop 由 CMP 提供 OS 检测）驱动双 scheme。桌面预就绪启动/错误屏保持现状默认浅色文本叠窗口默认底（现状即如此，可读性成立），登记为观察项而非缺陷（R-5）。
- **理由**：共享单点修复在两端的语义一致性优先于平台各自打补丁；桌面窗口默认底色不随系统明暗是平台事实，共享涂底使其成为无关变量。
- **备选与落选理由**：`DesktopRoot` 包 `P503Theme` + 根级涂底——落选：`Main.kt` 变更在本批无对应缺陷输入（桌面预就绪屏现状可读），留作启用批或后续批与桌面深色观感统一处理；`Window(background = …)` 静态底色——落选：静态值无法随模式切换，且把 scheme 色值复制进平台层造成双源漂移。
- **回退触发**：桌面人工门（§5.3 第 4 项）在深色模式下发现 Ready 路径任一屏可读性不达标（即共享涂底未按设计生效）→ 回本规格修订并把 `DesktopRoot` 根级处理纳入冻结清单；禁止实施批直接改动 `Main.kt`。

### D2-D5 性能判据执行（P6-D5 的 D2 侧义务）

- **决定**：本批运行时性能面 = 零变更（玻璃开关关闭且无调用点；主题变更均为组合期常量求值与涂底，不引入逐帧成本）。P6-D5 定性行门槛在本批人工门内以「与批前基线无可感知回退」执行（同一受管模拟器、同一流程清单）；数值判据与测量方法（冷启动、帧稳定、内存、低端设备）的正式冻结权威为 D3 平台回归批（P6-D5 授权 D2/D3 承接）；启用批先于 D3 开批时，其规格须自带数值冻结条款并作为批准条件（D2-D3 启用路径同款约束）。硬性不变量（效果层不阻塞记账创建/提交/确认、返回、关闭、错误处理）在开关关闭期结构性成立，启用批验收必须保留该不变量为硬门。
- **理由**：P6-D5 把数值判据的冻结权授予 D2/D3；本批无可测的启用态效果，提前造数即假精确（P6-D5 立项理由同源）。
- **备选与落选理由**：本批即冻结数值判据——落选：测量对象（启用态玻璃层）尚不存在，数值无证据效力；不执行任何定性检查——落选：主题修复本身需人工门，顺带记录定性基线零额外成本。
- **回退触发**：不适用（判据执行条款）。定性检查发现可感知回退且无法归因于环境噪声 → 回本规格修订。

## 5. D2 实施批冻结范围

D2 实施批仅在本文件 approved 后开批。冻结写入路径、验证与验收如下；实施批不得扩大范围，发现承载缺口须先回本规格修订并过评审门，不得静默变更。

### 5.1 冻结写入路径

| # | 文件 | 变更 | 条件 |
| --- | --- | --- | --- |
| 1 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt` | 新增共享 `P503Theme`（双 colorScheme 包装）；:199 `MaterialTheme { }` → `P503Theme { }`；包装内加 fillMaxSize 涂底容器（`colorScheme.background`） | 必须 |
| 2 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/`（新目录/单文件） | 玻璃封装层：单包装 composable（backdrop 形态 + Material3 回退形态）+ 编译期 `玻璃开关`（默认 false）；零调用点 | 必须（D2-D3 采纳态） |
| 3 | `app-ui/build.gradle.kts` | commonMain 新增 `implementation("io.github.kyant0:backdrop:2.0.0")`（唯一新增行） | 必须（D2-D3 采纳态） |
| 4 | `android-app/src/main/res/values/themes.xml` | parent `android:Theme.Material.NoActionBar` → `android:Theme.Material.Light.NoActionBar`（单属性） | 必须 |
| 5 | `android-app/src/main/res/values-night/themes.xml`（新文件） | `Theme.UnifiedLedger` parent=`android:Theme.Material.NoActionBar`（现 values 行原样移入） | 必须 |
| 6 | `android-app/src/main/kotlin/com/unifiedledger/android/MainActivity.kt` | `enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))`；`setContent { P503Theme { app() } }` | 必须 |
| 7 | `desktop-app`（全部） | 零改动（D2-D4） | — |

约束：除上表外全部跟踪文件零改动（含 `docs/DECISIONS.md`、`docs/ROADMAP.md`、`P503TabShell.kt`、`P503EditScreen.kt`、`App.kt`、reducer/coordinator、一切 schema/迁移与 CI 配置）。`minSdk 34`/`targetSdk 36`/`compileSdk 37`/全部既有依赖坐标/AGP/Kotlin/CMP/Gradle/JDK 逐值不变。封装文件类名、`玻璃开关` 常量名与涂底容器的精确形态由实施批在上述边界内冻结。

### 5.2 验证命令与人工门（本机，PowerShell，从仓库根执行；docs/CONTRIBUTING.md 资源约束：串行、单 worker、1 GB，每轮前后 `--stop`）

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :app-ui:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :desktop-app:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :android-app:testDebugUnitTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all
.\gradlew.bat --stop
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

- **依赖收敛审计**：对 `app-ui` 的 android 与 jvm 运行时 classpath 各执行一次 Gradle 依赖解析（精确 configuration 名由实施批登记），登记新增项 = `io.github.kyant0:backdrop:2.0.0` + shapes 1.2.0 + org.jetbrains:annotations，且 compose 工件收敛至本仓 1.11.1 线、无任何 1.12 线工件进入（D2-D3 回退触发判据）。
- **单元测试边界**：零新增单测。主题/涂底/玻璃均为 Compose UI 层，`:app-ui:jvmTest`（纯 reducer/校验 JVM 测试）无 compose ui-test harness（D-127/D-128 先例），不可覆盖；视觉验证归下述人工门。既有 `app-ui`/`ledger` 全部套件保持全绿 = 账务行为零变更的自动化证据。
- **模拟器人工门（受管 API 36 模拟器，D-127/D-128 先例面）**：浅色与深色两模式分别全新启动，覆盖首页、编辑页、确认页、应用内失败屏、预就绪错误屏截图；状态栏对比度按 2026-09-06 像素法复验（条带像素采样，判据见 §5.3 第 3 项）；APK 为固定提交的 CI artifact 或本地 `assembleDebug` 产物（执行前核对 SHA-256，D-130/D-132 先例）。uiMode 运行中切换为观察项（平台默认 activity 重建，不在判据内）。
- **视觉对比基线（冻结；选择 = 既有已登记工件，不另做批前基线构建）**：基线 = 2026-09-06 入口门工件——APK `local/artifacts/p6-base/android-app-debug.apk`（CI 提交 `98fc242` 工件，SHA-256 `3a8df9348bc7c03a5e2489320c59f51a4c3842c76129daecaddb4314e60f40c5`）与 `local/artifacts/p6-gates/` 下的 2026-09-06 门截图（均为本地不入库工件，仅以相对名称提及）；D1 为零视觉变更的工具链批，其工具链增量被接受为基线的一部分。D2 批前不执行本地 `assembleDebug` 基线构建（组装证据归属 CI，D-118 R-9）。
- **桌面人工门**：浅色与深色两模式分别启动，覆盖启动屏、编辑页、应用内失败屏截图；判据见 §5.3 第 4 项。
- **CI 边界（冻结）**：CI 零改动（android job/kotlin job 原样）；`:android-app:assembleDebug` 与 Desktop build 的组装证据归属 CI（D-118 R-9）。模拟器与真机门保持人工/用户侧（D-127/D-128/D-130 连续先例）。

### 5.3 验收标准

1. §5.1 表 1-6 全部落地且 diff 限于表列变更；表 7（desktop-app）零 diff；其余跟踪文件零改动。
2. §5.2 全部命令 exit 0；既有测试套件全绿且测试数量无减少；依赖收敛审计记录在案（无 1.12 线工件）。
3. **E2E-R-001 复验判据（P6-ENTRY-THEME-001 闭合门）**：浅色模式状态栏为深图标且图标像素对实际条带背景对比度 ≥ 3:1；深色模式为浅图标且对比度 ≥ 3:1（现状 13.20:1 不得回退）。两项同时 PASS → E2E-R-001 关闭并在实施登记记录；任一 FAIL → 保持未关闭并回本规格修订。
4. **P6-ENTRY-THEME-002 闭合判据**：两模式下首页/编辑页/确认页/应用内失败屏均无「scheme 文本叠错位底色」状态（浅色模式编辑页不再露深窗口底、深色模式首页不再涂浅底）；桌面 Ready 路径同判据（共享涂底生效）。预就绪屏：Android 两模式可读（D2-D1 预就绪接入），桌面维持现状可读（R-5 观察项）。
5. P6-D5 定性行门槛：人工门流程与批前基线无可感知回退；核心流程（创建/提交/确认、返回、关闭、错误处理）行为零变化（既有套件 + 人工门双重证据）。
6. CI 对该精确提交绿（含 APK 组装 artifact）；`ci.yml`/`docs/CONTRIBUTING.md`/`README.md` 零改动的一致性核查记录（含「零改动」结论本身）。

## 6. 边界（明确不做）

- 零账务/RG/导入/对账/schema/迁移变更；共享 UI 的交互与状态机语义零改动（编辑流 Back/Esc、关闭入口、fail-closed 重试、「权威刷新恒回首页」——D-125/D-126/D-129 边界延续；`P503TabShell`/`P503EditScreen`/`App.kt`/reducer 零 diff）。
- 无新界面、无导航库、无 compose ui-test harness；玻璃层零接线（`玻璃开关` 默认关闭、无调用点）；超出封装 backdrop 之外的液态玻璃折射效果不做。
- miuix 整套换肤不做（P6-D3 裁决延续）；主皮库方向保留见 D-117。
- 零 targetSdk/compileSdk/minSdk 变更；零 AGP/Kotlin/CMP/Gradle/JDK 变更；API 35/37 模拟器回归不因本批执行（D3 或独立验证批）。
- 数值性能测量设计不做（正式冻结权威为 D3；启用批先于 D3 开批时自带冻结条款作为批准条件，P6-D5 授权）。
- CI 零改动；模拟器 job/`connectedAndroidTest` 不新增（人工门边界不变）。
- `.external/` 零触碰；`docs/SOURCE_REFERENCES.md` 为本地未跟踪文件，本批不改动。
- 既有决定零改动：D-114/D-117/D-119/D-125/D-128/D-129/D-130/D-131/D-132/D-133 的全部语义与本批正交。

## 7. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | backdrop 2.0.0 为 fork-point pin（上游当前线 2.0.1 面向 CMP 1.12） | 上游演进与本仓栈脱节，未来 CMP 升级需重新选型 | 精确钉定 + 封装层隔离；启用批按届时证据复核六维；CMP 升级批必须重过采纳门 |
| R-2 | 1.11.0 构建 → 1.11.1 运行的 patch 兼容假设失效 | 编译或运行期不兼容 | 依赖收敛审计（无 1.12 线工件）+ 编译 + 人工门三重验证；失败即移除依赖（单行 + 单文件），采纳降级 fallback-only |
| R-3 | 主题 parent 换 Light 产生窗口级副作用（启动闪底变浅、平台控件色、深色模式条带与 scheme background 的色调差） | 观感偏差或门验证项增加 | 人工门双模式截图逐屏判读；色调差登记为观感项不构成门失败；必要时以显式 `windowBackground` 资源回本规格修订 |
| R-4 | Desktop `isSystemInDarkTheme()` 检测在部分平台不可用或不跟随 | 桌面始终浅色 scheme（现状外观） | 桌面人工门双模式实测；检测不可用时回退浅色 = 现状零回归；登记实测结论 |
| R-5 | 桌面预就绪屏维持默认浅色（不随系统深色） | 观感不一致（非可读性缺陷） | 登记为观察项；统一处理归启用批或后续批（`DesktopRoot` 根级包装），本批零改动 |
| R-6 | E2E-R-001 复验失败 | 缺陷保持未关闭，阶段 6 完成条件缺状态栏证据 | 判据已冻结（≥3:1 双模式）；失败回本规格修订，禁止实施批静默改判据 |
| R-7 | 玻璃层休眠期上游/依赖漂移（33 open issues、快速迭代库） | 启用批面临已过期的六维结论 | 启用批强制按届时时点复核 §3 六维；封装层保证未启用面零运行时影响 |

## 8. 批准后的登记路径

**批准记录：** 独立评审 D2SPEC-001..007 APPROVE-WITH-FINDINGS → delta 修订 → 闭环复核全部 CLOSED、终局 APPROVE；主代理按常设授权批准 2026-09-06。

1. 本文件状态行由 `proposal` 翻转为 `approved`（2026-09-06 已执行）。
2. `docs/DECISIONS.md` 登记为下一个全局空闲决定编号 **D-134**（登记时以 `docs/DECISIONS.md` 末尾为准；D-132/D-133 已占用，不得跳号或抢占）：内容 = §4 D2-D1..D2-D5 + §3 证据结论（含 E-5 六维表与落选登记）+ §5 冻结范围与验收 + §7 风险。
3. 登记批不改写本文件正文以外任何既有文档的历史内容；`docs/CURRENT_STATE.md` 与本地检查点同步由主代理执行。
4. D2 实施批在本文件 approved 后开批：独立 worktree、单一 bounded writer、独立规格与质量评审、distinct verifier、主代理最终验收与合并；E2E-R-001 的关闭动作记录于 D2 实施登记（判据 = §5.3 第 3 项）。
5. 玻璃启用批另立规格（挂点接线 + `玻璃开关` 翻转 + 模拟器视觉门 + 数值判据冻结——正式权威为 D3 平台回归批，启用批先于 D3 开批时该规格自带数值冻结条款并作为批准条件），不随本批。

## 边界断言（本批不含）

- 本批规格阶段唯一写入 = 本新文件；`docs/specs/` 既有文档、`docs/DECISIONS.md`、全部源码/资源/构建脚本与 CI 零改动。
- 本文件为设计草案：未经批准不构成实施授权；实施批的 D-134 登记由批准后流程执行，本批不自行登记。
- 本文件不含本机绝对路径、个人数据、真实账务数据或 agent/会话痕迹；本地工件目录仅以相对名称提及且不入库；`.external/` 内容除门查阅声明外零引用。
