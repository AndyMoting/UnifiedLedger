# RG-07 Golden Schema v2 Mapping

## Authority

This path map is governed by the frozen RG-07 fixture, refund design, golden tests, accounting rules, Golden Schema v2, and D-010, D-013, D-043, D-044, D-045, D-047, D-061, D-078. Historical external RG numbering is evidence only. This artifact authorizes no adapter, expected output, fixture rewrite, runtime, or publication.

## Inventory

- normalized paths: 2523
- leaf occurrences: 6084
- classified/unclassified: 2523/0
- classifications: map 1815, derive 613, preserve 87, reject 8
- dispositions: ready 2515, test_only_exclusion 8

## Frozen Semantics

The original 120.00 CNY expense remains on its original date. Actual receipt creates a separate balanced refund_receipt at arrival: destination asset +30.00 and the original exact secondary expense account -30.00. The v1 refund_cash_inflow value maps to the current canonical cash_inflow report metric; refund_receipt preserves the event distinction, while ordinary_income remains a separate zero metric. The arrival period therefore records consumption -30.00, cash inflow 30.00, ordinary income 0.00, and net-worth change +30.00. Requested, approved, and processing states have zero formal effects. Active linked partial refunds accumulate; over-cap allocation rejects atomically.

Merchant evidence targets only the refund relationship. Bank or wallet credit evidence targets only the exact destination asset posting. Posting evidence mappings include target_kind, target_id, and role. Expense postings use reconciliation_eligible=false with no reconciliation record. Opening is the only collapsed occurred_at expanded to all three economic times; other transaction statistics times are explicit. No created_at or confirmed_at is generated.

The manual and import paths remain independent roots. Import intake creates only a pending candidate until the original transaction, exact category and allocation, destination account, and arrival fact are explicitly confirmed. Mirror evidence adds provenance and reconciliation facts without creating another relation, transaction, version, posting, consumption effect, or cash-flow effect. Every rejected or pending operation compares a complete named baseline with an equal result state and exhaustive zero deltas.

The v2 relation owns typed membership between the original transaction and each independent refund transaction. The refund_relationship domain entity owns requested and received amounts, inherited category, destination, distinct request, approval, processor, observation, booking, value, arrival, and confirmation times, plus append-only lifecycle history. Evidence never substitutes for these ownership boundaries.

## Action Registry

Every family uses a top-level action_type and operation_class. Accepted and no-change forms use a strict closed input. Rejected forms forbid input and use a closed sparse attempted_input; omitted fields are not synthesized. A retry retains its originating discriminator and never becomes a generic retry action.

| action_type | operation_class | payload | source family |
| --- | --- | --- | --- |
| record_refund_request_status | status_transition | input | refund request and status-only changes |
| ingest_refund_status_source | status_transition | input | merchant status notice |
| confirm_manual_refund_receipt | creation | input | confirmed manual arrival |
| confirm_manual_refund_receipt | rejection | attempted_input | unconfirmed manual arrival |
| attach_original_payment_evidence | reconciliation | input | original payment source and exact payment-posting evidence |
| attach_refund_destination_evidence | reconciliation | input | bank or wallet destination evidence |
| attach_refund_dual_role_evidence | reconciliation | input | independently typed composite evidence links |
| confirm_refund_receipt | creation | input | accepted cumulative partial-refund receipt |
| allocate_refund_receipt | rejection | attempted_input | atomic over-cap allocation rejection |
| ingest_refund_credit_source | creation | input | pending imported refund candidate |
| confirm_imported_refund | creation | input | complete explicit imported confirmation |
| confirm_imported_refund | rejection | attempted_input | incomplete imported confirmation |
| merge_refund_mirror_evidence | reconciliation | input | mirror evidence merge |
| validate_refund_receipt | rejection | attempted_input | invalid refund receipt attempts |

The machine path map additionally freezes each row's exact required and optional fields with additional_properties=false. No operation dispatches through input.kind.

## Resolved Contract Gaps

1. RG07-GAP-01: Closed RG-07 action registry and atomic outcomes (265 affected paths)
2. RG07-GAP-02: Refund relation and refund_relationship lifecycle (504 affected paths)
3. RG07-GAP-03: Refund provenance payloads (569 affected paths)

## Gate

- status: approved
- expected output gate: approved
- unresolved gap count: 0
- contract gap count: 0
- resolved contract gap count: 3

Expected output is approved. Contract, Schema, validator, expected output, adapter, fixture replay, schema v10 migration, and Kotlin runtime are implemented; publication remains closed pending an explicit target.
