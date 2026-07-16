# RG-02 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-02.json`、`docs/specs/2026-07-14-rg-02-normal-income-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md` 与 `docs/GOLDEN_SCHEMA.md` 约束。它只定义 RG-02 v1 到 v2 的逐路径迁移，不授权 adapter、fixture rewrite 或 expected output 生成。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `154`
- leaf occurrences: `368`
- classified/unclassified: `154/0`
- classifications: `map 56`, `preserve 22`, `derive 75`, `reject 1`
- dispositions: `ready 111`, `requires_contract_amendment 42`, `test_only_exclusion 1`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `schema_version` / `case` | required `contract` and `contract_version` envelope, v2 case metadata, and currency declaration |
| `catalog` | every complete state's account/category catalog |
| `opening` | opening transaction, version, posting set, postings, and replayed balances |
| `create` | accepted manual-income operation, confirmation, formal income chain, projections, and reconciliation |
| `category_rename` | catalog transition between complete states; display path and transaction category association derive from legal catalog/posting facts; explicit name history remains a GAP-03 planned contract |
| `idempotency` | no-change retry over the original request and unchanged complete state |
| `invalid_inputs` | independent rejected operations with sparse attempted input and zero deltas |
| `variants` | independent accepted manual-income roots using the same formal and projection rules |
| `forbidden_side_effects` | test-only negative assertions; not serialized into v2 |

## IDs And Time

Existing stable account, category, transaction, version, posting-set, posting, request, and variant IDs are preserved where v2 owns the same identity. Missing root, state, operation, opening version/posting-set, confirmation, and reconciliation IDs use the contract's deterministic migration helpers with normalized source locator plus a stable source ID/request ID/case ID discriminator; array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The frozen RG-02 fixture and tests prove that the five normalized `occurred_at` paths in opening, create, and variants simultaneously carry occurrence, statistics attribution, and balance effectiveness. Under the approved RG-02 path-specific rule, the exact timestamp text may therefore expand to `transaction_versions[*].occurred_at`, `statistics_at`, and `effective_at`. This is not a general migration default and must never generate `created_at` or `confirmed_at`.

The v1 `schema_version` is a source-dialect discriminator. It maps to the required v2 envelope values `contract="unifiedledger.golden-case"` and `contract_version="2.0.0"`; v2 has no root `schema_version` field.

For every income classification posting, v1 `reconciliation_status="not_applicable"` maps to `postings[*].reconciliation_eligible=false` and canonical absence from `posting_reconciliations`. Only receiving owned-real-account postings receive a posting-reconciliation record. No `not_applicable` status token is serialized.

## Unresolved Gaps

1. `RG02-GAP-01` (`manual_income`): register strict accepted/no-change input and closed sparse rejected attempted input using `receiving_account_id`, including income-category validation.
2. `RG02-GAP-02` (income posting roles): register the negative income-classification leg and positive receiving owned-real-account leg.
3. `RG02-GAP-03` (`category_rename`): register a closed rename operation and closed `category_name_history` records containing `category_id`, `name`, `version`, and `status`, without creating financial changes. Name-history status/version are not operation outcome/sequence fields; the map names their future owner under `$.planned_contract.states[*].catalog.category_name_history[*]`.

## Gate

- status: `needs_contract_amendment`
- expected output gate: `closed`
- unresolved gap count: `3`

Expected v2 output remains closed until all three gaps are approved and implemented. The RG-02 collapsed-time decision is already closed and is not an unresolved gap.
