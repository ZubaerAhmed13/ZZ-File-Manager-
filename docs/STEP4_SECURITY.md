# Step 4 Security Model

## Purpose

This document defines the security and data-safety contract for **Step 4 — Archive, Media, Text, APK and Storage Analyzer**. It is implementation-specific to the Step 4 branch and complements `STEP4_ARCHITECTURE.md`, `STEP4_IMPLEMENTATION.md`, `STEP4_FEATURE_MATRIX.md`, `STEP4_TEST_MATRIX.md`, and `STEP4_COMPLETION_REPORT.md`.

Step 4 is a local file-management feature set. Its primary security objective is not to make untrusted files "safe" in the abstract; it is to ensure that opening, inspecting, extracting, creating, editing, backing up, analyzing, and recovering files does not silently escape user-authorized storage, overwrite authoritative data without proof, expose incomplete output as complete, or convert malformed/untrusted input into destructive behavior.

## Security principles

Step 4 follows these rules:

1. **Existing storage authority is the boundary.** Step 4 operates only through already-authorized `StorageProvider` references or Android system-mediated actions. A viewer route does not manufacture new storage permission.
2. **Incomplete output stays hidden.** Fresh and replacement file writes use hidden staged objects and a durable journal. Requested final names are not used as streaming destinations.
3. **Uncertainty preserves data.** Recovery never treats filename plus size as proof of a successful commit. When identity/digest proof is absent or contradictory, authoritative backups/journal state are retained.
4. **Provider weakness must be explicit.** If a provider cannot support the create/rename/delete semantics required for the advertised transactional guarantee, the operation is rejected rather than silently downgraded to unsafe direct-to-final writing.
5. **Untrusted containers are normalized before extraction.** Archive paths are validated before destination mutation.
6. **Destructive actions remain user/system confirmed.** Duplicate analysis is informational; APK install/uninstall uses Android confirmation surfaces; no silent duplicate deletion or package mutation is introduced.
7. **Resource use is bounded where practical.** Large files are streamed, sampled, windowed, or temporarily staged only where a parser requires random access. Byte counters use `Long`.

## Trust boundaries

### StorageProvider boundary

`StorageProvider` / `WritableStorageProvider` is the storage capability boundary. Step 4 code receives provider-scoped references and must not reinterpret a provider reference as permission to access unrelated filesystem paths.

Local storage and SAF-backed storage remain distinct authority domains. Provider capabilities are checked before transactional writes. For file transactions, safe finalization requires creation of a hidden file plus rename/delete support. For complete split-APK directory transactions, safe finalization additionally requires hidden directory creation and child-file creation.

### Android system boundary

APK install, uninstall, App Info and similar package-management actions are handed to Android system UIs. Step 4 does not bypass Android confirmation or package visibility rules. `QUERY_ALL_PACKAGES` is intentionally not requested.

### Untrusted input boundary

Archives, media, text files and APKs may be malformed or adversarial. Parsers and decoders are treated as consuming untrusted input. Step 4 therefore validates archive paths independently, catches metadata/parse failures at subsystem boundaries, avoids treating parser metadata as authority for destructive writes, and keeps file mutation behind the safe-write layer.

## Transactional output security

### Threats addressed

The Step 4 transaction layer is designed to prevent:

- process death leaving a partial normal-looking final file;
- archive creation exposing an incomplete `*.zip`/TAR-family file under the requested name;
- replacement losing the old destination before the new object is proven;
- recovery deleting a known-good backup because an unrelated same-name/same-size final exists;
- a provider silently receiving weaker direct-to-final semantics than the UI/documentation claims;
- a partially copied split-APK set appearing as a complete visible backup folder.

### File transaction state

`Step4WriteTransaction` records the transaction identity, parent reference, final name, hidden staged name, optional backup name, expected bytes, phase, output kind, replacement intent, staged mutation identity, content SHA-256, and directory-member proofs when relevant.

File phases include:

- `STAGING`
- `STAGED`
- `ATOMIC_COMMITTING`
- `BACKUP_PLANNED`
- `BACKED_UP`
- `COMMITTING`
- `COMMITTED`

The journal record is persisted in `STAGING` **before** the hidden stage is created. This ordering is security-significant: startup recovery can distinguish an interrupted operation without relying on a visible final filename.

### Fresh file write invariant

A fresh output follows this invariant:

1. resolve the intended final name;
2. persist `STAGING`;
3. create `.zzstage-*`;
4. stream bytes into the hidden stage using bounded buffers while counting bytes and computing SHA-256;
5. verify byte count/provider metadata;
6. capture provider `mutationIdentity` when the provider exposes a durable one;
7. persist the staged proof;
8. re-check destination collision state;
9. mark `COMMITTING` and rename the complete hidden stage to the final name;
10. prove the final object is the staged object;
11. only then mark committed and clear the journal.

The requested final filename is never opened as the streaming target.

### Replacement invariant

Replacement never overwrites the authoritative destination with unverified bytes. The new content is fully staged first.

If the provider exposes an atomic replacement primitive, Step 4 uses it and then proves the final object. Otherwise the fallback sequence preserves the old object as `.zzbackup-*`, commits the staged object, proves the final, and deletes the backup only after proof succeeds.

A backup is recoverable data, not disposable cleanup, until commit proof is complete.

### Commit proof hierarchy

Commit proof is intentionally stronger than name/size matching:

- When the provider supplies a durable staged-object `mutationIdentity`, that identity is authoritative and the final object must match it.
- When durable provider identity is unavailable, Step 4 falls back to a streaming SHA-256 comparison against the digest captured for the staged content.
- Filename and byte size may support diagnostics, but **never** establish commit identity by themselves.

SHA-256 here is used as an integrity/identity fallback, not as authentication, access control, or encryption.

### Recovery behavior

`Step4RecoveryWorker` reconciles durable journal records after process death.

Pre-commit `STAGING`/`STAGED` state may remove an abandoned hidden stage without creating the requested final filename. Commit-phase state is resolved only when the final object can be proved as the staged object. If proof is missing, mismatched, or otherwise ambiguous, recovery retains recoverable data and transaction state instead of guessing.

Legacy journal records that lack the new strong-proof fields remain decodable, but missing proof is treated conservatively as uncertainty.

## Complete split-APK backup security

A complete split APK is a set-level artifact, not independent files that may safely become visible one by one.

`COMPLETE_SPLITS` therefore uses a hidden `.zzapkbackup-*` directory as the transaction namespace. The implementation:

1. journals the directory transaction before visible finalization;
2. creates the hidden directory;
3. streams `base.apk` and each required split into that hidden directory;
4. computes/verifies a SHA-256 and byte count for every component;
5. writes `zz-apk-backup-manifest.json` last with package/version metadata, component count, and per-component size/digest proof;
6. re-reads/validates the manifest;
7. independently proofs the staged directory's direct members;
8. renames the entire directory to the visible `<App>-<version>-apks` name only after the set is complete.

A handled failure/cancellation before finalization removes only the hidden transaction directory. A provider that cannot safely finalize the whole staged directory is explicitly unsupported for complete-set backup.

This prevents a process kill or missing split from leaving a partial folder that looks like a reinstallable complete backup.

## Archive security

### Path traversal and destination escape

Archive entry paths are normalized and validated before extraction. Step 4 rejects hostile forms including:

- absolute paths;
- Windows drive-prefixed paths;
- NUL-containing names;
- `..` traversal that could escape the selected destination;
- ambiguous duplicate normalized paths, including case-insensitive duplicates.

Archive links with unsafe/escaping semantics are rejected or conservatively skipped rather than followed into arbitrary destination paths.

### Archive expansion / bomb defense

Step 4 applies structural suspicious-expansion controls using factors such as entry count, expansion ratio and actual destination capacity/free space. This is designed to stop clearly dangerous expansion without imposing an arbitrary 2 GiB/4 GiB-style normal archive ceiling.

Archive sizes and byte counters use `Long`. Cancellation is propagated through streamed operations.

### Archive creation integrity

ZIP and TAR-family creation uses `SafeOutputWriter.writeGenerated`. Compression output goes only to `.zzstage-*`; the requested archive name becomes visible only after the compression/container stream closes successfully and the staged output is ready for commit/proof.

A producer exception after writing partial bytes must not expose the requested archive name.

### Password/encryption scope

ZIP password/AES support protects encrypted archive content according to the archive library/format in use. It does not replace filesystem permissions, transaction integrity, secure key storage, or device-level encryption. Step 4 must not log or persist archive passwords as part of the write journal.

## Text editor security and data-loss prevention

`TextFileEngine` fingerprints an opened file and checks for external mutation before a normal overwrite. If the authoritative source changed outside the editor, Step 4 requires an explicit conflict decision such as Reload, Save As, or Overwrite rather than silently destroying the external change.

`Save` and `Save As` both use staged transactional output semantics. In particular, a fresh Save As cannot leave a process-killed partial file under the user-requested name.

Very large text avoids unconditional whole-file buffering. The engine derives editable capacity from VM heap headroom and falls back to streamed read-only windows when full editing would be unsafe.

Likely binary input is not treated as ordinary editable text merely because it has a text-like extension.

## APK and installed-app security

APK inspection is passive. The app reads package metadata, requested permissions, SDK metadata and signing-certificate SHA-256 without executing APK code.

Install and uninstall are system-mediated operations requiring Android confirmation. Installed-app enumeration follows Android package-visibility constraints, and Step 4 deliberately does not request `QUERY_ALL_PACKAGES`.

Base-only APK backup is clearly distinct from complete split-set backup. The UI/implementation must not imply that a base-only export is necessarily a complete reinstallable package for an app that depends on splits.

Component backup names are sanitized/uniqued before creation inside the hidden set directory.

## Media safety

Image decoding is sampled rather than forcing unconditional full-resolution pixel allocation. Zoom/pan state is bounded.

Audio/video playback uses Media3/ExoPlayer inside the app. Malformed metadata or media parse failure is handled without intentionally converting parser failure into file mutation. Viewer/activity startup remains resilient where metadata inspection fails.

Media parsing does not expand storage authority and does not bypass Android codec/media sandbox behavior.

## Storage analyzer and duplicate safety

The storage analyzer uses iterative traversal and bounded retained result sets rather than building an unbounded in-memory filesystem graph.

Duplicate detection deliberately separates candidate narrowing from proof:

1. exact-size grouping;
2. sampled beginning/middle/end SHA-256 to reduce candidates;
3. mandatory full-file SHA-256 before reporting a verified duplicate.

A sampled-hash collision therefore cannot alone produce a verified duplicate result. Duplicate analysis is informational; Step 4 does not automatically delete files identified as duplicates.

## Large-file and resource-exhaustion controls

Step 4 does not advertise a fixed public 2 GiB/4 GiB ceiling. Core byte accounting uses `Long`, and algorithms prefer streaming/bounded processing.

Controls include:

- bounded I/O buffers;
- sampled image decode;
- read-only text windows for files beyond safe editable headroom;
- iterative analyzer traversal;
- bounded largest-file/folder result retention;
- staged random-access copies only when a third-party parser requires seekable input;
- capacity checks before potentially large extraction/staging work;
- cancellation-aware loops.

The absence of an arbitrary product-size cap is not a promise that every device/provider has sufficient RAM, storage, codec support, or parser capability for every theoretically valid file.

## Temporary artifacts and cleanup

Hidden names such as `.zzstage-*`, `.zzbackup-*`, and `.zzapkbackup-*` are implementation transaction artifacts. They are not user-facing completion signals.

Normal handled failure/cancellation cleans uncommitted hidden artifacts where safe. Process-death leftovers are reconciled from the durable journal. Cleanup must never delete an authoritative replacement backup merely because a final object with matching name/size exists.

## Privacy and network posture

Step 4 file inspection/analysis is local to the app/provider architecture described above. This Step 4 design does not require uploading user files to a remote analysis service. Android/third-party platform components may have their own behavior outside Step 4's control, but Step 4 itself does not introduce a cloud file-analysis pipeline.

The write journal should contain only transaction/recovery metadata needed to reconcile storage mutations; it is not a content database.

## Security non-goals and residual risk

Step 4 does not claim to:

- prove third-party archive/media/parser libraries are free of vulnerabilities;
- provide malware scanning or determine whether an APK is trustworthy;
- make an encrypted archive equivalent to full-device or filesystem encryption;
- guarantee atomic rename/replace semantics on providers that do not expose the required capabilities;
- recover from physical media failure, filesystem corruption, malicious provider behavior, or device loss;
- certify all physical-device/vendor/storage combinations during Step 4. Physical-phone testing remains deferred to the final overall project step.

When a provider or parser cannot satisfy a safety precondition, explicit failure is preferable to silent weakening of the contract.

## Security regression coverage

Security-sensitive behavior is part of the permanent Step 4 certification gate. `STEP4_TEST_MATRIX.md` includes coverage for:

- archive traversal and duplicate normalized paths;
- suspicious expansion and multi-GiB accounting;
- failed replacement preserving the old destination;
- fresh hidden staging before final visibility;
- simulated process death during a logical 20 GiB fresh write;
- rejection of an unrelated same-size final during recovery while preserving backup/journal state;
- generated/archive producer failure without exposing the requested final filename;
- wrong archive password behavior;
- external text modification rejection;
- duplicate sample collision defeated by full SHA-256;
- interrupted complete-split backup leaving no visible partial folder;
- successful complete-split backup with verified manifest;
- malformed APK/media handling.

The permanent workflow also enforces zero skipped JVM tests and zero skipped API-35 instrumentation tests.

## Security acceptance rule

This document describes intended and implemented security behavior; it is not by itself proof of certification. Step 4 security acceptance requires the **exact current branch head** to pass the permanent `Android Step 4 CI` gate defined in `.github/workflows/android.yml` and `STEP4_TEST_MATRIX.md`, including real API-35 `connectedDebugAndroidTest` execution with zero skipped instrumentation tests. An older green SHA cannot certify a later security-documentation or implementation change.