# Step 6 security

- Only launcher `MainActivity` is exported. Tool/file activities, service, receiver, and FileProvider are private.
- Backups are disabled so encrypted credential records and keystore-bound ciphertext are not copied into inappropriate restores.
- No global cleartext opt-in or trust-all TLS exists. FTP/HTTP WebDAV remain explicit visibly insecure choices; FTPS/HTTPS/SFTP and pinning remain available.
- Credential editing alone sets `FLAG_SECURE`; ordinary browsing stays screenshot-capable. Password/passphrase fields are obscured.
- `MANAGE_EXTERNAL_STORAGE` remains an intentionally approved file-manager capability; SAF/MediaStore remain preferred when broad access is absent.

FileProvider grants are per-intent, temporary, and read-only for sharing/viewing. Its broad external declaration is required to share a specifically selected file from any approved local root; the provider itself cannot be queried externally and directory URIs are not granted.

Credentials remain in the Step 5 encrypted store. `SafeErrorMessage` redacts credential fields, authorization/bearer data, and credentials inside URLs, truncates output, and maps common failures. Diagnostics exposes no secrets. Step 5 TLS certificate and SSH host-key change blocking, SMB2/3 policy, OAuth lifecycle, and cloud ID namespacing remain mandatory.

Archive extraction continues through normalized paths and owned staging. Property tests cover traversal, mixed separators, absolute/drive/UNC forms, NULs, and 5,000 safe collision names. APK handling inspects metadata/signatures without executing bytecode and delegates install to Android. Remote move still deletes only after commit proof; resume validates identity. Release CI runs minification, lint, manifest/source security tests, and all Step 4/5 transaction/protocol regressions.

