# Step 5 Implementation

## Repository and branch

- Repository: `ZubaerAhmed13/ZZ-File-Manager-`
- Branch: `step5/removable-network-cloud`
- Approved Step 4 base: `01dab47f3c02e4667c80587fe1a17d06e1f65677`
- Android target/compile SDK: 35
- Step 5 app version: `0.5.0-step5`

## Main implementation packages

### Storage

`core/storage/StorageProvider.kt` defines provider-neutral read/write contracts and truthful capabilities. `StorageRepository.kt` owns built-in and runtime external provider registration. `SafStorageProvider.kt` implements URI-native removable/cloud DocumentsProvider access.

Removable roots are persisted with `SafLocationKind`: `SD_CARD`, `USB`, `CLOUD`, or `GENERIC`. Reconnection preserves the saved logical storage identity when a stable removable-volume token is available.

### Remote model and credentials

`core/remote/RemoteModels.kt` defines protocol, authentication, TLS/certificate policy, connection state and persisted transfer settings.

`NetworkConnectionRepository.kt` stores non-secret metadata and transfer settings. `AndroidKeystoreCredentialStore.kt` encrypts password/private-key/passphrase/OAuth-refresh secret material using AES-GCM with a key generated in Android Keystore.

`RemoteConnectionService.kt` coordinates save, remove, disconnect, connection testing and explicit SFTP host-key trust. Presentation code never asks the credential store for plaintext secrets directly.

### Runtime providers

`RemoteProviderCoordinator.kt` creates a `RemoteStorageProvider` for each saved connection and registers it with `StorageRepository`. No connection is opened merely because the app starts or the Network screen is shown.

`RemoteStorageProvider.kt` maps generic storage operations to protocol operations, maps protocol errors into storage-layer failures, and scopes every remote path to the saved connection root.

### Protocol clients

- `SmbRemoteFileSystem.kt`: SMBJ, SMB 2.0.2–3.1.1 only, signing enabled, offset streams, stable identity, native move, server-side copy and provider replace support.
- `FtpRemoteFileSystem.kt`: Apache Commons Net FTP/FTPS, binary/passive mode, explicit/implicit FTPS, endpoint checking, optional certificate pinning, REST-based offset I/O only when advertised.
- `SftpRemoteFileSystem.kt`: SSHJ, password/private-key authentication, explicit SHA-256 host-key trust, offset-safe streams.
- `WebDavRemoteFileSystem.kt`: OkHttp HTTP/HTTPS WebDAV, OPTIONS capability discovery, PROPFIND metadata, streamed PUT, GET, MKCOL, DELETE, MOVE and COPY where supported; redirects are rejected for explicit endpoint review.

### LAN discovery

`LanDiscoveryService.kt` uses Android `NsdManager` DNS-SD/mDNS. It watches only known service types and stores at most 100 candidates. Discovery is opt-in per session and can be disabled in Settings. It never scans arbitrary addresses or ports and never attempts authentication.

The Network & Remote screen displays discovered service name, hostname, resolved IP when available, protocol and port. **Add / Connect** converts only that selected candidate into an editable unsaved connection draft.

### Cloud

`core/cloud/DirectCloudAdapter.kt` defines account identity, paged listing, native file identity, streaming reads, resumable upload-session state and explicit upload commit/abort. `InMemoryDirectCloudAdapter.kt` provides deterministic repository coverage of this contract.

Production cloud applications that expose Android DocumentsProvider are supported through SAF. A vendor-native direct OAuth adapter is not shipped without real provider registration credentials; that integration/real-account certification belongs to Step 7.

## Transfer safety implementation

`FileOperationEngine.kt` is reused rather than creating a separate network copy engine. This preserves existing queue, cancellation, collision and recovery behavior across Local, SAF and remote providers.

All streamed files are written to hidden `.zzpart-*` names. A provider must support safe final rename before a streamed operation can expose a final filename. Existing destinations remain untouched during the stream. Replace uses atomic provider replacement when available, otherwise a reversible destination-backup ledger.

After byte streaming, the engine confirms the expected total byte count. After finalization it retrieves destination metadata and verifies expected size. It also checks destination mutation identity stability when the provider can supply one. Only after this proof can MOVE remove its source.

## Resume implementation

`OperationItem` persists:

- `resumeSourceIdentity: String?`
- `resumeStagedIdentity: String?`
- `resumeOffset: Long`
- the existing `partialOutput` and `processedBytes`.

`OperationJsonCodec` journals these fields. `OperationRecovery.markHostExecutionInterrupted` carries a checkpoint across process death only if the complete persisted proof tuple is internally consistent.

`TransferResumeCoordinator.evaluate` then revalidates the proof against the live source and hidden destination. Resume at a nonzero offset requires `ResumableStorageProvider` + `ResumableWritableStorageProvider`, matching source identity, matching staged identity, staged existence and exact staged length equality. The stream open is performed only after that evaluation.

A changed source produces `SOURCE_CHANGED`. An invalid stage is cleaned and restarted rather than appended. A provider that exposes seek I/O but cannot prove staged identity receives no nonzero resume.

## Settings implementation

Network settings are persisted with the connection repository and exposed in the main Settings screen:

- connection timeout,
- retry count,
- Wi-Fi-only background transfer preference,
- metered-network warning,
- safe auto-resume preference,
- hidden remote file display,
- LAN discovery enable/disable,
- additional insecure-protocol warnings.

Plain FTP and HTTP WebDAV remain visibly labelled unencrypted irrespective of warning preference.

## Large-file implementation rules

Byte sizes, offsets, aggregate progress and resume offsets use `Long`. File transfer uses the operation engine's fixed 256 KiB default buffer rather than allocating according to file size. The logical 3 GiB, 10 GiB and 30 GiB certification tests exercise values beyond the signed 32-bit limit without allocating giant fixtures.

## Dependency inventory

| Dependency | Version | Purpose | License |
|---|---:|---|---|
| SMBJ | 0.15.0 | SMB2/SMB3 | Apache License 2.0 |
| Apache Commons Net | 3.13.0 | FTP/FTPS | Apache License 2.0 |
| SSHJ | 0.40.0 | SFTP/SSH | Apache License 2.0 |
| OkHttp | 4.12.0 | HTTP/HTTPS WebDAV | Apache License 2.0 |
| Bouncy Castle `bcprov-jdk18on` | 1.85 | SSH/crypto provider support | Bouncy Castle License (MIT-style) |
| Bouncy Castle `bcutil-jdk18on` | 1.85 | Crypto utility support | Bouncy Castle License (MIT-style) |
| Bouncy Castle `bcpkix-jdk18on` | 1.85 | PKIX/PEM support | Bouncy Castle License (MIT-style) |

APK Java-resource packaging excludes duplicate third-party license/notice/OSGi metadata only. It does not exclude provider classes or disable duplicate-class detection.

## CI implementation

`.github/workflows/android.yml` is named **Android Step 5 CI**. The certification job runs the complete build/JVM/lint/release/instrumentation compile command, rejects skipped JVM tests, runs API 35 instrumentation on an emulator, rejects skipped instrumentation tests and uploads reports.

The completion report is not allowed to claim READY until this workflow is green on the final reviewed Step 5 branch head.
