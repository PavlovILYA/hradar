package dev.radar.profiles

import dev.radar.capabilities.LocalMcpSecurity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException

/** Runtime generation and owner-session gates are installed by the containing application. */
fun Application.configureAvatarRoutes(service: AvatarService) {
    routing {
        suspend fun ApplicationCall.avatar(load: suspend () -> ByteArray?) {
            response.headers.append(HttpHeaders.CacheControl, "no-store")
            response.headers.append("X-Content-Type-Options", "nosniff")
            if (!LocalMcpSecurity.valid(request.headers["Host"], request.headers["Origin"], false)) {
                respond(HttpStatusCode.Forbidden); return
            }
            val bytes = try { load() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (bytes == null) respond(HttpStatusCode.NotFound)
            else respondBytes(bytes, ContentType.Image.PNG)
        }
        get("/api/profiles/avatar") { call.avatar { service.self() } }
        get("/api/avatars/chats/{chatId}") {
            call.avatar { call.parameters["chatId"]?.toLongOrNull()?.let { service.chat(it) } }
        }
    }
}
