# P5-03 演示面 B 实施规格（阶段 5 实施批）

**状态：approved** — 本文件为 P5-03 演示面 B（阶段 5 首条可用业务流程）实施规格。承接已批准补充契约 `docs/specs/2026-08-30-p5-01-02-closure-supplement-design.md`（D-119）与冻结实施计划 `docs/P5-03-IMPLEMENTATION-PLAN.local.md`（`状态：active`，git-ignored，存放于主 checkout）。本文件只冻结设计，不实施任何代码；实施发生在批准后的独立实施批（独立 worktree、单一 bounded writer、独立评审、distinct verifier、主代理验收）。

**Scope:** 按 D-119 §3/§4 与实施计划 §1-§10，把 P5-03 的每一项落地为可实施的冻结设计：新增共享 `app-ui` KMP 库模块（android + jvm target，消费 JVM-only 核心库）、`ledger-application`/`ledger-domain` 构建脚本零改动（jvm-only 保持，延续 D-118 IMP-3 边界）、proposed application API（`ParseManualExpenseAmount`/options/read/resolver/submission/requestId source）、`SqlDelightLedgerCurrentStateReadAdapter`（只增查询、零 DDL）、固定 catalog 与 fixture、14 状态 UI 状态机与无障碍、fail-closed 装配、双端平台接线、CI APK 工件上传、测试矩阵、验收命令与门、执行路由。零 schema、零 migration、零账务规则变化。

## 1. 定位与范围

**交付物定义**：P5-03 是阶段 5 的第一条可用业务流程，同一条双端流程为：

`权威只读空态/总览 → 编辑手工支出 → 精确解析与应用校验 → 待确认 → 显式确认 → Created/NoChange/冲突/拒绝/Unknown → 权威刷新 → 重启恢复正式结果`

最小可见输入只有支付账户、二级费用分类、金额和发生时间；币种来自所选支付账户，不是自由文本；`note` 恒为空字符串，UI 不展示备注输入。取消未提交 draft 零写入。UI 只做展示与交互；精确金额解析、缺项校验、领域校验、幂等和事务全部走共享 application/domain 边界。正式保存链固定为 `ExecuteManualExpenseSave → ExecuteConfirmedManualExpense`，UI 不直连 data port 或 SQL（D-119 §3.1）。

**严格最小范围**：只做总览（只读）和一条手工支出写路径。两端使用一个共享 KMP UI 模块（`app-ui`），Android/Desktop 只保留平台入口和组合根装配。使用原生 Compose/Material 3；不引入视觉皮肤。使用固定匿名单 CNY `LedgerCatalog`；不做 catalog 管理、动态账户、产品多账户配置或多币种产品 fixture。零 schema、零 migration、零账务规则变化（计划 §1 固定取舍）。

**明确排除**（计划 §9）：

- `AndroidLiquidGlass`、miuix 和任何视觉皮肤；
- catalog persistence/management；
- 产品多账户配置；
- 收入、转账、借贷、mixed payment（写路径避开 mixed confirm，D-114 已知限制保持休眠）；
- 备注输入；
- 完整报表；
- schema/migration；
- draft/Unknown 跨进程恢复（P5-03 最小版重启只恢复已持久化的正式结果）；
- P5-04 皮肤或视觉效果实现（`AndroidLiquidGlass` 后续另立 P5-04 规格，独立验证；P5-04 不得反向改变 P5-03 的账务和状态边界）。

**「演示面 B」定义**（P5-01 §2 承接，D-117）：只读总览 + 一条手工支出写路径；写路径避开 mixed confirm，不激活 D-114 登记的 null explicitConfirmedAt 已知限制。

## 2. 权威与前置

本文件全部条款对齐以下权威：

- **契约（approved）**：`docs/specs/2026-08-29-p5-01-dual-platform-shell-contract-design.md`（D-117）：F-1..F-5 冻结决定（CMP 栈 `1.11.1` + plugin.compose `2.4.10`、桌面驱动 `sqlite-driver:2.3.2` 置 desktop-app jvmMain、组合根契约、`LedgerClock` 端口形状、UUIDv7 端口语义）。
- **实施规格（approved）**：`docs/specs/2026-08-29-p5-02-dual-platform-skeleton-design.md`（D-118）：IMP-1..IMP-13 冻结骨架事实（desktop-app/android-app 模块形态、`LedgerClock` 端口与 `FixedLedgerClock`、`UuidV7Generator`/`UuidV7ConfirmedManualExpenseIdSource` 六 ID 生成、组合根构造顺序、空库引导、CI/CONTRIBUTING/README/PROJECT_MAP 接线、R-1..R-10）。
- **闭环补充契约（approved）**：`docs/specs/2026-08-30-p5-01-02-closure-supplement-design.md`（D-119）：
  - §3.1 首条流程；§3.2 catalog 与可选项权威；§3.3 精确金额解析 wrapper（固定有效/拒绝向量）；§3.4 requestId 生命周期；§3.5 提交结果与未知提交（`ExecuteManualExpenseSubmission`/`ManualExpenseSubmissionResult`）；§3.6 状态矩阵；§3.7 双平台 parity；§3.8 最低可访问性；§3.9 固定匿名 fixture；§4 application read boundary（`LedgerCurrentStateReadPort`/`QueryLedgerCurrentState`/`ManualExpenseOptionsProvider`/`QueryManualExpenseOptions`/`ResolveManualExpenseCommitStatus`/`SqlDelightLedgerCurrentStateReadAdapter`、ledger-signed minor units、normal-balance display sign rule）；§5 生命周期证据；§6.3 P5-03 代码实施 entry gate；§6.5 自动验证基线。
- **实施计划（active）**：`docs/P5-03-IMPLEMENTATION-PLAN.local.md`：§1 固定取舍；§3 模块和依赖边界；§4 固定 Catalog 和 Fixture；§5 UI 状态机和交互；§6 平台生命周期和 APK；§7 测试矩阵；§8 验收命令和门；§9 排除与后续批次；§10 执行路由。
- **架构**：`docs/ARCHITECTURE.md`「P5-03 批准边界（尚未实现）」与目标模块边界/依赖方向/运行时能力规则；`docs/ACCOUNTING_RULES.md` 核心结构（借方为正、贷方为负；资产/费用正余额、负债/收入/权益负余额；同币种分别求和为零；跨币种不直接抵消）与分类规则（一级/二级，记账必须选二级，二级支出分类对应隐藏费用账户）。
- **开发规范**：`docs/CONTRIBUTING.md` 本机 16 GB 串行资源约束（`--no-daemon --max-workers=1`、1 GB heap、`gradlew --stop`）、Kotlin 验证命令、CI 同步规则、文档规则（中文为主，代码/API/命令英文；`docs/specs/` 状态标记）。

**实施授权说明**：本批由主代理在用户 standing-delegation（「研判后全权批准推荐的计划」）文义下下达实施 goal；在此授权下，实施规格经独立 specification/quality review 与 distinct verifier 通过后即构成计划 §8 entry gate 第 7 项「用户明确批准实施」在既有授权文义下的满足。Android emulator 人工证据按 D-119 §5.2/§6.3 保持开放，由本批如实登记，不得写成已完成；开放状态不阻止本规格获批。

**现状承接**（D-118/§2 计划事实，实施批零改动既有语义）：

- `ExecuteManualExpenseSave` 已区分 `InvalidInput` 与 `Executed`；`ExecuteConfirmedManualExpense` 已提供 request snapshot、幂等 replay、identity conflict 和惰性六 ID 生成；`ConfirmedManualExpenseResult` 已有 `Created`/`NoChange`/`RequestIdentityConflict`/`Rejected`。
- `parseExactDecimal(text, precision)` 位于 `ledger-domain`：不 trim；ASCII grammar；允许可选负号、不允许正号；禁止前导零（字面 `0` 除外）；要求恰好 `precision` 位小数；`precision` 必须为 `0..18`；精确转换为 `Long` minor units，格式或溢出返回 `null`。金额解析不得在 UI 或平台复制。
- `LedgerClock` 端口已实装（`fun interface LedgerClock { fun now(): Instant }`，commonMain）；`FixedLedgerClock` 在 commonTest；两端组合根注入系统实现。
- 两个组合根当前只负责 driver、schema、catalog、factory、clock 和占位 UI 装配。
- `ledger-data` 当前 schema v27，迁移链 `1.sqm`~`26.sqm`；本批不得增加 `.sqm` 或迁移。
- 本批不把 P5-02 的 Android 人工门误写为已完成：当前本机无 APK，CI 需要先产出可下载工件；emulator 安装、启动、私有数据库创建/打开和同版本重开仍是开放 entry evidence（计划 §2）。

## 3. 模块与依赖拓扑（关键结构决定）

### 3.1 决定

**P5-03 新增共享 `app-ui` KMP 库模块，targets = `androidTarget()` + `jvm()`；`ledger-application`/`ledger-domain` 构建脚本保持 JVM-only 零改动（延续 D-118 IMP-3 的「`ledger-application`/`ledger-data` 构建脚本零改动（除新增源码文件）」边界）。零版本升级、零 schema、零 migration、零账务规则变化。**

**为什么不需要给核心库加 Android target（本批冻结依据，仓库内证据）**：`app-ui` 的 android 编译单元消费 `api(project(":ledger-application"))` 并不要求 `ledger-application` 自身具有 Android target。仓库内已有同构先例：`ledger-data`（`com.android.kotlin.multiplatform.library`，android + jvm targets）在 commonMain `api(project(":ledger-application"))`，而 `:ledger-data:compileAndroidMain` 在 D-118 验证证据中通过（docs/DECISIONS.md D-118「验证证据」）；KGP 允许一个 KMP 模块的 Android 编译单元消费 jvm-only KMP 模块的 jvm 变体。因此本批不给 `ledger-application`/`ledger-domain` 增加 target，两库构建脚本与既有源码语义零变化，D-118 IMP-3 边界保持。备选方案「把 application facade 层复制进 `app-ui`」落选——违背共享核心零复制原则，形成两份业务语义。

**文档化 fallback**：若实施里程碑 0 冒烟（§13.2 门 0，finding P503Q-003）的实际 Gradle 解析暴露真实的 Android 解析失败（而非本批预期），fallback = 按 `ledger-data` 既有 `com.android.kotlin.multiplatform.library` 模式为 `ledger-application`/`ledger-domain` 增加 Android target（各自 commonMain 为 Android 编译；`ledger-application` jvmMain/POI 保持 jvm-only、android target 无 jvmMain 内容），并在实施批登记观察到的 Gradle 错误全文与复现步骤；不得静默变更共享模块选择，且该 fallback 不得扩大 P5-03 的账务与状态边界。

### 3.2 `app-ui` 模块形态

- 新建模块 `app-ui/`，KMP library，targets = `androidTarget()` + `jvm()`。
- plugins：`kotlin("multiplatform")`、`id("org.jetbrains.compose")`（版本 `1.11.1`）、`id("org.jetbrains.kotlin.plugin.compose")`（版本恒 = Kotlin `2.4.10`）、`org.jlleitschuh.gradle.ktlint`（D-115）+ 与既有模块相同的 build 过滤块（`ledger-data/build.gradle.kts:10-22` 同款）。
- `kotlin { jvmToolchain(21); jvm { compilerOptions { jvmTarget = JVM_21 } }; android { namespace = "com.unifiedledger.ui"; minSdk = 34; compileSdk = 36 } }`。
- `commonMain.dependencies`：`api(project(":ledger-application"))`（UI 状态机与 facade 直接消费 application 类型）+ `implementation(compose.runtime)`、`implementation(compose.foundation)`、`implementation(compose.material3)`——`org.jetbrains.compose` 访问器按 target 解析：android target 解析为 androidx.compose 工件，jvm target 解析为 JetBrains skiko 工件。`commonTest.dependencies`：`implementation(kotlin("test"))`。

**Material3 alpha 措辞解释（登记于 §14 R-12，finding P503SPEC-009）**：计划 §3.1 的「Material3 alpha 工件」禁令解释为禁止引入**额外**的 alpha 工件；冻结的 `compose.material3` 访问器本身在 1.11 线解析到 `1.11.0-alpha07`（P5-02 R-2 已登记），属于允许范围。这是登记在案的解释，不是与计划的矛盾。

**§12.3 测试策略对齐（finding P503SPEC-005）**：app-ui commonTest **不引入** compose ui-test harness（不依赖 `compose.uiTest` 访问器），只做纯 reducer/状态机测试（`implementation(kotlin("test"))` 即足够）；semantics 标签验证归 §9 无障碍人工/键盘/TalkBack 门（D-119 §3.8）。
- 目录布局：`app-ui/build.gradle.kts`；`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/...`（P503App、P503LedgerFacade、状态机/reducer、screens、semantics）；`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/...`（reducer/semantics 测试，使用内存测试替身，不依赖 `ledger-data`）。
- `app-ui` 不得依赖：`ledger-data`、SQLDelight、database handle、Android `Context`/Activity API、Desktop Window API、Liquid Glass、miuix、Material3 alpha 之外的 alpha 工件、导航库、DI 框架（计划 §3.1）。

**关键结构约束**：`app-ui` 是共享 UI 层，不是组合根。它拿到的是组合根构造好的 `P503LedgerFacade`（只含 application 层类型），不接触 driver/schema/database；数据库句柄与 SQL 全部留在 data/组合根，UI 不可达（D-119 §4）。

### 3.3 `ledger-application`/`ledger-domain` 构建脚本零改动（JVM-only 保持）

- `ledger-application/build.gradle.kts` 与 `ledger-domain/build.gradle.kts` 本批**零改动**：不新增 `com.android.kotlin.multiplatform.library` 插件、不新增 android block；jvmToolchain(21)、jvm jvmTarget JVM_21、`ledger-application` 的 jvmMain POI 依赖（保持 jvm-only 作用域）与 commonTest 全部不变。
- 这延续 D-118 IMP-3 的「`ledger-application`/`ledger-data` 构建脚本零改动（除新增源码文件）」边界：本批在 `ledger-application` commonMain **新增** §4 的 proposed API 源码文件（全部为新文件，零编辑既有文件），构建脚本不动。
- 若里程碑 0 冒烟（§13.2 门 0）暴露真实 Android 解析失败，按 §3.1 fallback 为两库增加 Android target 并登记观察到的 Gradle 错误全文；在此之前本批不预先修改两库。

### 3.4 组合根消费 `app-ui`

`android-app`（`com.android.application`）与 `desktop-app`（KMP jvm）各自：

- 新增 `implementation(project(":app-ui"))`；
- 移除占位 UI 对 `compose.material3`/`compose.foundation` 的直接占位使用，改为在组合根平台侧构造对象图后调用共享 `P503App(facade, onExit)`；`android-app` 保留 `androidx.activity:activity-compose:1.13.0`（`MainActivity.setContent` 入口）与 `compose.runtime`（如 setContent 需要显式依赖，activity-compose 传递提供）；`desktop-app` 保留 `compose.desktop.currentOs`（Window/application API）与 `sqlite-driver:2.3.2`（F-2，driver 声明不动）；
- 保留 `implementation(project(":ledger-application"))`、`implementation(project(":ledger-data"))`：组合根仍负责构造 driver/schema/catalog/clock/ID 源/adapters，并把这些装配成 `P503LedgerFacade`。

### 3.5 依赖图

```text
android-app ──> app-ui ──> ledger-application ──> ledger-domain
   │                │            │（jvm-only，构建脚本零改动）
   │                │            │
   └────────> ledger-data ───────┘   （adapters/driver/schema，UI 不可达）

desktop-app ──> app-ui ──> ledger-application ──> ledger-domain
   │                │            │
   └────────> ledger-data ───────┘
```

`settings.gradle.kts` 新增 `include(":app-ui")`。依赖方向保持组合根 → 应用/数据 → 领域，无反向依赖；`app-ui` 永不依赖 `ledger-data`；两组合根零核心逻辑复制（不复制金额解析、业务校验、提交状态机或余额计算，计划 §3.1）。`app-ui` 的 android 编译单元按 §3.1 仓库内证据消费 `ledger-application` 的 jvm 变体（KGP 既有行为，`:ledger-data:compileAndroidMain` 通过）。

## 4. Application API 契约

以下全部为 proposed API addition（D-119 §4：获批和实施前不存在）。位置：`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/` 与 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/`。组合根手工构造对象图（DI 暂缓，F-3），全部按本仓库手工构造约定。

### 4.1 `ParseManualExpenseAmount`（D-119 §3.3；计划 §3.2.1）

两端共同调用，不得各自实现金额 parser。wrapper 从 application 已解析的所选账户取得 `CurrencyUnit`，UI 不得提交独立 precision 覆盖账户币种。

```kotlin
enum class ManualExpenseAmountFormatError {
    EMPTY,               // 仅 trim ASCII space/tab/CR/LF 后为空字符串
    INTERNAL_WHITESPACE, // trim 后内部含 ASCII 空白
    INVALID_FORMAT,      // 既有 parseExactDecimal 返回 null（pattern 失败或 Long 溢出）
}

class ParseManualExpenseAmount {
    sealed interface Result {
        data class Valid(val minorUnits: Long) : Result
        data class Invalid(val error: ManualExpenseAmountFormatError) : Result
    }

    fun parse(text: String, currency: CurrencyUnit): Result
}
```

**规则冻结**（逐字承接 D-119 §3.3）：

- 仅 trim 两端 ASCII space/tab/CR/LF；trim 后空字符串拒绝（`EMPTY`），内部空白拒绝（`INTERNAL_WHITESPACE`）。
- trim 后调用既有 domain `parseExactDecimal`；grammar 为 `-?(0|[1-9][0-9]*)`，precision > 0 时必须追加小数点和恰好 precision 个 ASCII digit。
- `+`、指数、逗号、小数位不足/过多、前导零、超精度和 `Long` overflow 均为格式拒绝（`INVALID_FORMAT`），零舍入。
- parser 成功只表示精确语法和范围有效；`0.00`、`-0.01` 可解析，是否必须为正由既有 domain/business validation 决定（固定 fixture 下 `createAssetPaidOrdinaryExpense` 要求 `amount.minorUnits > 0`，故 `0.00`/`-0.01` 解析通过但提交时被领域 `Rejected`）。
- CNY fixture precision 固定来自 `CurrencyUnit("CNY", 2)`；产品 precision 始终来自所选账户。

固定有效向量（D-119 §3.3 逐字）：`"35.80" → 3580`、`" 35.80\t" → 3580`、`"0.00" → 0`、`"-0.01" → -1`。固定拒绝向量（逐字）：`""`、`" "`、`"+35.80"`、`"035.80"`、`"35.8"`、`"35.800"`、`"35,80"`、`"3.58e1"`、`"35 .80"`、`"92233720368547758.08"`。

### 4.2 `ManualExpenseOptionsProvider` / `QueryManualExpenseOptions`（D-119 §3.2/§4；计划 §3.2.2）

只返回目标 ledger 中 owned、`ASSET`、real 的 payment accounts（携带账户币种）与 active、leaf/secondary、`EXPENSE` 且 posting account 也为同 ledger `EXPENSE` 的 categories。不从余额、posting rows 或硬编码 ID 推导选项。

```kotlin
data class PaymentAccountOption(
    val accountId: AccountId,
    val currency: CurrencyUnit,
    val label: String,          // 固定 fixture 下取稳定 ID 文本；catalog 无名称模型，显示名语义留待 catalog 管理批
)

data class ExpenseCategoryOption(
    val categoryId: CategoryId,
    val parentCategoryId: CategoryId,
    val label: String,          // 同上，取稳定 ID 文本；消除歧义显示完整路径留待后续
    val postingAccountId: AccountId,
)

data class ManualExpenseOptions(
    val paymentAccounts: List<PaymentAccountOption>,
    val expenseCategories: List<ExpenseCategoryOption>,
)

fun interface ManualExpenseOptionsProvider {
    fun queryOptions(): ManualExpenseOptions
}

class QueryManualExpenseOptions(
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) : ManualExpenseOptionsProvider {
    override fun queryOptions(): ManualExpenseOptions
}
```

UI 只能经 `QueryManualExpenseOptions` 取得选择项和账户币种；币种随支付账户显示，不允许自由输入（D-119 §3.1）。

### 4.3 `LedgerCurrentStateReadPort` / `QueryLedgerCurrentState`（D-119 §4；计划 §3.2.3-4）

```kotlin
data class CurrentVersionRow(
    val transactionId: TransactionId,
    val currentVersionId: TransactionVersionId,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val postings: List<Posting>,            // current-version postings，ledger-signed minor units
)

data class ManualExpenseCommitRecord(
    val ledgerId: LedgerId,
    val requestId: RequestId,
    val snapshot: ManualExpenseRequestSnapshot,   // 持久化 snapshot
    val receipt: ConfirmedExpenseReceipt,
    val currentVersionId: TransactionVersionId,
)

fun interface LedgerCurrentStateReadPort {
    fun loadCurrentRows(ledgerId: LedgerId): List<CurrentVersionRow>
    fun findManualExpenseByRequest(ledgerId: LedgerId, requestId: RequestId): ManualExpenseCommitRecord?
    fun findManualExpenseByReceipt(ledgerId: LedgerId, receipt: ConfirmedExpenseReceipt): ManualExpenseCommitRecord?
}

data class AccountCurrencyBalance(
    val accountId: AccountId,
    val currency: CurrencyUnit,
    val ledgerSignedMinorUnits: Long,   // data adapter 原样返回，不翻转符号
    val displayMinorUnits: Long,        // application 按 normal-balance display sign 派生
)

data class LedgerCurrentState(
    val ledgerId: LedgerId,
    val transactions: List<CurrentVersionRow>,
    val balances: List<AccountCurrencyBalance>,
)

sealed interface LedgerCurrentStateResult {
    data class Success(val state: LedgerCurrentState) : LedgerCurrentStateResult
    data object InvalidState : LedgerCurrentStateResult   // catalog/posting 一致性校验失败（防御性，固定 fixture 不可达）
    data object Unavailable : LedgerCurrentStateResult    // 读端口异常（数据库不可用）
}

class QueryLedgerCurrentState(
    private val readPort: LedgerCurrentStateReadPort,
    private val ledgerId: LedgerId,
    private val catalog: LedgerCatalog,
) {
    fun query(): LedgerCurrentStateResult
}
```

**投影规则冻结**（D-119 §4）：

- 只投影 current-version transactions（`ledger_transaction_current_version` current pointer 连接）；不得返回已被 current pointer 替代的旧版本作为当前行。
- 所有查询强制 ledger filter；不同 ledger 不得串读。
- application 以注入 catalog 校验每个 account 属于目标 ledger、kind/currency 与 posting 一致，再按 account + currency 分组；不跨币种汇总。
- **normal-balance display sign rule**（`docs/ACCOUNTING_RULES.md` 核心结构）：`AccountKind.ASSET`/`EXPENSE` 的 `displayMinorUnits` = 账本符号（ledger-signed 原值）；`AccountKind.LIABILITY`/`INCOME`/`EQUITY` 的 `displayMinorUnits` = 账本符号取反。data adapter 只返回 ledger-signed minor units，不形成 display sign（符号派生在 application 层）。
- transaction row 至少包含 transactionId、currentVersionId、kind、occurredAt 和 current-version postings（D-119 §4）。
- 数据库异常 → `Unavailable`，不得映射为领域 `Rejected`。

### 4.4 `ResolveManualExpenseCommitStatus`（D-119 §3.5/§4；计划 §3.2.5）

```kotlin
sealed interface ManualExpenseCommitResolution {
    data class MatchingReceipt(val receipt: ConfirmedExpenseReceipt) : ManualExpenseCommitResolution
    data object SnapshotConflict : ManualExpenseCommitResolution
    data object Absent : ManualExpenseCommitResolution
    data object Unavailable : ManualExpenseCommitResolution
}

class ResolveManualExpenseCommitStatus(
    private val readPort: LedgerCurrentStateReadPort,
) {
    fun resolve(
        ledgerId: LedgerId,
        requestId: RequestId,
        attempted: ManualExpenseRequestSnapshot,
    ): ManualExpenseCommitResolution
}
```

**语义冻结**（D-119 §4 返回契约）：

- 结果固定为四态：`MatchingReceipt`（持久化 snapshot 与 attempted snapshot 全字段逐值相等）、`SnapshotConflict`（同 ledger/requestId 已存在但 snapshot 不同）、`Absent`、`Unavailable`。
- snapshot 等价比较逐字段覆盖 ledgerId、amount minor units + currency（code + precision）、categoryId、paymentAccountId、occurredAt 和空 note。
- 只有 `MatchingReceipt` 可以产生 `Recovered`；`SnapshotConflict` 映射为稳定 request identity conflict，绝不恢复成功；`Absent`/`Unavailable` 均保持 `UnknownCommit`。
- 数据库异常 → `Unavailable`，不得映射为领域 `Rejected`。
- unknown resolution 只以 request + attempted snapshot 为权威；receipt lookup 可用于正式结果重开。

### 4.5 `ExecuteManualExpenseSubmission`（D-119 §3.5；计划 §3.2.6）

```kotlin
sealed interface ManualExpenseSubmissionResult {
    data class Application(val result: ManualExpenseSaveResult) : ManualExpenseSubmissionResult
    data class Recovered(val receipt: ConfirmedExpenseReceipt) : ManualExpenseSubmissionResult
    data object InfrastructureFailure : ManualExpenseSubmissionResult
    data object UnknownCommit : ManualExpenseSubmissionResult
}

/** 组合根构造：tracker = CommitOnceInvocationTracker(realPort)，同一实例注入
 *  ExecuteConfirmedManualExpense 与 ExecuteManualExpenseSubmission。public 构造，供组合根手工装配。 */
class CommitOnceInvocationTracker(
    private val delegate: ConfirmedManualExpenseCommitPort,
) : ConfirmedManualExpenseCommitPort {
    var commitOnceInvoked: Boolean = false
        private set

    /** 每次 submit 起点调用：清空上一次提交的 handoff 痕迹，使标记只反映本次提交。 */
    fun reset() {
        commitOnceInvoked = false
    }

    override fun commitOnce(
        identity: ManualExpenseRequestIdentity,
        requestSnapshot: ManualExpenseRequestSnapshot,
        createFormalTransaction: () -> DomainResult<ConfirmedManualExpenseCommit>,
    ): ConfirmedManualExpenseResult {
        commitOnceInvoked = true
        return delegate.commitOnce(identity, requestSnapshot, createFormalTransaction)
    }
}

class ExecuteManualExpenseSubmission(
    private val executeSave: ExecuteManualExpenseSave,
    private val tracker: CommitOnceInvocationTracker,
    private val resolver: ResolveManualExpenseCommitStatus,
) {
    fun submit(input: ManualExpenseSaveInput): ManualExpenseSubmissionResult {
        tracker.reset()   // 关键：先清空上一次提交的 handoff 痕迹，再进入保存链
        // 实现按下方「异常恢复顺序冻结」第 1-5 条
    }
}
```

**异常恢复顺序冻结**（D-119 §3.5 逐条）：

1. 每次 `submit` 起点先 `tracker.reset()` 清空上一次提交的 handoff 痕迹，使 `commitOnceInvoked` 只反映本次提交（上一次成功提交的痕迹不得把本次 pre-submit failure 污染为 `UnknownCommit`，finding P503Q-001）。`submit` 在 handoff 前记录 `commitOnce` 是否被调用（`CommitOnceInvocationTracker` 在委托前翻转标记）；pre-submit/orchestration failure 只有在本次提交内 zero-call 可证明时才返回 `InfrastructureFailure`。
2. 一旦发生 commit handoff，此后的任何异常（无论本地事务看似已 rollback）都先进入 `UnknownCommit` 处理路径。
3. 通过 resolver 以 ledgerId、requestId 和本次 attempted `ManualExpenseRequestSnapshot` 查询权威状态。
4. `MatchingReceipt` → `Recovered(receipt)` 并刷新总览；`SnapshotConflict` → 返回稳定的 `Application(Executed(RequestIdentityConflict(identity)))`，绝不恢复成功。
5. `Absent` 或 `Unavailable` → 保持 `UnknownCommit`；absence 不能证明 rollback，不允许自动重试、乐观刷新或换 requestId。

`ManualExpenseSaveResult.InvalidInput` 与正常返回的 `Executed(Created/NoChange/RequestIdentityConflict/Rejected)` 均包装为 `Application(...)`。`Rejected` 只表示领域拒绝，不得承载数据库、driver 或其他基础设施异常。若未来要暴露 typed rollback proof，必须另立契约；当前不得从 SQLite 异常文本或本地 transaction 观察推断公共 retry safety（D-119 §3.5）。

### 4.6 `ManualExpenseRequestIdSource`（D-119 §3.4；计划 §3.2 requestId）

```kotlin
fun interface ManualExpenseRequestIdSource {
    fun next(): RequestId
}

class UuidV7ManualExpenseRequestIdSource(
    private val generator: UuidV7Generator,
) : ManualExpenseRequestIdSource {
    override fun next(): RequestId = RequestId(generator.next())
}
```

**与六 ID source 的独立性**：每次 `next()` 恰生成一个规范 UUIDv7 `RequestId`；它与每次产生六个正式 commit ID（confirmationId + `AssetPaidOrdinaryExpenseIds` 五字段）的 `UuidV7ConfirmedManualExpenseIdSource` 是两个独立 source，不得复用一次 `next()` 或共享消费计数。组合根构造两个独立 `UuidV7Generator` 实例分别注入两个 source。

**requestId 生命周期冻结**（D-119 §3.4 逐条 + finding P503Q-005 单一规则）：

- `requestId` 是一次 draft/save intent 的幂等键。draft 每次点击「继续」（进入保存/确认提交动作）时，UI host 按以下规则取得 requestId：draft 尚无 requestId → `ManualExpenseRequestIdSource.next()` **无条件分配，不论本次校验结果如何**；已有 → 复用。同一 intent 内后续每次「继续」均保留并使用该 ID，绝不重新分配（「进入提交动作」不表示校验已经通过）。
- 校验失败（字段错误）、`InvalidInput`、`Rejected`/`DomainRejected`、基础设施失败（`InfrastructureFailure`）和 `UnknownCommit` 均保留原 requestId；修改同一 intent 后继续使用原 ID。
- 未提交即取消的 draft 可丢弃 ID；丢弃后再次「继续」视为新 intent，重新分配；`Created`/`NoChange` 完成后，下一个新 draft 才分配新 ID。
- `RequestIdentityConflict` 保留原 ID 并显示冲突；只有用户显式放弃该冲突 draft、创建新的 save intent 后才分配新 ID，绝不静默换 ID 绕过冲突。
- P5-03 最小版不持久化待提交 draft 或 `UnknownCommit` presentation state，不承诺重启后恢复它们；重启只通过权威读边界恢复已存在的正式 transaction/receipt。

### 4.7 组合根 facade：`P503LedgerFacade`（app-ui commonMain）

按仓库手工构造约定（无 DI 框架），组合根把 use cases/options/read projection/resolver/ID source/clock 装配为不可变数据持有者，交给共享 UI：

```kotlin
package com.unifiedledger.ui

class P503LedgerFacade(
    val ledgerId: LedgerId,
    val currency: CurrencyUnit,
    val catalog: LedgerCatalog,
    val parseAmount: ParseManualExpenseAmount,
    val optionsProvider: ManualExpenseOptionsProvider,
    val queryCurrentState: QueryLedgerCurrentState,
    val resolveCommitStatus: ResolveManualExpenseCommitStatus,
    val submitExpense: ExecuteManualExpenseSubmission,
    val requestIdSource: ManualExpenseRequestIdSource,
    val ledgerClock: LedgerClock,
)

@Composable
fun P503App(
    facade: P503LedgerFacade,
    onExit: () -> Unit,   // StartupError 的 Exit：关闭窗口/结束 Activity
)
```

facade 只含 application 层类型，`app-ui` 不依赖 `ledger-data`/SQLDelight/database handle。

## 5. Data adapter：`SqlDelightLedgerCurrentStateReadAdapter`

- 位置：`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightLedgerCurrentStateReadAdapter.kt`，实现 `LedgerCurrentStateReadPort`。
- 构造：`SqlDelightLedgerCurrentStateReadAdapter(database: LedgerDatabase)`（与 `SqlDelightConfirmedManualExpenseCommitPort` 同层）。
- **只增加查询，不增加 DDL**：新查询追加到既有单 `Ledger.sq`（8454 行）；零 `.sqm`、零 DDL、schema v27 不变，既有 migration verifier 不变（计划 §3.3）。

新查询命名（SQLDelight 生成到 `database.ledgerQueries`）：

| 查询 | 参数 | 用途 |
| --- | --- | --- |
| `currentVersionRowsForLedger(ledger_id)` | ledgerId | `loadCurrentRows`；join `ledger_transaction_current_version`（current pointer）→ `transaction_version`（`version_id = current_version_id`）→ `posting_set` → `posting`（表名单数，finding P503Q-007）；只返回当前版本；ledger filter 强制 |
| `manualExpenseCommitByRequest(ledger_id, request_id)` | ledgerId, requestId | `findManualExpenseByRequest`；join `manual_expense_request` + `confirmed_expense_receipt` + `ledger_transaction_current_version`，返回 snapshot 全字段、receipt（confirmationId + transactionId）与 currentVersionId 引用关系 |
| `manualExpenseCommitByReceipt(ledger_id, confirmation_id, transaction_id)` | ledgerId, receipt | `findManualExpenseByReceipt`；receipt lookup，校验 receipt 中 transactionId 与持久化关系一致；返回同 `manualExpenseCommitByRequest` 的关系投影 |

**SELECT 形态（finding P503Q-007）**：

1. `currentVersionRowsForLedger(ledger_id)`：SELECT 经 current pointer 连接 `transaction`/`transaction_version`/`posting_set`/`posting`（单数）的全部当前行（`version_id = current_version_id`）；返回 transactionId、currentVersionId、kind、occurredAt 与 posting 行（posting_id、posting_index、account_id、amount_minor、currency_code、currency_precision）。
2. `manualExpenseCommitByRequest(ledger_id, request_id)`：SELECT `manual_expense_request` 全 snapshot 字段（amount_minor、currency_code、currency_precision、category_id、payment_account_id、occurred_at、note）+ `confirmed_expense_receipt`（confirmation_id、transaction_id）+ 经 transaction_id join `ledger_transaction_current_version` 的 currentVersionId。
3. `manualExpenseCommitByReceipt(ledger_id, confirmation_id, transaction_id)`：与 2 同构，以 receipt 过滤；SQL 层同时校验 `transaction_id` 与持久化关系一致。

**行→域映射（自由度声明）**：`CurrentVersionRow.postings: List<Posting>` 由当前版本的 `posting` 行按 `posting_index` 升序展平（同一 posting_set 内）组装；`ManualExpenseCommitRecord` 由 snapshot 字段 + receipt + currentVersionId 组装。精确 SQL 列清单、join 顺序与函数形参名由实施 writer 在命名自由度内落实，不得改变本批语义（ledger-signed、current-only、ledger filter、引用一致、异常→`Unavailable`）。

**不变量冻结**（计划 §3.3 / D-119 §4）：

- current pointer 只返回当前版本：所有 current-version 查询以 `ledger_transaction_current_version` 为当前指针，不返回已被替代的旧版本。
- ledger 查询不可串读其他 ledger：每条查询强制 `ledger_id` filter。
- 返回 ledger-signed minor units，data 层不翻转展示符号（display sign 在 application 层派生）。
- receipt、request、transaction、currentVersion 引用关系必须一致：receipt 中 transactionId 必须与持久化关系一致。
- 数据库异常进入 `Unavailable`（adapter 抛异常，use case 边界捕获并映射），不得映射为领域 `Rejected`。
- 既有 migration verifier 与 schema v27 保持不变。

## 6. 固定 Catalog 与 Fixture

### 6.1 固定 catalog（D-119 §3.2/§3.9 逐值冻结；计划 §4）

两个组合根在启动、Retry 和重启时都重建逐值相同的 immutable snapshot（`LedgerCatalog.create` 一次成功），并把同一个 snapshot 实例同时注入 write factory、options 和 read projection。**注（finding P503SPEC-004）**：计划 §4「把同一个 snapshot 实例同时注入 write factory、options、read projection 和 resolver」中的 resolver 措辞由 write-factory/options/read-projection 三方注入满足——`ResolveManualExpenseCommitStatus` 只依赖 `LedgerCurrentStateReadPort`（§4.4），不需要 catalog。

| 对象 | 稳定 ID | 属性 |
| --- | --- | --- |
| ledger | `ledger-local-test` | 唯一 ledger；唯一币种 `CurrencyUnit("CNY", 2)` |
| payment account | `asset-payment-local` | `AccountKind.ASSET`、`ownedByUser=true`、`realAccount=true`、CNY |
| expense posting account | `expense-account-local` | `AccountKind.EXPENSE`、`ownedByUser=false`、`realAccount=false`、CNY |
| parent category | `expense-category-food` | `active=true`、`CategoryKind.EXPENSE`、`postingAccountId=null` |
| leaf category | `expense-category-breakfast` | `active=true`、`CategoryKind.EXPENSE`、parent=`expense-category-food`、posting account=`expense-account-local` |

### 6.2 固定测试输入（D-119 §3.9；计划 §4）

| 字段 | 固定值 |
| --- | --- |
| ledgerId | `ledger-local-test` |
| requestId | 测试注入的规范 UUIDv7；同一 save intent 全程固定 |
| paymentAccountId | `asset-payment-local` |
| categoryId | `expense-category-breakfast` |
| amount | `35.80 CNY`（minor units `3580`，precision `2`） |
| occurredAt | `2026-01-15T00:30:00Z`（`Instant`） |
| note | `""`，UI 不展示备注输入 |

commit IDs 使用独立的确定性 UUIDv7 source（每次 `next()` 恰生成六个：confirmationId + `AssetPaidOrdinaryExpenseIds` 五字段）。测试分别注入确定性 request UUIDv7 source 和确定性六-ID commit source；人工验收只断言生成值均为规范 UUIDv7，且 requestId 与六个 commit ID 的职责、消费次数分离（D-119 §3.9）。

### 6.3 固定金额向量（D-119 §3.3 逐字；计划 §4）

- 固定有效向量：`"35.80" → 3580`、`" 35.80\t" → 3580`、`"0.00" → 0`、`"-0.01" → -1`（后两个可解析；提交时被领域 `AmountMustBePositive` 拒绝）。
- 固定拒绝向量：`""`、`" "`、`"+35.80"`、`"035.80"`、`"35.8"`、`"35.800"`、`"35,80"`、`"3.58e1"`、`"35 .80"`、`"92233720368547758.08"`。

五种 account kind、multi-currency、cross-ledger 测试使用扩展的匿名 injected catalog；这是 application read projection 的测试能力，不表示 P5-03 产品 fixture 支持多账户配置或多个币种（D-119 §3.2）。

## 7. UI 状态机与交互

### 7.1 状态集（计划 §5 逐字，14 个）

`Starting`、`Ready`、`StartupError(LocalDatabaseUnavailable)`、`OverviewEmpty`、`Editing`、`AwaitingConfirmation`、`Submitting`、`Created`、`NoChange`、`RequestIdentityConflict`、`DomainRejected`、`InfrastructureFailure`、`UnknownCommit`、`Recovered`。

**命名说明（规格级决定）**：计划 §5「状态至少包括」且任务冻结恰 14 个状态。总览屏（含非空列表）以 `OverviewEmpty` 状态承载 `LedgerCurrentState` payload：`transactions` 为空时渲染空态，非空时渲染 current transaction 列表与逐账户逐币种余额。`Created`/`NoChange`/`Recovered` 是提交后的瞬时结果状态，随后执行权威刷新并回到 `OverviewEmpty`。

```kotlin
sealed interface P503AppState {
    data object Starting : P503AppState
    data object Ready : P503AppState
    // LocalDatabaseUnavailable：demo 恰有一种启动失败模式，payload 收敛为 data object（finding P503Q-011）
    data object StartupError : P503AppState
    data class OverviewEmpty(val state: LedgerCurrentState) : P503AppState
    data class Editing(val draft: ManualExpenseDraft, val requestId: RequestId?) : P503AppState
    data class AwaitingConfirmation(val draft: ManualExpenseDraft, val requestId: RequestId) : P503AppState
    data class Submitting(val draft: ManualExpenseDraft, val requestId: RequestId) : P503AppState
    data object Created : P503AppState
    data object NoChange : P503AppState
    data class RequestIdentityConflict(val draft: ManualExpenseDraft, val requestId: RequestId) : P503AppState
    data class DomainRejected(val draft: ManualExpenseDraft, val requestId: RequestId) : P503AppState
    data class InfrastructureFailure(
        val context: InfrastructureFailureContext,
        // context == SUBMISSION 时 draft/requestId 必非空（供同 intent 重试/返回编辑）；READ 时均为 null（finding P503Q-014）
        val draft: ManualExpenseDraft? = null,
        val requestId: RequestId? = null,
    ) : P503AppState
    data object UnknownCommit : P503AppState
    data object Recovered : P503AppState
}

enum class InfrastructureFailureContext { READ, SUBMISSION }   // 读/刷新失败 vs 提交 pre-submit 失败（§7.2 按 context 分派重试路径）

data class ManualExpenseDraft(
    val paymentAccountId: AccountId?,
    val categoryId: CategoryId?,
    val amountText: String,        // parser 成功前保留原始文本
    val occurredAt: Instant?,
)

sealed interface P503UiEvent {
    // ---- 用户发起事件 ----
    data object StartRetry : P503UiEvent          // StartupError → Retry
    data object Exit : P503UiEvent                // StartupError → Exit
    data object StartNewExpense : P503UiEvent     // OverviewEmpty → Editing
    data class UpdateAmount(val text: String) : P503UiEvent
    data class UpdatePaymentAccount(val accountId: AccountId) : P503UiEvent
    data class UpdateCategory(val categoryId: CategoryId) : P503UiEvent
    data class UpdateOccurredAt(val instant: Instant) : P503UiEvent
    data class Continue(val requestId: RequestId) : P503UiEvent  // host 按 §4.6 取 ID 后分发；校验结果由 reducer 内纯函数（ParseManualExpenseAmount + 字段完整性）计算
    data object Cancel : P503UiEvent              // 取消/返回：AwaitingConfirmation 或 InfrastructureFailure(SUBMISSION) → Editing（转移表 §7.2 为权威）
    data object Confirm : P503UiEvent             // AwaitingConfirmation → Submitting（draft/requestId 取自源状态）
    data object RetrySubmission : P503UiEvent     // InfrastructureFailure(SUBMISSION) 重试（draft/requestId 取自状态）
    data object RetryRefresh : P503UiEvent        // InfrastructureFailure(READ) 重试刷新（状态不变，host 执行查询）
    data object AbandonConflict : P503UiEvent     // 显式放弃冲突 draft，新建 save intent
    // ---- 异步结果事件（UI 层把异步结果映射为这些事件后分发；reducer 只消费、不发起 IO）----
    data object StartupCompleted : P503UiEvent                // Starting → Ready
    data object StartupFailed : P503UiEvent                   // Starting → StartupError
    data class InitialLoadResult(val currentState: LedgerCurrentState) : P503UiEvent  // Ready → OverviewEmpty
    data object InitialLoadFailed : P503UiEvent               // Ready → InfrastructureFailure(READ)
    data class SubmissionResult(val result: ManualExpenseSubmissionResult) : P503UiEvent  // Submitting → 提交结果态（draft/requestId 取自源状态）
    data class RefreshResult(val currentState: LedgerCurrentState) : P503UiEvent       // Created/NoChange/Recovered/InfrastructureFailure(READ) → OverviewEmpty
    data object RefreshFailed : P503UiEvent                   // Created/NoChange/Recovered → InfrastructureFailure(READ)
}

fun interface P503Reducer {
    fun reduce(state: P503AppState, event: P503UiEvent): P503AppState   // 纯函数；无 IO/无随机
}
```

`ManualExpenseDraft` = 用户当前输入的支付账户、二级费用分类、金额文本、发生时间（金额在 parser 成功前保留文本）。屏幕 composable 命名冻结（finding P503Q-010）：`P503App(facade, onExit)`（入口）、`P503OverviewScreen(...)`（总览/空态）、`P503EditScreen(...)`（编辑）、`P503ConfirmationScreen(...)`（待确认）、`P503ResultScreen(...)`（Created/NoChange/Recovered banner 与 RequestIdentityConflict/DomainRejected/UnknownCommit 结果呈现）、`P503StartupScreen(state, onRetry, onExit)`（§8）；各屏参数形态由 writer 自由落实。

**异步结果 → 事件映射与 reducer 纯度（finding P503Q-014）**：UI 层（screen composables/host）执行全部异步操作（driver/schema/create/open、权威查询、提交 orchestration、结果后刷新），并把异步结果映射为结果事件（`StartupCompleted`/`StartupFailed`/`InitialLoadResult`/`InitialLoadFailed`/`SubmissionResult`/`RefreshResult`/`RefreshFailed`）后分发；`P503Reducer` 保持纯函数——只消费事件、不发起 IO、不持有可失败句柄。目标状态所需的 `draft`/`requestId` 由**状态自身携带**（`Submitting(draft, requestId)`、`InfrastructureFailure(SUBMISSION, draft, requestId)`），结果事件不重复携带；reducer 从当前源状态派生目标 payload（一致性声明见 §7.2 前文）。reducer **可持有**纯函数依赖 `ParseManualExpenseAmount` 与字段完整性检查（二者均无 IO），用于计算 `Continue(requestId)` 分支的校验结果（失败 → `Editing`，通过 → `AwaitingConfirmation`，finding P503Q-015）；reducer 仍永不执行 IO、随机数或 facade 调用。

### 7.2 状态转移表

**requestId 分配规则（finding P503Q-005，应用于所有「继续」行）**：用户每次点击「继续」，UI host 先按 §4.6 取得 requestId（draft 尚无 → `requestIdSource.next()` **无条件分配，不论校验结果**；已有 → 复用），再以 `Continue(requestId)` 事件分发；reducer 不自行分配。因此不区分「首次」与「再入」；取消后重新进入视为新 intent。

**AwaitingConfirmation 不可变不变量（finding P503SPEC-010）**：进入 `AwaitingConfirmation` 后其 draft 不可变；任何字段修改必须先回到 `Editing`（无直接编辑行）。

**异步结果事件与下一状态映射（finding P503Q-014）**：下表异步行以 §7.1 结果事件为事件载体——driver/schema/create/open 结果经 `StartupCompleted`/`StartupFailed`；权威查询结果经 `InitialLoadResult(currentState)`/`InitialLoadFailed`；提交结果经 `SubmissionResult(result)`（目标态所需 `draft`/`requestId` 取自当前 `Submitting(draft, requestId)`，结果事件不携带）；结果后刷新结果经 `RefreshResult(currentState)`/`RefreshFailed`。`Submitting(draft, requestId)` + `SubmissionResult(result)` 的下一状态映射（与 §4.5 `ManualExpenseSubmissionResult` 对应）：`Application(Executed(Created))` → `Created`；`Application(Executed(NoChange))` → `NoChange`；`Application(Executed(RequestIdentityConflict))` → `RequestIdentityConflict(draft, requestId)`；`Application(Executed(Rejected))` → `DomainRejected(draft, requestId)`；`Application(InvalidInput)` → `Editing(draft, requestId)`（防御性 fallback）；`InfrastructureFailure` → `InfrastructureFailure(SUBMISSION, draft, requestId)`；`UnknownCommit` → `UnknownCommit`；`Recovered(receipt)` → `Recovered`。

| 当前状态 | 事件/动作 | 下一状态 | 权威结果与写入 |
| --- | --- | --- | --- |
| `Starting` | `StartupCompleted`（driver/schema/create/open 全部成功） | `Ready` | 零写入；`Ready` graph 只构造一次固定 catalog snapshot |
| `Starting` | `StartupFailed`（driver/schema/create/open 任一异常） | `StartupError` | fail-closed，见 §8 |
| `Ready` | `InitialLoadResult(currentState)`（权威初始查询成功） | `OverviewEmpty` | payload 含列表与余额；空/非空由 payload 决定（§7.1 命名注记）；零写入 |
| `Ready` | `InitialLoadFailed`（读端口异常） | `InfrastructureFailure(READ)` | 零写入，可重试刷新（规格级决定，见 §7.4） |
| `OverviewEmpty` | 用户点击「新增支出」（`StartNewExpense`） | `Editing` | 零写入 |
| `Editing` | 输入变更（amount/account/category/occurredAt） | `Editing` | 保留输入与已有 requestId；零写入 |
| `Editing` | 点击「继续」（`Continue(requestId)`）且校验失败（字段缺失或金额格式错误） | `Editing` | 字段错误、保留输入+requestId；shared parser 拒绝/`InvalidInput`，零写入 |
| `Editing` | 点击「继续」（`Continue(requestId)`）且校验通过 | `AwaitingConfirmation` | 保留 requestId；零写入 |
| `AwaitingConfirmation` | 取消/返回（`Cancel`） | `Editing` | 未执行 save，零写入；未提交 draft 的 requestId 可丢弃 |
| `AwaitingConfirmation` | 确认提交（`Confirm`） | `Submitting(draft, requestId)` | 单提交锁：按钮禁用、禁止重复点击；draft/requestId 取自源状态 |
| `Submitting` | `SubmissionResult(Application(Executed(Created)))` | `Created` | 一笔 current transaction、一个 posting set、两条同币种平衡 posting |
| `Submitting` | `SubmissionResult(Application(Executed(NoChange)))` | `NoChange` | 平台内 receipt 与首次相同，零重复写入 |
| `Submitting` | `SubmissionResult(Application(Executed(RequestIdentityConflict)))` | `RequestIdentityConflict(draft, requestId)` | 零新增写入；冲突向量改变 amount/category/account/time 之一，不使用 note；draft/requestId 取自 `Submitting` |
| `Submitting` | `SubmissionResult(Application(Executed(Rejected)))` | `DomainRejected(draft, requestId)` | 业务错误，零新增写入；draft/requestId 取自 `Submitting` |
| `Submitting` | `SubmissionResult(InfrastructureFailure)` | `InfrastructureFailure(SUBMISSION, draft, requestId)` | 本次提交内 zero-call 可证明（tracker.reset 后）；同 requestId 可重试；draft/requestId 取自 `Submitting` |
| `Submitting` | `SubmissionResult(UnknownCommit)` | `UnknownCommit` | 禁止自动重试、乐观刷新、换 requestId |
| `Submitting` | `SubmissionResult(Recovered(receipt))` | `Recovered` | resolver `MatchingReceipt`；再权威刷新 |
| `Submitting` | `SubmissionResult(Application(InvalidInput))`（防御性 fallback，正常路径不可达） | `Editing(draft, requestId)` | 输入保留（含 requestId）；零写入；机器全覆盖（total）；draft/requestId 取自 `Submitting` |
| `Created` | `RefreshResult(currentState)`（权威刷新成功） | `OverviewEmpty` | 重新查询权威状态；不得由提交返回值拼接列表或 UI 自行累计余额 |
| `Created` | `RefreshFailed`（权威刷新失败） | `InfrastructureFailure(READ)` | 零写入 |
| `NoChange` | `RefreshResult(currentState)`（权威刷新成功） | `OverviewEmpty` | 不追加第二行 |
| `NoChange` | `RefreshFailed`（权威刷新失败） | `InfrastructureFailure(READ)` | 零写入 |
| `Recovered` | `RefreshResult(currentState)`（权威刷新成功） | `OverviewEmpty` | 重新查询权威状态 |
| `Recovered` | `RefreshFailed`（权威刷新失败） | `InfrastructureFailure(READ)` | 零写入 |
| `RequestIdentityConflict` | 用户修改输入（同一 intent） | `Editing` | 保留原 requestId |
| `RequestIdentityConflict` | 用户显式放弃冲突 draft（`AbandonConflict`）、新建 save intent | `Editing` | 丢弃原 requestId；下次「继续」分配新 ID；绝不静默换 ID |
| `DomainRejected` | 用户修改输入 | `Editing` | 保留 requestId |
| `InfrastructureFailure(SUBMISSION)` | `RetrySubmission`（用户重试，同一 intent） | `Submitting(draft, requestId)` | 保留 requestId，zero-call 可重试；draft/requestId 取自状态 |
| `InfrastructureFailure(SUBMISSION)` | `Cancel`（用户取消返回） | `Editing(draft, requestId)` | 保留 requestId；draft/requestId 取自状态 |
| `InfrastructureFailure(READ)` | `RetryRefresh`（用户触发；host 执行权威查询，状态不变） | `InfrastructureFailure(READ)` | 零写入；结果经 `RefreshResult`/`RefreshFailed` 分发 |
| `InfrastructureFailure(READ)` | `RefreshResult(currentState)`（重试刷新成功） | `OverviewEmpty` | 权威查询重跑成功；零写入 |
| `InfrastructureFailure(READ)` | `RefreshFailed`（重试刷新仍失败） | `InfrastructureFailure(READ)` | 零写入 |
| `UnknownCommit` | 无自动动作 | `UnknownCommit` | 只显示 resolving/unknown；会话内持续；重启只恢复正式结果 |
| `StartupError` | `StartRetry`（用户点击 Retry） | `Starting` | 从 driver/graph 创建起点重新执行 |
| `StartupError` | `Exit`（用户点击 Exit） | （进程/窗口关闭） | 关闭当前 Activity/进程窗口 |
| `OverviewEmpty` | 重启 | （`Starting`→`Ready`→`OverviewEmpty`） | 权威读恢复正式结果；不承诺 draft/unknown presentation state |

**机器全覆盖（total，finding P503SPEC-007）**：上述每一行恰好一个下一状态；任何可到达状态上的未列出 (state, event) 组合属编程错误，实现时显式不处理（不静默吞事件）。

### 7.3 界面结构

1. **总览**：显示 ledger、current transaction 列表、逐账户逐币种余额（`AccountCurrencyBalance`，display 符号）；空账本显示空态。
2. **编辑**：支付账户、二级费用分类、金额、发生时间；币种随支付账户显示，不允许自由输入。
3. **校验**：缺字段或金额格式错误时保留输入和 requestId，零写入。
4. **待确认**：展示完整 attempted snapshot，取消不调用 save。
5. **提交**：按钮进入 submitting，禁止重复点击（单提交锁）。
6. **结果**：Created/NoChange/Recovered 后重新查询权威状态；冲突/拒绝保留输入；Unknown 只显示 resolving/unknown，不提供自动重试。

### 7.4 交互规则（计划 §5 汇总 + 规格级决定）

- **requestId 单一规则（finding P503Q-005）**：每次点击「继续」，UI host 按 §4.6 取得 requestId（draft 尚无则无条件分配，不论校验结果；已有则复用），再以 `Continue(requestId)` 分发；校验失败/`DomainRejected`/`InfrastructureFailure`/`UnknownCommit` 全程保留；取消未提交 draft 可丢弃；冲突后保留直至显式放弃。
- 校验失败保留输入+requestId；取消零写入；单提交锁。
- `Created`/`NoChange`/`Recovered` 后必须重新执行权威查询刷新列表与余额，不得由提交返回值拼接列表或由 UI 自行累计余额。`NoChange` 不追加第二行。
- 冲突保留 requestId；Unknown 禁止 retry/new requestId。
- 币种显示遵循所选支付账户，不是自由文本。
- **规格级决定**：初始权威读（`Ready`→总览）或 `Created`/`NoChange`/`Recovered` 后的刷新读失败，映射为 `InfrastructureFailure(READ)` 呈现（零写入、可重试刷新）。该呈现态不改变 `ManualExpenseSubmissionResult.InfrastructureFailure` 的窄语义（仅 zero-call 可证明的 pre-submit failure）；`InfrastructureFailure` 状态按 `context`（READ/SUBMISSION）分派重试路径（§7.2）。
- **规格级决定**：`UnknownCommit` 只呈现 resolving/unknown 状态信息，不提供自动重试、不提供 requestId 更换、不做乐观刷新；会话内持续直到重启，重启只恢复正式结果。是否提供用户显式「重新查询」动作在最小版不冻结（保持最窄：仅状态展示）。

## 8. 启动 Fail-Closed（计划 §6.1；D-119 §5.3）

组合根必须严格执行：

- 正常：`Starting → Ready`。
- driver、schema、create、open 任一异常：`Starting → StartupError(LocalDatabaseUnavailable)`。
- 错误页只提供 **Retry** 和 **Exit**；Retry 从 driver/graph 创建起点重新执行，Exit 关闭当前 Activity/进程窗口。
- 失败时不得构造业务 UI、不得暴露 use case/database handle、不得创建半初始化正式交易、不得把异常映射为领域 `Rejected`。
- `Ready` graph 只构造一次 §6.1 固定 catalog snapshot，并把同一实例注入 write/options/read projection（D-119 §5.3）。

共享 UI 侧提供纯状态机与错误屏（app-ui commonMain）：

```kotlin
// com.unifiedledger.ui
sealed interface P503StartupState {
    data object Starting : P503StartupState
    data object Ready : P503StartupState
    // LocalDatabaseUnavailable：唯一失败模式，payload 收敛为 data object（finding P503Q-011）
    data object StartupError : P503StartupState
}

@Composable
fun P503StartupScreen(
    state: P503StartupState,
    onRetry: () -> Unit,
    onExit: () -> Unit,
)
```

平台侧组合根负责 driver 生命周期与异常捕获，把 `Starting`/`Ready`/`StartupError` 作为可测试 composition-root 状态暴露。Desktop 用 driver/open failure injection 测试 error → retry → ready 与 error → exit；Android 至少有可测试的 composition-root state transition，安装后的人工门核对错误页可达（D-119 §5.3）。

## 9. 无障碍（计划 §5 可访问性 + D-119 §3.8）

最低可访问性清单逐条落地：

- 输入、按钮、错误和状态都有明确 label。
- 字段错误通过 Compose semantics 关联（读屏可读字段、错误和状态）；首个字段错误可被焦点/读屏定位。
- 成功、错误、禁用、resolving 和待确认状态不只靠颜色，必须同时有文本或语义状态。
- Desktop 可用键盘完成整个流程，焦点顺序与视觉顺序一致且焦点可见。
- Android 可通过 TalkBack/Compose semantics 完成同一流程。
- Android 主要触控目标不小于 `48.dp`；图标按钮提供 content description。
- 动态成功、失败和 unknown 状态可被辅助技术感知（如 live region/announcement 语义）。

## 10. 平台接线（计划 §6）

### 10.1 Desktop（计划 §6.2）

- 使用现有 JDBC SQLite driver（`app.cash.sqldelight:sqlite-driver:2.3.2`，jvmMain 声明不动，F-2）。
- 使用临时文件数据库（`Files.createTempFile` 临时目录路径 + `JdbcSqliteDriver` + `LedgerDatabase.Schema.create`）；验证创建、关闭、同一当前 schema 重开。
- 注入固定 catalog；requestId 源与六 ID 源使用**产品**随机源（`java.security.SecureRandom`，F-5），时钟使用**产品**系统时钟（`LedgerClock { Clock.System.now() }`，F-4）；确定性 request source 与 test clock 只属于验证/demo harness（实施批 jvmTest 与人工演示脚本），不进产品组合根（finding P503SPEC-006）。运行共享 UI（`P503App`）。
- 验证 Created、NoChange、冲突、startup error/retry/exit 和重启恢复（同一临时文件重开）。
- 组合根装配顺序沿用 IMP-10：driver → schema → `LedgerDatabase` → commit port（`SqlDelightConfirmedManualExpenseCommitPort(database, driver)`）→ `CommitOnceInvocationTracker` → factory（`ConfirmedExpenseTransactionFactory`，接线为 `createAssetPaidOrdinaryExpense(catalog, AssetPaidOrdinaryExpenseCommand(ledgerId = request.ledgerId, amount = request.amount, categoryId = request.categoryId, paymentAccountId = request.paymentAccountId, times = TransactionTimes.collapsed(request.occurredAt)), ids.expenseIds)`；既有测试同款接线可复制自 `ConfirmedManualExpenseIdempotencyTest.kt:258-293`，finding P503Q-013）→ 六 ID source（`UuidV7ConfirmedManualExpenseIdSource(UuidV7Generator(::secureRandomBytes))`）→ requestId source（`UuidV7ManualExpenseRequestIdSource(UuidV7Generator(::secureRandomBytes))`，独立 generator 实例）→ `ExecuteConfirmedManualExpense(tracker, idSource, factory)` → `ExecuteManualExpenseSave` → read adapter（`SqlDelightLedgerCurrentStateReadAdapter(database)`）→ `QueryLedgerCurrentState`/`ResolveManualExpenseCommitStatus`/`ParseManualExpenseAmount`/`QueryManualExpenseOptions` → `ExecuteManualExpenseSubmission(save, tracker, resolver)` → `P503LedgerFacade` → `P503App(facade, onExit)`。`ledgerClock = LedgerClock { Clock.System.now() }` 注入 facade。

### 10.2 Android（计划 §6.3）

- 保留现有 `com.android.application` 组合根与 `AndroidSqliteDriver` 私有库（应用私有 `ledger.db`，沿用 IMP-11 既有 handle 模式）。
- 组合根装配顺序与 Desktop 相同，仅 driver 与随机源不同（`AndroidSqliteDriver(LedgerDatabase.Schema, context, "ledger.db")`、Android 侧 `java.security.SecureRandom`）。
- CI 执行 `:android-app:assembleDebug`；CI 上传 APK 工件；同步在 CONTRIBUTING 记录下载和人工安装步骤（§11）。
- 下载固定 SHA 对应 APK 后安装到 emulator；首次启动、创建/打开应用私有当前 schema 数据库；Activity/进程退出后同版本重开；核对启动状态、空态和最小流程。
- CI 成功不得误写成 emulator 人工证据已完成（计划 §6.3）。

两组合根都只保留平台 graph 构造 + `P503App` 调用；不复制金额解析、业务校验、提交状态机或余额计算。

## 11. CI 与文档同步（计划 §6.3；CONTRIBUTING 同步规则）

- `.github/workflows/ci.yml` android job 的 `assembleDebug` 步骤后新增 `actions/upload-artifact@v4` 步骤（当前 ci.yml 无任何 upload-artifact 步骤），逐字 snippet（finding P503Q-012）：

```yaml
      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: android-debug-apk-${{ github.sha }}
          path: android-app/build/outputs/apk/debug/*.apk
          retention-days: 7
```

- `.github/workflows/ci.yml` kotlin job 新增 `:app-ui:jvmTest` 步骤（`./gradlew --no-daemon --max-workers=1 :app-ui:jvmTest --warning-mode all`），与 CONTRIBUTING §13.1 命令同步；kotlin job 其余步骤与 python job 保持原样（finding P503SPEC-001）。
- `docs/CONTRIBUTING.md` 新增：下载固定 SHA 对应 APK 的人工步骤（`gh run download`/GitHub Actions artifact 页面下载 → `adb install`）与安装/启动/同版本重开人工核对步骤。
- 修改验证步骤时 ci.yml 与 CONTRIBUTING.md 同步更新（CONTRIBUTING 同步规则）。
- CI 必须不被当作 emulator 人工证据；artifact 可下载只是 Android 人工验收的固定输入（计划 §1/§6.3）。
- 本批实施后同步维护 README/PROJECT_MAP/ARCHITECTURE（新增 `app-ui` 模块与运行命令描述；ARCHITECTURE「P5-03 批准边界」段在实施后更新为已实现状态由主代理在合入登记批处理）。

## 12. 测试矩阵（计划 §7 逐节映射）

### 12.1 Application/domain（计划 §7.1）

- parser 全部固定有效/拒绝向量（§6.3 逐字）；成功精确 minor units，失败稳定格式错误（EMPTY/INTERNAL_WHITESPACE/INVALID_FORMAT）。
- 缺 amount/category/payment account 的 `InvalidInput`（`ManualExpenseInputFailure.Missing(AMOUNT/PAYMENT_ACCOUNT/CATEGORY)`）。
- options ownership/kind/currency/leaf 过滤（扩展匿名 catalog：非 owned、非 ASSET、非 real 账户不出现；parent/non-active/非 EXPENSE/非法 posting account 的 category 不出现）。
- current-version-only projection：被 current pointer 替代的旧版本不得作为当前行返回。
- 五种 account kind 的 normal-balance sign（扩展匿名 catalog）：ASSET/EXPENSE display = ledger sign；LIABILITY/INCOME/EQUITY 取反。
- multi-currency 不汇总（扩展匿名 catalog：跨币种分开按 account+currency 分组）。
- requestId 保留与显式更换规则（§4.6 生命周期逐条）。
- `Created`、`NoChange`、`RequestIdentityConflict`、`Rejected`。
- pre-submit zero-call failure → `InfrastructureFailure`（零调用可证明，同 requestId 可重试）。
- **第二次提交的 pre-handoff failure → `InfrastructureFailure`（finding P503Q-001）**：首次提交成功（或任何一次提交完成）后，再次提交在 handoff 前失败，仍须归类为可重试的 `InfrastructureFailure`，不得被上一次提交的 `commitOnceInvoked` 痕迹污染为 `UnknownCommit`（`ExecuteManualExpenseSubmission.submit()` 起点先 `tracker.reset()`）；同 requestId 可重试。
- post-handoff matching/conflict/absent/unavailable 四态（`Recovered` 只能由 `MatchingReceipt` 产生；`SnapshotConflict` → 稳定 `RequestIdentityConflict`；`Absent`/`Unavailable` → `UnknownCommit`）。

### 12.2 Data integration（计划 §7.2）

匿名临时数据库验证：current pointer（只返回当前版本）、ledger isolation（跨 ledger 零串读）、receipt lookup（transactionId 一致性）、snapshot matching/conflict、reopen 一致性、零重复 transaction/posting、schema/migration 零变化（migration verifier 与 schema v27 不变）。

### 12.3 UI reducer/semantics（计划 §7.3）

空态→编辑→待确认→Created；取消零写入；字段错误保留输入；NoChange 不追加行；Unknown 禁止 retry/new requestId；冲突保留 requestId；StartupError Retry/Exit；requestId 单一分配规则（§7.4）；`Submitting → Application(InvalidInput)` fallback → Editing（输入保留）；`InfrastructureFailure(READ)` 重试刷新路径（app-ui commonTest，使用内存测试替身，不依赖 `ledger-data`）。

**测试策略（冻结，finding P503SPEC-005/P503Q-015）**：app-ui commonTest 只做**纯 reducer/状态机测试**；reducer 可注入的纯依赖替身仅需 `ParseManualExpenseAmount`（字段完整性检查为纯函数，无需替身）。宿主层依赖（`LedgerCurrentStateReadPort`/`ManualExpenseOptionsProvider`/`ResolveManualExpenseCommitStatus`/`ExecuteManualExpenseSubmission`/`ManualExpenseRequestIdSource`/`LedgerClock`）属于宿主异步层/组合根，**不是** reducer 的依赖，reducer 测试不构造它们。**不引入** compose ui-test harness（不依赖 `compose.uiTest`），因此 `commonTest` 仅需 `implementation(kotlin("test"))`（与 §3.2 一致）。semantics 标签/错误关联/动态状态的可感知性验证归 §9 无障碍门（D-119 §3.8 人工 + Desktop 键盘流 + Android TalkBack 人工门）。

测试类名（命名冻结，finding P503Q-010/P503Q-014）：`P503ReducerTest`（app-ui commonTest，`com.unifiedledger.ui`）；细分组组织由 writer 自由落实。测试以**事件序列**驱动矩阵场景，结果事件由测试直接分发（宿主异步层不在 reducer 测试范围内）：例如「空态→编辑→待确认→Created」= `StartNewExpense` → `Update*` → `Continue(requestId)` → `Confirm` → `SubmissionResult(Application(Executed(Created)))` → `Created` → `RefreshResult(currentState)` → `OverviewEmpty`；其余场景同样以 `StartupCompleted`/`StartupFailed`/`InitialLoadResult`/`InitialLoadFailed`/`SubmissionResult`/`RefreshResult`/`RefreshFailed` 等事件组合驱动（提交结果目标态的 `draft`/`requestId` 由测试构造的 `Submitting(draft, requestId)` 源状态提供）。

### 12.4 Platform（计划 §7.4）

- Desktop：`:desktop-app:jvmTest`、`:desktop-app:build`、临时文件关闭/重开。
- Android：CI assemble、artifact 下载、emulator 安装、启动、私有库当前 schema 创建/打开、退出、同版本重开。

## 13. 验收命令与门（计划 §8；D-119 §6.3/§6.4/§6.5）

### 13.1 自动验证命令（按 CONTRIBUTING 单机串行资源规则，逐字：`.\gradlew.bat --stop` → `$env:GRADLE_OPTS='-Xmx1024m'` → 单个 task `.\gradlew.bat <task> --no-daemon --max-workers=1 '-Dkotlin.daemon.jvmargs=-Xmx1024m' --stacktrace --rerun-tasks --warning-mode all` → `.\gradlew.bat --stop`；一次一个）

- `.\gradlew.bat :ledger-application:jvmTest`
- `.\gradlew.bat :ledger-data:jvmTest`
- `.\gradlew.bat :ledger-domain:jvmTest`
- `.\gradlew.bat :app-ui:jvmTest`
- `.\gradlew.bat :desktop-app:jvmTest`
- `.\gradlew.bat :desktop-app:build`
- `.\gradlew.bat :android-app:assembleDebug`
- `.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration`
- `.\gradlew.bat :ledger-data:compileAndroidMain`
- `.\gradlew.bat ktlintCheck`
- Python 全量测试（`$env:PYTHONPATH="tools\python"` 后 `python -m unittest discover -s tests -t . -v`）
- `python -m project_docs .`
- `verify-project -Scope full`

注：`:app-ui:jvmTest` 显式执行 app-ui 的 commonTest（reducer/semantics 纯函数测试）；`:desktop-app:jvmTest` 只运行 desktop-app 自身测试，不执行 app-ui 的测试（finding P503SPEC-001/P503Q-004）。`ledger-application`/`ledger-domain` 本批无 Android target（§3.1/§3.3 冻结，构建脚本零改动）；`app-ui` 的 android 编译与双端消费由 `:android-app:assembleDebug` 与 `:desktop-app:build` 覆盖（含里程碑 0 冒烟，§13.2 门 0）。

### 13.2 实施前置门（计划 §8）

1. **门 0（里程碑 0 冒烟，finding P503Q-003）**：最小 `app-ui` 骨架（`settings.gradle.kts` 注册 + `app-ui/build.gradle.kts` + commonMain 一个空 `P503App` composable）双 target 编译通过（`:app-ui` android 编译任务，如 `:app-ui:compileAndroidMain`，以实际任务名为准；以及 `:app-ui:jvmMainClasses`/`:app-ui:compileKotlinJvm`），并被 `:android-app:assembleDebug` + `:desktop-app:build` 消费；**先于任何其余 UI 代码**。若 `org.jetbrains.compose 1.11.1 × AGP 9.1 com.android.kotlin.multiplatform.library`（KMP android target 组合，仓库无先例）不兼容，按官方 CMP/AGP 兼容性指引处理并回门，不静默更改共享模块选择（fallback 见 §3.1）。
2. P5-03 实施规格独立 specification review；
3. 独立 quality review；
4. distinct verifier；
5. Desktop current-schema reopen evidence（自动）；
6. Android APK artifact、安装、启动、私有库同版本重开证据（人工；D-119 §5.2）；
7. startup fail-closed 聚焦测试；
8. 用户明确批准实施 —— 本批由 standing-delegation（「研判后全权批准推荐的计划」）下达，独立评审通过后即视为满足（§2）。

### 13.3 完成门（计划 §8 + D-119 §6.4）

- 两端共享 UI 流程和 parity 通过（D-119 §3.7 逐行）；
- application/data/UI 测试全绿；
- `Created`/`NoChange`/`Recovered` 后权威刷新；NoChange 不追加第二行；
- `UnknownCommit` 不误判、不自动重试、不换 requestId；
- Android/Desktop 无障碍和人工流程通过；
- CI artifact 可下载；
- 代码、测试、CI、CONTRIBUTING 同步；
- schema、migration、正式账务规则零变化。

### 13.4 开放门登记规则

任何不能闭合的门（如 R-9 本地 APK 装配 OOM、emulator 不可用导致的人工证据开放）必须如实登记，绝不标记为已完成；不降低标准（D-119 §5.2/§7）。

## 14. 风险与开放项

| # | 来源 | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- | --- |
| R-9 | 继承 D-118 R-9 | 本地 `:android-app:assembleDebug` OOM（`mergeExtDexDebug` 在 1 GB heap 与 `-Xmx3g` 两度 OOM） | 本地无法产出完整 APK | 本地门 = `compileDebugKotlin`/`processDebugResources`/`dexBuilderDebug`；完整 APK 装配归 CI + emulator 人工门；不静默改 CONTRIBUTING/根构建资源控制 |
| R-10 | 继承 D-119 §5.2 | Android emulator 当前不可用 | entry 人工证据（安装/启动/私有库同版本重开）保持开放 | 如实登记开放门；CI 先产出可下载工件；不降低标准 |
| R-11 | 本批新增（finding P503Q-003） | `org.jetbrains.compose 1.11.1 × AGP 9.1 com.android.kotlin.multiplatform.library` 的 KMP android target 组合仓库无先例（仓库 compose 使用仅在 `com.android.application`；唯一 KMP android target `ledger-data` 无 compose） | app-ui android target 构建不兼容风险 | 里程碑 0 冒烟门（§13.2 门 0）先行，先于任何其余 UI 代码；不兼容时按官方 CMP/AGP 兼容性指引处理并回门；fallback（§3.1）：真实解析失败时为 `ledger-application`/`ledger-domain` 增加 android target 并登记观察到的 Gradle 错误全文；不静默更改共享模块选择 |
| R-12 | 承接 P5-02 R-2 | `compose.material3` 在 1.11 线解析为 alpha（`1.11.0-alpha07`），进入正式共享 UI（P5-02 中 material3 是占位 only） | alpha API 面进入共享 UI | 计划 §1 固定取舍明确使用原生 Compose/Material 3；§3.2 解释「Material3 alpha 工件」禁令 = 禁止**额外** alpha 工件，冻结的 `compose.material3` 访问器属允许范围（finding P503SPEC-009）；正式 M3 版本选择仍留皮肤批；实施批开工时登记实际解析坐标 |
| R-13 | 本批新增（D-119 §3.4） | UnknownCommit 语义风险：会话内无自动退出路径，重启只恢复正式结果 | 用户可能误以为数据丢失 | UI 明确呈现 resolving/unknown 状态文本；文档登记；若未来要求恢复 draft/unknown state，必须先新增持久化契约（D-119 §3.4） |
| R-14 | 本批新增 | 双端组合根占位 UI 收敛为 `P503App` 的改动面 | 重构风险 | 改动仅限组合根装配与 `Main.kt`/`App.kt` 占位 UI 替换；共享核心零改动；评审聚焦依赖拓扑 |
| R-15 | 本批新增（§7.4 规格级决定） | 初始权威读失败 / 提交后刷新读失败的状态归属（计划未命名） | 呈现语义分歧 | 规格级决定：映射为 `InfrastructureFailure(READ)`（零写入、可重试刷新），不扩大 submission 结果语义（§7.4） |

## 15. 执行路由（计划 §10）

- 一个独立 worktree 和一个 bounded writer（本批规格只起草，实施批另立独立 worktree）。
- 冻结候选后再做独立 specification review 和 quality review；任何候选文件变化都使受影响审查和验证证据失效，必须重新冻结。
- 独立 distinct verifier 不得兼任 writer/reviewer。
- 主代理负责 Git、critical diff、全量受影响验证、合入和 publication。
- 未经另行批准不得推送、引入皮肤依赖、修改 schema 或扩大产品范围。

## 边界断言（本批不含）

- 本批唯一写入 = 本新文件；`docs/specs/` 既有文档、`DECISIONS.md`、`ARCHITECTURE.md`、`CONTRIBUTING.md`、CI 与全部模块零改动。
- 本文件为实施规格草案：未经评审通过不构成实施授权；P5-03 实施仍在独立 worktree、单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径、个人数据、图标资产品牌名、agent/会话痕迹；`.external/` 内容除门查阅声明外零引用。
