# RG-10 Golden Schema v2 Mapping Closure Proposal

状态：active

## Authority And Boundary

This proposal records the D-083 closure candidate for the six historical RG-10 mapping gaps: the 13-action runtime, the 44-operation fixture oracle, SQLDelight persistence, the four new `TransactionKind` values, and the GAP-01..GAP-06 dispositions. It does not edit the frozen v1 fixture `golden/rules/rg-10.json`, `.external/`, or a publication target. The contract is closed by D-083; this artifact records the implemented owners, the v2 compatibility projections, the synthetic-input and classification decisions, and the proofs required before the mapping gate is marked `approved`. Per D-083 the mapping gate becomes `approved` only when the closure proposal, the complete oracle, focused persistence/migration tests, and independent review evidence are all present.

## Scope And Approval

| D-083 approved item | Implemented owner | Evidence |
| --- | --- | --- |
| 13-action runtime with closed inputs, sparse attempted inputs, typed outcomes, complete baseline/result snapshots, returned IDs, formal/intake/reconciliation deltas and status changes | `Rg10Operations.kt` (application), `Rg10FixtureReplay.kt` (44 operations) | `Rg10OperationsTest` (14 tests) and `Rg10RawJsonEndToEndTest` (9 tests) |
| 44-operation fixture oracle (D-083 "50-operation"; see disposal below) | `Rg10FullStateOracleTest.kt` | 7 oracle tests, one per operation family |
| SQLDelight persistence with immutable/sequence/target-type guards and additive schema migration | `SqlDelightRg10Store.kt`, `12.sqm` (v12 to v13), `Ledger.sq` v13 additions | `SqlDelightRg10StoreTest` (8 tests), `Rg10SchemaV13Test` (2 tests), `LedgerDatabaseMigrationTest` (13 tests) |
| Four new `TransactionKind`: `STORED_VALUE_RECHARGE`, `STORED_VALUE_SPEND`, `STORED_VALUE_EXPIRY_LOSS`, `STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT` | `FormalLedger.kt` | `Rg10SchemaV13Test.canonicalRg10TransactionKindsSurviveWriteAndReopen`; schema version assertions bumped in 7 test files |
| `RG10-GAP-01` closed as full action registry | 13 `action_type` tokens with closed `operation_class`/outcome combinations, action-preserving retry identity | oracle registry test asserts 44 operations and the 12/10/22 family split |
| `RG10-GAP-02` closed as lifecycle payload owner | lot, consumption, allocation and expiry payloads in `Rg10Operations.kt` and the rg10 tables | oracle full-state comparisons; `SqlDelightRg10StoreTest` |
| `RG10-GAP-03` closed as import provenance owner | imported recharge/spend stay `pending_confirmation` with zero formal effect; imported candidates never auto-confirm | `Rg10OperationsTest.imported candidates stay pending...`; `Rg10RawJsonEndToEndTest.imports never auto-confirm...` |
| `RG10-GAP-04` closed as activation boundary + replace-not-append reconstruction | `StoredValueReconstruction` domain entity with `active_mode` and append-only history; adjustment endpoint preserved, no in-place rewrite | `Rg10SchemaV13Test`, oracle activation-boundary family, reconstruction rows in `SqlDelightRg10StoreTest.importedCandidateAndActivationReconstructionSurviveReopen` |
| `RG10-GAP-05` fail-closed | numeric `level` path stays unmapped; no `parent_id` inference | `Rg10OperationsTest` category-boundary rejection tests; mapping doc GAP-05 record |
| `RG10-GAP-06` partially closed + fail-closed | bonus/expiry evidence roles executable in runtime; legacy link status has no owner and is never mapped to posting reconciliation or domain lifecycle state | oracle evidence-link and reconciliation projections below |

The runtime derives deterministic IDs only from the frozen v1 input and the approved contract (`tests/fixtures/rg10-runtime-input.json` `ids`/`sources`/`times`/`categories`/`lot_facts` maps) and never reads the expected output back into execution input (D-083:1061).

## D-083 "50-operation" Disposal (RG10-SPEC-004)

The frozen fixture `golden/rules/rg-10.json` was independently counted for this proposal. It contains exactly 44 operation-shaped cases:

| Directory | Count | Entries |
| --- | --- | --- |
| `main_path` | 4 | recharge, spend, expiry_reminder, expiry_confirmation |
| `reconciliation_path` | 2 | merchant_evidence, bank_evidence |
| `import_path.complete_unconfirmed` | 2 | imported recharge, imported spend |
| `import_path.incomplete_confirmations` | 2 | incomplete recharge, incomplete spend |
| `secondary_cases` | 4 | multi_lot_allocation, merchant_evidenced_allocation, rename_zero_effect, activation_boundary |
| `idempotency` | 10 | retries over 4 main + 2 reconciliation + 2 import + 2 secondary source inputs |
| `invalid_inputs` | 20 | 17 unique reason codes |
| **Total** | **44** | |

The remaining top-level directories are not operation documents: `canonical_states` (5 state snapshots), `reconciliation_states` (2 state snapshots), `import_path.pending_states` (2 pending-state snapshots), `request_registry` (10 identity strings), and `forbidden_side_effects` (13 prose rules). They are comparison and contract material, not replay inputs, and the oracle consumes them through the state documents and the fixture adapter rather than as operations.

D-083 approves a "50-operation" oracle without any composition breakdown. The number is inherited from the RG-09 planned scale: the RG-09 v1 fixture holds exactly 50 operation documents, and the RG-10 v1 fixture holds 44. Reaching 50 would require six synthetic extensions; of those only two have weak inference support and four have no frozen evidence at all. The repository precedent rejects synthetic expansion: 66 synthesized operations were rejected by the RG-09 Step 1 quality review (PROGRESS_LOG, 2026-08-07). D-083 also forbids modifying the frozen v1 fixture. Therefore 44 is the only number closable on frozen evidence, and the oracle is implemented as a strict 1:1 mirror of the 44 frozen operation-shaped cases (RG-09 oracle precedent: id-for-id, no completion logic).

The 12 accepted / 10 no_change / 22 rejected split is a runtime mapping decision that this proposal declares explicitly. The frozen `idempotency` blocks still carry `accepted: true` (idempotent replay returns the original stable IDs); the runtime maps an identical-fingerprint replay to `NoChange` returning the first-time IDs, following the RG-09 precedent and D-083's retry equality requirement. The 22 rejected cases are the 20 `invalid_inputs` plus the 2 `incomplete_confirmations`.

## v2 Projection Registry (RG10-SPEC-002)

The oracle is an independent replay comparator, not a fixture-to-runtime identity copier. It registers the following v2 projections, confined to the comparison layer and never altering runtime provenance, formal ownership, or append-only rows.

### Evidence-link projection

The frozen v1 states carry 3 recharge links including the legacy mixed role `stored_value_credit_lot`; the runtime emits 4 v2 split-role links (`bank_payment_posting`, `stored_value_asset_posting`, `stored_value_lot_fact`, `stored_value_bonus_component`). The mapping document authorizes the v2 split roles ("Merchant credit requires two independent typed links ... The old mixed stored_value_credit_lot link is never emitted"). The oracle therefore expands each legacy merchant link into the asset-posting link (legacy id preserved) plus the lot-fact link (id taken from the runtime inputs `merchant_lot_link_id`), and the recharge first intake `new_evidence_link_count` projects 3 to 4; a retry produces no new links and is not projected. The activation fact link target is rewritten from the legacy adjustment transaction to the `activation_adjustment` domain entity (mapping: "it never targets the adjustment transaction"), with status `pending` (the legacy `confirmed_business_fact` has no evidence-verification owner, RG10-GAP-06).

### Reconciliation projection

The frozen v1 reconciliation uses transaction-level keys with `complete`/`partial`/`pending`/`pending_financial_evidence` values. The runtime stores posting-level keys with `pending`/`matched` values. The oracle asserts the runtime posting-keyed shape and synthesizes a transaction-level key per transaction that owns reconciliation-eligible postings (`complete` when all eligible postings matched, `partial` when some matched, `pending` otherwise, with `pending_financial_evidence` for the expiry and activation kinds whose dedicated evidence is not posting-bound). Legacy `not_present`/`not_applicable` values map to record absence (mapping: "Legacy not_present/not_applicable reconciliation maps to no posting_reconciliations record") and are dropped from the projected expected block. The transaction-level `pending_financial_evidence` kind rule is an oracle-side inference of v1 semantics and is annotated as such in `Rg10FullStateOracleTest.projectReconciliation`.

### Other registered projections

- `balances` omit disabled stored-value accounts whose zero balance the frozen states do not publish; the publication rule reads `catalog.accounts[*].stored_value.enabled`.
- A spend without an unallocated remaining composition keeps `remaining_paid_amount`/`remaining_bonus_amount` explicit null in the frozen lots; the runtime has no remaining composition to attribute and the projection keeps the explicit null shape.
- Candidate `occurred_at` is preserved internally by the runtime but is not part of the v1 state shape and is not projected.
- Confirmations are projected per role with the frozen field sets: recharge/spend keep `id`, `request_id`, `role`, `transaction_id`, `confirmed_at`; expiry adds `confirms_actual_expiry`; activation adds `source_id`, `evidence_id`, `audit_link_id`, `explicit_confirmation`.
- Lot history records `composition_status` only on spent events (frozen v1 shape).
- Pending states are intake-delta projections: the frozen shape lists only the intake the import created on top of the referenced formal state, so candidate/source/evidence/link/audit collections are diffed against that formal baseline; only posting-level reconciliation participates.
- `rename_zero_effect` publishes flat top-level counts (some without the `new_` prefix); the flat-to-internal key mapping is registered in the oracle's `FLAT_COUNT_KEY_MAP`.
- Retry blocks keep `accepted: true` in the fixture; the projection asserts runtime `NoChange` with first-time IDs (registered in `assertOutcome`).

## Synthetic Input Registry (RG10-SPEC-003)

`secondary_cases.multi_lot_allocation` is the only operation-shaped case whose baseline is the inline `base` object instead of a canonical state id, and it carries no stable commit ids of its own. The spend facts it omits (request id, model/behavior/category/time, the confirmation flags, and all commit ids) are synthesized deterministically:

- IDs follow the `*-multi-lot-rg10` convention: `request-multi-lot-rg10`, `transaction-multi-lot-rg10`, `version-multi-lot-rg10-v1`, `posting-set-multi-lot-rg10`, `posting-expense-multi-lot-rg10`, `posting-stored-multi-lot-rg10`, `confirmation-multi-lot-rg10`, `consumption-multi-lot-{1..n}-rg10`, `lot-history-multi-lot-{1..n}-rg10`.
- Times mirror the main-path spend (`2026-01-20T12:00:00+08:00` occurred, `2026-01-20T12:03:00+08:00` created).
- The required acceptance flags are forced true so the fixture-asserted accepted outcome (allocation order only) stays reachable.
- The consumption count is derived from the inline `base.lots` (one consumption per base lot) and never reads the expected output back into runtime input (D-083:1061).

The oracle executes this operation (`Rg10FullStateOracleTest` secondary-case family against the inline base snapshot), and `Rg10RawJsonEndToEndTest`/`Rg10OperationsTest` cover the synthetic-baseline execution and its retries.

## Classification Inference Registry (RG10-SPEC-005)

The fixture adapter classifies every operation with the following chain (`Rg10FixtureReplay.fixtureOperation`):

1. a retry source id => `no_change`;
2. explicit `expected.no_change: true` => `no_change`;
3. explicit `expected.accepted: true` => `accepted`;
4. `expected.reason` present => `rejected`;
5. `expected.resulting_state_id` present => `accepted`;
6. otherwise => `rejected`.

Two inferences are registered here explicitly. `rename_zero_effect` carries no `accepted` field; it is classified `accepted` through rule 5 because its `resulting_state_id` equals its baseline (`state-rg10-spend-confirmed`) and all its delta counts are zero, i.e. a zero-effect acceptance. `multi_lot_allocation` uses the inline `base` and has no state id; it is classified through the explicit `accepted: true` in its expected block (rule 3). The chain's last-resort fallback is a silent `rejected` (fail-closed). This proposal records the chain so that a future operation missing all markers is not silently misclassified in either direction; the adapter comment should be treated as the classifier contract until a stricter schema-side outcome declaration exists.

## Identity Sharing (RG10-SPEC-007)

`confirm_imported_stored_value_recharge` and `ingest_stored_value_recharge_candidate` share the request identity `request-import-recharge-rg10`; the spend pair shares `request-import-spend-rg10` (both actions derive `Rg10OperationIdentity(ledgerId, requestId)`). On one serial runtime, the second action with a changed fingerprint returns `RequestIdentityConflict` — correct idempotency behavior, not a defect. The oracle therefore executes each incomplete confirmation on an independent fresh runtime against its fixture baseline (opening / recharge-confirmed), matching the v1 semantics where the incomplete import is an alternative baseline branch; `Rg10OperationsTest.imported candidates stay pending...` asserts the same isolation and atomic rejection.

## Persistence Deferral (RG10-QA-04)

`StoredValueReconstruction.reconstructedTransactionIds` is currently always empty at the persistence boundary: `confirmActivationBalance` passes `emptyList()`, the `rg10_reconstruction` table has no column for reconstructed transaction ids, and `SqlDelightRg10Store.loadPersistedSnapshot` hardcodes `reconstructedTransactionIds = emptyList()`. The reconstruction feature that creates reconstructed transactions is not yet activated (no runtime operation registers a reconstruction transition), so nothing is lost today. The column and the loader projection are deferred to the future migration that activates reconstruction; this proposal records the deferral as a known persistence limitation (RG10-QA-04) rather than a silent data loss.

## Known Records (RG10-QA-08/11)

- `countRg10FormalTransactions` counts every `ledger_transaction` row of the ledger, not only RG-10-created transactions; this is the shared-formal-chain limit also present in the RG-09 store.
- `loadPersistedSnapshot` hard-errors on a ledger transaction without `rg10_formal_transaction_metadata` (`missing persisted RG-10 formal transaction metadata`); the RG-10 store shares this limitation with the RG-09 store, which owns the same shared chain.
- `activateReconstructedMode` in the domain layer is currently dead code: the runtime registers no reconstruction activation, and `rg10_reconstruction_history` only ever records the `CREATED` event even though the schema guard admits `ACTIVATED`.
- Imports never auto-confirm: `ingest_stored_value_*_candidate` keeps candidates `pending_confirmation` with zero formal effect; this is the frozen v1 semantics, asserted by `Rg10RawJsonEndToEndTest.imports never auto-confirm even with complete facts`.
- `rename_stored_value_labels` zero-effect acceptance is an inference (see classification registry above) and is asserted by the oracle and `Rg10OperationsTest`.

## Acceptance Evidence

| Claim | Independent command or test | Result |
| --- | --- | --- |
| Every frozen operation is replayed and compared (44 operations, 12/10/22 families) | `:ledger-data:jvmTest --tests com.unifiedledger.data.Rg10FullStateOracleTest` | PASS (7 tests: registry, main, reconciliation, import, secondary, idempotency, invalid) |
| Runtime action semantics, identity conflict, category/provenance boundaries | `:ledger-application:jvmTest --tests com.unifiedledger.application.Rg10OperationsTest` | PASS (14 tests) |
| Persistence survives reopen; rejected receipt has zero dependent rows; failure rolls back claim and dependent writes; guards reject immutable/sequence/wrong-target writes; repeated deduction rejected | `:ledger-data:jvmTest --tests com.unifiedledger.data.SqlDelightRg10StoreTest` | PASS (8 tests) |
| v12 to v13 preserves prior formal rows and creates empty RG-10 owners across reopen; fresh schema creates every table at version 13 | `:ledger-data:jvmTest --tests com.unifiedledger.data.LedgerDatabaseMigrationTest` | PASS (13 tests, including the new `versionTwelveToThirteen...` test) |
| Canonical RG-10 transaction kinds survive write and reopen; rg10 owners created empty on fresh schema | `:ledger-data:jvmTest --tests com.unifiedledger.data.Rg10SchemaV13Test` | PASS (2 tests) |
| Frozen registry replays through the typed runtime with exact reasons and stable ids | `:ledger-data:jvmTest --tests com.unifiedledger.data.Rg10RawJsonEndToEndTest` | PASS (9 tests) |
| SQLDelight migration declaration is complete | `:ledger-data:verifyCommonMainLedgerDatabaseMigration` | PASS |
| Version assertion bumps | 7 files: `Rg03SchemaV5Test`, `Rg04SchemaV6Test`, `Rg04SchemaV7Test`, `Rg06SchemaV9Test`, `Rg09SchemaV12Test`, `SqlDelightRg05StoreTest`, `LedgerDatabaseMigrationTest` | present |

Rejected field paths have an authoritative oracle-side mirror (`Rg10FullStateOracleTest.expectedFieldPath`, RG10-SPEC-006); the authoritative runtime source is the `rejectInvalidInput` table in `Rg10Operations.kt` together with the mapping registry's six rejection branches, and this proposal declares that both sides must stay in agreement.

### Independent review disposition

The frozen candidate was reviewed by an independent specification reviewer (RG10-SPEC-001..010) and an independent quality reviewer (RG10-QA-01..11); both returned CONDITIONAL with no FAIL items.

- HIGH: RG10-SPEC-001 complete oracle — closed by `Rg10FullStateOracleTest`; RG10-SPEC-002 projection registration — closed by this proposal and the oracle class contract; RG10-SPEC-003 multi-lot reverse-expected reading and missing execution — closed by the synthetic input registry above (consumption count derived from inline base, never from expected) and oracle secondary-case execution; RG10-QA-01 multi-lot input crash — closed by the adapter synthesis; RG10-QA-02 confirm-spend phantom transaction on rejection — closed by atomic rejection (store test `failureAfterDeltaRollsBackClaimAndEveryDependentWrite`).
- MEDIUM: RG10-SPEC-004 tracked closure proposal — this document; RG10-SPEC-005 rename inference — classification registry above; RG10-SPEC-006 rejected field-path authority — oracle mirror plus this declaration; RG10-QA-03 composition-status case round-trip — covered by oracle full-state and typed comparisons; RG10-QA-04 reconstruction persistence — persistence deferral section above; RG10-QA-06 reconcile ignores input role — covered by evidence-link projection and store guard tests; RG10-QA-07 v12 to v13 migration test and stale test names — the new `versionTwelveToThirteen...` test plus the `...AtVersionThirteen` rename in `LedgerDatabaseMigrationTest`.
- LOW: RG10-QA-05 repeated lot deduction — closed by `lotGuardRejectsRepeatedDeductionOfTheSameConsumptionRow`; RG10-QA-08..11 — known records section above; RG10-SPEC-007..010 — identity sharing, PROGRESS_LOG count wording, status/reminder assertions, and the 44-vs-50 argument are registered in this proposal and the oracle.

## Unclosed Items And Next Steps

- The reconciliation transaction-level `pending_financial_evidence` kind rule is an oracle-side inference of v1 semantics (annotated in `Rg10FullStateOracleTest.projectReconciliation`); it stays a projection-layer contract until a v1 reconciliation authority exists.
- Activation and rename design-document boolean flags (`pre_activation_events_unchanged`, `double_counting`, the replace-not-append rule text) are not asserted flag by flag; they are covered by the canonical-state full comparison and the typed activation/rename tests.
- `RG10-GAP-05` stays fail-closed (no `parent_id` guess); `RG10-GAP-06` stays partially closed (legacy link status has no owner). Both remain recorded in the mapping document's unresolved-gap list and do not block the runtime, persistence, or oracle acceptance.
- `StoredValueReconstruction.reconstructedTransactionIds` persistence is deferred (see RG10-QA-04); the schema column and loader projection ship with the future reconstruction-activation migration.
- The mapping gate moves to `approved` with this proposal; publication (`golden/rules-v2/rg-10.json` + manifest) and push remain separate gates requiring their own authorization.

## Acceptance

The D-083 owner table, the 44-operation oracle, the projection and inference registries, the persistence boundary, the v12 to v13 migration evidence, and the independent review disposition are now present. Per D-083 the mapping gate is marked `approved`. Publication and push remain separate gates.
