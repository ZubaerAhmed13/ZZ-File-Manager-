# Step 4 Completion Report

## Deliverable

**Step 4 — Archive, Media, Text, APK and Storage Analyzer** is implemented on branch `step4/archive-media-text-apk-analyzer` as an additive extension of the Step 1–3 clean-room file-manager architecture.

This report describes the implementation and certification contract. The authoritative certification state is always the permanent GitHub Actions result for the **exact branch head**; an older green commit cannot certify a later code, test, workflow, or documentation commit.

## Implemented product areas

### Archive manager

- Browse/list ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, 7z and RAR.
- Extract all or selected entries with progress/cancellation and collision policy.
- Create ZIP and TAR-family archives.
- ZIP compression-level control and optional AES password.
- Path traversal / unsafe path / duplicate normalized-path defenses.
- Expansion-ratio, entry-count and destination-space protection without an arbitrary normal archive-size ceiling.
- Provider-aware random-access staging only where required.
- Archive creation writes through the same hidden transactional staged-output path as other Step 4 file creation; the user-requested archive name is not exposed while compression is incomplete.

### Media viewing/playback

- Sampled image decode, zoom/pan, previous/next image navigation and metadata display.
- Video playback with Media3, native controls and fullscreen/landscape handling.
- Audio playback with Media3 and metadata.
- Graceful malformed-media handling.

### Text viewer/editor

- UTF-8, UTF-8 BOM, UTF-16 LE and UTF-16 BE detection.
- LF/CRLF handling.
- Search, wrap, line numbers, bounded undo/redo.
- Heap-derived editable limit.
- Streamed read-only window mode for very large text files.
- Safe Save and Save As.
- External-change detection with explicit Reload / Save As / Overwrite choice.
- Fresh Save As uses hidden journaled staging and therefore cannot leave a process-killed partial under the requested visible filename.

### APK / installed-app tools

- Passive APK metadata and signing-certificate SHA-256 inspection.
- Android Package Installer handoff with system confirmation.
- Installed-app list/search within Android package-visibility rules.
- Launch and App Info actions.
- Android-confirmed uninstall handoff.
- Base APK backup through the staged file-output transaction.
- Complete split-APK backup through a hidden directory-level transaction containing every base/split component plus a verified manifest; the whole set is renamed into visibility only after completion/proof.
- No `QUERY_ALL_PACKAGES` permission.

### Storage analyzer

- Iterative bounded-memory scan.
- Category usage, largest files/folders, extension counts, empty-item counts, capacity/free-space context and scan warnings.
- Three-stage duplicate pipeline: exact size → sampled beginning/middle/end hash → mandatory full SHA-256.
- No automatic duplicate deletion.
- Cancellation-aware execution.

## Safe output / recovery hardening

Step 4 no longer has a separate direct fresh-write path. Fresh and replacement file outputs share one transaction model:

- a `Step4WriteTransaction` is durably recorded in `STAGING` before the hidden output file is created;
- bytes are streamed only into `.zzstage-*`, never into the requested final filename;
- byte count, provider metadata and a streaming SHA-256 are captured before commit;
- provider `mutationIdentity` is also persisted when the provider can supply a stable mutation identity;
- fresh output becomes visible only by renaming the fully staged object;
- replacement prefers `replaceAtomically` where available and otherwise preserves the old destination as `.zzbackup-*` during commit;
- recovery accepts a final object only when it can prove that it is the staged object: provider identity when available, otherwise streamed SHA-256;
- filename plus expected size is not accepted as proof;
- a replacement backup is never discarded merely because a same-name/same-size final exists;
- ambiguous state keeps the journal and recoverable data rather than guessing;
- providers that cannot safely create/rename/delete hidden staged output are rejected for the operation instead of receiving a weaker direct-to-final implementation.

This aligns Step 4's recovery rule with the Step 3 principle that uncertainty must preserve authoritative data.

## Complete split-APK transactional guarantee

`COMPLETE_SPLITS` does not create the final `<App>-<version>-apks` directory first. It creates a hidden `.zzapkbackup-*` transaction directory, streams and verifies the base APK and every split, writes a manifest containing package/version/component count and per-component sizes/SHA-256 digests, re-reads that manifest, and lets the transactional writer independently prove the complete direct-file membership before renaming the directory into its visible final name.

A handled pre-commit failure/cancellation removes only the hidden stage. Process-death state remains journaled for startup reconciliation. A provider without the directory/file create plus rename/delete semantics required to finalize this safely is reported unsupported for complete-set backup.

## Large-file position

Step 4 does not introduce a public hard maximum such as 2 GiB or 4 GiB. Sizes and counters use `Long`; streaming/bounded algorithms are used where practical; random-access formats may use capacity-checked temporary staging. Tests include logical 3 GiB, 10 GiB, 20 GiB and 30 GiB values and a 100,000-entry analyzer workload to catch regression toward `Int`-bounded assumptions.

## Certification assets

The branch contains:

- Step 4 JVM safety/algorithm tests;
- `Step4CertificationInstrumentationTest` for real Step 4 screens and generated fixtures;
- `Step4CoreBehaviorInstrumentationTest` for actual file-engine/archive/APK/media/safe-write behavior;
- transactional blocker regressions that prove hidden fresh staging, process-death cleanup for a logical 20 GiB fresh output, rejection of a same-size wrong-content final during recovery, generated/archive failure visibility safety, interrupted complete-split cleanup, and successful complete-split finalization with a manifest;
- CI that builds debug and release variants, runs JVM tests, lint, instrumentation compilation and API-35 connected instrumentation;
- zero-skipped-test enforcement for JVM and Android test XML.

See `STEP4_TEST_MATRIX.md` for the exact coverage contract.

## Dependency compatibility correction

Media3 1.11.0 and 1.10.1 publish AAR metadata requiring compileSdk 36, so neither can be used by this Step 4 build while it is intentionally certified on API 35 / compileSdk 35 with Android Gradle Plugin 8.7.3. Playback is therefore pinned to **Media3 1.9.4**. This preserves ExoPlayer playback and UI functionality without silently moving the requested certification boundary to API 36.

## Non-goals / deferred verification

- Step 5+ functionality is not pulled forward into Step 4.
- Physical-device certification is not claimed here. Per the project plan, physical phone testing remains deferred until the final overall project step.
- A successful compile alone is not certification; API-35 instrumentation must actually execute on the exact final head.

## Certification status

**CERTIFIED**, provided the permanent `Android Step 4 CI` run automatically associated with this exact report commit concludes `success`. This wording intentionally makes the exact-head workflow result authoritative rather than embedding a run number that would require another documentation commit.

The immediately preceding clean implementation head `2b6caebeb4e48b2edbed6b34c4d45f8476b0012b` passed permanent **Android Step 4 CI #226** end to end: build/unit/lint/release/androidTest compilation, zero-skipped JVM enforcement, KVM setup, real API-35 `connectedDebugAndroidTest`, zero-skipped instrumentation enforcement, and report upload all succeeded. This report-only commit changes no production code, tests, dependencies, or workflow behavior, but the project's exact-head rule still requires the automatically triggered permanent run for this commit to pass before the certification statement above is valid.

## Final acceptance rule

Step 4 is accepted only when all of the following are true on one exact head SHA:

1. `assembleDebug` passes;
2. `testDebugUnitTest` passes with zero skips;
3. `lintDebug` passes;
4. `assembleRelease` passes;
5. `assembleDebugAndroidTest` passes;
6. API-35 `connectedDebugAndroidTest` actually executes and passes;
7. instrumentation results contain zero skipped tests;
8. the passing run's `head_sha` equals the current `step4/archive-media-text-apk-analyzer` branch head;
9. no temporary hardening/patch workflow remains in the branch.

If the exact-head run fails, this certification statement is invalid until the failure is fixed and a new exact-head run succeeds.