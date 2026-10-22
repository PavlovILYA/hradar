package dev.radar.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import dev.radar.research.MonitoredChannel
import dev.radar.bot.BotAccessUser
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class TelegramStatus(val authorizationState: String, val authorized: Boolean, val connectionState: String = "Initializing")

@Serializable
data class TelegramUser(val id: Long, val firstName: String, val lastName: String, val username: String? = null)

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

internal suspend fun readPublicUser(username: String, request: suspend (TdApi.Function<*>) -> TdApi.Object): BotAccessUser = withTimeout(20_000) {
    val chat = request(TdApi.SearchPublicChat(username)) as TdApi.Chat
    val type = chat.type as? TdApi.ChatTypePrivate ?: throw IllegalArgumentException("Найден канал или группа. Отправьте приглашение по ссылке")
    val user = request(TdApi.GetUser(type.userId)) as TdApi.User
    require(user.type is TdApi.UserTypeRegular && user.id > 0) { "Найден не пользователь. Отправьте приглашение по ссылке" }
    BotAccessUser(user.id, listOf(user.firstName, user.lastName).filter { !it.isNullOrBlank() }.joinToString(" ").take(100),
        user.usernames?.activeUsernames?.firstOrNull(), "guest")
}

class TdlibGateway(
    private var apiId: Int? = null,
    private var apiHash: String? = null,
    private val databaseDirectory: Path,
) {
    @Volatile private var authState = "INITIALIZING"
    @Volatile private var authorized = false
    @Volatile private var connectionState = "Initializing"
    private lateinit var client: Client
    @Volatile var messageListener: ((TdApi.Message) -> Unit)? = null

    val onboarding = Onboarding(::rawRequest)

    @Synchronized fun configure(credentials: ApiCredentials, persist: () -> Unit = {}) {
        credentials.validate()
        check(!::client.isInitialized || onboarding.status().state in setOf("ParametersFailed")) { "TDLib уже настроен" }
        persist()
        apiId = credentials.apiId; apiHash = credentials.apiHash
        if (::client.isInitialized) {
            onboarding.initializing()
            authExecutor.execute { handleAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()) }
        } else start()
    }

    // Never block TDLib's update thread by reading the terminal from callbacks.
    private val authExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tdlib-parameters").apply { isDaemon = true }
    }

    @Synchronized fun start() {
        if (apiId == null || apiHash == null || ::client.isInitialized) return
        onboarding.initializing()
        // Native debug logs can contain account data; keep normal operation quiet.
        Client.execute(TdApi.SetLogVerbosityLevel(0))
        client = Client.create(
            { update -> onUpdate(update) },
            { ex -> System.err.println("TDLib update handler failed") },
            { ex -> System.err.println("TDLib request handler failed") },
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
        return TelegramUser(user.id, user.firstName, user.lastName, user.usernames?.activeUsernames?.firstOrNull())
    }
    suspend fun resolvePublicUser(username: String): BotAccessUser {
        ensureAuthorized()
        return readPublicUser(username, ::request)
    }

    suspend fun getRecentMessages(username: String, limit: Int): List<TelegramMessage> {
        ensureAuthorized()
        return readChannelHistory(username, limit, ::request)
    }

    suspend fun getHistoryPage(channelId: Long, cursor: Long): List<TelegramMessage> {
        val page = executeRead(TdApi.GetChatHistory(channelId, cursor, 0, 100, false)) as TdApi.Messages
        return page.messages.map { TelegramMessage(it.id, it.date, dev.radar.research.messageText(it.content), it.chatId) }
    }

    private fun ensureAuthorized() {
        check(authorized) { "Завершите вход в Telegram в интерфейсе hradar." }
    }

    private suspend fun request(query: TdApi.Function<*>): TdApi.Object {
        val result = rawRequest(query)
        if (result is TdApi.Error) throw TelegramRequestException(result.code, result.message)
        return result
    }

    private suspend fun rawRequest(query: TdApi.Function<*>): TdApi.Object = withTimeout(20_000) {
        suspendCancellableCoroutine<TdApi.Object> { continuation ->
            client.send(query, { answer ->
                if (continuation.isActive) continuation.resume(answer)
            }, { exception ->
                // Authentication exception details are not safe to return or log.
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Telegram временно недоступен"))
            })
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateConnectionState) connectionState = update.state.javaClass.simpleName.removePrefix("ConnectionState")
        if (update is TdApi.UpdateNewMessage) messageListener?.invoke(update.message)
        if (update is TdApi.UpdateAuthorizationState) {
            val state = update.authorizationState
            authState = state.javaClass.simpleName.removePrefix("AuthorizationState")
            authorized = state is TdApi.AuthorizationStateReady
            onboarding.update(state)
            if (state is TdApi.AuthorizationStateClosed) closed.countDown()
            if (state is TdApi.AuthorizationStateWaitTdlibParameters) authExecutor.execute { handleAuthorizationState(state) }
        }
    }

    @Synchronized private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = this@TdlibGateway.databaseDirectory.toString()
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = this@TdlibGateway.apiId!!
                    apiHash = this@TdlibGateway.apiHash!!
                    systemLanguageCode = "en"
                    deviceModel = "Recruitment Radar (macOS)"
                    applicationVersion = "0.1.0"
                }
                sendAuthorization(params)
            }
            else -> Unit
        }
    }

    private fun sendAuthorization(query: TdApi.Function<*>) {
        client.send(query, { result -> if (result is TdApi.Error) onboarding.parametersFailed() })
    }

    private val closed = java.util.concurrent.CountDownLatch(1)

    fun close() {
        if (::client.isInitialized) {
            client.send(TdApi.Close(), null)
            closed.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        authExecutor.shutdown()
    }
}
