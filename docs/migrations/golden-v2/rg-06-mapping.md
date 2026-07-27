# RG-06 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-06.json`、`docs/specs/2026-07-15-rg-06-staged-payment-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md`、`docs/GOLDEN_SCHEMA.md` 与正式 `D-060` 约束。它只定义 RG-06 v1 到 v2 的逐路径迁移，不授权 schema amendment、adapter、fixture rewrite 或 expected output 生成。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `1188`
- leaf occurrences: `3610`
- classified/unclassified: `1188/0`
- classifications: `preserve 666`, `map 216`, `derive 299`, `reject 7`
- dispositions: `ready 411`, `requires_contract_amendment 770`, `test_only_exclusion 7`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `schema_version` / `case` | required `contract` and `contract_version` envelope, case metadata, CNY declaration, isolated roots, states, and operations |
| `catalog` | every complete state's account/category catalog and posting reconciliation eligibility |
| `opening` | balanced opening transaction, version, posting set, postings, and replayed initial balances |
| `baselines` | complete balance/report projections plus the planned staged-payment lifecycle domain entity and independent statuses |
| `manual_path` | group creation, two independent actual-payment transactions, fulfillment/completion status operations, evidence links, and full final state |
| `import_path` | immutable payment sources, pending candidates, exact group/role/category/account confirmation, two formal payments, evidence binding, and mirror merge |
| `invalid_baselines` | independent complete states used by rejected operations; empty collections remain canonical absence |
| `invalid_inputs` | sparse rejected attempted inputs, exact field/reason, named baseline, and exhaustive zero deltas |
| `idempotency` | no-change retries returning original stable identities and preserving complete manual/import states |
| `out_of_scope` / `forbidden_side_effects` | test-only exclusions; never serialized into v2 product state |

## Accounting Ownership

The current v2 formal-ledger contract can carry both actual payments without amendment. The deposit creates one `expense` transaction with expense `+80.00 CNY` and payment asset `-80.00 CNY`; the final payment creates a separate `expense` transaction with expense `+220.00 CNY` and payment asset `-220.00 CNY`. Each transaction has its own stable version, posting set, expense posting, asset posting, balance effect, consumption record, cash-flow attribution, and posting reconciliation.

The unpaid `220.00 CNY` after the deposit remains only a staged-payment display fact. It does not create a liability, payable account, clearing account, transaction, posting, consumption, cash flow, income, net-worth change, or reconciliation record. Fulfillment and completion operations append state history only and cannot create a third transaction or repeat consumption.

`payment_progress` and `fulfillment_status` remain separate planned status names. Payment progress is derived only from confirmed actual payments (`unpaid`, `partially_paid`, `paid_in_full`); fulfillment is explicitly controlled (`in_progress`, `fulfilled`) and may be `fulfilled` while `220.00 CNY` remains due. Group reconciliation (`pending`, `partial`, `complete`) is a third independent projection based only on the two payment asset postings. Current `payment_progress`, `fulfillment_status`, and group `reconciliation` exist only in `derived_statuses`; none is duplicated in the lifecycle payload. Every complete state emits exactly one of each status name, all with `target_kind=domain_entity` and the staged-payment lifecycle entity ID as `target_id`. Payment progress and fulfillment derive from the latest history item; reconciliation derives independently from only the eligible payment-asset posting reconciliations.

Let `N` be the count of distinct installment members and `M` the count whose exact eligible `payment_asset` posting reconciliation is `matched`. Group reconciliation is `pending` when `N = 0` or `M = 0`, `partial` when `0 < M < N`, and `complete` when `N > 0` and `M = N`. Missing records, ineligible installment postings, non-`matched` statuses, and reconciliations for unrelated postings do not increase `M`.

Every lifecycle snapshot satisfies `total_amount = paid_amount + due_amount`. Current `paid_amount` is the sum of the relation's distinct installment amounts, while each history item's `paid_amount` is the cumulative sum of installments introduced by its own and earlier payment-confirmed items. Payment progress is `unpaid` exactly at zero paid, `partially_paid` exactly when paid and due are both positive, and `paid_in_full` exactly at zero due. Current lifecycle totals and payment/fulfillment projections equal the latest history item.

## Relation And Payment Identity

One stable `staged_payment` relation owns only identity linkage: `id`, `type`, typed set-like `member_refs`, and the closed empty payload. The initial relation contains exactly the lifecycle member and `payload={}`; each confirmed payment appends its distinct `installment_payment` member, up to two payment members total. The relation permits at most one `deposit` and at most one `final`, and a final member requires the deposit member. Member-array order is set-like and proves no payment chronology. Retries append nothing. The relation carries no amount, due state, category, display/system fact, payment progress, fulfillment status, reconciliation status, or history payload. Every normalized `group.id` occurrence maps only to `relations[*].id`; every `group.type=staged_payment` occurrence maps only to `relations[*].type`. The instance ID never determines the type, and the type discriminator is never reused as an instance ID.

A separate `staged_payment_lifecycle` domain entity owns total, paid, due, currency, category, display/system facts, and ordered immutable `payload.state_history`. The history is authoritative. Every history item preserves its exact `id`, `event`, `occurred_at`, `total_amount`, `paid_amount`, `due_amount`, `payment_id`, `payment_progress`, `fulfillment_status`, and `state_transition_effect_count` field under `payload.state_history[*]`; migration derives a continuous append-only sequence from operation order without flattening any history field into a current status or relation payload. Current status values and their presentation labels are derived projections, not lifecycle payload fields.

The deposit and final payment remain two stable `installment_payment` domain entities. Each binds exactly one role, positive display amount, currency, funding account, formal transaction, expense posting, asset posting, actual payment time, statistics time, and optional immutable source payment time. All references resolve in the same root. The referenced transaction is `expense`; its current version's posting set owns both referenced postings. The asset posting has role `payment_asset`, uses the installment funding account and currency, and equals the negative installment amount. The expense posting has role `expense`, uses the lifecycle's second-level category and currency, and equals the positive installment amount. Current-version `occurred_at` and `statistics_at` equal the installment's `actual_payment_at` and `statistics_at`, respectively. Relation `member_refs` link only typed lifecycle/payment identities; formal transactions and postings remain referenced from the installment-payment payload and are never copied into relation state.

## Time And IDs

Existing case, account, category, transaction, version, posting-set, posting, payment, relation, request, source, candidate, evidence, and evidence-link IDs are preserved when v2 owns the same identity. The staged-payment lifecycle domain entity receives a separate deterministic migration ID and does not reuse `group.id`, which remains only the relation instance ID. Missing root, state, operation, confirmation, lifecycle entity, consumption-record, reconciliation, and history wrapper IDs use deterministic migration helpers with normalized source locator plus a stable source ID, request ID, relation ID, entity ID, or case ID discriminator. Display names, traversal order, array index, runtime time, and local paths are forbidden identity discriminators.

The opening `occurred_at` path is the only collapsed timestamp expanded to `occurred_at`, `statistics_at`, and `effective_at`; the fixture and RG-06 tests explicitly prove those three roles share the same value. Actual payment transactions already carry distinct `occurred_at` and `statistics_at`: `occurred_at` maps only to transaction `occurred_at` and `effective_at`, while the sibling `statistics_at` maps separately. No `occurred_at`, `actual_payment_at`, `source_payment_at`, group creation time, or status time generates `created_at` or `confirmed_at`. A recorded candidate confirmation `confirmed_at` maps only from that exact source field.

The final payment operation and payment-confirmed history item follow the deposit equivalents, and final payment time is strictly later than deposit payment time. The history items also have strictly increasing `occurred_at` values; imported deposit and final source payment times retain the same strict order. Relation-member traversal or serialization order is irrelevant to all of these checks.

## Import And Evidence

Each imported payment remains a source-backed candidate with immutable source payment time, amount, currency, evidence identity, rule version, confidence, confirmation requirements, and role facts. The ambiguous candidate preserves `guessed_payment_role=null`; amount, date, or source order cannot infer `deposit` or `final`. Formal effects occur only after explicit binding to the existing relation, exact payment role, second-level expense category, and owned real asset account.

Manual `source_payment_at=null` and `source_refs=[]` are canonical source absence. They create no source entity, source payload, source reference, or invented provenance and do not belong to `RG06-GAP-02`. The optional installment-payment source time is omitted for manual payments. A later manual bank-evidence source remains an independent posting-level evidence fact and does not retroactively turn the manual transaction into an imported transaction.

RG-06 uses the distinct `staged_payment_bank_fact` source and `staged_payment_bank_payment` evidence discriminators. RG-05 `merged_payment_bank_fact` and `bank_payment` retain their existing closed ownership and semantics without aliasing or cross-subtype reuse. Manual bank-evidence sources preserve `observed_at` only; imported payment sources, including mirrors, preserve `source_payment_at` only. The staged-payment evidence payload preserves the same field name and exact timestamp text as its sole source. A missing, `null`, doubled, renamed, or synthesized time is invalid. Evidence-side `created_at` and `confirmed_at` are forbidden, not merely ignored. Neither source time creates either field. Original and mirror sources own the signed bank fact and optional source lineage; staged-payment evidence owns the referenced payment plus optional mirror and merge-link lineage. An RG-06 mirror names its exact original source, uses the same currency and equal absolute magnitude, and has the opposite sign; the frozen final-payment pair is `+220.00 CNY` and `-220.00 CNY`.

Each evidence's sole bank fact has the installment and asset posting currency and an absolute amount equal to both. This evidence-to-installment binding applies to original and mirror facts independently, so a `219.99` amount or a non-`CNY` currency cannot match the frozen `220.00 CNY` final installment even if mirror lineage is internally consistent.

Each emitted evidence link uses only canonical evidence-link fields and targets the exact owned-real `payment_asset` posting with `target_kind=posting` and `role=payment_asset_posting`. Legacy `payment_id`, `source_id`, `status`, `mirror_of_evidence_id`, and `merged_into_evidence_link_id` values therefore map to their canonical source, evidence, installment-payment, and posting-reconciliation owners, never to extra evidence-link fields. Expense-posting `not_applicable` becomes `postings[*].reconciliation_eligible=false` with no `posting_reconciliations` record. Mirror evidence preserves lineage and merges into the original payment/posting evidence result without adding a transaction, posting, payment, consumption record, relation, or cash flow.

## Unresolved Gaps

1. `RG06-GAP-01` - RG-06 staged-payment operation registry (`71` affected paths). Register closed group, payment, status, import, confirmation, evidence, mirror, rejection, and retry actions with strict inputs and exhaustive deltas.
2. `RG06-GAP-02` - staged-payment source and candidate payloads (`147` affected paths). Preserve real imported source facts, confidence, explicit null role ambiguity, status history, and exact confirmation binding; exclude canonical manual source absence.
3. `RG06-GAP-03` - identity-only `staged_payment` relation, separate lifecycle entity, and `installment_payment` payloads (`508` affected paths). Preserve relation `id/type/member_refs`, lifecycle business facts, two stable payment identities, and exact transaction/posting bindings without relation payload.
4. `RG06-GAP-04` - independent staged-payment status and history registries (`170` affected paths). Register payment progress, fulfillment, domain-entity reconciliation, and complete ordered immutable `payload.state_history[*]` fields without flattening or combining meanings.
5. `RG06-GAP-05` - bank payment evidence and mirror lineage (`72` affected paths). Register the bank-payment evidence subtype while retaining exact typed asset-posting links.

Affected path counts overlap where one source fact crosses contract boundaries. `unresolved_contract_gaps[*].affected_source_paths` is generated from entry references and is bidirectionally closed.

The approved topology, status ownership, and RG-06 evidence discriminators above close wording choices only. They do not change any path-map entry, disposition, gap status, or schema/validator capability; all five gaps remain unresolved at this mapping gate until the separately authorized contract implementation and review are complete.

## Gate

The artifact remains `status=needs_contract_amendment` and `expected_output_gate=closed`. The `411` ready paths may use current v2 targets, but the complete RG-06 document cannot be generated until all five gaps are separately reviewed and registered. This mapping does not implement schema changes, an adapter, expected output, or a fixture rewrite.
