# P7-04 A-04.1 批：建行 XLS Android 真实 HSSF parser instrumented 验证设计（隔离测试入口）

状态：approved（规格 v2 冻结候选；v1 评审 REQUEST-CHANGES findings S3/S4/S5.1-S5.4 已逐项吸收，S1/S2/S6 PASS。源码基线 main `f13f42615162ce2cec587901f2a6769c3bb3b367`，schema v30。本 tracked 版由冻结规格 v2 按本目录既有 design 文档组织风格正式落盘，全部命题、交付内容、验收与纪律逐条忠实保留。）

**Revision:** v2（冻结）。依据：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-04 行；spec `docs/specs/2026-09-14-p7-04-import-draft-confirmation-design.md` X-4（HSSF 在 Android 无官方可运行背书）；D-146/D-147；D01 文书 §2#4/#5。本批为 **test-only**（零产品代码改动），normal tracked 路由；设备执行由主代理按高风险 Android 归属协议执行（AVD 归属核实 + 隔离 adb 端口）。

## Authority And Boundary

- **命题来源**：spec `2026-09-14-p7-04-import-draft-confirmation-design.md` X-4 风险——POI core（HSSF）虽随 APK 打包（D01 unzip 证据 + 评审独立复核）且 `CcbBillParser`（ledger-application jvmMain，public object）对 androidTest 编译可见（android-app main 已消费同类 jvmMain 类先例；androidTest classpath ⊇ main），但「可编译、已打包」不证明「Android 运行时可执行」。
- **不可触碰面**：`.external/` 只读；零产品代码改动（`CcbBillParser`、`ImportFormatCapabilities`、orchestrator、任何 main 源集）；零 Gradle 依赖声明；`tests/fixtures/` 源文件只读引用（副本入 androidTest assets）；既有 P-31..P-48 内联期望零改动（JVM 精确断言不重写，回归面零扩大）；CI 不变（`ci.yml` 保持只 assembleDebug，instrumented 手动门）。
- **本批不做**：矩阵翻转（`ImportFormatCapabilities` 中建行 XLS 的 Android「待设备运行验证」标记）与产品路径验收——A-04.2/A-04.3 另批（高风险路由）。

## 1. 命题与边界

**命题（可证伪）**：HSSF（Apache POI 5.5.1）在 Android 运行时可用真实 instrumented 执行解析 BP-01 建行 XLS 输入，且与 JVM 解析逐字段等价。

**测试入口（隔离）**：androidTest 直接调用 `CcbBillParser.parse(inputRef, bytes)`；平台门语义（`JvmImportFileIntake` 矩阵门）由既有 JVM 测试钉死，本批零触碰。若设备红分支抛 `NoClassDefFoundError`（Error 不被 parser 的 `catch (Exception)` 捕获），测试直接红——落入证伪分支。

## 2. 交付内容

### 2.1 新 androidTest：`CcbBillParserAndroidInstrumentedTest`

位置 `android-app/src/androidTest/kotlin/com/unifiedledger/android/`（先例 `AndroidStartupFailClosedInstrumentedTest`；runner 配置已在 `android-app/build.gradle.kts` `testInstrumentationRunner`）。入口直接调用 `CcbBillParser.parse(inputRef, bytes)`。

### 2.2 共享期望载体（等价断言的唯一权威源）

- **tracked 期望文件** `android-app/src/androidTest/assets/fixtures/ccb/expected-batch-bp01-ccb.json`：7 个 fixture（`batch-bp01-ccb-{a,b1,b2,c,d1,d2,e}.xls`）的 `CcbBatchResult` 全对象确定性 JSON 序列化，含逐 ordinal `ImportSourceFacts` 全九字段（amountMinor/currencyCode/currencyPrecision/occurredAt/directionToken/statusToken/fundingState/fundingRuleId/fundingRuleVersion）、行级 recordKind、completeness、**全部行 diagnostics（含 accepted 行非阻断 note：REQUIRED_FACT_UNRESOLVED / SPINE_BANK_BALANCE_CONTINUITY 等——accepted 行的非阻断 note 是平台间差异的可观测出口，不得省略）**与批量级 batchDiagnostic。两端 per-fixture inputRef 取相同值（诊断含 inputRef 字段，另造 ref 必触发无谓红测）。
- **生成与权威链**：期望文件由 JVM 侧当前解析结果一次性生成（一次性工具运行，产物 tracked；工具本身不入库）。等价链：**JVM 解析 == 期望文件（逐字节）∧ androidTest 解析 == 期望文件（逐字段）⇒ 同字节输入两端解析等价**。
- **JVM 同步测试**（`CcbBillParserJvmTest` 新增）：解析 7 个 fixture 并经共享序列化器（`CcbBatchResultJson`，jvmTest 侧测试工具，手写确定性 JSON、无浮点）序列化，与期望文件**逐字节**比对。既有 P-31..P-48 内联期望保持原样。
- **androidTest 断言（全粒度，无弱化）**：解析 assets 字节 → 平台 org.json 反序列化期望文件 → 逐 fixture、逐行、逐字段断言（九字段 + recordKind + completeness + 全部行 diagnostics 含 accepted 行 note）。**禁止对 jvmMain 产品类型加 @Serializable 序列化注解**（违反零产品改动）；反序列化用平台 org.json（android.jar 自带，编译期已核实 API 面：getString/getInt/getLong/isNull/optString/JSONArray.getJSONObject/length 等，无 `similar`）。
- **序列化器护栏**：JVM 同步测试对 accepted 行断言 9 个 facts 字段名全部出现在序列化输出（防序列化器漏字段——同步测试与生成器同序列化器，自身无法发现漏字段）。
- 序列化形态：紧凑可 diff JSON、LF 行尾、无浮点（全部整数或带引号字符串）。

### 2.3 fixture 字节防漂移（JVM 侧文件级对比闭合）

- fixture 副本：`android-app/src/androidTest/assets/fixtures/ccb/batch-bp01-ccb-{a,b1,b2,c,d1,d2,e}.xls`（7 个，与 `tests/fixtures/` 逐字节相同，二进制 OLE2，`.gitattributes` 已按 `batch-bp01-ccb-*.xls binary` 覆盖）。
- **防漂移断言放 JVM 侧**（唯一能同时读两目录的位置）：`CcbBillParserJvmTest` 新增 fixture 同步测试，用既有 `repositoryRoot()` 机制逐文件 SHA-256 比对 `tests/fixtures/` 与 androidTest assets 副本；源文件变更未同步副本时 JVM 测试红。`repositoryRoot()` 从 `user.dir`（jvmTest 工作目录 = 模块目录）向上找 `settings.gradle.kts`，对 worktree 检出同样成立（worktree 根含 settings.gradle.kts；实施时以基线 jvmTest 全绿实测核实）。
- androidTest 端不做 hash 常量断言（设备读不到源目录，常量无法绑定当前内容）；androidTest 只解析 assets 字节本身。

### 2.4 内存合成拒绝向量（两端同批对等）

`CcbBillParserJvmTest` 与 androidTest 同批各增 4 个内存向量（期望显式、确定性合成）：

| 向量 | 合成（确定性） | 期望（两端同断言） |
| --- | --- | --- |
| V-empty | `ByteArray(0)` | 类型化拒绝，诊断码 `INPUT_DECODE_FAILED`，severity `fatal`，scope `input`，零行 |
| V-overlimit | OLE2 魔数 8 字节前缀 + 固定 0x41 填充至 `MAX_INPUT_BYTES + 1`（10,485,577 B） | 字节预检拒绝，诊断码 `INPUT_UNSAFE_OR_OVER_LIMIT`，零解析 |
| V-corrupt | OLE2 魔数 8 字节前缀 + 固定模式字节（0x00..0xFF 循环）4,096 B | POI 解码失败类型化拒绝，诊断码 `INPUT_DECODE_FAILED`，零候选行 |
| V-badmagic | 固定非 OLE2 字节（全 0x00）4,096 B | 魔数拒绝，诊断码 `INPUT_DECODE_FAILED` |

拒绝向量期望简单（诊断码 + severity/scope + 零候选行 + 全 null 定位字段），两端直接断言同一契约 token 字面量（诊断码是产品契约常量，非复制期望）。

### 2.5 不做

- 不改 `CcbBillParser`、`ImportFormatCapabilities`、orchestrator、任何 main 源集。
- 不加 Gradle 依赖声明（POI 已在 classpath；org.json 由 android.jar 提供；仅引用 public 类型）。
- 不入 CI（instrumented 手动门，D-130 先例）。

## 3. 测试与验收

1. **实施侧（Gradle 串行，单 worker，1GB heap，每步 `--stop`）**：
   - `:android-app:compileDebugAndroidTestKotlin`（或 assembleDebugAndroidTest）通过。
   - `:ledger-application:jvmTest --stacktrace --rerun-tasks` 全绿（含 2.2 同步测试、序列化器护栏、2.3 防漂移测试、2.4 四向量）。
   - 两模块 `ktlintCheck`（android-app + ledger-application）零违例。
   - 隐私自检：新 tracked 文件无本地路径/个人数据/agent 痕迹（fixture 与期望均匿名合成，派生自 tracked fixture）。
2. **主代理设备窗口**（AVD、隔离 adb 端口、归属核实）：`:android-app:connectedDebugAndroidTest` 全绿，逐向量记录；记录 logcat 中 POI/HSSF 异常（若有）。
3. **结果裁决**：
   - 全部绿 → 「HSSF 在 Android 运行时可执行」成立 → A-04.1 PASS；A-04.2（矩阵翻转 + `JvmImportFileIntakeJvmTest` 同批修订 + 产品路径设备验收）按高风险路由另批开。
   - 任何向量红 → 命题证伪 → 提交兼容方案与风险（不静默砍来源），A-04.2 不开。

## 4. 风险与纪律

- 单机 Gradle 串行（实施侧 Gradle 阶段与主代理设备计时窗口互斥；connectedDebugAndroidTest 由主代理排队执行）。
- 期望文件与 fixture 副本均匿名合成（tracked fixture 派生），无个人数据。
- 期望文件生成工具为一次性工具运行（jvmTest 内临时测试形态运行后删除，产物 tracked）。
- 期望文件为 LF 行尾纯 JSON；`.gitattributes` 对该路径无 CRLF 强制模式，检出即 LF，两端逐字节/逐字段比对不受行尾影响。
