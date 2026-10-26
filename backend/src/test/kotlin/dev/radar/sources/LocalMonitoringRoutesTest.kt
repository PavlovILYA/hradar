package dev.radar.sources

import dev.radar.desktop.configureDesktopSecurity
import dev.radar.research.*
import dev.radar.telegram.TdlibGateway
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

class LocalMonitoringRoutesTest {
    @Test fun explicitPrivateSubscriptionCreatesOnlyOwnerLocalMatchesAndHonorsHttpSecurity() = testApplication {
        val repo = Repositories(Files.createTempDirectory("local-monitor-routes").resolve("db"))
        val source = repo.saveSource(TelegramSource(chatId = -1001, type = SourceType.SUPERGROUP, title = "Synthetic private source"))
        val template = repo.saveTemplate(SearchTemplate(name = "Synthetic Java", required = listOf("Java"), telegramQuery = "Java", subscribed = true))
        val json = Json { ignoreUnknownKeys = true }
        fun message(id: Long) = TdApi.Message().apply { this.id = id; chatId = source.chatId; date = (System.currentTimeMillis() / 1000).toInt(); content = TdApi.MessageText().apply { text = TdApi.FormattedText("Synthetic Java publication", emptyArray()) } }
        val native: suspend (TdApi.Function<*>) -> TdApi.Object = { fn -> when(fn) {
            is TdApi.GetChat -> TdApi.Chat().apply { id = source.chatId; title = source.title; type = TdApi.ChatTypeSupergroup(1001, false) }
            is TdApi.GetSupergroup -> TdApi.Supergroup().apply { id = 1001; status = TdApi.ChatMemberStatusMember() }
            is TdApi.GetChatHistory -> TdApi.Messages().apply { messages = if (fn.fromMessageId == 0L) arrayOf(message(5)) else emptyArray() }
            else -> error("Unexpected native operation")
        } }
        val reader = SourceReadService(repo, native)
        val monitor = LocalMonitoringService(repo, reader, { true })
        val archive = SourceArchiveService(repo, { key,cursor -> reader.recent(key,cursor,100) }, { true })
        application {
            install(ContentNegotiation) { json() }
            configureDesktopSecurity("synthetic-launch", "synthetic-csrf")
            configureSourceRoutes(SourceCatalogService(TdlibSourceCatalog(native), repo), repo, reader, archive, monitor)
        }
        fun HttpRequestBuilder.owner() { header(HttpHeaders.Host,"127.0.0.1:8080"); header(HttpHeaders.Origin,"http://127.0.0.1:8080"); header(HttpHeaders.Cookie,"hradar-desktop=synthetic-launch"); header("X-Hradar-CSRF","synthetic-csrf"); contentType(ContentType.Application.Json) }
        try {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/local-notifications") { header(HttpHeaders.Host,"evil.example") }.status)
            val path = "/api/sources/${source.key}/monitoring"
            val initial = client.get(path) { owner() }
            assertEquals(HttpStatusCode.OK, initial.status); assertEquals("[]", initial.bodyAsText())
            assertTrue(repo.localMatches().items.isEmpty())
            val enabled = client.post(path) { owner(); setBody(json.encodeToString(ConfigureLocalMonitoring(template.id,true))) }
            assertEquals(HttpStatusCode.OK, enabled.status)
            assertTrue(repo.localMatches().items.isEmpty()) // activation never emits old head
            monitor.processUpdate(message(6)); monitor.processUpdate(message(6))
            val response = client.get("/api/local-notifications") { owner() }
            assertEquals(HttpStatusCode.OK,response.status)
            val matches = json.decodeFromString<LocalMatchPage>(response.bodyAsText())
            assertEquals(1,matches.items.size); assertEquals(source.key,matches.items.single().message.sourceKey)
            assertEquals(1,matches.unread)
            assertEquals(HttpStatusCode.Forbidden,client.post("/api/local-notifications/${matches.items.single().id}/read") { owner(); headers.remove("X-Hradar-CSRF") }.status)
            assertEquals(HttpStatusCode.OK,client.post("/api/local-notifications/${matches.items.single().id}/read") { owner() }.status)
            assertEquals(0,repo.localMatches().unread)
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.notifications().isEmpty()); assertTrue(repo.mcpSources().isEmpty())
            assertTrue(repo.historyMessages(source.chatId,50,null,"").messages.isEmpty())
            assertEquals(0,repo.botDeliveryCounts(123).pending)
            assertEquals(HttpStatusCode.OK,client.post(path) { owner(); setBody(json.encodeToString(ConfigureLocalMonitoring(template.id,false))) }.status)
            monitor.processUpdate(message(7)); assertEquals(1,repo.localMatches().items.size)
            assertEquals(HttpStatusCode.Forbidden,client.post(path) { owner(); headers.remove(HttpHeaders.Origin); header(HttpHeaders.Origin,"https://evil.example"); setBody(json.encodeToString(ConfigureLocalMonitoring(template.id,true))) }.status)
        } finally { monitor.close(); archive.close(); repo.close() }
    }
}
