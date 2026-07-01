package com.beatflowy.app.telegram

import android.content.Context
import android.util.Log
import com.beatflowy.app.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AuthState {
    object LoggedOut : AuthState()
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

    private val client: Client = Client.create({ update -> handleUpdate(update) }, null, null)

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState

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
            Log.d("TDLib", "Auth state changed to: ${update.authorizationState::class.simpleName}")
            when (val state = update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> setParameters()
                is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = AuthState.WaitPhoneNumber
                is TdApi.AuthorizationStateWaitCode -> _authState.value = AuthState.WaitCode
                is TdApi.AuthorizationStateWaitPassword -> _authState.value = AuthState.WaitPassword
                is TdApi.AuthorizationStateReady -> _authState.value = AuthState.Ready
                is TdApi.AuthorizationStateClosed -> _authState.value = AuthState.LoggedOut
                else -> {
                    Log.d("TDLib", "Unhandled auth state: ${state::class.simpleName}")
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
        val params = TdApi.SetTdlibParameters().apply {
            apiId = this@TdLibManager.apiId.toInt()
            apiHash = this@TdLibManager.apiHash
            databaseDirectory = context.filesDir.absolutePath + "/tdlib"
            useMessageDatabase = true
            useSecretChats = false
            systemLanguageCode = "en"
            deviceModel = "Android"
            applicationVersion = "1.0"
        }
        client.send(params) {}
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(RuntimeException("TDLib error ${result.code}: ${result.message}"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
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
