# Step 5 Test Matrix

This matrix maps Step 5 acceptance risks to executable repository tests and the API 35 CI gate. A test is not considered certified merely because it exists; the completion report may mark Step 5 READY only after **Android Step 5 CI** is green on the final reviewed head with zero skipped JVM and instrumentation tests.

## Mandatory hard-safety tests

| Required test | Layer | Safety assertion |
|---|---|---|
| `remoteMoveNeverDeletesSourceBeforeDestinationProven` | JVM / operation engine | Failed destination write cannot delete the authoritative MOVE source or expose a completed destination. |
| `downloadProcessDeathNeverExposesPartialFinal` | JVM / operation engine | Process death during stream leaves only hidden staged data; final download name is absent. |
| `uploadProcessDeathNeverExposesPartialRemoteFinal` | JVM / operation engine | Process death during upload does not expose the final remote name. |
| `remoteReplacePreservesKnownGoodDestinationUntilCommitProof` | JVM / replace engine | A failed replacement stream leaves the old known-good destination intact. |
| `resumeRejectsChangedRemoteRevision` | JVM / resume coordinator | A persisted offset is rejected when live source revision differs from the journaled revision. |
| `sftpHostKeyChangeBlocksConnection` | JVM / SSH trust verifier | A different returning SSH public key fails the saved SHA-256 trust decision and reports server identity change. |
| `ftpsCertificateChangeBlocksConnection` | JVM / certificate pin policy | A different leaf-certificate byte identity fails the configured SHA-256 pin. |
| `usbRemovalDuringMovePreservesSource` | JVM / operation engine | Provider disappearance while streaming a MOVE keeps the source and never exposes the final USB destination. |
| `networkTimeoutDoesNotDeadlockOperationQueue` | JVM / operation queue | A timeout terminates the affected operation without preventing the next queued operation from completing. |

## Large-file / encoding tests

`Step5LargeFileAndPathCertificationTest` executes logical 3 GiB, 10 GiB and 30 GiB cases using `Long` offsets. It does not allocate files of those sizes. The assertions cross the signed 32-bit boundary, verify remaining/progress arithmetic and retain a bounded transfer-buffer assumption.

Filename/path coverage includes:

- spaces,
- emoji,
- Bangla,
- German `äöüß`,
- combining Unicode,
- `#`, `%`, `?`,
- brackets and parentheses,
- a long single-component name,
- attempted `..` root escape rejection.

## Protocol integration

`Step5ProtocolIntegrationTest` verifies that production factories are registered for SMB, FTP, FTPS, SFTP and WebDAV.

It also starts a disposable local HTTP WebDAV server and exercises the **real OkHttp/WebDAV production client** through capability discovery, MKCOL, streaming PUT, PROPFIND/stat/list, GET, MOVE, COPY and DELETE. This prevents all Step 5 protocol coverage from being mock-only.

SMB/FTP/FTPS/SFTP hardware/server interoperability remains a Step 7 physical/external certification concern. Their deterministic parsing, capability, trust and operation-safety behavior remains covered in Step 5 repository tests.

## Android API 35 instrumentation

`Step5CertificationInstrumentationTest` runs on the API 35 emulator and covers:

- Android Keystore credential round trip,
- ciphertext-at-rest assertion that the plaintext secret is absent from the app-private credential preference value,
- persistence of Step 5 transfer/discovery warning settings,
- URI-native SAF/cloud location serialization without path conversion.

All existing Steps 1–4 instrumentation remains in the same `connectedDebugAndroidTest` run and therefore forms the regression gate.

## Existing regression suites retained

Step 5 retains and runs the existing suites for:

- browser/navigation and SAF grants,
- copy/move/delete/rename/batch rename,
- collision and replace transaction recovery,
- user library/search/favorites/recent/recycle-bin flows,
- archive/media/text/APK/storage analyzer Step 4 behavior,
- Step 4 recovery and large-file arithmetic,
- URI/path safety and browser model utilities.

## Build and CI gates

The CI job runs:

```text
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest --stacktrace
```

Then, on an Android API 35 emulator:

```text
./gradlew connectedDebugAndroidTest --stacktrace
```

Certification fails if JVM or instrumentation test XML reports any skipped tests. The final completion report additionally records the exact workflow run, head SHA, total tests, failures/errors/skips and final result.

## Deferred external matrix

The following are intentionally **not** claimed as physically executed by repository CI and are carried to Step 7:

- real SD card insertion/removal/reinsertion,
- real USB drive insertion/removal/reinsertion,
- NAS SMB2/SMB3 dialect interoperability,
- external FTP/FTPS servers and certificate deployment variants,
- external SFTP host-key lifecycle,
- router-specific DNS-SD/mDNS behavior,
- external WebDAV services,
- real cloud-account OAuth/provider interoperability,
- sustained multi-gigabyte transfer performance on physical devices.
