package dev.radar.sources

import dev.radar.history.*
import dev.radar.research.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class LocalMonitoringMigrationTest {
    private fun v11(): Path {
        val path = Files.createTempDirectory("local-monitor-v11-").resolve("db.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s -> javaClass.getResource("/schema-v11.sql")!!.readText().lines()
                .filter { it.isNotBlank() && !it.startsWith("--") }.forEach(s::execute) }
            fun insert(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate()
            }
            val source = TelegramSource(key = "fixture-source", chatId = 123, type = SourceType.PRIVATE, title = "Synthetic cloud")
            val publication = ResearchMessage(42, 123, 1700000000, "Synthetic Java Ktor", source.title, null, sourceKey = source.key)
            val template = SearchTemplate(id = "fixture-rule", name = "Synthetic", required = listOf("Java"), telegramQuery = "Java",
                sourceMode = "selected", sources = listOf(-1001), subscribed = true, subscriptionSince = 1699999999)
            val criteria = ProjectInput(name = "Synthetic project", sourceKeys = listOf(source.key))
            val project = RecruitmentProject("fixture-project", criteria, 1, 2)
            val card = RecruitmentCard("fixture-card", project.id, publication, notes = "Synthetic preserved note", interest = 4,
                classification = Classification(PublicationKind.INSUFFICIENT, "Synthetic reason"), createdAt = 1, updatedAt = 2)
            insert("INSERT INTO telegram_sources VALUES(?,?,?,?,?,?)", source.key, source.chatId, "CHAT", 0, null, Json.encodeToString(source))
            insert("INSERT INTO search_templates VALUES(?,?)", template.id, Json.encodeToString(template))
            val publicChannel = MonitoredChannel(-1001, "synthetic_jobs", "Synthetic legacy public")
            val legacyMatch = NotificationRecord("fixture-match", template.id, template.name,
                ResearchMessage(8, publicChannel.id, 1700000000, "Legacy synthetic", publicChannel.title, publicChannel.username))
            insert("INSERT INTO monitored_channels VALUES(?,?,?)", publicChannel.id, publicChannel.username, Json.encodeToString(publicChannel))
            val legacySource = TelegramSource(key = "fixture-legacy-source", chatId = publicChannel.id, type = SourceType.CHANNEL,
                title = publicChannel.title, username = publicChannel.username, isPublic = true, legacyChannel = true)
            insert("INSERT INTO telegram_sources VALUES(?,?,?,?,?,?)", legacySource.key, legacySource.chatId, "CHAT", 0, publicChannel.id, Json.encodeToString(legacySource))
            insert("INSERT INTO template_sources VALUES(?,?)", template.id, publicChannel.id)
            insert("INSERT INTO monitoring_state VALUES(?,?)", publicChannel.id, Json.encodeToString(MonitoringState(publicChannel.id, lastMessageId = 8, recoveryCursor = 6, recoveryHead = 10)))
            insert("INSERT INTO matched_messages(id,template_id,channel_id,message_id,data,message_date) VALUES(?,?,?,?,?,?)",
                legacyMatch.id, template.id, publicChannel.id, 8, Json.encodeToString(legacyMatch), 1700000000)
            insert("INSERT INTO notifications VALUES(?,?,?)", legacyMatch.id, 1, 1700000000000L)
            insert("INSERT INTO mcp_sources VALUES(?,?,?)", publicChannel.id, publicChannel.username, publicChannel.title)
            insert("INSERT INTO local_publications VALUES(?,?,?,?,?)", 123, 42, publication.date, "synthetic java ktor", Json.encodeToString(publication))
            insert("INSERT INTO local_source_membership VALUES(?,?,?)", source.key, 123, 42)
            insert("INSERT INTO local_source_sync VALUES(?,?)", source.key, Json.encodeToString(SourceSyncRecord(source.key, 123, source.title, status = SyncStatus.PAUSED, cursor = 42, messageCount = 1, pauseReason = PauseReason.USER)))
            insert("INSERT INTO recruitment_projects VALUES(?,?,?,?,?,?)", project.id, "ACTIVE", "synthetic project", Json.encodeToString(project), 1, 2)
            insert("INSERT INTO local_project_publications VALUES(?,?,?,?)", project.id, source.key, 123, 42)
            insert("INSERT INTO recruitment_cards VALUES(?,?,?,?,?,?,?,?,?,?,?)", card.id, project.id, 123, 42, "NEW", 4, "synthetic java ktor", "synthetic preserved note", Json.encodeToString(card), 1, 2)
            insert("INSERT INTO imported_messages VALUES(?,?,?,?,?)", -1001, 8, 1700000000, "Legacy synthetic", "legacy synthetic")
            insert("INSERT INTO bot_outbox(bot_id,event_key,user_id,chat_id,payload) VALUES(?,?,?,?,?)", 1, "synthetic", 2, 2, "synthetic pending payload")
        }
        return path
    }
    private fun snapshot(path: Path): Map<String, List<List<String?>>> = DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        val tables = db.createStatement().use { s -> s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('local_subscriptions','local_matches','local_monitoring_limits')").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
        tables.associateWith { name -> db.createStatement().use { s -> s.executeQuery("SELECT * FROM $name ORDER BY rowid").use { rows -> buildList { while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString)) } } } }
    }
    @Test fun actualV11ForwardMigrationPreservesArchiveCrmCursorsQueueAndDisabledDefaultsAcrossReopen() {
        val path = v11(); val before = snapshot(path)
        repeat(2) { Repositories(path).use { repo ->
            assertEquals(1, repo.sourceArchive("fixture-source").total)
            assertEquals(42L, repo.sourceSync("fixture-source")!!.cursor)
            val card = repo.recruitmentCard("fixture-project", "fixture-card")!!
            assertEquals(4, card.interest); assertEquals("Synthetic preserved note", card.notes)
            assertTrue(repo.localSubscriptions().isEmpty()); assertTrue(repo.localMatches().items.isEmpty())
            assertTrue(repo.template("fixture-rule")!!.subscribed)
            assertEquals(8L, repo.notifications().single().message.id)
        } }
        assertEquals(before, snapshot(path))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(12, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
        } }
    }
    @Test fun failedV12DdlRollsBackNewTablesVersionAndAllowsRetry() {
        val path = v11(); val before = snapshot(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("CREATE VIEW local_matches AS SELECT 1") } }
        assertFails { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(11, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='local_subscriptions'").use { it.next(); it.getInt(1) })
            s.execute("DROP VIEW local_matches")
        } }
        assertEquals(before, snapshot(path))
        Repositories(path).close(); assertEquals(before, snapshot(path))
    }
    @Test fun futureSchemaIsRefusedWithoutChangingPreservedV11Rows() {
        val path = v11(); val before = snapshot(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("PRAGMA user_version=13") } }
        assertFailsWith<IllegalStateException> { Repositories(path).close() }
        assertEquals(before, snapshot(path))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(13, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='local_subscriptions'").use { it.next(); it.getInt(1) })
        } }
    }
}
