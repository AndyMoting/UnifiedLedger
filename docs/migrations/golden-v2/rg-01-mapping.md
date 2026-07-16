# RG-01 Golden Schema v2 Adapter Mapping

状态：`approved`

本文件是 `golden/rules/rg-01.json` 到 Golden Schema v2 `2.0.0` 的已批准逐路径设计。四项 contract amendments 已实施，因此下一步可以生成 `draft_for_review` expected v2 output。它仍不授权 adapter 实现、v1 fixture rewrite 或迁移发布；adapter/fixture migration gate 继续关闭。

## 1. 权威与范围

- 业务权威是已冻结的 RG-01 规格、机器 fixture、黄金测试、正式账务规则和决定记录。
- 目标结构权威是 tracked `docs/GOLDEN_SCHEMA.md` 与 `schemas/golden-case-v2.schema.json`。
- `docs/examples/golden-schema-v2/rg-01.json` 只用于核对形状，不是迁移答案，也不覆盖 v1 中的 inactive category、distinct re-entry、idempotency 或 invalid inputs。
- 本设计只覆盖 RG-01。不得据此为其他 RG 推导 alias、payload、action 或迁移规则。

## 2. Path 盘点算法

使用 RFC 8259 JSON parser 读取 source，再递归遍历解析后的值：

1. 根路径固定为 `$`。
2. object member 追加 `.key`。
3. 所有数组元素统一追加 `[*]`，不把具体数组下标写入 normalized path。
4. scalar、`null`、空数组和空对象是 leaf；非空 object/array 继续递归。
5. 同一 normalized path 只登记一次；所有 occurrence 汇总到 `source_value_kinds` 和 `occurrence_count`。
6. entries 按 `source_path` Unicode 升序输出。

独立重算结果是 149 个唯一 normalized leaf paths、281 个 leaf occurrences。机器文件达到 `149/149/0`：

| 指标 | 结果 |
| --- | ---: |
| source paths | 149 |
| classified paths | 149 |
| unclassified paths | 0 |
| preserve | 56 |
| map | 40 |
| derive | 52 |
| reject | 1 |
| ready entries | 148 |
| requires-contract-amendment entries | 0 |
| test-only-exclusion entries | 1 |
| unresolved contract gaps | 0 |
| resolved approved contract gaps | 4 |

分类含义：

- `preserve`：值和业务语义直接进入当前 v2 contract。
- `map`：对象位置、枚举、角色、集合形状或 canonical absence 发生转换。
- `derive`：完整状态、余额、报告、计数、delta 或汇总状态由 source facts 重建；v1 expected aggregate 只作为 equivalence assertion。
- `reject`：不进入 serialized v2 output，但负面约束必须进入 semantic-equivalence test。

`classification` 描述最终语义处理方式。四项 amendment 实施后，所有可迁移 entries 均为 `disposition=ready`；唯一例外是一个 `test_only_exclusion`。`resolved_contract_gap_ids` 保留 GAP-01 至 GAP-04 的历史决策引用，`contract_gap_ids` 已清空。

## 3. v1 Sections 到 v2 执行结构

| v1 section | v2 structure | 规则 |
| --- | --- | --- |
| `case` | envelope `case` | 保留 ID、level、rule version、ledger、timezone；`currency + precision` 映射为单项 `currencies`；GAP-04 已实施 `draft_for_review/approved` lifecycle。 |
| `catalog` | 每个 complete state 的 `catalog` | 三个账户和三个分类全部保留，包括 inactive secondary category。 |
| `opening` | 每个 root 的 initial complete state | GAP-03 helper 生成缺失 ID；GAP-02 允许 collapsed economic time 展开；完整余额确定性重放。 |
| `create` | main root operation 1, accepted `manual_expense` | transaction/posting facts 已冻结；GAP-03 helper 生成 operation/state/confirmation/reconciliation ID；GAP-02 允许时间展开。 |
| `note_update` | main root operation 2, accepted `transaction_note_update` | 追加 v2，复用原 posting set，只改变 current-version pointer 和 note；GAP-03 生成缺失 ID；按 GAP-02 复用旧版三个 economic times。 |
| `idempotency` | main root operation 3, `manual_expense` + `no_change` | 使用原 create 的完整 input、同一 request ID 和 action；GAP-03 生成 operation/state ID。 |
| `distinct_reentry` | main root operation 4, accepted `manual_expense` | 新请求创建第二笔独立 expense；GAP-03 生成缺失 ID，GAP-02 允许时间展开。 |
| `invalid_inputs[*]` | 7 个独立 roots | GAP-01 closed sparse rejected branch 表达全部 16 个 normalized paths；GAP-03 生成缺失 ID。 |
| `forbidden_side_effects` | test-only exclusions | 不生成 v2 entity/field；逐项保留负面测试。 |

main root 的顺序必须把 idempotency 放在 distinct re-entry 之前。这样 retry 状态仍是 `964.20` 和单笔 `35.80` 影响；随后独立重录才得到 `928.40` 和两笔合计 `71.60`。

## 4. Stable ID 与 Normalized Migration Locator

GAP-03 已批准并实施。以下规则可用于生成 expected v2 output 初稿，但不构成 adapter 或 fixture migration 授权。

1. source 已有的合法稳定 ID 原样保留，包括账户、分类、transaction、posting、request、version 和 posting-set ID。
2. 缺失的 root、state、operation、opening version/posting set、confirmation 和 posting-reconciliation IDs 需要 deterministic migration helper。
3. contract 使用本 artifact 的 normalized source locator：根 `$`、object `.key`、数组 `[*]`。该 locator 不是 JSON Pointer，不使用 slash wildcard，也不包含实际 array index。
4. migration semantic key 固定为 `<normalized-source-locator> + "\noccurrence=" + <stable-occurrence-discriminator>`，由 `migration_semantic_key` 构造。
5. occurrence discriminator 优先使用 source stable ID、request ID、case ID 或 invalid-case ID。禁止使用 array index、遍历顺序、显示名、当前时间、机器路径或私人数据。
6. root ID 由 `deterministic_v2_root_id` 非循环生成，UUID name 固定为 `case-id + "\n@root\nroot\n" + semantic-key`；随后 descendant 才使用该 root ID。
7. descendant 由 `deterministic_v2_migration_id` 生成，UUID name 固定为 `case-id + "\n" + root-id + "\n" + entity-kind + "\n" + semantic-key`。namespace 继续使用 `cfad3f84-edb1-5838-ae53-aae49684cf1a`；collision 是 hard rejection，不允许 suffix 修补。
8. base case validator 无法从 output 反推 locator/discriminator。adapter/equivalence validation 必须从本 approved map 读取输入、调用 helper 重算并比较，不得宣称普通 case validation 自动完成 migration-ID 验证。

示例 locator：

- opening owner locator：`$.opening.transactions[*]`，occurrence：`tx-opening-a`。
- create action locator：`$.create.request`，occurrence：`request-rg01-create`。
- invalid root locator：`$.invalid_inputs[*]`，occurrence：`missing-amount`。

GAP-03 关联的 7 个 source entries 必须完整保留：

- `$.case.id`：case namespace 与 root bootstrap。
- `$.opening.transactions[*].id`：缺失 opening version/posting-set IDs。
- `$.create.request.request_id`：create operation/state/confirmation/reconciliation IDs。
- `$.note_update.request.request_id`：note operation/state/confirmation IDs。
- `$.idempotency.repeated_request_id`：retry operation/state IDs。
- `$.distinct_reentry.request.request_id`：distinct operation/state/confirmation/reconciliation IDs。
- `$.invalid_inputs[*].id`：7 组 deterministic request/root/state/operation IDs；同时属于 GAP-01。

## 5. Target-only Required Fields

### Accounts

- 所有 account `currency` 由 case 的唯一 `CNY` declaration 确定。
- `asset-bank-a`：`owned_by_user=true`、`real_account=true`、`reconciliation_eligible=true`。
- `equity-opening-a` 与 `expense-account-breakfast`：`owned_by_user=false`、`real_account=false`、`reconciliation_eligible=false`。
- 上述 flags 由 RG-01 固定账户角色、kind、real-account fact 和 reconciliation assertions 联合确定，不从中文名称猜测。

### Opening Formal Chain

- `tx-opening-a` 保留 transaction ID，type 映射为 `opening_balance`。
- opening version/posting-set IDs 使用已实施的 GAP-03 helper 生成。
- opening posting IDs、账户、金额和币种保留；posting role 省略，因为当前 contract 只允许 opening balance 缺 role。
- opening postings 的 `reconciliation_eligible=false`，且不创建 posting reconciliation。

### Complete Balances

每个 state 必须包含 catalog 中全部三个账户，不只包含 v1 expected balance map 的资产账户：

| state | asset-bank-a | equity-opening-a | expense-account-breakfast |
| --- | ---: | ---: | ---: |
| opening | `1000.00` | `-1000.00` | `0.00` |
| after create | `964.20` | `-1000.00` | `35.80` |
| after note update | `964.20` | `-1000.00` | `35.80` |
| after idempotent retry | `964.20` | `-1000.00` | `35.80` |
| after distinct re-entry | `928.40` | `-1000.00` | `71.60` |

v1 balance values是 replay assertions。v2 balances 从 current formal postings 重算，不复制 partial maps。

## 6. Economic Time Mapping Approved and Implemented

冻结 fixture 中，opening/create/distinct 的同一个 `occurred_at` 同时支撑交易发生、报表归属和余额生效。这是已批准的 collapsed semantic evidence。RG-01 mapping 可把 exact source timestamp text 展开为 version 的 `occurred_at`、`statistics_at` 和 `effective_at`。

GAP-02 关联 entries：

- `$.opening.transactions[*].occurred_at`
- `$.create.request.occurred_at`
- `$.create.expected.transaction.occurred_at`
- `$.distinct_reentry.request.occurred_at`
- `$.distinct_reentry.expected.transaction.occurred_at`

约束：

- 该展开只适用于本 approved RG-01 mapping，不是任意 legacy time 的默认规则。
- note-update v2 复用旧 version 的三个 economic times；note update 不建立新的经济时间。
- source 未提供修改、创建或确认时间，因此 `created_at` 与 `confirmed_at` 继续省略，不从业务时间或 runtime time 补造。
- timezone 与 source timestamp text 的 offset/precision 始终原样保留。

## 7. Canonical Absence 与 Projection

- `create.candidate=null` 映射为 complete states 中的 empty `candidates`，不生成 null candidate。
- empty `evidence_refs` 映射为 empty `evidence` 和 `evidence_links`。
- expense posting 的 v1 `not_applicable` 映射为 `reconciliation_eligible=false` 且无 posting-reconciliation record；`not_applicable` 只允许作为 derived display。
- bank posting 的 `pending` 保留为 canonical posting reconciliation；transaction `pending` 从 eligible posting 推导为 `reconciliation_summary`。
- `budget=not_applicable` 映射为 report metric applicability，禁止写 `currency` 或 `amount`。
- balances、statistics、effective/funding/entity counts 和 operation deltas 全部重算。source expected values只作 equivalence assertions，不成为第二套事实。

## 8. Resolved Contract Gaps

### GAP-01 Outcome-conditional Sparse Rejected Operations - 已批准/已实施

**行为：** RG-01 冻结 7 个独立 rejected manual-expense attempts。每个 attempt 必须有独立 root、完整 unchanged baseline/result states、`operation_class=rejection`、rejected outcome 和零 delta。

**受影响范围：** 所有 16 个 `$.invalid_inputs[*]` normalized paths，包括 case ID、三个 input paths、accepted/field/reason、entity counts、balance/report/version/reconciliation zero-effect assertions。`$.invalid_inputs[*].id` 同时引用 GAP-03。

**实施：** accepted/no_change 保留 strict `$.operations[*].input`；rejected 使用 closed sparse `$.operations[*].attempted_input`、`operation_class=rejection` 和 required `outcome.field_path + reason_code`。validator 按固定 precedence 重算实际失败，且 unchanged states、空 deltas、空 returned IDs 继续机械验证。

`attempted_input` 中 `null` 表示明确 absence，省略表示未提交；不得补造 currency/time/note。所有 GAP-01 target paths 现已存在于 schema。

推荐 validation mapping：

| invalid case | field_path | reason_code |
| --- | --- | --- |
| `missing-amount` | `$.attempted_input.amount` | `missing_required_field` |
| `missing-payment-account` | `$.attempted_input.payment_account_id` | `missing_required_field` |
| `missing-secondary-category` | `$.attempted_input.category_id` | `missing_required_field` |
| `zero-amount` | `$.attempted_input.amount` | `must_be_positive` |
| `negative-amount` | `$.attempted_input.amount` | `must_be_positive` |
| `primary-category` | `$.attempted_input.category_id` | `secondary_category_required` |
| `inactive-secondary-category` | `$.attempted_input.category_id` | `category_inactive` |

invalid-case ID 是 stable occurrence discriminator，并用于 deterministic identity；不得使用 invalid_inputs array index。identity targets 为 `$.roots[*].id`、`$.operations[*].id`、`$.operations[*].attempted_input.request_id`、`$.states[*].id` 与 `$.states[*].root_id`。

**备选：** 新增 `manual_expense_attempt` action；把 invalid cases 留在 operation graph 之外；或把 field 与 reason 合并为 field-qualified reason code。最后一种会丢失结构化 field semantics，不能作为推荐方案。

**风险：** 非 outcome-conditional sparse input 会削弱 accepted/no_change 校验；开放对象会破坏 closed contract；合并 field/reason 会降低机器可查询性。

**匿名示例：** `missing-amount` 只保存实际提交的 payment account 与 secondary category，并明确 amount absence；不得补 `35.80`、currency、note 或 time。

### GAP-02 Collapsed Economic Times - 已批准/已实施

**行为：** 一个 v1 `occurred_at` 在 frozen fixture 中同时承载 occurrence、statistics attribution 和 balance effectiveness。

**实施：** contract 明确 RG-01 collapsed time 可用 exact text 展开为三个 roles；note update 复用旧 version economic times；`created_at/confirmed_at` 继续省略。该规则不是一般默认。

**备选：** migration hard reject，直到补齐独立时间；或修改 v2 contract 使部分 time role 对此类 migrated record 可选。

**风险：** 未批准展开可能伪造统计归属或余额生效含义；hard reject 会延后迁移。

**匿名示例：** 批准后可按 RG-01 mapping 将原始 timestamp 文本 `2026-01-15T08:30:00+08:00` 精确展开到 `occurred_at`、`statistics_at` 和 `effective_at`；该规则不可泛化到其他时间或 RG，也不得生成 `created_at` 或 `confirmed_at`。

### GAP-03 Deterministic Normalized Migration Locator - 已批准/已实施

**行为：** v1 缺少多个 root/state/operation/version/confirmation/reconciliation IDs，且输出必须可重复。

**实施：** contract 与公共 lazy helper 统一使用 normalized source locator + stable occurrence discriminator；采用本 artifact 的 `$/.key/[*]` 规范且不称其为 JSON Pointer。root 使用 `@root/root` bootstrap，descendant 再使用 root ID。adapter/equivalence validation 负责按 approved map 重算。

**备选：** 预先给 v1 结构补齐全部稳定 ID；或人工冻结所有 generated IDs。

**风险：** array index/遍历顺序会导致无语义重排改变 ID；helper 全局变更会影响后续 adapters；bootstrap 不明确会产生循环定义。

**匿名示例：** locator `$.opening.transactions[*]` + occurrence `tx-opening-a`，而不是 `/opening/transactions/*` 或 `/opening/transactions/0`。

### GAP-04 Approved Expected-output Status - 已批准/已实施

**行为：** expected v2 output 初稿需要 `draft_for_review`，完成独立 review 和用户批准后需要机器可表达的 approved 状态。

**实施：** enum 已增加 `approved`。生成初稿时为 `draft_for_review`；只有 expected output 独立 review 通过且用户明确批准后才能变为 `approved`。`approved` 不等同 `rule_version` 或 frozen business rule。

**备选：** 永久保留 draft 并在外部记录 approval；或建立独立 reviewed manifest 与 case hash。

**风险：** 无 approved 状态会使 publication gate 无法从 artifact 判断；未经 review 修改状态会形成虚假授权。

**匿名示例：** 首次生成的 RG-01 expected output 是 `draft_for_review`；review 与四项决策批准后才可发布同内容的 `approved` revision。

## 9. Semantic Equivalence Checklist

- [ ] Opening transaction、version、posting set 和两条 postings 构成唯一平衡 opening chain；generated ID 使用已批准 GAP-03 helper。
- [ ] Create 只新增一笔 expense、一版、一个 posting set、两条 postings、一项 confirmation 和 bank-posting pending reconciliation。
- [ ] Expense posting 为 `+35.80`，payment asset posting 为 `-35.80`，逐币种和为零。
- [ ] Note update 保留两版，current pointer 指向 v2，两版共享同一 posting set，资金 delta 为零。
- [ ] 每个 state 的 balances 覆盖全部三个 accounts，并与 current-version replay 精确一致。
- [ ] Day/month consumption、cash outflow、income、net-worth change 和 budget applicability 与 v1 断言一致；time role 使用已批准 GAP-02 mapping。
- [ ] 只有真实资产 posting 有 posting reconciliation；expense posting 使用 canonical absence；transaction summary 始终 derived。
- [ ] Idempotency 使用同一 `manual_expense`、同一 request ID 和完整等价 input，outcome 为 `no_change`，返回原 IDs，所有 delta 为零。
- [ ] Distinct re-entry 是新的 accepted `manual_expense`，新增独立 transaction/version/posting set/postings/confirmation/reconciliation，最终资产余额 `928.40`。
- [ ] 7 个 invalid inputs 各有独立 root、closed sparse `$.operations[*].attempted_input`、outcome `field_path + reason_code`、完整 unchanged states 和零影响，并使用 GAP-03 IDs。
- [ ] `create_order_relation`、`create_budget_result`、`create_import_candidate`、`create_external_evidence`、`invoke_network`、`invoke_sync`、`invoke_intelligent_suggestion` 全部保持 test-only negative assertions。
- [ ] candidate、evidence、relation、domain entity、audit link 等不适用集合明确为空，不用 null 或未声明对象代替。
- [ ] 任何 generated ID 都使用批准后的 normalized locator 与 stable occurrence，不使用 array index、遍历顺序、显示名或 runtime facts。
- [ ] 未提供的 created/confirmed time 均未伪造。
- [ ] expected output 初稿为 `draft_for_review`，只有该 output 独立 review 和用户明确批准后才可标记 `approved`。

## 10. Gate

四项 amendments 已于 `2026-07-16` 明确批准并实施：

1. **GAP-01：** outcome-conditional rejected `manual_expense`、closed sparse `attempted_input`、`operation_class=rejection` 与 `field_path + reason_code`。
2. **GAP-02：** RG-01 frozen v1 collapsed `occurred_at` 展开到 `occurred_at/statistics_at/effective_at`。
3. **GAP-03：** normalized source locator + stable occurrence discriminator、公共 helper 与非循环 root bootstrap。
4. **GAP-04：** `approval_status` 增加 `approved`，且仅在 expected output 独立 review 与用户明确批准后使用。

expected v2 output 仍未生成。当前 gate 允许下一步生成 RG-01 expected v2 output 初稿，初稿必须为 `draft_for_review`，并接受单独 semantic-equivalence review 和用户批准。

adapter 实现、v1 fixture rewrite 和 migration publication 仍关闭，不能由本 mapping approval 推定授权。
