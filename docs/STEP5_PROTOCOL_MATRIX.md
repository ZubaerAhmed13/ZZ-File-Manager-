# Step 5 Protocol Capability Matrix

Capability entries describe the current implementation, not an ideal protocol feature list. **Conditional** means the provider/server must advertise or prove the capability at runtime. **No** means the app deliberately does not promise that behavior.

| Provider / protocol | Browse / metadata | Stream read | Stream write | Create dir | Delete | Rename / move | Server-side copy | Safe Replace | Nonzero resume | Identity / revision proof | Security state |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Local filesystem | Yes | Yes | Yes | Yes | Yes | Yes | Provider-dependent | Yes where provider contract permits | Existing local behavior; not a Step 5 remote claim | Local metadata/reference | Existing Steps 1–4 model |
| SAF generic | Yes | Yes | Conditional write grant | Conditional provider flags | Conditional provider flags | Conditional `DocumentsContract` flags | No generic promise | Only when generic engine can stage/finalize safely | No Step 5 nonzero seek contract | URI/document metadata | Android persisted URI grant |
| SD card via SAF | Yes | Yes | Conditional write grant | Conditional | Conditional | Conditional | No generic promise | Same safe-staging rules | No nonzero resume claim | Persisted tree + removable storage token where available | Explicit platform grant; reconnect must match saved volume identity |
| USB via SAF | Yes | Yes | Conditional write grant | Conditional | Conditional | Conditional | No generic promise | Same safe-staging rules | No nonzero resume claim | Persisted tree + removable storage token where available | Explicit platform grant; removed/permission-lost/read-only states surfaced |
| SMB 2/3 | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes; atomic provider path advertised | Yes when live source/stage identity and exact staged length validate | Stable SMB identity + revision proof | SMB1 excluded; signing enabled; password/guest/anonymous per saved config |
| FTP | Yes | Yes | Yes | Yes | Yes | Yes within server namespace | No | Reversible generic rename/delete path when supported | REST byte I/O may exist, but nonzero resume is disabled unless full staged identity proof is available | Size/mtime revision; no stable staged identity promised | **Unencrypted** warning always visible |
| FTPS | Yes | Yes | Yes | Yes | Yes | Yes within server namespace | No | Reversible generic rename/delete path when supported | Same conservative rule as FTP | Size/mtime revision; no stable staged identity promised | TLS, endpoint checking, optional explicit leaf SHA-256 pin |
| SFTP | Yes | Yes | Yes | Yes | Yes | Yes | No | Reversible generic rename/delete path when supported | Seek I/O exists, but nonzero resume requires complete identity proof; current provider does not promise stable staged identity | File revision proof; stable staged identity not promised | SHA-256 SSH host key must be explicitly trusted; changed key blocks |
| WebDAV HTTP/HTTPS | Conditional DAV support discovered by OPTIONS/PROPFIND | Yes | Conditional PUT | Conditional MKCOL | Conditional DELETE | Conditional MOVE | Conditional COPY | Generic safe replacement only if runtime capabilities support safe path; no atomic replace promise | No global nonzero resume promise; ranged GET alone is not treated as sufficient | ETag/size/mtime revision where server reports it; no stable stage ID promise | HTTPS uses system TLS; optional pin; HTTP always labelled unencrypted; redirects rejected |
| Cloud through Android DocumentsProvider | Yes through SAF provider | Yes | Conditional provider grant | Conditional provider flags | Conditional | Conditional | No generic promise | Same engine staging/finalization rules | No generic nonzero resume promise | Provider-native content URI/document ID | Trust delegated to Android provider app + explicit URI grant |
| Direct cloud adapter boundary | Interface supports paging/stat/streaming and upload sessions | Yes | Yes through upload session | Yes | Yes | Rename/move | Provider implementation dependent | Explicit upload commit required | Architecture supports persisted session ID + Long offset + source/destination revision proof | Native file ID + revisions | OAuth secret belongs behind Keystore boundary; production vendor registration not embedded |

## SMB dialect policy

The SMBJ client explicitly offers only:

- SMB 3.1.1
- SMB 3.0.2
- SMB 3.0
- SMB 2.1
- SMB 2.0.2

SMB1 is not enabled.

## FTP/FTPS resume note

Apache Commons Net REST support allows offset I/O on compatible servers. Step 5 deliberately does **not** equate REST support with safe process-death resume. A hidden staged object must also have a stable live identity and exact recorded length. Because the FTP provider does not promise stable staged identity, it safely restarts rather than blindly appending.

## SFTP resume note

SSHJ supports offset reads/writes. The same rule applies: byte seeking is necessary but insufficient. Until a server/provider can prove the same staged object across recovery, nonzero process-death resume is not advertised by the generic engine.

## WebDAV capability note

WebDAV capabilities are discovered from the server's OPTIONS/DAV/Allow response. The client can issue a ranged GET for a caller that explicitly requests an offset, but the provider does not globally advertise resume because standard WebDAV PUT has no portable offset-write guarantee.

## Physical interoperability status

Protocol code, deterministic safety tests and a disposable local WebDAV integration server are included in Step 5 CI. Hardware/vendor interoperability is not represented as completed here; it is a Step 7 certification task.
