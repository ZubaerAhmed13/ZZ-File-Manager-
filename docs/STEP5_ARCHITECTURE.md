# Step 5 Architecture — Removable, Network and Cloud Storage

## Scope

Step 5 extends the provider-neutral browser and file-operation engine from Steps 1–4 to removable SAF roots and saved remote locations. It does not begin Step 6. Physical SD-card, USB-device, NAS/LAN and real cloud-account certification remains a Step 7 activity.

Approved Step 4 base: `01dab47f3c02e4667c80587fe1a17d06e1f65677`.

## Provider graph

`StorageRepository` remains the application registry and browser boundary. Built-in providers are Local, SAF and MediaStore. Step 5 adds runtime registrations through `RemoteProviderCoordinator`; saved remote metadata is read at startup and provider objects are registered without opening a network session or decrypting credentials.

A `FileReference` carries a stable logical `providerId` plus a provider-native opaque identifier. A `ScopedFileReference` also carries the authorized logical root and storage identity. Remote paths are normalized POSIX-style identifiers inside their provider. They are never converted into local `java.io.File` paths.

`StorageCapability`/`ProviderCapabilities` is the single feature-advertising contract. The browser and operation engine must ask the provider what is actually supported instead of assuming Local-filesystem semantics.

## Removable storage

SD cards and USB storage use Android Storage Access Framework tree URIs. The user explicitly grants a tree using the platform picker. `StorageRepository.registerSafLocation` persists the tree grant and labels its durable kind as SD card, USB, cloud or generic SAF.

For removable media, a stable storage token derived from the authorized volume identity is retained. `reconnectSafLocation` refuses to silently map a saved SD/USB location to a different observed removable-volume token. `removableStatus` distinguishes available, read-only, removed, permission-lost and unsupported states.

The SAF provider continues to use URI-native `DocumentsContract`/`DocumentFile` operations. It does not manufacture filesystem paths for content URIs.

## Saved remote locations

`NetworkConnectionRepository` stores non-secret connection metadata. `AndroidKeystoreCredentialStore` stores encrypted credential payloads separately. `RemoteConnectionService` owns save/test/remove/disconnect/trust actions. `RemoteProviderCoordinator` translates saved connections into provider registrations.

Protocols implemented by production factories:

- SMB through SMBJ, restricted to SMB 2.0.2 through SMB 3.1.1 dialects.
- FTP/FTPS through Apache Commons Net.
- SFTP through SSHJ.
- WebDAV over HTTP/HTTPS through OkHttp.

Every returned remote stream owns its protocol session and closes that session when the stream closes. Blocking protocol work is kept off the main thread by the remote provider adapter.

## LAN discovery

`LanDiscoveryService` uses Android `NsdManager` DNS-SD/mDNS discovery for known SMB, FTP, FTPS, SSH/SFTP and WebDAV service types. Discovery is explicit and can be disabled in Settings.

Discovery does not enumerate IP ranges, scan arbitrary ports, try credentials, or automatically save/connect a result. At most 100 candidates are retained. A candidate exposes service name, hostname, optional resolved IP address, protocol and port. The user must choose **Add / Connect**, after which the normal editable connection form is shown.

## Cloud architecture

Two cloud paths exist by design:

1. **SAF/DocumentsProvider cloud** — cloud apps that expose Android DocumentsProvider are used through persisted tree URIs and the normal SAF provider.
2. **Direct provider boundary** — `DirectCloudAdapter`, `DirectCloudSession` and `CloudUploadSession` define stable account identity, native file IDs, paging, streaming reads, resumable upload-session identity and explicit commit/abort semantics.

`InMemoryDirectCloudAdapter` is the repository-safe reference implementation used to validate the boundary. A production vendor SDK/OAuth adapter is intentionally not hard-coded without provider registration credentials; real-account certification is deferred to Step 7.

## Transfer architecture

The existing `FileOperationEngine` remains authoritative for copy/move/replace. It streams with a bounded fixed buffer and persists `Long` byte counters.

For streamed destinations the engine writes to a hidden `.zzpart-*` object. A final visible filename is not created until streaming succeeds and the provider can safely rename/finalize it. Replace keeps the known-good destination until staged output is complete; providers without a safe replace path do not receive a destructive Replace option.

A MOVE deletes its source only after destination finalization and destination proof. If copy/finalization/proof fails, the source remains authoritative.

## Verified resume

Resume is capability- and identity-gated through `TransferResumeCoordinator`. A nonzero restart offset is accepted only when all of these still match live provider state:

- persisted source revision identity,
- persisted staged-object identity,
- persisted `Long` resume offset,
- live staged object exists,
- live staged byte length exactly equals the persisted offset,
- both source and destination implement the resumable provider contract.

If the source revision changed, resume is rejected. If staged identity/length is invalid, the hidden stage is discarded and the transfer restarts from zero when cleanup is possible. Providers that cannot prove these conditions simply do not resume at a nonzero offset.

## Lifecycle and recovery

Operation journals survive host/process interruption. `markHostExecutionInterrupted` retains a partial-transfer checkpoint only when the complete proof tuple was persisted. Otherwise byte progress is reset rather than guessed. Replace has its own reversible transaction ledger and remains separate from disposable partial output.

Opening Home, Search or Network & Remote does not auto-connect every saved server. Global search excludes network/cloud roots unless explicitly opted into a scoped external search, preventing unexpected connection storms.

## Step 5 settings

Persisted network/transfer settings include connection timeout, retry count, Wi-Fi-only background transfers, metered-network warning, safe auto-resume preference, hidden-remote-file visibility, LAN discovery enable/disable, and additional insecure-protocol warnings. FTP and HTTP WebDAV remain visibly marked unencrypted regardless of the warning preference.
