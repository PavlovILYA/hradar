package dev.radar.capabilities

import dev.radar.research.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64

@Serializable data class IntegrationConfig(val enabled: Boolean, val endpoint: String, val tools: List<ToolInfo>, val allowedSources: List<MonitoredChannel>, val availableSources: List<MonitoredChannel>, val csrfToken: String, val readOnly: Boolean = false)
@Serializable data class EnableInput(val enabled: Boolean)
@Serializable data class GrantInput(val username: String, val allowed: Boolean)

/** Exact authorities/origins: don't trust forwarded headers or arbitrary loopback DNS names. */
object LocalMcpSecurity {
    fun valid(host: String?, origin: String?, management: Boolean): Boolean {
        if (host !in setOf("127.0.0.1:8080", "127.0.0.1:5173")) return false
        val validOrigin = origin in setOf("http://127.0.0.1:8080", "http://127.0.0.1:5173")
        return if (management) validOrigin else origin == null || validOrigin
    }
}
fun Application.configureCapabilities(service: CapabilityService, port: TelegramReadPort, repo: Repositories) {
    val csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val guard = createRouteScopedPlugin("LocalCapabilitySecurity") {
        onCall { call ->
            val management = call.request.path().startsWith("/api/integrations/mcp") && call.request.httpMethod != HttpMethod.Get
            val allowed = LocalMcpSecurity.valid(call.request.headers["Host"], call.request.headers["Origin"], management)
            if (!allowed || (management && call.request.headers["X-Hradar-CSRF"] != csrf)) {
                call.respond(HttpStatusCode.Forbidden, CapabilityResult(false, error = CapabilityError("ACCESS_DENIED", "Local origin and UI confirmation are required")))
            }
        }
    }
    routing {
        route("/mcp") {
            install(guard)
            // Scoped serializer: leave the existing REST JSON/null behavior unchanged.
            install(ContentNegotiation) { json(McpJson) }
            post {
                if (!withContext(Dispatchers.IO) { repo.mcpEnabled() }) {
                    call.respond(HttpStatusCode.Forbidden, CapabilityResult(false, error = CapabilityError("DISABLED", "Enable MCP inside hradar"))); return@post
                }
                val server = McpTools.server(service)
                val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true, maxRequestBodySize = 32768)).apply { setSessionIdGenerator(null) }
                try { server.createSession(transport); transport.handleRequest(null, call) }
                finally { withContext(NonCancellable) { server.close() } }
            }
            get { call.respond(HttpStatusCode.MethodNotAllowed) }
            delete { call.respond(HttpStatusCode.MethodNotAllowed) }
        }
        route("/api/capabilities") {
            install(guard)
            McpTools.definitions.forEach { tool -> post("/${tool.name}") {
                val text = call.receiveText(); require(text.length <= 16384)
                call.respond(service.call(tool.name, Json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject))
            } }
        }
        route("/api/integrations/mcp") {
            install(guard)
            get {
                call.response.headers.append("Cache-Control", "no-store")
                call.respond(withContext(Dispatchers.IO) {
                    val available = (repo.channels() + repo.historySyncs().map { MonitoredChannel(it.channelId, it.username, it.title) }).distinctBy { it.id }.take(50)
                    IntegrationConfig(repo.mcpEnabled(), "http://127.0.0.1:8080/mcp", McpTools.definitions, repo.mcpSources(), available, csrf)
                })
            }
            put {
                val input = call.receive<EnableInput>(); withContext(Dispatchers.IO) { repo.setMcpEnabled(input.enabled) }; call.respond(mapOf("enabled" to input.enabled))
            }
            post("/sources") {
                val input = call.receive<GrantInput>(); require(input.username.matches(Regex("[a-zA-Z0-9_]{5,32}")))
                val existing = withContext(Dispatchers.IO) { repo.mcpSources().find { it.username.equals(input.username, ignoreCase = true) } }
                if (!input.allowed) { withContext(Dispatchers.IO) { existing?.let { repo.revokeMcpSource(it.id) } } }
                else {
                    val public = port.resolve(input.username)
                    require(public.id < 0) { "Only public channels are supported" }
                    withContext(Dispatchers.IO) { require(repo.mcpSources().size < 25 || repo.mcpSources().any { it.id == public.id }); repo.allowMcpSource(public) }
                }
                call.respond(mapOf("allowed" to input.allowed))
            }
        }
    }
}
