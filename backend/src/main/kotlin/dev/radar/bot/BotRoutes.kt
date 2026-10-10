package dev.radar.bot

import io.ktor.server.application.Application
import io.ktor.server.routing.*
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only operational summary. No credentials, IDs, contents or delivery mutation. */
fun Application.configureBotRoutes(bot: BotService) {
    routing { get("/api/integrations/bot") { call.respond(withContext(Dispatchers.IO) { bot.status() }) } }
}
