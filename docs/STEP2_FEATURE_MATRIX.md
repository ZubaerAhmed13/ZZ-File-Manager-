# Step 2 Feature Matrix

Status in this file is evidence-based. `IMPLEMENTED — CI PENDING` means code/tests exist on the Step 2 branch but the exact current head has not yet completed the full CI gate. It must not be interpreted as certified complete until the final Step 2 CI run is green.

| Requirement | Status | Evidence / notes |
|---|---|---|
| Multi-selection | IMPLEMENTED — CI PENDING | Stable-ID state in `BrowserOperationsViewModel`; list/grid long-press and tap-toggle UX. |
| Select all | IMPLEMENTED — CI PENDING | Selects currently displayed entries only. |
| Clear selection / Back exits selection | IMPLEMENTED — CI PENDING | Selection top bar and BackHandler priority. |
| Range-selection model | IMPLEMENTED (model), UI LIMITED | `selectRange()` supports anchor/target ranges; dedicated hardware Shift gesture is not yet exposed in the touch UI. |
| Internal clipboard | IMPLEMENTED — CI PENDING | `OperationClipboardRepository`; COPY/CUT typed model, persisted references/origin/time. |
| Copy | IMPLEMENTED — CI PENDING | Queued, bounded streaming, temporary output/finalization, per-item state. |
| Move | IMPLEMENTED — CI PENDING | Native local optimization plus copy→verify→delete fallback. |
| Permanent delete | IMPLEMENTED — CI PENDING | Explicit destructive confirmation; recursive tree represented as item queue. |
| Rename | IMPLEMENTED — CI PENDING | Provider-neutral rename with collision handling and safe names. |
| Batch rename | IMPLEMENTED — CI PENDING | Find/replace, prefix, suffix, numbering, preview, duplicate validation, temp-name safety. |
| Create folder | IMPLEMENTED — CI PENDING | Real provider create operation and browser refresh after completion. |
| Create empty file | IMPLEMENTED — CI PENDING | Real zero-byte provider create; no text editor in Step 2. |
| Share one/multiple files | IMPLEMENTED — CI PENDING | Android content URIs via existing safe open requests; no `file://`. |
| Basic properties | IMPLEMENTED — CI PENDING | Name/type/size/modified/location/readable/writable; no forced deep folder scan. |
| Collision handling | IMPLEMENTED — CI PENDING | Centralized typed collision engine. |
| Replace | IMPLEMENTED — CI PENDING | File collisions where replacement is meaningful. |
| Skip | IMPLEMENTED — CI PENDING | Per-item/subtree skip. |
| Keep both | IMPLEMENTED — CI PENDING | Extension-preserving deterministic names. |
| Directory merge | IMPLEMENTED — CI PENDING | Intentional merge; destination directory is not destroyed. |
| Apply to all | IMPLEMENTED — CI PENDING | Scoped to active operation and only compatible collision types. |
| Persistent operation queue | IMPLEMENTED — CI PENDING | FIFO `OperationRepository` + durable journal. |
| Persistent journal | IMPLEMENTED — CI PENDING | SQLite schema v1 + JSON typed snapshots + indexed state/timestamps. |
| Background execution | IMPLEMENTED — CI PENDING | User-started `dataSync` foreground service for local/SAF operations; Android 15 timeout reconciles journal to recoverable interruption. |
| Android 14+ user-initiated JobScheduler | NOT USED FOR STEP 2 | Platform mode is for user-requested network data transfers, not local/SAF file copies. |
| Progress | IMPLEMENTED — CI PENDING | Long byte/item counters; throttled persistence/events; indeterminate mode for unknown total. |
| Cancel | IMPLEMENTED — CI PENDING | Cooperative state/control checks; temporary output cleanup. |
| Pause/resume | IMPLEMENTED — CI PENDING | Cooperative in-process pause; current file restarts when seek-safe byte resume is unavailable. |
| Process recovery | IMPLEMENTED — CI PENDING | Unsafe running states reconcile to `INTERRUPTED`; never auto-complete. |
| File-boundary resume | IMPLEMENTED — CI PENDING | Completed items remain complete; interrupted item is re-queued. |
| Byte-level process-death resume | NOT GENERALLY CLAIMED | Only safe if a future provider-specific random-access implementation validates source/partial offset; correctness takes priority. |
| Retry | IMPLEMENTED — CI PENDING | New operation ID, source/destination/permission/collision revalidation on execution. |
| Low-space preflight | IMPLEMENTED — CI PENDING | Uses destination free capacity where reliable; unknown capacity is allowed. |
| Large-file Long support | IMPLEMENTED — CI PENDING | `Long` counters/model persistence; >30 GiB model test; fixed 256 KiB copy buffer. |
| No arbitrary size ceiling | IMPLEMENTED — CI PENDING | No app-level 2/4/10/30 GB cap. |
| Local provider writes | IMPLEMENTED — CI PENDING | Create/delete/rename/output/native move/capacity/root containment. |
| SAF provider writes | IMPLEMENTED — CI PENDING | DocumentFile/DocumentsContract/ContentResolver writes with scoped tree validation. |
| SAF full system-picker automation | LIMITED BY CI ENVIRONMENT | Provider logic is implemented; full system picker interaction may be unreliable in headless emulator and is not substituted with physical testing. |
| Virtual MediaStore destination protection | IMPLEMENTED — CI PENDING | Category roots remain read-only; Paste/Create disabled by location capability. |
| Symbolic-link loop safety | IMPLEMENTED — CI PENDING | Local links detected and not recursively followed. |
| Root/path traversal protection | IMPLEMENTED — CI PENDING | Canonical local-root checks + safe leaf names. |
| Operation details/progress surface | IMPLEMENTED — CI PENDING | Browser operation sheet with state, current item, bytes/items, failures, actions. |
| Notification controls | IMPLEMENTED — CI PENDING | Dedicated channel plus pause/cancel actions. |
| API 35 instrumentation | PENDING CURRENT HEAD CI | Real local create/copy/move/rename/delete nested test added. |
| assembleDebug | PENDING CURRENT HEAD CI | Final status updated only after exact-head CI. |
| testDebugUnitTest | PENDING CURRENT HEAD CI | Final status updated only after exact-head CI. |
| lintDebug | PENDING CURRENT HEAD CI | Final status updated only after exact-head CI. |
| assembleRelease | PENDING CURRENT HEAD CI | Final status updated only after exact-head CI. |
| Physical phone testing | DEFERRED TO STEP 7 | No physical-device requirement in Step 2. |
