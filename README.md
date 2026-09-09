# ZZ File Manager

ZZ File Manager is an original, clean-room, native Android file manager. The behavioral target is the convenience and feature coverage expected from a professional consumer file manager, while all production code, assets, package identity and implementation are independently created.

## Clean-room policy

The project may study common file-manager workflows and navigation patterns, but it does not copy source code, decompiled logic, layouts, icons, strings, branding, certificates, credentials or proprietary assets from File Manager + or any other reference application.

## Current status

**Step 2 of 7 — Professional file operations engine** is implemented and automated certification is complete on `step2/file-operations-engine` for functional implementation head `f21bd3705087cc65620e772e4a549c69baf4b443`. GitHub Actions run `34336655608` (#139) passed the clean build/JVM/lint/release/instrumentation-compile gate and the API-35 emulator suite with **9 tests, 0 skipped, 0 failed**. Step 1 remains preserved as the approved native browsing foundation. Final physical-phone certification is intentionally deferred to Step 7.

The verified Step 1 baseline commit is `2be3a6933931a1f7aa9ae3857e7adf2f910bca9e`.

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
- transactional batch rename preview/execution with find/replace, prefix, suffix and sequential numbering
- create real folder and zero-byte file
- centralized file/directory collision engine
- Replace / Skip / Keep both / directory Merge / compatible Apply to all
- capability-gated Replace: offered only when atomic replace exists or a reversible rename+delete transaction is possible
- predictable keep-both names that preserve file extensions
- persistent FIFO operation queue with item-level results
- SQLite-backed operation journal and process-death reconciliation
- durable Replace transaction ledger across backup/commit/recovery boundaries
- durable batch-rename phase/rollback ledger
- byte/item progress with throttled persistence
- cooperative pause/resume/cancel
- retry with a new operation identity and tracked-partial cleanup context
- operation details/progress sheet in the browser
- dedicated Android file-operation notification channel and controls
- user-started foreground-service execution host separated from operation logic
- Android 15 `dataSync` timeout reconciliation to a safe interrupted/recoverable journal state
- local + SAF writable provider capability layer
- tree-aware SAF CRUD using `DocumentsContract`, opaque document IDs and provider mutation flags
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
User-started dataSync Foreground Service
```

Android 14+ user-initiated JobScheduler jobs are intentionally not used for Step 2 local/SAF transfers because that API mode is for user-requested network data transfers. Keeping the Android host behind `OperationExecutionHost` lets later network-provider steps choose a different compliant host without changing the file-operation engine.

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

The Step 2 workflow executes:

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest   # API 35 emulator
```

Functional certification run `34336655608` (#139) completed successfully. The API-35 emulator executed **9 tests, 0 skipped, 0 failed**.

Coverage includes fake-provider JVM tests for queue/copy/move/delete/collision/space/interruption/race behavior, Replace failure and process-death recovery, durable Replace JSON ledger fields, capability-gated Replace, batch-rename rollback/process-death recovery, `Long` counter and recovery tests, duplicate-enqueue protection, a 10,000-source queue test, production local-provider Android operations, production SAF-provider tree CRUD/navigation/ancestry plus engine copy/move, Step 2 Compose interaction coverage, and Step 1 restoration/smoke regressions.

The SAF integration tests use a debug-only deterministic `DocumentsProvider` grant because headless CI cannot reliably drive the system `ACTION_OPEN_DOCUMENT_TREE` UI. The production `SafStorageProvider` itself is unchanged for the test and still executes through Android `ContentResolver`/`DocumentsContract` scoped-tree rules.

## Large-file and interruption safety

There is no application-level 2 GB, 4 GB, 10 GB, or 30 GB ceiling. All byte quantities use `Long`, while actual copies use a fixed bounded buffer (`256 KiB` by default). The engine never allocates memory based on source length and does not use whole-file `readBytes()` transfer logic.

Where safe rename is supported, data is written to tracked `.zzpart-*` output and finalized only after stream completion and expected-byte validation. Cancel/pause/failure attempts to remove partial output. A move never deletes the source before successful destination creation.

Replacing an existing destination is destructive only after the replacement has been fully staged. Local storage uses atomic replacement when the filesystem supports it. Providers without atomic replace use a durable reversible transaction: journal intent → rename the old destination to a hidden safety backup → journal phase → rename staged output to final → verify → delete backup → clear ledger. Process death or cancellation at a destructive boundary leaves enough persisted state to restore or complete the transaction without guessing.

Process death or an Android foreground-service timeout never becomes a false success. Unsafe running states reconcile to `INTERRUPTED`; completed file boundaries remain recorded, while an interrupted current file restarts when byte-level resume cannot be proven safe for the provider.

## Storage access and security

Android 11+ broad storage capability is handled through the platform `MANAGE_EXTERNAL_STORAGE` settings flow because this is a genuine file-manager use case. The app does not assume permission is granted and remains usable through persisted SAF locations and MediaStore categories.

Step 2 writes retain logical-root containment, canonical local-path validation, scoped SAF trees, safe leaf-name validation, symbolic-link loop protection, self/descendant-copy prevention, and provider-neutral persisted references. SAF URIs are not converted into fake local paths; document IDs are treated as opaque, tree containment is verified, and provider mutation flags are respected before create/write/delete/rename operations.

## Physical-device testing

**Physical phone certification is intentionally deferred to Step 7.** Step 2 uses JVM tests, fake providers, static/lint analysis, release compilation, API-35 emulator instrumentation, real app-private local-provider I/O and production SAF-provider integration against an instrumented DocumentsProvider. Hardware/OEM-specific final certification remains a Step 7 requirement.

## Step documentation

- [`docs/STEP2_ARCHITECTURE.md`](docs/STEP2_ARCHITECTURE.md)
- [`docs/STEP2_FEATURE_MATRIX.md`](docs/STEP2_FEATURE_MATRIX.md)
- [`docs/STEP2_TEST_MATRIX.md`](docs/STEP2_TEST_MATRIX.md)
- [`docs/STEP2_COMPLETION_REPORT.md`](docs/STEP2_COMPLETION_REPORT.md)
- [`docs/STEP1_FEATURE_MATRIX.md`](docs/STEP1_FEATURE_MATRIX.md)
