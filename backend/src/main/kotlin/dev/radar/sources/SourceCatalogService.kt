package dev.radar.sources

import dev.radar.research.Repositories
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class SourceCatalogService(private val port: SourceCatalogPort, private val repo: Repositories, private val now: () -> Long = System::currentTimeMillis) {
    private data class Snapshot(val ids: List<Long>, val offset: Int, val query: String, val filter: String, val expires: Long)
    private val pages = linkedMapOf<String, Snapshot>()
    private val lock = Mutex()
    suspend fun browse(query: String = "", filter: String = "all", cursor: String? = null): SourcePage = withTimeout(25_000) {
        require(query.length <= 128 && filter in setOf("all", "private", "groups", "channels") && (cursor == null || cursor.length <= 36)) { "Некорректный запрос каталога" }
        lock.withLock {
            pages.entries.removeIf { it.value.expires <= now() }
            val page = if (cursor != null) pages[cursor]?.takeIf { it.query == query && it.filter == filter }
                ?: throw IllegalArgumentException("Каталог устарел. Обновите список") else Snapshot(port.chatIds(query.trim()), 0, query, filter, now() + 180_000)
            val result = mutableListOf<TelegramSource>()
            var offset = page.offset
            var scanned = 0
            while (offset < page.ids.size && result.size < 25 && scanned++ < 50) {
                val id = page.ids[offset++]
                val source = try { port.describe(id) } catch (ex: TelegramRequestException) {
                    if (ex.code !in setOf(403, 404)) throw ex
                    null
                } ?: continue
                val accepts = when (filter) { "private" -> source.type == SourceType.PRIVATE; "groups" -> source.type in setOf(SourceType.BASIC_GROUP, SourceType.SUPERGROUP); "channels" -> source.type == SourceType.CHANNEL; else -> true }
                if (accepts) result += source
            }
            val next = if (offset < page.ids.size) UUID.randomUUID().toString().also {
                while (pages.size >= 32) pages.remove(pages.keys.first())
                pages[it] = page.copy(offset = offset)
            } else null
            SourcePage(result, next, "Показана ограниченная выборка загруженных чатов. Используйте поиск для остальных; обновление загружает следующую порцию из Telegram.")
        }
    }
    suspend fun save(input: SaveSourceInput): TelegramSource = withTimeout(25_000) {
        input.scope.validate()
        require(input.chatId != 0L) { "Укажите чат" }
        val source = when (input.scope.kind) {
            ScopeKind.CHAT -> port.describe(input.chatId) ?: throw IllegalArgumentException("Этот тип чата не поддерживается")
            ScopeKind.FORUM_TOPIC -> port.topic(input.chatId, input.scope.forumTopicId!!)
            ScopeKind.MESSAGE_THREAD -> throw IllegalArgumentException("Ветки обсуждений будут подключены на следующем этапе")
        }
        require(source.access != SourceAccess.UNAVAILABLE) { "Telegram сообщил об отсутствии доступа" }
        require(source.chatId == input.chatId && source.scope == input.scope) { "Telegram вернул другую область" }
        return@withTimeout withContext(Dispatchers.IO) { repo.saveSource(source.copy(checkedAt = now())) }
    }
    suspend fun refresh(key: String): TelegramSource = withTimeout(25_000) {
        val saved = withContext(Dispatchers.IO) { repo.sources().firstOrNull { it.key == key } } ?: throw dev.radar.research.ResourceNotFound()
        require(!saved.legacyChannel) { "Публичные подписки управляются в существующем интерфейсе" }
        val current = try {
            when (saved.scope.kind) {
                ScopeKind.CHAT -> port.describe(saved.chatId)
                ScopeKind.FORUM_TOPIC -> port.topic(saved.chatId, saved.scope.forumTopicId!!)
                ScopeKind.MESSAGE_THREAD -> throw IllegalArgumentException("Ветки обсуждений ещё не подключены")
            }
        } catch (ex: TelegramRequestException) { if (ex.code !in setOf(403, 404)) throw ex; null }
        return@withTimeout withContext(Dispatchers.IO) { repo.refreshSource(key, (current ?: saved.copy(access = SourceAccess.UNAVAILABLE)).copy(checkedAt = now())) }
    }
    suspend fun topics(chatId: Long, cursor: TopicCursor): TopicPage {
        require(chatId != 0L && cursor.date >= 0 && cursor.messageId >= 0 && cursor.forumTopicId >= 0) { "Некорректная страница тем" }
        return withTimeout(20_000) { port.topics(chatId, cursor) }
    }
}
