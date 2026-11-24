package com.beatraxus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.addons.plugins.PluginRepositoryManager
import kotlinx.coroutines.launch

@Composable
fun PluginCenterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repositories by PluginRepositoryManager.repositories.collectAsStateWithLifecycle()
    val catalog by PluginRepositoryManager.catalog.collectAsStateWithLifecycle()
    val installed by PluginRepositoryManager.installed.collectAsStateWithLifecycle()
    var repositoryUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { PluginRepositoryManager.initialize(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PLUGIN CENTER", fontWeight = FontWeight.Black) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Add trusted repositories to discover providers. Repositories deliver JSON configuration only; executable plugin code is never downloaded.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub repository or HTTPS repository.json") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val result = PluginRepositoryManager.addRepository(repositoryUrl)
                            status = result.fold(
                                { "Added ${it.name}. ${it.plugins.size} compatible plugin entries found." },
                                { it.message ?: "Could not add repository." }
                            )
                            if (result.isSuccess) repositoryUrl = ""
                            busy = false
                        }
                    },
                    enabled = repositoryUrl.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator()
                    else Text("ADD REPOSITORY")
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                if (repositories.isNotEmpty()) {
                    Text("REPOSITORIES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    repositories.forEach { repository ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repository.name, fontWeight = FontWeight.SemiBold)
                                Text(repository.indexUrl, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        status = PluginRepositoryManager.refreshRepository(repository.id)
                                            .fold({ "Updated ${it.name}." }, { it.message ?: "Refresh failed." })
                                        busy = false
                                    }
                                },
                                enabled = !busy
                            ) { Text("REFRESH") }
                            TextButton(onClick = { PluginRepositoryManager.removeRepository(repository.id) }) {
                                Text("REMOVE")
                            }
                        }
                    }
                }

                if (catalog.isNotEmpty()) {
                    Text("AVAILABLE PROVIDERS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    catalog.forEach { plugin ->
                        val alreadyInstalled = installed.any { it.id == plugin.id }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${plugin.category.wireName.replace('_', ' ')} · v${plugin.version}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (plugin.description.isNotBlank()) {
                                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (alreadyInstalled) {
                                Text("INSTALLED", style = MaterialTheme.typography.labelSmall)
                            } else if (!plugin.supported) {
                                Text("NEEDS APP UPDATE", style = MaterialTheme.typography.labelSmall)
                            } else {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            status = PluginRepositoryManager.installPlugin(plugin.id)
                                                .fold({ "Installed ${it.name}." }, { it.message ?: "Installation failed." })
                                            busy = false
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("INSTALL") }
                            }
                        }
                    }
                }

                if (installed.isNotEmpty()) {
                    Text("INSTALLED PROVIDERS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    installed.forEach { plugin ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, fontWeight = FontWeight.SemiBold)
                                Text("${plugin.category.wireName.replace('_', ' ')} · v${plugin.version}", style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = plugin.enabled,
                                onCheckedChange = { enabled ->
                                    PluginRepositoryManager.setPluginEnabled(plugin.id, enabled)
                                        .onFailure { status = it.message }
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = {
                                PluginRepositoryManager.uninstallPlugin(plugin.id)
                                    .onFailure { status = it.message }
                            }) { Text("REMOVE") }
                        }
                    }
                }
                if (repositories.isEmpty() && catalog.isEmpty() && installed.isEmpty()) {
                    Text("No repositories added yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
        shape = RoundedCornerShape(24.dp)
    )
}
