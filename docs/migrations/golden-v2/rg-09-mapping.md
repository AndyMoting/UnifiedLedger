# RG-09 Golden Schema v2 Mapping

## Authority

This mapping is governed by `golden/rules/rg-09.json`, `docs/specs/2026-07-16-rg-09-balance-adjustment-design.md`, `docs/GOLDEN_TESTS.md`, `docs/ACCOUNTING_RULES.md`, `docs/GOLDEN_SCHEMA.md`, Golden Schema v2, D-063, D-065, and D-082. D-082 closes the three historical contract gaps and authorizes the runtime, complete oracle, and formal SQLDelight owner. The frozen v1 fixture remains unchanged. The separately authorized v2 publication is recorded in `golden/rules-v2/rg-09.json` and its manifest entry.

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

The candidate and `confirm_balance_adjustment` input now carry the mandatory D-065 digest generated from the target-time current postings. Symbolic fixture tokens are compatibility evidence only; they are never accepted as formal digests. Stale diagnostics use the frozen names `preview_ledger_fingerprint`, `current_ledger_fingerprint`, `recomputed_replay_amount`, and `recomputed_delta`.

The current `balance_adjustment` entity owns only immutable original facts: observation, original delta, currency, and original transaction. The current `explanation_allocation` entity owns each immutable explanation amount, real transaction, reversal transaction, currency, and confirmation time. `explained_amount` and `remaining_amount` are derived from `original_delta` and the allocation set; they are not serialized as duplicate lifecycle fields. `open`, `partially_explained`, and `fully_explained` use the current `explanation_status` registry. Ordered operations, status changes, and complete snapshots retain append-only behavior without a new lifecycle payload.

Verification uses the current `verification_status` values. A target can remain `balanced_with_unexplained_adjustment` while the remaining amount is nonzero, and becomes `evidence_incomplete` after full explanation until every required real-account posting is matched. Posting reconciliation remains on each eligible real-account posting and never changes balances or reports. `fully_reconciled` requires both zero remaining adjustment and all required posting evidence.

## Evidence And Audit Links

Every evidence link carries `target_kind`, `target_id`, and `role`. Target-balance evidence links only the observation with `target_balance_observation`; real-account evidence links only the precise eligible posting with `real_account_posting`. Source lineage belongs to evidence, not to the evidence link.

The current audit-link contract fully represents adjustment provenance and needs no amendment. Each v1 `allocation_id` maps to `from={kind=domain_entity,id=<allocation_id>}`. Each `target_id` maps to `to={kind=transaction,id=<target_id>}`. The v1 role maps directly to `adjustment_transaction`, `explanation_transaction`, or `allocation_reversal`. Audit `created_at` is verified equal to the owning allocation `confirmed_at`; the audit payload remains `{}` and no duplicate timestamp is created.

Relations carry no RG-09 lifecycle or business state. Adjustment, allocation, verification, and reconciliation ownership remains in current typed domain entities, derived statuses, audit links, and posting reconciliations.

## Time Mapping

Explicit `occurred_at`, `statistics_at`, `effective_at`, `created_at`, `confirmed_at`, observation time, and discovery time retain their separate source roles. Collapsed time is expanded only where the frozen fixture proves equality. Missing creation or confirmation time is never synthesized from economic, observation, discovery, or runtime time.

## Closed v1 Compatibility Projections

The frozen v1 state shape is compared through explicit projections in `Rg09FullStateOracleTest`; these projections are compatibility rules, not additional runtime state or database aliases:

- `sha256:rg09-ledger-v1` in frozen candidate state is replaced with the D-065 digest recomputed from the opening current-version postings at the target time. The stale fixture token `sha256:rg09-ledger-v2` is symbolic input evidence only; it is never accepted as a digest. Runtime confirmation and stale diagnostics use the recomputed `sha256:<64 lowercase hex>` value.
- The frozen `target-observation-rg09` reconciliation key is a source-facing projection of the typed runtime `observation-*` key. `remaining_adjustment` and all other typed reconciliation keys remain independently projected.
- The frozen import-confirmation `formal_deltas.new_source_record_count` repeats an intake-owned count. The authoritative source count remains in `intake_deltas`; the oracle adds the repeated field only for this legacy comparison.
- The frozen imported partial adjustment history reuses the manual history fact. When the runtime history ID is `history-adjustment-partial-rg09` and its allocation is `allocation-rg09-import-20`, the projection emits the legacy allocation ID `allocation-rg09-20` and `2026-02-10T18:05:00+08:00` for both `occurred_at` and `created_at`.
- The imported allocation keeps its runtime source/discovery time, while the frozen state projects `discovered_at` as the manual discovery time `2026-02-10T17:30:00+08:00` for `allocation-rg09-import-20`.
- After the original main-path transfer has been consumed by an allocation, the frozen state omits `confirmation-transfer-rg09`. The runtime retains that confirmation append-only; this is a legacy state projection, not deletion or deduplication.
- For `retry-target-source-rg09`, the frozen returned-ID object omits `candidate_id` because the old fixture treats the retry as a source receipt. The runtime preserves the candidate and its provenance; only the v1 returned-ID projection omits the field.
- After `import-explanation-confirmation-rg09`, the frozen resulting state and its retry snapshots update the adjustment remainder to `10.00` but leave `reconciliation.remaining_adjustment` at the pre-allocation `30.00`. The runtime and persistence derive the current reconciliation remainder as `10.00`; only those comparisons project the stale frozen value.
- After `import-explanation-confirmation-rg09`, the frozen resulting state and its retry snapshots leave the imported candidate at `pending_confirmation` even though every mandatory transaction and explanation fact has been explicitly confirmed. The runtime persists `confirmed` with the owning adjustment and confirmation request; only those comparisons project the approved lifecycle.
- The v2 expected artifact (Golden Schema v2 owner model) does not carry the v1 first real-transfer intake `source_record` (`confirm_real_transfer` registers no `sources` delta; v1 `transfer_confirmation.intake_deltas.new_source_record_count` is 1 and the v1 canonical states carry `source-real-transfer-confirmation-rg09`). This is a v2 owner-model projection, not a runtime drop; the Kotlin runtime and persistence still own the source. It must be explicitly accepted in the semantic-equivalence gate record.
- The v2 expected artifact also marks both postings of the imported transfer `transaction-transfer-rg09-import` as `reconciliation_eligible=false`, although the frozen v1 fixture publishes them as `reconciliation_eligible=true` and the v1 comparison layer preserves that frozen value. This is an explicit v2 owner-model projection: the strict v2 runtime oracle excludes the import transaction from posting reconciliation eligibility (`Rg09V2RuntimeOracleTest.kt:425`), so the import transfer creates no owned posting-reconciliation records in the v2 owner model. It is recorded alongside the source omission in the semantic-equivalence gate record and never changes the v1 comparison shape.
- The v2 expected artifact reattributes four v1 op-level delta counts without changing state-level data (balances, reports, timestamps match v1 canonical states byte-for-byte): (a) audit-link attribution v1 0+3 vs v2 1+2 across the first explanation allocation (total 3 preserved in `partially_explained.audit_links`); (b) first-transfer source v1 1 vs v2 0 (previous bullet); (c) explanation `report_change_count` v1 1 vs v2 2 (v1 undercounts its own state projection; both `balance_adjustment_net_worth_change` and `net_worth_change` move 30.00→10.00); (d) v1 `reconciliation_change_count` semantics split in v2 across `posting_reconciliations` entity changes plus derived `verification_status` value changes. These reattributions must be recorded in the semantic-equivalence gate record.
- The legacy per-root second-transfer divergence is eliminated. Both owned real-account postings are reconciliation eligible and receive pending posting-reconciliation records at transfer creation in every root. The frozen main-path operation-level zero is retained only as evidence of a legacy undercount; it does not suppress canonical state ownership.

All other state, operation, returned-ID, formal/intake delta, and status fields compare directly after canonical collection ordering. No compatibility projection changes formal accounting, provenance ownership, derived reconciliation, or append-only history.

## Operation Families

The current registered families cover `preview_target_balance/read`, `confirm_balance_adjustment/adjustment`, `confirm_real_transfer/creation`, and `confirm_explanation_allocation/reversal`. The second real transfer and second allocation reuse the same canonical action families rather than introducing indexed action types. Existing closed input fields, complete baseline/result identities, exact outcome, returned IDs, and entity/value deltas map to the current operation graph.

Accepted and no-change operations use closed `input`. Rejected operations use closed sparse `attempted_input`. A retry retains its originating action type and exact input; there is no generic retry action and no dispatch through `input.kind`.

## Closure Disposition

The path map records the pre-D-082 inventory and therefore retains its historical `requires_contract_amendment` classifications. `rg-09-closure-overlay.json` closes the immutable inventory without rewriting it: 924 unique paths carry 934 gap references (`RG09-GAP-01` 403, `RG09-GAP-02` 68, `RG09-GAP-03` 463), including 10 paths shared by GAP-01 and GAP-02. D-082 closes those classifications as follows:

1. `RG09-GAP-01` is implemented by the registered operation classes, typed outcomes, full baseline/result oracle, action-preserving retries, and zero-effect rejection/no-change assertions.
2. `RG09-GAP-02` is implemented by mandatory D-065 digest generation, exact decimal/time validation, stale diagnostics, and the JVM provider proof.
3. `RG09-GAP-03` is implemented by immutable source/candidate/evidence owners, explicit imported confirmation fields, typed evidence targets, and separate audit links.

The former audit-link gap remains closed because the current typed audit contract is sufficient. Old external RG numbering remains historical evidence only and creates no canonical alias. The gate is proof-complete and remains pending the independent high-risk specification and quality review required by D-082.

## Gate

- status: `pending_independent_closure_review`
- expected output gate: `published`
- unresolved gap count: `0`
- frozen operations: `50`
- complete v1 oracle: `Rg09FullStateOracleTest.every frozen operation is independently replayed and compared`
- strict v2 runtime oracle: `Rg09V2RuntimeOracleTest` compares 9 roots, 50 operations and 59 states against the published artifact
- D-065 provider proof: `Rg09FingerprintJvmTest.D-065 JCS bytes and runtime digest match JVM SHA-256`
- formal persistence proof: `SqlDelightRg09StoreTest` reopen/retry/rollback/guard coverage
- migration proof: `LedgerDatabaseMigrationTest` v13 to v14 populated preserve/reopen/atomic rollback coverage plus fresh-v14 and v1-to-v14 schema equality and the SQLDelight migration verifier
