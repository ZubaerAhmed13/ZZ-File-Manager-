# Step 4 Architecture — Archive, Media, Text, APK and Storage Analyzer

## Scope

Step 4 extends the clean-room ZZ File Manager foundation without replacing the Step 1–3 browser, operation engine, user library, search, Recycle Bin, SAF/local storage model, or recovery guarantees. Step 4 adds first-party handling for archives, images, video/audio, text files, APK inspection/backup and storage analysis.

## Entry routing

`BrowserViewModel` keeps ordinary directory navigation unchanged. Supported Step 4 file types are routed through `Step4OpenCodec` into the internal-only `Step4FileActivity`. The codec carries the already-authorized provider reference and parent/root context; it does not grant new storage authority. Unknown/unsupported file types continue through the existing external-open path.

The Home screen exposes three Step 4 tools through `Step4ToolsActivity`: Installed Apps, Analyze Storage and Create Archive. These are additive destinations and do not replace the existing Home/browser UX.

## Provider and large-file model

All Step 4 data access uses the existing `StorageProvider` / `WritableStorageProvider` abstraction. Local paths and SAF-backed references remain the source of truth. Large content is streamed or staged only when a third-party parser genuinely requires random access. No fixed product-level 2 GiB/4 GiB style ceiling is introduced; byte accounting uses `Long` and certification includes logical multi-GiB values.

## Safe writes and crash recovery

Step 4 file outputs use `SafeOutputWriter` and `Step4WriteJournal` rather than writing directly to a user-visible destination name. This applies to both fresh outputs and replacement outputs.

The file transaction is:

1. choose the eventual final name and generate a transaction ID;
2. persist a `STAGING` journal record **before** creating output bytes;
3. create a hidden `.zzstage-*` file;
4. stream the output into that stage using bounded buffers while counting bytes and computing SHA-256;
5. verify streamed byte count and provider metadata, obtain provider `mutationIdentity` when one is durably available, then persist `STAGED` plus the proof data;
6. re-check the destination state to detect concurrent mutation;
7. for a fresh output, journal `COMMITTING` and rename the hidden stage to the final name;
8. for replacement, prefer provider `replaceAtomically`; otherwise rename the authoritative old file to a hidden backup, rename the stage to the final name, and retain the backup until commit proof succeeds;
9. prove that the final object is the staged object using provider mutation identity when available, otherwise a streaming SHA-256 comparison; filename and size alone are never sufficient commit proof;
10. only after proof succeeds mark `COMMITTED`, remove any replacement backup, and clear the journal record.

A provider must expose the create/rename/delete capabilities needed to keep an incomplete result hidden until commit. If it cannot safely finalize a hidden stage, the operation is rejected instead of silently degrading to direct-to-final output.

### Recovery rule

`Step4RecoveryWorker` reconciles journal records after process death. Pre-commit `STAGING`/`STAGED` stages can be cleaned without ever materializing the requested final filename. During an uncertain commit, recovery accepts a final only when the persisted staged-object proof matches it. A same-name/same-size object is not proof. For replacement transactions, a preserved backup is not deleted until the final is proved; when proof is absent or mismatched, data and journal state are retained rather than guessed away.

The journal format remains backward-aware: older records without strong proof fields are decoded, but missing proof is treated as uncertainty rather than success.

## Archive subsystem

`ArchiveManager` supports listing/extraction for ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, 7z and RAR, and creation for ZIP plus TAR-family formats. ZIP creation supports selectable compression and optional AES encryption. Archive handling includes:

- normalized path validation and rejection of absolute paths, drive-prefixed paths, NULs and `..` traversal;
- case-insensitive duplicate normalized-path rejection;
- conservative rejection/skip of archive links that could escape destination semantics;
- destination-space checks and suspicious expansion/bomb detection;
- cancellation-aware streamed extraction/creation;
- explicit collision policy through the same safe-write layer;
- temporary random-access staging only when required by the archive library/provider combination.

Archive **creation** uses `SafeOutputWriter.writeGenerated`: ZIP/TAR bytes are written only into the hidden `.zzstage-*` object. `Backup.zip`, for example, is not created until compression has closed successfully and the staged output is ready to commit. A handled failure/cancellation cleans the uncommitted hidden stage; process death leaves a journaled hidden stage for reconciliation rather than a partial archive masquerading under the requested name.

The suspicious-expansion guard is structural rather than an arbitrary maximum archive size, preserving legitimate large archives when capacity and ratios are sane.

## Media subsystem

`MediaInspector` reads image EXIF/basic metadata and audio/video metadata. The image viewer decodes a sampled bitmap rather than forcing full-resolution pixels into memory, supports pinch/double-tap zoom and pan, previous/next sibling navigation, and an image-information dialog.

Video/audio playback uses AndroidX Media3 ExoPlayer. Video provides player controls plus fullscreen/landscape system-UI handling; audio uses the same playback foundation. Metadata failure or malformed media is handled independently of activity startup so a bad file does not crash the entire viewer.

Media3 is pinned to the API-35-compatible stable line because Step 4 certification intentionally stays on compileSdk/target API 35.

## Text subsystem

`TextFileEngine` detects UTF-8, UTF-8 BOM, UTF-16 LE/BE and a guarded system fallback; preserves LF/CRLF semantics; detects likely binary files; supports full-file search; and fingerprints opened files to prevent unnoticed overwrite after external modification.

Editable file size is derived from actual VM heap headroom rather than a hard product cap. Files beyond the safe editable bound open in streamed, read-only line windows. The Compose editor provides view/edit switching, wrap, optional line numbers, bounded undo/redo, search, Save, Save As, and an explicit external-change decision (Reload / Save As / Overwrite).

Both replacement Save and fresh Save As use the staged transactional writer. A partial Save As therefore cannot remain under the user-requested final filename after abrupt process death.

## APK subsystem

`ApkManager` passively inspects APK metadata through Android package APIs, including label, package name, version, SDK levels, requested permissions and signing-certificate SHA-256. Installed-app listing follows Android package-visibility rules; `QUERY_ALL_PACKAGES` is intentionally not requested.

Base-only backup exports through the same hidden staged-file transaction used by other fresh file outputs.

Complete split-APK backup is a directory-level transaction. The app creates a hidden `.zzapkbackup-*` directory, copies `base.apk` plus every required split with streaming byte-count/SHA-256 verification, writes a manifest containing package/version/component count and per-component sizes/digests, re-reads the manifest, then asks `SafeOutputWriter` to independently prove every direct file member before renaming the whole directory to its visible `<App>-<version>-apks` name. A failed/cancelled pre-commit backup is cleaned only from the hidden staging directory. Providers that cannot create files/directories and rename/delete the staged directory are explicitly unsupported for complete-set backup.

A base-only export clearly reports when it is not a complete reinstallable package. Install and uninstall actions are handed to Android system confirmation UIs; there is no silent install/uninstall path.

## Storage analyzer

`StorageAnalyzer` uses iterative traversal with bounded retained result sets rather than building an unbounded in-memory filesystem graph. It reports category bytes, largest files/folders, extension counts, empty items, accessible capacity/free space and warnings.

Duplicate detection is three-phase:

1. exact-size grouping;
2. sampled beginning/middle/end SHA-256 candidate narrowing;
3. full-file SHA-256 verification before any result is called a duplicate.

A sampled collision can therefore never be surfaced as a verified duplicate. No duplicate is deleted automatically.

## UI and safety integration

Step 4 keeps file properties, Share, Open with, Favorites and Recycle Bin actions available from the internal viewer. All destructive actions remain explicit. SAF grants and Android system dialogs remain user-controlled.

Physical-phone testing remains deferred to the project’s final certification step. Step 4 itself is accepted only when the exact final branch head passes JVM/unit checks with zero skips, lint, debug/release and instrumentation compilation, plus real API-35 connected instrumentation with zero skipped tests.