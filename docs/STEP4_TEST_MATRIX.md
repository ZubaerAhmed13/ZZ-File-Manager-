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
| Fresh generated write staging | During the producer callback the requested final filename does not exist, a hidden `.zzstage-*` exists, and a `STAGING` journal record already exists. After success only the final file remains. |
| Simulated process death during fresh 20 GiB output | A journaled partial `.zzstage-*` in `STAGING` is reconciled without ever creating `movie.mkv`; the hidden stage and journal are removed when safe. |
| Same-size unrelated final during recovery | A `COMMITTING` replacement whose unrelated final has the expected size but wrong SHA-256 remains unresolved and the known-good backup is preserved. Filename + size are therefore insufficient commit proof. |
| Generated/archive producer failure | A producer that writes partial bytes and then fails never exposes `Backup.zip`; no uncommitted `.zzstage-*` or completed journal remains after handled cleanup. |
| Encrypted ZIP create/list/extract | AES ZIP is created through the staged generated-output path, listed and extracted with the correct password. |
| Wrong archive password | Extraction fails as `BadPassword` and does not materialize requested file. |
| Replace collision during extraction | Existing destination is replaced through safe-write semantics and contains extracted data. |
| Malformed APK | Passive inspection rejects invalid APK. |
| Own APK certificate | Package and signing certificate SHA-256 metadata are populated. |
| Base APK backup | Base APK streams through the staged file transaction to the selected destination and produces a non-empty `.apk`. |
| Interrupted complete split backup | Failure after the hidden split-backup directory is created leaves no visible `<App>-<version>-apks` folder and no lingering handled-failure stage/journal. |
| Successful complete split backup | Base + split files finalize together in the visible directory, `zz-apk-backup-manifest.json` exists with `complete=true`, and the hidden `.zzapkbackup-*` directory is gone. |
| Malformed media metadata | Metadata service returns safely without crashing caller. |

## Transactional blocker acceptance criteria

The following conditions are explicit Step 4 blockers, not optional hardening:

1. **No direct fresh-to-final streaming.** Every fresh output must create a durable journal record before hidden staging begins and must not expose the requested final filename until commit.
2. **No direct archive-creation output.** ZIP/TAR-family creation must write through the staged generated-output path.
3. **No filename+size commit proof.** Recovery must prove the final object using staged mutation identity when available or a streaming cryptographic digest when identity is unavailable. Replacement backups must survive uncertainty.
4. **No partial complete-split folder.** `COMPLETE_SPLITS` must finalize as one staged directory transaction with verified component membership and manifest.
5. **No unsafe provider downgrade.** If required create/rename/delete capabilities are unavailable, the operation must fail explicitly rather than advertise transactional safety while writing directly to a final name.

## Build / static gate

- `assembleDebug` validates debug packaging.
- `assembleRelease` ensures Step 4 is not debug-only.
- `assembleDebugAndroidTest` catches instrumentation compile/API drift before emulator provisioning.
- `lintDebug` remains mandatory.
- compileSdk and targetSdk stay at 35 for this step.
- AndroidX Media3 is pinned to the stable API-35-compatible line so playback features remain present without silently moving certification to API 36.

## Exact-head completion evidence

The completion report may mark Step 4 certified only when one permanent `Android Step 4 CI` run for the current branch head proves all of the following:

- build/unit/lint/release/androidTest-compile step succeeded;
- JVM zero-skip check succeeded;
- KVM/emulator preparation succeeded;
- `connectedDebugAndroidTest` on API 35 actually ran and succeeded;
- instrumentation zero-skip check succeeded;
- overall job conclusion is `success`;
- the run `head_sha` equals the current `step4/archive-media-text-apk-analyzer` branch head;
- no temporary patch/helper workflow remains in the branch.

## Failure policy

A failure is treated as a blocker until the cause is fixed and a new exact-head run completes. The completion report must not relabel failed, skipped or never-executed instrumentation as PASS. Emulator provisioning failure is infrastructure failure, not feature certification; rerun/fix is required until the exact head actually executes the test suite.