# RG-10 Golden Schema v2 Mapping

## Authority

This path map is governed by the frozen RG-10 fixture, stored-value design, Golden Schema v2, accounting rules, golden tests, and D-034, D-035, D-036, D-037, D-043, D-044, D-045, D-047, D-050, D-063, D-064, D-066, D-067, and D-068. It records the approved catalog, reconstruction, bonus/expiry provenance, and closed operation-registry foundation amendments but authorizes no adapter, expected output, fixture rewrite, economic creation operation, or replay switch.

## Inventory

- normalized paths: 1161
- leaf occurrences: 2022
- classified/unclassified: 1161/0
- classifications: map 662, derive 489, preserve 9, reject 1
- dispositions: requires_contract_amendment 708, ready 452, test_only_exclusion 1

## Frozen Semantics

Recharge is stored_value_recharge: stored-value asset +1200.00, payment bank asset -1000.00, and special non-cash bonus income -200.00. It has cash_outflow 1000.00 and zero consumption. Spend is stored_value_spend: expense +300.00 and stored-value asset -300.00, with consumption 300.00 and zero additional cash flow. No paid/bonus asset split, immediate expense, hidden clearing leg, or duplicate consumption is allowed.

Lots retain stable IDs, face value, explicit paid/bonus facts, and loaded/expiry ordering. Merchant allocation overrides expiry, loaded_at, stable-ID ordering only with evidence. Consumption records lot amount and evidence; missing composition remains unknown and never becomes paid-first or bonus-first. Expiry needs explicit confirmation and only affects confirmed remaining value. Dates, reminders, and status labels have zero formal effect.

The activation boundary is stored_value_pre_activation_balance_adjustment, not recharge or ordinary income. The closed activation_adjustment domain entity binds that transaction identity. The closed stored_value_reconstruction domain entity owns the original adjustment endpoint, a unique reconstructed transaction ID set, active_mode, and append-only mode history; its stable entity ID owns replacement_group_id. Typed empty-payload audit links exactly cover the adjustment and reconstructed transaction endpoints. Relations remain closed and own no lifecycle or business state.

The frozen reconstruction policy is replace_not_append: adjustment or reconstructed history is effective, never both, and both endpoint histories remain preserved. This batch validates domain identity, endpoint uniqueness and typing, active-mode history, and append-only state transitions only. Current formal replay does not select postings by active_mode, and no reconstruction operation or fixture transition is registered, so economic exclusivity and operation-level preservation remain RG10-GAP-04.

Merchant credit requires two independent typed links: target_kind=posting, target_id=stored-value asset posting, role=stored_value_asset_posting; and target_kind=domain_entity, target_id=stored_value_lot, role=stored_value_lot_fact. When a bonus fact is present, a separate optional target_kind=domain_entity link uses role=stored_value_bonus_component and targets the exact immutable stored_value_bonus_component. The posting link can reconcile; the lot and bonus links prove only their domain facts. Bank evidence remains target_kind=posting with role=bank_payment_posting. The old mixed stored_value_credit_lot link is never emitted. Legacy not_present/not_applicable reconciliation maps to no posting_reconciliations record. Activation evidence targets the activation_adjustment domain entity with role=stored_value_activation_balance_fact; it never targets the adjustment transaction or a posting.

The closed stored_value_bonus_component binds stable lot and recharge transaction identities, exact non-negative amount and currency, and the recharge bonus-income postings. The closed stored_value_expiry_event binds a positive lot amount and currency to an append-only reminder or reminder-then-confirmed status history. Reminder has no loss transaction and zero formal effect; only a confirmed history event carries the exact stored_value_expiry_loss transaction ID, whose loss and stored-value postings must match the fact. Evidence verification remains independent: role=stored_value_expiry_confirmation may target the exact expiry event but does not create the loss transaction or alter posting reconciliation. stored_value_expiry_event is never a role token. Legacy link status still lacks a current evidence-verification owner and remains RG10-GAP-06.

Imported recharge and spend remain pending_confirmation with zero formal effect until behavior/model, owned accounts, exact amounts, actual time, lot facts or allocation, category, evidence, and explicit confirmation are complete. The structural registry closes the 13 approved `action_type` tokens, their exact `operation_class` and outcome-status combinations, accepted/no-change closed inputs, and rejected sparse `attempted_input` forms. Its six rejection branches register only the 19 frozen reason codes and their exact field paths. Current owners execute 14 unique reasons across 17 of the 20 `invalid_inputs` end to end through action-specific predicates and generic rejected atomicity; wrong reasons, wrong fields, lower-priority failures, legal-field claims, state changes, nonzero deltas, status changes, and returned IDs are rejected. The other three invalid-input reasons and both incomplete-import reasons remain gated by missing effect, replay, provenance, or import owners. Accepted/no-change RG-10 effects and the five owner-dependent rejections remain fail-closed; fixture migration is not implemented. `created_at` and `confirmed_at` are mapped only when the fixture has actual evidence.

Current v2 owners close precision, ledger identity, account kind and real-account status, category posting ownership, stored-value account configuration, and stored-value system roles. RG-10 CNY precision maps exactly to the currency declaration; every source account ledger_id must equal the case ledger_id and is not serialized per account; account type maps to kind; financial maps account-by-account to real_account with asset/liability true and every other kind false; category account_id maps to posting_account_id; and category kind must equal the referenced posting account kind without adding a category kind field. A single optional closed account `stored_value` object owns `enabled`, `merchant_restricted`, and `merchant_id`; object presence owns capability. The account `system_role` registry now owns all three frozen stored-value roles.

Category identity remains `parent_id` plus `posting_account_id`, with at most two levels. A first-level category has neither parent nor posting account; an active second-level category has a non-null parent and must own an expense or income `posting_account_id`. An inactive legacy tombstone may retain its parent while lacking a posting owner, but cannot be selected or referenced by a formal consumption/allocation. Numeric `level` is derived and is never stored. Because the frozen RG-10 fixture has only `level` and no parent category identity, that source path remains unresolved and no parent is guessed.

## Structural Action Registry

| action_type | operation_class | payload |
| --- | --- | --- |
| confirm_stored_value_recharge | creation / rejection | closed input / sparse attempted_input |
| confirm_stored_value_spend | creation / rejection | closed input / sparse attempted_input |
| ingest_stored_value_recharge_candidate | creation | closed input; pending candidate only |
| ingest_stored_value_spend_candidate | creation | closed input; pending candidate only |
| confirm_imported_stored_value_recharge | rejection | sparse attempted_input |
| confirm_imported_stored_value_spend | rejection | sparse attempted_input |
| record_expiry_reminder | status_transition | closed input; effects gated |
| confirm_stored_value_expiry_loss | creation / rejection | closed input / sparse attempted_input |
| reconcile_merchant_credit | reconciliation | closed posting-link input; effects gated |
| reconcile_bank_payment | reconciliation | closed posting-link input |
| apply_merchant_lot_allocation | update / rejection | closed input / sparse attempted_input |
| confirm_stored_value_activation_balance | adjustment | closed input |
| rename_stored_value_labels | update | closed input; effects gated |

The schema has no generic retry action or `input.kind` dispatch. Rejected/no-change operations retain the shared baseline/result equivalence, zero-delta, zero-status-change, and empty-return requirements. Complete same-action retry validation, original returned IDs, a migrated RG-10 baseline/result pair, and exhaustive fixture effect signatures remain RG10-GAP-01; the full semantic validator rejects the structural action before any economic effect claim is accepted.

## Unresolved Gaps

1. RG10-GAP-01: RG-10 operation effects, fixture transitions, and complete retries (387 affected paths). Schema dispatch, exact six-branch rejection registries, and 14 owner-backed predicates covering 17 frozen invalid cases are executable end to end with generic rejected atomicity. Five cases remain gated: effective stored balance and lot remaining face value need effect/replay owners, paid/bonus composition needs a provenance owner, and both incomplete imports need candidate/fact owners. Economic creation, lifecycle effects, returned IDs for accepted operations, exhaustive fixture effect signatures, fixture migration, and complete retry behavior remain gated; accepted/no-change semantic operation validation remains fail-closed.
2. RG10-GAP-02: Remaining stored-value lot, consumption, allocation, and expiry lifecycle payloads (71 affected paths)
3. RG10-GAP-03: Stored-value import source, candidate, confirmation, and provenance payloads (109 affected paths)
4. RG10-GAP-04: Activation boundary operations, replay exclusivity, and replace-not-append transitions (137 affected paths). Domain identity, audit topology, and canonical bonus amount ownership are executable; fixture migration, reconstructed transaction creation, replay owner selection, atomic rejection, and complete operation transitions remain gated.
5. RG10-GAP-05: Category parent identity migration (1 affected path). Stored-value configuration and system roles are now ready. The remaining numeric level path cannot map until an explicit sanitized parent identity is supplied; it must not be inferred.
6. RG10-GAP-06: Independent bonus/expiry business-evidence verification status (3 affected paths). Both roles and exact domain targets are executable; legacy link status cannot map to posting reconciliation or domain lifecycle state.

## Gate

- status: approved
- expected output gate: closed
- unresolved gap count: 6
Expected output remains closed. No adapter, expected output, or fixture rewrite is implemented.

Mapping gate approved on 2026-08-08 under D-083: the closure proposal (`docs/migrations/golden-v2/rg-10-closure-proposal.md`), the complete 44-operation oracle (`Rg10FullStateOracleTest`: accepted 12 / no_change 10 / rejected 22), the focused persistence/migration tests (`SqlDelightRg10StoreTest`, `Rg10SchemaV13Test`, `LedgerDatabaseMigrationTest` including the new v12 to v13 migration test), and the independent specification (RG10-SPEC-001..010) and quality (RG10-QA-01..11) review dispositions are all present. The six GAP records above remain the historical mapping state; their D-083 dispositions are recorded in the closure proposal. Publication and push remain separate gates.
