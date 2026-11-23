package dev.radar.profiles

import dev.radar.desktop.*
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ProfileAccessTest {
    @Test fun decodedPathsGenerationsIdentityAndCookieCsrfGateAllAdapters() = testApplication {
        val home=Files.createTempDirectory("profile-access-").toRealPath()
        val store=ProfileStore(home);store.prepare()
        val flow=ProfileSession(store,store.active().id,{true},{ProfileIdentity(1,"Synthetic")},{"saved"},{},{})
        application {
            configureDesktopSecurity(DesktopAccess("synthetic-capability"),"synthetic-csrf")
            configureProfileAccess(flow)
            routing {
                get("/api/documents") {call.respondText("Synthetic private document")}
                post("/api/profiles/add") {call.respondText("Accepted")}
                post("/mcp") {call.respondText("Synthetic public adapter")}
            }
        }
        fun HttpRequestBuilder.owner(generation:String=flow.generation) {
            header(HttpHeaders.Host,"127.0.0.1:8080");header(HttpHeaders.Cookie,"hradar-desktop=synthetic-capability")
            header(HttpHeaders.Origin,"http://127.0.0.1:8080");header("X-Hradar-Workspace",generation)
        }
        try {
            assertEquals(HttpStatusCode.Forbidden,client.get("/api/documents") {header(HttpHeaders.Host,"127.0.0.1:8080")}.status)
            assertEquals(HttpStatusCode.Conflict,client.get("/api/documents") {owner("previous-profile")}.status)
            assertEquals(HttpStatusCode.Locked,client.get("/api/documents") {owner()}.status)
            assertEquals(HttpStatusCode.Locked,client.post("/mcp") {owner()}.status)
            assertEquals(HttpStatusCode.Forbidden,client.post("/api/profiles/add") {owner()}.status)
            runBlocking {flow.verify()}
            assertEquals(HttpStatusCode.OK,client.get("/api/documents") {owner()}.status)
            for(path in listOf("/%61pi/documents","/api//documents")) {
                assertEquals(HttpStatusCode.Conflict,client.get(path) {owner("previous-profile")}.status)
            }
            assertEquals(HttpStatusCode.OK,client.post("/api/profiles/add") {owner();header("X-Hradar-CSRF","synthetic-csrf")}.status)
            flow.prepareChange(flow.profileId)
            assertEquals(HttpStatusCode.Locked,client.get("/api/documents") {owner()}.status)
            assertEquals(HttpStatusCode.Locked,client.post("/mcp") {owner()}.status)
        } finally {flow.close();home.toFile().deleteRecursively()}
    }
}
