# RG-05 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-05.json`、`docs/specs/2026-07-15-rg-05-merged-payment-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md`、`docs/GOLDEN_SCHEMA.md` 与正式 `D-008`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-059` 约束。外部 `CORE_ACCEPTANCE_PLAN.md` 的 RG 编号已经过时，仅作为早期覆盖证据，不覆盖当前冻结的 RG-05 语义。本映射只定义 RG-05 v1 到 v2 的逐路径迁移，不授权 schema、adapter、expected output 或 fixture rewrite。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `549`
- leaf occurrences: `1407`
- classified/unclassified: `549/0`
- classifications: `preserve 46`, `map 420`, `derive 76`, `reject 7`
- dispositions: `ready 300`, `requires_contract_amendment 242`, `test_only_exclusion 7`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `schema_version` / `case` | required v2 envelope, case metadata, and the one declared `CNY` currency |
| `catalog` | every complete state's account/category catalog; category kind is derived through its posting account |
| `opening` | opening transaction, version, posting set, postings, and complete replayed balances |
| `manual_path` | closed manual merged-payment operation, confirmation, one formal expense chain, two independent consumption records and allocations, one relation, projections, and per-posting reconciliation |
| `import_path` | closed source intake, pending candidate, explicit confirmation, then item-receipt evidence merge with no duplicate formal entities |
| `allocation_failures` | closed rejected allocation operations with an explicit gap or conflict and exhaustive zero formal effects |
| `idempotency` | no-change replays returning the original candidate, transaction, records, allocations, relation, links, and complete state |
| `invalid_manual_inputs` | independent sparse rejected manual attempts with baseline balances and zero formal/statistical/reconciliation deltas |
| `forbidden_side_effects` / `out_of_scope` | test-only negative assertions; not serialized into v2 |

## IDs And Time

Existing stable account, category, transaction, posting, source, candidate, evidence, evidence-link, consumption-record, item-allocation, relation, request, and operation IDs are preserved where v2 owns the same identity. Missing root, state, opening version/posting-set, confirmation, status-history, and posting-reconciliation IDs use the contract's deterministic migration helpers with a normalized source locator plus a stable source ID, request ID, operation ID, item ID, evidence ID, invalid-case ID, or case ID discriminator. Array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

RG-05 has no collapsed-time approval. The source `opening.transactions[*].occurred_at` maps only to `transaction_versions[*].occurred_at`; it does not synthesize `statistics_at`, `effective_at`, `created_at`, or `confirmed_at`. The common payment statistics timestamp maps only from the explicit RG-05 statistics fields. Each item source timestamp remains immutable source/business evidence, while both independent consumption records use the common actual-payment statistics timestamp. Item-level statistics-time override remains prohibited.

## Frozen Semantics

The formal completed payment is exactly one `expense` transaction: item A expense `+40.00 CNY`, item B expense `+60.00 CNY`, and one owned real asset payment posting `-100.00 CNY`. The expense postings are `reconciliation_eligible=false` and have canonical absence from `posting_reconciliations`; the one real asset posting is eligible and is the only financial reconciliation target. The financial transaction/relation summary is derived from eligible postings and never changes balances or reports.

The two consumption records remain independent in identity, category, detail, amount, source time, and expense-posting reference. Current v2 `domain_entities` already own each record's `id`, `type=consumption_record`, and minimal `amount`, `category_id`, `currency`, `expense_posting_id`, and `statistics_at` payload fields. Current v2 also owns each allocation's `id`, `type=item_allocation`, `consumption_record_id`, `expense_posting_id`, `category_id`, `amount`, and `currency`. These fields are `ready`; only details, source/evidence bindings, completeness, and the closed shared-payment relation payload remain contract gaps.

The two item allocations retain the same independent identities and together equal `100.00 CNY`, but they do not own payments. A system-managed `merged_payment` relation has one formal transaction and its one payment posting together with the two allocation members. It has display name "合并付款", cannot become a generic order lifecycle, and cannot create a second transaction, posting, or cash flow.

Imported bank and item facts remain source/candidate facts until explicit confirmation. The bank evidence link targets only the unique asset posting with the existing typed posting target/role fields and may change that posting's reconciliation. Each item receipt uses `item_allocation_fact` and targets exactly one `item_allocation` domain entity; it does not target the related consumption record and cannot change financial reconciliation. A later item receipt merge adds only its source/evidence/link and independent completeness state.

The payment-day reports remain consumption `100.00 CNY`, the two category consumptions `40.00 CNY` and `60.00 CNY`, cash outflow `100.00 CNY`, income `0.00 CNY`, net-worth change `-100.00 CNY`, and budget not applicable. Original amount, discount, and settled amount explain settlement only; discount never produces an additional posting.

## Unresolved Gaps

1. `RG05-GAP-01` (operation registry, `51` affected paths): register closed RG-05 action types and accepted/no-change/rejected payloads for manual creation, source intake, explicit confirmation, item-receipt merge, allocation failure, and retry. Inputs must remain strict, rejected attempts sparse, and pending/invalid paths formally zero-effect.
2. `RG05-GAP-02` (import source and candidate payloads, `95` affected paths): register closed bank-payment and item-fact source/candidate payloads retaining immutable source times, suggested categories, source/evidence identities, provenance, confidence, completeness, status history, and required confirmation without formal posting creation.
3. `RG05-GAP-03` (`merged_payment` relation payload, `46` affected paths): register one system-managed relation instance that references one formal transaction, its one real payment posting, and exactly two current `item_allocation` domain entities whose exact amounts close to the payment total; prohibit generic order lifecycle and a second economic event. The allocations' current minimal fields are not part of this gap.
4. `RG05-GAP-04` (independent consumption/item allocation lifecycle extensions, `24` affected paths): add only the unregistered details, immutable item source time, source/item/evidence bindings, completeness, and no item-level statistics override boundary. Current minimal `consumption_record` and `item_allocation` fields are not part of this gap.
5. `RG05-GAP-05` (bank payment and item receipt evidence payloads, `26` affected paths): register the two evidence payload families while retaining current typed link targets and roles. Bank evidence is limited to the exact asset posting. Every item receipt maps current `target_kind=domain_entity`, `target_id` to the allocation ID, and `role=item_allocation_fact`; it never targets a `consumption_record`.

The existing envelope and state collections, exact decimals, catalog, `expense` transaction type, `expense` and `payment_asset` posting roles, complete balances, reports, operation deltas, `consumption_record` and `item_allocation` minimum fields, typed evidence-link target/role fields, posting reconciliation, transaction reconciliation summary, and deterministic migration IDs are not contract gaps.

## Gate

- status: `needs_contract_amendment`
- expected output gate: `closed`
- unresolved gap count: `5`

Expected v2 output remains closed until all five gaps are approved and implemented. No schema change, adapter, expected output, or fixture rewrite is authorized by this mapping.
