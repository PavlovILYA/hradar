package dev.radar.history

import dev.radar.research.MonitoredChannel
import dev.radar.research.Repositories
import dev.radar.telegram.TelegramMessage
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class HistorySyncTest {
    private val channel = MonitoredChannel(-1001L, "synthetic_channel", "Synthetic")
    private fun message(id: Long, text: String? = "Synthetic Java", channelId: Long = channel.id) = TelegramMessage(id, 1700000000, text, channelId)
    private fun database() = Files.createTempDirectory("hradar-history-test").resolve("db.sqlite")

    @Test fun followsShortAndInclusivePagesUntilEmptyAndCompletedRestartIsNoOp() = runBlocking {
        Repositories(database()).use { repo ->
            val state = repo.beginHistorySync(channel)
            assertEquals(SyncStatus.RUNNING, state.status)
            val cursors = mutableListOf<Long>()
            val service = HistorySyncService(repo, { _, cursor ->
                cursors.add(cursor)
                when (cursor) { 0L -> listOf(message(30)); 30L -> listOf(message(30), message(20)); 20L -> listOf(message(20), message(10)); else -> emptyList() }
            }, { true }, 0, 0, 0)
            service.runImport(channel.id)
            assertEquals(listOf(0L, 30L, 20L, 10L), cursors)
            assertEquals(SyncStatus.COMPLETED, repo.historySync(channel.id)!!.status)
            assertEquals(3, repo.historySync(channel.id)!!.messageCount)
            assertEquals(10L, repo.historySync(channel.id)!!.cursor)
            service.start(channel)
            delay(20)
            assertEquals(4, cursors.size)
            assertEquals(3, repo.historyMessages(channel.id, 100, null, "").messages.size)
            service.close()
        }
    }
    @Test fun resumesDurableCursorAfterReopenWithoutFetchingTheBeginningAgain() = runBlocking {
        val path = database()
        Repositories(path).use { repo ->
            repo.beginHistorySync(channel)
            val service = HistorySyncService(repo, { _, cursor -> if (cursor == 0L) listOf(message(30), message(20)) else throw CancellationException("simulated shutdown") }, { true }, 0, 0, 0)
            assertFailsWith<CancellationException> { service.runImport(channel.id) }
            assertEquals(20L, repo.historySync(channel.id)!!.cursor)
            assertEquals(2, repo.historySync(channel.id)!!.messageCount)
            assertEquals(SyncStatus.RUNNING, repo.historySync(channel.id)!!.status)
            service.close()
        }
        Repositories(path).use { repo ->
            val cursors = mutableListOf<Long>()
            val service = HistorySyncService(repo, { _, cursor -> cursors.add(cursor); if (cursor == 20L) listOf(message(20), message(10)) else emptyList() }, { true }, 0, 0, 0)
            service.resumePending()
            withTimeout(5000) { while (repo.historySync(channel.id)!!.status != SyncStatus.COMPLETED) delay(10) }
            assertEquals(listOf(20L, 10L), cursors)
            assertEquals(3, repo.historySync(channel.id)!!.messageCount)
            service.close()
        }
    }
    @Test fun stallsFailWithBoundedRetriesAndManualResumeKeepsCursor() = runBlocking {
        Repositories(database()).use { repo ->
            repo.beginHistorySync(channel)
            repo.saveHistoryPage(repo.historySync(channel.id)!!.copy(cursor = 10), listOf(message(10)))
            var requests = 0
            val stalled = HistorySyncService(repo, { _, _ -> requests++; listOf(message(10)) }, { true }, 0, 0, 0)
            stalled.runImport(channel.id)
            assertEquals(3, requests)
            assertEquals(SyncStatus.FAILED, repo.historySync(channel.id)!!.status)
            assertEquals(10L, repo.historySync(channel.id)!!.cursor)
            assertEquals(1, repo.historySync(channel.id)!!.messageCount)
            stalled.close()
            val retry = repo.beginHistorySync(channel)
            assertEquals(10L, retry.cursor)
            val service = HistorySyncService(repo, { _, cursor -> if (cursor == 10L) listOf(message(5)) else emptyList() }, { true }, 0, 0, 0)
            service.runImport(channel.id)
            assertEquals(SyncStatus.COMPLETED, repo.historySync(channel.id)!!.status)
            assertEquals(2, repo.historySync(channel.id)!!.messageCount)
            service.close()
        }
    }
    @Test fun retriesNetworkFailuresAndNeverAdvancesCursorOnError() = runBlocking {
        Repositories(database()).use { repo ->
            repo.beginHistorySync(channel)
            var requests = 0
            val service = HistorySyncService(repo, { _, cursor ->
                if (++requests < 3) throw IOException("synthetic outage")
                if (cursor == 0L) listOf(message(10)) else emptyList()
            }, { true }, 0, 0, 0)
            service.runImport(channel.id)
            assertEquals(4, requests)
            assertEquals(1, repo.historySync(channel.id)!!.messageCount)
            assertEquals(SyncStatus.COMPLETED, repo.historySync(channel.id)!!.status)
            service.close()
        }
    }
    @Test fun atomicallyRollsBackInvalidPageAndDeduplicatesPerChannel() {
        Repositories(database()).use { repo ->
            val state = repo.beginHistorySync(channel)
            assertFailsWith<IllegalArgumentException> { repo.saveHistoryPage(state.copy(cursor = 10), listOf(message(10), message(5, channelId = -2L))) }
            assertEquals(0, repo.historySync(channel.id)!!.messageCount)
            assertEquals(0L, repo.historySync(channel.id)!!.cursor)
            assertEquals(0, repo.historyMessages(channel.id, 100, null, "").total)
            repo.saveHistoryPage(state.copy(cursor = 10), listOf(message(10), message(10)))
            repo.saveHistoryPage(state.copy(cursor = 10), listOf(message(10, "Updated")))
            val second = channel.copy(id = -2001L, username = "another_channel")
            repo.saveHistoryPage(repo.beginHistorySync(second).copy(cursor = 10), listOf(message(10, channelId = second.id)))
            assertEquals(1, repo.historySync(channel.id)!!.messageCount)
            assertEquals(1, repo.historySync(second.id)!!.messageCount)
            assertEquals("Updated", repo.historyMessages(channel.id, 100, null, "").messages.single().text)
        }
    }
    @Test fun searchesAllLocalHistoryAndPaginatesWithoutWildcardInterpretation() {
        Repositories(database()).use { repo ->
            val state = repo.beginHistorySync(channel)
            repo.saveHistoryPage(state.copy(cursor = 1), listOf(message(30, "Стажёр JAVA"), message(20, "стажер Spring"), message(10, "Java 100%"), message(1, null)))
            val first = repo.historyMessages(channel.id, 1, null, "СТАЖЁР")
            assertEquals(2, first.total)
            assertEquals(30L, first.messages.single().id)
            val second = repo.historyMessages(channel.id, 1, first.nextCursor, "СТАЖЁР")
            assertEquals(20L, second.messages.single().id)
            assertNull(second.nextCursor)
            assertEquals(1, repo.historyMessages(channel.id, 50, null, "100%").total)
            assertEquals(4, repo.historyMessages(channel.id, 50, null, "").total)
            assertEquals(0, repo.historyMessages(channel.id, 50, null, "not present").total)
        }
    }
    @Test fun migratesVersionOneWithoutLosingResearchData() {
        val path = database()
        Repositories(path).use { repo -> repo.saveChannel(channel) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            s.execute("DROP TABLE imported_messages"); s.execute("DROP TABLE history_sync");
            listOf("local_monitoring_limits", "local_matches", "local_subscriptions", "local_project_publications", "local_source_membership", "local_source_sync", "local_publications").forEach { s.execute("DROP TABLE $it") }; s.execute("DROP TABLE telegram_sources"); s.execute("DROP TABLE bot_access"); s.execute("DROP TABLE bot_invitations"); s.execute("DROP TABLE mcp_settings"); s.execute("DROP TABLE mcp_sources"); s.execute("DROP TABLE mcp_audit"); s.execute("DROP TABLE bot_publish_request"); s.execute("DROP TABLE bot_publish_permission"); s.execute("DROP TABLE bot_outbox"); s.execute("DROP TABLE bot_recipients"); s.execute("DROP TABLE bot_state"); s.execute("DROP TABLE bot_workspace"); s.execute("DROP TABLE publication_decisions"); s.execute("DROP INDEX matches_date"); s.execute("ALTER TABLE matched_messages DROP COLUMN message_date"); s.execute("PRAGMA user_version=1")
        } }
        Repositories(path).use { repo ->
            assertEquals(channel, repo.channels().single())
            assertTrue(repo.historySyncs().isEmpty())
            repo.beginHistorySync(channel)
        }
        Repositories(path).use { repo -> assertEquals(channel.id, repo.historySyncs().single().channelId) }
    }
}
