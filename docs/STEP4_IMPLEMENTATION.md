# Step 4 Implementation Guide

## Purpose

This document maps the implemented Step 4 product behavior to the actual source structure in `step4/archive-media-text-apk-analyzer`. It is intended to answer **where each Step 4 feature lives, how the major flows execute, which provider/recovery contracts they depend on, and how they are certified**.

For design rationale see `STEP4_ARCHITECTURE.md`. For security/data-safety rules see `STEP4_SECURITY.md`. For acceptance coverage see `STEP4_TEST_MATRIX.md`.

## Step 4 composition

Step 4 is an additive layer over the Step 1–3 application. It does not replace the existing browser, storage-provider registry, operation engine, search, library, or recycle-bin implementation.

The central dependency wiring is in:

`app/src/main/java/com/zz/filemanager/app/AppContainer.kt`

`AppContainer` constructs the Step 4 services from the existing storage repository and shared Android context:

- `Step4WriteJournal`
- `SafeOutputWriter`
- `ArchiveManager`
- `TextFileEngine`
- `MediaInspector`
- `ApkManager`
- `StorageAnalyzer`

The shared `SafeOutputWriter` is deliberately injected into archive, text and APK subsystems so those features do not grow independent write semantics.

## Source map

### Application / UI entry points

- `app/src/main/java/com/zz/filemanager/ZZFileManagerApplication.kt`
  - creates the shared `AppContainer` lazily;
  - schedules `Step4RecoveryWorker` through WorkManager on application startup.
- `app/src/main/java/com/zz/filemanager/Step4FileActivity.kt`
  - internal file-viewer host for Step 4 file types;
  - contains archive/image/video/audio/text/APK viewing flows and viewer actions.
- `app/src/main/java/com/zz/filemanager/Step4ToolsActivity.kt`
  - hosts Step 4 tool screens such as Installed Apps, Analyze Storage and Create Archive.
- `app/src/main/java/com/zz/filemanager/core/step4/Step4OpenCodec.kt`
  - encodes/decodes internal Step 4 open requests using existing provider-scoped references and parent/root context.

### Transaction / recovery core

Directory:

`app/src/main/java/com/zz/filemanager/core/step4/`

Files:

- `Step4Transactions.kt`
  - `Step4WritePhase`
  - `Step4OutputKind`
  - `Step4MemberProof`
  - `Step4WriteTransaction`
  - `SafeWriteResult`
  - `Step4WriteJournal`
  - `SafeOutputWriter`
- `Step4RecoveryWorker.kt`
  - startup WorkManager bridge that invokes transaction reconciliation.
- `Step4OpenCodec.kt`
  - internal viewer routing codec.

### Archive subsystem

Directory:

`app/src/main/java/com/zz/filemanager/core/archive/`

Files:

- `ArchiveManager.kt` — browse/list/extract/create implementation.
- `ArchiveModels.kt` — archive request/result/domain models.
- `ArchiveSecurity.kt` — archive path normalization and suspicious-expansion safety logic.
- `ArchiveCompat.kt` — archive-library compatibility helpers.

### Text subsystem

Directory:

`app/src/main/java/com/zz/filemanager/core/text/`

Files:

- `TextFileEngine.kt` — encoding detection, line-ending handling, search, large-file read windows, fingerprinting, save/save-as integration.
- `TextModels.kt` — text document/search/state models.

### APK subsystem

Directory:

`app/src/main/java/com/zz/filemanager/core/apk/`

Files:

- `ApkManager.kt` — passive APK inspection, installed-app support, base backup, complete split-set backup.
- `ApkModels.kt` — APK/app metadata and backup models.

### Media subsystem

Directory:

`app/src/main/java/com/zz/filemanager/core/media/`

Files:

- `MediaInspector.kt` — image/audio/video metadata inspection and image decode support.
- `MediaMetadata.kt` — media metadata models.

Playback UI uses AndroidX Media3/ExoPlayer from `Step4FileActivity.kt`.

### Storage analyzer

Directory:

`app/src/main/java/com/zz/filemanager/core/analyzer/`

Files:

- `StorageAnalyzer.kt` — iterative scan, category totals, largest entries, empty entries, duplicate detection.
- `AnalyzerModels.kt` — analyzer configuration/result models.

## Application startup and recovery

`ZZFileManagerApplication.onCreate()` enqueues one unique `Step4RecoveryWorker` through WorkManager.

The worker exists because Step 4 transactional writes are independent of the queued Step 2 file-operation journal. Archive extraction/creation, text saves and APK backup can create hidden Step 4 transaction artifacts, so recovery must run even when no queued `FileOperation` exists.

Startup recovery flow:

1. application starts;
2. WorkManager schedules/replaces the unique Step 4 recovery job;
3. `Step4RecoveryWorker` obtains the app container/storage transaction services;
4. the durable `Step4WriteJournal` is read;
5. each transaction is reconciled according to its phase, output kind and persisted proof;
6. safe pre-commit leftovers may be removed;
7. ambiguous commit state remains journaled/recoverable rather than being guessed complete.

## Internal file-routing implementation

`Step4OpenCodec` carries an already-authorized `ScopedFileReference` plus context needed by the internal viewer. The route does not grant new storage authority.

Browser behavior remains unchanged for directories. For Step 4-supported file types, the browser/open flow sends the file to `Step4FileActivity`. Unsupported/unknown files continue to the existing external-open behavior.

The internal activity decides the concrete viewer mode from file type/MIME/extension and uses the injected `AppContainer` services for the actual work.

## SafeOutputWriter implementation

`SafeOutputWriter` is the central write primitive for Step 4-generated or copied file content.

### Core transaction model

`Step4WriteTransaction` persists:

- transaction `id`;
- parent `BrowserLocation`;
- intended `finalName`;
- hidden `stagedName`;
- optional `backupName`;
- optional `expectedBytes` as `Long`;
- `Step4WritePhase`;
- `Step4OutputKind` (`FILE` or `DIRECTORY`);
- whether an existing destination is being replaced;
- staged provider `mutationIdentity` when available;
- staged content SHA-256 fallback proof;
- direct directory-member proofs for transactional directory output.

The journal uses synchronous SharedPreferences `commit()` so a phase/proof update is durably requested before the operation advances to the next destructive stage.

### File write entry points

`SafeOutputWriter.write(...)`

Used when the caller has an `InputStream`. It delegates into `writeGenerated(...)` so copied content and generated content share the same transaction implementation.

`SafeOutputWriter.writeGenerated(...)`

Used when a caller produces bytes into an `OutputStream`, such as archive creation. The producer receives the hidden staged output stream rather than the user-requested final file.

### File staging sequence

The file flow is:

1. resolve the destination provider from `StorageProviderRegistry`;
2. require writable create/rename/delete capability needed for staged finalization;
3. apply collision policy (`SKIP`, `KEEP_BOTH`, `REPLACE`; `MERGE` is invalid for files);
4. generate the transaction ID and hidden `.zzstage-*` name;
5. write the durable `STAGING` journal record;
6. create the hidden stage;
7. stream/copy/generate output with bounded buffers;
8. count bytes with `Long` and compute SHA-256 while writing;
9. verify expected/provider-reported size where available;
10. capture provider `mutationIdentity` where available;
11. persist the completed staged proof;
12. re-read destination state to detect a collision/race that appeared while staging;
13. finalize as a fresh write or replacement transaction;
14. prove that the final object is the staged object;
15. mark `COMMITTED`, remove obsolete backup if applicable, and clear the journal.

The final filename is never the producer/copy streaming destination.

### Collision handling

The destination is checked both before staging and immediately before final mutation.

- `SKIP` returns the existing item without mutation.
- `KEEP_BOTH` resolves a unique name.
- `REPLACE` stages first, then uses the provider replacement path.
- A destination that changes type/state while staging causes a safe failure instead of blindly applying the original plan.

### Replacement implementation

Replacement prefers provider `replaceAtomically` when the provider advertises the capability.

When atomic replacement is unavailable but safe rename/delete semantics exist, the writer uses the journaled backup sequence:

1. plan hidden `.zzbackup-*`;
2. persist backup phase;
3. move the authoritative old object to backup;
4. persist backed-up phase;
5. rename hidden staged object to final name;
6. prove the final object;
7. delete backup only after proof succeeds.

### Commit proof

The proof decision is intentionally asymmetric:

- if a durable provider mutation identity was captured for the staged object, final identity must match it;
- only when provider identity is unavailable does the writer use streaming SHA-256 fallback;
- filename plus size is never accepted as proof.

This prevents recovery from confusing an unrelated same-size object with the staged output.

## Transactional directory implementation

`SafeOutputWriter.writeDirectoryAtomically(...)` is used for output that is only valid as a complete directory set, currently complete split-APK backup.

The directory flow requires destination capabilities for:

- directory creation;
- child file creation;
- rename;
- delete.

Implementation sequence:

1. resolve collision/final folder name;
2. persist the directory transaction before creating the hidden stage;
3. create hidden `.zzapkbackup-*` (or caller-selected hidden prefix) directory;
4. let the producer populate only that directory;
5. enumerate direct staged members;
6. compute/store each member's name, size and SHA-256 proof;
7. capture staged directory mutation identity when the provider exposes one;
8. persist the completed proof set;
9. rename the complete directory into the requested visible name;
10. prove final directory identity when possible, otherwise prove exact member set plus streaming per-member SHA-256;
11. clear the journal only after proof.

Handled pre-commit failure recursively removes the hidden transaction directory without creating the visible final folder.

## Archive implementation

`ArchiveManager` supports:

- ZIP;
- TAR;
- TAR.GZ;
- TAR.BZ2;
- TAR.XZ;
- 7z;
- RAR (read/extract);
- ZIP/TAR-family creation.

### Listing/browsing

Archive listing normalizes entry paths and creates a browseable hierarchy for the UI. Formats that can be read sequentially use streamed access where practical. Parsers requiring random access may use a temporary capacity-checked stage rather than buffering the complete file into RAM.

### Extraction

Extraction flow:

1. inspect/list selected archive entries;
2. validate every destination-relative path;
3. reject unsafe traversal/absolute/drive/NUL paths and ambiguous normalized duplicates;
4. evaluate suspicious expansion / destination capacity;
5. resolve collision policy;
6. for each output file, write through `SafeOutputWriter`;
7. propagate progress/cancellation;
8. clean uncommitted staged output on handled failure.

Extraction therefore uses the same replacement/fresh-write guarantees as text/APK output.

### Creation

Archive creation calls `SafeOutputWriter.writeGenerated(...)`.

The archive library writes ZIP/TAR-family bytes into the hidden `.zzstage-*` stream. Only after the archive/container stream closes successfully can the transaction finalize the requested name.

ZIP creation exposes compression selection and optional AES password support. TAR-family creation wraps the staged stream in the appropriate compressor/container chain.

## Text implementation

`TextFileEngine` provides the data layer behind the text viewer/editor.

### Open/detection

The engine detects:

- UTF-8;
- UTF-8 BOM;
- UTF-16 LE;
- UTF-16 BE;
- guarded platform fallback for likely text.

It detects LF vs CRLF and records the line-ending mode so save behavior can preserve the document convention.

Likely binary files are rejected from ordinary editable handling.

### Editable vs large read-only mode

The engine derives a safe editable threshold from current VM heap headroom. It does not advertise a fixed product-wide maximum.

When a file is larger than the safe editable bound, the engine uses streamed read-only line windows rather than loading the whole file into memory.

### Search

Text search streams through the source and returns bounded matches/progress instead of materializing an unlimited result set.

### External-change protection

The opened source receives a fingerprint. Before normal overwrite, the engine re-checks source state. If the file changed externally, save does not silently overwrite it; the UI surfaces Reload / Save As / Overwrite decisions.

### Save

- normal Save uses replacement semantics through `SafeOutputWriter`;
- Save As uses the fresh staged-output flow;
- requested Save As filename is never used as the streaming target.

## APK implementation

`ApkManager` handles both standalone APK inspection and installed-app tooling.

### APK inspection

The manager uses Android package APIs to inspect without executing the APK. Returned information includes, where available:

- app label;
- package name;
- version name/code;
- minimum/target SDK;
- requested permissions;
- signing-certificate SHA-256.

Malformed APK input returns an inspection failure rather than executing content.

### Install/uninstall

Install and uninstall are handoffs to Android-controlled confirmation UI. Step 4 does not implement silent package installation/removal.

### Installed apps

App enumeration/search operates under Android package-visibility rules. The manifest intentionally avoids `QUERY_ALL_PACKAGES`.

### Base backup

Base-only backup obtains the APK source and streams it through `SafeOutputWriter.write(...)` into the selected destination. It is labeled as a base-only export when splits exist; the implementation does not treat it as equivalent to a complete reinstallable split set.

### Complete split backup

Complete split backup uses the directory transaction described above:

1. construct the requested `<App>-<version>-apks` folder name;
2. create hidden `.zzapkbackup-*` transaction directory;
3. copy `base.apk`;
4. copy each split with sanitized/unique component name;
5. count and hash every copied component;
6. verify destination metadata/length;
7. write `zz-apk-backup-manifest.json` last;
8. re-read/validate `complete=true` and component count;
9. let `SafeOutputWriter` independently hash/prove direct directory members;
10. rename the entire set into visibility.

The manifest includes schema version, set ID, package/version information, component count, component names, byte sizes and SHA-256 digests.

## Media implementation

`MediaInspector` provides metadata and image decode support over provider-backed references.

### Images

Image viewing uses sampled decode to avoid allocating full-resolution pixels unconditionally. The UI provides bounded zoom/pan and previous/next navigation through readable sibling images in the current parent.

Metadata includes dimensions/basic fields and available EXIF information such as camera/orientation/exposure/ISO/focal-length/GPS-presence fields.

### Video/audio

Playback is hosted by `Step4FileActivity` with AndroidX Media3 ExoPlayer/player controls.

Video supports fullscreen/landscape behavior with system UI restored when leaving the mode. Audio uses the same playback foundation.

Metadata/parser failure is handled independently so malformed media does not intentionally crash the entire Step 4 activity.

## Storage analyzer implementation

`StorageAnalyzer` performs iterative traversal through the provider abstraction.

The scan accumulates:

- category byte totals;
- largest files;
- largest folders;
- extension counts;
- empty file/folder information;
- capacity/free-space context where available;
- warnings for inaccessible/problematic entries.

Top-result structures are bounded by configuration rather than retaining the complete filesystem graph.

### Duplicate pipeline

Duplicate verification is deliberately three-stage:

1. group candidates by exact file size;
2. compute sampled beginning/middle/end SHA-256 for candidate reduction;
3. compute full-file SHA-256 for survivors before reporting a verified duplicate set.

The sampled hash is a performance optimization only, never final duplicate proof.

No automatic duplicate deletion is performed by the analyzer.

## Large-file implementation rules

Step 4 uses `Long` for core byte sizes and counters. There is no intentional 2 GiB/4 GiB product limit introduced by Step 4.

Implementation patterns include:

- bounded copy/hash buffers;
- streamed archive extraction/creation where formats allow;
- random-access staging only where parser requirements demand it;
- sampled image decode;
- heap-aware text editing and large-file read windows;
- iterative analyzer traversal;
- bounded top-N retained result sets;
- sampled duplicate hashing followed by full verification;
- cancellation checks inside long operations.

Provider/device/parser constraints may still make a specific file impractical; the design requirement is to fail safely rather than overflow a 32-bit counter or partially expose final output.

## UI integration

### Step4FileActivity

The internal viewer hosts the file-type-specific UI while keeping common file actions available. Depending on type, it delegates to:

- `ArchiveManager` for archive browse/extract;
- `MediaInspector` and Media3 for image/media;
- `TextFileEngine` for text;
- `ApkManager` for APK details/backups/system handoffs.

Common actions such as Properties, Share, Open with, Favorites and Recycle Bin remain connected to the existing Step 1–3 services rather than being reimplemented inside each viewer.

### Step4ToolsActivity

The tools host provides the non-file-open Step 4 workflows:

- Installed Apps;
- Analyze Storage / Find duplicates;
- Create Archive.

These use the same service instances from `AppContainer`, so the analyzer/archive/APK behavior tested at core level is the behavior used by the screens.

## Provider capability contract

Step 4 must not infer transaction support from writability alone.

For staged **file** output, the provider must support the operations required to create an incomplete hidden file and later rename/delete it safely.

For staged **directory** output, the provider must also support hidden directory creation and child-file creation.

When these capabilities are missing, Step 4 reports the operation as unsupported instead of directly writing to the requested final name.

This is an implementation requirement, not merely documentation guidance.

## Failure and cancellation behavior

Handled producer/parser/copy failures propagate to the calling UI while cleanup removes only uncommitted hidden state that is safe to discard.

Cancellation follows the same rule: do not turn partial staged data into final output and do not destroy the authoritative replacement destination.

Process death is different from handled failure because ordinary cleanup code may not run. That case is why the journal is persisted before hidden-stage creation and why `Step4RecoveryWorker` runs at startup.

## Certification implementation

Permanent CI is defined in:

`.github/workflows/android.yml`

The Step 4 gate executes the equivalent of:

```text
clean
assembleDebug
testDebugUnitTest
lintDebug
assembleRelease
assembleDebugAndroidTest
connectedDebugAndroidTest on API 35
```

CI separately inspects JVM and instrumentation XML and fails if skipped tests are present.

Instrumentation classes include:

- `Step4CertificationInstrumentationTest`
- `Step4CoreBehaviorInstrumentationTest`

The connected core behavior suite includes explicit regressions for the transactional blockers: hidden fresh staging, process-death cleanup, same-size wrong-content recovery, generated/archive failure visibility, interrupted complete split backup, and successful complete split backup with manifest.

## Implementation acceptance rule

This file is descriptive documentation, not a substitute for executable proof. The implementation is accepted only when the permanent `Android Step 4 CI` run for the **exact current branch head** succeeds with:

- debug build;
- JVM tests;
- zero skipped JVM tests;
- lint;
- release build;
- androidTest compilation;
- API-35 emulator boot;
- real `connectedDebugAndroidTest` execution;
- zero failed instrumentation tests;
- zero skipped instrumentation tests;
- no temporary patch/helper workflow remaining.

Physical-phone certification remains intentionally deferred to the final overall project step.