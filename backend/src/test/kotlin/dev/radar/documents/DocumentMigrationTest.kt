package dev.radar.documents

import dev.radar.research.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class DocumentMigrationTest {
    private fun previousSchema(path: java.nio.file.Path) {
        Repositories(path).use { repo ->
            repo.saveChannel(MonitoredChannel(1,"synthetic_jobs","Synthetic"))
            val rule=repo.saveTemplate(SearchTemplate(name="Preserved",required=listOf("Java"),telegramQuery="Java"))
            repo.recordMatch(rule,ResearchMessage(42,1,1700000000,"Synthetic Java","Synthetic","synthetic_jobs"))
            repo.readInbox(repo.inboxPage(10,Long.MAX_VALUE).items.single().id)
            repo.saveState(MonitoringState(1,42,40,42))
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
            s.execute("DROP TABLE document_fts");s.execute("DROP TABLE documents");s.execute("PRAGMA user_version=13")
        } }
    }
    @Test fun schema13RowsAndOriginalFilesSurviveForwardMigrationAndReopen() {
        val root=Files.createTempDirectory("document-migration-").toRealPath()
        try {
            val path=root.resolve("db");previousSchema(path)
            val original=Files.writeString(root.resolve("owner-original"),"synthetic file preserved")
            repeat(2) { Repositories(path).use { repo ->
                assertEquals("Preserved",repo.templates().single().name)
                assertEquals(0,repo.inboxUnread());assertTrue(repo.inboxPage(10,Long.MAX_VALUE).items.single().read)
                assertEquals(40L,repo.state(1)!!.recoveryCursor);assertEquals(0,repo.documents(10,0).total)
            } }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
                assertEquals(14,s.executeQuery("PRAGMA user_version").use { it.next();it.getInt(1) })
                assertEquals("ok",s.executeQuery("PRAGMA integrity_check").use { it.next();it.getString(1) })
            } }
            assertEquals("synthetic file preserved",Files.readString(original))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun failedDdlRollsBackNewSchemaAndRetryPreservesOldDataAndFiles() {
        val root=Files.createTempDirectory("document-rollback-").toRealPath()
        try {
            val path=root.resolve("db");previousSchema(path)
            val original=Files.writeString(root.resolve("owner-original"),"synthetic unchanged")
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { it.execute("CREATE VIEW document_fts AS SELECT 1") } }
            assertFails { Repositories(path).close() }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
                assertEquals(13,s.executeQuery("PRAGMA user_version").use { it.next();it.getInt(1) })
                assertEquals(0,s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='documents'").use { it.next();it.getInt(1) })
                s.execute("DROP VIEW document_fts")
            } }
            Repositories(path).use { assertEquals("Preserved",it.templates().single().name) }
            assertEquals("synthetic unchanged",Files.readString(original))
        } finally { root.toFile().deleteRecursively() }
    }
}
