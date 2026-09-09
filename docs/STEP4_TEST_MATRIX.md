# Step 4 Test Matrix

## Certification rule

Step 4 is complete only when the **exact current branch head** passes `.github/workflows/android.yml` with no skipped JVM or instrumentation tests. A green earlier commit does not certify a later documentation/code commit.

The gate executes:

```text
clean
assembleDebug
testDebugUnitTest
lintDebug
assembleRelease
assembleDebugAndroidTest
connectedDebugAndroidTest on API 35
```

CI separately scans JUnit XML and fails if skipped tests are present. Physical-phone testing is not a Step 4 prerequisite because the project plan reserves physical-device certification for the final overall step.

## JVM / algorithm coverage

| Risk / behavior | Certification coverage |
|---|---|
| Archive absolute path / `..` traversal | Archive path-validator unit tests reject unsafe normalized paths. |
| Windows drive and slash normalization | Validator tests cover platform-style hostile paths. |
| Duplicate normalized archive entries | Unit coverage requires duplicate-path rejection. |
| Suspicious archive expansion | Expansion-guard tests exercise high ratio / structural protection. |
| Legitimate large archive accounting | Multi-GiB logical values validate `Long` accounting without a normal-size ceiling. |
| 3 GiB / 10 GiB / 30 GiB size safety | Explicit logical-size tests protect against accidental `Int` truncation/overflow. |
| Text undo/redo | Bounded history tests validate undo, redo, redo invalidation and snapshot cap. |
| Text heap-derived edit threshold | Unit behavior validates bounded dynamic sizing rather than a public hard file-size maximum. |
| Storage analyzer bounded results | Huge logical-tree tests retain only configured top-N results. |
| Analyzer 100,000-entry traversal | Synthetic provider test exercises large enumeration without storing an unbounded tree graph. |
| Duplicate sample collision | Crafted files with equal size and equal sampled regions but different interior bytes must not survive full SHA-256 verification as duplicates. |
| Existing operation/trash safety | All Step 1–3 JVM suites remain in the same `testDebugUnitTest` gate and must continue passing. |

## API-35 instrumentation — UI/activity coverage

`Step4CertificationInstrumentationTest` runs real Android components and generated local fixtures:

| Scenario | Expected result |
|---|---|
| Text file open/edit/save | Internal text viewer opens; edit mode accepts replacement; Save commits through Step 4 safe-write path; disk content matches. |
| Generated ZIP browse | ZIP is parsed, hierarchy is navigable and extraction controls exist. |
| Generated PNG | Viewer renders sampled image, reports dimensions and exposes previous/next controls. |
| Malformed MP4 | Video activity and Media3 controls initialize without activity crash; Fullscreen control exists. |
| Malformed MP3 | Audio activity initializes without app-level crash. |
| APK details | Test application APK is passively parsed; package metadata and Package Installer handoff are visible. |
| Installed Apps tool | Screen starts under normal Android package-visibility rules; search UI exists. |
| Analyzer tool | Analyze Storage, Scan and Find duplicates controls start on API 35. |
| Archive creator | Source/destination and create flow UI starts on API 35. |
| Analyzer cancellation | Real coroutine scan is cancelled and joins as cancelled. |
| Step 4 write journal persistence | Transaction survives constructing a new journal instance and retains phase/64-bit expected size. |

## API-35 instrumentation — core behavior coverage

`Step4CoreBehaviorInstrumentationTest` exercises actual `AppContainer` services against local storage:

| Scenario | Expected result |
|---|---|
| UTF-8 / BOM / UTF-16 LE/BE | Encoding detection and decoded text are correct. |
| LF / CRLF | Line-ending mode is detected correctly. |
| Text search | Search returns matches from the actual file engine. |
| Logical 10 GiB text entry | Engine selects large read-only window mode instead of attempting whole-file editable buffering. |
| External modification | Save rejects stale fingerprint and leaves external content intact. |
| Failed staged replace | Existing destination remains byte-for-byte authoritative and uncommitted `.zzstage-*` is cleaned. |
| Encrypted ZIP create/list/extract | AES ZIP is created, listed and extracted with correct password. |
| Wrong archive password | Extraction fails as `BadPassword` and does not materialize requested file. |
| Replace collision during extraction | Existing destination is replaced through safe-write semantics and contains extracted data. |
| Malformed APK | Passive inspection rejects invalid APK. |
| Own APK certificate | Package and signing certificate SHA-256 metadata are populated. |
| APK backup | Base APK streams to selected destination and produces non-empty `.apk`. |
| Malformed media metadata | Metadata service returns safely without crashing caller. |

## Build / static gate

- `assembleDebug` validates debug packaging.
- `assembleRelease` ensures Step 4 is not debug-only.
- `assembleDebugAndroidTest` catches instrumentation compile/API drift before emulator provisioning.
- `lintDebug` remains mandatory.
- compileSdk and targetSdk stay at 35 for this step.
- AndroidX Media3 is pinned to the stable API-35-compatible line so playback features remain present without silently moving certification to API 36.

## Failure policy

A failure is treated as a blocker until the cause is fixed and a new exact-head run completes. The completion report must not relabel failed, skipped or never-executed instrumentation as PASS. Emulator provisioning failure is infrastructure failure, not feature certification; rerun/fix is required until the exact head actually executes the test suite.
