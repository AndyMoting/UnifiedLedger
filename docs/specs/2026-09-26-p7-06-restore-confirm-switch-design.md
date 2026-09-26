# P7-06 备份与恢复 06.4 / 06.D 设计规格：恢复确认、原子切换、回退与失败恢复（confirm & switch）

状态：approved（2026-09-26 起草、2026-09-26 批准；本文是 06.4 / 06.D **恢复确认与切换**切片的**设计规格**，已经**独立规格评审与独立质量评审（draft-1 双评审均「需返工后批准」、无 P1；draft-2 闭合全部 findings，两份收口复评均 APPROVE 并余两项非阻塞 P3，final polish `a0f1bdb` 一并折叠）与 distinct verifier（结论 VERIFIED）**，并**由 `docs/DECISIONS.md` D-182 批准（本决定即批准依据）**，故按 `docs/CONTRIBUTING.md:168` 的允许分类标 `approved`。本文只冻结**设计级**语义，**不**构成产品行为、迁移、技术选型或发布授权，实施属后续实施批。）

**Revision:** draft-2（2026-09-26）。相对 draft-1 应用**独立规格评审与独立质量评审**（两轮均为「需返工后批准」）的全部 findings，其中两项 P2 为**两份评审独立收敛**：**P2-1**（事实更正）——`quiesce()` 置 `Quiescing`（`LedgerRuntimeOwner.kt:356`）后**从不复位 Ready**（`state = Ready` 仅在 `:309` startup 与 `:434` reopen），draft-1 §3.3/§3.4/§3.5 关于「业务可继续/状态自动复位」的陈述**错误**，现以新增 §3.10 的**运行时复原程序**闭合（每个 quiesce 后中止路径都经 `reopen(ActivePointer)` 复原 Ready），§8 第 3 项同步改写；**P2-2**（裁决 C 扩展）——D-178 残余 (a) 的砖化窗口含**复制中途**子情形（D-178 缺陷 2 实证的部分复制 `:3492`），仅验证采纳不能闭合它，§5.3 增设**用户确认式「废弃不可验证候选并重跑旧路径升级」分支**（以旧库原件仍在为守卫），残余 (a) 的两个子情形由此**全部**闭合；**P2-3**——回滚（进程内与重启两执行者）补齐**删除新代目录（含 sidecar）**义务，§3.6 装配前置改为**要求目标目录不存在/为空（delete-then-stage）**，消除重试碰撞与 stale `-wal` 危害（`LedgerStableStorage.kt:455-458` 自身登记的危害）；P3：§5.4 明确 v1 为**有条件准入**（迁移+校验设备证据，A04 往返等价腿未证）；§1.1/§3.4 登记 `parsePointer` 现为**文件私有**（`:390`）及其两个落地选项；三处悬空「§4.4」引用改为指向 §3.8/§5.3；两处「容器规格 §7.4 `:411`」更正为 **06.C 预检规格 §7.4 `:411`**；§4.1 崩溃表第 5 行补救措辞按 §5.7 更正为**重做完整预检**（非重新确认）；§7 矩阵补步骤 2 两条分支与复原程序断言；§5.3 恢复验证的版本集合**钉死为 `{currentSupportedSchemaVersion()}`** 并与 §5.4 接线集区分；容器规格 `§5.3 :254` 引用更正为 `:247`；§3.7 的「归实施批」补 §8 对应项；裸「§7.5」引用全部限定为「D-179 §7.5」。draft-1（2026-09-26）为初稿。事实基线 = 本 worktree 分支 `UL-p7-06ddesign`，基点 `b69ba7b`（D-180 登记合并点，起草时 main 头）。schema 停留 **v31**，迁移链 `1.sqm`～`30.sqm`。tracked 行号为该基点的实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 与 `local/artifacts/` 为**主 checkout 的本地只读文件**，本 worktree 内不存在，行号以主 checkout 实读为准。本文**不**新增决定条目、**不**修改任何既有决定；不复制大段产品代码；不写本机绝对路径、个人数据或工具轨迹；示例与命名全部匿名合成。文件名采用**起草日期** 2026-09-26，形式与既有 p7-06 规格（`2026-09-24-*`/`2026-09-25-*`）一致。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为当前 worktree 基点 `b69ba7b` 的实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读）：

- **已批准的容器格式规格（承重权威，D-174）**：`docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（approved）。本文实现其 **06.D 侧**：
  - §4.8（`:193-212`）：**恢复侧磁盘峰值公式**（`:201`、`:206-208`）——本文 §3.2/§5.1 给出它与 06.C 预检子集的**拆分确认**（D-179 §10 第 9 项的 06.D 义务）；`:210` 的后台线程要求对确认流程同样生效。
  - §5.3（`:245-254`）：**持久 journal `prepared → switched → committed` 与冻结的回滚（ROLLBACK）重启规则**（`:248-252`；`:247`：多文件 rename 不构成一次原子事务）。本文 §4 把该冻结机器**实例化**为确认流程的持久化半部，不重开其任何裁决。
  - §5.4（`:256-262`）：仅接受本产品固定身份（`:261`）。
  - §5.5（`:264-272`）：**06.D 必须满足的六条失败向量**（确认前零正式写入 `:268`、确认后准入与等待 `:269`、发布门 `:270`、切换前 `Rejected`/切换后按 journal 回滚/回滚也失败 `RecoveryRequired` `:271`、清理 `:272`）。本文 §3/§4 逐条对应。
  - §7（`:299-318`）：P706-A03（`:307`）、A04（`:308`）、A05（`:309`）、A06（`:310`）、A07（`:311`）的切片归属；A05/A06/A07 的实际切换与故障注入**归本切片**（`:318`）。
  - **本文逐字节不改其任何冻结字节、参数、拒绝码、journal 机器或重启规则**。
- **已批准的 06.1 稳定存储与 owner 规格（承重权威，D-176）**：`docs/specs/2026-09-24-p7-06-stable-storage-runtime-owner-design.md`（approved）。本文**只消费**其 owner/lease/generation/quiesce 契约与稳定存储原语（§1.1），**不改其契约**；其 §4.4（`:212-217`）明确登记「进程内 generation」与「磁盘代目录/指针」是**两个层次**（`:215`），本文全程区分这两层；其 §5.1（`:229-242`）把「journal 存在」作为 06.1 的 **fail-closed 门**（`:242`），本文 §4.3 将该门**扩展**为 06.D 的 journal 恢复（这是 06.1 规格自身登记的 06.D 义务，`§6 :285`「存在即 06.D 时代状态」）。
- **已批准的 06.B 导出规格（D-177）**：`docs/specs/2026-09-24-p7-06-backup-export-design.md`。本文不触碰其导出侧决定；`BackupExport.kt` 的既有 `catch (Throwable)` 位点（`:263`/`:271`/`:291`/`:371`）按 D-180 第 5(b) 条（`docs/DECISIONS.md:3561`）作为**登记的未来一致性清扫**，不归本批。
- **已批准的 06.C 预检规格（D-179）与其实施登记（D-180）**：`docs/specs/2026-09-25-p7-06-restore-preflight-design.md`（approved）。本文**消费**其预检产物与不透明 token 契约（§7.1 `:375-384`、§7.5 `:415-423`），**承接**其 §10（`:482-501`）的全部未关闭项（逐项处置见 §8），**不重开**其任何冻结裁决（含：预检全程持 lease `:393-398`、头部/payload 不一致拒绝、严格迁移规则、确认重校验硬要求）。D-180（`docs/DECISIONS.md:3544` 起）登记 06.C 实施事实与残余；其第 5(d) 条（`:3563`）把 **P2-7 组合根接线、supportedSourceVersions 白名单集合、preview 字段集、A03 端到端切换不变量、`AndroidBackupSourcePort` sizeOf 接线复检**五项归 **06.D**（A04 白名单集合与往返等价归 06.4）——本文逐项处置（§5.4/§5.5/§5.6/§7）。
- **阶段计划（本地只读，主 checkout）**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`：§6 子项表（`06.D 行 :138`）、§6.1（层责任表 `:148-154`，其中 `:150` 的 `ConfirmRestore` 用例与不透明 token、`:152` 的平台适配器责任、`:153` 的 owner、`:154` 的 app-ui「明确替换确认」与恢复后清缓存/恢复回执义务）、§6.2（恢复状态机 `:162`、技术门 `:164`、清理策略 `:166`）、§6.3（切片 `06.4 / 06.D 行 :176`、验收向量 A03 `:183`/A04 `:184`/A05 `:185`/A06 `:186`/A07 `:187`/A08 `:188`、`:194` 明述全部为未来验收）。该计划文件的「推荐/建议」均为 proposal，不构成产品行为、迁移、技术选型或发布授权。
- **决定**：`docs/DECISIONS.md` D-174（`:3397` 起）、D-176（`:3436` 起：owner 契约 `:3448`、**静默空库禁令** `:3447`）、D-177（`:3459` 起）、D-178（`:3483` 起：残余 (a) **`POINTER_MISSING` 砖化窗口承接至 06.D** `:3508`、(b) `:3509`、(c) `:3510`）、D-179（`:3518` 起）、D-180（`:3544` 起）。本条只**承接**其已冻结裁决，不修改任何一条。
- **本地检查点（主 checkout 只读）**：`docs/PROJECT_STATE.local.md:4`（r41：main = `b69ba7b`；下一步 = 06.D 设计规格，高风险路由：取证→规格→双评审→verifier→实施）。
- **开发规范**：`docs/CONTRIBUTING.md:165`（正式文档以中文为主、代码标识符保留英文）、`:168`（新建/实质修改的 `docs/specs/` 设计必须标记 `approved`/`proposal`/`superseded`/`historical`）、`:171`（文档不得含本机绝对路径、个人账务数据或临时讨论记录）。
- **源码现实（逐条复核，file:line）**：见 §1。
- **不可触碰面**：`.external/` 只读；本批零产品代码、零测试、零 schema/迁移、零依赖；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动；不改容器格式规格的任何冻结字节布局、参数值、拒绝码或 journal 机器；不改 D-156/D-158/D-174/D-176/D-177/D-179/D-180 及既有 P7-01～P7-05 冻结面。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 已就位、可被 06.D 直接消费的 owner / 稳定存储原语（消费面，不改）

| 原语 | 位置 | 对 06.D 的含义 |
| --- | --- | --- |
| `suspend fun quiesce()` | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/LedgerRuntimeOwner.kt:351`（有界超时 `LEDGER_QUIESCE_TIMEOUT_MILLIS` `:195`，默认 5 s；实现 `:351-379`） | 切换前拒绝新租约并等待在飞读写退出（容器规格 §5.5 第 2 点 `:269`）。**调用方契约**：发起 quiesce 的上下文**不得持有 lease**（`:348-349`；06.C 规格 §7.3 `:397` 的 P3-3 自死锁说明）。它**挂起**至在飞 lease 释放或有界超时后返回 `QuiesceBlocked`（`:374-377`），**不是**瞬时拒绝（D-179 §7.3 `:397` 明令 06.D 不得假设瞬时） |
| `fun acquireLease(): LeaseAcquireResult` | `LedgerRuntimeOwner.kt:321-331`（非阻塞 `tryLock`；非 Ready 返回 `RuntimeNotReady` `:324`） | 预检用（已实现）；确认流程**不得**用它包住 quiesce |
| `fun closeActiveGraph(): CloseResult` | `LedgerRuntimeOwner.kt:392-404`（`tryLock` `:393`；在飞 > 0 → `QuiesceBlocked` `:396`） | 关闭活动图；关闭后 `activeGeneration` 置 null（`closeHeldGraphLocked` `:442-447`） |
| `fun reopen(target): ReopenResult` | `LedgerRuntimeOwner.kt:413-439`（在飞 > 0 → `QuiesceBlocked` `:417`；失败 fail-closed `:425-428`） | 默认 `GenerationSelection.ActivePointer`（`:76`），即「按磁盘活动指针重开」——切换发布指针后经它重建新图 |
| generation 两层语义 | 进程内 `generationCounter`/`activeGeneration`（`LedgerRuntimeOwner.kt:249`/`:230`）≠ 磁盘代目录名 `gen-<n>`（`LedgerStableStorage.kt:36`/`:235`）；两层区分由 06.1 规格登记（`2026-09-24-p7-06-stable-storage-runtime-owner-design.md:215`） | token 绑定的是**进程内** generation（`RestorePreflightToken.generation`，`RestorePreflight.kt:194`）；切换的「当前代」判定与 `gen-(n+1)` 编号用的是**磁盘**指针（§3.4） |
| 落地守卫 | `shouldDiscardLandingResult`（`LedgerRuntimeOwner.kt:465-468`）与 `LedgerLeaseScope.isCurrentGeneration`（`:641`） | 切换结果回落 UI 时丢弃旧 generation 的晚到回调（计划 A07 `:187`） |
| 原子指针发布 | `publishActivePointer`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/LedgerStableStorage.kt:445-453`，internal）：`writeAtomic`（`:104`，原子替换，部分写入不可见 `:100-103`）→ 指针 `fsyncFile`（`:119`）→ 宿主目录 `fsyncDirectory`（`:126`） | **冻结原语**：06.D 的指针发布（含回滚 republish 与 §5.3 恢复采纳）**只准**经它进行 |
| 指针解析 | `parsePointer`（`LedgerStableStorage.kt:390-400`，严格 `gen-<n>`、`n >= 1`）与 `ledgerPointerBytes`（`:403`）。**可见性注记（P3-2）**：`parsePointer` 现为**文件私有**（`private fun`，`:390`），其他文件不可直接调用；落地二选一（均不新增 public API）：把它**放宽为 `internal`**（同模块 app-ui 内的最小改动，推荐），或复用既有 internal 面 `resolveLedgerStorage`/`ledgerPointerBytes` | 切换读「当前磁盘代」与发布 `gen-(当前+1)` 的依据（§5.2） |
| 新代落盘先例 | `stageLegacyUpgrade`（`LedgerStableStorage.kt:413-439`）：复制主文件与存在的 `-wal`/`-shm`（`:419-426`，sidecar 集合 `:39`）→ **逐文件 fsync + 目录 fsync 全部先于指针发布**（`:431-438`） | 06.D 装配新代目录**镜像该顺序**（§3.5）；fsync 是发布前置而非删除前置（D-176 `:3446`） |
| 打开前守卫 | `isUsableSqliteMainFile`（`LedgerStableStorage.kt:316-328`：存在、非空、16 字节 SQLite magic，只读前缀 `:323-327`） | 新代发布前的本地一致性门与 §5.3 恢复采纳的候选预检共用 |
| 启动解析与 fail-closed | `resolveLedgerStorage`（`LedgerStableStorage.kt:338-382`）：journal 门 `:343-345`（`JOURNAL_PRESENT` `:279`）、指针缺失 `:353-355`（`POINTER_MISSING` `:282`）、指针无效 `:357-359`（`POINTER_INVALID` `:284`）、活动代缺失/不可用 `:362-367`（`:288`/`:290`）；由 `openStableStorageLedger`（`LedgerRuntimeOwner.kt:761`）调用，失败抛 `LedgerStorageRejectedException`（`:455-457`）→ owner 映射 `StartupError` | 06.D **不弱化**任何 fail-closed 规则；journal 门在 06.D 扩展为恢复入口（§4.3）；`POINTER_MISSING` 恢复见 §5.3 |
| 启动暂存清扫 | `sweepBackupStaging`（`LedgerStableStorage.kt:491-505`）现删 `snapshot-`/`container-`/`restore-` 三前缀（`:497-499`），由 `openStableStorageLedger` 每次启动调用（`LedgerRuntimeOwner.kt:777`） | 06.C 的 `restore-*` 前缀已纳入启动清理（D-179 §10 第 11 项已由实施闭合）；跨会话 stale 策略据此成立（§5.7） |
| 单活动互斥 | owner 内部 `Mutex` + 原子状态（`LedgerRuntimeOwner.kt:221`/`:225`；D-176 `:3448`） | 并发 quiesce/close/reopen 由互斥串行化为 `TransitionInProgress`/typed 拒绝（§3.9） |

### 1.2 已就位的 06.C 预检产物（消费面，不改）

| 事实 | 位置 | 对 06.D 的含义 |
| --- | --- | --- |
| 不透明 token | `RestorePreflightToken`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/RestorePreflight.kt:191-198`）：opaque `handle` `:193`、internal 绑定 `generation` `:194`、`targetLedgerId` `:195`、`authenticatedArtifactSha256` `:196`、`migratedArtifactSha256: ByteArray?` `:197` | 确认重校验（D-179 §7.5 四项）的全部绑定材料已随 token 交付；确认用例与预检同模块（app-ui），可读 internal 字段 |
| 预检摘要 | `RestorePreflightSummary`（`RestorePreflight.kt:165-183`） | 已含容器格式版本/大小、迁移前后版本、来源/目标身份、认证摘要（hex 显示形式）、integrity/FK/domain 布尔与三个校验计数；**尚无**关键 owner 计数摘要（§5.5 的 06.D 缺口） |
| 暂存路径 | `layout.restoreSnapshotFile`/`restoreMigratedFile`（`LedgerStableStorage.kt:230`/`:233`）；暂存容器副本在预检成功后**已删除**（P3-12，`RestorePreflight.kt:311-315`） | 确认时只存在明文快照与迁移副本（或无迁移时仅快照，`validationPath = snapshotFile`，`RestorePreflight.kt:463`）；确认**不重新读取**外部容器（D-179 §7.2 `:386-389`） |
| 严格迁移与校验 | `migrateIsolatedSnapshotStrictlyOn`（`ledger-data/src/commonMain/kotlin/com/unifiedledger/data/RestoreIsolatedMigration.kt:91-113`，单事务迁移 + 事务内戳版本 `:105-108`）、`readAuthoritativeUserVersionOn` `:32-46`、`foreignKeyCheckOn` `:136-150`、`readIntegrityCheckRowsOn` `:157-171`、`validateDomainOn` `:311-334` | §5.3 恢复采纳与 §3.6 发布后权威读回复用同批 helper；不运行 seed bootstrap（`RestoreIsolatedMigration.kt:19-20`） |
| 白名单注入缝 | `RestorePreflightRequest.supportedSourceVersions: Set<Long>`（`RestorePreflight.kt:153`）与 `currentSchemaVersion` `:155`（组合根经 `currentSupportedSchemaVersion()`（`RestoreIsolatedMigration.kt:29`）供给，P2-6 `:144-147`） | 确认路径**不**重新迁移（迁移已在预检完成）；白名单集合本身归本文 §5.4 裁决 |
| 延后接线（P2-7） | `RestorePreflight.kt:236-244` KDoc 明述：该用例与端口**未被任何组合根构造**，理由是白名单集合与预览字段集为规格 OPEN 项且确认路径属 06.D | 06.D 接线（§6）同时解除该延后 |
| 拒绝码现状 | `BackupPreflightRejection`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/backup/BackupContainerReader.kt:39`）：冻结码 `:29-32`、**建议名（非冻结）** `:34-37` | 06.D 的切换期结果**不复用/不新增** `P706_*` 容器拒绝码（§3.7）；建议名的冻结与否仍 OPEN（§8） |

### 1.3 06.D 必须闭合的差距

- **无确认用例**：全仓检索 `ConfirmRestore` 仅命中回收站恢复的同名事件（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503UiEvent.kt:780`），无备份语义的 `ConfirmRestore`（06.C 规格 §1.5 `:87` 同款检索结论在其基线成立，本基点复核仍成立）；计划 `:150` 要求的 `ConfirmRestore` 用例不存在。
- **无持久切换 journal**：`LEDGER_SWITCH_JOURNAL_FILE` 常量已声明（`LedgerStableStorage.kt:30`）但 06.1 **从不写 journal**（其 KDoc `:26-29` 明述该机器属 06.D）；启动遇 journal 一律 fail-closed（`LedgerStableStorage.kt:343-345`；06.1 规格 `:242` 把扩展权明确留给 06.D）。
- **组合根未接线**：两端组合根未构造 `RestorePreflightUseCase` 与任何恢复 UI 调用点（`RestorePreflight.kt:236-244`；D-180 `:3550`「产品 UI 调用点未接线」）。
- **`AndroidBackupSourcePort` 的 `sizeOf` 未接线**：默认 `{ null }`（`android-app/src/main/kotlin/com/unifiedledger/android/AndroidRestorePreflightPorts.kt:50`），故 Android 端 provider 报大小的「不读取」快速路径（`RestorePreflight.kt:593-597`）不可达，只能走计数流回退（`:621-625`）；D-180 第 5(d) 条（`docs/DECISIONS.md:3563`）把接线复检归 06.D。桌面端已报大小（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopRestorePreflightPorts.kt:53`/`:64`）。
- **`POINTER_MISSING` 砖化窗口**：进程被杀/掉电发生在旧路径升级的 `stageLegacyUpgrade` 与 `publishActivePointer` 之间时留下无指针代目录，永久 fail-closed，06.1 无恢复（D-178 残余 (a)，`docs/DECISIONS.md:3508`；`openStableStorageLedger` KDoc `LedgerRuntimeOwner.kt:755-759` 同款登记）——承接至 06.D（§5.3，含**复制完成**与**复制中途**两个子情形：前者经验证采纳，后者经用户确认的废弃重跑分支重建）。
- **预览缺关键 owner 计数摘要**：06.C 预检规格 §7.4 `:411` 与计划 `:154` 要求「明确替换确认」使用关键 owner 计数摘要；`RestorePreflightSummary`（`RestorePreflight.kt:165-183`）只有校验计数，无 accounts/categories/transactions 计数（§5.5）。

## 2. 目标与范围

### 2.1 目标（06.4 / 06.D）

- **确认用例**：新增共享 `ConfirmRestore` 用例（app-ui，与 `RestorePreflight` 同侧），输入 = 不透明 token + 显式用户确认；执行 D-179 §7.5 四项硬重校验 → 完整峰值磁盘前置 → `quiesce` → `closeActiveGraph` → 新代落盘 → 原子指针发布 → `reopen` + 权威读回 → generation 守卫投递（§3）。
- **持久 journal 与重启回滚**：把容器规格 §5.3 的 `prepared → switched → committed` 冻结机器与 ROLLBACK 重启规则实例化（§4），扩展 06.1 的 journal fail-closed 门为 06.D 的 journal 恢复。
- **`POINTER_MISSING` 显式恢复**：用户确认式恢复闭合 D-178 残余 (a)（§5.3），不弱化 D-176 静默空库禁令。
- **组合根接线（P2-7）**：两端组合根构造预检 + 确认用例，注入白名单集合（§5.4）与 `currentSchemaVersion`；共享 UI 呈现预览摘要与明确替换确认（计划 `:152`/`:154`）。
- **预览字段集**：把 D-179 §7.4 最小集具体化为字段清单并补关键 owner 计数摘要（§5.5）。
- **sizeOf 接线**：Android 端接通单次 ContentResolver 大小查询，计数流降级为回退（§5.6）。
- **验收映射**：P706-A03 端到端切换不变量与 A05 语义（§7）；**不标任何 PASS**。

### 2.2 明确范围外（后续切片或本批不做）

- **06.4 的往返等价**：P706-A04 的白名单集合与 Android↔Desktop 往返等价（D-180 `:3563`「A04 白名单集合与往返等价 → 06.4」；容器规格 §7 `:308`）不在本文范围；本文 §5.4 只裁决**接线用**白名单集合。
- **成功后旧代清理策略**：旧代在切换成功后保留多久、何时删除（容器规格 §5.5 第 5 点 `:272`、计划 `:166`）登记为 OPEN（§5.2、§8）；本批不定义清理器。
- **OS 自动备份/设备迁移排除决策**：仍归隐私规格（D-174 第 4 条 `:3409`；容器规格 §6 `:291`）；**不声称**云备份/设备迁移已关闭。
- **任何 schema/迁移边/依赖变更**：schema 停留 v31，零 DDL、零 `.sqm`、零新依赖。
- **容器格式的任何改动**：不改 D-174 的冻结字节/参数/拒绝码/AAD/资源上限/journal 机器/重启规则。
- **D-176 owner 契约的任何改动**：不新增/修改 owner 方法签名与 fail-closed 语义；只消费 §1.1 列出的原语。（06.D **扩展**的是启动解析对 journal 的处理语义——那是 06.1 规格自身登记的 06.D 义务，不是契约改动；详见 §4.3。）
- **UI 组合细节**：确认对话框、进度、恢复面（`RecoveryRequired` 呈现）的具体组合形态归实施批；本文只冻结字段集与状态语义。
- **`BackupExport.kt` 的 `catch (Throwable)` 一致性清扫**：按 D-180 第 5(b) 条（`:3561`）登记为未来清扫，不归本批。

## 3. 确认与切换数据流（计划 `:162` 的 `AwaitingConfirmation → Quiescing → Prepared → Switching → Reopening → Committed` 段）

预检结束（`PreviewReady`，lease 已在 `finally` 释放，`RestorePreflight.kt:317`）后，token 处于 `AwaitingConfirmation`。确认按以下**冻结顺序**执行。**核心不变量**（计划 `:183`/`:185`；D-179 §7.5 `:422`）：**四项重校验任一失败 → 类型化 stale 拒绝、零切换**；**quiesce/close 被阻 → 类型化「切换推迟」拒绝、零切换**；**指针发布后失败 → 回滚到旧指针**；**回滚也失败 → fail-closed 恢复态**（§3.8）。**运行时不变量（P2-1）**：`quiesce()` 一旦把 owner 置入 `Quiescing`（`LedgerRuntimeOwner.kt:356`），**没有任何原语会把它复位回 `Ready`**（`state = Ready` 仅存在于 startup `:309` 与 reopen `:434`；`acquireLease` 要求 Ready，`:324`）——因此**每一个 quiesce 之后的中止路径都必须经 §3.10 的复原程序以 `reopen(ActivePointer)` 结束**，拒绝结果携带「运行时已复原（或 fail-closed）」的语义，绝不把 owner 留在 `Quiescing`。

```text
1) 四项确认时硬重校验（§3.1）——任一失败 → stale 拒绝，零切换
2) 完整恢复侧峰值磁盘前置（§3.2）——不足 → 类型化拒绝，零切换
3) owner.quiesce()（有界超时；ConfirmRestore 不持 lease）——QuiesceBlocked → 「切换推迟」拒绝，零切换
4) quiesce 后 generation 复核——代际已推进 → stale 拒绝，零切换
5) closeActiveGraph()——被阻 → 「切换推迟」拒绝，零切换（指针未动，图仍开）
6) 装配新代目录 gen-(当前+1)（复制迁移副本 + 逐文件 fsync + 目录 fsync）+ 写 journal=prepared ——失败 → 删除半成品目录、零切换
7) publishActivePointer(newGeneration) + 写 journal=switched ——此后进入「已切换」窗口，失败走回滚（§3.8）
8) reopen(ActivePointer)（打开闭包含权威读回）——失败 → 回滚（§3.8）
9) 移除 journal（committed）→ 结果经 generation 守卫投递（§3.9）
```

### 3.1 步骤 1：确认时硬重校验（D-179 §7.5 的四项，`docs/specs/2026-09-25-p7-06-restore-preflight-design.md:417-421`）

1. **暂存工件存在**：token `handle` 派生的 `restore-snapshot-<handle>`（`LedgerStableStorage.kt:230`）存在（`LedgerFileSystem.exists`，`LedgerStableStorage.kt:80`）；预检发生过迁移时 `restore-migrated-<handle>`（`:233`）亦存在。判定「是否发生过迁移」用 token 的 `migratedArtifactSha256 == null`（`RestorePreflight.kt:197`；null 表示预检无迁移、校验面即快照本身，`RestorePreflight.kt:460-463`）。
2. **摘要与 token 一致**：对存在文件做**流式 SHA-256**（与 D-179 §5.4 同一原语；产品内同形状为 `sha256OfFile`，`RestorePreflight.kt:839-850`），与 `authenticatedArtifactSha256`（快照）/`migratedArtifactSha256`（迁移副本）逐字节比较。
3. **token generation 仍是当前活动代**：`LedgerLeaseScope.isCurrentGeneration(token.generation)`（`LedgerRuntimeOwner.kt:641`，其判定为 `shouldDiscardLandingResult` `:465-468`）。
4. **目标账本身份一致**：`token.targetLedgerId`（`RestorePreflight.kt:195`）等于固定目标身份（容器规格 §5.4 `:261`）。

- **任一不符 → 类型化 stale 拒绝，零切换**（计划 A05 `:185`「零切换或 stale 拒绝，不能恢复未预览工件」）。
- **跨会话 token 由第 1 项天然拒绝**（§5.7 的既定策略）。
- 本步骤**不持 lease**（无 facade 访问）；它对 `activeGeneration` 的读是 `@Volatile` 快照（`LedgerRuntimeOwner.kt:229-231`），故步骤 4 设 quiesce 后复核。

### 3.2 步骤 2：完整恢复侧峰值磁盘前置（D-179 §10 第 9 项的 06.D 义务，本文确认拆分）

- 容器规格 §4.8 恢复侧峰值公式（verbatim `:207`）：`container_size + plaintext_size + migrated_copy_size + new_generation_db_size + retained_old_generation_db_size + 64 MiB`。该公式口径是**全恢复生命周期的峰值**（`:206`）。06.C 已在预检步骤 3 执行其**前半部**：`staged_container_size + plaintext_len + plaintext_len（迁移副本下界）+ 64 MiB`（`RestorePreflight.kt:358`，余量 `BACKUP_DISK_HEADROOM_BYTES` `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/backup/BackupContainerFormat.kt:58`）。**本文确认该拆分**：两个时点各自检查、**并集覆盖全部五项**——
  - **预检峰值时点**（06.C 已覆盖）：容器 + 明文暂存 + 迁移副本并存。
  - **确认峰值时点**（06.D 本步，新义务）：**暂存容器副本已删除**（P3-12，`RestorePreflight.kt:311-315`），并存的是 明文快照 + 迁移副本 + 新代 DB + 保留旧代 DB。本步检查：`可用空间 ≥ snapshot_size + migrated_copy_size + new_generation_db_size + retained_old_generation_db_size + 64 MiB`。
  - 取值：`snapshot_size`/`migrated_copy_size` = 暂存文件**实际长度**（`LedgerFileSystem.length`，`LedgerStableStorage.kt:84`）；`retained_old_generation_db_size` = 当前活动代主文件 + 存在 sidecar 的实际长度（`sidecarFile` `:241-244`）；`new_generation_db_size` 以迁移副本长度为**下界估计**（迁移重写页面，大小不保证相等，06.C 规格 §6.2 `:340`），新代落盘后以实际长度复核，复核不足即走步骤 6 失败路径（删除半成品、零切换）。
- **未知 `usableSpace` 的取值（设计点，替代已登记）**：`usableSpace` 可返回 null（`LedgerStableStorage.kt:136`）。06.C 预检对未知**fail-open**（`RestorePreflight.kt:356-357`；其规格 `:343-346` 同时登记「倾向在 06.D 切换前置对未知 fail-closed」的替代）。**本文选定确认时点对未知 fail-closed**：切换峰值最大、「不得中途半切换」（容器规格 `:201`）的后果最严重，且确认时尚可零代价放弃（指针未动）。**替代（否决）**：沿用 fail-open——若未知空间下新代落盘中途失败，将落入指针发布前失败路径，虽仍零切换，但白耗一次 quiesce/close/reopen 周期且违背本步存在的意义。
- **空间不足 → 类型化拒绝，零切换**；**必须后台线程**（容器规格 `:210`）。

### 3.3 步骤 3：`owner.quiesce()`

- `ConfirmRestore` **不得持有 operation lease**（调用方契约，`LedgerRuntimeOwner.kt:348-349`；P3-3 自死锁）。quiesce 挂起至在飞 lease 排空或有界超时（默认 5 s，`:195`）。
- **任一 quiesce 出口（`Quiesced` 或超时 `QuiesceBlocked`）都使 owner 停在 `Quiescing`**（`:356` 后无复位；超时返回点 `:374-377`）：活动图虽仍打开，但 `acquireLease` 要求 Ready（`:324`），**新的业务准入已停止**。因此本步之后的一切中止都**不是**「回到确认前状态」，而是经 §3.10 复原程序以 `reopen(ActivePointer)` 恢复 Ready——拒绝结果携带该语义，**不**声称业务无感继续。**不得假设瞬时拒绝**：预检/导出持 lease 期间确认，将先等待至超时（D-179 §7.3 `:397`）。用户层面的「可重试」指复原完成后可再次发起整个确认流程，**不**是自动重试循环。
- **替代（否决）：持 lease 覆盖切换**——与 `:348-349` 的调用方契约直接冲突，且切换恰恰需要**排空**在飞而非加入在飞。

### 3.4 步骤 4：quiesce 后 generation 复核

- quiesce 出口（含超时）后、close 前，复核 `isCurrentGeneration(token.generation)`：若步骤 1 与步骤 3 之间有并发 reopen 推进了进程内 generation（reopen 不需要 lease，`LedgerRuntimeOwner.kt:413-417` 只查互斥与在飞数），token 已 stale → **stale 拒绝 + §3.10 复原程序**（`reopen(ActivePointer)` 恢复 Ready；指针未动，旧图返回）。**不存在「无副作用复位」**：owner 已在 `Quiescing`，复原是显式动作而非语义自动。
- 磁盘代号在此步读取：活动指针内容按 `parsePointer` 的严格 `gen-<n>` 规则解析（实现 `LedgerStableStorage.kt:390-400`；见 §1.1 可见性注记）；此时磁盘指针仍指旧代（尚未发布）。

### 3.5 步骤 5：`closeActiveGraph()`

- 被阻（`TransitionInProgress` `:393` 或 `QuiesceBlocked` `:396`）→ **「切换推迟」拒绝 + §3.10 复原程序**：指针未动；owner 此刻在 `Quiescing`（新业务准入已停），**只有**复原 reopen 成功后才恢复 Ready——绝不声称「图仍开、业务可继续」。
- 成功 → 活动图关闭、`activeGeneration` 置 null（`:442-447`），业务准入停止（`acquireLease` 非 Ready 返回 `RuntimeNotReady`，`:324`）。此后的失败路径（§3.6/§3.7/§3.8）同样以 reopen（或 §3.8 的重开旧图）收尾，owner 不会滞留非 Ready 态。

### 3.6 步骤 6：装配新代目录 + journal=prepared

- 新目录 = `layout.generationDirectory(当前磁盘代 + 1)`（`generationDirectoryName` `:235` 的 `gen-<n>`；编号裁决见 §5.2）。从迁移副本装配：复制主文件（`fileSystem.copy` `:109`；无迁移时复制快照），复制**存在的** `-wal`/`-shm` sidecar（`LEDGER_SIDECAR_SUFFIXES` `:39`）——**镜像 `stageLegacyUpgrade` 顺序**（`LedgerStableStorage.kt:419-426`）：逐文件 `fsyncFile`（`:119`）→ 新代目录 `fsyncDirectory`（`:126`），**全部先于指针发布**（D-176 `:3446`「fsync 是发布指针的前置」）。
- **装配前置（P2-3）：目标目录必须不存在或为空**。若 `gen-(n+1)` 已存在（只可能是先前中止/回滚的遗留），执行 **delete-then-stage**：先删除整个遗留目录（含 sidecar，镜像 §4.3 `prepared` 恢复的废弃动作与 `removeLegacyFiles` 的 sidecar 纪律 `LedgerStableStorage.kt:455-458`），再按上述顺序装配；删除失败 → 类型化失败 + §3.10 复原。**禁止**向非空目标直接复制（`copy` 对已存在目标的行为未指定，且遗留 stale `-wal` 可能被新图打开拾取——`removeLegacyFiles` KDoc `:455-458` 自身登记的危害）。
- 装配完成后写持久 journal=`prepared`（§4.1；语义 = 容器规格 §5.3 `:249`「新代已完整落盘并通过校验，但指针未切换」）。
- **失败** → 尽力删除半成品新代目录（含 sidecar）→ 类型化失败、零切换（指针未动）→ **§3.10 复原程序**重开旧图恢复 Ready。`isUsableSqliteMainFile`（`:316-328`）作为装配后的本地一致性门，不过门同 failure 路径。

### 3.7 步骤 7：原子指针发布 + journal=switched

- `publishActivePointer(fileSystem, layout, 当前磁盘代 + 1)`（`LedgerStableStorage.kt:445-453`，**冻结原语**）→ 写 journal=`switched`（语义 = `:250`「活动指针已指向新代，但新图尚未成功打开并读回」）。
- 自本步起进入「已切换」窗口：任何失败走**回滚**（§3.8）；进程在此窗口被杀 → 重启按 journal 回滚（§4.2）。
- 06.D 的切换期结果（stale/推迟/失败/回滚/恢复）是**切换结果类型**，**不占用** `P706_*` 容器拒绝码空间（冻结码表属容器规格 §4.3.1 `:123-130`；建议名属 06.C §10 第 3 项 `:488`）；具体类型/命名归实施批（§8 第 8 项）。

### 3.8 步骤 8 的失败路径：回滚（ROLLBACK）

- **触发**：`reopen(GenerationSelection.ActivePointer)`（`LedgerRuntimeOwner.kt:413`）失败（`ReopenResult.Failed`，打开闭包内的权威读回失败 `:425-428`），或步骤 7 的 `publishActivePointer`/journal 写入抛出。
- **回滚动作**（容器规格 §5.5 第 4 点 `:271`「切换后失败 → 按 journal 回滚」；重启规则 v1 冻结为 ROLLBACK `:252`）：**旧代目录原样保留**（本流程从不删除旧代）→ `publishActivePointer(fileSystem, layout, 旧磁盘代)` republish 旧指针 → 移除 journal → **尽力删除新代目录 `gen-(n+1)`（含其 `-wal`/`-shm` sidecar；P2-3）** → `reopen(ActivePointer)` 重开旧图（Ready 恢复）→ 返回**类型化失败**（数据未损、可整流程重试）。删除新代目录是重试确定性的前提：下一轮确认的步骤 6 才能以干净目标重新装配，且失败的 reopen 遗留的 stale sidecar 不会被拾取（`LedgerStableStorage.kt:455-458` 的同款危害登记）。删除失败**不**阻断回滚本身（指针与 journal 已复原，删除是尽力而为的清理，登记残余于 §8 第 1 项的保留策略一并处置）。
- **回滚也失败** → **fail-closed 恢复态**（`RecoveryRequired`，计划 `:162`）：保留新旧双方、给出可恢复错误、**不循环自动初始化**；该状态的持久形状（指针指向哪一代、journal 是否残留、新代目录去留）与 §5.3 的显式恢复衔接（§8 第 5 项）。
- **替代（否决）：前滚（roll-forward）**——容器规格 §5.3 `:252` 明令 v1 冻结 ROLLBACK、前滚为显式否决替代；本文不重开。
- **替代（否决）：失败时发布新指针后不回滚、让重启自行恢复**——把进程内可即时完成的回滚推迟到重启，扩大半切换暴露窗口，违背 §5.5 第 4 点的即时回滚语义。
- **替代（否决）：回滚保留 `gen-(n+1)` 目录供重试复用**——重试装配必须面对「内容可能来自上一次失败尝试」的目录（含 stale sidecar 与半写文件），复用即引入不可信内容进切换路径；删除后重装是唯一可与 §4.3 `prepared` 恢复共享语义的选择。

### 3.9 步骤 9：committed 与结果投递

- `reopen` 成功（新图打开 + 权威读回通过，打开闭包职责 `LedgerRuntimeOwner.kt:211-214`）→ **移除 journal**（容器规格 §5.3 `:251` 的 committed 语义；发布门 `:270` 的「新图打开与权威读回通过」由此满足）。
- 结果回落 UI 经 generation 守卫：`shouldDiscardLandingResult`（`LedgerRuntimeOwner.kt:465-468`）——切换后旧 generation 的晚到回调一律丢弃（计划 `:162`「旧异步结果一律丢弃」；A07 `:187`）。
- 恢复成功后的产品收尾（清空旧草稿、旧目录/筛选缓存、旧 Unknown 会话，从新库恢复持久回执，不重放旧会话提交）是 app-ui 层义务（计划 `:154`）；本文只冻结其触发时机 = committed 之后、generation 守卫通过之后；具体组合归实施批。

### 3.10 quiesce 后中止的运行时复原程序（P2-1；本规格新增的冻结义务）

`quiesce()` 把 owner 置入 `Quiescing` 后**没有任何 owner 原语将其复位**（`state = Ready` 仅在 startup `LedgerRuntimeOwner.kt:309` 与 reopen `:434`；`acquireLease` 要求 Ready，`:324`）。故凡在 quiesce 出口之后中止确认（步骤 4 stale、步骤 5 被阻、步骤 6 失败——即一切**指针发布之前**的放弃），都必须执行以下**冻结复原程序**后才返回拒绝结果：

1. **再排空**：再次调用 `quiesce()`。在 `Quiescing` 态下它会**加入既有 drain**（`LedgerRuntimeOwner.kt:359-363` 保留同一 `zeroLeaseSignal`）并等待；由于 `Quiescing` 态下 `acquireLease` 拒绝新租约（`:324`），在飞 lease 只会减少，重复调用有界超时（`:195`）后在「在飞 lease 最终释放」的既有 owner 假设下收敛到 `Quiesced`。尝试次数上限与总时限归实施批（§8 第 3 项），超限（含某 lease 永久挂起致 drain 不收敛）→ 类型化失败并如实报告「运行时未复原」（见下不变量的唯一例外）。
2. **重开旧图**：`reopen(GenerationSelection.ActivePointer)`。指针从未被发布（中止路径都在步骤 7 之前），故 reopen 返回的就是**旧代**的图，`activeGeneration` 恢复非 null、state 回到 Ready（`:430-434`），业务准入恢复。并发 reopen 与本复原竞争时由 owner 互斥串行化（`TransitionInProgress` → 重试一次后如实报告）。
3. **失败出口**：reopen 失败（`ReopenResult.Failed`，`:425-428`）→ owner 已 fail-closed（`StartupError` 态），按既有启动错误面呈现，**绝不**静默重试或回退全新安装（D-176 禁令 `:3447`）。

- **不变量**：复原程序结束后，owner 处于 Ready（旧图）或 StartupError（fail-closed）二者之一，**绝不滞留 `Quiescing`/`Closed`**——唯一例外：当再排空在预算内**不能收敛**（某一在飞 lease 永久挂起，属 06.1 owner 既有语义的「挂起租约」情形，非 06.D 所造）时，owner 停在 `Quiescing`，类型化失败**如实**报告「运行时未复原」。拒绝结果的语义携带该终态。
- **与 §3.8 的关系**：指针发布后的失败走 §3.8 回滚（其收尾 reopen 即复原）；§3.10 只覆盖**发布前**的中止——两者合起来覆盖全部 post-quiesce 路径。

## 4. 持久 journal 与重启回滚（容器规格 §5.3 冻结机器的实例化）

### 4.1 journal 阶段与内容

- 文件：`LEDGER_SWITCH_JOURNAL_FILE`（`LedgerStableStorage.kt:30`，常量已在位）。阶段语义逐字承接容器规格 §5.3 `:248-251`：`prepared`（新代已完整落盘并通过校验，指针未切换）→ `switched`（指针已指向新代，新图未打开/读回）→ `committed`（新图打开并权威读回成功）。
- **写入时序（本文冻结设计级顺序）**：步骤 6 装配完成后写 `prepared` → 步骤 7 发布指针后写 `switched` → 步骤 9 读回通过后**移除** journal。崩溃窗口语义：

| 崩溃时点 | 磁盘状态 | 重启行为 |
| --- | --- | --- |
| 步骤 6 装配中途 | 无 journal；`gen-(n+1)` 半成品；指针 = 旧代 | 无 journal → 正常打开旧代；半成品目录惰性（解析只看指针，`LedgerStableStorage.kt:353-368`），清理归保留策略（§5.2/§8 第 1 项）；下一轮确认的步骤 6 按 delete-then-stage 处理（§3.6） |
| `prepared` 写入后、指针发布前 | journal=`prepared`；指针 = 旧代 | §4.3：废弃新代目录（含 sidecar）、移除 journal、正常打开旧代 |
| 指针发布后、`switched` 写入前 | journal=`prepared`；指针 = 新代 | §4.3：按 ROLLBACK 处理——republish 旧指针、移除 journal、删除新代目录（含 sidecar）、打开旧代 |
| `switched` 后、读回完成前 | journal=`switched`；指针 = 新代 | §4.3：按 ROLLBACK 处理（同上） |
| 读回通过后、journal 移除前 | journal=`switched`；指针 = 新代 | 同上——**按冻结的 ROLLBACK 规则回滚**（容器规格 `:252`），数据无损；但启动清扫已把上一会话的 `restore-*` 暂存删净（§5.7），**用户必须重新执行完整预检并再次确认**，不存在「重新确认」的捷径 |
| journal 移除后 | 无 journal；指针 = 新代 | 正常打开新代（恢复已完成） |

### 4.2 回滚的重启半部

- 进程内回滚（§3.8）与重启回滚（§4.3）是**同一条 ROLLBACK 规则**（容器规格 §5.5 第 4 点 `:271`）的两个执行者；两者动作相同：republish 旧指针（冻结原语 `:445-453`）→ 移除 journal → **删除新代目录 `gen-(n+1)`（含 sidecar；与 §3.8 同一义务，P2-3）** → 打开旧代。删除失败不阻断指针/journal 复原（尽力而为，登记于 §8 第 1 项）。
- journal 内容至少携带：阶段 + 旧/新磁盘代号。解析失败/未知阶段 → **维持 fail-closed**（不解析即拒绝的 06.1 门语义 `:343-345` 对不可识别内容保留）；具体编码归实施批。

### 4.3 启动 journal 门的 06.D 扩展（不改 D-176 契约）

- 06.1 现状：journal 存在 → `JOURNAL_PRESENT` fail-closed（`LedgerStableStorage.kt:343-345`；06.1 规格 `:242` 明述这是 06.1 时代的最小语义，完整机器归 06.D）。
- 06.D 扩展（§2.2 已声明这不是契约改动而是 06.1 登记的义务）：`resolveLedgerStorage` 的 journal 分支从「一律拒绝」扩展为「可识别的 `prepared`/`switched` → 执行 §4.1 表的重启回滚后继续正常解析；不可识别/解析失败 → 维持 fail-closed」。**静默空库禁令不弱化**（D-176 `:3447`）：journal 恢复的每条出路都以「已存在的旧指针/旧代」为锚，绝不落到全新安装。
- **替代（否决）：不写持久 journal，只靠进程内回滚**——进程在「已切换」窗口被杀/掉电时磁盘指针已指新代而无人知晓旧代，重启要么半切换要么砖化，直接违背容器规格 §5.3 `:248` 的冻结机器与计划 `:162`「切换后按 journal 回滚」。该替代不可行，非可选设计。

## 5. 设计裁决（每项含替代与决定性理由；状态：主代理已批准、待评审）

以下七项裁决在评审链（独立规格评审 + 独立质量评审 + distinct verifier）通过并由主代理批准登记前为 **main-agent-approved-pending-reviews**；登记后对实施批生效。

### 5.1 裁决 A：`ConfirmRestore` 用例（新建，app-ui，与 `RestorePreflight` 同侧）

- **设计**：输入 = 不透明 token（`RestorePreflightToken`）+ 显式用户确认；流程 = §3 的九步冻结顺序 + §3.10 复原程序；结果类型为**切换结果** sealed interface（PreviewReady 之后的世界：stale 拒绝 / 推迟拒绝 / 类型化失败 / 回滚后失败 / `RecoveryRequired` / 成功），命名归实施批。依赖注入：`owner`、`fileSystem`、`layout`、crypto（仅 SHA-256 重校验原语）、隔离库端口（仅 §5.3 恢复验证复用）、journal 原语。**不**复用 `P503UiEvent.ConfirmRestore`（回收站同名事件，`P503UiEvent.kt:780`）。
- **替代 1（否决）：把确认做进 `RestorePreflightUseCase`**——预检与确认的生命周期、并发模型（持 lease vs 排空）与失败语义完全不同；合一会让预检用例承担切换失败面，违背 D-179 已冻结的「预检零指针写入」边界（§3 `:122`）。
- **替代 2（否决）：确认时重新执行预检**——计划 `:150` 明令确认不得重新读取可被替换的外部文件（D-179 §7.2 `:386-389`）；token + 暂存重校验（§3.1）正是为此而设。
- **替代 3（否决）：就地 rename 旧代/新代目录完成切换**——多文件 rename 不是一次原子事务（容器规格 §5.3 `:247`），且旧代是回滚锚，移动即破坏回滚；指针单文件原子替换才是唯一切换原语。
- **决定性理由**：复用已评审的 owner 原语与冻结指针原语，切换窗口内业务准入由 owner 状态机结构性阻断（Quiescing/Closed 下 `acquireLease` 拒绝），回滚锚天然存在（旧代保留）。

### 5.2 裁决 B：新代编号与旧代保留

- **设计**：新目录 = `gen-(当前磁盘代 + 1)`，经 `generationDirectoryName`（`LedgerStableStorage.kt:235`）；「当前磁盘代」来自活动指针解析（§3.4），**不是**进程内 `generationCounter`（两层区分，06.1 规格 `:215`）。旧代在切换后**保留**为回滚锚（含回滚成功后的重复确认场景）。
- **成功后的旧代清理**：**本批不定义**，登记 OPEN（§8）——今日清扫只覆盖暂存前缀（`LedgerStableStorage.kt:497-499`），代目录清理需要「持久提交记录 + 新代重开校验完成」的判定（容器规格 §5.5 第 5 点 `:272`、计划 `:166`），属后续明确策略。
- **替代（否决）：复用/覆盖 `gen-当前` 目录**——就地覆盖旧代即销毁回滚锚，违背「旧代在新图打开并读回成功前保留」的计划 `:146` 语义与容器规格 §5.2 `:242`。

### 5.3 裁决 C：`POINTER_MISSING` 显式恢复（D-178 残余 (a)，`docs/DECISIONS.md:3508`）

- **设计**：**启动 fail-closed 行为不变**（`POINTER_MISSING` 仍硬失败，`LedgerStableStorage.kt:353-355`；不弱化 D-176 `:3447` 静默空库禁令）。恢复是**显式用户确认**动作，从启动失败面（`LedgerStorageFailure` 已类型化，`:277-292`）进入：当失败为 `POINTER_MISSING` 且代际目录存在时，UI 呈现「可恢复」状态；用户确认后执行**单事务恢复** = 对候选代目录逐一验证（`isUsableSqliteMainFile` `:316-328` 预检 → `PRAGMA integrity_check` 全 `ok` → 权威 `PRAGMA user_version` **恰等于 `{currentSupportedSchemaVersion()}`**（`RestoreIsolatedMigration.kt:29`））→ 仅当存在通过验证的候选（取最高代号）时经**冻结原语** `publishActivePointer`（`:445-453`）采纳 → 重启正常打开。验证与发布之间**不做**任何部分采纳（验证不过 → 不发布）。
- **版本集合的钉死（P3-6q）与 `{1, 31}` 的区分**：恢复验证集合是 `{currentSupportedSchemaVersion()}`（v31），**不是** §5.4 的接线白名单 `{1, 31}`——两者语义不同：确认切换的新代是已迁移到当前 schema 的副本（v31 天然成立）；而 **06.1 旧路径升级窗口**的候选（`stageLegacyUpgrade` 先复制原始旧库、打开时才迁移，`LedgerStableStorage.kt:413-439` + 组合根 open 闭包）可能是**完整但旧版本**的副本——它**不被采纳**（版本不符），改走下述废弃重跑分支，由真实 open 路径完成迁移，终态一致。
- **废弃重跑分支（P2-2，闭合 D-178 残余 (a) 的复制中途子情形）**：当**不存在**任何通过验证的候选时，恢复不止步于永久 fail-closed——在**旧库原件仍在**的守卫下（`legacyMainFile` 由平台解析并传入 `openStableStorageLedger`（`LedgerRuntimeOwner.kt:761-767`）与 `resolveLedgerStorage`（`LedgerStableStorage.kt:338-341`）；Android 为既有产品库位置，桌面无迁移位置、该参数为 null——06.1 规格 §3.3），向用户提供第二个**显式用户确认**动作：**废弃全部不可验证候选代目录（含 sidecar）→ 重跑 06.1 旧路径升级**（`UpgradeLegacy` 计划，`LedgerStableStorage.kt:374-377`：`stageLegacyUpgrade` → 打开并权威读回 → `publishActivePointer` → `removeLegacyFiles`，`LedgerRuntimeOwner.kt:797-810`）。守卫与理由：
  - **守卫 1（原件在盘）**：仅当 legacy 主文件仍存在且过 `isUsableSqliteMainFile` 守卫才提供该分支——D-178 缺陷 2 的部分复制砖化场景中旧库原件**必然还在**（`removeLegacyFiles` 只在指针发布成功后运行，`LedgerRuntimeOwner.kt:810`；「代际目录存在即只看指针」的规则 `LedgerStableStorage.kt:349-352` 使旧位置在无指针时不可达，本分支正是重建这一可达性的显式出口）。
  - **守卫 2（绝不自动）**：与采纳分支相同的人审闸门；拒绝确认 → 维持 fail-closed（不弱化 D-176 `:3447`）。
  - **非静默空库**：重跑升级从**用户的真实旧库**重建，不是全新安装；静默空库禁令的分母（无代际目录且无旧位置）未被触碰。
  - **为何选择扩展而非收窄（两评审给出的二选一）**：收窄式登记（把复制中途窗口留 Open）会把一个**已有充分守卫手段可闭合**的用户可恢复缺陷留砖，且 D-178 `:3508` 的承接义务是「消除砖化」，不是「消除 ANR 触发」；扩展分支只新增用户确认动作，不新增任何自动行为。
- **替代 1（否决）：启动时自动/静默采纳无指针候选代**——直接削弱 D-176 静默空库禁令的 fail-closed 精神：把「指针缺失」从「必须人审的状态」变成「可自动绕过」，半成品代（部分复制，D-178 缺陷 2 实证 `:3492`）会被静默采纳；确认式恢复保留人审闸门。
- **替代 2（否决）：维持不可恢复（06.1 现状）**——D-178 `:3508` 已把残余 (a) **承接至 06.D**，不承接即静默丢弃登记项，违反承接纪律。
- **替代 3（否决）：把完整但版本不符的候选（升级窗口的原始旧库副本）直接采纳**——绕过权威 open 路径的迁移与读回，等于用恢复路径复刻一份「未走正规升级」的库；废弃重跑让同一数据经唯一权威路径（open 闭包内的迁移 + 权威读回）落盘。
- **恢复路径与 §3 的关系**：恢复产物是一个有效指针；恢复不创建新代、不迁移、不读容器——它只让既有代重新可达（或经用户确认重跑升级），与确认切换互不依赖。

### 5.4 裁决 D：`supportedSourceVersions` 接线白名单集合 = `{1, 31}`

- **设计**：组合根（P2-7 接线，§6）注入 `Set<Long>` 字面量 `{1, 31}`：v1 是**有条件准入**——其依据目前只有**设备实证的严格迁移 + 校验**（`AndroidFrameworkSqlDriverInstrumentedTest.aSupportedV1FixtureStrictMigratesOnDeviceThroughTheAdapter`，`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidFrameworkSqlDriverInstrumentedTest.kt:165`；D-180 设备门 `docs/DECISIONS.md:3554` 五项含「v1 fixture 单事务严格迁移 1→current」）；逐版本门要求的**往返等价腿（A04）对 v1 尚未证明**，仍归 06.4（下条边界）——故 v1 的准入是**待 A04 闭合前的有条件准入**，A04 若证伪则该版本必须从集合移除。v31 = 当前 schema（预检无迁移直通，`RestorePreflight.kt:460-463`）。**其余**每个来源 `user_version` → 类型化拒绝（`RestorePreflight.kt:429-431` 的冻结规则路径），待其**逐版本门**：每个新版本加入集合须自带该版本的往返证据（严格迁移 + 校验 + 往返等价），这是 D-179 §10 第 1 项「由另立的严格结构识别/迁移门确定」纪律在接线集合上的实例化。
- **与 06.4 的边界**：本文裁决的是**接线集合**（D-180 `:3563` 归 06.D）；A04 的完整往返等价验收仍归 06.4，本文不标 PASS。
- **替代 1（否决）：仅 `{31}`**——把设备已实证的 v1 拒之门外，白费已取得的设备证据，且「恢复旧备份」是本切片的核心用户价值。
- **替代 2（否决）：注入全部历史版本 `1..31`**——v2..v30 无任何严格迁移证据（迁移链存在 `1.sqm`～`30.sqm` 不等于每级经恢复路径验证），盲注入把未验证迁移面暴露给外来库，违背逐版本门纪律。
- **替代 3（否决)：维持 OPEN 不接线**——P2-7 接线（D-180 `:3563`）正是因集合 OPEN 而延后；本批是解除该延后的时机，且注入式设计（`RestorePreflight.kt:153`）保证集合更新零代码面变化。

### 5.5 裁决 E：预览字段集（D-179 §10 第 13 项的具体化）

- **最终字段集**（在 `RestorePreflightSummary` 现有字段 `RestorePreflight.kt:165-183` 之上补最后一组）：
  1. 容器格式版本 + 容器大小（`:166-167`）；
  2. 权威迁移前 `user_version` + 迁移后版本（`:168-171`）；
  3. 来源账本身份 + 固定目标身份（`:172-174`）；
  4. 认证工件摘要（`payload_sha256` 的 hex 显示形式，`:175-176`）；
  5. integrity/FK/domain 结果（`:177-179`）；
  6. **关键 owner 计数摘要：accounts / categories / transactions 计数**（**06.D 新增义务**：经隔离库端口从迁移副本直读权威计数，不运行 seed bootstrap；计划 `:154`「明确替换确认」与 06.C 预检规格 §7.4 `:411` 的依据；现有 `ledgerIdentityCount`/`formalTableCount`/`postingImbalanceCount`（`:180-182`）是校验计数，**不能替代**用户可核对的 owner 计数）；
  7. 预检时间戳（用户核对「我何时预检的」）。
- **排除项**（verbatim 承接 D-179 §7.4 `:412`）：密码、派生密钥、明文账务内容明细、外部文件路径或本机绝对路径。
- **替代（否决）：以校验计数 + formalTableCount 充当计数摘要**——「8 张正式表存在」对用户无可核对性，「将替换 1,234 笔交易 / 56 个账户」才有「明确替换确认」的语义；且该数据在迁移副本上一次 SELECT 可得，无新增面。

### 5.6 裁决 F：`sizeOf` 接线复检（D-180 5(d)，`docs/DECISIONS.md:3563`）

- **设计**：`AndroidBackupSourcePort` 的 `sizeOf`（现默认 `{ null }`，`AndroidRestorePreflightPorts.kt:50`）接线为对 SAF `OpenDocument` 结果的**单次** `ContentResolver` 大小查询（`OpenableColumns.SIZE`）；查询返回 null → 计数流回退（已在 06.C 实现并有测试：报告值超限读前拒绝 `RestorePreflight.kt:593-597`、计数回退 `:621-625`）。桌面端已报大小（`DesktopRestorePreflightPorts.kt:53`/`:64`），不动。
- **理由**：避免「为拒绝一个超限容器而读完整 2 GiB」——provider 报大小路径是容器规格 §4.8 `:197`「不读取」的精确实现（D-179 §3.1 `:143-146`）；单次查询成本可忽略。**计数回退保留**意味着谎报大小的 provider 仍无法绕过上限（D-179 §3.1 `:146` 的登记语义）。
- **替代（否决）：维持 `sizeOf = { null }`**——Android 端永远走计数流，每个超限拒绝都要读满上限字节，纯浪费且拉长用户等待。

### 5.7 裁决 G：跨会话 stale 策略（D-179 §10 第 12 项的既定化）

- **设计**：**D-179 §7.5 第 1 项（存在性检查）就是执行机制**，不新增任何机制。依据链：暂存在取消/会话结束/下次启动清理（D-179 §6.5 `:366`）；06.C 已把 `restore-` 前缀纳入启动清扫（`LedgerStableStorage.kt:62`、`:497-499`，调用点 `LedgerRuntimeOwner.kt:777`）；故**上一会话的 token 所绑定的暂存工件在下次启动必被清扫**，确认时第 1 项必失败 → stale 拒绝（§3.1）。同会话内工件被杀进程破坏的情形同样由第 1 项拒绝。
- **替代（否决）：为 token 增加会话标识/有效期字段**——冗余：存在性检查已隐式覆盖全部「工件不再可信」情形（清扫、损坏、删除）；新增字段引入「token 自我声称有效性」的反模式，与「确认不信任 token」的 D-179 §7.5 立场（`:417`）相悖。
- **本裁决只是把 D-179 §10 第 12 项（`:497`「确切策略归实施批」）既定化为「无新机制」**；D-179 §7.2/§7.5 的硬要求原文不变。

## 6. 平台端口与组合根接线（P2-7）

- **commonMain 无新增平台端口**：确认流程消费 §1.1/§1.2 已有原语；唯一新增共享面是 `ConfirmRestore` 用例与其结果类型、journal 原语（读写/移除经 `LedgerFileSystem` 既有 `readBytes`/`writeAtomic`/`delete`，`LedgerStableStorage.kt:86`/`:104`/`:114`，文件名用既有常量 `:30`）。
- **组合根（两端）**：
  - 构造 `RestorePreflightUseCase`（`RestorePreflight.kt:252-260`）与 `ConfirmRestore`，注入 `supportedSourceVersions = {1, 31}`（§5.4）、`currentSchemaVersion = currentSupportedSchemaVersion()`（`RestoreIsolatedMigration.kt:29`）、加密原语、来源端口（§5.6 后 Android 端带 sizeOf）、隔离库端口。
  - **预检与确认均派发后台线程**（容器规格 §4.8 `:210`）；`quiesce` 是 suspend（`LedgerRuntimeOwner.kt:351`），确认协程在其上调用；结果经主线程 hop 落地并做 generation 守卫（§3.9）。
  - 共享 UI 呈现预览摘要（§5.5 字段集）与明确替换确认（计划 `:154`），不接触文件路径、driver 或密码日志（计划 `:152`）。
- **Android 启动失败面**：`StartupError` 呈现类型化 `LedgerStorageFailure`（`:277-292`）；`POINTER_MISSING` + 代际目录存在 → 暴露 §5.3 的用户确认恢复入口；journal 恢复（§4.3）在启动路径内完成、UI 只见恢复结果。
- **测试形态（设计级，归实施批）**：JVM 矩阵见 §7；设备 instrumented 确认测试沿用 06.C 的设备模式（受管模拟器、隔离 adb、`ContextWrapper` 重定向测试目录、绝不触碰生产库——D-180 设备门先例，`docs/DECISIONS.md:3554`）。

## 7. 验收映射（P706-A03 端到端 / A05）

下表把本切片承接的向量逐条映射。**本规格不把任何向量标为 PASS**——全部为未来验收要求，尚未执行（计划 `:194`）。

| 验收 ID | 计划要求（verbatim，计划 `:183`/`:185`） | 归属 | 06.D 交付（本文冻结的设计级不变量） |
| --- | --- | --- | --- |
| P706-A03（端到端切换半部） | 「类型化拒绝，当前库/活动指针均不变」 | 06.D（D-180 `:3563`；06.C 交付其容器级半部，06.C 规格 §9 `:477`） | **指针在预检全程不变**（预检零指针写入，D-179 §3 `:122`）；**确认时恰好一次切换**（§3 步骤 7 单次发布）；**stale / 推迟 / 发布前失败 → 指针字节不变**；**发布后失败 → 回滚恢复旧指针字节**（§3.8）；**旧代保留至成功**（§5.2） |
| P706-A05 | 「零切换或 stale 拒绝，不能恢复未预览工件」 | 06.D | §3.1 四项重校验任一失败 → stale 拒绝零切换；跨会话 token 必 stale（§5.7）；未预览工件（无 token）无确认入口 |

**JVM 测试矩阵（设计级，逐项对应 §3 失败路径，全部以「指针字节不变/恢复旧指针字节」为断言锚）**：四项重校验各一失败注入 → 指针字节不变；**步骤 2 空间不足注入 → 类型化拒绝、指针不变**；**步骤 2 未知 `usableSpace`（fake `LedgerFileSystem` 返回 null）→ fail-closed 拒绝、指针不变**；quiesce 超时（注入持 lease 的占用者）→ 推迟拒绝、指针不变、**owner 经 §3.10 复原回 Ready**；quiesce 后 generation 推进注入 → stale、**owner 经 §3.10 复原回 Ready**；close 被阻注入 → 推迟、指针不变、**owner 经 §3.10 复原回 Ready**；新代装配失败注入 → 零切换、半成品清理（含 sidecar）、**owner 经 §3.10 复原回 Ready**；happy path → 指针 = `gen-(n+1)`、journal 移除、旧代仍在；发布后 reopen 失败注入 → 回滚后指针 = 旧代、journal 移除、旧图重开、**新代目录（含 sidecar）已删除**；回滚也失败注入 → fail-closed 恢复态、不循环初始化；**遗留 `gen-(n+1)` 目录下重试 → 步骤 6 delete-then-stage 成功装配（§3.6）**；journal 各崩溃窗口（§4.1 表逐行）的重启行为（`switched` 窗口各行断言新代目录已删）；`POINTER_MISSING` 恢复：候选验证通过 → 指针发布、验证不过且无 legacy 原件 → 保持 fail-closed、**验证不过且有 legacy 原件 → 用户确认后废弃候选并重跑旧路径升级（§5.3）**。
**设备 instrumented**：确认切换端到端（复用 06.C 设备模式）；恢复面（`POINTER_MISSING` → 用户确认恢复 → 正常启动；复制中途砖化 → 用户确认废弃重跑 → 旧库数据回归）。

## 8. 明确登记为未关闭/未验证的项

本规格**不**关闭以下任何一项；它们或属后续切片、或归实施批、或需另立门/决定。与兄弟规格同一纪律：不得静默丢弃，也不得当作已达成。**先逐项处置 D-179 §10（`docs/specs/2026-09-25-p7-06-restore-preflight-design.md:482-501`）的 16 项，再登记新项。**

**D-179 §10 逐项处置**：

1. **受支持旧 schema 白名单集合与严格结构识别**（`:486`）：**部分处置**——接线集合 `{1, 31}` 由 §5.4 裁决（逐版本门纪律随附）；「严格结构识别」（历史无戳库的识别门）与 A04 往返等价仍 OPEN，归 06.4（D-180 `:3563`）。
2. **§4.6 AAD 构造跨端向量**（`:487`）：**承接**（继承 D-174 `:3409` 的 OPEN；与 06.D 无关，不声称已验证）。
3. **新拒绝码建议名（需批准）**（`:488`）：**承接**——06.C 实施已按「建议名非冻结」落地（`BackupContainerReader.kt:34-37`）；冻结与否属命名批准事项，本批不占用码空间（§3.7），不改变其状态。
4. **`P706_PAYLOAD_INTEGRITY_FAILED` 是否强制**（`:489`）：**已由 06.C 实施闭合**——实施为强制检查 + 类型化拒绝（`RestorePreflight.kt:409-410`），D-180 登记在案（`:3550`）。
5. **2 GiB 上限 vs provider 不报大小**（`:490`）：**实施已闭合，残余承接**——计数流回退已实现并有覆盖（§1.3 第 4 行）；provider 元数据可靠性的设备侧读数未取，随 §5.6 接线后的设备验收补证（§8 新项 6）。
6. **领域完整性/FK/关系校验面**（`:491`）：**实施已建面，设备 stated gap 承接**——`foreignKeyCheckOn`/`validateDomainOn` 已交付（`RestoreIsolatedMigration.kt:136`/`:311`）；设备上经 `AndroidFrameworkSqlDriver` 的 `PRAGMA integrity_check`/`foreign_key_check` 为 D-180 第 5(c) 条 stated gap（`:3562`），在 §5.3 恢复验证与确认流程上设备验收时一并补证。
7. **读侧明文上限/自洽等式/截断拒绝码名**（`:492`）：同第 3 项，**承接**。
8. **Android 读写 driver 面**（`:493`）：**已由 06.C 实施闭合**——`FrameworkSQLiteDatabase` 实测不在编译类路径，实施采用候选 2 自定义适配器（`AndroidRestorePreflightPorts.kt:145-155` 的 `openAndroidReadWriteDriver`；D-180 `:3550`、`:3554` 设备门 5/5）。
9. **06.C/06.D 磁盘公式拆分**（`:494`）：**由本规格处置**——拆分确认见 §3.2（两个时点、并集覆盖五项）；确认峰值检查的实际执行与读数归实施批。
10. **有界流式来源端口具体类型/包/超时**（`:495`）：**已由 06.C 实施闭合**——`BackupSourcePort`/`BackupSourceReader`（`RestorePreflight.kt:49-77`）、Android 10 分钟有界等待（`AndroidRestorePreflightPorts.kt:31`）。
11. **sweep 前缀扩展**（`:496`）：**已由 06.C 实施闭合**——`restore-` 前缀在位（`LedgerStableStorage.kt:497-499`）。
12. **token 会话边界与 stale 策略**（`:497`）：**由本规格处置**——§5.7 既定化（存在性检查即机制，无新代码）。
13. **预览摘要确切字段集**（`:498`）：**由本规格处置**——§5.5 七项字段集（含新增 owner 计数摘要的实现义务）。
14. **持 lease 替代方案（登记不采用）**（`:499`）：**由本规格处置**——确认流程明确不持 lease（§3.3）、不采用 sweep 守卫替代；D-179 的「不采用」结论被确认流程复核为正确。
15. **持 lease 期间切换被推迟的时长**（`:500`）：**承接**——预检实测时长未取；归规模评估（与 P706-A12 相关联，容器规格 §7 `:316`）。
16. （该规格自指条目，无承接义务。）

**D-180 残余逐项处置**（`docs/DECISIONS.md:3558-3563`）：5(a) ImportScaleTraversal 冷启动 a11y 环境限制——**承接**（与 06.D 无关，专项调查待办）；5(b) Error 清扫一致性（`RestoreIsolatedMigration.kt:110`/`BackupExport.kt:263` 等位点）——**承接**（未来一致性清扫，不归本批）；5(c) 设备 PRAGMA stated gap——**承接**（见上第 6 项）；5(d) 五项——P2-7 接线（§6）、白名单集合（§5.4）、preview 字段集（§5.5）、A03 端到端（§7）、sizeOf 复检（§5.6）**全部由本规格承接为设计裁决**，实施归后续实施批。

**本规格新增的未关闭/未验证项**：

1. **成功后旧代清理策略**（§5.2）：判定条件（持久提交记录 + 新代重开校验完成）、清理器与「未解决 journal 的相关代不得清理」（容器规格 §5.5 第 5 点 `:272`、计划 `:166`）归后续策略批；**已废弃新代目录的删除失败遗留**（§3.8/§4.2 回滚中尽力而为删除的残余）亦归同一策略批一并处置（N-2 的精确路由）。
2. **journal 内容编码与启动恢复的具体状态机**（§4）：阶段字段的字面编码、`prepared` 与 `switched` 恢复路径在 `resolveLedgerStorage` 内的确切编排、与 `withAndroidStableStorageOpenLock` 的交互，归实施批。
3. **§3.10 复原程序的编排细节**：再排空的尝试次数上限/总时限、并发 reopen 竞争下的重试策略、以及 `Quiescing` 态下 `closeActiveGraph` 衔接的状态机核实（`LedgerRuntimeOwner.kt:351-404`）归实施批；设计级约束已冻结：**任何 post-quiesce 中止的终态只能是 Ready（旧图）、StartupError，或——仅当再排空在预算内不能收敛（挂起租约，06.1 owner 既有受阻语义）时——`Quiescing` 且类型化失败如实报告未复原**，绝不无声滞留 `Quiescing`/`Closed`（§3.10）。
4. **确认/切换/回滚的实测时长**：quiesce 排空 + 新代落盘 + reopen 的规模读数未取（61k 级库），归实施批与规模评估。
5. **`RecoveryRequired` 持久形状与 UI 呈现**（§3.8/§5.3）：fail-closed 恢复态下指针/journal/新代目录去留的磁盘不变量与用户面呈现归实施批。
6. **Android provider 大小元数据的设备侧读数**（§5.6 接线后）：SAF provider 报大小/不报大小的真实分布未取。
7. **切换期结果类型与复原状态的命名**（§3.7/§5.1）：stale/推迟/失败/回滚/`RecoveryRequired`/复原完成各变体的具体类型名与「运行时已复原/fail-closed」语义字段的编码归实施批；不占用 `P706_*` 容器拒绝码空间（§3.7）。
8. **本规格自身**：状态 `proposal`；须经独立规格评审、独立质量评审与 distinct verifier，并由主代理批准登记（新决定条目）后才可进入实施批。本条不构成任何产品行为、迁移、技术选型或发布授权。

**D-178 残余处置（`docs/DECISIONS.md:3506-3510`）**：(a) **`POINTER_MISSING` 砖化窗口——由本规格闭合（设计级）**：复制完成子情形经 §5.3 验证采纳，复制中途子情形经 §5.3 废弃重跑分支（用户确认 + legacy 原件守卫）；实施与设备验证归实施批；(b) `closeActiveGraph` 尽力而为——**承接**（D-176 契约不变；§3.5 的 typed 路径消费其现有语义）；(c) 进程级打开锁无超时——**承接**（与 06.D 无关）。

## 9. 非目标与边界（no-touch）

- **`.external/` 只读**：本批未触碰、未编辑、未清理、未改名、未删除 `.external/` 任何内容。
- **零产品代码、零测试**：本批只新增本设计文档；不改任何 `.kt`、测试、构建脚本或清单。
- **零 schema/迁移/依赖变更**：schema 停留 **v31**，迁移链 `1.sqm`～`30.sqm` 零改动；不新增依赖。
- **不改容器格式**：不改容器格式规格（D-174）的任何冻结字节布局、KDF/AEAD 参数、AAD 构造、拒绝码、资源上限、§5.3 journal 机器与 ROLLBACK 重启规则；若未来确需放宽，必须重开 D-174 并取得显式批准。
- **不改 owner 契约**：不改 06.1 规格（D-176）的 `LedgerRuntimeOwner`/lease/generation/quiesce 端口签名与 fail-closed 语义；只消费。启动 journal 分支的扩展是 06.1 规格自身登记的 06.D 义务（其 `:242`、`§6 :285`），不是契约改动。
- **不改 D-156/D-158 及既有冻结面**：不改 D-156/D-158/D-174/D-176/D-177/D-179/D-180 与 P7-01～P7-05 既有冻结面；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动。
- **不实现**：确认用例、journal 机器、恢复流程、组合根接线、UI 面的全部产品代码与测试（实施批）；06.4 的往返等价（A04）。
- **不声称**：不声称任何 P706 向量 PASS（§7）；不声称云备份/设备迁移已关闭（§2.2）；不声称安全擦除物理扇区（容器规格 §4.9 `:219`）；不把任何 OPEN 项当作已达成。
- **隐私**：本文不复制任何真实金额、时间、锚点注册值或个人数据；示例全部匿名合成；tracked 文件不含本机绝对路径、临时研究或工具轨迹（`docs/CONTRIBUTING.md:171`）。
