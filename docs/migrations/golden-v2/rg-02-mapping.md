# RG-02 Golden Schema v2 Mapping

## Authority

本映射受 `golden/rules/rg-02.json`、`docs/specs/2026-07-14-rg-02-normal-income-design.md`、`docs/GOLDEN_TESTS.md`、`docs/ACCOUNTING_RULES.md` 与 `docs/GOLDEN_SCHEMA.md` 约束。它定义 RG-02 v1 到 v2 的逐路径迁移，并生成静态完整产物 `docs/migrations/golden-v2/rg-02-expected.json` 供独立复审；不授权 adapter 或 v1 fixture rewrite。

## Inventory

从 `$` 深度优先遍历 source JSON；对象成员写作 `.key`，数组元素归一化为 `[*]`，scalar、`null`、空数组和空对象均计为 leaf。相同 normalized path 聚合 value kind 与 occurrence count。

- normalized paths: `154`
- leaf occurrences: `368`
- classified/unclassified: `154/0`
- classifications: `map 56`, `preserve 22`, `derive 75`, `reject 1`
- dispositions: `ready 153`, `requires_contract_amendment 0`, `test_only_exclusion 1`

## Section Mapping

| v1 section | v2 ownership |
| --- | --- |
| `schema_version` / `case` | required `contract` and `contract_version` envelope, v2 case metadata, and currency declaration |
| `catalog` | every complete state's account/category catalog |
| `opening` | opening transaction, version, posting set, postings, and replayed balances |
| `create` | accepted manual-income operation, confirmation, formal income chain, projections, and reconciliation |
| `category_rename` | closed update operation plus catalog transition between complete states; display path and transaction category association derive from legal catalog/posting facts; closed `category_name_history` records preserve every name version |
| `idempotency` | no-change retry over the original request and unchanged complete state |
| `invalid_inputs` | independent rejected operations with sparse attempted input and zero deltas |
| `variants` | independent accepted manual-income roots using the same formal and projection rules |
| `forbidden_side_effects` | test-only negative assertions; not serialized into v2 |

## IDs And Time

Existing stable account, category, transaction, version, posting-set, posting, request, and variant IDs are preserved where v2 owns the same identity. Missing root, state, operation, opening version/posting-set, confirmation, and reconciliation IDs use the contract's deterministic migration helpers with normalized source locator plus a stable source ID/request ID/case ID discriminator; array index, display name, traversal order, runtime time, and local path are forbidden discriminators.

The frozen RG-02 fixture and tests prove that the five normalized `occurred_at` paths in opening, create, and variants simultaneously carry occurrence, statistics attribution, and balance effectiveness. Under the approved RG-02 path-specific rule, the exact timestamp text may therefore expand to `transaction_versions[*].occurred_at`, `statistics_at`, and `effective_at`. This is not a general migration default and must never generate `created_at` or `confirmed_at`.

The v1 `schema_version` is a source-dialect discriminator. It maps to the required v2 envelope values `contract="unifiedledger.golden-case"` and `contract_version="2.0.0"`; v2 has no root `schema_version` field.

For every income classification posting, v1 `reconciliation_status="not_applicable"` maps to `postings[*].reconciliation_eligible=false` and canonical absence from `posting_reconciliations`. Only receiving owned-real-account postings receive a posting-reconciliation record. No `not_applicable` status token is serialized.

## Closed Contract Owners

1. `RG02-GAP-01` is closed by strict accepted/no-change `manual_income` input, closed sparse rejected attempted input using `receiving_account_id`, and income-specific category/account validation.
2. `RG02-GAP-02` is closed by the exact `income_classification` negative leg and `receiving_asset` positive owned-real-account leg, including receiving-posting-only reconciliation.
3. `RG02-GAP-03` is closed by the `category_rename` operation and `states[*].catalog.category_name_history[*]` records containing `category_id`, `name`, `version`, and `status`. Rename changes only the category name/history and produces zero financial, report, or reconciliation effects.

The static expected output contains the opening/main chain, category rename, idempotent retry, eight independent invalid-input roots, and both independent accepted variants. Every complete state is schema-valid and passes the complete semantic validator; all generated migration IDs are reproducible from normalized source locators and stable occurrence discriminators.

## Gate

- status: `approved`
- expected output gate: `completed`
- unresolved gap count: `0`

All three approved amendments are implemented and the contract gap count is zero. The expected output passed independent review and received explicit user approval, so its approval status is `approved` and the expected-output gate is `completed`. The RG-02 collapsed-time decision remains path-specific: each frozen economic timestamp expands only to `occurred_at`, `statistics_at`, and `effective_at`.
