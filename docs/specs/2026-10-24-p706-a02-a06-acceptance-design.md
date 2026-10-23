# P706-A02/A06 验收取证设计规格（设计门方案先报）

状态：proposal（2026-10-24 起草于隔离 worktree（分支 `UL-p706-a02a06`，基点 = `main` `de86aa4d8`）。本文是 P7-06 验收向量 P706-A02（计划 `docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §6 行 `:182`）与 P706-A06（计划 `:186`）的**取证方案设计门先报**：只设计"如何取证"，不实施任何测试或产品代码，不产生任何证据。本文尚未批准；`docs/DECISIONS.md` 目前没有与之对应的决定条目（下一可用决定编号候选 D-246，**本文不占用、不分配**）；本文不分配 schema 版本（schema 维持 v35）。）

**Revision:** draft-2（2026-10-24）。闭合设计门独立评审的 1 项 medium（规格符合性维度：A06 边界枚举不完整）与质量维度 1 项 medium（A02 拓扑缺并发重叠取证护栏）及 6 项 low，全部为纯增补/更正，不改 A02/A06 的命题与已登记状态承诺：**（1）A06 边界枚举 15 → 19 行**——补升级指针发布前窗口（B11，`LedgerRuntimeOwner.kt:1043`/`:1051` 间，D-178 `:3492`/`:3508` 实证砖化窗口）、全新安装建代与发布（B14，`:1029-1032`）、`POINTER_MISSING` 采纳发布（B15，`BackupRestoreRecovery.kt:185`）与废弃重跑候选删除（B16，`:234`/`:240`），并相应重排编号（B12-B19）、更新 §3.4 矩阵、§3.2/§3.5 引用、§4 分母与 §5 成本；**（2）§2.2 新增步骤 6「并发重叠取证护栏」**（快照窗口前后各 ≥1 事务的不等式断言），§2.5/§6 项 5 同步；**（3）§3.3 限定 kill 主手段**（`Process.killProcess(Process.myPid())`；`am kill` 仅后台语义、须验证前置）；**（4）§3.3/§6 项 5 登记「同原语多点判别」机制缺口**（`deleteGenerationDirectory` 7 个调用点、`publishActivePointer` 服务 5 个边界行）；**（5）§1.3 补漂移 5**（D-239 :5082「`LedgerSwitchJournalTest` 七用例」vs 当前 9 用例）、§6 项 4 计数更新为五处字面；**（6）§6 项 5 补 CI 登记面**（`.github/workflows/ci.yml` 头注释类数，D-240 先例 `DECISIONS.md:5102`）；**（7）三处转引出处措辞中性化**（标记约定/§2.3/§3.1 末段，改为「转引自官方文档记载…待规格评审复核」，未改变任何事实的降级状态）。draft-1（2026-10-24）。初稿，单一草稿。tracked 行号均为基点 `de86aa4d8` 在本 worktree 的实读行号（worktree 与 `main` 该提交逐提交一致）；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 与 `docs/DECISIONS.md` 为主 checkout 的本地/正式文件，凡引用标注「计划」或「D-xxx」并以其主 checkout `main` `de86aa4d8` 实读行号为准。本文**不**修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不复制大段产品代码；不写本机绝对路径、个人数据或工具轨迹；示例全部匿名合成。

**标记约定：** 全文用【已验证事实】标注本次实读代码/文档所得的现状（行号均经本次逐项实读核验），用【冻结设计提案】标注本文建议、尚未批准、实施者不得当作既有授权的内容。外部官方文档事实按其可复核性分两档标注：本次起草时实际抓取核验的 sqlite.org 页面标注「官方文档，URL+节名（2026-10-24 抓取核验）」；无法在本次起草环境自行核验的条目标注「转引自官方文档记载（developer.android.com / AOSP javadoc 等），本次起草环境未能自行核验，待规格评审复核」。

## Scope

本文只覆盖两个验收向量的**取证方案设计**：

1. **P706-A02**（计划 `:182`：「WAL 下持续写入时导出；还原只出现某个自洽时点，无半笔/混合版本；主要证据=两端快照集成试验」）——设计一个以 WAL 模式打开数据库、在 `VACUUM INTO` 执行期间有并发写入者的集成试验拓扑，并论证两端（Android SQLiteDatabase / 桌面 JDBC）各自的 WAL 可达性与约束。
2. **P706-A06**（计划 `:186`：「每个持久状态边界掉电/杀进程及重启；完整旧代或新代可用；无半库、无静默空库、最多一个有效 graph」）——按代码实际命名枚举持久状态边界清单，拆分 JVM 合成注入与真机进程死亡各自覆盖的命题，并给出真机杀进程腿的环境裁决提案。

本文全部内容为【冻结设计提案】语义的设计；**零产品代码、零测试、零 schema、零 CI 配置、零 `DECISIONS.md` 改动**。实施、取证与验收裁决属后续独立实施批/验收批。

## 非目标（本文不授权）

- 不授权把 P706-A02 或 P706-A06 并入 PASS，也不把它们判 FAIL（D-239 `:5090` 的裁决——A02 缺证、A06 部分证据——保持不变）。
- 不授权产品切换 `journal_mode=WAL`（§2.4 明列为用户裁决项；D-239 现状与 D-174/D-177 既有规格均按「不依赖 WAL」冻结）。
- 不重开 D-174/D-176/D-177/D-179/D-182/D-183 的任何冻结裁决（含 OS 自动备份/设备迁移排除归隐私规格、§5.3 ROLLBACK 重启规则、`reopen(GenerationSelection.Explicit)` 路由修订）。
- 不改写计划 §10.6 `:451-452` 的 Q13/Q14 已批准推荐。
- 不实施任何测试：本文提出的测试文件、用例数、断言面全部是实施批的输入，不是已交付物。

## Authority And Boundary

- **计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`（主 checkout 实读）§6 `:182`/`:186`（A02/A06 原要求）、§10.1 `:381-400`（逐项证据纪律与状态表）、§10.2 `:402-408`（设备证据不可替代规则，尤其 `:407` 故障注入不能冒充进程死亡实测、替代验证须分开写明覆盖命题）、§10.3 `:410-418`（规模相关性）、§10.6 `:447-460`（Q13/Q14 已批准推荐，本文不改写）。
- **决定**（`docs/DECISIONS.md` 主 checkout 实读行号）：D-239 `:5064` 起（P7-06 验收裁决批；A02 缺证原文 `:5080`、A06 部分证据原文 `:5082`）；D-174 `:3397`（容器格式与 Q13 门，快照产物自洽、非裸复制，§5.3 journal 机器与 ROLLBACK 重启规则）、D-176 `:3436`（稳定存储与 owner 契约、静默空库禁令）、D-177 `:3459`（导出设计门；其评审承接明记「P3-2 A02 的 WAL 证据口径夸大」已修正）、D-179 `:3518`（预检设计门）、D-182 `:3589`（06.D 设计门七裁决）、D-183 `:3619`（06.D 实施登记、规格 §3 步骤 8 reopen 路由修订、`-journal` sidecar 纪律）。
- **既有规格**（tracked，本 worktree 实读）：`docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（§3 快照语义、§5.3 journal 机器、§7 A02/A06 行 `:306`/`:310`）；`docs/specs/2026-09-24-p7-06-backup-export-design.md`（`:71` Android 未配置 WAL 现状行、`:143` 受控连接一致性依据、`:282` A02 证据口径行）。
- **外部证据门**（AGENTS.md 规定的三件套已按门查阅，只读，本文只保留中立结论）：`docs/SOURCE_REFERENCES.md`；`.external/requirements/DISCOVERY_DECISION_LOG.md`（备份/恢复相关已确认方向：本地优先、无云同步；复用不照搬旧实现的备份经验）；`.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md`（反例必须写明零正式账务效果的纪律）。
- **官方文档**（中立契约，只引 URL+节名）：sqlite.org `wal.html`、`pragma.html`、`lang_vacuum.html`、`howtocorrupt.html`；AOSP `SQLiteDatabase` javadoc（转引，见 §2.3）。

## 1. 原要求映射与证据现状

### 1.1 P706-A02 原要求与 D-239 缺证裁决【已验证事实】

计划 `:182`：「P706-A02 | WAL 下持续写入时导出；还原只出现某个自洽时点，无半笔/混合版本 | 两端快照集成试验」。

D-239 第 4 条 A02 项（`:5080`）缺证原文（逐字引用）：

> **A02（缺证）**：**无任何测试以 WAL 模式打开数据库，也无任何测试在 `VACUUM INTO` 期间并发写入**（已 grep `journal_mode`/`=WAL`/`wal_mode` 于全部测试文件：零命中）。产品实际使用 `journal_mode=delete`（`AndroidLedgerDatabaseHandle.kt` 注释与规格均确认），故该向量的 WAL 前提在测试拓扑中未被行使。唯一相关证据是 ignored 的 `local/artifacts/p7-06-gate/q13-driver-snapshot-gate.md`（设备 `sqlite3` CLI 手动作业，非测试）。`BackupSnapshotDriverTest.vacuumIntoProducesASelfConsistentSnapshotThatVerifiesAsOk` 只证明单连接下产物自洽，`BackupExportUseCaseTest.theLeaseIsHeldAcrossTheSnapshotAndVerificationSteps` 只证明租约持有、无并发写者。

其中两条测试事实本次已复核仍然成立：`BackupSnapshotDriverTest` 现有 4 用例（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/BackupSnapshotDriverTest.kt:27,61,114,134`），首例仍为单连接产物自洽（`:27-58`）；`BackupExportUseCaseTest` 在 `app-ui/src/commonTest/kotlin/com/unifiedledger/ui/BackupExportUseCaseTest.kt` 存在。D-239 的 grep 字面已部分失效（§1.3 漂移 3），但「无任何测试以 WAL 模式打开数据库、无并发写者」的本体结论经本次独立 grep 复核仍成立（§2.1）。

### 1.2 P706-A06 原要求与 D-239 部分证据裁决【已验证事实】

计划 `:186`：「P706-A06 | 每个持久状态边界掉电/杀进程及重启 | 完整旧代或新代可用；无半库、无静默空库、最多一个有效 graph」。

D-239 第 4 条 A06 项（`:5082`）部分证据原文（逐字引用，节选收尾句）：

> **A06（部分证据）**：journal 三态恢复齐全——…（中间列举 `LedgerSwitchJournalTest` 七用例、`ConfirmBackupRestoreUseCaseTest` 提交/回滚链、`LedgerStableStorageTest` 四条 fail-closed、`LedgerRuntimeOwnerTest.concurrentAcquisitionsAndReopenProduceAtMostOneActiveGraph`、设备侧 `AndroidRestoreRecoveryInstrumentedTest` 六用例与 `AndroidStartupFailClosedInstrumentedTest` 三用例）…**缺口**：「每个持久状态边界掉电/杀进程」全部以**预设 journal 或注入抛异常**合成，**无任何测试真的杀进程或模拟掉电后重启**（已 grep `Process.killProcess`/`System.exit`/`Runtime.*halt` 于两个恢复仪器化测试：零命中）。

本次复核：`Process.killProcess`/`System.exit`/`Runtime.getRuntime().halt` 在 `android-app/src/androidTest/`、`app-ui/src`、`desktop-app/src` 全部零命中（grep 实证，2026-10-24）——缺口结论本体成立。`AndroidStartupFailClosedInstrumentedTest` 现为 **4** 用例而非三用例（§1.3 漂移 1）。

### 1.3 现状漂移登记（D-239 既有登记与当前树的字面差异）【已验证事实】

以下漂移如实登记；D-239 原文照引（§1.1/§1.2），现状锚点用本次实读行号。处置（是否以更正条目修订 D-239）归主代理裁决，本文不修改 `DECISIONS.md`。

1. **`AndroidStartupFailClosedInstrumentedTest` 用例数**：D-239 `:5082` 称「三用例」；当前树为 **4** 用例——`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidStartupFailClosedInstrumentedTest.kt:46`（normalOpen）、`:82`（corruptedFile）、`:103`（incompatibleSchema）、`:120`（unreadableFile）。
2. **`AndroidLedgerDatabaseHandle.kt` 的 journal_mode 注释**：D-239 `:5080` 称「`AndroidLedgerDatabaseHandle.kt` 注释与规格均确认 `journal_mode=delete`」；当前该文件已无 `journal_mode` 字样（grep 实证零命中），仅存伴生文件注释 `:258`（「the original file (and its -wal/-shm companions) stays in place」）。`journal_mode=delete` 的确认现只存在于导出规格 `:71` 的历史引用与 q13 门证据（ignored）中。
3. **D-239 的测试 grep 前提**：D-239 `:5080` 称 grep `journal_mode` 于全部测试文件零命中；当前树 `android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleOracle.kt:232` 存在一次 `rawQuery("PRAGMA journal_mode", null)` **只读诊断**（D-213 诊断面，注释 `:227-229` 明示「record the journal mode this observation connection sees」）。它是读模式、不是设置 WAL，A02 缺证结论不受影响；但「零命中」字面已失效。
4. **导出规格的历史行号**：导出规格 `:71` 引 `AndroidLedgerDatabaseHandle.kt:139-148`（onConfigure 只设 FK）；当前该段在 `:242-251`。属该已批准规格的历史行号漂移，非本文范围。
5. **`LedgerSwitchJournalTest` 用例数**：D-239 `:5082` 称「`LedgerSwitchJournalTest` 七用例」；当前树为 **9** 用例（`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/LedgerSwitchJournalTest.kt:67,:88,:107,:125,:145,:170,:189,:206,:224`）——「七用例」是语义归并列举而非计数错误：其中「发布失败与 journal 删除失败均保持 fail-closed」一句对应两个独立用例（`:189`/`:206`），D-239 行文把二者并为一条。性质与漂移 1 同类，归 §6 项 4 的 D-239 更正条目一并裁决；不影响 §3.2 按名复核的正确性。

## 2. A02 设计面：WAL 并发写入下的导出取证

### 2.1 打开路径与 journal 模式现状（两端）【已验证事实】

**Android。** 产品唯一收敛点是 `createAndroidLedgerDatabase`（`ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:10`）：`AndroidSqliteDriver` 构造 `:16-22`（唯一 callback = `ForeignKeysCallback`），其后 `:30-37` 一次只读 eager probe 强制真实打开。`ForeignKeysCallback.onConfigure`（`:242-251`）只调 `setForeignKeyConstraintsEnabled(true)`；`:245-250` 注释明文记录 PRAGMA 结果行限制：busy_timeout 之类的**返回结果行的 PRAGMA 不能走 execSQL/execute**（`SQLiteSession.executeForChangedRowCount` 拒绝，设备实测先例）。产品 main/androidMain 源码**零** `journal_mode`/WAL 设置（grep 实证：全部产品源仅 `ParseManualExpenseOccurredAt.kt` 的 `WALL_CLOCK_FORMAT` 正则字面命中，无关）。故 Android 产品库落在引擎默认 journal 模式（设备侧 q13 门实测为 `journal_mode=delete`，转引自容器规格 `:91` 引用的 ignored 门证据）。

**桌面。** 主连接由 `Main.kt:1216` 的裸 `JdbcSqliteDriver(databaseUrl)` 单参构造打开；产品侧 PRAGMA 的唯一汇聚点是 `configureSqliteConnection`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightConfirmedManualExpenseCommitPort.kt:273-280`），只设 `foreign_keys = ON` 与 `busy_timeout`，由各 store/commit port 工厂调用。两条**旁路裸连接**不经该汇聚点：快照验证连接 `DesktopBackupSnapshotPort.kt:33`（`JdbcSqliteDriver("jdbc:sqlite:$snapshotPath")`）与恢复预检/迁移隔离连接 `DesktopRestorePreflightPorts.kt:167`（`JdbcSqliteDriver("jdbc:sqlite:$path")`）。

**快照链。** `VACUUM INTO` 在活动连接上执行：`runSnapshotIntoOn`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/BackupSnapshotDriver.kt:29-39`），经 Android 受控入口 `AndroidLedgerDatabaseHandle.runSnapshotInto`（`:152-154`）与桌面 `DesktopBackupSnapshotPort.snapshot`（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopBackupSnapshotPort.kt:28-30`）接线。容器规格 `:91` 冻结快照语义：快照为**独立自洽文件**（q13 门实测快照 `journal_mode=delete`）、**不是**活动主文件的裸复制。

**测试面现状。** 全部测试文件（host + instrumented）中设置 `journal_mode=WAL` 的为零（grep 实证）；唯一 `journal_mode` 触点即 §1.3 漂移 3 的只读诊断。D-174 Q13 门（D-174 `:3407`）曾以设备 `sqlite3` CLI 在 WAL 下实证 `VACUUM INTO` 自洽（Android 侧），导出规格 `:282` 明记该证据口径（「WAL 一致性子 claim 只在 Android 侧实证…桌面门用的是非 WAL 的 4 交易库」）——两端的 **WAL 并发集成**均无测试。

### 2.2 JVM 集成试验拓扑【冻结设计提案】

**模块与文件建议**：新增 1 个测试类置于 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/`（与既有 `BackupSnapshotDriverTest` 同模块同目录，复用其 temp-dir 夹具形状 `:18-24`）。**零构建面变更**：该模块 jvmTest 已依赖 `app.cash.sqldelight:sqlite-driver:2.3.2`（`ledger-data/build.gradle.kts:65-67`），本拓扑不需要任何 Gradle/依赖改动——这是「不触碰构建面」的直接论证。

**拓扑（单测试进程内两连接 + 一写者线程）**：

1. **源库与 WAL 前提**：`JdbcSqliteDriver("jdbc:sqlite:<tmp>/ledger.db")` 建源库（表形状任意匿名合成，例如双列 `pair(id INTEGER PRIMARY KEY, pair_member INTEGER)` 与 `version_marker` 单行表）；以 `executeQuery` 发 `PRAGMA journal_mode = WAL` 并**读回返回值断言等于 `wal`**——sqlite.org `pragma.html` journal_mode 节（官方文档，URL+节名，2026-10-24 抓取核验）：「The new journal mode is returned. If the journal mode could not be changed, the original journal mode is returned」，即切换失败**静默返旧值**，不断言返回值就不能声称 WAL 前提成立。切换前置条件「无活动事务」同节明文。
2. **并发写者**：第二个连接（同进程另开一个 `JdbcSqliteDriver` 指向同一文件，模拟同机多连接；WAL 单写者上限由 sqlite.org `wal.html` §2.2「there can only be one writer at a time」约束，单写者线程恰好贴齐）在循环里逐事务提交**成对行**（同一事务写两行 + 同步翻动 `version_marker` 单行），使"半笔"与"混合版本"成为**可判定**的违反形态：任何自洽时点快照内，成对行要么都在要么都不在，`version_marker` 在快照内全程单值。
3. **被试动作**：写者运行期间，主连接调 `runSnapshotIntoOn`（`BackupSnapshotDriver.kt:29-39`）产出快照；随后按既有形状在快照文件上开验证连接调 `verifySnapshotOn`（`:69-85`）。
4. **断言面（对应计划 `:182` 逐项）**：
   - **还原只出现某个自洽时点**：快照内 `version_marker` 单值、成对行完整对出现（两行都在或都不在）；
   - **无半笔**：不存在孤行（成对表内行数恒为偶且 id 连续成对）；
   - **无混合版本**：全部行携带的版本戳与快照内 `version_marker` 值一致（写者每事务同版本戳写入，跨事务递增）；
   - **产物可被独立验证**：`verifySnapshotOn` 报 `integrityOk`；快照文件可被第三个全新连接完整打开并读尽（还原等价的最小行使）。
   - 断言的时点语义依据：sqlite.org `wal.html` §2.2（官方文档，2026-10-24 抓取核验）——单个读事务看到「a single point in time」的一致内容；`lang_vacuum.html` §2.1——「the generated output database is a consistent snapshot of the original database」。
5. **稳定性护栏**：写者线程须有界（有限迭代或取消位），测试结尾 join 并关闭全部连接。
6. **并发重叠取证护栏**：上述自洽性断言成立不要求写者与快照窗口重叠（无同步点），但**该向量的证据必须证明重叠真实发生**，否则运行可退化为既有单连接场景（`BackupSnapshotDriverTest.vacuumIntoProducesASelfConsistentSnapshotThatVerifiesAsOk:27-58`）而对「WAL 下持续写入时导出」空洞通过——与 D-177 已修正的「P3-2 A02 的 WAL 证据口径夸大」（`DECISIONS.md:3475`）属同类口径风险。设计冻结的重叠证明断言：写者在 `VACUUM INTO` 发起前已提交 ≥1 个事务（其版本戳记为 v_mid），且写者在快照验证连接打开后仍成功提交 ≥1 个事务（终值记为 v_end）；断言 `快照内 version_marker 值 ≥ v_mid` 且 `v_end > 快照内 version_marker 值`。两个不等式合取即证明：快照窗口内有已提交写入进入产物、且窗口后仍有写入被排除在产物之外——写者若先于快照结束、或晚于快照才开始提交，重叠断言必 FAIL。该重叠证明是实施规格的**冻结要求**；其精确测量实现（version_marker 读回对比、写者事务日志形状）归实施批（§6 项 5），不得被「相对次序不影响断言成立性」的表述吸收。

**集成腿边界**：本拓扑在驱动层取证（`VACUUM INTO` + 并发写者 + 验证连接），与既有 `BackupSnapshotDriverTest` 同层；完整「容器加密 → 预检 → 确认切换」用例级往返已由 D-239 第 2 条列举的 app-ui/data 用例族覆盖（非 WAL 前提），A02 的新增命题只在 WAL 前提与并发写者，不重复容器/预检状态机断言。

### 2.3 两端 WAL 可达性差异与 Android 约束【已验证事实 + 转引】

- **桌面 JDBC**：xerial 驱动连接上 `PRAGMA journal_mode=WAL` 经 `executeQuery`（返回行）可达；测试侧即可建立 WAL 前提（§2.2 步骤 1）。产品侧若裁决切换（§2.4），汇聚点 `configureSqliteConnection` 是单点接线处，但 `DesktopBackupSnapshotPort.kt:33` 与 `DesktopRestorePreflightPorts.kt:167` 两条旁路裸连接不经汇聚点——WAL 持久化在库上后旁路连接打开 WAL 库会**跟随库的持久模式**（sqlite.org `pragma.html` journal_mode 节：「The WAL journaling mode is persistent; after being set it stays in effect across multiple database connections and after closing and reopening the database」，官方文档，2026-10-24 抓取核验），故旁路连接不需逐点改写，但其打开行为应在实施批重新验证。
- **Android SQLiteDatabase**：返回行的 PRAGMA 只能走 `rawQuery` 形路径，不能走 `execSQL`/`execute`（`AndroidLedgerDatabaseHandle.kt:245-250` 的 busy_timeout 设备实测先例；`runQueryStatisticsOptimize` KDoc `:113-123` 同约束）。框架层另有 `enableWriteAheadLogging` API，但其与手工 `PRAGMA journal_mode` 互斥、要求调用时无活动事务、且对只读/内存/attach 库拒绝——该三条为**转引自官方文档记载（AOSP `SQLiteDatabase` javadoc；developer.android.com，本次起草环境网络不可达未能自行核验），待规格评审复核**。产品当前两者皆未使用（grep 实证），故产品 Android 库现状不可达 WAL。
- **设备侧取证的现实出口**：q13 门已以设备 `sqlite3` CLI 在 WAL 下实证过 Android `VACUUM INTO` 自洽（D-174 `:3407`、导出规格 `:282`）。若要求**测试形态**的 Android WAL 集成腿，instrumented 测试须以 `SQLiteDatabase.openDatabase` + `rawQuery("PRAGMA journal_mode=WAL")` 自行建立 WAL 前提（产品连接不受影响）——是否需要该腿列为开放项（§6 项 3），本文不默认需要。

### 2.4 「产品是否切换 journal_mode=WAL」为显式裁决项【冻结设计提案】

本规格**不授权实施切换**；该裁决独立于 A02 取证是否可行——A02 的 JVM 拓扑（§2.2）由测试自身建立 WAL 前提，不依赖产品切换。裁决权衡（全部锚点已验证或已标注出处）：

- **收益面**：并发读（`wal.html` §2.2）。但产品 owner 契约是单一受控连接 + 单活动图（D-176；受控连接上语句串行执行的论证见导出规格 `:143`，`DesktopBackupSnapshotPort` KDoc `:20-24`），并发读收益在现架构下有限；A02 的并发写者本身在受控连接之外，产品现状（delete 模式）下该向量甚至不可行——这正是 D-239 判缺证的原因，测试拓扑以 WAL 打开即可覆盖，无需产品切换。
- **代价面**：
  1. **checkpoint 时机**：默认 WAL 达 1000 页自动 checkpoint、最后连接关闭时 checkpoint 并清理（`wal.html` §2.1/§3.1/§4/§6，官方文档，2026-10-24 抓取核验）——长寿命单连接（产品形态）意味着 checkpoint 主要发生在连接关闭时，`-wal` 文件在运行期持续增长的行为需要实测评估。
  2. **sidecar 影响面**：`-wal`/`-shm` 伴生文件进入备份容器/恢复切换/稳定存储目录。现状**成组面已兼容**：复制清单 `LEDGER_SIDECAR_SUFFIXES = ["-wal","-shm"]`（`LedgerStableStorage.kt:56`）已用于 `stageLegacyUpgrade`（`:538-543`）与确认切换的 `stageNewGeneration`（`ConfirmBackupRestore.kt:637-642`），fsync 序列覆盖 sidecar（`:548-555`/`:643-650`）；删除集用超集 `LEDGER_DELETABLE_SIDECAR_SUFFIXES`（`:71`，D-183 缺陷 2 教训）；`AndroidLedgerDatabaseHandle.kt:258` 注释已按伴生文件表述。即 sidecar 成组纪律不构成切换阻塞，但切换后这些路径在真实 WAL 文件下的行为（尤其 `-wal` 中有未 checkpoint 页时复制代际的时点语义）须重新取证。
  3. **快照产物不变**：`VACUUM INTO` 产物为独立自洽文件（容器规格 `:91`，q13 门实测快照 `journal_mode=delete`），切换不改变容器格式与预检读侧。
  4. **A06 边界语义**：WAL 持久化入库（跨连接持久，`pragma.html` journal_mode 节）使「代目录三文件一致集」的时点语义从 delete 模式的单文件变为「主文件 + 可能非空的 `-wal`」组合；D-176 的非破坏式升级序列与 D-182 的 journal 机器本身按 sidecar 成组设计（兼容），但每个 §3 边界矩阵行的掉电形态都要按 WAL 形态重推——切换与否直接改变 A06 实施批的取证矩阵。
  5. **官方损坏警告**：反复 delete↔wal 往返切换曾被记录为另一进程以 stale cache 写坏库的来源（sqlite.org `howtocorrupt.html` §8.4，官方文档，2026-10-24 抓取核验；该缺陷 2011 年起已修复，教训是避免反复往返）。产品若切换应单向迁移，不设计运行期往返。
  6. **官方安全备份手段对照**：`howtocorrupt.html` §1.2 列举对活动库做副本的三种官方安全手段（sqlite3_rsync / `VACUUM INTO` / backup API）——产品已选 `VACUUM INTO`（D-174 冻结），本项不重开。

**建议默认答案**（供裁决，非本文授权）：产品**保持现状不切换**；A02 以测试拓扑建立 WAL 前提取证。理由：A02 向量考察的是「导出机制在 WAL 前提下的正确性」，机制（受控连接上的 `VACUUM INTO`）两端已冻结且 q13 门在 WAL 下实测过自洽；产品切换的成本（A06 矩阵重推、checkpoint 行为实测、sidecar 语义重验）远超单活动图架构下的并发读收益。

### 2.5 A02 向量映射与证据纪律（§10.1）

| 向量 | 原要求（计划 `:182` 分解） | 设计取证手段 | 现有覆盖 | 拟新增 | 状态承诺 |
| --- | --- | --- | --- | --- | --- |
| A02-a1 | WAL 前提下持续写入时导出（桌面） | §2.2 拓扑：测试自建 WAL + 写者线程 + `runSnapshotIntoOn` + 步骤 6 并发重叠取证护栏（窗口前后各 ≥1 事务的不等式断言） | 无（D-239 `:5080` 缺证；唯一 q13 门为 CLI 作业非测试） | 1 测试类（`ledger-data` jvmTest） | 证据仍不判 PASS：本规格只设计取证方案，不产生证据 |
| A02-a2 | WAL 前提下持续写入时导出（Android） | 待裁决：instrumented WAL 探针（§2.3）或以 q13 门 CLI 证据 + JVM 腿承接 | q13 门 CLI 证据（非测试、ignored） | 0 或 1 instrumented 用例（§6 项 3 裁决） | 证据仍不判 PASS |
| A02-a3 | 还原只出现某个自洽时点；无半笔 | §2.2 断言面：成对行完整性 + 版本戳单值 + `integrity_check` | 无 | 并入 A02-a1 用例断言 | 证据仍不判 PASS |
| A02-a4 | 无混合版本 | §2.2 断言面：版本戳与 `version_marker` 一致 | 无 | 并入 A02-a1 用例断言 | 证据仍不判 PASS |
| A02-a5 | 两端快照集成试验（导出→验证→还原行使） | A02-a1 含还原行使（第三个全新连接读尽）；Android 集成随 A02-a2 裁决 | 部分祖先证据：`BackupSnapshotDriverTest` 非 WAL 自洽（`:27-58`） | 并入 A02-a1/A02-a2 | 证据仍不判 PASS |

## 3. A06 设计面：持久状态边界的进程死亡/掉电取证

### 3.1 持久状态边界清单（代码实际命名，19 边界行）【已验证事实】

A06 原要求「每个持久状态边界」按当前代码实读枚举为 **19** 个边界行，其中 **16** 个是磁盘持久状态转变点（落盘动作）、**3** 个是紧邻转变点之间的崩溃窗口/恢复流程行（B7/B9/B11——它们自身不新增落盘调用，但进程在该窗口死亡时的磁盘形态与相邻落盘行不同、恢复命题不同，故单列成行，不与相邻行合并）。边界行分四组。命名全部来自代码符号（不使用示意名）：

**切换链（confirm & switch，`ConfirmBackupRestore.kt`）**

- **B1 delete-then-stage 前置删除**：`stageNewGeneration` 内 `deleteGenerationDirectory(fileSystem, layout, newGeneration)`（`ConfirmBackupRestore.kt:633-634`；被删函数 `LedgerStableStorage.kt:710-736`）。
- **B2 新代主文件复制中**：`fileSystem.copy(sourceFile, mainFile)`（`:636`）——非原子流复制，掉电留半写 `gen-(n+1)/ledger.db`。
- **B3 sidecar 复制 + fsync 序列 + 本地门**：`LEDGER_SIDECAR_SUFFIXES` 复制（`:637-642`）、主文件与 sidecar 逐个 `fsyncFile` + `fsyncDirectory`（`:643-650`）、`isUsableSqliteMainFile` 门与空间复查（`:651-675`）。
- **B4 journal=prepared 落盘**：`writeJournal(LedgerSwitchJournalStage.Prepared, …)`（`:429-441` 调用点；`writeJournal` `:688-696`：`writeAtomic` + 文件 fsync + host 目录 fsync）。
- **B5 指针发布**：`publishActivePointer(fileSystem, layout, newGeneration)`（`:446-448` 调用点；原语 `LedgerStableStorage.kt:562-570`：`writeAtomic` + fsync + host 目录 fsync）。
- **B6 journal=switched 落盘**：`writeJournal(LedgerSwitchJournalStage.Switched, …)`（`:449`）。
- **B7 步骤 8 显式重开读回（崩溃窗口行）**：`reopenOldGraphWithRetry(GenerationSelection.Explicit(newGeneration))`（`:471`）——本行无新落盘，但此处进程死亡留下「新指针 + `switched` journal」，是重启回滚（ROLLBACK）的教科书窗口（D-183 `:3644` 步骤 8 路由修订的背景）。
- **B8 journal 删除 = committed**：`removeSwitchJournalDurably()`（`:480-492` 调用点；原语 `:703-706`：delete + host 目录 fsync）。journal 删除事实即「committed」（容器规格 §5.3 `prepared → switched → committed` 机器；`LedgerStableStorage.kt:625-635` 注释段）。
- **B9 提交后 escape repair（流程行）**：受保护区外的 `catch (failure: Throwable)` 修复路径（`:493-494` 起；`ConfirmBackupRestore.kt:708` 起 KDoc）。

**旧路径升级与全新安装（`LedgerRuntimeOwner.kt` 组合根 + `LedgerStableStorage.kt` 原语）**

- **B10 stageLegacyUpgrade 复制 + fsync**：`LedgerRuntimeOwner.kt:1043` 调用；主文件与 sidecar 复制、逐文件 fsync、目录 fsync（`LedgerStableStorage.kt:530-556`）。
- **B11 升级指针发布前窗口（崩溃窗口行）**：复制+fsync 已完成（`LedgerRuntimeOwner.kt:1043`）与指针发布（`:1051`）之间的进程死亡——留下「有代目录、无指针」形态，下次启动 `POINTER_MISSING` fail-closed（`LedgerStableStorage.kt:449`），恢复归 06.D 的用户确认采纳/废弃重跑（D-178 `:3492` 设备实证的 ANR 部分复制 + 无指针代目录、`:3508` 残余 (a)「`POINTER_MISSING` 砖化窗口…承接至 06.D」；D-182 裁决 C `:3603` 的设计级承接面）。该窗口是 D-178 实测真实砖化用户的窗口，A06-b1 kill 腿矩阵必须有对应行。
- **B12 升级指针发布**：`LedgerRuntimeOwner.kt:1051` 调用 `publishActivePointer`（原语 `LedgerStableStorage.kt:562-570`，升级路径与切换/恢复共用同一冻结原语）。
- **B13 removeLegacyFiles 旧文件删除**：`LedgerRuntimeOwner.kt:1052` 调用；主文件 + 可删除 sidecar 超集（`LedgerStableStorage.kt:578-591`）。
- **B14 全新安装建代与发布指针**：`LedgerRuntimeOwner.kt:1029` `createDirectories` → `:1030` create-on-open 打开 → `:1032` `publishActivePointer(…, 1)`（注释 `:1026-1028`：唯一允许 create-on-open 的路径，指针在权威读回后才发布）。发布前 kill 留下无指针 `gen-1` 且**无 legacy 原件**，废弃重跑分支不可用（`BackupRestoreRecovery.kt:213-218` 的类型化 `LegacyOriginalMissing`）——该向量同时钉住 D-176 静默空库禁令在全新安装半途崩溃下的行为。

**POINTER_MISSING 恢复（用户确认动作，`BackupRestoreRecovery.kt`）**

- **B15 采纳发布**：KDoc `:174-178`（「re-verify…publish the HIGHEST passing generation through the frozen `publishActivePointer` primitive」）；`publishActivePointer(fileSystem, layout, target)`（`:185`）——磁盘形态从「无指针 fail-closed」转变为「指针指向已验证代」（D-182 裁决 C `:3603` 的 `POINTER_MISSING` 显式用户确认恢复，`DECISIONS.md:3603`）。
- **B16 废弃重跑候选删除**：KDoc `:196-204`；候选代目录删除 `:234`（`deleteGenerationDirectory`，strict 语义注释 `:240`）+ 清空后 generations 目录自身删除——下一个正常启动必须重新解析 `UpgradeLegacy`（`:196-204`；D-183 缺陷 2 的 strict 删除纪律即为本行服务）。

**启动恢复（重启执行者，`LedgerStableStorage.kt`）**

- **B17 recoverSwitchJournalAtStartup 旧指针重发布**：`publishActivePointer(… journal.oldGeneration)`（`:780-805`，`switched` 或指针已移时无条件重发布旧指针）。
- **B18 journal 删除 + host 目录 fsync**（`:806-816`）。
- **B19 新代目录删除**：`runCatching { deleteGenerationDirectory(…) }`（`:817`；严格删除 `:710-736`）。

**平台介质语义**（矩阵掉电形态的事实底座）【已验证事实】：Android 原子替换走 `AtomicFile`（`AndroidLedgerFileSystem.kt:51-69`：`startWrite`/`finishWrite`/`failWrite`）；进程死于 `startWrite` 与 finish/fail 之间可留 `.new` 残影文件（AOSP `AtomicFile` 行为，转引自官方文档记载，本次起草环境未能自行核验，待规格评审复核）；**Android 无目录 fsync 原语**，`fsyncDirectory` 为 best-effort 空实现（`:93-98`，类注释 `:21-23`）。桌面为临时文件 + `Files.move(…, ATOMIC_MOVE)`（`DesktopLedgerFileSystem.kt:47,60-63`），目录 fsync 在 Windows 不可移植、best-effort（`:96-97`）。

### 3.2 覆盖现状与命题拆分（§10.2 纪律）【已验证事实 + 冻结设计提案】

**JVM 合成注入继续覆盖的命题（现状保持，不冒充设备腿）**：每个边界的**恢复语义**——即给定边界后的磁盘形态，启动门/恢复函数是否按冻结 ROLLBACK 规则产出「完整旧代或新代、无半库、无静默空库」。现状覆盖（D-239 `:5082` 列举，本次按名复核）：

- `LedgerSwitchJournalTest`（`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/LedgerSwitchJournalTest.kt`，9 用例：`:67,:88,:107,:125,:145,:170,:189,:206,:224`）——journal 编码、半建代无 journal 惰性、prepared+旧指针丢弃暂存、prepared+指针已移回滚、switched 回滚、暂存删除失败不阻断、发布/删除失败保持 fail-closed、不可识别 journal 永不消费。
- `LedgerStableStorageTest`（同目录 `LedgerStableStorageTest.kt`）——启动门 fail-closed 族（`:37,:51,:64,:78,:95,:150,:167`）、升级序列与失败保留（`:321,:343,:357,:399,:411,:469,:541`）、staging sweep（`:561,:580,:599,:626`）。
- `LedgerRuntimeOwnerTest.concurrentAcquisitionsAndReopenProduceAtMostOneActiveGraph`（`LedgerRuntimeOwnerTest.kt:489`）。
- `ConfirmBackupRestoreUseCaseTest` 的提交/回滚链与 `aRollbackReopenFailureIsTheFailClosedRecoveryRequiredWithoutLooping`（`ConfirmBackupRestoreUseCaseTest.kt`）。
- `BackupRestoreRecoveryTest`（`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/BackupRestoreRecoveryTest.kt`，15 用例，与 D-239 `:5072` 计数一致）——`POINTER_MISSING` 采纳/废弃分支的**恢复语义**：采纳最高可验证代（`:102`/`:122`）、错误版本/完整性失败候选不采纳（`:139`/`:162`）、废弃删除含 journal sidecar 且清空目录存活即类型化失败（`:175`/`:197`）、无 legacy 原件类型化不可用（`:214`）、出现可采纳候选时先拒废弃（`:229`）、外物拒废弃（`:240`）、状态漂移拒绝（`:252`）、probe 零动作与 byte-identical（`:300`/`:314`/`:341`）；无指针既有代的启动门 fail-closed 语义另见 `LedgerStableStorageTest:37`。

**真机腿新增覆盖的命题（合成腿不可替代，§10.2 `:407`）**：**进程死亡实测本身**——上述 JVM 腿的磁盘形态是测试**预设/注入**的合成终态；真机腿要证明的是：真实 Android 进程在边界操作**进行中或刚落盘未及后续步骤时**被 OS 杀死，落盘产物恰是**代码在该窗口真实写出的字节**（含 `AtomicFile` `.new` 残影、无目录 fsync 下的重排风险等平台介质语义，§3.1 末段），且下次真实启动序列经 `resolveLedgerStorage`（`LedgerStableStorage.kt:414-471`）收敛到「完整旧代或新代可用」。**掉电实测**与**进程死亡实测**是两个命题：受管 AVD 上进程死亡可实测（`Process.killProcess`/`am kill`），真实掉电（存储控制器丢页序）在受管 AVD 上**不可实测**——该命题单列为未覆盖腿（§4），是否以进程死亡作为掉电的代理并显式登记命题差异，归用户裁决（§6 项 2）。

**真机腿不新增的行及理由**：无——19 边界行每行都提拟新增真机腿（§3.4），但 B17-B19 的真机腿验证的是「恢复执行者自身被杀后再次重启」的收敛性（restart-of-recovery 幂等）、B15/B16 验证的是「用户确认动作进行中被杀」的收敛性，与 B1-B14 的「操作中被杀」是不同断言，全部保留。B11（升级指针发布前窗口）的 kill 腿正是 D-178 `:3508` 残余 (a) 承接给 06.D 的实证窗口，不得从矩阵省略。

### 3.3 真机腿拓扑提案：边界驻停 + 两阶段 kill 协议【冻结设计提案】

**环境**：受管 AVD + instrumented 测试（沿 D-183 设备门协议：隔离 adb 端口、测试自带重定向目录、绝不触碰生产库；设备门由主代理执行，写者不执行设备门）。**零产品代码**的前提：`ConfirmBackupRestoreUseCase` 以 `LedgerFileSystem` 接口注入文件原语（构造参数 `fileSystem: LedgerFileSystem`，`ConfirmBackupRestore.kt:279`），测试可用**测试侧装饰器**包裹真实平台 `LedgerFileSystem`，在指定边界的落盘调用后驻停（latch），从而把进程精确停到「边界 N 已落盘、边界 N+1 未开始」的窗口。**同原语多点判别缺口（如实登记）**：`deleteGenerationDirectory` 在本链路有 7 个调用点——`ConfirmBackupRestore.kt:426`（B3 后失败清扫）、`:439`（B4 prepared 写失败清扫）、`:634`（B1 前置删除）、`:743`（回滚清扫）、`:789`（B9 escape 清扫），另 `BackupRestoreRecovery.kt:234`（B16）与 `LedgerStableStorage.kt:817`（B19）；`publishActivePointer` 同时服务 5 个边界行——B5（`ConfirmBackupRestore.kt:448`）、B12（`LedgerRuntimeOwner.kt:1051`）、B14（`:1032`）、B15（`BackupRestoreRecovery.kt:185`）与 B17（`LedgerStableStorage.kt:799`）。装饰器驻停**必须按调用序号与调用上下文判别目标边界**，不得按单一调用点驻停——按单点驻停会停错窗口、产出与目标边界不符的磁盘形态；该判别机制归实施规格（§6 项 5）。B7 无落盘调用，改为驻停重开期间或以「B6 驻停后放行至重开挂起」实现；B11 的驻停点在 `stageLegacyUpgrade` 返回后、`publishActivePointer` 调用前的组合根序列上（`LedgerRuntimeOwner.kt:1043`/`:1051`）。

**两阶段协议**（单条 instrumented 运行无法自杀后自证，进程死亡连带 instrumentation 终止）：

1. **kill 腿（run A）**：测试驱动真实用例走到目标边界驻停点 → 先把「目标边界号 + 夹具账本指纹」写入测试自有的 marker 文件 → 解除驻停让下一个落盘调用**不发生**（或直接在驻停中）→ `Process.killProcess(Process.myPid())`。**kill 手段限定**：主手段即 `Process.killProcess(Process.myPid())`；shell `am kill` 只终止「可安全关闭的后台进程」，对前台 instrumented 进程不保证生效——实施批若要启用它必须先验证该运行形态下的生效前置，否则一律回退主手段，不得以未验证的 `am kill` 充当 kill 证据。进程死亡本身即被试事件。
2. **verify 腿（run B，同 APK 下一次 instrumentation 启动）**：读 marker 确认前次目标边界 → 跑真实启动序列（`resolveLedgerStorage` → `openStableStorageLedger`）→ 断言 §3.5 断言面 + 权威读回（账本事实、指针指向、journal 消费状态、无半库/静默空库、owner 单图）。
3. CI/设备门把 19 边界行编排为 run A/run B 成对执行（可按边界分组合并用例以控时长，分组不合并断言）。

**边界情况**：B10-B13（旧路径升级）真机腿用 legacy 布局夹具（同 `AndroidRestoreRecoveryInstrumentedTest.aMidCopyBrickedCandidateIsDiscardedAndTheLegacyOriginalIsReStaged` 的布置形状，`AndroidRestoreRecoveryInstrumentedTest.kt:191`）叠加驻停；B14 用全新安装布局夹具叠加驻停；B15/B16 用无指针候选代布局夹具（`BackupRestoreRecoveryTest` 的布置形状）叠加驻停（用户确认动作中途被杀）；B17-B19 用预设 journal 夹具叠加驻停（恢复执行者中途被杀）。

### 3.4 逐边界矩阵【冻结设计提案】

| 边界 | 落盘动作（file:line） | 现有覆盖腿 | 拟新增真机腿 |
| --- | --- | --- | --- |
| B1 前置删除 | `ConfirmBackupRestore.kt:633-634` → `LedgerStableStorage.kt:710-736` | JVM：`LedgerStableStorageTest`（journal sidecar 随删、目录存活即抛 `:264,:280`）；`BackupRestoreRecoveryTest` 目录删除族 | run A/B：删除中途 kill → 半删目录/主文件缺失下的启动收敛 |
| B2 主文件复制中 | `ConfirmBackupRestore.kt:636` | JVM：`LedgerSwitchJournalTest.aHalfBuiltGenerationWithoutAJournalIsInertAtStartup:88`（预设半建代）；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:191`（升级侧半复制） | run A/B：复制中 kill → 无 journal 半建代惰性 + 旧代完好 |
| B3 sidecar+fsync+门 | `ConfirmBackupRestore.kt:637-651` | JVM：`LedgerStableStorageTest:321`（sidecar 成组） | run A/B：sidecar 已复制未 fsync 完成即 kill |
| B4 journal=prepared | `ConfirmBackupRestore.kt:429-441`/`:688-696` | JVM：`LedgerSwitchJournalTest:107,:125`；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:156`（switched 预设） | run A/B：prepared 落盘后 kill → 启动丢弃暂存、旧代打开 |
| B5 指针发布 | `ConfirmBackupRestore.kt:446-448` → `LedgerStableStorage.kt:562-570` | JVM：`LedgerSwitchJournalTest:125`（prepared+指针已移）；`ConfirmBackupRestoreUseCaseTest` 回滚链 | run A/B：指针已切、journal 仍 prepared 时 kill → 启动回滚旧代 |
| B6 journal=switched | `ConfirmBackupRestore.kt:449` | JVM：`LedgerSwitchJournalTest:145`；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:156` | run A/B：switched 落盘后 kill → 启动回滚（ROLLBACK 冻结规则） |
| B7 步骤 8 重开读回（窗口行） | `ConfirmBackupRestore.kt:471`（无新落盘） | JVM：`ConfirmBackupRestoreUseCaseTest.theHappyPathCommitsWhenTheReopenRunsTheRealStartupJournalGate`；真机：`AndroidRestoreConfirmSwitchInstrumentedTest:119`（happy path，无 kill） | run A/B：switched 窗口 + 新指针驻停重开期间 kill → 启动回滚且旧图可用 |
| B8 journal 删除=committed | `ConfirmBackupRestore.kt:480-492`/`:703-706` | JVM：journal 删除失败回滚链；真机：confirm-switch e2e `:119`（committed 后存活断言，无 kill） | run A/B：journal 删除 fsync 前后两个 kill 点 → 收敛到新代且不复活为回滚 |
| B9 提交后 escape repair（流程行） | `ConfirmBackupRestore.kt:493-494`/`:708` 起 | JVM：`ConfirmBackupRestoreUseCaseTest` escape repair 用例 | run A/B：repair 中 kill → 下次启动按 journal/指针状态收敛 |
| B10 升级复制+fsync | `LedgerRuntimeOwner.kt:1043` → `LedgerStableStorage.kt:530-556` | JVM：`LedgerStableStorageTest:357,:399,:411,:469`；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:191` | run A/B：升级复制中 kill → legacy 原件完整保留（非破坏式序列） |
| B11 升级指针发布前窗口（窗口行） | `LedgerRuntimeOwner.kt:1043` 完成 → `:1051` 前（无新落盘，磁盘为「有代目录、无指针」） | JVM：`LedgerStableStorageTest:37`（无指针既有代 fail-closed）；`BackupRestoreRecoveryTest` 采纳/废弃分支恢复语义；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:81`（无指针 fail-closed + 确认采纳打开） | run A/B：升级复制完成、指针未发布时 kill → `POINTER_MISSING` fail-closed → 经采纳（B15）或废弃重跑（B16）收敛；绝不静默空库（D-178 `:3492`/`:3508` 实证窗口、D-182 裁决 C 承接面） |
| B12 升级指针发布 | `LedgerRuntimeOwner.kt:1051` → `LedgerStableStorage.kt:562-570` | JVM：`LedgerStableStorageTest:343,:357` | run A/B：指针已发布、legacy 未删时 kill → 下次启动选新代、legacy 惰性 |
| B13 removeLegacyFiles | `LedgerRuntimeOwner.kt:1052` → `LedgerStableStorage.kt:578-591` | JVM：`LedgerStableStorageTest:303,:321` | run A/B：legacy 半删时 kill → 新代权威、无双重有效图 |
| B14 全新安装建代+发布 | `LedgerRuntimeOwner.kt:1029-1032` | JVM：`LedgerStableStorageTest:28,:343` | run A/B：发布前 kill → 无指针 `gen-1` fail-closed、绝不静默空库（D-176 禁令；无 legacy 原件时废弃分支类型化不可用，`BackupRestoreRecovery.kt:213-218`） |
| B15 采纳发布 | `BackupRestoreRecovery.kt:174-185` | JVM：`BackupRestoreRecoveryTest` 采纳链（`:102,:122,:139,:162`）；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:81`（采纳腿，无 kill） | run A/B：采纳发布中 kill → 保持无指针 fail-closed 或指针完整发布，无中间态被消费（发布是唯一 mutation，`:190-193`） |
| B16 废弃重跑候选删除 | `BackupRestoreRecovery.kt:196-240` | JVM：`BackupRestoreRecoveryTest` 废弃链（`:175,:197,:214,:229,:240`）；真机预设腿 | run A/B：候选删除中 kill → `POINTER_MISSING` 保持 fail-closed、无半删代被启动选中 |
| B17 恢复重发布旧指针 | `LedgerStableStorage.kt:780-805` | JVM：`LedgerSwitchJournalTest:107,:125,:145,:189`；真机预设腿 `AndroidRestoreRecoveryInstrumentedTest:156` | run A/B：恢复重发布中 kill → 再次重启仍收敛旧代（恢复的恢复幂等） |
| B18 恢复删 journal | `LedgerStableStorage.kt:806-816` | JVM：`LedgerSwitchJournalTest:189,:206` | run A/B：journal 删除未及目录 fsync 时 kill → 不复活已恢复状态 |
| B19 恢复删新代目录 | `LedgerStableStorage.kt:817`（→`:710-736`） | JVM：`LedgerSwitchJournalTest:170`；真机预设腿：`AndroidRestoreRecoveryInstrumentedTest:156`（switched 预设回滚后状态） | run A/B：暂存目录半删时 kill → 无半库、旧代唯一有效 |

矩阵与既有覆盖的对应关系按 D-239 `:5082` 的列举 + 本次测试名逐项复核（§3.2）；「真机预设腿」与拟新增「kill 腿」的命题区分见 §3.2，预设腿不冒充 kill 腿。

### 3.5 断言面映射【已验证事实 + 冻结设计提案】

计划 `:186` 的四个断言逐项映射到现有 restart 门与拟新增向量：

- **完整旧代或新代可用**：restart 门 `resolveLedgerStorage`（`LedgerStableStorage.kt:414-471`）在 journal 回滚（`:419-438`，经 `recoverSwitchJournalAtStartup` `:780-819`）后经指针重解析，`Planned(OpenGeneration)` 仅在 `isUsableSqliteMainFile`（`:348` 起）通过时返回；JVM 预设态已覆盖（§3.2），真机腿 run B 断言权威读回（账本事实与切换前指纹一致——回滚到旧代，或等于快照内容——切换完成）。
- **无半库**：B2/B10 类半复制产物要么被惰性忽略（无 journal 半建代，`LedgerSwitchJournalTest:88`），要么 fail-closed（有 journal 走回滚删除 B19）；run B 断言不存在「被选中的半写主文件」（`isUsableSqliteMainFile` 门 `:396-397`/`:464-467`）。
- **无静默空库**：D-176 静默空库禁令 + 启动门 `POINTER_MISSING`/`POINTER_INVALID`/`ACTIVE_GENERATION_MISSING`/`ACTIVE_GENERATION_UNUSABLE`/`JOURNAL_PRESENT` 全部 `Rejected`（`:436,:449,:455,:462,:467` 与 journal 门 `:436`），create-on-open 仅全新安装路径（`LedgerStableStorageTest:28,:343`）；run B 断言任一非全新夹具在 kill 后重启**绝不**出现空的新建库。
- **最多一个有效 graph**：指针单值 + owner 单活动约束（`LedgerRuntimeOwnerTest:489`）；run B 断言重启后恰有一个可用代（journal 回滚已删暂存 B19，或 committed 恰指向新代），不存在旧新两代同时可被 owner 选中。

### 3.6 A06 向量映射与证据纪律（§10.1）

| 向量 | 原要求（计划 `:186` 分解） | 设计取证手段 | 现有覆盖 | 拟新增 | 状态承诺 |
| --- | --- | --- | --- | --- | --- |
| A06-b1 | 每个持久状态边界（§3.1 十九边界行）**杀进程**及重启 | §3.3 两阶段 kill 协议（run A/run B） | JVM 注入/预设（恢复语义）；真机仅预设态（`AndroidRestoreRecoveryInstrumentedTest` 6 用例、`AndroidRestoreConfirmSwitchInstrumentedTest` 1 用例），零 kill（grep 实证） | 19 边界行 kill 腿（可分组） | 证据仍不判 PASS：本规格不产生证据 |
| A06-b2 | 每个持久状态边界**掉电**及重启 | 受管 AVD 无真实掉电手段；进程死亡腿不可替代该命题（§3.2） | 无 | 无（单列未覆盖腿，§4；代理裁决归 §6 项 2） | 证据仍不判 PASS |
| A06-b3 | 完整旧代或新代可用 | run B 权威读回 + `resolveLedgerStorage` 门（§3.5） | JVM 预设态 | 并入 b1 | 证据仍不判 PASS |
| A06-b4 | 无半库 | `isUsableSqliteMainFile` 门 + run B 断言（§3.5） | JVM 预设态（`:88` 等） | 并入 b1 | 证据仍不判 PASS |
| A06-b5 | 无静默空库 | D-176 禁令 + 启动门 fail-closed（§3.5） | JVM（`LedgerStableStorageTest:28,:37`）+ 真机 `AndroidStartupFailClosedInstrumentedTest` 4 用例（`:46,:82,:103,:120`） | 并入 b1 | 证据仍不判 PASS |
| A06-b6 | 最多一个有效 graph | 指针单值 + owner 单图（§3.5） | JVM（`LedgerRuntimeOwnerTest:489`） | 并入 b1 | 证据仍不判 PASS |

## 4. 验收分母登记（无缩减）

**A02 子项全集**（计划 `:182` 拆分）：A02-a1 桌面 WAL 持续写入导出；A02-a2 Android WAL 持续写入导出；A02-a3 单一自洽时点/无半笔；A02-a4 无混合版本；A02-a5 两端快照集成试验。**当前未覆盖腿（单列，不删除、不合并）**：A02-a1/a3/a4/a5 桌面腿全部待实施；A02-a2 Android 腿待裁决（§6 项 3），不裁决前保持缺证；D-239 的 A02「缺证」状态在证据产生并按 §10.1 登记前不变。

**A06 子项全集**（计划 `:186` 拆分）：A06-b1 十九边界行杀进程及重启（§3.1）；A06-b2 十九边界行掉电及重启；A06-b3 完整旧代或新代可用；A06-b4 无半库；A06-b5 无静默空库；A06-b6 最多一个有效 graph。**当前未覆盖腿（单列）**：b1 真机 kill 腿全部待实施（JVM 注入只覆盖恢复语义命题）；**b2 真实掉电在任何受管 AVD 环境均无实测手段**，本文不把它折叠进 b1，也不宣布「不适用」——是否接受进程死亡作为代理须显式裁决并分开登记命题（§10.2 `:407`）。D-239 的 A06「部分证据」状态在真机腿取证前不变。

## 5. 成本估计与受影响测试清单（含 §10.3 相关性）

| 项 | 文件（拟） | 用例数 | 运行环境 | 备注 |
| --- | --- | --- | --- | --- |
| A02-a1/a3/a4/a5 | `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/`（新测试类 1 个） | 约 3-4 | CI `:ledger-data` 分片（既有分片含 `BackupSnapshotDriverTest` 于 shard 1，`tools/ci/ledger-data-shards.txt:2`） | 零构建变更（`ledger-data/build.gradle.kts:65-67` 已含 sqlite-driver）；多线程用例注意 CI 串行纪律不冲突 |
| A02-a2（如裁决需要） | `android-app/src/androidTest/`（新测试类 1 个） | 1-2 | CI instrumented job / 受管 AVD 设备门 | `tools/ci/android-instrumented-inventory.txt` 登记面为清单外文件，实施批再议 |
| A06-b1 | `android-app/src/androidTest/`（新测试类 1-2 个：kill 腿 + verify 腿） | 约 19 边界行（可分组至 9-19 用例） | 受管 AVD 设备门 + CI instrumented job；两阶段协议使每边界至少两次 instrumented 启动 | 设备时长为主要成本；`android-instrumented-inventory.txt` 同上 |

**§10.3 规模相关性**：本批纯设计文档，默认不触发重测（§10.3 `:412`「纯文字等无关改动不重复整套压力测试」）。实施批注意：A02 JVM 用例引入并发写线程与快照 IO，属受控小库测试，不改产品读量/连接；A06 kill 腿不改产品路径。**若 §6 项 1 裁决为产品切换 WAL**，则连接语义与 checkpoint 行为变化命中 §10.3 `:412`（影响读量/数据库连接），届时须选择受影响规模向量（§10.3 累积候选库叠加正式交易）重测受影响流程——该义务随裁决登记，不随本批。

## 6. 开放项（本文未冻结，须裁决；不得静默丢弃）

1. **产品是否切换 `journal_mode=WAL`**（§2.4）：权衡与建议默认答案（不切换）已列；**裁决归属：用户/主代理**。本规格不授权实施切换；无论裁决如何，A02 拓扑不变。
2. **A06 真机腿环境与范围**：(a) 受管 AVD + 两阶段 kill 协议是否批准为 b1 的取证环境；(b) **真实掉电命题**的处置——推荐答案：b1 按 §3.3 实施；b2 以「受管 AVD 无掉电实测手段」登记 BLOCKED 或经裁决以进程死亡作代理并分开登记两命题差异（不静默合并）；**裁决归属：用户/主代理**。
3. **A02 Android 集成腿是否需要**（§2.3/§2.5 A02-a2）：推荐答案——以 q13 门 CLI 证据（D-174 `:3407`）+ §2.2 JVM 腿承接，Android instrumented WAL 探针列为可选低成本补充（1-2 用例）；**裁决归属：用户/主代理**。
4. **D-239 现状漂移的更正条目**（§1.3 五项）：是否以一次事实更正登记修订 D-239 的五处字面——`:5080` 的两处（`AndroidLedgerDatabaseHandle.kt` 注释锚点、grep 零命中）、`:5082` 的三处（`AndroidStartupFailClosedInstrumentedTest` 用例数三↔四、`LedgerSwitchJournalTest` 用例数七↔九、其所属句为语义归并列举）——连同导出规格 `:71` 历史行号；**裁决归属：主代理**（本文不改 `DECISIONS.md`）。
5. **测试实现细节**：kill 腿装饰器与 marker 协议的精确形状、边界-装饰器调用映射的逐行钉住方式（含 §3.3 登记的**同原语多点判别机制**——`deleteGenerationDirectory` 7 个调用点、`publishActivePointer` 服务 5 个边界行，不得按单一调用点驻停）、kill 手段前置验证（§3.3 的 `am kill` 限制）、A02 用例的精确断言容忍度（并发窗口下的重试策略）与 §2.2 步骤 6 **并发重叠证明的测量实现**（本文冻结的是断言不等式，不是计时手段）；CI 登记面含 `tools/ci/android-instrumented-inventory.txt`、分片清单（如 `tools/ci/ledger-data-shards.txt` 新增行）与 `.github/workflows/ci.yml` 头注释测试类数（D-240 先例：`tools/ci/ledger-data-shards.txt` 1 行 + ci.yml 头注释类数，`DECISIONS.md:5102` 实读）——归实施规格；本文只冻结命题与断言面。
6. **本文的批准载体**：批准由主代理以决定条目登记（候选编号 D-246，本文不占用）；未批准前本文无授权力，P706-A02/A06 状态维持 D-239 裁决不变。

## 边界断言

- 本文是**设计规格草案**（`proposal`）；只设计取证方案；**不授权**任何实施、测试编写、产品行为变更或验收结论；P706-A02/A06 在证据按 §10.1 登记前分别维持「缺证」「部分证据」。
- 本文不修改任何既有决定、已批准规格、`DECISIONS.md` 或代码；不重开 D-174/D-176/D-177/D-179/D-182/D-183 的冻结裁决；不改写计划 §10.6 `:451-452` 的 Q13/Q14 已批准推荐。
- 零 schema/迁移/依赖/CI 配置含义；`.external/` 只读未触碰；`rgXX_` 竖井、golden fixtures/expected 零改动。
- 全部金额语义零涉及（本批无账务行为）；示例数据全部匿名合成。
- 不写本机绝对路径、个人数据或工具轨迹；官方文档只留 URL+节名与中立结论。
