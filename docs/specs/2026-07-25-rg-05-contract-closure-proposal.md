# RG-05 Contract Closure Proposal

**Status:** Proposal for user approval. No schema, validator, expected output, fixture, or runtime change is authorized by this document alone.

**Scope:** Close the five contract gaps identified by `rg-05-mapping.md` while preserving the frozen RG-05 v1 behavior in `2026-07-15-rg-05-merged-payment-design.md` and the reusable v2 operation/state contracts.

## Decisions Requested

Approve the five closures below and the normalization of the frozen v1 over-allocation label `candidate_status: conflict` to a rejected operation outcome with `reason_code: allocation_conflict`. No persistent candidate conflict transition is added. The candidate remains `pending_confirmation` and the rejected confirmation has no state effect.

## Reused Contract

All operations use the existing closed `operationBase`: ordered operations, baseline/result state, outcome, status changes, complete entity/value deltas, and typed returned IDs. Accepted and replayed requests carry a closed `input`; rejected requests carry a closed sparse `attempted_input`. Outcomes remain `accepted`, `no_change` with a reason, or `rejected` with a reason and field path. Stable deterministic IDs, append-only status history, complete-state equality on replay, transaction versions, posting roles, balances, reports, financial reconciliation, source/candidate envelopes, and confirmation records are reused unchanged.

`consumption_record` retains its required expense posting, category, amount, currency, and statistics time. `item_allocation` retains its required consumption binding and amount/currency consistency. Financial reconciliation remains posting-owned; business evidence completeness is a separate derived relation status.

## GAP-01: Closed Operation Registry

Register exactly these action types:

| Action | Class | Accepted effect | No-change/rejection |
| --- | --- | --- | --- |
| `manual_merged_payment` | `creation` | One expense transaction, three postings, two consumptions, two allocations, one `merged_payment` relation | Same canonical `request_id` and input returns original IDs; invalid input is rejected atomically |
| `ingest_merged_payment_facts` | `creation` | Three sources, three evidence records, one `merged_payment` candidate in `pending_confirmation`; zero formal accounting effect | Same source identities/content replay; identity collision is rejected atomically |
| `confirm_merged_payment_candidate` | `creation` | Only explicit confirmation creates the same one-transaction formal result as the manual path | Replay returns original IDs; incomplete or excessive allocation is rejected with zero formal effect |
| `merge_item_receipt_evidence` | `reconciliation` | Adds only the new source, evidence, and typed allocation link; derives business completeness | Replay is `no_change`; target/source/amount/currency conflicts are rejected atomically |

No separate retry action is registered. A retry reuses the originating action and strict canonical input. Same idempotency key with changed input returns `identity_conflict` and writes nothing.

Manual input is closed to `request_id`, `total_amount`, `currency`, `funding_account_id`, `payment_at`, exactly two `items`, and `explicit_confirmation: true`. Each item is closed to `item_id`, positive `amount`, `currency`, `category_id`, `details`, and immutable `source_observed_at`. Optional `settlement_explanation` is closed to `original_amount`, `discount_amount`, and `settled_amount`; it validates `original - discount = settled = total_amount` and creates no entity or posting.

Confirmation input is closed to `request_id`, `candidate_id`, `funding_account_id`, `payment_at`, `common_statistics_at`, exactly two item allocations (`item_id`, `category_id`, positive `allocation_amount`, `currency`), and `explicit_confirmation: true`. Rejection reasons use the existing exact paths, including `secondary_category_required`, `category_inactive`, `expense_category_required`, `must_be_positive`, `item_amount_must_be_positive`, `allocation_total_must_equal_payment`, `duplicate_item_id`, `unknown_real_account`, `real_financial_account_required`, `asset_account_required`, `owned_account_required`, and `single_currency_required`; allocation failures use `allocation_incomplete` and `allocation_conflict`.

## GAP-02: Sources and Candidate

Add two closed source subtypes:

- `merged_payment_bank_fact`: `evidence_id`, `observed_at`, `details`, signed `amount`, `currency`, and `completeness: complete`.
- `merged_payment_item_fact`: `item_id`, `evidence_id`, `evidence_kind` (`item_receipt` or `item_summary`), `observed_at`, `details`, positive `amount`, `currency`, `suggested_category_id`, and `completeness` (`complete` or `summary_only`).

Register candidate type `merged_payment`. Its `source_ids` are exactly one bank source and two item sources. Candidate payload carries the payment total/currency, source IDs, item proposals, evidence references, fixed rule/version provenance, and a closed `requires_confirmation` set covering funding account, both final secondary categories, allocation closure, and formal creation. Confidence is deterministic for this fixture. Sources own immutable observed times, original amounts, details, and suggestions; candidate proposals are not a second source of truth and any copied facts must equal the source fields. Candidate history is only `pending_confirmation` or `pending_confirmation, confirmed`; source facts are never rewritten.

## GAP-03: `merged_payment` Relation

Add relation type `merged_payment`, distinct from RG-04 `mixed_payment`. Its `member_refs` are exactly four typed references: one current `expense` transaction, its one `payment_asset` posting, and two `domain_entity:item_allocation` objects. Payload is closed to `system_managed: true`, display name `合并付款`, `generic_order_lifecycle: false`, `payment_total`, and `currency`; it must not duplicate member IDs.

The validator requires the transaction to be current and expense-typed; the payment posting to belong to that posting set, be the single owned real-asset leg, and equal negative payment total; and the two distinct allocations to bind the two distinct expense postings, use the same currency, be positive, and sum exactly to the payment total. The relation is descriptive and creates no economic event.

## GAP-04: Consumption and Allocation Lifecycle

Extend `consumption_record` with optional `details`, `source_observed_at`, `source_item_id`, `source_id`, and `evidence_id`; extend `item_allocation` with optional `source_item_id`, `source_id`, and `evidence_id`. For imported merged-payment entities, source bindings are all-or-none and must match the corresponding source and evidence. Manual entities do not fabricate source or evidence references. In RG-05 v1, both statistics times equal the common payment statistics time; observed source time remains immutable evidence and cannot override reporting time. Records and allocations are immutable after formal creation; later evidence only adds source/evidence/link entities.

Relation-derived `item_evidence_completeness` is `none`, `partial`, or `complete`, computed from exact `item_allocation_fact` links for the two allocations. It is not stored in an allocation and never changes financial reconciliation, balances, reports, or postings.

## GAP-05: Bank and Item Evidence

Register closed evidence subtypes `bank_payment`, `item_receipt`, and `item_summary`. Evidence carries only its observed time plus the subtype's source identity; amount, currency, and details are owned by the source and must match it. A bank evidence record has exactly one bank source and may create only a `payment_asset_posting` link to the unique asset payment posting; this may change that posting's financial reconciliation and the transaction summary only. An item evidence record has exactly one matching item source. Only `item_receipt` may create an `item_allocation_fact` link to exactly one allocation. Item evidence may not target a consumption, transaction, relation, or financial posting, and does not change financial reconciliation. A later item receipt changes only source/evidence/link state and completeness (`partial` to `complete`).

## Lifecycle Examples

The manual accepted path uses `100.00 CNY`, item amounts `40.00` and `60.00`, one `-100.00` asset posting, two expense postings, two independent consumptions, two allocations, and one relation. The import path first creates only three sources, three evidence records, and one pending candidate. Confirmation with two explicit categories creates the formal result. A `90.00` allocation is rejected as `allocation_incomplete`; a `110.00` allocation is rejected as `allocation_conflict`; both preserve the candidate and baseline state. A repeated request/evidence/confirmation returns the original stable IDs with `no_change` and byte-equal complete state.

## Alternatives and Risks

Do not reuse RG-04 `mixed_payment`, RG-03 transfer semantics, generic order/payment entities, multiple funding legs, clearing accounts, discount postings, or a global candidate `conflict` status. Do not make the generic RG-12 item-receipt payload globally require RG-05 fields. The principal risks are subtype ambiguity in `oneOf`, source/candidate fact drift, accidental second cash-flow creation, privacy leakage through `details`, and mixing business completeness with financial reconciliation. These are addressed by closed payloads, source-owned facts, exact relation cardinality, anonymous fixtures, and semantic equality checks.

## Implementation and Verification Gate

After explicit approval, implementation proceeds in this order: write positive/negative schema and semantic tests with exact reason/field paths; add closed definitions and registries; add reference, relation, source/candidate equality, action-effect, append-only, and replay checks; then classify all 242 affected mapping paths as ready. Approval does not authorize unrelated RG-04 completion or runtime generalization.

Acceptance requires `549 classified / 0 unclassified / 0 unresolved gaps`; import has zero formal/report/reconciliation deltas; confirmation has exactly one transaction, three postings, two consumptions, two allocations, one relation, one asset cash-flow leg, and per-currency balance; evidence targets are exact; all 15 invalid manual inputs and both allocation failures are atomic zero-write outcomes; four replay classes are complete-state byte-equal; and RG-03, RG-04, and RG-12 regression contracts remain green. Expected output generation and runtime implementation remain gated until separate specification review, quality review, independent verification, and user approval.
