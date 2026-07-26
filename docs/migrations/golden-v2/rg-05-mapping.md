# RG-05 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-05.json`、`docs/specs/2026-07-15-rg-05-merged-payment-design.md`、`docs/specs/2026-07-25-rg-05-contract-closure-proposal.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md`、`docs/GOLDEN_SCHEMA.md` 与正式 `D-008`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-059` 约束。外部 `CORE_ACCEPTANCE_PLAN.md` 的 RG 编号已经过时，仅作为早期覆盖证据，不覆盖当前冻结的 RG-05 语义。本映射只定义 RG-05 v1 到 v2 的逐路径迁移；它批准生成 expected output，不批准 adapter、fixture rewrite 或 publication。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `549`
- leaf occurrences: `1407`
- classified/unclassified: `549/0`
- classifications: `preserve 46`, `map 420`, `derive 76`, `reject 7`
- dispositions: `ready 542`, `requires_contract_amendment 0`, `test_only_exclusion 7`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `schema_version` / `case` | required v2 envelope, case metadata, and the one declared `CNY` currency |
| `catalog` | every complete state's account/category catalog; category kind is derived through its posting account |
| `opening` | opening transaction, version, posting set, postings, and complete replayed balances |
| `manual_path` | `manual_merged_payment`, one explicit operation confirmation, one formal expense chain, two independent consumption records and allocations, one relation, projections, and per-posting reconciliation |
| `import_path` | `ingest_merged_payment_facts`, pending candidate, `confirm_merged_payment_candidate`, then `merge_item_receipt_evidence`, without duplicate formal entities |
| `allocation_failures` | rejected `confirm_merged_payment_candidate` attempts with exact incomplete/conflict outcomes and exhaustive zero effects |
| `idempotency` | the originating four action types replayed as `no_change`, returning original typed IDs and byte-equivalent complete states |
| `invalid_manual_inputs` | 15 independent sparse rejected `manual_merged_payment` attempts with baseline balances and zero formal/statistical/reconciliation deltas |
| `forbidden_side_effects` / `out_of_scope` | test-only negative assertions; not serialized into v2 |

## IDs And Time

Existing stable account, category, transaction, posting, source, candidate, evidence, evidence-link, consumption-record, item-allocation, relation, request, and operation IDs are preserved where v2 owns the same identity. Missing root, state, opening version/posting-set, confirmation, status-history, and posting-reconciliation IDs use the contract's deterministic migration helpers with a normalized source locator plus a stable source ID, request ID, operation ID, item ID, evidence ID, invalid-case ID, or case ID discriminator. Array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The runtime reproduces the same identities, so the exact generator inputs behind every entity RG-05 derives are recorded here. Only the inputs are recorded here: the resulting values are owned by the expected output. The runtime identity tests deliberately copy a subset of them as regression anchors, which is what makes a change to the shared generator provably output-preserving; apart from those anchors the values must not be restated in runtime code or documentation.

| entity | `entity_kind` | `source_locator` | `occurrence_discriminator` |
| --- | --- | --- | --- |
| manual root | root | `$.manual_path` | `request-rg05-manual` |
| manual confirmation | `confirmation` | `$.manual_path.confirmation` | `request-rg05-manual` |
| manual posting reconciliation | `posting_reconciliation` | `$.manual_path.expected.reconciliation` | `posting-asset-rg05-manual` |
| import root | root | `$.import_path` | `source-bank-debit-rg05` |
| candidate pending status | `candidate_status` | `$.import_path.ordered_operations[*].expected.candidate.status` | `candidate-rg05-imported` |
| candidate confirmed status | `candidate_status` | `$.import_path.ordered_operations[*].expected.candidate_status` | `request-rg05-confirm-candidate` |
| import confirmation | `confirmation` | `$.import_path.ordered_operations[*].expected.candidate_status` | `request-rg05-confirm-candidate` |
| import posting reconciliation | `posting_reconciliation` | `$.import_path.ordered_operations[*].expected.reconciliation` | `posting-asset-rg05-imported` |

Two asymmetries in that table are deliberate and must not be "corrected". The manual confirmation is located at `$.manual_path.confirmation` because v1 states an explicit confirmation object there, while the imported path has no such object and locates its confirmation at the candidate-status fact that evidences it, sharing that locator with the `candidate_status` entity and separating the two by `entity_kind` alone. This is a cross-scenario convention rather than an RG-05 choice: the already approved RG-04 output derives its imported confirmation from the identical `...ordered_operations[*].expected.candidate_status` locator, likewise separated from its candidate status only by `entity_kind`. Every locator and discriminator above is an opaque, frozen generator input whose only contract obligations are stability and uniqueness; changing one silently renames the entity it produces, so none of them may be edited for readability or symmetry after the expected output exists.

RG-05 has one narrow collapsed-time approval: each exact `opening.transactions[*].occurred_at` text expands only to that opening version's `occurred_at`, `statistics_at`, and `effective_at`. It never generates `created_at` or `confirmed_at` and cannot be generalized to payment, candidate, source, or evidence time. Formal payment versions use explicit `payment_at` for `occurred_at` and `effective_at`, while the explicit common payment statistics time owns `statistics_at`. Each item `source_observed_at` remains immutable source/business evidence and never overrides either formal or consumption statistics time.

## Closed Actions

| action type | class | closed behavior |
| --- | --- | --- |
| `manual_merged_payment` | `creation` or `rejection` | Accepted/no-change input owns request, payment total/currency, one funding account, payment time, exactly two complete item inputs, explicit confirmation, and optional settlement explanation. Rejected attempts are sparse and atomic. |
| `ingest_merged_payment_facts` | `creation` | Accepted/no-change input owns one bank fact and exactly two item facts. Intake creates sources, evidence, and one pending candidate, with zero formal, balance, report, or reconciliation effect. |
| `confirm_merged_payment_candidate` | `creation` or `rejection` | Explicit accepted/no-change input owns candidate, funding account, payment/common-statistics times, and exactly two categorized allocations. Incomplete or excessive allocation is rejected without changing the pending candidate. |
| `merge_item_receipt_evidence` | `reconciliation` | Accepted/no-change input owns one later receipt source/evidence and one exact allocation target. It adds only source/evidence/link state and business completeness. |

Retries retain the originating action type; RG-05 has no generic retry action. Same request identity plus changed canonical input is `identity_conflict` and writes nothing. The v1 persistent label `candidate_status: conflict` is normalized to a rejected confirmation outcome with `reason_code: allocation_conflict`; candidate status remains `pending_confirmation`.

## Sources And Candidate

`merged_payment_bank_fact` is closed to `evidence_id`, immutable `observed_at`, `details`, signed `amount`, `currency`, and `completeness:complete`. Each `merged_payment_item_fact` is closed to `item_id`, `evidence_id`, `evidence_kind`, immutable `observed_at`, `details`, positive `amount`, `currency`, `suggested_category_id`, and completeness. Source facts are never rewritten.

The `merged_payment` candidate has exactly one bank source and two item sources, fixed confidence `1.00`, exact evidence references, source-equal item proposals, rule/version provenance, and the four confirmation requirements. Its valid history is `[pending_confirmation]` or `[pending_confirmation, confirmed]`; there is no persistent `conflict` transition. Candidate proposals and suggested categories are not formal accounting facts.

## Formal Result And Relation

The completed payment is exactly one current `expense` transaction with three postings: item A expense `+40.00 CNY`, item B expense `+60.00 CNY`, and one owned real `payment_asset` posting `-100.00 CNY`. It creates exactly two `consumption_record` entities, two `item_allocation` entities, and one `merged_payment` relation. No clearing account, second payment, discount posting, or generic order lifecycle is allowed.

The relation has four members: the transaction, its unique payment posting, and the two allocations. Its closed payload is `system_managed:true`, `display_name:"合并付款"`, `generic_order_lifecycle:false`, `payment_total`, and `currency`. The two allocations bind distinct expense postings and sum exactly to the payment total.

Each consumption retains its own category, details, amount, expense posting, common payment `statistics_at`, and, on the imported path, all-or-none source/item/evidence binding plus immutable source time. Each allocation retains its consumption, expense posting, category, amount/currency and, on the imported path, all-or-none source/item/evidence binding. Manual entities do not fabricate source/evidence identities.

Category consumption totals are category-bound canonical state ownership, not free-standing report metric leaves. Each of the eight v1 `category_consumption` paths maps to the matching expense posting and consumption record as paired `category_id` plus `amount` fields; report metrics are derived from that canonical state. Allocation gap and over-allocation amounts are diagnostics owned by the attempted `payment_total`, `allocation_total`, and rejected outcome reason (`allocation_incomplete` or `allocation_conflict`), and never target formal postings. The opening `occurred_at` path is the sole RG-05 collapsed-time exception and expands only to version `occurred_at`, `statistics_at`, and `effective_at`; it never creates `created_at` or `confirmed_at`.

## Evidence And Derived State

Bank and item evidence may exist at intake before their future posting/allocation targets exist, so intake creates no evidence links. Confirmation creates the `payment_asset_posting` link from `bank_payment` evidence to the unique asset posting and the available `item_allocation_fact` receipt link to its exact allocation. `item_summary` never creates a receipt link. A later `item_receipt` creates exactly one allocation link and no formal entity.

Financial reconciliation belongs only to the unique owned real `payment_asset` posting; both expense postings are ineligible and have no reconciliation record. Relation `item_evidence_completeness` is independently derived as `none`, `partial`, or `complete` from exact receipt-to-allocation links. Changing completeness never changes financial reconciliation, balances, postings, reports, or cash flow.

Payment-day reports remain consumption `100.00 CNY`, category consumption `40.00` and `60.00`, cash outflow `100.00`, income `0.00`, net-worth change `-100.00`, and budget not applicable. Settlement original/discount/settled amounts are explanatory only.

## Resolved Contract Gaps

| gap | affected paths | closure |
| --- | ---: | --- |
| `RG05-GAP-01` | 51 | four closed actions, strict inputs/attempts, allocation rejection, complete deltas, and originating-action replay |
| `RG05-GAP-02` | 95 | closed bank/item sources and three-source candidate provenance/status lifecycle |
| `RG05-GAP-03` | 46 | exact four-member `merged_payment` relation and single-payment invariant |
| `RG05-GAP-04` | 24 | independent consumption/allocation detail and all-or-none source/evidence bindings plus derived completeness |
| `RG05-GAP-05` | 26 | closed bank/item evidence and exact posting/allocation target isolation |

The machine path map retains all five resolved audits and their original affected-source-path sets. Every affected entry is now `ready`, has an empty `contract_gap_ids`, and records its resolved gap identity.

## Expected Output Gate

The generated expected output is `approval_status: approved` as of `D-075`. It contains `17` roots, `25` operations, and `42` complete states: four accepted operations, four no-change replays, and 17 rejected operations. The rejected set is the 15 invalid manual inputs plus allocation incomplete and allocation conflict. That approval covers the expected artifact only: adapter implementation, fixture migration and publication each still require their own authorisation.

- mapping status: `approved`
- expected output gate: `approved`
- unresolved gap count: `0`
- resolved gap count: `5`
