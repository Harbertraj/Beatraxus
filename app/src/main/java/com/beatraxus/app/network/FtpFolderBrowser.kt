package com.beatraxus.app.network

import com.beatraxus.app.repository.FtpProtocol
import com.beatraxus.app.repository.FtpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.InputStream

data class FtpEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val fullPath: String
)

class FtpFolderBrowser {
    private var ftpClient: FTPClient? = null
    private var sftpClient: SFTPClient? = null
    private var sshClient: SSHClient? = null
    private var currentServerId: String? = null

    suspend fun connect(server: FtpServer): Boolean = withContext(Dispatchers.IO) {
        try {
            if (currentServerId == server.id && isConnected()) {
                return@withContext true
            }
            disconnect()

            when (server.protocol) {
                FtpProtocol.FTP, FtpProtocol.FTPS -> {
                    val client = if (server.protocol == FtpProtocol.FTPS) FTPSClient() else FTPClient()
                    client.connect(server.host, server.port)
                    if (client.login(server.username, server.password ?: "")) {
                        client.enterLocalPassiveMode()
                        client.setFileType(FTP.BINARY_FILE_TYPE)
                        ftpClient = client
                    } else {
                        client.disconnect()
                        return@withContext false
                    }
                }
                FtpProtocol.SFTP -> {
                    val client = SSHClient()
                    client.addHostKeyVerifier(PromiscuousVerifier())
                    client.connect(server.host, server.port)
                    if (server.privateKeyPath != null) {
                        client.authPublickey(server.username, server.privateKeyPath)
                    } else {
                        client.authPassword(server.username, server.password ?: "")
                    }
                    sshClient = client
                    sftpClient = client.newSFTPClient()
                }
            }
            currentServerId = server.id
            true
        } catch (e: Exception) {
            android.util.Log.e("FtpFolderBrowser", "Error connecting to FTP/SFTP", e)
            false
        }
    }

    private fun isConnected(): Boolean {
        return (ftpClient?.isConnected == true) || (sshClient?.isConnected == true)
    }

    fun disconnect() {
        try {
            ftpClient?.logout()
            ftpClient?.disconnect()
            sftpClient?.close()
            sshClient?.disconnect()
        } catch (e: Exception) {
            // Ignore
        } finally {
            ftpClient = null
            sftpClient = null
            sshClient = null
            currentServerId = null
        }
    }

    suspend fun listFolder(path: String): List<FtpEntry> = withContext(Dispatchers.IO) {
        try {
            val ftp = ftpClient
            if (ftp != null) {
                return@withContext ftp.listFiles(path).map { file ->
                    FtpEntry(
                        name = file.name,
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = file.timestamp?.timeInMillis ?: 0L,
                        fullPath = if (path.isEmpty()) file.name else "${path.removeSuffix("/")}/${file.name}"
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }

            val sftp = sftpClient
            if (sftp != null) {
                return@withContext sftp.ls(path).map { info ->
                    FtpEntry(
                        name = info.name,
                        isDirectory = info.isDirectory,
                        size = info.attributes.size,
                        lastModified = (info.attributes.mtime ?: 0L) * 1000L,
                        fullPath = if (path.isEmpty()) info.name else "${path.removeSuffix("/")}/${info.name}"
                    )
                }.filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("FtpFolderBrowser", "Error listing folder $path", e)
            emptyList()
        }
    }

    suspend fun openStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            val ftp = ftpClient
            if (ftp != null) {
                return@withContext ftp.retrieveFileStream(path)
            }

            val sftp = sftpClient
            if (sftp != null) {
                val file = sftp.open(path)
                return@withContext file.RemoteFileInputStream()
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("FtpFolderBrowser", "Error opening stream for $path", e)
            null
        }
    }
    
    suspend fun completePendingCommand(): Boolean = withContext(Dispatchers.IO) {
        ftpClient?.completePendingCommand() ?: true
    }
}
