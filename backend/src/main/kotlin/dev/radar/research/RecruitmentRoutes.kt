package dev.radar.research

import dev.radar.ApiError
import dev.radar.respondApiError
import dev.radar.capabilities.LocalMcpSecurity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

fun Application.configureRecruitmentRoutes(service: RecruitmentProjectService) {
    val localAccess = createRouteScopedPlugin("RecruitmentLocalAccess") {
        onCall { call ->
            call.response.headers.append("Cache-Control", "no-store")
            if (!LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], call.request.httpMethod != HttpMethod.Get)) {
                call.respondApiError(HttpStatusCode.Forbidden, ApiError("Требуется локальный доступ"))
            }
        }
    }
    routing {
        route("/api/projects") {
            install(localAccess)
            fun ApplicationCall.id() = parameters["id"] ?: throw IllegalArgumentException("Нужен проект")
            fun ApplicationCall.number(key: String, default: Int) = request.queryParameters[key]?.let { requireNotNull(it.toIntOrNull()) { "Некорректный $key" } } ?: default
            get {
                val q = call.request.queryParameters
                call.respond(withContext(Dispatchers.IO) { service.list(call.number("limit", 25), call.number("offset", 0), q["status"]?.let(ProjectStatus::valueOf), q["query"].orEmpty()) })
            }
            post { val input = call.receive<ProjectInput>(); call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.save(input) }) }
            get("/{id}") { call.respond(withContext(Dispatchers.IO) { service.get(call.id()) }) }
            put("/{id}") { val input = call.receive<ProjectInput>(); call.respond(withContext(Dispatchers.IO) { service.save(input, call.id()) }) }
            delete("/{id}") { withContext(Dispatchers.IO) { service.delete(call.id()) }; call.respond(mapOf("deleted" to true)) }
            get("/{id}/matches") {
                val before = call.request.queryParameters["before"]?.let { requireNotNull(it.toLongOrNull()) } ?: Long.MAX_VALUE
                call.respond(withContext(Dispatchers.IO) { service.matches(call.id(), call.number("limit", 20), before) })
            }
            get("/{id}/statistics") { call.respond(withContext(Dispatchers.IO) { service.statistics(call.id()) }) }
            get("/{id}/cards") {
                val q = call.request.queryParameters
                call.respond(withContext(Dispatchers.IO) { service.cards(call.id(), call.number("limit", 25), call.number("offset", 0), q["stage"]?.let(RecruitmentStage::valueOf), q["source"]?.let { requireNotNull(it.toLongOrNull()) }, q["query"].orEmpty(), q["technology"].orEmpty(), q["sort"] ?: "updated") })
            }
            post("/{id}/cards") { val input = call.receive<AddPublication>(); call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.add(call.id(), input) }) }
            put("/{id}/cards/{card}") { val input = call.receive<CardUpdate>(); call.respond(withContext(Dispatchers.IO) { service.update(call.id(), call.parameters["card"]!!, input) }) }
            delete("/{id}/cards/{card}") { withContext(Dispatchers.IO) { service.remove(call.id(), call.parameters["card"]!!) }; call.respond(mapOf("deleted" to true)) }
            post("/{id}/search") { val input = call.receive<ProjectSearch>(); call.respond(withContext(Dispatchers.IO) { service.search(call.id(), input) }) }
            post("/{id}/subscription") { call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.subscribe(call.id()) }) }
        }
    }
}
