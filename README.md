# UnifiedLedger

UnifiedLedger is an Android-first, local-first personal finance application that combines daily bookkeeping, bill imports, reconciliation, and audit history in one consistent ledger.

## Product Principles

- The formal ledger uses an `Account -> Transaction -> Posting` model.
- Manual entry, automatic capture, and imported bills share the same deterministic accounting core.
- Imported facts and inferred candidates remain separate from confirmed ledger records.
- Money uses exact decimal or integer minor-unit representations.
- Reconciliation records verification state without changing balances.
- Corrections preserve history; refunds and reversals remain separate economic events.
- Local storage is the default. Network services and synchronization are optional.
- Private financial data and machine-specific configuration are not stored in this repository.

## Current Modules

The first Python core checkpoint provides:

- exact money values;
- immutable transaction facts;
- evidence origin and confidence;
- conservative transaction-status evaluation.

## Tests

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest discover -s tests -t . -v
```

## Status

The project is in its accounting-core extraction phase. Application modules and the platform-independent production ledger will be added after the core rules and acceptance cases are stable.
