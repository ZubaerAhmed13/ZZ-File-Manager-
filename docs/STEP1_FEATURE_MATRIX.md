# Step 1 Feature Matrix

| Requirement | Status | Evidence |
|---|---|---|
| Native Kotlin Android / Compose | COMPLETE | `app` uses Kotlin, Compose and Material 3 |
| Provider-neutral storage model | COMPLETE | `core/model`, `StorageProvider` |
| URI-first resources | COMPLETE | `FileReference` supports URI-only entries |
| 64-bit sizes | COMPLETE | all byte sizes/capacities are `Long` |
| Dynamic local volume discovery | COMPLETE | `StorageRepository` + `StorageManager` |
| Local browsing | COMPLETE | `LocalStorageProvider` |
| SAF tree locations | COMPLETE | persisted tree permissions + `SafStorageProvider` |
| Media categories | COMPLETE | image/video/audio MediaStore provider |
| Storage capacity | COMPLETE where platform exposes it | `StatFs.totalBytes` / `availableBytes` |
| Recent locations | COMPLETE | DataStore, deduplicated, bounded to 12 |
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
| Unit tests | IMPLEMENTED | utility/model/history/codec test suite |
| Compose instrumentation | IMPLEMENTED | basic Home smoke test; emulator execution depends on CI runner setup |
| CI build/test/lint | IMPLEMENTED | `.github/workflows/android.yml` |
| Copy/move/delete engine | OUT OF STEP 1 SCOPE | Step 2 |
| Physical device certification | DEFERRED TO STEP 7 | project policy |

Physical phone certification intentionally deferred to Step 7 per project plan.
