package dev.radar.profiles

import dev.radar.desktop.RuntimePaths
import dev.radar.desktop.SessionLease
import dev.radar.research.Repositories
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.*

class ProfileStoreTest {
    private fun fixture(block: (Path) -> Unit) {
        val home = Files.createTempDirectory("hradar-profiles-").toRealPath()
        try { block(home) } finally { home.toFile().deleteRecursively() }
    }
    @Test fun `A B A retains separate paths identities and durable selection`() = fixture { home ->
        val store = ProfileStore(home); store.prepare()
        val a = store.active(); val b = store.create()
        assertEquals(a, store.active())
        store.bind(a.id, ProfileIdentity(101, "Synthetic A"), false)
        store.bind(b.id, ProfileIdentity(202, "Synthetic B"), false)
        Files.writeString(RuntimePaths(home, a.id).documents.resolve("a.txt"), "A")
        store.select(b.id)
        assertFalse(Files.exists(RuntimePaths(home, b.id).documents.resolve("a.txt")))
        store.select(a.id)
        val reopened = ProfileStore(home); reopened.prepare()
        assertEquals(101L, reopened.active().identity?.userId)
        assertEquals("A", Files.readString(RuntimePaths(home, a.id).documents.resolve("a.txt")))
        assertTrue(reopened.active().confirmed)
        assertEquals(RuntimePaths(home, a.id).credentials, RuntimePaths(home, b.id).credentials)
        assertNotEquals(RuntimePaths(home, a.id).botConfiguration, RuntimePaths(home, b.id).botConfiguration)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(RuntimePaths(home, a.id).avatars.parent))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(home.resolve(".hradar/profiles.json")))
    }
    @Test fun `mismatched and duplicate identities fail and logout retains binding`() = fixture { home ->
        val store = ProfileStore(home); store.prepare(); val a = store.active(); val b = store.create()
        store.bind(a.id, ProfileIdentity(101, "A"), false)
        assertFailsWith<IllegalStateException> { store.bind(a.id, ProfileIdentity(202, "B"), true) }
        assertFailsWith<IllegalStateException> { store.bind(b.id, ProfileIdentity(101, "A"), true) }
        store.markLoggedOut(a.id)
        assertTrue(store.active().loggedOut)
        assertEquals(101L, store.active().identity?.userId)
        assertFalse(store.bind(a.id, ProfileIdentity(101, "Renamed"), false).loggedOut)
    }
    @Test fun `old external session never resurrects automatically`() = fixture { home ->
        val old = home.resolve(".recruitment-radar/tdlib")
        Files.createDirectories(old); Files.writeString(old.resolve("td.binlog"), "synthetic")
        val store = ProfileStore(home); store.prepare(); val fresh = store.active()
        assertTrue(store.legacyAvailable()); assertFalse(fresh.legacy)
        assertFalse(Files.exists(RuntimePaths(home, fresh.id).session.resolve("td.binlog")))
        val imported = store.importLegacy()
        assertTrue(imported.legacy); assertFalse(imported.confirmed)
        assertEquals(fresh.id, store.active().id)
        assertFalse(store.legacyAvailable())
        assertFailsWith<IllegalStateException> { store.bind(imported.id, ProfileIdentity(101, "A"), false) }
        store.bind(imported.id, ProfileIdentity(101, "A"), true)
        assertFailsWith<IllegalStateException> { store.confirmLegacy(imported.id, 202) }
        assertTrue(store.confirmLegacy(imported.id, 101).confirmed)
        assertTrue(Files.exists(old.resolve("td.binlog")))
        store.markLoggedOut(imported.id)
        ProfileStore(home).prepare()
        assertFalse(store.legacyAvailable())
    }
    @Test fun `current schema14 archive documents bot and FTS migrate together retaining originals`() = fixture { home ->
        val original = RuntimePaths(home)
        Files.createDirectories(original.database.parent)
        Repositories(original.database).close()
        DriverManager.getConnection("jdbc:sqlite:${original.database}").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE VIRTUAL TABLE synthetic_fts USING fts5(text)")
                sql.execute("INSERT INTO synthetic_fts(rowid,text) VALUES(79,'synthetic searchable')")
            }
        }
        Files.createDirectories(original.session); Files.writeString(original.session.resolve("td.binlog"), "synthetic")
        Files.createDirectories(original.documents); Files.writeString(original.documents.resolve("synthetic.txt"), "document")
        Files.createDirectories(original.botConfiguration.parent); Files.writeString(original.botConfiguration, "synthetic local bot config")
        val store = ProfileStore(home); store.prepare(); val migrated = store.active()
        assertTrue(migrated.legacy); assertFalse(migrated.confirmed)
        val paths = RuntimePaths(home, migrated.id)
        assertTrue(Files.exists(original.database))
        assertEquals("document", Files.readString(paths.documents.resolve("synthetic.txt")))
        assertEquals("synthetic local bot config", Files.readString(paths.botConfiguration))
        DriverManager.getConnection("jdbc:sqlite:${paths.database}").use { connection ->
            connection.createStatement().use { sql ->
                assertEquals(79, sql.executeQuery("SELECT rowid FROM synthetic_fts WHERE synthetic_fts MATCH 'searchable'").use { it.next(); it.getInt(1) })
            }
        }
        ProfileStore(home).prepare()
        assertEquals(migrated.id, store.active().id)
        assertEquals(1, store.list().size)
        assertTrue(Files.exists(home.resolve(".hradar/backups/profile-${migrated.id}/data/research.sqlite")))
    }
    @Test fun `retained WAL migrates committed FTS rows without copying SHM`() = fixture { home ->
        val original = RuntimePaths(home)
        Files.createDirectories(original.database.parent)
        Repositories(original.database).close()
        val savedDb = home.resolve("synthetic-main.sqlite")
        val savedWal = home.resolve("synthetic-wal")
        DriverManager.getConnection("jdbc:sqlite:${original.database}").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute("PRAGMA wal_autocheckpoint=0")
                sql.execute("CREATE VIRTUAL TABLE synthetic_wal_fts USING fts5(text)")
                sql.execute("INSERT INTO synthetic_wal_fts(rowid,text) VALUES(83,'synthetic wal searchable')")
                Files.copy(original.database, savedDb)
                Files.copy(Path.of("${original.database}-wal"), savedWal)
            }
        }
        // Restore a crash-style committed WAL after closing every fixture connection.
        Files.copy(savedDb, original.database, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.copy(savedWal, Path.of("${original.database}-wal"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(Path.of("${original.database}-shm"))
        val store = ProfileStore(home); store.prepare()
        val migrated = RuntimePaths(home, store.active().id)
        assertEquals(-1L, Files.mismatch(savedDb, original.database))
        assertEquals(-1L, Files.mismatch(savedWal, Path.of("${original.database}-wal")))
        assertFalse(Files.exists(Path.of("${migrated.database}-shm")))
        DriverManager.getConnection("jdbc:sqlite:${migrated.database}").use { connection ->
            connection.createStatement().use { sql ->
                assertEquals(83, sql.executeQuery("SELECT rowid FROM synthetic_wal_fts WHERE synthetic_wal_fts MATCH 'searchable'").use { it.next(); it.getInt(1) })
            }
        }
    }
    @Test fun `pending migration refuses changed originals and backup`() = fixture { home ->
        val root = home.resolve(".hradar"); val id = "10d4d5d1-51cd-4aac-9f23-2c7754eb92fa"
        Files.createDirectories(root.resolve("tdlib")); val original = root.resolve("tdlib/td.binlog")
        Files.writeString(original, "synthetic original")
        Files.createDirectories(root.resolve("profiles/$id"))
        Files.writeString(root.resolve("profiles/$id/unexpected.txt"), "synthetic conflict")
        Files.writeString(root.resolve("profile-migration.json"), """{"id":"$id","external":false}""")
        val store = ProfileStore(home)
        assertFailsWith<IllegalStateException> { store.prepare() }
        assertTrue(Files.readString(root.resolve("profile-migration.json")).contains("backupFingerprint"))
        Files.writeString(original, "changed original")
        assertContains(assertFailsWith<IllegalStateException> { store.prepare() }.message.orEmpty(), "Originals changed")
        Files.writeString(original, "synthetic original")
        Files.writeString(root.resolve("backups/profile-$id/tdlib/td.binlog"), "changed backup")
        assertContains(assertFailsWith<IllegalStateException> { store.prepare() }.message.orEmpty(), "backup changed")
        assertFalse(Files.exists(root.resolve("profiles.json")))
    }
    @Test fun `locked old session and open source files refuse migration`() = fixture { home ->
        val old = home.resolve(".hradar/tdlib"); Files.createDirectories(old)
        SessionLease.acquire(old)!!.use { assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() } }
        Files.writeString(old.resolve("td.binlog"), "synthetic")
        java.nio.channels.FileChannel.open(old.resolve("td.binlog")).use {
            assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() }
        }
        ProfileStore(home).prepare()
    }
    @Test fun `manifest resumes after workspace publication before registry commit`() = fixture { home ->
        val root = home.resolve(".hradar"); val id = "10d4d5d1-51cd-4aac-9f23-2c7754eb92fa"
        Files.createDirectories(root.resolve("tdlib")); Files.writeString(root.resolve("tdlib/td.binlog"), "synthetic")
        Files.createDirectories(root.resolve("backups/profile-$id/tdlib"))
        Files.writeString(root.resolve("backups/profile-$id/tdlib/td.binlog"), "synthetic")
        Files.createDirectories(root.resolve("profiles/$id/tdlib"))
        Files.writeString(root.resolve("profiles/$id/tdlib/td.binlog"), "synthetic")
        Files.writeString(root.resolve("profile-migration.json"), """{"id":"$id","external":false}""")
        val store = ProfileStore(home); store.prepare(); store.prepare()
        assertEquals(id, store.active().id); assertEquals(1, store.list().size)
        assertFalse(Files.exists(root.resolve("profile-migration.json")))
    }
    @Test fun `unknown schema symlinks and invalid profile paths fail closed`() = fixture { home ->
        val root = home.resolve(".hradar"); Files.createDirectories(root.resolve("data"))
        DriverManager.getConnection("jdbc:sqlite:${root.resolve("data/research.sqlite")}").use { connection -> connection.createStatement().use { it.execute("PRAGMA user_version=99") } }
        assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() }
        assertTrue(Files.exists(root.resolve("data/research.sqlite")))
        assertFailsWith<IllegalArgumentException> { RuntimePaths(home, "../escape") }
        Files.delete(root.resolve("data/research.sqlite"))
        Files.delete(root.resolve("profile-migration.json"))
        Files.createSymbolicLink(root.resolve("tdlib"), home)
        assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() }
    }
    @Test fun `completed migration refuses later old version writes but ignores SQLite coordination files`() = fixture { home ->
        val original = RuntimePaths(home)
        Files.createDirectories(original.database.parent)
        Repositories(original.database).close()
        val store = ProfileStore(home); store.prepare()
        val active = store.active()
        val receipt = home.resolve(".hradar/current-profile-migrated.json")
        assertTrue(Files.exists(receipt))
        val receiptText = Files.readString(receipt)
        assertContains(receiptText, "originals")
        assertContains(receiptText, "backupFingerprint")
        Files.writeString(Path.of("${original.database}-shm"), "synthetic coordination state")
        ProfileStore(home).prepare()
        assertEquals(active.id, store.active().id)
        // Emulate a closed older build modifying its retained database after migration.
        DriverManager.getConnection("jdbc:sqlite:${original.database}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE old_version_write(value TEXT)") }
        }
        val error = assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() }
        assertContains(error.message.orEmpty(), "Retained originals changed")
        assertEquals(receiptText, Files.readString(receipt))
        assertEquals(active.id, store.active().id)
    }
    @Test fun `missing registry never recreates profiles from retained original sessions`() {
        for (migrated in listOf(false, true)) fixture { home ->
            val root = home.resolve(".hradar")
            if (migrated) {
                Files.createDirectories(root.resolve("tdlib"))
                Files.writeString(root.resolve("tdlib/td.binlog"), "synthetic original session")
            }
            val store = ProfileStore(home); store.prepare()
            val profile = store.active()
            Files.delete(root.resolve("profiles.json"))
            if (migrated) root.resolve("profiles").toFile().deleteRecursively()
            val error = assertFailsWith<IllegalStateException> { ProfileStore(home).prepare() }
            assertContains(error.message.orEmpty(), "Profile registry is missing")
            assertFalse(Files.exists(root.resolve("profiles.json")))
            if (migrated) {
                assertTrue(Files.exists(root.resolve("current-profile-migrated.json")))
                assertEquals("synthetic original session", Files.readString(root.resolve("tdlib/td.binlog")))
                assertFalse(Files.exists(root.resolve("profiles/${profile.id}")))
            } else assertTrue(Files.exists(root.resolve("profiles/${profile.id}")))
        }
    }
    @Test fun `profile path preparation never invokes external legacy migration`() = fixture { home ->
        Files.createDirectories(home.resolve(".recruitment-radar/tdlib"))
        Files.writeString(home.resolve(".recruitment-radar/tdlib/td.binlog"), "synthetic")
        val paths = RuntimePaths(home, "10d4d5d1-51cd-4aac-9f23-2c7754eb92fa"); paths.prepare()
        assertFalse(Files.exists(paths.session.resolve("td.binlog")))
    }
    @Test fun `pending legacy confirmation permits only its previously proven owner after logout`() = fixture { home ->
        val old=RuntimePaths(home);Files.createDirectories(old.session);Files.writeString(old.session.resolve("td.binlog"),"synthetic session")
        val store=ProfileStore(home);store.prepare();val id=store.active().id
        assertFails {store.bind(id,ProfileIdentity(10,"Unknown login"),false)}
        store.bind(id,ProfileIdentity(10,"Proven retained owner"),true)
        store.markLoggedOut(id)
        assertFails {store.bind(id,ProfileIdentity(20,"Other account"),false)}
        assertFalse(store.bind(id,ProfileIdentity(10,"Same owner after login"),false).confirmed)
        assertTrue(store.confirmLegacy(id,10).confirmed)
    }

}
