package app.tgplayer

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A TDLib error reply, e.g. code 400 "PHONE_CODE_INVALID". */
class TdException(val code: Int, override val message: String) : Exception(message)

/** The app's one TDLib client. Call [start] once, watch [authState], and make requests with [send]. */
object Telegram {
    private const val TAG = "Telegram"
    private lateinit var appContext: Context
    @Volatile private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val _messageUpdates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * New and deleted messages ([TdApi.UpdateNewMessage], [TdApi.UpdateDeleteMessages]), for screens that show
     * a chat live. TDLib sends a channel's or supergroup's only while it is open ([TdApi.OpenChat]).
     */
    val messageUpdates: SharedFlow<TdApi.Update> = _messageUpdates.asSharedFlow()

    /** Idempotent; call from the main thread. */
    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        Client.execute(TdApi.SetLogVerbosityLevel(1)) // errors only; the default floods logcat
        client = Client.create(::onUpdate, null, null)
    }

    /**
     * Starts TDLib if needed (the Library and player use Telegram without the Telegram screen) and
     * waits for it to settle: true once logged in, false when there's no login or it takes too long.
     */
    suspend fun ready(context: Context, timeoutMs: Long = 20_000): Boolean {
        withContext(Dispatchers.Main) { start(context) }
        val settled = withTimeoutOrNull(timeoutMs) {
            authState.first { it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters }
        }
        return settled is TdApi.AuthorizationStateReady
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: return@suspendCancellableCoroutine cont.resumeWithException(IllegalStateException("Telegram.start() not called"))
            c.send(function) { result ->
                if (result is TdApi.Error) cont.resumeWithException(TdException(result.code, result.message))
                else @Suppress("UNCHECKED_CAST") cont.resume(result as T)
            }
        }

    // Runs on TDLib's own thread.
    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateNewMessage || update is TdApi.UpdateDeleteMessages) {
            _messageUpdates.tryEmit(update as TdApi.Update)
            return
        }
        if (update !is TdApi.UpdateAuthorizationState) return
        val state = update.authorizationState
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                client?.send(tdlibParameters()) { if (it is TdApi.Error) Log.e(TAG, "setTdlibParameters: ${it.message}") }
            // After log out TDLib closes the client; open a fresh one so the login screen works again.
            is TdApi.AuthorizationStateClosed -> client = Client.create(::onUpdate, null, null)
        }
        _authState.value = state
    }

    private fun tdlibParameters() = TdApi.SetTdlibParameters().apply {
        databaseDirectory = File(appContext.noBackupFilesDir, "tdlib").path
        // Not cacheDir: Android empties that on its own, which would delete offline downloads.
        // data/Storage.kt keeps streamed files under the user's size limit instead.
        filesDirectory = File(appContext.noBackupFilesDir, "tdlib-files").path
        // tails: unencrypted DB inside app-private storage; add an Android Keystore-wrapped key if needed.
        databaseEncryptionKey = ByteArray(0)
        useFileDatabase = true
        useChatInfoDatabase = true
        useMessageDatabase = true
        apiId = BuildConfig.TELEGRAM_API_ID
        apiHash = BuildConfig.TELEGRAM_API_HASH
        systemLanguageCode = Locale.getDefault().toLanguageTag()
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        systemVersion = "" // empty = TDLib detects it
        applicationVersion = BuildConfig.VERSION_NAME
    }
}
