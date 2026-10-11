package dev.radar.research

import dev.radar.ApiError
import dev.radar.respondApiError
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class RecruitmentRoutesTest {
    @Test fun typedRestLifecycleErrorsAndLocalAccess() = testApplication {
        Repositories(Files.createTempDirectory("recruitment-api").resolve("research.sqlite")).use { repo ->
            repo.saveChannel(MonitoredChannel(-1001, "synthetic_jobs", "Synthetic"))
            val matching = MatchingService()
            val service = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), RecruiterService(repo, RecruiterSearchPort { SearchPage() }))
            application {
                routing { install(ContentNegotiation) { json(Json { encodeDefaults = true }) } }
                install(StatusPages) {
                    exception<ResourceNotFound> { call, _ -> call.respondApiError(HttpStatusCode.NotFound, ApiError("Ресурс не найден")) }
                    exception<IllegalArgumentException> { call, _ -> call.respondApiError(HttpStatusCode.BadRequest, ApiError("Некорректный запрос")) }
                    exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respondApiError(HttpStatusCode.BadRequest, ApiError("Некорректный JSON")) }
                }
                configureRecruitmentRoutes(service)
            }
            val body = """{"name":"Synthetic role","required":["Java"],"sources":[-1001]}"""
            val created = client.post("/api/projects") { header("Host", "127.0.0.1:8080"); header("Origin", "http://127.0.0.1:5173"); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(201, created.status.value)
            val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(200, client.get("/api/projects/$id") { header("Host", "127.0.0.1:8080") }.status.value)
            assertEquals(404, client.get("/api/projects/missing/cards") { header("Host", "127.0.0.1:8080") }.status.value)
            assertEquals(400, client.get("/api/projects/$id/cards?limit=10000") { header("Host", "127.0.0.1:8080") }.status.value)
            assertEquals(400, client.get("/api/projects/$id/cards?stage=WRONG") { header("Host", "127.0.0.1:8080") }.status.value)
            assertEquals(403, client.post("/api/projects") { header("Host", "127.0.0.1:8080"); contentType(ContentType.Application.Json); setBody(body) }.status.value)
            assertEquals(403, client.get("/api/projects") { header("Host", "evil.example"); header("Origin", "https://evil.example") }.status.value)
            assertEquals(1, service.list().total, "Denied mutations must not create projects")
            assertEquals(400, client.post("/api/projects") { header("Host", "127.0.0.1:8080"); header("Origin", "http://127.0.0.1:5173"); contentType(ContentType.Application.Json); setBody("{") }.status.value)
            assertEquals(404, client.post("/api/projects/$id/cards") { header("Host", "127.0.0.1:8080"); header("Origin", "http://127.0.0.1:5173"); contentType(ContentType.Application.Json); setBody("""{"channelId":-1001,"messageId":999}""") }.status.value)
            suspend fun search(body: String) = client.post("/api/projects/$id/search") {
                header("Host", "127.0.0.1:8080"); header("Origin", "http://127.0.0.1:5173")
                contentType(ContentType.Application.Json); setBody(body)
            }
            val emptyArchive = search("""{"query":"java","mode":"archive"}""")
            assertEquals(200, emptyArchive.status.value)
            val emptyResult = Json.parseToJsonElement(emptyArchive.bodyAsText()).jsonObject
            assertEquals("archive_empty", emptyResult["state"]!!.jsonPrimitive.content)
            assertTrue(emptyResult["exhausted"]!!.jsonPrimitive.boolean)
            assertEquals(0, emptyResult["scanned"]!!.jsonPrimitive.int)
            assertEquals(400, search("""{"query":"java","cursor":"invalid"}""").status.value)
            assertEquals(400, search("""{"query":"","mode":"archive"}""").status.value)
            assertEquals(200, client.delete("/api/projects/$id") { header("Host", "127.0.0.1:8080"); header("Origin", "http://127.0.0.1:5173") }.status.value)
            assertEquals(404, client.get("/api/projects/$id") { header("Host", "127.0.0.1:8080") }.status.value)
        }
    }
}
