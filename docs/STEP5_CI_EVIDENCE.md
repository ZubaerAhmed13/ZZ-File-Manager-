# Step 5 CI Evidence

This file is the compact certification contract for Step 5. It deliberately does not hard-code a run ID before the final reviewed commit exists; the authoritative result is the GitHub Actions run whose `head_sha` exactly matches that commit.

## Canonical gate

- Approved Step 4 base: `01dab47f3c02e4667c80587fe1a17d06e1f65677`
- Delivery branch: `step5/removable-network-cloud`
- Finalization branch: `hardening/step5-certification-finalize`
- Workflow: **Android Step 5 CI**
- Android instrumentation API: 35
- Build gate: debug build, JVM tests, lint, release build, androidTest build
- Device gate: API-35 `connectedDebugAndroidTest`
- Skip policy: zero skipped JVM tests and zero skipped instrumentation tests

## Mandatory JVM-name gate

The workflow requires 22 named Step 5 JVM tests covering transactional safety, resume proof, SFTP/FTPS identity changes, provider removal, queue timeout, real disposable SMB/FTP/FTPS/SFTP interoperability, bounded private-key input, Wi-Fi/metered policy execution, safe auto-resume, 100,001-entry incremental browsing and direct-cloud native-ID/paging/write/large-offset behavior.

A successful Gradle exit is insufficient if any mandatory name is absent from generated JUnit XML.

## Mandatory instrumentation-name gate

The workflow requires `directCloudOAuthLifecycleConnectRefreshDisconnectAndRevokeIsComplete` in the API-35 instrumentation XML and rejects any skipped instrumentation test.

## Disposable protocol evidence

CI starts:

- SMB2/3 on a disposable Samba container;
- SFTP on a disposable OpenSSH/SFTP container;
- FTP on a disposable pyftpdlib server;
- FTPS on a disposable pyftpdlib TLS server with a generated leaf certificate and exact SHA-256 pin.

All fixtures must pass readiness before the build/test phase. `STEP5_PROTOCOL_INTEROP_REQUIRED=1` prevents protocol integration from quietly degrading to skipped coverage.

The repository also retains the disposable local WebDAV integration test through the production OkHttp client.

## Exact-head rule

Step 5 is READY only when the final reviewed SHA receives a completed successful canonical workflow run. Any later production, test, workflow or documentation commit creates a new head that requires its own certification.
