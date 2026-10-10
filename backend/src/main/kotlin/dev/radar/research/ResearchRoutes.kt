package dev.radar.research

import dev.radar.telegram.TdlibGateway
import dev.radar.api.messageLimit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.http.ContentType
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.drinkless.tdlib.TdApi

@Serializable data class ChannelInput(val username: String)
@Serializable data class Deleted(val deleted: Boolean)
@Serializable data class PreviewMatch(val messageId: Long, val explanation: MatchExplanation)
class ResourceNotFound : RuntimeException()

fun Application.configureResearchRoutes(telegram: TdlibGateway, repo: Repositories, matching: MatchingService, search: SearchService, notifications: NotificationService) {
    val subscriptions = SubscriptionService(repo, matching)
    routing {
        get("/api/notifications") {
            val limit = messageLimit(call.request.queryParameters["limit"] ?: "50")
            val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Некорректный курсор") } ?: Long.MAX_VALUE
            call.respond(withContext(Dispatchers.IO) { notifications.page(limit, before) })
        }
        post("/api/notifications/read-all") { withContext(Dispatchers.IO) { notifications.readAll() }; call.respond(Deleted(true)) }
        post("/api/notifications/{id}/read") { withContext(Dispatchers.IO) { notifications.read(call.parameters["id"]!!) }; call.respond(Deleted(true)) }
        get("/api/monitoring") { call.respond(withContext(Dispatchers.IO) { repo.channels().map { repo.state(it.id) ?: MonitoringState(it.id) } }) }
        get("/api/events") {
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                try {
                while (currentCoroutineContext().isActive) {
                    val summary = withContext(Dispatchers.IO) { notifications.summary().copy(telegram = telegram.getStatus()) }
                    write("data: ${Json.encodeToString(summary)}\n\n")
                    flush()
                    delay(3000)
                }
                } catch (_: java.io.IOException) { /* Browser disconnected. EventSource will reconnect. */ }
            }
        }
        get("/api/messages/{chatId}/{id}/link") {
            val chatId = call.parameters["chatId"]?.toLongOrNull() ?: throw IllegalArgumentException("Некорректный ID канала")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Некорректный ID сообщения")
            val link = telegram.executeRead(TdApi.GetMessageLink().apply { this.chatId = chatId; messageId = id }) as TdApi.MessageLink
            call.respond(mapOf("link" to link.link))
        }
        post("/api/search") { call.respond(search.search(call.receive<SearchRequest>())) }
        get("/api/channels") { call.respond(withContext(Dispatchers.IO) { repo.channels() }) }
        post("/api/channels") {
            val username = call.receive<ChannelInput>().username.trim().removePrefix("@")
            require(username.matches(Regex("[a-zA-Z0-9_]{5,32}"))) { "Введите username публичного канала" }
            val channel = telegram.resolveChannel(username)
            val head = telegram.getRecentMessages(username, 1).firstOrNull()?.id ?: 0L
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) {
                repo.saveChannel(channel).also { if (repo.state(channel.id) == null) repo.saveState(MonitoringState(channel.id, head)) }
            })
        }
        delete("/api/channels/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Некорректный ID канала")
            val deleted = withContext(Dispatchers.IO) { repo.deleteChannel(id) }
            if (!deleted) throw ResourceNotFound()
            call.respond(Deleted(true))
        }
        get("/api/templates") { call.respond(withContext(Dispatchers.IO) { subscriptions.list() }) }
        post("/api/templates") {
            val template = call.receive<SearchTemplate>().copy(id = "")
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { subscriptions.save(template) })
        }
        put("/api/templates/{id}") {
            val id = call.parameters["id"]!!
            val template = call.receive<SearchTemplate>().copy(id = id)
            call.respond(withContext(Dispatchers.IO) { subscriptions.save(template, mustExist = true) })
        }
        delete("/api/templates/{id}") {
            if (!withContext(Dispatchers.IO) { subscriptions.delete(call.parameters["id"]!!) }) throw ResourceNotFound()
            call.respond(Deleted(true))
        }
        get("/api/templates/{id}/preview") {
            val template = withContext(Dispatchers.IO) { repo.template(call.parameters["id"]!!) } ?: throw ResourceNotFound()
            val username = call.request.queryParameters["username"] ?: throw IllegalArgumentException("Укажите username")
            require(username.matches(Regex("@?[a-zA-Z0-9_]{5,32}")))
            val messages = telegram.getRecentMessages(username.removePrefix("@"), messageLimit(call.request.queryParameters["limit"]))
            call.respond(messages.map { PreviewMatch(it.id, matching.check(template, it.text)) })
        }
    }
}
