# ZZ File Manager — Step 2 Architecture

## Scope

Step 2 establishes the durable professional file-operation subsystem while preserving the approved Step 1 browser/storage architecture. It does not implement recycle bin, global search, archives, network/cloud providers, media editing, text editing, APK management, or final physical-device certification.

Verified Step 1 baseline: `2be3a6933931a1f7aa9ae3857e7adf2f910bca9e`.

Certified Step 2 functional implementation head: `9f5fd474f64cbbd43308a147479bdaa3668a1170`, GitHub Actions run `34346037170` (#159).

## Data flow

```text
BrowserScreen
    ↓
BrowserOperationsViewModel
    ↓
FileOperationController
    ↓
OperationStore / OperationRepository
    ↓
OperationJournal (SQLite)
    ↓
FileOperationEngine
    ↓
StorageProviderRegistry
    ↓
LocalStorageProvider / SafStorageProvider
```

Android execution is deliberately separated from operation logic:

```text
FileOperationController
    ↓ requestExecution()
OperationExecutionHost
    ↓
AndroidOperationExecutionHost
    ↓
OperationForegroundService (dataSync)
```

The engine remains independently JVM-testable and has no dependency on Compose, Activity, Service, or notification classes.

## Android execution policy

Step 2 operations are local-filesystem and Storage Access Framework transfers initiated directly by the user. They use a user-started foreground service rather than Android 14+ user-initiated JobScheduler mode, which is intended for user-requested network data transfers.

`OperationForegroundService` starts from the operation request, posts an ongoing file-operation notification, runs the provider-neutral engine, and updates notification progress from the persistent operation store.

For Android 15's time-limited `dataSync` foreground-service execution, `Service.onTimeout(startId, fgsType)` explicitly reconciles unsafe journal state before stopping. An unfinished operation becomes interrupted/recoverable instead of remaining falsely RUNNING or being marked successful.

## Provider write architecture

`StorageProvider` is the common read contract. `WritableStorageProvider` adds explicit capabilities for write, create file/directory, delete, rename, native move, random access, atomic rename, and timestamp support.

All operations use provider-neutral `FileReference` / `ScopedFileReference` values. SAF URIs are never converted into fake filesystem paths. Local operations retain the logical root in each scoped reference, and every local write path is canonicalized and checked against that root.

### Local provider

The local provider implements create file/folder, delete, rename, output streaming, collision lookup, free-space reporting, safe same-filesystem native move, case-only rename handling, symbolic-link awareness, and atomic staged replacement where the filesystem supports `ATOMIC_MOVE + REPLACE_EXISTING`.

### SAF provider

The production SAF provider uses `DocumentsContract`, `ContentResolver`, and scoped tree permissions. Directory enumeration uses tree-aware child-document queries. Creation uses `DocumentsContract.createDocument()`, and rename/finalization uses `DocumentsContract.renameDocument()` so providers that change document IDs on rename are handled correctly.

SAF document IDs are treated as opaque values. Mutation capability reporting honors provider flags rather than assuming a writable URI implies every mutation is possible. Unknown SAF free capacity is treated as unknown, not as zero.

API-35 instrumentation exercises the unchanged production `SafStorageProvider` against a debug-only deterministic `DocumentsProvider`. The test grant substitutes only unreliable headless picker UI automation; resolver calls and production provider logic still execute through Android scoped-tree rules.

## Operation model and persistent journal

Operations have stable UUID-backed IDs and explicit states:

- `QUEUED`
- `PREPARING`
- `WAITING_FOR_USER`
- `RUNNING`
- `PAUSING`
- `PAUSED`
- `CANCELLING`
- `CANCELLED`
- `COMPLETED`
- `COMPLETED_WITH_WARNINGS`
- `FAILED`
- `INTERRUPTED`

Operation types implemented in Step 2 are copy, move, permanent delete, rename, batch rename, create directory, and create empty file.

`OperationRepository` is FIFO by creation time and persists typed operation snapshots through `OperationJournal`. Important persisted boundaries include queueing, preparation/running state, throttled progress, item completion, pause/cancel, collision wait, failure, transactional rename/replace phase changes, interruption and terminal completion.

Process death never converts unfinished work to success. Unsafe runtime states reconcile to `INTERRUPTED`; completed item boundaries remain recorded while unsafe current-file offsets are discarded when correct byte-level continuation cannot be proven.

### Terminal-completion invariant

Terminal success is permitted only when completion is proven. `FileOperationEngine.finishFromItems()` refuses `COMPLETED`/`COMPLETED_WITH_WARNINGS` while any item is still `QUEUED` or `RUNNING`, any item has `replacePhase != NONE`, `batchRenameRollbackRequired` is true, or a batch-rename item has an unresolved transactional phase. Such a snapshot is persisted as `INTERRUPTED` with no completion timestamp so recovery must finish before success can be reported.

This is a defense-in-depth invariant above the individual transaction coordinators: even if a lower-level mutation stops at an unexpected journal boundary, the operation-level finalizer cannot convert that unresolved state into success.

## Copy and move safety

File content is copied with a fixed bounded buffer of **256 KiB** by default. No operation uses `readBytes()` or allocates memory proportional to source size. All byte counters are `Long`.

For providers that support safe rename finalization, copy writes to a hidden `.zzpart-*` output, closes/flushes it, verifies expected byte count when known, and only then exposes the final filename.

Cross-provider move is:

```text
copy source → destination
verify destination completion
then delete source
```

If copy fails, source remains intact. If copy succeeds but source deletion fails, the item completes with a warning rather than falsely claiming a clean move. Same-provider local moves may use native move only after the provider confirms feasibility.

Free-space planning accounts for this distinction: a native move can proceed without requiring a second full payload copy, while a fallback move must satisfy copy-space requirements before allocation.

## Durable Replace transaction

Replacing an existing file is capability-gated. `Replace` is offered only if the provider can either perform atomic replacement or support the full reversible rename+delete transaction. Rename-only providers can still safely finalize Keep-Both but do not advertise unsafe Replace.

The engine never deletes the known-good destination before a complete replacement has been staged.

For atomic-capable local storage:

```text
stage replacement
verify size
atomic replace existing destination
```

For providers without atomic replace but with safe rename+delete support, `ReplaceTransactionCoordinator` uses a durable reversible protocol:

```text
journal BACKUP_PLANNED + old destination snapshot + staged ref
rename old destination → hidden .zzreplace-backup-* name
journal BACKED_UP
journal COMMITTING
rename staged output → final name
journal returned final reference while phase remains COMMITTING
verify committed size
journal COMMITTED
remove safety backup
clear Replace ledger
```

The post-rename journal write is deliberate. After `staged → final` mutates provider state, the returned final reference is persisted immediately while the transaction is still `COMMITTING`, before post-commit verification. Therefore a verification exception cannot leave the only proof of the new final in transient memory.

If post-commit verification fails after the final-name mutation, the coordinator persists the operation as `INTERRUPTED`, keeps the original safety-backup reference and the final result reference, and raises recovery-required control flow. It never returns a still-running snapshot that can be interpreted as successfully complete.

The operation item persists the phase, final name, original destination reference/size/modified timestamp, backup name/reference, staged partial reference, and when available the mutated final result reference. These fields are serialized by `OperationJsonCodec`, so process recreation does not lose the transaction ledger.

If process/service cancellation occurs during a destructive boundary, `CancellationException` propagates without speculative rollback in the cancelled coroutine. The persisted phase is left intact for deterministic recovery on resume.

Recovery is conservative. It compares final/backup/staged existence, the journaled result reference, and the known original destination snapshot. When it can prove the old file is still the valid destination, it resets safely. When it can prove the old file is in the safety backup and no committed final exists, it restores the original name before retrying. When it can prove staged content became the final candidate, it verifies that candidate before committing the ledger. If verification fails and the safety backup exists, recovery can remove the unverified final and restore the original; if the state cannot be proven safe, the operation remains `INTERRUPTED` and preserves the ledger rather than guessing or deleting data.

A failed backup cleanup after commit also remains recoverable instead of silently orphaning the hidden backup. Retry never clones an unfinished Replace transaction into a new operation; the existing ledger must be reconciled in place first.

## Transactional batch rename

`BatchRenamePlanner` previews find/replace, prefix, suffix and sequential numbering rules before enqueueing. Proposed names are validated for invalid leaf names and duplicates.

Execution uses persisted per-item phases and unique temporary names. Phase 1 moves every selected item from original → temporary. Phase 2 moves temporary → final. Current references and phases are saved at every mutation boundary.

If execution fails, rollback first moves any final-name items back to their unique temporary namespace and then restores temporary → original in reverse order. If rollback itself is interrupted or fails, `batchRenameRollbackRequired` remains true with live references/phases preserved; resume continues rollback before any new forward execution.

Process-death regressions cover interruption after original→temp mutation, between temp/final phases, and after a final rename mutation before its journal save.

## Directory traversal and collision handling

Tree preparation uses iterative `ArrayDeque` traversal rather than unbounded recursion. Empty directories are represented as operation items. Local symbolic links are not recursively followed, preventing loops and root escapes.

Copy/move rejects folder → self and folder → descendant targets before destructive execution.

Collision handling distinguishes file→file, directory→directory, file→directory, directory→file and same-resource cases. Supported choices are capability/kind dependent: Replace, Skip, Keep both, Merge directories and compatible Apply-to-all. Keep-both naming preserves extensions (`report.pdf` → `report (1).pdf`). A collision persists `WAITING_FOR_USER`; no worker thread blocks on a modal decision.

## Selection, clipboard and UI

`BrowserOperationsViewModel` owns stable-ID selection and reconciles it after refresh. Long press enters selection mode, taps toggle while selected, Select-all targets displayed entries, and Back exits selection before navigation.

`OperationClipboardRepository` persists COPY/CUT references, origin and timestamp. CUT items are visually dimmed; source deletion occurs only during successful move execution. Virtual/read-only destinations do not enable Paste/Create.

The browser exposes delete confirmation, rename, batch-rename preview, create file/folder, collision dialog, share/properties, and an operations sheet with state/progress/pause/resume/cancel/retry controls.

## Progress, large files and recovery

Progress persistence/events are throttled at a 500 ms default plus meaningful item/state boundaries. Unknown totals remain indeterminate rather than fabricating a percentage.

There is no application-level 2 GB/4 GB/10 GB/30 GB ceiling. Size/progress fields are `Long`, streaming memory is bounded, Replace ledger fields retain large `Long` values, and tests model values above 30 GiB without allocating giant CI artifacts. A 30 GiB logical native-move regression confirms a same-provider native move is not incorrectly blocked by only 1 GiB free space.

Byte-level process-death resume is not universally claimed. Completed item/file boundaries survive interruption; an interrupted current file restarts after revalidation when safe random-access continuation cannot be proven.

## Security

Step 2 preserves and expands Step 1 storage safety:

- canonical local-root containment
- safe leaf-name validation
- no path separators/NUL in generated names
- no shell command construction from file names
- no SAF URI-to-path conversion
- opaque SAF document IDs
- SAF authorized-tree containment checks
- provider mutation flags honored before writes
- no recursive symbolic-link following
- self/descendant copy validation
- provider-scoped references persisted instead of Android framework objects
- no visible partial final filename on providers without safe finalization
- durable safety backups are never treated as disposable `.zzpart-*` data
- no successful terminal state while active or transactional state is unresolved

## Automated certification

Functional implementation head `9f5fd474f64cbbd43308a147479bdaa3668a1170` passed GitHub Actions run `34346037170` (#159):

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

The combined build/JVM/lint/release/instrumentation-compile phase reported `BUILD SUCCESSFUL`. The API-35 emulator then executed **9 tests, 0 skipped, 0 failed** and also reported `BUILD SUCCESSFUL`.

The JVM suite on this exact head includes the final regressions for post-commit Replace verification failure, operation finalization with a RUNNING item, operation finalization with an unresolved Replace ledger, and process death after staged→final mutation before the `COMMITTED` journal save.

## Physical-device boundary

Physical phone certification intentionally deferred to Step 7 per project plan.
