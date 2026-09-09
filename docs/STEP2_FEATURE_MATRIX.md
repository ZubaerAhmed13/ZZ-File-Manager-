# Step 2 Feature Matrix

This matrix records the final Step 2 implementation state. Functional certification was completed on implementation head `52fad0bd6be9043ff091c495426c853cbe2f27e5` by GitHub Actions run `34324743879` (#105): clean debug build, JVM tests, lint, release build, instrumentation compile, and API-35 emulator instrumentation all passed. Documentation-only commits created after that run are re-certified by the final exact-head workflow before Step 2 is closed.

| Requirement | Status | Evidence / notes |
|---|---|---|
| Multi-selection | PASS | Stable-ID state in `BrowserOperationsViewModel`; list/grid long-press and tap-toggle UX; API-35 UI coverage. |
| Select all | PASS | Selects currently displayed entries only; API-35 UI coverage. |
| Clear selection / Back exits selection | PASS | Selection top bar and BackHandler priority; API-35 UI coverage. |
| Range-selection model | PASS (MODEL) | `selectRange()` supports anchor/target ranges. A hardware Shift gesture is not part of the Step 2 touch UI. |
| Internal clipboard | PASS | `OperationClipboardRepository`; persisted COPY/CUT references, origin and timestamp. |
| Copy | PASS | Queued bounded streaming, temporary output, provider-aware finalization and per-item state. |
| Move | PASS | Native local optimization plus copy→verify→delete fallback; source is not deleted before destination success. |
| Permanent delete | PASS | Explicit destructive confirmation and safe recursive child-before-parent execution. |
| Rename | PASS | Provider-neutral rename with safe names; SAF uses `DocumentsContract.renameDocument()`. |
| Batch rename | PASS | Find/replace, prefix, suffix, numbering, preview, duplicate validation, temporary-name safety and rollback handling. |
| Create folder | PASS | Real local/SAF provider create operation with browser refresh after completion. |
| Create empty file | PASS | Real zero-byte provider create; text editing remains outside Step 2. |
| Share one/multiple files | PASS | Android content-URI sharing; no `file://` exposure. |
| Basic properties | PASS | Name/type/size/modified/location/readable/writable without forced deep folder scan. |
| Collision handling | PASS | Central typed collision engine. |
| Replace | PASS | File replacement where provider/collision semantics allow it. |
| Skip | PASS | Per-item/subtree skip. |
| Keep both | PASS | Deterministic extension-preserving `(n)` naming. |
| Directory merge | PASS | Intentional merge; destination directory is not destroyed. |
| Apply to all | PASS | Scoped to the active operation and compatible collision types only. |
| Persistent operation queue | PASS | FIFO `OperationRepository` plus durable journal. |
| Persistent journal | PASS | SQLite schema + typed JSON snapshots + indexed state/timestamps. |
| Background execution | PASS | User-started `dataSync` foreground service for local/SAF work; Android 15 timeout reconciles journal state instead of false-completing. |
| Android 14+ user-initiated JobScheduler | NOT USED BY DESIGN | Android user-initiated jobs are for user-requested network data transfer, not local/SAF file copies. |
| Progress | PASS | `Long` byte/item counters, throttled persistence/events, indeterminate mode when total is unknown. |
| Cancel | PASS | Cooperative cancellation, stream close, source preservation and partial-output cleanup; JVM race test passed. |
| Pause/resume | PASS | Cooperative pause; temporary output cleanup and current-file restart where byte-offset resume cannot be proven safe; JVM test passed. |
| Process recovery | PASS | Unsafe running states reconcile to `INTERRUPTED`; never auto-complete. |
| File-boundary resume | PASS | Completed items remain complete; interrupted current item is re-queued. |
| Byte-level process-death resume | NOT GENERALLY CLAIMED | Correctness-first design restarts the interrupted file unless a provider can safely validate random-access continuation. This is not required for Step 2 completion. |
| Retry | PASS | New operation ID with source/destination/permission/collision revalidation; tracked partial cleanup data is carried forward. |
| Duplicate submission guard | PASS | Short-window duplicate enqueue suppression with clock rollback handling; JVM tests passed. |
| Low-space preflight | PASS | Uses destination free capacity where reliable; unknown SAF capacity is not treated as zero. |
| Large-file `Long` support | PASS | `Long` counters/model persistence, >30 GiB model coverage, fixed 256 KiB transfer buffer. |
| No arbitrary size ceiling | PASS | No app-level 2/4/10/30 GB limit. |
| Large selection/queue scale | PASS | 10,000-source metadata queue test; persistent selection stores stable IDs rather than file payloads. |
| Local provider writes | PASS | Create/delete/rename/output/native move/capacity/root containment; real API-35 provider test passed. |
| SAF provider writes | PASS | Tree-aware `DocumentsContract`/`ContentResolver` CRUD, opaque document IDs, scoped-tree containment, provider capability flags, safe rename/finalization; API-35 provider tests passed. |
| SAF system-picker UI automation | ENVIRONMENT-LIMITED | Step 2 validates the SAF provider through an instrumented DocumentsProvider. Full third-party/system picker interaction remains device/environment dependent and is deferred to Step 7 hardware certification. |
| Virtual MediaStore destination protection | PASS | Category roots remain read-only; Paste/Create disabled by location capability. |
| Symbolic-link loop safety | PASS | Local symbolic links are not recursively followed. |
| Root/path traversal protection | PASS | Canonical local-root checks, SAF tree containment and safe leaf-name validation. |
| Stale source protection | PASS | Missing/changed source snapshots fail safely before destructive behavior. |
| Operation details/progress surface | PASS | Browser operation sheet exposes state, current item, bytes/items, failures and actions. |
| Notification controls | PASS | Dedicated channel with pause/cancel controls and Android timeout reconciliation. |
| API-35 instrumentation | PASS | Run #105 completed 9/9 tests with zero failures on API 35. |
| `assembleDebug` | PASS | Run #105 clean Gradle gate. |
| `testDebugUnitTest` | PASS | Run #105 clean Gradle gate. |
| `lintDebug` | PASS | Run #105 clean Gradle gate. |
| `assembleRelease` | PASS | Run #105 clean Gradle gate. |
| `assembleDebugAndroidTest` | PASS | Run #105 clean Gradle gate. |
| Step 1 regression coverage | PASS | Existing JVM/instrumentation browse, navigation and persistence tests remain in the green suite. |
| Physical phone testing | DEFERRED TO STEP 7 | Intentionally not required during Step 2. |

## Step 2 boundary

No Step 3 functionality is claimed by this matrix. Step 2 closes only the professional file-operation/selection/clipboard/background/recovery/provider work defined for this stage. Physical-device certification remains a Step 7 activity.
