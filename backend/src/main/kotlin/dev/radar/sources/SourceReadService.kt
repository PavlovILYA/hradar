package dev.radar.sources

import dev.radar.research.*
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.drinkless.tdlib.TdApi
import java.util.UUID

@Serializable data class SourceMessagePage(
    val messages: List<ResearchMessage>, val nextCursor: Long? = null,
    val state: String = "ok", val notice: String? = null,
)

/** Owner-local selected sources. Never feeds the public recruiter, Bot or MCP adapters. */
class SourceReadService(
    private val repo: Repositories,
    private val execute: suspend (TdApi.Function<*>) -> TdApi.Object,
) {
    private val catalog = TdlibSourceCatalog(execute)
    private val matching = MatchingService()
    private data class SearchState(
        val keys: List<String>, val query: String, val template: SearchTemplate?,
        val positions: Map<String, Long> = emptyMap(), val done: Set<String> = emptySet(),
        val seen: Set<Pair<Long, Long>> = emptySet(), val expiresAt: Long,
    )
    private val cursors = linkedMapOf<String, SearchState>()
    private val lock = Mutex()

    private fun saved(key: String): TelegramSource {
        require(key.length in 1..36) { "Некорректный источник" }
        return repo.sources().firstOrNull { it.key == key } ?: throw ResourceNotFound()
    }
    private fun selectedStillExists(source: TelegramSource) {
        val current = saved(source.key)
        require(current.chatId == source.chatId && current.scope == source.scope) { "Источник изменился. Начните заново" }
    }
    private suspend fun accessible(source: TelegramSource) {
        source.scope.validate()
        val current = when (source.scope.kind) {
            ScopeKind.CHAT -> catalog.describe(source.chatId)
            ScopeKind.FORUM_TOPIC -> try { catalog.topic(source.chatId, source.scope.forumTopicId!!) }
                catch (_: SourceUnavailable) { null }
            ScopeKind.MESSAGE_THREAD -> catalog.thread(
                source.threadAnchorChatId ?: throw IllegalArgumentException("Не сохранено исходное сообщение обсуждения"),
                source.threadAnchorMessageId ?: throw IllegalArgumentException("Не сохранено исходное сообщение обсуждения"))
        }
        // Deleted cloud users may retain readable history. The message RPC is the
        // authority for access; deleting an account does not erase its past conversation.
        if (current == null || current.access == SourceAccess.UNAVAILABLE && current.type != SourceType.PRIVATE)
            throw TelegramRequestException(403, "SOURCE_UNAVAILABLE")
        require(current.chatId == source.chatId && current.scope == source.scope) { "Telegram вернул другую область" }
        selectedStillExists(source)
    }
    private fun accepts(source: TelegramSource, message: TdApi.Message): Boolean =
        message.chatId == source.chatId && message.id > 0 &&
            when (source.scope.kind) {
                ScopeKind.CHAT -> true
                ScopeKind.FORUM_TOPIC -> (message.topicId as? TdApi.MessageTopicForum)?.forumTopicId == source.scope.forumTopicId
                ScopeKind.MESSAGE_THREAD -> (message.topicId as? TdApi.MessageTopicThread)?.messageThreadId == source.scope.messageThreadId
            }

    private suspend fun convert(source: TelegramSource, message: TdApi.Message, withLink: Boolean = false,
        onRate: (() -> Unit)? = null): ResearchMessage {
        require(accepts(source, message)) { "Telegram вернул сообщение другой области" }
        val link = if (withLink) try {
            withTimeoutOrNull(1500) {
                val value = (execute(TdApi.GetMessageLink().apply { chatId = source.chatId; messageId = message.id }) as TdApi.MessageLink).link
                dev.radar.bot.verifiedTelegramLink(value)
            }
        } catch (ex: TelegramRequestException) {
            if (rateLimited(ex)) onRate?.invoke()
            null
        } else null
        return ResearchMessage(message.id, source.chatId, message.date, messageText(message.content), source.title,
            source.username, link, sourceKey = source.key,
            forumTopicId = (message.topicId as? TdApi.MessageTopicForum)?.forumTopicId, scopeTitle = source.scopeTitle,
            messageThreadId = (message.topicId as? TdApi.MessageTopicThread)?.messageThreadId,
            availability = when (message.content) {
                is TdApi.MessageUnsupported -> "unsupported"
                is TdApi.MessageExpiredPhoto, is TdApi.MessageExpiredVideo,
                is TdApi.MessageExpiredVideoNote, is TdApi.MessageExpiredVoiceNote -> "unavailable"
                null -> "unavailable"
                else -> "available"
            })
    }
    suspend fun recent(key: String, before: Long = 0, limit: Int = 50): SourceMessagePage = withTimeout(25_000) {
        require(limit in 1..100 && before >= 0) { "Некорректная страница сообщений" }
        val source = saved(key); accessible(source)
        val result = linkedMapOf<Long, ResearchMessage>()
        var cursor = before
        var exhausted = false
        var stalled = false
        var pages = 0
        while (result.size < limit && pages++ < 5) {
            currentCoroutineContext().ensureActive()
            val count = (limit - result.size + if (cursor > 0) 1 else 0).coerceAtMost(100)
            // Pinned OrderedMessage.cpp get_history excludes an exact anchor at offset 0;
            // MessagesManager.cpp forum history slices do likewise via lower_bound. Keep
            // tolerating inclusive overlaps, but only a native empty page proves exhaustion.
            val fn = when (source.scope.kind) {
                ScopeKind.CHAT -> TdApi.GetChatHistory(source.chatId, cursor, 0, count, false)
                ScopeKind.FORUM_TOPIC -> TdApi.GetForumTopicHistory(source.chatId, source.scope.forumTopicId!!, cursor, 0, count)
                ScopeKind.MESSAGE_THREAD -> TdApi.GetMessageThreadHistory(source.threadAnchorChatId!!,
                    source.threadAnchorMessageId!!, cursor, 0, count)
            }
            val raw = (execute(fn) as TdApi.Messages).messages
            require(raw.size <= 100 && raw.all { accepts(source, it) }) { "Telegram вернул сообщения другой области" }
            if (raw.isEmpty()) { exhausted = true; break }
            val accepted = raw.distinctBy { it.id }.filter { cursor == 0L || it.id < cursor }.sortedByDescending { it.id }.take(limit - result.size)
            if (accepted.isEmpty()) { stalled = true; break }
            accepted.forEach { result[it.id] = convert(source, it) }
            cursor = accepted.last().id
        }
        selectedStillExists(source)
        SourceMessagePage(result.values.toList(), if (exhausted) null else cursor,
            if (stalled) "cursor_stalled" else "ok",
            if (stalled) "Telegram не продвинула курсор. Повторите позже." else null)
    }

    suspend fun detail(key: String, messageId: Long): ResearchMessage = withTimeout(25_000) {
        require(messageId > 0) { "Некорректное сообщение" }
        val source = saved(key); accessible(source)
        val message = try { execute(TdApi.GetMessage(source.chatId, messageId)) as TdApi.Message }
        catch (ex: TelegramRequestException) {
            if (ex.code !in setOf(400, 404) || rateLimited(ex)) throw ex
            selectedStillExists(source)
            return@withTimeout ResearchMessage(messageId, source.chatId, 0, null, source.title, source.username,
                sourceKey = source.key, forumTopicId = source.scope.forumTopicId, scopeTitle = source.scopeTitle,
                messageThreadId = source.scope.messageThreadId, canGetMessageThread = false, availability = "unavailable")
        }
        require(message.id == messageId && accepts(source, message)) { "Сообщение не принадлежит выбранной области" }
        var limited = false
        val result = convert(source, message, withLink = true, onRate = { limited = true })
        val canGetThread = if (limited || source.isForum) false else try {
            withTimeoutOrNull(1500) {
                (execute(TdApi.GetMessageProperties(source.chatId, messageId)) as TdApi.MessageProperties).canGetMessageThread
            } ?: false
        } catch (_: TelegramRequestException) { false }
        selectedStillExists(source)
        result.copy(canGetMessageThread = canGetThread)
    }

    /** The user explicitly chooses a discussion from an accessible selected-source message. */
    suspend fun discussion(key: String, messageId: Long): TelegramSource = withTimeout(25_000) {
        require(messageId > 0) { "Некорректное сообщение" }
        val source = saved(key); accessible(source)
        val message = execute(TdApi.GetMessage(source.chatId, messageId)) as TdApi.Message
        require(message.id == messageId && accepts(source, message)) { "Сообщение не принадлежит выбранной области" }
        val target = catalog.thread(source.chatId, messageId)
        withContext(Dispatchers.IO) {
            synchronized(repo) {
                selectedStillExists(source)
                repo.saveSource(target.copy(checkedAt = System.currentTimeMillis()))
            }
        }
    }

    /** Callback workers use this typed owner-local port; callbacks themselves never perform IO. */
    suspend fun observe(key: String, message: TdApi.Message): ResearchMessage? = withTimeout(25_000) {
        val source = saved(key)
        if (!accepts(source, message)) return@withTimeout null
        accessible(source)
        val result = convert(source, message)
        selectedStillExists(source)
        result
    }

    private fun rateLimited(ex: TelegramRequestException) = ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")

    suspend fun search(input: SearchRequest): SearchPage = withTimeout(90_000) {
        require(input.limit in 1..100 && input.sourceKeys.distinct().size in 1..10) { "Выберите от 1 до 10 источников" }
        require(input.cursor.length <= 36) { "Некорректный курсор поиска" }
        val template = input.templateId?.let { repo.template(it) ?: throw ResourceNotFound() }
        require(template == null || template.active) { "Шаблон приостановлен" }
        val query = (template?.telegramQuery ?: input.query).trim()
        require(query.length in 1..256) { "Укажите запрос Telegram (до 256 символов)" }
        val keys = input.sourceKeys.distinct().sortedBy { key -> saved(key).let { "${it.chatId}:${it.scope.kind}:${it.scope.nativeId()}" } }
        val selected = keys.map(::saved)
        val initial = lock.withLock {
            val now = System.currentTimeMillis()
            cursors.entries.removeIf { it.value.expiresAt <= now }
            if (input.cursor.isBlank()) SearchState(keys, query, template, expiresAt = now + 600_000)
            else cursors[input.cursor]?.also {
                require(it.keys == keys && it.query == query && it.template == template) { "Условия изменились. Начните поиск заново" }
            } ?: throw IllegalArgumentException("Поиск устарел. Начните заново")
        }
        val positions = initial.positions.toMutableMap(); val done = initial.done.toMutableSet(); val seen = initial.seen.toMutableSet()
        val results = mutableListOf<ResearchMessage>(); val errors = mutableListOf<String>()
        var rate = false
        val active = selected.filterNot { it.key in done }.take(input.limit)
        for (source in active) {
            if (rate || seen.size >= 10_000) break
            try {
                accessible(source)
                val count = minOf((input.limit / active.size).coerceAtLeast(1), 10_000 - seen.size)
                val page = execute(TdApi.SearchChatMessages().apply {
                    chatId = source.chatId
                    topicId = when (source.scope.kind) {
                        ScopeKind.CHAT -> null
                        ScopeKind.FORUM_TOPIC -> TdApi.MessageTopicForum(source.scope.forumTopicId!!)
                        ScopeKind.MESSAGE_THREAD -> TdApi.MessageTopicThread(source.scope.messageThreadId!!)
                    }
                    this.query = query; fromMessageId = positions[source.key] ?: 0; limit = count
                }) as TdApi.FoundChatMessages
                require(page.messages.size <= count && page.messages.all { accepts(source, it) }) { "Telegram вернул сообщения другой области" }
                val previous = positions[source.key] ?: 0
                if (page.nextFromMessageId == 0L) done += source.key
                else if (page.nextFromMessageId < 0 || previous > 0 && page.nextFromMessageId >= previous)
                    errors += "Telegram не продвинула курсор источника. Повторите позже."
                else positions[source.key] = page.nextFromMessageId
                for (message in page.messages) {
                    if (!seen.add(message.chatId to message.id)) continue
                    val explanation = template?.let { matching.check(it, messageText(message.content)) }
                    if (explanation?.matches != false) results += convert(source, message,
                        withLink = input.withLinks && !rate, onRate = { rate = true }).copy(explanation = explanation)
                }
            } catch (ex: TelegramRequestException) {
                if (rateLimited(ex)) rate = true
                else errors += "Источник недоступен (${ex.code}). Сохранённые страницы доступны."
            }
        }
        selected.forEach(::selectedStillExists)
        val exhausted = done.containsAll(keys) || seen.size >= 10_000
        val token = if (exhausted) "" else lock.withLock {
            while (cursors.size >= 128) cursors.remove(cursors.keys.first())
            UUID.randomUUID().toString().also { cursors[it] = initial.copy(positions = positions, done = done, seen = seen,
                expiresAt = System.currentTimeMillis() + 600_000) }
        }
        if (rate) errors += "Telegram ограничил частоту запросов. Повторите позже."
        if (seen.size >= 10_000) errors += "Достигнут предел 10000 проверенных публикаций. Уточните запрос."
        SearchPage(results.distinctBy { it.channelId to it.id }.sortedWith(compareByDescending<ResearchMessage> { it.date }
            .thenByDescending { it.channelId }.thenByDescending { it.id }), token,
            if (rate) "rate_limited" else if (errors.isNotEmpty()) "partial" else "ok", errors.joinToString(" ").takeIf { it.isNotEmpty() })
    }
}
