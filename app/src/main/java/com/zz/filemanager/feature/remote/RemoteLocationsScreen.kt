@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zz.filemanager.feature.remote

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.remote.ConnectionTestResult
import com.zz.filemanager.core.remote.NetworkConnection
import com.zz.filemanager.core.remote.RemoteAuthenticationType
import com.zz.filemanager.core.remote.RemoteCertificatePolicy
import com.zz.filemanager.core.remote.RemoteConnectionState
import com.zz.filemanager.core.remote.RemoteProtocol
import com.zz.filemanager.core.remote.RemoteTlsMode
import java.net.URI

@Composable
fun RemoteLocationsScreen(
    viewModel: RemoteLocationsViewModel,
    onBack: () -> Unit,
    onOpenLocation: (BrowserLocation) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<NetworkConnection?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var importedPrivateKey by remember { mutableStateOf<ByteArray?>(null) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        importedPrivateKey?.fill(0)
        importedPrivateKey = uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val bytes = input.readBytes(MAX_PRIVATE_KEY_BYTES + 1)
                    require(bytes.size <= MAX_PRIVATE_KEY_BYTES) { "Private key file is too large" }
                    bytes
                }
            }.getOrNull()
        }
    }

    val feedback = state.feedback
    if (feedback != null) {
        when (val result = feedback.result) {
            is ConnectionTestResult.HostKeyTrustRequired -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text("Trust SFTP server key?") },
                text = {
                    Column {
                        Text("Verify this SHA-256 fingerprint with the server administrator before trusting it.")
                        Spacer(Modifier.height(12.dp))
                        Text(result.fingerprint, style = MaterialTheme.typography.bodyMedium)
                        result.algorithm?.let { Text("Algorithm: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.trustHostKeyAndRetest(feedback.connectionId, result.fingerprint) }) {
                        Text("Trust & test")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::clearFeedback) { Text("Cancel") } },
            )

            is ConnectionTestResult.ServerIdentityChanged -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text("Server identity changed") },
                text = {
                    Column {
                        Text("Connection is blocked. Independently verify the new fingerprint before replacing the saved key.")
                        Spacer(Modifier.height(8.dp))
                        result.expected?.let { Text("Expected: $it") }
                        result.observed?.let { Text("Observed: $it") }
                    }
                },
                confirmButton = {
                    val observed = result.observed
                    if (observed != null) {
                        TextButton(onClick = { viewModel.replaceTrustedHostKeyAndRetest(feedback.connectionId, observed) }) {
                            Text("Replace trusted key & test")
                        }
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::clearFeedback) { Text("Cancel") } },
            )

            is ConnectionTestResult.CertificateTrustRequired -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text("Trust server certificate?") },
                text = {
                    Column {
                        Text("The system trust store did not accept this certificate. Independently verify its SHA-256 fingerprint before pinning it to this saved connection.")
                        Spacer(Modifier.height(8.dp))
                        Text("Observed: ${result.observed}", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.trustCertificateAndRetest(feedback.connectionId, result.observed) }) {
                        Text("Trust certificate & test")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::clearFeedback) { Text("Cancel") } },
            )

            is ConnectionTestResult.CertificateIdentityChanged -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text("Server certificate changed") },
                text = {
                    Column {
                        Text("Connection is blocked because the pinned certificate changed. Independently verify the new certificate before replacing the pin.")
                        Spacer(Modifier.height(8.dp))
                        Text("Expected: ${result.expected}")
                        Text("Observed: ${result.observed}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.replaceTrustedCertificateAndRetest(feedback.connectionId, result.observed) }) {
                        Text("Replace certificate pin & test")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::clearFeedback) { Text("Cancel") } },
            )

            ConnectionTestResult.Success -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text("Connection successful") },
                text = { Text("The server authenticated and the configured root was verified.") },
                confirmButton = { TextButton(onClick = viewModel::clearFeedback) { Text("OK") } },
            )

            is ConnectionTestResult.Failure -> AlertDialog(
                onDismissRequest = viewModel::clearFeedback,
                title = { Text(result.state.displayLabel()) },
                text = { Text(result.message) },
                confirmButton = { TextButton(onClick = viewModel::clearFeedback) { Text("OK") } },
            )
        }
    }

    if (showForm) {
        ConnectionEditorDialog(
            existing = editing,
            importedPrivateKey = importedPrivateKey,
            onPickPrivateKey = { keyPicker.launch(arrayOf("application/x-pem-file", "application/pkcs8", "text/plain", "*/*")) },
            onDismiss = {
                importedPrivateKey?.fill(0)
                importedPrivateKey = null
                editing = null
                showForm = false
            },
            onSave = { connection, password, key, passphrase ->
                viewModel.save(connection, password, key, passphrase)
                importedPrivateKey = null
                editing = null
                showForm = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network & Remote") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            Button(onClick = { editing = null; showForm = true }) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.padding(3.dp))
                Text("Add server")
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LAN discovery", style = MaterialTheme.typography.titleLarge)
            Text(
                "DNS-SD/mDNS only. ZZ File Manager does not brute-force IP ranges, scan arbitrary ports, try credentials or create saved connections automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.lanDiscoveryEnabled) {
                Text("LAN discovery is disabled in Settings.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (state.discovering) viewModel.stopDiscovery() else viewModel.startDiscovery() }) {
                        Text(if (state.discovering) "Stop discovery" else "Discover LAN services")
                    }
                    if (state.discovered.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearDiscovery) { Text("Clear") }
                    }
                }
                state.discovered.forEach { candidate ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(candidate.serviceName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${candidate.protocol.name} · ${candidate.hostName}" +
                                    (candidate.address?.let { " · $it" } ?: "") +
                                    ":${candidate.port}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                editing = viewModel.connectionDraft(candidate)
                                showForm = true
                            }) { Text("Add / Connect") }
                        }
                    }
                }
            }

            Text("Saved servers", style = MaterialTheme.typography.titleLarge)
            Text(
                "Credentials are stored separately using Android Keystore. Opening this screen does not auto-connect to saved servers.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.connections.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Default.Lan, null)
                        Text("No saved servers", style = MaterialTheme.typography.titleMedium)
                        Text("Add SMB, FTP/FTPS, SFTP or WebDAV. Cloud document providers can also be added from Storage using Android's picker.")
                    }
                }
            }
            state.connections.forEach { connection ->
                RemoteConnectionCard(
                    connection = connection,
                    busy = state.busyConnectionId == connection.id,
                    onOpen = { viewModel.rootFor(connection.id)?.let(onOpenLocation) },
                    onTest = { viewModel.test(connection.id) },
                    onEdit = { editing = connection; showForm = true },
                    onDisconnect = { viewModel.disconnect(connection.id) },
                    onRemove = { viewModel.remove(connection.id) },
                )
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun RemoteConnectionCard(
    connection: NetworkConnection,
    busy: Boolean,
    onOpen: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (connection.protocol == RemoteProtocol.DIRECT_CLOUD) Icons.Default.Cloud else Icons.Default.Lan, null)
                Spacer(Modifier.padding(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${connection.protocol.name} · ${connection.host}:${connection.port}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Remove") }
            }
            Text("Status: ${connection.connectionState.displayLabel()}")
            connection.lastConnectedAt?.let { Text("Last connected: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall) }
            if (connection.protocol == RemoteProtocol.FTP) {
                Text("FTP is not encrypted.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (connection.protocol == RemoteProtocol.WEBDAV && connection.tlsMode == RemoteTlsMode.NONE) {
                Text("HTTP WebDAV is not encrypted.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            connection.sshHostKeySha256?.let { Text("Trusted host key: $it", style = MaterialTheme.typography.bodySmall) }
            if (connection.certificatePolicy == RemoteCertificatePolicy.PINNED) {
                connection.certificateSha256?.let { Text("Pinned certificate: $it", style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = !busy) { Text("Open") }
                TextButton(onClick = onTest, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Test")
                }
                if (connection.connectionState == RemoteConnectionState.CONNECTED) {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionEditorDialog(
    existing: NetworkConnection?,
    importedPrivateKey: ByteArray?,
    onPickPrivateKey: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (NetworkConnection, CharArray?, ByteArray?, CharArray?) -> Unit,
) {
    var protocol by remember(existing?.id) { mutableStateOf(existing?.protocol ?: RemoteProtocol.SMB) }
    var displayName by remember(existing?.id) { mutableStateOf(existing?.displayName.orEmpty()) }
    var host by remember(existing?.id) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(existing?.id) { mutableStateOf((existing?.port ?: protocol.defaultPort).toString()) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing?.id) { mutableStateOf("") }
    var showPassword by remember(existing?.id) { mutableStateOf(false) }
    var rootPath by remember(existing?.id) { mutableStateOf(existing?.rootPath ?: "/") }
    var share by remember(existing?.id) { mutableStateOf(existing?.share.orEmpty()) }
    var domain by remember(existing?.id) { mutableStateOf(existing?.domain.orEmpty()) }
    var guest by remember(existing?.id) { mutableStateOf(existing?.authenticationType == RemoteAuthenticationType.GUEST) }
    var usePrivateKey by remember(existing?.id) { mutableStateOf(existing?.authenticationType == RemoteAuthenticationType.PRIVATE_KEY) }
    var keyPassphrase by remember(existing?.id) { mutableStateOf("") }
    var implicitFtps by remember(existing?.id) { mutableStateOf(existing?.tlsMode == RemoteTlsMode.IMPLICIT) }
    var certificatePolicy by remember(existing?.id) { mutableStateOf(existing?.certificatePolicy ?: RemoteCertificatePolicy.SYSTEM) }
    var certificateSha256 by remember(existing?.id) { mutableStateOf(existing?.certificateSha256.orEmpty()) }
    var webDavUrl by remember(existing?.id) {
        mutableStateOf(
            if (existing?.protocol == RemoteProtocol.WEBDAV) {
                val scheme = if (existing.tlsMode == RemoteTlsMode.HTTPS) "https" else "http"
                "$scheme://${existing.host}:${existing.port}${existing.rootPath}"
            } else "https://"
        )
    }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }

    fun changeProtocol(next: RemoteProtocol) {
        protocol = next
        port = next.defaultPort.toString()
        guest = false
        usePrivateKey = false
        if (next == RemoteProtocol.WEBDAV && !webDavUrl.startsWith("http")) webDavUrl = "https://"
        if (next !in setOf(RemoteProtocol.FTPS, RemoteProtocol.WEBDAV)) {
            certificatePolicy = RemoteCertificatePolicy.SYSTEM
            certificateSha256 = ""
        }
    }

    val tlsCertificateCapable = protocol == RemoteProtocol.FTPS ||
        (protocol == RemoteProtocol.WEBDAV && !webDavUrl.startsWith("http://", ignoreCase = true))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protocol", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(RemoteProtocol.SMB, RemoteProtocol.FTP, RemoteProtocol.FTPS).forEach { p ->
                        TextButton(onClick = { changeProtocol(p) }, enabled = protocol != p) { Text(p.name) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(RemoteProtocol.SFTP, RemoteProtocol.WEBDAV).forEach { p ->
                        TextButton(onClick = { changeProtocol(p) }, enabled = protocol != p) { Text(p.name) }
                    }
                }
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                if (protocol == RemoteProtocol.WEBDAV) {
                    OutlinedTextField(webDavUrl, { webDavUrl = it }, label = { Text("WebDAV URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (webDavUrl.startsWith("http://", ignoreCase = true)) {
                        Text("HTTP WebDAV is not encrypted.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(rootPath, { rootPath = it }, label = { Text("Initial path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }

                if (protocol == RemoteProtocol.SMB) {
                    OutlinedTextField(share, { share = it }, label = { Text("Share") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(domain, { domain = it }, label = { Text("Domain / workgroup") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(guest, { guest = it }); Spacer(Modifier.padding(4.dp)); Text("Guest login") }
                }
                if (protocol == RemoteProtocol.FTPS) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(implicitFtps, { implicitFtps = it }); Spacer(Modifier.padding(4.dp)); Text(if (implicitFtps) "Implicit FTPS" else "Explicit FTPS") }
                }
                if (protocol == RemoteProtocol.FTP) {
                    Text("FTP is not encrypted. Credentials and file data can be exposed on the network.", color = MaterialTheme.colorScheme.error)
                }

                if (tlsCertificateCapable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = certificatePolicy == RemoteCertificatePolicy.PINNED,
                            onCheckedChange = { checked ->
                                certificatePolicy = if (checked) RemoteCertificatePolicy.PINNED else RemoteCertificatePolicy.SYSTEM
                                if (!checked) certificateSha256 = ""
                            },
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(if (certificatePolicy == RemoteCertificatePolicy.PINNED) "Pinned certificate trust" else "System certificate trust")
                    }
                    if (certificatePolicy == RemoteCertificatePolicy.PINNED) {
                        Text(
                            "Pin a SHA-256 certificate only after independently verifying the fingerprint. A test failure can also show the observed fingerprint for explicit trust/replacement.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            certificateSha256,
                            { certificateSha256 = it.trim() },
                            label = { Text("Certificate SHA-256") },
                            placeholder = { Text("SHA256:…") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                if (!guest) {
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                if (protocol == RemoteProtocol.SFTP) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(usePrivateKey, { usePrivateKey = it }); Spacer(Modifier.padding(4.dp)); Text("Private key authentication") }
                    if (usePrivateKey) {
                        Button(onClick = onPickPrivateKey) { Icon(Icons.Default.Key, null); Spacer(Modifier.padding(3.dp)); Text(if (importedPrivateKey == null) "Choose private key" else "Private key selected") }
                        OutlinedTextField(
                            keyPassphrase,
                            { keyPassphrase = it },
                            label = { Text(if (existing == null) "Key passphrase (optional)" else "Replace key passphrase (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    existing?.sshHostKeySha256?.let { Text("Trusted host key: $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (!usePrivateKey && !guest) {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text(if (existing == null) "Password" else "Replace saved password") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (existing != null) Text("Leave blank to keep the stored password.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    var resolvedHost = host
                    var resolvedPort = port.toIntOrNull() ?: protocol.defaultPort
                    var resolvedRoot = rootPath.ifBlank { "/" }
                    var tlsMode = when (protocol) {
                        RemoteProtocol.FTPS -> if (implicitFtps) RemoteTlsMode.IMPLICIT else RemoteTlsMode.EXPLICIT
                        RemoteProtocol.WEBDAV -> RemoteTlsMode.HTTPS
                        else -> RemoteTlsMode.NONE
                    }
                    if (protocol == RemoteProtocol.WEBDAV) {
                        val uri = URI(webDavUrl)
                        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "WebDAV URL must start with http:// or https://" }
                        resolvedHost = uri.host ?: throw IllegalArgumentException("WebDAV URL needs a host")
                        tlsMode = if (uri.scheme.equals("https", true)) RemoteTlsMode.HTTPS else RemoteTlsMode.NONE
                        resolvedPort = if (uri.port > 0) uri.port else if (tlsMode == RemoteTlsMode.HTTPS) 443 else 80
                        resolvedRoot = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
                    }
                    val auth = when {
                        guest -> RemoteAuthenticationType.GUEST
                        protocol == RemoteProtocol.SFTP && usePrivateKey -> RemoteAuthenticationType.PRIVATE_KEY
                        else -> RemoteAuthenticationType.PASSWORD
                    }
                    val resolvedCertificatePolicy = if (
                        protocol == RemoteProtocol.FTPS || (protocol == RemoteProtocol.WEBDAV && tlsMode == RemoteTlsMode.HTTPS)
                    ) certificatePolicy else RemoteCertificatePolicy.SYSTEM
                    val resolvedCertificateSha256 = if (resolvedCertificatePolicy == RemoteCertificatePolicy.PINNED) {
                        certificateSha256.trim().also { value -> require(value.isNotEmpty()) { "Enter the verified certificate SHA-256 fingerprint." } }
                    } else null

                    val connection = NetworkConnection(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        protocol = protocol,
                        displayName = displayName.trim(),
                        host = resolvedHost.trim(),
                        port = resolvedPort,
                        username = username.trim().takeIf { it.isNotEmpty() },
                        authenticationType = auth,
                        rootPath = resolvedRoot,
                        share = share.trim().takeIf { it.isNotEmpty() },
                        domain = domain.trim().takeIf { it.isNotEmpty() },
                        tlsMode = tlsMode,
                        certificatePolicy = resolvedCertificatePolicy,
                        certificateSha256 = resolvedCertificateSha256,
                        sshHostKeySha256 = existing?.sshHostKeySha256,
                        privateKeyAlias = if (auth == RemoteAuthenticationType.PRIVATE_KEY) "keystore:${existing?.id ?: "new"}" else null,
                        lastConnectedAt = existing?.lastConnectedAt,
                        lastSuccessfulEndpoint = existing?.lastSuccessfulEndpoint,
                        connectionState = RemoteConnectionState.DISCONNECTED,
                        readOnlyOverride = existing?.readOnlyOverride ?: false,
                    )
                    val validation = connection.validationErrors()
                    require(validation.isEmpty()) { validation.joinToString(" ") }
                    if (auth == RemoteAuthenticationType.PRIVATE_KEY && existing == null) require(importedPrivateKey != null) { "Choose a private key." }
                    val passwordChars = password.takeIf { it.isNotEmpty() }?.toCharArray()
                    val passphraseChars = keyPassphrase.takeIf { it.isNotEmpty() }?.toCharArray()
                    onSave(connection, passwordChars, importedPrivateKey, passphraseChars)
                } catch (t: Throwable) {
                    error = t.message ?: "Invalid connection settings."
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun RemoteConnectionState.displayLabel(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private const val MAX_PRIVATE_KEY_BYTES = 1024 * 1024
