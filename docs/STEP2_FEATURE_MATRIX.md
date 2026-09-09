# Step 2 Feature Matrix

Functional certification was completed on implementation head `f21bd3705087cc65620e772e4a549c69baf4b443` by GitHub Actions run `34336655608` (#139). The clean debug build, JVM tests, lint, release build, instrumentation compile, and API-35 emulator instrumentation all passed. The API-35 suite executed **9 tests, 0 skipped, 0 failed**.

Verified Step 1 baseline: `2be3a6933931a1f7aa9ae3857e7adf2f910bca9e`.

| Requirement | Status | Evidence / notes |
|---|---|---|
| Multi-selection | PASS | Stable-ID selection in `BrowserOperationsViewModel`; long-press/tap-toggle UX. |
| Select all | PASS | Selects currently displayed entries only. |
| Clear selection / Back exits selection | PASS | Selection top bar and Back priority. |
| Range-selection model | PASS (MODEL) | `selectRange()` supports anchor/target ranges; dedicated hardware-Shift gesture is not part of touch Step 2 UX. |
| Internal clipboard | PASS | Persistent COPY/CUT references, origin and timestamp. |
| Copy | PASS | Queued bounded streaming, hidden staging, provider-aware finalization and item state. |
| Move | PASS | Native optimization plus copy→verify→delete fallback; source never deleted before destination success. |
| Native move free-space semantics | PASS | Copy-space preflight is skipped when a provider-safe native move is confirmed; 30 GiB logical regression with 1 GiB free passes. |
| Permanent delete | PASS | Explicit destructive confirmation; child-before-parent tree deletion. |
| Rename | PASS | Provider-neutral rename with safe names; SAF uses `DocumentsContract.renameDocument()`. |
| Batch rename | PASS | Preview, find/replace, prefix, suffix, numbering, duplicate validation, two-phase temporary namespace, persisted transaction phases and rollback. |
| Batch rename rollback recovery | PASS | Failed/incomplete rollback remains `INTERRUPTED` with live phase/reference ledger and resumes rollback before forward work. |
| Create folder | PASS | Real local/SAF provider create operation. |
| Create empty file | PASS | Real zero-byte create; text editing remains outside Step 2. |
| Share one/multiple files | PASS | Android content-URI sharing; no `file://` exposure. |
| Basic properties | PASS | Name/type/size/modified/location/readable/writable without forced deep folder scan. |
| Collision handling | PASS | Central typed collision engine. |
| Replace | PASS | Safe staged replacement only when provider capabilities support atomic replace or reversible rename+delete transaction. |
| Replace capability gating | PASS | Rename-only providers do not offer unsafe Replace; Keep-Both can remain available when rename finalization is safe. |
| Atomic local Replace | PASS | Local provider attempts `ATOMIC_MOVE + REPLACE_EXISTING`; falls back only when atomic replacement is unavailable. |
| Weak-provider Replace transaction | PASS | Durable backup/commit/verify/cleanup ledger preserves old destination across write/read/finalization/provider failures. |
| Replace process-death recovery | PASS | Persisted phase/original snapshot/backup/staged/final references support deterministic restore or completion after restart. |
| Replace cancellation safety | PASS | Coroutine cancellation preserves transaction ledger instead of performing speculative rollback inside cancelled execution. |
| Replace backup cleanup recovery | PASS | Failure to remove safety backup leaves operation recoverable/interrupted rather than silently orphaning backup state. |
| Skip | PASS | Per-item/subtree skip. |
| Keep both | PASS | Deterministic extension-preserving `(n)` naming. |
| Directory merge | PASS | Intentional merge; destination directory is not destroyed. |
| Apply to all | PASS | Scoped to active operation and compatible collision types. |
| Persistent operation queue | PASS | FIFO `OperationRepository` plus durable journal. |
| Persistent journal | PASS | SQLite + typed JSON snapshots + indexed state/timestamps. |
| Replace ledger persistence | PASS | `ReplacePhase`, final name, original ref/size/mtime, backup name/ref and staged partial survive JSON round-trip. |
| Background execution | PASS | User-started `dataSync` foreground service for local/SAF work; Android 15 timeout reconciles to recoverable interruption. |
| Android 14+ user-initiated JobScheduler | NOT USED BY DESIGN | User-initiated jobs target network transfers, not local/SAF file operations. |
| Progress | PASS | `Long` byte/item counters, throttled persistence/events, indeterminate mode when total is unknown. |
| Cancel | PASS | Cooperative cancellation, stream close, source preservation, partial cleanup, collision-race protection and transaction-ledger preservation. |
| Pause/resume | PASS | Cooperative pause; safe current-file restart where byte-level continuation is not provable. |
| Process recovery | PASS | Unsafe running states reconcile to `INTERRUPTED`; never auto-complete. |
| File-boundary resume | PASS | Completed items stay complete; interrupted current item is re-queued. |
| Byte-level process-death resume | NOT GENERALLY CLAIMED | Correctness-first restart unless provider-safe random-access continuation can be proven. |
| Retry | PASS | New operation ID after terminal failure/warnings; tracked partial cleanup context carried forward. Unfinished Replace ledger is recovered in-place and is never cloned. |
| Duplicate submission guard | PASS | Short-window duplicate enqueue suppression with clock rollback handling. |
| Low-space preflight | PASS | Uses reliable destination capacity; unknown SAF capacity is not treated as zero. |
| Large-file `Long` support | PASS | `Long` counters/model/ledger persistence and >30 GiB simulated coverage. |
| No arbitrary size ceiling | PASS | No application-level 2/4/10/30 GB limit. |
| Fixed-memory transfer | PASS | Default buffer is 256 KiB; no source-size proportional allocation. |
| Large selection/queue scale | PASS | 10,000-source metadata queue regression. |
| Local provider writes | PASS | Create/delete/rename/output/native move/capacity/root containment/atomic replace. |
| SAF provider writes | PASS | Tree-aware `DocumentsContract`/`ContentResolver` CRUD, opaque document IDs, scoped-tree containment and provider flags. |
| SAF system-picker UI automation | ENVIRONMENT-LIMITED | Production provider logic is API-35 tested; full third-party/system picker UX is deferred to physical-device certification. |
| Virtual MediaStore destination protection | PASS | Category roots remain read-only; Paste/Create disabled by capability. |
| Symbolic-link loop safety | PASS | Local symbolic links are not recursively followed. |
| Root/path traversal protection | PASS | Canonical local-root checks, SAF tree containment and safe leaf names. |
| Stale source protection | PASS | Missing/changed source snapshots fail safely before destructive behavior. |
| No visible partial final filename | PASS | Providers without safe rename finalization fail before exposing a partial final-name file. |
| Operation details/progress surface | PASS | Browser operation sheet exposes state, current item, bytes/items, failures and actions. |
| Notification controls | PASS | Dedicated channel with pause/cancel controls and timeout reconciliation. |
| API-35 instrumentation | PASS | Run #139: 9/9 tests, 0 skipped, 0 failed. |
| `assembleDebug` | PASS | Run #139. |
| `testDebugUnitTest` | PASS | Run #139, including the new transaction regressions. |
| `lintDebug` | PASS | Run #139. |
| `assembleRelease` | PASS | Run #139. |
| `assembleDebugAndroidTest` | PASS | Run #139. |
| Step 1 regression coverage | PASS | Existing browse/navigation/persistence tests remain in the green suite. |
| Physical phone testing | DEFERRED TO STEP 7 | Intentionally not required during Step 2. |

## Step 2 boundary

No Step 3 functionality is claimed by this matrix. Step 2 closes the professional file-operation, selection, clipboard, background execution, recovery and provider work defined for this stage.

Physical phone certification intentionally deferred to Step 7 per project plan.
