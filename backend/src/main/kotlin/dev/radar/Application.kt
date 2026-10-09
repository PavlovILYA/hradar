package dev.radar

import dev.radar.api.configureRoutes
import dev.radar.telegram.TdlibGateway
import dev.radar.telegram.TelegramRequestException
import dev.radar.research.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ApiError(val error: String)

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
    val monitoring = MonitoringService(telegram, repositories, search, notifications)
    telegram.start()
    monitoring.start()
    Runtime.getRuntime().addShutdownHook(Thread { monitoring.close(); telegram.close(); repositories.close() })

    embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; encodeDefaults = true })
        }
        install(StatusPages) {
            exception<ResourceNotFound> { call, _ -> call.respond(HttpStatusCode.NotFound, ApiError("Ресурс не найден")) }
            exception<TelegramRequestException> { call, ex ->
                val status = when {
                    ex.reason in setOf("USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "CHAT_NOT_FOUND") || ex.code == 404 -> HttpStatusCode.NotFound
                    ex.reason in setOf("CHANNEL_PRIVATE", "CHANNEL_INVALID", "CHAT_ACCESS_DENIED") || ex.code == 403 -> HttpStatusCode.Forbidden
                    ex.code == 429 || ex.reason.startsWith("FLOOD_WAIT") -> HttpStatusCode.TooManyRequests
                    else -> HttpStatusCode.BadGateway
                }
                call.respond(status, ApiError(when (status) {
                    HttpStatusCode.NotFound -> "Public channel not found"
                    HttpStatusCode.Forbidden -> "Channel is unavailable to this account"
                    HttpStatusCode.TooManyRequests -> "Telegram rate limit; try again later"
                    else -> "Telegram request failed"
                }))
            }
            exception<IllegalArgumentException> { call, ex ->
                call.respond(HttpStatusCode.BadRequest, ApiError(ex.message ?: "Bad request"))
            }
            exception<IllegalStateException> { call, ex ->
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError(ex.message ?: "Not ready"))
            }
            exception<Throwable> { call, ex ->
                call.application.environment.log.error("Request failed", ex)
                call.respond(HttpStatusCode.BadGateway, ApiError("Telegram request failed; check local logs"))
            }
        }
        configureRoutes(telegram)
        configureResearchRoutes(telegram, repositories, matching, search, notifications)
    }.start(wait = true)
}
