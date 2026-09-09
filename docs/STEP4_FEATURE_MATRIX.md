# Step 4 Feature Matrix

Status terminology: **Implemented** means the feature exists in the Step 4 branch. **Certified** requires the exact final branch head to pass the complete GitHub Actions API-35 gate described in `STEP4_TEST_MATRIX.md`.

| Area | Capability | Status | Notes |
|---|---|---|---|
| Integration | Existing Step 1–3 browser preserved | Implemented | Step 4 is additive; directories and existing operations remain on prior architecture. |
| Integration | Internal viewer routing | Implemented | Archive, image, video, audio, text and APK types route through `Step4OpenCodec` / `Step4FileActivity`. |
| Integration | Home tools | Implemented | Apps, Analyze Storage and Create Archive are exposed from Home. |
| Large files | 64-bit byte accounting | Implemented | Core Step 4 sizes/counters use `Long`; tests include multi-GiB logical values. |
| Large files | Streaming/provider-native access | Implemented | Content is streamed where possible; parser staging is used only where random access is required. |
| Safe writes | Staged fresh/replace output | Implemented | Temporary `.zzstage-*` output is verified before commit. |
| Safe writes | Atomic replace when provider supports it | Implemented | Uses provider `replaceAtomically`. |
| Safe writes | Journaled fallback replace | Implemented | Backup/commit phases are persisted and reconciled after interruption. |
| Safe writes | Cancellation cleanup | Implemented | Uncommitted stages are removed without deleting authoritative destination data. |
| Archive | ZIP list/extract | Implemented | Password/encryption handling supported. |
| Archive | TAR / TAR.GZ / TAR.BZ2 / TAR.XZ list/extract | Implemented | Streaming TAR-family handling. |
| Archive | 7z list/extract | Implemented | Random-access staging when provider requires it. |
| Archive | RAR list/extract | Implemented | Read/extract support through Junrar. |
| Archive | ZIP create | Implemented | Compression-level selection and optional AES password. |
| Archive | TAR-family create | Implemented | TAR, gzip, bzip2 and xz output. |
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
| Text | Safe Save / Save As | Implemented | Uses `SafeOutputWriter`. |
| Text safety | External modification detection | Implemented | Fingerprint checked before normal overwrite; user must explicitly choose conflict action. |
| APK | Passive APK inspection | Implemented | Label/package/version/SDK/permissions/certificate digest. |
| APK | Android package installer handoff | Implemented | User/system confirmation required. |
| Apps | Installed-app list/search | Implemented | Respects package visibility; no `QUERY_ALL_PACKAGES`. |
| Apps | Launch / App Info / uninstall handoff | Implemented | Uninstall is delegated to Android confirmation UI. |
| Apps | Base APK backup | Implemented | Streaming export. |
| Apps | Complete split-APK backup | Implemented | Preserves base + split components in a dedicated output folder. |
| Analyzer | Iterative storage scan | Implemented | No recursive call-stack dependency; bounded top-result queues. |
| Analyzer | Category usage | Implemented | Images/video/audio/documents/archives/APKs/other. |
| Analyzer | Largest files/folders | Implemented | Configurable bounded retained list. |
| Analyzer | Extension counts / empty files/folders | Implemented | Counts plus bounded retained examples. |
| Analyzer | Capacity/free-space context | Implemented | Where provider/storage layer exposes it. |
| Duplicates | Size prefilter | Implemented | Phase 1. |
| Duplicates | Beginning/middle/end sampled hash | Implemented | Phase 2 candidate reduction. |
| Duplicates | Full SHA-256 verification | Implemented | Phase 3 mandatory before duplicate result. |
| Duplicates | Automatic deletion | Not implemented by design | Results are informational; destructive choice stays with user. |
| Recovery | Step 4 pending-write reconciliation | Implemented | `Step4RecoveryWorker` uses the durable write journal. |
| Certification | JVM/unit gate | Implemented | No skipped-test policy. |
| Certification | Lint + debug/release + androidTest compile | Implemented | CI gate. |
| Certification | API-35 emulator instrumentation | Implemented | Real Step 4 activity/core behavior tests, no skip allowance. |
| Certification | Physical phone | Deferred by project plan | Physical testing is intentionally reserved for the final overall project step. |
