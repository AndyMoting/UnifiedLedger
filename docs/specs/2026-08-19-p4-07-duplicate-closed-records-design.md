# P4-07 重复候选与关闭记录契约

**Status:** approved（2026-08-19 用户批准；本文件只冻结契约，不授权代码、schema、migration 或 runtime 实施）

## 1. 范围与边界

本批承载 RL-08：重复导入、合法相似记录、关闭/失败/无资金变化记录、非破坏性候选处置和跨批次行为。`request_id` replay、raw source identity、duplicate candidate 和 P4-08 mirror/evidence matching 是四个不同问题；本批不改变 D-098 已批准的 raw identity/collision，也不引入 P4-08 的 evidence link/reconciliation。

业务指纹只能提出候选，不能删除、折叠或替换来源事实。来源事实、duplicate candidate、人工处置和正式账目保持分层。任何未明确确认的候选均为零正式交易、零 posting、零余额、零报表和零对账影响。

## 2. 候选合同

每个候选至少包含以下逻辑字段（具体 Kotlin/JSON/SQL 形状留实施批规格）：

- `candidate_id`、`ledger_id`、`source_record_id`、`possible_existing_record_id`：稳定引用，不复制原始个人数据。
- `candidate_kind`：`same_source_replay`、`cross_batch_duplicate`、`legitimate_lookalike`、`identity_collision`、`closed_or_failed_related`。
- `status`：`open`、`confirmed_duplicate`、`confirmed_distinct`、`dismissed_lookalike`、`deferred`、`rejected`。
- `provenance`：候选字段来源、来源定位、机械解析或规则推导层级；候选不得伪装成 source fact。
- `confidence`：`exact`、`strong`、`weak`、`unresolved`；阈值和分数不得由实现静默发明。
- `rule_id`、`rule_version`、`generated_at`、`comparison_snapshot`：可复核规则和当时输入快照。
- `review_decision`、`reviewed_at`、`reviewer_reference`、`decision_reason`：人工确认/驳回的追加记录；无人处置时为空。

候选是追加、可审计实体；状态变化保留历史。候选确认不会自动确认导入候选，也不会替代正式交易确认。`confirmed_duplicate` 只表示该来源不应再产生经济效果；原始来源和候选仍保留。`confirmed_distinct`/`dismissed_lookalike` 明确允许两条合法记录各自进入后续确认流程。

## 3. 身份与重复边界

1. 同一 `request_id` 且请求内容等价：按既有 replay 合同返回原稳定结果，零新写入。
2. 同一 raw identity 且来源事实等价：按 D-098 返回既有 source/candidate 引用，零新写入；不称为业务 duplicate。
3. raw identity 不同但业务字段相似：仅生成 `cross_batch_duplicate` 候选；不得覆盖 raw identity、改变来源定位或破坏性去重。
4. 业务相似而具有独立来源、时间或经济理由的记录：可生成 `legitimate_lookalike`，默认 `deferred`，两条来源均保留。
5. raw identity 碰撞或同一身份的来源事实不等价：沿用 D-098 `identity_collision` hard reject，零写入；不得降级为 duplicate 候选或 fallback 身份。

候选比较只使用已存在的可靠 source facts 和明确的派生事实。用户分类、账户映射、可变别名、AI 建议和通道总额不得成为身份或自动折叠依据。

## 4. 同批次与跨批次行为

- 同一批次中两个不同 raw records 即使完全相同，也保留 multiplicity，分别生成来源记录；可生成候选，但不先到先得折叠。
- 跨批次重复导入只允许追加 duplicate candidate/lineage；确认重复后后续正式确认必须返回可解释的 `duplicate_not_confirmable` 或等价类型化结果，零正式效果。
- 已产生正式账目的记录，后到重复来源只能追加来源 lineage/candidate 处置，不创建第二笔 transaction、posting、version、消费、现金流、余额或 report effect。
- 候选生成、人工驳回、候选重试和状态重放必须幂等；等价输入返回相同候选和完整状态快照，冲突输入类型化拒绝、零写入。

## 5. 关闭、失败及非正常记录

`交易关闭`、`失败`、`撤销`、`不计收支` 或其他来源状态只有在来源合同明确证明无资金变化时，才可标记 `closed_or_failed_related`。状态事实原样保留，不能被解释为成功、退款或冲回。

- 无资金变化的关闭/失败记录：可保存 source fact 和 candidate，进入 `deferred` 或人工 `dismissed_lookalike`；有效资金分录数必须为 0。
- 状态未知、金额/时间不可靠、或可能已产生资金变化：`unresolved`/`deferred`，不得确认 duplicate、不得创建或抵销正式账目。
- 退款、真实冲回和修正分别遵守既有 RG-07/RG-12 合同；不能用 duplicate 状态吞掉独立经济事件。
- 任何人工“确认无资金变化”仅关闭该候选/来源处置，不创建零金额交易或零金额 posting。

## 6. 碰撞与歧义矩阵

| 情形 | 处置 | 正式效果 |
| --- | --- | --- |
| 等价 request replay | 返回原结果 | 零新写入 |
| 等价 raw identity replay | 返回原 source/candidate | 零新写入 |
| 不同 raw identity、强相似且唯一 | 生成候选，等待人工确认 | 零写入 |
| 同窗/同批次多个相似目标 | `deferred`，列出竞争者 | 零写入 |
| 证据不足或状态未知 | `deferred`/`unresolved` | 零写入 |
| 明确合法的相似记录 | `confirmed_distinct` | 后续各自明确确认 |
| raw identity collision | hard reject | 零写入 |
| 已正式入账的后到重复来源 | 追加 lineage/candidate | 不重复经济效果 |

通道级总额只作诊断展示，不能完成候选、链接证据、改变对账或压制记录。

## 7. 实施与验收门

本契约批准后，实施批仍须另行登记具体 schema/迁移（如需）、阈值、诊断码、状态历史列、候选确认端口和匿名 RL-08 fixtures，并经过单 writer、独立规格/质量评审、distinct verifier 及完整受影响套件验证。若实现证明现有 `import_*` 表无法承载候选，优先采用非破坏性加性迁移；在本决策批准前不得预先创建 schema 或 runtime。

## 8. 匿名验收锚点

RL-08 必须覆盖：同 request replay、同 raw identity replay、同批次重复、跨批次重复、合法 lookalike、关闭成功/关闭失败、状态未知、identity collision、候选人工确认/驳回、后到来源 lineage，以及每个路径的零/一笔正式效果计数。所有 fixtures 必须来源中立、合成且不含真实订单、账户锚点、个人标识或绝对路径。
