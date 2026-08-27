# 黄金测试

## 目标

黄金测试冻结已经确认的账务行为，使 Python 基线、后续 Kotlin Multiplatform 核心、导入器和对账引擎能够对同一输入逐项比较。黄金答案必须先经过规则复核再冻结，实现代码不得反向修改答案以迁就现有结果。

## 两层验收

### 第一层：公开匿名规则场景

使用完全合成的账户、金额、时间、分类和来源记录。首批固定为 12 个场景，进入仓库和持续集成，验证正式账务规则及其反例。

### 第二层：本地真实来源场景

使用不进入 Git 的真实来源资料验证解析、去重、镜像匹配和历史重建。首批固定为 8 个场景；仓库只记录匿名编号和验收维度，不记录来源哈希、真实金额、账户、商户、订单或可逆映射。

两层使用同一正式账务核心和同一输出契约。真实来源层不能用私人特例绕过公开层不变量。

## 案例级别

- `core_required`：首版公开能力必须通过，阻塞核心完成。
- `core_reserved`：规则已确认且底层必须支持，但首版普通界面可以不开放。
- `future_draft`：仅收集问题，不冻结预期结果，也不计入首版通过率。

首批公开场景和本地正式场景使用前两个正式级别。未来草案另行登记，不能伪装成已经确认的黄金答案。

## 统一案例契约

每个正式案例必须冻结以下内容：

1. 稳定场景 ID、级别、规则版本和固定测试时区。
2. 完全匿名的初始账户、余额、分类和已有交易状态。
3. 输入来源记录或手工操作，以及每项输入的稳定引用。
4. 应创建、替换、忽略或关联的正式 `Transaction`。
5. 每笔交易的全部 `Posting`，包括账户、币种、精确金额和有效状态。
6. 每个受影响账户在指定时点的余额变化与期末余额。
7. 现金流、收入、消费、预算和净资产等适用统计结果。
8. 每条真实账户分录的对账状态、证据引用和交易汇总状态。
9. 候选来源、置信度及必须由用户确认的字段或步骤。
10. 不应发生的副作用，例如重复入账、重复消费、提前扣款、证据覆盖或无痕补平。

预期结果使用结构化、可机器比较的固定格式保存。列表顺序不属于业务语义时，比较前必须按稳定 ID 排序；金额、时间、币种和版本均做精确比较。

## 首批 12 个匿名规则场景

### RG-01 普通支出

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-01.json`](../golden/rules/rg-01.json)。该答案冻结手工创建、备注修改、请求重试、独立重复记账、金额边界和分类可用性结果；完整设计见 [`RG-01 普通支出设计`](specs/2026-07-14-rg-01-normal-expense-design.md)。

资产账户 A 支付一笔普通消费并选择二级分类。验证费用与资产两条分录平衡、付款日余额和消费统计正确，备注变化不影响资金结果。

### RG-02 普通收入

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-02.json`](../golden/rules/rg-02.json)。该答案冻结工资主场景、分类改名、请求重试、输入边界，以及经用户确认的红包和项目款独立变体；完整设计见 [`RG-02 普通收入设计`](specs/2026-07-14-rg-02-normal-income-design.md)。

资产账户 A 收到一笔普通收入并选择二级分类。验证资产与收入分录平衡、收入统计只确认一次，分类改名不改变关联 ID。

### RG-03 账户互转与手续费

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-03.json`](../golden/rules/rg-03.json)。该答案冻结一对一账户互转、手续费独立入账、互转本金不计入收入、消费和对外现金流、导入待确认、镜像证据合并和组合转账范围边界；完整设计见 [`RG-03 账户互转与手续费设计`](specs/2026-07-15-rg-03-account-transfer-fee-design.md)。

资产账户 A 向资产账户 B 做一对一账户互转，到账金额小于转出金额。验证本金不进入收支统计、手续费独立进入费用、两端账户分录与费用共同平衡。组合转账不属于 `RG-03` v1 的 `core_required` 必测范围，保留为 `future_draft` 能力。

### RG-04 混合支付

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-04.json`](../golden/rules/rg-04.json)。该答案冻结手工混合支付、信用本金还款、导入待确认、分录级对账、镜像证据合并、`mixed_payment` 关联组和幂等结果；完整设计见 [`RG-04 混合支付设计`](specs/2026-07-15-rg-04-mixed-payment-design.md)。

一笔 `120.00 CNY` 消费形成费用 `+120.00 CNY`、资产 `-70.00 CNY` 和负债 `-50.00 CNY` 三条平衡分录，购买日现金流出为 `70.00 CNY`。后续信用本金还款产生 `50.00 CNY` 现金流出但不重复确认消费；导入在明确确认前保持待确认，真实账户分录独立对账，并由系统管理的 `mixed_payment` 关联组表达付款组成。

### RG-05 合并付款

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-05.json`](../golden/rules/rg-05.json)。该答案冻结导入待确认、证据合并、`merged_payment` 关联组和幂等结果；完整设计见 [`RG-05 合并付款设计`](specs/2026-07-15-rg-05-merged-payment-design.md)。

两条独立消费记录 `40.00 CNY` 和 `60.00 CNY` 共用一条真实的 `100.00 CNY` 资产扣款，并在同一笔平衡交易和系统管理的 `merged_payment` 关联组中表达；导入在明确确认前保持待确认，证据保留共同外部扣款来源，重复导入和确认保持幂等。

### RG-06 定金与尾款

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-06.json`](../golden/rules/rg-06.json)。该答案冻结非财务建组、两笔独立付款、付款进度与履约状态分离、导入确认、精确证据绑定、对账汇总、镜像合并和幂等结果；完整设计见 [`RG-06 分阶段付款设计`](specs/2026-07-15-rg-06-staged-payment-design.md)。

固定应付总额 `300.00 CNY` 分两天支付定金 `80.00 CNY` 和尾款 `220.00 CNY`，两笔付款分别影响余额、消费和现金流。未付金额只显示待付且不提前入账；允许已履约但仍待付，完成状态不重复确认消费。

### RG-07 退款

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-07.json`](../golden/rules/rg-07.json)。该答案冻结退款状态零财务影响、实际到账独立入账、原二级分类继承、到账期报告、累计退款上限、导入确认、证据职责、分录级对账、完整状态和幂等结果；完整设计见 [`RG-07 退款设计`](specs/2026-07-16-rg-07-refund-design.md)。

原支出 `120.00 CNY` 从资产账户 A 支付，之后 `30.00 CNY` 部分退款实际进入不同的资产账户 B。申请、批准和处理中状态均不改变余额；明确确认到账后创建资产 B `+30.00 CNY` 与原二级费用账户 `-30.00 CNY` 的独立平衡退款交易。原期间消费和现金流出仍为 `120.00 CNY`，到账期消费为 `-30.00 CNY`、退款现金流入为 `30.00 CNY`、普通收入为零，累计消费成为 `90.00 CNY`。

### RG-08 借贷与结算

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-08.json`](../golden/rules/rg-08.json)。该答案冻结个人级借贷位置、借出与收款现金口径、实际利息收入、本金上限、导入确认、证据职责、完整状态和幂等结果；完整设计见 [`RG-08 借贷与结算设计`](specs/2026-07-16-rg-08-lending-settlement-design.md)。

同一往来对象从资产账户 A 借出 `100.00 CNY`，形成应收 `+100.00 CNY` 与资产 A `-100.00 CNY`。之后资产账户 B 实收 `45.00 CNY`，明确拆为本金 `40.00 CNY` 和利息 `5.00 CNY`，形成资产 B `+45.00 CNY`、应收 `-40.00 CNY` 与利息收入 `-5.00 CNY`，剩余应收本金为 `60.00 CNY`。

### RG-09 目标余额调整与替代

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-09.json`](../golden/rules/rg-09.json)。该答案冻结目标时点余额重放、明确调整确认、真实交易与解释分配分离、反向调整、部分与完全解释、证据核验、完整状态和幂等结果；完整设计见 [`RG-09 目标余额调整与替代设计`](specs/2026-07-16-rg-09-balance-adjustment-design.md)。

资产账户 A 在目标时点重放为 `100.00 CNY`，目标余额为 `130.00 CNY`，明确确认后生成差额 `+30.00 CNY` 的平衡调整。后来确认实际转入 A 的真实账户互转 `20.00 CNY`，再明确分配其解释金额后，在原目标时点创建 A `-20.00 CNY` 的关联反向调整，使历史目标继续为 `130.00 CNY`；原调整剩余 `10.00 CNY` 待解释，且普通收支、消费、预算、分类和现金流均不受调整影响。

### RG-10 储值充值与赠送

**级别：** `core_required`

**冻结版本：** `1`

机器可比较答案保存在 [`golden/rules/rg-10.json`](../golden/rules/rg-10.json)。该答案冻结实付与到账分离、赠送权益、确定性批次分配、储值消费、确认到期、既有余额接入、导入待确认、分录级对账、幂等结果和拒绝输入；完整设计见 [`RG-10 商户储值充值与消费设计`](specs/2026-07-16-rg-10-stored-value-design.md)。

储值充值实付 `1000.00 CNY`、到账 `1200.00 CNY`，其中赠送权益为 `200.00 CNY`，形成储值资产 `+1200.00 CNY`、银行资产 `-1000.00 CNY` 和赠送权益收入 `-200.00 CNY` 的平衡交易。随后确认储值消费 `300.00 CNY`，只形成费用 `+300.00 CNY` 与储值资产 `-300.00 CNY`，不产生第二条现金流；批次按商户证据或最早到期日、载入时间、稳定批次 ID 的确定性顺序分配。提醒和未确认失效不产生交易，明确确认实际到期即形成到期损失；精确账户和余额证据仅用于对储值资产分录进行对账，并非创建损失的前提；历史批次和消费保持可追溯。

用户已建立受限储值资产账户，实付金额小于到账可用余额。验证付款资产、储值资产和赠送权益收入分录平衡，后续消费只从储值资产确认一次支出。

### RG-11 一次付款按期分摊

**级别：** `core_reserved`

机器可比较 direct-v2 答案保存在 [`golden/rules/rg-11.json`](../golden/rules/rg-11.json)，状态为 `approved`；完整设计见 [`RG-11 周期性分摊设计`](specs/2026-07-18-rg-11-periodic-allocation-design.md)。

长期服务在付款日全额支付并启用按月分摊。验证付款日真实资产全额减少，未确认部分进入系统隐藏的预付资产，各期只调整预付与费用且不再次扣减真实资产。验证实际付款时间与分摊开始时间分离、月末与固定日锚点、日历连续性、按期数等分及末期尾差、不可变修订边界、按 installment 的 recognition audit link、严格统计时间版本修正、精确 returned IDs 和幂等重放。独立 rejection root 冻结 malformed/非正金额、币种、锚点、重复确认、预付余额上限及修订边界/期数错误的原子拒绝生命周期。

### RG-12 部分对账与修正重匹配

**级别：** `core_required`

混合支付的一条账户分录已有证据，另一条缺少资料；随后用户修改第二条分录金额。验证第一条匹配保持有效，第二条旧匹配历史保留并重新进入匹配，系统不自动补平差异。

Direct-v2 validation covers complete replacement posting input, append-only `reconciliation_match` history, `posting_replacement` reconciliation effects, original-period report recomputation, idempotent retry IDs, and atomic rejected attempts with frozen first-failure reason/path pairs.

## 首批 8 个本地真实来源场景

以下场景的输入和冻结答案只存在于本地测试区。公开仓库不得保存其真实来源标识或私人解释。

| 场景 | 级别 | 验收维度 |
| --- | --- | --- |
| `RL-01` | `core_required` | 单一支付来源的普通支出；来源状态、账户扣减、分类和去重 |
| `RL-02` | `core_required` | 单一支付来源的普通收入；来源方向、账户增加和收入统计 |
| `RL-03` | `core_required` | 同一平台内的资产互转；镜像记录合并与零对外收支 |
| `RL-04` | `core_required` | 跨账户一对一资产互转；两端账户余额和对账证据 |
| `RL-05` | `core_required` | 信用消费、还款和退款生命周期；负债方向、费用与原交易关联 |
| `RL-06` | `core_required` | 资产与信用账户混合支付；多分录平衡、候选依据和无重复调整 |
| `RL-07` | `core_required` | 银行侧与支付侧镜像证据；只形成一笔正式转账并合并证据 |
| `RL-08` | `core_required` | 关闭、失败与重复导入记录；零资金影响、状态解释和幂等结果 |

## P4-09 收口失败矩阵

RL-01..RL-08 维度 × 六种失败模式（F1 解析 fatal / F2 intake 注入 / F3 confirm 注入 / F4 领域拒绝 / F5 并发输家 / F6 迁移 late-stage）的覆盖登记基线（D-109 契约 D4；D-110 规格 §6.1 登记版）。格值 = 既有锚点（类.测试:行）或 P4-09 收口 oracle 新增锚点；新增锚点全部位于 `ledger-data` jvmTest 的 `P409PhaseClosureFullStateOracleTest`，F6 列零新增（逐边 late 系列已闭合，单链迁移为正路径验收）。

| RL | F1 解析 fatal | F2 intake 注入 | F3 confirm 注入 | F4 领域拒绝 | F5 并发输家 | F6 迁移 late-stage |
| --- | --- | --- | --- | --- | --- | --- |
| RL-01 | `WechatBillParserJvmTest` fatal 容器/结构族（:354-465） | `ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable`（:730，INTAKE_AFTER_CANDIDATE 点） | 同 F2（:730，CONFIRM_AFTER_FORMAL 点） | 新增 `P409PhaseClosureFullStateOracleTest.ordinaryAndIncomeConfirmDomainFailuresStayPendingZeroWrites`；既有类型化拒绝 `ImportSpineLifecycleEndToEndTest.storeRejectsLedgerMismatchedIdentityAndSnapshot`（:1052）/`winningClaimWithStaleExpectedHashRejectsWithZeroWritesAndIdentityStaysUsable`（:1082） | `ImportSpineLifecycleEndToEndTest.concurrentIntakesCommitOnceWithoutLoserResidue`（:595）/`concurrentConfirmationsCommitOnceWithoutConsumingLoserIds`（:659） | `LedgerDatabaseMigrationTest.lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows`（:596，普通行保持）+`lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows`（:701） |
| RL-02 | `AlipayCsvParserJvmTest` fatal 容器/结构族（:407-509） | `ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable`（:730，INTAKE_AFTER_CANDIDATE 点） | 同 F2（:730，CONFIRM_AFTER_FORMAL 点） | `ImportSpineLifecycleEndToEndTest.incomeDomainFailureLeavesZeroResidueAndCorrectedRetryAccepts`（:811） | `ImportSpineLifecycleEndToEndTest.concurrentIntakesCommitOnceWithoutLoserResidue`（:595）/`concurrentConfirmationsCommitOnceWithoutConsumingLoserIds`（:659） | `LedgerDatabaseMigrationTest.lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows`（:596）+`lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows`（:701，schema 筧行级） |
| RL-03 | `WechatBillParserJvmTest` fatal 容器/结构族（:354-465；行级路由 fatal 不存在：结构错 = 容器 fatal） | `ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable`（:730，INTAKE_AFTER_CANDIDATE 点） | `ImportSpineTransferEndToEndTest.executesE31E32InjectedFailuresWithFullRollbackAndCorrectedRetries`（:1316） | `ImportSpineTransferEndToEndTest.executesE17ToE28RejectsGateFailuresDomainFailuresAndKindMismatches`（:997，域失败/门失败族） | `ImportSpineTransferEndToEndTest.executesE29E30ConcurrentTransferConfirmsWithSingleWinner`（:1184） | `ImportSpineTransferEndToEndTest.executesE41LateStageMigrationFailureRollsBackCompletely`（:2079） |
| RL-04 | `AlipayCsvParserJvmTest` fatal 容器/结构族（:407-509）；行级路由形态另列 `AlipayCsvParserYuebaoTransferJvmTest`（行级类型化，非 fatal） | `ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable`（:730，INTAKE_AFTER_CANDIDATE 点） | `ImportSpineTransferEndToEndTest.executesE31E32InjectedFailuresWithFullRollbackAndCorrectedRetries`（:1316） | `ImportSpineTransferEndToEndTest.executesE17ToE28RejectsGateFailuresDomainFailuresAndKindMismatches`（:997，绑定失配向量 B01-B13 :1598） | `ImportSpineTransferEndToEndTest.executesE29E30ConcurrentTransferConfirmsWithSingleWinner`（:1184） | `ImportSpineTransferEndToEndTest.executesE41LateStageMigrationFailureRollsBackCompletely`（:2079，同 transfer kind 族） |
| RL-05 | `AlipayCsvParserJvmTest` fatal 容器/结构族（:407-509，来源容器级）；信用路由行级类型化拒行另列 `AlipayCsvParserCreditJvmTest`（行级，非 fatal） | `P406CreditFullStateOracleTest.injectedIntakeFailureAfterCandidateRollsBackAllV3RowsAndAcceptsOnRetry`（:973） | `P406CreditFullStateOracleTest.injectedConfirmFailureAfterFormalRollsBackDecisionAndAcceptsOnRetry`（:1017） | `P406CreditFullStateOracleTest.creditConfirmationNegativePathsStayPendingWithZeroWritesAndClaimRetry`（:1160） | 新增 `P409PhaseClosureFullStateOracleTest.concurrentCreditAndMixedConfirmsHaveSingleWinnerAndLoserReplay` | `LedgerDatabaseMigrationTest.lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows`（:596，信用结构回滚） |
| RL-06 | 同 RL-05（fatal = `AlipayCsvParserJvmTest` :407-509 容器级；`AlipayCsvParserCreditJvmTest` 行级类型化，非 fatal） | `P406CreditFullStateOracleTest.injectedIntakeFailureAfterCandidateRollsBackAllV3RowsAndAcceptsOnRetry`（:973） | `P406CreditFullStateOracleTest.mixedConfirmInjectionRollsBackGroupTablesAndReplayReturnsOriginalReceipt`（:1430） | `P406CreditFullStateOracleTest.mixedConfirmationNegativePathsStayPendingWithZeroWritesAndClaimRetry`（:1534）+ `MixedPaymentTest`（`ledger-domain` commonTest） | 新增 `P409PhaseClosureFullStateOracleTest.concurrentCreditAndMixedConfirmsHaveSingleWinnerAndLoserReplay` | `LedgerDatabaseMigrationTest.lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows`（:596） |
| RL-07 | 平台侧 = `WechatBillParserJvmTest`（:354-465）与 `AlipayCsvParserJvmTest`（:407-509）fatal 容器/结构族（来源侧）；银行侧真实维度 = 已登记延期（O-8 → D-099:1540 银行 parser 门仍开） | spine evidence 侧 = `ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable`（:730，evidence 随 intake 注入） | 新增 `P409PhaseClosureFullStateOracleTest.rl07PlatformSideMirrorSubsetZeroSecondTransaction`（约束驱动 confirm 失败 + 零残留 + 身份可重试 + 纠正后重试成功 + reopen 保持；触发器 = 约束而非异常注入，差异于本格登记；同构先例 `P408ReconciliationStoreTest.duplicateLinkIdConstraintFailureIsTypedRejectedWithZeroWrites` :356） | `P408ReconciliationStoreTest` 类型化拒绝族（:55-:273/:280-:526） | 新增 `P409PhaseClosureFullStateOracleTest.rl07PlatformSideMirrorSubsetZeroSecondTransaction`（并发 confirmLink 单赢家、输家类型化冲突零残留；`changedRequestRetryIsTypedConflictWithZeroWrites` :198 语义的并发形态） | `LedgerDatabaseMigrationTest.versionTwentyTwoToTwentyThreeDdlFailureRollsBackEverySharedP408Owner`（:450） |
| RL-08 | `WechatBillParserJvmTest`（:354-465）与 `AlipayCsvParserJvmTest`（:407-509）fatal 容器/结构族；行级关闭形态归正路径锚点（本行 F4 括注登记） | `P407DuplicateClosedFullStateOracleTest.matrix5cReviewFailuresLeaveZeroResidueAndRetryableIdentities`（:772，REVIEW_AFTER_SNAPSHOT 点） | `P407DuplicateClosedFullStateOracleTest.matrix5cReviewFailuresLeaveZeroResidueAndRetryableIdentities`（:772）+ `ImportSpineLifecycleEndToEndTest.p407ConfirmedDuplicateBlocksFormalFactoryAndLeavesFormalRowsUnchanged`（:1242） | `P407DuplicateClosedFullStateOracleTest.matrix7UnknownRefundAndNonSettledAmbiguityStayUnresolved`（:1009）+ `ImportSpineLifecycleEndToEndTest.p407NoFundsCreatesOnlyIncompleteSourceCandidateAndClosedDuplicate`（:1281）/`p407NoFundsCandidateRejectsConfirmedDuplicateDecisionWithZeroWrites`（:1317）；正路径关闭行覆盖 = `P409PhaseClosureFullStateOracleTest.rl08ClosedRowsAcrossKindsStayZeroFunds`（四 kind 关闭变体，正路径格登记） | `P407DuplicateClosedFullStateOracleTest.matrix5cConcurrentReviewClaimsHaveSingleWinnerAndLoserReplay`（:841）+`matrix5dConcurrentSameRequestIntakesKeepOneDuplicateRow`（:977） | `LedgerDatabaseMigrationTest.lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows`（:701，duplicate 表属 v24 对象） |

语义维度延期：P4-08 correction/successor invalidation → D-103:1639，延期至后续独立批（O-5-A；属语义维度而非失败模式列，故不设列、以本行登记）；已交付解除（D-113，2026-08-28，`a3d11bb`），测试锚点见下方附注行。

语义维度测试锚点（P4-08 projection 第二批，D-112，schema v26，merge `b8755dd`）：`P408ProjectionAuthorityStoreTest` 14 例（`ledger-data` jvmTest），含 TP-18 `tp18NonScaleTwoAuthorityStaysPendingZeroLinksAndScaleTwoControlAccepts`——两批之间非 2 位来源保持 PENDING/UNRESOLVED、零错误 link 的固化断言；`P408ProjectionSixKindMaterializationTest` 11 例（`ledger-data` jvmTest），含 `tp01Combined99Scale0To9900Scale2ConfirmLinkAccepted`——99@0→9900@2 确认 link 全链。关联维度 = RL-07 镜像/对账与 RL-03/RL-04 转账；属语义维度而非失败模式列，故不设列、以本行登记。

语义维度测试锚点（P4-08 correction/successor invalidation，D-113，schema v27，merge `a3d11bb`；即上方延期行的交付解除批）：`P408CorrectionStoreTest` 17 例（`ledger-data` jvmTest）——tp16 matcher 零自动转移回归锁、tp17 仅备注/分类/统计时间替代负路径、tp10 真并发单赢家；`P408CorrectionMigrationTest` 5 例（`ledger-data` jvmTest）——含带合法失效历史的 v26→v27 迁移等价；`P408CorrectionRequestTest` 5 例（`ledger-application` jvmTest）。属语义维度而非失败模式列，故不设列、以本行登记。

正路径括注：`P409PhaseClosureFullStateOracleTest.rl08ClosedRowsAcrossKindsStayZeroFunds` 为正路径覆盖（关闭行零资金影响 + 跨 kind 关闭变体 + 重复导入幂等），非失败注入格，其登记位置 = 上表 RL-08 行 F4 格括注。

矩阵裁决依据（八项 HOW 裁决与替代方案）与新增锚点落点汇总见 [`P4-09 阶段收口实施规格`](specs/2026-08-22-p409-phase-closure-implementation-design.md)（D-110）§2/§6；本节为后续批次引用的正式覆盖登记基线。

## P4-01 normalized source acceptance（D-097 已批准，未实现）

P4-01 是 contract-only acceptance，不是新的 Golden 工件、序列化格式或 parser 实现。以下表格定义表示中立但可执行的逻辑 oracle；具体字段名、JSON 形状、Kotlin 类型、provider、文件格式、parser 限制和持久化 schema 均不属于本合同。

### 公共逻辑投影

`NormalizationResult` 固定比较：`contract_version = 1`；`outcome = complete | partial | rejected`；normalized records 的 semantic multiset（保留 multiplicity）；diagnostics 集合。存在任一 record error 时 outcome 为 `partial`，可以有零条或多条可靠 records；只有 fatal input/container/structure failure 或无法建立可靠 record boundary 时为 `rejected`。

每条 normalized record 固定比较以下逻辑类别：

| 类别 | 逻辑值 |
| --- | --- |
| safe source location | opaque synthetic `input-1` + record ordinal；字段诊断再加 field role |
| record kind | `ordinary_flow_source` |
| completeness | `valid_complete | valid_incomplete` |
| source facts | amount、currency、occurred_time、direction、status；各自 presence 为 `absent | explicit_null | present` |
| present source fact | 匿名 source token、机械 parsed value、provenance=`source_declared + mechanical_decode` |
| derived facts | normalized_direction、normalized_status、ordinary_flow；各自包含 rule key、version 1、input roles、confidence=`exact | unresolved` |
| unresolved requirements | `unresolved_required_facts` 集合 |

`valid_complete` 要求 exact amount、currency、带 offset 的 source time、known direction 与 known status 均可靠；可靠 record 已形成但任一必要项 absent、explicit null 或 unresolved 时为 `valid_incomplete`。无效 amount/time 是 record error，不生成 record。

已知 derived 映射使用：direction=`direction_token_v1`、status=`status_token_v1`、flow=`ordinary_flow_v1`，均为 version 1、列出 input roles、confidence=`exact`；未知映射保留 raw/source token，相关 derived 值 confidence=`unresolved`。不出现 account、category、candidate 或 identity。

amount 比较 exact decimal value、currency 与 source scale，禁止 binary float。time 比较 source token、temporal kind=`offset_datetime | local_datetime`、components 与 offset presence；缺 offset 不使用 Clock 补齐。

diagnostic 固定比较 code、severity、scope、安全 location 与可选 field role；message 不比较：

| Code | Severity | Scope |
| --- | --- | --- |
| `INPUT_UNSUPPORTED` | fatal | input |
| `INPUT_UNSAFE_OR_OVER_LIMIT` | fatal | input 或 container（按 fixture 冻结） |
| `INPUT_DECODE_FAILED` | fatal | input 或 container（按 fixture 冻结） |
| `STRUCTURE_MISMATCH` | fatal | structure |
| `FIELD_AMOUNT_INVALID` | record_error | field |
| `FIELD_TIME_INVALID` | record_error | field |
| `CONFLICTING_SOURCE_FACTS` | record_error | record 或 field |
| `REQUIRED_FACT_MISSING` | incomplete | field |
| `REQUIRED_FACT_UNRESOLVED` | incomplete | field |

safe location 只能包含有界 opaque synthetic input ref、record ordinal 与 field role。绝对路径、原文件名、worksheet 名、原始 header、raw value、整行、个人标识和底层库 exception 不得出现在 diagnostic、message、日志、异常或测试失败中。

### 九项匿名 fixtures 与 expected

以下所有非 fatal fixture 使用 synthetic input ref `input-1`。`r1`、`r2`、`r3` 分别映射 record ordinal 1、2、3。

以下展开规则是每项 expected 的组成部分，不允许实现填入未声明默认值：

- 每条保留 record 的 safe source location 精确为 `input-1/record-N`，record kind 为 `ordinary_flow_source`；每个 diagnostic 使用该坐标加固定 field role，fatal variant 只使用 `input-1` 与冻结 scope。
- input 明确给值的 amount/currency/time/direction/status presence 均为 `present`；明确写 absent 或 explicit null 时分别冻结对应 presence，不能互换。present source fact 保留表中 token；amount parsed value 是同一精确十进制值和 source scale，currency parsed value 是 `CNY`，time parsed value 是 token 中的 components/kind/offset presence，direction/status 的 source value 是原 token。
- 每个 present source fact 的 provenance 均为 `source_declared + mechanical_decode`。本合同不产生 account、category、candidate 或 identity 字段，也不允许额外 source/derived 字段进入比较。
- known direction/status/flow 的 rule trace 分别固定为 `direction_token_v1`/`status_token_v1`/`ordinary_flow_v1`、version 1、input roles `[direction]`/`[status]`/`[normalized_direction, normalized_status]`、confidence `exact`；unresolved 项保留同一适用 rule key/version/input roles，confidence `unresolved`。
- 除 fixture 明确列出的 diagnostics 和 unresolved requirements 外，对应集合必须为空；diagnostic message 不参与比较。

#### P401-EXP-01 valid ordinary expense

Input `r1`: amount token `30.00`；currency token `CNY`；time token `2026-01-02T08:30:00+08:00`；direction token `out`；status token `completed`。

Expected: contract v1，outcome `complete`，一条 `valid_complete ordinary_flow_source`，无 diagnostic。amount present，decimal `30.00`、source scale 2、currency `CNY`；currency present=`CNY`；time present，kind `offset_datetime`，components `2026-01-02 08:30:00`，offset present=`+08:00`；direction/status 均 present 并保留 source token。normalized_direction=`outgoing`（`direction_token_v1`，input role direction，exact）；normalized_status=`completed`（`status_token_v1`，input role status，exact）；ordinary_flow=`expense`（`ordinary_flow_v1`，input roles normalized_direction+normalized_status，exact）；unresolved requirements 为空。

#### P401-INC-01 valid ordinary income

Input `r1`: amount `100.00`；currency `CNY`；time `2026-01-02T09:15:00+08:00`；direction `in`；status `completed`。

Expected: contract v1，outcome `complete`，一条 `valid_complete ordinary_flow_source` at `input-1/record-1`，无 diagnostic。amount present，decimal `100.00`、scale 2、currency `CNY`；currency present=`CNY`；time present，kind `offset_datetime`，components `2026-01-02 09:15:00`，offset present=`+08:00`；direction/status present 并保留 source token。normalized_direction=`incoming`、normalized_status=`completed`、ordinary_flow=`income`，分别使用公共 rule trace，confidence 均为 exact；unresolved requirements 为空。

#### P401-INCOMPLETE-01 valid incomplete

Input `r1`: amount `45.00`；currency `CNY`；local time `2026-01-03T10:00:00`；direction absent；status explicit null。

Expected: contract v1，outcome `complete`，一条 `valid_incomplete ordinary_flow_source` at `input-1/record-1`。amount present，decimal `45.00`、scale 2、currency `CNY`；currency present=`CNY`；time present，kind `local_datetime`，components `2026-01-03 10:00:00`，offset absent；direction presence=`absent`；status presence=`explicit_null`。normalized_direction、normalized_status、ordinary_flow 均 unresolved，分别保留公共适用 rule trace，confidence=`unresolved`。`unresolved_required_facts` 精确为 direction、status、occurred_time_offset。diagnostics 精确为三个 `REQUIRED_FACT_MISSING`（incomplete/field），locations 分别为 `input-1/record-1/direction`、`input-1/record-1/status`、`input-1/record-1/occurred_time_offset`，field roles 与末段一致。explicit null status 与 absent direction 必须不同。

#### P401-AMOUNT-INVALID-01 invalid amount

Input `r1`: amount `1,2x.34`；currency `CNY`；time `2026-01-02T10:00:00+08:00`；direction `out`；status `completed`。

Expected: contract v1，outcome `partial`，零 records；一个 `FIELD_AMOUNT_INVALID` diagnostic，severity `record_error`、scope `field`、location `input-1/record-1/amount`、field role amount。不得生成 rejected-record envelope。

#### P401-TIME-INVALID-01 invalid time

Input `r1`: amount `12.00`；currency `CNY`；time `2026-02-30T10:00:00+08:00`；direction `out`；status `completed`。

Expected: contract v1，outcome `partial`，零 records；一个 `FIELD_TIME_INVALID` diagnostic，severity `record_error`、scope `field`、location `input-1/record-1/occurred_time`、field role occurred_time。不得生成 incomplete record。

#### P401-UNKNOWN-01 unknown tokens unresolved

Input `r1`: `12.00` CNY，time `2026-01-04T08:00:00+08:00`，direction `sideways`，status `completed`。Input `r2`: `13.00` CNY，time `2026-01-04T08:05:00+08:00`，direction `out`，status `mystery`。

Expected: contract v1，outcome `complete`，两条 `valid_incomplete ordinary_flow_source` at `input-1/record-1` 与 `input-1/record-2`。两条 amount/currency/time 均 present，decimal 分别 `12.00`/`13.00`、scale 2、currency `CNY`、time components 分别 `2026-01-04 08:00:00`/`08:05:00`、kind `offset_datetime`、offset present=`+08:00`。r1 direction/status 均 present；保留 direction token `sideways`，normalized_direction unresolved（公共 direction rule trace，unresolved）；normalized_status=`completed` exact；ordinary_flow unresolved（公共 flow rule trace，unresolved）；unresolved requirements 精确为 direction；一个 `REQUIRED_FACT_UNRESOLVED` incomplete/field diagnostic at `input-1/record-1/direction`。r2 direction/status 均 present；normalized_direction=`outgoing` exact，保留 status token `mystery`，normalized_status unresolved（公共 status rule trace，unresolved）；ordinary_flow unresolved；unresolved requirements 精确为 status；一个 `REQUIRED_FACT_UNRESOLVED` incomplete/field diagnostic at `input-1/record-2/status`。

#### P401-MIXED-01 mixed partial

Input `r1`: `20.00` CNY，`2026-01-05T08:00:00+08:00`，`out`，`completed`。Input `r2`: amount `bad`，CNY，`2026-01-05T08:05:00+08:00`，`out`，`completed`。Input `r3`: `25.00` CNY，`2026-01-05T08:10:00+08:00`，`in`，`completed`。

Expected: contract v1，outcome `partial`；records 精确为 r1/r3 at `input-1/record-1` 与 `input-1/record-3`。两条 amount/currency/time/direction/status 均 present。r1 是 `valid_complete` expense：decimal `20.00` scale 2、CNY、offset datetime components `2026-01-05 08:00:00`、offset `+08:00`、outgoing/completed/expense exact。r3 是 `valid_complete` income：decimal `25.00` scale 2、CNY、offset datetime components `2026-01-05 08:10:00`、offset `+08:00`、incoming/completed/income exact。两条均使用公共 provenance 和 version 1 rule traces，unresolved requirements 为空。唯一 diagnostic 为 r2 的 `FIELD_AMOUNT_INVALID` record_error/field at `input-1/record-2/amount`；无 rejected-record envelope。

#### P401-LOOKALIKE-01 lookalike preserved twice

Input `r1` 与 `r2` 字节及逻辑值相同：`9.99` CNY，`2026-01-06T08:00:00+08:00`，`out`，`completed`。

Expected: contract v1，outcome `complete`，无 diagnostic。semantic record multiset 含同一 `valid_complete ordinary_flow_source` expense 投影 multiplicity 2：五类 source facts 均 present，decimal `9.99` scale 2、CNY、offset datetime components `2026-01-06 08:00:00`、offset `+08:00`、outgoing/completed/expense exact，unresolved requirements 为空；具体 provenance locations 分别为 `input-1/record-1` 与 `input-1/record-2`。不得 dedup。

#### P401-FATAL-01 input/container fatal rejected variants

同一 operation family 下冻结四个 root variants，不冻结 provider、格式或限制数字：unsupported input→`INPUT_UNSUPPORTED` fatal/input；unsafe/over-limit container→`INPUT_UNSAFE_OR_OVER_LIMIT` fatal/container；decode/corrupt container→`INPUT_DECODE_FAILED` fatal/container；无法建立可靠 record boundary→`STRUCTURE_MISMATCH` fatal/structure。每个 variant 的 expected 都是 contract v1、outcome `rejected`、零 records、恰好一个对应 fatal diagnostic，安全 location 仅含 `input-1` 与适用 scope，不含原文件信息。

### 全局副作用与确定性

每个 fixture 的 candidate、confirmation、formal transaction、posting、evidence link、reconciliation、balance 与 report 创建/改变计数均为零。相同逻辑输入重复执行必须得到同一结构结果，不依赖产品 ID、Clock 或本机路径。

records 的主要比较是 semantic multiset 并保留 multiplicity；location/provenance 按当前 fixture 坐标精确比较。额外 permutation assertion 对 records 重排并同步重映射 fixture coordinates 后，必须得到相同 semantic multiset 与相应重映射的 diagnostics/provenance；不得要求原 locator 在重排后保持不变。

### 未来草案：组合转账

**级别：** `future_draft`

组合转账作为未来能力保留，用于收集一对多、多对一或多对多账户互转的展示、分账户余额和差额归因问题。该草案不冻结预期结果，也不计入首版 `core_required` 通过率。

## 隐私要求

- 公开场景使用“资产账户 A”“负债账户 B”“商户 X”等中性名称和固定合成日期。
- 公开数据不得包含真实姓名、账号、卡号、电话、订单标识、商户别名、余额锚点或可逆推出个人关系的数据。
- 本地真实场景只提交匿名编号、级别和验收维度；文件路径、来源哈希、映射表和运行日志不进入 Git。
- 真实资料脱敏副本只有在不可逆且通过专项审查后，才可以另行提议进入公开测试；本规范不视其为默认允许。

## 通过标准

一个案例只有同时满足以下条件才算通过：

- 输入产生的交易集合与冻结答案一致，未产生额外有效交易。
- 每笔正式交易的每条分录均精确一致，并且逐币种平衡。
- 指定时点余额、现金流、收入、消费、预算和净资产的适用断言全部一致。
- 对账状态在分录级一致，证据引用、差异和交易汇总状态可解释。
- 重复导入、重新运行和固定顺序变化不会产生第二次资金影响。
- 需要人工确认的候选不会提前进入正式账本。
- 关闭网络、同步和 AI 后，全部 `core_required` 与 `core_reserved` 场景仍能运行并得到同一结果。

失败报告必须定位到场景、交易、分录或证据断言，不能只报告期末余额不同。

## 冻结与变更

1. 先根据正式规则编写输入和人工推导的完整预期。
2. 独立核对分录平衡、统计口径、时间和不应发生的副作用。
3. 为案例与规则版本生成稳定版本号后冻结。
4. 实现变化只允许更新实际结果；需要修改黄金答案时，必须先修改决定或账务规则并说明取代关系。
5. 本地真实场景沿用相同流程，但冻结文件和审查记录留在本地。
