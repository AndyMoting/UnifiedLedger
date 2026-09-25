# P7-06 备份与恢复 06.3 / 06.C 设计规格：有界读取、认证解密、隔离迁移与预检 token

状态：proposal（2026-09-25 起草）。本文是 06.3 / 06.C **预检切片的规格草案**。`docs/DECISIONS.md` 当前最高决定 id 为 D-178，**没有**任何批准 06.C 规格的决定条目，且本文**尚未**经过独立评审的**收口**确认与 distinct verifier；故按 `docs/CONTRIBUTING.md:168` 的允许分类标 `proposal`，**不**标 `approved`。本文只冻结**设计级**语义，**不**构成产品行为、迁移、技术选型或发布授权，实施属后续实施批。

**Revision:** draft-3（2026-09-25）。相对 draft-2 应用**收口评审与独立 verifier**（结论 APPROVE WITH FINDINGS：draft-1 的 4 项 P2 与 6 项 P3 全部确认 CLOSED，无新 P1/P2）的 findings：**事实前提更正（verifier 证伪）**——draft-2 的「Android 无可跑 `Schema.migrate` 的非创建式 `SqlDriver`」为**错误**前提。经独立复核缓存 AAR（`javap -p` 实读）确认 `app.cash.sqldelight:android-driver:2.3.2` 暴露**公开**构造器 `AndroidSqliteDriver(SupportSQLiteDatabase)`、`(SupportSQLiteDatabase, int)`、`(SupportSQLiteDatabase, int, Long)`，其私有 4 参构造器以 `(openHelper != null) xor (database != null)` 断言且 `openHelper == null` 时从不触碰 helper；配合 `androidx.sqlite:sqlite-framework-android:2.6.2` 的公开 `FrameworkSQLiteDatabase(SQLiteDatabase)`，即可由 `SQLiteDatabase.openDatabase(..., OPEN_READWRITE)` 得到**非创建式、可迁移**的 `SqlDriver`。故 §1.4/§8.2/§10 第 8 项更正为「既有支持路径优先，**不一定**需要新驱动代码」，并把「`FrameworkSQLiteDatabase` 是否在 `ledger-data` androidMain 编译类路径上」登记为实施批首项待确认点（从缓存模块元数据读到它出现在 `android-driver:2.3.2` 的 **runtime** 发布而非 api 发布）。**P3-A** 更正 `LedgerLease.generation` 行号 `:179`→`:181`；**P3-B** 更正 `activeGeneration` 行号 `:229`→`:230`（两处）；**P3-C** 在 §3.1 补「计数上限在每一条路径上都生效，谎报 `reportedSize` 无法绕过 2 GiB 上限」；**P3-D** 在 §7.3 补「`quiesce()` 会挂起至有界超时（`LEDGER_QUIESCE_TIMEOUT_MILLIS` `:195`）后才返回 `QuiesceBlocked`，06.D 不得假设瞬时拒绝」。仍为 `proposal`。

**Revision:** draft-2（2026-09-25）。相对 draft-1 应用**独立规格评审**（结论「需返工后批准」，无 P1，4 项 P2 + 6 项 P3）的全部 findings：**P2-1** 头部 `db_schema_version` 与 payload `user_version` 不一致时，按容器规格 §4.3.2 `:136`/§5.4 `:259` **明令**以 `P706_SCHEMA_VERSION_UNSUPPORTED` 做**类 2** 结构拒绝（draft-1 的「以 payload 为准」被纠正为「以 payload 为准**并拒绝**」）；**P2-2** 数据流插入**磁盘前置检查**为独立编号步骤（现步骤 3）并重编号全部步骤/交叉引用，且把 `plaintext_len` 的标签从「已认证」更正为「认证前由自洽等式与 ≤ 1 GiB 上限校验，尚未经标签认证」；**P2-3** 把「仅捕获 generation、不持租约」的推荐改为**持 operation lease 覆盖整段预检**，理由改为**阻止 `reopen` 触发的 `sweepBackupStaging` 删除预检自己的已认证暂存工件**（draft-1 的「不读活动连接故不需租约」理由被证伪：遗漏了 sweep 的破坏性副作用），并同步 §6.5 与 §10 第 11/14 项；**P2-4** 明确 Android 交付物**依赖新增驱动代码**（自定义 `SqlDriver` 适配器或已核实的非创建式打开）——**该结论已在 draft-3 被事实更正**；**P3-1** 截断改用**独立**建议码 `P706_CONTAINER_TRUNCATED`，并登记「复用 `P706_CONTAINER_FORMAT_UNSUPPORTED` 会加宽冻结条件」的替代；**P3-2** 声明端口只有无参 `doFinal()`，尾部标签经 `update` 喂入，散文与端口形状对齐；**P3-3** §10 第 3 项补 `P706_SCHEMA_VERSION_UNSUPPORTED` 的出处（§4.3.2 `:136`、§5.4 `:259`）；**P3-4** 2 GiB 上限补「provider 报大小 > 上限即**不读取**拒绝」的精确路径，计数流降为 provider 不报大小时的登记回退；**P3-5** 新增 §7.5「确认时对暂存工件的存在性/摘要重校验」硬要求；**P3-6** 更正两处行号（`DesktopLedgerFileSystem.kt:116` 的桌面 `openRead`；`JvmBackupCryptoPrimitives.kt:80` 的增量 `MessageDigest.update`）。仍为 `proposal`。

**Revision:** draft-1（2026-09-25）。事实基线 = 本 worktree 分支 `UL-p7-06cspec2`，基点 `7199c56`（P7-06 06.2 / 06.B 备份导出合并点，D-177）。schema 停留 **v31**，迁移链 `1.sqm`～`30.sqm`（30 个文件，v1→v31；本 worktree 实读计数）。tracked 行号为该基点的实读行号；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 与 `local/artifacts/p7-06-gate/` 以主 checkout 为准、只读（本 worktree 内不存在该 `.local.md`，见 §1.0）。本文**不**新增决定条目、**不**修改任何既有决定；不复制大段产品代码；不写本机绝对路径、个人数据或工具轨迹；示例与命名全部匿名合成。文件名采用**起草日期** 2026-09-25，形式与既有 p7-06 规格（`2026-09-24-p7-06-*-design.md`）一致。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为当前 worktree 基点 `7199c56` 的实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读）：

- **已批准的容器格式规格（承重权威，D-174）**：`docs/specs/2026-09-24-p7-06-backup-container-format-design.md`（approved）。本文实现其**读侧**：
  - §4.3（`:94-113`）：冻结的容器字节布局（固定头部 59 字节 + salt + iv + ciphertext + tag）。
  - §4.3.1（`:117-132`）：固定头部的类型化拒绝码表（**逐条 verbatim 引用**，见 §4.1）。
  - §4.3.2（`:134-136`）：`db_schema_version` 为**非权威提示**；权威检查是 payload 的 `PRAGMA user_version`。
  - §4.4（`:138-156`）：PBKDF2WithHmacSHA256、salt、迭代上下界、256-bit 派生密钥、**冻结的密码加宽编码**。
  - §4.5（`:158-163`）：AES-256-GCM、96-bit IV、128-bit tag、单密钥单次调用、AAD 先供给。
  - §4.6（`:165-176`）：`AAD = 固定头部(0..58) || salt` 的无歧义构造。
  - §4.7（`:178-191`）：统一安全失败（无 oracle）与**三类结构检查时机**（类 1 认证前公开格式；类 2 认证后 payload 结构；类 3 认证后身份/账本）。
  - §4.8（`:193-212`）：资源上限（2 GiB 容器 / 1 GiB 明文 / 64 KiB 流式 / 自洽等式）、**恢复侧磁盘公式**、后台线程要求。
  - §4.9（`:214-220`）：明文隔离与生命周期；**禁止 `CipherInputStream`**；认证完成前绝不打开或迁移数据库。
  - §4.10（`:222-226`）：默认加密、密码不持久化、`clearPassword()`、读取端不施加长度门。
  - §5.4（`:256-262`）：默认拒绝未知/`user_version=0`/未来 schema；**禁止复用桌面宽松补戳路径**；仅接受本产品身份。
  - §6（`:276-295`）：技术门表（含 `:286` §4.6 AAD 构造跨端向量 OPEN、`:292` 旧 schema 支持集与迁移门 OPEN）；§7（`:299-318`）：P706-A03（`:307`）/A04（`:308`）的切片归属；§8（`:320-328`）：非目标；§9（`:330-335`）：未验证项纪律。
  - **本文逐字节不改其任何冻结字节、参数或拒绝码**；只补充**读侧**的数据流、有界 IO、隔离暂存/迁移与 token 契约。
- **已批准的 06.B 导出规格（承重上游，D-177）**：`docs/specs/2026-09-24-p7-06-backup-export-design.md`（approved）。本文**消费其产物**（其 §3.5 相 1 的「私有暂存内完整认证容器」即「可被独立预检的产物」，其 §7 `:290` 明述）；其 §2.2（`:102-107`，06.C 明确范围外）与 §7（`:275-290`，导出侧不承担容器拒绝）界定本文的起点。**本文不重开其任何写入端决定。**
- **已批准的 06.1 规格（承重权威，D-176）**：`docs/specs/2026-09-24-p7-06-stable-storage-runtime-owner-design.md`（approved）。本文**复用**其 owner/lease/generation 契约（§4.2 `:137-171` 的端口签名形状、§4.4 `:212-215` 的 generation 语义、§4.5 `:217-225` 的 fail-closed）；**不改其契约**。
- **阶段计划（本地只读，主 checkout）**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md`：§6 子项表（`:133-138`；**06.C 行 `:137`**）、§6.1（`:140-156`；**层责任表 `:148-154`**，其中 `:150` 的 `PreflightRestore`/`ConfirmRestore` 与**不透明 token**要求、`:151` 的「不运行 seed bootstrap」、`:152` 的平台适配器责任）、§6.2（`:158-166`；**恢复状态机 `:162`**、技术门 `:164`、清理策略 `:166`）、§6.3（`:168-194`；**切片 `06.3 / 06.C` 行 `:175`**、验收向量 P706-A03 `:183` / A04 `:184`、`:194` 明述全部为未来验收）。该计划文件的「推荐/建议」均为 proposal，不构成产品行为、迁移、技术选型或发布授权。
- **决定**：`docs/DECISIONS.md` D-174（`:3397` 起：批准容器格式规格与 Q13/Q14 设计级方案，并在第 4 条把「旧 schema 支持集与严格结构识别/迁移门」归入 **06.C**）、D-176（`:3436` 起：批准 06.1 稳定存储与 `LedgerRuntimeOwner` 设计）、D-177（`:3459` 起：批准 06.B 导出设计）、D-178（`:3483` 起：06.1 Android 启动路径 P0 缺陷修复登记，当前最高 id）。本条只**承接**其已冻结裁决，不修改任何一条。
- **开发规范**：`docs/CONTRIBUTING.md:165`（正式文档以中文为主、代码标识符保留英文）、`:168`（新建/实质修改的 `docs/specs/` 设计必须标记 `approved`/`proposal`/`superseded`/`historical`）、`:171`（文档不得含本机绝对路径、个人账务数据或临时讨论记录）。
- **源码现实（逐条复核，file:line）**：见 §1。
- **不可触碰面**：`.external/` 只读；零产品代码、零测试、零 schema/迁移、零依赖；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动；**不改容器格式规格的任何冻结字节布局、参数值或拒绝码**；不改 D-156/D-158/D-174/D-176/D-177 及既有 P7-01～P7-05 冻结面。

## 1. 现实与差距（逐条复核，file:line）

### 1.0 本 worktree 的文档与基线范围

- 本 worktree 分支 `UL-p7-06cspec2` 基点 `7199c56`；`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` 与 `local/artifacts/p7-06-gate/` 为**主 checkout 的本地只读文件**，本 worktree 内不存在（本基点实读 `ls` 确认）。故本文对计划与门证据的引用一律以「主 checkout 只读」标注，行号以主 checkout 实读为准。
- `docs/CONTRIBUTING.md` 在本基点与主 checkout **逐字节相同**（实读 `diff -q` 一致）；本规格引用的 `:165`/`:168`/`:171` 为本基点实读行号。

### 1.1 无读侧容器解析器：只有写侧常量与头部**装配器**

| 事实 | 位置 | 对 06.C 的含义 |
| --- | --- | --- |
| 写侧常量齐备 | `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/backup/BackupContainerFormat.kt`：`BACKUP_CONTAINER_MAGIC` `:19`、`BACKUP_CONTAINER_FORMAT_VERSION` `:22`、`BACKUP_KDF_ID` `:25`、`BACKUP_AEAD_ID` `:28`、`BACKUP_KDF_ITERATIONS` `:31`、`BACKUP_SALT_LENGTH` `:34`、`BACKUP_IV_LENGTH` `:37`、`BACKUP_TAG_LENGTH` `:40`、`BACKUP_FIXED_HEADER_LENGTH` `:43`、`BACKUP_KEY_LENGTH_BITS` `:46`、`BACKUP_STREAM_CHUNK_BYTES` `:49`、`BACKUP_MAX_PLAINTEXT_BYTES` `:52`、`BACKUP_MAX_CONTAINER_BYTES` `:55`、`BACKUP_DISK_HEADROOM_BYTES` `:58`、`BACKUP_MIN_PASSWORD_CODE_POINTS` `:61` | 读侧可复用这些常量与 `backupContainerOverheadBytes()`（`:69`），**不新增数值** |
| 只有头部**装配**，无**解析** | `backupContainerHeader`（`:89-111`）写固定头部；`backupContainerAad`（`:114-122`）拼 AAD；`writeU16`/`writeU32`/`writeU64`（`:124`/`:133`/`:143`） | **没有**任何 `readU16`/`readU32`/`readU64`、没有头部解析、没有范围校验（本基点对产品源码检索 `readU16`/`readU32`/`readU64`/`fun readU` **零命中**）。读侧解析器为 06.C 新增 |
| 2 GiB 容器上限已声明但**未被使用** | `BACKUP_MAX_CONTAINER_BYTES` `:55` | 全仓检索该标识符**仅**出现在其定义行（本基点实读），故该上限**当前无任何执行点**；06.C 是它的第一个使用者（§4.1） |
| 写侧端口形状可参照 | `BackupPlaintextSource`/`BackupPlaintextReader`/`BackupContainerSink`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/backup/BackupContainerWriter.kt:21`/`:26`/`:31`）、`writeBackupContainer`（`:56`）、`BackupContainerWriteResult`（`:40`） | 读侧的「有界分块读 + 有界分块写」应沿用同一 commonMain 闭包/值类型形状（§8.1） |

### 1.2 无读侧 crypto：只有加密器

| 事实 | 位置 | 对 06.C 的含义 |
| --- | --- | --- |
| 端口只有四个能力 | `interface BackupCryptoPrimitives`（`BackupContainerFormat.kt:158`）：`randomBytes` `:160`、`deriveKey` `:167`、`sha256Digest` `:175`、`gcmEncryptor` `:181`；`BackupSha256Digest` `:189`；`BackupGcmEncryptor` `:205` | **没有**解密器；`deriveKey`/`sha256Digest` 可读侧复用，`gcmEncryptor` 需新增**对称的**解密端口 |
| JVM 实现只有加密模式 | `JvmBackupCryptoPrimitives`（`ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/backup/JvmBackupCryptoPrimitives.kt:24`）：`deriveKey` `:35`（`PBEKeySpec` + `clearPassword()` `:46-48`）、`gcmEncryptor` `:53-63`（`ENCRYPT_MODE` + `GCMParameterSpec` + `updateAAD` 先供给 `:61`）；算法常量 `BACKUP_KDF_ALGORITHM` `:67`、`BACKUP_AEAD_TRANSFORMATION` `:70` | 06.C 须加 `DECRYPT_MODE` 解密器（§5.1）。**流式解密不得用 `CipherInputStream`**（容器规格 §4.9 `:217` 明令），故须显式 `update`/`doFinal` |
| 现有 SHA-256 只接受整块 | 产品手写 `Sha256`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/JcsSha256.kt:46`）只接受完整 `ByteArray`；增量能力由 `MessageDigest.update` 提供（`JvmBackupCryptoPrimitives.kt:80` 的 `digest.update(bytes, offset, length)`；`:51` 是 `MessageDigest.getInstance`） | `payload_sha256` 的认证后校验必须**流式**（§5.4），复用 `sha256Digest()` |

### 1.3 无「打开用户来源」端口：只有保存端口；导入端口有界为 16 MiB

| 事实 | 位置 | 对 06.C 的含义 |
| --- | --- | --- |
| 只有**保存**端口 | `BackupTargetPort`/`BackupTargetWriter`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/BackupExport.kt:121`/`:134`）；平台实现 `DesktopBackupTargetPort`（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopBackupTargetPort.kt:21`，`JFileChooser` `:82-85`）、`AndroidBackupTargetPort`（`android-app/src/main/kotlin/com/unifiedledger/android/AndroidBackupTargetPort.kt:32`，SAF `CreateDocument`） | 06.C 须新增**打开来源**端口（§8.1）；现有端口是写侧，不可复用 |
| `LedgerFileSystem.openRead` 是**路径**式 | `LedgerStableStorage.kt:136`（`openRead(path)` 接口声明）、平台实现 `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopLedgerFileSystem.kt:116` 与 `android-app/src/main/kotlin/com/unifiedledger/android/AndroidLedgerFileSystem.kt:111`（均为 `FileInputStream`） | 没有 SAF/URI 形状；用户选择的 SAF 文档不能直接喂给它 |
| 导入端口有界为 16 MiB **整块** | `ImportFilePick.kt`：`IMPORT_FILE_PICK_MAX_READ_BYTES` `:6`（16 MiB）、`ImportFilePickPort` `:112`、`readImportPickBounded` `:147`、`PickedImportFile` `:52`（含可空 `sizeBytes` `:54`） | **不可**用于 ~2 GiB 容器（整块读入内存违反容器规格 §4.8 `:202`）。但其**形状**（commonMain 闭包 + 可空平台 size 元数据）是 06.C 来源端口的先例，**可空 size** 正是 §4.3 的开放设计点来源 |
| 平台打开先例 | Android SAF `ActivityResultContracts.OpenDocument`（`App.kt:179`）+ `ContentResolver.openInputStream`（`App.kt:208`）；桌面 `JFileChooser` 打开（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/ImportFilePick.kt:103-109`） | 06.C 的两个平台适配器沿用同一先例（§8.1） |

### 1.4 隔离迁移所需的「按绝对路径打开、不 create-on-open、不自动迁移」表面两端均缺

| 事实 | 位置 | 对 06.C 的含义 |
| --- | --- | --- |
| 桌面可开任意绝对路径，但**开即创建** | `DesktopBackupSnapshotPort.verify`（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/DesktopBackupSnapshotPort.kt:32-33`）用 `JdbcSqliteDriver("jdbc:sqlite:$snapshotPath")`；组合根 `Main.kt:871` 同款 | 桌面 `JdbcSqliteDriver` 本身**不做**迁移（迁移是显式调用 `migrateToCurrentSchema`，`Main.kt:873`/`:928`），故「不自动迁移」可满足；「不 create-on-open」需在打开前先确保文件已存在（隔离副本是我们刚写出的，天然存在） |
| 桌面宽松补戳迁移路径（**禁止复用**） | `migrateToCurrentSchema`（`desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:928-977`）：尤其 `:936-939`（有 `catalog_version` 即补戳）、`:940-952`（v27 哨兵迁移）、`:957-964`（未知已填充库**直接补当前版本戳**） | 容器规格 §5.4 `:260` 明令**不得**复用于外来备份；06.C 必须另立**严格**迁移路径（§6.3） |
| 桌面**严格**分支已存在，可作形状参照 | `Main.kt:965`（`from == currentVersion` 直开）、`:966-972`（`from < currentVersion` 单事务 `Schema.migrate` + 戳版本）、`:973-976`（`from > currentVersion` fail-closed） | 06.C 的严格迁移采用同款「仅白名单内版本走 `Schema.migrate`」形状，**但不含任何 `from == 0L` 补戳分支** |
| Android 只有**只读**按路径打开，且不能迁移 | `verifyAndroidSnapshotFile`（`ledger-data/src/androidMain/kotlin/com/unifiedledger/data/AndroidLedgerDatabaseHandle.kt:161-181`）用 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`（`:162`），运行**零** schema/版本逻辑 | 只读，**不能**跑 `Schema.migrate`；06.C 须新增按绝对路径的**读写**打开（`OPEN_READWRITE`，无 `CREATE_IF_NECESSARY`）（§8.2） |
| Android `AndroidSqliteDriver` 开即创建并跑版本逻辑 | 工厂 `createAndroidLedgerDatabase`（`AndroidLedgerDatabaseHandle.kt:10-70`）经 `AndroidSqliteDriver(schema, context, name, callback)`（`:15-20`） | **不得**用于隔离副本（会跑 create/version 逻辑）。其 P0 缺陷记录（`:140-156`）说明 `version < 1` 的 `NoOpSnapshotSchema` 在**构造期**即抛 `IllegalArgumentException`，故「用零版本 schema 的 AndroidSqliteDriver」不可行 |
| **Android 有既有的非创建式、可迁移 `SqlDriver` 构造器（本规格的基线更正）** | `app.cash.sqldelight:android-driver:2.3.2`（`build.gradle.kts:4`）暴露**公开**构造器 `AndroidSqliteDriver(SupportSQLiteDatabase)`（本基点从缓存 AAR 的 `javap -p` 实读：`public app.cash.sqldelight.driver.android.AndroidSqliteDriver(androidx.sqlite.db.SupportSQLiteDatabase)`、`(SupportSQLiteDatabase, int)`、`(SupportSQLiteDatabase, int, Long)`）；其私有 4 参构造器（`:openHelper, :database, :cacheSize, :windowSizeBytes`）以 `(openHelper != null) xor (database != null)` 断言，**当 `openHelper == null` 时从不触碰 helper**（字节码 `database_delegate$lambda$0` 在 helper 为 null 时直接返回传入的 database）。配套的 `androidx.sqlite:sqlite-framework-android:2.6.2` 提供 `public FrameworkSQLiteDatabase(android.database.sqlite.SQLiteDatabase)`（仅持有 framework 句柄，无 create/version 逻辑） | 故存在**既有**的非创建式路径：`SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)`（**不带** `CREATE_IF_NECESSARY`，故不 create；无 schema/version 回调，故不自动迁移）→ `FrameworkSQLiteDatabase(frameworkHandle)` → `AndroidSqliteDriver(supportDatabase)`，得到一个**可跑 `Schema.migrate` 的 `SqlDriver`**。**06.C 的 Android 交付物因此不必依赖新增驱动代码**；实施批应优先用该路径，仅当它被证明不足时才回退到自定义适配器（§8.2、§10 第 8 项） |
| 驱动对组合根私有，只有受控入口先例 | `AndroidLedgerDatabaseHandle` 的 `private val driver`（`:85`）、`runQueryStatisticsOptimize`（`:102`）、`runFullAnalyze`（`:120`）、`runSnapshotInto`（`:130`） | 隔离副本的迁移/校验须按**同一形状**在句柄或平台适配器上新增受控入口，委托到 commonMain driver 级 helper |
| commonMain driver 级 helper 先例 | `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/BackupSnapshotDriver.kt`：`runSnapshotIntoOn` `:29`、`SnapshotVerification` `:42`、`snapshotIntegrityOk` `:58`、`verifySnapshotOn` `:69`、`readSnapshotUserVersion` `:88` | 06.C 的「读 `user_version` + `integrity_check` + 迁移 + FK 校验」helper 沿用同一文件形状 |

### 1.5 无预检用例、无不透明 token、无独立领域完整性/FK 校验面

- **无 `PreflightRestore`/`ConfirmRestore`（备份语义）**：全仓检索 `PreflightRestore`/`ConfirmRestore` 只命中**回收站恢复**（`P503UiEvent.ConfirmRestore`，`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503UiEvent.kt:780`）与导入 JSON 的 `strictJsonPreflight`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/StrictJsonPreflight.kt:14`），**无**备份预检用例、**无**不透明 token（本基点实读）。
- **owner 具备 generation/lease 原语**：`LedgerRuntimeOwner`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/LedgerRuntimeOwner.kt:209`）、`activeGeneration`（`:230`，`:229` 是 `@Volatile` 注解行）、`acquireLease`（`:321`，`tryLock` 非阻塞，非 Ready 返回 `RuntimeNotReady`）、`quiesce`（`:351`，有界 5 s，`LEDGER_QUIESCE_TIMEOUT_MILLIS` `:195`）、`closeActiveGraph`（`:392`）、`reopen`（`:413`）、`GenerationSelection`（`:74`）、`LedgerLease`（`:179`，其 `val generation` 在 `:181`）、`shouldDiscardLandingResult`（`:465`）、`LedgerLeaseScope.isCurrentGeneration`（`:641`）、`withFacade`（`:648`）、`leased`（`:670`）。06.B 已用 `BackupExportLaunch(request, generation)`（`:728`）建模「generation 捕获」形状，`backupExportLaunch`（`:569`）在 `activeGeneration` 为空时返回 null。
- **无独立领域完整性/FK/关系校验面（未验证项）**：对产品源码检索 `foreign_key_check` **零命中**；仅测试用 `pragma_foreign_key_check`（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/ImportSpineMigrationCoexistenceTest.kt:164`）。`PRAGMA integrity_check` 的产品面只有 `verifySnapshotOn`（`BackupSnapshotDriver.kt:69`）与 `verifyAndroidSnapshotFile`（`AndroidLedgerDatabaseHandle.kt:161`）。故「领域/关系检查」的**具体校验集**尚无既有表面（§10 第 6 项）。
- **路径穿越非问题**：容器格式为「简单头部 + 单数据库 payload」，**无归档**（容器规格 §8 `:323`），故无路径穿越/重复条目/白名单/解压上限四类契约的需求；计划 `:137` 的「若归档解包则拒绝路径穿越」在 v1 格式下**不适用**。

### 1.6 稳定存储与暂存清理现状

- 布局：`LedgerStorageLayout`（`LedgerStableStorage.kt:191`），`backupStagingDirectory`（`:207`）、`backupSnapshotFile`（`:210`）、`backupContainerFile`（`:213`）、`generationDirectory`（`:217`）、`mainFile`（`:219`）。暂存前缀 `LEDGER_BACKUP_SNAPSHOT_PREFIX` `:51`、`LEDGER_BACKUP_CONTAINER_PREFIX` `:54`。
- 启动清理：`sweepBackupStaging`（`LedgerStableStorage.kt:467-478`）**只删** `snapshot-`/`container-` 前缀（`:473`）；由 `openStableStorageLedger`（`LedgerRuntimeOwner.kt:761`，清理点 `:777`）在每次启动调用。
- 空间原语已存在：`LedgerFileSystem.usableSpace`（`LedgerStableStorage.kt:128`），Android 用 `StatFs`（`AndroidLedgerFileSystem.kt:105`）、桌面用 `FileStore.usableSpace`（`DesktopLedgerFileSystem.kt:110`）；06.B 对其**可空返回**采用 fail-open（`BackupExport.kt:242` 的 `available != null &&` 分支）。
- 有界流端口已存在：`openRead`（`:136`，平台 `FileInputStream`）、`openWrite`（`:148`，原子 rename 语义）、`listDirectory`（`:156`）、`LedgerReadStream`（`:160`）、`LedgerWriteStream`（`:173`）。

## 2. 目标与范围

### 2.1 目标（06.3 / 06.C）

- **有界读取与预认证格式检查**：从用户选择的来源取得有界流，执行 2 GiB 容器上限、读 59 字节固定头部、逐条执行容器规格 §4.3.1 与 §4.8 的认证前检查（计划 `:137`）。
- **认证解密**：KDF + 流式认证解密到**私有暂存**，标签通过前不使用明文（容器规格 §4.9）。
- **认证后结构/身份检查**：payload 权威 `PRAGMA user_version`（§4.3.2/§5.4）与身份/账本检查（§5.4）。
- **隔离迁移与验证**：在**隔离副本**上按**严格**白名单迁移受支持旧 schema，并在**不运行 seed bootstrap** 的前提下做完整性/FK/领域校验（计划 `:137`、`:151`）。
- **预览摘要与不透明 token**：绑定已认证工件、目标账本与当前运行代际；确认不得重新读取可被替换的外部文件（计划 `:150`）。
- **拒绝路径零当前库写入**：任何拒绝都不触碰当前库与活动指针（计划 `:175`、A03 `:183`）。
- **验收映射**：P706-A03（`:183`）/A04（`:184`）的 06.C 贡献与后续归属（§9）；**不标任何 PASS**。

### 2.2 明确范围外（后续切片或本批不做）

- **06.D 恢复切换**：明确确认、quiesce/close、原子切换与回退、重建用例/刷新、活动指针写入、新代发布（计划 `:138`/`:176`、容器规格 §5.3/§5.5）。本文只产出**预检结果与 token**，**不**做任何切换或指针写入。
- **任何 schema/迁移边/依赖变更**：schema 停留 v31，零 DDL、零 `.sqm`（计划 `:175` 只要求「受支持迁移」，不含新迁移边）。
- **容器格式的任何改动**：不改 D-174 的冻结字节/参数/拒绝码/AAD/资源上限。
- **06.1 owner 契约的任何改动**：不改 D-176 的端口签名与 fail-closed 语义。
- **`ConfirmRestore` 的完整实现**：本文只定义 token 契约与其在确认时的绑定校验，不实现确认用例（属 06.D）。
- **OS 自动备份/设备迁移排除决策**（归隐私规格；容器规格 §4.9 `:219`、§6 `:291`）；**不声称**云备份/设备迁移已关闭。

## 3. 预检数据流（计划 `:162` 的 `Reading → Authenticating → Staging → Validating/Migrating → PreviewReady` 段）

预检按以下**冻结顺序**执行，每步给出失败/取消行为。**核心不变量**：任何失败/取消路径都**不**写入当前库、**不**触碰活动指针、**不**发布任何 generation。

```text
1) 取得有界来源流（用户选择的容器），复制到私有暂存容器（同时计数，执行 2 GiB 上限）
2) 读 59 字节固定头部，执行全部认证前公开格式/大小检查（§4.1）
3) 有界磁盘前置检查（恢复侧公式的 06.C 子集；空间不足即拒绝，不开始解密）（§6.2）
4) 密码录入
5) KDF + 流式认证解密到私有暂存明文快照；doFinal 校验标签
6) 标签通过后校验 payload_sha256（存储层损坏检测，§5.4）
7) 认证后 payload 结构检查：隔离副本上权威读 PRAGMA user_version（§4.2）
8) 认证后身份/账本检查（§4.2）
9) 受支持旧 schema 的严格隔离迁移（§6.3）
10) 隔离副本上的完整性/FK/领域校验（不运行 seed bootstrap，§6.4）
11) 产出预览摘要与不透明 token（§7）
```

**与计划状态机的对应**：计划 `:162` 的 `Reading → Authenticating → Staging → Validating/Migrating → PreviewReady` 是**粗粒度**阶段；本节的 11 步是 06.C 在其 `Reading`/`Authenticating`/`Staging`/`Validating/Migrating` 段内的**细化**。磁盘前置检查（步骤 3）落在计划的 `Staging` 段（容器 §4.8 的恢复侧公式是它的权威依据），**不**新增阶段。

### 3.1 步骤 1：有界来源 → 私有暂存容器

- 平台来源端口返回一个有界分块读流（§8.1）。预检把来源**一次性复制**到私有暂存容器文件（`restore-container-<token>`，命名归实施批），按 `BACKUP_STREAM_CHUNK_BYTES`（64 KiB，`BackupContainerFormat.kt:49`）分块读写；复制**同时计数**（计数是上限判定与大小取得的共同手段，见下两条）。
- **2 GiB 上限（认证前，容器规格 §4.8 `:197`）**：容器规格 `:197` 要求文件大小 > 上限时**「不读取」**即拒绝。故：
  - **provider 报告大小且 > `BACKUP_MAX_CONTAINER_BYTES`（`BackupContainerFormat.kt:55`）→ 在读任何字节之前**类型化拒绝 `P706_CONTAINER_TOO_LARGE`（**精确的「不读取」**，对应容器规格 `:197`）。
  - **provider 未报告大小（或报告值不可信）→ 计数流作为登记回退**：一边有界分块复制到私有暂存、一边累计字节；一旦累计 > 上限即类型化拒绝 `P706_CONTAINER_TOO_LARGE` 并停止读取（**不再读取**剩余字节，不写满磁盘）。**该回退是本规格登记的替代**（§10 第 5 项），因为它不可避免地读取到上限为止；当 provider 报大小可用时**必须**走上面的「不读取」路径。
  - **计数上限在每一条路径上都生效（不得只依赖 provider 元数据）**：即使 provider 报告了一个 ≤ 上限的大小，复制仍**始终**累计字节并在累计 > 上限时立即拒绝。故一个**谎报** `reportedSize`（报小、实际流更多）的 provider **无法**绕过 2 GiB 上限；`reportedSize` 只用于「报大即提前拒绝」的快速路径，**不**作为信任边界。
- **可信容器大小的取得（本规格的开放设计点，给出推荐与替代）**：来源可能**不报告**大小（先例：`PickedImportFile.sizeBytes` 可空，`ImportFilePick.kt:54`），而 §4.8 的自洽等式需要**精确**容器大小。
  - **推荐：单遍有界复制到私有暂存并计数**。复制完成后，容器大小 = 暂存容器文件的**实际长度**（`LedgerFileSystem.length`，`LedgerStableStorage.kt:76`），精确且不依赖 provider 元数据；后续头部解析与解密都读**私有暂存副本**（可重复打开），从而**不**受外部文件在预检期间被替换的影响（与 §7 的 token 绑定要求一致）。
  - **替代 A（否决）：要求 provider 报告大小，缺失即拒绝**。这会把「provider 不报大小」变成不可预检，且 SAF 外部 provider 常不报大小（`sizeBytes` 可空即为证据）。
  - **替代 B（否决）：对**外部**流做两遍（先计数、再重开解密）**。外部文件在两次打开之间可被替换，导致「计数用的字节」与「解密的字节」不一致，破坏自洽等式与 token 绑定的语义。
  - **替代 C（否决）：依赖 `plaintext_len` 反推容器大小**。`plaintext_len` 是**未认证**的头部字段（认证前只读），不能作为容器大小的可信来源（自洽等式正是要用它**校验**）。
  - **成本说明**：推荐方案需要一份与容器同量级的私有暂存（≤ 2 GiB），已计入 §4.8 的恢复侧磁盘公式（§6.2）。这是「不依赖外部文件、可重复读」的代价。
- **失败/取消**：来源端口返回 null（用户取消）→ `Cancelled`；复制中读失败/写失败 → 类型化失败（如 `P706_SOURCE_READ_FAILED`，**建议名**，见 §10 第 3 项）；任一失败路径都删除已产生的暂存容器 → 释放资源 → **不报告成功**。

### 3.2 步骤 2：读固定头部 + 认证前公开格式/大小检查

- 从**暂存容器**读前 `BACKUP_FIXED_HEADER_LENGTH`（59，`BackupContainerFormat.kt:43`）字节。若暂存容器长度 < 59，或读不足 59 字节 → **截断**，类型化拒绝。
- **截断码不得复用 `P706_CONTAINER_FORMAT_UNSUPPORTED`（本规格的明确选择）**：该冻结码的条件是 `magic != "ULBK"`（容器规格 §4.3.1 `:123`），而一个 < 59 字节的文件**无法**满足该条件（连 `magic` 都读不全）。故本规格**建议**一个**独立**截断码 `P706_CONTAINER_TRUNCATED`（**建议名，需批准**，见 §10 第 3 项）。**替代（登记，不采用）**：复用 `P706_CONTAINER_FORMAT_UNSUPPORTED` 会把「magic 不符」这一冻结条件**加宽**为「字节不足」，属对冻结面的语义加宽，须显式登记；本规格**不**采用该替代，以免悄悄改变冻结码的含义。
- 解析固定头部（大端无符号；§4.3），逐条执行 §4.1 的认证前检查表，**全部在解密前**、**全部短路安全**（容器规格 §4.7 类 1 `:186`）。
- **认证前检查范围**（verbatim，容器规格 §4.7 `:186`）：「§4.3.1 的全部固定头部拒绝行**加** §4.8 的三项认证前检查（『容器大小 vs `plaintext_len` 自洽』、容器输入上限 `P706_CONTAINER_TOO_LARGE`、明文 `plaintext_len` 上限）」。
- **失败**：各自映射到**独立**的类型化拒绝码（§4.1），**不**与统一密码学失败码混用。任何拒绝都不触碰当前库/活动指针。

### 3.3 步骤 3：有界磁盘前置检查（06.C 作用域）

- 在**开始解密之前**执行 06.C 的恢复侧磁盘前置检查（§6.2 给出公式与 06.C/06.D 的拆分）；**空间不足即类型化拒绝**（**建议名** `P706_INSUFFICIENT_SPACE`，见 §10 第 3 项），**不**开始解密、**不**创建迁移副本、**不**触碰当前库/活动指针。
- **为什么必须是一步**：容器规格 §4.8 把恢复侧磁盘前置列为**硬门**（`:201`「不足即拒绝，不得中途半切换」），且它必须在解密产生任何明文**之前**生效，否则「解密到一半发现磁盘不足」会留下半写明文。故它是数据流中一个**独立**的编号步骤，与 §6.2 一致。
- 该步**不读取** payload、**不**解密，故属认证前的**本地资源**检查（**不是**容器规格 §4.7 的密码学失败，也不构成 oracle）。

### 3.4 步骤 4：密码录入

- 密码在共享 UI 采集（计划 `:154` 的「预检摘要、明确替换确认」属 app-ui）；**读取端不施加长度门**（容器规格 §4.10 `:226`：只按 §4.4 参数与 §4.7 统一失败处理，以免旧容器因策略变化而不可解）。
- 密码**不**持久化、**不**进日志（容器规格 §4.10 `:225`、§4.9 `:218`；计划 `:136`）。UI 不接触文件路径、driver 或密码日志（计划 `:152`）。

### 3.5 步骤 5：KDF + 流式认证解密到私有暂存明文快照

- 加宽编码**逐字节复用**冻结构造（§5.2）；`kdf_iterations` 取**头部值**（已校验落在 `[600000, 10000000]`），salt 取头部后 `salt_len` 字节；派生 256-bit 密钥。
- **流式认证解密**：从暂存容器偏移 `59 + salt_len + iv_len` 起，读取 `plaintext_len + tag_len` 字节（密文 + 尾部标签），经解密器 `update` 输出明文**分块写入**私有暂存明文快照（`restore-snapshot-<token>`，命名归实施批）；`doFinal` 完成**标签校验**。**禁止 `CipherInputStream`**（容器规格 §4.9 `:217`）。
- **明文隔离**：认证期间解密输出只写私有暂存；**认证完成前绝不打开或迁移数据库**（容器规格 §4.9 `:216`）。故步骤 5 的暂存明文在此刻**不得**被当作可信输入。
- **统一失败（§4.7）**：错密码、标签校验失败、密文被篡改**全部**映射到**同一**码 `P706_CONTAINER_AUTHENTICATION_FAILED`、同一外部可观察行为、**同一时序量级**（§5.3）。
- **失败/取消**：认证失败 → **立即删除**暂存明文与暂存容器（容器规格 §4.9 `:218`）→ 释放租约/资源 → 返回统一失败码；取消（在 64 KiB 分块之间检查）→ 删除暂存 → `Cancelled`。**不报告成功**。

### 3.6 步骤 6：认证后 `payload_sha256` 校验

- **仅在 `doFinal` 成功（标签通过）之后**，对暂存明文做**流式** SHA-256，与头部 `payload_sha256` 比较。相等 → 继续；不等 → **认证后完整性异常**（§5.4）。
- 该检查**不得**提前到解密前（容器规格 §4.7 `:191` 禁止先比较 `payload_sha256` 再解密），也不得用于对未认证输入短路。

### 3.7 步骤 7：认证后 payload 结构检查（权威 `user_version`）

- **认证后、payload 结构检查（容器规格 §4.7 类 2 `:187`）**：在暂存明文的**隔离副本**上打开数据库，执行**权威** `PRAGMA user_version`（§4.2）。头部 `db_schema_version` **仅为非权威提示**（§4.3.2 `:136`），**不得**据此决定接受或迁移。
- **版本判定（三类拒绝，全部为类 2）**：以 payload 实际 `user_version` 为准，下列三种情形**一律**类型化拒绝 `P706_SCHEMA_VERSION_UNSUPPORTED`（容器规格 §4.3.2 `:136`、§5.4 `:259`）：
  1. `user_version` **未知**（不是本产品认识的 schema 版本）；
  2. `user_version == 0`（无版本戳）；
  3. `user_version` **高于**当前支持 schema（v31）。
- **头部提示与 payload 实际值不一致 → 拒绝（不得静默接受或迁移）**：容器规格 §4.3.2 `:136` 与 §5.4 `:259` **明令**：「头部提示与 payload 实际 `user_version` 不一致时，**以 payload 为准并触发该结构拒绝码**」（§5.4 `:259`：「以 payload 为准并按 `P706_SCHEMA_VERSION_UNSUPPORTED` 做结构拒绝」）。即：当头部 `db_schema_version` 与 payload 实际 `user_version` **不相等**时，本步**必须**以 `P706_SCHEMA_VERSION_UNSUPPORTED` 拒绝，**不得**「以 payload 为准后继续接受或迁移」。这是**类 2**（认证后）检查，**不**是认证前短路，也**不**是密码 oracle（§4.7 `:190`）。
  - **为何不能弱化为「以 payload 为准后继续」**：头部 `db_schema_version` 与 salt 同受 AAD 保护（§4.6 `:175`），故头部与 payload 不一致**不可能**来自传输篡改，只能是**写入端或本地存储不一致**的信号；冻结权威选择把它当作结构拒绝，而非「用 payload 覆盖提示」。本文**不**采用更弱的解读。
  - **若未来确需允许不一致**：必须先重开容器规格（D-174）并取得显式批准；本规格**不**提供该路径（§11）。
- `user_version` 在**受支持旧 schema 白名单**内（且头部提示一致）→ 进入步骤 9 的严格迁移（§6.3）。
- **本步绝不**在认证完成前运行（容器规格 §4.7 `:190`）；它是类 2，**不是**密码 oracle。
- **失败** → 删除暂存（明文 + 容器）→ 返回类型化拒绝，**不**触碰当前库/活动指针。

### 3.8 步骤 8：认证后身份/账本检查

- **认证后、身份/账本检查（容器规格 §4.7 类 3 `:188`）**：判定 ledgerId 是否为固定身份且库中**无额外账本**（§4.2）。跨账本容器**类型化拒绝**（容器规格 §5.4 `:261`）。
- 同样**绝不**在认证完成前运行（容器规格 §4.7 `:190`）。
- **失败** → 删除暂存 → 类型化拒绝（**建议名**，见 §10 第 3 项）；**不**触碰当前库/活动指针。

### 3.9 步骤 9：受支持旧 schema 的严格隔离迁移

- 在**隔离副本**（`restore-migrated-<token>`，命名归实施批）上执行**严格**迁移（§6.3）；**原库完全不变**（计划 `:175`、A04 `:184`）。
- 迁移失败 → 删除隔离副本 → 类型化拒绝（**建议名** `P706_MIGRATION_FAILED`，见 §10 第 3 项）；**不**触碰当前库/活动指针。**绝不**回退到桌面宽松补戳路径（§6.3）。

### 3.10 步骤 10：隔离副本上的完整性/FK/领域校验（不运行 seed bootstrap）

- 在**迁移后**的隔离副本上执行 `PRAGMA integrity_check`（复用 `snapshotIntegrityOk` 的判定形状，`BackupSnapshotDriver.kt:58`）与 FK/领域关系校验（§6.4）。
- **不运行 seed bootstrap**（计划 `:151` 明令「不运行 seed bootstrap 掩盖缺表/缺事实」）。具体地：**不得**调用 `store.bootstrap(ledgerId, defaultCatalogSeed())`（`App.kt:728`、`Main.kt:851`）；校验必须直接读隔离副本的权威事实，缺表/缺事实一律**类型化拒绝**而非被种子掩盖。
- 失败 → 删除隔离副本 → 类型化拒绝（**建议名** `P706_DOMAIN_VALIDATION_FAILED`，见 §10 第 3 项）。

### 3.11 步骤 11：预览摘要与不透明 token

- 产出预览摘要（§7.3）与不透明 token（§7.1），返回 `PreviewReady`。**此处仍零当前库写入、零活动指针写入**（计划 `:175`）。

## 4. 读侧容器解析器契约

### 4.1 认证前检查（逐条 verbatim，容器规格 §4.3.1 与 §4.8）

固定头部字段（偏移以容器首字节为 0，大端无符号；容器规格 §4.3 `:98-113`）：

| 偏移 | 长度 | 字段 | 读侧取值 |
| --- | --- | --- | --- |
| 0 | 4 | `magic` | 必须为 ASCII `ULBK` |
| 4 | 2 | `container_format_version` | 必须为 `1` |
| 6 | 1 | `kdf_id` | 必须为 `1` |
| 7 | 1 | `aead_id` | 必须为 `1` |
| 8 | 4 | `kdf_iterations` | 必须落在 `[600000, 10000000]` |
| 12 | 1 | `salt_len` | 必须落在 `16..32` |
| 13 | 1 | `iv_len` | 必须为 `12` |
| 14 | 1 | `tag_len` | 必须为 `16` |
| 15 | 4 | `db_schema_version` | 非权威提示；不用于接受/迁移判定 |
| 19 | 8 | `plaintext_len` | 上限 1 GiB；参与自洽等式 |
| 27 | 32 | `payload_sha256` | 认证后校验（§5.4） |

认证前类型化拒绝码（**逐条 verbatim**，容器规格 §4.3.1 `:121-130`）：

| 条件（读固定头部即判定） | 拒绝码 |
| --- | --- |
| `magic != "ULBK"` | `P706_CONTAINER_FORMAT_UNSUPPORTED` |
| `container_format_version != 1` | `P706_CONTAINER_VERSION_UNSUPPORTED` |
| `kdf_id != 1` | `P706_KDF_UNSUPPORTED` |
| `aead_id != 1` | `P706_AEAD_UNSUPPORTED` |
| `salt_len ∉ 16..32` | `P706_KDF_PARAMETERS_UNSUPPORTED` |
| `iv_len != 12` | `P706_AEAD_PARAMETERS_UNSUPPORTED` |
| `tag_len != 16` | `P706_AEAD_PARAMETERS_UNSUPPORTED` |
| `kdf_iterations ∉ [600000, 10000000]` | `P706_KDF_PARAMETERS_UNSUPPORTED` |

认证前资源/大小检查（容器规格 §4.8 `:195-201`、§4.7 类 1 `:186`）：

| 条件 | 拒绝码 | 出处 |
| --- | --- | --- |
| 容器大小 > 2 GiB | `P706_CONTAINER_TOO_LARGE` | §4.8 `:197`（**冻结**） |
| `plaintext_len` > 1 GiB | **未命名**（§4.8 `:198` 只说「超限即拒绝」） | §4.8 `:198`；建议名见 §10 第 3 项 |
| `plaintext_len + 59 + salt_len + iv_len + tag_len != 容器大小` | **未命名**（§4.8 `:199` 只说「不成立即拒绝」） | §4.8 `:199`；建议名见 §10 第 3 项 |

**约定（承接容器规格 §4.3.1 `:132`）**：以上为 v1 冻结值；任何新增取值必须先升 `container_format_version` 并在规格中显式登记，不得就地扩展 v1 语义。

### 4.2 认证后检查（类 2 / 类 3，容器规格 §4.7 `:187-188`）

- **类 2（payload 结构）**：`P706_SCHEMA_VERSION_UNSUPPORTED`（**冻结**）——仅当**认证成功且 payload 已打开**后，对 payload 实际 `PRAGMA user_version` 判定（§3.7）。触发条件为四项：(a) 版本未知；(b) `user_version == 0`；(c) 高于当前支持 schema；(d) **头部 `db_schema_version` 提示与 payload 实际 `user_version` 不一致**（容器规格 §4.3.2 `:136`、§5.4 `:259`：不一致时「以 payload 为准**并触发该结构拒绝码**」）。
- **类 3（身份/账本）**：跨账本/额外账本拒绝码**未命名**（§5.4 `:261` 只规定「类型化拒绝」）；建议名见 §10 第 3 项。
- **时机硬约束**：类 2/类 3 **绝不**在认证完成前运行（容器规格 §4.7 `:190`）；实现必须把「读头部/大小检查」与「读 payload」在代码路径上分开，使类 2/类 3 只有在 `doFinal` 成功后可达。

### 4.3 解析器形状

- 头部解析为**纯函数**（`ByteArray(59) → 头部值对象或类型化拒绝`），无 IO、无密码、可 commonTest 直接断言（与写侧 `backupContainerHeader` 的纯函数形状对称，`BackupContainerFormat.kt:89`）。
- 大端无符号读取需**新增** `readU16`/`readU32`/`readU64`（本基点零命中，§1.1）。`u64` 的 `plaintext_len` 必须以无符号语义处理，超 `Long` 安全范围/为负即拒绝。
- 具体类型/包名归实施批（与兄弟规格一致）。

## 5. 读侧 crypto 契约

### 5.1 新增的解密原语

- 在 `BackupCryptoPrimitives`（`BackupContainerFormat.kt:158`）上新增对称的解密端口（**端口名归实施批**，形状须对称于 `gcmEncryptor` `:181`）：

```text
fun gcmDecryptor(key: ByteArray, iv: ByteArray, aad: ByteArray): BackupGcmDecryptor

interface BackupGcmDecryptor {
    // 返回本块密文解出的明文（GCM 可能缓冲，故可为空）
    fun update(bytes: ByteArray, offset: Int, length: Int): ByteArray
    // 校验标签并返回剩余明文；标签失败抛平台 AEAD 异常
    fun doFinal(): ByteArray
}
```

- **标签的供给方式（与端口形状一致）**：声明的端口**只有** `doFinal()`（**无** `doFinal(byte[])` 重载）。因此**尾部 `tag_len` 字节必须经 `update` 喂入**，随后调用无参 `doFinal()` 完成标签校验；**不**依赖任何带参 `doFinal`。（若实施批更倾向带参重载，则须**同时**在该端口上声明该重载，保持「声明的形状」与「散文」一致。）

- JVM 实现（`JvmBackupCryptoPrimitives`，`:24`）以 `Cipher.getInstance(BACKUP_AEAD_TRANSFORMATION)`（`:70`）+ `init(DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BACKUP_TAG_LENGTH * 8, iv))` + `updateAAD(aad)` **先于任何 `update`/`doFinal`**（容器规格 §4.5 `:163`）实现。**零新依赖**（容器规格 §8 `:322`）。

### 5.2 KDF 复用（不改参数）

- 加宽编码**复用** `widenBackupPassword`（`BackupContainerFormat.kt:77`）——即容器规格 §4.4 `:146-153` 的冻结构造（UTF-8 → 每字节取低 8 位 → `char`）。**禁止** `password.toCharArray()` 直构；**不做** Unicode 规范化。
- 密钥派生**复用** `deriveKey`（`BackupContainerFormat.kt:167`）；迭代次数取**头部值**（已校验在 `[600000, 10000000]`），salt 长度取头部 `salt_len`（`16..32`），派生长度固定 256 bit。

### 5.3 流式解密形状与统一失败

- **流式形状**：从暂存容器读取 `plaintext_len + tag_len` 字节，按 `BACKUP_STREAM_CHUNK_BYTES`（64 KiB）分块喂给解密器 `update`（**含尾部 `tag_len` 字节**，见 §5.1 的标签供给方式），明文**分块写入**暂存明文快照；随后调用无参 `doFinal()` 完成**标签校验**。硬约束是：**`doFinal` 返回成功之前，暂存明文不得被当作可信输入**。
- **禁止 `CipherInputStream`**（容器规格 §4.9 `:217`：它会在标签校验前返回明文）。
- **统一失败**：错密码、标签校验失败、密文篡改**全部**映射到 `P706_CONTAINER_AUTHENTICATION_FAILED`（**冻结**，§4.7 `:182`），**同一**外部可观察行为与**同一时序量级**；**禁止**对上述情形给出不同错误文案或不同耗时（§4.7 `:183`）。
- **时序属性的边界**：该「同一时序量级」要求**仅**约束「错误密码」与「密文/标签损坏」这一对（§4.7 `:183`）。认证前公开格式检查（§4.1）允许提前短路，且**不**构成 oracle（它们不依赖密码、不依赖解密，§4.7 `:186`）。
- **KDF 成本有界**：`kdf_iterations` 上界 10,000,000（§4.4 `:142`）保证恶意容器只在**有界** CPU 上消耗；KDF 与流式解密**必须在后台线程**执行并以**进度/可取消**方式呈现（容器规格 §4.8 `:210`、§4.4 `:142`）。

### 5.4 `payload_sha256` 的认证后角色

- **仅在标签通过后**用于**存储层损坏检测**（容器规格 §4.7 `:191`）：对暂存明文做**流式** SHA-256，与头部 `payload_sha256` 比较。
- 不等 → 认证后完整性异常，可映射到 `P706_PAYLOAD_INTEGRITY_FAILED`（容器规格 §4.7 `:183`，**「可」非「必须」**）。其失败路径与时序**不必**与错误密码匹配（该情形已被 GCM 认证排除为「密码错误」，不构成 oracle，§4.7 `:183`）。
- **不得**在解密前比较 `payload_sha256`（§4.7 `:191`）；**不得**把它当作安全边界。
- **本规格的设计立场**：由于固定头部是 AAD（§4.6 `:175`），标签通过即意味着头部（含 `payload_sha256`）与明文均未被篡改；因此标签通过后 `payload_sha256` 不符**只可能**来自暂存明文的本地存储损坏。故推荐**执行该检查并类型化拒绝**，其**是否强制**登记为 OPEN（§10 第 4 项）。

### 5.5 密码与密钥清除义务

- 加宽后的 `char[]` 与派生密钥字节在使用后**清零**（`widenBackupPassword` 的结果 `fill('\u0000')`、密钥 `fill(0)`），镜像写侧的 `finally`（`BackupContainerWriter.kt:117-121`）与容器规格 §4.10 `:225`。
- `PBEKeySpec.clearPassword()` 由 `deriveKey` 实现内部完成（`JvmBackupCryptoPrimitives.kt:46-48`）。
- 密码**不**写入任何文件/偏好/日志/容器；**不承诺找回**（容器规格 §4.10 `:225`）。**登记**：Java `String` 不可变，故 UI 传入的密码 `String` 本身无法就地清除（与写侧同一既有约束）。

## 6. 隔离暂存与迁移契约

### 6.1 落点与命名

- 解密明文快照与迁移副本**只**落在**应用私有暂存目录**（`layout.backupStagingDirectory`，`LedgerStableStorage.kt:207`）下的私有子文件——Android 应用私有目录 / 桌面平台用户数据目录下的私有子目录（容器规格 §4.9 `:218`）。宿主位置由平台 API 运行期解析（`AndroidLedgerFileSystem.kt:183`、`DesktopLedgerFileSystem.kt:210`），tracked 文件**不写**绝对路径（`docs/CONTRIBUTING.md:171`）。
- 字面命名与布局归实施批（容器规格 §5.2 `:243` 只冻结语义：私有、单账本、可枚举、可回退）。本规格只冻结「必须私有、必须可清理、必须与导出暂存前缀区分」。
- **建议前缀**（归实施批，但须登记）：`restore-container-<token>`（暂存容器副本）、`restore-snapshot-<token>`（解密明文）、`restore-migrated-<token>`（迁移副本）。现有前缀 `snapshot-`/`container-`（`:51`/`:54`）属导出，**不得**混用。

### 6.2 有界磁盘前置检查（容器规格 §4.8 `:206-208`）

- 容器规格 §4.8 的**恢复侧**公式（verbatim `:207`）：
  `container_size + plaintext_size + migrated_copy_size + new_generation_db_size + retained_old_generation_db_size + 64 MiB`。
- **该公式的口径是「恢复峰值」**（§4.8 `:206-207`：峰值时同时存在输入容器、解密暂存、隔离迁移副本、新代 DB、保留的旧代 DB）。其中 `new_generation_db_size` 与 `retained_old_generation_db_size` **由 06.D 的切换阶段**产生，**06.C 的预检不创建它们**（§2.2）。
- **06.C 的前置检查**（预检作用域，即数据流**步骤 3**，§3.3）：可用空间 ≥ `staged_container_size + decrypted_snapshot_size + migrated_copy_size + 64 MiB`。其中：
  - `staged_container_size` = 暂存容器副本的**实际长度**（步骤 1 完成后精确可得，§3.1）。
  - `decrypted_snapshot_size` = 头部 `plaintext_len`（**认证前**由 §4.1 的自洽等式与 ≤ 1 GiB 上限校验，**尚未**经标签认证；`plaintext_len` 在 AAD 内，故标签通过后其值即为可信）；解密后再以**实际**暂存明文长度复核（镜像 06.B 的「快照大小不保证等于源」双门，导出规格 §5 `:257`）。
  - `migrated_copy_size` ≈ `decrypted_snapshot_size`（迁移重写页面，大小**不保证**相等）；故以明文大小为**下界估计**并在迁移后复核。
- **06.D 的完整峰值检查**：`new_generation_db_size` 与 `retained_old_generation_db_size` 的项属 06.D 的切换前置（它知道当前活动代大小）。**06.C 与 06.D 的公式拆分登记为 OPEN**（§10 第 9 项），以免把 06.D 的义务静默塞进 06.C 或反之。
- **空间不足** → 类型化拒绝（**建议名** `P706_INSUFFICIENT_SPACE`，见 §10 第 3 项）；**不**开始解密、**不**创建迁移副本、**不**触碰当前库。
- **可空 `usableSpace` 的取值（设计点）**：`usableSpace` 可返回 null（`LedgerStableStorage.kt:128`），06.B 对其采用 **fail-open**（`BackupExport.kt:242`，其理由是该场景由写侧失败处理兜底且成功只在认证尾部后报告）。
  - **推荐（预检）**：沿用 06.B 的 **fail-open on unknown**，理由与 06.B 同：预检的所有写入只在私有暂存，未知空间最多导致暂存写失败并返回类型化失败，**绝不**产生「假成功」，也**绝不**影响当前库。
  - **替代（登记，倾向用于 06.D 切换前置）**：在**切换**前置对未知空间 **fail-closed**，因为切换峰值更大且「不得中途半切换」（§4.8 `:208`）的后果更严重。
  - **明确**：无论采用哪种，**已知空间不足必须拒绝**（硬门）。
- **禁止无界读入**：**不得**对无界输入调用 `readBytes()`（`LedgerStableStorage.kt:78` 是整文件读入）或任何整文件读入 API（容器规格 §4.8 `:202`、计划 `:144`）。预检只用 64 KiB 分块（`openRead`/`openWrite`，`:136`/`:148`）。
- **必须在后台线程**：KDF、流式解密、迁移、校验**全部**在后台线程（容器规格 §4.8 `:210`）。

### 6.3 严格迁移路径（不复用桌面宽松补戳）

- **前置**：步骤 7 已用**权威** `PRAGMA user_version` 判定 payload 版本（§3.7）。仅当该版本**在受支持旧 schema 白名单内**（且头部提示一致）才迁移；否则已由 `P706_SCHEMA_VERSION_UNSUPPORTED` 拒绝。
- **严格迁移（设计级）**：在**隔离副本**上、**单事务**内执行 `LedgerDatabase.Schema.migrate(driver, from, currentVersion)`，随后把 `user_version` 戳为 `currentVersion`（v31）。形状镜像桌面的**严格**分支（`Main.kt:966-972`）。
- **明令禁止**（容器规格 §5.4 `:260`）：**不得**复用 `Main.kt:936-939`（有 `catalog_version` 即补戳）、`:940-952`（v27 哨兵猜测）、`:957-964`（未知已填充库直接补当前版本戳）等**宽松补戳**分支；**不得**用 `hasTable`/`hasV27StructuralSentinel`（`Main.kt:1000-1030`）之类的结构**猜测**把外来库「认成」某个版本。外来备份若不在白名单内，一律类型化拒绝，**绝不**就地补戳。
- **迁移失败** → 单事务回滚 → 删除隔离副本 → 类型化拒绝；**原库完全不变**（计划 `:175`、A04 `:184`）。
- **受支持旧 schema 白名单（OPEN，本规格不关闭）**：白名单**集合**与「严格结构识别」属 06.C，但容器规格 §6 `:292` 与 D-174 第 4 条把它登记为 **OPEN**，且「历史无戳库」的支持须「另列严格结构识别与迁移门」（容器规格 §5.4 `:260`、计划 `:164`）。故本规格**只冻结规则**（「仅白名单内版本迁移；其余类型化拒绝；绝不复用宽松补戳」），**不冻结集合**；集合的确定须由另立的严格结构识别门完成（§10 第 1 项）。

### 6.4 完整性/FK/领域校验（不运行 seed bootstrap）

- 在**迁移后**的隔离副本上执行：`PRAGMA integrity_check`（判定形状复用 `snapshotIntegrityOk`，`BackupSnapshotDriver.kt:58`：至少一行且每行恰为 `ok`）与 FK 校验（`PRAGMA foreign_key_check`，须**新增**产品面；§1.5 零命中）及领域关系校验。
- **禁止 seed bootstrap**：**不得**调用 `store.bootstrap(ledgerId, defaultCatalogSeed())`（`App.kt:728`、`Main.kt:851`），也不得经 `buildLedgerGraph`（`App.kt:713`、`Main.kt:449`）的装配路径写入种子；缺表/缺事实一律类型化拒绝（计划 `:151`）。
- **校验集（未验证项）**：FK/领域校验的**具体校验集**（哪些表、哪些关系、是否需要跨 owner 不变量）尚无既有独立表面（§1.5），归实施批并登记（§10 第 6 项）。

### 6.5 清理生命周期（含 sweep 前缀影响）

- **清理时机**（计划 `:166`；容器规格 §4.9 `:219`）：未确认的预检暂存在**取消**、**会话结束**、**下次启动**时清理。
- **sweep 前缀影响（实施义务，登记）**：现有 `sweepBackupStaging`（`LedgerStableStorage.kt:467-478`）**只**删 `snapshot-`/`container-` 前缀（`:473`）。06.C 的新前缀（§6.1 建议的 `restore-*`）**必须**被纳入启动清理，否则被杀进程遗留的暂存明文不会被清理。本批**不**改代码，只登记该实施义务（§10 第 11 项）。
- **与持 lease 的交互（必须一致，见 §7.3）**：该 sweep 是**破坏性**的（删除匹配前缀的全部文件），且它由 `openStableStorageLedger`（`LedgerRuntimeOwner.kt:761`，清理点 `:777`）在**每次** open/`reopen` 时运行。本文§7.3**选定**预检全程持 operation lease，使 `reopen`/`closeActiveGraph` 在预检在飞时返回 `QuiesceBlocked`（`:417`/`:396`），**从结构上阻止**预检期间运行该 sweep，从而**不**会删除预检自己的已认证暂存工件。**任何**「缩短持 lease 窗口」的替代（§7.3、§10 第 14 项）**必须同时**给出 sweep 的在飞守卫（跳过活跃预检拥有的暂存），否则会留下悬空 token；本规格**不**采用该替代。
- **不声称安全擦除**：**禁止**声称已删除文件的物理扇区被安全擦除（容器规格 §4.9 `:219`；计划 `:166`）。
- **OS 自动备份/设备迁移（登记，未关闭）**：需有明确排除规则覆盖明文暂存、回退与 journal（计划 `:166`）；Android `AndroidManifest.xml` 的 `<application>` 未声明 `android:allowBackup`/`android:dataExtractionRules`，系统默认实际为备份启用。该决策**归隐私规格**（D-174 第 4 条；容器规格 §1.2 `:37`、§6 `:291`）。**本规格不声称**云备份/设备迁移已关闭。
- **运行期状态不入容器**：世代目录、journal、活动指针是运行期恢复状态，**不属于**备份 payload（容器规格 §4.9 `:220`）。

## 7. Token 与预览契约

### 7.1 不透明 token 的绑定

计划 `:150`（verbatim）：「预检生成不透明 token，绑定已认证工件、目标账本及当前运行代际，确认不能重新读取一个可被替换的外部文件」。据此，token **必须**绑定：

1. **已认证工件**：解密后经标签认证、并与头部 `payload_sha256` 校验一致的**暂存明文快照**（及其迁移副本）的摘要。token 只引用**私有暂存**内的工件，**绝不**引用用户选择的**外部**容器路径。
2. **目标账本**：固定账本身份（`LedgerId("ledger-local-test")`，`App.kt:722`；首版只接受该身份且库中无额外账本，容器规格 §5.4 `:261`）。
3. **当前运行代际**：预检开始时捕获的 `owner.activeGeneration`（`LedgerRuntimeOwner.kt:230`）。

- **不透明性**：token 对共享 UI 为不透明句柄（计划 `:150`），其内部结构归实施批；不得把文件路径或密码编码进 token。

### 7.2 确认不得重新读取可被替换的外部文件

- 因为 token 绑定的是**私有暂存**内的已认证工件（§7.1 第 1 点），确认阶段**不得**重新打开用户选择的**外部**容器（该文件在预检后可能被替换/删除，重新读取会破坏「已认证」的语义，计划 `:150` 明令）。
- 因此 §3.1 的**单遍复制到私有暂存**不仅是取可信大小的手段，也是 token 绑定的前提。
- **会话边界**：暂存在取消/会话结束/下次启动被清理（§6.5），故**跨会话的 token 必须视为 stale 并拒绝**；本规格登记该策略（§10 第 12 项）。

### 7.3 与 06.1 owner/lease 的 generation 交互

- **持 operation lease 覆盖整段预检（本规格选定）**：预检在开始时经 `owner.acquireLease()`（`LedgerRuntimeOwner.kt:321`，非阻塞，非 Ready 返回 `RuntimeNotReady`）取得一个 operation lease，**全程持有**，在结束（成功/失败/取消）后于 `finally` 释放（形状同 06.B 导出的 `BackupExport.kt:200-204`）。generation 取该 lease 捕获的值（`LedgerLease.generation`，`:181`）并绑定进 token（§7.1 第 3 点）。
  - **决定性理由：阻止 sweep 的破坏性副作用（不是「读取活动连接」）**。预检的暂存工件与 06.B 的导出暂存共用同一目录 `layout.backupStagingDirectory`（`LedgerStableStorage.kt:207`）。`reopen`（`LedgerRuntimeOwner.kt:413` → `:424` 的 `openGeneration`）会经组合根的 open 闭包再次调用 `openStableStorageLedger`（`:761`），后者每次启动都调用 `sweepBackupStaging`（`:777`）；而 `sweepBackupStaging`（`LedgerStableStorage.kt:467-478`）会删除该目录下**所有**带 sweep 前缀的文件。本文**同时**要求把预检的 `restore-*` 前缀纳入该 sweep（§6.5、§10 第 11 项）——一旦该扩展落地，**一次 lease-free 的 `reopen` 就会静默删掉预检自己的已认证暂存工件**（token 所绑定的工件），留下**悬空 token**。持 lease 时 `reopen`/`closeActiveGraph` 在飞 lease > 0 即返回 `QuiesceBlocked`（`:417`/`:396`），**从结构上阻止**该 sweep 运行，故不产生悬空 token。
  - **与 06.B 的差异说明（有据的偏离）**：06.B 持 lease 的理由是它经活动连接执行 `VACUUM INTO`（`BackupExport.kt` 的 `snapshotPort`）；预检**不**读活动连接，故该理由**不**适用。本规格**不**沿用该理由，而是以「暂存目录与 sweep 冲突」为持 lease 的依据——这是**不同于** 06.B 的理由，但结论一致（持 lease）。
  - **代价（登记）**：预检可能很长（KDF 至 10,000,000 次迭代 + 解密至 1 GiB + 迁移），持 lease 期间 `closeActiveGraph`/`reopen` 返回 `QuiesceBlocked`（`:396`/`:417`），即切换被推迟到预检结束。本规格**接受**该代价：预检是用户主动发起、以「即将替换整个账本」为前提的操作，其期间**本就不应**发生换代；把「可切换性」让位于「token 不自悬空、暂存不被误删」是正确的取舍。
  - **06.D 不得假设「立即拒绝」**：`closeActiveGraph`/`reopen` 是**非阻塞**的 `tryLock` 路径（`:393`/`:414`），在预检持 lease 时**立即**返回 `QuiesceBlocked`；但 06.D 的 drain 原语 `quiesce()`（`:351`）不同——它会**挂起**至在飞 lease 释放，或到有界超时（`LEDGER_QUIESCE_TIMEOUT_MILLIS`，`:195`，5 s）后才返回 `QuiesceBlocked`。故 06.D 的切换流程在预检持 lease 时须预期「先等待至超时、再得到 `QuiesceBlocked`」，**不得**把 `quiesce()` 当作瞬时操作（其调用契约还要求发起 quiesce 的上下文本身不持 lease，`:348-349` 的调用方契约与 P3-3 自死锁说明）。
  - **若未来确需更短的持 lease 窗口**：可改为「仅捕获 generation + 在 `sweepBackupStaging` 内加**在飞预检守卫**（跳过有活跃预检拥有的暂存）+ 确认时重校验工件」（§7.5）。该替代**需要新增代码**（sweep 守卫），归实施批；本规格登记为替代方案（§10 第 14 项），**不**采用。
- **确认时的 stale 校验**：确认（06.D）**必须**先校验 token 绑定的 generation 仍是当前活动代——复用 `LedgerLeaseScope.isCurrentGeneration`（`:641`，其判定为 `shouldDiscardLandingResult`，`:465`）；不等则**stale 拒绝**，**零切换**（计划 A05 的语义，本文不实现 06.D）。
- **旧回调不得污染新图**：预检结果/进度回落到 UI 时须校验捕获的 generation 仍为当前活动代（`shouldDiscardLandingResult` `:465`），形状同 06.B 的 `BackupExportLaunch(request, generation)`（`:728`）。
- **本规格不改 D-176 契约**：不新增/修改 owner 方法；token 的 generation 绑定只**消费**既有 `activeGeneration` 与 `isCurrentGeneration`。

### 7.4 预览摘要内容

- 摘要**至少**包含（具体字段归实施批，§10 第 13 项）：
  - 容器格式版本（`container_format_version`）与容器大小；
  - payload 的**权威** `user_version`（迁移前）与**迁移后**版本；
  - 来源账本身份与**目标账本**身份（固定身份，`App.kt:722`）；
  - 已认证工件摘要（`payload_sha256`，或其显示形式）；
  - 校验结果（`integrity_check`/FK/领域校验的通过与否）；
  - 关键 owner 的**计数摘要**（用于「明确替换确认」，计划 `:154`）。
- 摘要**不得**包含：密码、派生密钥、明文账务内容明细、外部文件路径或本机绝对路径（容器规格 §4.9 `:218`、§4.10 `:225`；计划 `:136`/`:152`；`docs/CONTRIBUTING.md:171`）。
- 共享 UI 负责呈现摘要与明确替换确认（计划 `:154`），**不**接触文件路径、driver 或密码日志（计划 `:152`）。

### 7.5 确认时对暂存工件的存在性/摘要重校验（硬要求）

- 计划 `:150` 禁止确认阶段重新读取**外部**文件（§7.2）；但**同样必须**确认阶段**不信任** token 而**重新校验**它绑定的**暂存**工件仍然存在且摘要一致。故确认（06.D）**必须**在切换前执行：
  1. token 绑定的暂存工件（明文快照及迁移副本）**存在**（`LedgerFileSystem.exists`，`LedgerStableStorage.kt:72`）；
  2. 其**摘要与 token 绑定的摘要一致**（流式 SHA-256，同 §5.4 的原语）；
  3. token 绑定的 generation 仍是当前活动代（`isCurrentGeneration`，§7.3）；
  4. 目标账本身份与 token 绑定一致（§7.1 第 2 点）。
- **任一项不符 → stale 拒绝，零切换**：这与计划 A05「零切换或 stale 拒绝，不能恢复未预览工件」（计划 `:185`）一致。**本规格不实现确认路径**（属 06.D），只冻结该**义务**。
- **与 §7.3 的关系**：§7.3 选定的「持 lease 覆盖整段预检」使预检期间暂存不会被 sweep 删除；但预检**结束、lease 释放之后**到用户确认之间，暂存仍可能被「会话结束/下次启动」清理（§6.5）或被杀进程破坏。故本重校验**不可省略**：它是「持 lease」之外的**第二道**防线（持 lease 只覆盖预检期间，不覆盖预览到确认之间的窗口）。

## 8. 平台端口

### 8.1 有界流式**来源**端口（新增）

- **commonMain 形状**（沿用 `ImportFilePickPort` 先例：commonMain 只声明闭包/值类型，平台侧实现，`ImportFilePick.kt:112`/`:123`）：

```text
fun interface BackupSourcePort {
    // 返回 null = 用户取消；否则一个可关闭的有界分块读流（可附平台报告的 size，可空）
    fun openSource(): BackupSourceReader?
}

interface BackupSourceReader : AutoCloseable {
    // InputStream 约定：返回读入字节数，非正数表示流结束；不得整块读入
    fun read(buffer: ByteArray): Int
    // 平台报告的大小元数据，或 null（同 ImportFilePick 的可空 sizeBytes 先例）
    val reportedSize: Long?
}
```

- **Android**：SAF `ActivityResultContracts.OpenDocument`（`App.kt:179` 先例）+ `ContentResolver.openInputStream`（`App.kt:208` 先例）；launcher 必须主线程调用，故沿用 06.B 的「主线程 poster + 后台线程等待」形状（`AndroidBackupTargetPort.kt:32-65`）。MIME 过滤可用 `application/octet-stream`。
- **桌面**：Swing `JFileChooser` 打开（`ImportFilePick.kt:103-109` 先例），`FileInputStream` 有界分块读。
- **`reportedSize` 的两种用途（与 §3.1 一致）**：(1) 若报告值 > 2 GiB，则**在读任何字节之前**拒绝（精确的「不读取」，对应容器规格 §4.8 `:197`）；(2) 若报告值可信，可直接作为容器大小的候选，但仍以**暂存副本的实际长度**为权威（复制后复核）。**provider 不报大小**时，`reportedSize` 为 null，以计数流为登记回退（§3.1、§10 第 5 项）。
- **具体类型/包名与超时值归实施批**（与 06.B 的端口命名归实施批一致）。

### 8.2 「按绝对路径打开读写、不 create-on-open、不自动迁移」表面（新增）

隔离迁移需要在**任意绝对路径**上打开**读写**驱动并跑严格迁移与校验，且**不**触发 create-on-open 或自动迁移。两端现状（§1.4）均缺：

- **桌面**：`JdbcSqliteDriver("jdbc:sqlite:$path")`（`DesktopBackupSnapshotPort.kt:33`）开即创建但**不**自动迁移（迁移是显式调用，`Main.kt:873`）。隔离副本是我们刚写出的文件（已存在），故 create-on-open 不构成问题；组合根须在打开前做与 06.1 同款的存在性/有效头守卫（`isUsableSqliteMainFile`，`LedgerStableStorage.kt:296`；组合根先例 `Main.kt:912`），然后显式调用**严格**迁移 helper（§6.3），**绝不**调用 `migrateToCurrentSchema`（`Main.kt:928`）。
- **Android**：现有按路径打开只有 `verifyAndroidSnapshotFile` 的 **`OPEN_READONLY`**（`AndroidLedgerDatabaseHandle.kt:162`），**不能**迁移。须新增一个**读写**、**无 create/version 回调**的打开面（`SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)`，**不带** `CREATE_IF_NECESSARY`）与一个受控入口（形状同 `runSnapshotInto`/`verifyAndroidSnapshotFile`，`AndroidLedgerDatabaseHandle.kt:130`/`:161`），委托到 commonMain driver 级 helper（`BackupSnapshotDriver.kt` 形状）。
  - **不得**使用 `AndroidSqliteDriver(schema, context, name)`（`:15-20`）：它会跑 create/version 逻辑，且零版本 schema 在构造期即抛（`:140-156` 的 P0 记录）。
- **读写驱动的落地形状（既有支持路径优先，见 §1.4 基线更正）**：Android 的 `SQLiteDatabase`（framework 句柄）本身**不是** SQLDelight `SqlDriver`，而严格迁移需要 `LedgerDatabase.Schema.migrate(driver, from, to)`。本仓 SQLDelight 版本为 **2.3.2**（`build.gradle.kts:4`、`android-app/build.gradle.kts:49`、`ledger-data/build.gradle.kts:56`）。**候选路径（按优先序，实施批择一并实证）**：
  1. **既有支持的非创建式构造器（首选，无新驱动代码）**：`SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)`（**不带** `CREATE_IF_NECESSARY`，故不 create；无 schema/version 回调，故不自动迁移）→ `FrameworkSQLiteDatabase(frameworkHandle)`（`androidx.sqlite:sqlite-framework-android:2.6.2`）→ `AndroidSqliteDriver(supportDatabase)`（`android-driver:2.3.2` 的**公开**单参构造器，`openHelper = null`，见 §1.4 的字节码实读）。这条路径**不需要**任何新驱动代码。
     - **实施前须确认的一点（本规格未从构建文件确认，登记为实施批首项）**：`FrameworkSQLiteDatabase` 位于 `androidx.sqlite:sqlite-framework-android:2.6.2`，而 `ledger-data` 的 `androidMain` 只**直接**声明了 `app.cash.sqldelight:android-driver:2.3.2`（`ledger-data/build.gradle.kts:56`）。本基点从缓存的 Gradle 模块元数据实读：`android-driver:2.3.2` 的 **api（编译）发布**只列出 `androidx.sqlite:sqlite:2.6.2`，而 `androidx.sqlite:sqlite-framework:2.6.2` 出现在其 **runtime 发布**中。故 `FrameworkSQLiteDatabase` **可能**只在运行期类路径上，编译期**可能需要**显式新增该依赖（**依赖变更须另行批准**，本文只登记该待确认点）。实施批必须先核实该类型是否在 `ledger-data` androidMain 的编译类路径上。
  2. **自定义 `SqlDriver` 适配器（回退，新代码）**：若候选 1 被证明不足（例如 `FrameworkSQLiteDatabase` 不在编译类路径且不允许新增依赖），则在 `SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)` 之上实现一个最小 `SqlDriver` 并把 `execute`/`executeQuery` 转发到 framework 句柄。
  - **明确结论（更正 draft-2 的过度断言）**：**06.C 的 Android 交付物不一定依赖新增驱动代码**——既有支持的候选 1 即为非创建式、可迁移路径；实施批应优先使用它，仅当它不足时才回退到候选 2。本规格**不**再声称「Android 无可跑 `Schema.migrate` 的非创建式 `SqlDriver`」（该前提已被证伪，见 §1.4）。
- **桌面**同样需要「读写打开隔离副本 + 跑严格迁移」的受控入口（`JdbcSqliteDriver` 可用，`Main.kt:871`；但**不得**经 `migrateToCurrentSchema`，§6.3）。
- **本批不实现**：本规格只冻结「必须有这样一个表面、且其语义为不 create/不自动迁移」，不改任何代码。

### 8.3 其余端口复用

- 私有暂存的读写复用 `LedgerFileSystem.openRead`/`openWrite`/`usableSpace`/`listDirectory`（`LedgerStableStorage.kt:136`/`:148`/`:128`/`:156`）。
- 明文暂存的 `user_version`/`integrity_check` 读取复用/扩展 `BackupSnapshotDriver.kt` 的 helper 形状。
- crypto 复用 `JvmBackupCryptoPrimitives`（`:24`）+ §5.1 的新增解密器；两端共用同一 JVM 实现（跨端字节兼容已由容器规格 §6 `:282`/`:285` 实证）。

## 9. 验收映射（P706-A03 / A04）

下表把计划 §6.3（`:183-184`）与容器规格 §7（`:307-308`）归属 06.C 的向量逐条映射。**本规格不把任何向量标为 PASS**——全部为未来验收要求，尚未执行（计划 `:194`）。

| 验收 ID | 计划要求（verbatim，计划 `:183-184`） | 容器规格归属（§7） | 06.C 交付 | 非 06.C 部分 |
| --- | --- | --- | --- | --- |
| P706-A03 | 「错密码、认证失败、截断、伪格式、超限、未知未来/无版本库、异账本」→「类型化拒绝，当前库/活动指针均不变」 | 容器规格 §7 `:307` 归 **06.C**；贡献为「冻结统一失败码（§4.7）、资源上限（§4.8）、拒绝规则（§5.4）」 | **06.C 核心**：错密码/认证失败 → `P706_CONTAINER_AUTHENTICATION_FAILED`（§5.3）；截断（头部不足 59 字节或自洽等式不成立）→ 认证前类型化拒绝（§4.1）；伪格式 → `P706_CONTAINER_FORMAT_UNSUPPORTED`；超限 → `P706_CONTAINER_TOO_LARGE` 与明文上限（§4.1）；未知未来/无版本库 → `P706_SCHEMA_VERSION_UNSUPPORTED`（类 2）与 `P706_CONTAINER_VERSION_UNSUPPORTED`；异账本 → 类 3 身份拒绝（**建议名**，§10 第 3 项）。「当前库/活动指针不变」由「预检只写私有暂存、从不写活动指针」结构性满足（§3、§7.2） | **06.D**：真正的切换/回退路径中「活动指针不变」的端到端证明；本规格不含指针写入 |
| P706-A04 | 「支持集合内有账旧 schema 与迁移失败」→「只改隔离副本；原库不变，成功后所有 owner 仍完整」 | 容器规格 §7 `:308`：**格式层面已就绪**（头部非权威提示 + payload `user_version` 权威检查），**迁移门 OPEN**（§6 `:292`） | **06.C 交付**：「只改隔离副本」（§6.1/§6.3，原库完全不变）、严格迁移路径（§6.3）、迁移失败类型化拒绝且回滚（§6.3）、不运行 seed bootstrap 的隔离校验（§6.4） | **受支持集合本身 OPEN**（§6.3、§10 第 1 项）：白名单集合与严格结构识别须另立门；「成功后所有 owner 仍完整」的**往返等价**属 06.4（P706-A01，容器规格 §7 `:305`）；**本规格不标 A04 PASS** |

**06.C 可支撑的**：A03 的全部容器级类型化拒绝（含统一失败与三类检查时机）与「零当前库写入」；A04 的隔离副本迁移与失败不改原库。**属后续切片的**：A03 的端到端切换不变性（06.D）、A04 的白名单集合与往返等价（06.4）。

## 10. 明确登记为未关闭/未验证的项

本规格**不**关闭以下任何一项；它们或属后续切片、或归实施批、或需另立门/决定。与兄弟规格同一纪律：不得静默丢弃，也不得当作已达成。

1. **受支持旧 schema 白名单集合与严格结构识别**：容器规格 §6 `:292` 登记 **OPEN**，D-174 第 4 条归 06.C。本规格只冻结**规则**（仅白名单内迁移、其余拒绝、绝不复用宽松补戳），**不冻结集合**；集合须由另立的严格结构识别/迁移门确定（§6.3）。
2. **§4.6 AAD 构造（`header(0..58) || salt`）的跨端向量**：容器规格 §6 `:286` 登记 **OPEN**；既有跨端向量用的是**测试替身 AAD**。06.C **不声称**该构造已跨端验证（§5.1 只复用同一 `backupContainerAad`，构造本身仍待向量）。
3. **本规格提出的新拒绝码名（需批准，非冻结）**：§4.8 的「明文 `plaintext_len` 上限」与「自洽等式不成立」在容器规格中**未命名**；截断（< 59 字节）在容器规格中**未命名**；身份/账本拒绝（类 3）**未命名**；来源读失败/迁移失败/领域校验失败/空间不足亦为本文新提出。**建议名**：`P706_PLAINTEXT_TOO_LARGE`、`P706_CONTAINER_SIZE_MISMATCH`、`P706_CONTAINER_TRUNCATED`、`P706_LEDGER_IDENTITY_UNSUPPORTED`、`P706_SOURCE_READ_FAILED`、`P706_MIGRATION_FAILED`、`P706_DOMAIN_VALIDATION_FAILED`、`P706_INSUFFICIENT_SPACE`。**这些是待批准的建议名**；只有容器规格中已写出的码是**冻结**的，出处逐条如下：`P706_CONTAINER_FORMAT_UNSUPPORTED`/`P706_CONTAINER_VERSION_UNSUPPORTED`/`P706_KDF_UNSUPPORTED`/`P706_AEAD_UNSUPPORTED`/`P706_KDF_PARAMETERS_UNSUPPORTED`/`P706_AEAD_PARAMETERS_UNSUPPORTED` 见 §4.3.1（`:123-130`）；`P706_CONTAINER_TOO_LARGE` 见 §4.8（`:197`）；`P706_CONTAINER_AUTHENTICATION_FAILED` 与 `P706_PAYLOAD_INTEGRITY_FAILED` 见 §4.7（`:182`、`:183`）；`P706_SCHEMA_VERSION_UNSUPPORTED` 见 §4.3.2（`:136`）与 §5.4（`:259`）。
4. **`P706_PAYLOAD_INTEGRITY_FAILED` 是否强制**：容器规格 §4.7 `:183` 只说「**可**映射到」该码。本规格推荐执行该检查并类型化拒绝（§5.4），但**是否强制**登记为 OPEN。
5. **2 GiB 上限 vs provider 不报大小**：当 provider 报大小且 > 上限时**精确「不读取」**即拒绝（§3.1，对应容器规格 §4.8 `:197`）；**provider 不报大小**时以「计数流」为**登记的替代**（会读到上限为止）。该替代**未经实现/实测**，归实施批验证；provider 元数据的可靠性亦未验证。
6. **领域完整性/FK/关系校验面**：产品源码**无**独立 FK/领域校验表面（`foreign_key_check` 零命中，§1.5）。校验**集合**与承载面归实施批（§6.4）。
7. **读侧明文上限、自洽等式与截断的拒绝码名**：见第 3 项（截断的独立码 `P706_CONTAINER_TRUNCATED` 为建议名）。
8. **Android 上「读写、不 create-on-open、不自动迁移、能跑 `Schema.migrate`」的 driver 面**：现状只有 `OPEN_READONLY` 的 `verifyAndroidSnapshotFile`（`AndroidLedgerDatabaseHandle.kt:161`）。**既有支持的首选路径已确认存在**：`SQLiteDatabase.openDatabase(..., OPEN_READWRITE)` → `FrameworkSQLiteDatabase` → `AndroidSqliteDriver(SupportSQLiteDatabase)`（`openHelper = null`，无 create/version 逻辑；§1.4、§8.2），故**不一定**需要新驱动代码。**实施批须先确认的唯一一点**：`FrameworkSQLiteDatabase`（`androidx.sqlite:sqlite-framework-android:2.6.2`）是否在 `ledger-data` androidMain 的**编译**类路径上——本规格从缓存模块元数据读到它出现在 `android-driver:2.3.2` 的 **runtime** 发布而非 api 发布，故编译期可能需要显式新增依赖（依赖变更须另行批准）。若首选路径不可用，回退到自定义 `SqlDriver` 适配器。
9. **06.C 与 06.D 的磁盘公式拆分**：容器规格 §4.8 的恢复侧公式是**峰值**口径，含 06.D 的新代/保留旧代两项；06.C 只执行其预检子集（§6.2）。拆分须在 06.D 规格中确认，避免义务重叠或遗漏。
10. **有界流式来源端口的具体类型/包/超时**：归实施批（§8.1）。
11. **sweep 前缀扩展**：`sweepBackupStaging`（`LedgerStableStorage.kt:467-478`）当前只删 `snapshot-`/`container-`；06.C 的新暂存前缀须纳入启动清理（§6.5）。本批不改代码。**该扩展与 §7.3 的「持 lease 覆盖整段预检」配套**：持 lease 阻止预检期间的 sweep 运行，故扩展不会删掉活跃预检的工件（§7.3、§6.5）。
12. **token 的会话边界与 stale 策略**：暂存在取消/会话结束/下次启动被清理（§6.5），故跨会话 token 必须 stale 拒绝（§7.2）；确认时的存在性/摘要重校验（§7.5）为硬要求，确切策略归实施批。
13. **预览摘要的确切字段集**：§7.4 只给最小集合，字段归实施批。
14. **持 lease 的替代方案（登记，不采用）**：§7.3 **选定**「持 operation lease 覆盖整段预检」。替代为「仅捕获 generation + `sweepBackupStaging` 的在飞预检守卫 + 确认时重校验工件」（§7.3、§7.5）；该替代**需新增 sweep 守卫代码**，本规格不采用。若实施批改采该替代，**必须**同时给出守卫与重校验，否则会留下悬空 token。
15. **持 lease 期间切换被推迟的时长**：§7.3 已接受「切换推迟到预检结束」；具体时长（KDF + 解密 + 迁移的实测值）未取，归实施批与规模评估。
16. **本规格自身**：状态 `proposal`，**尚未**经独立评审与 distinct verifier，也**无**批准决定条目。本修订（draft-2）应用了独立规格评审的 4 项 P2 与 6 项 P3，仍为 `proposal`。

## 11. 非目标与边界（no-touch）

- **`.external/` 只读**：本批未触碰、未编辑、未清理、未改名、未删除 `.external/` 任何内容。
- **零产品代码、零测试**：本批只新增本设计文档；不改任何 `.kt`、测试、构建脚本或清单。
- **零 schema/迁移/依赖变更**：schema 停留 **v31**，迁移链 `1.sqm`～`30.sqm` 零改动；不新增依赖（仅用平台自带 `AES/GCM/NoPadding`、`PBKDF2WithHmacSHA256`、`SecureRandom`、`MessageDigest("SHA-256")`）。
- **不改容器格式**：不改容器格式规格（D-174）的任何冻结字节布局、KDF/AEAD 参数、AAD 构造、拒绝码、资源上限；§4 为唯一权威。特别地，头部提示与 payload `user_version` 不一致时的 `P706_SCHEMA_VERSION_UNSUPPORTED` 结构拒绝（§3.7）是**冻结规则**，本文**不**放宽为「以 payload 为准后继续接受或迁移」；若未来确需放宽，必须重开 D-174 并取得显式批准。
- **不改 owner 契约**：不改 06.1 规格（D-176）的 `LedgerRuntimeOwner`/lease/generation/quiesce 契约；只消费其 `activeGeneration` 与 `isCurrentGeneration`。
- **不改 D-156/D-158 及既有冻结面**：不改 D-156/D-158/D-174/D-176/D-177 与 P7-01～P7-05 既有冻结面；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动。
- **不实现**：06.C 的产品代码与测试、06.D 的切换/回退、`ConfirmRestore` 的完整实现。
- **不声称**：不声称云备份/设备迁移已关闭（§6.5）；不声称安全擦除物理扇区（§6.5）；不声称 §4.6 AAD 构造已跨端验证（§10 第 2 项）；不把任何 OPEN 项当作已达成；不把任何验收向量并入 PASS（§9）。
- **隐私**：本文不复制任何真实金额、时间、锚点注册值或个人数据；示例全部匿名合成；tracked 文件不含本机绝对路径、临时研究或工具轨迹（`docs/CONTRIBUTING.md:171`）。
