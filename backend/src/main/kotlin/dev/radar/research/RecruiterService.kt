package dev.radar.research

import dev.radar.bot.verifiedTelegramLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/** A narrow port backed by the existing SearchService, never a generic TDLib executor. */
fun interface RecruiterSearchPort { suspend fun search(request: SearchRequest): SearchPage }
@Serializable private data class ArchiveCursor(val query: String, val sources: List<Long>, val positions: Map<Long, Long> = emptyMap(), val done: Set<Long> = emptySet())
@Serializable data class SavedResult(val id: String, val status: String, val message: ResearchMessage, val sequence: Long, val updatedAt: Long)
@Serializable data class SavedResultsPage(val items: List<SavedResult>, val nextCursor: Long? = null)
@Serializable data class MatchDigest(val total: Int, val unread: Int, val matches: List<NotificationRecord>, val nextCursor: Long? = null)

class RecruiterService(private val repo: Repositories, private val search: RecruiterSearchPort) {
    private val json = Json { encodeDefaults = true }
    suspend fun find(query: String, mode: String, sources: List<Long>, cursor: String = "", limit: Int = 10, withLinks: Boolean = true): SearchPage {
        require(query.trim().length in 1..256 && limit in 1..10 && cursor.length <= 10000)
        require(mode in setOf("telegram", "archive"))
        val channels = repo.channels().filter { it.id in sources }.sortedBy { it.id }
        require(sources.distinct().size in 1..10 && channels.size == sources.distinct().size)
        require(channels.all { it.id < 0 && it.username.matches(Regex("[a-zA-Z0-9_]{5,32}")) })
        val result = if (mode == "telegram") search.search(SearchRequest(mode = "tracked", query = query, usernames = channels.map { it.username }, limit = limit, cursor = cursor, withLinks = withLinks))
        else {
            val ids = channels.map { it.id }
            val state = if (cursor.isEmpty()) ArchiveCursor(query, ids) else try {
                json.decodeFromString<ArchiveCursor>(String(Base64.getUrlDecoder().decode(cursor)))
            } catch (_: Exception) { throw IllegalArgumentException("Некорректный курсор архива") }
            require(state.query == query && state.sources == ids && state.positions.keys.all { it in ids } && state.done.all { it in ids } && state.positions.values.all { it > 0 })
            val positions = state.positions.toMutableMap(); val done = state.done.toMutableSet()
            val messages = mutableListOf<ResearchMessage>()
            val active = channels.filterNot { it.id in done }.take(limit)
            for (channel in active) {
                // Reuse archive pagination and literal normalized substring search; no Telegram calls.
                val page = repo.historyMessages(channel.id, (limit / active.size).coerceAtLeast(1), positions[channel.id], query)
                page.messages.forEach { messages.add(ResearchMessage(it.id, channel.id, it.date, it.text, channel.title, channel.username)) }
                if (page.nextCursor == null) done.add(channel.id) else positions[channel.id] = page.nextCursor
            }
            val next = if (done.containsAll(ids)) "" else Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(state.copy(positions = positions, done = done)).toByteArray())
            SearchPage(messages, next, notice = "Локальный архив SQLite: поиск буквальной фразы, без импорта. Ссылка на оригинал — при доступном Telegram.")
        }
        require(result.messages.size <= limit && result.messages.all { it.channelId in sources && it.id > 0 })
        // Recheck sources after network suspension; a removed source cannot leak its result.
        require(repo.channels().filter { it.id in sources }.sortedBy { it.id } == channels) { "Источники изменились; начните поиск заново" }
        return result.copy(messages = result.messages.distinctBy { it.channelId to it.id }.map { it.copy(text = it.text?.take(4000), link = verifiedTelegramLink(it.link)) })
    }
    fun decide(botId: Long, user: Long, message: ResearchMessage, status: String) = repo.decidePublication(botId, user, message, status)
    fun saved(botId: Long, user: Long, before: Long = Long.MAX_VALUE) = repo.savedResults(10, before, botId, user, status = "SAVED")
}
