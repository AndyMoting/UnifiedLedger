# Golden Schema v2 阶段 0 盘点

## 状态、范围与权威

- 状态：阶段 0 结构与语义盘点完成；本文不是已批准的 Schema 或迁移契约。
- 完整性边界：本文是语义盘点，不是机器穷尽的 leaf-path map。
- 范围：已签入的 `RG-01` 至 `RG-10` v1 黄金答案、对应规格、测试及当前加载/验证能力。
- 暂停项：`RG-11` 保持暂停，不进入本轮统一契约。
- 目的：列出各 RG 方言、候选注册项、已有映射证据和必须决策的问题。
- 本文不批准字段名、枚举名、ID 生成法、时间生成法、默认值、兼容别名或迁移顺序。
- 迁移权威首先是已签入且冻结的规格、测试和 fixture，三者共同约束行为。
- 正式决定与账务规则约束业务语义，但不自动批准 v2 的序列化形状。
- 当前加载器和验证器只说明 v1 已实现的最低机械约束，不构成跨 RG 契约。
- 旧外部验收计划的 RG 编号与当前已签入编号存在冲突，仅作历史证据。
- 发生编号或语义冲突时，以当前已签入、已冻结的规格、测试和 fixture 为迁移依据。

## 当前 v1 验证缺口

- `load_golden_case` 只接受 `schema_version = 1`，其他版本直接拒绝。
- 根节点只要求为对象；加载器不解释各 RG 的业务分区。
- envelope 验证覆盖案例 ID、正式级别、币种和两位精度。
- catalog 验证覆盖账户与分类的稳定 ID 非空和局部唯一。
- 只有调用方把选定 transactions 与 accounts 传给通用验证函数时，才检查以下交易约束。
- 被传入的金额必须是精确的两位十进制字符串，不接受二进制浮点或可变精度文本。
- 被传入的交易时间必须是合法且带时区的 ISO 8601 时间。
- 被传入的每笔交易至少两条分录，并按币种精确平衡。
- 被传入的分录账户必须引用同时传入的 catalog accounts。
- 被传入的交易 ID、单笔分录 ID 和跨交易分录 ID 的重复会被拒绝。
- 余额重放只累计调用方选定的 `effective` 交易，并仅比较调用方传入的 expected balance keys。
- 当前 targeted tests 只选择特定路径调用上述函数；fixture 其他字段依赖各 RG 专用断言。
- 验证器不要求完整余额包含 catalog 中全部账户，也不强制零余额项。
- 验证器不理解版本链、posting set、来源、证据、候选、确认或关系实体。
- 验证器不统一 operation class、action type、transaction type、evidence role 或 report key。
- 验证器不校验对账状态的层级归属、别名投影或汇总推导。
- 因此十个 v1 文件是十种已测试方言，而不是一个已验证的跨 RG 数据契约。

## RG 方言盘点

### RG-01 普通支出

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`create`、`note_update`、`idempotency`、`distinct_reentry`、`invalid_inputs`、`forbidden_side_effects`。
- 操作：`manual_expense`、`transaction_note_update`、`idempotent_retry`、`atomic_rejection`。
- 交易类型：`opening_balance`、`expense`。
- `note_update` 以同一交易的多个版本表达，版本共享同一 posting set。
- 备注修订不改变分录、余额或报表效果，旧版本只供审计。
- 报表：日/月 `consumption`、`cash_outflow`、`income`、`net_worth_change`。
- `budget` 明确为 `not_applicable`，不能与数值零混同。
- `distinct_reentry` 表达独立重复记账，不等于幂等重试。
- 风险：独立重录与无效输入缺少统一、显式的操作前基线。

### RG-02 普通收入

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`create`、`category_rename`、`idempotency`、`invalid_inputs`、`variants`、`forbidden_side_effects`。
- 操作：`manual_income`、`category_rename`、重试、拒绝。
- 交易类型：`opening_balance`、`income`。
- 分类名称历史属于可变 catalog 历史；交易继续引用稳定分类 ID。
- 分类改名改变当前展示名称，不改变历史交易身份或财务效果。
- 顶层静态 catalog 无法完整表示改名前后两个目标状态。
- `variants` 是彼此独立的零基线案例，不是主路径上的后续操作。
- 风险：多个独立基线若被误并为一个操作图，会制造不存在的累计余额。

### RG-03 账户互转与手续费

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`manual_create`、`import_lifecycle`、`unknown_one_sided_debit`、`invalid_manual_inputs`、`idempotency`、`forbidden_side_effects`、`out_of_scope`。
- 操作：`manual_account_transfer`、`source_import`、`candidate_confirmation`、`evidence_merge`、重试、拒绝。
- 交易类型：`opening_balance`、`account_transfer`。
- 具有 sources、evidence、candidates 三类独立对象，不能压成单一导入记录。
- 已观察角色：`real_account_posting`、`destination_asset_posting`。
- 一笔交易同时包含内部转账本金和 `1.00` 手续费。
- 报表分派必须排除内部本金，同时保留手续费的消费与现金流出。
- 一侧扣款事实不足时保持候选或待确认，不得猜测到账账户。
- 聚合重试目前混合多个动作，v2 候选需拆为可重放的独立 operations。
- 风险：`internal_transfer_amount` 的口径是转出本金、转入本金还是配平金额，尚未冻结。

### RG-04 混合支付

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`manual_lifecycle`、`import_lifecycle`、`missing_funding_leg`、`idempotency`、`invalid_manual_inputs`、`out_of_scope`、`forbidden_side_effects`。
- 操作：`manual_mixed_expense`、`credit_repayment`、`source_import`、`candidate_confirmation`、`evidence_merge`。
- 交易类型：`opening_balance`、`expense`、`credit_repayment`。
- 领域关系：`mixed_payment`，并保存多个 funding components。
- 消费交易可同时具有资产腿和信用负债腿；每条真实账户分录独立对账。
- 后续信用本金还款产生现金流，但不得重复产生消费。
- `missing_funding_leg` 必须保持资料不足，不得自动补平。
- 生命周期报告可能只给累计结果，迁移时需重建被省略的先前期间。

### RG-05 合并付款

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`manual_path`、`import_path`、`allocation_failures`、`idempotency`、`invalid_manual_inputs`、`out_of_scope`、`forbidden_side_effects`。
- 操作：`manual_merged_payment`、`source_import`、`candidate_confirmation`、`evidence_merge`。
- 交易类型：`opening_balance`、`expense`。
- 领域关系：`merged_payment`。
- 领域实体：`consumption_record`、`item_allocation`。
- 两条消费保持独立身份与分类，但共享一个平衡交易和唯一真实付款分录。
- 项目证据目标是 item allocation，不是金融分录对账目标。
- 项目证据与付款证据必须分开建模和核验。
- 风险：pending/conflict 的基线与操作后结果尚未形成统一状态契约。

### RG-06 定金与尾款

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`baselines`、`manual_path`、`import_path`、`invalid_baselines`、`invalid_inputs`、`idempotency`、`out_of_scope`、`forbidden_side_effects`。
- 主路径是 staged group 生命周期，不是单笔交易生命周期。
- `payment_progress` 与 `fulfillment_status` 相互独立。
- 动作：`create_staged_payment_group`、`confirm_staged_payment`、`change_fulfillment_status`、`confirm_completion`。
- 导入动作：`receive_staged_payment_candidate`、`confirm_imported_staged_payment`。
- 证据动作：`link_payment_evidence`、`merge_payment_mirror_evidence`。
- 交易类型：`opening_balance`、`expense`。
- 领域关系：`staged_payment`；领域实体包含 installment/payment。
- 报表同时出现平铺结果、按操作日结果和累计结果。
- v1 fixture 对零财务影响的状态变化与完成确认仍保留操作及返回 ID。
- 风险：聚合重试若无明确目标 action identity，无法确定性重建。

### RG-07 退款

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`original`、`refund_request`、`merchant_notice`、`manual_receipt`、`bank_credit_evidence`、`dual_role_source`、`category_policy`、`refund_cap`、`import_path`、`invalid_inputs`、`idempotency`、`out_of_scope`、`forbidden_side_effects`、`operation_baselines`、`canonical_states`、`manual_unconfirmed_arrival`、`entity_registry`。
- 退款关系状态：requested -> approved -> processing -> received。
- 导入候选状态：pending -> confirmed，与退款关系状态不是同一状态机。
- 动作覆盖退款状态迁移、`refund_arrival`、导入确认、证据合并、退款上限拒绝、输入拒绝和重试。
- 交易类型：`opening_balance`、`expense`、`refund_receipt`。
- 已观察角色：`payment_asset_posting`、`refund_relationship`、`destination_asset_posting`。
- 确认角色：`refund_relationship_confirmation`。
- requested/approved/processing 是状态操作，不产生正式交易或报表变化。
- received 只在明确到账确认后创建独立退款交易。
- 风险：未完成确认时的 pending 与明确 rejected 尚未统一表达 outcome。

### RG-08 借贷与结算

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`behavior_codes`、`opening`、`lend`、`counterparty_identity`、`manual_collection`、`principal_cap`、`import_collection`、`evidence_policy`、`invalid_inputs`、`operation_baselines`、`canonical_states`、`idempotency`、`entity_registry`、`out_of_scope`、`forbidden_side_effects`。
- 动作覆盖借出、收款、往来对象改名、候选接收/确认、证据合并、本金上限拒绝和重试。
- 当前交易以通用 `lending` 加 `behavior_code` 表达。
- 候选 v2 拆分为 `lending_disbursement` 与 `lending_collection`，该拆分尚有争议。
- 领域实体：counterparty、lending position、lending settlement 及本金/利息/费用 components。
- 已观察角色：`funding_asset_posting`、`destination_asset_posting`、`counterparty_lending_relationship`。
- 往来对象名称可变，身份及历史结算引用必须稳定。
- 本金流入、流出的正负号与报表方向映射尚未冻结。
- 无效 fixture 中仅用于反例的 `receive` behavior 不得进入候选注册表。

### RG-09 目标余额调整与替代

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`request_registry`、`main_path`、`stale_preview`、`state_derivation_cases`、`negative_delta_boundary`、`zero_delta`、`import_path`、`invalid_inputs`、`canonical_states`、`idempotency`、`out_of_scope`、`forbidden_side_effects`、`post_target_fixture`、`idempotency_expected_ids`、`evidence_path`。
- 调整状态：open -> partial -> full；对账另有独立生命周期。
- 动作覆盖 preview、调整确认、真实转账确认、解释分配、来源接收、证据绑定、拒绝、观察和重试。
- 交易类型：`opening_balance`、`account_transfer`、`balance_adjustment`、`balance_adjustment_reversal`。
- 领域实体：target observation、adjustment、explanation allocation。
- 证据角色：`target_balance_observation`、`real_account_posting`。
- 审计角色：`adjustment_transaction`、`explanation_transaction`、`allocation_reversal`。
- 业务 reconciliation 字段描述目标余额解释进度，不是 posting reconciliation 记录。
- 风险：解释操作应归类为 reversal 还是 adjustment，尚未决定。

### RG-10 储值充值与赠送

- 完整顶层键（按 v1 顺序）：`schema_version`、`case`、`catalog`、`opening`、`request_registry`、`canonical_states`、`main_path`、`reconciliation_states`、`reconciliation_path`、`import_path`、`secondary_cases`、`idempotency`、`invalid_inputs`、`forbidden_side_effects`。
- lot history events：loaded、spent、expired；它们不是统一 lot status 枚举。
- 动作覆盖充值、消费、提醒、损失、接入余额调整、商户分配、对账、导入、重试和拒绝。
- v1 已观察交易类型：`opening_balance`、`stored_value_recharge`、`stored_value_spend`、`stored_value_expiry_loss`、`stored_value_pre_activation_balance_adjustment`。
- 争议仅在 v2 是否保留、设 alias 或替换 `stored_value_pre_activation_balance_adjustment`，不是 v1 是否存在该类型。
- 领域实体：lot、lot consumption、stored-value consumption、activation adjustment、merchant allocation。
- 已观察角色：`bank_payment_posting`、`stored_value_credit_lot`、`stored_value_bonus_component`、`stored_value_activation_balance_fact`。
- 另有到期与各类确认角色；确认 provenance 不应与业务关系角色混用。
- `stored_value_credit_lot` 同时承载 posting 证据与 lot 关系语义，v2 很可能需要拆分。
- 报表仅给累计结果；由目标状态 current transactions 确定性重建期间结果是未批准的 v2 候选要求。
- 旧对账值 pending_financial_evidence/not_present/partial/complete 需要显式别名映射。

## 汇总候选注册表

### Operation class

| 候选类 | 含义 | 当前结论 |
| --- | --- | --- |
| `creation` | 创建关系、实体或正式交易 | 候选 |
| `update` | 修改可变 catalog 或非财务属性 | 候选 |
| `read` | preview、observation 等只读/观察操作 | 候选 |
| `rejection` | 原子拒绝且无正式副作用 | 候选 |
| `reconciliation` | 绑定、合并或核验证据 | 候选 |
| `status_transition` | 仅推进业务状态 | 候选 |
| `reversal` | 创建经济或解释性反向记录 | 候选；RG-09 分类有争议 |
| `adjustment` | 创建或分配余额调整 | 候选；与 reversal 边界待定 |

### Action type

- RG-01：`manual_expense`、`transaction_note_update`、`idempotent_retry`、`atomic_rejection`。
- RG-02：`manual_income`、`category_rename`、重试、拒绝。
- RG-03：`manual_account_transfer`、`source_import`、`candidate_confirmation`、`evidence_merge`、重试、拒绝。
- RG-04：`manual_mixed_expense`、`credit_repayment`、`source_import`、`candidate_confirmation`、`evidence_merge`。
- RG-05：`manual_merged_payment`、`source_import`、`candidate_confirmation`、`evidence_merge`。
- RG-06：`create_staged_payment_group`、`confirm_staged_payment`、`change_fulfillment_status`、`confirm_completion`。
- RG-06 导入/证据：`receive_staged_payment_candidate`、`confirm_imported_staged_payment`、`link_payment_evidence`、`merge_payment_mirror_evidence`。
- RG-07：退款状态迁移、`refund_arrival`、导入确认、证据合并、上限拒绝、输入拒绝、重试。
- RG-08：借出、收款、往来对象改名、候选接收、候选确认、证据合并、本金上限拒绝、重试。
- RG-09：preview、调整确认、真实转账确认、解释分配、intake、证据绑定、拒绝、observation、重试。
- RG-10：recharge、spend、reminder、loss、activation adjustment、merchant allocation、reconciliation、import、retry、rejection。
- 非精确英文名仅表示已观察动作语义，不批准新的序列化标识。
- 跨 RG 同义动作只能在正式批准 alias 表后归一化，迁移时不得凭名称相似静默合并。

### Transaction type

- 明确候选：`opening_balance`、`expense`、`income`、`account_transfer`、`credit_repayment`、`refund_receipt`。
- 借贷候选：`lending_disbursement`、`lending_collection`；与通用 `lending + behavior_code` 的取舍未决。
- 调整候选：`balance_adjustment`、`balance_adjustment_reversal`。
- 储值已观察：`stored_value_recharge`、`stored_value_spend`、`stored_value_expiry_loss`、`stored_value_pre_activation_balance_adjustment`。
- v2 对 `stored_value_pre_activation_balance_adjustment` 的 preserve/alias/replace 选择未批准。

### Entity 与 relation

- catalog：account、category，以及需要历史的 category name state。
- 通用账务：transaction、transaction version、posting set、posting。
- 来源链：source、candidate、confirmation、evidence；四者保持独立身份与引用。
- 付款关系：`mixed_payment`、`merged_payment`、`staged_payment`。
- 分配实体：funding component、consumption record、item allocation、installment/payment。
- 退款实体：refund relationship 及其状态历史。
- 借贷实体：counterparty、lending position、lending settlement、settlement component。
- 调整实体：target observation、adjustment、explanation allocation。
- 储值实体：lot、lot consumption、stored-value consumption、activation adjustment、merchant allocation。
- relation 与领域实体由谁拥有生命周期、版本和删除约束，仍需正式契约决定。

### 已观察 role tokens

- 本节只盘点 v1 token 及其当前用途，不批准 v2 canonical role 或 alias。
- posting evidence/target tokens：`destination_asset_posting`、`real_account_posting`、`payment_asset_posting`、`funding_asset_posting`、`bank_payment_posting`。
- posting/fact target tokens：`target_balance_observation`、`stored_value_activation_balance_fact`。
- relationship tokens：`refund_relationship`、`counterparty_lending_relationship`。
- confirmation provenance tokens：`refund_relationship_confirmation`、`balance_adjustment_confirmation`、`explanation_allocation_confirmation`、`real_transfer_confirmation`。
- confirmation provenance tokens：`lending_event_confirmation`、`lending_settlement_confirmation`、`explicit_confirmation_provenance`。
- RG-10 confirmation tokens：`stored_value_activation_balance_confirmation`、`stored_value_expiry_confirmation`、`stored_value_recharge_confirmation`、`stored_value_spend_confirmation`。
- audit-link tokens：`adjustment_transaction`、`explanation_transaction`、`allocation_reversal`。
- domain/ambiguous tokens：`stored_value_credit_lot`、`stored_value_bonus_component`。
- 付款侧四个 outbound token 是否归一为一个 canonical role，尚未决定。
- RG-10 的 posting evidence 与 lot relationship link 应否拆开，尚未决定。
- v2 是否拆分字段、保留原 token 或建立 alias 表，均需正式批准。

### Report

- 候选键：`consumption`、`cash_outflow`、`cash_inflow`、`income`、`special_income`、`expense`、`expiry_loss`。
- 候选键：`net_worth_change`、`principal_consumption`、`principal_external_cash_flow`。
- 候选键：`balance_adjustment_net_worth_change`、`internal_transfer_amount`、`budget`、`category_effect`。
- 每个指标的正负方向、期间粒度和适用条件尚未形成统一契约。
- 数值零表示“适用但结果为零”；`not_applicable` 表示“不适用”，二者不得互换。

### Reconciliation

- canonical 候选：`pending`、`matched`、`has_difference`、`not_applicable`。
- posting reconciliation 只属于可对账的真实账户分录，不改变余额或交易有效性。
- transaction/group reconciliation 是从成员 posting 推导的汇总，不是独立事实来源。
- 退款、借贷、调整、履约、付款进度等 business status 不得复用 posting reconciliation 枚举。
- `partial`、`complete`、`pending_financial_evidence`、`not_present` 等旧值是 alias 或 projection 候选。
- 旧值必须通过批准的映射表转换，不得原样静默复制到 canonical 字段。

## 跨域观察事实与候选要求

### v1 观察事实

1. complete target state 的余额结果已包含相应 opening transactions 的影响。
2. 较新的 fixture 显式出现 `transaction -> current_version -> posting_set -> postings` 引用链。
3. 版本化案例把 superseded version 留在审计结构中，当前财务结果引用 current version。
4. 较早 fixture 缺少部分 version、posting set 或 history 结构。
5. 各 fixture 的 complete balance membership 不一致，部分只列受关注账户。
6. 多个 RG 显式记录 no-change、status-only、rejected operation 及返回 ID。
7. 多个 RG 同时保存 posting reconciliation 与 transaction/group/business derived status。
8. sources、evidence、confirmations、relations 与 domain entities 在较新 fixture 中分别出现。
9. 报表存在目标状态快照、按操作日结果和累计结果等不同形状。
10. variants、边界案例和 operation baselines 可代表相互独立的起始状态。

### 未批准的 v2 候选要求

1. 构造 complete target state 时合并 opening transactions，避免重复或遗漏期初影响。
2. v1 缺少 version、posting set 或 history 时确定性合成，并明确定义 ID 与时间来源。
3. 合成结果保持稳定、可重复，不依赖迁移运行时状态或非语义遍历偶然性。
4. complete balances 包含 catalog 中全部 account ID，并显式保留零余额。
5. 删除 `entity_registry` 或其他冗余 projection 前证明其与 canonical entities 等价。
6. 保留 no-change、status-only、rejected operation、稳定返回 ID 和 outcome。
7. posting reconciliation 与 transaction/group/business derived status 分层建模。
8. source、evidence、confirmation、relation 和 domain entity 保持不同对象身份。
9. candidate 仅在明确确认后创建或替换正式交易。
10. 报表期间从目标状态的 current transactions 推导，不沿 operation graph 分支累加。
11. 独立 fixture baseline 先声明作用域，再决定能否并入同一迁移案例。
12. 在任何 adapter 或 fixture rewrite 前，为每个 RG 生成 normalized JSON-path inventory。
13. 对每条 JSON path 标注 preserve、map、derive 或 reject，且未分类路径数必须为零。
14. normalized path inventory 与逐路径分类必须经过独立审查和明确批准。
15. 区分业务发生、支付、来源观察、确认、目标余额、证据和状态迁移等时间语义。
16. 时间迁移保留原始精确值、UTC offset 和可观察精度；任何规范化规则另行批准。
17. 每个数组声明为 ordered 或 set-like；set-like 比较和输出使用批准的确定性排序键。
18. 所有依赖顺序的分配、lot 消耗和同时间事件必须声明确定性 tie-break。
19. 本节全部条目仍需正式 contract、样例、独立审查和用户批准，不是当前实现授权。

### 未批准的迁移安全候选要求

1. v1 输入在迁移中保持不可变，迁移器不得原地改写来源 fixture。
2. v2 输出采用原子发布，未完成案例不得暴露为成功结果。
3. 迁移可恢复、可续跑；重试同一输入必须产生确定性相同输出。
4. 失败按 case 隔离并报告，不得以部分成功掩盖失败案例。
5. 明确 rollback/recovery 流程，并验证中断后不会混合新旧结果。
6. 发布 manifest 记录每个 case 迁移前后对象计数与内容 hash，支持审计和复核。
7. 本节是设计候选，不授权实现迁移器、写入输出或改写 fixture。

## 未决契约问题

1. 可变分类状态如何表示：catalog snapshot、历史事件还是版本化实体？
2. 可变 counterparty 名称如何表示，且不改变借贷位置与结算引用？
3. `variants`、边界案例和多个 independent baselines 如何声明隔离作用域？
4. candidate confirmation 属于 creation、status transition 还是独立 operation class？
5. candidate 的 pending、confirmed、rejected 和 incomplete confirmation 如何统一 outcome？
6. v1 缺失字段的确定性 ID 采用何种命名空间、输入材料和冲突规则？
7. v1 缺失时间的确定性 timestamp 从何处取得，禁止使用迁移运行时当前时间。
8. relation 与 domain entity 如何划分所有权、版本、状态历史和删除约束？
9. RG-05 item-allocation evidence 应引用 allocation、consumption record 还是两者的明确 target？
10. outbound evidence alias 是否归一；原值如何保留以支持无损往返？
11. RG-10 `stored_value_credit_lot` 如何拆成 posting 证据 link 与 lot 关系 link？
12. RG-03 同笔 transfer + fee 如何表达交易类型、内部本金与外部费用报表分派？
13. RG-03 `internal_transfer_amount` 采用转出额、转入额还是其他明确口径？
14. RG-08 采用通用 `lending + behavior_code` 还是拆分 transaction type？
15. RG-08 signed principal 的流入、流出、余额变化和报表方向如何统一？
16. RG-10 对已观察的 `stored_value_pre_activation_balance_adjustment` 应保留、设 alias 还是替换？
17. report 中适用且为零与 `not_applicable` 的统一编码是什么？
18. 只有累计报告的 RG 如何确定性重建各期间结果与分类效果？
19. 聚合 retries 如何拆成目标明确、顺序稳定且可独立重放的 operations？
20. pending 与 rejected 如何区分无决定、资料不足、校验失败和明确拒绝？
21. 旧外部 acceptance 编号与当前 RG 编号冲突时，迁移追踪表如何记录取代关系？
22. 对上一问的当前约束：已签入且冻结的规格、测试和 fixture 是迁移权威。
23. 每个时间字段属于哪一种时间语义；缺失、相同或冲突时间如何映射？
24. 时间文本是否允许格式规范化，还是必须逐字保留并另存解析值？
25. 每个数组是 ordered 还是 set-like；其稳定排序键和 tie-break 分别是什么？

## 阶段 0 结论与设计门

- `RG-01` 至 `RG-10` 的结构、观察事实、候选要求和主要冲突已完成盘点。
- 盘点证明 v1 存在多个已测试方言，尚不能据此宣称 v2 合同已批准。
- 当前不得创建 JSON Schema，不得扩展 validator，不得选择生产依赖。
- 当前不得实现 adapter，也不得迁移或重写任何 fixture。
- 下一设计产物必须给出正式 contract 与至少一个 representative sample。
- 正式 contract 必须逐项回答本文未决问题，并记录争议项的替代方案与取舍。
- representative sample 必须覆盖版本链、posting set、来源/候选/确认/证据、关系、报表和对账分层。
- contract 与 sample 均需独立规格审查和质量审查。
- 正式 contract 与 representative sample 通过独立审查并获明确批准后，只打开 Schema/validator prototype 实现门。
- adapter/fixture migration 仍保持关闭，不随 Schema/validator prototype 自动获准。
- 每个 RG 必须另有批准的 mapping、expected v2 output 和 semantic equivalence 判定。
- 每个 RG 还必须完成 normalized JSON-path inventory，并达到 preserve/map/derive/reject 零未分类路径。
- 上述逐 RG 产物通过独立审查并获明确批准后，才打开对应 adapter/fixture migration 实现门。
- 迁移安全候选要求也必须进入批准契约并有验证方案，才能发布迁移输出。
- `RG-11` 在该设计门打开前继续暂停。
