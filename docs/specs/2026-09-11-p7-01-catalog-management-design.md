# P7-01 账户与分类目录管理实施规格（设计门）

状态：approved（裁决依据 = 用户常设授权 + `docs/DECISIONS.md` D-143 登记；D-143 与本规格同批落盘，登记完成前不构成实施授权）。

**Revision:** draft-2（2026-09-11；独立规格评审 REQUEST-CHANGES 修正闭环，A1–A13 逐条落实）。承接登记链：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §3（P7-01 账户与分类管理，:45-72）与 §7（Q01/Q02 最迟决定点，:175-176）；该计划基线 `main` = `a710c6ce370b1eee8cd00ff8fe662377b930d795`（`docs/PROJECT_STATE.local.md:14`，主检出本地文件）。

**Scope:** 冻结「账户与分类目录管理」实施批（P7-01）的契约面：可管理账户与分类的生命周期裁决（Q01/Q02）、目录领域模型增补、每账本单调目录版本与乐观并发、claim-first 原子管理命令与 request/snapshot/receipt 幂等、旧库幂等 bootstrap 承接与桌面端迁移修复、失败码族、UI 契约、A01–A05 验收矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成值；引用均带 file:line；不粘大段产品代码。本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 worktree 基线 `a710c6c` 的实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **目录契约（Golden Schema）**：`docs/GOLDEN_SCHEMA.md:37`（「Catalog entities are state-owned because names, active flags, and other catalog attributes can change. Stable IDs do not change. A catalog update changes the entity snapshot and appends its own history entry」）、`:39`（余额必须覆盖目录内每个账户，含隐藏、非金融、零余额）、`:47`（`ref.kind` 注册含 `name_history`）、`:63`（`catalog.accounts` 形状：`id/name/kind/currency/owned_by_user/real_account/reconciliation_eligible`，`kind` 五值，可选 `system_role/system_managed/hidden`）、`:64`（`catalog.categories` 形状：`id/name/parent_id/posting_account_id/active`）、`:70`（二级分类 `posting_account_id` 必须等于所属分录账户；历史分录在分类停用后仍有效）。
- **会计规则**：`docs/ACCOUNTING_RULES.md:25-36`（领域不变量：稳定 ID、名称非关联键、展示层不得直接覆盖余额、差异用正式交易或余额调整表达）、`:84-92`（分类规则：一级/二级固定、一级创建同时建立至少一个二级、同父二级名称唯一、改名后历史显示当前名并保留历史、二级分别对应隐藏费用/收入账户）、`:217-237`（余额与调整：目标余额观察与明确确认、专用「余额调整」权益账户、零差额不建交易、幂等重试）。
- **决定（已确认/已批准）**：D-022（支出到二级，`DECISIONS.md:259`）、D-023（一级创建同时建至少一个二级，`:271`）、D-024（同父二级名称唯一、不同父允许同名，`:283`）、D-025（每账本独立维护分类，`:295`）、D-026（已使用分类默认停用、彻底删除前迁移历史，`:307`）、D-027（改名不改稳定 ID、历史显示当前名并保留名称历史，`:319`）、D-041（分类层级上限，`:491`）、D-046（二级对应隐藏费用/收入账户、一级仅分组、真实金融账户独立维度，`:551`）、D-063（余额调整对手账户与核验语义，`:757`）、D-066（分类 `level` 只从父链派生不单独存储、缺父身份必须待确认不得猜 `parent_id`、旧数据映射，`:799`）、D-077（恢复边界：合法历史在目录漂移后仍可恢复、新命令继续使用当前目录准入，`:947`）、D-087（RG-02 `category_rename` 最小闭环与 `rg02_category_name_history` 冻结表，`:1149`）、D-098（共享导入链范畴与「非 `rgXX_` 前缀共享表」方案 A 纪律，`:1475`）、D-113（受控 supersede 触发器先例，`:1814`）、D-119（ledger-scoped/current-version 读与选项边界，`:1978`）、D-120（P5-03 固定匿名目录与两端组合根，`:1994`）、D-126（确认页显示名来源扩展归后续批次，`:2092`）。
- **迁移先例**：`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/26.sqm`（P4-08 v26→v27：pre-guard → 结构重建无 RENAME → 零回填 → late sentinel → 版本推进；D-113）、`26.sqm:21` 的 `PRAGMA defer_foreign_keys` 纪律、`26.sqm:181-188` 的 late sentinel。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表冻结（D-066/D-098；`rg02_category_name_history` 不得复用为产品共享名称历史）；golden fixtures/expected 不改；Phase 7 后续批次（录入/看账/导入）不在本批。

## 1. 目标与范围

### 1.1 目标

- 交付可维护、重启持久的权威账户/分类目录：新建零余额账户与两级分类后退出重开仍在，名称替代裸 ID，停用对象历史可见但不可用于新录入。
- 交付同一权威目录版本同时服务选项、写入、读取与汇总（`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:61`），消除两端启动快照写死目录的现状。
- 从当前 schema v27 做纯加性迁移（预计 v28，版本号在实施基线分配），旧库经幂等 bootstrap 承接且正式 ID/金额/证据/余额不变。
- 修复桌面端打开已存在数据库不执行迁移的真实缺陷（§5.4）。

### 1.2 范围（冻结）

**范围内：** 领域目录模型增补（账户 `name/active`、分类 `name`、树与名称校验）；ledger-application 管理用例与请求/快照/回执契约、目录投影与 `expectedCatalogVersion` 乐观并发、提交重校验；ledger-data 新增非 `rgXX_` 前缀产品目录表、加性迁移、幂等 bootstrap、claim-first 原子命令；桌面端迁移接线修复；app-ui 账户 Tab 与共享分类管理；Android 人工门。

**范围外（本批明确不做，逐项冻结）：**

- **账户删除**：本批不提供任何账户删除入口或语义（A-5）。
- **分类改父级 / 重挂（reparent）**：不做（C-9）。
- **历史重分类迁移**：不做；有引用的分类一律拒绝删除（C-7）。
- **多币种**：管理范围内的金融账户币种固定 `CNY` / precision 2（A-1）。
- **余额初始值 / 余额调整能力**：不实现；初始余额恒 0，不得伪装普通收入（A-3）。
- **不做通用 CRUD**：所有目录写入必须经明确管理命令与乐观并发，禁止 UI 直连 SQL（沿 D-119 边界）。
- **不改** `rgXX_` 竖井表、golden fixtures/expected、既有 P4/P5 正式提交语义；不引入产品 Clock/随机 ID（沿用既有 `UuidV7*` 来源）。
- 真实金额/时间/锚点注册值与个人数据不入文；示例全部匿名合成。

## 2. 现状与差距（file:line）

1. **账户无名称/启停属性**：`ledger-domain/src/commonMain/kotlin/com/unifiedledger/domain/Catalog.kt:26-35` 的 `Account` 仅 `id/ledgerId/kind/currency/ownedByUser/realAccount/systemRole/storedValue`，无 `name`、无 `active`、无 `hidden`；而 `docs/GOLDEN_SCHEMA.md:37,63` 已把 `name` 列为目录账户必需字段并把名称视为可变更的状态属性。差距 = 展示名与启停无承载。
2. **分类无名称**：`Catalog.kt:41-48` 的 `Category` 有 `parentId/postingAccountId/active/kind` 但无 `name`；名称仅存在于冻结竖井 `rg02_category_name_history`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/17.sqm:7-14`），且该竖井表只服务 RG-02 回放（D-087），不是产品共享目录承载。
3. **目录构造只查重复 ID**：`Catalog.kt:63-68` 只校验账户/分类 ID 不重复，不校验分类树（一级无过账账户、二级指向同账本同类隐藏过账账户、父身份缺失、层级上限、名称唯一）。`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:31`（E01）已记录该事实。
4. **改名不可重试**：`ledger-application/.../ConfirmedCategoryRename.kt:21-26` 的 `ExplicitlyConfirmedCategoryRename` 无 `requestId` 与幂等通道，`:43-56` 的 `ConfirmedCategoryRenameCommitPort.commitOnce` 是单次调用；`ledger-data/.../SqlDelightConfirmedCategoryRenameCommitPort.kt:30-73` 只做「supersede 当前版本 + 追加下一版本」，没有 claim-first、请求快照与回执。不能原样作为移动端可重试管理 API（`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:32`，E02）。
5. **两端目录写死**：`android-app/src/main/kotlin/com/unifiedledger/android/App.kt:173-246` 与 `desktop-app/src/jvmMain/kotlin/com/unifiedledger/desktop/Main.kt:223-312` 各自在组合根构造同一 `syntheticCatalog`（App.kt:248-299、Main.kt:333-384），固定 `ledger-local-test` / `CNY(2)` / `asset-payment-local` / `expense-account-local` / `expense-category-food` / `expense-category-breakfast`，并在启动时一次性注入选项、写入、读取与汇总（`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:33`，E03）。
6. **选项显示裸 ID**：`ledger-application/.../ManualExpenseOptionsProvider.kt:60`（`label = account.id.value`）与 `:76`（`label = category.id.value`）；`QueryManualExpenseOptions.queryOptions()`（`:48-85`）从注入的静态 catalog 快照过滤 `owned ASSET real` 支付账户与 `active leaf EXPENSE` 分类，无版本、无名称。
7. **Android 自动升级、桌面不升级（缺陷）**：Android 经 `AndroidSqliteDriver(schema = LedgerDatabase.Schema, ...)`（`ledger-data/src/androidMain/.../AndroidLedgerDatabaseHandle.kt:13-19`）由 schema 自动 `onCreate/onUpgrade`；桌面 `openDesktopLedger`（`Main.kt:319-331`）只 `JdbcSqliteDriver(databaseUrl)`（`:320`）并以 `buildLedgerGraph(driver, createSchema = !Files.exists(Path.of(filePath)))`（`:324`）决定是否 `Schema.create`（`:227-229`），**对已存在文件既不建 schema 也不调用 `Schema.migrate`**。两端不对称：桌面打开旧库会以旧结构继续运行而非 fail-closed。
8. **迁移与版本现状**：当前 schema 版本 = 27（`ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/LedgerDatabaseMigrationTest.kt:307`、`ImportSpineMigrationCoexistenceTest.kt:149`）；迁移文件 `1.sqm`…`26.sqm` 位于 `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/`，`verifyMigrations = true` 且 `schemaOutputDirectory` 指向 `.../sqldelight/databases`（`ledger-data/build.gradle.kts:73-79`）。
9. **现状推论**：产品运行时尚无「持久权威目录」；账户无名称/启停；分类名称只在 RG-02 竖井；两端各自持有启动时快照。P7-01 的自由度边界 = 新增非 `rgXX_` 前缀产品表 + 幂等 bootstrap + 桌面迁移接线；硬约束 = 旧正式 ID（如上列 demo ID）在承接后仍可解析，未知引用 fail-closed。

## 3. 裁决（Q01/Q02 逐条，已裁决推荐方案）

> 以下 Q01-A-1..A-7、Q02-C-1..C-10、V-1/V-2 为已裁决推荐方案，实施批必须逐条落入，不得自行更改语义。

### 3.1 Q01 账户生命周期裁决

- **A-1 可管理属性**：账户可管理属性仅 `name`（显示名）与 `active`（启停）；`kind`(ASSET/LIABILITY)、`currency`、`ownedByUser`、`realAccount`、`systemRole` 为创建时固定、之后不可就地改写。本批管理的金融账户币种固定 `CNY` / precision 2。
- **A-2 可管理账户集合** = 同账本内 `realAccount && ownedByUser && systemRole == null && kind ∈ {ASSET, LIABILITY}`。隐藏/系统/收入/费用/权益账户**不得**进入管理入口；分类对应的隐藏过账账户绝不单独管理。**谓词区分（A11）**：A-2 集合谓词 **≠** 既有支付账户选项谓词——`ledger-application/.../ManualExpenseOptionsProvider.kt:51-55` 只取 `kind == ASSET && ownedByUser && realAccount` 用于手工支出付款账户选项，二者用途不同、互不替换。持有 stored value 配置（`Catalog.kt:20-24,34`）的账户若同时满足 A-2 全部谓词（含 `kind ∈ {ASSET, LIABILITY}`）则属可管理集合，可改名/启停，但这不改变支付选项语义。
- **A-3 新建账户**：输入名称 + 类型（资产/负债）；币种固定 CNY；`ownedByUser=true`、`realAccount=true`、`systemRole=null`、`active=true`；**初始余额恒为 0，不提供初始余额输入**。余额只能由正式交易或未来独立的「余额调整」能力产生（本批不实现余额调整，不得伪装普通收入）。
- **A-4 账户改名**：只改显示名，稳定 ID 不变，追加名称历史（历史显示当前名）。
- **A-5 账户停用**：保留历史与余额，阻止新交易选用，选项/选择器排除；允许重新启用。**本批不提供账户删除**。
- **A-6 名称规范化**：去首尾空白；内部连续空白（含全角空格）折叠为单个半角空格；拒绝空/纯空白、控制字符、长度 > 64 码点。规范化后在同一账本内**所有非空名称的账户**之间唯一（区分大小写）；冲突返回类型化拒绝。
- **A-7 首次安装/空库默认目录**：仅当目录表为空时，幂等地种入与当前两端 `syntheticCatalog` 等价的默认目录（账户 `asset-payment-local`(资产,支付)、`expense-account-local`(隐藏费用过账)；分类 `expense-category-food` 一级 + `expense-category-breakfast` 二级）。**保留旧 demo ID 并登记为可管理项**。非空永不覆盖；未知引用 fail-closed，不猜造账户。

### 3.2 Q02 分类生命周期裁决

- **C-1 两级固定**：一级为分组（无过账账户），二级叶子有 `postingAccountId` 指向同账本、同类别的**隐藏过账账户**（支出叶子→隐藏费用账户，收入叶子→隐藏收入账户）。`level` 由父链派生，不单独存储；父身份缺失必须待确认，不猜 `parentId`。
- **C-2 创建一级分类**必须原子地同时创建至少一个二级子项（延续 D-023），并在同一事务内创建对应隐藏过账账户。允许后续向已存在一级分类追加二级（独立命令）。
- **C-3 名称唯一**：一级名称在 (账本, kind) 内唯一；二级名称在 (账本, parentId) 内唯一；不同父级允许同名。名称规范化规则同 A-6。
- **C-4 改名**：替换当前名并追加名称历史，稳定 ID 不变，历史统一显示当前名，可追溯。
- **C-5 停用叶子**：默认动作；但若会导致其父级没有任何可用（active）叶子，则拒绝（`LastActiveChildCategory`）。停用后不用于新记录，历史仍可见。
- **C-6 停用一级分组**：原子停用父级及其全部子项。
- **C-7 删除**：**仅允许无任何经济引用**的分类删除（无正式交易/分录/过账、无导入候选或确认引用、非任何分类的父级而子项仍存）。删除需含其名称历史与隐藏过账账户，原子完成；有引用一律类型化拒绝并提示改用停用。一级分组删除要求其下所有二级均可删除。
- **C-8 重新启用**：启用叶子要求其父级 active（否则拒绝 `ParentInactive`）；提供整组启用命令。
- **C-9 不做改父级/重挂（reparent），不做历史重分类迁移**。
- **C-10 分类对应隐藏账户与叶子同生命周期**（创建/启停/删除同步），不可被账户管理入口独立操作。

**D-026 语义澄清（A10）**：「已使用分类默认停用」（`DECISIONS.md:307`）指**用户对已使用分类的默认处置动作**，不是系统自动停用——本批不引入任何自动停用。C-7「无任何经济引用才可删」是对 D-026「彻底删除前必须迁移相关账目」的**收窄**，因此**永不触发**其历史重分类迁移义务（本批不做任何历史重分类迁移，C-9）。

### 3.3 目录版本与提交重校验

- **V-1 目录版本**：每账本维护单调递增的目录版本，任一成功管理变更使其 +1；请求携带 `expectedCatalogVersion`，不匹配返回类型化 `CatalogVersionConflict`（stale），零写入，要求刷新。
- **V-2 提交重校验**：已打开草稿在提交时必须按当前权威目录重新校验；被引用账户/分类已停用、已被删除、kind 不符或已非叶子时，整笔类型化拒绝且零正式写入。该重校验须在写入正式交易的事务内再次成立（不仅查预览）。

## 4. 领域模型变更

1. **`Account` 增补**（`Catalog.kt:26-35`）：新增 `name: String = ""` 与 `active: Boolean = true`（均带默认值）；既有可选 `systemRole`/`storedValue` 不变。`name`/`active` 属 **Kotlin 领域层产品侧扩展**；`docs/GOLDEN_SCHEMA.md:63` 的 golden fixture 形状不变，默认值不改变任何 golden 回放语义。默认值保证既有构造/解码点（评审统计约 198 处；涉及 `ledger-domain` `Catalog.kt`、`ledger-application` 各 `Rg01–Rg05 RawJsonDecoder.kt` 与 `Rg06–Rg12 FixtureReplay.kt`，以及两端组合根 `App.kt:248-299`/`Main.kt:333-384`）**编译与回放语义不变**。`kind/currency/ownedByUser/realAccount/systemRole` 保持构造期固定（A-1）。
2. **`Category` 增补**（`Catalog.kt:41-48`）：新增 `name: String = ""`（带默认值，影响面结论同第 1 条）。`active`、`parentId`、`postingAccountId`、`kind` 语义不变；`level` 继续由父链派生，不新增字段（D-066）。
3. **`LedgerCatalog.create` 维持现状、不改**：`Catalog.kt:63-68` 继续只校验账户/分类 ID 不重复，本批**不新增任何校验**（评审 P0-1；原 draft-1 的「目录构造校验强化」撤销）。
4. **新增产品目录装载校验 `validateProductCatalog`（纯函数，N-3 入口点已定义）**：新增**纯函数** `validateProductCatalog(catalog: LedgerCatalog): DomainResult<Unit>`（名称可自定），**接收已构造的 `LedgerCatalog`**，无 IO、无随机、无 Clock。**调用点 = 产品目录装载路径**：`ledger-data` 从 `catalog_*` 表装载并构造 `LedgerCatalog` 后、或应用装配目录后调用；**不在 `LedgerCatalog.create` 内调用**（§4.3），也不对 golden 目录态调用（§4.5）。
   - **校验范围（有意收窄，非遗漏）**——仅「**分类树形态**」三项：
     - 一级分类 `postingAccountId == null`；
     - **active 叶子**必须有 `postingAccountId`，且指向同账本、`systemRole == null && ownedByUser == false && realAccount == false`、kind 匹配（EXPENSE 叶子→EXPENSE 隐藏账户；INCOME 叶子→INCOME 隐藏账户）的隐藏过账账户；
     - **停用/历史叶子允许 `postingAccountId == null`**，装载校验**不得**因此拒绝（与冻结 golden 一致）。
   - **显式不在此路径强制（范围声明，非遗漏）**：**深度/父存在性**校验（如「父必须存在且至多两级」「层深 > 2」）**只**在**管理命令路径**执行（失败码 `CategoryLevelNotSupported`/`CategoryParentRequired` 等），**不**在装载路径强制。理由：冻结 golden `rg-10` 运行时的目录态存在**悬空父**——`golden/rules/rg-10.json:120` 的 `expense-category-meal-rg10`（`level: 2`、无 `parent_id`，其父 `category-meal-parent-rg10` 不在目录内）；装载路径若强制父存在/深度校验会误拒该合法回放态。故此三形态项之外一律不在装载路径强制。
   - 校验失败返回类型化违规（`CategoryPostingAccountInvalid`），不静默放宽、不猜造账户。
5. **冻结 golden 兼容（硬约束，A1/N-3）**：
   - 既有 golden 夹具中 `parent_id != null && posting_account_id == null && active == false` 的停用分类——`golden/rules/rg-01.json:49` 的 `expense-category-inactive`（对象块 :48-53）、`golden/rules/rg-02.json:25` 的 `income-category-inactive`——在本批**维持现状行为**：它们继续经**未改的** `LedgerCatalog.create` 装载，**本批不对 golden 目录态施加 `validateProductCatalog`**，故其行为与改动前逐值一致。
   - 本批**不改 `LedgerCatalog.create`、不改 golden、不改 fixtures**，也不为迁就 golden 放宽 `validateProductCatalog`；装载校验与 golden 装载是**两条独立路径**（前者仅 `catalog_*` 产品目录，后者仅 golden 回放）。
6. **领域用例**：新增账户/分类生命周期用例（纯领域，返回 `DomainResult`，无 IO/随机/Clock）；名称规范化与新名称版本派生沿用/对齐 `CategoryRename.kt`（`ledger-domain/.../CategoryRename.kt:14-70`）的追加式版本模型，账户与分类共用同一名称历史语义。
7. **既有解码器与 fixture 零改动（显式，A2）**：本批**不改任何既有解码器与 fixture**；解码器**不强制**透传 `name`（可留待后续批），`name` 默认值 `""` 不改变任何 golden 回放语义。
8. **稳定性**：所有改名/停用/启用/删除均不改变稳定 ID；正式分录、版本、金额、对账结果与余额不受目录变更影响（V-1/V-2 只约束新录入准入）。

## 5. 持久化与迁移（P7-01.B）

### 5.1 表结构草案（新增，非 `rgXX_` 前缀）

表名：`catalog_version`、`catalog_account`、`catalog_category`、`catalog_name_history`（账户与分类共用，避免复用冻结的 `rg02_category_name_history`）、`catalog_command_request`、`catalog_command_receipt`。完整 DDL 草案见 Appendix A。要点：

- `catalog_version(ledger_id PK, version)`：每账本一行、单调递增；DB 级守卫仅允许 `new.version = old.version + 1`。
- `catalog_account`：为 `docs/GOLDEN_SCHEMA.md:63` golden 账户形状的**子集**（`account_id/name/kind/currency_code/currency_precision/owned_by_user/real_account/system_role/hidden/active`）。**不含 `reconciliation_eligible`（A3）**——理由：本批现有域 `Account`（`Catalog.kt:26-35`）亦无此字段，该属性从正式 postings 的分录资格派生，不属于本批目录管理面，故显式不落列。`name`/`active` 为 Kotlin 领域层产品侧扩展所驱动的产品表列，`docs/GOLDEN_SCHEMA.md:63` 的 golden fixture 形状不变。可管理集合由 A-2 谓词派生，不另设标志列。
- `catalog_category`：镜像 `docs/GOLDEN_SCHEMA.md:64` 形状（`category_id/kind/parent_id/posting_account_id/active`），`level` 不落列（派生）；表级 `CHECK` 保证一级无过账账户、二级必有（**产品表约束**：产品表叶子恒有 posting account；golden 的停用叶子 `posting_account_id == null` 不入产品表，故不冲突，见 Appendix A 注）；FK 用 `(ledger_id, parent_id)`/`(ledger_id, posting_account_id)` 保证同账本。
- `catalog_name_history(ledger_id, owner_kind, owner_id, version_number, name, status)`：账户与分类共用，`owner_kind ∈ {account, category}`；受控 supersede 触发器（先例 D-113 / `26.sqm`）只允许 `CURRENT→SUPERSEDED` 且其余列不变；删除仅经 C-7 受控级联（store 强制）。
- `catalog_command_request(ledger_id, request_id) PK`：`command/request_snapshot/input_fingerprint/outcome/reason_code/expected_catalog_version/result_catalog_version`；**`outcome CHECK IN ('ACCEPTED','NO_CHANGE')`（A5）**——claim 与 receipt 仅在成功时落库，`REJECTED`/`CONFLICT` 在单事务内回滚 claim、不落任何终态行，`PENDING` 对外不可见故不在值域内。**`reason_code` 为预留列（本批恒 NULL，N-4）**：在 outcome 仅 `ACCEPTED`/`NO_CHANGE` 下无失败原因可记，保留该列以便后续批次在不改 schema 的前提下承载失败原因；本批写入路径恒置 NULL。
- `catalog_command_receipt(ledger_id, request_id) PK`：终态回执，含 `new_catalog_version` 与本批有界新建实体列（见 Appendix A）；UPDATE/DELETE 触发 ABORT（回执不可改写）。
- **`request_snapshot` 与 `input_fingerprint` 职责区分（A4）**：`request_snapshot TEXT NOT NULL` 是命令载荷的规范化副本，**等价 replay 判定以此为准**；`input_fingerprint` 为派生/校验用摘要（沿 persistence spec §2 指纹纪律），**不作为**等价判定依据。

### 5.2 迁移语义

1. **加性迁移，只建结构**：新迁移文件从当前 v27 升到目标版本（版本号在实施基线分配，预计 v28，**不提前写死**；文件名 = 当前版本号，预计 `27.sqm`）。步骤：`PRAGMA defer_foreign_keys = 1`（先例 `26.sqm:21`）→ 建 6 张新表 + 索引 → 挂 `catalog_name_history` 受控 supersede 触发器与 `catalog_command_request/receipt` 守卫 → late sentinel（重建后插入失败即整事务回滚，先例 `26.sqm:181-188`）→ 版本推进。**迁移本身不做任何数据回填**（不种默认目录、不改既有行）。
2. **迁移链与版本断言（确定性发现，不写死行号，A7）**：实施批必须以 `grep -rn "assertEquals(27, LedgerDatabase.Schema.version)"` 与 `grep -rn "Schema.version" --include=*.kt` 全仓发现全部断言并**全部更新为新版本号**（不得遗留任何旧版本断言）；`verifyMigrations = true`（`ledger-data/build.gradle.kts:73-79`）必须保持通过。
3. **fresh=migrated 等价**：fresh 终态（`Ledger.sq`）与迁移后 schema 文本逐字一致（触发器/索引/CHECK），行集合零改写。
4. **失败整体回滚并可重开**：迁移在单一外层事务内；任一守卫/约束失败 → 整事务回滚、旧库文件与旧版本不变、可重试。

### 5.3 幂等 bootstrap（旧库承接，运行时）

1. **触发时机**：打开账本时，若某账本 `catalog_version` 无行且目录表为空 → 在单一事务内种入与 A-7/`syntheticCatalog` 等价的默认目录（固定 ID：`asset-payment-local`、`expense-account-local`、`expense-category-food`、`expense-category-breakfast`），并初始化 `catalog_version = 1`（种子本身不算「管理变更」，不 +1）。种子默认显示名为确定性合成字面量（实施评审冻结，见 Appendix A 注）。
2. **幂等**：目录表非空 → 不覆盖、不追加、不改版本；重复调用返回同一权威目录。并发/重试（含 claim 竞争）必须收敛到同一结果，不留半组目录。
3. **未知引用 fail-closed**：bootstrap 后校验账本内既有正式/导入引用（分录 `account_id`、`posting.category_id`、隐藏过账账户映射）均可解析到目录；任一引用缺失或与目录不一致 → 整笔 fail-closed，不猜造账户、不写目录，启动暴露可见失败（沿 D-077 恢复边界与 P5-04.4 fail-closed 先例）。
4. **正式面不变**：承接后正式 ID、金额、证据、对账与余额逐值不变（只新增目录行）。
5. **fail-closed 为期望终点（披露，A12）**：对引用非默认 demo ID（即既有正式/导入引用指向 bootstrap 未种入的账户/分类）的账本，升级后 `CatalogBootstrapUnknownReference` fail-closed 是**期望终点**——保留库文件、启动可见失败、仅 Retry/Exit，**不做任何猜测承接**；此类账本的目录映射/导入补救留待后续批次另行裁决，不在本批。

### 5.4 桌面端迁移缺陷修复（强制）

`openDesktopLedger`（`Main.kt:319-331`）必须与 Android 对称，步骤冻结为（A9）：

1. 读 `PRAGMA user_version` 探针取得 `from`（空库/新文件视为无版本）；
2. 空库/新文件 → `Schema.create`；
3. `from == 当前版本` → 直接打开；
4. `from < 当前版本` → 在**单一外层事务**内调用 `Schema.migrate(driver, from, to)`（沿用 `26.sqm`「调用方包外层事务」纪律）；
5. `from > 当前版本` → **fail-closed**（拒绝打开，不降级、不迁就）。

- **绝不删除或覆盖已有库文件**：不得以「删库重建」处理不兼容或迁移失败（与 `AndroidLedgerDatabaseHandle.kt:79-90` 的 `onCorruption` 保文件纪律一致）。
- 任一步失败经 `DesktopStartupController`（`Main.kt:153-196`）映射为 `P503StartupState.StartupError`，仅 Retry/Exit。
- 老逻辑 `createSchema = !Files.exists(...)`（`Main.kt:324`）必须被上述「版本检测 + 条件迁移」替换；不得静默跳过版本检测。
- **测试锚点修正（A9）**：`DesktopCurrentSchemaReopenTest`（`desktop-app/src/jvmTest/kotlin/com/unifiedledger/desktop/DesktopCurrentSchemaReopenTest.kt:24`）当前直接调用 `buildLedgerGraph(driver, createSchema = ...)`（同文件 `:31`/`:47`），**不经** `openDesktopLedger`，因此无法覆盖桌面迁移修复。扩展方式 = 改为**经由 `openDesktopLedger(url)` 走真实打开路径**，或另立桌面迁移测试类；`DesktopStartupControllerTest` 覆盖失败映射。不删既有断言。

## 6. 应用接口与失败码（P7-01.C）

### 6.1 管理命令（application）

统一请求形状（沿既有「手工支出」范式）`CatalogCommandRequest`：`requestId`、`requestSnapshot`（**等价 replay 判定的唯一依据**，规范化载荷副本）、`inputFingerprint`（派生/校验用摘要，不参与等价判定）、`expectedCatalogVersion`、命令载荷。统一四态结果 `CatalogCommandResult`：`Accepted(receipt)` / `NoChange(receipt)`（同 ID 同 `requestSnapshot` 等价 replay）/ `Rejected(failureCode)`（类型化拒绝，零写入）/ `Conflict(failureCode)`（`CatalogVersionConflict` 或 `RequestIdentityConflict`，零写入）。失败码见 §6.3。

| 命令 | 载荷 | 语义要点 |
| --- | --- | --- |
| `CreateAccount` | name, kind∈{ASSET,LIABILITY} | A-3：CNY/2、owned/real/systemRole=null、active=true、余额 0 |
| `RenameAccount` | accountId, newName | A-4：稳定 ID 不变 + 名称历史；须属可管理集合 |
| `SetAccountActive` | accountId, active | A-5：停用保留历史与余额、选项排除；可重启 |
| `CreateCategoryGroup` | kind, groupName, firstChildName | C-2：同一事务建一级 + 首二级 + 隐藏过账账户 |
| `AppendCategoryChild` | parentId, name | C-2：向已存在一级追加二级（同事务建隐藏过账账户） |
| `RenameCategory` | categoryId, newName | C-4：稳定 ID 不变 + 名称历史 |
| `SetCategoryActive` | categoryId, active | C-5/C-6/C-8：叶子停用须保留 ≥1 可用子；一级停用级联全子；启用叶子要求父 active |
| `DeleteCategory` | categoryId | C-7：仅无引用；含名称历史与隐藏过账账户；一级要求全子可删 |
| `EnableCategoryGroup` | parentId | C-8：整组启用 |

### 6.2 读模型与提交重校验

- `QueryCatalogSnapshot(ledgerId)` → `{catalogVersion, manageableAccounts, categoryTree}`（含名称、kind、active、余额展示所需数据；隐藏/系统账户不出现在管理投影）。
- `QueryManageableAccounts(ledgerId)` → 账户列表投影：显示名、类型（资产/负债）、余额、active；名称替代裸 ID（消除 `ManualExpenseOptionsProvider.kt:60,76` 的 `id.value` 标签）。
- 选项供应、写入、读取与汇总必须来自**同一权威目录版本**；管理成功后无需重启即可刷新（`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:61`）。
- **V-2 提交重校验**：任何消费目录的正式写入（本批落地为既有手工支出提交路径）必须在其写入事务内重新读取当前权威目录并校验被引用账户/分类 `active`、存在、`kind` 匹配、为叶子；失败整笔类型化拒绝、零正式写入。预览/选项快照不构成提交许可。

### 6.3 失败码（稳定命名）

code 稳定、message 不稳定不比较（沿 D-098 诊断 taxonomy 与 P4-08 correction 规格 §9 失败码风格）：

| code | 触发 | 结果 |
| --- | --- | --- |
| `CatalogNameEmpty` | 规范化后为空/纯空白（A-6） | Rejected，零写入 |
| `CatalogNameTooLong` | 规范化后 > 64 码点（A-6） | Rejected，零写入 |
| `CatalogNameInvalid` | 含控制字符（A-6） | Rejected，零写入 |
| `CatalogNameConflict` | 账户名全账本重复（A-6）；一级同 (账本,kind) 重复；二级同 (账本,parentId) 重复（C-3） | Rejected，零写入 |
| `CatalogVersionConflict` | `expectedCatalogVersion` ≠ 当前目录版本（V-1） | Conflict，零写入，要求刷新 |
| `RequestIdentityConflict` | 同 `requestId` 不同 `requestSnapshot` | Conflict，零写入 |
| `CatalogObjectNotFound` | 目标账户/分类不存在（A6） | Rejected，零写入 |
| `AccountNotManageable` | 账户不属于 A-2 集合（隐藏/系统/收入/费用/权益/非本人/非真实）（A6） | Rejected，零写入 |
| `AccountKindNotManageable` | 新建/请求 `kind ∉ {ASSET, LIABILITY}` | Rejected，零写入 |
| `CategoryNotManageable` | 分类非本人/跨账本或不属于可管理目录（A6） | Rejected，零写入 |
| `CategoryLevelNotSupported` | 三级、叶子下挂子、一级作为二级挂入（层深 > 2） | Rejected，零写入 |
| `CategoryParentCrossLedger` | `parentId` 不属于请求账本 | Rejected，零写入 |
| `CategoryParentRequired` | 二级分类缺父身份 / 父身份缺失待确认（D-066） | Rejected，零写入 |
| `CategoryPostingAccountInvalid` | 隐藏过账账户映射错误：非同账本、kind 不符、非隐藏、或指向禁用账户 | Rejected，零写入 |
| `CategoryHasNoActiveChild` | 停用叶子会使父级无任何 active 叶子（C-5；领域违规 token `LastActiveChildCategory`） | Rejected，零写入 |
| `CategoryHasReferences` | 删除分类存在正式/分录/过账/候选/确认或子项引用（C-7） | Rejected，零写入，提示改用停用 |
| `ParentInactive` | 启用叶子时父级非 active（C-8） | Rejected，零写入 |
| `CatalogBootstrapUnknownReference` | bootstrap 后既有引用无法解析（A-7/§5.3） | fail-closed，零目录写入，启动失败 |
| `CatalogConstraintViolation` | 触发器/唯一/FK 兜底失败 | 整事务回滚，typed 拒绝/失败 |

**失败码到命令的映射（A6，避免误用兜底码）**：

| 命令 | 可能失败码 |
| --- | --- |
| `CreateAccount` | `CatalogNameEmpty`/`CatalogNameTooLong`/`CatalogNameInvalid`/`CatalogNameConflict`/`AccountKindNotManageable`/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `RenameAccount` / `SetAccountActive` | `CatalogObjectNotFound`/`AccountNotManageable`/`CatalogNameEmpty`/`CatalogNameTooLong`/`CatalogNameInvalid`/`CatalogNameConflict`（仅改名）/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `CreateCategoryGroup` | `CatalogObjectNotFound`（隐藏账户/父导航）/`CatalogNameEmpty`/`CatalogNameTooLong`/`CatalogNameInvalid`/`CatalogNameConflict`/`CategoryPostingAccountInvalid`/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `AppendCategoryChild` | `CatalogObjectNotFound`/`CategoryNotManageable`/`CategoryParentRequired`/`CatalogNameConflict`/`CategoryLevelNotSupported`/`CategoryPostingAccountInvalid`/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `RenameCategory` | `CatalogObjectNotFound`/`CategoryNotManageable`/`CatalogNameEmpty`/`CatalogNameTooLong`/`CatalogNameInvalid`/`CatalogNameConflict`/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `SetCategoryActive` | `CatalogObjectNotFound`/`CategoryNotManageable`/`ParentInactive`（启用叶子）/`CategoryHasNoActiveChild`（停用叶子）/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `DeleteCategory` | `CatalogObjectNotFound`/`CategoryNotManageable`/`CategoryHasReferences`/`CatalogVersionConflict`/`RequestIdentityConflict` |
| `EnableCategoryGroup` | `CatalogObjectNotFound`/`CategoryNotManageable`/`CatalogVersionConflict`/`RequestIdentityConflict` |

零写入契约：任一 Rejected/Conflict 与 unique/FK 冲突沿命令事务全回滚（claim 行亦回滚 → 身份可重试，D-098 领域 4 语义）；等价 replay 返回原 receipt、零新增实体与名称历史。**并发收敛（A5）**：同 `(ledger_id, request_id)` 唯一键冲突 → 读既有 request 行；`request_snapshot` 相等 → `NoChange` 返回原 receipt；不等 → `RequestIdentityConflict`。claim + work + receipt 在**同一事务**内完成，`PENDING` 状态对外不可见（故 `catalog_command_request.outcome` 仅 `ACCEPTED`/`NO_CHANGE` 两值落库）。

## 7. UI 契约（P7-01.D）

1. **账户 Tab 可管理**：展示可管理账户（A-2）的显示名、类型（资产/负债）、余额与 active；支持新增（仅名称 + 类型，币种固定 CNY、无初始余额输入）、改名、停用/启用；不提供删除（A-5）。
2. **共享分类管理**：按支出/收入分组，两级展示；支持增组（含首个二级）、增子、改名、停用/启用、删除无引用项（C-1..C-8）；一级与二级路径用于消歧（D-024）。
3. **名称替代裸 ID**：账户/分类选项与管理页显示名称；历史引用统一显示当前名并保留名称历史（A-4/C-4，D-027）。
4. **权威刷新**：管理成功后无需重启即可刷新同一权威目录；`CatalogVersionConflict` 呈现为「目录已变化，请刷新」，不自动重试写入。
5. **两端启动兼容**：共享 UI 与两端组合根继续消费同一目录；默认 ID 保持（A-7），不因新增管理能力改变既有默认目录身份。
6. **draft 失效提示**：停用/删除后，引用该对象的已打开草稿在提交时被 V-2 拒绝，UI 呈现可操作刷新/返回，不静默沿用。
7. **Android 人工门**：覆盖新增 / 编辑 / 停用 / 删除（无引用）/ 返回 / 重开持久；遵守隔离 adb 协议（§10 第 4 项）。

## 8. 验收矩阵（A01–A05 可测步骤）

| # | 步骤 | 期望 | 关联失败码/契约 |
| --- | --- | --- | --- |
| A01 | 新建两个金融账户（各 ASSET/LIABILITY）+ 收入/支出各一组两级分类 → 退出 → 重开 | 目录持久；零余额账户正常显示；默认目录仍在 | A-3/A-7/C-2；`CatalogVersionConflict` 无 |
| A02 | 分别对账户与分类改名 → 查询历史与既有正式记录 | 稳定 ID 不变；分录金额/对账结果不变；历史统一显示当前名且可追溯 | A-4/C-4、D-027 |
| A03 | 停用账户/分类（保留 ≥1 可用子）→ 新录入选项 → 用停用前打开的草稿提交 | 停用后历史余额可见、新录入不可选；草稿提交被类型化拒绝、零正式写入 | A-5/C-5、V-2 |
| A04 | 依次尝试：三级、跨账本父级、同父同名、错误隐藏账户映射；重复同 ID 同内容；同 ID 不同内容 | 前四者类型化拒绝且不残留半组目录；重复等价请求不重复创建/追加名称历史；不同内容 `RequestIdentityConflict` | `CategoryLevelNotSupported`/`CategoryParentCrossLedger`/`CatalogNameConflict`/`CategoryPostingAccountInvalid`/`RequestIdentityConflict`；C-2/C-3 |
| A05 | v27 有账匿名库升级；注入迁移失败；同版本重开；并发/重试初始化；对历史/候选引用项执行删除 | 升级后正式 ID/金额/证据/余额不变；失败整回滚可重开；同版本重开一致；初始化幂等并发收敛；引用阻止删除 | A-7/C-7、§5.2/§5.3；`CategoryHasReferences`/`CatalogBootstrapUnknownReference` |

补充映射：停用叶子致父级无可用子 → `CategoryHasNoActiveChild`；启用叶子于非 active 父 → `ParentInactive`；stale `expectedCatalogVersion` → `CatalogVersionConflict`；名称空/超长/控制字符 → `CatalogNameEmpty`/`CatalogNameTooLong`/`CatalogNameInvalid`；目标不存在 → `CatalogObjectNotFound`；跨账本/非本人分类 → `CategoryNotManageable`。

**装载校验验收（A1/N-3，必须显式用例）**：

- **正路径（产品目录）**：构造产品目录（`catalog_*` 行装载所得 `LedgerCatalog`，含一级与 active 叶子且叶子绑定同账本同类隐藏过账账户）→ 调用 `validateProductCatalog(catalog)` → **返回 Success**。
- **兼容断言（golden 目录态）**：冻结 golden 目录态继续经**未改的** `LedgerCatalog.create` 装载，且**必须维持现状行为**——本批**不对 golden 目录态施加 `validateProductCatalog`**。以 `golden/rules/rg-01.json:49`（`expense-category-inactive`）、`golden/rules/rg-02.json:25`（`income-category-inactive`，停用叶子 `posting_account_id == null`）与 `golden/rules/rg-10.json:120`（`expense-category-meal-rg10`，悬空父）为夹具输入，断言装载与回放行为与改动前逐值一致、不抛新违规。
- **负路径**：构造 **active 叶子**缺 `postingAccountId`、或映射非同账本/非隐藏/kind 不符 → `validateProductCatalog` 类型化失败（`CategoryPostingAccountInvalid`）。
- **范围断言**：悬空父/深度 > 2 的目录态**不**由 `validateProductCatalog` 拒绝（仅管理命令路径以 `CategoryLevelNotSupported`/`CategoryParentRequired` 拒绝）。
- 断言 `LedgerCatalog.create` 行为与改动前逐值一致（仅重复 ID 校验）；本批不新增其校验。

## 9. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | 撤销对 `LedgerCatalog.create` 的强化（`Catalog.kt:63-68` 维持仅重复 ID 校验）；严格不变量改入**产品目录装载校验** | 若装载校验误拒停用/历史叶子，将破坏冻结 golden 兼容 | 停用/历史叶子允许 `posting_account_id == null`（§4.4、§8 装载校验验收）；以 `rg-01.json:49`/`rg-02.json:25` 为正路径夹具；不静默放宽、不改 golden |
| R-2 | `catalog_name_history` 为多态（账户+分类）表，跨表引用无 DB 级 FK | 完整性依赖 store 事务 | 受控 supersede 触发器 + claim-first 单事务 + 唯一键；登记为披露 |
| R-3 | 名称长度 ≤ 64 码点与一级名称 (账本,kind) 唯一为**新增产品约束**，权威文档未规定 | 需在实施/评审确认产品可接受 | `docs/GOLDEN_SCHEMA.md:63-64` 只声明 `string` 未设上限；登记披露，语义按本规格冻结 |
| R-4 | bootstrap 扫描既有引用做 fail-closed 校验可能随数据量增长 | 启动耗时 | 限定账本范围、单事务、索引化查询；超限以可见失败而非猜测处理 |
| R-5 | 桌面迁移修复依赖 SQLDelight JDBC 的 `user_version`/`Schema.migrate` 用法 | 迁移未执行或误判版本 | 实施批以 `26.sqm`「调用方包外层事务」纪律验证；扩展 `DesktopCurrentSchemaReopenTest`/`DesktopStartupControllerTest` |
| R-6 | 迁移版本号预计 v28，本规格不写死 | 迁移文件名/断言漂移 | 在实施基线分配并与迁移链断言同步（§5.2） |
| R-7 | 默认目录种子显示名（Appendix A 注）为合成默认值，权威未规定 | 文案偏差 | 实施评审冻结固定字面量；匿名、无个人数据 |
| R-8 | 本批不实现余额调整，初始余额恒 0 | 用户期望已有余额账户 | 按 A-3 明确不做；不得以普通收入伪装（`ACCOUNTING_RULES.md:217-237`、D-063） |
| R-9 | 聚合迁移校验在本机资源受限 | 本地无法完成 | 以 CI 为聚合权威（§10），本机跑 `:ledger-data:verifyCommonMainLedgerDatabaseMigration` 聚焦复现 |

**裁决一致性核对（对 10 条主裁决）：** 未发现与权威文档的语义冲突。逐项：A-1/A-2 与 `docs/GOLDEN_SCHEMA.md:37,63` 一致（名称/active 为可变状态属性、`system_role/hidden` 表达隐藏系统账户），A-2 可管理集合谓词与支付选项谓词（`ManualExpenseOptionsProvider.kt:51-55`）显式区分（A11）；A-3/A-5 与 `ACCOUNTING_RULES.md:25-36`、D-063 一致（不为余额调整能力）；A-6 为新增约束（§R-3）；A-7 与两端 `syntheticCatalog`（`App.kt:248-299`、`Main.kt:333-384`）一致，非默认 demo ID 账本的 fail-closed 为期望终点（A12，§5.3.5）；C-1..C-8 与 `ACCOUNTING_RULES.md:84-92`、D-022/23/24/26/27/46/66 一致；C-5 括号 token `LastActiveChildCategory` 与本规格失败码 `CategoryHasNoActiveChild` 指同一条件（本规格统一以 `CategoryHasNoActiveChild` 为稳定 code、`LastActiveChildCategory` 为领域违规 token，仅为命名归一，不改语义）；C-7 与 D-026「彻底删除前迁移历史」一致（本批仅收窄为无引用才可删、永不触发历史迁移义务，见 §3.2 D-026 语义澄清）；V-1/V-2 与 `ACCOUNTING_RULES.md:30`、D-077 恢复边界一致。**golden 兼容（A1）**：本批不改 `LedgerCatalog.create`、不改任何解码器与 fixture，停用/历史叶子允许 `posting_account_id == null`（§4.4–4.5、§8 装载校验验收）。

## 10. 实施与验证流程

1. **高风险路由**（沿根 `AGENTS.md` 与 `unifiedledger-harness`）：主代理建立隔离 worktree、指定单一 bounded writer 与精确可写范围；子代理先读主检出根 `AGENTS.md`、不复制嵌套索引、不变更 Git、不写 `.external/`。本批涉及目录/迁移/账务准入，规格与质量需**独立评审**，并以 **distinct verifier** 独立复跑，主代理最终复核。
2. **聚焦测试优先，再受影响模块**：先跑新目录用例聚焦 test，再跑受影响模块 `:ledger-domain:jvmTest`、`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`，随后 `ktlintCheck` 与 `project_docs`；P7-01.D 改动两端组合根时追加 `:desktop-app:jvmTest`、`:android-app:testDebugUnitTest`。
3. **聚合门以 CI 为准**：完整 `check`、Android/KMP 编译、Debug APK、Desktop build、完整 Python suite 与 migration verification 由 `.github/workflows/ci.yml` 在精确提交上提供发布证据，本机不重复资源密集型聚合（`AGENTS.md` 验证分工、`docs/CONTRIBUTING.md:31`）。
4. **Android 人工门隔离 adb 协议**：agent adb 一律 `ANDROID_ADB_SERVER_PORT=5038`、永不 `kill-server`、只操作本会话自行启动并已用 `emu avd name` 核实的设备；不触碰用户 MuMu/ALas 与 5554/5555 槽位；启动模拟器/重型构建前先确认用户自动化未运行。
5. **登记与实施前硬门（A13）**：独立规格评审通过后由主代理在 `docs/DECISIONS.md` 追加 D-143 并计算冻结 SHA-256；**实施批开始前必须确认 D-143 已落盘**（本规格 `状态：approved` 的实施授权以 D-143 登记完成为前提，登记完成前不构成实施授权）。实施、push 与 CI 触发仅在显式授权后由主代理执行。writer 只写本规格指定的可写文件，不执行任何 Git 写操作。

## Appendix A. 新表 DDL 草案（实施批以评审后文本为准）

```sql
CREATE TABLE catalog_version (
  ledger_id TEXT NOT NULL,
  version INTEGER NOT NULL CHECK (version >= 0),
  PRIMARY KEY (ledger_id)
);
-- 仅允许 +1 的受控推进
CREATE TRIGGER catalog_version_guard_update BEFORE UPDATE ON catalog_version BEGIN
  SELECT CASE WHEN new.ledger_id != old.ledger_id OR new.version != old.version + 1
    THEN RAISE(ABORT, 'catalog version must increase by one') END;
END;

-- 为 GOLDEN_SCHEMA.md:63 golden 账户形状的子集（A3）：不含 reconciliation_eligible
-- （现有域 Account 亦无此字段；该属性从正式 postings 派生，不属本批目录管理面）。
-- name/active 为 Kotlin 领域层产品侧扩展驱动的产品表列；golden fixture 形状不变。
CREATE TABLE catalog_account (
  ledger_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  name TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
  currency_code TEXT NOT NULL,
  currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
  owned_by_user INTEGER NOT NULL CHECK (owned_by_user IN (0, 1)),
  real_account INTEGER NOT NULL CHECK (real_account IN (0, 1)),
  system_role TEXT,
  hidden INTEGER NOT NULL CHECK (hidden IN (0, 1)),
  active INTEGER NOT NULL CHECK (active IN (0, 1)),
  PRIMARY KEY (ledger_id, account_id)
);

CREATE TABLE catalog_category (
  ledger_id TEXT NOT NULL,
  category_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('EXPENSE','INCOME')),
  parent_id TEXT,
  posting_account_id TEXT,
  active INTEGER NOT NULL CHECK (active IN (0, 1)),
  PRIMARY KEY (ledger_id, category_id),
  -- 一级无过账账户；二级必有 posting_account_id（**产品表**约束：产品表叶子恒有 posting account；
  -- golden 的停用叶子 posting_account_id == null 不入产品表，与该 CHECK 不冲突，见下注）
  CHECK ((parent_id IS NULL AND posting_account_id IS NULL)
      OR (parent_id IS NOT NULL AND posting_account_id IS NOT NULL)),
  FOREIGN KEY (ledger_id, parent_id) REFERENCES catalog_category(ledger_id, category_id) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (ledger_id, posting_account_id) REFERENCES catalog_account(ledger_id, account_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX catalog_category_by_parent ON catalog_category(ledger_id, parent_id);

CREATE TABLE catalog_name_history (
  ledger_id TEXT NOT NULL,
  owner_kind TEXT NOT NULL CHECK (owner_kind IN ('account','category')),
  owner_id TEXT NOT NULL,
  version_number INTEGER NOT NULL CHECK (version_number > 0),
  name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('CURRENT','SUPERSEDED')),
  PRIMARY KEY (ledger_id, owner_kind, owner_id, version_number)
);
-- 受控 supersede：唯一合法 UPDATE 是 CURRENT->SUPERSEDED，其余列不变（先例 D-113 / 26.sqm）
CREATE TRIGGER catalog_name_history_guard_update BEFORE UPDATE ON catalog_name_history BEGIN
  SELECT CASE WHEN old.status != 'CURRENT' OR new.status != 'SUPERSEDED'
      OR new.ledger_id != old.ledger_id OR new.owner_kind != old.owner_kind
      OR new.owner_id != old.owner_id OR new.version_number != old.version_number
      OR new.name != old.name
    THEN RAISE(ABORT, 'cannot update catalog name history') END;
END;
-- DELETE 无普遍守卫：C-7 受控删除（含名称历史与隐藏过账账户）是唯一合法删除路径，由 store 单事务强制。

CREATE TABLE catalog_command_request (
  ledger_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  command TEXT NOT NULL,
  request_snapshot TEXT NOT NULL,
  input_fingerprint TEXT NOT NULL,
  -- claim/receipt 仅在成功时落库；REJECTED/CONFLICT 单事务回滚，PENDING 对外不可见（A5）
  outcome TEXT NOT NULL CHECK (outcome IN ('ACCEPTED','NO_CHANGE')),
  reason_code TEXT,  -- 预留列：本批恒 NULL（N-4），为后续批次不改 schema 承载失败原因保留
  expected_catalog_version INTEGER NOT NULL CHECK (expected_catalog_version >= 0),
  result_catalog_version INTEGER,
  PRIMARY KEY (ledger_id, request_id)
);

CREATE TABLE catalog_command_receipt (
  ledger_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('ACCEPTED','NO_CHANGE')),
  new_catalog_version INTEGER NOT NULL CHECK (new_catalog_version >= 0),
  created_manageable_account_id TEXT,
  created_posting_account_id TEXT,
  created_parent_category_id TEXT,
  created_child_category_id TEXT,
  PRIMARY KEY (ledger_id, request_id),
  FOREIGN KEY (ledger_id, request_id) REFERENCES catalog_command_request(ledger_id, request_id)
);
CREATE TRIGGER catalog_command_receipt_guard_update BEFORE UPDATE ON catalog_command_receipt BEGIN SELECT RAISE(ABORT, 'cannot update catalog command receipt'); END;
CREATE TRIGGER catalog_command_receipt_guard_delete BEFORE DELETE ON catalog_command_receipt BEGIN SELECT RAISE(ABORT, 'cannot delete catalog command receipt'); END;
```

注：bootstrap 默认目录种子为确定性合成字面量，实施评审冻结，例如 `asset-payment-local`→「默认支付账户」、`expense-account-local`→「隐藏费用过账账户」、`expense-category-food`→「餐饮」、`expense-category-breakfast`→「早餐」（均为匿名默认值，无个人数据）；固定结构为：`asset-payment-local`(ASSET, owned=1, real=1, system_role=NULL, hidden=0)、`expense-account-local`(EXPENSE, owned=0, real=0, hidden=1)、`expense-category-food`(parent=NULL, posting=NULL)、`expense-category-breakfast`(parent=`expense-category-food`, posting=`expense-account-local`)。

**`catalog_category` CHECK 与 golden 的关系（A1 加注）**：该 CHECK 是**产品表**约束，约束「由本批命令创建/维护的产品分类」——产品命令的二级叶子恒会创建并绑定隐藏过账账户，故必有 `posting_account_id`。它与冻结 golden **无关**：golden 中 `parent_id != null && posting_account_id == null && active == false` 的停用/历史叶子（`golden/rules/rg-01.json:49`、`golden/rules/rg-02.json:25`）不属于产品表行，**不受该 CHECK 约束，也不被装载校验拒绝**（§4.4）。本批不改 golden。

FK 跨表字段（如 `catalog_category`→`catalog_account`）的 deferred 纪律与 fresh/migrated 逐字一致纳入迁移 verifier 断言。

## Appendix B. 请求快照与回执字段（草案）

```text
CatalogCommandRequest
  ledger_id, request_id                        -- claim 主键
  command                                      -- CreateAccount/RenameAccount/SetAccountActive/
                                               -- CreateCategoryGroup/AppendCategoryChild/RenameCategory/
                                               -- SetCategoryActive/DeleteCategory/EnableCategoryGroup
  expected_catalog_version                     -- V-1 乐观并发
  request_snapshot                             -- 等价 replay 判定域（命令载荷的规范化副本，A4）
  input_fingerprint                            -- 派生/校验用摘要，**不参与**等价判定（沿 persistence spec §2）
  reason_code                                  -- 预留列，本批恒 NULL（N-4）

CatalogCommandReceipt
  ledger_id, request_id
  outcome ∈ {ACCEPTED, NO_CHANGE}              -- 仅成功落库（A5）
  new_catalog_version                          -- 成功变更后的目录版本
  created_manageable_account_id?               -- CreateAccount
  created_posting_account_id?                  -- CreateCategoryGroup / AppendCategoryChild
  created_parent_category_id?                  -- CreateCategoryGroup
  created_child_category_id?                   -- CreateCategoryGroup
```

等价与并发语义（A4/A5）：同 `requestId` 同 `request_snapshot` → `NoChange` 返回原 receipt、零新增；同 `requestId` 不同 `request_snapshot` → `RequestIdentityConflict` 零写入；同内容不同 `requestId` → 正常执行（每次明确意图独立）。`input_fingerprint` 只作派生校验，**不作为**等价判定依据。

## 边界断言

- 本文档为设计门冻结候选：在独立规格评审闭环、**D-143 已落盘**与主代理登记前不构成实施授权；实施在单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（根 `AGENTS.md` 变更路由）。
- 实施批必须保持本规格冻结的：A-1..A-7、C-1..C-10、V-1/V-2、**`LedgerCatalog.create` 不改 + 严格不变量入产品目录装载校验**、**停用/历史叶子允许 `posting_account_id == null` 且不改 golden/解码器/fixture**、加性迁移零回填、幂等 bootstrap 与未知引用 fail-closed（非默认 demo ID 账本为期望终点）、桌面迁移修复不删库、失败码族、A01–A05 覆盖面；任何变更即重开评审门。
- 本批明确不做：账户删除、分类改父级、历史重分类迁移、多币种、余额初始值/余额调整能力。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`docs/specs/` 现有文档零改动（本批唯一写入 = 本新文件）。
