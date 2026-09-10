# Step 5 Test Matrix

Step 5 is certified only by a green **Android Step 5 CI** run on the exact reviewed head, with zero JVM/instrumentation failures, errors or skips and with all mandatory named tests present in produced XML.

## Mandatory JVM safety and behavior gate

The workflow requires these test cases by exact name:

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

The gate fails if any name is absent even if the overall test command exits successfully.

## Transfer safety

Operation-engine regression tests cover remote MOVE source preservation, hidden staging across process death, safe Replace behavior, changed-source resume rejection, USB/provider disappearance, timeout queue progress and exact staged-identity/length requirements.

Auto-resume coverage proves only interrupted COPY/MOVE work is considered and destructive metadata operations such as DELETE are not silently replayed.

## Executable settings and bounded input

`RemoteTransferExecutionPolicyTest` proves Wi-Fi-only background transfer blocks remote execution off Wi-Fi and metered-network warning remains a visible advisory instead of a false block.

`BoundedInputStreamTest` proves private-key import stops at the configured sentinel limit rather than reading an unbounded source into memory.

## Large files and paths

`Step5LargeFileAndPathCertificationTest` exercises logical 3 GiB, 10 GiB and 30 GiB positions with `Long` arithmetic without allocating giant files. Path coverage includes spaces, emoji, Bangla, German characters, combining Unicode, URL-sensitive characters, long names and root-escape rejection.

## 100,001-entry browser certification

`Step5LargeRemoteDirectoryTest.hundredThousandEntryRemoteDirectoryRendersFirstPageBeforeEnumerationCompletes` forces the incremental `BrowserStorage` path. It emits 256 entries, blocks the remaining enumeration, verifies those 256 are already rendered, then releases the rest and verifies the final 100,001-entry result. This prevents regression to full-directory buffering before first render.

## Disposable protocol interoperability

Step 5 CI starts real disposable services and requires production-client tests:

- SMB2/3: negotiation/authentication, offset I/O, remote/server-side copy and provider replace;
- FTP: authenticated real control/data session and file operations;
- FTPS: authenticated TLS control/data session with generated certificate pin;
- SFTP: real SSH/SFTP session including first-contact fingerprint acquisition and pinned reconnect;
- WebDAV: the existing local HTTP integration exercises OPTIONS, MKCOL, PUT, PROPFIND/stat/list, GET, MOVE, COPY and DELETE through the production OkHttp client.

The environment variable `STEP5_PROTOCOL_INTEROP_REQUIRED=1` makes unavailable disposable fixtures a CI failure instead of a skip.

## Direct-cloud certification

JVM coverage proves stable native IDs across rename/move, account namespacing, provider paging, committed writes, and `Long` resume offsets beyond 30 GiB.

API-35 instrumentation requires `directCloudOAuthLifecycleConnectRefreshDisconnectAndRevokeIsComplete`, proving connect, secure refresh-token persistence, refresh, local disconnect, provider revoke, local removal and caller secret-array clearing through the Android-side account lifecycle.

## API 35 instrumentation gate

The workflow runs `connectedDebugAndroidTest` on an API-35 emulator after debug/release builds, JVM tests, lint and instrumentation compilation. The instrumentation XML gate requires the direct-cloud lifecycle test by name and rejects any skipped test in the complete instrumentation suite. Existing Steps 1–4 instrumentation remains part of the same regression run.

## Build commands

JVM/build/lint/release gate:

```text
./gradlew clean assembleDebug testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest --stacktrace
```

API-35 device gate:

```text
./gradlew connectedDebugAndroidTest --stacktrace
```

## Deferred external matrix

Repository CI does not claim physical SD/USB insertion/removal timing, vendor-specific NAS/router behavior, external certificate deployments, real vendor OAuth accounts, or sustained multi-gigabyte physical-device performance. Those remain Step 7 external certification items.
