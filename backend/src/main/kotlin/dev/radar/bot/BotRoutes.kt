package dev.radar.bot

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class TokenInput(val token: String)
@Serializable private data class EnabledInput(val enabled: Boolean)
@Serializable private data class UserInput(val username: String, val id: Long)
@Serializable private data class UsernameInput(val username: String)
private suspend inline fun <reified T> input(call: ApplicationCall): T {
    val length = call.request.headers["Content-Length"]?.toLongOrNull()
    require(length != null && length in 1..4096) { "Недопустимый размер запроса" }
    val body = call.receiveText()
    require(body.length <= 4096) { "Недопустимый размер запроса" }
    return try { Json.decodeFromString<T>(body) } catch (_: Exception) { throw IllegalArgumentException("Некорректный запрос") }
}

fun Application.configureBotRoutes(bot: BotSettingsService) {
    routing { route("/api/integrations/bot") {
        get { call.respond(bot.status().status) }
        get("/settings") { call.respond(bot.status()) }
        post("/check") { call.respond(bot.checkToken(input<TokenInput>(call).token)) }
        put("/connection") { call.respond(bot.connect(input<TokenInput>(call).token)) }
        put("/enabled") { call.respond(bot.enable(input<EnabledInput>(call).enabled)) }
        post("/invitations") { call.respond(bot.createInvitation()) }
        delete("/invitations/{id}") { call.respond(bot.recallInvitation(call.parameters["id"] ?: "")) }
        post("/users/lookup") { call.respond(bot.resolveUser(input<UsernameInput>(call).username)) }
        post("/users") { val user = input<UserInput>(call); call.respond(bot.addUser(user.username, user.id)) }
        delete("/users/{id}") { call.respond(bot.revokeUser(call.parameters["id"]?.toLongOrNull() ?: 0)) }
    } }
}

/** Existing read-only route fixture remains available for isolated BotService tests. */
fun Application.configureBotRoutes(bot: BotService) {
    routing { get("/api/integrations/bot") { call.respond(bot.status()) } }
}
