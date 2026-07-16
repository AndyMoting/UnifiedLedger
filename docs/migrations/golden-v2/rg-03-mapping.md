# RG-03 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-03.json`、`docs/specs/2026-07-15-rg-03-account-transfer-fee-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md` 与 `docs/GOLDEN_SCHEMA.md` 约束。它只定义 RG-03 v1 到 v2 的逐路径迁移，不授权 schema、adapter、fixture rewrite 或 expected output 生成。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `304`
- leaf occurrences: `560`
- classified/unclassified: `304/0`
- classifications: `map 143`, `preserve 22`, `derive 135`, `reject 4`
- dispositions: `ready 210`, `requires_contract_amendment 90`, `test_only_exclusion 4`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `case` | v2 case metadata, currency declaration, and one-to-one transfer root purposes |
| `catalog` | every complete state's account/category catalog, including explicit ownership and reconciliation eligibility |
| `opening` | opening transaction, version, posting set, postings, and replayed complete balances |
| `manual_create` | accepted explicit-manual-save operation, one formal `account_transfer` chain, projections, and posting reconciliation |
| `import_lifecycle` | ordered source intake, pending candidate, explicit candidate confirmation, mirror-evidence merge, and complete states |
| `unknown_one_sided_debit` | accepted incomplete intake with a pending candidate, zero formal effects, and a no-change retry |
| `invalid_manual_inputs` | independent rejected operations with sparse attempted input and exhaustive zero deltas |
| `idempotency` | no-change replays preserving the manual and imported complete states and all stable identities |
| `forbidden_side_effects` | test-only negative assertions; not serialized into v2 |
| `out_of_scope` | test-only scope assertions; combination transfer remains `future_draft` and the other named lifecycles remain outside RG-03 |

## IDs And Time

Existing stable account, category, transaction, version, posting-set, posting, request, source, evidence, candidate, and evidence-link IDs are preserved where v2 owns the same identity. Missing root, state, operation, opening version/posting-set, confirmation, candidate-status, and reconciliation IDs use the contract's deterministic migration helpers with normalized source locator plus a stable source ID, request ID, operation ID, invalid-case ID, or case ID discriminator. Array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The frozen RG-03 fixture and tests prove that the opening, manual-transfer, and confirmed-import `occurred_at` paths simultaneously carry occurrence, statistics attribution, and balance effectiveness. Under the approved RG-03 path-specific mapping, the exact timestamp text may therefore expand to `transaction_versions[*].occurred_at`, `statistics_at`, and `effective_at`. This is not a general migration default and must never generate `created_at` or `confirmed_at`. Source-record `observed_at` remains evidence time and never becomes an economic transaction time.

## Transfer Boundary

RG-03 v1 serializes only a one-to-one, same-currency transfer between two distinct user-owned real asset or liability accounts. The formal transaction uses the existing canonical `account_transfer` type and `transfer_principal_out`, `transfer_principal_in`, and `transfer_fee` posting roles. Principal remains internal; only the fee contributes to consumption, external cash outflow, and net-worth change. Combination transfer is retained only as the `future_draft` negative scope assertion and does not create v2 entities or operations.

## Unresolved Gaps

1. `RG03-GAP-01` (account-transfer operations, `65` affected paths): register closed manual creation, transfer-source intake, candidate confirmation, mirror-evidence merge, incomplete intake, rejected attempts, and no-change retry forms without admitting combination transfer into v1.
2. `RG03-GAP-02` (transfer source and candidate, `46` affected paths): register closed complete/incomplete transfer source payloads and account-transfer candidate payload/status history with exact one-to-one accounts, amounts, completeness, provenance, confidence, and confirmation requirements.
3. `RG03-GAP-03` (transfer evidence subtype, `4` affected paths): register only a closed transfer-record evidence subtype carrying source identity and `observed_at`. Existing evidence-link `target_kind`/`target_id`/`role` fields and independent posting-reconciliation records remain the canonical owners of target and match status.

The existing `account_transfer` transaction type, three transfer posting roles, typed evidence-link targets, and posting-reconciliation records are already sufficient and are not contract gaps. Complete balances, reports, operation deltas, transaction reconciliation summary, candidate status values, candidate confirmation identity, and deterministic migration IDs also use current contracts. Legacy fee-posting `not_applicable` maps to `reconciliation_eligible=false` plus canonical absence of a reconciliation record.

## Gate

- status: `needs_contract_amendment`
- expected output gate: `closed`
- unresolved gap count: `3`

Expected v2 output remains closed until all three gaps are approved and implemented. No schema change, adapter, expected output, or fixture rewrite is authorized by this mapping.
