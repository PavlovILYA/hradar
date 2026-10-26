package dev.radar.research

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SavedResultsTest {
    private val source = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic source")
    @Test fun savedRestShowsSharedSQLiteDecisionsAndDeniesForeignOrigin() = testApplication {
        Repositories(Files.createTempDirectory("hradar-saved-rest").resolve("research.sqlite")).use { repo ->
            repo.saveChannel(source); repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000)
            val message = ResearchMessage(1, source.id, 1700000000, "Synthetic saved", source.title, source.username)
            repo.decidePublication(123, 7, message, "SAVED")
            application { routing { install(ContentNegotiation) { json() } }; configureSavedRoutes(repo) }
            val response = client.get("/api/saved?status=SAVED") { header("Host", "127.0.0.1:8080") }
            assertEquals(200, response.status.value); assertContains(response.bodyAsText(), "Synthetic saved")
            assertFalse(response.bodyAsText().contains("userId"))
            repo.decidePublication(123, 7, message, "REJECTED")
            val rejected = client.get("/api/saved?status=REJECTED") { header("Host", "127.0.0.1:8080") }
            assertContains(rejected.bodyAsText(), "REJECTED")
            val denied = client.get("/api/saved") { header("Host", "evil.example"); header("Origin", "https://evil.example") }
            assertEquals(403, denied.status.value)
        }
    }
    @Test fun migrationFromSixPreservesMatchesRecipientsAndArchiveWithActualDates() {
        val path = Files.createTempDirectory("hradar-v6-migration").resolve("research.sqlite")
        Repositories(path).use { repo ->
            repo.saveChannel(source); val rule = repo.saveTemplate(SearchTemplate(name = "Synthetic", required = listOf("java"), telegramQuery = "java"))
            repo.recordMatch(rule, ResearchMessage(1, source.id, 1700000000, "Synthetic java", source.title, source.username))
            repo.configureBot(123, setOf(7)); repo.registerBotRecipient(123, 7, 7, 1700000000000)
            repo.handleBotUpdate(123, 7) {}
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            listOf("local_monitoring_limits", "local_matches", "local_subscriptions", "local_project_publications", "local_source_membership", "local_source_sync", "local_publications").forEach { s.execute("DROP TABLE $it") }; s.execute("DROP TABLE telegram_sources"); s.execute("DROP TABLE bot_access"); s.execute("DROP TABLE bot_invitations"); s.execute("DROP TABLE bot_workspace"); s.execute("DROP TABLE publication_decisions"); s.execute("DROP INDEX matches_date"); s.execute("ALTER TABLE matched_messages DROP COLUMN message_date"); s.execute("PRAGMA user_version=6")
        } }
        Repositories(path).use { repo ->
            assertEquals(1, repo.notifications().size); assertTrue(repo.botRegistered(123, 7)); assertEquals(8L, repo.botOffset(123))
            assertEquals(1, repo.matchDigest(1699999999, 1700000001).total)
            assertEquals(0, repo.matchDigest(1700000001, 1700000100).total)
            assertTrue(repo.savedResults().items.isEmpty()); assertEquals(source, repo.channels().single())
        }
    }
}
