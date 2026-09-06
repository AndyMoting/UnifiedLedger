# P5-04.5-FOUND-001 Android 损坏数据库 fail-closed 决定批（设计规格）

**状态：** approved — 本文件为 P5-04.5-FOUND-001 的决定批规格（decision-only：零实现、零 schema、零生产代码、零依赖）。冻结 Android 端数据库损坏的六项处置决定（§4 D-1..D-6）与后续实施批的冻结范围与验收（§5）。实施、备份/恢复产品语义、恢复 UI 明确不在本批（§6）。

**修订 A-1（2026-09-06，R-1 触发）：** 实施批评审（FOUND-IMPL-S-001/S-002/Q-001/Q-002/Q-005，两个独立评审）实证：SQLDelight 2.3.2 `AndroidSqliteDriver` 为**惰性打开**——`createAndroidLedgerDatabase` 仅构造 driver、从不触碰 SQLite（App.kt:66-81 组合根 `openDatabase` lambda 无 driver 访问）；生产启动中真正的打开发生在 `Ready` 之后首个 UI 查询（P503App.kt:172-178 首查询 → QueryLedgerCurrentState.kt:57-61 `catch Exception → Unavailable` → InitialLoadFailed），损坏异常因此永远达不到 controller 的 `StartupError` 映射——D-130 机制上移一层复现；T-C 原设想的 `createAndroidLedgerDatabase(...).close()` 同样不触发打开。按本规格 §5「不得静默变更」与 R-1 回退触发条件，修订回归本决定批：D-1 增补急切探针打开、D-2 上浮异常叙事修正、T-C 以探针语句为真实打开（见 §4「增补与修订」及 §5.1/§5.2 修订标注）；fail-closed 行为与文件保留不变量不变。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `98fc242` 的行号；`.external/` 只读）：

- **主检出根指引**：主检出 `AGENTS.md`（worktree handoff 指定入口；外部证据门、验证分工与高风险路由按其执行）。
- **计划任务源（checkpoint 路由局部文件，不入 Git）**：docs/PHASE6_AND_REMAINDERS_PLAN_2026-09-06.local.md §B「P5-04.5-FOUND-001：Android 损坏数据库」——列出本决定批必须解决的六问与四项技术评估要求；本文件 §4 逐项落点。
- **决定承接**：docs/DECISIONS.md:2142（D-129 fail-closed 启动契约）、:2156（D-130 P5-04.5 回归收口与 FOUND-001 披露，:2170 为披露原文）、:2176（D-131，条目结构先例，语义正交）。
- **源码锚点（只读检查）**：ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:12-18（`AndroidSqliteDriver` 创建，传入既有 `ForeignKeysCallback`；无任何损坏处理）、:39-50（`ForeignKeysCallback` 现状：仅 `onConfigure` 启用外键与 busy_timeout 注记）；android-app/src/main/kotlin/com/unifiedledger/android/App.kt:66-79（`openDatabase` 装配与 mid-open 关闭保护）、:113-158（`AndroidStartupController`：`start()`、`logFailure` 端口 :117、catch 任意 Exception → `StartupError` :150-156）；app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503StartupScreen.kt:27-34（`P503StartupState` 契约）、:60-74（StartupError 呈现：文案 + 重试/退出）；desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:151-194（`DesktopStartupController` fail-closed 参照）、:316-328（JDBC 打开失败即异常路径）；android-app/src/test/kotlin/com/unifiedledger/android/AndroidStartupControllerTest.kt（既有 JVM 测试形态：无 Robolectric，`openDatabase`/`logFailure` 注入）。
- **工具链锚点（现状，本批零升级）**：SQLDelight `2.3.2`（build.gradle.kts:4）；`app.cash.sqldelight:android-driver:2.3.2`（ledger-data/build.gradle.kts:48）；`ledger-data` `minSdk = 34` / `compileSdk = 36`（ledger-data/build.gradle.kts:37-38）；`android-app` 现状只有 `main`/`test` 两个源集（无 `androidTest`，§5.2 T-C 为新源集）；CI 无模拟器、无 `connectedAndroidTest`（.github/workflows/ci.yml:70 仅 `:android-app:testDebugUnitTest`）。
- **验证分工**：docs/CONTRIBUTING.md「本机与 CI 的验证分工」（:27-29）与「本机 Gradle 资源限制」（:14-25，串行、单 worker、1 GB heap）。

术语与编号约定：`本批` = P5-04.5-FOUND-001 决定批；`损坏` = SQLite 栈判定的数据库文件完整性失败（corruption），与普通打开失败、权限失败区分（D-5）；`fail-closed` = 失败必须以异常呈现为 `StartupError`，禁止静默替代。**本批局部决定编号 D-1..D-6、证据编号 E-1..E-8、测试编号 T-A..T-D、风险编号 R-1..R-6 仅在本批内稳定使用，与全局 `docs/DECISIONS.md` 的决定编号空间（如 D-129）不同；本批批准后整体登记为 D-132（§8）。**

## 1. 目的与范围

1. **批定义**：P5-04.5-FOUND-001 决定批 = D-130（docs/DECISIONS.md:2170）处置条款指向的独立决策门。交付物只有本文件：六项处置决定（§4 D-1..D-6）、实施批冻结范围与验收（§5）。零实现、零 schema、零生产代码、零新依赖。
2. **问题（D-130 披露的分歧）**：Android 端 ledger.db 损坏时，androidx SupportSQLite 默认路径静默删除原文件并重建空库，应用正常启动为空账本；Desktop 同场景 JDBC 抛异常 → `StartupError`（fail-closed）。本批裁决 Android 如何到达与 Desktop 一致的 fail-closed 语义，并冻结实施批边界。
3. **范围外（本批明确不做）**：不修改任何生产代码与测试；不裁决备份/恢复/隔离重命名的产品语义（D-3 延后并给出触发条件）；不改共享 `app-ui` 契约；不改 CI。
4. **批次定位**：本批批准后，实施批（§5 冻结范围）另开，过独立评审与主代理验收；持久化变更高风险路由按主检出 `AGENTS.md` 与 `unifiedledger-harness` 执行。
5. **交付物清单与稳定 ID 约定**：本批交付物唯一 = 本文件。稳定 ID：决定 D-1..D-6、证据 E-1..E-8、测试 T-A..T-D、风险 R-1..R-6；后续批次与登记文档引用这些 ID，不重编号。

## 2. 既有裁决承接（批准后并入 D-132 登记）

以下为用户已作出的裁决与登记；本批原文承接复述，不新增裁决，不改写任何既有决定：

1. **D-129（docs/DECISIONS.md:2142）fail-closed 重试策略与失败状态单一来源**：启动失败/重试呈现与重试所有权归平台 `P503StartupState` + 两端 controller；`openDatabase` 契约为 `() -> CloseableLedgerGraph(facade, close)`，重试/重建前关闭旧连接、失败中途关闭新开 driver（S3）；`logFailure` 为注入端口（S2，Android 侧默认三参 `Log.w` 保留堆栈）；任何形式自动重试被明确禁止（启动重试为手工按钮）。
2. **D-130（docs/DECISIONS.md:2156，披露原文 :2170）**：「Android 端 ledger.db 文件损坏时（实机故障注入：以非 SQLite 内容替换后重启应用），androidx SupportSQLite 记录 "Corruption reported by sqlite"/"deleting the database file" 后静默删除并重建空库，应用正常启动为空账本（全新 schema v27 空库），无 StartupError、无数据可恢复提示；桌面端同场景 JDBC 抛异常 → `DesktopStartupController` → StartupError（fail-closed，Retry/Exit）」；定性「建议级别 Low-Medium（损坏本身已使数据不可读，但静默删除剥夺了诊断/恢复机会）」；处置「留待后续独立决策批（方向示例：自定义 SupportSQLiteOpenHelper 错误处理/预开完整性检查），本批不做代码修复、仅登记披露」。本批即该独立决策批。
3. **D-131（docs/DECISIONS.md:2176）**：与本批语义正交（录入体验批）；其条目结构（状态/决定/边界/验证证据/关联决定/遗留）与「已知卫生项登记延后」方式为本批 §5/§7 的格式先例。
4. **计划 §B（checkpoint 路由局部文件）六问 → 本批落点**：

| 计划 §B 待决问题 | 本批落点 |
| --- | --- |
| 损坏数据库是否一律进入 `StartupError` | D-1 |
| 是否禁止删除原始数据库文件 | D-2 |
| 原库不可读时如何保留证据、导出诊断并提示恢复 | D-3 |
| 备份、恢复、隔离重命名文件和人工恢复的产品语义 | D-3（延后，触发条件已登记） |
| 完整性检查时机，以及完整性失败与普通打开失败的区别 | D-4、D-5 |
| Android 与 Desktop 是否统一 fail-closed 语义 | D-6 |

## 3. 外部证据门记录

本批改变平台集成与持久化失败行为，按主检出 `AGENTS.md` 触发外部证据门。本节证据全部来自官方源（AndroidX 官方文档与官方源码、AOSP 平台源码、sqlite.org 官方文档、SQLDelight 官方仓库、Google Issue Tracker），逐条给出 URL 与关键引文，并转换为中立契约条款；不复制上游文档正文；`.external/` 内容零引用、零改动。证据时点：2026-09-06。

- **E-1 androidx.sqlite `SupportSQLiteOpenHelper.Callback.onCorruption`（官方参考页 + 官方源码）**：
  https://developer.android.com/reference/androidx/sqlite/db/SupportSQLiteOpenHelper.Callback ；源码 https://android.googlesource.com/platform/frameworks/support/+/androidx-main/sqlite/sqlite/src/androidMain/kotlin/androidx/sqlite/db/SupportSQLiteOpenHelper.android.kt
  官方引文："The method invoked when database corruption is detected. Default implementation will delete the database file."（自 androidx.sqlite 2.0.0 起可覆盖）。即：损坏时默认行为是删除数据库文件，覆盖该回调即可替换该行为。
- **E-2 `FrameworkSQLiteOpenHelper` 的损坏路由（官方源码）**：
  https://android.googlesource.com/platform/frameworks/support/+/androidx-main/sqlite/sqlite-framework/src/androidMain/kotlin/androidx/sqlite/db/framework/FrameworkSQLiteOpenHelper.android.kt
  平台 `DatabaseErrorHandler` 的损坏回调直接转接 `callback.onCorruption`——`SupportSQLiteOpenHelper.Callback.onCorruption` 是 androidx 框架删除路径的唯一转接点，覆盖它即覆盖删除路径。
- **E-3 androidx 默认损坏处理的日志与空库重建机制（官方源码 + 评审对解析版本 androidx.sqlite 2.6.2 的字节码核对）**：
  日志串 "Corruption reported by sqlite on database:" 与 "deleting the database file:" 位于 **androidx 自身**的 `SupportSQLiteOpenHelper.Callback.onCorruption`/`deleteDatabaseFile`（tag "SupportSQLite"），**不在**平台 `DefaultDatabaseErrorHandler`——`FrameworkSQLiteOpenHelper$OpenHelper` 安装自己的 `DatabaseErrorHandler` lambda 直接转接 `callback.onCorruption`（E-2），平台默认处理器从不运行。空库重建发生在**同一次 open 调用内**：androidx 重试循环 catch → sleep ≈500 ms → 重试 open →（默认回调已删除文件）→ `onCreate` 建空库；并非「helper 下次 open 重建」。与 D-130 实测披露（androidx SupportSQLite 记录后静默删除）一致。
  源码：https://android.googlesource.com/platform/frameworks/support/+/androidx-main/sqlite/sqlite/src/androidMain/kotlin/androidx/sqlite/db/SupportSQLiteOpenHelper.android.kt ；https://android.googlesource.com/platform/frameworks/support/+/androidx-main/sqlite/sqlite-framework/src/androidMain/kotlin/androidx/sqlite/db/framework/FrameworkSQLiteOpenHelper.android.kt
  机制背景（AOSP 同源对照，不在 androidx 运行路径上）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/DefaultDatabaseErrorHandler.java
  结论不变：默认回调删除文件；覆盖 `onCorruption` 即阻止删除（D-2）。
- **E-4 `SupportSQLiteOpenHelper.Configuration.allowDataLossOnRecovery`（官方发布说明）**：
  https://developer.android.com/jetpack/androidx/releases/sqlite#2.3.0-alpha01
  官方引文："Sets whether to delete and recreate the database file in situations when the database file cannot be opened"；androidx.sqlite 2.3.0-alpha01 起提供，默认 `false`；为 `false` 时 open 重试的错误改为重新抛出。仓库不设置该开关即维持「禁止删除重建 + 错误上抛」语义。
- **E-5 SQLDelight `AndroidSqliteDriver` 自定义回调入口（官方源码）**：
  https://github.com/sqldelight/sqldelight/blob/main/drivers/android-driver/src/main/java/app/cash/sqldelight/driver/android/AndroidSqliteDriver.kt
  构造接受自定义 `callback`（其 `Callback` 类型继承 androidx `SupportSQLiteOpenHelper.Callback`）与 `SupportSQLiteOpenHelper.Factory`。仓库既有 `ForeignKeysCallback` 即经此入口注入——`onCorruption` 覆盖零新依赖、零新工厂。
- **E-6 sqlite.org `PRAGMA integrity_check` 语义（官方文档）**：
  https://sqlite.org/pragma.html#pragma_integrity_check
  官方以检查范围描述二者差异：`quick_check` 略去部分深度检查，`integrity_check` 为完整检查；健康库返回单行 "ok"。量级注记：本批成本论证统一按**整库扫描量级**表述；精确 O(N)/O(NlogN) 渐近值由实施批复核（LOW：不影响 D-4 结论——任一变体均为全库扫描）。构成 D-4 成本论证的官方依据。
- **E-7 异常分类（官方参考页）**：
  https://developer.android.com/reference/android/database/sqlite/SQLiteDatabaseCorruptException 及同包 SQLiteException、SQLiteAccessPermException、SQLiteDiskIOException 各参考页。四类均为 `SQLiteException` 子类：损坏、权限、磁盘 IO 在打开路径上的失败形态官方已定型，诊断层无需自建分类（D-5）。
- **E-8 rename-aside 无官方背书**：
  官方文档与官方源码均无「损坏时把原文件改名隔离」的推荐做法；该想法仅以开放 feature request 存在：https://issuetracker.google.com/issues/267355686 。本批据此将 rename-aside 列为未背书选项并显式裁决（D-3）。

## 4. 决定

批局部编号 D-1..D-6（编号空间约定见 Authority And Boundary）。

### D-1 损坏一律 StartupError（与 Desktop 统一 fail-closed，禁止静默空库重建）

- **决定**：Android 上 SQLite 栈检出的数据库损坏，无论发生在 open、迁移或使用期，一律以异常路径上抛，由 `AndroidStartupController` 既有 catch 映射为 `P503StartupState.StartupError`；禁止任何形式的「静默删除原文件并重建空库后正常启动为空账本」。
- **理由**：D-129 契约意图是失败必须可见、可重试、可退出；D-130 实测证明缺陷机制是异常未达 controller 端口——框架层先静默重建（E-1/E-3）；「损坏即静默弃数据」使用户失去数据出过事的全部信号，与「只有显式确认才创建或替换正式账本事务」的项目边界相悖。
- **备选与落选理由**：维持默认删除重建并依赖空库提示（落选：静默、不可见、剥夺诊断/恢复机会，即 FOUND-001 本身）；为损坏提供专用错误页或分级错误呈现（落选：本批不改共享 UI 契约，用户面单一 `StartupError` 由 D-5 冻结；分级呈现属未来 UI 批）。

### D-2 禁止删除或改写原始数据库文件：覆盖 `onCorruption` 为抛出固定异常；`allowDataLossOnRecovery` 保持默认 false；打开失败同步上浮——损坏路径为固定异常、迁移/权限路径为原始 SQLiteException（修订 A-1 修正上浮异常叙事）

- **决定**：
  - 在既有 `ForeignKeysCallback`（AndroidLedgerDatabaseHandle.kt）中 override `onCorruption(db: SupportSQLiteDatabase)`：**抛出一个固定的新的异常类型**（在 ledger-data androidMain 定义；类型名与 message 由实施批冻结），替代 androidx 默认的「删除数据库文件」实现（E-1/E-3）；回调体内零文件操作。签名事实：`onCorruption` **不携带异常参数**（评审对解析版本 androidx.sqlite 2.6.2 的核对），回调内没有可 rethrow 的原始异常——固定异常类型是阻止默认删除的覆盖形态。
  - **controller 的实际接收物（修订 A-1 叙事修正）**：损坏路径上浮的最可能是本覆盖抛出的固定 `LedgerDatabaseCorruptionException`（非 SQLiteException）：androidx `innerGetDatabase` 吞掉首次失败的 open 尝试、sleep ≈500 ms 后重试一次，重试对原样保留的损坏文件再次失败，第二次失败后将非 SQLiteException **原样重抛**（`allowDataLossOnRecovery = false` 保持默认，E-3/E-4）——本句替换初稿「controller 最终收到真实 `SQLiteException`」的错误表述；迁移/权限路径上浮的仍是原始 `SQLiteException`（E-7）。既有 `logFailure` 端口（App.kt:117）在损坏路径记录固定异常、其余路径记录原始异常，诊断结论不变。
  - 不设置 `allowDataLossOnRecovery = true`：保持默认 `false`（E-4）。若为 `true`，androidx 将自行删除并重建数据库文件，直接违反本条不变量。
- **理由**：controller 的 catch 类型无关（`catch Exception` → `StartupError`，App.kt:150-156），固定异常类型与各路径上浮的异常（损坏路径固定类型、迁移/权限路径原始 `SQLiteException`）都映射 `StartupError`；固定类型的唯一职责是让「不删除」成为显式、可单测（T-A）的覆盖行为；诊断层在迁移/权限路径由 E-7 官方异常自描述，损坏路径由固定异常承载（修订 A-1）。
- **备选与落选理由**：
  - 「原样 rethrow 传入异常」——落选且不可实现：`onCorruption(SupportSQLiteDatabase)` 无异常参数（修正本规格初稿该错误表述）。
  - 自定义 `SupportSQLiteOpenHelper`/工厂错误处理（计划 §B 技术评估第 1 项）——落选：能力上是 `onCorruption` 覆盖的超集，需新增工厂与配置面；E-2 已证明框架删除路径唯一转接点即该回调，覆盖已足够时为过度设计。
  - 失败时关闭资源、保留原文件并向 controller 暴露明确异常（计划 §B 技术评估第 3 项）——已采纳，但由既有机制承载（App.kt:66-79 mid-open handle 关闭 + controller catch 的 `activeGraph` 关闭，D-129 S3），本批零新增。

### D-3 证据原位保留：不自动重命名、不自动备份、无应用内恢复；诊断经 logFailure；恢复为应用外人工操作

- **决定**：损坏发生后，原始数据库文件（含 `-wal`/`-shm` 伴随文件）由本批代码保持原位、原样：不删除、不改写、不重命名、不复制备份、不导出；应用侧唯一动作是经既有 `logFailure` 端口记录失败（含原始异常与堆栈）；恢复（取出文件、人工诊断、人工恢复数据）是应用外操作。隔离重命名（rename-aside）、自动备份、应用内恢复流程在本批全部不做。**延后触发条件**：未来批对备份/恢复/诊断导出作出产品裁决时（D-130 处置条款与计划 §B 第 4 问的产品语义部分）另行立批，并重新过外部证据门。
- **理由**：rename-aside 无任何官方背书（E-8，仅开放 feature request）；任何自动改写文件的动作都与 D-2「永不改动原文件」不变量冲突；本批目标是止血（停止静默删除）而非设计恢复产品。
- **备选与落选理由**：损坏时自动 rename-aside（原文件改名保留、新建空库可用）——落选：无官方背书（E-8）、与本批原位不动不变量冲突、文件命名/回收语义需产品裁决；先自动备份再重建——落选：备份内容本身不可信（损坏文件）、复制触发时机与保留策略未裁决、变相恢复「自动弃原文件」语义。

### D-4 不做预开完整性检查（本批不新增 PRAGMA integrity_check/quick_check 通道）

- **决定**：本批不新增 open 前的 `PRAGMA integrity_check`/`quick_check`（或对数据库副本执行等价检查）预检通道。损坏检出完全依赖 SQLite 栈在 open/使用期经被覆盖的 `onCorruption`（E-1/E-2）。
- **理由**：检出已内建且已在发生——损坏页在首次触及时由栈报错并进入 `onCorruption`，本批覆盖后该检出即上抛；预检在大库上的启动成本不可接受：两种 PRAGMA 均为整库扫描量级（E-6），对副本执行还需一次全库复制（双倍 IO 与空间峰值），与 16 GB 主机验证预算及低端设备启动预算冲突；预检通过不能阻止使用期损坏，对启动路径是纯冗余。
- **备选与落选理由**：open 前对原文件直接 `quick_check`——落选：仍是整库扫描（启动延迟同量级），且对疑似故障介质执行读密集检查本身无收益；open 前全库复制 + 对副本 `quick_check`——落选：复制 + 检查双重整库扫描，启动延迟与存储峰值双倍，唯一收益是把检出点从使用期提前到 open 前，而检出后的行为（D-1/D-2）完全相同。登记：未来若产品要求「主动健康报告」类功能，另行立批评估，不属本批。

### D-5 三类打开失败共用既有 StartupError 映射；不新增异常分类体系

- **决定**：完整性/损坏失败、普通打开失败（schema/迁移/IO）、权限失败（如 `SQLiteAccessPermException`）在阻止删除（D-2）后均为「`openDatabase` 抛异常」这一同一路径，全部由 `AndroidStartupController` 既有 catch 映射为 `StartupError`（既有行为，App.kt:150-156）。本批不引入新的异常分类体系（D-2 的固定覆盖异常为单一类型、无分支消费者，不属分类）、新错误枚举或新的用户可见错误分级；三类区分存在于 `logFailure` 记录的异常类型/堆栈（诊断层；修订 A-1：损坏路径记录固定覆盖异常，迁移/权限路径记录原始 `SQLiteException`）。用户面维持 `P503StartupScreen` 现契约：「无法打开本地账本（本地数据库不可用）」+ 重试/退出（P503StartupScreen.kt:60-74）。
- **理由**：controller 契约本就类型无关，且已被 D-129 既有 JVM 测试钉住；E-7 官方异常分类已足够诊断用；新增分类体系无消费者，且扩大共享 UI 面。
- **备选与落选理由**：按异常类型映射不同错误文案/恢复建议——落选：属共享 UI 契约变更（未来 UI 批）；且错误分级可能诱导用户在应用内自行「修复」损坏文件，与 D-3 的应用外人工恢复语义冲突。

### D-6 Android 与 Desktop 统一 fail-closed 语义

- **决定**：两端对「打开本地账本失败」的语义统一为：异常 → `StartupError` → 仅重试/退出。Desktop 现状已满足（JDBC 抛异常 → `DesktopStartupController` → `StartupError`，Main.kt:151-194、:316-328）；Android 经 D-2 到达相同语义。两端不因本批新增任何平台差异分支。
- **理由**：D-130 披露的正是平台层行为分歧；统一语义消除「哪一端会丢数据」的分歧面；共享 `P503StartupScreen` 契约零改动即成立。
- **备选与落选理由**：Android 端在 `StartupError` 下单独提供「重建空账本」第三动作——落选：静默弃数据的显式化仍是无显式确认弃数据，违背项目边界；且改共享 UI 契约（同 D-1/D-5 落选理由）。

### 增补与修订（修订 A-1，2026-09-06，R-1 触发）

- **D-1 增补（急切探针打开）**：`createAndroidLedgerDatabase` 必须在返回前强制一次急切打开——构造 driver 后执行一条最小探针语句（如经 SQLDelight driver API 的平凡 `SELECT 1`；探针语句的精确形态由实施批冻结），使 onCreate/onUpgrade/损坏失败同步地从组合根 `openDatabase` lambda 上浮进 `AndroidStartupController.start()` 的 catch → `StartupError`。理由：惰性打开会把失败推迟到 `Ready` 之后首个 UI 查询——那里被 `QueryLedgerCurrentState.query()` 的 `catch Exception → Unavailable` 吞掉（评审核对：App.kt:66-81 组合根 lambda 无 driver 访问；P503App.kt:172-178 首查询；QueryLedgerCurrentState.kt:57-61 catch），损坏因此绕过 controller 的 `StartupError` 映射，D-130 机制上移一层复现。App.kt 保持零改动；探针不得改写 schema 或数据。
- **D-2 叙事修正**：见 D-2 修订标注——损坏路径上浮的异常最可能是固定的 `LedgerDatabaseCorruptionException`（固定类型实施形态，即初稿「类型名由实施批冻结」的落地；androidx `innerGetDatabase` 吞首次失败、sleep ≈500 ms 重试、第二次失败后原样重抛非 SQLiteException），迁移/权限路径为原始 `SQLiteException`；fail-closed 行为与文件保留不变量不变。
- **T-C 验收修正**：见 §5.2 修订标注——探针语句即 instrumented 测试（路径 2/3/4）内 `assertOpens` 形态断言必须执行的「真实打开」（assertThrows 包裹 `createAndroidLedgerDatabase(...).use { it.<探针> }`），路径 4 setup 须先强制打开再作摘要，并按 D-3 增补 `-wal`/`-shm` 伴随文件保留断言。

## 5. 实施批冻结范围与验收

实施批尚未批准；本节为本批批准后实施批的冻结范围。实施批不得扩大本节；发现承载缺口须先回本批修订并过评审门，不得静默变更。

### 5.1 代码范围（冻结）

- ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt：`ForeignKeysCallback` 增加 `onCorruption` override（抛出固定新异常类型；零文件操作）+ 同模块新增小型固定异常类型（类型名与 message 由实施批冻结；置于同文件或同模块单文件均可）。为使同模块 `androidUnitTest` 可达，回调与异常类型可见性可从 `private` 放宽至 `internal`（唯一允许的附带改动；Kotlin `internal` 为模块作用域，跨模块不可见）。除此之外无结构性改动。
- （修订 A-1，D-1 增补）急切探针打开：`createAndroidLedgerDatabase` 在构造 driver 后、返回 handle 前执行一条最小探针语句（如平凡 `SELECT 1`，精确形态由实施批冻结），强制 onCreate/onUpgrade/损坏失败同步上抛至组合根；仍在同一文件内，App.kt 零改动，探针零 schema/数据变更。
- android-app/src/main/kotlin/com/unifiedledger/android/App.kt：**零改动**（任意 openDatabase 异常已映射 `StartupError`）。
- 零 schema 变更（v27 与全部迁移文件不变）；零 RG/导入/对账行为变更；零新依赖、零新 Gradle 模块。

### 5.2 测试范围（冻结）

- **T-A callback 单测（JVM，无 Robolectric、无 Android 框架）**：以 fake `SupportSQLiteDatabase` 调用 `onCorruption`，断言：(a) 抛出固定类型的异常（非删除行为）；(b) fake 记录显示零删除调用、零文件操作。落位冻结：ledger-data 新增 `androidUnitTest` 源集承载（Kotlin `internal` 为模块作用域，android-app 测试不可见——「经 android-app 既有 `src/test` 覆盖」的备选不可行，已删除）。
- **T-B `AndroidStartupControllerTest` 扩展（既有 JVM 测试类，无 Robolectric）**：注入模拟损坏异常 → 断言 `StartupError`、`facade` 为 null、`logFailure` 收到该异常；并断言映射与异常类型无关（与 D-5 一致，普通失败与损坏形态走同一状态）。
- **T-C Android instrumented 测试（android-app/src/androidTest，新源集；现状不存在，实施批创建）**：四条路径，各带验收判据（修订 A-1：SQLDelight driver 惰性打开——各注入路径的「真实打开」必须是探针语句，`close()` 不触发打开）：
  1. **正常打开**：健康库启动 → `Ready`，且数据库文件字节摘要前后一致（未被触碰）。
  2. **损坏注入**：以非 SQLite 字节替换 ledger.db 后启动 → 显示 `StartupError`（重试/退出可用），**且原文件字节逐字节保留**（前后摘要一致）——FOUND-001 直接反证：D-130 实测的「静默重建空库」不得再发生。（修订 A-1）打开经探针语句真实执行：assertThrows 包裹 `createAndroidLedgerDatabase(...).use { it.<探针> }`（`assertOpens` 形态）；`-wal`/`-shm` 伴随文件保留断言一并成立（D-3 原位不变量覆盖伴随文件）。
  3. **迁移失败注入**：预置旧 schema 版本/不完整 schema 使迁移路径抛出 → `StartupError` 且原文件字节保留。（修订 A-1）同以探针语句为真实打开（assertThrows 形态同上）。
  4. **权限失败注入**：以不可读权限（mode/world 语义）放置数据库文件 → `StartupError` 且文件存在性与位置不变（权限受限下字节断言以存在性 + 不变性为准）。（修订 A-1）setup 须先经探针语句强制打开（预期抛出）后再作摘要/存在性断言，避免惰性打开使断言空转。
- **T-D StartupError/重试行为断言（并入 T-B/T-C）**：`StartupError` 下重试在文件仍损坏时仍为 `StartupError` 且文件仍保留；文件被人工恢复（应用外操作）后重试达 `Ready`。重试资源安全（单活跃连接、mid-open 关闭）由 D-129 S3 既有测试继续钉住，零新增断言面。
- **执行通道与前提**：T-C 仅在本地受管模拟器作为人工门证据执行；APK 为固定提交的 CI artifact 或本地 `assembleDebug` 产物，执行前核对 SHA-256 一致；模拟器属环境前提，非产品依赖。T-A/T-B 为本机常规 JVM 验证步骤。
- **CI 边界（冻结）**：CI 零改动——无模拟器、无 `connectedAndroidTest`（与 docs/CONTRIBUTING.md 验证分工一致，:27-29；ci.yml:70 维持 `:android-app:testDebugUnitTest`）。T-C 四路径证据登记于实施决定条目。若未来 CI 引入模拟器，须同步修订 `docs/CONTRIBUTING.md` 与 `.github/workflows/ci.yml`（同步约束），另行立批。

### 5.3 验收判据汇总

- T-A：`onCorruption` 抛出且零删除/零文件操作记录——缺任一即不通过。
- T-B：损坏形态异常映射 `StartupError` + `logFailure` 记录；`facade` 恒为 null。
- T-C 路径 2 为本批核心验收：`StartupError` 呈现 + 原文件字节级保留，二者同时成立；其余三条路径各自的 `StartupError` + 文件不变判据成立。
- 全局判据：`:android-app:testDebugUnitTest` 全绿、触及 `ledger-data` 时 `:ledger-data:compileAndroidMain` 通过、`ktlintCheck` 全绿、`project_docs` 通过、同提交 CI 成功（聚合门发布证据）。
- 验证命令（本机约束按 docs/CONTRIBUTING.md：串行、单 worker、1 GB heap，验证前后 `.\gradlew.bat --stop`）：

```powershell
.\gradlew.bat :android-app:testDebugUnitTest --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat :ledger-data:compileAndroidMain --stacktrace --rerun-tasks --warning-mode all
.\gradlew.bat ktlintCheck --stacktrace --rerun-tasks --warning-mode all
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

## 6. 边界（明确不做）

- 零导入/RG/对账/账务规则变更；零 schema/迁移变更（v27 不变）。
- 无恢复 UI、无备份/恢复/诊断导出、无应用内文件管理（D-3，含延后触发条件）。
- 无预开完整性检查（D-4）；无新异常分类体系（D-5）；无用户可见错误分级。
- 共享 `app-ui` 契约零改动（`P503StartupState`/`P503StartupScreen` 原样）；desktop-app 零改动。
- 零新第三方依赖、零新 Gradle 模块；`androidTest` 源集仅测试用，不进入产品构建。
- `.external/` 零触碰；`DECISIONS.md` 等既有 tracked 文件零改动（登记归 §8 批准后流程）。

## 7. 风险登记

| # | 风险 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 固定异常覆盖 + 500 ms 重试路径的行为在设备/版本差异下偏离评审核对的 2.6.2 机制（异常不达 controller） | 损坏仍可能不呈现为 `StartupError` | T-C 路径 2 为硬验收实证；若实证失败，回本批修订（禁止实施批静默改方案，D-2 回退触发条件）。修订 A-1 登记：本触发条件已于 2026-09-06 经实施批评审成立（惰性打开——异常不达 controller 的另一机制，见页首修订记录），修正回归本批 A-1 处置 |
| R-2 | androidx.sqlite 传递版本（经 android-driver 2.3.2）随依赖升级移动 | E-3/E-4/D-2 的机制描述需随版本复核 | 评审已核对当前解析版本 2.6.2（sqlite.aar）；实施批登记实施时点 resolved 版本并复核 E-3/E-4 语义（该语义自 2.3.0-alpha01 起稳定） |
| R-3 | 阻止删除后，损坏库用户完全无法进入应用（可用性损失） | 数据保留但不可用，恢复全为人工 | 产品语义有意 fail-closed 优先（D-1/D-3）；登记为已知产品行为，不做静默缓解 |
| R-4 | 权限/磁盘 IO 失败形态存在 OEM/设备差异 | T-C 路径 4 未覆盖的设备面 | 权限路径覆盖主要面；OEM 差异登记为未验证面（延续 D-130 未验证面登记方式）。**busy/locked（数据库文件被锁）为登记的非路径**：Android 侧单连接模型无 busy 竞争（AndroidLedgerDatabaseHandle.kt:43-48 busy_timeout 注记——`PRAGMA busy_timeout` 不能经 execSQL/execute 设置，单连接演示面无竞争），busy/locked 失败形态不单列；未来引入多连接时另行立批 |
| R-5 | 新 `androidTest` 源集与 instrumented 执行增加本机验证负担 | 16 GB 主机资源约束 | T-C 仅本地模拟器人工门、CI 零新增；T-A/T-B JVM 测试为主自动化面 |
| R-6 | 使用期（非启动期）损坏经提交/查询路径抛出 | 行为面超出启动范围 | `onCorruption` 覆盖对使用期损坏同样阻止删除（文件保留不变量全局成立）；使用期失败经既有提交失败通道传播，其呈现语义不在本批，如需另行立批 |

## 8. 批准后的登记路径

**批准记录（2026-09-06）：** 独立规格评审 FOUND-SPEC-001..006（APPROVE-WITH-FINDINGS）→ delta 修订 → 闭环复审 FOUND-SPEC-001..006 全部 CLOSED（余项 FOUND-SPEC-007 为 LOW 非阻塞，已按建议于本文件 D-5 与 D-132 吸收）→ 主代理按常设授权批准。

登记路径执行情况（登记动作经本批准授权）：

1. 本文件状态行已由 `proposal` 翻转为 `approved`（批准记录见上）。
2. `docs/DECISIONS.md` 已追加 **D-132**：内容 = §4 D-1..D-6（六项决定）+ §5 实施批冻结范围与验收（T-A..T-D 与 CI 边界）+ §6 边界。
3. 实施批另开（待执行）：独立 worktree、单一 bounded writer、独立评审与主代理最终验收；持久化变更高风险路由按主检出 `AGENTS.md` 与 `unifiedledger-harness` 执行；实施批同时完成 §5 全部验收并登记 R-2 的 resolved 版本核对。
4. 批准与登记不触发提交、推送或 CI 变更；push 由主代理按既有授权流程执行。

## 边界断言（本批不含）

- 本批规格阶段唯一写入 = 本新文件；批准后登记动作仅限 §8 所列两项（本文件状态行翻转与批准记录、`docs/DECISIONS.md` 追加 D-132），另加 R-1 触发的修订 A-1（本文件页首修订记录与 §4「增补与修订」、§5 修订标注）及 D-132 实施规格段的修订注一行——均为已授权写入。`docs/specs/` 既有文档、其余全部模块源码/测试/构建脚本与 CI 零改动。
- 本文件为决定草案：未经批准不构成实施授权；实施批在独立 worktree、单一 bounded writer、独立评审与主代理最终验收之下。
- 本文件不含本机绝对路径、个人数据、账务锚点、agent/会话痕迹；`.external/` 内容除本节门声明外零引用。
