# Step 4 Feature Matrix

Status terminology: **Implemented** means the feature exists in the Step 4 branch. **Certified** requires the exact final branch head to pass the complete GitHub Actions API-35 gate described in `STEP4_TEST_MATRIX.md`.

| Area | Capability | Status | Notes |
|---|---|---|---|
| Integration | Existing Step 1–3 browser preserved | Implemented | Step 4 is additive; directories and existing operations remain on prior architecture. |
| Integration | Internal viewer routing | Implemented | Archive, image, video, audio, text and APK types route through `Step4OpenCodec` / `Step4FileActivity`. |
| Integration | Home tools | Implemented | Apps, Analyze Storage and Create Archive are exposed from Home. |
| Large files | 64-bit byte accounting | Implemented | Core Step 4 sizes/counters use `Long`; tests include multi-GiB logical values. |
| Large files | Streaming/provider-native access | Implemented | Content is streamed where possible; parser staging is used only where random access is required. |
| Safe writes | Fresh output staging | Implemented | Every new file output is journaled in `STAGING` before a hidden `.zzstage-*` file is created. The requested final filename is never opened for streaming. |
| Safe writes | Replacement output staging | Implemented | Replacement data is written and proved in `.zzstage-*` before any authoritative destination mutation. |
| Safe writes | Stage verification | Implemented | Streaming byte count and provider metadata are checked; SHA-256 is computed during the staged write with bounded memory. |
| Safe writes | Stable commit proof | Implemented | Uses provider `mutationIdentity` when durable identity is available; otherwise recovery streams SHA-256 and compares with the staged digest. Filename + size alone is never accepted as proof. |
| Safe writes | Atomic replace when provider supports it | Implemented | Uses provider `replaceAtomically`, followed by staged-object identity/digest proof. |
| Safe writes | Journaled fallback replace | Implemented | Backup/commit phases are persisted; backup is deleted only after the final object is proved to be the staged object. |
| Safe writes | Process-death recovery for fresh writes | Implemented | `STAGING`/`STAGED` hidden outputs are reconciled without creating a normal final filename; ambiguous commit states retain journal/data instead of guessing. |
| Safe writes | Provider safety gate | Implemented | File output requires create+rename+delete capabilities. If a provider cannot safely finalize a hidden stage, the operation is reported unsupported instead of writing directly to the final name. |
| Safe writes | Cancellation cleanup | Implemented | Pre-commit hidden stages are removed without deleting authoritative destination data. |
| Archive | ZIP list/extract | Implemented | Password/encryption handling supported. |
| Archive | TAR / TAR.GZ / TAR.BZ2 / TAR.XZ list/extract | Implemented | Streaming TAR-family handling. |
| Archive | 7z list/extract | Implemented | Random-access staging when provider requires it. |
| Archive | RAR list/extract | Implemented | Read/extract support through Junrar. |
| Archive | ZIP create | Implemented | Compression-level selection and optional AES password. Archive bytes are written to a hidden transactional stage and finalized only after successful close/proof. |
| Archive | TAR-family create | Implemented | TAR, gzip, bzip2 and xz output use the same staged transactional writer. |
| Archive safety | Incomplete-created archive visibility | Implemented | A process death while creating an archive can leave only a journaled hidden stage; it cannot leave the requested archive filename masquerading as complete. |
| Archive safety | Zip-slip/path traversal rejection | Implemented | Rejects absolute/drive/NUL/`..` paths. |
| Archive safety | Duplicate normalized path rejection | Implemented | Prevents ambiguous overwrite paths. |
| Archive safety | Expansion/bomb guard | Implemented | Entry-count, ratio and actual/free-space checks; no arbitrary normal-size ceiling. |
| Archive UX | Browse hierarchy / select entries / extract all or selected | Implemented | Collision policy is explicit. |
| Image | Sampled decode | Implemented | Avoids unconditional full-resolution bitmap allocation. |
| Image | Pinch/double-tap zoom and pan | Implemented | Zoom range is bounded. |
| Image | Previous/next image navigation | Implemented | Uses readable image siblings in current parent. |
| Image | EXIF/basic metadata | Implemented | Dimensions, camera fields, orientation/exposure/ISO/focal length/GPS-presence where available. |
| Video | In-app playback | Implemented | Media3 ExoPlayer with native player controls. |
| Video | Fullscreen/landscape mode | Implemented | System bars/orientation are restored on exit/dispose. |
| Audio | In-app playback | Implemented | Media3 playback and metadata. |
| Media safety | Malformed metadata handling | Implemented | Viewer/activity remains usable when metadata parsing fails. |
| Text | UTF-8 / UTF-8 BOM / UTF-16 LE/BE detection | Implemented | Guarded fallback for other likely text. |
| Text | LF/CRLF detection/preservation | Implemented | Save normalizes to opened document's line-ending mode. |
| Text | Editable mode | Implemented | Heap-derived limit, not an arbitrary public file-size cap. |
| Text | Large read-only window mode | Implemented | Streamed windows prevent whole-huge-file buffering. |
| Text | Search | Implemented | Streaming line search with progress and bounded returned matches. |
| Text | Wrap / line numbers | Implemented | Viewer controls. |
| Text | Bounded undo/redo | Implemented | Snapshot history capped to avoid runaway memory. |
| Text | Safe Save / Save As | Implemented | Uses staged `SafeOutputWriter`; Save As does not expose a partial requested filename. |
| Text safety | External modification detection | Implemented | Fingerprint checked before normal overwrite; user must explicitly choose conflict action. |
| APK | Passive APK inspection | Implemented | Label/package/version/SDK/permissions/certificate digest. |
| APK | Android package installer handoff | Implemented | User/system confirmation required. |
| Apps | Installed-app list/search | Implemented | Respects package visibility; no `QUERY_ALL_PACKAGES`. |
| Apps | Launch / App Info / uninstall handoff | Implemented | Uninstall is delegated to Android confirmation UI. |
| Apps | Base APK backup | Implemented | Streaming export through the hidden staged file transaction. |
| Apps | Complete split-APK backup | Implemented | Base + every split are copied into hidden `.zzapkbackup-*`; every component is size/digest verified, a complete-set manifest is written/verified, and only then is the whole directory renamed to its visible final name. |
| Apps | Interrupted split-backup recovery | Implemented | Failed/cancelled pre-commit split backup removes only the hidden transaction directory. Process-death reconciliation handles the hidden directory through the Step 4 journal. |
| Apps | Split-backup provider safety gate | Implemented | Requires create-directory/create-file/rename/delete semantics. Providers that cannot safely finalize the staged directory are explicitly unsupported for complete-set backup. |
| Analyzer | Iterative storage scan | Implemented | No recursive call-stack dependency; bounded top-result queues. |
| Analyzer | Category usage | Implemented | Images/video/audio/documents/archives/APKs/other. |
| Analyzer | Largest files/folders | Implemented | Configurable bounded retained list. |
| Analyzer | Extension counts / empty files/folders | Implemented | Counts plus bounded retained examples. |
| Analyzer | Capacity/free-space context | Implemented | Where provider/storage layer exposes it. |
| Duplicates | Size prefilter | Implemented | Phase 1. |
| Duplicates | Beginning/middle/end sampled hash | Implemented | Phase 2 candidate reduction. |
| Duplicates | Full SHA-256 verification | Implemented | Phase 3 mandatory before duplicate result. |
| Duplicates | Automatic deletion | Not implemented by design | Results are informational; destructive choice stays with user. |
| Recovery | Step 4 pending-write reconciliation | Implemented | `Step4RecoveryWorker` reconciles file and staged-directory transactions from the durable journal. |
| Recovery | Uncertain-state preservation | Implemented | Same-name/same-size objects are not sufficient commit proof; unresolved state and replacement backups are preserved when identity/digest proof is absent or mismatched. |
| Certification | JVM/unit gate | Implemented | No skipped-test policy. |
| Certification | Lint + debug/release + androidTest compile | Implemented | CI gate. |
| Certification | API-35 emulator instrumentation | Implemented | Real Step 4 activity/core behavior tests, including transactional blocker regressions, with no skip allowance. |
| Certification | Physical phone | Deferred by project plan | Physical testing is intentionally reserved for the final overall project step. |