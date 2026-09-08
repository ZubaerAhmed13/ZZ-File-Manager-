# ZZ File Manager — Step 2 Architecture

## Scope

Step 2 establishes the durable professional file-operation subsystem while preserving the approved Step 1 browser/storage architecture. It does not implement recycle bin, global search, archives, network/cloud providers, media editing, text editing, APK management, or final physical-device certification.

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
OperationJournal (SQLite v1)
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

Step 2 operations are local-filesystem and Storage Access Framework transfers initiated directly by the user. They therefore use a user-started foreground service rather than Android 14+ `JobInfo.Builder.setUserInitiated(true)`: Android's user-initiated JobScheduler mode is intended for user-requested **network data transfers**, not arbitrary local/SAF file copies.

`OperationForegroundService` starts immediately from the user's operation request, posts a dedicated ongoing file-operation notification, runs the provider-neutral engine, and updates notification progress from the persistent operation store.

For Android 15's time-limited `dataSync` foreground-service execution, `Service.onTimeout(startId, fgsType)` is handled explicitly. Before stopping, the persistent journal is reconciled so an operation formerly marked `RUNNING` is never left falsely running or marked successful. It becomes interrupted/recoverable and can resume correctly at a safe file boundary.

The host remains replaceable behind `OperationExecutionHost`, so future Android execution-policy changes can be absorbed without rewriting the operation engine.

## Provider write architecture

`StorageProvider` remains the common read contract. Step 2 adds `WritableStorageProvider` and explicit `StorageCapability` values for write, create file/directory, delete, rename, native move, random access, atomic rename, and timestamp support.

All operations use provider-neutral `FileReference` / `ScopedFileReference` values. SAF URIs are never converted into fake filesystem paths. Local operations retain the Step 1 logical root in each scoped reference, and every local write path is canonicalized and checked against that root.

### Local provider

The local provider implements create file/folder, delete, rename, output streaming, collision lookup, free-space reporting, safe same-filesystem native move, case-only rename handling, and symbolic-link detection. It rejects unsafe leaf names and root escapes.

### SAF provider

The SAF provider implements writable tree operations through `DocumentFile`, `DocumentsContract`, and `ContentResolver`, including create, delete, rename, input/output streaming, child lookup, and scoped-tree validation. Provider capability differences remain explicit; the engine does not assume atomic rename, random access, or reliable free-space capacity for SAF.

## Operation model

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

Each multi-item operation also stores item-level state so partial success is never flattened into an inaccurate generic result.

Operation types implemented in Step 2:

- copy
- move
- permanent delete
- rename
- batch rename
- create directory
- create empty file

Open/share/properties are immediate UI actions and do not need to enter the long-running queue.

## Persistent queue and journal

`OperationRepository` is FIFO by creation time and persists every important transition through `OperationJournal`. The journal uses an additive SQLite schema (version 1), stores the complete typed snapshot as JSON, and keeps indexed operation state/timestamps for recovery and bounded retention.

Important persisted boundaries include queueing, preparation/running state, periodic progress, item completion, pause/cancel, collision wait, failure, and terminal completion.

Process death never converts unfinished work to success. Unsafe runtime states are reconciled to `INTERRUPTED`; a running item returns to `QUEUED` with the unsafe current-file byte offset discarded. Completed item boundaries remain recorded.

## Copy and move safety

File content is copied with a fixed bounded buffer (`256 KiB` by default). No operation uses `readBytes()` or allocates memory based on source size. All byte counters are `Long`.

For providers that can safely rename, a copy writes to a temporary `.zzpart-*` destination, closes/flushes it, verifies the expected byte count when source size is known, then renames it to the final name. Cancellation, pause, failure, or host interruption attempts to remove tracked partial output.

A cross-provider move is:

```text
copy source → destination
verify completed destination
then delete source
```

If copy fails, the source remains intact. If copy succeeds but source deletion fails, the item completes with a warning rather than falsely reporting a clean move.

Same-provider local moves may use a native move only when the provider confirms it can do so safely.

## Directory traversal

Tree preparation uses an iterative `ArrayDeque` traversal rather than unbounded recursion. Empty directories are represented as operation items and copied. Symbolic links are detected on local storage and are not recursively followed, preventing traversal loops and root escapes.

Copy/move validates folder → self and folder → descendant targets before execution.

## Collision engine

Collision handling is centralized in the operation engine. It distinguishes:

- file → file
- directory → directory
- file → directory
- directory → file
- same resource

Supported decisions are capability/kind dependent:

- replace
- skip
- keep both
- merge directories
- apply compatible decision to all collisions in the active operation

Keep-both naming preserves extensions (`report.pdf` → `report (1).pdf`). Directory batch operations rewrite descendant relative paths when the root directory receives a keep-both name.

A collision moves the operation to `WAITING_FOR_USER`; no worker thread is blocked on a modal wait. The pending collision is persisted and resumed through `FileOperationController.resolveCollision()`.

## Batch rename

`BatchRenamePlanner` provides a preview before enqueueing. Supported rules include find/replace, prefix, suffix, and sequential numbering. Proposed names are validated for invalid leaf names and duplicates.

Execution uses temporary names first, preventing intermediate collisions such as `A → B` and `B → C`. If the temporary/final sequence fails, the engine attempts rollback to original names.

## Selection and clipboard UX

`BrowserOperationsViewModel` owns stable-ID selection state and reconciles it with currently displayed entries after refresh. Selection does not persist large `FileEntry` objects.

Long press enters selection mode. While selection mode is active, normal taps toggle selection instead of opening files. Select-all acts only on currently displayed entries. Back exits selection before directory navigation.

The internal copy/cut clipboard is stored in `OperationClipboardRepository` and survives activity/process recreation when references remain valid. CUT items remain visible and are visually dimmed; source deletion happens only during successful move execution.

The paste bar is shown only while clipboard content exists. Virtual/read-only destinations do not enable Paste.

## Background notification UX

Android execution components in Step 2 are:

- `AndroidOperationExecutionHost`
- `OperationForegroundService`
- `OperationActionReceiver`
- `OperationNotificationFactory`
- `OperationPresentation`

The manifest declares the foreground-service and notification permissions required for the file-operation channel and `dataSync` service type. Notifications expose live operation type/current item/progress plus pause and cancel actions where meaningful.

If notification permission is denied, the operation architecture remains guarded and no storage code depends on a notification callback to preserve file correctness; final permission-behavior certification on real OEM devices remains part of Step 7.

## Progress throttling

The engine does not emit or persist every buffer chunk. Progress is periodically persisted/emitted (500 ms default) and on meaningful item/state boundaries. This keeps Compose recomposition and journal writes bounded during multi-gigabyte copies.

If total bytes are unknown, UI uses item progress/indeterminate progress and never fabricates a percentage.

## Low-space and provider-loss behavior

Before known-size copy/move, free space is checked when the destination provider exposes reliable capacity. Unknown SAF capacity does not block valid work. Mid-stream write/provider failures close resources, preserve source data, clean temporary outputs where possible, and result in typed operation failure/warning state.

## Security

Step 2 preserves and expands Step 1 storage safety:

- canonical local-root containment
- safe leaf-name validation
- no path separators/NUL in generated names
- no shell command construction from file names
- no SAF URI-to-path conversion
- no recursive symbolic-link following
- self/descendant copy validation
- provider-scoped references persisted instead of Android framework objects
- release UI avoids raw encoded SAF URIs

## Large-file design

There is no application-level 2 GB/4 GB/10 GB/30 GB ceiling. Size/progress fields are `Long`, streaming memory is bounded, and tests model counters above 30 GiB without generating a 30 GiB CI artifact.

Byte-level resume after process death is intentionally not claimed for non-seekable providers. Recovery is correct at file boundaries: completed items remain complete; an interrupted current file is restarted after source/destination revalidation.

## Physical-device boundary

Physical-device certification is intentionally deferred to Step 7. Step 2 certification uses JVM tests, fake providers, static/lint checks, release compilation, API-35 emulator instrumentation, and app-private Android filesystem I/O.
