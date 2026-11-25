package dev.radar.profiles

import dev.radar.desktop.respondDesktopShutdown
import dev.radar.desktop.securityPath
import dev.radar.desktop.bounded
import dev.radar.telegram.ApiCredentials
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class Confirmation(val confirm: Boolean = false)
@Serializable private data class OwnerConfirmation(val userId: Long)
@Serializable private data class CredentialChange(val apiId: Int,val apiHash: String,val confirm: Boolean=false) { override fun toString() = "CredentialChange(redacted)" }
private suspend inline fun <reified T> ApplicationCall.profileInput(): T = bounded(this)

fun Application.configureProfileRoutes(store: ProfileStore, session: ProfileSession, saveCredentials: (ApiCredentials)->Unit, restart: (ProfileChange)->Unit) {
    routing { route("/api/profiles") {
        get("/status") { call.respond(session.status()) }
        post("/add") {
            require(call.profileInput<Confirmation>().confirm) { "Подтвердите добавление аккаунта" }
            val change=withContext(Dispatchers.IO) { session.prepareNew(store::create) }
            commitProfileChange({ call.respondDesktopShutdown {} }, { restart(change) })
        }
        post("/{id}/switch") {
            require(call.profileInput<Confirmation>().confirm) { "Подтвердите переключение" }
            val change=session.prepareChange(call.parameters["id"]!!)
            commitProfileChange({ call.respondDesktopShutdown {} }, { restart(change) })
        }
        post("/{id}/logout") {
            require(call.profileInput<Confirmation>().confirm) { "Подтвердите выход из Telegram" }
            val id=call.parameters["id"]!!
            require(id==session.profileId) { "Сначала переключитесь на этот аккаунт, чтобы выйти из Telegram" }
            val change=session.prepareChange(id,true)
            commitProfileChange({ call.respondDesktopShutdown {} }, { restart(change) })
        }
        post("/confirm-owner") { val input=call.profileInput<OwnerConfirmation>(); withContext(Dispatchers.IO) { session.confirm(input.userId) };call.respond(mapOf("ok" to true)) }
        post("/verify") { require(call.profileInput<Confirmation>().confirm); session.verify();call.respond(session.status()) }
        post("/import-legacy") {
            require(call.profileInput<Confirmation>().confirm) { "Подтвердите импорт прежнего рабочего пространства" }
            val change=withContext(Dispatchers.IO) { session.prepareNew(store::importLegacy) }
            commitProfileChange({ call.respondDesktopShutdown {} }, { restart(change) })
        }
        post("/credentials") {
            check(session.status().credentialsSource!="environment") { "Реквизиты заданы окружением. Измените окружение и перезапустите приложение." }
            val input=call.profileInput<CredentialChange>();require(input.confirm) { "Подтвердите изменение реквизитов приложения" }
            val credentials=ApiCredentials(input.apiId,input.apiHash);credentials.validate()
            val change=withContext(Dispatchers.IO) { session.prepareNew { saveCredentials(credentials); store.active() } }
            commitProfileChange({ call.respondDesktopShutdown {} }, { restart(change) })
        }
    } }
}
/** Every workspace response belongs to one identity and runtime generation. */
fun Application.configureProfileAccess(session: ProfileSession) {
    install(createApplicationPlugin("ProfileAccess") {
        onCall { call ->
            val path=call.securityPath()
            if (!path.startsWith("/api/") && path!="/mcp" && !path.startsWith("/mcp/")) return@onCall
            call.response.headers.append("X-Hradar-Workspace",session.generation)
            if (path=="/api/profiles/status") return@onCall
            val supplied=call.request.headers["X-Hradar-Workspace"] ?: call.request.queryParameters["workspace"]
            if (path!="/mcp" && !path.startsWith("/mcp/") && supplied!=session.generation) {
                call.respondText("{\"error\":\"Профиль изменился. Обновите страницу.\",\"kind\":\"workspace_changed\"}",ContentType.Application.Json,HttpStatusCode.Conflict);return@onCall
            }
            val setup=path.startsWith("/api/onboarding") || path.startsWith("/api/profiles/") || path=="/api/telegram/status" || path=="/api/desktop/shutdown"
            if (!setup && session.phase!="READY") {
                call.respondText("{\"error\":\"Сначала подтвердите текущий Telegram-аккаунт\"}",ContentType.Application.Json,HttpStatusCode.Locked);return@onCall
            }
            if(path.startsWith("/api/onboarding/") && call.request.httpMethod==HttpMethod.Post && !path.endsWith("/credentials")) session.authenticationAttempt()
        }
    })
}
