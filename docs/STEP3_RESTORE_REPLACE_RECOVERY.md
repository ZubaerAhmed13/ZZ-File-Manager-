# Step 3 — Restore + Replace Crash-Recovery Resolution

## Blocker resolved

The independent Step 3 review identified an unjournaled mutation window in app-managed Recycle Bin Restore + Replace:

```text
staged recycle payload
    ↓
replaceAtomically(...)
    ↓
filesystem commit succeeds
    ↓
PROCESS DEATH
    ↓
TrashRecord removal/finalization never executes
```

The previous catalog could then retain `RESTORING` + `restoreReplace=true` while `.zzrestore-<id>` had already become the final filename. Because a different same-named file legitimately existed before Replace, destination existence alone was not proof that the restore committed.

## Corrected durable protocol

Restore + Replace now persists an explicit ledger:

```text
NONE
  ↓
STAGING       — persisted before recycle payload leaves its container
  ↓
STAGED        — hidden .zzrestore-<id> exists; mutation identity captured
  ↓
COMMITTING    — persisted before replaceAtomically()
  ↓
COMMITTED     — final entry has been proven to carry the staged identity
  ↓
remove TrashRecord / restore related metadata
```

Persisted recovery fields:

- `restoreReplacePhase`
- `restoreStageName`
- `restoreCommitIdentity`
- `restoreCommittedReference`
- existing `restoreDestination`, `restoreName`, `restoreReplace`, and `trashReference`

The JSON-backed trash catalog remains backward compatible; no destructive database migration is used.

## Stable commit proof

A final filename is never accepted merely because it exists.

`StorageProvider.mutationIdentity()` is an optional provider contract for a stable underlying-object identity across rename/atomic move. `LocalStorageProvider` implements it with `BasicFileAttributes.fileKey()`.

Before the atomic boundary the staged payload identity is durably stored. After restart, a `COMMITTING`/`COMMITTED` Replace is finalized only when:

```text
final entry exists
AND
final mutation identity == durably recorded staged mutation identity
```

If identity is unavailable or does not match, Step 3 does not guess success. The record remains `INTERRUPTED` with recovery evidence preserved.

## Staging crash window

The earlier stage move is also journaled before mutation:

```text
persist STAGING + .zzrestore-<id>
    ↓
move recycle payload → .zzrestore-<id>
    ↓
persist STAGED + mutation identity
```

If Android dies after the move but before the second write, startup checks both the recycle container and the planned stage name. A found stage is adopted into the ledger rather than orphaned.

## Backward compatibility for old in-flight records

Old Step 3 records can have `restoreReplace=true` but no new phase/identity fields. Reconciliation treats them conservatively:

- old payload still in recycle container → reset to safe `TRASHED` state;
- `.zzrestore-<id>` still exists → adopt it as `STAGED` and capture identity where possible;
- stage missing and only final same-name item exists → keep `INTERRUPTED`; do **not** infer success without durable proof.

## No large-file sacrifice

The corrected flow keeps the existing same-storage native-move staging and atomic replacement design. It does not copy a 30 GiB payload merely to make Restore + Replace recoverable, does not load file contents into RAM, and does not require a second full-size payload.

## Deterministic certification added

`RestoreReplaceCrashRecoveryTest` covers:

1. normal identity-backed Restore + Replace success;
2. exact blocker window: atomic replacement succeeds, process dies before post-commit catalog write;
3. stage move succeeds, process dies before STAGED catalog write;
4. same-named final entry with the wrong identity is never guessed as a successful restore.

`RestoreReplaceLedgerInstrumentationTest` verifies the COMMITTING ledger, stage name, mutation identity, committed reference, and 30 GiB `Long` size survive SQLite repository recreation.

## Safety invariant

The Step 3 rule is now enforced for Replace as well:

> If the application cannot prove the Restore + Replace terminal state, it preserves the durable record and reports an interrupted/unresolved transaction instead of claiming success or failure from filename existence alone.

Physical phone certification remains intentionally deferred to Step 7.
