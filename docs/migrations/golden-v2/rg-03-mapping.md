# RG-03 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-03.json`、`docs/specs/2026-07-15-rg-03-account-transfer-fee-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md` 与 `docs/GOLDEN_SCHEMA.md` 约束。它只定义 RG-03 v1 到 v2 的逐路径迁移，不授权 schema、adapter、fixture rewrite 或 expected output 生成。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `304`
- leaf occurrences: `560`
- classified/unclassified: `304/0`
- classifications: `map 143`, `preserve 22`, `derive 135`, `reject 4`
- dispositions: `ready 300`, `test_only_exclusion 4`; all `304` normalized paths are classified, and test-only exclusions are not counted as executable ready paths

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

Existing stable account, category, transaction, version, posting-set, posting, request, source, evidence, candidate, and evidence-link IDs are preserved where v2 owns the same identity. Returned transaction identities are operation-owned and map to `$.operations[*].returned_ids[*].id`, with no substitute mapping through state transaction collections. Missing root, state, operation, opening version/posting-set, confirmation, candidate-status, and reconciliation IDs use the contract's deterministic migration helpers with normalized source locator plus a stable source ID, request ID, operation ID, invalid-case ID, or case ID discriminator. Array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The frozen RG-03 fixture and tests prove that the opening, manual-transfer, and confirmed-import `occurred_at` paths simultaneously carry occurrence, statistics attribution, and balance effectiveness. Under the approved RG-03 path-specific mapping, the exact timestamp text may therefore expand to `transaction_versions[*].occurred_at`, `statistics_at`, and `effective_at`. This is not a general migration default and must never generate `created_at` or `confirmed_at`. Source-record `observed_at` remains evidence time and never becomes an economic transaction time.

## Transfer Boundary

RG-03 v1 serializes only a one-to-one, same-currency transfer between two distinct user-owned real asset or liability accounts. The formal transaction uses the existing canonical `account_transfer` type and `transfer_principal_out`, `transfer_principal_in`, and `transfer_fee` posting roles. Principal remains internal; only the fee contributes to consumption, external cash outflow, and net-worth change. Combination transfer is retained only as the `future_draft` negative scope assertion and does not create v2 entities or operations.

## Closed Gaps

1. `RG03-GAP-01` is closed: registered operation forms cover manual creation, complete and incomplete intake, candidate confirmation, mirror-evidence merge, all ten frozen manual rejections, and originating-action retries.
2. `RG03-GAP-02` is closed: complete and incomplete transfer source/candidate payloads, provenance, confidence, confirmation requirements, candidate history, and the mirror-only `account_credit_observation` source payload are closed. Incomplete `destination_account_id:null` is mapped to destination-field omission/absence, never to a serialized null destination.
3. `RG03-GAP-03` is closed: `transfer_record` evidence retains only source identity and observation time; evidence links and posting reconciliation own targets and matching state. Mirror source records never coerce into complete transfer sources.

The existing `account_transfer` transaction type, three transfer posting roles, typed evidence-link targets, and posting-reconciliation records are already sufficient and are not contract gaps. Complete balances, reports, operation deltas, transaction reconciliation summary, candidate status values, candidate confirmation identity, and deterministic migration IDs also use current contracts. Legacy fee-posting `not_applicable` maps to `reconciliation_eligible=false` plus canonical absence of a reconciliation record.

## Gate

- status: `approved`
- expected output gate: `closed`
- unresolved gap count: `0`

Expected v2 output remains closed for the next approved generation stage; expected has not yet been generated. This closed mapping authorizes neither an adapter nor a v1 fixture rewrite.
