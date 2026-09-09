# Step 2 Test Matrix

Functional certification was completed on implementation head `f21bd3705087cc65620e772e4a549c69baf4b443` by GitHub Actions run `34336655608` (#139). The clean build/JVM/lint/release/instrumentation-compile gate passed, followed by **9/9 passing API-35 emulator instrumentation tests with zero failures and zero skips**.

Verified Step 1 baseline: `2be3a6933931a1f7aa9ae3857e7adf2f910bca9e`.

| Test / area | Operation | Provider | Scenario | Expected result | Result | Type | Physical device |
|---|---|---|---|---|---|---|---|
| Streamed copy | Copy | Fake writable | Real byte stream | Destination bytes equal source; COMPLETE | PASS | JVM | Not required |
| Safe move deletion order | Move | Fake writable | Copy/verify/delete fallback | Destination completes before source deletion | PASS | JVM | Not required |
| Copy failure source preservation | Move | Fake writable | Destination write failure | Source remains; operation fails safely | PASS | JVM | Not required |
| Collision keep-both | Copy | Fake writable | Existing same-name file | WAITING_FOR_USER then predictable extension-aware output | PASS | JVM | Not required |
| Cancelled collision race | Collision | Fake writable | Cancel while waiting then late decision | CANCELLED stays terminal; no revival | PASS | JVM | Not required |
| Pause during streamed copy | Copy | Fake writable | Cooperative pause | Partial cleaned/requeued; resume completes | PASS | JVM | Not required |
| Cancel during streamed copy | Copy | Fake writable | Cooperative cancel | Streams close; source retained; partial cleanup attempted | PASS | JVM | Not required |
| Queued cancellation | Copy | Fake writable | Cancel before execution | No destination created | PASS | JVM | Not required |
| Provider disappearance | Copy | Fake writable | Destination vanishes mid-write | Safe failure; source retained; uncleaned partial tracked | PASS | JVM | Not required |
| Stale clipboard source | Copy/paste | Fake writable | Source removed after clipboard snapshot | SOURCE_MISSING; no output | PASS | JVM | Not required |
| FIFO queue ordering | Queue | Fake writable | Three queued copies | Terminal order follows submission | PASS | JVM | Not required |
| Queue drain while new work arrives | Queue | Fake writable | Second operation arrives at completion boundary | Same engine run picks it up | PASS | JVM | Not required |
| Folder → self | Copy safety | Fake writable | Directory copied into itself | DESCENDANT_TARGET | PASS | JVM | Not required |
| Folder → descendant | Copy safety | Fake writable | Directory copied into child | DESCENDANT_TARGET | PASS | JVM | Not required |
| File → same resource | Copy safety | Fake writable | Same file/same folder | SAME_RESOURCE collision; original preserved | PASS | JVM | Not required |
| Permanent nested delete | Delete | Fake writable | Nested tree | Child-before-parent deletion | PASS | JVM | Not required |
| Known insufficient space | Copy | Fake writable | Required bytes > reliable free bytes | Fail before output | PASS | JVM | Not required |
| Large `Long` counters | Model/progress | N/A | >30 GiB modeled values | Persist/restore without `Int` overflow | PASS | JVM/model | Not required |
| Host interruption recovery | Recovery | Memory store | RUNNING host interrupted | INTERRUPTED; never false-COMPLETED | PASS | JVM | Not required |
| Retry carries partial cleanup context | Recovery/retry | Memory store | Failed `.zzpart-*` retained | New operation ID with cleanup context | PASS | JVM | Not required |
| Duplicate submission guard | Queue/controller | Memory store | Identical enqueue inside guard window | Original queued operation reused | PASS | JVM | Not required |
| Guard-window expiry | Queue/controller | Memory store | Same submission after window | New operation created | PASS | JVM | Not required |
| Clock rollback guard | Queue/controller | Memory store | Wall clock moves backward | Legitimate enqueue not suppressed | PASS | JVM | Not required |
| Batch rename mapping identity | Batch rename | Memory store | Same sources, different target map | Distinct operation | PASS | JVM | Not required |
| Batch rename map-order dedupe | Batch rename | Memory store | Same map, different iteration order | Duplicate suppressed deterministically | PASS | JVM | Not required |
| Batch rename planner | Batch rename | N/A | Numbering/extensions/duplicates | Extensions preserved; duplicate proposals rejected | PASS | JVM | Not required |
| Batch rename phase-two failure | Batch rename | Fake writable | Failure during temp→final | All items roll back to originals | PASS | JVM | Not required |
| Batch rollback failure/resume | Batch rename | Fake writable | Rollback itself fails | Operation remains INTERRUPTED with rollback ledger; resume restores originals | PASS | JVM | Not required |
| Batch process death after original→temp mutation | Batch rename | Fake writable | Mutation occurs then cancellation before save | Resume reconciles name/reference and completes | PASS | JVM | Not required |
| Batch process death between phases | Batch rename | Fake writable | Cancellation before first final rename | Resume completes safely | PASS | JVM | Not required |
| Batch process death after final mutation | Batch rename | Fake writable | Final rename mutates then cancellation before save | Resume reconciles and completes | PASS | JVM | Not required |
| Replace write failure | Replace | Fake writable | Destination staged-write failure | Existing destination unchanged; no hidden transfer artifacts | PASS | JVM | Not required |
| Replace source read failure | Replace | Fake writable | Source read fails mid-stream | Existing destination unchanged | PASS | JVM | Not required |
| Replace provider disappearance | Replace | Fake writable | Provider vanishes after first staged write | Existing final preserved; recovery context retained where needed | PASS | JVM | Not required |
| Replace cooperative cancellation | Replace | Fake writable | Cancel during staging | Existing destination unchanged; partial removed when possible | PASS | JVM | Not required |
| Replace final rename failure | Replace | Fake writable | Staged→final rename fails | Existing destination restored; hidden backup/partial cleanup verified | PASS | JVM | Not required |
| Replace process death after old→backup mutation | Replace | Fake writable | Cancellation after destination rename but before phase save | Persisted BACKUP_PLANNED ledger reconciles; original restored then replacement completes | PASS | JVM | Not required |
| Replace ledger JSON round-trip | Persistence | JSON/model | Replace phase/original/backup/staged fields incl. 20–30 GiB values | All transaction data restored exactly | PASS | JVM/model | Not required |
| Rename without delete capability | Replace/collision | Fake writable | Provider supports rename but not delete | Replace is not offered; Keep-Both remains allowed; existing file preserved | PASS | JVM | Not required |
| Provider without rename | Copy/finalization | Fake writable | Provider can create/write but cannot safely rename staged file | SAFE_FINALIZATION_UNSUPPORTED; no visible partial final | PASS | JVM | Not required |
| Native 30 GiB move with 1 GiB free | Move | Fake writable | Provider-safe same-storage native move | Move allowed; no false insufficient-space failure | PASS | JVM/model | Not required |
| Keep-both naming | Collision | N/A | File/folder names | Correct `(n)` placement before extension | PASS | JVM | Not required |
| 10,000-source queue | Selection/scale | Memory store | 10,000 metadata-only sources | Unique items queued; `Long` total; no file payload allocation | PASS | JVM | Not required |
| BrowserViewModel Step 1 regressions | Browse/navigation | Fake browser store | Sort/history/up/rapid nav/errors/open | Step 1 behavior preserved | PASS | JVM | Not required |
| Local root containment | Security | Local | Path outside logical root | Access denied | PASS | Provider/JVM | Not required |
| Real local CRUD/transfer | File operations | Production local provider | App-private Android tree | Create/copy/move/rename/delete nested flow succeeds | PASS | API-35 instrumentation | Not required |
| Production SAF CRUD/navigation/ancestry | SAF | Production SAF + deterministic test DocumentsProvider | Tree CRUD/list/rename/ancestry/parent/breadcrumb/delete | Scoped operations succeed | PASS | API-35 instrumentation | Not required |
| Engine copy/move across SAF locations | Copy/move | Production SAF | Real tree source/destination payloads | Bytes match; source deletion safe; no `.zzpart-*` left | PASS | API-35 instrumentation | Not required |
| Step 2 browser UI | UI | Compose | Selection/clipboard/delete/rename/batch/collision/progress controls | Required interaction surfaces work | PASS | API-35 instrumentation | Not required |
| Last-location restoration | Persistence | Android | Restart/navigation state | Step 1 restoration remains valid | PASS | API-35 instrumentation | Not required |
| Main activity smoke | UI | Android | Launch | Root UI renders | PASS | API-35 instrumentation | Not required |
| Unknown SAF capacity | Copy | SAF/fake | Capacity unavailable | Transfer not falsely blocked as out-of-space | PASS | Engine/provider design | Not required |
| Foreground host / notification | Background | Android | User-started long operation | `dataSync` FGS, pause/cancel controls, timeout reconciliation | PASS | Build/lint/integration | Step 7 OEM certification |
| System picker interaction | SAF UX | Android DocumentsUI | Arbitrary third-party tree grant | Production URI-grant contract used; headless CI substitutes deterministic grant | ENVIRONMENT-LIMITED | API-35 provider integration | Deferred Step 7 |

## Final automated functional certification gate

Run `34336655608` (#139), implementation head `f21bd3705087cc65620e772e4a549c69baf4b443`, passed:

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest   # API 35 emulator
```

The first Gradle phase reported `BUILD SUCCESSFUL` with `assembleDebug`, JVM tests, lint, release assembly and instrumentation compilation all successful. The API-35 phase reported **9 tests completed, 0 skipped, 0 failed** and `BUILD SUCCESSFUL`.

Failures found on earlier Step 2 heads were corrected rather than suppressed. The final repaired design includes durable Replace process-death recovery, durable batch-rename rollback recovery, safe capability-gated Replace, native-move-aware free-space planning, weak-provider partial-file protection, and the production SAF corrections from earlier certification.

Physical phone certification intentionally deferred to Step 7 per project plan.
