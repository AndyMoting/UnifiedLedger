# P4-07 重复候选与关闭记录实施规格

**Status:** proposal（承接已批准 D-104；本文件冻结实现候选，尚未授权 writer 修改 runtime 或 schema）

## 1. 目的与范围

本实施批实现 D-104 的 RL-08 维度，且只实现它：不同 raw identity 间的非破坏性 duplicate candidate、人工处置、确认重复后的 formalization 阻断、以及已由来源合同证明的关闭/失败零资金处置。它不改变 D-098 raw identity/replay/collision，不实现 P4-08 matcher/evidence-link/reconciliation，不实现 P4-06，也不保存整文件或引入产品 Clock/随机 ID。

当前 `main@fd57808` 已是 schema v23。P4-07 若实施持久化，唯一目标为 additive v23 -> v24；所有 `rgXX_` 竖井保持只读语料。

## 2. 冻结比较规则

`p407_exact_business_tuple_v1` 是唯一首版候选规则。它比较同一 ledger、不同 `source_id` 的以下已持久化 source facts：

1. `record_kind` 和 `contract_version`；
2. `amount_minor`、`currency_code`、`currency_precision`；
3. `occurred_at` 的完整规范文本；
4. `direction_token`；
5. `status_token`，包括 null/presence。

该规则是精确相等比较，无时间窗、金额容差、字符串归一化、用户分类、账户映射、对手方、订单号、AI、通道总额或“最先一条获胜”。只有完整可靠事实才可参加；`VALID_INCOMPLETE` 与状态为 `unresolved` 的记录不产生匹配候选。候选的 `provenance = source_declared + mechanical_decode + p407_exact_business_tuple_v1`，`confidence = exact`，`rule_id = p407_exact_business_tuple_v1`、`rule_version = 1`。

对于每个新 intake，向所有已存在且匹配该元组的 source 各追加一条有向候选（新 source 为 `subject_source_id`，已有 source 为 `possible_existing_source_id`）。同批次和跨批次相同；并列来源不选“赢家”。raw identity replay 不到达该步骤，identity collision 按 D-098 在此之前 hard reject。

## 3. 领域与应用合同

新增 `ImportDuplicateCandidateId`、`ImportDuplicateReviewId` 与下列产品枚举：

- `ImportDuplicateCandidateKind`: `EXACT_BUSINESS_TUPLE`、`CLOSED_OR_FAILED_NO_FUNDS`。
- `ImportDuplicateStatus`: `DEFERRED`、`CONFIRMED_DUPLICATE`、`CONFIRMED_DISTINCT`、`DISMISSED_LOOKALIKE`、`REJECTED`。
- `ImportFundingState`: `SETTLED`、`NO_FUNDS`、`UNRESOLVED`。

`ImportSourceFacts` 增加 `fundingState`、其 rule id/version 与 source status token 仍保留。`SETTLED` 允许既有 candidate lifecycle；`NO_FUNDS` 只能创建 source/evidence、普通 import candidate（终态 `incomplete`）与一条 `CLOSED_OR_FAILED_NO_FUNDS` duplicate candidate，正式 transaction/posting 数为零；`UNRESOLVED` 仍为现有 `VALID_INCOMPLETE`，不生成任何 duplicate candidate。

新增 `ReviewImportDuplicateCandidate` 用例和 commit port。请求显式携带 ledger/request/candidate、预期比较快照、review decision、reason token、reviewed_at、reviewer reference 和由应用分配的 review/history IDs；`generated_at` 也必须由请求显式提供，禁止读取产品 Clock。`comparison_snapshot` 是 privacy-safe、结构化的不可变比较投影：record kind/version、amount/currency/precision、occurred-at、direction、status presence/value 和 subject/possible-existing source IDs；不复制原始 provider payload、订单、对手方或账户映射。

review 使用专属 `import_duplicate_review_request` claim owner，而不改写共享 `import_request`。其主键为 `(ledger_id, request_id)`，operation 固定 `review_duplicate`，携带 immutable input fingerprint 与终态 outcome。store 必须 claim-first：先以 `PENDING` 插入 claim；竞争或重试方读取同一 claim 的 snapshot/receipt，完全等价则返回原 receipt，不等价将该 claim 终结为 `CONFLICT` 并返回 `SPINE_REQUEST_IDENTITY_CONFLICT`。任何 typed rejection 或失败注入回滚 claim、snapshot、history、receipt 和所有其他写入，使同一 request 可由已修正输入重试。首个成功路径在同一 outer transaction 内写 snapshot、status history、review receipt 并把 claim 转为 `ACCEPTED`；不得依靠裸 UNIQUE 异常推断 replay。

同 request 不同内容、candidate 不存在、fingerprint stale、跨 ledger、非 `DEFERRED` 或选择 `CONFIRMED_DUPLICATE` 时 subject source 不具备规则约束，均类型化拒绝并零写入。`CONFIRMED_DUPLICATE` 是唯一阻断 formalization 的决定：随后 `ConfirmImportCandidate` 必须返回 `SPINE_DUPLICATE_NOT_CONFIRMABLE`，不调用 formal factory、不分配 formal IDs、无 transaction/posting/confirmation/status residue。

`CONFIRMED_DISTINCT` 和 `DISMISSED_LOOKALIKE` 仅终结 duplicate candidate，不改变其 subject 的 normal import candidate；两个 source 仍需各自明确确认。`REJECTED` 只表示 review 请求被人工否决，亦不改变正式候选。任何 review 都不写 P4-08 evidence link/reconciliation。

## 4. 来源状态与关闭记录

来源 parser/adaptor 只有在一个已经单独批准的 provider status contract 明确证明“没有资金变化”时，才可以传递 `NO_FUNDS`。D-101/D-102 当前并未冻结 WeChat/Alipay 关闭或失败 token 的零资金语义，因此本实施批不新增或推断任何 provider token 映射；首版仅接受来源中立的显式 funding-state port 输入，并要求 raw status、source rule id/version、`exact` confidence 一并保存。退款、撤销中资金状态不确定、`不计收支`、未知 token 和缺少状态一律为 `UNRESOLVED`，不得借 `NO_FUNDS` 入账或关闭。provider token 映射另需来源契约修订和独立批准。

`NO_FUNDS` intake 始终保存 source/evidence 与 provenance，但创建的 import candidate 不可 formalize，且其唯一 source-derived duplicate candidate 为 `CLOSED_OR_FAILED_NO_FUNDS`、初始状态 `DEFERRED`、`possible_existing_source_id = NULL`。它不与普通记录进行 tuple 比较，也不能用 review 产生退款、冲回、零金额 transaction 或 posting。

## 5. v23 -> v24 持久化

迁移新增以下非 `rgXX_` append-only owners，并在一笔 outer transaction 内完成 schema upgrade：

- `import_duplicate_candidate`：`candidate_id`、ledger、subject source、nullable possible existing source、kind、comparison fingerprint、privacy-safe comparison snapshot、provenance、confidence、rule id/version、explicit `generated_at`、creation request；exact-tuple rows require non-null possible-existing source and are unique on `(ledger, subject, possible_existing, kind, comparison_fingerprint)`; `CLOSED_OR_FAILED_NO_FUNDS` requires NULL possible-existing source and is unique on `(ledger, subject, kind)` through a partial unique index. Both shapes have CHECK guards, so SQLite NULL uniqueness cannot bypass intake/retry idempotence.
- `import_duplicate_status_history`：candidate、连续 sequence、history ID、status、request、operation class；首条只能 `DEFERRED`，后续只能一个终态。
- `import_duplicate_review_request`：ledger/request claim，operation 固定 `review_duplicate`、input fingerprint、`PENDING|ACCEPTED|CONFLICT` outcome 和 reason；它是唯一 replay/conflict owner，不与 `import_request` 混用。
- `import_duplicate_review_snapshot`：review request、candidate、expected comparison fingerprint、decision、reason token、reviewed_at、reviewer reference 与 explicit generated-at；snapshot/receipt 一对一。
- `import_duplicate_review_receipt`：request、candidate、review/history references、outcome。

`import_source_record` 追加 funding state/rule/version 只能以重建表方式进行，必须保留所有 v23 import 和 P4-08 FK/trigger/SQLDelight query 语义；迁移为既有 source 赋 `UNRESOLVED`，不回推 NO_FUNDS 或 duplicate candidate。所有新表具有 ledger-scoped FK、不可变 guard、sequence/transition guard 和 claim/snapshot/receipt 的 replay uniqueness。不得给 `import_candidate` 增加 second source、不得改变其 `UNIQUE(ledger_id, source_id)`。

确认路径的 duplicate block 使用 `EXISTS` 查询，只检查 subject source 对应的 `CONFIRMED_DUPLICATE`；这不是 evidence/reconciliation 关系，不能跨 ledger，也不改变既有 confirmation/replay 的行为。

## 6. 匿名 oracle 与验证

新增 P4-07 canonical full-state oracle，比较全部 import source/evidence/candidate/status/confirmation/receipt、duplicate candidate/status/review snapshot/receipt、formal graph、balances、report 与 P4-08 reconciliation 状态。至少覆盖：

1. 相同 request 与相同 raw identity replay 均零新增 duplicate rows；identity collision hard reject；
2. 同批次与跨批次 exact tuple 每个已有 source 各创建一条 deferred candidate，且两条 source 都存在；
3. multiple lookalikes 不选择 winner；confirmed distinct/dismissed lookalike 后 source 仍能各自 formalize；
4. confirmed duplicate 阻断 subject formalization，并证明 allocation callback 和全部 formal IDs 未被消费；
5. review replay、request conflict、stale fingerprint、non-deferred review、concurrent claim loser 与 failure injection 全部零残留；并分别证明 exact-tuple 与 NULL-target NO_FUNDS 候选的 retry/concurrency uniqueness；
6. closed/failed `NO_FUNDS` 保留 source/evidence/候选/处置，但有效资金分录、余额、report、evidence link、reconciliation effect 均为 0；
7. unknown/refund/non-settled ambiguity 是 `UNRESOLVED` 而非 `NO_FUNDS`；
8. fresh v24、v1 -> v24、v23 -> v24 populated upgrade、reopen、P4-08 coexistence 与 late migration failure rollback。

验证顺序为聚焦 application/data tests、migration verifier、三个 JVM 模块、Android compile、Python suite、`project_docs`、`verify-project -Scope full -AllowDirty`，最后 clean trace before publication。

## 7. 排除项

不实现全局 dedup、自动删除、自动 confirmation、AI/阈值匹配、时间容差、来源级结算偏移、P4-08 matcher/reconciliation 写入、P4-06、整文件保留、产品 ID/Clock 或 UI。
