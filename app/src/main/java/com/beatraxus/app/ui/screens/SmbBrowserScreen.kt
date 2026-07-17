package com.beatraxus.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.model.PlayerUiState
import com.beatraxus.app.repository.SmbServer
import com.beatraxus.app.util.NetworkUtils
import com.beatraxus.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@Composable
fun SmbBrowserScreen(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    var selectedServer by rememberSaveable { mutableStateOf<SmbServer?>(null) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<com.beatraxus.app.network.SmbEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedServer, currentPath) {
        val server = selectedServer
        if (server != null) {
            isLoading = true
            if (viewModel.connectSmb(server)) {
                entries = viewModel.listSmbFolder(currentPath)
            }
            isLoading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selectedServer == null) {
            // Server List
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMB / NAS Servers", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, null, tint = Color.Cyan)
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(uiState.smbServers) { server ->
                    ListItem(
                        modifier = Modifier.clickable { selectedServer = server },
                        headlineContent = { Text(server.displayName, color = Color.White) },
                        supportingContent = { Text("${server.host}/${server.shareName}", color = Color.White.copy(0.6f)) },
                        leadingContent = { Icon(Icons.Rounded.Storage, null, tint = Color.Cyan) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeSmbServer(server.id) }) {
                                Icon(Icons.Rounded.Delete, null, tint = Color.Red.copy(0.7f))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        } else {
            // Folder Browser
            val server = selectedServer!!
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { 
                    if (currentPath.isEmpty()) {
                        selectedServer = null
                    } else {
                        currentPath = currentPath.substringBeforeLast("/", "").removePrefix("/")
                    }
                }) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(server.displayName, fontSize = 14.sp, color = Color.Cyan)
                    Text("/$currentPath", fontSize = 12.sp, color = Color.White.copy(0.7f), maxLines = 1)
                }
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Cyan)
                    Spacer(Modifier.width(8.dp))
                }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                items(entries) { entry ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (entry.isDirectory) {
                                currentPath = entry.fullPath
                            } else {
                                viewModel.playSmbFile(server, entry)
                            }
                        },
                        headlineContent = { Text(entry.name, color = Color.White) },
                        supportingContent = { 
                            if (!entry.isDirectory) {
                                Text(NetworkUtils.formatFileSize(entry.size), color = Color.White.copy(0.6f))
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.MusicNote,
                                null,
                                tint = if (entry.isDirectory) Color.Cyan else Color.White.copy(0.8f)
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        SmbAddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { server ->
                viewModel.addSmbServer(server)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun SmbAddServerDialog(onDismiss: () -> Unit, onAdd: (SmbServer) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var shareName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SMB Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display Name") })
                TextField(value = host, onValueChange = { host = it }, label = { Text("Host (IP or Name)") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = shareName, onValueChange = { shareName = it }, label = { Text("Share Name") })
                TextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                TextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
                TextField(value = domain, onValueChange = { domain = it }, label = { Text("Domain (Optional)") })
            }
        },
        confirmButton = {
            Button(onClick = {
                onAdd(SmbServer(host, port.toIntOrNull() ?: 445, shareName, username, password, domain.takeIf { it.isNotEmpty() }, displayName))
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
