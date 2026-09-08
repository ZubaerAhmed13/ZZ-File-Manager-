# Step 2 Test Matrix

`PENDING CI` rows have automated coverage committed but are not marked PASS until the exact final Step 2 head completes the relevant workflow job.

| Test / area | Operation | Provider | Scenario | Expected result | Result | Type | Physical device |
|---|---|---|---|---|---|---|---|
| queuedCopy_streamsBytesAndCompletes | Copy | Fake writable | One real byte stream | Destination bytes equal source; COMPLETE | PENDING CI | JVM | Not required |
| moveDeletesSourceOnlyAfterDestinationCompletes | Move | Fake writable | Cross-copy move fallback | Destination complete before source delete | PENDING CI | JVM | Not required |
| copyFailureKeepsSourceIntact | Move | Fake writable | Destination write failure | Source remains; failure/warning recorded | PENDING CI | JVM | Not required |
| collisionWaitsForDecisionThenKeepBothUsesPredictableName | Copy | Fake writable | Existing same-name file | WAITING_FOR_USER then `report (1).pdf` | PENDING CI | JVM | Not required |
| folderCannotBeCopiedIntoItsDescendant | Copy | Fake writable | Folder → child folder | Block with DESCENDANT_TARGET | PENDING CI | JVM | Not required |
| permanentDeleteRemovesChildrenBeforeDirectory | Delete | Fake writable | Nested tree | Files then directories deleted safely | PENDING CI | JVM | Not required |
| knownInsufficientSpaceFailsBeforeCreatingOutput | Copy | Fake writable | Required > available | Fail before destination output | PENDING CI | JVM | Not required |
| json/model counter >30 GiB | Model/progress | N/A | 30 GiB Long values | No Int overflow | PENDING CI | JVM/model | Not required |
| hostInterruptionNeverMarksRunningOperationComplete | Recovery | Memory store | RUNNING host interrupted | INTERRUPTED, current item requeued | PENDING CI | JVM | Not required |
| batchRenamePlannerPreservesExtensionsAndDetectsDuplicateOutputs | Batch rename | N/A | Numbering + duplicate proposal | Valid preview; duplicates rejected | PENDING CI | JVM | Not required |
| keepBothNamingPreservesFileExtension | Collision | N/A | File and folder names | Correct `(n)` placement | PENDING CI | JVM | Not required |
| BrowserViewModel Step 1 regression suite | Browse/navigation | Fake browser store | sort/history/up/rapid nav/errors/open | Existing Step 1 behavior preserved | PENDING CI | JVM | Not required |
| localProvider_createCopyMoveRenameDeleteNested | Copy/move/rename/delete | Real local provider | Android app-private test tree | All real filesystem operations succeed | PENDING CI | API-35 instrumentation | Deferred Step 7 |
| LastLocationRestorationTest | Persistence | Android | app restart/navigation state | Step 1 restoration remains valid | PENDING CI | API-35 instrumentation | Not required |
| MainActivityTest | UI smoke | Android | launch | Root UI renders | PENDING CI | API-35 instrumentation | Not required |
| Provider local root containment tests | Security | Local | path outside logical root | Access denied | Existing Step 1 + Step 2 coverage; PENDING CI | JVM | Not required |
| SAF scoped-tree validation | Security/write | SAF | URI outside authorized tree | Access denied | Implementation review + PENDING CI | JVM/instrumented provider logic | Not required |
| Unknown SAF capacity | Copy | SAF/fake | free bytes unavailable | Transfer not pre-blocked | Engine behavior implemented; additional exhaustive picker automation deferred | Automated logic + review | JVM | Not required |
| Notification permission denied | Background | Android | POST_NOTIFICATIONS unavailable | Operation host must not crash | Implementation review; platform-specific final device certification deferred | Static/emulator | Deferred Step 7 |
| Process recreation with persisted clipboard | Clipboard | SharedPreferences | COPY/CUT persisted | References/mode/origin restored | Implementation + CI smoke | JVM/Android storage | Not required |
| 10,000-entry selection rendering | Selection/performance | Compose | large metadata list | stable keys/ID set; no full-object persistent selection | Architecture/static review | Static/Compose architecture | Deferred performance certification Step 7 |
| 2/4/10/30 GB counters | Large files | Model | simulated Long sizes | no Int overflow | PENDING CI | JVM/model | Not required |
| 30 GB memory safety | Copy design | Engine | logical large source | fixed 256 KiB application buffer | Architecture + code review | Static/JVM constant | Not required |
| Pause during copy | Copy | Engine | cooperative pause check | partial temp removed/current file requeued | Engine path implemented | JVM/race expansion recommended before final release | Not required |
| Cancel during copy | Copy | Engine | cooperative cancellation | streams close, partial removed, source retained | Engine path implemented | JVM/race expansion recommended before final release | Not required |
| Collision decision after cancellation | Collision/race | Engine | late decision | cancelled operation must not resume | Controller/state checks implemented | Additional race regression recommended | Not required |
| SAF system picker end-to-end | SAF | System picker | grant/write/reopen | provider operation succeeds | CI ENVIRONMENT LIMITATION | Emulator picker automation can be unreliable | Deferred Step 7 hardware certification |

## Required CI gate

The Step 2 branch is not certified until one exact-head workflow run passes all of:

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
./gradlew connectedDebugAndroidTest   # API 35 emulator job
```

Failures are fixed rather than suppressed. Physical phone testing remains explicitly deferred to Step 7.
