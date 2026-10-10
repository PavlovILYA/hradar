package dev.radar

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
    val apiId = System.getenv("TG_API_ID")?.toIntOrNull()
        ?: error("Set TG_API_ID in your environment (see .env.example)")
    val apiHash = System.getenv("TG_API_HASH")?.takeIf { it.isNotBlank() }
        ?: error("Set TG_API_HASH in your environment (see .env.example)")

    val tdlibDirectory = Path.of(System.getProperty("user.home"), ".recruitment-radar", "tdlib")
    Files.createDirectories(tdlibDirectory)

    val telegram = TdlibGateway(apiId, apiHash, tdlibDirectory)
    val repositories = Repositories(Path.of(System.getProperty("user.home"), ".hradar", "research.sqlite"))
    val matching = MatchingService()
    val search = SearchService(telegram, repositories, matching)
    val notifications = NotificationService(repositories, matching)
    val subscriptions = SubscriptionService(repositories, matching)
    val bot = BotService(BotConfig.fromEnvironment(), repositories, subscriptions, statusText = {
        val status = telegram.getStatus()
        "hradar\nTDLib: ${status.authorizationState} · ${status.connectionState}\nМониторинг: ${repositories.channels().size} источников\nАктивные подписки: ${subscriptions.list().count { it.active && it.subscribed && it.sourceMode != "global" }}"
    }, link = { chatId, messageId ->
        (telegram.executeRead(TdApi.GetMessageLink().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.MessageLink).link
    })
    val monitoring = MonitoringService(telegram, repositories, search, notifications)
    val historySync = HistorySyncService(repositories, telegram::getHistoryPage, { telegram.getStatus().let { it.authorized && it.connectionState == "Ready" } })
    val readPort = TdlibReadPort(telegram, search)
    val capabilities = CapabilityService(readPort, repositories)
    (org.slf4j.LoggerFactory.getLogger("io.modelcontextprotocol") as ch.qos.logback.classic.Logger).level = ch.qos.logback.classic.Level.OFF
    telegram.start()
    bot.start()
    monitoring.start()
    historySync.resumePending()
    Runtime.getRuntime().addShutdownHook(Thread { historySync.close(); monitoring.close(); bot.close(); telegram.close(); repositories.close() })

    embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        // Root route installation permits the MCP child route to use its SDK serializer.
        routing { install(ContentNegotiation) {
            json(Json { prettyPrint = true; encodeDefaults = true })
        } }
        install(StatusPages) {
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
        configureRoutes(telegram)
        configureResearchRoutes(telegram, repositories, matching, search, notifications)
        configureHistoryRoutes(telegram, repositories, historySync)
    }.start(wait = true)
}
