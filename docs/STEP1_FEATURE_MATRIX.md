# Step 1 Feature Matrix

| Requirement | Status | Evidence |
|---|---|---|
| Native Kotlin Android / Compose | COMPLETE | `app` uses Kotlin, Compose and Material 3 |
| Provider-neutral storage model | COMPLETE | `core/model`, `StorageProvider`, `BrowserStorage` |
| URI-first resources | COMPLETE | `FileReference` supports URI-only entries |
| 64-bit sizes | COMPLETE | all byte sizes/capacities are `Long` |
| Dynamic local volume discovery | COMPLETE | `StorageRepository` + `StorageManager` |
| Local browsing | COMPLETE | `LocalStorageProvider` |
| SAF tree locations | COMPLETE | persisted tree permissions + `SafStorageProvider` |
| Images / Videos / Audio categories | COMPLETE | MediaStore provider |
| Documents category | COMPLETE | filtered MediaStore Files query |
| Downloads category | COMPLETE | MediaStore Downloads on API 29+, safe legacy fallback before API 29 |
| APK category | COMPLETE | filtered MediaStore Files query |
| Storage capacity | COMPLETE where platform exposes it | `StatFs.totalBytes` / `availableBytes` |
| Recent locations | COMPLETE | DataStore, deduplicated, bounded to 12 |
| Last-location persistence | COMPLETE | DataStore `lastLocation` |
| Last-location restoration | COMPLETE | startup calls `restorableLastLocation`; missing local paths/revoked SAF grants are rejected |
| List view | COMPLETE | lazy stable-key list |
| Grid view | COMPLETE | adaptive lazy grid |
| View persistence | COMPLETE | DataStore |
| Name/date/size/type sorting | COMPLETE | `FileSorter` |
| Natural name sorting | COMPLETE | `NaturalOrder` |
| Folders first | COMPLETE | persisted preference |
| Hidden files | COMPLETE | persisted preference |
| Refresh | COMPLETE | reloads current provider location |
| Breadcrumbs | COMPLETE | provider-specific logical breadcrumbs |
| Back/forward/up | COMPLETE | independent browser history + provider parent resolution |
| Loading / empty / permission / unavailable / I/O states | COMPLETE | explicit `BrowserUiState` |
| Image thumbnails | COMPLETE | sampled decode + EXIF handling / platform thumbnails |
| Video thumbnails | COMPLETE where provider/platform supports extraction | async platform/retriever path |
| Bounded thumbnail cache | COMPLETE | memory-aware `LruCache` |
| External open-with | COMPLETE | content URI + MIME + grant flags |
| System/light/dark theme | COMPLETE | persisted Material 3 theme |
| Responsive layout | COMPLETE | adaptive grid; Compose layout |
| Browser ViewModel JVM tests | IMPLEMENTED | direct fake-driven tests for load/filter/sort/history/errors/events/preferences |
| Core/unit tests | IMPLEMENTED | utility/model/history/codec + BrowserViewModel suite |
| Compose instrumentation | IMPLEMENTED | Home activity smoke test |
| Last-location instrumentation | IMPLEMENTED | valid local restore + removed-location rejection test |
| API 35 emulator execution | AUTOMATED | CI `instrumentation-api35` runs `connectedDebugAndroidTest` |
| CI build/test/lint | AUTOMATED | debug/release build, JVM tests, lint, API 35 instrumentation |
| Copy/move/delete engine | OUT OF STEP 1 SCOPE | Step 2 |
| Physical device certification | DEFERRED TO STEP 7 | project policy |

Physical phone certification remains intentionally deferred to Step 7. Emulator certification is separate and is executed automatically by CI; a green workflow run is the acceptance evidence for Step 1.
