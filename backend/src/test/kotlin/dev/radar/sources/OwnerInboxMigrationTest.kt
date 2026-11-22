package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class OwnerInboxMigrationTest {
    @Test fun schema12PreservesBothMatchTablesAndDeduplicatesWithoutHistoricalDelivery() {
        val path = Files.createTempDirectory("owner-migration").resolve("db")
        Repositories(path).use { repo ->
            repo.saveChannel(MonitoredChannel(1, "synthetic", "QA"))
            val source = repo.sources().single()
            val rule = repo.saveTemplate(SearchTemplate(name = "Java", required = listOf("java"), telegramQuery = "java"))
            val message = ResearchMessage(11, 1, Int.MAX_VALUE, "synthetic java", "QA", "synthetic")
            repo.recordMatch(rule, message)
            val state = repo.configureLocalSubscription(source, rule, null, true, 10, 1)
            repo.commitLocalMonitoring(state, source, rule, state, listOf(message.copy(sourceKey = source.key)), MatchingService())
            repo.saveState(MonitoringState(1, 10, 9, 11))
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            listOf("document_fts", "documents", "owner_inbox", "owner_bookmarks", "desktop_preferences", "search_cache").forEach { s.execute("DROP TABLE $it") }
            s.execute("PRAGMA user_version=12")
        } }
        Repositories(path).use { repo ->
            assertEquals(1, repo.templates().size); assertEquals(1, repo.localSubscriptions().size)
            assertEquals(1, repo.notifications().size); assertEquals(1, repo.localMatches().items.size)
            assertEquals(1, repo.inboxUnread()); assertEquals(1, repo.inboxPage(25, Long.MAX_VALUE).items.size)
            assertEquals(9L, repo.state(1)!!.recoveryCursor)
            repo.saveDesktopPreferences(DesktopPreferences(enabled = true))
            assertTrue(repo.claimDesktopDeliveries().isEmpty())
            val id = repo.inboxPage(25, Long.MAX_VALUE).items.single().id
            repo.readInbox(id)
            assertTrue(repo.notifications().single().read); assertTrue(repo.localMatches().items.single().read)
        }
        Repositories(path).use { repo -> assertEquals(0, repo.inboxUnread()); assertTrue(repo.claimDesktopDeliveries().isEmpty()) }
    }
    @Test fun failedSchema13MigrationRollsBackAndCanBeRetried() {
        val path = Files.createTempDirectory("owner-migration-rollback").resolve("db")
        Repositories(path).close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            listOf("document_fts", "documents", "owner_inbox", "owner_bookmarks", "desktop_preferences", "search_cache").forEach { s.execute("DROP TABLE $it") }
            s.execute("PRAGMA user_version=12"); s.execute("CREATE VIEW search_cache AS SELECT 1")
        } }
        assertFails { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(12, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='owner_inbox'").use { it.next(); it.getInt(1) })
            s.execute("DROP VIEW search_cache")
        } }
        Repositories(path).close()
    }
}
