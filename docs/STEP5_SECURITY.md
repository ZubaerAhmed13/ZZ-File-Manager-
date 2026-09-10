# Step 5 Security — Removable, Network and Cloud Storage

## Security objective

Step 5 treats every removable device, remote server and cloud provider as an external trust boundary. Correctness is defined primarily by preservation of the authoritative source and known-good destination, explicit server identity decisions, least-surprising network behavior, and avoiding secret persistence in plaintext.

## Credential storage

Connection metadata and secrets are stored separately. `NetworkConnectionRepository` persists only non-secret connection properties. Passwords, SFTP private keys, private-key passphrases and OAuth refresh-token references are handled through `AndroidKeystoreCredentialStore`.

The credential store generates a 256-bit AES key inside Android Keystore and stores only AES-GCM ciphertext plus IV in app-private preferences. The Keystore key is non-exportable. Caller-provided `CharArray`/`ByteArray` buffers are copied only where a platform/library boundary requires it and are cleared after use where the application owns the buffer.

## OAuth and cloud secrets

The direct-cloud boundary stores stable provider/account IDs separately from OAuth secret material. `CloudResumeToken` contains upload-session identity, offset and revision proof only; it must never contain access or refresh tokens. Production direct-provider refresh tokens belong behind the same Keystore-backed credential boundary.

A vendor direct-cloud production adapter is not embedded without provider registration credentials. This avoids shipping placeholder OAuth secrets or pretending an unregistered client is production-ready.

## SFTP host-key verification

SFTP never uses an accept-all host-key verifier. First contact is blocked until the server's SHA-256 host-key fingerprint is shown to the user for independent verification. Once trusted, the fingerprint is persisted with connection metadata. A different observed key on a later connection is classified as `SERVER_CHANGED` and the connection remains blocked until the user makes a new explicit trust decision.

`PinnedSha256HostKeyVerifier` is covered by the required `sftpHostKeyChangeBlocksConnection` certification test.

## TLS certificate validation

FTPS enables endpoint/hostname verification. System certificate validation remains the normal trust path. When certificate pinning is selected, the expected leaf certificate SHA-256 fingerprint must be present and a different certificate is rejected.

HTTPS WebDAV uses OkHttp/system TLS validation. Its optional pinning path delegates to the system X.509 trust manager first and then checks the configured leaf fingerprint. Redirect following is disabled so credentials are not silently forwarded to a different host or scheme.

`CertificatePinPolicy` is the shared explicit FTPS leaf-pin comparison and is covered by `ftpsCertificateChangeBlocksConnection`.

## Self-signed certificates

The application does not install a global trust-all TLS manager. A self-signed deployment must be explicitly configured through the per-connection pinned-certificate path after the user independently verifies the expected fingerprint. Trust decisions are scoped to the saved connection; there is no application-wide “ignore TLS errors” switch.

## Insecure FTP and HTTP WebDAV

Plain FTP and HTTP WebDAV are supported only as explicitly insecure transports. Their connection cards always display an **unencrypted** warning. The `warnOnInsecureProtocols` preference controls only additional warning behavior; it cannot make an insecure connection appear encrypted or trusted.

SMB is restricted to SMB2/SMB3 dialects by the SMBJ configuration. SMB1 is not offered to the negotiator.

## Remote path normalization

Remote names are not local paths. `RemotePath` canonicalizes separators into provider-internal POSIX-style identifiers, rejects NUL bytes, prevents `..` from escaping the logical root, and requires new leaf names to be exactly one path component. WebDAV creates URLs by adding encoded path segments through OkHttp rather than string-concatenating untrusted names into URLs.

The certification suite includes spaces, emoji, Bangla, German characters, combining Unicode, `#`, `%`, `?`, brackets, long names and explicit root-escape rejection.

## Resume proof

A recorded byte offset is not proof of sameness. `TransferResumeCoordinator` permits a nonzero resume only when the source provider can reproduce the persisted source revision, the destination can reproduce the staged-object identity, the staged object still exists, and its live size equals the persisted `Long` offset exactly.

Source revision change is a hard rejection. Staged-object mismatch or size mismatch causes safe cleanup/restart instead of append. Providers that cannot produce adequate proof fall back to restart-from-zero behavior.

## Remote overwrite safety

Streamed output uses hidden staging. The final user-visible filename is not exposed while data is incomplete. Replace keeps the existing destination untouched while new bytes are written and validated. For a non-atomic provider, the reversible replace ledger preserves a backup until commit verification is complete.

A MOVE never deletes its source merely because all input bytes were read. Source deletion occurs only after the committed destination exists and the engine verifies expected size and stable mutation state when that proof is available. Failure, cancellation, timeout, provider loss, removable-media removal or post-commit verification failure must not be promoted to success.

## Secret/log redaction

Application connection-state messages are deliberately generic and do not embed credentials. Passwords, private-key bytes, passphrases, OAuth tokens and Authorization headers must never be written to logs, operation journals, exception UI, analytics or documentation. Server endpoints and non-secret fingerprints may be logged only when needed for diagnosis; secret material remains excluded.

## LAN discovery limits

LAN discovery is user-initiated DNS-SD/mDNS through Android NSD. It does not brute-force subnets, enumerate arbitrary IP ranges, scan arbitrary ports, try usernames/passwords, or auto-create saved connections. Results are capped and shown as candidate metadata only. The user must explicitly choose **Add / Connect** before the normal connection editor is opened.

## Provider trust boundaries

Every provider controls what it can truthfully prove. Capabilities are not inferred from protocol names alone where behavior is server-dependent. WebDAV feature discovery is server driven; FTP resume is conditional on REST support; remote resume additionally requires identity proof even if seek operations exist.

SAF tree access is bounded by the persisted URI grant. Removable reconnection compares saved and observed volume identity when a stable token is available. A selected different device is not silently substituted for a missing saved removable volume.

## Residual risks and deferred physical certification

Network filesystems and cloud services can change state concurrently, lie about metadata, revoke permissions, or disappear between requests. Some protocols cannot supply a stable staged-object identity, so safe resume is intentionally disabled even if byte-range I/O exists. WebDAV server capability declarations may also be incomplete or inaccurate; unsupported features fail through provider errors rather than being simulated unsafely.

Real NAS interoperability, specific router/mDNS behavior, physical USB/SD removal timing, TLS deployments, SFTP servers, and real cloud accounts require physical/external infrastructure and are intentionally certified in Step 7. Step 5 CI certifies deterministic application behavior and disposable local protocol coverage without claiming those external systems were physically tested.
