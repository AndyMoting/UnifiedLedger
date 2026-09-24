# P7-06 备份与恢复 06.2 / 06.B 设计规格：一致快照、认证加密导出与取消/空间不足处理

状态：approved（2026-09-24 起草、2026-09-24 批准；本文是 06.2 / 06.B 导出切片的**设计规格**，已经**两轮独立规格评审（首轮 APPROVE WITH FINDINGS，含 1 项 P2 写序矛盾 + P2/P3；修订后的闭包复评 APPROVE WITH FINDINGS，全部条目已闭合）**，并**由 `docs/DECISIONS.md` D-177 批准（本决定即批准依据）**，故按 `docs/CONTRIBUTING.md:162` 的允许分类标 `approved`。本文**不**构成产品行为、迁移、技术选型或发布授权；它**不改动**任何已冻结字节、参数、拒绝码或既有决定，实施属后续实施批。）

**Revision:** draft-3（2026-09-24；在 draft-2 基础上修正两处事实/单位陈述与两处交叉引用：① 绑定上限字节数按同句算式 `plaintext_len + 59 + salt_len + iv_len + tag_len` 更正为 **103** 字节（59 + 16 + 12 + 16；原误作 75，即 59 + salt_len 的 AAD 长度），结论 103 ≪ 2 GiB 不变；② 后置门依据不再声称快照产物「略大于源」，改为「快照大小**不保证**等于源大小」，并按门证据实数引述（源 371,658,752 B ≈ 354 MiB → 快照产物 355,721,216 B，产物按字节数更小）；③ 指向私有暂存章节的交叉引用由 §5/§4 更正为 §6。draft-2 的内容不变（应用独立规格评审的 APPROVE WITH FINDINGS 全部条目：P2-1 `payload_sha256` 写序、P2-2 写侧 1 GiB 明文上限门、P2-3 有界流式写端口、P3-1 `integrity_check` 执行面登记、P3-2 A02 的 WAL 证据口径、P3-3～P3-5 引用精度）。本文已完成两轮独立规格评审（首轮 APPROVE WITH FINDINGS，含 1 项 P2 写序矛盾 + P2/P3；修订后的闭包复评 APPROVE WITH FINDINGS，全部条目已闭合），并由 `docs/DECISIONS.md` D-177 批准，故状态为 `approved`。基线 = 本 worktree 分支 `UL-p7-06sb`，基点 `d6cffb6`（P7-06 06.1 稳定存储与 `LedgerRuntimeOwner` 实施合并点，D-176）；schema **v31**，迁移链 `1.sqm`～`30.sqm`（30 个文件，v1→v31）。tracked 行号为该基点在本 worktree 的实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 与 `local/artifacts/p7-06-gate/` 以主 checkout 为准、只读。示例与向量全部匿名合成；不粘贴大段产品代码，不写本机绝对路径。本文**不**新增决定条目、**不**修改任何既有决定。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为当前 worktree 基点 `d6cffb6` 实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读）：

- **已批准的容器格式规格（承重权威，D-174）**：`docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（approved）：
  - §4.3（`:94-113`）：冻结的容器字节布局（固定头部 59 字节 + salt + iv + ciphertext + tag）。
  - §4.3.1（`:117-132`）：固定头部类型化拒绝码；§4.3.2（`:134-136`）：`db_schema_version` 为非权威提示。
  - §4.4（`:138-156`）：PBKDF2WithHmacSHA256 参数、salt、迭代上下界、256-bit 派生密钥、**冻结的密码加宽编码**。
  - §4.5（`:158-163`）：AES-256-GCM、96-bit IV、128-bit tag、单密钥单次调用、AAD 先供给。
  - §4.6（`:165-176`）：`AAD = 固定头部(0..58) || salt` 的无歧义构造。
  - §4.7（`:178-191`）：统一安全失败（无 oracle）与三类结构检查时机。
  - §4.8（`:193-212`）：资源上限、64 KiB 流式、导出侧磁盘公式、线程要求。
  - §4.9（`:214-220`）：明文隔离与生命周期；§4.10（`:222-226`）：默认加密与密码策略。
  - §6（`:278-295`）：技术门表；§7（`:299-318`）：P706-A01/A02/A08/A10 等向量的切片归属。
  - **本文实现其 §4 的格式，逐字节不改**；只补充**写入端**（导出）的数据流、有界 IO、暂存生命周期与取消/空间不足行为。
- **已批准的 06.1 规格（承重权威，D-176）**：`docs/specs/2026-09-24-p7-06-stable-storage-runtime-owner-design.md`（approved）：
  - §4.1（`:128-135`）：`LedgerRuntimeOwner` 责任；§4.2（`:137-171`）：端口签名形状（`acquireLease`/`quiesce`/`closeActiveGraph`/`reopen`）；§4.3（`:182-211`）：每一个 `facade.*` 入口点必经 lease。
  - §3.1（`:88-92`）：目录解析规则；§3.2（`:93-111`）：Android 稳定存储与旧路径升级；§3.3（`:113-119`）：桌面稳定存储。
  - §6（`:276-289`）：06.1 失败向量与回归面。
  - **本文的导出必须经该 owner/lease**（§2 数据流），不改其契约。
- **阶段计划（本地只读，主 checkout）**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`：
  - §6 子项表（`:133-138`；`06.B 导出` 行 `:136`）。
  - §6.1（`:140-156`；Q13 推荐 `:142`、Q14 `:146`、层责任表 `:150-154`、稳定存储前置 `:156`）。
  - §6.2（`:158-166`；**导出数据流 `:160`**、技术门 `:164`、清理策略建议 `:166`）。
  - §6.3（`:168-194`；**切片 `06.2 / 06.B` 行 `:174`**、验收向量 P706-A01/A02/A03/A08/A09/A10 于 `:181-190`）。
  - 该计划文件的「推荐/建议」均为 proposal，不构成产品行为、迁移、技术选型或发布授权（计划 `:70`、`:460`）。
- **决定**：`docs/DECISIONS.md` D-174（`:3397` 起：批准容器格式规格与 Q13/Q14 设计级方案）、D-176（`:3436` 起：批准 06.1 稳定存储与 `LedgerRuntimeOwner` 设计）。本条只**承接**其已冻结裁决，不修改任何一条。
- **开发规范**：`docs/CONTRIBUTING.md:159-166`（正式文档以中文为主、代码标识符保留英文；不得含本机绝对路径、个人账务数据或临时讨论记录）、`:162`（新建/实质修改的 `docs/specs/` 设计必须标记 `approved`/`proposal`/`superseded`/`historical`）。
- **技术门证据（本地只读，主 checkout）**：`local/artifacts/p7-06-gate/q13-driver-snapshot-gate.md`（两端 `VACUUM INTO` 实证，含 61k 大库读数）、`local/artifacts/p7-06-gate/q13-crypto-official-evidence.md`（AES-GCM / PBKDF2 官方参数依据与跨端字节兼容实证）。
- **源码现实（逐条复核，file:line）**：见 §1。
- **不可触碰面**：`.external/` 只读；零产品代码、零测试、零 schema/迁移、零依赖；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动；**不改容器格式规格的任何冻结字节布局、参数值或拒绝码**；不改 D-156/D-158/D-174/D-176 及既有 P7-01～P7-05 冻结面。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 owner / lease 已就绪：导出必须经它取租约

| 事实 | 位置 | 对 06.B 的含义 |
| --- | --- | --- |
| 单活动运行时 owner | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/LedgerRuntimeOwner.kt:209`（`class LedgerRuntimeOwner<G : Any>`） | 导出期间不得被 `close`/`reopen` 打断，故须持一个 operation lease |
| 取租约端口（非挂起、非阻塞） | `LedgerRuntimeOwner.kt:321-331`（`acquireLease()`，`tryLock`；非 Ready 返回 `LeaseAcquireResult.RuntimeNotReady`） | 导出用例在 Ready 时取租约；非 Ready 时返回类型化「未就绪」，不得阻塞 UI 线程 |
| 租约携带 generation 且幂等释放 | `LedgerRuntimeOwner.kt:179-192`（`class LedgerLease`）、`:333-342`（`releaseLease`） | 导出完成/取消/失败后必须在 `finally` 释放；释放时机晚于业务调用返回（计划 `:153`） |
| quiesce / close / reopen 的在飞前置 | `LedgerRuntimeOwner.kt:351-379`（`quiesce()`）、`:392-404`（`closeActiveGraph()`）、`:413-439`（`reopen()`） | 导出租约在飞时，切换类操作返回类型化 `QuiesceBlocked`，不会提前关闭连接 |
| 租约作用域 choke point | `LedgerRuntimeOwner.kt:493-654`（`class LedgerLeaseScope`）、`:619-631`（`withFacade`）、`:634`（`probe`）、`:641-653`（`leased`） | 导出用例须走同一作用域形状（取租约 → 取 `facade`/受控入口 → 释放），不得绕过 lease |
| 原始 facade 为模块私有 | `LedgerRuntimeOwner.kt:269`（`internal val facade`） | 组合根拿不到裸 facade；导出所需的数据层入口必须像既有受控入口那样经 graph/facade 暴露 |
| 旧结果按 generation 丢弃 | `LedgerRuntimeOwner.kt:465-468`（`shouldDiscardLandingResult`） | 导出的进度/结果回落到 UI 时须校验捕获的 generation 仍为当前活动代 |

### 1.2 稳定存储与文件系统端口：缺少导出所需的三个操作

| 事实 | 位置 | 06.B 必须补什么 |
| --- | --- | --- |
| 平台无关文件端口 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/LedgerStableStorage.kt:50-104`（`interface LedgerFileSystem`） | 现有 `join`/`exists`/`isDirectory`/`length`/`readBytes`/`readPrefix`(`:71-74`)/`writeAtomic`(`:81-84`)/`copy`(`:86-89`)/`delete`(`:91`)/`createDirectories`(`:93`)/`fsyncFile`(`:96`)/`fsyncDirectory`(`:103`)。**无**「向用户目标的有界写流」「可用空间查询」「容器级临时文件 + 原子 rename」 |
| 稳定存储布局 | `LedgerStableStorage.kt:106-128`（`LedgerStorageLayout`；`hostDirectory` `:110`） | 私有暂存的父位置可解析自该宿主目录；字面命名归实施批（容器规格 §5.2 只冻结语义） |
| Android 适配器 | `android-app/src/main/kotlin/com/unifiedledger/android/AndroidLedgerFileSystem.kt:22`（类）；`readPrefix` `:36-46`；`writeAtomic`（`AtomicFile`）`:48-64`；`copy` `:66-74`；`delete` `:76-80`；`createDirectories` `:82-84`；`fsyncFile` `:86-88`；`fsyncDirectory` 空实现 `:90-94` | 同上：**无**有界写流、**无**可用空间查询（Android 需 `StatFs`/`File.usableSpace` 类原语，属实施批） |
| 桌面适配器 | `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopLedgerFileSystem.kt:19`（类）；`readPrefix` `:33-43`；`writeAtomic`（临时文件 + 原子 rename）`:45-70`；`fsyncFile` `:90-92`；`fsyncDirectory` 尽力 `:94-100`；宿主解析 `:118-134` | 桌面 `Files.move(ATOMIC_MOVE)` 已在 `writeAtomic` 内；**无**有界写流、**无**可用空间查询（桌面需 `FileStore.getUsableSpace()` 类原语，属实施批） |
| 全仓零可用空间 API | 对产品源码检索 `getUsableSpace`/`StatFs`/`usableSpace` **零命中**（本基点实读） | 导出侧磁盘前置（容器规格 §4.8）当前**无法**执行，06.B 必须先补该原语 |

### 1.3 数据库句柄：driver 私有，只有受控入口先例

| 事实 | 位置 | 06.B 的含义 |
| --- | --- | --- |
| Android 句柄工厂 | `ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:9-19`（`createAndroidLedgerDatabase(context, name)`） | 导出快照须在该受控连接上执行 |
| driver 私有 | `AndroidLedgerDatabaseHandle.kt:71-85`（`class AndroidLedgerDatabaseHandle`，`private val driver` `:84`）；`close()` `:86-88` | 组合根**拿不到** driver；不能从外部对 driver 发 `VACUUM INTO` |
| 受控入口先例 | `AndroidLedgerDatabaseHandle.kt:101-103`（`runQueryStatisticsOptimize()`）、`:119-121`（`runFullAnalyze()`） | 06.B 须按**同一形状**在句柄上新增一个受控快照入口（如 `runSnapshotInto(target)`），委托到 commonMain 的 driver 级 helper |
| commonMain driver 级 helper 先例 | `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/QueryStatisticsOptimize.kt:55-71`（`runQueryStatisticsOptimizeOn(driver)` 走 `executeQuery`）、`:81-87`（`runFullAnalyzeOn(driver)` 走 `driver.execute`，行少语句） | `VACUUM INTO` 为**行少语句**，按该文件 `:39-50` 的两侧披露，Android 走 `driver.execute`、JDBC 亦走 `driver.execute`；具体 binder 路径尚未实证（见 §3.3 未验证项、§9 第 4 项） |
| Android 未配置 WAL / busy_timeout | `AndroidLedgerDatabaseHandle.kt:139-148`（`ForeignKeysCallback.onConfigure` 只设 `setForeignKeyConstraintsEnabled(true)`；注释明述不设 `busy_timeout`） | 与门证据一致：Android 源库 `journal_mode=delete`（`q13-driver-snapshot-gate.md`），快照机制不依赖 WAL |
| 桌面 JDBC 打开 | `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:805-806`（`openDesktopLedger` → `JdbcSqliteDriver(databaseUrl)`）；`:855`（`migrateToCurrentSchema`）；`:944-987`（`hasTable`/`writeUserVersion`/`readUserVersion` 的 raw-exec 先例） | 桌面同样 driver 私有于组合根；raw `execute`/`executeQuery` 已有先例（`driver.execute(null, "PRAGMA ...", 0)`） |

### 1.4 组合根与接线

| 事实 | 位置 | 06.B 的含义 |
| --- | --- | --- |
| Android 组合根 | `android-app/src/main/kotlin/com/unifiedledger/android/App.kt:221`（`AndroidStartupController`）、`:236-241`（owner 构造）、`:316-323`（`CloseableLedgerGraph`：`facade`/`close`/`catalogSession`/`catalogCommands`/`runQueryStatisticsOptimize`/`runFullAnalyze`）、`:337-368`（`openAndroidStableStorageLedger`）、`:361`（`createAndroidLedgerDatabase(context, name)`）、`:398`（`LedgerId("ledger-local-test")`）、`:761-763`（`secureRandom`/`secureRandomBytes`） | graph 不暴露 driver 或路径；导出端口须新增到 graph 并经 owner 的 lease 作用域暴露给共享 UI |
| 桌面组合根 | `Main.kt:167`（`main()`）、`:168-169`（`DesktopLedgerFileSystem()` + `ledgerStorageLayout(resolveDesktopHostDirectory())`）、`:264-278`（`DesktopStartupController` 与 owner）、`:339-353`（`CloseableLedgerGraph`）、`:829-843`（`openStableStorageDesktopLedger`） | 同上 |
| 稳定存储共享序列 | `LedgerRuntimeOwner.kt:717-771`（`openStableStorageLedger`）、`LedgerStableStorage.kt:199-211`（`isUsableSqliteMainFile`）、`:221-265`（`resolveLedgerStorage`） | 导出不改变启动路径；只读取活动代主文件所在连接 |
| 平台文件选择端口先例 | `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/ImportFilePick.kt:112`（`interface ImportFilePickPort`，commonMain 只有闭包形状、无 `java.io`）、`:123-129`（`ImportPickRawRead` 闭包对）、`:147` 起（`readImportPickBounded` 共享有界读驱动） | **06.B 的「保存到用户目标」端口应沿用同一形状**：commonMain 只声明闭包/值类型，平台侧实现 SAF `CreateDocument` / `JFileChooser` |

### 1.5 零 crypto、零 `VACUUM`（已复核）

- 对产品源码（`android-app/src/main`、`desktop-app/src/jvmMain`、`app-ui/src/commonMain`、`ledger-data/src/commonMain`、`ledger-data/src/androidMain`、`ledger-application/src`、`ledger-domain/src`）检索 `javax.crypto`/`Cipher`/`PBKDF2`/`SecretKey`/`AEAD`/`GCMParameterSpec`/`PBEKeySpec` **零命中**（本基点实读）；检索 `VACUUM` 在**全仓** `.kt` **零命中**。故加密实现与快照 SQL 均为 06.B 新增。
- **`MessageDigest("SHA-256")` 的现有用途（仅测试，非产品路径）**：`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidStartupFailClosedInstrumentedTest.kt:199-201`（Android instrumented），以及 JVM 测试 `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/ImportContentFingerprintJvmTest.kt:280`、`Rg09FingerprintJvmTest.kt:29`、`import/ccb/CcbBillParserJvmTest.kt:567`。**产品**代码的 SHA-256 走手写实现 `Sha256`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/JcsSha256.kt:46`、`digestHex` `:126`），由 `ImportContentFingerprint.kt:57`/`:81`/`:85` 等调用，**不**使用 `java.security.MessageDigest`。
- **对 06.B 的含义**：写入端的 `payload_sha256` 需要**流式**哈希（§5 的 64 KiB 分块，不得整文件读入）；现有 `Sha256.digestHex(ByteArray)` 只接受**已完整读入内存**的字节数组，且手写实现未做流式增量接口，故**不能**直接复用为快照哈希；`MessageDigest` 支持 `update(ByteArray, Int, Int)` 增量喂入，可满足流式要求。具体原语选择（`MessageDigest` 增量 vs 为 `Sha256` 增加增量接口）归实施批，本文只冻结「必须流式、不得整读」。
- **CSPRNG 先例**：两端组合根已用 `java.security.SecureRandom` 生成 UUIDv7 随机位（Android `App.kt:140`/`:761-763`；桌面 `Main.kt:150`/`:991-993`）。salt/IV 的 CSPRNG 源可沿用同一平台原语（容器规格 §4.4/§4.5 要求 CSPRNG）。
- **平台 crypto API 可用性**：两端均为 JVM/JCE 环境，`javax.crypto` 可用（桌面 JDK 21 `SunJCE`、Android `AndroidOpenSSL`；跨端字节兼容已由门证据实证），故 **06.B 不引入新依赖**（容器规格 §8）。

## 2. Scope

### 2.1 范围内（06.2 / 06.B）

- **一致快照**：在受控连接上以 `VACUUM INTO` 生成自洽快照（计划 `:160`、容器规格 §4.2）。
- **认证加密导出**：按容器规格 §4.3/§4.4/§4.5/§4.6 **逐字节**生成加密容器并写入用户选择的目标（计划 `:136`、`:160`）。
- **取消与空间不足**：导出侧磁盘前置、取消令牌、写中断的有界处理；失败/取消**绝不**报告成功，且不留下声称成功的部分产物（计划 `:136`、`:174`；容器规格 §4.8）。
- **私有暂存生命周期**：快照与明文（如产生）落在应用私有暂存目录，取消/会话结束/下次启动清理，且**登记**为需排除 OS 自动备份/设备迁移（容器规格 §4.9）。
- **平台保存端口与共享导出用例**：commonMain 的中立结果类型 + 有界保存端口；共享 UI 的导出进度/结果呈现（计划 `:136`、`:150`、`:152`、`:154`）。
- **验收映射**：P706-A01/A02/A08/A10 及导出相关向量的 06.B 贡献与后续归属（§6）。

### 2.2 明确范围外（后续切片）

- **06.C 导入预检**（有界读取、格式/完整性/兼容/结构/语义检查、隔离暂存库迁移、token/摘要）（计划 `:137`、`:175`）。本文只要求**产出的容器可被独立预检**（计划 `:174`），不实现预检。
- **06.D 恢复切换**（明确确认、关闭旧资源、原子切换与回退、重建用例/刷新）（计划 `:138`、`:176`）。
- **06.1 / 06.D 前置的稳定存储与 owner 落地**：已由 D-176 批准并**已实施**（基点 `d6cffb6`）；本文只消费其契约。
- 任何 schema 变更、迁移边、依赖变更、`.external/` 触碰；任何既有决定的修改；容器格式的任何字节/参数/拒绝码改动。

## 3. 导出数据流（计划 `:160`）

导出按计划 §6.2 的**冻结顺序**执行，每一步给出失败/取消/空间不足行为：

```text
1) 取得 runtime lease（owner.acquireLease）
2) 磁盘前置检查（导出侧公式，§5）
3) 在受控连接生成一致快照（VACUUM INTO → 私有暂存快照文件）
4) 关闭快照并验证（PRAGMA integrity_check = ok）
5) 有界加密写到用户目标（私有暂存容器 → 交付到用户目标）
6) 完成认证尾部且流正常关闭后报告成功
7) 清理私有暂存（快照文件、私有暂存容器、任何明文）
```

### 3.1 步骤 1：取得 runtime lease

- 导出用例在 owner 为 Ready 时取一个 operation lease（`LedgerRuntimeOwner.kt:321-331`），并在整个导出期间持有；导出结束（成功/失败/取消）后在 `finally` 释放（`:179-192`）。
- **非 Ready 时**：`acquireLease()` 返回 `LeaseAcquireResult.RuntimeNotReady`；导出用例返回类型化「运行时未就绪」，**不阻塞 UI 线程**（`:321-331` 的 `tryLock` 语义）。
- **保证**：持租约期间 `closeActiveGraph`/`reopen` 返回类型化 `QuiesceBlocked`（`:392-404`、`:413-439`），导出的受控连接不会被切换打断；同时导出**不**独占业务写入（lease 非互斥），故须靠步骤 3 的快照一致性语义而非排他锁。
- **失败/取消**：任一步失败或取消 → 释放租约 → 返回类型化失败/已取消结果，**不报告成功**。

### 3.2 步骤 2：磁盘前置检查（导出侧公式）

- 在生成快照**之前**检查可用空间 ≥ **`container_size + plaintext_size + 64 MiB`**（容器规格 §4.8 `:204`、`:200`；公式 `:204`）。其中 `plaintext_size` 取当前活动主文件大小（或其上界），`container_size` 取 `plaintext_size + 59 + salt_len + iv_len + tag_len` 的上界（§4.3 布局）。
- **空间不足** → 类型化拒绝（如 `P706_EXPORT_INSUFFICIENT_SPACE`，命名归实施批），**不开始**生成快照，**不**创建任何用户目标文件。
- **同一前置步须先查明文上限（写侧门）**：本步同时执行 §5 的**前置门**——活动主文件大小 > **1 GiB** 即类型化拒绝（`P706_EXPORT_PLAINTEXT_TOO_LARGE`），理由见 §5（否则会产出读取端自拒的容器）。两次检查都在 `VACUUM INTO` 之前、都在用户目标被创建之前。
- **依赖缺口（明确登记）**：可用空间查询原语当前**不存在**（§1.2 零命中），06.B 必须先补（Android `StatFs`/`File.usableSpace`、桌面 `FileStore.getUsableSpace`）。**未验证项**：具体平台 API 选择归实施批。
- **注意**：64 MiB 余量相对 354 MiB 级快照不足 20%，不得当作主要余量来源；真正的 fail-closed 来自「不得在写满磁盘后留下成功标记」（容器规格 §4.8 `:208`）。

### 3.3 步骤 3：在受控连接生成一致快照（`VACUUM INTO`）

- 快照 SQL 为 `VACUUM INTO ?`（或等价的绑定参数形式），**在受控连接上**执行：Android 经句柄新增的受控入口（§1.3），桌面经组合根的 graph 入口；两者委托到 commonMain 的 driver 级 helper（形状同 `QueryStatisticsOptimize.kt:55-87`）。
- **目标路径**：私有暂存快照文件（§6），**必须不存在**——门证据显示两端 `VACUUM INTO` 对已存在目标报 `output file already exists` 并拒绝覆盖（`q13-driver-snapshot-gate.md`）；06.B 须在调用前确保目标不存在（存在则先删除或换名），且**绝不**以活动代主文件或用户目标为快照落点。
- **后置明文上限门**：`VACUUM INTO` 返回后**立即**对实际快照文件大小执行 §5 的**后置门**（> **1 GiB** 即删除快照、释放租约、类型化拒绝 `P706_EXPORT_PLAINTEXT_TOO_LARGE`，**不**创建用户目标文件）。依据：快照大小**不保证**等于源大小（`VACUUM INTO` 重写页面布局，产物可能小于或大于源），故不能只靠前置门；门证据的 61k 大库即为产物按字节数小于源之例（源 371,658,752 B ≈ 354 MiB → 快照产物 355,721,216 B）。
- **一致性依据**：owner 单活动图 = 单一受控连接；SQLite 在该连接上串行执行语句，`VACUUM INTO` 在**同一连接**上产生自洽文件（门证据：WAL 下 `integrity_check=ok`、快照 `journal_mode=delete`、独立自洽文件）。Android 源库为 `journal_mode=delete`（§1.3），机制不依赖 WAL。
- **不做裸复制**：快照**不得**用 `LedgerFileSystem.copy` 复制活动主文件代替（容器规格 §4.2 `:91`；计划 06.A 行 `:135` 明令不把裸复制等同完整备份）。
- **线程要求**：快照与后续加解密**必须在后台线程**执行，**禁止** UI 线程（容器规格 §4.8 `:210`；计划 `:160`）。61k 大库实测 `VACUUM INTO` 约 **65.8 s**（源库约 **354 MiB**；`q13-driver-snapshot-gate.md` 大库段），故 UI 不得阻塞并须显示进度。
- **失败/取消**：`VACUUM INTO` 失败或取消（取消在语句粒度不可中断，故取消在语句完成后于后续步骤生效）→ 删除已产生的部分快照文件 → 释放租约 → 返回失败/已取消，**不报告成功**。
- **未验证项**：**大库峰值内存**（61k）未测（容器规格 §6 `:289`；`q13-driver-snapshot-gate.md` 明述未取），故本文不预先冻结内存阈值。**未验证项**：`VACUUM INTO` 经 **SQLDelight 的 Android `driver.execute` binder 路径**执行尚未实证（门证据的 Android 参数绑定用的是设备 `sqlite3` CLI 的 `.parameter set`，桌面用的是 JDBC `PreparedStatement`；`q13-driver-snapshot-gate.md`），故该具体路径属实施批须实证项。**未验证项**：步骤 4 的 `integrity_check` 需对**快照文件**第二次打开数据库，该表面当前不存在（§3.4、§9 第 9 项）。

### 3.4 步骤 4：关闭快照并验证（`integrity_check`）

- 快照文件句柄在 `VACUUM INTO` 返回后即关闭（SQLite 已完成写入）。随后对**快照文件**（而非活动库）执行 `PRAGMA integrity_check`，要求结果为 `ok`；并可读回 `PRAGMA user_version` 以填充容器头部 `db_schema_version`（非权威提示，容器规格 §4.3.2 `:136`）。
- **执行面（明确登记为未验证项）**：该 `PRAGMA` 必须作用在**快照文件**上，即需要**第二次打开数据库**——活动连接正持有活动代主文件，不能替代。而 driver 对组合根私有（§1.3），现有受控入口（`runQueryStatisticsOptimize`/`runFullAnalyze`）都作用于活动句柄，**没有**「对任意路径开一个只读校验连接」的现有表面。故 06.B 须新增该第二打开表面（Android 经新增受控入口委托到 commonMain driver 级 helper、桌面经 graph 入口），其**具体 driver 选择与打开/关闭语义尚未实证**，登记为 §9 第 9 项；本文不预先冻结其实现。
- **失败** → 删除快照 → 释放租约 → 返回类型化失败（如 `P706_SNAPSHOT_INTEGRITY_FAILED`，命名归实施批），**不报告成功**，**不**创建用户目标文件。
- **边界**：本步骤只验证**快照自洽**；容器的格式/结构/领域预检属 **06.C**（§2.2）。

### 3.5 步骤 5：有界加密写到用户目标

本步骤分为**两相**，以满足「用户目标可能不支持原子 rename；中断时明确『未完成备份』」（计划 `:160`）与「失败文件不标成功」（计划 `:136`）：

1. **相 1（私有暂存内生成认证容器）**：按容器规格 §4.3 逐字节写容器到**私有暂存容器文件**。按 §4.6 的两遍顺序：**第一遍** 64 KiB 分块流式读快照**完整算完** `payload_sha256`（必须先于写头部，因为该值在固定头部偏移 27 且固定头部是 AAD），组装并写头部 + salt + iv，**第二遍** 64 KiB 分块流式读做流式加密（§5）。此相结束时容器**已完整认证**（尾部 tag 已写、流正常关闭）。
2. **相 2（交付到用户目标）**：经平台保存端口把私有暂存容器**有界复制**到用户选择的目标。**必须使用 06.B 新增的有界流式写端口**（commonMain 只声明闭包/值类型，平台侧实现 SAF `CreateDocument` / `JFileChooser`；形状沿用 §1.4 的 `ImportFilePickPort` 先例），按 64 KiB 分块从暂存容器读到用户目标。**禁止**调用现有 `LedgerFileSystem.writeAtomic(path, bytes: ByteArray)`（`LedgerStableStorage.kt:81-84`）——它是**整文件读入内存**的写原语，对最大 ~2 GiB 的容器既违反 §5 的 64 KiB 流式规则，也违反 §5 的「禁止无界读入」；`DesktopLedgerFileSystem.kt:45-70` 与 `AndroidLedgerFileSystem.kt:48-64` 的适配器实现同样以 `ByteArray` 为入参。仅**保留**这些适配器体现的**原子 rename 语义**（临时文件 + 平台原子替换；桌面 `Files.move(ATOMIC_MOVE)`、Android `AtomicFile`）：若目标为本地文件且平台支持原子 rename，则流式写到同目录临时文件、fsync 后原子替换；若目标不支持原子 rename（如 SAF 外部 provider），则流式写出并在**流正常关闭**后报告成功。

- **报告成功的唯一条件**：相 1 完成（认证尾部已写）+ 相 2 的交付流**正常关闭**（且原子替换成功，如适用）。**仅此**才报告成功（计划 `:160`）。
- **失败/取消**：相 1 失败/取消 → 删除私有暂存容器 → 不报告成功（用户目标未被触碰）。相 2 失败/取消 → **尽力**删除用户目标上的部分文件，并报告「未完成备份」；**不承诺**外部 provider 自动删除失败文件（计划 `:160`），也不把该部分文件标记为成功。
- **写中断**：任一相写入抛出（磁盘满、provider 拒绝、进程被杀）→ 捕获 → 清理私有暂存 → 返回失败；**绝不**留下「声称成功」的产物。
- **敏感值不进日志**：密码、派生密钥、明文快照内容、用户目标路径**不得**进入日志或诊断文案（计划 `:136`；容器规格 §4.9 `:218`、§4.10 `:225`）。

### 3.6 步骤 6：完成认证尾部且流正常关闭后报告成功

- 成功报告须同时满足：(a) 容器尾部 16 字节认证标签已写；(b) 交付流 `close()` 无异常（或原子替换成功）；(c) 私有暂存容器已 fsync（如平台支持）。
- **保证（本文核心不变量）**：**任何**未达到上述条件的路径都**不得**返回成功结果；失败/取消路径返回类型化非成功结果（`Cancelled` / `Failed(reason)`），且不留下声称成功的部分产物（用户目标上可能存在的部分文件按 §3.5 尽力删除并明确标注「未完成」）。

### 3.7 步骤 7：清理私有暂存

- 成功/失败/取消均清理私有暂存：快照文件、私有暂存容器、任何明文（§6）。
- **清理失败不改变结果**：清理失败记录为诊断但不把成功翻转为失败（暂存清理有 §4 的「下次启动」兜底）。

## 4. 容器写入（逐字节，容器规格 §4 为权威）

本节**重述**容器规格 §4.3 的字节布局与 §4.4/§4.5/§4.6 的参数，使实现者只需**本文 + 容器规格 §4.4/§4.5/§4.6**即可实现写入端。**本文不改动任何值**；§4 为唯一权威。

### 4.1 容器字节布局（容器规格 §4.3 `:98-113`）

多字节整数一律**大端（big-endian）**、无符号；偏移以容器文件首字节为 0。

| 偏移 | 长度（字节） | 字段 | 写入值 / 含义 |
| --- | --- | --- | --- |
| 0 | 4 | `magic` | ASCII `ULBK`（0x55 0x4C 0x42 0x4B） |
| 4 | 2 | `container_format_version` | `u16`；v1 = `1`（与 DB schema 版本无关） |
| 6 | 1 | `kdf_id` | `u8`；`1` = PBKDF2WithHmacSHA256 |
| 7 | 1 | `aead_id` | `u8`；`1` = AES-256-GCM |
| 8 | 4 | `kdf_iterations` | `u32`；写入值 = `600000` |
| 12 | 1 | `salt_len` | `u8`；写入值 = `16` |
| 13 | 1 | `iv_len` | `u8`；写入值 = `12` |
| 14 | 1 | `tag_len` | `u8`；写入值 = `16` |
| 15 | 4 | `db_schema_version` | `u32`；快照的 `PRAGMA user_version`（当前 v31）。**非权威提示** |
| 19 | 8 | `plaintext_len` | `u64`；明文字节数（= 快照文件大小） |
| 27 | 32 | `payload_sha256` | SHA-256(明文)。**仅用于损坏检测，不构成认证** |
| 59 | `salt_len` | `salt` | CSPRNG 随机字节 |
| 59+`salt_len` | `iv_len` | `iv` | CSPRNG 随机字节 |
| 59+`salt_len`+`iv_len` | `plaintext_len`+`tag_len` | `ciphertext` + `tag` | AES-GCM 输出；标签在密文之后 |

**固定头部**（偏移 0..58，共 59 字节）不含任何可变长字段。

### 4.2 KDF（容器规格 §4.4 `:138-156`）

- **算法**：`PBKDF2WithHmacSHA256`；**迭代**：写入端固定 `600000`；**salt**：平台 CSPRNG 生成，**恰 16 字节**。
- **派生密钥**：**256 bit（32 字节）**，`SecretKeySpec(bytes, "AES")`。
- **密码加宽编码（v1 冻结，逐字节一致）**：先 `password.getBytes(UTF_8)`，再逐字节取低 8 位加宽为 `char[]`，再构造 `PBEKeySpec(widened, salt, iterations, 256)`（容器规格 §4.4 `:146-153` 的代码块）。**禁止** `password.toCharArray()` 直构；**不做** Unicode 规范化。
- **读取端接受范围**（写入端不受影响）：`salt_len ∈ 16..32`、`kdf_iterations ∈ [600000, 10000000]`，超出即类型化拒绝（容器规格 §4.3.1 `:127-130`）。

### 4.3 AEAD（容器规格 §4.5 `:158-163`）

- **算法**：`AES/GCM/NoPadding`；**IV/nonce**：96 bit（12 字节），CSPRNG 生成；**tag**：128 bit（16 字节）。
- **单密钥单次调用**：每次导出使用**全新 CSPRNG salt** 派生**全新密钥**，且该密钥仅执行**一次** GCM 调用；**禁止**在同一密钥上重复加密不同 payload，也禁止复用 salt+IV 组合（§4.5 `:162`）。
- **AAD 先供给**：`updateAAD` 必须先于 `update`/`doFinal`（§4.5 `:163`）。

### 4.4 AAD 构造（容器规格 §4.6 `:165-176`）

```
AAD = 固定头部(偏移 0..58，59 字节) || salt(salt_len 字节)
```

- 固定头部长度恒定（59 字节），其中已含 `salt_len`；其后紧跟恰好 `salt_len` 字节的 salt；两者拼接无歧义。**IV 不进入 AAD**。
- **未验证项（明确登记）**：该拼接构造本身**尚无跨端向量**（容器规格 §4.6 `:176`、§6 `:286` 登记为 OPEN）；既有跨端向量使用的是测试替身 AAD。故 06.B **不声称**该构造已跨端验证。

### 4.5 统一安全失败（容器规格 §4.7 `:178-191`）

- **写入端不产生**该失败；但 06.B 须保证写出的容器满足统一失败语义：读取端在错误密码/标签校验失败/密文篡改下只能得到**单一**失败码 `P706_CONTAINER_AUTHENTICATION_FAILED`，同一外部可观察行为与同一时序量级。
- **`payload_sha256` 的角色**：仅损坏检测，**不是**安全边界；写入端据明文计算并写入，读取端只在 GCM 标签通过后用它检测存储层损坏（§4.7 `:191`）。
- **固定头部类型化拒绝**（写入端须写出符合 §4.3.1 的合法头部）：`magic`、`container_format_version=1`、`kdf_id=1`、`aead_id=1`、`salt_len=16`、`iv_len=12`、`tag_len=16`、`kdf_iterations=600000` 全部落在读取端接受范围（§4.3.1 `:121-130`）。

### 4.6 写入端有序步骤（本文补充，不改格式）

1. 生成 salt(16) 与 iv(12)（CSPRNG）。
2. 由密码（加宽编码）+ salt + 600000 + 256 派生密钥。
3. 计算 `plaintext_len` = 快照大小；**第一遍** 64 KiB 分块流式读**完整算完** `payload_sha256`（**必须**先于步骤 5/6，因为该值在固定头部偏移 27，而固定头部是加密的 AAD；§5）。
4. 组装固定头部（含 `db_schema_version` = 快照 `user_version`）。
5. 写头部 + salt + iv 到私有暂存容器。
6. `Cipher` 初始化 GCM（iv，128-bit tag）→ `updateAAD(header || salt)` → **第二遍** 64 KiB 分块读快照并 `update` 写密文（**第二遍有界流式读**；§5）。
7. `doFinal` 写尾部 tag；流正常关闭；fsync（如平台支持）。
8. 使用后清除 `PBEKeySpec` 字符数组（`clearPassword()`）与派生密钥字节（容器规格 §4.10 `:225`）。

**两遍读的说明**：步骤 3 与步骤 6 各是一次有界的 64 KiB 分块流式读，**都不得**把快照整体读入内存；不存在「哈希与加密同一遍」的可行顺序（§5）。

## 5. 资源上限与有界 IO

| 限制 | 值 | 出处 |
| --- | --- | --- |
| 容器上限 | **2 GiB**（读取端类型化拒绝 `P706_CONTAINER_TOO_LARGE`） | 容器规格 §4.8 `:197` |
| 明文（快照）上限 | **1 GiB**（读取端在 `plaintext_len` 超限时拒绝；**同时**为写入端绑定上限，见下） | 容器规格 §4.8 `:198` |
| 流式缓冲 | 固定 **64 KiB**，逐块 `update`/写入 | 容器规格 §4.8 `:202` |
| 导出侧磁盘前置 | 可用空间 ≥ `container_size + plaintext_size + 64 MiB` | 容器规格 §4.8 `:204` |

- **写入端必须自己执行 1 GiB 明文上限（不得只依赖读取端）**：容器规格 §4.8 `:198` 的 `P706_CONTAINER_TOO_LARGE`/`plaintext_len` 上限是**读取端**检查；写入端若不设门，源库快照 > 1 GiB 时会**通过**磁盘前置、产出一个**连 06.B 自己的读取端都会拒绝**的容器——即「导出报成功但产物不可用」。故 06.B 必须加**写侧门**，且在**创建任何用户目标文件之前**拒绝：
  - **前置门（`VACUUM INTO` 之前）**：先取当前活动主文件大小（或其上界，§3.2 已用于磁盘公式）；若已 > 1 GiB 即类型化拒绝（如 `P706_EXPORT_PLAINTEXT_TOO_LARGE`，命名归实施批），**不**生成快照、**不**触碰用户目标。
  - **后置门（快照生成之后、加密之前）**：快照大小**不保证**等于源大小（`VACUUM INTO` 重写页面布局），故须对**实际快照文件大小**再查一次；> 1 GiB 即删除快照、释放租约、类型化拒绝，**不**创建用户目标文件。门证据的 61k 大库为产物按字节数小于源之例（源 371,658,752 B ≈ 354 MiB → 快照产物 355,721,216 B），故后置门依据是「大小不保证相等」而非任一方向的大小关系。
  - **绑定上限**：**1 GiB 明文上限（严于 2 GiB 容器上限）是写入端的绑定上限**——因为 `container_size = plaintext_len + 59 + salt_len + iv_len + tag_len`，明文一旦 ≤ 1 GiB，容器必 ≤ 1 GiB + **103** 字节（59 + 16 + 12 + 16；其中 59 为固定头部、16 为 salt、12 为 IV、16 为 tag），远低于 2 GiB 容器上限；故写侧只需门 1 GiB 明文，无需单独门容器大小。

- **禁止无界读入**：**不得**对无界输入调用 `readBytes()` 或任何整文件读入内存的 API（容器规格 §4.8 `:202`；计划 `:144`）。注意现有 `LedgerFileSystem.readBytes`（`LedgerStableStorage.kt:63`）是整文件读入，**不得**用于快照或容器；导出路径只用 64 KiB 分块（可新增流式端口，§1.2）。
- **`payload_sha256` 必须先完成、再做加密（两遍有界流式读）**：`payload_sha256` 位于固定头部偏移 27，而固定头部是加密开始前供给的 AAD（§4.4、§4.6），故**不可能**与加密在同一遍流中算出。正确顺序（与 §4.6 一致）：**第一遍**对快照做一次 64 KiB 分块流式读，**完整算完** `payload_sha256`；据此组装固定头部并写头部 + salt + iv；**第二遍**再对快照做一次 64 KiB 分块流式读做 GCM 加密。**两遍都不得**把快照整体读入内存。
- **必须在后台线程**：快照、KDF、流式加解密、交付**全部**在后台线程；**禁止** UI 线程（容器规格 §4.8 `:210`）。依据：61k 大库 `VACUUM INTO` 实测约 **65.8 s**、源库约 **354 MiB**（`q13-driver-snapshot-gate.md` 大库段），故须显示进度、处理空间不足/取消。
- **KDF 后台 + 可取消**：读取端对 `kdf_iterations` 上界（10,000,000）的 CPU 成本须在后台执行并以进度/可取消方式呈现（容器规格 §4.4 `:142`）；写入端固定 600000 亦在后台执行。
- **取消粒度**：取消令牌在 64 KiB 分块之间检查；快照阶段（`VACUUM INTO`）为单条不可中断语句，取消在语句完成后生效（§3.3）。

## 6. 私有暂存生命周期

- **落点**：快照文件、私有暂存容器、任何明文（06.B 的加密流程中明文即快照；写入端不落明文容器）**只**落在**应用私有暂存目录**——Android 应用私有目录、桌面平台用户数据目录下的私有子目录（容器规格 §4.9 `:218`；06.1 规格 §3.1/§3.2/§3.3）。宿主位置经平台 API 运行期解析（`AndroidLedgerFileSystem.kt:102-107`、`DesktopLedgerFileSystem.kt:118-134`），tracked 文件**不写**绝对路径。
- **字面命名与布局**：归实施批（容器规格 §5.2 `:243` 只冻结语义：私有、单账本、可枚举、可回退）。本文只冻结「必须私有、必须可清理」。
- **清理时机**（计划 `:166`；容器规格 §4.9 `:219`）：未确认的暂存在**取消**、**会话结束**、**下次启动**时清理。
- **不声称安全擦除**：**禁止**声称已删除文件的物理扇区被安全擦除（容器规格 §4.9 `:219`；计划 `:166`）。
- **OS 自动备份/设备迁移（登记，未关闭）**：需有明确排除规则覆盖明文暂存、回退与 journal（计划 `:166`）。Android `AndroidManifest.xml` 的 `<application>`（`:3`）**未**声明 `android:allowBackup`/`android:dataExtractionRules`（全文 15 行，无该属性），故系统默认实际为备份启用；该排除决策**归隐私规格**（D-174 第 4 条；容器规格 §1.2 `:37`、§6 `:291`）。**本文不声称**云备份/设备迁移已关闭。
- **运行期状态不入容器**：世代目录、journal、活动指针是运行期恢复状态，**不属于**备份 payload（容器规格 §4.9 `:220`）；导出只承载单一物理快照。

## 7. 验收映射（P706-A01/A02/A08/A10 及导出相关向量）

下表把计划 §6.3（`:181-190`）的向量映射到 06.B。**本规格不把任何向量标为 PASS**——全部为未来验收要求，尚未执行（计划 `:194`）。

| 验收 ID | 计划要求（摘要，计划 `:181-190`） | 06.B 交付 | 留给后续切片 |
| --- | --- | --- | --- |
| P706-A01 | 当前库含全部 owner，往返逐 owner/稳定 ID 等价 | 快照为**整库物理快照**（`VACUUM INTO`），保留全部 owner（容器规格 §3 清单）；**往返等价**由 06.C/06.4 验证 | 往返与权威投影（06.C/06.4） |
| P706-A02 | WAL 下持续写入时导出，还原只出现自洽时点 | 受控连接上的 `VACUUM INTO` 一致性快照机制。**driver 门两端已实证的仅限：可达、参数绑定、产物完整性、拒绝覆盖**（`q13-driver-snapshot-gate.md`）。**WAL 一致性子claim 只在 Android 侧实证**（经设备 `sqlite3` CLI 的 `journal_mode=WAL` + 写事务 + `VACUUM INTO`）；**桌面门用的是非 WAL 的 4 交易库**（`user_version=30`），故**桌面 WAL 并发一致性未测**。**两端快照集成试验**属 06.2 实施 | WAL 并发集成试验与还原自洽（06.2/06.4） |
| P706-A03（导出侧相关） | 错密码、认证失败、截断、伪格式、超限、未知未来/无版本库、异账本 → 类型化拒绝 | **无**（拒绝码与容器格式判定属 06.C）；06.B 只保证写出的头部合法（§4.5） | 容器级类型化拒绝（06.C） |
| P706-A08 | 恢复库已有提交回执，重放同请求返回原结果、零新增经济效果 | 物理快照保留幂等回执（容器规格 §3、§4.2）；**重放行为**属 06.4 | 重放语义（06.4） |
| P706-A09（导出侧相关） | Android→Desktop→Android 身份与事实一致 | 跨端字节兼容已用 **ASCII 向量**与**非 ASCII（CJK）密码向量**实证（容器规格 §6 `:282`、`:285`）；**A09 验收本身**属 06.4/06.5 | 端到端跨端与跨进程重开（06.4/06.5） |
| P706-A10 | 满盘、SAF 写失败、取消、损坏活动库 → 不报成功；FOUND-001 保留旧库 | **06.B 核心**：导出侧磁盘前置（§3.2/§5）、取消（§3.3/§3.5）、SAF/写失败不报成功（§3.5/§3.6）、失败工件按策略处置（§3.5/§6）；**损坏活动库 fail-closed 保留旧库**已由 06.1 交付（06.1 规格 §6 `:281-282`） | FOUND-001 回归属 06.1/06.D（本文不改） |

**格式层面 06.B 即可支撑的**：A01 的分母（整库快照）、A02 的快照机制（driver 门的可达/绑定/完整性/拒绝覆盖两端已实证；WAL 一致性仅 Android 侧）、A08 的幂等回执随快照保留、A10 的导出侧磁盘/取消/写失败不报成功、A09 的跨端可解密。**属后续切片的**：A01/A02 的实际往返与集成试验、A03 的容器拒绝、A08 的重放、A09 的端到端、A10 的恢复侧路径。

**06.2 / 06.B 的放行条件（计划 `:174`）**：「一致快照、认证加密导出、取消与空间不足；**先证明产物可被独立预检**」——本文的 §3.5 相 1（私有暂存内的完整认证容器）即为「可被独立预检的产物」；预检本身属 06.C。

## 8. 非目标与边界

- **不改 schema**：零 DDL、零迁移边、schema 停留 v31；不预占未来版本号。
- **不引入新依赖**：仅用平台自带 `AES/GCM/NoPadding`、`PBKDF2WithHmacSHA256`、`SecureRandom`、`MessageDigest("SHA-256")`；不引入 BouncyCastle/Tink/Argon2id/scrypt（容器规格 §8 `:322`）。**已存在产品 SHA-256 原语**：手写 `Sha256`（`JcsSha256.kt:46`，经 `ImportContentFingerprint.kt` 使用），但它只接受整块 `ByteArray`、无增量接口，**不满足** §5 的流式要求（§1.5）。
- **不触碰 `.external/`**；不写个人数据；tracked 文件不含本机绝对路径、临时研究或工具轨迹（`docs/CONTRIBUTING.md:165`）。
- **不重开容器格式**：不改容器格式规格的字节布局、KDF/AEAD 参数、AAD 构造、拒绝码、资源上限（容器规格 §4 为唯一权威）。
- **不改既有决定**：不新增决定条目；不改 D-156/D-158/D-174/D-176 及 P7-01～P7-05 冻结面。
- **不实现**：06.C 预检与 06.D 切换的产品代码与测试。
- **不声称**：不声称云备份/设备迁移已关闭（§6）；不声称安全擦除物理扇区（§6）；不把 OPEN 门项当作已达成（§9）。

## 9. 边界断言与证据纪律

- 本文状态为 **approved**（`docs/CONTRIBUTING.md:162` 允许分类之一），依据 `docs/DECISIONS.md` D-177；本文**已经两轮独立规格评审（首轮 APPROVE WITH FINDINGS，含 1 项 P2 写序矛盾 + P2/P3；修订后的闭包复评 APPROVE WITH FINDINGS，全部条目已闭合）**。本文只冻结 06.B 的**设计级**方案，仍**不**构成产品行为、迁移、技术选型或发布授权；06.B 的实施属后续实施批。
- 本文与两份已批准上游规格逐条一致：**不改**容器格式规格（D-174）的任何冻结字节/参数/拒绝码，**不改** 06.1 规格（D-176）的 owner/lease 契约；只承接其写入端义务。
- 每项事实主张均带 file:line 证据；`local/artifacts/` 以主 checkout 为准（只读，不粘贴大段原文）。
- **明确标记为未验证/未取读数**的项：
  1. **大库峰值内存（61k）**未测（容器规格 §6 `:289`；`q13-driver-snapshot-gate.md` 明述未取）——本文不预先冻结内存阈值。
  2. **桌面侧 61k `VACUUM INTO` 资源读数**未取（容器规格 §6 `:290`）。
  3. **§4.6 AAD 构造（`header(0..58) || salt`）的跨端向量**尚无（容器规格 §6 `:286`）——06.B 不声称该构造已跨端验证。
  4. **`VACUUM INTO` 经 SQLDelight Android `driver.execute` binder 路径**执行未实证（门证据的 Android 绑定是设备 `sqlite3` CLI，桌面是 JDBC `PreparedStatement`）。
  5. **可用空间查询原语**（Android `StatFs`/`File.usableSpace`、桌面 `FileStore.getUsableSpace`）当前不存在（§1.2 零命中），平台 API 选择归实施批。
  6. **私有暂存目录字面命名与布局**、**导出用例/端口的具体类型、包、超时值**归实施批（容器规格 §5.2 `:243`；06.1 规格 §9 `:320`）。
  7. **OS 自动备份/设备迁移排除规则**归隐私规格（D-174 第 4 条）；本文只登记，不裁决。
  8. **取消在 `VACUUM INTO` 单语句内的可中断性**：设计上取消在语句完成后生效（§3.3）；平台是否能中断单条快照语句未验证。
  9. **快照 `PRAGMA integrity_check` / `user_version` 的第二次打开表面**（§3.4）：driver 对组合根私有（§1.3），现有受控入口只作用于活动句柄，无「按路径打开只读校验连接」的现有表面；具体 driver 选择与打开/关闭语义归实施批。
  10. **面向用户目标的有界流式写端口**（§3.5 相 2）当前**不存在**：唯一现有写原语 `writeAtomic(path, bytes: ByteArray)`（`LedgerStableStorage.kt:81-84`）是整文件读入内存，**不得**用于 ~2 GiB 容器；06.B 须新增流式写端口（仅沿用原子 rename 语义）。其具体类型/包与平台落点归实施批。
  11. **写侧 1 GiB 明文上限门**（§5）的两个检查点（`VACUUM INTO` 前的前置门、快照后的后置门）为本文新增设计义务；具体阈值取整与拒绝码命名归实施批，但**不得**放宽 1 GiB（容器规格 §4.8 `:198` 为权威）。
- 本文不复制任何真实金额、时间、锚点注册值或个人数据；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动。