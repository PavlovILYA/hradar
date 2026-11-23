package dev.radar.profiles

import dev.radar.telegram.ApiCredentials
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@kotlinx.serialization.Serializable data class ProfileStatus(
    val generation: String, val active: TelegramProfile, val profiles: List<TelegramProfile>,
    val phase: String, val identity: ProfileIdentity?, val credentialsSource: String,
    val legacyAvailable: Boolean, val error: String? = null,
)
data class ProfileChange(val target: String, val logout: Boolean = false)

/** Owns only identity/lifecycle intent. No user data is readable before verified identity. */
class ProfileSession(
    private val store: ProfileStore, val profileId: String,
    private val authorized: () -> Boolean,
    private val getMe: suspend () -> ProfileIdentity,
    private val credentialsSource: () -> String,
    private val startWorkers: () -> Unit,
    private val stopWorkers: () -> Unit,
    private val operationBusy: () -> Boolean = { false },
    private val botOwner: () -> Long? = { null },
) : AutoCloseable {
    val generation = UUID.randomUUID().toString()
    @Volatile var phase = "AUTHORIZING"; private set
    @Volatile var identity: ProfileIdentity? = null; private set
    @Volatile private var error: String? = null
    @Volatile private var authAttempted = false
    private val changing = AtomicBoolean(false)
    private val workersStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var verifier: Job? = null
    @Volatile private var retryAt: Long = 0

    fun authenticationAttempt() { authAttempted = true }
    @Synchronized fun start() {
        if (verifier?.isActive == true) return
        verifier = scope.launch {
            while (isActive && !changing.get()) {
                if (workersStarted.get() && !authorized()) {
                    phase = "ERROR"; identity = null; error = "Telegram завершил сеанс. Перезапустите вход в этот профиль."
                    stopWorkers(); break
                }
                if (authorized() && phase == "AUTHORIZING") verify()
                delay(500)
            }
        }
    }
    suspend fun verify() {
        check(!workersStarted.get() || phase != "ERROR") { "Перезапустите профиль для повторного входа" }
        if (changing.get()) return
        check(System.currentTimeMillis() >= retryAt) { "Telegram ограничил запросы. Дождитесь окончания паузы." }
        phase = "VERIFYING"; error = null
        try {
            val me = getMe()
            currentCoroutineContext().ensureActive()
            synchronized(this) {
            if (changing.get()) return
            require(me.userId > 0)
            identity = me
            val owner = botOwner()
            require(owner == null || owner == me.userId) { "Владелец настройки бота не совпадает с Telegram-профилем" }
            val bound = try { store.bind(profileId, me, existingSession = !authAttempted) }
            catch (_:IllegalStateException) { throw IllegalArgumentException("Identity mismatch") }
            identity = me
            phase = if (bound.confirmed) "READY" else "CONFIRM_OWNER"
            if (bound.confirmed) enableWorkers()
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            phase = "ERROR"; error = "Проверка Telegram-аккаунта заняла слишком много времени. Повторите проверку."
        } catch (problem: dev.radar.telegram.TelegramRequestException) {
            val seconds=if(problem.code==429 || problem.reason.startsWith("FLOOD_WAIT")) Regex("(?:FLOOD_WAIT_|retry after )(\\d+)",RegexOption.IGNORE_CASE).find(problem.reason)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(1,604800) ?: 60L else 0L
            retryAt=System.currentTimeMillis()+seconds*1000
            phase="ERROR";error=if(seconds>0) "Telegram ограничил запросы. Повторите проверку после паузы ($seconds сек.)." else "Не удалось проверить Telegram-аккаунт. Повторите проверку."
        } catch (_: CancellationException) { throw CancellationException() }
        catch (_: IllegalArgumentException) {
            phase = "MISMATCH"; error = "Этот аккаунт не соответствует владельцу локальных данных или уже сохранён в другом профиле. Данные закрыты. Выберите нужный профиль или выйдите из Telegram."
        } catch (_: Exception) {
            phase = "ERROR"; error = "Не удалось проверить Telegram-аккаунт. Проверьте соединение и повторите проверку."
            if (workersStarted.get()) stopWorkers()
        }
    }
    private fun enableWorkers() {
        if (!changing.get() && workersStarted.compareAndSet(false,true)) startWorkers()
    }
    @Synchronized fun confirm(userId: Long) {
        check(!changing.get()) { "Профиль переключается" }
        check(phase == "CONFIRM_OWNER" && identity?.userId == userId) { "Сначала проверьте текущий Telegram-аккаунт" }
        store.confirmLegacy(profileId,userId); phase = "READY"
        try { enableWorkers() } catch (_:Exception) {
            phase="ERROR";error="Не удалось запустить рабочее пространство. Перезапустите этот профиль."
            stopWorkers()
            throw IllegalStateException("Не удалось запустить рабочее пространство. Перезапустите этот профиль.")
        }
    }
    @Synchronized fun status() = ProfileStatus(generation, store.list().first { it.id == profileId },store.list(),phase,identity,credentialsSource(),store.legacyAvailable(),error)
    @Synchronized fun prepareNew(create: () -> TelegramProfile): ProfileChange {
        check(!changing.get() && phase != "VERIFYING" && !operationBusy()) { "Завершите текущую операцию перед переключением" }
        return prepareChange(create().id)
    }
    @Synchronized fun prepareChange(target: String, logout: Boolean = false): ProfileChange {
        val profiles=store.list()
        require(profiles.any { it.id == target }) { "Профиль не найден" }
        if(logout && profiles.first { it.id==profileId }.identity==null && profiles.any { it.id!=profileId && it.identity?.userId==identity?.userId && identity!=null })
            throw IllegalStateException("Этот аккаунт уже сохранён в другом профиле. Переключитесь на него; выход из копии прежней сессии может завершить и исходную сессию.")
        check(phase != "VERIFYING" && !operationBusy()) { "Завершите вход или отмените загрузки перед переключением профиля" }
        check(changing.compareAndSet(false,true)) { "Переключение уже выполняется" }
        phase = "SWITCHING"; identity = null
        return ProfileChange(target,logout)
    }
    override fun close() { changing.set(true); scope.cancel(); runBlocking { scope.coroutineContext[Job]?.join() } }
}
