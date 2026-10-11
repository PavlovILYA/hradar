package dev.radar.agent

import dev.radar.capabilities.LocalMcpSecurity
import dev.radar.research.MonitoredChannel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

@Serializable data class AgentSettings(val available: Boolean, val limits: AgentLimits, val sources: List<MonitoredChannel>, val consent: AgentConsent?, val csrfToken: String, val operator: String)
@Serializable data class AgentConsentInput(val enabled: Boolean, val sources: List<Long>)
@Serializable data class AgentSubmitInput(val question: String)
@Serializable data class AgentApiError(val error: String, val code: String)

fun Application.configureAgentRoutes(service: AgentService) {
    val csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val guard = createRouteScopedPlugin("LocalAgentSecurity") {
        onCall { call ->
            val mutation = call.request.httpMethod != HttpMethod.Get
            if (!LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], mutation) ||
                mutation && call.request.headers["X-Hradar-CSRF"] != csrf) {
                call.respond(HttpStatusCode.Forbidden, AgentApiError("Нужны локальный интерфейс и подтверждение CSRF.", "ACCESS_DENIED"))
            }
            call.response.headers.append("Cache-Control", "no-store")
        }
    }
    routing {
        route("/api/agent") {
            install(guard)
            suspend fun handle(call: ApplicationCall, action: suspend (AgentOwner) -> Unit) {
                try { withContext(Dispatchers.IO) { action(service.resolveOwner(call.request.headers["X-Hradar-Agent-Operator"])) } }
                catch (ex: AgentFailure) {
                    call.respond(when (ex.code) {
                        "ALREADY_ACTIVE", "QUEUE_FULL" -> HttpStatusCode.Conflict
                        "PROVIDER_NOT_CONFIGURED" -> HttpStatusCode.ServiceUnavailable
                        "INVALID_QUESTION" -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.Forbidden
                    }, AgentApiError(AgentService.errorText(ex.code), ex.code))
                }
            }
            get("/settings") { handle(call) { owner -> call.respond(AgentSettings(service.available, service.limits, service.sources(), service.consent(owner), csrf, if (owner == AgentOwner()) "Локальный оператор" else "Telegram-оператор")) } }
            put("/consent") { handle(call) { owner ->
                val input = call.receive<AgentConsentInput>()
                call.respond(service.setConsent(owner, input.enabled, input.sources))
            } }
            get("/jobs") { handle(call) { owner ->
                val before = call.request.queryParameters["before"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: throw AgentFailure("INVALID_QUESTION") } ?: Long.MAX_VALUE
                call.respond(service.history(owner, before))
            } }
            post("/jobs") { handle(call) { owner -> call.respond(HttpStatusCode.Accepted, service.submit(owner, call.receive<AgentSubmitInput>().question)) } }
            post("/jobs/{id}/cancel") { handle(call) { owner -> call.respond(mapOf("cancelled" to service.cancel(owner, call.parameters["id"]))) } }
        }
    }
}
