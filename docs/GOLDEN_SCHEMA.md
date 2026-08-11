# Golden Schema v2 Data Contract

## Status and authority

This document is the approved Golden Schema v2 data contract. The JSON Schema and semantic validator implement this contract, but this document does not authorize an adapter, a frozen-fixture rewrite, or publication of migrated output. The examples in `docs/examples/golden-schema-v2/` are anonymous representative drafts and are not migrated expected outputs.

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

`case` requires `id`, `level`, `rule_version`, `approval_status`, `ledger_id`, `timezone`, and `currencies`. `approval_status` is `draft_for_review` or `approved`; the representative examples remain `draft_for_review`. `approved` means only that the expected output received independent review and explicit user approval. It does not replace `rule_version`, does not mean the governing business rule is newly frozen, and does not authorize an adapter or fixture rewrite. `currencies` is a set-like array of `{code, precision}` records. Currency code and precision determine every formal amount in the case.

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

The scalar aliases used below are: `id` = non-empty stable ID string; `decimal` = canonical fixed-precision string; `currency` = a declared currency code; `timestamp` = the strict timestamp defined later; `ref` = closed `{kind, id}` where `kind` is one of `operation`, `transaction`, `transaction_version`, `posting_set`, `posting`, `source`, `candidate`, `confirmation`, `evidence`, `evidence_link`, `relation`, `domain_entity`, `audit_link`, `observation`, `component`, `counterparty`, or `name_history`.

### Envelope and state shapes

| Object | Required fields | Optional fields and enums | References |
| --- | --- | --- | --- |
| envelope | `contract:string`, `contract_version:string`, `case:object`, `roots:array`, `states:array`, `operations:array` | none | roots, states, and operations are internally linked |
| case | `id:id`, `level`, `rule_version:integer >= 1`, `approval_status`, `ledger_id:id`, `timezone:string`, `currencies:array` | `level`: `core_required`, `core_reserved`, `future_draft`; `approval_status`: `draft_for_review`, `approved` | none |
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
| `postings` | `id`, `posting_set_id`, `account_id`, `amount`, `currency`, `reconciliation_eligible:boolean` | optional registered `role`, optional `category_id` | posting set and account; `category_id` is owned by the categorized posting and resolves to a same-state catalog category |

`posting.category_id` is optional in the JSON shape but has closed semantic ownership. When present, it must resolve to an existing second-level category whose `posting_account_id` equals the posting's `account_id`; this generic ownership rule applies to matching expense or income category accounts. For an RG-04 mixed expense, the `expense` posting must contain the confirmed secondary expense category ID, while the two `mixed_expense_asset_funding` and `mixed_expense_credit_funding` postings must not contain `category_id`; they are owned by their real funding accounts. Category activity is required when creating or confirming new formal effects, but historical postings remain valid after their category is deactivated. A formal state validator enforces historical reference and posting-account ownership independently of operation-level input and result checks.

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
| `prepaid_purchase` | Actual payment reduces an eligible real asset and increases an owned non-real hidden prepaid asset; it is cash outflow only. |
| `prepaid_recognition` | A scheduled installment decreases that prepaid asset and increases its exact categorized expense; it has no real-account or cash-flow leg. |

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
| `prepaid_asset` | The owned non-real hidden prepaid-asset leg used only by the periodic allocation transaction types. |

An absent posting role is permitted only for `opening_balance`. Any new transaction type or posting role requires a contract amendment before Schema or adapter work.

### Provenance and business collection shapes

| Collection | Required common fields | Optional common fields | References |
| --- | --- | --- | --- |
| `sources` | `id`, `type`, `payload` | none | payload-defined references |
| `candidates` | `id`, `type`, `source_ids:id[]`, `confidence:decimal`, `payload`, `status_history:array` | none | sources; each history item is closed `{id, sequence:integer >= 1, status}` with status `pending_confirmation`, `confirmed`, `rejected`, or `incomplete` |
| `confirmations` | `id`, `type`, `operation_id`, `subject:ref`, `payload` | optional `confirmed_at` only when provided | operation is always required and must be the operation that created this confirmation |
| `evidence` | `id`, `type`, `source_ids:id[]`, `payload` | none | sources |
| `evidence_links` | `id`, `evidence_id`, `target_kind`, `target_id`, `role` | `target_kind`: `posting`, `observation`, `relation`, `domain_entity` | exact typed target |
| `relations` | `id`, `type`, `member_refs:ref[]`, `payload` | none | same-root members; subtype cardinality is closed below; `staged_payment` and a pre-receipt `refund` may temporarily have one member, while every other relation requires two or more members |
| `domain_entities` | `id`, `type`, `payload` | none | payload-defined references |
| `audit_links` | `id`, `type`, `from:ref`, `to:ref`, `payload` | none | exact same-root endpoints |

Representative subtype payloads are fully frozen:

| Collection/type | Closed payload required fields |
| --- | --- |
| source `explicit_balance_observation` | `account_id`, `target_amount`, `currency`, `target_observed_at` |
| candidate `balance_adjustment` | `account_id`, `replayed_amount`, `target_amount`, `delta`, `currency`, `effective_at` |
| source `account_transfer`, complete | `source_account_id`, `destination_account_id`, `source_debit_amount`, `destination_credit_amount`, `fee_amount`, `currency`, `completeness:"complete"`, `observed_at`, `evidence_id` |
| source `account_transfer`, missing destination | `source_account_id`, `debit_amount`, `currency`, `completeness:"missing_destination"`, `observed_at`, `evidence_id`; destination fields are absent |
| source `account_credit_observation` | `account_id`, `credit_amount`, `currency`, `observed_at`, `evidence_id`; this subtype is mirror evidence only and cannot contain transfer-source or formal-ledger fields |
| candidate `account_transfer`, complete | `source_ids` contains exactly one complete transfer source; payload contains the exact source and destination accounts, debit, credit, fee, currency, one `evidence_refs` transfer-record identity, `provenance:{rule:"complete_transfer_source",rule_version:1}`, and `requires_confirmation:["formal_transaction_creation"]` |
| candidate `account_transfer`, missing destination | `source_ids` contains exactly one incomplete transfer source; payload contains only the exact source account, debit, currency, one `evidence_refs` transfer-record identity, the same deterministic provenance, and exactly `destination_account_id` plus `formal_transaction_creation` in `requires_confirmation`; destination account and split amounts are absent |
| source `merged_payment_bank_fact` | `evidence_id`, immutable `observed_at`, `details`, signed `amount`, `currency`, and `completeness:"complete"` |
| source `merged_payment_item_fact` | `item_id`, `evidence_id`, `evidence_kind` (`item_receipt` or `item_summary`), immutable `observed_at`, `details`, positive `amount`, `currency`, `suggested_category_id`, and completeness |
| source `staged_payment_bank_fact` | signed `amount`, `currency`, and exactly one of `observed_at` or `source_payment_at`; a manual bank-evidence fact requires `observed_at`, an imported payment fact requires `source_payment_at`, and the other time field is forbidden; optional `mirror_of_source_id` is present only for an imported mirror and identifies exactly one earlier `staged_payment_bank_fact` source with the same currency, equal absolute magnitude, and opposite sign whose owning evidence resolves to the same payment; every original or mirror fact has that payment's currency and an absolute amount equal to its positive installment amount and the absolute amount of its negative `payment_asset` posting |
| source `bank_debit`, `merchant_refund_notice`, `wallet_credit`, `combined_refund_statement`, `wallet_credit_mirror` | common closed RG-07 payload preserves `source_record_id`, one owned `evidence_id`, immutable `observed_at`, subtype `kind`, and `immutable_payload_hash`; applicable variants preserve signed `amount`, `currency`, `account_id`, `reported_state`, `proves_arrival`, `processor_reported_at`, `source_observed_at`, `booking_at`, `value_at`, or `original_source_payload_hash`; only `wallet_credit_mirror` requires `mirror_of_source_id`, which identifies one earlier non-mirror wallet-credit source with equal positive amount and currency |
| candidate `staged_payment` | common closed shape `id,type,source_ids,confidence,payload,status_history`; exactly one `staged_payment_bank_fact` source; known-role payload has `payment_role` (`deposit` or `final`), positive `amount`, `currency`, `source_payment_at`, one `evidence_ref`, `provenance:{rule:"staged_payment_bank_fact",rule_version:1}`, and exactly `relation_id`, `payment_role`, `category_id`, and `funding_account_id` in `requires_confirmation`, with confidence `1.00` and no `guessed_payment_role`; ambiguous payload has the same fields but requires both `payment_role:null` and `guessed_payment_role:null`, with confidence `0.50`; history is exactly pending only or pending then confirmed |
| candidate `refund_credit` | exactly one `wallet_credit` source; positive `proposed_amount`, source currency, nullable proposed original transaction and category, proposed owned-real-asset destination and arrival time, exact `requires_confirmation`, `rule_version:1`, and `original_source_payload_hash`; pending requires exactly all four tokens `original_transaction_id`, `category_id_and_allocation`, `destination_account_id`, and `arrival`, while confirmed requires `[]`; history is closed `{id,sequence,status,occurred_at,formal_effect_count}`, exactly pending only or pending then confirmed, with formal effect count zero then one |
| candidate `merged_payment` | exactly three `source_ids` (one bank and two item sources), fixed `confidence:"1.00"`, exact payment total/currency and source/evidence references, two source-equal item proposals, `provenance:{rule:"merged_payment_facts",rule_version:1}`, and the exact four confirmation requirements; history is pending only or pending then confirmed |
| confirmation `explicit_manual_save` | empty payload; subject kind `operation` |
| confirmation `candidate_confirmation` | empty payload; subject kind `candidate`; optional `confirmed_at` only when the legacy or native fact records the actual time; RG-05 candidate confirmation never synthesizes it from a source or payment timestamp |
| confirmation `explicit_operation_confirmation` | empty payload; subject kind `operation`; optional `confirmed_at` only when the legacy or native fact records the actual time; RG-05 manual save never synthesizes it |
| confirmation `refund_relationship_confirmation` | subject kind `relation`; payload is exactly `{original_transaction_id}` and identifies the same original expense member owned by that refund relation; `confirmed_at` is the actual explicit confirmation time |
| evidence `user_balance_observation` | `observed_at` |
| evidence `item_receipt` | `observed_at` |
| evidence `bank_payment` | `observed_at`; exactly one `merged_payment_bank_fact` source |
| evidence `staged_payment_bank_payment` | `payment_id` plus its exactly one `staged_payment_bank_fact` source's same required time field (`observed_at` or `source_payment_at`) with byte-for-byte equal timestamp text; the other time field is forbidden; the sole source's currency and absolute amount match the referenced installment and `payment_asset` posting; optional `mirror_of_evidence_id` and `merged_into_evidence_link_id` are present together only for a mirror and identify the earlier staged-payment evidence and its existing exact posting link |
| evidence `asset_debit`, `refund_notice`, `asset_credit`, `combined_refund_statement`, `asset_credit_mirror` | exactly one matching RG-07 source and immutable `observed_at` byte-equal to that source; only `asset_credit_mirror` additionally requires both `mirror_of_evidence_id` and `merged_into_evidence_link_id`, owned inside the evidence payload and pointing to the earlier asset-credit evidence and its existing exact destination-posting link |
| evidence `item_summary` | `observed_at`; exactly one matching `merged_payment_item_fact` source; this subtype cannot create `item_allocation_fact` |
| evidence `merchant_stored_value_credit` | `observed_at` |
| evidence `transfer_record` | `observed_at`; `source_ids` contains exactly one `account_transfer` source whose `evidence_id` and `observed_at` are identical |
| domain entity `target_balance_observation` | `account_id`, `target_amount`, `currency`, `observed_at`, `source_id` |
| domain entity `balance_adjustment` | `observation_id`, `original_delta`, `currency`, `transaction_id` |
| domain entity `explanation_allocation` | `adjustment_id`, `explanation_transaction_id`, `reversal_transaction_id`, `amount`, `currency`, `confirmed_at` |
| relation `merged_payment` | exactly four members: one current expense transaction, its unique owned real `payment_asset` posting, and two distinct `item_allocation` entities; payload is `system_managed:true`, `display_name:"合并付款"`, `generic_order_lifecycle:false`, positive `payment_total`, and `currency` |
| relation `staged_payment` | exactly one `staged_payment_lifecycle` domain entity and zero to two distinct `installment_payment` domain entities; payload is exactly `{}` |
| relation `refund` | exactly one current original `expense` transaction and zero or one distinct `refund_receipt` transaction; payload is exactly `{}`; every relation owns exactly one same-state `refund_relationship` domain entity through that entity's `relation_id` |
| domain entity `staged_payment_lifecycle` | `total_amount`, `paid_amount`, `due_amount`, `currency`, `category_id`, `display_name`, `system_managed:true`, `generic_order_lifecycle:false`, and ordered append-only `state_history`; each closed history item is `{id,sequence,event,occurred_at,total_amount,paid_amount,due_amount,payment_id,payment_progress,fulfillment_status,state_transition_effect_count}`; event is exactly `group_created`, `payment_confirmed`, `fulfillment_changed`, or `completion_confirmed`; only `payment_confirmed` requires a non-null installment `payment_id`, all other events require `payment_id:null`; payment progress is `unpaid`, `partially_paid`, or `paid_in_full`, fulfillment is `in_progress` or `fulfilled`, and `state_transition_effect_count` is zero |
| domain entity `installment_payment` | `role` (`deposit` or `final`), positive `amount`, `currency`, `funding_account_id`, `transaction_id`, `expense_posting_id`, `asset_posting_id`, `actual_payment_at`, and `statistics_at`; optional immutable `source_payment_at` is present only when a source supplied it |
| domain entity `refund_relationship` | `relation_id`, `original_transaction_id`, nullable `refund_transaction_id`, `category_id`, positive `requested_amount`, non-negative `received_amount`, `currency`, nullable `destination_account_id`, closed optional-time object `times`, and append-only `state_history`; each history item is `{id,sequence,state,occurred_at,transaction_id,formal_effect_count}`; optional frozen policy/cap fields are `allow_guessed_category`, `allow_refund_category_override`, `inheritance`, `misclassified_original_action`, `multi_category_allocation`, `original_category_id`, `refund_category_id`, `refund_expense_account_id`, `active_linked_refunds`, `original_confirmed_refundable_expense`, and `remaining_refundable` |
| domain entity `consumption_record` | `expense_posting_id`, `category_id`, `amount`, `currency`, `statistics_at`; optional `details`, immutable `source_observed_at`, `source_item_id`, `source_id`, and `evidence_id`, with imported source bindings all-or-none |
| domain entity `reconciliation_match` | `posting_id`, `evidence_id`, and append-only `status_history`; history starts with `matched` and may append one `invalidated` event with stable `id`, continuous `sequence`, timestamp `at`, and `reason` |
| domain entity `item_allocation` | `consumption_record_id`, `expense_posting_id`, `category_id`, `amount`, `currency`; optional all-or-none `source_item_id`, `source_id`, and `evidence_id` for imported merged payments |
| domain entity `stored_value_lot` | `recharge_transaction_id`, `loaded_at`, `face_value`, `currency` |
| domain entity `periodic_allocation_schedule` | `payment_transaction_id`, `prepaid_account_id`, `category_id`, `total_amount`, `currency`, `cadence:"monthly"`, `start_at`, and exactly one `anchor` (`month_end` or `day_of_month` 1..28) |
| domain entity `periodic_allocation_revision` | `schedule_id`, continuous `revision_number`, `recognized_through`, `remaining_amount`, `currency`, and exact `installment_ids` |
| domain entity `periodic_allocation_installment` | `schedule_id`, `revision_id`, unique sequence, `scheduled_at`, positive `amount`, and `currency`; recognition state is derived, not payload data |
| relation `counterparty_lending_relationship` | one or more `lending_position` domain-entity members for exactly one stable `counterparty_id` |
| domain entity `lending_position` | stable `counterparty_id`, `position_scope:"person_level_net_position"`, `contract_allocation_enabled:false`, non-real `receivable_account_id`, non-negative current `principal_balance`, `currency`, and append-only history of signed lend/collect principal events whose running total never crosses below zero |
| domain entity `lending_settlement` | `behavior_code:"collect"`, stable counterparty/position/transaction/destination/interest-category references, `allocated_lend_transaction_id:null`, positive `total_received`, `currency`, actual receipt and confirmation times, exactly principal/interest/fee components, and one confirmed history event; fee is exactly `0.00` and has no posting |
| audit link `adjustment_transaction`, `explanation_transaction`, `allocation_reversal` | empty payload; endpoint kinds must match the role semantics |
| audit link `periodic_allocation_recognition` | empty payload from one periodic-allocation installment to its exact `prepaid_recognition` transaction |
| audit links `mirror_of_evidence_id`, `merged_into_evidence_link_id` | empty payload between two distinct evidence endpoints or two distinct evidence-link endpoints respectively; they are never evidence-link payload fields |
| audit link `posting_replacement` | from old posting to replacement posting; closed payload `reconciliation_effect` is `preserved`, `invalidated`, or `not_applicable` |

The remaining approved type registry reserves these semantics without claiming their full RG payloads are frozen:

- Relation types still reserved without a closed payload include `mixed_payment`; `staged_payment`, `merged_payment`, and `refund` are registered closed subtypes that relate exact existing identities without creating an economic event.
- Domain entity types still reserved without a closed payload are `funding_component`, `counterparty`, `settlement_component`, `lot_consumption`, `stored_value_consumption`, `activation_adjustment`, and `merchant_allocation`. The registered `lending_position`, `lending_settlement`, `consumption_record`, `item_allocation`, and `refund_relationship` include only their closed lifecycle fields above; other scenarios cannot infer additional fields from that closure.
- Evidence-link roles: `target_balance_observation`, `real_account_posting`, `payment_asset_posting`, `destination_asset_posting`, `funding_asset_posting`, `bank_payment_posting`, `refund_relationship`, `counterparty_lending_relationship`, `stored_value_activation_balance_fact`, `item_allocation_fact`, `stored_value_asset_posting`, and `stored_value_lot_fact`. Each may target only the exact fact named by the role.
- Confirmation types reserved for later payload registration are `stored_value_activation_balance_confirmation`, `stored_value_expiry_confirmation`, `stored_value_recharge_confirmation`, and `stored_value_spend_confirmation`. Registered lending event/settlement confirmations bind their creating operation, exact transaction subject, stable counterparty and confirmation request; a settlement confirmation also binds its exact settlement and optionally the explicitly confirmed candidate. Every confirmation requires `operation_id` and an exact subject.
- Audit link types currently registered are `adjustment_transaction`, `explanation_transaction`, `allocation_reversal`, `reconstruction_adjustment`, `reconstruction_transaction`, `periodic_allocation_recognition`, `mirror_of_evidence_id`, and `merged_into_evidence_link_id`.

Reserved-but-unregistered payloads cannot appear in a v2 document. A later independent contract amendment may register them without changing the representative examples or treating those examples as migration output.

For a `staged_payment` relation, `member_refs` remains set-like. At creation it has exactly one `staged_payment_lifecycle` domain-entity member; it may then append at most two distinct `installment_payment` domain-entity members, one per confirmed payment. Its closed payload is exactly `{}`. A relation contains at most one `deposit` member and at most one `final` member; a `final` member is invalid unless the relation also contains its required `deposit` member. Relation member array order is not chronological evidence. The lifecycle member is never replaced by a payment member, and a retry cannot append a duplicate member.

Every installment member references an existing same-root `expense` transaction and two distinct existing postings. Both referenced postings belong to that transaction's current posting set. The asset posting uses `funding_account_id`, role `payment_asset`, the installment currency, and the exact negative installment amount. The expense posting uses the lifecycle's second-level `category_id`, role `expense`, the same currency, and the exact positive installment amount. The transaction version's `occurred_at` equals `actual_payment_at`, and its `statistics_at` equals the installment's `statistics_at`.

`payload.state_history` is the authoritative lifecycle state. Its sequence is continuous from one, append-only, and ordered by the operations that created the history items. The current lifecycle satisfies `total_amount = paid_amount + due_amount`, and `paid_amount` equals the sum of its distinct installment amounts. Every history item satisfies the same total arithmetic, and its `paid_amount` equals the cumulative sum of installments named by its own and earlier `payment_confirmed` items. Current lifecycle totals and current payment/fulfillment projections equal the latest history item.

`derived_statuses` is the sole current-state projection for `payment_progress`, `fulfillment_status`, and staged-payment `reconciliation`. The lifecycle payload must not duplicate any of those current status values. Every complete state that owns a `staged_payment_lifecycle` has exactly one current derived status for each of `payment_progress`, `fulfillment_status`, and `reconciliation`. All three use `target_kind=domain_entity` and the exact lifecycle entity ID as `target_id`. Their `status_name` values are exactly those three names. The `payment_progress` value registry is exactly `unpaid`, `partially_paid`, and `paid_in_full`; its value is `unpaid` exactly when `paid_amount` is zero, `partially_paid` exactly when both `paid_amount` and `due_amount` are positive, and `paid_in_full` exactly when `due_amount` is zero. The `fulfillment_status` registry is exactly `in_progress` and `fulfilled`; the `reconciliation` registry is exactly `pending`, `partial`, and `complete`.

Payment progress and fulfillment are projected from the latest history item. Reconciliation is independently projected from only the eligible `payment_asset` posting reconciliations owned by the relation's installment members. Let `N` be the number of distinct `installment_payment` members in the relation, and let `M` be the number of those members whose exact `asset_posting_id` resolves to an eligible `payment_asset` posting with a `posting_reconciliations` record whose status is `matched`. The reconciliation projection is `pending` exactly when `N = 0` or `M = 0`, `partial` exactly when `0 < M < N`, and `complete` exactly when `N > 0` and `M = N`. A missing reconciliation record, an ineligible installment asset posting, or any status other than `matched` contributes zero to `M`. Posting reconciliations for postings not named by the relation's installment members are ignored. Presentation labels are derived from those projections and are not lifecycle payload fields.

Staged-payment chronology never derives from relation member order. The operation that creates or confirms the deposit precedes the final-payment operation; the deposit's `payment_confirmed` history item has a lower sequence and earlier `occurred_at` than the final's item. A final installment's `actual_payment_at` must be strictly later than the deposit's `actual_payment_at`. When both installments have `source_payment_at`, the final source time must also be strictly later.

The existing RG-05 `bank_payment` evidence and its exact `merged_payment_bank_fact` source ownership are unchanged; neither RG-06 discriminator is an alias for either RG-05 discriminator. `staged_payment_bank_fact` and `staged_payment_bank_payment` are distinct closed RG-06 source and evidence types. Manual RG-06 bank evidence owns immutable `observed_at`; imported RG-06 payment evidence, including its mirror, owns immutable `source_payment_at`. Neither variant permits both time fields, a missing required time, or a `null` time. Evidence-side `observed_at` and `source_payment_at` are each required only for their selected variant, and neither may be missing or `null`. The evidence payload uses the source variant's same required time field with the exact same timestamp text. `staged_payment_bank_fact` and `staged_payment_bank_payment` forbid `created_at` and `confirmed_at` fields entirely. Neither source time generates either field. For mirror evidence, source lineage stays in `mirror_of_source_id`, while evidence/payment/link lineage stays in the staged-payment evidence payload; none of those values is copied onto the evidence link.

An RG-06 staged-payment bank evidence link contains only `id`, `evidence_id`, `target_kind`, `target_id`, and `role`. Its `target_kind` is `posting`, its `target_id` is the exact owned-real `payment_asset` posting of the referenced `installment_payment`, and its role is `payment_asset_posting`. The link cannot target the expense posting, transaction, relation, lifecycle entity, or payment entity. The evidence payload's `payment_id` must resolve to the installment whose `asset_posting_id` is that target; a mirror's `merged_into_evidence_link_id` must resolve to the same existing target link.

The staged-payment evidence's sole source currency equals the referenced installment and `payment_asset` posting currency, and the source's absolute amount equals both the positive installment amount and the absolute posting amount. This binding applies independently to original and mirror evidence; mirror-to-original equality does not replace it.

For an RG-07 `refund` relation, `member_refs` is set-like and contains exactly one current original `expense` transaction plus zero or one `refund_receipt`. The pre-receipt form contains only the original expense. Receipt confirmation appends the one independent refund transaction; it never changes the original transaction, version, posting set, postings, time, category, or reconciliation. A relation payload is always exactly `{}`. Exactly one `refund_relationship` domain entity names the relation through `payload.relation_id`; no other entity may claim that relation.

The refund relationship's original transaction and optional refund transaction equal the relation members. Its category is the exact second-level expense category owned by the original current categorized expense posting. `requested_amount` is positive; `received_amount` is non-negative and is zero before receipt. A received relationship has one positive received amount, a non-null destination account and refund transaction, and a latest `received` history item; a non-received relationship has no refund transaction and zero received amount. All active received relationships for one original expense use the same currency and category, and their received amounts sum to no more than that original categorized expense amount. When the optional cap fields are present, `active_linked_refunds`, `original_confirmed_refundable_expense`, and `remaining_refundable` must exactly equal that recomputation and satisfy `original = active + remaining`.

Refund history is authoritative, append-only, continuously sequenced from one, and uses only `requested`, `approved`, `processing`, and `received`. A manual lifecycle may progress through that order; an imported relationship may begin at `received` after explicit confirmation. A non-received item has `transaction_id:null` and `formal_effect_count:0`. Only `received` has the exact refund transaction ID and `formal_effect_count:1`. The current lifecycle value exists only as the derived status `{target_kind:"domain_entity", target_id:<refund relationship id>, status_name:"refund_status", value:<latest history state>}`; the payload does not duplicate a `state` field.

The closed `times` object preserves only times actually present. Its possible members are `requested_at`, `approved_at`, `processor_reported_at`, `source_observed_at`, `booking_at`, `value_at`, `confirmed_at`, and `arrived_at`; every present member is a strict timestamp and `null` is forbidden. A `received` relationship requires both actual `confirmed_at` and `arrived_at`. The refund version's `occurred_at`, `statistics_at`, and `effective_at` equal `arrived_at`, while optional `created_at` equals the actual `confirmed_at`. No request, approval, processor, observation, booking, or value time can replace either role.

The current `refund_receipt` posting set contains exactly two postings in the relationship currency. One positive `destination_asset` posting targets the relationship's known, user-owned, real asset destination and is reconciliation eligible. One negative `expense` posting has the exact received magnitude, the inherited category ID and category posting account, and is not reconciliation eligible. Those postings balance exactly. The original categorized expense remains positive and unchanged. The sum of linked receipt amounts enforces the cumulative cap; no operation may partially accept an over-cap attempted allocation.

RG-07 provenance remains split by owner. Every RG-07 source owns its source-record identity, immutable source times, source hash, amount/account facts, and source lineage. Every RG-07 evidence object references exactly one matching source and owns its evidence observation time. An `asset_credit_mirror` evidence additionally owns `mirror_of_evidence_id` and `merged_into_evidence_link_id`; its link itself still contains only the five generic evidence-link fields. Merchant `refund_notice` evidence may create only `role=refund_relationship` to the exact `refund` relation. `asset_credit` and `asset_credit_mirror` may create only `role=destination_asset_posting` to the exact positive refund destination posting. A `combined_refund_statement` may create those two independent typed links. Original `asset_debit` evidence may create only `role=payment_asset_posting` to the original expense's exact payment-asset posting. Posting-match state remains solely in `posting_reconciliations`.

An imported `refund_credit` candidate references exactly one wallet-credit source. Its pending form keeps the proposed source facts, has exactly all four confirmation requirements, and has no formal ledger, relation, report, or reconciliation effect. Explicit confirmation appends only the confirmed candidate history item, clears `requires_confirmation`, creates the independent refund result, and adds exactly one `refund_relationship_confirmation`. That confirmation has `subject.kind=relation`, names the new refund relation, and its payload's `original_transaction_id` equals the relation's original expense. It is not an operation-subject `refund_receipt_confirmation` alias.

Transfer-source and account-transfer-candidate records are provenance only. They cannot contain formal transaction, posting, balance, report, reconciliation, target, match-status, or evidence-link fields. Every transfer source `evidence_id` resolves to exactly one same-state `transfer_record` evidence object; that evidence must reference exactly that source and carry the identical `observed_at`. A transfer-record evidence object owns only its source identity and observation time; typed targets and matching status remain owned by `evidence_links` and `posting_reconciliations`. An account-transfer candidate status history is exactly `[pending_confirmation]` or `[pending_confirmation, confirmed]`. The confirmed form requires exactly one existing `candidate_confirmation` whose subject is that candidate; it does not create a second confirmation owner. No other status, repetition, or later append is valid. Formal posting creation still requires the separately registered confirmation operation. This contract retains one source, one evidence identity, two distinct owned real financial accounts, and one currency; combination transfers and multi-currency payloads remain `future_draft`.

### Projection shapes

| Collection | Closed shape |
| --- | --- |
| `posting_reconciliations` | `{id, posting_id, status}`; status is `pending`, `matched`, or `has_difference` |
| `balances` | `{account_id, currency, amount}` |
| `reports` | `{period_type, period, metrics}`; period type is `day`, `month`, or `cumulative` |
| report metric, applicable | `{metric, applicability:"applicable", currency, amount}` |
| report metric, inapplicable | `{metric, applicability:"not_applicable"}` |
| `derived_statuses` | `{id, target_kind, target_id, status_name, value}`; target kind is a `ref.kind`; value is a registered status string |

The RG-09 verification-status registry used here is `balanced_with_unexplained_adjustment`, `difference_pending_explanation_confirmation`, `evidence_incomplete`, and `fully_reconciled`. Explanation status is `open`, `partially_explained`, or `fully_explained`. Transaction reconciliation summary is `pending`, `partial`, `matched`, or `has_difference` and remains derived. Periodic allocation derives `allocation_status`: schedules are `active` or `recognized`; installments are `pending`, `recognized`, or `superseded`. The highest revision owns future installments and typed recognition links own confirmed facts; no payload persists this status.

### Action registry

For `accepted` and `no_change`, operation `input` is closed per `action_type` and `attempted_input` is forbidden. These are the strict action inputs registered by this contract:

| Action type | Operation class | Required input fields |
| --- | --- | --- |
| `manual_expense` | `creation` | `request_id`, `amount`, `currency`, `category_id`, `payment_account_id`, `occurred_at`, `note`, `explicit_confirmation:true` |
| `transaction_note_update` | `update` | `request_id`, `transaction_id`, `note`, `explicit_confirmation:true` |
| `preview_target_balance` | `read` | `request_id`, `account_id`, `target_amount`, `currency`, `target_observed_at`, `explicit_confirmation:false` |
| `confirm_balance_adjustment` | `adjustment` | `request_id`, `candidate_id`, `account_id`, `target_amount`, `replayed_amount`, `delta`, `currency`, `effective_at`, `explicit_confirmation:true`, `confirmed_at` |
| `confirm_real_transfer` | `creation` | `request_id`, `target_account_id`, `counter_account_id`, `amount`, `currency`, `actual_occurred_at`, `discovered_at`, `explicit_confirmation:true`, `confirmed_at` |
| `confirm_explanation_allocation` | `reversal` | `request_id`, `adjustment_id`, `transaction_id`, `target_account_id`, `actual_occurred_at`, `real_transaction_amount`, `currency`, `target_observed_at`, `allocation_direction`, `explanation_amount`, all seven `confirms_*:true` fields shown in the representative sample, `explicit_confirmation:true`, `confirmed_at` |
| `manual_account_transfer` | `creation` | `request_id`, `source_account_id`, `destination_account_id`, `source_debit_amount`, `destination_credit_amount`, `fee_amount`, `currency`, `fee_category_id`, `occurred_at`, `explicit_confirmation:true` |
| `import_source_record` | `creation` | `request_id`, `source_id`, `evidence_id`, `source_account_id`, `destination_account_id`, `source_debit_amount`, `destination_credit_amount`, `fee_amount`, `currency`, `observed_at` |
| `confirm_account_transfer_candidate` | `creation` | `request_id`, `candidate_id`, `source_account_id`, `destination_account_id`, `source_debit_amount`, `destination_credit_amount`, `fee_amount`, `currency`, `occurred_at`, `explicit_confirmation:true` |
| `import_mirror_record` | `reconciliation` | `request_id`, `source_id`, `evidence_id`, `transaction_id`, `candidate_id`, `account_id`, `credit_amount`, `currency`, `observed_at` |
| `import_incomplete_source` | `creation` | `request_id`, `source_id`, `evidence_id`, `source_account_id`, `debit_amount`, `currency`, `observed_at` |
| `manual_mixed_expense` | `creation` | `request_id`, `asset_account_id`, `liability_account_id`, `asset_funding_amount`, `liability_funding_amount`, `total_amount`, `currency`, `category_id`, `occurred_at`, `settlement_explanation`, `explicit_confirmation:true` |
| `credit_principal_repayment` | `creation` | `request_id`, `asset_account_id`, `liability_account_id`, `principal_amount`, `currency`, `occurred_at`, `explicit_confirmation:true` |
| `ingest_mixed_payment_source` | `creation` | `request_id`, `source_record` (`complete` source with two funding components or `missing_funding_leg` source with one known asset amount) |
| `confirm_mixed_payment_candidate` | `creation` | `request_id`, `candidate_id`, `category_id`, `confirmed_funding_components` (exactly one owned real asset and one owned real liability), `explicit_confirmation:true` |
| `merge_mixed_payment_mirror_evidence` | `reconciliation` | `request_id`, `source_record_id`, `evidence_id`, `transaction_id`, `candidate_id`, `account_id`, `amount`, `currency`, `observed_at` |
| `manual_merged_payment` | `creation` | `request_id`, positive `total_amount`, `currency`, one owned real asset `funding_account_id`, `payment_at`, exactly two items (`item_id`, positive `amount`, `currency`, secondary expense `category_id`, `details`, immutable `source_observed_at`), `explicit_confirmation:true`, and optional exact `settlement_explanation` |
| `ingest_merged_payment_facts` | `creation` | `request_id`, one closed `bank_fact`, and exactly two closed `item_facts`; intake creates source/evidence/candidate state but no formal accounting effect |
| `confirm_merged_payment_candidate` | `creation` | `request_id`, `candidate_id`, one owned real asset `funding_account_id`, `payment_at`, `common_statistics_at`, exactly two categorized item allocations whose total equals payment, and `explicit_confirmation:true` |
| `merge_item_receipt_evidence` | `reconciliation` | `request_id`, `source_id`, `evidence_id`, exact `item_allocation_id`, immutable `observed_at`, `details`, matching positive `amount`, and `currency` |
| `create_periodic_allocation` | `creation` | `request_id`, `payment_account_id`, `prepaid_account_id`, `category_id`, positive `amount`, `currency`, actual payment `occurred_at`, allocation `start_at`, exact `anchor`, `cadence:"monthly"`, `installment_count >= 1`, `explicit_confirmation:true` |
| `recognize_periodic_allocation_installment` | `creation` | `request_id`, `schedule_id`, current pending `installment_id`, exact installment `amount`, `currency`, `explicit_confirmation:true` |
| `revise_periodic_allocation` | `update` | `request_id`, `schedule_id`, exact latest contiguous `recognized_through`, exact `remaining_amount`, `currency`, `remaining_installment_count >= 1`, `explicit_confirmation:true` |
| `correct_transaction_version` | `update` | closed union selected by `correction_kind`: `statistics_time` owns `statistics_at` and `explicit_confirmation:true`; `posting_facts` owns `corrected_at`, complete `replacement_postings`, and `explicit_confirmation:true` |
| `create_staged_payment` | `creation` | `request_id`, `kind:"staged_payment"`, positive `total_amount`, `currency`, `category_id`, and `created_at` |
| `record_staged_payment_installment` | `creation` | `request_id`, canonical `relation_id`, `payment_role` (`deposit` or `final`), positive `payment_amount`, `currency`, `funding_account_id`, and `actual_payment_at` |
| `change_staged_payment_fulfillment` | `status_transition` | `request_id`, canonical `relation_id`, `fulfillment_status:"fulfilled"`, and `occurred_at` |
| `confirm_staged_payment_completion` | `status_transition` | `request_id`, canonical `relation_id`, `confirmed:true`, and `occurred_at` |
| `link_staged_payment_evidence` | `reconciliation` | `source_id`, `evidence_id`, `payment_id`, and exact `posting_id` |
| `ingest_staged_payment_bank_fact` | `creation` | `source_id`, `evidence_id`, `source_payment_at`, signed `amount`, and `currency`; optional `suggested_payment_role` is `deposit` or `final`, and omission is the explicit ambiguous-role branch |
| `confirm_staged_payment_candidate` | `creation` | `request_id`, `candidate_id`, canonical `relation_id`, `payment_role` (`deposit` or `final`), `category_id`, `funding_account_id`, and `exact_binding_confirmed:true` |
| `merge_staged_payment_mirror_evidence` | `reconciliation` | `source_id`, `evidence_id`, `payment_id`, exact `posting_id`, signed `amount`, `currency`, and explicit `source_payment_at`; the time cannot be inherited or synthesized from the payment, original source, or another timestamp |
| `record_refund_request_status` | `status_transition` | `request_id`, `original_transaction_id`, positive `requested_amount`, `currency`, `requested_at`, `approved_at`, and `processor_reported_at` |
| `ingest_refund_status_source` | `status_transition` | `source_id`, `observed_at`, `reported_state` (`requested`, `approved`, or `processing`), and `proves_arrival:false` |
| `confirm_manual_refund_receipt` | `creation` | `request_id`, `refund_relation_id`, `original_transaction_id`, positive `amount`, `currency`, exact `category_id`, owned-real-asset `destination_account_id`, `source_observed_at`, `booking_at`, `value_at`, `arrived_at`, `confirmed_at`, `confirmation_mode:"explicit_manual_receipt"`, `observation_mode:"manual_account_observation"`, and `arrival_confirmed:true` |
| `attach_original_payment_evidence` | `reconciliation` | `source_id`, `evidence_id`, exact original `payment_asset_posting_id`, signed negative `amount`, `currency`, `observed_at`, `booking_at`, `value_at`, and `immutable_payload_hash`; it adds one `bank_debit` source, one `asset_debit` evidence object, and one `payment_asset_posting` evidence link, and changes only that posting's reconciliation from `pending` to `matched` |
| `attach_refund_destination_evidence` | `reconciliation` | `source_id`, `evidence_id`, destination `account_id`, positive `amount`, `currency`, `booking_at`, and `value_at` |
| `attach_refund_dual_role_evidence` | `reconciliation` | `source_id`, `evidence_id`, and exactly `roles:["refund_relationship","destination_asset_posting"]` as a set-like pair |
| `confirm_refund_receipt` | `creation` | `request_id`, `original_transaction_id`, positive `amount`, `currency`, exact `category_id`, owned-real-asset `destination_account_id`, `arrived_at`, `confirmed_at`, and `arrival_confirmed:true` |
| `ingest_refund_credit_source` | `creation` | `source_id`, `source_record_id`, owned-real-asset `account_id`, positive `amount`, `currency`, `processor_reported_at`, `source_observed_at`, `booking_at`, `value_at`, and `original_source_payload_hash` |
| `confirm_imported_refund` | `creation` | `request_id`, `candidate_id`, `original_transaction_id`, exact `category_id`, positive `allocated_amount`, owned-real-asset `destination_account_id`, `arrived_at`, `confirmed_at`, and `arrival_confirmed:true` |
| `merge_refund_mirror_evidence` | `reconciliation` | `source_id`, `evidence_id`, `request_id`, `observed_at`, positive `amount`, and `currency` |
| `validate_lending_event` | `creation` or `update` | `lend` owns request, stable counterparty, owned real funding asset, positive principal, currency, actual/confirmation time and explicit confirmation; `rename_counterparty` owns only stable counterparty and append-only name-history identity and is the single zero-formal-effect `no_change` variant |
| `validate_lending_settlement` | `creation` | `manual_collection` owns stable position/counterparty, destination asset, positive total, non-negative principal/interest, `fee_amount:"0.00"`, exact interest category, currency, actual receipt/confirmation times and explicit confirmation |
| `confirm_imported_lending_collection` | `creation` or `reconciliation` | closed union: `import_intake` binds one bank-credit and one lending-agreement source to a pending candidate; `formal_confirmation` explicitly confirms all six fields and creates the formal collection; `mirror_merge` appends one mirror source/evidence/link and two typed audit links with zero formal effect |
| `allocate_lending_collection` | `creation` | `maximum_allocation` uses the collection components and may reduce the person-level principal exactly to zero; an over-cap attempt is rejected atomically and never truncated |
| `retry_idempotent_input` | `read` | generic RG-08-only `retry` with exact `input_anchor_id`; it returns the anchored first accepted result IDs and leaves the complete state byte-equivalent |

RG-08 registers six accepted variants (`lend`, `manual_collection`, `maximum_allocation`, `import_intake`, `formal_confirmation`, `mirror_merge`), 25 rejected operations, and 13 no-change operations (one rename plus twelve generic retries). Rejected and no-change results preserve the complete baseline and exhaustive zero deltas. The D-090 canonical mirrors are fixed: a negative-interest attempt maps to `$.attempted_input.principal_amount`, while guessed component splitting maps to `$.attempted_input.split_source`. Imported candidates always begin `pending_confirmation` with exactly the six gates `behavior_code`, `counterparty_id`, `destination_account_id`, `principal_amount`, `interest_and_fee_amounts`, and `actual_receipt_time`; bank evidence, expected interest, and name matching cannot auto-confirm them.

All accepted and no-change RG-07 operations use exactly the listed fields with no aliases or optional additions. `no_change` retains the originating action and byte-equivalent input. The semantic validator validates every referenced original transaction, category, account, relation, candidate, source, evidence, amount, currency, time, and exact state effect; structural closure alone is insufficient.

RG-07 rejected operations use action-specific closed attempted inputs and the frozen reason/path pairs below. They forbid strict `input`, return no IDs, and have byte-equivalent baseline/result states with exhaustive zero deltas:

| Action | Closed attempted input | Frozen rejection |
| --- | --- | --- |
| `confirm_manual_refund_receipt` | exactly the manual receipt fields through `arrived_at`, with `arrival_confirmed:false` and no confirmation-only fields | `arrival_confirmation_required` at `$.attempted_input.arrival_confirmed` |
| `allocate_refund_receipt` | exactly `candidate_id`, `requested_allocation`, `available_allocation` | `refund_amount_exceeds_remaining_refundable` at `$.attempted_input.requested_allocation` |
| `confirm_imported_refund` | required `attempt_id`; optional `candidate_id`, `original_transaction_id`, `category_id`, `allocated_amount`, `destination_account_id`, `arrived_at`, `confirmed_at`, and `arrival_confirmed` | first missing explicit confirmation: `original_transaction_confirmation_required` / `original_transaction_id`; `category_allocation_confirmation_required` / `category_id`; `destination_confirmation_required` / `destination_account_id`; or `arrival_confirmation_required` / `arrival_confirmed` |
| `validate_refund_receipt` | required `attempt_id`; optional nullable `original_transaction_id` and `category_id`, plus optional `amount`, `currency`, `destination_account_id`, `remaining_refundable`, and `destination_confirmed` | the exact RG-07 v1 reason/field registry: positive amount, same currency, effective original expense, known owned-real-asset destination, exact original category, remaining cap, original confirmation, and destination confirmation |

`validate_refund_receipt` closes the exact reason/path pairs: `must_be_positive` / `amount`; `same_currency_required` / `currency`; `effective_confirmed_original_expense_required` or `original_transaction_confirmation_required` / `original_transaction_id`; `known_destination_account_required`, `owned_real_asset_destination_required`, or `destination_confirmation_required` / `destination_account_id`; `exact_original_secondary_category_required` / `category_id`; and `refund_amount_exceeds_remaining_refundable` / `amount`. Each field path is rooted at `$.attempted_input`.

All eight staged-payment actions use these same closed inputs for `accepted` and `no_change`; a retry retains its originating action, returns the original stable identities, and does not register a generic retry action. `association_group_id` is not a canonical alias and is forbidden. Unknown or omitted input fields fail Schema validation.

RG-06 rejections retain their originating action with `operation_class=rejection`, forbid strict `input`, require the action's closed sparse `attempted_input`, and return no IDs. Group-creation attempts contain exactly one of `total_amount` or `category_id`. Installment-recording attempts contain exactly one of: `payment_amount`; `payment_role` plus `payment_amount`; `total_currency` plus `payment_currency`; or `funding_account_id`. Completion validation contains exactly `payment_progress:"paid_in_full"`. The 18 frozen cases and first-failure order are:

| Order | Action | Attempted failure | `field_path` | `reason_code` |
| ---: | --- | --- | --- | --- |
| 1 | `create_staged_payment` | total is zero | `$.attempted_input.total_amount` | `must_be_positive` |
| 2 | `create_staged_payment` | total is negative | `$.attempted_input.total_amount` | `must_be_positive` |
| 3 | `create_staged_payment` | category is missing or `null` | `$.attempted_input.category_id` | `secondary_category_required` |
| 4 | `create_staged_payment` | category is primary | `$.attempted_input.category_id` | `secondary_category_required` |
| 5 | `create_staged_payment` | category is inactive | `$.attempted_input.category_id` | `category_inactive` |
| 6 | `create_staged_payment` | category is not an expense category | `$.attempted_input.category_id` | `expense_category_required` |
| 7 | `record_staged_payment_installment` | payment is zero | `$.attempted_input.payment_amount` | `must_be_positive` |
| 8 | `record_staged_payment_installment` | payment is negative | `$.attempted_input.payment_amount` | `must_be_positive` |
| 9 | `record_staged_payment_installment` | deposit equals total | `$.attempted_input.payment_amount` | `deposit_must_be_less_than_total` |
| 10 | `record_staged_payment_installment` | deposit exceeds total | `$.attempted_input.payment_amount` | `deposit_must_be_less_than_total` |
| 11 | `record_staged_payment_installment` | final exceeds due | `$.attempted_input.payment_amount` | `payment_exceeds_due` |
| 12 | `record_staged_payment_installment` | final does not equal remaining due | `$.attempted_input.payment_amount` | `final_must_equal_remaining_due` |
| 13 | `record_staged_payment_installment` | total and payment currencies differ | `$.attempted_input.currency` | `single_currency_required` |
| 14 | `record_staged_payment_installment` | funding account is unknown | `$.attempted_input.funding_account_id` | `unknown_real_account` |
| 15 | `record_staged_payment_installment` | funding account is non-financial | `$.attempted_input.funding_account_id` | `real_financial_account_required` |
| 16 | `record_staged_payment_installment` | funding account is not user-owned | `$.attempted_input.funding_account_id` | `owned_account_required` |
| 17 | `record_staged_payment_installment` | funding account is a liability | `$.attempted_input.funding_account_id` | `asset_account_required` |
| 18 | `confirm_staged_payment_completion` | payment progress claims paid in full while due remains | `$.attempted_input.payment_progress` | `due_must_be_zero` |

Schema closes these attempted shapes and reason/path registries. The semantic validator applies the ordered first applicable failure to the baseline state; Schema does not infer category/account ownership, amounts due, or cross-field business precedence.

Periodic creation binds all three payment-version time roles to input `occurred_at`; `start_at` controls only the allocation calendar. `installment_count` and `remaining_installment_count` determine exact domain-entity cardinality and deterministic equal splits, with all minor-unit remainder assigned to the final installment. Creation returns the new purchase transaction then schedule; recognition returns its new transaction; revision returns its new revision; correction returns its new transaction version. These ordered responses may not contain unrelated existing identities.

`statistics_time` correction creates one `explicit_operation_confirmation`; compared with the prior version, only `id`, consecutive `version_number`, `statistics_at`, and `confirmation_id` may differ. `posting_facts` correction explicitly replaces every current posting exactly once, balances each currency without inferred legs, appends one next transaction version and posting set, retains old postings and evidence unchanged, and sets the new version `created_at` plus confirmation time to `corrected_at`. It preserves `occurred_at`, `statistics_at`, and `effective_at`, so reports recompute in the original period rather than on the correction day. Every replacement has one `posting_replacement` link: non-real legs are `not_applicable`; unchanged real facts are `preserved`; changed real facts are `invalidated`. A preserved match inherits its evidence through the replacement lineage; an invalidated old match appends its invalidation event and the replacement posting reconciliation is pending.

For `confirm_explanation_allocation`, `allocation_direction` is currently `same_as_original_adjustment`; required booleans are `confirms_target_account`, `confirms_actual_occurred_at`, `confirms_real_transaction_amount`, `confirms_currency`, `confirms_target_observed_at`, `confirms_allocation_direction`, and `confirms_explanation_amount`. None of these facts may be inherited from the transaction, candidate, or prior operation.

Registered rejected actions include `manual_expense`, `manual_account_transfer`, all four RG-05 actions (`manual_merged_payment`, `ingest_merged_payment_facts`, `confirm_merged_payment_candidate`, `merge_item_receipt_evidence`), and the four periodic-allocation actions above. They use `operation_class=rejection`, forbid strict `input`, and require their closed `attempted_input`. RG-05 manual rejected items are sparse and closed to only `id`/`item_id`, `amount`, `currency`, `category_id`, `details`, and `source_observed_at`; allocation rejection requires exactly `request_id`, `candidate_id`, `payment_total`, `allocation_total`, `currency`, and `explicit_confirmation:true`. All four actions use `reason_code:identity_conflict` for a same-request changed canonical input, with atomic zero state effect. A rejected periodic operation carries the same complete submitted fields as its accepted input, while attempted decimal fields additionally admit a JSON number so malformed numeric representation can be preserved and rejected.

Periodic rejected outcomes close `reason_code` and `field_path` to these pairs: `exact_decimal_string_required` on `amount` or `remaining_amount`; `must_be_positive` on the same fields; `unsupported_currency` or `currency_mismatch` on `currency`; `invalid_anchor` on `anchor`; `invalid_installment_count` on `installment_count` or `remaining_installment_count`; `installment_not_pending` on `installment_id`; `exceeds_remaining_prepaid` or `installment_amount_mismatch` on `amount`; `invalid_revision_boundary` on `recognized_through`; `remaining_amount_mismatch` on `remaining_amount`; and `transaction_not_correctable` on `transaction_id`. The semantic validator recomputes the first applicable failure from attempted input and baseline state. The result is a distinct state snapshot with identical semantic payload, zero entity/value/status deltas, and no returned IDs.

| Field | Presence and type |
| --- | --- |
| `request_id` | required non-empty ID |
| `amount` | optional decimal string or `null` |
| `category_id` | optional ID or `null` |
| `payment_account_id` | optional ID or `null` |
| `currency` | optional non-empty declared currency string |
| `occurred_at` | optional strict timestamp |
| `note` | optional string |
| `explicit_confirmation` | optional boolean |

`null` records explicit absence; omission means the field was not submitted. Migration and validation must not synthesize omitted currency, time, note, or confirmation facts. Any non-null currency, timestamp, category reference, or account reference that is present must pass lexical, timezone, declaration, and catalog-reference validation before the business rejection is evaluated. Unknown attempted fields are rejected.

A rejected outcome requires both `reason_code` and `field_path`. The path is rooted at the attempted payload and has the form `$.attempted_input.<field>`. RG-01 rejected `manual_expense` validation uses this stable first-failure precedence:

| Precedence | Attempted failure | `field_path` | `reason_code` |
| ---: | --- | --- | --- |
| 1 | amount omitted or `null` | `$.attempted_input.amount` | `missing_required_field` |
| 2 | payment account omitted or `null` | `$.attempted_input.payment_account_id` | `missing_required_field` |
| 3 | category omitted or `null` | `$.attempted_input.category_id` | `missing_required_field` |
| 4 | amount is zero or negative | `$.attempted_input.amount` | `must_be_positive` |
| 5 | category is a primary category | `$.attempted_input.category_id` | `secondary_category_required` |
| 6 | secondary category is inactive | `$.attempted_input.category_id` | `category_inactive` |

The validator recomputes the first failure and requires exact agreement with both outcome fields. A sparse payload that does not match one of these registered failures is invalid; sparse accepted/no-change input never becomes valid through this branch.

Unlisted actions require an independent contract amendment that freezes their operation class and closed input before Schema, validator, adapter, or migration implementation.

RG-03 `manual_account_transfer` uses the v1 field names. Its sparse attempted input requires `request_id` and may contain only `source_account_id`, `destination_account_id`, `source_debit_amount`, `destination_credit_amount`, `fee_amount`, `currency`, `source_currency`, `destination_currency`, `fee_category_id`, `occurred_at`, and `explicit_confirmation`; every field except `request_id` may be omitted, and account and amount fields may be explicit `null`. Its stable first-failure precedence is: missing source, missing destination, same account, unknown source or destination, non-owned source or destination, non-real-financial source or destination, non-positive destination principal, unbalanced source debit/credit/fee, then different source/destination currencies. Reasons and fields are exactly those frozen by RG-03 v1: `required`, `distinct_own_real_financial_accounts_required`, `known_account_required`, `own_account_required`, `real_financial_account_required`, `must_be_positive`, `amounts_must_balance`, and `same_currency_required`.

RG-03 intake actions create only the named source, transfer-record evidence, and pending candidate until explicit candidate confirmation. Candidate confirmation adds one formal `account_transfer`, its version, three role-bound postings, the candidate confirmation, and source-posting evidence/reconciliation; the candidate history changes from pending to confirmed. Mirror intake maps its v1 `source_record` to the closed `account_credit_observation` source subtype and adds only that source, its `transfer_record` evidence, a `destination_asset_posting` link to the existing destination principal posting, and the corresponding reconciliation transition when one exists. It never adds a second formal transaction, version, posting set, posting, candidate, balance, report, or derived financial effect. Incomplete intake creates its source, evidence, and pending candidate with no formal or projection effects. `no_change` replays use their originating action and strict input, return exactly the original operation's returned IDs, and leave the complete state unchanged.

This amendment closes operation representation only. Expected RG-03 v2 output remains for the next approved generation stage; it does not authorize an adapter or v1 fixture rewrite.

RG-04 mixed-payment behavior is split across provenance, confirmation, and formal-ledger layers. `ingest_mixed_payment_source` preserves the source record and its `asset_funding_debit` evidence, creates only a pending `mixed_payment` candidate, and creates no formal transaction or financial effect. The complete candidate has confidence `1.00`; the frozen missing-funding-leg candidate retains confidence `0.58` and remains pending until explicit confirmation. `confirm_mixed_payment_candidate` requires an explicit `category_id` and exactly two confirmed funding components: one distinct owned real asset and one distinct owned real liability, both matching the candidate currency and total. It creates one expense transaction with one categorized expense posting and two negative funding postings, plus one candidate confirmation and one `mixed_payment` relation. The candidate's single source must own exactly one `asset_funding_debit` evidence item; confirmation adds exactly one `real_account_posting` evidence link from that evidence to the newly created asset funding posting. The asset reconciliation starts `matched`, the liability reconciliation starts `pending`, and the transaction summary is therefore `partial`. The candidate transition appends only `confirmed` status and `payload.transaction_id`; `transaction_id` is forbidden while pending and may not replace an existing binding. Source-time binding belongs to the version created by that candidate confirmation, so a later legitimate replacement version may become current without rewriting the original source binding.

The RG-04 `manual_mixed_expense` action uses the same three-posting mixed-expense contract for a directly confirmed input. `credit_principal_repayment` creates only the asset outflow and liability-principal roles and is not consumption. `merge_mixed_payment_mirror_evidence` requires the asset funding reconciliation already matched and the liability funding reconciliation pending. It adds a closed mirror source containing only `evidence_id`, `observed_at`, `account_id`, `amount`, and `currency`, plus `credit_liability_mirror` evidence and one typed link to the existing liability funding posting. Only that liability reconciliation changes from `pending` to `matched`, making the transaction summary `matched`; the formal ledger, relation, balances, reports, and candidate remain unchanged. A mirror source cannot own a candidate or create a second formal transaction, posting set, posting, balance, or report effect. Each real funding posting remains independently reconcilable.

RG-05 merged payment is a different topology: exactly one expense transaction has two distinct positive expense postings and one negative owned real `payment_asset` posting. Two independent `consumption_record` and `item_allocation` pairs bind the expense postings; one `merged_payment` relation binds the transaction, unique payment posting, and both allocations. The allocations are positive, same-currency, distinct, and sum exactly to the payment magnitude. There is one asset cash-flow leg, no clearing account, no discount posting, and no second transaction. Accepted action effects are input-owned field by field: item details/source times and categories bind the corresponding consumption, expense posting, allocation, and evidence links; imported facts remain source-owned and manual entities never fabricate source IDs. Opening-only time expansion is limited to `occurred_at`, `statistics_at`, and `effective_at`; RG-05 never creates `created_at` or `confirmed_at` from source/payment time.

`ingest_merged_payment_facts` creates exactly three sources, three evidence objects, and one pending candidate with zero formal/report/reconciliation effect. Only `confirm_merged_payment_candidate` creates the formal result and appends confirmed candidate history. A `90.00` allocation is rejected as `allocation_incomplete`; a `110.00` allocation is rejected as `allocation_conflict`; both preserve the pending candidate and complete baseline state. The legacy persistent word `conflict` is therefore an operation rejection normalization, not a candidate status. The RG-05 expected output approved under `D-075` contains 17 roots, 25 operations, and 42 complete states: four accepted, four no-change replays, and 17 rejected operations.

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

`occurred_at` is the economic occurrence time, `statistics_at` controls report period attribution, and `effective_at` controls balance replay. `created_at` is an optional actual creation or confirmation time. These fields are not generally interchangeable.

A legacy `occurred_at` may expand into `occurred_at`, `statistics_at`, and `effective_at` only when a frozen fixture plus an approved per-RG mapping proves that exact legacy field simultaneously carried all three economic meanings. The exact source timestamp text is copied byte-for-byte into those approved roles. This is not a general default, does not permit copying an arbitrary available time, and never permits generation of `created_at` or `confirmed_at`. RG-01 v1 is explicitly approved for this three-role expansion; its note-only replacement reuses the prior version's three economic times. RG-05 has the same expansion only for each exact `opening.transactions[*].occurred_at`; payment, candidate, source, and evidence times are excluded from that approval.

Canonical transaction types include `opening_balance`, `expense`, `income`, `account_transfer`, `credit_repayment`, `refund_receipt`, `lending_disbursement`, `lending_collection`, `balance_adjustment`, `balance_adjustment_reversal`, `stored_value_recharge`, `stored_value_spend`, `stored_value_expiry_loss`, `stored_value_pre_activation_balance_adjustment`, `prepaid_purchase`, and `prepaid_recognition`. The pre-activation token is preserved as a canonical type; migration must not alias or replace it silently.

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
- `audit_links` are non-evidentiary links among formal and domain identities. Examples are `adjustment_transaction`, `explanation_transaction`, `allocation_reversal`, and `periodic_allocation_recognition`.

All targets must exist in the same root state and have the declared target kind. An evidence role cannot be used as a relation role, confirmation provenance, or audit role. Later evidence can change reconciliation facts but cannot change balances or reports.

RG-05 intake may create evidence before the future formal posting or allocation target exists, so intake creates no evidence link. Explicit candidate confirmation may then link `bank_payment` evidence only to the unique owned real `payment_asset` posting with role `payment_asset_posting`, and may link existing `item_receipt` evidence only to its exact `item_allocation` with role `item_allocation_fact`. `item_summary` never creates an allocation link. A later receipt merge adds one new receipt source, evidence object, and allocation link only. Item evidence does not also target the related `consumption_record`; that reference remains inside the closed allocation payload.

RG-10 merchant credit evidence is one evidence object with two independent evidence-link identities and targets: `stored_value_asset_posting` targets the eligible owned real posting whose posting role is `stored_value_asset`, while `stored_value_lot_fact` targets exactly one `stored_value_lot` domain entity. Both links must describe the same recharge: the lot references a `stored_value_recharge` transaction, the posting belongs to that transaction's current version and posting set, lot currency equals posting currency, lot `face_value` equals the positive asset-posting amount, and lot `loaded_at` equals the current recharge version's `occurred_at`. The posting link participates in posting reconciliation; the lot link proves only the lot and business fact. A combined `stored_value_credit_lot` role is not canonical.

No state may contain a redundant entity registry or nested projection that duplicates one of these canonical collections.

## Operations

Every operation requires:

- `id`, `root_id`, and integer `sequence` unique within the root.
- `operation_class` and `action_type` as separate fields.
- `baseline_state_id` and `result_state_id`.
- Exactly one outcome-appropriate payload: strict `input` for `accepted` and `no_change`, or the registered sparse `attempted_input` for `rejected`. Missing values are not inherited from a candidate or earlier operation.
- `outcome`, `status_changes`, `deltas`, and `returned_ids`.

Canonical operation classes are `creation`, `update`, `read`, `rejection`, `reconciliation`, `status_transition`, `reversal`, and `adjustment`. Candidate confirmation uses the class of its actual effect: for example, transaction creation is `creation`, evidence binding is `reconciliation`, and RG-09 explanation allocation plus its counter-adjustment is `reversal`.

`outcome.status` has exactly these meanings:

| Status | Meaning |
| --- | --- |
| `accepted` | The requested action completed and its declared result state is authoritative. It may have zero financial deltas but must have the declared state or intake effect. |
| `rejected` | Validation or business rules denied the action atomically. `reason_code` and `field_path` are required; baseline and result states are byte-equivalent after canonicalization, all deltas and status changes are empty, and `returned_ids` is empty. |
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

Legacy RG-10 `not_present` maps at migration time to absence of a `posting_reconciliations` record, not to a new stored status. This mapping rule does not globally relax the current RG-01/RG-09 representative validator, which still requires reconciliation records to cover every eligible posting. Once RG-10 is added to the semantic validator, its supported state rules must explicitly define when record absence is permitted. When RG-10 reconciliation records are present for a transaction, the summary is derived only from eligible postings: one `matched` plus one `pending` is `partial`, and all `matched` is `matched`. The legacy word `complete` is not a canonical summary token and must not enter v2.

Transaction, relation, group, target-observation, payment-progress, fulfillment, refund, lending, and adjustment summaries belong in `derived_statuses`. They are recomputed from canonical postings, evidence links, and domain entities. RG-05 `item_evidence_completeness` belongs to its `merged_payment` relation and is `none`, `partial`, or `complete` according to exact `item_allocation_fact` receipt links; it is independent of the payment posting's financial reconciliation. Derived statuses cannot be an independent balance-changing fact. Reconciliation or completeness changes never alter posting amounts, balances, transaction effectiveness, or reports.

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

Formal and strict accepted/no-change amounts are JSON strings in canonical fixed precision. For precision `p`, the form is `0` or a non-zero integer part followed by exactly `p` fractional digits, with an optional leading `-`; leading `+`, exponent notation, leading zeroes, `.5`, `1.`, and negative zero are invalid. For CNY precision 2, examples are `0.00`, `35.80`, and `-20.00`. A rejected `manual_expense.attempted_input.amount` additionally permits a negative-zero integer part such as `-0.01` so the failed submission can be represented; it is never a valid formal amount.

Python semantic validation verifies that every amount uses the declared currency precision and that arithmetic uses exact decimal or integer minor units.

### Time

Timestamps use RFC 3339 date-time strings with seconds and an explicit `Z` or numeric offset. `-00:00`, local times without an offset, and leap-second text are rejected. The numeric offset must be valid for `case.timezone` at that instant. Fractional-second precision, when present, is preserved.

An absent time property means the source did not provide that time. Timestamp properties may not be `null`, copied from another semantic time, derived from file order, or filled with migration/runtime current time. Required economic times must instead cause preserve/map/derive/reject classification to reject an unresolvable record. Representative sample times are included only where the governing fixture or sample input explicitly defines their semantics.

The sole migration exception is an explicit per-RG collapsed-time approval as defined under Formal ledger ownership. Such an approval names the exact legacy `occurred_at` field and all three target economic roles; the frozen fixture and mapping are evidence that the one legacy fact already carried those meanings, not permission to invent missing times. RG-01 has this approval for its frozen transaction time. RG-05 has it only for each exact `opening.transactions[*].occurred_at`. Both expand solely to `occurred_at`, `statistics_at`, and `effective_at`; neither generates `created_at` or `confirmed_at`.

### IDs and canonicalization

Existing valid stable IDs are preserved. A missing ID is generated deterministically with UUIDv5 using namespace `cfad3f84-edb1-5838-ae53-aae49684cf1a`.

Migration identity uses a normalized source locator, not JSON Pointer. Its grammar is root `$`, object member `.key`, and array wildcard `[*]`. A concrete array index, slash pointer, traversal position, control character, or empty stable occurrence discriminator is invalid. The semantic key is exactly:

`source-locator + "\noccurrence=" + stable-occurrence-discriminator`

`migration_semantic_key(source_locator, occurrence_discriminator)` constructs that key. A root is bootstrapped without a root-ID dependency by `deterministic_v2_root_id`; its UUIDv5 name is exactly:

`case-id + "\n@root\nroot\n" + semantic-key`

After the root exists, `deterministic_v2_migration_id` generates descendants with the exact UUIDv5 name:

`case-id + "\n" + root-id + "\n" + entity-kind + "\n" + semantic-key`

The existing `deterministic_v2_id(case_id, root_id, entity_kind, semantic_key)` remains the authored-fixture-compatible helper. Stable occurrence discriminators prefer source stable IDs, request IDs, case IDs, or approved semantic occurrence IDs. Array index, traversal order, display names, current time, machine paths, and private data are forbidden inputs. A collision between different canonical identity inputs is a hard rejection.

The base case validator cannot infer source locators or occurrence discriminators from v2 output alone and therefore cannot prove migration IDs by reverse inspection. An adapter or semantic-equivalence validator must read the approved path map, call the migration helpers with the approved locator/discriminator inputs, and compare every generated ID. It must not claim that ordinary v2 case validation performed this migration check.

JSON input follows RFC 8259 and duplicate object member names are rejected. Before hashing or byte comparison, set-like arrays are sorted by their declared key, then the document is serialized with the repository's deterministic Python serializer `json.dumps(document, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":"))` and the UTF-8 bytes are used directly (no Unicode normalization). This is a Python-specific serialization, not RFC 8785 JCS: integer-valued floats render as `130.0` (json.dumps preserves int/float types, so the `.0` form appears only for float values), so canonical bytes are reproducible only by this exact serializer and are not portable to an independent JCS implementation. Fingerprints are lowercase `sha256:<hex>` over canonical UTF-8 bytes.

Canonical hashes are a self-consistent integrity checksum, not a cross-implementation interoperability standard.  The repository's single Python serializer, `json.dumps(ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",",":"))` applied to the sorted document and encoded as UTF-8, is the only implementation that computes or verifies them: the 12 publishers' `_canonical_bytes`, the pre-publish integrity gate's `canonical_bytes`, and every canonical test share it, and neither the Kotlin oracle nor CI has an independent recomputation path (verified 2026-08-11).  A canonical fingerprint therefore detects accidental corruption of the parsed document and proves nothing about other implementations.  Cross-language recomputation (for example RFC 8785 JCS) is valid only when it agrees with this exact serializer.  The manifest's built-in `canonicalization` metadata block, `{"encoding":"UTF-8","key_order":"sorted","separators":[",",":"],"scope":"parsed expected/output JSON"}`, records the same contract for the published cases.

The published v2 outputs contain exactly six integer-valued floats, all inside rejected `attempted_input` payloads, where the established design admits a JSON number so malformed numeric representation can be preserved and rejected.  A full JSON walk of `golden/rules-v2` verifies that these six are the only float leaves and that every other amount is a decimal string: `rg-09.json` `$.operations[21].attempted_input.target_amount` = 130.0; `rg-10.json` `$.operations[4].attempted_input.paid_amount` = 1000.0, `$.operations[5].attempted_input.credited_amount` = 1200.0, and `$.operations[6].attempted_input.bonus_amount` = 200.0; `rg-11.json` `$.operations[12].attempted_input.amount` = 100.0; and `rg-12.json` `$.operations[10].attempted_input.replacement_postings[0].amount` = 110.0.  Each renders with a trailing `.0` in canonical bytes and is an implementation-specific known serialization point.

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

Base Python case validation is responsible for cross-reference existence and target kind, root isolation, stable-ID uniqueness, catalog ownership, complete balance membership, currency precision, per-currency posting-set balance, transaction/version/posting-set ownership, append-only history, current-version replay, exact balances, reports, posting reconciliation eligibility, derived business status, operation delta recomputation, and idempotency. Canonical fingerprints remain a publication-layer check. Deterministic migration-ID and mapping-completeness checks belong to adapter/equivalence validation because only that layer has the approved source locator and occurrence discriminator inputs.

Neither layer may auto-correct an invalid fixture. Validation failure reports the path and rule and leaves the input unchanged.

## Migration and implementation gates

The gates are independent and sequential:

1. **Contract:** the data contract requires independent specification and quality review followed by explicit final approval. This contract has passed that gate. Representative examples remain anonymous non-output drafts and do not acquire migration approval from the contract gate.
2. **Schema and validator:** contract approval authorizes the JSON Schema 2020-12 and semantic validator implementation. Passing this gate does not authorize adapters or fixture rewrites.
3. **Per-RG mapping:** every RG receives an approved normalized-source-locator inventory and a path-by-path `preserve`, `map`, `derive`, or `reject` decision. The unclassified path count must be zero, and alias/replacement decisions must be explicit. Passing this gate authorizes generation of a `draft_for_review` expected output, not adapter implementation or fixture rewrite.
4. **Expected output, adapter, and fixture migration:** each RG receives an independently reviewed expected v2 output and semantic-equivalence decision, followed by explicit user approval. Only then may adapter implementation or fixture migration be separately authorized. Migration must be deterministic, resumable, case-isolated, atomic on publication, and auditable by pre/post object counts and content hashes. Publication still requires explicit approval.

RG-05 has passed the contract/schema/validator and per-RG mapping gates. Under `D-075`, its generated 17-root, 25-operation, 42-state expected output has `approval_status: approved`. The shared `GoldenV2Oracle` and `Rg05FullStateOracleTest` compare full state, deltas, and status changes for all 25 operations. `D-075` does not authorize adapter implementation or fixture migration; publication remains unauthorized, and no RG-05 artifact exists under `golden/rules-v2`.

Migration never mutates v1 input in place. Failure cannot expose a partial v2 case as successful. Recovery cannot mix old and new outputs. Representative examples in this directory are not migration baselines, approved release artifacts, or expected outputs for any gate after gate 1.
