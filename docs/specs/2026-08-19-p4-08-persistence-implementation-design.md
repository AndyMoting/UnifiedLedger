# P4-08 persistence and reconciliation implementation batch

**Status:** proposal (revised after independent specification review)

## 1. Scope and authority

This batch implements the approved D-103 RL-07 contract. The pure `P408Matcher`
continues to return proposals only. A separate explicit-confirmation port persists
only a user-confirmed match; no matcher result, link, or reconciliation state can
create a formal transaction or change financial totals.

The shared schema is additive to v22 and does not alter any `rgXX_` silo. The
scenario contract, not a global table constraint, owns evidence cardinality. RL-07
registers exactly one evidence-to-posting link per evidence (evidence:posting =
1:1) and permits the two evidence links to reference postings in the same formal
transfer (evidence:transaction = many-to-one). Correction/rematching is
represented by append-only link events and reconciliation history.

## 2. Request and candidate identity

Reconciliation operations have their own `reconciliation_request`,
`reconciliation_request_snapshot`, and `reconciliation_receipt` tables. They do
not reuse `import_request` because its operation CHECK and receipt shape are frozen
to import-spine semantics.

The request snapshot stores structured, canonical fields: ledger/evidence/candidate
IDs, posting ID, transaction ID, evidence responsibility (`real_account_posting` or
`destination_asset_posting`), basis version, sorted basis field tokens, window days,
natural-day distance, source occurred-at raw text, confirmation-at raw text, and the
human decision. Canonical serialization is UTF-8 with `|`-separated ASCII field
names, sorted set tokens, and exact source text; no Clock value is substituted for a
source time. Equivalent retry compares every snapshot column and returns the first
receipt. `link_id`, `reconciliation_id`, and `created_at` are output/generated IDs
and are excluded from the fingerprint, so an equivalent retry carrying different
output IDs is `NoChange` and returns the original receipt. A changed semantic field
is a typed conflict with zero writes.

The candidate ID is a proposal identity, not an `import_candidate` foreign key:
the pure matcher candidate is transient. The snapshot binds that identity to the
exact posting and basis that the user confirmed, so replay and audit are verifiable.

## 3. Shared tables

### 3.1 `evidence_link` and `evidence_link_history`

`evidence_link` is an immutable link fact. It has no global evidence or posting
UNIQUE constraint because other approved scenario contracts may register different
cardinality. Columns are `ledger_id`, `link_id`, `evidence_id`, `posting_id`,
`transaction_id`, `responsibility`, `basis_version`, `match_basis`, `candidate_id`,
`request_id`, and `created_at`. Responsibility is restricted to the approved duties
`real_account_posting` and `destination_asset_posting`.

For RL-07 the registered cardinality is **evidence:posting = 1:1** (one evidence
links to exactly one posting) and **evidence:transaction = many-to-one** (the two
different evidence links may reference postings in the same formal transaction).
`evidence_link.transaction_id` makes the shared formal transaction explicit and is
written from the confirmed posting's transaction. The confirmation port enforces
that an evidence has at most one active link; it does not allow the same evidence to
link to multiple postings.

`evidence_link_history` is append-only and records `(link_id, sequence, state,
reason, request_id, occurred_at)`. `state` is `active` or `invalidated`; sequence 1
must be `active`. This implementation batch exposes confirmation and read projection
only. Correction/successor operations remain a follow-up contract: when authorized,
they will append an invalidation event and successor link without mutating the
predecessor. RL-07 confirmation enforces its registered per-responsibility cardinality
in the application transaction; other scenarios may register different cardinality.

### 3.2 `posting_reconciliation` and history

`posting_reconciliation` is one current projection per `(ledger_id, posting_id)`.
It is initialized as `PENDING` with sequence 1, and may be updated only by the
store together with a same-sequence append in `posting_reconciliation_history`.
The update trigger requires `new.latest_sequence = old.latest_sequence + 1` and
requires a matching history row. Deletes are forbidden. History is append-only,
has consecutive sequences, and its foreign keys bind the link and request to the
same ledger; the store additionally checks that a referenced link targets the same
posting.

Stable status tokens are `PENDING`, `PARTIAL`, `DIFFERENCE`, `MISSING`, and
`CHECKED`, mapped respectively to the approved meanings 待对账、部分匹配、有差异、待补资料、已核对.
The read projection exposes these as a typed `P408ReconciliationStatus` enum with
the approved labels rather than raw storage tokens. For RL-07, each confirmed
responsibility is evaluated against the exact posting it proves: that posting
becomes `CHECKED` after its first accepted link. A transaction may therefore
aggregate to `PARTIAL` when its other real posting remains pending. Explicit no-match
or correction requests may append `MISSING` or `DIFFERENCE` only with their typed
reason. No automatic transition is performed by the matcher.

## 4. Eligibility and migration

The current-posting read query joins `posting` through the current transaction
version and the owning `ledger_transaction`, and accepts only postings whose
effective transaction kind is `ACCOUNT_TRANSFER`
(`COALESCE(ledger_transaction.canonical_kind, ledger_transaction.kind) =
'ACCOUNT_TRANSFER'`). This shared product qualification does not depend on any
`rgXX_` semantic table, so product P4-04/P4-05 postings are eligible. The exact
predicate is frozen in the SQL query and tested against current-version and ledger
ownership; stale-version, non-transfer, category/expense, and cross-ledger rows are
rejected. This is a read qualification, not a new account catalog.

Migration `22.sqm` creates the five shared tables, indexes, triggers, and a
migration audit request/snapshot owner. For every current posting whose effective
transaction kind is `ACCOUNT_TRANSFER` it inserts one
`posting_reconciliation(PENDING, sequence=1)` and one matching history row owned by
that migration request. No evidence links or statuses are inferred from old RG rows.
The migration audit owner is a stable internal token and is not exposed as an
application receipt. Fresh `Ledger.sq` and migrated v23 must have identical shared
objects and the same current-ACCOUNT_TRANSFER PENDING seed predicate.

## 5. Atomic confirmation and correction

The winning confirm operation claims its request, writes the structured snapshot,
link and link-history row, appends reconciliation history, advances the current
projection, and writes its receipt in one transaction. It checks ledger/transaction
ownership, current-version and ACCOUNT_TRANSFER qualification, exact funding facts,
responsibility-to-posting-side binding (`REAL_ACCOUNT_POSTING` only with
out/negative, `DESTINATION_ASSET_POSTING` only with in/positive), the fixed ±2-day
window, canonical basis, and RL-07 scenario cardinality. RL-07 enforces
evidence:posting = 1:1: once an evidence has any active link, another confirmation
for the same evidence is rejected (typed `P408_EVIDENCE_ALREADY_LINKED`) regardless
of transaction. Evidence:transaction = many-to-one is carried explicitly by
`evidence_link.transaction_id`; each request must supply the `transaction_id` that
matches the confirmed posting's transaction (typed `P408_TRANSACTION_ID_MISMATCH`),
and two different evidence links may reference postings in the same formal
transaction without creating a second transfer. A mismatched `reconciliation_id` is
rejected unconditionally. Any typed failure, unique conflict, FK failure, or
callback failure rolls back the request claim and every derived row. Equivalent
replay returns all original IDs and appends nothing. A different request with an
already-active exclusive RL-07 responsibility returns a typed conflict and writes
nothing.

The deferred correction operation must never mutate or delete the old link/history.
It will append an invalidation event, create a successor link, and append the
resulting posting reconciliation state. Financial balances, transaction versions,
and report financial dimensions remain unchanged.

## 6. Report and canonical oracle

The report adds a read-only reconciliation dimension derived from current postings,
current reconciliation rows, and latest active link events. It contains stable,
sorted rows `(posting_id, transaction_id, account_id, status, active_link_ids)` and
is excluded from all financial reducers. The canonical oracle serializes every row
as typed, NULL-preserving pipe fields, sorts by `(ledger_id, table, primary-key
columns)`, and compares pre/post snapshots, receipt IDs, all link/history rows,
current/history reconciliation rows, existing financial dimensions, and the
reconciliation dimension after close/reopen. It asserts that the second RL-07 mirror
adds evidence lineage only and no second transaction, posting, balance, or financial
report effect.

## 7. Acceptance matrix

R1 unique proposal plus explicit responsibility confirmation; R2 mirror
confirmation to the other posting of the same formal transfer; R3 in-window competitor yields
`AMBIGUOUS` and zero writes; R4 unresolved temporal facts yield `UNRESOLVED` and
zero writes; R5 changed request retry is conflict and zero writes; R6 equivalent
retry returns the original receipt; R7 cross-ledger/old-version/ineligible posting
is rejected; R8 v22→v23 migration and reopen preserve all old rows and seed only
qualified PENDING projections. Channel totals remain diagnostic only.

This batch implements R1, R2, R5, R6, R7, R8 confirmation/read semantics. Correction
successor behavior is explicitly deferred to a follow-up batch and is not claimed by
these tests. Implementation must preserve this proposal's exact responsibilities, request owner,
candidate snapshot, correction lineage, eligibility query, migration seed, and
canonical ordering. Any change requires a new review gate.
