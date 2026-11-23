package dev.radar

import dev.radar.sources.*
import dev.radar.profiles.*
import dev.radar.documents.configureDocuments

import dev.radar.desktop.*
import io.ktor.server.http.content.staticResources
import dev.radar.api.configureRoutes
import dev.radar.bot.*
import org.drinkless.tdlib.TdApi
import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import dev.radar.research.*
import dev.radar.history.*
import dev.radar.capabilities.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ApiError(val error: String)

// StatusPages runs outside the selected route's serializer pipeline.
internal suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, error: ApiError) {
    respondText(Json.encodeToString(ApiError.serializer(), error), ContentType.Application.Json, status)
}

fun main() = runApplication()

internal fun runApplication(
    openBrowser: (DesktopAccess) -> Unit = { DesktopRuntime.openInterface(it) },
    probeReady: () -> Boolean = DesktopRuntime::probeReady,
) {
    if (System.getProperty("hradar.jniSmoke") == "true") { jniSmoke(); return }
    val base = RuntimePaths()
    if (DesktopRuntime.portOccupied()) {
        if (!DesktopRuntime.reopen(base)) DesktopRuntime.conflict("Порт hradar занят. Завершите работающий экземпляр.")
        return
    }
    Files.createDirectories(base.data)
    check(!Files.isSymbolicLink(base.data)) { "Профиль приложения недоступен" }
    Files.setPosixFilePermissions(base.data, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
    val applicationLease = SessionLease.acquire(base.data)
        ?: throw IllegalStateException("Другой экземпляр hradar уже использует профили")
    try {
        val store = ProfileStore(base.home)
        try { store.prepare() } catch (_:Exception) {
            DesktopRuntime.conflict("Не удалось безопасно подготовить профили. Исходные данные сохранены. Закройте другие версии, проверьте место и реестр профилей; восстановление описано в TELEGRAM_PROFILES.md.")
            return
        }
        // Retained 0.11 session copies must not be reopened by an older binary concurrently.
        val retainedLease = if (Files.isDirectory(base.session, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            SessionLease.acquire(base.session) ?: error("Прежний экземпляр hradar использует сохранённую сессию") else null
        try { do {
            val paths = RuntimePaths(base.home, store.active().id)
            val again = try { runProfileApplication(openBrowser,probeReady,store,paths) }
            catch (_:Exception) {
                DesktopRuntime.conflict("Не удалось открыть выбранный профиль. Другой аккаунт не запущен; данные сохранены. Проверьте профиль и резервную копию.")
                false
            }
        } while (again) } finally { retainedLease?.close() }
    } finally { applicationLease.close() }
}

private fun runProfileApplication(
    openBrowser: (DesktopAccess) -> Unit,
    probeReady: () -> Boolean,
    profileStore: ProfileStore,
    paths: RuntimePaths,
): Boolean {
    // Older builds may have no session lock. Check their fixed port before any migration.
    if (DesktopRuntime.portOccupied()) {
        if (DesktopRuntime.reopen(paths)) {
            if (DesktopRuntime.enabled && !DesktopRuntime.smoke) kotlin.system.exitProcess(0)
            return false
        }
        DesktopRuntime.conflict("Порт ${DesktopRuntime.port} занят. Закройте работающий hradar перед запуском новой версии.")
        return false
    }
    try { paths.prepare() } catch (_: Exception) {
        DesktopRuntime.conflict("Не удалось безопасно перенести локальные данные hradar. Оригиналы сохранены; проверьте права, место и занятые файлы.")
        return false
    }
    val lease = SessionLease.acquire(paths.session)
    if (lease == null) {
        if (!DesktopRuntime.reopen(paths)) DesktopRuntime.conflict("hradar уже использует эту Telegram-сессию. Закройте работающий экземпляр.")
        if (DesktopRuntime.enabled && !DesktopRuntime.smoke) kotlin.system.exitProcess(0)
        return false
    }
    // Older development installs don't take our lock: reject occupied port before TDLib starts.
    if (DesktopRuntime.portOccupied()) {
        lease.close()
        DesktopRuntime.conflict("Порт ${DesktopRuntime.port} занят. Закройте старый backend hradar или другое приложение и повторите запуск.")
        return false
    }
    val desktopAccess = if (DesktopRuntime.enabled) DesktopAccess(DesktopRuntime.token()) else null
    val credentials = if (DesktopRuntime.smoke) null else paths.loadCredentials()
    val telegram = TdlibGateway(credentials?.apiId, credentials?.apiHash, paths.session.toRealPath())
    val repositories = try { Repositories(paths.database) } catch (ex: Exception) {
        lease.close()
        DesktopRuntime.conflict(if (ex is IllegalStateException && ex.message == "Database schema is newer than this application")
            "База данных создана более новой версией hradar. Используйте совместимую новую сборку; данные не удалены."
        else "Не удалось открыть пользовательскую базу hradar. Проверьте доступ к профилю и завершите старый экземпляр.")
        return false
    }
    val csrf = DesktopRuntime.token()
    val matching = MatchingService()
    val search = SearchService(telegram, repositories, matching)
    val notifications = NotificationService(repositories, matching)
    val subscriptions = SubscriptionService(repositories, matching)
    val recruiter = RecruiterService(repositories, RecruiterSearchPort(search::search))
    val workspace = BotWorkspaceService(repositories, recruiter)
    val bot = BotSettingsService(paths, repositories, subscriptions,
        owner = { telegram.getMe().let { BotAccessUser(it.id, listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" "), it.username, "owner") } },
        lookup = telegram::resolvePublicUser, statusText = {
        val status = telegram.getStatus()
        "hradar\nTDLib: ${status.authorizationState} · ${status.connectionState}\nМониторинг: ${repositories.channels().size} источников\nАктивные подписки: ${subscriptions.list().count { it.active && it.subscribed && it.sourceMode != "global" }}"
    }, workspace = workspace, legacyConfig = BotConfig.managed(null, emptySet(), false), link = { chatId, messageId ->
        (telegram.executeRead(TdApi.GetMessageLink().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.MessageLink).link
    })
    val monitoring = MonitoringService(telegram, repositories, search, notifications)
    val sourceReader = dev.radar.sources.SourceReadService(repositories, execute = telegram::executeRead)
    // Resolve the established private profile: macOS temporary homes may use the /var alias.
    val documents = dev.radar.documents.DocumentService(repositories, sourceReader,
        paths.documents.toRealPath(), paths.session.toRealPath().resolve("documents"), telegram::executeRead)
    val localMonitoring = dev.radar.sources.LocalMonitoringService(repositories, sourceReader, { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } })
    val sourceArchive = dev.radar.sources.SourceArchiveService(repositories, { key, cursor -> sourceReader.recent(key, cursor, 100) }, { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } })
    val historySync = HistorySyncService(repositories, telegram::getHistoryPage, { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } })
    val readPort = TdlibReadPort(telegram, search)
    val capabilities = CapabilityService(readPort, repositories)
    (org.slf4j.LoggerFactory.getLogger("io.modelcontextprotocol") as ch.qos.logback.classic.Logger).level = ch.qos.logback.classic.Level.OFF
    val workerGate = Any()
    var workersStopped = false
    var workerFailure: Throwable? = null
    fun stopWorkers() = synchronized(workerGate) {
        if (!workersStopped) {
            workersStopped = true
            telegram.localMessageListener = null
            val closes = listOf<()->Unit>({ localMonitoring.close() },{ documents.close() },{ sourceArchive.close() },{ historySync.close() },{ monitoring.close() },{ bot.close() })
            for(close in closes) try { close() } catch(problem:Throwable) { if(workerFailure==null) workerFailure=problem }
        }
        workerFailure?.let { throw it }
    }
    val profileSession = ProfileSession(profileStore, paths.profileId!!,
        authorized = { if (DesktopRuntime.smoke) !profileStore.active().loggedOut else telegram.getStatus().authorized },
        getMe = {
            if (DesktopRuntime.smoke) ProfileIdentity((java.util.UUID.fromString(paths.profileId).mostSignificantBits ushr 1) % 1_000_000_000L + 1,"Synthetic account",null)
            else telegram.getMe().let { ProfileIdentity(it.id,listOf(it.firstName,it.lastName).filter(String::isNotBlank).joinToString(" "),it.username,it.avatarAvailable) }
        },
        credentialsSource = { when {
            System.getenv("TG_API_ID")?.toIntOrNull() != null && System.getenv("TG_API_HASH") != null -> "environment"
            Files.exists(paths.credentials) -> "saved"
            else -> "missing"
        } },
        startWorkers = { synchronized(workerGate) {
            check(!workersStopped) { "Перезапустите профиль после завершения сеанса" }
            if (!DesktopRuntime.smoke) {
                telegram.localMessageListener = localMonitoring::offer
                bot.start(); monitoring.start(); localMonitoring.start(); historySync.resumePending(); sourceArchive.resumePending()
            }
        } },
        stopWorkers = ::stopWorkers,
        operationBusy = { telegram.onboarding.status().busy || documents.hasActiveJobs() },
        botOwner = { bot.status().owner?.id },
    )
    val avatars = AvatarService(paths.avatars,paths.session.toRealPath(),telegram::executeRead)
    var restartProfile: (ProfileChange)->Unit = {}
    val pendingChange = java.util.concurrent.atomic.AtomicReference<ProfileChange?>(null)
    val cleanupFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    var menu: DesktopMenu? = null
    val shutdownRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    fun requestShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        menu?.stopping()
        // Existing shutdown hook owns all cleanup, including TDLib Close.
        Thread({ kotlin.system.exitProcess(0) }, "hradar-shutdown").start()
    }
    val server = embeddedServer(Netty, host = "127.0.0.1", port = DesktopRuntime.port) {
        configureDesktopSecurity(desktopAccess, csrf)
        configureProfileAccess(profileSession)
        configureOnboarding(telegram, paths, csrf, ::requestShutdown)
        configureProfileRoutes(profileStore,profileSession,paths::saveCredentials) { restartProfile(it) }
        configureAvatarRoutes(avatars)
        routing { staticResources("/", "web", index = "index.html") }
        // Root route installation permits the MCP child route to use its SDK serializer.
        routing { install(ContentNegotiation) {
            json(Json { prettyPrint = true; encodeDefaults = true })
        } }
        install(StatusPages) {
            exception<BotApiException> { call, ex ->
                val status = when (ex.code) { 401 -> HttpStatusCode.BadRequest; 409 -> HttpStatusCode.Conflict; 429 -> HttpStatusCode.TooManyRequests; else -> HttpStatusCode.BadGateway }
                val message = when (ex.code) { 401 -> "Telegram отклонил токен бота"; 409 -> "Бот уже используется другим подключением"; 429 -> "Telegram ограничил запросы. Попробуйте позже"; else -> "Не удалось связаться с Telegram" }
                call.respondApiError(status, ApiError(message))
            }
            exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respondApiError(HttpStatusCode.BadRequest, ApiError("Некорректный JSON запроса")) }
            exception<ResourceNotFound> { call, _ -> call.respondApiError(HttpStatusCode.NotFound, ApiError("Ресурс не найден")) }
            exception<TelegramRequestException> { call, ex ->
                val status = when {
                    ex.reason in setOf("USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "CHAT_NOT_FOUND") || ex.code == 404 -> HttpStatusCode.NotFound
                    ex.reason in setOf("CHANNEL_PRIVATE", "CHANNEL_INVALID", "CHAT_ACCESS_DENIED") || ex.code == 403 -> HttpStatusCode.Forbidden
                    ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT") -> HttpStatusCode.TooManyRequests
                    else -> HttpStatusCode.BadGateway
                }
                call.respondApiError(status, ApiError(when (status) {
                    HttpStatusCode.NotFound -> "Public channel not found"
                    HttpStatusCode.Forbidden -> "Channel is unavailable to this account"
                    HttpStatusCode.TooManyRequests -> "Telegram rate limit; try again later"
                    else -> "Telegram request failed"
                }))
            }
            exception<dev.radar.telegram.OnboardingRejected> { call, ex ->
                if (ex.retryAfterSeconds > 0) call.response.headers.append("Retry-After", ex.retryAfterSeconds.toString())
                call.respondText(Json.encodeToString(ex.failure(telegram.onboarding.status().state)),
                    ContentType.Application.Json, when (ex.kind) {
                        "rate_limited", "wait" -> HttpStatusCode.TooManyRequests
                        "state_changed", "busy" -> HttpStatusCode.Conflict
                        "network" -> HttpStatusCode.ServiceUnavailable
                        else -> HttpStatusCode.BadRequest
                    })
            }
            exception<IllegalArgumentException> { call, ex ->
                call.respondApiError(HttpStatusCode.BadRequest, ApiError(ex.message ?: "Bad request"))
            }
            exception<IllegalStateException> { call, ex ->
                call.respondApiError(HttpStatusCode.ServiceUnavailable, ApiError(ex.message ?: "Not ready"))
            }
            exception<Throwable> { call, ex ->
                call.application.environment.log.error("Request failed", ex)
                call.respondApiError(HttpStatusCode.BadGateway, ApiError("Telegram request failed; check local logs"))
            }
        }
        configureCapabilities(capabilities, readPort, repositories)
        configureBotRoutes(bot)
        configureRecruitmentRoutes(RecruitmentProjectService(repositories, matching, subscriptions, recruiter, localSearch = LocalProjectSearchService(repositories, RecruiterSearchPort(search::search))))
        configureSavedRoutes(repositories)
        configureRoutes(telegram)
        configureOwnerWorkflow(repositories, sourceReader, localMonitoring)
        configureResearchRoutes(telegram, repositories, matching, search, notifications)
        configureSourceRoutes(SourceCatalogService(TdlibSourceCatalog(telegram::executeRead), repositories), repositories, sourceReader, sourceArchive, localMonitoring)
        configureHistoryRoutes(telegram, repositories, historySync)
        configureDocuments(documents)
    }
    val marker = paths.data.resolve("desktop-instance.json")
    val stopped = java.util.concurrent.CountDownLatch(1)
    val cleaned = java.util.concurrent.atomic.AtomicBoolean(false)
    val lifecycleLock = Any()
    fun cleanup() = synchronized(lifecycleLock) {
        if (!cleaned.compareAndSet(false, true)) return@synchronized
        shutdownRequested.set(true)
        menu?.stopping()
        try {
            // Each owner must close even if another service fails. Never log profile data.
            val steps: List<() -> Unit> = listOf(
                { server.stop(500, 3000) },
                { profileSession.close() }, { stopWorkers() },
                { if (pendingChange.get()?.logout == true) { kotlinx.coroutines.runBlocking { telegram.logOut() }; profileStore.markLoggedOut(paths.profileId!!) } },
                { telegram.close() }, { repositories.close() },
                { if (desktopAccess != null) Files.deleteIfExists(marker); Unit },
                { menu?.close(); Unit },
            )
            closeProfileResources(steps, { lease.close() }) {
                cleanupFailed.set(true)
                System.err.println("hradar: завершение сеанса не подтверждено; процесс остановлен без запуска другого профиля")
                // Keep leases until the OS terminates every native/polling thread.
                Runtime.getRuntime().halt(1)
                throw IllegalStateException("Process termination failed")
            }
        } finally { stopped.countDown() }
    }
    restartProfile = { change ->
        if (shutdownRequested.compareAndSet(false,true)) {
            pendingChange.set(change)
            Thread({ cleanup() },"profile-restart").start()
        }
    }
    val shutdownHook = Thread { cleanup() }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
        synchronized(lifecycleLock) {
            menu = DesktopMenu.install({ destination ->
                DesktopRuntime.openInterface(checkNotNull(desktopAccess), destination)
            }, ::requestShutdown)
            // Bind first. A competing unrelated listener must never cause a second account client.
            server.start(wait = false)
            if (!DesktopRuntime.smoke) telegram.start()
            profileSession.start()
            if (desktopAccess != null) {
                val temporaryMarker = Files.createTempFile(paths.data, "desktop-", ".tmp", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
                try {
                    Files.writeString(temporaryMarker, "{\"pid\":${ProcessHandle.current().pid()},\"token\":\"${desktopAccess.capability}\"}")
                    Files.move(temporaryMarker, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporaryMarker) }
            }
        }
        // Blocking adapters must never hold the initialization/cleanup lock.
        if (desktopAccess != null) {
            var ready = false
            for (attempt in 0 until 50) {
                if (shutdownRequested.get()) break
                try {
                    if (probeReady()) {
                        ready = true
                        menu?.ready()
                        if (!shutdownRequested.get()) openBrowser(desktopAccess)
                        break
                    }
                } catch (_: java.io.IOException) { }
                Thread.sleep(100)
            }
            check(ready || shutdownRequested.get()) { "Локальный сервер не готов" }
        }
        stopped.await()
    } catch (_: Exception) {
        cleanup()
        if (DesktopRuntime.enabled && !DesktopRuntime.smoke) {
            try { DesktopRuntime.conflict("Не удалось запустить hradar или Menu Bar. Приложение остановлено; повторите запуск в графическом сеансе macOS.", exitCode = 1) }
            finally { kotlin.system.exitProcess(1) }
        }
        throw IllegalStateException("Не удалось запустить hradar; ресурсы освобождены")
    } finally {
        cleanup()
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_:IllegalStateException) { }
    }
    val change = pendingChange.get() ?: return false
    check(!cleanupFailed.get()) { "Сеанс не завершился безопасно. Завершите hradar перед повторным запуском." }
    profileStore.select(change.target)
    return true
}
