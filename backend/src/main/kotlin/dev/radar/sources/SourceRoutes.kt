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

fun Application.configureSourceRoutes(service: SourceCatalogService, repo: Repositories, reader: SourceReadService? = null, archive: SourceArchiveService? = null, monitoring: LocalMonitoringService? = null) {
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
            if (reader != null && archive != null) {
                fun io.ktor.server.application.ApplicationCall.key() = parameters["key"].orEmpty().also { require(it.length in 1..36) { "Некорректный источник" } }
                fun io.ktor.server.application.ApplicationCall.before(): Long? = request.queryParameters["before"]?.let { value ->
                    (value.toLongOrNull() ?: throw BadRequestException("Некорректный курсор")).also { require(it > 0) { "Некорректный курсор" } }
                }
                fun io.ktor.server.application.ApplicationCall.limit(): Int = request.queryParameters["limit"]?.let { value ->
                    (value.toIntOrNull() ?: throw BadRequestException("Некорректный лимит")).also { require(it in 1..100) { "Некорректный лимит" } }
                } ?: 50
                get("/{key}/messages") { call.respond(reader.recent(call.key(), call.before() ?: 0, call.limit())) }
                get("/{key}/messages/{message}") {
                    val id = call.parameters["message"]?.toLongOrNull() ?: throw BadRequestException("Некорректное сообщение")
                    call.respond(reader.detail(call.key(), id))
                }
                post("/{key}/messages/{message}/discussion") {
                    val id = call.parameters["message"]?.toLongOrNull() ?: throw BadRequestException("Некорректное сообщение")
                    call.respond(HttpStatusCode.Created, reader.discussion(call.key(), id))
                }
                get("/{key}/archive") {
                    val key = call.key(); val before = call.before(); val limit = call.limit(); val query = call.request.queryParameters["query"].orEmpty()
                    call.respond(withContext(Dispatchers.IO) { repo.sourceArchive(key, limit, before, query) })
                }
                get("/{key}/sync") {
                    val key = call.key()
                    call.respond(withContext(Dispatchers.IO) {
                        val source = repo.sources().firstOrNull { it.key == key } ?: throw ResourceNotFound()
                        repo.sourceSync(key) ?: SourceSyncRecord(key, source.chatId, source.title)
                    })
                }
                post("/{key}/sync") {
                    val key = call.key()
                    val text = call.receiveText()
                    require(text.length <= 1024) { "Некорректный импорт" }
                    val input = if (text.isBlank()) dev.radar.history.HistorySyncOptions() else Json.decodeFromString<dev.radar.history.HistorySyncOptions>(text)
                    call.respond(HttpStatusCode.Accepted, archive.start(key, input.maxMessages))
                }
                post("/{key}/stop") {
                    val key = call.key()
                    val source = withContext(Dispatchers.IO) { repo.sources().firstOrNull { it.key == key } } ?: throw ResourceNotFound()
                    call.respond(archive.stop(key) ?: SourceSyncRecord(key, source.chatId, source.title))
                }
            }
            if (monitoring != null) {
                get("/{key}/monitoring") {
                    val key = call.parameters["key"].orEmpty()
                    require(key.length in 1..36) { "Некорректный источник" }
                    call.respond(withContext(Dispatchers.IO) {
                        if (repo.sources().none { it.key == key }) throw ResourceNotFound()
                        monitoring.list(key)
                    })
                }
                post("/{key}/monitoring") {
                    val key = call.parameters["key"].orEmpty()
                    val input = call.receive<ConfigureLocalMonitoring>()
                    call.respond(monitoring.configure(key, input.templateId, input.enabled))
                }
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
        if (monitoring != null) route("/api/local-notifications") {
            install(local)
            get {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw BadRequestException("Некорректный лимит") } ?: 50
                val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw BadRequestException("Некорректный курсор") } ?: Long.MAX_VALUE
                require(limit in 1..100 && before > 0) { "Некорректная страница" }
                call.respond(withContext(Dispatchers.IO) { repo.localMatches(limit, before) })
            }
            post("/{id}/read") {
                val id = call.parameters["id"].orEmpty()
                withContext(Dispatchers.IO) { if (!repo.markLocalRead(id)) throw ResourceNotFound() }
                call.respond(mapOf("read" to true))
            }
            post("/read-all") { withContext(Dispatchers.IO) { repo.markAllLocalRead() }; call.respond(mapOf("read" to true)) }
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
