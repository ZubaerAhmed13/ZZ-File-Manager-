# Step 5 Completion Report

## Deliverable

**Step 5 — Removable, Network and Cloud Storage** is implemented on branch `step5/removable-network-cloud` as an additive extension of the Step 1–4 provider-neutral browser and transactional file-operation architecture.

This report describes the implemented Step 5 scope and its certification contract. The authoritative certification state is always the permanent **Android Step 5 CI** result for the **exact branch head**. An older green commit cannot certify a later production, test, workflow, dependency, or documentation commit.

Step 6 functionality is not included in this deliverable.

## Implemented product areas

### Removable storage through Android SAF

- SD-card and USB roots are selected through Android's Storage Access Framework tree picker.
- Persisted URI grants remain the authority; content URIs are never converted into invented filesystem paths.
- Saved removable locations retain a logical storage identity and, where Android exposes a stable removable-volume token, reconnection refuses to silently substitute a different device.
- Availability distinguishes available, read-only, removed, permission-lost, and unsupported states.
- Generic SAF capability flags remain provider-driven instead of assuming local-filesystem behavior.

### Saved network locations

- Saved connection metadata is persisted separately from credentials.
- Runtime external providers are registered without opening every network connection at app startup.
- The Network & Remote UI and provider coordinator operate through the same `StorageRepository` / provider-capability model used by existing storage.
- Remote paths remain provider-native normalized identifiers scoped to the saved connection root.

### SMB

- Production SMB implementation uses SMBJ.
- Only SMB 2.0.2, 2.1, 3.0, 3.0.2, and 3.1.1 are offered; SMB1 is not enabled.
- SMB signing is enabled.
- Browse, metadata, streaming read/write, directory creation, delete, rename/native move, server-side copy, provider replace, offset I/O, and stable identity/revision proof are implemented according to the provider contract.
- Nonzero resume remains subject to the generic live source/stage identity and exact-length proof requirements.

### FTP and FTPS

- Production FTP/FTPS implementation uses Apache Commons Net in binary/passive mode.
- Explicit and implicit FTPS are supported.
- FTPS enables endpoint verification and can enforce an explicitly configured leaf-certificate SHA-256 pin.
- Plain FTP is always presented as unencrypted.
- FTP REST offset support is not treated as sufficient proof for safe process-death resume. Because the provider does not promise stable staged-object identity, the generic engine does not advertise unsafe nonzero resume.

### SFTP

- Production SFTP implementation uses SSHJ with password/private-key authentication paths.
- Host identity is SHA-256 fingerprint based; first contact requires explicit trust and a changed returning host key blocks the connection.
- Offset I/O is supported by the protocol client, but nonzero process-death resume is not advertised unless the full generic staged-identity proof can be satisfied.

### WebDAV

- Production WebDAV implementation uses OkHttp.
- OPTIONS/DAV/Allow capability discovery drives supported operations rather than assuming server behavior.
- PROPFIND metadata, streamed PUT, GET, MKCOL, DELETE, MOVE, and COPY are implemented where the server supports them.
- HTTPS uses system TLS validation with an optional explicit pinning path; HTTP is always labelled unencrypted.
- Redirect following is disabled for explicit endpoint review.
- Ranged GET alone is not considered sufficient for generic resume because standard WebDAV PUT does not provide a portable offset-write guarantee.
- Missing-object `stat()` semantics correctly convert a WebDAV 404 into an absent result rather than surfacing it as a protocol failure.

### LAN discovery

- Android `NsdManager` DNS-SD/mDNS discovery covers known SMB, FTP, FTPS, SSH/SFTP, and WebDAV service types.
- Discovery is user initiated, can be disabled in Settings, and retains at most 100 candidates.
- It does not scan arbitrary subnets/ports, try credentials, auto-save a result, or auto-connect a discovered service.
- Selecting **Add / Connect** opens the normal editable connection flow.

### Cloud boundary

Two cloud paths are implemented by design:

1. Cloud apps exposing Android `DocumentsProvider` work through the existing SAF provider and persisted tree grants.
2. `DirectCloudAdapter`, `DirectCloudSession`, and `CloudUploadSession` define the production-facing direct-cloud boundary for stable account identity, native IDs, paged listing, streaming reads, upload-session identity, resumable state, and explicit commit/abort semantics.

`InMemoryDirectCloudAdapter` provides deterministic repository coverage of the direct-cloud contract. A vendor-native OAuth/SDK adapter is intentionally not represented as production-integrated without actual provider registration credentials. Real cloud-account interoperability remains a Step 7 external certification item.

## Credentials and trust hardening

- Non-secret connection metadata and secret material are persisted separately.
- `AndroidKeystoreCredentialStore` uses a 256-bit AES key generated inside Android Keystore and AES-GCM encrypted payloads for credential material.
- Passwords, SFTP private keys/passphrases, and OAuth refresh-secret material are not stored as plaintext connection metadata.
- SFTP does not use an accept-all host-key verifier.
- FTPS and HTTPS WebDAV do not use a global trust-all TLS manager.
- Optional certificate pinning remains connection scoped.
- Plain FTP and HTTP WebDAV cannot be made to appear encrypted by disabling warning preferences.
- Remote path normalization rejects NUL/root escape and prevents remote names from being treated as local paths.
- Secret values are excluded from the operation journal and application-facing connection-state text by design.

## Transactional transfer safety

Step 5 reuses the existing `FileOperationEngine`; it does not introduce a weaker parallel network-copy engine.

For streamed destinations:

- bytes are written to hidden `.zzpart-*` staged objects;
- the requested final name is not exposed while streaming is incomplete;
- expected byte count is checked before finalization;
- destination metadata/size is verified after finalization;
- provider mutation identity is checked when the provider can supply it;
- Replace keeps the known-good destination intact while new data is staged and uses atomic replace where available or a reversible backup ledger otherwise;
- MOVE deletes its source only after destination finalization and proof;
- failure, cancellation, timeout, provider disappearance, or verification failure cannot be promoted to successful MOVE completion.

A provider that cannot safely stage/finalize an operation does not receive a weaker destructive implementation.

## Verified process-death resume

A persisted byte offset is not accepted as proof by itself. Nonzero resume requires all of the following to validate against live state:

- persisted source revision identity;
- persisted staged-object identity;
- persisted `Long` resume offset;
- live staged object existence;
- exact equality between the live staged length and recorded offset;
- resumable source and destination provider contracts.

A changed source revision is rejected. A mismatched or invalid hidden stage is cleaned/restarted when safe rather than blindly appended. If the complete proof tuple cannot be persisted and revalidated, interruption recovery resets byte progress rather than guessing.

## Large-file position

Step 5 introduces no public 2 GiB/4 GiB transfer ceiling. Transfer sizes, offsets, aggregate progress, and resume positions use `Long`. Streaming uses the operation engine's bounded fixed-size buffer rather than allocating memory according to source size.

Repository certification includes logical 3 GiB, 10 GiB, and 30 GiB arithmetic/offset cases crossing the signed 32-bit boundary without allocating giant fixture files.

## Path and filename coverage

Executable coverage includes spaces, emoji, Bangla, German `äöüß`, combining Unicode, `#`, `%`, `?`, brackets/parentheses, long single-component names, and attempted `..` root escape rejection.

## Settings implemented

Persisted Step 5 settings include:

- connection timeout;
- retry count;
- Wi-Fi-only background-transfer preference;
- metered-network warning;
- safe auto-resume preference;
- hidden remote-file visibility;
- LAN discovery enable/disable;
- additional insecure-protocol warnings.

Plain FTP and HTTP WebDAV remain visibly unencrypted regardless of the additional-warning preference.

## Documentation set

The Step 5 branch contains:

- `STEP5_ARCHITECTURE.md` — provider graph, removable/remote/cloud architecture, transfer and lifecycle design;
- `STEP5_IMPLEMENTATION.md` — implementation map, dependencies, provider wiring, transfer and resume behavior;
- `STEP5_PROTOCOL_MATRIX.md` — truthful per-provider capability matrix and conditional behavior;
- `STEP5_SECURITY.md` — credential, trust, path, overwrite, resume and residual-risk security contract;
- `STEP5_TEST_MATRIX.md` — executable acceptance and deferred-external-test matrix;
- `STEP5_CI_EVIDENCE.md` — certification-trigger/evidence note used during hardening;
- `STEP5_COMPLETION_REPORT.md` — this completion and exact-head acceptance record.

## Certification assets

Step 5 retains all prior regression suites and adds/strengthens coverage for:

- remote MOVE source preservation;
- process-death download/upload visibility safety;
- known-good remote Replace preservation;
- changed-source resume rejection;
- SFTP host-key change blocking;
- FTPS certificate-pin change blocking;
- USB/provider removal during MOVE;
- network timeout queue progress;
- large `Long` offsets and filename/path edge cases;
- production factory registration for SMB, FTP, FTPS, SFTP, and WebDAV;
- a disposable local WebDAV integration service exercising the real production OkHttp/WebDAV client through OPTIONS, MKCOL, PUT, PROPFIND/stat/list, GET, MOVE, COPY, and DELETE;
- Android Keystore credential round trip and ciphertext-at-rest behavior;
- Step 5 settings persistence;
- URI-native SAF/cloud-location behavior;
- all retained Steps 1–4 instrumentation regressions.

CI builds debug and release variants, runs JVM tests and lint, compiles instrumentation, actually runs API-35 connected instrumentation, enforces zero skipped JVM tests, enforces zero skipped instrumentation tests, and uploads both report sets.

## Pre-completion-report validation evidence

The implementation/test/workflow tree immediately before creation of this report was validated by permanent **Android Step 5 CI #284**, run ID `34489429250`, on exact head:

`d28f4768effee0dde6afaf2b288aabc966779ff3`

Measured results from that run:

- Gradle `clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest`: **PASS**;
- JVM tests: **145 tests, 0 failures, 0 errors, 0 skipped**;
- all nine mandatory Step 5 hard-safety JVM test names found by the CI guard;
- API 35 `connectedDebugAndroidTest`: **40 tests, 0 failures, 0 errors, 0 skipped**;
- JVM/lint artifact: `jvm-lint-reports`, artifact ID `10157451163`;
- API-35 instrumentation artifact: `instrumentation-api35-reports`, artifact ID `10157615697`;
- overall job result: **success**.

That run also specifically proves the WebDAV 404/missing-object regression fix that was discovered by the preceding certification attempt. It certifies the implementation/test/workflow tree before this completion-report documentation commit.

## Mandatory hard-safety tests

The exact-head CI guard requires these JVM test cases to exist in produced XML and rejects certification if any are missing:

1. `remoteMoveNeverDeletesSourceBeforeDestinationProven`
2. `downloadProcessDeathNeverExposesPartialFinal`
3. `uploadProcessDeathNeverExposesPartialRemoteFinal`
4. `remoteReplacePreservesKnownGoodDestinationUntilCommitProof`
5. `resumeRejectsChangedRemoteRevision`
6. `sftpHostKeyChangeBlocksConnection`
7. `ftpsCertificateChangeBlocksConnection`
8. `usbRemovalDuringMovePreservesSource`
9. `networkTimeoutDoesNotDeadlockOperationQueue`

The same CI additionally fails if any JVM or instrumentation test XML reports a skipped test.

## Deferred physical/external certification

Repository CI does **not** claim that the following physical/external interoperability matrix has already been executed:

- real SD-card insertion/removal/reinsertion;
- real USB insertion/removal/reinsertion;
- real NAS SMB2/SMB3 interoperability;
- external FTP/FTPS deployment variants;
- external SFTP host-key lifecycle;
- router-specific DNS-SD/mDNS behavior;
- external WebDAV services;
- real cloud-account OAuth/vendor-provider interoperability;
- sustained multi-gigabyte transfer performance on physical devices.

Those checks require hardware or external infrastructure and remain explicitly assigned to Step 7. This report therefore certifies deterministic repository/application behavior without misrepresenting external systems as physically tested.

## Non-goals

- Step 6 is not started or pulled into this branch.
- SMB1 is not supported.
- No accept-all SSH/TLS trust mode is introduced.
- Nonzero resume is not promised merely because a protocol exposes seek/range operations.
- Vendor-native direct cloud integration is not claimed without real provider registration credentials.
- Physical-device/external-server certification is not claimed at Step 5.

## Certification status

**CERTIFIED only when the permanent `Android Step 5 CI` run automatically associated with this exact completion-report commit concludes `success`.**

The wording is intentionally exact-head based. The pre-report #284 result above proves the production/test/workflow tree, but this documentation commit creates a new branch head. Under the project acceptance rule, the new head must receive its own full successful API-35 certification before Step 5 can be declared READY.

## Final acceptance rule

Step 5 is accepted only when all of the following are true on one exact branch-head SHA:

1. `assembleDebug` passes;
2. `testDebugUnitTest` passes;
3. every mandatory hard-safety JVM test name is present in generated test XML;
4. JVM test XML reports zero skipped tests;
5. `lintDebug` passes;
6. `assembleRelease` passes;
7. `assembleDebugAndroidTest` passes;
8. API-35 `connectedDebugAndroidTest` actually executes and passes;
9. instrumentation XML reports zero skipped tests;
10. the passing workflow run's `head_sha` equals the current `step5/removable-network-cloud` branch head;
11. no temporary patch/hardening workflow remains in the branch;
12. `STEP5_ARCHITECTURE.md`, `STEP5_IMPLEMENTATION.md`, `STEP5_PROTOCOL_MATRIX.md`, `STEP5_SECURITY.md`, `STEP5_TEST_MATRIX.md`, and `STEP5_COMPLETION_REPORT.md` exist in `docs/`;
13. physical/external Step 7 items remain labelled deferred rather than silently marked passed.

If the exact-head run fails, this certification statement is invalid until the failure is corrected and a new exact-head run succeeds.
