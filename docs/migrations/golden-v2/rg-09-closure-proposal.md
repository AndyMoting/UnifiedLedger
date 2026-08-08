# RG-09 Golden Schema v2 Mapping Closure Proposal

状态：active

## Authority And Boundary

This proposal records the D-082 closure candidate for the three historical RG-09 mapping gaps. The frozen v1 fixture and `.external/` remain unchanged. The contract is closed by D-082; the separately authorized v2 artifact is now published, and this document records the implemented owners, compatibility projections, and proofs required before the mapping gate is accepted.

## Resolved Owners

| Gap | Closed owner | Required proof |
| --- | --- | --- |
| `RG09-GAP-01` | Closed RG-09 action registry and atomic outcomes | Every accepted, no-change, rejected, stale, incomplete, evidence, invalid and action-preserving retry operation has a strict input shape, complete baseline/result state, returned IDs, formal/intake deltas and status changes. Rejected and no-change paths are zero-effect and state-equivalent. |
| `RG09-GAP-02` | D-065 fingerprint, decimal/time boundary and stale diagnostics | JCS bytes and SHA-256 are computed from current postings effective at or before the target time. Symbolic v1 tokens are recomputed. Stale confirmation returns all four diagnostics and performs no write. |
| `RG09-GAP-03` | Source, candidate and evidence provenance | Imported facts remain pending until every required confirmation field is explicit. Evidence links target only their typed observation or exact real-account posting; audit links remain separate. |

## Persistence Owner

`ledger-data` owns `SqlDelightRg09Store`. Formal transaction rows remain in the shared formal chain. RG-09 normalized tables own operations, receipts/rejections, observations, sources, evidence, evidence links, candidates/status history, adjustments/history, allocations, audit links, posting semantics and posting reconciliation. Schema v14 removes the current adjustment table's duplicate `explained_amount_minor`, `remaining_amount_minor`, and `state` projections; the store derives them from immutable original delta, allocations, and the latest history and fails closed on missing/inconsistent history or over-allocation. The guarded v13 to v14 migration preserves populated child foreign keys and rejects inconsistent legacy projections atomically. A failed operation rolls back the identity claim and all dependent rows; a reopened database reconstructs the same typed snapshot.

## Oracle Contract

The fixture replay is an independent oracle, not a fixture-to-runtime identity copier. For every registered operation it compares:

- outcome and typed rejection reason/field;
- returned IDs and action-preserving retry identity;
- complete canonical state, including formal transactions, versions, postings, intake entities, balances, reports and reconciliation;
- formal and intake delta collections and derived status changes;
- rejected/no-change baseline equality and zero-effect invariants.

The runtime may derive deterministic IDs only from the frozen v1 input and approved contract. It must never use the expected output to configure execution. The first implementation batch covers every frozen v1 branch exposed by the registry; later fixture expansion remains a separate publication gate.

The oracle applies only ten explicit legacy projections: recompute symbolic v1/v2 fingerprint tokens as D-065 digests; expose the typed target-observation reconciliation status under the frozen source-facing key; repeat the import source count in the legacy formal delta; map the imported partial-history allocation and timestamps to the reused manual fact; map the imported allocation discovery time to the frozen manual discovery time; omit the already-consumed original transfer confirmation from the legacy state; omit `candidate_id` from the target-source retry receipt; project the frozen imported candidate status from `pending_confirmation` to `confirmed` after `import-explanation-confirmation-rg09` and through its retry snapshots, where all required facts are explicitly confirmed; retain those frozen imported snapshots' stale `remaining_adjustment=30.00` while the typed runtime correctly derives `10.00` from the confirmed allocation; and project the pre-allocation transfer state to the frozen legacy shape, publishing `balanced_with_unexplained_adjustment` with no `posting-*` reconciliation keys until the first explanation allocation exists, while the v2 runtime publishes `difference_pending_explanation_confirmation` and pending posting reconciliations immediately at transfer confirmation. `rg-09-closure-overlay.json` registers all ten dispositions. These projections are confined to the v1 comparison layer and do not alter runtime provenance, formal ownership, derived reconciliation, or append-only rows.

## Semantic-equivalence Dispositions

The v2 closure accepts one source omission and four delta reattributions without changing accounting facts. `source-real-transfer-confirmation-rg09` remains in runtime/persistence but is omitted from the v2 owner model; audit-link attribution, first-transfer source attribution, the two changed report metrics, and posting-reconciliation plus verification-status changes are attributed to their canonical owners. The prior second-transfer per-root divergence is eliminated: both owned real-account postings are eligible and receive pending reconciliation rows when created in every root. The overlay records these dispositions and the historical 924/934 gap inventory. Old external RG numbering is historical evidence only and creates no alias.

## Verification Evidence

| Claim | Independent command or test | Result |
| --- | --- | --- |
| Every frozen operation is replayed and compared | `:ledger-data:jvmTest --tests com.unifiedledger.data.Rg09FullStateOracleTest` | PASS, 50 operations |
| Runtime projects independently to the published v2 artifact | `:ledger-data:jvmTest --tests com.unifiedledger.data.Rg09V2RuntimeOracleTest` | PASS, 9 roots / 50 operations / 59 states |
| D-065 bytes and provider digest agree | `:ledger-application:jvmTest --tests com.unifiedledger.application.Rg09FingerprintJvmTest` | PASS |
| Formal owner survives reopen and retry; failures roll back; guards reject invalid writes | `:ledger-data:jvmTest --tests com.unifiedledger.data.SqlDelightRg09StoreTest` | PASS |
| v13 to v14 preserves populated RG-09 rows and child FKs, removes duplicate projections, rejects inconsistent legacy data atomically, and matches fresh-v14 metadata | `:ledger-data:jvmTest --tests com.unifiedledger.data.LedgerDatabaseMigrationTest` | PASS |
| SQLDelight migration declaration is complete | `:ledger-data:verifyCommonMainLedgerDatabaseMigration` | PASS |

The frozen `golden/rules/rg-09.json` and `.external/` were not modified. Publication copied the reviewed expected bytes transactionally to `golden/rules-v2/rg-09.json`; the manifest records source, expected, canonical, and output hashes and the 9/50/59 object counts.

## Acceptance

The D-082 owner table, closure overlay, complete v1 and strict v2 oracles, D-065 proof, schema v14 persistence boundary, migration/reopen/rollback evidence, and publication hashes are now present. The three historical gaps are closed; acceptance remains `pending_independent_closure_review` until the independent high-risk specification review, independent quality review, distinct verifier disposition, and main-agent final rerun are recorded. Push remains a separate main-agent gate.
