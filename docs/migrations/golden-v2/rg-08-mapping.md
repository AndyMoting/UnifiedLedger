# RG-08 Golden Schema v2 Mapping

This inventory is closed and preserves all **4969** normalized source paths and **16797** leaf occurrences, with **4967** mapped paths, exactly **2** rejects, and no unclassified paths.

## Disposition

| Disposition | Paths |
| --- | ---: |
| Ready in the current schema | 2003 |
| Requires contract amendment | 2964 |
| Test-only exclusion | 2 |
| Total | 4969 |

The expected-output gate remains **closed**. Every `requires_contract_amendment` entry has at least one matching gap, and every planned-contract target is amendment-only with gap IDs.

## Contract Gaps

| Gap | Affected paths | Boundary |
| --- | ---: | --- |
| `RG08-GAP-01` | 545 | Closed RG-08 lending action registry and atomic outcomes |
| `RG08-GAP-02` | 1069 | Lending relations and domain entity payloads/lifecycles |
| `RG08-GAP-03` | 1374 | Lending intake, evidence subtypes, confirmations, and typed mirror/merge audit links |
| `RG08-GAP-04` | 43 | Actual receipt and effective economic time |

Each gap's `affected_source_paths` list is sorted and unique, and is bidirectionally identical to entry-level `contract_gap_ids`.

## Mapping Rules

- Each source leaf maps to its exact owning canonical collection field; balance/report/reconciliation map keys are context, not extra target fields.
- All position and settlement lifecycle `history[*].id` values map to `domain_entities[*].payload.history[*].id`, never to the owning `domain_entities[*].id`.
- Other nested IDs map to the owning `states[*]` collection (`domain_entities`, `transactions`, `transaction_versions`, `posting_sets`, `postings`, `candidates`, `confirmations`, `evidence`, `evidence_links`, `audit_links`, or `posting_reconciliations`). Only real state snapshot IDs map to `states[*].id`.
- `mirror_of_evidence_id` and `merged_into_evidence_link_id` form typed audit-link `from` and `to` references. They are not evidence-link fields and remain blocked by `RG08-GAP-03`.
- `not_applicable` reconciliation values map only to `states[*].postings[*].reconciliation_eligible`; no reconciliation record or operation/candidate payload is fabricated.
- `occurred_at` and `statistics_at` remain economic/reporting times. `created_at` is confirmation/version creation time only. Actual/proposed receipt and unavailable `effective_at` are tracked by `RG08-GAP-04` and never derived from `created_at`.
- Current `relations` is false. Agreement links, source/status, mirror/merge provenance, and counterparty lending relation instances remain in `RG08-GAP-02` or `RG08-GAP-03`; role enum membership alone is not relation capability.
