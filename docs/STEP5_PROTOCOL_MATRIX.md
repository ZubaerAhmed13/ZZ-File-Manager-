# Step 5 Protocol Capability Matrix

Capability entries describe the implemented contract. **Conditional** means the provider/server must advertise or prove the capability at runtime. **No** means the app deliberately does not promise it.

| Provider / protocol | Browse / metadata | Stream read | Stream write | Create dir | Delete | Rename / move | Server-side copy | Safe Replace | Nonzero resume | Identity / revision proof | Security state |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Local filesystem | Yes | Yes | Yes | Yes | Yes | Yes | Provider-dependent | Yes where provider contract permits | Existing local behavior | Local metadata/reference | Existing Steps 1–4 model |
| SAF generic | Yes | Yes | Conditional | Conditional | Conditional | Conditional | No generic promise | Safe engine path only | No Step 5 generic nonzero seek promise | URI/document metadata | Persisted Android URI grant |
| SD card via SAF | Yes | Yes | Conditional | Conditional | Conditional | Conditional | No generic promise | Safe engine path only | No generic nonzero claim | Persisted tree + removable token where available | Explicit grant; reconnect checks saved identity |
| USB via SAF | Yes | Yes | Conditional | Conditional | Conditional | Conditional | No generic promise | Safe engine path only | No generic nonzero claim | Persisted tree + removable token where available | Removed/read-only/permission-lost states surfaced |
| SMB 2/3 | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes; provider replace path | Yes only after full live resume proof | Stable SMB identity + revision | SMB1 excluded; signing enabled |
| FTP | Yes | Yes | Yes | Yes | Yes | Yes in server namespace | No | Generic reversible path when supported | REST offset alone is insufficient; generic nonzero resume not promised without stage identity | Size/mtime revision | **Unencrypted** warning always visible |
| FTPS | Yes | Yes | Yes | Yes | Yes | Yes in server namespace | No | Generic reversible path when supported | Same conservative rule as FTP | Size/mtime revision | TLS endpoint verification + optional explicit leaf SHA-256 pin |
| SFTP | Yes | Yes | Yes | Yes | Yes | Yes | No | Generic reversible path when supported | Offset I/O exists, but nonzero resume requires full stage proof | File revision; no generic stable stage ID promise | Explicit SHA-256 host-key trust; changed key blocks |
| WebDAV HTTP/HTTPS | Conditional DAV support | Yes | Conditional PUT | Conditional MKCOL | Conditional DELETE | Conditional MOVE | Conditional COPY | Safe generic path only when capabilities permit | Ranged GET alone is insufficient | ETag/size/mtime where reported | HTTPS system TLS + optional pin; HTTP always unencrypted; redirects rejected |
| Cloud through Android DocumentsProvider | Yes through SAF | Yes | Conditional | Conditional | Conditional | Conditional | No generic promise | Safe engine path only | No generic nonzero promise | Provider-native URI/document ID | Android provider app + explicit URI grant |
| Direct cloud adapter/provider | Yes, paged | Yes | Yes through upload session | Yes | Yes | Native rename/move | Adapter-dependent | Explicit upload commit; provider contract | Supported only with persisted session/offset/revision proof | Native file ID + revisions | OAuth secret behind secure credential boundary; adapter must be registered |

## SMB dialect policy

The SMBJ client offers SMB 3.1.1, 3.0.2, 3.0, 2.1 and 2.0.2 only. SMB1 is not enabled.

## FTP/FTPS resume rule

Apache Commons Net REST support permits offset I/O on compatible servers, but REST support is not treated as process-death resume proof. The hidden staged object must also have stable identity and exact recorded length. Without that proof the transfer restarts rather than blindly appending.

## SFTP resume rule

SSHJ supports offset reads/writes. Byte seeking is necessary but insufficient. Nonzero process-death resume is not advertised unless the same staged object can be proven after recovery.

## WebDAV capability rule

Capabilities are discovered from OPTIONS/DAV/Allow and server behavior. Ranged GET is supported where applicable, but the provider does not globally advertise resume because portable offset-write semantics are not guaranteed by standard WebDAV PUT.

## Direct-cloud registration rule

`DirectCloudStorageProvider` is a real runtime storage provider over `DirectCloudAdapter`. `DirectCloudAccountManager` manages connect/refresh/disconnect/revoke/remove lifecycle. `RemoteProviderCoordinator` registers saved direct-cloud accounts only when a matching adapter exists and the account is not `AUTH_REQUIRED`.

The repository-safe `AppContainer` intentionally has empty vendor adapter/OAuth-driver registries. A distribution with legitimate provider registration supplies concrete vendor adapters/drivers; Step 5 does not embed placeholder Google/Microsoft client credentials.

## Repository interoperability status

Step 5 CI now starts disposable real servers for SMB2/3, FTP, FTPS and SFTP and runs the production protocol clients against them. FTPS uses a generated self-signed certificate with an exact connection-scoped pin. The existing local WebDAV integration exercises the production OkHttp/WebDAV client.

This repository coverage is distinct from external/hardware certification. Real NAS models, routers, removable devices, external server deployments and real vendor cloud accounts remain Step 7 items.
