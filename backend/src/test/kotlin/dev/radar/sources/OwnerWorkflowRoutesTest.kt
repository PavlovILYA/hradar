package dev.radar.sources

import dev.radar.desktop.*
import dev.radar.research.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

class OwnerWorkflowRoutesTest {
    @Test fun catalogSearchTemplateLiveInboxAndDeliveryUseOneOwnerWorkflow() = testApplication {
        val repo = Repositories(Files.createTempDirectory("owner-routes").resolve("db"))
        val json = Json { ignoreUnknownKeys = true }
        var historyCalls = 0
        fun message(id: Long, text: String = "Synthetic java ktor") = TdApi.Message().apply {
            this.id = id; chatId = 123; date = Int.MAX_VALUE
            content = TdApi.MessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
        }
        val native: suspend (TdApi.Function<*>) -> TdApi.Object = { fn -> when (fn) {
            is TdApi.LoadChats -> TdApi.Ok()
            is TdApi.GetChats -> TdApi.Chats(1, longArrayOf(123))
            is TdApi.GetChat -> TdApi.Chat().apply { id = 123; title = "Synthetic QA"; type = TdApi.ChatTypeBasicGroup(1) }
            is TdApi.GetBasicGroup -> TdApi.BasicGroup().apply { id = 1; isActive = true; status = TdApi.ChatMemberStatusMember() }
            is TdApi.GetChatHistory -> { historyCalls++; TdApi.Messages().apply { messages = arrayOf(message(10)) } }
            is TdApi.SearchChatMessages -> TdApi.FoundChatMessages().apply { messages = arrayOf(message(9)); nextFromMessageId = 0 }
            else -> error("Unexpected native read")
        } }
        val reader = SourceReadService(repo, execute = native)
        val monitoring = LocalMonitoringService(repo, reader, { true }, requestIntervalMs = 0)
        application {
            install(ContentNegotiation) { json() }
            configureDesktopSecurity(null, "synthetic")
            configureSourceRoutes(SourceCatalogService(TdlibSourceCatalog(native), repo), repo)
            configureOwnerWorkflow(repo, reader, monitoring)
        }
        fun HttpRequestBuilder.owner() { header(HttpHeaders.Host, "127.0.0.1:8080"); header(HttpHeaders.Origin, "http://127.0.0.1:5173"); header("X-Hradar-CSRF", "synthetic"); contentType(ContentType.Application.Json) }
        try {
            val catalog = client.get("/api/source-catalog") { owner() }
            assertEquals(HttpStatusCode.OK, catalog.status)
            assertEquals("Synthetic QA", json.decodeFromString<SourcePage>(catalog.bodyAsText()).items.single().title)
            assertTrue(repo.sources().isEmpty()); assertEquals(0, historyCalls)
            val picked = client.post("/api/sources") { owner(); setBody(Json.encodeToString(SaveSourceInput(123))) }
            val source = json.decodeFromString<TelegramSource>(picked.bodyAsText())
            val search = client.post("/api/workspace/search") { owner(); setBody(Json.encodeToString(OwnerSearchInput(SearchRequest(mode = "sources", query = "java", sourceKeys = listOf(source.key), withLinks = false)))) }
            assertEquals(HttpStatusCode.OK, search.status)
            assertEquals(9L, json.decodeFromString<OwnerSearchResult>(search.bodyAsText()).messages.single().id)
            val rule = SearchTemplate(name = "Java", required = listOf("java"), any = listOf("ktor", "kotlin"), excluded = listOf("spam"), telegramQuery = "java", sourceMode = "chats", sourceKeys = listOf(source.key), subscribed = true)
            val denied = client.post("/api/workspace/templates") { owner(); headers.remove("X-Hradar-CSRF"); setBody(Json.encodeToString(rule)) }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertTrue(repo.templates().isEmpty())
            repo.saveDesktopPreferences(DesktopPreferences(enabled = true))
            val created = client.post("/api/workspace/templates") { owner(); setBody(Json.encodeToString(rule)) }
            assertEquals(HttpStatusCode.OK, created.status)
            val saved = json.decodeFromString<SearchTemplate>(created.bodyAsText())
            assertEquals(10L, repo.localSubscriptions().single().checkpoint)
            monitoring.processUpdate(message(11)); monitoring.processUpdate(message(11))
            monitoring.processUpdate(message(12, "Synthetic kotlin")); monitoring.processUpdate(message(13, "Synthetic java ktor spam"))
            val notifications = NotificationService(repo, MatchingService())
            assertEquals(1, notifications.summary().unread)
            assertEquals(11L, notifications.page(25, Long.MAX_VALUE).items.single().message.id)
            assertEquals(1, repo.claimDesktopDeliveries().size); assertTrue(repo.claimDesktopDeliveries().isEmpty())
            val paused = client.put("/api/workspace/templates/${saved.id}") { owner(); setBody(Json.encodeToString(saved.copy(active = false))) }
            assertEquals(HttpStatusCode.OK, paused.status)
            monitoring.processUpdate(message(14)); assertEquals(1, notifications.summary().unread)
            val resumed = client.put("/api/workspace/templates/${saved.id}") { owner(); setBody(Json.encodeToString(saved.copy(active = true))) }
            assertEquals(HttpStatusCode.OK, resumed.status)
            monitoring.processUpdate(message(11)); assertEquals(1, notifications.summary().unread)
            assertTrue(repo.notifications().isEmpty()); assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
            assertEquals(0, repo.botDeliveryCounts(123).pending)
            assertEquals(0, repo.historyMessages(123, 25, null, "").total)
            for (path in listOf("/api/workspace/deliveries/claim", "/api/workspace/search", "/api/%77orkspace/templates")) {
                assertEquals(HttpStatusCode.Forbidden, client.post { url.encodedPath = path; owner(); headers.remove("X-Hradar-CSRF"); setBody("{}") }.status)
            }
        } finally { monitoring.close(); repo.close() }
    }
}
