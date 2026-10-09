package dev.radar.history

import dev.radar.research.MonitoredChannel
import dev.radar.research.Repositories
import dev.radar.telegram.TelegramMessage
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
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
) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<Long, Job>()

    fun resumePending() { scope.launch { repo.historySyncs().filter { it.status == SyncStatus.RUNNING }.forEach { enqueue(it.channelId) } } }
    suspend fun start(channel: MonitoredChannel): HistorySyncRecord = withContext(Dispatchers.IO) {
        repo.beginHistorySync(channel).also { if (it.status == SyncStatus.RUNNING) enqueue(it.channelId) }
    }
    @Synchronized private fun enqueue(id: Long) {
        if (jobs[id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) { permits.withPermit { runImport(id) } }
        jobs[id] = job
        job.invokeOnCompletion { synchronized(this) { if (jobs[id] === job) jobs.remove(id) } }
        job.start()
    }
    internal suspend fun runImport(id: Long) {
        while (currentCoroutineContext().isActive) {
            val state = repo.historySync(id) ?: return
            if (state.status != SyncStatus.RUNNING) return
            if (!isReady()) { delay(2000); continue }
            val wait = state.nextRetryAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                val page = withTimeout(25_000) { fetchPage(id, state.cursor) }
                require(page.size <= 100 && page.all { it.channelId == id && it.id > 0 }) { "Unexpected TDLib history response" }
                if (page.isEmpty()) {
                    repo.saveHistoryPage(state.copy(status = SyncStatus.COMPLETED, error = null, retryCount = 0, nextRetryAt = 0), emptyList())
                    return
                }
                val oldest = page.minOf { it.id }
                if (state.cursor != 0L && oldest >= state.cursor) {
                    retry(state, "TDLib не продвинула курсор. Данные сохранены; импорт можно продолжить.", retryDelayMs * (1L shl state.retryCount.coerceAtMost(3)), page)
                } else {
                    // Short pages and inclusive cursor messages are normal. Only an empty
                    // successful response ends the import. Page and cursor commit atomically.
                    repo.saveHistoryPage(state.copy(cursor = oldest, error = null, retryCount = 0, nextRetryAt = 0), page.distinctBy { it.id })
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
