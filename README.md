# ZZ File Manager

ZZ File Manager is an original, clean-room, native Android file manager. The behavioral target is the convenience and feature coverage expected from a professional consumer file manager, while all production code, assets, package identity and implementation are independently created.

## Clean-room policy

The project may study common file-manager workflows and navigation patterns, but it does not copy source code, decompiled logic, layouts, icons, strings, branding, certificates, credentials or proprietary assets from File Manager + or any other reference application.

## Current status

**Step 2 of 7 — Professional file operations engine** is implemented on `step2/file-operations-engine` and is under exact-head automated certification. Step 1 remains preserved as the approved native browsing foundation.

### Step 1 foundation preserved

- Kotlin + Jetpack Compose + Material 3 native app
- provider-neutral storage architecture with URI-first references
- dynamic local volume discovery with real `Long` capacity values
- local directory browsing constrained to each logical storage root
- Storage Access Framework tree grants with persisted permissions
- MediaStore category views
- list/grid browser, sorting, breadcrumbs, Back/Forward/Up, thumbnails and persisted preferences
- cancellation-aware directory loading
- no recursive startup scan and no arbitrary whole-file buffering

### Step 2 file-operation capabilities

- long-press multi-selection with stable IDs and Select all
- internal persistent COPY/CUT clipboard with visible Paste/Cancel bar
- copy and move across local/SAF provider boundaries
- safe native local move optimization where available
- permanent delete with explicit destructive confirmation
- individual rename
- professional batch rename preview with find/replace, prefix, suffix and sequential numbering
- create real folder and zero-byte file
- centralized file/directory collision engine
- Replace / Skip / Keep both / directory Merge / compatible Apply to all
- predictable keep-both names that preserve file extensions
- persistent FIFO operation queue with item-level results
- SQLite-backed operation journal and process-death reconciliation
- byte/item progress with throttled persistence
- cooperative pause/resume/cancel
- retry with a new operation identity
- operation details/progress sheet in the browser
- dedicated Android file-operation notification channel and controls
- user-initiated background execution host separated from operation logic
- API 34+ user-initiated JobScheduler strategy with foreground-service fallback
- local + SAF writable provider capability layer
- basic properties and Android-standard one/multiple file sharing through content URIs
- automatic browser refresh after terminal operations

## Operation architecture

```text
Compose Browser UI
  -> BrowserOperationsViewModel
    -> FileOperationController
      -> OperationRepository / persistent journal
        -> FileOperationEngine
          -> StorageProviderRegistry
            -> LocalStorageProvider
            -> SafStorageProvider
```

Android execution is a separate replaceable host:

```text
FileOperationEngine
        ↑
OperationExecutionHost
        ↑
JobScheduler user-initiated job / Foreground service
```

This keeps transfer correctness testable independently from Compose and Android process infrastructure.

## Toolchain

- minSdk: 26
- targetSdk: 35
- compileSdk: 35
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.1.0
- Gradle: 8.9
- Java/JDK: 17
- Step 2 app version: `0.2.0-step2`

## Build and verification

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

GitHub Actions runs the build/unit/lint/release gate on `step1/**`, `step2/**`, and `main`, then runs `connectedDebugAndroidTest` on an API-35 emulator after the build gate succeeds.

Step 2 includes fake-provider JVM tests for queue/copy/move/delete/collision/low-space behavior, Long-counter/recovery/batch-rename tests, and API-35 instrumentation that exercises real app-private local create/copy/move/rename/delete flows.

## Large-file and interruption safety

There is no application-level 2 GB, 4 GB, 10 GB, or 30 GB ceiling. All byte quantities use `Long`, while actual copies use a fixed bounded buffer (`256 KiB` by default). The engine never allocates memory based on source length and does not use whole-file `readBytes()` transfer logic.

Where safe rename is supported, data is written to tracked `.zzpart-*` output and finalized only after stream completion and expected-byte validation. Cancel/pause/failure attempts to remove partial output. A move never deletes the source before successful destination creation.

Process death never becomes a false success. Unsafe running states reconcile to `INTERRUPTED`; completed file boundaries remain recorded, while an interrupted current file restarts when byte-level resume cannot be proven safe for the provider.

## Storage access and security

Android 11+ broad storage capability is handled through the platform `MANAGE_EXTERNAL_STORAGE` settings flow because this is a genuine file-manager use case. The app does not assume permission is granted and remains usable through persisted SAF locations and MediaStore categories.

Step 2 writes retain logical-root containment, canonical local-path validation, scoped SAF trees, safe leaf-name validation, symbolic-link loop protection, self/descendant-copy prevention, and provider-neutral persisted references. SAF URIs are not converted into fake local paths.

## Physical-device testing

**Physical phone certification is intentionally deferred to Step 7.** Step 2 uses JVM tests, fake providers, static/lint analysis, release compilation, and API-35 emulator instrumentation. Hardware-specific final certification remains a Step 7 requirement.

## Step documentation

- [`docs/STEP2_ARCHITECTURE.md`](docs/STEP2_ARCHITECTURE.md)
- [`docs/STEP2_FEATURE_MATRIX.md`](docs/STEP2_FEATURE_MATRIX.md)
- [`docs/STEP2_TEST_MATRIX.md`](docs/STEP2_TEST_MATRIX.md)
- [`docs/STEP1_FEATURE_MATRIX.md`](docs/STEP1_FEATURE_MATRIX.md)
