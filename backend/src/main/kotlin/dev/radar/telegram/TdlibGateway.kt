package dev.radar.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import dev.radar.research.MonitoredChannel
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class TelegramStatus(val authorizationState: String, val authorized: Boolean, val connectionState: String = "Initializing")

@Serializable
data class TelegramUser(val id: Long, val firstName: String, val lastName: String)

@Serializable
data class TelegramMessage(val id: Long, val date: Int, val text: String?, val channelId: Long)

class TelegramRequestException(val code: Int, val reason: String) : RuntimeException("Telegram request failed")

internal suspend fun readChannelHistory(
    username: String,
    limit: Int,
    request: suspend (TdApi.Function<*>) -> TdApi.Object,
): List<TelegramMessage> = withTimeout(20_000) {
    require(limit in 1..100) { "limit must be in 1..100" }
    val chat = request(TdApi.SearchPublicChat(username)) as TdApi.Chat
    require((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) {
        "Username must refer to a public channel"
    }
    val messages = linkedMapOf<Long, TelegramMessage>()
    var cursor = 0L
    while (messages.size < limit) {
        // TDLib may return fewer messages than requested, even for a full history.
        // The cursor message is inclusive, so reserve one extra slot and deduplicate.
        val count = (limit - messages.size + if (cursor == 0L) 0 else 1).coerceAtMost(100)
        val page = request(TdApi.GetChatHistory(chat.id, cursor, 0, count, false)) as TdApi.Messages
        if (page.messages.isEmpty()) break
        for (message in page.messages) {
            if (messages.size == limit) break
            val text = when (val content = message.content) {
                is TdApi.MessageText -> content.text?.text
                is TdApi.MessagePhoto -> content.caption?.text
                is TdApi.MessageVideo -> content.caption?.text
                is TdApi.MessageAnimation -> content.caption?.text
                is TdApi.MessageDocument -> content.caption?.text
                is TdApi.MessageAudio -> content.caption?.text
                is TdApi.MessageVoiceNote -> content.caption?.text
                else -> null
            }
            messages.putIfAbsent(message.id, TelegramMessage(message.id, message.date, text, chat.id))
        }
        val nextCursor = page.messages.last().id
        if (nextCursor == cursor) break
        cursor = nextCursor
    }
    messages.values.toList()
}

class TdlibGateway(
    private val apiId: Int,
    private val apiHash: String,
    private val databaseDirectory: Path,
) {
    @Volatile private var authState = "INITIALIZING"
    @Volatile private var authorized = false
    @Volatile private var connectionState = "Initializing"
    private lateinit var client: Client
    @Volatile var messageListener: ((TdApi.Message) -> Unit)? = null

    // Never block TDLib's update thread by reading the terminal from callbacks.
    private val authExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tdlib-auth-console").apply { isDaemon = true }
    }

    fun start() {
        // Native debug logs can contain account data; keep normal operation quiet.
        Client.execute(TdApi.SetLogVerbosityLevel(0))
        client = Client.create(
            { update -> onUpdate(update) },
            { ex -> System.err.println("TDLib update handler failed: ${ex.message}") },
            { ex -> System.err.println("TDLib request handler failed: ${ex.message}") },
        )
    }

    fun getStatus() = TelegramStatus(authState, authorized, connectionState)

    internal suspend fun executeRead(query: TdApi.Function<*>): TdApi.Object {
        ensureAuthorized()
        return request(query)
    }

    suspend fun resolveChannel(username: String): MonitoredChannel {
        ensureAuthorized()
        val chat = request(TdApi.SearchPublicChat(username)) as TdApi.Chat
        require((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) { "Username должен принадлежать каналу" }
        return MonitoredChannel(chat.id, username.lowercase(), chat.title)
    }

    suspend fun getMe(): TelegramUser {
        ensureAuthorized()
        val user = request(TdApi.GetMe()) as TdApi.User
        return TelegramUser(user.id, user.firstName, user.lastName)
    }

    suspend fun getRecentMessages(username: String, limit: Int): List<TelegramMessage> {
        ensureAuthorized()
        return readChannelHistory(username, limit, ::request)
    }

    private fun ensureAuthorized() {
        check(authorized) { "Telegram isn't authorized yet. Check the server terminal." }
    }

    private suspend fun request(query: TdApi.Function<*>): TdApi.Object = withTimeout(20_000) {
        val result = suspendCancellableCoroutine<TdApi.Object> { continuation ->
            client.send(query, { answer ->
                if (continuation.isActive) continuation.resume(answer)
            }, { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            })
        }
        if (result is TdApi.Error) {
            throw TelegramRequestException(result.code, result.message)
        }
        result
    }

    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateConnectionState) connectionState = update.state.javaClass.simpleName.removePrefix("ConnectionState")
        if (update is TdApi.UpdateNewMessage) messageListener?.invoke(update.message)
        if (update is TdApi.UpdateAuthorizationState) {
            val state = update.authorizationState
            authState = state.javaClass.simpleName.removePrefix("AuthorizationState")
            authorized = state is TdApi.AuthorizationStateReady
            authExecutor.execute { handleAuthorizationState(state) }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = this@TdlibGateway.databaseDirectory.toString()
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = this@TdlibGateway.apiId
                    apiHash = this@TdlibGateway.apiHash
                    systemLanguageCode = "en"
                    deviceModel = "Recruitment Radar (macOS)"
                    applicationVersion = "0.1.0"
                }
                sendAuthorization(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                sendAuthorization(TdApi.SetAuthenticationPhoneNumber(prompt("Telegram phone (+...): "), null))
            }
            is TdApi.AuthorizationStateWaitCode -> {
                sendAuthorization(TdApi.CheckAuthenticationCode(prompt("Telegram login code: ")))
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                sendAuthorization(TdApi.CheckAuthenticationPassword(prompt("Telegram 2FA password: ")))
            }
            is TdApi.AuthorizationStateWaitEmailAddress -> {
                sendAuthorization(TdApi.SetAuthenticationEmailAddress(prompt("Login email address: ")))
            }
            is TdApi.AuthorizationStateWaitEmailCode -> {
                val code = prompt("Login email code: ")
                sendAuthorization(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                println("Confirm login in Telegram on another device: ${state.link}")
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                System.err.println("New account registration is not supported in this prototype.")
            }
            is TdApi.AuthorizationStateReady -> println("Telegram authenticated. GET http://127.0.0.1:8080/api/telegram/me")
            is TdApi.AuthorizationStateClosing, is TdApi.AuthorizationStateClosed -> println("TDLib is closing/closed")
            else -> Unit
        }
    }

    private fun prompt(message: String): String {
        print(message)
        System.out.flush()
        return readlnOrNull()?.trim() ?: error("No terminal input available")
    }

    private fun sendAuthorization(query: TdApi.Function<*>) {
        client.send(query, { result ->
            if (result is TdApi.Error) {
                System.err.println("Telegram authorization failed (${result.code}): ${result.message}")
                // Retry requires user action / a subsequent state update; do not loop on a bad code.
                authExecutor.execute { handleAuthorizationStateFromRetry() }
            }
        })
    }

    private fun handleAuthorizationStateFromRetry() {
        // GetAuthorizationState lets us re-prompt after an incorrect code or password.
        client.send(TdApi.GetAuthorizationState(), { result ->
            if (result is TdApi.AuthorizationState) {
                authExecutor.execute { handleAuthorizationState(result) }
            }
        })
    }

    fun close() {
        if (::client.isInitialized) client.send(TdApi.Close(), null)
        authExecutor.shutdown()
    }
}
