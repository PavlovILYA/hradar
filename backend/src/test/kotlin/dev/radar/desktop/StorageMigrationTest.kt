package dev.radar.desktop

import dev.radar.research.Repositories
import dev.radar.telegram.ApiCredentials
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.*

class StorageMigrationTest {
    private fun fixture(block: (RuntimePaths) -> Unit) {
        val home = Files.createTempDirectory("hradar-storage-")
        try { block(RuntimePaths(home)) } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `clean install creates only new private directories`() = fixture { paths ->
        paths.prepare()
        assertTrue(Files.isDirectory(paths.data))
        assertTrue(Files.isDirectory(paths.session))
        assertTrue(Files.isDirectory(paths.database.parent))
        assertTrue(Files.isDirectory(paths.credentials.parent))
        assertEquals(paths.data.resolve("config/telegram-bot.json"), paths.botConfiguration)
        assertFalse(Files.exists(paths.home.resolve(".recruitment-radar")))
    }

    @Test fun `legacy database credentials and TDLib tree survive migration and restart`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        Repositories(oldDb).use { }
        val oldCredentials = paths.data.resolve("telegram-api.json")
        Files.writeString(oldCredentials, """{"apiId":12345,"apiHash":"${"a".repeat(32)}"}""")
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession.resolve("files"))
        Files.writeString(oldSession.resolve("td.binlog"), "synthetic-binlog")
        Files.writeString(oldSession.resolve("files/cache.sqlite"), "synthetic-sqlite")
        paths.prepare()
        assertEquals("synthetic-binlog", Files.readString(paths.session.resolve("td.binlog")))
        assertEquals("synthetic-sqlite", Files.readString(paths.session.resolve("files/cache.sqlite")))
        assertEquals(10, DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } } })
        assertEquals(ApiCredentials(12345, "a".repeat(32)), paths.loadCredentials())
        assertTrue(Files.exists(oldDb))
        assertTrue(Files.exists(oldSession.resolve("td.binlog")))
        assertTrue(Files.walk(paths.backups).use { it.anyMatch { file -> file.fileName.toString() == "td.binlog" } })
        paths.prepare()
        assertEquals("synthetic-binlog", Files.readString(paths.session.resolve("td.binlog")))
    }

    @Test fun `conflicting new and old databases fail closed`() = fixture { paths ->
        Files.createDirectories(paths.database.parent)
        Files.writeString(paths.database, "new")
        Files.writeString(paths.data.resolve("research.sqlite"), "old")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertEquals("new", Files.readString(paths.database))
        assertEquals("old", Files.readString(paths.data.resolve("research.sqlite")))
    }

    @Test fun `saved bot configuration migrates privately and survives restart`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val old = paths.data.resolve("telegram-bot.json")
        val synthetic = """{"token":"123:synthetic","identity":{"id":123,"username":"fake_bot","name":"Fake Bot"},"enabled":true}"""
        Files.writeString(old, synthetic)
        paths.prepare()
        assertEquals(synthetic, Files.readString(paths.botConfiguration))
        assertEquals(synthetic, Files.readString(old))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(paths.botConfiguration))
        assertTrue(Files.walk(paths.backups).use { stream -> stream.anyMatch { it.fileName.toString() == "telegram-bot.json" } })
        paths.prepare()
        assertEquals(synthetic, Files.readString(paths.botConfiguration))
    }

    @Test fun `conflicting bot configuration is refused without overwriting`() = fixture { paths ->
        Files.createDirectories(paths.data)
        Files.createDirectories(paths.botConfiguration.parent)
        Files.writeString(paths.data.resolve("telegram-bot.json"), "old")
        Files.writeString(paths.botConfiguration, "new")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertEquals("new", Files.readString(paths.botConfiguration))
    }

    @Test fun `completed storage migration still picks up old bot settings`() = fixture { paths ->
        Files.createDirectories(paths.data)
        Repositories(paths.data.resolve("research.sqlite")).use { }
        paths.prepare()
        assertTrue(Files.exists(paths.data.resolve("storage-migration-v1.complete")))
        Files.writeString(paths.data.resolve("telegram-bot.json"), "synthetic-bot-settings")
        paths.prepare()
        assertEquals("synthetic-bot-settings", Files.readString(paths.botConfiguration))
    }

    @Test fun `locked legacy session fails before copying`() = fixture { paths ->
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession)
        val channel = FileChannel.open(oldSession.resolve("hradar.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel.lock().use {
            assertFailsWith<IllegalStateException> { paths.prepare() }
            assertFalse(Files.exists(paths.session.resolve("td.binlog")))
        }
        channel.close()
    }

    @Test fun `corrupt old database fails without creating target`() = fixture { paths ->
        Files.createDirectories(paths.data)
        Files.writeString(paths.data.resolve("research.sqlite"), "not sqlite")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.database))
    }

    @Test fun `incomplete prior copy resumes without replacing original`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        Repositories(oldDb).use { }
        Files.createDirectories(paths.database.parent)
        Files.copy(oldDb, paths.database)
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession)
        Files.writeString(oldSession.resolve("td.binlog"), "session")
        paths.prepare()
        assertEquals("session", Files.readString(paths.session.resolve("td.binlog")))
        assertTrue(Files.exists(oldDb))
        paths.prepare()
    }

    @Test fun `WAL sidecars are copied with SQLite before opening repository`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        Repositories(oldDb).use { }
        val connection = DriverManager.getConnection("jdbc:sqlite:$oldDb")
        val snapshot = Files.createTempDirectory(paths.home, "wal-snapshot-")
        try {
            connection.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA wal_autocheckpoint=0")
                it.execute("INSERT INTO imported_messages VALUES (-1001,1,1,'preserved','preserved')")
            }
            assertTrue(Files.exists(oldDb.resolveSibling("research.sqlite-wal")))
            assertFailsWith<IllegalStateException> { paths.prepare() }
            for (suffix in listOf("", "-wal", "-shm")) {
                val source = oldDb.resolveSibling("research.sqlite$suffix")
                if (Files.exists(source)) Files.copy(source, snapshot.resolve(source.fileName))
            }
        } finally { connection.close() }
        Files.list(snapshot).use { it.forEach { file -> Files.copy(file, oldDb.resolveSibling(file.fileName), StandardCopyOption.REPLACE_EXISTING) } }
        paths.prepare()
        Repositories(paths.database).use { repo ->
            DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { db ->
                db.createStatement().use { s ->
                    assertEquals(1, s.executeQuery("SELECT count(*) FROM imported_messages").use { it.next(); it.getInt(1) })
                    assertEquals("ok", s.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) })
                }
            }
        }
    }

    @Test fun `invalid old credentials fail before target creation`() = fixture { paths ->
        Files.createDirectories(paths.data)
        Files.writeString(paths.data.resolve("telegram-api.json"), "invalid")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.credentials))
    }

    @Test fun `copied secrets and directories have private permissions`() = fixture { paths ->
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession)
        Files.writeString(oldSession.resolve("td.binlog"), "synthetic")
        paths.prepare()
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(paths.session))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(paths.session.resolve("td.binlog")))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(Files.walk(paths.backups).use { stream -> stream.filter { it.fileName.toString() == "td.binlog" }.findFirst().orElseThrow() }))
    }

    @Test fun `symlinked old session is refused`() = fixture { paths ->
        val external = Files.createDirectory(paths.home.resolve("unrelated"))
        Files.createDirectory(paths.home.resolve(".recruitment-radar"))
        Files.createSymbolicLink(paths.home.resolve(".recruitment-radar/tdlib"), external)
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.session))
    }

    @Test fun `completed migration never silently recreates a missing session`() = fixture { paths ->
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession)
        Files.writeString(oldSession.resolve("td.binlog"), "synthetic")
        paths.prepare()
        paths.session.toFile().deleteRecursively()
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.session))
    }

    @Test fun `completion marker protects session even after old directory is removed`() = fixture { paths ->
        val oldRoot = paths.home.resolve(".recruitment-radar")
        val oldSession = oldRoot.resolve("tdlib")
        Files.createDirectories(oldSession)
        Files.writeString(oldSession.resolve("td.binlog"), "synthetic")
        paths.prepare()
        oldRoot.toFile().deleteRecursively()
        paths.session.toFile().deleteRecursively()
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.session))
    }

    @Test fun `orphaned SQLite WAL refuses empty database creation`() = fixture { paths ->
        Files.createDirectories(paths.data)
        Files.writeString(paths.data.resolve("research.sqlite-wal"), "orphan")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.database))
    }

    @Test fun `writes by an old binary after migration are detected before new startup`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        Repositories(oldDb).use { }
        val oldSession = paths.home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(oldSession)
        Files.writeString(oldSession.resolve("td.binlog"), "before")
        paths.prepare()
        Repositories(oldDb).use { repo ->
            DriverManager.getConnection("jdbc:sqlite:$oldDb").use { db ->
                db.createStatement().use { it.execute("INSERT INTO imported_messages VALUES (-1001,2,2,'late','late')") }
            }
        }
        Files.writeString(oldSession.resolve("td.binlog"), "after")
        assertFailsWith<IllegalStateException> { paths.prepare() }
        DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { db ->
            db.createStatement().use { s ->
                assertEquals(0, s.executeQuery("SELECT count(*) FROM imported_messages").use { it.next(); it.getInt(1) })
            }
        }
    }

    @Test fun `old database opened after migration blocks concurrent new startup`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        Repositories(oldDb).use { }
        paths.prepare()
        DriverManager.getConnection("jdbc:sqlite:$oldDb").use {
            assertFailsWith<IllegalStateException> { paths.prepare() }
        }
    }

    @Test fun `SQLite with v8 header but missing schema is rejected`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val oldDb = paths.data.resolve("research.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$oldDb").use { db ->
            db.createStatement().use { it.execute("PRAGMA user_version=8") }
        }
        assertFailsWith<IllegalStateException> { paths.prepare() }
        assertFalse(Files.exists(paths.database))
    }

    @Test fun `unreadable legacy configuration is not replaced with empty settings`() = fixture { paths ->
        Files.createDirectories(paths.data)
        val source = paths.data.resolve("telegram-api.json")
        Files.writeString(source, """{"apiId":12345,"apiHash":"${"a".repeat(32)}"}""")
        Files.setPosixFilePermissions(source, emptySet())
        try {
            assertFailsWith<IllegalStateException> { paths.prepare() }
            assertFalse(Files.exists(paths.credentials))
        } finally { Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------")) }
    }
}
