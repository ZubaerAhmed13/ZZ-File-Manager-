# Step 2 Test Matrix

Functional certification was completed on implementation head `52fad0bd6be9043ff091c495426c853cbe2f27e5` by GitHub Actions run `34324743879` (#105). The clean build/JVM/lint/release/instrumentation-compile gate passed, followed by 9/9 passing API-35 emulator instrumentation tests with zero failures and zero skips. Documentation-only commits created after that run are re-certified on the final exact branch head before Step 2 is closed.

| Test / area | Operation | Provider | Scenario | Expected result | Result | Type | Physical device |
|---|---|---|---|---|---|---|---|
| `queuedCopy_streamsBytesAndCompletes` | Copy | Fake writable | Real byte stream | Destination bytes equal source; COMPLETE | PASS | JVM | Not required |
| `moveDeletesSourceOnlyAfterDestinationCompletes` | Move | Fake writable | Copy/verify/delete fallback | Destination completes before source deletion | PASS | JVM | Not required |
| `copyFailureKeepsSourceIntact` | Move | Fake writable | Destination write failure | Source remains; operation fails safely | PASS | JVM | Not required |
| `collisionWaitsForDecisionThenKeepBothUsesPredictableName` | Copy | Fake writable | Existing same-name file | WAITING_FOR_USER then deterministic keep-both output | PASS | JVM | Not required |
| `cancelledCollisionCannotBeRevivedByLateDecision` | Collision/race | Fake writable | Cancel while waiting, then late decision | CANCELLED remains terminal; no late output | PASS | JVM | Not required |
| `pauseDuringCopyCleansPartialThenResumeCompletes` | Copy | Fake writable | Cooperative pause during streamed write | Partial removed, item requeued, resume completes | PASS | JVM | Not required |
| `cancelDuringCopyCleansPartialAndPreservesSource` | Copy | Fake writable | Cooperative cancellation during streamed write | Streams close, partial removed, source retained | PASS | JVM | Not required |
| `queuedCancellationNeverCreatesDestination` | Copy | Fake writable | Cancel before execution | No destination created; source retained | PASS | JVM | Not required |
| `destinationDisappearsDuringCopyFailsAndKeepsPartialTracked` | Copy | Fake writable | Destination becomes unavailable during write | FAILED, source retained, partial reference tracked | PASS | JVM | Not required |
| `staleClipboardSourceDeletedBeforePasteFailsSafely` | Copy/paste | Fake writable | Source removed after clipboard snapshot | SOURCE_MISSING, no destination output | PASS | JVM | Not required |
| `queuedOperationsCompleteInSubmissionOrder` | Queue | Fake writable | Three queued copies | FIFO terminal order | PASS | JVM | Not required |
| `secondOperationQueuedAsFirstCompletesIsPickedUpBySameRun` | Queue | Fake writable | New work arrives at terminal transition | Same engine run drains newly queued work | PASS | JVM | Not required |
| Folder copied into itself | Copy safety | Fake writable | Directory → itself | Blocked as DESCENDANT_TARGET | PASS | JVM | Not required |
| Folder copied into descendant | Copy safety | Fake writable | Directory → child directory | Blocked as DESCENDANT_TARGET | PASS | JVM | Not required |
| File copied onto itself | Copy safety | Fake writable | Same file/same directory | SAME_RESOURCE collision; original preserved | PASS | JVM | Not required |
| Permanent nested delete | Delete | Fake writable | Nested tree | Children removed before parent; no recursion overflow dependency | PASS | JVM | Not required |
| Known insufficient space | Copy | Fake writable | Required bytes > reliable free bytes | Fail before destination output | PASS | JVM | Not required |
| >30 GiB JSON/model counters | Model/progress | N/A | Large `Long` values | Persist/restore without `Int` overflow | PASS | JVM/model | Not required |
| 2/4/10/30 GB counter semantics | Large-file model | N/A | Simulated large sizes | No artificial app ceiling or integer overflow | PASS | JVM/model | Not required |
| Host interruption recovery | Recovery | Memory store | RUNNING execution host interrupted | INTERRUPTED; never false-COMPLETED | PASS | JVM | Not required |
| Retry carries tracked partial | Recovery/retry | Memory store | Failed operation with `.zzpart-*` reference | New operation ID; partial cleanup context preserved | PASS | JVM | Not required |
| Interrupted cancel terminal | Recovery/cancel | Memory store | Cancel interrupted operation | CANCELLED terminal state; tracked partial retained for audit/cleanup | PASS | JVM | Not required |
| Duplicate submission guard | Queue/controller | Memory store | Identical enqueue inside guard window | Original queued operation ID reused | PASS | JVM | Not required |
| Guard-window expiry | Queue/controller | Memory store | Same submission after guard window | New operation created | PASS | JVM | Not required |
| Clock rollback guard | Queue/controller | Memory store | Wall clock moves backward | Legitimate enqueue is not incorrectly suppressed | PASS | JVM | Not required |
| Batch rename mapping identity | Batch rename | Memory store | Same sources with different target mapping | Distinct operations | PASS | JVM | Not required |
| Batch rename map-order dedupe | Batch rename | Memory store | Same mapping, different map iteration order | Duplicate suppressed deterministically | PASS | JVM | Not required |
| Batch rename planner | Batch rename | N/A | Numbering/extensions/duplicate proposal | Extensions preserved; duplicate outputs rejected | PASS | JVM | Not required |
| Keep-both naming | Collision | N/A | File/folder names | Correct extension-aware `(n)` placement | PASS | JVM | Not required |
| 10,000-source queue | Selection/scale | Memory store | 10,000 metadata-only sources | All unique items queued with `Long` total count; no file payload allocation | PASS | JVM | Not required |
| BrowserViewModel Step 1 regression suite | Browse/navigation | Fake browser store | Sort/history/up/rapid nav/errors/open | Existing Step 1 behavior preserved | PASS | JVM | Not required |
| Local root containment | Security | Local | Path outside logical root | Access denied | PASS | JVM/provider coverage | Not required |
| `localProvider_createCopyMoveRenameDeleteNested` | File operations | Real local provider | Android app-private test tree | Create/copy/move/rename/delete nested flow succeeds | PASS | API-35 instrumentation | Not required |
| `productionSafProviderSupportsTreeCrudNavigationAndAncestry` | SAF CRUD/navigation/security | Production SAF provider + test DocumentsProvider | Tree list/create/write/read/find/rename/ancestry/parent/breadcrumb/delete | All operations stay within scoped tree and succeed | PASS | API-35 instrumentation | Not required |
| `fileOperationEngineCopiesAndMovesAcrossRealSafTreeLocations` | Copy/move | Production SAF provider + test DocumentsProvider | Real SAF tree source/destination with streamed payloads | Copy/move complete, bytes match, source deletion safe, no `.zzpart-*` left | PASS | API-35 instrumentation | Not required |
| `Step2BrowserUiTest` | Selection/clipboard/operations UI | Android Compose | Long-press/select-all/copy/cut/delete/rename/batch rename/collision/progress controls | Step 2 interaction surfaces render and behave as specified | PASS | API-35 instrumentation | Not required |
| `LastLocationRestorationTest` | Persistence | Android | Restart/navigation state | Step 1 restoration remains valid | PASS | API-35 instrumentation | Not required |
| `MainActivityTest` | UI smoke | Android | Launch | Root UI renders | PASS | API-35 instrumentation | Not required |
| Unknown SAF capacity | Copy | SAF/fake | Free bytes unavailable | Transfer is not falsely blocked as out-of-space | PASS by engine behavior/review | JVM + provider design | Not required |
| Fixed-memory transfer design | Large files | Engine | Logical large source | Application copy buffer remains fixed at 256 KiB | PASS | Static + JVM construction | Not required |
| Process recreation with persisted clipboard | Clipboard | SharedPreferences | COPY/CUT snapshot persistence | Typed references/mode/origin can be restored | PASS by implementation + green Android/JVM suite | JVM/Android storage | Not required |
| Notification / foreground host | Background | Android | User-started long operation | Foreground `dataSync` execution with pause/cancel actions and timeout reconciliation | PASS by build/lint/instrumented integration | Android/static | Device/OEM certification Step 7 |
| Full system picker interaction | SAF UX | Android DocumentsUI | User grants arbitrary third-party tree | Production code uses normal URI-grant contract; headless CI uses deterministic provider grant rather than driving DocumentsUI | ENVIRONMENT-LIMITED | API-35 provider integration substitutes only picker UI automation, not provider logic | Deferred Step 7 |

## Final automated certification gate

Run `34324743879` (#105), implementation head `52fad0bd6be9043ff091c495426c853cbe2f27e5`, passed:

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest   # API 35 emulator
```

API-35 result: **9 tests executed, 0 skipped, 0 failed**.

Failures discovered during Step 2 were fixed rather than suppressed. In particular, SAF directory traversal was changed from unsupported `SingleDocumentFile.listFiles()` behavior to tree-aware `DocumentsContract` queries, SAF document IDs are treated as opaque, mutation capabilities honor provider flags, and rename/finalization uses `DocumentsContract.renameDocument()`.

Physical phone certification remains explicitly deferred to Step 7.
