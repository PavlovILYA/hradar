package dev.radar.sources

import dev.radar.history.PauseReason
import dev.radar.history.SyncStatus
import dev.radar.research.Repositories
import dev.radar.research.ResourceNotFound
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Explicit owner-local imports; no subscription, matching, notification or export side effects. */
class SourceArchiveService(
    private val repo: Repositories,
    private val fetchPage: suspend (key: String, cursor: Long) -> SourceMessagePage,
    private val isReady: () -> Boolean,
    private val pageDelayMs: Long = 350,
    private val retryDelayMs: Long = 1000,
    private val requestIntervalMs: Long = 700,
) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val control = Mutex()
    // A single worker slot bounds all selected-source imports and shares flood waits.
    private val requestGate = Mutex()
    private var lastRequestNanos = 0L
    @Volatile private var floodWaitUntil = 0L

    fun resumePending() { scope.launch { control.withLock {
        repo.sourceSyncs().filter { it.status == SyncStatus.RUNNING }.forEach { enqueue(it.sourceKey) }
    } } }
    suspend fun start(key: String, maxMessages: Int? = 1000): SourceSyncRecord = withContext(Dispatchers.IO) {
        control.withLock {
            if (jobs[key]?.isActive == true) return@withLock repo.sourceSync(key) ?: throw ResourceNotFound()
            repo.beginSourceSync(key, maxMessages).also { if (it.status == SyncStatus.RUNNING) enqueue(key) }
        }
    }
    suspend fun stop(key: String): SourceSyncRecord? = withContext(NonCancellable + Dispatchers.IO) {
        control.withLock {
            jobs[key]?.cancelAndJoin()
            val state = repo.sourceSync(key) ?: return@withLock null
            if (state.status == SyncStatus.RUNNING) repo.saveSourcePage(
                state.copy(status = SyncStatus.PAUSED, pauseReason = PauseReason.USER, error = null), emptyList()
            ) else state
        }
    }
    private fun enqueue(key: String) {
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch { runImport(key) }
    }
    private suspend fun awaitRequestSlot() {
        while (true) {
            val floodDelay = maxOf(floodWaitUntil, repo.sourceSyncs().maxOfOrNull { it.nextRetryAt } ?: 0L) - System.currentTimeMillis()
            val intervalDelay = requestIntervalMs - (System.nanoTime() - lastRequestNanos) / 1_000_000
            val wait = maxOf(floodDelay, intervalDelay)
            if (wait <= 0) break
            delay(wait)
        }
        lastRequestNanos = System.nanoTime()
    }
    internal suspend fun runImport(key: String) {
        while (currentCoroutineContext().isActive) {
            val state = repo.sourceSync(key) ?: return
            if (state.status != SyncStatus.RUNNING) return
            if (state.maxMessages != null && state.messageCount >= state.maxMessages) {
                repo.saveSourcePage(state.copy(status = SyncStatus.PAUSED, pauseReason = PauseReason.LIMIT), emptyList())
                return
            }
            if (!isReady()) { delay(2000); continue }
            val wait = state.nextRetryAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                val page = requestGate.withLock {
                    awaitRequestSlot()
                    currentCoroutineContext().ensureActive()
                    withTimeout(25_000) { fetchPage(key, state.cursor) }
                }
                // Revocation during a suspended RPC never restores its cursor or bodies.
                val archivedCount = repo.sourceSync(key)?.messageCount ?: return
                require(page.messages.size <= 100 && page.messages.all { it.channelId == state.channelId && it.id > 0 })
                val newMessages = page.messages.distinctBy { it.channelId to it.id }
                    .filter { state.cursor == 0L || it.id < state.cursor }.sortedByDescending { it.id }
                if (page.state != "ok" && newMessages.isEmpty() || page.nextCursor != null &&
                    (page.nextCursor <= 0 || state.cursor != 0L && page.nextCursor >= state.cursor)) {
                    retry(state, "Telegram не продвинула курсор. История сохранена; импорт можно продолжить.", backoff(state))
                } else if (newMessages.isEmpty() && page.nextCursor == null) {
                    repo.saveSourcePage(state.copy(status = SyncStatus.COMPLETED, error = null, retryCount = 0, nextRetryAt = 0, pauseReason = null), emptyList())
                    return
                } else {
                    val remaining = state.maxMessages?.minus(archivedCount)?.coerceAtLeast(0) ?: Int.MAX_VALUE
                    val accepted = newMessages.take(remaining)
                    val capped = state.maxMessages != null && archivedCount + accepted.size >= state.maxMessages
                    val stalled = page.state != "ok"
                    val exhausted = !stalled && page.nextCursor == null && accepted.size == newMessages.size
                    val next = if (accepted.size < newMessages.size) accepted.lastOrNull()?.id ?: state.cursor else page.nextCursor ?: accepted.lastOrNull()?.id ?: state.cursor
                    // Recent reads can make progress before a later native subpage stalls.
                    // Commit that evidence and retry from the progressed position, not the old head.
                    val attempts = if (stalled) state.retryCount + 1 else 0
                    repo.saveSourcePage(state.copy(cursor = next,
                        error = if (stalled) "Telegram не продвинула курсор. Полученные сообщения сохранены." else null,
                        retryCount = attempts, nextRetryAt = if (stalled) System.currentTimeMillis() + backoff(state) else 0,
                        status = when { capped -> SyncStatus.PAUSED; exhausted -> SyncStatus.COMPLETED; attempts >= 3 -> SyncStatus.FAILED; else -> SyncStatus.RUNNING },
                        pauseReason = if (capped) PauseReason.LIMIT else null), accepted)
                    if (capped || exhausted || attempts >= 3) return
                    delay(pageDelayMs)
                }
            } catch (ex: CancellationException) {
                if (ex !is TimeoutCancellationException) throw ex
                retry(state, "Таймаут Telegram. Курсор сохранён; повторим запрос.", backoff(state))
            } catch (ex: TelegramRequestException) {
                val limited = ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT")
                if (!limited && ex.code in setOf(400, 401, 403, 404)) {
                    repo.saveSourcePage(state.copy(status = SyncStatus.FAILED,
                        error = "Источник недоступен в Telegram. История и курсор сохранены."), emptyList())
                    return
                }
                val seconds = if (limited) Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE)
                    .find(ex.reason)?.groupValues?.get(1)?.toLongOrNull()?.coerceAtLeast(1) ?: 60L else 0L
                val now = System.currentTimeMillis()
                val retryAt = if (limited) now + seconds.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000 else null
                if (retryAt != null) floodWaitUntil = maxOf(floodWaitUntil, retryAt)
                retry(state, if (limited) "Лимит Telegram. Ожидаем разрешённого времени повтора." else "Telegram временно недоступен. Курсор сохранён.",
                    backoff(state), retryAt)
            } catch (_: IOException) {
                retry(state, "Сетевая ошибка. Курсор сохранён; повторим запрос.", backoff(state))
            } catch (_: ResourceNotFound) {
                return
            } catch (_: Exception) {
                repo.saveSourcePage(state.copy(status = SyncStatus.FAILED,
                    error = "Не удалось импортировать выбранную область. Курсор сохранён; попробуйте продолжить."), emptyList())
                return
            }
        }
    }
    private fun backoff(state: SourceSyncRecord) = retryDelayMs * (1L shl state.retryCount.coerceAtMost(3))
    private fun retry(state: SourceSyncRecord, error: String, waitMs: Long, retryAt: Long? = null) {
        val attempts = state.retryCount + 1
        repo.saveSourcePage(state.copy(status = if (attempts >= 3) SyncStatus.FAILED else SyncStatus.RUNNING,
            retryCount = attempts, error = error, nextRetryAt = retryAt ?: (System.currentTimeMillis() + waitMs)), emptyList())
    }
    override fun close() { runBlocking { supervisor.cancelAndJoin() } }
}
