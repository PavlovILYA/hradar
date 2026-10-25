package dev.radar.sources

import dev.radar.research.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class SourceStorageTest {
    private fun oldDatabase(): Path {
        val path = Files.createTempDirectory("source-old-db-").resolve("fixture.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            javaClass.getResource("/schema-v9.sql")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("--") }.forEach(s::execute)
            s.execute("INSERT INTO monitored_channels VALUES(-1001,'synthetic_jobs','{\"id\":-1001,\"username\":\"synthetic_jobs\",\"title\":\"Synthetic\"}')")
            s.execute("INSERT INTO search_templates VALUES('t','{}')")
            s.execute("INSERT INTO template_sources VALUES('t',-1001)")
            s.execute("INSERT INTO matched_messages VALUES('m','t',-1001,42,'{}',1700000000)")
            s.execute("INSERT INTO notifications VALUES('m',1,1700000000)")
            s.execute("INSERT INTO monitoring_state VALUES(-1001,'{\"channelId\":-1001,\"lastMessageId\":42,\"recoveryCursor\":30}')")
            s.execute("INSERT INTO imported_messages VALUES(-1001,42,1700000000,'synthetic body','synthetic body')")
            s.execute("INSERT INTO history_sync VALUES(-1001,'synthetic_jobs','PAUSED','{}')")
            s.execute("INSERT INTO recruitment_projects VALUES('p','ACTIVE','synthetic','{}',1,2)")
            s.execute("INSERT INTO recruitment_templates VALUES('p','t')")
            s.execute("INSERT INTO recruitment_cards VALUES('c','p',-1001,42,'NEW',2,'synthetic','note','{}',1,2)")
            s.execute("INSERT INTO recruitment_publications VALUES(-1001,42,'{}')")
            s.execute("INSERT INTO bot_recipients VALUES(1,2,2,1,1)")
            s.execute("INSERT INTO bot_state VALUES(1,42,3,4,5)")
            s.execute("INSERT INTO bot_outbox(bot_id,event_key,user_id,chat_id,payload) VALUES(1,'e',2,2,'synthetic pending payload')")
            s.execute("INSERT INTO bot_access VALUES(1,2,'owner','Synthetic',NULL)")
            s.execute("INSERT INTO bot_invitations VALUES('i',1,'synthetic-hash',42,NULL,NULL)")
            s.execute("INSERT INTO mcp_sources VALUES(-1001,'synthetic_jobs','Synthetic')")
        } }
        return path
    }
    private fun snapshot(path: Path): Map<String, List<List<String?>>> = DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        val names = db.createStatement().use { s -> s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='telegram_sources' AND name NOT LIKE 'local_%'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
        names.associateWith { name -> db.createStatement().use { s -> s.executeQuery("SELECT * FROM $name ORDER BY rowid").use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).map(r::getString)) } } } }
    }
    @Test fun forwardMigrationPreservesEveryLegacyTableIncludingPendingAndArchivedData() {
        val path = oldDatabase(); val before = snapshot(path)
        // Catalog migration doesn't deserialize templates/archive/CRM; their bytes stay intact.
        Repositories(path).use { assertEquals(-1001, it.channels().single().id.toInt()) }
        assertEquals(before, snapshot(path))
        Repositories(path).use { assertEquals(-1001, it.channels().single().id.toInt()) }
        assertEquals(before, snapshot(path))
    }
    @Test fun failedCatalogMigrationRollsBackVersionAndCanRetryWithoutReset() {
        val path = oldDatabase(); val before = snapshot(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("CREATE VIEW telegram_sources AS SELECT id FROM monitored_channels") } }
        assertFails { Repositories(path).close() }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            assertEquals(9, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
            s.execute("DROP VIEW telegram_sources")
        } }
        assertEquals(before, snapshot(path))
        Repositories(path).close()
        assertEquals(before, snapshot(path))
    }
    @Test fun duplicateStableIdsRenameTopicsRestartAndLegacySyncStayIsolated() {
        val path = Files.createTempDirectory("source-save-").resolve("db")
        var key = ""
        Repositories(path).use { repo ->
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
            val source = TelegramSource(chatId = 123, type = SourceType.PRIVATE, title = "Synthetic", username = "synthetic_old")
            key = repo.saveSource(source).key
            assertEquals(key, repo.saveSource(source.copy(username = null, title = "Renamed")).key)
            repo.saveSource(source.copy(chatId = -2001, type = SourceType.SUPERGROUP, isForum = true, scope = SourceScope(ScopeKind.FORUM_TOPIC, 1)))
            repo.saveSource(source.copy(chatId = -2001, type = SourceType.SUPERGROUP, isForum = true, scope = SourceScope(ScopeKind.FORUM_TOPIC, 2)))
            assertEquals(3, repo.sources().size)
            assertTrue(repo.channels().isEmpty()); assertTrue(repo.mcpSources().isEmpty()); assertTrue(repo.historySyncs().isEmpty())
            assertTrue(repo.notifications().isEmpty()); assertTrue(repo.templates().isEmpty())
            repo.saveChannel(MonitoredChannel(-1001, "synthetic_old", "Legacy"))
            val legacy = repo.sources().single { it.chatId == -1001L }
            repo.saveChannel(MonitoredChannel(-1001, "synthetic_new", "Legacy renamed"))
            assertEquals(legacy.key, repo.sources().single { it.chatId == -1001L }.key)
            assertEquals("synthetic_new", repo.sources().single { it.chatId == -1001L }.username)
            assertFailsWith<IllegalArgumentException> { repo.saveSource(legacy.copy(username = null, isPublic = false)) }
            assertTrue(repo.deleteSource(legacy.key)); assertTrue(repo.channels().isEmpty())
        }
        Repositories(path).use { repo ->
            assertEquals(key, repo.sources().single { it.scope.kind == ScopeKind.CHAT }.key)
            assertNull(repo.sources().single { it.key == key }.username)
            assertTrue(repo.deleteSource(key)); assertEquals(2, repo.sources().size)
            assertFalse(repo.deleteSource(key))
        }
    }
}
