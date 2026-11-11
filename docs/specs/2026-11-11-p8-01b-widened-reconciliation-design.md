# P8-01b 对账写面扩面与读面统一设计（零迁移；先修两个既有缺陷）

状态：approved v4（2026-11-11 用户批准 D1–D5 全选 A；据本规格实施）

日期：2026-11-11。前置：`P8-01a` 四批已合入（i1 `51c3c432` / i2 `f08ed5aa` / i3a `f830e08c` / i3b `aea9de00`）。

**本规格性质**：D2=A 时零 schema 变更（停留 v36）、零迁移、零账务语义变更。对账写面只写 `evidence_link` 与 `posting_reconciliation` 两张面；余额、报表、分录、版本的既有派生路径不动。本规格**不含** UI 版式改动；用户可见变化有两类，都单列为待批准决定：**可确认腿集**（D3）与**文案/释义**（D1、D5）。

**与既有授权的关系**：`D-308` 十条裁决与 P8-01a 规格继续有效，**除下列三处被本规格实质修订**——它们都写进 D2 的修订请求，本规格不自行取消：①`D-308` 第 1 条登记的「v36→v37 回填」（§1.5 证明不应做）；②同条登记的「`selectP408PostingIntegrity` 放宽」（§2.6 改为新具名查询、原查询冻结给修正面）；③`D-308` 第 3 条与 P8-01a 规格 `:140` 的「确认面仅转账腿」及「届时对读面的统一」（§2.1/§2.8 改为队列/汇总统一、报告 oracle-only）。

**关联决定**：`D-308`、`D-103`、`D-112`/`D-113`、`D-145`、`D-156`、`ACCOUNTING_RULES.md:188/241/259-263`、`GOLDEN_SCHEMA.md:466-468`。

---

## 1. 起点

### 1.1 缺陷 A（P1，既有）：带新 id 的请求永远无法确认「已有行」的腿

`confirmLink` 在事务内先 `INSERT … ON CONFLICT (ledger_id, posting_id) DO NOTHING`（`Ledger.sq:8913-8915`），再读回该行并与**请求携带的** `reconciliationId` 比对，不等即 `P408_RECONCILIATION_ID_MISMATCH`（`SqlDelightP408ReconciliationStore.kt:267-279`）。应用层**无 identity 的首次确认**每次铸新 UUIDv7（`ConfirmReconciliationLink.kt:60-66`；发号源 `android-app/.../App.kt:1139`、`desktop-app/.../Main.kt:805`；i3a 的确认路径不携带 identity，`P503App.kt:1695-1701`）——所以**生产路径上，任何在确认前已有行的腿都确认不了**。而 `v23` 迁移为**所有**当前 `ACCOUNT_TRANSFER` 分录播种了行（`22.sqm:143-152`，新库镜像 `Ledger.sq:9125-9134`，无账户过滤），这正是 P8-01a §1 认定「唯一可达待对账」的那批腿。

**既有覆盖（本片要反转的契约）**：`P408ReconciliationStoreTest.kt:250-280` 预置 PENDING 行并断言该码；`LedgerMigrationV20ToV36Test.kt:167-177` 用**复用行 id** 的请求把种子腿确认成功（sequence=2）。两者合起来说明：机制本身没问题，**只有「新 id 请求」这一支是死的**——而生产只走这一支。该码在全仓只有这一处测试断言；另一个生产者是修正面（`SqlDelightCorrectionStore.kt:236-238`），其生产调用刻意复用既有行 id（`SqlDelightTransactionCorrectionCommitPort.kt:359-365`），不受影响。

**判据**：种子行的腿 + 一次生产形态的确认（新 id）→ 今天 `P408_RECONCILIATION_ID_MISMATCH`；本片要求 `Linked`，状态历史从既有序号继续。

### 1.2 缺陷 B（P1，既有）：写面谓词没有「有效交易」门

`selectP408PostingIntegrity`（`Ledger.sq:9040-9052`）join 当前版本与 `ledger_transaction`，但**没有** `transaction_effective_state`（对照队列 `:11725-11728` 与事实读 `:12011-12014`）。作废交易的腿因此可以被确认。**今天的可达路径是 TOCTOU**：读到候选 → 交易被作废 → 确认落地；新读不会给出作废腿的候选。扩面会把这个洞从「转账偶发」放大到「全类型」。

**判据**：TOCTOU 场景下确认 → `P408_POSTING_NOT_ELIGIBLE`，零链接、零状态行。

### 1.3 读/写面口径（六处）

| 面 | kind 过滤 | rg03 覆盖 | 复合标记 | 作废 | 缺行 |
| --- | --- | --- | --- | --- | --- |
| 队列（i1） | 无 | **不消费** | INNER | 排除 | `PENDING`（`:11673` 的 COALESCE） |
| 汇总（i1） | 无 | 不消费 | INNER | 排除 | `PENDING` |
| 详情（P7-03） | 无 | **false 胜** | LEFT + 只增 OR | 外部先判 | 合格读 `PENDING`、不合格读 `null`（`QueryTransactionDetail.kt:118-123`） |
| P408 报告 | **`ACCOUNT_TRANSFER`** | 无 | 无 | 无 | `PENDING` |
| 审计（i3a） | 无 | 无 | 无 | 无 | typed absence |
| 写面谓词 | **`ACCOUNT_TRANSFER`** | 无 | 无 | **无** | 不适用 |

结论：三面**不会对同一腿给出两种互斥状态**；分歧在**腿集**。两例：**费用腿** = 队列无、详情无资格、报告 `PENDING`；**作废转账腿** = 报告仍描述、队列/详情不描述。

### 1.4 职责枚举与方向规则

枚举只有两员（`P408Reconciliation.kt:4-9`），方向规则唯一实现 `forDirectionOrNull`（`:22-27`），方向取符号（`SqlDelightP801AccountReconciliationReadAdapter.kt:266-271`）。

- **ASSET 腿**：支出/转出 = 付款侧，收入/退款/转入 = 到账侧，语义成立；但 `OPENING_BALANCE`、`BALANCE_ADJUSTMENT(_REVERSAL)`、`STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT` 这类**非资金流**分录（`FormalLedger.kt:41-58`，由 `SqlDelightRg09Store.kt:946-956` 写成正式交易）落在真实账户上，扩面后同样可确认，「钱出去/进来」是假的。
- **LIABILITY 腿**：**两个方向都不精确**。负向（信用消费，`CreditFlowFormalFactory.kt:25`）是欠款增加而非付款；正向（还款本金）是欠款减少而非到账。
- 该列被三处 `CHECK` 锁死两值（`Ledger.sq:8409/8449/8568`）：加第三 token = 重建三张表 + 一次真迁移。

### 1.5 回填不需要，且与金标契约相反

不需要：读面无行即 `PENDING`（队列 `:11673`、汇总 `:11810`、报告 `:9088`）；详情有派生（`LedgerEntryReadModels.kt:133-138`）；`confirmLink` 自建行并在 history 空时自补 `PENDING` seq1（`267-312`）；i2 与设备种子都在无行腿上跑通。

不应做：`GOLDEN_SCHEMA.md:466-468` 冻结「不合格腿没有对账记录，`not_applicable` 是派生显示值，不是存储事实」。**v23 种子已经违反了这条**——它无账户过滤地为所有转账分录播种，连转账手续费腿（不合格）也写入了对账事实，详情面正是靠 `hasReconciliationRow`（`LedgerEntryReadModels.kt:137`）把这些非标记腿判成合格。回填会把这个既有偏差按数量级放大；修复方向是读面派生，不是继续落库。

---

## 2. 本片冻结的机制（用户可见效果见 D1/D3/D5）

### 2.1 对账写面谓词：队列成员规则

另立一条具名查询供对账写面使用，腿集 = **当前版本 + `transaction_effective_state` + §2.1 复合标记**（`owned_by_user = 1 AND real_account = 1 AND kind IN ('ASSET','LIABILITY')`）。相对 `selectP408PostingIntegrity`：**删** `COALESCE(tx.canonical_kind, tx.kind) = 'ACCOUNT_TRANSFER'`（`:9051`），**加** effective 门与 `catalog_account` 内连三项。

**不得加**：`p.account_id = :account_id`（写面按 posting_id，账户相等已由 store 校验 `:178`）、时间窗口、键集/`LIMIT`、`active_link_count` 子查询、`GROUP BY`/`sum`、报告的 active-link LEFT JOIN、`catalog_account.active = 1`（`:11635-11638` 明确不参与）。**rg03 覆盖不进写面**（见 §2.7，含其反向不对称的登记）。

### 2.2 入口 B 的事实读：只删 kind 谓词，保留标志

该读刻意「FLAGS, NOT FILTERS」（`Ledger.sq:11970-11978`），把旧版本与非标记账户的腿**也返回**，由 matcher 在 `P408Matcher.kt:135` 过滤 `current && eligibleRealAccount`（另有展示消费者 `P503ReconciliationScreen.kt:450` 读 `eligibleRealAccount`，但它不过滤）。因此**只删** `:12020` 的 kind 谓词；LEFT JOIN、两个标志与账户口径全部保留，并同步订正**四处** KDoc（`Ledger.sq:11965-11968`、`SqlDelightP801AccountReconciliationReadAdapter.kt:68-69`、`P801AccountReconciliationReadPort.kt:310-313` 与 `:376-385`，后者同写「Non-transfer legs are NOT offered」）以及候选门的 KDoc（`ProposeReconciliationCandidates.kt:45-49`，随 §2.3 删门失效）。

**测试反转（点名）**：`SqlDelightP801ReconciliationCandidatesTest.kt:274-293` 今天断言「只提议转账腿、facts 只有 `posting-out`」；删谓词后同额同日的支出腿也会成为候选，该例翻成两条候选 / `AMBIGUOUS`。

### 2.3 候选/提案门：删掉非转账锚腿的类型化拒绝

`ProposeReconciliationCandidates.kt:55-56` 在任何读之前用 `LEG_NOT_TRANSFER_CONFIRMABLE` 拒绝非转账锚腿，这是 V-12（及 V-15 的非转账腿来源）真正的入口门。本片**只冻结「删门」**；枚举成员与相关测试期望（`SqlDelightP801ReconciliationCandidatesTest.kt:260-272`）的去留是 D5。

### 2.4 缺陷 A 的修法：既有行是权威

删掉 `SqlDelightP408ReconciliationStore.kt:277-279` 的 id 比对。既有行的 `reconciliation_id` 是权威，请求携带的 id 只在**新建**时使用；回执、状态历史与审计今天已经一律用行的 id（`:287-311`、`:324`、`:332`，审计 `Ledger.sq:12104`），指纹不含 `reconciliationId`（`P408Reconciliation.kt:112-143`），重放按 `request_id` + 指纹（`:362-388`）。最小 diff 就是删这三行；**不加**额外防御断言（同一事务内同键插后读的断言恒真）。

**测试反转（点名）**：`P408ReconciliationStoreTest.kt:250-280` 的契约由「拒绝」改为「既有行权威 → `Linked`」；`LedgerMigrationV20ToV36Test.kt:167-177` 的**断言**不变，但夹具须补一行 `catalog_account`：该测试从 v20 迁到 v36，而 `catalog_account` 自 `27.sqm` 才存在，行必须在迁移到 ≥27 之后插入，否则新谓词的复合标记内连会把种子腿判成不合格。同一原因波及 P408/P409 夹具族 10 个类（§7 残余）。

### 2.5 缺陷 B 的修法：补有效交易门

§2.1 的新谓词带 `transaction_effective_state`；作废交易的腿一律 `P408_POSTING_NOT_ELIGIBLE`，零写入。

### 2.6 修正写面不随扩面（也防收紧）

`selectP408PostingIntegrity` 同时被 P408 修正/后继路径使用（`SqlDelightCorrectionStore.kt:135`）。本片**冻结为**：对账写面用 §2.1 的新具名查询；`selectP408PostingIntegrity` **原样保留**给修正面。理由两条：共享查询会放宽修正面（kind），也会**收紧**修正面（effective 门，今天修正可作用于作废交易的腿）。「布尔参数化」被否决——把安全谓词的取值留给调用点，正是本节要防的静默变化。

### 2.7 rg03 覆盖只进读面，且在 page CTE 内

队列与汇总消费 `rg03_transfer_posting_semantic.reconciliation_eligible = 0`（false 覆盖）。该表主键 `(ledger_id, posting_id)`（`:853-862`），LEFT JOIN 是 1:1，不放大汇总的 `sum()/count(*)`；但 join 必须放在 **page CTE 内、键集与 `LIMIT` 之前**，否则会截短页并破坏游标全序。事实读不消费该覆盖（它按设计返回全部腿，由 matcher 过滤），写面同样不消费。

**反向不对称（登记）**：写面能确认一条**队列与汇总看不到**的 `rg03=false` 腿（事实读仍会返回它，因为该读不消费覆盖）。今天产品不可达（该覆盖的 `CHECK` 只允许 `TRANSFER_FEE` + `category_id` 行，而手续费腿在费用账户上、被复合标记排除），但口径上必须登记；同时修订 `SqlDelightP801AccountReconciliationReadAdapter.kt:71-77` 的既有残余注（原文要求「P8-01b 必须覆盖全部五个读面」，本片改为：队列与汇总采纳、事实读与候选读不采纳、报告 oracle-only）。

### 2.8 报告读面：登记 oracle-only，不对齐

`selectP408ReconciliationReport` 零生产消费者；本片不改它的腿集，登记为 oracle-only。V-17 钉住它**仍然**排除非转账腿。

### 2.9 本片的证据形态

- JVM 真库测试：V-12…V-19。
- 设备证据：由 V-10 的 instrumented 链路证明承担（本批 `p8-01a-v10`：生产组合根 + 真 Android SQLite，四个用例覆盖队列/绑定/候选/确认/审计与滚动槽）。该批**正在 CI 上跑**，绿后作为 P8-01a 的 V-10 证据；P8-01b 扩面后的腿型证据由 V-12 的 JVM 测试 + 同一 instrumented 用例（扩面后重跑）承担。
- 字号机静态屏**在 D4=A 时不在本片改动**：seeded 档的两个 gate 标记（`流水 · 共 1 笔`、`未核对 1 条`）与种子回读都依赖「恰一笔转账、恰一条未核对腿」，加一条非转账腿会同时打破它们与 `P801FontscaleSeedWriterTest.kt:183/202/227/243`（D4=B 则需一并改种子、标记与回读）。

---

## 3. 决定的裁决结果（D1–D5 全选 A，2026-11-11 用户批准）

**D1 职责的呈现与释义。** 现状：DB 存两个 token，审计行**原样印** storage token（`P503ReconciliationPresentation.kt:158`，其规则 KDoc 在 `:157`），i3a 的 copy 规则是「持久 token 不翻译」（`P503ReconciliationPresentation.kt:82-87`，该段是 human_decision token 的规则），测试 `P503ReconciliationPresentationTest.kt:393` 钉住 `real_account_posting`。选项：
- **A（推荐）**：不改任何用户可见文案。两个 token 的**释义**在决定条目里写明为「付款侧/到账侧」，并登记「负债两向与非资金流分录的命名是近似」。零代码、零测试反转。
- **B**：审计的职责一行改印中文（付款侧/到账侧），DB 不变。需要改 i3a 的 copy 规则与至少一条测试，且与同屏其他原样 token 不一致。
- **C**：加第三 token（`liability_posting` 之类）。需要重建三张表 + 一次真 v36→v37 迁移 + 高风险评审；本规格明确不含。

**裁决：A（2026-11-11 用户批准）。**

**D2 修订 `D-308` 与 P8-01a 规格的三处登记。** 选项：
- **A（推荐）**：一次修订请求包含三项——①取消 v36→v37 回填（§1.5）；②把「放宽 `selectP408PostingIntegrity`」改为「新具名查询 + 原查询冻结给修正面」（§2.6）；③把「届时对读面的统一」收窄为「队列/汇总统一、报告登记 oracle-only」（§2.8），并反转 `D-308` 第 3 条的「确认面仅转账腿」（§2.1/§2.3）。
- **B**：仍做回填并保留原登记（需要先落 §2.4，且会把不合格腿的对账事实继续放大；同时仍要修 ②③ 两处，否则规格无法实现）。

**裁决：A（2026-11-11 用户批准）。**

**D3 接受「歧义增加」。** 扩面后，同账户同额同日的一条支出腿与一条转账腿会互为候选，原本唯一命中的转账腿可能变 `AMBIGUOUS`（今天 `SqlDelightP801ReconciliationCandidatesTest.kt:274-293` 钉的正是「只提议转账腿」）。这是**用户可见的回退**：从「自动唯一」变成「要求明确选择」。选项：A（推荐）接受，`AMBIGUOUS` 强制人工选择且零写入；B 只在同 kind 内匹配——入口 B 的证据没有 kind，无法在应用层按 kind 过滤，若要在 matcher 内加 kind 就改了冻结契约，不可行；C 不扩面。

**裁决：A（2026-11-11 用户批准）。**

**D4 扩面后的设备证据分工。** 选项：A（推荐）本片只给 JVM 证据，设备侧由 V-10 instrumented 链路承担（扩面后重跑同一用例），字号机静态屏不动；B 本片同时改种子（加一条非转账腿 + 新标记 + 新 tap 路径 + 种子回读），成本是一次 rig 改动与两档重跑。

**裁决：A（2026-11-11 用户批准）。**

**D5 退役与文案。** 事实修正：`LEG_NOT_TRANSFER_CONFIRMABLE`（候选门）在扩面后不可达；但文案 `RECONCILIATION_NOT_CONFIRMABLE_TEXT`（`P503ReconciliationPresentation.kt:330`）**同时是 `P408_POSTING_NOT_ELIGIBLE` 的标签**，而该码在 §2.5/V-14 下继续活着（作废腿），所以字符串**不能**随枚举一起删。选项：A（推荐）删枚举成员与其测试期望，字符串保留给 `P408_POSTING_NOT_ELIGIBLE`（文案本身准确）；B 删字符串并给 `P408_POSTING_NOT_ELIGIBLE` 换一句新文案（用户可见文案变更，需 copy 批准）；C 保留枚举成员（死代码）。

**裁决：A（2026-11-11 用户批准）。**

---

## 4. 读写契约（D2=A 时零 DDL）

| 用途 | 形态 | 变化 |
| --- | --- | --- |
| 对账写面资格 | 新具名查询 | §2.1 腿集；`selectP408PostingIntegrity` 原样留给修正面 |
| 入口 B 事实读 | 既有查询 | 只删 `:12020` 的 kind 谓词 |
| 候选/提案用例 | 应用层 | 删 `LEG_NOT_TRANSFER_CONFIRMABLE` 门（成员去留见 D5） |
| 队列 / 汇总 | 既有查询 | page CTE 内 LEFT JOIN rg03，采纳 false 覆盖（各 2 行） |
| 既有行 id | 写面逻辑 | 删 id 比对，行 id 权威 |
| 职责呈现 | 无改动（D1=A 时） | — |
| 种子 / rig | 无改动（D4=A 时） | — |

唯一写动作仍是既有 `materialize` 与 `confirmLink`。

---

## 5. 验收向量

| # | 向量 | 判据 |
| --- | --- | --- |
| V-12 | 非转账资产腿可确认（§2.1/§2.3） | 普通支出腿 → 唯一候选 → `Linked`；正式行指纹（`P801ReconciliationFixture.kt:371-383`）与计数（`:403`）不变 |
| V-13 | 有行的腿可确认（§2.4，缺陷 A 回归） | 预置行 + 新 id 请求 → `Linked`，状态历史从既有序号继续，回执与审计用行 id |
| V-14 | 作废腿不可确认（§2.5，缺陷 B 回归） | TOCTOU：取候选 → 作废交易 → 确认 → `P408_POSTING_NOT_ELIGIBLE`，零链接零状态行（需新增作废事实夹具；`P801ReconciliationFixture` 今天已造交易/版本/分录/证据/投影/链接，缺 `transaction_void_fact`） |
| V-15 | 同额同日两 kind 不唯一（D3） | 一条支出腿 + 一条转账腿 → `AMBIGUOUS`，绝不 `PROPOSED_MATCH` |
| V-16 | 三面等式，限复合标记腿（§2.1/§2.2） | 对复合标记腿：队列逐腿状态 = `COALESCE(row,'PENDING')`，汇总的 (币种,状态) 合计与队列一致，详情同腿同状态。带对账行/活动链接的非标记腿是 §1.5 的已知偏差，**不纳入本向量** |
| V-17 | 报告口径不变（§2.8） | 报告仍排除非转账腿，转账腿仍 `PENDING`/`CHECKED` 如常 |
| V-18 | rg03 false 覆盖与分页（§2.7） | 合成行必须落在**复合标记腿**上（`role='TRANSFER_FEE'` + `category_id`，`Ledger.sq:860-861`）：队列与汇总不再列该腿，而写面仍可确认（登记「可写但队列不可见」）；同一夹具下带该行的键集分页不跳行、不重行，且汇总合计等于页内合格腿之和 |
| V-19 | 修正面不随扩面（§2.6） | 非转账腿的 P408 修正请求仍被拒；作废交易的转账腿修正行为与今天逐字节一致 |
| V-20 | 职责呈现不变（D1=A） | 审计行仍原样印 `real_account_posting` **与** `destination_asset_posting`（测试今天只钉了前者，需补后者） |

---

## 6. 范围边界

**本片不做**（不得据本规格实施）：第三职责 token（三表 `CHECK` 重建与迁移）；`posting_reconciliation` 回填与任何 `36.sqm`（除非 D2=B）；`posting(ledger_id, account_id)` 索引；DB 级唯一约束；重写报告查询；改 `P408Matcher` 或对账状态机；把 `DIFFERENCE`/`MISSING` 变成可写；批量确认；时间范围选择器；跨账户/跨币种匹配；任何 UI 版式或文案改动（D1=B、D5=B 除外）。

**不变量**：正式金额、分录、余额、报表、版本、作废恢复零变化；对账状态只描述核验程度；不伪造交易补平差额；不把余额差额当对账结论。

---

## 7. 验证与残余

本片交付走任务分支 + PR：`Kotlin tests` / `Android compile` / `Python tests` / `Trace scan` 四门全绿后 squash 合并。设备证据见 §2.9 与 D4。

**登记残余**：负债两向（消费/还款本金）与非资金流分录（期初、余额调整、储值预激活调整）的职责命名是近似；一腿一职责的唯一性仍是代码级（无 DB 约束，靠单连接 + 事务）；报告读面继续只认转账（oracle-only）；`posting(ledger_id, account_id)` 无索引，队列在真实规模下的性能未实测；v23 种子为不合格腿写入对账事实的既有偏差（D2=A 时登记，不在本片修）；写面可确认 `rg03=false` 而读面不可见（§2.7）；写面谓词的复合标记要求 `catalog_account` 行，P408/P409 夹具族 11 个类（`P408ReconciliationStoreTest`、`P408CorrectionStoreTest`、`P408ProjectionAuthorityStoreTest`、`P408ProjectionSixKindMaterializationTest`、`P408ReconciliationCanonicalOracleTest`、`P409SingleChainMigrationTest`、`P409SiloSpineCoexistenceTest`、`P409PhaseClosureFullStateOracleTest`、`ImportSpineBankEndToEndTest`、`LedgerMigrationV20ToV36Test`、`P708RestoreRoundtripEquivalenceTest`）因此补了最小 catalog 种子——这是夹具落后于产品现实的修正（生产每个真实账户都有 catalog 行），断言不变（本清单首稿漏列第 11 个 `confirmLink` 调用者 `P708RestoreRoundtripEquivalenceTest`，实施勘察时补齐并登记于此）；负例账户另补 `kind='EXPENSE', real_account=0` 行以免拒绝空过。
