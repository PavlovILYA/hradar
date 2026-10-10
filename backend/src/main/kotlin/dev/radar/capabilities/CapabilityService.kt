package dev.radar.capabilities

import dev.radar.research.*
import dev.radar.telegram.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private class AccessDenied : RuntimeException()

/** One domain implementation shared by MCP, REST and integration UI. No mutating Telegram operation. */
class CapabilityService(private val telegram: TelegramReadPort, private val repo: Repositories) {
    private val json = Json { encodeDefaults = true }
    private val requests = Semaphore(4)
    fun status() = telegram.status().let { StatusDto(it.authorizationState, it.authorized, it.connectionState) }
    private inline fun <reified T> decode(input: JsonObject): T = json.decodeFromJsonElement(input)
    private inline fun <reified T> objectOf(value: T) = json.encodeToJsonElement(value).jsonObject
    private fun checkLimit(limit: Int) { require(limit in 1..50) }
    private fun channel(input: String): MonitoredChannel {
        require(input.length in 1..64)
        val clean = input.removePrefix("@").lowercase()
        return repo.mcpSources().firstOrNull { it.id.toString() == clean || it.username.lowercase() == clean } ?: throw AccessDenied()
    }
    private fun dto(channel: MonitoredChannel) = ChatDto(channel.id, channel.username, channel.title)
    private fun message(value: TelegramMessage, channel: MonitoredChannel) = MessageDto(value.id, value.channelId, value.date, value.text?.take(2000), (value.text?.length ?: 0) > 2000, channel.title, channel.username)
    private fun message(value: ResearchMessage, channel: MonitoredChannel) = MessageDto(value.id, value.channelId, value.date, value.text?.take(2000), (value.text?.length ?: 0) > 2000, channel.title, channel.username, value.link?.takeIf { it.startsWith("https://t.me/") })
    suspend fun call(name: String, arguments: JsonObject): CapabilityResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = try {
            if (!repo.mcpEnabled()) return@withContext failure("DISABLED", "MCP capabilities are disabled by the user")
            val grants = repo.mcpSources()
            val data = withTimeout(30_000) { requests.withPermit {
                when (name) {
                    "get_telegram_status" -> { require(arguments.isEmpty()); objectOf(status()) }
                    "list_chats" -> {
                        val input = decode<ListInput>(arguments); checkLimit(input.limit); require(input.cursor in 0..10000)
                        val all = repo.mcpSources(); val page = all.drop(input.cursor).take(input.limit)
                        objectOf(ChatPage(page.map(::dto), (input.cursor + page.size).takeIf { it < all.size }))
                    }
                    "resolve_chat" -> {
                        val input = decode<ChatInput>(arguments); val allowed = channel(input.chat)
                        val resolved = telegram.resolve(allowed.username)
                        if (resolved.id != allowed.id) throw AccessDenied()
                        objectOf(dto(resolved))
                    }
                    "get_chat_history" -> {
                        val input = decode<HistoryInput>(arguments); checkLimit(input.limit); require(input.cursor >= 0)
                        val allowed = channel(input.chat); var cursor = input.cursor; var ended = false
                        val items = linkedMapOf<Long, TelegramMessage>()
                        for (attempt in 0 until 10) {
                            val page = telegram.history(allowed.id, cursor)
                            require(page.size <= 100 && page.all { it.channelId == allowed.id && it.id > 0 })
                            if (page.isEmpty()) { ended = true; break }
                            page.filter { input.cursor == 0L || it.id < input.cursor }.sortedByDescending { it.id }.forEach { items.putIfAbsent(it.id, it) }
                            val next = page.minOf { it.id }
                            if (items.size > input.limit) break
                            if (next == cursor || (cursor != 0L && next > cursor)) break
                            cursor = next
                            delay(350)
                        }
                        val page = items.values.sortedByDescending { it.id }.take(input.limit)
                        objectOf(MessagesDto(page.map { message(it, allowed) }, page.lastOrNull()?.id?.takeIf { !ended || items.size > input.limit }))
                    }
                    "search_chat_messages" -> {
                        val input = decode<SearchInput>(arguments); checkLimit(input.limit); require(input.query.isNotBlank() && input.query.length <= 256 && input.cursor.length <= 10000)
                        val allowed = channel(input.chat)
                        val page = telegram.search(allowed.username, input.query, input.limit, input.cursor)
                        require(page.messages.size <= input.limit && page.messages.all { it.channelId == allowed.id })
                        objectOf(SearchDto(page.messages.map { message(it, allowed) }, page.cursor.takeIf { it.isNotEmpty() }, page.state, page.notice))
                    }
                    "get_message_context" -> {
                        val input = decode<ContextInput>(arguments); require(input.messageId > 0 && input.neighbors in 0..10)
                        val allowed = channel(input.chat); val selected = telegram.message(allowed, input.messageId)
                        require(selected.channelId == allowed.id && selected.id == input.messageId)
                        val neighbors = if (input.neighbors == 0) emptyList() else telegram.neighbors(allowed, input.messageId, input.neighbors)
                        require(neighbors.all { it.channelId == allowed.id })
                        objectOf(ContextDto(message(selected, allowed), neighbors.distinctBy { it.id }.filter { it.id != input.messageId }.take(input.neighbors * 2).map { message(it, allowed) }))
                    }
                    "search_local_archive" -> {
                        val input = decode<ArchiveInput>(arguments); checkLimit(input.limit); require(input.query.length <= 256 && (input.before == null || input.before > 0))
                        val allowed = channel(input.chat)
                        val page = repo.historyMessages(allowed.id, input.limit, input.before, input.query)
                        objectOf(MessagesDto(page.messages.map { message(it, allowed) }, page.nextCursor, "sqlite", page.total))
                    }
                    "list_monitoring_rules" -> {
                        val input = decode<ListInput>(arguments); checkLimit(input.limit); require(input.cursor in 0..10000)
                        val ids = repo.mcpSources().map { it.id }
                        val all = repo.templates().filter { it.sourceMode != "global" && ids.isNotEmpty() && (it.sourceMode == "all" || it.sources.any { id -> id in ids }) }
                        val page = all.drop(input.cursor).take(input.limit).map { RuleDto(it.id, it.name, it.required, it.any, it.excluded, it.active, it.subscribed, if (it.sourceMode == "all") ids else it.sources.filter { id -> id in ids }) }
                        objectOf(RulePage(page, (input.cursor + page.size).takeIf { it < all.size }))
                    }
                    "prepare_message" -> {
                        val input = decode<DraftInput>(arguments); require(input.text.isNotBlank() && input.text.length <= 4096)
                        objectOf(DraftDto(dto(channel(input.chat)), input.text))
                    }
                    else -> throw UnsupportedOperationException()
                }
            } }
            if (!repo.mcpEnabled() || repo.mcpSources() != grants) failure("ACCESS_CHANGED", "Permissions changed; retry after reviewing access")
            else CapabilityResult(true, data).also { require(json.encodeToString(it).toByteArray().size <= 256 * 1024) }
        } catch (_: AccessDenied) { failure("ACCESS_DENIED", "Chat is not explicitly allowed; private chats are unsupported") }
          catch (_: TimeoutCancellationException) { failure("TIMEOUT", "Operation timed out; no changes were made") }
          catch (ex: CancellationException) { throw ex }
          catch (ex: TelegramRequestException) {
              val rate = ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")
              failure(if (rate) "RATE_LIMITED" else "TELEGRAM_ERROR", if (rate) "Telegram requires waiting before retry" else "Telegram could not read the requested data", if (rate) Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(ex.reason)?.groupValues?.get(1)?.toLongOrNull() ?: 60 else null)
          } catch (_: IllegalArgumentException) { failure("INVALID_ARGUMENTS", "Invalid arguments or response size; follow the tool schema") }
          catch (_: UnsupportedOperationException) { failure("UNSUPPORTED", "Operation is not registered or permitted") }
          catch (_: IllegalStateException) { failure("NOT_READY", "Telegram is not ready") }
          catch (_: Exception) { failure("UNAVAILABLE", "Operation is temporarily unavailable") }
        runCatching { repo.auditMcp(name.take(64), result.error?.code ?: "OK", (System.nanoTime() - started) / 1_000_000) }
            .onFailure { org.slf4j.LoggerFactory.getLogger(CapabilityService::class.java).warn("MCP audit storage unavailable") }
        result
    }
    private fun failure(code: String, message: String, retry: Long? = null) = CapabilityResult(false, error = CapabilityError(code, message, retry))
}
