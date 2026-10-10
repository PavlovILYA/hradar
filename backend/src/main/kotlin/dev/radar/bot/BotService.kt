package dev.radar.bot

import dev.radar.research.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Optional independent transport; one polling worker and one persistent-outbox worker. */
class BotService(
    private val config: BotConfig, private val repo: Repositories, subscriptions: SubscriptionService,
    statusText: () -> String,
    private val link: suspend (Long, Long) -> String? = { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
    gatewayFactory: (String) -> BotGateway = ::HttpsBotGateway,
    workspace: BotWorkspaceService? = null,
) : AutoCloseable {
    private val gateway = if (config.enabled) gatewayFactory(config.token!!) else null
    private val botId = config.botId
    private val handler = BotUpdateHandler(config, repo, subscriptions, statusText, now, workspace)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val identityLock = Mutex()
    private val deliveryLock = Mutex()
    private val pollingLock = Mutex()
    private var identity: BotIdentity? = null
    @Volatile private var state = if (config.enabled) "starting" else config.state
    @Volatile private var notice: String? = config.notice
    @Volatile private var fatal = false
    @Volatile private var lastPollAt = 0L
    private var pollFailures = 0
    init { repo.configureBot(botId.takeIf { config.enabled }, config.allowedUsers) }
    fun status(): BotStatus = BotStatus(state, notice, config.allowedUsers.size,
        botId?.let(repo::botRegisteredCount) ?: 0, botId?.let(repo::botDeliveryCounts) ?: BotDeliveryCounts(),
        botId?.let { maxOf(repo.botRetryAt(it, true), repo.botRetryAt(it, false), repo.botRateRetryAt(it)) } ?: 0, lastPollAt)
    fun start() {
        if (gateway == null || !started.compareAndSet(false, true)) return
        scope.launch { while (isActive) { guarded { pollOnce() }; delay(1000) } }
        scope.launch { while (isActive) { guarded { deliverOnce() }; delay(1100) } }
    }
    private suspend fun guarded(action: suspend () -> Unit) {
        try { action() }
        catch (ex: CancellationException) { throw ex }
        catch (_: Exception) {
            state = "error"; notice = "Не удалось обработать Bot API или локальную очередь; повторим позже"
            delay(5000)
        }
    }
    private suspend fun verifiedIdentity(): BotIdentity = identityLock.withLock {
        identity ?: gateway!!.identity().also {
            if (it.id != botId) throw BotApiException(401)
            identity = it
        }
    }
    private fun retryDelay(error: BotApiException, attempts: Int): Long = error.retryAfterSeconds?.times(1000)
        ?: (2000L * (1L shl attempts.coerceIn(0, 7))).coerceAtMost(300_000)
    private fun fail(error: BotApiException) {
        state = "error"
        notice = when (error.code) {
            401 -> "Bot API отклонил авторизацию; проверьте токен локально и перезапустите backend"
            409 -> "Конфликт getUpdates: проверьте другой poller или ранее настроенный webhook"
            429 -> "Telegram ограничил Bot API; доставка ожидает retry_after"
            403 -> "Bot API запретил доставку получателю; откройте бота и проверьте блокировку"
            400 -> "Bot API отклонил запрос; проверьте состояние подписки и получателя"
            else -> "Bot API временно недоступен; очередь сохранена для повторной попытки"
        }
        if (error.code == 429) botId?.let { repo.setBotRateRetryAt(it, now() + retryDelay(error, 0)) }
        if (error.code == 401) fatal = true
    }
    internal suspend fun pollOnce() = pollingLock.withLock {
        val api = gateway ?: return@withLock
        val id = botId ?: return@withLock
        if (fatal || maxOf(repo.botRetryAt(id, true), repo.botRateRetryAt(id)) > now()) return@withLock
        try {
            val bot = verifiedIdentity()
            val updates = api.updates(repo.botOffset(id))
            for (update in updates.sortedBy { it.id }) {
                currentCoroutineContext().ensureActive()
                val callbackId = handler.handle(update, bot.username)
                if (callbackId != null && repo.botRateRetryAt(id) <= now()) {
                    try { api.answerCallback(callbackId, "Обработано") }
                    catch (ex: CancellationException) { throw ex }
                    catch (ex: BotApiException) {
                        if (ex.code == 429) fail(ex)
                        // Command and reply already committed; expired callback acknowledgement is optional.
                    }
                }
            }
            lastPollAt = now(); pollFailures = 0; repo.setBotRetryAt(id, true, 0)
            if (!fatal && maxOf(repo.botRetryAt(id, false), repo.botRateRetryAt(id)) <= now()) { state = "running"; notice = null }
        } catch (ex: CancellationException) { throw ex }
        catch (ex: BotApiException) {
            fail(ex); repo.setBotRetryAt(id, true, now() + retryDelay(ex, pollFailures++))
        }
    }
    private fun deliveryPermitted(item: BotOutboxItem): Boolean {
        val payload = item.payload
        if (!repo.botDeliveryPending(item.id)) return false
        if (payload.publishRequestId != null && !repo.publishPreviewValid(botId!!, item.userId, payload.publishRequestId, now())) return false
        return payload.mcpSourceId == null || (repo.botPublishPermitted(item.userId) && repo.mcpEnabled() && repo.mcpSources().any { it.id == payload.mcpSourceId })
    }
    internal suspend fun deliverOnce() = deliveryLock.withLock {
        val api = gateway ?: return@withLock
        val id = botId ?: return@withLock
        if (fatal || maxOf(repo.botRetryAt(id, false), repo.botRateRetryAt(id)) > now() || repo.botRetryAt(id, true) > now() && identity == null) return@withLock
        val item = repo.nextBotDelivery(id, now()) ?: return@withLock
        if (item.userId !in config.allowedUsers || item.chatId != item.userId) return@withLock
        if (!deliveryPermitted(item)) { repo.cancelBotDelivery(item.id); return@withLock }
        try {
            verifiedIdentity()
            var payload = item.payload
            if (payload.needsLink && payload.channelId != null && payload.messageId != null) {
                val original = try { withTimeout(5000) { verifiedTelegramLink(link(payload.channelId!!, payload.messageId!!)) } }
                catch (_: TimeoutCancellationException) { null }
                catch (ex: CancellationException) { throw ex }
                catch (_: Exception) { null }
                payload = payload.copy(buttons = payload.buttons + (original?.let { listOf(listOf(BotButton("Оригинал", url = it))) } ?: emptyList()), text = payload.text + (original?.let { "\n\n$it" } ?: ""), needsLink = false)
                repo.prepareBotDelivery(item.id, payload)
                if (original != null) repo.rememberPublicationLink(id, item.userId, payload.channelId!!, payload.messageId!!, original)
            }
            currentCoroutineContext().ensureActive()
            if (!deliveryPermitted(item)) { repo.cancelBotDelivery(item.id); return@withLock }
            val sentId = api.send(item.chatId, payload)
            // Crash after send success but before this commit can redeliver (Bot API has no idempotency key).
            repo.confirmBotDelivery(item.id, sentId)
            repo.setBotRetryAt(id, false, now() + 1100)
        } catch (ex: CancellationException) { throw ex }
        catch (ex: BotApiException) {
            fail(ex)
            val at = now() + retryDelay(ex, item.attempts)
            repo.retryBotDelivery(item.id, at, "BOT_${ex.code}", ex.code in setOf(400, 403))
            if (ex.code in setOf(429, 401)) repo.setBotRetryAt(id, false, at)
        }
    }
    override fun close() {
        runBlocking { job.cancelAndJoin() }
        gateway?.close()
    }
}
