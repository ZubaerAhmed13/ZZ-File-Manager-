package com.zz.filemanager.core.remote

import java.util.UUID

enum class RemoteProtocol(val providerPrefix: String, val defaultPort: Int, val encryptedByDefault: Boolean) {
    SMB("smb", 445, true),
    FTP("ftp", 21, false),
    FTPS("ftps", 21, true),
    SFTP("sftp", 22, true),
    WEBDAV("webdav", 443, true),
    DIRECT_CLOUD("cloud", 443, true),
}

enum class RemoteAuthenticationType { NONE, GUEST, PASSWORD, PRIVATE_KEY, OAUTH }
enum class RemoteTlsMode { NONE, EXPLICIT, IMPLICIT, HTTPS }
enum class RemoteCertificatePolicy { SYSTEM, PINNED }

enum class RemoteConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTH_REQUIRED,
    AUTH_FAILED,
    HOST_UNREACHABLE,
    TIMEOUT,
    TLS_ERROR,
    CERTIFICATE_ERROR,
    PERMISSION_DENIED,
    READ_ONLY,
    PROTOCOL_ERROR,
    SERVER_CHANGED,
    OFFLINE,
    UNKNOWN,
}

data class NetworkConnection(
    val id: String = UUID.randomUUID().toString(),
    val protocol: RemoteProtocol,
    val displayName: String,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String? = null,
    val authenticationType: RemoteAuthenticationType = RemoteAuthenticationType.PASSWORD,
    val rootPath: String = "/",
    val share: String? = null,
    val domain: String? = null,
    val tlsMode: RemoteTlsMode = when (protocol) {
        RemoteProtocol.FTPS -> RemoteTlsMode.EXPLICIT
        RemoteProtocol.WEBDAV, RemoteProtocol.DIRECT_CLOUD -> RemoteTlsMode.HTTPS
        else -> RemoteTlsMode.NONE
    },
    val certificatePolicy: RemoteCertificatePolicy = RemoteCertificatePolicy.SYSTEM,
    val certificateSha256: String? = null,
    val sshHostKeySha256: String? = null,
    val privateKeyAlias: String? = null,
    val oauthAccountId: String? = null,
    val lastConnectedAt: Long? = null,
    val lastSuccessfulEndpoint: String? = null,
    val connectionState: RemoteConnectionState = RemoteConnectionState.DISCONNECTED,
    val readOnlyOverride: Boolean = false,
) {
    val providerId: String get() = "${protocol.providerPrefix}:$id"
    val storageId: String get() = providerId

    fun validationErrors(): List<String> = buildList {
        if (displayName.isBlank()) add("Display name is required.")
        if (host.isBlank() && protocol != RemoteProtocol.DIRECT_CLOUD) add("Host is required.")
        if (port !in 1..65535) add("Port must be between 1 and 65535.")
        if (protocol == RemoteProtocol.SMB && share.isNullOrBlank()) add("SMB share is required.")
        if (protocol == RemoteProtocol.SFTP && authenticationType == RemoteAuthenticationType.NONE) add("SFTP authentication is required.")
        if (protocol == RemoteProtocol.FTP && tlsMode != RemoteTlsMode.NONE) add("Plain FTP cannot use a TLS mode; choose FTPS instead.")
        if (protocol == RemoteProtocol.FTPS && tlsMode !in setOf(RemoteTlsMode.EXPLICIT, RemoteTlsMode.IMPLICIT)) add("FTPS requires explicit or implicit TLS.")
        if (protocol == RemoteProtocol.WEBDAV && tlsMode !in setOf(RemoteTlsMode.NONE, RemoteTlsMode.HTTPS)) add("WebDAV TLS mode is invalid.")
        if (certificatePolicy == RemoteCertificatePolicy.PINNED && certificateSha256.isNullOrBlank()) add("Pinned certificate fingerprint is required.")
    }
}

data class RemoteTransferSettings(
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 30_000,
    val writeTimeoutMillis: Int = 30_000,
    val retryCount: Int = 2,
    val wifiOnlyBackgroundTransfers: Boolean = false,
    val warnOnMeteredNetwork: Boolean = true,
    val autoResumeInterruptedTransfers: Boolean = true,
    val showHiddenRemoteFiles: Boolean = false,
    val lanDiscoveryEnabled: Boolean = true,
    // Disabling this preference may suppress additional prompts only. Plain FTP/HTTP state remains
    // permanently visible in the connection UI and is never represented as encrypted.
    val warnOnInsecureProtocols: Boolean = true,
) {
    init {
        require(connectTimeoutMillis in 1_000..120_000)
        require(readTimeoutMillis in 1_000..300_000)
        require(writeTimeoutMillis in 1_000..300_000)
        require(retryCount in 0..10)
    }
}
