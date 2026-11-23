package dev.radar.documents

import dev.radar.capabilities.LocalMcpSecurity
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

private suspend inline fun <reified T> ApplicationCall.documentInput(): T {
    val channel=receiveChannel()
    val bytes=ByteArray(4097)
    var size=0
    while(size<bytes.size) {
        val count=channel.readAvailable(bytes,size,bytes.size-size)
        if(count<0) break
        size+=count
    }
    require(size<=4096) { "Запрос документов слишком большой" }
    val text=try { bytes.copyOf(size).decodeToString(throwOnInvalidSequence=true) }
        catch (_: Exception) { throw IllegalArgumentException("Некорректный запрос документов") }
    return try { Json.decodeFromString<T>(text) }
        catch (_: SerializationException) { throw IllegalArgumentException("Некорректный запрос документов") }
}

fun Application.configureDocuments(service: DocumentService) {
    val local = createRouteScopedPlugin("DocumentsLocalAccess") {
        onCall { call ->
            call.response.headers.append("Cache-Control","no-store")
            if (!LocalMcpSecurity.valid(call.request.headers["Host"],call.request.headers["Origin"],call.request.httpMethod !in setOf(HttpMethod.Get,HttpMethod.Head)))
                call.respond(HttpStatusCode.Forbidden)
        }
    }
    routing { route("/api/documents") {
        install(local)
        fun limit(call: ApplicationCall) = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Некорректная страница") } ?: 50
        fun offset(call: ApplicationCall) = call.request.queryParameters["offset"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Некорректная страница") } ?: 0
        get { call.respond(withContext(Dispatchers.IO) { service.list(limit(call),offset(call),call.request.queryParameters["sourceKey"]) }) }
        get("/search") { call.respond(withContext(Dispatchers.IO) { service.search(call.request.queryParameters["q"].orEmpty(),limit(call),offset(call)) }) }
        post("/discover") { val input = call.documentInput<DocumentDiscoverInput>(); call.respond(service.discover(input)) }
        post("/register") { val input = call.documentInput<DocumentRegisterInput>(); call.respond(service.register(input.sourceKey,input.messageId)) }
        post("/{id}/download") { call.respond(withContext(Dispatchers.IO) { service.download(call.parameters["id"]!!) }) }
        post("/{id}/cancel") { call.respond(service.cancel(call.parameters["id"]!!)) }
        post("/{id}/reindex") { call.respond(withContext(Dispatchers.IO) { service.download(call.parameters["id"]!!,true) }) }
        get("/{id}") { call.respond(withContext(Dispatchers.IO) { service.record(call.parameters["id"]!!) }) }
        get("/{id}/text") { call.respond(withContext(Dispatchers.IO) { service.text(call.parameters["id"]!!) }) }
        get("/{id}/file") {
            val (record,path) = withContext(Dispatchers.IO) { service.original(call.parameters["id"]!!) }
            call.response.headers.append("X-Content-Type-Options","nosniff")
            call.response.headers.append("Content-Security-Policy","sandbox; default-src 'none'")
            val safeName = record.fileName.replace(Regex("[^A-Za-z0-9._-]"),"_")
            call.response.headers.append(HttpHeaders.ContentDisposition,ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName,safeName).toString())
            call.respond(LocalFileContent(path.toFile(),ContentType.Application.OctetStream))
        }
    } }
}
