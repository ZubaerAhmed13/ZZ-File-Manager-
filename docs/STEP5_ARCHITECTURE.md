# Step 5 Architecture — Removable, Network and Cloud Storage

## Scope

Step 5 extends the provider-neutral browser and transactional file-operation engine from Steps 1–4 to removable SAF roots, saved network locations, and a production-facing direct-cloud provider boundary. It does not begin Step 6. External hardware/vendor certification remains a Step 7 activity, while repository CI now performs real disposable SMB, FTP, FTPS, SFTP and WebDAV interoperability where feasible.

Approved Step 4 base: `01dab47f3c02e4667c80587fe1a17d06e1f65677`.

## Provider graph

`StorageRepository` remains the single browser/storage registry. Built-in providers are Local, SAF and MediaStore. Step 5 adds runtime external providers through `RemoteProviderCoordinator`.

Saved network and cloud account metadata can be reconstructed at startup without opening network sessions or decrypting secrets. Credentials are resolved only when a provider operation actually needs them.

A `FileReference` carries a stable logical `providerId` plus provider-native opaque identity. A `ScopedFileReference` also carries the authorized logical root and storage identity. Remote paths are normalized provider identifiers and are never converted to local `java.io.File` paths.

`StorageCapability` / `ProviderCapabilities` is the authoritative feature contract. UI and operation code must ask providers what they can truthfully support.

## Removable storage

SD cards and USB media use Android Storage Access Framework tree URIs. The user grants access through the platform picker and the app persists the URI grant. `StorageRepository.registerSafLocation` records the durable location kind as SD card, USB, cloud or generic SAF.

For removable media, a stable storage token derived from authorized volume identity is retained where Android exposes one. Reconnect refuses to silently map a saved SD/USB location to a different observed token. Availability distinguishes available, read-only, removed, permission-lost and unsupported states.

SAF operations stay URI-native through `DocumentsContract` / `DocumentFile`.

## Saved remote locations

`NetworkConnectionRepository` stores non-secret connection/account metadata. `AndroidKeystoreCredentialStore` stores encrypted secret payloads separately. `RemoteConnectionService` owns save/test/remove/disconnect and trust actions. `RemoteProviderCoordinator` turns saved entries into runtime providers.

Production network factories are:

- SMB through SMBJ with SMB 2.0.2–3.1.1 only;
- FTP/FTPS through Apache Commons Net;
- SFTP through SSHJ;
- WebDAV over HTTP/HTTPS through OkHttp.

Every remote stream owns its protocol session and closes it when the stream closes. Blocking protocol work is kept off the main thread.

## Incremental remote browsing

`BrowserStorage.listChildrenIncrementally` is the large-directory boundary. Remote providers emit bounded pages instead of requiring a complete directory result before the browser can render.

The browser consumes each page as it arrives. The certification suite includes a synthetic 100,001-entry remote directory that emits 256 entries, deliberately blocks further enumeration, verifies those 256 are already visible, and only then releases the remaining pages. This proves first-page rendering does not wait for full enumeration.

## LAN discovery

`LanDiscoveryService` uses Android `NsdManager` DNS-SD/mDNS discovery for known SMB, FTP, FTPS, SSH/SFTP and WebDAV service types. Discovery is explicit, can be disabled, retains at most 100 candidates, and never scans arbitrary address ranges, tries credentials, saves automatically or connects automatically.

## Direct cloud architecture

Two cloud paths exist:

1. **SAF/DocumentsProvider cloud** — cloud apps exposing Android DocumentsProvider are used through persisted SAF grants.
2. **Direct cloud provider boundary** — `DirectCloudAdapter`, `DirectCloudSession`, `CloudUploadSession` and `DirectCloudStorageProvider` support stable account identity, provider-native file IDs, paged listing, streaming reads/writes, native rename/move, upload-session identity, resume state and explicit commit/abort behavior.

`DirectCloudAccountManager` owns the local OAuth lifecycle: connect, refresh, local disconnect, provider-side revoke, and local account removal. Refresh tokens are stored only through the secure credential boundary and caller-owned token arrays are cleared after use where applicable.

`RemoteProviderCoordinator` registers a `DirectCloudStorageProvider` only when saved account metadata is valid, the account is not `AUTH_REQUIRED`, and a matching adapter is present in `DirectCloudAdapterRegistry`. `AppContainer` intentionally starts with empty vendor adapter/OAuth-driver registries; a product distribution that owns valid provider registration supplies its adapters/drivers. This repository therefore does not embed fake Google/Microsoft credentials or claim unconfigured vendor OAuth interoperability.

`InMemoryDirectCloudAdapter` is the deterministic repository implementation used to certify native IDs, paging, committed writes and large `Long` resume offsets.

## Transfer architecture

`FileOperationEngine` remains authoritative for copy/move/replace across local, SAF and remote/cloud providers. It uses bounded buffers and `Long` byte counters.

Streamed destinations use hidden `.zzpart-*` staging. The final visible name is not exposed until streaming and finalization succeed. Replace preserves the known-good destination until staged output is complete. MOVE removes its source only after finalization and destination proof.

## Verified resume

`TransferResumeCoordinator` accepts a nonzero restart offset only when persisted source revision, staged-object identity and `Long` offset can be revalidated against live provider state. The stage must still exist and its live length must exactly equal the recorded offset. Changed source revision rejects resume; invalid stage identity/length is cleaned/restarted when safe. Seek/range support alone is never treated as proof.

## Transfer policy execution

Step 5 settings are behavioral, not decorative:

- retry count controls retry execution;
- hidden-remote-file preference filters remote browser results;
- Wi-Fi-only background transfer is a hard execution gate when enabled;
- metered-network warning produces a visible warning without falsely blocking allowed transfers;
- auto-resume only requeues interrupted resumable COPY/MOVE work and never automatically replays destructive metadata operations such as DELETE.

## Lifecycle and recovery

Operation journals survive host/process interruption. Resume checkpoints are retained only when the complete proof tuple was persisted; otherwise progress is reset rather than guessed. Replace uses its reversible transaction ledger independently from disposable partial output.

Opening Home, Search or Network & Remote does not auto-connect every saved server. Global search excludes network/cloud roots unless explicitly scoped, preventing unexpected connection storms.
