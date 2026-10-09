package dev.radar.history

import dev.radar.research.MonitoredChannel
import dev.radar.research.Repositories
import dev.radar.telegram.TelegramMessage
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** Explicit imports only. Matching can later consume durable history pages separately. */
class HistorySyncService(
    private val repo: Repositories,
    private val fetchPage: suspend (channelId: Long, cursor: Long) -> List<TelegramMessage>,
    private val isReady: () -> Boolean,
    private val pageDelayMs: Long = 350,
    private val retryDelayMs: Long = 1000,
    private val requestIntervalMs: Long = 700,
) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<Long, Job>()

    private val control = Mutex()
    private val requestGate = Mutex()
    private var lastRequestNanos = 0L
    @Volatile private var floodWaitUntil = 0L

    fun resumePending() { scope.launch { control.withLock {
        repo.historySyncs().filter { it.status == SyncStatus.RUNNING }.forEach { enqueue(it.channelId) }
    } } }
    suspend fun start(channel: MonitoredChannel, maxMessages: Int? = 1000): HistorySyncRecord = withContext(Dispatchers.IO) {
        control.withLock {
            if (jobs[channel.id]?.isActive == true) return@withLock repo.historySync(channel.id)!!
            repo.beginHistorySync(channel, maxMessages).also { if (it.status == SyncStatus.RUNNING) enqueue(it.channelId) }
        }
    }
    suspend fun stop(id: Long): HistorySyncRecord? = withContext(NonCancellable + Dispatchers.IO) {
        control.withLock {
            jobs[id]?.cancelAndJoin()
            val state = repo.historySync(id) ?: return@withLock null
            if (state.status == SyncStatus.RUNNING) repo.saveHistoryPage(
                state.copy(status = SyncStatus.PAUSED, pauseReason = PauseReason.USER, error = null), emptyList()
            ) else state
        }
    }
    // Called only under control. Completed jobs stay in the map until the next
    // start, avoiding completion callbacks racing a concurrent stop/start.
    private fun enqueue(id: Long) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch { permits.withPermit { runImport(id) } }
    }
    private suspend fun awaitRequestSlot() = requestGate.withLock {
        while (true) {
            val floodDelay = maxOf(floodWaitUntil, repo.historySyncs().maxOfOrNull { it.nextRetryAt } ?: 0L) - System.currentTimeMillis()
            val intervalDelay = requestIntervalMs - (System.nanoTime() - lastRequestNanos) / 1_000_000
            val wait = maxOf(floodDelay, intervalDelay)
            if (wait <= 0) break
            delay(wait)
        }
        lastRequestNanos = System.nanoTime()
    }
    internal suspend fun runImport(id: Long) {
        while (currentCoroutineContext().isActive) {
            val state = repo.historySync(id) ?: return
            if (state.status != SyncStatus.RUNNING) return
            if (state.maxMessages != null && state.messageCount >= state.maxMessages) {
                repo.saveHistoryPage(state.copy(status = SyncStatus.PAUSED, pauseReason = PauseReason.LIMIT), emptyList())
                return
            }
            if (!isReady()) { delay(2000); continue }
            val wait = state.nextRetryAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                awaitRequestSlot()
                currentCoroutineContext().ensureActive()
                val page = withTimeout(25_000) { fetchPage(id, state.cursor) }
                require(page.size <= 100 && page.all { it.channelId == id && it.id > 0 }) { "Unexpected TDLib history response" }
                if (page.isEmpty()) {
                    repo.saveHistoryPage(state.copy(status = SyncStatus.COMPLETED, error = null, retryCount = 0, nextRetryAt = 0, pauseReason = null), emptyList())
                    return
                }
                val oldest = page.minOf { it.id }
                if (state.cursor != 0L && oldest >= state.cursor) {
                    retry(state, "TDLib не продвинула курсор. Данные сохранены; импорт можно продолжить.", retryDelayMs * (1L shl state.retryCount.coerceAtMost(3)), page)
                } else {
                    // Short pages and inclusive cursor messages are normal. Only an empty
                    // successful response ends the import. Page and cursor commit atomically.
                    val newMessages = page.distinctBy { it.id }.filter { state.cursor == 0L || it.id < state.cursor }.sortedByDescending { it.id }
                    val remaining = state.maxMessages?.minus(state.messageCount) ?: Int.MAX_VALUE
                    val accepted = newMessages.take(remaining)
                    val capped = state.maxMessages != null && state.messageCount + accepted.size >= state.maxMessages
                    repo.saveHistoryPage(state.copy(cursor = accepted.last().id, error = null, retryCount = 0, nextRetryAt = 0,
                        status = if (capped) SyncStatus.PAUSED else SyncStatus.RUNNING,
                        pauseReason = if (capped) PauseReason.LIMIT else null), accepted)
                    if (capped) return
                    delay(pageDelayMs)
                }
            } catch (ex: CancellationException) {
                if (ex !is TimeoutCancellationException) throw ex
                retry(state, "Таймаут Telegram. Курсор сохранён; повторим запрос.", retryDelayMs * (1L shl state.retryCount.coerceAtMost(3)))
            } catch (ex: TelegramRequestException) {
                val rateLimited = ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")
                if (!rateLimited && ex.code in setOf(400, 401, 403, 404)) {
                    repo.saveHistoryPage(state.copy(status = SyncStatus.FAILED, error = "Telegram ${ex.code}: канал недоступен. История и курсор сохранены."), emptyList())
                    return
                }
                val seconds = if (rateLimited) Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(ex.reason)?.groupValues?.get(1)?.toLongOrNull() ?: 60L else 0L
                if (rateLimited) floodWaitUntil = maxOf(floodWaitUntil, System.currentTimeMillis() + seconds.coerceAtLeast(1) * 1000)
                retry(state, if (rateLimited) "Лимит Telegram. Ожидаем разрешённого времени повтора." else "Telegram временно недоступен. Курсор сохранён.", if (rateLimited) seconds.coerceAtLeast(1) * 1000 else retryDelayMs * (1L shl state.retryCount.coerceAtMost(3)))
            } catch (_: IOException) {
                retry(state, "Сетевая ошибка. Курсор сохранён; повторим запрос.", retryDelayMs * (1L shl state.retryCount.coerceAtMost(3)))
            } catch (_: Exception) {
                repo.saveHistoryPage(state.copy(status = SyncStatus.FAILED, error = "Не удалось импортировать историю. Курсор сохранён; попробуйте продолжить."), emptyList())
                return
            }
        }
    }
    private fun retry(state: HistorySyncRecord, error: String, waitMs: Long, messages: List<TelegramMessage> = emptyList()) {
        val attempts = state.retryCount + 1
        repo.saveHistoryPage(state.copy(status = if (attempts >= 3) SyncStatus.FAILED else SyncStatus.RUNNING, retryCount = attempts, error = error, nextRetryAt = System.currentTimeMillis() + waitMs), messages)
    }
    override fun close() { runBlocking { supervisor.cancelAndJoin() } }
}
