package dev.radar.research

import dev.radar.ApiError
import dev.radar.respondApiError
import dev.radar.history.HistorySyncRecord
import dev.radar.telegram.TelegramMessage
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** Opt-in local browser fixture. Temporary SQLite only, no TDLib construction or credentials. */
object RecruitmentBrowserFixture {
    @JvmStatic fun main(args: Array<String>) {
        val repo = Repositories(Files.createTempDirectory("hradar-crm-browser-").resolve("research.sqlite"))
        val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic source")
        repo.saveChannel(source)
        val text = "Ищу работу Senior Java Spring Boot PostgreSQL Kafka Docker"
        repo.saveHistoryPage(HistorySyncRecord(source.id, source.username, source.title), listOf(TelegramMessage(1, 1700000000, text, source.id)))
        repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000)
        repo.decidePublication(123, 7, ResearchMessage(1, source.id, 1700000000, text, source.title, source.username, "https://t.me/synthetic_jobs/1"), "SAVED")
        val matching = MatchingService()
        val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { error("Fixture permits archive reads only") }))
        Runtime.getRuntime().addShutdownHook(Thread { repo.close() })
        embeddedServer(Netty, host = "127.0.0.1", port = 18081) {
            routing { install(ContentNegotiation) { json(Json { encodeDefaults = true }) } }
            install(StatusPages) {
                exception<ResourceNotFound> { call, _ -> call.respondApiError(HttpStatusCode.NotFound, ApiError("Ресурс не найден")) }
                exception<IllegalArgumentException> { call, ex -> call.respondApiError(HttpStatusCode.BadRequest, ApiError(ex.message ?: "Некорректный запрос")) }
                exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respondApiError(HttpStatusCode.BadRequest, ApiError("Некорректный JSON")) }
            }
            configureRecruitmentRoutes(service)
        }.start(wait = true)
    }
}
