package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.util.Base64
import java.security.MessageDigest

internal fun messageText(content: TdApi.MessageContent?): String? = when (content) {
    is TdApi.MessageText -> content.text?.text
    is TdApi.MessagePhoto -> content.caption?.text
    is TdApi.MessageVideo -> content.caption?.text
    is TdApi.MessageAnimation -> content.caption?.text
    is TdApi.MessageDocument -> content.caption?.text
    is TdApi.MessageAudio -> content.caption?.text
    is TdApi.MessageVoiceNote -> content.caption?.text
    else -> null
}

@Serializable private data class SearchCursor(val context: String, val positions: Map<String, Long> = emptyMap(), val done: Set<String> = emptySet(), val globalOffset: String = "")

class SearchService(private val telegram: TdlibGateway, private val repo: Repositories, private val matching: MatchingService, private val execute: suspend (TdApi.Function<*>) -> TdApi.Object = telegram::executeRead) {
    private val json = Json { encodeDefaults = true }
    private fun encode(cursor: SearchCursor) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(cursor).toByteArray())
    private fun decode(value: String): SearchCursor {
        require(value.length <= 10_000) { "Слишком большой курсор" }
        return try { json.decodeFromString<SearchCursor>(String(Base64.getUrlDecoder().decode(value))) }
        catch (_: Exception) { throw IllegalArgumentException("Некорректный курсор поиска") }
    }
    suspend fun enrich(message: TdApi.Message, known: MonitoredChannel? = null, withLink: Boolean = true, onLinkRateLimit: (() -> Unit)? = null): ResearchMessage {
        val channel = known ?: run {
            val chat = execute(TdApi.GetChat(message.chatId)) as TdApi.Chat
            val type = chat.type as? TdApi.ChatTypeSupergroup
            val group = type?.let { execute(TdApi.GetSupergroup(it.supergroupId)) as TdApi.Supergroup }
            MonitoredChannel(chat.id, group?.usernames?.activeUsernames?.firstOrNull().orEmpty(), chat.title)
        }
        val link = if (withLink) try {
            (execute(TdApi.GetMessageLink().apply { chatId = message.chatId; messageId = message.id }) as TdApi.MessageLink).link
        } catch (ex: TelegramRequestException) {
            if (ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")) onLinkRateLimit?.invoke()
            null
        } else null
        return ResearchMessage(message.id, message.chatId, message.date, messageText(message.content), channel.title, channel.username.takeIf { it.isNotBlank() }, link)
    }
    suspend fun search(input: SearchRequest): SearchPage = withTimeout(90_000) {
        require(input.limit in 1..100) { "Количество результатов: от 1 до 100" }
        require(input.mode in setOf("channel", "tracked", "global")) { "Неизвестный режим поиска" }
        val template = input.templateId?.let { id -> withContext(Dispatchers.IO) { repo.template(id) } ?: throw ResourceNotFound() }
        require(template == null || template.active) { "Шаблон приостановлен" }
        val query = (template?.telegramQuery ?: input.query).trim()
        require(query.length in 1..256) { "Укажите запрос Telegram (до 256 символов)" }
        val saved = withContext(Dispatchers.IO) { repo.channels() }
        val mode = when (template?.sourceMode) { "global" -> "global"; "all", "selected" -> "tracked"; else -> input.mode }
        val usernames = when {
            mode == "global" -> emptyList()
            template?.sourceMode == "all" -> saved.map { it.username }
            template?.sourceMode == "selected" -> saved.filter { it.id in template.sources }.map { it.username }
            mode == "tracked" -> saved.filter { it.username in input.usernames.map { n -> n.removePrefix("@").lowercase() } }.map { it.username }
            else -> input.usernames.map { it.removePrefix("@").lowercase() }
        }.distinct().sorted()
        require(mode == "global" || usernames.size in 1..10) { "Выберите от 1 до 10 источников" }
        require(mode != "channel" || usernames.size == 1) { "Выберите один канал" }
        require(usernames.all { it.matches(Regex("[a-zA-Z0-9_]{5,32}")) }) { "Некорректный username" }
        val context = MessageDigest.getInstance("SHA-256").digest("$mode|$query|$usernames|${template?.let { json.encodeToString(it) }}".toByteArray()).joinToString("") { "%02x".format(it) }
        val cursor = if (input.cursor.isEmpty()) SearchCursor(context) else decode(input.cursor)
        require(cursor.context == context) { "Параметры поиска изменились. Начните заново" }
        if (mode == "global") return@withTimeout global(query, input.limit, cursor, template, input.withLinks)
        val positions = cursor.positions.toMutableMap()
        val done = cursor.done.toMutableSet()
        val channels = usernames.filterNot { it in done }.take(input.limit)
        val result = mutableListOf<ResearchMessage>()
        val errors = mutableListOf<String>()
        var candidates = 0
        var rateLimited = false
        for (username in channels) {
            if (rateLimited) break
            try {
                val channel = if (mode == "tracked") saved.first { it.username == username } else telegram.resolveChannel(username)
                val count = (input.limit / channels.size).coerceAtLeast(1)
                val page = execute(TdApi.SearchChatMessages().apply {
                    chatId = channel.id; this.query = query; fromMessageId = positions[username] ?: 0L; limit = count
                }) as TdApi.FoundChatMessages
                if (page.nextFromMessageId == 0L || page.nextFromMessageId == positions[username]) done.add(username)
                positions[username] = page.nextFromMessageId
                for (message in page.messages) {
                    candidates++
                    val explanation = template?.let { matching.check(it, messageText(message.content)) }
                    if (explanation == null || explanation.matches) result.add(enrich(message, channel,
                        withLink = input.withLinks && !rateLimited, onLinkRateLimit = { rateLimited = true }).copy(explanation = explanation))
                }
            } catch (ex: TelegramRequestException) {
                if (ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")) {
                    rateLimited = true
                    break // Keep completed source pages; failed source position is unchanged.
                }
                errors.add("@$username недоступен (${ex.code})")
                // Retry failed sources on the next page; never silently replace them.
            }
        }
        val next = if (done.containsAll(usernames)) "" else encode(SearchCursor(context, positions, done))
        val notice = (errors +
            (if (rateLimited) listOf("Telegram ограничил частоту запросов. Повторите позже; уже найденные публикации доступны.") else emptyList()) +
            (if (template != null) listOf("Проверено $candidates публикаций. Локальные условия применяются к результатам запроса «$query».") else emptyList()))
            .joinToString(" ").takeIf { it.isNotBlank() }
        SearchPage(result.distinctBy { it.channelId to it.id }.sortedWith(compareByDescending<ResearchMessage> { it.date }.thenByDescending { it.id }), next, if (rateLimited) "rate_limited" else if (errors.isEmpty()) "ok" else "partial", notice)
    }
    private fun function(name: String, fields: Map<String, Any>): TdApi.Function<*> {
        val type = Class.forName("org.drinkless.tdlib.TdApi\$$name")
        return (type.getDeclaredConstructor().newInstance() as TdApi.Function<*>).also { fn -> fields.forEach { (key, value) -> type.getField(key).set(fn, value) } }
    }
    private fun field(obj: TdApi.Object, name: String): Any = obj.javaClass.getField(name).get(obj)
    private suspend fun global(query: String, limit: Int, cursor: SearchCursor, template: SearchTemplate?, withLinks: Boolean): SearchPage {
        try {
            val check = function("GetPublicPostSearchLimits", mapOf("query" to query))
            val limits = execute(check)
            val free = field(limits, "isCurrentQueryFree") as Boolean
            val remaining = field(limits, "remainingFreeQueryCount") as Int
            if (!free && remaining <= 0) return SearchPage(state = "free_limit", notice = "Бесплатный лимит исчерпан. Повторите через ${field(limits, "nextFreeQueryIn")} секунд. Stars не расходуются.")
            val page = execute(function("SearchPublicPosts", mapOf("query" to query, "offset" to cursor.globalOffset, "limit" to limit, "starCount" to 0L)))
            @Suppress("UNCHECKED_CAST") val messages = field(page, "messages") as Array<TdApi.Message>
            val result = messages.mapNotNull { message ->
                val explanation = template?.let { matching.check(it, messageText(message.content)) }
                if (explanation != null && !explanation.matches) null else enrich(message, withLink = withLinks).copy(explanation = explanation)
            }
            val offset = field(page, "nextOffset") as String
            val exceeded = field(page, "areLimitsExceeded") as Boolean
            return SearchPage(result, if (offset.isBlank() || offset == cursor.globalOffset) "" else encode(cursor.copy(globalOffset = offset)), if (exceeded) "free_limit" else "ok", if (exceeded) "Бесплатный лимит исчерпан. Stars не расходуются." else "Глобальные шаблоны запускаются вручную. Непрерывного глобального мониторинга нет.")
        } catch (_: ReflectiveOperationException) {
            return SearchPage(state = "unsupported", notice = "Установленная TDLib не поддерживает безопасный глобальный поиск с проверкой лимита.")
        } catch (ex: TelegramRequestException) {
            return when {
                ex.reason.contains("METHOD") || ex.reason.contains("NOT_SUPPORTED") -> SearchPage(state = "unsupported", notice = "Telegram не поддерживает этот метод для текущей сессии.")
                ex.reason.contains("PAYMENT") || ex.reason.contains("STARS") || ex.reason.contains("SEARCH_LIMIT") || ex.reason.contains("PREMIUM") -> SearchPage(state = "free_limit", notice = "Бесплатный глобальный поиск недоступен для аккаунта. Stars не расходуются.")
                else -> throw ex
            }
        }
    }
}
