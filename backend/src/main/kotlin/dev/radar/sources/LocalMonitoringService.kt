package dev.radar.sources

import dev.radar.research.MatchingService
import dev.radar.research.Repositories
import dev.radar.research.ResearchMessage
import dev.radar.research.ResourceNotFound
import dev.radar.research.SearchTemplate
import dev.radar.telegram.TelegramRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit owner-local monitoring. Native callbacks enqueue only; writes never reach public adapters. */
class LocalMonitoringService(
    private val repo: Repositories,
    private val reader: SourceReadService,
    private val ready: () -> Boolean,
    private val requestIntervalMs: Long = 700,
    private val pollIntervalMs: Long = 60_000,
    private val readRecent: suspend (String, Long, Int) -> SourceMessagePage = reader::recent,
    private val observe: suspend (String, TdApi.Message) -> ResearchMessage? = reader::observe,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val updates = Channel<TdApi.Message>(256)
    private val serial = Mutex()
    private val requestGate = Mutex()
    private val started = AtomicBoolean()
    private val matching = MatchingService()
    @Volatile private var knownChatIds: Set<Long> = emptySet()
    private var lastRequestNanos = 0L

    private fun source(key: String) = repo.sources().firstOrNull { it.key == key } ?: throw ResourceNotFound()
    private fun refreshKnownIds() {
        val keys = repo.localSubscriptions().filter { it.enabled }.map { it.sourceKey }.toSet()
        knownChatIds = repo.sources().filter { it.key in keys }.map { it.chatId }.toSet()
    }
    fun list(key: String? = null): List<LocalSubscription> {
        if (key != null) { require(key.length in 1..36) { "Некорректный источник" }; source(key) }
        return repo.localSubscriptions(key).map { state ->
            if (state.enabled && repo.template(state.templateId)?.active != true) state.copy(status = "template_paused") else state
        }
    }
    suspend fun configure(key: String, templateId: String, enabled: Boolean): LocalSubscription = withContext(Dispatchers.IO) {
        require(key.length in 1..36 && templateId.length in 1..36) { "Некорректный источник или правило" }
        val source = source(key)
        val template = repo.template(templateId) ?: throw ResourceNotFound()
        require(!enabled || template.active) { "Правило приостановлено" }
        val old = repo.localSubscriptions(key).firstOrNull { it.templateId == templateId }
        if (old?.enabled == enabled) {
            if (enabled && old.status in setOf("unavailable", "retrying", "rate_limited", "error", "access_denied") &&
                ready() && now() >= old.retryAt && now() >= rateDeadline()) serial.withLock {
                val current = repo.localSubscription(old.id) ?: throw ResourceNotFound()
                val currentTemplate = repo.template(templateId) ?: throw ResourceNotFound()
                if (current.enabled && currentTemplate.active && current.status in setOf("unavailable", "retrying", "rate_limited", "error", "access_denied") &&
                    now() >= current.retryAt && now() >= rateDeadline()) recover(current, source(key), currentTemplate)
            }
            return@withContext repo.localSubscription(old.id) ?: throw ResourceNotFound()
        }
        val initial = enabled && (old == null || old.sinceDate == 0)
        val since = (now() / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val head = if (initial) {
            check(ready()) { "Telegram пока недоступен" }
            check(now() >= rateDeadline()) { "Telegram ограничил частоту запросов. Повторите после окончания ожидания" }
            requestSlot()
            val page = try { withTimeout(25_000) { readRecent(key, 0, 1) } }
            catch (ex: TelegramRequestException) {
                if (isRate(ex)) persistRateDeadline(ex)
                throw ex
            }
            require(page.state == "ok") { "Не удалось установить начальную позицию. Повторите позже" }
            page.messages.maxOfOrNull { it.id } ?: 0L
        } else null
        repo.configureLocalSubscription(source, template, old, enabled, head, since).also { refreshKnownIds() }
    }
    fun offer(message: TdApi.Message) {
        if (message.chatId in knownChatIds) updates.trySend(message)
        // A full queue drops only the live hint: durable history recovery remains authoritative.
    }
    fun start() {
        if (!started.compareAndSet(false, true)) return
        refreshKnownIds()
        scope.launch {
            for (message in updates) serial.withLock {
                try { processUpdate(message) }
                catch (ex: CancellationException) { throw ex }
                catch (_: Exception) { /* Recovery retries any missed live hint from the durable checkpoint. */ }
            }
        }
        scope.launch {
            while (isActive) {
                try { pollOnce() }
                catch (ex: CancellationException) { throw ex }
                catch (_: Exception) { /* Source-specific errors are persisted below; retry later. */ }
                delay(pollIntervalMs)
            }
        }
    }
    private fun rateDeadline() = maxOf(repo.localMonitoringRetryAt(), repo.localSubscriptions().filter { it.rateLimited }.maxOfOrNull { it.retryAt } ?: 0L)
    private suspend fun requestSlot() = requestGate.withLock {
        currentCoroutineContext().ensureActive()
        val wait = requestIntervalMs - (System.nanoTime() - lastRequestNanos) / 1_000_000
        if (wait > 0) delay(wait)
        val remaining = rateDeadline() - now()
        if (remaining > 0) throw TelegramRequestException(429, "FLOOD_WAIT_${(remaining / 1000).coerceAtLeast(1)}")
        lastRequestNanos = System.nanoTime()
    }
    internal suspend fun pollOnce() {
        if (!ready() || now() < rateDeadline()) return
        serial.withLock {
            refreshKnownIds()
            for (state in repo.localSubscriptions().filter { it.enabled }) {
                if (now() < rateDeadline()) break
                if (now() < state.retryAt) continue
                val source = repo.sources().firstOrNull { it.key == state.sourceKey } ?: continue
                val template = repo.template(state.templateId)?.takeIf { it.active } ?: continue
                recover(state, source, template)
            }
        }
    }
    internal suspend fun processUpdate(message: TdApi.Message) {
        if (!ready() || now() < rateDeadline()) return
        for (state in repo.localSubscriptions().filter { it.enabled }) {
            if (message.id <= state.checkpoint || message.date < state.sinceDate || now() < state.retryAt) continue
            val source = repo.sources().firstOrNull { it.key == state.sourceKey && it.chatId == message.chatId } ?: continue
            val template = repo.template(state.templateId)?.takeIf { it.active } ?: continue
            try {
                requestSlot()
                val publication = observe(source.key, message) ?: continue
                // Individual live updates can arrive out of order and never move recovery checkpoints.
                repo.commitLocalMonitoring(state, source, template, state, listOf(publication), matching)
            } catch (ex: CancellationException) {
                if (ex !is TimeoutCancellationException) throw ex
                fail(state, source, template, null)
            } catch (ex: Exception) {
                fail(state, source, template, ex as? TelegramRequestException)
                if (now() < rateDeadline()) break
            }
        }
    }
    private suspend fun recover(initial: LocalSubscription, source: TelegramSource, template: SearchTemplate) {
        var state = initial
        repeat(4) {
            try {
                requestSlot()
                val page = withTimeout(25_000) { readRecent(source.key, state.recoveryCursor, 100) }
                require(page.messages.size <= 100)
                val head = state.recoveryHead.takeIf { it != 0L }
                    ?: maxOf(state.checkpoint, page.messages.maxOfOrNull { it.id } ?: 0)
                val messages = page.messages.filter { it.id > state.checkpoint && it.id <= head }
                val complete = page.state == "ok" && (page.nextCursor == null || page.messages.any { it.id <= state.checkpoint })
                val cursor = page.nextCursor
                val progressed = cursor != null && cursor > 0 && (state.recoveryCursor == 0L || cursor < state.recoveryCursor)
                val next = when {
                    complete -> state.copy(checkpoint = maxOf(state.checkpoint, head), recoveryCursor = 0, recoveryHead = 0,
                        retryAt = 0, error = null, status = "active", rateLimited = false)
                    progressed -> state.copy(recoveryCursor = cursor!!, recoveryHead = head, retryAt = 0, error = null,
                        status = "recovering", rateLimited = false)
                    else -> state.copy(error = "Telegram не продвинула курсор. Полученные совпадения сохранены; восстановление продолжится позже.",
                        retryAt = retryDeadline(60), status = "retrying", rateLimited = false)
                }
                if (!repo.commitLocalMonitoring(state, source, template, next, messages, matching)) return
                state = next
                if (complete || !progressed || page.state != "ok") return
            } catch (ex: CancellationException) {
                if (ex !is TimeoutCancellationException) throw ex
                fail(state, source, template, null)
                return
            } catch (ex: Exception) {
                fail(state, source, template, ex as? TelegramRequestException)
                return
            }
        }
    }
    private fun retryDeadline(seconds: Long): Long {
        val instant = now()
        return instant + seconds.coerceAtLeast(1).coerceAtMost((Long.MAX_VALUE - instant) / 1000) * 1000
    }
    private fun isRate(error: TelegramRequestException) = error.code == 429 || error.reason.startsWith("FLOOD_WAIT")
    private fun persistRateDeadline(error: TelegramRequestException): Long {
        val seconds = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE)
            .find(error.reason)?.groupValues?.get(1)?.toLongOrNull() ?: 60L
        return retryDeadline(seconds).also(repo::deferLocalMonitoring)
    }
    private fun fail(state: LocalSubscription, source: TelegramSource, template: SearchTemplate, error: TelegramRequestException?) {
        val limited = error?.let(::isRate) == true
        val deadline = if (limited) persistRateDeadline(error!!) else retryDeadline(60)
        val denied = error?.code in setOf(400, 401, 403, 404)
        repo.commitLocalMonitoring(state, source, template, state.copy(retryAt = deadline,
            error = when { limited -> "Telegram ограничил частоту запросов. Совпадения и курсор сохранены."
                denied -> "Источник временно недоступен. Совпадения и курсор сохранены."
                else -> "Не удалось прочитать источник. Курсор сохранён; повторим позже." },
            status = when { limited -> "rate_limited"; denied -> "unavailable"; else -> "retrying" }, rateLimited = limited), emptyList(), matching)
    }
    override fun close() {
        runBlocking { job.cancelAndJoin() }
        updates.close()
    }
}
