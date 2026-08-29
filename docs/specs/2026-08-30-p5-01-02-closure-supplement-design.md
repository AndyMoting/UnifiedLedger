# P5-01/P5-02 闭环补充规格

**状态：approved** — 本文件是 P5-01/P5-02 之后、P5-03 之前的补充契约。P5-02 依 D-118 保持已交付；本文件不回改其完成状态。2026-08-30 用户明确批准已通过独立 specification/quality review 与 distinct verifier 的 proposal（SHA-256 `7A2603373469B68DF4F137411C69A916A168B142E1D9DF104B40BE9B51DFBA77`）。

## 1. 目的、阶段与边界

本文件补齐 P5-03 所需的用户流程、只读边界、提交不确定性、平台生命周期证据与验收契约。批准本规格只批准这些后续工作边界，不代表 closure evidence 或 P5-03 实现已经完成。

批准后的顺序冻结为：

1. 可立即起草 P5-03 spec-only；起草不构成代码实施授权。
2. 独立执行 `closure-evidence follow-up`，取得 P5-03 entry lifecycle evidence；该 follow-up 不是 P5-02 补交或 D-118 修订。
3. P5-03 实施规格经独立评审与用户批准，且 entry gate 满足后，才实施 P5-03。
4. P5-03 按本文件完成门验收。

本文件明确不做：

- 不修改 schema、迁移、账务规则、正式交易模型、导入、匹配、对账、退款或修正。
- 不引入皮肤库、品牌资产、复杂导航或新的设计系统。
- 不承诺草稿或未知提交状态的跨进程恢复；P5-03 最小版重启只恢复已持久化的正式结果。
- 不提供备注输入。当前 P5-02 factory 不把 `ManualExpenseRequestSnapshot.note` 带入正式交易内容；P5-03 最小路径固定 `note = ""`，备注 UI 与正式交易版本语义另立契约。
- 不提供 catalog persistence、catalog 管理或产品多账户配置。P5-03 只使用 §3.2 的固定匿名 catalog；扩展 catalog 仅用于边界测试。
- 不把 Android emulator 人工证据、P5-03 新 API 或 startup error state 写成当前已经实现的事实。

## 2. 权威与既有事实

- P5-01 契约：`docs/specs/2026-08-29-p5-01-dual-platform-shell-contract-design.md`（approved，D-117）。
- P5-02 实施：`docs/specs/2026-08-29-p5-02-dual-platform-skeleton-design.md`（approved，D-118）。
- 账务符号与正常余额：`docs/ACCOUNTING_RULES.md`；架构与验证：`docs/ARCHITECTURE.md`、`docs/CONTRIBUTING.md`。
- 既有保存链：`ExecuteManualExpenseSave` 将 `InvalidInput` 与 `Executed(ConfirmedManualExpenseResult)` 分开，并委托 `ExecuteConfirmedManualExpense`。
- 既有 `ConfirmedManualExpenseResult` 只有 `Created`、`NoChange`、`RequestIdentityConflict`、`Rejected`；没有基础设施失败或 unknown-commit 分类。
- 既有 `parseExactDecimal(text, precision)` 位于 `ledger-domain`：不 trim；ASCII grammar；允许可选负号、不允许正号；禁止前导零（字面 `0` 除外）；要求恰好 `precision` 位小数；`precision` 必须为 `0..18`；精确转换为 `Long` minor units，格式或溢出返回 `null`。
- `LedgerClock` 已是组合根能力，但当前 `ExecuteConfirmedManualExpense` 不消费它。本补充不为 Clock 指定新的 timestamp owner，也不以固定 Clock 作为 P5-03 可观察验收；未来确有运行时 timestamp 写入需求时另立接线契约。

## 3. P5-03 产品契约

### 3.1 首条流程

P5-03 实现同一条双端流程：

`权威只读空态 → 编辑 → 语法解析/应用校验 → 待确认 → 显式确认 → Created 或恢复结果 → 权威查询刷新 → NoChange replay → 重启恢复正式结果`

最小可见输入只有支付账户、二级费用分类、金额和发生时间；币种来自所选支付账户，不是自由文本；`note` 恒为空字符串。取消未提交 draft 零写入。

UI 只做展示和交互；精确金额解析、缺项校验、领域校验、幂等和事务全部走共享/application/domain 边界。正式保存链固定为 `ExecuteManualExpenseSave → ExecuteConfirmedManualExpense`，UI 不直连 data port 或 SQL。

### 3.2 Catalog 与可选项权威

P5-03 demo 只使用两端组合根按相同稳定 ID 确定性重建的固定匿名 `LedgerCatalog`。snapshot 逐值冻结为：

- ledger：`ledger-local-test`；唯一币种：`CurrencyUnit("CNY", 2)`。
- payment account：`asset-payment-local`，同 ledger、`ASSET`、owned-by-user、real account、CNY。
- expense posting account：`expense-account-local`，同 ledger、`EXPENSE`、非 real account、CNY。
- parent category：`expense-category-food`，同 ledger、active、`EXPENSE`、无 posting account。
- leaf category：`expense-category-breakfast`，同 ledger、active、`EXPENSE`、parent 为上述 category、posting account 为上述 expense account。

每次组合根启动/重试/重启必须重建逐值相同的 immutable snapshot，并把同一个 snapshot 实例同时注入 write factory、§4 application read projection，以及 proposed `ManualExpenseOptionsProvider`/`QueryManualExpenseOptions`。options 只暴露：

- 当前 ledger 中 `ASSET`、owned-by-user 的 payment accounts，并携带各自 `CurrencyUnit`。
- 当前 ledger 中 active、leaf/secondary、`EXPENSE` 且 posting account 也是同 ledger `EXPENSE` 的 categories。

UI 只能经 `QueryManualExpenseOptions` 取得选择项和账户币种，不得从 balances、posting rows 或硬编码 ID 反推选项。catalog persistence/management、动态新增账户/分类和产品多账户配置均延后。

五种 account kind、multi-currency、cross-ledger 测试使用扩展的匿名 injected catalog；这是 application read projection 的测试能力，不表示 P5-03 产品 fixture 支持多账户配置或多个币种。

### 3.3 精确金额解析

P5-03 proposed API addition：在 `ledger-application` 增加共享 wrapper `ParseManualExpenseAmount`，两端共同调用；不得各自实现金额 parser。wrapper 从 application 已解析的所选账户取得 `CurrencyUnit`，UI 不得提交独立 precision 覆盖账户币种。

wrapper 规则冻结如下：

- 仅 trim 两端 ASCII space/tab/CR/LF；trim 后空字符串拒绝，内部空白拒绝。
- trim 后调用既有 domain `parseExactDecimal`；grammar 为 `-?(0|[1-9][0-9]*)`，precision > 0 时必须追加小数点和恰好 precision 个 ASCII digit。
- `+`、指数、逗号、小数位不足/过多、前导零、超精度和 `Long` overflow 均为格式拒绝，零舍入。
- parser 成功只表示精确语法和范围有效；`0.00`、`-0.01` 可解析，是否必须为正由既有 domain/business validation 决定。
- CNY fixture precision 固定来自 `CurrencyUnit("CNY", 2)`；产品 precision 始终来自所选账户。

固定有效向量：`"35.80" → 3580`、`" 35.80\t" → 3580`、`"0.00" → 0`、`"-0.01" → -1`。固定拒绝向量：`""`、`" "`、`"+35.80"`、`"035.80"`、`"35.8"`、`"35.800"`、`"35,80"`、`"3.58e1"`、`"35 .80"`、`"92233720368547758.08"`。

### 3.4 requestId 生命周期

P5-03 proposed API addition：新增平台注入的 `ManualExpenseRequestIdSource`，生成规范 UUIDv7 `RequestId`。它与每次产生六个正式 commit ID 的 `ConfirmedManualExpenseIdSource` 是两个独立 source，不得复用一次 `next()` 或共享消费计数。

- `requestId` 是一次 draft/save intent 的幂等键。draft 首次进入保存/确认提交动作时分配；“进入提交动作”不表示校验已经通过。
- `InvalidInput`、`Rejected`、基础设施失败和 `UnknownCommit` 均保留原 requestId；修改同一 intent 后继续使用原 ID。
- 未提交即取消的 draft 可丢弃 ID；`Created`/`NoChange` 完成后，下一个新 draft 才分配新 ID。
- `RequestIdentityConflict` 保留原 ID 并显示冲突；只有用户显式放弃该冲突 draft、创建新的 save intent 后才分配新 ID，绝不静默换 ID 绕过冲突。
- P5-03 最小版不持久化待提交 draft 或 `UnknownCommit` presentation state，因此不承诺重启后恢复它们；重启只通过权威读边界恢复已存在的正式 transaction/receipt。若未来要求恢复 draft/unknown state，必须先新增持久化契约。

### 3.5 提交结果与未知提交

现有 `ConfirmedManualExpenseResult.Rejected` 只表示领域拒绝，不得承载数据库、driver 或其他基础设施异常。

P5-03 proposed application/presentation orchestration：新增 `ExecuteManualExpenseSubmission` 与 `ManualExpenseSubmissionResult`，后者至少区分：

- `Application(result: ManualExpenseSaveResult)`：既有 `InvalidInput` 或 `Executed(Created/NoChange/RequestIdentityConflict/Rejected)`。
- `Recovered(receipt: ConfirmedExpenseReceipt)`：commit handoff 后抛出异常，snapshot-aware resolver 返回 `MatchingReceipt`。
- `InfrastructureFailure`：只允许表示尚未调用 `commitOnce` 且可由 zero-call 断言证明的 orchestration/pre-submit failure；可使用同一 requestId 重试。
- `UnknownCommit`：一旦调用过 `commitOnce`，其后的任何异常都先进入此状态；禁止乐观刷新、禁止生成新 requestId、禁止自动重试。

异常恢复顺序固定：

1. `ExecuteManualExpenseSubmission` 在 handoff 前记录 `commitOnce` 是否被调用；pre-submit/orchestration failure 只有在 zero-call 可证明时才返回 `InfrastructureFailure`。
2. 一旦发生 commit handoff，此后的任何异常，无论本地事务看似已 rollback，公共契约都先返回 resolving/`UnknownCommit`。
3. 通过 §4 的 resolver，以 ledgerId、requestId 和本次 attempted `ManualExpenseRequestSnapshot` 三者查询权威状态。
4. `MatchingReceipt` 才返回 `Recovered` 并刷新总览；`SnapshotConflict` 映射为 `RequestIdentityConflict`/稳定冲突，绝不恢复为成功。
5. `Absent` 或 `Unavailable` 均保持 `UnknownCommit`；absence 不能证明 rollback，不允许自动重试或换 requestId。

这是一项 proposed 新 orchestration，不声称现有 API 已支持。若未来要暴露 typed rollback proof，必须另立契约；当前不得从 SQLite 异常文本或本地 transaction 观察推断公共 retry safety。

### 3.6 状态矩阵

| 场景 | UI 状态 | 权威结果与写入 |
| --- | --- | --- |
| 空账本 | 空态 | ledger-scoped 查询为空，零写入 |
| 金额语法失败 | 字段错误，保留输入 | shared parser 拒绝，零写入 |
| 缺少必填字段 | 字段错误，保留输入/requestId | `ManualExpenseSaveResult.InvalidInput`，零写入 |
| 待确认/取消 | 待确认或回到编辑 | 未执行 save，零写入 |
| 首次确认 | 成功 | `Executed(Created)`；一笔 current transaction、一个 posting set、两条同币种平衡 posting |
| 原样 replay | 恢复同一成功行 | `Executed(NoChange)`；平台内 receipt 与首次相同，零重复写入 |
| snapshot conflict | 冲突，保留输入/requestId | `Executed(RequestIdentityConflict)`，零新增写入；冲突向量改变 amount/category/account/time 之一，不使用 note |
| domain rejection | 业务错误，保留输入/requestId | `Executed(Rejected)`，零新增写入 |
| handoff 前 zero-call failure | 可重试基础设施错误 | `InfrastructureFailure`；证明 `commitOnce` 零调用后才可同 requestId retry |
| handoff 后 matching | 恢复成功 | resolver `MatchingReceipt` → `Recovered`；再权威刷新 |
| handoff 后 snapshot conflict | 稳定冲突，保留输入/requestId | resolver `SnapshotConflict` → `RequestIdentityConflict`，不得误判成功 |
| handoff 后 absent/unavailable | resolving/unknown，禁止新提交 | 保持 `UnknownCommit`；不乐观更新、不自动 retry、不换 requestId |
| 重启 | 只恢复正式结果 | current-version 查询恢复列表、余额和 receipt 引用；不承诺 draft/unknown presentation state |

`Created`、`NoChange` 或 `Recovered` 后必须重新执行权威查询刷新列表与余额，不得由提交返回值拼接列表或由 UI 自行累计余额。`NoChange` 不追加第二行。

### 3.7 双平台 parity

| 场景 | 必须一致 | 允许差异 |
| --- | --- | --- |
| 空态/输入/待确认 | 相同输入得到相同 parser/application 分类、输入保留和零写入 | 输入法、日期控件、窗口布局、返回手势 |
| `Created` | outcome 类型、current-version 计数、余额、receipt 结构与引用关系 | 两端随机 UUIDv7 字面值、视觉反馈 |
| `NoChange` | 各平台内 replay receipt 等于该平台首次 receipt，零重复写入、不追加行 | 提示形式 |
| conflict/rejected/failure/unknown | 稳定类别、requestId 生命周期、输入保留和写入结果 | 文案与错误布局 |
| 重启 | 各平台自己的正式结果在重启前后不变；跨平台结构、计数和金额一致 | 随机 ID 字面值、Activity/窗口生命周期 |

### 3.8 最低可访问性

- 输入、按钮和错误有明确标签；字段错误通过 Compose semantics 关联，读屏可读字段、错误和状态。
- 成功、错误、禁用、resolving 和待确认状态不只靠颜色，必须同时有文本或语义状态。
- Desktop 可用键盘完成流程，焦点顺序与视觉顺序一致且焦点可见；Android 可通过 TalkBack/Compose semantics 完成同一流程。
- Android 主要触控目标不小于 `48.dp`；图标按钮提供 content description。
- 首个字段错误可被焦点/读屏定位；动态成功、失败和 unknown 状态可被辅助技术感知。

### 3.9 固定匿名 fixture

| 字段 | 固定值 |
| --- | --- |
| ledgerId | `ledger-local-test` |
| requestId | 测试注入的规范 UUIDv7；同一 save intent 全程固定 |
| paymentAccountId | `asset-payment-local` |
| categoryId | `expense-category-breakfast` |
| amount | `35.80 CNY`（minor units `3580`，precision `2`） |
| occurredAt | `2026-01-15T00:30:00Z` |
| note | `""`，UI 不展示备注输入 |

测试分别注入确定性 request UUIDv7 source 和确定性六-ID commit source。人工验收只断言生成值均为规范 UUIDv7，且 requestId 与六个 commit ID 的职责、消费次数分离。

完整脚本依次执行：重建 §3.2 catalog 并查询 options、空态查询、金额有效/无效向量、缺 amount 的 `InvalidInput`、待确认取消、首次 `Created`、权威查询核对一行 current transaction/两条平衡 posting/逐账户逐币种余额、原样 `NoChange`、同 requestId 改 amount 触发 conflict、pre-submit zero-call failure，以及 post-handoff matching/conflict/absent/unavailable recovery，最后重启并核对同一 catalog snapshot 与正式结果。测试和 artifact 只使用匿名数据。

## 4. Proposed Application Read Boundary

P5-03 新增 ledger-scoped、current-version-only 只读边界；这是 proposed API addition，获批和实施前不存在。data adapter 只提供 ledger-signed current rows，application use case 以注入的 §3.2 catalog 校验并形成 UI projection。建议稳定名称如下：

- `LedgerCurrentStateReadPort`：`loadCurrentRows(ledgerId)`、`findManualExpenseByRequest(ledgerId, requestId)`、`findManualExpenseByReceipt(ledgerId, receipt)`；只返回 data-owned ledger-signed current rows 和持久化 snapshot/receipt 关系。
- `QueryLedgerCurrentState`：注入 `LedgerCurrentStateReadPort` 与同一 `LedgerCatalog` snapshot，校验 ledger/account ownership、kind 和 currency 后形成 current transaction 与余额 projection。
- `ManualExpenseOptionsProvider`/`QueryManualExpenseOptions`：只从同一 catalog snapshot 投影 §3.2 允许的 payment accounts/categories，不查询 balance 反推。
- `ResolveManualExpenseCommitStatus`：输入必须包含 ledgerId、requestId 和 attempted `ManualExpenseRequestSnapshot`，用于 §3.5 unknown-commit resolution。
- `SqlDelightLedgerCurrentStateReadAdapter`：`ledger-data` 实现；所有 SQL 与 database handle 留在 data/组合根，UI 不可达。

返回契约冻结为：

- resolver 结果至少为：`MatchingReceipt`（持久化 snapshot 与 attempted snapshot 全字段逐值相等）、`SnapshotConflict`（同 ledger/requestId 已存在但 snapshot 不同）、`Absent`、`Unavailable`。snapshot 等价比较覆盖 ledgerId、amount minor units + currency、categoryId、paymentAccountId、occurredAt 和空 note。
- `Recovered` 只能由 `MatchingReceipt` 产生；`SnapshotConflict` 映射为稳定 request identity conflict。`Absent`/`Unavailable` 都保持 `UnknownCommit`。receipt lookup 可用于正式结果重开，但 unknown resolution 只以 request + attempted snapshot 为权威。
- application `LedgerCurrentState`：指定 ledger 的 current-version transaction 列表与 `AccountCurrencyBalance` 列表。
- transaction row 至少包含 transactionId、currentVersionId、kind、occurredAt 和 current-version postings；不得返回已被 current pointer 替代的旧版本作为当前行。
- data adapter 的余额/Posting 返回 ledger-signed minor units，不形成 display sign。所有查询强制 ledger filter；不同 ledger 不得串读。
- application 以注入 catalog 验证每个 account 属于目标 ledger、kind/currency 与 posting 一致，再按 account + currency 分组；不跨币种汇总。依据 `ACCOUNTING_RULES` 派生 normal-balance display minor units：ASSET/EXPENSE 等于账本符号，LIABILITY/INCOME/EQUITY 取反。
- request/receipt 查询返回 receipt、requestId、transactionId 与 currentVersionId 的引用关系；查询必须 ledger-scoped，receipt 中 transactionId 必须与持久化关系一致。

P5-03 以 integration tests 证明 current-version filtering、cross-ledger isolation、resolver 四态、receipt lookup 与 reopen 一致性。五种账户 kind 的正常余额符号和 multi-currency 不汇总使用扩展匿名 injected catalog；这不扩大 §3.2 产品 fixture。零 schema 变更。

## 5. P5-03 Entry Lifecycle Evidence

本节工作统一称为 `closure-evidence follow-up` 或 `P5-03 entry lifecycle evidence`，不得称为 P5-02 completion、P5-02 补交或 D-118 修订。

### 5.1 Desktop

保留现有 JVM smoke（当前 schema 空库、shared use case `Created`/`NoChange`、逐币种平衡），新增临时文件数据库关闭后同一当前 schema 重开证据。测试不依赖产品固定路径，也不声称已实现历史版本自动升级。

### 5.2 Android

保留 `:android-app:assembleDebug` 自动构建证据；人工证据为安装、首次启动、应用私有当前 schema 数据库创建/打开，以及 Activity/进程退出后的同版本重开。占位 UI 不需要执行完整手工支出。当前环境无可用 emulator，因此该证据保持开放；开放状态不阻止本规格获批或 P5-03 spec-only 起草，但阻止 P5-03 代码实施 entry gate 闭合。

### 5.3 Startup Fail-Closed Follow-Up

批准后的 entry follow-up 在两端组合根增加稳定启动状态：`Starting`、`Ready`、`StartupError(LocalDatabaseUnavailable)`。`Ready` graph 必须只构造一次 §3.2 catalog snapshot，并把同一实例注入 write/options/read projection。driver/schema/create/open 任一异常必须被组合根捕获并进入 `StartupError`：

- 不构造或不暴露业务 UI、write use case、read use case 和 database handle。
- 显示可访问的稳定错误状态，仅提供 Retry 与 Exit；Retry 从 driver/graph 创建起点重新执行，Exit 关闭当前 Activity/进程窗口。
- 不创建半初始化正式交易，不把异常映射为领域 `Rejected`。

Desktop 用 driver/open failure injection 测试 error → retry → ready 与 error → exit；Android 至少有可测试 composition-root state transition，安装后的人工门核对错误页可达。此项当前未实现。

### 5.4 数据库事实边界

- 两端新库直接创建当前 schema；entry evidence 只覆盖同一当前 schema 文件重开。
- v1→当前的历史链继续由 `:ledger-data:verifyCommonMainLedgerDatabaseMigration` 验证；当前无已发布客户端存量库，产品组合根尚未声明历史版本自动升级策略。
- 本 follow-up 零 migration、零 schema；打开失败一律 fail closed。

## 6. Gates And Verification

### 6.1 本规格批准门

本文件从 `proposal` 翻转为 `approved` 只要求：文档独立评审通过、distinct verifier 确认权威/阶段/API/验收无矛盾、`project_docs` 与 `git diff --check` 通过、用户明确批准。Desktop 重开证据、Android emulator、startup follow-up 或 P5-03 实施规格都不是文档批准前置。

### 6.2 批准后 P5-03 spec-only

本规格 approved 后即可起草 P5-03 实施规格。该规格必须逐项承接 §3/§4，并把 read/options/request-ID/parser/orchestration 的精确 Kotlin 签名、SQLDelight 查询、snapshot-aware resolver 和 UI 状态机钉死；仍需独立评审与用户批准，且不得在 entry gate 前实施代码。

### 6.3 P5-03 代码实施 entry gate

- §5 Desktop current-schema 文件重开自动证据通过。
- §5 Android 安装/启动/私有库同版本重开人工证据通过。
- §5 startup fail-closed follow-up 的双端实现与聚焦测试通过。
- P5-03 实施规格 approved；所有未完成门如实登记。

### 6.4 P5-03 完成门

- §3 固定脚本、双平台 parity、catalog/options、requestId 生命周期、金额向量和可访问性验证通过。
- submission tests 通过：pre-submit zero-call failure → `InfrastructureFailure`；post-handoff exception + matching → `Recovered`；post-handoff + conflict → 稳定冲突；post-handoff + absent/unavailable → `UnknownCommit`。
- §4 read boundary 的 current-version、ledger isolation、snapshot-aware resolver 四态、正常余额符号、multi-currency 与 receipt lookup integration tests 通过。
- `Created`/`NoChange`/`Recovered` 后权威刷新；NoChange 不追加第二行；重启恢复正式结果。
- Android 与 Desktop 人工完成一次固定流程；不声称恢复 draft/unknown state。

### 6.5 自动验证基线

按 `docs/CONTRIBUTING.md` 资源约束运行适用聚焦测试，然后运行：

- `:desktop-app:jvmTest`、`:desktop-app:build`
- `:android-app:assembleDebug`
- `:ledger-application:jvmTest`、`:ledger-data:jvmTest`
- `:ledger-data:verifyCommonMainLedgerDatabaseMigration`、`:ledger-data:compileAndroidMain`
- `ktlintCheck`、Python 全量测试、`project_docs`

## 7. 开放风险与批准后登记

- Android emulator 当前不可用；entry 人工证据保持开放，不降低标准。
- read/options boundary、amount wrapper、request ID source、submission orchestration 和 startup states 均是 proposed addition，不是当前实现事实；实施必须留在批准后的对应批次。
- P5-03 产品只承诺固定单 CNY catalog；catalog persistence/management 与产品多账户配置保持后续非目标，不能从扩展测试 catalog 推导为已支持。
- 本文件获批后由主代理将状态改为 `approved`，并在决定登记和本地 checkpoint 记录批准与开放 evidence；本文件不自行宣告决定或完成。
