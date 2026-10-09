package dev.radar.history

import dev.radar.research.MonitoredChannel
import dev.radar.research.Repositories
import dev.radar.telegram.TdlibGateway
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Application.configureHistoryRoutes(telegram: TdlibGateway, repo: Repositories, sync: HistorySyncService) {
    fun username(value: String?): String {
        val name = value?.trim()?.removePrefix("@")?.lowercase() ?: throw IllegalArgumentException("Укажите username")
        require(name.matches(Regex("[a-z0-9_]{5,32}"))) { "Введите username публичного канала" }
        return name
    }
    routing {
        get("/api/history/syncs") { call.respond(withContext(Dispatchers.IO) { repo.historySyncs() }) }
        post("/api/history/{username}/sync") {
            val name = username(call.parameters["username"])
            val body = call.receiveText()
            val options = if (body.isBlank()) HistorySyncOptions() else Json.decodeFromString<HistorySyncOptions>(body)
            require(options.maxMessages == null || options.maxMessages in setOf(1000, 5000, 10000)) { "Некорректный лимит импорта" }
            val existing = withContext(Dispatchers.IO) { repo.historySyncByUsername(name) }
            val channel = existing?.let { MonitoredChannel(it.channelId, it.username, it.title) } ?: telegram.resolveChannel(name)
            call.respond(HttpStatusCode.Accepted, sync.start(channel, options.maxMessages))
        }
        post("/api/history/{username}/stop") {
            val name = username(call.parameters["username"])
            val state = withContext(Dispatchers.IO) { repo.historySyncByUsername(name) }
            call.respond(state?.let { sync.stop(it.channelId) } ?: HistorySyncRecord(username = name))
        }
        get("/api/history/{username}/sync") {
            val name = username(call.parameters["username"])
            call.respond(withContext(Dispatchers.IO) { repo.historySyncByUsername(name) } ?: HistorySyncRecord(username = name))
        }
        get("/api/history/{username}/messages") {
            val name = username(call.parameters["username"])
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Некорректный limit") } ?: 50
            require(limit in 1..100) { "limit должен быть от 1 до 100" }
            val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Некорректный курсор") }
            require(before == null || before > 0) { "Курсор должен быть положительным ID сообщения" }
            val query = call.request.queryParameters["query"].orEmpty().trim()
            require(query.length <= 256) { "Запрос: не более 256 символов" }
            call.respond(withContext(Dispatchers.IO) {
                val state = repo.historySyncByUsername(name)
                if (state == null) HistoryPage(emptyList(), null, 0, query) else repo.historyMessages(state.channelId, limit, before, query)
            })
        }
    }
}
