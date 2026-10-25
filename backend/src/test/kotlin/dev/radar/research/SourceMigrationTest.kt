package dev.radar.research

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SourceMigrationTest {
    @Test fun migratesActualVersionNineSchemaWithoutRewritingExistingRows() {
        val path = Files.createTempDirectory("source-v9-").resolve("fixture.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val sql = javaClass.getResource("/schema-v9.sql")!!.readText()
            db.createStatement().use { s ->
                sql.lines().filter { it.isNotBlank() && !it.startsWith("--") }.forEach(s::execute)
                s.execute("INSERT INTO monitored_channels VALUES(-1001,'synthetic_jobs','{\"id\":-1001,\"username\":\"synthetic_jobs\",\"title\":\"Synthetic Jobs\"}')")
            }
        }
        Repositories(path).use { repo -> assertEquals(-1001L, repo.channels().single().id) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s ->
                assertEquals(11, s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
                assertEquals(1, s.executeQuery("SELECT COUNT(*) FROM telegram_sources").use { it.next(); it.getInt(1) })
            }
        }
    }
}
