# Step 5 Security — Removable, Network and Cloud Storage

## Security objective

Step 5 treats removable devices, remote servers and cloud providers as external trust boundaries. The primary invariants are preservation of authoritative data, explicit server identity decisions, bounded secret handling, and no plaintext credential persistence.

## Credential storage

`NetworkConnectionRepository` stores non-secret metadata. Passwords, SFTP private keys/passphrases and OAuth refresh tokens are handled through `AndroidKeystoreCredentialStore`.

The credential store uses a 256-bit AES key generated inside Android Keystore and AES-GCM encrypted payloads in app-private storage. The Keystore key is non-exportable. Caller-owned secret arrays are cleared after use where the application controls their lifetime.

Private-key import is explicitly bounded. The remote UI reads through `BoundedInputStream` with a sentinel byte beyond the configured maximum so oversized key material is rejected without unbounded allocation. `privateKeyImportReadStopsAtConfiguredSentinelLimit` is a mandatory JVM certification test.

## Direct-cloud OAuth security

Direct-cloud metadata stores stable provider/account identity separately from OAuth secrets. `CloudResumeToken` contains upload-session identity, offset and revision proof only; it never contains access or refresh tokens.

`DirectCloudAccountManager` requires a registered adapter before connect. Connect writes the refresh token through the secure credential store and clears the supplied `CharArray`. Refresh retrieves the secret only for the duration of the driver call, stores any replacement securely, and clears temporary arrays. Local disconnect removes the local refresh token and marks the account `AUTH_REQUIRED`. Revoke invokes the provider-specific driver before removing the local token and also leaves the account `AUTH_REQUIRED`.

The repository intentionally ships no vendor client secrets. `AppContainer` uses empty vendor adapter/OAuth-driver registries until a distribution with legitimate provider registration supplies them.

## SFTP host-key verification

SFTP never uses an accept-all verifier. First contact requires explicit SHA-256 host-key trust. A later different key is `SERVER_CHANGED` and remains blocked. The trust UI supports an explicit replacement decision rather than silently overwriting the saved pin.

`PinnedSha256HostKeyVerifier` is covered by the mandatory `sftpHostKeyChangeBlocksConnection` test, while disposable-server interoperability exercises first-contact pin acquisition and a subsequent pinned connection.

## TLS certificate validation and pinning

FTPS enables endpoint/hostname verification. HTTPS WebDAV uses the platform/system X.509 trust path. Optional connection-scoped leaf SHA-256 pinning is supported for FTPS and HTTPS WebDAV; pin replacement is an explicit per-connection decision.

There is no application-wide trust-all TLS manager. A self-signed deployment is accepted only through the explicit connection-scoped pin path after the expected fingerprint is independently verified. The disposable FTPS CI server uses a one-run self-signed certificate and exports its exact leaf SHA-256 pin to the production client test.

Redirect following is disabled for WebDAV so credentials are not silently forwarded to another endpoint.

## Insecure transports

Plain FTP and HTTP WebDAV are always represented as unencrypted. Warning preferences can add warnings but cannot make an insecure transport appear encrypted or trusted.

SMB negotiation is restricted to SMB2/SMB3 dialects; SMB1 is not offered.

## Remote path normalization

Remote names are provider identifiers, not local paths. `RemotePath` normalizes separators, rejects NUL bytes, prevents `..` from escaping the logical root, and requires new leaf names to be one path component. WebDAV URLs are built with encoded path segments rather than unsafe string concatenation.

Coverage includes spaces, emoji, Bangla, German characters, combining Unicode, `#`, `%`, `?`, brackets, long names and root-escape rejection.

## Resume proof

A byte offset alone is not proof of sameness. Nonzero resume requires live agreement with persisted source revision, staged-object identity and exact staged length. Changed source revision rejects resume. Stage mismatch/size mismatch triggers safe cleanup/restart instead of append. Providers unable to prove identity do not advertise unsafe resume.

## Remote overwrite and MOVE safety

Streamed output is written to hidden staging. The final user-visible filename is not exposed while incomplete. Replace retains the known-good destination until commit proof. Non-atomic replacement uses a reversible backup ledger.

MOVE never deletes its source merely because input streaming ended. Source deletion occurs only after the committed destination exists and expected proof succeeds. Cancellation, timeout, provider loss, removable-media removal or verification failure cannot be promoted to a successful MOVE.

## Transfer-policy safety

Wi-Fi-only background transfer is enforced before remote execution when enabled. Metered-network warning is advisory and visible; it does not silently convert an allowed transfer into success or failure. Auto-resume is deliberately limited to interrupted COPY/MOVE work with resume proof; DELETE and other destructive metadata operations are not automatically replayed.

## Secret/log redaction

Passwords, key bytes, passphrases, OAuth tokens and Authorization headers must not be written to operation journals, exception UI, analytics, documentation or logs. Generic connection-state messages avoid embedding credential material. Non-secret endpoints and fingerprints may be used for diagnostics where necessary.

## LAN discovery limits

LAN discovery is user-initiated DNS-SD/mDNS. It does not brute-force subnets, scan arbitrary ports, try credentials, auto-create saved connections or auto-connect results.

## Residual and deferred external risks

Repository CI now performs real disposable SMB, FTP, FTPS and SFTP sessions plus the local WebDAV production-client integration. These tests prove protocol-library wiring and important trust/operation behaviors, but they do not claim every NAS, router, certificate deployment or vendor cloud implementation behaves identically.

Real SD/USB removal timing, external NAS/router variants, external TLS/SFTP deployments, real vendor OAuth accounts and sustained multi-gigabyte physical-device performance remain Step 7 certification items.
