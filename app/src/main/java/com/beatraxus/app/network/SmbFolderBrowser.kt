package com.beatraxus.app.network

import com.beatraxus.app.repository.SmbServer
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.*

data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val fullPath: String
)

class SmbFolderBrowser {
    private val client = SMBClient()
    private var session: Session? = null
    private var share: DiskShare? = null
    private var currentServerId: String? = null

    suspend fun connect(server: SmbServer): Boolean = withContext(Dispatchers.IO) {
        try {
            if (currentServerId == server.id && session?.connection?.isConnected == true) {
                return@withContext true
            }
            
            disconnect()
            
            val connection = client.connect(server.host, server.port)
            val authContext = AuthenticationContext(
                server.username,
                server.password.toCharArray(),
                server.domain ?: ""
            )
            val session = connection.authenticate(authContext)
            this@SmbFolderBrowser.session = session
            this@SmbFolderBrowser.share = session.connectShare(server.shareName) as DiskShare
            this@SmbFolderBrowser.currentServerId = server.id
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbFolderBrowser", "Error connecting to SMB", e)
            false
        }
    }

    fun disconnect() {
        try {
            share?.close()
            session?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            share = null
            session = null
            currentServerId = null
        }
    }

    suspend fun listFolder(path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val currentShare = share ?: return@withContext emptyList()
        try {
            currentShare.list(path).mapNotNull { info ->
                if (info.fileName == "." || info.fileName == "..") return@mapNotNull null
                
                val isDir = info.fileAttributes.and(0x10L) != 0L // FILE_ATTRIBUTE_DIRECTORY
                SmbEntry(
                    name = info.fileName,
                    isDirectory = isDir,
                    size = info.endOfFile,
                    lastModified = info.changeTime.toEpochMillis(),
                    fullPath = if (path.isEmpty()) info.fileName else "${path.replace("\\", "/")}/${info.fileName}".removePrefix("/")
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            android.util.Log.e("SmbFolderBrowser", "Error listing folder $path", e)
            emptyList()
        }
    }

    suspend fun openStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        val currentShare = share ?: return@withContext null
        try {
            val file = currentShare.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            file.inputStream
        } catch (e: Exception) {
            android.util.Log.e("SmbFolderBrowser", "Error opening stream for $path", e)
            null
        }
    }
}
