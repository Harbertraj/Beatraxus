package com.beatraxus.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.beatraxus.app.repository.FtpProtocol
import com.beatraxus.app.repository.FtpServer
import com.beatraxus.app.util.NetworkUtils
import com.beatraxus.app.viewmodel.PlayerViewModel

@Composable
fun FtpBrowserScreen(uiState: PlayerUiState, viewModel: PlayerViewModel) {
    var selectedServer by rememberSaveable { mutableStateOf<FtpServer?>(null) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<com.beatraxus.app.network.FtpEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedServer, currentPath) {
        val server = selectedServer
        if (server != null) {
            isLoading = true
            if (viewModel.connectFtp(server)) {
                entries = viewModel.listFtpFolder(currentPath)
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
                Text("FTP / SFTP Servers", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, null, tint = Color.Magenta)
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(uiState.ftpServers) { server ->
                    ListItem(
                        modifier = Modifier.clickable { selectedServer = server },
                        headlineContent = { Text(server.displayName, color = Color.White) },
                        supportingContent = { Text("${server.protocol.name}://${server.host}:${server.port}", color = Color.White.copy(0.6f)) },
                        leadingContent = { Icon(Icons.Rounded.Dns, null, tint = Color.Magenta) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeFtpServer(server.id) }) {
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
                    Text(server.displayName, fontSize = 14.sp, color = Color.Magenta)
                    Text("/$currentPath", fontSize = 12.sp, color = Color.White.copy(0.7f), maxLines = 1)
                }
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Magenta)
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
                                viewModel.playFtpFile(server, entry)
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
                                tint = if (entry.isDirectory) Color.Magenta else Color.White.copy(0.8f)
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        FtpAddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { server ->
                viewModel.addFtpServer(server)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun FtpAddServerDialog(onDismiss: () -> Unit, onAdd: (FtpServer) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var protocol by remember { mutableStateOf(FtpProtocol.FTP) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKeyPath by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add FTP/SFTP Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display Name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Protocol: ", Modifier.padding(end = 8.dp))
                    FtpProtocol.values().forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                            protocol = p
                            if (port == "21" || port == "22") {
                                port = if (p == FtpProtocol.SFTP) "22" else "21"
                            }
                        }) {
                            RadioButton(selected = protocol == p, onClick = null)
                            Text(p.name, fontSize = 12.sp)
                        }
                    }
                }
                TextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                if (protocol == FtpProtocol.SFTP) {
                    TextField(value = privateKeyPath, onValueChange = { privateKeyPath = it }, label = { Text("Private Key Path (Optional)") })
                }
                TextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
            }
        },
        confirmButton = {
            Button(onClick = {
                onAdd(FtpServer(host, port.toIntOrNull() ?: 21, protocol, username, password.takeIf { it.isNotEmpty() }, privateKeyPath.takeIf { it.isNotEmpty() }, displayName))
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
