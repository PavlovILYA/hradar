package dev.radar.sources

import dev.radar.history.*
import dev.radar.research.*
import dev.radar.telegram.TelegramMessage
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class SourceArchiveMigrationTest {
    // Stage A's exact additive DDL on the authentic v9 SQL fixture; no new-code initialization.
    private fun v10(): Path {
        val path = Files.createTempDirectory("source-archive-v10-").resolve("db.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            javaClass.getResource("/schema-v9.sql")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("--") }.forEach(s::execute)
            s.execute("CREATE TABLE telegram_sources(source_key TEXT PRIMARY KEY,chat_id INTEGER NOT NULL,scope_kind TEXT NOT NULL CHECK(scope_kind IN ('CHAT','FORUM_TOPIC','MESSAGE_THREAD')),scope_id INTEGER NOT NULL,legacy_channel_id INTEGER UNIQUE REFERENCES monitored_channels(id) ON DELETE CASCADE,data TEXT NOT NULL,UNIQUE(chat_id,scope_kind,scope_id))")
            s.execute("INSERT INTO monitored_channels VALUES(-1001,'synthetic_jobs','{\"id\":-1001,\"username\":\"synthetic_jobs\",\"title\":\"Synthetic\"}')")
            s.execute("INSERT INTO telegram_sources VALUES('stable-key',-1001,'CHAT',0,-1001,'{\"key\":\"stable-key\",\"chatId\":-1001,\"type\":\"CHANNEL\",\"title\":\"Synthetic\",\"username\":\"synthetic_jobs\",\"isPublic\":true,\"legacyChannel\":true}')")
            s.execute("INSERT INTO imported_messages VALUES(-1001,42,1700000000,'synthetic preserved','synthetic preserved')")
            s.execute("INSERT INTO history_sync VALUES(-1001,'synthetic_jobs','PAUSED','{\"channelId\":-1001,\"username\":\"synthetic_jobs\",\"status\":\"PAUSED\",\"cursor\":42,\"messageCount\":1}')")
            s.execute("INSERT INTO recruitment_projects VALUES('p','ACTIVE','synthetic','{}',1,2)")
            s.execute("INSERT INTO recruitment_cards VALUES('c','p',-1001,42,'NEW',2,'synthetic','note','{}',1,2)")
            s.execute("INSERT INTO recruitment_publications VALUES(-1001,42,'{}')")
            s.execute("INSERT INTO bot_outbox(bot_id,event_key,user_id,chat_id,payload) VALUES(1,'e',2,2,'synthetic pending payload')")
            s.execute("PRAGMA user_version=10")
        } }
        return path
    }
    private fun snapshot(path: Path) = DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        val tables = db.createStatement().use { s -> s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'local_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
        tables.associateWith { name -> db.createStatement().use { s -> s.executeQuery("SELECT * FROM $name ORDER BY rowid").use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map(r::getString)) } } } }
    }
    @Test fun forwardMigrationPreservesEveryV10RowAndLegacyArchiveRemainsVisibleBySourceKey() {
        val path = v10(); val before = snapshot(path)
        repeat(2) { Repositories(path).use { repo ->
            assertEquals(1, repo.sourceArchive("stable-key").total)
            assertEquals("synthetic preserved", repo.sourceArchive("stable-key").messages.single().text)
            assertEquals("stable-key", repo.sourceArchive("stable-key").messages.single().sourceKey)
            assertEquals(42L, repo.historySync(-1001)!!.cursor)
        } }
        assertEquals(before, snapshot(path))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(12, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
        } }
    }
    @Test fun migrationDdlFailureRollsBackAllNewTablesVersionAndAllowsRetry() {
        val path = v10(); val before = snapshot(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("CREATE VIEW local_source_membership AS SELECT 1") } }
        assertFails { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(10, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            assertEquals(0, s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='local_publications'").use { it.next(); it.getInt(1) })
            s.execute("DROP VIEW local_source_membership")
        } }
        Repositories(path).close()
        assertEquals(before, snapshot(path))
    }
    @Test fun localEditedLegacyMessageOverridesOriginalWithoutRewritingIt() {
        val path = v10()
        Repositories(path).use { repo ->
            val state = repo.beginSourceSync("stable-key")
            repo.saveSourcePage(state, listOf(ResearchMessage(42, -1001, 1700000001, "updated synthetic", "Synthetic", "synthetic_jobs", sourceKey = "stable-key")))
            assertEquals(1, repo.sourceArchive("stable-key").total)
            assertEquals("updated synthetic", repo.sourceArchive("stable-key").messages.single().text)
            assertEquals(0, repo.sourceArchive("stable-key", query = "preserved").total)
            assertEquals("synthetic preserved", repo.historyMessages(-1001, 50, null, "").messages.single().text)
        }
    }
}
