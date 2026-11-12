# P8-03 退款到账手工入口设计（复杂事项入口初门）

状态：**approved v1**（2026-11-12；proposal v1–v4 经三轮独立评审 + 一轮窄口径闭合复核；D-H 的两条规则读法由用户 2026-11-12 亲裁「两条都认」）
日期：2026-11-12。依据：`docs/PHASE8_ANDROID_PLAN.local.md:45`（每类独立小设计门、先核实运行时与规则、RG 核心存在不等于可接 UI、未开门类型保持未交付）与 `:58`（共通规则：读路径要有页界与 20k/50k 基线）；用户 2026-11-12 照准六条（A2 / 首门退款 / 允许一次加性迁移 / 扩 `EntryType` / 排除图片凭证 / F1 骨架 + F2 证据标注）。**这六条将由 `D-312` 在批准时一并登记**（v3 的 N-15：此前只存在于会话，权威链不可引用）。

**本门范围**：只开**退款到账**。

**未开门的五类**（全文档只用这一份清单）：余额调整、混合支付、分期（含预付分摊）、图片凭证、信用卡账单与分期管理费。它们在验收矩阵中**保持未交付**。

## 1. 起点（今日事实，逐条 file:line）

- **退款今天没有任何 ASSET 到账的产品路径**：导入的信用退款走 `ImportPaymentVariant.CREDIT_EXPENSE_REFUND`（`ImportSpine.kt:89`）→ `CreditFlowFormalFactory` → **`createCreditRefundReceipt`**（`CreditFlowFormalFactory.kt:144`；`RefundReceipt.kt:150`，**负债到账**变体），被 `ACCOUNTING_RULES.md:150/:158` 排除在本片外。**`createRefundReceipt` 的唯一生产调用者是 RG-07 store**（`SqlDelightRg07Store.kt:1105`）。
- **组合根不实例化 `Rg*` store**（三处零命中），但接了四个 formal factory（`App.kt:1353-1372`、`Main.kt:1038`），它们写共享 import/ordinary 表。
- **RG-07 的手工到账合同不可用于首笔退款**：`isCompleteAcceptedInput()`（`Rg07Operations.kt:668-694`）要求 `refundRelationId != null` + 三个来源时间非空 + 两个固定 mode 字符串；执行侧 `SqlDelightRg07Store.kt:289-298` 在 `refundRelationId == null` 时直接 `REFUND_RELATION_NOT_FOUND`。关系由状态迁移建立（`persistStatus:762-790`）。**v1 的机制已作废。**
- **RG-07 的候选查询看不到产品交易**：`selectRg07OriginalExpense`（`Ledger.sq:4843-4858`）内连 `rg07_posting_semantic`，该表只由 RG-07 store 写；产品路径的支出全部被判无资格。
- **竖井定位**：`DECISIONS.md:1329` 竖井是验证语料、逐个退役；`:1335`「共享链只被产品路径写；**竖井只被 jvmTest 写**」；`:1738` O-7「竖井不退役」。
- **交易类型的真相在 `canonical_kind`**：`ledger_transaction.kind` 是五值 legacy CHECK（`Ledger.sq:4`），`REFUND_RECEIPT`/`BALANCE_ADJUSTMENT*`/`STORED_VALUE_*`/`LEND`/`COLLECT`/`PREPAID_*` 都以 `'EXPENSE'` 写入、真值在 `canonical_kind`（`Ledger.sq:2304-2340`）；全仓读法 `COALESCE(canonical_kind, kind)`（76 处）。**任何按 `kind` 判「支出」的过滤器都会把退款、借贷、余额调整当支出。**
- **有效性只有一处定义**：`transaction_effective_state`（`Ledger.sq:10610-10622`）；作废事实在 `:10507-10524`。
- **手工录入面五类**：`EntryType`（`EntryDraft.kt:16-22`）；`validateEntryTypeSupported`（`:364-372`）**零生产调用者**（只有声明与一个测试），`EntryTypeNotSupported` 无生产者。
- **退款领域合同**：`createRefundReceipt(catalog, command, ids)`（`RefundReceipt.kt:28-32`），命令 6 字段（`:3-10`），校验金额>0、二级支出分类且 active、费用账户非真实、到账账户 `ASSET` 且 owned/real、同账本同币种、溢出与平衡（`:33-83`）。**不校验原交易、不校验上限、不限 CNY。**
- **账务规则** `ACCOUNTING_RULES.md:140-158`：原交易有效且已确认；关系用**稳定 ID** 并**按实际出现情况**保存各事件时间与**不可变历史**；明确确认到账时另建交易；继承精确二级分类；累计不超可退；超限整笔拒绝且**超额到账保持待明确分类**；未确认零效果（**手工请求可保持待确认**）；同请求重放返回原结果；首版限**同币种 CNY**、单分类原支出、自有真实资产到账。

## 2. 冻结的机制

### 2.1 产品自有关系表，一次加性迁移（v36 → v37）

不写 `rg07_*`（竖井只被 jvmTest 写）。v1 范围内没有第二个退款来源（唯一导入退款是负债到账，被规则排除）。

`36.sqm` 新增（按仓库迁移纪律：全列 NOT NULL、复合外键、显式确认标记、不可变触发器）：

```
manual_refund_receipt(
  ledger_id TEXT NOT NULL,
  relationship_id TEXT NOT NULL,                 -- 规则 :142 的稳定关系 ID
  request_id TEXT NOT NULL,                      -- 幂等键
  request_fingerprint TEXT NOT NULL,             -- 同 id 不同载荷 ⇒ 冲突
  original_transaction_id TEXT NOT NULL,
  refund_transaction_id TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  currency_code TEXT NOT NULL,
  currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
  destination_account_id TEXT NOT NULL,
  category_id TEXT NOT NULL,
  arrived_at TEXT NOT NULL,
  confirmed_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  confirmation_marker TEXT NOT NULL CHECK (confirmation_marker = 'explicit_manual_confirm'),
  PRIMARY KEY (ledger_id, request_id),
  UNIQUE (ledger_id, relationship_id),
  UNIQUE (ledger_id, refund_transaction_id),
  FOREIGN KEY (original_transaction_id, ledger_id) REFERENCES ledger_transaction(transaction_id, ledger_id),
  FOREIGN KEY (refund_transaction_id, ledger_id) REFERENCES ledger_transaction(transaction_id, ledger_id)
)
+ INDEX (ledger_id, original_transaction_id)
+ 不可变触发器（无 UPDATE 路径；DELETE 仅允许在测试拆卸时关闭外键）
```

**对 `:142` 的读法（明文登记，不静默收窄）**：规则要求「按实际出现情况分别保存」各事件时间——手工到账流程**只实际出现「到账」与「确认」两个事件**，因此一行即其完整历史；规则要求的不可变历史在本门由「单行不可变 + 触发器」满足，不需要状态机表。**申请/批准/处理方报告/来源观察**等事件只在接商户通知的片出现。此读法若被否，本门须改为「关系表 + 历史表」，成本随之上升。

### 2.2 入口形态：`EntryType` 增成员，修掉七处行为

`EntryType` 增 `REFUND_RECEIPT`（「退款到账」）。**P7-02 冻结的是类型集，本门扩写它**（`EntryDraft.kt:11-15` 注释同步更新，登记为扩写）。

| 站点 | 行为（冻结） |
| --- | --- |
| `EntryDraft.kt:246-352` `switchType` | 补分支；每个方向在实现批列成表 |
| `EntryDraft.kt:364-372` `validateEntryTypeSupported` | 补分支，**并在提交边界新增唯一调用点**（它今天零生产调用者；不新增调用点就删掉「有生产者」这句话） |
| `P503ConfirmationPresentation.kt:40-46`、`:70` | 补分支（编译期强制） |
| `P503Reducer.kt:1041-1063`「保存后再记一笔」 | 退款保存后**回到空支出草稿**：不带继承分类、不带原主账户、不带到账账户（见下「保留规则」） |
| `P503EditScreen.kt:244` 列表、`:221` 与 `:259` 两处 `else -> "支出"/"新增手工支出"` | 列表加入退款；**两处 `else` 改穷尽 `when`**（漏分支=编译错） |
| `ExternalNavigation.kt:305` `isRoutableNewEntryType` | **保持只路由收支**（冻结） |
| `P503Reducer.kt:516-519` `else -> UNROUTABLE_TARGET` | 保持不改（冻结） |

**保留规则（枚举，仅覆盖 `switchType` 的类型切换）**：金额、发生时间、备注保留；**到账账户不保留**；分类不保留也不可选（只读继承）。**「再记一笔」另有一条冻结**：`draft.toRetainedIntent(...)`（`P503App.kt:1827`、`:5453-5462`）把 `categoryId` 与 `primaryAccountId` 交给后续草稿，而 `P503Reducer.kt:1039-1043` 对该路径**清空金额与备注、并把 occurredAt 置为 now**——故「保留金额/发生时间/备注」不适用于该路径。要求：退款保存后走「再记一笔」→ **空支出草稿**（无继承分类、无原主账户、无到账账户、金额与备注为空、时间为当前）。该处 `when` 是表达式，**会强制编译错**（v4 曾误写「无编译错」）；但编译错只强制分支、不强制载荷，故 V-18 仍需保留。

**结果类型**：并入既有**两个**家族——`ManualEntrySubmissionResult`（`ManualEntrySubmission.kt:10-30`，各类型自带的 `UnknownCommit`）与 `ManualEntryCommitResolution`（`:36-56`，即「权威回读」面，`P503Reducer.kt:4098-4102` 分派、`P503App.kt:1965-2070` 构建），两者都增退款分支；`ManualEntrySubmission.kt:154-206` 的 `ManualEntrySaveInput` 分派与 `P503App.kt:5474-5481` 的 `runtimeNotReadySubmission` 是另外两处编译强制站点。共四处逐点补齐。

### 2.3 原交易选择器（禁裸 ID；用 canonical kind）

候选条件（全部在一条具名查询里）：

1. 同账本；
2. `transaction_effective_state.is_effective = 1`；
3. `COALESCE(tx.canonical_kind, tx.kind) = 'EXPENSE'`（**不是** `tx.kind`——后者会把 LEND/COLLECT/退款/余额调整都算成支出）；
4. 当前有效版本有**恰好一个**正额支出腿，且其账户 = 该分类的费用账户；
5. 原交易的二级支出分类唯一且 active；
6. `currency_code = 'CNY'`（规则 `:158` 的首版限制）；
7. 剩余可退 > 0。

展示：日期、二级分类名、**支出腿金额**（即上限基数，不是「原交易金额」）、已退合计、剩余可退。有界分页。空候选→「暂无可退交易」空态；读失败→类型化失败，**绝不呈现为空**。

### 2.4 分类只读继承

用户不选分类；提交时由服务端按原交易**重新解析**（不信 UI）；分类 = 条件 4 的那个二级支出分类（`:146`）。

### 2.5 上限（含有效性）与提交时重校验

剩余 = **支出腿金额** − Σ(同账本、同原交易、同币种、**且退款交易当前有效**的 `manual_refund_receipt.amount_minor`)。求和**必须 join `transaction_effective_state`**（规则 `:148` 的「有效关联退款」）；退款被作废后上限自动释放。

超限**整笔拒绝**（`:148`），不截断。

**提交时重校验（同一事务内）**：原交易仍满足 §2.3 的 1/3/4/5/6 条且仍有效、仍有剩余；上限按当前有效退款重算。任一不成立→类型化拒绝，零写入。

**超额到账读法**：`:148` 的「保持待明确分类」针对**已发生的到账事实**（导入/账单）；本门是用户手工断言，故整笔拒绝并让用户改正金额。将来接导入到账时必须按规则保留待明确分类。

**「手工请求保持待确认」的读法（关 N-16，明文）**：`:154` 的句子描述的是**存在请求对象时**的处置。v1 不建请求对象：用户未点确认前零写入，因此「保持待确认并产生零效果」在语义上**成立**（没有请求、没有效果）；把「待确认请求」做成持久对象留待接商户通知的片。**此读法与 `:148` 的读法同级登记，供评审否掉。**

### 2.6 明确确认、幂等与 Unknown

确认页点确认前零写入。确认事务内：有效性重校验 → 上限重算 → `createRefundReceipt` → 写关系行。

- **幂等**：`PRIMARY KEY (ledger_id, request_id)`；`request_fingerprint = sha256(original | amount | currency | destination | arrived_at)`。同 id 同指纹→返回原结果；同 id **不同**指纹→`REFUND_REQUEST_CONFLICT`，零写入。
- **Unknown**：写入结果不确定（**提交后读取失败**）→ 返回 `UnknownCommit`。**进程中断不在本门的 Unknown 覆盖内**：草稿与 requestId 只存在于会话内存（`P503AppState.kt:1105-1106`、`P503Reducer.kt:2518`），进程被杀后没有 id 可复用；重开后按关系行与退款交易的权威回读判定，不重试写入（登记为残余 9），UI 呈现「结果未知，请重开确认」，**不重试写入**；重开按权威回读（关系行是否存在 + 退款交易是否有效）。**Unknown 后的重试必须复用同一 `request_id`**（换新 id 会绕过幂等）。
- **拒绝码闭集**（关 N-06）：`REFUND_CATEGORY_INELIGIBLE`（分类不合格/唯一二级分类缺失）、`REFUND_ACCOUNT_INELIGIBLE`（到账账户不合格）、`ORIGINAL_NOT_REFUNDABLE`（提交时原交易不满足 §2.3）、`ORIGINAL_NO_LONGER_REFUNDABLE`（取候选后被作废/改版）、`REFUND_EXCEEDS_STRUCTURAL_CAP`（金额 > 原支出的**支出腿金额**——结构性超限，用户必须改金额）、`REFUND_EXCEEDS_REMAINING`（金额 ≤ 支出腿金额 但 > **当前剩余**——已退部分占用了额度，用户可改金额或作废既有退款）。**两者的判别只用一个服务端可判的阈值**（支出腿金额），不使用「显示时快照」，因此请求与指纹都不需要携带 cap 快照、`REFUND_REQUEST_CONFLICT`、`REFUND_STORE_UNAVAILABLE`。

### 2.7 失败语义

所有拒绝走 §2.6 的闭集；数据库异常不得以未类型化异常逃逸（映射到 `REFUND_STORE_UNAVAILABLE` 或 `UnknownCommit`）。

### 2.8 时间字段

`TransactionTimes`：`occurredAt = statisticsAt = 用户输入的到账时刻`；`createdAt = 确认瞬时`。关系行另记 `arrived_at` / `confirmed_at` / `created_at`。

## 3. 裁决记录

- **D-A = A2**／**D-B = 退款**（理由按 §1 更正：不是「数据已在库里」，而是规则边界最窄且今天无任何 ASSET 退款路径）／**D-C = 允许一次加性迁移**（本门使用：v36→v37）／**D-D = D1**（扩 `EntryType`，修穷尽性）／**D-E = 排除图片凭证**／**D-F = F1 + F2**：用户 2026-11-12 照准，**由 `D-312` 登记**。
- **D-G = 产品自有关系表 + 一次加性迁移**（取代 v1 作废的 RG-07 复用）。
- **D-H = `:142` 的历史收窄读法**（§2.1 末段）与 **`:154` 的待确认读法**（§2.5 末段）：两条都是对规则句子的**显式读法**，**已由用户 2026-11-12 亲裁「两条都认」**（历史收窄：手工流程只出现到账+确认两个事件，一行不可变即其完整历史；待确认读法：v1 不建请求对象，未确认即零写入，语义上成立）。
- **D-I = 类型集扩写**：`EntryType` 从五类扩到六类，P7-02 `EntryDraft.kt:11-15` 注释同步。

## 4. 读写契约

| 用途 | 形态 | 说明 |
| --- | --- | --- |
| 提交手工退款 | 新应用用例 `SubmitManualRefundReceipt` | 事务内：有效性重校验 + 上限重算 + `createRefundReceipt` + 写关系行；返回并入 `ManualEntrySubmissionResult`（含 `UnknownCommit`） |
| 可退原交易候选 | 新具名查询 + 读端口 | §2.3 七条件 + 有界分页 |
| 剩余可退 | 新具名查询（走 §2.1 索引 + 有效性 join） | `SUM(amount_minor)` by `(ledger_id, original_transaction_id, currency)` |
| 迁移 | `36.sqm`（v37） | 一张表 + 一个索引 + 不可变触发器；migration verify + 夹具种子 |
| **既有守卫扩展** | ①`catalogReferencedManualCategoryIds` 或 `…ConfirmationCategoryIds` 加入本表的 `category_id`；②账户引用探针加入 `destination_account_id`；③**作废守卫**：DP-13 的「有效关联退款」探针目前只读导入路径（`Ledger.sq:10931-10949`），必须加入本表，否则被手工退款的原交易仍可被作废 | 三条都要在实现批落地，缺一则上限与 `:142` 的「有效原支出」前提失效 |
| 入口面 | `app-ui`：`EntryType.REFUND_RECEIPT` + draft + 校验 + 确认页 | 七处行为见 §2.2 |
| 组合根 | 两根本接线 + `LedgerLeaseScope` var + `LedgerSurfaces` 探针 | 沿用 P8-01a/P8-02 立法 |
| 详情呈现 | 既有详情页增加退款关系只读行 | 不改时间线语义 |

## 5. 验收向量

| # | 向量 | 判据 | 证据类型 |
| --- | --- | --- | --- |
| V-1 | 入口可达 | 类型单选出现「退款到账」；选中后出现选择器；删任一 `when` 分支即编译错 | 纯呈现测试 + 设备人读 |
| V-2 | 候选正确 | §2.3 七条件；**退款/LEND/COLLECT/余额调整即使 `kind='EXPENSE'` 也被排除**（canonical kind + 正额支出腿） | JVM 真库测试 |
| V-3 | 分类继承 | 展示与服务端解析恒等；原交易被版本替代后按新分类；UI 无分类选择器 | JVM 真库测试 + 纯呈现测试 |
| V-4 | 上限与有效性 | 剩余 = 支出腿金额 − 有效退款合计；超额整笔拒绝；**退款被作废后上限释放**；并发两笔串行 | JVM 真库测试 |
| V-5 | 无确认即零效果 | 草稿态/取消后：关系表 0 行、无正式交易、余额/报表/对账不变 | JVM 真库测试 |
| V-6 | 正式效果 | 退款交易与原支出分开：到账资产 +、原费用账户 −；原交易行/日期/版本/对账状态不变 | JVM 真库测试 |
| V-7 | 幂等与冲突 | 同 id 同指纹重放返回原结果；同 id 不同指纹 → `REFUND_REQUEST_CONFLICT` 且零写入；`UNIQUE(ledger_id, refund_transaction_id)` 兜底 | JVM 真库测试 |
| V-8 | 报表口径 | 原期间不变；到账期间消费 −A、退款现金流入 +A、普通收入 0（按 `:144` 算术例子，读既有账本读面断言，不新建报表路径） | JVM 真库测试 |
| V-9 | 重开与不可变 | 重开后读回关系行；**不可变由触发器保证**（试写 UPDATE 被拒） | JVM 真库测试 |
| V-10 | 未开门类型 | 五类未开门类型在类型列表与验收矩阵中均不出现 | 纯呈现测试 + 文档 |
| V-11 | 迁移 | v36→v37：既有数据不变、新表为空、约束与触发器可建 | migration verify + JVM 真库 |
| V-12 | 提交时重校验 | 取候选后作废 → `ORIGINAL_NO_LONGER_REFUNDABLE` 且零写入 | JVM 真库测试 |
| V-13 | 空候选与读失败 | 无候选→空态；读失败→类型化失败，不呈现为空 | 纯呈现测试 + JVM 真库 |
| V-14 | 1.3× 与 TalkBack | 入口、选择器、确认页在 1.3× 下不截断不重叠；语义名可读 | 字号机一屏 + 人读图 |
| V-15 | 候选查询基线 | 20k/50k 合成库的首屏与剩余合计耗时登记为基线（不设门槛） | JVM 读数 |
| V-16 | 退款腿自己对账 | 退款交易到账腿初始为待对账，可独立链接证据，原付款腿的对账状态不被重置（`:152`） | JVM 真库测试 |
| V-17 | Unknown | 模拟「提交后读取失败」→ 返回 `UnknownCommit`、不重试写入、重开按权威回读；同 `request_id` 重试幂等 | JVM 真库测试 |
| V-18 | 「再记一笔」不继承 | 退款保存后走「再记一笔」→ 空支出草稿：无继承分类、无原主账户、无到账账户 | 纯 reducer 测试 |
| V-19 | 作废守卫 | 有有效手工退款的原交易**不可被作废**（既有作废守卫已扩） | JVM 真库测试 |

## 6. 范围边界

不做：未开门的五类（见开头清单）；跨币种（含非 CNY）；负债或商户储值到账；手续费退款；多分类或合并购买的原交易；拒付/超额补偿；**部分到账**；分阶段付款的取消或退款；账目修正（RG-12）；「待确认请求」持久对象；超额到账的待明确分类处置。不写 `rg07_*`；不改共享正式链语义。

**导入面「原交易裸 ID」的收敛**不归本门、也不归 `P8-04`（P8-04 是模板导入，不拥有银行导入审核面），登记为独立小片候选。

## 7. 验证与残余

**验证**：任务分支 + PR，四门全绿后 squash；高风险管理拓扑；设备腿由字号机 profile 增一屏。

**登记残余**：
1. 候选查询首次标定只登记基线（V-15）。
2. 若后续引入「导入产生的 ASSET 到账退款」，上限与作废守卫都要改双来源（§2.1/§4 已明文）。
3. 导入面裸 ID 与选择器两套交互，收敛归独立小片。
4. `validateEntryTypeSupported` 今天零生产调用者；本门要么新增唯一调用点，要么删掉「有生产者」的表述（§2.2 已写）。
5. 关系状态机与商户通知不在 v1（D-H 的收窄读法）。
6. 报表 V-8 只证算术，不证图形化（归 `P8-06`）。
7. Unknown 只覆盖单进程写入不确定，不覆盖跨设备并发。
8. `:148` 与 `:154` 的两条读法（§2.5 末段）若被评审否掉，本门范围与迁移量都要改。
9. 进程被杀后的 Unknown 覆盖缺 id 复用（草稿与 requestId 只在会话内存）；重开只做权威回读，不重试写入。

## 8. 实施登记（i2 期；不改写 §1–§7 的批准原文）

本节只登记实施期确定的事实与读法，供后续评审与后续批次引用。

- **候选行的分类展示名（i2a）**：`selectManualRefundExpenseCandidates` 增加只读展示投影 `category_name`，取值是该分类在 `catalog_name_history` 的 CURRENT 名，以 `ORDER BY version_number DESC LIMIT 1` 的标量子查询单值化（畸形目录出现两条 CURRENT 名行也不会放大候选行）。**没有 CURRENT 名行时回落显示分类 id**，该候选仍可选；这是 §2.3「展示二级分类名」与「候选集只由七条件决定」的共同结果，登记为显式读法。
- **候选排序按 `occurred_at` 文本（i2a）**：产品写点的 `occurred_at` 一律是 ISO-8601 文本（如 `SqlDelightConfirmedManualIncomeCommitPort.kt:153`），文本序与时间序一致；RG-07 竖井是唯一写裸字符串的路径且只被 jvmTest 写。将来承接来源文本时间时须另立读法。
- **V-15 读数落点（i2a）**：20k/50k 的 JVM 读数以 `P803-V15 ` 前缀打印，由 `tools/ci/scale-readings-report.py` 收进分片 job 的 summary 与日志，run 结束后仍可引用；数值只在检查点与本条登记，不设门槛。
- **i2a 登记残余**：①V-15 首页断言依赖夹具「全行同 `occurred_at`、按交易 id 升序」的隐式事实；②SQL 注释声称的「两条 CURRENT 名行也不放大」只有单值半边有测试（回退半边有、重复半边无向量）；③V-15 用既有 INCOME 交易充当退款交易，只为聚合与有效性的成本形状，不声称产品退款语义；④分类名回退的**用户可见文案**（内部 id）尚无产品裁决——若将来要求隐藏该候选，须改条件 5 并重测。
- **退款写入面（i2b）**：手工退款**不写备注、不写标签/商家**——域命令 `RefundReceiptCommand` 只有 6 字段，应用请求与指纹都不含它们。因此编辑屏与确认页都不呈现备注与标注选择器（`switchType` 仍按 §2.2 保留草稿载荷）；确认页固定四行（原交易 / 到账账户 / 继承分类 / 退款金额）。若将来要让退款携带备注或标注，须改域命令、请求与指纹口径，属另一次规格修订。
- **组合根形态（i2b）**：退款三面（候选读、提交用例、Unknown 回读）随图一次性构造，经 `P503LedgerFacade` 可空字段暴露，`LedgerSurfaces.manualRefundEntry` 为三者皆非空的纯 wiring 探针（提交探针读 fold 自身的 `hasRefundDelegate`，不读兄弟字段）；未新增 `LedgerLeaseScope` var（§4 的「var」按「随图生命周期」读法登记）。`SqlDelightManualRefundReceiptStore` 增加无 driver 的公开构造路径（Android handle 拥有 driver，重跑 `configureSqliteConnection` 在 Android 不成立）。
- **结果与失败语义（i2b）**：退款家族增第 6 态 `InfrastructureFailure`（未就绪运行时，与其余五类一致）；`Replayed` 落 `NoChange`（房屋回放形态）；提交端口的 `Unavailable` 落 `UnknownCommit`（§2.6 的「写入结果不确定」），其根因——store 无法区分「无行」与「回读失败」——登记为 **i1 债务**。提交侧 `REFUND_REQUEST_CONFLICT` 与回读侧 `SnapshotConflict` 同落 `RequestIdentityConflict`；拒绝码以 `ManualRefundRejectionCode` 承载（不预渲染字符串）。
- **入口就绪门（i2b）**：类型单选仅在 `LedgerSurfaces.manualRefundEntry` 为真时出现退款项；未接线的根不渲染死 affordance。类型列表由 `EntryType.entries` 派生，标签/标题/确认行/「再记一笔」/未就绪落点均为穷尽 `when`。
- **证据口径（i2b）**：V-3 的「UI 无分类选择器」由三条纯层断言（草稿 `categoryId` 恒 null、校验无分类门、确认页无费用分类行但有「继承分类」只读行）加屏结构代码审阅承载；V-13 的呈现半由类型化四态与「空≠失败、失败可重试」断言承载；V-1 的「删任一 when 分支即编译错」由穷尽 `when` 集合保证。
- **实施期事实纠正（i2b）**：i1 的向量「目录缺失 → `REFUND_STORE_UNAVAILABLE`」保留（**确定失败**，发生在任何写入之前）；新增应用层 fold 测试覆盖**不确定提交**（`Unavailable → UnknownCommit`）。两者不矛盾：前者读不到目录即拒绝，后者写入结果不明。
- **i2b 登记残余**：①拒绝页「原交易」在候选页丢失时回落裸 id（防御分支，正常流程不达）；②`validateEntryTypeSupported` 现为永不拒绝的未来防漂移闸门（提交边界唯一调用点在 reducer 的 Continue）；③`REFUND_STORE_UNAVAILABLE` 的确定失败文案仍可达（目录权威缺失路径）；④1.3× 下六类型单选的挤压留给 i2c 的 V-14 人读；⑤Unknown 的读回仍按单请求指纹比对（V-17 已由 JVM 覆盖）。
- **V-14 设备腿（i2c）**：字号机新增 `profile=refund`——同一 prepared v37 seed 库（第三个 owned 资产账户 + 一笔真实手工支出，候选资格按 §2.3 条件 5 用默认目录的 `早餐` 分支），1.3× 下拍「退款入口（类型单选 + 候选行）」与「确认页」两屏。确认页 leg 必须填「发生时间」（首页 FAB 的草稿 `occurredAt` 为空，Continue 门要求它）；入口屏的「费用分类」负标记证明类型真的切到退款表单；确认页 marker 用「早餐」证明候选解析成功，负标记用入口标题证明导航落地。gate 只判 `marker present`（滚动表单的屏外节点仍在树中，不构成可见性断言）；「不截断、不重叠、语义名可读」由人读图与 dump 承载。
- **i2c 登记残余**：①每屏只拍一张、不滚动到底，1.3× 的「不截断」只覆盖首屏视口（人读半）；②`_focused_text_field`/`find_field_center` 的口径是「任一字段获焦」而非「目标字段获焦」，误焦由后续 marker 门兜底；③`ime_is_shown` 在 manager 与 IME 段短暂矛盾时取最后一次出现（错判方向都会大声失败，不会静默绿）；④`scroll_until` 只证 label 中心在窗口内，`TapStep` 自身不复核可达性。
