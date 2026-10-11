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
fun Application.configureDesktopSecurity(token: String?, csrf: String) {
    val guard = createApplicationPlugin("DesktopSecurity") {
        onCall { call ->
            val path = call.request.path()
            val host = call.request.headers["Host"]
            val origin = call.request.headers["Origin"]
            val validHost = host in setOf("127.0.0.1:8080", "127.0.0.1:5173")
            val validOrigin = origin == null || origin in setOf("http://127.0.0.1:8080", "http://127.0.0.1:5173")
            val api = path.startsWith("/api/") || path == "/mcp"
            val login = path.startsWith("/api/onboarding") || path == "/api/desktop/shutdown"
            val mutation = call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)
            if (!validHost || !validOrigin || (token != null && api && call.request.cookies["hradar-desktop"] != token) ||
                (login && mutation && (origin == null || call.request.headers["X-Hradar-CSRF"] != csrf))) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Доступ запрещён"))
            }
            if (login || path == "/desktop/launch") call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
        }
    }
    install(guard)
    if (token != null) routing {
        post("/desktop/launch") {
            if (call.request.headers["Origin"] != "http://127.0.0.1:8080" || bounded<LaunchInput>(call).token != token) {
                call.respond(HttpStatusCode.Forbidden); return@post
            }
            call.response.cookies.append(Cookie("hradar-desktop", token, httpOnly = true, path = "/", extensions = mapOf("SameSite" to "Strict")))
            call.respond(mapOf("ok" to true))
        }
    }
}
private suspend inline fun <reified T> bounded(call: ApplicationCall): T {
    val length = call.request.headers["Content-Length"]?.toLongOrNull()
    require(length != null && length in 1..4096) { "Недопустимый размер запроса" }
    val text = call.receiveText(); require(text.length <= 4096) { "Недопустимый размер запроса" }
    return Json.decodeFromString<T>(text)
}
fun Application.configureOnboarding(telegram: TdlibGateway, paths: RuntimePaths, csrf: String, shutdown: () -> Unit) {
    routing {
        route("/api/onboarding") {
            get { call.respond(telegram.onboarding.status().copy(csrfToken = csrf)) }
            post("/credentials") {
                val input = bounded<ApiCredentials>(call); input.validate()
                check(telegram.onboarding.status().state == "Credentials") { "TDLib уже настроен" }
                paths.saveCredentials(input); telegram.configure(input)
                call.respond(mapOf("ok" to true))
            }
            post("/login") { telegram.onboarding.submit(bounded<LoginInput>(call)); call.respond(mapOf("ok" to true)) }
        }
        if (DesktopRuntime.enabled) post("/api/desktop/shutdown") { call.respond(mapOf("ok" to true)); shutdown() }
    }
}
