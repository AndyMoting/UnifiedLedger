# P7-06 备份与恢复 06.0 / 06.A 设计规格：备份容器格式、owner 清单与 Q13 技术门

状态：proposal（2026-09-24 起草；本文尚未经独立规格评审，按 `docs/CONTRIBUTING.md:162` 的允许分类标 `proposal`。本文冻结 Q13 容器格式字节定义与 Q14 世代/指针设计级方案，并登记 06.0 匿名技术门结果；实施、Git 写操作与最终验收属后续实施批）。

**Revision:** draft-1（2026-09-24）。工作基线 `main` = `5ea7f67`（P7-05 lost-commit 人工复核面，D-173），schema **v31**，迁移链 `1.sqm`～`30.sqm`（30 个文件，v1→v31）。tracked 行号为该基线实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读。示例与向量全部匿名合成；引用不粘贴大段产品代码，不写本机绝对路径。本文**不**新增决定条目、**不**修改任何既有决定。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 行号为当前 worktree 基线 `5ea7f67` 实读行号；`.local.md` 与 `local/artifacts/` 以主 checkout 为准、只读）：

- **阶段计划**：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §6（P7-06 备份与恢复：目标 `:131`、06.A～06.D 子项表 `:133-138`）、§6.1（Q13/Q14 推荐与义务 `:140-156`）、§6.2（数据流与失败恢复、技术门 `:158-166`）、§6.3（切片 06.0～06.5 与验收向量 P706-A01..A12 `:168-194`）、§10.6（Q13/Q14 设计门出口 `:447-460`）。上述计划文件为本地规划，其「推荐/建议」均为 proposal，不构成产品行为、迁移、技术选型或发布授权（计划 `:70`、`:460`）。
- **格式先例**：D-156（`docs/DECISIONS.md:2947` 起：设计门规格与决定条目的引用形状、证据纪律、边界段结构）、D-158（`docs/DECISIONS.md:2987` 起：实施登记与残余条件承接的形状；schema v30→v31）。本条只借其**格式**，不改变其任何裁决。
- **开发规范**：`docs/CONTRIBUTING.md:159-166`（正式文档以中文为主、代码标识符保留英文；不得含本机绝对路径、个人账务数据或临时讨论记录）、`:162`（新建/实质修改的 `docs/specs/` 设计必须标记 `approved`/`proposal`/`superseded`/`historical`）。
- **技术门证据（本地只读，主 checkout）**：
  - `local/artifacts/p7-06-gate/q13-driver-snapshot-gate.md`（driver 快照门：Android 系统 SQLite 3.44.3 与桌面 xerial sqlite-jdbc 3.51.3.0 两端 `VACUUM INTO` 实证，含 61k 大库资源读数）。
  - `local/artifacts/p7-06-gate/q13-crypto-official-evidence.md`（AES-GCM / PBKDF2 官方参数依据与**跨端字节兼容**实证）。
- **源码现实（逐条复核，file:line）**：见 §1。
- **不可触碰面**：`.external/` 只读；零产品代码、零测试、零 schema/迁移、零依赖；不新增决定条目；`rgXX_` 竖井与 golden fixtures/expected 零改动；不改 D-156/D-158 及既有 P7-01～P7-05 冻结面。

## 1. 现实与差距（逐条复核，file:line）

### 1.1 两端组合根现状：无 quiesce / lease / generation / 活动指针

| 事实 | 位置 | 后果 |
| --- | --- | --- |
| 固定数据库名与固定账本身份 | `android-app/src/main/kotlin/com/unifiedledger/android/App.kt:175`（`createAndroidLedgerDatabase(context, "ledger.db")`）、`:300`（`LedgerId("ledger-local-test")`） | 备份/恢复只能针对该单一身份；首版不改写 ledgerId、不合并、不加 selector |
| 启动控制器只有 Starting/Ready/StartupError 与 Retry 重建 | `App.kt:170`（`AndroidStartupController(...)`）、`:217`（类定义）、`:239-261`（`start()` 关闭旧 graph 后重建） | **没有** quiesce、作业租约、换库代际协议；恢复切换所需的「拒绝新工作并等待既有工作退出」不存在 |
| graph 仅以 close 回调表达生命周期 | `App.kt:281-288`（`CloseableLedgerGraph`） | 只有「关闭」一个动作，没有「暂停准入」「等待在飞作业」「换代」的受控端口 |
| 桌面每次启动新建临时目录 | `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:162-165`（`createDemoDatabaseUrl` → `Files.createTempDirectory("unifiedledger-demo-")`） | 产品路径**不稳定**，每次启动得到不同库；稳定存储是 06.D 的硬前置（§5.1） |
| 桌面宽松「补戳」迁移路径 | `Main.kt:783-832`（`migrateToCurrentSchema`），尤其 `:791-793`（有 `catalog_version` 即补戳）与 `:812-819`（未知已填充库直接补当前版本戳） | 该路径会为某些 `user_version=0` 或不可判定的库**补版本戳**；外来备份**不得**复用它（计划 §6.2 `:164`、E06-03 `:84` 明令） |

### 1.2 持久化 owner 全部在单一 SQLite schema 内，零非 DB 持久状态

- 单一 schema：`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq` 定义 **234 张表 / 9 个视图 / 502 个触发器**（本基线实读计数）；当前 schema 版本 **v31**（迁移链 `1.sqm`～`30.sqm`，共 30 个文件）。
- 有效谓词视图：`Ledger.sq:9779`（`CREATE VIEW transaction_effective_state AS ...`，v31 由 D-158 引入），是「作废/恢复」有效性的单点定义。
- 置顶偏好属 DB owner：`Ledger.sq:263`（`CREATE TABLE entry_pin`）——即计划 E06-05 所述「现有持久偏好含置顶」是**表内**状态，不是外部偏好文件。
- **零非 DB 持久状态**：对产品源码（`android-app/src/main`、`app-ui/src/commonMain`、`desktop-app/src/jvmMain`、`ledger-data/src/commonMain`、`ledger-data/src/androidMain`）检索 `SharedPreferences`/`DataStore`/`getPreferences`/`openFileOutput`/`Files.write`/`writeText` **零命中**。故一个物理快照即可覆盖全部已持久化事实，不存在 sidecar 需要单独打包。**注意**：AndroidManifest 未显式声明系统备份政策（`android-app/src/main/AndroidManifest.xml` 的 `<application>` 无 `android:allowBackup`/`android:dataExtractionRules`），故**不得**声称云备份/设备迁移已关闭（计划 E06-05）。
- `fresh = migrated` 有既有保证：`ledger-data/build.gradle.kts:83`（`verifyMigrations.set(true)`）产出 `:ledger-data:verifyCommonMainLedgerDatabaseMigration` 任务；`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/LedgerDatabaseMigrationTest.kt:541` 以 `schemaMetadata(freshUrl)` 与 `schemaMetadata(migratedUrl)` 相等断言 fresh 与 migrated 逐项一致。

### 1.3 恢复语义所需的生命周期端口在当前代码中不存在

`AndroidStartupController`（`App.kt:217`）与桌面 `DesktopStartupController`（`Main.kt:759` 起的 `openDesktopLedger`）都只有「打开或失败」两态，没有跨「导出/恢复」的租约与代际。因此本规格的 Q13（容器格式）可以在 06.0/06.A 内冻结，而 Q14 的**切换机制**只能给设计级方案，落地属 06.1/06.D（§5、§6.3）。

## 2. 目标与范围

### 2.1 目标

- **06.0**：交付 (a) 完整 owner 清单（§3）、(b) Q13 容器格式的实施规格（§4）、(c) Q14 世代/指针的设计级规格（§5）、(d) 匿名技术门结果与开放项登记（§6）、(e) 验收向量映射（§7）。
- **06.A**：冻结带格式版本 / schema 版本 / 完整性元数据的容器格式，明确一致快照来源、敏感内容、加密与密码处理（计划 `:135`）。

### 2.2 范围内

owner 清单（§3）；Q13 容器字节格式定义、AAD 构造、KDF/AEAD 参数与上下界、密码编码、统一安全失败、资源上限、明文隔离、默认加密（§4）；Q14 世代目录/活动指针/journal 的设计级方案与拒绝规则（§5）；技术门 CLOSED/OPEN 登记（§6）；P706-A01..A12 的切片归属（§7）。

### 2.3 明确范围外（本规格不覆盖，属后续切片）

- **06.B 导出**、**06.C 导入预检**、**06.D 恢复切换**的具体实现（计划 `:173-177`）；本规格只为其提供格式与协议前提。
- **06.1 / 06.D 前置**（两端稳定存储、runtime owner/lease/generation、旧路径升级）的落地。
- 任何 schema 变更、迁移边、依赖变更、产品代码或测试。
- 任何既有决定的修改；Q13/Q14 的最终批准（本文仅为 proposal，须经独立规格评审后由主代理裁决）。

## 3. Owner 清单（06.0）

一个完整备份必须逐 owner 往返。下表按家族分组，覆盖 `Ledger.sq` 的 234 张表中全部**非 `rgXX_` 竖井**表；`rg02_`～`rg12_` 竖井为冻结的回放 owner，其存在与内容随同一物理快照整体往返，不单独做逻辑导出（计划 Q13：物理快照保留所有 owner、稳定 ID 与幂等回执，避免逐表逻辑导出漏掉审计/候选）。

| 家族 | owner（表） | 往返要求 |
| --- | --- | --- |
| 正式账本核心 | `ledger_transaction`、`transaction_version`、`ledger_transaction_current_version`、`posting_set`、`posting`、`formal_transaction_metadata`、`formal_relation`、`formal_relation_member`、`counterparty`、`counterparty_name_history`、`mixed_payment_group`、`mixed_payment_group_leg` | 逐行逐列等价；稳定 ID（transaction_id/version_id/posting_set_id）不变 |
| 目录 | `catalog_account`、`catalog_category`、`catalog_name_history`、`catalog_version`、`catalog_command_request`、`catalog_command_receipt` | 逐行等价；名称历史与命令回执保留 |
| 手工创建 request/receipt | `manual_expense_request`、`manual_income_request`、`manual_lending_request`、`manual_transfer_request`、`confirmed_expense_receipt`、`confirmed_income_receipt`、`confirmed_lending_receipt`、`confirmed_transfer_receipt` | 逐行等价；幂等回执不得丢失或改写 |
| 修正 / 作废 / 回收站（v31，D-158） | `transaction_correction_request`、`transaction_correction_receipt`、`transaction_note_update_request`、`confirmed_transaction_note_update_receipt`、`transaction_void_fact`、`transaction_void_request`、`transaction_void_receipt` | 逐行等价；`transaction_void_fact` 序列完整（有效谓词 `Ledger.sq:9779` 依赖它） |
| 导入与重复审核 | `import_candidate`、`import_source_record`、`import_candidate_status_history`、`import_candidate_payment_profile`、`import_candidate_requires_confirmation`、`import_candidate_decision_snapshot`、`import_duplicate_candidate`、`import_duplicate_review_request`、`import_duplicate_review_receipt`、`import_duplicate_review_snapshot`、`import_duplicate_status_history`、`import_confirmation`、`import_receipt`、`import_request`、`import_evidence` | 逐行等价；候选终态与确认回执保留（往返后重放不得重复入账） |
| 对账 / 证据 / 审计 | `posting_reconciliation`、`posting_reconciliation_history`、`evidence_link`、`evidence_link_history`、`evidence_projection`、`reconciliation_correction_snapshot`、`reconciliation_receipt`、`reconciliation_request`、`reconciliation_request_snapshot` | 逐行等价；对账状态属于真实账户分录，往返后不得改变任何余额 |
| 借贷本金历史 | `lending_position`、`lending_position_history` | 逐行等价；append-only 历史不得丢行 |
| 偏好 | `entry_pin`（`Ledger.sq:263`） | 逐行等价 |
| RG 回放竖井 | `rg02_`～`rg12_` 全部表与视图 | 随物理快照整体往返，零逻辑改动 |
| **尚不存在的 owner** | 预算配置/历史、通用标签、受管理商家、交易注释聚合（计划 §7/§8，Q15～Q18） | **当前不存在**；P706-A11 为**前瞻性**向量，在最终 schema 冻结时纳入（计划 `:177`、`:191`） |

**结论**：owner 清单当前等价于「整库」——因为零非 DB 持久状态（§1.2）且所有 owner 在单一 `Ledger.sq`。物理快照（§4.2）因此是唯一完备的备份机制；预算/标签/商家/注释加入后清单自动扩展，备份机制无需重设计。

## 4. Q13 容器格式（06.A）

### 4.1 设计选择与依据

**选择：简单头部 + 单数据库 payload 的版本化认证加密容器。** 依据计划 Q13（`:142`、`:144`）：优先简单头部 + 单数据库 payload，**避免引入通用归档解包**；容器版本与数据库 schema 版本分开；默认加密；密码不持久化、不承诺找回；SHA-256 只能检测损坏、不能替代认证。**本规格不采用 ZIP 或任何通用归档格式**（见 §8 的路径穿越/重复条目/白名单/解压上限契约条件）。

**算法（零新 crypto 依赖）**：`AES/GCM/NoPadding` 与 `PBKDF2WithHmacSHA256` 均为两端平台自带——JDK 21 `Cipher` 必需支持集含 `AES/GCM/NoPadding (128)`（https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html），Android `SecretKeyFactory` 支持 `PBKDF2withHmacSHA256 26+`（https://developer.android.com/reference/javax/crypto/SecretKeyFactory），Android 官方密码学指南推荐 AES-GCM 256 位（https://developer.android.com/privacy-and-security/cryptography）。**跨端字节兼容已实证**（证据 `local/artifacts/p7-06-gate/q13-crypto-official-evidence.md`：同 (密码字节, salt, iterations, keyLen, IV, AAD, 明文) 在 SunJCE 与 AndroidOpenSSL 上 derivedKey 与 ciphertext+tag 逐字节相同；错密码得 `AEADBadTagException`）。

### 4.2 明文与密文内容

- **明文** = 一致快照文件的完整字节流。快照由 `VACUUM INTO` 生成（两端实证：Android 系统 SQLite 3.44.3 与桌面 xerial 3.51.3.0 均可达、可绑定参数、产物 `integrity_check=ok`、拒绝覆盖已存在目标、WAL 下自洽；证据 `local/artifacts/p7-06-gate/q13-driver-snapshot-gate.md`）。快照为独立自洽文件（快照 `journal_mode=delete`），**不是**活动主文件的裸复制。
- **密文** = 明文经 AES-GCM 加密后的输出，尾部追加 16 字节认证标签。

### 4.3 容器字节布局（逐字节定义）

多字节整数一律**大端（big-endian）**、无符号。偏移以容器文件首字节为 0。

| 偏移 | 长度（字节） | 字段 | 取值 / 含义 |
| --- | --- | --- | --- |
| 0 | 4 | `magic` | ASCII `ULBK`（0x55 0x4C 0x42 0x4B） |
| 4 | 2 | `container_format_version` | `u16`；v1 = `1`。**与 DB schema 版本无关**（计划 `:142`） |
| 6 | 1 | `kdf_id` | `u8`；`1` = PBKDF2WithHmacSHA256 |
| 7 | 1 | `aead_id` | `u8`；`1` = AES-256-GCM |
| 8 | 4 | `kdf_iterations` | `u32`；写入值 = `600000`（§4.4） |
| 12 | 1 | `salt_len` | `u8`；写入值 = `16` |
| 13 | 1 | `iv_len` | `u8`；写入值 = `12` |
| 14 | 1 | `tag_len` | `u8`；写入值 = `16` |
| 15 | 4 | `db_schema_version` | `u32`；payload 快照的 `PRAGMA user_version`（当前 v31） |
| 19 | 8 | `plaintext_len` | `u64`；明文字节数（= 快照文件大小） |
| 27 | 32 | `payload_sha256` | SHA-256(明文)。**仅用于损坏检测，不构成认证**（计划 `:142`） |
| 59 | `salt_len` | `salt` | CSPRNG 随机字节（§4.4） |
| 59+`salt_len` | `iv_len` | `iv` | CSPRNG 随机字节（§4.5） |
| 59+`salt_len`+`iv_len` | `plaintext_len`+`tag_len` | `ciphertext` + `tag` | AES-GCM 输出；标签在密文之后（RFC 5116 §2.3：密文位于构造解密输入所需的其他数据之后） |

**固定头部**（偏移 0..58，共 59 字节）不含任何可变长字段。

### 4.4 KDF：PBKDF2WithHmacSHA256

- **PRF**：HMAC-SHA256（Java 标准算法名 `PBKDF2WithHmacSHA256`，https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html）。
- **Salt**：由平台 CSPRNG 生成，**v1 恰为 16 字节（128 bit）**。依据 NIST SP 800-132 §5.1（随机 salt ≥ 128 bit，approved RBG）https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-132.pdf；RFC 5116 §3.1 要求 nonce 每次调用不同。读取端接受的 `salt_len` 范围为 **16..32**，超出即类型化拒绝。
- **迭代次数**：写入端固定 `600000`。依据 OWASP Password Storage Cheat Sheet（PBKDF2-HMAC-SHA256 ≥ 600,000 iterations）https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html。**硬下界 = 600000，硬上界 = 10000000**：`kdf_iterations` 落在 `[600000, 10000000]` 之外即类型化拒绝（`P706_KDF_PARAMETERS_UNSUPPORTED`）。下界依据 OWASP 推荐值；上界依据 NIST SP 800-132 §5.2（关键密钥可至 10,000,000）；NIST 同节最低值 1,000 仅为标准底线，**本产品不采用该底线**。RFC 8018 §4.1/§4.2 同引 NIST 值（https://www.rfc-editor.org/rfc/rfc8018.txt）。
- **派生密钥长度**：**256 bit（32 字节）**，`SecretKeySpec(bytes, "AES")`。`kLen ≥ 112 bit`（NIST SP 800-132 §5.2）已满足。
- **密码编码（必须显式）**：密码字符串先按**固定字符集 UTF-8** 编码为字节，再构造 `PBEKeySpec`。依据 Android `SecretKeyFactory` 文档对 PBE 族的原文警示——「use only the low order 8 bits of each password character」（https://developer.android.com/reference/javax/crypto/SecretKeyFactory）——若不显式编码，两端字符集/字宽分歧会破坏跨端可解密性。**v1 不做任何 Unicode 规范化**（不做 NFC/NFKC），以保证「同一字节序列」在两端的可复现性；密码中的 U+0000 与未配对代理项由实现类型化拒绝（编码为合法 UTF-8 的前提）。
- **密钥材料编码**：`SecretKeyFactory.generateSecret(...).getEncoded()` 与 `SecretKeySpec` 均为原始字节；跨端一致性以 §6 的字节兼容实证为准，**不依赖 provider 身份**（JDK 21 为 SunJCE、Android 为 AndroidOpenSSL，证据同上）。

### 4.5 AEAD：AES-256-GCM

- **IV/Nonce**：**96 bit（12 字节）**，由平台 CSPRNG 生成。依据 NIST SP 800-38D §5.2.1.1（建议限制为 96 bit 以利互操作与效率）https://csrc.nist.gov/pubs/sp/800/38/d/final。读取端 `iv_len` 必须等于 12，否则类型化拒绝。
- **认证标签**：**128 bit（16 字节）**。依据 SP 800-38D §5.2.1.2（t ∈ {128,120,112,104,96}，每密钥固定一个 t）。读取端 `tag_len` 必须等于 16。
- **单密钥单次调用（no nonce reuse）**：每次导出使用**全新 CSPRNG salt** 派生**全新密钥**，且该密钥仅执行**一次** GCM 调用；因此同一密钥下重复 IV 的概率受 SP 800-38D §8 约束（≤ 2^-32），总调用数远低于 §8.3 的 2^32 上限。**禁止**在同一密钥上重复加密不同 payload，也禁止复用 salt+IV 组合。
- **AAD 必须在密文处理前全部供给**：`updateAAD` 必须先于 `update`/`doFinal`（Android `Cipher` 文档明述）。

### 4.6 被认证元数据（AAD）的无歧义构造

依据 RFC 5116 §3.3：AD 若由多元素构成，必须可无歧义解析，变长串须含长度。本格式的 AAD 构造为：

```
AAD = 固定头部(偏移 0..58，59 字节) || salt(salt_len 字节)
```

- 固定头部长度恒定（59 字节），其中已含 `salt_len`；其后紧跟恰好 `salt_len` 字节的 salt。两者拼接无歧义：任何解析者都能从固定头部读出 `salt_len` 并切出 salt。
- **IV 不进入 AAD**（nonce 已作为 GCM 输入；RFC 5116 §3.1：nonce 无需保密）。
- 因此头部所有参数（格式版本、KDF/AEAD 标识、迭代次数、salt 长度、IV 长度、标签长度、schema 版本、明文长度、payload SHA-256）与 salt 本身**全部受认证**：任何篡改都会导致标签校验失败。

### 4.7 统一安全失败（无 oracle）

依据 RFC 5116 §2.2 与 SP 800-38D §5.2.2：解密输出只能是明文或**单一 FAIL 符号**；**错误密码与损坏必须不可区分**。

- **单一失败码**：`P706_CONTAINER_AUTHENTICATION_FAILED`。
- 下列全部情形映射到**同一**失败码、同一外部可观察行为、**同一时序量级**（不提前短路、不区分消息）：错误密码；标签校验失败；密文被篡改；`payload_sha256` 与解密后明文不符；头部字段与密文不一致。**禁止**先比较 `payload_sha256` 再解密，也禁止对上述情形给出不同错误文案或不同耗时。
- `payload_sha256` 的角色仅是**损坏检测**（计划 `:142`：「SHA-256 只能检测损坏，不能替代认证」）；它**不是**安全边界，其失败不得单独构成一条与认证失败不同的可观察路径。

### 4.8 资源上限与有界读取

| 限制 | 值 | 说明 |
| --- | --- | --- |
| 容器输入上限 | **2 GiB** | 打开备份时若文件大小 > 该值，类型化拒绝（`P706_CONTAINER_TOO_LARGE`），不读取 |
| 明文（快照）上限 | **1 GiB** | `plaintext_len` 超限即拒绝；同时防止 `plaintext_len` 被篡改为巨值触发分配 |
| `plaintext_len` 与容器大小的自洽 | `plaintext_len + 59 + salt_len + iv_len + tag_len == 容器大小` | 不成立即拒绝（无需解密即可判定） |
| 磁盘前置 | 可用空间 ≥ 容器大小 + 明文大小 + 64 MiB 余量 | 不足即拒绝；不得在写满磁盘后留下「成功」标记 |
| 读取方式 | 固定 **64 KiB** 流式缓冲，逐块 `update`/写入 | **禁止**对无界输入调用 `readBytes()` 或任何整文件读入内存的 API |

依据：计划 `:144`「不得无界 `readBytes()`」、`:142`「最大输入/明文/磁盘占用」；大库实测 354 MB 快照需与源库同量级的额外磁盘（证据 `q13-driver-snapshot-gate.md`），故磁盘前置为硬门。

### 4.9 明文隔离与生命周期

- **认证完成前绝不打开或迁移数据库**（计划 `:144`：「不得未经认证打开数据库或执行迁移」）。
- **流式解密的陷阱**：`CipherInputStream` 会在标签校验前返回明文。故 v1 **禁止**用 `CipherInputStream` 直接把明文喂给数据库；实现必须显式 `Cipher.update`/`doFinal`，在 `doFinal` 成功（标签通过）**且** `payload_sha256` 校验通过之前，不得把暂存文件当作可信输入。
- **明文落点**：认证期间解密输出只写入**应用私有暂存目录**下的临时文件（Android 应用私有目录 / 桌面平台用户数据目录下的私有子目录）。认证失败 → 立即删除暂存文件；认证成功 → 才允许在**隔离副本**上执行结构/领域校验与迁移（06.C）。共享 UI 不接触文件路径、driver 或密码日志（计划 `:152`）。
- **清理**：未确认的暂存在取消、会话结束、下次启动时清理（计划 `:166`）；OS 自动备份/设备迁移需有明确排除规则覆盖明文暂存、回退与 journal（计划 `:166`）。**禁止**声称安全擦除已删除文件的物理扇区。

### 4.10 默认加密与密码策略

- **默认加密**：导出默认产生加密容器（计划 Q13）。明文容器不在首版提供。
- **密码永不持久化**：密码不写入任何文件、偏好、日志或容器；`PBEKeySpec` 使用后应清除其字符数组（`clearPassword()`），派生密钥字节在使用后清零。**不承诺找回**（计划 `:142`）。
- 密码强度：写入端要求非空且长度下限（如 ≥ 8 个码点）并在 UI 明确提示；**不**以强度估计替代 KDF 参数（§4.4）。

## 5. Q14 世代 / 指针设计（06.0，设计级）

依据计划 Q14（`:146`）：完整替换同一单账本，**私有代际目录 + 原子活动指针**；首版只接受固定账本身份（`LedgerId("ledger-local-test")`，`App.kt:300`）且库中无额外账本；不改写 ledgerId、不合并、不加 selector。

### 5.1 稳定存储（06.D 前置）

- **桌面问题**：产品入口当前每次启动新建临时目录（`Main.kt:162-165`），路径不稳定。06.D 前置 = 桌面产品入口改为**平台用户数据目录中的固定位置**；demo/test 临时路径仍可注入并隔离（计划 `:156`）。
- **Android 问题**：现用固定库 `databases/ledger.db`（`App.kt:175`）首次接入代际目录时，必须**迁移旧位置与 sidecar**，不得当作空安装初始化（计划 `:156`）。
- **目录解析不在 tracked 文件写绝对路径**（计划 `:156`；`docs/CONTRIBUTING.md:165`）。
- 启动顺序（计划 `:156`）：**先处理未完成 journal → 再选择活动库 → 最后才允许正常业务**。缺失/损坏已登记活动代 **fail-closed**，不静默建空库。

### 5.2 私有代际目录

- 每代（generation）是一个私有目录，包含**自己的 DB 与 sidecar**（当前产品无 sidecar，见 §1.2；目录结构预留该能力）。
- 旧代在**新图打开并读回成功之前**保留（计划 `:146`）；不得在新代验证通过前删除旧代。
- 代目录与 journal 的命名与布局由实施批细化；本规格冻结其**语义**（私有、单账本、可枚举、可回退），不冻结字面目录名。

### 5.3 原子活动指针 + 持久 journal

- **活动指针** = 一个指向「当前活动代」的小文件；其更新必须借助平台原子替换原语（Android `AtomicFile`，https://developer.android.com/reference/android/util/AtomicFile；桌面以「写临时文件 + 原子 rename 到固定名」实现）。**不得**把多个文件的 rename 当作一次原子事务（计划 `:146`）。
- **持久 journal** 记录阶段：**prepared → switched → committed**。语义：
  - `prepared`：新代已完整落盘并通过校验，但指针未切换。
  - `switched`：活动指针已指向新代，但新图尚未成功打开并读回。
  - `committed`：新图打开并权威读回成功，可发布新 generation。
- 崩溃顺序（计划 `:146`）：以平台实测证明原子性与崩溃顺序；在 `switched` 之后、`committed` 之前崩溃时，重启按 journal **回滚**（指针回退到旧代）或前滚，二者必须有确定规则且只能二选一，不得混搭两种半实现。
- `AtomicFile` **不提供锁**，也不使数据库与 sidecar 整组天然原子（计划 `:106`）；故整组一致性靠 journal + 代目录，而非单次 rename。

### 5.4 拒绝规则（严格结构识别门）

- **默认拒绝**：数据库版本未知、`user_version=0`、或 `user_version` 高于当前支持的 schema，一律**默认拒绝**（计划 `:164`、E06-03 `:84`）。
- **禁止复用桌面宽松补戳路径**：`Main.kt:791-793` 与 `:812-819` 的「补当前版本戳」逻辑**不得**用于接受外来备份（计划 §6.2 明令）。若未来要支持历史无戳库，必须另立严格结构识别与迁移门（计划 `:164`）。
- **仅接受本产品身份**：ledgerId 必须是固定身份且库中无额外账本；跨账本容器类型化拒绝。
- 结构白名单与隔离迁移属 06.C（计划 `:137`），本规格只冻结「默认拒绝 + 不复用宽松路径」的规则。

### 5.5 06.D 必须满足的失败向量（来自计划 §6.2 状态机）

计划 `:162` 的恢复状态机为 `Reading → Authenticating → Staging → Validating/Migrating → PreviewReady → AwaitingConfirmation → Quiescing → Prepared → Switching → Reopening → Committed`。06.D 必须逐阶段满足：

1. **确认前零正式写入**：确认前所有写入仅在私有隔离区（计划 `:162`）。
2. **确认后准入与等待**：拒绝新业务租约，等待已有读写退出；存在未解决提交/批次时先完成结果解析或明确阻断，**不靠取消按钮假定零写入**（计划 `:162`）。
3. **发布门**：新图打开与权威读回通过才发布新 generation；旧异步结果一律丢弃（计划 `:162`）。
4. **切换前失败** → `Rejected`；**切换后失败** → 按 journal 回滚；**回滚也失败** → `RecoveryRequired`，保留双方并给出可恢复错误，不循环自动初始化（计划 `:162`）。
5. **清理**：成功后旧代仅保留到持久提交记录与新代重开校验完成，再按明确策略清理；未解决 journal 的相关代**不得**清理（计划 `:166`）。

## 6. 技术门结果（06.0）

技术门必须用匿名库验证（计划 `:164`）。下表区分**已闭合**与**仍开放**；任何 OPEN 项在冻结 06.A 之前不得被当作已达成（计划 `:144`、`:460`）。

| 门项 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| driver 快照：Android 系统 SQLite 3.44.3 `VACUUM INTO` 可达、参数绑定、产物完整性、拒绝覆盖、WAL 一致性 | **CLOSED** | `local/artifacts/p7-06-gate/q13-driver-snapshot-gate.md`（`bound-ok`、`integrity_check=ok`、`output file already exists` 拒绝、WAL 下自洽） |
| driver 快照：桌面 xerial sqlite-jdbc 3.51.3.0 `VACUUM INTO` 可达、参数绑定、产物完整性、拒绝覆盖 | **CLOSED** | 同上（桌面段：`sqlite_version=3.51.3`、PreparedStatement 绑定 PASS、`output file already exists` 拒绝） |
| 跨端 crypto 字节兼容：同参数下 derivedKey 与 ciphertext+tag 在 SunJCE 与 AndroidOpenSSL 上逐字节相同；错密码 → `AEADBadTagException` | **CLOSED** | `local/artifacts/p7-06-gate/q13-crypto-official-evidence.md`（两端字节相同；统一 FAIL） |
| 官方参数依据：AES-GCM 96-bit IV / 128-bit tag；PBKDF2-HMAC-SHA256 ≥ 600,000 次；salt ≥ 128 bit；AAD 先供给；统一 FAIL | **CLOSED** | 同上（NIST SP 800-38D、NIST SP 800-132、OWASP、RFC 5116/8018） |
| 大库 `VACUUM INTO` 时长与磁盘：61k 库（354 MB）耗时 ~65.8 s、需与源库同量级额外磁盘 | **CLOSED（仅此两项）** | `q13-driver-snapshot-gate.md` 大库段：`~65.8 s`、产物 355,721,216 B、`integrity_check=ok`；`/data/user/0` +~0.7G |
| **大库峰值内存（61k）** | **OPEN** | **61k 峰值内存读数尚未取得**（设备无 `time -v`；`VACUUM INTO` 为流式实现但须在实现批补峰值内存读数）。证据文件明确记载未测峰值内存 |
| **桌面侧 61k `VACUUM INTO` 资源读数** | **OPEN** | 桌面段只测了 4 交易的小库（`user_version=30`）；61k 桌面读数未取 |
| **旧 schema 支持集与迁移门** | **OPEN** | 支持集合、严格结构识别与隔离迁移属 06.C；本规格只冻结「默认拒绝 + 不复用宽松补戳」（§5.4） |
| **文件指针切换 / 重启恢复** | **OPEN** | 属 06.1/06.D；当前两端**无** quiesce/lease/generation/活动指针（§1.1），桌面路径每次启动新建临时目录 |
| **06.D 完整恢复机制（准入、切换、回滚、故障重启）** | **OPEN** | 属 06.4；本规格只给设计级方案（§5） |
| **稳定存储（两端固定产品路径 + 旧路径升级）** | **OPEN** | 属 06.1/06.D 前置（§5.1） |

**结论（如实）**：Q13 的**格式字节定义**（§4）与**两端 driver 快照可达性**、**跨端 crypto 字节兼容**、**官方参数依据**已具备冻结条件；但**大库峰值内存**与**旧 schema 迁移门**仍未闭合，故本规格给出格式定义的同时，把这两项列为 06.A 最终冻结前的待补证据（计划 `:460`：Q13 实施规格冻结前必须补齐「现用 driver 快照实证；官方加密/KDF 依据、精确格式/参数/资源上限及双端验证」——其中「精确格式/参数」由本文给出，「资源上限」的**实测读数**仍待补）。

## 7. 验收映射（P706-A01..A12）

下表把计划 §6.3 的 12 个父验收向量映射到切片。**本规格不把任何向量标为 PASS**——全部为未来验收要求，尚未执行（计划 `:194`）。

| 验收 ID | 计划要求（摘要） | 归属切片 | 本规格的贡献 |
| --- | --- | --- | --- |
| P706-A01 | 当前库含全部 owner，往返逐 owner/稳定 ID 等价 | 06.2/06.4 | 提供完整 owner 清单（§3）作为往返分母 |
| P706-A02 | WAL 下持续写入时导出，还原只出现自洽时点 | 06.2 | 快照机制与 WAL 一致性已由 driver 门实证（§6） |
| P706-A03 | 错密码、认证失败、截断、伪格式、超限、未知未来/无版本库、异账本 → 类型化拒绝 | 06.C | 冻结统一失败码（§4.7）、资源上限（§4.8）、拒绝规则（§5.4） |
| P706-A04 | 支持集合内有账旧 schema 与迁移失败只改隔离副本 | 06.C | **格式层面已就绪**（`db_schema_version` 字段）；迁移门 OPEN（§6） |
| P706-A05 | 预览取消、确认旧 token、确认前更换来源/目标代际 → 零切换或 stale 拒绝 | 06.3/06.4 | Q14 的 prepared/switched/committed journal 为 token/代际绑定提供基础（§5.3） |
| P706-A06 | 每个持久状态边界掉电/杀进程及重启 → 完整旧代或新代可用 | 06.4 | Q14 journal 崩溃顺序要求（§5.3、§5.5） |
| P706-A07 | 恢复与导入/记账/读作业并发，旧回调晚到不污染新图 | 06.4 | Q14 的准入/等待要求（§5.5 第 2 点） |
| P706-A08 | 恢复库已有提交回执，重放同请求返回原结果、零新增经济效果 | 06.4 | 物理快照保留幂等回执（§3、§4.2） |
| P706-A09 | Android→Desktop→Android、两端退出进程再开，身份与事实一致 | 06.4/06.5 | 跨端字节兼容已实证（§6），保证容器可跨端解密 |
| P706-A10 | 满盘、SAF 写失败、取消、损坏活动库 → 不报成功，FOUND-001 保留旧库 | 06.2/06.4 | 磁盘前置与「失败文件不标成功」（§4.8、§5.1 fail-closed） |
| P706-A11 | 最终 schema 含预算配置历史及标签/商家注释历史，所有新增 owner 往返 | 06.5 | **前瞻性**：这些 owner 当前不存在（§3），纳入最终 schema 冻结时 |
| P706-A12 | 累积候选库叠加 20k/50k 正式交易及历史，记录大小/峰值内存/时长，零 OOM/ANR | 06.5 | **阈值在 06.0 基线后冻结**；本规格登记峰值内存 OPEN（§6），不预先冻结阈值 |

**格式层面本规格即可支撑的**：A01（分母）、A03（拒绝码与上限）、A04 的格式字段、A05/A06/A07 的协议基础、A08（幂等回执随物理快照保留）、A09（跨端可解密）。**属后续切片的**：A02、A04 的迁移门、A10、A11、A12，以及 A05/A06/A07/A08 的实际切换与故障注入。

## 8. 非目标与边界

- **不引入新 crypto 依赖**：仅用平台自带 `AES/GCM/NoPadding` 与 `PBKDF2WithHmacSHA256`；不引入 BouncyCastle/Tink/Argon2id/scrypt（证据文件已论证零能力增益、并增加依赖与许可负担）。
- **不采用 ZIP 或通用归档**：v1 为「简单头部 + 单数据库 payload」。若未来改为归档容器，必须补齐四类契约——**路径穿越拒绝**、**重复条目拒绝**、**条目白名单**、**解压上限**（计划 Q13 `:142`）；本规格不提供该路径。
- **不改 schema**：零 DDL、零迁移边、schema 停留 v31；不预占未来版本号（计划 `:375`）。
- **不触碰 `.external/`**；不写个人数据；tracked 文件不含本机绝对路径、临时研究或工具轨迹（`docs/CONTRIBUTING.md:165`）。
- **不改既有决定**：不新增决定条目；不改 D-156/D-158 及 P7-01～P7-05 冻结面。
- **不实现**：06.B/06.C/06.D 的产品代码与测试；稳定存储与 runtime owner（06.1）。
- **不声称**：不声称云备份/设备迁移已关闭（§1.2）；不声称安全擦除物理扇区（§4.9）；不把 OPEN 项当作已达成（§6）。

## 9. 边界断言与证据纪律

- 本文状态为 **proposal**（`docs/CONTRIBUTING.md:162` 允许分类之一）；尚未经独立规格评审，**不构成实施授权**，不冻结 Q13/Q14 的最终裁决。评审闭环并由主代理裁决后，本文方可转 `approved`（沿 D-156 规格先例 `docs/DECISIONS.md:2947`）。
- 每项事实主张均带 file:line 或公开 URL 证据；本地证据以主 checkout 的 `local/artifacts/p7-06-gate/` 两文件为准（只读，不粘贴大段原文）。
- **明确标记为未验证/未取读数**的项：61k 峰值内存（设备侧与桌面侧均未取）、桌面侧 61k `VACUUM INTO` 资源读数、旧 schema 迁移门、指针切换/重启恢复、06.D 完整机制、两端稳定存储（§6）。
- 本文不复制任何真实金额、时间、锚点注册值或个人数据；示例全部匿名合成；`.external/` 只读未触碰；`rgXX_` 竖井与 golden 零改动。