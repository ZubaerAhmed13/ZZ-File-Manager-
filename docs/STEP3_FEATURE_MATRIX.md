# ZZ File Manager — Step 3 Feature Matrix

| Feature | Status | Evidence |
|---|---|---|
| Current-folder search | COMPLETE | `SearchCoordinator.resolveRoots`; non-recursive JVM test |
| Recursive search | COMPLETE | Iterative queue; recursive and 5,000-level deep-tree tests |
| Current-storage search | COMPLETE | Storage-ID-scoped roots in `SearchCoordinator` |
| All-locations search | COMPLETE | Accessible-root resolution and partial-provider-failure test |
| Search cancellation | COMPLETE | Coroutine cancellation checks plus ViewModel generation guard |
| Search streaming/bounds | COMPLETE | 64-item default batches, 5,000-result UI window, 100,000-entry test |
| Search filters | COMPLETE | Match, extension, type, custom/preset size/date, hidden and kind controls |
| Search sorting | COMPLETE | Relevance/name/date/size/type/location sorter and all-mode test |
| Search result actions | COMPLETE | Open, reveal, copy, move clipboard, share, favorite, properties, trash and explicit permanent delete |
| Search history | COMPLETE | Local bounded SQLite history and independent clear control |
| Favorites | COMPLETE | File/folder add/remove, duplicate identity, validation, sorting and persistent storage |
| Recent files | COMPLETE | Successful-open tracking, dedupe, validation, 50-item retention and clear |
| Recent locations | COMPLETE | Step 1 behavior preserved; independent clear control |
| Activity history | COMPLETE | Derived from Step 2 terminal events plus trash/restore events; bounded/clearable |
| App-managed trash | COMPLETE | Per-root reserved namespace, UUID containers, native move and Step 2 fallback |
| MediaStore trash | COMPLETE | API-30 platform confirmation, cancel-safe catalog update |
| Local restore | COMPLETE | Native original/alternate restore and safe collisions |
| SAF restore | COMPLETE WHERE CAPABLE | Native DocumentsContract move or Step 2 cross-location fallback |
| Restore collision | COMPLETE | Cancel, Keep Both, capability-gated Replace, Choose destination |
| Permanent trash deletion | COMPLETE | Confirmation, iterative deletion, provider-result/existence verification |
| Empty recycle bin | COMPLETE | App-managed partial counts and MediaStore confirmation |
| Retention | COMPLETE | Never/7/30/60/90; startup and daily WorkManager cleanup |
| Crash recovery | COMPLETE | Durable states, linked journal reconciliation and interrupted-state preservation |
| Orphan reconciliation | COMPLETE | Orphan payload preservation; missing-payload corrupted state |
| 30 GiB logical test | COMPLETE | Native trash+restore with 1 GiB free and zero output-stream opens |
| API-35 CI | COMPLETE | Exact-head clean/build/JVM/lint/release/instrumentation compile plus 13 emulator tests, 0 failed and 0 skipped |
| Physical certification | DEFERRED STEP 7 | Physical phone certification intentionally deferred to Step 7 per project plan. |

`COMPLETE WHERE CAPABLE` means the UI exposes only operations supported safely by the current provider; unsupported SAF implementations are reported rather than simulated by unsafe deletion.
