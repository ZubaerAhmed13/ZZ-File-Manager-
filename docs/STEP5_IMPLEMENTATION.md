# Step 5 Implementation

## Repository and branch

- Repository: `ZubaerAhmed13/ZZ-File-Manager-`
- Delivery branch: `step5/removable-network-cloud`
- Finalization branch during certification: `hardening/step5-certification-finalize`
- Approved Step 4 base: `01dab47f3c02e4667c80587fe1a17d06e1f65677`
- Android target/compile SDK: 35
- Step 5 app version: `0.5.0-step5`

Step 6 functionality is not part of this implementation.

## Storage and removable media

`core/storage/StorageProvider.kt` defines provider-neutral read/write contracts and capabilities. `StorageRepository.kt` owns built-in and runtime provider registration. `SafStorageProvider.kt` implements URI-native removable/cloud DocumentsProvider access.

Removable roots persist `SafLocationKind` (`SD_CARD`, `USB`, `CLOUD`, `GENERIC`) and preserve logical volume identity where Android provides a stable token. Content URIs are not converted to filesystem paths.

## Remote metadata and credentials

`core/remote/RemoteModels.kt` defines protocol/authentication/TLS/state/settings models. `NetworkConnectionRepository.kt` stores non-secret metadata. `AndroidKeystoreCredentialStore.kt` encrypts password, key, passphrase and OAuth refresh-secret material using AES-GCM with an Android Keystore key.

Private-key import is bounded by `feature/remote/BoundedInputStream.kt`; the importer reads no more than the configured maximum plus one sentinel byte used to reject oversized input.

## Runtime provider registration

`RemoteProviderCoordinator.kt` rebuilds external providers from saved non-secret metadata. It registers network providers through `RemoteStorageProvider` and direct-cloud accounts through `DirectCloudStorageProvider` when a matching cloud adapter is installed. `AUTH_REQUIRED` direct-cloud accounts are not registered as available providers.

No provider is connected merely because the app starts or a screen opens. Secrets are resolved lazily when an operation needs a session.

## Protocol clients

- `SmbRemoteFileSystem.kt`: SMBJ, SMB2/SMB3 only, signing, streaming and offset I/O, stable identity, native move, server-side copy and provider replace.
- `FtpRemoteFileSystem.kt`: Apache Commons Net FTP/FTPS, binary/passive mode, explicit/implicit FTPS, endpoint verification and optional leaf SHA-256 pinning.
- `SftpRemoteFileSystem.kt`: SSHJ, password/private-key authentication, explicit SHA-256 host-key trust and offset I/O.
- `WebDavRemoteFileSystem.kt`: OkHttp HTTP/HTTPS WebDAV, OPTIONS discovery, PROPFIND metadata, streamed PUT/GET, MKCOL, DELETE, MOVE and COPY; redirects rejected.

Connection-scoped trust management supports explicit SFTP key replacement and FTPS/WebDAV certificate-pin decisions. There is no global accept-all trust mode.

## Incremental browser loading

`BrowserStorage` exposes `listChildrenIncrementally`. Remote/storage implementations can emit pages while enumeration continues. `BrowserViewModel` consumes those pages and updates content incrementally.

The Step 5 large-directory certification emits a first page of 256 entries from a synthetic 100,001-entry remote directory, blocks further enumeration, verifies the first page is already rendered, then releases and verifies all remaining pages.

## Direct cloud

`core/cloud/DirectCloudAdapter.kt` defines provider/account identity, native file IDs, paged listing, streaming reads, upload sessions, revision proof and commit/abort semantics.

`DirectCloudStorageProvider.kt` adapts that contract to the common storage layer so direct-cloud accounts participate in browser and operation flows without path-string emulation.

`DirectCloudAccountManager.kt` implements connect, refresh, disconnect, revoke and local-account removal. OAuth secrets use the same secure credential store as network credentials; temporary token arrays are cleared where owned by the app.

`DirectCloudAdapterRegistry` and `DirectCloudOAuthDriverRegistry` are intentionally empty in repository-safe `AppContainer`. A distribution with legitimate vendor OAuth registration installs concrete adapters/drivers. `InMemoryDirectCloudAdapter` is used for deterministic repository certification and is not presented as a vendor service.

## Transactional transfer safety

`FileOperationEngine.kt` remains the authoritative copy/move/replace engine. Streamed destinations use hidden `.zzpart-*` staging and bounded buffers. Existing destinations remain intact while replacement data is staged. Atomic provider replace is used where available; otherwise a reversible backup ledger is used.

Expected byte count and destination metadata are verified before success. MOVE deletes its source only after destination finalization and proof.

## Resume

`OperationItem` persists source identity, staged identity and `Long` resume offset. `OperationRecovery` retains a checkpoint only when the proof tuple is internally consistent. `TransferResumeCoordinator` then revalidates source revision, staged identity, staged existence and exact staged length before opening a nonzero-offset stream.

If proof fails, resume is rejected or safely restarted. Protocol-level seek/range support alone does not enable process-death resume.

## Executable transfer settings

Step 5 settings are connected to runtime behavior:

- connection timeout and retry count affect remote execution;
- Wi-Fi-only background transfer blocks execution when the active network does not satisfy Wi-Fi policy;
- metered-network warning is surfaced without falsely blocking an otherwise permitted operation;
- auto-resume only requeues interrupted COPY/MOVE operations and preserves their proof for revalidation;
- DELETE and other destructive metadata operations are never automatically replayed by auto-resume;
- hidden remote file visibility affects browser filtering;
- LAN discovery enable/disable controls discovery availability;
- insecure-protocol warnings never hide the unencrypted state of FTP/HTTP WebDAV.

## Disposable protocol CI

`.github/workflows/android.yml` starts real disposable SMB2/3 and OpenSSH/SFTP containers plus real FTP/FTPS servers. FTPS receives a generated self-signed certificate whose exact SHA-256 leaf fingerprint is passed to the production client test. Readiness is required before Gradle runs.

The workflow requires real production-client interoperability test names in JUnit XML and fails if fixtures are unavailable, tests are missing, or any JVM test is skipped.

The existing WebDAV integration continues to exercise the production OkHttp client against a disposable local service.

## Dependencies

| Dependency | Version | Purpose | License |
|---|---:|---|---|
| SMBJ | 0.15.0 | SMB2/SMB3 | Apache License 2.0 |
| Apache Commons Net | 3.13.0 | FTP/FTPS | Apache License 2.0 |
| SSHJ | 0.40.0 | SFTP/SSH | Apache License 2.0 |
| OkHttp | 4.12.0 | WebDAV/TLS | Apache License 2.0 |
| Bouncy Castle `bcprov-jdk18on` | 1.85 | SSH/crypto support | Bouncy Castle License |
| Bouncy Castle `bcutil-jdk18on` | 1.85 | Crypto utilities | Bouncy Castle License |
| Bouncy Castle `bcpkix-jdk18on` | 1.85 | PKIX/PEM support | Bouncy Castle License |

## CI acceptance

The canonical **Android Step 5 CI** runs debug build, JVM tests, lint, release build, instrumentation compile, API-35 connected instrumentation, exact mandatory-name checks and zero-skip checks. Step 5 is not READY unless that workflow is green on the exact reviewed delivery SHA.
