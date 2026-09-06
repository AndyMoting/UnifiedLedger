# 玻璃启用批（backdrop 玻璃层启用、底部胶囊接线与 D2IMPL-Q-001 内容裁切修复）设计规格

**日期：** 2026-09-06

**状态：** approved

**Scope:** 本文件是阶段 6 玻璃启用批的设计规格（design + implementation-freeze）：`GLASS_ENABLED` 旗标翻转、`ui/theme/glass` 两件式封装演进、`P503TabShell` 底部胶囊唯一挂点接线与 D2IMPL-Q-001 内容裁切修复归本批实施批执行；模拟器视觉门与数值门由主代理按 D-136 条款 4/5 执行。批准权威 = 用户于 2026-09-06 批准玻璃启用批立项（含建议阈值冷启 ≤+10% 与 PSS ≤+15MB），登记见 `docs/DECISIONS.md` D-136；本文件不改变 D-136 的任何条款，只把其实施面冻结为可执行规格。正文只依赖 tracked 文件与既有登记事实，无新增外部研究输入。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `319c366` 的行号；`.external/` 零触碰）：

- **路线权威**：docs/ROADMAP.md:64（阶段 6「再验证稳定主皮库双主题、AndroidLiquidGlass/Backdrop、Material3 回退路径」——本批即 Backdrop 玻璃路径的启用验证）与 docs/ROADMAP.md:67（完成条件「玻璃效果失败但回退路径稳定时允许阶段 6 收口，且不阻塞核心账务流程」——本批回退许可的出处）。
- **批准依据**：用户于 2026-09-06 批准玻璃启用批立项（含建议阈值冷启 ≤+10% 与 PSS ≤+15MB）；D-134 批准条件「启用批先于 D3 开批须自带冻结条款」因数值冻结权威 D-135（D3 平台回归批）已完成而满足——本规格 §3.1 即该自带冻结条款。
- **决定承接**：
  - **docs/DECISIONS.md D-134（D2 主题与组件批）**：D2-D3 采纳 `io.github.kyant0:backdrop:2.0.0` 并把启用义务移交启用批——旗标翻转（登记名为 `玻璃开关`，实施批冻结名 `GLASS_ENABLED`）、最小接线（挂点唯一 = `P503TabShell.kt:61-66` 底部胶囊 `Surface`，D-134 登记为「未来启用批的唯一预期挂点」）、模拟器双模式视觉验证 + 数值判据冻结为前置门；验收注记 ③（缺陷候选 D2IMPL-Q-001）：GlassLayer 回退路径按 shape 裁切内容、玻璃路径内容未裁切，「启用批必须补内容裁切（如 `Modifier.clip(shape)`）或保持等价语义」；玻璃效果参数冻结值 = `vibrancy()` + `blur(2.dp)` + `lens(12.dp, 24.dp)`。
  - **docs/DECISIONS.md D-135（D3 平台回归批）**：数值判据冻结权威；其性能采样即本批数值门的对照基线（下表）。测量口径与 D-135 一致：冷启 = `adb shell am start -W` TotalTime；内存 = `adb shell dumpsys meminfo` TOTAL PSS。
  - **docs/DECISIONS.md D-133（P6-D3 视觉架构与分层不变量）**：Material3 为稳定基线与默认回退；玻璃封装独立主题与组件层；账务状态、导航、提交与失败状态不依赖玻璃组件；硬不变量「视觉效果不阻塞记账、返回、关闭或错误处理流程」由本批 E2E-R-002 迷你回归证明（§3.2）。

  **D-135 基线（逐值引用，本批数值门对照基线）：**

  | API / 模拟器 | 冷启 TotalTime | TOTAL PSS | 备注 |
  | --- | --- | --- | --- |
  | API 34（`ul_p6_api34`） | 3219ms | ≈104MB | full regression PASS（E2E-R-002 + D2 视觉门） |
  | API 35（`ul_p6_api35`） | 6721ms | ≈98MB | 全新 AVD 首启离群值（fresh-AVD first boot outlier），D-135 如实登记；不作为本批门基线 |
  | API 36（`ul_p5_test`） | 2994ms | ≈119MB | compact regression PASS |
  | API 37 | — | — | 环境阻塞（D-135 三次尝试均失败，延后另批执行） |

- **源码锚点（只读检查）**：
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/GlassLayer.kt:23`：`internal const val GLASS_ENABLED = false`（本批翻转点）；:33-75 自包含 `GlassLayer`（backdrop 与 content 同框渲染——该形态在胶囊挂点无法产生有效效果，演进理由见 §1 条款 2）。
  - `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503TabShell.kt:61-66`：底部胶囊 `Surface`（`shape = RoundedCornerShape(percent = 50)`、`tonalElevation = 3.dp`、`shadowElevation = 6.dp`）——唯一玻璃挂点；:100-104 Scaffold 内容 `Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }`——GlassBackdropSource 落点。

术语与编号：`GLASS_ENABLED` = D-134 交付的编译期玻璃旗标（GlassLayer.kt:23）；`GlassBackdrop`/`GlassBackdropSource`/`GlassSurface` = 本批两件式封装组件（§2.2 契约）；`D2IMPL-Q-001` = D-134 验收注记 ③ 登记的内容裁切移交义务；`E2E-R-002` = D-133 登记的端到端回归清单。本规格 §1 条款 1..6 与 D-136 决定条款 1..6 一一对应、语义逐字承接；本文件不引入新的全局决定编号。

## 1. 决定与裁决记录（= D-136 决定条款 1..6 忠实冻结）

1. **启用旗标**：`app-ui` `ui/theme/glass` 的 `GLASS_ENABLED` 由 `false` 翻转为 `true`，玻璃路径成为激活渲染路径；旗标保持编译期常量与 `internal` 可见性。
2. **接线（挂点唯一）**：`P503TabShell.kt:61-66` 底部胶囊 `Surface`（`RoundedCornerShape(percent = 50)`、`tonalElevation 3.dp`、`shadowElevation 6.dp`）为唯一玻璃挂点。接线设计演进（本批登记）：GlassLayer 原自包含 API（backdrop 与 content 同框渲染）在该挂点无法产生有效效果——玻璃必须采样胶囊后方的应用内容，而内容不在胶囊自身的布局框内；故 `ui/theme/glass` 演进为两件式封装：`GlassBackdropSource`（内容侧注册 layerBackdrop 源；回退态为透明直通 Box）与 `GlassSurface`（胶囊侧 drawBackdrop 玻璃绘制；回退态与现 Surface 参数逐值一致）。全部玻璃代码收敛于 `ui/theme/glass` 包内，`P503TabShell` 仅组合该封装组件、不直接 import `com.kyant.backdrop` 库符号（D-133 分层不变量延续）；玻璃效果参数沿用 D-134 冻结值（`vibrancy()` + `blur(2.dp)` + `lens(12.dp, 24.dp)`）零改动。
3. **D2IMPL-Q-001 关闭**：玻璃路径内容 Box 补 `Modifier.clip(shape)`，玻璃路径与回退路径的内容裁切语义对齐（D-134 验收注记 ③ 移交义务就此履行）。
4. **数值判据冻结**：见 §3.1（逐字登记，本批批准条件）。
5. **视觉门与回归**：见 §3.2。
6. **FAIL 即回退**：见 §4.1（回退梯）。

## 2. 实施范围（冻结）

实施批仅在本文件 approved 后开批。冻结写入路径、组件契约与接线规格如下；实施批不得扩大范围，发现承载缺口须先回本规格修订并过评审门，不得静默变更。

### 2.1 冻结写入路径

| # | 路径 | 变更 | 条件 |
| --- | --- | --- | --- |
| 1 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/theme/glass/` | `GLASS_ENABLED` 翻转 `true`；GlassLayer.kt 重构或拆分为两件式封装（§2.2 契约；GlassLayer 原自包含 API 停用移除——本批前本就零调用点）；回退态语义保持 | 必须 |
| 2 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503TabShell.kt` | 仅接线（§2.3）：胶囊 Surface → GlassSurface；Scaffold 内容 Box → GlassBackdropSource | 必须 |
| 3 | `app-ui` 测试（既有 app-ui 测试目录内） | 仅当实施者发现确有必要时新增聚焦测试；零新增为默认预期 | 可选 |

约束：除上表外全部跟踪文件零改动（含 `docs/`、`desktop-app`、`android-app`、`app-ui/build.gradle.kts`、schema/迁移与 CI 配置）；依赖坐标、AGP/Kotlin/CMP/Gradle/JDK、minSdk/targetSdk/compileSdk 逐值不变；玻璃效果参数（`vibrancy()` + `blur(2.dp)` + `lens(12.dp, 24.dp)`）零改动；`desktop-app` 模块文件零改动。

### 2.2 两件式封装契约（建议签名；实施者仅在 kyant0 2.0.0 API 强制处调整，偏差逐项记入实施登记）

glass/fallback 分支以编译期常量 `GLASS_ENABLED` 判定（延续 GlassLayer.kt 现形态）：

- `@Composable internal fun rememberGlassBackdrop(): GlassBackdrop`——`GLASS_ENABLED = true` 时内部持有 `com.kyant.backdrop.backdrops` 的 `rememberLayerBackdrop()`；回退态返回惰性把手（inert handle）。`GlassBackdrop` 为封装内部类型，库符号不外泄出 `ui/theme/glass` 包。
- `@Composable internal fun GlassBackdropSource(backdrop: GlassBackdrop, modifier: Modifier = Modifier, content: @Composable () -> Unit)`——玻璃路径对内容 Box 应用 `Modifier.layerBackdrop(...)` 注册采样源；回退态为普通 Box（透明直通，零行为变化）。
- `@Composable internal fun GlassSurface(backdrop: GlassBackdrop, modifier: Modifier = Modifier, shape: Shape, content: @Composable () -> Unit)`——玻璃路径：`Modifier.drawBackdrop(backdrop = ..., shape = { shape }, effects = { vibrancy(); blur(2.dp.toPx()); lens(12.dp.toPx(), 24.dp.toPx()) })` + 内容 Box 补 `Modifier.clip(shape)`（D2IMPL-Q-001 修复）+ 阴影对齐 `shadowElevation 6.dp`；回退路径：与现 Surface 参数逐值一致——`Surface(shape = shape, tonalElevation = 3.dp, shadowElevation = 6.dp)` 包裹 content。

签名说明：建议形态与 D-136 条款 2 的组件命名一一对应；若 kyant0 2.0.0 的实际 API（`rememberLayerBackdrop`/`layerBackdrop`/`drawBackdrop` 签名）迫使参数形态调整，实施者可在不改变语义边界（回退态逐值一致、库符号不外泄、效果参数零改动）的前提下调整，偏差逐项记入 D-136 实施登记。

### 2.3 `P503TabShell` 接线规格（仅接线，无其他改动）

- Scaffold 内容（现 `P503TabShell.kt:100-104`）：`Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }` 变为 `GlassBackdropSource` 承载同等内容（`fillMaxSize().padding(innerPadding)` 修饰语义经 `modifier` 参数保持）。
- 底部胶囊（现 `P503TabShell.kt:61-66`）：`Surface(...)` 变为 `GlassSurface(...)`，shape 逐值一致（`RoundedCornerShape(percent = 50)`），`weight(1f)` 修饰保持。
- `FloatingActionButton` 与 `NavigationBar` 代码零改动；`P503TabShell` 不得直接 import `com.kyant.backdrop` 库符号（D-133 分层不变量延续，全部库符号收敛于 `ui/theme/glass` 包内）。

## 3. 验收与门

### 3.1 数值判据冻结（D-136 条款 4 逐字，本批批准条件）

**数值判据冻结（对 D-135 基线，测量口径与 D-135 一致：`adb shell am start -W` TotalTime；`adb shell dumpsys meminfo` TOTAL PSS）**：API 34（模拟器 `ul_p6_api34`）：冷启 TotalTime ≤ 3541ms（基线 3219ms × 1.10）、TOTAL PSS ≤ 119MB（基线 104MB + 15MB）；API 36（模拟器 `ul_p5_test`）：冷启 TotalTime ≤ 3294ms（基线 2994ms × 1.10）、TOTAL PSS ≤ 134MB（基线 119MB + 15MB）。执行口径：fresh install 首次启动为预热不计入判定，随后连续 3 次冷启动逐值登记，以第 3 次（末值）判定，任一超限即判 FAIL。

判据不许实施侧放宽或重解读；判据修订只能回本规格修订并重新过批准门。

### 3.2 视觉门与回归（D-136 条款 5）

- **视觉门**：API 34 模拟器浅/深双模式（首页 + 编辑页）截图证据入 `local/artifacts/p6-gates/`（`glass-` 前缀命名，相对引用不入库）；状态栏对比度按 D-134 像素级方法复验 ≥ 3:1（D-134 登记基线 浅 5.64:1 / 深 18.59:1 不得回退）。
- **E2E-R-002 迷你回归**：启动 → 三 Tab 切换 → 进入编辑 → 提交一笔手工支出 → Created → 总览出现交易行与两侧余额正确；该回归证明 D-133 硬不变量「视觉效果不阻塞记账、返回、关闭或错误处理流程」。
- 两个模拟器门（视觉 + 数值）由主代理执行并登记（D-136 条款 4/5）；模拟器与真机门保持人工/主代理侧（D-127/D-128/D-130/D-134 先例延续）。

### 3.3 门与验证映射（主张 → 命令）

本机 PowerShell 7、仓库根执行；docs/CONTRIBUTING.md 资源约束（串行、单 worker、每轮前后 `--stop`）：

| 主张 | 命令/门 |
| --- | --- |
| 共享 UI 与账务行为零回归 | `.\gradlew.bat :app-ui:jvmTest --offline --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all` |
| 桌面共享路径回归（含 DesktopSkeletonSmokeTest） | `.\gradlew.bat :desktop-app:jvmTest --offline --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all` |
| Android 单元测试 | `.\gradlew.bat :android-app:testDebugUnitTest --offline --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all` |
| Lint | `.\gradlew.bat ktlintCheck --offline --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --warning-mode all` |
| 组装（模拟器门用 APK） | `.\gradlew.bat :android-app:assembleDebug`（`GRADLE_OPTS='-Xmx3072m'` 3GB CI-parity；D-118 R-9「组装证据归 CI」的例外先例——本地模拟器门需要本地 APK，与 D-134 实施登记同口径） |
| 视觉门（§3.2） | 主代理执行；证据入 `local/artifacts/p6-gates/`（`glass-` 前缀） |
| 数值门（§3.1） | 主代理执行；连续 3 次逐值登记，第 3 次（末值）判定 |

CI 零改动（android job/kotlin job 原样）；`:connectedAndroidTest` 不新增。

## 4. 风险与回退

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| GR-1 | 桌面运行时等价性：`GLASS_ENABLED` 位于共享 `app-ui`，翻转后玻璃路径在桌面 JVM 同样激活 | 桌面运行时进入未在桌面面人工验证的渲染路径 | `desktop-app` 模块文件零改动（D-136 边界）；`:desktop-app:jvmTest`（含 DesktopSkeletonSmokeTest）为桌面回归门；桌面观感差异如实登记为观察项 |
| GR-2 | GPU/blur 性能方差：模拟器 GPU 后端（swiftshader/host）差异放大冷启、PSS 或帧成本 | 数值门误判（环境噪声当作回归，或反之） | 判据已冻结（§3.1）；fresh install 首启预热不计入、连续 3 次取末值收敛噪声；任一超限即判 FAIL，不放宽 |
| GR-3 | 对比度回退：玻璃渲染改变胶囊表面与文本/图标的合成结果 | 视觉门 FAIL（状态栏对比度 < 3:1 或胶囊可读性缺陷） | §3.2 判据复验；FAIL 即走 §4.1 回退梯，缺陷候选登记 |
| GR-4 | 依赖解析冲突复现（backdrop 2.0.0 传递工件被拉离已收敛基线） | 编译/解析不可收敛 | D-134 实施登记已收敛（新增工件仅 backdrop 2.0.0 + shapes 1.2.0 + org.jetbrains:annotations），本批零依赖变更；若复现且不可收敛 → §4.1 末级处置 |

### 4.1 回退梯（D-136 条款 6）

**FAIL 即回退**：任一门失败 → `GLASS_ENABLED` 回退 `false`（单常量回退）+ 缺陷候选登记，阶段 6 按稳定 Material3 回退路径收口（docs/ROADMAP.md:67 许可）；`:app-ui:jvmTest`、`:desktop-app:jvmTest`（含 DesktopSkeletonSmokeTest）或 `:android-app:testDebugUnitTest` 失败同属 FAIL；依赖解析冲突不可收敛 → 移除 backdrop 依赖与封装文件、采纳门结论降级 fallback-only 并登记。

## 5. 开放问题

无——全部未决项已闭合：启用批准与建议阈值由用户 2026-09-06 立项批准给定，数值基线由 D-135 逐值登记给定。

## 边界断言（本批不含）

- 本批规格阶段唯一写入 = 本新文件与 `docs/DECISIONS.md` D-136 登记；实施批写入面 = §2.1 表列路径，其余跟踪文件零改动。
- 零账务/RG/导入/对账/schema/迁移变更；共享 UI 交互与状态机语义零改动（编辑流 Back/Esc、关闭入口、fail-closed 重试、「权威刷新恒回首页」延续 D-125/D-126/D-129）；零 CI 变更；`.external/` 零触碰；D-114/D-117/D-119/D-125/D-128/D-129/D-130/D-131/D-132/D-133/D-134/D-135 语义零改动。
- targetSdk/compileSdk/minSdk、依赖坐标与工具链版本零变更；API 35/37 回归不因本批执行；真机门由用户保留。
- 本文件不含本机绝对路径、个人数据、真实账务数据或 agent/会话痕迹；`local/artifacts/` 仅以相对名称提及且不入库。
