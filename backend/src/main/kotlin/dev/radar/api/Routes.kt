package dev.radar.api

import dev.radar.telegram.TdlibGateway
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(val status: String = "ok")

internal fun messageLimit(value: String?): Int {
    val limit = if (value == null) 10 else value.toIntOrNull()
        ?: throw IllegalArgumentException("limit must be an integer in 1..100")
    require(limit in 1..100) { "limit must be in 1..100" }
    return limit
}

fun Application.configureRoutes(telegram: TdlibGateway) {
    routing {
        get("/health") { call.respond(HealthDto()) }
        get("/api/telegram/status") { call.respond(telegram.getStatus()) }
        get("/api/telegram/me") { call.respond(telegram.getMe()) }
        get("/api/telegram/chats/{username}/messages") {
            val username = call.parameters["username"]
                ?: throw IllegalArgumentException("Missing username")
            require(username.matches(Regex("@?[a-zA-Z0-9_]{5,32}"))) {
                "Expected a public channel username, for example @examplechannel"
            }
            val limit = messageLimit(call.request.queryParameters["limit"])
            call.respond(telegram.getRecentMessages(username.removePrefix("@"), limit))
        }
    }
}
