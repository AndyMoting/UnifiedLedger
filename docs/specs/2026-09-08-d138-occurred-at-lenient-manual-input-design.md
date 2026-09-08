# 发生时间手工输入宽容解析批 D-138（设计规格）

**状态：** proposal — 本文件为发生时间手工输入宽容解析批（D-138）的实施规格草案，也是本次派发（part 1 of 2）的唯一写入物；配套决定条目（part 2）由另一派发单独写入，不属本文件。等待独立评审与用户批准；冻结前不授权任何代码实施。批准后按既有实施路由执行（独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理验收）。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `177545e` 的行号；`.external/` 只读）：

- **主检出根指引**：主检出 `AGENTS.md`（worktree handoff 指定入口；外部证据门、验证分工与高风险路由按其执行）。
- **D-131 录入体验批规格**：`docs/specs/2026-09-03-input-ux-amount-time-design.md`——R1 金额宽容解析（§2，`ParseManualExpenseAmount` wrapper 先例）、R2 发生时间选择器（§3.3 固定 Asia/Shanghai 换算与 DST fail-closed、§3.4 确认页冻结格式、§3.5 桌面 Esc 语义）。
- **源码锚点（只读检查）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503DraftValidation.kt:64-73`（`occurredAtTextReconciles` Continue 门与 finding P503IMPL-Q-001：文本必须重解析为与 draft instant 逐值相等）、`:28-49`（`errors()`）；`OccurredAtPicker.kt:18`（`occurredAtTimeZone` 固定 Asia/Shanghai）、`:26-29`（`occurredAtFromLocalDateTime` round-trip fail-closed）、`:54-58`（`occurredAtDisplayText`）；`P503EditScreen.kt:96`（`occurredAtText` 状态）、`:218-236`（字段与文本变化接线，`Instant.parse` 严格门 :229）、`:241-254`（Continue 门）、`:221-226`（错误与辅助文案）、`:317-343`（选择器确认写入 `onUpdateOccurredAt`）；`P503LedgerFacade.kt:24`（`parseAmount` 注入先例）；`P503UiEvent.kt:39-41`（`UpdateOccurredAt(instant: Instant)`，非空 instant，reducer 直接替换 draft）；`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ParseManualExpenseAmount.kt`（R1 解析器形状先例：trim → 空白/内部空白拒绝 → sealed `Result Valid/Invalid`）。
- **用户裁决（原文，本批直接作为已批准方向）**：「不要支持"昨天 20:00"这种相对表达」。

术语与编号约定：`本批` = D-138 发生时间手工输入宽容解析批；`选择器` = D-131 R2 的 material3 DatePicker/TimePicker 路径；`lenient 解析器` = 本批新建的 `ParseManualExpenseOccurredAt`。本批局部风险编号 R-1..R-5 仅在本批内稳定使用，与全局 `docs/DECISIONS.md` 决定编号空间（如 D-131）不同。

## 1. 目的与范围

### 1.1 目的

- 给「发生时间」字段一条真正的手工打字通道，配宽容解析，与选择器对称（D-131 R1 为金额做过同样的事：金额文本输入放宽小数位后仍精确换算，wrapper 层 strict 优先）。
- 文本兜底（D-131 §3.2 原样保留的 `Instant.parse` 通道）从「仅 ISO 8601 完整 instant」放宽为「ISO instant + 本地墙钟友好格式 + 省年格式」，同时保持换算精确性、时区冻结与 fail-closed 纪律：所有墙钟输入一律按固定 Asia/Shanghai 换算为 UTC instant，DST 空档一律拒绝，绝不猜测。
- Continue 门（P503IMPL-Q-001）不变量保持不变：显示文本必须重解析为与 draft instant 逐值相等，门才放行。

### 1.2 范围（冻结）

**范围内：**

- `ledger-application`：新建纯解析器（§2.1）。
- `app-ui`：校验（`P503DraftValidation.kt` 的门改用 lenient 解析器）、编辑屏接线（`P503EditScreen.kt` parse-on-type + 文案）、facade 注入（`P503LedgerFacade.kt`）。
- 测试在范围内：新解析器测试、`P503DraftValidationTest` 与 `P503ReducerTest` 新增用例，既有套件保持绿（§3、§4）。

**范围外（本批明确不做）：**

- **零 schema 变更**：schema v27 与全部既有迁移文件不变；`ledger-domain` 零改动（解析与换算均落在 application/app-ui 层）。
- **零桌面 Esc/back 行为变更**：D-137 的对话框 Esc 语义与 `DesktopEscBackHandler` 零改动（§2.4、风险 R-5）。
- **零新坐标**：不新增任何未过门依赖；`ledger-application` 模块新增对既有已过门坐标 `org.jetbrains.kotlinx:kotlinx-datetime:0.7.1` 的声明（坐标已被 D-131 §3.1 过门、已存在于 app-ui 解析图；变更见 §3 表）。既有 `P503UiEvent` 写通道复用不变。
- **相对/自然语言时间表达一律不支持**（用户裁决，原文照录）：「不要支持"昨天 20:00"这种相对表达」——任何相对表达（如 昨天/明天/上周）必须解析为 `Invalid`（§2.2 拒绝向量）。
- 不做其他 locale 格式（如 `2026/09/15`、`09-15-2026`、`8:30 PM`、`2026年9月15日` 等一律拒绝）；不做时区选择、不做设备时区跟随；不做 DST 自动适配（仅 fail-closed 拒绝，同 D-131 §3.3）。

## 2. 机制（冻结）

### 2.1 新纯解析器 `ParseManualExpenseOccurredAt`（ledger-application）

位置：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ParseManualExpenseOccurredAt.kt`（新文件），镜像 `ParseManualExpenseAmount` 的形状：

```kotlin
enum class OccurredAtFormatError { INVALID_FORMAT, DST_GAP }

class ParseManualExpenseOccurredAt {
    sealed interface Result {
        data class Valid(val instant: Instant) : Result
        data class Invalid(val reason: OccurredAtFormatError) : Result
    }
    fun parse(text: String, clock: LedgerClock): Result
}
```

- **输入**：原始文本 + 记账时钟（年补齐用）。`clock` 为组合根注入的既有 `LedgerClock`（与选择器初始值同源，`P503EditScreen.kt:263/:293` 先例），不新增第二时钟来源。
- **空白与内部空白（冻结）**：仅 trim 两端 ASCII 空白（space/tab/CR/LF）；trim 后空串 → `Invalid(INVALID_FORMAT)`；除 (b)/(c) 语法中唯一允许的单个空格分隔符外，出现任何其他 ASCII 空白（如双空格）→ `Invalid(INVALID_FORMAT)`。调用方（编辑屏）约定在解析前拦截空白文本（既有 missing-field 路径），此返回仅为总函数性防御。
- **年补齐（格式 (c) 用）**：补齐年 = `clock.now()` 在 Asia/Shanghai 的本地年（`clock.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).year`）——墙钟输入与补齐同在 Asia/Shanghai 墙钟帧内，此为文档化选择。
- **换算（冻结，与选择器同款语义）**：所有墙钟格式先构造 `LocalDateTime`，再按固定 `TimeZone.of("Asia/Shanghai")`（与 `occurredAtTimeZone` 同值，+08:00）执行选择器同款 round-trip 一致性检查（`occurredAtFromLocalDateTime` 结构：`toInstant` 后回推 `toLocalDateTime` 逐字段相等才接受）：一致 → `Valid(instant)`；不一致（本地时刻不存在，即历史 DST 空档）→ `Invalid(DST_GAP)`（fail-closed，绝不猜测、绝不静默偏移）。
- **DST 空档路径的覆盖方式（结构保证）**：Asia/Shanghai 自 1991 年起无夏令时，1991 年后墙钟到 instant 的换算为双射，`DST_GAP` 对现代日期不可达；该路径由结构与测试双重覆盖：(i) 结构上，拒绝是 round-trip 检查的内建结果而非特判分支，与选择器实现同构（同一 zone 数据源，任何时区数据变化对两侧同等生效，不存在单侧漂移）；(ii) 测试上，冻结既有已测量日期向量（OccurredAtPickerTest.kt:33-36 已冻结）：`1986-05-04 02:30` 与 `1991-04-14 02:30` 解析为 `Invalid(DST_GAP)`（见 §2.3 拒绝向量）；邻近接受向量同日 `01:30`/`03:30`（选择器侧先例 OccurredAtPickerTest.kt:40-48）同为解析器测试集。UTC 格式（(a)）无 gap 概念，天然不受影响。
- **实现落点（冻结）**：换算规则在 app-ui（`OccurredAtPicker.kt`，选择器零改动）与 ledger-application（新解析器）各有一份实现——模块边界所致，故意重复；两侧 DST 向量（本节冻结日期）为一致性纽带；未来若抽取共享 helper 需触碰 D-131 已交付代码，另立批次。

### 2.2 接受格式（冻结，附示例）

| # | 格式 | 语义 | 示例 → 期望 instant |
| --- | --- | --- | --- |
| (a) | `YYYY-MM-DDTHH:mm(:ss)?Z`（ISO 8601 instant，秒可选） | 直接解析为 instant，不经过墙钟换算 | `2026-09-15T00:30:00Z` → `2026-09-15T00:30:00Z`；`2026-09-15T00:30Z` → 同刻 |
| (b) | `YYYY-MM-DD[ 或 T]HH:mm` 与 `YYYY-MM-DD[ 或 T]HH:mm:ss`（空格或 `T` 分隔，允许 `H:mm` 单位数小时） | Asia/Shanghai 墙钟 → 换算 | `2026-09-15 08:30` → `2026-09-15T00:30:00Z`；`2026-09-15 08:30:15` → `2026-09-15T00:30:15Z`；`2026-09-15T08:30` → `2026-09-15T00:30:00Z`；`2026-09-15 8:30` → `2026-09-15T00:30:00Z` |
| (c) | 省年 `MM-DD HH:mm`、`MM-DD HH:mm:ss`、`MM-DD` | 年由记账时钟补齐（§2.1；时钟依赖为文档化选择） | （固定时钟 2026-09-08）`09-15 20:00` → `2026-09-15T12:00:00Z`；`09-15 20:00:05` → `2026-09-15T12:00:05Z`；`09-15` → `2026-09-14T16:00:00Z` |
| (d) | `YYYY-MM-DD`（仅日期） | 00:00 本地开始时刻（文档化语义选择） | `2026-09-15` → `2026-09-14T16:00:00Z` |

格式细则（冻结）：

- 月/日必须两位数（`MM`/`DD` 零填充；`2026-9-5` 拒绝）；年必须四位数；时允许 `H`/`HH`，分/秒必须两位数。
- (a) 仅接受 `Z` 结尾；不带 `Z` 的 `T` 分隔形式属于 (b) 墙钟格式；(b) 不接受 `Z` 结尾（`2026-09-15 08:30Z` 拒绝）。小数秒、其他偏移（如 `+08:00`）不在任何格式内（拒绝）。
- (d) 与 (c) 的 `MM-DD` 均取 00:00 墙钟开始时刻并走 §2.1 换算。

**拒绝向量（冻结，`Invalid(INVALID_FORMAT)` 除注明外）：**

| 输入 | 说明 |
| --- | --- |
| `昨天 20:00`、`明天 09:00`、`上周三 12:00`、`上周` | 相对/自然语言表达——用户裁决原文「不要支持"昨天 20:00"这种相对表达」，一律拒绝（必须为 Invalid） |
| `2026/09/15 08:30`、`09-15-2026`、`8:30 PM`、`2026年9月15日`、`15/09/2026` | 其他 locale 格式 |
| `2026-9-5 8:30` | 月/日未两位填充（单位数小时允许，月/日不允许） |
| `2026-09-15T8:30Z` | 格式 (a) 小时必须两位（单位数小时仅限 (b) 墙钟格式） |
| `2026-09-15 08:30Z` | (b) 与 (a) 混写 |
| `2026-09-15T00:30:00.5Z`、`2026-09-15T00:30:00+08:00` | 小数秒/非 Z 偏移不在格式集内 |
| `2026-09-15 25:00`、`2026-09-15 08:61`、`2026-13-15`、`2026-02-30` | 越界组件 |
| `2026-09-15  08:30`（双空格） | 内部空白（空格分隔形式仅允许单个空格） |
| `1986-05-04 02:30`、`1991-04-14 02:30` | 历史 DST 空档（既有已测量日期，OccurredAtPickerTest.kt:33-36）→ `Invalid(DST_GAP)`（fail-closed）；邻近接受向量同日 `01:30`/`03:30` 为解析器测试集（选择器侧先例 OccurredAtPickerTest.kt:40-48） |

### 2.3 界面接线（冻结，parse-on-type）

`P503EditScreen.kt` 发生时间文本变化回调（现 :227-234）从 `Instant.parse` 严格门改为调用 lenient 解析器（含注入的 ledger clock），每次文本变化只走三条分支：

1. **空白文本**：不派发任何事件、不产生解析错误（沿用既有 missing-field 路径；缺失态由既有 `errors().missingOccurredAt` 判定显示「请输入发生时间」）。Continue 门按既有 P503IMPL-Q-001 语义：文本空白仅当 draft instant 为 null 时放行。
2. **有效文本**：派发**既有** `P503UiEvent.UpdateOccurredAt(instant)`（P503UiEvent.kt:39-41；reducer `draft.copy(occurredAt = event.instant)` 行为不变，P503Reducer.kt:90/:237/:263）——文本与选择器共用这一条写通道，选择器路径零改动。
3. **无效非空白文本**：解析无结果，写通道上无效文本不产生任何 instant，draft 保留上一有效值；Continue 门以当前文本重解析拦截（空白且 draft 非空同样拦截，P503DraftValidation.kt:68-69 既有语义）；字段进入内联错误态（既有 `occurredAtParseError` 显示路径：supportingText + isError）。**错误文案冻结：** `无法识别的时间格式，示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z`。

**Continue 门（冻结）**：`occurredAtTextReconciles`（P503DraftValidation.kt:64-73）从 `Instant.parse`（:71）切换到**同一个** lenient 解析器实例（同一 clock 源）：文本解析成功且结果恰等于 `draftOccurredAt` 才放行；解析失败、或解析为不同 instant，一律拦截并置 `occurredAtParseError`（P503EditScreen.kt:249 既有路径）。`P503DraftValidation` 构造新增 `parseOccurredAt` 与 `ledgerClock`（与屏幕同一 `facade.ledgerClock` 实例）；该类 doc 注释的纯函数声明（「No IO, no randomness, no facade calls」）相应更新，说明 clock 为构造注入的参数化输入（纯函数性保持，谓词不依赖任何全局/可变状态）。P503IMPL-Q-001 不变量保持：门两侧（屏幕 parse-on-type 与门）使用同一解析器与同一时钟实例，文本与 draft instant 的逐值一致由构造保证，不存在两套解析语义漂移（风险 R-4）。

**Reducer `P503Reducer.kt` 零改动**（`UpdateOccurredAt` 已替换 draft instant，无新增事件、无新分支）。

### 2.4 显示语义不变（冻结）

- 选择器确认后文本框仍同步为该 instant 的 ISO 串（`draft.occurredAt.toString()`，P503EditScreen.kt:96 既有行为）——选择器产物恒为整分钟（时:分粒度），`toString()` 恒形如 `YYYY-MM-DDTHH:mm:00Z`，落在格式 (a) 内（ISO superset 要求，P503IMPL-Q-001）。若选择结果恰等于现有 draft instant，文本框保持当前文本（`remember(draft.occurredAt)` 键不变）；门仍按当前文本重解析拦截。
- 手工键入的文本**原样保留**在文本框中（不重写为 ISO 串）；draft instant 为解析结果。
- 确认页显示（D-131 §3.4 冻结格式 `本地时间（UTC+8）＝ UTC ISO 串`）、失败/重试机制、提交链全部零改动。
- 桌面 Esc/back 行为（D-137、`DesktopEscBackHandler`）零改动——本批只动解析器与文本接线（风险 R-5）。

## 3. 逐文件变更表（冻结）

| 文件（仓库相对路径） | 变更（冻结） |
| --- | --- |
| `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ParseManualExpenseOccurredAt.kt`（新文件） | 新增纯解析器：`ParseManualExpenseOccurredAt` + `OccurredAtFormatError`（INVALID_FORMAT/DST_GAP）+ sealed `Result`（§2.1/§2.2） |
| `ledger-application/build.gradle.kts` | commonMain 增加 `implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")`（坐标已被 D-131 §3.1 过门，已存在于 app-ui 解析图；本次为声明既有坐标，非新增依赖） |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503LedgerFacade.kt` | 新增成员 `val parseOccurredAt: ParseManualExpenseOccurredAt`，与 `parseAmount`（:24）同列构造参数注入——镜像 parseAmount，不新增第二构造路径 |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt` | 注入 parser（并以同一 `facade.ledgerClock` 实例一并传入），沿既有装配路径传给校验装配（`P503DraftValidation` 构造）并经 `P503EditScreen` 调用下传；与 parseAmount 同路径，不新增装配面 |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503DraftValidation.kt` | 构造新增 `parseOccurredAt` 与 `ledgerClock`（与 `parseAmount` 并列注入；clock 与屏幕同一 `facade.ledgerClock` 实例）；类 doc 注释的纯函数声明（「No IO, no randomness, no facade calls」）相应更新，说明 clock 为构造注入的参数化输入；`occurredAtTextReconciles`（:64-73）改用 lenient 解析器（与屏幕同 clock 源，文本解析恰等于 draft instant 才放行）；`errors()`（:28-49）零改动 |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503EditScreen.kt` | 文本变化接线（:227-234）改调 lenient 解析器（parse-on-type，三条分支见 §2.3；clock 用既有注入 `ledgerClock`，:86）；错误文案（:223 分支）替换为冻结文案；辅助文案（:225）由「ISO 8601，如 2026-01-15T00:30:00Z」更新为列出友好格式（定稿：**「示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z」**，与错误文案示例一致）；选择器确认路径（:317-343）与 Continue 门结构（:241-254）保持（门内部改用同一解析器）；`occurredAtParseError` 显示路径零改动 |
| `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503Reducer.kt` | **零改动**（`UpdateOccurredAt` 已替换 draft instant，:90/:237/:263） |
| `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt`、`android-app/src/main/kotlin/com/unifiedledger/android/App.kt`（组合根） | 构造 `P503LedgerFacade` 时注入 `parseOccurredAt`（与 parseAmount 同路径）；其余零改动 |
| 测试 | 新增 `ledger-application` 测试 `ParseManualExpenseOccurredAtTest`（§2.2 全格式矩阵、§3.1 冻结测试矩阵、禁止相对表达、固定时钟年补齐、ISO superset 用例、DST gap 拒绝——冻结日期 `1986-05-04 02:30`/`1991-04-14 02:30` 与邻近 `01:30`/`03:30` 接受向量）；`P503DraftValidationTest` 新增（reconcile 对 (a)–(d) 全格式通过、相对表达/无效文本拒绝、门语义保持）；`P503ReducerTest` 新增（文本→picker→文本 顺序组合下的最终 instant 正确性等不回归用例）；既有套件全部保持绿 |

### 3.1 冻结测试矩阵补充（P2-5 评审闭环）

`ParseManualExpenseOccurredAtTest` 在上述行外另钉以下冻结向量：

| 输入 | 期望 | 说明 |
| --- | --- | --- |
| `2028-02-29 08:30` | `Valid`（2028-02-28T00:30:00Z） | 闰年接受 |
| `2026-02-29` | `Invalid(INVALID_FORMAT)` | 平年 2-29 拒绝 |
| `2026-09-15 00:00` | `Valid`（2026-09-14T16:00:00Z） | 边界时刻接受 |
| ` 2026-09-15 08:30 `（两端空白） | `Valid`（2026-09-15T00:30:00Z） | trim 规则（§2.1） |
| `2026-09-15T08:30:15` | `Valid`（2026-09-15T00:30:15Z） | (b) 的 T 分隔 + 秒 |
| 固定时钟 2026-12-31 23:59（Asia/Shanghai）+ 输入 `01-02` | `Valid`（2026-01-02 00:00 本地 → 2026-01-01T16:00:00Z） | 跨年钉：补年为 2026（补年取 Asia/Shanghai 本地年，§2.1） |

`P503DraftValidationTest` 新增用例沿用同向量取向（reconcile 对 (a)–(d) 全格式通过与无效文本拒绝），`P503ReducerTest` 新增不改断言语义。

## 4. 验证计划

资源旗标遵循 `docs/CONTRIBUTING.md`：本机 16 GB 主机串行、单 worker、1 GB heap（`GRADLE_OPTS=-Xmx1024m`、`-Dkotlin.daemon.jvmargs=-Xmx1024m`、`--no-daemon`、`--max-workers=1`、`--rerun-tasks`、`--warning-mode all`），验证前后 `.\gradlew.bat --stop`，一次只运行一个命令。受影响集合（按顺序）：

- `:ledger-application:jvmTest`：新 `ParseManualExpenseOccurredAtTest` 全矩阵（含 §3.1 冻结矩阵五向量与 DST 冻结日期）+ 既有回归。
- `:app-ui:jvmTest`（既有 61 用例 + 新增 Δ）：`P503DraftValidationTest` 新增用例、`P503ReducerTest` 新增与不回归。
- `:desktop-app:jvmTest`（既有 5 用例）：桌面侧回归（新增测试不涉及桌面源集）。
- `:android-app:testDebugUnitTest`（既有 7 用例）：Android 侧回归。
- `ktlintCheck`（受影响模块 `ledger-application` + `app-ui`，与 CI Ktlint 步骤一致）。
- `:android-app:compileDebugKotlin`：Android 编译门（APK 装配归 CI，按既有纪律）。
- `project_docs`：正式文档验证。

**双端人工门（冻结）：**

1. 键入 `2026-09-15 08:30` → 继续 → 确认页显示 `发生时间：2026-09-15 08:30（UTC+8）＝ 2026-09-15T00:30:00Z`（+08:00 换算正确）。
2. 键入 `昨天 20:00` → 字段显示冻结错误文案（`无法识别的时间格式，示例：2026-09-15 08:30 或 2026-09-15T00:30:00Z`）+ 继续被拦。
3. 选择器仍工作：选择 → 文本框同步为 ISO 串 → 继续通过（既有 R2 流程不回归）。
4. D-137 桌面 Esc 行为不受影响：对话框打开时 Esc 仅关对话框、编辑页保持打开、draft 完整（本批零改动 Esc/back 路径，人工核对确认）。

## 5. 验收判据（A-1..A-6）

- **A-1**：受影响套件全绿且含新增测试——`:ledger-application:jvmTest`、`:app-ui:jvmTest`（61 + Δ）、`:desktop-app:jvmTest`、`:android-app:testDebugUnitTest`；相对表达向量（昨天/明天/上周）全部断言 Invalid。
- **A-2**：`ktlintCheck`（ledger-application + app-ui）零告警。
- **A-3**：`:android-app:compileDebugKotlin` 通过。
- **A-4**：`project_docs` 通过。
- **A-5**：双端人工门四项全部通过（§4）。
- **A-6**：独立评审通过、distinct verifier 验收、同提交 CI 成功（聚合门发布证据）；P503IMPL-Q-001 不变量（文本重解析恰等于 draft instant 才放行）经测试与人工门双重保持。

## 6. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 输入中段闪烁（partial-typing flicker） | 键入过程 draft instant 为空/为上一有效值，字段短暂进入错误态（如 `2026-09-15 0` 中间态） | 可接受：冻结错误文案引导格式；reconcile 门保证文本/instant 逐值一致（P503IMPL-Q-001），不一致只拦截不放行，不产生错误值 |
| R-2 | ISO superset 回归（选择器写入串不再被解析） | P503IMPL-Q-001 断裂：picker 产物文本与 draft 对不上，继续被误拦 | 强制测试向量：`2026-09-15T00:30:00Z`（含秒）解析为同一 instant；选择器产物恒为整分钟 `YYYY-MM-DDTHH:mm:00Z`，结构上落在格式 (a) |
| R-3 | 年补齐的时钟依赖 | 解析结果随 `LedgerClock` 的当前年变化；跨年瞬间（23:59:59.9 → 00:00:00）补齐年切换 | 文档化（§2.1）；测试用固定时钟注入（fixed-clock test hook，§3.1 跨年钉）；跨年窗口内门只会短暂拒绝（fail-closed 方向），自愈于下次键入（或选择器），登记为已知微窗口 |
| R-4 | 双解析一致性（屏幕 parse-on-type 与 Continue 门两处解析漂移） | 两处语义不同 → 门放行屏幕未显示的值 | 冻结：屏幕与门共用**同一**解析器实例与同一 clock 源（构造仅一处：facade → P503App → validation/screen），由 §3 装配路径保证 |
| R-5 | D-137 交互（桌面 Esc/back） | 理论上新接线可能触碰 Esc 路径 | 零：本批只动解析器与文本接线，Esc/back 与对话框代码零改动；桌面人工门第 4 项核对 |

## 7. 批准后的登记路径

批准后（本文件由 proposal 翻转为 approved，批准记录由主代理按既有流程写入）：

1. **决定条目（part 2）**：配套派发在 `docs/DECISIONS.md` 登记 **D-138**，内容 = 本规格冻结机制（§2 四项）、范围（§1.2）与验收（§5）——该写入不属本文件、不属本次派发。
2. **对 D-131 §3.2 文本兜底接受集的窄幅超限声明（D-131 §7 风格）**：在手工输入发生时间文本通道范围内，D-131 §3.2 文本输入兜底（`Instant.parse` 全接受集）中的偏移（`+08:00` 等）与小数秒形式由接受改为拒绝（不在本规格格式 (a)–(d) 内），其余保留——格式 (a) 覆盖 `YYYY-MM-DDTHH:mm(:ss)?Z`（含秒与无秒），选择器产物（整分钟 ISO 串）逐字节保持解析（P503IMPL-Q-001）。该窄幅超限随本批登记同步写入 DECISIONS 条目范围段。
3. **实施批另开（待执行）**：独立 worktree、单一 bounded writer、独立评审与主代理最终验收；实施时完成 §4 全部验证与人工门并登记 R-2 向量与 R-3 固定时钟钩子。
4. 批准与登记不触发提交、推送或 CI 变更；push 由主代理按既有授权流程执行。

## 边界断言（本批不含）

- 本次派发（part 1）唯一写入 = 本新文件；配套派发（part 2）另行写入决定条目。`docs/specs/` 既有文档、其余全部模块源码/测试/构建脚本与 CI 零改动。
- 本文件为实施规格草案：未经批准不构成实施授权；实施批在独立 worktree、单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径（仅仓库相对路径）、个人数据、账务锚点、agent/会话痕迹；`.external/` 内容零引用、零改动。