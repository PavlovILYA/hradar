package dev.radar.sources

import dev.radar.capabilities.LocalMcpSecurity
import dev.radar.research.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable data class OwnerBookmarkInput(val sourceKey: String, val messageId: Long)
@Serializable data class OwnerSearchInput(val search: SearchRequest, val cached: Boolean = false)
@Serializable data class OwnerSearchResult(val messages: List<ResearchMessage>, val cursor: String, val state: String,
    val notice: String?, val fetchedAt: Long, val origin: String)

fun Application.configureOwnerWorkflow(repo: Repositories, reader: SourceReadService, monitoring: LocalMonitoringService) {
    val workflow = OwnerWorkflowService(repo, monitoring::activationPage, monitoring::refreshConfiguration)
    val searchGate = Mutex()
    val local = createRouteScopedPlugin("OwnerWorkflowLocalAccess") {
        onCall { call ->
            call.response.headers.append("Cache-Control", "no-store")
            if (!LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)))
                call.respond(HttpStatusCode.Forbidden)
        }
    }
    routing { route("/api/workspace") {
        install(local)
        post("/templates") { call.respond(workflow.save(call.receive<SearchTemplate>().copy(id = ""))) }
        put("/templates/{id}") { call.respond(workflow.save(call.receive<SearchTemplate>().copy(id = call.parameters["id"]!!))) }
        get("/monitoring") { call.respond(withContext(Dispatchers.IO) { monitoring.list() }) }
        post("/search") {
            val input = call.receive<OwnerSearchInput>()
            require(input.search.mode == "sources" && input.search.sourceKeys.distinct().size in 1..10)
            val template = withContext(Dispatchers.IO) { input.search.templateId?.let(repo::template) }
            val encoded = Json.encodeToString(OwnerSearchInput.serializer(), input.copy(cached = false)) + template.toString()
            val key = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
            val cached = if (input.cached) withContext(Dispatchers.IO) { repo.cachedSearch(key) ?: throw ResourceNotFound() } else searchGate.withLock {
                val page = reader.search(input.search)
                CachedSearchPage(page, System.currentTimeMillis(), "telegram").also { value ->
                    withContext(Dispatchers.IO) { repo.cacheSearch(key, page, value.fetchedAt) }
                }
            }
            call.respond(OwnerSearchResult(cached.page.messages, if (input.cached) "" else cached.page.cursor,
                cached.page.state, cached.page.notice, cached.fetchedAt, cached.origin))
        }
        get("/saved") {
            val before = call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
            call.respond(withContext(Dispatchers.IO) { repo.ownerBookmarks(25, before) })
        }
        post("/saved") {
            val input = call.receive<OwnerBookmarkInput>()
            val message = reader.detail(input.sourceKey, input.messageId)
            withContext(Dispatchers.IO) { repo.saveOwnerBookmark(message) }
            call.respond(mapOf("saved" to true))
        }
        delete("/saved/{id}") {
            withContext(Dispatchers.IO) { if (!repo.removeOwnerBookmark(call.parameters["id"]!!)) throw ResourceNotFound() }
            call.respond(mapOf("deleted" to true))
        }
        delete("/cache") { withContext(Dispatchers.IO) { repo.clearSearchCache() }; call.respond(mapOf("cleared" to true)) }
        get("/desktop") { call.respond(withContext(Dispatchers.IO) { repo.desktopPreferences() }) }
        put("/desktop") { val value = call.receive<DesktopPreferences>(); call.respond(withContext(Dispatchers.IO) { repo.saveDesktopPreferences(value) }) }
        post("/deliveries/claim") { call.respond(withContext(Dispatchers.IO) { repo.claimDesktopDeliveries() }) }
        get("/inbox/{id}") { call.respond(withContext(Dispatchers.IO) { repo.inboxItem(call.parameters["id"]!!) ?: throw ResourceNotFound() }) }
    } }
}
