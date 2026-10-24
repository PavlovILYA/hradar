package dev.radar.desktop

import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.response.respond
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*

class DesktopSecurityTest {
    @Test fun `desktop capability cookie and exact authority origin csrf checks`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureDesktopSecurity("fake-launch-token", "fake-csrf")
            routing {
                get("/api/test") { call.respond(mapOf("ok" to true)) }
                post("/api/onboarding/login") { call.respond(mapOf("ok" to true)) }
                post("/api/integrations/bot/check") { call.respond(mapOf("ok" to true)) }
            }
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/test") { header(HttpHeaders.Host,"127.0.0.1:8080") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/test") { header(HttpHeaders.Host,"attacker.example"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/test") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"https://evil.example"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/test") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/onboarding/login") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/onboarding/login") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token"); header("X-Hradar-CSRF","fake-csrf") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/integrations/bot/check") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/integrations/bot/check") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/integrations/bot/check") { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=fake-launch-token"); header("X-Hradar-CSRF","fake-csrf") }.status)
        val launch = client.post("/desktop/launch") {
            header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); contentType(ContentType.Application.Json); setBody("{\"token\":\"fake-launch-token\"}")
        }
        assertEquals(HttpStatusCode.OK,launch.status)
        assertTrue(launch.headers[HttpHeaders.SetCookie]!!.contains("HttpOnly",ignoreCase=true))
        assertTrue(launch.headers[HttpHeaders.SetCookie]!!.contains("SameSite=Strict"))
    }
}
