# P4-06 片 2（RL-06 混合支付激活）实施规格

**Status:** proposal（draft；2026-08-22 起草，待独立规格评审闭环与用户批准后转 approved）

本规格按 D-106 契约（`docs/specs/2026-08-22-p4-06-credit-mixed-payment-contract-design.md`，Status: approved）§9 :154 片 2 授权范围起草；前置 = 片 1 已合入（D-107，merge `7cf9b79`）。粒度与密度先例为片 1 规格（`docs/specs/2026-08-22-p4-06-slice1-credit-implementation-design.md`）。本批交付混合支付路由启用、多腿 decision snapshot 确认、`mixed_payment` 关联组产品表行为、缺腿资料不足路径与 `constraint_solved` 反推建议边界、§7.2/§7.3 锚点验收（契约 §9 :154），并登记契约 §9 :156 要求的全部实施批事项。任何超出 D-106 的默认行为均退回契约修订，不由实现静默发明（D-096 纪律）。

## 1. 目的与边界

本批激活片 1 建而不用的一切混合结构（片 1 §1 :11「片 2 零 schema 变更」承诺的兑现）：解析层 6d 分支由一律拒行改为接受恰 1 资产腿 + 1 信用腿的支出行；spine/store 确认路径启用 `MixedPaymentFlow` 决策形状、决策快照腿金额列与 posting 三分录门；确认事务内启用 `mixed_payment_group` 两表；登记 `constraint_solved` 候选级建议边界（本批不产出建议，§2 裁决 4）。

**零 schema 变更声明（冻结）与验证方式**：

- 不新增任何 `.sqm`（无 `25.sqm`）；schema 版本钉 v25，`Ledger.sq` 全部 DDL（含 `mixed_payment_group`/`mixed_payment_group_leg`/`import_candidate_payment_profile` 及其触发器，`Ledger.sq:7546-7620`）零字节改动——本批对既有触发器（`Ledger.sq:7597-7615`，与 `24.sqm:306-325` 同文本）只消费不修改。
- `Ledger.sq` 唯一允许的改动 = 新增恰两个命名查询 `insertMixedPaymentGroup`/`insertMixedPaymentGroupLeg`（§4.3）。**命名查询是 SQLDelight 查询目录，不是 DDL**：不创建/修改任何 schema 对象，不参与 migration verifier 的 fresh=migrated 结构比对。此区分在此显式登记，评审按「DDL 零改动 + 查询仅两条 insert」检查。
- 验证方式：迁移 verifier 与 `LedgerDatabaseMigrationTest` 原样通过（fresh v25 = migrated v25，无任何迁移输入变化）；`docs/migrations` 注册表与本批无交集；review 门附 DDL 区段 diff 为空的检查项。

本批修改的既有测试面（契约强制的行为变化，非回归）：`AlipayCsvParserCreditJvmTest.kt:256`（Matrix 4 row 0「混合腿一律拒行」断言）翻转为接受断言（§5.1）；`P406CreditFullStateOracleTest.kt` 的 Expected 扩展（§5.2）。除此之外片 1 全部断言逐值不变。

## 2. 裁决（主代理已裁决的四项缺口；各配替代方案与风险）

### 裁决 1：混合候选初始状态 = intake 即 pending_confirmation；「腿金额不完整」由确认门承载

**裁决**：行事实完整（总额 + 腿 token 集合 + 方向 + 成功状态）的混合行，intake 即 `pending_confirmation`（与片 1 §5 :191 的 v3 通用规则一致）；「腿金额不完整」不进入候选状态，而由确认门承载——决策字段腿金额缺失（`MixedPaymentFlow` 两腿金额为 null，§4.1）→ `SPINE_CANDIDATE_INCOMPLETE` 拒确认、候选保持 `pending_confirmation`、零写入、claim 回滚可重试。

**依据**：契约 §4 :78（候选默认 `pending_confirmation`）与 :79-80（确认门要求全部腿金额完整；缺腿保持资料不足、补全后可再确认、路径幂等）的唯一自洽读法。支付宝列 7 是「机构名(####)」组合 token，无腿金额（契约 §2.1 :23 取证；片 1 §2.1 :23-28 提取算法只产出 token 集合），解析层永远无法提供腿金额；若把「腿金额不完整」读入候选初始状态，则每个合法混合候选都永久停在资料不足、永不可确认，契约 §7.2 :118 锚点（确认后恰一套三分录）无法验收。故「缺腿」只能落在决策字段层。

**替代方案（否决）**：混合候选 intake 即 `incomplete`，直到某来源提供腿金额——与 :118 锚点验收直接矛盾（不存在可确认路径），且把「行事实完整」与「决策资料不足」两种语义压进同一候选状态。

**替代方案（否决）**：候选状态新增第三态（如 legs_pending）承载腿金额缺失——违反契约 §6 :101-102「两片合计只允许一次版本推进」（状态枚举列无加性扩展空间），且与契约 §7.3 :125 行 2「候选待确认」矩阵冲突。

**风险**：`SPINE_CANDIDATE_INCOMPLETE` 诊断码语义扩频（候选状态门 `SqlDelightImportSpineStore.kt:336-340` 与本批腿完整性门共用一码）。登记理由：两者同族——资料不足、零写入、补全后可重试（契约 :80）；message 不稳定、不比较（片 1 §2.4 纪律），code 复用不产生断言面冲突。oracle 以「拒确认后候选状态行仍为 pending_confirmation + 零正式写入 + 补全重试成功」固化语义（§5.2）。

### 裁决 2：多腿构成门 = 资产腿 >1 或信用腿 >1 的行解析层类型化拒行

**裁决**：去重后腿集合资产腿 >1 或信用腿 >1 的 v3 绑定行（即含信用腿的行）→ 解析层类型化拒行，fail-closed、零写入（对齐片 1 §2.3 3d :75 还款多资产腿先例的「构成门先于路由」结构）。

**依据**：领域 `createMixedPaymentExpense` 冻结恰 2 funding（`MixedPayment.kt:108` funding.size != 2 拒绝；`:144-146` 恰 ASSET+LIABILITY 账户种类集合）且本批禁改领域（§4.2）；schema 层 `mixed_payment_group_complete` 触发器冻结恰 1 asset + 1 liability 腿求和等于总额（`24.sqm:306-320`）；profile 形状冻结双腿 token（`ImportSpine.kt:63-66`、`Ledger.sq:7560` CHECK）；契约唯一混合锚点为 1+1（§7.2 :118），>2 腿形态无锚点样本。

**替代方案（否决）**：接受多腿候选、确认时由用户把 N 腿合并为两腿金额——把「合并」发明为未冻结的经济语义（D-096 纪律），且 profile/快照形状无处承载 N 腿。

**替代方案（否决）**：降级为 `valid_incomplete`（状态 raw 保留）——构成不合法是行形态错误而非可补资料，与状态门「非成功 token → raw + unresolved」（契约 §2.2 :47）语义不同；拒行零写入有片 1 6b/3d 先例。

**风险**：白名单未来扩张（新增资产/信用 token）后，真实多腿行将落在本门拒行。登记为已知限制：扩张时必须经契约修订重估本门（§6），实现不得静默放行。

### 裁决 3：诊断码归宿 = 保留 `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED`，语义收窄为「混合腿构成不合法（非恰 1 资产 + 1 信用）」

**裁决**：不新增诊断码；`SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED`（片 1 §2.4 :95 冻结）语义由「混合腿一律拒行」（片 1 §2.3 6d :83 片间状态）收窄登记为「混合腿构成不合法（非恰 1 资产腿 + 1 信用腿）」的类型化拒行。判定顺序中的精确位置：构成门位于 6d 内部——白名单门（6b）之后、混合源激活之前；6d 激活接受恰 1+1，构成不合法先于此拒行（§3.1 冻结完整顺序）。片 1 的 6d 一律拒行语义随本批解除（既有断言翻转见 §1/§5.1）。

**依据**：契约 §2.2 :49 明文把判定顺序细化与诊断码归宿留实施批规格；契约 §2.2 行 7（:39）只冻结「资产腿+信用腿 → 混合支付源」的族归宿，构成门属实施批登记范围。

**替代方案（否决）**：新增第七个 `SPINE_ALIPAY_*` 码——片 1 已冻结本码，同批两码表达同一「构成不合法」族稀释诊断面，零收益。

**替代方案（否决）**：复用 `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG`——腿 token 本身在白名单内（KNOWN），拒行原因是构成而非 token 未知；且 3d 多资产腿门用该码是片 1 已冻结行为（不可改），6d 复用会让两族码语义进一步混淆。两门异码 asymmetry 在 §3.3 显式登记。

**风险**：码语义跨片变化；以 Matrix 4 断言翻转（§5.1）+ 本节登记 + D-108 决定文本三处显式留痕，防止后续读片 1 规格者误用旧语义。

### 裁决 4：`constraint_solved` 反推建议 = 本批只冻结候选级建议边界，登记已知限制「本批不产出建议」

**裁决**：冻结建议边界（约束后续批）：建议必须以候选级建议呈现、携带 provenance 与 confidence、永不自动入账、不产生任何正式效果、不得回写来源事实（契约 §4 :81；D-097 事实分层）。同时登记已知限制：本批不产出任何建议——(i) 总额单约束下分拆解不唯一（12.40 = 3.60 + 8.80 = 5.00 + 7.40 = …），无来源可推理出腿金额；(ii) 零 schema 变更边界（契约 §6 :101-102）下无表可持久化建议。产出留待有可推理来源（如账单腿金额来源、对账端证据）的独立批。

**替代方案（否决）**：以启发式（对半、比例）产出建议——非唯一解的 confidence 语义无法冻结，且无处落盘（零 schema）。

**替代方案（否决）**：扩 schema 建议表——直接违反契约 §6 :102「两片实施合计只允许这一次版本推进」。

**风险**：契约 §7.4 :137 引用的外部验收点「占位释放为最终有效分录并保留独立推断证据」中「独立推断证据」部分在本批后仍无产出物。本批以「恰一套最终分录 + 一行来源 evidence」承接「占位释放」（契约 :82 禁止双记由确认路径保证），推断证据产出义务显式登记给后续批（§6）。

## 3. 解析层：6d 激活、构成门与判定顺序

### 3.1 判定顺序（完整冻结；仅 6d/6e 内部变化，0-5 与 6a-6c/6f/6g 不变）

片 1 §2.3 冻结的顺序 0-5（结构校验、退款、投资理财、信用借还、拒绝分类、未知分类）与 6a（空列 7）、6b（白名单门）、6c（仅资产腿 → ordinary v1，多资产腿亦维持，契约 §2.2 行 5 :37「仅资产腿」不设构成门）、6f（`收入` + 任一信用腿 → `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED`）、6g（`不计收支`/未映射方向 → ordinary v1 A-04，腿 token 零持久化）全部不变。**白名单全清单重述**（契约 §9 :156 字面；本批零扩张）：信用腿 = {`花呗`}；资产腿 = {`余额宝`、`账户余额`、`余额`、`招商银行储蓄卡`}（均为括注剥离后的归一化 token；`####` 为 4 位合成数字掩码占位记法）。变化仅两处：

- **6d（支出方向 + 混合腿）改写**（现实现 `AlipayCsvParser.kt:151-157`）：
  1. 前提不变：腿门（6b）已通过、方向已解析为 `out`、资产腿 ≥1 且信用腿 ≥1（`PaymentLegs` 两集合均非空）。
  2. **构成门（新增，先于激活）**：去重后 `assetTokens.size > 1 || creditTokens.size > 1` → `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` 拒行（裁决 2/3）。
  3. **激活（恰 1 资产腿 + 1 信用腿）** → `parseMixedPaymentRow`（§3.2）产出 `MIXED_PAYMENT_SOURCE` 候选。
- **6e（恰信用腿）收紧**：显式冻结为 `creditTokens.size == 1 && assetTokens.isEmpty()`；`creditTokens.size > 1` → 同码 `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` 拒行。现状 `AlipayCsvParser.kt:329` 以 `single()` 隐式恰一（>1 会抛异常而非类型化拒行），本批升格为显式门；当前白名单信用集 = {`花呗`}（片 1 §2.1 :34）使该分支结构性不可达，登记为白名单扩张防御（fail-closed，不抛异常）。**同款已知限制登记**：1c 退款变体 profile 构造存在同款隐式 `single()`（`AlipayCsvParser.kt:245`）——片 1 冻结行为本批不改（1c 只接受恰信用腿退款、其余形态拒行；信用 token >1 同属结构性不可达的防御缺口）；白名单扩张新增信用 token 时，6e 与 1c 两处须一并升格为显式类型化拒行（1c 的归宿变化属未来契约修订，本批不动其行为）。

**1d 混合腿退款维持**：退款标记 + 资产+信用混合腿 → `SPINE_ALIPAY_REFUND_UNSUPPORTED` 拒行（片 1 §2.3 1d :69、`AlipayCsvParser.kt:235-237`），混合退款分录形状未冻结（§6）。**契约字面偏差登记**：契约 §2.2 行 1（:33）「腿种类含信用腿」按字面包含混合行（混合行含信用腿），片 1 起实施冻结为「恰信用腿（≥1 信用腿、0 资产腿）」，混合腿退款走 1d 拒行；偏差原因 = 混合退款无契约分录形状（§3.3.3 只冻结单负债腿退款），fail-closed 优先。本批维持该偏差并在此显式登记（不改契约文本；若未来冻结混合退款形状，须经契约修订）。

### 3.2 `parseMixedPaymentRow`（新增，仿 `parseCreditExpenseRow` :315-347 形态）

- 金额 = 列 6 总额（`parseAmount`，CNY/precision=2）；时间 = 列 0（`parseTime`）；方向 = 既有 `支出`→`out` 映射；全部复用 P4-05 冻结解析常数（片 1 §2.3 末段纪律）。
- 状态门（契约 §2.2 :47）：混合支付源成功 token = `交易成功` → 族内映射 `settled`、`VALID_COMPLETE`；其余状态 token（含 `交易关闭`）→ 状态 raw 保留 + unresolved → `VALID_INCOMPLETE`，不可确认、零拒行升级。
- profile：`ImportPaymentVariant.MIXED_PAYMENT` + `assetLegKindToken`/`creditLegKindToken` = 两归一化 token（`importPaymentProfileShapeValid` 双腿非空形状 `ImportSpine.kt:76-78` 已冻结，本批首次由解析层真实产出）。
- intake 状态：`VALID_COMPLETE` + `SETTLED` → `pending_confirmation`（裁决 1）；`VALID_INCOMPLETE` → `incomplete`。funding 中继 `SETTLED`/`legacy-settled-v1` 不变。spine intake 侧零代码变更：kind→candidate 映射（`SqlDelightImportSpineStore.kt:153`）、profile 同事务写入（:164-170）、指纹 profile 字段（`ImportContentFingerprint.kt:30/:40`）均已在片 1 建成并对 mixed 形状生效。

### 3.3 诊断码登记（无新增码）

| code | 本批后语义 | 位置 |
| --- | --- | --- |
| `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` | 混合腿构成不合法（非恰 1 资产腿 + 1 信用腿；含 6e 防御分支）的类型化拒行 | 6d 构成门 / 6e 防御门 |
| `SPINE_ALIPAY_REFUND_UNSUPPORTED` | 维持片 1（含混合腿退款，§3.1 偏差登记） | 1b/1d |
| `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED` / `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG` / `SPINE_ALIPAY_UNSUPPORTED_TX_TYPE` | 维持片 1 语义 | 6f / 6b/3d / 3b/3c/4 |

异码登记：3d 还款多资产腿门用 `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG`（片 1 :75 已冻结行为，不可改）；6d 构成门用收窄后的 `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED`（裁决 3）。两门同构异码，语义以本表为准。severity/scope/message 纪律维持片 1 §2.4（不新增值域、message 不稳定、不透出腿原文/掩码）。

## 4. 应用与 store：确认路径、group 写入、幂等与 evidence 基数

### 4.1 决策字段形状 `MixedPaymentFlow`（`ImportConfirmDecisionFields` 新实现）

```
MixedPaymentFlow(
  categoryId: CategoryId,                    // 用户确认的二级支出分类（显式）
  assetAccountId: AccountId,                 // 资产腿账户（显式，D-032 手工两端先例）
  creditLiabilityAccountId: AccountId,       // 信用负债账户（显式，契约 §3.2 持有前置）
  assetLegMinor: Long?,                      // 资产腿精确金额；null = 决策资料不足（裁决 1）
  creditLegMinor: Long?,                     // 信用腿精确金额；null = 决策资料不足
)
```

三个账户/分类字段非空（账户一律用户显式指定，候选不携带猜测账户，契约 §3.2 :64-67）；两腿金额可空——null 是「腿金额不完整」在决策字段层的承载（裁决 1；解析层永远无法提供，支付宝列 7 无金额）。费用合计约束（= 资产腿 + 负债腿合计，契约 §4 :79）不在形状层硬编码，由领域工厂算术校验执行（§4.2）。

### 4.2 确认门与正式化工厂（`commitOnce` 顺序冻结）

门顺序（未列步骤与片 1 一致，引用行号为现状）：

1. claim/replay 分流（:315-317）→ 决策=confirm 且字段非空（:319-325）→ 候选状态门（:335-347：`incomplete` → `SPINE_CANDIDATE_INCOMPLETE`；`pending_confirmation` 放行）——均不变。
2. **kind 门扩展（替换片 1 防御）**：删除 `candidateKind == "mixed_payment"` 一律 `SPINE_DECISION_KIND_MISMATCH` 的防御分支（:351-360；**登记该防御的解除**——片 1 注释明言「mixed_payment 片 1 结构性不可能、防御性 mismatch」，本批使其可能故替换为正门）；替换为：`mixed_payment` ⇒ 决策字段必须 `MixedPaymentFlow`，否则 `SPINE_DECISION_KIND_MISMATCH`（与 :379-400 既有 when 合流）。既有五 kind 门不变。
3. content hash 门（:410-416）、P4-07 `CONFIRMED_DUPLICATE` 阻断（:420-428）、evidence 存在性（:429-437）——不变，对 mixed 原样生效。
4. **腿完整性门（新增，仅 mixed）**：`assetLegMinor == null || creditLegMinor == null` → `SPINE_CANDIDATE_INCOMPLETE` 拒确认、零写入、claim 回滚可重试、候选保持 `pending_confirmation`（裁决 1）。位置在 kind 门之后、posting 门之前（决策形状先配对、再查资料完整性）。
5. **posting 数量门按 kind 扩展**：`mixed_payment` ⇒ `postingIds.size == 3`；其余全部 kind 维持 `== 2`（:451-460 现门 `!= 2` 扩为 kind 感知；违例码维持 `SPINE_REFERENCE_INTEGRITY_VIOLATION`，工厂零调用纪律不变）。
6. **正式化工厂**：新增 `MixedPaymentFlowFormalFactory`（应用层新类，仿 `CreditFlowFormalFactory` 形态；`ImportCommitIds.formalIds.postingIds` 映射：`[0]` = 费用、`[1]` = 资产腿、`[2]` = 信用腿 → `MixedPaymentExpenseIds(transactionId, versionId, postingSetId, expensePostingId, fundingPostingIds)`，`MixedPayment.kt:13-19`）调用**既有** `createMixedPaymentExpense`（`MixedPayment.kt:93-165`，**复用勿改领域**；片 1 已建成并冻结）。命令映射：`funding = [FundingComponent(assetAccountId, assetLeg), FundingComponent(creditLiabilityAccountId, creditLeg)]`（顺序即 posting 顺序），`total = resolved.amountMinor`（来源总额），`times = collapsed(occurredAt)`，`explicitConfirmedAt` 由请求显式携带（无产品 Clock）。工厂对 null 腿防御性返回 `DomainResult.Failure`（不抛异常；正常路径由步骤 4 先行拦截）。
7. 领域算术/账户校验归属（违例 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入、claim 回滚可重试）：腿 ≤0（`FundingLegMustBePositive`）、腿和 ≠ 总额（`FundingTotalMustEqualExpense`）、双腿同账户（`DuplicateFundingAccount`）、账户非 real/未持有/种类非 ASSET/LIABILITY、账本/币种不符、上溢（`MixedPayment.kt:103-150` 既有校验，全部复用）。负债账户持有前置与片 1 §3.3 :145 同一门。正式产物：`TransactionKind.EXPENSE` 恰 3 分录（费用 `+total`、资产 `−assetLeg`、信用 `−creditLeg`，posting role `EXPENSE`/`MIXED_EXPENSE_ASSET_FUNDING`/`MIXED_EXPENSE_CREDIT_FUNDING`，`MixedPayment.kt:152-160`）；报告效应 `consumption=total, ordinaryExpense=total, cashOutflow=assetLeg, income=0, netWorth=−total`（:162-164；契约 §7.2 :118 算例）。

### 4.3 决策快照与 `mixed_payment_group` 写入（同一确认事务）

- **决策快照**：`:481-497` 的列映射 when 增加 `MixedPaymentFlow` 分支（`category_id`/`asset_account_id`/`credit_liability_account_id`）；**`:516-517` 的 `asset_leg_minor = null, credit_leg_minor = null` 硬编码替换为决策字段两腿金额**（此时步骤 4 已保证非空）。写入形状满足片 1 §4 XOR CHECK 的混合形状（`category_id`+`asset_account_id`+`credit_liability_account_id`+两腿金额非空、其余 NULL，`24.sqm` 快照重建段；片 1 :170）。reject 路径 `:642-643` 维持全 NULL（reject 无腿金额语义）。
- **group 写入（启用两表，首个写者）**：在 `persistFormal`（:479）成功后、决策快照写入前，同事务写入：
  - `group_id` 确定性派生：`"group-" + formalIds.transactionId.value`（片 1 提交批次 id 模式：同一批次 token 派生 `tx-`/`confirmation-`/`status-`/`posting-` 前缀，本批追加 `group-` 前缀；无新 id 源、无随机、无 Clock；测试形态 `group-tx-{prefix}`）。
  - **腿先头后写序**（`24.sqm:321-325` `mixed_payment_group_leg_before_head` 触发器冻结；腿表 FK DEFERRABLE INITIALLY DEFERRED `Ledger.sq:7586`）：先插两腿行 `leg_index = 1`（`leg_class = 'asset'`，`account_id` = `assetAccountId`，`amount_minor` = `assetLegMinor`）与 `leg_index = 2`（`leg_class = 'liability'`，`account_id` = `creditLiabilityAccountId`，`amount_minor` = `creditLegMinor`），再插头行（`candidate_id`/`transaction_id`/`request_id` FK、`total_minor` = 来源总额、`generated_at` = 决策 `explicitConfirmedAt`）。头行 INSERT 即触发 `mixed_payment_group_complete`（`24.sqm:306-320`）完成性校验（恰 1+1、求和 = 总额）——领域已保证，触发器兜底。
  - 两表 update/delete 守卫触发器（`Ledger.sq:7590-7593`）append-only 不变。
  - `Ledger.sq` 新增恰两个命名查询：`insertMixedPaymentGroup`、`insertMixedPaymentGroupLeg`（§1 零 schema 声明：查询非 DDL；不新增读查询——replay 走 receipt，oracle 断言走测试 raw SQL，片 1 oracle `:464-465` 同模式）。

### 4.4 幂等与 replay

- **confirm replay**（`resolveConfirm` :706-738）：等价比较链（:725-736）扩展共五处，与腿列翻转同批实施——
  1. `:734-735` 腿列 null 断言**翻转为值比较**：`stored.asset_leg_minor == (fields as? MixedPaymentFlow)?.assetLegMinor && stored.credit_leg_minor == (fields as? MixedPaymentFlow)?.creditLegMinor`（mixed ⇒ 比较两腿值；其余形状 ⇒ 仍断言 null）。
  2. `:727` `categoryDecisionValue`（辅助 `:909-914`，现状三形状 else ⇒ null）加 `MixedPaymentFlow -> fields.categoryId.value` 分支——不扩展则混合重放求值 null 而 `stored.category_id` 非空 ⇒ 等价判定失败 ⇒ `requestIdentityConflict`（:738），违反契约 §4 :83「相同请求……重放返回原稳定结果」。
  3. `:731` `creditLiabilityDecisionValue`（辅助 `:902-907`，同款三形状 else ⇒ null）加 `MixedPaymentFlow -> fields.creditLiabilityAccountId.value` 分支（理由同上，`stored.credit_liability_account_id` 非空）。
  4. `:732` `asset_account_id` 的 `as? CreditRepaymentFlow` 安全转型比较扩展为同时覆盖 `MixedPaymentFlow.assetAccountId.value`（如 `(fields as? CreditRepaymentFlow)?.assetAccountId?.value ?: (fields as? MixedPaymentFlow)?.assetAccountId?.value` 或等价辅助；理由同上，`stored.asset_account_id` 非空）。
  5. 无需改动的三处一并登记：`:728-730`（funding/from/to，类型封闭转型对 mixed 求值 null）与 `:733`（original_transaction_id 同）——混合快照按 XOR CHECK 对应列均为 NULL，null == null 通过，逐值不变。
  扩展纪律：mixed ⇒ 比较决策值；其余形状 ⇒ 两辅助函数与转型比较的求值结果与片 1 逐值不变（辅助仅加分支、不改既有分支）。**登记**：片 1 在两处 replay 比较冻结了腿列 null 断言，本批仅翻转/扩展 confirm 路径；reject 路径（:771-772，含腿列 null 断言）维持不变（reject 快照全列恒 NULL）。重放命中返回原 receipt，零新写入（含零 group 写入——claim 未赢得则永远到不了写入段）。
- **intake replay / 等价性**：profile 三字段比较与指纹成员已在片 1 冻结（片 1 §3.2 :129；`ImportContentFingerprint.kt:30/:40`），mixed profile 走同一字段，**本批零扩展**——登记为「片 2 无需翻转」项。
- **失败注入回滚**：既有 `CONFIRM_AFTER_FORMAL` 注入点（:480）天然覆盖 group 写入（同事务其后于 persistFormal）——oracle 断言注入后 group 两表零行、重试成功全状态等价（§5.2）。P4-07 重复机制对 mixed 行原样生效（kind 无关 persisted-facts 元组比较）。

### 4.5 evidence 基数（契约 §5 :92 首个 1:N 场景的固化）

一行来源 evidence ↔ 一笔交易（不拆单）；交易内 evidence:posting = 1:2——同一 evidence 关联全部真实资金腿（资产腿 + 负债腿），费用分录不参与（:96）。本批不写任何 P4-08 evidence link/reconciliation 表（D-103 边界维持），基数以 oracle 断言形式固化（§5.2）：恰 1 行 `import_evidence`；正式交易恰 3 分录中恰 2 条 real-account 资金分录且账户集合 = 决策 `{assetAccountId, creditLiabilityAccountId}`；费用分录挂费用类账户（非 real）；P4-08 链接/对账表保持空。

## 5. 测试与验收计划

### 5.1 解析层（`AlipayCsvParserCreditJvmTest`）

1. **Matrix 4 row 0 翻转**（`余额宝&花呗` + `支出` + `交易成功`）：断言接受——`MIXED_PAYMENT_SOURCE`、facts（方向 `out`、状态 `settled`、总额）、`VALID_COMPLETE`、profile（`MIXED_PAYMENT` + 双 token `余额宝`/`花呗`）、零 diagnostics。
2. **构成不合法拒行（新增用例）**：`余额宝&招商银行储蓄卡(####)&花呗`（2 资产 + 1 信用）、`余额宝&账户余额&花呗`、`招商银行储蓄卡(####)&花呗`（单资产单信用但括注剥离后仍 1+1，作对照接受或并入 1）——多资产腿混合行 → `SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED` + 零 source/candidate/profile；含两个不同信用 token 的用例因当前白名单不可合成，以 6e 防御分支单元断言（`creditTokens.size > 1` 路径）覆盖。
3. **Matrix 4 rows 1-3 维持**：`不计收支` 混合腿 → ordinary v1 A-04（方向 raw、`VALID_INCOMPLETE`、无 profile）；`收入` + 混合腿/纯信用腿 → `SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED`。
4. **混合状态门负路径**：混合腿 + `支出` + `交易关闭` → `MIXED_PAYMENT_SOURCE` + 状态 raw + unresolved + `VALID_INCOMPLETE`（不可确认、零拒行升级）。
5. **1d 维持**：退款标记 + `余额宝&花呗` → `SPINE_ALIPAY_REFUND_UNSUPPORTED`（片 1 断言原样）；恰信用腿退款 → 退款变体维持。

### 5.2 全状态 oracle（扩展 `P406CreditFullStateOracleTest`；不新建类——复用其 Executor/catalog/`captureFullState`/`assertFullState`/Expected 机制，新建类将分叉约四百行脚手架）

覆盖组（金额全为匿名代表值；fixtures 全合成、来源中立，无真实尾号/括注原文落盘）：

1. **混合全状态正路径**（契约 §7.3 :125 行 2）：intake（`交易成功` 混合行）→ 断言 source/evidence/candidate（`mixed_payment`）/profile/requirement/状态 `pending_confirmation`；完整决策确认 → 恰 3 分录（费用 `+total`/资产 `−assetLeg`/信用 `−creditLeg`）、`EXPENSE` 交易、决策快照混合形状（含两腿金额列）、`mixed_payment_group` 头行 + 两腿行（`leg_index` 1=asset/2=liability、`total_minor`、`generated_at` = `explicitConfirmedAt`、三 FK）、confirmation/receipt、余额与报告效应（cashOut=assetLeg、consumption=total、netWorth=−total）、P4-08 表全空。
2. **§7.2 锚点匿名化合成 fixture**（`GL-F6107E0842D3` → 合成行，真实注册值不落盘）：混合 12.40 = 3.60 + 8.80 → 费用 `+1240` / 资产 `−360` / 负债 `−880`（欠款增加 8.80）；购买日现金流出 3.60、消费 12.40、净资产变化 `−1240`；恰一套最终分录、无第二套分录/交易（契约 :82/:118）。
3. **缺腿金额路径**（契约 §7.3 :126 行 3 × 裁决 1）：`MixedPaymentFlow` 任一腿 null → `SPINE_CANDIDATE_INCOMPLETE`、候选仍 `pending_confirmation`、零正式写入/零快照/零 group；补全后重试确认成功（幂等路径，契约 :80「补全后可再确认」）。
4. **重试与 replay**：失败注入（`CONFIRM_AFTER_FORMAL`）→ 回滚含 group 两表与快照 → 重试成功全状态等价；确认后同 request 重放返回原 receipt 零新写入（含 group）；raw identity 重放 unchanged；`SPINE_STALE_FINGERPRINT`/`SPINE_CANDIDATE_NOT_PENDING` 原样适用。
5. **evidence 1:2 断言**（§4.5）：恰 1 evidence 行、2 real 资金分录账户集合 = 决策双账户、费用分录非 real、P4-08 链接/对账空断言（含片 1 补测的显式空断言面维持）。
6. **确认负路径**：kind/形状错配（mixed 候选 + 非 `MixedPaymentFlow`；`MixedPaymentFlow` + 非 mixed 候选）→ `SPINE_DECISION_KIND_MISMATCH`（登记片 1 防御解除后的正门）；腿和 ≠ 总额 / 腿 ≤0 / 双腿同账户 / 未持有或非 `LIABILITY` 负债账户 / 跨账本币种不符 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入、候选保持待确认、claim 可重试；posting 门（3 id 缺一）→ `SPINE_REFERENCE_INTEGRITY_VIOLATION` 工厂零调用。
7. **重复与 §7.3 矩阵锚点**：两条等值混合行 → D-104 exact-tuple duplicate candidate 追加、无第二笔交易（:132 行 9）；`constraint_solved` 建议边界零产出断言（:133 行 10——断言无任何建议产物、无额外写入；已知限制见裁决 4）；行 6（未指定已持有负债账户）由负路径 6 承接；行 7（白名单外腿种类 token）由片 1 既有白名单拒行断言经 §5.2.9 回归承接（§5.1.2 新用例的腿 token 全在白名单内、触发的是构成门，不覆盖行 7）；行 8（收入方向 + 信用腿）由 §5.1.3 承接。
8. **期望辅助扩展**：`Expected.formal()`（`:632-655`，两腿）保持既有调用点不变，新增 `formal3`（posting 0 = 费用 `+total`、1 = 资产 `−assetLeg`、2 = 信用 `−creditLeg`，与领域 typed 构序一致 `MixedPayment.kt:152-160`）；`Expected` 增加 group 头/腿行构建器（替换 `:692-693` 的 `emptyList()` 硬编码）；`captureFullState` 的 group 两表 select（`:464-465`）扩列（头：candidate/transaction/request/total/generated_at；腿：leg_class/account/amount）。
9. **回归**：片 1 全部 oracle 断言（除 §1 登记的 Matrix 4 row 0 翻转）、ordinary/transfer/P4-05/P4-07/P4-08 共存断言逐值不变；迁移面 fresh v25、v1→v25、v24→v25 populated、reopen、失败原子回滚**无变化回归**（版本钉 v25、无新 `.sqm`、`Ledger.sq` DDL 零改动由迁移测试原样通过证明）。

### 5.3 评审门（对齐 D-107 `DECISIONS.md:1706` 实施门）

独立规格评审（findings 修复闭环）→ 独立质量评审 → distinct verifier 实际执行聚焦与全量 → 主代理全量受影响套件（`:ledger-data:jvmTest` 全类 + `:ledger-application:jvmTest` + Android 编译 + Python 全套）后方可接受合并。

## 6. 排除项

1. **D-106 §8 全部适用项重申**（:139-147）：分期付款 future_rule；微信侧信用负证据维持；拆单支付与亲友代付未分配；P4-08 matcher 写入之外的对账新语义不做（D-103 边界）；无产品 Clock/随机 ID；利息/手续费/逾期费 future_rule；仅资产腿退款与信用借还族其余形态维持拒行。
2. **D-107 边界适用项重申**：营销腿/非资金标注腿剥离不做（含此类腿的行拒行，片 1 已知限制维持）；provider token 映射不扩张（ordinary `STATUS_TOKEN_MAP` 不动、族内映射不外溢）；共享/默认负债账户映射不进产品核心（D-053）。
3. **本批新增排除**：`constraint_solved` 建议产出（只冻结边界，裁决 4）；多腿 >2 形态（构成门拒行，裁决 2）；混合腿退款分录形状（维持 1d 拒行与契约 :33 字面偏差登记，§3.1）；片 1 `mixed_payment` kind 防御解除以外的 store intake 侧改动（零变更，§3.2）；任何 schema/DDL 改动（§1 零 schema 声明）。

## 7. 验证顺序

聚焦新增/受影响测试（`AlipayCsvParserCreditJvmTest` + `P406CreditFullStateOracleTest`）→ 迁移 verifier（fresh = migrated，无新迁移输入）→ 三 JVM 模块（domain 零改动、application、data）→ Android 编译 → Python 全套 → `project_docs` → 全量 `jvmTest` → trace 清理后交付（评审、独立 verifier 与完整受影响套件按 §5.3 执行；主代理拥有 Git 状态与合并）。
