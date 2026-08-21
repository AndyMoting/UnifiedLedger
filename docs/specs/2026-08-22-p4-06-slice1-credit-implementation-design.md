# P4-06 片 1（RL-05 信用）实施规格

**Status:** approved（2026-08-22 用户常设授权 + 契约 D-106；独立评审 findings P406S1-SPEC-001..009 已修复闭合）

本规格按 D-106 契约（`docs/specs/2026-08-22-p4-06-credit-mixed-payment-contract-design.md`，Status: approved）§9 授权范围实施片 1（RL-05 信用），登记契约 §9 要求的全部实施批事项：kind token 标识符、白名单 token 清单、判定顺序与诊断码、列形状与迁移设计、全合成 fixtures 与 state oracle 粒度。粒度先例为 D-105 实施规格；任何超出 D-106 的默认行为均退回契约修订，不由实现静默发明（D-096 纪律）。

## 1. 目的与片间边界

本批实现 D-106 契约 §2.1 白名单解冻与信用腿路由、§3 三生命周期中信用消费（含退款变体）与信用还款的候选与确认、§5 evidence 基数登记、§7.1 RL-05 匿名锚点验收，并承载契约 §6 冻结的**完整** v24→v25 单个加性迁移（三类结构一次建成；混合结构建而不用）。

片间状态（契约 §9）：检测到资产+信用混合腿的行维持类型化拒行（fail-closed，登记待片 2），不产生半确认状态；片 2 零 schema 变更（其全部持久化结构由本批迁移一次交付）。

本批同时执行 D-106 §2.2 对 P4-05 的两处已登记修订：列 7（收/付款方式）由「不持久化、不解析」改为「白名单路由列」（P4-05 规格 §2.4 该行的修订已在 D-106 登记，本批只实现）；「信用借还」由类型化拒行改为还款路由（P4-05 规格 §3.2 该行的修订已在 D-106 登记）。本批不改变 D-097 ordinary v1、D-100 transfer v2、D-104/D-105 重复候选与处置的已批准行为，只在其上层叠加信用行族。

本批修改的既有测试面（契约强制的行为变化，非回归）：P4-05 合成 fixture 中的 `信用借还`×`不计收支`×`还款` 行（`AlipayCsvParserJvmTest.kt:108`、`ImportSpineAlipayEndToEndTest.kt:135`）由断言拒行改为断言信用还款源接受；其余 P4-05 fixture 断言不变。

数据填充修订（P406S1-SPEC-001 登记）：两个测试构建器的列 7 缺省填充值由 `SYN-SECRET-METHOD` 改为空串——`AlipayCsvParserJvmTest.kt:64`（`recordRow`）与 `ImportSpineAlipayEndToEndTest.kt:120`（同款构建器）。理由：P4-05 冻结表未冻结 fixture 的列 7 值（该列当时不读取），本批列 7 成为白名单路由列后 `SYN-SECRET-METHOD` 属非白名单 token、会使全部既有 fixture 行触发腿门拒行；改为空串（= 无腿，腿门不触发）后，A-01…A-16 其余断言由此保持逐值不变（等价性说明见 §6.9）。

## 2. 解析层：白名单、括注剥离与判定顺序

### 2.1 支付腿种类白名单（首批冻结）

列 7（收/付款方式，0-based 字段 7）为组合 token：`&` 连接多个腿，腿呈「token(括注)」形态（P4-05 规格 §3.1 取证）。本批冻结提取算法：

1. 列 7 为空 → 无腿（腿集合为空）。
2. 非空 → 按 `&` 拆分为腿原文，逐腿做 §2.2 括注剥离，得到归一化腿 token。
3. 归一化 token 去重（集合语义：`花呗` 与 `花呗分期(3期)` 归并后为同一 token，不产生重复腿）。
4. 任一归一化 token ∉ 白名单 → 该行类型化拒行（§2.4 诊断码），fail-closed、零写入（契约 §2.1；对**所有**读取列 7 的分支通用，包括否则走 ordinary 的行）。

白名单首批冻结（语义分类按契约 §2.1 两类登记）：

| 类 | 归一化 token（剥离后） | 依据 |
| --- | --- | --- |
| 信用腿 | `花呗` | RL-05 消费锚点即此形态（`花呗分期(##期)` 剥离分期括注后归并花呗族）；契约 §7.1 已冻结为信用腿 |
| 资产腿 | `余额宝` | P4-05b 规格 :142-143（余额宝族 9 真实样本行为证据与 golden 候选计数）；还款行人工确认建议输入 |
| 资产腿 | `账户余额` | P4-05b 规格 :117（`账户余额` 通用 token 行为证据登记）+ :142（7/9 样本中 收/付款方式=账户余额 ×4）；`(个人余额)` 限定形剥离后归并 |
| 资产腿 | `余额` | P4-05b 规格 :117（`余额` 通用 token 行为证据登记）+ :143（1/9 样本 收/付款方式=余额） |
| 资产腿 | `招商银行储蓄卡` | 行为证据汇编（还款行付款列机构类；尾号括注剥离） |

**明确不入白名单**（含此类腿的行类型化拒行，并登记为已知限制）：营销腿族（`红包`、`优惠`、`花呗立减`、`闪购支付红包`、`网上消费红包`、`支付宝随机立减`、`到店支付立减券`——非资金腿，证据不足）；`他人代付账户`（不导入本人账户）；微信侧 token `零钱`/`零钱通`（P4-03:141 冻结不解析维持）；`数字人民币钱包`、裸尾号形（如 `(1234)`）、`信用卡`（零证据）。

已知限制登记：真实世界中「花呗 + 营销腿」组合行（如 `花呗(1234)&支付宝随机立减`）在 v1 将因营销腿非白名单而拒行；「非资金标注腿剥离」语义待未来契约修订引入，本批不实现（§7）。

白名单扩张只经显式合同修订（行为证据 + 独立评审）；实现不得静默接受新 token（契约 §2.1 开放域原则）。

隐私边界（契约 §2.1）：腿种类 token 仅用于路由与候选 provenance；掩码尾号、账号、括注原文一律不落盘；列 7 除归一化腿 token 外的任何内容不进入事实或持久化。**仅含资产腿或空列 7 的普通行不产生任何列 7 持久化事实（「列 7 零落盘」对普通行维持）**——资产腿 token 只在行被路由为 v3 源（还款建议输入）时持久化。

### 2.2 括注剥离规则（冻结）

三类括注一律剥离、不持久化；无括号 token 原样保留：

| 括注形态 | 剥离规则 | 例 |
| --- | --- | --- |
| 数字掩码尾号 | 剥离恰 `(\d{4})`（4 位数字，P4-05 §3.1 定长取证；`####` 为占位记法，指任意 4 位合成数字掩码） | `招商银行储蓄卡(####)` → `招商银行储蓄卡` |
| 限定词 | 剥离 `(个人余额)` | `账户余额(个人余额)` → `账户余额` |
| 分期数 | 剥离 `(\d+期)` | `花呗分期(3期)` → `花呗`（归并花呗族） |

剥离后 token 必须逐字等于白名单成员才视为白名单内；任何其他括注形态（其他位数数字、其他限定词、字母括注等）或任何其他 token 文本 → 非白名单 → 类型化拒行。剥离只作用于尾部括注，token 内部括号不处理（视为非白名单）。

### 2.3 判定顺序（在 `AlipayCsvParser` 现顺序中的插入位置）

现冻结顺序：结构/tab 校验 → (1) 退款标记 → (2) 投资理财（RL-04）→ 拒绝分类集 → 未知分类 → §2.4 事实映射。本批修改后的完整顺序（确定性，逐条短路）：

0. 结构/tab 校验（不变）。
1. **退款标记分支**（分类或状态含 `退款`；读取列 5/7/8）：
   a. 列 7 腿解析（§2.1）：任一归一化 token ∉ 白名单 → `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行。
   b. 列 5 ≠ `不计收支` → `SPINE_ALIPAY_REFUND_UNSUPPORTED` 拒行（未列形态，维持 P4-05 处置）。
   c. 恰信用腿（≥1 信用腿、0 资产腿）→ **信用退款变体源**（§3.1 kind，contract_version=3）：状态 = `退款成功` → `refund_settled`、valid_complete；其余状态 token（含 `交易关闭`）→ 状态 raw 保留 + unresolved → valid_incomplete（不可确认，零拒行升级，契约 §2.2 状态门）。方向不读列 5，按 §2.5 冻结规则派生。
   d. 其余（仅资产腿、空、或资产+信用混合腿）→ `SPINE_ALIPAY_REFUND_UNSUPPORTED` 拒行（维持 P4-05；混合腿退款无契约分录形状——§3.3.3 冻结为单负债腿 费用−/负债+——故 fail-closed，登记 §7）。
2. 投资理财分支（RL-04，不变）。登记：白名单腿门只施于读取列 7 的分支（步骤 1 退款、步骤 3 信用借还、步骤 6 接受分类）；RL-04 投资理财分支不读列 7，D-102 冻结行为维持不变。
3. **信用借还分支**（新增；`信用借还` 自 `REJECTED_TX_TYPES` 移出，进入专属分支）。族籍冻结读法（契约 §2.2 行 3）：本族族籍 = 分类 `信用借还` + 列 5 `不计收支`；腿构成与状态不参与族籍判定，由族内门 a–e 处置（非族籍形态即列 5 ≠ `不计收支`，在 3b 维持类型化拒行）：
   a. 列 7 腿解析：任一 token ∉ 白名单 → `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行。
   b. 列 5 ≠ `不计收支` → `SPINE_ALIPAY_UNSUPPORTED_TX_TYPE` 拒行（族其余形态，维持 P4-05 处置与诊断码）。
   c. 含信用腿 → `SPINE_ALIPAY_UNSUPPORTED_TX_TYPE` 拒行（族其余形态，无锚点）。
   d. 前置门：去重后资产腿 > 1（多于一个不同资产 token，如 `余额宝&招商银行储蓄卡(####)`）→ `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行（无锚点，fail-closed）。通过后：仅资产腿（恰一）或空列 7 + 状态 = `还款` → **信用还款源**（valid_complete；状态映射 `settled`；方向按 §2.5 派生；资产腿 token 作建议输入持久化，见 §3.2）。
   e. 仅资产腿或空列 7 + 其余状态 token → 信用还款源、状态 raw 保留 + unresolved → valid_incomplete（不可确认，零拒行升级）。
4. 其余拒绝分类集（`账户存取`/`转账红包`/`亲友代付`）→ `SPINE_ALIPAY_UNSUPPORTED_TX_TYPE`（不变）。
5. 未知分类 → `SPINE_ALIPAY_UNKNOWN_TOKEN`（不变）。
6. 接受分类（`网上支付`/`扫码支付`/`其他`）——在既有事实映射**之前**插入列 7 腿门：
   a. 列 7 为空 → ordinary v1 原路径（列 7 零读取、零落盘，不变）。
   b. 腿解析：任一 token ∉ 白名单 → `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行（契约 §2.2 末行对一切行通用）。
   c. 仅资产腿 → ordinary v1 原路径（列 7 零落盘维持，契约 §2.2 行 5）。
   d. 混合腿（资产腿 + 信用腿）且列 5 已解析为 `支出` → `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` 拒行（片 1 fail-closed，契约 §9 片间状态，登记待片 2；方向未解析为 `支出` 的混合腿行走 f/g）。
   e. 恰信用腿 + 列 5 = `支出` → **信用消费源（直接变体）**：状态 = `交易成功` → `settled`、valid_complete；其余状态 → raw + unresolved → valid_incomplete（不可确认）。方向走既有 `支出`→`out` 映射。
   f. 任一信用腿（恰信用或混合）+ 列 5 = `收入` → `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED` 拒行（无锚点，防御性 fail-closed，契约 §2.2 行 8）。
   g. 任一信用腿（恰信用或混合）+ 列 5 = `不计收支` 或其余未映射方向 token → ordinary v1、方向 raw + unresolved → valid_incomplete（P4-05 A-04 冻结行为维持，契约 §2.2 行 10；不进任何 v3 源，腿 token 不持久化）。

金额/时间/币种/精度解析与 P4-05 §2.4 冻结完全一致（所有 v3 分支复用同一 `parseAmount`/`parseTime`/CNY/precision=2 常数）；拒行零 record、零 candidate、零 spine intake（D-099:1539 登记纪律）。

### 2.4 诊断码（新增 3 个，仿 `SPINE_ALIPAY_*` 先例）

| code | severity | scope | 安全 location |
| --- | --- | --- | --- |
| `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` | unsupported | record | {input_ref, record_ordinal} |
| `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` | unsupported | record | {input_ref, record_ordinal} |
| `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED` | unsupported | record | {input_ref, record_ordinal} |

不新增 severity/scope 值域；message 不稳定、不比较；不透出腿原文或掩码（隐私边界）。既有 `SPINE_ALIPAY_REFUND_UNSUPPORTED`/`SPINE_ALIPAY_UNSUPPORTED_TX_TYPE` 语义按 §2.3 收窄使用。

### 2.5 状态门与方向派生（冻结）

- 状态门（契约 §2.2，D-102 先例）：信用消费族（直接变体与混合）成功 token = `交易成功`；信用还款族 = `还款`；信用退款变体 = `退款成功`。映射为族内映射（**不扩张** P4-05 ordinary `STATUS_TOKEN_MAP`：ordinary 行状态 `还款`/`退款成功` 仍 raw + unresolved + valid_incomplete，P4-05 §3.3 行为不变）。持久化 status_token = 族内映射值：`settled`（消费/还款族）、`refund_settled`（退款变体，新冻结映射值，同时构成退款变体在事实层的可判别信号）；非成功 → 状态 raw 保留 + unresolved → valid_incomplete，不可确认，零类型化拒行升级。
- 方向派生（RL-04 `yuebao_subtype_direction_v1` 先例）：还款行与退款变体行的列 5 恒为 `不计收支`（P4-05 §3.1 取证），不可走 `收入/支出` 映射；两族方向由冻结规则派生、不读列 5——还款 → `out`（rule `credit_repayment_direction_v1`，exact）；退款变体 → `in`（rule `credit_refund_direction_v1`，exact；资金向用户方向回流，即便落负债腿）。信用消费直接变体方向走既有 `支出`→`out`。
- funding 中继：v3 行与既有行一致中继 `SETTLED`/`legacy-settled-v1`（D-105 实施登记的行为惰性中继，路径不进任何 funding 门控）。

## 3. 领域与应用合同

### 3.1 kind token 与 contract_version=3（契约 §3.1）

`ImportRecordKind` 新增恰三个值（仿 `ordinary_flow_source`/`transfer_flow_source` 命名纪律），全部 `contractVersion = 3`：

| enum | storageValue | 对应 candidate kind |
| --- | --- | --- |
| `CREDIT_EXPENSE_SOURCE` | `credit_expense_source` | `credit_expense`（含直接/退款两变体） |
| `CREDIT_REPAYMENT_SOURCE` | `credit_repayment_source` | `credit_repayment` |
| `MIXED_PAYMENT_SOURCE` | `mixed_payment_source` | `mixed_payment`（片 1 仅结构，解析层不产出） |

退款变体不设第四 kind：在候选层以语义标记区分（§3.2 `variant`），正式化按 RG-07 退款合同语义（D-078）。ordinary v1 与 transfer v2 既有行不重写、不升级（契约 §3.1）。

### 3.2 intake 合同扩展：payment profile（候选层语义标记与腿 provenance）

`ImportSpine.kt` 新增：

- `enum class ImportPaymentVariant { CREDIT_EXPENSE_DIRECT, CREDIT_EXPENSE_REFUND, CREDIT_REPAYMENT, MIXED_PAYMENT }`
- `data class ImportPaymentProfile(val variant: ImportPaymentVariant, val assetLegKindToken: String?, val creditLegKindToken: String?)` —— `assetLegKindToken` 对还款行为可空（建议输入；列 7 空），`creditLegKindToken` 对还款行为空。

`ImportIntakeRequest`/`ImportIntakeSnapshot` 增加可空 `paymentProfile: ImportPaymentProfile?`。spine 校验门：v3 kind 必须携带非空 profile、v1/v2 kind 必须为 null，违例 `SPINE_INTAKE_INVALID`（对称于既有五事实校验）。变体形状校验：`CREDIT_EXPENSE_DIRECT`/`CREDIT_EXPENSE_REFUND` ⇒ `creditLegKindToken != null && assetLegKindToken == null`；`CREDIT_REPAYMENT` ⇒ `creditLegKindToken == null`（`assetLegKindToken` 可空）；`MIXED_PAYMENT` ⇒ 双腿非空（片 1 解析层不产出，防御性形状冻结）。

`ImportContentFingerprint.canonicalJson` 加性扩展：profile 非空时追加成员 `payment_variant`（必有）、`asset_leg_kind_token`/`credit_leg_kind_token`（非空时），成员名升序插入既有闭对象；profile 为 null（v1/v2）时输出与现行字节相同——既有行 replay 等价性不变。`intakeEquivalent` 的冻结比较清单同步扩展 profile 三字段。

### 3.3 决策字段形状（契约 §3.2/O-4：腿 token 只是建议，不构成账户映射）

`ImportConfirmDecisionFields` 新增三个实现（账户一律由用户显式指定；候选不携带猜测账户）：

| 决策字段 | 形状 | 适用 |
| --- | --- | --- |
| `CreditExpenseFlow` | `categoryId: CategoryId` + `creditLiabilityAccountId: AccountId` | `credit_expense` 直接变体 |
| `CreditExpenseRefundFlow` | `categoryId` + `creditLiabilityAccountId` + `originalTransactionId: TransactionId` | `credit_expense` 退款变体（退款关联原交易的输入形状） |
| `CreditRepaymentFlow` | `assetAccountId: AccountId` + `creditLiabilityAccountId: AccountId` | `credit_repayment` |

store 确认门（`commitOnce`）扩展：kind 门按 candidate kind + profile 变体判定决策字段类型——`credit_expense` + `CREDIT_EXPENSE_DIRECT` ⇒ 必须 `CreditExpenseFlow`；`credit_expense` + `CREDIT_EXPENSE_REFUND` ⇒ 必须 `CreditExpenseRefundFlow`（`originalTransactionId` 必填）；`credit_repayment` ⇒ 必须 `CreditRepaymentFlow`；`mixed_payment`（片 1 防御：不可能存在）⇒ 一律 `SPINE_DECISION_KIND_MISMATCH`。错配复用既有 `SPINE_DECISION_KIND_MISMATCH`。posting 数量门维持 `== 2`（三个信用生命周期确认均为 2 分录；混合 3 分录门属片 2 代码变更，零 schema 影响）。

`CreditExpenseRefundFlow.categoryId` 语义（退款分类继承规则，ACCOUNTING_RULES.md:146 + D-078）：必须等于 `originalTransactionId` 所指原交易的**当前**二级支出分类；原交易分类错误时先经版本替代修正原交易再处理退款，不允许只为退款改用其他分类。违反 → 领域工厂失败 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入（§3.4 工厂 3 校验 (ii)）。

信用负债账户持有前置（契约 §3.2）：确认请求携带的负债账户未被用户持有、非 `LIABILITY`、跨账本或币种不符时，领域工厂返回 `DomainResult.Failure` → `SPINE_DOMAIN_VALIDATION_FAILED` 类型化拒绝、零写入、claim 回滚可重试——候选保持 `pending_confirmation`（不可确认路径，零正式写入）。解析器/spine/候选流程不自动创建信用负债账户；产品核心不内置默认负债账户或默认账户映射（D-053：共享负债账户属迁移配置）。

### 3.4 正式化工厂与领域服务（复用 D-072/D-078 冻结合同语义，不复制竖井 owner 表）

三个应用层工厂（仿 `TransferFlowFormalFactory` 形态，将 `ImportCommitIds` 映射为领域 ids 并调用领域服务）：

1. **信用消费（直接变体）**：领域新增 `createCreditExpense(catalog, command, ids)`（`MixedPayment.kt` 随附；`createMixedPaymentExpense` 冻结为恰 2 条 funding，单信用腿消费不能复用）。合同语义 = D-072 混合分录的信用腿单腿退化：二级活跃支出分类 + 其费用账户（+总额）；信用负债账户校验同 `createCreditPrincipalRepayment` 的负债校验（real/owned/`LIABILITY`/同账本/同币种）；分录 费用 `+` / 负债 `−`；`TransactionKind.EXPENSE`；新 posting role `CREDIT_EXPENSE_LIABILITY_FUNDING`；报告效应（D-058 算例）`consumption=total, ordinaryExpense=total, cashOutflow=0, income=0, netWorth=-total`（购买日现金流出为零、消费全额确认一次）。
2. **信用还款**：直接复用既有 `createCreditPrincipalRepayment`（`CREDIT_REPAYMENT` 独立交易类型、资产 `−`/负债 `+`、`real/owned/kind/同账本/同币种` 校验、报告效应 cash=本金、消费与净资产变化为零；不得以 `ACCOUNT_TRANSFER` 代替，D-072）。
3. **信用退款变体**：领域新增 `createCreditRefundReceipt(catalog, command, ids)`（`RefundReceipt.kt` 随附；既有 `createRefundReceipt` 冻结为 `ASSET` 目的地，不能复用）。合同语义 = D-078 退款收据的负债目的地变体：二级活跃支出分类 + 费用账户（`−` 退款额）；目的地 = 用户持有 real `LIABILITY` 账户（`+` 退款额，欠款减少）；`TransactionKind.REFUND_RECEIPT`；独立经济事件（非冲正、非版本修正、不吞并原消费版本）；`originalTransactionId` 随命令携带并由 §4 决策快照列 + FK 持久化（原期间消费不变，退款期冲减消费）。两道原交易校验（违反任一 → `DomainResult.Failure` → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入）：(i) `originalTransactionId` 必须指向同账本、同币种、`EXPENSE` 族交易（kind = `EXPENSE`，含混合支付确认产物；跨账本由 §4 复合 FK 先拒）；(ii) `command.categoryId` 必须等于原交易当前版本费用分录账户所对应的二级支出分类（ACCOUNTING_RULES.md:146 分类继承 + D-078）。

领域扩展为纯加性（新函数 + 新枚举值），不修改任何既有领域函数的已批准行为。三个工厂均不读产品 Clock；`explicitConfirmedAt` 由请求显式携带（既有纪律）。

## 4. v24 -> v25 持久化（单个加性迁移，三类结构一次建成）

迁移 `25.sqm`（v24→v25，恰一次版本推进；片 2 零 schema 变更）。caller 单一外层事务包裹；六阶段模板（20.sqm/21.sqm/23.sqm 先例）；重建表与 `Ledger.sq` fresh DDL 结构等同（SQLDelight migration verifier + `LedgerDatabaseMigrationTest` 强制）。append-only 守卫触发器风格沿用 23.sqm；既有 v24 funding 列（`funding_state`/`funding_rule_id`/`funding_rule_version`/`candidate_generated_at`）原语义保留、不回写。

**重建表（3 张；`PRAGMA defer_foreign_keys = 1` + stage 拷贝 = 23.sqm defer+stage 模板；重建计数守卫 = 21.sqm 模板；后代行全部保留）**：

1. `import_source_record`：`record_kind` CHECK 追加 `credit_expense_source`/`credit_repayment_source`/`mixed_payment_source`；`contract_version` CHECK 追加 `3`；kind→version 配对 CHECK 追加三对 v3 配对（D-100 第 9 条先例）。其余列与 v24 形状不变。
2. `import_candidate`：`candidate_kind` CHECK 追加 `credit_expense`/`credit_repayment`/`mixed_payment`。`UNIQUE(ledger_id, source_id)` 与单 source 约束不变（D-105 §5 纪律）；`rule` 仍存 record kind storageValue、`rule_version` 仍 = 1。
3. `import_candidate_decision_snapshot`：新增 5 个可空列（既有四账户列无 FK 先例维持，仅退款关联列加 FK）：
   - `credit_liability_account_id TEXT`（三个信用确认形状共用）
   - `asset_account_id TEXT`（还款与混合）
   - `asset_leg_minor INTEGER` / `credit_leg_minor INTEGER`（混合各腿精确金额；片 1 建而不用）
   - `original_transaction_id TEXT`，`FOREIGN KEY (original_transaction_id, ledger_id) REFERENCES ledger_transaction(transaction_id, ledger_id)`（非延迟：原交易必先于退款确认存在；`import_confirmation` 交易 FK 先例）
   - confirm XOR CHECK 加性演进（20.sqm 先例）为恰一形状：ordinary（`category_id`+`funding_account_id`，其余全 NULL）｜transfer（`from`+`to`）｜信用消费直接（`category_id`+`credit_liability_account_id`）｜信用退款（`category_id`+`credit_liability_account_id`+`original_transaction_id`）｜信用还款（`asset_account_id`+`credit_liability_account_id`，`category_id` NULL）｜混合（`category_id`+`asset_account_id`+`credit_liability_account_id`+`asset_leg_minor`+`credit_leg_minor`，金额列与账户列绑定非空）；reject CHECK 扩为全列（含 5 新列）NULL。混合形状的 CHECK 现在即允许（结构先建）；kind⇄形状配对由 store 决策字段门（§3.3）执行，与既有设计一致（快照表无 kind 列）。

**新建表（3 张 + 触发器）**：

4. `import_candidate_payment_profile`（「信用/混合 candidate」结构：候选层语义标记 + 腿 token provenance）：
   - `ledger_id, candidate_id, variant TEXT NOT NULL CHECK (variant IN ('credit_expense_direct','credit_expense_refund','credit_repayment','mixed_payment')), asset_leg_kind_token TEXT, credit_leg_kind_token TEXT`，PK `(ledger_id, candidate_id)`，FK → `import_candidate`。
   - 形状 CHECK：direct/refund ⇒ `credit_leg_kind_token IS NOT NULL AND asset_leg_kind_token IS NULL`；repayment ⇒ `credit_leg_kind_token IS NULL`（资产腿可空）；mixed ⇒ 双腿非空（片 1 不产出，形状先冻结）。
   - intake 与 source/evidence/candidate 同事务写入；「每个 v3 candidate 恰一行 profile」由 store 写入路径 + oracle 断言（无跨表 CHECK，先例：store 层归属门）。
   - update/delete 守卫触发器。
5. `mixed_payment_group`（「`mixed_payment` 关联组产品表」头表；片 1 建而不用，零行）：
   - `ledger_id, group_id, candidate_id TEXT NOT NULL, transaction_id TEXT NOT NULL, request_id TEXT NOT NULL, total_minor INTEGER NOT NULL CHECK (total_minor > 0), generated_at TEXT NOT NULL`（显式时间，不读产品 Clock），PK `(ledger_id, group_id)`，`UNIQUE (ledger_id, candidate_id)`、`UNIQUE (ledger_id, transaction_id)`，FK → `import_candidate`/`ledger_transaction`/`import_request`；update/delete 守卫触发器。
6. `mixed_payment_group_leg`（关联组成员行；片 1 建而不用）：
   - `ledger_id, group_id, leg_index INTEGER NOT NULL CHECK (leg_index >= 1), leg_class TEXT NOT NULL CHECK (leg_class IN ('asset','liability')), account_id TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK (amount_minor > 0)`，PK `(ledger_id, group_id, leg_index)`，FK → `mixed_payment_group` **DEFERRABLE INITIALLY DEFERRED**（`import_confirmation` 交易 FK 先例：片 2 写入顺序为腿先、头后，头行 INSERT 触发器完成性校验）。
   - 完成性触发器 `mixed_payment_group_complete`（BEFORE INSERT ON `mixed_payment_group`）：恰 1 `asset` 腿 + 恰 1 `liability` 腿（D-072 冻结恰 2 funding）且 `SUM(leg.amount_minor) = total_minor`，否则 ABORT。
   - 写序守卫触发器 `mixed_payment_group_leg_before_head`（BEFORE INSERT ON `mixed_payment_group_leg`：同 `(ledger_id, group_id)` 的头行已存在即 ABORT）——冻结「腿先、头后」写入序：头行 INSERT 即组完成（完成性触发器校验），此后腿不可再追加；与腿表 DEFERRABLE FK 兼容（腿先插入时头行尚不存在，FK 延迟到提交时检查）。
   - 两表 update/delete 守卫触发器。

**迁移阶段**：Stage 1 新建 3 表；Stage 2 回填空（新列以 NULL 重建写入，21.sqm 模板）；Stage 3 fail-closed 数据守卫（guard 表建拆于阶段内，21.sqm 模板：迁移前 `import_source_record.record_kind` ∉ v3 集、`import_candidate.candidate_kind` ∉ 信用/混合集、决策快照满足旧 XOR、新建 3 表可查询且空）；Stage 4 三表 staged 重建（子→父删、父→子建、显式列清单拷贝、计数守卫、stage 拆除；既有后代表行与 FK 语义全保留）；Stage 5 空（不删任何既有表；rgXX 竖井可查询守卫断言）；Stage 6 重建表守卫触发器复原 + 新表守卫触发器（与 fresh DDL 同文本）。`Ledger.sq` fresh DDL 同步全部上述结构（fresh = migrated）。

## 5. 候选、确认、幂等与 replay（对齐 D-104/D-105 claim/rollback 模式）

- **intake**：v3 行 intake 与既有路径同事务写 source/evidence/candidate（kind 映射追加）+ `import_candidate_payment_profile` + requirement 行；`VALID_COMPLETE`+`SETTLED` → 初始状态 `pending_confirmation`，否则 `incomplete`。P4-07 重复候选机制对 v3 行族原样生效（kind 无关的 persisted-facts 元组比较；退款变体 status_token=`refund_settled` 参与元组）。
- **确认门**（契约 §3.2/§3.3）：候选 `pending_confirmation` + kind/变体门（§3.3）+ content hash 门 + P4-07 `CONFIRMED_DUPLICATE` 阻断门（原样）+ posting 数量门（=2）+ 领域工厂校验（账户持有/kind/账本/币种、分类二级活跃）→ 恰一套最终分录（§3.4 三工厂）；决策快照按 §4 XOR 形状写入（退款变体必含 `original_transaction_id`）。禁止重复计入：确认后不得以调整/推断/镜像名义追加第二套分录或第二笔交易（契约 §4）；后到镜像证据按 D-103/D-104 只追加 lineage/candidate。
- **幂等与 replay**：同 request 等价重放返回原 receipt（比较清单含 profile 与新决策字段，§3.2/§4）；raw identity 重放零写入回滚（unchanged）；不等价同身份 → `SPINE_IDENTITY_COLLISION`；任何类型化拒绝/失败注入回滚全部写入含 claim（unchanged 机制）；`SPINE_STALE_FINGERPRINT`/`SPINE_CANDIDATE_NOT_PENDING`/`SPINE_CANDIDATE_INCOMPLETE`（valid_incomplete 状态门负路径）原样适用。
- **evidence 基数（契约 §5 首个场景登记）**：一行来源 evidence ↔ 一笔交易（不拆单）；交易内 evidence:posting —— 信用消费 1:1（负债腿）、信用还款 1:1（资产腿）、信用退款 1:1（负债腿）；费用分录不参与 evidence link 与对账（D-072）。本批不写任何 P4-08 evidence link/reconciliation（D-103 边界维持）；基数登记以 oracle 断言形式固化（可链接的 real-account 分录集合），约束后续 P4-08 场景合同。
- **退款关联**：原期间消费版本不变；退款交易独立（`REFUND_RECEIPT`）；关联事实 = 决策快照 `original_transaction_id`（append-only + FK + 跨账本由复合 FK 拒绝）；不创建第二笔交易、不冲正原版本。

## 6. 匿名 oracle 矩阵（金额为匿名代表值；fixtures 全合成、来源中立）

新增 P4-06 canonical full-state oracle（`P406CreditFullStateOracleTest`，仿 `P407DuplicateClosedFullStateOracleTest`：比较全部 import source/evidence/candidate/profile/status/confirmation/receipt、P4-07 duplicate 全表、formal graph（交易 kind/分录/方向/金额）、余额与 P4-08 reconciliation 状态）。最少覆盖：

1. **RL-05 三锚点**（契约 §7.1，匿名代表值；真实金额/时间只在只读外部 fixture）：信用消费（分期形支付方式 `花呗分期(N期)`）确认 → 1 `EXPENSE` 交易恰 2 分录（费用 `+`/负债 `−`）、现金流出 0、消费=全额、净资产变化 `−`全额、`cash=0` 报告效应；信用还款 56.20 确认 → 独立 `CREDIT_REPAYMENT`、资产 `−5620`/负债 `+5620`、现金流出 56.20、消费与净资产变化 0、不重复原消费；信用退款 15.35 确认 → 独立 `REFUND_RECEIPT`、费用 `−1535`/负债 `+1535`、`original_transaction_id` 持久化且 FK 有效、原期间消费版本不变。
2. **状态门负路径**：消费族 `交易关闭`、还款族 `交易成功`/`交易关闭`、退款变体非 `退款成功` → 各自 kind + valid_incomplete + 状态 raw 保留 + `SPINE_CANDIDATE_INCOMPLETE` 不可确认 + 零正式写入；断言无一条因状态升格为类型化拒行。
3. **白名单外拒行**：营销腿（`红包`/`花呗立减`/`支付宝随机立减` 等）、`他人代付账户`、`零钱`/`零钱通`、`数字人民币钱包`、`信用卡`、裸尾号形、非冻结括注形态 → `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行 + 零 source/candidate/profile；「花呗+营销腿」组合行拒行（已知限制）。
4. **片间 fail-closed 与混合腿方向性**：`余额宝&花呗` 混合腿 + `支出` → `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` + 零写入（含零半确认状态，§2.3 6d）；混合腿 + `不计收支`（或未映射方向 token）→ ordinary v1、方向 raw + unresolved → valid_incomplete、零 v3 写入、腿 token 不持久化（契约 §2.2 行 10，§2.3 6g）；混合腿 + `收入` → `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED`（§2.3 6f）。
5. **负形态与防御**：空列 7 → 无腿（普通行零落盘、还款行可路由）；`收入`+信用腿 → `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED`；`不计收支`+接受分类+信用腿 → ordinary valid_incomplete（A-04 维持）且 profile/腿零持久化；信用借还族其余形态（`支出`/`收入`方向、含信用腿）→ `SPINE_ALIPAY_UNSUPPORTED_TX_TYPE`；信用借还还款行去重后资产腿 > 1（如 `余额宝&招商银行储蓄卡(####)`，二者均白名单内）→ `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` 拒行 + 零写入（§2.3 3d 前置门）。
6. **括注剥离与隐私**：`花呗分期(3期)`→信用腿 `花呗`；`账户余额(个人余额)`/`余额宝(个人余额)`→资产腿；`招商银行储蓄卡(####)`（`####` 为任意 4 位合成数字掩码占位）→`招商银行储蓄卡`；oracle 断言任何持久化行/快照/profile 不含掩码数字、括注原文与列 7 其他内容。
7. **重复与 replay**：同 request 重放与 raw identity 重放零新增行；两条等值信用行 → D-104 exact-tuple duplicate candidate 追加、无第二笔交易；确认后重放返回原 receipt；失败注入零残留。
8. **确认负路径**：未指定/未持有/非 `LIABILITY`/跨账本/币种不符负债账户 → `SPINE_DOMAIN_VALIDATION_FAILED` + 候选保持 pending + claim 可重试；退款变体缺/跨账本 `originalTransactionId` → 类型化拒绝零写入；退款变体确认分类 ≠ 原交易当前二级支出分类 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入（§3.4 校验 (ii)，ACCOUNTING_RULES.md:146）；`originalTransactionId` 指向非 `EXPENSE` 族交易（如 `CREDIT_REPAYMENT`）或币种不符交易 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入（§3.4 校验 (i)）；kind/变体/决策字段错配 → `SPINE_DECISION_KIND_MISMATCH`。
9. **回归**：ordinary/transfer 全部既有断言（含 ordinary 行状态 `还款`/`退款成功` 仍 raw+unresolved）、P4-05 fixtures（除 §1 登记的契约强制更新行）、P4-07 全流程、P4-08 共存。等价性说明（P406S1-SPEC-001）：两个测试构建器列 7 缺省值改空串（§1 数据填充修订）后，A-01…A-16 行的列 7 = 无腿 → 腿门不触发，解析输出与 P4-05「列 7 不读取」行为逐值等价，故除 §1 登记的信用借还行外，A-01…A-16 全部既有断言逐值不变。
10. **迁移**：fresh v25、v1→v25、v24→v25 populated 升级（v3 结构零行、v1/v2 行与 funding 列逐值不变）、reopen、迁移失败原子回滚。

## 7. 排除项

1. 片 2 混合行为激活：混合路由仍类型化拒行（§2.3 6d）；`mixed_payment` 两表与决策快照混合形状建而不用；`constraint_solved` 反推建议、缺腿资料不足路径、§7.2 RL-06 锚点全部属片 2。
2. 营销腿/非资金标注腿剥离：不做（含此类腿的行拒行，§2.1 已知限制）；待未来契约修订。
3. provider funding/status token 映射扩张：ordinary `STATUS_TOKEN_MAP` 不动；族内映射不外溢（D-105 边界维持）。
4. 微信侧信用路由（契约 §2.3 负证据维持）、分期付款未来扣款计划、利息/手续费/逾期费、仅资产腿退款、信用借还族其余形态、拆单/亲友代付：维持拒行或 future_rule（契约 §8）。
5. 共享负债账户映射（D-053 迁移配置）、默认负债账户：不进产品核心。
6. P4-08 evidence link/reconciliation 写入语义、产品 Clock/随机 ID、整文件保留、UI：不做。

## 8. 验证顺序

聚焦 application/data 新增与受影响 tests → migration verifier（fresh = migrated）→ 三 JVM 模块（domain/application/data）→ Android 编译 → Python 全套 → `project_docs` → 全量 `jvmTest` → trace 清理后交付（评审、独立 verifier 与完整受影响套件按 D-106 §9 片授权门执行）。
