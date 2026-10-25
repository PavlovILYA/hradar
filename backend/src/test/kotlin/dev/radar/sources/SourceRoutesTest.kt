package dev.radar.sources

import dev.radar.desktop.configureDesktopSecurity
import dev.radar.research.Repositories
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respondText
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.nio.file.Files
import kotlin.test.*

class SourceRoutesTest {
    @Test fun sourceApiPreservesCookieOriginCsrfAndMetadataOnlyGuards() = testApplication {
        val repo = Repositories(Files.createTempDirectory("source-routes-").resolve("db"))
        var requests = 0
        val port = object : SourceCatalogPort {
            override suspend fun chatIds(query: String) = listOf(1L)
            override suspend fun describe(chatId: Long): TelegramSource { requests++; return TelegramSource(chatId = chatId, type = SourceType.PRIVATE, title = "Synthetic user", access = SourceAccess.AVAILABLE) }
            override suspend fun topics(chatId: Long, cursor: TopicCursor) = TopicPage(emptyList())
            override suspend fun topic(chatId: Long, topicId: Int) = error("No topics in fixture")
        }
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { exception<BadRequestException> { call, _ -> call.respondText("Invalid source", status = HttpStatusCode.BadRequest) } }
            configureDesktopSecurity("synthetic-launch", "synthetic-csrf")
            configureSourceRoutes(SourceCatalogService(port, repo), repo)
        }
        for (path in listOf("/api/sources", "/api/source-catalog")) {
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header(HttpHeaders.Host, "127.0.0.1:8080") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header(HttpHeaders.Host, "evil.invalid"); header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch"); header(HttpHeaders.Origin, "https://evil.invalid") }.status)
        }
        for ((origin, csrf) in listOf(null to null, "http://127.0.0.1:8080" to null, "http://127.0.0.1:8080" to "wrong", "https://evil.invalid" to "synthetic-csrf")) {
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/sources") {
                header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch")
                origin?.let { header(HttpHeaders.Origin, it) }; csrf?.let { header("X-Hradar-CSRF", it) }
                contentType(ContentType.Application.Json); setBody("{\"chatId\":1}")
            }.status)
        }
        assertEquals(0, requests); assertTrue(repo.sources().isEmpty())
        val created = client.post("/api/sources") {
            header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch")
            header(HttpHeaders.Origin, "http://127.0.0.1:8080"); header("X-Hradar-CSRF", "synthetic-csrf")
            contentType(ContentType.Application.Json); setBody("{\"chatId\":1}")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertFalse(created.bodyAsText().contains("csrf")); assertEquals(1, repo.sources().size)
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/sources") {
            header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch")
            header(HttpHeaders.Origin, "http://127.0.0.1:8080"); header("X-Hradar-CSRF", "synthetic-csrf")
            contentType(ContentType.Application.Json); setBody("{\"chatId\":2,\"monitoringEnabled\":true,\"legacyChannel\":true}")
        }.status)
        assertEquals(1, requests); assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
        repo.close()
    }
}
