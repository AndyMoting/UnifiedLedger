# RG-10 Golden Schema v2 Mapping

## Authority

This path map is governed by the frozen RG-10 fixture, stored-value design, Golden Schema v2, accounting rules, golden tests, and D-034, D-035, D-036, D-037, D-043, D-044, D-050. This artifact authorizes no schema, adapter, expected output, or fixture rewrite.

## Inventory

- normalized paths: 1161
- leaf occurrences: 2022
- classified/unclassified: 1161/0
- classifications: map 660, derive 490, preserve 10, reject 1
- dispositions: requires_contract_amendment 873, ready 287, test_only_exclusion 1

## Frozen Semantics

Recharge is stored_value_recharge: stored-value asset +1200.00, payment bank asset -1000.00, and special non-cash bonus income -200.00. It has cash_outflow 1000.00 and zero consumption. Spend is stored_value_spend: expense +300.00 and stored-value asset -300.00, with consumption 300.00 and zero additional cash flow. No paid/bonus asset split, immediate expense, hidden clearing leg, or duplicate consumption is allowed.

Lots retain stable IDs, face value, explicit paid/bonus facts, and loaded/expiry ordering. Merchant allocation overrides expiry, loaded_at, stable-ID ordering only with evidence. Consumption records lot amount and evidence; missing composition remains unknown and never becomes paid-first or bonus-first. Expiry needs explicit confirmation and only affects confirmed remaining value. Dates, reminders, and status labels have zero formal effect.

The activation boundary is stored_value_pre_activation_balance_adjustment, not recharge or ordinary income. Reconstruction is replace_not_append: adjustment or reconstructed history is effective, never both; adjustments, versions, and provenance remain append-only. Renaming an account or lot retains stable IDs and has zero economic and reconciliation effect.

Merchant credit uses two independent typed links: target_kind=posting, target_id=stored-value asset posting, role=stored_value_asset_posting; and target_kind=domain_entity, target_id=stored_value_lot, role=stored_value_lot_fact. The posting link can reconcile; the lot link proves only the lot fact. Bank evidence remains target_kind=posting with role=bank_payment_posting. The old mixed stored_value_credit_lot link is never emitted. Legacy not_present/not_applicable reconciliation maps to no posting_reconciliations record. Activation evidence targets the activation_adjustment domain entity with role=stored_value_activation_balance_fact; it never targets the adjustment transaction or a posting. Bonus-component and expiry-confirmation evidence remain in their dedicated contract gap until exact domain roles and targets are registered; neither is aliased to stored_value_lot_fact.

Imported recharge and spend remain pending_confirmation with zero formal effect until behavior/model, owned accounts, exact amounts, actual time, lot facts or allocation, category, evidence, and explicit confirmation are complete. Operation action_type and operation_class remain explicit; rejected forms use sparse attempted_input, and retries retain their originating action. created_at and confirmed_at are mapped only when the fixture has actual evidence.

## Planned Action Registry

| action_type | operation_class | payload |
| --- | --- | --- |
| confirm_stored_value_recharge | creation / rejection | closed input / sparse attempted_input |
| confirm_stored_value_spend | creation / rejection | closed input / sparse attempted_input |
| ingest_stored_value_recharge_candidate | creation | closed input; pending candidate only |
| ingest_stored_value_spend_candidate | creation | closed input; pending candidate only |
| confirm_imported_stored_value_recharge | rejection | sparse attempted_input |
| confirm_imported_stored_value_spend | rejection | sparse attempted_input |
| record_expiry_reminder | status_transition | closed input, zero formal deltas |
| confirm_stored_value_expiry_loss | creation / rejection | closed input / sparse attempted_input |
| reconcile_merchant_credit | reconciliation | closed posting-link input; lot link unchanged |
| reconcile_bank_payment | reconciliation | closed posting-link input |
| apply_merchant_lot_allocation | update / rejection | closed input / sparse attempted_input |
| confirm_stored_value_activation_balance | adjustment | closed input |
| rename_stored_value_labels | update | closed input, financial no-change |

Every retry retains its originating action_type and operation_class, returns the original stable IDs with outcome.status=no_change, and has exhaustive zero deltas.

## Unresolved Gaps

1. RG10-GAP-01: Closed RG-10 stored-value action family and atomic operation outcomes (527 affected paths)
2. RG10-GAP-02: Stored-value lot, consumption, allocation, and expiry lifecycle payloads (75 affected paths)
3. RG10-GAP-03: Stored-value import source, candidate, confirmation, and provenance payloads (109 affected paths)
4. RG10-GAP-04: Activation boundary and replace-not-append reconstruction semantics (136 affected paths)
5. RG10-GAP-05: Stored-value account activation, merchant restriction, category metadata, and precision contract (11 affected paths)
6. RG10-GAP-06: Bonus-component and expiry-confirmation evidence roles and domain targets (15 affected paths)

## Gate

- status: needs_contract_amendment
- expected output gate: closed
- unresolved gap count: 6
Expected output remains closed. No schema, adapter, expected output, or fixture rewrite is implemented.
