package com.zz.filemanager.core.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent non-secret connection metadata. Passwords, tokens and private keys never enter this store. */
class NetworkConnectionRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<NetworkConnection> = decodeList(preferences.getString(KEY_CONNECTIONS, null))

    @Synchronized
    fun get(id: String): NetworkConnection? = list().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(connection: NetworkConnection) {
        val errors = connection.validationErrors()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val current = list()
        val next = buildList {
            add(connection)
            addAll(current.filterNot { it.id == connection.id })
        }
        preferences.edit().putString(KEY_CONNECTIONS, encodeList(next)).commit()
    }

    @Synchronized
    fun remove(id: String) {
        preferences.edit().putString(KEY_CONNECTIONS, encodeList(list().filterNot { it.id == id })).commit()
    }

    @Synchronized
    fun updateState(id: String, state: RemoteConnectionState, nowMillis: Long = System.currentTimeMillis(), endpoint: String? = null) {
        val connection = get(id) ?: return
        upsert(
            connection.copy(
                connectionState = state,
                lastConnectedAt = if (state == RemoteConnectionState.CONNECTED) nowMillis else connection.lastConnectedAt,
                lastSuccessfulEndpoint = if (state == RemoteConnectionState.CONNECTED) endpoint ?: connection.lastSuccessfulEndpoint else connection.lastSuccessfulEndpoint,
            ),
        )
    }

    @Synchronized
    fun settings(): RemoteTransferSettings {
        val raw = preferences.getString(KEY_SETTINGS, null) ?: return RemoteTransferSettings()
        return runCatching {
            val json = JSONObject(raw)
            RemoteTransferSettings(
                connectTimeoutMillis = json.optInt("connectTimeoutMillis", 15_000),
                readTimeoutMillis = json.optInt("readTimeoutMillis", 30_000),
                writeTimeoutMillis = json.optInt("writeTimeoutMillis", 30_000),
                retryCount = json.optInt("retryCount", 2),
                wifiOnlyBackgroundTransfers = json.optBoolean("wifiOnlyBackgroundTransfers", false),
                warnOnMeteredNetwork = json.optBoolean("warnOnMeteredNetwork", true),
                autoResumeInterruptedTransfers = json.optBoolean("autoResumeInterruptedTransfers", true),
                showHiddenRemoteFiles = json.optBoolean("showHiddenRemoteFiles", false),
                lanDiscoveryEnabled = json.optBoolean("lanDiscoveryEnabled", true),
                warnOnInsecureProtocols = json.optBoolean("warnOnInsecureProtocols", true),
            )
        }.getOrDefault(RemoteTransferSettings())
    }

    @Synchronized
    fun setSettings(value: RemoteTransferSettings) {
        val json = JSONObject().apply {
            put("connectTimeoutMillis", value.connectTimeoutMillis)
            put("readTimeoutMillis", value.readTimeoutMillis)
            put("writeTimeoutMillis", value.writeTimeoutMillis)
            put("retryCount", value.retryCount)
            put("wifiOnlyBackgroundTransfers", value.wifiOnlyBackgroundTransfers)
            put("warnOnMeteredNetwork", value.warnOnMeteredNetwork)
            put("autoResumeInterruptedTransfers", value.autoResumeInterruptedTransfers)
            put("showHiddenRemoteFiles", value.showHiddenRemoteFiles)
            put("lanDiscoveryEnabled", value.lanDiscoveryEnabled)
            put("warnOnInsecureProtocols", value.warnOnInsecureProtocols)
        }
        preferences.edit().putString(KEY_SETTINGS, json.toString()).commit()
    }

    private fun encodeList(connections: List<NetworkConnection>): String = JSONArray().apply {
        connections.forEach { connection -> put(toJson(connection)) }
    }.toString()

    private fun decodeList(raw: String?): List<NetworkConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> fromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun toJson(value: NetworkConnection) = JSONObject().apply {
        put("id", value.id)
        put("protocol", value.protocol.name)
        put("displayName", value.displayName)
        put("host", value.host)
        put("port", value.port)
        putNullable("username", value.username)
        put("authenticationType", value.authenticationType.name)
        put("rootPath", value.rootPath)
        putNullable("share", value.share)
        putNullable("domain", value.domain)
        put("tlsMode", value.tlsMode.name)
        put("certificatePolicy", value.certificatePolicy.name)
        putNullable("certificateSha256", value.certificateSha256)
        putNullable("sshHostKeySha256", value.sshHostKeySha256)
        putNullable("privateKeyAlias", value.privateKeyAlias)
        putNullable("oauthAccountId", value.oauthAccountId)
        putNullable("lastConnectedAt", value.lastConnectedAt)
        putNullable("lastSuccessfulEndpoint", value.lastSuccessfulEndpoint)
        put("connectionState", value.connectionState.name)
        put("readOnlyOverride", value.readOnlyOverride)
    }

    private fun fromJson(json: JSONObject) = NetworkConnection(
        id = json.getString("id"),
        protocol = RemoteProtocol.valueOf(json.getString("protocol")),
        displayName = json.getString("displayName"),
        host = json.optString("host", ""),
        port = json.getInt("port"),
        username = json.stringOrNull("username"),
        authenticationType = json.stringOrNull("authenticationType")?.let(RemoteAuthenticationType::valueOf) ?: RemoteAuthenticationType.PASSWORD,
        rootPath = json.optString("rootPath", "/"),
        share = json.stringOrNull("share"),
        domain = json.stringOrNull("domain"),
        tlsMode = json.stringOrNull("tlsMode")?.let(RemoteTlsMode::valueOf) ?: RemoteTlsMode.NONE,
        certificatePolicy = json.stringOrNull("certificatePolicy")?.let(RemoteCertificatePolicy::valueOf) ?: RemoteCertificatePolicy.SYSTEM,
        certificateSha256 = json.stringOrNull("certificateSha256"),
        sshHostKeySha256 = json.stringOrNull("sshHostKeySha256"),
        privateKeyAlias = json.stringOrNull("privateKeyAlias"),
        oauthAccountId = json.stringOrNull("oauthAccountId"),
        lastConnectedAt = json.longOrNull("lastConnectedAt"),
        lastSuccessfulEndpoint = json.stringOrNull("lastSuccessfulEndpoint"),
        connectionState = json.stringOrNull("connectionState")?.let(RemoteConnectionState::valueOf) ?: RemoteConnectionState.DISCONNECTED,
        readOnlyOverride = json.optBoolean("readOnlyOverride", false),
    )

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

    companion object {
        private const val PREFS_NAME = "zz_step5_network_connections"
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_SETTINGS = "settings"
    }
}

class NetworkConnectionManager(
    private val repository: NetworkConnectionRepository,
    private val credentials: SecureCredentialStore,
) {
    fun save(connection: NetworkConnection, password: CharArray? = null, privateKey: ByteArray? = null, privateKeyPassphrase: CharArray? = null) {
        repository.upsert(connection)
        password?.let { credentials.putChars(AndroidKeystoreCredentialStore.passwordReference(connection.id), it) }
        privateKey?.let { credentials.putBytes(AndroidKeystoreCredentialStore.privateKeyReference(connection.id), it) }
        privateKeyPassphrase?.let { credentials.putChars(AndroidKeystoreCredentialStore.privateKeyPassphraseReference(connection.id), it) }
    }

    fun remove(connectionId: String) {
        repository.remove(connectionId)
        credentials.remove(AndroidKeystoreCredentialStore.passwordReference(connectionId))
        credentials.remove(AndroidKeystoreCredentialStore.privateKeyReference(connectionId))
        credentials.remove(AndroidKeystoreCredentialStore.privateKeyPassphraseReference(connectionId))
    }
}
