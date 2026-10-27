package dev.radar.desktop

import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.response.respond
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.test.*

class DesktopRecoveryTest {
    @Test fun `recovery needs owner cookie exact same origin and custom header`() = testApplication {
        val access = DesktopAccess("new-process")
        application {
            install(ContentNegotiation) { json() }
            configureDesktopSecurity(access, "new-csrf")
            routing { get("/api/test") { call.respond(mapOf("ok" to true)) } }
        }
        suspend fun recover(host: String = "hradar.localhost:8080", origin: String? = "http://hradar.localhost:8080", cookie: String = "new-process", proof: Boolean = true) = client.post("/desktop/recover") {
            header(HttpHeaders.Host, host)
            if (origin != null) header(HttpHeaders.Origin, origin)
            header(HttpHeaders.Cookie, "hradar-desktop=$cookie")
            if (proof) header("X-Hradar-Recovery", "1")
        }
        for (host in listOf("evil.localhost:8080", "hradar.localhost:1809", "hradar.localhost:8080.evil", "[::1]:8080", "192.168.1.1:8080"))
            assertEquals(HttpStatusCode.Forbidden, recover(host = host).status)
        for (origin in listOf(null, "null", "https://evil.example", "http://evil.localhost:8080", "http://127.0.0.1:8080"))
            assertEquals(HttpStatusCode.Forbidden, recover(origin = origin).status)
        assertEquals(HttpStatusCode.Forbidden, recover(cookie = "").status)
        assertEquals(HttpStatusCode.Forbidden, recover(cookie = "old-process").status)
        assertEquals(HttpStatusCode.Forbidden, recover(proof = false).status)
        val recovered = recover()
        assertEquals(HttpStatusCode.OK, recovered.status)
        assertNull(recovered.headers[HttpHeaders.SetCookie])
        assertEquals("no-store", recovered.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/test") {
            header(HttpHeaders.Host, "hradar.localhost:8080"); header(HttpHeaders.Cookie, "hradar-desktop=old-process")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/test") {
            header(HttpHeaders.Host, "hradar.localhost:8080"); header(HttpHeaders.Cookie, "hradar-desktop=new-process")
        }.status)
        val launch = access.issueLaunch()
        repeat(2) { attempt ->
            val result = client.post("/desktop/launch") {
                header(HttpHeaders.Host, "hradar.localhost:8080"); header(HttpHeaders.Origin, "http://hradar.localhost:8080")
                contentType(ContentType.Application.Json); setBody("{\"token\":\"$launch\"}")
            }
            assertEquals(if (attempt == 0) HttpStatusCode.OK else HttpStatusCode.Forbidden, result.status)
            if (attempt == 0) assertTrue(result.headers.getAll(HttpHeaders.SetCookie)!!.any { it.contains("hradar-desktop=new-process") && it.contains("HttpOnly") && it.contains("SameSite=Strict") && !it.contains("Domain=") })
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/desktop/reopen") {
            header(HttpHeaders.Host, "127.0.0.1:8080"); contentType(ContentType.Application.Json); setBody("{\"nonce\":\"${"x".repeat(43)}\",\"proof\":\"foreign\"}")
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/desktop/reopen") {
            header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Origin, "http://hradar.localhost:8080")
            contentType(ContentType.Application.Json); setBody("{\"token\":\"new-process\"}")
        }.status)
        val nonce = "x".repeat(43)
        val proof = instanceProof("new-process", "request:$nonce")
        val reopen = client.post("/desktop/reopen") {
            header(HttpHeaders.Host, "127.0.0.1:8080"); contentType(ContentType.Application.Json); setBody("{\"nonce\":\"$nonce\",\"proof\":\"$proof\"}")
        }
        assertEquals(HttpStatusCode.OK, reopen.status)
        assertFalse(reopen.bodyAsText().contains("new-process"))
    }
}
