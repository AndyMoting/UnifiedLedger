# RG-04 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-04.json`、`docs/specs/2026-07-15-rg-04-mixed-payment-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md`、`docs/GOLDEN_SCHEMA.md` 与正式 `D-008`、`D-011`、`D-015`、`D-017`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058` 约束。外部 `CORE_ACCEPTANCE_PLAN.md` 的 RG 编号已经过时，仅作为早期覆盖证据，不覆盖当前冻结的 RG-04 语义。本映射只定义 RG-04 v1 到 v2 的逐路径迁移；expected 已生成、通过独立复审并获得用户明确批准，`approval_status=approved`，但 adapter generation、v1 fixture rewrite 和 publication 仍保持关闭。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `406`
- leaf occurrences: `863`
- classified/unclassified: `406/0`
- classifications: `preserve 112`, `map 130`, `derive 159`, `reject 5`
- dispositions: `ready 401`, `test_only_exclusion 5`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `case` | v2 case metadata, one `CNY` currency declaration, and root purposes |
| `catalog` | every complete state's account/category catalog; catalog ID `mixed_payment` becomes `relation.type`, while display and management metadata move to the registered relation payload |
| `opening` | opening transaction, version, posting set, postings, and replayed initial balances |
| `manual_lifecycle` | ordered explicit manual mixed-expense creation followed by credit-principal repayment, each with complete states and projections |
| `import_lifecycle` | source intake, pending mixed-payment candidate, explicit completion and confirmation, then liability mirror-evidence merge |
| `missing_funding_leg` | incomplete source and pending candidate with zero formal effects, no guessed account, and a no-change retry |
| `idempotency` | no-change replays preserving transactions, postings, relation identity, candidate identity, evidence links, reports, and reconciliation |
| `invalid_manual_inputs` | independent sparse rejected mixed-expense operations with exhaustive zero deltas |
| `forbidden_side_effects` | test-only negative assertions; not serialized into v2 |
| `out_of_scope` | test-only ownership assertions for RG-05, RG-06, RG-07, and RG-12; not serialized into RG-04 |

## IDs And Time

Existing stable account, category, transaction, version, posting-set, posting, request, source, candidate, evidence, evidence-link, and association-group IDs are preserved where v2 owns the same identity. `mixed_payment` is a relation type discriminator, not an instance ID; `association-group-rg04-manual` and `association-group-rg04-imported` remain the two stable relation instance IDs. Missing root, state, operation, opening version/posting-set, confirmation, candidate-status, and posting-reconciliation IDs use the contract's deterministic migration helpers with normalized source locator plus a stable request ID, source ID, evidence ID, invalid-case ID, operation ID, or case ID discriminator. Array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The opening, manual purchase, repayment, and confirmed import `occurred_at` values simultaneously carry occurrence, statistics attribution, and balance effectiveness in the frozen fixture. Under this RG-04 path-specific rule, the exact timestamp text expands to `transaction_versions[*].occurred_at`, `statistics_at`, and `effective_at`. This is not a general default and must never synthesize `created_at` or `confirmed_at`. Imported source and mirror `observed_at` remain immutable evidence times; the confirmed imported purchase uses the source payment time as its initial economic and statistics time.

## Frozen Semantics

The mixed purchase is one `expense` transaction with expense `+120.00`, asset funding `-70.00`, and credit-liability funding `-50.00`. Purchase-day consumption is `120.00`, cash outflow is `70.00`, income is zero, and net-worth change is `-120.00`. Original amount, discount, and settled amount remain settlement explanation facts; discount creates no posting.

The later `credit_repayment` transaction has asset `-50.00` and liability `+50.00`. It records repayment-day cash outflow `50.00` while consumption, income, and net-worth change remain zero; lifecycle consumption therefore stays `120.00` rather than being counted again.

The system-managed `mixed_payment` relation uses canonical `member_refs` for exactly one existing purchase transaction and its two existing real-account funding postings. `funding_components` do not create identities: each component reuses one posting member ID and carries only its account, positive display amount, and currency. Relation payload separately carries display, system-management, composition-total, and no-generic-order constraints. Mirror evidence keeps the same transaction version, posting set, postings, relation ID, members, and funding composition; it adds only the later source/evidence/link and moves the liability posting from pending to matched, making the transaction summary fully matched.

The expense posting has `reconciliation_eligible=false` and has no `posting_reconciliation` record. Asset and liability funding postings are independently eligible and own their pending or matched reconciliation records. Complete and incomplete imports remain `pending_confirmation` with zero formal effects until explicit confirmation; reconciliation never changes balances or reports.

## Operation Discriminators

| v1 family | `action_type` | `operation_class` |
| --- | --- | --- |
| manual mixed purchase | `manual_mixed_expense` | `creation` |
| credit-principal repayment | `credit_principal_repayment` | `creation` |
| complete or missing-leg source intake | `ingest_mixed_payment_source` | `creation` |
| candidate completion and confirmation | `confirm_mixed_payment_candidate` | `creation` |
| liability mirror-evidence merge | `merge_mixed_payment_mirror_evidence` | `reconciliation` |
| invalid mixed-expense attempt | `manual_mixed_expense` | `rejection` |

`input.kind` is consumed to derive these top-level discriminators and is not retained as an open payload discriminator. Every action receives a closed input; rejected attempts receive a closed sparse `attempted_input`. A no-change retry retains the original action type and operation class, returns the original stable entities, and creates no formal, relation, evidence, report, or reconciliation effect.

## Closed Gaps

1. `RG04-GAP-01` is closed with approved implementation of the explicit action-type/class pairs, closed accepted/no-change inputs, and sparse rejected attempted input.
2. `RG04-GAP-02` is closed with approved implementation of complete and missing-leg source/candidate payloads, provenance, confidence, completeness, and confirmation requirements.
3. `RG04-GAP-03` is closed with approved implementation of distinct mixed-funding and credit-principal repayment posting roles.
4. `RG04-GAP-04` is closed with approved implementation of separate relation identity/type, canonical members, and non-economic funding-component payloads.
5. `RG04-GAP-05` is closed with approved implementation of source-linked financial evidence subtypes and posting-level reconciliation ownership.

All five resolved gaps have status `approved_implemented`; no unresolved contract gap remains.

The existing `expense` and `credit_repayment` transaction types, generic transaction/version/posting-set/posting chains, exact decimals, complete balances, report metrics, operation deltas, candidate statuses, confirmations, `real_account_posting` evidence-link role, posting reconciliation, transaction reconciliation summary, and deterministic migration IDs are not contract gaps.

## Gate

- status: `approved`
- expected output gate: `completed`
- unresolved gap count: `0`

The expected v2 output has been generated, passed independent review, and received explicit user approval; its `approval_status` is `approved` and the expected-output gate is `completed`. Adapter implementation, v1 fixture rewrite, and publication remain closed.
