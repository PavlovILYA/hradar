package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import dev.radar.telegram.TelegramStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicLong

@Serializable data class NotificationSummary(val unread: Int, val revision: Long, val byChannel: Map<Long, Int>, val telegram: TelegramStatus? = null)
@Serializable data class NotificationPage(val items: List<NotificationRecord>, val unread: Int, val cursor: Long?)

class NotificationService(private val repo: Repositories, private val matching: MatchingService) {
    private val revision = AtomicLong()
    fun summary() = NotificationSummary(repo.unreadCount(), revision.get(), repo.unreadByChannel())
    fun page(limit: Int, before: Long): NotificationPage {
        val items = repo.notifications(limit, before)
        return NotificationPage(items, repo.unreadCount(), items.lastOrNull()?.sequence?.takeIf { items.size == limit })
    }
    fun read(id: String) { if (!repo.markRead(id)) throw ResourceNotFound(); revision.incrementAndGet() }
    fun readAll() { repo.markAllRead(); revision.incrementAndGet() }
    fun process(channel: MonitoredChannel, message: ResearchMessage): Int {
        var count = 0
        repo.templates().filter { it.active && it.subscribed && message.date >= it.subscriptionSince && it.sourceMode != "global" && (it.sourceMode == "all" || channel.id in it.sources) }.forEach { template ->
            val explanation = matching.check(template, message.text)
            if (explanation.matches && repo.recordMatch(template, message.copy(explanation = explanation))) count++
        }
        if (count > 0) revision.incrementAndGet()
        return count
    }
}

/** Callbacks enqueue only; IO and matching never run on TDLib's update thread. */
class MonitoringService(
    private val telegram: TdlibGateway, private val repo: Repositories,
    private val search: SearchService, private val notifications: NotificationService,
    private val history: suspend (Long, Long, Int) -> TdApi.Messages = { id, cursor, limit ->
        telegram.executeRead(TdApi.GetChatHistory(id, cursor, 0, limit, false)) as TdApi.Messages
    },
    private val ready: () -> Boolean = { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } },
    private val requestIntervalMs: Long = 700,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val updates = Channel<TdApi.Message>(256)
    private val serial = Mutex()
    @Volatile private var knownIds: Set<Long> = emptySet()
    private var cooldownUntil = 0L
    private var lastRequestNanos = 0L
    fun start() {
        knownIds = repo.channels().map { it.id }.toSet()
        telegram.messageListener = { message -> if (message.chatId in knownIds) updates.trySend(message) }
        scope.launch {
            for (message in updates) serial.withLock {
                try {
                    processUpdate(message)
                    // Do not advance the recovery checkpoint on an individual live update.
                } catch (ex: CancellationException) { throw ex }
                catch (_: Exception) { /* Durable recovery below retries missing updates. */ }
            }
        }
        scope.launch {
            while (isActive) {
                if (!ready()) { delay(2000); continue }
                pollOnce()
                delay(60_000)
            }
        }
    }
    internal suspend fun pollOnce() {
        if (!ready()) return
        cooldownUntil = maxOf(cooldownUntil, repo.channels().maxOfOrNull { repo.state(it.id)?.retryAt ?: 0L } ?: 0L)
        if (System.currentTimeMillis() >= cooldownUntil) serial.withLock {
            knownIds = repo.channels().map { it.id }.toSet()
            val templates = repo.templates().filter { it.active && it.subscribed && it.sourceMode != "global" }
            for (channel in repo.channels().filter { c -> templates.any { it.sourceMode == "all" || c.id in it.sources } }) {
                if (System.currentTimeMillis() < cooldownUntil) break
                try { recover(channel) }
                catch (ex: CancellationException) { throw ex }
                catch (ex: Exception) {
                    val state = repo.state(channel.id) ?: MonitoringState(channel.id)
                    if (ex is TelegramRequestException && (ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT"))) {
                        val seconds = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(ex.reason)?.groupValues?.get(1)?.toLongOrNull() ?: 120L
                        cooldownUntil = System.currentTimeMillis() + seconds.coerceAtLeast(1L) * 1000
                    }
                    repo.saveState(state.copy(error = if (ex is TelegramRequestException) "Telegram ${ex.code}: источник временно недоступен" else "Не удалось восстановить историю; повторим позже", retryAt = cooldownUntil))
                }
            }
        }
    }
    private suspend fun readHistory(id: Long, cursor: Long, limit: Int): TdApi.Messages {
        val wait = requestIntervalMs - (System.nanoTime() - lastRequestNanos) / 1_000_000
        if (wait > 0) delay(wait)
        lastRequestNanos = System.nanoTime()
        return history(id, cursor, limit)
    }
    internal suspend fun processUpdate(message: TdApi.Message) {
        val channel = repo.channels().find { it.id == message.chatId } ?: return
        val state = repo.state(channel.id) ?: return
        if (message.id > state.lastMessageId) notifications.process(channel, search.enrich(message, channel, withLink = false))
    }
    internal suspend fun recover(channel: MonitoredChannel) {
        var state = repo.state(channel.id)
        if (state == null) {
            // New sources start now. Never notify about their entire old history.
            val latest = readHistory(channel.id, 0L, 1)
            repo.saveState(MonitoringState(channel.id, latest.messages.firstOrNull()?.id ?: 0L))
            return
        }
        repeat(4) {
            val page = readHistory(channel.id, state!!.recoveryCursor, 100)
            val head = state!!.recoveryHead.takeIf { it != 0L } ?: page.messages.firstOrNull()?.id ?: state!!.lastMessageId
            for (message in page.messages.filter { it.id > state!!.lastMessageId }.asReversed()) {
                // Store only matching publications; no full history copy.
                notifications.process(channel, search.enrich(message, channel, withLink = false))
            }
            val last = page.messages.lastOrNull()?.id ?: 0L
            val complete = page.messages.isEmpty() || last <= state!!.lastMessageId
            if (!complete && last == state!!.recoveryCursor) {
                repo.saveState(state!!.copy(error = "История пока не продвинулась; восстановление продолжится позже"))
                return
            }
            state = if (complete) MonitoringState(channel.id, maxOf(state!!.lastMessageId, head)) else state!!.copy(recoveryCursor = last, recoveryHead = head, error = null)
            repo.saveState(state!!)
            if (complete) return
        }
        // The durable cursor continues next minute (or after a restart).
    }
    override fun close() {
        telegram.messageListener = null
        runBlocking { job.cancelAndJoin() }
        updates.close()
    }
}
