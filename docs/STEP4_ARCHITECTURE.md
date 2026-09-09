# Step 4 Architecture — Archive, Media, Text, APK and Storage Analyzer

## Scope

Step 4 extends the clean-room ZZ File Manager foundation without replacing the Step 1–3 browser, operation engine, user library, search, Recycle Bin, SAF/local storage model, or recovery guarantees. Step 4 adds first-party handling for archives, images, video/audio, text files, APK inspection/backup and storage analysis.

## Entry routing

`BrowserViewModel` keeps ordinary directory navigation unchanged. Supported Step 4 file types are routed through `Step4OpenCodec` into the internal-only `Step4FileActivity`. The codec carries the already-authorized provider reference and parent/root context; it does not grant new storage authority. Unknown/unsupported file types continue through the existing external-open path.

The Home screen exposes three Step 4 tools through `Step4ToolsActivity`: Installed Apps, Analyze Storage and Create Archive. These are additive destinations and do not replace the existing Home/browser UX.

## Provider and large-file model

All Step 4 data access uses the existing `StorageProvider` / `WritableStorageProvider` abstraction. Local paths and SAF-backed references remain the source of truth. Large content is streamed or staged only when a third-party parser genuinely requires random access. No fixed product-level 2 GiB/4 GiB style ceiling is introduced; byte accounting uses `Long` and certification includes logical multi-GiB values.

## Safe writes and crash recovery

Step 4 destructive writes use `SafeOutputWriter` and `Step4WriteJournal` rather than blind overwrite. Replace follows a staged-and-verified sequence:

1. write a unique `.zzstage-*` file;
2. verify byte count/metadata;
3. persist the transaction phase;
4. prefer provider-native `replaceAtomically`;
5. otherwise use a durable backup/rename commit sequence;
6. retain ambiguous data and journal state rather than guessing after failure;
7. reconcile pending transactions through `Step4RecoveryWorker`.

Cancellation before a destructive boundary removes only the uncommitted stage. The existing destination remains authoritative. This is deliberately separate from the Step 2 operation journal because editor/archive output is not a queued file-operation item.

## Archive subsystem

`ArchiveManager` supports listing/extraction for ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, 7z and RAR, and creation for ZIP plus TAR-family formats. ZIP creation supports selectable compression and optional AES encryption. Archive handling includes:

- normalized path validation and rejection of absolute paths, drive-prefixed paths, NULs and `..` traversal;
- case-insensitive duplicate normalized-path rejection;
- conservative rejection/skip of archive links that could escape destination semantics;
- destination-space checks and suspicious expansion/bomb detection;
- cancellation-aware streamed extraction/creation;
- explicit collision policy through the same safe-write layer;
- temporary random-access staging only when required by the archive library/provider combination.

The suspicious-expansion guard is structural rather than an arbitrary maximum archive size, preserving legitimate large archives when capacity and ratios are sane.

## Media subsystem

`MediaInspector` reads image EXIF/basic metadata and audio/video metadata. The image viewer decodes a sampled bitmap rather than forcing full-resolution pixels into memory, supports pinch/double-tap zoom and pan, previous/next sibling navigation, and an image-information dialog.

Video/audio playback uses AndroidX Media3 ExoPlayer. Video provides player controls plus fullscreen/landscape system-UI handling; audio uses the same playback foundation. Metadata failure or malformed media is handled independently of activity startup so a bad file does not crash the entire viewer.

Media3 is pinned to the API-35-compatible stable line because Step 4 certification intentionally stays on compileSdk/target API 35.

## Text subsystem

`TextFileEngine` detects UTF-8, UTF-8 BOM, UTF-16 LE/BE and a guarded system fallback; preserves LF/CRLF semantics; detects likely binary files; supports full-file search; and fingerprints opened files to prevent unnoticed overwrite after external modification.

Editable file size is derived from actual VM heap headroom rather than a hard product cap. Files beyond the safe editable bound open in streamed, read-only line windows. The Compose editor provides view/edit switching, wrap, optional line numbers, bounded undo/redo, search, Save, Save As, and an explicit external-change decision (Reload / Save As / Overwrite).

## APK subsystem

`ApkManager` passively inspects APK metadata through Android package APIs, including label, package name, version, SDK levels, requested permissions and signing-certificate SHA-256. Installed-app listing follows Android package-visibility rules; `QUERY_ALL_PACKAGES` is intentionally not requested.

Backup supports base-only APK export and a complete split-APK set. A base-only export clearly reports when it is not a complete reinstallable package. Install and uninstall actions are handed to Android system confirmation UIs; there is no silent install/uninstall path.

## Storage analyzer

`StorageAnalyzer` uses iterative traversal with bounded retained result sets rather than building an unbounded in-memory filesystem graph. It reports category bytes, largest files/folders, extension counts, empty items, accessible capacity/free space and warnings.

Duplicate detection is three-phase:

1. exact-size grouping;
2. sampled beginning/middle/end SHA-256 candidate narrowing;
3. full-file SHA-256 verification before any result is called a duplicate.

A sampled collision can therefore never be surfaced as a verified duplicate. No duplicate is deleted automatically.

## UI and safety integration

Step 4 keeps file properties, Share, Open with, Favorites and Recycle Bin actions available from the internal viewer. All destructive actions remain explicit. SAF grants and Android system dialogs remain user-controlled. Physical-phone testing remains deferred to the project’s final certification step; Step 4 itself is certified through JVM/unit checks, static/lint/release compilation and API-35 emulator instrumentation.
