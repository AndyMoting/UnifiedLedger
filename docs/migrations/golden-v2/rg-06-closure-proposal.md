# RG-06 Golden Schema v2 Mapping Closure Proposal

状态：approved

## Authority And Boundary

This proposal records the RG-06 mapping closure candidate that converts the five historical mapping gaps from `planned_contract` targets to owners already approved by `docs/GOLDEN_SCHEMA.md` and implemented by the current JSON Schema and validators. Closure applies both top-level prefix rewrites and explicit replacement of legacy planned fields that the approved closed types intentionally do not contain. It does not add or change product behavior, accounting semantics, schema fields, discriminator values, or operation actions. The candidate includes only a proof-level validator correction: existing RG-06 `accepted` append-only transitions are distinguished from the already-required zero-effect `no_change` and `rejected` outcomes.

This proposal is limited to mapping closure evidence. The approved candidate path-map records `status=approved`, `expected_output_gate=approved`, five `approved_implemented` resolved gaps and no unresolved gaps. The expected artifact is deterministic and approved under `D-081`; adapter/replay, fixture rewrite and publication are separately evidenced by the RG-06 runtime replay, publication tests, and the published output/manifest.

## Candidate Baseline

- Source fixture: `golden/rules/rg-06.json`, schema version 1.
- Target contract: `unifiedledger.golden-case` contract version `2.0.0`.
- Inventory invariant: `1188` normalized source paths, `3610` leaf occurrences and zero unclassified paths.
- Resolved gap counts: GAP-01 `71`, GAP-02 `147`, GAP-03 `508`, GAP-04 `170`, GAP-05 `72`; counts overlap where one source fact crosses owners.
- Current candidate gate: `1181` `ready` entries, `7` test-only exclusions, zero unresolved gaps and `expected_output_gate=approved`.
- Current expected artifact: `20` roots, `41` operations and `61` states.

## Corrected Mapping Defect

Five source-record `evidence_id` entries shared by GAP-02 and GAP-05 contained two planned target JSONPaths concatenated into one string. This candidate splits each value into these two independent targets:

- `$.planned_contract.states[*].sources[*].payload.evidence_id`
- `$.planned_contract.states[*].evidence[*].id`

The correction changes no source path, occurrence count, classification, disposition, gap reference or contract meaning. A cross-RG regression now requires every target path to start with exactly one `$.` root marker and contain no whitespace.

The planned source-payload target is the historical pre-closure target for an RG-06 branch-scoped exception: `stagedPaymentBankFactSource` forbids `evidence_id` in its payload. The current closure removes that source-payload target after emitting its frozen legacy evidence identity only to the closed evidence `id` owner. `evidence.source_ids` copies the exact frozen source-record identity; an existing `evidence_links[*].source_id` is an independent consistency check for linked evidence and must equal that same source identity. An imported pending candidate may have source and evidence with no evidence link, in which case the candidate's exact `source_id` (which must equal the source record ID) supplies `evidence.source_ids` and no link, payment, reconciliation or formal effect may be created. It must never receive the legacy evidence ID.

## Schema Reconciliation

The historical pre-closure path map contained `1020` planned target occurrences and `113` unique candidate schema paths: `95` existed directly in the current JSON Schema and `18` were legacy planned fields intentionally absent from the approved closed types. The current candidate has removed every `planned_contract` target. The 18 legacy paths remain encoded in this document as explicit replacement rules, while their emitted canonical owners are the current map targets.

For the `95` directly owned historical paths, this closure candidate applies these mechanical prefix rewrites:

| Planned prefix | Approved owner prefix |
| --- | --- |
| `$.planned_contract.states[*]` | `$.states[*]` |
| `$.planned_contract.operations[*]` | `$.operations[*]` |

Already canonical `$.roots[*]`, `$.states[*]` and `$.operations[*]` targets remain unchanged. After prefix rewriting, every target must exist in the current v2 schema path inventory. No field may be dropped, renamed, synthesized or redirected merely to make validation pass.

The planned `$.planned_contract.states[*].sources[*].payload.evidence_id` target is a branch-scoped exception outside the `18`-target replacement table. A generic schema-path inventory can encounter `payload.evidence_id` through unrelated source branches, but neither actual `stagedPaymentBankFactSource` payload branch permits it. The special transform removes that source-payload field and emits its frozen evidence ID only to canonical evidence `id`; it obtains `evidence.source_ids` from the exact source-record ID, then checks any existing evidence link's `source_id` against it. For an unlinked pending imported candidate, the source-record/candidate `source_id` is the only provenance owner and the output remains unlinked and zero-effect. The candidate validates the transformed manual and imported staged source/evidence forms against their direct schema branches and validates both known-role and ambiguous-role `stagedPaymentCandidate` branches, including cross-branch rejection cases. Generic inventory membership proves target addressability only and does not prove oneOf subtype compliance.

The `18` absent legacy planned fields require these approved-contract replacements. A replacement may remove a redundant target only where the same source entry already names the canonical owner.

| Legacy target after prefix removal | Canonical replacement |
| --- | --- |
| `$.operations[*].input.association_group_id` | `$.operations[*].input.relation_id` |
| `$.states[*].candidates[*].payload.candidates` | `$.states[*].candidates`; this source leaf represents an empty collection, not a nested candidate payload |
| `$.states[*].candidates[*].payload.kind` | `$.states[*].candidates[*].type` |
| `$.states[*].candidates[*].payload.evidence_id` | `$.states[*].candidates[*].payload.evidence_ref` |
| `$.states[*].candidates[*].payload.immutable_source_fields[*]` | The closed source and candidate owners: source `id`, `type`, and `payload`; candidate `id`, `type`, `source_ids`, `confidence`, `payload`, and `status_history[*].status`, following the approved RG-05 mapping precedent |
| `$.states[*].candidates[*].payload.confirmation_provenance` | Candidate `status_history[*].status` plus the state `confirmations` collection; `null` means no confirmation object and pending status |
| `$.states[*].candidates[*].payload.association_group_id` | Confirming `$.operations[*].input.relation_id` |
| `$.states[*].candidates[*].payload.category_id` | Confirming `$.operations[*].input.category_id` |
| `$.states[*].candidates[*].payload.funding_account_id` | Confirming `$.operations[*].input.funding_account_id` |
| `$.states[*].candidates[*].payload.exact_binding_confirmed` | Confirming `$.operations[*].input.exact_binding_confirmed` |
| `$.states[*].candidates[*].payload.request_id` | Confirming `$.operations[*].input.request_id` |
| `$.states[*].candidates[*].payload.confirmed_at` | `$.states[*].confirmations[*].confirmed_at`; the actual v1 confirmation time is preserved and is not synthesized from source time |
| `$.states[*].domain_entities[*].payload.payment_ids[*]` | Installment `domain_entities[*].id` plus staged-payment `relations[*].member_refs[*].kind/id` |
| `$.states[*].domain_entities[*].payload.payments` | Top-level `domain_entities` plus staged-payment `relations[*].member_refs`; the source leaf represents an empty installment collection, not lifecycle payload |
| `$.states[*].domain_entities[*].payload.payment_progress` | Remove this redundant target; the same entry already targets the sole current owner under `derived_statuses` |
| `$.states[*].domain_entities[*].payload.fulfillment_status` | Remove this redundant target; the same entry already targets the sole current owner under `derived_statuses` |
| `$.states[*].domain_entities[*].payload.user_labels.payment` | Map the legacy display projection to the canonical `payment_progress` derived-status tuple; do not serialize localized display text |
| `$.states[*].domain_entities[*].payload.user_labels.fulfillment` | Map the legacy display projection to the canonical `fulfillment_status` derived-status tuple; do not serialize localized display text |

Every replacement target named above exists in the current JSON Schema path inventory. The closure candidate must encode these rules entry by entry and must not use a broad string substitution for the `18` exceptions.

Within an RG-06 staged-payment candidate only, the frozen `requires_confirmation` array token `association_group_id` becomes `relation_id`. This is an array-token rewrite scoped to that candidate field, not a broad value substitution across legacy fields or operation inputs. A null legacy confirmation provenance produces zero confirmations, zero confirming operations and zero formal effect. Each non-null frozen provenance produces exactly one `candidate_confirmation` with an identical `confirmed_at`, the exact candidate subject, and one matching `confirm_staged_payment_candidate` action/input.

## Gap Closure Plan

| Gap | Approved owner after prefix rewrite | Required preservation and proof |
| --- | --- | --- |
| `RG06-GAP-01` operation registry | `operations[*]`, with root operation references and complete baseline/result states | Preserve all eight closed action types, translate legacy `association_group_id` to canonical `relation_id`, retain strict `input` or sparse `attempted_input`, accepted/no-change/rejected outcomes, returned IDs, exhaustive deltas, status changes, retry identity and rejection atomicity. Dispatch every legacy `operation_context` to its canonical rejected action and replay all 18 fixture invalid cases with the exact first-failure reason and field. Prove with RG-06 contract/operation tests and delta recomputation. |
| `RG06-GAP-02` source and candidate payloads | `states[*].sources`, `states[*].candidates`, `states[*].confirmations`, confirming `operations[*].input`, and their exact references | Preserve immutable source times, amount/currency/evidence lineage, confidence, explicit null role ambiguity and bounded candidate history. Move legacy confirmation provenance to the canonical confirmation object and confirming operation input; candidate status never authorizes formal effects. Manual source absence remains omission. Manual deposit/final installment operations must create deterministic `explicit_manual_save` confirmations owned by the creating operation, bind the transaction version and return the confirmation identity. Prove with RG-06 contract and semantic tests. |
| `RG06-GAP-03` relation/lifecycle/installment topology | `states[*].relations`, `states[*].domain_entities`, formal transactions, versions, posting sets and postings | Keep the `staged_payment` relation identity-only with empty payload; keep lifecycle and each installment as separate domain entities; translate legacy payment collections and IDs into installment entities plus relation member refs; preserve member cardinality, amount/time arithmetic and exact transaction/posting bindings. Prove with relation, lifecycle, formal-ledger and rehydration tests. |
| `RG06-GAP-04` status and history | `states[*].derived_statuses` and lifecycle `domain_entities[*].payload.state_history` | Keep payment progress, fulfillment and group reconciliation independent; remove duplicate current-status payload targets and localized `user_labels`; preserve ordered continuous history, latest-state projection and zero-effect transitions. Prove with RG-06 operation and semantic tests. |
| `RG06-GAP-05` evidence and mirror lineage | `states[*].sources`, `states[*].evidence`, `states[*].evidence_links` and `states[*].posting_reconciliations` | Preserve original/mirror lineage: the original precedes its mirror in the frozen source collection and their `source_payment_at` values are byte-for-byte equal. Preserve exclusive source time ownership, exact amount/currency match, canonical posting-level evidence link and zero financial effect. Evidence links target only the exact `payment_asset` posting. Prove with contract, semantic and reconciliation tests. |

## Implemented Closure Candidate

The current closure candidate applies the transformations above against the approved contract:

1. Rewrite the `95` directly owned unique target paths to their approved top-level owners.
2. Apply the explicit canonical replacement rules above to all entries using any of the `18` absent legacy planned fields.
3. Require every resulting target to resolve in the current JSON Schema path inventory and require no `planned_contract` target to remain.
4. Set every executable entry to `disposition=ready`, clear its `contract_gap_ids`, and retain the seven test-only exclusions.
5. Resolve all five contract gaps as `approved_implemented`, set `contract_gap_count=0`, and preserve bidirectional gap-reference closure.
6. Set path-map `status=approved` and `expected_output_gate=approved`; preserve the expected artifact as deterministic and approved under `D-081`.

The closure implementation must preserve all inventory and classification counts. It must leave the v1 fixture, Golden Schema, schema JSON, runtime, persistence, manifest and published v2 directory unchanged. The only validator delta is the narrow outcome gate described above; it enforces existing state-equivalence and does not alter any accepted product contract.

## Validator Proof Boundary

The frozen RG-06 contract already requires `accepted` operations to append their declared history, relation, evidence, or reconciliation effect, while `no_change` retries and `rejected` operations preserve an equivalent complete state. The candidate validator now gates the RG-06 append-only exceptions on `accepted` and rejects any non-accepted state change before the existing operation checks. This is verifier maintenance for an existing invariant, not a new schema owner, operation action, accounting behavior, fixture field, adapter, rewrite, or publication decision.

## Closure Acceptance Evidence

The closure candidate provides all of these independent proofs:

1. A pure target-path transform applies only the two declared prefix rewrites, the `18` explicit legacy replacements, and the branch-scoped source-payload `evidence_id` transform. Every emitted target has no `planned_contract` envelope and belongs to the current normalized schema-path inventory; the current path-map itself contains no planned target.
2. Direct schema validation covers the manual and imported `stagedPaymentBankFactSource` and `stagedPaymentBankPaymentEvidence` branches. It must prove that the source-payload `evidence_id` is absent, its frozen value emits only to evidence `id`, and `evidence.source_ids` receives the exact source-record identity. A matching `evidence_links[*].source_id` must agree when present; an unlinked pending imported candidate must remain unlinked with no payment, reconciliation or formal effect.
3. Direct `stagedPaymentCandidate` `oneOf` validation covers both known-role and ambiguous-role branches from fixture-derived candidates. It must prove confidence, explicit null fields, source/evidence references, confirmation requirements, provenance and bounded status history, and must reject cross-branch mutations.

4. Rejected-operation dispatch is fixture-derived and exhaustive: each of the 18 `invalid_inputs` entries maps its legacy `operation_context` to one canonical RG-06 action, uses the declared sparse attempted-input definition, preserves the exact reason/field pair, and emits a rejected outcome with empty deltas, no status changes, no returned IDs and no state effect.

5. Manual `save-deposit` and `save-final` replay is fixture-derived: each legacy `association_group_id` maps to `relation_id`, the installment role determines the deterministic `confirmation-{payment_role}` ID, the confirmation is `explicit_manual_save` with operation subject/owner, the current transaction version stores that confirmation ID, and returned IDs include it alongside the transaction and installment entity.

The flattened normalized schema-path inventory is not a substitute for the direct oneOf subtype proof. Both proofs must pass before a closure candidate can be reviewed.

## Machine-Readable Rules

The following block is the complete machine-readable representation of this proposal. It describes a future, separately authorized closure only; it does not rewrite the current path map or change its gate metadata.

<!-- rg06-closure-rules:begin -->
```json
{
  "artifact_type": "rg06_mapping_closure_proposal_rules",
  "artifact_version": 1,
  "scope": "proposal_only",
  "path_map_preconditions": {
    "status": "approved",
    "expected_output_gate": "approved",
    "contract_gap_count": 0,
    "unresolved_contract_gap_ids": [],
    "entry_dispositions": {
      "ready": 1181,
      "test_only_exclusion": 7
    },
    "resolved_contract_gap_count": 5,
    "planned_target_occurrences": 0,
    "unique_planned_targets": 0
  },
  "five_target_corrections": {
    "source_paths": [
      "$.idempotency.expected.import_state.source_records[*].evidence_id",
      "$.idempotency.expected.manual_state.source_records[*].evidence_id",
      "$.import_path.canonical_final_state.source_records[*].evidence_id",
      "$.import_path.ordered_operations[*].expected.state.source_records[*].evidence_id",
      "$.manual_path.canonical_final_state.source_records[*].evidence_id"
    ],
    "target_paths": [
      "$.planned_contract.states[*].sources[*].payload.evidence_id",
      "$.planned_contract.states[*].evidence[*].id"
    ],
    "canonical_target_paths": [
      "$.states[*].evidence[*].id"
    ]
  },
  "source_payload_evidence_id_closure": {
    "case_id": "RG-06",
    "future_closure_only": false,
    "current_path_map_action": "redirected",
    "planned_source_target": "$.planned_contract.states[*].sources[*].payload.evidence_id",
    "source_definition": "stagedPaymentBankFactSource",
    "forbidden_source_payload_field": "evidence_id",
    "source_payload_action": "remove_after_redirect",
    "validation": "direct_schema_branches_only",
    "canonical_evidence_ownership": {
      "evidence_definition": "stagedPaymentBankPaymentEvidence",
      "evidence_id_owner_path": "$.states[*].evidence[*].id",
      "legacy_source_payload_evidence_id_emits_to": "$.states[*].evidence[*].id",
      "evidence_source_reference_path": "$.states[*].evidence[*].source_ids[*]",
      "evidence_source_reference_value_source": "$.source_records[*].id",
      "linked_evidence_source_reference_validation": "$.evidence_links[*].source_id",
      "unlinked_pending_candidate_source_reference_value_source": "$.candidates[*].source_id",
      "unlinked_pending_candidate_evidence_link": "absent",
      "legacy_source_payload_evidence_id_must_not_emit_to": "$.states[*].evidence[*].source_ids[*]",
      "source_payload_evidence_id": "forbidden"
    },
    "branches": [
      {
        "source_variant": "manual",
        "source_definition": "stagedPaymentBankFactSource",
        "evidence_definition": "stagedPaymentBankPaymentEvidence",
        "source_payload_evidence_id": "forbidden",
        "evidence_id_owner_path": "$.states[*].evidence[*].id",
        "legacy_source_payload_evidence_id_emits_to": "$.states[*].evidence[*].id",
        "evidence_source_reference_path": "$.states[*].evidence[*].source_ids[*]",
        "evidence_source_reference_value_source": "$.source_records[*].id",
        "linked_evidence_source_reference_validation": "$.evidence_links[*].source_id",
        "legacy_source_payload_evidence_id_must_not_emit_to": "$.states[*].evidence[*].source_ids[*]"
      },
      {
        "source_variant": "imported",
        "source_definition": "stagedPaymentBankFactSource",
        "evidence_definition": "stagedPaymentBankPaymentEvidence",
        "source_payload_evidence_id": "forbidden",
        "evidence_id_owner_path": "$.states[*].evidence[*].id",
        "legacy_source_payload_evidence_id_emits_to": "$.states[*].evidence[*].id",
        "evidence_source_reference_path": "$.states[*].evidence[*].source_ids[*]",
        "evidence_source_reference_value_source": "$.source_records[*].id",
        "linked_evidence_source_reference_validation": "$.evidence_links[*].source_id",
        "unlinked_pending_candidate_source_reference_value_source": "$.candidates[*].source_id",
        "unlinked_pending_candidate_evidence_link": "absent",
        "legacy_source_payload_evidence_id_must_not_emit_to": "$.states[*].evidence[*].source_ids[*]",
        "candidate_evidence_reference_path": "$.states[*].candidates[*].payload.evidence_ref"
      }
    ]
  },
  "future_closure_acceptance": {
    "direct_schema_branch_proof": {
      "source_evidence_variants": ["manual", "imported"],
      "candidate_definition": "stagedPaymentCandidate",
      "candidate_variants": ["known_role", "ambiguous_role"],
      "candidate_cross_branch_rejections_required": true,
      "unlinked_pending_evidence_source_reference_required": true,
      "unlinked_pending_evidence_link": "absent",
      "unlinked_pending_formal_effect": 0,
      "unlinked_pending_reconciliation_effect": 0
    }
  },
  "prefix_rewrites": [
    {
      "from": "$.planned_contract.states[*]",
      "to": "$.states[*]"
    },
    {
      "from": "$.planned_contract.operations[*]",
      "to": "$.operations[*]"
    }
  ],
  "replacement_strategy": "explicit_per_target",
  "legacy_target_replacements": [
    {
      "legacy_target": "$.operations[*].input.association_group_id",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.relation_id"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.association_group_id",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.relation_id"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.candidates",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].candidates"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.category_id",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.category_id"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.confirmation_provenance",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].candidates[*].status_history[*].status",
        "$.states[*].confirmations[*]"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.confirmed_at",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].confirmations[*].confirmed_at"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.evidence_id",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].candidates[*].payload.evidence_ref"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.exact_binding_confirmed",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.exact_binding_confirmed"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.funding_account_id",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.funding_account_id"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.immutable_source_fields[*]",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].sources[*].id",
        "$.states[*].sources[*].type",
        "$.states[*].sources[*].payload",
        "$.states[*].candidates[*].id",
        "$.states[*].candidates[*].type",
        "$.states[*].candidates[*].source_ids[*]",
        "$.states[*].candidates[*].confidence",
        "$.states[*].candidates[*].payload",
        "$.states[*].candidates[*].status_history[*].status"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.kind",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].candidates[*].type"
      ]
    },
    {
      "legacy_target": "$.states[*].candidates[*].payload.request_id",
      "mode": "replace",
      "replacement_targets": [
        "$.operations[*].input.request_id"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.fulfillment_status",
      "mode": "remove_redundant",
      "replacement_targets": [],
      "retained_owner_paths": [
        "$.states[*].derived_statuses[*].target_kind",
        "$.states[*].derived_statuses[*].target_id",
        "$.states[*].derived_statuses[*].status_name",
        "$.states[*].derived_statuses[*].value"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.payment_ids[*]",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].domain_entities[*].id",
        "$.states[*].relations[*].member_refs[*].kind",
        "$.states[*].relations[*].member_refs[*].id"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.payment_progress",
      "mode": "remove_redundant",
      "replacement_targets": [],
      "retained_owner_paths": [
        "$.states[*].derived_statuses[*].target_kind",
        "$.states[*].derived_statuses[*].target_id",
        "$.states[*].derived_statuses[*].status_name",
        "$.states[*].derived_statuses[*].value"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.payments",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].domain_entities[*].id",
        "$.states[*].relations[*].member_refs[*].kind",
        "$.states[*].relations[*].member_refs[*].id"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.user_labels.fulfillment",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].derived_statuses[*].target_kind",
        "$.states[*].derived_statuses[*].target_id",
        "$.states[*].derived_statuses[*].status_name",
        "$.states[*].derived_statuses[*].value"
      ]
    },
    {
      "legacy_target": "$.states[*].domain_entities[*].payload.user_labels.payment",
      "mode": "replace",
      "replacement_targets": [
        "$.states[*].derived_statuses[*].target_kind",
        "$.states[*].derived_statuses[*].target_id",
        "$.states[*].derived_statuses[*].status_name",
        "$.states[*].derived_statuses[*].value"
      ]
    }
  ],
  "candidate_branches": [
    {
      "definition": "stagedPaymentCandidate",
      "schema_branch_definition": "knownRoleStagedPaymentCandidate",
      "variant": "known_role",
      "confidence": "1.00",
      "payment_role": [
        "deposit",
        "final"
      ],
      "guessed_payment_role": "omitted",
      "provenance": {
        "rule": "staged_payment_bank_fact",
        "rule_version": 1
      },
      "requires_confirmation": [
        "relation_id",
        "payment_role",
        "category_id",
        "funding_account_id"
      ]
    },
    {
      "definition": "stagedPaymentCandidate",
      "schema_branch_definition": "ambiguousRoleStagedPaymentCandidate",
      "variant": "ambiguous_role",
      "confidence": "0.50",
      "payment_role": null,
      "guessed_payment_role": null,
      "provenance": {
        "rule": "staged_payment_bank_fact",
        "rule_version": 1
      },
      "requires_confirmation": [
        "relation_id",
        "payment_role",
        "category_id",
        "funding_account_id"
      ]
    }
  ],
  "candidate_status_history": {
    "pending": [
      "pending_confirmation"
    ],
    "confirmed": [
      "pending_confirmation",
      "confirmed"
    ]
  },
  "candidate_requires_confirmation_token_rewrite": {
    "case_id": "RG-06",
    "future_closure_only": false,
    "legacy_candidate_kind": "staged_payment",
    "source_definition": "stagedPaymentCandidate",
    "legacy_field": "requires_confirmation",
    "target_field": "payload.requires_confirmation",
    "rewrite_kind": "array_token",
    "scope": "staged_payment_candidate_requires_confirmation_token_only",
    "frozen_source_values_unchanged": true,
    "forbid_broad_value_substitution": true,
    "from": "association_group_id",
    "to": "relation_id",
    "canonical_tokens": [
      "relation_id",
      "payment_role",
      "category_id",
      "funding_account_id"
    ]
  },
  "candidate_confirmation": {
    "candidate_status_is_not_authorization": true,
    "authorization_operation": {
      "action_type": "confirm_staged_payment_candidate",
      "operation_class": "creation",
      "input_definition": "confirmStagedPaymentCandidateInput",
      "required_input_fields": [
        "request_id",
        "candidate_id",
        "relation_id",
        "payment_role",
        "category_id",
        "funding_account_id",
        "exact_binding_confirmed"
      ],
      "required_values": {
        "exact_binding_confirmed": true
      },
      "confirmation_definition": "candidateConfirmation",
      "confirmation_type": "candidate_confirmation",
      "subject_kind": "candidate",
      "formal_effect": "creates_installment_payment_only_after_authorized_operation",
      "actual_payment_at_owner": "$.states[*].domain_entities[*].payload.actual_payment_at"
    },
    "legacy_confirmation_provenance_branches": {
      "pending_null": {
        "legacy_confirmation_provenance": null,
        "candidate_status": "pending_confirmation",
        "confirmation_count": 0,
        "operation_count": 0,
        "formal_effect": "none"
      },
      "confirmed_non_null": {
        "legacy_confirmation_provenance": "non_null",
        "confirmation_count": 1,
        "operation_count": 1,
        "confirmation_type": "candidate_confirmation",
        "confirmed_at_source_field": "confirmed_at",
        "confirmed_at_owner_path": "$.states[*].confirmations[*].confirmed_at",
        "subject_kind": "candidate",
        "confirmation_projection": {
          "confirmed_at": {
            "source": "confirmation_provenance.confirmed_at",
            "preservation": "identical"
          },
          "subject": {
            "kind": "candidate",
            "id_source": "candidate.id"
          }
        },
        "operation_action_type": "confirm_staged_payment_candidate",
        "operation_input_definition": "confirmStagedPaymentCandidateInput",
        "operation_input_candidate_field": "candidate_id",
        "operation_input_projection": {
          "request_id": "confirmation_provenance.request_id",
          "candidate_id": "candidate.id",
          "relation_id": "confirmation_provenance.association_group_id",
          "payment_role": "confirmation_provenance.payment_role",
          "category_id": "confirmation_provenance.category_id",
          "funding_account_id": "confirmation_provenance.funding_account_id",
          "exact_binding_confirmed": "confirmation_provenance.exact_binding_confirmed"
        },
        "formal_effect": "creates_installment_payment_only_after_authorized_operation"
      }
    }
  },
  "source_branches": [
    {
      "definition": "stagedPaymentBankFactSource",
      "payload_definition": "manualStagedPaymentBankFactPayload",
      "type": "staged_payment_bank_fact",
      "variant": "manual",
      "required_payload_fields": [
        "amount",
        "currency",
        "observed_at"
      ],
      "forbidden_payload_fields": [
        "evidence_id",
        "source_payment_at",
        "mirror_of_source_id",
        "created_at",
        "confirmed_at"
      ],
      "mirror": {
        "allowed": false
      }
    },
    {
      "definition": "stagedPaymentBankFactSource",
      "payload_definition": "importedStagedPaymentBankFactPayload",
      "type": "staged_payment_bank_fact",
      "variant": "imported",
      "required_payload_fields": [
        "amount",
        "currency",
        "source_payment_at"
      ],
      "forbidden_payload_fields": [
        "evidence_id",
        "observed_at",
        "created_at",
        "confirmed_at"
      ],
      "mirror": {
        "allowed": true,
        "field": "mirror_of_source_id",
        "must_be_non_null_when_present": true
      }
    }
  ],
  "evidence_branches": [
    {
      "definition": "stagedPaymentBankPaymentEvidence",
      "payload_definition": "manualStagedPaymentEvidencePayload",
      "type": "staged_payment_bank_payment",
      "variant": "manual",
      "required_payload_fields": [
        "payment_id",
        "observed_at"
      ],
      "forbidden_payload_fields": [
        "source_payment_at",
        "mirror_of_evidence_id",
        "merged_into_evidence_link_id",
        "created_at",
        "confirmed_at"
      ],
      "mirror": {
        "allowed": false
      }
    },
    {
      "definition": "stagedPaymentBankPaymentEvidence",
      "payload_definition": "importedStagedPaymentEvidencePayload",
      "type": "staged_payment_bank_payment",
      "variant": "imported",
      "required_payload_fields": [
        "source_payment_at"
      ],
      "forbidden_payload_fields": [
        "observed_at",
        "created_at",
        "confirmed_at"
      ],
      "mirror": {
        "allowed": true,
        "fields": [
          "mirror_of_evidence_id",
          "merged_into_evidence_link_id"
        ],
        "must_appear_together": true,
        "must_be_non_null_when_present": true
      }
    }
  ],
  "mirror_branch": {
    "original_is_non_mirror": true,
    "original_precedes_mirror_in_frozen_source_collection": true,
    "source_payment_at_must_be_byte_for_byte_equal": true,
    "currency_must_match": true,
    "absolute_amount_must_match": true,
    "amounts_must_have_opposite_sign": true,
    "formal_effect": "none"
  },
  "rejection_dispatch": [
    {
      "legacy_operation_context": "group_creation",
      "action_type": "create_staged_payment",
      "operation_class": "rejection",
      "attempted_input_definition": "createStagedPaymentAttemptedInput",
      "outcome_definition": "createStagedPaymentRejectedOutcome"
    },
    {
      "legacy_operation_context": "payment_creation",
      "action_type": "record_staged_payment_installment",
      "operation_class": "rejection",
      "attempted_input_definition": "recordStagedPaymentInstallmentAttemptedInput",
      "outcome_definition": "recordStagedPaymentInstallmentRejectedOutcome"
    },
    {
      "legacy_operation_context": "payment_progress_transition",
      "action_type": "confirm_staged_payment_completion",
      "operation_class": "rejection",
      "attempted_input_definition": "confirmStagedPaymentCompletionAttemptedInput",
      "outcome_definition": "confirmStagedPaymentCompletionRejectedOutcome"
    }
  ],
  "action_rules": [
    {
      "action_type": "create_staged_payment",
      "operation_class": "creation",
      "input_definition": "createStagedPaymentInput",
      "required_input_fields": [
        "request_id",
        "kind",
        "total_amount",
        "currency",
        "category_id",
        "created_at"
      ],
      "rejection": {
        "operation_class": "rejection",
        "legacy_operation_contexts": ["group_creation"],
        "attempted_input_definition": "createStagedPaymentAttemptedInput",
        "outcome_definition": "createStagedPaymentRejectedOutcome",
        "sparse_attempted_input": true,
        "atomic_effect": {
          "status": "rejected",
          "deltas": "empty",
          "status_changes": [],
          "returned_ids": [],
          "state_effect": "none"
        },
        "cases": [
          {
            "fixture_id": "zero-total",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentAmountAttempt",
            "reason_code": "must_be_positive",
            "field_path": "$.attempted_input.total_amount"
          },
          {
            "fixture_id": "negative-total",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentAmountAttempt",
            "reason_code": "must_be_positive",
            "field_path": "$.attempted_input.total_amount"
          },
          {
            "fixture_id": "missing-category",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentCategoryAttempt",
            "reason_code": "secondary_category_required",
            "field_path": "$.attempted_input.category_id"
          },
          {
            "fixture_id": "primary-category",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentCategoryAttempt",
            "reason_code": "secondary_category_required",
            "field_path": "$.attempted_input.category_id"
          },
          {
            "fixture_id": "inactive-category",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentCategoryAttempt",
            "reason_code": "category_inactive",
            "field_path": "$.attempted_input.category_id"
          },
          {
            "fixture_id": "wrong-kind-category",
            "baseline_id": "opening_only",
            "attempted_input_definition": "createStagedPaymentCategoryAttempt",
            "reason_code": "expense_category_required",
            "field_path": "$.attempted_input.category_id"
          }
        ]
      }
    },
    {
      "action_type": "record_staged_payment_installment",
      "operation_class": "creation",
      "input_definition": "recordStagedPaymentInstallmentInput",
      "required_input_fields": [
        "request_id",
        "relation_id",
        "payment_role",
        "payment_amount",
        "currency",
        "funding_account_id",
        "actual_payment_at"
      ],
      "manual_confirmation": {
        "trigger": "record_staged_payment_installment_without_candidate_id",
        "confirmation_type": "explicit_manual_save",
        "confirmation_id_template": "confirmation-{payment_role}",
        "operation_id_source": "operation.id",
        "subject": {
          "kind": "operation",
          "id_source": "operation.id"
        },
        "transaction_version_confirmation_id_source": "confirmation.id",
        "returned_ids": [
          {"kind": "confirmation", "id_source": "confirmation.id"},
          {"kind": "transaction", "id_source": "transaction.id"},
          {"kind": "domain_entity", "id_source": "installment.id"}
        ],
        "fixture_replay": [
          {
            "fixture_operation_id": "save-deposit",
            "payment_role": "deposit",
            "legacy_relation_field": "association_group_id",
            "canonical_relation_field": "relation_id"
          },
          {
            "fixture_operation_id": "save-final",
            "payment_role": "final",
            "legacy_relation_field": "association_group_id",
            "canonical_relation_field": "relation_id"
          }
        ]
      },
      "rejection": {
        "operation_class": "rejection",
        "legacy_operation_contexts": ["payment_creation"],
        "attempted_input_definition": "recordStagedPaymentInstallmentAttemptedInput",
        "outcome_definition": "recordStagedPaymentInstallmentRejectedOutcome",
        "sparse_attempted_input": true,
        "atomic_effect": {
          "status": "rejected",
          "deltas": "empty",
          "status_changes": [],
          "returned_ids": [],
          "state_effect": "none"
        },
        "cases": [
          {
            "fixture_id": "zero-payment",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentAmountAttempt",
            "reason_code": "must_be_positive",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "negative-payment",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentAmountAttempt",
            "reason_code": "must_be_positive",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "deposit-equals-total",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentRoleAmountAttempt",
            "reason_code": "deposit_must_be_less_than_total",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "deposit-exceeds-total",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentRoleAmountAttempt",
            "reason_code": "deposit_must_be_less_than_total",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "final-not-remaining",
            "baseline_id": "after_deposit",
            "attempted_input_definition": "stagedPaymentRoleAmountAttempt",
            "reason_code": "final_must_equal_remaining_due",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "payment-exceeds-due",
            "baseline_id": "after_deposit",
            "attempted_input_definition": "stagedPaymentRoleAmountAttempt",
            "reason_code": "payment_exceeds_due",
            "field_path": "$.attempted_input.payment_amount"
          },
          {
            "fixture_id": "mixed-currencies",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentCurrencyAttempt",
            "reason_code": "single_currency_required",
            "field_path": "$.attempted_input.currency"
          },
          {
            "fixture_id": "unknown-funding-account",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentFundingAccountAttempt",
            "reason_code": "unknown_real_account",
            "field_path": "$.attempted_input.funding_account_id"
          },
          {
            "fixture_id": "nonfinancial-funding-account",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentFundingAccountAttempt",
            "reason_code": "real_financial_account_required",
            "field_path": "$.attempted_input.funding_account_id"
          },
          {
            "fixture_id": "non-owned-funding-account",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentFundingAccountAttempt",
            "reason_code": "owned_account_required",
            "field_path": "$.attempted_input.funding_account_id"
          },
          {
            "fixture_id": "liability-funding-account",
            "baseline_id": "group_only",
            "attempted_input_definition": "stagedPaymentFundingAccountAttempt",
            "reason_code": "asset_account_required",
            "field_path": "$.attempted_input.funding_account_id"
          }
        ]
      }
    },
    {
      "action_type": "change_staged_payment_fulfillment",
      "operation_class": "status_transition",
      "input_definition": "changeStagedPaymentFulfillmentInput",
      "required_input_fields": [
        "request_id",
        "relation_id",
        "fulfillment_status",
        "occurred_at"
      ]
    },
    {
      "action_type": "confirm_staged_payment_completion",
      "operation_class": "status_transition",
      "input_definition": "confirmStagedPaymentCompletionInput",
      "required_input_fields": [
        "request_id",
        "relation_id",
        "confirmed",
        "occurred_at"
      ],
      "rejection": {
        "operation_class": "rejection",
        "legacy_operation_contexts": ["payment_progress_transition"],
        "attempted_input_definition": "confirmStagedPaymentCompletionAttemptedInput",
        "outcome_definition": "confirmStagedPaymentCompletionRejectedOutcome",
        "sparse_attempted_input": true,
        "atomic_effect": {
          "status": "rejected",
          "deltas": "empty",
          "status_changes": [],
          "returned_ids": [],
          "state_effect": "none"
        },
        "cases": [
          {
            "fixture_id": "paid-in-full-while-due",
            "baseline_id": "after_deposit",
            "attempted_input_definition": "confirmStagedPaymentCompletionAttemptedInput",
            "reason_code": "due_must_be_zero",
            "field_path": "$.attempted_input.payment_progress"
          }
        ]
      }
    },
    {
      "action_type": "link_staged_payment_evidence",
      "operation_class": "reconciliation",
      "input_definition": "linkStagedPaymentEvidenceInput",
      "required_input_fields": [
        "source_id",
        "evidence_id",
        "payment_id",
        "posting_id"
      ]
    },
    {
      "action_type": "ingest_staged_payment_bank_fact",
      "operation_class": "creation",
      "input_definition": "ingestStagedPaymentBankFactInput",
      "required_input_fields": [
        "source_id",
        "evidence_id",
        "source_payment_at",
        "amount",
        "currency"
      ],
      "optional_input_fields": [
        "suggested_payment_role"
      ]
    },
    {
      "action_type": "confirm_staged_payment_candidate",
      "operation_class": "creation",
      "input_definition": "confirmStagedPaymentCandidateInput",
      "required_input_fields": [
        "request_id",
        "candidate_id",
        "relation_id",
        "payment_role",
        "category_id",
        "funding_account_id",
        "exact_binding_confirmed"
      ]
    },
    {
      "action_type": "merge_staged_payment_mirror_evidence",
      "operation_class": "reconciliation",
      "input_definition": "mergeStagedPaymentMirrorEvidenceInput",
      "required_input_fields": [
        "source_id",
        "evidence_id",
        "payment_id",
        "posting_id",
        "amount",
        "currency",
        "source_payment_at"
      ]
    }
  ]
}
```
<!-- rg06-closure-rules:end -->

## Acceptance

- The historical five target arrays are represented by separate normalized JSONPaths; the current candidate emits their frozen evidence identity only to `$.states[*].evidence[*].id`, and the cross-RG target-path format regression passes.
- RG-06 mapping inventory is `1188/1188` classified with `0` unclassified paths and `3610` leaf occurrences.
- The approved candidate has `status=approved`, `expected_output_gate=approved`, `1181` ready entries, `7` test-only exclusions and five `approved_implemented` resolved gaps.
- A machine check proves that prefix rewrite plus the explicit `18`-path replacement table leaves zero targets absent from the current JSON Schema inventory and no `planned_contract` target in the candidate map.
- A fixture-derived direct-branch check proves that staged source payloads omit `evidence_id`, canonical evidence owns the frozen ID, evidence source IDs use the exact source record and agree with links when linked, unlinked pending evidence remains zero-effect and unlinked, both candidate `oneOf` branches and their cross-branch rejection cases validate, candidate token rewriting is scoped, and each emitted confirmation preserves its frozen timestamp and subject.
- RG-06 contract, operation, semantic, mapping and expected regression tests pass with the proof-only validator outcome gate and without schema or product-contract changes.
- `test_golden_v2_mappings`, full Python tests, `project_docs`, `git diff --check` and the affected project verification pass on the frozen candidate.
- Independent specification review, independent quality review and distinct verification report no unresolved findings before integration.

## Next Decision

The mapping closure and expected artifact are approved by `D-081`. Adapter/replay, fixture rewrite, and publication are complete as separate gates; retain the independent evidence and finish the clean release requirement before integration.
