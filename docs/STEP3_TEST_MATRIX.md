# ZZ File Manager — Step 3 Test Matrix

| Test | Subsystem | Provider | Failure/scenario | Expected behavior | Result | Mode | CI evidence |
|---|---|---|---|---|---|---|---|
| Matcher filters | Search | Model | Unicode/case/extension/type/size/date/hidden | Correct local match without filename mutation | PASS | JVM | Final exact-head run |
| Recursive/non-recursive | Search | Fake | Explicit scope | Descend only when selected | PASS | JVM | Final exact-head run |
| Partial provider failure | Search | Fake multi-root | One root throws | Keep valid results and report one failure | PASS | JVM | Final exact-head run |
| 100,000 entries | Search | Fake | Very large flat listing | Batches ≤128; accurate 100,000 total | PASS | JVM | Final exact-head run |
| Deep tree | Search | Fake | 5,000 nested folders | No stack overflow; target found | PASS | JVM | Final exact-head run |
| Symbolic link | Search | Fake | Directory link cycle | Link is not traversed | PASS | JVM | Final exact-head run |
| Cancellation | Search | Fake | Cancel after streamed batches | No terminal success from cancelled traversal | PASS | JVM | Final exact-head run |
| Superseded-query race | Search/ViewModel | Fake delayed provider | Slow A starts; fast B supersedes it | B remains authoritative; only B enters history | PASS | JVM | Final exact-head run |
| Sort modes | Search | Model | Every supported sort | Deterministic complete result set | PASS | JVM | Final exact-head run |
| Metadata restart | Library | SQLite/Android | Repository recreation | Favorite/search data reloads | PASS | Instrumentation | Final exact-head run |
| Retention and clear | Library | SQLite/Android | 60 recents/searches, 110 activities | 50/50/100 retained; all clear independently | PASS | Instrumentation | Final exact-head run |
| Restore Replace ledger restart | Library/Restore | SQLite/Android | COMMITTING record recreated | Phase, stage, identity and committed reference survive restart with 30 GiB `Long` intact | PASS | Instrumentation | Final exact-head run |
| Favorite lifecycle | Library | Fake | Add twice/remove/missing | Unique toggle; unavailable validation; metadata-only removal | PASS | JVM | Final exact-head run |
| Trash relationship | Library/Trash | Fake writable | Favorite/recent trashed and restored | Trashed status then valid restored reference | PASS | JVM | Final exact-head run |
| 30 GiB trash/restore | Trash | Fake writable | 30 GiB logical, 1 GiB free | Native moves; no copy buffer/output stream; restored | PASS | JVM | Final exact-head run |
| Duplicate names | Trash | Fake writable | Same name trashed twice | Independent UUID records/payload containers | PASS | JVM | Final exact-head run |
| Restore collision | Restore | Fake writable | Destination name exists | Cancel collision then deliberate Keep Both | PASS | JVM | Final exact-head run |
| Restore Replace success | Restore | Identity fake | Same-name destination, user selects Replace | Atomic Replace succeeds; restored data final; trash record removed | PASS | JVM | Final exact-head run |
| Restore Replace post-commit death | Restore | Identity fake | `replaceAtomically()` succeeds; process dies before catalog finalization | Reconcile proves final by staged mutation identity and finalizes restore exactly once | PASS | JVM | Final exact-head run |
| Restore Replace stage-move death | Restore | Identity fake | Payload moves to `.zzrestore-*`; process dies before STAGED write | Planned stage is discovered/adopted; payload remains recoverable | PASS | JVM | Final exact-head run |
| Restore Replace wrong final identity | Restore | Identity fake | Stage missing; same-named unrelated final remains | Never infer success; retain `INTERRUPTED` transaction and recovery evidence | PASS | JVM | Final exact-head run |
| Missing original parent | Restore | Fake writable | Original folder removed | Preserve payload; restore to chosen alternate | PASS | JVM | Final exact-head run |
| Non-native fallback | Trash/Step 2 | Fake writable | Native move disabled | Durable Step 2 MOVE queued; source remains before verification | PASS | JVM | Final exact-head run |
| Native crash window | Trash | Fake writable | Payload moved; state regressed to MOVING | Startup reconciliation proves TRASHED | PASS | JVM | Final exact-head run |
| Step 1 browser regressions | Browser | Fake/Android | Navigation/preferences | Approved browsing behavior remains | PASS | JVM/instrumentation | Final exact-head run |
| Step 2 operation regressions | Operations | Fake/Android/SAF | Copy/move/replace/recovery/batch/collision | Approved journal safety remains | PASS | JVM/instrumentation | Final exact-head run |
| API-35 full suite | Integration | Emulator API 35 | Clean install/headless environment | 0 failed, 0 skipped required tests | PASS required | CI | Exact final branch-head workflow |

No required deterministic test is ignored or conditionally skipped. The UI suite uses a deterministic debug `DocumentsProvider` for SAF because system-picker automation is not reliable in headless CI; production provider code is exercised through Android `ContentResolver`/`DocumentsContract`.
