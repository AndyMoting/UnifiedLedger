# RG-09 Golden Schema v2 Mapping

## Authority

This mapping is governed by `golden/rules/rg-09.json`, `docs/specs/2026-07-16-rg-09-balance-adjustment-design.md`, `docs/GOLDEN_TESTS.md`, `docs/ACCOUNTING_RULES.md`, `docs/GOLDEN_SCHEMA.md`, Golden Schema v2, and D-065. It records the approved fingerprint foundation amendment but authorizes no adapter, expected output, or fixture rewrite.

## Inventory

The path map independently parses the v1 fixture, normalizes object members as `.key` and all array elements as `[*]`, and treats scalars, nulls, empty arrays, and empty objects as leaves. Entries are sorted by normalized source path.

- normalized paths: `7445`
- leaf occurrences: `23869`
- classified/unclassified: `7445/0`
- classifications: `preserve 1292`, `map 3234`, `derive 2917`, `reject 2`
- dispositions: `ready 6519`, `requires_contract_amendment 924`, `test_only_exclusion 2`

## Canonical Identity Ownership

Only the 54 complete baseline/result snapshot IDs map to `states[*].id`. All 395 nested IDs that previously collided with state identity now map to their canonical collections: transactions, transaction versions, postings, candidates, confirmations, evidence links, typed domain entities, and derived status records. The full inventory also maps source, evidence, observation, audit-link, catalog-account, case, and operation IDs by their actual semantic owner. No nested entity ID maps to a state ID.

Posting-set identity is reconstructed from each transaction version and its posting membership. Adjustment IDs map to `balance_adjustment` domain entities; allocation IDs map to `explanation_allocation` domain entities. Adjustment history IDs map to the corresponding derived explanation-status records, with ordered operations and complete snapshots retaining append-only history.

## Frozen Accounting Semantics

Target observations are immutable evidence, never balance overrides. Preview replays effective postings at the observation time and has zero formal effect. Explicit confirmation of a nonzero delta creates one balanced `balance_adjustment` between the target real asset and hidden system-managed `equity-balance-adjustments`. It is not income, expense, consumption, budget/category effect, or external cash flow. Zero delta retains observation, source, evidence, and its typed observation link without creating a zero transaction, version, or posting.

Stale confirmation replays the target time and rejects atomically when earlier effective postings changed. Later real transfers retain their actual economic time and `account_transfer` identity. A separate explicit allocation creates exactly one reverse adjustment at the original target time. The original adjustment transaction, versions, postings, observation, source, evidence, confirmations, and earlier operation states remain unchanged.

The fingerprint computational foundation projects only current-version postings effective at or before the target time. Its container is exactly `{"postings":[...]}`; each item contains only `transaction_id`, `current_version_id`, `effective_at`, `posting_id`, `account_id`, `currency`, and `amount`. Items sort by the frozen tuple using UTF-16 code-unit lexicographic order, matching JCS and Java/Kotlin string comparison, before RFC 8785 JCS serialization and SHA-256. Creation time, evidence, reconciliation, reports, derived state, and state identity are excluded. The v1 symbolic fingerprint tokens are not valid digests and remain classified as derive: migration must recompute them rather than preserve their text.

This batch exposes no fingerprint field on the closed candidate payload or `confirm_balance_adjustment` input. It retains only the independent `sha256Fingerprint`, `ledgerFingerprintProjection`, and `staleReplayDiagnostics` schema definitions and private deterministic projection/hash helpers. Fingerprint generation, candidate/input population, mandatory confirmation, stale diagnostics, and atomic rejection all remain in `RG09-GAP-02`; because the action surfaces are closed, there is no optional safety field to bypass. The future diagnostics names remain frozen as `preview_ledger_fingerprint`, `current_ledger_fingerprint`, `recomputed_replay_amount`, and `recomputed_delta`.

The current `balance_adjustment` entity owns only immutable original facts: observation, original delta, currency, and original transaction. The current `explanation_allocation` entity owns each immutable explanation amount, real transaction, reversal transaction, currency, and confirmation time. `explained_amount` and `remaining_amount` are derived from `original_delta` and the allocation set; they are not serialized as duplicate lifecycle fields. `open`, `partially_explained`, and `fully_explained` use the current `explanation_status` registry. Ordered operations, status changes, and complete snapshots retain append-only behavior without a new lifecycle payload.

Verification uses the current `verification_status` values. A target can remain `balanced_with_unexplained_adjustment` while the remaining amount is nonzero, and becomes `evidence_incomplete` after full explanation until every required real-account posting is matched. Posting reconciliation remains on each eligible real-account posting and never changes balances or reports. `fully_reconciled` requires both zero remaining adjustment and all required posting evidence.

## Evidence And Audit Links

Every evidence link carries `target_kind`, `target_id`, and `role`. Target-balance evidence links only the observation with `target_balance_observation`; real-account evidence links only the precise eligible posting with `real_account_posting`. Source lineage belongs to evidence, not to the evidence link.

The current audit-link contract fully represents adjustment provenance and needs no amendment. Each v1 `allocation_id` maps to `from={kind=domain_entity,id=<allocation_id>}`. Each `target_id` maps to `to={kind=transaction,id=<target_id>}`. The v1 role maps directly to `adjustment_transaction`, `explanation_transaction`, or `allocation_reversal`. Audit `created_at` is verified equal to the owning allocation `confirmed_at`; the audit payload remains `{}` and no duplicate timestamp is created.

Relations carry no RG-09 lifecycle or business state. Adjustment, allocation, verification, and reconciliation ownership remains in current typed domain entities, derived statuses, audit links, and posting reconciliations.

## Time Mapping

Explicit `occurred_at`, `statistics_at`, `effective_at`, `created_at`, `confirmed_at`, observation time, and discovery time retain their separate source roles. Collapsed time is expanded only where the frozen fixture proves equality. Missing creation or confirmation time is never synthesized from economic, observation, discovery, or runtime time.

## Operation Families

The current registered families cover `preview_target_balance/read`, `confirm_balance_adjustment/adjustment`, `confirm_real_transfer/creation`, and `confirm_explanation_allocation/reversal`. The second real transfer and second allocation reuse the same canonical action families rather than introducing indexed action types. Existing closed input fields, complete baseline/result identities, exact outcome, returned IDs, and entity/value deltas map to the current operation graph.

Accepted and no-change operations use closed `input`. Rejected operations use closed sparse `attempted_input`. A retry retains its originating action type and exact input; there is no generic retry action and no dispatch through `input.kind`.

## Unresolved Gaps

1. `RG09-GAP-01`: closed missing action families and atomic outcomes (`403` affected paths). This covers stale-preview rejection, zero-delta observation save, imported intake and incomplete confirmation, source-aware imported confirmation, posting-evidence binding, invalid attempts, and action-preserving retries.
2. `RG09-GAP-02`: mandatory replay fingerprint, stale diagnostics, and explicit validation contract (`68` affected paths). Only computational and independent data-shape definitions exist. Candidate/input fields, preview generation, confirmation population, symbolic-token migration, stale rejection, and explicit counter-account/amount/explanation fields remain gated. It contains no adjustment lifecycle payload.
3. `RG09-GAP-03`: imported-transfer source, candidate, and evidence provenance (`463` affected paths). This covers closed imported proposal requirements, immutable source digests/times, account-statement evidence, and source-aware candidate/evidence variants.

Every future target references one of these three unresolved gaps, and each gap lists exactly the entries that reference it. The former audit-link gap is removed because the current typed audit contract is sufficient.

## Gate

- status: `needs_contract_amendment`
- expected output gate: `closed`
- unresolved gap count: `3`

No adapter, expected output, or fixture rewrite is implemented.
