package com.beatraxus.app.telegram

import android.content.Context
import android.util.Log
import com.beatraxus.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AuthState {
    object NotReady : AuthState()
    object Ready : AuthState()
    object LoggedOut : AuthState()
    data class Error(val message: String) : AuthState()
    // Keeping these for UI compatibility while debugging "Not Ready" issues
    object WaitPhoneNumber : AuthState()
    object WaitCode : AuthState()
    object WaitPassword : AuthState()
    object WaitOtherDeviceConfirmation : AuthState()
    object WaitEmailAddress : AuthState()
    object WaitEmailCode : AuthState()
}

class TdLibManager private constructor(
    private val context: Context,
    private val apiId: String,
    private val apiHash: String
) {

    private var client: Client? = null
    @Volatile private var pendingRestart = false
    private val activeRestartId = java.util.concurrent.atomic.AtomicInteger(0)
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotReady)
    val authState: StateFlow<AuthState> = _authState

    init {
        startClient()
    }

    private fun startClient() {
        if (client != null) return
        Log.d("TDLib", "Starting TDLib client...")
        try {
            client = Client.create({ update -> handleUpdate(update) }, null, null)
        } catch (e: Exception) {
            Log.e("TDLib", "Failed to create TDLib client: ${e.message}", e)
            _authState.value = AuthState.Error("Failed to create TDLib client: ${e.message}")
        }
    }

    val updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)

    fun isReady(): Boolean {
        val ready = _authState.value is AuthState.Ready
        Log.d("TDLib", "isReady() check: $ready (current state: ${_authState.value::class.simpleName})")
        return ready
    }

    private val fileFlows = ConcurrentHashMap<Int, MutableStateFlow<TdApi.File?>>()

    fun getFileFlow(fileId: Int): StateFlow<TdApi.File?> {
        val flow = fileFlows.getOrPut(fileId) { 
            MutableStateFlow<TdApi.File?>(null as TdApi.File?) 
        }
        return flow.asStateFlow()
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) {
            val state = update.authorizationState
            Log.d("TDLib", "Authorization state update: ${state::class.java.simpleName}")
            when (state) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    Log.d("TDLib", "State: WaitTdlibParameters -> calling setParameters()")
                    setParameters()
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    Log.d("TDLib", "State: WaitPhoneNumber")
                    _authState.value = AuthState.WaitPhoneNumber
                }
                is TdApi.AuthorizationStateWaitCode -> {
                    Log.d("TDLib", "State: WaitCode")
                    _authState.value = AuthState.WaitCode
                }
                is TdApi.AuthorizationStateWaitPassword -> {
                    Log.d("TDLib", "State: WaitPassword")
                    _authState.value = AuthState.WaitPassword
                }
                is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                    Log.d("TDLib", "State: WaitOtherDeviceConfirmation")
                    _authState.value = AuthState.WaitOtherDeviceConfirmation
                }
                is TdApi.AuthorizationStateWaitEmailAddress -> {
                    Log.d("TDLib", "State: WaitEmailAddress")
                    _authState.value = AuthState.WaitEmailAddress
                }
                is TdApi.AuthorizationStateWaitEmailCode -> {
                    Log.d("TDLib", "State: WaitEmailCode")
                    _authState.value = AuthState.WaitEmailCode
                }
                is TdApi.AuthorizationStateWaitRegistration -> {
                    Log.d("TDLib", "State: WaitRegistration")
                    _authState.value = AuthState.Error("Telegram registration required. Please log in with an existing account.")
                }
                is TdApi.AuthorizationStateReady -> {
                    Log.d("TDLib", "State: Ready")
                    _authState.value = AuthState.Ready
                }
                is TdApi.AuthorizationStateLoggingOut -> {
                    Log.d("TDLib", "State: LoggingOut")
                    _authState.value = AuthState.LoggedOut
                }
                is TdApi.AuthorizationStateClosing -> {
                    Log.d("TDLib", "State: Closing (pendingRestart=$pendingRestart)")
                }
                is TdApi.AuthorizationStateClosed -> {
                    Log.d("TDLib", "State: Closed -> clearing client (pendingRestart=$pendingRestart)")
                    _authState.value = AuthState.LoggedOut
                    client = null
                    if (pendingRestart) {
                        Log.d("TDLib", "pendingRestart is true, calling startClient()")
                        pendingRestart = false
                        startClient()
                    }
                }
                else -> {
                    val stateName = state::class.java.simpleName
                    Log.d("TDLib", "Unhandled auth state: $stateName")
                    if (stateName.contains("WaitEmail", ignoreCase = true)) {
                        _authState.value = AuthState.Error("Email-based login is not supported in this version. Please use a phone number.")
                    } else {
                        // Surface unhandled states as Error for visibility
                        _authState.value = AuthState.Error("Auth incomplete/unhandled: $stateName")
                    }
                }
            }
        } else if (update is TdApi.UpdateFile) {
            fileFlows.getOrPut(update.file.id) { MutableStateFlow(null) }.value = update.file
            updates.tryEmit(update)
        } else if (update is TdApi.Update) {
            updates.tryEmit(update)
        }
    }

    private val setParamsRetryCount = java.util.concurrent.atomic.AtomicInteger(0)
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
        }
        
        client?.send(params) { result ->
            if (result is TdApi.Error) {
                Log.e("TDLib", "SetTdlibParameters failed: ${result.code} ${result.message}")
                if (result.message.contains("DATABASE_LOCK_FAILED", ignoreCase = true) && setParamsRetryCount.get() < 3) {
                    val attempt = setParamsRetryCount.incrementAndGet()
                    Log.w("TDLib", "Database locked, retrying in 1s... (Attempt $attempt)")
                    managerScope.launch {
                        delay(1000)
                        setParameters()
                    }
                } else {
                    _authState.value = AuthState.Error("TDLib Init Failed: ${result.message}")
                }
            } else {
                setParamsRetryCount.set(0)
                // Optimize network for faster downloads
                client?.send(TdApi.SetOption("is_network_unmetered", TdApi.OptionValueBoolean(true))) {}
                client?.send(TdApi.SetOption("ignore_background_networking", TdApi.OptionValueBoolean(true))) {}
                // Higher number of concurrent downloads
                client?.send(TdApi.SetOption("active_network_count", TdApi.OptionValueInteger(25))) {}
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

    fun ensureClientStarted(forceRestart: Boolean = false) {
        val currentState = _authState.value
        if (client == null) {
            Log.d("TDLib", "ensureClientStarted: client is null, starting...")
            _authState.value = AuthState.NotReady
            startClient()
            return
        }
        if (forceRestart || currentState is AuthState.Error || currentState is AuthState.LoggedOut) {
            if (pendingRestart) {
                Log.d("TDLib", "ensureClientStarted: restart already pending, ignoring request.")
                return
            }
            Log.d("TDLib", "ensureClientStarted: restarting client (force=$forceRestart, state=${currentState::class.simpleName})")
            _authState.value = AuthState.NotReady
            pendingRestart = true
            val currentRestartId = activeRestartId.incrementAndGet()
            
            Log.d("TDLib", "ensureClientStarted: sending TdApi.Close() [restartId=$currentRestartId]")
            client?.send(TdApi.Close()) { }
            
            // Safety timeout: if AuthorizationStateClosed never arrives, force restart after 5s
            managerScope.launch {
                delay(5000)
                if (pendingRestart && activeRestartId.get() == currentRestartId) {
                    Log.w("TDLib", "Safety timeout: AuthorizationStateClosed did not arrive for restartId $currentRestartId. Forcing restart.")
                    pendingRestart = false
                    client = null
                    startClient()
                }
            }
        }
    }

    fun restart() {
        ensureClientStarted(forceRestart = true)
    }

    suspend fun awaitTdlibReady(timeoutMs: Long = 20000): Boolean {
        val current = authState.value
        if (current is AuthState.Ready) return true
        
        // Don't wait if we're in a state that requires user intervention
        if (current is AuthState.WaitPhoneNumber || current is AuthState.WaitCode || 
            current is AuthState.WaitPassword || current is AuthState.LoggedOut || 
            current is AuthState.Error) {
            Log.d("TDLib", "awaitTdlibReady: Immediate fail due to state ${current::class.java.simpleName}")
            return false
        }
        
        Log.d("TDLib", "awaitTdlibReady: current state is ${current::class.java.simpleName}, waiting up to ${timeoutMs}ms...")
        return withTimeoutOrNull(timeoutMs) {
            authState.first { it is AuthState.Ready }
            true
        } ?: run {
            Log.w("TDLib", "awaitTdlibReady: Timed out. Final state: ${authState.value::class.java.simpleName}")
            false
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
        ensureClientStarted()
        if (!awaitTdlibReady(15000)) {
            Log.e("TDLib", "getChannelHistory failed: TDLib not ready")
            return emptyList()
        }

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
        try {
            send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
        } catch (e: Exception) {
            Log.e("TDLib", "DownloadFile failed for $fileId", e)
            return null
        }

        // Wait for completion using the file flow with a timeout
        return withTimeoutOrNull(60000) { // 60s timeout for audio download
            getFileFlow(fileId).first { it?.local?.isDownloadingCompleted == true }
                ?.local?.path?.let { File(it) }
        }
    }

    /**
     * Reactively waits for a file to reach a certain download state.
     * @param downloadSize If > 0, waits for at least this many bytes to be available.
     *                     If 0, waits for full completion.
     */
    suspend fun waitForFile(fileId: Int, downloadSize: Long = 0, timeoutMs: Long = 10000): String? {
        return withTimeoutOrNull(timeoutMs) {
            getFileFlow(fileId).first { file ->
                file != null && file.local.path.isNotBlank() && (
                    (downloadSize <= 0 && file.local.isDownloadingCompleted) ||
                    (downloadSize > 0 && (file.local.isDownloadingCompleted || file.local.downloadedPrefixSize >= downloadSize))
                )
            }?.local?.path
        }
    }

    fun close() {
        managerScope.cancel()
        client?.send(TdApi.Close()) {
            INSTANCE = null
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("tdjni")
                // Optional: set log level for TDLib
                Client.execute(TdApi.SetLogVerbosityLevel(1))
            } catch (e: Throwable) {
                Log.e("TDLib", "Failed to load native library or set log level: ${e.message}")
            }
        }

        @Volatile
        private var INSTANCE: TdLibManager? = null

        fun initialize(context: Context): TdLibManager {
            val instance = getInstance(context)
            instance.ensureClientStarted()
            return instance
        }

        fun getInstance(context: Context): TdLibManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TdLibManager(
                    context.applicationContext,
                    BuildConfig.TELEGRAM_API_ID,
                    BuildConfig.TELEGRAM_API_HASH
                ).also {
                    // removed: this used to log apiId/apiHash in plaintext to Logcat
                    INSTANCE = it
                }
            }
        }
    }
}
