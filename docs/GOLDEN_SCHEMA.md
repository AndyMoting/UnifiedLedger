# Golden Schema v2 Data Contract

## Status and authority

This document is the proposed Golden Schema v2 data contract for final review. It is a design artifact, not a JSON Schema, validator, adapter, migration implementation, or approval to rewrite a frozen fixture. The examples in `docs/examples/golden-schema-v2/` are anonymous representative drafts and are not migrated expected outputs.

The signed-in frozen specification, tests, and v1 fixture for each current RG remain the migration authority. Historical external RG numbering does not override current repository RG identities.

## Envelope

Every document is one JSON object with these required members:

| Member | Contract |
| --- | --- |
| `contract` | Exactly `unifiedledger.golden-case`. |
| `contract_version` | Exactly `2.0.0` for this contract. |
| `case` | Case metadata described below. |
| `roots` | One or more explicitly isolated execution roots. |
| `states` | Complete state snapshots owned by roots. |
| `operations` | Ordered assertions that connect baseline and result states. |

`case` requires `id`, `level`, `rule_version`, `approval_status`, `ledger_id`, `timezone`, and `currencies`. `approval_status` is `draft_for_review` for the representative examples. `currencies` is a set-like array of `{code, precision}` records. Currency code and precision determine every decimal amount in the case.

Each root requires `id`, `purpose`, `initial_state_id`, and ordered `operation_ids`. A state and every entity it owns belongs to exactly one root. References across roots are invalid. A root never inherits catalog entries, entities, balances, reports, or status from another root. Variants and independent baselines therefore use separate roots instead of being appended to the main path.

## Complete states

Every state is a complete snapshot, not a before/after patch. It requires all of these members, including empty arrays:

- `id`, `root_id`, and `as_of_operation_id`; the initial state uses `null` for `as_of_operation_id`.
- `catalog` with complete `accounts` and `categories` for that state.
- `transactions`, `transaction_versions`, `posting_sets`, and `postings`.
- `sources`, `candidates`, `confirmations`, `evidence`, and `evidence_links`.
- `relations`, `domain_entities`, and `audit_links`.
- `posting_reconciliations`, `balances`, `reports`, and `derived_statuses`.

Catalog entities are state-owned because names, active flags, and other catalog attributes can change. Stable IDs do not change. A catalog update changes the entity snapshot and appends its own history entry; transactions continue to reference the stable ID. Golden states do not delete history. A correction appends a version or history record and updates an explicit current pointer.

`balances` must contain exactly one balance for every account in the state catalog, including hidden, non-financial, and zero-balance accounts. Each balance is `{account_id, currency, amount}`. No account may be omitted merely because it is not the focus of the operation. Balance maps containing only changed accounts are deltas, not states.

## Canonical object shapes and registries

This section is normative. The Schema stage translates these shapes mechanically and must not choose new fields, enums, discriminators, payloads, defaults, aliases, or action inputs.

All normative objects are closed: JSON Schema uses `additionalProperties: false`. Every polymorphic object has a required `type` discriminator and a required closed `payload` object. Fields common to all subtypes remain beside `type`; subtype-specific fields exist only in `payload`. An empty registered payload is `{}`. Extensions require a reviewed contract amendment and cannot be invented by a Schema, validator, adapter, or migration implementation.

The scalar aliases used below are: `id` = non-empty stable ID string; `decimal` = canonical fixed-precision string; `currency` = a declared currency code; `timestamp` = the strict timestamp defined later; `ref` = closed `{kind, id}` where `kind` is one of `operation`, `transaction`, `transaction_version`, `posting_set`, `posting`, `source`, `candidate`, `confirmation`, `evidence`, `evidence_link`, `relation`, `domain_entity`, or `observation`.

### Envelope and state shapes

| Object | Required fields | Optional fields and enums | References |
| --- | --- | --- | --- |
| envelope | `contract:string`, `contract_version:string`, `case:object`, `roots:array`, `states:array`, `operations:array` | none | roots, states, and operations are internally linked |
| case | `id:id`, `level`, `rule_version:integer >= 1`, `approval_status`, `ledger_id:id`, `timezone:string`, `currencies:array` | `level`: `core_required`, `core_reserved`, `future_draft`; `approval_status`: currently only `draft_for_review` | none |
| currency declaration | `code:currency`, `precision:integer 0..18` | none | none |
| root | `id:id`, `purpose:string`, `initial_state_id:id`, `operation_ids:id[]` | none | initial state and operations in same root |
| state | all fields listed in Complete states | `as_of_operation_id` is `id` or `null`; all collection fields are required | same-root entities only |

### Catalog and formal ledger shapes

| Collection | Required fields | Optional fields and enums | References and time semantics |
| --- | --- | --- | --- |
| `catalog.accounts` | `id`, `name:string`, `kind`, `currency`, `owned_by_user:boolean`, `real_account:boolean`, `reconciliation_eligible:boolean` | `kind`: `asset`, `liability`, `equity`, `income`, `expense`; optional `system_role`, `system_managed:boolean`, `hidden:boolean`; registered `system_role`: `opening_balance`, `balance_adjustments` | currency declaration |
| `catalog.categories` | `id`, `name:string`, `parent_id:id|null`, `posting_account_id:id|null`, `active:boolean` | none | parent category and posting account in same state |
| `transactions` | `id`, `type`, `current_version_id` | `type` registry below | current version owned by this transaction |
| `transaction_versions` | `id`, `transaction_id`, `version_number:integer >= 1`, `posting_set_id`, `occurred_at`, `statistics_at`, `effective_at` | optional `created_at`, `note:string`, `confirmation_id` | transaction, posting set, optional confirmation; time roles are defined under Formal ledger ownership |
| `posting_sets` | `id`, `posting_ids:id[]` | none | at least two postings, all owned by this set |
| `postings` | `id`, `posting_set_id`, `account_id`, `amount`, `currency`, `reconciliation_eligible:boolean` | optional registered `role` | posting set and account |

Canonical transaction registry:

| Type | Required semantics |
| --- | --- |
| `opening_balance` | Establishes a replayable opening position; excluded from ordinary period activity. |
| `expense` | Expense-role postings balance explicit funding postings. |
| `income` | Income-role postings balance explicit receipt postings. |
| `account_transfer` | Principal uses transfer-in/out roles; internal principal is not external income, expense, or cash flow. |
| `credit_repayment` | Liability principal reduction is not repeated consumption; actual asset reduction is cash outflow. |
| `refund_receipt` | New economic event at actual receipt time; offsets the original expense category and is not ordinary income. |
| `lending_disbursement` | Receivable increases, funding asset decreases, signed principal external cash flow is negative. |
| `lending_collection` | Destination asset increases, receivable decreases, signed principal external cash flow is positive. |
| `balance_adjustment` | Target account and dedicated balance-adjustment equity balance at the target observation time. |
| `balance_adjustment_reversal` | Reverses only an allocated part of a prior balance adjustment at that same target time. |
| `stored_value_recharge` | Formal recharge under the activated stored-value asset model. |
| `stored_value_spend` | Reduces stored-value asset and recognizes the applicable consumption. |
| `stored_value_expiry_loss` | User-confirmed actual expiry loss; reminders alone cannot create it. |
| `stored_value_pre_activation_balance_adjustment` | Preserved canonical type for the confirmed pre-activation balance boundary; it is not ordinary income or recharge. |

Canonical posting-role registry:

| Role | Required semantics |
| --- | --- |
| `expense`, `payment_asset` | Expense classification leg and actual asset payment leg. |
| `transfer_principal_in`, `transfer_principal_out`, `transfer_fee` | Internal destination principal, internal source principal, and externally consumed fee. |
| `destination_asset`, `funding_asset`, `bank_payment` | Actual destination, funding, or bank-payment real-account leg for the applicable transaction type. |
| `lending_receivable`, `lending_principal_in`, `lending_principal_out`, `lending_interest`, `lending_fee` | Lending position, principal direction, and separately classified interest/fee legs. |
| `balance_adjustment_target`, `balance_adjustment_counterpart` | Original target-account adjustment and dedicated equity counterpart. |
| `balance_adjustment_reversal_target`, `balance_adjustment_reversal_counterpart` | Allocated counter-adjustment and its dedicated equity counterpart. |
| `stored_value_asset`, `stored_value_bonus_income`, `stored_value_expiry_loss` | Stored-value face asset, separately reported bonus, and confirmed expiry-loss leg. |

An absent posting role is permitted only for `opening_balance`. Any new transaction type or posting role requires a contract amendment before Schema or adapter work.

### Provenance and business collection shapes

| Collection | Required common fields | Optional common fields | References |
| --- | --- | --- | --- |
| `sources` | `id`, `type`, `payload` | none | payload-defined references |
| `candidates` | `id`, `type`, `source_ids:id[]`, `confidence:decimal`, `payload`, `status_history:array` | none | sources; each history item is closed `{id, sequence:integer >= 1, status}` with status `pending_confirmation`, `confirmed`, `rejected`, or `incomplete` |
| `confirmations` | `id`, `type`, `operation_id`, `subject:ref`, `payload` | optional `confirmed_at` only when provided | operation is always required and must be the operation that created this confirmation |
| `evidence` | `id`, `type`, `source_ids:id[]`, `payload` | none | sources |
| `evidence_links` | `id`, `evidence_id`, `target_kind`, `target_id`, `role` | `target_kind`: `posting`, `observation`, `relation`, `domain_entity` | exact typed target |
| `relations` | `id`, `type`, `member_refs:ref[]`, `payload` | none | two or more same-root members |
| `domain_entities` | `id`, `type`, `payload` | none | payload-defined references |
| `audit_links` | `id`, `type`, `from:ref`, `to:ref`, `payload` | none | exact same-root endpoints |

Representative subtype payloads are fully frozen:

| Collection/type | Closed payload required fields |
| --- | --- |
| source `explicit_balance_observation` | `account_id`, `target_amount`, `currency`, `target_observed_at` |
| candidate `balance_adjustment` | `account_id`, `replayed_amount`, `target_amount`, `delta`, `currency`, `effective_at` |
| confirmation `explicit_manual_save` | empty payload; subject kind `operation` |
| confirmation `candidate_confirmation` | empty payload; subject kind `candidate`; `confirmed_at` required |
| confirmation `explicit_operation_confirmation` | empty payload; subject kind `operation`; `confirmed_at` required |
| evidence `user_balance_observation` | `observed_at` |
| domain entity `target_balance_observation` | `account_id`, `target_amount`, `currency`, `observed_at`, `source_id` |
| domain entity `balance_adjustment` | `observation_id`, `original_delta`, `currency`, `transaction_id` |
| domain entity `explanation_allocation` | `adjustment_id`, `explanation_transaction_id`, `reversal_transaction_id`, `amount`, `currency`, `confirmed_at` |
| audit link `adjustment_transaction`, `explanation_transaction`, `allocation_reversal` | empty payload; endpoint kinds must match the role semantics |

The remaining approved type registry reserves these semantics without claiming their full RG payloads are frozen:

- Relation types: `mixed_payment`, `merged_payment`, `staged_payment`. Each relates the exact business entity or allocation identities to its single formal payment identity; payload registration requires a later contract amendment.
- Domain entity types: `funding_component`, `consumption_record`, `item_allocation`, `installment_payment`, `refund_relationship`, `counterparty`, `lending_position`, `lending_settlement`, `settlement_component`, `stored_value_lot`, `lot_consumption`, `stored_value_consumption`, `activation_adjustment`, and `merchant_allocation`. Each owns the named business identity and lifecycle rather than formal postings. It is reserved but unusable until an amendment freezes its closed payload.
- Evidence-link roles: `target_balance_observation`, `real_account_posting`, `payment_asset_posting`, `destination_asset_posting`, `funding_asset_posting`, `bank_payment_posting`, `refund_relationship`, `counterparty_lending_relationship`, and `stored_value_activation_balance_fact`. Each may target only the fact named by the role.
- Confirmation types reserved for later payload registration: `refund_relationship_confirmation`, `lending_event_confirmation`, `lending_settlement_confirmation`, `stored_value_activation_balance_confirmation`, `stored_value_expiry_confirmation`, `stored_value_recharge_confirmation`, and `stored_value_spend_confirmation`. Every confirmation still requires `operation_id`, an exact subject, and an actual `confirmed_at` when confirmation occurred.
- Audit link types currently registered are only `adjustment_transaction`, `explanation_transaction`, and `allocation_reversal`.

Reserved-but-unregistered payloads cannot appear in a v2 document. A later independent contract amendment may register them without changing the representative examples or treating those examples as migration output.

### Projection shapes

| Collection | Closed shape |
| --- | --- |
| `posting_reconciliations` | `{id, posting_id, status}`; status is `pending`, `matched`, or `has_difference` |
| `balances` | `{account_id, currency, amount}` |
| `reports` | `{period_type, period, metrics}`; period type is `day`, `month`, or `cumulative` |
| report metric, applicable | `{metric, applicability:"applicable", currency, amount}` |
| report metric, inapplicable | `{metric, applicability:"not_applicable"}` |
| `derived_statuses` | `{id, target_kind, target_id, status_name, value}`; target kind is a `ref.kind`; value is a registered status string |

The RG-09 verification-status registry used here is `balanced_with_unexplained_adjustment`, `difference_pending_explanation_confirmation`, `evidence_incomplete`, and `fully_reconciled`. Explanation status is `open`, `partially_explained`, or `fully_explained`. Transaction reconciliation summary is `pending`, `partial`, `matched`, or `has_difference` and remains derived.

### Action registry

Operation `input` is closed per `action_type`. These are the only actions registered by this contract:

| Action type | Operation class | Required input fields |
| --- | --- | --- |
| `manual_expense` | `creation` | `request_id`, `amount`, `currency`, `category_id`, `payment_account_id`, `occurred_at`, `note`, `explicit_confirmation:true` |
| `transaction_note_update` | `update` | `request_id`, `transaction_id`, `note`, `explicit_confirmation:true` |
| `preview_target_balance` | `read` | `request_id`, `account_id`, `target_amount`, `currency`, `target_observed_at`, `explicit_confirmation:false` |
| `confirm_balance_adjustment` | `adjustment` | `request_id`, `candidate_id`, `account_id`, `target_amount`, `replayed_amount`, `delta`, `currency`, `effective_at`, `explicit_confirmation:true`, `confirmed_at` |
| `confirm_real_transfer` | `creation` | `request_id`, `target_account_id`, `counter_account_id`, `amount`, `currency`, `actual_occurred_at`, `discovered_at`, `explicit_confirmation:true`, `confirmed_at` |
| `confirm_explanation_allocation` | `reversal` | `request_id`, `adjustment_id`, `transaction_id`, `target_account_id`, `actual_occurred_at`, `real_transaction_amount`, `currency`, `target_observed_at`, `allocation_direction`, `explanation_amount`, all seven `confirms_*:true` fields shown in the representative sample, `explicit_confirmation:true`, `confirmed_at` |

For `confirm_explanation_allocation`, `allocation_direction` is currently `same_as_original_adjustment`; required booleans are `confirms_target_account`, `confirms_actual_occurred_at`, `confirms_real_transaction_amount`, `confirms_currency`, `confirms_target_observed_at`, `confirms_allocation_direction`, and `confirms_explanation_amount`. None of these facts may be inherited from the transaction, candidate, or prior operation.

All other RG actions require an independent contract amendment that freezes their operation class and closed input before Schema, validator, adapter, or migration implementation.

## Formal ledger ownership

The single formal chain is:

`transaction -> current_version_id -> transaction_version.posting_set_id -> posting_set.posting_ids -> postings`

- A transaction owns stable economic identity, `type`, and `current_version_id`.
- A transaction version owns versioned metadata and one `posting_set_id`.
- A posting set owns only its ordered-independent `posting_ids`.
- A posting owns `account_id`, signed `amount`, `currency`, optional `role`, and reconciliation eligibility.
- Posting objects are never embedded in transactions, versions, or posting sets.
- A metadata-only replacement may reuse the prior posting set. RG-01 note replacement therefore adds a version and changes `current_version_id` without adding postings or financial effects.
- Historical versions remain present. Whether a version is current or superseded is derived from `current_version_id`; an immutable old version is not rewritten to carry a new status.
- Every posting set balances exactly to zero per currency. Binary floating-point values are forbidden.

`occurred_at` is the economic occurrence time, `statistics_at` controls report period attribution, and `effective_at` controls balance replay. `created_at` is an optional actual creation or confirmation time. These fields are not interchangeable.

Canonical transaction types include `opening_balance`, `expense`, `income`, `account_transfer`, `credit_repayment`, `refund_receipt`, `lending_disbursement`, `lending_collection`, `balance_adjustment`, `balance_adjustment_reversal`, `stored_value_recharge`, `stored_value_spend`, `stored_value_expiry_loss`, and `stored_value_pre_activation_balance_adjustment`. The last token is preserved as a canonical type; migration must not alias or replace it silently.

### Cross-domain posting rules

- RG-03 transfer principal and fee are dispatched by posting `role`, not inferred from transaction type alone. Canonical roles are `transfer_principal_in`, `transfer_principal_out`, and `transfer_fee`. `internal_transfer_amount` is the sum of positive `transfer_principal_in` postings. A `transfer_fee` posting contributes to expense/consumption and external cash outflow under its account and role; it is excluded from internal principal.
- `lending_disbursement` increases the receivable principal and reduces the funding asset. `principal_external_cash_flow` is negative by the principal amount, while consumption, ordinary expense, ordinary income, and net-worth change are zero.
- `lending_collection` increases the destination asset and reduces receivable principal. `principal_external_cash_flow` is positive by the principal amount. Interest and fees use separate postings and report roles; principal never becomes consumption or income.

## Provenance and business layers

The following collections have distinct identities and cannot be collapsed into an import record:

- `sources` preserve observed or submitted facts and their provenance. A source does not become a formal transaction.
- `candidates` preserve proposed values, source references, confidence, and status history. Only explicit confirmation can create or replace formal ledger facts.
- `confirmations` preserve an explicit decision, its registered `type`, exact subject, confirming `operation_id`, and an actual confirmation time only when one was provided.
- `evidence` preserves a normalized evidentiary object and its source references.
- `evidence_links` connect one evidence object to exactly one typed target with one role. Target kinds are `posting`, `observation`, `relation`, or `domain_entity`.
- `relations` express typed links among existing identities. They do not own business amounts, lifecycle state, evidence, or audit history.
- `domain_entities` own business data and append-only lifecycle history, such as a target observation, balance adjustment, explanation allocation, lending position, refund relationship, or stored-value lot.
- `audit_links` are non-evidentiary links among formal and domain identities. Examples are `adjustment_transaction`, `explanation_transaction`, and `allocation_reversal`.

All targets must exist in the same root state and have the declared target kind. An evidence role cannot be used as a relation role, confirmation provenance, or audit role. Later evidence can change reconciliation facts but cannot change balances or reports.

No state may contain a redundant entity registry or nested projection that duplicates one of these canonical collections.

## Operations

An operation requires:

- `id`, `root_id`, and integer `sequence` unique within the root.
- `operation_class` and `action_type` as separate fields.
- `baseline_state_id` and `result_state_id`.
- `input`, containing all facts required by that action. Missing values are not inherited from a candidate or earlier operation.
- `outcome`, `status_changes`, `deltas`, and `returned_ids`.

Canonical operation classes are `creation`, `update`, `read`, `rejection`, `reconciliation`, `status_transition`, `reversal`, and `adjustment`. Candidate confirmation uses the class of its actual effect: for example, transaction creation is `creation`, evidence binding is `reconciliation`, and RG-09 explanation allocation plus its counter-adjustment is `reversal`.

`outcome.status` has exactly these meanings:

| Status | Meaning |
| --- | --- |
| `accepted` | The requested action completed and its declared result state is authoritative. It may have zero financial deltas but must have the declared state or intake effect. |
| `rejected` | Validation or business rules denied the action atomically. `reason_code` is required; baseline and result states are byte-equivalent after canonicalization. |
| `no_change` | The request was valid but intentionally produced no new state, normally an idempotent replay. `reason_code` is required; baseline and result states are byte-equivalent and returned IDs identify the prior result. |

Candidate states such as `pending_confirmation`, `confirmed`, and `rejected` are entity statuses, not operation outcomes. Incomplete intake that successfully saves a pending candidate is an `accepted` operation with intake deltas and zero formal ledger deltas.

`status_changes` is an ordered array of `{target_kind, target_id, status_name, before, after}` and is empty when no derived status was added, removed, or changed. Creation and removal use `null` as the absent side. It must contain exactly the same keyed changes as `deltas.value_changes.derived_statuses`.

`deltas` is exhaustive. It contains an entry for every canonical state collection under `entity_changes`: `catalog_accounts`, `catalog_categories`, `transactions`, `transaction_versions`, `posting_sets`, `postings`, `sources`, `candidates`, `confirmations`, `evidence`, `evidence_links`, `relations`, `domain_entities`, `audit_links`, and `posting_reconciliations`. Every entry has `added_ids`, `changed_ids`, and `removed_ids`, including empty arrays. Golden v2 normally has no removals because history is append-only.

`deltas.value_changes` contains exactly three set-like arrays: `balances`, `reports`, and `derived_statuses`. Empty arrays are explicit. Their closed item shapes are:

- Balance change: `{key:{account_id,currency}, before, after}`, where each value is a decimal or `null`.
- Report change: `{key:{period_type,period,metric,currency?}, before, after}`, where each value is the complete applicable or inapplicable metric value without the repeated `metric` key, or `null`.
- Derived-status change: `{key:{kind,target_id,status_name}, before, after}`, where each value is the complete status value or `null`.

There is no implicit map projection and no JSON Pointer interpretation. Arrays are sorted by the displayed compound key. Python semantic validation must index the complete baseline and result arrays by those keys, recompute all additions, removals, and changed values, and require exact equality with `value_changes`. Every actual `status_changes` item must have an equal derived-status value change, and every changed status must appear in both places.

`returned_ids` is a set-like array of `{kind, id}`. It is explicit even when empty.

## Reconciliation and derived state

Financial reconciliation belongs only to an eligible posting on an owned real asset or liability account. `posting_reconciliations` references one exact `posting_id` and has canonical status `pending`, `matched`, or `has_difference`. A posting that is not eligible has no reconciliation record; `not_applicable` is a derived display value, not a stored reconciliation fact.

Transaction, relation, group, target-observation, payment-progress, fulfillment, refund, lending, and adjustment summaries belong in `derived_statuses`. They are recomputed from canonical postings, evidence links, and domain entities. They cannot be an independent balance-changing fact. Reconciliation changes never alter posting amounts, balances, transaction effectiveness, or reports.

## Reports

`reports` is a set-like array of period records. Each record has `period_type` (`day`, `month`, or `cumulative`), `period`, and set-like `metrics`.

An applicable metric is:

```json
{"metric":"income","applicability":"applicable","currency":"CNY","amount":"0.00"}
```

An inapplicable metric is:

```json
{"metric":"budget","applicability":"not_applicable"}
```

`not_applicable` forbids `currency` and `amount`. `applicable` requires both, including explicit zero. Zero therefore means the metric applies and evaluates to zero; it never means “not applicable.” Cash inflow and outflow are non-negative magnitudes. Signed metrics include consumption, income corrections, net-worth change, principal external cash flow, and balance-adjustment net-worth change as defined by their rule.

Reports are derived from current transaction versions in the complete target state. They are not accumulated by walking alternate operation branches.

## Scalar and canonical form

### Decimal and currency

Amounts are JSON strings in canonical fixed precision. For precision `p`, the form is `0` or a non-zero integer part followed by exactly `p` fractional digits, with an optional leading `-`; leading `+`, exponent notation, leading zeroes, `.5`, `1.`, and negative zero are invalid. For CNY precision 2, examples are `0.00`, `35.80`, and `-20.00`.

Python semantic validation verifies that every amount uses the declared currency precision and that arithmetic uses exact decimal or integer minor units.

### Time

Timestamps use RFC 3339 date-time strings with seconds and an explicit `Z` or numeric offset. `-00:00`, local times without an offset, and leap-second text are rejected. The numeric offset must be valid for `case.timezone` at that instant. Fractional-second precision, when present, is preserved.

An absent time property means the source did not provide that time. Timestamp properties may not be `null`, copied from another semantic time, derived from file order, or filled with migration/runtime current time. Required economic times must instead cause preserve/map/derive/reject classification to reject an unresolvable record. Representative sample times are included only where the governing fixture or sample input explicitly defines their semantics.

### IDs and canonicalization

Existing valid stable IDs are preserved. A missing ID is generated deterministically with UUIDv5 using namespace `cfad3f84-edb1-5838-ae53-aae49684cf1a`. The UUID name is the UTF-8 string `case-id + "\n" + root-id + "\n" + entity-kind + "\n" + semantic-key`. For migration, `semantic-key` is the approved source JSON Pointer plus any approved occurrence discriminator; for authored fixtures it is an explicit stable semantic key. Traversal order, display names, current time, machine paths, and private data are forbidden inputs. A collision between different canonical identity inputs is a hard rejection.

JSON input follows RFC 8259 and duplicate object member names are rejected. Before hashing or byte comparison, set-like arrays are sorted by their declared key, then the document is serialized using RFC 8785 JSON Canonicalization Scheme without Unicode normalization. Fingerprints are lowercase `sha256:<hex>` over canonical UTF-8 bytes.

## Array semantics and stable order

Arrays are set-like unless listed as ordered below. Set-like arrays reject duplicate stable keys, and their source order has no semantics. Canonical comparison and emitted canonical output sort them in ascending Unicode code-point order of the key.

| Array | Semantics and key |
| --- | --- |
| `roots`, `states`, all entity collections | Set-like by `id`. |
| `currencies` | Set-like by `code`. |
| `balances` | Set-like by `(account_id, currency)`. |
| `reports` | Set-like by `(period_type, period)`. |
| `metrics` | Set-like by `(metric, currency-or-empty)`. |
| `returned_ids` | Set-like by `(kind, id)`. |
| Delta ID arrays | Set-like by ID. |
| `operations` | Ordered by `(root_id, sequence)`; sequence is semantic. |
| Root `operation_ids` | Ordered execution path and must match operation sequence. |
| Entity status/history arrays | Ordered by explicit integer `sequence`, then ID as tie-break. |

Posting order is not accounting semantics; `posting_ids` and `postings` are set-like by ID. Allocation consumption, lot consumption, and otherwise same-time events require an explicit `sequence`; ties use stable ID. No implementation may rely on source object member order or incidental traversal order.

## Structural and semantic validation boundary

JSON Schema Draft 2020-12 is responsible for envelope shape, required members, primitive types, enums, conditional field presence, decimal/time/ID lexical patterns, array item shape, and closed objects where specified.

Python validation is responsible for cross-reference existence and target kind, root isolation, stable-ID uniqueness, deterministic ID checks, catalog ownership, complete balance membership, currency precision, per-currency posting-set balance, transaction/version/posting-set ownership, append-only history, current-version replay, exact balances, reports, posting reconciliation eligibility, derived business status, operation delta recomputation, idempotency, canonical fingerprints, and migration mapping completeness.

Neither layer may auto-correct an invalid fixture. Validation failure reports the path and rule and leaves the input unchanged.

## Migration and implementation gates

The gates are independent and sequential:

1. **Contract and representative examples:** this document and the RG-01/RG-09 examples receive independent specification and quality review, then explicit final approval. Until then, this draft authorizes no implementation.
2. **Schema and validator:** only final approval of gate 1 authorizes a JSON Schema 2020-12 and semantic validator prototype. Passing this gate does not authorize adapters or fixture rewrites.
3. **Adapter:** every RG receives an approved normalized JSON-path inventory and a path-by-path `preserve`, `map`, `derive`, or `reject` decision. The unclassified path count must be zero. Alias and replacement decisions must be explicit. Only then may that RG's adapter be implemented.
4. **Fixture migration:** each RG receives an independently reviewed expected v2 output and semantic-equivalence decision. Migration must be deterministic, resumable, case-isolated, atomic on publication, and auditable by pre/post object counts and content hashes. Only explicit approval opens fixture rewrite or publication.

Migration never mutates v1 input in place. Failure cannot expose a partial v2 case as successful. Recovery cannot mix old and new outputs. Representative examples in this directory are not migration baselines, approved release artifacts, or expected outputs for any gate after gate 1.
