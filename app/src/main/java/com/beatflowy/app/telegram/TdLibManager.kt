package com.beatflowy.app.telegram

import android.content.Context
import android.util.Log
import com.beatflowy.app.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AuthState {
    object Initializing : AuthState()
    object LoggedOut : AuthState()
    data class Error(val message: String) : AuthState()
    object WaitPhoneNumber : AuthState()
    object WaitCode : AuthState()
    object WaitPassword : AuthState() // 2FA
    object Ready : AuthState()
}

class TdLibManager private constructor(
    private val context: Context,
    private val apiId: String,
    private val apiHash: String
) {

    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState

    init {
        startClient()
    }

    private fun startClient() {
        Log.d("TDLib", "Starting TDLib client...")
        client = Client.create({ update -> handleUpdate(update) }, null, null)
    }

    val updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)

    fun isReady(): Boolean = _authState.value is AuthState.Ready

    private val fileFlows = ConcurrentHashMap<Int, MutableStateFlow<TdApi.File?>>()

    fun getFileFlow(fileId: Int): StateFlow<TdApi.File?> {
        val flow = fileFlows.getOrPut(fileId) { 
            MutableStateFlow<TdApi.File?>(null as TdApi.File?) 
        }
        return flow.asStateFlow()
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) {
            val authState = update.authorizationState
            Log.d("TDLib", "Auth state changed to: ${authState::class.simpleName}")
            when (authState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> setParameters()
                // In some TDLib versions this state might be named differently or handled automatically
                // If AuthorizationStateWaitEncryptionKey is unresolved, we can try to skip it if database encryption is not used
                /*
                is TdApi.AuthorizationStateWaitEncryptionKey -> {
                    client?.send(TdApi.CheckAuthenticationEncryptionKey()) { result ->
                        if (result is TdApi.Error) {
                            Log.e("TDLib", "CheckAuthenticationEncryptionKey failed: ${result.code} ${result.message}")
                            _authState.value = AuthState.Error("Encryption Error: ${result.message}")
                        }
                    }
                }
                */
                is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = AuthState.WaitPhoneNumber
                is TdApi.AuthorizationStateWaitCode -> _authState.value = AuthState.WaitCode
                is TdApi.AuthorizationStateWaitPassword -> _authState.value = AuthState.WaitPassword
                is TdApi.AuthorizationStateReady -> _authState.value = AuthState.Ready
                is TdApi.AuthorizationStateLoggingOut -> _authState.value = AuthState.LoggedOut
                is TdApi.AuthorizationStateClosing -> {}
                is TdApi.AuthorizationStateClosed -> {
                    _authState.value = AuthState.LoggedOut
                    // Client is dead, will be recreated on next login attempt if needed
                    client = null
                }
                else -> {
                    Log.d("TDLib", "Unhandled auth state: ${authState::class.simpleName}")
                }
            }
        } else if (update is TdApi.UpdateFile) {
            fileFlows[update.file.id]?.value = update.file
            updates.tryEmit(update)
        } else if (update is TdApi.Update) {
            updates.tryEmit(update)
        }
    }

    private fun setParameters() {
        val id = apiId.toIntOrNull() ?: 0
        if (id == 0 || apiHash.isBlank()) {
            val msg = "TELEGRAM_API_ID or API_HASH is not set. Please check your local.properties."
            Log.e("TDLib", msg)
            _authState.value = AuthState.Error(msg)
            return
        }

        val params = TdApi.SetTdlibParameters().apply {
            apiId = id
            apiHash = this@TdLibManager.apiHash
            databaseDirectory = File(context.filesDir, "tdlib/db").absolutePath
            filesDirectory = File(context.cacheDir, "tdlib/files").absolutePath
            
            // Ensure directories exist
            File(databaseDirectory).mkdirs()
            File(filesDirectory).mkdirs()
            
            useMessageDatabase = true
            useChatInfoDatabase = true
            useFileDatabase = true
            useSecretChats = false
            systemLanguageCode = java.util.Locale.getDefault().language
            deviceModel = android.os.Build.MODEL
            applicationVersion = "1.0"
            // If enableStorageOptimizer is unresolved, it might be named differently or missing in this build
            // useStorageOptimizer = true
        }
        
        client?.send(params) { result ->
            if (result is TdApi.Error) {
                Log.e("TDLib", "SetTdlibParameters failed: ${result.code} ${result.message}")
                _authState.value = AuthState.Error("TDLib Init Failed: ${result.message}")
            } else {
                // Optimize network for faster downloads
                client?.send(TdApi.SetOption("is_network_unmetered", TdApi.OptionValueBoolean(true))) {}
                client?.send(TdApi.SetOption("ignore_background_networking", TdApi.OptionValueBoolean(true))) {}
                // Higher number of concurrent downloads
                client?.send(TdApi.SetOption("active_network_count", TdApi.OptionValueInteger(3))) {}
            }
        }
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: run {
                cont.resumeWithException(RuntimeException("TDLib client is not active"))
                return@suspendCancellableCoroutine
            }
            c.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(RuntimeException("TDLib error ${result.code}: ${result.message}"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }

    fun ensureClientStarted() {
        if (client == null || _authState.value is AuthState.Error) {
            Log.d("TDLib", "ensureClientStarted: Restarting client (current state: ${_authState.value::class.simpleName})")
            _authState.value = AuthState.Initializing
            startClient()
        }
    }

    suspend fun submitPhoneNumber(phone: String): TdApi.Ok {
        Log.d("TDLib", "submitPhoneNumber called for $phone (Ready: ${isReady()})")
        return send(TdApi.SetAuthenticationPhoneNumber(phone, TdApi.PhoneNumberAuthenticationSettings()))
    }

    suspend fun submitCode(code: String) =
        send(TdApi.CheckAuthenticationCode(code))

    suspend fun submitPassword(password: String) =
        send(TdApi.CheckAuthenticationPassword(password))

    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message =
        send(TdApi.GetMessage(chatId, messageId))

    /**
     * Fetches messages from a channel by username.
     * Tries to find as many audio/document messages as possible.
     */
    suspend fun getChannelHistory(channelUsername: String, limit: Int = 500): List<TdApi.Message> {
        Log.d("TDLib", "getChannelHistory for: $channelUsername")
        // 1. Resolve username or ID or Invite to chat
        val chat = try {
            if (channelUsername.startsWith("-") || channelUsername.toLongOrNull() != null) {
                send(TdApi.GetChat(channelUsername.toLong()))
            } else if (channelUsername.startsWith("+")) {
                val invite = send(TdApi.CheckChatInviteLink("https://t.me/$channelUsername"))
                send(TdApi.GetChat(invite.chatId))
            } else {
                send(TdApi.SearchPublicChat(channelUsername))
            }
        } catch (e: Exception) {
            Log.e("TDLib", "Failed to find chat: $channelUsername", e)
            // Fallback for public channels if prefix was missing
            if (!channelUsername.startsWith("-") && !channelUsername.startsWith("+")) {
                 send(TdApi.SearchPublicChat(channelUsername))
            } else {
                throw e
            }
        }

        Log.d("TDLib", "Found chat: ${chat.title} (ID: ${chat.id})")

        val combined = mutableListOf<TdApi.Message>()
        
        // Use a loop to fetch up to 'limit' messages using SearchChatMessages
        suspend fun searchWithFilter(filter: TdApi.SearchMessagesFilter): List<TdApi.Message> {
            val filterName = filter::class.simpleName
            val results = mutableListOf<TdApi.Message>()
            var lastMessageId = 0L
            while (results.size < limit) {
                val req = TdApi.SearchChatMessages()
                req.chatId = chat.id
                req.query = ""
                req.fromMessageId = lastMessageId
                req.offset = 0
                req.limit = (limit - results.size).coerceAtMost(100)
                req.filter = filter
                
                Log.d("TDLib", "Searching $filterName in ${chat.title}, offset=${results.size}, lastId=$lastMessageId")
                val res = try { send(req) } catch (e: Exception) { 
                    Log.e("TDLib", "Search failed for $filterName", e)
                    null 
                }
                if (res == null || res.messages.isEmpty()) {
                    Log.d("TDLib", "No more messages found for $filterName")
                    break
                }
                
                results.addAll(res.messages)
                if (lastMessageId == res.messages.last().id) break // Prevent infinite loop
                lastMessageId = res.messages.last().id
            }
            Log.d("TDLib", "Found ${results.size} messages for $filterName")
            return results
        }

        combined.addAll(searchWithFilter(TdApi.SearchMessagesFilterAudio()))
        combined.addAll(searchWithFilter(TdApi.SearchMessagesFilterDocument()))

        if (combined.size < 5) {
            // Fallback to general history if search didn't yield enough results (indexing lag)
            Log.d("TDLib", "Search yielded too few results (${combined.size}), falling back to GetChatHistory")
            val history = send(TdApi.GetChatHistory(chat.id, 0, 0, 100, false))
            Log.d("TDLib", "GetChatHistory found ${history.messages.size} messages")
            combined.addAll(history.messages.toList())
        }

        if (combined.isNotEmpty()) {
            val finalResult = combined.distinctBy { it.id }.sortedByDescending { it.date }
            Log.d("TDLib", "Total unique messages found: ${finalResult.size}")
            return finalResult
        }

        return emptyList()
    }

    /**
     * Downloads a file and returns its local path when complete.
     */
    suspend fun downloadAudioFile(fileId: Int): File? {
        // Start download
        send(TdApi.DownloadFile(fileId, 32, 0, 0, true))

        // Wait for completion using the file flow
        return getFileFlow(fileId).first { it?.local?.isDownloadingCompleted == true }
            ?.local?.path?.let { File(it) }
    }

    fun close() {
        client?.send(TdApi.Close()) {
            INSTANCE = null
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TdLibManager? = null

        fun getInstance(context: Context): TdLibManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TdLibManager(
                    context.applicationContext,
                    BuildConfig.TELEGRAM_API_ID,
                    BuildConfig.TELEGRAM_API_HASH
                ).also {
                    Log.d("TDLib", "apiId=${BuildConfig.TELEGRAM_API_ID} apiHash=${BuildConfig.TELEGRAM_API_HASH}")
                    INSTANCE = it
                }
            }
        }
    }
}
