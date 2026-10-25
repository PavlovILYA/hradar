package dev.radar.research

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable private data class LocalArchiveCursor(val keys: List<String>, val query: String, val positions: Map<String, Long> = emptyMap(), val done: Set<String> = emptySet())

/** Owner-local project reads. The public recruiter port and external adapters remain separate. */
class LocalProjectSearchService(private val repo: Repositories, private val search: RecruiterSearchPort) {
    private val json = Json { encodeDefaults = true }
    suspend fun find(criteria: ProjectInput, query: String, mode: String, cursor: String, limit: Int): SearchPage {
        require(limit in 1..10 && query.length in 1..256 && cursor.length <= 10000)
        val saved = repo.sources()
        val keys = (criteria.sourceKeys + criteria.sources.map { id -> saved.firstOrNull { it.legacyChannel && it.chatId == id }?.key ?: throw ResourceNotFound() }).distinct().sorted()
        require(keys.size in 1..10 && keys.all { key -> saved.any { it.key == key } }) { "Источник удалён. Обновите проект" }
        if (mode == "telegram") return search.search(SearchRequest(mode = "sources", sourceKeys = keys, query = query, cursor = cursor, limit = limit, withLinks = false))
        require(mode == "archive")
        val state = if (cursor.isEmpty()) LocalArchiveCursor(keys, query) else try {
            json.decodeFromString<LocalArchiveCursor>(String(Base64.getUrlDecoder().decode(cursor)))
        } catch (_: Exception) { throw IllegalArgumentException("Некорректный курсор архива") }
        require(state.keys == keys && state.query == query && state.positions.keys.all { it in keys } && state.done.all { it in keys } && state.positions.values.all { it > 0 }) { "Параметры поиска изменились" }
        val positions = state.positions.toMutableMap(); val done = state.done.toMutableSet()
        val messages = mutableListOf<ResearchMessage>()
        val active = keys.filterNot { it in done }.take(limit)
        for (key in active) {
            val page = repo.sourceArchive(key, (limit / active.size).coerceAtLeast(1), positions[key], query)
            messages.addAll(page.messages)
            if (page.nextCursor == null) done.add(key) else positions[key] = page.nextCursor
        }
        require(repo.sources().filter { it.key in keys } == saved.filter { it.key in keys }) { "Источники изменились. Начните заново" }
        val next = if (done.containsAll(keys)) "" else Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(state.copy(positions = positions, done = done)).toByteArray())
        return SearchPage(messages.distinctBy { it.channelId to it.id }.sortedWith(compareByDescending<ResearchMessage> { it.date }.thenBy { it.channelId }.thenByDescending { it.id }), next)
    }
}
