# 系统架构

## 当前状态

本文件定义目标模块边界和依赖约束。仓库当前包含 `ledger-domain`、`ledger-application`、`ledger-data` 与 `app-ui` 四个可构建的 Kotlin Multiplatform 共享库模块，以及 `desktop-app`/`android-app` 两个组合根应用模块（P5-03）。它们已承载 RG-01 至 RG-12 全部场景的 runtime 范围，包括精确金额、平衡分录与版本替代，明确确认、严格 raw JSON 与 request identity，以及全部场景的 SQLDelight 原子持久化、import/candidate/evidence ownership 和分录级 reconciliation。全部 12 个场景的领域、应用与持久化 runtime 及其 schema/migration 支持已进入共享库；RG-06 由 dedicated normalized owners 保存场景状态，并复用共享正式交易表。

`ledger-data` 使用 SQLDelight `2.3.2`，当前 schema 为 v31；迁移链 `1.sqm`~`30.sqm`（共 30 个文件，v1→v31；`30.sqm` 为 D-158 的 P7-05 片 1a 基底层加性边，纯加性零回填，11 个新 DDL 对象与 fresh `Ledger.sq` 终态定义逐字节一致）、fresh/migrated schema、一致性约束与 Android system SQLite 装配均有验证。迁移链包含 `ALTER TABLE DROP COLUMN`（SQLite ≥ 3.35.0），Android system SQLite 自 API 34（Android 14）满足；`ledger-data` 声明 minSdk 34。全部 12 个 RG 场景均有完整 oracle：RG-01 实现 note_update replacement、replay、request identity conflict 与 stale CAS 零写入，并有完整 state/delta/status 比较（D-087）；RG-09 严格 v2 oracle 以 runtime 独立投影比较已发布的 9 roots、50 operations 与 59 states；RG-08 lending settlement 44-op oracle（schema v15，D-084）；RG-11 periodic allocation 22-op oracle（schema v16，D-085）；RG-12 reconciliation correction 12-op oracle（schema v17，D-085）。

全部 12 个 RG golden v2 工件已发布（`golden/rules-v2/`，manifest 完整，357 operations），806 Kotlin tests 与完整 Python suite 均为绿色，RG-09 mapping gate 已 approved（2026-08-08）。RG-06 恢复边界由领域层验证 snapshot 并通过既有 `FormalTransaction` factory 重建正式链，不查询当前 catalog，也不允许 adapter replay command、解码 opaque aggregate 或重写领域不变量；恢复后的新命令仍按当前 catalog 准入。`D-075` 不授权 RG-05 fixture 迁移。已完成的 schema 变更：DATA-001（D-091），`19.sqm` v19→v20，合并五张私表为共享 `formal_transaction_metadata`，RG-08 的 `effective_at_text` 命名偏差随迁移闭合。下表中除现有模块之外的模块（`import-core`、`reconcile-core`、`reporting-core`、`platform-android`、`platform-desktop`）仍是后续实现必须遵守的逻辑职责，仓库尚未包含对应构建模块；`android-app` 与 `desktop-app` 两个组合根已按 P5-03 接通共享 `app-ui`（只做组合根装配）。P5-04.1 在共享 `app-ui` 建立三 Tab（首页/账户/分析）与新增入口壳（布局经 D-123 调整为底部 Tab 左组 + 右侧圆形新增按钮）：Tab 选择由共享 reducer 状态承载，账户 Tab 只渲染权威读取载荷中的余额，分析 Tab 经 facade 消费 `ledger-application` 的纯派生 `SummarizeLedgerActivity`（零 DDL、零新依赖）。P5-04.2（D-125）在 `app-ui` 扩展编辑流状态机：`Editing`/`AwaitingConfirmation`/`Submitting`/`RequestIdentityConflict`/`DomainRejected`/`InfrastructureFailure` 增 `overview`/`originTab` 全网络穿线（默认值向后兼容），新增 `Back` 事件把编辑流各态关闭回来源 Tab（Submitting 拦截并吞返回防提交中退出，确认页「取消」回编辑保留草稿、系统返回关闭丢弃），`app-ui` 暴露 `backHandler(enabled, onBack)` 平台钩子，Android 组合根经 activity-compose 既有稳定 `BackHandler` 接入（零新依赖、不引入导航库）。桌面应用经 `.\gradlew.bat :desktop-app:run` 启动并打开本地测试账本，Android 调试 APK 经 `.\gradlew.bat :android-app:assembleDebug` 构建；Desktop 键盘/焦点门与 Android TalkBack 人工门均已完成。`ledger-application` 在此指共享 library，`ledger-data` 的 Android target 也不是可运行的 app/client。

## 架构原则

- 账务核心不依赖 Android、Desktop UI、网络、同步、AI 或本地外部参考树。
- Android 与 Desktop 共享完整业务核心，不各自实现账务规则。
- 业务规则通过端口访问持久化、文件和平台能力。
- 解析器、匹配器、约束求解器和未来智能能力只生成候选，不直接写入正式账本。
- 正式交易只能通过应用层的确认用例创建或替换。
- 平台模块负责权限和系统集成，界面层不能修补账务规则。
- 数据迁移和版本替换必须保留来源、审计历史及失败恢复能力。

## 目标模块边界

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| `ledger-domain` | 精确金额、账户、交易、分录、版本、证据值对象和纯业务不变量 | 持久化、平台 API、界面状态 |
| `ledger-application` | 用例、事务边界、确认流程、权限无关的应用服务和端口 | 具体数据库、文件选择器、窗口或权限 |
| `ledger-data` | 共享持久化实现、查询、原子写入、schema 迁移和审计历史存储 | 决定账务规则或绕过应用用例 |
| `app-ui` | 共享演示界面、纯 UI 状态机/reducer、无障碍呈现和 P5-03 流程编排 | 数据库、SQLDelight、driver、平台窗口/Activity API、账务规则 |
| `import-core` | `SourceRecord` 标准化、解析结果、来源哈希、去重和导入候选 | 自动确认正式交易 |
| `reconcile-core` | 分录匹配、证据引用、差异、置信度和对账候选 | 修改余额或伪造补平交易 |
| `reporting-core` | 有效分录重放、余额、收支、消费和其他确定性报表计算 | 保存独立于正式账本的报表事实 |
| `platform-android` | Android 权限、采集、通知、文件和系统入口 | 账务规则与正式数据写入策略 |
| `platform-desktop` | 桌面文件、拖放、窗口和系统入口 | 账务规则与正式数据写入策略 |
| `android-app` | Android 组合根、界面和平台依赖装配 | 复制共享核心逻辑 |
| `desktop-app` | Desktop 组合根、界面和平台依赖装配 | 复制共享核心逻辑 |

## 依赖方向

```text
android-app  -> platform-android  --+
                                     |
desktop-app  -> platform-desktop  --+--> app-ui --> ledger-application --> ledger-domain
                                     |                    ^                 ^
                                     +--------------------|-----------------+
                                                          |
ledger-data -----+----------------------------------------+
import-core -----+
reconcile-core --+
reporting-core --+
```

- `ledger-domain` 位于最内层，不依赖其他目标模块。
- `ledger-application` 依赖领域类型，并定义外部能力端口。
- 数据、导入、对账和报表模块依赖领域或应用契约，不能反向成为核心依赖。
- `app-ui` 共享界面只消费 application 层类型（facade/use cases/read projection），不依赖 `ledger-data`、SQLDelight 或 database handle；两端组合根把装配好的 `P503LedgerFacade` 交给它。
- 平台模块实现系统能力端口，应用组合根负责装配。
- ID、时钟等运行时能力由应用用例通过端口消费；持久化适配器不拥有生成策略。当前 Android database handle 只是数据装配，不是 app 组合根。
- 客户端 UI 只能调用应用用例，不直接写数据库或构造绕过不变量的正式分录。
- 模块之间交换稳定 ID、精确金额和带时区的时间，不通过显示名称或备注建立业务关系。

## 运行时能力与时间

`D-093` 至 `D-095` 中已标记“暂停实施、重新审议”的历史细节不构成实现授权。`D-097` 已批准 contract-only P4-01 normalized source、typed diagnostics 与匿名 acceptance 子集；`D-098` 已定案 raw identity/retention/provenance、candidate lifecycle 与 atomic confirmation 合同（实施范围限于共享 spine 最小实现，docs/DECISIONS.md 的 D-098）；首个来源与 parser 技术已由 D-099 定案（微信账单 XLSX + Apache POI，ledger-application jvm 作用域）；matcher、产品 ID、Clock 与支付宝/P4-05、银行 PDF、P4-07/P4-08/P4-09 门禁继续待决。

阶段 4 产品路径的 ID 与 Clock 均是应用能力。当前源码证明 ID 保持在持久化 `commitOnce` 的原子首请求 callback 内惰性物化：持久化适配器先 claim 请求并判断 replay/conflict，只有赢得首请求的路径调用应用提供的 factory/callback；精确 replay、identity conflict 和并发失败方不消耗 ID。当前源码已有应用层产品 Clock 端口（`LedgerClock`，P5-02 IMP-4，只供应处理/创建/确认/审计时间）；该端口到具体用例的接入点由各用例签名在 P5-02/P5-03 决定，retry/并发语义与审计时间戳分配仍须另行决定。数据适配器负责原子写入、请求幂等、冲突检测、唯一性和事务恢复；它不能选择生成策略、读取系统时间补写来源事实或把 database handle 提升为应用组合根。既有 RG 专用 Store/IdentitySource 保持冻结回放语料，不构成产品装配先例。

Android 与 Desktop 的运行时端口实现由 `android-app` / `desktop-app` 组合根装配（P5-03：接通共享 `app-ui` 并保持组合根边界；真实系统集成需求出现后再建 `platform-*` 模块承接）。组合根只经应用用例调用共享核心；`ledger-data` Android target 中的 SQLDelight database handle 只是数据装配，不是可运行客户端或组合根。Golden 回放使用冻结输入供应确定性 ID 和文本时间；`GoldenV2Identity` 的命名空间与名字布局只属于 Golden v2 合同，不定义产品运行时身份。

来源发生、支付、入账、起息和观察时间是不可变来源事实，用户确认的统计时间是独立业务值；运行时 Clock 只供应处理、创建、确认和审计事件自身的时间。任何 adapter、用例或 Store 都不得用当前时间覆盖缺失或已有的来源时间。

## P5-03 演示面 B 边界（已实现，D-120）

`D-119` 批准 P5-03 的承接架构，不重开已交付的 D-118；该边界已由 D-120 实施（规格 `docs/specs/2026-08-30-p5-03-demo-surface-b-implementation-design.md`）。拟议 application 边界按 ledger 隔离并只投影 current transaction versions：`ledger-data` adapter 仅返回 ledger-signed current rows 与 request/snapshot/receipt 关系；application 以组合根注入的同一 `LedgerCatalog` snapshot 校验账户 ownership/kind/currency、投影可用 payment account/leaf expense category options，并派生正常余额展示符号。UI 只调用 read/options/write 用例，禁止直接访问 SQL、database handle 或从余额反推输入选项；不同币种不得汇总。

P5-03 demo 的 catalog 为两端以相同稳定 ID 确定性重建的固定匿名单 CNY snapshot，同一实例注入 write factory、options 与 read projection；catalog persistence/management 和产品多账户配置延后。精确金额由共享 application wrapper 复用 domain `parseExactDecimal`，两端不得复制 parser；每个 draft/save intent 使用独立平台 UUIDv7 request ID source，与六个 commit ID source 分离。

commit handoff 后的异常采用 snapshot-aware resolution：resolver 输入 ledgerId、requestId 与 attempted snapshot；只有逐值 matching receipt 可恢复成功，snapshot conflict 映射稳定冲突，absent/unavailable 保持 unknown 且不得自动重试或换 requestId。仅 handoff 前可证明 `commitOnce` 零调用的 orchestration failure 可重试。两端组合根还须实现 `Starting`/`Ready`/稳定 startup-error 的 fail-closed 装配，driver/schema/open 失败时业务 UI 与用例不可达，只提供 Retry/Exit。上述 read/options、resolver、amount/requestId 和 startup 边界均已由 D-120 实施（`docs/specs/2026-08-30-p5-03-demo-surface-b-implementation-design.md`），现为已实现约束。

`D-143`（P7-01）补齐 catalog persistence/management 边界：产品账户/分类目录由 `ledger-data` 持久化（非 `rgXX_` 前缀的 `catalog_*` 表，加性迁移、纯建结构零回填，旧库由幂等 bootstrap 承接）；`ledger-application` 拥有管理用例、目录投影与每账本单调递增目录版本（`expectedCatalogVersion` 乐观并发）；两端组合根装载同一权威目录，统一供应选项、写入、读取与汇总，管理成功后无需重启刷新。严格目录不变量置于产品目录装载路径（不改 `LedgerCatalog.create`），且停用/历史叶子允许无过账账户以保持冻结 golden 兼容；引用未知或被停用对象的提交在写入事务内校验并类型化拒绝。具体契约见 `docs/specs/2026-09-11-p7-01-catalog-management-design.md`。

`D-144`（P7-02）补齐统一录入边界：共享 `app-ui` 的编辑流以类型化草稿（`EXPENSE/INCOME/TRANSFER/LEND/COLLECT`，默认 `EXPENSE`）承载类型判别，只消费 `ledger-application` 类型；数据流固定为「校验 → 明确确认快照 → 应用用例 → claim-first 原子提交 → 权威回读」，每个新意图分配新 requestId，等价 replay 幂等、同 id 不同内容冲突、Unknown 不自动重试。备注由类型化草稿贯通并真正写入正式交易版本 `note`。手工转账复用领域 `createOwnAssetAccountTransfer`/`createOwnAssetPrincipalTransfer`（不接导入工厂），手工借贷复用 `LendingPosition`/`LendingSettlement` 领域规则并新增产品侧最小稳定往来对象目录、对象级本金查询与**按对象时间单调校验（倒填拒绝 `LendingBackdatedNotAllowed`，既有 `createLendingPosition` 原样作唯一重建校验器）**，**不复用 RG-08 回放来源编排、不生成 `BANK_DEBIT`/`MATCHED` 证据**。产品持久化为非 `rgXX_` 前缀新表（往来对象目录与名称历史、借贷位置与历史、手工转账/借贷 request/receipt、置顶偏好），自当前 v28 新增 `28.sqm` 目标 v29、加性迁移零回填。P7-01 的提交目录准入（V-2，真实 token 族 `CatalogAdmissionRejection`）扩展到全部新类型，在写入事务内重校验并整笔类型化拒绝；D-125/D-138/D-139/D-140 的支出编辑语义保持不变。具体契约见 `docs/specs/2026-09-12-p7-02-entry-foundation-design.md`。

`D-145`（P7-03，规格冻结 approved，LF SHA-256 `59D8E68C…0CEBB`）规划看账只读读边界：读模型以新增命名查询与端口方法扩展（有效 kind 读 `COALESCE(canonical_kind, kind)`、双时间与当前版本 note 入投影），既有 `CurrentVersionRow`/`loadCurrentRows` 与 22 个既有测试锚零改动；月度桶、普通收支分类（特殊 kind 与本金互转/借贷本金不入普通收支）与对账资格投影全部为 application 纯函数与只读查询，零 DDL、零账务写入路径改动、schema 停留 v29；有效统计时间一律取当前版本 `transaction_version.statistics_at`，不读 `formal_transaction_metadata`；月度投影逐币种携带普通收入/净支出/结余（结余不得称为账户余额或现金流），流水按统计时间倒序稳定排序，月度重请求触发由规格 §6.2 冻结。具体契约见 `docs/specs/2026-09-13-p7-03-ledger-view-design.md`。

`D-146`（P7-04，设计门已冻结：规格 approved，2026-09-14 DELTA CLOSURE APPROVE；实施登记已随实施批闭合补登于 `docs/DECISIONS.md` D-146（2026-09-15））规划导入与草稿确认边界：共享 `app-ui` 新增文件选择与有界读取端口（commonMain 定义接口与值类型，承担 16 MiB 读取上限——两端组合根闭包实现；display name 仅当次显示、绝不入身份与持久化；平台选择 Android 经系统文件选择器（SAF）一次性读取，Desktop 经文件选择对话框，均不持久系统文件引用、不保存文件副本）；`ledger-application` commonMain 以代码声明格式能力矩阵（UI 只显示当前平台声明可用的格式：支付宝 CSV 与招行 CSV 双端可用，支付宝 GB18030 以运行时字符集探测为界、不支持时类型化失败；微信 XLSX 双端可用，生产读取改用 jvmMain 内新「有界最小 XLSX 读取器」（`java.util.zip` + `javax.xml.parsers`，POI XSSF 退出生产读取路径、仅保留测试造数，`poi-ooxml` 依赖收敛至 jvmTest、`poi` 显式留 jvmMain 供建行 HSSF——此为 D-099 生产读取技术路径的修订，其格式契约不变）；建行 XLS 桌面可用、Android 诚实标记待设备运行验证不显示为可用）（CCB XLS 的 Android 单元已按 A-04.2 于 2026-09-18 翻转为 AVAILABLE，设备 instrumented 与产品路径证据背书；见 DECISIONS D-149）、导入接治编排端口（commonMain 接口 + jvmMain 实现，承担单文件 10,000 接治候选上限，超限类型化失败携带实收值、绝不静默截断）与导入候选/重复/恢复只读读取端口（fail-loud 默认，沿 D-145 G6 纪律）；生产 ID 端口修订——`ImportIntakeIdSource` 增需求量参数，由存储在获胜 claim 事务内按入站分支与事务内实际匹配数确定需求后调用，类型化早拒绝一律先于 ID 分配、拒绝与重试不消耗正式 ID，调用方严禁预查数量预分配；文件身份 = 每次文件选择生成的不透明随机句柄（UUIDv7，不含 URI、文件名、内容或任何个人标识；内容诊断哈希仍只作交叉校验、不构成身份、不参与去重），请求级重试沿既有 intake 幂等（NoChange/身份碰撞 hard reject），同文件重选/改名 = 新候选 + 疑似重复人工审核阻断（`CONFIRMED_DUPLICATE` 不再入账，不产生第二次余额/报表影响），绝不自动去重——审核含批量处置：同一次文件选择产生的疑似重复（`EXACT_BUSINESS_TUPLE` 且最新重复状态为待处置）可整组标记 `CONFIRMED_DUPLICATE`（组键 = kind + 本次文件选择句柄 + 待处置状态；比较快照为整组确认页的逐项呈现核验义务而非查询键；UI 层对逐项既有审核用例的顺序循环、每项独立请求与回执，零 core 语义改动、无整组原子性、可见部分成功）；批量确认 = 对勾选快照一次明确授权下的逐项原子提交与可见部分成功（无批次表、无整批事务承诺；恢复从既有持久化逐候选状态重建 ledger 级待确认清单）；确认时间 = 用户授权动作时间（注入 LedgerClock），来源记录时间不由处理时钟补写。schema 停留 v29（零 DDL，只新增只读命名查询，沿 D-145 先例）；导航面扩展为四 Tab（新增导入审核 Tab，D-122 三 Tab 契约由本批扩展）。四格式的目标 Android 运行证据整体为外部阻塞待办，不以设计侧证据替代（上述「schema 停留 v29」与「四格式运行证据外部阻塞」均为 D-146 于 2026-09-14 设计门冻结时的记录：「schema 停留 v29」已由 D-147 的 v30 加性迁移修订（`29.sqm`，纯加性覆盖索引）；Android 运行证据门 D01 已于 2026-09-15 在**披露语义**下闭合——该闭合只证成诚实披露义务，权限撤回向量保留 PARTIAL、建行 XLS 当时仍被平台门拦下且零解析；四格式双平台可用性由 A-04.1/A-04.2/A-04.3 于 2026-09-18 以设备证据另行确立，披露式闭合不等于四格式可用，二者不是同一命题）。具体契约见 `docs/specs/2026-09-14-p7-04-import-draft-confirmation-design.md`。

## 正式数据流

### 手工入口

```text
手工输入 -> 录入候选 -> 用户确认 -> Transaction / Posting
                                      |-> 余额与报表
                                      +-> 对账记录
```

手工输入没有外部来源文件时仍可形成用户确认事实，并保留创建与后续修正历史。

### 导入与自动入口

```text
来源文件或采集事实
  -> SourceRecord
  -> 标准化与去重
  -> 解析、匹配或约束候选
  -> 用户确认
  -> Transaction / Posting
       |-> 余额与报表
       +-> 独立对账记录 <-> 外部证据
```

每一层保留前一层引用。候选必须带来源、规则、置信度和待确认字段；重复来源可以补充证据，但不能再次影响余额。

阶段 4 产品路径 ownership 为 `D-092` 共享导入链的共享 spine（非 `rgXX_` 前缀共享 source/evidence/candidate/confirmation 链、追加-only 状态历史、claim-first 原子确认，见 `D-098`）；dedup/duplicate 数据合同、mirror/evidence matching 与 reconciliation、整文件保留生命周期合同分别延后至 P4-07、P4-08 与独立门禁，与 D-098 一致。

余额与报表从有效分录确定性计算。对账独立关联真实账户分录与证据，只描述核验状态，不回写金额或余额。

### 导入逻辑职责

`import-core` 当前是目标逻辑职责，不是已存在的构建模块。该职责拥有产品定义的 normalized source contract、可移植格式解析、raw/source facts 与派生字段分层、类型化解析诊断、来源身份、重复候选和导入候选生成；应用层拥有批次/命令幂等、确认用例和外部能力端口；`ledger-data` 只实现这些端口的原子持久化、唯一性、查询和审计历史。平台模块负责文件访问、权限和系统集成，portable parser 在可行时拥有格式语义，两者通过有界接口连接。平台路径或文件 API 不能泄漏为产品 schema；具体读取形态和容器解码责任继续暂缓。

格式解析与平台文件访问在可行时分离。D-097 的 P4-01 逻辑合同规定：input/container fatal failure fail-closed；record 级错误隔离，可靠记录保留，混合批次显式 partial；无效行只产生脱敏 typed diagnostic，不生成 normalized record；只有可靠来源事实已经形成但后续必要事实不足时才是 `valid_incomplete`。v1 只覆盖 ordinary expense/income 最小核心并版本化扩展，不预设 transfer/credit/refund superset、provider DTO 或序列化形状。

normalized source 中 source facts 与 derived facts 分层。机械可复核 decode 可以是 source fact；方向推断、默认币种、符号翻转、账户映射、分类、交易类型、duplicate 和 mirror 判断均属于 derived 或后续职责。unknown direction/status token 保留 raw/source token，normalized 语义 unresolved。金额保持 exact decimal 与 source scale，禁止 binary float；来源时间保留 temporal kind、components 与 offset presence，缺 offset 不得由 Clock 补写。

source location 只用于 diagnostic/provenance，不是 identity，只能由有界 opaque synthetic input ref、record ordinal 与 field role 组成；绝对路径、原文件名、worksheet 名、原始 header、raw value、整行、个人标识和底层库 exception 不得进入 diagnostic、日志、异常或测试失败。semantic records 按 multiset 比较并保留 multiplicity；permutation 验收在重映射 fixture coordinates 后比较 semantic multiset，不能把原 locator 固定为重排后的业务不变量。

P4-01 不做 dedup，也不创建 candidate、confirmation、formal transaction、posting、evidence link 或 reconciliation，不改变 balance/report。归一化契约由产品需求和匿名验收拥有，个人 Python 类型只作迁移与行为基线。raw retention/provenance 持久化合同已由 D-098 定案；首个来源与 parser 技术已由 D-099 定案（微信账单 XLSX + Apache POI，jvm 作用域）；整文件生命周期与支付宝/P4-05、银行 PDF、P4-07/P4-08 各门禁继续待决；整文件保存策略不能由 parser 实现自行决定。

批次 `request_id`、raw source record identity、duplicate candidate detection 和 mirror/evidence matching 是四个独立关注点。分类、账户映射、用户配置映射、对方归一化等可变结果不能决定权威 raw identity。业务指纹只提供重复候选信号，不能静默删除来源或直接复用正式交易；具体来源身份算法已由 D-098 定案；重复候选数据合同仍待 P4-07 决定。

`reconcile-core` 的状态变化必须先解析到精确且具备资格的真实账户 posting，并通过应用确认/对账用例遵守证据职责和场景合同。精确请求 replay 不追加新状态；同一经济事件的后到补充证据只追加已批准的 lineage，不创建第二笔正式交易或重复既有 link/reconciliation effect；排他性冲突类型化拒绝且零写入。具体 matcher 字段、时间窗口、歧义模型和基数由场景合同另行决定。通道级总额比较只生成诊断差额，不能链接证据、改变对账、抑制交易或代替逐 posting 验收。

## 候选与确认边界

解析器、去重器、匹配器、约束求解器和未来 AI 均属于候选生成能力。它们可以：

- 提取来源字段；
- 识别重复或镜像记录；
- 建议账户、分类和交易关系；
- 给出差异原因和处理候选。

它们不能直接：

- 创建、修改或删除正式交易；
- 无依据补平余额或对账差异；
- 将推断写成来源事实；
- 覆盖用户已经确认的版本历史。

确定性规则只有在用户对该来源或规则明确开启自动确认后，才能通过与人工确认相同的应用用例形成正式账目。

## Python 的位置

Python 只用于旧账迁移、规则原型、来源解析实验和黄金结果基线。个人配置与真实来源场景保持在本地；生产客户端不能调用个人脚本或依赖本地参考目录。Python 中确认过的通用行为已经开始通过匿名黄金测试逐项迁移到 `ledger-domain`，而不是整套脚本直接嵌入客户端。

## 技术选择状态

| 事项 | 状态 | 当前结论或决定门槛 |
| --- | --- | --- |
| 共享核心语言与平台 | 已确定 | Kotlin Multiplatform 共享 Android 与 Desktop 的完整业务核心 |
| 客户端策略 | 已确定 | Android 与 Desktop 保持最小可运行、可持续构建的外壳 |
| 平台边界 | 已确定 | 业务核心共享，系统能力和 UI 平台独立 |
| Python | 已确定 | 仅用于迁移、规则原型和黄金结果基线 |
| 运行方式 | 已确定 | 本地优先；同步与 AI 默认关闭且不影响核心验收 |
| 当前正式持久化边界的数据库与迁移 | 已确定 | `ledger-data` 使用 SQLDelight `2.3.2`；Android 只使用 system SQLite driver；Desktop（`desktop-app` jvmMain）使用 `app.cash.sqldelight:sqlite-driver:2.3.2`（JDBC，传递 xerial `sqlite-jdbc` 3.51.3.0）；Android system SQLite 不变；D-117。当前 schema v31，迁移链 `1.sqm`~`30.sqm`（30 个文件，v1→v31）均经过验证。迁移链含 `ALTER TABLE DROP COLUMN`（SQLite ≥ 3.35.0），Android system SQLite 自 API 34（Android 14）满足；`ledger-data` 声明 minSdk 34。该选择不预先决定报表、同步或更广泛查询的存储方案 |
| UI 与导航库 | 部分已确定 | UI 栈已确定 = Compose Multiplatform（CMP `1.12.0`（D-141 起；此前 `1.11.1`）已实装于 `app-ui`、`desktop-app`/`android-app` 双模块，P5-03）；皮肤库仍延后（皮肤主皮库选型证据已登记于 D-117 契约 §3.2、依赖随皮肤批进入）；导航库仍暂缓决定，Android 与 Desktop 的最小工作流、可访问性和预览需求明确后选择 |
| 依赖注入方案 | 暂缓决定 | 模块构造关系和测试替身需求稳定后选择 |
| RG-01 Golden JSON decoding | 已确定 | `ledger-application/commonMain` 使用 `kotlinx-serialization-json 1.11.0` runtime-only；不启用 serialization compiler plugin，不引入 Ktor；严格 duplicate/unknown/type/resource guard 位于 adapter 边界 |
| 产品运行时 ID 算法 | 已确定 | UUIDv7（RFC 9562），在持久化首请求 callback 内惰性物化，P5-02 实装；当前无产品存量 ID 数据、无需迁移；Golden v2 UUID 命名空间与名字布局仍不是产品默认（D-117） |
| CSV/XLSX 解析技术 | 四来源已定案（D-099 微信、D-101 支付宝、D-116 招行/建行）；微信生产读取已按 D-146 修订并实装（有界最小 XLSX 读取器） | 微信账单 XLSX 生产读取采用有界最小 XLSX 读取器（`java.util.zip` + `javax.xml.parsers` SAX，jvmMain；D-146 修订 D-099 第 2 条的生产读取技术路径，格式契约不变）；Apache POI XSSF 仅保留 jvmTest 造数、`poi-ooxml` 收敛至 jvmTest，`poi`（HSSF）留 jvmMain；支付宝 CSV 自研解析器（D-101）；招行 CSV 自研 + 建行 XLS 采用 POI HSSF（D-116；Android 端经 A-04.1 instrumented 验证与 A-04.2 矩阵翻转后可用）；银行 PDF 继续待决，届时单独评估具体库或自研实现 |
| 网络库 | 暂缓决定 | 第一个可选网络边界及其安全、离线和替换要求确认后选择 |
| 同步实现 | 暂缓决定 | 本地闭环、版本语义、冲突策略、加密和恢复要求通过验收后选择 |

暂缓决定不列未经比较的候选清单。每项选择必须说明适用模块、许可证、升级与替换成本，并用最小验证证明满足对应门槛后，才能改为“已确定”。
