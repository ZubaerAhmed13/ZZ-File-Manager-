# ZZ File Manager

ZZ File Manager is an original, clean-room, native Android file manager. The behavioral target is the convenience and feature coverage expected from a professional consumer file manager, while all production code, assets, package identity and implementation are independently created.

## Clean-room policy

The project may study common file-manager workflows and navigation patterns, but it does not copy source code, decompiled logic, layouts, icons, strings, branding, certificates, credentials or proprietary assets from File Manager + or any other reference application.

## Current status

**Step 1 of 7 — Native foundation and local file browser** is implemented on `step1/native-foundation` for automated review. Step 2 write operations have intentionally not been started.

### Step 1 capabilities

- Kotlin + Jetpack Compose + Material 3 native app
- provider-neutral storage architecture with URI-first references
- dynamic local volume discovery with real `Long` capacity values
- local directory browsing constrained to each logical storage root
- Storage Access Framework tree grants with persisted permissions
- MediaStore Images, Videos and Audio categories with modern runtime permissions
- professional Home dashboard and recent locations
- list/grid browser with persisted view mode
- name/date/size/type sorting, natural filename ordering, ascending/descending and folders-first
- show/hide hidden files
- provider-aware breadcrumbs, Back, Forward and Up navigation
- refresh, empty/error/permission/unavailable states
- asynchronous bounded image/video thumbnails; sampled image decode and EXIF orientation handling
- external open-with using content URIs and MIME types
- System / Light / Dark themes
- settings persistence with DataStore
- cancellation-aware directory loading so stale navigation cannot overwrite a newer folder
- no recursive device scan on startup and no full-file buffering

## Architecture

```text
Compose UI
  -> ViewModels
    -> StorageRepository / PreferencesRepository
      -> StorageProvider
        -> LocalStorageProvider
        -> SafStorageProvider
        -> MediaStoreProvider
```

The storage contract is deliberately provider-neutral so later steps can add SD/USB refinements and network/cloud providers without rewriting the browser UI.

## Toolchain

- minSdk: 26
- targetSdk: 35
- compileSdk: 35
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.1.0
- Gradle: 8.9
- Java/JDK: 17

The repository includes a small bootstrapping `gradlew` script that downloads the pinned Gradle distribution when a standard wrapper JAR is not yet present.

## Build and verification

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

GitHub Actions runs build, unit tests and lint on pushes to `step1/**` and `main`.

Emulator instrumentation is intentionally separate from the normal CI job because emulator availability is infrastructure-dependent. The committed `MainActivityTest` is ready for `connectedDebugAndroidTest` when an emulator is available.

## Storage access model

Android 11+ broad storage capability is handled through the platform `MANAGE_EXTERNAL_STORAGE` settings flow because this is a genuine file-manager use case. The app does not assume permission is granted and remains usable through persisted SAF locations and MediaStore categories. It does not bypass protected Android directories.

## Large-file and large-directory safety

All byte quantities use `Long`. Directory enumeration and thumbnail work run off the main thread. Rendering is lazy. Images are sampled to target size rather than fully decoded for list thumbnails, video thumbnails are bounded, caches are limited, and arbitrary files are never loaded with whole-file byte arrays.

## Physical-device testing

**Physical phone certification intentionally deferred to Step 7 per project plan.** Step 1 verification relies on compilation, unit tests, lint, static review and emulator/instrumentation where infrastructure permits.

See [`docs/STEP1_FEATURE_MATRIX.md`](docs/STEP1_FEATURE_MATRIX.md) for the requirement matrix.
