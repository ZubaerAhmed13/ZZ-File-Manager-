from pathlib import Path

# App dependency wiring.
path = Path("app/src/main/java/com/zz/filemanager/ZZFileManagerApp.kt")
text = path.read_text()
old = "RemoteLocationsViewModel.Factory(container.remoteConnectionService)"
new = "RemoteLocationsViewModel.Factory(container.remoteConnectionService, container.lanDiscovery)"
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)
old = "SettingsViewModel.Factory(container.storage, container.preferences)"
new = "SettingsViewModel.Factory(container.storage, container.preferences, container.remoteConnectionService)"
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)
path.write_text(text)

# LAN discovery presentation. Discovery is user initiated and a candidate is only converted into
# an editable connection draft after explicit Add.
path = Path("app/src/main/java/com/zz/filemanager/feature/remote/RemoteLocationsScreen.kt")
text = path.read_text()
anchor = '''            Text("Saved servers", style = MaterialTheme.typography.titleLarge)\n            Text(\n                "Credentials are stored separately using Android Keystore. Opening this screen does not auto-connect to saved servers.",\n                style = MaterialTheme.typography.bodySmall,\n            )\n'''
insert = '''            Text("LAN discovery", style = MaterialTheme.typography.titleLarge)\n            Text(\n                "DNS-SD/mDNS only. ZZ File Manager does not brute-force IP ranges, scan arbitrary ports, try credentials or create saved connections automatically.",\n                style = MaterialTheme.typography.bodySmall,\n            )\n            if (!state.lanDiscoveryEnabled) {\n                Text("LAN discovery is disabled in Settings.", style = MaterialTheme.typography.bodyMedium)\n            } else {\n                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    Button(onClick = { if (state.discovering) viewModel.stopDiscovery() else viewModel.startDiscovery() }) {\n                        Text(if (state.discovering) "Stop discovery" else "Discover LAN services")\n                    }\n                    if (state.discovered.isNotEmpty()) {\n                        TextButton(onClick = viewModel::clearDiscovery) { Text("Clear") }\n                    }\n                }\n                state.discovered.forEach { candidate ->\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {\n                            Text(candidate.serviceName, style = MaterialTheme.typography.titleMedium)\n                            Text(\n                                "${candidate.protocol.name} · ${candidate.hostName}" +\n                                    (candidate.address?.let { " · $it" } ?: "") +\n                                    ":${candidate.port}",\n                                style = MaterialTheme.typography.bodySmall,\n                            )\n                            TextButton(onClick = {\n                                editing = viewModel.connectionDraft(candidate)\n                                showForm = true\n                            }) { Text("Add / Connect") }\n                        }\n                    }\n                }\n            }\n\n            Text("Saved servers", style = MaterialTheme.typography.titleLarge)\n            Text(\n                "Credentials are stored separately using Android Keystore. Opening this screen does not auto-connect to saved servers.",\n                style = MaterialTheme.typography.bodySmall,\n            )\n'''
assert text.count(anchor) == 1, text.count(anchor)
text = text.replace(anchor, insert)
path.write_text(text)

# Clear stale resume identities in the second collision race path as well.
path = Path("app/src/main/java/com/zz/filemanager/core/operation/FileOperationEngine.kt")
text = path.read_text()
old = '''                    item = item.copy(\n                        state = OperationItemState.QUEUED,\n                        partialOutput = if (cleaned) null else outputRef,\n                        processedBytes = 0L,\n                    )\n                    operation = replaceItem(operation, item)\n                    var collision = collisionFor(operation, item, raced, finalName)\n'''
new = '''                    item = TransferResumeCoordinator.clearCheckpoint(\n                        item.copy(\n                            state = OperationItemState.QUEUED,\n                            partialOutput = if (cleaned) null else outputRef,\n                        ),\n                        keepPartial = !cleaned,\n                    )\n                    operation = replaceItem(operation, item)\n                    var collision = collisionFor(operation, item, raced, finalName)\n'''
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)
path.write_text(text)

print("Step 5 final wiring patch applied")
