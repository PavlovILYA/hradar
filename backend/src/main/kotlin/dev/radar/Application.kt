package dev.radar

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

fun main() {
    if (System.getProperty("hradar.jniSmoke") == "true") { jniSmoke(); return }
    val paths = RuntimePaths()
    paths.prepare()
    val lease = SessionLease.acquire(paths.session)
    if (lease == null) {
        if (!DesktopRuntime.reopen(paths)) DesktopRuntime.conflict("hradar уже использует эту Telegram-сессию. Закройте работающий экземпляр.")
        if (DesktopRuntime.enabled && !DesktopRuntime.smoke) kotlin.system.exitProcess(0)
        return
    }
    // Older development installs don't take our lock: reject occupied port before TDLib starts.
    if (DesktopRuntime.portOccupied()) {
        lease.close()
        DesktopRuntime.conflict("Порт 8080 занят. Закройте старый backend hradar или другое приложение и повторите запуск.")
        return
    }
    val credentials = if (DesktopRuntime.smoke) null else paths.loadCredentials()
    val telegram = TdlibGateway(credentials?.apiId, credentials?.apiHash, paths.session)
    val repositories = try { Repositories(paths.database) } catch (ex: Exception) {
        lease.close()
        DesktopRuntime.conflict(if (ex is IllegalStateException && ex.message == "Database schema is newer than this application")
            "База данных создана более новой версией hradar. Используйте совместимую новую сборку; данные не удалены."
        else "Не удалось открыть пользовательскую базу hradar. Проверьте доступ к профилю и завершите старый экземпляр.")
        return
    }
    val csrf = DesktopRuntime.token()
    val launchToken = if (DesktopRuntime.enabled) DesktopRuntime.token() else null
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
    }, workspace = workspace, link = { chatId, messageId ->
        (telegram.executeRead(TdApi.GetMessageLink().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.MessageLink).link
    })
    val monitoring = MonitoringService(telegram, repositories, search, notifications)
    val historySync = HistorySyncService(repositories, telegram::getHistoryPage, { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } })
    val readPort = TdlibReadPort(telegram, search)
    val capabilities = CapabilityService(readPort, repositories)
    (org.slf4j.LoggerFactory.getLogger("io.modelcontextprotocol") as ch.qos.logback.classic.Logger).level = ch.qos.logback.classic.Level.OFF
    val server = embeddedServer(Netty, host = "127.0.0.1", port = DesktopRuntime.port) {
        configureDesktopSecurity(launchToken, csrf)
        configureOnboarding(telegram, paths, csrf) { Thread { kotlin.system.exitProcess(0) }.start() }
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
                call.respondApiError(if (ex.rateLimited) HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest, ApiError(ex.message!!))
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
        configureRecruitmentRoutes(RecruitmentProjectService(repositories, matching, subscriptions, recruiter))
        configureSavedRoutes(repositories)
        configureRoutes(telegram)
        configureResearchRoutes(telegram, repositories, matching, search, notifications)
        configureHistoryRoutes(telegram, repositories, historySync)
    }
    val marker = paths.data.resolve("desktop-instance.json")
    val stopped = java.util.concurrent.CountDownLatch(1)
    val cleaned = java.util.concurrent.atomic.AtomicBoolean(false)
    fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        try {
            server.stop(500, 3000)
            historySync.close(); monitoring.close(); bot.close(); telegram.close(); repositories.close()
            if (launchToken != null) Files.deleteIfExists(marker)
        } finally { lease.close(); stopped.countDown() }
    }
    Runtime.getRuntime().addShutdownHook(Thread { cleanup() })
    try {
        // Bind first. A competing unrelated listener must never cause a second account client.
        server.start(wait = false)
        if (!DesktopRuntime.smoke) { telegram.start(); bot.start(); monitoring.start(); historySync.resumePending() }
        if (launchToken != null) {
            val temporaryMarker = Files.createTempFile(paths.data, "desktop-", ".tmp", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            try {
                Files.writeString(temporaryMarker, "{\"pid\":${ProcessHandle.current().pid()},\"token\":\"$launchToken\"}")
                Files.move(temporaryMarker, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporaryMarker) }
            // Netty start returns after bind; verify the HTTP route before launching the browser.
            val http = java.net.http.HttpClient.newHttpClient()
            repeat(50) {
                try {
                    if (http.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${DesktopRuntime.port}/health")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                        DesktopRuntime.open("http://127.0.0.1:${DesktopRuntime.port}/#launch=$launchToken")
                        stopped.await(); return
                    }
                } catch (_: java.io.IOException) { }
                Thread.sleep(100)
            }
            error("Локальный сервер не готов")
        }
        stopped.await()
    } finally { cleanup() }
}
