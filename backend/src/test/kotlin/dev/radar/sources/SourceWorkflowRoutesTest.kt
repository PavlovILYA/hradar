package dev.radar.sources

import dev.radar.capabilities.CapabilityService
import dev.radar.capabilities.TelegramReadPort
import dev.radar.desktop.configureDesktopSecurity
import dev.radar.history.SyncStatus
import dev.radar.research.*
import dev.radar.telegram.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

/** Actual Ktor/SQLite/read/search/import/CRM workflow; only Telegram RPCs are fake. */
class SourceWorkflowRoutesTest {
    @Test fun selectedPrivateSourceCanBeReadSearchedArchivedAndPersistedOnKanbanWithoutExternalExposure() = testApplication {
        val path = Files.createTempDirectory("source-workflow").resolve("db")
        val repo = Repositories(path)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val text = "Synthetic Java engineer looking for work with Spring experience"
        fun message() = TdApi.Message().apply {
            id = 100; chatId = 1; date = 1710000000
            content = TdApi.MessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
        }
        val native: suspend (TdApi.Function<*>) -> TdApi.Object = { fn -> when (fn) {
            is TdApi.GetChat -> TdApi.Chat().apply { id = 1; title = "Synthetic private conversation"; type = TdApi.ChatTypePrivate(1) }
            is TdApi.GetUser -> TdApi.User().apply { id = 1; type = TdApi.UserTypeRegular() }
            is TdApi.GetChatHistory -> TdApi.Messages().apply { messages = if (fn.fromMessageId == 0L) arrayOf(message()) else emptyArray() }
            is TdApi.SearchChatMessages -> TdApi.FoundChatMessages().apply { messages = arrayOf(message()); nextFromMessageId = 0 }
            is TdApi.GetMessage -> message()
            is TdApi.GetMessageLink -> throw TelegramRequestException(400, "MESSAGE_LINK_UNAVAILABLE")
            else -> error("Unexpected external operation ${fn.javaClass.simpleName}")
        } }
        val gateway = TdlibGateway(databaseDirectory = path.parent.resolve("unused-session"))
        val matching = MatchingService()
        val reader = SourceReadService(repo, native)
        val search = SearchService(gateway, repo, matching, native)
        val archive = SourceArchiveService(repo, { key, cursor -> reader.recent(key, cursor, 100) }, { true }, 0, 0, 0)
        val publicRecruiter = RecruiterService(repo, RecruiterSearchPort(search::search))
        val projects = RecruitmentProjectService(repo, matching, SubscriptionService(repo, matching), publicRecruiter,
            localSearch = LocalProjectSearchService(repo, RecruiterSearchPort(search::search)))
        application {
            install(ContentNegotiation) { json() }
            configureDesktopSecurity("synthetic-launch", "synthetic-csrf")
            configureSourceRoutes(SourceCatalogService(TdlibSourceCatalog(native), repo), repo, reader, archive)
            configureResearchRoutes(gateway, repo, matching, search, NotificationService(repo, matching))
            configureRecruitmentRoutes(projects)
        }
        fun HttpRequestBuilder.local() {
            header(HttpHeaders.Host, "127.0.0.1:8080")
            header(HttpHeaders.Origin, "http://127.0.0.1:8080")
            header(HttpHeaders.Cookie, "hradar-desktop=synthetic-launch")
            header("X-Hradar-CSRF", "synthetic-csrf")
            contentType(ContentType.Application.Json)
        }
        try {
            val saved = client.post("/api/sources") { local(); setBody("{\"chatId\":1}") }
            assertEquals(HttpStatusCode.Created, saved.status)
            val source = json.decodeFromString<TelegramSource>(saved.bodyAsText())
            assertNull(source.username)
            val recentResponse = client.get("/api/sources/${source.key}/messages") { local() }
            assertEquals(HttpStatusCode.OK, recentResponse.status)
            val recent = json.decodeFromString<SourceMessagePage>(recentResponse.bodyAsText())
            assertEquals(text, recent.messages.single().text)
            assertEquals(source.key, recent.messages.single().sourceKey)
            val found = client.post("/api/search") {
                local(); setBody(json.encodeToString(SearchRequest(mode = "sources", query = "Java", sourceKeys = listOf(source.key), withLinks = false)))
            }
            assertEquals(HttpStatusCode.OK, found.status)
            assertEquals(100L, json.decodeFromString<SearchPage>(found.bodyAsText()).messages.single().id)
            assertEquals(HttpStatusCode.Accepted, client.post("/api/sources/${source.key}/sync") { local(); setBody("{\"maxMessages\":1000}") }.status)
            withTimeout(5000) {
                while (true) {
                    val response = client.get("/api/sources/${source.key}/sync") { local() }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val state = json.decodeFromString<SourceSyncRecord>(response.bodyAsText())
                    if (state.status == SyncStatus.COMPLETED) { assertEquals(1, state.messageCount); break }
                    assertEquals(SyncStatus.RUNNING, state.status)
                    delay(10)
                }
            }
            val stored = client.get("/api/sources/${source.key}/archive?query=Java") { local() }
            assertEquals(HttpStatusCode.OK, stored.status)
            assertTrue(stored.bodyAsText().contains(text))
            val created = client.post("/api/projects") {
                local(); setBody(json.encodeToString(ProjectInput("Synthetic local project", required = listOf("Java"), sourceKeys = listOf(source.key))))
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val project = json.decodeFromString<RecruitmentProject>(created.bodyAsText())
            val discovered = client.post("/api/projects/${project.id}/search") {
                local(); setBody(json.encodeToString(ProjectSearch("archive", "Java")))
            }
            assertEquals(HttpStatusCode.OK, discovered.status)
            assertEquals(listOf(100L), json.decodeFromString<ProjectSearchPage>(discovered.bodyAsText()).messages.map { it.id })
            val added = client.post("/api/projects/${project.id}/cards") { local(); setBody(json.encodeToString(AddPublication(1, 100))) }
            assertEquals(HttpStatusCode.Created, added.status)
            val card = json.decodeFromString<RecruitmentCard>(added.bodyAsText())
            val updated = client.put("/api/projects/${project.id}/cards/${card.id}") {
                local(); setBody(json.encodeToString(CardUpdate(RecruitmentStage.INTERESTING, "Synthetic review note", 4)))
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val board = client.get("/api/projects/${project.id}/cards") { local() }
            assertEquals("Synthetic review note", json.decodeFromString<CardPage>(board.bodyAsText()).items.single().notes)

            // The owner-local body exists, but legacy public archives/recruiter and MCP remain isolated.
            assertTrue(repo.sourceArchive(source.key, 10, null, "").messages.isNotEmpty())
            assertTrue(repo.historyMessages(1, 10, null, "").messages.isEmpty())
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty()); assertTrue(repo.notifications().isEmpty())
            assertFailsWith<IllegalArgumentException> { publicRecruiter.find("Java", "archive", listOf(1)) }
            repo.setMcpEnabled(true)
            val external = object : TelegramReadPort {
                override fun status() = TelegramStatus("Ready", true)
                override suspend fun resolve(username: String): MonitoredChannel = error("No external RPC allowed")
                override suspend fun history(channelId: Long, cursor: Long): List<TelegramMessage> = error("No external RPC allowed")
                override suspend fun search(username: String, query: String, limit: Int, cursor: String): SearchPage = error("No external RPC allowed")
                override suspend fun message(channel: MonitoredChannel, id: Long): ResearchMessage = error("No external RPC allowed")
                override suspend fun neighbors(channel: MonitoredChannel, id: Long, count: Int): List<ResearchMessage> = error("No external RPC allowed")
            }
            val denied = CapabilityService(external, repo).call("search_local_archive", buildJsonObject { put("chat", "1"); put("query", "Java") })
            assertEquals("ACCESS_DENIED", denied.error?.code)
            archive.close(); repo.close()
            Repositories(path).use { reopened ->
                assertEquals(source.key, reopened.sources().single().key)
                assertEquals(1, reopened.sourceArchive(source.key, 10, null, "").messages.size)
                val restored = reopened.recruitmentCard(project.id, card.id)!!
                assertEquals(RecruitmentStage.INTERESTING, restored.stage); assertEquals(4, restored.interest)
                assertEquals("Synthetic review note", restored.notes); assertEquals(text, restored.message.text)
            }
        } finally { archive.close(); repo.close() }
    }
}
