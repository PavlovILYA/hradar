package dev.radar.sources

import dev.radar.research.Repositories
import dev.radar.research.ResourceNotFound
import dev.radar.desktop.DesktopRuntime
import dev.radar.capabilities.LocalMcpSecurity
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun Application.configureSourceRoutes(service: SourceCatalogService, repo: Repositories) {
    val local = createRouteScopedPlugin("SourceCatalogLocalAccess") {
        onCall { call ->
            call.response.headers.append("Cache-Control", "no-store")
            val mutation = call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)
            val desktop = DesktopRuntime.enabled && call.request.headers["Host"] == "127.0.0.1:${DesktopRuntime.port}" &&
                (call.request.headers["Origin"] == "http://127.0.0.1:${DesktopRuntime.port}" || (!mutation && call.request.headers["Origin"] == null))
            if (!desktop && !LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], mutation))
                call.respondText("{\"error\":\"Требуется локальный доступ\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
    }
    routing {
        route("/api/sources") {
            install(local)
            get { call.respond(withContext(Dispatchers.IO) { SourcePage(repo.sources()) }) }
            post {
                val text = call.receiveText()
                if (text.length > 4096) throw BadRequestException("Некорректный источник")
                val input = try { Json.decodeFromString<SaveSourceInput>(text) } catch (_: SerializationException) { throw BadRequestException("Некорректный источник") }
                call.respond(HttpStatusCode.Created, service.save(input))
            }
            post("/{key}/refresh") {
                val key = call.parameters["key"].orEmpty()
                require(key.length in 1..36) { "Некорректный источник" }
                call.respond(service.refresh(key))
            }
            delete("/{key}") {
                val key = call.parameters["key"].orEmpty()
                require(key.length in 1..36) { "Некорректный источник" }
                if (!withContext(Dispatchers.IO) { repo.deleteSource(key) }) throw ResourceNotFound()
                call.respond(mapOf("deleted" to true))
            }
        }
        route("/api/source-catalog") {
            install(local)
            get {
                val q = call.request.queryParameters
                call.respond(service.browse(q["query"].orEmpty(), q["filter"] ?: "all", q["cursor"]))
            }
            get("/{chatId}/topics") {
                val q = call.request.queryParameters
                val chat = call.parameters["chatId"]?.toLongOrNull() ?: throw BadRequestException("Некорректный чат")
                fun int(name: String) = q[name]?.toIntOrNull() ?: if (q[name] == null) 0 else throw BadRequestException("Некорректная страница тем")
                val message = q["messageId"]?.toLongOrNull() ?: if (q["messageId"] == null) 0 else throw BadRequestException("Некорректная страница тем")
                call.respond(service.topics(chat, TopicCursor(int("date"), message, int("forumTopicId"))))
            }
        }
    }
}
