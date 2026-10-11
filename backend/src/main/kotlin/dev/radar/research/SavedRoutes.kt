package dev.radar.research

import dev.radar.capabilities.LocalMcpSecurity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

/** Local read-only review view. No operator IDs, credentials or workflow state are returned. */
fun Application.configureSavedRoutes(repo: Repositories) {
    routing {
        get("/api/saved") {
            if (!LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], false)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Local access required")); return@get
            }
            val before = call.request.queryParameters["before"]?.let { requireNotNull(it.toLongOrNull()) } ?: Long.MAX_VALUE
            val limit = call.request.queryParameters["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 25
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(withContext(Dispatchers.IO) { repo.savedResults(limit, before, status = status) })
        }
    }
}
