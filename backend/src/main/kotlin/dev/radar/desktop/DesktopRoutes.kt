package dev.radar.desktop

import dev.radar.telegram.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class LaunchInput(val token: String)
fun Application.configureDesktopSecurity(access: DesktopAccess?, csrf: String) {
    val guard = createApplicationPlugin("DesktopSecurity") {
        onCall { call ->
            val path = call.request.path()
            val host = call.request.headers["Host"]
            val origin = call.request.headers["Origin"]
            val validHost = host in DesktopRuntime.authorities
            val validOrigin = DesktopRuntime.validOrigin(origin)
            // MCP retains its separate explicit enable/source grants and exact Host/Origin guard.
            val api = path.startsWith("/api/")
            val login = path.startsWith("/api/onboarding") || path == "/api/desktop/shutdown"
            val botSettings = path.startsWith("/api/integrations/bot")
            val sourceSettings = path == "/api/sources" || path.startsWith("/api/sources/") || path.startsWith("/api/source-catalog")
            val mutation = call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)
            if (!validHost || !validOrigin || (access != null && api && call.request.cookies["hradar-desktop"] != access.capability) ||
                ((login || botSettings || sourceSettings) && mutation && (origin == null || call.request.headers["X-Hradar-CSRF"] != csrf))) {
                call.respondText("{\"error\":\"Доступ запрещён\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
            }
            if (api || path.startsWith("/desktop/")) call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Content-Security-Policy", "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        }
    }
    install(guard)
    if (access != null) routing {
        fun ApplicationCall.processCookie() {
            response.cookies.append(Cookie("hradar-desktop", access.capability, httpOnly = true, path = "/", extensions = mapOf("SameSite" to "Strict")))
        }
        post("/desktop/launch") {
            if (!DesktopRuntime.sameOrigin(call.request.headers["Host"], call.request.headers["Origin"]) ||
                !access.consumeLaunch(bounded<LaunchInput>(call).token)) {
                call.respond(HttpStatusCode.Forbidden); return@post
            }
            call.processCookie()
            call.respond(mapOf("ok" to true))
        }
        post("/desktop/recover") {
            if (!DesktopRuntime.sameOrigin(call.request.headers["Host"], call.request.headers["Origin"]) ||
                call.request.headers["X-Hradar-Recovery"] != "1" || call.request.cookies["hradar-desktop"] != access.capability) {
                call.respond(HttpStatusCode.Forbidden); return@post
            }
            // A native launch/reopen refreshes this cookie for every tab of this origin.
            // Never exchange a previous process bearer for current authority.
            call.respond(mapOf("ok" to true))
        }
        post("/desktop/reopen") {
            // Only the owner launcher knows the process secret in the private marker.
            // Browser requests (even same-origin) must not mint new launch codes.
            if (call.request.headers["Origin"] != null || call.request.headers["Sec-Fetch-Site"] != null ||
                call.request.headers["Host"] != "127.0.0.1:${DesktopRuntime.port}") {
                call.respond(HttpStatusCode.Forbidden); return@post
            }
            val input = bounded<ReopenInput>(call)
            if (!input.nonce.matches(Regex("[A-Za-z0-9_-]{43}")) ||
                input.proof != instanceProof(access.capability, "request:${input.nonce}")) {
                call.respond(HttpStatusCode.Forbidden); return@post
            }
            val launch = access.issueLaunch()
            call.respond(mapOf("launch" to launch, "proof" to instanceProof(access.capability, "response:${input.nonce}:$launch")))
        }
    }
}

private suspend inline fun <reified T> bounded(call: ApplicationCall): T {
    val length = call.request.headers["Content-Length"]?.toLongOrNull()
    require(length != null && length in 1..4096) { "Недопустимый размер запроса" }
    val text = call.receiveText(); require(text.length <= 4096) { "Недопустимый размер запроса" }
    return try { Json.decodeFromString<T>(text) } catch (_: Exception) { throw IllegalArgumentException("Некорректный формат запроса") }
}
fun Application.configureOnboarding(telegram: TdlibGateway, paths: RuntimePaths, csrf: String, shutdown: () -> Unit) {
    routing {
        route("/api/onboarding") {
            get { call.respond(telegram.onboarding.status().copy(csrfToken = csrf)) }
            post("/credentials") {
                check(!DesktopRuntime.smoke) { "Smoke mode does not create a Telegram client" }
                val input = bounded<ApiCredentials>(call); input.validate()
                check(telegram.onboarding.status().state in setOf("Credentials", "ParametersFailed")) { "TDLib уже настроен" }
                telegram.configure(input) { paths.saveCredentials(input) }
                call.respond(mapOf("ok" to true))
            }
            post("/login") { telegram.onboarding.submit(bounded<LoginInput>(call)); call.respond(mapOf("ok" to true)) }
            post("/resend") { telegram.onboarding.resend(bounded<LoginInput>(call)); call.respond(mapOf("ok" to true)) }
            post("/phone") { telegram.onboarding.changePhone(bounded<LoginInput>(call)); call.respond(mapOf("ok" to true)) }
        }
        if (DesktopRuntime.enabled) post("/api/desktop/shutdown") { call.respond(mapOf("ok" to true)); shutdown() }
    }
}
