# P7-06 备份与恢复 06.1 / 06.D 前置设计规格：两端稳定存储与 `LedgerRuntimeOwner`（单活动图、generation、operation lease、quiesce/close/reopen）

状态：proposal（2026-09-24 起草；本文是 06.1 / 06.D 前置的设计规格，**已经独立评审第一轮（REJECT，1 项 P1 + 若干 P2/P3）与独立 verifier 复核（修正两处过度归因），本轮已按意见修订；尚未经复审通过、未经批准**，故按 `docs/CONTRIBUTING.md:162` 的允许分类标 `proposal`。本文与已批准的容器格式规格 `docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（approved，依据 `docs/DECISIONS.md` D-174）逐条一致：本文承接其 §5（Q14 世代/指针设计）与 §6（技术门）中登记为 OPEN 的「稳定存储」与「文件指针切换/重启恢复」两项，**不重开**其任何冻结的容器格式决定，也**不修改**任何既有决定。本文只写设计，零产品代码、零测试、零 schema/迁移、零依赖。）

**Revision:** draft-2（2026-09-24）。相对 draft-1 应用了首轮独立评审与 verifier 的全部意见：P1（§3.2 旧路径升级改为「复制 → 打开并读回 → 发布指针 → 再删旧」的非破坏顺序；rule 3 收紧为「仅当完全无代际目录才允许全新安装」，代际目录存在但指针无效一律 fail-closed）、P2-1（`closeActiveGraph`/`reopen` 增加「零在飞 lease」前置与 typed 拒绝）、P2-2（转换义务改为「每一个 `facade.*` 入口点」并补全类别）、P2-3（06.1 对存在的 journal fail-closed）、P2-4（补写静默空库的机制性防护）、P2-5（点名同步原语）、D-2/D-3（修正两处过度归因）、P3-1～P3-4。原始基线说明见下。工作基线 = 本 worktree 基线 `dc274d5`（P7-06 06.0/06.A 收口后的登记点），schema **v31**，迁移链 `1.sqm`～`30.sqm`（30 个文件，v1→v31）。tracked 行号为该基线在本 worktree 的实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读；示例与命名全部匿名合成；引用不粘贴大段产品代码，不写本机绝对路径。本文**不**新增决定条目、**不**修改任何既有决定。本文已完成第一轮独立评审与独立 verifier 复核，本轮为据此修订的 draft-2。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为当前 worktree 基线 `dc274d5` 实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读）：

- **已批准的上游规格（承重权威）**：`docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（approved，D-174）：
  - §5.1（`:232-237`）：稳定存储是 06.D 前置；桌面入口每次启动新建临时目录的问题、Android 首次接入代际目录须迁移旧位置与 sidecar、目录解析不在 tracked 文件写绝对路径、启动顺序「先处理未完成 journal → 再选择活动库 → 最后才允许正常业务」、缺失/损坏已登记活动代 fail-closed。
  - §5.2（`:239-243`）：私有代际目录；旧代在新图打开并读回成功前保留；代目录与 journal 的命名与布局由实施批细化，本规格只冻结**语义**（私有、单账本、可枚举、可回退）。
  - §5.3（`:245-254`）：原子活动指针（Android `AtomicFile`；桌面「临时文件 + 原子 rename」）；持久 journal `prepared → switched → committed`；崩溃后**回滚（ROLLBACK）**；`AtomicFile` 不提供锁。
  - §5.4（`:256-262`）：默认拒绝未知/`user_version=0`/未来 schema；权威版本检查在 payload；**禁止复用桌面宽松补戳路径**。
  - §5.5（`:264-272`）：06.D 失败向量——确认前零正式写入、确认后拒绝新租约并等待既有读写退出、发布门、切换前/后失败与回滚、清理策略。
  - §6（`:295`）：稳定存储（两端固定产品路径 + 旧路径升级）登记为 **OPEN**，归属 06.1/06.D 前置；`:293`「文件指针切换 / 重启恢复」同为 **OPEN**，归属 06.1/06.D。
  - §7（`:307-314`）：P706-A03/A04/A06/A07/A10 的切片归属。
- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §6.1（`:140-156`；`:153` 的两端组合根 `LedgerRuntimeOwner` 责任行、`:156` 的稳定存储前置段）、§6.2（`:158-166`；`:162` 恢复状态机与准入/等待/发布门/回滚、`:164` 技术门与「默认拒绝」）、§6.3（`:168-194`；`:173` 切片 06.1/06.D 前置的放行条件）。上述计划文件为本地规划，其「推荐/建议」均为 proposal，不构成产品行为、迁移、技术选型或发布授权。
- **决定**：`docs/DECISIONS.md` D-174（`:3397` 起：批准容器格式规格与 Q13/Q14 设计级方案，并在第 4 条把「文件指针切换/重启恢复」与 Q14 的 quiesce/lease/generation 机制归入 06.1/06.4，把 OS 自动备份/设备迁移排除决策归入 06.1/隐私规格）；D-156（`:2947` 起：设计门规格与决定条目的引用形状、证据纪律、边界段结构）、D-158（`:2987` 起：实施登记与残余条件承接的形状；schema v30→v31）。本条只借其**格式**，不改变其任何裁决。
- **开发规范**：`docs/CONTRIBUTING.md:159-166`（正式文档以中文为主、代码标识符保留英文；不得含本机绝对路径、个人账务数据或临时讨论记录）、`:162`（新建/实质修改的 `docs/specs/` 设计必须标记 `approved`/`proposal`/`superseded`/`historical`）。
- **源码现实（逐条复核，file:line）**：见 §1。
- **不可触碰面**：`.external/` 只读；零产品代码、零测试、零 schema/迁移、零依赖、零清单；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动；不改容器格式规格的任何冻结字节布局、参数值或拒绝码；不改 D-156/D-158/D-174 及既有 P7-01～P7-05 冻结面。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 两端组合根：只有「打开或失败」两态，无 quiesce / lease / generation

| 事实 | 位置 | 后果 |
| --- | --- | --- |
| Android 启动控制器只有 Starting/Ready/StartupError 三态 | `android-app/src/main/kotlin/com/unifiedledger/android/App.kt:217-262`（类定义 `:217`、`state` 初值 `:223`、`start()` `:239-261`） | **没有** quiesce、作业租约、换库代际；恢复切换所需的「拒绝新工作并等待既有工作退出」不存在 |
| Android `start()` 有重入守卫并在重建前关闭旧图 | `App.kt:242`（`if (startedOnce && state == P503StartupState.Starting) return`）、`:246`（`activeGraph?.close()`）、`:256`（catch 内 `activeGraph?.close()`） | 只有「关闭」一个动作，且是「先关后开」的破坏式重建，不是受控的 quiesce→close→reopen |
| Android graph 仅以 close 回调表达生命周期 | `App.kt:281-288`（`CloseableLedgerGraph`：`facade`、`close`、`catalogSession`、`catalogCommands`、`runQueryStatisticsOptimize`、`runFullAnalyze`） | 没有「暂停准入」「等待在飞作业」「换代」的受控端口，也没有 generation 字段 |
| Android 固定数据库名与固定账本身份 | `App.kt:175`（`createAndroidLedgerDatabase(context, "ledger.db")`）、`:300`（`LedgerId("ledger-local-test")`） | 备份/恢复只针对该单一身份；首版不改写 ledgerId、不合并、不加 selector（容器规格 §5） |
| Android catalog bootstrap 在装配期**写**种子事实 | `App.kt:305-310`（`store.bootstrap(ledgerId, defaultCatalogSeed())`，`Seeded`/`AlreadyInitialized` 两分支） | 装配路径本身含写入；「缺表/缺事实」会被 bootstrap 掩盖（计划 `:151` 明令不得用 seed bootstrap 掩盖） |
| 桌面启动控制器同为三态且先关后开 | `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:239-282`（类定义 `:239`、`start()` `:258-281`、重入守卫 `:261`、旧图关闭 `:265`/`:275`） | 与 Android 同款缺口 |
| 桌面 `CloseableLedgerGraph` 同样无 generation/lease | `Main.kt:293-307` | 同款缺口 |
| 桌面产品入口每次启动新建临时目录 | `Main.kt:152-160`（`main()` → `createDemoDatabaseUrl()`）、`:162-165`（`Files.createTempDirectory("unifiedledger-demo-")` + `LOCAL_TEST_LEDGER_FILE_NAME` `:144`） | 产品路径**不稳定**，每次启动得到不同库；稳定存储是 06.D 的硬前置（容器规格 §5.1） |
| 桌面宽松「补戳」迁移路径 | `Main.kt:783-832`（`migrateToCurrentSchema`），尤其 `:791-794`（有 `catalog_version` 即补当前版本戳）与 `:812-819`（未知已填充库直接补当前版本戳） | 该路径会为某些 `user_version=0` 或不可判定的库**补版本戳**；外来备份**不得**复用它（容器规格 §5.4、计划 `:164`） |

### 1.2 Android 数据库句柄：只有私有 driver，无路径、无 busy_timeout/WAL 配置

- 工厂签名：`ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:9-19`（`createAndroidLedgerDatabase(context, name)`，`AndroidSqliteDriver(schema, context, name, callback = ForeignKeysCallback())`）。
- 句柄只暴露一个私有 driver：`AndroidLedgerDatabaseHandle.kt:71-85`（`private val driver: AndroidSqliteDriver`），`close()` 仅 `driver.close()`（`:86-88`）。**句柄不暴露路径**——代际目录/活动指针若要落地，须由平台适配器另行解析，不能从既有句柄取得。
- `ForeignKeysCallback.onConfigure`（`:139-148`）**只**设 `setForeignKeyConstraintsEnabled(true)`；注释明述 **不**设 `busy_timeout`（Android 上该语句返回结果行，`executeForChangedRowCount` 会拒绝）。故当前 Android 侧**未显式配置** WAL 与 busy_timeout（`onConfigure` 内无任何 `journal_mode`/`busy_timeout` 语句；这不等于「库上不存在 `-wal`」——本仓 instrumented 测试本身就把 `-wal`/`-shm` 当作可能存在的 sidecar 处理，见 §1.4）。
- `onCorruption`（`:150-161`）抛固定类型 `LedgerDatabaseCorruptionException`（定义 `:134-136`），零文件操作，保留原文件与 `-wal`/`-shm`。

### 1.3 共享 UI：55 处 `scope.launch`、23 处 `Dispatchers.Default`，无 Mutex/Semaphore

- 作用域：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:152`（`rememberCoroutineScope()`）。
- **计数（本基线实读）**：`P503App.kt` 中 `Dispatchers.Default` 出现 **23** 次（其中 `scope.launch(Dispatchers.Default)` **19** 次）；`scope.launch` 字面出现 **55** 次（含注释散文中的提及；其中裸 `scope.launch {` **31** 次）。仓库产品源码内 `Dispatchers.Default` **仅**出现在该文件（全仓 23 次）。
- 代表性 DB 调用点锚：`P503App.kt:183-187`（目录快照读）、`:955-972`（导入接治 + 统计刷新 + 复核列表读）、`:1821-1834`（修正提交）、`:2036-2042`（提交后的重读链）。这些点都直接在 `scope.launch(Dispatchers.Default)` 内调用 `facade.*`。
- **已有单飞守卫是协调器级的、非互斥**：`P503HostCoordinator.kt:604-637`（`P503CurrentStateLoadCoordinator`，`@Volatile` 布尔 + 合并语义）；同类还有 `P503CatalogSnapshotLoadCoordinator`（类定义 `P503HostCoordinator.kt:552`；实例化于 `P503App.kt:167`）。它们只保证「同一读不并发重入」，**不**提供「业务写入互斥」或「等待全部在飞工作退出」。
- **无 `Mutex`/`Semaphore`**：对产品源码（`android-app/src`、`desktop-app/src`、`app-ui/src`、`ledger-data/src`、`ledger-application/src`、`ledger-domain/src`）检索 `Mutex`/`Semaphore`/`withLock` **零命中**（命中项仅为注释散文与测试内 `AtomicInteger`，非协程互斥原语）。故当前不存在可复用的租约原语。

### 1.4 必须继续通过的生命周期测试（各自钉住的不变量）

| 测试 | 位置 | 钉住的不变量（一行） |
| --- | --- | --- |
| `AndroidStartupControllerTest` | `android-app/src/test/kotlin/com/unifiedledger/android/AndroidStartupControllerTest.kt`（`:48`/`:71`/`:87`/`:122`/`:147`/`:173`/`:198`） | 注入失败→StartupError 且不暴露 graph、重试可达 Ready、重试先关旧连接且**保持单一活动连接**、Starting 期间重入被忽略且不重建不关闭 |
| `AndroidStartupFailClosedInstrumentedTest` | `android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidStartupFailClosedInstrumentedTest.kt`（`:46`/`:82`/`:103`/`:120`） | FOUND-001：正常打开不动库文件；**损坏文件**路径（`:82-100`）逐字节保留原文件**且保留现存 `-wal`/`-shm`**（sidecar 断言仅在此路径）；**不兼容 schema**（`:103-117`）与**不可读文件**（`:120-151`）路径只断言主文件哈希/存在，不断言 sidecar。三条路径均要求工厂调用本身抛异常（`:162-166`） |
| `DesktopStartupControllerTest` | `desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopStartupControllerTest.kt`（`:20`/`:45`/`:61`） | 与 Android 控制器同款三态与「失败→重试可达 Ready、重复失败保持 fail-closed、重试先关旧连接且保持单一活动连接」语义；**该桌面测试不含重入用例**（重入不变量仅由 `AndroidStartupControllerTest.kt:198` 钉住；桌面重入守卫 `Main.kt:261` 目前是**仅代码、无对应测试**） |
| `DesktopCurrentSchemaReopenTest` | `desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopCurrentSchemaReopenTest.kt:26` | 同一当前 schema 文件关闭再打开后，正式提交与读边界可读回（正常重开回归） |
| `DesktopCatalogMigrationTest` | `desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopCatalogMigrationTest.kt`（`:19`/`:40`/`:57`/`:76`/`:103`/`:148`/`:187`） | 低版本就地迁移不删文件、当前版本直接重开、更高版本 fail-closed 保文件、无版本戳的已填充库**补戳而非失败**、宽松补戳分支的既有语义 |
| `ForeignKeysCallbackCorruptionTest` | `ledger-data/src/androidHostTest/kotlin/com/unifiedledger/data/ForeignKeysCallbackCorruptionTest.kt`（`:19`/`:39`） | `onCorruption` 抛固定类型且**零 DB 调用**；`onConfigure` 仍只设 FK（D-129 行为不变） |

## 2. Scope

### 2.1 范围内（06.1 / 06.D 前置）

- **两端稳定存储**（§3）：Android 应用私有数据目录、桌面运行时解析的每 OS 用户数据目录；两端的**旧路径升级**。
- **`LedgerRuntimeOwner` 契约**（§4）：单活动 graph、generation 计数、operation lease（全部业务工作必经）、quiesce→close→reopen、缺失/损坏已登记活动代 fail-closed。
- **启动顺序与状态机**（§5）：先处理未完成 journal → 再选择活动 generation → 最后允许正常业务；在既有 Starting/Ready/StartupError 上扩展出 06.D 所需的 quiesce/switch 状态，06.1 内**最小实现**。
- **失败向量与验证**（§6）：缺失活动代、损坏活动库、不可读文件、旧路径尚未升级、并发打开。
- **验收映射**（§7）：P706-A03/A04/A06/A07/A10 的 06.1 贡献与后续归属；**不标任何 PASS**。
- **回归**：正常重开与 FOUND-001 既有回归（§1.4 测试面）须继续通过。

### 2.2 明确范围外（后续切片）

- **06.B 导出 / 06.C 导入预检 / 06.D 恢复切换**的产品实现与 UI（计划 `:174-176`）；本规格只提供其稳定存储与 owner 前提。
- 容器格式、KDF/AEAD 参数、拒绝码（属已批准的 06.A 规格，本文不改）。
- 任何 schema 变更、迁移边、依赖变更（§8 说明 06.1 是否需要 schema 变更）。
- OS 自动备份/设备迁移排除决策（D-174 第 4 条归 06.1 平台适配器/隐私规格；本文只登记该边界，不裁决 Manifest 政策）。
- 代目录与 journal 的**字面命名与布局**（容器规格 §5.2 明确归实施批；本文冻结语义）。

## 3. 稳定存储方案（设计级）

### 3.1 共同原则

- **目录解析规则在运行期由平台 API 决定**；tracked 文件**不得**写本机绝对路径（`docs/CONTRIBUTING.md:165`、容器规格 §5.1）。测试与 demo 的临时路径**仍可注入并隔离**（计划 `:156`）。
- 稳定存储的**唯一目标**是让「活动代目录」有一个跨进程、跨重启恒定的宿主位置；它是容器规格 §5.2 私有代际目录的父位置，不改变 §5.2 的语义冻结。

### 3.2 Android

- **宿主位置**：应用私有数据目录，经平台 API 解析（`Context.getDatabasePath(...)` 的父目录或 `Context.filesDir`；两者均为应用私有、卸载即清除、无需存储权限）。**具体选哪个 API 由实施批按容器规格 §4.9「应用私有暂存目录」与 §5.2 代目录的父子关系确定**；本规格只冻结「必须是应用私有、必须跨进程稳定、必须可由平台 API 在运行期解析」三条。
- **活动库所在**：由代际目录承载（容器规格 §5.2）；`AndroidLedgerDatabaseHandle` 的私有 driver（§1.2）不暴露路径，故代目录解析发生在**平台适配器/组合根**层，不由既有句柄推导。
- **旧路径升级（硬要求，非破坏式有序序列）**：既有产品库位于 `databases/ledger.db`（`App.kt:175`）。首次接入代际目录时**必须迁移旧位置及其 sidecar**（`ledger.db-wal`、`ledger.db-shm`），**不得**把它当作空安装初始化（计划 `:156`、容器规格 §5.1）。判定与顺序规则：
  1. **无代际目录且旧位置 `ledger.db` 存在** → 进入「旧路径升级」，**严格按以下顺序**（任何一步失败即 fail-closed，旧文件保持原样）：
     a. **复制（不是移动）**旧主文件与存在的 `-wal`/`-shm` 作为**一个一致集合**到新的活动代目录；旧文件此刻**原样保留**。
     b. **打开新代并权威读回**（成功读回即容器规格 §5.2 的「新图打开并读回成功」）。
     c. **发布原子活动指针**（临时文件 + 原子 rename；容器规格 §5.3）。
     d. **仅在此后**才移除旧位置的三个文件。
     - **持久性要求**：在 (d) 移除旧副本之前，必须把新代目录下的三个文件与新指针目录**刷盘并 fsync**（flush + fsync 目录项），否则掉电可能留下「旧文件已删、新文件未落盘」的空洞。
     - **三文件的顺序/原子性规则**：主文件与 `-wal`/`-shm` 必须以**同一时刻的一致快照**复制（复制前先确认无其他连接在写；Android 单连接模型下即启动早期），且**不得**只复制其中一部分后打开——否则会出现主文件与 sidecar 不匹配的库。崩溃只允许落在「旧集合完整」或「新集合完整」两侧，中间态一律由 (a) 的复制顺序保证旧集合仍可回退。
     - **不得**新建空库覆盖；**不得**在指针发布前删除旧文件。
  2. **代际目录存在**（无论指针是否有效） → **不再**看旧位置；指针有效则正常启动（§5），指针缺失/无效则按 §4.5 fail-closed（见 rule 4）。旧位置若仍在，按 06.1 清理策略处理（本规格只要求「不得因此重复导入或静默改库」）。
  3. **完全无代际目录、且旧位置也不存在** → 全新安装，按正常路径初始化（**仅此路径**允许 bootstrap 种子，见 §4.5）。
  4. **代际目录存在但活动指针缺失/无效** → **fail-closed**（`StartupError`），**绝不**回退为全新安装。理由：rule 1 的 (a)→(c) 之间存在崩溃窗口（旧文件已复制、指针未发布），若该窗口被误判为「全新安装」就会静默创建空库并 bootstrap 种子——这正是本规格与容器规格 §5.1 明令禁止的静默空库。收紧后，§5.1 步骤 3 的「缺失/损坏 → fail-closed」才可达。
- **与上游容器规格一致**：本顺序即容器规格 §5.2（旧代在**新图打开并读回成功之前**保留）与 §5.3（指针为原子替换；`AtomicFile`/rename **不**使整组文件天然原子，故靠「先复制后发布再删除」的顺序而非单次 rename）在 06.1 启动路径上的落地。
- **不声称**：本规格**不**声称 OS 云备份/设备迁移已关闭（AndroidManifest 未声明 `allowBackup`/`dataExtractionRules`，容器规格 §1.2/§6）；该排除决策归隐私规格（D-174 第 4 条）。

### 3.3 Desktop

- **宿主位置**：运行时解析的**每 OS 用户数据目录**下的产品固定子目录。解析规则（设计级，不写死字面路径）：
  - 首选平台约定的用户数据根（Windows 为 `%LOCALAPPDATA%` 类、macOS 为平台标准的 Application Support 目录、Linux 为 `$XDG_DATA_HOME` 或其默认回退），在其下解析出本产品子目录；
  - 解析必须**只**依赖运行期环境/系统属性，**不得**在 tracked 文件写绝对路径，也**不得**依赖 `.external/` 或本地参考树（根 `AGENTS.md` 非协商边界）。
- **demo/test 注入**：现有 `createDemoDatabaseUrl()`（`Main.kt:162-165`）的临时目录语义**保留为可注入的测试/demo 入口**，但**不再**作为产品入口（计划 `:156`）。产品 `main()`（`Main.kt:152-160`）改为使用用户数据目录解析出的固定位置。
- **旧路径升级**：桌面既有**产品**路径本身就是每次启动新建的临时目录（`Main.kt:162-165`），**没有**可迁移的旧持久位置——故桌面侧「升级」= **停止把临时目录当产品路径**，而不是搬迁数据。**明确登记**：桌面侧不存在需要搬运的既有产品账本；`DesktopCurrentSchemaReopenTest` 等测试使用的 `Files.createTempFile` 路径是测试夹具，不属产品路径。**明确登记（有意不处理）**：旧产品路径（`Main.kt:162-165` 每次启动 `Files.createTempDirectory("unifiedledger-demo-")`）留下的历次临时目录与其中的数据库，升级后成为**孤儿**——本规格**不**做清理、**不**做迁移，也不把它们当作可恢复来源；这是有意的（这些目录本就随每次启动随机生成、且可能已被系统临时目录清理策略删除，无法可靠枚举归属）。

### 3.4 两端共同的稳定性要求

- 同一进程内解析出的宿主位置必须**恒定**（同一 OS 用户/同一应用安装内多次启动得到同一位置）；代目录在该宿主下可枚举（容器规格 §5.2「可枚举」）。
- 宿主位置解析失败（权限、无可用目录）必须 **fail-closed** 到 `StartupError`，**不得**回退到临时目录静默运行。

## 4. `LedgerRuntimeOwner` 契约（设计级）

### 4.1 责任

`LedgerRuntimeOwner` 是两端组合根的单一运行时所有者（计划 `:153`）。它：

1. 持有**至多一个活动 graph**（generation）；
2. 为**全部**业务工作（记账、导入、查询、未来 widget）发放 **operation lease**；
3. 支持 **quiesce（拒绝新 lease + 等待在飞 lease）→ close → reopen**；
4. 在缺失/损坏已登记活动代时 **fail-closed**，**绝不**静默创建空库。

### 4.2 端口签名形状（设计级，方法名 + 语义）

以下为**设计级签名形状**，用于冻结语义；具体类型、包与 Kotlin 细节由实施批确定。`Generation` 为单调递增的整型标识（每次成功 reopen 递增）。

```text
interface LedgerRuntimeOwner {
    // 当前活动 generation 的只读快照；未就绪时为 null。业务侧不得缓存跨 quiesce 使用。
    val activeGeneration: Generation?

    // 申请一个 operation lease。仅当 owner 处于 Ready（非 quiesce/switch/closed）时成功。
    // 返回的 lease 携带申请时刻的 generation；lease.close() 释放并递减在飞计数。
    // 未就绪时返回 typed failure（如 RuntimeNotReady），绝不阻塞 UI 线程等待。
    fun acquireLease(): LeaseAcquireResult

    // quiesce：原子地拒绝后续 acquireLease，并挂起至所有在飞 lease 释放（或在超时/阻断时
    // 返回 QuiesceBlocked 的 typed 结果）。返回后保证零在飞业务工作。
    suspend fun quiesce(): QuiesceResult

    // 关闭当前 graph 并释放其 driver。幂等。
    // 前置：零在飞 lease。在飞 lease > 0 时返回 typed 拒绝（QuiesceBlocked），绝不提前关闭。
    // 仅由 owner 内部（quiesce 成功后）或持有同一谓词检查的调用方调用，不对外自由开放。
    internal fun closeActiveGraph(): CloseResult

    // 打开指定 generation（默认：活动指针指向的 generation）并重建 graph；成功后 activeGeneration 递增。
    // 前置：零在飞 lease。在飞 lease > 0 时返回 typed 拒绝（QuiesceBlocked/RuntimeNotReady），
    // 绝不与在飞工作并发切换。打开/权威读回失败时 fail-closed，activeGeneration 不变或为 null，
    // 绝不返回半打开 graph。
    fun reopen(target: GenerationSelection): ReopenResult
}

interface Lease : AutoCloseable {
    val generation: Generation   // 申请时刻捕获的 generation
    override fun close()          // 幂等；释放租约
}
```

语义要点：

- **单活动图**：任一时刻至多一个已打开 graph。`reopen` 内部必须先 `closeActiveGraph()`（或由 `quiesce`→`closeActiveGraph`→`reopen` 的顺序保证），沿用既有 `AndroidStartupController.start()`「先关后开」的资源安全语义（`App.kt:246`/`:256`）但**升级为受控顺序**。
- **close/switch 的在飞前置（硬要求）**：`closeActiveGraph()` 与 `reopen()`（以及任何切换）在**在飞 lease > 0** 时必须返回 typed 拒绝（`QuiesceBlocked` 或 `RuntimeNotReady`），**不得**继续执行；即任何 close/switch 都**不**会在仍有租约时运行。这直接落实计划 `:153` 的「不提前释放仍运行的 SQLite 调用」。`quiesce()` 是唯一合法前置：只有它成功返回（零在飞 lease）之后，close/reopen 才可执行；`closeActiveGraph()` 因此是 owner 内部动作或由持有同一谓词检查的调用方触发，不是可自由调用的公开非挂起方法。
- **同步原语（P2-5）**：上述「原子地拒绝后续 acquireLease」「至多一个活动 graph」「在飞计数与状态迁移的一致性」由一个 owner 内部的 `kotlinx.coroutines.sync.Mutex`（守护状态迁移与在飞计数的读改写）加一个原子状态字段（`@Volatile`/atomic 状态，供无锁快照读取）承载。这是「既有协程能力」而非新依赖：`kotlinx.coroutines` 已是本仓依赖，且 `kotlinx.coroutines.sync` 属其标准模块（§8）。当前仓库产品源码**无**任何 `Mutex`/`Semaphore`（§1.3），故这是**新引入的用法**，但仍**不新增依赖**。该原语是 06.1 的实施义务，不属任何冻结决定。
- **generation 捕获**：lease 携带申请时刻的 generation（§4.4）。
- **fail-closed**：`acquireLease` 在非 Ready 时返回 typed failure；`reopen` 失败时**不得**产出空库（§4.5）。
- **`quiesce()` 自死锁（P3-3）**：若在**持有 lease 的协程内**调用 `quiesce()`，该协程自己即是一个在飞 lease，quiesce 会等待它释放——而它正阻塞在 quiesce 上，形成自死锁。该情形只由超时/`QuiesceBlocked` 路径兜底（quiesce 必须在超时后返回 typed 结果而非无限等待）；故调用方契约是「发起 quiesce 的上下文本身不得持有 lease」。

### 4.3 业务工作如何经过 owner

- **转换义务 = 每一个 `facade.*` 入口点**（不是某个「调用点计数」）：凡在共享 UI 内调用 `facade.*` 完成业务读写的地方，改造形状一律是「**在调用 `facade.*` 之前取得 lease，调用完成后释放 lease，并在释放前捕获该 lease 的 generation**」。按调用上下文分类（本基线实读，逐条复核）：
  - **(a) 异步 `Dispatchers.Default` 启动**：`P503App.kt` 中 `Dispatchers.Default` 出现 **23** 次，其中 `scope.launch(Dispatchers.Default)` **19** 次（其余 4 次为注释散文），即 §1.3 所述集合。代表锚：`:183-187`（目录快照读）、`:955-972`（导入接治 + 统计刷新 + 复核列表读）、`:1821-1834`（修正提交）、`:2036-2042`（提交后的重读链）。
  - **(b) 同步主线程读/写**：不经 `scope.launch`，直接在 UI 线程调用 `facade.*`。写入：`submit()`（`P503App.kt:612-720`），由 `P503App.kt:2472-2475`（确认按钮 `onConfirm` → `dispatchCurrentP503Action`）与 `P503HostCoordinator.kt:348-353`（`retrySubmission` → `onSubmit`）触达。读取：`requestMonthlyPayload()`（`P503App.kt:441-467`）、`selectTransaction()`（`P503App.kt:470-473`）。这些点同样必须经过 lease；因其在主线程同步执行，`acquireLease` 在非 Ready 时必须返回 typed failure 而**不得**阻塞（§4.2）。
  - **(c) 裸 `scope.launch` 目录命令**：`runCatalogToggle()`（`P503App.kt:1601-1610`）、`runCounterpartyForm()`（`P503App.kt:1698-1705`）——`scope.launch` 内直接调用 `facade.executeCatalogCommand`/`facade.counterpartyCommands`，不经 `Dispatchers.Default`，仍属转换范围。
- **`P503App.kt` 中的 `Dispatchers.Default` 出现 23 次、`scope.launch` 出现 55 次**只是规模说明，**不**构成转换清单——清单以「每一个 `facade.*` 入口点」为准，上列三类为其实读枚举。伪代码形状：

```text
scope.launch(Dispatchers.Default) {
    val lease = owner.acquireLease()
    if (lease is LeaseAcquireResult.NotReady) { /* typed UI 状态，不触碰 facade */ ; return@launch }
    try {
        val gen = lease.generation
        val result = facade.someBusinessCall(...)          // 原调用点
        scope.launch { /* 仅在 generation 仍为当前活动代时落状态 */ }
    } finally {
        lease.close()
    }
}
```

- **旧回调晚到不得污染新图**（计划 `:162`、P706-A07）：落到主线程的状态写入必须校验「捕获的 generation == 当前活动 generation」；不等则丢弃。这是既有「单飞 + 实例匹配消费」纪律（`P503HostCoordinator.kt:604-637`、`consumeRetainedIntentAfterRefresh` `:648-651`）在 generation 维度的扩展，**不是**新的互斥原语。
- **不提前释放仍运行的 SQLite 调用**（计划 `:153` 原文）：lease 的释放时机必须是业务调用返回之后（`finally`），不得以取消 UI 协程的方式假定调用已停止。

### 4.4 generation 语义

- generation 是**进程内**单调递增的整数；每次成功 `reopen` 递增。它**不**等同于容器格式的 `container_format_version`（后者属 §5.2 的容器/代目录，是磁盘概念），也**不**等同于 SQLite `user_version`（schema 版本）。
- generation 的作用是**防止旧异步结果污染新图**（§4.3），以及为 06.D 的 `prepared/switched/committed`（容器规格 §5.3）提供运行期锚点。**明确登记**：本文的 generation 与容器规格 §5.3 的 journal 阶段是**两个不同层次**——前者是进程内租约代际，后者是磁盘上的持久切换记录；06.1 只实现前者（最小），后者属 06.D。

### 4.5 缺失/损坏活动代的 fail-closed

- 已登记活动代**缺失**（指针指向的目录/DB 不存在）或**损坏**（SQLite 打开/`integrity` 失败）时：owner 进入 fail-closed，暴露 `StartupError`（或 06.D 的 `RecoveryRequired` 前身），**绝不**静默创建空库。
- **静默空库的机制性防护（P2-4）**：仅声明「绝不静默创建空库」不足以成立，因为当前工厂 `createAndroidLedgerDatabase` 的 `AndroidSqliteDriver` **在打开时即创建**（create-on-open），且 A-1 的 `SELECT 1` 探针（`ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:25-30`）会把这次创建**同步**逼出。故要求：**代际目录打开路径必须在调用该工厂之前先检查目标文件是否存在**；目标代的主文件不存在时**不得**调用 `createAndroidLedgerDatabase`（其 `AndroidSqliteDriver` 会在 `:9-19` 的工厂内静默建库），而应直接 fail-closed。只有「完全无代际目录的全新安装」路径（§3.2 rule 3）才允许调用该工厂去创建。桌面侧同款要求（JDBC 打开同样会创建）。
- 这与既有 FOUND-001 语义一致（`App.kt:254-260` catch → `StartupError`；`ForeignKeysCallback.onCorruption` 保留原文件，`AndroidLedgerDatabaseHandle.kt:150-161`）。
- **bootstrap 边界**：`buildLedgerGraph` 在装配期写 catalog 种子事实（`App.kt:305-310`）。**全新安装**路径允许该 bootstrap；**已登记活动代存在**的路径**不得**让 bootstrap 掩盖缺表/缺事实（计划 `:151`）——实施时须把「bootstrap 仅用于全新安装」显式化。本文登记该约束为 06.1 的实施义务。

## 5. 启动顺序与状态机

### 5.1 启动顺序（计划 `:156`、容器规格 §5.1）

```text
1) 解析稳定存储宿主位置（§3）
2) 检查 journal：06.1 不写任何 journal（journal 属 06.D）。若发现存在 journal 文件，
   说明这是 06.D 时代的状态或外部干预，06.1 一律 fail-closed（StartupError），不得忽略。
3) 选择活动 generation（活动指针 → 代目录；缺失/损坏 → fail-closed）
4) 打开并权威读回活动代（成功后 activeGeneration 就绪）
5) 进入 Ready，此后才允许正常业务（acquireLease 成功）
```

**顺序是硬要求**：未完成 journal 未处理前**不得**选择/打开活动代；活动代未就绪前**不得**发放 lease。

**步骤 2 在 06.1 的明确语义（P2-3）**：06.1 **不产生** journal（`prepared/switched/committed` 属 06.D，§4.4、§5.2），故「journal 存在」在 06.1 是一个**不应出现的状态**。两种可选处理中本规格选定**fail-closed**：journal 文件存在 → `StartupError`（不忽略、不静默删除、不尝试解析）。理由：静默忽略会让「06.D 切换中途崩溃后回退到 06.1 二进制」的情形直接打开一个可能半切换的库；fail-closed 与 §5.1 的「先处理 journal」硬顺序及容器规格 §5.3 的 ROLLBACK 规则一致（06.1 无法执行 ROLLBACK，故只能拒绝）。**不**采用「断言 journal 不存在并令步骤 2 为空操作」的替代方案——那会在该情形下静默放行。该向量已列入 §6 失败向量表。

### 5.2 06.1 状态机（在既有三态上扩展，最小实现）

既有 `P503StartupState` 只有 `Starting`/`Ready`/`StartupError`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503StartupScreen.kt:27-34`）。06.1 引入 owner 内部状态（可与 UI 三态映射），并**预留** 06.D 的切换状态：

```text
ResolvingStorage → JournalRecovery → SelectingGeneration → Opening → Ready
                                                                   ↑
                                          Quiescing → Closing → Reopening ─┘
                                                                   ↓
                                        StartupError (fail-closed) / RecoveryRequired (06.D)
```

- **06.1 实现最小集**：`ResolvingStorage`、`SelectingGeneration`、`Opening`、`Ready`、`StartupError`，以及 `Quiescing`/`Closing`/`Reopening` 的**可调用骨架**（供正常重开与后续 06.D 使用）。
- **06.D 才实现**：`JournalRecovery` 的完整 prepared/switched/committed 语义、`RecoveryRequired`、以及切换期 UI。
- **UI 映射**：06.1 内 `ResolvingStorage`/`SelectingGeneration`/`Opening`/`Reopening` 均可映射到既有 `Starting`；`Ready`/`StartupError` 不变。故 06.1 **不需要**新增 UI 状态（零 UI 面变更），新增状态是 owner 内部状态。

## 6. 06.1 失败向量与验证

| 失败向量 | fail-closed 行为 | 证明它的测试（06.1 新增） |
| --- | --- | --- |
| **缺失活动代**（指针指向的目录/DB 不存在） | 不创建空库；`StartupError`（06.D 为 `RecoveryRequired`）；原指针不变 | owner 测试：预置一个指向不存在目录的活动指针 → 启动 → 断言 fail-closed 且**未**产生任何新库文件 |
| **损坏活动库**（非 SQLite 字节 / 打开失败） | 保留原文件与 sidecar；`StartupError`；不重建 | 复用 FOUND-001 注入形状（`AndroidStartupFailClosedInstrumentedTest.kt:82-100` 的注入 + 哈希断言），在代目录语境下重跑 |
| **不可读文件**（权限拒绝） | 保留原文件；`StartupError` | 复用 `AndroidStartupFailClosedInstrumentedTest.kt:120-151` 的权限机制形状 |
| **旧路径存在但尚未升级**（Android `databases/ledger.db` 存在、代际目录不存在） | **不**当空安装：按 §3.2 rule 1 的**复制 → 打开并读回 → 发布指针 → 再删旧**顺序升级；任一步失败则 fail-closed 且旧文件保留 | Android instrumented 测试：预置旧路径库 + `-wal`/`-shm` → 首次接入 → 断言新代目录含等价库、旧文件在指针发布前仍存在、账本事实读回一致；另注入 (a)→(c) 之间的崩溃 → 断言下次启动 fail-closed 而**非**空库 |
| **代际目录存在但指针缺失/无效**（rule 1 (a)→(c) 之间崩溃后的状态） | **fail-closed**（`StartupError`）；**绝不**回退为全新安装、绝不 bootstrap 种子（§3.2 rule 4） | owner 测试：预置一个存在的代际目录但无有效指针 → 启动 → 断言 fail-closed 且**未**创建任何空库、未写种子事实 |
| **journal 文件存在**（06.1 不产生 journal；存在即 06.D 时代状态或外部干预） | **fail-closed**（`StartupError`）；不忽略、不静默删除、不尝试解析/回滚（§5.1 步骤 2） | owner 测试：预置一个 journal 文件 → 启动 → 断言 fail-closed、journal 未被删除或改写 |
| **并发打开**（两个 owner/两次 `reopen` 同时尝试打开同一代） | 至多一个有效 graph；第二个 fail-closed（`RuntimeNotReady`/typed 拒绝），不产生第二个连接写入 | owner 测试：并发调用 `reopen`/`acquireLease` → 断言最多一个活动 graph、无第二个写入方；**注意** `AtomicFile` 不提供锁（容器规格 §5.3），故该断言须由 owner 自身的单活动约束承载（内部 `Mutex` + 原子状态字段，§4.2），而非文件锁 |
| **在飞业务未退出即 close/reopen** | `quiesce` 挂起至全部 lease 释放（或返回 `QuiesceBlocked`）；在飞 lease > 0 时 `closeActiveGraph`/`reopen` 返回 typed 拒绝，不提前 close | owner 测试：持有一个 lease → `quiesce()` → 断言不返回直到 lease.close()；持有 lease 时直接调用 `reopen()` → 断言返回 `QuiesceBlocked` 且旧 graph 未关；`QuiesceBlocked` 路径可确定复现 |

**回归面（06.1 必须继续通过，§1.4）**：`AndroidStartupControllerTest`、`AndroidStartupFailClosedInstrumentedTest`、`DesktopStartupControllerTest`、`DesktopCurrentSchemaReopenTest`、`DesktopCatalogMigrationTest`、`ForeignKeysCallbackCorruptionTest`。

## 7. 验收映射（P706-A03/A04/A06/A07/A10）

下表把容器规格 §7 归属到 06.1 的向量逐条映射。**本规格不把任何向量标为 PASS**——全部为未来验收要求，尚未执行（计划 `:194`）。

| 验收 ID | 计划要求（摘要） | 06.1 交付 | 留给后续切片 |
| --- | --- | --- | --- |
| P706-A03 | 错密码、认证失败、截断、伪格式、超限、未知未来/无版本库、异账本 → 类型化拒绝，当前库/活动指针均不变 | 仅「**当前库/活动指针不变**」的 owner 侧基础：缺失/损坏活动代 fail-closed、不静默建空库（§4.5、§6）；**拒绝码与容器格式判定不属 06.1** | 容器级类型化拒绝（06.C）；本规格不新增任何拒绝码 |
| P706-A04 | 支持集合内有账旧 schema 与迁移失败只改隔离副本 | **无**（隔离迁移属 06.C）；06.1 只保证旧路径升级**不**误用桌面宽松补戳路径（§3、容器规格 §5.4） | 支持集合、严格结构识别、隔离副本迁移（06.C） |
| P706-A06 | 每个持久状态边界掉电/杀进程及重启 → 完整旧代或新代可用、无半库、无静默空库、**最多一个有效 graph** | **06.1 贡献核心**：单活动 graph 约束、缺失/损坏活动代 fail-closed、正常重开回归、旧路径升级后重开可读回（§4、§5、§6） | 掉电注入下的持久切换与回滚（06.4，依赖容器规格 §5.3 的 journal） |
| P706-A07 | 恢复与导入/记账/读作业并发，旧回调晚到不污染新图 | **06.1 贡献前置**：operation lease + generation 捕获 + 旧回调按 generation 丢弃（§4.3、§4.4）；并发打开 fail-closed（§6） | 恢复期「拒绝新租约 + 等待既有读写退出」的端到端（06.4；`quiesce` 在 06.1 只做骨架） |
| P706-A10 | 满盘、SAF 写失败、取消、损坏活动库 → 不报成功；FOUND-001 保留旧库 | **部分**：损坏活动库 fail-closed 且保留旧库（§4.5、§6）；稳定存储解析失败 fail-closed（§3.4） | 满盘/SAF 写失败/取消属导出与恢复（06.B/06.D）；磁盘前置属容器规格 §4.8 |

**06.1 可支撑的**：A06 的单活动图与正常重开、A07 的 lease/generation 前置、A10 的损坏活动库 fail-closed 与稳定存储 fail-closed。**属后续切片的**：A03 的容器拒绝、A04 的隔离迁移、A06 的掉电持久切换、A07 的恢复期准入端到端、A10 的导出/恢复磁盘与取消路径。

## 8. 非目标与边界

- **不引入新依赖**：稳定存储解析与 owner/lease 均用平台与既有协程能力；不新增库（计划 `:153` 的端口「均待新增」指新增源码，不是新增依赖）。
- **schema 变更：06.1 不需要**。理由：generation、lease、活动指针、代目录都是**运行期/文件系统**状态，不是 DB 表；容器规格 §5.2 已冻结「每代是自己的 DB 与 sidecar」，代目录布局由实施批细化，均不要求 schema 边。故 06.1 **零 DDL、零迁移边，schema 停留 v31**（若实施批发现确需 schema 支持，必须另立决定与迁移门，不得在本规格范围内隐式加入）。
- **不触碰 `.external/`**；不写个人数据；tracked 文件不含本机绝对路径、临时研究或工具轨迹（`docs/CONTRIBUTING.md:165`）。
- **不重开容器格式**：不改容器格式规格的字节布局、KDF/AEAD 参数、拒绝码、资源上限。
- **不改既有决定**：不新增决定条目；不改 D-156/D-158/D-174 及 P7-01～P7-05 冻结面。
- **不实现**：06.B/06.C/06.D 的产品代码与测试；本文只给 06.1 的设计级规格。
- **不声称**：不声称云备份/设备迁移已关闭（§3.2）；不把任何 OPEN 门项当作已达成（§7）；不声称代目录字面布局已冻结（容器规格 §5.2）。

## 9. 边界断言与证据纪律

- 本文状态为 **proposal**（`docs/CONTRIBUTING.md:162` 允许分类之一），**已经独立评审第一轮（REJECT）与独立 verifier 复核，draft-2 已应用其全部意见，尚未经复审通过、未经批准**；本文不构成产品行为、迁移、技术选型或发布授权。
- 本文与已批准的容器格式规格（D-174）逐条一致：承接其 §5.1/§5.2/§5.3/§5.4/§5.5 与 §6 的 OPEN 项，**不重开**其任何冻结决定。
- 每项事实主张均带 file:line 证据；`local/artifacts/` 以主 checkout 为准（只读，不粘贴大段原文）。
- **明确标记为未验证/未取读数**的项：稳定存储的**具体平台 API 选择与目录字面布局**（归实施批）；代目录与 journal 的字面命名与布局（容器规格 §5.2 归实施批）；`LedgerRuntimeOwner` 的具体类型/包/超时值（本文只冻结语义）；06.D 的完整 journal prepared/switched/committed 语义与 `RecoveryRequired`（归 06.D）；并发打开断言**不依赖文件锁**（`AtomicFile` 无锁，容器规格 §5.3）。
- 本文不复制任何真实金额、时间、锚点注册值或个人数据；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动。