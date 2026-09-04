# 录入体验批 D-131 实施规格（R1 金额宽容解析 + R2 时间选择器）

**状态：proposal** — 本文件为录入体验批（D-131）实施规格草案，等待独立评审与用户批准；冻结前不授权任何代码实施。批准后按既有实施路由执行（独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理验收）。

## 1. 概述

**批目标：** 本批 = 录入体验批 D-131，一批两件事：R1 手工支出金额输入的宽容解析（在保持精确转换的前提下放宽小数位输入），R2 手工支出发生时间的选择器（material3 官方 DatePicker + TimePicker，文本输入兜底）。两件事同批实施、同批验收、同批登记 D-131。

**基线：** main = `056e24c`（D-130 已推送，本地 main = origin/main = `056e24c`）。本批在基线之上只新增下述设计内代码，不重开任何已交付批次。

**四项已裁决方向（来源 = 本批规划，用户已裁决，直接作为已批准方向写入本规格）：**

1. **金额放宽 = 补零全量**：R1 采用补零全量方案——小数位不足按账户币种精度右补零；小数位超精度仅当超出位全为 `0` 时接受（精确整除）；不做四舍五入、不做动态精度、不做其他宽容策略。
2. **时间控件 = material3 官方 DatePicker + TimePicker**（文本输入兜底原样保留）：R2 使用 material3 1.9.0 官方 `DatePickerDialog`/`TimePicker`，不引入第三方时间控件库；既有文本输入通道不删除。
3. **时区规则 = 固定 Asia/Shanghai（+08:00）**：选择器结果按固定 `TimeZone.of("Asia/Shanghai")` 换算为 `Instant`，不跟随设备时区；与黄金 fixture 时区锁定同源（Rg01RawJsonDecoder 要求 `timezone == "Asia/Shanghai"`、偏移 `"+08:00"`）。
4. **一批两件事**：R1 与 R2 在同一实施批内交付，共用验证与人工门。

**边界承诺：** 零账务语义变化、零 schema 变化（schema v27 与全部既有迁移文件不变）、零幂等/确认语义变化；除本规格 §3.1 声明的 kotlinx-datetime 外零新直接依赖；不引入 compose ui-test harness；不做设备时区跟随。

## 2. R1 金额宽容解析

### 2.1 domain 新函数 `parseExactDecimalLenient`（建议名，命名自由度在此）

位置：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/ExactDecimal.kt`，与既有 `parseExactDecimal` 同文件独立顶层函数：

```kotlin
fun parseExactDecimalLenient(
    text: String,
    precision: Int,
): Long?
```

**完整语法（冻结）：** `-?(0|[1-9][0-9]*)(\.[0-9]+)?`

- 不 trim（与 strict 相同，trim 由调用方负责）。
- 同族禁令（与 strict 相同）：禁止正号 `+`、禁止前导零（除字面 `0`）、禁止逗号、禁止指数、禁止内部空白（`\.[0-9]+` 至少一位小数，故 `.5`、`35.` 均拒绝）。
- `null` 表示文本不是有效的宽容十进制串（与 strict 同构）。

**补零规则（冻结）：**

- 无小数部分（d = 0）：整数部分右补 `precision` 个零。
- 0 < d ≤ precision：右补 `(precision - d)` 个零至恰好 `precision` 位。

**精确整除规则（冻结）：**

- d > precision：仅当超出位（第 `precision+1` 位起的全部小数位）全为字符 `'0'` 时接受，取前 `precision` 位参与换算；否则返回 `null`。超出位含任一非零数字即拒绝——换算必须是精确的，任何隐式舍入都不允许。

**溢出与约束（冻结，与 strict 相同）：**

- `precision` 必须为 0..18，越界返回 `null`。
- 溢出防护与 strict 相同：`scale = pow10Exact(precision)`（复用既有 `pow10Exact`，0..18 限位），`maxWhole = (Long.MAX_VALUE - (scale - 1L)) / scale`，`whole > maxWhole` 返回 `null`。
- 负号语义与 strict 相同：`-` 后跟语法其余部分，`-0` 允许且为 0。

### 2.2 与 strict 函数的关系

- `parseExactDecimalLenient` 是**独立新函数**；既有 `parseExactDecimal` 本体零改动。
- strict 的两个既有调用方必须保持严格语义不变：
  - `PostingFactsCorrection.kt`（版本更正，`parseExactDecimal` 失败 → `ExactDecimalStringRequired`）；
  - `Rg12Operations.kt`（RG-12 更正，失败 → `DOMAIN_REJECTED`）。
- lenient 与 strict 的包含关系：strict 的接受集是 lenient 接受集的子集，且 d == precision 时两者结果一致；lenient 仅放宽小数位不足/超精度全零两种情况，不改变任何已接受输入的换算结果。
- `PeriodicAllocation.kt` 中的独立 `parseExactDecimalMinorUnits`（不许负号、要求 > 0）与本批无关，**不得触碰**。

### 2.3 wrapper 语义（`ParseManualExpenseAmount`）

位置：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ParseManualExpenseAmount.kt`。

- **strict 优先**：现有行为逐字节保留——仅 trim 两端 ASCII space/tab/CR/LF；trim 后空 → `EMPTY`；内部 ASCII 空白 → `INTERNAL_WHITESPACE`；然后先调用既有 `parseExactDecimal`（strict），成功即返回（现有 4 条固定有效向量全部仍走 strict 路径，结果逐字节不变）。
- **失败才走 lenient**：strict 返回 `null` 时，再调用 `parseExactDecimalLenient(trimmed, currency.precision)`；仍为 `null` → `INVALID_FORMAT`。
- `EMPTY`/`INTERNAL_WHITESPACE` 语义与错误类型映射不变；`currency.precision` 来源不变（UI 永不提交独立 precision）。
- 产品 precision 仍始终来自所选账户 `CurrencyUnit`；CNY fixture 固定为 `CurrencyUnit("CNY", 2)`。

### 2.4 冻结向量表（实施照抄）

以下向量在 wrapper 层冻结（`ParseManualExpenseAmountTest` 主表；domain 层 `parseExactDecimalLenient` 测试使用同值、含负号与精度边界用例）。

**有效向量（CNY precision 2）：**

| 输入 | 期望 minorUnits | 路径 |
| --- | --- | --- |
| `"35.80"` | `3580` | strict（原有） |
| `" 35.80\t"` | `3580` | strict + trim（原有） |
| `"0.00"` | `0` | strict（原有） |
| `"-0.01"` | `-1` | strict（原有） |
| `"35.8"` | `3580` | lenient 补零（新增） |
| `"35.800"` | `3580` | lenient 整除（新增） |
| `"11"` | `1100` | lenient 补零（新增） |
| `"11.0"` | `1100` | lenient 补零（新增） |
| `"0"` | `0` | lenient 补零（新增） |
| `"35.810"` | `3581` | lenient 整除（新增） |
| `"-11"` | `-1100` | lenient 补零（新增） |

**有效向量（JPY precision 0）：**

| 输入 | 期望 minorUnits | 路径 |
| --- | --- | --- |
| `"358"` | `358` | strict（原有） |
| `"358.0"` | `358` | lenient 整除（新增） |
| `"35.00"` | `35` | lenient 整除（新增） |

**JPY(0) 下 `"35.00" → 35` 的精确整除语义（规格写明）：** d = 2 > precision = 0，超出位即全部小数位 `"00"` 全为 `'0'`——`35.00` 在零精度币种下与 `35` 是同一精确金额，整除接受，无任何舍入。对比：若超出位含非零数字（如 JPY 下 `"35.01"`），无法精确表示为整数 minor units，拒绝。

**拒绝向量（错误类型同现 wrapper 映射）：**

| 输入 | 期望错误 | 说明 |
| --- | --- | --- |
| `""` | `EMPTY` | 原有 |
| `" "` | `EMPTY` | 原有 |
| `"+35.80"` | `INVALID_FORMAT` | 正号（原有） |
| `"035.80"` | `INVALID_FORMAT` | 前导零（原有） |
| `"35,80"` | `INVALID_FORMAT` | 逗号（原有） |
| `"3.58e1"` | `INVALID_FORMAT` | 指数（原有） |
| `"35 .80"` | `INTERNAL_WHITESPACE` | 内部空白（原有） |
| `"92233720368547758.08"` | `INVALID_FORMAT` | Long 溢出（原有） |
| `"35.812"` | `INVALID_FORMAT` | 超出位 `2` 非零（新增） |
| `".5"` | `INVALID_FORMAT` | 缺整数部分（新增） |
| `"011"` | `INVALID_FORMAT` | 前导零（新增） |

原 D-119 §3.3 固定拒绝向量中的 `"35.8"`、`"35.800"` 在本批转为有效（上表），是本规格对 D-119 §3.3 的唯一窄替代，见 §7；其余全部冻结向量与错误类型不变。

### 2.5 UI 提示文案（定稿）

- `P503EditScreen.kt` 金额框（现 :133-147）label「金额（CNY）」不变（币种仍随所选账户显示）。
- 辅助文案（非错误态，现 :144「精确金额，两位小数」）定稿为：**「金额示例：11、35.8 或 35.80」**。
- 错误态文案不变（「请输入金额」/「金额格式无效」）。

### 2.6 测试

- **domain 层**（`ledger-domain` commonTest，新增）：`parseExactDecimalLenient` 独立测试——§2.4 全向量、`precision` 边界 0/18、越界 `precision`（-1/19）返回 null、溢出防护、负号与 `-0`。
- **application 层**（`ledger-application` commonTest）：`ParseManualExpenseAmountTest` 改造——既有 `"35.8"`/`"35.800"` 拒绝断言按 §2.4 反转（移入有效向量），其余既有断言保留，并追加 §2.4 新向量（含错误类型断言）；JPY(0) 有效/拒绝向量；`" 35.8\t"`（trim + lenient 组合，→ 3580）。
- **UI 层不回归**：`P503DraftValidationTest` 既有两用例必须保持绿——CNY(2) 下 `"35.80"` 合法；JPY(0) 下 `"35.80"` 拒绝（d=2 > 0 且超出位 `"80"` 非零，lenient 同样拒绝，语义不变）。
- **UI 层补充用例**（`P503DraftValidationTest` 新增）：CNY(2) 下 `"35.8"`、`"11"` 合法（`isValid` true）；CNY(2) 下 `"35.812"`、`"011"` 不合法（`amountFormatError` 非 null）；JPY(0) 下 `"358.0"`、`"35.00"` 合法。
- `P503ReducerTest` 既有用例全部不回归（reducer `UpdateAmount` 仅存文本，本批零改动，见 :84-85/:224-225/:250-251）。

## 3. R2 时间选择器

### 3.1 依赖与证据门

**声明：** `app-ui/src/commonMain` 增加 `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")`（显式坐标）。这是本批**唯一新直接依赖**；两端组合根（`desktop-app`/`android-app`）零构建脚本改动（经 app-ui 传递）。

**证据门三条（本批规划已核实，规格复述）：**

1. **官方维护**：`kotlinx-datetime` 是 JetBrains 官方 Kotlin 库（`org.jetbrains.kotlinx` 组织），与 `kotlin.time.Instant`（stdlib 2.4.10，无时区能力，`kotlin/time` 包内无 TimeZone 类）互补，是本仓库获取 `TimeZone`/`LocalDateTime` 能力的标准来源。
2. **许可**：Apache-2.0，与本仓库既有依赖许可族一致。
3. **非新增入图（传递约束对齐，按 target 分述）**：
   - **desktop/jvm 侧（非新增）**：仓库实际解析坐标 `org.jetbrains.compose.material3:material3:1.9.0`（CMP 1.11.1 解析；仓库无 version catalog，`gradle/` 下只有 wrapper）的 `.module` 元数据 `metadataApiElements` variant **requires kotlinx-datetime 0.7.1**——kotlinx-datetime 已作为传递依赖存在于该侧解析图内（本地 Gradle 缓存含 `kotlinx-datetime/0.7.1` 与 `material3-desktop-1.9.0.jar`，后者内含 96 个 DatePicker/TimePicker 相关类，如 `DatePickerDialog_skikoKt`、`TimePickerDialogKt`、`AnalogTimePickerState` 等）。声明为直接 implementation 只改变依赖的可见性，不改变该侧解析图内容，零版本协商风险。
   - **Android 侧（本批新增）**：解析新增 `kotlinx-datetime-android 0.7.1` 及其传递 `kotlinx-serialization-core 1.6.2`；版本由本批显式坐标单一来源指定，与 desktop/jvm 侧传递版本一致，无版本冲突。

**类型换算注意（写入规格供实施对照）：** kotlinx-datetime 0.7.1 中 `kotlinx.datetime.Instant` 是 `kotlin.time.Instant` 的 typealias（0.7.0 移除原类、0.7.1 以别名回归），不存在 `toKotlinInstant()` 互转扩展；换算链固定为与 §3.3 同款：`LocalDateTime.toInstant(TimeZone.of("Asia/Shanghai"))` 直接返回 `kotlin.time.Instant`，无需任何互转，直接走既有 `onUpdateOccurredAt(Instant)` 通道。实施须直接使用 `kotlin.time.Instant`，避免引用已废弃的 `kotlinx.datetime.Instant` 别名（产生 deprecated 告警）。

### 3.2 交互冻结

- 发生时间字段（`P503EditScreen.kt` `OccurredAtField`，现 :216-231）新增**尾随入口**（trailing icon 槽位；因 material3 1.9.0 无 icons 传递依赖，入口用可聚焦文本控件承载，配 `contentDescription`，见 §3.6），点击打开 `DatePickerDialog`。
- **流程：** `DatePickerDialog` 确认日期 → `TimePicker`（对话框）确认时间 → 组合 `LocalDateTime` → 按 §3.3 换算为 `Instant` → 经既有 `onUpdateOccurredAt(Instant)` 通道写入 draft（reducer 行为零改动，仍为 `draft.copy(occurredAt = event.instant)`）。
- **文本框同步：** 选择完成后，本地文本状态（现 :63-64）同步为该 `Instant` 的 ISO 串（`draft.occurredAt.toString()`，即 UTC 形式）。这保证 `P503DraftValidation.occurredAtTextReconciles`（:64-73）的严格相等语义不变——文本重新解析结果与 draft.occurredAt 逐值相等，Continue 门行为与现有文本输入完全一致。
- **文本输入兜底原样保留：** 屏幕层 `Instant.parse` 成功才派发（现 :161-165）、解析失败显示「时间格式无效」（:155-157 文案保持）等现有行为零改动。选择器只是另一条写入通道，不是替代通道。
- 日期/时间选择器的初始值：draft 已有 `occurredAt` 时取其 Asia/Shanghai 本地日期/时间；无 draft 值时用当前日期/时间（组合根注入的 `LedgerClock`）。

### 3.3 时区规则与换算（冻结）

- **固定时区：** `TimeZone.of("Asia/Shanghai")`（偏移固定 +08:00），与黄金 fixture 时区锁定同源（`Rg01RawJsonDecoder.kt:148-164` 要求 `timezone == "Asia/Shanghai"`、偏移 `"+08:00"`）。**不做设备时区跟随**——不读系统时区、不提供时区选择。
- **换算：** 选择器产出的 `LocalDate` + `LocalTime` → `LocalDateTime` → `.toInstant(TimeZone.of("Asia/Shanghai"))` → `kotlin.time.Instant`（ISO 串为 UTC 形式）。
- **历史 DST 空档（fail-closed）：** Asia/Shanghai 1991 年前存在夏令时（含 DST 跳变空档）。若用户选择的本地时间落在 DST gap（该本地时刻不存在），换算结果不得猜测或静默偏移——校验必须拒绝（fail-closed）：换算后做 round-trip 一致检查（`Instant.toLocalDateTime(TimeZone.of("Asia/Shanghai"))` 回推结果与所选 `LocalDateTime` 不等即为无效），无效则视为时间格式错误，阻止进入确认页，并在字段上显示既有错误态。文本输入路径不受影响（UTC 输入无 gap 概念）。

### 3.4 确认页显示（冻结格式）

`P503ConfirmationScreen.kt:41` 现渲染 `Text("发生时间：${draft.occurredAt ?: "—"}")`。定稿为同时含本地时间与 UTC 的冻结格式：

**`发生时间：2026-01-15 08:30（UTC+8）＝ 2026-01-15T00:30:00Z`**

- 格式定义：`YYYY-MM-DD HH:mm`（Asia/Shanghai 本地 24 小时制，由 `Instant.toLocalDateTime(TimeZone.of("Asia/Shanghai"))` 得出）+ `（UTC+8）` + `＝ ` + 原 ISO UTC 串（`draft.occurredAt.toString()`）。
- **（UTC+8）标注限定：** 该标注对 1991 年后日期精确（Asia/Shanghai 自 1991 年起无夏令时）；1986–1991 DST 窗口内仅显示本地墙钟（偏移标注不适用），且此类日期若落在 gap 内本就按 §3.3 拒绝。
- 本地时间展示仅用于人类可读性；存储/提交/校验仍用 `Instant`（UTC），与 domain `TransactionTimes.occurredAt: Instant`（`Values.kt:61`）与 `ManualExpenseSaveInput.kt:15` 保持一致。
- 建议将本地时间格式化与换算做成 app-ui 纯函数（JVM 可测），不内联在 composable 中。

### 3.5 桌面 Esc 语义（必须遵守）

- **冻结行为：** 选择器对话框打开期间，Esc **不得关闭底层编辑页**；Esc 由对话框自吸收（仅关闭对话框本身，首选）或仅触发对话框关闭——两种实现路径均接受，但底层编辑页必须保持打开、draft 保持完整。
- **现状风险（写入规格）：** 既有 `DesktopEscBackHandler`（desktop `Main.kt:106-133`）是 JVM 级 `KeyboardFocusManager` dispatcher，其注释明示「demo has a single window and no dialogs in the editor flow」——本批引入对话框后该假设不再成立，实施必须保证 dispatcher 与对话框共存时不越权（例如：对话框打开时禁用 back handler 或将 Esc 让渡给对话框）。
- **此点列入选期桌面键盘人工门必验项**（§3.7），Android 侧系统返回在对话框打开时由官方 Dialog 自吸收，行为天然满足冻结语义，但仍列入模拟器人工门核对。

### 3.6 a11y（逐条落位）

- 官方控件自带语义：`DatePickerDialog`/`TimePicker` 为 material3 官方组件，其日期/时间/确认/取消控件自带可聚焦与语义标注，不额外包裹会破坏语义的容器。
- **尾随入口：** 可聚焦控件（material3 自带 48dp 触达 enforcement），配 `contentDescription = "选择发生时间"`；**不手工叠加 `minimumInteractiveComponentSize()`**（D-127 教训：material3 自带 enforcement 的控件上手工叠加会产生双重命中层缺陷；既有 48dp 约定注释见 `P503EditScreen.kt:80-85`）。
- 字段错误语义沿用既有模式：文本兜底解析错误仍走 `supportingText` + `isError`（既有实现），选择器换算无效（§3.3 fail-closed）同样映射为字段错误。
- 结果/状态提示沿用 liveRegion 先例（`P503ResultScreen.kt:43/:70/:100`）与 contentDescription 先例（`P503TabShell.kt:94`）。
- 键盘可达：对话框内全部操作用 Tab/Enter/空格可完成；关闭对话框后焦点回到发生时间字段（或至少不丢失在不可见控件上）。

### 3.7 人工门计划

- **Android 模拟器**（既有 API 36 模拟器先例，`CURRENT_STATE.md:62` / D-127、D-128）：安装 CI APK（`android-debug-apk-<sha>` 工件，随授权 push 后生成，D-127 先例）——尾随入口打开 DatePicker → 选日期 → TimePicker → 选时间 → 确认后文本框同步为 ISO 串 → Continue 通过 → 确认页显示冻结格式 → 提交链完整；系统返回在对话框打开时不关闭编辑页；TalkBack 走查（入口可聚焦/可读、对话框控件可感知、确认后焦点行为）。
- **桌面**（`:desktop-app:run` + Win32 自动化）：选择器交互全流程；**Esc 语义必验**——对话框打开按 Esc 仅关对话框、编辑页保持打开且 draft 完整；对话框关闭后编辑页 Esc 语义恢复（既有行为）。
- 人工门随授权 push 后按 CI APK 执行；CI 成功不构成人工证据已完成（CONTRIBUTING 既有纪律）。

## 4. 边界（本批不改动）

- 不改 `parseExactDecimal` 本体；更正（`PostingFactsCorrection.kt`）与 RG-12（`Rg12Operations.kt`）调用方保持严格语义；`PeriodicAllocation.kt` 的 `parseExactDecimalMinorUnits` 不触碰。
- 不动账务核心、schema（v27）/迁移、幂等（requestId/UnknownCommit 语义）、确认/取消语义、reducer 状态机与事件集（本批对 reducer 零改动，仅新增 UI 层写入通道）。
- 不引入 compose ui-test harness（app-ui 测试仍为纯 reducer/状态机 JVM 测试）。
- 不做设备时区跟随、不做时区选择 UI、不做 DST 自动适配（仅 fail-closed 拒绝）。
- 除 kotlinx-datetime 0.7.1 外零新直接依赖；零新 Gradle 模块；零新正式交易类型。

## 5. 验证命令

资源旗标遵循 CONTRIBUTING：本机 16 GB 主机上 Gradle/Kotlin 验证必须串行；每次验证前后 `gradlew --stop`；单命令 heap 不变（`GRADLE_OPTS=-Xmx1024m`、`-Dkotlin.daemon.jvmargs=-Xmx1024m`、`--max-workers=1`、`--no-daemon`、`--rerun-tasks`、`--warning-mode all`）。一次只运行一个命令。

本批受影响集合（按顺序）：

```powershell
.\gradlew.bat --stop
$env:GRADLE_OPTS='-Xmx1024m'
.\gradlew.bat :ledger-domain:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :ledger-application:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :app-ui:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :desktop-app:jvmTest --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :desktop-app:build --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat :android-app:compileDebugKotlin --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
.\gradlew.bat ktlintCheck --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat --stop
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

- `:ledger-domain:jvmTest`：新 lenient 函数测试 + strict 既有测试回归。
- `:ledger-application:jvmTest`：wrapper 全向量 + 更正/RG-12 相关既有测试回归。
- `:app-ui:jvmTest`：`P503DraftValidationTest` 既有 + 新增用例、`P503ReducerTest` 不回归、时间换算/格式纯函数新测试。
- `:desktop-app:jvmTest`：桌面侧回归（空库引导、一次手工支出 Created、UUIDv7 文本、逐币种平衡与重放 NoChange，CONTRIBUTING 既有步骤）。
- `:desktop-app:build`：Desktop 构建门（与 A10 对齐；与 CI 的 Desktop app build 步骤一致）。
- `:android-app:compileDebugKotlin`：Android 编译门（本地 assembleDebug 按 R-9 既有登记归 CI，APK 工件用于人工门）。
- `ktlintCheck`：全部模块（与 CI Ktlint check 步骤一致；本批受影响为 ledger-domain/ledger-application/app-ui/desktop-app/android-app）。
- `project_docs`：正式文档验证。
- 桌面键盘人工门使用 `:desktop-app:run`（§3.7）。
- 文档自检：`verify-project.ps1 -Scope docs`（本规格为 docs 范围变更）。

## 6. 验收判据（供评审与 verifier 对照）

- **A1**：§2.4 冻结向量全过——domain 层 `parseExactDecimalLenient` 与 wrapper 层 `ParseManualExpenseAmount` 测试全部绿（有效 11 + JPY 3 + 拒绝 11，错误类型逐条断言）。
- **A2**：strict 回归——`parseExactDecimal` 本体零改动；`PostingFactsCorrection`/RG-12 相关既有测试全绿（`:ledger-domain:jvmTest`、`:ledger-application:jvmTest` 全量）。
- **A3**：wrapper 边界语义不回归——`EMPTY`/`INTERNAL_WHITESPACE`/trim/内部空白检查逐字节保持；`P503DraftValidationTest` 既有两用例（CNY `"35.80"` 合法、JPY 拒绝）保持绿。
- **A4**：UI 层补充用例全过（§2.6），`P503ReducerTest` 既有用例零回归（reducer 零改动）。
- **A5**：R2 交互——尾随入口打开 DatePickerDialog → 确认 → TimePicker → 确认 → 文本框同步为 UTC ISO 串 → `occurredAtTextReconciles` 严格相等 → Continue 通过（JVM 纯函数测试 + 双端人工门）。
- **A6**：换算正确性——固定 `TimeZone.of("Asia/Shanghai")`（+08:00）换算 round-trip 一致；DST gap 本地时间 fail-closed 拒绝（纯函数测试覆盖，含 1991 前 gap 用例）。
- **A7**：确认页显示冻结格式——本地时间 + `（UTC+8）` + `＝ ` + UTC ISO 串（纯函数测试 + 人工核对）。
- **A8**：桌面 Esc 语义——选择器对话框打开时按 Esc 仅关对话框，编辑页保持打开、draft 完整；对话框关闭后编辑页 Esc 语义恢复（桌面键盘人工门必验项）。
- **A9**：a11y——尾随入口 contentDescription、48dp 触达（不手工叠 minimumInteractiveComponentSize）、对话框内键盘可达、焦点归还；Android TalkBack 人工走查通过。
- **A10**：双端构建——`:android-app:compileDebugKotlin` 与 `:desktop-app:build`（或 CI assembleDebug，按 R-9 归 CI 纪律）成功。
- **A11**：静态与文档门——`ktlintCheck`、`project_docs`、`verify-project -Scope docs` 全绿。
- **A12**：边界核查——`parseExactDecimal` 本体与更正/RG-12 调用方零改动；`PeriodicAllocation` 零触碰；schema v27 与迁移零变化；除 kotlinx-datetime 0.7.1 外零新直接依赖；零 compose ui-test harness；设备时区不参与任何换算。

## 7. D-131 登记预告

本批实施完成后登记 **D-131（录入体验批：R1 金额宽容解析 + R2 时间选择器）**，登记内容至少包括：

- 四项已裁决方向（§1）与实施落点（R1：`parseExactDecimalLenient` + wrapper strict 优先回退；R2：material3 官方选择器 + kotlinx-datetime 0.7.1 固定 Asia/Shanghai 换算 + 文本兜底保留）。
- **对 D-119 §3.3 的窄替代声明：** 在手工支出 wrapper（`ParseManualExpenseAmount`）范围内，D-119 §3.3 固定拒绝向量中的 `"35.8"`、`"35.800"` 转为有效（补零/整除），其余全部冻结向量与错误类型不变；strict 领域函数及更正/RG-12 路径不受影响；wrapper 的 trim/EMPTY/内部空白语义与 precision 来源不变。该窄替代同时覆盖 D-120 实施规格（`docs/specs/2026-08-30-p5-03-demo-surface-b-implementation-design.md`）§4.1（:141 同款固定向量）与 §6.3（:438-441 固定金额向量）的相应条目，一并声明按本规格反转；实施与登记以本规格 §2.4 冻结向量表为准。该替代经用户裁决（裁决方向①）与本规格冻结，登记时引用本规格文件名与状态。
- 验证证据（A1..A12）与边界声明（§4）。
- 关联决定：`D-119`、`D-120`（P5-03 承接边界）。
