# ZZ File Manager — Step 3 Architecture

## Scope and invariants

Step 3 extends the approved Step 2 head `f18fcbd5e808bd23283c9d7f25f425795cdb7909`. It adds local search, a persistent user library, and recoverable deletion without weakening the Step 2 operation engine. Provider references remain opaque and root-scoped. SAF URIs are never converted to filesystem paths. Physical-phone certification remains Step 7 work.

The principal invariant is: when mutation completion cannot be proven, preserve the source or payload and persist an interrupted/failed/corrupted state; never claim success by inference.

## Search

`SearchScreen` observes `SearchViewModel`, which debounces input, cancels superseded jobs, and protects state with a monotonically increasing generation. `SearchRepository` delegates to `SearchCoordinator` and writes bounded search history only after normal flow completion.

`SearchCoordinator` resolves the exact requested scope, traverses directories iteratively with `ArrayDeque`, checks cancellation regularly, never follows symbolic-link directories, and emits controlled batches. Each `SearchResult` retains its real `FileReference`, parent `BrowserLocation`, storage identity, type, size, time and capabilities. The UI retains at most 5,000 lightweight results while the coordinator continues its accurate total count.

Current folder, explicit recursive folder, current storage and all-accessible-location scopes share the same provider-neutral traversal. Local and SAF use their actual provider listing APIs. MediaStore roots use the existing MediaStore-backed category provider and platform database queries. One provider failure becomes partial-results metadata rather than discarding successful locations.

Name matching is Unicode NFKC/case normalized without mutating filenames. Contains, starts-with and exact modes combine with extension, type, `Long` size, modified date, hidden, file/folder and sort filters. Relevance is deterministic: exact name, prefix, name containment, then path containment. The reserved `.ZZFileManagerRecycle` namespace and temporary trash prefixes are always excluded.

Search result actions reuse Step 2 references, clipboard/controller and Android sharing; they do not implement a second transfer engine. Successful trash/platform trash and queued permanent deletion are removed from the active window.

## User library and history

`UserLibraryRepository` owns a separate SQLite database (`step3_user_library.db`, schema v2), isolating user metadata from the Step 2 recovery journal. It stores favorites, recent files, search history, derived activity and trash records. Tables have primary identities plus timestamp, state and provider/reference indexes. The v1→v2 migration adds provider/reference columns and indexes without destructive fallback.

Limits are 10,000 favorites, 50 recent files, 50 searches and 100 activity entries. Stable provider/root/storage/reference identities replace duplicates. `UserLibraryManager` validates references, records successfully opened files, updates favorite last-opened time and never deletes storage when metadata is cleared.

`OperationLibrarySynchronizer` consumes authoritative Step 2 completion/failure events. It derives user-readable activity and follows reliable rename/move result references for favorites and recents. Trash records keep their logical favorite/recent relationship: status becomes `TRASHED`, and a proven restore reconnects the returned provider reference.

## Recycle backends

MediaStore items use `MediaStore.createTrashRequest()` / platform confirmation on API 30+. Catalog state is written only after `RESULT_OK`. Restore and permanent delete likewise require the platform request; cancellation leaves metadata unchanged.

Local/SAF app-managed trash is per writable logical root under `.ZZFileManagerRecycle/<UUID>/`. A durable `TrashRecord` is written before mutation. If a safe same-root native move is supported, the payload moves into its unique container with bounded memory and no second-copy space. Otherwise the manager enqueues a Step 2 `MOVE` into that hidden container. Step 2 performs copy→verification→source deletion, reports progress through its journal/UI, and preserves the source on pre-delete failure.

Trash states are explicit: `PREPARING`, `MOVING`, `COPYING`, `TRASHED`, `RESTORING`, `DELETE_PENDING`, `DELETED`, `INTERRUPTED`, `FAILED`, and `CORRUPTED`. Duplicate source transactions and concurrent restore/delete mutations are rejected. Files, empty directories and directory trees use the same provider operations.

## Restore and deletion

Original-location restore validates that the parent still exists inside its authorized root. It uses native move when safe. Collision choices are Cancel, extension-aware Keep Both, and capability-gated atomic Replace. If the original parent is unavailable, the UI retains the payload and offers a user-selected SAF destination. Same-provider alternate restore prefers native move; cross-provider restore uses the Step 2 move transaction and clears the trash record only on its proven completion event.

Permanent deletion walks directories iteratively, checks provider deletion results and verifies the payload no longer exists before removing catalog state. Empty Recycle Bin reports partial app-managed failures exactly and requests separate platform confirmation for MediaStore items.

## Crash recovery and reconciliation

At startup and periodic maintenance, `TrashManager.reconcile()` initializes the catalog, joins linked Step 2 operations, and compares source/payload presence. It completes only provable states, marks ambiguous cases interrupted/corrupted, and never deletes uncertain data. Unknown non-empty UUID containers become preserved corrupted/orphan records; empty containers are safely cleaned. Missing payload records become corrupted rather than restorable.

Retention supports Never, 7, 30, 60 and 90 days (default 30). Cleanup runs opportunistically at app startup and through unique daily WorkManager scheduling. Only expired app-managed records in the proven `TRASHED` state are eligible; MediaStore confirmation and active/interrupted states are never bypassed.

## Large-file behavior

All sizes, timestamps and counters are `Long`. Native trash/restore allocate no content-sized buffers. Fallback transfers use Step 2's fixed bounded streaming buffer and tracked partial-output journal. Automated tests model a 30 GiB file with only 1 GiB free and assert both trash and restore use native moves with zero output-stream opens. Existing Step 2 tests also cover 20–30+ GiB journal values and the absence of 2/4/10/30 GB application limits.
