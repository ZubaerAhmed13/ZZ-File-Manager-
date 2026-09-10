# Step 5 Completion Report

## Deliverable

**Step 5 — Removable, Network and Cloud Storage** is the additive storage/remote/cloud extension of the approved Steps 1–4 architecture. The delivery branch is `step5/removable-network-cloud`; final hardening is performed on `hardening/step5-certification-finalize` and may be fast-forwarded to the delivery branch only after exact-head certification succeeds.

Step 6 functionality is not included.

## Completion scope

Step 5 includes:

- removable SD/USB/cloud/generic SAF roots with persisted URI grants and removable-volume identity checks where Android exposes a stable token;
- saved SMB, FTP, FTPS, SFTP and WebDAV locations through the common provider/capability model;
- encrypted credential storage through Android Keystore + AES-GCM;
- explicit SFTP host-key and TLS certificate-pin trust decisions without global accept-all modes;
- user-initiated bounded LAN discovery;
- transactional hidden-stage transfer safety and destination proof before MOVE source deletion;
- proof-based process-death resume using source revision, staged identity and exact `Long` staged length;
- incremental remote directory delivery to the browser;
- executable retry, hidden-file, Wi-Fi-only, metered-warning and safe auto-resume settings;
- a direct-cloud storage provider boundary with account lifecycle management and conditional runtime adapter registration.

## Network protocols

### SMB

Production SMB uses SMBJ and offers SMB 2.0.2 through SMB 3.1.1 only. SMB1 is not enabled. Streaming/offset I/O, stable identity, native move, server-side copy and provider replace are implemented according to advertised capabilities.

### FTP / FTPS

Production FTP/FTPS uses Apache Commons Net in binary/passive mode. FTPS supports endpoint verification and connection-scoped leaf SHA-256 pinning. Plain FTP remains visibly unencrypted. REST/offset capability is not treated as sufficient process-death resume proof by itself.

### SFTP

Production SFTP uses SSHJ with password/private-key authentication and explicit SHA-256 host-key trust. Changed host keys block the connection until the user makes a new explicit trust decision. Offset I/O exists, but generic nonzero resume still requires complete staged-object proof.

### WebDAV

Production WebDAV uses OkHttp with server-driven capability discovery, PROPFIND metadata, PUT/GET, MKCOL, DELETE, MOVE and COPY where supported. HTTPS uses system TLS plus optional connection-scoped pinning. HTTP is always labelled unencrypted. Redirects are rejected for explicit endpoint review.

## Direct cloud

`DirectCloudAdapter`, `DirectCloudSession`, `CloudUploadSession` and `DirectCloudStorageProvider` provide a runtime storage path for native provider IDs, paged listing, streaming reads/writes, native rename/move, revision proof and explicit upload commit/abort.

`DirectCloudAccountManager` implements connect, refresh, local disconnect, provider revoke and local account removal. Refresh tokens are stored behind the secure credential boundary and temporary app-owned token arrays are cleared.

`RemoteProviderCoordinator` registers a saved direct-cloud account only when the account is usable and a matching adapter exists. Repository-safe `AppContainer` intentionally has empty vendor adapter/OAuth-driver registries, so no fake Google/Microsoft credentials are embedded. Concrete vendor OAuth interoperability remains distribution/external certification work rather than a false Step 5 claim.

## Incremental 100,001-entry behavior

The browser/storage boundary supports incremental page delivery. `hundredThousandEntryRemoteDirectoryRendersFirstPageBeforeEnumerationCompletes` emits 256 entries, blocks the remaining enumeration, proves those entries are already visible, and only then releases the full 100,001-entry result. This prevents a regression to waiting for a complete large remote directory before first render.

## Secret and input hardening

Passwords, private keys/passphrases and OAuth refresh secrets are separate from saved non-secret metadata. Private-key import is bounded and uses a sentinel beyond the configured maximum so oversized input cannot trigger unbounded reads.

## Transfer-policy hardening

Wi-Fi-only background transfer is an execution gate. Metered warning produces visible advisory state without false blocking. Auto-resume only requeues interrupted COPY/MOVE work that will still undergo resume-proof validation; it never automatically replays DELETE or other destructive metadata operations.

## Disposable protocol interoperability

Canonical Step 5 CI starts real disposable SMB2/3, FTP, FTPS and SFTP servers. The production clients must authenticate and exercise their required behaviors. FTPS uses a generated self-signed certificate and the exact SHA-256 leaf pin for the test connection. The existing local WebDAV integration continues to exercise the production OkHttp client.

These tests materially improve repository interoperability evidence without misrepresenting external NAS/router/vendor/hardware coverage.

## Mandatory JVM certification names

The canonical workflow requires all 22 names below in generated JVM XML:

1. `remoteMoveNeverDeletesSourceBeforeDestinationProven`
2. `downloadProcessDeathNeverExposesPartialFinal`
3. `uploadProcessDeathNeverExposesPartialRemoteFinal`
4. `remoteReplacePreservesKnownGoodDestinationUntilCommitProof`
5. `resumeRejectsChangedRemoteRevision`
6. `sftpHostKeyChangeBlocksConnection`
7. `ftpsCertificateChangeBlocksConnection`
8. `usbRemovalDuringMovePreservesSource`
9. `networkTimeoutDoesNotDeadlockOperationQueue`
10. `smbClientPerformsNegotiationOffsetIoRemoteCopyAndAtomicReplaceAgainstDisposableServer`
11. `ftpClientPerformsRealSessionAgainstDisposableServer`
12. `ftpsClientPerformsRealTlsSessionAgainstDisposableServer`
13. `sftpClientPerformsRealSessionAgainstDisposableServer`
14. `privateKeyImportReadStopsAtConfiguredSentinelLimit`
15. `wifiOnlyBackgroundTransfersBlockRemoteExecutionOffWifi`
16. `warnOnMeteredNetworksProducesVisibleWarningWithoutFalseBlock`
17. `autoResumeInterruptedTransfersHonorsSettingAndDoesNotReplayDelete`
18. `hundredThousandEntryRemoteDirectoryRendersFirstPageBeforeEnumerationCompletes`
19. `directCloudProviderRegistersNativeIdsPagesAndCommittedWrites`
20. `nativeIdsSurviveRenameAndMove`
21. `pagingAndMultipleAccountsAreNamespaced`
22. `resumeTokenUsesLongBeyondThirtyGiBWithoutOverflow`

Any missing name invalidates certification.

## Mandatory API-35 instrumentation name

The instrumentation XML must include `directCloudOAuthLifecycleConnectRefreshDisconnectAndRevokeIsComplete`. The complete instrumentation suite must report zero skipped tests.

## CI gate

The canonical workflow runs:

```text
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest --stacktrace
```

and then on an API-35 emulator:

```text
./gradlew connectedDebugAndroidTest --stacktrace
```

Certification requires zero JVM failures/errors/skips, all 22 mandatory JVM names, zero instrumentation failures/errors/skips, the mandatory direct-cloud instrumentation name, successful debug/release/lint/androidTest compilation, and successful disposable-protocol readiness/interoperability.

## Documentation set

The final Step 5 tree includes:

- `STEP5_ARCHITECTURE.md`
- `STEP5_IMPLEMENTATION.md`
- `STEP5_PROTOCOL_MATRIX.md`
- `STEP5_SECURITY.md`
- `STEP5_TEST_MATRIX.md`
- `STEP5_CI_EVIDENCE.md`
- `STEP5_COMPLETION_REPORT.md`

## Deferred Step 7 external certification

The following remain external/physical certification rather than repository-CI claims:

- real SD-card and USB insertion/removal/reinsertion timing;
- vendor-specific NAS and router behavior;
- external FTP/FTPS/SFTP/WebDAV deployments and certificate rotations;
- router-specific DNS-SD/mDNS behavior;
- real vendor cloud OAuth/account interoperability;
- sustained multi-gigabyte performance on physical devices.

## Exact-head certification rule

This document does not predeclare a run ID or success result. **Step 5 is READY only if the permanent `Android Step 5 CI` run associated with this exact reviewed commit concludes `success`.** A later code, test, workflow or documentation commit creates a new head and invalidates earlier exact-head certification.

After the finalization head is green, `step5/removable-network-cloud` may be advanced only by non-forced fast-forward to the same certified SHA. If the delivery-branch push starts another canonical run, that same-SHA delivery run must also succeed before the branch is treated as fully closed.
